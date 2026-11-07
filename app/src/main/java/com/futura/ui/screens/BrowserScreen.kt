package com.futura.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

private const val HOME = "https://duckduckgo.com/"

/**
 * A simple web browser: address bar, back, forward, reload, and close.
 * Only http and https pages load. Other schemes (app intents, tel:, and so
 * on) are ignored. The WebView has no bridge to app code, no file access,
 * and third-party cookies off.
 */
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }

    val webView = remember {
        newSecureWebView(context) { view, url, isLoading ->
            if (url != null) address = url
            loading = isLoading
            canBack = view.canGoBack()
            canForward = view.canGoForward()
        }
    }

    LaunchedEffect(webView) { webView.loadUrl(HOME) }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    fun go() {
        val url = browserUrl(address) ?: return
        webView.loadUrl(url)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "Close browser", tint = Color.White)
            }
            IconButton(onClick = { webView.goBack() }, enabled = canBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            IconButton(onClick = { webView.goForward() }, enabled = canForward) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Forward", tint = Color.White)
            }
            IconButton(onClick = { webView.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = Color.White)
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search or enter address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() })
            )
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { webView }
        )
    }
}

/**
 * Turns what the user typed into a URL. Text with a dot and no spaces is a
 * site address. Anything else is a search.
 */
private fun browserUrl(input: String): String? {
    val text = input.trim()
    return when {
        text.isEmpty() -> null
        text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true) -> text
        text.contains(' ') || !text.contains('.') ->
            "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
        else -> "https://$text"
    }
}

private class BrowserClient(
    private val onPage: (WebView, String?, Boolean) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val scheme = request?.url?.scheme?.lowercase()
        // true stops the WebView from loading the request. Only web pages load here.
        return scheme != "http" && scheme != "https"
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (view != null) onPage(view, url, true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (view != null) onPage(view, url, false)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun newSecureWebView(context: Context, onPage: (WebView, String?, Boolean) -> Unit): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) safeBrowsingEnabled = true
        }
        // Applies to every WebView in the app, including the Media Hub.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = BrowserClient(onPage)
    }
