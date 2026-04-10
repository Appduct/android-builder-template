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

    private val websiteUrl = "%%WEBSITE_URL%%"
    private var splashDismissed = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

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

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        splashView = findViewById(R.id.splashView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        val retryButton = findViewById<View>(R.id.retryButton)
        retryButton.setOnClickListener {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            splashView.visibility = View.VISIBLE
            splashDismissed = false
            loadUrl()
        }

        setupWebView()
        setupSwipeRefresh()

        splashView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE

        if (isNetworkAvailable()) {
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
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
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
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    dismissSplash()
                    showError("Failed to load the page. Please check your connection and try again.")
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
                // Cancel any existing callback
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
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView = null
                fullscreenCallback = null
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(resources.getColor(R.color.colorPrimary, theme))
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) { webView.reload() }
            else { swipeRefresh.isRefreshing = false; showError("No internet connection.") }
        }
    }

    private fun loadUrl() { webView.loadUrl(websiteUrl) }
    private fun showError(message: String) {
        webView.visibility = View.GONE
        splashView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorText.text = message
        progressBar.visibility = View.GONE
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
            fullscreenView = null
            fullscreenCallback = null
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            return
        }
        if (webView.canGoBack()) { webView.goBack() }
        else { @Suppress("DEPRECATION") super.onBackPressed() }
    }
}
