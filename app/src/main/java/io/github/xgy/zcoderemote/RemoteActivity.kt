package io.github.xgy.zcoderemote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.snackbar.Snackbar
import io.github.xgy.zcoderemote.data.RemoteSession
import io.github.xgy.zcoderemote.data.SessionStore
import io.github.xgy.zcoderemote.data.TransientSessionVault
import io.github.xgy.zcoderemote.databinding.ActivityRemoteBinding
import io.github.xgy.zcoderemote.security.RemoteUrlPolicy
import io.github.xgy.zcoderemote.web.AutoReconnectPolicy
import io.github.xgy.zcoderemote.web.CompletionTransition
import io.github.xgy.zcoderemote.web.TrustedRemoteWebViewClient

class RemoteActivity : AppCompatActivity(), TrustedRemoteWebViewClient.Callbacks {
    private lateinit var binding: ActivityRemoteBinding
    private lateinit var session: RemoteSession
    private var webView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var acceptedFileTypes: List<String> = listOf(ANY_MIME_TYPE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectPolicy = AutoReconnectPolicy()
    private val completionTransition = CompletionTransition()
    private lateinit var completionNotifier: RemoteCompletionNotifier
    private var retryRunnable: Runnable? = null
    private var completionPollRunnable: Runnable? = null
    private var retryAttempt = 0
    private var lastErrorKind: TrustedRemoteWebViewClient.ErrorKind? = null
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                if (lastErrorKind == TrustedRemoteWebViewClient.ErrorKind.NETWORK) {
                    scheduleReconnect(TrustedRemoteWebViewClient.ErrorKind.NETWORK, immediate = true)
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        val values = if (isTrustedCurrentPage()) {
            extractSafeFileUris(result.resultCode, result.data, acceptedFileTypes)
        } else {
            null
        }
        acceptedFileTypes = listOf(ANY_MIME_TYPE)
        runCatching { callback.onReceiveValue(values) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterImmersiveMode()

        if (WebView.getCurrentWebViewPackage() == null) {
            Toast.makeText(this, R.string.webview_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        session = sessionId?.let { id ->
            runCatching { SessionStore(this).find(id) }.getOrNull()
                ?: TransientSessionVault.find(id)
        } ?: run {
            Toast.makeText(this, R.string.session_unavailable, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }

        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        completionNotifier = RemoteCompletionNotifier(this).also { it.createChannel() }
        applyInsets()
        configureErrorActions()

        webView = binding.webView
        configureWebView(binding.webView)
        registerNetworkCallback()
        loadSession()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        webView?.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        cancelReconnect()
        stopCompletionPolling()
        unregisterNetworkCallback()
        cancelPendingFileChooser()
        destroyWebView(webView)
        webView = null
        if (
            ::session.isInitialized &&
            session.id.startsWith("volatile-") &&
            isFinishing &&
            !isChangingConfigurations
        ) {
            TransientSessionVault.remove(session.id)
        }
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, ime.bottom)

            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemGestures(),
            )
            val spacing = dp(28)
            binding.errorContent.setPadding(
                safe.left + spacing,
                safe.top + spacing,
                safe.right + spacing,
                safe.bottom + spacing,
            )
            insets
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun configureErrorActions() {
        binding.retryButton.setOnClickListener { loadSession() }
        binding.homeButton.setOnClickListener { finish() }
    }

    private fun configureWebView(target: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        target.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(target, false)
        }
        target.overScrollMode = View.OVER_SCROLL_NEVER
        target.isSaveEnabled = false
        target.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        target.webViewClient = TrustedRemoteWebViewClient(this)
        target.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                if (!isTrustedCurrentPage()) {
                    filePathCallback.onReceiveValue(null)
                    onMainFrameError(TrustedRemoteWebViewClient.ErrorKind.UNSAFE)
                    return true
                }

                cancelPendingFileChooser()
                this@RemoteActivity.fileChooserCallback = filePathCallback
                acceptedFileTypes = normalizeAcceptedMimeTypes(fileChooserParams.acceptTypes)
                return runCatching {
                    filePickerLauncher.launch(createSafeFilePicker(fileChooserParams))
                    true
                }.getOrElse {
                    cancelPendingFileChooser()
                    Snackbar.make(binding.root, R.string.file_picker_unavailable, Snackbar.LENGTH_LONG)
                        .show()
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.deny() }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                callback.invoke(origin, false, false)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean = false

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        }
    }

    private fun loadSession(resetRetryState: Boolean = true) {
        cancelReconnect()
        if (resetRetryState) {
            reconnectPolicy.reset()
            retryAttempt = 0
            lastErrorKind = null
            completionTransition.reset()
        }
        val target = webView ?: createReplacementWebView()
        binding.errorPanel.visibility = View.GONE
        target.visibility = View.VISIBLE
        target.loadUrl(session.url)
    }

    private fun createReplacementWebView(): WebView {
        val replacement = WebView(this)
        replacement.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        binding.webContainer.addView(replacement, 0)
        configureWebView(replacement)
        webView = replacement
        return replacement
    }

    private fun destroyWebView(target: WebView?) {
        if (target == null) return
        (target.parent as? android.view.ViewGroup)?.removeView(target)
        target.stopLoading()
        target.clearHistory()
        target.clearFormData()
        target.webChromeClient = null
        target.webViewClient = android.webkit.WebViewClient()
        target.removeAllViews()
        target.destroy()
    }

    override fun onPageStarted() {
        stopCompletionPolling()
        binding.errorPanel.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    override fun onPageFinished() {
        cancelReconnect()
        reconnectPolicy.reset()
        retryAttempt = 0
        lastErrorKind = null
        maybeRequestNotificationPermission()
        startCompletionPolling()
    }

    override fun onMainFrameError(kind: TrustedRemoteWebViewClient.ErrorKind) {
        val message = when (kind) {
            TrustedRemoteWebViewClient.ErrorKind.EXPIRED -> R.string.web_error_expired
            TrustedRemoteWebViewClient.ErrorKind.SSL,
            -> R.string.web_error_ssl

            TrustedRemoteWebViewClient.ErrorKind.UNSAFE -> R.string.web_error_unsafe

            TrustedRemoteWebViewClient.ErrorKind.RENDERER -> R.string.web_error_renderer
            TrustedRemoteWebViewClient.ErrorKind.NETWORK -> R.string.web_error_generic
        }
        if (kind == TrustedRemoteWebViewClient.ErrorKind.NETWORK) {
            scheduleReconnect(kind)
        } else {
            cancelReconnect()
            stopCompletionPolling()
            lastErrorKind = kind
            showError(message)
        }
    }

    override fun onRendererGone(webView: WebView) {
        cancelPendingFileChooser()
        destroyWebView(webView)
        this.webView = null
        scheduleReconnect(TrustedRemoteWebViewClient.ErrorKind.RENDERER)
    }

    override fun openExternal(uri: Uri) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }.onFailure {
            Snackbar.make(binding.root, R.string.external_link_blocked, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onBlockedNavigation() {
        Snackbar.make(binding.root, R.string.external_link_blocked, Snackbar.LENGTH_SHORT).show()
    }

    private fun showError(messageResource: Int) {
        webView?.visibility = View.GONE
        binding.errorMessage.setText(messageResource)
        binding.errorPanel.visibility = View.VISIBLE
    }

    private fun scheduleReconnect(
        kind: TrustedRemoteWebViewClient.ErrorKind,
        immediate: Boolean = false,
    ) {
        cancelReconnect()
        stopCompletionPolling()
        lastErrorKind = kind
        val delay = if (immediate) 0L else reconnectPolicy.nextDelay(kind)
        if (delay == null) {
            showError(
                if (kind == TrustedRemoteWebViewClient.ErrorKind.RENDERER) {
                    R.string.web_error_renderer
                } else {
                    R.string.web_error_generic
                },
            )
            return
        }

        retryAttempt += 1
        webView?.visibility = View.GONE
        binding.errorMessage.text = getString(R.string.web_error_reconnecting, retryAttempt)
        binding.errorPanel.visibility = View.VISIBLE
        val action = Runnable {
            retryRunnable = null
            if (!isFinishing && !isDestroyed) loadSession(resetRetryState = false)
        }
        retryRunnable = action
        mainHandler.postDelayed(action, delay)
    }

    private fun cancelReconnect() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun startCompletionPolling() {
        stopCompletionPolling()
        val action = object : Runnable {
            override fun run() {
                completionPollRunnable = this
                pollCompletionState()
            }
        }
        completionPollRunnable = action
        mainHandler.postDelayed(action, COMPLETION_FIRST_POLL_DELAY_MILLIS)
    }

    private fun pollCompletionState() {
        val target = webView
        if (target == null || !isTrustedCurrentPage() || isFinishing || isDestroyed) return
        target.evaluateJavascript(COMPLETION_STATE_SCRIPT) { rawValue ->
            val state = when (rawValue?.trim()?.trim('"')) {
                "running" -> CompletionTransition.PageState.RUNNING
                "idle" -> CompletionTransition.PageState.IDLE
                else -> CompletionTransition.PageState.UNKNOWN
            }
            if (completionTransition.observe(state)) {
                completionNotifier.notifyCompleted(session)
            }
            completionPollRunnable?.let {
                mainHandler.postDelayed(it, COMPLETION_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopCompletionPolling() {
        completionPollRunnable?.let(mainHandler::removeCallbacks)
        completionPollRunnable = null
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val preferences = getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallbackRegistered = runCatching {
            manager.registerDefaultNetworkCallback(networkCallback)
            true
        }.getOrDefault(false)
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    private fun isTrustedCurrentPage(): Boolean =
        RemoteUrlPolicy.isTrustedTopLevelNavigation(webView?.url.orEmpty())

    private fun createSafeFilePicker(params: WebChromeClient.FileChooserParams): Intent {
        val mimeTypes = acceptedFileTypes
        val primaryType = mimeTypes.singleOrNull() ?: ANY_MIME_TYPE
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(primaryType)
            .putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            )
            .apply {
                if (mimeTypes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                }
            }
    }

    private fun normalizeAcceptedMimeTypes(rawTypes: Array<out String>?): List<String> {
        val normalized = rawTypes
            .orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { type ->
                val slash = type.indexOf('/')
                slash > 0 && slash < type.lastIndex && type.none(Char::isWhitespace)
            }
            .distinct()
            .take(MAX_ACCEPTED_MIME_TYPES)
        return normalized.ifEmpty { listOf(ANY_MIME_TYPE) }
    }

    private fun extractSafeFileUris(
        resultCode: Int,
        resultData: Intent?,
        allowedTypes: List<String>,
    ): Array<Uri>? {
        if (resultCode != RESULT_OK || resultData == null) return null
        val candidates = buildList {
            val clipData = resultData.clipData
            if (clipData != null) {
                for (index in 0 until clipData.itemCount.coerceAtMost(MAX_SELECTED_FILES + 1)) {
                    clipData.getItemAt(index).uri?.let(::add)
                }
            } else {
                resultData.data?.let(::add)
            }
        }
        val safe = candidates.takeIf { it.isNotEmpty() && it.size <= MAX_SELECTED_FILES }
            ?.takeIf { values -> values.all { isSafeFileUri(it, allowedTypes) } }
            ?.toTypedArray()
        if (candidates.isNotEmpty() && safe == null) {
            Snackbar.make(binding.root, R.string.unsafe_file_rejected, Snackbar.LENGTH_LONG).show()
        }
        return safe
    }

    private fun isSafeFileUri(uri: Uri, allowedTypes: List<String>): Boolean {
        if (uri.scheme != "content") return false
        return runCatching {
            val actualType = contentResolver.getType(uri)?.lowercase() ?: return@runCatching false
            val typeAllowed = allowedTypes.any { allowed ->
                allowed == ANY_MIME_TYPE ||
                    allowed == actualType ||
                    (allowed.endsWith("/*") &&
                        actualType.startsWith(allowed.substringBefore('/') + "/"))
            }
            if (!typeAllowed) return@runCatching false
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun cancelPendingFileChooser() {
        val callback = fileChooserCallback
        fileChooserCallback = null
        acceptedFileTypes = listOf(ANY_MIME_TYPE)
        if (callback != null) runCatching { callback.onReceiveValue(null) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SESSION_ID = "io.github.xgy.zcoderemote.SESSION_ID"
        private const val ANY_MIME_TYPE = "*/*"
        private const val MAX_ACCEPTED_MIME_TYPES = 16
        private const val MAX_SELECTED_FILES = 10
        private const val COMPLETION_FIRST_POLL_DELAY_MILLIS = 1_000L
        private const val COMPLETION_POLL_INTERVAL_MILLIS = 2_500L
        private const val NOTIFICATION_PREFERENCES = "notification_preferences"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "permission_requested"
        private const val COMPLETION_STATE_SCRIPT = """
            (() => {
              if (location.origin !== 'https://zcode.z.ai' || !location.pathname.startsWith('/remote/v4')) return 'unknown';
              const running = document.querySelector('[data-testid="v4-composer-stop"], [data-testid="v4-composer-cancel"]');
              if (running) return 'running';
              return document.querySelector('[data-testid="v4-composer-send"]') ? 'idle' : 'unknown';
            })()
        """

        fun createIntent(context: Context, sessionId: String): Intent =
            Intent(context, RemoteActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
