package io.github.yuloong07star.luwi.update

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把远处那一条 Release 取回来 (网络那一层, 两份调用方共用)
 *
 * 两份调用方是设置页里手动「检测更新」([UpdateCheck]) 与冷启动那一次自动查 ([AutoUpdate]) —— 两者
 * 要的是同一条协议 (GitHub 的 API 要 User-Agent, 缺省源先直连后镜像), 而**只有后者碰不得
 * [UpdateCheck] 那份单例状态** (它一写, 手动那一趟的结果就被覆盖了, 见 2.7.9 施工单 F2), 所以取数
 * 这一步单独抽在这里: 谁都不持有状态, 谁都能调
 *
 * 报错仍旧只说域名 —— 完整地址太长, 而它本来就在设置页那一行写着
 */
internal object ReleaseFetch {

    /** 先直连后镜像, 第一个答上来的算数; 全都不行就把每一路各自的原因拼成一句抛上去 */
    fun latest(context: Context): ReleaseJson.Release {
        val failures = mutableListOf<String>()
        for (url in AboutSettings.attempts(context)) {
            try {
                return ReleaseJson.parse(raw(url))
            } catch (error: Throwable) {
                failures += "${host(url)}: ${error.message ?: error}"
            }
        }
        throw IllegalStateException(failures.joinToString("; "))
    }

    /** 取一条 URL 的正文 (GitHub 的 API 要 User-Agent, 少了它一律回 403) */
    fun raw(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Luwi")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            return connection.inputStream.use { stream -> stream.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    /** 报错时只报域名: 完整地址太长, 而它就在设置页那一行写着 */
    fun host(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
}
