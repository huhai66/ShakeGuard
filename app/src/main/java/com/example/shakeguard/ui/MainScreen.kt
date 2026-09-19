package com.example.shakeguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shakeguard.R
import com.example.shakeguard.data.AppInfo
import com.example.shakeguard.ui.toy.ToyBox
import com.example.shakeguard.ui.toy.ToyButton
import com.example.shakeguard.ui.toy.ToyDims
import com.example.shakeguard.ui.toy.ToyTheme
import com.example.shakeguard.ui.toy.ToyTile
import com.example.shakeguard.ui.toy.ToyToggle
import com.example.shakeguard.ui.toy.ToyToggleSize
import com.example.shakeguard.ui.toy.drawBrick
import com.example.shakeguard.util.AccessibilityUtil
import com.example.shakeguard.util.rememberAppIcon
import kotlinx.coroutines.delay

/** 列表块间距。积木的硬阴影已经由 ToyBox 内部让出了位置，这里只管视觉节奏。 */
private val ListGap = 10.dp

/**
 * 页面自上而下是一条「先让它能跑，再决定管什么」的顺序：
 * 主开关 → 服务状态 → 后台保活 → 图像识别（唯一的功能选项）→ 管哪些应用。
 *
 * 拉回（自动返回）功能已移除，所以原先那个"两个开关并排"的控件面板不复存在，
 * 剩下的图像识别改回整行布局，注意事项也收回卡片里 —— 少一块积木，页面更短。
 */
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsState()
    val search by viewModel.search.collectAsState()
    val master by viewModel.masterEnabled.collectAsState()
    val enabled by viewModel.enabledPackages.collectAsState()
    val count by viewModel.skipCount.collectAsState()
    val debug by viewModel.debugLines.collectAsState()
    val imageRecog by viewModel.imageRecognitionEnabled.collectAsState()
    val lastEvent by viewModel.lastEventTime.collectAsState()
    val colors = ToyTheme.colors

    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val serviceAlive = nowTick - lastEvent < 10000

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityUtil.isEnabled(context)) }
    var batteryWhitelisted by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var showSystem by remember { mutableStateOf(false) }

    // 返回界面时刷新无障碍开关状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityUtil.isEnabled(context)
                batteryWhitelisted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filtered = remember(apps, search, showSystem) {
        apps.filter { app ->
            (showSystem || !app.isSystem) &&
                (search.isBlank() ||
                    app.label.contains(search, ignoreCase = true) ||
                    app.packageName.contains(search, ignoreCase = true))
        }
    }

    Scaffold(
        containerColor = colors.paper,
        topBar = { ToyHeader() },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        ) {
            // 主控台放第一位：它是这个 App 唯一的"主按钮"，不该被次要选项挤到下面
            item {
                MasterPanel(
                    enabled = master,
                    count = count,
                    onToggle = viewModel::setMasterEnabled,
                )
            }

            // 无障碍服务是"能不能用"的前提，紧跟主开关
            item {
                StatusCard(
                    enabled = accessibilityEnabled,
                    alive = serviceAlive,
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }

            if (!batteryWhitelisted) {
                item { BatteryCard(onAllow = { openBatterySettings(context) }) }
            }

            item {
                ImageRecognitionCard(
                    checked = imageRecog,
                    supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                    onToggle = viewModel::setImageRecognitionEnabled,
                )
            }

            // 从这里往下都是"管哪些应用"，所以紧贴应用列表
            item {
                SearchField(
                    value = search,
                    onValueChange = { viewModel.search.value = it },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ToyDims.SCREEN_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToyButton(
                        text = "显示系统应用",
                        onClick = { showSystem = !showSystem },
                        fill = if (showSystem) colors.grape else colors.brick,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "已选 ${enabled.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.hint,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ToyDims.SCREEN_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(ListGap),
                ) {
                    ToyButton(
                        text = "全部勾选",
                        onClick = {
                            viewModel.setEnabledPackages(
                                (enabled + filtered.map { it.packageName }).toSet()
                            )
                        },
                        fill = colors.brick,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                    ToyButton(
                        text = "全部不选",
                        onClick = { viewModel.setEnabledPackages(emptySet()) },
                        fill = colors.brick,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (apps.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.candy)
                    }
                }
            } else {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        enabled = app.packageName in enabled,
                        onToggle = {
                            viewModel.setEnabled(app.packageName, app.packageName !in enabled)
                        },
                    )
                }
            }

            if (debug.isNotEmpty()) {
                item {
                    DebugSection(lines = debug, onClear = viewModel::clearDebugLines)
                }
            }
        }
    }
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    intent.data = Uri.parse("package:${context.packageName}")
    context.startActivity(intent)
}

/** 顶部标识：糖果色积木里放一枚白色盾牌，复用通知图标那份白色 vector，不用再画一份。 */
@Composable
private fun ToyHeader() {
    val colors = ToyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToyBox(
            modifier = Modifier.size(width = 48.dp, height = 54.dp),
            fill = colors.candy,
            corner = 14.dp,
            depth = 6.dp,
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_notification),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = "AD tool",
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
    }
}

/**
 * 主控台：整屏最厚的一块（[ToyDims.DEPTH_HERO] + 4dp 描边），确立它"主按钮"的地位。
 *
 * 开启时底色换成 candy 的浅底 [ToyColors.mint]，配合糖果色拨杆，状态一眼可辨；
 * 关闭时退回白底 + 灰拨杆。**注意标题不用 candy 当文字色** ——
 * 糖果色是中调色，压在浅底上对比度只有 2:1 上下，大字号也过不了 WCAG。
 */
@Composable
private fun MasterPanel(enabled: Boolean, count: Int, onToggle: (Boolean) -> Unit) {
    val colors = ToyTheme.colors
    ToyBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING),
        fill = if (enabled) colors.mint else colors.brick,
        corner = ToyDims.CORNER_CARD,
        depth = ToyDims.DEPTH_HERO,
        stroke = ToyDims.STROKE_HERO,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "自动跳过广告",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (enabled) colors.ink else colors.hint,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (enabled) "拦截开屏 / 弹窗广告" else "已关闭，不再拦截",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.hint,
                )
            }
            Spacer(Modifier.width(12.dp))
            ToyToggle(
                checked = enabled,
                onCheckedChange = onToggle,
                toggleSize = ToyToggleSize.Large,
            )
        }
        Spacer(Modifier.height(16.dp))
        ScoreWindow(count = count, live = enabled)
    }
}

/** LCD 计分窗：嵌在卡片里的一个小窗，本身不带厚度（它是"凹进去"的，不是另一块积木）。 */
@Composable
private fun ScoreWindow(count: Int, live: Boolean) {
    val colors = ToyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawBrick(
                    fill = colors.brick,
                    shadow = Color.Transparent,
                    outline = colors.ink,
                    corner = 16.dp,
                    depth = 0.dp,
                    stroke = 2.5.dp,
                )
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已跳过",
            style = MaterialTheme.typography.bodySmall,
            color = colors.hint,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.displayLarge,
            color = if (live) colors.ink else colors.hint,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "次",
            style = MaterialTheme.typography.bodySmall,
            color = colors.hint,
        )
    }
}

/**
 * 无障碍服务状态。健康时只有一行字，出问题才冒出「去开启 / 去重启」按钮 ——
 * 一个健康的应用不该在首屏摆一个按钮等着你点。
 */
@Composable
private fun StatusCard(enabled: Boolean, alive: Boolean, onOpenSettings: () -> Unit) {
    val colors = ToyTheme.colors
    val healthy = enabled && alive
    ToyBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING),
        fill = colors.brick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(16.dp)
                    .drawBehind {
                        drawCircle(if (healthy) colors.candy else colors.berry)
                        drawCircle(colors.ink, style = Stroke(2.dp.toPx()))
                    },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "无障碍服务",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(
                    text = when {
                        !enabled -> "未开启，拦截已暂停"
                        alive -> "已开启，正在运行"
                        else -> "已开启但服务未运行"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (healthy) colors.hint else colors.berry,
                )
            }
            if (!healthy) {
                Spacer(Modifier.width(10.dp))
                ToyButton(
                    text = if (enabled) "去重启" else "去开启",
                    onClick = onOpenSettings,
                    fill = colors.candy,
                )
            }
        }
    }
}

/**
 * 图像识别。这是移除拉回之后唯一的功能选项，所以改回整行布局：
 * 左边标题+说明、右边开关，长句有地方落脚，不必再挤在半宽的小积木里。
 *
 * 注意事项做成卡内可展开的一行，而不是单独一块积木 —— 不展开时不占地方，
 * 也省掉页面上一块只写着一句话的积木。
 */
@Composable
private fun ImageRecognitionCard(
    checked: Boolean,
    supported: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = ToyTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    ToyBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING),
        fill = colors.brick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .drawBehind {
                        drawCircle(if (supported) colors.grape else colors.off)
                        drawCircle(colors.ink, style = Stroke(2.dp.toPx()))
                    },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "图像识别",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Black,
                    color = if (supported) colors.ink else colors.hint,
                )
                Text(
                    text = if (supported) "网页广告也能识别" else "需 Android 11 及以上",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.hint,
                )
            }
            Spacer(Modifier.width(10.dp))
            ToyToggle(
                checked = checked,
                onCheckedChange = onToggle,
                toggleSize = ToyToggleSize.Medium,
                enabled = supported,
                accent = colors.grape,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "收起注意事项" else "展开注意事项",
                style = MaterialTheme.typography.bodySmall,
                color = colors.hint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.bodySmall,
                color = colors.hint,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (supported) {
                    "开启后会识别屏幕上的所有「×」并自动点击（包括网页广告里的 ×），" +
                        "可能误点其他形似 × 的内容，请谨慎开启。"
                } else {
                    "当前系统版本过低，暂不支持图像识别（需 Android 11+）。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.hint,
            )
        }
    }
}

@Composable
private fun BatteryCard(onAllow: () -> Unit) {
    val colors = ToyTheme.colors
    ToyBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING),
        fill = colors.brick,
        outline = colors.sun,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "建议：允许后台运行",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "防止系统关闭拦截服务，让拦截更稳定",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.hint,
                )
            }
            Spacer(Modifier.width(12.dp))
            ToyButton(text = "去允许", onClick = onAllow, fill = colors.sun)
        }
    }
}

/** 搜索框。用 BasicTextField 自绘而不用 OutlinedTextField：描边、圆角、光标色全部可控。 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = ToyTheme.colors
    ToyBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING),
        fill = colors.brick,
        corner = 999.dp,
        depth = ToyDims.DEPTH_TILE,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MagnifierIcon(color = colors.hint)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = "搜索应用",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.hint,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.candy),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black,
                        color = colors.hint,
                    )
                }
            }
        }
    }
}

/** 放大镜手绘。不用 emoji：不同 ROM 的字形差别很大，有的还会渲染成彩色。 */
@Composable
private fun MagnifierIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val strokeWidth = size.minDimension * 0.14f
        val radius = size.minDimension * 0.30f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = radius, center = center, style = Stroke(strokeWidth))
        val from = Offset(center.x + radius * 0.72f, center.y + radius * 0.72f)
        drawLine(
            color = color,
            start = from,
            end = Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 应用列表行：扁平贴片 + **整行可点**。
 *
 * 行本身是开关（TalkBack 读作一个开关），行内的拨杆传 `onCheckedChange = null` 变成纯展示，
 * 这样触控面积是整行而不是那颗 64×36 的小拨杆 —— 对中老年用户这点很关键。
 */
@Composable
private fun AppRow(app: AppInfo, enabled: Boolean, onToggle: () -> Unit) {
    val colors = ToyTheme.colors
    val icon = rememberAppIcon(app.packageName)
    ToyTile(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING)
            // 用 toggleable + Role.Switch 而不是 clickable：行本身就充当开关，
            // 这样 TalkBack 播报的是"开关，已开启"而不是含糊的"双击以激活"。
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { onToggle() },
            ),
        fill = if (enabled) colors.mint else colors.brick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .drawBehind {
                        drawBrick(
                            fill = colors.brick,
                            shadow = Color.Transparent,
                            outline = colors.ink,
                            corner = 13.dp,
                            depth = 0.dp,
                            stroke = 2.dp,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (enabled) "会帮您跳广告" else "不处理此应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.hint,
                )
            }
            Spacer(Modifier.width(10.dp))
            ToyToggle(
                checked = enabled,
                onCheckedChange = null,
                toggleSize = ToyToggleSize.Small,
            )
        }
    }
}

@Composable
private fun DebugSection(lines: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    val colors = ToyTheme.colors
    ToyTile(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToyDims.SCREEN_PADDING),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "调试信息",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            ToyButton(
                text = "复制",
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("debug", lines.joinToString("\n")))
                },
                fill = colors.brick,
                depth = 2.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            ToyButton(
                text = "清空",
                onClick = onClear,
                fill = colors.brick,
                depth = 2.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        lines.takeLast(8).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = colors.hint,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
