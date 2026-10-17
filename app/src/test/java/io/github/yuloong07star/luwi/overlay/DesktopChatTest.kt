package io.github.yuloong07star.luwi.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面对话那一本账 (2.7.8 批次 6): **全纯, 没有设备也能量**
 *
 * 那一格的内容只有一个来源 (那份 `desktop-chat.json`), 而它两侧都在写 (应用写 `user`, 宿主写
 * `assistant`), 于是四条性质最要紧:
 *
 * 1. 只留最近几条, 超了从最老的开始丢
 * 2. 不认识的那几条跳过, 而**不许把整个文件一起带走**
 * 3. 同一条连着写两遍只留一条 (推两遍的那条链确实存在)
 * 4. 编码再解析是同一本账 (两侧的写法必须逐字一致)
 */
class DesktopChatTest {

    private fun line(who: String, text: String, at: Long = 0L) = DesktopChatFile.Line(who, text, at)

    /** 一份正常文件: 那几条按顺序读回来 */
    @Test
    fun `读回那几条`() {
        val raw = """
            {"v":1,"at":10,"lines":[
              {"who":"user","text":"今天几号","at":1},
              {"who":"assistant","text":"十月十七号","at":2}]}
        """.trimIndent()
        val snapshot = DesktopChatFile.parse(raw)
        assertEquals("", snapshot.note)
        assertEquals(listOf(line("user", "今天几号", 1), line("assistant", "十月十七号", 2)), snapshot.lines)
        // 组件画的是最近那几条 (不超过 SHOW_LINES)
        assertEquals(2, snapshot.shown.size)
    }

    /** 超过上限时丢最老的 (读与写两头都要丢) */
    @Test
    fun `只留最近几条`() {
        val many = (0 until DesktopChatFile.MAX_LINES + 4).map { line("user", "第 $it 句", it.toLong()) }
        val raw = DesktopChatFile.encode(many, 0)
        val read = DesktopChatFile.parse(raw)
        assertEquals(DesktopChatFile.MAX_LINES, read.lines.size)
        assertEquals("第 ${DesktopChatFile.MAX_LINES + 4 - DesktopChatFile.MAX_LINES} 句".replace("${DesktopChatFile.MAX_LINES + 4 - DesktopChatFile.MAX_LINES}", "4"), read.lines.first().text)
        // 组件那一格只画最近 SHOW_LINES 条
        assertEquals(DesktopChatFile.SHOW_LINES, read.shown.size)
        assertEquals("第 ${DesktopChatFile.MAX_LINES + 3} 句", read.shown.last().text)
    }

    /** 坏的那一条跳过, 认识的那几条照旧读得出来; 整份坏掉时不抛, 只留一句人话 */
    @Test
    fun `坏的那一条不许把整本账带走`() {
        val raw = """
            {"v":1,"at":10,"lines":[
              {"who":"system","text":"这个来源还不认识"},
              {"who":"user","text":"  "},
              {"who":"assistant","text":"还在","at":3}]}
        """.trimIndent()
        assertEquals(listOf(line("assistant", "还在", 3)), DesktopChatFile.parse(raw).lines)
        val broken = DesktopChatFile.parse("{ 半个文件")
        assertTrue(broken.lines.isEmpty())
        assertTrue("读不懂要有一句人话", broken.note.isNotEmpty())
    }

    /** 一模一样的两条连着写只留一条 (回答那条链真会把同一条推两遍) */
    @Test
    fun `同一条连着写两遍只留一条`() {
        val once = DesktopChatFile.withLine(emptyList(), "assistant", "好的", 1)
        val twice = DesktopChatFile.withLine(once, "assistant", "好的", 2)
        assertEquals(once, twice)
        // 内容不同就是新的一条 (哪怕来源一样)
        assertEquals(2, DesktopChatFile.withLine(once, "assistant", "好的呀", 3).size)
    }

    /** 截一道: 换行压成空格, 超长带 `…` */
    @Test
    fun `长文本截一道`() {
        assertEquals("第一行 第二行", DesktopChatFile.clip("第一行\n\n第二行"))
        val long = "字".repeat(DesktopChatFile.MAX_CHARS + 20)
        val clipped = DesktopChatFile.clip(long)
        assertEquals(DesktopChatFile.MAX_CHARS, clipped.length)
        assertTrue(clipped.endsWith("…"))
        // 空白行 (发出去那一句是空的) 什么都不写
        assertEquals(emptyList<DesktopChatFile.Line>(), DesktopChatFile.withLine(emptyList(), "user", "   ", 0))
    }

    /** 编码再解析: 同一本账 (两侧的 `who` / `text` / `at` 三个键必须逐字一致) */
    @Test
    fun `存下去再读回来是同一本账`() {
        val lines = listOf(line("user", "带 \"引号\" 的一句", 7), line("assistant", "嗯", 8))
        val text = DesktopChatFile.encode(lines, 99)
        assertEquals(lines, DesktopChatFile.parse(text).lines)
        assertTrue("版本号与时间戳要写进去", text.contains("\"v\":1") && text.contains("\"at\":99"))
    }
}

