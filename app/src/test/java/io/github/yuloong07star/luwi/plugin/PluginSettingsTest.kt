package io.github.yuloong07star.luwi.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件自己的设置项 (协议第 10 节 `ui.settings`, 2.7.8 批次 6)
 *
 * 这一层是"插件声明, 主人调值": 声明写错要在**装的时候**就点出来 (而不是设备上画出一个画不出的
 * 控件), 而盘上那个值坏了要落回缺省 —— 三件事都是纯函数, 于是没有设备也能量
 */
class PluginSettingsTest {

    private fun decl(text: String): JsonArray =
        Json.parseToJsonElement(text).jsonObject["settings"]!!.jsonArray

    /** 三条声明按顺序读出来, 缺省与上下限都在 */
    @Test
    fun `读声明`() {
        val list = PluginSettings.parse(
            decl(
                """
                {"settings":[
                  {"key":"bubbleSp","type":"int","default":11,"title":"气泡字号","min":9,"max":24},
                  {"key":"face","type":"enum","default":"ball","title":"显示的人物",
                   "options":[{"value":"ball","label":"与浮标相同外观"},
                              {"value":"art/face.png","label":"插件自带的那个人"}]},
                  {"key":"faceDp","type":"int","default":64,"title":"人物原始大小","min":44,"max":132}]}
                """,
            ),
        )
        assertEquals(listOf("bubbleSp", "face", "faceDp"), list.map { it.key })
        assertEquals("ball", (list[1].defaultValue as JsonPrimitive).content)
        assertEquals(2, list[1].options.size)
        assertEquals(44L, list[2].min)
    }

    /** 声明写错就拒, 而且点得出是哪一条 */
    @Test
    fun `坏的声明要拒`() {
        // 类型不认识
        assertThrows(IllegalArgumentException::class.java) {
            PluginSettings.parse(decl("""{"settings":[{"key":"a","type":"float","default":1,"title":"x"}]}"""))
        }
        // int 的缺省落在范围外
        assertThrows(IllegalArgumentException::class.java) {
            PluginSettings.parse(
                decl("""{"settings":[{"key":"a","type":"int","default":99,"title":"x","min":1,"max":10}]}"""),
            )
        }
        // enum 的缺省不在候选项里
        assertThrows(IllegalArgumentException::class.java) {
            PluginSettings.parse(
                decl(
                    """{"settings":[{"key":"a","type":"enum","default":"c","title":"x",
                       "options":[{"value":"a","label":"A"}]}]}""",
                ),
            )
        }
        // 同一个键写了两遍
        assertThrows(IllegalArgumentException::class.java) {
            PluginSettings.parse(
                decl(
                    """{"settings":[{"key":"a","type":"int","default":1,"title":"x"},
                       {"key":"a","type":"int","default":2,"title":"y"}]}""",
                ),
            )
        }
    }

    /** 值先夹一遍: 整数按 min/max, 枚举只认候选项, 布尔只认 true/false */
    @Test
    fun `值要夹一遍`() {
        val list = PluginSettings.parse(
            decl(
                """
                {"settings":[
                  {"key":"n","type":"int","default":5,"title":"数","min":1,"max":10},
                  {"key":"e","type":"enum","default":"a","title":"枚举",
                   "options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]},
                  {"key":"b","type":"bool","default":false,"title":"开关"}]}
                """,
            ),
        )
        val int = list[0]
        assertEquals(JsonPrimitive(10), PluginSettings.normalize(int, JsonPrimitive(99)))
        assertEquals(JsonPrimitive(1), PluginSettings.normalize(int, JsonPrimitive(0)))
        assertNull("不是整数就不是这个键的值", PluginSettings.normalize(int, JsonPrimitive("abc")))
        val enum = list[1]
        assertEquals(JsonPrimitive("b"), PluginSettings.normalize(enum, JsonPrimitive("b")))
        assertNull(PluginSettings.normalize(enum, JsonPrimitive("z")))
        val bool = list[2]
        assertEquals(JsonPrimitive(true), PluginSettings.normalize(bool, JsonPrimitive(true)))
        assertNull(PluginSettings.normalize(bool, JsonPrimitive("yes")))
    }

    /** 空声明 / 没写 ui 都是"没有设置", 不是错 */
    @Test
    fun `没有设置也可以`() {
        assertTrue(PluginSettings.parse(null).isEmpty())
        assertTrue(PluginSettings.parse(decl("""{"settings":[]}""")).isEmpty())
    }
}
