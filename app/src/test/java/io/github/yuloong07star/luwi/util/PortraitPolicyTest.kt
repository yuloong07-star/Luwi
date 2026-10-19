package io.github.yuloong07star.luwi.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手机锁竖屏的判据 (2.7.9 第 9 条)
 *
 * 为什么值得单测: 它错一档就是"平板被锁成竖屏"或者"手机还跟着转", 而这两件事各要装一次包、转一次屏
 * 才看得出来; 式子本身没有设备也能量, 就把它钉在这里
 */
class PortraitPolicyTest {

    /** 手机那一档 (Pixel 7 = 412 dp): 锁竖屏, 599 是这条线的最后一格 */
    @Test
    fun `手机档锁竖屏`() {
        assertEquals(PortraitPolicy.Orientation.PORTRAIT, PortraitPolicy.orientationFor(411))
        assertEquals(PortraitPolicy.Orientation.PORTRAIT, PortraitPolicy.orientationFor(599))
    }

    /** 平板那一档 (Pixel Tablet = 800 dp 宽 / 800 dp 最短边): 跟随系统, 600 是第一条 */
    @Test
    fun `大屏档跟随系统`() {
        assertEquals(PortraitPolicy.Orientation.FOLLOW_SYSTEM, PortraitPolicy.orientationFor(600))
        assertEquals(PortraitPolicy.Orientation.FOLLOW_SYSTEM, PortraitPolicy.orientationFor(800))
        assertEquals(PortraitPolicy.Orientation.FOLLOW_SYSTEM, PortraitPolicy.orientationFor(1280))
    }

    /** 宽度未知 (0 / 负数) 先按手机档: 先钉竖屏, 配置落下之后下一次判定自己会更正 */
    @Test
    fun `宽度未知时先按手机档`() {
        assertEquals(PortraitPolicy.Orientation.PORTRAIT, PortraitPolicy.orientationFor(0))
        assertEquals(PortraitPolicy.Orientation.PORTRAIT, PortraitPolicy.orientationFor(-1))
    }
}
