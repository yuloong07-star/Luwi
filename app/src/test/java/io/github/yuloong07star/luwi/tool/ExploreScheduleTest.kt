package io.github.yuloong07star.luwi.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 探索排期那几条纯算术 (2.7.8 批次 8)
 *
 * 三个地方错了都不会当场报错, 只会在"该跑的那天没跑"或"到点了却跳过了"上表现出来:
 *
 * - [ExploreSchedule.nextAt]: 今天还没到就今天, 过了就明天; 时刻认不出来就是 null
 * - [ExploreSchedule.skipReason]: 宿主 / 省电 / 上一场三条, 顺序不能反
 * - [ExploreSchedule.planOf]: 读坏了退回"关着 + 03:00", 而不是让整段设置页炸掉
 */
class ExploreScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val json = Json

    private fun moment(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun stamp(at: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(at), zone).toString()

    @Test
    fun theNextMomentIsTodayWhenTheTimeHasNotComeYet() {
        val now = moment("2026-10-18T01:00:00+08:00")

        assertEquals("2026-10-18T03:00+08:00", stamp(ExploreSchedule.nextAt("03:00", now, zone)!!))
    }

    @Test
    fun theNextMomentIsTomorrowOnceTheTimeHasPassed() {
        val now = moment("2026-10-18T04:00:00+08:00")

        assertEquals("2026-10-19T03:00+08:00", stamp(ExploreSchedule.nextAt("03:00", now, zone)!!))
    }

    @Test
    fun theExactMomentItselfCountsAsPassed() {
        val now = moment("2026-10-18T03:00:00+08:00")

        assertEquals("2026-10-19T03:00+08:00", stamp(ExploreSchedule.nextAt("03:00", now, zone)!!))
    }

    @Test
    fun aBadTimeHasNoNextMoment() {
        assertNull(ExploreSchedule.nextAt("25:00", 0L, zone))
        assertNull(ExploreSchedule.nextAt("tomorrow", 0L, zone))
    }

    @Test
    fun theSkipReasonsComeInOrder() {
        assertEquals("宿主没在跑", ExploreSchedule.skipReason(hostRunning = false, powerSave = false, running = false))
        assertEquals(
            "宿主没在跑",
            ExploreSchedule.skipReason(hostRunning = false, powerSave = true, running = true),
        )
        assertEquals("现在在省电模式", ExploreSchedule.skipReason(hostRunning = true, powerSave = true, running = false))
        assertEquals(
            "上一场探索还在跑",
            ExploreSchedule.skipReason(hostRunning = true, powerSave = false, running = true),
        )
        assertNull(ExploreSchedule.skipReason(hostRunning = true, powerSave = false, running = false))
    }

    @Test
    fun aMissingPlanMeansOffAtThree() {
        val plan = ExploreSchedule.planOf(JsonObject(emptyMap()))

        assertEquals(false, plan.enabled)
        assertEquals("03:00", plan.at)
        assertNull(plan.last)
    }

    @Test
    fun aBrokenTimeFallsBackToThree() {
        val plan = ExploreSchedule.planOf(
            json.parseToJsonElement("""{"enabled":true,"at":"明天早上"}""") as JsonObject,
        )

        assertEquals(true, plan.enabled)
        assertEquals("03:00", plan.at)
    }

    @Test
    fun theLastRunIsReadBack() {
        val plan = ExploreSchedule.planOf(
            json.parseToJsonElement(
                """{"enabled":true,"at":"21:30","last":{"at":123,"outcome":"skipped","why":"现在在省电模式"}}""",
            ) as JsonObject,
        )

        assertEquals("21:30", plan.at)
        assertEquals(123L, plan.last?.at)
        assertEquals("skipped", plan.last?.outcome)
        assertEquals("现在在省电模式", plan.last?.why)
        assertTrue(ExploreSchedule.describeLast(plan.last!!).startsWith("没跑"))
    }

    /* ── 每场预算 (2.7.9 第 8 条 T2) ─────────────────────────────────────── */

    /** 老文件没有这两个键: 落 30 / 60, 与宿主那边的缺省是同一对 */
    @Test
    fun aMissingBudgetMeansThirtyAndSixty() {
        val plan = ExploreSchedule.planOf(JsonObject(emptyMap()))

        assertEquals(ExploreSchedule.MINUTES_DEFAULT, plan.minutes)
        assertEquals(ExploreSchedule.STEPS_DEFAULT, plan.steps)
        assertEquals(30, plan.minutes)
        assertEquals(60, plan.steps)
    }

    /** 读进来就夹进范围 (与宿主 exploreClamp 同一个口径: 0 -> 1, 500 -> 180) */
    @Test
    fun theBudgetIsClampedWhenRead() {
        val low = ExploreSchedule.planOf(
            json.parseToJsonElement("""{"minutes":0,"steps":0}""") as JsonObject,
        )
        val high = ExploreSchedule.planOf(
            json.parseToJsonElement("""{"minutes":500,"steps":4000}""") as JsonObject,
        )

        assertEquals(1, low.minutes)
        assertEquals(1, low.steps)
        assertEquals(180, high.minutes)
        assertEquals(1000, high.steps)
    }

    /** 认不出来的值 (字符串 / 小数) 给缺省, 不把那一行当坏 JSON */
    @Test
    fun anUnreadableBudgetFallsBack() {
        val plan = ExploreSchedule.planOf(
            json.parseToJsonElement("""{"minutes":"很久","steps":12.5}""") as JsonObject,
        )

        assertEquals(30, plan.minutes)
        assertEquals(60, plan.steps)
    }

    /** 夹取那两条纯函数的边界 (写回文件之前也要过一遍) */
    @Test
    fun theBudgetIsClampedBeforeItIsWritten() {
        assertEquals(1, ExploreSchedule.clamp(-5, ExploreSchedule.MINUTES_RANGE))
        assertEquals(180, ExploreSchedule.clamp(500, ExploreSchedule.MINUTES_RANGE))
        assertEquals(60, ExploreSchedule.clamp(null, ExploreSchedule.STEPS_RANGE, ExploreSchedule.STEPS_DEFAULT))
    }
}
