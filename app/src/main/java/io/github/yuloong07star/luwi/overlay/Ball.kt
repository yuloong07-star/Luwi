package io.github.yuloong07star.luwi.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Bitmap
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import io.github.yuloong07star.luwi.R
import kotlin.math.abs

/**
 * 球上那几个字的四种可能
 *
 * 前三个都是「正在 X」三个字 (主人 2026-10-05 点过名: 状态词就写「正在听」这一档), 12 sp 下正好画得
 * 进 48 dp 的球 —— 这是"状态提示"落在最小面积上的办法 (颜色只是它的第二种说法, 不是唯一的那种:
 * 颜色认不出来的人也要读得懂)
 *
 * [FAILED] 是 2026-10-08 加的那一档, 只有**两个字**: 它说的不是"正在做什么", 而是"刚才那一轮没成"
 * (判据与寿命见 [BallPhaseFile] 与 `OverlayState.failedAckAt`)。所以它挪出「正在 X」那一套句式 ——
 * 硬凑成四个字反而看不清
 */
internal enum class BallWord {
    SPEAKING,
    THINKING,
    LISTENING,
    FAILED,
}

/** 那三个字怎么说: 与设置页、菜单、通知栏说的是同一份文案 */
internal fun BallWord.label(context: Context): String = when (this) {
    BallWord.SPEAKING -> context.getString(R.string.ball_state_speaking)
    BallWord.THINKING -> context.getString(R.string.ball_state_thinking)
    BallWord.LISTENING -> context.getString(R.string.ball_state_listening)
    BallWord.FAILED -> context.getString(R.string.ball_state_failed)
}

/** 每种状态描边的颜色: 只用来描一圈边, 认字那一半永远在 */
internal fun BallWord.ring(): Int = when (this) {
    BallWord.SPEAKING -> RING_SPEAKING
    BallWord.THINKING -> RING_THINKING
    BallWord.LISTENING -> RING_LISTENING
    BallWord.FAILED -> RING_FAILED
}

private val RING_SPEAKING = 0xFFB388FF.toInt()
private val RING_THINKING = 0xFFFFC24D.toInt()
private val RING_LISTENING = 0xFF6EF3B0.toInt()
private val RING_FAILED = 0xFFFF5252.toInt()

/**
 * 球现在该说哪三个字
 *
 * 优先级是 **在念 > 在想 / 在听 (谁最近变成真的谁上) > 失败** (2026-10-09 主人两轮定下来的口径):
 *
 * | 两条同时成立 | 谁说话 | 主人给的理由 |
 * | :-- | :-- | :-- |
 * | 在念 + 任意 | **在念** | "正在想状态的显示不能遮挡正在说, 以方便打断说话" —— 喇叭在出声的时候 |
 * | 在想 + 在听 | **时间戳晚的那个** | "在想着点一下球就写正在听": 点一下开麦是刚发生的事, 球上要看得见; 而开麦之后新一轮又起来, 想是刚发生的事, 球上回到想 |
 * | 失败 + 任意 | 让位 | 那三个字说的是"此刻正在发生", 失败说的是"刚刚"; 让位不等于认过 |
 *
 * **"谁最近变成真的谁上"靠 [thinkingAt] / [listeningAt] 两个时刻** (0 = 现在不是真的): 那一档不成立
 * 时调用方把它清零, 于是"又变成真的"那一刻会盖上一个新的数。两个数同一拍一起变 (或就是同一个数)
 * 时给**想** —— 它在跑, 是要紧的那一件
 *
 * **它只改"显示", 不动任务**: 谁被谁盖住都不取消任何东西 (播报照念、轮次照跑、麦克风照开),
 * 跟它走的只有**手动打断**那一下 —— 球上写着「正在想」时双击才是打断 (见 [BallTaps.kind])
 *
 * **动作那一边仍然是"念 > 想 > 听"** ([BallTouch.act] 里 `speaking` 排第一): 显示上写着「正在想」
 * 而喇叭同时也在念时, 单点那一下仍然是"别念了" —— 那两件事各说各的, 见 [BallTouch.act]
 *
 * **视频模式那一档顶格** (主人 2026-10-10: "把视频状态下正在听这三个字在 ball 上优先及最高, 不能被
 * 其它状态打断"): `resident` 为真时那一格永远写「正在听」, 压过正在想与失败 —— 只有 [BallWord.SPEAKING]
 * 能盖它一下 (那三个字亮着时点球是"别念了")。**它跟的是常驻那个记号, 不是 `listening`**:
 * "说"的时候麦克风是真关掉的 (见 `WakeWordService`), 跟着 `capturing` 走的话那几百毫秒字就会掉
 *
 * 纯函数, 没有设备也能量 (见 BallTest)
 */
internal object BallStatus {
    fun wordFor(
        speaking: Boolean,
        thinking: Boolean,
        listening: Boolean,
        failed: Boolean,
        thinkingAt: Long = 0L,
        listeningAt: Long = 0L,
        resident: Boolean = false,
    ): BallWord? = when {
        // 念回答最上 (2026-10-09 主人: "正在想状态的显示不能遮挡正在说"): 喇叭在出声的时候那件事
        // 最要紧, 而"别念了"那一下就是点球 ([BallTouch.act] 里 `speaking` 也排第一)
        speaking -> BallWord.SPEAKING
        // 视频模式 (常驻语音那个记号开着, 且不在省电模式): 那一格顶格, 只让"正在说"盖一下
        resident -> BallWord.LISTENING
        // 想与听之间看谁最近变成真的 (见上面那张表): 同一拍一起变时给想
        thinking && listening ->
            if (listeningAt > thinkingAt) BallWord.LISTENING else BallWord.THINKING
        thinking -> BallWord.THINKING
        listening -> BallWord.LISTENING
        failed -> BallWord.FAILED
        else -> null
    }
}

/**
 * 哪几种结束原因在球上写成「失败」 (2026-10-08 主人定的口径: 三种都算)
 *
 * dsh 那张表 (`TurnEndReasonMap`) 一共 7 种, 挑得出这三种是有话说的:
 *
 * - `error` —— 真的失败了 (模型 / 网络 / 工具), 带一份结构化理由 (`Ended.why`)
 * - `max-tokens` —— 回答撞上输出上限被截断, 主人拿到的是半句话
 * - `blocked` —— 这一轮被闸住, 一步都没跑起来 (`agent/pre-step` 被拒)
 *
 * 剩下那几种都**不该**报成失败: `completed` 是正常结束; `aborted` 是主人自己按的停;
 * `interrupted` / `forked` 根本不是实时事件 (它们是事后补进日志的收尾, 见 dsh 的 `session/repair`),
 * 这条链永远收不到 —— 所以这里也不该给它们留位置
 *
 * 纯函数, 没有设备也能量 (见 BallTest)
 */
internal object BallFailure {
    val KINDS = setOf("error", "blocked", "max-tokens")

    /** 这条结束原因要不要点亮球上那两个字 */
    fun counts(kind: String?): Boolean = kind != null && kind in KINDS

    /**
     * 这一拍该不该写「失败」
     *
     * `at` 与 `ackAt` **都是文件或那一笔账里的时间戳, 不是本机时钟**: 主人点一下球就把认过的那一条
     * 的时间记进 `ackAt`, 于是判据是"文件里那条比认过的这条新"。拿本机时间当水位线的话, 两侧时钟
     * 只要差一点, 下一条真的失败就会被当成"早就认过了"吞掉
     */
    fun shows(kind: String?, at: Long, ackAt: Long): Boolean = counts(kind) && at > ackAt
}

/**
 * 位置与钳制那几个算式
 *
 * 三个约定照着开源那一份 (Petterpx/FloatingX, Apache-2.0, 1.5k star 的悬浮窗组件 —— 它把"边缘吸附
 * + 半隐藏 + 回弹"这套做成了公开 API, 设备上那套系统助手没露出来时以它为准):
 *
 * - **半隐是比例, 不是像素**: 它叫 `FxHalfHide`, 按窗口宽度算 (demo 用 `0.3`) —— 见 [HALF_HIDE]
 * - **吸附取"剩余距离最近"的那条边**, 不是"球心过了中线就算换边" —— 见 [snapX]
 * - **拖动过程中允许暂时越界, 松手回弹** (`rebound`) —— 硬钳在边上会让人觉得"手被挡了一下", 见 [dragX]
 *
 * 抽出来是为了能单测: 吸附、越界、键盘、侧边半隐那四种边界没有设备也可以量 (见 BallGeometryTest),
 * 而它们恰恰是最容易在真机上"看着差不多"却差一个球的地方
 */
internal object BallGeometry {

    /** 贴着哪一边: 存盘记的是这个, 不是 x —— 换分辨率之后 x 要按新宽度重算, 边与高度是不变的意图 */
    const val EDGE_LEFT = "left"
    const val EDGE_RIGHT = "right"

    /**
     * 半隐比例: 贴边时藏起来的那一份球宽 (0 = 全露, 1 = 全藏)
     *
     * **主人 2026-10-05 点过名: "半隐藏"就是球的一半在屏幕外** —— 所以是 0.5, 不是参考那份 demo 的
     * 0.3。48 dp 的球藏掉 24 dp, 而球体本身画在窗口中间那 45 dp 上 (见 [BallView.BALL_DRAW_DP]),
     * 于是**看得见的那颗球正好一半留在屏外**: (24 - 1.5) / 45 = 0.5
     */
    const val HALF_HIDE = 0.5f

    /** 松手之后贴哪一边: 比两边的剩余距离, 近的那条 (参考那套的 `nearestEdge`) */
    fun snapX(x: Int, screenWidth: Int, ball: Int): Int {
        val limit = (screenWidth - ball).coerceAtLeast(0)
        return if (x <= limit - x) 0 else limit
    }

    /** 存盘那条边 + 这一屏的宽度 = 这一屏该有的 x */
    fun xForEdge(edge: String, screenWidth: Int, ball: Int): Int =
        if (edge == EDGE_LEFT) 0 else (screenWidth - ball).coerceAtLeast(0)

    /** 吸附之后落到了哪一边 (写存盘用) */
    fun edgeFor(x: Int, screenWidth: Int, ball: Int): String =
        if (snapX(x, screenWidth, ball) == 0) EDGE_LEFT else EDGE_RIGHT

    /** 纵向不许出屏 */
    fun clampY(y: Int, screenHeight: Int, ball: Int): Int =
        y.coerceIn(0, (screenHeight - ball).coerceAtLeast(0))

    /**
     * 键盘起来时把球抬到键盘上沿之上
     *
     * [imeBottom] 为 0 就是"没有键盘 / 这一屏拿不到那个 inset", 那时原样返回 —— 拿不到 inset 时
     * 与其猜一个高度, 不如什么都不动 (猜错的表现是球跳到半空中)
     */
    fun clampAboveIme(y: Int, imeBottom: Int, screenHeight: Int, ball: Int): Int {
        if (imeBottom <= 0) return y
        val highest = (screenHeight - imeBottom - ball).coerceAtLeast(0)
        return if (y > highest) highest else y
    }

    /** 半隐时球该在的 x: 贴右边就往右挪出去, 贴左边就往左挪 */
    fun peekX(restX: Int, edge: String, ball: Int, fraction: Float = HALF_HIDE): Int {
        val hidden = (ball * fraction).toInt()
        return if (edge == EDGE_LEFT) restX - hidden else restX + hidden
    }

    /**
     * 拖动中的位置: 允许推出去到半隐的位置, 松手再回弹 ([snapX] + 半隐那一步会把它收回去)
     *
     * 上下不分那一档 (贴边只贴左右), 所以 y 仍然按 [clampY] 硬钳
     */
    fun dragX(x: Int, screenWidth: Int, ball: Int, fraction: Float = HALF_HIDE): Int {
        val hidden = (ball * fraction).toInt()
        return x.coerceIn(-hidden, (screenWidth - ball + hidden).coerceAtLeast(0))
    }
}

/**
 * 球停在哪: 「贴哪边 + y」两个数, 只有拖完那一下才写
 *
 * 那一个 `ball-on` 是**存盘的开关** (「浮标一直在」), 它与"服务在不在跑"是两件事: 开关说的是
 * "下一次应用起来要不要把球放出来", 服务说的是"它现在在不在" (见 OverlayService)
 */
internal object BallSpot {

    /** 与设置页那些偏好同一个文件: 这一批的键都在这里, 换名字要两处一起换 */
    private const val STORE = "luwi"
    private const val KEY_ON = "ball-on"
    private const val KEY_EDGE = "ball-edge"
    private const val KEY_X = "ball-x"
    private const val KEY_Y = "ball-y"

    /**
     * 手势灵敏度那一档的键 (2026-10-08 主人: "增加 ball 各操作的防误触" + "做成设置页一档")
     *
     * 存的是 [BallFeel] 的枚举名, 认不出来的写法一律落回 [BallFeel.GUARD] —— 那一档是**更严**
     * 的那个, 于是"存坏了"的结果是"更不容易误触", 不是"球变得一碰就开"
     */
    private const val KEY_FEEL = "ball-feel"

    fun on(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** 手势灵敏度: 缺省 [BallFeel.GUARD] (防误触) */
    fun feel(context: Context): BallFeel =
        BallFeel.of(prefs(context).getString(KEY_FEEL, null))

    fun setFeel(context: Context, feel: BallFeel) {
        prefs(context).edit().putString(KEY_FEEL, feel.name).apply()
    }

    // **那个"手势提示只给一次"的记号已经删掉了** (2026-10-05 主人: 关于球的所有操作都不要有提示):
    // 球一出来就弹一句 Toast 正是主人点名不要的那一种, 于是 `ball-hint` 这个键与读写它的两个方法
    // 一起没了 —— 手势那几句话现在只在长按菜单里 (它自己写着「键盘输入 / 识屏模式 / 关掉浮标」)

    /** 存过的那一处, 没存过就是 null (调用方按"贴右、中下"那个缺省摆) */
    fun read(context: Context): Pair<String, Int>? {
        val store = prefs(context)
        if (!store.contains(KEY_Y)) return null
        val edge = store.getString(KEY_EDGE, BallGeometry.EDGE_RIGHT) ?: BallGeometry.EDGE_RIGHT
        return edge to store.getInt(KEY_Y, 0)
    }

    /** 拖完那一下写一次; [x] 与 [edge] 一起存是为了对账 (真正算位置的是边 + y) */
    fun write(context: Context, edge: String, x: Int, y: Int) {
        prefs(context).edit()
            .putString(KEY_EDGE, edge)
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}

/**
 * 那颗球: 一个不吃焦的小圆点, 样子照着那套系统助手那颗球做
 *
 * 视觉取的是"一颗蓝紫渐变的柔光球 + 白色标" (那种一眼认得出是助手的圆形按钮), 而**动态**是照着
 * 设备自己那套词表来的 —— `floating_ball_idle_to_edge` (闲置贴边半藏)、闲置那一档很淡的不透明度、
 * 单击 / 长按 / 横向拖三种手势各有分工。落在代码里是五件事:
 *
 * 1. **点一下 = 说话, 长按 = 菜单, 拖 = 挪地方**。三者靠触摸斜率分开 (动了超过 [ViewConfiguration]
 *    那个阈值就是拖, 按住超过长按时间是菜单), 所以同一个 48 dp 的球什么都干得了
 * 2. **按下有反馈**: 按下去缩到 0.94, 拖动时放大到 1.06 —— 手指按住的那一下要有回应, 不是一块
 *    点不动的图
 * 3. **它不获焦** (窗口那一侧给的 `FLAG_NOT_FOCUSABLE`), 所以手指落在球外面照旧给底下的应用 ——
 *    球只吃掉自己那 48 dp
 * 4. 空闲时画 Luwi 那个标记, 有状态时画三个字 (正在听 / 正在想 / 正在念), 换词是淡入淡出 ——
 *    状态变化是这颗球唯一"说话"的机会, 直接跳字会让人以为是闪了一下
 * 5. **侧边半藏由窗口那一侧做** (见 OverlayService 的 `peek` / `unpeek`): 球自己的位置不是一个
 *    view 属性, 挪窗只能改窗口坐标, 所以这里只把"按下了"这件事告诉它
 * 6. **念与听那两档描边会呼吸** (2026-10-09 主人: "说话时加个边框闪烁, 要像 vivo 蓝心小v那样"):
 *    环色在满亮与 [BREATH_MIN] 之间 1.2 s 一个来回, 而字色不跟着闪 (12 sp 那三个字要一直读得清)。
 *    往球外走的那一圈圈涟漪不在这里 —— 它比球大, 画在这块 48 dp 的窗里会被裁掉, 那块窗在
 *    [BallRipple] / `OverlayService` 那一侧
 */
internal class BallView(context: Context, private val listener: Listener) : FrameLayout(context) {

    internal interface Listener {
        /** 手指刚落在球上: 半藏着的球要立刻滑出来 (晚一步就变成"拖不动") */
        fun onPressStart()

        /** 点一下: 说话 */
        fun onTap()

        /** 长按: 菜单 */
        fun onLongPress()

        /** 拖动中: [dx] / [dy] 是相对按下那一点的位移 (原始坐标), 窗口位置由调用方算 */
        fun onDragTo(dx: Float, dy: Float)

        /** 松手: 该吸附了 */
        fun onDrop()
    }

    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
        // 球底下是什么说不准 (别人的界面、白底、照片), 所以字自己带一点影子
        setShadowLayer(3f, 0f, 1f, Color.argb(170, 0, 0, 0))
    }

    /**
     * 空闲时球上画的是 Luwi 自己那个标记 (自适应图标的前景, **无背景**的那一份), 不写任何名字
     *
     * 它那块 view 比球体本身还大 (见 [GLYPH_DP]): 那份矢量里标记只占画布 0.46, 照抄进这颗球
     * 只剩一小圈
     */
    private val glyph = ImageView(context).apply {
        setImageResource(R.drawable.ic_launcher_foreground)
        // 那个标记原本是 #01B4FF (一圈水花里一只鲸尾), 在蓝紫球上看不清 —— 染成白的是同一份形状, 不是另一张图
        imageTintList = ColorStateList.valueOf(TEXT)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /**
     * 蓝紫渐变那颗球: 底色是渐变, 描边跟着状态换
     *
     * **球体住在窗口里居中那一层上** ([BALL_DRAW_DP] = 45 dp, 窗口仍是 48 dp): 拖动时放大到 1.06 倍
     * (47.7 dp) 正好还在窗口里, 而窗口是**照着 view 的测量尺寸**开的 —— 球画满 48 dp 的话, 放大
     * 之后四边会超出窗口被裁掉一圈 (主人 2026-10-05 报的就是这个: "球形四边会超出边框, 四边被削掉
     * 一部分")。放大与圆形裁剪都落在这层上, 所以图标与字也一起被裁进这颗球里
     */
    private val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.LINEAR_GRADIENT
        orientation = GradientDrawable.Orientation.TL_BR
        colors = intArrayOf(FILL_FROM, FILL_TO)
        setStroke(dp(STROKE_DP), NEUTRAL)
    }

    /** 球体那一层: 45 dp 见方、居窗口正中, 放大缩的是它 */
    private val core = FrameLayout(context).apply {
        background = circle
        clipToOutline = true
        // 标记那一层居中、比球还大一圈, 多出来的透明边由上面那句圆裁剪吃掉 —— 只有中间那块墨迹
        // 落进球里, 于是"标记占球多大"就是 [GLYPH_INK_RATIO] 这个数
        val side = dp(GLYPH_DP)
        addView(glyph, LayoutParams(side, side, Gravity.CENTER))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private var fill: ValueAnimator? = null

    /**
     * 描边那一口呼吸 (只在"正在说 / 正在听"两档跑)
     *
     * 与 [fill] (按下 / 拖动那两档缩放) 分开: 那条动的是球体那一层, 这条只改描边的透明度, 而两者
     * 可以同时跑 (拖着球说话)
     */
    private var breathe: ValueAnimator? = null

    /** 这一口呼吸的底色 (环色): 会话轮换换了色就要拿新的重开一遍 */
    private var breatheBase: Int = NEUTRAL

    /**
     * 外观插件那一档 (协议 10.1 / 方案 3.2): 挑中一个主题插件时, 球体那一层换成它给的图
     *
     * **读不到一律回内置** —— 这与"删掉插件回退内置画法"是同一件事。挑的是哪一个由
     * [BallTheme.currentId] 说了算 (设置页里那一行), 心跳每一拍顺手读一次那个小文件: 主人换了外观
     * 当场就该看得见, 不用等状态变化
     */
    private var themeKey: String = ""
    private var faceFrame: Bitmap? = null
    private var faceFrames: List<Bitmap> = emptyList()
    private var faceIndex = 0
    private var faceIntervalMs = 0
    private val faceAdvance = Runnable { advanceFace() }

    /** 平台自己那一档门槛: 标准灵敏度用它, 防误触那一档拿它当垫底 (见 [BallFeel]) */
    private val platformSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * 平台那个长按门槛 (400~500 ms 那一档): 两档灵敏度都从它派生 (见 [BallFeel.pressMs])
     *
     * 取的是那个静态方法而不是 `ViewConfiguration.longPressTimeout` —— 后者在当前 SDK 里是隐藏成员
     */
    private val platformPress = ViewConfiguration.getLongPressTimeout().toLong()

    /**
     * "这一下算拖动"的门槛: **手指按下那一刻按当前灵敏度算一次** (见 [slopFor])
     *
     * 平台自己的 `scaledTouchSlop` 只有 8 dp 上下, 而球是常驻的 —— 手按上去抖两下就过线, 于是本该是
     * 单击的那一下变成"拖了一下没动地方"(拖动不出单击动作)。12 dp 那一档是 2026-10-08 防误触加的
     * (见 BallMinutes 里那一段说明), 而它是**灵敏度设置页那一档**管着的一个数
     *
     * 按下那一刻读一次就够: 在一次手势中间改设置页是另一只手在做的事, 不必让这一下中途变卦
     */
    private var slop = platformSlop.toFloat()

    /** 这一档的拖动门槛: 0 = 就用平台那个数, 否则取两者里大的那个 */
    private fun slopFor(feel: BallFeel): Float =
        if (feel.slopDp <= 0) platformSlop.toFloat() else maxOf(platformSlop, dp(feel.slopDp)).toFloat()

    /**
     * 这一档的长按门槛
     *
     * **2026-10-10 起两档是同一个式子** (见 [BallFeel.pressMs]): 标准档就是平台那个数, 防误触档在
     * `max(平台值, 500)` 上加 150 ms —— 典型设备上仍是原来那个 650 ms, 而两档之间的差不再是写死的
     */
    private fun pressFor(feel: BallFeel): Long = feel.pressMs(platformPress)

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longFired = false

    private val press = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onLongPress()
    }

    /** 现在画的是哪三个字, null = 空闲 */
    var word: BallWord? = null
        private set

    /**
     * 上一拍落在描边与那几个字上的颜色
     *
     * 会话轮换时字还是「正在想」而环色换了, 光比 [word] 会把这三十多个字色变化全吃掉 —— 它是 [show]
     * 里那两句"要不要重画"的第二个判据
     */
    private var lastColor: Int = NEUTRAL

    init {
        // 那 3 dp 的余量是留给拖动放大的 (见 [core])
        addView(
            core,
            LayoutParams(dp(BALL_DRAW_DP), dp(BALL_DRAW_DP), Gravity.CENTER),
        )
        show(null, animate = false)
    }

    /**
     * 窗口那一侧给的是 WRAP_CONTENT, 所以尺寸由这里说了算: 一个正方形
     *
     * **必须走 `super.onMeasure`**: 球体 (含图标与字) 是里面居中那一层, 只 `setMeasuredDimension`
     * 的话孩子根本不会被量 —— 表现就是"球在视觉上看不见了" (2026-10-05 主人报的, 而且那次之前的
     * 图标与状态词其实也一直没被画出来, 只是圆形底色挂在 view 自己身上, 所以看着像没问题)
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(BALL_SIZE_DP)
        val exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
        setMeasuredDimension(size, size)
    }

    /**
     * 换状态: 空闲画那个标, 有状态画三个字, 描边一起换
     *
     * 同一个值时什么都不做 (心跳 400 ms 一次, 不必每次都重画), 而换字那一下是淡入淡出:
     * 先淡到 0 再换字再淡回来, 总共 [FADE_MS] —— 比"啪一下换个字"稳
     *
     * [ring] 是**这一档指定的环色** (现在只有"正在想"用得上: 颜色按会话固定, 见 `BallPhaseFile`),
     * 传 null 就用这个字自己的颜色 —— 两个会话轮流在想时那个色要跟着换, 所以它得从外面进来。
     *
     * **环色与那几个字的颜色是同一个色** (主人 2026-10-09: "正在想的字体颜色换成对应的颜色"): 原来
     * 只有描边跟着会话换, 字恒白 —— 两个会话同时在想时要凑近看那一圈才分得出是哪一场。所以 [ring]
     * 进来之后两边一起上色, 而**换色但字没换那一下也要重画** (会话轮换时 `next` 是同一个 THINKING,
     * 光看"字变没变"会把这次换色吃掉)
     */
    fun show(next: BallWord?, animate: Boolean = true, ring: Int? = null) {
        val changed = next != word
        val color = ring ?: next?.ring() ?: NEUTRAL
        val recolored = color != lastColor
        // 外观插件那一档: 挑中哪一个的那一行文件改了, 这一拍就重画 (不必等状态变化)
        val themeNow = runCatching { BallTheme.currentId(context) }.getOrDefault("")
        val rethemed = themeNow != themeKey
        themeKey = themeNow
        word = next
        lastColor = color
        circle.setStroke(dp(STROKE_DP), color)
        // 念与听那两档: 描边跟着呼吸 (见类头第 6 条)
        breathe(color, next == BallWord.SPEAKING || next == BallWord.LISTENING)
        val text = next?.label(context).orEmpty()
        val size = if (next == null) LABEL_SP else WORD_SP
        if (!changed && !recolored && !rethemed && label.text.isNotEmpty()) return
        // 字换了才值得闪一下; 只换色 (会话轮换) 直接落色, 不该看着像闪了一下
        if (!changed || !animate) {
            label.animate().cancel()
            apply(next, text, size, color)
            return
        }
        label.animate().cancel()
        label.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction {
            apply(next, text, size, color)
            label.animate().alpha(1f).setDuration(FADE_MS / 2).start()
        }.start()
    }

    /**
     * 那一口呼吸开不开: [on] 为假时**立刻落回满亮的实心描边** (不留在半亮上)
     *
     * 同一个底色已经在跑就什么都不做 —— 心跳 400 ms 一拍, 每一拍都重开一次动画会看着像卡住。
     * 底色换了 (会话轮换) 才拿新色重开, 于是"字没换、颜色换了"那一下在呼吸里也跟得上
     */
    private fun breathe(color: Int, on: Boolean) {
        if (!on) {
            breathe?.cancel()
            breathe = null
            breatheBase = color
            circle.setStroke(dp(STROKE_DP), color)
            return
        }
        if (breathe?.isRunning == true && breatheBase == color) return
        breathe?.cancel()
        breatheBase = color
        breathe = ValueAnimator.ofFloat(BREATH_MIN, 1f).setDuration(BREATH_MS).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                circle.setStroke(dp(STROKE_DP), alphaOf(color, animator.animatedValue as Float))
            }
            start()
        }
    }

    /** 把 [color] 的透明度按 [factor] 缩放 (0..1): 呼吸只动亮度, 不动色相 */
    private fun alphaOf(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    /**
     * 摘下来就把两条动画放掉
     *
     * 球窗会被反复摘掉再挂回来 (关掉浮标、服务重建), 留着一条跑在没人看的 view 上的动画是纯浪费
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fill?.cancel()
        fill = null
        breathe?.cancel()
        breathe = null
        stopFace()
    }

    /**
     * 那两个字与应用标互斥: 有状态就把标收起来, 闲着就把标放回来
     *
     * [color] 与描边那一个环色同源 (见 [show]) —— 空闲时它是那颗标的白 (文字不可见, 这个色用不上)
     */
    private fun apply(next: BallWord?, text: String, size: Float, color: Int) {
        val themed = applyTheme(next)
        glyph.visibility = if (next == null && !themed) VISIBLE else GONE
        label.visibility = if (next != null && (!themed || !hideBuiltinText)) VISIBLE else GONE
        label.text = text
        label.setTextColor(color)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        label.alpha = 1f
    }

    /** 主题那一份说不说"把内置那三个字也藏起来" (只在有主题时才有意义) */
    private var hideBuiltinText = false

    /**
     * 球体那一层换成插件给的那张图 (方案 3.1 的 `theme/ball.json`)
     *
     * @return true 表示这一档生效了 (调用方就不用再画标记 / 字)
     */
    private fun applyTheme(next: BallWord?): Boolean {
        val state = next?.name?.lowercase() ?: "idle"
        val face = runCatching { BallTheme.face(context, state) }.getOrNull()
        stopFace()
        if (face == null) {
            hideBuiltinText = false
            core.background = circle
            return false
        }
        hideBuiltinText = face.hideBuiltinText
        val maxPx = dp(BALL_DRAW_DP).coerceAtLeast(96)
        val frames = face.frames?.let { spec ->
            BallTheme.framesOf(context, state, spec)?.mapNotNull { file -> BallTheme.decode(file, maxPx) }
        }.orEmpty()
        if (frames.size >= 2) {
            faceFrames = frames
            faceIndex = 0
            faceIntervalMs = face.frames?.intervalMs ?: 100
            core.background = android.graphics.drawable.BitmapDrawable(resources, frames[0])
            postDelayed(faceAdvance, faceIntervalMs.toLong())
            return true
        }
        val bitmap = BallTheme.decode(face.file, maxPx)
        if (bitmap == null) {
            hideBuiltinText = false
            core.background = circle
            return false
        }
        faceFrame = bitmap
        core.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        return true
    }

    /** 帧序列那一拍: 换下一张, 排下一拍 */
    private fun advanceFace() {
        if (faceFrames.size < 2) return
        faceIndex = (faceIndex + 1) % faceFrames.size
        core.background = android.graphics.drawable.BitmapDrawable(resources, faceFrames[faceIndex])
        postDelayed(faceAdvance, faceIntervalMs.toLong())
    }

    private fun stopFace() {
        removeCallbacks(faceAdvance)
        faceFrames = emptyList()
        faceFrame = null
        faceIndex = 0
    }

    /** 按下 / 拖动那两档尺寸: 手指按住要有回应, 拖起来要像"拿起来了" (缩的是球体那一层) */
    private fun scaleTo(target: Float) {
        fill?.cancel()
        fill = ValueAnimator.ofFloat(core.scaleX, target).setDuration(SCALE_MS).apply {
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                core.scaleX = value
                core.scaleY = value
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                longFired = false
                // 灵敏度那一档在按下这一刻读一次 (见 [slop] / [pressFor]): 拖与长按两个门槛都跟着它走
                val feel = BallSpot.feel(context)
                slop = slopFor(feel)
                listener.onPressStart()
                scaleTo(PRESS_SCALE)
                // **按下这一瞬间就回一记轻触觉** (2026-10-10): 开麦那一下现在要等一个确认窗
                // (见 [BallCommit]), 而人对手感延迟的容忍度主要靠"按下去有没有立刻回应"决定 ——
                // 缩放动画之外再补这一记, 那 220 ms 的等待基本不会被察觉。它跟长按那一记用的是同一个
                // API, 跟着系统的"触摸反馈"开关走, 不需要权限
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                // 长按门槛从平台那个数派生 (见 [pressFor]): 那颗球一直在屏上, 慢一点的单击会被平台的
                // 判据吃掉并弹出菜单 (2026-10-08 防误触)
                postDelayed(press, pressFor(feel))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                // 一动就撤掉长按: 否则"按住再拖"到时间也会弹菜单
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(press)
                    scaleTo(DRAG_SCALE)
                }
                if (dragging) listener.onDragTo(dx, dy)
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(press)
                scaleTo(1f)
                if (dragging) {
                    dragging = false
                    listener.onDrop()
                } else if (!longFired) {
                    listener.onTap()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(press)
                scaleTo(1f)
                if (dragging) {
                    dragging = false
                    listener.onDrop()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** D16 定的窗口尺寸 (约 48 dp): 窗口与命中范围都是它, **装了图标也不许改** */
        const val BALL_SIZE_DP = 48

        /**
         * 球体本身画多大: 45 dp, 比窗口小 3 dp
         *
         * 拖动时整颗球放大到 1.06 倍 = 47.7 dp, 正好还在 48 dp 的窗口里 —— 画满窗口的话放大那四边
         * 会被窗口裁掉一圈
         */
        const val BALL_DRAW_DP = 45

        private const val LABEL_SP = 20f

        /**
         * 状态词那 12 sp
         *
         * 三个汉字按 1 em 宽算就是 36 dp, 48 dp 的球里还剩两边各 4 dp —— 再多一档 (13 sp) 就会
         * 顶到描边上, 而少一档又要在 vivo 那块 3 倍密度的屏上才看得出来偏小
         */
        private const val WORD_SP = 12f

        private const val STROKE_DP = 2

        /**
         * 球上那个标记的墨迹宽度占球体直径多少
         *
         * 启动器图标那份是 0.46 ([ICON_INK_RATIO]), 照抄到球上只剩小小一圈 —— 主人 2026-10-10
         * 换了标记之后点名"ball 的图案占比小, 放大一点", 两轮下来定的是 0.65 (先给 0.55, 主人
         * 看过实机截图之后要 0.65)。**只有球这一处动**, 桌面与通知栏那份还是 0.46 (主人换标记时的
         * 原话是"图案占比要和现在的一样")
         */
        private const val GLYPH_INK_RATIO = 0.65f

        /**
         * 那份矢量里标记自己占画布多少 (与 `tools/vector-ink.py` 那面 `ink-width` 旗标同一个数)
         *
         * 它不是外观参数而是**分母**: 标记只占画布 0.46, 想让它占球体 0.65, 承载它的那块 view
         * 就得比球还大 —— 多出来的透明边由 [BallView.core] 那圈圆裁掉
         */
        private const val ICON_INK_RATIO = 0.46f

        /** 承载标记那块 view 的边长 (dp): 见上面两个比例 */
        private val GLYPH_DP = (BALL_DRAW_DP * GLYPH_INK_RATIO / ICON_INK_RATIO).toInt()

        private const val PRESS_SCALE = 0.94f
        private const val DRAG_SCALE = 1.06f
        private const val SCALE_MS = 120L
        private const val FADE_MS = 240L

        /**
         * 描边那一口呼吸: 1.2 s 一个来回 (主人 2026-10-09 选的是"柔和呼吸", 不是快闪)
         *
         * 它与往外走的那一圈涟漪 ([BallPulse.RING_MS]) 是同一个节拍, 两件事看起来才像一件事
         */
        private const val BREATH_MS = 1200L

        /** 呼吸最暗的那一档: 见底但不熄 —— 描边在的时候那颗球的样子不能变 */
        private const val BREATH_MIN = 0.45f

        /** 蓝紫渐变: 那颗球的身份色, 不随状态变 —— 状态只动描边与那三个字 */
        private val FILL_FROM = 0xFF3D7BFF.toInt()
        private val FILL_TO = 0xFF8B5CFF.toInt()
        private val TEXT = 0xFFFFFFFF.toInt()
        private val NEUTRAL = 0x66FFFFFF
    }
}
