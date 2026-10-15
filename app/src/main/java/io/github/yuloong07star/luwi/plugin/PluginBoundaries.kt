package io.github.yuloong07star.luwi.plugin

import okhttp3.HttpUrl
import java.io.File
import java.io.IOException

/**
 * G3 能力沙盒 (2026-10-12): 两条带参数能力的边界
 *
 * `files.read:<路径前缀>` 与 `net.http:<域名>` 是**每条插件各自一条**的边界 —— 声明里那一截就是它的
 * 地盘, 出了地盘的调用一律拒, 拒的话还要说清"越到哪儿去了"。装包时的语法闸、运行时的每次判定、
 * JVM 测试用的是同一份纯逻辑 (不碰 Android API), 三处各写一遍的代价不是"多写几行", 而是迟早漂开
 *
 * **诚实声明** (协议第 5 节那条"不是墙"的原话): 这两道闸拦的是"经 Luwi 这个代理人去够"的那条路 ——
 * companion 的每一次调用都过这里, 所以对它是真的收紧; `inline` 与 Luwi 同一个 uid, 绕过能力直接
 * 读文件 / 发请求是拦不住的。它是**闸**, 不是安卓层面强制的边界
 */
internal object PluginFilesReadBoundary {

    /** 一次最多读多少 (与 `files.own` 同一个上限) */
    const val MAX_BYTES = 256 * 1024

    /** 前缀最长写多少 */
    private const val MAX_PREFIX = 200

    /**
     * 装包时的语法闸 (能力表里"声明可装"的那一条, 参数也得是个能用的形状)
     *
     * 这里只管形状: 绝对路径 / 不许 `..` / 不许就是 `/`。"这个范围碰没碰 Luwi 自己的私有目录"要等
     * 有 Context 的那两处判 (装的时候 [PluginInstaller] 查一遍, 运行时 [resolve] 里还有一层)
     */
    fun validateDeclaration(declared: String) {
        val prefix = declared.removePrefix("files.read:").trim()
        if (prefix.isEmpty()) {
            throw IllegalArgumentException("files.read: 后面要跟着一个路径前缀 (例如 files.read:/sdcard/DSH)")
        }
        if (prefix.length > MAX_PREFIX) {
            throw IllegalArgumentException("files.read 的前缀最多 $MAX_PREFIX 个字, 这一条有 ${prefix.length} 个")
        }
        if (!prefix.startsWith("/")) {
            throw IllegalArgumentException("files.read 的前缀要是一个绝对路径 (以 / 开头), 这一份写的是 \"$prefix\"")
        }
        if (prefix == "/") {
            throw IllegalArgumentException("files.read 不许把整个根目录给出去 (单独一个 /)")
        }
        if (prefix.split('/').any { it == ".." }) {
            throw IllegalArgumentException("files.read 的前缀里不许有 .. (这一份写的是 \"$prefix\")")
        }
    }

    /**
     * 一次调用的判定
     *
     * @param prefix 声明里那一截 (例如 `/sdcard/DSH`)
     * @param path 要读的路径: 绝对路径, 或者前缀下的相对路径; 空串 = 前缀自己
     * @param forbiddenRoots 私有的那几棵树 (调用方给 Luwi 自己的 dataDir) —— 前缀与目标都不许落进去
     * @return canonical 之后的目标
     *
     * 三层各挡一类: 前缀本身在私有树里 → 拒; 目标解 canonical 之后不在前缀里 → 拒 (`..` 跳出、软链
     * 跳出、`DSH` 与 `DSH2` 那种字面前缀撞车都在这一层); 目标落在私有树里 → 拒 (`/data` 这种覆盖
     * 到 Luwi 私有目录的宽前缀由这一层兜住)
     */
    fun resolve(prefix: String, path: String, forbiddenRoots: List<File>): File {
        val root = canonical(File(prefix), prefix)
        val forbidden = forbiddenRoots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        forbidden.firstOrNull { root == it || root.startsWith(it) }?.let {
            throw IllegalArgumentException("声明的前缀 $prefix 就在 Luwi 自己的私有目录里 (${it.path}), files.read 的范围不包括它")
        }
        val requested = path.trim()
        val target = when {
            requested.isEmpty() -> root
            requested.startsWith("/") -> File(requested)
            else -> File(root, requested)
        }
        val canonical = canonical(target, requested.ifEmpty { prefix })
        if (!canonical.startsWith(root)) {
            throw IllegalArgumentException("\"${requested}\" 不在声明的前缀 $prefix 里面 (解出来是 ${canonical.path})")
        }
        forbidden.firstOrNull { canonical == it || canonical.startsWith(it) }?.let {
            throw IllegalArgumentException("\"$requested\" 落在 Luwi 自己的私有目录里 (${it.path}), files.read 的范围不包括它")
        }
        return canonical
    }

    private fun canonical(file: File, shown: String): File = try {
        file.canonicalFile
    } catch (error: IOException) {
        throw IllegalArgumentException("\"$shown\" 这条路径现在解析不出来: ${error.message}")
    }
}

/**
 * `net.http:<域名>` 的边界 (协议第 5 节: 只允许访问列出的域名)
 *
 * 落地的三条口径 (2026-10-12, 与第 13 节 URL 安装同一条风格): **只走 https**, **重定向每一跳都
 * 重查**, **地址里不许带用户名密码**。域名逐字匹配 (不分大小写), 不支持通配 —— 要 `api` 与 `www`
 * 两条就声明两条, 名单宽窄是装包时摆给主人看的那一件事
 */
internal object PluginNetBoundary {

    /** 响应体一次最多收多少 */
    const val MAX_BODY = 1024 * 1024

    /** 请求体一次最多发多少 */
    const val MAX_REQUEST = 256 * 1024

    /** 不给 timeoutMs 时跑多久 */
    const val DEFAULT_TIMEOUT_MS = 15_000

    /** timeoutMs 的上限 */
    const val MAX_TIMEOUT_MS = 60_000

    /** 重定向最多跟几跳 */
    const val MAX_HOPS = 3

    /** 声明那一截的形状: 域名或 IPv4 字面量, 点分段, 不许带 scheme / 端口 / 路径 / 通配 */
    private val DOMAIN_RULE = Regex("^[a-z0-9]([a-z0-9.\\-]{0,251}[a-z0-9])?$")

    fun validateDeclaration(declared: String) {
        val domain = declared.removePrefix("net.http:").trim()
        if (domain.isEmpty()) {
            throw IllegalArgumentException("net.http: 后面要跟着一个域名 (例如 net.http:api.example.com)")
        }
        if (domain.length > 253 || !DOMAIN_RULE.matches(domain.lowercase())) {
            throw IllegalArgumentException(
                "net.http 后面要是一个域名 (例如 api.example.com; 不带 https:// 与路径), 这一份写的是 \"$domain\"",
            )
        }
        if (domain.contains("..")) {
            throw IllegalArgumentException("net.http 的域名里不许有连续的点 (这一份写的是 \"$domain\")")
        }
    }

    /** 声明里那一截的比对形态 (小写) */
    fun domainOf(declared: String): String = declared.removePrefix("net.http:").trim().lowercase()

    /** 一次调用与重定向的每一跳都过这里; 过不了就给一句点名的话 */
    fun checkHop(domain: String, url: HttpUrl): String? {
        if (!url.isHttps) {
            return "net.http 只走 https, 这一趟是 ${url.scheme}://${url.host} —— 明文那一档不给走"
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return "地址里不许带用户名密码 (${url.redact()})"
        }
        if (!url.host.equals(domain, ignoreCase = true)) {
            return "要访问的主机是 ${url.host}, 而这一条声明的是 $domain —— 只有声明过的主机才能去"
        }
        return null
    }
}
