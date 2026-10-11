package io.github.yuloong07star.luwi.plugin.widget

import android.content.Context
import io.github.yuloong07star.luwi.plugin.PluginStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 插件自己推给桌面那几格的内容 (方案 3.3 那条 `ui.widget`)
 *
 * `art` 域是静态的: 内容全写在包里的 `widget.json`。带代码的域 (`inline` / `companion`) 可以
 * **推** —— 一次 `context.call("ui.widget", {…})` 把标题 / 正文 / 图 / 按钮换掉, 于是番茄钟那种
 * "每秒都要变"的组件不需要伴侣 APK
 *
 * 落在插件自己那一块 (`<id>/.data/widget-push.json`): 它推一次就在那儿, 重启、重启桌面都还在,
 * 直到它推下一次或者插件被卸掉
 */
internal object WidgetPush {

    /** 一次推送: 没给的字段就是"这次不动它" */
    @Serializable
    data class Content(
        val label: String = "",
        val title: String = "",
        val text: String = "",
        val image: String = "",
        val buttons: List<Button> = emptyList(),
        val at: Long = 0,
    ) {
        @Serializable
        data class Button(val label: String = "", val action: String = "", val tool: String = "")
    }

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(context: Context, id: String): File =
        File(PluginStore.dataDir(context, id), "widget-push.json")

    fun read(context: Context, id: String): Content? {
        val file = file(context, id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<Content>(file.readText()) }.getOrNull()
    }

    fun write(context: Context, id: String, content: Content) {
        file(context, id).writeText(json.encodeToString(content.copy(at = System.currentTimeMillis())) + "\n")
    }

    fun clear(context: Context, id: String) {
        file(context, id).delete()
    }
}
