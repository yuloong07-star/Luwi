package io.github.yuloong07star.luwi.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `$DSH_HOME/lw/session-sync.json` 的读写 (设置页「Luwi-dsh-sync」那一段)
 *
 * 这一段是手机侧**唯一**的配置面, 所以三条判据都要钉住:
 *   1. 写一个键时**别的键一个字节都不动** (人手工写进去的、插件以后加的, 都不能被吃掉)
 *   2. 越界的值在手机上就被拒 (返回一句人话), 而不是等宿主加载期把插件停用
 *   3. 令牌只在文件里: 界面上那两处 (打码形态 / 「看当前配置」) 都不带原文
 *
 * 全落在临时目录里量, 不碰真机
 */
class SyncSettingTest {

    /** 一间临时目录; 结束时自清 */
    private fun dir(): File {
        val root = File(System.getProperty("java.io.tmpdir") ?: ".", "lw-sync-setting-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        root.deleteOnExit()
        return root
    }

    private fun file(root: File) = File(root, SyncSetting.FILE)

    /* ── 读 ─────────────────────────────────────────────────── */

    @Test
    fun `文件不在时全按缺省`() {
        val snapshot = SyncSetting.snapshotOf(file(dir()))
        assertFalse(snapshot.fileExists)
        assertEquals("", snapshot.sessionRemote)
        assertEquals(0, snapshot.pullInterval)
        // 对端那三个缺省: 通道默认开 (缺省值跟 DEFAULTS)、listen 与 proxy 也开
        assertTrue(snapshot.peerListen)
        assertTrue(snapshot.peerProxy)
        assertFalse("没有口令时通道是关的", snapshot.peerReady)
    }

    @Test
    fun `一份写好的配置读回来`() {
        val root = dir()
        File(root, SyncSetting.FILE).writeText(
            """
            {
              "remote": "https://github.com/me/sessions.git",
              "autoPullOnStart": true,
              "pullIntervalMinutes": 2,
              "deviceName": "Luwi 手机",
              "workspace": { "remote": "https://github.com/me/ws.git", "exclude": ["node_modules"] },
              "peer": { "token": "0123456789abcdef", "url": "http://100.64.0.7:3080", "proxy": false }
            }
            """.trimIndent(),
        )
        val snapshot = SyncSetting.snapshotOf(file(root))
        assertTrue(snapshot.fileExists)
        assertTrue(snapshot.sessionRemoteSet)
        assertTrue(snapshot.autoPull)
        assertEquals(2, snapshot.pullInterval)
        assertEquals("Luwi 手机", snapshot.deviceName)
        assertTrue(snapshot.workspaceOn)
        assertTrue(snapshot.workspaceRemoteSet)
        assertTrue(snapshot.peerReady)
        assertEquals("http://100.64.0.7:3080", snapshot.peerUrl)
        assertFalse(snapshot.peerProxy)
        assertTrue("没写的键跟缺省", snapshot.peerListen)
    }

    @Test
    fun `读不懂的文件不抛`() {
        val root = dir()
        File(root, SyncSetting.FILE).writeText("{ not json")
        val snapshot = SyncSetting.snapshotOf(file(root))
        assertEquals("", snapshot.sessionRemote)
    }

    /* ── 写: 保留未知键 ─────────────────────────────────────── */

    @Test
    fun `写一个键不吃掉别的键`() {
        val root = dir()
        val target = file(root)
        target.writeText(
            """{"remote":"git://old/sessions.git","futureKey":42,"workspace":{"remote":"git://ws.git","maxFileBytes":1048576}}""",
        )
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_SESSION_REMOTE, "git://new/sessions.git"))
        val text = target.readText()
        assertTrue(text.contains("git://new/sessions.git"))
        assertTrue("未知键要留着", text.contains("futureKey"))
        assertTrue(text.contains("42"))
        assertTrue("别的段不动", text.contains("git://ws.git"))
        assertTrue(text.contains("maxFileBytes"))
    }

    @Test
    fun `删掉一个键用空串`() {
        val root = dir()
        val target = file(root)
        target.writeText("""{"remote":"git://old/x.git","branch":"dev"}""")
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_SESSION_REMOTE, "   "))
        val snapshot = SyncSetting.snapshotOf(target)
        assertFalse(snapshot.sessionRemoteSet)
        assertEquals("别的键照旧", "dev", snapshot.branch)
    }

    @Test
    fun `两个 remote 各写各的段`() {
        val root = dir()
        val target = file(root)
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_SESSION_REMOTE, "git://a/sessions.git"))
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_WORKSPACE_REMOTE, "git://a/ws.git"))
        val snapshot = SyncSetting.snapshotOf(target)
        assertEquals("git://a/sessions.git", snapshot.sessionRemote)
        assertEquals("git://a/ws.git", snapshot.workspaceRemote)
    }

    @Test
    fun `布尔与间隔写进去`() {
        val root = dir()
        val target = file(root)
        assertNull(SyncSetting.setFlagIn(target, SyncSetting.FLAG_AUTO_PUSH, true))
        assertNull(SyncSetting.setFlagIn(target, SyncSetting.FLAG_PEER_ON, true))
        assertNull(SyncSetting.setFlagIn(target, SyncSetting.FLAG_PEER_LISTEN, false))
        assertNull(SyncSetting.setPullIntervalIn(target, 1))
        val snapshot = SyncSetting.snapshotOf(target)
        assertTrue(snapshot.autoPush)
        assertTrue(snapshot.peerOn)
        assertFalse(snapshot.peerListen)
        assertEquals(1, snapshot.pullInterval)
    }

    /* ── 校验 ───────────────────────────────────────────────── */

    @Test
    fun `不像地址的 remote 被拒且不落盘`() {
        val root = dir()
        val target = file(root)
        val problem = SyncSetting.setTextIn(target, SyncSetting.FIELD_SESSION_REMOTE, "这不是地址")
        assertNotNull(problem)
        assertFalse("拒了就不该写出文件", target.isFile)
    }

    @Test
    fun `对端地址只收 http`() {
        val root = dir()
        val target = file(root)
        assertNotNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_PEER_URL, "100.64.0.7:3080"))
        assertNotNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_PEER_URL, "ftp://host/x"))
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_PEER_URL, "http://100.64.0.7:3080"))
    }

    @Test
    fun `口令太短被拒`() {
        val root = dir()
        val target = file(root)
        val problem = SyncSetting.setTextIn(target, SyncSetting.FIELD_PEER_TOKEN, "short")
        assertNotNull(problem)
        assertTrue(problem!!.contains(SyncSetting.MIN_TOKEN_LENGTH.toString()))
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_PEER_TOKEN, "0123456789abcdef"))
    }

    @Test
    fun `间隔越界被拒`() {
        val root = dir()
        val target = file(root)
        assertNotNull(SyncSetting.setPullIntervalIn(target, -1))
        assertNotNull(SyncSetting.setPullIntervalIn(target, SyncSetting.MAX_PULL_INTERVAL + 1))
        assertNull(SyncSetting.setPullIntervalIn(target, SyncSetting.MAX_PULL_INTERVAL))
    }

    @Test
    fun `分支名的字符集`() {
        val root = dir()
        val target = file(root)
        assertNotNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_BRANCH, "bad branch"))
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_BRANCH, "release/2.0.0"))
    }

    /* ── 令牌纪律 ───────────────────────────────────────────── */

    @Test
    fun `打码形态只留尾四位`() {
        assertEquals("<unset>", SyncSetting.maskedToken(""))
        assertEquals("••••", SyncSetting.maskedToken("short"))
        assertEquals("••••cdef", SyncSetting.maskedToken("0123456789abcdef"))
    }

    @Test
    fun `看当前配置那一页不带真口令`() {
        val root = dir()
        val target = file(root)
        assertNull(SyncSetting.setTextIn(target, SyncSetting.FIELD_PEER_TOKEN, "0123456789abcdef"))
        val shown = SyncSetting.prettyOf(target)
        assertFalse(shown.contains("0123456789abcdef"))
        assertTrue(shown.contains("••••cdef"))
        assertTrue(shown.contains("\"token\""))
    }

    @Test
    fun `还没有配置时看当前配置说的是路径`() {
        val shown = SyncSetting.prettyOf(file(dir()))
        assertTrue(shown.contains(SyncSetting.FILE))
    }

    /* ── 「同步」总开关 (默认关) ─────────────────────────────── */

    @Test
    fun `默认是关的 (没有远端就是关)`() {
        assertFalse(SyncSetting.snapshotOf(file(dir())).syncOn)
    }

    @Test
    fun `关掉同步时地址挪进 lw 段存着, 三个自动值一起归零`() {
        val root = dir()
        val target = file(root)
        target.writeText(
            """{"remote":"git://a/sessions.git","autoPullOnStart":true,"autoPushOnTurnEnd":true,"pullIntervalMinutes":2,"deviceName":"Luwi 手机"}""",
        )
        assertNull(SyncSetting.setSyncOnIn(target, false))
        val parked = SyncSetting.snapshotOf(target)
        assertFalse("关着 = 远端为空 (插件那一侧认的就是它)", parked.syncOn)
        assertEquals("地址没丢, 存在 lw 段里", "git://a/sessions.git", parked.resumableRemote)
        // 光清远端不够: 插件的引擎是无条件建的, 周期拉取还会跑 (那一轮要经沙箱起 proot+git)
        assertFalse(parked.autoPull)
        assertFalse(parked.autoPush)
        assertEquals(0, parked.pullInterval)
        assertEquals("别的键一个都不动", "Luwi 手机", parked.deviceName)
    }

    @Test
    fun `再打开时地址与三个自动值都回来`() {
        val root = dir()
        val target = file(root)
        target.writeText(
            """{"remote":"git://a/sessions.git","autoPullOnStart":true,"autoPushOnTurnEnd":true,"pullIntervalMinutes":5}""",
        )
        assertNull(SyncSetting.setSyncOnIn(target, false))
        assertNull(SyncSetting.setSyncOnIn(target, true))
        val back = SyncSetting.snapshotOf(target)
        assertTrue(back.syncOn)
        assertEquals("git://a/sessions.git", back.sessionRemote)
        assertTrue(back.autoPull)
        assertTrue(back.autoPush)
        assertEquals(5, back.pullInterval)
        assertFalse("填回去之后 lw 段不该再留着那份地址", target.readText().contains("savedRemote"))
    }

    @Test
    fun `没填过地址时打不开同步`() {
        val root = dir()
        val target = file(root)
        val problem = SyncSetting.setSyncOnIn(target, true)
        assertNotNull("要说清为什么", problem)
        assertFalse(SyncSetting.snapshotOf(target).syncOn)
    }

    /* ── 扫码配对 (2026-10-20) ────────────────────────────────── */

    /** 一份能写进去的配对载荷 (各个用例只换自己关心的那一项) */
    private fun paired(
        remote: String = "https://user:token@github.com/me/sessions.git",
        branch: String = "release/2.0.0",
        peerUrl: String = "http://100.64.0.7:3080",
        peerToken: String = "0123456789abcdef0123456789abcdef",
        from: String = "书房 电脑",
        at: Long? = 1760000000000L,
    ) = PairPayload(
        v = 1,
        id = "3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21",
        remote = remote,
        branch = branch,
        peerUrl = peerUrl,
        peerToken = peerToken,
        from = from,
        at = at,
    )

    @Test
    fun `扫码配对把该写的写全, lw 与 workspace 一个字节都不动`() {
        val root = dir()
        val target = file(root)
        target.writeText(
            """{"remote":"git://old/sessions.git","lw":{"savedRemote":"git://parked/x.git"},""" +
                """"workspace":{"remote":"git://ws.git","enabled":true},"futureKey":42}""",
        )
        assertNull(SyncSetting.applyPairingIn(target, paired(), now = 1L))
        val snapshot = SyncSetting.snapshotOf(target)
        assertEquals("https://user:token@github.com/me/sessions.git", snapshot.sessionRemote)
        assertEquals("release/2.0.0", snapshot.branch)
        assertTrue("对端通道要打开", snapshot.peerOn)
        assertTrue("影子工作区也要打开", snapshot.peerProxy)
        assertTrue(snapshot.peerReady)
        assertEquals("http://100.64.0.7:3080", snapshot.peerUrl)
        assertTrue(snapshot.paired)
        assertEquals("书房 电脑", snapshot.pairedFrom)
        assertEquals(1760000000000L, snapshot.pairedAt)
        assertEquals("github.com", snapshot.repoHost)
        assertTrue(snapshot.autoPull)
        assertTrue(snapshot.autoPush)
        assertEquals("原来没有拉取间隔, 配对时补 15 分钟", 15, snapshot.pullInterval)
        assertEquals("Luwi 手机", snapshot.deviceName)
        val text = target.readText()
        assertTrue("lw 段一个字都不动", text.contains("savedRemote"))
        assertTrue("workspace 段一个字都不动", text.contains("git://ws.git"))
        assertTrue("未知键留着", text.contains("futureKey"))
    }

    @Test
    fun `配对保留 peer 段别的键与已经填好的间隔`() {
        val root = dir()
        val target = file(root)
        target.writeText(
            """{"deviceName":"我的手机","pullIntervalMinutes":30,""" +
                """"peer":{"listen":false,"token":"zzzzzzzzzzzzzzzz","sendTimeoutMs":8000}}""",
        )
        assertNull(SyncSetting.applyPairingIn(target, paired(peerToken = ""), now = 1L))
        val snapshot = SyncSetting.snapshotOf(target)
        assertFalse("listen 是别人写的键", snapshot.peerListen)
        assertTrue("间隔已经填过就不覆盖", snapshot.pullInterval == 30)
        assertEquals("设备名已经起过就不覆盖", "我的手机", snapshot.deviceName)
        assertTrue("口令留空就沿用原来那一份", snapshot.peerToken.isNotEmpty())
        val text = target.readText()
        assertTrue(text.contains("sendTimeoutMs"))
        assertTrue(text.contains("\"listen\": false") || text.contains("\"listen\":false"))
    }

    @Test
    fun `配对时分支留空按 main, at 缺了用当下时刻`() {
        val root = dir()
        val target = file(root)
        assertNull(SyncSetting.applyPairingIn(target, paired(branch = "", at = null), now = 1234L))
        val snapshot = SyncSetting.snapshotOf(target)
        assertEquals("main", snapshot.branch)
        assertEquals(1234L, snapshot.pairedAt)
    }

    @Test
    fun `配对码没有仓库地址时拒写`() {
        val root = dir()
        val target = file(root)
        assertNotNull(SyncSetting.applyPairingIn(target, paired(remote = "  "), now = 1L))
        assertFalse("拒了就不该写出文件", target.isFile)
    }
}
