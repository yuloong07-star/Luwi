package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.voice.VoiceState
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本机朗读: 把一段文字交给系统语音合成念出来
 *
 * 为什么是这一条: 它是这台手机上唯一不需要密钥、不需要联网的语音输出 —— 引擎是 ROM 自带的
 * (vivo 的 AIService / AiAgent 声明了 `android.intent.action.TTS_SERVICE`, 见
 * AndroidManifest.xml 里 queries 那一条: Android 11 起不声明, 系统引擎对本应用就是不可见的),
 * 而网络那几家 (edge-tts、MiMo TTS) 要么走别人的服务, 要么要密钥
 *
 * 四个动作: `status` 看引擎与中文音色在不在, `speak` 念一段, `stop` 掐断正在念的, `release`
 * 把引擎还回去 (初始化要几百毫秒, 所以平时留着复用)
 */
internal object LwSpeak {

    /** 引擎初始化与一次朗读各自的上限; 到点就说没念完, 不无限等 */
    private const val INIT_BUDGET_MS = 8_000L
    private const val SPEAK_BUDGET_MS = 15_000L
    private const val SPEAK_BUDGET_PER_CHAR_MS = 250L
    private const val MAX_WAIT_MS = 120_000L

    /** 设置页最多列几个中文音色: 有的引擎能列出几十个, 全铺开会把这一页撑得没法看 */
    private const val MAX_VOICES = 8

    private const val TAG = "LwSpeak"

    /** 正在等的那一条: 只有它的完成/出错才算这一次念完了 */
    private class Utterance(val id: String, val latch: CountDownLatch)

    private val lock = Any()
    private var engine: TextToSpeech? = null

    @Volatile
    private var current: Utterance? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var spoken: Int = 0

    /**
     * 每念一句一个代际号 (T14): 异步念的时候后一声 (`interrupt` 那条会 QUEUE_FLUSH) 把前一声的
     * "放开半双工闸 / 写尾巴预算 / 清 current" 全部作废 —— 否则前一声等满预算回来时, 会把后一声
     * 那笔账清掉 (麦克风提前开, 球上也不再写「正在说」)
     */
    private val speakingRun = AtomicInteger(0)

    /** 异步那一档起过几趟 (读数用) */
    private val asyncRuns = AtomicInteger(0)

    /** Edge 落到系统朗读的次数 (读数用): 数字涨了就是"那条在线引擎这段时间不可用" */
    private val edgeFallbacks = AtomicInteger(0)

    /**
     * 上一次念的是不是**系统那条**引擎
     *
     * 只有它需要"预算窗 + 专属属性"那一条 (播放器在引擎进程里, 看不到); 自带与在线那两条的播放器就在
     * 本进程, 它们说停就是停 —— 拿系统那条的判据往它们身上套, 只会白留一段"可能还在响"的窗
     */
    @Volatile
    private var lastWasSystem: Boolean = false

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context)
        "speak" -> if (request.bool("async", false)) speakAsync(context, request) else speak(context, request)
        // 预录填音 (2.7.9 批次 9 T13): 放 assets/clips/<clip>.ogg, 不走引擎、不联网、不等播完
        "clip" -> clip(context, request)
        "stop" -> stop()
        "release" -> release()
        // 只读探针: 系统引擎到底把声音放在哪种属性上 (判据换不换得成, 靠它一句话定), 见 [probe]
        "probe" -> probe(context)
        // Edge 那条的体检 (2026-10-20): 走一趟完整握手与合成, 只收不播; 回执说清用的是本地还是远端令牌
        "edge" -> LwEdgeTts.probe(request.bool("remote", false))
        else -> throw IllegalArgumentException(
            "op has to be status, speak, clip, stop, release, probe or edge, not \"$op\"",
        )
    }

    /**
     * 放一段预录填音 (T13)
     *
     * 这一条**故意不碰** [VoiceState.spokeUntil] 与 [talking]: 填音只有一两秒, 而 `talking` 是"说的时候
     * 把麦真关掉"的判据 —— 每响一声关一次麦, 再开一次, 就是那个插件 README 里写的"麦克风反复开关"。
     * 半双工只靠 [LwClips] 里那段 `VoiceState.speaking`
     */
    private fun clip(context: Context, request: JsonObject): JsonObject {
        val name = request.stringOrNull("clip") ?: request.stringOrNull("name") ?: ""
        // 音量是 0..1 的线性增益 (与 TTS 那条 100 = 原电平的百分数不是同一个刻度): 填音不调它时就是 1
        val volume = (request.numberOrNull("volume")?.toFloat() ?: 1f).coerceIn(0f, 1f)
        return LwClips.play(context, name, volume)
    }

    /**
     * 系统引擎"我还在不在出声"这条判据的现场读数 (T0 探针, **不说话、不碰麦克风**)
     *
     * 量法: 另开一条连接叫一次 `op=speak`, 念的整段里反复叫这一条 —— `matched` 一直是 0 就说明这台 ROM
     * 的引擎不吃我们给的属性 (那时判据退化成"只按预算", 见 [talking]), 全过程 ≥1 才是换对了
     */
    private fun probe(context: Context): JsonObject = buildJsonObject {
        val tts = runCatching { engine(context) }.getOrNull()
        put("ours", SystemSpeech.describe())
        put("matched", SystemSpeech.matches(context))
        put("active", SystemSpeech.snapshot(context).joinToString(" | "))
        // 老判据那一条也报出来: 一眼看得出"别人的音乐"与"我们的那一份"在这一刻各是什么
        val audio = context.getSystemService(AudioManager::class.java)
        put("musicActive", runCatching { audio?.isMusicActive == true }.getOrDefault(false))
        // 平台只给了 `setAudioAttributes`, 没有公开的读回口子 (2026-10-16 编译期量出来的, 上一批
        // 这里想读 `tts.audioAttributes` 编不过): 照实报"读不到", 引擎吃没吃我们那一份还是靠
        // matched 那一个数 (活跃播放里 ours() 命中几条) 判
        put("engineAttributes", "not readable: the platform exposes no getter for engine audio attributes")
        put("lastWasSystem", lastWasSystem)
        put("tailUntil", VoiceState.spokeUntil)
        put("speakingNow", speakingNow)
    }

    /** 引擎现在什么样: 有没有引擎、默认是哪一个、中文音色能不能用、以及这一侧选了什么 */
    private fun status(context: Context): JsonObject {
        val attempt = runCatching { engine(context) }
        val tts = attempt.getOrNull()
        val chinese = tts?.let { runCatching { it.setLanguage(Locale.CHINESE) }.getOrElse { -99 } }
        val voices = tts?.let { runCatching { it.voices }.getOrNull() }
        return buildJsonObject {
            put("engine", tts?.defaultEngine ?: "unavailable")
            put("voices", voices?.size ?: 0)
            put(
                "chineseVoices",
                chineseVoices(voices)
                    ?.joinToString(", ") { it.name }
                    .orEmpty(),
            )
            // 设置页里选的那两样: 语速跟不跟随系统、以及选了哪个音色
            put("rateFollowsSystem", SpeakSettings.followsSystem)
            put("rate", SpeakSettings.rate.toDouble())
            // 音量 (百分数, 100 = 模型自己的电平)。**只有自带那条引擎吃得到** —— 系统那条与两条在线
            // 引擎的音量都在它们自己手里 (在线那两条拿回来的是编码过的音频, 应用不解码就没有增益可加),
            // 所以下面还会直说一句它管不到哪里
            put("volume", SpeakSettings.volume.toDouble())
            put("volumeRange", "${SpeakSettings.volumeRange.start.toInt()}..${SpeakSettings.volumeRange.endInclusive.toInt()}")
            put("volumeAppliesTo", "on-device")
            put("selectedVoice", SpeakSettings.voice ?: "the engine's own default")
            // 朗读用的是哪条引擎: 四条都报出来 (自带那条的音色是主人自己放进来的目录; 两条在线引擎
            // 的音色在它们各自的服务那一侧)
            // **键名不能叫 engine**: 那个键上面已经用来报系统引擎的包名了
            put("readingWith", readingWith())
            // 自动念那条链在宿主那侧, 所以这个开关必须报出去 —— 它据此决定要不要念
            put("readAloud", SpeakSettings.readAloud)
            put("onDeviceModel", SpeakSettings.model ?: "")
            put("edgeVoice", SpeakSettings.edgeVoice)
            // API 那条: 地址与模型/音色照报, **密钥只报"有没有"** (它不该出现在任何回执或日志里)
            put("apiUrl", SpeakSettings.apiUrl)
            put("apiKeySet", SpeakSettings.apiKey.isNotBlank())
            put("apiModel", SpeakSettings.apiModel)
            put("apiVoice", SpeakSettings.apiVoice)
            put(
                "onDeviceVoices",
                LwTts.list(context).joinToString(", ") { voice ->
                    if (voice.usable) "${voice.name} (${voice.family?.label})" else "${voice.name} (unusable: ${voice.problem})"
                },
            )
            put("voicesDirectory", LwTts.root(context).absolutePath)
            put(
                "chinese",
                when (chinese) {
                    TextToSpeech.LANG_AVAILABLE -> "available"
                    TextToSpeech.LANG_COUNTRY_AVAILABLE -> "available (country)"
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "available (variant)"
                    TextToSpeech.LANG_MISSING_DATA -> "missing data: the engine still has to download its Chinese voice"
                    TextToSpeech.LANG_NOT_SUPPORTED -> "not supported by this engine"
                    null -> "unknown: the engine did not come up (${attempt.exceptionOrNull()?.message})"
                    else -> "unknown ($chinese)"
                },
            )
            // 三条引擎的播放标记**都要算上** (2026-10-16 修: 这一条原来漏了自带那条 `LwTts.speaking`,
            // 与 [speakingNow] 报的不是同一件事 —— 用自带音色念时 status 会答"没在说")
            put("speaking", speakingNow)
            // 半双工那道闸的**真实值**: 填音那一段它与 speaking 故意不同 (clip 会合上它, 但不进
            // [talking], 见 `op=clip`), 所以两个都报出来, 免得"闸合没合上"只能靠猜
            put("halfDuplex", VoiceState.speaking)
            // 异步那一档 (T14): 起过几趟。**在不在响看 speaking 那一个**, 这里只是"这一档用没用过"
            put("asyncStarts", asyncRuns.get())
            // Edge 那条体检的读数 (2026-10-20): 落到系统朗读几次、上一趟用的是本地还是远端令牌
            put("edgeFallbacks", edgeFallbacks.get())
            put("edgeTokenSource", if (EdgeRemote.lastWasRemote) "remote" else "local")
            put("utterances", spoken)
            // 「还在出声吗」这条判据自己的读数 (2026-10-16): 尾巴预算什么时候到点、上一次念的是不是系统
            // 那条、以及**活跃播放里有几条带的是我们给引擎那份属性** (0 = 判据退化成"只按预算")
            put("tailUntil", VoiceState.spokeUntil)
            put("lastWasSystem", lastWasSystem)
            put("systemMatches", SystemSpeech.matches(context))
            put("systemOurs", SystemSpeech.describe())
            // 预录填音 (T13): 带的是哪几段、现在响没响。**顺手把 SoundPool 预载好** —— 插件那侧开工前
            // 会先叫一次 status 把引擎拉起来, 填音也走同一条预热, 于是第一声也是几十毫秒
            runCatching { LwClips.warm(context) }
            put("clips", LwClips.names(context).joinToString(", "))
            put("clipPlaying", LwClips.playingNow())
        }
    }

    /**
     * 设置页要的那几个中文音色
     *
     * 只给中文的: 这个应用念的一律是中文回答, 把英文音色列出来只会让人选错。引擎没起来时回 null
     * (调用方据此说"引擎还没就绪"而不是"没有音色")
     */
    internal fun chineseVoices(context: Context): List<Voice>? =
        runCatching { chineseVoices(engine(context)?.voices) }.getOrNull()

    /**
     * 一个音色在设置里的身份
     *
     * **不能只用 `Voice.name`**: 这台设备的引擎把三个中文音色都叫 `zh` (只差 locale), 只用名字的话
     * 三个都会显示"正在用", 选了也分不出是哪一个 (2026-10-05 在真机上就是这么露出来的)。所以名字
     * 与地区一起存
     */
    internal fun voiceKey(voice: Voice): String = "${voice.name}@${voice.locale.toLanguageTag()}"

    private fun chineseVoices(voices: Set<Voice>?): List<Voice>? =
        voices
            ?.filter { it.locale.language == "zho" || it.locale.language == "zh" }
            ?.sortedBy { it.name }
            ?.take(MAX_VOICES)

    /** 设置页那个「试听」: 用当前设置念一句, 让人当场听见音色与语速 */
    internal fun preview(context: Context): JsonObject =
        speak(context, buildJsonObject { put("op", "speak"); put("text", context.getString(R.string.settings_speak_sample)) })

    /**
     * 试听那一条的**人话**: 念成了回 null, 没念成回一句原因
     *
     * 设置页要的不是字段而是"要不要说一句、说什么", 所以这一步收在这里 —— 页面原来把整份回执丢掉,
     * 于是在线那两条念不出来时界面上一片安静 (2026-10-09 真机上"点试听没声音"就是这一处: 服务端因为
     * 它不认的那个音色当场关了 WebSocket, 而唯一写着原因的地方就是这里的 detail)
     */
    internal fun spokenProblem(answer: JsonObject): String? =
        if (answer.bool("spoken", false)) null else answer.stringOrNull("detail") ?: "it did not say anything"

    /** 念一段: 太长就按句切, 只等最后一片念完 */
    private fun speak(context: Context, request: JsonObject): JsonObject {
        val text = request.string("text").trim()
        if (text.isEmpty()) {
            unavailable("speaking", "the text is empty")
        }
        // **半双工那道闸对三条引擎都得合上** (2026-10-09 真机上踩的这条): 原来只有系统那条 (下面那个
        // try/finally) 与自带那条 (`LwTts` 自己置) 会写 [VoiceState.speaking], 而**在线那两条
        // (Edge / API) 一个字节都没置** —— 于是用在线引擎念回答时麦克风照开着, 喇叭里念的那句被录
        // 回去、再当成"主人说的一句话"投进会话 (主人那条: "把她自己的说录入听里去了"), 球上也就显示
        // 成「正在听」而不是「正在念」。现在整段调用都在闸里, 三条引擎一样
        //
        // 包住的是**整段**: 下面的 `unavailable` 会抛, 那时标记也必须放回去, 不然麦克风一直关着
        //
        // **代际号是异步那一档 (T14) 的必要条件**: 异步念的时候主人又叫了一声 (`interrupt` 那条会
        // QUEUE_FLUSH), 前一趟得等满自己的预算才返回 —— 它返回时**不许**把后一声那笔闸清掉, 否则
        // 后一声还在响, 麦克风却已经开了
        val run = speakingRun.incrementAndGet()
        VoiceState.speaking = true
        // 新的一句开始: 上一句那份"可能还在响"的预算作废 (这一句结束时会重新写, 见 [talking])
        VoiceState.spokeUntil = 0L
        try {
            return speakWith(context, request, text, run)
        } finally {
            if (run == speakingRun.get()) VoiceState.speaking = false
        }
    }

    /**
     * 异步念 (T14): 起播就返回, 在不在响由 `op=status` 的 `speakingNow` 答
     *
     * 为什么要有这一档: `speak` 原来要等播完 (`budget` = 15 s + 250 ms/字, 201 字 ≈ 65 s), 一次长
     * 回答把通道占住, 而通道是串行的 —— 那个插件只好去抢内置朗读 (施工单 §8.2)。异步这一档让调用方
     * "交出去就走", 停下照旧走 `op=stop`
     *
     * **它不是默认**: 现有调用方靠 `speak` 的返回值判"念完了没有", 所以异步只能是一个新档
     */
    private fun speakAsync(context: Context, request: JsonObject): JsonObject {
        val text = request.string("text").trim()
        if (text.isEmpty()) {
            unavailable("speaking", "the text is empty")
        }
        val runs = asyncRuns.incrementAndGet()
        val worker = Thread({
            try {
                speak(context, request)
            } catch (error: Throwable) {
                Log.w(TAG, "the asynchronous utterance failed", error)
            }
        }, "lw-speak-async-$runs")
        // 不挡进程退出: 一句念到一半而应用被收掉, 不需要靠它把进程钉着
        worker.isDaemon = true
        worker.start()
        return buildJsonObject {
            put("accepted", true)
            put("started", true)
            put("async", true)
            put("readingWith", readingWith())
            put("characters", text.length)
            put("detail", "started in the background; op=status says speakingNow until it ends, op=stop cuts it")
        }
    }

    /** 这一侧会拿哪条引擎念 (只看设置, 不初始化任何引擎) —— 异步回执与 status 共用这一份 */
    private fun readingWith(): String = when {
        SpeakSettings.usesOnDevice() -> "on-device"
        SpeakSettings.usesEdge() -> "edge"
        SpeakSettings.engine == SpeakSettings.Engine.API -> "api"
        else -> "system"
    }

    /** [speak] 的真身: 三条引擎各自怎么念 (闸在上面那一层合, 这里只管念) */
    private fun speakWith(context: Context, request: JsonObject, text: String, run: Int): JsonObject {
        // 自带那条先问: 设置页选了它、而且真的挑了一个音色目录, 就整段交给它 (它自己按句切)
        if (SpeakSettings.usesOnDevice()) {
            val wanted = SpeakSettings.model
            val voice = LwTts.list(context).firstOrNull { it.name == wanted }
            if (voice == null) {
                unavailable(
                    "speaking with the on-device voice \"$wanted\"",
                    "that voice is not in ${LwTts.root(context).absolutePath} any more; import one or switch back to the system engine",
                )
            }
            if (!voice.usable) {
                unavailable("speaking with the on-device voice \"$voice.name\"", voice.problem.orEmpty())
            }
            val asked = request.numberOrNull("rate")?.toFloat()
            val speed = (asked ?: SpeakSettings.rate).coerceIn(0.5f, 2.0f)
            // 音量: 这次调用点名的 > 设置页里选的。**只有自带这条吃得到** (系统那条的增益握在引擎
            // 自己手里, 见下面 status 里那句), 所以这个数在这里才是有用的
            val wantedVolume = request.numberOrNull("volume")?.toFloat() ?: SpeakSettings.volume
            val volume = wantedVolume.coerceIn(SpeakSettings.volumeRange.start, SpeakSettings.volumeRange.endInclusive)
            val answer = LwTts.speak(context, voice, text, speed, volume / SpeakSettings.VOLUME_UNITY)
            val failed = !answer.startsWith("said ")
            // 自带那条的播放器就在本进程, 它说停就是停 —— [talking] 认它的播放标记, 不认这段预算
            // (这一笔只是给 `status`/`probe` 看的现场)
            if (!failed) stampTail(run, text, system = false)
            return buildJsonObject {
                put("spoken", !failed)
                put("readingWith", "on-device")
                put("voice", voice.name)
                put("rate", speed.toDouble())
                put("volume", volume.toDouble())
                put("text", text)
                put("characters", text.length)
                put("detail", answer)
            }
        }
        // 在线那两条: 语音在服务那一侧, 所以**音量那一条对它们不成立** (拿回来的是编码过的音频) ——
        // 点名要了音量就必须如实说没生效, 而不是静默丢掉
        if (SpeakSettings.usesEdge() || SpeakSettings.engine == SpeakSettings.Engine.API) {
            val asked = request.numberOrNull("rate")?.toFloat()
            val speed = (asked ?: SpeakSettings.rate).coerceIn(0.5f, 2.0f)
            val edge = SpeakSettings.usesEdge()
            val answer = if (edge) {
                LwEdgeTts.speak(context, text, speed)
            } else {
                // 没填地址时这一句会直说缺什么 (见 LwApiTts.speak), 所以这里不另设闸
                LwApiTts.speak(context, text, speed)
            }
            val failed = !answer.startsWith("said ")
            // 在线那两条的播放器 (LwVoiceClip 里那个 MediaPlayer) 也在本进程: 同上, 判据只认播放标记
            if (!failed) stampTail(run, text, system = false)
            // **Edge 彻底不行时换系统那条** (2026-10-20): LwEdgeTts 已经在 403 时问过官网那个令牌
            // 端点, 还失败就是"算法又变了 / 端点够不着"; 不让主人没声音 —— 落到系统引擎, 回执写明来龙去脉
            if (edge && failed) {
                Log.w(TAG, "the Edge call failed ($answer); falling back to the system engine")
                val fallback = try {
                    systemSpeak(context, request, text, run)
                } catch (error: Throwable) {
                    unavailable(
                        "speaking",
                        "the Edge call failed ($answer), and the system engine could not take over: ${error.message ?: error}",
                    )
                }
                edgeFallbacks.incrementAndGet()
                return buildJsonObject {
                    fallback.forEach { (name, element) -> put(name, element) }
                    put("fallbackFrom", "edge")
                    put("edgeDetail", answer)
                }
            }
            return buildJsonObject {
                put("spoken", !failed)
                put("readingWith", if (edge) "edge" else "api")
                put("voice", if (edge) SpeakSettings.edgeVoice else SpeakSettings.apiVoice)
                put("rate", speed.toDouble())
                put("text", text)
                put("characters", text.length)
                val askedVolume = request.numberOrNull("volume") != null
                put("volumeIgnored", askedVolume)
                if (askedVolume) {
                    put("volumeNote", "the online engine keeps its own loudness, so that number did not apply")
                }
                put("detail", answer)
            }
        }
        return systemSpeak(context, request, text, run)
    }

    /**
     * 系统那条引擎 (ROM 自带的 TextToSpeech)
     *
     * 从 [speakWith] 里抽出来, 为了让 Edge 那一档在彻底失败时**还能落到这里** —— "自动换系统朗读"
     * 那一条 (2026-10-20) 不能靠改 [SpeakSettings] 的全局设置, 那会把主人选的那条引擎改掉
     */
    private fun systemSpeak(context: Context, request: JsonObject, text: String, run: Int): JsonObject {
        val tts = engine(context)
        val chinese = tts.setLanguage(Locale.CHINESE)
        if (chinese == TextToSpeech.LANG_MISSING_DATA || chinese == TextToSpeech.LANG_NOT_SUPPORTED) {
            unavailable(
                "speaking Chinese",
                "the system engine has no usable Chinese voice (setLanguage said $chinese);" +
                    " download a voice pack in the system's text-to-speech settings",
            )
        }
        // 语速的优先级: 这次调用点名要的 > 设置页里选的 > **什么都不动**
        //
        // 最后那一档是必须的: 原来写死 `rate ?: 1.0` 就等于每次出声都把系统里调好的语速按回 1.0,
        // 而"系统的设置是用户的"。所以没点名、设置页又选了跟随系统时, 这里一个数都不设
        val asked = request.numberOrNull("rate")?.toFloat()
        val chosen = asked?.coerceIn(0.5f, 2.0f) ?: SpeakSettings.effectiveRate()
        if (chosen != null) runCatching { tts.setSpeechRate(chosen) }
        // 音色同理由设置页定; 存的是"名字@地区" (名字单独用会撞车), 找不到就退回引擎默认
        SpeakSettings.voice?.let { stored ->
            val wanted = runCatching { tts.voices }.getOrNull()?.firstOrNull { voiceKey(it) == stored }
            if (wanted != null) {
                runCatching { tts.voice = wanted }
                    .onFailure { Log.w(TAG, "the engine would not take the voice $stored", it) }
            } else {
                Log.w(TAG, "the engine no longer lists the voice $stored, using its own default")
            }
        }
        val pieces = chunk(text, TextToSpeech.getMaxSpeechInputLength())
        val latch = CountDownLatch(1)
        val lastId = "lw-speak-${++spoken}-${pieces.lastIndex}"
        failure = null
        current = Utterance(lastId, latch)
        val interrupt = request.bool("interrupt", true)
        val budget = (SPEAK_BUDGET_MS + text.length * SPEAK_BUDGET_PER_CHAR_MS).coerceAtMost(MAX_WAIT_MS)
        // 半双工: **先关麦克风再出声**, 反过来就有几十毫秒的喇叭内容被录进去,见 VoiceState.speaking
        // 与采集那条链上的 halfDuplex 闸 —— 不采就不会把自己的声音录回去, 也叫不醒自己
        //
        // try 要包住整段: 喇叭排队失败时 `unavailable` 会抛, 那时标记也必须放回去, 不然麦克风就
        // 一直关着, 下一次说话谁也听不见
        VoiceState.speaking = true
        val finished = try {
            pieces.forEachIndexed { index, piece ->
                val id = "lw-speak-$spoken-$index"
                val mode = if (interrupt && index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val result = tts.speak(piece, mode, null, id)
                if (result != TextToSpeech.SUCCESS) {
                    if (run == speakingRun.get()) current = null
                    unavailable("speaking", "the engine refused the utterance ($result)")
                }
            }
            latch.await(budget, TimeUnit.MILLISECONDS)
        } finally {
            if (run == speakingRun.get()) VoiceState.speaking = false
        }
        val reported = failure
        // 只清自己那一条: 异步那一档 (T14) 里后一声可能已经挂上了新的 utterance
        if (run == speakingRun.get()) current = null
        // 系统那条: **只有它**要靠"预算窗 + 专属属性"那一条 (播放器在引擎进程里). **"说完了"之后
        // 那一段**: 引擎报的结束常常早于喇叭真的不响, 按这一句的长度留一份上限, 那一段里由"带我们那份
        // 属性的活跃播放"说了算 (见 [talking] 与 `SystemSpeech`)。被掐断的那一趟不写 (代际号不一致)
        stampTail(run, text, system = true)
        return buildJsonObject {
            put("spoken", finished && reported == null)
            put("text", text)
            put("pieces", pieces.size)
            put("characters", text.length)
            put("waitedMs", budget)
            // 点名要音量而这条引擎给不了, 那就要**说出来**: 静默丢掉参数等于报了一件没发生的事
            // (系统那条的增益在引擎自己手里, 应用这一侧没有 API —— 见 status 里的 volumeAppliesTo)
            val askedVolume = request.numberOrNull("volume") != null
            put("volumeIgnored", askedVolume)
            if (askedVolume) {
                put("volumeNote", "the system engine keeps its own volume, so that number did not apply")
            }
            put("detail", reported ?: if (finished) "the engine finished" else "still speaking after ${budget}ms")
        }
    }

    /** 设置页那颗「检测 Edge」: 走一趟完整握手与合成, 只收不播 (见 LwEdgeTts.probe) */
    internal fun probeEdge(remote: Boolean = false): JsonObject = LwEdgeTts.probe(remote)

    /**
     * 掐断正在念的
     *
     * **两条引擎都要停**: 原来只停了系统那条 (`tts.stop()`), 而自带那条正在放的是我们自己的
     * `AudioTrack` —— 用它念的时候"停止"会什么都不做 (2026-10-05 做停止按钮时发现的)。两边都叫一遍,
     * 谁在放谁就停
     */
    internal fun stop(): JsonObject {
        // **先加代际号**: 正在等预算的那一趟 (同步被掐 / 异步还在念) 回来时会发现号变了, 于是它不
        // 会再把半双工闸合上、不会写尾巴预算、也不会把 current 清掉 (T14)
        speakingRun.incrementAndGet()
        val waiting = current
        current = null
        // 让还在 `latch.await` 的那一趟立刻回来, 不必等满自己的预算 (长回答是 60 s 以上)
        waiting?.latch?.countDown()
        // 预录填音也一起停 (它在另一条声轨上, 系统引擎的 stop 碰不到它)
        LwClips.stop()
        val onDevice = LwTts.stop()
        // 在线那两条: 谁在放谁就停 (它们的播放器在 LwVoiceClip 里, 两条引擎各自记着自己的那一次)
        val online = LwEdgeTts.stop() ?: LwApiTts.stop()
        val tts = synchronized(lock) { engine }
        val result = tts?.stop()
        // 掐断了就不能让那道半双工的闸一直关着
        VoiceState.speaking = false
        // 手动掐断是"现在就停": 那份尾巴预算也一起作废, 否则麦克风会白关一会儿 (见 [talking])
        VoiceState.spokeUntil = 0L
        val stopped = onDevice != null || online != null || result == TextToSpeech.SUCCESS
        return buildJsonObject {
            put("stopped", stopped)
            put("onDevice", onDevice != null)
            put("online", online != null)
            put(
                "detail",
                when {
                    online != null -> online
                    onDevice != null -> onDevice
                    result == TextToSpeech.SUCCESS -> "the system engine stopped (it said $result)"
                    tts == null -> "nothing was speaking"
                    else -> "the system engine had nothing playing (it said $result)"
                },
            )
        }
    }

    /** 现在有没有在念: ⋮ 菜单那条「停止朗读」据此决定要不要说自己没东西可停 */
    internal val speakingNow: Boolean
        get() = current != null || LwTts.speaking || LwEdgeTts.speaking || LwApiTts.speaking

    /**
     * **她此刻还在出声吗** —— 半双工那道闸与"说的时候关麦"都认这一条, 不认 [speakingNow]
     *
     * 三条引擎各自有说得准的答案 ([SpeechTail]):
     *
     * | 引擎 | 判据 |
     * | :-- | :-- |
     * | 自带 (`LwTts`) / 在线 (Edge / API) | 播放器就在本进程, `LwTts.speaking` / `LwVoiceClip.speaking` 就是精确答案, **一个字节都不问系统** |
     * | 系统 (`TextToSpeech`, 默认那一条) | 还挂着一条 utterance, 或者在那段预算窗里、而且**带我们给引擎那份属性**的活跃播放还在 ([SystemSpeech]) |
     *
     * **`AudioManager.isMusicActive` 从这条链上整个删掉了** (2026-10-16 主人: "正在说有时会跟随手机其它
     * 应用播放而显示"): 它答的是全系统 STREAM_MUSIC 的活跃度, 不看是谁在放, 于是任何一次朗读之后那段
     * 预算窗里, 别的应用一放音乐/视频就把它带起来, 窗口长度还与本句真实播了多久无关。现在宁可「正在说」
     * 多亮一会儿 (有 [SpeechTail.budgetMs] 上限), 也不能被别人的音乐带起来 —— 拿不到播放列表时**退化
     * 成"只按预算"**, 绝不回退到媒体流那一条
     */
    internal fun talking(context: Context): Boolean {
        // 自带那条与两条在线引擎: 播放器在进程内, 精确
        val onThisProcess = LwTts.speaking || LwEdgeTts.speaking || LwApiTts.speaking
        // 系统那条: 只有它才去问"带我们那份属性的活跃播放"
        val ownPlayback = lastWasSystem && SystemSpeech.playing(context)
        return SpeechTail.holding(
            now = System.currentTimeMillis(),
            enginePlaying = onThisProcess,
            // 还挂着一条没收到 onDone / onError 的 utterance
            utterancePending = current != null,
            tailUntil = VoiceState.spokeUntil,
            ownPlayback = ownPlayback,
        )
    }

    /**
     * 这一句念完之后留多长的"可能还在响"预算
     *
     * **它只是上限**, 算术在 [SpeechTail.budgetMs] (那里能单测): 系统那条引擎的合成比实时快, 所以
     * 引擎报完之后还要留一段, 真实长度由 [talking] 里那两条精确判据说
     */
    private fun tailBudgetMs(text: String): Long = SpeechTail.budgetMs(text)

    /**
     * 记下"这一句念完了, 尾巴到什么时候" (T14 起带代际号)
     *
     * 被后一声盖掉、或被 `stop` 掐掉的那一趟**一个字节都不写**: 写了就等于把当前这一声的预算清成
     * 旧值, 半双工闸会提前放行
     */
    private fun stampTail(run: Int, text: String, system: Boolean) {
        if (run != speakingRun.get()) return
        lastWasSystem = system
        VoiceState.spokeUntil = System.currentTimeMillis() + tailBudgetMs(text)
    }

    /** 把引擎放掉: 下一次 status/speak 会重新初始化 */
    private fun release(): JsonObject {
        val held = synchronized(lock) {
            val loaded = engine
            engine = null
            loaded
        }
        current = null
        if (held == null) {
            return buildJsonObject {
                put("released", false)
                put("detail", "no engine was loaded")
            }
        }
        held.stop()
        held.shutdown()
        return buildJsonObject { put("released", true) }
    }

    /**
     * 建一个可用的引擎: 初始化是异步的, 而且 TextToSpeech 要在有 Looper 的线程上建, 所以这一步
     * 交给主线程做, 这里等它回话
     */
    private fun engine(context: Context): TextToSpeech {
        synchronized(lock) {
            engine?.let { return it }
            val app = context.applicationContext
            val ready = CountDownLatch(1)
            var created: TextToSpeech? = null
            var init = TextToSpeech.ERROR
            val create = Runnable {
                created = TextToSpeech(app) { result ->
                    init = result
                    ready.countDown()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                create.run()
            } else {
                Handler(Looper.getMainLooper()).post(create)
            }
            val up = ready.await(INIT_BUDGET_MS, TimeUnit.MILLISECONDS)
            val instance = created
            if (!up || init != TextToSpeech.SUCCESS || instance == null) {
                instance?.let { runCatching { it.shutdown() } }
                unavailable(
                    "speaking through the system engine",
                    "the text to speech engine did not come up within ${INIT_BUDGET_MS}ms (init said $init)",
                )
            }
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    val waiting = current
                    if (waiting != null && waiting.id == utteranceId) waiting.latch.countDown()
                }

                override fun onError(utteranceId: String?) {
                    failure = "the engine reported an error for $utteranceId"
                    current?.latch?.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    failure = "the engine reported error $errorCode for $utteranceId"
                    current?.latch?.countDown()
                }
            })
            // **给它一份专属属性** (2026-10-16): 引擎的播放器在它自己的进程里, 这是应用这一侧唯一能
            // "认出我那一份声音还在不在"的办法 —— [talking] 就靠它取代了原来的 `isMusicActive`
            // (那条会把别的应用的音乐算成"她还在说")。引擎不吃这份属性时不影响它念, 只是判据退化成
            // "只按预算", 而 `speak op=probe` 量得出来
            runCatching { instance.setAudioAttributes(SystemSpeech.attributes()) }
                .onFailure { Log.w(TAG, "the engine would not take this app's audio attributes", it) }
            engine = instance
            return instance
        }
    }

    /** 按句号换行切到引擎能吃的长度; 切不出好位置就硬切 —— 系统那条与自带那条都靠它切长文本 */
    internal fun chunk(text: String, limit: Int): List<String> {
        val safe = if (limit <= 0) 4000 else limit
        if (text.length <= safe) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + safe).coerceAtMost(text.length)
            if (end < text.length) {
                val cut = text.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';'), end)
                if (cut > start + safe / 2) end = cut + 1
            }
            pieces += text.substring(start, end)
            start = end
        }
        return pieces
    }
}
