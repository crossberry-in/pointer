package com.nxmirror.db

import org.json.JSONObject

/**
 * Fluent query builder for SQL (engine `sqlite`) databases — exact port of the
 * JS `NxQueryBuilder`.
 *
 * ```
 * val rows = db.table("users")
 *     .where("score", ">", 10)          // eq ne gt lt gte lte like in isNull
 *     .where(mapOf("active" to true))   // object form, AND-combined
 *     .whereNull("deleted_at")
 *     .orderBy("id", "DESC")
 *     .limit(20).offset(40)
 *     .select()                          // executes — limit() BEFORE select()
 * rows.rows                              // select() returns the full result object
 *
 * val total = db.table("users").where(mapOf("active" to true)).count()
 * ```
 */
class NxQueryBuilder internal constructor(
    private val client: NxDatabase,
    private val table: String
) {
    private val whereClauses = mutableListOf<String>()
    private val whereParams = mutableListOf<Any?>()
    private var orderByClause: String? = null
    private var limitValue: Int? = null
    private var offsetValue: Int? = null
    private var columns: List<String>? = null

    /** Execute a SELECT. Pass a column list to project; `rows` holds the data. */
    fun select(columns: List<String>? = null): NxQueryResult {
        this.columns = columns
        return run()
    }

    /** `SELECT COUNT(*)` — returns the matched row count. */
    fun count(): Int {
        val sql = "SELECT COUNT(*) AS __n FROM \"${table}\"${whereSql()}"
        val result = client.query(sql, whereParams)
        return (result.rows.firstOrNull()?.opt("__n") as? Number)?.toInt() ?: 0
    }

    /** Typed operator form: `where("age", "gt", 18)`. Operators: eq ne gt lt gte lte like in isNull. */
    fun where(column: String, op: String, value: Any?): NxQueryBuilder {
        if (op == "in") {
            val list = value as? List<*>
            if (list == null || list.isEmpty()) {
                whereClauses.add("0 = 1")
                return this
            }
            val placeholders = list.joinToString(",") { "?" }
            whereClauses.add("\"${column}\" IN (${placeholders})")
            whereParams.addAll(list)
            return this
        }
        if (op == "isNull") {
            whereClauses.add("\"${column}\" IS NULL")
            return this
        }
        val sqlOp = OPS[op] ?: op
        whereClauses.add("\"${column}\" $sqlOp ?")
        whereParams.add(value)
        return this
    }

    /** Object form: `where(mapOf("active" to true))` — null values become `IS NULL`. */
    fun where(conditions: Map<String, Any?>): NxQueryBuilder {
        for ((key, value) in conditions) {
            if (value == null || value === JSONObject.NULL) {
                whereNull(key)
            } else {
                whereClauses.add("\"${key}\" = ?")
                whereParams.add(value)
            }
        }
        return this
    }

    fun whereNull(column: String): NxQueryBuilder {
        whereClauses.add("\"${column}\" IS NULL")
        return this
    }

    fun orderBy(column: String, direction: String = "ASC"): NxQueryBuilder {
        val dir = if (direction.uppercase() == "DESC") "DESC" else "ASC"
        orderByClause = "\"${column}\" $dir"
        return this
    }

    /** Clamped to 0..1000. */
    fun limit(n: Int): NxQueryBuilder {
        limitValue = n.coerceIn(0, 1000)
        return this
    }

    fun offset(n: Int): NxQueryBuilder {
        offsetValue = n.coerceAtLeast(0)
        return this
    }

    /** Insert one row. Accumulates [NxQueryResult.changes]; keeps the last non-null rowid. */
    fun insert(data: Map<String, Any?>): NxQueryResult = insertAll(listOf(data))

    /** Insert several rows sequentially with one shared statement shape. */
    fun insertAll(rows: List<Map<String, Any?>>): NxQueryResult {
        if (rows.isEmpty()) {
            return NxQueryResult(emptyList(), emptyList(), 0, 0, false, null)
        }
        val keys = rows[0].keys.toList()
        val columnSql = keys.joinToString(", ") { "\"${it}\"" }
        val placeholderSql = keys.joinToString(",") { "?" }
        val sql = "INSERT INTO \"${table}\" (${columnSql}) VALUES (${placeholderSql})"
        var changes = 0
        var executionMs = 0L
        var lastInsertRowid: Long? = null
        for (row in rows) {
            val result = client.query(sql, keys.map { row[it] })
            changes += result.changes
            executionMs += result.executionMs
            if (result.lastInsertRowid != null) lastInsertRowid = result.lastInsertRowid
        }
        return NxQueryResult(emptyList(), emptyList(), changes, executionMs, false, lastInsertRowid)
    }

    /** UPDATE with a patch map; optional object-form WHERE. Needs at least one column. */
    fun update(patch: Map<String, Any?>, where: Map<String, Any?>? = null): NxQueryResult {
        if (where != null) this.where(where)
        val sets = patch.keys.map { "\"${it}\" = ?" }
        if (sets.isEmpty()) throw NxError("update() needs at least one column to set.", "invalid_input")
        val params = patch.values.toList() + whereParams
        return client.query("UPDATE \"${table}\" SET ${sets.joinToString(", ")}${whereSql()}", params)
    }

    /** DELETE — refuses to run without a WHERE clause. */
    fun delete(where: Map<String, Any?>? = null): NxQueryResult {
        if (where != null) this.where(where)
        if (whereClauses.isEmpty()) {
            throw NxError(
                "delete() refuses to run without a WHERE clause — pass where() or delete(mapOf(\"id\" to ...)).",
                "invalid_input"
            )
        }
        return client.query("DELETE FROM \"${table}\"${whereSql()}", whereParams)
    }

    private fun whereSql(): String =
        if (whereClauses.isEmpty()) "" else " WHERE " + whereClauses.joinToString(" AND ")

    private fun run(): NxQueryResult {
        val cols = columns?.joinToString(", ") { "\"${it}\"" } ?: "*"
        var sql = "SELECT ${cols} FROM \"${table}\"${whereSql()}"
        if (orderByClause != null) sql += " ORDER BY ${orderByClause}"
        if (limitValue != null) sql += " LIMIT ${limitValue}"
        if (offsetValue != null) sql += " OFFSET ${offsetValue}"
        return client.query(sql, whereParams)
    }

    companion object {
        /** JS parity: `const OPS = { eq: "=", ne: "!=", gt: ">", lt: "<", gte: ">=", lte: "<=", like: "LIKE" }`. */
        private val OPS = mapOf(
            "eq" to "=",
            "ne" to "!=",
            "gt" to ">",
            "lt" to "<",
            "gte" to ">=",
            "lte" to "<=",
            "like" to "LIKE"
        )
    }
}
