package com.meshlink

/**
 * Compile-time feature switches.
 *
 * These are `const val` deliberately, and every guard compares against the
 * constant directly rather than through a property. The compiler folds the
 * condition, so a disabled feature costs nothing at runtime: no branch is
 * evaluated, no view is created, no listener attached, no payload handled.
 *
 * It does not shrink the APK on its own. Kotlin leaves the unreachable bodies in
 * the dex — confirmed on a clean build, where the feature's strings are still
 * present with the flag off. Stripping those bytes needs R8, which this project
 * currently has disabled (`isMinifyEnabled = false`). So read a `false` flag as
 * "this feature cannot run", not as "this feature is not in the binary".
 *
 * Reading a flag through a getter defeats even the constant folding, which is
 * why nothing outside a feature's façade should read one.
 */
object Features {

    /**
     * Voice calling: signalling, the real-time audio transport, and the UI that
     * starts or answers a call.
     *
     * Set to `false` to disable the feature entirely. Everything calling-related
     * is reached through [CallFeature], so this single line is the whole switch.
     *
     * Off until the transport work lands: on a BLE-only link a call is usable
     * but fragile, and shipping it enabled before Wi-Fi Direct exists would set
     * an expectation the radio cannot yet meet.
     */
    const val VOICE_CALLS = true
}
