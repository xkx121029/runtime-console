package dev.runtimeconsole.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.runtimeconsole.core.KernelEndpointStatus
import dev.runtimeconsole.core.KernelId
import dev.runtimeconsole.core.KernelState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 应用根界面（M4）：一屏看清两个内核的状态，并可直接起停、打开控制台、看日志。
 *
 * 视觉取向：暖灰纸面 + 墨绿主色，等宽字体只用于"数字和日志"，不做渐变/发光那类
 * 通用 AI 风格装饰；状态靠色块与字重表达。
 */

private val Paper = Color(0xFFFBF9F4)
private val Ink = Color(0xFF14160F)
private val Forest = Color(0xFF2F5D3A)
private val ForestContainer = Color(0xFFCDEFD2)
private val Clay = Color(0xFF8A5A2B)
private val ClayContainer = Color(0xFFF7DFC4)
private val Graphite = Color(0xFF11140F)
private val GraphiteCard = Color(0xFF1B1F19)

private fun consoleLightScheme() = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = ForestContainer,
    onPrimaryContainer = Color(0xFF0B2311),
    secondary = Clay,
    onSecondary = Color.White,
    secondaryContainer = ClayContainer,
    onSecondaryContainer = Color(0xFF2C1600),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E7DE),
    onSurfaceVariant = Color(0xFF444840),
    outline = Color(0xFF757A6E),
    outlineVariant = Color(0xFFC6C9BE),
    error = Color(0xFFB3261E),
)

private fun consoleDarkScheme() = darkColorScheme(
    primary = Color(0xFF9ED6A8),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF1A4B32),
    onPrimaryContainer = Color(0xFFBAF3C4),
    secondary = Color(0xFFE5C08D),
    onSecondary = Color(0xFF442B0C),
    secondaryContainer = Color(0xFF5C3F1D),
    onSecondaryContainer = Color(0xFFF7DFC4),
    background = Graphite,
    onBackground = Color(0xFFE4E6DC),
    surface = Graphite,
    onSurface = Color(0xFFE4E6DC),
    surfaceVariant = GraphiteCard,
    onSurfaceVariant = Color(0xFFC4C9BD),
    outline = Color(0xFF8D9286),
    outlineVariant = Color(0xFF3F453B),
    error = Color(0xFFFFB4A9),
)

@Composable
fun RuntimeConsoleTheme(darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) consoleDarkScheme() else consoleLightScheme(),
        shapes = MaterialTheme.shapes.copy(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}

/**
 * @param runtimeReady 运行时二进制是否随 APK 安装到位
 * @param kernels 两个内核的当前状态（由 app 层每 2s 刷新）
 * @param autoRun 启动 App 时是否自动拉起内核
 * @param onAutoRunChange 切换自动拉起
 * @param onStartKernel / onStopKernel 起停
 * @param onOpenKernel 用系统浏览器打开内核入口（dsh 自动带 token）
 * @param loadLog 读取日志尾部（挂起函数，IO 线程由 app 层保证）
 * @param selfTestOutput / selfTestRunning / onRunSelfTest 运行时自检
 */
@Composable
fun RuntimeConsoleApp(
    runtimeReady: Boolean,
    kernels: List<KernelEndpointStatus>,
    autoRun: Boolean = true,
    onAutoRunChange: (Boolean) -> Unit = {},
    onStartKernel: (KernelId) -> Unit = {},
    onStopKernel: (KernelId) -> Unit = {},
    onOpenKernel: (KernelId) -> Unit = {},
    loadLog: suspend (KernelId) -> String = { "" },
    selfTestOutput: String = "",
    selfTestRunning: Boolean = false,
    onRunSelfTest: () -> Unit = {},
) {
    var logTarget by remember { mutableStateOf<KernelId?>(null) }
    var logText by remember { mutableStateOf("") }

    RuntimeConsoleTheme {
        Scaffold { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Header(kernels = kernels, runtimeReady = runtimeReady) }
                items(kernels, key = { it.id.name }) { status ->
                    KernelCard(
                        status = status,
                        onStart = { onStartKernel(status.id) },
                        onStop = { onStopKernel(status.id) },
                        onOpen = { onOpenKernel(status.id) },
                        onShowLog = {
                            logTarget = status.id
                            logText = ""
                        },
                    )
                }
                item {
                    AutoRunRow(autoRun = autoRun, onAutoRunChange = onAutoRunChange)
                }
                item {
                    SelfTestCard(
                        running = selfTestRunning,
                        output = selfTestOutput,
                        onRun = onRunSelfTest,
                    )
                }
                item {
                    Text(
                        text = "所有端口仅监听 127.0.0.1，不提供局域网访问。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        logTarget?.let { id ->
            ModalLogSheet(
                title = id.title,
                text = logText,
                onDismiss = { logTarget = null },
                onLoad = { logText = loadLog(id) },
            )
        }
    }
}

@Composable
private fun Header(kernels: List<KernelEndpointStatus>, runtimeReady: Boolean) {
    val running = kernels.count { it.state == KernelState.RUNNING }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "运行时控制台",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (kernels.isEmpty()) "正在读取状态…" else "$running / ${kernels.size} 个内核在线 · 双内核同进程宿主",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!runtimeReady) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "运行时二进制缺失：请重新安装 APK",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun KernelCard(
    status: KernelEndpointStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onShowLog: () -> Unit,
) {
    val accent = when (status.state) {
        KernelState.RUNNING -> MaterialTheme.colorScheme.primary
        KernelState.STARTING -> MaterialTheme.colorScheme.secondary
        KernelState.UNHEALTHY -> MaterialTheme.colorScheme.error
        KernelState.STOPPED -> MaterialTheme.colorScheme.outline
    }
    val container = MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = container,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = accent, pulsing = status.state == KernelState.STARTING)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.id.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = status.id.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PortBadge(status.id.port)
            }

            Text(
                text = stateLabel(status),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric("内存", status.rssKb?.let { formatMb(it) } ?: "—")
                Metric("运行", status.uptimeMs?.let { formatDuration(it) } ?: "—")
                Metric("HTTP", status.httpStatus?.toString() ?: "—")
                Metric("PID", status.pid?.takeIf { it > 0 }?.toString() ?: "—")
            }

            status.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStart,
                    enabled = status.state == KernelState.STOPPED || status.state == KernelState.UNHEALTHY,
                ) { Text(if (status.state == KernelState.UNHEALTHY) "重启" else "启动") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = status.state != KernelState.STOPPED,
                ) { Text("停止") }
                Button(
                    onClick = onOpen,
                    enabled = status.state == KernelState.RUNNING,
                ) { Text("打开") }
                TextButton(onClick = onShowLog) { Text("日志") }
            }
        }
    }
}

@Composable
private fun stateLabel(status: KernelEndpointStatus): String = when (status.state) {
    KernelState.RUNNING -> "在线 · HTTP ${status.httpStatus}"
    KernelState.STARTING -> "启动中… 首次冷启动约 1–2 分钟"
    KernelState.UNHEALTHY -> "进程在跑，端口未响应"
    KernelState.STOPPED -> "已停止"
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse-alpha",
        )
        value
    } else 1f
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun PortBadge(port: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        Text(
            text = "127.0.0.1:$port",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AutoRunRow(autoRun: Boolean, onAutoRunChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启动应用时自动运行内核", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "关闭后可手动单独启动，省电也省内存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoRun, onCheckedChange = onAutoRunChange)
        }
    }
}

@Composable
private fun SelfTestCard(running: Boolean, output: String, onRun: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("运行时自检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "分别启动 Node 与 CPython，验证版本、文件读写与回环 HTTP。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRun, enabled = !running) {
                Text(if (running) "自检中…" else "运行自检")
            }
            if (output.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalLogSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onLoad: suspend () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(title) { onLoad() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$title · 日志尾部",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { onLoad() } }) { Text("刷新") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = text.ifBlank { "（暂无输出）" },
                modifier = Modifier
                    .height(420.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatMb(kb: Int): String = String.format(Locale.US, "%.0f MB", kb / 1024.0)

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m${seconds}s"
        else -> "${seconds}s"
    }
}
