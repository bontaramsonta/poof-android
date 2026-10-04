package dev.bontaramsonta.poof.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.bontaramsonta.poof.core.SessionRecord
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * The bearer token and the stored Session record, each AES-GCM encrypted
 * under a non-exportable Android Keystore key. Deleting the record ends the
 * Session (spec §6.4).
 */
class SecretStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("poof-secrets", Context.MODE_PRIVATE)

    var token: String?
        get() = read(KEY_TOKEN)
        set(value) = write(KEY_TOKEN, value)

    var session: SessionRecord?
        get() = read(KEY_SESSION)?.let { json.decodeFromString<SessionRecord>(it) }
        set(value) = write(KEY_SESSION, value?.let { json.encodeToString(it) })

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun write(name: String, value: String?) {
        if (value == null) {
            // commit, not apply: a Session must not survive a crash right after it ended.
            prefs.edit().remove(name).commit()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "poof-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_TOKEN = "token"
        const val KEY_SESSION = "session"
        val json = Json { ignoreUnknownKeys = true }
    }
}
