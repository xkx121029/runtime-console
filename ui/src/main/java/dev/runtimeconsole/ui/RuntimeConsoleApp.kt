package dev.runtimeconsole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 应用根界面（M1 形态：只要能触发运行时自检并看到原始输出，不做视觉打磨）。
 *
 * @param runtimeReady 运行时二进制是否随 APK 安装到位
 * @param autoStart 启动后自动跑一次自检（由 `--ez runtime_selftest true` 触发，便于取设备侧证据）
 * @param runSelfTest 实际执行自检的挂起函数；由 app 模块注入，UI 不直接持有 Context
 */
@Composable
fun RuntimeConsoleApp(
    runtimeReady: Boolean,
    runSelfTest: suspend (onProgress: (String) -> Unit) -> String,
    autoStart: Boolean = false,
) {
    var output by remember { mutableStateOf("尚未运行自检。") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun runTest(scope: CoroutineScope) {
        if (running) return
        running = true
        progress = "准备中"
        scope.launch {
            output = runCatching { runSelfTest { progress = it } }
                .getOrElse { "自检异常：$it" }
            progress = ""
            running = false
        }
    }

    LaunchedEffect(autoStart) {
        if (autoStart) runTest(scope)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Runtime Console", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (runtimeReady) "运行时文件：已就位" else "运行时文件：缺失",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { runTest(scope) }, enabled = !running) {
                    Text(text = if (running) "自检中…" else "运行自检")
                }
                if (progress.isNotEmpty()) {
                    Text(text = progress, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
