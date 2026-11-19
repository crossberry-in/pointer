package com.nxmirror.db

/**
 * Platform-wide defaults for the NxMirror DB Kotlin SDK.
 */
object NxdbDefaults {
    /** Default API base URL. Endpoint URLs are built as `{apiBase}/db/{path}`. */
    const val DEFAULT_API_BASE = "https://nxmirror.netlify.app/api"

    /** Version of this Kotlin SDK (reported by [NxDatabase.connectInfo]). */
    const val SDK_VERSION = "1.0.0"
}

/**
 * Connection settings for [NxDatabase].
 *
 * Build directly or through [NxConfigBuilder] via `NxDatabase.connect { ... }`.
 *
 * @param databaseId NxDB database ID (`nxdb-...`). Required.
 * @param password   Database password — full access. Trusted environments only.
 * @param token      Scoped API token (`nxdb_...`).
 * @param appId      Registered application ID.
 * @param appToken   Application token (`nxdbapp_...`). Needs [appId].
 * @param apiBase    API base URL. A trailing `/api` is normalized automatically.
 * @param timeoutMs  Per-request connect/read timeout (default 30 s).
 * @param retries    Safe retry limit for idempotent calls (default 2, clamped 0..5).
 */
data class NxConfig(
    val databaseId: String,
    val password: String? = null,
    val token: String? = null,
    val appId: String? = null,
    val appToken: String? = null,
    val apiBase: String = NxdbDefaults.DEFAULT_API_BASE,
    val timeoutMs: Long = 30_000,
    val retries: Int = 2
)

/**
 * Builder for [NxConfig] — used by the `NxDatabase.connect { ... }` DSL:
 *
 * ```
 * val db = NxDatabase.connect {
 *     databaseId = "nxdb-..."
 *     password = "..."
 * }
 * ```
 */
class NxConfigBuilder {
    var databaseId: String? = null
    var password: String? = null
    var token: String? = null
    var appId: String? = null
    var appToken: String? = null
    var apiBase: String = NxdbDefaults.DEFAULT_API_BASE
    var timeoutMs: Long = 30_000
    var retries: Int = 2

    /** Validates the database ID and produces an immutable [NxConfig]. */
    fun build(): NxConfig {
        val id = databaseId
        if (id == null || id.isBlank()) {
            throw NxError("NxDB.connect requires a databaseId.", "invalid_input")
        }
        return NxConfig(
            databaseId = id,
            password = password,
            token = token,
            appId = appId,
            appToken = appToken,
            apiBase = apiBase,
            timeoutMs = timeoutMs,
            retries = retries
        )
    }
}
