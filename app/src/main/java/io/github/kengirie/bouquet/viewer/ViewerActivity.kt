package io.github.kengirie.bouquet.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import io.github.kengirie.bouquet.BouquetApplication

/**
 * Hosts a hardened WebView pointed at the per-session loopback HTTP
 * gateway. Each Activity instance owns one [ViewerSession] which is
 * released in [onDestroy]; a top-level [BouquetApplication.onTerminate]
 * handler covers the (rare) case where the process is torn down with
 * sessions still open.
 *
 * The WebView is configured to refuse `file://` and `content://` access,
 * disable file-URL same-origin escapes, and block mixed content. Any
 * cross-origin navigation (e.g. an outbound link) is handed to the OS via
 * an `ACTION_VIEW` intent rather than loaded inside the gateway origin.
 */
class ViewerActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val addressSegment = intent.getStringExtra(EXTRA_ADDRESS_SEGMENT)
        if (addressSegment.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as BouquetApplication
        val viewerSession = app.sessionRegistry.createSession(addressSegment)
        sessionId = viewerSession.id

        val view = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                domStorageEnabled = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url
                    val current = Uri.parse(view.url ?: "")
                    val sameOrigin = target.scheme == current.scheme &&
                        target.host == current.host &&
                        target.port == current.port
                    return if (sameOrigin) {
                        false // let WebView handle in-site navigation
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, target))
                        } catch (_: Exception) {
                            // No handler — drop silently.
                        }
                        true
                    }
                }
            }

            loadUrl("http://127.0.0.1:${viewerSession.port}/")
        }

        webView = view
        setContentView(view)

        onBackPressedDispatcher.addCallback(this) {
            val w = webView
            if (w != null && w.canGoBack()) {
                w.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionId?.let { id ->
            (application as BouquetApplication).sessionRegistry.closeSession(id)
        }
        webView?.destroy()
        webView = null
    }

    companion object {
        const val EXTRA_ADDRESS_SEGMENT = "address_segment"

        fun newIntent(context: Context, addressSegment: String): Intent =
            Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_ADDRESS_SEGMENT, addressSegment)
            }
    }
}
