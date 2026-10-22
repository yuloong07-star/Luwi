package io.github.yuloong07star.luwi.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 扫码配对的载荷解析 (`sync/PairPayload.kt`)
 *
 * 这一份的判据与插件那一侧 (`lib/luwi-pairing.mjs` + `test/luwi-pairing.test.mjs`) 是同一件事:
 * 合法的要逐项读回来, 不合法的要落到**对的那一档**上 —— 只要有一档错了, 界面上念出来的就是另一件事,
 * 而"扫到了别人的二维码"与"配对码坏了"要人做的事完全不同
 *
 * 全在 JVM 上量 (这一份是纯 Kotlin), 不碰设备
 */
class PairPayloadTest {

    /** 一段 JSON → 二维码里那串文本 (与插件 `encodePairing` 同一个形状: base64url 不带 padding) */
    private fun code(json: String): String =
        PairPayload.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    /** 一份能过校验的 JSON; 各个用例只换自己关心的那一项 */
    private fun json(
        v: String = "1",
        id: String = "3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21",
        remote: String = "https://user:token@github.com/me/luwi-sessions.git",
        branch: String = "release/2.0.0",
        peerUrl: String = "http://100.64.0.7:3080",
        peerToken: String = "0123456789abcdef0123456789abcdef",
        from: String = "书房 电脑",
        at: String = "1760000000000",
    ): String = """
        {
          "v": $v,
          "id": "$id",
          "remote": "$remote",
          "branch": "$branch",
          "peerUrl": "$peerUrl",
          "peerToken": "$peerToken",
          "from": "$from",
          "at": $at
        }
    """.trimIndent()

    private fun okOf(text: String): PairPayload {
        val result = PairPayload.parse(text)
        assertTrue("应该解析成功, 实际是 $result", result is PairResult.Ok)
        return (result as PairResult.Ok).payload
    }

    private fun errorOf(text: String): PairError {
        val result = PairPayload.parse(text)
        assertTrue("应该被拒, 实际是 $result", result is PairResult.Rejected)
        return (result as PairResult.Rejected).error
    }

    /* ── 合法的那些 ─────────────────────────────────────────── */

    @Test
    fun `合法载荷逐项读回来 (中文设备名与带凭据的地址)`() {
        val payload = okOf(code(json()))
        assertEquals(1, payload.v)
        assertEquals("3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21", payload.id)
        assertEquals("https://user:token@github.com/me/luwi-sessions.git", payload.remote)
        assertEquals("release/2.0.0", payload.branch)
        assertEquals("http://100.64.0.7:3080", payload.peerUrl)
        assertEquals("0123456789abcdef0123456789abcdef", payload.peerToken)
        assertEquals("书房 电脑", payload.from)
        assertEquals(1760000000000L, payload.at)
    }

    @Test
    fun `空分支按 main`() {
        assertEquals("main", okOf(code(json(branch = ""))).branch)
    }

    @Test
    fun `at 可以省略`() {
        val withoutAt = """
            {"v":1,"id":"3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21","remote":"git://host/repo.git","branch":"main",
             "peerUrl":"","peerToken":"0123456789abcdef","from":"电脑"}
        """.trimIndent()
        assertNull(okOf(code(withoutAt)).at)
    }

    @Test
    fun `对端那两项可以留空 (沿用手机现在那一份)`() {
        val payload = okOf(code(json(peerUrl = "", peerToken = "")))
        assertEquals("", payload.peerUrl)
        assertEquals("", payload.peerToken)
    }

    @Test
    fun `几种 git 远端都收`() {
        for (remote in listOf(
            "https://github.com/me/sessions.git",
            "git://host/me/sessions.git",
            "ssh://git@host/me/sessions.git",
            "file:///srv/git/sessions.git",
            "git@github.com:me/sessions.git",
            "/srv/git/sessions.git",
            "D:/repos/sessions.git",
        )) {
            assertEquals(remote, okOf(code(json(remote = remote))).remote)
        }
    }

    @Test
    fun `反斜杠的盘符路径也收`() {
        // JSON 里那个反斜杠要写成两个, 而这一份字符串是 raw string —— 所以这里看到的是四个
        val text = code(
            """{"v":1,"id":"3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21","remote":"D:\\repos\\sessions.git",
             "branch":"main","peerUrl":"","peerToken":"","from":"电脑"}""",
        )
        assertEquals("D:\\repos\\sessions.git", okOf(text).remote)
    }

    @Test
    fun `前后空白不算内容`() {
        val payload = okOf("  " + code(json()) + "\n")
        assertEquals("书房 电脑", payload.from)
    }

    /* ── 不是配对码 / 坏了 ─────────────────────────────────── */

    @Test
    fun `空串`() {
        assertEquals(PairError.NotPairingCode, errorOf(""))
        assertEquals(PairError.NotPairingCode, errorOf("   "))
    }

    @Test
    fun `别的二维码`() {
        assertEquals(PairError.NotPairingCode, errorOf("https://example.com/somewhere"))
        assertEquals(PairError.NotPairingCode, errorOf("luwi-sync://pair?pc=ABCD"))
    }

    @Test
    fun `base64 坏了`() {
        assertEquals(PairError.CorruptedCode, errorOf(PairPayload.PREFIX + "看起来像是中文"))
        assertEquals(PairError.CorruptedCode, errorOf(PairPayload.PREFIX + "abc\$def"))
    }

    @Test
    fun `JSON 坏了`() {
        assertEquals(PairError.CorruptedCode, errorOf(code("{\"v\":1,")))
        assertEquals(PairError.CorruptedCode, errorOf(code("这不是 JSON")))
    }

    @Test
    fun `JSON 不是对象`() {
        assertEquals(PairError.CorruptedCode, errorOf(code("[1,2,3]")))
        assertEquals(PairError.CorruptedCode, errorOf(code("\"luwi-sync\"")))
    }

    /* ── 逐项校验 ───────────────────────────────────────────── */

    @Test
    fun `版本不对`() {
        assertEquals(PairError.UnsupportedVersion, errorOf(code(json(v = "2"))))
        assertEquals(PairError.UnsupportedVersion, errorOf(code(json(v = "0"))))
        // 字符串里的 1 不算版本号 (与插件 typeof 那一条同一个答案)
        assertEquals(PairError.UnsupportedVersion, errorOf(code(json(v = "\"1\""))))
    }

    @Test
    fun `版本缺了也算版本不对`() {
        val noVersion = """{"id":"3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21","remote":"git://host/repo.git"}"""
        assertEquals(PairError.UnsupportedVersion, errorOf(code(noVersion)))
    }

    @Test
    fun `id 坏`() {
        assertEquals(PairError.BadId, errorOf(code(json(id = "short"))))
        assertEquals(PairError.BadId, errorOf(code(json(id = "-3f9a1c2e-8b4d"))))
        assertEquals(PairError.BadId, errorOf(code(json(id = "3f9a1c2e_8b4d"))))
        assertEquals(PairError.BadId, errorOf(code(json(id = ""))))
    }

    @Test
    fun `remote 坏`() {
        assertEquals(PairError.BadRemote, errorOf(code(json(remote = ""))))
        assertEquals(PairError.BadRemote, errorOf(code(json(remote = "就是电脑上那个仓库"))))
        assertEquals(PairError.BadRemote, errorOf(code(json(remote = "github.com/me/sessions.git"))))
        assertEquals(PairError.BadRemote, errorOf(code(json(remote = "https://host/a b.git"))))
    }

    @Test
    fun `branch 坏`() {
        assertEquals(PairError.BadBranch, errorOf(code(json(branch = "bad branch"))))
        assertEquals(PairError.BadBranch, errorOf(code(json(branch = "dev?"))))
        assertEquals(PairError.BadBranch, errorOf(code(json(branch = "特性分支"))))
    }

    @Test
    fun `peerUrl 坏`() {
        assertEquals(PairError.BadPeerUrl, errorOf(code(json(peerUrl = "100.64.0.7:3080"))))
        assertEquals(PairError.BadPeerUrl, errorOf(code(json(peerUrl = "ftp://host/x"))))
        assertEquals(PairError.BadPeerUrl, errorOf(code(json(peerUrl = "http://has space"))))
    }

    @Test
    fun `peerToken 太短`() {
        assertEquals(PairError.BadPeerToken, errorOf(code(json(peerToken = "0123456789abcde"))))
    }

    @Test
    fun `peerToken 太长`() {
        assertEquals(PairError.BadPeerToken, errorOf(code(json(peerToken = "a".repeat(257)))))
    }

    @Test
    fun `peerToken 带空白`() {
        assertEquals(PairError.BadPeerToken, errorOf(code(json(peerToken = "0123456789 abcdef"))))
    }

    @Test
    fun `from 太长`() {
        // 65 个字: 上限是 64
        assertEquals(PairError.TooLong, errorOf(code(json(from = "a".repeat(65)))))
    }

    @Test
    fun `整段太长`() {
        // 前缀对、字母表也对, 只是长过一个建能收的 (1200 字节的 4/3 再加一点余量)
        assertEquals(PairError.TooLong, errorOf(PairPayload.PREFIX + "A".repeat(2_000)))
    }

    @Test
    fun `多出来的键不影响解析`() {
        val extra = """
            {"v":1,"id":"3f9a1c2e-8b4d-4c1a-9f2e-7d6b5a4c3e21","remote":"git://host/repo.git",
             "branch":"main","peerUrl":"","peerToken":"","from":"电脑","futureKey":{"a":1}}
        """.trimIndent()
        assertEquals("git://host/repo.git", okOf(code(extra)).remote)
    }
}
