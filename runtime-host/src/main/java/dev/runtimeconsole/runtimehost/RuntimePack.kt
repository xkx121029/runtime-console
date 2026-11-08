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
    /** 分卷文件名，顺序拼接后是一个完整 zip。 */
    val files: List<String>,
)

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
        )
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

        markerFile.writeText(
            JSONObject()
                .put("packVersion", manifest.packVersion)
                .put("abi", manifest.abi)
                .put("installedAt", System.currentTimeMillis())
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
        staging.deleteRecursively()
        require(staging.mkdirs() || staging.isDirectory) { "无法创建暂存目录 ${staging.name}" }

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
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        if (entryName.isEmpty()) continue
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

        // 原子替换：旧目录先改名，成功后再删；改名失败则恢复
        val backup = File(root, part.target + ".old")
        backup.deleteRecursively()
        if (target.exists() && !target.renameTo(backup)) {
            staging.deleteRecursively()
            throw IllegalStateException("旧目录无法让位：${part.target}")
        }
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            staging.deleteRecursively()
            throw IllegalStateException("无法落位到 ${part.target}")
        }
        backup.deleteRecursively()
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

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
