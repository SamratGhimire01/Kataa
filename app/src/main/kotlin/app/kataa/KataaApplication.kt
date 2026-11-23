package app.kataa

import android.app.Application
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import app.kataa.core.session.KataaSession
import app.kataa.core.session.KataaState
import app.kataa.triggers.Triggers
import app.kataa.data.CoreProvider
import app.kataa.voice.AndroidSpeechEngine
import kotlinx.datetime.TimeZone

class KataaApplication : Application() {
    lateinit var cores: CoreProvider
        private set

    /** One conversation state for the whole app: the orb, the power-key overlay and the widget share it. */
    val session: KataaSession by lazy {
        KataaSession(
            core = { cores.core },
            speech = AndroidSpeechEngine.createOrNull(this),
            context = { AssistantContext(System.currentTimeMillis(), TimeZone.currentSystemDefault()) },
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
