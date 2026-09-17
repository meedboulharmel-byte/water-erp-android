package com.sidilahcen.watererp

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.WebViewTransport
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sidilahcen.watererp.databinding.ActivityMainBinding
import java.io.File

/**
 * Full-screen WebView host for the WATER ERP Google Apps Script web app.
 *
 * Keeps every navigation, Google login redirect and form POST inside the
 * WebView; only tel / mailto / WhatsApp leave the app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var keepSplash = true
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val parsed = WebChromeClient.FileChooserParams.parseResult(
            result.resultCode,
            result.data,
        )
        when {
            parsed != null -> callback.onReceiveValue(parsed)
            result.resultCode == RESULT_OK && cameraImageUri != null ->
                callback.onReceiveValue(arrayOf(cameraImageUri!!))
            else -> callback.onReceiveValue(null)
        }
        cameraImageUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchFileChooser(includeCamera = true) else launchFileChooser(includeCamera = false)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val allowed = grants.values.any { it }
        pendingGeoCallback?.invoke(pendingGeoOrigin, allowed, false)
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (request == null) return@registerForActivityResult
        val grantedResources = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    grants[Manifest.permission.CAMERA] == true
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    grants[Manifest.permission.RECORD_AUDIO] == true
                else -> true
            }
        }.toTypedArray()
        if (grantedResources.isEmpty()) request.deny() else request.grant(grantedResources)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSplash }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        configureWebView()
        configureSwipeRefresh()
        configureOverlays()
        configureBackNavigation()
        registerNetworkCallback()

        if (isOnline()) {
            binding.webView.loadUrl(AppConfig.TARGET_URL)
        } else {
            keepSplash = false
            showOffline()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Apps Script stores the session token in localStorage / sessionStorage.
        // WebSQL (databaseEnabled) is deprecated; kept only as a fallback for
        // older WebView builds that still expose it.
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = AppConfig.MOBILE_USER_AGENT
        settings.setGeolocationEnabled(true)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = WaterErpWebViewClient()
        webView.webChromeClient = WaterErpWebChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefresh.isEnabled =
                scrollY == 0 && binding.offlineView.visibility != View.VISIBLE
        }
    }

    private fun configureSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.teal_700)
        binding.swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                hideOverlays()
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showOffline()
            }
        }
    }

    private fun configureOverlays() {
        binding.btnRetryOffline.setOnClickListener { retryLoad() }
        binding.btnRetryError.setOnClickListener { retryLoad() }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.webView.canGoBack() -> binding.webView.goBack()
                        else -> finish()
                    }
                }
            },
        )
    }

    private fun retryLoad() {
        if (!isOnline()) {
            showOffline()
            return
        }
        hideOverlays()
        if (binding.webView.url.isNullOrBlank()) {
            binding.webView.loadUrl(AppConfig.TARGET_URL)
        } else {
            binding.webView.reload()
        }
    }

    private fun hideOverlays() {
        binding.offlineView.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = true
    }

    private fun showOffline() {
        binding.offlineView.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        binding.webView.visibility = View.INVISIBLE
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.isEnabled = false
        binding.progressBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.txtErrorMessage.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.offlineView.visibility = View.GONE
        binding.webView.visibility = View.INVISIBLE
        binding.swipeRefresh.isRefreshing = false
        binding.progressBar.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (binding.offlineView.visibility == View.VISIBLE) retryLoad()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isOnline()) showOffline()
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType),
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = getSystemService(DownloadManager::class.java)
            manager?.enqueue(request)
        } catch (err: Exception) {
            Log.e(TAG, "Download failed: ${err.message}")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun launchFileChooser(includeCamera: Boolean) {
        val gallery = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        val intents = mutableListOf<Intent>()
        if (includeCamera) {
            val photoFile = File(File(cacheDir, "images").apply { mkdirs() }, "capture_${System.currentTimeMillis()}.jpg")
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile,
            )
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intents.add(camera)
        }
        val chooser = Intent.createChooser(gallery, getString(R.string.choose_file)).apply {
            if (intents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            }
        }
        fileChooserLauncher.launch(chooser)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "No handler for $uri")
        }
    }

    private inner class WaterErpWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()

            if (scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "whatsapp") {
                openExternal(uri)
                return true
            }
            if (scheme == "intent") {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                } catch (err: Exception) {
                    Log.w(TAG, "intent: URL failed: ${err.message}")
                }
                return true
            }
            if (scheme == "http" || scheme == "https") {
                if (AppConfig.EXTERNAL_HOST_HINTS.any { host.contains(it) }) {
                    openExternal(uri)
                    return true
                }
                // Stay inside the WebView for Apps Script, Google login, fonts, etc.
                return false
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            keepSplash = false
            if (isOnline()) hideOverlays()
            binding.progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefresh.isRefreshing = false
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            if (!isOnline()) {
                showOffline()
            } else {
                val description = error.description?.toString()
                    ?: getString(R.string.error_generic)
                showError(description)
            }
        }
    }

    private inner class WaterErpWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility =
                if (newProgress in 1..99) View.VISIBLE else View.GONE
            if (newProgress >= 80) keepSplash = false
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val level = when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                ConsoleMessage.MessageLevel.TIP,
                ConsoleMessage.MessageLevel.DEBUG,
                -> Log.DEBUG
                else -> Log.INFO
            }
            Log.println(
                level,
                JS_TAG,
                "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
            )
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            val input = EditText(this@MainActivity).apply {
                setText(defaultValue.orEmpty())
                setSelection(text.length)
            }
            val container = FrameLayout(this@MainActivity).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(input)
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.confirm(input.text.toString())
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // Load pop-ups (rare Google OAuth windows) in the same WebView so
            // they never escape to Chrome.
            val transport = resultMsg.obj as? WebViewTransport ?: return false
            transport.webView = view
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            val cameraGranted = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted) {
                launchFileChooser(includeCamera = true)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            val fine = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (fine) {
                callback.invoke(origin, true, false)
                return
            }
            pendingGeoOrigin = origin
            pendingGeoCallback = callback
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = mutableListOf<String>()
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.CAMERA
            }
            if (needed.isEmpty()) {
                request.grant(request.resources)
                return
            }
            pendingWebPermission = request
            webPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
        binding.webView.apply {
            loadUrl("about:blank")
            stopLoading()
            webChromeClient = WebChromeClient()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WaterERP"
        private const val JS_TAG = "WaterERP-JS"
    }
}
