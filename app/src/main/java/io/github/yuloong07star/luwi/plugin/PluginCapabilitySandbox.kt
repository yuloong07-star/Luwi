package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.os.Bundle
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * G3 能力沙盒那两条能力真正干活的地方 (2026-10-12)
 *
 * 边界判定全在 [PluginFilesReadBoundary] 与 [PluginNetBoundary] 里 (纯逻辑, JVM 测试跑的就是它),
 * 这里只做"读 / 发, 然后把结果装进 Bundle"这一段。两条的边界就是声明里那一截 —— 调用方传进来的
 * `capability` 串自己在 [LwPluginContextBinder] 那一道就核过"声明过且被授权", 所以这一层不再猜
 *
 * 几个刻意写死的口径:
 *
 * - `files.read`: 单次最多 256 KiB, `list` / `read` 两个 op, 目标按 canonical 路径判 (软链与 `..`
 *   跳出去都拒), Luwi 自己的私有目录整棵不在范围里
 * - `net.http`: 只走 https, 域名逐字匹配, 重定向最多 3 跳且**每一跳重查**, 响应体最多 1 MiB,
 *   `timeoutMs` 默认 15 s 上限 60 s; 非 2xx 不算"能力失败" —— 状态码原样装进 data, 由插件自己判
 */
internal object PluginCapabilitySandbox {

    /** 请求头里不许自己设的那几个 (连接怎么建是 OkHttp 的事, 不是插件的事) */
    private val blockedHeaders = setOf("host", "content-length", "connection", "transfer-encoding", "upgrade")

    private val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

    /**
     * 自己看 302 (每一跳都要重查域名), 所以两个 follow 都关掉; 超时按每次调用在 `newBuilder` 里给
     *
     * 不用 `HttpURLConnection` 的理由与下载那条链同一个 (2026-10-11 记的): 那一套不做 Happy Eyeballs,
     * 双栈设备上解析出 IPv6 时会直接去连黑洞
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // ── files.read:<路径前缀> ─────────────────────────────────────────────

    fun filesRead(context: Context, declared: String, args: Bundle): Bundle {
        val prefix = declared.removePrefix("files.read:")
        val op = args.getString("op") ?: "list"
        val target = try {
            PluginFilesReadBoundary.resolve(prefix, args.getString("path").orEmpty(), listOf(context.dataDir))
        } catch (error: IllegalArgumentException) {
            return LwPluginResults.fail(error.message ?: "\"$declared\" 这一趟过不了前缀那道闸")
        }
        return when (op) {
            "list" -> {
                if (!target.isDirectory) return LwPluginResults.fail("${target.path} 不是一个目录")
                val rows = target.listFiles()?.sortedBy { it.name }?.map {
                    if (it.isDirectory) "${it.name}/" else "${it.name} (${it.length()} 字节)"
                }.orEmpty()
                LwPluginResults.ok(rows.joinToString("\n").ifBlank { "(空)" })
            }
            "read" -> {
                if (!target.isFile) return LwPluginResults.fail("${target.path} 不是一个文件")
                if (target.length() > PluginFilesReadBoundary.MAX_BYTES) {
                    return LwPluginResults.fail(
                        "${target.path} 有 ${target.length()} 字节, 超过一次能读的 ${PluginFilesReadBoundary.MAX_BYTES}",
                    )
                }
                LwPluginResults.ok(target.readText())
            }
            else -> LwPluginResults.fail("files.read 的 op 只能是 list 或 read, 不是 \"$op\"")
        }
    }

    // ── net.http:<域名> ─────────────────────────────────────────────────

    fun netHttp(declared: String, args: Bundle): Bundle {
        val domain = PluginNetBoundary.domainOf(declared)
        val raw = args.getString("url").orEmpty().trim()
        if (raw.isEmpty()) return LwPluginResults.fail("net.http 得给一个 url")
        val first = raw.toHttpUrlOrNull()
            ?: return LwPluginResults.fail("\"$raw\" 不像一个 http(s) 地址")
        PluginNetBoundary.checkHop(domain, first)?.let { return LwPluginResults.fail(it) }

        val method = (args.getString("method") ?: "GET").trim().uppercase()
        if (method !in methods) {
            return LwPluginResults.fail("method 只能是 ${methods.joinToString(" / ")}, 写的是 \"$method\"")
        }
        val body = args.getString("body").orEmpty()
        if (body.isNotEmpty() && (method == "GET" || method == "HEAD")) {
            return LwPluginResults.fail("$method 不带 body")
        }
        if (body.length > PluginNetBoundary.MAX_REQUEST) {
            return LwPluginResults.fail(
                "请求体有 ${body.length} 个字符, 超过一次能发的 ${PluginNetBoundary.MAX_REQUEST}",
            )
        }
        val timeout = args.getInt("timeoutMs", 0).takeIf { it > 0 } ?: PluginNetBoundary.DEFAULT_TIMEOUT_MS
        if (timeout > PluginNetBoundary.MAX_TIMEOUT_MS) {
            return LwPluginResults.fail("timeoutMs 最多 ${PluginNetBoundary.MAX_TIMEOUT_MS}, 写的是 $timeout")
        }
        val headers = stringMap(args.getBundle("headers"))
            ?: return LwPluginResults.fail("headers 要是一个 {名字: 值} 的小字典")
        headers.keys.firstOrNull { it.lowercase() in blockedHeaders }?.let {
            return LwPluginResults.fail("headers 里 \"$it\" 不许自己设 (连接怎么建不是插件的事)")
        }
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value

        val reply = try {
            val walked = follow(domain, first, method, body, contentType, headers, timeout)
            walked.second?.let { return LwPluginResults.fail(it) }
            walked.first ?: return LwPluginResults.fail("这一趟没有走到头")
        } catch (error: IOException) {
            return LwPluginResults.fail("请求失败了: ${error.message ?: error.javaClass.simpleName}")
        }

        reply.use { reply ->
            val data = Bundle().apply {
                putInt("status", reply.code)
                putString("contentType", reply.body.contentType()?.toString().orEmpty())
                putString("headers", reply.headers.joinToString("\n") { "${it.first}: ${it.second}" }.take(8192))
                putString("url", reply.request.url.toString())
            }
            if (method == "HEAD") return LwPluginResults.ok("(HEAD: 没有正文)", data = data)
            val bytes = readAtMost(reply.body.byteStream(), PluginNetBoundary.MAX_BODY + 1)
                ?: return LwPluginResults.fail("响应体超过 ${PluginNetBoundary.MAX_BODY} 字节, 没有全收")
            val charset = reply.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            return LwPluginResults.ok(String(bytes, charset), data = data)
        }
    }

    /**
     * 走完重定向: 回 (最终那一次回应, null), 或者 (null, 一句拒绝的话)
     *
     * 每一跳都过 [PluginNetBoundary.checkHop] —— 跳到名单外的域名、从 https 跳到 http、地址里被塞了
     * 用户名密码, 三种都在这里被拦下, 而不是跟着跳过去再说
     */
    private fun follow(
        domain: String,
        start: okhttp3.HttpUrl,
        method: String,
        body: String,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): Pair<Response?, String?> {
        var current = start
        var hops = 0
        while (true) {
            val builder = Request.Builder().url(current)
            // POST / PUT / PATCH 就算没有内容也得给一个**空 body**: OkHttp 对这三个方法
            // 收 null body 会当场抛"must have a request body"
            val carriesBody = method == "POST" || method == "PUT" || method == "PATCH"
            if (body.isNotEmpty() || carriesBody) {
                builder.method(method, body.toRequestBody(contentType?.toMediaTypeOrNull()))
            } else {
                builder.method(method, null)
            }
            headers.forEach { (name, value) -> builder.header(name, value) }
            val reply = client.newBuilder()
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(builder.build())
                .execute()
            if (reply.code !in 300..399) return reply to null
            if (hops >= PluginNetBoundary.MAX_HOPS) {
                reply.close()
                return null to "跳了 ${PluginNetBoundary.MAX_HOPS} 次还没到头, 不跟了"
            }
            val location = reply.header("Location").orEmpty()
            val next = if (location.isBlank()) null else current.resolve(location)
            val code = reply.code
            reply.close()
            if (next == null) return null to "回了 $code 却没说要跳到哪 (Location 读不出来)"
            PluginNetBoundary.checkHop(domain, next)?.let { return null to "重定向被拦下: $it" }
            current = next
            hops += 1
        }
    }

    /** Bundle 里那一层 {名字: 值}; 值不是字符串的如实回 null (不猜) */
    @Suppress("DEPRECATION")
    private fun stringMap(bundle: Bundle?): Map<String, String>? {
        if (bundle == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        bundle.keySet().forEach { key ->
            val value = bundle.get(key)
            if (value !is String) return null
            result[key] = value
        }
        return result
    }

    /** 收 [limit] 个字节; 真收了 [limit] 个就是"还不止这么多" (超没超上限由调用方判) */
    private fun readAtMost(stream: InputStream, limit: Int): ByteArray? {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (collected.size() < limit) {
            val want = minOf(buffer.size, limit - collected.size())
            val read = stream.read(buffer, 0, want)
            if (read < 0) break
            collected.write(buffer, 0, read)
        }
        return if (collected.size() >= limit) null else collected.toByteArray()
    }
}
