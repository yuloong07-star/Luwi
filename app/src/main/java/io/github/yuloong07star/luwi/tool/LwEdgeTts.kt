package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 「Edge 在线」那条引擎: 用微软 Edge 朗读那条 WebSocket 接口念, **不要密钥**
 *
 * 协议与那几处容易写错的地方都在 [LwEdgeSpeech] (纯算术, 有单测); 这一层只做三件事: 连、收、放。
 * 收齐一段音频之后交给 [LwVoiceClip] —— 与 API 那条共用播放, 于是"停止"与半双工那道闸两边一致
 *
 * 代价如实说: 这是**没有承诺**的免费接口, 它随时可能改协议 (改的时候现象多半是 403 或连上没声音),
 * 所以设置页里它是三条可选引擎中的一条, 而不是唯一
 */
internal object LwEdgeTts {

    /** 现在有没有在放 */
    val speaking: Boolean get() = LwVoiceClip.speaking

    /**
     * 念一段: 成功回 "said …" (与其它引擎同形), 失败回一句人话
     *
     * [context] 只用来找放临时文件的 cache 目录
     */
    fun speak(context: Context, text: String, speed: Float): String {
        val pieces = LwSpeak.chunk(text, LwEdgeSpeech.CHUNK_CHARS)
        if (pieces.isEmpty()) return "there is nothing to say"
        val voice = SpeakSettings.edgeVoice.ifBlank { LwEdgeSpeech.DEFAULT_VOICE }
        val started = System.currentTimeMillis()
        stopped = false
        return try {
            pieces.forEach { piece ->
                if (stopped) return "stopped on request before saying \"${piece.take(SCRIBBLE)}\""
                val audio = synthesize(voice, piece, speed)
                    ?: return "stopped on request while saying \"${piece.take(SCRIBBLE)}\""
                if (!LwVoiceClip.play(context, audio, "mp3")) {
                    return if (stopped) {
                        "stopped on request while saying \"${piece.take(SCRIBBLE)}\""
                    } else {
                        "the audio did not finish playing"
                    }
                }
            }
            "said ${text.length} characters in ${pieces.size} piece(s) with Edge $voice" +
                " in ${System.currentTimeMillis() - started} ms"
        } catch (error: Throwable) {
            Log.w(TAG, "the Edge call failed", error)
            "the Edge call failed: ${error.message ?: error}"
        }
    }

    /**
     * **只合成、不播**: 给填音跟随用 (见 `LwClips.follow`)
     *
     * 与 [speak] 走同一趟握手、同一个音色与语速, 只是收下的字节交回调用方 —— 那一侧把它落成
     * `filesDir/clips` 下的 mp3, 于是"垫一句"与"念回答"在听上就是同一个人
     *
     * 回来 null = 这次被叫停 (调用方按"没合成出来"处理, 保留旧的那份)
     */
    internal fun bytesOf(text: String, speed: Float, voice: String): ByteArray? =
        synthesize(voice, text, speed)

    /** 掐断: 连着的与放着的都要停; 都没在动时回 null */
    fun stop(): String? {
        val held = socket
        if (held == null && !LwVoiceClip.speaking) return null
        stopped = true
        val cut = LwVoiceClip.stop()
        runCatching { held?.cancel() }
        return cut ?: "the Edge call was cut off"
    }

    /**
     * 连一次, 把这一段的音频收齐
     *
     * 本地令牌被服务端拒 (401 / 403) 时**再问一趟官网那个令牌端点**重试一次 —— 微软换算法时, 本地
     * 那份只会一直 403, 远端那一份是"不用更新 APK 也能恢复"的路 (见 [EdgeRemote])
     *
     * 回来 null = 这次是被叫停的 (调用方按"停了"报, 不当作失败)
     */
    private fun synthesize(voice: String, text: String, speed: Float): ByteArray? {
        val local = try {
            collect(voice, text, speed, LwEdgeSpeech.gec(System.currentTimeMillis()), LwEdgeSpeech.SEC_MS_GEC_VERSION, remote = false)
        } catch (error: EdgeRejected) {
            if (error.code != 401 && error.code != 403) throw error
            Log.w(TAG, "the Edge service refused this device's token (${error.code}); asking the token endpoint")
            val remote = (EdgeRemote.valid() ?: EdgeRemote.fetch())
                ?: throw IllegalStateException(
                    "the Edge service refused this device's token (${error.code}) and the token endpoint did not answer",
                )
            collect(voice, text, speed, remote.token, remote.version, remote = true)
        }
        return local
    }

    /** 用一份指定的令牌连一次; 收齐音频或抛出 (被拒 = [EdgeRejected]) */
    private fun collect(
        voice: String,
        text: String,
        speed: Float,
        token: String,
        version: String,
        remote: Boolean,
    ): ByteArray? {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val request = Request.Builder()
            .url(LwEdgeSpeech.connectionUrl(connectionId, System.currentTimeMillis(), token, version))
            .header("Origin", LwEdgeSpeech.ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()
        val audio = ByteArrayOutputStream()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val listener = EdgeListener(
            requestId = requestId,
            ssml = LwEdgeSpeech.ssml(text, voice, speed),
            audio = audio,
            failure = failure,
            finished = finished,
        )
        val webSocket = client.newWebSocket(request, listener)
        socket = webSocket
        try {
            if (!finished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("the service did not finish within ${TIMEOUT_MS} ms")
            }
            if (stopped) return null
            failure.get()?.let { problem ->
                val code = listener.status.get()
                if (code != 0) throw EdgeRejected(code, "the Edge service answered HTTP $code (${problem.message ?: problem})")
                throw problem
            }
            val bytes = synchronized(audio) { audio.toByteArray() }
            if (bytes.isEmpty()) throw IllegalStateException("the service returned no audio")
            EdgeRemote.noteSource(remote)
            return bytes
        } finally {
            socket = null
            runCatching { webSocket.cancel() }
        }
    }

    /**
     * 检测: 用一句两字的话走一趟完整的握手与合成, **只收音频不播放** (2026-10-20)
     *
     * 回执里 `token` 说的是这一趟最后用的是本地还是远端令牌 —— 真机上它同时回答"算法还对不对"与
     * "那个令牌端点通不通"两件事
     */
    internal fun probe(forceRemote: Boolean = false): JsonObject {
        if (LwVoiceClip.speaking || socket != null) {
            return buildJsonObject {
                put("ok", false)
                put("detail", "something is already speaking; stop it first")
            }
        }
        val voice = SpeakSettings.edgeVoice.ifBlank { LwEdgeSpeech.DEFAULT_VOICE }
        val started = System.currentTimeMillis()
        return try {
            val audio = synthesizeSafely(voice, forceRemote)
            buildJsonObject {
                put("ok", audio != null)
                put("ms", System.currentTimeMillis() - started)
                put("bytes", audio?.size ?: 0)
                put("voice", voice)
                put("token", if (EdgeRemote.lastWasRemote) "remote" else "local")
                put("detail", if (audio != null) "the service synthesized this sentence" else "stopped before any audio")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "the Edge probe failed", error)
            buildJsonObject {
                put("ok", false)
                put("ms", System.currentTimeMillis() - started)
                put("voice", voice)
                put("token", if (EdgeRemote.lastWasRemote) "remote" else "local")
                put("detail", error.message ?: error.toString())
            }
        }
    }

    /** 探针用: 这一句不会进播放, 也不该被半双工闸当"在念" */
    private fun synthesizeSafely(voice: String, forceRemote: Boolean): ByteArray? {
        stopped = false
        if (!forceRemote) return synthesize(voice, "嗯", 1f)
        // 只为验证"远端那一份令牌能不能用": 跳过本地, 直接问端点
        val remote = (EdgeRemote.valid() ?: EdgeRemote.fetch())
            ?: throw IllegalStateException("the token endpoint did not answer")
        return collect(voice, "嗯", 1f, remote.token, remote.version, remote = true)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // **只走 HTTP/1.1**: 这条接口不支持 HTTP/2 的扩展 CONNECT —— 那条路上握手会成功, 但之后
            // 一个字节都不回来 (2026-10-06 在真机上就是这么卡住的: 服务端连 ping 的 pong 都不给)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val TAG = "LwEdgeTts"
    private const val SCRIBBLE = 12
    private const val TIMEOUT_MS = 60_000L

    /** 与 [LwEdgeSpeech.CHROMIUM_VERSION] 同一档的浏览器标识 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    /** 正在连的那一条 (停止时要把它掐掉) */
    @Volatile
    private var socket: WebSocket? = null

    /** 这一次念有没有被叫停 */
    @Volatile
    private var stopped = false
}

/** 服务端拒绝的原文最多带出去多少 (它有时候很长, 整段塞进回执没有意义) */
private const val MAX_ERROR_CHARS = 300

/**
 * 一次连接的那几条回调: 收音频、认"念完了"、以及**对端主动关闭**
 *
 * 单独一类就是为了能测 (见 LwEdgeTtsTest): 这条链上最容易漏的恰恰是"服务端自己把关了"那一条, 而它
 * 只走 `onClosing` —— OkHttp 只在**我们自己发起关闭**时才回调 `onClosed`。漏掉它就等于漏掉一条"有
 * 结论"的路, 调用方只能等满那个 60 秒预算, 界面上什么也没有 (2026-10-09 真机上"点试听没声音"的根)
 *
 * [finished] 是"这一次有结论了"的闩: 音频收齐、报错、被关, 三条路都得收它
 */
internal class EdgeListener(
    private val requestId: String,
    private val ssml: String,
    private val audio: ByteArrayOutputStream,
    private val failure: AtomicReference<Throwable?>,
    private val finished: CountDownLatch,
) : WebSocketListener() {

    /** 这一段念完了没有: 服务端拒一个音色时是"turn.start 之后直接关", 而我们自己念完也会主动关 */
    private val ended = AtomicBoolean(false)

    /**
     * 握手被拒时的 HTTP 状态码 (0 = 不是握手那一步失败的)
     *
     * 403 是"令牌不对"的那一条, 也是唯一值得换一份令牌重试的; 其余失败 (网络、超时) 直接报
     */
    val status = AtomicInteger(0)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        // 两条消息的顺序就是协议: 先说输出格式, 再说文本与音色
        webSocket.send(LwEdgeSpeech.configMessage(System.currentTimeMillis()))
        webSocket.send(LwEdgeSpeech.ssmlMessage(requestId, System.currentTimeMillis(), ssml))
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val chunk = LwEdgeSpeech.audioChunk(bytes.toByteArray()) ?: return
        synchronized(audio) { audio.write(chunk) }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        when {
            text.contains("Path:turn.end") -> {
                ended.set(true)
                webSocket.close(1000, null)
                finished.countDown()
            }
            // 服务端的拒绝常常是一条 Path:error 的文本帧, 把它原样带出去比"连上了没声音"强
            text.contains("Path:error") -> {
                failure.set(IllegalStateException(text.take(MAX_ERROR_CHARS)))
                finished.countDown()
            }
        }
    }

    override fun onFailure(webSocket: WebSocket, problem: Throwable, response: Response?) {
        // 握手被拒时 response 不为空 (403 / 401); 记下它, 上面那一层才知道该不该换令牌重试
        response?.let { status.compareAndSet(0, it.code) }
        failure.compareAndSet(null, problem)
        finished.countDown()
    }

    /**
     * 对端主动关闭: **这里必须立刻收闩**, 不能等 `onClosed`
     *
     * 对端关过来时只有这一条 (见 RealWebSocket 的 sendOnClosed), 所以它原来没人接 —— 服务端一句话
     * 拒绝之后, 这条链要干等满 60 秒才报"没念完", 界面上看起来就是"点了试听什么都没有"
     */
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closed(code, reason)
        finished.countDown()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        closed(code, reason)
        finished.countDown()
    }

    /** 关闭算不算失败由 [LwEdgeSpeech.closingFailure] 定: 没念完才算, 理由原样带出去 */
    private fun closed(code: Int, reason: String) {
        LwEdgeSpeech.closingFailure(code, reason, ended.get())
            ?.let { failure.compareAndSet(null, IllegalStateException(it)) }
    }
}

/** 握手被服务端拒 (401 / 403): 带着状态码往上抛, 那一层据此决定要不要换远端令牌重试 */
internal class EdgeRejected(val code: Int, message: String) : IllegalStateException(message)
