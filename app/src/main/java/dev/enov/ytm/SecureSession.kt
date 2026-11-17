package dev.enov.ytm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val alias = "ytm-google-session"
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generator.generateKey()
    }
    var cookie: String
        get() = runCatching {
            val stored = prefs.getString("session", null) ?: return@runCatching ""
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            require(bytes.size > 12)
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8) }
        }.getOrDefault("")
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString("session", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Could not save the Google session" }
        }
    fun mergeCookies(replacements: List<String>) {
        val cookies = cookie.split(';').map(String::trim).filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
        replacements.forEach { value -> cookies[value.substringBefore('=')] = value.substringAfter('=') }
        cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    fun clear() { prefs.edit().clear().commit(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) } }
    companion object { private val keyLock = Any() }
}
