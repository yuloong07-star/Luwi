package io.github.yuloong07star.luwi.plugin

import io.github.yuloong07star.luwi.plugin.widget.WidgetChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面对话组件里那块立绘多大 (2.7.8 批次 6)
 *
 * 主人 2026-10-17: "人物应该随边框变大而变大, 但长宽要等比, 防止变形" —— 前一半是这条算式, 后一半
 * 由"正方形 + `fitCenter`"保证 (图在方窗里按比例缩, 不会横向拉宽)。所以这里要钉的是四件事:
 *
 * 1. **拉大就变大**: 格子越大, 那个数越大 (单调)
 * 2. **两头有界**: 再大不超过 [WidgetChat.Face.MAX_DP], 再小不低于 [WidgetChat.Face.MIN_DP]
 * 3. **格子读不到时落回原来那个数** (64): 刚装上 / 桌面还没报尺寸那一拍
 * 4. 两个方向给的是**同一个数** (正方形): 于是等比不靠布局, 靠这个数本身
 */
class WidgetChatTest {

    /** 拉大 → 变大, 而且是单调不减的 */
    @Test
    fun `格子越大立绘越大`() {
        val small = WidgetChat.Face.sizeDp(150, 150)
        val medium = WidgetChat.Face.sizeDp(260, 260)
        val large = WidgetChat.Face.sizeDp(380, 560)
        assertTrue("$small / $medium / $large 该一路变大", small <= medium && medium <= large)
        assertTrue("中等那一档要比 64 大", medium > WidgetChat.Face.DEFAULT_DP)
    }

    /** 两头有界: 极小不塌, 极大不涨破 */
    @Test
    fun `两头夹住`() {
        assertEquals(WidgetChat.Face.MIN_DP, WidgetChat.Face.sizeDp(40, 40))
        assertEquals(WidgetChat.Face.MAX_DP, WidgetChat.Face.sizeDp(2000, 2000))
    }

    /** 尺寸读不到 (0 / 负数) 时落回原来那个 64 */
    @Test
    fun `读不到尺寸就用缺省`() {
        assertEquals(WidgetChat.Face.DEFAULT_DP, WidgetChat.Face.sizeDp(0, 0))
        assertEquals(WidgetChat.Face.DEFAULT_DP, WidgetChat.Face.sizeDp(-1, 300))
        assertEquals(WidgetChat.Face.DEFAULT_DP, WidgetChat.Face.sizeDp(300, -1))
    }

    /** 宽与高各自限一道: 窄而高的格子按宽那一边, 宽而矮的按高那一边 */
    @Test
    fun `宽高各限一道`() {
        // 很窄很长: 宽度那一边先卡住 (55% 的可用宽)
        val narrow = WidgetChat.Face.sizeDp(120, 800)
        assertEquals(104 * 55 / 100, narrow)
        // 很宽很矮: 高度那一边先卡住 (34% 的可用高), 而它已经低过下限, 所以落成 [MIN_DP]
        val short = WidgetChat.Face.sizeDp(800, 120)
        assertEquals(WidgetChat.Face.MIN_DP, short)
    }

    /** 那个数是**正方形**的边长: 两个方向传给 RemoteViews 的是同一个值 */
    @Test
    fun `同一个数给宽也给它高`() {
        val size = WidgetChat.Face.sizeDp(300, 400)
        assertEquals(size, WidgetChat.Face.sizeDp(300, 400))
        assertTrue(size in WidgetChat.Face.MIN_DP..WidgetChat.Face.MAX_DP)
    }
}
