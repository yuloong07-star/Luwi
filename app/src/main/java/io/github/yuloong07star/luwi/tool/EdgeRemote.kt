package io.github.yuloong07star.luwi.tool

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 远端那个 Edge 门票 (2026-10-20): 本地令牌被 403 时来这里要一个
 *
 * **为什么要有它**: 微软换令牌算法时 (2026-10 那次就是), 已经装出去的应用里那份实现只会回 403;
 * 让官网算一个 (`functions/api/edge-token.js`, JS 版同一份算法) 之后, **以后只更新服务端**,
 * 旧 APK 也能恢复。令牌只与时间有关, 不含任何用户数据 —— 这个端点看到的最多是一个 IP
 *
 * 三个纪律:
 * - **只在本地 403 之后才来** (正常那一路一个字节都不出设备)
 * - **窗口内复用**: 响应带 `expiresAt` (5 分钟档的末尾), 到了之前不再打第二趟
 * - **校验形状再收**: 令牌必须是 `16 hex-64 hex`, 版本得像 `2-…`, 不然当作没拿到
 *
 * 失败一律回 null (不抛): 调用方据此走"换系统引擎"那一档, 不让主人没声音
 */
internal object EdgeRemote {

    private const val TAG = "EdgeRemote"

    /** 与官网函数那条路由同一个地址 (施工单 §8.9) */
    private const val URL = "https://luwi.dpdns.org/api/edge-token"

    private val TOKEN = Regex("[0-9A-Fa-f]{16}-[0-9A-Fa-f]{64}")

    /** 一次拿到的令牌: 值、版本前缀与它什么时候作废 (epoch 秒) */
    internal data class Token(val token: String, val version: String, val expiresAt: Long)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cached: Token? = null

    /** 上一趟念用的是不是远端令牌 (读数/探针用) */
    @Volatile
    var lastWasRemote: Boolean = false
        private set

    /** 窗口内还新鲜的那一个 (离作废不到 5 秒就当没有) */
    internal fun valid(now: Long = System.currentTimeMillis()): Token? =
        cached?.takeIf { it.expiresAt * 1000 > now + 5_000 }

    /** 去要一个; 失败回 null */
    internal fun fetch(): Token? = try {
        val request = Request.Builder().url(URL).header("accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "the token endpoint answered ${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty()
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            val token = root?.get("token")?.jsonPrimitive?.contentOrNull.orEmpty()
            val version = root?.get("version")?.jsonPrimitive?.contentOrNull.orEmpty()
            val expiresAt = root?.get("expiresAt")?.jsonPrimitive?.longOrNull ?: 0L
            if (!TOKEN.matches(token) || !version.startsWith("2-") || expiresAt <= 0) {
                Log.w(TAG, "the token endpoint answered something that is not a token: ${body.take(120)}")
                return null
            }
            Token(token.uppercase(), version, expiresAt).also {
                cached = it
                Log.i(TAG, "got a remote Edge token (version $version, good until $expiresAt)")
            }
        }
    } catch (error: Throwable) {
        Log.w(TAG, "could not reach the token endpoint: ${error.message ?: error}")
        null
    }

    /** 问一趟之后记下这一趟用的是哪一份 (探针回执里说清"本地还是远端") */
    internal fun noteSource(remote: Boolean) {
        lastWasRemote = remote
    }

    /** 清掉缓存 (测试与强制刷新用) */
    internal fun clear() {
        cached = null
    }
}
