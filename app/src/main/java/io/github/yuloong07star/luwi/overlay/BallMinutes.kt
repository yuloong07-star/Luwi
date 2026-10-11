package io.github.yuloong07star.luwi.overlay

/**
 * 浮标这一摊的时长, **一份来源** (主人 2026-10-05 的要求: 时间参数集中定义, 不用散落的魔法数字)
 *
 * 改哪一个数会让什么跟着变, 每个常量上面都写着 —— 状态提示与收边**不是同一个数**: 混成一个的话
 * "提示消失"与"球收边"就会同时发生, 而那看起来像球自己闪了一下
 *
 * **2026-10-05 起这里只剩两个数了**: 主人说"关于球的所有操作都不要有提示", 于是那颗球上不再有
 * 任何自己冒出来的话 (召出提示、手势提示都拿掉了), 与"说一句什么"有关的时长跟着一起没了 ——
 * 留下的 [NOTE_MS] 管的是球上那三个字 (正在听 / 正在想 / 正在念) 显示多久
 *
 * 语音链那一半的闲置超时不在这个文件里: 它属于 `WakeWordService` (那边的
 * `WakeWordService.VOICE_IDLE_MS`), 这里只引一个名字, 不复制那个值
 */
internal object BallMinutes {

    /**
     * 球闲着多久就收边 (半隐)
     *
     * **主人 2026-10-05 原话: "空闲 3 秒后自动隐藏" 之后改成 "4s 改为 5s, 其它不变"** —— 所以这
     * 个数是 5 s, 而"3 秒"落在 [NOTE_MS] 上 (状态提示显示多久), 两件事各管各的
     *
     * 判据是"最后一次活动之后过了这么久", 活动包括: 有人碰它 (按下 / 拖完松手)、它的状态词换过、
     * 菜单开了关、输入通道开了关。有状态词 (正在听 / 正在想 / 正在念) 时不收
     */
    const val PEEK_IDLE_MS = 5_000L

    /**
     * 状态提示 (球上那三个字) 显示多久
     *
     * 主人 2026-10-05 点过名: **3 秒**。它管的是"多久之后不再拿状态词当主角", 与 [PEEK_IDLE_MS]
     * 是两笔账: 提示消失不等于球收边, 收边也不把语音链的状态清掉 (需求里那条"提示的显隐与功能的
     * 开合分开处理")
     */
    const val NOTE_MS = 3_000L

    /** 收边 / 滑回来的那条动画 (与那套内置动画的缺省时长同一个数) */
    const val EDGE_SNAP_MS = 200L

    /**
     * 收边那一刻的**实际**判据 (测试与排查用): 只读 [PEEK_IDLE_MS] 那一个数, **不另写一个字面量**
     *
     * 主人点过名的两个数 (提示 3 s 与空闲 5 s) 都只有一条来源, 而模拟器上要量"3 s 时还没收、5 s 后
     * 收了"这条判据得有个一致的读数 —— 这一条就是给它用的 (见 tools/lw-ball-check.ps1 的 -IdleMs)
     */
    fun idleDue(now: Long, lastActiveAt: Long): Boolean = now - lastActiveAt > PEEK_IDLE_MS

    /** 收起输入条之后等一拍再摆球: 键盘那一次收尾的 inset 回调就在这之后 */
    const val IME_SETTLE_MS = 400L

    /**
     * 两次点球之间至少要隔多久才算"两次"
     *
     * 主人 2026-10-06 报的: "点一次可能直接打开语音输入" —— 手指一抖出两下, 第一下召出、第二下就
     * 顺路进了语音输入。**平台的 `doubleTapTimeout` 是 300 ms** (系统自己拿它区分单击与双击), 所以
     * 这里取同一个量级但更宽一档: 350 ms 之内连着的第二下当作同一次点击, 只记一次账
     */
    const val TAP_GUARD_MS = 350L

    /**
     * 「正在想」时点一下球: **等这么久再开语音输入** (2026-10-08 主人: "进入语音输入要比第二次点击
     * 慢一点")
     *
     * 那一档上挂着两个手势: 点一下 = 开语音, 点两下 = 打断正在跑的那一轮。两下里的第二下必须在
     * [DOUBLE_TAP_MS] (300 ms) 之内到, 所以那一下开语音**至少**要排在它后面 —— 取 450 ms 是把它放到
     * 双击窗口与 [TAP_GUARD_MS] (350 ms) 两条线**都**过去之后, 于是"想打断"的那第二种意图永远先被
     * 认出来 (打断时那一拍会把这里的待办取消掉, 见 `OverlayService.onTap`)
     *
     * 慢了这 450 ms 的代价很小: 它只影响"在想的时候自己开口说一句"这一条路, 而语音窗口开起来之后球
     * 上立刻是「正在听」(见 `BallStatus.wordFor`)
     */
    const val THINKING_TAP_MS = 450L

    /**
     * 双击的窗口: 主人 2026-10-06 点名的**0.3 s**
     *
     * 它只在"正在想"那一档有用 (见 [BallTap] / [BallTaps]): 两下之间的间隔不超过它才算一次双击,
     * 而双击那一下要打断正在跑的那一轮。这个数**小于** [TAP_GUARD_MS] 是有意的 —— 双击窗口是"怎么算
     * 一下", 而防连击窗口是"第二下要不要当没发生", 两件事的时长本来就不是一个
     */
    const val DOUBLE_TAP_MS = 300L

    /**
     * "正在听"那一档的防连击窗口: 主人 2026-10-06 点名的**1 s**
     *
     * 那一档的点击是"把语音输入收回来"([BallAct.HUSH]), 而开语音的那一下也是点球 —— 手抖出第二下
     * 就会"刚开就关"。1 s 之内的第二下**只记一次账** (什么都不做), 1 s 之后照旧能关; 其余状态的窗口
     * 还是 [TAP_GUARD_MS]
     */
    const val LISTEN_GUARD_MS = 1_000L

    /* ── 防误触档那几个数 (2026-10-09 主人: "ball 的键盘输入和语音输入经常会误触" ──────
     *
     * 只有 [BallFeel.GUARD] 用它们, 标准档照旧用上面那几个 —— 于是"标准"那半边的三个数 (300 / 350 /
     * 1000) 一个字都不用改, 而防误触档把**开语音与开键盘输入这两条入口**收紧:
     *
     * - 双击窗口收窄: 打断那一支是唯一"点错就真的停了一场"的动作
     * - 两个"太近不算"的窗口放宽: 手抖出来的第二下被算成同一击, 于是不会进语音输入
     *
     * **2026-10-10 起这一档还多一个 [BallFeel.commitWindowMs]** (开麦那一下的确认窗, 取值就是双击
     * 窗口): 单击里只有"真要开麦"那一支排队, 其余动作照旧当场做掉
     */

    /** 防误触档的双击窗口: **220 ms** (标准档是 300 ms) */
    const val BALL_DOUBLE_TAP_MS = 220L

    /** 防误触档的点球防连击窗口: **450 ms** (标准档是 350 ms) */
    const val BALL_TAP_GUARD_MS = 450L

    /** 防误触档的"正在听"防连击窗口: **1500 ms** (标准档是 1000 ms) */
    const val BALL_LISTEN_GUARD_MS = 1_500L

    /**
     * [BallFeel.thinkingTapMs] 那一条比两条线还要晚的余量: **80 ms**
     *
     * 它只保证"晚一点"这件事本身有个可量的间距, 而不是压着窗口边界 —— 压线的话两边的判据会随主线程
     * 那一拍的抖动互相越界
     */
    const val THINKING_TAP_MARGIN_MS = 80L

    /**
     * 输入通道那块框没人碰多久就自己收掉: **20 s**
     *
     * 主人 2026-10-06 选的处置 (回复框也跟着空闲自己收): 回答到了框会自己张出来 ([OverlayService.deliverReply]),
     * 而框开着的那段时间球不收边 ([BallRest.wait] 的 `channel` 那条闸, 因为框是跟着球摆的)。于是
     * "回复到了"与"球半隐"这两件事会互相顶住 —— 框没人碰就自己收, 收掉之后球那一笔账从"框关掉"重新
     * 起算 ([OverlayService.closeChannel] 的 [BallRest] 记账), 5 s 后就收边
     *
     * 20 s 是"够读完那几条回复"的量级 (回复那一块最多 [BallBox.REPLY_LINES] 行, 2026-10-06 由 3 行
     * 加到 5 行), 比收边的 5 s 长得多是故意的: 框是给人读的, 而球只是"别挡着"。**有人用它就不算
     * 没人碰**: 打进一个字 (`BoxView.Listener.onTyping`)、滚动内容 (`onScroll`)、摸到框 (`onTouch`)
     * 与开语音 (服务那一侧 `parkChannel`) 都续期, 所以停手 20 s 才收 —— 框里的半截文字留着, 再张
     * 回来还在
     */
    const val BOX_IDLE_MS = 20_000L

    /**
     * 框外那两下的窗口: 与球上那个 [DOUBLE_TAP_MS] 同一个数 (**300 ms**)
     *
     * 主人 2026-10-06 点名的口径: "收窄成真双击, 超时的要重新计算, 而不是隔多久都累计" ——
     * 原来那本账是个纯粹的计数器 (点一下、过一会儿再点一下也会把关掉), 现在第一下只在窗口内有意义:
     * 超时的第二下**重新起算** (它是新的一下, 不是第一下的第二下)
     *
     * 与球上那个数是**同一个量级也是同一个值**: "双击"在这套东西里就只有一个时长, 两处不一样的话
     * 只会让人猜"到底多快才算" (判据见 [BallTaps.blankDouble])
     */
    const val BOX_DOUBLE_TAP_MS = 300L

    /**
     * "这一次召出"算多久之内还有效 —— 也就是"再点一下就说话"给主人留的那段时间
     *
     * 这一段是**点击序列那四段的核心** (2026-10-06 主人报的两条毛病合成的一条病根):
     *
     * - 球被召出之后 (半隐里点一下 / 菜单关掉之后), 紧随其后的下一击才是"第二次点击" → 开语音
     * - 超过这段时间再点, **只当又召出一次** (不会开口) —— 于是"点一次就开了语音"不可能发生
     *
     * 要长到"看完球滑回来再点"仍然算得上 (滑回来那条动画 200 ms, 加上人的反应), 又要短到"隔了很久
     * 随手一点"不会被当成那半句话的开头。3 s 与状态提示同一个量级
     */
    const val SUMMON_FRESH_MS = 3_000L

    /**
     * 这一击算不算"刚被召出来那一下之后的第一击"
     *
     * 纯函数, 没有设备也能量 (见 BallTest): 召出那一刻之后的 [SUMMON_FRESH_MS] 之内为真
     */
    fun summonIsFresh(now: Long, summonedAt: Long): Boolean =
        summonedAt != 0L && now - summonedAt in 0..SUMMON_FRESH_MS

    /* ── 防误触那一条 (2026-10-08 主人: "增加 ball 各操作的防误触") ──────────────
     *
     * 球上现在挂着手势五档 (单击 / 双击 / 三击 / 长按 / 拖), 全都落在同一颗 48 dp 的球上 —— 手一抖
     * 就可能把"说话"、"打断"、"开键盘"、"出菜单"里的某一个做掉。下面这四个数是把那一档收紧的地方,
     * 而它们的判据都写成纯函数/常量, 没有设备也能量 (见 BallTest)
     */

    /**
     * 长按门槛的基准: **500 ms**
     *
     * 平台自己的 `ViewConfiguration.getLongPressTimeout()` 是 400~500 ms 那一档, 而那对一颗随时都
     * 在屏上的球来说太短了 —— 慢一点的单击 (按下、觉得不对、松开) 会被判成长按并弹出菜单。
     * 防误触档在这个基准上再加 [BALL_PRESS_MARGIN_MS] 那么一段 (见 [BallFeel.pressMs])
     *
     * **2026-10-10 之前防误触档写死 650 ms** (主人 2026-10-08 点名), 而那与"标准档直接用平台的
     * 400~500 ms"差了一倍多 —— 同一颗球两档之间的长按手感于是对不上。现在两档都是一个式子: 基准是
     * `max(平台值, 这个数)`, 防误触档只多一段余量。典型设备 (平台值 400~500 ms) 上量出来仍是
     * 650 ms, 与原值一个字不差
     */
    const val BALL_PRESS_BASE_MS = 500L

    /** 防误触档在长按基准上留的那一段余量: **150 ms** (与基准合起来就是原来那个 650 ms) */
    const val BALL_PRESS_MARGIN_MS = 150L

    /**
     * 判定"这一下是拖动"的门槛: **至少 12 dp**
     *
     * 平台那个 `scaledTouchSlop` 只有 8 dp 上下, 手在球上按一下时抖两下就过线了 —— 于是本该是单击的
     * 那一下变成"拖了一下没动地方", 而拖动是不出单击动作的 (见 [BALL_DROP_GUARD_MS] 与 BallView)
     */
    const val BALL_SLOP_DP = 12

    /**
     * 刚拖完的那一下不算单击: **250 ms**
     *
     * 拖动松手会吸附到边上 ([OverlayService.snapToEdge]), 而人常常在松手之后又补一下 —— 那一下如果
     * 算单击, 球就会在刚放好的位置上开口说话。这一条只挡"紧接着的那一下", 隔久了照旧
     */
    const val BALL_DROP_GUARD_MS = 250L

    /**
     * 刚拖完的那一下算不算单击 (纯函数, 见 BallTest)
     *
     * [feel] 是设置页那一档灵敏度 (见 [BallFeel]): 标准档那一条闸是关着的 ([BallFeel.dropGuardMs]
     * 为 0), 于是这里一律答"不算" —— 关掉的手段是"窗口是 0", 不是另一份判断
     */
    fun tapAfterDropIsFresh(
        now: Long,
        droppedAt: Long,
        feel: BallFeel = BallFeel.GUARD,
    ): Boolean {
        val window = feel.dropGuardMs
        return window > 0L && droppedAt != 0L && now - droppedAt in 0..window
    }
}

/**
 * 浮标手势的灵敏度: **两套常数, 一份来源** (主人 2026-10-08: 防误触那一条与"做成设置页一档")
 *
 * 那颗球上挂着五档手势 (单击 / 双击 / 三击 / 长按 / 拖), 全都落在同一个 48 dp 上, 而"多严"这件事
 * 因人而异: 手稳的人嫌 650 ms 的长按等得久, 一只手拿东西的人嫌平台的 400 ms 太容易弹菜单。所以
 * 两套常数都写在这里 (与那几个 `BALL_*` 挨着, 不散到调用方), 设置页只选一个名字
 *
 * **取值的方向是 0 = 这一档不启用这条闸**, 于是"关掉"与"用平台的"不需要第二份判断:
 *
 * | 档 | 长按 | 拖动门槛 | 刚拖完那一下 | 双击窗口 | 点球防连击 | 正在听防连击 | 确认窗 |
 * | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- |
 * | [STANDARD] 标准 | 平台的 `longPressTimeout` (400~500 ms) | 平台的 `scaledTouchSlop` (8 dp 上下) | 不挡 | 300 ms | 350 ms | 1 s | 300 ms |
 * | [GUARD] 防误触 (缺省) | `max(平台值, 500) + 150` = 650 ms | 12 dp | 250 ms | 220 ms | 450 ms | 1.5 s | 220 ms |
 *
 * **后三列 2026-10-09 加的** (主人: "ball 的键盘输入和语音输入经常会误触" + "只收紧防误触档"):
 * 双击窗口、以及那两档"太近不算"的防连击窗口原来两档共用, 现在也各归各的档 —— 标准档那三个数
 * (300 / 350 / 1000) 一个字没改, 收的只有防误触档。收紧的方向两边都指向"更难误开语音与键盘":
 * 双击窗口收窄 (打断那一下最不该被手抖凑出来), 两个防连击窗口放宽 (抖出来的第二下算同一击)
 *
 * **2026-10-10 的两处**: 加了最后一列 `commitWindowMs` (开麦那一下的确认窗, 取值就是双击窗口),
 * 而长按那一列不再写死 650 ms —— 它改成从平台的 `longPressTimeout` 派生 (见 [pressMs]), 典型设备
 * 上仍是 650 ms。原来那条"三击总时长 450 ms"的闸删掉了: 2 × 220 = 440 < 450, 它在防误触档永远
 * 触不到, 留着只会让这张表与逻辑脱节
 */
internal enum class BallFeel {
    /** 标准: 手感优先, 长按与拖动两个门槛都交给平台那一档 (另外两条防连击也回到上面那两个数) */
    STANDARD,

    /** 防误触: 缺省, 上面那张表里右侧那七个门槛都往"更难误触发"那一头 */
    GUARD;

    /**
     * 长按门槛: 标准档就是平台那个 `ViewConfiguration.getLongPressTimeout()` (400~500 ms 那一档),
     * 防误触档在 `max(平台值, [BallMinutes.BALL_PRESS_BASE_MS])` 上再加
     * [BallMinutes.BALL_PRESS_MARGIN_MS] 那么一段
     *
     * 平台值当入参而不是在这里读 `ViewConfiguration`, 是为了让这个式子**没有设备也能量** (见 BallTest)
     * —— 上一版防误触档写死 650 ms, 两档之间的长按手感差了一倍多, 现在两档是同一个式子
     */
    fun pressMs(platformMs: Long): Long = when (this) {
        GUARD -> maxOf(platformMs, BallMinutes.BALL_PRESS_BASE_MS) + BallMinutes.BALL_PRESS_MARGIN_MS
        STANDARD -> platformMs
    }

    /** 判定"这一下是拖动"的门槛, 0 = 用平台的 `scaledTouchSlop` */
    val slopDp: Int get() = if (this == GUARD) BallMinutes.BALL_SLOP_DP else 0

    /** 刚拖完那一下的挡窗, 0 = 不挡 */
    val dropGuardMs: Long get() = if (this == GUARD) BallMinutes.BALL_DROP_GUARD_MS else 0L

    /**
     * 双击窗口 ("正在想"那一档里两下算不算一次打断)
     *
     * **标准档还是主人点名的 0.3 s**; 防误触档收窄一档 —— 打断是这三档里唯一"点错就真的停了一场"
     * 的动作, 越窄越不容易被手抖凑出来 (2026-10-09 主人: "键盘输入和语音输入经常会误触")
     */
    val doubleTapMs: Long get() = if (this == GUARD) BallMinutes.BALL_DOUBLE_TAP_MS else BallMinutes.DOUBLE_TAP_MS

    /** 两次点球之间"太近, 不算一次"的窗口 (见 [BallTaps.guard]) */
    val tapGuardMs: Long get() = if (this == GUARD) BallMinutes.BALL_TAP_GUARD_MS else BallMinutes.TAP_GUARD_MS

    /** "正在听"那一档的防连击窗口 (那一档的点击是"把语音收回来", 手一抖就是刚开就关) */
    val listenGuardMs: Long get() =
        if (this == GUARD) BallMinutes.BALL_LISTEN_GUARD_MS else BallMinutes.LISTEN_GUARD_MS

    /**
     * **动作的确认窗** (2026-10-10): 单击里那个"贵重动作" (开麦) 要等这么久才真的提交
     *
     * 取值就是 [doubleTapMs], 不是另一个数 —— 理由在时间轴上: 过了这一个窗口还没有第二下, 双击
     * (两下都 ≤ [doubleTapMs]) 与三击 (每一下都 ≤ [doubleTapMs]) 就都**不可能**再成立了, 所以
     * "这一下是单击"到那一刻就是终局, 再等只是白等
     *
     * 动作层只读这一个名字 ([OverlayService.onTap] 与 [BallCommit]), 不再直接引用 `BALL_DOUBLE_TAP_MS`
     * 那一族常量 —— 于是以后加第三档灵敏度也不用到处改
     */
    val commitWindowMs: Long get() = doubleTapMs

    /**
     * 「正在想」里那一次单点该等多久才开语音
     *
     * **它必须晚于防连击窗口与双击窗口** —— 这是 `OverlayService.pendingTap` 那套的硬前提: 不等的话
     * 双击的第一下会先走单点动作 (开麦克风), 于是"想打断"变成了"开了语音"。原来它是一个常量
     * (450 ms), 而防误触档把两个窗口都放宽之后那个常量就排到它们前面去了, 所以改成**由当前这一档
     * 算出来**: 取两条线里更晚的那个, 再加上一档余量
     */
    fun thinkingTapMs(): Long =
        maxOf(tapGuardMs, doubleTapMs) + BallMinutes.THINKING_TAP_MARGIN_MS

    companion object {
        /** 存盘用的名字, 与设置页那两项一一对应 */
        fun of(name: String?): BallFeel = if (name == STANDARD.name) STANDARD else GUARD
    }
}

/**
 * 点击序列与期望结果的对照表 —— 这一段是**纯函数**, 没有设备也能量 (见 BallTest)
 *
 * 需求原话里那几段最容易写错: "第一次点击只召出浮标"、"第二次点击才打开语音输入"、"召出后再次
 * 点击不弹提示、直接进入语音输入", 加上 2026-10-05 追加的"念回复时再点一次球可停止播报"。四种
 * 情形落在同一个 `onTap` 上, 靠的就是这里这一个 `when`:
 *
 * | 点之前的样子 | 点一下之后 |
 * | :-- | :-- |
 * | 半隐收着 ([BallPhase.RESTED]) | 滑回来, **不碰语音链** ([BallAct.SUMMON]) |
 * | 已经召出 ([BallPhase.SUMMONED]) | 进语音输入 ([BallAct.LISTEN]) |
 * | 正听着 ([BallPhase.VOICE]) | 收回语音输入 ([BallAct.HUSH]) |
 * | 正在念回答 (`speaking`) | **掐断播报** ([BallAct.STOP]) |
 *
 * **输入通道开着不再是"什么都不做"** (2026-10-06 改): 原来第一档是 `channelOpen -> NOTHING`, 于是
 * "键盘开着时点球"什么都不发生 —— 主人报的正是这一条 ("键盘输入有概率不能使用点击浮标的方式打开
 * 语音", 有概率 = 那块框还在的时候)。框有自己的位置、自己的焦点, 球不该替它挡住语音这一个入口;
 * 点球那一下照旧会把"框外双击"那本账清掉 (见 `OverlayService.ballListener` 的 onTap), 所以
 * 框该不该收还是由那本账说了算
 *
 * **「正在想」那一档点一下开语音, 但要等过双击窗口** (2026-10-08 主人定的口径): 一下 = 开语音输入,
 * 两下 = 打断正在跑的那一轮 —— 两者靠**时间**分开: 那一下开语音排在 [THINKING_TAP_MS] (450 ms, 比
 * 双击窗口 300 ms 与防连击窗口 350 ms 都晚) 之后, 于是第二下永远先被认成打断, 而**打断了就把那个
 * 待办取消掉** (见 `OverlayService.onTap`)。开起来之后球上写「正在听」(优先级见 `BallStatus.wordFor`)
 *
 * **"正在念"排在"正听着"前面**: 半双工那道闸在念的时候把麦克风整个关掉了, 所以那时人真正想按的是
 * "别念了" —— 而"正在念"这几个字就写在球上, 按下去掐断它是最直的那个理解
 *
 * **按住 (长按) 不走这里**: 它一直在球自己的触摸处理里 ([BallView.Listener.onLongPress]), 菜单
 * 与这几段点击互不干扰
 */
internal enum class BallPhase {
    /** 半隐收着: 只有一条边留在屏幕里 */
    RESTED,

    /** 全露着, 但没在听 (`idle`) */
    SUMMONED,

    /** 常驻语音那半条在跑 */
    VOICE,
}

/** 球上点一下该做什么 */
internal enum class BallAct {
    /** 半隐的球滑回来 */
    SUMMON,

    /** 开语音输入 (与常驻语音同一个入口) */
    LISTEN,

    /** 把语音输入收回来 */
    HUSH,

    /**
     * **收掉常驻语音那一档, 并把视频模式一并退出** (2026-10-10 主人: "在 ball 新增一个常驻语音状态
     * …单击关闭后也关闭视频模式")
     *
     * 它挂在**常驻语音开着时那一下"正在听"**上 (主人: "显示状态与正在听一致" + "那三个字也是正在听"):
     * 显示上一模一样, 只有这一下多做一件事 —— 常驻语音本来就是视频模式要的东西 (见 [LwModes]), 于是
     * "把这一路收掉"与"退出视频模式"在同一处收口, 走的是切模式那**同一次调用**, 不是第二套做法。
     * 收掉之后相机、常驻链与 `.active` 一起回到手机模式
     */
    LEAVE_VIDEO,

    /** 掐断正在念的回答 (播报) */
    STOP,

    /** 什么都不做 */
    NOTHING,
}

internal object BallTouch {
    /**
     * **动作的优先级与显示的不是同一张表** (2026-10-09): 显示那边现在是"在想 > 在念 > 在听"
     * ([BallStatus.wordFor]), 而这里仍是"在念 > 在想 > 在听" —— 于是"球上写着「正在想」、喇叭同时
     * 也在念"那一种重叠里, 单点那一下还是"别念了" (那是此刻唯一在出声的东西), 而双击才是打断那一轮
     * ([BallTaps.kind] 按显示的字认双击)。两件事分开是有意的: 显示回答"现在最要紧的是什么",
     * 动作回答"这一下最可能想做什么"
     *
     * @param listening 那一条语音链此刻是不是开着 ([VoiceState.capturing]) —— 判它而不是判 [phase]:
     *   `phase` 现在同时被"正在听"与"正在想"占用 (两者都不收边), 靠它分不出该"收回"还是该"开"
     * @param resident 常驻语音此刻开着没有 (视频模式那个记号, 见 `LwWakeWord.resident` / `LwModes`):
     *   它与"麦克风开着"同时成立时, 那一下不只是"收一句", 而是"把常驻语音与视频模式一起收掉"
     *   (主人 2026-10-10)。**显示上两档是同一档** (都是「正在听」), 差别只在动作这一边
     */
    fun act(
        phase: BallPhase,
        speaking: Boolean,
        channelOpen: Boolean,
        listening: Boolean = false,
        resident: Boolean = false,
    ): BallAct = when {
        // 念回答的时候那一下是"别念了" (主人 2026-10-05 追加的一条)
        speaking -> BallAct.STOP
        // 半隐收着的球: 那一下永远只是"召出来"
        phase == BallPhase.RESTED -> BallAct.SUMMON
        // 常驻语音那一档 (显示上就是「正在听」): 点一下把它收掉, 并把视频模式一并退出 (主人 2026-10-10)
        resident && listening -> BallAct.LEAVE_VIDEO
        // 麦克风开着: 点一下收回来 (原来判的是 `phase == VOICE`, 而那个档位现在也会因为"正在想"而成立)
        listening -> BallAct.HUSH
        // 剩下两档都是"点一下开语音": 空闲时那是"点两次才说话"的第一下 (见 [opensVoiceNow]),
        // 而**正在想时那一下等过双击窗口就当场开** (见 [THINKING_TAP_MS] 与 OverlayService.tapAction)
        else -> BallAct.LISTEN
    }

    /**
     * 这一下 [BallAct.LISTEN] 该不该**当场**开语音
     *
     * 两条: 刚被召出来那一下之后的第一击 (主人点名的"点两次才说话"), 或者**回复框在屏上且有回复**
     * —— 那时人已经在那块框上说话了, 再要他点两下是多余的 (2026-10-06 那条例外: "有回复窗时也可以
     * 点击小球进行语音输入")。判据是纯函数 (见 BallTest), 调用方只按它的答案走
     */
    fun opensVoiceNow(summonFresh: Boolean, replyBox: Boolean): Boolean = replyBox || summonFresh
}

/**
 * 这一下单击该"马上做"还是"等一个确认窗再做"
 *
 * **为什么要有这一层** (2026-10-10 主人: "三击 ball 会打开语音输入, 并且会卡一下"): 单击的动作是
 * 在 UP 那一刻当场做掉的 (见 [BallTaps] 的文件头), 而三击的头一下就可能已经把那句"开语音"做掉了
 * —— 第三下再来把它收掉、改弹键盘, 于是主人看到的是"先开麦、卡一下、再弹键盘"。开麦那一步会起
 * 前台服务并在主线程上建切段会话, 一次来回就是一次整屏卡顿
 *
 * 处置是**只给开麦加一个确认窗** ([BallFeel.commitWindowMs]): 窗里来了第二下 (双击) 或第三下
 * (三击) 就把待办取消, 于是一次三击从头到尾不碰麦克风。其余动作**一律立即**:
 *
 * - [BallAct.SUMMON] (半隐球滑回来) 是"手指落下去就要跟手"的那一条, 晚一档就成了按了没反应
 * - [BallAct.HUSH] (正在听时收麦) 与 [BallAct.STOP] (正在念时掐播报) 都是"别说了"那一个意思,
 *   晚 220 ms 就变成说了半句才停 —— 那比多开一次麦糟得多
 * - [BallAct.LISTEN] 里那支"只记召出、不开麦"的分支只写一个时间戳, 当场做掉没有任何代价
 *
 * 判据是纯函数 (见 BallTest), 调用方只按它给的那个数决定等不等、等多久
 */
internal object BallCommit {

    /**
     * 这一下要等多久才提交; **0 = 当场做掉**
     *
     * 三个入参与 [BallTouch.act] 和 [BallTouch.opensVoiceNow] 共用, 只是提前问一次"这一下会是开麦
     * 吗" —— 答案在窗里不会变 (那一支只看状态词与两笔框账), 所以可以提前取
     *
     * 两个等待长度不是随便挑的:
     *
     * - 「正在想」那一档本来就在等 [BallFeel.thinkingTapMs] (要晚过双击与防连击两条线), 那个数
     *   原封不动地留用 —— 它比确认窗还晚, 于是"想打断"永远先被认出来
     * - 其余开麦只等 [BallFeel.commitWindowMs]: 过了这一个窗口就不会再有双击/三击了, 再多等只是
     *   白让主人等 (那个数的理由写在 [BallFeel.commitWindowMs] 上)
     */
    fun deferMs(
        act: BallAct,
        word: BallWord?,
        summonFresh: Boolean,
        replyBox: Boolean,
        feel: BallFeel = BallFeel.GUARD,
    ): Long = when {
        act != BallAct.LISTEN -> 0L
        word == BallWord.THINKING -> feel.thinkingTapMs()
        word == BallWord.FAILED -> feel.commitWindowMs
        BallTouch.opensVoiceNow(summonFresh, replyBox) -> feel.commitWindowMs
        else -> 0L
    }
}

/**
 * 输入通道那块框记着的两笔"哪一场"账, 该按哪一场走
 *
 * 两笔账都来自宿主, 但答的不是同一件事 (2026-10-09 主人: "谁发送了输入框, 就回到那一场"):
 *
 * - `input` (`OverlayState.inputSession`): **我从这块框发出去的那句话投给了谁** —— 主人要的"对应的
 *   会话"就是它, 所以它在前面
 * - `reply` (`OverlayState.replySession`): **框里最新那条回复是哪一场推来的** —— 一次都还没发过话
 *   (框是三击刚开出来的) 时退回它, 那样"框里正显示着谁的回答"与"回去看谁"仍然对得上
 *
 * 两条都没有 (既没发过话, 也没收过带会话的回复) 就回 null, 由调用方接浮标账本那条兜底。抽成纯函数
 * 是为了没有设备也能量 (见 BallTest): 优先级反了正是这次要修的那条病 (回到"最近推过回复的那一场"
 * 而不是"我发给了哪一场")
 */
internal object BoxTarget {

    fun choose(input: String?, reply: String?): String? =
        input?.takeIf { it.isNotBlank() } ?: reply?.takeIf { it.isNotBlank() }
}

/**
 * 「回去看会话」那两跳各自的落点 —— **一份判据, 两扇门各问一句**
 *
 * 主人 2026-10-11 把两扇门分开定了 ("ball菜单栏里『回到应用』要改成走的是正在想的对话，没有则返回新
 * 对话。而双击回复框现在不变，仍返回现在的发送回复的对应对话"):
 *
 * - [menu] (球菜单那行「返回应用」, 与常驻通知那条路): **正在想的那一场**, 没有就 null —— 调用方给的
 *   是 `BallReturn.NEW_SESSION` 那个记号, 也就是"回去开一场新的"。按「返回应用」要看的是"它现在在
 *   给谁干活", 所以**不看这块框记着哪一场** (那个是"谁说的"的账, 不是"它在忙什么"的账)
 * - [replyBox] (双击回复框): **这块框跟哪一场打过交道** (投出去的那一句优先, 见 [BoxTarget]),
 *   没有才是"框上次跟谁打过交道" —— 这一档**一个字没改** (2026-10-09 的口径)
 *
 * 判据抽成纯函数是为了没有设备也能量 (见 BallTest): "谁压过谁、哪一档不给"正是最容易在改动里悄悄
 * 漂开的地方
 */
internal object ReturnTarget {

    fun menu(running: String?): String? = running?.takeIf { it.isNotBlank() }

    fun replyBox(input: String?, reply: String?, last: String?): String? =
        BoxTarget.choose(input, reply) ?: last?.takeIf { it.isNotBlank() }
}

/**
 * 这一下点击算哪一种
 *
 * 三个词就是全部答案, 而它比 [BallTouch.act] 先跑一步: "双击"与"单点"是**两个不同的手势**, 得先
 * 分开, 再拿单点那一下去问 [BallTouch.act] 该做什么
 */
internal enum class BallTap {
    /** 一次算数的点击: 走原来的四段 ([BallTouch.act]) */
    SINGLE,

    /** 双击的第二下 (只在"正在想"那一档): 打断 [BallAct] 那一套之外的那一个动作 */
    DOUBLE,

    /**
     * **三击: 打开键盘输入那块框** (主人 2026-10-07: "加上三击 ball 打开键盘输入, ball 菜单的
     * 键盘输入可以删除")
     */
    TRIPLE,

    /** 离上一次太近, 当同一次点击 (什么都不做, 只记一行) */
    TOO_SOON,
}

/**
 * 点击序列外面那一层: 双击、三击与防连击
 *
 * 判据是四个输入 (**球现在说的是哪三个字** + 这一次 / 上一次的时刻 + 这一下算进第几次连击), 所以
 * 没有设备也能量 (见 BallTest) —— 而它恰恰是最容易在真机上"看着差不多"的地方: 双击窗口比防连击窗口
 * 宽一点, 单点的那一下就会被双击吃掉; 窄一点, 手一抖就算两次手势
 *
 * 三条规则:
 *
 * - **两下贴着 (300 ms 之内, [BallMinutes.DOUBLE_TAP_MS]) = 双击** ([BallTap.DOUBLE]): 只有"正在想"
 *   那一档有动作 (打断正在跑的那一轮), 别的档里第二下什么都不做 —— 于是"两下贴着"对主人的净效果
 *   还是原来那一次单击
 * - **三下连着 (每一下都贴着上一记) = 三击** ([BallTap.TRIPLE]): 打开键盘输入那块框。这是菜单里
 *   那行「键盘输入」的替身 (那行删掉了)
 * - 离上一记超过连击窗口但**还在防连击窗口里**的那一下 = [BallTap.TOO_SOON]: 什么都不做, 只记一行
 *
 * **净效果那一层要想清楚**: 单击那一下是**当场**做掉的 (只有开麦那一支会等一个确认窗, 见
 * [BallCommit]), 所以连击计数只是"把手势认出来", 不取消第一下已经做的事 —— 三击里那两下轻动作
 * (召出球、记一笔) 因此照样发生, 而"打开键盘输入"是三击自己的动作 ([OverlayService.onTap] 那一支)
 */
internal object BallTaps {

    /**
     * 这一档的防连击窗口: 正在听那一档比其余那档宽一截
     *
     * **两个数都随 [BallFeel] 走** (2026-10-09): 防误触档把它们一起放宽, 于是手抖出来的第二下被算成
     * 同一击, 不会顺路进语音输入。标准档读到的仍是主人点名的 1 s / 350 ms
     */
    fun guard(word: BallWord?, feel: BallFeel = BallFeel.GUARD): Long =
        if (word == BallWord.LISTENING) feel.listenGuardMs else feel.tapGuardMs

    /**
     * 这一下算哪一种
     *
     * @param lastTapAt 上一次**算数的**点击时刻 (0 = 还没有过)
     * @param chain 这一下之前那串连击已经数到几次 (0 = 上一记之后已经隔开了)
     *
     * 判据的先后就是它的意思: **先看"是不是接着上一记"** (300 ms 之内), 是就往下数 (第 2 下是双击,
     * 第 3 下起是三击 —— 四连击也算三击, 那只是主人多戳了一下); 不是再看"是不是离得太近" (防连击那
     * 个窗口), 太近的**既不计数也不动作**; 都不是才是新的一次单击
     *
     * **2026-10-08 到 2026-10-10 之间这里还有一条"整串总时长 450 ms"的闸**, 现在删掉了: 三击的
     * 每一段间隔都必须 ≤ [BallFeel.doubleTapMs] (防误触档 220 ms), 于是整串最多 2 × 220 = 440 ms,
     * 那条 450 ms 的闸在防误触档永远触不到; 标准档根本没开它。留着只是一行永远为真的判断
     *
     * [feel] 是设置页那一档灵敏度: 双击窗口与两档防连击窗口都跟着它走
     */
    fun kind(
        word: BallWord?,
        now: Long,
        lastTapAt: Long,
        chain: Int = 0,
        feel: BallFeel = BallFeel.GUARD,
    ): BallTap {
        if (lastTapAt == 0L) return BallTap.SINGLE
        val gap = now - lastTapAt
        return when {
            gap <= feel.doubleTapMs ->
                if (chain + 1 < 3) BallTap.DOUBLE else BallTap.TRIPLE
            gap < guard(word, feel) -> BallTap.TOO_SOON
            else -> BallTap.SINGLE
        }
    }

    /**
     * 框外那两下算不算一次双击: 真 = 这一下就是窗口内的第二下 (**关掉那块框**)
     *
     * 主人 2026-10-06 的口径是"收窄成真双击, 超时的要重新计算": 假的那一支**不是"差一点就关掉"**,
     * 而是"它是新的一下" —— 调用方在那一支上把窗口重新起算 ([OverlayService.noteBlankTap]), 所以
     * 点一下、过一会儿再点一下**永远不会**把框关掉, 而超时那一下之后紧跟的半秒内再点一下就关
     *
     * 与球上那个双击判据 ([kind]) 分开写, 是因为它们答的不是同一个问题: 那一个是"球上这一下算哪种
     * 手势", 这一条是"框外这一下是不是第二下"
     */
    fun blankDouble(now: Long, lastAt: Long): Boolean =
        lastAt != 0L && now - lastAt <= BallMinutes.BOX_DOUBLE_TAP_MS
}

/**
 * 闲置那一笔账该不该重算, 以及为什么 —— **纯函数, 没有设备也能量** (见 BallTest)
 *
 * 这是 [OverlayService.refresh] 那 400 ms 一拍里唯一的一处记账判据, 而它成立的理由是 2026-10-06 主人
 * 报的那一条 ("ball 半隐藏功能又失效了"): 原来那一处写的是
 *
 * ```kotlin
 * if (word != null || capturing) { if (prevWord != word) reset("state change") }
 * else { if (prevWord != null) reset("voice off") }
 * ```
 *
 * 而 `prevWord` 只在"字换了"那一下被赋值 —— 于是一旦出现过任何一个状态词 (正在听 / 正在想 / 正在念),
 * `prevWord` 就**再也不落**, 每一拍都进"voice off"那一支把 `lastActiveAt` 推到当下, `idleDue` 永远
 * 为假。真机上量到的指纹是 13.7 h 里 `peeked=true` 只出现过 14 次, 而 `voice off` 与 `waiting` 两行
 * 互相接替了 8000 多次 (见 docs/floating-input.md)
 *
 * 现在两处都判**上一拍**那个字 ([previous]): "字刚落下"与"字刚换"各只记一次账, 记完就过去
 */
internal object BallIdle {

    /** 状态词换了: 有状态那一档开始 / 换了一个字 */
    const val STATE = "state change"

    /** 状态词落下 (回到空闲那一档): 那一下本身算一次活动, 5 s 从这里起算 */
    const val VOICE_OFF = "voice off"

    /**
     * 这一拍要记哪一笔账 (null = 不记, 计时照走)
     *
     * [previous] 必须是**上一拍**那个字, 不是"上一次变化时的字" —— 那一个字的区别就是这条判据的全部
     */
    fun reset(next: BallWord?, capturing: Boolean, previous: BallWord?): String? = when {
        next != null || capturing -> if (next != previous) STATE else null
        previous != null -> VOICE_OFF
        else -> null
    }
}

/**
 * 球现在该收边还是该待着, 以及**为什么**
 *
 * 这是那个 400 ms 一拍里唯一的一处判断 (见 `OverlayService.refresh` 与它调用的 `waitNow`): 原来
 * "要不要收边"散在两处 —— `peek()` 里三条闸、`refresh()` 里再多两条 —— 于是每加一条就多一个只在一处
 * 生效的闸, 而 2026-10-06 主人报的两条 (键盘输入时它居然算空闲, 以及"说话"点两次之后它不再收边)
 * 都是这么来的
 *
 * 六条闸各自的理由 (顺序就是报出来的顺序, 排查时先看那一个):
 *
 * | 闸 | 什么时候算 | 为什么不算空闲 |
 * | :-- | :-- | :-- |
 * | `held` | 手指正按着 / 拖着 | 人在用它 |
 * | `menu` | 菜单开着 | 那一眼正在选, 收起去就是"点了个空的" |
 * | `listening` | 语音链正在跑 (`VoiceState.capturing`) | 主人正在说话, 那正是它最该在场的时候 |
 * | `keyboard` | 输入框有焦点 / 键盘在屏上 | **键盘输入不属于空闲** (主人 2026-10-06 原话) |
 * | `channel` | 输入通道那块框在窗口上 | 回答会自己把框张出来 (那时它**不抢焦点**, 键盘没起) —— 少了这一条, 球会在回答出现的同时半隐溜走, 而框跟着球走 |
 *
 * 这几条都**不记活动那一笔账**: 计时由 `OverlayService` 按"这一档开始 / 结束"去重写 (见
 * `OverlayService.resetIdleWait`), 因为它知道"上一拍是怎么判的"。这里只回答"现在这一档对吗"
 */
internal object BallRest {

    /**
     * 两拍之间过多久才真的收边
     *
     * 收边的判据是"闲置超过 [BallMinutes.PEEK_IDLE_MS]"(严格大于), 而这一拍是 [OverlayService.TICK_MS]
     * 一次 —— 两者相加才是"点完之后最早什么时候看见它收起来"。那个数不进任何提示, 只用来写注释与
     * 解释"为什么 5.0 s 时还看得见"
     */
    const val TICK_PAD_MS = 400L

    /** 球现在在干嘛 (`waiting` / `asleep` / `peeking` 三选一) */
    fun wait(
        phase: BallPhase,
        peeked: Boolean,
        dragging: Boolean,
        menuOpen: Boolean,
        listening: Boolean,
        keyboard: Boolean,
        channelOpen: Boolean,
        idleDue: Boolean,
    ): BallWait = when {
        peeked -> BallWait(peek = false, reason = "asleep")
        dragging -> BallWait(peek = false, reason = "held")
        menuOpen -> BallWait(peek = false, reason = "menu")
        listening -> BallWait(peek = false, reason = "listening")
        keyboard -> BallWait(peek = false, reason = "keyboard")
        channelOpen -> BallWait(peek = false, reason = "channel")
        idleDue -> BallWait(peek = true, reason = "peeking")
        // 剩下的只有"全露着但没在听"那一档 ([BallPhase.SUMMONED]) 与还没到点的 [BallPhase.RESTED]:
        // 后者已经在收着那一支里被 `peeked` 接住了, 所以走到这里一律是"还等着"
        else -> BallWait(peek = false, reason = "waiting")
    }

    /** 这一档是不是"人正在用它": 是的话调用方不去动那笔空闲账 —— 账该从它**结束**那一刻起算 */
    fun isHeld(wait: BallWait): Boolean =
        wait.reason == "held" || wait.reason == "menu" || wait.reason == "listening" ||
            wait.reason == "keyboard" || wait.reason == "channel"
}

/** [BallRest.wait] 的答案: 收不收边, 以及这一拍为什么这么判 */
internal data class BallWait(val peek: Boolean, val reason: String)

/**
 * 设置页那个开关该画成什么 —— **纯函数, 没有设备也能量** (见 BallSwitchTest)
 *
 * 那一个"浮标一直在"的开关有两个来源, 而它们会分家:
 *
 * - [remembered] 是**存盘的记号** (`ball-on`): 它管的是"下一次应用起来要不要把球放出来"
 * - [showing] 是**球此刻真的在不在屏幕上** (`OverlayState.showing`): 菜单里、通知栏上、或者服务
 *   自己那边都能把球收掉, 而那几条路都不会回来改设置页里那一份记忆值
 *
 * 只认 [remembered] 就是 2026-10-06 报上来的那个样子: 从菜单里把球关掉, 屏幕上球没了, 而这个开关
 * 照旧画着"开" —— 人看到的是"两个入口各说各话"。所以判据是"**球真的在, 或者它记着要在**": 多认
 * 前者的那一半, 少认的那一半会在下一次 `ensure()` 里补上 (它照着 [remembered] 放球)
 */
internal object BallSwitch {
    fun onFor(showing: Boolean, remembered: Boolean): Boolean = showing || remembered
}
