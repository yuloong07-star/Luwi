package io.github.yuloong07star.luwi.tool

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edge 那条接口的 v2 门令牌 (`Sec-MS-GEC`): 固定向量全部是用 **edge-tts 7.3.1** 在 PC 上复算出来的
 *
 * 这一份值为什么值得单测: 算法写错时服务端只回一句 403, 手机上什么都看不到; 而这一段算术里有
 * 改过的 SHA-256 常数、CS64 的多轮乘法与"用 Double 打时间戳"三个容易搬错的地方, 任何一处错一位,
 * 令牌就是另一串十六进制。固定向量能把它钉在原地
 */
class EdgeDrmTest {

    @Test
    fun `时间那一半按 Double 打出来`() {
        // 这个数超过 2^53, 用整数算末几位会对不上
        assertEquals("134369040000000000", EdgeDrm.ticks(1_792_430_400_000L))
        assertEquals("134044734000000000", EdgeDrm.ticks(1_760_000_000_000L))
    }

    @Test
    fun `v2 令牌与 edge-tts 7_3_1 对得上`() {
        assertEquals(
            "6D50DDDB91812C33-DD68EFB01898B0750C9797D51A5FF93471DFDAE6888A1039D532CBDCCE54545D",
            EdgeDrm.token(1_760_000_000_000L),
        )
        assertEquals(
            "418070A1C11CF24E-4BB44A5B421D5A6EF2605938C3F376A87B27911420EB13032DC8FE87E51A0353",
            EdgeDrm.token(1_792_430_400_000L),
        )
        assertEquals(
            "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            EdgeDrm.token(0L),
        )
    }

    @Test
    fun `同一档 5 分钟里令牌不变`() {
        val base = 1_760_000_000_000L
        assertEquals(EdgeDrm.token(base), EdgeDrm.token(base + 60_000L))
        // base 落在那一档的第 200 秒, 再加 99 秒还在同一个 5 分钟窗口里 (加 299 秒会跨到下一档)
        assertEquals(EdgeDrm.token(base), EdgeDrm.token(base + 99_000L))
    }

    @Test
    fun `中间量也对得上`() {
        val data = (EdgeDrm.ticks(1_792_430_400_000L) + "6A5AA1D4EAFF4E9FB37E23D68491D6F4")
            .toByteArray(Charsets.US_ASCII)
        val digest = EdgeDrm.sha256Digest(data)
        assertEquals(
            "4BB44A5B421D5A6EF2605938C3F376A87B27911420EB13032DC8FE87E51A0353",
            digest.joinToString("") { "%02X".format(it) },
        )
        // CS64 那两个 32 位字在令牌里按小端排: 418070A1C11CF24E, 直接由 token 那一条钉住
    }
}
