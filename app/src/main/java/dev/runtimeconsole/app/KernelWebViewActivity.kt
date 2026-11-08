package dev.runtimeconsole.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.runtimeconsole.ui.RuntimeConsoleTheme

/**
 * 应用内 WebView：直接加载内核控制台（dsh 带一次性 token 的 URL）。
 *
 * 这是**应用内**的详情页，不是第二个 App / 第二个入口：桌面图标仍然只有 MainActivity。
 * 用 WebView 而不是外部浏览器，是为了让 token URL 不出应用、返回键行为可控。
 *
 * 健壮性（T-11 之后补的）：
 *  - 顶部进度条：内核冷启动时页面可能几十秒没内容，至少让用户看到"在加载"；
 *  - 主框架加载失败 / 5xx 时显示错误页，给出"内核可能未就绪"的说明与重试入口；
 *  - 前端 console 输出转到 logcat（tag `WebViewConsole`），排查空白页不用再猜。
 */
class KernelWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var pageProgress by mutableIntStateOf(0)
    private var failure by mutableStateOf<String?>(null)

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
                        Box(modifier = Modifier.fillMaxSize()) {
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
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                // 这里的隐式接收者是 WebView（有只读的 getProgress），必须限定到 Activity
                                                this@KernelWebViewActivity.pageProgress = newProgress
                                            }

                                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                                Log.w(
                                                    "WebViewConsole",
                                                    "${message.message()} @${message.sourceId()}:${message.lineNumber()}",
                                                )
                                                return true
                                            }
                                        }
                                        // 只允许回环地址：外链交给系统浏览器，避免把 WebView 变成通用浏览器
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                failure = null
                                                this@KernelWebViewActivity.pageProgress = 0
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                this@KernelWebViewActivity.pageProgress = 100
                                            }

                                            override fun onReceivedError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                error: WebResourceError,
                                            ) {
                                                // 子资源（图片）失败不遮页面，只有主文档失败才判定"打不开"
                                                if (request.isForMainFrame) {
                                                    failure = "加载失败（${error.errorCode}）：${error.description}"
                                                }
                                            }

                                            override fun onReceivedHttpError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                errorResponse: WebResourceResponse,
                                            ) {
                                                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                                                    failure = "内核返回 HTTP ${errorResponse.statusCode}"
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                request: WebResourceRequest,
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
                            if (pageProgress in 0..99) {
                                LinearProgressIndicator(
                                    progress = { pageProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter),
                                )
                            }
                            failure?.let { reason ->
                                ErrorPanel(
                                    reason = reason,
                                    onRetry = {
                                        failure = null
                                        pageProgress = 0
                                        webView?.reload()
                                    },
                                    onBack = { finish() },
                                )
                            }
                        }
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
        Row(
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

    @Composable
    private fun ErrorPanel(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "页面打不开",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "最常见的原因是内核还没启动完成（首次冷启动需要 1–2 分钟），" +
                        "或者进程刚被系统回收。回控制台确认状态是「在线」后再打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onBack) { Text("返回控制台") }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "kernel_url"
        const val EXTRA_TITLE = "kernel_title"
    }
}
