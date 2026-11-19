package com.crossberry.pointlook

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences helpers — cloud settings, local fallback lists, download log. */
object Prefs {
    private const val FILE = "pointlook_settings"
    private const val KEY_DB_ID = "nxdb_id"
    private const val KEY_DB_PASS = "nxdb_password"

    /** Local fallback for bookmarks/favorites when no NxDB is configured. */
    const val KEY_LOCAL_ITEMS = "local_items"

    /** Log of every cloud save and device download (drives the Saved files screen). */
    const val KEY_SAVED_FILES = "saved_files_log"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nxDbId(context: Context): String = sp(context).getString(KEY_DB_ID, "") ?: ""

    fun nxDbPassword(context: Context): String = sp(context).getString(KEY_DB_PASS, "") ?: ""

    fun isCloudConfigured(context: Context): Boolean =
        nxDbId(context).isNotBlank() && nxDbPassword(context).isNotBlank()

    fun saveCloudSettings(context: Context, id: String, password: String) {
        sp(context).edit()
            .putString(KEY_DB_ID, id.trim())
            .putString(KEY_DB_PASS, password)
            .apply()
    }

    fun clearCloudSettings(context: Context) {
        sp(context).edit().remove(KEY_DB_ID).remove(KEY_DB_PASS).apply()
    }

    fun loadList(context: Context, key: String): JSONArray {
        val raw = sp(context).getString(key, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun saveList(context: Context, key: String, value: JSONArray) {
        sp(context).edit().putString(key, value.toString()).apply()
    }

    fun appendRecord(context: Context, key: String, record: JSONObject) {
        val arr = loadList(context, key)
        arr.put(record)
        saveList(context, key, arr)
    }

    fun removeRecord(context: Context, key: String, predicate: (JSONObject) -> Boolean) {
        val arr = loadList(context, key)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!predicate(obj)) out.put(obj)
        }
        saveList(context, key, out)
    }
}
