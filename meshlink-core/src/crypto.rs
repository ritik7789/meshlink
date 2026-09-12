use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use x25519_dalek::{EphemeralSecret, PublicKey};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Nonce,
};
use rand::rngs::OsRng;
use std::sync::Mutex;
use uniffi::{Object, export};

#[derive(Object)]
pub struct IdentityKeyPair {
    signing_key: SigningKey,
}

#[export]
impl IdentityKeyPair {
    #[uniffi::constructor]
    pub fn generate() -> Self {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        Self { signing_key }
    }

    #[uniffi::constructor]
    pub fn from_bytes(bytes: &[u8]) -> Result<std::sync::Arc<Self>, crate::MeshError> {
        if bytes.len() != 32 {
            return Err(crate::MeshError::DeserializeError);
        }
        let mut seed = [0u8; 32];
        seed.copy_from_slice(bytes);
        let signing_key = SigningKey::from_bytes(&seed);
        Ok(std::sync::Arc::new(Self { signing_key }))
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        self.signing_key.to_bytes().to_vec()
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.signing_key.verifying_key().to_bytes().to_vec()
    }

    pub fn sign(&self, data: &[u8]) -> Vec<u8> {
        self.signing_key.sign(data).to_bytes().to_vec()
    }
}

#[export]
pub fn verify_signature(public_key: &[u8], data: &[u8], signature: &[u8]) -> bool {
    if public_key.len() != 32 || signature.len() != 64 {
        return false;
    }
    
    let mut pub_key_bytes = [0u8; 32];
    pub_key_bytes.copy_from_slice(public_key);
    
    let mut sig_bytes = [0u8; 64];
    sig_bytes.copy_from_slice(signature);

    if let Ok(verifying_key) = VerifyingKey::from_bytes(&pub_key_bytes) {
        let sig = Signature::from_bytes(&sig_bytes);
        return verifying_key.verify_strict(data, &sig).is_ok();
    }
    false
}

#[derive(Object)]
pub struct EphemeralKeyPair {
    secret: Mutex<Option<EphemeralSecret>>,
    public_key: PublicKey,
}

#[export]
impl EphemeralKeyPair {
    #[uniffi::constructor]
    pub fn generate() -> Self {
        // use random() since we enabled getrandom feature for x25519-dalek
        let secret = EphemeralSecret::random();
        let public_key = PublicKey::from(&secret);
        Self { secret: Mutex::new(Some(secret)), public_key }
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.public_key.as_bytes().to_vec()
    }

    pub fn compute_shared_secret(&self, peer_public_key: &[u8]) -> Vec<u8> {
        if peer_public_key.len() != 32 {
            return vec![];
        }
        let mut pub_key_bytes = [0u8; 32];
        pub_key_bytes.copy_from_slice(peer_public_key);
        let peer_pub = PublicKey::from(pub_key_bytes);

        let secret = self.secret.lock().unwrap().take().expect("Secret already consumed");
        let shared_secret = secret.diffie_hellman(&peer_pub);
        shared_secret.as_bytes().to_vec()
    }
}

#[export]
pub fn encrypt_transport(key: &[u8], nonce: &[u8], plaintext: &[u8]) -> Vec<u8> {
    if key.len() != 32 || nonce.len() != 12 {
        return vec![];
    }
    let mut k = [0u8; 32];
    k.copy_from_slice(key);
    let mut n = [0u8; 12];
    n.copy_from_slice(nonce);

    let cipher = ChaCha20Poly1305::new(&k.into());
    let nonce_val = Nonce::from(n);
    cipher.encrypt(&nonce_val, plaintext).unwrap_or_default()
}

#[export]
pub fn decrypt_transport(key: &[u8], nonce: &[u8], ciphertext: &[u8]) -> Option<Vec<u8>> {
    if key.len() != 32 || nonce.len() != 12 {
        return None;
    }
    let mut k = [0u8; 32];
    k.copy_from_slice(key);
    let mut n = [0u8; 12];
    n.copy_from_slice(nonce);

    let cipher = ChaCha20Poly1305::new(&k.into());
    let nonce_val = Nonce::from(n);
    cipher.decrypt(&nonce_val, ciphertext).ok()
}

// ─────────────────────────────────────────────────────────────────────────────
// Stable node identity + end-to-end encryption
//
// `EphemeralKeyPair` above secures a single BLE hop: it is regenerated per
// connection and its secret is consumed by the first `compute_shared_secret`
// call. That is fine for link encryption but useless for multi-hop traffic,
// where the sender needs to encrypt for a node it may never connect to
// directly. `StaticKeyPair` fills that gap: a long-lived X25519 keypair
// derived deterministically from the node's persisted Ed25519 identity seed,
// so it survives restarts and can be published in presence announcements.
// ─────────────────────────────────────────────────────────────────────────────

use sha2::{Digest, Sha256};
use x25519_dalek::StaticSecret;

/// Domain separators keep the three values we derive from the same 32-byte
/// identity seed independent of one another.
const X25519_DERIVE_DOMAIN: &[u8] = b"meshlink-x25519-static-v1";
const BEACON_ID_DOMAIN: &[u8] = b"meshlink-beacon-id-v1";
const E2E_KDF_DOMAIN: &[u8] = b"meshlink-e2e-key-v1";

fn sha256_with_domain(domain: &[u8], data: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(domain);
    hasher.update(data);
    let mut out = [0u8; 32];
    out.copy_from_slice(&hasher.finalize());
    out
}

/// Derive a node's mesh address from its Ed25519 identity public key.
///
/// Because the identity key is persisted by the Android `KeyManager`, the
/// resulting id is stable across service restarts — which is what lets
/// conversation history, roster entries and cached display names survive a
/// process death. Never returns 0, which is reserved for the broadcast address.
#[export]
pub fn beacon_id_from_public_key(public_key: &[u8]) -> u32 {
    let digest = sha256_with_domain(BEACON_ID_DOMAIN, public_key);
    let id = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]);
    if id == 0 {
        1
    } else {
        id
    }
}

/// Long-lived X25519 keypair used for end-to-end encryption across relays.
#[derive(Object)]
pub struct StaticKeyPair {
    secret: StaticSecret,
    public_key: PublicKey,
}

#[export]
impl StaticKeyPair {
    /// Derives the keypair from the same 32-byte seed that backs the node's
    /// Ed25519 identity, so both peers can recompute it after a restart.
    #[uniffi::constructor]
    pub fn from_identity_seed(seed: &[u8]) -> Result<std::sync::Arc<Self>, crate::MeshError> {
        if seed.len() != 32 {
            return Err(crate::MeshError::DeserializeError);
        }
        let secret = StaticSecret::from(sha256_with_domain(X25519_DERIVE_DOMAIN, seed));
        let public_key = PublicKey::from(&secret);
        Ok(std::sync::Arc::new(Self { secret, public_key }))
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.public_key.as_bytes().to_vec()
    }

    /// Encrypt `plaintext` so that only the holder of `peer_public_key` can read
    /// it. Relay nodes forward the result without being able to open it.
    /// Returns `nonce || ciphertext`, or an empty vec if the peer key is invalid.
    pub fn seal(&self, peer_public_key: &[u8], plaintext: &[u8]) -> Vec<u8> {
        let key = match self.derive_shared_key(peer_public_key) {
            Some(k) => k,
            None => return vec![],
        };
        let mut nonce = [0u8; 12];
        use rand::RngCore;
        OsRng.fill_bytes(&mut nonce);

        let ciphertext = encrypt_transport(&key, &nonce, plaintext);
        if ciphertext.is_empty() {
            return vec![];
        }
        let mut out = Vec::with_capacity(12 + ciphertext.len());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ciphertext);
        out
    }

    /// Reverse of `seal`. Returns `None` if the payload was not sealed for us by
    /// the holder of `peer_public_key`, which also serves as implicit sender
    /// authentication: no relay can forge a payload that opens correctly.
    pub fn open(&self, peer_public_key: &[u8], sealed: &[u8]) -> Option<Vec<u8>> {
        if sealed.len() <= 12 {
            return None;
        }
        let key = self.derive_shared_key(peer_public_key)?;
        decrypt_transport(&key, &sealed[0..12], &sealed[12..])
    }
}

impl StaticKeyPair {
    /// X25519 DH followed by a hash so the raw curve point never becomes a key.
    /// Symmetric: both peers derive the same bytes from opposite directions.
    fn derive_shared_key(&self, peer_public_key: &[u8]) -> Option<[u8; 32]> {
        if peer_public_key.len() != 32 {
            return None;
        }
        let mut peer_bytes = [0u8; 32];
        peer_bytes.copy_from_slice(peer_public_key);
        let shared = self.secret.diffie_hellman(&PublicKey::from(peer_bytes));
        Some(sha256_with_domain(E2E_KDF_DOMAIN, shared.as_bytes()))
    }
}
