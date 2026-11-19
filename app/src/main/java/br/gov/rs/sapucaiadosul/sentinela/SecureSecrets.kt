package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Segredo HTTPS cifrado em Android Keystore, não em texto puro. */
object SecureSecrets {
    private const val ALIAS = "sentinela.sync.secret.v1"
    private const val PREFS = "secure_sync_settings"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
    fun save(ctx: Context, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString("token_enc",Base64.encodeToString(bytes,Base64.NO_WRAP)).apply()
    }
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove("token_enc").commit()
    }
    fun read(ctx: Context): String {
        val raw = ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("token_enc",null) ?: return ""
        return try {
            val bytes = Base64.decode(raw,Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
        } catch (_:Exception) { "" }
    }
}
