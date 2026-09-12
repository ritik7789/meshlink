//! End-to-end checks for the behaviour that multi-node testing exposed:
//! a middle node must relay for nodes beyond it, every node must be able to
//! address every other node, and relays must not be able to read what they
//! carry.

use meshlink_core::dedup::DedupCache;
use meshlink_core::envelope::{
    MessageEnvelope, PayloadType, Priority, BROADCAST_RECIPIENT, INITIAL_TTL,
};
use meshlink_core::router::{process_incoming, ProcessAction};
use meshlink_core::{beacon_id_from_public_key, IdentityKeyPair, StaticKeyPair};
use std::collections::HashMap;
use std::sync::Arc;

/// One simulated device: a stable mesh address, its dedup cache and its
/// long-lived encryption keypair, all derived the way the Android service does.
struct Node {
    id: u32,
    dedup: Arc<DedupCache>,
    keys: Arc<StaticKeyPair>,
    delivered: Vec<MessageEnvelope>,
}

impl Node {
    fn new(seed_byte: u8) -> Self {
        let seed = [seed_byte; 32];
        let identity = IdentityKeyPair::from_bytes(&seed).expect("valid seed");
        Self {
            id: beacon_id_from_public_key(&identity.public_key()),
            dedup: Arc::new(DedupCache::new()),
            keys: StaticKeyPair::from_identity_seed(&seed).expect("valid seed"),
            delivered: Vec::new(),
        }
    }
}

/// A mesh with explicit links. Only linked nodes can exchange packets, so a
/// message reaching a non-neighbour proves relaying actually happened.
struct Mesh {
    nodes: Vec<Node>,
    links: Vec<(usize, usize)>,
}

impl Mesh {
    /// Builds a chain: 0 <-> 1 <-> 2 <-> ... Node 0 and node 2 are never
    /// directly linked, which is the topology that was failing.
    fn chain(len: usize) -> Self {
        Self {
            nodes: (0..len).map(|i| Node::new(i as u8 + 1)).collect(),
            links: (0..len.saturating_sub(1)).map(|i| (i, i + 1)).collect(),
        }
    }

    fn neighbors(&self, index: usize) -> Vec<usize> {
        self.links
            .iter()
            .filter_map(|&(a, b)| {
                if a == index {
                    Some(b)
                } else if b == index {
                    Some(a)
                } else {
                    None
                }
            })
            .collect()
    }

    /// Floods `envelope` outward from `origin`, running each receiving node's
    /// real routing decision. Returns how many times each node relayed.
    fn flood(&mut self, origin: usize, envelope: MessageEnvelope) -> HashMap<usize, usize> {
        // The originator records its own id, exactly as the service does on send.
        self.nodes[origin].dedup.record_message(&envelope.message_id);

        let mut relay_counts: HashMap<usize, usize> = HashMap::new();
        // (receiver, arriving-from, envelope)
        let mut queue: Vec<(usize, usize, MessageEnvelope)> = self
            .neighbors(origin)
            .into_iter()
            .map(|n| (n, origin, envelope.clone()))
            .collect();

        let mut guard = 0;
        while let Some((index, from, env)) = queue.pop() {
            guard += 1;
            assert!(guard < 10_000, "flood failed to terminate");

            let local_id = self.nodes[index].id;
            let dedup = Arc::clone(&self.nodes[index].dedup);
            let action = process_incoming(env.clone(), local_id, dedup);

            match action {
                // Neither delivers nor forwards: the simulation has no ack path,
                // so it behaves as a stop here.
                ProcessAction::Drop | ProcessAction::AcknowledgeOnly => continue,
                ProcessAction::DeliverLocal => {
                    self.nodes[index].delivered.push(env);
                }
                ProcessAction::Relay | ProcessAction::DeliverAndRelay => {
                    if action == ProcessAction::DeliverAndRelay {
                        self.nodes[index].delivered.push(env.clone());
                    }
                    let mut forwarded = env.clone();
                    if forwarded.decrement_ttl() {
                        *relay_counts.entry(index).or_default() += 1;
                        for next in self.neighbors(index) {
                            if next != from {
                                queue.push((next, index, forwarded.clone()));
                            }
                        }
                    }
                }
            }
        }
        relay_counts
    }
}

#[test]
fn beacon_id_is_stable_across_restarts() {
    let seed = [7u8; 32];
    let first = IdentityKeyPair::from_bytes(&seed).unwrap();
    let second = IdentityKeyPair::from_bytes(&seed).unwrap();
    assert_eq!(
        beacon_id_from_public_key(&first.public_key()),
        beacon_id_from_public_key(&second.public_key()),
        "a node must keep its address across restarts"
    );
    assert_ne!(beacon_id_from_public_key(&first.public_key()), 0);
}

#[test]
fn distinct_identities_get_distinct_addresses() {
    let a = Node::new(1);
    let b = Node::new(2);
    assert_ne!(a.id, b.id);
}

#[test]
fn direct_message_reaches_a_node_two_hops_away() {
    let mut mesh = Mesh::chain(3);
    let (a, c) = (mesh.nodes[0].id, mesh.nodes[2].id);

    let envelope = MessageEnvelope::new(
        a,
        c,
        b"hello from A".to_vec(),
        Priority::Direct,
        PayloadType::Text,
        INITIAL_TTL,
    );
    let relays = mesh.flood(0, envelope);

    assert_eq!(mesh.nodes[2].delivered.len(), 1, "C must receive A's message");
    assert_eq!(
        mesh.nodes[1].delivered.len(),
        0,
        "the middle node must relay without consuming a message addressed elsewhere"
    );
    assert_eq!(relays.get(&1), Some(&1), "B must relay exactly once");
    assert_eq!(
        mesh.nodes[2].delivered[0].hops_taken(),
        2,
        "C should see the message as two hops away"
    );
}

#[test]
fn every_node_reaches_every_other_node_in_a_chain() {
    // The original failure was a node being able to talk to exactly one peer.
    for origin in 0..4 {
        for target in 0..4 {
            if origin == target {
                continue;
            }
            let mut mesh = Mesh::chain(4);
            let (from, to) = (mesh.nodes[origin].id, mesh.nodes[target].id);
            let envelope = MessageEnvelope::new(
                from,
                to,
                b"ping".to_vec(),
                Priority::Direct,
                PayloadType::Text,
                INITIAL_TTL,
            );
            mesh.flood(origin, envelope);
            assert_eq!(
                mesh.nodes[target].delivered.len(),
                1,
                "node {origin} could not reach node {target}"
            );
        }
    }
}

#[test]
fn broadcast_reaches_all_nodes_exactly_once_and_never_returns_to_sender() {
    let mut mesh = Mesh::chain(4);
    let a = mesh.nodes[0].id;

    let envelope = MessageEnvelope::new(
        a,
        BROADCAST_RECIPIENT,
        b"everyone".to_vec(),
        Priority::Broadcast,
        PayloadType::Text,
        INITIAL_TTL,
    );
    mesh.flood(0, envelope);

    assert_eq!(mesh.nodes[0].delivered.len(), 0, "sender must not receive its own broadcast");
    for index in 1..4 {
        assert_eq!(
            mesh.nodes[index].delivered.len(),
            1,
            "node {index} should receive the broadcast exactly once"
        );
    }
}

#[test]
fn presence_propagates_beyond_one_hop_with_accurate_distance() {
    let mut mesh = Mesh::chain(4);
    let a = mesh.nodes[0].id;

    let envelope = MessageEnvelope::new(
        a,
        BROADCAST_RECIPIENT,
        b"{\"v\":1}".to_vec(),
        Priority::Broadcast,
        PayloadType::Presence,
        INITIAL_TTL,
    );
    mesh.flood(0, envelope);

    for index in 1..4 {
        let received = &mesh.nodes[index].delivered;
        assert_eq!(received.len(), 1, "node {index} must learn about node A");
        assert_eq!(
            received[0].hops_taken() as usize,
            index,
            "node {index} should record A at {index} hop(s)"
        );
    }
}

#[test]
fn a_ring_does_not_loop_forever() {
    // Redundant paths are where naive flooding melts down.
    let mut mesh = Mesh::chain(4);
    mesh.links.push((3, 0)); // close the ring

    let envelope = MessageEnvelope::new(
        mesh.nodes[0].id,
        BROADCAST_RECIPIENT,
        b"loop me".to_vec(),
        Priority::Broadcast,
        PayloadType::Text,
        INITIAL_TTL,
    );
    mesh.flood(0, envelope);

    assert_eq!(mesh.nodes[0].delivered.len(), 0, "sender must drop its own echo");
    for index in 1..4 {
        assert_eq!(
            mesh.nodes[index].delivered.len(),
            1,
            "node {index} must deliver the broadcast once despite two paths"
        );
    }
}

#[test]
fn ttl_bounds_how_far_a_message_travels() {
    let mut mesh = Mesh::chain(3);
    let (a, c) = (mesh.nodes[0].id, mesh.nodes[2].id);

    // TTL 1 means the first receiver is the last hop allowed to act on it.
    let envelope = MessageEnvelope::new(a, c, b"short".to_vec(), Priority::Direct, PayloadType::Text, 1);
    mesh.flood(0, envelope);

    assert_eq!(mesh.nodes[2].delivered.len(), 0, "an exhausted message must not be relayed");
}

#[test]
fn a_relay_cannot_read_what_it_forwards() {
    let mesh = Mesh::chain(3);
    let (a, b, c) = (&mesh.nodes[0], &mesh.nodes[1], &mesh.nodes[2]);

    let secret = b"meet me at the north gate";
    let sealed = a.keys.seal(&c.keys.public_key(), secret);
    assert!(!sealed.is_empty());
    assert!(
        !sealed.windows(secret.len()).any(|w| w == secret),
        "the plaintext must not appear in the sealed payload"
    );

    // The intended recipient opens it.
    assert_eq!(
        c.keys.open(&a.keys.public_key(), &sealed).as_deref(),
        Some(&secret[..])
    );

    // The relay, holding both public keys, still cannot.
    assert_eq!(b.keys.open(&a.keys.public_key(), &sealed), None);
    assert_eq!(b.keys.open(&c.keys.public_key(), &sealed), None);
}

#[test]
fn opening_with_the_wrong_sender_key_fails() {
    let mesh = Mesh::chain(3);
    let (a, b, c) = (&mesh.nodes[0], &mesh.nodes[1], &mesh.nodes[2]);

    let sealed = a.keys.seal(&c.keys.public_key(), b"authentic");
    // Claiming the message came from B does not open it, so a relay cannot
    // pass its own traffic off as someone else's.
    assert_eq!(c.keys.open(&b.keys.public_key(), &sealed), None);
}

#[test]
fn static_keys_are_reproducible_from_the_persisted_seed() {
    let seed = [42u8; 32];
    let first = StaticKeyPair::from_identity_seed(&seed).unwrap();
    let second = StaticKeyPair::from_identity_seed(&seed).unwrap();
    assert_eq!(first.public_key(), second.public_key());

    // A message sealed before a restart is still readable after one.
    let peer = StaticKeyPair::from_identity_seed(&[43u8; 32]).unwrap();
    let sealed = peer.seal(&first.public_key(), b"survives a restart");
    assert_eq!(
        second.open(&peer.public_key(), &sealed).as_deref(),
        Some(&b"survives a restart"[..])
    );
}

#[test]
fn a_node_ignores_its_own_traffic_coming_back() {
    let node = Node::new(9);
    let envelope = MessageEnvelope::new(
        node.id,
        BROADCAST_RECIPIENT,
        b"mine".to_vec(),
        Priority::Broadcast,
        PayloadType::Text,
        INITIAL_TTL,
    );
    // Arriving at the sender without ever having been recorded, as happens when
    // it returns over a second path before the local copy was cached.
    let action = process_incoming(envelope, node.id, Arc::clone(&node.dedup));
    assert_eq!(action, ProcessAction::Drop);
}

#[test]
fn a_retransmission_of_a_delivered_message_is_re_acknowledged() {
    let node = Node::new(11);
    let envelope = MessageEnvelope::new(
        999,
        node.id,
        b"sealed".to_vec(),
        Priority::Direct,
        PayloadType::Text,
        INITIAL_TTL,
    );

    // First arrival is delivered normally.
    assert_eq!(
        process_incoming(envelope.clone(), node.id, Arc::clone(&node.dedup)),
        ProcessAction::DeliverLocal
    );

    // The sender retries because our acknowledgement was lost. Dropping it as a
    // plain duplicate would strand the sender retrying forever.
    assert_eq!(
        process_incoming(envelope, node.id, Arc::clone(&node.dedup)),
        ProcessAction::AcknowledgeOnly
    );
}

#[test]
fn a_duplicate_acknowledgement_is_dropped_rather_than_acknowledged() {
    let node = Node::new(12);
    let ack = MessageEnvelope::new(
        999,
        node.id,
        b"acked-id".to_vec(),
        Priority::Direct,
        PayloadType::Ack,
        INITIAL_TTL,
    );

    assert_eq!(
        process_incoming(ack.clone(), node.id, Arc::clone(&node.dedup)),
        ProcessAction::DeliverLocal
    );
    // Acknowledging an acknowledgement would bounce between the two nodes.
    assert_eq!(
        process_incoming(ack, node.id, Arc::clone(&node.dedup)),
        ProcessAction::Drop
    );
}

#[test]
fn a_duplicate_meant_for_someone_else_is_still_dropped() {
    let node = Node::new(13);
    let envelope = MessageEnvelope::new(
        999,
        424242,
        b"not mine".to_vec(),
        Priority::Direct,
        PayloadType::Text,
        INITIAL_TTL,
    );
    assert_eq!(
        process_incoming(envelope.clone(), node.id, Arc::clone(&node.dedup)),
        ProcessAction::Relay
    );
    assert_eq!(
        process_incoming(envelope, node.id, Arc::clone(&node.dedup)),
        ProcessAction::Drop
    );
}

#[test]
fn a_signed_envelope_verifies_against_its_sender_and_nobody_else() {
    let alice = IdentityKeyPair::from_bytes(&[21u8; 32]).unwrap();
    let mallory = IdentityKeyPair::from_bytes(&[22u8; 32]).unwrap();

    let envelope = meshlink_core::sign_envelope(
        MessageEnvelope::new(
            beacon_id_from_public_key(&alice.public_key()),
            777,
            b"carry this for me".to_vec(),
            Priority::Direct,
            PayloadType::Text,
            INITIAL_TTL,
        ),
        &alice,
    );

    assert!(meshlink_core::verify_envelope(envelope.clone(), alice.public_key()));
    // Relays are asked to spend storage on this; it must not be attributable to
    // anyone who did not actually send it.
    assert!(!meshlink_core::verify_envelope(envelope, mallory.public_key()));
}

#[test]
fn tampering_with_a_signed_envelope_is_detected() {
    let alice = IdentityKeyPair::from_bytes(&[23u8; 32]).unwrap();
    let signed = meshlink_core::sign_envelope(
        MessageEnvelope::new(777, 888, b"original".to_vec(), Priority::Direct, PayloadType::Text, INITIAL_TTL),
        &alice,
    );

    let mut altered = signed.clone();
    altered.encrypted_payload = b"swapped".to_vec();
    assert!(!meshlink_core::verify_envelope(altered, alice.public_key()));

    let mut rerouted = signed.clone();
    rerouted.recipient_id = 999;
    assert!(!meshlink_core::verify_envelope(rerouted, alice.public_key()));

    // A relay must still be able to decrement TTL without breaking the signature.
    let mut forwarded = signed.clone();
    assert!(forwarded.decrement_ttl());
    assert!(meshlink_core::verify_envelope(forwarded, alice.public_key()));
}

#[test]
fn an_acknowledgement_names_the_message_it_settles_in_the_clear() {
    let alice = IdentityKeyPair::from_bytes(&[24u8; 32]).unwrap();
    let ack = meshlink_core::create_ack_envelope(111, 222, b"sealed receipt".to_vec(), "msg-abc".into());

    // A relay carrying msg-abc needs this without being able to open the payload.
    assert_eq!(ack.ack_for.as_deref(), Some("msg-abc"));
    assert_eq!(ack.payload_type, PayloadType::Ack);

    // And it is covered by the signature, so a relay cannot be tricked into
    // dropping a message that was never actually delivered.
    let signed = meshlink_core::sign_envelope(ack, &alice);
    let mut forged = signed.clone();
    forged.ack_for = Some("msg-xyz".into());
    assert!(meshlink_core::verify_envelope(signed, alice.public_key()));
    assert!(!meshlink_core::verify_envelope(forged, alice.public_key()));
}

#[test]
fn binary_encoding_keeps_attachments_close_to_their_real_size() {
    // A 10 KB attachment is the inline ceiling, so the encoding overhead on it
    // decides whether that limit means anything.
    let payload = vec![0xA5u8; 10_240];
    let envelope = MessageEnvelope::new(
        111,
        222,
        payload.clone(),
        Priority::Direct,
        PayloadType::ContactCard,
        INITIAL_TTL,
    );

    let encoded = envelope.serialize();
    // JSON rendered each byte as an array element ("165," = four chars), pushing
    // this past 40 KB and over the chunking limit.
    assert!(
        encoded.len() < payload.len() + 512,
        "encoding added {} bytes of overhead",
        encoded.len() - payload.len()
    );

    let decoded = MessageEnvelope::deserialize(encoded).expect("round-trips");
    assert_eq!(decoded.encrypted_payload, payload);
    assert_eq!(decoded.payload_type, PayloadType::ContactCard);
}

#[test]
fn every_payload_type_survives_a_round_trip() {
    for payload_type in [
        PayloadType::Text,
        PayloadType::Ack,
        PayloadType::MediaOffer,
        PayloadType::MediaRequest,
        PayloadType::MediaChunk,
        PayloadType::MediaComplete,
        PayloadType::ContactCard,
        PayloadType::StickerRef,
        PayloadType::Sos,
        PayloadType::Presence,
        PayloadType::TopologyHint,
        PayloadType::GroupInvite,
        PayloadType::GroupMessage,
        PayloadType::CallInvite,
        PayloadType::CallAccept,
        PayloadType::CallDecline,
        PayloadType::CallEnd,
    ] {
        let mut envelope = MessageEnvelope::new(
            1, 2, b"payload".to_vec(), Priority::Direct, payload_type.clone(), INITIAL_TTL,
        );
        envelope.ack_for = Some("some-id".into());
        let decoded = MessageEnvelope::deserialize(envelope.serialize()).expect("round-trips");
        assert_eq!(decoded.payload_type, payload_type);
        assert_eq!(decoded.ack_for.as_deref(), Some("some-id"));
    }
}

#[test]
fn only_media_chunks_count_as_bulk() {
    // Offers must flood so an absent recipient still learns a file is waiting;
    // only the bytes are restricted to direct links.
    assert!(PayloadType::MediaChunk.is_bulk());
    assert!(!PayloadType::MediaOffer.is_bulk());
    assert!(!PayloadType::ContactCard.is_bulk());
    assert!(!PayloadType::StickerRef.is_bulk());
    assert!(!PayloadType::Text.is_bulk());
}

/// The wire value of every payload type, pinned.
///
/// postcard encodes an enum as its positional discriminant, so inserting a
/// variant anywhere but the end silently renumbers everything after it and an
/// older node reads a message as a *different* type rather than rejecting it.
/// That is not theoretical: a group invite once arrived as a chat bubble with
/// the group key printed in it, because two variants had been inserted in the
/// middle.
///
/// A comment did not stop that happening. This does: adding a variant anywhere
/// but the end fails here, and the failure names the position that moved.
#[test]
fn payload_type_wire_values_never_move() {
    let expected: [(PayloadType, u8); 17] = [
        (PayloadType::Text, 0),
        (PayloadType::Ack, 1),
        (PayloadType::MediaOffer, 2),
        (PayloadType::MediaRequest, 3),
        (PayloadType::MediaChunk, 4),
        (PayloadType::MediaComplete, 5),
        (PayloadType::ContactCard, 6),
        (PayloadType::StickerRef, 7),
        (PayloadType::Sos, 8),
        (PayloadType::Presence, 9),
        (PayloadType::TopologyHint, 10),
        (PayloadType::GroupInvite, 11),
        (PayloadType::GroupMessage, 12),
        (PayloadType::CallInvite, 13),
        (PayloadType::CallAccept, 14),
        (PayloadType::CallDecline, 15),
        (PayloadType::CallEnd, 16),
    ];

    for (payload_type, wire_value) in expected {
        let encoded = postcard::to_allocvec(&payload_type).expect("encodes");
        assert_eq!(
            encoded,
            vec![wire_value],
            "{:?} must stay at wire value {}: a node running an older build \
             will read anything else as a different payload type entirely",
            payload_type,
            wire_value
        );
    }
}
