package dev.runtimeconsole.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.runtimeconsole.ui.RuntimeConsoleApp

/** 应用唯一入口：只负责挂载 Compose 根节点，不含业务逻辑。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RuntimeConsoleApp()
        }
    }
}
