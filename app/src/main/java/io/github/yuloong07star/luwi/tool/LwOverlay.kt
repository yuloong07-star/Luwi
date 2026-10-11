package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.yuloong07star.luwi.channel.LwModes
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.HostStatus
import io.github.yuloong07star.luwi.overlay.BallBox
import io.github.yuloong07star.luwi.overlay.BallMinutes
import io.github.yuloong07star.luwi.overlay.BallSpot
import io.github.yuloong07star.luwi.overlay.OverlayService
import io.github.yuloong07star.luwi.overlay.OverlayState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 浮标的开关: 把一颗球浮在别的应用上面, 点一下就能说话
 *
 * 窗是 [OverlayService] 建的, 这里只管四件事 —— 查权限、把球放出来 / 收起来、展开或收起那条输入条、
 * 再看它现在什么样。真正的绘制与输入都不在这一层
 *
 * **球与输入条是两件事**: 球常驻且不获焦 (窗外照旧穿透), 输入条只在展开时挂上 (它要收系统输入法,
 * 所以它可获焦)。`show` 默认只放球, `expand=true` 才顺带把输入条也挂上
 *
 * `SYSTEM_ALERT_WINDOW` 那条特殊访问本机已经给了 (见 lw_permissions 的「悬浮窗」), 而清单里的
 * `MANAGE_OVERLAY_PERMISSION` 只是把授权位占住 —— 没有它, 下面这一步会直接说缺哪一条, 不会
 * 悄悄什么都不画
 */
internal object LwOverlay {

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "show" -> show(context, request)
        "expand" -> expand(context)
        "collapse" -> collapse(context)
        "hide" -> hide(context)
        "state" -> state(context)
        // 输入通道 (那块自己画的文字框): 开它, 或者把一句回答推进去
        "channel" -> channel(context)
        "reply" -> reply(context, request)
        // 同一条推送, 但这一条不是回答: 比如"这句话没能送进会话" (画出来带一个 ⚠)
        "note" -> note(context, request)
        else -> throw IllegalArgumentException(
            "op has to be show, expand, collapse, hide, state, channel, reply or note, not \"$op\"",
        )
    }

    private fun show(context: Context, request: JsonObject): JsonObject {
        val expand = request.bool("expand", false)
        refusal(context)?.let { unavailable("floating the ball", it) }
        val reason = launch(context, expand)
        if (reason != null) unavailable("floating the ball", reason)
        return buildJsonObject {
            put("shown", true)
            put("expanded", expand)
            put("url", (DshHost.status as? HostStatus.Running)?.url ?: "")
            put("x", OverlayState.x)
            put("y", OverlayState.y)
            put(
                "detail",
                "the ball is up: tap it to speak, hold it for the menu (keyboard input, screen mode," +
                    " close the ball) and drag it to an edge to dock it there",
            )
        }
    }

    /** 展开那条可获焦的输入条 (球留着) */
    private fun expand(context: Context): JsonObject {
        refusal(context)?.let { unavailable("expanding the floating window", it) }
        val started = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_EXPAND
            (DshHost.status as? HostStatus.Running)?.url?.let { putExtra(OverlayService.EXTRA_URL, it) }
        }
        try {
            ContextCompat.startForegroundService(context, started)
        } catch (error: Throwable) {
            unavailable("expanding the floating window", error.message ?: error.toString())
        }
        BallSpot.setOn(context, true)
        return buildJsonObject {
            put("expanded", true)
            put("detail", "the keyboard strip is up, so this window takes focus while it is open")
        }
    }

    /** 收起输入条, 球留着 */
    private fun collapse(context: Context): JsonObject {
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_COLLAPSE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        return buildJsonObject {
            put("collapsed", true)
            put("detail", "the keyboard strip is gone; the ball stays")
        }
    }

    /**
     * 关掉浮标: **走服务自己那条 `ACTION_HIDE`**, 而不是在外面直接 `stopService`
     *
     * 这一条是 2026-10-06 那次修复的核心那次取舍: `stopService` 只把服务停掉, 而**服务停掉不会让它加
     * 的窗自己消失** —— 真机实测到的正是"服务没了、`ball-on` 写成 false、球还留在桌面上"(摘窗只有
     * 服务自己那侧做得到, [OverlayService.hideBall] 里四步: 摘窗、读回、写偏好、停服务)。所以这里
     * 只发那一条动作, 让关闭这件事**只有一份实现**
     *
     * `stopService` 仍然要跟一下: 服务没在跑时 `startForegroundService` 会把它拉起来跑一遍
     * `hideBall` 再停 (无害但绕), 而**服务真的不在时它就是唯一一条路** —— 那种情形下也没有窗要摘
     */
    private fun hide(context: Context): JsonObject {
        val asked = ask(context, wait = true)
        return buildJsonObject {
            put("hidden", true)
            put("showing", OverlayState.showing)
            put("running", OverlayState.running)
            put("problem", OverlayState.hideFailed ?: "")
            put("detail", if (asked.isNotEmpty()) asked else HIDDEN_DETAIL)
        }
    }

    /**
     * 发那条关闭动作, 并且**问一句"窗真的掉了没有"**
     *
     * 两个调用方, 而这个区别是要紧的:
     *
     * - **设置页那个开关不等人** ([wait] = false): 它在主线程上 (`SwitchPreference` 的
     *   `onCheckedChange`), 在这里睡一秒半就是 ANR。它本来也不需要等 —— 那一段每秒轮询
     *   [OverlayState.showing], 窗一掉开关自己就跟着回位
     * - **工具与通道那条路等** ([wait] = true): 它跑在通道自己那条线程上, 而调用方要的正是那句回执
     *   (`op=hide` 的答案里带 `problem`)。不等的话这条工具会在一瞬间回一句"关了", 而屏幕上那块窗还
     *   要过几十毫秒才消失 —— 模型紧接着截一张图就会看到球还在
     */
    private fun ask(context: Context, wait: Boolean): String {
        if (!OverlayState.running && !BallSpot.on(context)) return ""
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { return "the hide action did not reach the service: ${it.message ?: it}" }
        // 窗是服务那一侧摘的, 摘完它把 showing 与 running 都落下去 (最多等 WINDOW_GONE_MS)
        val deadline = System.currentTimeMillis() + WINDOW_GONE_MS
        while (wait && OverlayState.showing && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(POLL_MS) }
        }
        // 兜底: 服务那时可能压根没在跑 (或者已经跑完了那一趟), 所以再直接停一次 —— 它是幂等的
        val stopped = runCatching { context.stopService(Intent(context, OverlayService::class.java)) }
            .getOrDefault(false)
        BallSpot.setOn(context, false)
        OverlayState.showing = false
        OverlayState.expanded = false
        return when {
            OverlayState.hideFailed != null -> "the ball did not come off: ${OverlayState.hideFailed}"
            stopped -> "the ball was stopped"
            else -> HIDDEN_DETAIL
        }
    }

    /**
     * 输入通道 (那块 650 px 的文本框): 把它张开, 焦点给输入框
     *
     * 与 `expand` 的分工: `expand` 是面板 A 那块 WebView (整个会话界面), 这一条是主人 2026-10-05
     * 点名的"自己新建一个文本框" —— 打字、回车发送、回答落在同一个通道里 (见 BoxView)
     */
    private fun channel(context: Context): JsonObject {
        refusal(context)?.let { unavailable("opening the input channel", it) }
        val reason = launch(context, expand = false)
        if (reason != null) unavailable("opening the input channel", reason)
        val intent = Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_CHANNEL)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        BallSpot.setOn(context, true)
        return buildJsonObject {
            put("channel", true)
            put("width", BallBox.WIDTH_PX)
            put("lines", BallBox.MAX_LINES)
            put(
                "detail",
                "the text channel is up (${BallBox.WIDTH_PX} px wide, at most ${BallBox.MAX_LINES} lines," +
                    " it grows with the text and follows the ball); it closes as soon as a line goes" +
                    " out, comes back by itself - without taking focus - when the reply arrives, and" +
                    " closes for good after a double tap on empty space (the second tap within" +
                    " ${BallMinutes.BOX_DOUBLE_TAP_MS}ms, a slower one starts the count again)",
            )
        }
    }

    /**
     * 一轮的回答推回浮标那个通道 (见 host-plugin 的 `startReadAloud`)
     *
     * 通道没开着时**不丢**: 服务那边记着 ([OverlayState.pendingReply]), 而它会顺手把框张出来
     * (主人 2026-10-06 的口径: 回答到了自动打开, 但不抢焦点)
     */
    private fun reply(context: Context, request: JsonObject): JsonObject =
        push(context, OverlayService.ACTION_REPLY, request, "reply")

    /**
     * 一条**提示**推回浮标那个通道 (见 host-plugin 的 `reportNote`)
     *
     * 与 [reply] 是同一条路, 只是记号不同: 应用那侧把它画成一条 `⚠` 行, 所以"它说的"与"系统说哪里
     * 不对"分得开。现在唯一一条来源是投不出去的那句话 —— 那种事在屏幕上本来一个字都没有
     */
    private fun note(context: Context, request: JsonObject): JsonObject =
        push(context, OverlayService.ACTION_NOTE, request, "note")

    /**
     * 两条推送的实际动作: **球没在跑时不许把服务拉起来** (2026-10-06)
     *
     * 它们是宿主每一轮回答都会发的一类, 而 `startForegroundService` 一起服务, `onStartCommand` 就会
     * `showBall` —— 于是一个刚被主人关掉的球会被下一轮回答自己推回屏幕上, 那正是"关掉浮标不生效"里
     * 最难查的一半。通道本来就是"浮标的输入口", 浮标不在时这句话没有落脚处, 如实说一句就够了 (那一句
     * 也不会丢: 球再被叫出来时 [OverlayState.pendingReply] / [OverlayState.pendingNote] 那两条路照旧补上)
     */
    private fun push(context: Context, action: String, request: JsonObject, what: String): JsonObject {
        val text = request.string("text").trim()
        if (text.isEmpty()) throw IllegalArgumentException("op=$what needs the text to show")
        // **哪一场发来的** (`op=reply` 才带, 2026-10-06 加): 回复框记住它, 之后从框里发出去的那句话
        // 就点名投回这一场。`op=note` 不带, 读出来是空串, 应用那一侧按"没点名"处理
        val from = request.string("session").trim()
        if (!OverlayState.running) {
            return buildJsonObject {
                put("delivered", 0)
                put("channel", false)
                put("replies", OverlayState.replies)
                put("detail", "the ball is down, so nothing was pushed; it will show up the next" +
                    " time the ball comes up and the channel opens")
            }
        }
        val intent = Intent(context, OverlayService::class.java)
            .setAction(action)
            .putExtra(OverlayService.EXTRA_TEXT, text)
            .putExtra(OverlayService.EXTRA_SESSION, from)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            unavailable("putting $what into the channel", error.message ?: error.toString())
        }
        return buildJsonObject {
            put("delivered", text.length)
            put("channel", OverlayState.channel)
            put("replies", OverlayState.replies)
            put(
                "detail",
                if (OverlayState.channel) {
                    "it is in the floating text channel"
                } else {
                    "the channel is closed; it is kept and opens the channel as soon as the service" +
                        " picks it up"
                },
            )
        }
    }

    private fun state(context: Context): JsonObject {
        val host = DshHost.status
        return buildJsonObject {
            put("permission", Settings.canDrawOverlays(context))
            put("showing", OverlayState.showing)
            // **服务在不在**与**球在不在**是两件事 (2026-10-06 真机实测的那条: 服务没了而窗还在),
            // 所以两个都要报出来 —— 只看一个的话"关掉浮标"失败时根本说不清失败在哪一半
            put("running", OverlayState.running)
            put("hideProblem", OverlayState.hideFailed ?: "")
            put("expanded", OverlayState.expanded)
            put("channel", OverlayState.channel)
            put("replies", OverlayState.replies)
            // **那块框记着的两笔"哪一场"账** (2026-10-09): `boxInput` 是"我从框里发出去的话投给了
            // 谁" (「回应用」按它走), `boxReply` 是"框里最新那条回复是谁推来的" (它只管显示)。
            // 双击回复框回到哪一场全靠前者 —— 排障时一眼能看出它是不是空的 (空了才会退回后者)
            put("boxInput", OverlayState.inputSession ?: "")
            put("boxReply", OverlayState.replySession ?: "")
            // **框收掉之后还算数的那一笔** (2026-10-11, 见 [OverlayState.boxSession]): 按**双击回复框**
            // 那条路时框本来就是关着的, 所以真正决定它落到哪一场的往往是这一个 —— 上面那两个是空的而
            // 这一个非空才是常态; 三个都空 = 这一台还没跟任何一场说过话, 那一档去新会话界面
            put("boxLast", OverlayState.boxSession ?: "")
            // **此刻按「返回应用」会落到哪一场** (2026-10-11 主人: "走的是正在想的对话"): 有轮在跑时
            // 是那一场 (与上面 `session` 相同), 没有就是一场新对话 (空) —— 球菜单那行与常驻通知都是
            // 这一个; 而 `boxTo` 是**双击回复框**那一跳的落点 (框那一套, 2026-10-11 起两扇门分开)
            put("menuTo", OverlayState.runningSession() ?: "")
            put("boxTo", OverlayState.viewSession() ?: "")
            // 这两条是排查"状态说开着、屏幕上看不见"用的: 逻辑开着 != 那块窗真的挂上了
            put("attached", OverlayState.channelAttached)
            put("channelHeight", OverlayState.channelHeight)
            put("peeked", OverlayState.peeked)
            // **三块窗各自在哪儿 + 这一屏多大** (2026-10-08 加的): 主人报的"横竖屏切换时文本框与球
            // 极大偏移、球消失"只有坐标说得清。规格 `x;y;宽;高` (屏幕自身像素), 与 lw_screenshot 那套
            // 坐标同一个口径 —— 转屏前后各读一次, "偏到哪儿去了"就是两个数的差
            put("screen", OverlayState.screen ?: "")
            put("ballRect", OverlayState.ballRect ?: "")
            put("stripRect", OverlayState.stripRect ?: "")
            put("boxRect", OverlayState.boxRect ?: "")
            // 收边那几道闸的读数 (为什么没收边, 这几个数一起看)
            put("ballPhase", OverlayState.ballPhase)
            put("dragging", OverlayState.dragging)
            put("busy", OverlayState.busy)
            // **这一拍算出来的那一档** (`waiting` / `asleep` / `peeking`) 与它为什么这么判
            // (held / menu / listening / keyboard / channel / activity 那几种): 主人 2026-10-06 报的
            // 三条都是靠这一对数定位的, 所以它必须有
            put("ballWait", OverlayState.ballWait)
            // 键盘算不算"正在用它": 主人点过名的那一条 (键盘输入不属于空闲)
            put("keyboard", OverlayState.keyboard)
            put("idleMs", OverlayState.idleMs)
            put("peekCalls", OverlayState.peekCalls)
            put("peekBlocked", OverlayState.peekBlocked)
            // 双击打断记了几笔 (主人 2026-10-06 加的那一支): 与 peekCalls 一样, 触摸类的东西要有个数
            put("interrupts", OverlayState.interrupts)
            // **确认窗那三笔账** (2026-10-10): `tapCommits` 是投出去几个开麦待办, `tapCommitsCancelled`
            // 是其中被双击/三击当场取消掉几个, `lastTripleMs` 是最近一次三击"第一下到键盘"那一段
            // 有多长。三击那一批修的就是这三个数: 前者与取消数一起涨、最后一个只剩弹框本身的开销
            put("tapCommits", OverlayState.tapCommits)
            put("tapCommitsCancelled", OverlayState.tapCommitsCancelled)
            put("lastTripleMs", OverlayState.lastTripleMs)
            // **常驻语音那一档** (2026-10-10): `residentVoice` 是那个记号在不在 —— 它也是"球上写「正在听」
            // 顶着其余状态"与"点一下连视频模式一起收掉"唯一的判据; `residentVoiceLeaves` 是球上收了几次。
            // 球上那三个字与「正在听」是同一份文案, 所以这两笔账就是那一档的对账处
            put("residentVoice", OverlayState.residentVoice)
            put("residentVoiceLeaves", OverlayState.residentVoiceLeaves)
            // 输入通道那块框闲置了多久 (0 = 框不在屏上): 到 BallMinutes.BOX_IDLE_MS 它自己收
            put("boxIdleMs", OverlayState.boxIdleMs)
            put("ballWindowX", OverlayState.ballWindowX)
            put("lastMoveTo", OverlayState.lastMoveTo)
            put("remembered", BallSpot.on(context))
            put("x", OverlayState.x)
            put("y", OverlayState.y)
            put("word", OverlayState.word ?: "")
            put("phase", OverlayState.phase)
            // 球外那一圈涟漪往哪边走 (`out` = 正在说 / `in` = 正在听 / 空 = 没在跑): 那块窗是非触摸的,
            // 光看 dumpsys 分不出它在画什么, 所以"动效到底跑没跑、往哪边跑"由这一个字段说 (2026-10-09)
            put("pulse", OverlayState.pulse)
            // 这一句播报念的是哪一场的回答, 以及自动掐了几次 (同一场起了新轮那一档): 那一笔账为空时
            // 一条都不掐, 所以这两个数是排障时最先要看的一对
            put("reading", OverlayState.readingSession)
            put("speechCuts", OverlayState.speechCuts)
            // 这一轮在跑的是哪一场、它那个环色是什么 (5 色轮转, 见 BallPhaseFile); 读文件读不动时
            // phaseNote 里有人话 —— "球为什么不显示正在想"就靠这一对
            put("session", OverlayState.session)
            put("ringColor", OverlayState.ringColor)
            put("phaseNote", OverlayState.phaseNote)
            // 最近结束的那一轮是怎么收的 (2026-10-08): 球上写「失败」的判据就在这几个数上 ——
            // `failedKind` 是宿主透传的原因, `failedWhy` 是失败那句理由 (球上不显示, 这里给排障读),
            // 而 `failedAckAt` 是"主人点过一下球了没有"那一笔账: 字没亮时靠它分辨"没失败"与"认过了"
            put("failedKind", OverlayState.failedKind)
            put("failedAt", OverlayState.failedAt)
            put("failedAckAt", OverlayState.failedAckAt)
            put("failedWhy", OverlayState.failedWhy)
            put("mode", LwModes.active(context))
            put("url", OverlayState.url ?: "")
            put("page", OverlayState.lastError ?: "")
            put("said", OverlayState.lastHint ?: "")
            put(
                "host",
                when (host) {
                    is HostStatus.Running -> host.url
                    is HostStatus.Installing -> "installing ${host.done}/${host.total}"
                    is HostStatus.Failed -> "failed: ${host.reason}"
                    HostStatus.Idle -> "idle"
                    HostStatus.Starting -> "starting"
                },
            )
        }
    }

    /**
     * 应用起来时照那个存盘开关把球放出来
     *
     * 与唤醒词那条 `ensure` 同一个道理: 开关是存盘的, 没人照着它做事的话它就只是个记号。缺权限、
     * 开关关着时这里什么都不做 (失败只记日志: 界面不该因为一颗球起不来而不出来)
     */
    internal fun ensure(context: Context) {
        // **这一行是"关掉之后不许被它拉回来"的那道闸**: 浮标那个开关关掉之后, 这条启动路径是唯一
        // 还会把球放出来的地方 (MainActivity 每次起来都叫它), 少了它就是"关掉、重启应用、球又回来了"
        if (!BallSpot.on(context)) return
        if (refusal(context) != null) return
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
            (DshHost.status as? HostStatus.Running)?.url?.let { putExtra(OverlayService.EXTRA_URL, it) }
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    /**
     * 设置页那个开关走这一条: 回一句话 (放出去了没有 / 缺哪一条), 与工具那边说的是同一句
     *
     * 关的方向与 `lw_overlay op=hide` **同一份实现** ([ask]): 只写偏好是不够的 —— 存盘那个记号管的是
     * "下一次应用起来要不要把球放出来", 而**屏幕上那块窗要有人摘** (真机实测: 只写偏好 + 停服务,
     * 球照样留在桌面上, 见 [OverlayService.hideBall])
     */
    internal fun setOn(context: Context, on: Boolean): String {
        if (!on) return ask(context, wait = false).ifEmpty { HIDDEN_DETAIL }
        val reason = refusal(context) ?: launch(context, expand = false)
        if (reason != null) return reason
        return "the ball is up: tap it to speak, hold it for the menu, drag it to an edge to dock it there"
    }

    /** 放球 / 展开: null = 已经发出去了, 否则是那句拒绝的理由 */
    private fun launch(context: Context, expand: Boolean): String? {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = if (expand) OverlayService.ACTION_EXPAND else OverlayService.ACTION_SHOW
            (DshHost.status as? HostStatus.Running)?.url?.let { putExtra(OverlayService.EXTRA_URL, it) }
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            return "starting the floating ball failed: ${error.message ?: error}"
        }
        BallSpot.setOn(context, true)
        return null
    }

    private fun refusal(context: Context): String? =
        if (Settings.canDrawOverlays(context)) {
            null
        } else {
            "the overlay permission is not granted: turn on 显示在其他应用上层 for this app in the" +
                " system settings, or run `appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow`"
        }

    /** 关球那条路的两个数: 等窗真掉下去的上限与检查的节拍 (摘窗是毫秒级的活, 这里只是不许无限等) */
    private const val WINDOW_GONE_MS = 1_500L
    private const val POLL_MS = 50L

    /** 没有球可关时回的那一句 (与"关掉了"分开说: 两件事对主人不一样) */
    private const val HIDDEN_DETAIL = "no ball was running"
}
