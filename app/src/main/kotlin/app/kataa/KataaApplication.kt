package app.kataa

import android.app.Application
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.model.GeoPoint
import app.kataa.core.assistant.Reply
import app.kataa.core.session.KataaSession
import app.kataa.core.session.KataaState
import app.kataa.triggers.Triggers
import app.kataa.data.CoreProvider
import app.kataa.data.MindProvider
import app.kataa.voice.AndroidSpeechEngine
import kotlinx.datetime.TimeZone

class KataaApplication : Application() {
    /** After the owner unlocks with fingerprint/PIN, Private answers are shown for a short while. */
    @Volatile private var privateUnlockedUntil = 0L

    fun unlockPrivateFor(ms: Long) { privateUnlockedUntil = System.currentTimeMillis() + ms }

    lateinit var cores: CoreProvider
        private set

    /** The on-device AI. Handed to the assistant on the core thread once loaded. */
    val mind: MindProvider by lazy {
        MindProvider(this) { m -> cores.worker.execute { runCatching { cores.core.assistant.mind = m } } }
    }

    /**
     * Loads the AI if Smart mode is on (no-op when it is loaded, loading, or can't run here). Called only when
     * the user opens Kataa, never for a background alarm or geofence, so those stay light.
     */
    fun ensureMind() {
        if (app.kataa.ui.Look.smartMode(this) && (mind.status == MindProvider.Status.OFF || mind.status == MindProvider.Status.FAILED)) mind.load()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The AI is ~1 GB: give it back first when Android needs memory. It reloads when Kataa opens again.
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) mind.unload()
    }

    /** One conversation state for the whole app: the orb, the power-key overlay and the widget share it. */
    val session: KataaSession by lazy {
        KataaSession(
            core = { cores.core },
            speech = AndroidSpeechEngine.createOrNull(this),
            context = {
                val now = System.currentTimeMillis()
                val loc = Triggers.recentLocation(this)?.let { GeoPoint(it.latitude, it.longitude, it.accuracy.toDouble()) }
                AssistantContext(now, TimeZone.currentSystemDefault(), location = loc, privateUnlocked = now < privateUnlockedUntil)
            },
            freshLocation = { Triggers.currentLocation(this)?.let { GeoPoint(it.latitude, it.longitude, it.accuracy.toDouble()) } },
            runOnCore = { block ->
                cores.worker.execute {
                    block()
                    // Keep OS wake-ups in sync with whatever that request changed.
                    runCatching {
                        Triggers.refreshAlarm(this)
                        val reply = (session.state.value as? KataaState.Done)?.reply
                        val placesChanged = reply is Reply.PlaceSaved ||
                            (reply is Reply.ReminderSet && reply.reminders.any { it.placeTarget != null })
                        if (placesChanged) Triggers.refreshGeofences(this, null)
                    }
                }
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        cores = CoreProvider(this)
        // Warm up (open database, load model) in the background so the first question is instant.
        cores.worker.execute {
            runCatching { cores.core.processPending() }
            runCatching { Triggers.refreshAll(this) }
        }
    }
}
