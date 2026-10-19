package io.github.yuloong07star.luwi.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动那次自动更新的判据 (2.7.9 第 3 条)
 *
 * 三条账在这里量: **该不该弹** (施工单 §2.1 那张真值表, 一行一条), **同一天第二次不查** (那个记号
 * 真的落下去了, 也没再打一次网络), 以及**失败只落读数** (不抛、不弹)
 *
 * 网络与 SharedPreferences 都不在这里: [AutoUpdate.once] 吃的是 [AutoUpdate.Prefs] 与一个取数 lambda,
 * 所以这一整套没有设备也能跑
 */
class AutoUpdateTest {

    private fun release(version: String) = ReleaseJson.Release(
        version = version,
        name = "",
        notes = "",
        pageUrl = "",
        published = "",
        apkName = null,
        apkBytes = 0L,
        apkUrl = null,
    )

    /** 内存里那一份偏好: 与真机上那三个键同一个面 */
    private class FakePrefs : AutoUpdate.Prefs {
        val values = mutableMapOf<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String) {
            values[key] = value
        }
    }

    private val today = "2026-10-19"
    private val yesterday = "2026-10-18"
    private val local = "2.7.8"

    /* ── 真值表 ─────────────────────────────────────────────────────────── */

    /** 第一行: 今天查过就不再查 (手动那条不受它管) */
    @Test
    fun `同一天不查`() {
        assertEquals(
            AutoUpdate.Verdict.SKIP_TODAY,
            AutoUpdate.shouldCheck(today, null, release("2.7.9"), local, today),
        )
    }

    /** 第二行: 没拿到结果就不查 —— "没有新版本"与"查不到"是两件事, 只有后者落读数 */
    @Test
    fun `没结果不查`() {
        assertEquals(
            AutoUpdate.Verdict.SKIP_NO_REMOTE,
            AutoUpdate.shouldCheck(yesterday, null, null, local, today),
        )
    }

    /** 第三行: 不比手上新就不查 (同版本与更旧的版本各一条) */
    @Test
    fun `远端不比手上新就不弹`() {
        assertEquals(
            AutoUpdate.Verdict.SKIP_NOT_NEWER,
            AutoUpdate.shouldCheck(yesterday, null, release("2.7.8"), local, today),
        )
        assertEquals(
            AutoUpdate.Verdict.SKIP_NOT_NEWER,
            AutoUpdate.shouldCheck(yesterday, null, release("2.7.7"), local, today),
        )
    }

    /** 第四行: 人点过「不再提示」的那一版不弹, 但读数照记 (这一条由 [AutoUpdate.once] 保证) */
    @Test
    fun `点了不再提示的那一版不弹`() {
        assertEquals(
            AutoUpdate.Verdict.SKIP_SKIPPED,
            AutoUpdate.shouldCheck(yesterday, "2.7.9", release("2.7.9"), local, today),
        )
    }

    /** 第五行: 其余情况弹; 忽略过 2.7.9 之后再出 2.7.10, 仍然要弹 */
    @Test
    fun `其余情况弹`() {
        assertEquals(
            AutoUpdate.Verdict.SHOW,
            AutoUpdate.shouldCheck(yesterday, null, release("2.7.9"), local, today),
        )
        assertEquals(
            AutoUpdate.Verdict.SHOW,
            AutoUpdate.shouldCheck(yesterday, "2.7.9", release("2.7.10"), local, today),
        )
    }

    /* ── 记账 ───────────────────────────────────────────────────────────── */

    /** 同一天第二次: 结论是"今天查过", 而且**网络一次都不再打** */
    @Test
    fun `同一天第二次不查也不打网络`() {
        val prefs = FakePrefs()
        var fetches = 0
        val fetch = {
            fetches += 1
            release("2.7.9")
        }
        val first = AutoUpdate.once(prefs, local, today, 0L, fetch)
        val second = AutoUpdate.once(prefs, local, today, 1L, fetch)
        assertEquals(AutoUpdate.Verdict.SHOW, first.verdict)
        assertEquals(AutoUpdate.Verdict.SKIP_TODAY, second.verdict)
        assertEquals("网络只该打一次", 1, fetches)
        assertEquals(today, prefs.values[AutoUpdate.KEY_CHECKED_DAY])
    }

    /** 查到了: 读数是一行 JSON, 四个键一个不少 */
    @Test
    fun `查到了就把读数写成一行 JSON`() {
        val prefs = FakePrefs()
        val outcome = AutoUpdate.once(prefs, local, today, 1234L) { release("2.7.9") }
        assertEquals(AutoUpdate.Verdict.SHOW, outcome.verdict)
        assertEquals(
            """{"at":1234,"result":"newer","remote":"2.7.9","detail":null}""",
            prefs.values[AutoUpdate.KEY_LAST],
        )
    }

    /** 点过「不再提示」的那一版: 结论是 skipped, 读数里也是 skipped (给排查看) */
    @Test
    fun `被忽略的那一版在读数里写 skipped`() {
        val prefs = FakePrefs()
        prefs.put(AutoUpdate.KEY_SKIP, "2.7.9")
        val outcome = AutoUpdate.once(prefs, local, today, 7L) { release("2.7.9") }
        assertEquals(AutoUpdate.Verdict.SKIP_SKIPPED, outcome.verdict)
        assertTrue(prefs.values.getValue(AutoUpdate.KEY_LAST).contains("\"result\":\"skipped\""))
        assertEquals("2.7.9", outcome.release?.version)
    }

    /** 失败: 不抛、不弹, 只落 failed 读数 —— 这一天也算查过了 (限流那道闸) */
    @Test
    fun `失败只落读数`() {
        val prefs = FakePrefs()
        val outcome = AutoUpdate.once(prefs, local, today, 42L) {
            throw IllegalStateException("HTTP 403")
        }
        assertEquals(AutoUpdate.Verdict.SKIP_NO_REMOTE, outcome.verdict)
        assertNull(outcome.release)
        val line = prefs.values.getValue(AutoUpdate.KEY_LAST)
        assertTrue(line.contains("\"result\":\"failed\""))
        assertTrue(line.contains("HTTP 403"))
        assertEquals(today, prefs.values[AutoUpdate.KEY_CHECKED_DAY])
    }
}
