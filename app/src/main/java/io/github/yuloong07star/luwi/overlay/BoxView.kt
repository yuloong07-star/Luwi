package io.github.yuloong07star.luwi.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.yuloong07star.luwi.R

/**
 * 输入通道那几条尺寸, 全在这里 —— 主人 2026-10-05 给的是**像素**: "长度的像素为 650, 行数上限为
 * 5 行, 每行 12 字, 高度随行数增长而不是固定的"
 *
 * **行数上限 2026-10-06 由主人追加了两行: 5 → 7** (那一批的第三条), 其余口径一个字没改 —— 宽度还是
 * 650 px, 高度仍然随行数长, 到 7 行才停
 *
 * 为什么宽是按像素而不是 dp: 主人说的是像素, 而"一行多少字"这件事本身也是按这条宽度算的
 * (14 sp 的汉字一步约 42 px, 650 / 42 ≈ 15 字, 再加上左右内边距正好 12 字上下) —— 加了两行之后,
 * 一行多少字这个数与它无关 (那是宽度管的事)
 *
 * **2026-10-08 主人: "增加文框长度, 2 个词长度"** —— 从 650 加到 734 (+84 px, 正好是两个字宽),
 * 而**上限跟着来**: 横屏那一档屏幕短边只有 600 出头, 734 的框放进去会压在球上, 所以
 * [widthFor] 按屏宽的 62% 钳一道 (钳过之后照旧整块留在屏幕里, 见 [BoxSpot.x])
 */
internal object BallBox {

    /** 主人点名的数: 原 650 px, 2026-10-08 加两个字 → 734 px */
    const val WIDTH_PX = 734

    /** 一个字按 14 sp 在 3 倍密度下算出来的宽度 (42 px): 加宽那两个字就是它乘出来的 */
    const val CHAR_PX = 42

    /** 窄屏上的上限: 框不许超过屏宽的这个比例 (过了就会把球压住, 而球是唯一那个常驻的入口) */
    const val MAX_WIDTH_PERCENT = 62

    /**
     * 这一屏上面, 框该有多宽
     *
     * 一直是 [WIDTH_PX], 只在**窄屏** (横屏 / 分屏那一档) 上按 [MAX_WIDTH_PERCENT] 收窄 ——
     * 收窄之后里面的字会自己换行, 而框的高随行数长 ([MAX_LINES] 行封顶)
     */
    fun widthFor(screenWidth: Int): Int =
        minOf(WIDTH_PX, (screenWidth * MAX_WIDTH_PERCENT / 100).coerceAtLeast(1))

    /**
     * 行数上限: **7 行** (主人 2026-10-05 给的是 5, 2026-10-06 追加两行)
     *
     * 到了就不再长, 里面自己滚 (需求原话: "高度随行数增长而不是固定的")
     */
    const val MAX_LINES = 7

    /**
     * 回复那一块最多占几行, 再多也是里面滚
     *
     * **2026-10-06 主人追加两行: 3 → 5** (那一批的第三条) —— 输入框那个 [MAX_LINES] 同一天也加过
     * 两行, 两个数是各管各的: 一个是"人写的", 一个是"agent 回复的"
     */
    const val REPLY_LINES = 5

    /** 行距: 14 sp 的 1.35 倍, 七行加两个内边距正好是一块好按的框 */
    const val LINE_SPACING = 1.35f

    /** 外框的圆角与内边距 (像素) */
    const val CORNER_DP = 12
    const val PAD_H_DP = 10
    const val PAD_V_DP = 8

    /**
     * 输入框现在该占几行: **内容越长越高, 到 [MAX_LINES] 就停**
     *
     * 纯函数 (它只吃一个行数), 所以"高度随行数增长"这件事没有设备也能量 —— 这条需求的判据就是它
     */
    fun limitedLines(lines: Int): Int = lines.coerceIn(1, MAX_LINES)

    /**
     * 最新那一条回复在合并文本里的**起点** (光标选到这里, 框口就停在它的首行)
     *
     * 主人 2026-10-06: "消息回复框应保持首行, 以免每次都要向上找" —— 原来选的是末尾, 于是一条长回答
     * 进来先看见尾巴, 每次都得往上翻回开头。纯函数 (`joined` 里最后那一段就是 `newest`), 没有设备也
     * 能量: 只有一条回复时它回 0, 也就是框自己的首行
     */
    fun newestStart(joined: String, newest: String): Int =
        (joined.length - newest.length).coerceIn(0, joined.length)
}

/**
 * 那条 WebView 输入条 (面板 A) 该占多大、摆在哪
 *
 * 抽出来是因为**它以前只在创建那一刻算一次** (2026-10-08 主人报的"横竖屏切换时文本框会极大偏移"):
 * 宽高与坐标都按当时那块屏算好就留在窗口上了, 而转屏之后窗口管理器不会替它重算 —— 竖屏算出来的
 * 2400 高的条子横过来还是 2400 高, 于是整块跑到屏外
 *
 * 三条算式都是纯函数, 没有设备也能量 (见 BallTest): 一条竖屏、一条横屏, 尺寸与坐标一眼看得出来
 */
internal object StripSpot {

    /** 左右各留一条边距 */
    fun x(margin: Int): Int = margin

    /** 宽 = 屏宽减两边距 */
    fun width(screenWidth: Int, margin: Int): Int =
        (screenWidth - margin * 2).coerceAtLeast(margin * 2)

    /**
     * 高 = 屏高乘那个百分比 (**不是短边**: 竖屏那一档一直是按屏高算的, 换算法会顺带把竖屏也改小),
     * 上下各留一条边距封顶
     */
    fun height(screenHeight: Int, margin: Int, percent: Int): Int {
        val wanted = screenHeight * percent / 100
        return wanted.coerceIn(1, (screenHeight - margin * 2).coerceAtLeast(1))
    }

    /**
     * 停靠的 y: 从屏幕底部往上留 [lift] 那一截 (它给的是"别贴着底边", 不是键盘 —— 键盘由 inset 那一层
     * 另算), 再钳进屏幕里
     */
    fun restY(screenHeight: Int, height: Int, margin: Int, lift: Int): Int {
        val wanted = screenHeight - height - lift
        val ceiling = (screenHeight - height - margin).coerceAtLeast(margin)
        return wanted.coerceIn(margin, ceiling)
    }
}

/**
 * 通道那块窗该摆在哪儿
 *
 * **它跟着球走** (需求原话: "文本框随浮标移动"): 优先摆在球里侧那一边, 里侧放不下就翻到另一边,
 * 两边都放不下时按屏幕右边界钳住 —— 球贴着右边缘那一档就是这一条接住的
 */
internal object BoxSpot {
    fun x(ballX: Int, ballSize: Int, boxWidth: Int, screenWidth: Int): Int {
        val right = ballX + ballSize + GAP_PX
        if (right + boxWidth <= screenWidth) return right
        val left = ballX - boxWidth - GAP_PX
        if (left >= 0) return left
        // 两边都放不下 (600 出头的横屏、分屏那一档): **宁可压在球上也要留在屏幕里** —— 框露出屏外
        // 一半比挡住球糟得多, 而球本来就有一半是"收着"的
        return (ballX - boxWidth / 2).coerceIn(0, (screenWidth - boxWidth).coerceAtLeast(0))
    }

    fun y(ballY: Int, ballSize: Int, boxHeight: Int, screenHeight: Int, imeBottom: Int): Int {
        val wanted = ballY + ballSize / 2 - boxHeight / 2
        val ceiling = screenHeight - imeBottom - boxHeight
        return wanted.coerceIn(0, ceiling.coerceAtLeast(0))
    }

    /** 与球之间留的那点缝 (像素) */
    const val GAP_PX = 10
}

/**
 * 输入通道: 一块自己画的文字框, 打字、回车发送、回复也回到这里
 *
 * 主人 2026-10-05 的口径: "自己新建一个文本框, 在此可输入与滚动, 长度的像素为 650, 行数上限为 5 行,
 * 每行 12 字, 高度随行数增长而不是固定的, 文本框随浮标移动, 点击 2 次空白处才消失。回复结果也传此
 * 通道" —— 其中"5 行"在 2026-10-06 被主人追加成 7 行 ([BallBox.MAX_LINES]), "点击 2 次空白处"**同一天
 * 被收窄成"框外真双击"** (`BallMinutes.BOX_DOUBLE_TAP_MS` 之内的第二下才关, 超时的那一下重新起算),
 * 另外**从那天起它还会自己在闲置 20 s 之后收掉** ([BallMinutes.BOX_IDLE_MS]; 收掉的是窗, 实例与里面
 * 的字跟着一起没 —— 再张回来是一块新框, 里面的回复由宿主再推的那一条补上)
 *
 * 三件事与那块 WebView 输入条不同, 所以这里重写一块而不是复用:
 *
 * 1. **它是纯原生的**: 那 650 px 的宽度、7 行的上限、随内容长高这些都要精确控制, 而 WebView 里的
 *    输入框宽高由页面说了算 (那份页面是 dsh 的 GUI, 不该为浮标改)
 * 2. **发送走应用自己那条链** ([Listener.onSend]): 与球上说话同一个入口, 所以"浮标语音输入"与
 *    "键盘输入"不是两套实现, 只有"字从哪来"不同
 * 3. **回复也画在这里**: 宿主把一轮的回答推回这个通道 ([appendReply]), 于是主人的眼睛不必在浮标
 *    与页面之间来回找
 *
 * 而"什么时候在屏上"这件事是 2026-10-06 主人点名的两条: **发出去了就立刻收** (服务那侧
 * `OverlayService.boxListener.onSend`), **回答到了自己张出来** (服务那侧 `deliverReply`, 那时它
 * 不抢焦点、不弹键盘)。关掉有三条路: 框外那一下**双击** ([OverlayService.noteBlankTap])、闲置 20 s
 * 自己收, 以及**右上角那个 `×`** ([Listener.onClose] —— 2026-10-07 主人点名加的)
 */
internal class BoxView(context: Context, private val listener: Listener) : FrameLayout(context) {

    internal interface Listener {
        /** 回车 (或那个发送键): 把这一行送出去 */
        fun onSend(text: String)

        /** 输入框拿到 / 放下键盘, 调用方据此摆球与框 (键盘起来时两边都要避让) */
        fun onFocus(hasFocus: Boolean)

        /**
         * 有人在框里打字: **这也是一次"有人用它"**
         *
         * 主人 2026-10-06 报的第一条: "键盘输入时, 浮标会空闲半隐藏, 键盘输入时不属于空闲"。打字
         * 只改文本, 不碰球也不碰框的触摸, 所以原来一个回调都不走 —— 5 s 一到球就当没人管它了。
         * 现在每打进一个字都算一次活动
         */
        fun onTyping()

        /**
         * 有人在框里滚了内容 (回复那一块, 或者输入框自己滚)
         *
         * 与打字一样算"有人用它": 读一条长回答本身就是用它, 而这个动作**不碰框的触摸**
         * (`setOnTouchListener` 挂在框上, 滚动发生在子 view 里), 所以原来它一个回调都不走 ——
         * 读到一半就被 20 s 那一笔账把框收掉
         */
        fun onScroll()

        /** 有人碰了框: 这是一次"点到了非空白处", 调用方把框外双击那本账清掉 */
        fun onTouch()

        /** 框自己长高 / 变矮了: 位置要重算 (它跟着球, 而球没动) */
        fun onResize()

        /**
         * **双击回复那一块 = 回应用** (主人 2026-10-07: "双击回复框相当于回应用, ball 菜单的
         * 「回应用」选项可以删掉")
         *
         * 它替代的是长按菜单里那一行, 而目标比那一行更准确: 这条回复是哪一场发来的就回到哪一场
         * (调用方从 [OverlayState.replySession] 取, 见 OverlayService)
         */
        fun onReplyDoubleTap()

        /**
         * 右上角那个 `×` 被点了一下: **把这块框收掉** (主人 2026-10-07: "在图标文本框左/右上角加个
         * 关闭按钮, 点击可关闭")
         *
         * 与"框外双击"是同一条收尾 ([OverlayService.closeChannel]) —— 两条路各留一个, 因为框外双击在
         * 全屏应用里不好找空白处, 而这个按钮一直在看得见的地方
         */
        fun onClose()
    }

    /**
     * 竖排的内容层: 输入框 / 分隔线 / 回复区
     *
     * 它与右上角那个 `×` 是**叠着的两层**而不是一行: 框的高度必须仍然由"输入几行 + 回复几行"说了算
     * ([inputHeight]), 多出来的那一行会把那块框顶高一截
     */
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** 输入框: 透明底, 最多 [BallBox.MAX_LINES] 行, 到顶之后里面自己滚 */
    private val input = VoiceInput(context) { send() }.apply {
        background = null
        setTextColor(TEXT)
        setHintTextColor(HINT)
        hint = context.getString(R.string.ball_box_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = BallBox.MAX_LINES
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(false)
        setLineSpacing(0f, BallBox.LINE_SPACING)
        // 右边空出那一条给右上角的 `×`: 长句子折行时不会有一两个字压在按钮下面
        setPadding(0, 0, dp(CLOSE_RESERVE_DP), 0)
        // **软键盘上那个键就是"发送"**: 多行框的 `imeOptions` 是 NONE, 于是键盘上画的是换行键 ——
        // 主人 2026-10-06 的口径是"换行代表提交", 所以这里把动作直接标成 `IME_ACTION_SEND`
        // (能听懂这个动作的输入法会把键画成"发送"; 听不懂的仍发一个回车, 而回车由 [VoiceInput]
        //  那三级接住, 两条路都落到同一个 [send])
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        setOnEditorActionListener { _, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_GO ||
                enter
            ) {
                send()
                true
            } else {
                false
            }
        }
        // 输入框自己也会滚 (字多过 [BallBox.MAX_LINES] 之后): 那也是一次"有人用它", 与回复那一块
        // 同一条回调 —— 判据只有一处 ([BoxView.Listener.onScroll])
        setOnScrollChangeListener { _, _, _, _, _ -> listener.onScroll() }
        setOnFocusChangeListener { _, has -> listener.onFocus(has) }
    }

    init {
        // 回复那一块是只读的, 但**它自己也会改文本** (appendReply 往里面写), 所以那个监听只挂在
        // 输入框上, 不能挂在整块框上 —— 不然"回答到了"也会被算成"有人在打字"
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                listener.onTyping()
            }
        })
    }

    /** 回复那一块: 只读 (但能选中复制), 里面自己滚 */
    private val replies = EditText(context).apply {
        background = null
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        inputType = InputType.TYPE_NULL
        isFocusable = false
        isFocusableInTouchMode = false
        maxLines = BallBox.REPLY_LINES
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(false)
        setLineSpacing(0f, BallBox.LINE_SPACING)
        setPadding(0, 0, 0, 0)
        // **能滚才有"读到一半"这回事**: 只读 + 不获焦的那一块原来没有 movement method, 超过
        // [BallBox.REPLY_LINES] 行就只能看最后几行 —— 主人点名要的"滚动内容"因此先要它滚得动,
        // 滚动本身再由 [Listener.onScroll] 记成一次活动 (2026-10-06)
        movementMethod = ScrollingMovementMethod.getInstance()
        setOnScrollChangeListener { _, _, _, _, _ -> listener.onScroll() }
        // **双击 = 回应用** (2026-10-07 主人点名的口径): 这一块就是"agent 刚发回来的那条消息",
        // 双击它落到那条回复所在的那场对话。监听器挂在回复这一块上 (不是整块框): 输入框那一半
        // 的双击还是"选中一个词", 只有回复这一半才有这个手势
        //
        // 返回 false 是有意的: 事件继续交给 EditText 自己处理, 于是滚动与选中复制一个字都不少,
        // 双击只是**多**出来的一件事
        val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                listener.onReplyDoubleTap()
                return true
            }
        })
        setOnTouchListener { _, event ->
            doubleTap.onTouchEvent(event)
            // 碰到回复这一块也算"碰到了框": 调用方据此把"框外双击那本账"清掉 (那本账说的是"点到了
            // 非空白处"), 顺带给闲置那笔账续期 —— 少了它, 读完一条回复之后那一下空白点击可能被
            // 当成第二下而把框关掉
            listener.onTouch()
            false
        }
        visibility = GONE
    }

    private val divider = View(context).apply {
        setBackgroundColor(DIVIDER)
        visibility = GONE
    }

    /**
     * 右上角那个 `×`: 点一下把框收掉
     *
     * 画的是那个字符而不是一张图 (项目里没有图标资源这一层, 而它是 12 sp 就能看清的一个符号), 位置与
     * 大小都在 [CLOSE_RESERVE_DP] 那一条留给它的空档里
     */
    private val close = TextView(context).apply {
        text = CLOSE_GLYPH
        setTextColor(HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOSE_SP)
        gravity = Gravity.CENTER
        val padH = dp(CLOSE_PAD_H_DP)
        val padV = dp(CLOSE_PAD_V_DP)
        setPadding(padH, padV, padH, padV)
        contentDescription = context.getString(R.string.ball_box_close)
        setOnClickListener { listener.onClose() }
    }

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(BallBox.CORNER_DP).toFloat()
            setColor(BACKGROUND)
            setStroke(dp(1), STROKE)
        }
        val padH = dp(BallBox.PAD_H_DP)
        val padV = dp(BallBox.PAD_V_DP)
        setPadding(padH, padV, padH, padV)
        val wide = LinearLayout.LayoutParams.MATCH_PARENT
        val tall = LinearLayout.LayoutParams.WRAP_CONTENT
        column.addView(input, LinearLayout.LayoutParams(wide, tall))
        column.addView(divider, LinearLayout.LayoutParams(wide, dp(1)))
        column.addView(replies, LinearLayout.LayoutParams(wide, tall))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setOnTouchListener { _, _ ->
            listener.onTouch()
            false
        }
        // 内容长高 / 变矮的时候把"该摆哪儿"重新算一次: 框跟着球, 而它自己的高度也在变
        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) listener.onResize()
        }
    }

    /** 框现在有几行 (量出来的那个数, 不是猜的) */
    fun inputLines(): Int = input.lineCount

    /**
     * 输入框现在要多高: **跟着行数长, 到 [BallBox.MAX_LINES] 就停**
     *
     * 量出来的是行数乘一行的高度 (而不是那个真实测量值), 因为这一条正是主人点名的那句"高度随行数
     * 增长而不是固定的" —— 它得是一个能算、能对账的数, 而不是"看着差不多高"
     */
    fun inputHeight(): Int {
        val metrics = input.paint.fontMetrics
        val line = (metrics.descent - metrics.ascent) * BallBox.LINE_SPACING
        return (line * BallBox.limitedLines(inputLines())).toInt() +
            input.paddingTop + input.paddingBottom
    }

    /** 让主人能接着打字: 点一下框就该起键盘 (框一挂上时**不**自动抢焦点, 那样球一挂上就顶起键盘) */
    fun focusInput() {
        input.requestFocus()
    }

    /**
     * 挂上窗口之后**再要一次**焦点与键盘 (2026-10-09 主人报的"输入框唤出有延迟")
     *
     * 光靠 [focusInput] 那一句 `requestFocus` 是不够的: 窗口刚 `addView` 那一刻还没有输入连接, 而
     * `showSoftInput` 要等焦点真的落到这个 view 上、并且它已经接上窗口的输入通道才生效 —— 那一拍
     * 落在下一次 `performTraversals` 之后, 观感就是"框已经出来了, 键盘迟一拍才冒出来"
     *
     * 所以这一条做两件事, 都排在遍历之后: 再 `requestFocus` 一次 (拿到窗口焦点那一下的补票), 以及
     * 显式 `showSoftInput` 兜底 —— 有些输入法不认 `requestFocus` 顺手带起来的那一次, 而这一下是
     * 直接对 IME 说话。**它是空的**: 窗还没接上时那次 `post` 会在挂上之后再跑, 于是这里不需要等
     */
    fun claimInputAfterLayout() {
        post {
            input.requestFocus()
            val keyboard = context.getSystemService(InputMethodManager::class.java)
            runCatching { keyboard?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    /**
     * 把键盘与焦点收掉, **但窗留着**
     *
     * 开语音那一下要用它 (2026-10-06 主人: "有回复窗时也可以点击小球进行语音输入"): 框与语音是
     * 同一句话的两个入口, 键盘与麦克风不能抢, 而框本身还要留在屏上 —— 回复内容与"这一场是谁"
     * 都靠它, 收掉之后回答就没地方画了
     */
    fun blurInput() {
        input.clearFocus()
        val keyboard = context.getSystemService(InputMethodManager::class.java)
        runCatching { keyboard?.hideSoftInputFromWindow(input.windowToken, 0) }
    }

    fun hasInputFocus(): Boolean = input.hasFocus()

    /** 回车 / 发送键那一下: 有字才发, 发完清空 (空的那一下当没发生) */
    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        // 发完把光标留在框里: 键盘不收起, 接着打下一句时回车照样能提交 (2026-10-06 主人报的那条
        // "换行代表提交"里最容易漏的一半 —— 光标跟着回复跑到只读那一块上, 下一次回车就打在空处了)
        input.requestFocus()
        listener.onSend(text)
    }

    /**
     * 回复来了: 画在下面那一块里, 最多留 [MAX_REPLIES] 条
     *
     * 只留最近几条是**故意的**: 这一块是"刚说完的那句回答"的落脚处, 不是会话记录 —— 上面那块
     * WebView 与主界面才是看历史的地方
     *
     * 画的时候是**与输入框同一套字号与颜色** (主人 2026-10-06: "agent 回复时应像键盘输入那样用文本
     * 框"): 那两个字共用一个 14 sp 的正文色, 只有分隔线分得开它们 —— 所以回答看着还是"一块能读的
     * 字", 不是一行小注
     *
     * **停在最新那条的首行** (主人 2026-10-06: "消息回复框应保持首行, 以免每次都要向上找"): 光标
     * 选到 [BallBox.newestStart] 那个位置, 再等布局排好把这一行顶到框口 —— 原来选末尾, 长回答一进来
     * 先看见的是尾巴
     */
    fun appendReply(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        kept.add(line)
        while (kept.size > MAX_REPLIES) kept.removeAt(0)
        render(line)
    }

    /**
     * 一条**本地的信息** (不是回答, 也不是报错): 现在唯一的来源是球菜单那一行「看余额」
     *
     * 它以 [INFO_MARK] 开头, 与回答分得开, 与那几条 `⚠` 提示也分得开。**三条会话账一个都不动**
     * (`replySession` / `inputSession` / `boxSession`) —— 双击回复框与「返回应用」的去向不因为看了一眼
     * 余额就变, 这是它与 [appendReply] 最大的差别
     *
     * [replaceInfo] 是它的"就地改写": 「正在取…」被真数顶掉时用的就是它, 屏上不会留下两行
     */
    fun appendInfo(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        val marked = "$INFO_MARK $line"
        kept.add(marked)
        while (kept.size > MAX_REPLIES) kept.removeAt(0)
        render(marked)
    }

    /** 改写最后一条信息行; 最后一条不是信息行时就当作新增 (那一次「正在取」已经被收掉了) */
    fun replaceInfo(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        val marked = "$INFO_MARK $line"
        val last = kept.lastOrNull()
        if (last != null && last.startsWith(INFO_MARK)) {
            kept[kept.size - 1] = marked
        } else {
            kept.add(marked)
            while (kept.size > MAX_REPLIES) kept.removeAt(0)
        }
        render(marked)
    }

    /** 把 [kept] 画到只读那一块里, 并把最新那一条顶到框口 ([BallBox.newestStart] 那条口径) */
    private fun render(newest: String) {
        val joined = kept.joinToString("\n\n")
        val start = BallBox.newestStart(joined, newest)
        replies.setText(joined)
        // **光标只能落在真的存下来的那段字里**: 这块是只读的 EditText, `setText` 之后拿到的长度未必
        // 等于 `joined.length` —— 2026-10-14 真机上量到一次 5546 字的回复落进去只剩 5000, 而
        // `setSelection(5546)` 当场抛 `IndexOutOfBoundsException: setSpan (5546 ... 5546) ends beyond
        // length 5000` 把整个应用打死 (每 100 秒一次)。夹一下之后, 长了就停在末尾, 不会再崩
        val caret = start.coerceIn(0, replies.text?.length ?: 0)
        replies.setSelection(caret)
        replies.visibility = VISIBLE
        divider.visibility = VISIBLE
        // `setText` 之后的行是异步排的, 那一刻拿 layout 可能还是旧的: 等一拍再把这行顶到框口
        // (拿不到 layout 就什么都不做 —— 上面的 setSelection 已经把这一行带进视野了)
        replies.post {
            val layout = replies.layout ?: return@post
            val lineIndex = layout.getLineForOffset(caret)
            runCatching { replies.scrollTo(0, layout.getLineTop(lineIndex)) }
        }
    }

    /**
     * 一条**提示** (不是回答): 同一块只读区, 前面带一个 `⚠`
     *
     * 来源只有一条: 宿主那边投不出去的那句话 (见 host-plugin 的 `reportNote`)。它必须与回答长得不
     * 一样 —— 主人要分得清"这是它说的"与"这是系统在说哪里不对"
     */
    fun appendNote(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        appendReply("${NOTE_MARK} $line")
    }

    /** 现在框里留着几条回复 (判据用) */
    fun replyCount(): Int = kept.size

    private val kept = mutableListOf<String>()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TEXT_SP = 14f
        const val MAX_REPLIES = 4

        /** 右上角那个关闭按钮: 字符 / 字号 / 内边距, 以及输入框右边给它留的那一条 (dp) */
        const val CLOSE_GLYPH = "×"
        const val CLOSE_SP = 15f
        const val CLOSE_PAD_H_DP = 8
        const val CLOSE_PAD_V_DP = 2
        const val CLOSE_RESERVE_DP = 30

        /** 提示那一行的记号: 与回答同一块只读区, 一眼分得开 ([appendNote]) */
        const val NOTE_MARK = "⚠"

        /** 信息那一行的记号 (现在是「看余额」那一档, [appendInfo]): 金额开头, 与回答和 ⚠ 都分得开 */
        const val INFO_MARK = "¥"

        val BACKGROUND = 0xF21B1B1F.toInt()
        val TEXT = 0xFFEDEDED.toInt()
        val HINT = 0x80EDEDED.toInt()
        val DIVIDER = 0x33FFFFFF
        val STROKE = 0x33FFFFFF
    }
}

/** 通道那块窗的布局参数 (它可获得焦点: 要收系统输入法) */
internal fun boxLayout(width: Int, height: Int): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 那块框自己可获焦 (要收输入法), 而**窗外照旧要能点**: NOT_TOUCH_MODAL 让窗外的触摸落到
        // 底下的应用上, 同时 WATCH_OUTSIDE_TOUCH 让我收到一个 ACTION_OUTSIDE —— 那个事件正是
        // "框外那一下双击"的输入 (窗口 300 ms 内的第二下才收, 见 OverlayService.noteBlankTap)。
        // **不给 WATCH_OUTSIDE_TOUCH 就只能"点里面"或"点穿过去"**, 两种都收不到
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

/**
 * 输入框那一个 `EditText`: 回车就是发送
 *
 * **为什么要一个自己的类**: 多行框的 `imeOptions` 是 NONE (软键盘上那个回车只发一个
 * `KEYCODE_ENTER`), 而"按回车即可发送提示词"是主人 2026-10-05 点名的行为 —— 所以回车键要在
 * `onKeyPreIme` / `onKeyDown` / `onKeyUp` 三级上接住, 而不是让 `EditText` 把它插成换行
 *
 * 三级都要 (主人 2026-10-06 再点了一次这条: "键盘输入时换行代表提交"):
 * - 软键盘那个回车/换行键: 有的输入法发 `onKeyPreIme` (先到 IME 再到 view), 有的只发 `onKeyDown`
 * - 硬件键盘 (模拟器上 `input keyevent 66`): `onKeyDown`
 * - **多行框的兜底是 `onKeyUp`**: `EditText` 把回车插成换行是在 **UP** 那一下做的, 所以只拦 DOWN
 *   时仍会留下一个换行符 —— 那正是"按了回车却在框里多出一行"的现象
 *
 * 三个都吃掉之后, 这个框里**不会出现换行**: 七行上限只用来显示长句子自己的折行
 */
private class VoiceInput(context: Context, private val onEnter: () -> Unit) : EditText(context) {

    /** 回车在 DOWN 与 UP 上都会到, 只认一次 (不然同一下按两回就发两句) */
    private var swallowed = false

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (!swallowed) onEnter()
                swallowed = false
            } else {
                swallowed = true
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            // 这里**不立刻发**: 等 UP 那一下 (软键盘与硬件键盘最终都会给一个 UP)。DOWN 上发的话,
            // 紧接着的 UP 还会再走一遍 —— 那就是"按一次回车发了两句"
            swallowed = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (swallowed) {
                swallowed = false
                onEnter()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

/**
 * 通道那块窗的根: 收 `ACTION_OUTSIDE`
 *
 * 与菜单那一层 ([MenuRoot]) 同一个道理: `FLAG_WATCH_OUTSIDE_TOUCH` 给的 `ACTION_OUTSIDE` 只会走到
 * 窗口根那一层, 而根是 `BoxView` (一个 `LinearLayout`, 它不吃这个事件) —— 所以套一层专门收它的
 * 壳。**收下但不消费掉窗内的触摸**: 框里的点击照旧给输入框
 */
internal class ChannelRoot(context: Context) : FrameLayout(context) {

    var onOutside: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }
}
