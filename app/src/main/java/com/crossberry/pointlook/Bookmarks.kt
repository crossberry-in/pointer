package com.crossberry.pointlook

import android.content.Context
import com.nxmirror.db.NxDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SavedItem(
    val id: Long,
    val kind: String,
    val title: String,
    val url: String,
    val createdAt: String,
    val cloud: Boolean
)

/**
 * Bookmarks + favorite links. When NxDB is configured in Settings, items are
 * stored in the cloud table `pointlook_items` so they survive reinstalls and
 * sync across devices; otherwise a device-local list is used.
 */
object Bookmarks {
    const val KIND_BOOKMARK = "bookmark"
    const val KIND_FAVORITE = "favorite"
    private const val TABLE = "pointlook_items"
    private var tableReady = false

    fun now(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun ensureTable(db: NxDatabase) {
        if (tableReady) return
        db.exec(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "url TEXT NOT NULL, " +
                "created_at TEXT NOT NULL)"
        )
        tableReady = true
    }

    /** Returns (items, syncedWithCloud). Blocking — call from a worker thread. */
    fun list(context: Context, kind: String): Pair<List<SavedItem>, Boolean> {
        val db = NxClient.create(context)
        if (db != null) {
            try {
                ensureTable(db)
                val result = db.query(
                    "SELECT id, kind, title, url, created_at FROM $TABLE WHERE kind = ? ORDER BY id DESC",
                    listOf(kind)
                )
                val items = result.rows.map { row ->
                    SavedItem(
                        id = row.optLong("id"),
                        kind = row.optString("kind"),
                        title = row.optString("title"),
                        url = row.optString("url"),
                        createdAt = row.optString("created_at"),
                        cloud = true
                    )
                }
                return items to true
            } catch (_: Exception) {
                // cloud unavailable — fall back to device storage
            }
        }
        return localList(context, kind) to false
    }

    private fun localList(context: Context, kind: String): List<SavedItem> {
        val arr = Prefs.loadList(context, Prefs.KEY_LOCAL_ITEMS)
        val items = mutableListOf<SavedItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("kind") != kind) continue
            items.add(
                SavedItem(
                    id = obj.optLong("id"),
                    kind = kind,
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    createdAt = obj.optString("createdAt"),
                    cloud = false
                )
            )
        }
        return items.sortedByDescending { it.id }
    }

    /** Toggle: removes when present, adds when missing. Blocking — call from a worker thread. */
    fun toggle(context: Context, kind: String, title: String, url: String): String {
        val db = NxClient.create(context)
        if (db != null) {
            try {
                ensureTable(db)
                val existing = db.query(
                    "SELECT id FROM $TABLE WHERE kind = ? AND url = ? LIMIT 1",
                    listOf(kind, url)
                )
                val row = existing.rows.firstOrNull()
                return if (row != null) {
                    db.query("DELETE FROM $TABLE WHERE id = ?", listOf(row.optLong("id")))
                    "removed"
                } else {
                    db.query(
                        "INSERT INTO $TABLE (kind, title, url, created_at) VALUES (?, ?, ?, ?)",
                        listOf(kind, title, url, now())
                    )
                    "added"
                }
            } catch (_: Exception) {
                // cloud unavailable — fall through to device storage
            }
        }
        val arr = Prefs.loadList(context, Prefs.KEY_LOCAL_ITEMS)
        var removed = false
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("kind") == kind && obj.optString("url") == url) {
                removed = true
                continue
            }
            out.put(obj)
        }
        if (!removed) {
            out.put(
                JSONObject()
                    .put("id", System.currentTimeMillis())
                    .put("kind", kind)
                    .put("title", title)
                    .put("url", url)
                    .put("createdAt", now())
            )
        }
        Prefs.saveList(context, Prefs.KEY_LOCAL_ITEMS, out)
        return if (removed) "device-removed" else "device-added"
    }

    /** Whether [url] is already saved with [kind]. Blocking — call from a worker thread. */
    fun has(context: Context, kind: String, url: String): Boolean =
        list(context, kind).first.any { it.url == url }

    fun removeById(context: Context, item: SavedItem) {
        if (item.cloud) {
            val db = NxClient.create(context) ?: return
            try {
                db.query("DELETE FROM $TABLE WHERE id = ?", listOf(item.id))
            } catch (_: Exception) {
            }
        } else {
            Prefs.removeRecord(context, Prefs.KEY_LOCAL_ITEMS) {
                it.optString("kind") == item.kind && it.optLong("id") == item.id
            }
        }
    }
}
