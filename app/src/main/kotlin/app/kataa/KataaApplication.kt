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
    /** Kataa's own offline speech recognizer (arm64 builds); null on phones that use Android's instead. */
    val ear: app.kataa.voice.WhisperSpeechEngine? by lazy { app.kataa.voice.WhisperSpeechEngine.createOrNull(this) }

    fun ensureMind() {
        ear?.warmUp()
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
            speech = ear?.let { app.kataa.voice.HybridSpeechEngine(this, it) } ?: AndroidSpeechEngine.createOrNull(this),
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
            // The offline map of Nepal's shops (OpenStreetMap): loaded once per app version, a few seconds.
            runCatching {
                // The build tools store "nepal.tsv.gz" unzipped as "nepal.tsv" (compressed inside the APK anyway).
                val raw = runCatching { assets.open("places/nepal.tsv") }.getOrNull()
                    ?: java.util.zip.GZIPInputStream(assets.open("places/nepal.tsv.gz"), 1 shl 16)
                raw.bufferedReader().useLines { lines -> cores.core.places.importPlacesPack(PLACES_PACK_VERSION, lines) }
            }.onFailure { android.util.Log.e("Kataa", "places pack", it) }
            runCatching { Triggers.refreshAll(this) }
        }
    }

    companion object {
        /** Bump when app/src/main/assets/places/nepal.tsv.gz is regenerated (tools/extract_places.py). */
        const val PLACES_PACK_VERSION = "osm-nepal-2026-11-24"
    }
}
