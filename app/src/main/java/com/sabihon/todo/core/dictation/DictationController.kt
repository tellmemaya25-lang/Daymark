package com.sabihon.todo.core.dictation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One dictation session at a time, on top of the system speech recogniser.
 *
 * The recogniser is created when the microphone is tapped and destroyed the moment the
 * session ends - never held while the screen sits idle, and never shared app-wide. A
 * recogniser that outlives its screen keeps the microphone open and leaves the next
 * screen with ERROR_RECOGNIZER_BUSY.
 *
 * There is no animation and no dialog anywhere in here: speaking either lands text in a
 * field that is already on screen, or it produces one line in the strip.
 */
class DictationController(private val context: Context) {

    private val _state = MutableStateFlow(DictationUiState())
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var buffer: DictationBuffer? = null
    private var onText: ((String) -> Unit)? = null

    /**
     * True once a partial has been written into the field. Cancel only rewinds when it
     * has, so opening the microphone and hearing nothing never touches the text.
     */
    private var published = false

    fun start(
        target: DictationTarget,
        baseText: String,
        multiline: Boolean,
        onText: (String) -> Unit,
        /** Search replaces the box; the Add Task form appends to it. */
        replace: Boolean = false
    ) {
        // A second microphone tap while one is open puts the first field back first.
        if (_state.value.isListening) cancel()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = DictationUiState(message = NO_RECOGNIZER)
            return
        }

        buffer = DictationBuffer(baseText, multiline, replace)
        this.onText = onText
        published = false

        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (created == null) {
            teardown(restore = false)
            _state.value = DictationUiState(message = COULD_NOT_START)
            return
        }

        created.setRecognitionListener(Listener())
        recognizer = created
        _state.value = DictationUiState(target = target, isListening = true)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Free form, not web search: a task title is not a query.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // No EXTRA_LANGUAGE: the device's own language is the one the user is
            // already typing in, and forcing a tag invites ERROR_LANGUAGE_NOT_SUPPORTED.
        }

        runCatching { created.startListening(intent) }.onFailure {
            teardown(restore = true)
            _state.value = DictationUiState(message = COULD_NOT_START)
        }
    }

    /** Ends the sentence in flight and keeps what was heard. */
    fun stop() {
        if (!_state.value.isListening) return
        runCatching { recognizer?.stopListening() }
    }

    /** Throws away what was heard and puts the field back exactly as it was. */
    fun cancel() {
        teardown(restore = true)
        _state.value = DictationUiState()
    }

    fun permissionDenied() {
        teardown(restore = true)
        _state.value = DictationUiState(message = MICROPHONE_OFF)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Called when the screen goes away: destroys the recogniser, keeps the text. */
    fun release() {
        teardown(restore = false)
        _state.value = DictationUiState()
    }

    private fun publish(phrase: String) {
        val text = buffer?.render(phrase) ?: return
        published = true
        onText?.invoke(text)
    }

    private fun teardown(restore: Boolean) {
        if (restore && published) {
            buffer?.let { onText?.invoke(it.originalText) }
        }
        recognizer?.let { speech ->
            runCatching { speech.cancel() }
            runCatching { speech.destroy() }
        }
        recognizer = null
        buffer = null
        onText = null
        published = false
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun messageFor(error: Int): String = when (error) {
        // Silence. The most common outcome there is, and not a failure.
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING_HEARD

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> NO_SERVICE

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MICROPHONE_OFF
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY
        SpeechRecognizer.ERROR_AUDIO -> NO_MICROPHONE
        else -> STOPPED
    }

    private inner class Listener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(audioBuffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(::publish)
        }

        override fun onResults(results: Bundle?) {
            val heard = firstResult(results)
            if (heard == null) {
                teardown(restore = true)
                _state.value = DictationUiState(message = NOTHING_HEARD)
                return
            }
            publish(heard)
            teardown(restore = false)
            _state.value = DictationUiState()
        }

        override fun onError(error: Int) {
            teardown(restore = true)
            _state.value = DictationUiState(message = messageFor(error))
        }
    }

    private companion object {
        const val NOTHING_HEARD = "Didn't catch that — try again"
        const val NO_SERVICE = "The speech service can't be reached — type instead"
        const val MICROPHONE_OFF = "Microphone is off for daymark . — type instead, or turn it on in Settings"
        const val BUSY = "The speech service is busy — tap the mic again"
        const val NO_MICROPHONE = "The microphone didn't start — try again"
        const val STOPPED = "Dictation stopped — tap the mic to try again"
        const val NO_RECOGNIZER = "This device has no speech service — type instead"
        const val COULD_NOT_START = "Dictation couldn't start — tap the mic again"
    }
}
