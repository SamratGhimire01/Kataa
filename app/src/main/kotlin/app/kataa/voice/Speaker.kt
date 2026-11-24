package app.kataa.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speaks Kataa's short replies with the phone's own text-to-speech (offline voices where installed). */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault().takeIf { it.language == "en" } ?: Locale.US
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kataa")
    }

    fun stop() { if (ready) tts.stop() }

    fun shutdown() = tts.shutdown()
}
