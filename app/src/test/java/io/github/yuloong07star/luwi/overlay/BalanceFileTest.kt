package io.github.yuloong07star.luwi.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看余额」那份文件的解析 ([BalanceFile])
 *
 * 为什么值得单测: 这一档最怕的是"拿不到就编一个 0" —— 文件没写 / 写坏 / 宿主报失败, 三种都得**如实**,
 * 而且哪一种都不能让那块文本框空着或崩掉。解析是纯的 (只吃一个 String), 所以四种都在这儿钉住
 */
class BalanceFileTest {

    @Test
    fun `宿主报了数就照单全收`() {
        val snapshot = BalanceFile.parse(
            """{"v":1,"at":1760000000000,"ok":true,"currency":"CNY","value":"12.34","bonus":"5.00","reason":""}""",
        )
        assertTrue(snapshot != null && snapshot.ok)
        snapshot!!
        assertEquals(1_760_000_000_000L, snapshot.at)
        assertEquals("CNY", snapshot.currency)
        assertEquals("12.34", snapshot.value)
        assertEquals("5.00", snapshot.bonus)
        assertEquals("", snapshot.reason)
    }

    @Test
    fun `赠金那一档没有时是空串`() {
        val snapshot = BalanceFile.parse(
            """{"v":1,"at":1760000000000,"ok":true,"currency":"USD","value":"0.01"}""",
        )
        assertTrue(snapshot != null && snapshot.ok)
        assertEquals("", snapshot!!.bonus)
    }

    @Test
    fun `失败也是一份完整的快照, 原话一个字不改`() {
        val snapshot = BalanceFile.parse(
            """{"v":1,"at":1760000000000,"ok":false,"reason":"the DeepSeek account is not signed in on this host"}""",
        )
        assertTrue(snapshot != null && !snapshot.ok)
        assertEquals("", snapshot!!.value)
        assertEquals("the DeepSeek account is not signed in on this host", snapshot.reason)
    }

    /**
     * 「当前模型不支持查询余额」那一档 (2026-10-16 主人定的口径): 宿主先认模型再认余额源, 提供方不在
     * 表里就带 `unsupported: true` —— 应用把它说成「当前模型不支持查询余额」, 不再念宿主的英文原话
     */
    @Test
    fun `当前模型没有余额源时带 unsupported 那一面`() {
        val snapshot = BalanceFile.parse(
            """{"v":1,"at":1760000000000,"ok":false,"unsupported":true,"reason":"no balance source for the current model (xiaomi/mimo-v2.6-flash)"}""",
        )
        assertTrue(snapshot != null && !snapshot.ok && snapshot!!.unsupported)
        // 旧宿主写的文件没有这一个键: 当 false, 走 reason 那条 (不把老文件误报成"不支持")
        val legacy = BalanceFile.parse(
            """{"v":1,"at":1760000000000,"ok":false,"reason":"the DeepSeek account is not signed in on this host"}""",
        )
        assertTrue(legacy != null && !legacy!!.unsupported)
    }

    /**
     * v2 那一份 (2026-10-17 加了厂商名与两档答案): 金额那一档照旧, 只是多带一个名字 —— 宿主用它说
     * 清"这一笔是谁家的", 而旧宿主写的那一份**没有那几个新键**, 缺省就是"没有名字的金额"
     */
    @Test
    fun `v2 的钱带上厂商名, 旧文件缺省是金额`() {
        val named = BalanceFile.parse(
            """{"v":2,"at":1760000000000,"ok":true,"provider":"moonshot","model":"kimi-k2","label":"Moonshot",
               "kind":"money","currency":"CNY","value":"12.34","bonus":"5.00","percent":0,"reason":""}""",
        )
        assertTrue(named != null && named.ok)
        assertEquals("Moonshot", named!!.label)
        assertEquals(BalanceFile.KIND_MONEY, named.kind)
        assertEquals(0, named.percent)

        val legacy = BalanceFile.parse(
            """{"v":1,"at":1760000000000,"ok":true,"currency":"CNY","value":"12.34"}""",
        )
        assertTrue(legacy != null && legacy.ok)
        legacy!!
        assertEquals("", legacy.label)
        assertEquals(BalanceFile.KIND_MONEY, legacy.kind)
        assertEquals(0, legacy.percent)
    }

    /**
     * 订阅制那几家给的是「还剩百分之多少」, 不是金额 —— 那一档认 percent, 货币与金额两栏没有意义
     * (宿主写空), 所以它一个字都不许被读成钱
     */
    @Test
    fun `额度那一档认 percent`() {
        val snapshot = BalanceFile.parse(
            """{"v":2,"at":1760000000000,"ok":true,"provider":"zai","label":"Zhipu GLM","kind":"quota","percent":62,"reason":""}""",
        )
        assertTrue(snapshot != null && snapshot.ok)
        assertEquals(BalanceFile.KIND_QUOTA, snapshot!!.kind)
        assertEquals(62, snapshot.percent)
        assertEquals("", snapshot.value)
    }

    @Test
    fun `文件没写 or 写坏时就是 null (那是还没写过, 不是没余额)`() {
        assertNull(BalanceFile.parse(""))
        assertNull(BalanceFile.parse("不是 JSON"))
        assertNull(BalanceFile.parse("""{"ok":true,"value":"1"}"""))  // 缺 at, 那一份不可信
        assertNull(BalanceFile.parse("""{"at":1,"value":"1"}"""))     // 缺 ok, 那一份不可信
        assertTrue(BalanceFile.parse("""{"at":1,"ok":true,"value":"1"}""")!!.ok)
    }
}
