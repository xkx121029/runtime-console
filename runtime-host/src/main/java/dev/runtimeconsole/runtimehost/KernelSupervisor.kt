package dev.runtimeconsole.runtimehost

import dev.runtimeconsole.core.KernelId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 长驻内核进程管理。
 *
 * 与 [RuntimeHost.exec] 的区别：`exec` 是"跑完拿输出"的一次性调用（自检用），
 * 本类负责"起完就返回"的长驻进程，只做四件事：拉起、停止、报出 PID/内存、给出日志位置。
 * 内核的业务逻辑一行都不在这里。
 */
class KernelSupervisor(private val host: RuntimeHost) {

    private data class Running(val process: Process, val startedAt: Long, val pid: Int, val log: File)

    private val running = ConcurrentHashMap<KernelId, Running>()

    /** 进程已退出时保留的失败摘要（UI 直接显示，不用去翻日志）。 */
    private val failures = ConcurrentHashMap<KernelId, String>()

    /** 用户主动停止的标记：用于区分"崩溃"和"正常关停"，避免把主动停止记成失败。 */
    private val stopping = ConcurrentHashMap.newKeySet<KernelId>()

    fun isRunning(id: KernelId): Boolean = running[id]?.process?.isAlive == true

    fun startedAt(id: KernelId): Long? = running[id]?.startedAt

    fun pid(id: KernelId): Int? = running[id]?.takeIf { it.process.isAlive }?.pid

    fun lastFailure(id: KernelId): String? = failures[id]

    fun logFile(id: KernelId): File = File(host.logsDir, id.logName)

    /** 常驻内存（/proc/<pid>/status 的 VmRSS），单位 KB。 */
    fun rssKb(id: KernelId): Int? {
        val pid = pid(id) ?: return null
        val status = File("/proc/$pid/status").takeIf { it.isFile } ?: return null
        return status.useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
    }

    /** 日志尾部若干行，供 UI 的日志面板直接显示。 */
    fun logTail(id: KernelId, maxLines: Int = 120): String = runCatching {
        val log = logFile(id)
        if (!log.isFile) return ""
        val text = log.readText()
        text.lines().takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    /** 拉起 dsh 的 web 内核（非阻塞）。日志中的 token 由 core 层解析。 */
    @Synchronized
    fun startDshWeb(port: Int = KernelId.DSH_WEB.port): File {
        if (isRunning(KernelId.DSH_WEB)) return logFile(KernelId.DSH_WEB)
        host.prepare()
        val dshHome = File(host.home, "dsh")
        val entry = File(dshHome, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        require(entry.exists()) { "未找到 dsh 安装：${entry.absolutePath}" }
        spawn(
            id = KernelId.DSH_WEB,
            command = listOf(
                host.nodeExecutable().absolutePath,
                // 必须带 --expose-internals：见 docs/UPSTREAM.md 补丁 P-01
                "--expose-internals", entry.absolutePath,
                "web", "--no-open", "--host", "127.0.0.1", "--port", port.toString(),
            ),
            workingDir = dshHome,
            extraEnv = mapOf("NODE_ENV" to "production"),
        )
        return logFile(KernelId.DSH_WEB)
    }

    /** 拉起 Octop 的 FastAPI（非阻塞）。 */
    @Synchronized
    fun startOctopApi(port: Int = KernelId.OCTOP_API.port): File {
        if (isRunning(KernelId.OCTOP_API)) return logFile(KernelId.OCTOP_API)
        host.prepare()
        val launcher = File(host.launcherDir, "octop_serve.py")
        require(launcher.exists()) { "未找到 Octop 启动脚本：${launcher.absolutePath}" }
        require(File(host.pythonSiteDir, "octop").isDirectory) {
            "未找到 Octop 安装：${host.pythonSiteDir.absolutePath}（先跑 scripts/octop-runtime.ps1）"
        }
        spawn(
            id = KernelId.OCTOP_API,
            command = listOf(host.pythonExecutable().absolutePath, launcher.absolutePath),
            workingDir = host.home,
            extraEnv = mapOf(
                "OCTOP_HOME" to File(host.home, "octop-home").absolutePath,
                "OCTOP_PORT" to port.toString(),
            ),
        )
        return logFile(KernelId.OCTOP_API)
    }

    @Synchronized
    fun stop(id: KernelId) {
        val entry = running.remove(id) ?: return
        stopping += id
        entry.process.destroy()
        if (!entry.process.waitFor(5, TimeUnit.SECONDS)) {
            entry.process.destroyForcibly()
        }
        stopping -= id
    }

    fun stopAll() {
        KernelId.entries.forEach { stop(it) }
    }

    private fun spawn(id: KernelId, command: List<String>, workingDir: File, extraEnv: Map<String, String>) {
        val log = logFile(id)
        log.parentFile?.mkdirs()
        // 每次启动新开日志：旧日志只保留 .1 一份，够查"上一次为什么起不来"
        if (log.isFile && log.length() > 0) {
            val previous = File(log.parentFile, log.name + ".1")
            log.copyTo(previous, overwrite = true)
        }
        log.writeText("")
        failures.remove(id)
        // 追加而不是覆盖：进程存活期间的输出持续落盘，UI 的日志面板直接读同一个文件
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().apply {
            putAll(host.baseEnv())
            putAll(extraEnv)
        }
        val process = builder.start()
        val pid = pidOf(process)
        running[id] = Running(process, System.currentTimeMillis(), pid, log)
        // 崩溃监听：进程非主动退出时，把日志尾部留给 UI（模拟器上最常见的是 OOM/缺库）
        Thread {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            // 只清掉自己这一份，避免把之后重启的新进程状态抹掉
            running.compute(id) { _, current -> if (current?.process === process) null else current }
            if (id !in stopping) {
                val tail = runCatching {
                    log.readText().takeLast(600).ifBlank { "无输出" }
                }.getOrDefault("日志不可读")
                failures[id] = "进程退出（code=$code）：$tail"
            }
        }.apply { name = "kernel-watch-${id.name}"; isDaemon = true }.start()
    }

    /**
     * Android 的 `java.lang.Process` 没有公开 `pid()`（那是 Java 9 的 API），
     * 只能读实现类的私有字段；拿不到就返回 -1，UI 显示 "—"。
     */
    private fun pidOf(process: Process): Int = runCatching {
        val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
        field.getInt(process)
    }.getOrDefault(-1)
}
