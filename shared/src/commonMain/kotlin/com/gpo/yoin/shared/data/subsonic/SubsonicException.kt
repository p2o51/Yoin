package com.gpo.yoin.shared.data.subsonic

/**
 * What went wrong, as one flat enum Swift can `switch` on
 * (`SubsonicErrorKind.wrongCredentials`, …).
 *
 * The first block is the Subsonic API's own error codes
 * (`subsonic-response.status = "failed"`); the last three are client-side.
 */
enum class SubsonicErrorKind(val code: Int) {
    GENERIC(0),
    MISSING_PARAMETER(10),
    CLIENT_TOO_OLD(20),
    SERVER_TOO_OLD(30),
    WRONG_CREDENTIALS(40),
    TOKEN_AUTH_NOT_SUPPORTED(41),
    AUTH_MECHANISM_NOT_SUPPORTED(42),
    CONFLICTING_AUTH(43),
    INVALID_API_KEY(44),
    NOT_AUTHORIZED(50),
    TRIAL_EXPIRED(60),
    NOT_FOUND(70),

    /** Couldn't reach the server (DNS, refused, timeout, TLS…). */
    NETWORK(-1),

    /** Non-2xx HTTP status without a Subsonic error body. */
    HTTP(-2),

    /** The body wasn't a Subsonic response we could read. */
    PARSE(-3),
    ;

    companion object {
        fun fromCode(code: Int): SubsonicErrorKind = entries.firstOrNull { it.code == code && it.code >= 0 } ?: GENERIC
    }
}

/**
 * Every failure of [SubsonicClient] surfaces as this one type (Swift: an
 * `NSError` whose `kotlinException` is a `SubsonicException`).
 *
 * [code] is the server's Subsonic error code for API errors, or the [kind]'s
 * negative code for client-side ones. [httpStatus] is set when an HTTP
 * response arrived.
 */
class SubsonicException(
    val kind: SubsonicErrorKind,
    val code: Int,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 40 / 41 / 42 / 43 / 44: the saved credentials need fixing, not a retry. */
    val isAuthFailure: Boolean
        get() = kind == SubsonicErrorKind.WRONG_CREDENTIALS ||
            kind == SubsonicErrorKind.TOKEN_AUTH_NOT_SUPPORTED ||
            kind == SubsonicErrorKind.AUTH_MECHANISM_NOT_SUPPORTED ||
            kind == SubsonicErrorKind.CONFLICTING_AUTH ||
            kind == SubsonicErrorKind.INVALID_API_KEY

    internal companion object {
        fun api(code: Int, message: String?, httpStatus: Int?): SubsonicException =
            SubsonicException(
                kind = SubsonicErrorKind.fromCode(code),
                code = code,
                message = message ?: "Subsonic error (code $code)",
                httpStatus = httpStatus,
            )
    }
}
