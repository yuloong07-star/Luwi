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

    /**
     * 「原始大小」设多少就是多少, 只有格子放不下时才按格子缩 (主人 2026-10-17: "人物被固定了")
     *
     * 原来那是"格子大小 × 设的值 ÷ 64", 大格子上 64 以上全顶到上限 —— 改 64 / 115 / 132 看起来
     * 一模一样。现在 base ≤ 格子时就是 base, base > 格子时取格子
     */
    @Test
    fun `原始大小设多少就是多少`() {
        // 大格子: 64 / 115 / 132 各不相同 (那正是"改设置要看得见"这一条)
        val small = WidgetChat.Face.actualDp(400, 700, 64)
        val medium = WidgetChat.Face.actualDp(400, 700, 115)
        val large = WidgetChat.Face.actualDp(400, 700, 132)
        assertEquals(64, small)
        assertEquals(115, medium)
        assertEquals(132, large)
        assertTrue(small < medium && medium < large)
        // 小格子: 按格子能给的最大画 (再大也放不下)
        val capped = WidgetChat.Face.actualDp(120, 120, 132)
        assertEquals(WidgetChat.Face.cellDp(120, 120), capped)
        // 尺寸读不到: 直接用你设的那个数
        assertEquals(115, WidgetChat.Face.actualDp(0, 0, 115))
        // 越界的 base 先夹进合法区间
        assertTrue(WidgetChat.Face.actualDp(400, 700, 5000) <= WidgetChat.Face.MAX_DP)
        assertTrue(WidgetChat.Face.actualDp(400, 700, 0) >= WidgetChat.Face.MIN_DP)
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



