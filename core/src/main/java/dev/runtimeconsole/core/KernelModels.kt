package dev.runtimeconsole.core

import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 两个内核在设备上的固定入口。
 *
 * 端口写死而不是动态分配，原因：
 *  - 内核自身的启动参数/配置里已经写死了端口（dsh `web --port`、uvicorn `Config(port=…)`）；
 *  - 只绑 127.0.0.1，端口冲突只可能来自本机同一 App，错误更容易复现。
 */
enum class KernelId(
    val title: String,
    /** 给用户看的一句话说明（不出现任何上游品牌措辞之外的信息）。 */
    val summary: String,
    val port: Int,
    /** 健康检查路径；返回 2xx/3xx/401/403 都算“服务在线”。 */
    val healthPath: String,
    /** 日志文件名，落在 `filesDir/logs/`。 */
    val logName: String,
) {
    DSH_WEB("dsh 控制台", "Node / TS 内核 · Web 控制台", 3080, "/", "dsh-web.log"),
    OCTOP_API("Octop API", "Python / FastAPI 内核 · 管理接口", 18080, "/api/setup/status", "octop-api.log");

    val baseUrl: String get() = "http://127.0.0.1:$port"
    val healthUrl: String get() = baseUrl + healthPath

    /**
     * 用户点“打开”时真正要访问的地址。
     *
     * dsh 的 Web 控制台必须带一次性 token，否则只会拿到 401；token 由内核打印在日志里，
     * 由 [RuntimeHostSnapshot] 解析出来带到这里。没有 token 时退回裸地址。
     */
    fun openUrl(token: String?): String = when {
        this == DSH_WEB && !token.isNullOrBlank() -> "$baseUrl/?token=$token"
        else -> baseUrl
    }
}

/** 内核生命周期状态（UI 与网关共用）。 */
enum class KernelState { STOPPED, STARTING, RUNNING, UNHEALTHY }

/** 一个内核的当前状态，供 UI 直接渲染。 */
data class KernelEndpointStatus(
    val id: KernelId,
    val state: KernelState = KernelState.STOPPED,
    val running: Boolean = false,
    /** HTTP 状态码；null 表示端口未通或探测失败。 */
    val httpStatus: Int? = null,
    val startedAt: Long? = null,
    val pid: Int? = null,
    /** 常驻内存（/proc/<pid>/status 的 VmRSS），单位 KB。 */
    val rssKb: Int? = null,
    val token: String? = null,
    val logPath: String? = null,
    /** 最近一次启动失败的原因（进程已退出时）。 */
    val lastError: String? = null,
) {
    val uptimeMs: Long? get() = startedAt?.let { System.currentTimeMillis() - it }
    val portOpen: Boolean get() = httpStatus != null
}

/** 回环端口探测。所有方法都是阻塞的，调用方必须在 IO 线程。 */
object HealthProbe {

    /** TCP 端口是否可连。 */
    fun tcpOpen(port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 发一个 GET 并返回状态码。
     *
     * dsh 未带 token 时返回 401，这也说明服务在线，所以不把 401/403 当失败。
     */
    fun httpStatus(url: String, timeoutMs: Int = 1200): Int? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** 拿到任何 HTTP 状态码都说明服务活着（包括 401/403 鉴权拒绝）。 */
    fun isAlive(status: Int?): Boolean = status != null
}

/** 从日志尾部解析 dsh 打印的访问 token（`dsh web: http://…?token=…`）。 */
fun parseDshToken(log: File, tailBytes: Int = 8192): String? {
    if (!log.isFile) return null
    return try {
        val length = log.length()
        val from = maxOf(0L, length - tailBytes)
        val text = java.io.RandomAccessFile(log, "r").use { raf ->
            raf.seek(from)
            ByteArray((length - from).toInt()).let { buffer ->
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }
        Regex("token=([A-Za-z0-9_-]+)").findAll(text).lastOrNull()?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }
}
