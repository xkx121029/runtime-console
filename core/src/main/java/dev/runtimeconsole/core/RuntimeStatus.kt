package dev.runtimeconsole.core

/** 运行时种类。 */
enum class RuntimeKind { NODE, PYTHON }

/**
 * 一个运行时的对外状态。
 *
 * @property kind 运行时种类
 * @property installed 运行时文件是否已就位
 * @property running 进程是否在运行
 * @property version 探测到的版本号，未探测为 null
 * @property endpoint 本机监听地址，未监听为 null
 */
data class RuntimeStatus(
    val kind: RuntimeKind,
    val installed: Boolean = false,
    val running: Boolean = false,
    val version: String? = null,
    val endpoint: String? = null,
)
