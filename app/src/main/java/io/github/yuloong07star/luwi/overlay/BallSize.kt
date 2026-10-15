package io.github.yuloong07star.luwi.overlay

import android.content.Context

/**
 * 球多大 —— 一档设置 (主人 2026-10-15: "在 luwi 浮标设置里, 增加 ball 尺寸大小")
 *
 * 它以前是个常量 (D16 定的 48 dp), 换人物立绘时又被写死成 72 dp。两件事都不对:
 * **球多大只有主人自己知道** —— 球小不挡屏, 球大人物五官看得清。
 *
 * 于是有两个来源, 取**大的那个**:
 *
 * | 来源 | 值 | 什么时候算数 |
 * | :-- | :-- | :-- |
 * | 这一档设置 ([sizeDp]) | 缺省 [DEFAULT_DP] = 48 | 一直算数 |
 * | 外观插件的要求 (BallSpec.minDp) | 鲸鱼娘那份是 72 | 那个主题生效时 |
 *
 * 插件那个下限不是"抢设置"而是"它自己画不下": 全身立绘缩到 48 dp 时五官糊成一团, 所以鲸鱼娘那一份
 * 声明 72。主人把设置拉到 80 就用 80, 留在 48 时插件球仍是 72。
 *
 * 那几条算式都跟着测量尺寸走, 不需要各自再存一个数:
 * - 球体画多大: 窗口的 94% (见 BallView.drawDp) —— 拖动放大那 1.06 倍才落得进窗口
 * - 半隐: 比例是 BallGeometry.HALF_HIDE (半个球), 与球多大无关
 * - 涟漪窗: BallPulse.SPAN_DP 是固定的, 球调小之后环离球远一点, 但不会被裁
 */
internal object BallSize {

    /** 与设置页、BallSpot 同一个偏好文件 */
    private const val STORE = "luwi"
    private const val KEY = "ball-size"

    /** 缺省: **还是原来那个 48 dp** (换立绘时改大过一版, 主人看过之后要它回原尺寸) */
    const val DEFAULT_DP = 48

    /** 可调范围 (dp) —— 上界 80: 再大人就该去用虚拟屏看了 */
    val range = 48f..80f

    /** 滑块上那几个吸附点 */
    val keyPoints = listOf(48f, 56f, 64f, 72f, 80f)

    /** 吸附容差 (dp) */
    const val MAGNET = 1.5f

    /** 主人设的基准尺寸 (dp) */
    fun sizeDp(context: Context): Int =
        prefs(context).getInt(KEY, DEFAULT_DP)
            .coerceIn(range.start.toInt(), range.endInclusive.toInt())

    /** 存一档 (先让界面用上, 再落盘 —— 与 ScreenshotBudget 同一个做法) */
    fun set(context: Context, dp: Int) {
        prefs(context).edit()
            .putInt(KEY, dp.coerceIn(range.start.toInt(), range.endInclusive.toInt()))
            .apply()
    }

    /**
     * 现在这颗球实际多大 (dp): 设置值与插件要求里**大的那个**
     *
     * @param themeMinDp 当前主题要求的下限 (没挑主题 / 它没声明就是 0)
     */
    fun resolve(context: Context, themeMinDp: Int): Int =
        maxOf(sizeDp(context), themeMinDp).coerceIn(range.start.toInt(), range.endInclusive.toInt())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
