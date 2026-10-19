package io.github.yuloong07star.luwi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「缺省展开哪几段」那条纯算术 ([defaultCollapsed] 与 [SettingsSectionsAlwaysOpen])
 *
 * 为什么值得单测: 2026-10-16 第 1 条把这条账翻了个面 —— 从前 `collapsed` 的初值是空集 (缺省全展开,
 * 于是以后新加一段时它自己就是展开的), 现在缺省只展开白名单那七段 (省电 + 「常用」组六段), 新加一段
 * 天然收起 (D2)。做错的样子都很安静: 白名单里多写一个段名, 那一段就跟着缺省展开; 页面上新加一段而
 * 忘了加进 [SettingsSectionKeys], 它会恰好展开着, 界面上一点异常都看不出来。所以判据钉在纯函数上,
 * 不需要设备
 */
class SettingsSectionsTest {

    /** D1 拍板的那六段: 省电那一段 + 「常用」一级分组下那五段 (导航那一段 2026-10-17 撤了) */
    private val common = listOf("power", "appearance", "channel", "ball", "wake", "speak")

    /** 「能力」那一组的 13 段, 缺省全收着 (无障碍与 OCR 2026-10-19 并进「特权通道」了) */
    private val abilities = listOf(
        "notifications", "screenshot", "look", "voice", "lock",
        "explore", "automation", "quick", "plugin", "skills", "linux", "sync", "permissions",
    )

    @Test
    fun commonSectionsAreOpen() {
        val collapsed = defaultCollapsed(SettingsSectionKeys)

        assertEquals("白名单就是 D1 那六段 (导航撤了)", common.toSet(), SettingsSectionsAlwaysOpen)
        common.forEach { key ->
            assertFalse("$key 是常用那一段, 缺省应当开着", key in collapsed)
        }
    }

    @Test
    fun abilitySectionsAreCollapsed() {
        val collapsed = defaultCollapsed(SettingsSectionKeys)

        assertEquals("缺省收起来的就是能力那 13 段", abilities.toSet(), collapsed)
        abilities.forEach { key ->
            assertTrue("$key 在能力组, 缺省应当收着", key in collapsed)
        }
    }

    /** D2: 以后新加一段, 它天然是收起来的 —— 给一个页面上还没有的段名, 它也要进那一份 */
    @Test
    fun aSectionAddedLaterIsCollapsed() {
        val collapsed = defaultCollapsed(SettingsSectionKeys + "future-thing")

        assertTrue("新加的段必须缺省收起", "future-thing" in collapsed)
        assertEquals("只多收它一个", abilities.size + 1, collapsed.size)
    }

    /** 白名单里的名字必须真的在页面上有那一段 (写错一个字的话它会永远开不了) */
    @Test
    fun everyWhitelistedKeyIsOnThePage() {
        SettingsSectionsAlwaysOpen.forEach { key ->
            assertTrue("$key 不在 SettingsSectionKeys 里 (那一段名写错了?)", key in SettingsSectionKeys)
        }
    }

    /**
     * 退出设置页再进来: 冷启动是缺省那一份, 主人手动点开过一段之后照他那份
     *
     * 2026-10-17 在模拟器上量到的原样是"再进来又收起来了" —— `rememberSaveable` 只活一次界面,
     * 退出再进是新建的组合
     */
    @Test
    fun reenteringKeepsWhatTheUserToggled() {
        assertEquals(
            "冷启动 (进程里还没有那一份): 缺省那 13 段收着",
            defaultCollapsed(SettingsSectionKeys),
            initialCollapsed(null),
        )

        val toggled = defaultCollapsed(SettingsSectionKeys) - "notifications"
        assertEquals(
            "主人点开过「通知」: 再进来它仍是开的",
            toggled,
            initialCollapsed(toggled),
        )
        assertFalse("那一段真的不在收起的名单里", "notifications" in initialCollapsed(toggled))
    }

    /** 19 段一份名单, 各自只出现一次 —— 重复了的话 `collapsed` 那份集合会把两段看成一段 */
    @Test
    fun sectionKeysAreUnique() {
        assertEquals("段名不能重复", SettingsSectionKeys.size, SettingsSectionKeys.toSet().size)
        assertEquals("现在页面上是 19 段", 19, SettingsSectionKeys.size)
        assertEquals("常用六段 + 能力十三段", SettingsSectionKeys.size, common.size + abilities.size)
    }
}
