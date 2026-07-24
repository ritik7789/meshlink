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
