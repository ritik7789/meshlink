pub mod envelope;
pub mod protocol;
pub mod handshake;
pub mod dedup;
pub mod router;
pub mod crypto;

uniffi::setup_scaffolding!();

pub use envelope::{MessageEnvelope, PayloadType, Priority};
pub use protocol::{protocol_version as core_protocol_version, is_protocol_compatible as core_is_protocol_compatible};
pub use handshake::{generate_beacon_id as core_generate_beacon_id, HandshakeCache};
pub use dedup::DedupCache;
pub use router::{process_incoming, ProcessAction};
pub use crypto::{IdentityKeyPair, EphemeralKeyPair, verify_signature, encrypt_transport, decrypt_transport};

#[derive(Debug, uniffi::Error)]
pub enum MeshError {
    DeserializeError,
}

impl std::fmt::Display for MeshError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MeshError::DeserializeError => write!(f, "Failed to deserialize"),
        }
    }
}
impl std::error::Error for MeshError {}

#[uniffi::export]
pub fn create_test_envelope(text: String) -> MessageEnvelope {
    MessageEnvelope::new_direct(text)
}

#[uniffi::export]
pub fn create_envelope(
    sender_id: u32,
    recipient_id: u32,
    payload: String,
    priority: Priority,
    payload_type: PayloadType,
) -> MessageEnvelope {
    MessageEnvelope::new(sender_id, recipient_id, payload, priority, payload_type, 7)
}

#[uniffi::export]
pub fn decrement_ttl(mut envelope: MessageEnvelope) -> Option<MessageEnvelope> {
    if envelope.decrement_ttl() {
        Some(envelope)
    } else {
        None
    }
}

#[uniffi::export]
pub fn serialize_envelope(envelope: MessageEnvelope) -> Vec<u8> {
    envelope.serialize()
}

#[uniffi::export]
pub fn serialize_for_signing(envelope: MessageEnvelope) -> Vec<u8> {
    envelope.serialize_for_signing()
}

#[uniffi::export]
pub fn deserialize_envelope(data: Vec<u8>) -> Result<MessageEnvelope, MeshError> {
    MessageEnvelope::deserialize(data).ok_or(MeshError::DeserializeError)
}

#[uniffi::export]
pub fn protocol_version() -> u8 {
    core_protocol_version()
}

#[uniffi::export]
pub fn is_protocol_compatible(remote_version: u8) -> bool {
    core_is_protocol_compatible(remote_version)
}

#[uniffi::export]
pub fn generate_beacon_id() -> u32 {
    core_generate_beacon_id()
}
pub use handshake::{HandshakePayload, create_handshake_payload, verify_handshake_payload, serialize_handshake, deserialize_handshake};
