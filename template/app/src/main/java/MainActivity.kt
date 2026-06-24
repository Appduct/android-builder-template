package %%PACKAGE_NAME%%

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var splashView: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private var landingView: androidx.recyclerview.widget.RecyclerView? = null
    private var landingContainer: View? = null
    private var landingIntroView: TextView? = null

    // Multi-link landing page + custom bottom-nav tabs (templated at build time)
    private val landingEnabled: Boolean = %%LANDING_ENABLED%%
    private val landingLayoutPref: String = "%%LANDING_LAYOUT%%"
    private val navTabsEnabled: Boolean = %%NAV_TABS_ENABLED%%
    // When false, any external-domain http(s) link is opened in the system browser
    // instead of inside this WebView.
    private val externalLinksInApp: Boolean = %%EXTERNAL_LINKS_IN_APP%%
    // Require fingerprint / face / device credential before the app contents are shown.
    private val biometricEnabled: Boolean = %%BIOMETRIC_ENABLED%%
    private var biometricAuthPassed: Boolean = false


    private val websiteUrl = "%%WEBSITE_URL%%"
    private val expiryTimestamp: Long = %%EXPIRY_TIMESTAMP%%L
    private var splashDismissed = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var landingBaselineIndex = -1

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    // Set true while the system file/camera picker is in the foreground so onResume()
    // does NOT reload the page (which would discard the in-progress form/upload state
    // and bounce the user back to the dashboard).
    private var isPickingFile: Boolean = false
    private var pickerLaunchedAt: Long = 0L

    // Pending geolocation prompt state — held while we ask the OS for location permission
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // Offline pre-warm cache state
    private var prewarmDone = false
    private var prewarmWebView: WebView? = null
    private var triedCacheFallback = false

    // Track when the app was last paused. We refresh on resume after even a short absence
    // so users always see the latest content when returning to the app — without waiting
    // for a logout/login. The native `beforeunload` dialog is suppressed in WebChromeClient
    // so this never produces a "Confirm Navigation" prompt.
    private var lastPauseTime: Long = 0L
    // Only refresh on resume after long absences. Short screen-off / app-switch cycles
    // must NOT reload (would disrupt videos, forms, uploads, ongoing reads).
    private val resumeReloadThresholdMs: Long = 5 * 60 * 1000L // 5 minutes

    // Push-style sync: poll backend for a "reload" signal so content updates immediately
    // when the website publishes new data — no need to wait for the user to background/resume.
    private val syncProjectId = "%%PROJECT_ID%%"
    private val syncSupabaseUrl = "%%SUPABASE_URL%%"
    private val syncAnonKey = "%%SUPABASE_ANON_KEY%%"
    private val syncPollIntervalMs: Long = 5000L
    private var lastSignalAt: String = ""
    private var syncSignalInitialized: Boolean = false
    private var lastSyncReloadAt: Long = 0L
    private var syncSocket: WebSocket? = null
    private var syncRefCounter: Int = 1
    private val syncHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    }
    private val syncHeartbeatRunnable = object : Runnable {
        override fun run() {
            syncSocket?.send("""{"topic":"phoenix","event":"heartbeat","payload":{},"ref":"${nextSyncRef()}"}""")
            syncHandler.postDelayed(this, 25000L)
        }
    }
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            checkSyncSignal()
            syncHandler.postDelayed(this, syncPollIntervalMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register file chooser result handler
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                if (result.resultCode == Activity.RESULT_OK) {
                    val data = result.data
                    val resultUris: Array<Uri>? = if (data?.clipData != null) {
                        Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                    } else {
                        data?.data?.let { arrayOf(it) }
                    }
                    fileUploadCallback?.onReceiveValue(resultUris ?: arrayOf())
                } else {
                    // User cancelled — must signal cancellation to the WebView so the
                    // <input type="file"> control resets cleanly. Never throw.
                    fileUploadCallback?.onReceiveValue(null)
                }
            } catch (e: Exception) {
                // Surface the real error to the page instead of silently dropping it,
                // and keep the WebView responsive.
                try { fileUploadCallback?.onReceiveValue(null) } catch (_: Exception) {}
                try {
                    android.widget.Toast.makeText(
                        this,
                        "Upload error: ${e.message ?: e.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            } finally {
                fileUploadCallback = null
                // Clear the picker flag on the next tick so onResume (which fires
                // immediately after the picker closes) skips the resume-reload.
                isPickingFile = false
                pickerLaunchedAt = 0L
            }
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

        // Keep the screen on while the app is in the foreground — videos, long reads, and
        // forms should never put the device to sleep mid-activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        landingView = findViewById(R.id.landingView)
        landingContainer = findViewById(R.id.landingContainer)
        landingIntroView = findViewById(R.id.landingIntro)

        val retryButton = findViewById<View>(R.id.retryButton)
        retryButton.setOnClickListener {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            splashView.visibility = View.VISIBLE
            splashDismissed = false
            triedCacheFallback = false
            loadUrl()
        }

        // Feature flags
        val pullToRefreshEnabled = %%PULL_TO_REFRESH%%
        val bottomNavEnabled = %%BOTTOM_NAV%%
        val offlineModeEnabled = %%OFFLINE_MODE%%

        setupWebView(offlineModeEnabled)
        setupSwipeRefresh(pullToRefreshEnabled)
        setupBottomNav(bottomNavEnabled)
        // NOTE: We intentionally do NOT pre-request READ_MEDIA_IMAGES/VIDEO here.
        // The WebView file chooser uses Storage Access Framework (ACTION_GET_CONTENT),
        // which does not require those runtime permissions. Pre-requesting them on
        // Android 14+ causes the "Allow access to more photos and videos" partial-access
        // dialog to re-appear on every launch and screen flicker.

        // Show splash while we (optionally) wait for biometric authentication.
        splashView.visibility = View.VISIBLE
        if (!landingEnabled) webView.visibility = View.INVISIBLE

        runBiometricGate {
            if (landingEnabled && setupLanding()) {
                // Landing shown — skip auto-loading the website URL. Tile clicks load it.
            } else {
                splashView.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE

                if (isNetworkAvailable()) {
                    loadUrl()
                } else if (offlineModeEnabled) {
                    // Try cached version
                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                    triedCacheFallback = true
                    loadUrl()
                } else {
                    dismissSplash()
                    showError("Please check your internet connection and try again.")
                }
            }


            syncHandler.removeCallbacks(syncRunnable)
            syncHandler.post(syncRunnable)
            startSyncRealtime()

            // %%KEEP_ALIVE_START%%
        }
    }

    /**
     * Gates app startup behind a biometric (or device-credential) prompt when the
     * project has Biometric Login enabled. If the device has no biometric/credential
     * enrolled, or the feature is off, the gate is bypassed silently.
     */
    private fun runBiometricGate(onSuccess: () -> Unit) {
        if (biometricAuthPassed || !biometricEnabled) { onSuccess(); return }
        try {
            val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
            val bm = androidx.biometric.BiometricManager.from(this)
            val can = bm.canAuthenticate(authenticators)
            if (can != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                // No fingerprint/face enrolled — fall through silently per spec.
                biometricAuthPassed = true
                onSuccess()
                return
            }
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
            val appLabel = try { applicationInfo.loadLabel(packageManager).toString() } catch (_: Throwable) { "App" }
            val prompt = androidx.biometric.BiometricPrompt(this, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        biometricAuthPassed = true
                        onSuccess()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        when (errorCode) {
                            androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED,
                            androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            androidx.biometric.BiometricPrompt.ERROR_CANCELED -> finish()
                            androidx.biometric.BiometricPrompt.ERROR_LOCKOUT,
                            androidx.biometric.BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> finish()
                            else -> {
                                // Hardware/unknown error — don't lock the user out forever.
                                biometricAuthPassed = true
                                onSuccess()
                            }
                        }
                    }
                })
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock $appLabel")
                .setSubtitle("Authenticate to continue")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Cancel")
                .build()
            prompt.authenticate(info)
        } catch (_: Throwable) {
            biometricAuthPassed = true
            onSuccess()
        }
    }



    /**
     * Detects whether the user has something in flight that a reload would disrupt:
     * playing video/audio, fullscreen media, dirty form input, focused contentEditable,
     * or an in-progress file picker. The result is delivered async on the UI thread.
     */
    private fun isUserBusy(callback: (Boolean) -> Unit) {
        if (isPickingFile || fullscreenView != null) { callback(true); return }
        if (!::webView.isInitialized) { callback(false); return }
        val js = """(function(){try{
            var m=document.querySelectorAll('video,audio');
            for(var i=0;i<m.length;i++){if(!m[i].paused && !m[i].ended && m[i].currentTime>0)return 'busy';}
            if(document.fullscreenElement)return 'busy';
            var ae=document.activeElement;
            if(ae){
              var t=(ae.tagName||'').toLowerCase();
              if(t==='input'||t==='textarea'||t==='select')return 'busy';
              if(ae.isContentEditable)return 'busy';
            }
            var forms=document.querySelectorAll('form');
            for(var j=0;j<forms.length;j++){
              var els=forms[j].elements||[];
              for(var k=0;k<els.length;k++){
                var e=els[k];var ty=(e.type||'').toLowerCase();
                if(ty==='hidden'||ty==='submit'||ty==='button')continue;
                if(ty==='checkbox'||ty==='radio'){if(e.checked!==e.defaultChecked)return 'busy';}
                else if(e.value!=null && e.defaultValue!=null && e.value!==e.defaultValue && String(e.value).length>0)return 'busy';
              }
            }
            return 'idle';
        }catch(e){return 'idle';}})();""".trimIndent()
        try {
            webView.evaluateJavascript(js) { value ->
                val v = (value ?: "").replace("\"", "")
                callback(v == "busy")
            }
        } catch (_: Exception) { callback(false) }
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
        // Enable cookies (1st-party + 3rd-party) and persist them across sessions
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            // Real-time compliance: clear only the HTTP cache (not cookies/storage) so logins persist
            webView.clearCache(true)
            // Disable any service worker caching from the wrapped website so content stays live
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode = WebSettings.LOAD_NO_CACHE
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
            // Support target=_blank / window.open so we can route share-link popups to the OS
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        // Expose a native share bridge so navigator.share() opens the OS share sheet
        try { webView.addJavascriptInterface(NativeShareBridge(), "AndroidShareBridge") } catch (_: Exception) {}


        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank() && handleExternalUrl(url)) {
                    try { view?.stopLoading() } catch (_: Exception) {}
                    try { view?.loadUrl("about:blank") } catch (_: Exception) {}
                    return
                }
                progressBar.visibility = View.GONE
                injectNativeShareShim(view)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                injectNativeShareShim(view)
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                resetLandingRootHistoryIfNeeded(url)
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
                    if (offlineMode && !triedCacheFallback) {
                        // Try loading from cache as fallback
                        triedCacheFallback = true
                        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                        webView.reload()
                    } else {
                        dismissSplash()
                        showError("Please check your internet connection and try again.")
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (handleExternalUrl(url)) {
                    try { view?.stopLoading() } catch (_: Exception) {}
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                if (handleExternalUrl(url)) {
                    try { view?.stopLoading() } catch (_: Exception) {}
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (request.isForMainFrame && isBlockedExternalPage(url)) {
                    runOnUiThread {
                        try { view?.stopLoading() } catch (_: Exception) {}
                        try { handleExternalUrl(url) } catch (_: Exception) {}
                    }
                    return emptyWebResponse()
                }
                return null
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (!url.isNullOrBlank() && isBlockedExternalPage(url)) {
                    runOnUiThread {
                        try { view?.stopLoading() } catch (_: Exception) {}
                        try { handleExternalUrl(url) } catch (_: Exception) {}
                    }
                    return emptyWebResponse()
                }
                return null
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

                // Build base content intent honoring the website's accept attribute
                val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                val mimeType = when {
                    acceptTypes.isEmpty() -> "*/*"
                    acceptTypes.size == 1 && acceptTypes[0].startsWith(".") -> "*/*"
                    acceptTypes.size == 1 -> acceptTypes[0]
                    else -> "*/*"
                }
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType
                    if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    if (acceptTypes.size > 1) {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                    }
                }

                // Wrap in a chooser so the user always gets a clean picker
                val chooser = Intent.createChooser(contentIntent, "Select file")

                // Optionally offer camera capture when site asks for image/video
                val extras = mutableListOf<Intent>()
                if (acceptTypes.any { it.startsWith("image/") } || mimeType == "*/*") {
                    Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).also {
                        if (it.resolveActivity(packageManager) != null) extras.add(it)
                    }
                }
                if (acceptTypes.any { it.startsWith("video/") }) {
                    Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).also {
                        if (it.resolveActivity(packageManager) != null) extras.add(it)
                    }
                }
                if (extras.isNotEmpty()) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                }

                try {
                    isPickingFile = true
                    pickerLaunchedAt = System.currentTimeMillis()
                    fileChooserLauncher.launch(chooser)
                } catch (e: Exception) {
                    isPickingFile = false
                    pickerLaunchedAt = 0L
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    try {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Could not open file picker: ${e.message ?: e.javaClass.simpleName}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {}
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                // Grant camera/microphone access when the wrapped website requests it (getUserMedia)
                runOnUiThread {
                    try { request?.grant(request.resources) } catch (_: Exception) { request?.deny() }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Grant the WebView's per-origin permission, then make sure the
                // OS-level location permission is granted too — otherwise the
                // wrapped site reports "Location Unavailable".
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION
                )
                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    coarse == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    callback?.invoke(origin, true, false)
                    pendingGeoOrigin = null
                    pendingGeoCallback = null
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        2002
                    )
                }
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

            // Suppress the native "Confirm Navigation" dialog that the WebView would otherwise
            // show whenever the wrapped page (or our own reload) triggers a JS `beforeunload`
            // handler. Many sites attach beforeunload for analytics/forms, and combined with our
            // resume-reload it caused a popup on every screen-off/on cycle. Silently allow the
            // navigation so the user is never prompted inside the app shell.
            override fun onJsBeforeUnload(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }

            // Handle window.open / target=_blank — most sites use these for share popups
            // (WhatsApp/Twitter/Facebook intent links). Route them to the OS instead of
            // opening an inner WebView.
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                try {
                    val hitUrl = view?.hitTestResult?.extra
                    if (!hitUrl.isNullOrBlank()) {
                        // If toggle is OFF, always push to the system browser/native app.
                        // If toggle is ON, only leave the app for share/non-http URLs;
                        // plain http(s) links load in the main WebView.
                        if (!externalLinksInApp) {
                            handleExternalUrl(hitUrl, forceExternal = true)
                            return false
                        }
                        if (handleExternalUrl(hitUrl, forceExternal = false)) return false
                        try { webView.loadUrl(hitUrl) } catch (_: Exception) {}
                        return false
                    }
                } catch (_: Exception) {}
                // Fallback for window.open() with no hitTestResult — wait for first
                // navigation in a throwaway WebView, then route per toggle.
                val tempWebView = WebView(this@MainActivity)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return true
                        if (!externalLinksInApp) {
                            handleExternalUrl(url, forceExternal = true)
                        } else if (!handleExternalUrl(url, forceExternal = false)) {
                            try { webView.loadUrl(url) } catch (_: Exception) {}
                        }
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = tempWebView
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    /**
     * Decide whether a URL should leave the WebView (open in a native app / browser)
     * or be loaded in-place. Returns true if it was handled externally and the WebView
     * should NOT load it.
     */
    private fun handleExternalUrl(url: String, forceExternal: Boolean = false): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("intent:")) return openIntentUri(url)
        if (isWhatsAppUrl(url)) return openWhatsAppUrl(url)
        if (isKnownExternalAppUrl(url)) return openExternalAppUrl(url)

        // Always external: non-http schemes (mailto, tel, sms, whatsapp, intent, market, etc.)
        val nonHttp = !lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("about:") && !lower.startsWith("javascript:")
        // Known share / external-app HTTPS URLs
        val shareHosts = listOf(
            "wa.me", "api.whatsapp.com/send", "web.whatsapp.com/send", "whatsapp.com/send", "chat.whatsapp.com",
            "t.me/", "telegram.me/",
            "twitter.com/intent", "x.com/intent",
            "facebook.com/sharer", "facebook.com/dialog/share", "m.facebook.com/sharer",
            "linkedin.com/sharing", "linkedin.com/shareArticle",
            "pinterest.com/pin/create", "reddit.com/submit",
            "mail.google.com/mail/?view=cm", "messenger.com/share"
        )
        val isShareUrl = shareHosts.any { lower.contains(it) }

        // Per-app preference: when external links should open in the system browser,
        // any http(s) URL whose host differs from the wrapped website's host is sent out.
        val isExternalHostHttp = !externalLinksInApp &&
            (lower.startsWith("http://") || lower.startsWith("https://")) &&
            isExternalHost(url)

        if (nonHttp || isShareUrl || forceExternal || isExternalHostHttp) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // No app can handle it — fall back to loading in WebView for http(s)
                if (!nonHttp) return false
            }
            return true
        }
        return false
    }

    /** Returns true when [url]'s host is different from the wrapped website's host. */
    private fun isExternalHost(url: String): Boolean {
        return try {
            val target = (Uri.parse(url).host ?: "").lowercase().removePrefix("www.")
            val base = (Uri.parse(websiteUrl).host ?: "").lowercase().removePrefix("www.")
            if (target.isEmpty() || base.isEmpty()) false
            else target != base && !target.endsWith(".$base") && !base.endsWith(".$target")
        } catch (_: Exception) { false }
    }

    private fun isBlockedExternalPage(url: String): Boolean {
        return isWhatsAppUrl(url) || isKnownExternalAppUrl(url)
    }

    private fun emptyWebResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream("".toByteArray())
        )
    }

    private fun isWhatsAppUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = (uri.scheme ?: "").lowercase()
            val host = (uri.host ?: "").lowercase().removePrefix("www.")
            scheme == "whatsapp" || host == "wa.me" || host == "api.whatsapp.com" ||
                host == "web.whatsapp.com" || host == "whatsapp.com" || host == "chat.whatsapp.com"
        } catch (_: Exception) {
            url.lowercase().contains("whatsapp") || url.lowercase().contains("wa.me/")
        }
    }

    private fun isKnownExternalAppUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = (uri.scheme ?: "").lowercase()
            val host = (uri.host ?: "").lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
            if (scheme in setOf("fb", "fb-messenger", "tg", "twitter", "x", "linkedin", "pinterest", "reddit")) return true
            when (host) {
                "t.me", "telegram.me", "telegram.dog", "twitter.com", "x.com",
                "facebook.com", "messenger.com", "m.me", "linkedin.com",
                "pinterest.com", "reddit.com" -> true
                else -> false
            }
        } catch (_: Exception) {
            val lower = url.lowercase()
            listOf("t.me/", "telegram.me/", "twitter.com/intent", "x.com/intent", "facebook.com/sharer", "messenger.com", "linkedin.com/sharing", "pinterest.com/pin/create", "reddit.com/submit").any { lower.contains(it) }
        }
    }

    private fun openExternalAppUrl(originalUrl: String): Boolean {
        val uri = try { Uri.parse(originalUrl) } catch (_: Exception) { null }
        val lower = originalUrl.lowercase()
        val candidates = mutableListOf<Intent>()
        fun addView(url: String, pkg: String? = null) {
            candidates.add(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (!pkg.isNullOrBlank()) setPackage(pkg) })
        }

        when {
            lower.contains("t.me/") || lower.contains("telegram.me/") || lower.startsWith("tg:") -> {
                val text = uri?.getQueryParameter("text") ?: uri?.getQueryParameter("url") ?: originalUrl
                addView(originalUrl, "org.telegram.messenger")
                addView("tg://msg?text=${Uri.encode(text)}", "org.telegram.messenger")
                addView("tg://resolve?domain=${Uri.encode(uri?.lastPathSegment ?: "")}", "org.telegram.messenger")
            }
            lower.contains("twitter.com/intent") || lower.contains("x.com/intent") || lower.startsWith("twitter:") || lower.startsWith("x:") -> {
                val text = listOfNotNull(uri?.getQueryParameter("text"), uri?.getQueryParameter("url")).joinToString(" ").ifBlank { originalUrl }
                addView("twitter://post?message=${Uri.encode(text)}", "com.twitter.android")
                addView(originalUrl, "com.twitter.android")
            }
            lower.contains("facebook.com/sharer") || lower.contains("facebook.com/dialog/share") || lower.startsWith("fb:") -> {
                // Facebook's app no longer accepts fb://facewebmodal or the https sharer URL
                // routed into com.facebook.katana — both open to a blank screen. The reliable
                // path is ACTION_SEND to the FB app (some versions accept it), then fall back
                // to opening sharer.php in an EXTERNAL browser (not the FB app), where
                // Facebook's web share dialog renders correctly.
                val shareUrl = uri?.getQueryParameter("u") ?: uri?.getQueryParameter("href") ?: originalUrl
                val sharerHttps = if (lower.startsWith("http")) originalUrl
                    else "https://www.facebook.com/sharer/sharer.php?u=${Uri.encode(shareUrl)}"
                candidates.add(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareUrl)
                    setPackage("com.facebook.katana")
                })
                // Force the sharer URL into a real browser, bypassing this WebView and the FB app.
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(sharerHttps)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    // Exclude the FB app explicitly so it can't grab the intent again.
                }
                candidates.add(browserIntent)
            }
            lower.contains("messenger.com") || lower.contains("m.me/") || lower.startsWith("fb-messenger:") -> {
                addView(originalUrl, "com.facebook.orca")
                addView(originalUrl)
            }
            lower.contains("linkedin.com/sharing") || lower.contains("linkedin.com/sharearticle") || lower.startsWith("linkedin:") -> {
                addView(originalUrl, "com.linkedin.android")
                addView(originalUrl)
            }
            lower.contains("pinterest.com/pin/create") || lower.startsWith("pinterest:") -> {
                addView(originalUrl, "com.pinterest")
                addView(originalUrl)
            }
            lower.contains("reddit.com/submit") || lower.startsWith("reddit:") -> {
                addView(originalUrl, "com.reddit.frontpage")
                addView(originalUrl)
            }
            else -> addView(originalUrl)
        }

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }
        return true
    }

    private fun openWhatsAppUrl(originalUrl: String): Boolean {
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        val candidates = mutableListOf<Intent>()
        val directUrl = toWhatsAppDeepLink(originalUrl)
        val sendText = extractWhatsAppText(originalUrl)

        for (pkg in packages) {
            candidates.add(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)).setPackage(pkg))
            candidates.add(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sendText)
                setPackage(pkg)
            })
        }
        candidates.add(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?text=${Uri.encode(sendText)}")))
        candidates.add(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }

        // WhatsApp-specific URLs must never fall back into this app's WebView. If the app
        // is unavailable, leave the app via Play Store/browser instead of showing the
        // api.whatsapp.com "download WhatsApp" page inside the wrapper.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
        return true
    }

    private fun extractWhatsAppText(originalUrl: String): String {
        return try {
            val uri = Uri.parse(originalUrl)
            val text = uri.getQueryParameter("text")
            if (!text.isNullOrBlank()) text else websiteUrl
        } catch (_: Exception) {
            websiteUrl
        }
    }

    private fun toWhatsAppDeepLink(originalUrl: String): String {
        return try {
            val uri = Uri.parse(originalUrl)
            if ((uri.scheme ?: "").lowercase() == "whatsapp") return originalUrl
            val host = (uri.host ?: "").lowercase().removePrefix("www.")
            val builder = Uri.Builder().scheme("whatsapp").authority("send")
            val phone = when {
                !uri.getQueryParameter("phone").isNullOrBlank() -> uri.getQueryParameter("phone")
                host == "wa.me" && uri.pathSegments.isNotEmpty() -> uri.pathSegments.firstOrNull()
                else -> null
            }?.filter { it.isDigit() }
            val text = uri.getQueryParameter("text")
            if (!phone.isNullOrBlank()) builder.appendQueryParameter("phone", phone)
            if (!text.isNullOrBlank()) builder.appendQueryParameter("text", text)
            builder.build().toString()
        } catch (_: Exception) {
            originalUrl
        }
    }

    private fun openIntentUri(url: String): Boolean {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                removeCategory(Intent.CATEGORY_BROWSABLE)
                removeExtra("browser_fallback_url")
            }
            startActivity(intent)
        } catch (_: Exception) {}
        return true
    }


    private fun setupSwipeRefresh(enabled: Boolean) {
        swipeRefresh.isEnabled = enabled
        if (!enabled) return
        swipeRefresh.setColorSchemeColors(resources.getColor(R.color.colorPrimary, theme))
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) { loadFreshWebsite() }
            else { swipeRefresh.isRefreshing = false; showError("Please check your internet connection and try again.") }
        }
    }

    private fun setupBottomNav(enabled: Boolean) {
        // When custom nav tabs are configured, replace the default 4-button nav
        // with user-defined destinations.
        if (navTabsEnabled && setupCustomNavTabs()) return

        bottomNav.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        findViewById<ImageButton>(R.id.navBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<ImageButton>(R.id.navForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<ImageButton>(R.id.navRefresh).setOnClickListener { loadFreshWebsite() }
        findViewById<ImageButton>(R.id.navHome).setOnClickListener {
            if (landingEnabled && landingView != null) showLanding()
            else loadUrl()
        }
    }

    /** Returns true when custom tabs were rendered (and the default nav should NOT run). */
    private fun setupCustomNavTabs(): Boolean {
        val items = readJsonAssetArray("nav_tabs.json").take(5)
        if (items.isEmpty()) { bottomNav.visibility = View.GONE; return true }
        bottomNav.removeAllViews()
        bottomNav.orientation = LinearLayout.HORIZONTAL
        bottomNav.visibility = View.VISIBLE
        for (item in items) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                isFocusable = true
            }
            val iconView = android.widget.ImageView(this).apply {
                setImageResource(navIconRes(item["icon"] ?: "home"))
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    (24 * resources.displayMetrics.density).toInt(),
                    (24 * resources.displayMetrics.density).toInt()
                )
            }
            val label = TextView(this).apply {
                text = item["label"] ?: ""
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            container.addView(iconView)
            container.addView(label)
            val url = item["url"] ?: ""
            container.setOnClickListener {
                if (url.isNotEmpty()) {
                    showWebViewFromLanding()
                    webView.loadUrl(url)
                }
            }
            bottomNav.addView(container)
        }
        return true
    }

    private fun navIconRes(name: String): Int = when (name) {
        "search" -> android.R.drawable.ic_menu_search
        "menu" -> android.R.drawable.ic_menu_sort_by_size
        "info" -> android.R.drawable.ic_dialog_info
        "cart" -> android.R.drawable.ic_menu_add
        "user" -> android.R.drawable.ic_menu_myplaces
        else -> android.R.drawable.ic_menu_revert
    }

    /** Initialise the native landing page. Returns true when shown. */
    private fun setupLanding(): Boolean {
        val rv = landingView ?: return false
        val container = landingContainer ?: return false
        val items = readJsonAssetArray("multi_links.json")
        if (items.isEmpty()) return false
        val lm = when (landingLayoutPref) {
            "grid3" -> androidx.recyclerview.widget.GridLayoutManager(this, 3)
            "list" -> androidx.recyclerview.widget.LinearLayoutManager(this)
            else -> androidx.recyclerview.widget.GridLayoutManager(this, 2)
        }
        rv.layoutManager = lm
        rv.adapter = LandingAdapter(this, items) { url ->
            showWebViewFromLanding()
            webView.loadUrl(url)
        }

        // Optional intro/description text loaded from assets so we don't have to
        // escape multi-line / quoted content through sed at build time.
        val intro = readAssetText("landing_intro.txt")
        landingIntroView?.let { tv ->
            if (intro.isNotBlank()) { tv.text = intro; tv.visibility = View.VISIBLE }
            else { tv.visibility = View.GONE }
        }

        // Hide webview but KEEP the splash showing — it should still play the
        // user's uploaded splash image first, then fade into the landing page.
        swipeRefresh.visibility = View.GONE
        webView.visibility = View.GONE
        container.visibility = View.VISIBLE
        splashView.visibility = View.VISIBLE
        splashDismissed = false
        Handler(Looper.getMainLooper()).postDelayed({
            if (!splashDismissed) {
                splashDismissed = true
                val fadeOut = AlphaAnimation(1f, 0f)
                fadeOut.duration = 400
                fadeOut.fillAfter = true
                splashView.startAnimation(fadeOut)
                Handler(Looper.getMainLooper()).postDelayed({ splashView.visibility = View.GONE }, 420)
            }
        }, 3000)
        return true
    }

    private fun showWebViewFromLanding() {
        // Capture the history index BEFORE loading the linked URL so we can later
        // tell whether the user is still on the first linked page (back -> landing)
        // or has navigated deeper inside it (back -> webView.goBack()).
        landingBaselineIndex = try { webView.copyBackForwardList().currentIndex } catch (_: Exception) { -1 }
        landingContainer?.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
    }

    private fun showLanding(): Boolean {
        val container = landingContainer ?: return false
        try { webView.stopLoading(); webView.loadUrl("about:blank") } catch (_: Exception) {}
        swipeRefresh.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.GONE
        container.visibility = View.VISIBLE
        return true
    }

    private fun resetLandingRootHistoryIfNeeded(url: String?) {
        // Kept as a no-op to preserve existing call sites. History is now tracked
        // via landingBaselineIndex captured in showWebViewFromLanding().
    }

    private fun handleLandingBack(): Boolean {
        if (!landingEnabled || landingContainer?.visibility == View.VISIBLE) return false
        val index = try { webView.copyBackForwardList().currentIndex } catch (_: Exception) { 0 }
        // The first linked page sits at landingBaselineIndex + 1. Anything beyond
        // that means the user navigated deeper and should use normal back behavior.
        if (index > landingBaselineIndex + 1 && webView.canGoBack()) {
            webView.goBack()
        } else {
            showLanding()
        }
        return true
    }

    private fun readAssetText(name: String): String {
        return try { assets.open(name).bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
    }

    private fun readJsonAssetArray(name: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        try {
            val text = assets.open(name).bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val m = mutableMapOf<String, String>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = o.optString(k, "")
                }
                out.add(m)
            }
        } catch (_: Exception) {}
        return out
    }


    private fun loadUrl() {
        val headers = hashMapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0"
        )
        webView.loadUrl(websiteUrl, headers)
    }

    private fun loadFreshWebsite() {
        try {
            webView.stopLoading()
            webView.clearCache(true)
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            val currentUrl = webView.url ?: websiteUrl
            val parsed = Uri.parse(currentUrl)
            val builder = parsed.buildUpon().clearQuery()
            for (name in parsed.queryParameterNames) {
                if (name != "app_sync") {
                    parsed.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
                }
            }
            val freshUrl = builder.appendQueryParameter("app_sync", System.currentTimeMillis().toString()).build().toString()
            val headers = hashMapOf(
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Pragma" to "no-cache",
                "Expires" to "0"
            )
            webView.loadUrl(freshUrl, headers)
        } catch (_: Exception) {
            try { webView.reload() } catch (_: Exception) {}
        }
    }

    private fun reloadFromSyncSignal() {
        // Never reload while the user is interacting with the system file/camera
        // picker — it would discard the in-progress upload.
        if (isPickingFile) return
        val now = System.currentTimeMillis()
        if (now - lastSyncReloadAt < 1500L) return
        syncHandler.post {
            isUserBusy { busy ->
                // Skip reload while user is mid-activity (playing media, typing in a form,
                // fullscreen, etc.). The next sync poll will retry.
                if (busy) return@isUserBusy
                lastSyncReloadAt = System.currentTimeMillis()
                try {
                    if (::webView.isInitialized && isNetworkAvailable()) loadFreshWebsite()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If we're returning from the system file/camera picker, do NOT reload the
        // page — that would discard the in-progress upload, the form state, and
        // bounce the user back to whatever route the SPA defaults to (often the
        // dashboard). The fileChooserLauncher callback fires right after this and
        // delivers the picked URIs to the WebView's <input type="file">.
        if (isPickingFile) {
            // Restart sync polling but skip the reload-on-resume path.
            syncHandler.removeCallbacks(syncRunnable)
            syncHandler.post(syncRunnable)
            startSyncRealtime()
            return
        }
        if (::webView.isInitialized && splashDismissed && isNetworkAvailable()) {
            val awayMs = if (lastPauseTime == 0L) 0L else System.currentTimeMillis() - lastPauseTime
            try {
                webView.evaluateJavascript(
                    "(function(){try{" +
                    "Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){return 'visible'}});" +
                    "Object.defineProperty(document,'hidden',{configurable:true,get:function(){return false}});" +
                    "document.dispatchEvent(new Event('visibilitychange'));" +
                    "window.dispatchEvent(new Event('focus'));" +
                    "}catch(e){}})();",
                    null
                )
            } catch (_: Exception) {}
            // Only reload after a long absence AND when nothing is in progress, so
            // short screen-off / app-switch cycles never disrupt an ongoing process.
            if (awayMs >= resumeReloadThresholdMs) {
                isUserBusy { busy ->
                    if (!busy) {
                        try { loadFreshWebsite() } catch (_: Exception) {}
                    }
                }
            }
        }
        // Restart push-style sync polling
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.post(syncRunnable)
        startSyncRealtime()
    }

    override fun onPause() {
        super.onPause()
        lastPauseTime = System.currentTimeMillis()
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        stopSyncRealtime()
        syncHandler.removeCallbacks(syncRunnable)
    }

    private fun nextSyncRef(): String = (syncRefCounter++).toString()

    private fun syncConfigured(): Boolean =
        !(syncProjectId.isEmpty() || syncProjectId.startsWith("%%") ||
          syncSupabaseUrl.isEmpty() || syncSupabaseUrl.startsWith("%%") ||
          syncAnonKey.isEmpty() || syncAnonKey.startsWith("%%"))

    private fun startSyncRealtime() {
        if (!syncConfigured() || syncSocket != null) return
        try {
            val wsBase = syncSupabaseUrl.trimEnd('/').replace("https://", "wss://").replace("http://", "ws://")
            val encodedKey = URLEncoder.encode(syncAnonKey, "UTF-8")
            val request = Request.Builder()
                .url("$wsBase/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0")
                .addHeader("apikey", syncAnonKey)
                .addHeader("Authorization", "Bearer $syncAnonKey")
                .build()
            syncSocket = syncHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: OkHttpResponse) {
                    val ref = nextSyncRef()
                    webSocket.send("""{"topic":"realtime:public:app_sync_signals","event":"phx_join","payload":{"config":{"broadcast":{"self":false},"presence":{"key":""},"postgres_changes":[{"event":"INSERT","schema":"public","table":"app_sync_signals","filter":"project_id=eq.$syncProjectId"}]},"access_token":"$syncAnonKey"},"ref":"$ref"}""")
                    syncHandler.removeCallbacks(syncHeartbeatRunnable)
                    syncHandler.postDelayed(syncHeartbeatRunnable, 25000L)
                    checkSyncSignal()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"event\":\"postgres_changes\"") && text.contains(syncProjectId)) {
                        reloadFromSyncSignal()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkHttpResponse?) {
                    syncSocket = null
                    syncHandler.removeCallbacks(syncHeartbeatRunnable)
                    syncHandler.postDelayed({ startSyncRealtime() }, 5000L)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    syncSocket = null
                    syncHandler.removeCallbacks(syncHeartbeatRunnable)
                }
            })
        } catch (_: Exception) {
            syncSocket = null
        }
    }

    private fun stopSyncRealtime() {
        syncHandler.removeCallbacks(syncHeartbeatRunnable)
        try { syncSocket?.close(1000, "paused") } catch (_: Exception) {}
        syncSocket = null
    }

    /**
     * Poll the backend for a reload signal. When a newer signal exists than the
     * last one we observed, reload the WebView so users get fresh content within
     * seconds of a backend-side update — without waiting for a backgrounding cycle.
     */
    private fun checkSyncSignal() {
        if (!syncConfigured()) return
        Thread {
            try {
                val urlStr = syncSupabaseUrl.trimEnd('/') +
                    "/rest/v1/app_sync_signals?project_id=eq." + syncProjectId +
                    "&select=created_at&order=created_at.desc&limit=1"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("apikey", syncAnonKey)
                conn.setRequestProperty("Authorization", "Bearer " + syncAnonKey)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val match = Regex("\"created_at\"\\s*:\\s*\"([^\"]+)\"").find(body)
                    val ts = match?.groupValues?.get(1) ?: ""
                    if (ts.isNotEmpty() && ts != lastSignalAt) {
                        val wasInitialized = syncSignalInitialized
                        lastSignalAt = ts
                        syncSignalInitialized = true
                        // Reload on any new signal we observe after the first poll.
                        // (First poll just establishes a baseline so we don't reload on launch.)
                        if (wasInitialized) reloadFromSyncSignal()
                    } else if (ts.isEmpty()) {
                        // No signals yet — still mark baseline so the first ever signal triggers a reload.
                        syncSignalInitialized = true
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
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
        // %%KEEP_ALIVE_STOP%%
        super.onDestroy()
    }

    /**
     * JS bridge exposed as `AndroidShareBridge` — opens the Android system share
     * sheet so users get WhatsApp / Messages / Email / etc. as native options
     * when the wrapped site calls navigator.share().
     */
    inner class NativeShareBridge {
        @android.webkit.JavascriptInterface
        fun share(title: String?, text: String?, url: String?) {
            runOnUiThread {
                try {
                    val parts = mutableListOf<String>()
                    if (!text.isNullOrBlank()) parts.add(text)
                    if (!url.isNullOrBlank()) parts.add(url)
                    val body = parts.joinToString("\n").ifEmpty { url ?: title ?: "" }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    val chooser = Intent.createChooser(intent, title?.ifBlank { "Share" } ?: "Share")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(chooser)
                } catch (_: Exception) {}
            }
        }

        /** Force a URL to leave the WebView and open in its native app / browser. */
        @android.webkit.JavascriptInterface
        fun openExternal(url: String?) {
            if (url.isNullOrBlank()) return
            runOnUiThread {
                try { handleExternalUrl(url, forceExternal = true) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Override navigator.share / canShare AND intercept share-link clicks + window.open,
     * so the wrapped website's share button routes to the device's native share sheet
     * or directly to the target app. WebView's shouldOverrideUrlLoading is not always
     * invoked for JS-driven navigations, so we also pre-empt at the JS layer.
     */
    private fun injectNativeShareShim(view: WebView?) {
        val js = "(function(){try{" +
            "if(window.__appductShareShim)return;window.__appductShareShim=1;" +
            "var b=window.AndroidShareBridge;if(!b)return;" +
            "navigator.share=function(d){try{d=d||{};b.share(String(d.title||''),String(d.text||''),String(d.url||''));return Promise.resolve();}catch(e){return Promise.reject(e);}};" +
            "navigator.canShare=function(){return true;};" +
            "var SHARE_RE=/(^mailto:|^tel:|^sms:|^whatsapp:|^fb-messenger:|^tg:|^viber:|^intent:|^market:|^geo:|^skype:|wa\\.me\\/|api\\.whatsapp\\.com|web\\.whatsapp\\.com|whatsapp\\.com|chat\\.whatsapp\\.com|t\\.me\\/|telegram\\.me\\/|twitter\\.com\\/intent|x\\.com\\/intent|facebook\\.com\\/sharer|facebook\\.com\\/dialog\\/share|m\\.facebook\\.com\\/sharer|linkedin\\.com\\/sharing|linkedin\\.com\\/shareArticle|pinterest\\.com\\/pin\\/create|reddit\\.com\\/submit|mail\\.google\\.com\\/mail\\/\\?view=cm|messenger\\.com\\/share)/i;" +
            "function isShare(u){try{u=String(u||'');return !!u&&SHARE_RE.test(u);}catch(e){return false;}}" +
            "function ext(u,ev){try{if(isShare(u)){if(ev){ev.preventDefault();ev.stopImmediatePropagation();ev.stopPropagation();}b.openExternal(String(u));return true;}}catch(e){}return false;}" +
            "var _open=window.open;window.open=function(u,n,f){try{if(isShare(u)){b.openExternal(String(u));return null;}}catch(e){}return _open?_open.apply(window,arguments):null;};" +
            "document.addEventListener('click',function(ev){try{var t=ev.target;while(t&&t!==document){if(t.tagName==='A'&&t.href){if(ext(t.href,ev))return false;break;}t=t.parentNode;}}catch(e){}},true);" +
            "document.addEventListener('submit',function(ev){try{var f=ev.target;if(f&&f.action&&ext(f.action,ev))return false;}catch(e){}},true);" +
            "var _assign=Location.prototype.assign;Location.prototype.assign=function(u){if(ext(u,null))return;return _assign.call(this,u);};" +
            "var _replace=Location.prototype.replace;Location.prototype.replace=function(u){if(ext(u,null))return;return _replace.call(this,u);};" +
            "}catch(e){}})();"
        try { view?.evaluateJavascript(js, null) } catch (_: Exception) {}
    }



    private fun showError(message: String) {
        try {
            webView.stopLoading()
            // Clear any native error page (which would otherwise reveal the URL)
            webView.loadUrl("about:blank")
        } catch (_: Exception) {}
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

    private fun requestUploadPermissions() {
        try {
            val perms = mutableListOf<String>()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            perms.add(android.Manifest.permission.CAMERA)
            val needed = perms.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2002) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            try {
                pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            } catch (_: Exception) {}
            pendingGeoOrigin = null
            pendingGeoCallback = null
        }
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
        val deviceNavEnabled = %%DEVICE_NAVIGATION%%
        if (!deviceNavEnabled) {
            try {
                android.widget.Toast.makeText(this, "Please use in-app navigation", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        // Landing is native home: navigate normally inside linked pages, but once the
        // linked page is back at its first entry, the next device Back returns home.
        if (handleLandingBack()) {
            return
        } else if (webView.canGoBack()) { webView.goBack() }
        else { @Suppress("DEPRECATION") super.onBackPressed() }
    }
}
