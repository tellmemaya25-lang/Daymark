package com.sabihon.todo.core.dictation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Important Android SpeechRecognizer invariants:
 *  1. Undocumented `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` extras cause modern
 *     Google Speech Services (Android 12–15 & emulators) to abort immediately with
 *     `ERROR_CLIENT` or `ERROR_NO_MATCH`, so we rely on native endpointing instead.
 *  2. Calling `startListening()` synchronously inside `onError`/`onResults` on the same
 *     `SpeechRecognizer` instance races the binder teardown and fails immediately with
 *     `ERROR_RECOGNIZER_BUSY` (8) or `ERROR_CLIENT` (5). Every re-arm destroys the
 *     finished instance and starts a fresh `SpeechRecognizer` on the main looper after
 *     a short settle delay.
 */
class DictationController(private val context: Context) {

    private val _state = MutableStateFlow(DictationUiState())
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    private var stopFallbackRunnable: Runnable? = null

    private var recognizer: SpeechRecognizer? = null
    private var buffer: DictationBuffer? = null
    private var onText: ((String) -> Unit)? = null

    private var sessionOriginalText: String = ""
    private var sessionMultiline: Boolean = false
    private var sessionReplace: Boolean = false
    private var stopping: Boolean = false

    /**
     * How many consecutive cycles heard nothing at all.
     */
    private var silentRestarts = 0

    /**
     * True once a partial or final phrase has been written into the field during this
     * session. Cancel only rewinds when it has, so opening the microphone and hearing
     * nothing never touches the text.
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

        clearScheduledCallbacks()
        silentRestarts = 0
        stopping = false
        published = false
        sessionOriginalText = baseText
        sessionMultiline = multiline
        sessionReplace = replace
        buffer = DictationBuffer(baseText, multiline, replace)
        this.onText = onText

        _state.value = DictationUiState(target = target, isListening = true)

        if (!startFreshRecognizer()) {
            teardown(restore = false)
            _state.value = DictationUiState(message = COULD_NOT_START)
        }
    }

    /** Ends the sentence in flight and keeps what was heard. */
    fun stop() {
        if (!_state.value.isListening) return
        stopping = true
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null

        val active = recognizer
        if (active == null) {
            teardown(restore = false)
            _state.value = DictationUiState()
            return
        }

        runCatching { active.stopListening() }
        // Safety net: if the speech engine was between cycles or ignores stopListening(),
        // finish cleanly after 900 ms keeping everything already dictated.
        val fallback = Runnable {
            if (_state.value.isListening && stopping) {
                teardown(restore = false)
                _state.value = DictationUiState()
            }
        }
        stopFallbackRunnable = fallback
        mainHandler.postDelayed(fallback, STOP_FALLBACK_MS)
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

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun destroyRecognizerOnly() {
        recognizer?.let { speech ->
            runCatching { speech.setRecognitionListener(null) }
            runCatching { speech.cancel() }
            runCatching { speech.destroy() }
        }
        recognizer = null
    }

    private fun startFreshRecognizer(): Boolean {
        destroyRecognizerOnly()
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: return false
        created.setRecognitionListener(Listener(created))
        recognizer = created
        return runCatching { created.startListening(buildIntent()) }.isSuccess
    }

    private fun scheduleRestart(delayMs: Long) {
        restartRunnable?.let(mainHandler::removeCallbacks)
        destroyRecognizerOnly()
        val task = Runnable {
            restartRunnable = null
            if (!_state.value.isListening || stopping) return@Runnable
            if (!startFreshRecognizer()) {
                teardown(restore = !published)
                _state.value = DictationUiState(message = COULD_NOT_START)
            }
        }
        restartRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun clearScheduledCallbacks() {
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
        stopFallbackRunnable?.let(mainHandler::removeCallbacks)
        stopFallbackRunnable = null
    }

    private fun publish(phrase: String): String? {
        val text = buffer?.render(phrase) ?: return null
        published = true
        onText?.invoke(text)
        return text
    }

    private fun teardown(restore: Boolean) {
        clearScheduledCallbacks()
        if (restore && published) {
            onText?.invoke(sessionOriginalText)
        }
        destroyRecognizerOnly()
        buffer = null
        onText = null
        stopping = false
        published = false
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun messageFor(error: Int): String = when (error) {
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

    private inner class Listener(
        private val owner: SpeechRecognizer
    ) : RecognitionListener {

        private val isCurrent: Boolean get() = recognizer === owner

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(audioBuffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent) return
            firstResult(partialResults)?.let(::publish)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent) return
            silentRestarts = 0
            val heard = firstResult(results)

            if (heard != null) {
                val committed = publish(heard)
                // In form fields (not one-shot Search), keep listening across pauses
                // until the user taps Done or taps the mic again.
                if (!stopping && !sessionReplace && committed != null) {
                    buffer = DictationBuffer(
                        original = committed,
                        multiline = sessionMultiline,
                        replace = false
                    )
                    scheduleRestart(CONTINUE_DELAY_MS)
                    return
                }
                teardown(restore = false)
                _state.value = DictationUiState()
                return
            }

            if (!stopping && silentRestarts < MAX_SILENT_RESTARTS) {
                silentRestarts++
                scheduleRestart(RESTART_DELAY_MS)
                return
            }

            teardown(restore = !published)
            _state.value = DictationUiState(
                message = if (published) null else NOTHING_HEARD
            )
        }

        override fun onError(error: Int) {
            if (!isCurrent) return

            if (stopping) {
                teardown(restore = false)
                _state.value = DictationUiState()
                return
            }

            // Silence or transient engine teardown contention: recreate after a short
            // delay rather than shutting the microphone off in the user's face.
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT

            if (recoverable && silentRestarts < MAX_SILENT_RESTARTS) {
                silentRestarts++
                scheduleRestart(RESTART_DELAY_MS)
                return
            }

            val wasSilence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            // If we already captured words earlier in the session, keep them!
            teardown(restore = !published)
            _state.value = DictationUiState(
                message = when {
                    published -> null
                    wasSilence -> NOTHING_HEARD_AFTER_RETRIES
                    else -> messageFor(error)
                }
            )
        }
    }

    private companion object {
        const val NOTHING_HEARD = "Didn't catch that — try again"
        const val NOTHING_HEARD_AFTER_RETRIES = "Didn't catch anything — tap the mic and try again"

        /** Settle delay before spinning up a fresh SpeechRecognizer after silence/busy. */
        const val RESTART_DELAY_MS = 300L
        const val CONTINUE_DELAY_MS = 200L
        const val STOP_FALLBACK_MS = 900L

        /** Consecutive silent cycles allowed before closing automatically. */
        const val MAX_SILENT_RESTARTS = 6
        const val NO_SERVICE = "The speech service can't be reached — type instead"
        const val MICROPHONE_OFF = "Microphone is off for daymark . — type instead, or turn it on in Settings"
        const val BUSY = "The speech service is busy — tap the mic again"
        const val NO_MICROPHONE = "The microphone didn't start — try again"
        const val STOPPED = "Dictation stopped — tap the mic to try again"
        const val NO_RECOGNIZER = "This device has no speech service — type instead"
        const val COULD_NOT_START = "Dictation couldn't start — tap the mic again"
    }
}
