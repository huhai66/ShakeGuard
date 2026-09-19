package com.example.shakeguard.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * 图像识别：在截图上用 OpenCV 模板匹配查找「×」关闭按钮，返回屏幕坐标列表（可能为空）。
 *
 * 模板在运行时生成（透明背景无法参与匹配，故用 128 中灰底 + 白色字形），每个尺寸一份，
 * 黑白两种极性由 minVal 一并覆盖。尺寸表以「× 占屏宽的比例」为准，后续可按需增删或调阈值。
 */
object ImageRecognizer {

    private const val TAG = "ImageRecognizer"
    private const val THRESHOLD = 0.8
    private const val MAX_WIDTH = 720

    /**
     * 模板边长。**步长必须够密**，这一条是拿真机截图离线量出来的，不是估的。
     *
     * 相关分对尺寸极其敏感：相邻两档差 4~8px 时，× 的真实边长一旦落在档与档中间，
     * 最近的一档也要差 20% 以上，分数从 0.85 掉到 0.7 —— 整类 × 直接漏检。
     *
     * 实测（某「灰圆里一个白 ×」的摇一摇广告，1256×2760 真机截图，× 位于 (1167,256)）：
     *  旧表 12,16,20,26,… → 最高 0.65~0.77，一直过不了 0.8，而它恰好落在 20 和 26 的空档里
     *  同一模板、步长加密到 2 → 18/20/22/24 四档分别拿到 0.835 / 0.869 / 0.845 / 0.844
     * 从"擦边过"变成一片宽平台，对 × 的实际大小不再敏感。上端保留给尺寸偏大的 × 按钮。
     *
     * 代价：模板数 7 → 15，匹配耗时约 2 倍。调参工具见 tools/Tune.java。
     */
    private val TEMPLATE_SIZES = intArrayOf(12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 34, 38, 42, 46, 50)

    /**
     * 「×」两条对角线离模板边框的比例（× 占框的 1 - 2×该值）。
     *
     * 原来是 0.18（× 只占框的 64%），实测偏小：真机按钮上的 × 相对它的可视范围更大。
     * 同一张截图上，0.18 只有 24 这一档勉强到 0.808 —— 全靠尺寸恰好落点；
     * 收到 0.12（× 占 76%）才有上一条注释里那片宽平台。
     */
    private const val INSET_FRACTION = 0.12f

    /**
     * 一个命中的「×」。
     *
     * @param score 该命中的相关系数
     * @param size  命中的模板边长（缩到 [MAX_WIDTH] 后的像素），可反推这个 × 在屏幕上大概多大
     */
    data class Hit(val x: Int, val y: Int, val score: Double, val size: Int, val glyph: String)

    /**
     * @param hits     命中的「×」，按分数从高到低。**带分数和字形名**：只给坐标无法判断是
     *                 "稳妥命中"还是"勉强踩过阈值的误命中"，也看不出命中的到底是手绘的 ×
     *                 还是以文字渲染的 X / x —— 三者要调的东西完全不同。
     * @param topScore 所有模板里的最高相关系数，**低于 [THRESHOLD] 时也会返回**。
     */
    data class Result(val hits: List<Hit>, val topScore: Double, val topGlyph: String)

    @Volatile
    private var ready = false

    /**
     * 一份模板 + 它的字形名。[label] 会一路带到调试面板上，用来分辨命中的到底是
     * 手绘的 ×、还是以文字渲染的 X / x —— 这三者失配时要调的东西完全不同。
     */
    private data class Template(val mat: Mat, val label: String)

    private val templates = mutableListOf<Template>()

    fun findCloseButtons(screen: Bitmap): Result {
        if (!ensureReady()) return Result(emptyList(), 0.0, "")

        // 缩小截图以提速；HardwareBuffer 位图先转软位图
        val soft = if (screen.config == Bitmap.Config.HARDWARE) screen.copy(Bitmap.Config.ARGB_8888, false) else screen
        val scale = if (soft.width > MAX_WIDTH) MAX_WIDTH.toDouble() / soft.width else 1.0
        val w = max(1, (soft.width * scale).toInt())
        val h = max(1, (soft.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(soft, w, h, true)

        val screenMat = Mat()
        Utils.bitmapToMat(small, screenMat)
        val gray = Mat()
        Imgproc.cvtColor(screenMat, gray, Imgproc.COLOR_RGBA2GRAY)

        val matches = ArrayList<Match>()
        var topScore = 0.0
        // 记下最高分是哪个字形给的：0.66 来自「画×」还是来自「X」，要调的方向完全不同
        var topGlyph = ""
        for (tmpl in templates) {
            val mat = tmpl.mat
            if (mat.cols() > gray.cols() || mat.rows() > gray.rows()) continue
            val result = Mat()
            Imgproc.matchTemplate(gray, mat, result, Imgproc.TM_CCOEFF_NORMED)
            val minMax = Core.minMaxLoc(result)
            // TM_CCOEFF_NORMED 对亮度反转严格反号，恒有「黑×模板.maxVal == -(白×模板.minVal)」，
            // 且位置相同（已在真实截图上逐尺寸验证）。所以同一份白×模板把 minVal 取负，就等于
            // 「黑 × 压亮底」那个极性的分数，不必再生成一份黑色模板。省下的模板额度正好用来补小尺寸。
            val inverted = -minMax.minVal
            val useInverted = inverted > minMax.maxVal
            val score = if (useInverted) inverted else minMax.maxVal
            val loc = if (useInverted) minMax.minLoc else minMax.maxLoc
            if (score > topScore) {
                topScore = score
                topGlyph = tmpl.label
            }
            if (score >= THRESHOLD) {
                matches.add(
                    Match(
                        x = loc.x + mat.cols() / 2.0,
                        y = loc.y + mat.rows() / 2.0,
                        score = score,
                        w = mat.cols(),
                        h = mat.rows(),
                        glyph = tmpl.label,
                    )
                )
            }
            result.release()
        }

        gray.release()
        screenMat.release()

        return Result(
            hits = nonMaxSuppression(matches).map { m ->
                Hit(
                    x = ((m.x + 0.5) / scale).toInt(),
                    y = ((m.y + 0.5) / scale).toInt(),
                    score = m.score,
                    size = m.w,
                    glyph = m.glyph,
                )
            },
            topScore = topScore,
            topGlyph = topGlyph,
        )
    }

    private data class Match(
        val x: Double,
        val y: Double,
        val score: Double,
        val w: Int,
        val h: Int,
        val glyph: String,
    )

    private fun nonMaxSuppression(matches: List<Match>): List<Match> {
        val sorted = matches.sortedByDescending { it.score }
        val kept = ArrayList<Match>()
        for (m in sorted) {
            val overlaps = kept.any { k ->
                abs(m.x - k.x) < max(m.w, k.w) * 0.5 && abs(m.y - k.y) < max(m.h, k.h) * 0.5
            }
            if (!overlaps) kept.add(m)
        }
        return kept
    }

    private fun ensureReady(): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            val ok = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
            if (!ok) {
                Log.e(TAG, "OpenCV 初始化失败")
                return false
            }
            templates.clear()
            // 每个尺寸只需一份模板：黑白两种极性由 max(maxVal, -minVal) 覆盖。
            //
            // 这里曾加过「以文字渲染的 X / x」两份模板（想覆盖"关闭按钮其实是个拉丁字母"的
            // 情况），**已回退**：多出来的模板会命中更多似是而非的位置，而点击目标只按
            // 最高分挑一个（hits.first()），于是误命中一旦分数更高就会顶掉真正的 ×，
            // 表现正是"原本跳得掉的广告变成跳不掉"。要再试的话，得给新字形单独一个更高阈值，
            // 不能和手绘 × 共用 0.8。
            for (size in TEMPLATE_SIZES) {
                templates.add(Template(makeTemplate(size), LABEL_DRAWN))
            }
            ready = true
            return true
        }
    }

    /**
     * 生成「×」字形模板：128 中灰底 + 白色两条交叉对角线（圆头）。
     *
     * 底色必须保持中灰：匹配时窗口内「背景对背景」也参与相关，中灰能让深色底与浅色底
     * 两种情况拿到相反的符号，从而配合 minVal 一并覆盖黑 × 极性。
     */
    private fun makeTemplate(size: Int): Mat {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size / 8f
            strokeCap = Paint.Cap.ROUND
        }
        val inset = size * INSET_FRACTION
        canvas.drawLine(inset, inset, size - inset, size - inset, paint)
        canvas.drawLine(size - inset, inset, inset, size - inset, paint)

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        mat.release()
        return gray
    }

    private const val LABEL_DRAWN = "画×"
}
