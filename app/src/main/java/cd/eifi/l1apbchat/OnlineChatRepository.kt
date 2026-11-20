package cd.eifi.l1apbchat

import android.content.Context

/**
 * Experimental online transport boundary.
 *
 * This intentionally contains no Firebase SDK wiring yet. It keeps the Nearby
 * transport independent while authentication, room membership and validated
 * Firestore rules are reviewed and tested. Callers must keep using Nearby when
 * this transport is unavailable.
 */
class OnlineChatRepository(private val context: Context) {
    enum class State { DISABLED, SIGNED_OUT, READY }

    val state: State = State.DISABLED

    fun isAvailable(): Boolean = false

    fun explainUnavailable(): String =
        "Online chat is disabled until Firebase authentication and room authorization are implemented."
}
