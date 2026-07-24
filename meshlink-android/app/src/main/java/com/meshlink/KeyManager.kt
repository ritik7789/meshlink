package com.meshlink

import android.content.Context
import android.util.Base64
import uniffi.meshlink_core.IdentityKeyPair

class KeyManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "MeshLinkKeys"
        private const val KEY_IDENTITY_SEED = "identity_seed"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var identityKeyPair: IdentityKeyPair? = null

    fun getIdentityKey(): IdentityKeyPair {
        if (identityKeyPair != null) {
            return identityKeyPair!!
        }

        val b64Seed = prefs.getString(KEY_IDENTITY_SEED, null)
        if (b64Seed != null) {
            try {
                val seedBytes = Base64.decode(b64Seed, Base64.DEFAULT)
                val keyPair = IdentityKeyPair.fromBytes(seedBytes)
                identityKeyPair = keyPair
                return keyPair
            } catch (e: Exception) {
                // If decoding fails, we fall through and generate a new one
            }
        }

        // Generate new if none exists or loading failed
        val newKeyPair = IdentityKeyPair.generate()
        val seedBytes = newKeyPair.toBytes()
        val encoded = Base64.encodeToString(seedBytes, Base64.DEFAULT)
        prefs.edit().putString(KEY_IDENTITY_SEED, encoded).apply()
        
        identityKeyPair = newKeyPair
        return newKeyPair
    }
}
