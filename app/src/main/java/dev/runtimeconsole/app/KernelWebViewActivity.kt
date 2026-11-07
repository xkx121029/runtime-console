package dev.runtimeconsole.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.runtimeconsole.ui.RuntimeConsoleTheme

/**
 * 应用内 WebView：直接加载内核控制台（dsh 带一次性 token 的 URL）。
 *
 * 这是**应用内**的详情页，不是第二个 App / 第二个入口：桌面图标仍然只有 MainActivity。
 * 用 WebView 而不是外部浏览器，是为了让 token URL 不出应用、返回键行为可控。
 */
class KernelWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "内核控制台"

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = webView
                if (web != null && web.canGoBack()) web.goBack() else finish()
            }
        })

        setContent {
            RuntimeConsoleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Header(title = title, onClose = { finish() })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                WebView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    setBackgroundColor(Color.TRANSPARENT)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    // 只允许回环地址：外链交给系统浏览器，避免把 WebView 变成通用浏览器
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: android.webkit.WebResourceRequest,
                                        ): Boolean {
                                            val target = request.url
                                            val loopback = target.host in setOf("127.0.0.1", "localhost")
                                            if (!loopback) {
                                                runCatching {
                                                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target))
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                    loadUrl(url)
                                    webView = this
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.let { web ->
            web.stopLoading()
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }

    @Composable
    private fun Header(title: String, onClose: () -> Unit) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }

    companion object {
        const val EXTRA_URL = "kernel_url"
        const val EXTRA_TITLE = "kernel_title"
    }
}
