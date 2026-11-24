package app.kataa.data

import android.content.Context
import android.net.Uri
import app.kataa.core.KataaCore
import app.kataa.core.backup.EncryptedBackup
import app.kataa.core.backup.ImportReport

/** Export / import the encrypted backup to a file the user picks (Drive, Downloads, USB). Run off the main thread. */
object BackupFiles {
    const val MIME = "application/octet-stream"
    fun suggestedName(nowMs: Long) = "kataa-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(nowMs)}.kataa"

    fun export(context: Context, core: KataaCore, to: Uri, passphrase: CharArray) {
        val bytes = EncryptedBackup.seal(core.backup.exportJson(), passphrase)
        context.contentResolver.openOutputStream(to, "wt")!!.use { it.write(bytes) }
    }

    fun import(context: Context, core: KataaCore, from: Uri, passphrase: CharArray): ImportReport {
        val bytes = context.contentResolver.openInputStream(from)!!.use { it.readBytes() }
        val report = core.backup.importJson(EncryptedBackup.open(bytes, passphrase))
        core.processPending()
        return report
    }
}
