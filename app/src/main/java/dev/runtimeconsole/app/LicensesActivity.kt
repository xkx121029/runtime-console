package dev.runtimeconsole.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.runtimeconsole.ui.RuntimeConsoleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 开源许可页：离线展示 LICENSE 与第三方组件清单，全文可选、可一键复制。
 *
 * 两份文件由 Gradle 的 `copyLicenseAssets` 从仓库根目录复制进 assets，
 * 保证 App 里看到的内容与仓库里的完全一致，不需要联网。
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RuntimeConsoleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LicensesScreen(onClose = { finish() })
                }
            }
        }
    }
}

private sealed interface LicenseDoc {
    val assetName: String
    val title: String
}

private object LicenseText : LicenseDoc {
    override val assetName = "licenses/LICENSE.txt"
    override val title = "本应用许可证（MIT）"
}

private object NoticesText : LicenseDoc {
    override val assetName = "licenses/THIRD_PARTY_NOTICES.txt"
    override val title = "第三方组件与许可证"
}

@Composable
private fun LicensesScreen(onClose: () -> Unit) {
    var doc by remember { mutableStateOf<LicenseDoc>(LicenseText) }
    var body by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(doc) {
        loading = true
        body = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(doc.assetName).bufferedReader().use { it.readText() }
            }.getOrElse { "读取失败：${it.message}" }
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "开源许可",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { doc = LicenseText }) { Text("本应用") }
            TextButton(onClick = { doc = NoticesText }) { Text("第三方组件") }
            Text(
                text = if (loading) "读取中…" else "${body.length} 字符",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            TextButton(
                onClick = {
                    // 剪贴板是唯一"导出"能力：用户可以把全文粘到任何地方做合规留档
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(doc.title, body))
                },
                enabled = !loading && body.isNotEmpty(),
            ) { Text("复制全文") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(doc.title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
