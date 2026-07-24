use crate::envelope::MessageEnvelope;
use crate::dedup::DedupCache;
use std::sync::Arc;

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ProcessAction {
    DeliverLocal,
    Relay,
    DeliverAndRelay,
    Drop,
}

#[uniffi::export]
pub fn process_incoming(
    envelope: MessageEnvelope,
    local_id: u32,
    dedup_cache: Arc<DedupCache>,
) -> ProcessAction {
    // 1. Dedup check
    if dedup_cache.is_duplicate(&envelope.message_id) {
        return ProcessAction::Drop;
    }

    // 2. Record it
    dedup_cache.record_message(&envelope.message_id);

    // 3. Check for local delivery
    if envelope.priority == crate::envelope::Priority::Broadcast {
        return ProcessAction::DeliverAndRelay;
    }

    if envelope.recipient_id == local_id {
        return ProcessAction::DeliverLocal;
    }

    // 4. Check TTL
    if envelope.ttl <= 1 {
        return ProcessAction::Drop;
    }

    // Will be decremented later or we decrement now? Requirement says: "(The TTL decrement will happen right before actually sending)."
    // So we don't decrement TTL here.
    
    ProcessAction::Relay
}
