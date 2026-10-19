package io.github.yuloong07star.luwi.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「sync 签到」那一页的账号读数 (2.7.9 第 4 条, 这一版只有壳)
 *
 * 这一条错一档的样子: 界面上冒出一个空账号, 或者到期时间显示 1970 年 —— 都不会报错, 所以把"读进来是
 * 什么"钉在这里。真接口接上之后 `sync-account.json` 由后端写, 解析这一半不改
 */
class SyncAccountTest {

    private val json = Json

    private fun root(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun aBoundAccountIsReadBack() {
        val state = SyncAccount.stateOf(
            root("""{"account":"u***@example","expiresAt":1793000000000}"""),
        )

        assertEquals("u***@example", state?.account)
        assertEquals(1793000000000L, state?.expiresAt)
    }

    /** 老文件 / 后端还没写到期: 读得到账号, 到期是 0 (界面把它显示成 `—`) */
    @Test
    fun aMissingExpiryMeansZero() {
        assertEquals(0L, SyncAccount.stateOf(root("""{"account":"u***"}"""))?.expiresAt)
    }

    /** 没有账号 / 空串都算"未连接", 界面不画一个空账号 */
    @Test
    fun aBlankAccountMeansNotConnected() {
        assertNull(SyncAccount.stateOf(root("""{"account":""}""")))
        assertNull(SyncAccount.stateOf(root("""{"expiresAt":1793000000000}""")))
        assertNull(SyncAccount.stateOf(root("""{}""")))
    }
}
