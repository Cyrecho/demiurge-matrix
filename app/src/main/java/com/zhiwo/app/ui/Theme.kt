package com.zhiwo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 颜色全部来自原版安装包里的常量：
 * 亮色 primary #426EFE，暗色 primary #507BF2（原版暗色主题的强调色就是浅一档）
 * 亮色面 #FFFFFF / #F5F5F5 / #EDEDED
 * 暗色面 #1E1E1E / #292929 / #333333 / #3C3C3D
 * 用户气泡用的浅蓝 #EDF3FE / 深蓝 #283142
 */
data class ZhiwoExtra(
    val userBubble: Color,
    val onUserBubble: Color,
    val inputBar: Color,
    val thinkingBg: Color
)

private val LightExtra = ZhiwoExtra(
    userBubble = Color(0xFFEDF3FE),
    onUserBubble = Color(0xFF1E1E1E),
    inputBar = Color(0xFFF5F5F5),
    thinkingBg = Color(0xFFF5F5F5)
)

private val DarkExtra = ZhiwoExtra(
    userBubble = Color(0xFF283142),
    onUserBubble = Color(0xFFF2F5FF),
    inputBar = Color(0xFF292929),
    thinkingBg = Color(0xFF292929)
)

val LocalZhiwoExtra = staticCompositionLocalOf { LightExtra }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF426EFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEEAFF),
    onPrimaryContainer = Color(0xFF33436B),
    secondary = Color(0xFF6799FE),
    onSecondary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1E1E1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFEDEDED),
    error = Color(0xFFD93025)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF507BF2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2F4C8F),
    onPrimaryContainer = Color(0xFFDEEAFF),
    secondary = Color(0xFF6799FE),
    onSecondary = Color(0xFF0E1526),
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF292929),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF3C3C3D),
    error = Color(0xFFFF7A7A)
)

@Composable
fun ZhiwoTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalZhiwoExtra provides if (dark) DarkExtra else LightExtra
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            content = content
        )
    }
}
