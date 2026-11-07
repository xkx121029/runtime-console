package dev.runtimeconsole.gateway

import dev.runtimeconsole.core.HealthProbe
import dev.runtimeconsole.core.KernelEndpointStatus
import dev.runtimeconsole.core.KernelId
import dev.runtimeconsole.core.KernelState
import dev.runtimeconsole.core.parseDshToken
import dev.runtimeconsole.runtimehost.KernelSupervisor

/**
 * 回环网关：把两个内核的"起停 + 健康状态"收敛成一个入口给 UI 用。
 *
 * 这层**不做反向代理**：dsh 的 Web 控制台需要带 token 直接访问，Octop 的 dashboard 也自带
 * 前端路由，套一层代理只会把 token/Origin 问题变复杂。等需要"一个端口访问两个内核"
 * 时再在这里加路径路由。
 */
class LoopbackGateway(private val supervisor: KernelSupervisor) {

    /** 冷启动超过这个时间还没监听，就从“启动中”改判成“异常”。 */
    private val startGraceMs = 5 * 60_000L

    /** 当前状态；[probe] 为 true 时会真的发一次 HTTP 探活（阻塞，必须在 IO 线程调用）。 */
    fun snapshot(probe: Boolean = true): List<KernelEndpointStatus> =
        KernelId.entries.map { id ->
            val running = supervisor.isRunning(id)
            // 进程可能由 adb exec 起的（调试场景），所以端口通也算在线
            val httpStatus = when {
                !probe -> null
                running -> HealthProbe.httpStatus(id.healthUrl)
                HealthProbe.tcpOpen(id.port) -> HealthProbe.httpStatus(id.healthUrl)
                else -> null
            }
            val startedAt = supervisor.startedAt(id)
            val uptime = startedAt?.let { System.currentTimeMillis() - it }
            val state = when {
                httpStatus != null && running -> KernelState.RUNNING
                httpStatus != null -> KernelState.RUNNING
                running && (uptime ?: 0L) < startGraceMs -> KernelState.STARTING
                running -> KernelState.UNHEALTHY
                else -> KernelState.STOPPED
            }
            KernelEndpointStatus(
                id = id,
                state = state,
                running = running,
                httpStatus = httpStatus,
                startedAt = startedAt,
                pid = supervisor.pid(id),
                rssKb = supervisor.rssKb(id),
                token = if (id == KernelId.DSH_WEB) parseDshToken(supervisor.logFile(id)) else null,
                logPath = supervisor.logFile(id).absolutePath,
                lastError = supervisor.lastFailure(id),
            )
        }

    /** 日志尾部（阻塞 IO，调用方必须在 IO 线程）。 */
    fun logTail(id: KernelId, maxLines: Int = 120): String = supervisor.logTail(id, maxLines)

    /** 拉起内核；失败原样抛出（UI 负责显示原因）。 */
    fun start(id: KernelId) {
        when (id) {
            KernelId.DSH_WEB -> supervisor.startDshWeb()
            KernelId.OCTOP_API -> supervisor.startOctopApi()
        }
    }

    fun stop(id: KernelId) = supervisor.stop(id)

    fun stopAll() = supervisor.stopAll()
}
