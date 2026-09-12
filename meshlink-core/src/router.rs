use crate::dedup::DedupCache;
use crate::envelope::{MessageEnvelope, PayloadType, Priority, BROADCAST_RECIPIENT};
use std::sync::Arc;

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ProcessAction {
    DeliverLocal,
    Relay,
    DeliverAndRelay,
    Drop,
}

/// Decides what a node should do with an envelope that just arrived on a link.
///
/// Routing is TTL-bounded flooding: a node forwards anything it has not seen
/// before to every neighbour except the one it came from, until the hop budget
/// runs out. There is no route table to go stale, so the mesh re-forms by
/// itself whenever BLE links drop and re-establish.
///
/// Loop termination rests on three checks, in order:
///   1. traffic we originated and that came back around is dropped,
///   2. anything already in the dedup cache is dropped,
///   3. anything out of TTL is not forwarded further.
#[uniffi::export]
pub fn process_incoming(
    envelope: MessageEnvelope,
    local_id: u32,
    dedup_cache: Arc<DedupCache>,
) -> ProcessAction {
    // Our own message looped back to us through another node. Without this a
    // broadcast returns via a two-hop path and gets delivered to the sender as
    // if it were inbound, then flooded again.
    if envelope.sender_id == local_id {
        return ProcessAction::Drop;
    }

    if dedup_cache.is_duplicate(&envelope.message_id) {
        return ProcessAction::Drop;
    }
    dedup_cache.record_message(&envelope.message_id);

    // A unicast that reached its destination stops here; forwarding it further
    // would only waste airtime since nobody else is addressed by it.
    if envelope.recipient_id == local_id {
        return ProcessAction::DeliverLocal;
    }

    let is_mesh_wide = envelope.recipient_id == BROADCAST_RECIPIENT
        || envelope.priority == Priority::Broadcast
        || envelope.payload_type == PayloadType::Presence;

    // TTL is decremented by the caller immediately before sending, so a value of
    // 1 means this node is the last hop allowed to consume it.
    let can_relay = envelope.ttl > 1;

    if is_mesh_wide {
        if can_relay {
            ProcessAction::DeliverAndRelay
        } else {
            ProcessAction::DeliverLocal
        }
    } else if can_relay {
        ProcessAction::Relay
    } else {
        ProcessAction::Drop
    }
}
