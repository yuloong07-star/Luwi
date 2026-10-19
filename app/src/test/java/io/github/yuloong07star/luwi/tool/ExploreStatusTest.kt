package io.github.yuloong07star.luwi.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宿主写进 `explore.json` 的那段 `status` 怎么读 (2.7.9 T5)
 *
 * 错一档的样子很安静: 设置页多一行空的"为什么是它", 或者省电探针那次没读到却显示正常 —— 两种都不会
 * 报错, 所以把"读进来是什么"钉在这里
 */
class ExploreStatusTest {

    private val json = Json

    private fun root(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun theFourFactsAreReadBack() {
        val status = ExploreStatus.snapshotOf(
            root(
                """{"v":1,"run":null,"status":{"preset":"explore","presetWhy":"the explore preset (explore)","lastError":null,"powerSaveUnknown":true}}""",
            ),
        )

        assertEquals("explore", status?.preset)
        assertEquals("the explore preset (explore)", status?.presetWhy)
        assertNull(status?.lastError)
        assertEquals(true, status?.powerSaveUnknown)
    }

    /** 还没有这一段 (老版本宿主的文件): 整段 null, 设置页不画那四行 */
    @Test
    fun aFileWithoutStatusIsNull() {
        assertNull(ExploreStatus.snapshotOf(root("""{"v":1,"run":null}""")))
    }

    /** 空串与缺键都按"没有"算: 设置页不该多出一行只有标签的读数 */
    @Test
    fun blankTextsMeanNothing() {
        val status = ExploreStatus.snapshotOf(
            root("""{"status":{"preset":"","presetWhy":"","lastError":"","powerSaveUnknown":false}}"""),
        )

        assertNull(status?.preset)
        assertNull(status?.presetWhy)
        assertNull(status?.lastError)
        assertEquals(false, status?.powerSaveUnknown)
    }

    /** 运行时那本账的其它键 (run / said) 不影响这一段 */
    @Test
    fun otherKeysAreIgnored() {
        val status = ExploreStatus.snapshotOf(
            root("""{"run":{"session":"s1"},"said":2,"status":{"preset":"standard","lastError":"boom"}}"""),
        )

        assertEquals("standard", status?.preset)
        assertEquals("boom", status?.lastError)
        assertNull("没写就是空, 不是 false 之外的什么东西", status?.presetWhy)
    }
}
