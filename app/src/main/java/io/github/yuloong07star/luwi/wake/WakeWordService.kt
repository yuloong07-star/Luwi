package io.github.yuloong07star.luwi.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.yuloong07star.luwi.MainActivity
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.HostStatus
import io.github.yuloong07star.luwi.lock.LockReplay
import io.github.yuloong07star.luwi.lock.LockSetting
import io.github.yuloong07star.luwi.overlay.BallSpot
import io.github.yuloong07star.luwi.overlay.OverlayService
import io.github.yuloong07star.luwi.overlay.OverlayState
import io.github.yuloong07star.luwi.tool.LwSpeech
import io.github.yuloong07star.luwi.tool.LwSpeak
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.voice.AudioCapture
import io.github.yuloong07star.luwi.voice.SpeechSegmenter
import io.github.yuloong07star.luwi.voice.VoiceCommands
import io.github.yuloong07star.luwi.voice.VoiceInbox
import io.github.yuloong07star.luwi.voice.VoiceRoute
import io.github.yuloong07star.luwi.voice.VoiceSettings
import io.github.yuloong07star.luwi.voice.VoiceState
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 唤醒词现在什么样: 通道方法 `wakeword` 的 status 就读这里
 *
 * **2026-10-06 起常驻语音只允许视频模式触发** (主人定的口径): 设置页那个「允许常驻语音」与浮标
 * 菜单里那行「一直听」都删掉了, 而 [voiceAllowed] / [voiceActive] 这两个字段留着 —— 它们说的是
 * "视频模式那个记号在不在"与"识别链此刻留没留着", 是排查"切回手机模式之后它还在吃麦克风"这类问题
 * 唯一的读数
 */
internal object WakeWordState {
    @Volatile
    var listening: Boolean = false

    /** 现在守着的那几个词, 显示名那一半 (肥鱼肥鱼 / 小爱同学 …), 同名的只留一个 */
    @Volatile
    var keywords: List<String> = emptyList()

    /** 从监听开始到现在命中过几次, 以及最后一次是什么时候、哪个词 */
    @Volatile
    var hits: Int = 0

    /**
     * **被去抖吃掉几次** (主人 2026-10-09 调参那一批)
     *
     * 声学阈值从 0.25 降到 0.01 之后 KWS 会灵敏得多, 同一个词连着报两遍、或者指令前半段被当成下一次
     * 唤醒都会变多; [WakeDecision] 把它们压掉, 而"压掉了几次"必须看得见 —— 它是"去抖真在起作用"与
     * "阈值是不是放得太开"共用的那个读数 ([io.github.yuloong07star.luwi.tool.LwWakeWord] 的
     * `op=status` 与 `hits` 并排报)
     */
    @Volatile
    var suppressed: Int = 0

    @Volatile
    var lastKeyword: String? = null

    @Volatile
    var lastHitAt: Long = 0

    @Volatile
    var startedAt: Long = 0

    /**
     * **正在跑的那一份 KWS 参数** (阈值 / 加分 / 候选路数 / 挂帧数)
     *
     * 起监听那一刻由 [WakeWordService.startListening] 写, 通道 `op=status` 报的是这四个而不是
     * [WakeTuning] 里的缺省 —— "守的是哪个数"与"缺省是哪个数"在真机回调时是两件事
     */
    @Volatile
    var threshold: Double = WakeTuning.KEYWORDS_THRESHOLD.toDouble()

    @Volatile
    var score: Double = WakeTuning.KEYWORDS_SCORE.toDouble()

    @Volatile
    var maxActivePaths: Int = WakeTuning.MAX_ACTIVE_PATHS

    @Volatile
    var trailingBlanks: Int = WakeTuning.NUM_TRAILING_BLANKS

    /** 麦克风前台服务那条类型有没有被系统接下来: 退到 specialUse 时后台可能录不到音 */
    @Volatile
    var microphoneForeground: Boolean = false

    /**
     * 视频模式那个记号开着没有 (常驻语音唯一的开门条件)
     *
     * 它与 [listening] 一起才能说清现在的状态: `listening = true, voiceAllowed = false` 是**只有
     * 唤醒词在守**那一个态 (常态), 而开了视频模式之后这两个会同时为真
     */
    @Volatile
    var voiceAllowed: Boolean = false

    /** 识别链此刻留没留着 (视频模式那一档, 或者刚买来的那一句话) —— 与 [listening] 是两件事 */
    @Volatile
    var voiceActive: Boolean = false

    /**
     * **"开了又关"那两笔账** (2026-10-10, 主人报的"三击 ball 会打开语音输入, 并且会卡一下")
     *
     * 三击的头一下原来会先把这一句话的窗口开起来 ([WakeWordService.openVoice], 那一步在主线程上建
     * 切段会话), 第三下再收掉 —— 一次来回就是一次整屏卡顿。[VoiceRollback] 那条快径把"开了又关"
     * 降级成一次静默回退, 而这两个数就是它的读数:
     *
     * - [fastRollbacks]: 走了快径几次 (窗口内、还没听到人声就收了 —— 通知一个字都不说, 模型也不读)
     * - [shortLivedOpens]: 开门之后 [WakeWordService.VOICE_SHORT_LIVED_MS] 之内就被收掉几次 (不论走
     *   没走快径)。**确认窗落地之后它应该趋近于 0**: 三击这一条路压根不该再把门打开
     *
     * 两个数都在 `lw_wakeword op=status` 里报出来
     */
    @Volatile
    var fastRollbacks: Int = 0

    @Volatile
    var shortLivedOpens: Int = 0

    /**
     * **省电模式此刻生不生效** (主人 2026-10-07): 手动那个开关, 或者设置页定的那一段定时
     *
     * 它说的是"麦克风关着, 喊不醒"这一件事 —— 而服务本身、通知、浮标与 host 都还在, 点球也能临时
     * 借一次麦克风说一句。它与 [listening] 是两件事: 省电模式里服务活着而 [listening] 是 false
     */
    @Volatile
    var powerSave: Boolean = false

    /** 上一次失败的原因, 供 status 与日志共用 */
    @Volatile
    var lastError: String? = null
}

/**
 * 半双工那道闸: 这一帧音频该怎么走
 *
 * **走法 A** (主人 2026-10-05 定): 喇叭正在说话时**谁都不许吃音频** —— 不采就没有"把自己的声音录回
 * 去"这回事, 顺带也解决了唤醒词被自己念的那句话叫醒。合闸的代价是"念回答那几秒叫不醒", 那是认下来
 * 的取舍
 *
 * **合上时要作废, 不只是丢样本**: 直接丢掉新来的音频, VAD 手里那半句还留着 —— 几秒后 TTS 结束,
 * 新音频接上去就切成"前半句 + 后半句"拼起来的一段, 而那一段会被当成主人刚说的话。所以合闸的那一帧
 * 要 [HalfDuplex.ABANDON] (让切段器把手里的作废掉), 而唤醒词那一路**跟着一起哑**: "这一帧不许吃"
 * 只写一处, 不为唤醒词开例外
 *
 * 两条判据是三条展开的 `if`, 所以它抽成一个纯函数 (`forward`) —— 没有设备也能量, 见 [HalfDuplexTest]
 *
 * **喂进来的那个"在说话没有"是两个来源的并集** (2026-10-09 修的, 2026-10-16 收口): 谁在放由
 * `LwSpeak.talking` 说 (三条引擎各自准的那一条, 不再看系统媒体流), 而 `VoiceState.speaking` 带的是它
 * 管不到的那一件事 —— 开门那一声「嘀」。在线那两条 (Edge / API) 过去不写任何标记, 于是它念回答时
 * 麦克风开着, 自己的声音被录回去 ([WakeWordService.voiceSink])
 */
internal object HalfDuplex {

    /** 喇叭在说话: 这一帧作废掉, 唤醒词与切段都不吃 */
    const val ABANDON = "abandon"

    /** 谁都没在说话: 喂给唤醒词与切段那两个消费者 */
    const val ACCEPT = "accept"

    fun forward(speaking: Boolean): String = if (speaking) ABANDON else ACCEPT
}

/**
 * "这一句话的窗口"是不是**开了又关** —— 如果是, 关的那一下走快径
 *
 * **为什么要有这一条** (2026-10-10 主人: "三击 ball 会打开语音输入, 并且会卡一下"): 三击的头一下
 * 原来会先把窗口开起来, 第三下再收掉。开门那一步除了建切段会话, 还会把识别模型**预热到内存里**并
 * 把通知栏改成"听着你说这一句" —— 第二次还没读完就收, 这几笔全是白花的
 *
 * 快径的判据是**"还没有任何人声进来"**: 窗口开了 [WakeWordService.VOICE_ROLLBACK_MS] 之内收掉,
 * 而且这一段里一次都没听到人声 (VAD 说这一窗有人声才会记一笔, 32 ms 一窗)、也没有出过字。几条都
 * 成立才是"主人根本没说话", 那时预热与通知那两句可以整个跳过; 只要有一条不成立 (哪怕只听见一窗),
 * 说明这一次开门真的听进去了东西, 那就照常收尾 —— 那一笔闲置账也跟着走
 *
 * 快径**不跳**收尾本身: 切段器照常 flush / close, 转写线程照常收, 识别器照常 reap —— 少一样就是
 * 一次泄漏。它省的只有"还没开始的东西" (预热与通知那两句)
 *
 * 纯函数, 没有设备也能量 (见 VoiceRollbackTest)
 */
internal object VoiceRollback {

    /**
     * 这一次关窗能不能走快径
     *
     * @param openedAt 开门的时刻 (0 = 不知道, 当没开过处理)
     * @param heardAt 这一段里最后一次"VAD 说有人声"的时刻 (0 = 一次都没有)
     * @param delivered 这一段里出过字没有 (投出去一句之后那一笔闲置账是另一个数, 见 [VoiceIdle])
     * @param windowMs 快径窗口 ([WakeWordService.VOICE_ROLLBACK_MS])
     */
    fun isFast(now: Long, openedAt: Long, heardAt: Long, delivered: Boolean, windowMs: Long): Boolean =
        openedAt != 0L && !delivered && heardAt == 0L && now - openedAt in 0..windowMs
}

/**
 * "这一句话"的窗口该不该收了
 *
 * **两条都算一次活动** (主人 2026-10-06 定的): 出一句字 ([lastTextAt]), 或者 VAD 说这一窗有人声
 * ([lastVoiceAt])。只看前者的话有一条真机上够得着的错 —— 一口气不停顿地说得比 [limit] 还长时, VAD
 * 还没切出段 (要 0.8 s 静音, 或者到 15 s 上限才强切), 于是"正在说话"在计时的眼里等于"没人说话",
 * 看门狗从句子中间就把窗口收回了
 *
 * 抽成纯函数是为了没有设备也能一条条量, 见 [VoiceIdleTest]
 */
internal object VoiceIdle {

    /**
     * 助手还在想 / 还在念的时候, 这一笔闲置账**挂起**, 不收这一句话的窗口
     *
     * **2026-10-09 加的** (主人报"语音输入没说完自动退出"): 原来这两个状态也在计时, 而它们正是主人
     * 最可能在"想下一句怎么说"的时候 —— 比如问完一句、等回答的那几秒, 窗口自己收了, 下一句就得
     * 重新喊唤醒词
     *
     * 判据只有两个, 都是**球上看得见**的状态 (与球上三个字同源): 「正在想」(`OverlayState.phase`,
     * 宿主写进 `ball-phase.json` 那一笔账) 与「正在念」(`VoiceState.speaking`)。两个都不在时, 调用方
     * 把这一笔账从那一刻重新起算 —— 所以"想完了 / 念完了"之后仍然有完整的一份 [expired] 那个 limit
     *
     * **但它只管"还没说到一句"的那一段** (2026-10-09 补): 一句话已经出字投出去了之后就不该再挂起
     * —— 那时窗口只剩 [WakeTuning.SENT_TAIL_MS] 那条短尾巴 (人声照样续期, 见 [limit])。调用方用
     * [WakeWordService.voiceDelivered] 把这两档分开
     *
     * 纯函数, 判据在 [VoiceIdleTest]
     */
    fun held(thinking: Boolean, speaking: Boolean): Boolean = thinking || speaking

    /**
     * 这一笔闲置账用哪个上限
     *
     * 还没说到一句时是调用方给的 [limit] (10 s: "想一下再说第一句"那一段); **投出去过一句之后**是
     * [WakeTuning.SENT_TAIL_MS] —— 一次唤醒买的就是一句话
     */
    fun limit(delivered: Boolean, limit: Long): Long = if (delivered) WakeTuning.SENT_TAIL_MS else limit

    /**
     * 从"最后一次活动"到现在够不够 [limit]
     *
     * 一次活动都没有过时两个时刻都是 0, 于是判据落在 0 上 —— 但调用方传进来的 [lastTextAt] 是
     * **开门那一刻** (见 [WakeWordService.openVoice]), 所以"开了门没人说话"照样会到期
     */
    fun expired(now: Long, lastTextAt: Long, lastVoiceAt: Long, limit: Long): Boolean =
        now - maxOf(lastTextAt, lastVoiceAt) >= limit
}

/**
 * 一直听着麦克风: 唤醒词在那一路音频上等着, 命中之后**开一次"说一句话"的窗口**
 *
 * 这是「喊一声唤醒词」的落点。**它只有一层** (2026-10-06 起): 唤醒词那一路从服务起到服务停一直在守
 * (低功耗守门人: `KeywordSpotter` 与采集, 是"允许唤醒"那个许可唯一的消费者, 也是
 * [WakeWordState.listening] 说的那件事), 而 silero VAD 切段 + SenseVoice 出字那一条**只在需要听
 * 一句话的时候才铺开** —— 唤醒词命中一次, 或者主人在球上点一下 ([listenNow])。
 *
 * **"常驻语音"整条链路已经删掉了**: 那是一个"允许常驻语音"的许可加上一条不收回的识别链, 结果是
 * 对话一直进行下去 (主人 2026-10-06 判定它多余)。现在开门只有上面那两条路, 而**两条都用完就收**:
 * 闲置 [VOICE_IDLE_MS] 之后 [startWatchdog] 把切段与出字收回去, 麦克风回到只喂唤醒词那一路。
 * 于是"谁在吃麦克风"这件事只剩两个态: 只有唤醒词在守 / 正在听你说的这一句。
 *
 * 三件事与浮窗那套不同:
 *
 * 1. **它靠 sherpa-onnx 跑在本地**: 唤醒词是关键词检测 (KWS, 3.3M 参数的 zipformer), 切段是
 *    silero VAD, 出字是 SenseVoice —— 同一份 AAR、同一个 16 kHz 单声道音频, 一句话不出设备
 * 2. **前台服务, 类型是 microphone**: 后台一直开麦克风必须有这个类型, 而且起服务那一刻应用
 *    得在前台 (或握着 `SYSTEM_ALERT_WINDOW`, 见下面 [fend] 的注释)。通知栏留一条常驻, 上面
 *    一个「停止」按钮 —— 一直开着的麦克风必须有一眼看得见、一下就关得掉的地方
 * 3. **听到之后的动作是可配的**: 默认把应用提到前面, 也可以把浮窗叫起来 (见 [onWake])
 *
 * **采集只有一路** ([AudioCapture]): 唤醒词与切段是同一段音频的两个消费者, 两个消费者都在采集
 * 线程上跑, 而切出来的整段话交给另一条线程去认 —— 在采集线程上认一段话会卡住采集几百毫秒, 那就
 * 是丢音频,这条链有三个独立的"成不成" (采集 / 切段 / 出字), 任何一个不成都不该把别的带走: 缺
 * silero 模型时唤醒词照样好用, 只是没有"说一句话进会话", 那时 [VoiceState] 要如实说明是哪一条
 *
 * 关键词不接受中文原文: sherpa-onnx 的 keywords 文件里每一行是**模型的 token 序列**加一个
 * `@显示名` (见模型自带的 keywords.txt), EncodeKeywords 只认 token 表里有的符号。所以词表由
 * 宿主那一侧写进来 (lw_wakeword op=keywords), 这边只管读与校验
 */
class WakeWordService : Service() {    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var vad: SpeechSegmenter? = null
    private var capture: AudioCapture? = null
    private var transcriber: Thread? = null

    /** 切出来等着认的段: 上限是"设备认不过来时丢最新的", 而丢了多少要看得见 */
    private val segments = ArrayBlockingQueue<FloatArray>(SEGMENT_QUEUE)

    @Volatile
    private var transcribing = false

    @Volatile
    private var listening = false

    private var keywordsFile: File? = null
    private var modelDirectory: File? = null

    // 类型写出来是必须的: 这两个数从 Intent 那来的是 Double (extra 只有 double), 而 sherpa 的
    // 配置要的是 Float,原来靠 `= WakeTuning.KEYWORDS_THRESHOLD` 推出来的类型是 Float, 于是同一个文件里
    // ".toFloat() 赋给 Float 字段" 与 "Float 传给要 Float 的形参" 两处都要写清楚
    //
    // 四个数的缺省在 [WakeTuning] 那张表里**只此一份** (2026-10-09 从两处重复的常量收拢过来)
    private var threshold = WakeTuning.KEYWORDS_THRESHOLD
    private var score = WakeTuning.KEYWORDS_SCORE
    private var maxActivePaths = WakeTuning.MAX_ACTIVE_PATHS
    private var trailingBlanks = WakeTuning.NUM_TRAILING_BLANKS
    private var onWake = WAKE_TO_APP
    private var vibrateMs = DEFAULT_VIBRATE_MS

    /**
     * 命中之后的静默窗: 在这一刻之前报出来的词一律不算数 ([WakeDecision])
     *
     * 采集线程 ([keywordSink]) 读它决定放不放行, 而命中那一刻 ([hit]) 与"第一段出字投递" ([deliver])
     * 两处写它 —— 后者在另一条线程上 (认字线程), 所以它是 `@Volatile`
     */
    @Volatile
    private var kwsMutedUntil = 0L

    /**
     * 这一次静默是不是还在"等第一段出字把命令尾收掉"
     *
     * 命中时置真, 第一段出字投递时置假 (并把静默窗收到冷却下限), 于是**一次命中只收一次** ——
     * 一句话被 VAD 切成两段时, 第一段把窗收掉, 第二段不再动它
     */
    @Volatile
    private var kwsAwaitingTail = false

    /**
     * 命中之后除了叫醒还要做什么 (批次 4.4)
     *
     * 三个值由设置页那个「叫醒之后」定 ([LwWakeWord.onHit]): 只叫醒 ([LwWakeWord.HIT_WAKE], 缺省)、
     * 顺带切到视频模式、顺带切回手机模式。后两条在这里**只写一句命令进收件箱**, 真正切模式的是宿主
     * 插件那张命令表 —— 命令词表只有一份, 见 [io.github.yuloong07star.luwi.voice.VoiceCommands]
     */
    private var onHit = LwWakeWord.HIT_WAKE

    /**
     * **视频模式要求把识别链留着** —— 常驻语音现在唯一的开门条件
     *
     * 主人 2026-10-06 定的: 常驻语音留着, 但**只允许视频模式触发它**。设置页那个「允许常驻语音」与
     * 浮标菜单里那行「一直听」都删掉了, 因为那两条路都能让人在手机模式下把它打开, 结果就是对话一直
     * 进行下去 —— 那是主人判定多余的东西。视频模式不一样: 那个模式本来就是"看着东西说话", 连着问
     * 才用得下去 (见 `assets/modes/video.md` 第一节)
     *
     * 判据是 [LwWakeWord.residentWanted] 读的那个记号 (`$DSH_HOME/modes/voice-resident.on`), 由切模式
     * 那一步写/删; 服务在 [onStartCommand] 与 [openVoice] 两处读它 —— 前者管"服务活着的时候切模式",
     * 后者管"命中那一刻它该不该留"
     */
    private var voiceResidency = false

    /**
     * **视频模式里说的话投给哪一场** (进场那一刻定格的, 见 [LwWakeWord.videoTarget])
     *
     * 它与 [voiceResidency] 是一对: 记号说"这一路要不要留着", 这一条说"留下来的这一路往哪儿投" ——
     * 主人 2026-10-07 的口径是"打开视频模式那句话与视频模式里接着说进的话要在同一场", 而"哪一场"
     * 只有页面知道 ([VoiceState.uiSession]), 所以切模式那一步把它定格在 `modes/video-voice.session` 里
     */
    @Volatile
    private var videoTarget: String? = null

    /**
     * "说一句话"那个窗口现在开着没有
     *
     * 它与 [WakeWordState.listening] 是**两件事**: 唤醒词一直在守, 而这一条说的是"切段与出字也铺开
     * 了、麦克风正被吃着"。开门有两个调用方 ([openForOneSentence] 与 [listenNow]), 而**收不收**
     * 看 [voiceSticky]: 一次命中买一句话 (用完由 [startWatchdog] 按 [VOICE_IDLE_MS] 收回去), 视频模式
     * 那一次要一直留着
     */
    @Volatile
    private var voiceActive = false

    /**
     * **说之前那句话的窗口是不是开着** (2026-10-10 主人: "我点击正在说后, 语音输入又用不了了")
     *
     * "说的时候关麦"那一条 (见 [VoiceState.spokeUntil] / [LwSpeak.talking]) 把窗口收掉了, 而原来
     * 只有常驻那一档 (视频模式) 会在说完之后把它拉回来 —— **手机模式下没人拉**: 主人刚说完一句、助手
     * 开始念、他点一下"正在说"把念掐断, 那之后麦克风就一直关着, 得重新喊唤醒词。
     *
     * 所以关麦那一下记一笔"这一句的窗口本来是开着的", 说完 (或掐断) 之后照这一笔把它拉回来 ——
     * 与"说"之前的状态对齐, 而不是一律常驻
     */
    @Volatile
    private var micWasUpBeforeSpeaking = false

    /**
     * 这一次开门是不是"要留着"的
     *
     * 命中开的那一次是**用完就收**, 而视频模式要的那一次要一直留着 —— 两者走的都是 [openVoice],
     * 差别只在这一个记号, 见 [openVoice] 与 [startWatchdog]
     */
    @Volatile
    private var voiceSticky = false

    /** 唤醒窗口: 命中唤醒词之后, 头一句出字要带上"这是头一句"的记号 (见 [deliver]) */
    @Volatile
    private var wakeWindow = false

    /**
     * 这一次"说一句话"的窗口该投给哪一场 (**回复框点名的那一场**, 见 [openForOneSentence])
     *
     * 它只在开窗口那一刻取一次 ([OverlayState.replyTarget]), 与 [wakeWindow] 一起被头一句消费掉:
     * 框在屏上时, 点球与喊唤醒词说的那一句都回给"发出那条回复的会话" —— 宿主那侧把它排在时间那
     * 笔账前面。没有回复框时它是 null, 那一句照旧按 10 分钟那一档走
     */
    @Volatile
    private var voiceTarget: String? = null

    /** 这一句话的窗口最后一次**出字**的时刻, 与 [voiceHeardAt] 一起喂给 [VoiceIdle] */
    @Volatile
    private var voiceIdleSince = 0L

    /** 这一次开门里 VAD 最近一次说有人声的时刻, 0 = 还没听见过 (跟着 [openVoice] 归零) */
    @Volatile
    private var voiceHeardAt = 0L

    /**
     * 这一次开门是哪一刻开的 (0 = 没开过), 与 [voiceHeardAt] / [voiceDelivered] 一起喂给
     * [VoiceRollback] —— "开了又关"那条快径就靠这三个数认
     */
    @Volatile
    private var voiceOpenedAt = 0L

    /** 主线程的投递队列: 现在只挂 [settle] 那一条 (服务自己的回调本来就在主线程上) */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 开门之后 [VOICE_ROLLBACK_MS] 才做的两件事: **预热识别模型 + 把通知栏改成"听着你说这一句"**
     *
     * 分开成一条延迟任务是为了让它**可取消**: [closeVoice] 走快径时 ([VoiceRollback.isFast]) 把它
     * 摘掉 —— 这是"开了又关"不再白读一次几百 MB 模型、也不再闪一下通知的全部手段 (2026-10-10)
     */
    private val settle = Runnable {
        // 延后这一段时间里窗口可能已经收了, 那就一个字都不说
        if (voiceActive) {
            preheat()
            announce(listeningText())
        }
    }

    /**
     * 这一次开门里**已经投出去过一句话**没有 (2026-10-09: "一次唤醒 = 一句话")
     *
     * 它决定闲置账用哪一档 ([VoiceIdle.limit]) 与要不要挂起 ([VoiceIdle.held]): 投出去之前是 10 s
     * 且思考/念期间挂起 (主人还得把那一句说完), 投出去之后只剩 [WakeTuning.SENT_TAIL_MS] 那条短尾巴 ——
     * 而**又听到人声就清掉** ([noteVoice]), 因为那说明下一句还在说
     */
    @Volatile
    private var voiceDelivered = false

    private var watchdog: Thread? = null

    @Volatile
    private var watching = false

    /** 盯 `VoiceState.speaking` 那一跳的观察者 (通知栏那句"正在念回答"靠它) */
    private var speakingWatch: Thread? = null

    @Volatile
    private var watchingSpeech = false

    /**
     * **省电模式此刻生不生效** (主人 2026-10-07: "增加省电模式开关, 及定时开关")
     *
     * 两个来源 ([LwWakeWord.powerSave]): 设置页那个手动开关, 或者主人自己定的一段定时 (`23:00-07:00`)。
     * 它生效时**只停唤醒词监听那一条** —— 麦克风整个关掉, 喊不醒; host / 浮标 / 通知都留着, 而点球
     * 那一条仍能临时借一次麦克风说一句话 ([listenNow])。
     *
     * 服务**不跟着停**: 定时那一段走完要有人把麦克风打开, 而那个"到点了"只有活着的服务知道
     * (见 [startPowerWatch])
     */
    @Volatile
    private var powerSave = false

    /** 省电模式的观察者: 定时那一段只能靠时间自己走, 见 [startPowerWatch] */
    private var powerWatch: Thread? = null

    @Volatile
    private var watchingPower = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 通知栏那一条的字要在 [fend] 之前就有真相 (省电模式里它写的是"麦克风关着")
        powerSave = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(false)
        WakeWordState.powerSave = powerSave
        fend()
        startPowerWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 把这一句话的窗口收回来: 会话界面那个麦克风胶囊与球上"再点一下"走的都是这一条, 而唤醒词
        // 接着守 —— 喊一声 (或者再点一下球) 就能把下一次窗口要回来。**视频模式那个常驻档也走这里**:
        // 收掉之后它就是"只说一句"的档, 而视频模式下次开门时又把它铺开 (见 [residentWanted])
        if (intent?.action == ACTION_HUSH) {
            runCatching { closeVoice() }
            runCatching { announce(listeningText()) }
            return START_STICKY
        }
        // 浮标上点一下 (批次 4.2): 开一次"讲一句话"的窗口, 与命中走同一条路, 只是没有那个词
        if (intent?.action == ACTION_LISTEN_NOW) {
            listenNow()
            return START_STICKY
        }
        // 设置页改过"叫醒之后", 或者视频模式刚开关过: 不重启服务, 只把新值读进来 —— 打开常驻那个档
        // 时顺手把识别链铺开, 关掉时把已经在跑的那条收回来 (**两个方向都要管**, 否则切回手机模式之后
        // 它还在吃麦克风)
        if (intent?.action == ACTION_REFRESH) {
            onHit = LwWakeWord.onHit(this)
            vibrateMs = if (LwWakeWord.vibrate(this)) DEFAULT_VIBRATE_MS else 0
            // 省电模式那两个开关 (手动 / 定时那一段) 也在这一条上读 —— 设置页拨完当场生效
            val wantedPower = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(false)
            if (wantedPower != powerSave) {
                applyPower(wantedPower)
                return START_STICKY
            }
            val wanted = residentWanted()
            if (wanted == voiceResidency) {
                // 记号没变也要重读一次定格: 主人可能又切了一次模式 (那一次会重新定格), 而缓存错过它
                // 的表现正是"视频模式里说的话落回浮标账本"
                refreshVideoTarget()
                return START_STICKY
            }
            voiceResidency = wanted
            refreshVideoTarget()
            WakeWordState.voiceAllowed = wanted
            // **省电模式里不铺常驻链**: 那个记号的意图仍被记着 (出了时段照旧恢复), 只是此刻不生效
            //
            // **链子已经开着时也要把这一档改成常驻** (2026-10-10 主人报的"她打开后听不了话了…当前语音
            // 是开的但输入不了话"): 进视频模式那一下往往正好落在"一句话的窗口"里 (唤醒 → 说"打开视频
            // 模式" → 切模式), 而原来的写法只写记号、不动那条已经开着的链 —— 于是它还是"只说一句"的档
            // ([voiceSticky] 为假), 0.3 s 那句尾巴一到就自己收了, 球上却按记号写着「正在听」。所以这里
            // 补上"已经开着就当场转常驻"这一支
            if (wanted && !powerSave) {
                if (voiceActive) voiceSticky = true else runCatching { openVoice() }
            } else {
                runCatching { closeVoice() }
            }
            runCatching { announce(listeningText()) }
            Log.i(TAG, "video-mode residency is now $wanted")
            return START_STICKY
        }
        val directory = intent?.getStringExtra(EXTRA_MODEL_DIR)
        val keywords = intent?.getStringExtra(EXTRA_KEYWORDS_FILE)
        if (directory.isNullOrEmpty() || keywords.isNullOrEmpty()) {
            fail("the model directory and the keywords file both have to be handed over")
            return START_NOT_STICKY
        }
        modelDirectory = File(directory)
        keywordsFile = File(keywords)
        // 四个数各有缺省 (见 [WakeTuning]), 而通道那条 op=start 仍可以逐个点名覆盖 —— 真机回调要用
        threshold = intent.getDoubleExtra(EXTRA_THRESHOLD, WakeTuning.KEYWORDS_THRESHOLD.toDouble()).toFloat()
        score = intent.getDoubleExtra(EXTRA_SCORE, WakeTuning.KEYWORDS_SCORE.toDouble()).toFloat()
        maxActivePaths = intent.getIntExtra(EXTRA_MAX_ACTIVE_PATHS, WakeTuning.MAX_ACTIVE_PATHS)
        trailingBlanks = intent.getIntExtra(EXTRA_TRAILING_BLANKS, WakeTuning.NUM_TRAILING_BLANKS)
        onWake = intent.getStringExtra(EXTRA_ON_WAKE) ?: WAKE_TO_APP
        vibrateMs = intent.getIntExtra(EXTRA_VIBRATE_MS, DEFAULT_VIBRATE_MS)
        onHit = intent.getStringExtra(EXTRA_ON_HIT) ?: LwWakeWord.onHit(this)
        // 视频模式那个记号: 服务起来时读一次, 它是"这一路要不要留着"的唯一来源
        voiceResidency = residentWanted()
        refreshVideoTarget()
        powerSave = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(false)
        WakeWordState.powerSave = powerSave
        // 服务重复起来时是"换一套参数重新开始", 不是再开一路: 先收掉上一个循环
        stopListening()
        if (powerSave) {
            // **省电模式: 服务与通知留着, 麦克风与唤醒词那一关着** (主人 2026-10-07)。点球那一条仍
            // 能用 ([listenNow] 里临时借一次), 而这一条链的看门狗照旧要跑 —— 借来的那一句说完也
            // 得有人把它收回去
            listening = false
            WakeWordState.listening = false
            WakeWordState.voiceActive = false
            startWatchdog()
            startSpeakingWatch()
            announce(listeningText())
            Log.i(TAG, "power save is on: the microphone stays closed until the ball asks for a sentence")
            return START_STICKY
        }
        if (!startListening()) return START_NOT_STICKY
        // 视频模式开着时, 那一条链跟着服务一起铺开 —— 那时它才是真的"留着" (那个模式要连着说),
        // 而不是被哪个开关顺带打开的
        if (voiceResidency) openVoice()
        return START_STICKY
    }

    override fun onDestroy() {
        stopPowerWatch()
        stopListening()
        // 开门之后那条"预热 + 通知"的待办跟服务一起走 (2026-10-10): [stopListening] 那条路上多半已经
        // 取消了它 (走快径时), 这里挡的是"窗口早就关了、而那条待办还挂在主线程上"那一种残余
        handler.removeCallbacks(settle)
        super.onDestroy()
    }

    /**
     * 起识别器与麦克风, 失败时给一句人话
     *
     * 顺序是刻意的: 先看权限, 再建识别器 (它会读模型, 慢), 最后才开麦克风 —— 这样"缺权限"
     * 与"模型坏了"分得开, 而麦克风一旦开了就一定有人在读它
     *
     * 只有唤醒词那一条是"起不来就别听了" (它是这个服务的门槛, 也是主人按下那个开关的意图),切段
     * 与出字是两条**尽力而为**的附加链: silero 模型没下、识别模型没下、原生库起不来, 都只记在
     * [VoiceState] 里, 服务照常听着唤醒词 —— 反过来做就会变成"没下模型导致喊不醒"
     */
    private fun startListening(): Boolean {
        val directory = modelDirectory ?: return false
        val keywords = keywordsFile ?: return false
        if (!hasMicrophone()) {
            fail("the microphone permission is not granted, so there is nothing to listen with")
            return false
        }
        val model = wakeWordModelOf(directory)
        if (model == null) {
            fail("no encoder/decoder/joiner/tokens set was found in ${directory.absolutePath}")
            return false
        }
        if (!model.complete) {
            fail("the model in ${directory.absolutePath} is incomplete: ${model.describe()}")
            return false
        }
        if (!keywords.isFile || keywords.readLines().none { it.isNotBlank() }) {
            fail("the keywords file ${keywords.absolutePath} is missing or empty")
            return false
        }
        val spotter = try {
            KeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model.encoder.absolutePath,
                            decoder = model.decoder.absolutePath,
                            joiner = model.joiner.absolutePath,
                        ),
                        tokens = model.tokens.absolutePath,
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                        modelType = "zipformer2",
                    ),
                    // 关键词走文件: 与 config 里那份默认词表是同一份, createStream 传空串就用它
                    keywordsFile = keywords.absolutePath,
                    keywordsScore = score,
                    keywordsThreshold = threshold,
                    // 四个数都是显式给的 (2026-10-09 调参): 后两个以前吃的是 sherpa 自己的缺省 (4 / 2),
                    // 而"连续帧确认"这件事就落在 numTrailingBlanks 上 —— AAR 不吐分数, 它是能做的那一半
                    maxActivePaths = maxActivePaths,
                    numTrailingBlanks = trailingBlanks,
                ),
            )
        } catch (error: Throwable) {
            fail("the keyword spotter would not come up: ${error.message ?: error}")
            return false
        }
        val stream = try {
            spotter.createStream("")
        } catch (error: Throwable) {
            spotter.release()
            fail("the keyword stream would not come up: ${error.message ?: error}")
            return false
        }
        this.spotter = spotter
        this.stream = stream
        val device = AudioCapture(::onCaptureError).also { capture = it }
        device.add(voiceSink)
        if (!device.start()) {
            // 麦克风起不来是这条链的唯一硬失败: 谁来读都没有音频了
            stopListening()
            fail(VoiceState.lastError ?: "the microphone would not open")
            return false
        }
        listening = true
        WakeWordState.listening = true
        // "守的是哪个数"要让 op=status 报得出来 (真机回调那几次全靠它)
        WakeWordState.threshold = threshold.toDouble()
        WakeWordState.score = score.toDouble()
        WakeWordState.maxActivePaths = maxActivePaths
        WakeWordState.trailingBlanks = trailingBlanks
        WakeWordState.keywords = keywords.readLines()
            .filter { it.isNotBlank() }
            .map { keywordName(it) }
            // 一张容错词表里好几条读音共用一个显示名: 不去重那句"正在听「…」"会把同一个词念好几遍
            .distinct()
        WakeWordState.startedAt = System.currentTimeMillis()
        WakeWordState.lastError = null
        WakeWordState.voiceAllowed = voiceResidency
        WakeWordState.voiceActive = false
        startWatchdog()
        startSpeakingWatch()
        announce(listeningText())
        Log.i(TAG, "listening for ${WakeWordState.keywords.joinToString()}, video residency: $voiceResidency")
        return true
    }

    /**
     * 把识别链铺开: 切段器、识别器与认字线程
     *
     * 两个调用方要的**不是同一件事**, 而差别就在这一次要不要留着:
     *
     * - **一次命中 / 球上点一下** ([openForOneSentence]): 用完就收, 闲置 [VOICE_IDLE_MS] 由
     *   [startWatchdog] 收回去
     * - **视频模式** ([residentWanted] 那个记号为真): 一直留着, 看门狗不收 —— 那个模式是"看着东西
     *   说话", 连着问才用得下去 (主人 2026-10-06 定的口径: 常驻语音只允许由视频模式触发)
     *
     * [sticky] 由调用方给, 但**开门之前还要再问一次记号** ([residentWanted]): "设置页那个开关 =
     * 常驻监听"那个错 (2026-10-05 修掉的) 的教训是"开门条件只能有一处说得清", 所以视频模式那条路
     * 命中的那一次也必须算常驻 —— 否则在视频模式里喊一声只买一句话, 下一句就得再喊一次
     */
    private fun openVoice(sticky: Boolean = false) {
        if (voiceActive) return
        // "把耳朵打开要多久"这个数 (主人 2026-10-09 问的那条延迟): 主体是切段器那条 ONNX 会话
        val began = System.currentTimeMillis()
        // **省电模式里这一路永远不常驻** (主人 2026-10-07: 省电模式就是"麦克风别一直开着"): 视频模式
        // 那个记号此刻也不作数 —— 一句话说完 [closeVoice] 就把麦克风还回去, 出了时段再谈常驻
        if (!powerSave && (sticky || residentWanted())) voiceSticky = true
        prepareSegmentation()
        prepareRecognition()
        startTranscribing()
        voiceActive = true
        voiceOpenedAt = System.currentTimeMillis()
        voiceIdleSince = voiceOpenedAt
        voiceHeardAt = 0L
        // 新开的一次: 还没说出任何一句, 所以闲置账是 10 s 那一档 (见 [voiceDelivered])
        voiceDelivered = false
        VoiceState.capturing = true
        WakeWordState.voiceActive = true
        // **预热与通知那两句延后 [VOICE_ROLLBACK_MS] 再说** (2026-10-10): 两笔都是"这一次开门真的听成
        // 了"才值得花的钱。一句都没听到就收回去那一条 (三击原来走的就是它) 由 [closeVoice] 把这条
        // 待办摘掉 —— 不摘的话, 那一次来回会白读一次几百 MB 的识别模型
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, VOICE_ROLLBACK_MS)
        Log.i(
            TAG,
            "the voice chain is up (VAD ${VoiceState.vadReady}, ASR ${VoiceState.asrReady}), sticky: $voiceSticky," +
                " opened in ${System.currentTimeMillis() - began}ms",
        )
    }

    /**
     * 把识别链收回去, 唤醒词接着守
     *
     * 与 [stopListening] 的分工: 那个是整件事收工 (唤醒词与麦克风一起), 这个只收那半条 —— 闲置超时
     * 到了、视频模式关掉了、或者主人手动收了, 都走这里, 而**麦克风不能停** (停了唤醒词也听不见了)
     *
     * **顺序是要紧的** (2026-10-06 的真机 crash 就是它错了一半): 先把手里那一份**交出去并放掉**,
     * 再收别的。切段器那半句要 `flush()` 交出去 (不是 `abandon()` 丢掉) —— 到这一刻主人可能刚把
     * 一整句话说完, 那半段正是有用的那句 —— 而 flush 与 release 都必须在**采集线程还可能在这一帧
     * 里转发**的前提下安全, 所以放在最前面: 那时 `vad` 还是个活的引用
     */
    private fun closeVoice() {
        if (!voiceActive) return
        voiceActive = false
        // **"开了又关"那两笔账** (2026-10-10): 一次人声都没听到就收回来时, 把那条延迟待办摘掉 ——
        // 预热与"听着你说这一句"两笔都还没发生, 于是这一次开门只留下一次状态回退
        val closedAt = System.currentTimeMillis()
        if (VoiceRollback.isFast(closedAt, voiceOpenedAt, voiceHeardAt, voiceDelivered, VOICE_ROLLBACK_MS)) {
            WakeWordState.fastRollbacks += 1
            handler.removeCallbacks(settle)
        }
        // 不论走没走快径都记一笔"这一声门是不是白开了": 确认窗落地之后它应该趋近于 0
        if (voiceOpenedAt != 0L && closedAt - voiceOpenedAt in 0..VOICE_SHORT_LIVED_MS) {
            WakeWordState.shortLivedOpens += 1
        }
        // 这个记号跟这一次开门同生共死: 下一次开门 (命中或视频模式) 会照着记号重新定
        // (先把它抄下来: 下面"省电模式里还麦克风"那一条要判"这一次是借来的还是常驻的")
        val wasSticky = voiceSticky
        voiceSticky = false
        flushVoice()
        VoiceState.pending = segments.size
        transcribing = false
        transcriber?.let { runCatching { it.join(WORKER_JOIN_MS) } }
        transcriber = null
        segments.clear()
        VoiceState.capturing = false
        WakeWordState.voiceActive = false
        reapRecognizer()
        // **这一次开门活到了收工**: 把"开窗口就把进程带走"那个记号清掉 (见 `LwWakeWord.speakNow`
        // 里的崩兜底) —— 不然连着正常用两句之后那个计数会一路涨到上限, 把好用的设备也锁住
        runCatching { LwWakeWord.noteVoiceClosed() }
        Log.i(TAG, "the voice chain is down again; only the wake word is listening now")
        // **省电模式里借来的那一句说完就把麦克风还回去** (主人 2026-10-07: "点球照样能说一句话"):
        // 借来的那一次开门是不常驻的 ([wasSticky] 为假), 所以这里就是"还"的那一刻。停的是采集与
        // 唤醒词那一路, 服务与通知都还在 —— [stopListening] 里那次 [closeVoice] 会因为 voiceActive
        // 已经是 false 而直接回头, 不会绕回来
        if (powerSave && listening && !wasSticky) {
            runCatching { stopListening() }
            runCatching { announce(listeningText()) }
            Log.i(TAG, "the borrowed sentence is over: the microphone is closed again (power save)")
        }
    }

    /**
     * 把这一句话的窗口交出去: **先把引用摘掉, 再动那个切段器**
     *
     * 这两句的顺序就是那条 use-after-free 的解药 ([voiceSink] 在采集线程上每帧读 `vad`):
     * `vad = null` 之后, 采集那一侧再怎么被调度也只会看到 null (干脆不转发); 而在那之前活着的
     * 那一个引用是**我们自己手里的这一份**, 所以 flush 与 release 都发生在"没有人还能拿到它"之后
     *
     * 与 `SpeechSegmenter.close()` 的幂等记号一起看: 那条保证重复收尾不会第二次碰原生
     */
    private fun flushVoice() {
        val segmenter = vad ?: return
        vad = null
        runCatching { segmenter.flush() }
        runCatching { segmenter.close() }
    }

    /**
     * 视频模式那个记号还在不在 (常驻语音唯一的开门条件)
     *
     * 读它而不是读一个字段, 是因为**服务可能停在切模式之前**: 视频模式开着的时候应用被杀掉再起来,
     * 服务得从磁盘上那份事实里恢复"这一路要留着", 而不是从 Intent 里 (那条路只在服务被显式起时才有)
     */
    private fun residentWanted(): Boolean = runCatching { LwWakeWord.residentWanted(this) }.getOrDefault(false)

    /**
     * 把"视频模式那一场"读进内存 ([videoTarget])
     *
     * 读它只有两个时机 —— 切模式那一拍 ([onStartCommand] 的 ACTION_REFRESH) 与服务起来那一拍 ——
     * 所以出字那一路 ([deliver]) 不必每一句都去开一次文件
     */
    private fun refreshVideoTarget() {
        videoTarget = if (voiceResidency) {
            runCatching { LwWakeWord.videoTarget(this) }.getOrNull()
        } else {
            null
        }
    }

    /**
     * 闲置太久就把这一句话的窗口收掉, 回到只守唤醒词
     *
     * 没有这一条的话, 一次命中就等于"识别链一直挂着" —— 麦克风一直吃、对话一直往下走, 那正是主人
     * 2026-10-06 点名不要的那件事。判据是"最后一段出字之后过了 [VOICE_IDLE_MS] 还没有新的活动", 而
     * **一次都没出过字时看的是开门那一刻**, 不然"开了门没人说话"会永远开着
     *
     * **[voiceSticky] 为真时不收**: 那是视频模式要的那一档 (主人明说那个模式要连着说), 超时只管
     * 一次命中 / 球上点一下买来的那一句
     */
    private fun startWatchdog() {
        // 出字与 VAD 说有人声都算一次活动 (见 [VoiceIdle], 2026-10-06 补的第二条): 只听出字的话,
        // 一口气说得比 [VOICE_IDLE_MS] 还长会被从句子中间收回
        if (watching) return
        watching = true
        watchdog = Thread({
            while (watching) {
                try {
                    Thread.sleep(WATCHDOG_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                // **常驻那一档掉了就按电平拉回来** (2026-10-10 主人: "她打开后听不了话了…当前语音是开的
                // 但输入不了话"): 视频模式要的就是"一直听着", 而那条链会被别处收掉 —— 新一轮那一下、
                // 球上/输入框上那个"收起来"、以及"切模式那一刻链子已经开着、随后的收尾"都算。只在
                // "说"的边沿上重开是不够的: 收了之后**没有人举手**, 于是球上写着「正在听」而麦是关的
                //
                // 放这里是因为这条钟本来就在跑, 而且判据全是现成的: 记号在 (videoResidency)、唤醒词还
                // 在守 (listening)、不是省电模式、链子真的关着, 并且**这一刻没有在念** —— 念的时候不许
                // 拉, 那正是"说的时候关麦"那一档
                // **手机模式那条也一样兜**: 说之前开着而说完没拉回来的, 这里补上 (见 [micWasUpBeforeSpeaking])
                if (listening && !powerSave && !voiceActive && !ttsPlaying() &&
                    (voiceResidency || micWasUpBeforeSpeaking)
                ) {
                    micWasUpBeforeSpeaking = false
                    runCatching { openVoice() }
                    continue
                }
                if (!voiceActive || voiceSticky) continue
                // **助手还在想 / 还在念的时候不收** (主人 2026-10-09: "语音输入没说完自动退出")
                //
                // 两个判据都取球上那个**看得见**的状态: 「正在想」是宿主写进 `ball-phase.json` 那一笔账
                // ([OverlayState.phase], 与球上三个字同源), 「正在念」是 TTS 自己在跑 —— 都是"主人此刻
                // 还在等一句话说完"的样子, 这时候把麦收了, 他下一句就得重新喊唤醒词
                //
                // 收的代价也没有了: 两个状态都不在时这一笔闲置账从**那一刻**重新起算 (下面那两行),
                // 于是"想完了 / 念完了"之后仍然有完整的一份 [VOICE_IDLE_MS] 可以说下一句
                val now = System.currentTimeMillis()
                // **投出去一句之后就不挂起了** (见 [voiceDelivered]): 那时窗口只剩一条短尾巴, 主人要的
                // 是"发送问题后就收掉"; 挂起只管"还没把那一句说完"的那一段
                if (!voiceDelivered) {
                    val held = VoiceIdle.held(
                        thinking = OverlayState.phase == OverlayState.PHASE_THINKING &&
                            DshHost.status is HostStatus.Running,
                        // 与半双工那道闸同一个判据 (见 [LwSpeak.talking]): 引擎报"说完"之后喇叭还在响的
                        // 那一段也算"正在说", 否则这一笔闲置账会从那一段里开始走
                        speaking = LwSpeak.talking(this),
                    )
                    if (held) {
                        voiceIdleSince = now
                        voiceHeardAt = 0L
                        continue
                    }
                }
                val expired = VoiceIdle.expired(
                    now = now,
                    lastTextAt = voiceIdleSince,
                    // **人声照样算一次活动** (主人 2026-10-09: "要看'还在不在说'"): 投出去之后他要是
                    // 接着说, 人声每 32 ms 给这一笔账续一次期, 窗口就继续留着; 一停下来 [SENT_TAIL_MS]
                    // 那条短尾巴就走完。与"还没说到一句"那一档的差别只在**上限** (0.3 s 而不是 10 s)
                    // 与**不挂起** (见上面那个 `!voiceDelivered` 的分支)
                    lastVoiceAt = voiceHeardAt,
                    limit = VoiceIdle.limit(voiceDelivered, VOICE_IDLE_MS),
                )
                if (!expired) continue
                // 在采集线程之外收: closeVoice 会 join 认字线程, 在采集线程上等它就是把采集卡死
                runCatching { closeVoice() }
            }
        }, "lw-voice-idle").apply { start() }
    }

    private fun stopWatchdog() {
        watching = false
        watchdog?.let { runCatching { it.interrupt() } }
        watchdog = null
    }

    /**
     * 省电模式的观察者: **定时那一段只能靠时间自己走**
     *
     * 手动那个开关是设置页当场发 `ACTION_REFRESH` 过来的 (那一条会立刻 [applyPower]), 而"23:00 到了"
     * 这件事没有任何人会举手 —— 所以要有一条自己的钟。周期取 [POWER_POLL_MS]: 读两个偏好加一个时刻
     * 比较, 代价可以忽略, 而"到点最多晚十几秒"对省电这件事无所谓
     */
    private fun startPowerWatch() {
        if (watchingPower) return
        watchingPower = true
        powerWatch = Thread({
            while (watchingPower) {
                try {
                    Thread.sleep(POWER_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                val wanted = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(powerSave)
                if (wanted == powerSave) continue
                runCatching { applyPower(wanted) }
            }
        }, "lw-power-save").apply { start() }
    }

    private fun stopPowerWatch() {
        watchingPower = false
        powerWatch?.let { runCatching { it.interrupt() } }
        powerWatch = null
    }

    /**
     * 省电模式开 / 关那一步 —— 手动开关与定时那一段**共用这一处**
     *
     * 关的那一半 (省电模式生效): 那一句话的窗口先收 ([stopListening] 里那条次序), 唤醒词与麦克风
     * 跟着收 —— 服务本身、通知、浮标与 host 都不动 (主人 2026-10-07: "host、浮标、通知都留着")。
     *
     * 开的那一半 (出了时段): 许可还开着、模型还在就重新开始听 —— **许可不是这里给或收的**, 它只是
     * 照旧生效; 视频模式那个记号也一样, 出了时段照旧把常驻链铺回来
     *
     * 两个线程会走到这里 (主线程序列里的 `ACTION_REFRESH`, 与 [startPowerWatch] 那一拍), 所以它是
     * `@Synchronized` 的, 而且第一句就把值对上 —— 同一档重复进来直接回头
     */
    @Synchronized
    private fun applyPower(save: Boolean) {
        if (save == powerSave) return
        powerSave = save
        WakeWordState.powerSave = save
        if (save) {
            if (listening) {
                runCatching { stopListening() }
            } else {
                runCatching { closeVoice() }
            }
        } else {
            val ready = runCatching {
                LwWakeWord.allow(this) &&
                    WakeWordDownload.readyCount(this) == WakeWordDownload.files.size
            }.getOrDefault(false)
            if (ready && !listening) runCatching { startListening() }
            // 出了时段, 视频模式那个"留着"照旧算数
            if (voiceResidency && listening && !voiceActive) runCatching { openVoice() }
        }
        runCatching { announce(listeningText()) }
        Log.i(TAG, "power save is now $save (listening: $listening)")
    }

    /**
     * 认出实例还回去
     *
     * 那 240 MB 不该在只守唤醒词的时候一直占着 (纪律: 触发条件是唤醒词, 那代价也该跟着它走)
     * 下一段要认出时 [LwSpeech.recognize] 会自己再建一个, 所以这里失败也不影响什么
     */
    private fun reapRecognizer() {
        runCatching { LwSpeech.dispatch(this, buildJsonObject { put("op", "release") }) }
    }

    /**
     * 那个**常驻的**采集消费者: 唤醒词与切段共用同一帧音频, 半双工那道闸也在这里
     *
     * 喇叭正在说话时**谁都不许吃音频** ([VoiceState.speaking]): 不采就没有"把自己的声音录回去"
     * 这回事, 顺带也解决了唤醒词被自己念的那句话叫醒 (走法 A, 主人 2026-10-05 定: 认下"念回答那几秒
     * 叫不醒"这个代价, 换掉自唤醒的风险)。闸放在两个消费者之前 (而不是各自里面), 就是为了让
     * "不许吃"只写一次
     *
     * **合上时要作废, 不只是丢样本**: 直接 `return` 只丢了新来的音频, 而 VAD 手里那半句还留着 ——
     * 几秒后 TTS 结束, 新音频接上去就切成"前半句 + 后半句"拼起来的一段, 那一段会被当成主人刚说的
     * 话, 所以合上的时候让切段器把手里的作废掉 ([SpeechSegmenter.abandon]), 下一次说话从干净的一段
     * 开始, 唤醒词那一路跟着一起哑 (A 走法要的就是这个), 这里不为它开例外
     *
     * **它不许在"这一句话的窗口"开 / 关的时候被摘上摘下** (2026-10-06 的真机 crash 逼出来的
     * 一条): 原来切段那一路是一个只在 [openVoice] 里挂上去、[closeVoice] 里摘下来的 sink, 而
     * [AudioCapture.loop] 是**一边遍历一边调**的 —— 关掉那一刻正被调用的那一个已经拿到手, 于是
     * 它在 `vad.release()` 之后还是走了进去, 下一次 `acceptWaveform` 就是 use-after-free。真机上
     * 抓到的正是这一条 (七次 SIGSEGV, 都在 `lw-audio-captur` 线程的 `Vad.acceptWaveform` ->
     * `SpeechSegmenter.accept` -> `halfDuplex$lambda$0`)。主人看到的现象是"整个应用重启"
     *
     * 所以现在是: **这台 sink 与采集同生共死** (见 [startListening] / [stopListening]), 而
     * "这一句话的窗口开着没有"由 [vad] 那一个引用说 —— 关窗口时先把引用清掉, 再交给 [flushVoice],
     * 于是那一帧拿到的要么是 null (干脆不转发), 要么是一个活着的切段器
     *
     * **这一帧该走哪一条由 [HalfDuplex.forward] 说**, 不写在这里: 它是三条展开的 `if`, 而那三条
     * 判据 (闭闸时作废 / 闭闸时唤醒词跟着哑 / 开闸时两个消费者都喂) 是真的要一条条量的
     */
    private val voiceSink = AudioCapture.Sink { samples ->
        // **闸的判据是两个来源的并集**: [LwSpeak.talking] 说"我们的喇叭还在响" —— 三条引擎各自那条准的
        // 判据 (2026-10-16 起不看系统媒体流了: 别人的音乐不再把这道闸合上, 原来那一条会把麦克风白关
        // 十几秒), 而 [VoiceState.speaking] 还带一件它管不到的事: 开门那一声「嘀」
        // ([openForOneSentence]) 只置那个标记。只看后者时, 在线引擎念回答那几秒麦克风是开着的 ——
        // 她自己的回答被录回去、当成一句话投进会话 (真机上实测到的那条)
        when (HalfDuplex.forward(LwSpeak.talking(this) || VoiceState.speaking)) {
            // 在采集线程上: 这一句与 keywordSink / vad 那两个消费者是同一个线程, 所以与它们不冲突
            HalfDuplex.ABANDON -> vad?.abandon()
            HalfDuplex.ACCEPT -> {
                keywordSink.accept(samples)
                vad?.accept(samples)
            }
        }
    }

    /**
     * 唤醒词这一路消费者: 与原来那条循环一模一样, 只是音频从共享采集那来
     *
     * 它在采集线程上跑, 而 KWS 一次只认 100 ms (几十毫秒的活), 所以不会拖住采集
     *
     * **命中之后有一段静默窗** (2026-10-09 调参那一批, 判据见 [WakeDecision]): 阈值降到 0.01 之后
     * 同一个词被连着报、指令前半段被当成下一次唤醒都会变多, 所以窗里报出来的词**照样解码但不认**
     * (只把 [WakeWordState.suppressed] 加一)
     *
     * **为什么是"接着解码"而不是"干脆不喂"**: 不喂的话 `isReady` 会一直是真, 静默窗一过那条 while
     * 会把攒下来的整段音频一次性解完, 而里面正是刚刚那条指令 —— 反而当场误触发一次。接着解码、
     * 只是不认, 窗一过再 `reset` 一次就是干净的一段
     */
    private val keywordSink = AudioCapture.Sink { samples ->
        val spotter = spotter ?: return@Sink
        val stream = stream ?: return@Sink
        // 静默窗刚过的那一帧: 先把解码器手里那一截丢掉 (见上面那条注释), 再从干净的一段接着听
        val muted = !WakeDecision.accepts(System.currentTimeMillis(), kwsMutedUntil)
        if (mutedFrame && !muted) {
            spotter.reset(stream)
            Log.i(TAG, "the cooldown is over; the wake word starts from a clean segment again")
        }
        mutedFrame = muted
        stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val heard = spotter.getResult(stream).keyword
            if (heard.isBlank()) continue
            // 命中之后必须立刻复位, 不然同一个词会被连着报好几次
            spotter.reset(stream)
            // **每个词各自判一次**: 上面那次 hit 可能刚刚把静默窗立起来, 同一条 while 里再报一个词
            // 就不该算数了 (拿帧首那个 `muted` 去判会漏掉这一种)
            if (WakeDecision.accepts(System.currentTimeMillis(), kwsMutedUntil)) {
                hit(heard)
            } else {
                WakeWordState.suppressed += 1
                Log.i(TAG, "ignored \"$heard\": inside the cooldown after the last hit")
            }
        }
    }

    /**
     * 采集线程自己的一个记号: 上一帧在不在静默窗里
     *
     * **只有 [keywordSink] 那一根线程碰它**, 所以不必 `@Volatile`; 它只为"窗刚过"那一下服务, 用来
     * 决定要不要 `reset` 一次
     */
    private var mutedFrame = false

    /** 切段那一路消费者: 把整段话丢进队列就走, 不等识别 */
    private fun rememberSegment(samples: FloatArray) {
        VoiceState.segments += 1
        VoiceState.lastTruncated = vad?.lastWasTruncated ?: false
        if (!segments.offer(samples)) {
            // 队列满 = 这台设备认不过来,丢的是最新的那一段, 而丢了多少必须看得见
            VoiceState.dropped += 1
            Log.w(TAG, "the transcriber is behind: dropped a ${samples.size} sample segment")
        }
        VoiceState.pending = segments.size
    }

    /** 切段器: silero 模型不在或不对就只记原因, 不影响唤醒词 */
    private fun prepareSegmentation() {
        // 上一次留下的那个先收掉: [openVoice] 的开门判据是 `voiceActive`, 而 `closeVoice` 失败的
        // 那一条路上它可能还挂着一个 (同一条命: 引用先摘, 再动原生)
        flushVoice()
        val path = LwSpeech.vadModel(this)
        val refusal = SpeechSegmenter.inspect(path)
        if (refusal != null) {
            VoiceState.vadReady = false
            VoiceState.vadDetail = refusal
            Log.w(TAG, refusal)
            return
        }
        val created = try {
            // 静音长度走设置页那一档 (T15): 缺省还是量过的 0.8 s, 调小之后一句话会发得更早,
            // 代价是正常的停顿可能被切成两句 (施工单 §8.2)
            SpeechSegmenter.open(path, ::rememberSegment, ::noteVoice, VoiceSettings.silence(this))
        } catch (error: Throwable) {
            VoiceState.vadReady = false
            VoiceState.vadDetail = "the silero VAD would not come up: ${error.message ?: error}"
            Log.w(TAG, VoiceState.vadDetail)
            return
        }
        // **引用最后才立起来**: 采集线程是照着它决定要不要转发的, 所以一个"建到一半"的切段器不该
        // 被它看见
        vad = created
        VoiceState.vadReady = true
        VoiceState.vadDetail = "ready: ${created.minSilenceSeconds}s of silence ends a segment, "
            .plus("at most ${SpeechSegmenter.MAX_SPEECH_SECONDS}s each")
    }

    /**
     * VAD 说这一窗有人声 (采集线程上叫的): 唤醒窗口的闲置计时把它也算成一次活动
     *
     * 只写一个 `@Volatile` 的 long, 别在这里做任何重活 —— 每个有人声的窗口都会来一次 (32 ms 一窗)
     */
    private fun noteVoice() {
        voiceHeardAt = System.currentTimeMillis()
    }

    /** 出字那一段有没有模型: 没有就说清楚, 识别器那时**一个都不会建** (240 MB) */
    private fun prepareRecognition() {
        val ready = LwSpeech.ready(this)
        VoiceState.asrReady = ready
        VoiceState.asrDetail = if (ready) {
            "ready: the SenseVoice model is on disk and gets loaded by the first segment"
        } else {
            "the SenseVoice model is not downloaded yet: run lw_speech op=prepare once"
        }
        if (!ready) Log.w(TAG, VoiceState.asrDetail)
    }

    private fun onCaptureError(reason: String) {
        VoiceState.lastError = reason
        Log.w(TAG, reason)
    }

    private val preheating = AtomicBoolean(false)

    /**
     * 命中那一下把识别器先装进内存, 别等第一段
     *
     * SenseVoice 是 240 MB 量级, 第一次认要先把模型读进来 —— 那一下是一到几秒, 不预热的话这笔
     * 账正好落在**主人说完第一句话之后**: 他等的那段就凭空多出好几秒 (2026-10-05 主人反馈"说话
     * 结束到发送等得太久"里的一段), 命中之后他还要说一句话, 那几秒正好与说话重叠, 所以这里是
     * 拿时间换时间
     *
     * 三条纪律: **另起一条线程** (在采集线程上装模型就是几百毫秒不读麦克风, 唤醒词那一路会哑掉)、
     * **一次只有一个** ([preheating] 挡住连击)、**失败只记不抛** (识别模型没下时预热必然不成,
     * 而那不影响唤醒词 —— 与 [prepareRecognition] 那句"尽力而为"是同一件事)
     */
    private fun preheat() {
        if (!VoiceState.asrReady) return
        if (!preheating.compareAndSet(false, true)) return
        Thread({
            try {
                LwSpeech.warmUp(this)
                Log.i(TAG, "the recogniser is in memory, so the first segment will not wait for it")
            } catch (error: Throwable) {
                Log.w(TAG, "preheating the recogniser failed: ${error.message ?: error}")
            } finally {
                preheating.set(false)
            }
        }, "lw-voice-warmup").start()
    }

    /**
     * 认字那条线程: 一段一段地认, 一次一段
     *
     * 单独一条线程是必须的: SenseVoice 认一句要几百毫秒, 放在采集线程上就是几百毫秒不读麦克风,
     * 队列留着上限, 满时丢最新的那一段并计数 —— 悄悄丢比报错更难查
     */
    private fun startTranscribing() {
        if (transcribing) return
        transcribing = true
        transcriber = Thread({ transcribeLoop() }, "lw-voice-asr").apply { start() }
    }

    private fun transcribeLoop() {
        // 收工那一刻还要把队列里的认完: 主人停下来之前说的最后一句通常正是有用的那句
        while (transcribing || segments.isNotEmpty()) {
            val segment = try {
                segments.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                return
            } ?: continue
            VoiceState.pending = segments.size
            val text = try {
                LwSpeech.recognize(this, segment)
            } catch (error: Throwable) {
                VoiceState.lastError = "recognising a segment failed: ${error.message ?: error}"
                Log.w(TAG, VoiceState.lastError!!, error)
                null
            } ?: continue
            if (text.isBlank()) continue
            VoiceState.recognized += 1
            deliver(text)
        }
    }

    /**
     * 出字之后去哪儿: 投进队列, 由宿主那侧读走并送进会话
     *
     * 这一句只做"投出去"这一件事, 而**投没投进去要如实说**: 写失败 (磁盘满 / 目录建不出来) 与
     * 投出去了对主人是两回事, 所以失败时状态里留下原因, 通知栏也照它改
     *
     * **唤醒之后的头一句带一个记号**: 记号只给**命中之后的第一句** —— 一句话被 VAD 切成两段时要是
     * 两段都带, 就会多标一句。**它不再决定新开一场** (2026-10-06 主人改口径: "只要是 1 小时内, 无论
     * 点球/喊唤醒词 都只在同一场对话"; 2026-10-07 这个数改成 20 分钟, 2026-10-11 缩到 10 分钟): 挑会话由宿主那侧按
     * `voice/session.json` 那笔账定
     *
     * **投给哪一场由 [VoiceRoute] 一处说了算**: 视频模式常驻时用定格的那一场 ([videoTarget]), 其余
     * 时候用回复框点名的那一场 ([voiceTarget]) —— 回复框那个记号和头一句一起清 (读一次就消费掉), 而
     * 定格那条在整段常驻里每一句都算数 ("打开视频模式"与之后说的话必须落在同一场, 主人 2026-10-07)
     */
    private fun deliver(text: String) {
        VoiceState.lastText = text
        VoiceState.lastAt = System.currentTimeMillis()
        voiceIdleSince = System.currentTimeMillis()
        // **这一句投出去了**: 从这一刻起这一次开门只剩一条短尾巴 ([WakeTuning.SENT_TAIL_MS]) ——
        // 主人 2026-10-09 那条"说完后发送问题, 正在听状态没有结束, 应该结束掉"就是它。再听到人声
        // ([noteVoice]) 会把这一笔清掉, 那说明下一句还在说
        voiceDelivered = true
        // **这一句话的第一段出字了**: 命令尾到此为止, 把静默窗收到冷却下限 ([WakeDecision]) ——
        // 一次命中只收一次, 后面几段出字不再动它 (一句话被 VAD 切成两段时会来第二次)
        if (kwsAwaitingTail) {
            kwsMutedUntil = WakeDecision.afterDelivery(
                now = VoiceState.lastAt,
                hitAt = WakeWordState.lastHitAt,
                mutedUntil = kwsMutedUntil,
            )
            kwsAwaitingTail = false
        }
        // 读一次就消费掉: 头一句之后回到老规矩 (投给最近动过的那个会话)
        val fresh = wakeWindow
        wakeWindow = false
        val target = VoiceRoute.target(voiceTarget, videoTarget, voiceResidency)
        voiceTarget = null
        val seq = VoiceInbox.append(this, text, SOURCE_VOICE, fresh, target)
        if (seq == null) {
            VoiceState.lastError = "could not queue \"$text\": the voice inbox file is not writable"
            Log.w(TAG, VoiceState.lastError!!)
        } else {
            VoiceState.delivered += 1
            VoiceState.lastSeq = seq
            Log.i(
                TAG,
                "segment ${VoiceState.recognized} queued as #$seq" +
                    (if (fresh) " (the head sentence after a wake)" else "") + ": $text",
            )
        }
        runCatching { announce(listeningText()) }
    }

    /**
     * 听到了一次
     *
     * **第一件事是开门, 别的都排在它后面** (2026-10-09 主人: "听到唤醒词到语音输入间隔多长, 可以再
     * 缩短吗")。原来那一串是"先让人知道 (震动 / 通知 / 点亮屏幕), 再开语音", 而**点亮屏幕那一步
     * 最多占住采集线程 600 ms** ([HIT_WAKE_BUDGET_MS]) —— 那 600 ms 里麦克风没人读, 主人喊完紧接着说
     * 出口的头几个字就是这样丢的。现在顺序改成:
     *
     *   1. [openForOneSentence]: 先把切段器与认字线程铺开 (耳朵打开)
     *   2. 震动 / 通知: 当场的手感与看得见的记录
     *   3. 点亮屏幕与解锁: **另起一条线程**, 不再占着采集线程
     *   4. 把球叫出来 / "叫醒之后"那句命令
     *
     * 任何一步失败都不该把监听带走, 所以各自 runCatching
     */
    private fun hit(keyword: String) {
        WakeWordState.hits += 1
        WakeWordState.lastKeyword = keyword
        val now = System.currentTimeMillis()
        WakeWordState.lastHitAt = now
        Log.i(TAG, "heard $keyword (${WakeWordState.hits} so far)")
        // **去抖那笔账在这里立起来, 而且排在下面那些活之前**: 下面有一步点亮屏幕最多占住采集线程
        // 600 ms, 静默窗要从"听到"那一刻起算, 不能从那一串活干完起算
        kwsMutedUntil = WakeDecision.afterHit(now)
        kwsAwaitingTail = true
        // **耳朵先打开**: 这一句排在最前, 后面那几步都不许再挡着麦克风 (见上面那段)
        runCatching { openForOneSentence() }
        if (vibrateMs > 0) runCatching { buzz(vibrateMs.toLong()) }
        runCatching { announce(heardText(keyword)) }
        // **点亮屏幕与解锁整段丢到后台** (批次 5 那条的收尾): 点亮是同步的 (它有预算, 而且"屏幕亮了
        // 没有"决定重放要不要跑), 而解锁重放本来就在它自己的 worker 上 —— 但这一句以前跑在采集线程
        // 上, 屏幕熄着时那几百毫秒等于麦克风没人读。整段挪到自己的线程之后, 耳朵已经开着、音频在
        // 缓冲里等着, 屏幕晚几百毫秒亮不影响主人说那一句
        if (LockSetting.wakeScreen(this)) {
            Thread({ runCatching { LockReplay.onWake(this, HIT_WAKE_BUDGET_MS) } }, "lw-wake-screen").start()
        }
        runCatching { wake(keyword) }
        runCatching { afterHit() }
    }

    /**
     * 一次唤醒 (或球上点一下) 要的那一句话
     *
     * 三件事都要做, 而理由各不相同:
     *
     * - **那一声短提示音占住半双工那一小段**: 不占的话它会被麦克风录进去, 而主人紧接着说的第一句
     *   话就带着一声"嘀"进识别器 (占着的那一百多毫秒里采集照跑、只是 VAD 那边作废)
     * - **[openVoice] 是开门**: 唤醒词的全部意义就是"喊一声然后说话", 所以**这一句的机会永远给** ——
     *   要把那句话认出来就得把切段与出字铺开。开的那一次只买一句话: 闲置 [VOICE_IDLE_MS] 由
     *   [startWatchdog] 收回去
     * - **`wakeWindow` 只给头一句**: 一句话被 VAD 切成两段时两段都带记号就会开出两个对话
     * - **回复框点名的那一场在同一个地方取** ([OverlayState.replyTarget]): 点球与唤醒词命中都走这
     *   一个函数, 所以"框在屏上时说的那一句投给框那一场"两条路自动一致; 取的那一刻也算一次
     *   "有人用框" (开语音要给那 20 s 的空闲账续期, 主人 2026-10-06 点名的那一条)
     */
    private fun openForOneSentence() {
        val began = System.currentTimeMillis()
        VoiceState.speaking = true
        try {
            beep(BEEP_MS)
        } finally {
            VoiceState.speaking = false
        }
        // 开窗口这一刻取一次: 框可能在说话的这几秒里被收掉, 而这一句的方向不该跟着变
        val target = OverlayState.replyTarget()
        voiceTarget = target
        if (target != null) OverlayState.noteBoxActivity()
        wakeWindow = true
        openVoice()
        // **主人问的那条延迟就是这一行** (2026-10-09): 从"命中/点球那一下"到"耳朵真的开着"的毫秒数。
        // 命中那一次另外把"听到唤醒词"那一刻也带上 —— 两个数之差就是 KWS 接受那一小段
        val now = System.currentTimeMillis()
        val hit = WakeWordState.lastHitAt
        Log.i(
            TAG,
            "the ears are open ${now - began}ms after the tap/wake" +
                (if (hit in 1..now && now - hit < 15_000) ", ${now - hit}ms after the hit" else ""),
        )
    }

    /**
     * 手动让它听一句 (浮标上点一下)
     *
     * 与 [hit] 同一条开门路, 只是没有"听到某个词"这件事: 不震动、不报"听到", 而它同样给一句话的
     * 机会 (投给哪一场见 [openForOneSentence]: 回复框在屏上时是框那一场, 否则由宿主那侧按 10 分钟
     * 那一笔账定) —— 球与唤醒词在主人看来是同一件事: "我要说话了"
     */
    private fun listenNow() {
        if (!listening) {
            // **省电模式里点球那一条** (主人 2026-10-07: "点球照样能说一句话"): 麦克风平时是关着的,
            // 点球就是"借一次"的意思 —— 把采集与唤醒词那一路临时拉起来, 这一句说完由 [closeVoice]
            // 还回去。缺权限 / 缺模型时 [startListening] 自己会说清缺哪一条 (fail 那条路)
            if (!powerSave || !startListening()) {
                VoiceState.lastError = "the wake word service is not listening yet, so there is nothing" +
                    " to open the voice chain on"
                Log.w(TAG, VoiceState.lastError!!)
                return
            }
        }
        runCatching { openForOneSentence() }
        runCatching { announce(listeningText()) }
    }

    /**
     * 命中之后除了叫醒还要做的 ([onHit], 批次 4.4)
     *
     * 后两条**不是在这里切模式**, 而是把一句规范命令写进收件箱 ([VoiceCommands]): 命令词表只有一份,
     * 在宿主插件里 (可行性稿 2.7), 而插件是按 seq 顺序读的 —— 这条命令比主人随后说的那句话先落进
     * 文件, 所以"切模式"一定发生在那一句话被投进会话之前
     */
    private fun afterHit() {
        val line = when (onHit) {
            LwWakeWord.HIT_VIDEO -> VoiceCommands.VIDEO
            LwWakeWord.HIT_PHONE -> VoiceCommands.PHONE
            else -> return
        }
        val seq = VoiceInbox.append(this, line)
        if (seq == null) {
            VoiceState.lastError = "the hit asked for \"$line\" but the voice inbox is not writable"
            Log.w(TAG, VoiceState.lastError!!)
            return
        }
        Log.i(TAG, "the hit also asked for \"$line\" (#$seq)")
    }

    /**
     * 听到之后把谁叫起来: **先是浮标那颗球, 而球起来就是去听**
     *
     * 主人 2026-10-05 的口径: "唤醒词只唤醒该浮标的语音输入, 不触发任何其他入口或功能"。所以这一
     * 条现在只做两件事——**把球放出来 + 让它进聆听那一档**([OverlayService.ACTION_LISTEN]), 而
     * 语音链本身早在这之前就已经开门了 ([hit] 里的 [openForOneSentence]), 球那一下只是"同一个
     * 开关从另一头按了一遍" —— 两条路走的是**同一份实现** ([listenNow] 与球上点一下完全一样)
     *
     * 这里**不再把 `MainActivity` 提到前台**: 唤醒是"我要说一句话", 不是"我要看界面"; 界面那半
     * 条是**双击回复框** (2026-10-07 起替掉了长按菜单里那一行)。浮标没开着 (没放球 / 缺悬浮窗授权 /
     * 主人把开关关着) 时
     * 才退回应用那一条老路, 否则一次唤醒会什么都不发生
     */
    private fun wake(keyword: String) {
        val host = DshHost.status
        if (onWake == WAKE_TO_OVERLAY && host is HostStatus.Running && Settings.canDrawOverlays(this) &&
            BallSpot.on(this)
        ) {
            val intent = Intent(this, OverlayService::class.java).apply {
                // **这一条不叫 ACTION_LISTEN**: 那一个在球上是一个开关 (说"再点一下收回来"), 而命中
                // 时这一句话的窗口**已经开着**了 ([openForOneSentence] 刚开过) —— 发 ACTION_LISTEN
                // 会把刚开的那一次收掉, 于是头一句话谁都听不见。叫 ACTION_SHOW 只是"把球放出来",
                // 而它出来时球上那三个字正是"正在听" (状态词读的是 VoiceState.capturing)
                action = OverlayService.ACTION_SHOW
                putExtra(OverlayService.EXTRA_URL, host.url)
            }
            ContextCompat.startForegroundService(this, intent)
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_HEARD, keyword)
        }
        // 从后台把一个 Activity 提到前台, 官方豁免之一就是握着 SYSTEM_ALERT_WINDOW;
        // 这台机器上没有悬浮窗授权的构建会在这里被拦, 那就只剩通知栏那一条路
        startActivity(intent)
    }

    private fun stopListening() {
        listening = false
        stopWatchdog()
        stopSpeakingWatch()
        // 这一句话的窗口先收: 它自己的收尾要等麦克风还开着 (flush 出来的最后一段得有人认), 而它一收
        // 就把 [vad] 那一个引用清掉, 之后没有任何一帧还会转发给切段器
        runCatching { closeVoice() }
        // 采集再停: 它同时喂着唤醒词与切段, 反过来的话手上那半段话会被下一帧接上, 切成一段怪的
        capture?.let { device ->
            device.remove(voiceSink)
            device.stop()
        }
        capture = null
        stream?.let { runCatching { it.release() } }
        stream = null
        spotter?.let { runCatching { it.release() } }
        spotter = null
        wakeWindow = false
        // 去抖那笔账跟着这一条链一起清: 服务停了再起来是全新的一段, 不该继承上一次的静默窗
        kwsMutedUntil = 0L
        kwsAwaitingTail = false
        VoiceState.capturing = false
        WakeWordState.listening = false
        WakeWordState.voiceActive = false
    }

    /**
     * 半双工那道闸什么时候开合的, 通知栏也得跟着改
     *
     * [listeningText] 里有一句"正在念回答, 先不听" —— 那是主人点过名要如实说的状态 (纪律: 静音是
     * 一个状态, 不是一个看不见的副作用), 而那道闸是 `LwSpeak` 那一侧合上的, 它不会回来叫我们
     * 所以这里用一个 400 ms 的观察者盯 `VoiceState.speaking` 那一跳, **只在真的变了的时候**才重发
     * 通知 (念一句通常几秒, 所以这是几次通知, 不是几十次)
     *
     * 为什么不是让 `LwSpeak` 直接调一下: 那个类在 `tool` 包里, 通知是这边私有的东西, 从那边伸手
     * 要跨一个包再往回叫; 而这条链本来就在轮询好几个 `@Volatile` 状态量 (设置页那边也是一秒一次)
     */
    private fun startSpeakingWatch() {
        if (watchingSpeech) return
        watchingSpeech = true
        speakingWatch = Thread({
            var was = ttsPlaying()
            while (watchingSpeech) {
                try {
                    Thread.sleep(SPEAKING_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                val now = ttsPlaying()
                if (now == was) continue
                was = now
                // **"说"的时候把麦克风真关掉** (主人 2026-10-10: "在说时要关闭麦克风, 否则会录入,
                // 这个是全部模式都生效"): 帧级那道半双工闸只丢帧、麦克风还开着, 而这里把采集也整个
                // 收回去 —— 关麦之前那几帧由那道闸兜住 (助手自己念的声音进不了切段器)。
                // **重开只在常驻那一档** (视频模式): 那时"说完/掐断后重新拉起听"才是主人要的; 别的
                // 模式里一句说完就该歇着, 由点球或唤醒词再要下一句
                //
                // 判据用 [ttsPlaying] 而不是 `VoiceState.speaking`: 后者还包括开门那一声"嘀", 拿它
                // 关麦会把刚张开的窗口当场掐掉
                handler.post {
                    runCatching {
                        if (now) {
                            // **关麦之前记一笔**: 说完之后照这一笔把它拉回来 (手机模式下也要 ——
                            // 见 [micWasUpBeforeSpeaking])
                            micWasUpBeforeSpeaking = voiceActive
                            closeVoice()
                        } else if (listening && !powerSave && (voiceResidency || micWasUpBeforeSpeaking)) {
                            micWasUpBeforeSpeaking = false
                            openVoice()
                        }
                    }
                    runCatching { announce(listeningText()) }
                }
            }
        }, "lw-voice-notify").apply { start() }
    }

    /**
     * 现在有没有在放: [LwSpeak.talking] (三条引擎各自那条准的判据, 2026-10-16 起不看系统媒体流),
     * **不含那一声提示音** —— 提示音走 [VoiceState.speaking], 拿它当判据会把刚张开的窗口当场掐掉
     */
    private fun ttsPlaying(): Boolean = LwSpeak.talking(this)

    private fun stopSpeakingWatch() {
        watchingSpeech = false
        speakingWatch?.let { runCatching { it.interrupt() } }
        speakingWatch = null
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun buzz(ms: Long) {
        val manager = getSystemService(VibratorManager::class.java)
        val device = manager?.defaultVibrator ?: getSystemService(Vibrator::class.java)
        device?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * 命中那一声短提示音
     *
     * 手机可能扣在桌上或者静音, 所以它不是唯一的反馈 (震动与通知都在), 但它是"当场就听见"的那一条。
     * 造一个用完就放: ToneGenerator 会占着音频输出, 而命中一次与下一次之间可能隔着几小时 —— 留着
     * 一个常驻的实例换不来什么。放的那一下要等它响完, 所以放在另一条线程上睡一下再放, 不挡命中
     * 之后的唤起
     */
    private fun beep(ms: Int) {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, ms)
        Thread {
            runCatching { Thread.sleep(ms + 200L) }
            runCatching { generator.release() }
        }.start()
    }

    /**
     * 前台服务: 类型里必须带 microphone
     *
     * 后台起步时带 while-in-use 类型的那一半可能被系统拒 (Android 14 起从后台起麦克风前台服务
     * 是受限的, 持有 `SYSTEM_ALERT_WINDOW` 是豁免之一, 但不是所有 ROM 都认), 那就退到 specialUse
     * 并把代价记在状态里 —— 前台时麦克风照常, 退到后台可能就拿不到了。这一条与 OverlayService
     * 是同一个写法, 只是那边丢掉的是浮窗里的录音, 这边丢掉的是整件事本身
     */
    private fun fend() {
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listeningText()), both)
            WakeWordState.microphoneForeground = true
        } catch (error: Throwable) {
            Log.w(TAG, "the microphone foreground type was refused, falling back to specialUse", error)
            WakeWordState.microphoneForeground = false
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(listeningText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    /**
     * 通知栏那一行: 唤醒词与识别链各自的状态都在这里, 而且必须分得开
     *
     * 这条通知是"一直开着的麦克风"唯一的可见处 (纪律: 一眼看得见、一下就关得掉), 所以它要说清
     * **哪几条链真的在跑**, 三种组合各有各的说法: 只有唤醒词在守 / 识别链也在跑 (视频模式那个常驻档
     * 或刚买的那一句话) / 正在念回答 (那一小段半双工里谁都不听, 是主人点过名要如实说的状态, 不是
     * 一个看不见的副作用)
     *
     * **文案 2026-10-06 改回来说"常驻语音"**: 那一条链路随常驻语音的删除消失过一次, 主人随即定了新
     * 口径 —— 常驻语音留着, 但只允许视频模式触发 (设置页那个开关与球菜单那行都删了)。所以这里又要
     * 说得清"是常驻的"还是"就这一句", 因为两者对"麦克风会不会一直开着"是两回事
     */
    private fun listeningText(): String {
        val words = WakeWordState.keywords
        // 通知的头一句就是"在听哪个词" (2026-10-06 主人点的那一句): 正在听「肥鱼肥鱼」
        val names = if (words.isEmpty()) "麦克风开着" else "「${words.joinToString("」「")}」"
        val listening = if (words.isEmpty()) names else "正在听$names"
        // **省电模式: 麦克风关着** (主人 2026-10-07) —— 喊不醒是有意的, 所以通知栏要这么写,
        // 而不是装作在听; 点球那一条仍然在, 那句话也得说出来
        if (powerSave && !voiceActive) return "省电模式 · 麦克风关着 · 点球还能说一句"
        // 正在念回答: 这期间唤醒词与识别链都被那道半双工的闸关着, 叫不醒 —— 这一档不能报"正在听"
        // (判据与那道闸同一个: [LwSpeak.talking] 把"引擎说完了而喇叭还在响"那一段也算进来)
        if (LwSpeak.talking(this)) return "$names · 正在说话, 先不听"
        // 只有唤醒词在守 (纯 KWS 态): 通知就是那一句"正在听「…」"
        if (!voiceActive) {
            return when {
                // 视频模式的记号开着而这一路还没起来: 缺件要说清是哪一件
                voiceResidency && !VoiceState.vadReady -> "$listening · 常驻语音没就绪"
                voiceResidency && !VoiceState.asrReady -> "$listening · 识别模型还没下"
                else -> listening
            }
        }
        // 开着的时候分两种: 常驻 (视频模式要的那一档) 与"命中之后那一句的机会"(用完闲置超时就收)
        val how = if (voiceSticky) "常驻语音在跑 (视频模式)" else "听着你说这一句"
        val heard = VoiceState.lastText
        return when {
            heard != null -> "$listening · $how · 刚听到: $heard"
            !VoiceState.vadReady -> "$listening · $how · 切段没就绪"
            !VoiceState.asrReady -> "$listening · $how · 识别模型还没下"
            else -> "$listening · $how"
        }
    }

    private fun heardText(keyword: String): String = "听到「$keyword」"

    /** 通知栏那一条常驻: 文字随状态走, 上面一个「停止」 */
    private fun announce(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "唤醒词", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun fail(reason: String) {
        WakeWordState.lastError = reason
        WakeWordState.listening = false
        Log.w(TAG, reason)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "io.github.yuloong07star.luwi.wake.STOP"

        /**
         * 把"这一句话的窗口"收回来, 唤醒词接着守
         *
         * 会话界面那个麦克风胶囊与球上"再点一下"走的都是这一条。**这个名字原来叫 `ACTION_STOP_VOICE`**
         * (2026-10-06 改的): 常驻语音删掉之后, 它收的不再是"常驻那一半", 而是主人此刻正在说的那一句 ——
         * 叫 HUSH 才不会让人以为还有一条常驻的东西可以关
         */
        const val ACTION_HUSH = "io.github.yuloong07star.luwi.wake.HUSH"

        /** 设置页改过"叫醒之后"之后来这一条: 服务不重启, 只把新值读进来 */
        const val ACTION_REFRESH = "io.github.yuloong07star.luwi.wake.REFRESH"

        /** 浮标上点一下那条路: 不经过唤醒词, 直接开一次"讲一句话"的窗口 (批次 4.2) */
        const val ACTION_LISTEN_NOW = "io.github.yuloong07star.luwi.wake.LISTEN_NOW"
        const val EXTRA_ON_HIT = "onHit"
        const val EXTRA_MODEL_DIR = "modelDir"
        const val EXTRA_KEYWORDS_FILE = "keywordsFile"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_SCORE = "score"
        const val EXTRA_MAX_ACTIVE_PATHS = "maxActivePaths"
        const val EXTRA_TRAILING_BLANKS = "numTrailingBlanks"
        const val EXTRA_ON_WAKE = "onWake"
        const val EXTRA_VIBRATE_MS = "vibrateMs"

        /** 听到之后干什么: 把应用提到前面, 或者把浮窗叫起来 */
        const val WAKE_TO_APP = "app"
        const val WAKE_TO_OVERLAY = "overlay"

        /** 从 MainActivity 那一侧读得到的最后听到的词, 供界面显示 */
        const val EXTRA_HEARD = "heard"

        /** 投递给宿主那行话的来源标记: 会话里据此看得出这是说出来的, 不是打字的 */
        const val SOURCE_VOICE = "voice"

        /** 关键词检测是 16 kHz / 80 维 fbank, 与模型训练时那几个数对不上就什么都听不出来 */
        private const val FEATURE_DIM = 80

        /**
         * 唤醒命中之后, 点亮屏幕那一步最多等多久 (批次 5)
         *
         * 这一句跑在采集线程上, 所以这个预算是"麦克风能被拖住多久": KEYCODE_WAKEUP 通常几百毫秒就
         * 亮了, 而等不到就该让开 —— 解锁那一段自己走它自己的线程, 屏幕晚一点亮不影响它
         */
        private const val HIT_WAKE_BUDGET_MS = 600L

        /** 四个 KWS 参数 (阈值 / 加分 / 挂帧 / 候选路数) 的缺省都在 [WakeTuning] 那一份表里, 这里不抄第二遍 */
        private const val DEFAULT_VIBRATE_MS = 500

        /** 命中那一声短提示音: 一个系统自带的气泡音, 够短也够认得出, 音量取 ToneGenerator 的 0..100 */
        private const val BEEP_MS = 120
        private const val TONE_VOLUME = 80

        /**
         * 等着认的段最多几段
         *
         * 一段最长 15 s, 而认一段是几百毫秒到几秒 —— 8 段是"这台设备明显认不过来"的那个量级,
         * 到那时丢新的并计数, 而不是让内存跟着一起涨
         */
        private const val SEGMENT_QUEUE = 8

        /** 转写线程取队列的等待: 只影响收工那一刻的响应, 不影响延迟 */
        private const val POLL_MS = 200L

        /**
         * "这一句话"的窗口闲置多久就收回去, 回到只守唤醒词
         *
         * 主人 2026-10-05 定的: 先定 20 s, 真机上看过之后改成 **10 s** —— 这个数是"说完最后一句之后
         * 它还在吃麦克风"的那段时间, 越短越省电与隐私 (而它只影响"想一下再说第二句"能拖多久)。
         * 没有这一条的话一次命中就等于识别链一直挂着, 那正是主人 2026-10-06 判定多余的那件事
         *
         * **浮标与它共用同一条**: 球上点一下走的是 [openForOneSentence], 与命中完全同一条路, 所以
         * "闲置 10 s 自动关"在两条路上是同一个数、同一段代码 (需求: 两条真实生效的关闭路径之一);
         * 另一条是手动关 ([ACTION_HUSH] → [closeVoice], 球上再点一下与输入框上沿那个胶囊都是它),
         * 两条互不冲突 —— 谁先到谁关, 关完再看门狗那一条什么都不做 (`voiceActive` 已经是 false)
         *
         * **活动有两种** (2026-10-06 补上的第二条): 出一句字, 或者 VAD 说这一窗有人声 —— 只看前者的
         * 话, 一口气不停顿地说得比这个数还长, 会在说到一半时被收回 (VAD 要静音 0.8 s 才出字,
         * 见 [VoiceIdle])
         *
         * **这个数只管"还没说到一句"的那一段** (2026-10-09 补): 一句话出字投出去之后 ([voiceDelivered])
         * 超时换成 [WakeTuning.SENT_TAIL_MS] (0.3 s), 而且不再因"它在想 / 它在念"而挂起 —— 人声照样
         * 续期, 所以"还在说"不会从中间被收掉, 一停下来就收
         */
        internal const val VOICE_IDLE_MS = 10_000L

        /**
         * "开了又关"那条快径的窗口: **300 ms** (2026-10-10)
         *
         * [openVoice] 的那两笔"还没开始的东西" (识别模型预热、通知栏那一句) 都延迟这个数再说, 而这个
         * 数之内收窗 (且这一段里一个人声都没听到) 就走快径 ([VoiceRollback.isFast]) —— 两笔一起取消,
         * 于是"开了又关"只剩一次状态回退, 没有通知闪烁、也不会白读一次模型
         *
         * 300 ms 这个量级来自原来的症状: 主人三击的那三下就在 220~300 ms 一档里走完 (见 [BallFeel]),
         * 而这个窗口还要盖住"确认窗没能挡住的那几条路" (唤醒词刚打开就又被新一轮收掉那一种)
         */
        internal const val VOICE_ROLLBACK_MS = 300L

        /**
         * 开门之后这么久之内就被收掉的算"短命的一次": **500 ms**
         *
         * 它只进读数 ([WakeWordState.shortLivedOpens]), 不影响任何动作 —— 快径窗口是
         * [VOICE_ROLLBACK_MS], 比它窄。两个数分开是因为"有没有白读模型"与"这一声门是不是白开了"
         * 本来就是两件事, 而确认窗落地之后**这一个数应该趋近于 0**
         */
        internal const val VOICE_SHORT_LIVED_MS = 500L

        /**
         * 看门狗的检查节拍
         *
         * 2026-10-09 从 2 s 收到 **500 ms**: 投出去一句话之后那一档的超时只有
         * [WakeTuning.SENT_TAIL_MS] (300 ms) —— 2 s 的节拍会让"发送问题后收掉"最多晚两秒才发生, 而
         * 主人对那个状态很敏感。一拍只读两个内存里的数, 快一点不花什么
         */
        private const val WATCHDOG_MS = 500L

        /**
         * 盯 `VoiceState.speaking` 的节拍
         *
         * 400 ms 是"半双工那道闸开合"能被看见、又不至于刷通知的那个量级: 念一句通常几秒, 所以一次
         * 朗读只换来两条通知 (开始念 / 念完了)
         */
        private const val SPEAKING_POLL_MS = 400L

        /**
         * 省电模式那一拍的周期 (见 [startPowerWatch])
         *
         * 一次 tick 只是读两个偏好加一次时刻比较, 所以 15 s 这种量级足够密: "到点最多晚十几秒"对
         * 省电这件事没有任何影响, 而手动那个开关根本不走这一条 (设置页当场发 `ACTION_REFRESH`)
         */
        private const val POWER_POLL_MS = 15_000L

        private const val WORKER_JOIN_MS = 2000L

        private const val TAG = "LwWakeWord"
        private const val CHANNEL_ID = "lw-wake"

        /** 与浮窗 (3) 和工具通知分开的第三条常驻 */
        private const val NOTIFICATION_ID = 4
    }
}
