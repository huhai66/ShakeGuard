package com.example.shakeguard.ui.toy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 积木容器：厚描边 + **无模糊**硬阴影 + 大圆角。
 *
 * 玩具感全靠这条硬阴影 —— Material 的柔和投影是"飘在纸上"，硬阴影是"一块塑料坐在板子上"，
 * 边界清楚。对中老年用户来说，凸起就是能按、边界就是范围，比一层朦胧的灰色好懂得多。
 *
 * [depth] 是通过容器**内部**的 `padding(bottom)` 让出来的，不是外层间距：
 * 阴影向下偏移，外层间距一旦小于 depth 就会被下一块盖住，玩具感直接消失。
 * 让在这里之后，外层只用管正常的块间距（[ToyDims.GAP_SECTION] 之类），不必再算阴影。
 *
 * [sink] 是"按住"时的下沉量，由调用方驱动动画（见 [ToyButton]）。
 */
@Composable
fun ToyBox(
    modifier: Modifier = Modifier,
    fill: Color = ToyTheme.colors.brick,
    corner: Dp = ToyDims.CORNER_CARD,
    depth: Dp = ToyDims.DEPTH,
    stroke: Dp = ToyDims.STROKE,
    outline: Color = ToyTheme.colors.ink,
    sink: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ToyTheme.colors
    Column(
        modifier
            .padding(bottom = depth)
            .drawBehind {
                drawBrick(
                    fill = fill,
                    shadow = colors.shadow,
                    outline = outline,
                    corner = corner,
                    depth = depth,
                    stroke = stroke,
                    sink = sink,
                )
            }
            .padding(contentPadding),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/**
 * 扁平贴片：同一套描边和圆角，但没有厚度。
 *
 * 给应用列表、调试面板这种上百行的地方用。**立体感只留给控件**：如果每行都带硬阴影，
 * 一屏几十条深色投影反而全是噪点，真正该突出的开关就被淹没了。
 */
@Composable
fun ToyTile(
    modifier: Modifier = Modifier,
    fill: Color = ToyTheme.colors.brick,
    corner: Dp = ToyDims.CORNER_TILE,
    stroke: Dp = ToyDims.STROKE,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) = ToyBox(
    modifier = modifier,
    fill = fill,
    corner = corner,
    depth = 0.dp,
    stroke = stroke,
    contentPadding = contentPadding,
    horizontalAlignment = horizontalAlignment,
    content = content,
)

/**
 * 画一块积木：硬阴影 → 面 → 描边，三层。
 *
 * [sink] 让面整体下移、阴影露出的厚度从 depth 收到 depth - sink，而**总高度不变**，
 * 所以按压时布局不会跳。
 */
internal fun DrawScope.drawBrick(
    fill: Color,
    shadow: Color,
    outline: Color,
    corner: Dp,
    depth: Dp,
    stroke: Dp,
    sink: Dp = 0.dp,
) {
    val depthPx = depth.toPx()
    val sinkPx = sink.toPx()
    val strokePx = stroke.toPx()
    val faceHeight = size.height - depthPx
    if (faceHeight <= strokePx || size.width <= strokePx) return

    // 圆角不能超过面高的一半，否则窄块会画成怪形状
    val radius = corner.toPx().coerceAtMost(faceHeight / 2f)

    drawRoundRect(
        color = shadow,
        topLeft = Offset(0f, depthPx),
        size = Size(size.width, faceHeight),
        cornerRadius = CornerRadius(radius),
    )
    drawRoundRect(
        color = fill,
        topLeft = Offset(0f, sinkPx),
        size = Size(size.width, faceHeight),
        cornerRadius = CornerRadius(radius),
    )
    // Stroke 是**居中于路径**的：不内缩半个线宽，描边就会有一半落在边界外被父布局裁掉，
    // 看上去像缺了一边的细线，而不是一圈厚实的塑料边。
    val half = strokePx / 2f
    drawRoundRect(
        color = outline,
        topLeft = Offset(half, sinkPx + half),
        size = Size(size.width - strokePx, faceHeight - strokePx),
        cornerRadius = CornerRadius((radius - half).coerceAtLeast(0f)),
        style = Stroke(width = strokePx),
    )
}
