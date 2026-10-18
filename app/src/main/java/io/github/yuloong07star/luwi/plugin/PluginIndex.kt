package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 插件仓库的那份官方索引 (`lw-plugin-index/1`, 协议第 13 节): **读、搜、下** 的应用侧一半
 *
 * 索引住在生态仓 (`yuloong07-star/luwi-plugins`) 的根上, 由 `tools/lw-plugin-index.mjs` 从 `packages/`
 * 里那些签好名的包**现算**出来: 整包 sha256、逐文件哈希、发布者指纹与签名都验过才进索引。所以它比
 * "一张写死的下载链接表"多了一件要紧的事 —— **它给每份包钉了一个整包摘要**, 装之前对得上才装
 * (协议第 14 节第 3 条点名的洞: 索引被替换会变成"把你引到一个同名不同发布者的包")
 *
 * 四条规矩 (与其它几条联网的路同一个口径):
 *
 * - **平时只读缓存, 点了「刷新」才联网** (照 `AppSkills` 的 catalog/refresh 分工): 设置页不该因为
 *   一次网络慢就卡住; 缓存落 `$DSH_HOME/plugins/.index.json`
 * - **取 JSON 不能走 [PluginDownload.fetch]**: 那一条按"前两个字节是 PK"判包, 一份 JSON 会被它当场
 *   拒掉 —— 所以这里自己发 GET (同一个 OkHttp 客户端的形状: https only, 1 MB 上限)
 * - **地址是一条多镜像的表**: jsDelivr 的几个等价域 (2026-10-13 本机实测: `cdn` 不通而
 *   `fastly`/`gcore` 通), 逐条试, 全失败把每一条的原因拼进那一句话; 上一份内容 tag 留作兜底
 *   (索引换了内容 tag 之后, 旧的 tag 还在 —— 现在这对是 `index-8` 与 `index-7`)
 * - **装走与「从链接装入」完全同一条链**: 下字节用 [PluginDownload.fetch], 校验是 [PluginInstaller]
 *   的那十一档 —— 索引只多做两件它才有资格做的事: 装之前核整包 sha256 与大小
 */
internal object PluginIndex {

    private const val TAG = "LwPluginIndex"

    /** 索引自己那份的大小上限: 它是几 KB 的 JSON, 大过这一条多半是撞上了一页网页 */
    private const val MAX_BYTES = 1L * 1024 * 1024

    private const val CONNECT_MS = 10_000L
    private const val READ_MS = 15_000L

    private const val USER_AGENT = "Luwi-plugin-index"

    /** 内容 tag: 索引换了内容就换新 tag (jsDelivr 对旧 tag 的边缘缓存刷不掉, 见 packages/README.md) */
    private const val REF_CURRENT = "index-8"

    /** 上一份内容 tag: 兜底 (新 tag 还没推上去 / 新 tag 那条链路坏了时, 旧的一份照样能用) */
    private const val REF_PREVIOUS = "index-7"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_MS, TimeUnit.MILLISECONDS)
            .callTimeout(0L, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 一条索引记录里"这个包是谁的" (指纹那一串与 Kotlin 那侧 `PluginSignature` 同一个形状) */
    internal data class Publisher(val name: String, val fingerprint: String)

    /** 一条可装的候选 */
    internal data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val kind: String,
        val minLw: String,
        val toolPrefix: String,
        val requested: List<String>,
        val dangerous: List<String>,
        val tools: List<String>,
        val publisher: Publisher,
        val url: String,
        /**
         * 同一份包的其余镜像地址 (索引生成器写的)
         *
         * **2026-10-15 补**: 主址是 `cdn.jsdelivr.net`, 而它在有些网络里连不上 (实测 SSL 直接失败),
         * 能通的 `fastly` / `gcore` / `testingcf` 都在这儿 —— 在这之前应用侧**一个字节都没读这个字段**,
         * 于是只试主址, 主址不通就等于"这个包装不上" (主人 2026-10-15: "安装不了")
         */
        val mirrors: List<String> = emptyList(),
        val sha256: String,
        val size: Long,
        val description: String,
        val tags: List<String>,
    ) {
        /** 这份包能下载的地址: 主址优先, 再逐个镜像 (去重, 空的不算) */
        fun packageUrls(): List<String> =
            (listOf(url) + mirrors).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** 一份解析好的索引 */
    internal data class Index(val generated: String, val plugins: List<Entry>, val revoked: Int)

    /** 缓存在哪: `$DSH_HOME/plugins/.index.json` (与装好的插件同一个家, 点号开头是"它不是一份插件") */
    fun cacheFile(context: Context): File = File(PluginStore.root(context), ".index.json")

    /** 默认那一份地址表: 现行 tag 的三个镜像, 再垫一份旧 tag (逐条试, 谁通用谁) */
    fun addresses(): List<String> = buildList {
        for (host in listOf("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net")) {
            add("https://$host/gh/yuloong07-star/luwi-plugins@$REF_CURRENT/index.json")
        }
        add("https://fastly.jsdelivr.net/gh/yuloong07-star/luwi-plugins@$REF_PREVIOUS/index.json")
    }

    /** 现在缓存里那一份 (没有 / 坏了就是 null —— 那是"还没取过", 不是"没有插件") */
    fun cached(context: Context): Index? {
        val file = cacheFile(context)
        if (!file.isFile) return null
        return runCatching { parse(file.readText()) }.getOrNull()
    }

    /** 联网取一份新的: 逐条地址试 → 校验格式 → 落缓存; 回一句**给人看**的话 (成了/为什么没成) */
    fun refresh(context: Context): String {
        val reasons = mutableListOf<String>()
        for (address in addresses()) {
            try {
                val text = fetchText(address)
                val index = parse(text) ?: throw IllegalArgumentException("这不是一份 lw-plugin-index/1 的 JSON")
                val file = cacheFile(context)
                file.parentFile?.mkdirs()
                file.writeText(text)
                Log.i(TAG, "the plugin index came from $address (${index.plugins.size} entries)")
                return "索引取到了: ${index.plugins.size} 份插件 (${index.generated})"
            } catch (problem: Throwable) {
                reasons += "${address.substringAfter('@')}: ${problem.message ?: problem.javaClass.simpleName}"
            }
        }
        return "索引取不到 (用的还是缓存里那一份, 如果有): ${reasons.joinToString("; ")}"
    }

    /** 一个 GET: 只收 https, 1 MB 上限, 回 UTF-8 文本 (每一句错话都点名是哪一条地址) */
    private fun fetchText(address: String): String {
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(address)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build(),
            ).execute()
        } catch (problem: IOException) {
            throw IllegalArgumentException("连不上: ${problem.message ?: problem.javaClass.simpleName}")
        }
        response.use {
            if (!it.request.url.toString().startsWith("https://")) {
                throw IllegalArgumentException("这个地址跳到了非 https 的地方, 不跟")
            }
            if (it.code != 200) throw IllegalArgumentException("回了 ${it.code}")
            val body = it.body
            val declared = body.contentLength()
            if (declared > MAX_BYTES) throw IllegalArgumentException("这一份 $declared 字节, 超过 1 MB 的上限")
            val text = body.string()
            if (text.length > MAX_BYTES) throw IllegalArgumentException("这一份超过 1 MB 的上限")
            return text
        }
    }

    /** 解析那一份 JSON: 手写的那一个解析器 (宽容: 缺哪一样那一条就缺, 不让整份索引作废) */
    fun parse(text: String): Index? {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != "lw-plugin-index/1") return null
        val generated = (root["generated"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val revoked = (root["revoked"] as? JsonArray)?.size ?: 0
        val plugins = (root["plugins"] as? JsonArray).orEmpty().mapNotNull { one ->
            val item = one as? JsonObject ?: return@mapNotNull null
            entry(item)
        }
        return Index(generated, plugins, revoked)
    }

    private fun entry(item: JsonObject): Entry? {
        fun text(key: String): String = (item[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val id = text("id")
        val url = text("url")
        if (id.isEmpty() || url.isEmpty()) return null
        val capabilities = item["capabilities"] as? JsonObject
        val publisher = item["publisher"] as? JsonObject
        return Entry(
            id = id,
            name = text("name").ifBlank { id },
            version = text("version"),
            kind = text("kind"),
            minLw = text("minLw"),
            toolPrefix = text("toolPrefix"),
            requested = strings(capabilities?.get("requested")),
            dangerous = strings(capabilities?.get("dangerous")),
            tools = strings(item["tools"]),
            publisher = Publisher(
                name = (publisher?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                fingerprint = (publisher?.get("fingerprint") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            ),
            url = url,
            mirrors = strings(item["mirrors"]),
            sha256 = text("sha256"),
            size = (item["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
            description = text("description"),
            tags = strings(item["tags"]),
        )
    }

    private fun strings(node: Any?): List<String> =
        (node as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /**
     * 搜: id / 名字 / 描述 / 标签 / 发布者 / 工具名 / kind, 大小写不敏感的子串
     *
     * 纯函数 (只吃一份 [Index] 与一句话), 空串就是"全部按索引顺序给"
     */
    fun search(index: Index, query: String): List<Entry> {
        val wanted = query.trim().lowercase()
        if (wanted.isEmpty()) return index.plugins
        return index.plugins.filter { entry ->
            listOf(
                entry.id, entry.name, entry.description, entry.publisher.name, entry.kind,
                entry.tags.joinToString(" "), entry.tools.joinToString(" "),
            ).any { it.lowercase().contains(wanted) }
        }
    }

    /** 分段数字比大小 (1.1.10 > 1.1.4); 读不出来按 0 算 —— 那一档交给安装器自己的 minLw 闸 */
    internal fun compareVersion(left: String, right: String): Int {
        val a = left.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
        val b = right.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0L }
            val y = b.getOrElse(index) { 0L }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** 这台机器的 Luwi 版本 (读不出来是空串: 那一档 `minLw` 的闸交给安装器) */
    fun luwiVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 整包摘要: 与索引里那一个形状一样 (`sha256:<hex>`) */
    fun digestOf(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
