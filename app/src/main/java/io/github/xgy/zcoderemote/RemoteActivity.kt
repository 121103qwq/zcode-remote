package io.github.xgy.zcoderemote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.snackbar.Snackbar
import io.github.xgy.zcoderemote.data.RemoteSession
import io.github.xgy.zcoderemote.data.SessionStore
import io.github.xgy.zcoderemote.data.TransientSessionVault
import io.github.xgy.zcoderemote.databinding.ActivityRemoteBinding
import io.github.xgy.zcoderemote.security.RemoteUrlPolicy
import io.github.xgy.zcoderemote.web.TrustedRemoteWebViewClient

class RemoteActivity : AppCompatActivity(), TrustedRemoteWebViewClient.Callbacks {
    private lateinit var binding: ActivityRemoteBinding
    private lateinit var session: RemoteSession
    private var webView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var acceptedFileTypes: List<String> = listOf(ANY_MIME_TYPE)

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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

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
        enterImmersiveMode()
        applyInsets()
        configureErrorActions()

        webView = binding.webView
        configureWebView(binding.webView)
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

    override fun onPause() {
        webView?.clearCache(true)
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
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
        ViewCompat.getWindowInsetsController(window.decorView)?.apply {
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
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(target, false)
        }
        target.overScrollMode = View.OVER_SCROLL_NEVER
        target.isSaveEnabled = false
        target.clearCache(true)
        target.webViewClient = TrustedRemoteWebViewClient(this)
        target.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

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

    private fun loadSession() {
        val target = webView ?: createReplacementWebView()
        target.clearCache(true)
        binding.errorPanel.visibility = View.GONE
        target.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress = 5
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
        target.clearCache(true)
        target.clearHistory()
        target.clearFormData()
        target.webChromeClient = null
        target.webViewClient = android.webkit.WebViewClient()
        target.removeAllViews()
        target.destroy()
    }

    override fun onPageStarted() {
        binding.errorPanel.visibility = View.GONE
        webView?.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
    }

    override fun onPageFinished() {
        webView?.clearCache(true)
        binding.progress.visibility = View.GONE
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
        showError(message)
    }

    override fun onRendererGone(webView: WebView) {
        cancelPendingFileChooser()
        destroyWebView(webView)
        this.webView = null
        showError(R.string.web_error_renderer)
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
        binding.progress.visibility = View.GONE
        webView?.visibility = View.GONE
        binding.errorMessage.setText(messageResource)
        binding.errorPanel.visibility = View.VISIBLE
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

        fun createIntent(context: Context, sessionId: String): Intent =
            Intent(context, RemoteActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
