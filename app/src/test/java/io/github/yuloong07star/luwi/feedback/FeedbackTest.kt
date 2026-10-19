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

    /** 没配后端 (这一版的常态): 进剪贴板 */
    @Test
    fun `没配后端就走兜底`() {
        assertEquals(Feedback.Route.COPY_NO_BACKEND, Feedback.route("", null))
        assertEquals(
            Feedback.Route.COPY_NO_BACKEND,
            Feedback.route("   ", Feedback.Attachment("log.txt", 12)),
        )
    }

    /** 配了后端又没有附件: 真的 POST 一次 */
    @Test
    fun `配了后端就发出去`() {
        assertEquals(Feedback.Route.POST, Feedback.route("https://luwi.dpdns.org/feedback", null))
    }

    /** 有附件一律走兜底 —— 这一版不传字节, 走 POST 会让那条 issue 少一半信息 */
    @Test
    fun `有附件就不走后端`() {
        assertEquals(
            Feedback.Route.COPY_WITH_ATTACHMENT,
            Feedback.route("https://luwi.dpdns.org/feedback", Feedback.Attachment("shot.png", 2048)),
        )
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
