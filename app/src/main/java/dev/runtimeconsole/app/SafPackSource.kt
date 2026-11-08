package dev.runtimeconsole.app

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import dev.runtimeconsole.runtimehost.PackSource
import java.io.InputStream

/**
 * 把用户通过系统文件选择器选中的目录包装成 [PackSource]。
 *
 * 走 SAF 而不是直接读 `/sdcard/Download`：Android 11+ 分区存储下，直接按路径读公共目录
 * 需要 `MANAGE_EXTERNAL_STORAGE`（权限过重且上架受限），而 SAF 只需用户点一次授权。
 */
class SafPackSource(
    private val resolver: ContentResolver,
    private val root: DocumentFile,
) : PackSource {

    private val files: List<DocumentFile> = root.listFiles().filter { it.isFile }

    override val label: String get() = root.name ?: "所选目录"

    override fun names(): List<String> = files.mapNotNull { it.name }

    override fun size(name: String): Long = file(name)?.length() ?: -1

    override fun open(name: String): InputStream {
        val document = file(name) ?: error("目录里没有 $name")
        return resolver.openInputStream(document.uri) ?: error("无法读取 $name")
    }

    private fun file(name: String): DocumentFile? = files.firstOrNull { it.name == name }
}
