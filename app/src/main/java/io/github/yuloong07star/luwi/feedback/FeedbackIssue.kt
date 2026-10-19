package io.github.yuloong07star.luwi.feedback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公开那一档的投递: 用仓库写权限直接开一条 issue (api.github.com)
 *
 * 与私密那一档同一个前提 —— 凭据只躺在主人自己那台机器上 ([FeedbackChannel] 的 `githubToken`),
 * 不在包里。`github.com:443` 被 SNI 挡住的网络里 `api.github.com` 仍然通 (应用更新那条链就是它)
 *
 * issue 的标题取正文第一行: 反馈写的多半就是"哪儿坏了"那一句, 比"反馈"两个字的信息量大得多
 */
internal object FeedbackIssue {

    private const val TIMEOUT_MS = 20_000

    /** 标题: 正文第一行, 最多 60 字; 正文是空的时候退回"反馈 <版本>" */
    fun titleOf(body: String, app: String): String {
        val first = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val cut = if (first.length <= 60) first else first.substring(0, 60)
        return cut.ifBlank { "反馈 $app" }
    }

    /** 开一条 issue: 成功回 `null`, 失败回一句人话 (HTTP 码 + 服务器那句) */
    fun create(token: String, title: String, body: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://api.github.com/repos/${Feedback.REPO}/issues")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "Luwi")
            }
            val payload: JsonObject = buildJsonObject {
                put("title", title)
                put("body", body)
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                null
            } else {
                // GitHub 的错因全在体里 ("Bad credentials" / "Issues are disabled"), 那一段要带回去
                val said = runCatching {
                    (connection.errorStream ?: connection.inputStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                "HTTP $code ${said.take(200)}".trim()
            }
        } catch (error: Throwable) {
            error.message ?: error.toString()
        } finally {
            connection?.disconnect()
        }
    }
}
