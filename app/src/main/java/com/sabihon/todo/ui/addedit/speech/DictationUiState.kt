package com.sabihon.todo.ui.addedit.speech

/**
 * The state the dictation strip reads.
 *
 * Deliberately not part of `AddEditUiState`: the recogniser is a framework service
 * connection owned by the screen, so its state lives with the screen and dies with it.
 */
data class DictationUiState(
    /** The field being dictated into, while a session is open. */
    val target: DictationTarget? = null,
    val isListening: Boolean = false,
    /** One line, shown after a session ends without text or could not start. */
    val message: String? = null
)
