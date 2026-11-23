package app.kataa.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : Exception("Wrong passphrase, or the backup file is damaged")

/**
 * The backup file the user keeps (Drive, USB, another phone): Kataa's JSON export, gzipped, encrypted with
 * AES-256-GCM under a key derived from the user's passphrase (PBKDF2-SHA256). Works on Android and desktop.
 *
 * Layout: "KATAABK1" | iterations (4 bytes) | salt (16) | iv (12) | ciphertext+tag.
 */
object EncryptedBackup {
    private val MAGIC = "KATAABK1".encodeToByteArray()
    private const val ITERATIONS = 310_000

    fun seal(json: String, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(passphrase.size >= 6) { "Use a passphrase of at least 6 characters" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
            updateAAD(MAGIC)
        }
        val packed = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(json.encodeToByteArray()) } }.toByteArray()
        return MAGIC + intBytes(iterations) + salt + iv + cipher.doFinal(packed)
    }

    fun open(file: ByteArray, passphrase: CharArray): String {
        if (file.size < MAGIC.size + 4 + 16 + 12 + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a Kataa backup file")
        }
        var p = MAGIC.size
        val iterations = ((file[p].toInt() and 0xff) shl 24) or ((file[p + 1].toInt() and 0xff) shl 16) or
            ((file[p + 2].toInt() and 0xff) shl 8) or (file[p + 3].toInt() and 0xff)
        p += 4
        require(iterations in 1_000..10_000_000) { "Not a Kataa backup file" }
        val salt = file.copyOfRange(p, p + 16); p += 16
        val iv = file.copyOfRange(p, p + 12); p += 12
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
            updateAAD(MAGIC)
        }
        val packed = try { cipher.doFinal(file, p, file.size - p) } catch (e: AEADBadTagException) { throw WrongPassphraseException() }
        return GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }.decodeToString()
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
