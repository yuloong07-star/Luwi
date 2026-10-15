package io.github.yuloong07star.luwi.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 球往**外面**走的那一圈圈涟漪 (2026-10-09 主人: "说话时加个边框闪烁, 要像 vivo 蓝心小v那样"
 * 与 "给正在听加个与正在说相反的收回来特效")
 *
 * 两档, 方向相反:
 *
 * | 球上那三个字 | 走法 | 说法 |
 * | :-- | :-- | :-- |
 * | 正在说 | [Direction.OUT] | 从球的外缘一圈圈往外扩, 越扩越淡 (喇叭在出声) |
 * | 正在听 | [Direction.IN] | 从最外圈一圈圈往球收, 越收越淡 (耳朵在收) |
 * | 其余 (在想 / 失败 / 空闲) | [Direction.NONE] | 什么都不画 |
 *
 * **那一圈涟漪比球大** (球 45 dp, 最外那圈 92 dp), 画在球那块 48 dp 的窗里会被裁掉 —— 所以它住在
 * 自己那块 [SPAN_DP] 见方的**非触摸窗**里 (见 `OverlayService.attachRipple`): `FLAG_NOT_TOUCHABLE`
 * 是硬条件, 球的"只吃自己那 48 dp"那条性质不许被这件事破坏
 *
 * 这里的两半都特意做小: **算式全是纯函数** (没有设备也能量, 见 BallTest), 而 [RippleView] 只管把
 * 它画出来
 */
internal object BallPulse {

    /** 涟漪往哪边走 */
    enum class Direction { NONE, OUT, IN }

    /** 那一档该往哪边走 */
    fun direction(word: BallWord?): Direction = when (word) {
        BallWord.SPEAKING -> Direction.OUT
        BallWord.LISTENING -> Direction.IN
        else -> Direction.NONE
    }

    /** 那一档的方向怎么写成读数 (`overlay op=state` 的 `pulse`): 空串 = 涟漪没在跑 */
    fun name(word: BallWord?): String = when (direction(word)) {
        Direction.OUT -> "out"
        Direction.IN -> "in"
        Direction.NONE -> ""
    }

    /**
     * 一圈涟漪走了 [progress] (0..1) 时该在的半径 (px)
     *
     * 两个方向的**起点与终点正好对调**: 说从 [from] 走到 [to], 听从 [to] 回到 [from] —— 主人要的
     * "相反的特效"就是这一个式子
     */
    fun radiusAt(progress: Float, from: Float, to: Float, direction: Direction): Float {
        val at = progress.coerceIn(0f, 1f)
        return when (direction) {
            Direction.IN -> to - (to - from) * at
            else -> from + (to - from) * at
        }
    }

    /**
     * 一圈涟漪走了 [progress] 时的不透明度: **起点最亮, 走完淡没** (两个方向都是这一条)
     *
     * 于是一个"从球边出来往外淡掉", 一个"从外面进来往球里淡掉" —— 淡的那一头就是"去/来"的方向
     */
    fun alphaAt(progress: Float, peak: Float): Float {
        val at = progress.coerceIn(0f, 1f)
        return (peak * (1f - at)).coerceIn(0f, 1f)
    }

    /**
     * 这一拍那几圈各自的进度 (0..1): 每 [STAGGER] 出一圈
     *
     * 两圈错开半圈, 于是每隔半圈就有一圈出发 —— 一圈一圈接下来看着像"一直在动", 而不是"闪一下、
     * 空一拍、再闪一下"
     */
    fun phases(at: Float): List<Float> {
        val start = at.coerceIn(0f, 1f)
        return (0 until RINGS).map { one -> (start + one * STAGGER) % 1f }
    }

    /**
     * 涟漪窗多大: 球 67 dp 加两边各 32.5 dp 的余量, 最外那一圈正好落在 66 dp 半径上
     *
     * 这个数跟着 [BallView.BALL_DRAW_DP] 走 —— 球从 45 dp 放大到 67 dp (换人物立绘那一档) 时,
     * 窗不改就会让环缩在球里看不见
     */
    const val SPAN_DP = 132

    /** 一圈走完用多久: 与描边那口呼吸 ([BallView] 的 `BREATH_MS`) 同一个节拍 */
    const val RING_MS = 1200L

    /** 几圈同时在飞 */
    const val RINGS = 2

    /** 相邻两圈的错相 (0.5 = 半圈, 与 [RING_MS] 一起就是"每 600 ms 出一圈") */
    const val STAGGER = 0.5f

    /** 涟漪最亮那一档: 比描边淡一截 —— 它只是"在动"的旁注, 不许盖过球上那三个字 */
    const val PEAK_ALPHA = 0.55f

    /** 那一圈的粗细: 描边也是 2 dp, 两样东西同一个分量 */
    const val STROKE_DP = 2f

    /**
     * 起点半径: **内置那个球的半径** (48 dp 的球画在 45 dp 上, 见 `BallView.drawDp`)
     *
     * 从球心算, 所以是半个球 —— 涟漪与球之间不留缝。球尺寸是可调的 (48~80 dp), 而环从一个固定的
     * 半径起步: 调大之后环从球内部往外扩, 那一段被球自己盖住, 于是看起来仍是"从球边散出去"
     */
    const val FROM_DP = BallView.BALL_SIZE_DP * 0.94f / 2f

    /**
     * 那块窗的标题: 显式写一个
     *
     * 不写的话窗口管理器拿 view 的类名当标题, 而**别的脚本是拿 `dumpsys window windows` 认球窗的**
     * (见 tools/lw-ball-check.ps1 那几份): 多出来一块我们包的 `APPLICATION_OVERLAY` + `NOT_FOCUSABLE`
     * 的窗, 光按那两个特征认就会把涟漪窗当成球 (量到 96 dp 的假帧)。标题分开写, 认球窗那边加上类名
     * `BallView` 一起判
     */
    const val TITLE = "Luwi ball pulse"
}

/**
 * 画那几圈涟漪的 view: 一只一直转的表, 每一帧把 [BallPulse] 那几条算式的结果画出来
 *
 * 它自己**什么事件都不收** (窗那一侧还有 `FLAG_NOT_TOUCHABLE`), 也不改方向与颜色以外的任何东西 ——
 * "该不该在跑"由调用方决定 (挂窗 / 摘窗, 见 `OverlayService` 的 `attachRipple` / `detachRipple`)
 */
internal class RippleView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(BallPulse.STROKE_DP)
    }

    private var direction = BallPulse.Direction.NONE
    private var color = Color.TRANSPARENT
    private var phase = 0f

    /**
     * 那一只一直转的表: 0 → 1 走 [BallPulse.RING_MS], 无限循环
     *
     * 线性插值 (不是那几条缓动): 涟漪是匀速的, 一头快一头慢会看着像顿了一下
     */
    private val clock = ValueAnimator.ofFloat(0f, 1f).setDuration(BallPulse.RING_MS).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            phase = animator.animatedValue as Float
            invalidate()
        }
    }

    init {
        // 它就是一层画: 不吃焦点、不吃点击 (窗那一侧也拦着)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 这一档往哪边走、用什么色: 换字换色时调用方每一拍都会叫一次, 一样的值什么都不做 */
    fun pulse(next: BallWord, ring: Int) {
        val want = BallPulse.direction(next)
        if (direction == want && color == ring) return
        direction = want
        color = ring
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!clock.isStarted) clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (direction == BallPulse.Direction.NONE || Color.alpha(color) == 0) return
        val centerX = width / 2f
        val centerY = height / 2f
        // 终点是窗里留 2 dp 边的那一圈 (见 BallPulse.SPAN_DP): 再往外就贴到窗边被裁了
        val from = dpf(BallPulse.FROM_DP)
        val to = minOf(width, height) / 2f - dpf(BallPulse.STROKE_DP)
        if (to <= from) return
        BallPulse.phases(phase).forEach { at ->
            val alpha = BallPulse.alphaAt(at, BallPulse.PEAK_ALPHA)
            paint.color = Color.argb(
                (Color.alpha(color) * alpha).toInt(),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
            canvas.drawCircle(centerX, centerY, BallPulse.radiusAt(at, from, to, direction), paint)
        }
    }

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density
}
