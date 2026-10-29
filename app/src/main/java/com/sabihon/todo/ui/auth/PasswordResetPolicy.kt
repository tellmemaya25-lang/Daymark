package com.sabihon.todo.ui.auth

/**
 * What the reset screen decides *before* it touches the network.
 *
 * Extracted from `AuthViewModel` for one reason: the view model cannot be unit-tested
 * here (it needs `androidx.lifecycle` and coroutines), but these decisions are exactly
 * the ones that go wrong — an address that never reaches Firebase, a success message
 * quoting the wrong string, a blank submit that shows a spinner and does nothing.
 *
 * No Android imports on purpose, so `./gradlew test` covers it and so do the offline
 * checks in `tools/`.
 *
 * The rules are deliberately loose. The job is catching typos ("abc", "a@b", "a b@c.com"),
 * not defending the RFC: anything that could plausibly deliver mail is allowed through
 * and left to Firebase to accept or reject. Rejecting a valid address here would lock a
 * user out of the only way back into their account.
 */
object PasswordResetPolicy {

    /** RFC 5321 upper bound on a full address. Longer cannot be delivered anywhere. */
    const val MAX_EMAIL_LENGTH = 254

    const val BLANK_EMAIL = "Enter your email"
    const val INVALID_EMAIL = "Enter a valid email"
    const val FAILURE = "Could not send the reset link. Please try again."

    /** Trims the way the repository call does, so the message and the request agree. */
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

    /** The one confirmation the user sees, quoting exactly what was sent to Firebase. */
    fun successMessage(email: String): String = "Reset link sent to $email"
}
