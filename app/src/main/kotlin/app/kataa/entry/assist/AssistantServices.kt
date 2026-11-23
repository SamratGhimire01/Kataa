package app.kataa.entry.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import app.kataa.entry.CaptureActivity

/**
 * Lets the user pick Kataa as the phone's "Digital assistant app" (Settings > Apps > Default apps). Then
 * long-pressing the power button (or home, or the assistant gesture) opens Kataa's quick capture, even
 * on the lock screen. This is Play-policy friendly, unlike reading volume keys with an accessibility
 * service (that stays in a separate sideload build, if ever).
 */
class KataaVoiceInteractionService : VoiceInteractionService()

class KataaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = KataaSession(this)
}

private class KataaSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
    }
}

/**
 * Android requires an assistant to declare a recognition service. Kataa uses the phone's own recognizer
 * (see AndroidSpeechEngine), so this one only reports that it is not a general-purpose recognizer.
 */
class KataaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
