package com.nxmirror.db

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.URLEncoder

/**
 * Client for one NxDB database — faithful Kotlin port of the official
 * JavaScript/TypeScript SDK (`nxmirror-db`).
 *
 * Works on Android and plain JVM. Zero dependencies besides `org.json`;
 * HTTP goes through `java.net.HttpURLConnection`.
 *
 * ```
 * val db = NxDatabase.connect {
 *     databaseId = "nxdb-..."
 *     password = "..."            // trusted environments only
 * }
 * val rows = db.query("SELECT * FROM users WHERE active = ?", listOf(true))
 * ```
 *
 * All network calls are synchronous (blocking). On Android run them off the
 * main thread (e.g. inside `Dispatchers.IO`).
 */
class NxDatabase(config: NxConfig) {

    val databaseId: String = config.databaseId
    internal val apiBase: String
    internal val timeoutMs: Long
    internal val retries: Int
    private val authToken: String?
    private val authPassword: String?
    private val authAppId: String?
    private val authAppToken: String?

    /** Learned from [connectInfo]; powers the client-side engine guard. */
    var engine: String? = null
        private set

    /** Permission strings returned by the last successful [connectInfo]. */
    var permissions: List<String>? = null
        private set

    /** Hybrid file storage (works on both engines). */
    val storage: NxStorage = NxStorage(this)

    /** NoSQL document collections — needs a database created with engine "nosql". */
    val nosql: NxNoSql = NxNoSql(this)

    init {
        if (config.databaseId.isBlank()) {
            throw NxError("NxDB.connect requires a databaseId.", "invalid_input")
        }
        if (config.password == null && config.token == null && !(config.appId != null && config.appToken != null)) {
            throw NxError("Provide a token, a password, or an appId + appToken.", "invalid_input")
        }
        apiBase = detectApiBase(config.apiBase)
        timeoutMs = config.timeoutMs
        retries = config.retries.coerceIn(0, 5)
        authToken = config.token
        authPassword = config.password
        authAppId = config.appId
        authAppToken = config.appToken
    }

    /**
     * Client-side engine guard: block calls that cannot succeed on this engine
     * with a clear error, BEFORE any network request is made. When the engine
     * is still unknown (no [connectInfo] yet), the server decides.
     */
    internal fun assertEngine(expected: String) {
        val known = engine ?: return
        if (known == expected) return
        val hint = if (expected == "nosql")
            "NoSQL document collections (db.nosql.*) need a database created with engine \"nosql\"."
        else
            "SQL calls (db.query / db.exec / db.table) need the \"sqlite\" engine — JSON documents live on engine \"nosql\" via db.nosql.*"
        throw NxError(
            "This database runs the \"$known\" engine — this call requires \"$expected\". $hint",
            "wrong_engine"
        )
    }

    /* ---------- transport ---------- */

    private fun request(path: String, method: String, body: String?, blob: Boolean, attempt: Int): Any? {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(path, method, body)
            val status = conn.responseCode

            if (status == 429) {
                val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull() ?: 1.0
                if (attempt < retries) {
                    sleepMillis((retryAfter * 1000).toLong())
                    return request(path, method, body, blob, attempt + 1)
                }
                throw NxError("NxDB rate limit exceeded. Slow down and retry shortly.", "rate_limited", 429)
            }
            if (status >= 500 && method == "GET" && attempt < retries) {
                sleepMillis(400L * (attempt + 1))
                return request(path, method, body, blob, attempt + 1)
            }

            if (blob) {
                if (status in 200..299) {
                    return conn.inputStream.use { it.readBytes() }
                }
                // Netlify Blobs replicas can serve a brief stale window right
                // after a write — retry idempotent blob GETs on 404/5xx first.
                if (method == "GET" && (status == 404 || status >= 500) && attempt < retries) {
                    sleepMillis(600L * (attempt + 1))
                    return request(path, method, body, blob, attempt + 1)
                }
                throw NxError(errorText(conn.errorStream, status), "request_failed", status)
            }

            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val data = try {
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("error", text.take(300))
            }
            if (status !in 200..299) {
                val rawError = data.opt("error")
                val message = if (rawError != null && rawError != JSONObject.NULL && rawError.toString().isNotEmpty())
                    rawError.toString()
                else
                    "Request failed ($status)"
                val rawCode = data.opt("code")
                val code = if (rawCode != null && rawCode != JSONObject.NULL && rawCode.toString().isNotEmpty())
                    rawCode.toString()
                else
                    "request_failed"
                throw NxError(message, code, status)
            }
            return data
        } catch (e: NxError) {
            throw e
        } catch (e: IOException) {
            val idempotent = method == "GET"
            if (idempotent && attempt < retries) {
                sleepMillis(400L * (attempt + 1))
                return request(path, method, body, blob, attempt + 1)
            }
            throw NxError("Network error contacting NxDB: ${e.message ?: e.javaClass.simpleName}", "network_error")
        } catch (e: IllegalArgumentException) {
            // malformed URL argument
            throw NxError("Network error contacting NxDB: ${e.message ?: e.javaClass.simpleName}", "network_error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun openConnection(path: String, method: String, body: String?): HttpURLConnection {
        val url = try {
            URI("$apiBase/db/$path").toURL()
        } catch (e: IllegalArgumentException) {
            throw MalformedURLException(e.message)
        }
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs.toInt().coerceAtLeast(0)
        conn.readTimeout = timeoutMs.toInt().coerceAtLeast(0)
        conn.setRequestProperty("x-nxdb-id", databaseId)
        if (authToken != null) conn.setRequestProperty("Authorization", "Bearer $authToken")
        if (authPassword != null) conn.setRequestProperty("x-nxdb-password", authPassword)
        if (authAppId != null) {
            conn.setRequestProperty("x-nxdb-app", authAppId)
            if (authAppToken != null) conn.setRequestProperty("x-nxdb-app-token", authAppToken)
        }
        if (body != null) conn.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }

    /** Readable error message from a failed (blob/JSON) response body. */
    private fun errorText(stream: InputStream?, status: Int): String {
        val text = try {
            stream?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } ?: ""
        if (text.isNotEmpty()) {
            try {
                val json = JSONObject(text)
                val err = json.opt("error")
                if (err != null && err != JSONObject.NULL) return err.toString().take(300)
            } catch (e: Exception) {
                // plain text body
            }
            return text.take(300)
        }
        return "Request failed ($status)"
    }

    /** JSON request helper used by [NxStorage] and [NxNoSql]. */
    internal fun requestJson(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val result = request(path, method, body?.toString(), blob = false, attempt = 0)
        return result as? JSONObject ?: JSONObject()
    }

    /** Binary (blob) GET helper used by [NxStorage.download]. */
    internal fun requestBytes(path: String): ByteArray {
        val result = request(path, "GET", null, blob = true, attempt = 0)
        return result as? ByteArray ?: ByteArray(0)
    }

    private fun sleepMillis(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NxError("NxDB request interrupted while waiting to retry.", "network_error")
        }
    }

    /* ---------- SQL ---------- */

    /** Execute a parameterized SQL statement. */
    fun query(sql: String, params: List<Any?> = emptyList()): NxQueryResult {
        assertEngine("sqlite")
        val body = JSONObject().put("databaseId", databaseId).put("sql", sql).put("params", toJsonArray(params))
        val result = requestJson("query", "POST", body)
        val rowsArray = result.optJSONArray("rows")
        val rows = if (rowsArray == null) emptyList()
        else (0 until rowsArray.length()).map { rowsArray.optJSONObject(it) ?: JSONObject() }
        val columnsArray = result.optJSONArray("columns")
        val columns = if (columnsArray == null) emptyList()
        else (0 until columnsArray.length()).map { columnsArray.optString(it) }
        val lastId = result.opt("lastInsertRowid")
        return NxQueryResult(
            rows = rows,
            columns = columns,
            changes = (result.opt("changes") as? Number)?.toInt() ?: 0,
            executionMs = (result.opt("executionMs") as? Number)?.toLong() ?: 0,
            truncated = result.optBoolean("truncated"),
            lastInsertRowid = (lastId as? Number)?.toLong()
        )
    }

    /** Execute a multi-statement SQL batch (no parameters). */
    fun exec(sql: String): NxExecResult {
        assertEngine("sqlite")
        val body = JSONObject().put("databaseId", databaseId).put("sql", sql)
        val result = requestJson("query", "POST", body)
        return NxExecResult(
            changes = (result.opt("changes") as? Number)?.toInt() ?: 0,
            executionMs = (result.opt("executionMs") as? Number)?.toLong() ?: 0
        )
    }

    /** Query builder entry point. */
    fun table(name: String): NxQueryBuilder {
        if (!TABLE_NAME_REGEX.matches(name)) throw NxError("Invalid table name.", "invalid_input")
        return NxQueryBuilder(this, name)
    }

    /* ---------- connection ---------- */

    /** Verify connectivity + permissions; primes the engine guard. */
    fun connectInfo(): NxConnectInfo {
        val info = requestJson("connect", "POST", JSONObject().put("databaseId", databaseId))
        permissions = info.optJSONArray("permissions")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        } ?: emptyList()
        engine = info.optJSONObject("database")?.opt("engine") as? String
        return NxConnectInfo(
            connected = info.optBoolean("connected"),
            latencyMs = (info.opt("latencyMs") as? Number)?.toLong() ?: 0,
            authMethod = info.opt("authMethod") as? String,
            permissions = permissions ?: emptyList(),
            engine = engine,
            databaseName = info.optJSONObject("database")?.opt("name") as? String,
            sdkVersion = NxdbDefaults.SDK_VERSION,
            apiBase = apiBase
        )
    }

    /** Database metadata + current table/collection list (raw JSON). */
    fun details(): JSONObject =
        requestJson("details", "POST", JSONObject().put("databaseId", databaseId))

    /* ---------- realtime (change polling; WebSocket realtime is planned) ---------- */

    /** Fetch change events newer than a sequence number. */
    fun changes(table: String?, since: Long = 0): NxChanges {
        var q = "db=${urlEncode(databaseId)}&since=$since"
        if (!table.isNullOrEmpty()) q += "&table=${urlEncode(table)}"
        val result = requestJson("events?$q")
        val eventsArray = result.optJSONArray("events")
        val events = if (eventsArray == null) emptyList()
        else (0 until eventsArray.length()).map { eventsArray.optJSONObject(it) ?: JSONObject() }
        return NxChanges(
            events = events,
            latestSeq = (result.opt("latestSeq") as? Number)?.toLong() ?: 0
        )
    }

    /**
     * Poll for changes until [NxWatchHandle.unsubscribe] is called. Runs on a
     * daemon thread; delivery happens on that thread. On error the callback
     * receives `{"op":"error","error":...,"code":...}` instead of an event.
     */
    fun watch(
        table: String?,
        callback: (JSONObject) -> Unit,
        intervalMs: Long = 5000,
        since: Long? = null
    ): NxWatchHandle {
        val handle = NxWatchHandle()
        val worker = Thread {
            var cursor = since ?: 0L
            while (!handle.stopped) {
                try {
                    val read = changes(table, cursor)
                    cursor = maxOf(cursor, read.latestSeq)
                    for (event in read.events) callback(event)
                } catch (e: Exception) {
                    if (!handle.stopped) {
                        val payload = JSONObject()
                            .put("op", "error")
                            .put("error", e.message ?: e.javaClass.simpleName)
                        if (e is NxError) payload.put("code", e.code)
                        callback(payload)
                    }
                }
                try {
                    Thread.sleep(maxOf(1000L, intervalMs))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        worker.isDaemon = true
        worker.name = "nxdb-watch-$databaseId"
        handle.worker = worker
        worker.start()
        return handle
    }

    /* ---------- companion ---------- */

    companion object {
        private val TABLE_NAME_REGEX = Regex("^\\w+$")

        /**
         * Connect to an NxDB database and verify the connection. Connect
         * failures are advisory — later calls surface auth errors.
         */
        fun connect(config: NxConfig): NxDatabase {
            val client = NxDatabase(config)
            try {
                client.connectInfo()
            } catch (e: Exception) {
                // advisory — later calls surface auth errors
            }
            return client
        }

        /** DSL variant: `NxDatabase.connect { databaseId = "nxdb-..."; password = "..." }`. */
        fun connect(block: NxConfigBuilder.() -> Unit): NxDatabase {
            val builder = NxConfigBuilder()
            builder.block()
            return connect(builder.build())
        }

        /** JS parity: a base without a trailing `/api` gets one appended. */
        fun detectApiBase(base: String?): String {
            val trimmed = (if (base.isNullOrEmpty()) NxdbDefaults.DEFAULT_API_BASE else base).trimEnd('/')
            return if (trimmed.endsWith("/api")) trimmed else "$trimmed/api"
        }
    }
}

/** JSON array from a Kotlin list; Kotlin `null` values become JSON `null`. */
internal fun toJsonArray(values: List<Any?>): JSONArray {
    val array = JSONArray()
    for (value in values) array.put(value ?: JSONObject.NULL)
    return array
}

/** URL-encode a query parameter or path segment the way the JS SDK does. */
internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
