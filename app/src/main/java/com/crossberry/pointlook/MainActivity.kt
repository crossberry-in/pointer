package com.crossberry.pointlook

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.mlm.adblock.AdblockEngine
import org.mlm.adblock.RequestTypeMapper
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val blockedCount = AtomicInteger(0)
    @Volatile private var adblockEngine: AdblockEngine? = null
    @Volatile private var protectionEnabled = true
    private lateinit var browser: WebView
    private lateinit var addressBar: EditText
    private lateinit var homeSearch: EditText
    private lateinit var homePanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var shieldButton: Button
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(250, 250, 252)
        window.navigationBarColor = Color.rgb(250, 250, 252)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildBrowserUi()
        initializeAdblock()
    }

    private fun buildBrowserUi() {
        val ink = Color.rgb(38, 38, 48)
        val muted = Color.rgb(135, 135, 150)
        val accent = Color.rgb(120, 112, 190)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 252))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 18, 8)
        }
        val brand = TextView(this).apply {
            text = "M"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
        }
        val headerTitle = TextView(this).apply {
            text = "Google"
            textSize = 15f
            setTextColor(ink)
            gravity = Gravity.CENTER
        }
        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 22f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(12, 4, 4, 4)
            setOnClickListener { showSettings() }
        }
        addressBar = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(14, 4, 14, 4)
            setBackgroundColor(Color.rgb(242, 242, 247))
            imeOptions = EditorInfo.IME_ACTION_GO
            visibility = View.GONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        header.addView(brand, LinearLayout.LayoutParams(42, 44))
        header.addView(headerTitle, LinearLayout.LayoutParams(0, 44, 1f))
        header.addView(addressBar, LinearLayout.LayoutParams(0, 44, 1f))
        header.addView(menuButton, LinearLayout.LayoutParams(40, 44))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 0, 18, 6)
        }
        shieldButton = Button(this).apply {
            text = "Shield ON"
            textSize = 10f
            setOnClickListener {
                protectionEnabled = !protectionEnabled
                updateShieldUi()
            }
        }
        countText = TextView(this).apply {
            text = "0 blocked"
            textSize = 11f
            setTextColor(muted)
            setPadding(8, 0, 0, 0)
        }
        statusText = TextView(this).apply {
            text = "Preparing protection…"
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        controls.addView(shieldButton)
        controls.addView(countText)
        controls.addView(statusText, LinearLayout.LayoutParams(0, 40, 1f))

        val contentFrame = FrameLayout(this)
        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(30, 24, 30, 40)
            setBackgroundColor(Color.rgb(250, 250, 252))
        }
        val master = TextView(this).apply {
            text = "MASTER"
            textSize = 28f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(105, 105, 115))
            gravity = Gravity.CENTER
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 2, 10, 2)
            setBackgroundColor(Color.rgb(241, 241, 246))
        }
        val searchIcon = TextView(this).apply {
            text = "⌕"
            textSize = 25f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        homeSearch = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        val go = TextView(this).apply {
            text = "Go"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(10, 10, 6, 10)
            setOnClickListener { navigate(homeSearch.text.toString()) }
        }
        searchRow.addView(searchIcon, LinearLayout.LayoutParams(28, 48))
        searchRow.addView(homeSearch, LinearLayout.LayoutParams(0, 48, 1f))
        searchRow.addView(go, LinearLayout.LayoutParams(42, 48))
        homePanel.addView(master, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 54))
        val gap = View(this)
        homePanel.addView(gap, LinearLayout.LayoutParams(1, 14))
        homePanel.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52))

        browser = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
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
                        val decision = engine.checkNetworkRequest(request.url.toString(), view?.url ?: "", requestType)
                        if (decision.matched) {
                            val total = blockedCount.incrementAndGet()
                            mainHandler.post { countText.text = "$total blocked" }
                            WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Pointlook",
                                emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        } else null
                    } catch (_: Exception) { null }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                        addressBar.setText(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusText.text = "Protected browsing"
                }
            }
        }
        contentFrame.addView(browser, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(homePanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 7, 8, 10)
            setBackgroundColor(Color.rgb(250, 250, 252))
        }
        fun navButton(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
            setOnClickListener { action() }
        }
        bottom.addView(navButton("◉") { showHome() })
        bottom.addView(navButton("⌂") { showHome() })
        bottom.addView(navButton("↻") { if (browser.visibility == View.VISIBLE) browser.reload() })
        bottom.addView(navButton("▢") { showSettings() })

        root.addView(header)
        root.addView(controls)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        setContentView(root)

        // Android 15 enforces edge-to-edge for targetSdk 35. Keep app content
        // below the status bar and above the gesture/navigation area.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    topInset = insets.systemWindowInsetTop
                    bottomInset = insets.systemWindowInsetBottom
                }
            }
            view.setPadding(0, topInset, 0, bottomInset)
            insets
        }
        root.requestApplyInsets()
    }

    private fun navigate(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val target = when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.contains(" ") || !value.contains(".") ->
                "https://www.google.com/search?q=" + Uri.encode(value)
            else -> "https://$value"
        }
        homePanel.visibility = View.GONE
        browser.visibility = View.VISIBLE
        addressBar.visibility = View.VISIBLE
        addressBar.setText(target)
        browser.loadUrl(target)
    }

    private fun showHome() {
        browser.stopLoading()
        browser.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        addressBar.visibility = View.GONE
        homeSearch.setText("")
    }

    private fun showSettings() {
        val options = arrayOf(
            if (protectionEnabled) "AdBlock: ON" else "AdBlock: OFF",
            "Blocked requests: ${blockedCount.get()}",
            "Home"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        protectionEnabled = !protectionEnabled
                        updateShieldUi()
                    }
                    2 -> showHome()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

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

    private fun updateShieldUi() {
        shieldButton.text = if (protectionEnabled) "Shield ON" else "Shield OFF"
        shieldButton.setTextColor(if (protectionEnabled) Color.rgb(50, 150, 90) else Color.DKGRAY)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.visibility == View.VISIBLE && browser.canGoBack()) {
            browser.goBack()
        } else if (::homePanel.isInitialized && homePanel.visibility != View.VISIBLE) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        adblockEngine?.close()
        worker.shutdownNow()
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
    }
}
