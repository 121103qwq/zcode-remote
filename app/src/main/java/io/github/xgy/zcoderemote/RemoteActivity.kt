package io.github.xgy.zcoderemote

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.Menu
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.xgy.zcoderemote.data.RemoteSession
import io.github.xgy.zcoderemote.data.SessionStore
import io.github.xgy.zcoderemote.data.TransientSessionVault
import io.github.xgy.zcoderemote.databinding.ActivityRemoteBinding
import io.github.xgy.zcoderemote.web.TrustedRemoteWebViewClient
import kotlin.math.max

class RemoteActivity : AppCompatActivity(), TrustedRemoteWebViewClient.Callbacks {
    private lateinit var binding: ActivityRemoteBinding
    private lateinit var session: RemoteSession
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallbackRegistered = false
    private var webView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        callback.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { binding.networkBanner.visibility = View.GONE }
        }

        override fun onLost(network: Network) {
            runOnUiThread { binding.networkBanner.visibility = View.VISIBLE }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            finish()
            return
        }

        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        configureToolbar()
        configureBackHandling()
        configureErrorActions()

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        updateNetworkBanner()
        registerNetworkCallback()

        webView = binding.webView
        configureWebView(binding.webView)
        binding.toolbar.title = session.name
        binding.toolbar.subtitle = session.displayLocation
        loadSession()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        destroyWebView(webView)
        webView = null
        if (::session.isInitialized && session.id.startsWith("volatile-")) {
            TransientSessionVault.remove(session.id)
        }
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun configureToolbar() {
        binding.toolbar.menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, R.string.reload).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_HOME, Menu.NONE, R.string.back_home)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RELOAD -> {
                    loadSession()
                    true
                }

                MENU_HOME -> {
                    confirmLeave()
                    true
                }

                else -> false
            }
        }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )
    }

    private fun configureErrorActions() {
        binding.retryButton.setOnClickListener { loadSession() }
        binding.homeButton.setOnClickListener { confirmLeave() }
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
        target.webViewClient = TrustedRemoteWebViewClient(this)
        target.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val safeTitle = title
                    ?.filterNot { it.isISOControl() }
                    ?.trim()
                    ?.take(64)
                    ?.takeIf(String::isNotBlank)
                if (safeTitle != null) binding.toolbar.title = safeTitle
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                this@RemoteActivity.fileChooserCallback?.onReceiveValue(null)
                this@RemoteActivity.fileChooserCallback = filePathCallback
                return runCatching {
                    filePickerLauncher.launch(fileChooserParams.createIntent())
                    true
                }.getOrElse {
                    this@RemoteActivity.fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
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
        binding.errorPanel.visibility = View.GONE
        target.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress = 5
        binding.toolbar.title = session.name
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
        target.webChromeClient = null
        target.webViewClient = android.webkit.WebViewClient()
        target.removeAllViews()
        target.destroy()
    }

    private fun handleBack() {
        val target = webView
        if (target?.canGoBack() == true) {
            target.goBack()
        } else {
            confirmLeave()
        }
    }

    private fun confirmLeave() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leave_title)
            .setMessage(R.string.leave_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.leave) { _, _ -> finish() }
            .show()
    }

    private fun updateNetworkBanner() {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        binding.networkBanner.visibility = if (online) View.GONE else View.VISIBLE
    }

    private fun registerNetworkCallback() {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onPageStarted() {
        binding.errorPanel.visibility = View.GONE
        webView?.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
    }

    override fun onPageFinished() {
        binding.progress.visibility = View.GONE
    }

    override fun onMainFrameError(kind: TrustedRemoteWebViewClient.ErrorKind) {
        val message = when (kind) {
            TrustedRemoteWebViewClient.ErrorKind.EXPIRED -> R.string.web_error_expired
            TrustedRemoteWebViewClient.ErrorKind.SSL,
            TrustedRemoteWebViewClient.ErrorKind.UNSAFE,
            -> R.string.web_error_ssl

            TrustedRemoteWebViewClient.ErrorKind.RENDERER -> R.string.web_error_renderer
            TrustedRemoteWebViewClient.ErrorKind.NETWORK -> R.string.web_error_generic
        }
        showError(message)
    }

    override fun onRendererGone(webView: WebView) {
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

    companion object {
        private const val EXTRA_SESSION_ID = "io.github.xgy.zcoderemote.SESSION_ID"
        private const val MENU_RELOAD = 1
        private const val MENU_HOME = 2

        fun createIntent(context: Context, sessionId: String): Intent =
            Intent(context, RemoteActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
