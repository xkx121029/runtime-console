package dev.runtimeconsole.bridges

/**
 * 系统桥接占位：通知、文件选择、分享等 Android 能力在此层封装。
 * 上游内核不得直接依赖 Android API，只能通过本层的接口访问。
 */
interface SystemBridges {
    /** 打开一个本机地址（例如内核提供的 Web 控制台）。 */
    fun openLocalUrl(url: String, title: String? = null)
}
