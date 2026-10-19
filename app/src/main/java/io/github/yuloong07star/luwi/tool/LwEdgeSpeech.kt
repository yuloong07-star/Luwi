package io.github.yuloong07star.luwi.tool
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「Edge 在线」那条引擎的纯算术: GEC 令牌、连接地址、SSML、时间戳、以及从二进制帧里挑出音频
 *
 * 这条引擎是**免费的**: 用微软 Edge 朗读那条 WebSocket 接口, 不要密钥、不要账号, 代价是它随时可能改
 * 协议 (2025 年起它拿 `Sec-MS-GEC` 当门: 2026-10 起那个令牌是 v2 (`CS64-SHA256`), 算法在 [EdgeDrm])。
 *
 * 协议是从 `rany2/edge-tts` 的 Python 实现核出来的 (2026-10): 先发一条 `Path:speech.config` 说输出
 * 格式, 再发一条 `Path:ssml` 说文本与音色, 然后收若干二进制帧 (前两字节是大端头长度, 头里有
 * `Path:audio`), 直到文本帧 `Path:turn.end` 为止
 *
 * 这些都是"不开设备也能量"的东西, 所以单测 (见 LwEdgeSpeechTest) 把它们钉住 —— 真正难查的是
 * "连上了但没有声音", 而原因常常是这里的某一条少了或写错了
 */
internal object LwEdgeSpeech {

    /** Edge 朗读接口固定的客户端令牌 (公开在自己那个扩展里, 不是用户的凭据) */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** 服务端会看这个版本号; 跟不上的时候报的是 403, 所以它要跟上游一起走 */
    const val CHROMIUM_VERSION = "143.0.3650.75"

    /**
     * `Sec-MS-GEC-Version` 的前缀是 **2** (2026-10-20 改): 上游 edge-tts 7.3.1 起令牌换成 v2, 服务端
     * 按这个前缀挑算法 —— 还发 `1-…` 的话握手直接 403
     */
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_VERSION"

    const val ENDPOINT = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    /** 输出格式: 24 kHz 48 kbps 单声道 mp3, MediaPlayer 直接认 */
    const val AUDIO_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /** 一段最多几个字: 这条接口一次给整段音频, 切一段短一点让"停"有粒度 */
    const val CHUNK_CHARS = 300

    /**
     * 设置页列出来的中文音色
     *
     * 接口那边有几百条 (含方言与多语言), 这里只给常用的八个; 设置页的"音色"一行还要能自己填名字,
     * 所以清单只当快捷方式, 不当白名单
     *
     * **这份清单会过期, 而它会以"某个音色念不出来"的形式过期**: 2026-10-09 真机上试听没声音那次,
     * 就是微软把「晓辰」下线了 —— 服务端收下 SSML 之后直接 close 1007 `Unsupported voice ...`, 而这里
     * 还留着它, 于是手机上那个存着的音色一直念不出来。改这份清单要对着接口那一条
     * `/voices/list?trustedclienttoken=...` 核一遍 (见 [closingFailure])
     */
    val VOICES = listOf(
        "zh-CN-XiaoxiaoNeural" to "晓晓 (女声, 通用)",
        "zh-CN-XiaoyiNeural" to "晓伊 (女声, 活泼)",
        "zh-CN-YunxiNeural" to "云希 (男声, 年轻)",
        "zh-CN-YunyangNeural" to "云扬 (男声, 播报)",
        "zh-CN-YunjianNeural" to "云健 (男声, 沉稳)",
        "zh-CN-YunxiaNeural" to "云夏 (少年, 轻松)",
        "zh-CN-liaoning-XiaobeiNeural" to "小北 (东北话)",
        "zh-CN-shaanxi-XiaoniNeural" to "小妮 (陕西话)",
    )

    /**
     * 对端主动关闭时该报什么: 没等到 `turn.end` 就算失败, 把 close 的 code 与理由原样带出去
     *
     * 为什么非有这一条不可: OkHttp 只在**我们自己发起关闭**时才回调 `onClosed`, 对端主动关只给
     * `onClosing` —— 而这条接口拒绝一个音色时正是那个样子 (握手成功, `Path:turn.start` 之后紧跟一条
     * close: 1007 `Unsupported voice zh-CN-XiaochenNeural.`)。那一处原来没人接, 于是只能干等满 60 秒
     * 预算, 界面上一片安静
     *
     * [turnEnded] 那一位不能省: 正常念完时是**我们自己** `close(1000)`, 对端回过来的那条 close 不能再
     * 被当成失败
     */
    fun closingFailure(code: Int, reason: String, turnEnded: Boolean): String? {
        if (turnEnded) return null
        val why = reason.trim()
        return "the service closed the connection (code $code)" + if (why.isEmpty()) "" else ": $why"
    }

    /**
     * 那个门 (`Sec-MS-GEC`): 当前时间取整到 5 分钟、换成 Windows 的 100 ns 计数, 拼上客户端令牌做
     * **v2 令牌** (`CS64-SHA256`, 算法与常数在 [EdgeDrm])
     *
     * 2026-10 之前这里是普通 SHA-256 的十六进制; 微软换了算法之后那一种只换来 403 (见 [EdgeDrm])
     */
    fun gec(epochMillis: Long): String = EdgeDrm.token(epochMillis)

    /** 连一次的地址: 令牌、门、版本号与这次连接的 id 都在查询串里 */
    fun connectionUrl(
        connectionId: String,
        epochMillis: Long,
        token: String = gec(epochMillis),
        version: String = SEC_MS_GEC_VERSION,
    ): String =
        "$ENDPOINT?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$token" +
            "&Sec-MS-GEC-Version=$version" +
            "&ConnectionId=$connectionId"

    /** JS 那种时间戳 (这条接口就是这么写的, 与服务端对得上就行) */
    fun timestamp(epochMillis: Long): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    /** 语速 → SSML 的百分比: 应用的 0.5..2.0 正好是 -50%..+100%, 再外面夹住 */
    fun rateArgument(speed: Float): String {
        val percent = ((speed - 1f) * 100f).roundToInt().coerceIn(-50, 100)
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    /** 一条 SSML: 音色 + 语速 + 文本 (文本要转义, 不然文本里的尖括号会把 SSML 撕开) */
    fun ssml(text: String, voice: String, speed: Float): String {
        val name = voice.ifBlank { DEFAULT_VOICE }
        val language = name.split('-').take(2).joinToString("-").ifBlank { "zh-CN" }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$language'>" +
            "<voice name='$name'>" +
            "<prosody pitch='+0Hz' rate='${rateArgument(speed)}' volume='+0%'>" +
            escape(text) +
            "</prosody></voice></speak>"
    }

    /** 第一条消息的正文: 告诉服务端要 mp3、不要句界与词界元数据 */
    fun speechConfig(): String =
        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"" +
            "},\"outputFormat\":\"$AUDIO_FORMAT\"}}}}"

    /** speech.config 那条消息 (头 + 正文) */
    fun configMessage(epochMillis: Long): String =
        "X-Timestamp:${timestamp(epochMillis)}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            speechConfig()

    /** ssml 那条消息; 时间戳尾巴上那个 `Z` 是上游的实现里跟着的 (注释里写明"不是笔误") */
    fun ssmlMessage(requestId: String, epochMillis: Long, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp(epochMillis)}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

    /**
     * 从一条二进制帧里挑出音频
     *
     * 帧的结构是: 两字节大端头长度 + 头 (文本, `Path:` 那几行) + 音频字节。元数据帧 (Path 是
     * `audio.metadata`) 与太短的帧都回 null, 调用方据此跳过
     */
    fun audioChunk(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val offset = headerLength + 2
        if (offset > frame.size) return null
        val path = String(frame, 2, headerLength, Charsets.US_ASCII)
            .lineSequence()
            .firstOrNull { it.startsWith("Path:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        if (!path.equals("audio", ignoreCase = true)) return null
        return frame.copyOfRange(offset, frame.size)
    }

    private val DATE_FORMAT = DateTimeFormatter
        .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .withZone(ZoneOffset.UTC)

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
