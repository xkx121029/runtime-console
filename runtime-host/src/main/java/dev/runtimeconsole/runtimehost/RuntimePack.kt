package dev.runtimeconsole.runtimehost

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 首装分包（runtime pack）的读取、校验与安装。
 *
 * 背景（T-10）：两个内核的依赖（Node 侧 node_modules 约 700 MB、Python 侧 python-site
 * 约 800 MB）无法塞进 APK，也不能靠手工 adb 命令完成首装。这里定义一个可分发、可校验、
 * 可断点续传的分包格式：
 *
 * ```
 * runtime-pack-x86_64/
 *   manifest.json
 *   dsh-x86_64-000.zip        # 分卷（按字节切分，顺序拼接即为完整 zip）
 *   python-site-x86_64-000.zip
 * ```
 *
 * `manifest.json` 里每个 part 声明目标目录、原始大小、SHA-256 与分卷文件名；安装器逐卷
 * 拼接校验后解压到 `filesDir/<target>`，先把内容解到 `<target>.staging` 再原子替换，
 * 校验失败时旧目录一个字节都不动。
 *
 * 约束：
 *  - 只写 app 私有目录，不申请存储权限；
 *  - 只做解包与校验，不执行包内任何文件；
 *  - 路径穿越（`..`、绝对路径）一律拒绝。
 */
data class PackPart(
    val id: String,
    val title: String,
    /** 相对 filesDir 的目标目录，例如 `python-site`、`home/dsh`。 */
    val target: String,
    /** 压缩后总字节数；-1 表示包内未声明。 */
    val bytes: Long,
    val sha256: String,
    /** 分卷文件名，顺序拼接后是一个完整 zip / tar / tar.gz。 */
    val files: List<String>,
    /**
     * 包格式：`zip`（默认，兼容旧包）、`tar`、`tar.gz`。
     *
     * 为什么加 tar：现有 ZipInputStream 逐条目解包实测只有 0.4–0.5 MB/s，
     * 全量 69k 文件要 20 分钟；设备自带 toybox `tar` 解同一棵树是 6.6 MB/s
     * （见 docs/DISTRIBUTION.md）。tar 走内核侧一条流，不在 Java 里逐条目建对象。
     */
    val format: String = FORMAT_ZIP,
) {
    companion object {
        const val FORMAT_ZIP = "zip"
        const val FORMAT_TAR = "tar"
        const val FORMAT_TAR_GZ = "tar.gz"
    }
}

data class PackManifest(
    val packVersion: String,
    val abi: String,
    val parts: List<PackPart>,
) {
    companion object {
        fun parse(text: String): PackManifest {
            val root = JSONObject(text)
            val schema = root.optInt("schema", 1)
            require(schema == 1) { "不支持的包格式：schema=$schema" }
            val array = root.getJSONArray("parts")
            val parts = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val filesArray = item.getJSONArray("files")
                PackPart(
                    id = item.getString("id"),
                    title = item.optString("title", item.getString("id")),
                    target = item.getString("target"),
                    bytes = item.optLong("bytes", -1),
                    sha256 = item.getString("sha256").lowercase(),
                    files = (0 until filesArray.length()).map { filesArray.getString(it) },
                    format = item.optString("format", PackPart.FORMAT_ZIP).lowercase().let {
                        if (it == "tgz") PackPart.FORMAT_TAR_GZ else it
                    }.also {
                        require(it in setOf(PackPart.FORMAT_ZIP, PackPart.FORMAT_TAR, PackPart.FORMAT_TAR_GZ)) {
                            "不支持的包格式：$it"
                        }
                    },
                )
            }
            return PackManifest(
                packVersion = root.optString("packVersion", "unknown"),
                abi = root.optString("abi", ""),
                parts = parts,
            )
        }
    }
}

/**
 * 分包的来源抽象：本地目录、SAF 目录（用户从「下载」里选的文件夹）、HTTP 下载缓存都实现它。
 */
interface PackSource {
    val label: String
    fun names(): List<String>
    fun size(name: String): Long
    fun open(name: String): InputStream
}

class DirectoryPackSource(private val dir: File) : PackSource {
    override val label: String get() = dir.absolutePath
    override fun names(): List<String> = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    override fun size(name: String): Long = File(dir, name).length()
    override fun open(name: String): InputStream = File(dir, name).inputStream()
}

class RuntimePackInstaller(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val message: String,
        val manifest: PackManifest? = null,
        val installed: List<String> = emptyList(),
    )

    data class Status(
        val packVersion: String?,
        val installedAt: Long?,
        val components: Map<String, Boolean>,
        /** 磁盘上保留的上一版本号；为空表示没有可回滚的版本。 */
        val prevPackVersion: String? = null,
    )

    /** 安装器认识的目标目录（顺序即 UI 展示顺序）。 */
    private val knownTargets = listOf("home/dsh", "python-site")

    private val filesDir: File get() = context.filesDir
    private val markerFile: File get() = File(filesDir, "runtime-pack.json")

    /** 已安装组件：目录存在且非空才算（不依赖 marker，脚本侧载的旧安装也认）。 */
    fun status(): Status {
        val components = knownTargets.associateWith { target ->
            val dir = File(filesDir, target)
            dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
        }
        val marker = runCatching {
            if (markerFile.isFile) JSONObject(markerFile.readText()) else null
        }.getOrNull()
        return Status(
            packVersion = marker?.optString("packVersion")?.takeIf { it.isNotBlank() },
            installedAt = marker?.optLong("installedAt")?.takeIf { it > 0 },
            components = components,
            prevPackVersion = marker?.optString("prevPackVersion")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 回滚到上一版本：把当前目录挪开、把 `<target>.prev` 换回来。
     *
     * 只在每个目标都存在 `.prev` 时才动手；中途任意一步失败都会把已经换过的目录还原，
     * 保证不会出现"一半新一半旧"的半死状态。
     */
    fun rollback(): Result {
        val marker = runCatching {
            if (markerFile.isFile) JSONObject(markerFile.readText()) else null
        }.getOrNull() ?: return Result(false, "还没有安装记录")
        val prevVersion = marker.optString("prevPackVersion")
        if (prevVersion.isBlank()) return Result(false, "没有可回滚的上一版本")

        val root = filesDir.canonicalFile
        val targets = marker.optString("targets")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { knownTargets }
        val missing = targets.filter { !File(root, it + PREV_SUFFIX).isDirectory }
        if (missing.isNotEmpty()) {
            return Result(false, "上一版本目录不完整：${missing.joinToString()}")
        }

        val swapped = mutableListOf<Pair<File, File>>()
        try {
            for (targetPath in targets) {
                val target = File(root, targetPath)
                val prev = File(root, targetPath + PREV_SUFFIX)
                val aside = File(root, targetPath + ".rollback")
                aside.deleteRecursively()
                if (target.exists() && !target.renameTo(aside)) {
                    throw IllegalStateException("当前版本无法让位：$targetPath")
                }
                if (!prev.renameTo(target)) {
                    if (aside.exists()) aside.renameTo(target)
                    throw IllegalStateException("上一版本无法落位：$targetPath")
                }
                swapped += aside to target
            }
        } catch (e: Exception) {
            // 逆序还原已经换过的目标，回到回滚前的状态
            for ((aside, target) in swapped.asReversed()) {
                target.renameTo(File(root, target.name + PREV_SUFFIX))
                aside.renameTo(target)
            }
            return Result(false, "回滚失败：${e.message}")
        }
        swapped.forEach { (aside, _) -> aside.deleteRecursively() }

        markerFile.writeText(
            JSONObject()
                .put("packVersion", prevVersion)
                .put("prevPackVersion", "")
                .put("abi", marker.optString("abi"))
                .put("installedAt", System.currentTimeMillis())
                .put("targets", targets.joinToString(","))
                .put("parts", marker.optString("parts"))
                .toString(2),
        )
        Log.i(TAG, "已回滚到 $prevVersion")
        return Result(true, "已回滚到 $prevVersion")
    }

    /**
     * 从 [source] 安装整包。阻塞调用，必须在 IO 线程执行。
     *
     * @param onProgress 阶段名 / 已处理字节 / 总字节；解压阶段的总量是压缩后大小，仅作进度参考
     */
    fun install(source: PackSource, onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): Result {
        val names = source.names()
        val manifestName = names.firstOrNull { it.equals(MANIFEST_NAME, ignoreCase = true) }
            ?: return Result(false, "所选目录里没有 $MANIFEST_NAME")
        val manifest = runCatching {
            PackManifest.parse(source.open(manifestName).use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrElse { return Result(false, "$MANIFEST_NAME 解析失败：${it.message}") }

        val deviceAbis = Build.SUPPORTED_ABIS.toSet()
        if (manifest.abi.isNotBlank() && manifest.abi !in deviceAbis) {
            return Result(false, "该包面向 ${manifest.abi}，本机是 ${Build.SUPPORTED_ABIS.firstOrNull()}", manifest)
        }

        val declaredBytes = manifest.parts.sumOf { it.bytes.coerceAtLeast(0) }
        val available = StatFs(filesDir.absolutePath).availableBytes
        // 解包期间同时存在 staging 与旧目录，留 15% 余量；只有声明了大小才预检
        if (declaredBytes > 0 && available < declaredBytes + declaredBytes / 6) {
            return Result(
                false,
                "存储空间不足：包 ${formatBytes(declaredBytes)}，可用 ${formatBytes(available)}",
                manifest,
            )
        }

        val installed = mutableListOf<String>()
        for (part in manifest.parts) {
            val partStarted = System.currentTimeMillis()
            val missing = part.files.filter { it !in names }
            if (missing.isNotEmpty()) {
                return Result(false, "缺少分卷：${missing.joinToString()}", manifest, installed)
            }
            onProgress("校验 ${part.title}", 0, part.files.sumOf { source.size(it).coerceAtLeast(0) })
            val verifyStarted = System.currentTimeMillis()
            val actual = sha256(source, part.files) { done, total -> onProgress("校验 ${part.title}", done, total) }
            if (actual != part.sha256) {
                return Result(false, "${part.id} 校验失败：期望 ${part.sha256.take(12)}…，实际 ${actual.take(12)}…", manifest, installed)
            }
            val verifyMs = System.currentTimeMillis() - verifyStarted
            runCatching { installPart(part, source, onProgress) }
                .onFailure { return Result(false, "${part.id} 解压失败：${it.message}", manifest, installed) }
            Log.i(
                TAG,
                "${part.id} 安装完成：校验 ${verifyMs}ms，总计 ${System.currentTimeMillis() - partStarted}ms，压缩 ${part.bytes} B",
            )
            installed += part.id
        }

        // 只有每个目标都真的留下了 .prev 才把上一版本号写进 marker，
        // 否则 rollback() 会在清点目录时失败，不如一开始就不显示可回滚。
        val previous = runCatching {
            if (markerFile.isFile) JSONObject(markerFile.readText()) else null
        }.getOrNull()
        val previousVersion = previous?.optString("packVersion")
            ?.takeIf { it.isNotBlank() && it != manifest.packVersion }
            ?.takeIf { manifest.parts.all { part -> File(filesDir, part.target + PREV_SUFFIX).isDirectory } }
            .orEmpty()

        markerFile.writeText(
            JSONObject()
                .put("packVersion", manifest.packVersion)
                .put("prevPackVersion", previousVersion)
                .put("abi", manifest.abi)
                .put("installedAt", System.currentTimeMillis())
                .put("targets", manifest.parts.map { it.target }.joinToString(","))
                .put("parts", manifest.parts.map { it.id }.joinToString(","))
                .toString(2),
        )
        Log.i(TAG, "运行时包安装完成：${manifest.packVersion} / ${installed.joinToString()}")
        return Result(true, "安装完成：${manifest.packVersion}", manifest, installed)
    }

    private fun installPart(part: PackPart, source: PackSource, onProgress: (String, Long, Long) -> Unit) {
        val root = filesDir.canonicalFile
        val target = File(root, part.target)
        val staging = File(root, part.target + ".staging")
        // 上一次安装保留下来的旧版本先清掉：本次成功后会把它换成"当前版本"，
        // 这样任意时刻磁盘上最多只有 staging + 当前 + 上一版，不会越积越多。
        File(root, part.target + PREV_SUFFIX).deleteRecursively()
        staging.deleteRecursively()
        require(staging.mkdirs() || staging.isDirectory) { "无法创建暂存目录 ${staging.name}" }

        if (part.format == PackPart.FORMAT_TAR || part.format == PackPart.FORMAT_TAR_GZ) {
            extractTar(part, source, staging, onProgress)
            finalizePart(root, part, target, staging)
            return
        }

        // 多分卷先拼成一个临时 zip（ZipInputStream 不支持跨文件），装完立即删除
        val concatenated = if (part.files.size > 1) File(context.cacheDir, "pack-${part.id}.zip") else null
        if (concatenated != null) {
            concatenated.delete()
            val total = part.files.sumOf { source.size(it).coerceAtLeast(0) }
            var done = 0L
            concatenated.outputStream().use { out ->
                for (name in part.files) {
                    source.open(name).use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            done += read
                            onProgress("拼接 ${part.title}", done, total)
                        }
                    }
                }
            }
        }

        val totalBytes = part.bytes.coerceAtLeast(0)
        var lastEmit = 0L
        val raw = if (concatenated != null) concatenated.inputStream() else source.open(part.files.single())
        // 单块缓冲区跨条目复用：copyTo(bufSize) 会给每个条目新分配一块，6 万个小文件下来
        // 会制造 GB 级短命对象，实测能把解包速度压到 20 文件/秒（见 docs/DISTRIBUTION.md）
        val copyBuffer = ByteArray(BUFFER)
        try {
            CountingInputStream(raw).use { counting ->
                ZipInputStream(counting.buffered(), Charsets.UTF_8).use { zip ->
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // zip 规范用 "/"，但 .NET Framework / 部分打包工具写的是 "\"。
                        // Linux 把 "\" 当普通字符，会把整棵目录树写成一堆扁平文件（实测 dsh 无法启动），
                        // 所以这里统一归一化，旧包也不会白导入。
                        val entryName = normalizeEntryName(entry.name) ?: continue
                        val outFile = File(staging, entryName)
                        if (!outFile.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            throw IllegalStateException("包内路径非法：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().buffered(BUFFER).use { out ->
                                while (true) {
                                    val read = zip.read(copyBuffer)
                                    if (read <= 0) break
                                    out.write(copyBuffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                        entries++
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250) {
                            lastEmit = now
                            onProgress("解压 ${part.title}（$entries 项）", counting.count, totalBytes)
                        }
                    }
                    onProgress("解压 ${part.title}（$entries 项）", counting.count, totalBytes)
                }
            }
        } finally {
            concatenated?.delete()
        }

        finalizePart(root, part, target, staging)
    }

    /**
     * 把 staging 目录原子地换到位：旧目录改名成 `<target>.prev` 保留，用于回滚。
     *
     * 不再像早期实现那样把旧目录删掉——用户升级失败时可以直接回滚到上一版，
     * 不必重新导入整个 1.5 GB 运行时包（T-12）。
     */
    private fun finalizePart(root: File, part: PackPart, target: File, staging: File) {
        val prev = File(root, part.target + PREV_SUFFIX)
        prev.deleteRecursively()
        if (target.exists() && !target.renameTo(prev)) {
            staging.deleteRecursively()
            throw IllegalStateException("旧目录无法让位：${part.target}")
        }
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) {
            if (prev.exists()) prev.renameTo(target)
            staging.deleteRecursively()
            throw IllegalStateException("无法落位到 ${part.target}")
        }
    }

    /**
     * 单条 tar 流解包：把分卷字节直接灌给系统 `toybox tar` 的 stdin。
     *
     * 这样绕开 ZipInputStream 的逐条目 Java 对象创建——实测设备上 zip 路径
     * 0.4–0.5 MB/s，tar 路径 6.6 MB/s（同一棵 133 MB 的树）。
     *
     * 路径穿越由 toybox 自己拦：它默认拒绝解到 `-C` 目录之外（实测 `../evil.txt`
     * 会报 "not under ..." 且不落盘）。注意不能用 `--restrict`——那个选项会把
     * 锚点设成归档里第一个目录，多根归档（node_modules/a、node_modules/b）会大面积失败。
     * `-o` 忽略包里记录的 uid/gid（应用进程也无权 chown）。
     */
    private fun extractTar(
        part: PackPart,
        source: PackSource,
        staging: File,
        onProgress: (String, Long, Long) -> Unit,
    ) {
        val gzip = part.format == PackPart.FORMAT_TAR_GZ
        val command = listOf(
            "/system/bin/tar",
            if (gzip) "-xzf" else "-xf",
            "-", "-C", staging.absolutePath,
            "-o",
        )
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            throw IllegalStateException("无法启动系统 tar：${e.message}")
        }
        val stderr = StringBuilder()
        val drain = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    if (stderr.length < 4000) stderr.appendLine(it)
                }
            }
        }.apply { isDaemon = true; start() }

        val total = part.files.sumOf { source.size(it).coerceAtLeast(0) }
        var done = 0L
        var lastEmit = 0L
        try {
            process.outputStream.buffered(BUFFER).use { out ->
                val buffer = ByteArray(BUFFER)
                for (name in part.files) {
                    source.open(name).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 250) {
                                lastEmit = now
                                onProgress("解包 ${part.title}", done, total)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // tar 提前退出（例如压缩流损坏）会让写 stdin 抛 broken pipe；
            // 真正的错误原因在 stderr 与退出码里，下面统一报。
        }
        val code = process.waitFor()
        drain.join(2000)
        if (code != 0) {
            throw IllegalStateException("tar 退出码 $code：${stderr.toString().trim().take(300)}")
        }
        if (staging.listFiles().isNullOrEmpty()) {
            throw IllegalStateException("tar 解包结果为空")
        }
        onProgress("解包 ${part.title}", total, total)
    }

    private fun sha256(
        source: PackSource,
        files: List<String>,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = files.sumOf { source.size(it).coerceAtLeast(0) }
        var done = 0L
        val buffer = ByteArray(BUFFER)
        for (name in files) {
            source.open(name).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = inner.close()
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        private const val TAG = "RuntimePack"
        private const val BUFFER = 128 * 1024
        /** 上一版本目录后缀（保留用于回滚）。 */
        private const val PREV_SUFFIX = ".prev"

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/**
 * 把 zip 条目名归一化成相对路径。
 *
 * zip 规范用 "/"，但 .NET Framework 的 `ZipFile.CreateFromDirectory` 写的是 "\"。
 * Linux 把 "\" 当普通字符，直接落盘会把整棵目录树压成一层扁平文件
 * （实测 69k 个条目全在根目录，dsh 启动即失败）。空名返回 null（跳过）。
 */
internal fun normalizeEntryName(raw: String): String? {
    val name = raw.replace('\\', '/').trimStart('/')
    return name.ifEmpty { null }
}
