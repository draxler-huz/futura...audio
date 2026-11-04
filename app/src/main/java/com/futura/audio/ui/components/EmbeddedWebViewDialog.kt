package com.futura.audio.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedWebViewDialog(
    url: String,
    onDismiss: () -> Unit
) {
    // Held so back navigation and release can reach the live WebView.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Back goes to the previous embed page first, then closes the dialog.
    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
            color = Color(0xFF0F1115)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Dialog Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161A23))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Official Web Embed",
                        color = Color.White,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Embed",
                            tint = Color.White
                        )
                    }
                }

                // WebView Container
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        loadError = null
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        // Only main-frame failures; sub-resource errors are ignored.
                                        if (request?.isForMainFrame == true) {
                                            loadError = error?.description?.toString() ?: "Failed to load embed"
                                        }
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                webViewRef = this
                                loadUrl(url)
                            }
                        },
                        update = { webView ->
                            if (webView.url != url) {
                                webView.loadUrl(url)
                            }
                        },
                        onRelease = { webView ->
                            // Stop media and free the WebView when the dialog leaves composition.
                            webView.stopLoading()
                            webView.destroy()
                            if (webViewRef === webView) webViewRef = null
                        }
                    )

                    loadError?.let { message ->
                        Text(
                            text = "Embed failed to load: $message",
                            color = Color(0xFFFF4081),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
