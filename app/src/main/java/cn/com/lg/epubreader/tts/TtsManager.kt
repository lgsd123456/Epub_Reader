package cn.com.lg.epubreader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsManager(context: Context, private val onInit: (Boolean) -> Unit) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var activeToken: String? = null
    private var activeFinalUtteranceId: String? = null
    private var onComplete: (() -> Unit)? = null
    private var sessionCounter: Long = 0L

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.getDefault())
                val languageOk = langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        val expectedFinal = activeFinalUtteranceId ?: return
                        if (utteranceId == expectedFinal) {
                            val completion = onComplete
                            clearActiveSession()
                            completion?.invoke()
                        } 
                    }
                    override fun onError(utteranceId: String?) {
                        val token = activeToken ?: return
                        if (utteranceId != null && utteranceId.startsWith(token)) {
                            val completion = onComplete
                            clearActiveSession()
                            completion?.invoke()
                        }
                    }
                })
                isInitialized = languageOk
                onInit(languageOk)
            } else {
                onInit(false)
            }
        }
    }

    fun isReady(): Boolean = isInitialized

    fun speak(
        text: String,
        flush: Boolean = true,
        completion: (() -> Unit)? = null
    ): Boolean {
        if (!isInitialized) return false
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            completion?.invoke()
            return true
        }

        if (flush) {
            tts?.stop()
            clearActiveSession()
        }

        val token = "tts_${++sessionCounter}_"
        activeToken = token
        onComplete = completion

        val maxChunkLength = 3500
        val sentenceChunks = trimmed
            .split(Regex("(?<=[。！？.!?])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        for (chunk in sentenceChunks) {
            if (chunk.length <= maxChunkLength) {
                chunks.add(chunk)
            } else {
                var start = 0
                while (start < chunk.length) {
                    val end = (start + maxChunkLength).coerceAtMost(chunk.length)
                    chunks.add(chunk.substring(start, end))
                    start = end
                }
            }
        }

        if (chunks.isEmpty()) {
            completion?.invoke()
            clearActiveSession()
            return true
        }

        for (i in chunks.indices) {
            val utteranceId = if (i == chunks.size - 1) "${token}final" else "${token}$i"
            if (i == chunks.size - 1) {
                activeFinalUtteranceId = utteranceId
            }
            val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunks[i], queueMode, null, utteranceId)
        }
        return true
    }

    fun stop() {
        tts?.stop()
        clearActiveSession()
    }

    fun shutdown() {
        tts?.shutdown()
    }
    
    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    private fun clearActiveSession() {
        activeToken = null
        activeFinalUtteranceId = null
        onComplete = null
    }
}
