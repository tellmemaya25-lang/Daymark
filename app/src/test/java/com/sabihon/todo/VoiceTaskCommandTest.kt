package com.sabihon.todo

import com.sabihon.todo.core.dictation.VoiceTaskCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTaskCommandTest {

    @Test
    fun `trailing done is removed and requests completion`() {
        assertEquals(
            VoiceTaskCommand.MarkDone("Buy milk"),
            VoiceTaskCommand.parseMarkDone("", "Buy milk, done!")
        )
    }

    @Test
    fun `done spoken after an existing title preserves the original title`() {
        assertEquals(
            VoiceTaskCommand.MarkDone("Call mom"),
            VoiceTaskCommand.parseMarkDone("Call mom", "Call mom done")
        )
    }

    @Test
    fun `done matching is case insensitive`() {
        assertEquals(
            VoiceTaskCommand.MarkDone("Pay rent"),
            VoiceTaskCommand.parseMarkDone("", "Pay rent DONE")
        )
    }

    @Test
    fun `done inside a title is ordinary text`() {
        assertNull(VoiceTaskCommand.parseMarkDone("", "Get things done tomorrow"))
    }

    @Test
    fun `done must be a standalone word`() {
        assertNull(VoiceTaskCommand.parseMarkDone("", "Finish undone tasks"))
    }

    @Test
    fun `a title already ending in done does not trigger without new speech`() {
        assertNull(VoiceTaskCommand.parseMarkDone("Get it done", "Get it done"))
    }
}
