package dev.runtimeconsole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.runtimeconsole.core.RuntimeKind
import dev.runtimeconsole.core.RuntimeStatus

/**
 * 应用根界面。
 *
 * M0 阶段只显示两个运行时的占位状态，不做任何视觉打磨；
 * 真实状态由 runtime-host 在 M1 之后提供。
 */
@Composable
fun RuntimeConsoleApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = "Runtime Console", style = MaterialTheme.typography.headlineSmall)
                RuntimeRow(RuntimeStatus(RuntimeKind.NODE))
                RuntimeRow(RuntimeStatus(RuntimeKind.PYTHON))
            }
        }
    }
}

@Composable
private fun RuntimeRow(status: RuntimeStatus) {
    val name = when (status.kind) {
        RuntimeKind.NODE -> "Node"
        RuntimeKind.PYTHON -> "Python"
    }
    val state = if (status.running) "运行中" else "未启动"
    Text(text = "$name：$state", style = MaterialTheme.typography.bodyLarge)
}
