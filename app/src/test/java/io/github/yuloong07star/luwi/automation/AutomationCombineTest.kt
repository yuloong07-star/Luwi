package io.github.yuloong07star.luwi.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 组合判定那一批 (2.7.8 批次 9) 的纯函数判据: 真值表 / 校验 / 老形状照旧
 *
 * 这一批最容易漂的是三处: `combine` 的真值 (两边各一种, 而 `any` 的空表与 `all` 的空表**不一样**),
 * 组合的校验闸 (2~5 个, 不许嵌套), 以及**老形状一个字节不动** —— 最后那几条拿 2.5.0 起就在用的
 * 那条 `time` 规则来量, 它要是被这次改动读坏, 主人家里的老规则会一起失效
 */
class AutomationCombineTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun rule(when_: String): AutomationRule.Rule = AutomationRule.parse(
        "probe",
        """{"name":"probe","when":$when_,"then":{"kind":"remind","text":"说一句"}}""",
    )

    private fun problemOf(when_: String): String =
        runCatching { rule(when_) }.exceptionOrNull()?.message.orEmpty()

    private fun moment(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    /* ── 一、combine 的真值表 ─────────────────────────────────────────────── */

    @Test
    fun allIsTrueOnlyWhenEveryConditionHolds() {
        assertTrue(AutomationRule.combine("all", listOf(true, true)))
        assertFalse(AutomationRule.combine("all", listOf(true, false)))
        assertFalse(AutomationRule.combine("all", listOf(false, false)))
        assertTrue(AutomationRule.combine("all", listOf(true, true, true, true, true)))
        assertFalse(AutomationRule.combine("all", listOf(true, true, true, true, false)))
    }

    @Test
    fun anyIsTrueWhenOneConditionHolds() {
        assertTrue(AutomationRule.combine("any", listOf(true, false)))
        assertTrue(AutomationRule.combine("any", listOf(false, true)))
        assertFalse(AutomationRule.combine("any", listOf(false, false)))
        assertTrue(AutomationRule.combine("any", listOf(false, false, false, false, true)))
    }

    @Test
    fun anEmptyListIsFalseForBothAndUnknownKindsAreFalse() {
        assertFalse(AutomationRule.combine("all", emptyList()))
        assertFalse(AutomationRule.combine("any", emptyList()))
        assertFalse(AutomationRule.combine("time", listOf(true)))
    }

    /* ── 二、组合的形状与校验 ─────────────────────────────────────────────── */

    @Test
    fun aCombinationKeepsItsSubConditionsInOrder() {
        val one = rule(
            """{"kind":"all","conds":[
                {"kind":"unlock","firstOfDay":true,"within":3600},
                {"kind":"time","at":"06:00","within":18000}]}""",
        )

        assertEquals("all", one.whenKind)
        assertEquals(listOf("unlock", "time"), one.subKinds)
        assertEquals(2, one.conds.size)
        assertTrue(one.involves("unlock"))
        assertTrue(one.involves("time"))
        assertFalse(one.involves("notice"))
    }

    @Test
    fun anAnyCombinationIsReadableToo() {
        val one = rule(
            """{"kind":"any","conds":[
                {"kind":"time","at":"23:30"},
                {"kind":"light","below":20,"forSeconds":60}]}""",
        )

        assertEquals(listOf("time", "light"), one.subKinds)
        assertEquals("当 每天 23:30 或 持续 60 秒 光照低于 20 lux 时", one.condition(zone))
    }

    @Test
    fun aCombinationNeedsTwoToFiveConditions() {
        assertTrue(problemOf("""{"kind":"all","conds":[{"kind":"time","at":"07:30"}]}""").contains("2..5"))
        assertTrue(
            problemOf(
                """{"kind":"all","conds":[
                    {"kind":"time","at":"07:30"},{"kind":"time","at":"08:30"},
                    {"kind":"time","at":"09:30"},{"kind":"time","at":"10:30"},
                    {"kind":"time","at":"11:30"},{"kind":"time","at":"12:30"}]}""",
            ).contains("2..5"),
        )
        assertTrue(problemOf("""{"kind":"any","conds":[]}""").contains("2..5"))
    }

    @Test
    fun nestingIsRefusedWithAWayOut() {
        val problem = problemOf(
            """{"kind":"all","conds":[
                {"kind":"any","conds":[{"kind":"time","at":"07:30"},{"kind":"time","at":"08:30"}]},
                {"kind":"time","at":"09:00"}]}""",
        )

        assertTrue(problem.contains("not supported"))
        assertTrue("要说得出来它错在哪儿", problem.contains("conds[0]"))
    }

    @Test
    fun anUnknownSubKindIsRefused() {
        assertTrue(
            problemOf("""{"kind":"all","conds":[{"kind":"battery"},{"kind":"time","at":"07:30"}]}""")
                .contains("conds[0].kind"),
        )
    }

    @Test
    fun aBrokenSubConditionIsRefusedWithItsIndex() {
        val problem = problemOf(
            """{"kind":"all","conds":[
                {"kind":"time","at":"07:30"},
                {"kind":"light","below":20,"above":30}]}""",
        )

        assertTrue(problem.contains("conds[1]"))
        assertTrue(problem.contains("lower than"))
    }

    @Test
    fun theWithinWindowIsChecked() {
        assertTrue(
            problemOf(
                """{"kind":"all","conds":[
                    {"kind":"time","at":"07:30","within":1},
                    {"kind":"unlock"}]}""",
            ).contains("within"),
        )
    }

    @Test
    fun unlockAndActivityAreTheirOwnKinds() {
        val unlock = rule("""{"kind":"unlock","firstOfDay":true}""")
        val activity = rule("""{"kind":"activity","withinMinutes":10}""")

        assertEquals("今天第一次解锁后", unlock.condition(zone))
        assertEquals("最近 10 分钟在动", activity.condition(zone))
        assertEquals("最近一次解锁后", rule("""{"kind":"unlock"}""").condition(zone))
    }

    /* ── 三、新鲜窗口的缺省 ──────────────────────────────────────────────── */

    @Test
    fun theDefaultWindowFollowsTheKind() {
        fun window(kind: String, extra: String = ""): Int {
            val one = rule("""{"kind":"all","conds":[{"kind":"$kind"$extra},{"kind":"time","at":"07:30"}]}""")
            return AutomationRule.windowSeconds(kind, one.conds.first())
        }

        assertEquals("time 那 1800 秒是既有迟到闸的显式化", 1800, window("time", """, "at":"23:45""""))
        assertEquals(120, window("foreground", """, "package":"com.tencent.mm""""))
        assertEquals(300, window("light", """, "below":20"""))
        assertEquals(300, window("activity"))
        assertEquals("自己写的 within 覆盖缺省", 60, window("activity", """, "within":60"""))
    }

    /* ── 四、排闹钟: 组合里那条 time 也要排上 ────────────────────────────── */

    @Test
    fun aCombinationWithATimeSubConditionStillGetsAnAlarm() {
        val one = rule(
            """{"kind":"all","conds":[
                {"kind":"unlock","firstOfDay":true},
                {"kind":"time","at":"06:00"}]}""",
        )
        val from = moment("2026-10-18T05:00:00+08:00")
        val next = one.nextFireAt(from, zone)

        assertEquals(
            "2026-10-18T06:00+08:00",
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(next!!), zone).toString(),
        )
        assertEquals(null, rule("""{"kind":"all","conds":[
            {"kind":"unlock"},{"kind":"activity"}]}""").nextFireAt(from, zone))
    }

    /* ── 五、老形状一个字节不动 ──────────────────────────────────────────── */

    @Test
    fun theOldTimeRuleIsUntouched() {
        val one = rule("""{"kind":"time","at":"07:30"}""")

        assertEquals("time", one.whenKind)
        assertEquals("每天 07:30", one.condition(zone))
        assertEquals("每天 07:30 → 提醒一声: 说一句", one.summary(zone))
        assertEquals(
            "2026-10-18T07:30+08:00",
            OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(one.nextFireAt(moment("2026-10-18T06:00:00+08:00"), zone)!!),
                zone,
            ).toString(),
        )
    }

    @Test
    fun everyKindNameIsStable() {
        assertEquals(
            listOf("notice", "foreground", "light", "time", "place", "weather", "unlock", "activity"),
            AutomationRule.LEAF_KINDS,
        )
        assertEquals(
            AutomationRule.LEAF_KINDS + "once",
            AutomationRule.WATCH_KINDS,
        )
        assertEquals(
            AutomationRule.WATCH_KINDS + listOf("all", "any"),
            AutomationRule.WHEN_KINDS,
        )
    }
}
