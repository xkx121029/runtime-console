package dev.runtimeconsole.runtimehost

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行时宿主：负责把 APK 里打包的 Node / CPython 启动起来。
 *
 * 设计约束（来自项目硬性要求）：
 *  - 二进制只从 `applicationInfo.nativeLibraryDir`（APK 的 lib/<abi>/）执行，
 *    不在 app data 目录 exec；
 *  - 所有数据写入 `filesDir`，通过 `HOME` / `TMPDIR` 等环境变量注入；
 *  - 只监听 127.0.0.1（由脚本/内核自身保证，宿主不提供局域网开关）。
 *
 * 文件布局：
 * ```
 * filesDir/home/                      HOME
 * filesDir/runtime/python/            PYTHONHOME（内含 lib/python3.14）
 * filesDir/runtime/selftest/          自检脚本
 * filesDir/logs/                      每个进程一份日志
 * cacheDir/tmp/                       TMPDIR
 * ```
 */
class RuntimeHost(private val context: Context) {

    /** 一次子进程执行的结果。 */
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val durationMs: Long,
        val timedOut: Boolean,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    val home: File get() = File(context.filesDir, "home")
    val runtimeDir: File get() = File(context.filesDir, "runtime")
    val pythonHome: File get() = File(runtimeDir, "python")
    val compatLibsDir: File get() = File(runtimeDir, "lib")
    val selftestDir: File get() = File(runtimeDir, "selftest")
    val logsDir: File get() = File(context.filesDir, "logs")
    val tmpDir: File get() = File(context.cacheDir, "tmp")

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /** Node 可执行文件（APK lib/ 内的 `libnode_exec.so`）。 */
    fun nodeExecutable(): File = File(nativeDir, "libnode_exec.so")

    /** CPython 可执行文件（APK lib/ 内的 `libpython_exec.so`）。 */
    fun pythonExecutable(): File = File(nativeDir, "libpython_exec.so")

    /** 运行时文件是否随 APK 一起安装到位。 */
    fun isReady(): Boolean = nodeExecutable().canExecute() && pythonExecutable().canExecute()

    /**
     * 准备目录结构与随包资源。幂等，可在每次启动时调用。
     *
     * @param onProgress 进度回调（阶段名，当前/总数），用于界面显示
     */
    fun prepare(onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }) {
        listOf(home, runtimeDir, pythonHome, compatLibsDir, selftestDir, logsDir, tmpDir).forEach { it.mkdirs() }
        stagePythonStdlib(onProgress)
        stageCompatLibs()
        stageSelftest()
    }

    /**
     * 解压保留原始 soname 的兼容库（`libz.so.1` 等）。
     *
     * 这些库不能放进 APK 的 `lib/<abi>/`（那里只认 `lib*.so`），但 Python 的 C 扩展在
     * DT_NEEDED 里写死了版本化名字，所以落到 `filesDir/runtime/lib` 并加进 `LD_LIBRARY_PATH`。
     */
    private fun stageCompatLibs() {
        val names = context.assets.list("runtime-libs") ?: emptyArray()
        for (name in names) {
            val target = File(compatLibsDir, name)
            if (target.exists() && target.length() > 0) continue
            context.assets.open("runtime-libs/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun stagePythonStdlib(onProgress: (String, Int, Int) -> Unit) {
        val stdlib = File(pythonHome, "lib/python3.14")
        if (File(stdlib, "os.py").exists()) return
        val files = mutableListOf<Pair<String, File>>()
        collectAssets("python-stdlib", File(pythonHome, "lib/python3.14"), files)
        files.forEachIndexed { index, (assetPath, target) ->
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            onProgress("解压 Python 标准库", index + 1, files.size)
        }
        Log.i(TAG, "Python 标准库解压完成：${files.size} 个文件")
    }

    private fun collectAssets(assetPath: String, target: File, out: MutableList<Pair<String, File>>) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            out += assetPath to target
            return
        }
        for (child in children) {
            collectAssets("$assetPath/$child", File(target, child), out)
        }
    }

    private fun stageSelftest() {
        for (name in listOf("hello.js", "hello.py")) {
            val target = File(selftestDir, name)
            if (target.exists()) continue
            context.assets.open("selftest/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    /** 执行 Node 脚本。 */
    fun runNode(script: File, args: List<String> = emptyList(), timeoutSec: Long = 120): CommandResult =
        exec(nodeExecutable(), listOf(script.absolutePath) + args, mapOf(), timeoutSec, "node.log")

    /** 执行 Python 脚本。 */
    fun runPython(script: File, args: List<String> = emptyList(), timeoutSec: Long = 120): CommandResult =
        exec(
            pythonExecutable(),
            listOf(script.absolutePath) + args,
            mapOf("PYTHONHOME" to pythonHome.absolutePath),
            timeoutSec,
            "python.log",
        )

    /**
     * 启动任意运行时命令（M2 启动内核时使用）。
     *
     * @param logName 日志文件名，落在 `filesDir/logs/`
     */
    fun exec(
        binary: File,
        args: List<String>,
        extraEnv: Map<String, String>,
        timeoutSec: Long,
        logName: String,
    ): CommandResult {
        require(binary.exists()) { "运行时文件不存在：${binary.absolutePath}" }
        val command = mutableListOf(binary.absolutePath).apply { addAll(args) }
        val builder = ProcessBuilder(command)
            .directory(home)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", home.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("LD_LIBRARY_PATH", "${nativeDir.absolutePath}:${compatLibsDir.absolutePath}")
            put("PYTHONDONTWRITEBYTECODE", "1")
            putAll(extraEnv)
        }

        val startedAt = System.currentTimeMillis()
        val process = builder.start()
        val output = StringBuilder()
        val logFile = File(logsDir, logName)
        logFile.parentFile?.mkdirs()
        val logWriter = logFile.outputStream().bufferedWriter()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        logWriter.appendLine(line)
                    }
                }
            }
            runCatching { logWriter.flush() }
        }
        reader.start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000)
        runCatching { logWriter.close() }
        val duration = System.currentTimeMillis() - startedAt
        if (!finished) output.appendLine("RUNTIME_TIMEOUT=${timeoutSec}s")
        return CommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = output.toString(),
            durationMs = duration,
            timedOut = !finished,
        )
    }

    /**
     * M1 自检：启动 Node 与 Python 各自的 hello 脚本，返回逐行输出。
     *
     * 这是"从 APK lib/ 启动运行时"的唯一证据来源；任何一行 FAILED 都必须如实保留。
     */
    fun runSelfTest(onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): String {
        prepare(onProgress)
        val report = StringBuilder()
        onProgress("启动 Node 自检", 1, 2)
        val node = runNode(File(selftestDir, "hello.js"), timeoutSec = 120)
        report.appendLine("=== Node 自检 ===")
        report.append(node.output)
        report.appendLine("NODE_EXIT=${node.exitCode} NODE_MS=${node.durationMs} TIMEOUT=${node.timedOut}")
        onProgress("启动 Python 自检", 2, 2)
        val python = runPython(File(selftestDir, "hello.py"), timeoutSec = 120)
        report.appendLine("=== Python 自检 ===")
        report.append(python.output)
        report.appendLine("PYTHON_EXIT=${python.exitCode} PYTHON_MS=${python.durationMs} TIMEOUT=${python.timedOut}")
        return report.toString()
    }

    companion object {
        private const val TAG = "RuntimeHost"
    }
}
