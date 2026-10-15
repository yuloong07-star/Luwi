package io.github.yuloong07star.luwi.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `$DSH_HOME/lw/mode-model.json` 的读写 (设置页「模式与模型」那一段)
 *
 * 三条判据:
 *   1. 缺省就是「跟随当前模型」: 文件不在、读不懂、两个键都空, 三种都按跟随算
 *   2. 写的时候**别的键一个字节都不动** (保留未知键)
 *   3. 只填一半 / 越界的值在这里就被拒, 不落盘
 *
 * 全落在临时目录里量, 不碰真机
 */
class ModeModelTest {

    /** 一间临时目录; 结束时自清 */
    private fun dir(): File {
        val root = File(System.getProperty("java.io.tmpdir") ?: ".", "lw-mode-model-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        root.deleteOnExit()
        return root
    }

    private fun file(root: File) = File(root, ModeModel.FILE)

    /* ── 读 ─────────────────────────────────────────────────── */

    @Test
    fun `文件不在时是跟随当前模型`() {
        val snapshot = ModeModel.snapshotOf(file(dir()))
        assertFalse(snapshot.fileExists)
        assertTrue(snapshot.followsCurrent)
        assertFalse(snapshot.configured)
    }

    @Test
    fun `读不懂的文件不抛`() {
        val root = dir()
        File(root, ModeModel.FILE).writeText("{ not json")
        val snapshot = ModeModel.snapshotOf(file(root))
        assertTrue(snapshot.followsCurrent)
    }

    @Test
    fun `一份写好的配置读回来`() {
        val root = dir()
        File(root, ModeModel.FILE).writeText(
            """{"v":1,"video":{"provider":"deepseek-official","model":"deepseek-reasoner"},"future":42}""",
        )
        val snapshot = ModeModel.snapshotOf(file(root))
        assertTrue(snapshot.fileExists)
        assertFalse(snapshot.followsCurrent)
        assertTrue(snapshot.configured)
        assertEquals("deepseek-official", snapshot.videoProvider)
        assertEquals("deepseek-reasoner", snapshot.videoModel)
    }

    @Test
    fun `只填一半不算配好`() {
        val root = dir()
        File(root, ModeModel.FILE).writeText("""{"v":1,"video":{"provider":"deepseek-official","model":""}}""")
        val snapshot = ModeModel.snapshotOf(file(root))
        assertFalse(snapshot.configured)
        assertTrue(snapshot.followsCurrent)
    }

    /* ── 写: 保留未知键 ─────────────────────────────────────── */

    @Test
    fun `写一对不吃掉别的键`() {
        val root = dir()
        val target = file(root)
        target.writeText("""{"v":1,"future":{"keep":true},"video":{"provider":"a","model":"b","note":"x"}}""")
        assertNull(ModeModel.setVideoIn(target, "deepseek-official", "deepseek-chat"))
        val text = target.readText()
        assertTrue(text.contains("deepseek-official"))
        assertTrue(text.contains("deepseek-chat"))
        assertTrue("树顶的未知键要留着", text.contains("future"))
        assertTrue("video 段里的未知键也要留着", text.contains("note"))
    }

    @Test
    fun `跟随当前模型写的是两个空串`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setVideoIn(target, "a", "b"))
        assertNull(ModeModel.setVideoIn(target, "", "  "))
        val text = target.readText()
        val snapshot = ModeModel.snapshotOf(target)
        assertTrue(snapshot.followsCurrent)
        assertTrue("空串那一档也要把键写上 (施工单里那份形状)", text.contains("\"provider\": \"\""))
        assertTrue(text.contains("\"v\": 1"))
    }

    @Test
    fun `首写就带上 v`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setVideoIn(target, "a", "b"))
        assertTrue(target.readText().contains("\"v\": 1"))
    }

    /* ── 校验 ───────────────────────────────────────────────── */

    @Test
    fun `只填一半被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(ModeModel.setVideoIn(target, "deepseek-official", ""))
        assertNotNull(ModeModel.setVideoIn(target, "", "deepseek-chat"))
        assertFalse("拒了就不该写出文件", target.isFile)
    }

    @Test
    fun `带空白的字段被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(ModeModel.setVideoIn(target, "deepseek official", "deepseek-chat"))
        assertNull(ModeModel.setVideoIn(target, "deepseek-official", "deepseek-chat"))
    }

    @Test
    fun `越界长度的字段被拒`() {
        val root = dir()
        val target = file(root)
        val long = "m".repeat(ModeModel.MAX_FIELD_LENGTH + 1)
        assertNotNull(ModeModel.setVideoIn(target, long, "deepseek-chat"))
        assertNull(ModeModel.setVideoIn(target, "a".repeat(ModeModel.MAX_FIELD_LENGTH), "deepseek-chat"))
    }

    @Test
    fun `陌生字符被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(ModeModel.setVideoIn(target, "a b", "deepseek-chat"))
        assertNull(ModeModel.setVideoIn(target, "openai-compat", "gpt-4.1+2025-04-14"))
    }

    /* ── 自建端点那一段 (2026-10-16 主人: url / api 等放到视频模式设置里) ────── */

    @Test
    fun `自建端点写进去又读回来`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "sk-abcdefgh1234", "acme-think"))
        val snapshot = ModeModel.snapshotOf(target)
        assertTrue(snapshot.endpointSet)
        assertEquals("https://api.example.com/v1", snapshot.endpointUrl)
        assertEquals("acme-think", snapshot.endpointModel)
        assertEquals("sk-abcdefgh1234", snapshot.endpointSecret)
        assertTrue(snapshot.endpointKeySet)
        // 路由名是宿主那一侧挂的那一条
        assertEquals(ModeModel.ROUTE, snapshot.route)
        // 配好自建端点就不算"跟随当前模型"
        assertFalse(snapshot.followsCurrent)
        // 线上协议照缺省写上 (以后要加档就在这个键上)
        assertEquals(ModeModel.DEFAULT_WIRE, snapshot.endpointWire)
    }

    @Test
    fun `不要 key 的端点也收 (key 留空)`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setEndpointIn(target, "http://192.168.1.9:8000/v1", "", "qwen3-vl"))
        val snapshot = ModeModel.snapshotOf(target)
        assertTrue(snapshot.endpointSet)
        assertFalse(snapshot.endpointKeySet)
        assertFalse("key 空就不要写这个键", target.readText().contains("\"apiKey\""))
    }

    @Test
    fun `关掉自建端点时 key 留着`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "sk-abcdefgh1234", "acme-think"))
        assertNull(ModeModel.clearEndpointIn(target))
        val snapshot = ModeModel.snapshotOf(target)
        assertFalse(snapshot.endpointSet)
        assertTrue("地址与模型名清了, key 还在", snapshot.endpointSecret == "sk-abcdefgh1234")
        assertFalse(snapshot.endpointKeySet.not())
    }

    @Test
    fun `地址与模型名只填一个被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "", ""))
        assertNotNull(ModeModel.setEndpointIn(target, "", "", "acme-think"))
        assertFalse("拒了就不该写出文件", target.isFile)
    }

    @Test
    fun `不是 http 的地址被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(ModeModel.setEndpointIn(target, "api.example.com/v1", "", "acme-think"))
        assertNotNull(ModeModel.setEndpointIn(target, "ftp://api.example.com", "", "acme-think"))
        assertNull(ModeModel.setEndpointIn(target, "http://api.example.com/v1", "", "acme-think"))
    }

    @Test
    fun `带空格的 key 与非 ASCII 的 key 被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "sk abc", "acme-think"))
        assertNotNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "密钥", "acme-think"))
        assertNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "sk-abc", "acme-think"))
    }

    @Test
    fun `key 的打码形态只留尾四位`() {
        assertEquals("<unset>", ModeModel.maskedKey(""))
        assertEquals("••••", ModeModel.maskedKey("short"))
        assertEquals("••••1234", ModeModel.maskedKey("sk-abcdefgh1234"))
    }

    @Test
    fun `写端点不吃掉别的键 (video 段与树顶的未知键都在)`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setVideoIn(target, "deepseek-official", "deepseek-v4-pro"))
        target.writeText(target.readText().replaceFirst("{", """{"future":42,"""))
        assertNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "sk-abc", "acme-think"))
        val text = target.readText()
        assertTrue(text.contains("future"))
        assertTrue(text.contains("deepseek-v4-pro"))
        // 两个档可以同时在文件里: 配了自建端点就走它, 而 video 那一对原样留着 (清掉端点就回到它)
        val snapshot = ModeModel.snapshotOf(target)
        assertEquals(ModeModel.ROUTE, snapshot.route)
        assertEquals("deepseek-v4-pro", snapshot.videoModel)
    }

    @Test
    fun `端点配好之后摘要里没有明文 key`() {
        val root = dir()
        val target = file(root)
        assertNull(ModeModel.setEndpointIn(target, "https://api.example.com/v1", "sk-abcdefgh1234", "acme-think"))
        val summary = ModeModel.snapshotOf(target).endpointSummary
        assertFalse("摘要是给人看的, 只能有打码形态", summary.contains("sk-abcdefgh1234"))
        assertTrue(summary.contains("••••1234"))
        assertTrue(summary.contains("acme-think"))
    }
}
