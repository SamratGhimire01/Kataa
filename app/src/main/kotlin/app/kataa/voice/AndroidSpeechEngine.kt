package app.kataa.voice

import android.content.ComponentName
import android.content.Context
import android.speech.RecognitionService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.kataa.core.session.SpeechEngine
import app.kataa.core.session.SpeechError
import java.util.Locale

/**
 * Speech-to-text with the phone's own recognizer: adds nothing to the app size. Prefers the on-device
 * recognizer (Android 13+), so audio stays on the phone and it works offline; falls back to the default
 * recognizer with "prefer offline". English only, in the user's English variant (en-IN, en-GB, ...).
 *
 * SpeechRecognizer must be used on the main thread; every call here is posted there.
 */
class AndroidSpeechEngine private constructor(private val context: Context) : SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** One way of listening. Tried in order until one works; the one that worked is tried first next time. */
    private enum class Way { ON_DEVICE, SERVICE_OFFLINE, SERVICE_ONLINE, SYSTEM_DEFAULT }
    private var plan: List<Way> = emptyList()
    private var step = 0
    private var heardSomething = false
    @Volatile private var preferred: Way? = null

    override fun start(listener: SpeechEngine.Listener) = main.post {
        plan = ways()
        step = 0
        next(listener)
    }.let { }

    override fun stop() = main.post { recognizer?.stopListening() }.let { }

    override fun cancel() = main.post { recognizer?.cancel() }.let { }

    private fun ways(): List<Way> {
        val all = buildList {
            if (onDeviceAvailable()) add(Way.ON_DEVICE)
            if (realRecognizer(context) != null) { add(Way.SERVICE_OFFLINE); add(Way.SERVICE_ONLINE) }
            add(Way.SYSTEM_DEFAULT)
        }
        val p = preferred
        return if (p != null && p in all) listOf(p) + (all - p) else all
    }

    /** Starts the next way in the plan, or reports that this phone can't listen in-app. */
    private fun next(listener: SpeechEngine.Listener) {
        recognizer?.destroy(); recognizer = null
        while (step < plan.size) {
            val way = plan[step++]
            val r = runCatching { create(way) }.getOrNull() ?: continue
            heardSomething = false
            recognizer = r
            r.setRecognitionListener(Bridge(listener, way))
            val ok = runCatching { r.startListening(intent(offline = way != Way.SERVICE_ONLINE && way != Way.SYSTEM_DEFAULT)) }.isSuccess
            if (ok) return
            r.destroy(); recognizer = null
        }
        listener.onError(SpeechError.UNAVAILABLE)
    }

    private fun create(way: Way): SpeechRecognizer? = when (way) {
        Way.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        // Never the system default blindly first: once Kataa is the phone's assistant, the default points at
        // Kataa's own placeholder service. Use a real recognizer from another app.
        Way.SERVICE_OFFLINE, Way.SERVICE_ONLINE -> realRecognizer(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
        Way.SYSTEM_DEFAULT -> if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    private fun intent(offline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, englishTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // People pause mid-thought ("remind me to... uh... call mom"): allow a slightly longer pause.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    private inner class Bridge(private val l: SpeechEngine.Listener, private val way: Way) : RecognitionListener {
        private var finished = false
        override fun onRmsChanged(rmsdB: Float) = l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onPartialResults(partialResults: Bundle?) {
            first(partialResults)?.let { heardSomething = true; l.onPartial(it) }
        }
        override fun onResults(results: Bundle?) {
            if (finished) return
            finished = true
            preferred = way
            l.onFinal(first(results).orEmpty())
        }
        override fun onError(error: Int) {
            if (finished) return
            finished = true
            lastErrorCode = error
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    preferred = way
                    l.onError(SpeechError.NO_SPEECH)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> l.onError(SpeechError.NO_PERMISSION)
                else -> {
                    // This way can't listen right now (model not downloaded, no network, recognizer busy or
                    // broken): ask for the offline English model if we can, then try the next way.
                    if (way == Way.ON_DEVICE && Build.VERSION.SDK_INT >= 33) {
                        runCatching { recognizer?.triggerModelDownload(intent(offline = true)) }
                    }
                    if (preferred == way) preferred = null
                    main.post { next(l) }
                }
            }
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun englishTag(): String {
        val l = Locale.getDefault()
        return if (l.language == "en") l.toLanguageTag() else "en-US"
    }

    companion object {
        /** The last raw SpeechRecognizer error code, shown in the "couldn't catch that" message. */
        @Volatile var lastErrorCode: Int = 0
            private set

        /** The phone's own voice typing screen, used when in-app listening isn't possible. */
        fun voiceTypingIntent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val l = Locale.getDefault()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (l.language == "en") l.toLanguageTag() else "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I remember?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }.takeIf { it.resolveActivity(context.packageManager) != null }

        /** The recognized text from [voiceTypingIntent]'s result. */
        fun voiceTypingResult(data: Intent?): String? =
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Always returns an engine: which recognizers exist is checked each time listening starts (a voice
         * app can be installed or updated later). If none can listen, it reports UNAVAILABLE and the screen
         * opens the phone's own voice typing screen instead.
         */
        fun createOrNull(context: Context): AndroidSpeechEngine = AndroidSpeechEngine(context.applicationContext)

        private val PREFERRED = listOf("com.google.android.as", "com.google.android.googlequicksearchbox", "com.google.android.tts")

        /** Installed recognition services other than Kataa's own, Google's first. */
        fun realRecognizer(context: Context): ComponentName? {
            val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo }.filter { it.packageName != context.packageName }
            val pick = services.minByOrNull { PREFERRED.indexOf(it.packageName).let { i -> if (i < 0) PREFERRED.size else i } }
            return pick?.let { ComponentName(it.packageName, it.name) }
        }
    }
}
