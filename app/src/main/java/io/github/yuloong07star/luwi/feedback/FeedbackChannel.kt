package io.github.yuloong07star.luwi.feedback

import android.content.Context
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 设备上那一份"真发出去"的通道凭据: `$DSH_HOME/lw/feedback-channel.json`
 *
 * **为什么是文件而不是包内常量**: 这台机器上的两只凭据 (仓库写权限与发信身份) 一旦编进 APK, 反编译
 * 就能拿到, 而它们换不掉也不需要给任何用户 —— 所以它们只躺在主人自己那台机器上, 由 `adb push`
 * 放进来; 别人装这一版时文件不存在, 反馈照旧走"复制 + 打开页面"的兜底 (2.7.9 T7 的口径)
 *
 * 形状 (每一项都可缺, 缺哪一项就关掉哪一条路):
 *
 * ```json
 * {
 *   "mailTo": "3021605064@qq.com",
 *   "smtp": { "host": "smtp.qq.com", "port": 465, "user": "3021605064@qq.com", "code": "<授权码>" },
 *   "githubToken": "github_pat_…"
 * }
 * ```
 *
 * 两条路各自成立: `smtp` + `mailTo` 齐了才能发私密邮件, `githubToken` 在就能开公开 issue。
 * 文件读坏 / 形状不对时**整条关闭** (回兜底), 不猜也不要半份凭据
 */
internal data class FeedbackChannel(
    val mailTo: String = "",
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val smtpUser: String = "",
    val smtpCode: String = "",
    val githubToken: String = "",
) {

    /** 私密邮件那一条通不通 */
    val mailReady: Boolean
        get() = mailTo.isNotBlank() && smtpHost.isNotBlank() &&
            smtpUser.isNotBlank() && smtpCode.isNotBlank() && smtpPort in 1..65535

    /** 公开 issue 那一条通不通 */
    val issueReady: Boolean get() = githubToken.isNotBlank()

    companion object {

        /** 文件就在设置那一堆里: `$DSH_HOME/lw/feedback-channel.json` */
        const val FILE = "feedback-channel.json"

        /** 什么都没有的那一份 (文件不存在 / 读坏时用它, 于是"没配"与"配坏了"都退同一条兜底) */
        val NONE = FeedbackChannel()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun file(context: Context): File = File(DshHost.settingsDirectory(context), FILE)

        /** 真机上那一趟: 文件不在就 [NONE], 读坏也 [NONE] */
        fun load(context: Context): FeedbackChannel = runCatching {
            val target = file(context)
            if (!target.isFile) return NONE
            parse(target.readText())
        }.getOrDefault(NONE)

        /**
         * 文件那一段 JSON 的解析: 形状不对就是 [NONE]
         *
         * 一条规则: **只认完完整整的凭据**。半份 (例如有 `user` 没有 `code`) 不算"配好了",
         * 否则界面上会先答应"已发送", 再在 SMTP 那一步失败 —— 那种失败比直接走兜底难查得多
         */
        fun parse(text: String): FeedbackChannel = try {
            val root = json.parseToJsonElement(text).jsonObject
            val smtp = root["smtp"] as? JsonObject
            val channel = FeedbackChannel(
                mailTo = root.text("mailTo").trim(),
                smtpHost = smtp?.text("host").orEmpty().trim(),
                smtpPort = smtp?.text("port")?.trim()?.toIntOrNull() ?: 465,
                smtpUser = smtp?.text("user").orEmpty().trim(),
                smtpCode = smtp?.text("code").orEmpty().trim(),
                githubToken = root.text("githubToken").trim(),
            )
            when {
                channel.mailReady || channel.issueReady -> channel
                else -> NONE
            }
        } catch (_: Throwable) {
            NONE
        }

        /** 取值容错: 数字 / 字符串都收, 缺了就空串 */
        private fun JsonObject.text(key: String): String =
            (this[key] as? JsonPrimitive)?.content.orEmpty()
    }
}
