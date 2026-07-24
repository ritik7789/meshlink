# MeshLink 🌐

**MeshLink** is a decentralized, off-grid peer-to-peer (P2P) mesh networking platform built for Android devices. It enables encrypted communication without internet access, Wi-Fi infrastructure, or cellular networks by leveraging Bluetooth Low Energy (BLE) multi-peer relaying and a high-performance Rust cryptographic core.

---

## 🚀 Key Features

- 📶 **Off-Grid Mesh Relaying**: Nodes automatically discover surrounding peers and relay messages across multiple hops (Store-and-Forward / Multi-hop flooding).
- 🔒 **End-to-End Encryption**: Built-in Rust core using **Ed25519** digital signatures, **X25519** Diffie-Hellman ephemeral key exchanges, and **ChaCha20-Poly1305** symmetric transport encryption.
- 🔄 **Dual-Role BLE Operation**: Every device simultaneously operates as a BLE Peripheral (advertising service presence) and BLE Central (scanning & initiating connections).
- 🛡️ **Anti-Replay & Deduplication**: Built-in deduplication cache (`DedupCache`) prevents message loop floods and duplicate payload processing.
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
- **UniFFI Scaffolding**: Auto-generates type-safe Kotlin bindings (`meshlink_core.kt`) and native shared objects (`.so`) for Android architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

### 2. `meshlink-android` (Android Kotlin Client)
- **`RelayService.kt`**: Foreground Service coordinating advertising, scanning, and background message routing.
- **`GattServer.kt`**: Manages incoming GATT client connections, GATT service definitions, and characteristic writes/reads.
- **`GattClient.kt`**: Initiates outbound connections to discovered BLE peers and streams chunked message payloads.
- **`PeerManager.kt`**: Tracks active mesh peers, connection states, and handshake timeouts.
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

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for more information.
