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
class AndroidSpeechEngine private constructor(private val context: Context, private val onlineOnly: Boolean = false) : SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** One way of listening. Tried in order until one works; the one that worked is tried first next time. */
    private enum class Way { SERVICE_ONLINE, ON_DEVICE, SYSTEM_DEFAULT }
    private data class Attempt(val way: Way, val lang: String)
    private var plan: List<Attempt> = emptyList()
    private var step = 0
    @Volatile private var preferred: Attempt? = null

    override fun start(listener: SpeechEngine.Listener) = main.post {
        plan = attempts()
        step = 0
        next(listener)
    }.let { }

    override fun stop() = main.post { recognizer?.stopListening() }.let { }

    override fun cancel() = main.post { recognizer?.cancel() }.let { }

    /**
     * Google's own recognizer first (most accurate; uses the network when there is one, which never asks to
     * download anything), then the phone's on-device recognizer, then whatever the system default is.
     * Kataa never asks the user to download a language pack: if a way needs one, it just moves on.
     */
    private fun attempts(): List<Attempt> {
        val langs = languages()
        val all = buildList {
            if (realRecognizer(context) != null) langs.forEach { add(Attempt(Way.SERVICE_ONLINE, it)) }
            if (!onlineOnly) {
                if (onDeviceAvailable()) langs.forEach { add(Attempt(Way.ON_DEVICE, it)) }
                langs.forEach { add(Attempt(Way.SYSTEM_DEFAULT, it)) }
            }
        }
        val p = preferred
        return if (p != null && p in all) listOf(p) + (all - p) else all
    }

    /** Starts the next attempt in the plan, or reports that this phone can't listen in-app. */
    private fun next(listener: SpeechEngine.Listener) {
        recognizer?.destroy(); recognizer = null
        while (step < plan.size) {
            val a = plan[step++]
            val r = runCatching { create(a.way) }.getOrNull() ?: continue
            recognizer = r
            r.setRecognitionListener(Bridge(listener, a))
            val ok = runCatching { r.startListening(intent(a)) }.isSuccess
            if (ok) return
            r.destroy(); recognizer = null
        }
        listener.onError(SpeechError.UNAVAILABLE)
    }

    private fun create(way: Way): SpeechRecognizer? = when (way) {
        Way.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        // Never the system default blindly first: once Kataa is the phone's assistant, the default points at
        // Kataa's own placeholder service. Use a real recognizer from another app.
        Way.SERVICE_ONLINE -> realRecognizer(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
        Way.SYSTEM_DEFAULT -> if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    private fun intent(a: Attempt) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.lang)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, a.lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Never "prefer offline": with Google's recognizer that is what pops up "download English (India)".
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        // Several guesses, so the one that makes sense ("call mom", not "call more") can win.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(BIAS))
        // People pause mid-thought ("remind me to... uh... call mom"): allow a slightly longer pause.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    private inner class Bridge(private val l: SpeechEngine.Listener, private val a: Attempt) : RecognitionListener {
        private var finished = false
        private var ready = false
        private var heard = false
        private val startedAt = android.os.SystemClock.elapsedRealtime()
        override fun onRmsChanged(rmsdB: Float) = l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onPartialResults(partialResults: Bundle?) {
            all(partialResults).firstOrNull()?.let { heard = true; l.onPartial(it) }
        }
        override fun onResults(results: Bundle?) {
            if (finished) return
            finished = true
            preferred = a
            l.onFinal(best(all(results)))
        }
        override fun onError(error: Int) {
            if (finished) return
            finished = true
            lastErrorCode = error
            val instant = !ready || android.os.SystemClock.elapsedRealtime() - startedAt < 700
            when {
                // A real "you said nothing": the recognizer was listening and heard nothing.
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !instant -> {
                    preferred = a
                    l.onError(SpeechError.NO_SPEECH)
                }
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> l.onError(SpeechError.NO_PERMISSION)
                // Something broke after the user already spoke (network dropped): don't make them wait on more tries.
                heard -> l.onError(SpeechError.OTHER)
                else -> {
                    // This attempt can't listen (no network, language pack missing, recognizer busy or broken, or
                    // it gave up before listening): quietly try the next one.
                    if (preferred == a) preferred = null
                    main.postDelayed({ next(l) }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 250L else 0L)
                }
            }
        }
        override fun onReadyForSpeech(params: Bundle?) { ready = true }
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        private fun all(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * English variants to try, best first. Recognizers don't all know "English (Nepal)": South Asian English
     * maps to Indian English, which also understands the accent best; US English is the last resort.
     */
    private fun languages(): List<String> = englishVariants(Locale.getDefault())

    companion object {
        private val SOUTH_ASIA = setOf("NP", "IN", "BD", "LK", "PK", "BT", "MV")

        internal fun englishVariants(l: Locale): List<String> {
            val first = when {
                l.language == "en" && l.country in SOUTH_ASIA -> "en-IN"
                l.language == "en" && l.country.isNotEmpty() -> l.toLanguageTag()
                l.language in setOf("ne", "hi", "bn", "si", "ur", "ta", "te", "mr", "gu", "kn", "ml", "pa") -> "en-IN"
                else -> "en-US"
            }
            return listOf(first, "en-IN", "en-US").distinct()
        }

        /** Words people say to Kataa; recognizers lean toward them when unsure. */
        private val BIAS = listOf(
            "remind me", "where are my", "where is my", "mom", "dad", "password", "passport", "keys", "wallet",
            "charger", "medicine", "Big Mart", "Bhatbhateni", "Kataa", "undo", "this is", "parked",
        )

        /** Words that make a guess more likely to be what was meant. */
        private val LIKELY = setOf(
            "mom", "mum", "dad", "mother", "father", "sister", "brother", "wife", "husband", "remind", "where", "my",
            "password", "keys", "key", "wallet", "phone", "charger", "medicine", "meds", "milk", "buy", "call", "pay",
            "tomorrow", "today", "tonight", "morning", "evening", "pm", "am", "home", "office", "car", "bike", "parked",
            "drawer", "bag", "table", "room", "kitchen", "bed", "is", "are", "in", "on", "under",
        )

        /** Picks the recognizer's best guess, unless a close alternative is made of much more likely words. */
        internal fun best(guesses: List<String>): String {
            if (guesses.isEmpty()) return ""
            fun score(g: String) = g.lowercase().split(Regex("[^a-z']+")).count { it in LIKELY }
            val top = guesses.first()
            val alt = guesses.drop(1).take(3).filter { it.split(' ').size == top.split(' ').size }.maxByOrNull(::score)
            return if (alt != null && score(alt) > score(top)) alt else top
        }

        /** The last raw SpeechRecognizer error code, shown in the "couldn't catch that" message. */
        @Volatile var lastErrorCode: Int = 0
            private set

        /** The phone's own voice typing screen, used when in-app listening isn't possible. */
        fun voiceTypingIntent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, englishVariants(Locale.getDefault()).first())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I remember?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }.takeIf { it.resolveActivity(context.packageManager) != null }

        /** The recognized text from [voiceTypingIntent]'s result. */
        fun voiceTypingResult(data: Intent?): String? =
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                .let { best(it) }.takeIf { it.isNotEmpty() }

        /**
         * Always returns an engine: which recognizers exist is checked each time listening starts (a voice
         * app can be installed or updated later). If none can listen, it reports UNAVAILABLE and the screen
         * opens the phone's own voice typing screen instead.
         */
        fun createOrNull(context: Context): AndroidSpeechEngine = AndroidSpeechEngine(context.applicationContext)

        /** Only Google's online recognizer (no on-device fallbacks): used while there is internet. */
        fun onlineOnly(context: Context): AndroidSpeechEngine = AndroidSpeechEngine(context.applicationContext, onlineOnly = true)

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
