package com.nxmirror.db

import org.json.JSONObject

/**
 * Result of [NxDatabase.query], [NxQueryBuilder.select], `insert`, `update`
 * and `delete` — the same shape the JS SDK returns.
 */
data class NxQueryResult(
    val rows: List<JSONObject>,
    val columns: List<String>,
    val changes: Int,
    val executionMs: Long,
    val truncated: Boolean,
    val lastInsertRowid: Long?
)

/** Result of [NxDatabase.exec] — multi-statement batches return no rows. */
data class NxExecResult(
    val changes: Int,
    val executionMs: Long
)

/** One-shot change-feed read: [NxDatabase.changes]. */
data class NxChanges(
    val events: List<JSONObject>,
    val latestSeq: Long
)

/** Result of [NxDatabase.connectInfo]. */
data class NxConnectInfo(
    val connected: Boolean,
    val latencyMs: Long,
    val authMethod: String?,
    val permissions: List<String>,
    val engine: String?,
    val databaseName: String?,
    val sdkVersion: String,
    val apiBase: String
)
