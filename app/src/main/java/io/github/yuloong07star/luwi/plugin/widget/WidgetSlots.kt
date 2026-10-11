package io.github.yuloong07star.luwi.plugin.widget

import android.content.Context
import io.github.yuloong07star.luwi.plugin.PluginStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 槽与插件的对应关系 (方案 3.1): `$DSH_HOME/plugins/.widgets.json`
 *
 * ```json
 * { "anim": "io.example.whale", "image": null }
 * ```
 *
 * 一份文件只记"哪个槽挂着谁", 卡片的实际内容每次重画时去插件目录现读 —— 插件被删掉、升级、
 * 手工改过, 下一次重画就如实反映, 没有第二份缓存要对账
 */
internal object WidgetSlots {

    const val FILE = ".widgets.json"

    @Serializable
    data class Assignments(val anim: String? = null, val image: String? = null) {
        fun get(slot: String): String? = when (slot) {
            WidgetSlot.ANIM -> anim
            WidgetSlot.IMAGE -> image
            else -> null
        }

        fun with(slot: String, id: String?): Assignments = when (slot) {
            WidgetSlot.ANIM -> copy(anim = id)
            WidgetSlot.IMAGE -> copy(image = id)
            else -> this
        }
    }

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(context: Context): Assignments {
        val file = File(PluginStore.root(context), FILE)
        if (!file.isFile) return Assignments()
        return runCatching { json.decodeFromString<Assignments>(file.readText()) }.getOrDefault(Assignments())
    }

    fun holder(context: Context, slot: String): String? =
        read(context).get(slot)?.takeIf { it.isNotBlank() }

    /** 挂上 / 清掉一个槽 (id = null 就是清掉); 回一句给人看的话 */
    fun assign(context: Context, slot: String, id: String?): String {
        val current = read(context)
        if (current.get(slot) == id) return "这个槽上没有变化"
        File(PluginStore.root(context), FILE).writeText(json.encodeToString(current.with(slot, id)) + "\n")
        return if (id == null) "已经把 ${slotName(slot)} 清空" else "已经把 ${slotName(slot)} 换成了它"
    }

    /**
     * 卸载一个插件时先走这一条: 凡挂着它的槽清成空
     *
     * 少了它, 桌面上那一格会挂着一条已经删掉的插件 —— 渲染空态那一步虽然也兜得住, 但那时
     * "插件没了"与"主人故意清空"两种状态会混在一起, 分不清
     *
     * @return 动过哪几个槽 (调用方拿它去重画)
     */
    fun clear(context: Context, id: String): List<String> {
        val current = read(context)
        val hit = WidgetSlot.ALL.filter { current.get(it) == id }
        if (hit.isEmpty()) return emptyList()
        var next = current
        hit.forEach { slot -> next = next.with(slot, null) }
        File(PluginStore.root(context), FILE).writeText(json.encodeToString(next) + "\n")
        return hit
    }

    fun slotName(slot: String): String = when (slot) {
        WidgetSlot.ANIM -> "动画组件"
        WidgetSlot.IMAGE -> "图文组件"
        else -> slot
    }
}
