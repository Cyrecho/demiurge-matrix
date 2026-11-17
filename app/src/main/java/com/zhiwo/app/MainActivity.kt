package com.zhiwo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.app.data.Settings
import com.zhiwo.app.ui.ChatScreen
import com.zhiwo.app.ui.ImportScreen
import com.zhiwo.app.ui.SettingsScreen
import com.zhiwo.app.ui.ZhiwoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 签名自检：只提示，不阻断。自己重新签名调试时不该被拦下来。
        if (!com.zhiwo.app.data.Integrity.isGenuine(this)) {
            android.widget.Toast.makeText(
                this,
                "注意：这个包的签名和官方不一致，可能被人重新打包过",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        setContent {
            val systemDark = isSystemInDarkTheme()
            // 主题改动时 bump 一下，强制重新读设置
            var themeVersion by remember { mutableIntStateOf(0) }
            val settings = Settings.get(this)
            val dark = remember(themeVersion, systemDark) {
                when (settings.themeMode) {
                    "light" -> false
                    "dark" -> true
                    else -> systemDark
                }
            }

            ZhiwoTheme(dark = dark) {
                val vm: AppViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("chat") }

                BackHandler(enabled = screen != "chat") {
                    screen = "chat"
                }

                when (screen) {
                    "settings" -> SettingsScreen(
                        vm = vm,
                        onBack = { screen = "chat" },
                        onThemeChanged = { themeVersion++ }
                    )

                    "import" -> ImportScreen(
                        vm = vm,
                        onBack = { screen = "chat" },
                        onImported = { id ->
                            vm.openConversation(id)
                            screen = "chat"
                        }
                    )

                    else -> ChatScreen(
                        vm = vm,
                        onNewChat = { vm.newChat() },
                        onOpenImport = { screen = "import" },
                        onOpenSettings = { screen = "settings" }
                    )
                }
            }
        }
    }
}
