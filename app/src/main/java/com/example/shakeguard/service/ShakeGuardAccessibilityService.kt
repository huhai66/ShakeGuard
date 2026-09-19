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
    private var lastClickTime = 0L
    private var lastDebugTime = 0L
    private var lastScreenshotTime = 0L
    private var lastDiagLog = 0L
    private var lastTruncateLog = 0L
    private var lastImageLog = 0L

    /** 已经有一个扫描任务排在主线程队列里 —— 靠它把事件洪水塌缩成一次扫描。见 [requestScan]。 */
    private var scanScheduled = false

    /** 下一次扫描最早何时可以开始，由上一次扫描**结束时**推进。 */
    private var nextScanAllowedAt = 0L

    /** 上一次由 content-changed 触发的扫描时刻：这类事件数量极大，单独给一个更长的节流。 */
    private var lastContentScan = 0L

    /**
     * 广告窗口期：被监控应用的窗口刚刚切换。只有在这个窗口期内才做定时重试和图像识别兜底。
     *
     * 区分「窗口期」和「平时的浏览」是本轮修复的核心 —— 微博这类应用平时也在疯狂发事件，
     * 把广告场景和日常场景混在一起处理，正是卡死的来源。
     */
    private var burstActive = false
    private var burstStartAt = 0L

    /** 本轮窗口期已经扫了多少次、累计扫了多久 —— 用来给窗口期设工作量上限，见 [burstTask]。 */
    private var burstScans = 0
    private var burstScanMs = 0L

    /** 上一次截图还在处理中（回读位图 + 15 次模板匹配）。挡住无界队列的堆积。 */
    private var screenshotInFlight = false

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanTask = Runnable {
        scanScheduled = false
        runScan()
    }

    /**
     * 窗口期内的定时重试。
     *
     * 开屏广告的 × 往往在窗口切过来之后才渲染出来，而广告页一旦渲染完就不再产生
     * content-changed 事件 —— 原来完全靠「下一个偶发事件」来救，延迟不可控（这正是
     * 慢 2~3 秒的原因之一）。这里在窗口切换后的头几秒主动重试，把时机拿回来。
     */
    private val burstTask = object : Runnable {
        override fun run() {
            if (!burstActive) return
            val now = SystemClock.elapsedRealtime()
            // 三重上限，任一先到就收工：窗口存活时间、扫描次数、**累计扫描耗时**。
            // 后两个是必需的：微博那种树上单次扫描可能上秒，只掐「6 秒窗口」等于放任主线程
            // 连续扫十几秒 —— 那正是要消灭的白屏本身。工作量预算才是真正的刹车。
            if (now - burstStartAt > BURST_MS ||
                burstScans >= MAX_BURST_SCANS ||
                burstScanMs >= MAX_BURST_SCAN_MS
            ) {
                burstActive = false
                return
            }
            // 刚点过就先歇一会儿，别在同一个页面上连着点
            if (now - lastClickTime >= REARM_MS) requestScan()
            mainHandler.postDelayed(this, BURST_INTERVAL_MS)
        }
    }

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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged()
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
            lastTruncateLog = 0L
            lastImageLog = 0L
            lastScreenshotTime = 0L
        }

        if (!shouldSkip(pkg)) {
            // 从「已勾选的应用」切到一个「未勾选的窗口」，正是广告浮层另起窗口的形态。
            // 这条路径原本完全静默，加一条好判断广告是不是根本没走到拦截逻辑。
            if (previousWasMonitored) logDiag("切到未勾选窗口：$pkg")
            stopBurst()
            return
        }

        // 窗口切换就是开屏 / 弹窗广告出现的时刻。原来这里是「清掉节流后直接 attemptClick」，
        // 意图相同（别让上一屏的节流挡住这一屏），但换成窗口期重试后不再依赖后续事件。
        lastClickTime = 0L
        startBurst()
    }

    private fun onContentChanged() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < REARM_MS) return
        // 窗口期内交给 burstTask 定时扫描，事件这条线让路 —— 两条路径同时压主线程没有意义
        if (burstActive) return
        if (now - lastContentScan < CONTENT_THROTTLE_MS) return
        lastContentScan = now
        requestScan()
    }

    /**
     * 登记「需要扫一次」，而不是立刻扫。
     *
     * 这是修微博卡死的关键一刀。事件回调和扫描跑在同一条主线程上，所以扫描执行期间事件
     * 根本进不来，只能在 Looper 队列里排队；而原来的节流是在扫描**开始前**记时的，等这次
     * 扫描返回，排在最前面的事件看到的间隔已经是「整段扫描耗时」≫ 300ms，节流直接放行 ——
     * 于是扫描背靠背地跑，主线程占空比接近 100%，UI 永远画不出来。
     *
     * 现在被积压的事件只能让 [scanScheduled] 置位一次，成千上万个事件塌缩成一次扫描。
     */
    private fun requestScan() {
        if (scanScheduled) return
        scanScheduled = true
        val gap = (nextScanAllowedAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        mainHandler.postDelayed(scanTask, gap)
    }

    private fun runScan() {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            attemptClick()
        } finally {
            val endedAt = SystemClock.elapsedRealtime()
            // 从扫描**结束**起算最小间隔：若从开始起算，一次慢扫描会把这 300ms 整个吃掉，
            // 又退化成背靠背。
            nextScanAllowedAt = endedAt + MIN_SCAN_INTERVAL_MS
            if (burstActive) {
                burstScans++
                burstScanMs += endedAt - startedAt
            }
        }
    }

    /** 窗口切到被监控应用：开一段确定性重试。已经在窗口期内就只把起点往后推。 */
    private fun startBurst() {
        burstStartAt = SystemClock.elapsedRealtime()
        if (burstActive) return
        burstActive = true
        burstScans = 0
        burstScanMs = 0L
        mainHandler.post(burstTask)
    }

    private fun stopBurst() {
        if (!burstActive) return
        burstActive = false
        mainHandler.removeCallbacks(burstTask)
    }

    private fun shouldSkip(pkg: String): Boolean {
        if (!settings.masterEnabled.value) return false
        if (!settings.enabledPackages.value.contains(pkg)) return false
        return true
    }

    private fun attemptClick() {
        // 绝不能扫自己。上面 onWindowChanged 里那条「本应用自己」的 return 发生在更新
        // currentPkg **之前**，所以从微博切回本应用时，currentPkg 仍然停在微博（已勾选），
        // shouldSkip 会一路放行 —— 于是我们驱动无障碍去遍历**自己的 Compose 树**，还是压在
        // 自己的主线程上，界面一帧都画不出来。这正是「切回来白屏」最直接的成因。
        if (currentPkg == packageName) return
        if (!shouldSkip(currentPkg)) return

        val root = rootInActiveWindow

        // 双重确认当前前台包名：活动窗口根节点最可靠，取不到时退回 currentPkg。
        //
        // 这里原来退回的是「触发本次尝试的**事件**所携带的包名」。改用调度器之后，一次扫描
        // 可能同时服务好几个被积压的事件，那个包名会变成「队列里最旧那条的」—— 与其拿一个
        // 已经过期的包名去判断前台，不如用 currentPkg（它只在 WINDOW_STATE_CHANGED 时更新，
        // 语义就是「当前前台是谁」）。root 取不到时本就无从判断，这个退路只在那一瞬生效。
        val foreground = root?.packageName?.toString()?.takeIf { it.isNotEmpty() }
            ?: currentPkg.takeIf { it.isNotEmpty() }

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

        // 同上，只是换用更可靠的前台包名再确认一次：rootInActiveWindow 若是本应用自己，
        // 说明 currentPkg 已经过期，绝不能拿自己那棵树当目标。
        if (foreground == packageName) {
            logDiag("拦下：前台是本应用自己（currentPkg=$currentPkg 已过期）")
            return
        }

        if (root != null) {
            // 探针（probeLabel）的产物只在下面写调试日志时才用得到，而它对每个节点都要建字符串、
            // 对无文案的小节点还要分配一个 Rect。所以先在**遍历前**定一次「这次要不要写日志」，
            // 不要就把整个探针跳过。省的是分配不是 IPC（遍历里唯一的 IPC 是 getChild），
            // 但能让热路径干净些。
            val wantDebug = SystemClock.elapsedRealtime() - lastDebugTime >= DEBUG_THROTTLE_MS
            val result = scanAndClick(root, currentIsSystemApp, wantDebug)
            if (result.clicked) {
                lastClickTime = SystemClock.elapsedRealtime()
                settings.onSkipped()
                settings.addDebugLine("点击成功: $currentPkg | ${result.clickedNode}")
                Log.d(TAG, "已点击: $currentPkg | ${result.clickedNode}")
                return
            }
            // ACTION_CLICK 走不通的目标，改用真实触摸按坐标再试一次。
            //
            // 这一兜底专治「只标『跳过』的广告点不动」：那种按钮的 ACTION_CLICK 不被受理
            // （performAction 直接返回 false），而带倒计时的同类按钮多半是标准控件、能正常
            // 受理 —— 两者在控件实现上分道扬镳，现象就是一类跳得掉、另一类纹丝不动。
            //
            // 用坐标发触摸而不是换别的无障碍动作，是因为触摸走的是正常事件分发，不要求目标
            // 声明任何无障碍能力。这跟 tap() 上方那条结论一致：兼容性来自真实触摸，精度来自
            // 用节点的中心坐标。前提是配置里必须有 canPerformGestures（已补）。
            if (!result.clicked && result.gestureFallbacks.isNotEmpty()) {
                val target = result.gestureFallbacks.first()
                settings.addDebugLine(
                    "点击动作不受理，改用触摸兜底: ${target.label.take(40)} @ (${target.x.toInt()}, ${target.y.toInt()})"
                )
                Log.d(TAG, "触摸兜底: $currentPkg @ (${target.x.toInt()}, ${target.y.toInt()})")
                tapAt(target.x, target.y)
                return
            }

            // 「匹配到但点不动」和「根本没匹配上」要调的东西完全不同，分开报
            if (result.unclickableTargets.isNotEmpty()) {
                settings.addDebugLine("匹配到但点不动: ${result.unclickableTargets.joinToString(" , ")}")
            }
            // 被上限截断和「真的没有」也是两回事：前者要调上限，后者要调匹配规则。
            if (result.truncated) logTruncated()
            if (result.candidates.isNotEmpty()) maybeLogDebug(foreground, result.candidates, wantDebug)
        }
        // 节点树未命中时，尝试图像识别兜底（针对网页广告里的 ×）：此处已确认前台就是被监控应用
        attemptImageRecognition()
    }

    private fun attemptImageRecognition() {
        if (!settings.imageRecognitionEnabled.value) return
        // 只在「窗口刚切换」的广告窗口期内做图像兜底。
        //
        // 原来每一次节点扫描失败都会走到这里，仅由 SCREENSHOT_THROTTLE_MS 节流 —— 而微博的
        // content-changed 是持续的，等于**每 2 秒无条件截一次全屏图（13.8MB 位图回读）再跑
        // 15 次模板匹配**，跟屏幕上有没有广告毫无关系。这是白屏的另一半原因。
        //
        // 注意别把「开屏慢 2~3 秒」也算在它头上：在补上 canPerformGestures 之前，
        // dispatchGesture 一直返回 false，图像识别**从来没有真的点下去过** —— 一条点不动的
        // 路径不可能造成跳过延迟。那 2~3 秒只能来自节点路径，也就是上面 scanAndClick 的规模问题。
        if (!burstActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenshotRecognition()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotRecognition() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotTime < SCREENSHOT_THROTTLE_MS) return
        // screenshotExecutor 是**无界队列**的单线程 executor，而单次「回读位图 + 15 次模板匹配」
        // 要 0.5~1.5 秒。一旦触发快过处理速度，回调就会排队，而排队出来的 tap 用的是**几秒前
        // 那张截图**的坐标 —— 屏幕上广告早没了，等于对着别的界面盲点（可能落到落地页甚至购买
        // 按钮）。所以一次没处理完就不再接新的。
        // 第二道是防呆：系统偶尔会既不回调成功也不回调失败。若只认这个标志位，
        // 图像识别会就此永久停摆；所以超过一定岁数就当它已经作废，放行新的截图。
        if (screenshotInFlight && now - lastScreenshotTime < SCREENSHOT_STUCK_MS) return
        lastScreenshotTime = now
        screenshotInFlight = true

        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val capturedAt = SystemClock.elapsedRealtime()
                val capturedPkg = currentPkg
                val bitmap = screenshotToBitmap(screenshot)
                if (bitmap == null) {
                    screenshotInFlight = false
                    logDiag("图像识别：截图转位图失败")
                    return
                }
                val result = ImageRecognizer.findCloseButtons(bitmap)
                screenshotInFlight = false
                // 匹配跑完的时刻。从这里到真正 tap 之间的延迟才是「坐标在队列里等了多久」，
                // 与上面那段图像处理时间必须分开看 —— 后者是必然开销，前者才是可疑信号。
                val processedAt = SystemClock.elapsedRealtime()
                // 把截图的实际像素尺寸一起打出来：点击坐标是按这份位图算的，
                // 只有知道位图多大，才能和屏幕上 × 的真实位置换算对比、判断落点偏了多少。
                val shot = "${bitmap.width}x${bitmap.height}"
                val processMs = processedAt - capturedAt
                if (result.hits.isEmpty()) {
                    // 分数和"哪个字形给的"都是排查关键：接近 0.8 说明模板形态差一点，
                    // 很低说明根本没匹配上；而最高分来自「画×」还是「X」指向不同的修法。
                    logImage(
                        "图像识别未命中 ${burstElapsed(processedAt)}最高 %.2f 处理${processMs}ms [图 $shot]"
                            .format(result.topScore)
                    )
                    return
                }
                // 命中的全部列出（不只是要点的那个）：能看出是不是"旁边还有个更像 × 的"被漏掉，
                // 以及每个命中的分数是勉强过阈还是稳稳命中、命中的是哪种字形。
                settings.addDebugLine(
                    "图像识别命中 ${burstElapsed(processedAt)}处理${processMs}ms" +
                        (if (result.earlyExit) "·早退" else "") + " [图 $shot] " +
                        result.hits.take(MAX_HITS_LOGGED)
                            .joinToString(" , ") { "${it.glyph}(${it.x},${it.y})%.2f/t${it.size}".format(it.score) }
                )
                val hit = result.hits.first()
                Log.d(TAG, "图像识别命中: $currentPkg @ (${hit.x}, ${hit.y}) 分 ${hit.score}")
                // 连同**拍照时刻**一起投递：这一跳排在主线程队列里，前面可能还有一次
                // 慢扫描，等它真正执行时坐标可能已经过期。tap 里会按 capturedAt 校验。
                mainHandler.post {
                    tap(hit.x.toFloat(), hit.y.toFloat(), capturedAt, processedAt, capturedPkg)
                }
            }

            override fun onFailure(errorCode: Int) {
                screenshotInFlight = false
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
     * 「扫描被上限截断」的提示，**单独一条更长的节流**。
     *
     * 不能复用 [logDiag]：微博那种树几乎每一次扫描都会被截断，若共用同一个 5 秒节流，
     * 它会把真正要看的「图像识别未命中 / 最高分」挤出面板 —— 而后者才是排查漏点的关键。
     */
    /**
     * 图像识别专用的日志节流，**故意比 [logDiag] 短**。
     *
     * 原来「未命中」也走 logDiag 的 5 秒节流，于是窗口期内一大半尝试根本没被记下来 ——
     * 「到底是广告渲染慢，还是我们流水线慢」这个问题就永远分不开。每次尝试都要留痕，
     * 而且要带上**距窗口切换多久**：这个时间轴一摆出来，两者立刻分得清。
     */
    private fun logImage(line: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastImageLog < IMAGE_LOG_MS) return
        lastImageLog = now
        settings.addDebugLine(line)
    }

    /** 「距窗口切换多久」的可读前缀；不在窗口期内就不带（那时这个数字没有意义）。 */
    private fun burstElapsed(at: Long): String =
        if (burstActive) "+${at - burstStartAt}ms " else ""

    private fun logTruncated() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTruncateLog < TRUNCATE_LOG_MS) return
        lastTruncateLog = now
        settings.addDebugLine(
            "扫描被截断（上限：$MAX_SCAN_NODES 节点 / 深度 $MAX_SCAN_DEPTH / ${SCAN_TIME_BUDGET_MS}ms）"
        )
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
    /**
     * @param capturedAt   截图回调刚触发、位图还没回读的时刻
     * @param processedAt  模板匹配跑完的时刻（[capturedAt] 到它之间是图像处理的必然开销）
     * @param capturedPkg  截图那一刻的前台包名
     */
    private fun tap(x: Float, y: Float, capturedAt: Long, processedAt: Long, capturedPkg: String) {
        val now = SystemClock.elapsedRealtime()
        val processMs = processedAt - capturedAt
        val queueMs = now - processedAt
        val age = now - capturedAt

        // 判断「坐标还能不能用」，关键不是它多大岁数，而是**屏幕上的东西还是不是截图那一刻的**。
        // 前台换成别的应用了就一定不能点 —— 那一下必然落到无关界面上。
        if (currentPkg != capturedPkg) {
            logDiag("图像识别：前台已从 $capturedPkg 切到 $currentPkg，放弃点击")
            return
        }

        // 总年龄只作兜底。这里**绝不能**用紧阈值：位图回读 + 15 次模板匹配本身就是 0.5~1.5 秒，
        // 那是我们自己的开销，不是「坐标过期」。上一版把这段一道算进去、阈值又只给 700ms，
        // 结果实测 2785ms 直接被判死 —— 识别对了、坐标对了，却一次都没点下去。
        if (age > TAP_MAX_AGE_MS) {
            logDiag("图像识别：坐标已过期 ${age}ms（其中图像处理 ${processMs}ms、排队 ${queueMs}ms），放弃点击")
            return
        }
        // 把两段耗时一并带出来：若"排队"这段长期偏大，说明卡在主线程队列上，那是另一个问题
        Log.d(TAG, "图像识别点击 age=${age}ms (处理 ${processMs}ms / 排队 ${queueMs}ms)")
        dispatchTap(x, y, "图像识别点击")
    }

    /**
     * 节点兜底触摸。
     *
     * 坐标是刚刚从节点读出来的，天然新鲜，所以不需要 [tap] 那道年龄校验 —— 这里是给
     * 「匹配到但点不动」的目标用的：ACTION_CLICK 不被受理时，改按坐标发真实触摸。
     */
    private fun tapAt(x: Float, y: Float) = dispatchTap(x, y, "触摸兜底点击")

    private fun dispatchTap(x: Float, y: Float, tag: String) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        if (runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)) {
            lastClickTime = SystemClock.elapsedRealtime()
            settings.onSkipped()
            settings.addDebugLine("$tag: $currentPkg @ (${x.toInt()}, ${y.toInt()})")
            Log.d(TAG, "$tag: $currentPkg @ (${x.toInt()}, ${y.toInt()})")
        } else {
            // 这条分支以前是**完全静默**的，而它恰好是「命中日志照打、屏幕上什么都没发生」
            // 那种最难查的情况。dispatchGesture 返回 false 最常见的原因就是
            // 无障碍配置里少了 canPerformGestures —— 现在补上了，这里留个话便于确认。
            logDiag("$tag：dispatchGesture 返回 false，手势未注入")
            Log.w(TAG, "dispatchGesture 返回 false")
        }
    }

    /**
     * 带上 sys / fg 两个标记，因为它们各自能一句话解释掉一整类“什么都不点”：
     *  - `sys=true` 时 isTargetNode 会关掉「关闭 / ×」整类匹配，一旦对第三方应用判错，
     *    表现就是弹窗和 × 全都不点，而原来的日志里看不出任何异常；
     *  - `fg` 与 currentPkg 不一致说明广告另起了窗口，扫描用的却是另一个包名的节点树。
     */
    private fun maybeLogDebug(foreground: String?, candidates: List<String>, wantDebug: Boolean) {
        // wantDebug 是遍历前就定好的：它同时决定了探针有没有跑。这里再判断一次结果必然相同，
        // 直接用传入的值，避免「探针没跑却以为跑了」的错位。
        if (!wantDebug) return
        lastDebugTime = SystemClock.elapsedRealtime()
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
        /** 遍历因达到节点数或深度上限而提前结束 —— 用来区分「真的没有」和「没扫到」。 */
        val truncated: Boolean,
        /**
         * `ACTION_CLICK` 走不通、但**位置已知**的目标：交给真实触摸兜底。
         *
         * 这一类正是「匹配到但点不动」的主体。带坐标而不带节点，是因为节点在这个环节已经
         * 用不上了 —— 我们本来就打算绕开无障碍动作，直接按坐标发触摸。
         */
        val gestureFallbacks: List<TapTarget>,
    )

    /** 一个可以按坐标真实触摸的目标。 */
    private data class TapTarget(val x: Float, val y: Float, val label: String)

    /**
     * 遍历节点树：优先点“自身可点击且匹配”的按钮，其次点文本节点向上最近的可点击父节点。
     *
     * **成本模型（曾判断错，这里记准确）**：`AccessibilityNodeInfo` 是获取时就一次性取回的
     * 封口快照，`text` / `contentDescription` / `viewIdResourceName` / `className` /
     * `isClickable` / `isVisibleToUser` / `getBoundsInScreen` / `childCount` **全是本进程的
     * 字段读取，不产生任何 IPC**。整个遍历里**唯一的跨进程调用是 `getChild(i)`**，一个子节点
     * 一次往返。
     *
     * 这为什么能卡死微博：那次往返要投递到**目标应用自己的 UI 线程**去执行，请求方在这里
     * 阻塞等待，超时上限 5 秒（`AccessibilityInteractionClient.TIMEOUT_INTERACTION_MILLIS`）。
     * 所以「我们扫得越多 → 微博主线程被我们占得越久 → 我们的请求越慢」，互相拖。微博开着
     * `flagIncludeNotImportantViews`、又是信息流 + WebView 的万级节点树，请求量大到远超其他
     * 应用 —— 这就是「只有微博卡死」的解释。轻量应用只有几百节点，几百毫秒就扫完了。
     *
     * 因此限制**遍历规模**（[MAX_SCAN_NODES]）和**遍历耗时**（[SCAN_TIME_BUDGET_MS]）才是
     * 真正的解法；减少几个 getter 的调用并不省 IPC。[wantDebug] 省下的是字符串与 Rect 分配，
     * 顺带让热路径更干净，但别把它当性能手段。
     */
    private fun scanAndClick(
        root: AccessibilityNodeInfo,
        isSystemApp: Boolean,
        wantDebug: Boolean,
    ): ScanResult {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val candidates = ArrayList<String>()
        val stuckTargets = ArrayList<String>()
        val gestureFallbacks = ArrayList<TapTarget>()
        val textTargets = ArrayList<AccessibilityNodeInfo>()
        queue.addLast(root to 0)

        var visited = 0
        var truncated = false
        val deadline = SystemClock.elapsedRealtime() + SCAN_TIME_BUDGET_MS

        while (queue.isNotEmpty()) {
            if (visited >= MAX_SCAN_NODES) {
                truncated = true
                break
            }
            // 节点数上限挡不住「微博 UI 线程忙 → 单次 getChild 逼近 5 秒超时」这种病态情况，
            // 所以还要一道按墙钟计的预算：本函数跑在主线程上，拖多久界面就冻多久。
            if (SystemClock.elapsedRealtime() > deadline) {
                truncated = true
                break
            }
            val (node, depth) = queue.removeFirst()
            visited++

            if (node.isVisibleToUser && isTargetNode(node, isSystemApp)) {
                if (node.isClickable) {
                    // 遍历序就是原实现里 clickableTargets 的顺序，所以「第一个自身可点击的
                    // 目标」与原来要点的是同一个；点到就走，不必再把整棵树走完。
                    if (performClick(node)) {
                        return ScanResult(
                            true, nodeLabel(node), candidates, emptyList(), truncated, emptyList(),
                        )
                    }
                    // ACTION_CLICK 没被受理：记下坐标，稍后用真实触摸再试一次
                    centerOf(node)?.let { gestureFallbacks.add(TapTarget(it.first, it.second, nodeLabel(node))) }
                    if (stuckTargets.size < MAX_CANDIDATES) {
                        stuckTargets.add(nodeLabel(node).take(40))
                    }
                } else {
                    textTargets.add(node)
                }
            }

            if (wantDebug) {
                probeLabel(node)?.let { if (candidates.size < MAX_CANDIDATES) candidates.add(it) }
            }

            // 深度上限：只在这个节点**确实还有子节点**时才算被截断，否则叶节点会误报
            if (depth >= MAX_SCAN_DEPTH) {
                if (node.childCount > 0) truncated = true
                continue
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }

        // 其次：文本节点向上找最近可点击父节点（最多 3 层，避免点到整个广告容器）
        for (node in textTargets) {
            if (clickNearestClickable(node)) {
                return ScanResult(
                    true, nodeLabel(node), candidates, emptyList(), truncated, emptyList(),
                )
            }
            // 向上 3 层都没接住（或接住的那个也点不动）：**用文本节点自己的中心**兜底。
            // 这比继续往上找更安全 —— MAX_UP_LEVELS 限 3 层本就是为了避免点到覆盖大半屏的
            // 广告容器，而直接点在「跳过」这两个字上，落点就是用户眼睛看到的那个按钮。
            centerOf(node)?.let { gestureFallbacks.add(TapTarget(it.first, it.second, nodeLabel(node))) }
            if (stuckTargets.size < MAX_CANDIDATES) stuckTargets.add(nodeLabel(node).take(40))
        }
        return ScanResult(false, "", candidates, stuckTargets, truncated, gestureFallbacks)
    }

    /** 节点的屏幕中心；尺寸为 0（不可见 / 已 detach）时返回 null —— 那种坐标点下去等于乱点。 */
    private fun centerOf(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return bounds.centerX().toFloat() to bounds.centerY().toFloat()
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

    /**
     * **惰性短路**：每次读取 text / contentDescription / viewIdResourceName / className 都是一次
     * 跨进程调用，而本函数要对整棵树的每个可见节点跑一遍。原来把四个属性全部提前读出来再判定，
     * 于是绝大多数（本来就不该命中的）节点白付四次 IPC；现在命中就立刻返回，读不到的属性才往后读。
     * 判定次序与结果和原实现完全一致，只是不再提前取。
     */
    private fun isTargetNode(node: AccessibilityNodeInfo, isSystemApp: Boolean): Boolean {
        // 跳过类：所有应用、任何时刻
        val text = node.text?.toString().orEmpty()
        if (text.contains("跳过") || text.equals("skip", ignoreCase = true)) return true

        val desc = node.contentDescription?.toString().orEmpty()
        if (desc.contains("跳过") || desc.contains("skip", ignoreCase = true)) return true

        val id = node.viewIdResourceName.orEmpty()
        if (id.contains("skip", ignoreCase = true)) return true

        // 关闭类：仅第三方应用（避免误点系统里的“×”“删除”等按钮）
        if (isSystemApp) return false

        if (text.contains("关闭")) return true
        if (desc.contains("关闭") || desc.contains("close", ignoreCase = true)) return true
        if (isLoneCloseGlyph(text) || isLoneCloseGlyph(desc)) return true
        if (id.contains("close", ignoreCase = true)) return true
        if (text in DEFER_TEXTS) return true

        // 广告 SDK 的“×”关闭按钮常是小图标，id 含 interstitial/suspend 但不一定含 close。
        // className 只在这最后一步才读 —— 它原来和上面三个属性一起被提前读出，纯属浪费。
        val isImage = node.className?.toString()?.contains("Image", ignoreCase = true) == true
        return isImage &&
            (id.contains("interstitial", ignoreCase = true) || id.contains("suspend", ignoreCase = true))
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
        mainHandler.removeCallbacks(burstTask)
        mainHandler.removeCallbacks(scanTask)
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 无需处理
    }

    private companion object {
        const val TAG = "ShakeGuardService"
        const val REARM_MS = 3000L
        const val DEBUG_THROTTLE_MS = 3000L
        const val MAX_UP_LEVELS = 3
        const val MAX_CANDIDATES = 40

        /** 两次扫描之间至少隔这么久（从上次扫描**结束**起算），保证主线程有喘息。 */
        const val MIN_SCAN_INTERVAL_MS = 300L

        /** content-changed 事件数量极大且绝大多数与广告无关，单独给一个更长的节流。 */
        const val CONTENT_THROTTLE_MS = 900L

        /** 窗口切换后的重试窗口时长与间隔 —— 覆盖开屏广告出现的那几秒。 */
        const val BURST_MS = 6000L
        const val BURST_INTERVAL_MS = 300L

        /** 窗口期的**工作量**上限：次数与累计扫描耗时，任一先到即收工。见 burstTask。 */
        const val MAX_BURST_SCANS = 15
        const val MAX_BURST_SCAN_MS = 1500L

        /**
         * 截图坐标的绝对年龄上限（兜底）。
         *
         * 别把它调紧：这段年龄里包含了「位图回读 + 15 次模板匹配」的必然开销（实测 1~2 秒），
         * 那是我们自己的处理时间，不是坐标过期。真正判断场景有没有变靠的是包名校验，
         * 见 tap()。上一版给 700ms，把实测 2785ms 的正常命中直接判死了。
         */
        const val TAP_MAX_AGE_MS = 4000L

        /**
         * 单次扫描最多访问这么多节点（≈ 这么多次 `getChild` 跨进程往返）。
         *
         * 它是**安全网**，不是调优旋钮：正常路径靠「命中可点击目标就 early-return」收敛，
         * 轮不到它生效。开屏广告的 × 通常在自己的窗口里，深度 5~12、BFS 序位前 300 以内，
         * 1200 有很大富余。真被它截断时调试面板会明说（见 logTruncated），那时再调。
         */
        const val MAX_SCAN_NODES = 1200

        /**
         * 单次扫描的墙钟预算。节点数上限挡不住病态情况：微博 UI 线程被占满时，单次
         * `getChild` 要等到接近 5 秒才超时返回 null，几十次就够把主线程冻住。
         * 本函数跑在主线程上，拖多久界面就冻多久，所以耗时必须单独兜一道。
         */
        const val SCAN_TIME_BUDGET_MS = 300L

        /** BFS 深度上限。广告的 × 通常离窗口根节点不超过十几层。 */
        const val MAX_SCAN_DEPTH = 20

        /** 探测只收这么短的文案。跳过 / × / 关闭 / 知道了 这类按钮文案都很短。 */
        const val MAX_PROBE_TEXT = 8

        /** 调试面板里每次最多列几个图像识别命中。 */
        const val MAX_HITS_LOGGED = 4

        /** 探针里「无文案可点小节点」的边长上限（像素）。超过这个尺寸的多半是容器而非按钮。 */
        const val MAX_PROBE_SIDE = 320
        /**
         * 图像识别抽帧间隔。只在窗口期内生效（见 attemptImageRecognition），所以它不再需要
         * 兼顾「平时别太频繁」，只决定开屏那几秒里兜底多快。单次成本 0.5~1.5 秒（位图回读 +
         * 15 次模板匹配），配合 [screenshotInFlight] 不会堆积，所以 1200ms 是安全的。
         */
        const val SCREENSHOT_THROTTLE_MS = 600L

        /** 上一次截图超过这么久还没回调，就当它作废，别把图像识别永久卡死。 */
        const val SCREENSHOT_STUCK_MS = 5000L

        /**
         * 图像识别日志的节流。比 [DIAG_LOG_MS] 短，为的是**每一次尝试都留痕** ——
         * 窗口期内只有几次尝试、又只记下其中一次的话，"广告渲染慢"和"我们流水线慢"
         * 根本分不开。
         */
        const val IMAGE_LOG_MS = 1200L
        const val DIAG_LOG_MS = 5000L

        /** 「扫描被截断」的提示节流，故意比 DIAG_LOG_MS 长得多，见 logTruncated()。 */
        const val TRUNCATE_LOG_MS = 20000L
        val DEFER_TEXTS = setOf("知道了", "我知道了", "暂不", "以后再说", "下次再说")

        /** 「×」的各种写法。仅当文案几乎只由它构成时才认定为关闭按钮，见 isLoneCloseGlyph。 */
        val CLOSE_GLYPHS = setOf('×', '✕', '✖', '✗')
    }
}
