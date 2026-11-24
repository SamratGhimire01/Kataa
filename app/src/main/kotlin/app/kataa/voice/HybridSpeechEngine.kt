package app.kataa.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.kataa.core.session.SpeechEngine
import app.kataa.core.session.SpeechError

/**
 * With internet: Google's online recognizer (fast, shows words live, very accurate). Without internet, or
 * whenever it can't listen: Kataa's own offline recognizer, instantly, with no popup and nothing to download.
 * Kataa itself never sends anything: the online recognizer is the phone's Google app.
 */
class HybridSpeechEngine(context: Context, private val offline: WhisperSpeechEngine) : SpeechEngine {
    private val ctx = context.applicationContext
    private val online = AndroidSpeechEngine.onlineOnly(ctx)
    @Volatile private var current: SpeechEngine = offline

    override fun start(listener: SpeechEngine.Listener) {
        offline.warmUp()
        if (!hasInternet()) { current = offline; offline.start(listener); return }
        current = online
        online.start(object : SpeechEngine.Listener by listener {
            override fun onError(error: SpeechError) {
                when (error) {
                    SpeechError.NO_SPEECH, SpeechError.NO_PERMISSION -> listener.onError(error)
                    // Online voice can't listen right now (no Google app, slow network): switch to Kataa's own.
                    else -> { current = offline; offline.start(listener) }
                }
            }
        })
    }

    override fun stop() = current.stop()

    override fun cancel() { online.cancel(); offline.cancel() }

    private fun hasInternet(): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
