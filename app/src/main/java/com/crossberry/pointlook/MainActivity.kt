package com.crossberry.pointlook

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import org.mlm.adblock.AdblockEngine
import org.mlm.adblock.RequestTypeMapper
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val blockedCount = AtomicInteger(0)
    @Volatile private var adblockEngine: AdblockEngine? = null
    @Volatile private var protectionEnabled = true
    private var pendingDeviceUrl: String? = null

    private lateinit var rootView: View
    private lateinit var addressBar: TextInputEditText
    private lateinit var pageProgress: LinearProgressIndicator
    private lateinit var shieldChip: Chip
    private lateinit var statusText: TextView
    private lateinit var browser: WebView
    private lateinit var btnBookmarkAdd: MaterialButton

    // Saved-links bottom sheet state
    private var savedSheetDialog: BottomSheetDialog? = null
    private var savedSheetKind: String = Bookmarks.KIND_BOOKMARK
    private var savedSheetList: LinearLayout? = null
    private var savedSheetEmpty: TextView? = null
    private var savedSheetSync: TextView? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val url = pendingDeviceUrl
        pendingDeviceUrl = null
        if (url != null) startDeviceDownload(url, publicDir = granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 25)
        window.navigationBarColor = Color.rgb(12, 15, 25)
        setContentView(R.layout.activity_main)
        bindViews()
        buildBrowser()
        wireBottomBar()
        wireHeaderActions()
        initializeAdblock()
        if (savedInstanceState == null) browser.loadUrl(HOME_URL)
    }

    private fun bindViews() {
        rootView = findViewById(android.R.id.content)
        addressBar = findViewById(R.id.addressBar)
        pageProgress = findViewById(R.id.pageProgress)
        shieldChip = findViewById(R.id.shieldChip)
        statusText = findViewById(R.id.statusText)
        btnBookmarkAdd = findViewById(R.id.btnBookmarkAdd)
        browser = findViewById(R.id.browser)
        shieldChip.setOnClickListener { toggleShield() }
    }

    /* ---------- browser core ---------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildBrowser() {
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.loadsImagesAutomatically = true
        browser.settings.cacheMode = WebSettings.LOAD_DEFAULT
        browser.webChromeClient = WebChromeClient()
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "http" || uri.scheme == "https") addressBar.setText(uri.toString())
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (!protectionEnabled || request == null || request.isForMainFrame) return null
                val engine = adblockEngine ?: return null
                return try {
                    val accept = request.requestHeaders["Accept"] ?: ""
                    val requestType = RequestTypeMapper.from(request.url, accept)
                    val decision = engine.checkNetworkRequest(
                        request.url.toString(),
                        view?.url ?: "",
                        requestType
                    )
                    if (decision.matched) {
                        val total = blockedCount.incrementAndGet()
                        mainHandler.post { updateShieldChip() }
                        WebResourceResponse(
                            "text/plain", "utf-8", 403, "Blocked by Pointlook",
                            emptyMap(), ByteArrayInputStream(ByteArray(0))
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageProgress.visibility = View.VISIBLE
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    addressBar.setText(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageProgress.visibility = View.GONE
                statusText.text = "Protected browsing"
                refreshBookmarkIcon()
            }
        }

        // Download links -> confirm dialog: save to cloud or download to device
        browser.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val target = url ?: return@setDownloadListener
            handleDownloadRequest(target, userAgent ?: "", contentDisposition, mimetype)
        }

        // Long-press -> save image / video to cloud or device, or act on links
        browser.setOnLongClickListener {
            handleLongPress()
        }
    }

    // Async result of requestFocusNodeHref: bundle carries "src", "url", "title"
    private val hitTestHandler = Handler(Looper.getMainLooper()) { msg ->
        val image = msg.data.getString("src") ?: msg.data.getString("url")
        if (image != null) offerMediaSave(image)
        true
    }

    private fun handleLongPress(): Boolean {
        val hit = browser.hitTestResult
        return when (hit.type) {
            WebView.HitTestResult.IMAGE_TYPE -> {
                hit.extra?.let { offerMediaSave(it) }
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                browser.requestFocusNodeHref(android.os.Message.obtain(hitTestHandler))
                true
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                hit.extra?.let { offerLinkMenu(it) }
                true
            }
            else -> false
        }
    }

    private fun offerLinkMenu(url: String) {
        val options = arrayOf("Open link", "Save to favorites", "Copy link")
        MaterialAlertDialogBuilder(this)
            .setTitle("Link")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> navigate(url)
                    1 -> toggleSaved(Bookmarks.KIND_FAVORITE, url, url)
                    2 -> copyToClipboard(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Pointlook", text))
        snackbar("Copied to clipboard")
    }

    /* ---------- header actions ---------- */

    private fun wireHeaderActions() {
        addressBar.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                navigate(addressBar.text.toString())
                true
            } else false
        }
        btnBookmarkAdd.setOnClickListener { toggleSaved(Bookmarks.KIND_BOOKMARK, browser.url ?: "", browser.title ?: browser.url ?: "") }
        val btnMore = findViewById<MaterialButton>(R.id.btnMore)
        btnMore.setOnClickListener { anchor -> showOverflowMenu(anchor) }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_overflow, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_saved_links -> { openSavedSheet(Bookmarks.KIND_BOOKMARK); true }
                R.id.menu_fav_add -> {
                    toggleSaved(Bookmarks.KIND_FAVORITE, browser.url ?: "", browser.title ?: browser.url ?: "")
                    true
                }
                R.id.menu_files -> { startActivity(Intent(this, FilesActivity::class.java)); true }
                R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleShield() {
        protectionEnabled = !protectionEnabled
        updateShieldChip()
        snackbar(if (protectionEnabled) "Shield ON" else "Shield OFF")
    }

    private fun updateShieldChip() {
        shieldChip.text =
            if (protectionEnabled) "Shield ON · ${blockedCount.get()} blocked"
            else "Shield OFF · ${blockedCount.get()} blocked"
    }

    private fun navigate(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val target = when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.contains(" ") || !value.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(value)
            else -> "https://$value"
        }
        browser.loadUrl(target)
    }

    private fun wireBottomBar() {
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { if (browser.canGoBack()) browser.goBack() }
        findViewById<MaterialButton>(R.id.btnForward).setOnClickListener { if (browser.canGoForward()) browser.goForward() }
        findViewById<MaterialButton>(R.id.btnHome).setOnClickListener { browser.loadUrl(HOME_URL) }
        findViewById<MaterialButton>(R.id.btnReload).setOnClickListener { browser.reload() }
        findViewById<MaterialButton>(R.id.btnFavorites).setOnClickListener { openSavedSheet(Bookmarks.KIND_FAVORITE) }
        findViewById<MaterialButton>(R.id.btnFiles).setOnClickListener { startActivity(Intent(this, FilesActivity::class.java)) }
    }

    /* ---------- bookmarks + favorites ---------- */

    private fun toggleSaved(kind: String, url: String, title: String) {
        if (url.isBlank()) {
            snackbar("Nothing to save yet — open a page first")
            return
        }
        val ctx = applicationContext
        val savedTitle = title.ifBlank { url }
        worker.execute {
            val result = Bookmarks.toggle(ctx, kind, savedTitle, url)
            mainHandler.post {
                val msg = when (result) {
                    "added" -> if (kind == Bookmarks.KIND_FAVORITE) "Favorite saved to NxDB cloud" else "Bookmark saved to NxDB cloud"
                    "removed" -> if (kind == Bookmarks.KIND_FAVORITE) "Favorite removed" else "Bookmark removed"
                    "device-added" -> "Saved on device (connect NxDB in Settings to sync)"
                    else -> "Removed from device list"
                }
                snackbar(msg)
                refreshBookmarkIcon()
            }
        }
    }

    private fun refreshBookmarkIcon() {
        val url = browser.url ?: return
        val ctx = applicationContext
        worker.execute {
            val saved = Bookmarks.has(ctx, Bookmarks.KIND_BOOKMARK, url)
            mainHandler.post {
                btnBookmarkAdd.icon?.mutate()?.setTint(
                    if (saved) 0xFFAAA0FF.toInt() else 0xFFC9CFE0.toInt()
                )
            }
        }
    }

    private fun openSavedSheet(initialKind: String) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_bookmarks, null)
        savedSheetDialog = dialog
        savedSheetKind = initialKind
        savedSheetList = view.findViewById(R.id.sheetList)
        savedSheetEmpty = view.findViewById(R.id.sheetEmpty)
        savedSheetSync = view.findViewById(R.id.sheetSync)
        val tabs = view.findViewById<MaterialButtonToggleGroup>(R.id.sheetTabs)
        tabs.check(if (initialKind == Bookmarks.KIND_FAVORITE) R.id.btnSheetFavorites else R.id.btnSheetBookmarks)
        tabs.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                savedSheetKind =
                    if (checkedId == R.id.btnSheetFavorites) Bookmarks.KIND_FAVORITE else Bookmarks.KIND_BOOKMARK
                refreshSavedSheet()
            }
        }
        refreshSavedSheet()
        dialog.setContentView(view)
        dialog.show()
    }

    private fun refreshSavedSheet() {
        val ctx = applicationContext
        val kind = savedSheetKind
        worker.execute {
            val (items, cloud) = Bookmarks.list(ctx, kind)
            mainHandler.post {
                val dialog = savedSheetDialog ?: return@post
                if (!dialog.isShowing) return@post
                val list = savedSheetList ?: return@post
                val empty = savedSheetEmpty
                val sync = savedSheetSync
                sync?.text = if (cloud) "Synced via NxDB cloud" else "On this device only"
                list.removeAllViews()
                empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                empty?.text = if (items.isEmpty())
                    "Nothing saved yet. Use the bookmark button or long-press a link to save it."
                else ""
                for (item in items) {
                    val row = layoutInflater.inflate(R.layout.row_item, list, false)
                    val icon = row.findViewById<ImageView>(R.id.itemIcon)
                    val title = row.findViewById<TextView>(R.id.itemTitle)
                    val urlView = row.findViewById<TextView>(R.id.itemUrl)
                    val delete = row.findViewById<ImageButton>(R.id.itemDelete)
                    icon.setImageResource(
                        if (item.kind == Bookmarks.KIND_FAVORITE) R.drawable.ic_star else R.drawable.ic_bookmark
                    )
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.brand_accent))
                    title.text = item.title.ifBlank { item.url }
                    urlView.text = item.url
                    delete.setColorFilter(ContextCompat.getColor(this, R.color.chrome_hint))
                    delete.setOnClickListener {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Remove")
                            .setMessage("Remove this saved link?")
                            .setPositiveButton("Remove") { _, _ ->
                                val c2 = applicationContext
                                worker.execute {
                                    Bookmarks.removeById(c2, item)
                                    mainHandler.post { refreshSavedSheet() }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    row.setOnClickListener {
                        dialog.dismiss()
                        navigate(item.url)
                    }
                    list.addView(row)
                }
            }
        }
    }

    /* ---------- downloads: cloud + device ---------- */

    private fun handleDownloadRequest(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val filename = DownloadHelper.fileNameFrom(url, contentDisposition)
        val mime = mimetype?.takeIf { it.isNotBlank() } ?: DownloadHelper.guessMime(filename)
        snackbar("Preparing download…")
        worker.execute {
            val (size, probed) = DownloadHelper.probe(url)
            val contentType = probed ?: mime
            mainHandler.post { showDownloadDialog(url, filename, contentType, size) }
        }
    }

    private fun showDownloadDialog(url: String, filename: String, mime: String, size: Long) {
        val host = try { Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
        val sizeText = if (size > 0) DownloadHelper.humanBytes(size) else "unknown size"
        MaterialAlertDialogBuilder(this)
            .setTitle("Download file")
            .setMessage(
                "File: $filename\nType: $mime\nSize: $sizeText\nFrom: $host\n\n" +
                    "Save this file to your NxDB cloud or download it to the device?"
            )
            .setPositiveButton("Save to cloud") { _, _ -> saveToCloud(url, mime, filename) }
            .setNegativeButton("Save to device") { _, _ -> downloadToDevice(url) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun offerMediaSave(url: String) {
        if (url.startsWith("data:")) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Save image")
                .setMessage("Save this inline image to your NxDB cloud?")
                .setPositiveButton("Save to cloud") { _, _ ->
                    saveToCloud(url, "image/png", "image-${System.currentTimeMillis()}.png")
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        snackbar("Checking file…")
        worker.execute {
            val (size, mime) = DownloadHelper.probe(url)
            val filename = DownloadHelper.fileNameFrom(url)
            mainHandler.post {
                showDownloadDialog(url, filename, mime ?: "image/*", size)
            }
        }
    }

    private fun saveToCloud(url: String, mimeHint: String?, filenameHint: String? = null) {
        if (!Prefs.isCloudConfigured(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Cloud not configured")
                .setMessage("Add your NxDB database ID and password in Settings to save files to the cloud.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val filename = filenameHint ?: DownloadHelper.fileNameFrom(url)
        val mime = DownloadHelper.guessMime(filename, mimeHint)
        snackbar("Saving to cloud…")
        val ctx = applicationContext
        worker.execute {
            try {
                val db = NxClient.create(ctx) ?: throw Exception("NxDB not configured")
                val bytes = DownloadHelper.fetchBytes(url)
                val record = db.storage.upload("browser/$filename", bytes, mime, isPublic = true)
                val rec = JSONObject()
                    .put("where", "cloud")
                    .put("name", record.optString("filename", filename))
                    .put("url", url)
                    .put("mime", record.optString("mime", mime))
                    .put(
                        "size",
                        if (record.opt("size") is Number) (record.opt("size") as Number).toLong()
                        else bytes.size.toLong()
                    )
                    .put("cloudId", record.optString("id"))
                    .put("time", System.currentTimeMillis())
                Prefs.appendRecord(ctx, Prefs.KEY_SAVED_FILES, rec)
                mainHandler.post { snackbar("Saved to cloud: $filename") }
            } catch (e: Exception) {
                mainHandler.post { snackbar("Cloud save failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun downloadToDevice(url: String) {
        val canPublic = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < 29 && !canPublic) {
            pendingDeviceUrl = url
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDeviceDownload(url, publicDir = canPublic)
    }

    private fun startDeviceDownload(url: String, publicDir: Boolean) {
        val filename = DownloadHelper.fileNameFrom(url)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading via Pointlook")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            addRequestHeader("User-Agent", "Pointlook Android Browser")
            DownloadHelper.webCookies(url)?.let { addRequestHeader("Cookie", it) }
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        if (publicDir) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        } else {
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
        }
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            val rec = JSONObject()
                .put("where", "device")
                .put("name", filename)
                .put("url", url)
                .put("mime", DownloadHelper.guessMime(filename))
                .put("downloadId", id)
                .put("time", System.currentTimeMillis())
            Prefs.appendRecord(applicationContext, Prefs.KEY_SAVED_FILES, rec)
            snackbar("Download started: $filename")
        } catch (e: Exception) {
            snackbar("Download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /* ---------- adblock engine ---------- */

    private fun initializeAdblock() {
        statusText.text = "Loading filter list…"
        worker.execute {
            try {
                val filterText = downloadText(EASYLIST_URL)
                val engine = AdblockEngine.create()
                engine.loadFilterList(filterText)
                val resources = assets.open("adblock/resources.json").bufferedReader().use { it.readText() }
                engine.loadResources(resources)
                adblockEngine = engine
                mainHandler.post { statusText.text = "AdBlock ready" }
            } catch (_: Exception) {
                mainHandler.post { statusText.text = "Protection unavailable" }
            }
        }
    }

    private fun downloadText(source: String): String {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Pointlook Android Browser")
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun snackbar(text: String) {
        Snackbar.make(rootView, text, Snackbar.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        adblockEngine?.close()
        worker.shutdownNow()
        savedSheetDialog?.dismiss()
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://www.google.com"
        private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
    }
}
