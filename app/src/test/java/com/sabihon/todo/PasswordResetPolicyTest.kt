package com.sabihon.todo

import com.sabihon.todo.ui.auth.PasswordResetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What "Send reset link" decides before it calls Firebase.
 *
 * The screen used to check only `isBlank()`, so "abc" reached the network and came back
 * as a snackbar. These cases pin the two halves of the fix: obvious typos are caught
 * locally, and genuine addresses are never caught by mistake — blocking one would lock a
 * user out of the only route back into their account.
 */
class PasswordResetPolicyTest {

    private fun errorFor(raw: String): String? =
        PasswordResetPolicy.validate(PasswordResetPolicy.normalize(raw))

    /* ------------------------------ rejects ------------------------------ */

    @Test
    fun `a blank or whitespace-only address is asked for rather than sent`() {
        assertEquals(PasswordResetPolicy.BLANK_EMAIL, errorFor(""))
        assertEquals(PasswordResetPolicy.BLANK_EMAIL, errorFor("   "))
        assertEquals(PasswordResetPolicy.BLANK_EMAIL, errorFor("\t\n "))
    }

    @Test
    fun `obvious typos never reach the network`() {
        listOf(
            "abc",
            "maya",
            "maya@",
            "@example.com",
            "maya@@example.com",
            "maya@example",          // no dot: nothing will deliver this
            "maya@example.",         // trailing dot
            "maya@.example.com",     // leading dot
            "maya@example..com",     // doubled dot
            "maya bloggs@example.com", // internal space
            "maya@exa mple.com"
        ).forEach { candidate ->
            assertEquals("expected $candidate to be rejected", PasswordResetPolicy.INVALID_EMAIL, errorFor(candidate))
        }
    }

    @Test
    fun `an address longer than 254 characters is rejected`() {
        val tooLong = "a".repeat(250) + "@example.com"
        assertEquals(PasswordResetPolicy.INVALID_EMAIL, errorFor(tooLong))
    }

    /* ------------------------------ accepts ----------------------------- */

    @Test
    fun `ordinary addresses pass`() {
        listOf(
            "maya@example.com",
            "maya.angelou+daymark@example.co.uk",
            "m@example.io",
            "first.last@sub.domain.example.org",
            "user_name@example-mail.com",
            "1234@9999.com"
        ).forEach { candidate ->
            assertNull("expected $candidate to be accepted", errorFor(candidate))
        }
    }

    @Test
    fun `surrounding whitespace is trimmed rather than rejected`() {
        assertEquals("maya@example.com", PasswordResetPolicy.normalize("  maya@example.com \n"))
        assertNull(errorFor("  maya@example.com "))
    }

    @Test
    fun `case is preserved - Firebase treats it as the user typed it`() {
        assertEquals("Maya.Angelou@Example.COM", PasswordResetPolicy.normalize(" Maya.Angelou@Example.COM "))
    }

    /* ---------------------------- the message --------------------------- */

    @Test
    fun `the confirmation quotes exactly what was sent`() {
        val email = PasswordResetPolicy.normalize("  maya@example.com  ")
        assertEquals("Reset link sent to maya@example.com", PasswordResetPolicy.successMessage(email))
    }

    @Test
    fun `there is a message to fall back on when the repository has none`() {
        assertEquals(
            "Could not send the reset link. Please try again.",
            PasswordResetPolicy.FAILURE
        )
    }
}
