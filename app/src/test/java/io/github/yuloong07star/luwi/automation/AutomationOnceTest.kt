package io.github.yuloong07star.luwi.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 「只响一次」那条 (2.7.8 批次 8) 与每日主动说的记账
 *
 * 这一批最容易漂的是两处: `once` 的 `at` 到底认不认"没带时区的本机时刻" (不认), 以及
 * `saidToday` 数的是不是只有"真的投出去的 task" (remind / 手动 / 被拦 / 没投出去都不算)
 */
class AutomationOnceTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = "2026-10-18T21:30:00+08:00"

    private fun rule(condition: String): AutomationRule.Rule = AutomationRule.parse(
        "probe",
        """{"name":"probe","when":$condition,"then":{"kind":"task","text":"说一句"}}""",
    )

    private fun moment(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun fired(
        hour: Int,
        action: String = "task",
        hit: Boolean = true,
        blocked: String? = null,
        delivered: Boolean? = true,
        manual: Boolean = false,
    ) = AutomationStore.Fired(
        at = moment("2026-10-18T${hour.toString().padStart(2, '0')}:00:00+08:00"),
        rule = "r$hour",
        kind = "time",
        hit = hit,
        blocked = blocked,
        reason = "test",
        action = action,
        delivered = delivered,
        count = 1,
        manual = manual,
    )

    @Test
    fun aOnceRuleIsReadAndScheduled() {
        val one = rule("""{"kind":"once","at":"$at"}""")

        assertEquals("once", one.whenKind)
        assertEquals(moment(at), one.onceAt)
        assertEquals(moment(at), one.nextFireAt(moment(at) - 1_000))
        assertNull("过了那个时刻就没有下一次", one.nextFireAt(moment(at)))
    }

    @Test
    fun anOffsetInZuluIsAcceptedToo() {
        val one = rule("""{"kind":"once","at":"2026-10-18T13:30:00Z"}""")

        assertEquals(moment(at), one.onceAt)
    }

    @Test
    fun aBareLocalTimeIsRejected() {
        val problem = runCatching {
            rule("""{"kind":"once","at":"2026-10-18T21:30:00"}""")
        }.exceptionOrNull()

        assertTrue("没带时区就不收", problem is IllegalArgumentException)
        assertTrue(
            "话里要说清要带时区, 而不是一句 JSON 错",
            problem?.message.orEmpty().contains("timezone"),
        )
    }

    @Test
    fun aGarbageMomentIsRejected() {
        assertTrue(
            runCatching { rule("""{"kind":"once","at":"tomorrow evening"}""") }.isFailure,
        )
    }

    @Test
    fun theSummarySaysOnceAtTheLocalTime() {
        val one = rule("""{"kind":"once","at":"$at"}""")

        assertEquals("只响一次 · 2026-10-18 21:30", one.condition(zone))
        assertTrue(
            one.summary(zone).startsWith("只响一次 · 2026-10-18 21:30 → 开一场会话去干"),
        )
    }

    @Test
    fun aTimeRuleStillWorks() {
        val one = rule("""{"kind":"time","at":"07:30"}""")
        val from = moment("2026-10-18T06:00:00+08:00")
        val next = one.nextFireAt(from, zone)

        assertEquals("2026-10-18T07:30+08:00", OffsetDateTime.ofInstant(Instant.ofEpochMilli(next!!), zone).toString())
    }

    @Test
    fun theSaidLimitIsClamped() {
        assertEquals(1, AutomationRule.coerceSaidLimit(0))
        assertEquals(1, AutomationRule.coerceSaidLimit(-5))
        assertEquals(10, AutomationRule.coerceSaidLimit(99))
        assertEquals(3, AutomationRule.coerceSaidLimit(3))
    }

    @Test
    fun saidTodayCountsOnlyDeliveredTaskRuns() {
        val rows = listOf(
            fired(hour = 8),
            fired(hour = 9, manual = true),
            fired(hour = 10, action = "remind"),
            fired(hour = 11, blocked = "quiet"),
            fired(hour = 12, delivered = false),
            fired(hour = 13, hit = false),
            fired(hour = 14, blocked = "said"),
        )

        assertEquals("只有 08:00 那一条算", 1, AutomationStore.saidToday(rows, "2026-10-18", zone))
    }

    @Test
    fun saidTodayOnlyCountsTheGivenDay() {
        val rows = listOf(
            fired(hour = 8),
            AutomationStore.Fired(
                at = moment("2026-10-17T08:00:00+08:00"),
                rule = "yesterday",
                kind = "time",
                hit = true,
                blocked = null,
                reason = "test",
                action = "task",
                delivered = true,
                count = 1,
            ),
        )

        assertEquals(1, AutomationStore.saidToday(rows, "2026-10-18", zone))
    }
}
