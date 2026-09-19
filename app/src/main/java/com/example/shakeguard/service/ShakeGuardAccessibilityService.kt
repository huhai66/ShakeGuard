package com.example.shakeguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.example.shakeguard.data.SkipSettings
import com.example.shakeguard.util.ImageRecognizer
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 核心：监听窗口/内容变化，自动点击广告/会员弹窗的“跳过 / 关闭 / 知道了 / 暂不”等按钮。
 */
class ShakeGuardAccessibilityService : AccessibilityService() {

    private lateinit var settings: SkipSettings

    private var currentPkg = ""
    private var currentIsSystemApp = true
    private var lastAttempt = 0L
    private var lastClickTime = 0L
    private var lastDebugTime = 0L
    private var lastScreenshotTime = 0L
    private var lastDiagLog = 0L

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SkipSettings.get(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (::settings.isInitialized) {
            settings.onEvent()
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(event)
        }
    }

    private fun onWindowChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 本应用自己

        // 上一个窗口是否为「已勾选的应用」：用于下面判断“是不是刚从一个被监控应用切走”
        var previousWasMonitored = false

        if (pkg != currentPkg) {
            val previous = currentPkg
            previousWasMonitored = previous.isNotEmpty() && shouldSkip(previous)
            currentPkg = pkg
            // 与 currentPkg 就地绑定，避免出现“包名已换成新的、系统应用标记还是上一个应用”的中间态
            currentIsSystemApp = isSystemApp(pkg)
            // 换了新窗口就重置诊断节流：否则广告页的第一次扫描会被上一屏的节流吃掉，
            // 调试面板里留下的全是上一个页面的噪音，根本看不到广告那一刻的节点树。
            lastDebugTime = 0L
            lastDiagLog = 0L
            lastScreenshotTime = 0L
        }

        if (!shouldSkip(pkg)) {
            // 从「已勾选的应用」切到一个「未勾选的窗口」，正是广告浮层另起窗口的形态。
            // 这条路径原本完全静默，加一条好判断广告是不是根本没走到拦截逻辑。
            if (previousWasMonitored) logDiag("切到未勾选窗口：$pkg")
            return
        }

        lastAttempt = 0L
        lastClickTime = 0L
        attemptClick(pkg)
    }

    private fun onContentChanged(event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < REARM_MS) return
        if (now - lastAttempt < THROTTLE_MS) return
        lastAttempt = now
        attemptClick(event.packageName?.toString())
    }

    private fun shouldSkip(pkg: String): Boolean {
        if (!settings.masterEnabled.value) return false
        if (!settings.enabledPackages.value.contains(pkg)) return false
        return true
    }

    /** [eventPkg] 为触发本次尝试的事件所携带的包名，可能为空。 */
    private fun attemptClick(eventPkg: String?) {
        if (!shouldSkip(currentPkg)) return

        val root = rootInActiveWindow

        // 双重确认当前前台包名：活动窗口根节点最可靠，取不到时退回事件自带的包名。
        val foreground = root?.packageName?.toString()?.takeIf { it.isNotEmpty() }
            ?: eventPkg?.takeIf { it.isNotEmpty() }

        // 只在「前台确实是系统窗口」时收手（通知栏、系统弹窗、电源菜单……），其余一律放行。
        //
        // 这里原本要求包名与 currentPkg 严格相等，那会把「广告另起一个包名的浮层窗口」整条链掐死——
        // attemptClick 直接 return，连截图都不会触发。而实测识别对这些广告本来是有效的
        // （抖音悬浮球的 × 得分 0.86，连旧的尺寸表都能过阈），所以卡住的从来不是识别能力，
        // 是这道校验本身。放宽成“只拦系统窗口”既能保住当初要防的误点通知栏，又不挡广告。
        //
        // 注意：currentPkg 只在 WINDOW_STATE_CHANGED 时更新，不要在这里刷新它：
        // currentIsSystemApp 是跟它绑定的一对，分开更新会留下“新包名 + 旧系统标记”的中间态；
        // onWindowChanged 里判断“刚从哪个应用切走”也依赖它。
        if (foreground != null && foreground != currentPkg && isSystemApp(foreground)) {
            Log.d(TAG, "跳过：前台是系统窗口 $foreground")
            logDiag("拦下：前台是系统窗口 $foreground")
            return
        }

        if (root != null) {
            val result = scanAndClick(root, currentIsSystemApp)
            if (result.clicked) {
                lastClickTime = SystemClock.elapsedRealtime()
                settings.onSkipped()
                settings.addDebugLine("点击成功: $currentPkg | ${result.clickedNode}")
                Log.d(TAG, "已点击: $currentPkg | ${result.clickedNode}")
                return
            }
            // 「匹配到但点不动」和「根本没匹配上」要调的东西完全不同，分开报
            if (result.unclickableTargets.isNotEmpty()) {
                settings.addDebugLine("匹配到但点不动: ${result.unclickableTargets.joinToString(" , ")}")
            }
            if (result.candidates.isNotEmpty()) maybeLogDebug(foreground, result.candidates)
        }
        // 节点树未命中时，尝试图像识别兜底（针对网页广告里的 ×）：此处已确认前台就是被监控应用
        attemptImageRecognition()
    }

    private fun attemptImageRecognition() {
        if (!settings.imageRecognitionEnabled.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenshotRecognition()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotRecognition() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotTime < SCREENSHOT_THROTTLE_MS) return
        lastScreenshotTime = now

        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = screenshotToBitmap(screenshot)
                if (bitmap == null) {
                    logDiag("图像识别：截图转位图失败")
                    return
                }
                val result = ImageRecognizer.findCloseButtons(bitmap)
                // 把截图的实际像素尺寸一起打出来：点击坐标是按这份位图算的，
                // 只有知道位图多大，才能和屏幕上 × 的真实位置换算对比、判断落点偏了多少。
                val shot = "${bitmap.width}x${bitmap.height}"
                if (result.hits.isEmpty()) {
                    // 分数和"哪个字形给的"都是排查关键：接近 0.8 说明模板形态差一点，
                    // 很低说明根本没匹配上；而最高分来自「画×」还是「X」指向不同的修法。
                    logDiag("图像识别未命中，最高 %.2f(%s) [图 $shot]".format(result.topScore, result.topGlyph))
                    return
                }
                // 命中的全部列出（不只是要点的那个）：能看出是不是"旁边还有个更像 × 的"被漏掉，
                // 以及每个命中的分数是勉强过阈还是稳稳命中、命中的是哪种字形。
                settings.addDebugLine(
                    "图像识别命中 [图 $shot] " + result.hits.take(MAX_HITS_LOGGED)
                        .joinToString(" , ") { "${it.glyph}(${it.x},${it.y})%.2f/t${it.size}".format(it.score) }
                )
                val hit = result.hits.first()
                Log.d(TAG, "图像识别命中: $currentPkg @ (${hit.x}, ${hit.y}) 分 ${hit.score}")
                mainHandler.post { tap(hit.x.toFloat(), hit.y.toFloat()) }
            }

            override fun onFailure(errorCode: Int) {
                logDiag("图像识别：截图失败 code=$errorCode")
                Log.d(TAG, "截图失败 code=$errorCode")
            }
        })
    }

    /**
     * 拦截原因原本只打在 Log.d 上，不开 adb 完全看不到，于是“屏幕上明明有 × 却关不掉”
     * 永远查不出卡在哪一步。这里节流后同步一条到界面调试面板。
     *
     * 覆盖两类：包名校验拦截、图像识别的截图/匹配结果。
     */
    private fun logDiag(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagLog < DIAG_LOG_MS) return
        lastDiagLog = now
        settings.addDebugLine(reason)
    }

    /**
     * 把截图转成软件位图。任何一步失败都会把**具体原因**写进调试面板——
     * 原来整体包在 runCatching 里返回 null，面板只看到一句“截图转位图失败”，
     * 既不知道是取 buffer 失败、wrap 返回 null 还是 copy 失败，白查了很久。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotToBitmap(screenshot: ScreenshotResult): Bitmap? {
        val hb = hardwareBufferOf(screenshot)
        if (hb == null) {
            logDiag("截图转位图失败：取 HardwareBuffer 失败")
            return null
        }
        val wrapped = runCatching { Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace) }
            .onFailure { Log.e(TAG, "wrapHardwareBuffer 抛异常", it) }
            .getOrNull()
        if (wrapped == null) {
            logDiag("截图转位图失败：wrap 返回 null（fmt=${hb.format} usage=${hb.usage}）")
            hb.close()
            return null
        }
        val copied = runCatching { wrapped.copy(Bitmap.Config.ARGB_8888, false) }
            .onFailure { Log.e(TAG, "copy 软件位图失败", it) }
            .getOrNull()
        hb.close()
        if (copied == null) logDiag("截图转位图失败：copy 返回 null")
        return copied
    }

    /**
     * 取 HardwareBuffer：**先走公开 getter，失败才退回反射**。
     *
     * 原实现在 API 30-33 上直接跳进反射读私有字段 `mHardwareBuffer`——而 getHardwareBuffer()
     * 从 API 30 起就是公开的。白白绕开公开 API 去碰私有字段，正好撞上隐藏 API 限制。
     * 若反射这条路真的被系统挡掉，下面会把异常名打出来，便于判断。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun hardwareBufferOf(screenshot: ScreenshotResult): HardwareBuffer? {
        runCatching { screenshot.hardwareBuffer }.getOrNull()?.let { return it }
        return runCatching {
            val field = ScreenshotResult::class.java.getDeclaredField("mHardwareBuffer")
            field.isAccessible = true
            field.get(screenshot) as HardwareBuffer
        }.onFailure {
            Log.e(TAG, "反射取 mHardwareBuffer 失败", it)
            logDiag("取 buffer：${it.javaClass.simpleName} ${it.message?.take(40)}")
        }.getOrNull()
    }

    /**
     * 这里曾经有一版「拿命中坐标反查节点、对它 performAction」的实现，**已回退**。
     *
     * 理由是它造成了"原本点得掉的广告变成点不掉"：performAction(ACTION_CLICK) 的返回值
     * 只表示动作**被派发**，不表示控件真的响应 —— 自绘控件、SurfaceView、只处理 onTouchEvent
     * 的关闭按钮会收下这个动作然后什么都不做，而 performClick 返回 true 后就不会再退回
     * dispatchGesture，于是那一下彻底落空。dispatchGesture 发的是真实触摸事件，兼容性好得多。
     *
     * 若将来还要做"吸附到节点"以提升精度，正确做法是**用节点的中心坐标去 tap**，
     * 而不是把触摸换成 ACTION_CLICK —— 精度和兼容性才能兼得。
     */
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        if (runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)) {
            lastClickTime = SystemClock.elapsedRealtime()
            settings.onSkipped()
            settings.addDebugLine("图像识别点击: $currentPkg @ (${x.toInt()}, ${y.toInt()})")
            Log.d(TAG, "图像识别已点击: $currentPkg @ (${x.toInt()}, ${y.toInt()})")
        }
    }

    /**
     * 带上 sys / fg 两个标记，因为它们各自能一句话解释掉一整类“什么都不点”：
     *  - `sys=true` 时 isTargetNode 会关掉「关闭 / ×」整类匹配，一旦对第三方应用判错，
     *    表现就是弹窗和 × 全都不点，而原来的日志里看不出任何异常；
     *  - `fg` 与 currentPkg 不一致说明广告另起了窗口，扫描用的却是另一个包名的节点树。
     */
    private fun maybeLogDebug(foreground: String?, candidates: List<String>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugTime < DEBUG_THROTTLE_MS) return
        lastDebugTime = now
        settings.addDebugLine(
            "$currentPkg sys=$currentIsSystemApp fg=$foreground win=[${windowSummary()}] | " +
                candidates.joinToString(" , ")
        )
    }

    /**
     * 当前所有窗口的「包名/类型」，带 `*` 的是活动窗口。
     *
     * 这一条直接回答“广告是不是挂在另一个窗口上”：如果列表里出现了被监控应用之外的窗口
     * （尤其是它自己的 app 窗口之外还有一个），而 fg 指向的是底下那个界面，
     * 那就说明 rootInActiveWindow 扫错了树 —— 这跟“匹配规则不灵”是完全不同的病。
     */
    private fun windowSummary(): String = runCatching {
        windows.joinToString(",") { w ->
            val pkgName = w.root?.packageName?.toString() ?: "?"
            val kind = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "sys"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"
                else -> "t${w.type}"
            }
            if (w.isActive) "$pkgName/$kind*" else "$pkgName/$kind"
        }
    }.getOrDefault("?")

    private data class ScanResult(
        val clicked: Boolean,
        val clickedNode: String,
        val candidates: List<String>,
        /** 命中了 isTargetNode、但所有点击动作都失败的目标。 */
        val unclickableTargets: List<String>,
    )

    /** 遍历节点树：优先点“自身可点击且匹配”的按钮，其次点文本节点向上最近的可点击父节点。 */
    private fun scanAndClick(root: AccessibilityNodeInfo, isSystemApp: Boolean): ScanResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<String>()
        val clickableTargets = ArrayList<AccessibilityNodeInfo>()
        val textTargets = ArrayList<AccessibilityNodeInfo>()
        queue.addLast(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            probeLabel(node)?.let { if (candidates.size < MAX_CANDIDATES) candidates.add(it) }

            if (node.isVisibleToUser && isTargetNode(node, isSystemApp)) {
                if (node.isClickable) clickableTargets.add(node) else textTargets.add(node)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }

        // 优先：自身可点击的按钮（最可靠）
        for (node in clickableTargets) {
            if (performClick(node)) {
                return ScanResult(true, nodeLabel(node), candidates, emptyList())
            }
        }
        // 其次：文本节点向上找最近可点击父节点（最多 3 层，避免点到整个广告容器）
        for (node in textTargets) {
            if (clickNearestClickable(node)) {
                return ScanResult(true, nodeLabel(node), candidates, emptyList())
            }
        }
        val stuck = (clickableTargets + textTargets).map { nodeLabel(it).take(40) }
        return ScanResult(false, "", candidates, stuck)
    }

    /**
     * 探测标签：只收「文案很短」的节点，并标出它自己可不可点、可不可见。
     *
     * 这里刻意**不按 isClickable 过滤**——旧版 dump 只收可点击节点，恰好把最该看的一类全滤掉了：
     * 广告的「跳过 / × / 关闭」经常自己不可点击，靠向上找可点击父节点来点。于是
     * “屏幕上明明有跳过却不点”在日志里永远查不出原因。
     *
     * 三个信息各对应一种完全不同的故障：
     *  - 压根不出现 → 无障碍读不到这棵树（SurfaceView / 独立窗口），只能靠图像识别兜底
     *  - 出现但标了「不可点」→ 要么向上找不到可点击父节点，要么层数超过 MAX_UP_LEVELS
     *  - 出现且可点 → 那是 isTargetNode 没匹配上，改匹配规则
     */
    private fun probeLabel(node: AccessibilityNodeInfo): String? {
        val raw = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (raw != null) {
            val text = raw.trim()
            if (text.length > MAX_PROBE_TEXT) return null
            return buildString {
                append(text)
                if (!node.isClickable) append("·不可点")
                if (!node.isVisibleToUser) append("·不可见")
            }
        }
        // 没有文案的可点击小节点 = 自绘「×」的典型形态，必须探。
        // 上一版把探针从「可点击节点」换成「短文案节点」时，恰好把这一类漏掉了 ——
        // 于是「× 到底在不在树里」这个问题在日志里彻底看不见，只能靠猜。
        // 只收小尺寸：大块的是广告容器，点它等于点「跳到落地页」。
        if (!node.isClickable || !node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > MAX_PROBE_SIDE || bounds.height() > MAX_PROBE_SIDE) return null
        return "<空@${bounds.centerX()},${bounds.centerY()}>"
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

    private fun clickNearestClickable(node: AccessibilityNodeInfo?): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth <= MAX_UP_LEVELS) {
            if (cur.isClickable && performClick(cur)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty().substringAfterLast('.')
        val id = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return listOf(cls, id, text, desc).filter { it.isNotBlank() }.joinToString(" | ")
    }

    private fun isTargetNode(node: AccessibilityNodeInfo, isSystemApp: Boolean): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val isImage = node.className?.toString()?.contains("Image", ignoreCase = true) == true

        // 跳过类：所有应用、任何时刻
        if (text.contains("跳过") || text.equals("skip", ignoreCase = true)) return true
        if (desc.contains("跳过") || desc.contains("skip", ignoreCase = true)) return true
        if (id.contains("skip", ignoreCase = true)) return true

        // 关闭类：仅第三方应用（避免误点系统里的“×”“删除”等按钮）
        if (!isSystemApp) {
            if (text.contains("关闭")) return true
            if (desc.contains("关闭") || desc.contains("close", ignoreCase = true)) return true
            if (isLoneCloseGlyph(text) || isLoneCloseGlyph(desc)) return true
            if (id.contains("close", ignoreCase = true)) return true
            if (text in DEFER_TEXTS) return true
            // 广告 SDK 的“×”关闭按钮常是小图标，id 含 interstitial/suspend 但不一定含 close
            if (isImage && (id.contains("interstitial", ignoreCase = true) || id.contains("suspend", ignoreCase = true))) return true
        }
        return false
    }

    /**
     * 现在每个 content-changed 事件都会问到，加一层缓存避免每次都走一次 packageManager 的 binder 调用。
     * 只在无障碍事件线程（主线程）访问，无需加锁。
     */
    private val systemAppCache = HashMap<String, Boolean>()

    /**
     * 「×」类关闭按钮的文案通常**就只有这一个字符**（至多带个空格）。
     *
     * 这里不能用 contains：商品标题里的尺寸「60×120cm」同样含 ×，
     * 实测会把商城的商品卡片当成关闭按钮点掉，可能直接把用户带进下单页。
     */
    private fun isLoneCloseGlyph(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.length <= 2 && t.any { it in CLOSE_GLYPHS }
    }

    private fun isSystemApp(pkg: String): Boolean = systemAppCache.getOrPut(pkg) {
        runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(true)
    }

    override fun onDestroy() {
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 无需处理
    }

    private companion object {
        const val TAG = "ShakeGuardService"
        const val THROTTLE_MS = 300L
        const val REARM_MS = 3000L
        const val DEBUG_THROTTLE_MS = 3000L
        const val MAX_UP_LEVELS = 3
        const val MAX_CANDIDATES = 40

        /** 探测只收这么短的文案。跳过 / × / 关闭 / 知道了 这类按钮文案都很短。 */
        const val MAX_PROBE_TEXT = 8

        /** 调试面板里每次最多列几个图像识别命中。 */
        const val MAX_HITS_LOGGED = 4

        /** 探针里「无文案可点小节点」的边长上限（像素）。超过这个尺寸的多半是容器而非按钮。 */
        const val MAX_PROBE_SIDE = 320
        const val SCREENSHOT_THROTTLE_MS = 2000L
        const val DIAG_LOG_MS = 5000L
        val DEFER_TEXTS = setOf("知道了", "我知道了", "暂不", "以后再说", "下次再说")

        /** 「×」的各种写法。仅当文案几乎只由它构成时才认定为关闭按钮，见 isLoneCloseGlyph。 */
        val CLOSE_GLYPHS = setOf('×', '✕', '✖', '✗')
    }
}
