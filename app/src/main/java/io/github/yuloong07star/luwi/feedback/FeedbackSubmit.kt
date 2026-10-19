package io.github.yuloong07star.luwi.feedback

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.yuloong07star.luwi.update.UpdateCheck
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 反馈的出口那一半: 设备读数、POST 那一次、后端地址从哪儿读 (2.7.9 第 10 条)
 *
 * **缺省那一台是官网** ([DEFAULT_ENDPOINT], `Luwi-html/functions/feedback.js`): 它拿着两只凭据去开
 * issue / 发邮件, 于是**任何一台装了 Luwi 的设备都能提交**, 而包里只有一个地址、一个令牌都没有
 * (施工单 §2.8: 反编译能拿到的东西里不许有仓库写权限与发信身份)
 *
 * 设备上另有一份本地凭据 (`$DSH_HOME/lw/feedback-channel.json`, 主人本机那一档) 时那一条优先;
 * 两条都失败、或者带附件, 才退回"复制 + 打开页面"的兜底 (见 `ui/FeedbackDialog.kt`)
 *
 * 发出去的体就是施工单那一行: `{kind, body, contact?, appVersion, device}`。**附件不在里面** ——
 * 带附件那一次一律走兜底, 见 [Feedback.Route], 这样"issue 里少了图却没有一句说明"不会发生
 */
internal object FeedbackSubmit {

    /** 后端地址 (例如 `https://luwi.dpdns.org/feedback`); 空 = 只走本地兜底 */
    const val KEY_ENDPOINT = "feedback-endpoint"

    /**
     * 缺省后端: 官网那一台 Pages Function (`Luwi-html/functions/feedback.js`)
     *
     * **它才让"别人也能提交"成立**: 两条真投递的凭据 (仓库写权限 / 发信身份) 只在那一台的服务端
     * Secrets 里, 包里只有这个地址; 设备上若另有一份本地凭据 (`feedback-channel.json`), 那一条优先
     * (主人本机那一档), 这里是其余所有设备的通路
     */
    const val DEFAULT_ENDPOINT = "https://luwi.dpdns.org/feedback"

    /** 与设置页那些偏好同一个文件 */
    private const val STORE = "luwi"
    private const val TAG = "LwFeedback"

    /**
     * 一次 POST 等多久 (连接与读取各一份)
     *
     * 反馈是"人点了一下就等"的动作, 不该像下载那样拖几十秒; 超时就当没接上, 走兜底 ——
     * 那条路一个字都不会丢
     */
    private const val TIMEOUT_MS = 10_000

    /** 现在配的后端地址: 存过就用存的 (自建 / 换一台), 没存过就是官网那一台 */
    fun endpoint(context: Context): String = context
        .getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(KEY_ENDPOINT, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_ENDPOINT

    /** 应用那一行: `2.7.9 (16)` —— 与「关于」里念的是同一对读数 */
    fun appLine(context: Context): String =
        "${UpdateCheck.localVersionOf(context)} (${UpdateCheck.localCodeOf(context)})"

    /** 设备那一行: 厂商型号 (代号) · Android 版本 (SDK) · 语言 */
    fun deviceLine(context: Context): String {
        val locale = context.resources.configuration.locales[0]?.toString().orEmpty()
        return listOf(
            "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            locale,
        ).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** 那一条 JSON 体 (施工单 §2.8 那一行, 一个键都不多) */
    fun payload(
        kind: Feedback.Kind,
        body: String,
        contact: String,
        app: String,
        device: String,
    ): JsonObject = buildJsonObject {
        put("kind", if (kind == Feedback.Kind.EMAIL) "email" else "issue")
        put("body", body.trim())
        if (contact.isNotBlank()) put("contact", contact.trim())
        put("appVersion", app)
        put("device", device)
    }

    /**
     * POST 一次: 成功回 `null`, 失败回一句人话 (那一句会念在窗里, 然后照旧走兜底)
     *
     * 用 `HttpURLConnection` 而不是 OkHttp: 这里就一次短请求, 与 [io.github.yuloong07star.luwi.update.ReleaseFetch]
     * 同一个写法; 下载那几条链用 OkHttp 是为了 Happy Eyeballs, 反馈不值当再引一份
     */
    fun post(endpoint: String, payload: JsonObject): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // 一条 POST 被 302 到别处就不再是我们那条后端了, 直接当失败
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Luwi")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                Log.i(TAG, "feedback accepted by $endpoint ($code)")
                null
            } else {
                Log.w(TAG, "feedback refused by $endpoint (HTTP $code)")
                "HTTP $code"
            }
        } catch (error: Throwable) {
            Log.w(TAG, "feedback could not reach $endpoint", error)
            error.message ?: error.toString()
        } finally {
            connection?.disconnect()
        }
    }
}
