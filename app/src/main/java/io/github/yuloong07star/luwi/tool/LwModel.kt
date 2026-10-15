package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.util.Log
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 「视频模式使用的模型」那一份目录 (`$DSH_HOME/lw/model-catalog.json`)
 *
 * 模型目录住在宿主那一侧 (dsh 的 llm 注册表与账号), 而挑模型的那一行在应用进程的设置页里 —— 又是
 * 「文件即真相」那一条老路 (与 balance.json / ball-phase.json 同一个形状): **宿主写, 应用读**, 谁都
 * 不许推送。宿主起来刷一次, 应用点开那一段时的「现在刷一次」请求记号 (`model-catalog-request.json`,
 * `{"at":ms}`) 由宿主本来就有的那条 3 s 轮询看一眼
 *
 * ```json
 * {"v":1,"at":1760000000000,"ok":true,
 *  "current":{"provider":"deepseek-official","model":"deepseek-chat"},
 *  "items":[{"provider":"deepseek-official","model":"deepseek-chat","label":"DeepSeek · deepseek-chat"}]}
 * ```
 *
 * `ok:false` (或文件不在) 时 items 为空 —— 设置页那一行**退化为两个文本框**, 不假装有列表;
 * 这里也绝不自己编一个目录出来
 *
 * 桥上那一档 (`model op=catalog`) 与设置页读的是同一份文件: host 侧的量具 (`lw.ps1 -Method model`)
 * 从外面量到的与应用里看见的是同一个事实
 */
internal object LwModel {

    private const val TAG = "LwModel"

    /** 与宿主那侧同一个约定 (谁都不许单边改) */
    private const val DIRECTORY = "lw"
    private const val FILE_NAME = "model-catalog.json"
    private const val REQUEST_NAME = "model-catalog-request.json"

    /** 一次读的答案 (纯数据, [parse] 只吃一个 String, 判据脚本钉键名) */
    internal data class Catalog(
        val at: Long,
        val ok: Boolean,
        /** 「当前模型」(宿主认的部署默认): 取不到就是空串, 界面不说谎 */
        val currentProvider: String,
        val currentModel: String,
        val items: List<Item>,
    ) {
        internal data class Item(val provider: String, val model: String, val label: String)
    }

    /** 目录那份文件在哪儿 (读与请求记号共用父目录) */
    fun file(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY), FILE_NAME)

    private fun requestFile(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY), REQUEST_NAME)

    /** 现在那份目录 (文件不存在或读不出来就是 null —— 那是"宿主还没写过", 不是"没有模型") */
    fun snapshot(context: Context): Catalog? {
        val target = file(context)
        if (!target.isFile) return null
        return runCatching { parse(target.readText()) }.getOrNull()
    }

    /** 解析那一份 JSON: 纯函数 (只吃一个 String) */
    fun parse(raw: String): Catalog? {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val at = (root["at"] as? JsonPrimitive)?.longOrNull ?: return null
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
        val current = root["current"] as? JsonObject
        fun field(section: JsonObject?, key: String): String =
            (section?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val items = (root["items"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { entry ->
                (entry as? JsonObject)?.let { item ->
                    val provider = field(item, "provider")
                    val model = field(item, "model")
                    if (provider.isEmpty() || model.isEmpty()) {
                        null
                    } else {
                        Catalog.Item(provider, model, field(item, "label").ifEmpty { "$provider / $model" })
                    }
                }
            }
            .orEmpty()
        return Catalog(
            at = at,
            ok = ok,
            currentProvider = field(current, "provider"),
            currentModel = field(current, "model"),
            items = items,
        )
    }

    /**
     * 写一个"现在刷一次"的请求记号 (设置页打开那一段 / 下拉重取时用)
     *
     * 同一个记号写多少遍都无所谓 (宿主只看它的新旧), 所以这里不做去重 —— 一句 `writeText` 的事
     */
    fun request(context: Context) {
        runCatching {
            requestFile(context).parentFile?.mkdirs()
            requestFile(context).writeText("{\"at\":${System.currentTimeMillis()}}\n")
        }.onFailure { Log.w(TAG, "could not write the catalog request marker", it) }
    }

    /** 这个记号是不是应用刚写的 (宿主那侧判"要不要刷"用; 两侧同一个数, 谁都不许单边改) */
    fun requestFresh(context: Context, withinMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val target = requestFile(context)
        if (!target.isFile) return false
        val at = runCatching {
            (Json.parseToJsonElement(target.readText()).jsonObject["at"] as? JsonPrimitive)?.longOrNull
        }.getOrNull() ?: return false
        return now - at in 0..withinMs
    }

    /** 桥那一档: `model op=catalog` 把文件里的目录原样交出去 (缺什么补什么, 不编造) */
    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "catalog" -> {
            val snapshot = snapshot(context)
            buildJsonObject {
                put("at", snapshot?.at ?: 0L)
                put("ok", snapshot?.ok == true)
                putJsonObject("current") {
                    put("provider", snapshot?.currentProvider.orEmpty())
                    put("model", snapshot?.currentModel.orEmpty())
                }
                putJsonArray("items") {
                    snapshot?.items?.forEach { item ->
                        add(
                            buildJsonObject {
                                put("provider", item.provider)
                                put("model", item.model)
                                put("label", item.label)
                            },
                        )
                    }
                }
                put("text", if (snapshot?.ok == true) {
                    "the model catalog has ${snapshot.items.size} model(s); the current model is " +
                        "${snapshot.currentProvider}/${snapshot.currentModel}"
                } else {
                    "the model catalog is not available yet (the host has not written it): " +
                        "the video-mode setting degrades to typing provider and model by hand"
                })
            }
        }

        else -> throw IllegalArgumentException("op has to be catalog, not \"$op\"")
    }
}
