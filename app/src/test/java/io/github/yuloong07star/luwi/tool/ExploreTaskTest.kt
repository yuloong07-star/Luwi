package io.github.yuloong07star.luwi.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 探索那张任务卡里唯一能脱离 Android 测的一小块: `explore.json` 的 run 账怎么读成"还在跑的那一场"
 *
 * 三个判据都很安静: 读错一个, 排期就会在"上一场还在跑"与"可以开新的"之间做相反的决定
 */
class ExploreTaskTest {

    private fun state(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    private val now = 1_792_230_000_000L

    @Test
    fun aLiveRunIsBack() {
        val one = ExploreTask.runningOf(
            state("""{"run":{"session":"s1","deadline":${now + 60_000},"steps":3,"stepLimit":60}}"""),
            now,
        )

        assertEquals("s1", one?.session)
        assertEquals(now + 60_000, one?.deadline)
        assertEquals(3, one?.steps)
        assertEquals(60, one?.stepLimit)
    }

    @Test
    fun aRunThatIsOverIsNotRunning() {
        val one = ExploreTask.runningOf(
            state("""{"run":{"session":"s1","deadline":${now + 60_000},"over":true}}"""),
            now,
        )

        assertNull("over=true 是「到点了等收尾」, 不是「还在跑」", one)
    }

    @Test
    fun aRunPastItsDeadlineIsNotRunning() {
        assertNull(
            ExploreTask.runningOf(state("""{"run":{"session":"s1","deadline":$now}}"""), now),
        )
    }

    @Test
    fun noRunAtAllIsNotRunning() {
        assertNull(ExploreTask.runningOf(state("""{"run":null}"""), now))
        assertNull(ExploreTask.runningOf(state("""{}"""), now))
    }

    @Test
    fun aRunWithoutASessionIsNotRunning() {
        assertNull(
            ExploreTask.runningOf(
                state("""{"run":{"session":"","deadline":${now + 60_000}}}"""),
                now,
            ),
        )
    }
}
