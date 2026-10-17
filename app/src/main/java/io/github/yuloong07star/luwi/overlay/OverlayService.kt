package io.github.yuloong07star.luwi.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.animation.doOnEnd
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import io.github.yuloong07star.luwi.MainActivity
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.channel.CameraOwner
import io.github.yuloong07star.luwi.channel.LwModes
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import io.github.yuloong07star.luwi.plugin.widget.WidgetHost
import io.github.yuloong07star.luwi.host.BallReturn
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.HostStatus
import io.github.yuloong07star.luwi.tool.LwSpeak
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.voice.VoiceCommands
import io.github.yuloong07star.luwi.voice.VoiceInbox
import io.github.yuloong07star.luwi.voice.VoiceState
import io.github.yuloong07star.luwi.wake.WakeWordService
import io.github.yuloong07star.luwi.wake.WakeWordState

/**
 * 浮标现在什么样: 通道方法 `overlay` 的 state 就读这里
 *
 * `phase` 是**宿主推来的**那一个 (见 host-plugin 的 `turn/start` / `turn/end`): "在想"只有宿主知道,
 * 应用这一侧没有第二条路能看出"一轮正在跑"
 */
internal object OverlayState {

    const val PHASE_IDLE = "idle"
    const val PHASE_THINKING = "thinking"

    /** 球在不在 */
    @Volatile
    var showing: Boolean = false

    /** 输入条展开没有 */
    @Volatile
    var expanded: Boolean = false

    /** 输入通道 (那块自己画的文字框) 开着没有 */
    @Volatile
    var channel: Boolean = false

    /** 通道里留着几条回复 (判据用: 宿主推来的回答有没有真的落到框里) */
    @Volatile
    var replies: Int = 0

    /**
     * 回复框里**最新那条回复是哪一场发来的** (宿主 `overlay op=reply` 带过来的 `session`)
     *
     * 一条条回复落进来, 后一条顶掉前一条 —— 旧回复还画着, 而"这一条是谁说的"以最新的为准
     * (主人 2026-10-06 选的那一档)。它只回答"框里显示的是谁的话", **不再当投递目标** (那个由
     * [inputSession] 记)
     */
    @Volatile
    var replySession: String? = null

    /**
     * 这块框**真正把话投给了哪一场** (2026-10-09 主人: "谁发送了输入框, 就回到那一场")
     *
     * 它与 [replySession] 是**两笔账, 不是一笔**: 一块框可以先后跟好几场说话 (第一次是浮标那一场,
     * 之后回复里带进来另一场), 而主人要的"对应会话"是**他自己那次输入投出去的那一场** —— 回来的
     * 那一跳因此按这一条走 (见 [viewSession]), 不再跟着"最后推回复进来的是谁"漂
     *
     * 只有从框里发出去过话才会有值 ([OverlayService.ask] 写收件箱那一刻记下实际用的 `to`); 一次都
     * 没发过时是 null, 调用方退回 [replySession] 与浮标账本
     */
    @Volatile
    var inputSession: String? = null

    /**
     * 这块框**最后跟哪一场对话打过交道** (2026-10-11)
     *
     * 与 [inputSession] / [replySession] 是**两笔账, 不是一笔**: 那两个答的是"框此刻在屏上时, 这一句
     * 该投给谁 / 上面写的是谁的话", 所以框一收就跟着清 ([OverlayService.closeChannel]) —— 而
     * 「返回应用」在"没有轮在跑"那一档要的恰恰是**上一刻**那一场: 框把话发出去就自己收掉了 (主人
     * 2026-10-06 的口径), 于是人按「返回应用」的时候它本来就关着, 那两笔账早已是空的
     *
     * 空着会出什么事主人 2026-10-11 报了出来: 那一刻跳的其实是"新会话界面" (删掉
     * `dsh.sessions.current` 再重载), 而 dsh 那一侧的新会话落在**最近动过的那一场的工作区**上 ——
     * 于是界面动不动就跑到那一场正在跑的对话那边去, 回的**不是**这块框打交道的那一场
     *
     * 所以这一笔**只在框真的跟某一场打过交道时才写**: 从框里投出去的那一句 ([OverlayService.ask]
     * 记下实际用的 `to`), 或者落进框里的那一条回答 ([OverlayService.appendReply] 记下它的来源)。
     * **一次都没跟任何一场打过交道的设备上它是 null** —— 那一档「返回应用」照旧去新会话界面
     * (主人 2026-10-09 定的那一条), 一个字节都没改
     */
    @Volatile
    var boxSession: String? = null

    /**
     * 框现在该把话投给谁: **只有框在屏上、而且里面真有回复时**才点名
     *
     * 两个条件缺一不可: 框收掉之后那条例外就不成立了 (回老规矩按时间那一笔账), 而框由球上**三击**
     * 开出来时里面可能一条回复都没有 —— 那时它只是个输入框, 不该替宿主挑会话
     *
     * **优先还给上一次投过的那一场** ([inputSession]): 接着跟同一场说话是最常见的那一档, 而光靠
     * [replySession] 会在"框里同时进过两场的回复"时把后一场当成目标 —— 那正是这次要修的那条
     */
    fun replyTarget(): String? {
        if (!channel || replies <= 0) return null
        return BoxTarget.choose(inputSession, replySession)
    }

    /**
     * **双击回复框**该落到哪一场: 这块框跟哪一场打过交道 ([ReturnTarget.replyBox])
     *
     * 三条, 顺序就是优先级:
     *
     * 1. **投出去的那一场** ([inputSession]) —— 2026-10-09 主人: "谁发送了输入框, 就回到那一场"
     * 2. **最新那条回复是谁推来的** ([replySession]) —— 一次都还没从框里发过话时退它
     * 3. **[boxSession]: 框上一次跟哪一场打过交道** (2026-10-11) —— 框收掉之后前两笔账是空的, 而按
     *    的时候它本来就关着, 所以这是常态那一档 (没了它落到的是 dsh 那一侧的"新会话", 而那个会挑
     *    最近动过的那一场的工作区, 见 [boxSession])
     *
     * 都没有 (这台设备上这块框还没跟任何一场说过话) → null, 由调用方给"新会话界面"那个记号。
     *
     * **2026-10-11 主人把两扇门分开了**: 这一条只管**双击回复框** (球菜单那行「返回应用」与常驻通知
     * 走的是 [runningSession], 见 [ReturnTarget.menu]) —— 主人原话: "ball菜单栏里『回到应用』要改成走
     * 的是正在想的对话，没有则返回新对话。而双击回复框现在不变，仍返回现在的发送回复的对应对话"
     *
     * 与 [replyTarget] 分开是故意的: 那个还带"盒子在屏上而且有回复"这两道闸 (它管的是一条投递
     * 目标), 而双击回复框只想问"这一场是谁"
     */
    fun viewSession(): String? =
        ReturnTarget.replyBox(inputSession, replySession, boxSession)

    /**
     * 此刻**正在跑**的那一场 —— 就是球上写着「正在想」的那一场
     *
     * 判据与球上那三个字**同一份、连那道闸都同一个**: 那一轮在跑 ([phase] 来自 `lw/ball-phase.json`)
     * **且宿主真的在跑** (`DshHost.status`, 球上那三个字也是这么判的) —— 宿主崩了之后那份文件会停在
     * 最后一次的样子, 少了这道闸, 球上不写「正在想」而「返回应用」仍往一场已经不在的会话上跳
     *
     * 那一场是 `ball-phase.json` 里 `startedAt` 最大的那一条 (见 [BallPhaseFile]), 而它也是双击打断
     * 会打的那一场 (`host-plugin` 的 `pickInterruptTarget`) —— 显示什么就回什么、就打什么, 中间没有
     * 第二套口径
     */
    fun runningSession(): String? =
        if (phase == PHASE_THINKING && DshHost.status is HostStatus.Running) {
            session.takeIf { it.isNotBlank() }
        } else {
            null
        }

    /** 框"没人碰"那一笔账的起点 (epoch ms; 0 = 框不在屏上, 见 [boxIdleMs]) */
    @Volatile
    var boxActiveAt: Long = 0

    /**
     * 有人用了框一下: 空闲那一笔账重算
     *
     * 打字 / 滚动 / 摸到框 / 开语音 / 回复落进都走这里 —— **唤醒那个服务也要能记** (开语音窗口那一刻
     * 也算一次, 而它在另一个服务里), 所以这一笔放在进程级的状态对象上, 不是 `OverlayService` 的
     * 私有字段
     */
    fun noteBoxActivity() {
        boxActiveAt = System.currentTimeMillis()
    }

    @Volatile
    var url: String? = null

    /** 上一次失败的原因, 供 state 与日志共用 */
    @Volatile
    var lastError: String? = null

    /**
     * 球刚说过的那一句话 (提示音那种, 不是失败)
     *
     * 与 [lastError] 分开是**故意的**: 成功的话 ("已经让它切到视频模式") 与失败的话 ("写不进收件箱")
     * 对读状态的人是两件事, 混在一个字段里就会把"它刚做成了什么"报成 `last problem`
     */
    @Volatile
    var lastHint: String? = null

    /** 球现在停在哪儿 (窗口坐标) */
    @Volatile
    var x: Int = 0

    @Volatile
    var y: Int = 0

    /**
     * 宿主说的一轮在跑没有 (`PHASE_THINKING` / `PHASE_IDLE`)
     *
     * 与球自己那一档 ([ballPhase]) 是两件事: 这一条说的是轮次, 那一条说的是球收着还是全露着
     *
     * **2026-10-08 起它不是宿主推来的, 是从 [BallPhaseFile] 那份文件读来的**: 宿主在"哪几场在跑"
     * 真的变化时写 `$DSH_HOME/lw/ball-phase.json`, 应用用 inotify 盯着那个目录, 一改就重读 (见那个
     * 文件的头: 推送会丢, 而文件不会)
     */
    @Volatile
    var phase: String = PHASE_IDLE

    /** 现在这一轮在跑的是哪一场会话 (空 = 没有); 与 [phase] 一样来自那份文件 */
    @Volatile
    var session: String = ""

    /** 那一场该用的环色 (按会话固定, 见 `BallPhaseFile.colorFor`); 0 = 没在用 */
    @Volatile
    var ringColor: Int = 0

    /** 上一次读那份文件的那一句人话 (读不到 / 读不懂; 空 = 正常), 排障读数用 */
    @Volatile
    var phaseNote: String = ""

    /**
     * 球外那一圈涟漪现在往哪边走: `"out"` = 正在说 (往外扩), `"in"` = 正在听 (往回收), `""` = 没在跑
     *
     * 它是 2026-10-09 那套动效的唯一读数: 那块窗是**非触摸**的, 光看 `dumpsys` 分不出它在画什么,
     * 所以"涟漪到底跑没跑、往哪边跑"由这一个字段说
     */
    @Volatile
    var pulse: String = ""

    /**
     * 这一句播报念的是哪一场的回答 (空 = 没在念 / 不知道是哪一场)
     *
     * 它决定"同一场起了新的一轮"那一条掐不掐 (见 `OverlayService.cutReading`): 不知道是哪一场时
     * **一个字节都不掐** —— 宁可让它念完, 也不要凭猜把别人的回答掐掉
     */
    @Volatile
    var readingSession: String = ""

    /** 自动掐播报掐了几次 (同一场起了新轮那一档): 与 [interrupts] 一样, 动过的都要有个数 */
    @Volatile
    var speechCuts: Int = 0

    /**
     * 刚结束的那一轮是怎么收的 (见 [BallPhaseFile.Ended])
     *
     * 三条都是那份文件里的 `last` 原样搬过来的 —— 球上只认 [BallFailure.counts] 那三种 (写成
     * 「失败」), 而 [failedWhy] 不上球: 它只进 `overlay op=state`, 给"球为什么说失败"一个读数
     */
    @Volatile
    var failedAt: Long = 0

    @Volatile
    var failedKind: String = ""

    @Volatile
    var failedWhy: String = ""

    /**
     * 主人认过哪一条了: **点一下球**就把 [failedAt] 记在这儿 (见 `ballListener.onTap`)
     *
     * **记的是文件里那个 `at`, 不是本机时钟** —— 两侧时钟只要不一样, 拿本机时间当"认过"的水位线
     * 就会把下一条真的失败吞掉。判据于是是"文件里的 `at` 比这个数大", 与时钟无关
     */
    @Volatile
    var failedAckAt: Long = 0

    /** 这一拍该不该写「失败」: 文件里那一条算失败, 而且主人还没点过 */
    fun failedNow(): Boolean = BallFailure.shows(failedKind, failedAt, failedAckAt)

    /**
     * 宿主推来、而通道还没开着的那一句回答
     *
     * 它**不能丢**: 主人问了一句就去别的应用里了, 回来把通道张开时那句话还该在那儿 —— 所以答案是
     * "先攒着, 开通道时补上" (见 OverlayService.openChannel)
     */
    @Volatile
    var pendingReply: String? = null

    /**
     * 与 [pendingReply] 一起攒着的**来源会话** (宿主推来时带的 `session`)
     *
     * 缺了它, 那句补画出来的回答就只能算"某一场说过的话", 从框里回过去的那句也就没了定向
     */
    @Volatile
    var pendingReplySession: String? = null

    /**
     * 宿主推来、而通道还没开着的那一条提示 (不是回答)
     *
     * 与 [pendingReply] 分开是两个理由: **它不是回答** (画的时候前面带 `⚠`), 以及两条的来源不同
     * (提示来自投递失败那一路, 见 host-plugin 的 `reportNote`)
     */
    @Volatile
    var pendingNote: String? = null

    /** 球上现在画的那三个字, 空 = 空闲 */
    @Volatile
    var word: String? = null

    /**
     * 球现在半隐着没有 (只有一条边留在屏幕里)
     *
     * 它是**内部状态**: 光看窗口坐标很难判 —— 球"停靠"与"半隐"差着 63 px (48 dp 球的一半), 而
     * 两个数都写在 `dumpsys` 里。这一条让 `overlay op=state` 直接把它说出来, 于是"闲置 5 s 收边"
     * 这条判据在设备上量得动 (2026-10-05: 这台模拟器把应用自己的 logcat 滤掉了, 只能靠状态对账)
     */
    @Volatile
    var peeked: Boolean = false

    /**
     * 通道那块框**真的挂在 WindowManager 上**没有
     *
     * 与 [channel] 分开: 那一个是"逻辑上开着", 而这一个说的是那块 view 有没有进窗口管理器 ——
     * 两者不一致就是"状态说开了、屏幕上看不见", 排查时先要分清是哪一边
     */
    @Volatile
    var channelAttached: Boolean = false

    /** 那块框现在多高 (px), 0 = 还没量到 */
    @Volatile
    var channelHeight: Int = 0

    /**
     * 三块窗现在各自占的矩形与这一屏的尺寸, 规格都是 `x;y;宽;高` / `宽x高` (屏幕自身像素)
     *
     * **2026-10-08 加的**: 主人报的"横竖屏切换时文本框与球极大偏移、球消失"只有坐标说得清 —— 靠眼睛
     * 只能看出"不对", 而这三个数把"偏到哪儿去了"变成一条可直接读的证据 (见 OverlayService.noteRects)
     */
    @Volatile
    var screen: String? = null

    /** 球窗现在的位置与尺寸 */
    @Volatile
    var ballRect: String? = null

    /** 输入条 (那条 WebView) 现在的位置与尺寸 */
    @Volatile
    var stripRect: String? = null

    /** 输入通道那块框现在的位置与尺寸 */
    @Volatile
    var boxRect: String? = null

    /** 球自己那一档 (RESTED / SUMMONED / VOICE): 收边那几道闸的读数之一 */
    @Volatile
    var ballPhase: String = "SUMMONED"

    /** 有人正按着球没有 (`ballDragging`): 也是收边的闸 */
    @Volatile
    var dragging: Boolean = false

    /** 菜单或通道开着没有: 也是收边的闸 */
    @Volatile
    var busy: Boolean = false

    /** 上次"有人用它"到现在过了多少毫秒: 收边的判据就是它超过 5000 */
    @Volatile
    var idleMs: Long = 0

    /**
     * 上一次关浮标**没把窗摘掉**的原因 (空 = 摘掉了 / 还没关过)
     *
     * 它必须是一个单独的字段, 而不是并进 [lastError]: 那两个说的不是一回事 —— `lastError` 是"这块窗
     * 没能建起来", 这一条是"要摘的时候摘不掉"。2026-10-06 在真机上量到的那个毛病正是后者: 服务停了、
     * `ball-on` 写了 false, 而窗还挂在窗口管理器上 (见 [OverlayService.hideBall])
     */
    @Volatile
    var hideFailed: String? = null

    /**
     * 浮标这个服务**现在活着**没有 (不是"球在不在屏上")
     *
     * 两者必须分得开: 窗是服务加的, 而**服务停掉不等于窗会自己消失** (2026-10-06 真机实测: 服务没了、
     * 窗还在)。所以"服务在不在"要单独记一个记号, 让想把球叫出来的人先问一句, 别拿一个已经死掉的服务
     * 当通道 —— 更要紧的是反过来: 关掉之后那些"顺手把球推出来"的路 ([LwOverlay.reply]) 不许凭这个
     * 记号之外的东西去起服务
     */
    @Volatile
    var running: Boolean = false

    /** `peek()` 被叫了几次 (收边真的发生过几次) */
    @Volatile
    var peekCalls: Int = 0

    /** 上一次 `peek()` 被哪一道闸挡住 (空 = 没被挡) */
    @Volatile
    var peekBlocked: String = ""

    /**
     * 球现在这一档叫什么: `waiting` (全露着等着) / `asleep` (已经收着) / `peeking` (这一拍正在收)
     *
     * 它是排查"为什么没收边"那一个数 —— [peekBlocked] 只在收边**被拒**的时候写, 而"还没到点"与
     * "人正在用它"在状态里长得一样。这一条把那六条闸的名字原样报出来 (见 [BallRest.wait])
     */
    @Volatile
    var ballWait: String = "waiting"

    /**
     * 球此刻的不透明度 (1 = 全亮, 0.35 = 半隐那一档 (半藏只挪位置, 不改它))
     *
     * 它是"到点了却没变淡"那条账上**唯一说得清的数** (2026-10-17 加): [ballWait] 里的 `pale`
     * 只说"判据认为该淡了", 而真正落到 View 上的是这个值 —— 写它的是 [setBallAlpha] 那一处
     */
    @Volatile
    var ballAlpha: Float = 1f

    /**
     * 键盘算不算"正在用它"
     *
     * 判据是两件事里有一件成立: 输入框有焦点, 或者键盘真的在屏上 ([OverlayService.imeBottom] > 0)。
     * 主人 2026-10-06 点过名: **键盘输入不属于空闲** —— 所以这一档为真时球不收边
     */
    @Volatile
    var keyboard: Boolean = false

    /** 球的窗口现在真实的 x (半隐那个偏移算在里面): 收边到底动没动窗, 看它 */
    @Volatile
    var ballWindowX: Int = 0

    /** 上一次 `moveX` 的目标 x */
    @Volatile
    var lastMoveTo: Int = 0

    /**
     * 双击打断记了几笔 (主人 2026-10-06 加的那一支)
     *
     * 与 [peekCalls] 同一种用法: 触摸类的东西"看着像发生了"与"真的发生了"要分得开, 而这一条只有
     * 一个数说得清 —— `lw_overlay op=state` 会报它
     */
    @Volatile
    var interrupts: Int = 0

    /**
     * 确认窗那两笔账 (2026-10-10): **投出去几个开麦待办**, 以及其中**被双击/三击当场取消掉几个**
     *
     * 主人报的"三击 ball 会打开语音输入, 并且会卡一下"那一批就是靠这一对数收口的 (见 [BallCommit]):
     * `tapCommitsCancelled` 应该随着三击次数一起涨, 而 [WakeWordState.shortLivedOpens] 应该趋近于 0
     * —— 前者涨、后者不涨才是"确认窗真的挡在开麦前面"
     */
    @Volatile
    var tapCommits: Int = 0

    @Volatile
    var tapCommitsCancelled: Int = 0

    /**
     * 最近一次三击: 从那一串的**第一下**到"键盘要来了"过了多少毫秒 (0 = 还没发生过)
     *
     * 它是主人报的那一声"卡一下"在应用这一侧的读数 (主人 2026-10-10): 三击的净效果里没有开麦之后,
     * 这一段就只应该剩下弹框本身的开销
     */
    @Volatile
    var lastTripleMs: Long = 0

    /**
     * **常驻语音开着没有** (2026-10-10): 读的是切模式那一步写下的那个记号 (`modes/voice-resident.on`,
     * 见 `LwWakeWord.residentWanted`) —— 也就是"原本的常驻语音调用"那一份事实本身
     *
     * 球上这一档显示上与「正在听」一模一样 (主人: "那三个字也是正在听"), 只有点那一下不同: 记号开着
     * 时单击不只是收麦, 还把视频模式一起退出 ([BallAct.LEAVE_VIDEO])。它就是那一下的判据, 也是读数
     */
    @Volatile
    var residentVoice: Boolean = false

    /** 球上那一下收掉常驻语音 (顺带退出视频模式) 记了几笔 —— 触摸类的东西要有个数 */
    @Volatile
    var residentVoiceLeaves: Int = 0

    /**
     * 输入通道那块框闲置了多久 (毫秒, 0 = 框不在屏上)
     *
     * 到 [BallMinutes.BOX_IDLE_MS] 框就自己收掉 (主人 2026-10-06 选的处置), 收掉之后球那一笔空闲账
     * 从"框关掉"重新起算 —— 于是"回复到了"与"球半隐"不再互相顶住
     */
    @Volatile
    var boxIdleMs: Long = 0
}

/**
 * 浮标: 一颗不吃焦的小球, 点一下说话, 长按出菜单, 拖到哪儿吸附到哪边
 *
 * 它与 `docs/floating-input.md` 里那块「面板 A」是同一个服务的两块窗:
 *
 * - **球** ([BallView]) 常驻, `FLAG_NOT_FOCUSABLE`, 所以手指落在球外面照旧给底下的应用 (改动之前
 *   那块窗收系统输入法, 代价正是窗外全被它吃掉)
 * - **输入条** (那块 WebView) 只在展开时挂上, 可获焦、收输入法, 收起时把它从窗口上摘下来 ——
 *   页面本身留着 (会话、cookie 与主界面共享, 摘下来再挂回去不必重载)
 *
 * 三件事与别的窗不同, 都要记住:
 * 1. **两块窗是分开加的**, 不是运行期改 `FLAG_NOT_FOCUSABLE`: 那个标志在各 ROM 上改起来行为不一
 *    (有的要摘掉再加一遍), 而加窗/摘窗没有这个不确定面
 * 2. **前台服务**: 球挂在服务上, 应用退到后台球才不会被系统收走; 通知栏那一条的文字跟着球的状态走,
 *    上面的按钮直接关掉浮标
 * 3. **麦克风授权与宿主 WebView 同一条规矩** (见 ui/HostScreen.kt): 只放 `RESOURCE_AUDIO_CAPTURE`,
 *    只给 `127.0.0.1` 那个来源, 别的一律拒
 */
class OverlayService : Service() {

    private var window: WindowManager? = null

    private var ballView: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /**
     * 球外那一圈圈涟漪那块窗 (2026-10-09): **只在"正在说 / 正在听"两档挂着**, 落下就摘
     *
     * 与球窗分开的理由只有一个: 涟漪比球大 (96 dp 对 48 dp), 画在球那块窗里会被窗口裁掉。它
     * `FLAG_NOT_TOUCHABLE`, 所以多挂一块窗**一个触摸都不会多吃** —— 球"只吃自己那 48 dp"那条性质
     * 与它无关
     */
    private var rippleView: RippleView? = null
    private var rippleParams: WindowManager.LayoutParams? = null

    private var stripView: View? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var stripTitle: TextView? = null

    private var menuView: View? = null

    /** 输入通道那块窗 (自己画的文字框): 开着时挂上, 关掉时摘下来 */
    private var boxView: BoxView? = null

    /** 那块窗的根 (收 ACTION_OUTSIDE 的壳): 加窗 / 摘窗 / 挪窗动的都是它 */
    private var boxRoot: ChannelRoot? = null
    private var boxParams: WindowManager.LayoutParams? = null

    /**
     * 上一次点在框外是什么时候 (0 = 还没有过): **框外那两下算不算一次双击就看它**
     *
     * 原来这里是一个计数器 (`blankTaps`), 隔多久都累计 —— 于是"点一下、过一会儿再点一下"也会把框
     * 关掉。主人 2026-10-06 的口径是"收窄成真双击, 超时的要重新计算", 所以账从"点了几下"换成
     * "上一次是什么时候": 窗口 ([BallMinutes.BOX_DOUBLE_TAP_MS]) 之内的第二下才关, 超时的那一下
     * 重新起算 (见 [noteBlankTap])
     *
     * 这本账只由 [noteBlankTap] (窗外的 `ACTION_OUTSIDE`) 与那几处清零动, 而**关菜单那一下不记**
     * —— 菜单开着时点外面是去关菜单的, 不是在对通道说话
     */
    private var lastBlankTapAt = 0L

    /**
     * 上一次**算数的**那一下点球是什么时候 (0 = 还没有过)
     *
     * 它现在喂给 [BallTaps.kind] 一个人看: 双击窗口 ([BallMinutes.DOUBLE_TAP_MS]) 与两档防连击窗口
     * ([BallTaps.guard]) 都从这一个数上算
     */
    private var lastTapAt = 0L

    /**
     * 这一串连击已经数到几次 (0 = 还没开始)
     *
     * 与 [lastTapAt] 一起喂给 [BallTaps.kind]: 那个数是"上一记什么时候", 这个是"到上一记为止数到
     * 几次" —— 有了它才分得出双击与**三击** (主人 2026-10-07: "加上三击 ball 打开键盘输入")。
     * [BallTap.TOO_SOON] 那一支不动这两个数 (连击里"太近"的一下按没算过处理)
     */
    private var tapChain = 0

    /**
     * 这一串连击**头一下**的时刻 (0 = 还没开始)
     *
     * **它已经不参与判定** (2026-10-10): 原来那句"三击要在整串 450 ms 之内走完"的闸删掉了 ——
     * 每一段间隔都必须 ≤ 双击窗口, 于是整串最多 2 × 220 = 440 ms, 那条闸在防误触档永远触不到
     * (见 [BallTaps.kind])。现在它只当一个读数用: 三击落地时量"从第一下到键盘"那一段有多长
     * ([OverlayState.lastTripleMs]), 主人报的那一声"卡一下"看的就是这一个数
     */
    private var chainStartedAt = 0L

    /**
     * 上一次拖动**松手**是什么时候 (0 = 还没有过)
     *
     * 防误触那一条 (2026-10-08): 松手会吸附到边上, 而人常常在松手之后又补一下 —— 那一下不该算单击
     * (算了就会在刚放好的位置上开口说话), 判据是纯函数 [BallMinutes.tapAfterDropIsFresh]
     */
    private var lastDropAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** 球的状态词、标题与通知都靠它刷: 400 ms 一次, 与唤醒词服务那个观察者同一个节奏 */
    private val tick = object : Runnable {
        override fun run() {
            runCatching { refresh() }
            // **宿主翻成"在跑"时重读一次那份文件**: 它重启后会自己写一遍空表, 而 inotify 那一条只在
            // 文件真的变过时才叫醒我们 (宿主没重启、文件也没变, 就什么都不用做)
            val up = DshHost.status is HostStatus.Running
            if (up != lastHostUp) {
                lastHostUp = up
                runCatching { readBallPhase() }
            }
            // **再兜一眼那份文件的 mtime** (2026-10-15 真机: 这台机器上 inotify 一次都没叫醒过我们,
            // 于是球上「正在想」从来没亮过 —— 而这份文件只有三处会读: 服务起来 / 宿主翻成在跑 /
            // inotify)。一次 stat 而已 (不是读文件、更不是解析), 变了才真读 —— 于是 inotify 那条
            // 仍然是最快的, 它坏了最多晚一拍 (400 ms)
            val stamp = runCatching { BallPhaseFile.file(this@OverlayService).lastModified() }.getOrDefault(0L)
            if (stamp != phaseStamp) {
                phaseStamp = stamp
                runCatching { readBallPhase() }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** 那份"哪几场在跑"的文件: inotify 盯着它 (见 [BallPhaseFile]) */
    private var phaseWatch: BallPhaseFile.Watch? = null

    /**
     * 上一次读那份文件时它的 mtime
     *
     * 它是 [tick] 那一拍的兜底判据 (见那里): inotify 万一没叫醒我们, 每 400 ms 的一次 stat 也能发现
     * 文件变过 —— 2026-10-15 真机上 inotify 就是死的, 靠的就是这一条
     */
    private var phaseStamp = 0L

    /** 那份余额文件: 同一个盯法 (见 [BalanceFile], 2026-10-16) */
    private var balanceWatch: BalanceFile.Watch? = null

    /**
     * 上一次「看余额」是主人什么时候要的
     *
     * **两档都认它** (2026-10-17 主人: 「余额查询只能用户主动在 ball 菜单主动查询」): 宿主往那份文件里
     * 写了新数这件事本身不再是上屏的理由 —— 只有他自己点了菜单、正等着那一行时, 到的那份才值得写进框
     * (金额那一档与失败那一句 `⚠` 合用一个判据, 从前只有失败那一条带闸, 于是每轮结束时宿主自己刷出来
     * 的那一份会**悄没声地长在主人的回答后面**)
     */
    private var balanceAskedAt: Long = 0L

    /** 上一拍画的那个环色: 会话换了而字没换时也得重画一次 (见 [refresh]) */
    private var lastRing: Int? = null

    /** 余额那份文件多新才算"直接给" (过了这个数就先写「正在取」再让宿主刷) */
    private val BALANCE_FRESH_MS = 60_000L

    /** 「看余额」之后多久之内的失败才算"主人正等着" (只有这一段里失败才值得往框里塞一句 ⚠) */
    private val BALANCE_ASK_WINDOW_MS = 30_000L

    /**
     * 上一拍浮标外观挑的是哪一个 (协议 10.1 / 方案 3.2)
     *
     * 外观换了要**当场重画** —— 而 [BallView.show] 那条链只在"字换了 / 环色换了 / 外部要求"时才走,
     * 所以这里把"外观 id"也算成一档变化: 主人在设置页换了外观、这一拍就看得见, 不必等下一次状态变化
     */
    private var lastThemeId: String? = null

    /** 上一拍宿主在不在跑 */
    private var lastHostUp = false

    private var snap: ValueAnimator? = null

    /** 球真正停靠的那个 x (半藏是它上面加的一个偏移) 与那个 y */
    private var ballRestX = 0
    private var ballBaseY = 0

    /** 现在半藏着没有 (只留一条边在外面) */
    private var peeked = false

    /**
     * 球现在在哪一档: **半隐收着 / 全露着 / 正听着**
     *
     * 这是这一批的核心 (主人 2026-10-05 的点击序列): 三段点击靠它分开 —— 收着时点一下只召出,
     * 召出之后再点才开语音输入。把它与 [peeked] 分开是故意的: 收起说的是位置, 召出说的是意图,
     * 而"召出之后不弹提示"那一条判的正是意图 (见 [BallTouch])
     */
    private var phase: BallPhase = BallPhase.SUMMONED

    /** 最后一次有人用它的时刻: 闲置超过 [BallMinutes.PEEK_IDLE_MS] 就收边 */
    private var lastActiveAt = 0L

    /**
     * 键盘算不算"正在用它" (主人 2026-10-06: 键盘输入不属于空闲)
     *
     * 判据是两件事里有一件成立: 输入框有焦点, 或者键盘真的在屏上 ([imeBottom] > 0)。收到
     * [BoxView.Listener.onFocus] 与那次 inset 回调时重算, 每一拍只读
     */
    private var overlayKeyboard = false

    /** 输入框现在有焦点没有 (与 [imeBottom] 一起决定 [overlayKeyboard]) */
    private var boxFocused = false

    /**
     * 通道那块框现在**真在窗口上**没有
     *
     * 与 `boxView != null` 分开: 关通道只把窗摘下来、实例留着复用 (见 [openChannel]), 所以"有实例"
     * 不等于"框在屏上" —— 键盘那一档的判据要的是后者
     */
    private val boxOnScreen: Boolean get() = boxRoot?.isAttachedToWindow == true

    private var ballDragging = false
    private var dragFromX = 0
    private var dragFromY = 0

    /** 球那块窗现在真实的 x (半隐的偏移算在里面): 通道摆位照它算 */
    private var currentBallX = 0

    /**
     * 最近一次"把球召出来"发生在什么时候 (0 = 没有过)
     *
     * **点击序列那几段的判据就是它** (2026-10-06 主人报的"点一次可能直接打开语音输入"):
     *
     * - 半隐里点一下 / 菜单关掉之后 → 记一个时刻, 那一下**绝不碰语音链**
     * - 紧接着 (在 [BallMinutes.SUMMON_FRESH_MS] 之内) 再点一下 → 那才是"第二次点击" → 开语音
     * - 隔久了再点 → 只当又召出一次, **不开口**
     *
     * 改之前这里是一个布尔 `everSummoned` (立起来就不再落下): 于是球**第一次**用完之后, 此后任何
     * 一击都直接开语音 —— 那正是"点一次就说话", 也把"第一下只召出"整条语义吃掉了
     */
    private var summonedAt = 0L

    /** 输入条归位用的那个 y (拖动改它) */
    private var stripBaseY = 0

    /** 键盘占了屏幕下方多少 (输入条的 inset 给的): 球靠它避开键盘, 输入条靠它整体上抬 */
    private var imeBottom = 0

    private var lastWidth = 0
    private var lastHeight = 0

    /**
     * 上一拍球上说的是哪个字 —— **每一拍都写**, 于是 `word != lastWord` 这一个判据同时管三件事:
     * 要不要重画、状态那两笔账 (见 [BallIdle.reset]) 该不该记
     *
     * 原来这里还有第二个字段 `prevWord` (只在"字换了"那一下赋值), 而它正是 2026-10-06 那条
     * "半隐藏又失效"的病根: 它一旦非空就再也不落, 于是每一拍都去重算空闲计时
     */
    private var lastWord: BallWord? = null

    /**
     * 「正在想」与「正在听」各自**最近一次变成真的**是什么时候 (0 = 现在不是真的)
     *
     * 这是 2026-10-09 那条口径的实现: 球上写"想"还是"听"看这两个数谁大 ([BallStatus.wordFor]) ——
     * "点一下开麦就写正在听"与"开麦之后新一轮又起来就写回正在想"两件事都落在这两个数上, 而 400 ms
     * 一拍就是它们的粒度
     */
    private var thinkingAt = 0L
    private var listeningAt = 0L

    /**
     * 那一份 `ball-phase.json` 里现在在跑的那几场 ([BallPhaseFile.Turn], id 与开始时刻)
     *
     * 两笔自动收口都要它: "起了一轮新的"看最新的开始时刻 ([lastTurnAt]), 而"同一场起了新轮"要把
     * 正在念那一句的会话 id 在这一批里找一遍
     */
    private var turns: List<BallPhaseFile.Turn> = emptyList()

    /** 上一次读到的那一批里最新的开始时刻 (0 = 上一次读到的是空表) */
    private var lastTurnAt = 0L

    /** 第一次读到那份文件没有: **那一次只当基线**, 不把"已经有轮在跑"当成"刚刚起了一轮" */
    private var turnsBaseline = false

    /** 这一句播报念的是哪一场的回答 (null = 没在念 / 不知道是哪一场), 见 [cutReading] */
    private var readingSession: String? = null

    /** 这一句播报已经掐过一次了没有 (一次念只掐一次, 停不掉时不许每一拍都再叫一遍) */
    private var readingCut = false

    /** 上一拍在不在念: "念开始了"那一个边沿用来记下这一句是谁的回答 */
    private var lastSpeaking = false

    /**
     * 这一下单击里那个**还没到点的开麦待办** (2026-10-10 起是它唯一一份待办)
     *
     * 它现在管的是"确认窗" (见 [BallCommit]): 会开麦的那一下不立刻开, 而是把开麦这件事挂在这上面
     * 等 [BallFeel.commitWindowMs] —— 窗里来了双击或三击就把它取消, 于是一次三击从头到尾不碰麦克风
     *
     * **双击/三击到了就取消它** 是这套的全部要点, 理由有两条: 一是三击的净效果不该包含"先开麦";
     * 二是"正在想"那一档的双击是打断 —— 不取消的话第一下会先把麦克风打开, 于是"想打断"变成了
     * "开了语音"(2026-10-08 主人报的那一条)
     *
     * 生命周期由 [onDestroy] 的 `handler.removeCallbacksAndMessages(null)` 兜住 (那一条在这套之前
     * 就有了), 所以服务走的时候不会有待办对着已销毁的视图抢焦点
     */
    private var pendingTap: Runnable? = null

    /**
     * 这一下按那四段算出来要做什么
     *
     * [tapAction] 与 [onTap] 的确认窗判定 ([BallCommit.deferMs]) 共用同一份入参 —— 分成两处写的话,
     * "要不要等"与"等到了做什么"迟早会对不上
     */
    private fun currentAct(): BallAct = BallTouch.act(
        phase = phase,
        // 与球上那几个字**同一个判据** (2026-10-16): 原来这里读的是 `speakingNow`, 于是尾巴窗口里球上
        // 写着「正在说」、点一下却不掐播报 (显示与动作两套判据)。现在两边都是 [LwSpeak.talking], 再加
        // 上半双工那个标记管着的开门一声「嘀」
        speaking = LwSpeak.talking(this) || VoiceState.speaking,
        channelOpen = boxView != null,
        listening = VoiceState.capturing,
        // 常驻语音那一档: 显示上与「正在听」一模一样, 只有那一下多做一件事 (见 [BallAct.LEAVE_VIDEO])
        resident = residentVoiceOn(),
    )

    /**
     * 常驻语音此刻开着没有
     *
     * 读的是切模式那一步写下的**那个记号** ([LwWakeWord.residentWanted]) —— 与 `lw_wakeword op=status`
     * 报的 `allowVoice`、与 `LwModes` 切模式用的是同一份事实, 所以"球上那一下"不会与"原本的常驻语音
     * 调用"漂开 (主人 2026-10-10: "和原本的常驻语音调用一致")
     *
     * **不读 `WakeWordState.voiceAllowed`**: 那个字段只在唤醒词服务收到 `ACTION_REFRESH` 时才更新,
     * 而服务没在听 (省电模式 / 还没起) 时它有可能是旧的 —— 记号文件才是那份事实本身
     */
    private fun residentVoiceOn(): Boolean = runCatching { LwWakeWord.residentWanted(this) }.getOrDefault(false)

    /**
     * 取消那个还没到点的开麦待办, 回"真的取消掉了一个没有"
     *
     * 真的取消掉才记一笔 [OverlayState.tapCommitsCancelled]: 它就是"确认窗挡住过几次开麦"那个读数,
     * 与 [OverlayState.tapCommits] 并排看才知道待办有没有把主人想要的开口也吃掉
     */
    private fun dropPendingCommit(): Boolean {
        val run = pendingTap ?: return false
        handler.removeCallbacks(run)
        pendingTap = null
        OverlayState.tapCommitsCancelled += 1
        return true
    }

    private var lastNotice: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OverlayState.running = true
        createChannel()
        fend()
        // 先按现有那一份对一次账, 再盯住它 —— 三处读取之一 (另两处: inotify 事件、宿主翻成在跑)
        runCatching { readBallPhase() }
        startPhaseWatch()
        // 余额那份文件同一个盯法 (2026-10-16): 宿主一刷, 框里那条「正在取」就地换成真数
        startBalanceWatch()
        // 桌面对话那本账 (2.7.8 批次 6): 宿主写完 `assistant` 那一笔要有人重画桌面那一格 ——
        // 而这里起来的服务是"应用还活着"最可靠的那个标志
        runCatching { DesktopChatFile.ensure(this) { WidgetHost.refreshChat(this) } }
    }

    /**
     * 读一次"哪几场在跑"那份文件, 落进 [OverlayState] 并当场重画
     *
     * 球上只显示**最近开始的那一场** ([BallPhaseFile.latest]), 它的颜色按会话固定 —— 于是多个会话
     * 同时在想时那个环色会跟着换, 主人一眼看得出"现在这个正在想是不是我先前那一场"
     *
     * **同一个 snapshot 顺手把 `last` 也带回来** (2026-10-08): 球上「失败」那两个字与「正在想」
     * 出自同一份文件, 分两次读就会拿到两个时刻的真相
     */
    private fun readBallPhase() {
        val snapshot = BallPhaseFile.snapshot(this)
        // 顺手把 mtime 记下来: [tick] 那一拍就是拿它判"文件变过没有" (见那里的兜底)
        phaseStamp = runCatching { BallPhaseFile.file(this).lastModified() }.getOrDefault(phaseStamp)
        // **整份清单**留着给两笔自动收口用 (见 [turns]): 球上那个字只要最近那一场, 而"同一场起了
        // 新轮"要能把那一场在这里面找出来
        turns = snapshot.running
        val turn = snapshot.latest
        OverlayState.phase = if (turn == null) OverlayState.PHASE_IDLE else OverlayState.PHASE_THINKING
        OverlayState.session = turn?.id.orEmpty()
        OverlayState.ringColor = turn?.let { BallPhaseFile.colorFor(this, it.id) } ?: 0
        OverlayState.phaseNote = snapshot.note
        // **每次都覆盖**: 文件即真相, 而宿主那边 `turn/start` 不清 `last` —— 所以"认过没有"这件事
        // 只能由 [OverlayState.failedAckAt] 说了算, 覆盖不会把那一笔冲掉
        OverlayState.failedAt = snapshot.last?.at ?: 0
        OverlayState.failedKind = snapshot.last?.kind.orEmpty()
        OverlayState.failedWhy = snapshot.last?.why.orEmpty()
        refresh(force = true)
    }

    /**
     * 盯住那份文件所在的目录 (inotify): 宿主一写就叫醒, 当场重读
     *
     * 回调在 [android.os.FileObserver] 自己那条线程上, 而界面这些东西都属于主线程 —— 所以整件事
     * 只是往 `handler` 上投一个 `readBallPhase`, 一秒都不用等
     */
    private fun startPhaseWatch() {
        if (phaseWatch != null) return
        val watch = BallPhaseFile.Watch {
            handler.post { runCatching { readBallPhase() } }
        }
        // **盯不上不会有读数** (`startWatching()` 是 void, 2026-10-15 真机上它就是这么死的): 所以
        // 这条路不靠它 —— [tick] 那一拍顺手看一眼 mtime, inotify 坏掉最多晚 400 ms
        runCatching { watch.start(this) }
            .onFailure { problem -> OverlayState.phaseNote = "盯不住 ball-phase.json: ${problem.message}" }
        phaseWatch = watch
    }

    /** 余额那份文件同一个盯法 ([BalanceFile.Watch]): 宿主一刷就叫醒, 当场改掉框里那一行 */
    private fun startBalanceWatch() {
        if (balanceWatch != null) return
        val watch = BalanceFile.Watch {
            handler.post { runCatching { readBalance() } }
        }
        runCatching { watch.start(this) }
            .onFailure { problem -> note("盯不住 balance.json: ${problem.message}") }
        balanceWatch = watch
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideBall(EXTRA_HIDE_FROM_NOTIFICATION)
                return START_NOT_STICKY
            }

            // 收起输入条, 球留着
            ACTION_COLLAPSE -> {
                collapse()
                return START_STICKY
            }

            ACTION_EXPAND -> {
                showBall(intent)
                expand(intent)
                return START_STICKY
            }

            // 输入通道 (那块自己画的文字框): 球上**三击**走这一条 (2026-10-07 起替掉了长按菜单那行)
            ACTION_CHANNEL -> {
                showBall(intent)
                openChannel(focus = true)
                return START_STICKY
            }

            // 宿主推来的一轮回答: 画进通道那块框里 (通道没开就记着, 等它开了再补上)
            ACTION_REPLY -> {
                deliverReply(
                    intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                    intent.getStringExtra(EXTRA_SESSION),
                )
                return START_STICKY
            }

            // 宿主推来的一条提示 (不是回答): 比如"这句话没能送进会话" —— 与回答同一条路, 画成 ⚠ 那一行
            ACTION_NOTE -> {
                deliverNote(intent.getStringExtra(EXTRA_TEXT).orEmpty())
                return START_STICKY
            }

            // 球上那一下「说话」: 直接调常驻语音链, **不碰页面自己的麦克风**
            ACTION_LISTEN -> {
                listenNow()
                return START_STICKY
            }
        }
        showBall(intent)
        if (intent?.getBooleanExtra(EXTRA_EXPAND, false) == true) expand(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 服务走了, 那个目录就没人盯了: inotify 的描述符也要跟着放掉 (下次 onCreate 再盯回去)
        phaseWatch?.stop()
        phaseWatch = null
        balanceWatch?.stop()
        balanceWatch = null
        snap?.cancel()
        snap = null
        closeMenu()
        closeChannel()
        // 服务不管怎么停 (我们自己关的、系统收的、进程要走之前那一轮) 都**先把窗摘掉再去引用**:
        // 服务停掉不会让这块窗自己消失, 而那只剩一个后果 —— 一颗没人管得了的球留在别人屏幕上
        // (2026-10-06 真机实测, 见 [hideBall])。正常那条路已经在 hideBall 里摘过了, 这里挡的是
        // "别的入口把服务停了"那几种情形; 那句回执这里不看, 但**摘不掉要留一行**
        if (!detachBall()) Log.w(TAG, "the service is going down with the ball window still on screen")
        // 涟漪那块窗同理: 它比球大, 留下来是一圈没人管得了的光晕 —— 而它必须在 `window` 置空之前摘
        runCatching { detachRipple() }
        web?.let { runCatching { it.destroy() } }
        web = null
        ballView = null
        ballParams = null
        stripView = null
        stripParams = null
        stripTitle = null
        boxView = null
        boxRoot = null
        boxParams = null
        window = null
        OverlayState.showing = false
        OverlayState.expanded = false
        OverlayState.channel = false
        OverlayState.channelAttached = false
        OverlayState.word = null
        OverlayState.running = false
        // 前台服务那一条常驻通知跟着一起退: 球都没了, 通知栏上还留一条"浮标"就是一条点不到的假消息。
        // 系统通常自己会收 (实测 lw-overlay 那条在服务停后确实没了), 这里是**不靠它**的那一份
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /**
     * 转屏 / 换分辨率: **三块窗一起重摆** (2026-10-08 修的那一条)
     *
     * 2026-10-08 主人报的: "横竖屏切换时, 文本框和 ball 会发生极大偏移, ball 消失"。病灶是三块窗的
     * 几何来源不统一 —— 球是每屏重算的 ([replace] 按存盘那条边算), 而**输入条与文本框的尺寸只在创建
     * 那一刻算过一次** ([expand] / [openChannel]), 转屏之后窗口管理器不会替它们重算: 竖屏算出来的那条
     * 2400 高的输入条横过来还是 2400 高, 于是整块跑到屏外; 球那条"消失"是同一件事的另一面 —— 半隐
     * ([peek]) 的账与窗口坐标在转屏那一刻没对上, 而 `FLAG_LAYOUT_NO_LIMITS` 允许窗口留在屏外
     *
     * 所以现在是 [relayoutAll] 一个出口, 而且**补一拍**: 转屏是异步的 (窗口树常常要两拍才稳), 立刻做
     * 一次、[ROTATE_SETTLE_MS] 之后再对一次账
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { relayoutAll(force = true) }
        handler.postDelayed({ runCatching { relayoutAll(force = true) } }, ROTATE_SETTLE_MS)
        runCatching { refresh(force = true) }
    }

    /**
     * 按**现在**这块屏把三块窗重摆一遍
     *
     * 顺序是刻意的: 先把球按存盘那条边摆好 (它是文本框的基准), 再让输入条与文本框跟着算
     *
     * [force] 为真时连"当前这一屏没变"也照样重摆: 转屏那两拍之间 `displayMetrics` 可能还是旧值,
     * 而两拍都要各做一次真的重摆 —— 少一次就会留下一次用旧值算出来的位置
     */
    private fun relayoutAll(force: Boolean = false) {
        val metrics = resources.displayMetrics
        if (!force && metrics.widthPixels == lastWidth && metrics.heightPixels == lastHeight) return
        lastWidth = metrics.widthPixels
        lastHeight = metrics.heightPixels
        // 半隐与拖动都归零: 转屏之后"收着的那一半"没有意义, 而留着这个记号会让 [applyBallPosition]
        // 把一个用旧屏宽算出来的半隐偏移再画一次 (球就是这么"消失"的)
        snap?.cancel()
        snap = null
        peeked = false
        ballDragging = false
        // 归零到全亮, 读数跟着写 (转屏之后那两档都从头算, 见 [alphaOf])
        ballView?.alpha = 1f
        OverlayState.ballAlpha = 1f
        replace()
        reflowStrip()
        layoutChannel()
        noteRects()
    }

    /**
     * 输入条按现在这块屏重算尺寸与停靠位
     *
     * 它**不重载页面**: WebView 交给窗口管理器的是 MATCH_PARENT, 窗口大小一变自己就跟着重排 —— 那条
     * 显式 `layoutParams` 的规矩见 AGENTS.md (少了它 viewport unit 全解析成 0)
     */
    private fun reflowStrip() {
        val layout = stripParams ?: return
        val view = stripView ?: return
        val metrics = resources.displayMetrics
        val margin = dp(MARGIN_DP)
        layout.width = StripSpot.width(metrics.widthPixels, margin)
        layout.height = StripSpot.height(metrics.heightPixels, margin, DEFAULT_HEIGHT_PERCENT)
        layout.x = StripSpot.x(margin)
        stripBaseY = StripSpot.restY(metrics.heightPixels, layout.height, margin, dp(STRIP_LIFT_DP))
        // 键盘那一层由 [applyIme] 统一算 (它读 [stripBaseY] 与 [imeBottom]), 这里只把它再叫一次
        applyIme()
        runCatching { window?.updateViewLayout(view, layout) }
    }

    /**
     * `overlay op=state` 的三个读数: 三块窗各自在哪儿、这一屏多大
     *
     * 加它是因为 2026-10-08 那条转屏毛病**只有坐标说得清**: "极大偏移"到底是多偏、跑出去多少, 靠眼睛
     * 只能看出"不对"。规格是 `x;y;宽;高` (与 `lw_screenshot` 那套坐标同一个口径, 都是**屏幕自身像素**)
     */
    private fun noteRects() {
        val metrics = resources.displayMetrics
        // **半隐那个记号也要在这里落一次** (2026-10-08): 它原来只在 [applyBallPosition] 里写, 而
        // [peek] / [unpeek] 走的是 [moveX] 那条动画 (不经过 applyBallPosition) —— 于是 "球已经收进去
        // 了, 而读数是 false"。排查那条转屏毛病时我自己就被这个滞后骗过一次, 所以凡是要摆窗的地方都
        // 走这一条: 它读的是同一个字段, 只是把读数对齐到"刚刚做完那一下"
        OverlayState.peeked = peeked
        OverlayState.screen = "${metrics.widthPixels}x${metrics.heightPixels}"
        // 球窗开的是 WRAP_CONTENT, 所以宽高取**量出来的** (params 里那两个数是 -2); 输入条与文本框那两块
        // 是显式尺寸, 直接读 params (文本框的高仍是 WRAP_CONTENT, 取 measuredHeight)
        OverlayState.ballRect = ballParams?.let { "${it.x};${it.y};${ballView?.width ?: 0};${ballView?.height ?: 0}" }
        OverlayState.stripRect = stripParams?.let { "${it.x};${it.y};${it.width};${it.height}" }
        OverlayState.boxRect = boxParams?.let { "${it.x};${it.y};${it.width};${boxRoot?.measuredHeight ?: 0}" }
    }

    /* ── 球 ───────────────────────────────────────────────────────────────── */

    private fun showBall(intent: Intent?) {
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotEmpty() }?.let { OverlayState.url = it }
        val manager = getSystemService(WindowManager::class.java)
        if (manager == null) {
            fail(getString(R.string.overlay_no_window_manager))
            return
        }
        window = manager
        if (ballView == null) {
            val metrics = resources.displayMetrics
            val ball = ballSizePx()
            val spot = BallSpot.read(this)
            val edge = spot?.first ?: BallGeometry.EDGE_RIGHT
            val restY = BallGeometry.clampY(spot?.second ?: (metrics.heightPixels * 2 / 3), metrics.heightPixels, ball)
            val restX = BallGeometry.xForEdge(edge, metrics.widthPixels, ball)
            val view = BallView(this, ballListener)
            val layout = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 球不许获焦: 有它挡着也不该让窗外的触摸变少 —— 除此之外 NOT_TOUCH_MODAL 是把
                // "窗外照旧"这件事写出来, 而不是靠"非获焦"顺带成立
                //
                // **LAYOUT_NO_LIMITS 是半隐的关键** (2026-10-05 真机/模拟器都量到过): 只给
                // LAYOUT_IN_SCREEN 的话, 窗口管理器会把超出屏幕的位置**钳回边界** —— `mAttrs` 里
                // 申请的是 (1017,…), 而真实 `frame` 是 [954,…][1080,…], 也就是球原地不动,
                // 看着就是"半隐藏没成功"。参考那份 README 里"越界不裁剪"说的正是这个标志
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = restX
                this.y = restY
            }
            try {
                manager.addView(view, layout)
            } catch (error: Throwable) {
                fail(getString(R.string.overlay_refused, error.message ?: error.toString()))
                return
            }
            ballView = view
            ballParams = layout
            ballRestX = restX
            ballBaseY = restY
            currentBallX = restX
            phase = BallPhase.SUMMONED
            // 刚出来不半隐: 先让人看见它落在哪儿, 闲置 [BallMinutes.PEEK_IDLE_MS] 之后才自己收边
            noteActivity()
            lastWidth = metrics.widthPixels
            lastHeight = metrics.heightPixels
            // **那一次"怎么用它"的提示已经拿掉了** (主人 2026-10-05: 关于球的所有操作都不要有提示):
            // 手势那几句话现在只在文档与长按菜单里 (菜单自己写着「识屏模式 / 关掉浮标」),
            // 而球一出来就先弹一句 Toast 正是主人点名不要的那种提示
            //
            // 宿主在通道还没开的时候推来的回答 / 提示: 补上 (那两条会自己把框张出来)
            OverlayState.pendingReply?.let { waiting ->
                OverlayState.pendingReply = null
                val from = OverlayState.pendingReplySession
                OverlayState.pendingReplySession = null
                handler.post { deliverReply(waiting, from) }
            }
            OverlayState.pendingNote?.let { waiting ->
                OverlayState.pendingNote = null
                handler.post { deliverNote(waiting) }
            }
        }
        OverlayState.lastError = null
        OverlayState.showing = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        refresh(force = true)
    }

    /**
     * 球上那三段点击 (需求原文: 第一次点击只召出浮标, 第二次点击才打开语音输入, 召出后再次点击
     * 不弹提示、直接进入语音输入)
     *
     * 三个分支不写在这里, 写在 [BallTouch.act] 那个纯函数里 —— 它没有设备也能量, 而这三段恰恰是
     * 最容易"看着对"却点错一段的地方 (见 BallTest)
     *
     * **而"这一下算哪一种"在它外面一层** (2026-10-06 主人加的两条): 双击打断与两档防连击都由
     * [BallTaps.kind] 那个纯函数回答 —— 于是同一个 [BallView.onTap] 的入口要先分出手势, 再谈动作
     */
    private val ballListener = object : BallView.Listener {
        override fun onPressStart() {
            // 半隐收着的球在手指落下的这一瞬间就要滑出来: 晚一步就成了"按住了却拖不动"。
            // 那一条滑动动画也要掐掉 —— 手指一动它就该跟手, 而不是继续走自己的
            snap?.cancel()
            noteActivity()
            unpeek()
            closeMenu()
            // 通道开着时, 碰球也算"点了一下非空白处": 那本框外双击的账要清掉。**菜单开着时不算** ——
            // 那一下是去关菜单的 (这一按会把菜单收掉), 记进通道那本账就成了"关菜单顺带数掉一格"
            //
            // 这几行在 2026-10-06 之前是"通道开着时球那一下什么都不做"那一条的陪衬 (点球 → 清账),
            // 而那一条已经拿掉了 (见 [BallTouch]): 现在点球是**开语音**, 账照旧清 —— 不然"点球 +
            // 点一下空白"会把正在打字的框收掉
            if (boxView != null && menuView == null) lastBlankTapAt = 0L
        }

        override fun onTap() {
            // 先分手势 (单击 / 双击 / 三击 / 太近): 判据全在 [BallTaps.kind] 里, 这里只按它的答案动手
            val now = System.currentTimeMillis()
            // 手势有多严是设置页那一档的事 (见 [BallFeel]): 两条判据都跟着它走, 这里读一次就够
            val feel = BallSpot.feel(this@OverlayService)
            // **刚拖完的那一下不算** (2026-10-08 防误触): 那条账压在下面所有手势之前 —— 松手吸附之后
            // 人常常再补一下, 而那一补要是被当成单击, 球就在刚放好的位置上开了口
            if (BallMinutes.tapAfterDropIsFresh(now, lastDropAt, feel)) {
                note("a tap ${now - lastDropAt}ms after a drop: not counting it as a click")
                return
            }
            val gesture = BallTaps.kind(lastWord, now, lastTapAt, tapChain, feel)
            // **"点一下球"就是认过那一轮失败** (2026-10-08 主人定的口径): 球上正亮着那两个字时,
            // 被认成手势的那一下 (单击 / 双击 / 三击) 把水位线推到**文件里那个 `at`**。三个限制
            // 都是刻意的:
            //
            // - **字亮着才算** (`lastWord == FAILED`): 失败让位给"正在听/正在念"的那几秒里点球是
            //   在关麦克风或掐播报, 那一下不该顺手把一条主人还没看见的失败吞掉 —— 那两个字回来之后
            //   再点一下才算认过
            // - **太近的那一下不算**: 它不算手势, 于是也不算"点了一下球"
            // - **纯按下、拖动、长按不算**: 那几条路根本不走这里 (在 onPressStart / onDragTo 上)
            //
            // **它不影响这一下自己的动作**: 抹字与"开语音"是两件事, 各做各的 —— 主人点的就是一次
            // 普通的点击
            if (gesture != BallTap.TOO_SOON && lastWord == BallWord.FAILED) {
                OverlayState.failedAckAt = OverlayState.failedAt
            }
            when (gesture) {
                // **防连击** (主人 2026-10-06): 手指一抖点出两下时, 第一下已经把球召出来了, 紧接着的
                // 第二下就会顺路进语音输入 —— 而那看着像"点一次直接开了语音"。所以两次点击之间要隔
                // [BallTaps.guard] 那个窗口才算两下 (正在听那一档是主人点名的 1 s), 隔不够只记一次账
                BallTap.TOO_SOON -> {
                    note("a tap ${now - lastTapAt}ms after the last one: too soon to count")
                    return
                }

                // **双击** (双击窗口之内两下): 只有"正在想"那一档有动作 —— 打断正在跑的那一轮 (主人
                // 2026-10-06), 而第一下那个延迟动作当场取消 (见 [pendingTap])。别的档里第二下**什么
                // 都不做**: 那正是防连击要的净效果 ("两下贴着"还是原来那一次单击)
                BallTap.DOUBLE -> {
                    lastTapAt = now
                    tapChain = 2
                    // **第一下那个开麦待办在这里取消** (2026-10-10, 见 [BallCommit]): 这一下既然是
                    // 双击, 就不可能再凑出三击, 于是"点两下"这一档不该留下一声开麦
                    val dropped = dropPendingCommit()
                    // **视频模式里双击不打断** (主人 2026-10-10 选的那一档): 那一档球上永远写「正在听」
                    // (见 [BallStatus.wordFor]), 而"正在想"只会在麦克风意外掉下去的那几百毫秒里露个头
                    // —— 不显式挡一下, 那几百毫秒里点两下就会把一场真的停掉
                    if (lastWord == BallWord.THINKING && !residentVoiceOn()) {
                        interruptBall()
                    } else {
                        note(
                            "a second tap within ${feel.doubleTapMs}ms: " +
                                (if (dropped) {
                                    "the pending voice open was cancelled"
                                } else {
                                    "it does nothing here"
                                }),
                        )
                    }
                    return
                }

                // **三击 = 打开键盘输入那块框** (主人 2026-10-07: "加上三击 ball 打开键盘输入, ball
                // 菜单的键盘输入可以删除"): 三下连着 (每一下都贴着上一记) 才算 —— 见 [tripleTap]
                BallTap.TRIPLE -> {
                    lastTapAt = now
                    tapChain = 3
                    // **确认窗就靠这一行** (2026-10-10): 头一下要是已经投出一个开麦待办, 这一下当场
                    // 把它取消掉 —— 于是三击从头到尾不碰麦克风, 也就没有那一声"开了又关"的卡顿
                    dropPendingCommit()
                    tripleTap(now)
                    return
                }

                BallTap.SINGLE -> {
                    lastTapAt = now
                    tapChain = 1
                    // 新的一串从这里开始: 它只当读数用 (三击落地时量"第一下到键盘"那一段)
                    chainStartedAt = now
                    // 那本"框外双击"的账在 onPressStart 已经清过了 (按下必到那一条), 这里不必再清
                    // **这一下要不要等确认窗** (2026-10-10): 判据在纯函数 [BallCommit.deferMs] 里 ——
                    // 只有"真的要开麦"那几支排队, 其余 (召出 / 收麦 / 掐播报 / 只记召出) 当场做掉。
                    // "正在想"那一档原来就在等 [BallFeel.thinkingTapMs] (要晚过双击与防连击两条线),
                    // 现在与其余几支走同一个待办, 只是那个数由 [BallCommit] 按状态词挑
                    val wait = BallCommit.deferMs(
                        act = currentAct(),
                        word = lastWord,
                        summonFresh = BallMinutes.summonIsFresh(now, summonedAt),
                        replyBox = OverlayState.replyTarget() != null,
                        feel = feel,
                    )
                    if (wait <= 0L) {
                        tapAction(now)
                    } else {
                        // **先把上一次那个待办取消掉**: 上一拍可以也是同一档里的一下单点 (隔得没过确认窗
                        // 的那种), 两个待办都在的话, 先开的那个会被后一个当场关掉 —— 看着像闪了一下
                        pendingTap?.let { handler.removeCallbacks(it) }
                        note("a tap that would open the voice chain: holding it for ${wait}ms")
                        val run = Runnable {
                            pendingTap = null
                            // **到点了再算一次**: 窗里状态可能已经变了 (比如宿主翻成在念, 或者别人
                            // 把麦克风打开了), 那一刻该做的也许已经不是开麦 —— 那就作废, 而不是替
                            // 别人把麦克风关掉 ([tapAction] 的 HUSH 那一支)
                            if (currentAct() == BallAct.LISTEN) {
                                tapAction(System.currentTimeMillis())
                            } else {
                                note("the commit window closed on a different state: dropping the open")
                            }
                        }
                        pendingTap = run
                        OverlayState.tapCommits += 1
                        handler.postDelayed(run, wait)
                    }
                }
            }
        }

        override fun onLongPress() {
            toggleMenu()
        }

        override fun onDragTo(dx: Float, dy: Float) {
            val metrics = resources.displayMetrics
            val ball = ballSizePx()
            // 位移是相对"按下那一点"的, 所以基准只在第一帧取一次 —— 每帧用当前 x 当基准的话,
            // 拖动会随帧率加速 (位置既是结果又是基准)
            if (!ballDragging) {
                ballDragging = true
                dragFromX = ballRestX
                dragFromY = ballBaseY
            }
            ballRestX = BallGeometry.dragX((dragFromX + dx).toInt(), metrics.widthPixels, ball)
            ballBaseY = (dragFromY + dy).toInt().coerceIn(0, (metrics.heightPixels - ball).coerceAtLeast(0))
            applyBallPosition()
            // 文本框跟着球走: 拖动时每一帧都跟 (松手之后那次吸附动画在 moveX 里收尾)
            layoutChannel()
        }

        override fun onDrop() {
            ballDragging = false
            lastDropAt = System.currentTimeMillis()
            noteActivity()
            snapToEdge()
        }

        /**
         * 球换了一档尺寸 (挑过 / 退了外观插件, 或主人在设置里改了那一档)
         *
         * 窗口是照 view 的测量尺寸开的, 所以换档之后**这一拍不能立刻摆位**: view 的新尺寸要等下一趟
         * 测量 ([BallView.onMeasure]) 才生效, 那时算出来的才是对的 —— 于是 `requestLayout` + `post`
         * 走一趟, 让贴边 / 半隐 / 回复框那几条算式都按新的尺寸重算
         */
        override fun onBallSizeChanged() {
            ballView?.requestLayout()
            ballView?.post {
                if (ballView == null) return@post
                val metrics = resources.displayMetrics
                val ball = ballSizePx()
                // 停靠点本身也要按新尺寸夹一次: 球变大之后原来那个 x 可能已经出屏
                ballRestX = BallGeometry.xForEdge(
                    BallGeometry.edgeFor(ballRestX, metrics.widthPixels, ball),
                    metrics.widthPixels,
                    ball,
                )
                ballBaseY = BallGeometry.clampY(ballBaseY, metrics.heightPixels, ball)
                applyBallPosition()
                layoutChannel()
                note("the ball is now ${ball}px: rest=$ballRestX,$ballBaseY peeked=$peeked")
            }
        }
    }

    /**
     * 球现在的窗口边长 (px)
     *
     * **定位那几条算式一律问这个**, 不要自己拿 `BallView.BALL_SIZE_DP` 乘 —— 尺寸有两个来源
     * (主人设的那一档 + 外观插件要求的下限, 见 [BallSize.resolve]), 而且它会在运行中变
     */
    private fun ballSizePx(): Int = ballView?.sizePx() ?: dp(BallView.BALL_SIZE_DP)

    /**
     * 这一档该用哪个环色: 只有「正在想」有自己那个 (按会话固定, 见 [BallPhaseFile]); 别的交给
     * `BallWord.ring()` 那一套固定色
     */
    private fun ringOf(word: BallWord?): Int? =
        if (word == BallWord.THINKING) OverlayState.ringColor.takeIf { it != 0 } else null

    /**
     * 一次**算数的**单击该做什么 (那四段)
     *
     * 从 `onTap` 里抽出来是因为它现在有两个调用方, 而两者的区别只在"什么时候跑": 别的时候当场跑,
     * 而"正在想"那一档要压过双击窗口再跑 (见 [pendingTap])。动作本身一个字都没改 —— `now` 是**跑
     * 的那一刻**, 不是按下的那一刻
     */
    private fun tapAction(now: Long) {
        // 按住球的那一下只算"召出它": 先把读数清掉, 再按那几段走
        when (currentAct()) {
            BallAct.NOTHING -> Unit

            // 收着的时候点一下: 滑回来, 并且把"这一次召出"记下来 —— **那一下绝对不碰语音链**
            // (2026-10-06 主人报的那两条合成了一条病根: 见 [summonedAt] 的说明)
            BallAct.SUMMON -> {
                unpeek()
                summonedAt = System.currentTimeMillis()
            }

            // 全露着的时候点一下: 只有**刚被召出来那一下**之后的第一击才算"第二次点击",
            // 其余的一律当第一次 (也就是只召出, 不开口)。判据是"这一击是不是紧跟着那一次召出"
            //
            // **回复框在屏上且有回复时是例外** (2026-10-06 主人: "有回复窗时也可以点击小球进行语音
            // 输入"): 那时人已经在那块框上说话了, 再要他点两下是多余的 —— 这一下当场开口
            // (判据是纯函数 [BallTouch.opensVoiceNow], 见 BallTest)
            //
            // **「正在想」与「失败」那两档也当场开口** (2026-10-08 主人: "正在想时可以点, 点一次
            // 进入语音输入" —— 那一下本来就已经等过了双击窗口, 见 `onTap`): 球已经全露在外面,
            // "先召出再说话"那套在这儿没有意义, 再点一下只会让人以为点没生效。
            //
            // 「失败」那一档同理: 球上有字就一直是全露着的, 而人点它就是想说句话
            BallAct.LISTEN ->
                if (
                    lastWord == BallWord.THINKING ||
                    lastWord == BallWord.FAILED ||
                    BallTouch.opensVoiceNow(
                        BallMinutes.summonIsFresh(now, summonedAt),
                        OverlayState.replyTarget() != null,
                    )
                ) {
                    summonedAt = 0L
                    listenNow()
                } else {
                    // 已经召出过了 (或者召出很久了) 的这一击**不开口**: 主人要的是"点两次才说话",
                    // 而把这一击也算进去就等于"点一次就说话"
                    summonedAt = now
                    note("this tap only counts as the summon; tap again to speak")
                    unpeek()
                }

            // 正听着的时候再点一下: 收回来。这一下是"切开关", 而"开没开"由状态词自己说
            BallAct.HUSH -> listenNow()

            // 常驻语音那一档 (显示上就是「正在听」): 收掉它的同时把视频模式也退出 (主人 2026-10-10)
            BallAct.LEAVE_VIDEO -> leaveVideoMode()

            // 正在念回答: 掐断它 (主人 2026-10-05 追加的一条)
            BallAct.STOP -> stopSpeaking()
        }
    }

    /**
     * 球上那一下「正在听」把常驻语音收掉, 并把视频模式一并退出 (2026-10-10 主人: "单击关闭后也关闭
     * 视频模式")
     *
     * 走的是**切模式那同一次调用** ([LwModes.switchTo], 与 `lw_mode`、设备上那几个脚本同一个入口):
     * 常驻语音那个记号、相机、`.active` 与提示词四件事一起收口。球这边不拼第二套 —— 少一处就会漂:
     * 记号清了而相机还开着, 或者反过来
     *
     * 那次调用回一份 JSON, 里面 `voice` 那一句是人话 —— 记进现场表, 出问题时有话可说
     */
    private fun leaveVideoMode() {
        noteActivity()
        val said = runCatching {
            LwModes.switchTo(this, LwModes.PHONE).toString()
        }.getOrElse { error -> "failed: ${error.message ?: error}" }
        OverlayState.residentVoiceLeaves += 1
        note("the ball closed the resident voice and left video mode: $said")
        // 记号与 `.active` 都在这一次调用里改完了, 所以立刻重算一遍字 (不等 400 ms 那一拍)
        refresh(force = true)
    }

    /**
     * **三击: 打开键盘输入那块框** (主人 2026-10-07: "加上三击 ball 打开键盘输入, ball 菜单的键盘
     * 输入可以删除")
     *
     * 它替掉的是长按菜单里那一行 ([menuEntries]), 而净效果比"只开框"多一步: 连击的头两下可能已经把
     * 语音那一档开起来了 ([BallTouch.act] 的 `LISTEN` —— 那一下会让麦克风进"听你说一句"那一档),
     * 所以这里**先把那个窗口收掉** ([LwWakeWord.hushWindow], 与球上"再点一下"同一个动作), 再张开键盘
     * 那块框。不这么做就是"麦克风与键盘同时抢这一件事": 框会跟着语音那一档走 ([parkChannel] 把焦点
     * 收掉), 主人想在框里打字却看着球上写着"正在听"
     *
     * `VoiceState.capturing` 那个判据是必须的: 窗口没开着时**不许**调 [LwWakeWord.hushWindow] —— 那
     * 一条是 `startForegroundService`, 在一个不该跑的服务上发它等于把服务与通知重新拉起来
     */
    private fun tripleTap(now: Long) {
        if (VoiceState.capturing) runCatching { LwWakeWord.hushWindow(this) }
        noteActivity()
        // 从这一串的**第一下**到"键盘要来了"那一段 (主人 2026-10-10 报的那一声"卡一下"就量这一个数):
        // 三击的净效果里没有开麦之后, 这一段只剩弹框本身的开销
        val span = if (chainStartedAt != 0L) now - chainStartedAt else 0L
        OverlayState.lastTripleMs = span
        note("triple: keyboard asked for ${span}ms after the chain's first tap")
        openChannel(focus = true)
    }

    /**
     * 双击打断 (主人 2026-10-06): **状态与对话两个都要停**
     *
     * 说的是"正在想"那一档, 而"正在想"是宿主推来的 (`overlay op=phase`, 见 host-plugin 的
     * `startBallPhase`) —— 应用这一侧没有第二条路看得出"模型在干活", 所以打断也只能**请宿主去做**:
     * 这一句写进 [VoiceInbox] (那一条是应用 → 宿主唯一的门), 插件那侧认出来就 `agent.cancel` 掉
     * 浮标那一场正在跑的轮, 并顺手把 `phase` 推回 `idle`
     *
     * 为什么不是新开一条桥: 现有的方向是"宿主问、应用答", 反过来的门就那一份队列 (理由写在
     * [VoiceInbox] 的文件头), 而命令句那一套本来就认得"说给手机听的一句话" —— 这一句与"打开识屏模式"
     * 是同一种东西, 只是它落在打断那一支上
     *
     * **状态不在这里改** (2026-10-08 主人定的口径): 那一轮真的停了, 宿主才写 `ball-phase.json`, 球上
     * 那三个字才落 —— 应用**不再抢先清字**。原来那一下是"先落下、宿主再纠正", 而真相是宿主说了算:
     * 打断没成 (那一场根本不在跑) 时, 字挂着才是对的
     *
     * 收件箱写不进去时不能装作打断成功, 所以失败走 [hint] 说出来, `phase` 一个字节都不动
     */
    private fun interruptBall() {
        noteActivity()
        val seq = VoiceInbox.append(this, VoiceCommands.INTERRUPT, VoiceInbox.SOURCE_BALL)
        if (seq == null) {
            hint(getString(R.string.ball_interrupt_failed))
            return
        }
        OverlayState.interrupts += 1
        note("interrupt queued as #$seq: the turn that is running was asked to stop")
        refresh(force = true)
    }

    /**
     * 松手吸附最近那一边, 落定之后写存盘, 然后收边
     *
     * 停靠即收边 (参考那套的 `adsorb(halfHide)` 就是这个意思) —— 它在别的应用上一直是一块挡视线的
     * 东西, 而停靠这个动作本身已经说明"我放这儿了"; 有事 (正在听 / 正在想 / 正在念) 或者人碰它时
     * 立刻滑回来, 见 [unpeek]
     *
     * **"停留一会儿就收边"那一档已经不在这儿了** (批次 5): 收边的判据统一成了"闲置
     * [BallMinutes.PEEK_IDLE_MS]"[hideNow], 由 `refresh` 那 400 ms 一拍推着走 —— 一处判据
     * 比"松手收一次、闲着又收一次"两条路好对账
     */
    private fun snapToEdge() {
        val metrics = resources.displayMetrics
        val ball = ballSizePx()
        val target = BallGeometry.snapX(ballRestX, metrics.widthPixels, ball)
        val from = ballParams?.x ?: ballRestX
        ballRestX = target
        peeked = false
        moveX(from, target) {
            val edge = BallGeometry.edgeFor(target, metrics.widthPixels, ball)
            BallSpot.write(this@OverlayService, edge, target, ballBaseY)
            applyBallPosition()
        }
    }

    /**
     * 侧边半隐 (参考那套的 `floating_ball_idle_to_edge`): 闲置 [BallMinutes.PEEK_IDLE_MS] 之后
     * 把球挪出去一半
     *
     * 只留一半在外面 —— 它在别的应用上一直是块挡视线的东西, 而闲着的时候它没有任何话要说; 有事
     * (正在听 / 正在想 / 正在念)、菜单或通道开着、人正在碰它时都不收, 见 [unpeek]
     *
     * **不做成"缩成一个小点"**: 缩尺寸要动窗口大小, 那样半隐与展开是两套几何; 挪坐标只有一套
     *
     * 回 false = 这一下没收 (正忙 / 已经在收着 / 没窗): 调用方据此决定要不要改 [phase] —— 收边
     * 真的发生了才谈得上"下一次点击只召出"
     */
    private fun peek(): Boolean {
        if (peeked || ballDragging || menuView != null || phase == BallPhase.VOICE) {
            OverlayState.peekBlocked = when {
                peeked -> "already peeked"
                ballDragging -> "dragging"
                menuView != null -> "menu open"
                else -> "voice phase"
            }
            return false
        }
        val layout = ballParams ?: run {
            OverlayState.peekBlocked = "no window yet"
            return false
        }
        val ball = ballSizePx()
        val edge = BallGeometry.edgeFor(ballRestX, resources.displayMetrics.widthPixels, ball)
        peeked = true
        OverlayState.peekBlocked = ""
        OverlayState.peekCalls += 1
        moveX(layout.x, BallGeometry.peekX(ballRestX, edge, ball))
        // 收边只动位置: 那一档该多亮由"半透明"自己说了算 (见 [BallMinutes.alphaFor])
        setBallAlpha(BallMinutes.alphaFor(BallSpot.translucent(this)))
        return true
    }

    /**
     * 滑回来: 有人碰它, 或者它有状态要说了
     *
     * **不再以 `peeked` 为门槛** (2026-10-06): 原来第一句是 `if (!peeked) return`, 而 `peeked` 与
     * "窗真的在半隐位"这两件事会分家 —— [moveX] 会 `snap?.cancel()` 掉一条正在跑的动画, 于是
     * "意图已经翻过来而位置还留在半路"这种状态没有任何人会去纠正它。现在判的是窗**现在**在不在
     * 停靠位, 不一致就摆回去 (重复调用是幂等的)
     */
    private fun unpeek() {
        val layout = ballParams ?: return
        val want = ballXFor(peeked = false)
        if (!peeked && layout.x == want && snap?.isRunning != true) return
        peeked = false
        moveX(layout.x, want)
        // 回弹这一下一定是"有人在用它": 那一档不算, 全亮全露 —— 半透明的账下一拍自己重算
        setBallAlpha(BallMinutes.alphaFor(translucent = false))
    }

    /** 窗该在的 x: [wanted] 为真 (或 [peeked] 为真) 时是半隐位, 否则是停靠位 */
    private fun ballXFor(peeked: Boolean = this.peeked): Int {
        val ball = ballSizePx()
        val edge = BallGeometry.edgeFor(ballRestX, resources.displayMetrics.widthPixels, ball)
        return if (peeked) BallGeometry.peekX(ballRestX, edge, ball) else ballRestX
    }

    /**
     * 位置与意图对不对账: 不对就按意图摆一次, 并记一行
     *
     * 这一条是 2026-10-06 主人报的第三条 ("说话"点两次之后球不再空闲半隐) 的兜底: 那两个动作会把
     * 停靠、半隐、两次"召出"撞在一起, 而 [moveX] 每次都要 `snap?.cancel()` —— 中间那一下被掐掉
     * 之后留下的位置是谁也不管的。400 ms 一拍回头对一次账, 于是"卡住"这件事最多只能撑一拍
     *
     * 只在**没有动画在跑**的时候动手: 动画跑着的时候位置本来就是移动中的 (那几帧不算数)
     */
    private fun settleBall() {
        val layout = ballParams ?: return
        if (snap?.isRunning == true || ballDragging) return
        val want = ballXFor()
        if (layout.x == want) return
        note("the ball was ${layout.x} but ${if (peeked) "peeked" else "resting"} wants $want: putting it back")
        moveX(layout.x, want)
    }

    /**
     * 把球的窗口挪到某个 x (半隐那个偏移已经算在里面): 吸附与半隐共用这一条动画
     *
     * 收尾那一下要把 [layoutChannel] 再叫一次: 动画中间的位置不值一提 (那几帧人眼看不出文本
     * 框掉队), 而**落定的那个 x** 才是文本框该待的地方
     */
    private fun moveX(from: Int, to: Int, done: (() -> Unit)? = null) {
        snap?.cancel()
        currentBallX = to
        OverlayState.lastMoveTo = to
        snap = ValueAnimator.ofInt(from, to).setDuration(BallMinutes.EDGE_SNAP_MS).apply {
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val held = ballParams ?: return@addUpdateListener
                held.x = animator.animatedValue as Int
                OverlayState.ballWindowX = held.x
                runCatching { window?.updateViewLayout(ballView, held) }
                // 半隐那一下球是一格一格滑的: 涟漪不跟着走就会看到它自己留在原地
                placeRipple()
            }
            doOnEnd {
                done?.invoke()
                layoutChannel()
            }
            start()
        }
    }

    /** 球的窗口位置 = 停靠点 (+ 半隐偏移) + 键盘那一层的钳制, 一切挪动都从这里走 */
    private fun applyBallPosition() {
        val layout = ballParams ?: return
        val metrics = resources.displayMetrics
        val ball = ballSizePx()
        val edge = BallGeometry.edgeFor(ballRestX, metrics.widthPixels, ball)
        layout.x = if (peeked) BallGeometry.peekX(ballRestX, edge, ball) else ballRestX
        layout.y = BallGeometry.clampAboveIme(ballBaseY, imeBottom, metrics.heightPixels, ball)
        runCatching { window?.updateViewLayout(ballView, layout) }
        currentBallX = layout.x
        OverlayState.x = ballRestX
        OverlayState.y = ballBaseY
        // 半隐是"球收边了没有"那一条判据的读数 (窗口坐标差 63 px, 光看图很难分)
        OverlayState.peeked = peeked
        // 涟漪窗跟着球心 (拖着球说话时它每一帧都要跟)
        placeRipple()
        // 球这一块的坐标也进那份读数: 拖完那一下要看得见它落在哪儿 (转屏那次则由 [noteRects] 一起写)
        noteRects()
    }

    /**
     * 闲置那一档为什么不收边: 这一拍算出来的那一档与**为什么**, 一行一句写进 `files/ball-idle.txt`
     *
     * 只在**原因变了**的时候写, 所以正常跑一整天也就几行。它是排查用的: app 自己的 logcat 在这台
     * 模拟器上被系统滤掉了, 而"5 秒到了却没半隐"这种问题只有现场数说得清 (2026-10-06 那三条就是
     * 靠这份表定位的)
     */
    private fun traceIdle(wait: BallWait) {
        val now = System.currentTimeMillis()
        // **两档任一变了都要落一行**: 休闲第一阶段到点时 `reason` 仍是 `waiting`, 只拿它当判据的话
        // "到点了却没变淡"这件事在现场表里一个字都看不见 (2026-10-17 加)
        if (wait.reason == lastIdleReason && wait.translucent == lastIdlePale) return
        lastIdleReason = wait.reason
        lastIdlePale = wait.translucent
        val line = "$now ${wait.reason}" +
            // pale 是"这一刻该不该半透明" (2026-10-17 加的两阶段里的第一阶段): 与 peek 分开记,
            // 因为"该淡却没淡"与"该藏却没藏"是两条不同的账
            " pale=${wait.translucent}" +
            " idle=${now - lastActiveAt} peeked=$peeked" +
            " keyboard=$overlayKeyboard phase=${phase.name}" +
            " listening=${VoiceState.capturing} menu=${menuView != null} held=$ballDragging" +
            " calls=${OverlayState.peekCalls} blocked=${OverlayState.peekBlocked}\n"
        runCatching { File(filesDir, "ball-idle.txt").appendText(line) }
    }

    /** 上一次写进那份现场表的原因: 只在它 (或半透明那一档) 变了的时候才落盘 */
    private var lastIdleReason: String? = null

    /** 上一次写进那份现场表的半透明档: 与 [lastIdleReason] 一起决定"这一拍算不算变了" */
    private var lastIdlePale: Boolean? = null

    /**
     * 闲置那一笔账从这里重新算, 并且**记一行**
     *
     * 一处判据 ([refresh] 那 400 ms 一拍) 加上一处记账, 就是"空闲 5 秒收边"的全部实现。这里的
     * `reason` 进的是同一份现场表 —— 那一笔账是谁写的与"这一拍是什么档"对不上时, 这一行就是答案
     */
    private fun resetIdleWait(reason: String) {
        lastActiveAt = System.currentTimeMillis()
        // 刚落完账: 两档都还谈不上 (半透明那一位是"这一刻该不该淡", 见 [alphaOf])
        traceIdle(BallWait(peek = false, translucent = false, reason = reason))
    }

    /** 有人动过它 (或者它自己说了一次状态): 闲置那一笔账从这里重新算 */
    private fun noteActivity() {
        resetIdleWait("activity")
    }

    /**
     * 这一拍: 球该收边吗 —— **唯一的一处判断**是 [BallRest.wait], 这里只负责把那几个读数喂给它,
     * 再按它的答案动手
     *
     * 六条闸 (手指按着 / 菜单开着 / 正在听 / 键盘在用 / 输入通道开着 / 闲够 5 s 了) 都在那个纯函数里,
     * 所以"为什么没收边"这件事没有设备也量得动 (见 BallTest)
     *
     * **通道开着那一条是 2026-10-06 加的**: 回答一到框会自己弹出来 ([deliverReply]), 而那时它**不抢
     * 焦点** (键盘不弹), 于是"键盘在用"那一条不成立 —— 少了这一条, 球会在回答出现的同时半隐溜走,
     * 而框是跟着球走的 (那条规矩见 [layoutChannel])
     */
    private fun waitNow() {
        val now = System.currentTimeMillis()
        val wait = BallRest.wait(
            phase = phase,
            peeked = peeked,
            dragging = ballDragging || snap?.isRunning == true,
            menuOpen = menuView != null,
            listening = VoiceState.capturing,
            keyboard = overlayKeyboard,
            channelOpen = boxOnScreen,
            // 「休闲半隐藏」关着时, 那一条闸永远不成立 —— 于是纯函数 [BallRest.wait] 与它的单测
            // 一个字都不用改 (2026-10-15 主人要的是开关, 不是改判据)
            idleDue = BallMinutes.idleDue(now, lastActiveAt) && BallSpot.autoPeek(this),
            // 两个状态**同一个 5 秒到点** (主人 2026-10-17: "时间与透明度都和以前的一样"), 各看各的
            // 开关: 半隐那一档问 [BallSpot.translucent], 半藏那一档问 [BallSpot.autoPeek]
            translucentDue = BallMinutes.idleDue(now, lastActiveAt) && BallSpot.translucent(this),
        )
        OverlayState.ballWait = wait.reason
        traceIdle(wait)
        if (wait.peek) hideNow()
        // 两个开关都可能在设置里刚被改过: 没在收边、也没有滑行动画时校正一次 (改完当场看得见)
        if (!peeked && snap?.isRunning != true) {
            setBallAlpha(BallMinutes.alphaFor(wait.translucent))
        }
    }

    /**
     * 把不透明度改过去 (变淡与变亮都带一小段过渡)
     *
     * 从全亮直接跳到三成半会看着像闪一下, 而休闲本来就是慢慢来的 —— 时长取 [BallMinutes.EDGE_SNAP_MS],
     * 与收边那一下同一个节拍。**目标值已经对上就一个字节都不动**: 这一拍是 400 ms 一次, 反复启动
     * 同一条动画会让它永远走不完
     *
     * 那个值由 [BallMinutes.alphaFor] 算 —— 它只认"半透明"那一档, **半隐一个字节都不参与**
     * (2026-10-17 主人: "改的应该是状态而不是时间")
     */
    private fun setBallAlpha(target: Float) {
        // 读数先写: 它就是"这一刻该多亮"的答案, View 还没挂上也照样报得出来 (见 [OverlayState.ballAlpha])
        OverlayState.ballAlpha = target
        val view = ballView ?: return
        if (view.alpha == target) return
        runCatching { view.animate().alpha(target).setDuration(BallMinutes.EDGE_SNAP_MS).start() }
    }

    /* ── 输入条 ───────────────────────────────────────────────────────────── */

    /** 展开: 把那条可获焦的窗挂上 (页面留着, 摘下来再挂回去不必重载) */
    private fun expand(intent: Intent?) {
        val manager = window ?: return
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotEmpty() }?.let { OverlayState.url = it }
        val url = OverlayState.url ?: (DshHost.status as? HostStatus.Running)?.url
        if (url.isNullOrEmpty()) {
            hint(getString(R.string.overlay_no_url))
            return
        }
        // 两块可获焦的窗不该同时开着 (它们会抢输入法): 通道让位, 页面那一块上
        closeChannel()
        if (stripView != null) {
            OverlayState.expanded = true
            return
        }
        val metrics = resources.displayMetrics
        val margin = dp(MARGIN_DP)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 0).orZero().takeIf { it > 0 }
            ?: StripSpot.width(metrics.widthPixels, margin)
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 0).orZero().takeIf { it > 0 }
            ?: StripSpot.height(metrics.heightPixels, margin, DEFAULT_HEIGHT_PERCENT)
        val layout = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 这一块是**照着获焦去的**: 只有能获焦的窗才收得到系统输入法, 代价是展开期间窗外
            // 不再穿透 —— 球那颗常驻的不获焦, 两边合起来才是"平时不挡, 打字时才占住"
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = intent?.getIntExtra(EXTRA_X, 0).orZero().takeIf { it > 0 } ?: StripSpot.x(margin)
            y = intent?.getIntExtra(EXTRA_Y, 0).orZero().takeIf { it > 0 }
                ?: StripSpot.restY(metrics.heightPixels, height, margin, dp(STRIP_LIFT_DP))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val view = stripView ?: buildStrip(url)
        try {
            manager.addView(view, layout)
        } catch (error: Throwable) {
            fail(getString(R.string.overlay_refused, error.message ?: error.toString()))
            return
        }
        stripView = view
        stripParams = layout
        stripBaseY = layout.y
        OverlayState.expanded = true
        OverlayState.url = url
        refresh(force = true)
    }

    /** 收起: 把输入条从窗口上摘下来, 球留着 (页面实例不销毁) */
    private fun collapse() {
        val view = stripView ?: return
        runCatching { window?.removeView(view) }
        OverlayState.expanded = false
        // 键盘那点高度是输入条上报的, 而它刚被摘下来 —— 摘下来之后还可能来一次收尾的 inset 回调,
        // 所以这里先把那个数清掉并在下一拍把球放回主人放的那个 y。**2026-10-05 真机上抓到的**:
        // 收起之后球比主人放的位置高了 440 px, 就是那次收尾回调把"键盘还占着 891 px"留在状态里
        imeBottom = 0
        handler.postDelayed({ runCatching { applyBallPosition() } }, BallMinutes.IME_SETTLE_MS)
        refresh(force = true)
    }

    /**
     * 闲置到点了: 收边, 并且改记"收着"这一档
     *
     * 分两步是**故意的**: [peek] 里的那几条"什么时候不该收" (有人正按着、菜单开着、语音在跑) 一条
     * 都不该被绕过, 所以这里先让它自己判, 判过了才把 [phase] 改成 [BallPhase.RESTED] —— 那一下
     * 正是"下一次点击只召出"的开关
     */
    private fun hideNow() {
        if (peeked) return
        // 真的收下去了才承认"现在是收着"这一档: 正听着、有人正按着、菜单开着的时候 [peek] 会拒绝
        if (peek()) phase = BallPhase.RESTED
    }

    /**
     * 关掉浮标: **把窗真摘下来**, 再把存盘那个开关关掉, 最后才停服务
     *
     * 顺序与分工都是被真机量出来的 (2026-10-06, vivo V2417A / 2.0.0 debug):
     *
     * **改动之前的写法是"写偏好 + `stopSelf()`"就完了, 而那样球不会消失**。实测的三件事实:
     * `dumpsys activity services` 里 `.overlay.OverlayService` **没了** (服务真的停了)、`ball-on`
     * 写成了 `false`、而 `dumpsys window windows` 里那块 `type=2038` 的球窗**还在屏上** (frame
     * 一动不动)。原因是摘窗从来没人做过: [onDestroy] 只把 `ballView` 置 null, 而**服务停掉不会让
     * 它加的窗自己消失** —— 那块窗由窗口管理器拿着, 要等**进程**死才收; 这台设备上进程不会死, 因为
     * `DshHostService` 还开着。于是"关掉浮标"表现为"球赖在桌面上", 设置页那个开关也一起看着没生效
     * (它的 `showing` 读的是内存里的记号, 而屏幕上那块窗不读那个记号)
     *
     * 所以这里四步一个都不能少: **摘窗 → 读回确认 → 写偏好 → 停服务**。摘窗放在最前面是因为它是
     * 唯一一件"只有服务活着才做得到"的事 ([WindowManager.removeView] 要那个 window token), 而停服务
     * 之后 `window` 就是 null 了
     *
     * **摘不干净要说出来**: 读回之后还有残留就把原因写进 [OverlayState.hideFailed] 并记一行日志 ——
     * "没关掉"与"关掉了"对主人是两回事, 不许静默
     */
    private fun hideBall(from: String) {
        // 上一趟留下的读数先清掉: 不清的话"这一次摘干净了"会带着上一次那句失败的话一起报出去
        OverlayState.hideFailed = null
        // 四块窗一起收: 菜单与通道是可获焦的那两块, 先收它们, 免得摘球的时候它们还抓着输入法
        runCatching { closeMenu() }
        runCatching { closeChannel() }
        runCatching { detachRipple() }
        runCatching { collapse() }
        // 摘窗那一段整块包起来: 它里面可能抛 (已经摘过的 view 再摘一次就会), 而**后面那两步一步都
        // 不能省** —— 偏好不写, 下一次应用起来球自己又冒出来; 服务不停, 那块窗连摘都摘不掉
        val gone = runCatching { detachBall() }
            .onFailure { Log.w(TAG, "taking the ball window off failed outright", it) }
            .getOrElse { failure ->
                OverlayState.hideFailed = failure.message ?: failure.toString()
                false
            }
        // 偏好要写: 它是"下一次应用起来要不要把球放出来"那一个记号
        runCatching { BallSpot.setOn(this, false) }
        OverlayState.showing = false
        OverlayState.expanded = false
        OverlayState.channel = false
        OverlayState.word = null
        OverlayState.running = false
        val note = "hide the ball (asked by $from): window gone=$gone, " +
            (OverlayState.hideFailed?.let { "problem: $it" } ?: "no leftovers")
        OverlayState.lastHint = note
        if (gone) Log.i(TAG, note) else Log.w(TAG, note)
        handler.removeCallbacksAndMessages(null)
        stopSelf()
    }

    /**
     * 把那块球窗从窗口管理器上摘下来, 回一句"真的掉了没有"
     *
     * 抽出来是因为它有两个调用方, 而它们的理由不一样: [hideBall] 是主人点名要关 (要回执; 摘不掉的
     * 兜底走 `WindowManagerGlobal` 那一笔静态账 —— 与 `LwVirtualDisplay` 那边反射隐藏 API 是同一种
     * 取舍, 一块摘不掉的窗会一直挡在别人的屏幕上), 而 [onDestroy] 是"不管谁把服务停了, 都不许剩下
     * 一块没人管的窗" (那一句回执它不看)
     *
     * **服务停掉不会让它加的窗自己消失**, 这是这个函数存在的唯一理由 (2026-10-06 真机实测: 服务没了、
     * `ball-on` 是 false、而那块 `type=2038` 的窗还留在屏上)。
     */
    private fun detachBall(): Boolean {
        val ball = ballView ?: return true
        if (window != null && ball.isAttachedToWindow) {
            runCatching { window?.removeView(ball) }
                .onFailure { Log.w(TAG, "removeView refused the ball, trying removeViewImmediate", it) }
            if (ball.isAttachedToWindow) {
                runCatching { window?.removeViewImmediate(ball) }
                    .onFailure { Log.w(TAG, "removeViewImmediate refused it too", it) }
            }
        }
        if (!ball.isAttachedToWindow) return true
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            global.getMethod("removeView", View::class.java, Boolean::class.javaPrimitiveType)
                .invoke(instance, ball, true)
        }.onFailure { error ->
            OverlayState.hideFailed = "WindowManagerGlobal would not do it either: ${error.message ?: error}"
        }
        return !ball.isAttachedToWindow
    }

    /**
     * 球上那一下「说话」——**它是一个开关, 不是一个只能开的门**
     *
     * 空着的时候点一下: 开一次"说一句话"的窗口 (`LwWakeWord.speakNow`), **不是页面自己的麦克风** ——
     * 球、输入通道与主页三处共用同一个识别器
     *
     * 正在听的时候再点一下: 把这一句话的窗口**收回来** (与输入框上沿那个胶囊同一个动作, 唤醒词接着
     * 守), 于是同一个球可以反复切换: 点开、点关、再点开 (主人 2026-10-05 定的)
     *
     * **这两下都不出声** (主人 2026-10-05: 关于球的所有操作都不要有提示): "开没开"由球上那三个字
     * 说, 结果照旧记一笔进 [OverlayState.lastHint] 与日志 (`lw_overlay op=state` 的 `said` 读它)。
     * **不成功的那一条仍然要说** —— 缺权限 / 缺模型 / 起不来这几种情形不说的话, 点一下什么都没发生
     */
    private fun listenNow() {
        closeMenu()
        noteActivity()
        note(if (VoiceState.capturing) hushWindow() else speakNow())
    }

    /**
     * 那两下**真的做的那件事** (`LwWakeWord` 那两个入口), 与记账分开
     *
     * 那两条是两条不同的路 (开一个窗口 / 收一个窗口), 而把它们与记账分开是为了"这一下做了什么"
     * 只有一处说得出: 开没开的判据只有一个 ([VoiceState.capturing]), 两句回执也都是人话 —— 失败
     * 那一条 (缺权限 / 缺模型 / 起不来) **必须说出来**, 点了一下什么都不发生是最不能接受的那种失败
     */
    private fun speakNow(): String {
        val refusal = runCatching { LwWakeWord.speakNow(this) }
            .getOrElse { error -> error.message ?: error.toString() }
        // 开语音那一下**把框停一下, 不再收掉** (2026-10-06 主人: "有回复窗时也可以点击小球进行语音
        // 输入", 而且那一句还要投给框里那一场): 键盘与麦克风不能抢, 所以焦点与键盘要收; 而框本身
        // 留着 —— 回复内容与"这一场是谁"都在它身上, 收掉之后回答就没地方画了
        if (refusal == null) runCatching { parkChannel() }
        return refusal ?: "voice input is on"
    }

    /**
     * 语音要开口时把框"停"一下: **窗留着**, 只把焦点与键盘收掉
     *
     * 与 [closeChannel] 的分工: 那一条是"这一轮用完了" (框外双击 / 闲置 20 s 到点), 这一条是
     * "接下来这一句用嘴说"。开语音这一刻也算一次"有人用它" ([OverlayState.noteBoxActivity]), 于是
     * 20 s 那一笔账不会在主人说话的时候把框收走 (主人点名的"这个语音输入可重置回复框的空闲消失计时")
     */
    private fun parkChannel() {
        val view = boxView ?: return
        if (!boxOnScreen) return
        view.blurInput()
        boxFocused = false
        OverlayState.noteBoxActivity()
        noteActivity()
        refresh(force = true)
    }

    private fun hushWindow(): String {
        val stopped = LwWakeWord.hushWindow(this)
        return getString(if (stopped) R.string.ball_voice_stopped else R.string.ball_voice_stop_failed)
    }

    /**
     * 掐断正在念的回答 (主人 2026-10-05: "念回复时再点一次球可停止播报")
     *
     * 走的是**本进程里那条停止路** ([LwSpeak.stop]), 而不是"让宿主去点页面那个按钮": 念回答的是
     * 应用自己那个 TTS (`lw_speak` / `LwTts`, 见 `startReadAloud` 里那个 `call('speak', …)`), 所以
     * 直接掐它才是最短的一条; `LwSpeak.stop` 两条引擎都停 (系统 TTS 与自带的 `AudioTrack`), 而且
     * 顺手把半双工那一道闸放回去 ([VoiceState.speaking])
     *
     * 结果照旧不出声: 记一笔进状态与日志, `lw_overlay op=state` 看得见
     */
    private fun stopSpeaking() {
        noteActivity()
        val answer = runCatching { LwSpeak.stop() }
            .getOrElse { error -> error.message ?: error.toString() }
        note("stop reading: $answer")
    }

    /* ── 菜单 ─────────────────────────────────────────────────────────────── */

    private fun toggleMenu() {
        if (menuView != null) closeMenu() else showMenu()
    }

    private fun closeMenu() {
        val view = menuView ?: return
        menuView = null
        runCatching { window?.removeView(view) }
        // 这一下**不记进"点空白"那本账**: 菜单与通道是两块窗, 关菜单那一下是主人对菜单说的,
        // 不是对通道说的 —— 记进去的话"关菜单 + 点一下空白"就会把通道收掉
        //
        // 但它**算一次召出**: 主人从菜单里回来, 球是全露着的, 紧接着那一击照"点两次才说话"的规矩
        // 只该召出 —— 不记的话那一击会当"点一次直接开语音"
        summonedAt = System.currentTimeMillis()
    }

    /** 把输入条摘下来 (通道要开的时候让位): 页面实例留着, 与 [collapse] 的区别是不动 inset 那一笔 */
    private fun closeStrip() {
        val view = stripView ?: return
        runCatching { window?.removeView(view) }
        OverlayState.expanded = false
        imeBottom = 0
    }

    /**
     * 长按出来的那个菜单
     *
     * 它是第三块窗, 而不是 PopupWindow: overlay 窗里的 PopupWindow 要另设 windowLayoutType 才挂得上
     * 别处, 而这里已经有"加窗 / 摘窗"这一套现成的
     *
     * **它可获焦** (与球相反): 菜单开着的时候点外面应当关掉菜单, 而不是顺手按到底下那个应用的按钮上
     */
    private fun showMenu() {
        val manager = window ?: return
        val ball = ballParams ?: return
        val metrics = resources.displayMetrics
        val themed = ContextThemeWrapper(this, R.style.Theme_Luwi)
        val column = MenuRoot(themed).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(MENU_BACKGROUND)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            onOutside = { closeMenu() }
        }
        column.addView(
            label(themed, getString(R.string.ball_menu_title, modeName())),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        menuEntries().forEach { (title, run) ->
            column.addView(menuRow(themed, title) { run() })
        }
        val width = dp(MENU_WIDTH_DP)
        val height = dp(MENU_HEIGHT_DP)
        val size = ballSizePx()
        val layout = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH = 点外面时我收到一个 OUTSIDE 事件并自己关掉,
            // 而底下那个应用**不会**同时被按一下
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val onRight = ball.x + size / 2 > metrics.widthPixels / 2
            x = if (onRight) (ball.x - width - dp(8)).coerceAtLeast(0) else ball.x + size + dp(8)
            y = ball.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        }
        try {
            manager.addView(column, layout)
        } catch (error: Throwable) {
            hint(getString(R.string.overlay_refused, error.message ?: error.toString()))
            return
        }
        menuView = column
        column.isFocusableInTouchMode = true
        column.requestFocus()
        column.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                closeMenu()
                true
            } else {
                false
            }
        }
    }

    /**
     * 菜单里那两行: 返回应用 / 关掉浮标 (有人占着相机时中间多一行「释放视频模式」)
     *
     * **「返回应用」那一行是 2026-10-07 加回来的** (主人: "ball菜单里加上返回应用按钮"): 它是"回应用"
     * 的第二个入口 ([returnToApp]), 与**回复框上双击**同一个去向 —— 回复框点名优先, 否则浮标账本,
     * 顺带把框收起来。第一次加的是「回应用」那个名字, 这一版按主人给的名字改成「返回应用」
     *
     * **「说话」那一行拿掉了** (主人 2026-10-06): 点球本来就是"点两次进语音输入", 菜单再放一个入口
     * 只是同一件事的第二扇门 —— 而点球那条路一直都在 ([BallTouch] 那几段)
     *
     * **「识屏模式」那一行 2026-10-08 拿掉了** (批次 4: 那个模式整个摘掉): 那块屏现在归手机模式管, 而
     * "用户得先切一个模式, 模型才看得见屏"正是那一批要消掉的心智负担 —— 指代不明的那句话现在会在投递
     * 前自动附一张主屏截图。模式名照旧写在标题上 ([modeName]), 说"打开/退出识屏模式"也照旧有人认
     *
     * **「键盘输入」那一行 2026-10-07 拿掉了** (主人: "加上三击 ball 打开键盘输入, ball 菜单的键盘
     * 输入可以删除") —— 同一个去向挪到**球上三击** ([tripleTap]), 菜单里不再有第二扇门
     *
     * **「回应用」那一行 2026-10-07 又拿掉了** (主人: "双击回复框相当于回应用, ball 菜单的「回应用」
     * 选项可以删掉") —— 同一个去向挪到了**回复框上双击** ([BoxView.Listener.onReplyDoubleTap]), 而且
     * 目标更准确: 那条回复是哪一场发来的就回到哪一场。它做的事比"把应用提到前面"多一件: 顺带把会话
     * 界面带到那一场对话 (见 [openApp] 与 [BallReturn])。**同一天它又以「返回应用」这个名字回到菜单**
     * (主人: "ball菜单里加上返回应用按钮") —— 名字换了, 去向与上面那一条完全一样
     *
     * **「一直听」那一行早就拿掉了** (2026-10-06): 它是"允许常驻语音"那个许可的入口, 而那条一直不收的
     * 识别链整个下线了。**模式那两行也拿掉了** (主人 2026-10-05: 默认手机模式, 长按菜单里不要模式选择
     * 项) —— 从那以后菜单里只剩识屏那一档, 而它这一批也走了: 菜单现在只有"回应用"与"关掉浮标"两件事,
     * 加上"有人占着相机"时那一行
     */
    private fun menuEntries(): List<Pair<String, () -> Unit>> = buildList {
        add(getString(R.string.ball_menu_return) to { closeMenu(); returnToApp() })
        // 「换个形象」(2026-10-16): 当场在已装外观之间轮换一圈, 不用进设置页。装了几个就转几个,
        // 只有一项 (只有内置画法) 时那一下什么都不换 —— 菜单行还在, 但它不该装作换过了
        add(
            getString(
                R.string.ball_menu_theme,
                BallTheme.nameOf(this@OverlayService, runCatching { BallTheme.currentId(this@OverlayService) }.getOrDefault(""))
                    .ifBlank { getString(R.string.ball_theme_builtin) },
            ) to { closeMenu(); rotateTheme() },
        )
        // 「看余额」(2026-10-16): 写进通道那块文本框 (主人定的显示位置), 不弹窗也不上球面
        add(getString(R.string.ball_menu_balance) to { closeMenu(); showBalance() })
        // 相机占用表那一行 (批次 3): **只在真的有人占着时出现** —— 平时它什么都不做, 而一个永远在那儿
        // 的空行只会把菜单撑长。它是表卡住时手上最快的那条出口 (另一条在设置页「视频识别」段里)
        if (CameraOwner.read(this@OverlayService) != null) {
            add(getString(R.string.ball_menu_release_camera) to { closeMenu(); releaseCameraOwner() })
        }
        add(getString(R.string.ball_menu_close) to { closeMenu(); hideBall("menu") })
    }

    /**
     * 「换个形象」那一拍: 轮换到下一个, 然后当场重画 (不等那 400 ms 的一拍)
     *
     * [BallTheme.rotate] 只有一项 (没装任何外观插件) 时原样返回, 那就不值得重画 —— 但读数里那一下
     * 还是要记的 (主人点了它而球没变, 回执要能看出"为什么没变")
     */
    private fun rotateTheme() {
        val now = runCatching { BallTheme.rotate(this) }.getOrDefault("")
        note("theme rotated to ${now.ifBlank { "builtin" }}")
        if (now.isBlank()) return
        val current = ballView?.word
        ballView?.show(current, ring = ringOf(current), peeked = peeked)
    }

    /**
     * 「看余额」那一拍: **查的是当前模型那一边** (宿主先认模型再认余额源) —— 新鲜的直接给, 已经有
     * 答案的失败也直接给, 否则先「正在取」再给宿主递一个请求记号
     *
     * 数到真身之后 [readBalance] 会把那一行**就地改写** ([BoxView.replaceInfo]), 屏上不会留两行
     *
     * 余额源那一侧 2026-10-17 从"只有 DeepSeek 官方"扩到主流厂商 (宿主那张表里现在有 DeepSeek /
     * 月之暗面 / OpenRouter / 阶跃 / Novita / PPIO 与智谱 / MiniMax 的额度), 应用这一侧只多认一个
     * [BalanceFile.KIND_QUOTA] 与一个厂商名, 取数一个字节都不管
     */
    private fun showBalance() {
        val snapshot = BalanceFile.snapshot(this)
        if (snapshot != null && System.currentTimeMillis() - snapshot.at <= BALANCE_FRESH_MS) {
            if (snapshot.ok) {
                showLocalInfo(balanceLine(snapshot))
            } else {
                // 已经有答案的失败就地给: 「不支持」是稳定事实, 不该每次都「正在取」再绕一圈
                showLocalNote(balanceFailure(snapshot))
            }
            return
        }
        showLocalInfo(getString(R.string.ball_balance_pending))
        balanceAskedAt = System.currentTimeMillis()
        BalanceFile.request(this)
    }

    /** 失败那一句话: 当前模型没有余额源说「不支持」, 其余把宿主那句原话递上去 */
    private fun balanceFailure(snapshot: BalanceFile.Snapshot): String =
        if (snapshot.unsupported) {
            getString(R.string.ball_balance_unsupported)
        } else {
            getString(R.string.ball_balance_failed, snapshot.reason.ifBlank { "unknown" })
        }

    /**
     * 宿主把余额那份文件刷新了: 就地把那一行改掉 / 把失败如实递上去
     *
     * **只在主人刚点过菜单那一段里上屏** ([BALANCE_ASK_WINDOW_MS]): 「看余额」是唯一能让这一行出现的
     * 入口 (2026-10-17 主人: 「余额查询只能用户主动在 ball 菜单主动查询」) —— 宿主那一侧现在也只在
     * 请求记号出现时才去查, 但"文件变了就写框"这条路照样要带闸, 否则那一行会撞在主人刚收到的回答后面
     * (2026-10-17 真机上量到的那一次就是: 宿主起来那几拍补刷正好压在第一次问答的尾巴上)
     */
    private fun readBalance() {
        val snapshot = BalanceFile.snapshot(this) ?: return
        val asked = balanceAskedAt > 0 && System.currentTimeMillis() - balanceAskedAt <= BALANCE_ASK_WINDOW_MS
        if (!asked) return
        if (snapshot.ok) {
            // 只有"框在屏上"才值得写: 主人关掉框之后余额到账, 那一行不该凭空把框弹回来
            if (boxOnScreen) runCatching { boxView?.replaceInfo(balanceLine(snapshot)) }
            return
        }
        // 失败: 走 ⚠ 那一条, 那是"系统在说哪里不对"的位置
        if (boxOnScreen) runCatching { boxView?.appendNote(balanceFailure(snapshot)) }
    }

    /**
     * 那一行文本: 谁家的钱 + 金额 (含赠金) 或剩余额度 + 数据多旧
     *
     * 名字那一截来自宿主 (只有它知道这一份是问哪一家要来的): 订阅制那几家给的是"还剩百分之多少",
     * 不是金额, 所以它单独一句话。旧宿主写的那一份没有名字 —— 那种照从前只写金额, 不拿一个空名字去
     * 凑一句「 余额」
     */
    private fun balanceLine(snapshot: BalanceFile.Snapshot): String {
        val name = snapshot.label.takeIf { it.isNotBlank() }
        val age = balanceAge(snapshot.at)
        if (snapshot.kind == BalanceFile.KIND_QUOTA) {
            val percent = snapshot.percent.coerceIn(0, 100)
            return if (name == null) {
                getString(R.string.ball_balance_quota_line, percent, age)
            } else {
                getString(R.string.ball_balance_quota_line_by, name, percent, age)
            }
        }
        val symbol = if (snapshot.currency == "USD") "$" else "¥"
        val bonus = snapshot.bonus.takeIf { it.isNotBlank() }
            ?.let { " (${getString(R.string.ball_balance_bonus, "$symbol$it")})" }
            .orEmpty()
        val amount = "$symbol${snapshot.value}$bonus"
        return if (name == null) {
            getString(R.string.ball_balance_line, amount, age)
        } else {
            getString(R.string.ball_balance_line_by, name, amount, age)
        }
    }

    /** "数据多旧": 一分钟内说"刚刚", 其余按分钟 (宿主那侧每轮结束才刷一次, 别指望它实时) */
    private fun balanceAge(at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
        return if (minutes < 1) {
            getString(R.string.ball_balance_just_now)
        } else {
            getString(R.string.ball_balance_minutes, minutes.toInt())
        }
    }

    /**
     * 一条**本地**的信息写进通道那块框 ([BoxView.appendInfo]): 形状照 `deliverNote` —— 框在屏上就写
     * 进去, 不在就张出来, **不抢焦点、不弹键盘**
     *
     * 它不走 [appendReply] 那条: 那一条会记"这条是谁说的" (三笔会话账, 双击回复框与「返回应用」都认),
     * 而余额跟哪一场对话都没关系
     */
    private fun showLocalInfo(text: String) {
        if (boxOnScreen) {
            boxView?.appendInfo(text)
            return
        }
        openChannel(focus = false)
        // 张框那一步是同步挂窗的, 所以这里再补一次 (挂失败时 boxView 为 null, 什么都不做)
        boxView?.appendInfo(text)
    }

    /**
     * 一条**本地**的 ⚠ 写进通道那块框: 形状与 [showLocalInfo] 一模一样 (框在就写, 不在就张, 不抢焦点
     * 不弹键盘), 只是画在提示那一档 —— "当前模型不支持查询余额"这一类回答走它 (那不是金额, 不该带 ¥)
     */
    private fun showLocalNote(text: String) {
        if (boxOnScreen) {
            boxView?.appendNote(text)
            return
        }
        openChannel(focus = false)
        boxView?.appendNote(text)
    }

    /**
     * 菜单里那行「释放视频模式」: 把相机占用表删掉 (批次 3 的需求 3)
     *
     * 它**不碰相机本身**: 释放之后下一场取景会自己重新落账, 而"把摄像头还给系统"这件事仍然只由切回手机
     * 模式那条路做 —— 表上那一笔账说的是"哪一场在用", 相机在后台本来就归本应用
     *
     * 读一次再删: 那一行只在有人占着时出现, 而回执要说清"放掉的是谁" (表卡住时人要知道是不是自己那一场)
     */
    private fun releaseCameraOwner() {
        val owner = CameraOwner.read(this)
        val released = CameraOwner.release(this)
        note(
            "release video mode from the menu: " + when {
                owner == null -> "nobody was holding the camera"
                released -> "released ${CameraOwner.short(owner.sessionId)}" +
                    " (held since ${CameraOwner.clock(owner.since)})"
                else -> "the table is still there (${CameraOwner.short(owner.sessionId)})"
            },
        )
    }

    /**
     * 菜单里那行「返回应用」: **与双击回复框同一个去向**
     *
     * **2026-10-11 起它跟双击回复框不再是同一个去向** (主人: "ball菜单栏里『回到应用』要改成走的是
     * 正在想的对话，没有则返回新对话。而双击回复框现在不变"): 这一行走 [OverlayState.runningSession]
     * —— 球上写着「正在想」的那一场, **没有在跑的轮就是一场新对话** (`openApp(null)` 给的是
     * `BallReturn.NEW_SESSION` 那个记号)。要看"这块框刚才是跟谁说话"就走双击回复框那条
     * ([OverlayState.viewSession])。
     *
     * 这里另外多做一件: **把框收起来**。主人 2026-10-07 的口径是"回应用 = 让出界面看会话", 而这块框
     * 正是挡在会话上面的那扇窗
     */
    private fun returnToApp() {
        val from = OverlayState.runningSession()
        note(
            "back to the app from the menu: " + when {
                from != null -> "the conversation that is thinking ($from)"
                else -> "nothing is thinking, so a new conversation"
            },
        )
        runCatching { closeChannel() }
        openApp(from)
    }

    /**
     * 一个模式的显示名: **查表, 不写二选一**
     *
     * 原来是 `if (mode == VIDEO) 视频模式 else 手机模式` —— 第三个模式 (识屏) 出现之后, 那种写法会把
     * 它报成「手机模式」, 而球与通知栏上那两行正是主人看"它现在在哪个模式"的地方 (2026-10-06)。现在
     * 模式只剩两个, 而这条查表的路照旧: 退役的名字由 [LwModes.active] 收成手机模式
     */
    private fun modeName(mode: String? = null): String = LwModes.label(this, mode)

    /* ── 输入通道 (那块自己画的文字框) ─────────────────────────────────────── */

    /**
     * 开输入通道: 挂上那块 650 px 的框, 并把焦点交给输入框
     *
     * 三件事与那块 WebView 输入条不同, 都写在 [BoxView] 的注释里; 这里只说摆位: 框挂在球里侧那
     * 一边 ([BoxSpot]), 球半隐着也照样摆 —— 框是"跟着球"的, 不跟着半隐那个偏移
     */
    private fun openChannel(focus: Boolean) {
        val manager = window ?: return
        closeMenu()
        closeStrip()
        val metrics = resources.displayMetrics
        val width = BallBox.WIDTH_PX
        // **判据是"我们手上还拿着那块窗的根", 不是"它这一帧挂上没有"** (2026-10-09 主人报的
        // "两次三击会出现两个输入框而且会重叠"): `isAttachedToWindow` 要等下一帧的
        // `performTraversals` 才为真, 而 `addView` 返回那一刻它还是假的 —— 两次三击落进这一段窗口里,
        // 第二次就会走"旧框已经不在窗口上"那一支: 旧框被摘下来, 又建一块新的。手上那一份引用随后被
        // 新框顶掉, 而**旧框已经从窗口管理器上摘了、屏幕上却还画着最后一帧**, 于是看着是两块叠在一起,
        // 而其中一块按不动 (它已经不在窗口树里了) —— 那就是"重叠后第一个窗口无法复原"
        //
        // 摘窗时我们会把 [boxRoot] 置空 ([closeChannel]), 所以"根还在"与"框真在窗口上"是同一件事,
        // 而且这一条是**同步**的: 同一个消息队列上再进来一次三击也只会走到下面那条复用分支
        var view = boxView
        if (view == null) {
            val box = BoxView(this, boxListener)
            // **新的一块框**: 上一块的回复与"这一场是谁"跟着它一起没了 (窗摘下来之后再开就是新实例),
            // 读数要跟着清 —— 留着旧的那几个数会让 [OverlayState.replyTarget] 与 [OverlayState.replySession]
            // 指着一块空框点名
            OverlayState.replies = 0
            OverlayState.replySession = null
            OverlayState.inputSession = null
            // 套一层收 ACTION_OUTSIDE 的壳 (见 ChannelRoot): 框外那一下双击要靠它收
            val root = ChannelRoot(this).apply { addView(box) }
            root.onOutside = { runCatching { noteBlankTap() } }
            val layout = boxLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                x = BoxSpot.x(currentBallX, ballSizePx(), width, metrics.widthPixels)
                y = BoxSpot.y(ballBaseY, ballSizePx(), 0, metrics.heightPixels, imeBottom)
            }
            try {
                manager.addView(root, layout)
            } catch (error: Throwable) {
                hint(getString(R.string.overlay_refused, error.message ?: error.toString()))
                return
            }
            view = box
            boxView = box
            boxRoot = root
            boxParams = layout
            OverlayState.channelAttached = true
            root.setOnApplyWindowInsetsListener { _, insets ->
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                if (ime != imeBottom) {
                    imeBottom = ime
                    runCatching { applyIme() }
                    // 键盘起来 / 落下都是"键盘那一档"的一次变化, **那一拍要立刻算** (见 [refresh]
                    // 里 keyboard 那一段): 只等 400 ms 那一拍的话, 收起键盘之后会多等一格才收边
                    runCatching { refresh(force = true) }
                }
                insets
            }
            // **挂上之后马上把焦点与键盘要到手** (2026-10-09): 原来这里只在下面那句 [focusInput] 里
            // 要一次, 而新窗那一刻还没有输入连接 —— 要等一拍 `performTraversals` 才真的把 IME 拉起来,
            // 观感就是"框出来了而键盘迟一拍"。挂窗之后再补一次, 键盘那一拍于是落在同一个帧序列里
            if (focus) view.claimInputAfterLayout()
        } else {
            // **框已经开着**: 这一下只是"再要一次这块框" (三击那条第 4 下以上 / 开着时又三击一遍),
            // 于是只摆一次位置、把焦点还给输入框 —— **不再建第二块**, 也不清掉里面已经有的回复
            note("the text channel was already open: bringing it back instead of opening a second one")
        }
        OverlayState.channel = true
        lastBlankTapAt = 0L
        // 张出来的这一刻起算"没人碰它"那一笔账 (到点自己收, 见 [refresh] 的 boxIdle 那一段)
        OverlayState.noteBoxActivity()
        noteActivity()
        layoutChannel()
        if (focus) {
            view.focusInput()
            // 键盘起来之后那块框要跟着让位 (inset 到了才动, 拿不到就什么都不做)
            handler.postDelayed({ runCatching { layoutChannel() } }, BallMinutes.IME_SETTLE_MS)
        }
        // 张开之前攒下的那句回答与那条提示: 现在补上 (回答在前, 提示在后 —— 那是它们发生的顺序)
        OverlayState.pendingReply?.let { waiting ->
            OverlayState.pendingReply = null
            val from = OverlayState.pendingReplySession
            OverlayState.pendingReplySession = null
            appendReply(waiting, from)
        }
        OverlayState.pendingNote?.let { waiting ->
            OverlayState.pendingNote = null
            appendNote(waiting)
        }
        refresh(force = true)
    }

    /**
     * 关掉通道: 从那块窗上摘下来, 实例留着 (再开一次不必重建)
     *
     * **幂等** (2026-10-09): 没有框在手上时也要把那一堆读数清干净再返回 —— 原来第一句是
     * `val root = boxRoot ?: return`, 而"窗摘下来之后实例还留着"那一条路会把读数 (channel /
     * attached / 高度 / 焦点 / 键盘) 停在"开着"上, 下一次判断就可能拿着一块不在窗口上的框当它在
     * (2026-10-06 报的"点空白关掉之后再也打不开键盘输入"就是那一支)。清理与摘窗分开写, 两条路都干净
     */
    private fun closeChannel() {
        boxRoot?.let { root -> runCatching { window?.removeView(root) } }
        boxView = null
        boxRoot = null
        boxParams = null
        // 键盘那一档跟着这一块框走: 它一离开窗口, "人正在打字"就不成立了 (见 [refresh])
        boxFocused = false
        imeBottom = 0
        OverlayState.channel = false
        OverlayState.channelAttached = false
        OverlayState.channelHeight = 0
        // 框不在屏上就没有"闲置多久"这回事: 那一笔账跟着窗一起清 (见 [refresh] 的 boxIdle 那一段)
        OverlayState.boxActiveAt = 0L
        OverlayState.boxIdleMs = 0
        // **框没了, 那两笔"哪一场"的账跟着一起落** (2026-10-09): 留着它们的话, 下一块框还没说过话
        // 就已经指着一个上一块框的会话
        OverlayState.replies = 0
        OverlayState.replySession = null
        OverlayState.inputSession = null
        lastBlankTapAt = 0L
        noteActivity()
        refresh(force = true)
    }

    /**
     * 框该待的地方: 跟着球, 并且避开键盘
     *
     * **球在动的时候它跟着动** (需求原话: "文本框随浮标移动"): 拖动那一帧一次 ([BallView.Listener]
     * 的 onDragTo), 吸附/半隐动画收尾那一下 ([moveX]), 键盘起来那一下 ([applyIme]), 以及框自己
     * 长高那一下 ([BoxView.Listener.onResize]) —— 那几处加起来就是"始终在球旁边"
     */
    private fun layoutChannel() {
        val root = boxRoot ?: return
        val layout = boxParams ?: return
        val metrics = resources.displayMetrics
        val ball = ballSizePx()
        // **宽也在这里重算** (2026-10-08): 窄屏那一档要收窄 ([BallBox.widthFor]), 而收窄之后 x 的钳制
        // 必须用同一个数 —— 两处各算一次的话, 框会一半压在球上或者一半留在屏外
        layout.width = BallBox.widthFor(metrics.widthPixels)
        layout.x = BoxSpot.x(currentBallX, ball, layout.width, metrics.widthPixels)
        layout.y = BoxSpot.y(
            ballParams?.y ?: ballBaseY,
            ball,
            root.measuredHeight,
            metrics.heightPixels,
            imeBottom,
        )
        runCatching { window?.updateViewLayout(root, layout) }
        OverlayState.channelAttached = root.isAttachedToWindow
        OverlayState.channelHeight = root.measuredHeight
        noteRects()
    }

    /** 通道那块框的回调: 发送 / 焦点 / 打字 / 有人碰到它 / 它自己长高了 */
    private val boxListener = object : BoxView.Listener {
        override fun onSend(text: String) {
            // **发出去了就立刻把框收起来** (主人 2026-10-06 的口径: 框发出问题后应立刻关): 这一句已经
            // 交出去了, 而框留在屏幕上只是挡着看回答 —— 回答到了它会自己弹回来 (见 [deliverReply]),
            // 所以这里可以放心收。**没送出去就把框留着**: 那一句还要在里面改 (写失败的 Toast 由
            // [ask] 说出来)
            if (ask(text)) runCatching { closeChannel() }
        }

        override fun onFocus(hasFocus: Boolean) {
            // 焦点这一笔账**不在这里逐次记活动**: 键盘那一档由 [refresh] 按"这一档开始 / 结束"去重写
            // (见那一处注释), 于是"点进框又把键盘收掉"不会留下一笔永远新鲜的计时。这里只留三个动作:
            // 记下焦点这一件事实、拿到焦点时把球叫回来, 以及给"框闲置多久"那一笔账续一次期 (拿到焦点
            // 就是"有人碰它")
            boxFocused = hasFocus
            if (hasFocus) {
                OverlayState.noteBoxActivity()
                runCatching { unpeek() }
            }
            refresh(force = true)
        }

        override fun onTyping() {
            // **打进一个字就是一次"有人用它"** (主人 2026-10-06: 键盘输入不属于空闲): 只记那一笔账,
            // 球该不该收边由 [waitNow] 说了算 —— 键盘那一档在那里, 所以这里不必自己去判
            //
            // 它同时是"框闲置"那一笔账的续期: 正在打字当然不算"没人碰它"
            OverlayState.noteBoxActivity()
            noteActivity()
        }

        /**
         * 滚了一下 (回复那一块 / 输入框自己)
         *
         * 读一条长回答本身就是用它, 而滚动不碰框的触摸 (事件在子 view 里被吃掉), 所以它得单独记一笔
         * —— 少了它, 读到一半就会被 20 s 那一笔账收掉
         */
        override fun onScroll() {
            OverlayState.noteBoxActivity()
            noteActivity()
        }

        override fun onTouch() {
            // 碰到框: 那本"框外双击"的账清掉 (需求: 关它那两下要落在**框外**)
            lastBlankTapAt = 0L
            OverlayState.noteBoxActivity()
            noteActivity()
        }

        override fun onResize() {
            // 高度变了就把位置重算一次 (摆位是拿 measuredHeight 算的, 所以这一步不能省)
            handler.post { runCatching { layoutChannel() } }
        }

        /**
         * **双击回复框 = 回应用** (主人 2026-10-07: "双击回复框相当于回应用, ball 菜单的「回应用」
         * 选项可以删掉")
         *
         * 与菜单里那行「返回应用」是同一个去向 ([returnToApp] → [openApp]), 只有目标更准: 这条回复是
         * 哪一场发来的就回到哪一场 ([OverlayState.replySession]); 还没记下是哪一场时落回浮标那一场
         *
         * **它同时把框收起来** (2026-10-07 追加): 主人报的"在 dsh 应用里双击回复框没有任何反馈"正是
         * 这一档 —— 界面本来就在那一场时, 切会话那一步什么都不用做 (JS 回 `already`), 屏幕上于是没有
         * 一点动静。收掉这块框是这一下无论如何都看得见的那一半, 而需要切场时它让开的正是会话本身
         *
         * 双击也算"有人用它": 那一笔闲置账跟着续期 —— 否则点一下回应用, 回来时框已经自己收掉了
         */
        override fun onReplyDoubleTap() {
            OverlayState.noteBoxActivity()
            noteActivity()
            // 先把那一场抄下来: 收框之后 [OverlayState.replyTarget] 就不成立了。
            // **这一条 2026-10-11 起只管双击**: 取的是"这块框跟哪一场打过交道"
            // ([OverlayState.viewSession] 那条, 优先 inputSession) —— 球菜单那行「返回应用」与常驻
            // 通知走的是另一套 (正在想的那一场, 没有就是新对话, 见 [returnToApp] 与 [runningSession])
            val from = OverlayState.viewSession()
            note("a double tap on the reply: back to ${from ?: "a new conversation"}, closing the channel")
            runCatching { closeChannel() }
            openApp(from)
        }

        /**
         * 右上角那个 `×`: **把框收掉** (主人 2026-10-07: "在图标文本框左/右上角加个关闭按钮,
         * 点击可关闭")
         *
         * 与"框外双击"([noteBlankTap]) 是同一条收尾, 两条路都留着: 框外双击在别人的全屏应用里不容易
         * 找到空白处, 而这个按钮一直在框自己的角上。它只关框, 不动语音链, 也不动球
         */
        override fun onClose() {
            OverlayState.noteBoxActivity()
            noteActivity()
            note("the channel was closed from its own button")
            runCatching { closeChannel() }
        }
    }

    /**
     * 送一句话出去: **键盘那一路与球上说话同一个入口**
     *
     * 所以"浮标语音输入"与"键盘输入"不是两套实现, 只有"字从哪来"不同 —— 一句写进
     * [VoiceInbox] (宿主那侧边读边送进会话), 一句落到通道那块框里 ([appendReply]), 两件事都在
     * 这一处发生
     *
     * 回"这一句排上了没有": 排上了调用方立刻把框收起来, 没排上就把框留着 (见 [BoxView] 那个回调)
     *
     * 写失败必须说出来: "发出去了"与"没发出去"对主人是两回事 (那条纪律与 `lw_*` 那批一样)
     *
     * **host 没起来也说一句**: 队列是落盘的, 这一句不会丢 (宿主起来会从游标那里接着投), 但屏幕上
     * 本来没有任何迹象 —— 而"我说了话, 没有回音"与"这句还排着队"对主人是完全不同的两件事
     * (2026-10-06 那一批真机上就是这样: 12 句话卡在队列里, 界面上一个字都没有)
     */
    private fun ask(text: String): Boolean {
        noteActivity()
        if (DshHost.status !is HostStatus.Running) hint(getString(R.string.ball_ask_no_host))
        // **回复框在屏上就点名投它那一场** (2026-10-06 那条例外): 宿主那侧把 `to` 排在时间那笔账
        // 前面; 没有回复框时它是 null, 行为与从前一个字不差
        val to = OverlayState.replyTarget()
        val seq = VoiceInbox.append(this, text, VoiceInbox.SOURCE_KEYBOARD, to = to)
        if (seq == null) {
            // 这一条**要说**: "发出去了"与"没发出去"对主人是两回事, 而屏幕上本来没有任何别的迹象
            hint(getString(R.string.ball_channel_failed))
            return false
        }
        // **这一句话投给了谁, 这块框就记下谁** (2026-10-09 主人: "谁发送了输入框, 就回到那一场"):
        // 「回应用」那一跳按这一条走 (见 [OverlayState.viewSession]), 所以"我发给了哪一场"与
        // "框把我带回哪一场"是同一件事。`to` 为 null 时不写 —— 那是"按浮标那本 10 分钟的账投的",
        // 目标由宿主那侧定, 我们这里不知道, 留着上一次记的反而更准
        to?.trim()?.takeIf { it.isNotEmpty() }?.let {
            OverlayState.inputSession = it
            // **框收掉之后这一笔要留下** (见 [OverlayState.boxSession]): 「返回应用」按的时候框已经关了
            OverlayState.boxSession = it
            note("the box will return to $it, the conversation this line went to")
        }
        note("channel line queued as #$seq: $text")
        // **桌面对话那本账上的 `user` 那一笔** (2.7.8 批次 6): 从这块框里发出去的话, 桌面上那一格也要
        // 看得见 —— 于是"点组件说一句"与"点浮标说一句"在那一格里长得一样。写失败只记一行: 组件那一格
        // 少一条气泡不该把"这句话已经排上了"这件事实搅乱
        if (runCatching { DesktopChatFile.append(this, DesktopChatFile.WHO_USER, text) }.getOrDefault(false)) {
            runCatching { WidgetHost.refreshChat(this) }
        }
        return true
    }

    /**
     * 回复到了: 画进通道那块框里, **并且把它张出来**
     *
     * 主人 2026-10-06 的口径: 框发出问题后立刻关 ([BoxView.Listener.onSend]), 而回答到了**自动打开**
     * —— 所以这里不只是"画", 还得把那块框挂回窗口上。挂的时候是 `focus = false`: **不抢焦点、不弹
     * 键盘**, 主人正在用的那个应用照旧能用; 想接着打字就点一下框
     *
     * 通道没开着时那一句**不丢**: 记在 [OverlayState.pendingReply] 里 —— 而 [openChannel] 收尾那一步
     * 正好会把它补上, 所以这里两条路各自只画一次
     */
    private fun deliverReply(text: String, session: String?) {
        val line = text.trim()
        if (line.isEmpty()) return
        val from = session?.trim()?.takeIf { it.isNotEmpty() }
        OverlayState.pendingReply = line
        OverlayState.pendingReplySession = from
        if (boxOnScreen) appendReply(line, from) else openChannel(focus = false)
    }

    /**
     * 宿主推来的一条**提示** (不是回答): 比如"这句话没能送进会话"
     *
     * 与 [deliverReply] 同一条路 (自动张框、不抢焦点), 只是画成一条 `⚠` 提示 —— 投递失败那件事实在
     * 屏幕上本来一个字都没有, 而主人看到的只是"我说了话, 没有回音"
     */
    private fun deliverNote(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        OverlayState.pendingNote = line
        if (boxOnScreen) appendNote(line) else openChannel(focus = false)
    }

    /** 真画进框里: 通道开着才画 (没开就只留着那句话) */
    private fun appendReply(text: String, session: String?) {
        val view = boxView
        if (view == null || !OverlayState.channel) return
        view.appendReply(text)
        OverlayState.pendingReply = null
        OverlayState.pendingReplySession = null
        // **这一条是谁说的**: 回复一条条落进来, 后一条顶掉前一条 (见 [OverlayState.replySession])。
        // 它只管"框里显示的是谁的话"与"下一刻该把话投给谁"那条兜底, **不动开框目标**
        // ([OverlayState.inputSession], 那是"我发给了谁"那笔账, 见 [ask])。没带 `session` 的推送
        // (老宿主、别的来源) 不动它 —— 抹掉一个已经点好的名比留着更糟
        session?.trim()?.takeIf { it.isNotEmpty() }?.let {
            OverlayState.replySession = it
            // 同一件事的另一半: 这一条回答是哪一场推来的, 框关掉之后仍然算数 (见 [OverlayState.boxSession])
            OverlayState.boxSession = it
        }
        OverlayState.replies = view.replyCount()
        // 新的一句回答落进框里 = 有人该看它一眼, 那一笔"没人碰"的账从这里重新起算
        OverlayState.noteBoxActivity()
        noteActivity()
        layoutChannel()
    }

    /** 提示那一行 (与回答同一块只读区, 前面带一个 ⚠) */
    private fun appendNote(text: String) {
        val view = boxView
        if (view == null || !OverlayState.channel) return
        view.appendNote(text)
        OverlayState.pendingNote = null
        OverlayState.replies = view.replyCount()
        OverlayState.noteBoxActivity()
        noteActivity()
        layoutChannel()
    }

    /**
     * 点框外那一下: **窗口之内的第二下才关, 超时的那一下重新起算** (主人 2026-10-06 定的口径)
     *
     * 第一下什么都不做 (也不把键盘收掉 —— 手滑一下不该把正在打的字弄没), 但它**只在
     * [BallMinutes.BOX_DOUBLE_TAP_MS] 之内有效**: 过了窗口再点的那一下不是"第二下", 而是新的第一下
     * (原来那本账是纯计数器, 隔多久都累计, 于是"点一下、过一会儿再点一下"也会关掉)
     *
     * 判据是纯函数 [BallTaps.blankDouble] —— 没有设备也能量 (见 BallTest), 而这里只负责按它的答案
     * 动手与记账
     */
    private fun noteBlankTap(): Boolean {
        val now = System.currentTimeMillis()
        val doubled = BallTaps.blankDouble(now, lastBlankTapAt)
        // 先把窗口挪到这一下: 真双击时它随后被清零, 超时那一支则从这里重新起算
        lastBlankTapAt = now
        if (!doubled) {
            note("a tap outside the channel: waiting ${BallMinutes.BOX_DOUBLE_TAP_MS}ms for a second one")
            return false
        }
        note("a double tap outside the channel: closing it")
        lastBlankTapAt = 0L
        closeChannel()
        return true
    }

    /* ── 状态 ─────────────────────────────────────────────────────────────── */

    /**
     * 400 ms 一次: 球上那几个字、输入条标题与通知栏都照它改
     *
     * **那几个字有三个来源**: "正在念"与"正在听"在应用这一侧 (`VoiceState`); "正在想"与"失败"
     * 都出自宿主写的那份 `ball-phase.json` (见 [readBallPhase]) —— 而宿主没在跑的时候那条 phase
     * 一律不采纳, 否则宿主崩了球会永远停在"正在想"
     *
     * 「失败」的寿命**不由时间决定**: 文件里那一条被下一轮改写、或者主人点一下球之前, 它一直亮着
     * ([OverlayState.failedAckAt]); 它的优先级与"让位给语音那两档"写在 [BallStatus.wordFor] 上面
     */
    private fun refresh(force: Boolean = false) {
        val metrics = resources.displayMetrics
        // **换了屏才重摆三块窗** (转屏那件事见 [relayoutAll]): 这一拍是 400 ms 一次的热路径, 而
        // `force` 那一支每个回答、每次焦点变化都会走到 —— 那里只该刷新读数, 不该动窗口
        if (metrics.widthPixels != lastWidth || metrics.heightPixels != lastHeight) relayoutAll(force = true)
        else if (force) noteRects()
        // **念那一路有两个来源** (2026-10-09 真机: 在线引擎念的时候球上写着「正在听」):
        // `VoiceState.speaking` 是半双工那道闸的标记, 而三条引擎各自的 `speaking` 是
        // [LwSpeak.speakingNow] —— 只看前者时, 在线那两条 (Edge / API) 念的那几秒球会说成
        // "正在听"。点球那一下 ([tapAction]) 早就是两个一起看的, 这里对齐它
        // **"正在说"也认那条尾巴** (2026-10-10): 引擎报"说完"之后喇叭往往还在响, 那一段球上仍该写
        // 「正在说」(那一下点球是"别念了"), 也要挡住"她自己的回答被录回去" (见 [LwSpeak.talking])
        val speaking = LwSpeak.talking(this)
        // 「正在想」: 宿主说有一轮在跑 (那份文件是真相, 见 [BallPhaseFile]); 宿主没在跑时一律不采纳,
        // 否则宿主崩了球会永远停在"正在想"
        val thinking = OverlayState.phase == OverlayState.PHASE_THINKING && DshHost.status is HostStatus.Running
        // "正在听"说的就是这一句话的窗口开着 ([VoiceState.capturing]): 唤醒词一直守着, 而守着这件事
        // 不该在球上写成"正在听" —— 那是常态, 一直挂着只会让人以为麦克风在被吃
        val listening = VoiceState.capturing
        // 常驻语音那一档的读数 (2026-10-10): 记号开着而麦克风也开着时, 球上那一下「正在听」不只是收麦,
        // 还把视频模式一起退出 (见 [BallAct.LEAVE_VIDEO]) —— 它显示上与「正在听」一模一样, 所以这个
        // 读数就是"那一档到底在不在"唯一的对账处
        val residentVoice = residentVoiceOn()
        OverlayState.residentVoice = residentVoice
        // 三笔边沿账 (想/听各自"什么时候变成真的"、念的开始与结束、"起了一轮新的") 都在这一处算,
        // 而它们同时是那两笔自动收口的触发点 (见 [noteEdges])
        noteEdges(System.currentTimeMillis(), speaking, thinking, listening)
        val word = BallStatus.wordFor(
            speaking = speaking,
            thinking = thinking,
            listening = listening,
            // 「失败」: 上一轮是 error / blocked / max-tokens 收的, 而且主人还没点过球 (那一笔账在
            // [OverlayState.failedNow] 里 —— 它比时间窗口诚实, 时间窗口一到就自己落字反而看不见)
            failed = OverlayState.failedNow(),
            // 想与听同时成立时谁上就看这两个数 (见 [BallStatus.wordFor]): 晚的那个是"刚发生的事"
            thinkingAt = thinkingAt,
            listeningAt = listeningAt,
            // 视频模式那一档顶格 (主人 2026-10-10): 记号在就一直写「正在听」, 只让正在说盖一下。
            // **收紧过两次**(同一天主人那句"宁可不写, 也不骗人"): ① 省电模式里不算 (那时麦克风整个
            // 关着); ② **服务真的在听才算** —— 唤醒词服务被杀掉之后记号还在, 那时球上不该写着
            // 「正在听」而麦克风一动不动 (2026-10-10 当场量到的那一次)
            resident = residentVoice && WakeWordState.listening && !WakeWordState.powerSave,
        )
        val changed = word != lastWord
        // **环色也得算进"变了没有"**: 两个会话轮流在想时那个字一直是「正在想」, 而颜色要跟着换
        val ring = ringOf(word)
        // **外观也算** (协议 10.1): 设置页里换了一套外观, 这一拍就该重画, 不必等状态变化
        val themeNow = runCatching { BallTheme.currentId(this) }.getOrDefault("")
        val rethemed = themeNow != lastThemeId
        lastThemeId = themeNow
        if (force || changed || ring != lastRing || rethemed) {
            // **半隐也要跟着进**: BallPet 那一类外观给"贴边半藏"单独画了一张 (`peeked`), 半隐/回弹
            // 那一拍就得多喂这一个入参 (内置画法没有这一档, 照旧按 idle 画)
            ballView?.show(word, ring = ring, peeked = peeked)
            OverlayState.word = word?.label(this)
            stripTitle?.text = title(word)
            lastRing = ring
        }
        // 球外那几圈涟漪 (说往外扩 / 听往回收): 那两档才挂窗, 别的档位把窗摘掉 (见 [applyPulse])
        applyPulse(word, ring)
        // 有状态就滑出来说话, 闲着就收边 (参考那套的 `floating_ball_idle_to_edge`)
        //
        // **语音在跑时不收边, 但计时照走** (主人 2026-10-06 报的: "开了语音输入之后就没法空闲隐藏
        // 了"): 那一支原来只 unpeek 不记事, 于是"正在听"一出现 `lastActiveAt` 就冻在那儿 —— 语音链
        // 自己 10 s 闲置关门之后, 那一笔账一算就是"闲置很久", 可这一拍的状态词又把它挡在门槛外……
        // 绕回来自始至终没收过边。现在只有"状态**换了** / **落了**"这两下各算一次活动
        //
        // **这两笔账判的是上一拍那个字** ([BallIdle.reset] 收下了那个判据, 与这里同一个 reason 字符串):
        // 原来 else 那一支写的是 `prevWord != null`, 而 `prevWord` 按"字换了"赋值 —— 它一旦非空就再也
        // 不落, 于是每一拍都把 `lastActiveAt` 推到当下, 球永远等不到那 5 s。2026-10-06 主人报的
        // "ball 半隐藏功能又失效了"就是它 (真机 13.7 h 里 `peeked=true` 只出现过 14 次, 见 docs)
        BallIdle.reset(word, VoiceState.capturing, lastWord)?.let { resetIdleWait(it) }
        if (word != null || VoiceState.capturing) {
            phase = BallPhase.VOICE
            unpeek()
        } else {
            // 什么都不在跑 = 它就是"全露着但没在听"那一档。这一行同时管两件事: 语音链收掉之后
            // (10 s 闲置自动关 / 手动关) 回到那一档, 以及从有状态回到闲置时把计时重新起算
            //
            // **少了它就会有一个假状态**: 语音关掉之后 `phase` 还停在 VOICE, 于是下一次点球会被
            // 当成"收回来" —— 那一下看着像"点了没反应"
            phase = BallPhase.SUMMONED
        }
        // **每一拍都落**: 上面两处判据要的是"上一拍那个字", 而不是"上一次换字时那个字" —— 这一个字
        // 的区别就是上面那条病根
        lastWord = word
        // 键盘那一档: 输入框有焦点, 或者键盘真的在屏上 —— 有一件成立就算"人正在用它"。这里的
        // **开始与结束都各记一次账** (而不是每拍都记), 于是"打完字把键盘收掉"之后 5 s 才收边
        //
        // 2026-10-06 主人报的第一条 ("键盘输入时, 浮标会空闲半隐藏, 键盘输入时不属于空闲") 就出在
        // 这一档原来根本不存在: 打字只改文本, 一个活动回调都不走
        val keyboard = boxOnScreen && (boxFocused || imeBottom > 0)
        if (keyboard != overlayKeyboard) {
            overlayKeyboard = keyboard
            resetIdleWait(if (keyboard) "keyboard" else "keyboard off")
        }
        // **收边那六条闸只有一处** (见 [BallRest.wait]): 语音在跑、人在打字、菜单开着、输入通道开着、
        // 手指按着, 以及"闲够 5 s 了"——都在那一个纯函数里, 这里只问它一句
        waitNow()
        // 位置与意图对一次账: 被掐掉的动画、撞在一起的停靠与展边都在这上面收口 (见 [settleBall])
        settleBall()
        // 输入通道那块框的闲置账: **没人碰它到点就自己收** (主人 2026-10-06 选的处置)
        //
        // 为什么要有它: 回答到了框会自己张出来 ([deliverReply]), 而框开着的那段时间球不收边
        // ([BallRest.wait] 的 `channel` 那条闸 —— 框是跟着球摆的, 球走了框就跟着跑)。于是"回复到了"
        // 与"球半隐"互相顶住: 框不收走, 球就永远露着。框收掉之后 [closeChannel] 会记一次活动, 球那
        // 一笔账从那一刻重新起算, 5 s 后收边
        //
        // **不在这一拍里直接收**: [closeChannel] 自己会再叫一次 `refresh(force = true)`, 那就是递归。
        // 丢一拍去关它, 与其它"改窗"的地方一个口径
        // **`boxActiveAt` 为 0 时不算闲置** (2026-10-08 修的小毛病, 那一条的读数长这样:
        // "框没人碰了 1791438154137ms" = `now - 0`): [closeChannel] 把那一笔账清零, 而 `removeView`
        // 是下一拍才真的离开窗口树, 于是"刚摘下来的那一拍" boxOnScreen 还是真 —— 少了这个判断,
        // 它会立刻再判一次"闲置太久了"并关一次 (多一条假日志, 还多一次 removeView)
        val boxIdle = if (boxOnScreen && OverlayState.boxActiveAt != 0L) {
            System.currentTimeMillis() - OverlayState.boxActiveAt
        } else {
            0L
        }
        OverlayState.boxIdleMs = boxIdle
        if (boxOnScreen && boxIdle > BallMinutes.BOX_IDLE_MS) {
            note("the text channel was untouched for ${boxIdle}ms: closing it so the ball can tuck away")
            handler.post { runCatching { closeChannel() } }
        }
        // 收边那几道闸的读数: 这台模拟器把应用自己的 logcat 滤掉了 (2026-10-05), 所以排查"为什么
        // 不收边"只能靠状态对账 —— 这几个数把 [BallRest.wait] 吃进去的条件原样报出来
        OverlayState.ballPhase = phase.name
        OverlayState.dragging = ballDragging
        OverlayState.keyboard = overlayKeyboard
        OverlayState.busy = menuView != null || boxView != null
        OverlayState.idleMs = System.currentTimeMillis() - lastActiveAt
        val notice = notice(word)
        if (force || notice != lastNotice) {
            lastNotice = notice
            announce(notice)
        }
    }

    /**
     * 这一拍的三笔边沿账, 以及挂在它们上面的两条自动收口 (2026-10-09 主人定的口径)
     *
     * 1. **想与听各自"什么时候变成真的"** ([thinkingAt] / [listeningAt]): 球上写哪一个由
     *    [BallStatus.wordFor] 比这两个数决定 —— 点一下开麦就写「正在听」、开麦之后新一轮又起来就
     *    写回「正在想」, 两件事都是这里的一行
     * 2. **念开始了 / 念完了**: 开始那一下把"这一句念的是哪一场的回答"记进 [readingSession]
     *    (宿主推回复那一条就带着会话 id, 见 [OverlayState.replySession]), 念完把那笔账清掉
     * 3. **"起了一轮新的"**: 那份文件里最新的开始时刻比上一次读到的更新 —— 两笔自动收口都挂在
     *    这一个边沿上 ([onNewTurn])。**第一次读只当基线**: 服务起来时"已经有一轮在跑"不是"刚刚起了
     *    一轮", 拿它当边沿会平白把语音窗口收掉一次
     */
    private fun noteEdges(now: Long, speaking: Boolean, thinking: Boolean, listening: Boolean) {
        if (thinking) {
            if (thinkingAt == 0L) thinkingAt = now
        } else {
            thinkingAt = 0L
        }
        if (listening) {
            if (listeningAt == 0L) listeningAt = now
        } else {
            listeningAt = 0L
        }
        if (speaking && !lastSpeaking) {
            readingSession = OverlayState.replySession?.takeIf { it.isNotBlank() }
            readingCut = false
            OverlayState.readingSession = readingSession.orEmpty()
        } else if (!speaking) {
            readingSession = null
            readingCut = false
            OverlayState.readingSession = ""
        }
        lastSpeaking = speaking
        val newest = turns.maxOfOrNull { it.startedAt } ?: 0L
        val fresh = turnsBaseline && newest > lastTurnAt
        lastTurnAt = newest
        turnsBaseline = true
        if (fresh) onNewTurn()
    }

    /**
     * 后台起了一轮新的: 两条"该收的收掉"
     *
     * - **同一场起了新轮就掐播报** (主人 2026-10-09: "只掐「同一场」的新轮"): 正在念的那一句是这个
     *   会话上一轮的回答, 而它已经又跑起来了 —— 那一句念完也没人要听。别的会话跑起来**不碰**这一句
     *   ([readingSession] 对不上就不动), 而那一笔账是空的时 (手动 `lw_speak` 念的一句、或者回复没带
     *   会话 id) 一样不掐: 宁可让它念完, 也不要凭猜把别人的回答掐掉
     * - **新一轮出现就把「正在听」收回来** (主人 2026-10-09: "把「正在听」一并收掉"): 麦克风那条链与
     *   它上面那个输入条一起收, 走的是三击那条同一个入口 ([LwWakeWord.hushWindow])。代价写在
     *   AGENTS 里: 人说到一半时被新一轮收掉, 下半句就没了
     *
     * 两条都**只动播报与麦克风, 一个轮次都不取消**; 球上那个字仍然只按 [BallStatus.wordFor] 算
     */
    private fun onNewTurn() {
        val reading = readingSession
        if (reading != null && !readingCut && turns.any { it.id == reading }) {
            readingCut = true
            note("$reading started another turn: cutting the reply it was reading")
            stopSpeaking()
            OverlayState.speechCuts += 1
        }
        // **视频模式里不收这一下** (2026-10-10 主人: "把视频状态下正在听这三个字…不能被其它状态打断" +
        // "在说时要关闭麦克风…说完后/打断后重新拉起听"): 常驻那一档要的就是"接着说话不用再喊唤醒词",
        // 而这一收会让"正在想"那几秒的麦也关掉。收麦这件事从此只由"说"的边沿决定
        // (见 `WakeWordService` 那条观察线程): 念的时候关, 念完/掐断之后拉回来
        if (VoiceState.capturing && !residentVoiceOn()) {
            note("a new turn started: taking the voice window back")
            runCatching { LwWakeWord.hushWindow(this) }
        }
    }

    /**
     * 球外那几圈涟漪: 说 (往外扩) 与听 (往回收) 两档才挂那块窗, 别的档位一个字节都不留
     *
     * 涟漪的颜色跟**当前那个字的环色**走 (说=紫 / 听=绿): 它只是那个字的一种说法, 不是另一种状态
     */
    private fun applyPulse(word: BallWord?, ring: Int?) {
        OverlayState.pulse = BallPulse.name(word)
        if (word != BallWord.SPEAKING && word != BallWord.LISTENING) {
            if (rippleView != null) detachRipple()
            return
        }
        val color = ring ?: word.ring()
        val view = rippleView ?: attachRipple() ?: return
        view.pulse(word, color)
        placeRipple()
    }

    /**
     * 挂上涟漪那块窗: 96 dp 见方, 圆心与球心重合
     *
     * **`FLAG_NOT_TOUCHABLE` 是它唯一的硬条件**: 涟漪比球大 (96 dp 对 48 dp), 可触摸的话球周围会多出
     * 一圈吃手指的死区 —— 球"只吃自己那 48 dp"那条性质就是这么保住的 ([WindowManager] 会跳过不可
     * 触摸的窗, 所以它在球窗上面也不会抢走任何一下)
     *
     * 挂不上就算了 (返回 null): 涟漪是"在动"的旁注, 少了它球上那三个字照旧
     */
    private fun attachRipple(): RippleView? {
        val manager = window ?: return null
        val ball = ballParams ?: return null
        val span = dp(BallPulse.SPAN_DP)
        val ballSize = ballSizePx()
        val view = RippleView(this)
        val layout = WindowManager.LayoutParams(
            span,
            span,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 球窗画在它那 48 dp 的正中, 而球体又画在球窗正中 —— 于是"圆心"就是球窗的中心
            x = ball.x + (ballSize - span) / 2
            y = ball.y + (ballSize - span) / 2
            // 标题显式写一个: 认球窗那几份脚本按 `dumpsys window windows` 走, 多出来的这块要认得出来
            title = BallPulse.TITLE
        }
        return try {
            manager.addView(view, layout)
            rippleView = view
            rippleParams = layout
            view
        } catch (error: Throwable) {
            note("the ripple window was refused: ${error.message ?: error}")
            null
        }
    }

    /** 涟漪窗的圆心跟着球心: 拖球、半隐那两条滑动、转屏都从这里过一遍 */
    private fun placeRipple() {
        val manager = window ?: return
        val view = rippleView ?: return
        val ball = ballParams ?: return
        val layout = rippleParams ?: return
        val ballSize = ballSizePx()
        val span = dp(BallPulse.SPAN_DP)
        val wantX = ball.x + (ballSize - span) / 2
        val wantY = ball.y + (ballSize - span) / 2
        if (layout.x == wantX && layout.y == wantY) return
        layout.x = wantX
        layout.y = wantY
        runCatching { manager.updateViewLayout(view, layout) }
    }

    /** 摘掉涟漪那块窗: 那一档过去之后屏上不许留一块看不见的常驻窗 */
    private fun detachRipple() {
        OverlayState.pulse = ""
        val view = rippleView ?: return
        rippleView = null
        rippleParams = null
        runCatching { window?.removeView(view) }
    }

    /** 输入条那条标题: 「Luwi · 正在听 · 视频模式」(手机模式是缺省那一档, 不占一个字) */
    private fun title(word: BallWord?): String {
        val parts = mutableListOf(getString(R.string.app_name))
        word?.let { parts += it.label(this) }
        // 三个模式,**只有手机模式不写**: 它是缺省那一档, 而另外两个都是"现在不一样了", 要看得见
        val mode = LwModes.active(this)
        if (mode != LwModes.PHONE) parts += modeName(mode)
        return parts.joinToString(" · ")
    }

    /**
     * 通知栏那一行: 球在不在、正在做什么
     *
     * **不报模式, 也不报"正在听"** (2026-10-06 主人点的两条): 模式是设置页里选的东西, 不占通知的
     * 位置; 而"正在听"与唤醒词那条通知说的是同一件事, 球上也有那三个字, 这里不复述
     */
    private fun notice(word: BallWord?): String = listOfNotNull(
        getString(R.string.overlay_notification),
        word?.takeIf { it != BallWord.LISTENING }?.label(this),
    ).joinToString(" · ")

    private fun replace() {
        val metrics = resources.displayMetrics
        val ball = ballSizePx()
        val edge = BallSpot.read(this)?.first ?: BallGeometry.edgeFor(ballRestX, metrics.widthPixels, ball)
        ballRestX = BallGeometry.xForEdge(edge, metrics.widthPixels, ball)
        ballBaseY = BallGeometry.clampY(ballBaseY, metrics.heightPixels, ball)
        applyBallPosition()
    }

    /**
     * 键盘那一层
     *
     * inset 只能是**那块可获焦的输入条**给的 (球自己不收输入法, 它的窗上报 0): 键盘起来时输入条
     * 整体抬到键盘上沿, 球抬到不会被键盘压住的地方; 拿不到 inset 时两边都原样不动 —— 猜一个高度
     * 只会让球跳到半空中
     */
    private fun applyIme() {
        val metrics = resources.displayMetrics
        stripParams?.let { layout ->
            val lifted = if (imeBottom > 0 &&
                layout.y + layout.height > metrics.heightPixels - imeBottom
            ) {
                (metrics.heightPixels - imeBottom - layout.height).coerceAtLeast(0)
            } else {
                stripBaseY
            }
            if (layout.y != lifted) {
                layout.y = lifted
                runCatching { window?.updateViewLayout(stripView, layout) }
            }
        }
        // 球靠同一条 inset 避开键盘 (它自己那块窗上报 0), 钳制写在 applyBallPosition 里
        applyBallPosition()
        // 通道那块框也吃同一条: 键盘起来时它在键盘上沿之上, 球在它下面照样看得见
        layoutChannel()
    }

    /** 说一句话给主人听 (Toast + 记一笔): 它**不是**失败, 所以不写 [OverlayState.lastError] */
    private fun hint(text: String) {
        OverlayState.lastHint = text
        Log.i(TAG, text)
        runCatching { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    /**
     * 只记一笔, **不弹任何东西** —— 关于球的所有操作都走这一条 (主人 2026-10-05: "关于球的所有操作
     * 都不要有提示")
     *
     * 它与 [hint] 的差别只在最后那一步 (Toast)。留下的那一笔照样进 [OverlayState.lastHint] 与日志,
     * 所以 `lw_overlay op=state` 的 `said` 与 `adb logcat -s LwOverlay` 都还答得出"刚才那一下做了
     * 什么" —— **"不出声"不等于"查不到"**, 这条链是排除故障时唯一看得见的东西
     *
     * [hint] 只留给"不成功"和"缺东西"那几种情形 (缺悬浮窗权限 / 缺 GUI 地址 / 收件箱写不进去):
     * 那些不说的话, 点一下就是"什么都没发生"
     */
    private fun note(text: String) {
        OverlayState.lastHint = text
        Log.i(TAG, text)
    }

    private fun fail(reason: String) {
        OverlayState.lastError = reason
        OverlayState.showing = false
        Log.w(TAG, reason)
        stopSelf()
    }

    /* ── 输入条那一块 (面板 A) ─────────────────────────────────────────────── */

    /** 拖动的那一条: 标题栏按住就能挪, 位置是相对屏幕左上角 */
    private fun dragHandle(bar: View) {
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var fromX = 0
            private var fromY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val layout = stripParams ?: return false
                // 窗不一定还在: 收起与拖动可以撞在一起, 所以这里也判一次空
                val target = stripView ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        fromX = layout.x
                        fromY = layout.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        layout.x = fromX + (event.rawX - downX).toInt()
                        layout.y = fromY + (event.rawY - downY).toInt()
                        runCatching { window?.updateViewLayout(target, layout) }
                        // 键盘没起来时这一处就是"主人放的那个 y", 键盘起来时它留给落回去用
                        if (imeBottom == 0) stripBaseY = layout.y
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (imeBottom == 0) stripBaseY = layout.y
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 回应用去 —— 并且**把会话界面带到那一场对话上** (主人 2026-10-06: "回应用时回到最近的浮标对话")
     *
     * 两件事分开做, 而且**两件都要做** (2026-10-07 真机那条"在 dsh 里双击回复框没有任何反馈"就落在
     * 这里):
     *
     * 1. **把请求直接写进同进程的那一份状态** ([BallReturn], 由界面那一侧落地): 界面活着时这一步就
     *    够, 不靠 intent 送不送得到 —— `startActivity` 的 `onNewIntent` 有没有被叫到不受我们控制
     * 2. **把任务提到前台** ([startActivity]): 从后台启动 Activity 在 Android 10 起默认禁止, 而持有
     *    SYSTEM_ALERT_WINDOW 正是官方豁免之一 —— 这台手机这条权限已经给了。它起不来的那几种情形
     *    必须说出来, 别让主人以为"点了没反应"
     *
     * 那一场对话的 id 由调用方给 (回复框记着的那一场, 见 [OverlayState.viewSession] 与它的两个入口
     * [BoxView.Listener.onReplyDoubleTap] / [returnToApp])。**没有就是"进新会话界面"** (2026-10-09
     * 主人: "没有 (还没发过话) 就进入新会话界面"): 那一档不再落回浮标那本 10 分钟的账
     * (`voice/session.json`) —— 那是"浮标上一轮说话的地方", 与"这块框该带到哪儿"是两件事, 主人要的
     * 是新的会话界面。记号是 [BallReturn.NEW_SESSION], 由会话界面清掉当前会话再重载
     */
    private fun openApp(session: String? = null) {
        val asked = session?.trim()?.takeIf { it.isNotEmpty() }
        // **没点名就是新会话** (不是"没有就不动"): 空串是那个记号, [BallReturn.ask] 认它
        val target = asked ?: BallReturn.NEW_SESSION
        // **先写状态, 再拉界面**: 界面那一侧是拿这个对象当钥匙的, 与 intent 谁先到没有关系
        BallReturn.ask(target)
        note(
            when {
                asked == null -> "back to the app: no conversation was recorded, so a new one it is"
                else -> "back to the app: the reply box asked for $asked"
            },
        )
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(EXTRA_OPEN_SESSION, target)
        runCatching { startActivity(intent) }.onFailure { error ->
            // **这一步失败不许静默**: 起不来就是"点了没反应", 而主人要的是"回到应用"
            hint(getString(R.string.ball_back_failed, error.message ?: error.toString()))
        }
    }

    private fun buildStrip(url: String): View {
        // WebView 要一个带主题的 context, 服务自己没有主题, 所以套一层
        val themed = ContextThemeWrapper(this, R.style.Theme_Luwi)
        val column = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        val bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
            minimumHeight = dp(BAR_HEIGHT_DP)
        }
        val heading = TextView(themed).apply {
            text = title(lastWord)
            setTextColor(TEXT)
            textSize = 14f
        }
        bar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stripTitle = heading
        val collapseButton = button(themed, getString(R.string.overlay_collapse)) { collapse() }
        val toApp = button(themed, getString(R.string.overlay_app)) { openApp() }
        val close = button(themed, getString(R.string.overlay_close)) { hideBall("strip") }
        bar.addView(collapseButton)
        bar.addView(toApp)
        bar.addView(close)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dragHandle(bar)

        // 键盘那点高度只有这块窗拿得到 (它是唯一可获焦的那一块), 球在另一个窗里靠它避让
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
            // **已经摘下来的条不再认 inset**: 收起之后那次收尾回调会拿一个"已经没有键盘"的高度
            // 去按球 (见 [collapse])
            if (!view.isAttachedToWindow) return@setOnApplyWindowInsetsListener insets
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime != imeBottom) {
                imeBottom = ime
                runCatching { applyIme() }
            }
            insets
        }

        val page = WebView(themed).apply {
            settings.javaScriptEnabled = true
            // 主题与字号存在 localStorage 里, 与主界面共用同一份
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            // 语音输出那一半靠页面播放音频, 而 WebView 默认要有手势才放音
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (request.origin.host != "127.0.0.1" || audio.isEmpty()) {
                        Log.w(TAG, "denied ${request.resources.joinToString()} for ${request.origin}")
                        request.deny()
                        return
                    }
                    Log.i(TAG, "granting ${audio.joinToString()} to ${request.origin}")
                    request.grant(audio.toTypedArray())
                }
            }
            loadUrl(url)
        }
        column.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web = page
        return column
    }

    private fun menuRow(parent: Context, label: String, onClick: () -> Unit): TextView =
        TextView(parent).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun label(parent: Context, text: String): TextView =
        TextView(parent).apply {
            this.text = text
            setTextColor(HINT)
            textSize = 12f
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }

    private fun button(parent: Context, label: String, onClick: () -> Unit): TextView =
        TextView(parent).apply {
            text = label
            setTextColor(TEXT)
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    /**
     * 前台服务: 类型里带上 microphone, 浮窗里的 WebView 才好在后台录到音
     *
     * 后台起步时带 while-in-use 类型的那一半可能被系统拒 (持有 SYSTEM_ALERT_WINDOW 是豁免之一,
     * 但不是所有 ROM 都认), 那就退到 specialUse, 并把代价写清楚: 前台时麦克风照常, 后台可能拿不到
     */
    private fun fend() {
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(notice(null)), both)
        } catch (error: Throwable) {
            Log.w(TAG, "the microphone foreground type was refused, falling back to specialUse", error)
            // **兜底那一条也要兜住** (2026-10-06): 两次都抛出来就是服务起不来, 而这一次抛发生在
            // `onCreate` 里 —— 它会一路冒到系统那层, 表现是"球没出来, 应用自己重启了一趟"。
            // 所以这里把最后那条路包住, 失败就走 [fail] 这条正规的收场 (它就是为"建不起来"写的)
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification(notice(null)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }.onFailure { refusal ->
                Log.w(TAG, "the overlay service could not go to the foreground at all", refusal)
                fail(getString(R.string.overlay_refused, refusal.message ?: refusal.toString()))
            }
        }
    }

    /**
     * 常驻通知那一条
     *
     * **点它回应用**: content intent 带上"该落在哪一场"(`open-session`), 于是它是完整的「回应用」,
     * 而不是只把界面拉到前台 (2026-10-07 补上 —— 在这之前这个 intent 里没有那个键, 点通知等于半个
     * 「回应用」)。这一条同时是"直接拉 Activity 被系统拦住"那一档的兜底: 点通知是用户手势, 不受
     * 后台启动的限制
     *
     * 会话 id 取这块框记着的那一场 ([OverlayState.viewSession]: 优先"输入投给了谁", 没有就退回"最新
     * 那条回复是谁推来的")。**两笔账都空着就是"进新会话界面"** (2026-10-09 主人) —— 与双击回复框、
     * 菜单那行「返回应用」同一个取法, 不再去读浮标那本 10 分钟的账 ([VoiceInbox.currentSession])
     */
    private fun notification(text: String): Notification {
        // **与球菜单那行「返回应用」同一套口径** (2026-10-11 主人把两扇门分开之后): 正在想的那一场,
        // 没有在跑的轮就是一场新对话 —— 这里抄下来的这一份只是**冷启动那条路的兜底** (进程是新起的
        // 时候球那份状态还没读进来), 点下去那一刻以 [MainActivity.requestedSession] 现问的为准
        val requested = OverlayState.runningSession()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                // **空串也要带上**: 它说的是"去新会话界面", 而不是"这个 intent 没带会话"
                putExtra(EXTRA_OPEN_SESSION, requested ?: BallReturn.NEW_SESSION)
                // **这一条是"以点下去那一刻那条账为准"的记号** (2026-10-11): 上面那个 id 是**建这条
                // 通知那一刻**抄下来的, 而 PendingIntent 的附加数据从此就定死了 —— 通知只在球上那三个
                // 字变化时重建, 而"框里落进一条回复 / 输入投给了另一场"并不改那三个字, 于是点通知很
                // 容易落到一个**过时**的会话上 —— 与菜单那行「返回应用」各答各的 (主人 2026-10-11 报
                // 的"回到应用"这条路上的不一致正是它)。[MainActivity] 见到这个记号就以**收到 intent
                // 那一刻**的 [OverlayState.viewSession] 为准, 读不到才退回上面这个 id (冷启动那一档)
                putExtra(EXTRA_OPEN_LIVE, true)
            },
            // UPDATE_CURRENT: 会话变了之后那一次重建要真的换掉旧的附加数据
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, getString(R.string.overlay_close), close)
            .setOngoing(true)
            .build()
    }

    private fun announce(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        const val ACTION_SHOW = "io.github.yuloong07star.luwi.overlay.SHOW"
        const val ACTION_HIDE = "io.github.yuloong07star.luwi.overlay.HIDE"
        const val ACTION_EXPAND = "io.github.yuloong07star.luwi.overlay.EXPAND"
        const val ACTION_COLLAPSE = "io.github.yuloong07star.luwi.overlay.COLLAPSE"
        const val ACTION_LISTEN = "io.github.yuloong07star.luwi.overlay.LISTEN"

        /** 输入通道 (那块自己画的文字框): 开 / 再点一次把焦点还给输入框 */
        const val ACTION_CHANNEL = "io.github.yuloong07star.luwi.overlay.CHANNEL"

        /** 宿主推来的一轮回答: 画进通道那块框里 */
        const val ACTION_REPLY = "io.github.yuloong07star.luwi.overlay.REPLY"

        /**
         * 宿主推来的一条**提示** (不是回答): 画进同一块框, 前面带一个 `⚠`
         *
         * 现在只有一条来源: 投递试满几次也没送出去的那一句 (见 host-plugin 的 `reportNote`) —— 那种
         * 事在屏幕上本来一个字都没有, 而主人看到的只是"我说了话, 没有回音"
         */
        const val ACTION_NOTE = "io.github.yuloong07star.luwi.overlay.NOTE"

        /**
         * 通知栏那个「关掉浮标」按钮用的记号
         *
         * 它只是**日志与状态里那半句"这一下是谁按的"**: 三个入口都关同一件事, 而关不掉的现场要分得清
         * 是哪一条路进来的 (通知栏那个走 `PendingIntent`, 与菜单、设置页那两条不是同一段代码)
         */
        const val EXTRA_HIDE_FROM_NOTIFICATION = "notification"

        const val EXTRA_URL = "url"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_EXPAND = "expand"

        /**
         * 「回应用」顺带要落到的那一场对话 (浮标那一场, 见 [openApp])
         *
         * 名字里那个 `OPEN_SESSION` 说的是"会话界面要把哪一场打开", 与 [EXTRA_EXPAND] 那种"窗口怎么摆"
         * 的参数不是一类 —— 它由 [MainActivity] 收下之后交给 [BallReturn]
         */
        const val EXTRA_OPEN_SESSION = "open-session"

        /**
         * "通知那一条的会话以点下去那一刻为准" (2026-10-11)
         *
         * 与 [EXTRA_OPEN_SESSION] 一起带上: 那个 id 是建通知那一刻抄的, 而这条账之后还会变 (见
         * [notification])。带这个记号时 [MainActivity] 先问当下的 [OverlayState.viewSession]
         */
        const val EXTRA_OPEN_LIVE = "open-session-live"

        /** 推给通道的那一句正文 (回答) */
        const val EXTRA_TEXT = "text"

        /**
         * 那条回答是**哪一场会话**发来的 (宿主 `overlay op=reply` 的 `session`)
         *
         * 回复框记进 [OverlayState.replySession]: "框里显示的是谁的话"以它为准, 而**没从框里发过话
         * 时**投递与「回应用」也拿它当兜底 (从框里发过话之后一切按 [OverlayState.inputSession])。
         * 老宿主不带这个键, 读出来是 null, 行为与从前一样
         */
        const val EXTRA_SESSION = "session"

        private const val TAG = "LwOverlay"
        private const val CHANNEL_ID = "lw-overlay"

        /** 与 LwNotify 那条通知分开: 那条是给模型的, 这条是浮窗自己的常驻 */
        private const val NOTIFICATION_ID = 3
        private const val MARGIN_DP = 12
        private const val BAR_HEIGHT_DP = 44
        private const val DEFAULT_HEIGHT_PERCENT = 45

        /**
         * 输入条从屏幕底部往上留的那一截 (dp)
         *
         * 它给的是"别贴着底边" (导航条与手势那一条), 不是键盘 —— 键盘由 inset 那一层另算。以前这个数
         * 是写在 `expand` 里的一行 (140), 现在收成一个名字: 转屏重摆时要用同一个值
         */
        private const val STRIP_LIFT_DP = 140

        /**
         * 转屏之后补一拍的那 250 ms
         *
         * 转屏是异步的: 系统改配置、窗口管理器重排、我们自己收 `onConfigurationChanged` 是三件事,
         * 而它们不保证落在同一帧里。立刻重摆一次、过 250 ms 再对一次账, 那两拍之间用旧值算出来的位置
         * 才收得回来 (与 [BallMinutes.IME_SETTLE_MS] 同一个套路)
         */
        private const val ROTATE_SETTLE_MS = 250L
        private const val MENU_WIDTH_DP = 168
        private const val MENU_HEIGHT_DP = 320

        // **"点几下空白才收"那个数已经不在这里了** (2026-10-06): 它原来是 2 (一个计数器, 隔多久都
        // 累计), 而主人当天把口径收窄成"真双击": 窗口是 [BallMinutes.BOX_DOUBLE_TAP_MS], 判据是
        // [BallTaps.blankDouble], 账是 [OverlayService.lastBlankTapAt] —— 那个常量跟着那本计数器一起
        // 删掉了, 免得留下第二个人回答"几下才算"

        // **"半隐时那点透明度"那个常量已经不在了** (2026-10-17 主人: "改的应该是状态而不是时间"):
        // 半隐藏只挪位置, 不再顺手把球压暗 —— 那个 0.5 与"半透明"的 0.7 相乘出来的 0.35 正是主人
        // 点名不要的耦合。现在两颗球各说各的: 淡由 [BallMinutes.alphaFor] 一个人回答, 藏只看坐标

        /** 与唤醒词服务那个观察者同一个节奏: 400 ms 一次, 只在变了的时候重画 */
        private const val TICK_MS = 400L

        private val BACKGROUND = 0xF21B1B1F.toInt()
        private val MENU_BACKGROUND = 0xF71B1B1F.toInt()
        private val TEXT = 0xFFEDEDED.toInt()
        private val HINT = 0x99EDEDED.toInt()
    }
}

/**
 * 菜单那层要"点外面就关掉"
 *
 * `FLAG_WATCH_OUTSIDE_TOUCH` 给的是一个 `ACTION_OUTSIDE` (坐标在窗外), 它只会走到窗口根那一层 ——
 * 而 `LinearLayout` 自己不吃这个事件, 所以这里收一下
 */
private class MenuRoot(context: Context) : LinearLayout(context) {

    var onOutside: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }
}
