package app.kataa.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key. A random 32-byte key encrypts the database (SQLCipher, AES-256). That key is
 * stored only wrapped by a non-exportable AES key in the Android Keystore (hardware-backed, StrongBox
 * when available), so copying the app's files off the phone does not reveal anything.
 *
 * Not bound to user authentication on purpose: reminders must fire with the phone locked. Private
 * memories get a second, biometric gate in the UI.
 */
object DatabaseKey {
    private const val ALIAS = "kataa_db_wrap_v1"
    private const val FILE = "db.key"

    fun get(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        val wrapKey = wrappingKey()
        if (file.exists()) {
            val blob = file.readBytes()
            val iv = blob.copyOfRange(1, 1 + blob[0])
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(128, iv)) }
            return cipher.doFinal(blob, 1 + iv.size, blob.size - 1 - iv.size)
        }
        val dbKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey) }
        val sealed = cipher.doFinal(dbKey)
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed)
        check(tmp.renameTo(file)) { "could not store database key" }
        return dbKey
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
        return try {
            generate(strongBox = true)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 28 && e !is StrongBoxUnavailableException && e.cause !is StrongBoxUnavailableException) throw e
            generate(strongBox = false)
        }
    }
}
