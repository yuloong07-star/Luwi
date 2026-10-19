package io.github.yuloong07star.luwi.feedback

import java.net.URLEncoder
import java.util.Locale

/**
 * 反馈那一段的纯规则: 上限、兜底走哪条路、兜底那一段文字怎么拼、往哪儿发 (2.7.9 第 10 条)
 *
 * **这一份不碰 Android**: 它只吃字符串与数字, 于是 `FeedbackTest` 没有设备也能跑 —— 那三条判据
 * (500 字拦得住 / 没后端时进剪贴板 / 附件存在时不走后端) 都在这里量
 *
 * **应用里没有一个令牌** (施工单 §2.8): 那条 `POST <后端>/feedback` 由"同步 / 签到"那一台一起做,
 * 应用只提交正文与几项设备读数; 后端没就绪时走本地兜底 —— **复制正文 + 打开 New Issue 页**, 并明确说
 * "已复制", 不假装发送成功
 *
 * 收件的那两处都是公开信息 (仓库与 README 里就写着), 不是凭据:
 * 私密那一档走 `mailto:` 交给主人那台机器上的邮件客户端, **应用自己不碰 SMTP**
 */
internal object Feedback {

    /** 正文上限 (施工单 §2.8: 500 字, 实时计数) */
    const val LIMIT = 500

    /** 反馈去的那个仓库: 与更新源 (`AboutSettings.DEFAULT_SOURCE`) 同一个 */
    const val REPO = "yuloong07-star/Luwi"

    /** 兜底时打开的 New Issue 页 */
    const val ISSUE_PAGE = "https://github.com/$REPO/issues/new"

    /** 私密那一档的收件人: README「交流与反馈」里那个邮箱, 与仓库一样是公开信息 */
    const val EMAIL = "3021605064@qq.com"

    /** 两种方式: 公开 issue (缺省) 与私密邮件 */
    enum class Kind { ISSUE, EMAIL }

    /** 附件只记名字与大小 —— 这一版不上传字节 (后端没有那条接收面) */
    data class Attachment(val name: String, val bytes: Long)

    /**
     * 这一次提交走哪条路
     *
     * - [Route.POST]: 配了后端地址且没有附件 → 真的 POST 一次
     * - [Route.COPY_NO_BACKEND]: 没配后端地址 (这一版的常态) → 复制 + 打开页面
     * - [Route.COPY_WITH_ATTACHMENT]: 有附件 → **一律走兜底**, 因为这一版不带附件上传,
     *   假装发出去就会让那条 issue 少一半信息
     */
    enum class Route { POST, COPY_NO_BACKEND, COPY_WITH_ATTACHMENT }

    /** 500 字上限: 到了就截住, 不让人写出一段注定交不出去的正文 */
    fun clamp(text: String): String =
        if (text.length <= LIMIT) text else text.substring(0, LIMIT)

    /** 空正文 (或者只有空白) 不提交: 交出去也只是一条没有内容的 issue */
    fun ready(body: String): Boolean = body.isNotBlank()

    /** 这一次走哪条路 (见 [Route]) */
    fun route(endpoint: String, attachment: Attachment?): Route = when {
        endpoint.isBlank() -> Route.COPY_NO_BACKEND
        attachment != null -> Route.COPY_WITH_ATTACHMENT
        else -> Route.POST
    }

    /**
     * 兜底那一段文字: 正文 + 空行 + 那几行元信息
     *
     * 每一行都先 trim 再拼, 空的整行略掉 (联系方式是最常空着的那一项); 正文自己不带尾巴空白 ——
     * 复制出去的文字不该在 issue 页里多出几行空行
     */
    fun compose(body: String, extra: List<String>): String {
        val head = body.trim()
        val tail = extra.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return when {
            tail.isEmpty() -> head
            head.isEmpty() -> tail
            else -> "$head\n\n$tail"
        }
    }

    /** 字节数报成 KiB / MiB; 0 或负数回空串 (那一行整行都不写) */
    fun size(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MiB", bytes / 1024.0 / 1024.0)
    }

    /** 私密那一档的 `mailto:`: 只带主题, 正文靠剪贴板 —— 长正文塞进 URL 会被各家客户端截断 */
    fun mailUrl(subject: String): String =
        "mailto:$EMAIL?subject=" + URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
}
