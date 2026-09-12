# MeshLink 🌐

**MeshLink** is a decentralized, off-grid peer-to-peer (P2P) mesh networking platform built for Android devices. It enables encrypted communication without internet access, Wi-Fi infrastructure, or cellular networks by leveraging Bluetooth Low Energy (BLE) multi-peer relaying and a high-performance Rust cryptographic core.

---

## 🚀 Key Features

- 📶 **Off-Grid Mesh Relaying**: Nodes discover surrounding peers and relay messages across multiple hops using TTL-bounded flooding. Any node can address any other node, whether it is a direct neighbour or several relays away.
- 🗺️ **Mesh-Wide Visibility**: Nodes periodically flood a signed-by-construction *presence announcement* carrying their address, keys and display name, so every node sees every reachable node — with a hop count showing how far away each one is.
- 🔒 **End-to-End Encryption**: Direct messages are sealed with **X25519** against the *recipient's* long-lived key, so relays forward ciphertext they cannot read. Each BLE hop is separately encrypted with **ChaCha20-Poly1305** using an ephemeral handshake, and identities are **Ed25519**.
- 🆔 **Stable Node Identity**: A node's mesh address is derived from its persisted identity key, so it survives app and service restarts along with its conversation history.
- 🔄 **Dual-Role BLE Operation**: Every device simultaneously operates as a BLE Peripheral (advertising service presence) and BLE Central (scanning & initiating connections). A single link carries traffic both ways — GATT writes one direction, indications the other — so a relay can forward over a link the *peer* established.
- 🛡️ **Anti-Replay & Deduplication**: Built-in deduplication cache (`DedupCache`) plus a TTL budget and an originator check prevent flood loops, even in topologies with redundant paths.
- ⚡ **Android Foreground Relay Service**: Background processing managed by an Android Foreground Service (`RelayService.kt`) with periodic BLE scan cycling to maintain active mesh discovery.
- 💾 **Local Offline Persistence**: Room database integration (`AppDatabase`) for message queuing, persistent logs, and offline message storage.

---

## 🏗️ Architecture Overview

The system is split into two primary components:

```
┌─────────────────────────────────────────────────────────┐
│               Android Application Layer                 │
│  (MainActivity.kt | RelayService.kt | Room DB)          │
└──────────────────────────┬──────────────────────────────┘
                           │ UniFFI Kotlin Bindings
┌──────────────────────────▼──────────────────────────────┐
│                meshlink-core (Rust Core)                │
│  - Ed25519 / X25519 Cryptography & Handshakes          │
│  - Packet Envelopes & Binary Serialization               │
│  - Transport Layer Encryption (ChaCha20-Poly1305)       │
│  - Message Deduplication Cache                          │
└─────────────────────────────────────────────────────────┘
```

### 1. `meshlink-core` (Rust Shared Core)
- **`crypto.rs`**: Key generation, ephemeral key exchange, payload encryption/decryption.
- **`envelope.rs`**: Binary packet envelope layout with sender/recipient IDs, message priority, and TTL.
- **`handshake.rs`**: Handshake verification and session key negotiation.
- **`dedup.rs`**: Deduplication cache to drop duplicate packets across mesh hops.
- **`router.rs`**: The forwarding decision for every received packet — deliver, relay, both, or drop.
- **UniFFI Scaffolding**: Auto-generates type-safe Kotlin bindings (`meshlink_core.kt`) and native shared objects (`.so`) for Android architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

### How routing works

Routing is TTL-bounded flooding rather than a route table, which suits a mesh
whose links drop and re-form constantly — there is no route state to go stale.

1. A sender seals the payload for the recipient's static X25519 key, stamps the
   envelope with `TTL = 7`, and hands it to every neighbour it has.
2. Each receiving node asks `process_incoming` what to do. Anything it has seen
   before, anything it originated itself, and anything out of TTL is dropped.
3. A message addressed to the node is delivered and stops there. A message for
   someone else is forwarded to every neighbour *except* the one it came from,
   with its TTL decremented.
4. Broadcasts and presence announcements are both delivered *and* relayed, so
   they reach the whole mesh.

Because TTL is decremented exactly once per hop, a receiver can derive how far
away the sender is from the TTL that is left — that is where the roster's hop
counts come from.

Routing behaviour is covered by `meshlink-core/tests/mesh_routing.rs`, which
simulates multi-node chains and rings:

```bash
cd meshlink-core && cargo test
```

### 2. `meshlink-android` (Android Kotlin Client)
- **`RelayService.kt`**: Foreground Service coordinating advertising, scanning, and background message routing.
- **`GattServer.kt`**: Manages incoming GATT client connections, GATT service definitions, and characteristic writes/reads.
- **`GattClient.kt`**: Initiates outbound connections to discovered BLE peers and streams chunked message payloads.
- **`LinkCodec.kt`**: Per-hop framing shared by both GATT directions — link encryption, chunking, and reassembly.
- **`PeerManager.kt`**: Tracks two things separately: *neighbours* (live BLE links, the set a flood is sent across) and the *roster* (every node presence gossip says is reachable, with its distance in hops).
- **`MainActivity.kt`**: Responsive UI displaying peer statuses, interactive node chat, and broadcast controls.

---

## 🛠️ Prerequisites

To build and run MeshLink, ensure you have the following installed:

1. **Android Studio & SDK**: Android API Level 26+ (Android 8.0+)
2. **Android NDK**: Configured with environment variable `ANDROID_NDK_HOME`
3. **Rust Toolchain**: `rustc`, `cargo` (with Android targets installed):
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```
4. **Cargo Tools**: `cargo-ndk` and `uniffi_bindgen`:
   ```bash
   cargo install cargo-ndk
   ```

---

## 📦 Building & Running

### Step 1: Build the Rust Core & Kotlin Bindings
Run the automated build script from the project root:
```bash
chmod +x build_android.sh
./build_android.sh
```
This compiles `meshlink-core` into native `.so` libraries for Android architectures and generates `meshlink_core.kt` in `meshlink-android/app/src/main/java/uniffi/meshlink_core/`.

### Step 2: Build & Install Android App
Connect one or more Android devices (USB or Wireless ADB) and run:
```bash
cd meshlink-android
./gradlew installDebug
```

---

## 📱 Hardware Requirements & BLE Setup

- **Permissions**: On initial launch, grant all requested permissions (**Bluetooth Scan**, **Bluetooth Advertise**, **Bluetooth Connect**, **Location**, and **Notifications**).
- **Physical Devices**: For multi-node testing, test on 2 or more physical Android phones/tablets supporting Bluetooth 4.2+ or 5.0+.

### Verifying multi-hop relaying

To confirm a middle node really is relaying rather than just talking to one
peer, place three devices so that A and C are **out of BLE range of each other**
but both are in range of B (separate rooms, or keep C physically distant from A).

- Within ~20 seconds every device's home screen should read **3 nodes
  reachable**, and on A the entry for C should be labelled **2 hops away**.
- Opening a chat with C from A and sending a message should deliver it through
  B. Watch it with `adb logcat -s MeshLinkRelay`: B logs `Relayed <id> … to N
  neighbour(s)` without logging a local delivery for that message, and C logs
  `Delivering message from <A> (2 hop(s))`.
- Moving B out of range should drop A and C off each other's rosters within
  90 seconds; bringing it back should restore them.

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for more information.
