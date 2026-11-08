package dev.runtimeconsole.core

/**
 * 首装组件（运行时分包）在 UI 上的状态。
 *
 * 运行时不随 APK 分发（体积原因，见 docs/DISTRIBUTION.md）：全新设备需要先导入运行时包，
 * 否则内核无法启动。这里只放展示所需的最小信息，安装逻辑在 runtime-host 模块。
 */
data class RuntimeComponentStatus(
    /** 组件标识，与包 manifest 里的 part id 对齐。 */
    val id: String,
    val title: String,
    val detail: String,
    val installed: Boolean,
    /** 预计占用空间（字节），用于安装前让用户判断存储是否够。 */
    val expectedBytes: Long? = null,
)
