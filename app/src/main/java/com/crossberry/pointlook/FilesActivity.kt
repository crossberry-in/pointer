package com.crossberry.pointlook

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saved files showcase — every cloud save and device download appears here
 * with full file details (name, type, size, date, source URL, cloud metadata).
 */
class FilesActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootView: View
    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView
    private var currentTab = "cloud"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        rootView = findViewById(android.R.id.content)
        listBox = findViewById(R.id.listBox)
        emptyText = findViewById(R.id.emptyText)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_files)
        tintMenu(toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> { load(); true }
                R.id.action_open_downloads -> {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                    true
                }
                else -> false
            }
        }

        val tabs = findViewById<MaterialButtonToggleGroup>(R.id.tabGroup)
        tabs.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                currentTab = if (checkedId == R.id.btnTabDevice) "device" else "cloud"
                load()
            }
        }
        tabs.check(R.id.btnTabCloud)
    }

    private fun tintMenu(toolbar: MaterialToolbar) {
        val color = ContextCompat.getColor(this, R.color.chrome_icon)
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.mutate()?.setTint(color)
        }
    }

    private fun load() {
        if (currentTab == "cloud") loadCloud() else loadDevice()
    }

    /* ---------- cloud files ---------- */

    private fun loadCloud() {
        listBox.removeAllViews()
        emptyText.visibility = View.VISIBLE
        emptyText.text = "Loading cloud files…"
        val ctx = applicationContext
        worker.execute {
            val db = NxClient.create(ctx)
            if (db == null) {
                mainHandler.post {
                    emptyText.text = "Cloud not configured. Add your NxDB database ID " +
                        "and password in Settings, then save files from the browser."
                }
                return@execute
            }
            try {
                val files = db.storage.list(folder = "browser")
                mainHandler.post { renderCloud(files) }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                mainHandler.post { emptyText.text = "Could not load cloud files: $msg" }
            }
        }
    }

    private fun renderCloud(files: JSONArray) {
        listBox.removeAllViews()
        emptyText.visibility = if (files.length() == 0) View.VISIBLE else View.GONE
        emptyText.text = if (files.length() == 0)
            "No files in the cloud folder 'browser' yet. Long-press an image in the " +
                "browser or use the download prompt to save files to the cloud."
        else ""
        for (i in 0 until files.length()) {
            val rec = files.optJSONObject(i) ?: continue
            val filename = rec.optString("filename", "file")
            val mime = rec.optString("mime", "")
            val size = rec.optLong("size")
            val row = layoutInflater.inflate(R.layout.row_file, listBox, false)
            val icon = row.findViewById<ImageView>(R.id.fileIcon)
            val name = row.findViewById<TextView>(R.id.fileName)
            val meta = row.findViewById<TextView>(R.id.fileMeta)
            name.text = filename
            meta.text = listOfNotNull(
                DownloadHelper.humanBytes(size).takeIf { size > 0 },
                mime.takeIf { it.isNotBlank() },
                dateString(rec).takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")
            icon.setImageResource(mimeIcon(mime, filename))
            icon.setColorFilter(ContextCompat.getColor(this, R.color.brand_accent))
            row.setOnClickListener { cloudDetails(rec) }
            listBox.addView(row)
        }
    }

    private fun cloudDetails(rec: JSONObject) {
        val id = rec.optString("id")
        val filename = rec.optString("filename", "file")
        val mime = rec.optString("mime", "unknown")
        val size = rec.optLong("size")
        val folder = rec.optString("folder", "")
        val backend = rec.optString("backend", "")
        val checksum = rec.optString("checksum", "")
        val source = findSource(id)
        val message = buildString {
            append("Name: $filename\n")
            append("Type: $mime\n")
            if (size > 0) append("Size: ${DownloadHelper.humanBytes(size)}\n")
            if (folder.isNotBlank()) append("Folder: $folder\n")
            if (backend.isNotBlank()) append("Storage backend: $backend\n")
            if (id.isNotBlank()) append("Cloud ID: ${id.take(18)}\n")
            if (checksum.length > 16) append("Checksum: ${checksum.take(16)}\n")
            append("Saved: ${dateString(rec).ifBlank { "unknown date" }}\n")
            if (source.isNotBlank()) append("Source: $source")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("File details")
            .setMessage(message)
            .setPositiveButton("Save to device") { _, _ ->
                downloadCloudFile(id, filename, mime)
            }
            .setNegativeButton("Copy link") { _, _ ->
                copyLink(NxClient.publicUrl(Prefs.nxDbId(this), id))
            }
            .setNeutralButton("Delete") { _, _ -> confirmDelete(rec) }
            .show()
    }

    private fun downloadCloudFile(id: String, filename: String, mime: String) {
        snackbar("Downloading from cloud…")
        val ctx = applicationContext
        worker.execute {
            try {
                val db = NxClient.create(ctx) ?: throw Exception("NxDB not configured")
                val bytes = db.storage.download(id)
                val where = DownloadHelper.saveBytesToDownloads(
                    ctx, filename, mime.ifBlank { "application/octet-stream" }, bytes
                )
                mainHandler.post { snackbar("Saved to $where") }
            } catch (e: Exception) {
                mainHandler.post { snackbar("Save failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun confirmDelete(rec: JSONObject) {
        val filename = rec.optString("filename", "file")
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete cloud file")
            .setMessage("Delete $filename from the NxDB cloud? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ctx = applicationContext
                val id = rec.optString("id")
                worker.execute {
                    try {
                        NxClient.create(ctx)?.storage?.delete(id)
                        Prefs.removeRecord(ctx, Prefs.KEY_SAVED_FILES) {
                            it.optString("cloudId") == id
                        }
                        mainHandler.post {
                            snackbar("Deleted from cloud")
                            if (currentTab == "cloud") loadCloud() else loadDevice()
                        }
                    } catch (e: Exception) {
                        mainHandler.post { snackbar("Delete failed: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun findSource(cloudId: String): String {
        val arr = Prefs.loadList(this, Prefs.KEY_SAVED_FILES)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("cloudId") == cloudId) return obj.optString("url")
        }
        return ""
    }

    /* ---------- device downloads ---------- */

    private fun loadDevice() {
        listBox.removeAllViews()
        val arr = Prefs.loadList(this, Prefs.KEY_SAVED_FILES)
        val rows = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("where") == "device") rows.add(obj)
        }
        rows.sortByDescending { it.optLong("time") }
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (rows.isEmpty())
            "No device downloads yet. When you click a download link in the browser, " +
                "choose 'Save to device' and it will be tracked here."
        else ""
        for (rec in rows) {
            val filename = rec.optString("name", "file")
            val mime = rec.optString("mime", "")
            val size = rec.optLong("size")
            val time = rec.optLong("time")
            val row = layoutInflater.inflate(R.layout.row_file, listBox, false)
            val icon = row.findViewById<ImageView>(R.id.fileIcon)
            val name = row.findViewById<TextView>(R.id.fileName)
            val meta = row.findViewById<TextView>(R.id.fileMeta)
            name.text = filename
            meta.text = listOfNotNull(
                DownloadHelper.humanBytes(size).takeIf { size > 0 },
                mime.takeIf { it.isNotBlank() },
                if (time > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time)) else null,
                "device download"
            ).joinToString("  ·  ")
            icon.setImageResource(mimeIcon(mime, filename))
            icon.setColorFilter(ContextCompat.getColor(this, R.color.brand_accent))
            row.setOnClickListener { deviceDetails(rec) }
            listBox.addView(row)
        }
    }

    private fun deviceDetails(rec: JSONObject) {
        val name = rec.optString("name", "file")
        val url = rec.optString("url")
        val mime = rec.optString("mime", "unknown")
        val size = rec.optLong("size")
        val time = rec.optLong("time")
        val downloadId = rec.optLong("downloadId")
        val message = buildString {
            append("Name: $name\n")
            append("Type: $mime\n")
            if (size > 0) append("Size: ${DownloadHelper.humanBytes(size)}\n")
            if (time > 0) append("Saved: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time))}\n")
            if (downloadId > 0) append("Download ID: $downloadId\n")
            if (url.isNotBlank()) append("Source: $url")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("File details")
            .setMessage(message)
            .setPositiveButton("Open downloads") { _, _ ->
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            }
            .setNegativeButton("Remove from list") { _, _ ->
                Prefs.removeRecord(this, Prefs.KEY_SAVED_FILES) {
                    it.optLong("downloadId") == downloadId
                }
                loadDevice()
            }
            .setNeutralButton("Close", null)
            .show()
    }

    /* ---------- helpers ---------- */

    private fun mimeIcon(mime: String, name: String): Int {
        val m = mime.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") ||
                ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg") -> R.drawable.ic_image
            m.startsWith("video/") ||
                ext in listOf("mp4", "webm", "mkv", "mov", "avi", "3gp") -> R.drawable.ic_movie
            else -> R.drawable.ic_file
        }
    }

    private fun dateString(rec: JSONObject): String {
        for (key in listOf("created_at", "createdAt", "updated_at", "updatedAt")) {
            val value = rec.opt(key) ?: continue
            if (value is Number && value.toLong() > 0) {
                return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(value.toLong()))
            }
            val s = value.toString()
            if (s.isNotBlank() && s != "null") return s.replace('T', ' ').take(16)
        }
        return ""
    }

    private fun copyLink(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Pointlook link", url))
        snackbar("Public link copied")
    }

    private fun snackbar(text: String) {
        Snackbar.make(rootView, text, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
