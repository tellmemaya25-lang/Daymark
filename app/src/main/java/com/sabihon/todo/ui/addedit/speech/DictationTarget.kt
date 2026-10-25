package com.sabihon.todo.ui.addedit.speech

/**
 * Where a dictated sentence goes.
 *
 * The form has four text surfaces and only one microphone can be open at a time, so the
 * controller carries the destination instead of the caller holding it: a second tap can
 * then cancel the first session and say, honestly, which field it cancelled.
 */
sealed interface DictationTarget {

    data object Title : DictationTarget

    data object Description : DictationTarget

    data object NewSubTask : DictationTarget

    /** A sub-task that already exists, being renamed in place. */
    data class ExistingSubTask(val id: String) : DictationTarget
}

/** The name the strip puts in "Listening for …". */
val DictationTarget.label: String
    get() = when (this) {
        DictationTarget.Title -> "Title"
        DictationTarget.Description -> "Description"
        DictationTarget.NewSubTask -> "New sub-task"
        is DictationTarget.ExistingSubTask -> "Sub-task"
    }
