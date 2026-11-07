package dev.runtimeconsole.bridges

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 系统桥接的 Android 实现。 */
class AndroidSystemBridges(private val context: Context) : SystemBridges {

    /**
     * 在应用内 WebView 打开回环地址。
     *
     * 用 `ComponentName` 而不是直接引用 Activity 类：bridges 模块不能反向依赖 app 模块
     * （那会形成循环），类名在这里是唯一的耦合点。
     */
    override fun openLocalUrl(url: String, title: String?) {
        val intent = Intent().apply {
            component = ComponentName(context.packageName, WEB_VIEW_ACTIVITY)
            putExtra("kernel_url", url)
            putExtra("kernel_title", title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private companion object {
        const val WEB_VIEW_ACTIVITY = "dev.runtimeconsole.app.KernelWebViewActivity"
    }
}
