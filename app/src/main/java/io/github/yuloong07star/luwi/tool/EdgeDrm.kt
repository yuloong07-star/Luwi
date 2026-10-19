package io.github.yuloong07star.luwi.tool

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 「Edge 在线」那条引擎的门票: `Sec-MS-GEC` 令牌的 **v2** 算法 (2026-10-20 起)
 *
 * 这条接口从 2025 年起要一个 `Sec-MS-GEC` 查询参数; 2026-10 微软把令牌换成 v2:
 * 不再是"时间 + 令牌"的一次 SHA-256, 而是 `CS64-SHA256` 两段, 后面还跟着**改过常数的 SHA-256 变体**
 * (见 `rany2/edge-tts` 的 `drm.py`, 这一份就是照它逐行搬过来的)。算法写错的唯一现象就是 **403**,
 * 而且服务端只说 "403 Forbidden", 所以这里每个中间量都留了能单测的入口
 *
 * 输入是**当前时间取整到 5 分钟** (Windows FILETIME 的 100 ns 计数), 拼上那个公开的客户端令牌:
 *
 * ```text
 * ticks = round_down_300(unix + 11644473600) * 1e7
 * data  = ascii("$ticks") + "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
 * token = cs64(data) + "-" + sha256v2(data)      // 各 16 / 64 位十六进制, 大写
 * ```
 *
 * 为什么不能只发 v1: 服务端按 `Sec-MS-GEC-Version` 选算法, v1 (`1-…`) 现在直接 403; 应用这一侧
 * 跟着上游改成 `2-…` 与 v2 令牌
 *
 * 纯算术, 没有设备也能量 (见 [EdgeDrmTest]); 固定向量是用 edge-tts 7.3.1 在 PC 上复算出来的
 */
internal object EdgeDrm {

    /** 公开的客户端令牌 (在 Edge 自己那个朗读扩展里, 不是用户凭据) */
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** Windows FILETIME 纪元到 Unix 的秒差 */
    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600.0

    private const val MASK = 0xFFFFFFFFL

    /** 标准 SHA-256 的 K, **第 14 个换成了 0x9BDC06A7** (上游就是这么改的, 别"顺手修好") */
    private val ROUND = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )

    /** 第一块里第 14 轮用的那个常数 (上游把它单独换掉) */
    private val BLOCK = ROUND.copyOf().also { it[14] = 0xC9DC06A7L }

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    private val INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL, 0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )

    private val STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL, 0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )

    private val SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L,
        0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L,
    )

    /** CS64 的 14 组系数与偏移 (一对一对排) */
    private val CS64_COEFFICIENTS = longArrayOf(
        0xE82E91B1L, 0xB32700D0L,
        0x44DE24C6L, 0x88DFF51DL,
        0x3BEB65BCL, 0x29514038L,
        0xC89C5933L, 0xB328CBDFL,
        0x8D1EC211L, 0xD405B4D5L,
        0x314EF5D5L, 0x26E55F3CL,
        0xE92491B1L, 0x607801C6L,
        0xF0A5B9D5L, 0x82B2C1FBL,
        0x06064CF3L, 0xFA51A3DAL,
        0xDA79FA60L, 0x207CC464L,
        0xBED20560L, 0x3C3C74F3L,
        0x89F9C9F8L, 0xD2D14B61L,
        0x35FA38C6L, 0xBB04A2C1L,
        0xCB49E3C6L, 0xC5626167L,
    )

    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    /** 现在该带的那个令牌: `CS64-SHA256`, 两段都大写 */
    fun token(epochMillis: Long): String {
        val data = (ticks(epochMillis) + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (low, high) = cs64Digest(data, digest)
        return hexLe(low) + hexLe(high) + "-" + hex(digest)
    }

    /**
     * 时间那一半: 取整到 5 分钟, 换成 100 ns 计数, 再按 Python 的 `f"{ticks:.0f}"` 打出来
     *
     * **必须走 Double**: 那个数超过 2^53, 上游也是用 float 算的; 用整数算会在末几位对不上
     */
    internal fun ticks(epochMillis: Long): String {
        var seconds = epochMillis / 1000.0 + WINDOWS_EPOCH_SECONDS
        seconds -= seconds % 300.0
        return String.format(Locale.US, "%.0f", seconds * 10_000_000.0)
    }

    /** 那个改过常数的 SHA-256 (输出 32 字节, 大端) */
    internal fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "the token salt has to be at least 7 bytes" }
        val length = 57 + data.size
        val buffer = ByteArrayOutputStream()
        buffer.write(data, 7, data.size - 7)
        buffer.write(0x80)
        // Python 的 (55 - length) % 64 永远是正的; Kotlin 的 % 会带符号
        val zeros = (((55 - length) % 64) + 64) % 64
        repeat(zeros) { buffer.write(0) }
        val bits = length.toLong() * 8
        for (shift in 7 downTo 0) buffer.write(((bits ushr (shift * 8)) and 0xFF).toInt())
        val padded = buffer.toByteArray()

        var state = sha256Init(data.copyOfRange(0, 7))
        val words = Array(64) { 0L }
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val at = offset + index * 4
                words[index] = ((padded[at].toLong() and 0xFF) shl 24) or
                    ((padded[at + 1].toLong() and 0xFF) shl 16) or
                    ((padded[at + 2].toLong() and 0xFF) shl 8) or
                    (padded[at + 3].toLong() and 0xFF)
            }
            expand(words, 16)
            val working = rounds(state, words, 0)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        val digest = ByteArray(32)
        for (index in 0 until 8) {
            for (byte in 0 until 4) {
                digest[index * 4 + byte] = ((state[index] ushr ((3 - byte) * 8)) and 0xFF).toByte()
            }
        }
        return digest
    }

    /** CS64 的那两个 32 位字 (小端的那一半看 [hexLe]) */
    internal fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val keyA = (leU32(digest, 0) or 1L)
        val keyB = (leU32(digest, 4) or 1L)
        val factors = longArrayOf((keyA * CS64_FACTOR) and MASK, (keyB * CS64_FACTOR) and MASK)

        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        var index = 0
        while (index < CS64_COEFFICIENTS.size) {
            terms += (CS64_COEFFICIENTS[index] * factors[(index / 2) % 2]) and MASK
            offsets += CS64_COEFFICIENTS[index + 1]
            index += 2
        }
        val payload = ByteArray(data.size + 1)
        System.arraycopy(data, 0, payload, 1, data.size)
        wordsLe4Even(payload).forEachIndexed { at, word ->
            val even = at % 2 == 0
            var term = (word * (if (even) keyA else keyB)) and MASK
            var offset = (rol16(word) * (if (even) 0xEF0569FBL else 0xC31713DBL)) and MASK
            if (at == 0) {
                term = (term + CS64_ADJUSTMENT0 * factors[0]) and MASK
                offset = (offset + CS64_ADJUSTMENT1) and MASK
            }
            terms += term
            offsets += offset
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16((offsets[index] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and MASK)) and MASK
            t = rol16((offsets[index + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc = (acc + state) and MASK

            var next = (state * keyB + y) and MASK
            next = (rol16(next) * 0xA27416F5L) and MASK
            next = (rol16(next) * 0xD38396FFL) and MASK
            state = (rol16((rol16(next) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            index += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    /** 与上游那个"从数据前 7 字节起算"的初始状态一致 */
    private fun sha256Init(salt: ByteArray): LongArray {
        // 上游的 words 一开始就是 16 项 (0..15), 再往 16..29 追加 —— 这里不能先开 30 项
        val words = MutableList(16) { 0L }
        words[14] = bigEndian(salt, 0, 3)
        words[15] = bigEndian(salt, 3, 4)
        for (index in 16..29) {
            var value = SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    smallSigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    smallSigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    smallSigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    smallSigma0(words[index - 15])
                }
            }
            words += value and MASK
        }
        words += (smallSigma1(words[28]) + words[23] + smallSigma0(words[15]) + words[14] +
            SHA256_ADJUSTMENT - ROUND[14]) and MASK
        val expanded = Array(64) { 0L }
        words.forEachIndexed { at, value -> expanded[at] = value }
        expand(expanded, 31)
        val working = rounds(STATE, expanded, 14)
        return LongArray(8) { (INITIAL[it] + working[it]) and MASK }
    }

    /** 标准的 W 扩展, 但 sigma 的中间值不先掩码 (上游的写法, 结果等价) */
    private fun expand(words: Array<Long>, first: Int) {
        for (index in first until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            words[index] = (smallSigma1(x) + words[index - 7] + smallSigma0(y) + words[index - 16]) and MASK
        }
    }

    /** 64 轮; [firstRound] 只被 14 那一处用 (第一块从第 14 轮起接) */
    private fun rounds(state: LongArray, words: Array<Long>, firstRound: Int): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) BLOCK else ROUND
        for (index in firstRound until 64) {
            val t1 = (h + bigSigma1(e) + (g xor (e and (f xor g))) + constants[index] + words[index]) and MASK
            val t2 = (bigSigma0(a) + ((a and b) or (c and (a or b)))) and MASK
            h = g
            g = f
            f = e
            e = (d + t1) and MASK
            d = c
            c = b
            b = a
            a = (t1 + t2) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun bigSigma0(value: Long): Long = rotr(value, 2) xor rotr(value, 13) xor rotr(value, 22)
    private fun bigSigma1(value: Long): Long = rotr(value, 6) xor rotr(value, 11) xor rotr(value, 25)

    private fun smallSigma0(value: Long): Long =
        ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor (value shl 14) xor (value ushr 3)) and MASK

    private fun smallSigma1(value: Long): Long =
        ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor (value shl 13) xor (value ushr 10)) and MASK

    private fun rotr(value: Long, bits: Int): Long =
        ((value ushr bits) or (value shl (32 - bits))) and MASK

    private fun rol16(value: Long): Long = ((value and MASK) shl 16) or ((value and MASK) ushr 16)

    private fun wordsLe4Even(data: ByteArray): List<Long> {
        val padded = if (data.size % 4 == 0) data else data + ByteArray(4 - data.size % 4)
        if (padded.isEmpty()) return emptyList()
        val words = ArrayList<Long>(padded.size / 4)
        var index = 0
        while (index < padded.size) {
            words += (padded[index].toLong() and 0xFF) or
                ((padded[index + 1].toLong() and 0xFF) shl 8) or
                ((padded[index + 2].toLong() and 0xFF) shl 16) or
                ((padded[index + 3].toLong() and 0xFF) shl 24)
            index += 4
        }
        if (words.size % 2 == 1) words += 0L
        return words
    }

    private fun bigEndian(data: ByteArray, from: Int, count: Int): Long {
        var value = 0L
        for (index in 0 until count) value = (value shl 8) or (data[from + index].toLong() and 0xFF)
        return value
    }

    private fun leU32(data: ByteArray, from: Int): Long =
        (data[from].toLong() and 0xFF) or
            ((data[from + 1].toLong() and 0xFF) shl 8) or
            ((data[from + 2].toLong() and 0xFF) shl 16) or
            ((data[from + 3].toLong() and 0xFF) shl 24)

    /** 大写十六进制 (与上游的 `.hex().upper()` 一样) */
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    /** 一个 32 位字按小端排出来的 8 个十六进制字符 (`struct.pack("<I")`) */
    private fun hexLe(word: Long): String = buildString(8) {
        for (shift in 0..24 step 8) {
            val byte = (word ushr shift) and 0xFF
            append(HEX[(byte ushr 4).toInt()])
            append(HEX[(byte and 0x0F).toInt()])
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
