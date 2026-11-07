package dev.runtimeconsole.bridges

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 系统桥接的 Android 实现。 */
class AndroidSystemBridges(private val context: Context) : SystemBridges {

    /** 用系统浏览器打开回环地址（设备上只有本机能访问，用外部浏览器即可）。 */
    override fun openLocalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
