package com.example.shakeguard.ui.toy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 可按压的积木按钮：按住时整块沉下去、阴影从 [depth] 收到 1dp，松开弹回。
 *
 * 替代 Material 的水波纹。水波纹是"从手指处扩散的涟漪"，下沉是"按钮被按进去了"——
 * 后者才是玩具的物理直觉，反馈也更明确（整块在动，不是一小圈光）。
 *
 * 最小高度锁 48dp（无障碍触控下限），实际因为字号大，通常远超。
 */
@Composable
fun ToyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = ToyTheme.colors.candy,
    depth: Dp = ToyDims.DEPTH_TILE,
    corner: Dp = 999.dp,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
) {
    val colors = ToyTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 下沉用短 tween 而不是 spring：按下要立刻跟手，回弹留给出更"重"的控件
    val sink by animateDpAsState(
        targetValue = if (pressed && enabled) depth - 1.dp else 0.dp,
        animationSpec = tween(durationMillis = 70),
    )
    val faceFill = if (enabled) fill else colors.off

    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .padding(bottom = depth)
            .drawBehind {
                drawBrick(
                    fill = faceFill,
                    shadow = colors.shadow,
                    outline = colors.ink,
                    corner = corner,
                    depth = depth,
                    stroke = ToyDims.STROKE,
                    sink = sink,
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) colors.on(faceFill) else colors.hint,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
        )
    }
}
