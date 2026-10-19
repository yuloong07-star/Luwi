package io.github.yuloong07star.luwi.tool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.util.Capability
import io.github.yuloong07star.luwi.util.PermissionGate
import io.github.yuloong07star.luwi.voice.VoiceInbox
import io.github.yuloong07star.luwi.voice.VoiceLive
import io.github.yuloong07star.luwi.voice.VoiceSettings
import io.github.yuloong07star.luwi.voice.VoiceState
import io.github.yuloong07star.luwi.wake.WakeWordDownload
import io.github.yuloong07star.luwi.wake.WakeWordModel
import io.github.yuloong07star.luwi.wake.WakeWordService
import io.github.yuloong07star.luwi.wake.WakeWordState
import io.github.yuloong07star.luwi.wake.WakeWordWords
import io.github.yuloong07star.luwi.wake.PowerWindow
import io.github.yuloong07star.luwi.wake.WakeTuning
import io.github.yuloong07star.luwi.wake.keywordName
import io.github.yuloong07star.luwi.wake.symbolsOf
import io.github.yuloong07star.luwi.wake.unknownTokens
import io.github.yuloong07star.luwi.wake.wakeWordModelOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 唤醒词: 一直听着麦克风, 听到某个词就把 dsh 叫起来
 *
 * 这一层只管三件事 —— 模型在不在、词表写了什么、服务起没起。真正的监听与识别在
 * [WakeWordService] 里, 识别器是 [LwSpeech] 同一份 sherpa-onnx, 只是换成了 `KeywordSpotter`
 *
 * 词表那一半有个坑必须先说: sherpa-onnx 的 keywords 文件每一行是**模型的 token 序列**加一个
 * `@显示名`, 写中文原文进去不会报错, 只会被静默丢掉 —— 于是"照做了但永远不触发"。所以这一层
 * 拿模型自带的 tokens.txt 逐行对一遍, 对不上的 token 当面报出来, 不装作写成功了
 */
internal object LwWakeWord {

    /** 模型落在 filesDir 下的这一层, 与宿主侧的插件是约定, 谁都不许单边改 */
    const val MODEL_ROOT = "wake-word"

    /** 缺省那一套: 中文的 zipformer KWS, 3.3M 参数 */
    const val MODEL_NAME = "kws-zipformer-wenetspeech-3.3M"

    /** 词表文件名, 与模型自带的那个同名: 认的是内容, 不是名字 */
    private const val KEYWORDS_FILE = "keywords.txt"

    /** 设置页那一份人写的词表 (友好格式) 存在与别处同一个偏好文件里 */
    private const val STORE = "luwi"
    private const val WORDS_KEY = "wake-words"

    /** "视频模式要求识别链留着"那个记号的位置: 与 `modes/.active` 同一个目录 (见 [residentWanted]) */
    private const val RESIDENT_DIR = "modes"
    private const val RESIDENT_MARK = "voice-resident.on"

    /**
     * 视频模式里说的话投给哪一场 (定格的, 见 [rememberVideoTarget]): 与 [RESIDENT_MARK] 同一个目录
     *
     * 与那个记号同生共死: 进视频模式时写, 出视频模式时删。存成文件是为了"服务被杀掉再起来"那一档 ——
     * 记号还在而目标丢了, 视频模式里说的第一句话就会落回浮标账本, 而那正是主人要统一掉的东西
     */
    private const val VIDEO_TARGET_MARK = "video-voice.session"

    /**
     * 允许不允许唤醒 (缺省开着)
     *
     * 现在**只剩这一个许可**: 它只决定服务起不起来 —— 起来之后那条"说一句话"的窗口是命中 (或球上点
     * 一下) 才开的, 而且用完就收 (`WakeWordService.VOICE_IDLE_MS`)。**"允许常驻语音"那第二个许可
     * 已经删掉了** (2026-10-06): 它管的是"识别链留不留着", 而那条一直不收的链 (切段 + 出字, 那份
     * 240 MB 的模型与一直吃着的 CPU) 正是主人判定多余的东西 —— 对话不该一直进行下去
     */
    private const val ALLOW_WAKE_KEY = "wake-allow"

    /**
     * 命中之后干什么 (批次 4.4): 只叫醒 / 顺带切到视频模式 / 顺带切回手机模式
     *
     * 这三个值就是设置页那一个下拉的东西。**"切模式"那两条不是在这里做**, 而是往收件箱写一句
     * 命令 ([VoiceCommands]): 命令词表只有一份, 在宿主插件里 (见那个文件的注释)
     */
    private const val ON_HIT_KEY = "wake-on-hit"

    /** 命中时震不震一下 (缺省开) */
    private const val VIBRATE_KEY = "wake-vibrate"

    /**
     * **省电模式** (主人 2026-10-07 定): 只停唤醒词监听那一条 —— 麦克风整个关掉, 喊不醒; host / 浮标 /
     * 通知都留着, **点球照样能说一句话** (那一次是临时把麦克风借来, 说完就还)。缺省关
     *
     * 它与「允许唤醒」是两件事: 那一个是"我许可你听", 这一个更像主人此刻的"现在别听" —— 关掉省电
     * 之后原有的许可照旧生效
     */
    private const val POWER_SAVE_KEY = "wake-power-save"

    /**
     * **省电时连自动指令的监测一起停** (第 15 条, 批次 2 先落位)
     *
     * 与上面那个省电开关挨着放, 因为它是同一个"省电"的第三条; 真正读它的是批次 8 那几个监测器
     */
    private const val POWER_SAVE_AUTOMATION_KEY = "wake-power-save-automation"

    /**
     * **定时省电那一段**: `23:00-07:00` 这种一句, 空 = 没开定时
     *
     * 主人 2026-10-07 点名的形状 ("定时开关, 设置页就行, 由用户自己定时间"): 到点自己进省电模式,
     * 出了时段自己回来 —— 时段的解析与判定是 [PowerWindow] 那一份纯算术
     */
    private const val POWER_WINDOW_KEY = "wake-power-window"

    /**
     * 连着开两次语音窗口而两次都把进程带走之后, 这一版就不要再自动开了
     *
     * 它防的是**宿主自己转圈**: 原生那一份 (sherpa) 在某个环境里开语音窗口就崩, 而系统会把应用
     * 拉起来, 于是一点一下球就重启一次。2026-10-06 那条 use-after-free 已经堵上了 (见
     * `WakeWordService.voiceSink`), 这个是**兜底**: 崩过一次的进程里, 30 s 之内再开一次还崩,
     * 那就不再开, 并留一句人话
     *
     * 记号**活在进程里, 不落盘**: 进程一起来它就清了 —— 主人重启应用就是"再给我试一次"的意思,
     * 而落盘的那个记号会让人在修好之后还得去清文件
     */
    @Volatile
    private var voiceOpenAttempts = 0

    /** 上一次开语音窗口的时刻 (用来判"这一次崩是它引起的") */
    @Volatile
    private var lastVoiceOpenAt = 0L

    /** 连着两次都在开窗口之后很快就崩了 */
    private const val OPEN_CRASH_WINDOW_MS = 30_000L

    private const val MAX_OPEN_ATTEMPTS = 2

    /** 命中之后只叫醒 (缺省: 与改动之前的行为一致) */
    const val HIT_WAKE = "wake"

    const val HIT_VIDEO = "video"
    const val HIT_PHONE = "phone"

    /** 这一个能力的名字, 与设置页里那条一致 */
    private val microphone = Capability(
        name = "麦克风",
        why = "唤醒词要一直听着麦克风",
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
    )


    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context, request)
        "keywords" -> keywords(context, request)
        "start" -> start(context, request)
        "stop" -> stop(context)
        // 常驻语音那一半: 切模式的脚本与插件用它 (见 [resident])
        "voice" -> resident(context, request)
        else -> throw IllegalArgumentException(
            "op has to be status, keywords, start, stop or voice, not \"$op\"",
        )
    }

    /** 模型在哪儿: 缺省 filesDir/wake-word/kws-zipformer-wenetspeech-3.3M, 也可以点名别处 */
    private fun modelDirectory(context: Context, request: JsonObject): File =
        request.stringOrNull("model")?.let { File(it) } ?: directory(context)

    /** 同一个缺省目录, 给不经过通道的调用方用 (下载器与设置页都要它) */
    internal fun directory(context: Context): File = File(File(context.filesDir, MODEL_ROOT), MODEL_NAME)

    private fun keywordsFile(directory: File): File = File(directory, KEYWORDS_FILE)

    /**
     * 设置页那一份词表: 存的格式与人输入的一样 (`词=带音调数字拼音`), **没存过就是空的**
     *
     * **它不再回退到缺省那一句** (2026-10-06 修的那条: "设置说明的唤醒词和真实的唤醒词不一致"):
     * 偏好里没存过的时候, 真正在守的其实是 `keywords.txt` 里那一份 —— 启动时由 `ensure` 照着
     * [WakeWordWords.defaultText] 写下去 (`if (names(context).isEmpty()) setWords(...)`), 而下载模型那
     * 一条路也会补写。如果这里自己回一句缺省, 两处只要有一处不同字, 设置页就会念出一个**没有一个字
     * 是真的**的词 (真机上就是这样: 说明里写着一个词而守的是另一个)
     *
     * 所以这里只答"人存过什么", 空的就是空 —— 要"现在真正在守的那一个"该读 [names] (它读的是
     * `keywords.txt`, 那是模型手里的那一份事实)
     */
    internal fun words(context: Context): String = context
        .getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(WORDS_KEY, null)
        ?.takeIf { it.isNotBlank() }
        .orEmpty()

    /**
     * 现在真正在守的那几个词 (显示名), 按 `keywords.txt` 那一行一个, **同名只留一个**
     *
     * 去重是因为缺省那张表就是四条同名读音 (见 [WakeWordWords.DEFAULT_WORDS]): 不去重时设置页那句
     * "正在听「…」"会把同一个词列四遍, 而人看到的应该是"在听哪一个词", 不是"表里有几行"
     *
     * **这是"哪一个词是真的"的唯一答案** —— 设置页那两句说"正在听「…」"就该念它。`keywords.txt`
     * 还没有时回空的, 由调用方决定退成什么 (设置页退成缺省那一句 + 一句"还没设过")
     */
    internal fun names(context: Context): List<String> {
        val file = keywordsFile(directory(context))
        if (!file.isFile) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { keywordName(it) }.distinct()
    }

    /**
     * 允许不允许唤醒 (那个缺省开着的许可)
     *
     * 它只决定**服务起不起来** —— 这是"设置项只作前置许可"落在代码里的样子, 而"这一句话的窗口"永远
     * 是命中 (或球上点一下) 才开的
     */
    internal fun allow(context: Context): Boolean = prefs(context).getBoolean(ALLOW_WAKE_KEY, true)

    /**
     * **视频模式要求把识别链留着** —— 常驻语音现在唯一的开门条件 (主人 2026-10-06 定的口径)
     *
     * 它一度是一个存盘的"许可" (偏好键 `wake-allow-voice`), 由设置页那个开关与浮标菜单那行「一直听」
     * 管; 那两条路都删掉了, 因为**在手机模式下打开它就等于对话一直进行下去**。留下的是视频模式那一
     * 条: 那个模式本来就是"看着东西说话", 连着问才用得下去。
     *
     * 记号落在 `$DSH_HOME/modes/` 下 —— 与 `.active` 同一个目录, 因为**写下它的是切模式那一步**
     * ([io.github.yuloong07star.luwi.channel.LwModes.set]), 而读它的是服务那一侧。存成文件而不是存
     * 成偏好, 是为了让"视频模式现在开着"与"这一路要留着"这两件事在磁盘上是同一份事实: 服务被杀掉再
     * 起来时照它恢复, 而不是靠一个可能与现实脱节的记号
     */
    internal fun residentWanted(context: Context): Boolean = residentMark(context).isFile

    /** 切模式那一步用它写/删 ([LwModes.set]): 视频模式写, 手机模式删 */
    internal fun setResident(context: Context, wanted: Boolean) {
        val mark = residentMark(context)
        if (wanted) {
            runCatching {
                mark.parentFile?.mkdirs()
                mark.writeText("video mode asked for the chain to stay\n")
            }
        } else {
            runCatching { mark.delete() }
        }
    }

    private fun residentMark(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), RESIDENT_DIR), RESIDENT_MARK)

    /**
     * **视频模式里说的话投给哪一场** (没有就是 null): 进视频模式那一刻页面正在看的那一场
     *
     * 主人 2026-10-07 的口径是"两条投递目标要统一": "打开视频模式"那句话是看着哪一场说的, 视频模式里
     * 接着说的每一句就该落在同一场 —— 而"看哪一场"只有页面自己知道 ([VoiceState.uiSession], 由注入的
     * 脚本一秒一次报上来), 所以定格发生在切模式那一步 ([rememberVideoTarget]), 这里只负责读
     */
    internal fun videoTarget(context: Context): String? = runCatching {
        videoTargetMark(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 进视频模式那一刻**定格**目标; 出视频模式时清掉
     *
     * 页面还没报过 (或那一刻没有选中的会话) 时写不出东西来: 那一档就删掉旧文件, 让投递落回原来的规则
     * (回复框点名 → 浮标账本), 而不是拿一个过期的 id 硬钉在那儿
     */
    private fun rememberVideoTarget(context: Context, wanted: Boolean) {
        val mark = videoTargetMark(context)
        if (!wanted) {
            runCatching { mark.delete() }
            return
        }
        val ui = VoiceState.uiSession?.takeIf { it.isNotBlank() }
        runCatching {
            if (ui == null) {
                mark.delete()
            } else {
                mark.parentFile?.mkdirs()
                mark.writeText(ui)
            }
        }
    }

    private fun videoTargetMark(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), RESIDENT_DIR), VIDEO_TARGET_MARK)

    /**
     * 记下那个许可
     *
     * **这里只写偏好, 什么都不启动** —— 许可与运行时状态是两件事 (改动之前设置页那个开关直接读
     * `WakeWordState.listening`, 于是"许可 = 常驻监听"在界面上就成立了), 服务那边会把这个值从
     * Intent 里读走, 而 Intent 由 [listen] 装
     */
    internal fun setAllow(context: Context, wake: Boolean) {
        prefs(context).edit().putBoolean(ALLOW_WAKE_KEY, wake).apply()
    }
    /** 命中之后干什么: [HIT_WAKE] / [HIT_VIDEO] / [HIT_PHONE], 认不出的值一律当"只叫醒" */
    internal fun onHit(context: Context): String = when (val asked = prefs(context).getString(ON_HIT_KEY, HIT_WAKE)) {
        HIT_VIDEO, HIT_PHONE -> asked
        else -> HIT_WAKE
    }

    /** 命中时震一下没有 (缺省开) */
    internal fun vibrate(context: Context): Boolean = prefs(context).getBoolean(VIBRATE_KEY, true)

    /** 省电模式那个手动开关开着没有 (缺省关; 定时那一段另算, 见 [powerSave]) */
    internal fun powerSaveManual(context: Context): Boolean =
        prefs(context).getBoolean(POWER_SAVE_KEY, false)

    /** 记下省电模式那个手动开关; 服务在跑时顺手让它现在就照新的值办 */
    internal fun setPowerSave(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(POWER_SAVE_KEY, on).apply()
        refresh(context)
    }

    /**
     * 省电时要不要连**自动指令的监测**一起停 (缺省开, 第 15 条)
     *
     * 语义按开发计划 2.15 那一节: 停的是**费电的那几种** (天气 / 地点 / 前台应用那几个轮询器),
     * 系统事件类的 (通知 / 时间 / 光感) 保留 —— 后者几乎不吃电, 而"到点了提醒我"正是省电时段里最
     * 需要的那一条。**这个开关此刻还不驱动任何东西**: 自动指令在批次 8 才落地, 这里先把主人的选择
     * 记下来, 那一批直接读它
     */
    internal fun powerSaveAutomation(context: Context): Boolean =
        prefs(context).getBoolean(POWER_SAVE_AUTOMATION_KEY, true)

    internal fun setPowerSaveAutomation(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(POWER_SAVE_AUTOMATION_KEY, on).apply()
    }

    /** 定时省电那一段的原文 (`23:00-07:00`; 空 = 没开) —— 设置页那一行就显示它 */
    internal fun powerWindowText(context: Context): String =
        prefs(context).getString(POWER_WINDOW_KEY, null)?.trim().orEmpty()

    /** 现在生效的那个时段 (解析不出来或起止相同时回 null, 见 [PowerWindow]) */
    internal fun powerWindow(context: Context): PowerWindow.Window? =
        PowerWindow.parse(powerWindowText(context))?.takeIf { !it.empty }

    /**
     * 现在在不在定时省电那一段里
     *
     * 起止相同 (空窗) 与解析不出来的写法一律**不算** —— 猜成"全天省电"会让麦克风静默地一直关着,
     * 而那样主人只会看到"喊不醒, 不知道为什么"
     */
    internal fun powerWindowActive(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val window = powerWindow(context) ?: return false
        return PowerWindow.inside(PowerWindow.minutesOfDay(now), window)
    }

    /**
     * 省电模式此刻该不该生效: **手动那个开关, 或者定时那一段**
     *
     * 服务那一侧每隔一拍读一次它 ([WakeWordService] 的省电观察者), 设置页那三行也读它
     */
    internal fun powerSave(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        powerSaveManual(context) || powerWindowActive(context, now)

    /**
     * 存下定时那一段; 看不懂就**什么都不写**, 回一句人话给界面念
     *
     * 空白 = 关掉定时 (那是主人清空那一行的意思), 所以它是一条约得住的合法输入
     */
    internal fun setPowerWindow(context: Context, text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            prefs(context).edit().remove(POWER_WINDOW_KEY).apply()
            refresh(context)
            return null
        }
        val window = PowerWindow.parse(trimmed)
            ?: return context.getString(R.string.settings_wake_power_window_bad)
        if (window.empty) return context.getString(R.string.settings_wake_power_window_empty)
        // 存主人写的那一句原样 (全角冒号也留着), 读的时候再解析 —— 回显与他写的一致
        prefs(context).edit().putString(POWER_WINDOW_KEY, trimmed).apply()
        refresh(context)
        return null
    }

    /**
     * 记下"命中之后干什么"与震不震
     *
     * 与那个许可一样, **这里只写偏好**: 服务那边会在收到 `ACTION_REFRESH` 时自己读一遍
     * ([refresh]), 所以改完不必重启监听
     */
    internal fun setHit(context: Context, action: String, vibrate: Boolean) {
        prefs(context).edit()
            .putString(ON_HIT_KEY, if (action == HIT_VIDEO || action == HIT_PHONE) action else HIT_WAKE)
            .putBoolean(VIBRATE_KEY, vibrate)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * 应用起来时照着许可把服务拉起来
     *
     * 没有这一条, "允许唤醒"就只是一个存盘的记号: 服务不会自己起, 那个许可开着也没人听 —— 而
     * 主人按下它的意思显然是"让它听着", 所以缺模型、缺权限、或者许可关着时这里什么都不做, 否则
     * 就起 (失败只记日志: 应用这一侧不该因为一个后台服务起不来而崩)
     */
    internal fun ensure(context: Context) {
        if (!allow(context)) return
        val ready = WakeWordDownload.readyCount(context) == WakeWordDownload.files.size
        if (!ready) return
        if (WakeWordState.listening) return
        runCatching { listen(context) }
            .onFailure { Log.w("LwWakeWord", "the listener did not come up on start: ${it.message}") }
    }

    /**
     * 收下设置页编辑过的那段文本: 先逐行对符号表, 全对才写, 写完把友好格式一起存下来
     *
     * 抛出来的句子直接给界面念 —— 对不上 token 时说清是哪一个, 不装作写成功了
     */
    internal fun setWords(context: Context, text: String): List<String> {
        val directory = directory(context)
        val table = File(directory, "tokens.txt")
        if (!table.isFile) {
            throw IllegalArgumentException("模型还没下, 没有符号表可以核词表 (先按「下载唤醒词模型」)")
        }
        val lines = WakeWordWords.lines(text, symbolsOf(table))
        if (lines.isEmpty()) throw IllegalArgumentException("词表不能是空的")
        directory.mkdirs()
        File(directory, KEYWORDS_FILE).writeText(lines.joinToString("\n") + "\n")
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(WORDS_KEY, text.trim())
            .apply()
        return lines.map { keywordName(it) }.distinct()
    }

    /** 引擎、模型与词表现在什么样: 页面与模型据此决定能不能开始听 */
    private fun status(context: Context, request: JsonObject): JsonObject {
        val directory = modelDirectory(context, request)
        val model = wakeWordModelOf(directory)
        val keywords = keywordsFile(directory)
        val lines = if (keywords.isFile) keywords.readLines().filter { it.isNotBlank() } else emptyList()
        val symbols = model?.tokens?.takeIf { it.isFile }?.let { symbolsOf(it) }.orEmpty()
        val unknown = lines.flatMap { unknownTokens(it, symbols) }.distinct()
        val refusal = PermissionGate.refusal(context, microphone)
        return buildJsonObject {
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put("keywordsPath", keywords.absolutePath)
            put("present", model?.complete == true)
            put("modelFiles", model?.describe() ?: "")
            put("symbols", symbols.size)
            put("keywords", lines.joinToString(" | "))
            put("keywordNames", lines.map { keywordName(it) }.distinct().joinToString(", "))
            put("unknownTokens", unknown.joinToString(" "))
            put("permission", refusal == null)
            put("listening", WakeWordState.listening)
            // 三层各自的状态: 唤醒词在守 (listening) / 视频模式那个记号 (allowVoice) / 识别链此刻留没
            // 留着 (voiceActive)。**"允许常驻语音"作为一个用户许可已经没有了** (2026-10-06): 现在只有
            // 视频模式能要求这一路留着, 所以 `allowVoice` 报的是那个记号
            put("allowWake", allow(context))
            put("allowVoice", residentWanted(context))
            // 视频模式里说的话投给哪一场 (进视频模式那一刻定格的) 与页面现在报上来的那一场:
            // 两个数一起看才说得清"两条投递目标统一了没有" (主人 2026-10-07)
            put("videoTarget", videoTarget(context) ?: "")
            put("uiSession", VoiceState.uiSession ?: "")
            // 省电模式那三件 (主人 2026-10-07): 手动开关 / 定时那一段 / 此刻该不该生效
            put("powerSave", powerSave(context))
            put("powerSaveManual", powerSaveManual(context))
            put("powerWindow", powerWindowText(context))
            put("powerWindowActive", powerWindowActive(context))
            // 命中之后干什么 (批次 4.4): 只叫醒 / 切到视频模式 / 切回手机模式, 外加震不震
            put("onHit", onHit(context))
            put("vibrate", vibrate(context))
            put("voiceAllowed", WakeWordState.voiceAllowed)
            put("voiceActive", WakeWordState.voiceActive)
            put("liveKeywords", WakeWordState.keywords.joinToString(", "))
            put("hits", WakeWordState.hits)
            // 被去抖吃掉几次 (2026-10-09 调参那一批): 与 hits 并排看才知道阈值放到了什么程度
            put("suppressed", WakeWordState.suppressed)
            // **"开了又关"那两笔账** (2026-10-10): `fastRollbacks` 是走了静默快径几次, 而
            // `shortLivedOpens` 是"开麦之后 500 ms 内就被收掉"几次 —— 确认窗落地之后后者应该趋近于 0
            // (三击那一条路压根不该再把窗口打开, 见 BallCommit)
            put("fastRollbacks", WakeWordState.fastRollbacks)
            put("shortLivedOpens", WakeWordState.shortLivedOpens)
            // **正在跑的**那四个 KWS 参数 (不是缺省表里的那一份): 真机回调时看它才对得上
            put("threshold", WakeWordState.threshold)
            put("score", WakeWordState.score)
            put("maxActivePaths", WakeWordState.maxActivePaths)
            put("numTrailingBlanks", WakeWordState.trailingBlanks)
            put("lastKeyword", WakeWordState.lastKeyword ?: "")
            put("lastHitAt", WakeWordState.lastHitAt)
            put("startedAt", WakeWordState.startedAt)
            put("microphoneForeground", WakeWordState.microphoneForeground)
            put("lastError", WakeWordState.lastError ?: "")
            // "说一句话"那一路的三个"成不成"与它切出来的东西: 采集 / 切段 / 出字
            put("capturing", VoiceState.capturing)
            put("vadReady", VoiceState.vadReady)
            put("vadDetail", VoiceState.vadDetail)
            put("asrReady", VoiceState.asrReady)
            put("asrDetail", VoiceState.asrDetail)
            put("segments", VoiceState.segments)
            put("recognized", VoiceState.recognized)
            put("pending", VoiceState.pending)
            put("dropped", VoiceState.dropped)
            put("lastTruncated", VoiceState.lastTruncated)
            put("lastText", VoiceState.lastText ?: "")
            put("lastAt", VoiceState.lastAt)
            put("delivered", VoiceState.delivered)
            put("lastSeq", VoiceState.lastSeq)
            put("speaking", VoiceState.speaking)
            // 批次 9 的两个设置与状态出口的读数 (T15 / T17): "静音档现在是多少"与"出口到底通不通"
            // 都不该只能去设置页看 —— 真机验收与排障读的就是这几行
            put("vadSilence", VoiceSettings.silence(context))
            put("exportEnabled", VoiceSettings.exportEnabled(context))
            put("exportPackage", VoiceSettings.exportPackage(context))
            put("exportTarget", VoiceSettings.exportTarget(context) ?: "")
            put("liveState", VoiceLive.state)
            put("liveSession", VoiceLive.session ?: "")
            put("liveFile", VoiceLive.file(context).absolutePath)
            put("inbox", VoiceInbox.file(context).absolutePath)
            put(
                "text",
                table(
                    listOf(
                        "model" to MODEL_NAME,
                        "directory" to directory.absolutePath,
                        "model on disk" to (model?.let { it.complete.toString() } ?: "no"),
                        "symbols in tokens.txt" to symbols.size.toString(),
                        "keywords" to (lines.joinToString(" | ").ifEmpty { "none" }),
                        "tokens not in the model's table" to (unknown.joinToString(" ").ifEmpty { "none" }),
                        "microphone permission" to (refusal ?: "granted"),
                        "allowed to wake" to allow(context).toString(),
                        "power save" to powerSave(context).toString(),
                        "power save switch" to powerSaveManual(context).toString(),
                        "power save window" to (powerWindowText(context).ifEmpty { "none" }) +
                            (if (powerWindowActive(context)) " (active now)" else ""),
                        // 这一条现在说的是"视频模式那个记号": 常驻语音唯一的开门条件
                        "resident voice asked for (video mode)" to residentWanted(context).toString(),
                        "after a hit" to onHit(context),
                        "buzz on a hit" to vibrate(context).toString(),
                        "listening" to WakeWordState.listening.toString(),
                        "resident voice running" to WakeWordState.voiceActive.toString(),
                        "hits" to WakeWordState.hits.toString(),
                        // 去抖吃掉几次, 以及正在跑的那四个 KWS 参数 (2026-10-09 调参那一批)
                        "hits dropped by the cooldown" to WakeWordState.suppressed.toString(),
                        "KWS tuning (threshold / score / paths / trailing blanks)" to
                            "${WakeWordState.threshold} / ${WakeWordState.score}" +
                            " / ${WakeWordState.maxActivePaths} / ${WakeWordState.trailingBlanks}",
                        "last heard" to (WakeWordState.lastKeyword ?: "nothing yet"),
                        // 这一句说的是"话正被听着" (常驻那一档或刚买来的那一句, 都在这个记号里)
                        "capture running" to VoiceState.capturing.toString(),
                        "silero VAD" to VoiceState.vadDetail,
                        "SenseVoice" to VoiceState.asrDetail,
                        "segments cut" to "${VoiceState.segments} (recognised ${VoiceState.recognized},"
                            .plus(" pending ${VoiceState.pending}, dropped ${VoiceState.dropped})"),
                        "last text" to (VoiceState.lastText ?: "nothing yet"),
                        "queued for the host" to "${VoiceState.delivered} line(s), last #${VoiceState.lastSeq}",
                        "inbox" to VoiceInbox.file(context).absolutePath,
                        "reading aloud" to VoiceState.speaking.toString(),
                        "last problem" to (WakeWordState.lastError ?: "none"),
                    ),
                ),
            )
        }
    }

    /**
     * 写词表
     *
     * 每一行都是 token 序列加 `@显示名`, 例如 `d à f éi y ú @大肥鱼`。逐行核对模型那张符号表, 有对不上
     * 的就整批不写 (半张词表比没有词表更难看: 有的词会触发, 有的永不触发, 而人说不出为什么)
     */
    private fun keywords(context: Context, request: JsonObject): JsonObject {
        val directory = modelDirectory(context, request)
        val file = keywordsFile(directory)
        val model: WakeWordModel? = wakeWordModelOf(directory)
        val symbols = model?.tokens?.takeIf { it.isFile }?.let { symbolsOf(it) }
            ?: unavailable(
                "writing the keywords table",
                "the model's tokens.txt is not in ${directory.absolutePath}, so there is nothing to" +
                    " check the keywords against: run `lw_wakeword op=prepare` first",
            )
        val asked = request.strings("lines").map { it.trim() }.filter { it.isNotEmpty() }
        if (asked.isEmpty()) {
            val current = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
            return buildJsonObject {
                put("written", false)
                put("keywordsPath", file.absolutePath)
                put("keywords", current.joinToString(" | "))
                put("text", "this table is what it is; name lines=<one line per keyword> to replace it")
            }
        }
        val malformed = asked.filterNot { it.contains('@') }
        if (malformed.isNotEmpty()) {
            throw IllegalArgumentException(
                "each line has to end with @ its display name, for example `d à f éi y ú @大肥鱼`;" +
                    " these do not: ${malformed.joinToString(" | ")}",
            )
        }
        val unknown = asked.flatMap { unknownTokens(it, symbols) }.distinct()
        if (unknown.isNotEmpty()) {
            unavailable(
                "writing the keywords table",
                "these tokens are not in the model's table, and sherpa-onnx would drop their whole" +
                    " line without saying so: ${unknown.joinToString(" ")}. Check the pinyin against" +
                    " the model's tokens.txt in ${directory.absolutePath}",
            )
        }
        directory.mkdirs()
        file.writeText(asked.joinToString("\n") + "\n")
        return buildJsonObject {
            put("written", true)
            put("keywordsPath", file.absolutePath)
            put("keywords", asked.joinToString(" | "))
            put("keywordNames", asked.map { keywordName(it) }.distinct().joinToString(", "))
            put("text", "the table now holds ${asked.size} keyword(s): ${asked.joinToString(" | ")}")
        }
    }

    /**
     * 开始听
     *
     * 麦克风那条权限先过闸: 缺权限时回的是"缺哪一条、怎么给", 不是起一个听不见的服务
     */
    private fun start(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, microphone)?.let { throw IllegalStateException(it) }
        val directory = modelDirectory(context, request)
        val model = wakeWordModelOf(directory)
        // **词表没有就先照着缺省那张表写一份** (2026-10-06): 原来这条路是"没有词表就拒绝启动", 而
        // `initialize` 那一处补写只在应用刚起来时走一次 —— 于是"重装之后自己起监听"这种次序会以
        // "there is no keywords table" 收场, 而设置页那两句念的又是另一份缺省。两处缺省只要有一处
        // 不同字, 屏幕上就会出现"说明里写着一个词、守的是另一个词"
        val keywords = keywordsFile(directory)
        if (model?.complete == true && (!keywords.isFile || keywords.readLines().none { it.isNotBlank() })) {
            runCatching { setWords(context, WakeWordWords.defaultText()) }
                .onFailure { Log.w("LwWakeWord", "the default keyword table was not written: ${it.message}") }
        }
        if (model?.complete != true) {
            unavailable(
                "starting the wake word",
                "the model is not in ${directory.absolutePath}: run `lw_wakeword op=prepare` first",
            )
        }
        if (!keywords.isFile || keywords.readLines().none { it.isNotBlank() }) {
            unavailable(
                "starting the wake word",
                "there is no keywords table at ${keywords.absolutePath}: run `lw_wakeword" +
                    " op=keywords lines=[...]` first",
            )
        }
        val intent = Intent(context, WakeWordService::class.java).apply {
            putExtra(WakeWordService.EXTRA_MODEL_DIR, directory.absolutePath)
            putExtra(WakeWordService.EXTRA_KEYWORDS_FILE, keywords.absolutePath)
            putExtra(WakeWordService.EXTRA_THRESHOLD, request.number("threshold", WakeTuning.KEYWORDS_THRESHOLD.toDouble()))
            putExtra(WakeWordService.EXTRA_SCORE, request.number("score", WakeTuning.KEYWORDS_SCORE.toDouble()))
            // 四个数里后两个以前吃的是 sherpa 自己的缺省 (4 / 2), 2026-10-09 起显式给并且可覆盖
            putExtra(
                WakeWordService.EXTRA_MAX_ACTIVE_PATHS,
                request.int("maxActivePaths", WakeTuning.MAX_ACTIVE_PATHS),
            )
            putExtra(
                WakeWordService.EXTRA_TRAILING_BLANKS,
                request.int("numTrailingBlanks", WakeTuning.NUM_TRAILING_BLANKS),
            )
            putExtra(
                WakeWordService.EXTRA_ON_WAKE,
                if (request.string("onWake", WakeWordService.WAKE_TO_APP) == WakeWordService.WAKE_TO_OVERLAY) {
                    WakeWordService.WAKE_TO_OVERLAY
                } else {
                    WakeWordService.WAKE_TO_APP
                },
            )
            // 命中时震不震: 设置页那个开关就是它的缺省值, 通道那条路仍可以点名覆盖 (vibrateMs=0 就是不震)
            putExtra(
                WakeWordService.EXTRA_VIBRATE_MS,
                request.int("vibrateMs", if (vibrate(context)) DEFAULT_VIBRATE_MS else 0),
            )
            // 命中之后干什么 (批次 4.4): 缺省取设置页存的那个
            putExtra(
                WakeWordService.EXTRA_ON_HIT,
                if (request.contains("onHit")) request.string("onHit") else onHit(context),
            )
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            unavailable("starting the wake word service", error.message ?: error.toString())
        }
        return buildJsonObject {
            put("started", true)
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put(
                "keywords",
                keywords.readLines().filter { it.isNotBlank() }.joinToString(" | "),
            )
            put(
                "text",
                "the service is up; the wake word is listening on the microphone and a hit shows up" +
                    " in status. A hit (or a tap on the ball) opens the cutting + recognition chain for" +
                    " ONE sentence, and that closes again ${WakeWordService.VOICE_IDLE_MS / 1000}s after" +
                    " the last thing it heard. **Video mode is the only thing that keeps that chain" +
                    " resident** (it is currently " +
                    (if (residentWanted(context)) "asked for" else "not asked for") +
                    "); the settings page no longer has a resident-voice switch of its own" +
                    // 省电模式里上面那一段不成立, 如实说 —— 否则"起好了"是一句假话
                    if (powerSave(context)) {
                        ". **Power save is on right now**: the service is up but the microphone stays" +
                            " closed, so nothing is being heard; tapping the ball still opens the chain" +
                            " for one sentence, and the settings page has the switch and the window"
                    } else {
                        ""
                    },
            )
        }
    }

    /** 停: 服务一停, 识别器、麦克风与那条常驻通知一起收 */
    private fun stop(context: Context): JsonObject {
        val stopped = hush(context)
        return buildJsonObject {
            put("stopped", true)
            put("hits", WakeWordState.hits)
            put("detail", if (stopped) "the listener was stopped" else "no listener was running")
        }
    }

    /**
     * 常驻语音那一半的开与关 —— **切模式那两个脚本与插件走这一条**
     *
     * 视频模式是它唯一的开门条件 (主人 2026-10-06 定的口径), 而"切进视频模式"这件事由谁发起不固定:
     * 模型调 `lw_mode`、主人说一句命令句、或者在设备上直接跑 `modes/video.sh`。三条路要的是同一件事,
     * 所以它们都落到这里, 而不是各写一遍。
     *
     * 两个记号一起写, 而且**先写文件再叫服务**:
     *
     * - `modes/voice-resident.on` 是那份"事实": 服务被杀掉再起来时照它恢复 ([residentWanted])
     * - 服务那一侧收到 `ACTION_REFRESH` 之后读这个文件, 当场把识别链铺开或收回去
     *
     * **on 而服务没在跑时如实说**: 那一条链长在唤醒词那一路的采集中间 (服务起了才有麦克风可切), 所以
     * 这种情形下记号写下了、链却起不来 —— 回执要把这句说清, 而不是报一个"开好了" (`[WakeWordState.listening]`)
     */
    private fun resident(context: Context, request: JsonObject): JsonObject {
        val (wanted, detail) = resident(context, request.bool("on", true))
        return buildJsonObject {
            put("on", wanted)
            put("active", WakeWordState.voiceActive)
            put("listening", WakeWordState.listening)
            put("marker", residentMark(context).absolutePath)
            put("detail", detail)
        }
    }

    /**
     * 上面那个动作的可用形态: **切模式那一步直接调它** (它不经过通道, 所以不拼 JSON)
     *
     * 回的是"现在这个记号是什么"与"一句人话": 切模式那一步要把后者说给主人 (或者模型) 听 ——
     * "记号设了而服务没在跑"这种半开状态最容易被误报成"开好了", 所以那一句必须说出来
     *
     * @param settle 等不等服务把这条链真的铺开 (缺省等): 应用那一侧是另一条线程在做这件事, 所以不等
     *   就只能报"已经叫它去开了"。**切模式那一步不等** (2026-10-06) —— 那 600 ms 是白等的, 而切模式
     *   要快; `wakeword op=voice` 那条通道 (人在设备上按一下、要看到真相) 走缺省
     */
    internal fun resident(context: Context, wanted: Boolean, settle: Boolean = true): Pair<Boolean, String?> {
        setResident(context, wanted)
        // **进视频模式那一刻把"投给哪一场"定格下来** (主人 2026-10-07: 两条投递目标要统一), 出视频
        // 模式时清掉。它排在这里是因为三条路 (lw_mode / 命令句 / 设备上的脚本, 还有 `wakeword op=voice`)
        // 都落到这一个函数上 —— 定格点只有这一处
        rememberVideoTarget(context, wanted)
        val listening = WakeWordState.listening
        if (listening) refresh(context)
        // 服务在另一条线程上做这件事 (onStartCommand 之后还要建识别器), 给它一拍再读状态
        if (listening && settle) runCatching { Thread.sleep(RESIDENT_SETTLE_MS) }
        val active = WakeWordState.voiceActive
        val detail = when {
            !wanted && !active -> "the resident voice chain is off; a hit buys one sentence again"
            !wanted -> "the marker is gone and the service was told to close the chain"
            active -> "the resident voice chain is up (video mode): keep talking, no wake word needed"
            !listening ->
                "the marker is set, but the wake word service is not listening, so there is no" +
                    " microphone to cut yet; it opens by itself once the listener is up"
            !settle ->
                "the marker is set and the service was told to open the chain; it comes up in a" +
                    " moment (op=status says when)"
            else ->
                "the marker is set and the service was told to open the chain; it is not up yet" +
                    " (the silero / SenseVoice models may be missing - see op=status)"
        }
        return residentWanted(context) to detail
    }

    /**
     * 起监听, 用缺省那几个数
     *
     * 设置页那个开关走这一条, 与通道那条 ([start]) 是同一个实现 —— 界面与模型各有一套参数的话,
     * "开关开着而模型那边调到别处"这种状态迟早会出现
     *
     * **起监听永远不会顺手把识别链打开**: 那条链只在命中唤醒词 (或球上点一下) 时由服务自己开, 用完
     * 就收; 要它一直留着只有视频模式那一条路 ([resident])
     *
     * **2026-10-05 起 `onWake` 的缺省是"浮标"而不是"应用"** (主人: 唤醒词只唤醒该浮标的语音输入,
     * 不触发任何其他入口或功能): 于是"设置页那个开关打开唤醒词"这条路也叫醒浮标那颗球。真相是
     * 缺省值本身不决定什么 —— 服务那一侧 [WakeWordService.wake] 还要看三件事 (浮标那个开关存着、
     * 悬浮窗授权在、host 在跑), **三件里有一件不成立就退回应用那一条老路**, 所以没有浮标的人照旧
     * 得到"把界面叫起来"这唯一做得到的事
     */
    internal fun listen(context: Context): JsonObject = start(
        context,
        buildJsonObject {
            put("threshold", WakeTuning.KEYWORDS_THRESHOLD)
            put("score", WakeTuning.KEYWORDS_SCORE)
            put("maxActivePaths", WakeTuning.MAX_ACTIVE_PATHS)
            put("numTrailingBlanks", WakeTuning.NUM_TRAILING_BLANKS)
            put("vibrateMs", DEFAULT_VIBRATE_MS)
            put("onWake", WakeWordService.WAKE_TO_OVERLAY)
        },
    )

    /** 停监听: 回"有没有真的停掉一个" */
    internal fun hush(context: Context): Boolean {
        val stopped = context.stopService(Intent(context, WakeWordService::class.java))
        WakeWordState.listening = false
        VoiceState.capturing = false
        return stopped
    }

    /**
     * "叫醒之后"改了之后让正在跑的那个服务知道
     *
     * 服务是从 Intent 里读那个值的, 所以改完不告诉它, 就得等下一次起服务才生效 —— 而"我改成了切视频
     * 模式, 它却要等我关掉再打开一次"是个说不通的中间态, **已经在跑时才发这一条**
     */
    internal fun refresh(context: Context) {
        if (!WakeWordState.listening) return
        val intent = Intent(context, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_REFRESH)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w("LwWakeWord", "the listener did not take the new settings: ${it.message}") }
    }

    /**
     * 手动让它听一句话 (浮标上点一下那条路, 批次 4.2)
     *
     * 回 null = 已经发出去了; 否则是**拒绝的理由**, 调用方原样说给主人听 —— 点了一下却什么都不发生
     * 是最不能接受的那种失败, 所以缺模型 / 缺权限 / 正在念回答这三种情形各有各的说法
     *
     * 服务没起时先把监听起起来: 两条 `startForegroundService` 是按顺序送到同一个服务实例上的
     * (`onStartCommand` 在主线程序贯执行), 所以"先起服务、再让它听"不会撞在一起, 而 `listen()`
     * 抛出来的那一句 (缺权限 / 缺模型) 就是拒绝的理由
     */
    internal fun speakNow(context: Context): String? {
        PermissionGate.refusal(context, microphone)?.let { return it }
        val ready = WakeWordDownload.readyCount(context) == WakeWordDownload.files.size
        if (!ready) return context.getString(R.string.ball_speak_no_model)
        if (VoiceState.speaking) return context.getString(R.string.ball_speak_speaking)
        // **崩了两次之后就别再开了**: 见 [MAX_OPEN_ATTEMPTS] —— 一点球就重启一次应用比"语音用不了"
        // 糟得多, 而这件事必须说出来 (主人点了一下却什么都没有, 那是另一种更坏的体验)
        val lastAttempt = lastVoiceOpenAt
        if (voiceOpenAttempts >= MAX_OPEN_ATTEMPTS &&
            System.currentTimeMillis() - lastAttempt < OPEN_CRASH_WINDOW_MS
        ) {
            VoiceState.lastError = "the voice chain brought the app down twice in a row: not opening it" +
                " again in this run (restart the app to try once more)"
            Log.w("LwWakeWord", VoiceState.lastError!!)
            return VoiceState.lastError
        }
        if (!WakeWordState.listening) {
            val failure = runCatching { listen(context) }.exceptionOrNull()
            if (failure != null) return failure.message ?: failure.toString()
        }
        val intent = Intent(context, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_LISTEN_NOW)
        return try {
            // 记账在发出去**之前**: 这条 Intent 到服务那一侧会同步地把识别链铺开 (`onStartCommand`
            // 在服务的主线程上), 而"崩"就发生在铺开之后的那一小段里 (见 [voiceOpenAttempts])
            lastVoiceOpenAt = System.currentTimeMillis()
            voiceOpenAttempts += 1
            ContextCompat.startForegroundService(context, intent)
            null
        } catch (error: Throwable) {
            context.getString(R.string.ball_speak_failed, error.message ?: error.toString())
        }
    }

    /**
     * 那个窗口**活过了它自己那一段** (闲置超时收回去 / 主人手动收了): 崩的记号清掉
     *
     * 由 `WakeWordService` 在收窗口那两处调 ([WakeWordService.closeVoice] 与 `ACTION_HUSH` 那条路):
     * 一次开门只要正常活到收工, 就说明"开窗口"这个动作本身没问题, 于是下一句照旧给 —— 不然连着正常
     * 用两句之后那个计数会一路涨到上限, 把好用的设备也锁住
     */
    internal fun noteVoiceClosed() {
        voiceOpenAttempts = 0
    }

    /**
     * 把"这一句话的窗口"收回来 (浮标上"再点一下"那条路, 或输入框上沿那个胶囊)
     *
     * 走的是同一条动作 (`ACTION_HUSH`): 唤醒词接着守 —— 想再要一次"开口说话", 再点一下球或者喊一声
     * 都回来。整件事收工是通知栏那个「停止」
     *
     * 名字原来是 `stopVoice` (2026-10-06 改的): 它收的从来就不是"常驻语音", 而是主人此刻正在说的
     * 那一句; 常驻语音删掉之后那个旧名字更是会把人指错方向
     */
    internal fun hushWindow(context: Context): Boolean {
        val intent = Intent(context, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_HUSH)
        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrElse { error ->
            Log.w("LwWakeWord", "the one-sentence window was not closed: ${error.message}")
            false
        }
    }

    /** 四个 KWS 参数的缺省在 [WakeTuning] 那一份表里, 这里只剩震动这一个数 */
    private const val DEFAULT_VIBRATE_MS = 500

    /**
     * [resident] 写完记号之后等服务一拍再读状态的那个数
     *
     * 铺开这一条链要先建 silero 切段器与认字线程 (服务在它自己的主线程上做), 所以"我叫它开了"与"它
     * 真的开了"之间隔着几十到几百毫秒。不等的话回执里那个 `active` 永远是 false, 而切模式那一步要
     * 拿它决定要不要说"现在可以连着说了" —— 报错了比不报更坏 (与 lw_* 那批"写完读回"是同一条纪律)
     */
    private const val RESIDENT_SETTLE_MS = 600L
}
