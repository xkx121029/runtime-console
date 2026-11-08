package dev.runtimeconsole.runtimehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归测试：zip 条目分隔符归一化。
 *
 * 背景：用 .NET Framework 打的包条目名是 `node_modules\.bin\acorn`，
 * 直接落盘会把 69k 个文件压平在根目录，dsh 启动失败（见 docs/STATE.md）。
 */
class RuntimePackNamingTest {

    @Test
    fun `反斜杠分隔符归一化为斜杠`() {
        assertEquals("node_modules/.bin/acorn", normalizeEntryName("node_modules\\.bin\\acorn"))
    }

    @Test
    fun `正斜杠保持不变`() {
        assertEquals("node_modules/.bin/acorn", normalizeEntryName("node_modules/.bin/acorn"))
    }

    @Test
    fun `去掉开头的斜杠`() {
        assertEquals("home/dsh/bin.js", normalizeEntryName("/home/dsh/bin.js"))
    }

    @Test
    fun `空条目名返回 null`() {
        assertNull(normalizeEntryName(""))
        assertNull(normalizeEntryName("/"))
    }

    @Test
    fun `保留向上跳转的片段交给调用方做越界校验`() {
        // 这里不做安全判断：install() 之后用 canonicalPath 前缀检查拦截逃逸
        assertEquals("../evil.txt", normalizeEntryName("..\\evil.txt"))
    }
}
