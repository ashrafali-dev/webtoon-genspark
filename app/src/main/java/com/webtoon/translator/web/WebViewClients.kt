package com.webtoon.translator.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Shared WebView configuration for both reader and chatbot panes. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureWebview(onProgress: (Int) -> Unit = {}, onPage: (String) -> Unit = {}) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadsImagesAutomatically = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = false
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.setSupportZoom(true)
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(false)
    settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { onPage(url ?: "") }
        override fun onPageFinished(view: WebView?, url: String?) { onProgress(100) }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
    }
}

/** Native bridge for page-context image fetching. */
class ImageBridge(private val onResult: (requestId: String, json: String) -> Unit) {
    @Volatile var host: WebView? = null

    @JavascriptInterface
    fun fetch(requestId: String, url: String) {
        val wv = host ?: return
        wv.post {
            val js = "(" + WebScripts.FETCH_IMAGE + ")(" + JSONObject.quote(url) + ")" +
                ".then(function(r){WebToonBridge.onImageResult(" +
                JSONObject.quote(requestId) + ", r);});"
            wv.evaluateJavascript(js, null)
        }
    }

    @JavascriptInterface
    fun onImageResult(requestId: String, json: String) { onResult(requestId, json) }
}
