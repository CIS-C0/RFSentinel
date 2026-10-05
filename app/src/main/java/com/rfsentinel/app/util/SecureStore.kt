package com.rfsentinel.app.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (an API key) kept encrypted with an AES-GCM key that lives in the
 * Android Keystore and never leaves it. Not included in backups.
 */
object SecureStore {
    private const val ALIAS = "rfsentinel_secrets"
    private const val PREFS = "secure_store"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun put(context: Context, name: String, value: String?) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) { sp.edit().remove(name).apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val data = c.iv + c.doFinal(value.trim().toByteArray())
            sp.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
        }
    }

    fun get(context: Context, name: String): String? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        val data = Base64.decode(raw, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(c.doFinal(data, 12, data.size - 12))
    }.getOrNull()
}
