package io.github.yuloong07star.luwi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge 在线那条的协议算术: GEC 门、连接地址、SSML、时间戳与二进制帧
 *
 * GEC 那一条是**外部实现算出来的固定向量** (rany2/edge-tts 的算法, 用 python 复算过): 它算错时现象
 * 是 403, 而这里一眼就能看出来是哪一位不对
 */
class LwEdgeSpeechTest {

    /* ── GEC: 用固定时间钉住算法与它的大小写 ───────────────────────────── */

    @Test
    fun `固定时间的 GEC 与外部实现对得上`() {
        assertEquals(
            "6D50DDDB91812C33-DD68EFB01898B0750C9797D51A5FF93471DFDAE6888A1039D532CBDCCE54545D",
            LwEdgeSpeech.gec(1_760_000_000_000L),
        )
    }

    @Test
    fun `同一档 5 分钟里 GEC 不变`() {
        val base = 1_760_000_000_000L
        assertEquals(LwEdgeSpeech.gec(base), LwEdgeSpeech.gec(base + 60_000L))
    }

    /* ── 连接地址: 四样都要在 ───────────────────────────────────────────── */

    @Test
    fun `连接地址带上令牌 门 版本与连接 id`() {
        val url = LwEdgeSpeech.connectionUrl("abc-123", 1_760_000_000_000L)
        assertTrue(url, url.startsWith("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?"))
        assertTrue(url, url.contains("TrustedClientToken=${LwEdgeSpeech.TRUSTED_CLIENT_TOKEN}"))
        assertTrue(url, url.contains("Sec-MS-GEC="))
        assertTrue(url, url.contains("Sec-MS-GEC-Version=2-${LwEdgeSpeech.CHROMIUM_VERSION}"))
        assertTrue(url, url.contains("ConnectionId=abc-123"))
    }

    /* ── 语速与 SSML ───────────────────────────────────────────────────── */

    @Test
    fun `语速换成 SSML 的百分比`() {
        assertEquals("+0%", LwEdgeSpeech.rateArgument(1f))
        assertEquals("+50%", LwEdgeSpeech.rateArgument(1.5f))
        assertEquals("+100%", LwEdgeSpeech.rateArgument(2f))
        assertEquals("-50%", LwEdgeSpeech.rateArgument(0.5f))
        assertEquals("-50%", LwEdgeSpeech.rateArgument(0.1f))
    }

    @Test
    fun `SSML 带上音色 语速与转义过的文本`() {
        val ssml = LwEdgeSpeech.ssml("a<b>&c", "zh-CN-YunxiNeural", 1.25f)
        assertTrue(ssml, ssml.contains("<voice name='zh-CN-YunxiNeural'>"))
        assertTrue(ssml, ssml.contains("rate='+25%'"))
        assertTrue(ssml, ssml.contains("xml:lang='zh-CN'"))
        assertTrue(ssml, ssml.contains("a&lt;b&gt;&amp;c"))
    }

    @Test
    fun `空音色回落到缺省`() {
        assertTrue(LwEdgeSpeech.ssml("hi", "  ", 1f).contains(LwEdgeSpeech.DEFAULT_VOICE))
    }

    /* ── 帧: 只挑 Path 是 audio 的 ─────────────────────────────────────── */

    @Test
    fun `音频帧剥掉头之后就是音频`() {
        val header = "X-RequestId:1\r\nPath:audio\r\n".toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + payload
        assertEquals(payload.toList(), LwEdgeSpeech.audioChunk(frame)?.toList())
    }

    @Test
    fun `元数据帧与坏帧都跳过`() {
        val header = "Path:audio.metadata\r\n".toByteArray(Charsets.US_ASCII)
        val metadata = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + byteArrayOf(9)
        assertNull(LwEdgeSpeech.audioChunk(metadata))
        assertNull(LwEdgeSpeech.audioChunk(byteArrayOf(1)))
        assertNull(LwEdgeSpeech.audioChunk(byteArrayOf(0, 200.toByte(), 1, 2)))
    }

    /* ── 对端主动关闭: 没念完就要算失败, 而且理由要带出来 ───────────────── */

    @Test
    fun `还没念完就对端关闭 报错里带上 code 与理由`() {
        val failure = LwEdgeSpeech.closingFailure(
            1007,
            "Unsupported voice zh-CN-XiaochenNeural.",
            turnEnded = false,
        )
        assertNotNull(failure)
        assertTrue(failure!!, failure.contains("1007"))
        assertTrue(failure, failure.contains("Unsupported voice zh-CN-XiaochenNeural."))
    }

    @Test
    fun `念完之后对端关闭不算失败`() {
        assertNull(LwEdgeSpeech.closingFailure(1000, "bye", turnEnded = true))
    }

    @Test
    fun `关闭理由空着时也要有一句人话`() {
        val failure = LwEdgeSpeech.closingFailure(1006, "  ", turnEnded = false)
        assertTrue("$failure", failure!!.contains("1006"))
        assertTrue("$failure", failure.isNotBlank())
    }
}
