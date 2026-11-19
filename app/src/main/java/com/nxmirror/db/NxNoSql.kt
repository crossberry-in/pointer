package com.nxmirror.db

import org.json.JSONArray
import org.json.JSONObject

/**
 * NoSQL document API for databases created with engine `nosql`.
 *
 * Filter syntax (AND across keys, dot-paths into nested objects):
 *   `{ "field": value }`              exact match
 *   `{ "field": { "$gt": 10 } }`      $eq $ne $gt $gte $lt $lte $in $nin $exists $contains $startsWith
 *   `{ "$or": [ {..}, {..} ] }`       top-level OR
 *
 * Every document gets immutable metadata: `_id`, `_createdAt`, `_updatedAt`.
 */
class NxNoSql internal constructor(private val client: NxDatabase) {

    /** Every op asserts the engine and posts to `{apiBase}/db/nosql/{op}`. */
    private fun op(op: String, payload: JSONObject): JSONObject {
        client.assertEngine("nosql")
        payload.put("databaseId", client.databaseId)
        return client.requestJson("nosql/$op", "POST", payload)
    }

    /** Insert one document. Returns `{ inserted, ids, lastInsertId }`. */
    fun insert(collection: String, doc: JSONObject): JSONObject =
        op("insert", JSONObject().put("collection", collection).put("doc", doc))

    /** Insert a batch of documents (200 max). Returns `{ inserted, ids, lastInsertId }`. */
    fun insertAll(collection: String, docs: List<JSONObject>): JSONObject {
        val arr = JSONArray()
        for (doc in docs) arr.put(doc)
        return op("insert", JSONObject().put("collection", collection).put("docs", arr))
    }

    /**
     * Query documents. Returns the raw server result:
     * `{ docs: [...], total, truncated }`.
     */
    fun find(
        collection: String,
        filter: JSONObject? = null,
        projection: JSONArray? = null,
        sort: JSONObject? = null,
        limit: Int? = null,
        offset: Int? = null
    ): JSONObject {
        val payload = JSONObject().put("collection", collection)
        filter?.let { payload.put("filter", it) }
        projection?.let { payload.put("projection", it) }
        sort?.let { payload.put("sort", it) }
        limit?.let { payload.put("limit", it) }
        offset?.let { payload.put("offset", it) }
        return op("find", payload)
    }

    /** Fetch one document by `_id` — `null` when missing (404). */
    fun get(collection: String, id: String): JSONObject? {
        return try {
            op("get", JSONObject().put("collection", collection).put("id", id)).optJSONObject("doc")
        } catch (e: NxError) {
            if (e.status == 404) null else throw e
        }
    }

    /** Count documents matching a filter (all when [filter] is null). */
    fun count(collection: String, filter: JSONObject? = null): Int {
        val payload = JSONObject().put("collection", collection)
        filter?.let { payload.put("filter", it) }
        return (op("count", payload).opt("count") as? Number)?.toInt() ?: 0
    }

    /** Update one document by `_id`. Returns `{ updated, ids }`. */
    fun update(collection: String, id: String, patch: JSONObject, many: Boolean = false): JSONObject =
        op("update", JSONObject().put("collection", collection).put("id", id).put("patch", patch).put("many", many))

    /**
     * Update documents matching a filter. Multi-match REQUIRES `many = true`
     * — otherwise the server answers 409 `ambiguous_match`.
     * Returns `{ updated, ids }`.
     */
    fun update(collection: String, filter: JSONObject, patch: JSONObject, many: Boolean = false): JSONObject =
        op("update", JSONObject().put("collection", collection).put("filter", filter).put("patch", patch).put("many", many))

    /** Replace a document by `_id` (keeps `_id`/`_createdAt`). Returns `{ replaced }`. */
    fun replace(collection: String, id: String, doc: JSONObject): JSONObject =
        op("replace", JSONObject().put("collection", collection).put("id", id).put("doc", doc))

    /** Delete one document by `_id`. Returns `{ deleted, ids }`. */
    fun delete(collection: String, id: String, many: Boolean = false): JSONObject =
        op("delete", JSONObject().put("collection", collection).put("id", id).put("many", many))

    /**
     * Delete documents matching a filter. Multi-match REQUIRES `many = true`
     * — otherwise the server answers 409 `ambiguous_match`.
     * Returns `{ deleted, ids }`.
     */
    fun delete(collection: String, filter: JSONObject, many: Boolean = false): JSONObject =
        op("delete", JSONObject().put("collection", collection).put("filter", filter).put("many", many))

    /** List collections: `[{ name, count, bytes }]`. */
    fun listCollections(): JSONArray =
        op("collections", JSONObject()).optJSONArray("collections") ?: JSONArray()

    /** Drop a whole collection (needs schema permission). Returns `{ dropped, documents }`. */
    fun drop(collection: String): JSONObject =
        op("drop", JSONObject().put("collection", collection))
}
