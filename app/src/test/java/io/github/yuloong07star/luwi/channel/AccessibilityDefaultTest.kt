package io.github.yuloong07star.luwi.channel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「首次拿到特权通道之后要不要替主人开无障碍」那条判据 (2.7.9 第 7 条)
 *
 * 为什么值得单测: 这一条在模拟器上量不了 (没有 root / Shizuku, 通道起不来), 而它错一档的样子很安静
 * —— "每次启动都替他打开" (第二档记号丢了) 或者"永远开不了" (第一档记号写早了)
 */
class AccessibilityDefaultTest {

    /** 第一次: 没开过、没关过、也没开着 —— 替他开一次 */
    @Test
    fun firstTimeEnable() {
        assertEquals(
            AccessibilityDefault.Act.ENABLE,
            AccessibilityDefault.decide(running = false, autoMarked = false, userOff = false),
        )
    }

    /** 已经替他开过: 以后启动都不再动它 (他要是关了就由下一档记号管) */
    @Test
    fun autoMarkedDoesNothing() {
        assertEquals(
            AccessibilityDefault.Act.NOTHING,
            AccessibilityDefault.decide(running = false, autoMarked = true, userOff = false),
        )
    }

    /** **他自己关过**: 即使现在没开着也不再替他开 —— 否则"关掉"只关到下次启动 */
    @Test
    fun userOffIsRespected() {
        assertEquals(
            AccessibilityDefault.Act.NOTHING,
            AccessibilityDefault.decide(running = false, autoMarked = false, userOff = true),
        )
    }

    /** 服务本来就开着 (比如系统里手动开的): 只把"替他开过"的记号补上, 免得每一趟都算一遍 */
    @Test
    fun alreadyRunningOnlyMarks() {
        assertEquals(
            AccessibilityDefault.Act.MARK_ONLY,
            AccessibilityDefault.decide(running = true, autoMarked = false, userOff = false),
        )
    }

    /** 两个记号都在 (开过又被他自己关过): 说什么都不做 */
    @Test
    fun bothMarksDoNothing() {
        assertEquals(
            AccessibilityDefault.Act.NOTHING,
            AccessibilityDefault.decide(running = true, autoMarked = true, userOff = true),
        )
    }
}
