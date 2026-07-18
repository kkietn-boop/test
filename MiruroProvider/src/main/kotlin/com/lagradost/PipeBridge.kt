package com.lagradost

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

data class BridgeResponse(
    val body: String,
    val headers: Map<String, String>
)

object PipeBridge {
    private var webView: WebView? = null
    
    @Volatile
    private var isReady = CompletableDeferred<Unit>()
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredRequests = ConcurrentHashMap<String, CompletableDeferred<BridgeResponse>>()

    class AndroidBridge {
        @JavascriptInterface
        fun onResult(id: String, body: String, headersJson: String) {
            val deferred = deferredRequests[id]
            if (deferred != null) {
                try {
                    val headers = jsonParser.decodeFromString<Map<String, String>>(headersJson)
                    deferred.complete(BridgeResponse(body, headers))
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                } finally {
                    deferredRequests.remove(id)
                }
            }
        }

        @JavascriptInterface
        fun onError(id: String, error: String) {
            val deferred = deferredRequests[id]
            if (deferred != null) {
                deferred.completeExceptionally(Exception(error))
                deferredRequests.remove(id)
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun init(context: Context) {
        runOnMainThread {
            if (webView != null) return@runOnMainThread
            
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.contains("miruro.to") == true) {
                        isReady.complete(Unit)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val reqUrl = request?.url?.toString() ?: return false
                    return shouldBlock(reqUrl)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    url ?: return false
                    return shouldBlock(url)
                }

                private fun shouldBlock(url: String): Boolean {
                    val isSameOrigin = url.startsWith("https://www.miruro.to") || url.startsWith("https://miruro.to")
                    return !isSameOrigin
                }
            }
            
            wv.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    return false
                }
            }
            
            webView = wv
            wv.loadUrl("https://www.miruro.to/")
        }
    }

    fun destroy() {
        runOnMainThread {
            webView?.let { wv ->
                wv.removeJavascriptInterface("AndroidBridge")
                wv.stopLoading()
                wv.destroy()
            }
            webView = null
            isReady = CompletableDeferred()
            
            for ((_, deferred) in deferredRequests) {
                deferred.completeExceptionally(Exception("PipeBridge destroyed"))
            }
            deferredRequests.clear()
        }
    }

    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): BridgeResponse {
        isReady.await()
        
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeResponse>()
        deferredRequests[requestId] = deferred
        
        runOnMainThread {
            val wv = webView
            if (wv == null) {
                deferred.completeExceptionally(Exception("WebView not initialized"))
                deferredRequests.remove(requestId)
                return@runOnMainThread
            }
            
            val jsonUrl = jsonParser.encodeToString(url)
            val jsonHeaders = jsonParser.encodeToString(headers)
            val js = """
                (function() {
                    const url = $jsonUrl;
                    const headers = $jsonHeaders;
                    const id = "$requestId";
                    fetch(url, {
                        method: 'GET',
                        headers: headers
                    })
                    .then(response => {
                        const resHeaders = {};
                        response.headers.forEach((value, key) => {
                            resHeaders[key] = value;
                        });
                        return response.text().then(text => ({ body: text, headers: resHeaders }));
                    })
                    .then(result => {
                        AndroidBridge.onResult(id, result.body, JSON.stringify(result.headers));
                    })
                    .catch(error => {
                        AndroidBridge.onError(id, error.toString());
                    });
                })();
            """.trimIndent()
            
            wv.evaluateJavascript(js, null)
        }
        
        return deferred.await()
    }
}
