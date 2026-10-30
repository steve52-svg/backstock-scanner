package com.frozenpull.scanner

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

enum class Cmd { PULLED, CANT, NEXT, BACK, REPEAT, STOP }

/**
 * Talks and listens for hands-free pulling, using the phone's own speech engine
 * (works offline when the phone has the offline speech pack, which most do).
 *
 * It never listens while it is talking, so it doesn't hear itself.
 */
class Voice(
    private val context: Context,
    private val onCommand: (Cmd) -> Unit,
    private val onStatus: (String) -> Unit,
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null
    private var rec: SpeechRecognizer? = null
    private var useOnDevice = true
    private var speaking = false
    private var actedThisTurn = false
    private var n = 0

    /** Whether it should be listening for commands. */
    var listening = false
        private set
    /** Whether it reads items aloud. */
    var talk = true

    fun start() {
        tts = TextToSpeech(context) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
                pending?.let { pending = null; speak(it) }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { main.post { speaking = false; listen() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { speaking = false; listen() } }
        })
    }

    fun speak(text: String) {
        if (!talk) { listen(); return }
        if (!ttsReady) { pending = text; return }
        stopRec()
        speaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${n++}")
    }

    fun setListening(on: Boolean) {
        listening = on
        if (on) { if (!speaking) listen() } else { stopRec(); onStatus("Voice off") }
    }

    /** Stops talking and listening right away. */
    fun hush() {
        listening = false
        pending = null
        stopRec()
        try { tts?.stop() } catch (_: Exception) { }
        speaking = false
        onStatus("Voice off")
    }

    fun shutdown() {
        listening = false
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (_: Exception) { }
        rec = null
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private fun create(): SpeechRecognizer? {
        val onDeviceOk = Build.VERSION.SDK_INT >= 31 && useOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return when {
            onDeviceOk -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }

    private fun listen() {
        if (!listening || speaking) return
        if (rec == null) {
            rec = create()?.also { it.setRecognitionListener(this) }
            if (rec == null) { listening = false; onStatus("Voice isn't available on this phone. Use the buttons or volume keys."); return }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        actedThisTurn = false
        try { rec?.startListening(i); onStatus("Listening…") } catch (_: Exception) { again(600) }
    }

    private fun stopRec() { main.removeCallbacksAndMessages(null); try { rec?.cancel() } catch (_: Exception) { } }

    private fun again(delay: Long = 250) { main.removeCallbacksAndMessages(null); main.postDelayed({ listen() }, delay) }

    private fun act(c: Cmd) {
        if (actedThisTurn) return
        actedThisTurn = true
        stopRec()
        onCommand(c) // the screen answers by speaking, and listening resumes when it's done
    }

    override fun onPartialResults(b: Bundle?) {
        val heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        heard.firstNotNullOfOrNull { parse(it) }?.let { act(it) }
    }

    override fun onResults(b: Bundle?) {
        val heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val c = heard.firstNotNullOfOrNull { parse(it) }
        if (c != null) act(c) else { if (heard.isNotEmpty()) onStatus("Heard \"${heard.first()}\". Say Pulled, Can't find, Next, Back or Repeat."); again() }
    }

    override fun onError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { listening = false; onStatus("Allow the microphone to use voice"); return }
            12, 13 -> { // on-device language not supported / not downloaded: fall back to the regular recognizer
                useOnDevice = false; try { rec?.destroy() } catch (_: Exception) { }; rec = null; again(100); return
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { again(800); return }
            SpeechRecognizer.ERROR_CLIENT -> { try { rec?.destroy() } catch (_: Exception) { }; rec = null; again(500); return }
        }
        again() // no match / timeout: just keep listening
    }

    override fun onReadyForSpeech(p: Bundle?) { onStatus("Listening…") }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rms: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(t: Int, p: Bundle?) {}

    companion object {
        private val CANT = Regex("\\b(can'?t|cannot|can not|couldn'?t|could not|can find|not here|not found|missing|no find|don'?t see)\\b")
        private val PULLED = Regex("\\b(pulled|pull|pulling|pool|pulls|got it|got that|done|check|yes|yep|yeah)\\b")
        private val NEXT = Regex("\\b(next|skip|later)\\b")
        private val BACK = Regex("\\b(back|previous|undo)\\b")
        private val REPEAT = Regex("\\b(repeat|again|what|say that|huh|where)\\b")
        private val STOP = Regex("\\b(stop listening|stop|quiet|pause)\\b")

        fun parse(s: String): Cmd? {
            val t = s.lowercase().replace("’", "'")
            return when {
                CANT.containsMatchIn(t) -> Cmd.CANT
                STOP.containsMatchIn(t) -> Cmd.STOP
                PULLED.containsMatchIn(t) -> Cmd.PULLED
                NEXT.containsMatchIn(t) -> Cmd.NEXT
                BACK.containsMatchIn(t) -> Cmd.BACK
                REPEAT.containsMatchIn(t) -> Cmd.REPEAT
                else -> null
            }
        }
    }
}
