package io.github.yuloong07star.luwi.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈那三条判据 (2.7.9 第 10 条)
 *
 * 施工单 §5 第 10 行要的是「500 字上限拦得住」与「无后端时已复制」, 而"已复制"那一下是界面那一侧的
 * 动作 —— 这里量的是它前面那个决定: **这一趟到底走 POST 还是走兜底** ([Feedback.route]), 以及兜底
 * 复制出去的那段文字是不是把该带的都带上了 ([Feedback.compose])
 *
 * 没有设备: [Feedback] 只吃字符串与数字, 所以这一整套在 JVM 上就能跑
 */
class FeedbackTest {

    /* ── 500 字 ─────────────────────────────────────────────────────────── */

    /** 上限之内一个字都不动 (499 / 500 都原样) */
    @Test
    fun `上限之内不截`() {
        val body = "字".repeat(500)
        assertEquals(body, Feedback.clamp(body))
        assertEquals(499, Feedback.clamp("字".repeat(499)).length)
    }

    /** 超了截到 500 (多出来的那部分是静默丢掉的, 界面上靠计数器提示) */
    @Test
    fun `超了截到五百`() {
        assertEquals(500, Feedback.clamp("字".repeat(501)).length)
        assertEquals(500, Feedback.clamp("字".repeat(2000)).length)
        assertEquals("字".repeat(500), Feedback.clamp("字".repeat(2000)))
    }

    /** 空正文不提交: 交出去也只是一条没有内容的 issue */
    @Test
    fun `空正文不提交`() {
        assertFalse(Feedback.ready(""))
        assertFalse(Feedback.ready("   \n\t "))
        assertTrue(Feedback.ready("打不开视频模式"))
    }

    /* ── 走哪条路 ───────────────────────────────────────────────────────── */

    /** 什么都没配 (这一版的常态): 进剪贴板 */
    @Test
    fun `没配就走兜底`() {
        assertEquals(
            Feedback.Route.COPY_NO_BACKEND,
            Feedback.route(Feedback.Kind.ISSUE, "", null, hasMail = false, hasIssue = false),
        )
        assertEquals(
            Feedback.Route.COPY_NO_BACKEND,
            Feedback.route(Feedback.Kind.EMAIL, "   ", null, hasMail = false, hasIssue = false),
        )
    }

    /** 配了后端又没有本地凭据: 真的 POST 一次 */
    @Test
    fun `配了后端就发出去`() {
        assertEquals(
            Feedback.Route.POST,
            Feedback.route(
                Feedback.Kind.ISSUE,
                "https://luwi.dpdns.org/feedback",
                null,
                hasMail = false,
                hasIssue = false,
            ),
        )
    }

    /** 设备上有发信凭据且选了私密: 直接发邮件 (本地凭据优先于后端地址) */
    @Test
    fun `有发信凭据就发邮件`() {
        assertEquals(
            Feedback.Route.MAIL,
            Feedback.route(Feedback.Kind.EMAIL, "", null, hasMail = true, hasIssue = true),
        )
        assertEquals(
            Feedback.Route.MAIL,
            Feedback.route(
                Feedback.Kind.EMAIL,
                "https://luwi.dpdns.org/feedback",
                null,
                hasMail = true,
                hasIssue = false,
            ),
        )
    }

    /** 设备上有仓库写权限且选了公开: 直接开 issue */
    @Test
    fun `有仓库写权限就开 issue`() {
        assertEquals(
            Feedback.Route.ISSUE,
            Feedback.route(Feedback.Kind.ISSUE, "", null, hasMail = false, hasIssue = true),
        )
    }

    /** 有附件一律走兜底 —— 两条真投递都不带附件, 假装发出去会让那条记录少一半信息 */
    @Test
    fun `有附件就不走真投递`() {
        assertEquals(
            Feedback.Route.COPY_WITH_ATTACHMENT,
            Feedback.route(
                Feedback.Kind.EMAIL,
                "https://luwi.dpdns.org/feedback",
                Feedback.Attachment("shot.png", 2048),
                hasMail = true,
                hasIssue = true,
            ),
        )
    }

    /* ── 设备上那份凭据 ─────────────────────────────────────────────────── */

    /** 完整的一份: 两条路都通 */
    @Test
    fun `凭据文件读得懂`() {
        val channel = FeedbackChannel.parse(
            """
            {
              "mailTo": "3021605064@qq.com",
              "smtp": { "host": "smtp.qq.com", "port": 465, "user": "3021605064@qq.com", "code": "abc" },
              "githubToken": "github_pat_x"
            }
            """,
        )
        assertTrue(channel.mailReady)
        assertTrue(channel.issueReady)
        assertEquals("smtp.qq.com", channel.smtpHost)
        assertEquals(465, channel.smtpPort)
        assertEquals("3021605064@qq.com", channel.mailTo)
    }

    /** 半份凭据不算数 (有 user 没 code): 界面上不能先答应"已发送"再去失败 */
    @Test
    fun `半份凭据不算数`() {
        val channel = FeedbackChannel.parse(
            """{ "mailTo": "a@b.c", "smtp": { "host": "smtp.qq.com", "user": "u" } }""",
        )
        assertFalse(channel.mailReady)
        assertFalse(channel.issueReady)
    }

    /** 只有仓库写权限时, 私密那一档仍然走兜底 */
    @Test
    fun `只有 token 时只有 issue 通`() {
        val channel = FeedbackChannel.parse("""{ "githubToken": "github_pat_x" }""")
        assertTrue(channel.issueReady)
        assertFalse(channel.mailReady)
    }

    /** 读坏的 JSON / 不是对象: 整条关闭, 不猜 */
    @Test
    fun `坏文件整条关闭`() {
        assertFalse(FeedbackChannel.parse("not json").issueReady)
        assertFalse(FeedbackChannel.parse("[1,2,3]").issueReady)
        assertFalse(FeedbackChannel.parse("").mailReady)
    }

    /* ── 那封信 ─────────────────────────────────────────────────────────── */

    /** 头齐、正文是 base64 (中文与换行都不必转义), 主题按 RFC 2047 编码 */
    @Test
    fun `邮件那头与正文都对`() {
        val letter = FeedbackMail.message(
            from = "3021605064@qq.com",
            to = "3021605064@qq.com",
            subject = "Luwi 反馈",
            body = "第一行\n第二行",
            at = 0L,
        )
        assertTrue(letter.startsWith("From: 3021605064@qq.com\r\nTo: 3021605064@qq.com\r\n"))
        assertTrue(letter.contains("Subject: =?UTF-8?B?"))
        assertTrue(letter.contains("Content-Transfer-Encoding: base64"))
        assertFalse("正文不许原样出现", letter.contains("第一行"))
        // base64 那一节解回来就是原文
        val encoded = letter.substringAfter("\r\n\r\n").replace("\r\n", "")
        assertEquals("第一行\n第二行", String(java.util.Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8))
    }

    /* ── issue 标题 ─────────────────────────────────────────────────────── */

    /** 标题取正文第一行, 太长截到 60; 空的退回"反馈 <版本>" */
    @Test
    fun `issue 标题取第一行`() {
        assertEquals("打不开视频模式", FeedbackIssue.titleOf("\n  打不开视频模式 \n后面还有", "2.7.9 (16)"))
        assertEquals("反馈 2.7.9 (16)", FeedbackIssue.titleOf("   \n ", "2.7.9 (16)"))
        assertEquals(60, FeedbackIssue.titleOf("字".repeat(200), "2.7.9 (16)").length)
    }

    /* ── 兜底那段文字 ───────────────────────────────────────────────────── */

    /** 正文 + 空行 + 元信息; 正文自己的尾巴空白不跟着走 */
    @Test
    fun `正文与元信息之间空一行`() {
        val text = Feedback.compose("  打不开视频模式 \n\n", listOf("Luwi 2.7.9 (16)", "Android 16"))
        assertEquals("打不开视频模式\n\nLuwi 2.7.9 (16)\nAndroid 16", text)
    }

    /** 空的那几行整行略掉 (联系方式是最常空着的那一项) */
    @Test
    fun `空的元信息行略掉`() {
        val text = Feedback.compose("一句话", listOf("", "方式: 公开 issue", "   "))
        assertEquals("一句话\n\n方式: 公开 issue", text)
    }

    /** 一行元信息都没有时只有正文, 也不留那条空行 */
    @Test
    fun `没有元信息就不留空行`() {
        assertEquals("一句话", Feedback.compose("一句话", emptyList()))
    }

    /* ── 去处 ───────────────────────────────────────────────────────────── */

    /** 兜底打开的是这个仓库的 New Issue 页 (与更新源同一个仓库) */
    @Test
    fun `兜底去仓库的 new issue 页`() {
        assertEquals("https://github.com/yuloong07-star/Luwi/issues/new", Feedback.ISSUE_PAGE)
    }

    /** 私密那一档: mailto 只带主题, 空格与中文都要编码 (不然各家客户端会截断) */
    @Test
    fun `邮件主题要编码`() {
        val url = Feedback.mailUrl("Luwi 反馈")
        assertTrue(url.startsWith("mailto:${Feedback.EMAIL}?subject="))
        assertFalse("主题里不该留裸空格", url.contains(" "))
        assertTrue("空格要编码成 %20 而不是 +", url.contains("%20"))
        assertTrue("中文要按 UTF-8 编码", url.contains("%E5%8F%8D%E9%A6%88"))
    }

    /* ── 大小显示 ───────────────────────────────────────────────────────── */

    /** 附件那一格: 拿到 0 就不写, 其余按 B / KiB / MiB 三档 */
    @Test
    fun `大小按三档念`() {
        assertEquals("", Feedback.size(0))
        assertEquals("", Feedback.size(-1))
        assertEquals("512 B", Feedback.size(512))
        assertEquals("1.5 KiB", Feedback.size(1536))
        assertEquals("2.0 MiB", Feedback.size(2L * 1024 * 1024))
    }
}
