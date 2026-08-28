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

class TrustedRemoteWebViewClient(
    private val callbacks: Callbacks,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        if (RemoteUrlPolicy.isTrustedTopLevelNavigation(target)) return false

        if (
            request.isForMainFrame &&
            request.hasGesture() &&
            RemoteUrlPolicy.isExternalHttps(target)
        ) {
            callbacks.openExternal(request.url)
        } else {
            callbacks.onBlockedNavigation()
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        callbacks.onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        callbacks.onPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            callbacks.onMainFrameError(ErrorKind.NETWORK)
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!request.isForMainFrame) return
        val kind = when (errorResponse.statusCode) {
            401, 403, 404, 410 -> ErrorKind.EXPIRED
            in 400..599 -> ErrorKind.NETWORK
            else -> return
        }
        callbacks.onMainFrameError(kind)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
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
        callbacks.onMainFrameError(ErrorKind.UNSAFE)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRendererGone(view)
        return true
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
