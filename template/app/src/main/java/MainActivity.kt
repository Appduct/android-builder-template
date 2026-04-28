package %%PACKAGE_NAME%%

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var splashView: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var bottomNav: LinearLayout

    private val websiteUrl = "%%WEBSITE_URL%%"
    private val expiryTimestamp: Long = %%EXPIRY_TIMESTAMP%%L
    private var splashDismissed = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // Offline pre-warm cache state
    private var prewarmDone = false
    private var prewarmWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register file chooser result handler
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val resultUris: Array<Uri>? = if (data?.clipData != null) {
                    Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                } else {
                    data?.data?.let { arrayOf(it) }
                }
                fileUploadCallback?.onReceiveValue(resultUris ?: arrayOf())
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }

        // Dark mode
        val darkMode = %%DARK_MODE%%
        if (darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        // Screenshot control
        val allowScreenshot = %%ALLOW_SCREENSHOT%%
        if (!allowScreenshot) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Status bar / header color
        val headerColor = "%%HEADER_COLOR%%"
        if (headerColor.isNotEmpty() && headerColor != "default") {
            try {
                window.statusBarColor = Color.parseColor(headerColor)
            } catch (_: Exception) {}
        }

        setContentView(R.layout.activity_main)

        // Trial expiry check (0 = no expiry)
        if (expiryTimestamp > 0L && System.currentTimeMillis() > expiryTimestamp) {
            showExpiredScreen()
            return
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        splashView = findViewById(R.id.splashView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        bottomNav = findViewById(R.id.bottomNav)

        val retryButton = findViewById<View>(R.id.retryButton)
        retryButton.setOnClickListener {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            splashView.visibility = View.VISIBLE
            splashDismissed = false
            loadUrl()
        }

        // Feature flags
        val pullToRefreshEnabled = %%PULL_TO_REFRESH%%
        val bottomNavEnabled = %%BOTTOM_NAV%%
        val offlineModeEnabled = %%OFFLINE_MODE%%

        setupWebView(offlineModeEnabled)
        setupSwipeRefresh(pullToRefreshEnabled)
        setupBottomNav(bottomNavEnabled)

        splashView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE

        if (isNetworkAvailable()) {
            loadUrl()
        } else if (offlineModeEnabled) {
            // Try cached version
            webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
            loadUrl()
        } else {
            dismissSplash()
            showError("No internet connection. Please check your network and try again.")
        }
    }

    private fun dismissSplash() {
        if (splashDismissed) return
        splashDismissed = true
        webView.visibility = View.VISIBLE
        val fadeOut = AlphaAnimation(1f, 0f)
        fadeOut.duration = 400
        fadeOut.fillAfter = true
        splashView.startAnimation(fadeOut)
        Handler(Looper.getMainLooper()).postDelayed({ splashView.visibility = View.GONE }, 420)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(offlineMode: Boolean) {
        // Real-time compliance: clear stale caches, cookies, and service-worker storage on launch
        try {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().flush()
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            // Disable any service worker caching from the wrapped website
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
                })
            }
        } catch (_: Exception) {}

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            // Real-time mode: never serve from cache when online; offline mode falls back to cache
            cacheMode = if (offlineMode) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_NO_CACHE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.GONE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                dismissSplash()
                // Pre-warm offline cache once per launch when Offline Mode is on and we're online
                if (offlineMode && !prewarmDone && isNetworkAvailable()) {
                    prewarmDone = true
                    Handler(Looper.getMainLooper()).postDelayed({ prewarmOfflineCache() }, 1500)
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    if (offlineMode) {
                        // Try loading from cache as fallback
                        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                        webView.reload()
                    } else {
                        dismissSplash()
                        showError("Failed to load the page. Please check your connection and try again.")
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) { }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView?.visibility = View.GONE
                swipeRefresh.visibility = View.GONE
                bottomNav.visibility = View.GONE
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                swipeRefresh.visibility = View.VISIBLE
                if (%%BOTTOM_NAV%%) bottomNav.visibility = View.VISIBLE
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView = null
                fullscreenCallback = null
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun setupSwipeRefresh(enabled: Boolean) {
        swipeRefresh.isEnabled = enabled
        if (!enabled) return
        swipeRefresh.setColorSchemeColors(resources.getColor(R.color.colorPrimary, theme))
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) { webView.reload() }
            else { swipeRefresh.isRefreshing = false; showError("No internet connection.") }
        }
    }

    private fun setupBottomNav(enabled: Boolean) {
        bottomNav.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        findViewById<ImageButton>(R.id.navBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<ImageButton>(R.id.navForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<ImageButton>(R.id.navRefresh).setOnClickListener { webView.reload() }
        findViewById<ImageButton>(R.id.navHome).setOnClickListener { loadUrl() }
    }

    private fun loadUrl() {
        val headers = hashMapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0"
        )
        webView.loadUrl(websiteUrl, headers)
    }

    override fun onResume() {
        super.onResume()
        // Real-time: when user returns to the app, refresh to pick up latest website changes
        if (::webView.isInitialized && splashDismissed && isNetworkAvailable()) {
            try { webView.reload() } catch (_: Exception) {}
        }
    }

    /**
     * Offline pre-warm: harvest same-origin links from the current page, then silently
     * load the top N in a hidden WebView with default cache mode so the HTTP cache is
     * populated. Result: when the user goes offline later, those pages load from cache.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun prewarmOfflineCache() {
        try {
            val origin = Uri.parse(websiteUrl).let { "${it.scheme}://${it.host}" }
            val js = """
                (function() {
                  try {
                    var origin = location.origin;
                    var seen = {};
                    var out = [];
                    var anchors = document.querySelectorAll('a[href]');
                    for (var i = 0; i < anchors.length && out.length < 8; i++) {
                      var href = anchors[i].href;
                      if (!href) continue;
                      if (href.indexOf(origin) !== 0) continue;
                      if (href.indexOf('#') !== -1) href = href.split('#')[0];
                      if (href === location.href) continue;
                      if (seen[href]) continue;
                      seen[href] = 1;
                      out.push(href);
                    }
                    return JSON.stringify(out);
                  } catch (e) { return '[]'; }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { value ->
                val urls = mutableListOf<String>()
                try {
                    var raw = value ?: "[]"
                    // evaluateJavascript returns a JSON-encoded string; strip outer quotes & unescape
                    if (raw.startsWith("\"") && raw.endsWith("\"")) {
                        raw = raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                    // Naive parse: split on quoted entries
                    val regex = Regex("\"(https?://[^\"]+)\"")
                    regex.findAll(raw).forEach { urls.add(it.groupValues[1]) }
                } catch (_: Exception) {}
                if (urls.isEmpty()) return@evaluateJavascript
                runPrewarmQueue(urls.take(5))
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runPrewarmQueue(urls: List<String>) {
        if (urls.isEmpty()) return
        if (prewarmWebView == null) {
            prewarmWebView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = webView.settings.userAgentString
                visibility = View.GONE
            }
        }
        val pw = prewarmWebView ?: return
        val queue = urls.toMutableList()
        pw.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (queue.isNotEmpty()) {
                    val next = queue.removeAt(0)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { pw.loadUrl(next) } catch (_: Exception) {}
                    }, 800)
                } else {
                    try { pw.destroy() } catch (_: Exception) {}
                    prewarmWebView = null
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // Skip failed url, continue queue
                if (request?.isForMainFrame == true && queue.isNotEmpty()) {
                    val next = queue.removeAt(0)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { pw.loadUrl(next) } catch (_: Exception) {}
                    }, 500)
                }
            }
        }
        try { pw.loadUrl(queue.removeAt(0)) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { prewarmWebView?.destroy() } catch (_: Exception) {}
        prewarmWebView = null
        super.onDestroy()
    }

    private fun showError(message: String) {
        webView.visibility = View.GONE
        splashView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorText.text = message
        progressBar.visibility = View.GONE
    }
    private fun showExpiredScreen() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = android.view.Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        val title = TextView(this).apply {
            text = "Trial Expired"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val msg = TextView(this).apply {
            text = "Your 7-day free trial of this app has ended.\n\nPlease contact the app owner to upgrade and continue using this app."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 15f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(title)
        container.addView(msg)
        setContentView(container)
        window.statusBarColor = Color.parseColor("#0F172A")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (fullscreenView != null) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenContainer.removeAllViews()
            fullscreenContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            swipeRefresh.visibility = View.VISIBLE
            if (%%BOTTOM_NAV%%) bottomNav.visibility = View.VISIBLE
            fullscreenView = null
            fullscreenCallback = null
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            return
        }
        if (webView.canGoBack()) { webView.goBack() }
        else { @Suppress("DEPRECATION") super.onBackPressed() }
    }
}
