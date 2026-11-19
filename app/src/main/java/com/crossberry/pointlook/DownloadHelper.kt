package com.crossberry.pointlook

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/** Filename probing, byte fetching, mime guessing and saving to device storage. */
object DownloadHelper {
    /** NxDB hard upload cap. */
    const val CLOUD_SAVE_CAP = 50L * 1024 * 1024

    fun fileNameFrom(url: String, contentDisposition: String? = null, fallback: String = "file"): String {
        var name: String? = null
        if (!contentDisposition.isNullOrBlank()) {
            val m = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
                ?: Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (m != null) {
                name = try {
                    URLDecoder.decode(m.groupValues[1].trim(), "UTF-8")
                } catch (_: Exception) {
                    m.groupValues[1]
                }
            }
        }
        if (name.isNullOrBlank()) {
            val raw = url.substringBefore('?').substringAfterLast('/')
            name = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        val clean = name?.trim().orEmpty().ifBlank { fallback }
        return clean.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
    }

    fun guessMime(name: String, hint: String? = null): String {
        if (!hint.isNullOrBlank() && hint != "application/octet-stream") return hint
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    fun humanBytes(n: Long): String = when {
        n >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
        n >= (1L shl 10) -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> "$n B"
    }

    /** Cheap HEAD probe: returns (contentLength or -1, contentType or null). */
    fun probe(url: String): Pair<Long, String?> {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Pointlook Android Browser")
            }
            try {
                val len = when {
                    conn.contentLengthLong > 0 -> conn.contentLengthLong
                    conn.contentLength > 0 -> conn.contentLength.toLong()
                    else -> -1L
                }
                len to conn.contentType
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            -1L to null
        }
    }

    fun webCookies(url: String): String? = CookieManager.getInstance().getCookie(url)

    /** Fetch remote bytes (follows redirects, carries cookies, enforces the cloud cap). */
    fun fetchBytes(url: String, cap: Long = CLOUD_SAVE_CAP): ByteArray {
        if (url.startsWith("data:")) return dataUrlBytes(url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Pointlook Android Browser")
            webCookies(url)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val input = conn.inputStream
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > cap) throw Exception("File is larger than the ${cap / 1024 / 1024} MB cloud limit")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    fun dataUrlBytes(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) throw Exception("Invalid data URL")
        val meta = dataUrl.substring(0, comma)
        val payload = dataUrl.substring(comma + 1)
        return if (meta.contains("base64", ignoreCase = true)) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            payload.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Save bytes into the public Downloads folder (MediaStore on API 29+,
     * classic filesystem on older devices with a safe app-folder fallback).
     * Returns a human description of where the file landed.
     */
    fun saveBytesToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create the download entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw Exception("Could not write the file")
            return "Downloads/$name"
        }
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val target = uniqueFile(dir, name)
            FileOutputStream(target).use { it.write(bytes) }
            target.absolutePath
        } catch (e: Exception) {
            val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw Exception("Storage unavailable: ${e.message}")
            val target = uniqueFile(appDir, name)
            FileOutputStream(target).use { it.write(bytes) }
            "App folder: ${target.name}"
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }
}
