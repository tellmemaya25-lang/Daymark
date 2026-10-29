package com.sabihon.todo

import com.sabihon.todo.ui.auth.PasswordResetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /* --------------------------- the sent copy -------------------------- */

    /**
     * Firebase resolves this call whether or not it queued any mail, so the panel must
     * not claim delivery. It names the address it was attempted for and stops there.
     */
    @Test
    fun `the sent copy is conditional, never a claim of delivery`() {
        val detail = PasswordResetPolicy.sentDetail("maya@example.com")
        assertEquals(
            "If maya@example.com has a Daymark account with a password, the reset link " +
                "is on its way. It usually arrives within a minute.",
            detail
        )
    }

    @Test
    fun `the sender address names the project, and degrades safely`() {
        assertEquals(
            "noreply@sabihon-todo.firebaseapp.com",
            PasswordResetPolicy.senderAddress("sabihon-todo")
        )
        assertEquals("noreply@firebase.com", PasswordResetPolicy.senderAddress(null))
        assertEquals("noreply@firebase.com", PasswordResetPolicy.senderAddress("   "))
    }

    @Test
    fun `troubleshooting covers spam, the Google-only account, and one-use links`() {
        val lines = PasswordResetPolicy.troubleshooting("sabihon-todo")
        assertEquals(3, lines.size)
        assertTrue("must name the sender", lines[0].contains("noreply@sabihon-todo.firebaseapp.com"))
        assertTrue("must name the Google route", lines[1].contains("Continue with Google"))
        assertTrue("must explain expiry", lines[2].contains("expires"))
    }

    /* ----------------------------- resending ---------------------------- */

    @Test
    fun `the first send is never blocked`() {
        assertNull(PasswordResetPolicy.resendBlockedReason(nowMillis = 5_000L, lastSentAtMillis = 0L))
    }

    @Test
    fun `a second send inside the minute is refused`() {
        val sentAt = 1_000_000L
        assertEquals(
            PasswordResetPolicy.RESEND_TOO_SOON,
            PasswordResetPolicy.resendBlockedReason(sentAt + 1, sentAt)
        )
        assertEquals(
            PasswordResetPolicy.RESEND_TOO_SOON,
            PasswordResetPolicy.resendBlockedReason(sentAt + 59_999, sentAt)
        )
    }

    @Test
    fun `a resend after the minute is allowed`() {
        val sentAt = 1_000_000L
        assertNull(PasswordResetPolicy.resendBlockedReason(sentAt + 60_000, sentAt))
        assertNull(PasswordResetPolicy.resendBlockedReason(sentAt + 600_000, sentAt))
    }

    @Test
    fun `a clock that jumped backwards does not lock the user out`() {
        assertNull(PasswordResetPolicy.resendBlockedReason(nowMillis = 500L, lastSentAtMillis = 9_000L))
    }

    @Test
    fun `there is a message to fall back on when the repository has none`() {
        assertEquals(
            "Could not send the reset link. Please try again.",
            PasswordResetPolicy.FAILURE
        )
    }
}
