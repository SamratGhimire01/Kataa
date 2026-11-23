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
    private var usingOnDevice = false
    private var triedFallback = false

    override fun start(listener: SpeechEngine.Listener) = main.post {
        triedFallback = false
        begin(listener, onDevice = onDeviceAvailable())
    }.let { }

    override fun stop() = main.post { recognizer?.stopListening() }.let { }

    override fun cancel() = main.post { recognizer?.cancel() }.let { }

    private fun begin(listener: SpeechEngine.Listener, onDevice: Boolean) {
        recognizer?.destroy()
        usingOnDevice = onDevice
        val r = when {
            onDevice && Build.VERSION.SDK_INT >= 33 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            // Never the system default blindly: once Kataa is the phone's assistant, the default points at
            // Kataa's own placeholder service. Use a real recognizer from another app.
            else -> realRecognizer(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
                ?: SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(Bridge(listener))
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, englishTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // People pause mid-thought ("remind me to... uh... call mom"): allow a slightly longer pause.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        })
    }

    private inner class Bridge(private val l: SpeechEngine.Listener) : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) = l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onPartialResults(partialResults: Bundle?) {
            first(partialResults)?.let(l::onPartial)
        }
        override fun onResults(results: Bundle?) = l.onFinal(first(results).orEmpty())
        override fun onError(error: Int) {
            val languageProblem = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            if (usingOnDevice && !triedFallback && (languageProblem || error == SpeechRecognizer.ERROR_CLIENT)) {
                // On-device model missing for this language: use the regular recognizer once.
                triedFallback = true
                main.post { begin(l, onDevice = false) }
                return
            }
            l.onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.UNAVAILABLE
                    else -> SpeechError.OTHER
                },
            )
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun englishTag(): String {
        val l = Locale.getDefault()
        return if (l.language == "en") l.toLanguageTag() else "en-US"
    }

    companion object {
        /** Null when the phone has no speech recognizer at all (the UI then offers typing). */
        fun createOrNull(context: Context): AndroidSpeechEngine? {
            val c = context.applicationContext
            val any = realRecognizer(c) != null || (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c))
            return if (any) AndroidSpeechEngine(c) else null
        }

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
