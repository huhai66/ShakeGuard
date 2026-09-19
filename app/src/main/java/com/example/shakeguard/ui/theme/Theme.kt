package com.example.shakeguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.shakeguard.ui.toy.LocalToyColors
import com.example.shakeguard.ui.toy.toyColorsFor

/**
 * Material3 的 colorScheme 现在只剩兜底作用 —— 界面主体走 ToyColors（见 `ui/toy`）。
 *
 * 之所以还留一份而不是删掉：Scaffold、CircularProgressIndicator 这类 M3 组件内部会读它，
 * 留空或不管就会在糖果色界面里冒出默认的紫。对齐成同一套色相即可，不用它做设计决策。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF16C2A3),
    onPrimary = Color(0xFF1B2130),
    background = Color(0xFFEFF5FF),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF232A38),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2FD9B8),
    onPrimary = Color(0xFF0E3A33),
    background = Color(0xFF171C27),
    surface = Color(0xFF242C3B),
    onSurface = Color(0xFFE9EFFA),
)

/**
 * 中老年友好：字号整体偏大，并统一用 Black 字重。
 *
 * 玩具感靠**字重和字号**撑，不靠字体：中文玩具风字体动辄 5–10MB，打进 APK 不划算，
 * 离线构建也引入不了新依赖。所以没有一个字是"换字体换来的"，
 * 全是把系统字体推到 900 字重 + 放大字号 + 收紧/放松字距换来的。
 */
private val AppTypography = Typography(
    // 计分窗里的大数字专用：像玩具上的 LED 计数器
    displayLarge = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Black),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Black),
)

@Composable
fun ShakeGuardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val toy = toyColorsFor(dark)

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
    ) {
        // LocalContentColor 一并接管：Text 不写 color 时默认取它。
        // 不接管的话，深色模式下裸 Text 会拿到它的默认值（纯黑），黑字压深蓝底直接看不见。
        CompositionLocalProvider(
            LocalToyColors provides toy,
            LocalContentColor provides toy.ink,
            content = content,
        )
    }
}
