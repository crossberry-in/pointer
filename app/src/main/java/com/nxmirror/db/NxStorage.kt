package com.nxmirror.db

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Hybrid file storage — small files are stored as SQL BLOBs, larger ones in
 * object storage; the SDK picks the backend automatically and switches to
 * resumable part uploads above 3.4 MB (up to 50 MB). Overwrites create new
 * versions; every file gets a SHA-256 checksum.
 *
 * Works on both engines (sqlite and nosql databases).
 */
class NxStorage internal constructor(private val client: NxDatabase) {

    /** Folder scope: `db.storage.from("images").list()`. */
    fun from(folder: String): NxFolderScope = NxFolderScope(this, folder)

    /** List files, optionally scoped to a [folder] and/or a filename [q] search. */
    fun list(folder: String? = null, q: String? = null): JSONArray {
        val query = StringBuilder("storage/list?db=").append(urlEncode(client.databaseId))
        if (!folder.isNullOrEmpty()) query.append("&folder=").append(urlEncode(folder))
        if (!q.isNullOrEmpty()) query.append("&q=").append(urlEncode(q))
        return client.requestJson(query.toString()).optJSONArray("files") ?: JSONArray()
    }

    /** Search files by name fragment. */
    fun search(q: String): JSONArray = list(q = q)

    /**
     * Upload raw bytes. Small payloads go in a single request; payloads above
     * [SINGLE_UPLOAD_MAX] switch to resumable part uploads (cap [RESUMABLE_MAX_TOTAL]).
     *
     * @param path       storage path including the filename, e.g. `"images/logo.png"`
     * @param mime       optional; the server sniffs when omitted — explicit is safest
     * @param isPublic   when true the file is reachable anonymously without a signature
     * @param onProgress optional progress callback `(loaded, total, pct, part, parts)`
     * @return the file record (`id`, `filename`, `mime`, `size`, `backend`, `checksum`, ...)
     */
    fun upload(
        path: String,
        file: ByteArray,
        mime: String? = null,
        isPublic: Boolean? = null,
        onProgress: ((loaded: Int, total: Int, pct: Int, part: Int, parts: Int) -> Unit)? = null
    ): JSONObject = uploadBytes(path, file, mime, isPublic, onProgress)

    /**
     * Upload flexible content: a [ByteArray] or a [String] (plain text, raw
     * base64, or a `data:` URL). Strings that look like base64 are decoded;
     * everything else is sent as UTF-8 text.
     */
    fun uploadContent(
        path: String,
        content: Any,
        mime: String? = null,
        isPublic: Boolean? = null,
        onProgress: ((loaded: Int, total: Int, pct: Int, part: Int, parts: Int) -> Unit)? = null
    ): JSONObject = uploadBytes(path, toBytes(content), mime, isPublic, onProgress)

    /** Convenience for plain text uploads. */
    fun uploadText(
        path: String,
        text: String,
        mime: String? = null,
        isPublic: Boolean? = null
    ): JSONObject = uploadBytes(path, toBytes(text), mime, isPublic, null)

    private fun uploadBytes(
        path: String,
        bytes: ByteArray,
        mime: String?,
        isPublic: Boolean?,
        onProgress: ((loaded: Int, total: Int, pct: Int, part: Int, parts: Int) -> Unit)?
    ): JSONObject {
        if (path.isBlank()) throw NxError("upload() needs a path like 'images/logo.png'.", "invalid_input")
        val clean = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val filename = clean.lastOrNull()
            ?: throw NxError("upload() needs a path like 'images/logo.png'.", "invalid_input")
        val folder = clean.dropLast(1).joinToString("/")

        if (bytes.size > SINGLE_UPLOAD_MAX) {
            if (bytes.size > RESUMABLE_MAX_TOTAL) {
                throw NxError("Files are limited to ${RESUMABLE_MAX_TOTAL / 1024 / 1024} MB.", "too_large", 413)
            }
            val initBody = JSONObject()
                .put("databaseId", client.databaseId)
                .put("filename", filename)
                .put("folder", folder)
                .put("size", bytes.size)
                .put("partSize", RESUMABLE_PART_SIZE)
            if (mime != null) initBody.put("mime", mime)
            val init = client.requestJson("storage/upload/init", "POST", initBody)
            val uploadId = init.optString("uploadId")
            val partCount = (init.opt("partCount") as? Number)?.toInt() ?: 0
            for (index in 0 until partCount) {
                val from = index * RESUMABLE_PART_SIZE
                val to = minOf(bytes.size, (index + 1) * RESUMABLE_PART_SIZE)
                val chunk = bytes.copyOfRange(from, to)
                client.requestJson(
                    "storage/upload/part",
                    "PUT",
                    JSONObject()
                        .put("databaseId", client.databaseId)
                        .put("uploadId", uploadId)
                        .put("index", index)
                        .put("content", bytesToBase64(chunk))
                )
                if (onProgress != null) {
                    val loaded = minOf(bytes.size, (index + 1) * RESUMABLE_PART_SIZE)
                    val pct = (((index + 1).toDouble() / partCount) * 99).let {
                        kotlin.math.round(it).toInt().coerceAtMost(99)
                    }
                    onProgress(loaded, bytes.size, pct, index + 1, partCount)
                }
            }
            val completeBody = JSONObject()
                .put("databaseId", client.databaseId)
                .put("uploadId", uploadId)
            if (isPublic != null) completeBody.put("public", isPublic)
            val completed = client.requestJson("storage/upload/complete", "POST", completeBody)
            if (onProgress != null) onProgress(bytes.size, bytes.size, 100, partCount, partCount)
            return completed.optJSONObject("file") ?: JSONObject()
        }

        val body = JSONObject()
            .put("databaseId", client.databaseId)
            .put("filename", filename)
            .put("folder", folder)
            .put("content", bytesToBase64(bytes))
        if (mime != null) body.put("mime", mime)
        if (isPublic != null) body.put("public", isPublic)
        val result = client.requestJson("storage/upload", "POST", body)
        if (onProgress != null) onProgress(bytes.size, bytes.size, 100, 1, 1)
        return result.optJSONObject("file") ?: JSONObject()
    }

    /**
     * Download a file as raw bytes.
     * Idempotent: transient 404/5xx responses are retried with backoff.
     */
    fun download(fileId: String): ByteArray =
        client.requestBytes("storage/download/${urlEncode(fileId)}?db=${urlEncode(client.databaseId)}")

    /** Short-lived signed URL for private files (default 15 minutes, 5-60 min TTL). */
    fun signedUrl(fileId: String, ttlSeconds: Int = 900): String {
        val result = client.requestJson(
            "storage/signed",
            "POST",
            JSONObject()
                .put("databaseId", client.databaseId)
                .put("id", fileId)
                .put("ttlSeconds", ttlSeconds)
        )
        return client.apiBase.removeSuffix("/api") + result.optString("url")
    }

    /** Rename and/or move a file. Returns the updated file record. */
    fun rename(fileId: String, filename: String? = null, folder: String? = null): JSONObject {
        val body = JSONObject()
            .put("databaseId", client.databaseId)
            .put("id", fileId)
        if (filename != null) body.put("filename", filename)
        if (folder != null) body.put("folder", folder)
        return client.requestJson("storage/update", "POST", body).optJSONObject("file") ?: JSONObject()
    }

    /** Move a file into [folder]. */
    fun move(fileId: String, folder: String): JSONObject = rename(fileId, folder = folder)

    /** Copy a file, optionally renaming / moving the copy. Returns the new file record. */
    fun copy(fileId: String, filename: String? = null, folder: String? = null): JSONObject {
        val body = JSONObject()
            .put("databaseId", client.databaseId)
            .put("id", fileId)
        if (filename != null) body.put("filename", filename)
        if (folder != null) body.put("folder", folder)
        return client.requestJson("storage/copy", "POST", body).optJSONObject("file") ?: JSONObject()
    }

    /** Create a folder path like `"exports/2026"`. */
    fun mkdir(path: String): JSONObject =
        client.requestJson(
            "storage/folders",
            "POST",
            JSONObject().put("databaseId", client.databaseId).put("path", path)
        )

    /** Delete a file. Returns true on success. */
    fun delete(fileId: String): Boolean {
        client.requestJson(
            "storage/delete",
            "POST",
            JSONObject().put("databaseId", client.databaseId).put("id", fileId)
        )
        return true
    }

    /** Version history — overwrites create new versions. Returns `{ current, versions }`. */
    fun versions(fileId: String): JSONObject {
        val result = client.requestJson("storage/versions/${urlEncode(fileId)}?db=${urlEncode(client.databaseId)}")
        return JSONObject()
            .put("current", result.opt("current") ?: JSONObject.NULL)
            .put("versions", result.optJSONArray("versions") ?: JSONArray())
    }

    /**
     * JS parity of `toBytes()`: [ByteArray] passes through; a [String] is a
     * data URL (stripped), raw base64 (decoded), or plain text (UTF-8).
     */
    internal fun toBytes(content: Any): ByteArray {
        if (content is ByteArray) return content
        if (content is String) {
            if (content.isEmpty()) {
                throw NxError("No file content provided for upload.", "invalid_input")
            }
            var input = content
            if (DATA_URL_REGEX.containsMatchIn(input)) input = input.substring(input.indexOf(',') + 1)
            val stripped = input.replace(WHITESPACE_REGEX, "")
            if (BASE64_REGEX.matches(stripped) && stripped.length % 4 == 0) {
                return Base64.getDecoder().decode(stripped)
            }
            return input.toByteArray(Charsets.UTF_8)
        }
        throw NxError(
            "Unsupported file content type. Pass a ByteArray or a String (plain text, base64 or a data URL).",
            "invalid_input"
        )
    }

    companion object {
        /** Single-request upload ceiling — the SDK switches to resumable above this (server limit 3.5 MB). */
        const val SINGLE_UPLOAD_MAX = 3_565_158 // 3.4 * 1024 * 1024

        /** Resumable part size (3 MB). */
        const val RESUMABLE_PART_SIZE = 3_145_728

        /** Hard upload cap (50 MB). */
        const val RESUMABLE_MAX_TOTAL = 52_428_800

        private val DATA_URL_REGEX = Regex("^data:[^;]+;base64,")
        private val BASE64_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** JS parity of `bytesToBase64()`. */
        fun bytesToBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

/** Folder scope returned by [NxStorage.from]. */
class NxFolderScope internal constructor(
    private val storage: NxStorage,
    private val folder: String
) {
    /** List the files in this folder, optionally filtered by name fragment [q]. */
    fun list(q: String? = null): JSONArray = storage.list(folder, q)
}
