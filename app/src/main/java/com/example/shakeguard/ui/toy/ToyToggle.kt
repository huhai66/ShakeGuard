package com.example.shakeguard.ui.toy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三档尺寸。Large 给主控台，Medium 给次要开关，Small 给列表行。
 * 三档共用同一套造型，只是大小不同 —— 整个界面只有一种"开关"，用户学一次就够。
 */
enum class ToyToggleSize(
    val trackWidth: Dp,
    val trackHeight: Dp,
    val knob: Dp,
    /** 拨杆离滑轨内壁的留白，同时决定拨杆行程。 */
    val inset: Dp,
    val stroke: Dp,
) {
    Large(112.dp, 60.dp, 48.dp, 5.dp, 3.dp),
    Medium(84.dp, 46.dp, 35.dp, 4.dp, 3.dp),
    Small(64.dp, 36.dp, 27.dp, 3.dp, 2.5.dp),
}

/**
 * 玩具拨杆开关：一根滑轨 + 一颗带自身阴影的圆头拨杆。
 *
 * 和 Material 的 Switch 比，这里刻意做得又大又厚：拨杆自带硬阴影、滑动带一点回弹，
 * 拨起来像在动一个真实的塑料开关。对中老年用户，"开关"的实物经验比扁平滑块好懂得多。
 *
 * [onCheckedChange] 传 `null` 表示**纯展示**：自己不吃点击、也不进无障碍树。
 * 给「整行都是一个开关」的列表行用 —— 那时点击由外层行接管，所以触摸区域能大得多，
 * 也不会出现 TalkBack 在一行里读两个开关的情况。
 */
@Composable
fun ToyToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    toggleSize: ToyToggleSize = ToyToggleSize.Medium,
    enabled: Boolean = true,
    accent: Color = ToyTheme.colors.candy,
) {
    val colors = ToyTheme.colors

    // 行程 = 滑轨内宽 - 拨杆直径
    val travel = toggleSize.trackWidth - toggleSize.inset * 2 - toggleSize.knob
    val knobOffset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        // 阻尼调低一点，滑到底会轻轻顿一下，像拨杆撞到限位
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
    )

    val trackWidth = toggleSize.trackWidth
    val trackHeight = toggleSize.trackHeight
    val knobSize = toggleSize.knob
    val inset = toggleSize.inset
    val strokeWidth = toggleSize.stroke

    val interaction = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier.clearAndSetSemantics { }
    }

    Box(
        modifier
            .size(width = trackWidth, height = trackHeight)
            .then(interaction)
            .drawBehind {
                val radius = trackHeight.toPx() / 2f
                val trackFill = when {
                    !enabled -> colors.off.copy(alpha = 0.5f)
                    checked -> accent
                    else -> colors.off
                }
                drawRoundRect(color = trackFill, cornerRadius = CornerRadius(radius))

                val knobPx = knobSize.toPx()
                val centerX = inset.toPx() + knobOffset.toPx() + knobPx / 2f
                val centerY = inset.toPx() + knobPx / 2f

                // 拨杆自己再压一层硬阴影，圆头才像一根立起来的塑料柱而不是贴纸
                drawCircle(
                    color = colors.shadow.copy(alpha = 0.45f),
                    radius = knobPx / 2f,
                    center = Offset(centerX, centerY + 2.dp.toPx()),
                )
                drawCircle(color = Color.White, radius = knobPx / 2f, center = Offset(centerX, centerY))
                drawCircle(
                    color = colors.ink,
                    radius = knobPx / 2f,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 2.5.dp.toPx()),
                )

                // 滑轨描边最后画，压住拨杆可能溢出的一点边
                drawRoundRect(
                    color = colors.ink,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = strokeWidth.toPx()),
                )
            },
    )
}
