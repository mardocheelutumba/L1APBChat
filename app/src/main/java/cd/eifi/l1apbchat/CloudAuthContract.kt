package cd.eifi.l1apbchat

/**
 * SDK-free contract for a future cloud-authentication screen.
 *
 * This is intentionally only a contract: it does not authenticate, send SMS,
 * persist credentials, create/link accounts, initialize Firebase, or enable
 * cloud chat. Nearby remains the working transport.
 *
 * Before adding a Firebase adapter, verify the Firebase Android app and SHA
 * fingerprints, keep App Check enabled, check phone-region policy and current
 * Spark/Blaze requirements, and use Auth test phone numbers/emulators first.
 * Never link providers just because email and phone were entered by the same
 * person: require reauthentication of the existing account and explicit
 * confirmation before linking.
 */
internal object CloudAuthContract {
    enum class Method { EMAIL_PASSWORD, PHONE_SMS }

    /** Distinct input types prevent phone numbers being confused with user IDs. */
    sealed interface SignInInput {
        data class EmailPassword(val email: String, val password: String) : SignInInput
        data class PhoneNumber(val e164Number: String) : SignInInput
        data class SmsCode(val verificationId: String, val code: String) : SignInInput
    }

    /** A future auth adapter, not this contract, must establish a signed-in state. */
    sealed interface UiState {
        object Disabled : UiState
        object SignedOut : UiState
        object Working : UiState
        data class AwaitingSmsCode(val maskedNumber: String) : UiState
        data class SignedIn(val uid: String, val providerIds: Set<String>) : UiState
        data class Failed(val reason: FailureReason) : UiState
    }

    /** Generic failure categories avoid disclosing whether an account exists. */
    enum class FailureReason {
        INVALID_INPUT,
        INVALID_CREDENTIALS,
        CODE_EXPIRED_OR_INVALID,
        RATE_LIMITED,
        NETWORK_UNAVAILABLE,
        PROVIDER_UNAVAILABLE,
        UNKNOWN
    }

    fun isValidEmail(value: String): Boolean {
        val email = value.trim()
        val at = email.indexOf('@')
        return email.length in 3..254 && at > 0 && at < email.lastIndex &&
            !email.any { it.isWhitespace() } && email.substring(at + 1).contains('.')
    }

    /** E.164 shape only: 1-15 digits; the country code cannot start with zero. */
    fun isValidE164(value: String): Boolean =
        value.matches(Regex("^\\+[1-9][0-9]{1,14}$"))

    /** Accept only nonblank digits; Firebase verification-code lengths can vary. */
    fun isValidSmsCode(value: String): Boolean =
        value.isNotBlank() && value.length <= 20 && value.all { it.isDigit() }
}
