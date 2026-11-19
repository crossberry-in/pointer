package com.crossberry.pointlook

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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
    private lateinit var statusText: TextView
    private lateinit var shieldButton: Button
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 25)
        window.navigationBarColor = Color.rgb(12, 15, 25)
        buildBrowserUi()
        initializeAdblock()
        if (savedInstanceState == null) browser.loadUrl(HOME_URL)
    }

    private fun buildBrowserUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 15, 25))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 8)
        }
        val brand = TextView(this).apply {
            text = "P"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(170, 160, 255))
            gravity = Gravity.CENTER
            setPadding(8, 0, 12, 0)
        }
        addressBar = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(14, 4, 14, 4)
            setBackgroundColor(Color.rgb(35, 40, 56))
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    navigate(addressBar.text.toString())
                    true
                } else false
            }
        }
        header.addView(brand, LinearLayout.LayoutParams(42, 48))
        header.addView(addressBar, LinearLayout.LayoutParams(0, 48, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 8)
        }
        shieldButton = Button(this).apply {
            text = "Shield ON"
            textSize = 11f
            setOnClickListener {
                protectionEnabled = !protectionEnabled
                updateShieldUi()
            }
        }
        countText = TextView(this).apply {
            text = "0 blocked"
            textSize = 12f
            setTextColor(Color.rgb(180, 190, 210))
            setPadding(12, 0, 0, 0)
        }
        statusText = TextView(this).apply {
            text = "Preparing protection…"
            textSize = 11f
            setTextColor(Color.rgb(160, 170, 190))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        controls.addView(shieldButton)
        controls.addView(countText)
        controls.addView(statusText, LinearLayout.LayoutParams(0, 42, 1f))

        browser = WebView(this).apply {
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
                        val decision = engine.checkNetworkRequest(
                            request.url.toString(),
                            view?.url ?: "",
                            requestType
                        )
                        if (decision.matched) {
                            val total = blockedCount.incrementAndGet()
                            mainHandler.post { countText.text = "$total blocked" }
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

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 6, 8, 8)
        }
        fun navButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            setOnClickListener { action() }
        }
        bottom.addView(navButton("Back") { if (browser.canGoBack()) browser.goBack() })
        bottom.addView(navButton("Forward") { if (browser.canGoForward()) browser.goForward() })
        bottom.addView(navButton("Home") { browser.loadUrl(HOME_URL) })
        bottom.addView(navButton("Reload") { browser.reload() })

        root.addView(header)
        root.addView(controls)
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        setContentView(root)
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
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        adblockEngine?.close()
        worker.shutdownNow()
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://www.google.com"
        private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
    }
}
