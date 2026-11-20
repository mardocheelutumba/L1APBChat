package cd.eifi.l1apbchat

/**
 * SDK-free contract for a future email/password authentication screen.
 *
 * This is preparation only: it does not authenticate, create accounts, persist
 * credentials, initialize Firebase, or enable cloud chat. It has no phone/SMS
 * path. Nearby remains the working transport and never requires sign-in.
 *
 * Before adding a Firebase adapter, verify the Android app configuration and
 * SHA fingerprints, keep App Check enabled, confirm the project is still on
 * Spark, and use the Firebase Auth Emulator before any real-account tests.
 * Never link providers based only on matching email addresses; require
 * reauthentication of the existing account and explicit user confirmation.
 */
internal object CloudAuthContract {
    /** The password must never be logged or persisted by this contract. */
    data class EmailPassword(val email: String, val password: String)

    /** A future adapter, not this contract, must establish a signed-in state. */
    sealed interface UiState {
        object Disabled : UiState
        object SignedOut : UiState
        object Working : UiState
        data class SignedIn(val uid: String, val providerIds: Set<String>) : UiState
        data class Failed(val reason: FailureReason) : UiState
    }

    /** Generic failures avoid disclosing whether an account exists. */
    enum class FailureReason {
        INVALID_INPUT,
        INVALID_CREDENTIALS,
        NETWORK_UNAVAILABLE,
        PROVIDER_UNAVAILABLE,
        UNKNOWN
    }

    fun normalizeEmail(value: String): String = value.trim()

    fun isValidEmail(value: String): Boolean {
        val email = normalizeEmail(value)
        val at = email.indexOf('@')
        return email.length in 3..254 && at > 0 && at < email.lastIndex &&
            !email.any { it.isWhitespace() } && email.substring(at + 1).contains('.')
    }

    /** Matches Firebase's default minimum; server policy remains authoritative. */
    fun meetsDefaultPasswordMinimum(value: String): Boolean = value.length >= 6
}
