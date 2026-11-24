package app.kataa

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.kataa.data.BackupFiles
import app.kataa.triggers.Triggers
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.kataa.core.assistant.ReplyText
import app.kataa.core.session.KataaState
import app.kataa.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Temporary developer console to try the backend on a real phone: tap Talk or type, see what Kataa
 * does. The real interface (orb, Kai, animation, design) is the frontend phase and replaces this; it
 * will read the same [KataaState] stream.
 */
class DevConsoleActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var speaker: Speaker

    private val session get() = (application as KataaApplication).session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setPadding(pad, pad, pad, 0); textSize = 18f }
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; setPadding(pad, pad, pad, pad); text = "Kataa developer console\n" }
        val talk = Button(this).apply { text = "Talk"; setOnClickListener { talk() } }
        val perms = Button(this).apply { text = "Allow reminders (notifications, location, alarms, Bluetooth)"; setOnClickListener { askReminderPermissions() } }
        val car = Button(this).apply { text = "Choose car Bluetooth"; setOnClickListener { chooseCar() } }
        val backup = Button(this).apply { text = "Back up / restore"; setOnClickListener { backupMenu() } }
        val input = EditText(this).apply {
            hint = "Or type, e.g. my keys are in the drawer"
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { v, _, _ -> session.type(v.text.toString()); v.text = ""; true }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(talk); addView(perms); addView(car); addView(backup); addView(input); addView(status)
            addView(ScrollView(context).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        scope.launch { session.state.collect(::render) }
    }

    private fun talk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        speaker.stop()
        session.tapToTalk()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) session.tapToTalk()
        if (requestCode == REQ_REMINDERS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) askReminderPermissions()
    }

    /** Step by step, as Android requires: notifications + location first, background location separately. */
    private fun askReminderPermissions() {
        val first = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            first.isNotEmpty() -> requestPermissions(first.toTypedArray(), REQ_REMINDERS)
            Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                // Android shows this as "Allow all the time" in settings.
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_REMINDERS)
            Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms() ->
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            else -> {
                log.append("\nAll reminder permissions granted.\n")
                (application as KataaApplication).cores.worker.execute { Triggers.refreshAll(this) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun chooseCar() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_REMINDERS); return
        }
        val devices = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) { log.append("\nNo paired Bluetooth devices.\n"); return }
        AlertDialog.Builder(this)
            .setTitle("Which one is your car?")
            .setItems(devices.map { (if (Build.VERSION.SDK_INT >= 30) it.alias else null) ?: it.name ?: it.address }.toTypedArray()) { _, i ->
                val d = devices[i]
                val app = application as KataaApplication
                app.cores.worker.execute { app.cores.core.triggers.setBluetoothAlias("car", d.address) }
                log.append("\nCar set to ${d.name ?: d.address}.\n")
            }
            .show()
    }

    private var pendingPass: CharArray? = null

    private fun backupMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("Save encrypted backup", "Restore from backup")) { _, which ->
            askPassphrase { pass ->
                pendingPass = pass
                val pick = if (which == 0) Intent(Intent.ACTION_CREATE_DOCUMENT).setType(BackupFiles.MIME)
                    .putExtra(Intent.EXTRA_TITLE, BackupFiles.suggestedName(System.currentTimeMillis()))
                else Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                startActivityForResult(pick.addCategory(Intent.CATEGORY_OPENABLE), if (which == 0) REQ_EXPORT else REQ_IMPORT)
            }
        }.show()
    }

    private fun askPassphrase(then: (CharArray) -> Unit) {
        val field = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "Passphrase (6+ characters)" }
        AlertDialog.Builder(this).setTitle("Backup passphrase").setView(field)
            .setPositiveButton("OK") { _, _ -> then(field.text.toString().toCharArray()) }.show()
    }

    @Deprecated("Activity result API is overkill for the temporary console")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data ?: return
        val pass = pendingPass ?: return
        pendingPass = null
        val app = application as KataaApplication
        app.cores.worker.execute {
            val msg = runCatching {
                if (requestCode == REQ_EXPORT) { BackupFiles.export(this, app.cores.core, uri, pass); "Backup saved." }
                else BackupFiles.import(this, app.cores.core, uri, pass).let { "Restored ${it.memoriesAdded} memories, ${it.remindersAdded} reminders." }
            }.getOrElse { "Backup failed: ${it.message}" }
            pass.fill(' ')
            runOnUiThread { log.append("\n$msg\n") }
            if (requestCode == REQ_IMPORT) runCatching { Triggers.refreshAll(this) }
        }
    }

    private fun render(s: KataaState) {
        status.text = when (s) {
            KataaState.Idle -> "Ready"
            is KataaState.Listening -> "Listening " + "▮".repeat((s.level * 10).toInt()) + "\n" + s.partial
            is KataaState.Thinking -> "…"
            is KataaState.Done -> ReplyText.spoken(s.reply)
            is KataaState.Failed -> "Mic problem: ${s.error}. You can type instead."
        }
        if (s is KataaState.Done) {
            // Masked: this screen shows and speaks, and must never reveal a password.
            val text = ReplyText.spoken(s.reply)
            log.append("\n> ${s.heard}\n$text\n  ${s.reply::class.simpleName}\n")
            speaker.say(text)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQ_MIC = 1
        const val REQ_REMINDERS = 2
        const val REQ_EXPORT = 3
        const val REQ_IMPORT = 4
    }
}
