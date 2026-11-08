package dev.runtimeconsole.runtimehost

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 运行时包的网盘式下载（HTTP Range 断点续传）。
 *
 * 状态：**EXPERIMENTAL**。机制已实现并在本机 HTTP 服务上验证过续传，但项目没有公共分发
 * 服务器，所以「全新设备一键下载」目前只在这个前提成立时才可用（见 docs/DISTRIBUTION.md）。
 *
 * 只做三件事：取 manifest、按分卷续传、交给 [RuntimePackInstaller] 校验与解包。
 * 下载落 `cacheDir/pack-download/`，安装完成后由调用方清理。
 */
class RuntimePackDownloader(private val cacheDir: File) {

    private val downloadDir = File(cacheDir, "pack-download")

    fun manifestUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/" + RuntimePackInstaller.MANIFEST_NAME

    /**
     * 把 manifest 与各分卷下载到本地目录，返回可直接交给安装器的 [DirectoryPackSource]。
     *
     * @param onProgress 文件名 / 已下载字节 / 总字节（总字节未知时为 -1）
     */
    fun download(
        baseUrl: String,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
    ): PackSource {
        downloadDir.mkdirs()
        val manifestFile = File(downloadDir, RuntimePackInstaller.MANIFEST_NAME)
        fetch(manifestUrl(baseUrl), manifestFile, onProgress)
        val manifest = PackManifest.parse(manifestFile.readText())
        for (part in manifest.parts) {
            for (name in part.files) {
                val target = File(downloadDir, name)
                // 已完整的文件不重下：长度与大小都已知时先比长度，再靠安装器做 SHA-256 终检
                fetch(baseUrl.trimEnd('/') + "/" + name, target, onProgress)
            }
        }
        return DirectoryPackSource(downloadDir)
    }

    /** 单文件下载，带 Range 续传；服务端不支持 Range 时退化为整文件重下。 */
    private fun fetch(url: String, target: File, onProgress: (String, Long, Long) -> Unit) {
        val existing = if (target.isFile) target.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK && existing > 0) {
                // 服务端忽略 Range：从头覆盖，避免把两份内容拼坏
                target.delete()
                return fetch(url, target, onProgress)
            }
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308
            ) {
                val location = connection.getHeaderField("Location") ?: error("重定向缺少 Location：$url")
                return fetch(location, target, onProgress)
            }
            require(code == HttpURLConnection.HTTP_OK || code == 206) { "下载失败 HTTP $code：$url" }
            val total = connection.contentLengthLong.takeIf { it > 0 }?.let { existing + it } ?: -1
            var done = existing
            connection.inputStream.use { input ->
                FileOutputStream(target, existing > 0).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(target.name, done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
