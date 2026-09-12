use serde::{Deserialize, Serialize};
use uuid::Uuid;
use std::time::{SystemTime, UNIX_EPOCH};

/// Reserved recipient id meaning "every node in the mesh".
pub const BROADCAST_RECIPIENT: u32 = 0;

/// Hop budget a freshly created envelope starts with. Every relay decrements it
/// before forwarding, so it bounds how far a message can travel and guarantees
/// that flooding terminates even if the dedup cache is somehow bypassed.
pub const INITIAL_TTL: u8 = 7;

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
    /// Small descriptor of a file the sender is offering: id, type, size, hash
    /// and a thumbnail. Floods like a message; the bytes themselves do not.
    MediaOffer,
    /// Recipient asking for a specific byte range of an offered file.
    MediaRequest,
    /// One slice of an offered file. Direct links only, never relayed, unless
    /// the sender spent an emergency allowance.
    MediaChunk,
    /// Sender signalling that every chunk has been sent.
    MediaComplete,
    /// A contact card, small enough to travel inline like text.
    ContactCard,
    /// Reference to a sticker in a pack shipped inside the app, so sending one
    /// costs a few bytes rather than an image transfer.
    StickerRef,
    Sos,
    /// Node announcing itself to the whole mesh: carries its identity key, its
    /// static X25519 key and its display name. Flooded like a broadcast, which
    /// is what makes nodes visible to each other beyond one hop.
    Presence,
    TopologyHint,
    /// Hands one member the group's shared key and current roster, sealed to
    /// that member alone. Unicast, and re-sent to everyone still in the group
    /// whenever the key is rotated.
    GroupInvite,
    /// Anything addressed to a group: chat, roster changes, deletions, leaves.
    ///
    /// Flooded once and encrypted with the group key, so one message costs the
    /// same airtime whether the group has three members or twenty-five, and
    /// nodes outside the group cannot open any of it.
    GroupMessage,
}

// NOTE: new variants must be appended here, never inserted. The encoding is
// positional, so inserting one shifts every later discriminant and an older node
// silently reads a message as a *different* type rather than rejecting it -
// which is how a group invite once rendered as a chat message, key and all.

impl PayloadType {
    /// Whether this kind of payload carries bulk data that must not be flooded.
    pub fn is_bulk(&self) -> bool {
        matches!(self, PayloadType::MediaChunk)
    }
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
    /// For `Priority::Direct` this is `nonce || ciphertext` sealed with
    /// `StaticKeyPair::seal` for `recipient_id` — relays forward it without
    /// being able to read it. For mesh-wide traffic (broadcast, presence) there
    /// is no single recipient key, so it carries plaintext bytes.
    pub encrypted_payload: Vec<u8>,
    /// For an `Ack`, the id of the message being acknowledged, in the clear.
    ///
    /// The sealed payload proves *who* acknowledged, but only the sender can
    /// open it. Relays carrying a copy of that message on the sender's behalf
    /// need to know it was delivered so they can stop carrying it, which is why
    /// this one field is readable by everyone the acknowledgement passes.
    pub ack_for: Option<String>,
    /// Ed25519 signature over `serialize_for_signing`, proving the envelope was
    /// produced by the holder of `sender_id`'s identity key.
    pub signature: Vec<u8>,
}

impl MessageEnvelope {
    pub fn new(
        sender_id: u32,
        recipient_id: u32,
        payload: Vec<u8>,
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
            ack_for: None,
            signature: vec![],
        }
    }

    /// Rebuilds an envelope that keeps an id assigned earlier. Used when a
    /// message was queued before the recipient's key was known: the stored row
    /// and the envelope that eventually goes out must agree on `message_id`, or
    /// the sender's own copy and the delivered copy become different messages.
    pub fn with_id(
        message_id: String,
        sender_id: u32,
        recipient_id: u32,
        payload: Vec<u8>,
        priority: Priority,
        payload_type: PayloadType,
        ttl: u8,
    ) -> Self {
        let mut envelope = Self::new(sender_id, recipient_id, payload, priority, payload_type, ttl);
        envelope.message_id = message_id;
        envelope
    }

    /// Compact binary encoding.
    ///
    /// JSON turned every payload byte into an array element averaging about four
    /// bytes on the wire, which put the practical inline limit near 30 KB rather
    /// than the 127 KB the chunking allows. Postcard writes byte sequences
    /// verbatim, so a 10 KB attachment costs 10 KB.
    pub fn serialize(&self) -> Vec<u8> {
        postcard::to_allocvec(self).unwrap_or_default()
    }

    /// Excludes `ttl` and `signature`: the TTL changes at every hop, so signing
    /// over it would invalidate the signature the moment a relay forwards it.
    pub fn serialize_for_signing(&self) -> Vec<u8> {
        #[derive(Serialize)]
        struct SigningPayload<'a> {
            message_id: &'a String,
            sender_id: u32,
            recipient_id: u32,
            priority: &'a Priority,
            timestamp: u32,
            payload_type: &'a PayloadType,
            encrypted_payload: &'a Vec<u8>,
            ack_for: &'a Option<String>,
        }

        let payload = SigningPayload {
            message_id: &self.message_id,
            sender_id: self.sender_id,
            recipient_id: self.recipient_id,
            priority: &self.priority,
            timestamp: self.timestamp,
            payload_type: &self.payload_type,
            encrypted_payload: &self.encrypted_payload,
            ack_for: &self.ack_for,
        };

        postcard::to_allocvec(&payload).unwrap_or_default()
    }

    pub fn deserialize(data: Vec<u8>) -> Option<MessageEnvelope> {
        postcard::from_bytes(&data).ok()
    }

    pub fn decrement_ttl(&mut self) -> bool {
        if self.ttl == 0 {
            return false;
        }
        self.ttl -= 1;
        self.ttl > 0
    }

    /// How many relays this envelope has already crossed. A direct neighbour's
    /// message reports 1, a message relayed once reports 2, and so on.
    pub fn hops_taken(&self) -> u8 {
        INITIAL_TTL.saturating_sub(self.ttl).saturating_add(1)
    }
}
