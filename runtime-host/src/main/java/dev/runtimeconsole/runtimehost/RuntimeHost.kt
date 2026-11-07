package dev.runtimeconsole.runtimehost

/**
 * 运行时宿主占位实现。
 *
 * M1 起由本类负责：从 APK `lib/` 目录解析 `libnode.so` / `libpython*.so`，
 * 以 `ProcessBuilder` 启动并注入 `HOME` 等环境变量，维护重启与日志。
 */
class RuntimeHost {
    /** 返回当前是否已具备启动运行时的条件；M0 阶段恒为 false。 */
    fun isReady(): Boolean = false
}
