package cd.eifi.l1apbchat

/**
 * Client-side input constraints for a future online room protocol.
 * These checks improve UI consistency only; they are NOT an authorization
 * boundary. Firestore rules must independently enforce identity, membership,
 * immutable fields and limits before any cloud transport is enabled.
 * This type performs no Firebase access and cannot enable online chat.
 */
internal object OnlineRoomContract {
    const val MAX_ROOM_ID_LENGTH = 64
    const val MAX_DISPLAY_NAME_LENGTH = 40
    const val MAX_MESSAGE_LENGTH = 2_000
    const val MAX_ORGANIZATION_LENGTH = 80
    const val MAX_MESSAGES_PER_PAGE = 50

    /** Opaque IDs only: user-entered room names must not be document IDs. */
    fun isValidRoomId(value: String): Boolean =
        value.length in 20..MAX_ROOM_ID_LENGTH && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Future Firebase Auth UID only, never a display name or phone number. */
    fun isValidUserId(value: String): Boolean =
        value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun isValidDisplayName(value: String): Boolean =
        value == value.trim() && value.length in 1..MAX_DISPLAY_NAME_LENGTH && value.none(Char::isISOControl)

    fun isValidOrganization(value: String?): Boolean = value == null ||
        (value == value.trim() && value.length <= MAX_ORGANIZATION_LENGTH && value.none(Char::isISOControl))

    fun isValidMessageBody(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_MESSAGE_LENGTH &&
            value.none { it.isISOControl() && it != '\n' && it != '\t' }
}
