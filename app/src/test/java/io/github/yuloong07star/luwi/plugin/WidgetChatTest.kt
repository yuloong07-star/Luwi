package io.github.yuloong07star.luwi.plugin

import io.github.yuloong07star.luwi.plugin.widget.WidgetChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面对话组件那两道算式 (2.7.8 批次 6)
 *
 * 主人 2026-10-17 的两条原话:
 *
 * - "人物应该随边框变大而变大, 但长宽要等比, 防止变形" —— 前一半是 [WidgetChat.Face.cellDp] 与
 *   [WidgetChat.Face.actualDp], 后一半由"正方形 + `fitCenter`"保证 (图在方窗里按比例缩, 不会拉宽)
 * - "气泡显示区域改成比人物高度高约一行气泡的高度" —— 那是 [WidgetChat.Face.bubbleAreaDp]
 *
 * 三条性质最要紧: **拉大就变大** (单调) / **两头有界** / **格子读不到时落回缺省**; 外加一条
 * "设了原始大小也按倍数变", 那是插件设置里的 `faceDp` 那一条
 */
class WidgetChatTest {

    /** 拉大 → 变大, 而且是单调不减的 */
    @Test
    fun `格子越大立绘越大`() {
        val small = WidgetChat.Face.cellDp(150, 150)
        val medium = WidgetChat.Face.cellDp(260, 260)
        val large = WidgetChat.Face.cellDp(380, 560)
        assertTrue("$small / $medium / $large 该一路变大", small <= medium && medium <= large)
        assertTrue("中等那一档要比基准大", medium > WidgetChat.Face.BASE_DP)
    }

    /** 两头有界: 极小不塌, 极大不涨破 */
    @Test
    fun `两头夹住`() {
        assertEquals(WidgetChat.Face.MIN_DP, WidgetChat.Face.cellDp(40, 40))
        assertEquals(WidgetChat.Face.MAX_DP, WidgetChat.Face.cellDp(2000, 2000))
    }

    /** 尺寸读不到 (0 / 负数) 时落回缺省 */
    @Test
    fun `读不到尺寸就用缺省`() {
        assertEquals(WidgetChat.Face.BASE_DP, WidgetChat.Face.cellDp(0, 0))
        assertEquals(WidgetChat.Face.BASE_DP, WidgetChat.Face.cellDp(-1, 300))
        assertEquals(WidgetChat.Face.BASE_DP, WidgetChat.Face.cellDp(300, -1))
    }

    /** 宽与高各自限一道: 窄而高的格子按宽那一边, 宽而矮的按高那一边 */
    @Test
    fun `宽高各限一道`() {
        val narrow = WidgetChat.Face.cellDp(120, 800)
        assertEquals(104 * 55 / 100, narrow)
        val short = WidgetChat.Face.cellDp(800, 120)
        assertEquals(WidgetChat.Face.MIN_DP, short)
    }

    /**
     * 插件里设的「原始大小」按倍数乘上去, 而两头仍夹住
     *
     * 64 是基准: 设成 96 在同一格上就该更大, 设成 44 就该更小 —— 而两者都还在 44~132 里
     */
    @Test
    fun `原始大小按倍数算`() {
        val base = WidgetChat.Face.actualDp(300, 400, 64)
        val bigger = WidgetChat.Face.actualDp(300, 400, 96)
        val smaller = WidgetChat.Face.actualDp(300, 400, 44)
        assertTrue("$smaller < $base < $bigger", smaller < base && base < bigger)
        assertTrue(bigger <= WidgetChat.Face.MAX_DP)
        assertTrue(smaller >= WidgetChat.Face.MIN_DP)
        // 原始大小归零 / 越界时仍落在合法区间 (声明里的 min/max 已经夹过, 这里是第二道)
        assertTrue(WidgetChat.Face.actualDp(300, 400, 0) >= WidgetChat.Face.MIN_DP)
        assertTrue(WidgetChat.Face.actualDp(300, 400, 5000) <= WidgetChat.Face.MAX_DP)
    }

    /** 一行气泡有多高 (字号越大越高), 而下限 = 人物高度 + 一行 */
    @Test
    fun `气泡区下限跟着人物走`() {
        val small = WidgetChat.Face.oneLineDp(9)
        val big = WidgetChat.Face.oneLineDp(18)
        assertTrue("$small < $big", small < big)
        assertEquals(64 + small, WidgetChat.Face.minAreaDp(64, 9))
        // 人物变大时下限一起变高 (主人 2026-10-17 的第二条)
        assertEquals(100 + small, WidgetChat.Face.minAreaDp(100, 9))
    }

    /**
     * 行数按"这一格能放几行"算, 不再写死 3 (主人 2026-10-17: "只显示了 3 行, 超过就消失了")
     *
     * 而气泡区高度取 max(下限, 行数 × 行高): 内容多就往上长, 不会被裁
     */
    @Test
    fun `行数按格子算`() {
        // 高格子: 放得下多行 (夹在 1..MAX_ROWS)
        val tall = WidgetChat.Face.rowsFor(800, 100, 11)
        assertEquals(WidgetChat.Face.MAX_ROWS, tall)
        // 矮格子: 至少一行
        val short = WidgetChat.Face.rowsFor(160, 100, 11)
        assertEquals(1, short)
        // 尺寸读不到: 回到缺省那 3 行
        assertEquals(WidgetChat.Face.DEFAULT_ROWS, WidgetChat.Face.rowsFor(0, 64, 11))
        // 高度取 max(下限, 内容)
        val line = WidgetChat.Face.oneLineDp(11)
        // 一条都没有: 只剩那条下限
        assertEquals(64 + line, WidgetChat.Face.areaDp(64, 11, 0))
        // 三行还不到下限 (25*3=75 < 89), 所以仍是下限 —— 行数要多到超过它才往上长
        assertEquals(64 + line, WidgetChat.Face.areaDp(64, 11, 3))
        assertEquals(6 * line, WidgetChat.Face.areaDp(64, 11, 6))
    }
}


