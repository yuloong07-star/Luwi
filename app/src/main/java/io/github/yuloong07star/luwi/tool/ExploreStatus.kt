package io.github.yuloong07star.luwi.tool

import android.content.Context
import io.github.yuloong07star.luwi.host.DshHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 探索那一档的读数 (2.7.9 T5): 宿主写进 `$DSH_HOME/lw/explore.json` 的那一段 `status`
 *
 * **为什么要有这一份**: `preset` / `presetWhy` / `lastError` / `powerSaveUnknown` 原来只在宿主
 * 内存里, 也就是只有模型调 `lw_explore op=status` 时看得见 (施工单 F20); 设置页那一段只显示"下一次"
 * 与"上一次", 主人看不出这一场为什么没开成 / 用了哪个预设。宿主现在每次落盘都把这四项写进同一个
 * 文件, 应用这一侧只读它 —— 与 `explore-schedule.json` 一样, 不需要经过模型
 *
 * 另外把"最近一份摘要"也放在这里: 摘要在 `lw/explore/<yyyyMMdd-HHmm>.md`, 文件名天然可排序, 所以
 * 不额外落一份索引 (轻量档, 见施工单 §2.6 T4)
 */
internal object ExploreStatus {

    const val FILE = "explore.json"
    const val SUMMARY_DIR = "explore"

    /** 宿主那一段读数的四项; 读不到整段就是 null */
    data class Snapshot(
        val preset: String?,
        val presetWhy: String?,
        val lastError: String?,
        val powerSaveUnknown: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun file(context: Context): File =
        File(File(context.filesDir, DshHost.HOME_DIR), "lw/$FILE")

    fun summaryDirectory(context: Context): File =
        File(File(context.filesDir, DshHost.HOME_DIR), "lw/$SUMMARY_DIR")

    /** 现在那一份读数; 文件不在 / 还没有 `status` 段就是 null */
    fun read(context: Context): Snapshot? {
        val raw = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return snapshotOf(root)
    }

    /**
     * 一份 `explore.json` -> 那四项 (纯函数, 单测钉)
     *
     * 空串按"没有"算: 宿主那侧 `presetWhy` 在一切正常时是空串的可能很小, 但它把"没有原因要说"写成
     * 空串时, 设置页不该多出一行"为什么是它: "
     */
    fun snapshotOf(root: JsonObject): Snapshot? {
        val status = root["status"] as? JsonObject ?: return null
        return Snapshot(
            preset = status["preset"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            presetWhy = status["presetWhy"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            lastError = status["lastError"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            powerSaveUnknown = status["powerSaveUnknown"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /**
     * 最近一份摘要 (文件名是 `yyyyMMdd-HHmm.md`, 直接按名字取最大那个)
     *
     * 读不到目录就给 null: 那正是"第一场还没收尾", 设置页那一行如实这么说
     */
    fun latestSummary(context: Context): File? = runCatching {
        summaryDirectory(context).listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.maxByOrNull { it.name }
    }.getOrNull()
}
