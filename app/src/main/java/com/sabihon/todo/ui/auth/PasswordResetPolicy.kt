package com.sabihon.todo.ui.auth

/**
 * What the reset screen decides *before* it touches the network, and what it says after.
 *
 * Extracted from `AuthViewModel` for one reason: the view model cannot be unit-tested
 * here (it needs `androidx.lifecycle` and coroutines), but these decisions are exactly
 * the ones that go wrong — an address that never reaches Firebase, a success message
 * quoting the wrong string, a blank submit that shows a spinner and does nothing.
 *
 * No Android imports on purpose, so `./gradlew test` covers it and so do the offline
 * checks in `tools/`.
 *
 * The validation rules are deliberately loose. The job is catching typos ("abc", "a@b",
 * "a b@c.com"), not defending the RFC: anything that could plausibly deliver mail is
 * allowed through and left to Firebase to accept or reject. Rejecting a valid address
 * here would lock a user out of the only way back into their account.
 */
object PasswordResetPolicy {

    /** RFC 5321 upper bound on a full address. Longer cannot be delivered anywhere. */
    const val MAX_EMAIL_LENGTH = 254

    const val BLANK_EMAIL = "Enter your email"
    const val INVALID_EMAIL = "Enter a valid email"
    const val FAILURE = "Could not send the reset link. Please try again."

    /** One resend a minute: enough for a lost mail, cheap against Firebase's quota. */
    const val RESEND_COOLDOWN_MS = 60_000L
    const val RESEND_TOO_SOON = "That one just went out. Give it a minute before trying again."

    /**
     * Trims the way the repository call does, so the message and the request agree.
     */
    fun normalize(raw: String): String = raw.trim()

    /**
     * Returns the error to show under the field, or null when the address is usable.
     *
     * The caller must pass a [normalize]d value; `AuthViewModel` does.
     */
    fun validate(email: String): String? {
        if (email.isEmpty()) return BLANK_EMAIL
        if (email.length > MAX_EMAIL_LENGTH) return INVALID_EMAIL
        if (email.any { it.isWhitespace() }) return INVALID_EMAIL

        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.lastIndex) return INVALID_EMAIL

        val domain = email.substring(at + 1)
        // A dot is required: "maya@localhost" is a valid *address* that no mail provider
        // will ever deliver to, and this screen exists to deliver mail.
        if (!domain.contains('.')) return INVALID_EMAIL
        if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return INVALID_EMAIL

        return null
    }

    /**
     * Why a resend was refused, or null when one may go out.
     *
     * A backwards clock (device time changed) never blocks: the user cannot do anything
     * about it, and a false "too soon" is worse than a duplicate mail.
     */
    fun resendBlockedReason(nowMillis: Long, lastSentAtMillis: Long): String? {
        if (lastSentAtMillis <= 0L) return null
        val elapsed = nowMillis - lastSentAtMillis
        if (elapsed < 0L) return null
        return if (elapsed < RESEND_COOLDOWN_MS) RESEND_TOO_SOON else null
    }

    /* ------------------------------------------------------------------ */
    /* What the user is told after a successful call                       */
    /* ------------------------------------------------------------------ */

    /**
     * Firebase resolves this call **even when no mail was sent**: with email-enumeration
     * protection on (the default for every project since 2023) a missing account, and an
     * account that only ever signed in with Google, both come back as success with
     * nothing queued.
     *
     * So the copy cannot say "sent". It says what is actually true — an attempt was made
     * — and then tells the user how to find out more, because "nothing arrived" is
     * otherwise indistinguishable from a bug in this screen.
     */
    fun sentDetail(email: String): String =
        "If $email has a Daymark account with a password, the reset link is on its way. " +
            "It usually arrives within a minute."

    /** The From: address Firebase uses when the project has not configured custom SMTP. */
    fun senderAddress(projectId: String?): String {
        val id = projectId?.trim().orEmpty()
        return if (id.isEmpty()) "noreply@firebase.com" else "noreply@$id.firebaseapp.com"
    }

    /**
     * The "didn't get it?" list.
     *
     * The middle line is the one that matters most in practice: this app signs people in
     * with Google, and a Google-only account has no password to reset, so the link will
     * never arrive no matter how many times it is requested.
     */
    fun troubleshooting(projectId: String?): List<String> = listOf(
        "Check spam or Promotions for a message from ${senderAddress(projectId)}.",
        "Signed up with Google? There is no password to reset — use Continue with Google on the sign-in screen instead.",
        "Each link works once and expires within an hour, so use the newest email."
    )
}
