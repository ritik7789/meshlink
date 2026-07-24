use serde::{Deserialize, Serialize};
use uuid::Uuid;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
pub enum Priority {
    Sos = 0,
    Direct = 1,
    Broadcast = 2,
    BulkMediaNotify = 3,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
pub enum PayloadType {
    Text,
    Ack,
    MediaOffer,
    Sos,
    TopologyHint,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct MessageEnvelope {
    pub message_id: String,
    pub sender_id: u32,
    pub recipient_id: u32,
    pub priority: Priority,
    pub ttl: u8,
    pub timestamp: u32,
    pub payload_type: PayloadType,
    // In a real scenario, this would be encrypted bytes (Vec<u8>)
    pub encrypted_payload: String, 
    // Ed25519 signature
    pub signature: Vec<u8>,
}

impl MessageEnvelope {
    pub fn new(
        sender_id: u32,
        recipient_id: u32,
        payload: String,
        priority: Priority,
        payload_type: PayloadType,
        ttl: u8,
    ) -> Self {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as u32;
            
        Self {
            message_id: Uuid::new_v4().to_string(),
            sender_id,
            recipient_id,
            priority,
            ttl,
            timestamp,
            payload_type,
            encrypted_payload: payload,
            signature: vec![],
        }
    }

    pub fn new_direct(payload: String) -> Self {
        Self::new(
            12345,
            67890,
            payload,
            Priority::Direct,
            PayloadType::Text,
            7,
        )
    }

    pub fn serialize(&self) -> Vec<u8> {
        serde_json::to_vec(self).unwrap_or_default()
    }

    pub fn serialize_for_signing(&self) -> Vec<u8> {
        // We can create a temporary struct to serialize without ttl and signature,
        // or just clone, zero out ttl, clear signature, and serialize.
        #[derive(Serialize)]
        struct SigningPayload<'a> {
            message_id: &'a String,
            sender_id: u32,
            recipient_id: u32,
            priority: &'a Priority,
            timestamp: u32,
            payload_type: &'a PayloadType,
            encrypted_payload: &'a String,
        }

        let payload = SigningPayload {
            message_id: &self.message_id,
            sender_id: self.sender_id,
            recipient_id: self.recipient_id,
            priority: &self.priority,
            timestamp: self.timestamp,
            payload_type: &self.payload_type,
            encrypted_payload: &self.encrypted_payload,
        };

        serde_json::to_vec(&payload).unwrap_or_default()
    }

    pub fn deserialize(data: Vec<u8>) -> Option<MessageEnvelope> {
        serde_json::from_slice(&data).ok()
    }

    pub fn decrement_ttl(&mut self) -> bool {
        if self.ttl == 0 {
            return false;
        }
        self.ttl -= 1;
        self.ttl > 0
    }
}
