package dev.runtimeconsole.gateway

/**
 * 本机回环网关占位。
 *
 * 设计约束：只监听 `127.0.0.1`，只做端口编排与健康检查，不改写上游业务。
 */
class LoopbackGateway {
    /** 网关当前对外暴露的基址；M0 阶段尚未监听，返回 null。 */
    fun baseUrl(): String? = null
}
