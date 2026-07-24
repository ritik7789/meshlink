#!/bin/bash
set -e

# Navigate to the rust core directory
cd meshlink-core

echo "Building Rust core for Android..."
# We use cargo-ndk to easily build for Android architectures. 
# This requires ANDROID_NDK_HOME to be set.
cargo ndk -t aarch64-linux-android -t armv7-linux-androideabi -t x86_64-linux-android -o ../meshlink-android/app/src/main/jniLibs build --release

echo "Generating Kotlin bindings with UniFFI..."
# UniFFI can generate bindings directly from the compiled dynamic library.
cargo run --bin uniffi-bindgen -- generate --language kotlin \
  --out-dir ../meshlink-android/app/src/main/java \
  --library ../meshlink-android/app/src/main/jniLibs/arm64-v8a/libmeshlink_core.so

echo "Done! The Rust core is now wired into the Android project."
