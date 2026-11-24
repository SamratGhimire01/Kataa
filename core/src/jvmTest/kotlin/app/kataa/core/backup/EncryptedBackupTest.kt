package app.kataa.core.backup

import app.kataa.core.model.NewMemory
import app.kataa.core.newCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class EncryptedBackupTest {
    @Test fun roundTripIntoANewPhone() {
        val old = newCore()
        old.memories.save(NewMemory("My bank PIN is 4321"))
        old.memories.save(NewMemory("Passport is in the brown suitcase"))
        val file = EncryptedBackup.seal(old.backup.exportJson(), "correct horse".toCharArray(), iterations = 10_000)
        assertFalse(String(file, Charsets.ISO_8859_1).contains("4321"))

        val fresh = newCore()
        val report = fresh.backup.importJson(EncryptedBackup.open(file, "correct horse".toCharArray()))
        assertEquals(2, report.memoriesAdded)
        assertEquals(2L, fresh.memories.countLive())
    }

    @Test fun wrongPassphraseOrTamperingIsRejected() {
        val file = EncryptedBackup.seal("{\"x\":1}", "correct horse".toCharArray(), iterations = 10_000)
        assertFailsWith<WrongPassphraseException> { EncryptedBackup.open(file, "wrong horse".toCharArray()) }
        val tampered = file.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertFailsWith<WrongPassphraseException> { EncryptedBackup.open(tampered, "correct horse".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { EncryptedBackup.open("hello".encodeToByteArray(), "x".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { EncryptedBackup.seal("{}", "123".toCharArray()) }
    }
}
