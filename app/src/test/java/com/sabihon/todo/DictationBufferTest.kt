package com.sabihon.todo

import com.sabihon.todo.ui.addedit.speech.DictationBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure half of dictation: where a spoken phrase lands, given what the field already
 * holds. Every case here is one the recogniser actually produces - leading spaces,
 * doubled spaces, and the partial that grows into the final.
 */
class DictationBufferTest {

    @Test
    fun `an empty field takes the phrase as it was spoken`() {
        assertEquals("Call mom", DictationBuffer("").render("Call mom"))
    }

    @Test
    fun `an existing title keeps its text and gains one space`() {
        assertEquals("Buy milk call mom", DictationBuffer("Buy milk").render("call mom"))
    }

    @Test
    fun `a field already ending in a space does not gain a second one`() {
        assertEquals("Buy milk call mom", DictationBuffer("Buy milk ").render("call mom"))
    }

    @Test
    fun `a phrase with trailing space loses it`() {
        assertEquals("Buy milk call mom", DictationBuffer("Buy milk").render("call mom   "))
    }

    @Test
    fun `a field holding only spaces counts as empty`() {
        assertEquals("Call mom", DictationBuffer("   ").render("call mom"))
    }

    @Test
    fun `a description puts the next sentence on a new line`() {
        assertEquals(
            "Ring the plumber\nAsk about the boiler",
            DictationBuffer("Ring the plumber", multiline = true).render("Ask about the boiler")
        )
    }

    @Test
    fun `a description already ending in a newline gains no blank line`() {
        assertEquals(
            "Ring the plumber\nAsk about the boiler",
            DictationBuffer("Ring the plumber\n", multiline = true).render("Ask about the boiler")
        )
    }

    @Test
    fun `the final does not duplicate what the partial already showed`() {
        val buffer = DictationBuffer("Call")

        assertEquals("Call mom tomorrow", buffer.render("mom tomorrow"))
        assertEquals("Call mom tomorrow", buffer.render("mom tomorrow"))
    }

    @Test
    fun `a longer final replaces the shorter partial it grew from`() {
        val buffer = DictationBuffer("")

        assertEquals("Call mom", buffer.render("Call mom"))
        assertEquals("Call mom tomorrow", buffer.render("Call mom tomorrow"))
    }

    @Test
    fun `a doubled space inside the phrase collapses`() {
        assertEquals("Call mom", DictationBuffer("").render("Call  mom"))
    }

    @Test
    fun `hearing nothing leaves the field exactly as it was`() {
        assertEquals("Buy milk", DictationBuffer("Buy milk").render(""))
        assertEquals("", DictationBuffer("").render("   "))
    }

    @Test
    fun `cancel has the original to put back`() {
        val buffer = DictationBuffer("Buy milk")

        buffer.render("call mom")

        assertEquals("Buy milk", buffer.originalText)
    }
}
