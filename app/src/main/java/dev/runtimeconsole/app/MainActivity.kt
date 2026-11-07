package dev.runtimeconsole.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.runtimeconsole.runtimehost.RuntimeHost
import dev.runtimeconsole.ui.RuntimeConsoleApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用唯一入口：只挂载 Compose 根节点并把运行时宿主注入 UI。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = RuntimeHost(applicationContext)
        // 设备侧取证入口：adb shell am start ... --ez runtime_selftest true 可免点击自动自检
        val autoStart = intent?.getBooleanExtra(EXTRA_RUNTIME_SELFTEST, false) == true
        // M2 取证入口：--ez runtime_dsh true 直接拉起 dsh web（日志在 files/logs/dsh-web.log）
        val startDsh = intent?.getBooleanExtra(EXTRA_RUNTIME_DSH, false) == true
        if (startDsh) {
            Thread {
                try {
                    host.prepare()
                    val result = host.runDshWeb()
                    android.util.Log.i("DshLaunch", "exit=${result.exitCode} timeout=${result.timedOut} ms=${result.durationMs}")
                } catch (t: Throwable) {
                    android.util.Log.e("DshLaunch", "启动失败", t)
                }
            }.start()
        }
        setContent {
            RuntimeConsoleApp(
                runtimeReady = host.isReady(),
                autoStart = autoStart,
                runSelfTest = { onProgress ->
                    withContext(Dispatchers.IO) {
                        host.runSelfTest { stage, index, total ->
                            onProgress(if (total > 0) "$stage（$index/$total）" else stage)
                        }
                    }
                },
            )
        }
    }

    companion object {
        const val EXTRA_RUNTIME_SELFTEST = "runtime_selftest"
        const val EXTRA_RUNTIME_DSH = "runtime_dsh"
    }
}
