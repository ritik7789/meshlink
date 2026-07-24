use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{SystemTime, Duration};
use rand::Rng;

pub fn generate_beacon_id() -> u32 {
    let mut rng = rand::thread_rng();
    rng.gen()
}

#[derive(uniffi::Object)]
pub struct HandshakeCache {
    cache: Mutex<HashMap<u32, SystemTime>>,
}

#[uniffi::export]
impl HandshakeCache {
    #[uniffi::constructor]
    pub fn new() -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            cache: Mutex::new(HashMap::new()),
        })
    }

    pub fn should_handshake(&self, beacon_id: u32) -> bool {
        let cache = self.cache.lock().unwrap();
        if let Some(&time) = cache.get(&beacon_id) {
            if let Ok(elapsed) = time.elapsed() {
                if elapsed < Duration::from_secs(300) {
                    return false;
                }
            }
        }
        true
    }

    pub fn record_handshake(&self, beacon_id: u32) {
        let mut cache = self.cache.lock().unwrap();
        cache.insert(beacon_id, SystemTime::now());
    }

    pub fn cleanup_expired(&self) {
        let mut cache = self.cache.lock().unwrap();
        let now = SystemTime::now();
        cache.retain(|_, time| {
            if let Ok(elapsed) = now.duration_since(*time) {
                elapsed < Duration::from_secs(300)
            } else {
                false
            }
        });
    }
}

use crate::crypto::{IdentityKeyPair, EphemeralKeyPair, verify_signature};
use serde::{Serialize, Deserialize};
use crate::MeshError;

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct HandshakePayload {
    pub beacon_id: u32,
    pub identity_pub_key: Vec<u8>,
    pub ephemeral_pub_key: Vec<u8>,
    pub signature: Vec<u8>,
}

#[uniffi::export]
pub fn create_handshake_payload(beacon_id: u32, identity_key: &IdentityKeyPair, ephemeral_key: &EphemeralKeyPair) -> HandshakePayload {
    let ephemeral_pub_key = ephemeral_key.public_key();
    let signature = identity_key.sign(&ephemeral_pub_key);
    HandshakePayload {
        beacon_id,
        identity_pub_key: identity_key.public_key(),
        ephemeral_pub_key,
        signature,
    }
}

#[uniffi::export]
pub fn verify_handshake_payload(payload: HandshakePayload) -> bool {
    verify_signature(&payload.identity_pub_key, &payload.ephemeral_pub_key, &payload.signature)
}

#[uniffi::export]
pub fn serialize_handshake(payload: HandshakePayload) -> Vec<u8> {
    let mut data = Vec::with_capacity(132);
    data.extend_from_slice(&payload.beacon_id.to_be_bytes());
    data.extend_from_slice(&payload.identity_pub_key);
    data.extend_from_slice(&payload.ephemeral_pub_key);
    data.extend_from_slice(&payload.signature);
    data
}

#[uniffi::export]
pub fn deserialize_handshake(data: Vec<u8>) -> Result<HandshakePayload, MeshError> {
    if data.len() != 132 {
        return Err(MeshError::DeserializeError);
    }
    
    let mut beacon_bytes = [0u8; 4];
    beacon_bytes.copy_from_slice(&data[0..4]);
    let beacon_id = u32::from_be_bytes(beacon_bytes);
    
    Ok(HandshakePayload {
        beacon_id,
        identity_pub_key: data[4..36].to_vec(),
        ephemeral_pub_key: data[36..68].to_vec(),
        signature: data[68..132].to_vec(),
    })
}
