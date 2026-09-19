package com.example.shakeguard.ui.toy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 玩具积木风调色板。
 *
 * 不走 Material3 的 colorScheme：玩具风里「颜色 + 圆角 + 厚度 + 硬阴影」是一套绑死的造型语言，
 * 拆进 primary / primaryContainer / surfaceVariant 这些语义槽位反而对不上号
 * —— Theme.kt 里原来那段注释已经踩过一次（M3 默认 primaryContainer 是淡紫，
 * 拿它当选中底色就出「淡紫底 + 青绿字」）。
 *
 * 每个糖果色都对应一个具体功能，颜色本身就是信息：
 * candy = 主开关、grape = 图像识别、sun = 后台保活提醒、berry = 出问题了。
 *
 * **糖果色是填充色，不当文字色。** 它们都是中调色，压在浅底上对比度只有 2:1 上下，
 * 大字号也过不了 WCAG。文字只有两个选择：[ink] 和 [hint]；
 * 真要往糖果底上放字，用 [on] 取色（它会自动挑深墨或白）。
 */
@Immutable
data class ToyColors(
    /** 描边与主文字。深色模式下反转为亮色——同一槽位兼作"墨线"和"正文"。 */
    val ink: Color,
    /** 无模糊硬阴影。深色下用比底色更深的黑，保持"嵌进板子"的感觉。 */
    val shadow: Color,
    /** 页面底：浅色是淡蓝塑料板，深色是深蓝塑料板。 */
    val paper: Color,
    /** 积木面。 */
    val brick: Color,
    /** 主色，总开关 ON。 */
    val candy: Color,
    /** candy 的浅底，用于选中态。 */
    val mint: Color,
    val sun: Color,
    val grape: Color,
    /** 异常 / 未开启 / 警示。 */
    val berry: Color,
    /** 关闭态的拨杆滑轨。 */
    val off: Color,
    /** 次级文字。 */
    val hint: Color,
) {
    /**
     * 某个色面上的文字色：亮底配深墨、暗底配白。
     *
     * 这样以后加新颜色不用同步补一对 onXxx 槽位，也不会出现「糖果底 + 灰字」这种看不清的组合。
     */
    fun on(fill: Color): Color =
        if (fill.luminance() > 0.55f) INK_ON_BRIGHT else Color.White

    private companion object {
        val INK_ON_BRIGHT = Color(0xFF1B2130)
    }
}

private val LightToy = ToyColors(
    ink = Color(0xFF232A38),
    shadow = Color(0xFF232A38),
    paper = Color(0xFFEFF5FF),
    brick = Color(0xFFFFFFFF),
    candy = Color(0xFF16C2A3),
    mint = Color(0xFFE4F7F3),
    sun = Color(0xFFFFC42E),
    grape = Color(0xFF8C7BFF),
    berry = Color(0xFFFF5A6E),
    off = Color(0xFFD8DEE8),
    hint = Color(0xFF5B6675),
)

private val DarkToy = ToyColors(
    ink = Color(0xFFE9EFFA),
    shadow = Color(0xFF05070C),
    paper = Color(0xFF171C27),
    brick = Color(0xFF242C3B),
    candy = Color(0xFF2FD9B8),
    mint = Color(0xFF1E3A38),
    sun = Color(0xFFFFD24D),
    grape = Color(0xFFA795FF),
    berry = Color(0xFFFF7A8A),
    off = Color(0xFF3A4356),
    hint = Color(0xFF9AA6B6),
)

val LocalToyColors = staticCompositionLocalOf { LightToy }

internal fun toyColorsFor(dark: Boolean): ToyColors = if (dark) DarkToy else LightToy

object ToyTheme {
    val colors: ToyColors
        @Composable
        @ReadOnlyComposable
        get() = LocalToyColors.current
}

/**
 * 尺寸常量。层级用**厚度**表达——越重要的块越厚，比 Material 的 elevation 直观得多：
 * 主控台 8dp、普通积木 6dp、贴片按钮 3dp、列表行 0（扁平）。
 */
object ToyDims {
    val CORNER_CARD = 26.dp
    val CORNER_TILE = 18.dp
    val STROKE = 3.dp
    val STROKE_HERO = 4.dp
    val DEPTH_HERO = 8.dp
    val DEPTH = 6.dp
    val DEPTH_TILE = 3.dp

    /** 大块之间的间距。**必须大于块的 depth**，否则硬阴影会被下一块盖掉。 */
    val GAP_SECTION = 18.dp
    val GAP_ROW = 8.dp
    val SCREEN_PADDING = 16.dp
}
