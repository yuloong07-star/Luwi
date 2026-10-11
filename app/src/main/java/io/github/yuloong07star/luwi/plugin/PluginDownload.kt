package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 从链接把一份插件包取下来 (协议第 13 节那条"从 URL 安装")
 *
 * 这一份只做"把字节拿到手", 一个字的校验都不省给后面: 拿到的那份交给 [PluginInstaller], 走的是与
 * 本地包**完全同一条链** (解包 → 验签名与逐文件哈希 → 查 protocol / minLw / 前缀 / 未知能力 → 才落地)。
 * 所以这里的每一句错话都只关于"这一趟下载", 不关于"这份包能不能装"
 *
 * 四条写在这里的规矩:
 *
 * - **只许 https**: 每一次跳转都重查一遍 URL —— 从 https 跳到 http 就地中止, 不跟着去
 * - **不信任任何头**: `Content-Length` 只当个早拒的提示, 真正的上限靠边读边数 (声明与实际不符的头
 *   到处都是), 超了就把半份文件删掉
 * - **下到 cache**: 它是 app 私有目录里的一份临时文件, 装完或失败都当场删 (设置页那条路在 `finally`
 *   里删, 桥那条路也一样)
 * - **下完先看它像不像一份包**: 前两个字节不是 `PK` 就当场说"这不是一份 .lwp", 而不是让安装器去报一句
 *   看不懂的错 (GitHub 那种 API 链接回的是 JSON, 这是最容易撞上的一种)
 * - **走 OkHttp, 不走 `HttpURLConnection`** (2026-10-11 换的): 那一套**不做 Happy Eyeballs** ——
 *   双栈设备上这台 CDN 解析出 IPv6 时会直接去连 IPv6, 而移动网络里那条路常常是黑洞, 于是浏览器
 *   下得动、应用只拿到 "Connection reset"。OkHttp 既做 IPv4/IPv6 竞速, 连不上还会换下一个地址
 */
internal object PluginDownload {

    private const val TAG = "LwPluginDownload"

    /** 协议第 3 节那份大小上限, 与 [PluginInstaller] 那一条对齐 */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** 跳几次就够: 一个短链加一次 302 到头了, 再多是环 */
    private const val MAX_HOPS = 5

    /** 一条地址最多试几次: 移动网络上一个地址连不上很常见, 换一个就成了 */
    private const val ATTEMPTS = 3

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000

    private const val USER_AGENT = "Luwi-plugin-install"

    /**
     * 自己看 302 (跟着走就没机会查"下一跳是不是还是 https"), 所以两个 follow 都关掉
     *
     * `retryOnConnectionFailure` 保持默认那个 true: 换地址这件事它自己还会再帮一次忙
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(0L, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * zip 的头两个字节, 也就是 `P` 与 `K` (**两个都要写对**: 第一版这里只写了 0x50 当"魔数",
     * 于是第二个字节那个 0x4B 永远对不上, 一份好端端的包也被说成"不像 zip" —— 2026-10-10 实测撞的)
     */
    private const val ZIP_FIRST = 0x50
    private const val ZIP_SECOND = 0x4B

    /**
     * 下到 `cacheDir/plugin-download/` 里的一份临时文件并返回它
     *
     * 抛出来的每一句都点名叫人不明白的那一处: 不是 https / 跳太多 / 回了别的状态码 / 太大 / 不像 zip。
     * **调用方负责删掉这一份** (它是临时文件, 装完就不需要了)
     */
    fun fetch(context: Context, url: String): File {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("链接是空的")
        val directory = File(context.cacheDir, "plugin-download").apply { mkdirs() }
        val target = File(directory, "package-${System.currentTimeMillis()}.lwp")
        try {
            val landed = downloadTo(trimmed, target)
            if (target.length() == 0L) throw IllegalArgumentException("这个链接下下来是空的")
            checkLooksLikePackage(target, landed)
            Log.i(TAG, "fetched ${target.length()} bytes from $trimmed")
            return target
        } catch (problem: Throwable) {
            target.delete()
            throw problem
        }
    }

    /** 落到哪一条地址上 (跳完最后那一条), 只给"不是一份 .lwp"那句错话用 */
    private fun downloadTo(url: String, target: File): String {
        var current = url
        var hops = 0
        while (true) {
            requireHttps(current)
            val response = connect(current)
            val code = response.code
            if (code in 300..399) {
                val where = response.header("Location")
                // 相对地址要按当前这一跳解出来, 下一轮开头那句 requireHttps 会把 http 拦下
                val next = response.request.url.resolve(where.orEmpty())?.toString()
                response.close()
                if (where.isNullOrBlank()) throw IllegalArgumentException("这个链接回了 $code, 却没说要跳到哪")
                hops += 1
                if (hops > MAX_HOPS) throw IllegalArgumentException("这个链接跳了 $MAX_HOPS 次还没到头, 不跟了")
                current = next ?: throw IllegalArgumentException("这个链接回了 $code, 跳的目标读不出来: $where")
                continue
            }
            if (code != 200) {
                response.close()
                throw IllegalArgumentException("这个链接回了 $code (要 200 才能拿到字节)")
            }
            try {
                val declared = response.body.contentLength()
                if (declared > MAX_BYTES) {
                    throw IllegalArgumentException("这一份 ${declared / 1024 / 1024} MB, 超过 ${MAX_BYTES / 1024 / 1024} MB 的上限")
                }
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_BYTES) {
                                throw IllegalArgumentException("这一份超过 ${MAX_BYTES / 1024 / 1024} MB 的上限")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                response.close()
            }
            return current
        }
    }

    /** 只许 https: 这一句在每一跳都跑一次, 所以"跳到 http"这一条也是当场中止 */
    private fun requireHttps(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("只收 https 的链接 (协议第 13 节那一条), 这一条是 ${url.take(80)}")
        }
    }

    /** 试几次再放弃: 连不上多半是"这一个地址不通", 换一个就成了 */
    private fun connect(url: String): Response {
        var last: IOException? = null
        repeat(ATTEMPTS) {
            try {
                return client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "*/*")
                        .header("User-Agent", USER_AGENT)
                        .build(),
                ).execute()
            } catch (problem: IOException) {
                last = problem
            }
        }
        val why = last
        throw IllegalArgumentException("连不上这个链接: ${why?.message ?: why?.javaClass?.simpleName}")
    }

    /**
     * 前两个字节得是 `PK`: 不是的话下下来的多半是一页 JSON 或者一页网页
     *
     * 那句错话里带上**实际收到的开头** (前 60 个可打印字符) 与最后落到的那条地址: 少了这两样, 撞上
     * "这个链接下的不是一份 .lwp"时人只能猜是链接写错了还是对面回了一页 HTML
     */
    private fun checkLooksLikePackage(file: File, landed: String) {
        val head = ByteArray(2)
        val read = file.inputStream().use { it.read(head) }
        if (read < 2 ||
            (head[0].toInt() and 0xFF) != ZIP_FIRST ||
            (head[1].toInt() and 0xFF) != ZIP_SECOND
        ) {
            val peek = ByteArray(60)
            val got = file.inputStream().use { it.read(peek) }.coerceAtLeast(0)
            val text = (0 until got).map { index ->
                val value = peek[index].toInt() and 0xFF
                if (value in 32..126) value.toChar() else '.'
            }.joinToString("")
            throw IllegalArgumentException(
                "这个链接下的不是一份 .lwp (包是一个 zip)。它最后落在 $landed, " +
                    "开头是这段: $text",
            )
        }
    }
}
