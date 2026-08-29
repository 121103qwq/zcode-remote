package io.github.xgy.zcoderemote.web

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.xgy.zcoderemote.security.RemoteUrlPolicy
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

class TrustedRemoteWebViewClient(
    private val callbacks: Callbacks,
) : WebViewClient() {
    private val unsafeNavigationReported = AtomicBoolean(false)
    private var mainFrameFailed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        if (RemoteUrlPolicy.isTrustedTopLevelNavigation(target)) return false

        if (
            request.isForMainFrame &&
            request.hasGesture() &&
            RemoteUrlPolicy.isExternalHttps(target)
        ) {
            callbacks.openExternal(request.url)
        } else if (request.isForMainFrame) {
            reportUnsafeNavigation(view)
        } else {
            callbacks.onBlockedNavigation()
        }
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (
            request.isForMainFrame &&
            !RemoteUrlPolicy.isTrustedTopLevelNavigation(request.url.toString())
        ) {
            // shouldOverrideUrlLoading is not invoked for POST requests. Intercepting the main
            // resource keeps an untrusted POST target from reaching the network. The UI callback
            // is posted because WebView invokes this method on a background thread.
            reportUnsafeNavigation(view)
            return blockedNavigationResponse()
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (!RemoteUrlPolicy.isTrustedTopLevelNavigation(url)) {
            // shouldInterceptRequest is not invoked for every redirect. Re-check the committed
            // main-frame URL before it can remain visible, and keep all UI callbacks on this
            // main-thread WebView callback.
            view.stopLoading()
            reportUnsafeNavigation(view)
            return
        }
        unsafeNavigationReported.set(false)
        mainFrameFailed = false
        callbacks.onPageStarted()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (!RemoteUrlPolicy.isTrustedTopLevelNavigation(url)) {
            view.stopLoading()
            reportUnsafeNavigation(view)
        }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (!RemoteUrlPolicy.isTrustedTopLevelNavigation(url)) {
            view.visibility = WebView.INVISIBLE
            view.stopLoading()
            reportUnsafeNavigation(view)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!RemoteUrlPolicy.isTrustedTopLevelNavigation(url)) return
        if (mainFrameFailed) return
        callbacks.onPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (
            request.isForMainFrame &&
            RemoteUrlPolicy.isTrustedTopLevelNavigation(request.url.toString())
        ) {
            mainFrameFailed = true
            callbacks.onMainFrameError(ErrorKind.NETWORK)
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!request.isForMainFrame) return
        if (!RemoteUrlPolicy.isTrustedTopLevelNavigation(request.url.toString())) return
        val kind = when (errorResponse.statusCode) {
            401, 403, 404, 410 -> ErrorKind.EXPIRED
            in 400..599 -> ErrorKind.NETWORK
            else -> return
        }
        mainFrameFailed = true
        callbacks.onMainFrameError(kind)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        mainFrameFailed = true
        callbacks.onMainFrameError(ErrorKind.SSL)
    }

    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            callback.backToSafety(true)
        }
        mainFrameFailed = true
        callbacks.onMainFrameError(ErrorKind.UNSAFE)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRendererGone(view)
        return true
    }

    private fun blockedNavigationResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        Charsets.UTF_8.name(),
        403,
        "Blocked",
        mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'",
        ),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun reportUnsafeNavigation(view: WebView) {
        if (!unsafeNavigationReported.compareAndSet(false, true)) return
        view.post { callbacks.onMainFrameError(ErrorKind.UNSAFE) }
    }

    enum class ErrorKind {
        NETWORK,
        EXPIRED,
        SSL,
        UNSAFE,
        RENDERER,
    }

    interface Callbacks {
        fun onPageStarted()
        fun onPageFinished()
        fun onMainFrameError(kind: ErrorKind)
        fun onRendererGone(webView: WebView)
        fun openExternal(uri: Uri)
        fun onBlockedNavigation()
    }
}
