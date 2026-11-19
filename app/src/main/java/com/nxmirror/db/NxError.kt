package com.nxmirror.db

/**
 * The only exception type thrown by this SDK.
 *
 * Every failure carries a stable [code] you can switch on (never parse messages)
 * and, for server errors, the HTTP [status]. Client-side guards such as the
 * engine check throw with `status == null` — no network call was made.
 *
 * Error codes: `unauthorized` (401), `invalid_credentials` (401),
 * `wrong_database` (403), `insufficient_permission` (403), `forbidden` (403),
 * `confirm_required` (400), `rate_limited` (429), `sql_error` (400),
 * `wrong_engine` (client-side, null status), `ambiguous_match` (409),
 * `invalid_collection` (400), `too_large` (413), `request_failed`,
 * `network_error`, `invalid_input`.
 */
class NxError(message: String, val code: String = "error", val status: Int? = null) : Exception(message) {
    override fun toString(): String =
        "NxDBError(code=$code, status=$status): $message"
}
