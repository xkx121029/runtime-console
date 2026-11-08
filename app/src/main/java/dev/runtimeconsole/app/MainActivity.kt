package dev.runtimeconsole.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import dev.runtimeconsole.bridges.AndroidSystemBridges
import dev.runtimeconsole.core.KernelEndpointStatus
import dev.runtimeconsole.core.KernelId
import dev.runtimeconsole.core.KernelState
import dev.runtimeconsole.core.RuntimeComponentStatus
import dev.runtimeconsole.gateway.LoopbackGateway
import dev.runtimeconsole.runtimehost.DirectoryPackSource
import dev.runtimeconsole.runtimehost.KernelSupervisor
import dev.runtimeconsole.runtimehost.PackSource
import dev.runtimeconsole.runtimehost.RuntimeHost
import dev.runtimeconsole.runtimehost.RuntimePackDownloader
import dev.runtimeconsole.runtimehost.RuntimePackInstaller
import dev.runtimeconsole.ui.RuntimeConsoleApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 应用唯一入口：把运行时宿主、内核管理与系统桥接注入 UI。 */
class MainActivity : ComponentActivity() {

    private lateinit var host: RuntimeHost
    private lateinit var supervisor: KernelSupervisor
    private lateinit var gateway: LoopbackGateway
    private lateinit var bridges: AndroidSystemBridges
    private lateinit var packInstaller: RuntimePackInstaller
    private lateinit var packDownloader: RuntimePackDownloader

    // 首装组件状态：放在 Activity 上而不是 Compose 里，SAF 回调（Compose 外）也要能刷新
    private val packComponents = mutableStateOf<List<RuntimeComponentStatus>>(emptyList())
    private val packBusy = mutableStateOf(false)
    private val packProgress = mutableStateOf("")
    private val packMessage = mutableStateOf<String?>(null)

    /** 用户从系统文件选择器挑运行时包目录（SAF），授权只持续到本次导入。 */
    private val pickPackDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) {
            packMessage.value = "无法读取所选目录"
            return@registerForActivityResult
        }
        runInstall("所选目录") { SafPackSource(contentResolver, root) }
    }

    /** 已请求启动、还没看到端口的标记：UI 用它显示"启动中"。 */
    private val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<KernelId>()

    /** 前台保活服务是否已拉起（避免每 2 秒重复 startForegroundService）。 */
    private var keepAlive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = RuntimeHost(applicationContext)
        supervisor = KernelSupervisor(host)
        gateway = LoopbackGateway(supervisor)
        bridges = AndroidSystemBridges(this)
        packInstaller = RuntimePackInstaller(applicationContext)
        packDownloader = RuntimePackDownloader(cacheDir)
        refreshPackStatus()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContent {
            var kernels by remember { mutableStateOf<List<KernelEndpointStatus>>(emptyList()) }
            var autoRun by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_RUN, true)) }
            var selfTestOutput by remember { mutableStateOf("") }
            var selfTestRunning by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            suspend fun refresh() {
                kernels = withContext(Dispatchers.IO) {
                    runCatching { gateway.snapshot() }.getOrDefault(emptyList()).map { status ->
                        // 已请求启动但端口还没通：对外显示"启动中"，而不是"已停止"
                        if (status.state == KernelState.STOPPED && status.id in requested) {
                            status.copy(state = KernelState.STARTING, startedAt = System.currentTimeMillis())
                        } else {
                            status.also { if (it.state == KernelState.RUNNING) requested.remove(it.id) }
                        }
                    }
                }
                // 内核是 App 进程的子进程：有内核在跑就提成前台服务，避免后台被回收（T-11）
                val active = kernels.any { it.state != KernelState.STOPPED }
                when {
                    active && !keepAlive -> { keepAlive = true; KernelService.start(this@MainActivity) }
                    !active && keepAlive -> { keepAlive = false; KernelService.stop(this@MainActivity) }
                }
            }

            LaunchedEffect(Unit) {
                refresh()
                // 自动拉起：只在还没有进程时执行，失败的单内核不影响另一个
                if (prefs.getBoolean(KEY_AUTO_RUN, true)) {
                    withContext(Dispatchers.IO) {
                        KernelId.entries.forEach { id ->
                            if (!supervisor.isRunning(id)) {
                                requested += id
                                runCatching { gateway.start(id) }
                                    .onFailure { requested.remove(id); android.util.Log.e("KernelLaunch", "启动失败：$id", it) }
                            }
                        }
                    }
                }
                while (true) {
                    delay(2_000)
                    refresh()
                }
            }

            RuntimeConsoleApp(
                runtimeReady = host.isReady(),
                kernels = kernels,
                autoRun = autoRun,
                onAutoRunChange = { enabled ->
                    autoRun = enabled
                    prefs.edit().putBoolean(KEY_AUTO_RUN, enabled).apply()
                    if (enabled) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                KernelId.entries.filter { !supervisor.isRunning(it) }.forEach { id ->
                                    requested += id
                                    runCatching { gateway.start(id) }.onFailure { requested.remove(id) }
                                }
                            }
                            refresh()
                        }
                    }
                },
                onStartKernel = { id ->
                    scope.launch {
                        requested += id
                        withContext(Dispatchers.IO) { runCatching { gateway.start(id) }.onFailure { requested.remove(id) } }
                        refresh()
                    }
                },
                onStopKernel = { id ->
                    scope.launch {
                        requested.remove(id)
                        withContext(Dispatchers.IO) { runCatching { gateway.stop(id) } }
                        refresh()
                    }
                },
                onOpenKernel = { id ->
                    val token = kernels.firstOrNull { it.id == id }?.token
                    bridges.openLocalUrl(id.openUrl(token), id.title)
                },
                loadLog = { id -> withContext(Dispatchers.IO) { gateway.logTail(id, 200) } },
                selfTestOutput = selfTestOutput,
                selfTestRunning = selfTestRunning,
                onRunSelfTest = {
                    if (!selfTestRunning) {
                        selfTestRunning = true
                        scope.launch {
                            var progress = ""
                            selfTestOutput = withContext(Dispatchers.IO) {
                                runCatching {
                                    host.runSelfTest { stage, index, total ->
                                        progress = if (total > 0) "$stage（$index/$total）" else stage
                                    }
                                }.getOrElse { "自检异常：$it" }
                            }
                            selfTestRunning = false
                        }
                    }
                },
                components = packComponents.value,
                packBusy = packBusy.value,
                packProgress = packProgress.value,
                packMessage = packMessage.value,
                onImportPack = { pickPackDirectory.launch(null) },
                onDismissPackMessage = { packMessage.value = null },
                onOpenLicenses = {
                    startActivity(Intent(this@MainActivity, LicensesActivity::class.java))
                },
            )
        }

        // 设备侧取证入口（无需点击）：
        //   --ez runtime_selftest true  跑一次自检（本轮未接 UI，改用按钮）
        //   --ez runtime_dsh true       只拉起 dsh web（127.0.0.1:3080）
        //   --ez runtime_octop true     只拉起 Octop API（127.0.0.1:18080）
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App 在前台时 adb/外部再次投递的意图走这里，不处理就等于什么都没发生（踩过）
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // 首装取证入口：直接把某个目录 / 某个 URL 的运行时包交给安装器
        intent.getStringExtra(EXTRA_PACK_DIR)?.let { path ->
            runInstall("运行时包目录") { DirectoryPackSource(File(path)) }
            return
        }
        intent.getStringExtra(EXTRA_PACK_URL)?.let { url ->
            runInstall("运行时包下载") { packDownloader.download(url) { name, done, total -> reportPackProgress(name, done, total) } }
            return
        }
        // 回滚到上一版本（升级出问题时不用重新导入 1.5 GB 的包）
        if (intent.getBooleanExtra(EXTRA_PACK_ROLLBACK, false)) {
            runRollback()
            return
        }
        // 取证入口：不依赖内核状态直接打开 WebView，用来验证"内核未起"的错误页
        if (intent.getBooleanExtra(EXTRA_RUNTIME_WEBVIEW, false)) {
            startActivity(
                Intent(this, KernelWebViewActivity::class.java)
                    .putExtra(KernelWebViewActivity.EXTRA_URL, KernelId.DSH_WEB.baseUrl)
                    .putExtra(KernelWebViewActivity.EXTRA_TITLE, KernelId.DSH_WEB.title),
            )
            return
        }
        val wantDsh = intent.getBooleanExtra(EXTRA_RUNTIME_DSH, false)
        val wantOctop = intent.getBooleanExtra(EXTRA_RUNTIME_OCTOP, false)
        if (!wantDsh && !wantOctop) return
        Thread {
            runCatching { host.prepare() }
            listOfNotNull(
                KernelId.DSH_WEB.takeIf { wantDsh },
                KernelId.OCTOP_API.takeIf { wantOctop },
            ).forEach { id ->
                requested += id
                runCatching { gateway.start(id) }
                    .onFailure { requested.remove(id); android.util.Log.e("KernelLaunch", "启动失败：$id", it) }
            }
        }.start()
    }

    /** 首装组件状态：只读目录，开销可以忽略（每 2s 随内核状态一起刷新）。 */
    private fun refreshPackStatus() {
        val status = runCatching { packInstaller.status() }.getOrNull() ?: return
        packComponents.value = listOf(
            RuntimeComponentStatus(
                id = "home/dsh",
                title = "Node 运行时（Web 控制台）",
                detail = "解包后约 700 MB · 运行时包提供",
                installed = status.components["home/dsh"] == true,
                expectedBytes = 701L * 1024 * 1024,
            ),
            RuntimeComponentStatus(
                id = "python-site",
                title = "Python 运行时（管理接口）",
                detail = "解包后约 800 MB · 运行时包提供",
                installed = status.components["python-site"] == true,
                expectedBytes = 803L * 1024 * 1024,
            ),
        )
    }

    /**
     * 统一的首装入口：校验 + 解包都在后台线程，进度节流后回主线程刷新。
     *
     * @param sourceProvider 在后台线程执行，允许先下载再返回来源
     */
    private fun runInstall(label: String, sourceProvider: () -> PackSource) {
        if (packBusy.value) {
            packMessage.value = "已有一次导入在进行"
            return
        }
        packBusy.value = true
        packMessage.value = null
        packProgress.value = "读取$label…"
        Thread {
            val result = runCatching {
                val source = sourceProvider()
                packInstaller.install(source) { stage, done, total ->
                    reportPackProgress(stage, done, total)
                }
            }.getOrElse { RuntimePackInstaller.Result(false, "安装异常：${it.message}") }
            runOnUiThread {
                packBusy.value = false
                packProgress.value = ""
                packMessage.value = result.message
                refreshPackStatus()
            }
        }.apply { name = "runtime-pack-install"; isDaemon = true }.start()
    }

    private fun runRollback() {
        if (packBusy.value) {
            packMessage.value = "已有一次导入在进行"
            return
        }
        packBusy.value = true
        packMessage.value = null
        packProgress.value = "回滚中…"
        Thread {
            val result = runCatching { packInstaller.rollback() }
                .getOrElse { RuntimePackInstaller.Result(false, "回滚异常：${it.message}") }
            runOnUiThread {
                packBusy.value = false
                packProgress.value = ""
                packMessage.value = result.message
                refreshPackStatus()
            }
        }.apply { name = "runtime-pack-rollback"; isDaemon = true }.start()
    }

    private var lastProgressAt = 0L

    /** 大包解压时进度回调非常密集，节流到 250ms 一次，避免主线程被刷爆。 */
    private fun reportPackProgress(stage: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < 250) return
        lastProgressAt = now
        val text = if (total > 0) {
            String.format(Locale.US, "%s · %d%%（%s / %s）", stage, done * 100 / total,
                RuntimePackInstaller.formatBytes(done), RuntimePackInstaller.formatBytes(total))
        } else {
            stage
        }
        runOnUiThread { packProgress.value = text }
    }

    companion object {
        private const val PREFS = "runtime-console"
        private const val KEY_AUTO_RUN = "auto-run-kernels"
        const val EXTRA_RUNTIME_SELFTEST = "runtime_selftest"
        const val EXTRA_RUNTIME_DSH = "runtime_dsh"
        const val EXTRA_RUNTIME_OCTOP = "runtime_octop"
        const val EXTRA_RUNTIME_WEBVIEW = "runtime_webview"
        const val EXTRA_PACK_DIR = "runtime_pack_dir"
        const val EXTRA_PACK_URL = "runtime_pack_url"
        const val EXTRA_PACK_ROLLBACK = "runtime_pack_rollback"
    }
}
