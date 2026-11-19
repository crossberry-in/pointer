package com.crossberry.pointlook

import android.content.Context
import android.net.Uri
import com.nxmirror.db.NxConfig
import com.nxmirror.db.NxDatabase

/** Builds NxDatabase clients from the credentials saved in Settings. */
object NxClient {
    const val DEFAULT_SITE = "https://nxmirror.netlify.app"

    /** Returns a client, or null when the user has not configured NxDB yet. */
    fun create(context: Context): NxDatabase? {
        val id = Prefs.nxDbId(context)
        val pass = Prefs.nxDbPassword(context)
        if (id.isBlank() || pass.isBlank()) return null
        return try {
            NxDatabase(NxConfig(databaseId = id, password = pass))
        } catch (_: Exception) {
            null
        }
    }

    /** Anonymous download URL for a public file in the database. */
    fun publicUrl(databaseId: String, fileId: String): String =
        "$DEFAULT_SITE/api/db/storage/download/$fileId?db=" + Uri.encode(databaseId)
}
