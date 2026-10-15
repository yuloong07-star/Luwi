package io.github.yuloong07star.luwi.sync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * `$DSH_HOME/lw/mode-model.json` 的读写 (设置页「模式与模型」那一段, 施工单 3.0.2 需求 2)
 *
 * 视频模式用哪一个模型由这一份文件说了算, 宿主插件在 `lw_mode` 与 `turn/start` 那两处读它:
 *
 * ```json
 * {"v":1,"video":{"provider":"","model":""}}
 * ```
 *
 * `video` 段里两个空串 = **跟随当前模型** (缺省, 界面上叫「跟随当前模型」); 填了就是视频模式那一次
 * 请求用挑的这一对, 退回手机模式再变回来。键名与插件那一侧由 `tools/check-mode-model.mjs` 钉住
 *
 * 三条纪律照 [SyncSetting] 那一份:
 *   · **保留未知键**: 整棵树照抄, 只动 `video` 段里自己那两个键
 *   · **写盘前校验**: 越界的值在这里就被拒 (返回一句人话), 不让宿主读到一句读不懂的配置
 *   · **纯文件那一半只吃 [File]** (单测在临时目录上量, 见 [ModeModelTest])
 */
internal object ModeModel {

    /** 文件名 (与宿主插件、施工单里的是同一个) */
    const val FILE = "mode-model.json"

    // --- 文件里的键 ------------------------------------------------------------

    const val VIDEO_KEY = "video"
    const val PROVIDER_KEY = "provider"
    const val MODEL_KEY = "model"

    // 自建端点那一段 (2026-10-16 主人: "视频模式选择的 llm 的 url, api 等放到 luwi 的视频模式设置里")

    const val ENDPOINT_KEY = "endpoint"
    const val URL_KEY = "url"
    const val SECRET_KEY = "apiKey"
    const val WIRE_KEY = "api"

    /** 自建端点那条路由的名字 (宿主那一侧把它挂成 pi-ai 的一条路由, 两侧同一个字面量) */
    const val ROUTE = "luwi-video"

    /** 自建端点默认走哪一种线上协议 (现在只有 OpenAI 兼容这一档; 以后要加就在这儿加) */
    const val DEFAULT_WIRE = "openai-completions"

    /** 取值边界 (与插件那一侧保持一致) */
    const val MAX_FIELD_LENGTH = 200

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val FIELD_RE = Regex("^[A-Za-z0-9._:@/+-]+$")

    /** 设置页那一段要画的全部取值 */
    data class Snapshot(
        val videoProvider: String = "",
        val videoModel: String = "",
        val endpointUrl: String = "",
        val endpointModel: String = "",
        /** 明文只在文件里 (0600); 界面只许出 [maskedKey] 的产物, 日志一个字都不打 */
        val endpointSecret: String = "",
        val endpointWire: String = DEFAULT_WIRE,
        val fileExists: Boolean = false,
    ) {
        /**
         * 跟随当前模型 (缺省那一个): **两个都空, 或者只填了一半** —— 宿主那一侧只认"完整的一对",
         * 不完整的配置一律当跟随 (施工单第 5 节"配置为空 → null"那条), 两侧同一个口径
         */
        val followsCurrent: Boolean get() = !configured && !endpointSet

        /** 配置齐了没有 (两个键都非空才算) */
        val configured: Boolean get() = videoProvider.isNotBlank() && videoModel.isNotBlank()

        /** 自建端点配好了没有 (接口地址与模型名**都**非空才算) */
        val endpointSet: Boolean get() = endpointUrl.isNotBlank() && endpointModel.isNotBlank()

        /** 这一档现在真的会走的那条路由 (null = 不走自建端点) */
        val route: String? get() = if (endpointSet) ROUTE else null

        /** 自建端点要不要 key (空 = 那个端点不要 key, 宿主那一侧就不写 apiKeyEnv) */
        val endpointKeySet: Boolean get() = endpointSecret.isNotBlank()

        /** 界面上的那一行摘要 (设置页现在自己拼那三样; 这一份留给读数与单测): 走这个模型时出地址, 模型名与打码的 key */
        val endpointSummary: String get() = when {
            !endpointSet -> "没有添加模型"
            else -> "$endpointUrl · $endpointModel" + if (endpointKeySet) " · key ${maskedKey(endpointSecret)}" else " · 不要 key"
        }
    }

    /** 界面的重画记号 (存完 +1, 那一段 remember 它) */
    var revision: Int by mutableIntStateOf(0)
        private set

    /** 上一次动作之后那一句话 (失败时说清为什么) */
    var message: String? by mutableStateOf(null)
        private set

    /** 配置文件 (父目录不存在时由 `settingsDirectory` 建出来) */
    fun file(context: Context): File = File(DshHost.settingsDirectory(context), FILE)

    /** 现在的取值 */
    fun snapshot(context: Context): Snapshot = snapshotOf(file(context))

    /**
     * 写视频模式那一对 (两个都空 = 跟随当前模型)。
     * @return null 成功; 否则是一句能给人看的原因。
     */
    fun setVideo(context: Context, provider: String, model: String): String? =
        finish(setVideoIn(file(context), provider, model))

    /**
     * 写自建端点 (接口地址 / API Key / 模型名)。地址与模型名要么都填, 要么都不填。
     * @return null 成功; 否则是一句能给人看的原因。
     */
    fun setEndpoint(context: Context, url: String, secret: String, model: String): String? =
        finish(setEndpointIn(file(context), url, secret, model))

    /** 关掉自建端点 (回到目录/跟随那一档; **API Key 留着**, 下次填回来不用重打) */
    fun clearEndpoint(context: Context): String? = finish(clearEndpointIn(file(context)))

    /** key 的显示形态 (界面只许出现它) */
    fun maskedKey(value: String): String = when {
        value.isBlank() -> "<unset>"
        value.length < 8 -> "••••"
        else -> "••••" + value.takeLast(4)
    }

    /** 文件里那棵树的原文 (设置页以后要看原文时用; 这一份没有机密) */
    fun prettyOf(file: File): String = when {
        !file.isFile -> "还没有这一份配置 (${file.absolutePath})"
        else -> runCatching { file.readText() }.getOrElse { "读不出来: ${it.message}" }
    }

    // --- 纯文件那一半 (单测直接吃它) -------------------------------------------

    /** 读一份配置的取值 (文件不在或读不懂时全按缺省, 与宿主"坏文件 = 跟随当前"同一个口径) */
    fun snapshotOf(file: File): Snapshot = runCatching {
        val root = readOf(file)
        val video = root[VIDEO_KEY] as? JsonObject
        val endpoint = root[ENDPOINT_KEY] as? JsonObject
        Snapshot(
            videoProvider = text(video, PROVIDER_KEY),
            videoModel = text(video, MODEL_KEY),
            endpointUrl = text(endpoint, URL_KEY),
            endpointModel = text(endpoint, MODEL_KEY),
            endpointSecret = text(endpoint, SECRET_KEY),
            endpointWire = text(endpoint, WIRE_KEY).ifEmpty { DEFAULT_WIRE },
            fileExists = file.isFile,
        )
    }.getOrElse { Snapshot() }

    /**
     * 写视频模式那一对。两个都空 = 跟随当前模型 (缺省), 照施工单里那份形状把两个键都写上 (空串)。
     * @return null 成功; 否则是原因。
     */
    fun setVideoIn(file: File, rawProvider: String, rawModel: String): String? {
        val provider = rawProvider.trim()
        val model = rawModel.trim()
        val problem = fieldProblem(provider, "提供方") ?: fieldProblem(model, "模型")
        if (problem != null) return problem
        val follow = provider.isEmpty() && model.isEmpty()
        if (!follow && (provider.isEmpty() || model.isEmpty())) {
            return "两个都要填, 或者都留空 (都空 = 跟随当前模型)"
        }
        return runCatching {
            val root = readOf(file).toMutableMap()
            val video = (root[VIDEO_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            video[PROVIDER_KEY] = JsonPrimitive(provider)
            video[MODEL_KEY] = JsonPrimitive(model)
            root[VIDEO_KEY] = JsonObject(video)
            if (!root.containsKey("v")) root["v"] = JsonPrimitive(1)
            write(file, JsonObject(root))
            null
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /**
     * 写自建端点那一段
     *
     * 两条纪律: **地址与模型名要么都填要么都不填** (只有地址没有模型名那条路由起不来 —— pi-ai
     * 对手写路由要求非空 `models`), 以及 **key 只在这一份文件里** (0600, 界面只出打码)。
     * 清掉地址请走 [clearEndpointIn] —— 那条路会把 key 留着。
     * @return null 成功; 否则是原因。
     */
    fun setEndpointIn(file: File, rawUrl: String, rawSecret: String, rawModel: String): String? {
        val url = rawUrl.trim()
        val secret = rawSecret.trim()
        val model = rawModel.trim()
        val problem = urlProblem(url) ?: keyProblem(secret) ?: fieldProblem(model, "模型名")
        if (problem != null) return problem
        if (url.isEmpty() != model.isEmpty()) {
            return "接口地址与模型名要么都填, 要么都留空 (只填一个那条路由起不来)"
        }
        return runCatching {
            val root = readOf(file).toMutableMap()
            val endpoint = (root[ENDPOINT_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            endpoint[URL_KEY] = JsonPrimitive(url)
            endpoint[MODEL_KEY] = JsonPrimitive(model)
            endpoint[WIRE_KEY] = JsonPrimitive(text(endpoint, WIRE_KEY).ifEmpty { DEFAULT_WIRE })
            if (secret.isEmpty()) endpoint.remove(SECRET_KEY) else endpoint[SECRET_KEY] = JsonPrimitive(secret)
            root[ENDPOINT_KEY] = JsonObject(endpoint)
            if (!root.containsKey("v")) root["v"] = JsonPrimitive(1)
            write(file, JsonObject(root))
            null
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /**
     * 关掉自建端点: 地址与模型名清空, **API Key 留着** (主人只是临时关掉它, 下次填回来不用重打)
     * @return null 成功; 否则是原因。
     */
    fun clearEndpointIn(file: File): String? = runCatching {
        val root = readOf(file).toMutableMap()
        val endpoint = (root[ENDPOINT_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        endpoint[URL_KEY] = JsonPrimitive("")
        endpoint[MODEL_KEY] = JsonPrimitive("")
        root[ENDPOINT_KEY] = JsonObject(endpoint)
        write(file, JsonObject(root))
        null
    }.getOrElse { "写入失败: ${it.message}" }

    // --- 读写 -----------------------------------------------------------------
    private fun readOf(file: File): Map<String, JsonElement> {
        if (!file.isFile) return emptyMap()
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject.toMap() }.getOrElse { emptyMap() }
    }

    private fun write(file: File, root: JsonObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    /** 一个段里某个键的字符串 (JsonObject 与正在改的那份 MutableMap 都吃) */
    private fun text(section: Map<String, JsonElement>?, key: String): String =
        (section?.get(key) as? JsonPrimitive)?.jsonPrimitive?.content ?: ""

    /** 一个字段的校验: 空 = 合法 (跟随当前那一路), 非空只收模型 id 里会出现的那几个字符 */
    private fun fieldProblem(value: String, what: String): String? {
        if (value.isEmpty()) return null
        if (value.length > MAX_FIELD_LENGTH) return "${what}最多 $MAX_FIELD_LENGTH 个字"
        if (value.any { it.isWhitespace() }) return "${what}里不能有空格或换行"
        return if (FIELD_RE.matches(value)) null else "${what}只能用字母数字与 . _ : @ / + -"
    }

    /** 接口地址: 空 = 合法 (那一档就是"不走自建端点"), 非空必须是 http(s) 的地址 */
    private fun urlProblem(value: String): String? {
        if (value.isEmpty()) return null
        if (value.length > MAX_FIELD_LENGTH * 4) return "接口地址太长了"
        if (value.any { it.isWhitespace() }) return "接口地址里不能有空格或换行"
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            null
        } else {
            "接口地址要以 http:// 或 https:// 开头"
        }
    }

    /** API Key: 空 = 合法 (那个端点不要 key), 非空只收能进 HTTP 头的那几个字符 */
    private fun keyProblem(value: String): String? {
        if (value.isEmpty()) return null
        if (value.length > MAX_FIELD_LENGTH * 2) return "API Key 太长了"
        if (value.any { it.isWhitespace() }) return "API Key 里不能有空格或换行"
        return if (value.all { it.code in 0x21..0x7e }) null else "API Key 只能是可见的 ASCII 字符"
    }

    /** 把纯文件那一半的结果接到界面上 (存完重画那一段) */
    private fun finish(problem: String?): String? {
        message = problem
        if (problem == null) revision += 1
        return problem
    }
}
