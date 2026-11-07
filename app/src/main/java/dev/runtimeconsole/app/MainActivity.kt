package dev.runtimeconsole.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.runtimeconsole.bridges.AndroidSystemBridges
import dev.runtimeconsole.core.KernelEndpointStatus
import dev.runtimeconsole.core.KernelId
import dev.runtimeconsole.core.KernelState
import dev.runtimeconsole.gateway.LoopbackGateway
import dev.runtimeconsole.runtimehost.KernelSupervisor
import dev.runtimeconsole.runtimehost.RuntimeHost
import dev.runtimeconsole.ui.RuntimeConsoleApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用唯一入口：把运行时宿主、内核管理与系统桥接注入 UI。 */
class MainActivity : ComponentActivity() {

    private lateinit var host: RuntimeHost
    private lateinit var supervisor: KernelSupervisor
    private lateinit var gateway: LoopbackGateway
    private lateinit var bridges: AndroidSystemBridges

    /** 已请求启动、还没看到端口的标记：UI 用它显示"启动中"。 */
    private val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<KernelId>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = RuntimeHost(applicationContext)
        supervisor = KernelSupervisor(host)
        gateway = LoopbackGateway(supervisor)
        bridges = AndroidSystemBridges(this)

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
                    bridges.openLocalUrl(id.openUrl(token))
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

    companion object {
        private const val PREFS = "runtime-console"
        private const val KEY_AUTO_RUN = "auto-run-kernels"
        const val EXTRA_RUNTIME_SELFTEST = "runtime_selftest"
        const val EXTRA_RUNTIME_DSH = "runtime_dsh"
        const val EXTRA_RUNTIME_OCTOP = "runtime_octop"
    }
}
