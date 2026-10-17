package io.github.yuloong07star.luwi.plugin.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.github.yuloong07star.luwi.MainActivity
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.overlay.DesktopChatFile
import io.github.yuloong07star.luwi.plugin.PluginManifest
import io.github.yuloong07star.luwi.plugin.PluginPaths
import io.github.yuloong07star.luwi.plugin.PluginSettings
import io.github.yuloong07star.luwi.plugin.PluginStore
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/**
 * 对话槽那一格的组装 (2.7.8 批次 6): 读那份 `desktop-chat.json` + 取插件与它的设置, 拼一份 RemoteViews
 *
 * **这一格由插件驱动** (主人 2026-10-17: "这个作为插件发布, 并可在设置中安装"): 挂在这个槽上的那只
 * 插件声明它的设置项 (`ui.settings`), 而这一格画什么全从那几项读出来:
 *
 * | 设置键 | 意思 | 缺省 |
 * | :-- | :-- | :-- |
 * | `bubbleSp` | 气泡字号 | 11 |
 * | `face` | 显示哪个人物: `ball` (与浮标相同外观) 或插件自带的一张图 | `ball` |
 * | `faceDp` | 那个人的原始大小 (dp) | 64 |
 *
 * 三件事按顺序来:
 *
 * 1. **气泡**: 只取最近 [DesktopChatFile.SHOW_LINES] 条 (`who` 决定靠左还是靠右), 字号读 `bubbleSp`
 * 2. **立绘**: `ball` 走 [BallTheme] 当前那一套 (与球同一个来源), 否则走插件自己那张图; 帧序列走
 *    `ViewFlipper` + 逐帧 `addView` (桌面那一侧只有这条路真会动)
 * 3. **那颗「说一句」**: 走法 A 时是发给 [WidgetChatActionReceiver] 的广播 (不出 Activity), 而浮标
 *    权限没给 / 开关位挑的是走法 B 时, 它如实退回"打开 Luwi"的 activity 意图
 */
internal object WidgetChat {

    /** 走法 A: 广播给浮标服务, 右下角那块输入窗挂出来 (D5 定下的那一条) */
    const val INPUT_OVERLAY = "overlay"

    /** 走法 B: 透明 Activity (批次 6 先不写, 只留这个开关位) */
    const val INPUT_ACTIVITY = "activity"

    /** 「清空」: 一键把气泡那本账清空 (入口与投递链不动) */
    const val ACTION_CLEAR = "io.github.yuloong07star.luwi.widget.CLEAR"



    /** 当前翻到第几页之前 */
    /**
     * 点一下气泡区: 往前翻一页; 已经到最早那一页就回到最新
     *
     * 这是"滚动窗"在 RemoteViews 里能落地的做法 (那个容器里没有 ScrollView): 一页一页往回看,
     * 再点一下回到最新 —— 比"只显示最近几条、更早的看不到"多给了一条路
     */
    /** 那颗按钮点下去要说的话 (`PendingIntent.getBroadcast` 的 action) */
    /** 那颗按钮点下去要说的话 (`PendingIntent.getBroadcast` 的 action) */
    const val ACTION_SAY = "io.github.yuloong07star.luwi.widget.SAY"

    /** 三个设置键 (与插件包里 `ui.settings` 那三行逐字一致) */
    const val KEY_BUBBLE_SP = "bubbleSp"
    const val KEY_FACE = "face"
    const val KEY_FACE_DP = "faceDp"

    /** `face` 的那个特殊值: 与浮标相同外观 (缺省) */
    const val FACE_BALL = "ball"

    private const val STORE = "luwi"
    private const val KEY_INPUT = "chat-widget-input"

    /**
     * 立绘取哪一个状态的脸
     *
     * `idle` 而不是"当前正在想的那一档": 桌面那一格**不在会话里**, 它没有"这一轮"可跟 —— 挑
     * `idle` 是"没在说话"那张脸, 与人看一眼桌面时的预期一致
     */
    private const val FACE_STATE = "idle"

    private const val DEFAULT_BUBBLE_SP = 11L
    private const val DEFAULT_FACE_DP = 64L

    /** 走法 A / B 的开关位 (D5 的退路; 缺省走法 A) */
    fun input(context: Context): String {
        val stored = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(KEY_INPUT, INPUT_OVERLAY)
        return if (stored == INPUT_ACTIVITY) INPUT_ACTIVITY else INPUT_OVERLAY
    }

    fun setInput(context: Context, value: String) {
        val chosen = if (value == INPUT_ACTIVITY) INPUT_ACTIVITY else INPUT_OVERLAY
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY_INPUT, chosen).apply()
    }

    /**
     * 尺寸那一套算式, 全在这里 (纯算术, 没有设备也能量 —— 见 WidgetChatTest)
     *
     * 主人 2026-10-17 的两条: "人物应该随边框变大而变大, 但长宽要等比, 防止变形" + "气泡显示区域
     * 改成比人物高度高约一行气泡的高度"
     */
    internal object Face {

        /** 这个数就是"64 dp 基准"那个缺省 (插件没写 `faceDp` 时) */
        const val BASE_DP = 64
        const val MIN_DP = 44
        const val MAX_DP = 132

        /** 与布局根上那个 padding 是同一个数 */
        const val PAD_DP = 8

        /** 立绘最多占可用宽度的百分之多少 (右边那颗「说一句」要留在它旁边) */
        const val WIDTH_PERCENT = 70

        /** 立绘最多占可用高度的百分之多少 */
        const val HEIGHT_PERCENT = 45

        /** 格子有多大就画多大 (上限那一道, 与插件的基准无关) */
        fun cellDp(cellWidthDp: Int, cellHeightDp: Int): Int {
            if (cellWidthDp <= 0 || cellHeightDp <= 0) return BASE_DP
            val usableWidth = (cellWidthDp - PAD_DP * 2).coerceAtLeast(MIN_DP)
            val usableHeight = (cellHeightDp - PAD_DP * 2).coerceAtLeast(MIN_DP)
            val byWidth = usableWidth * WIDTH_PERCENT / 100
            val byHeight = usableHeight * HEIGHT_PERCENT / 100
            return minOf(byWidth, byHeight).coerceIn(MIN_DP, MAX_DP)
        }

        /**
         * 这一格上人物实际多大 (dp)
         *
         * **设多少就是多少, 只有格子放不下时才按格子上限缩** (主人 2026-10-17: "人物被固定了, 修改
         * 设置也没有变化")。原来那道是"格子的大小 × 设的值 ÷ 64", 于是大格子上只要设到 64 以上就
         * 全都顶到上限 —— 改 64 / 115 / 132 看起来一模一样, 像是设置没生效
         *
         * 读不到格子尺寸时 (0) 直接用你设的那个数: 组件刚放上去、桌面还没报尺寸那一拍也不会被缩
         */
        fun actualDp(cellWidthDp: Int, cellHeightDp: Int, baseDp: Long): Int {
            val base = baseDp.coerceIn(MIN_DP.toLong(), MAX_DP.toLong()).toInt()
            if (cellWidthDp <= 0 || cellHeightDp <= 0) return base
            return minOf(base, cellDp(cellWidthDp, cellHeightDp)).coerceIn(MIN_DP, MAX_DP)
        }

        /** 一行气泡有多高 (dp): 字号 × 行距 + 上下内边距 (`lw_widget_slot_chat_bubble_*.xml` 那 5 dp) */
        fun oneLineDp(bubbleSp: Long): Int = (bubbleSp * LINE_SPACING + BUBBLE_PAD_DP * 2).roundToInt()

        /**
         * 气泡区多高 (dp): **下限是人物高度 + 一行**, 上面再按"这一格能放几行"长
         *
         * 主人 2026-10-17 两条合并的结果:
         *
         * - "气泡显示区域改成比人物高度高约一行气泡的高度" → 下限 [minAreaDp]
         * - "对话框只显示了 3 行, 超过就消失了" → 内容多的时候不许裁, 按 [rowsFor] 算出来的行数往上长
         *
         * 所以它是 `max(下限, 行数 × 行高)`: 一条都没有时也留着"人物 + 一行"那么高的底, 而气泡多时
         * 一路长到这一格放不下为止 (再多的由那份文件自己的上限管)
         */
        fun areaDp(faceDp: Int, bubbleSp: Long, rows: Int): Int {
            val line = oneLineDp(bubbleSp)
            return maxOf(minAreaDp(faceDp, bubbleSp), rows * line)
        }

        /** 那条下限: 人物高度 + 一行 (人物变大它就跟着变高, 主人 2026-10-17 的第二条) */
        fun minAreaDp(faceDp: Int, bubbleSp: Long): Int = faceDp + oneLineDp(bubbleSp)

        /**
         * 这一格放得下几行气泡
         *
         * 格子有多高、扣掉上下 padding 与底部那一行人物, 剩下的除以一行的高度 —— 结果夹在
         * 1..[MAX_ROWS]。尺寸读不到 (0) 时按 [DEFAULT_ROWS] 算, 于是"还没报尺寸"那一拍也不会只剩空
         */
        fun rowsFor(cellHeightDp: Int, faceDp: Int, bubbleSp: Long): Int {
            if (cellHeightDp <= 0) return DEFAULT_ROWS
            val line = oneLineDp(bubbleSp).coerceAtLeast(1)
            val free = cellHeightDp - PAD_DP * 2 - faceDp
            return (free / line).coerceIn(1, MAX_ROWS)
        }

        const val LINE_SPACING = 1.35f
        const val BUBBLE_PAD_DP = 5

        /** 气泡区最多往上长几行 (与那份文件的 6 条对齐: 一条一行就正好放得下) */
        const val MAX_ROWS = 6

        /** 格子尺寸还读不到时按几行算 (原来写死的那个 3) */
        const val DEFAULT_ROWS = 3
    }

    /** 拼一次那一格 (没给格子尺寸时立绘用缺省那一个数) */
    fun views(context: Context): RemoteViews = views(context, 0, 0)

    /**
     * 拼一次那一格
     *
     * @param cellWidthDp / @param cellHeightDp 这一格现在多大 (来自 `AppWidgetManager` 的 options);
     *   读不到就传 0, 立绘落回缺省
     */
    fun views(context: Context, cellWidthDp: Int, cellHeightDp: Int, widgetId: Int = 0): RemoteViews {
        val holder = WidgetSlots.holder(context, WidgetSlot.CHAT)
        val manifest = holder?.let { PluginStore.manifest(context, it) }
        val spec = manifest?.widget?.takeIf { it.slot == WidgetSlot.CHAT }
        if (holder == null || manifest == null || spec == null) {
            // **没有插件挂着就是空态**: 这一格的内容全来自插件 (设置与素材), 空着要说得清
            return empty(context, if (holder == null) {
                context.getString(R.string.settings_widget_slot_vacant)
            } else {
                context.getString(R.string.settings_widget_slot_gone)
            })
        }
        val settings = PluginSettings.values(context, manifest)
        val bubbleSp = number(settings[KEY_BUBBLE_SP], DEFAULT_BUBBLE_SP).coerceIn(9, 24)
        val faceDp = Face.actualDp(cellWidthDp, cellHeightDp, number(settings[KEY_FACE_DP], DEFAULT_FACE_DP))
        val faceChoice = text(settings[KEY_FACE], FACE_BALL)

        val view = RemoteViews(context.packageName, R.layout.lw_widget_slot_chat)
        val snapshot = DesktopChatFile.snapshot(context)
        // 气泡区: 高度 = 下限 (人物 + 一行) 与"这一格放得下几行"里大的那个
        val rows = Face.rowsFor(cellHeightDp, faceDp, bubbleSp)
        val areaHeight = Face.areaDp(faceDp, bubbleSp, rows)

        // ① 气泡区的尺寸: 高度按上面那个数, 而人物那一块是正方形:
        // **第三个参数是 `TypedValue.COMPLEX_UNIT_*` 而不是 MeasureSpec** —— 传错的后果是接收器里
        // 抛 "Must be a valid COMPLEX_UNIT_*", 那一格退回 initialLayout (看着像组件坏了)
        view.setViewLayoutHeight(R.id.lw_chat_bubbles, areaHeight.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        view.setViewLayoutWidth(R.id.lw_chat_flipper, faceDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        view.setViewLayoutHeight(R.id.lw_chat_flipper, faceDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)

        // ② 气泡区是 ListView: **必须把 adapter 挂上** —— 少了这一句列表永远是空的 (2.7.8 批次 6 之九),
        // 而且要用**带 widgetId 的那个重载**: 只有 viewId+intent 的老重载桌面拿不到 adapter,
        // 现象是 factory 一次都没被要过
        view.setRemoteAdapter(
            widgetId,
            R.id.lw_chat_bubbles,
            Intent(context, WidgetChatListService::class.java)
                .putExtra(WidgetChatListService.EXTRA_ID, holder),
        )
        view.setEmptyView(R.id.lw_chat_bubbles, R.id.lw_chat_empty)

        // ② 气泡由 ListView 画 (见 WidgetChatListService), 空态那一格由 setEmptyView 顶上来
        val empty = snapshot.note.ifBlank { context.getString(R.string.widget_chat_empty) }
        view.setTextViewText(R.id.lw_chat_empty, empty)
        view.setViewVisibility(R.id.lw_chat_empty, if (empty.isBlank()) View.GONE else View.VISIBLE)

        // ③ 立绘: `ball` = 与浮标相同外观 (缺省), 否则是插件自己那张图
        bindFace(context, view, holder, faceChoice)

        // ④ 点哪儿去 (主人 2026-10-17 定的口径): **点气泡或空白处什么都不做** —— 那一格是"看最近几句"
        // 的地方, 一点就跳回应用会把人正看着的桌面顶掉。整格只留那一颗「说一句」有动作
        view.setOnClickPendingIntent(R.id.lw_chat_say, sayIntent(context))
        // 清空 (一键清空气泡对话)
        view.setOnClickPendingIntent(R.id.lw_chat_clear, actionIntent(context, ACTION_CLEAR, 32))
        return view
    }

    /** 一个槽空了: 只留一句说得清的正文 */
    private fun empty(context: Context, message: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.lw_widget_slot_chat).apply {
            removeAllViews(R.id.lw_chat_bubbles)
            setTextViewText(R.id.lw_chat_empty, message)
            setViewVisibility(R.id.lw_chat_empty, View.VISIBLE)
            setOnClickPendingIntent(R.id.lw_chat_say, sayIntent(context))
        }

    private fun number(value: JsonElement?, fallback: Long): Long =
        (value as? JsonPrimitive)?.longOrNull ?: fallback

    private fun text(value: JsonElement?, fallback: String): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { fallback } ?: fallback

    /**
     * 立绘那一块
     *
     * 两个来源: `ball` (`BallTheme` 当前那一套, 与球同一个来源) 与插件自带那张图。都解不出东西时
     * **什么都不画**: 内置那套是球本身 (一个圆 + 一圈水花), 摆在桌面那一格里只会是一块看不懂的图形,
     * 空着比画错好, 而气泡照样在
     */
    private fun bindFace(context: Context, view: RemoteViews, holder: String, choice: String) {
        view.removeAllViews(R.id.lw_chat_flipper)
        if (choice == FACE_BALL) {
            bindBallFace(context, view)
            return
        }
        // 主人自己导入的 gif (`gif:<名字>`): 拆好的帧序列逐帧放 (RemoteViews 里这是唯一会动的做法)
        io.github.yuloong07star.luwi.plugin.PluginGif.nameOf(choice)?.let { name ->
            bindGifFace(context, view, holder, name)
            return
        }
        val directory = PluginStore.packageDir(context, holder)
        val file = directory?.let { runCatching { PluginPaths.resolve(it, choice, "ui.settings 的 face") }.getOrNull() }
        if (file == null || !file.isFile) {
            view.setViewVisibility(R.id.lw_chat_flipper, View.INVISIBLE)
            return
        }
        val uri = WidgetFramesProvider.pluginFileUri(holder, choice)
        WidgetHost.grantToHomes(context, listOf(uri))
        view.setViewVisibility(R.id.lw_chat_flipper, View.VISIBLE)
        view.addView(
            R.id.lw_chat_flipper,
            RemoteViews(context.packageName, R.layout.lw_widget_slot_frame).apply {
                setImageViewUri(R.id.lw_slot_frame_image, uri)
            },
        )
    }

    /** 导入过的 gif: 拆好的那些帧 (`.data/faces/<名字>/frame-XX.png`) 逐帧放 */
    private fun bindGifFace(context: Context, view: RemoteViews, holder: String, name: String) {
        val frames = io.github.yuloong07star.luwi.plugin.PluginGif.frames(context, holder, name)
        if (frames.isEmpty()) {
            view.setViewVisibility(R.id.lw_chat_flipper, View.INVISIBLE)
            return
        }
        view.setViewVisibility(R.id.lw_chat_flipper, View.VISIBLE)
        val base = ".data/${io.github.yuloong07star.luwi.plugin.PluginGif.DIRECTORY}/$name"
        val uris = frames.map { WidgetFramesProvider.pluginFileUri(holder, "$base/${it.name}") }
        WidgetHost.grantToHomes(context, uris)
        uris.forEach { uri ->
            view.addView(
                R.id.lw_chat_flipper,
                RemoteViews(context.packageName, R.layout.lw_widget_slot_frame).apply {
                    setImageViewUri(R.id.lw_slot_frame_image, uri)
                },
            )
        }
        view.setInt(
            R.id.lw_chat_flipper,
            "setFlipInterval",
            io.github.yuloong07star.luwi.plugin.PluginGif.FLIP_INTERVAL_MS,
        )
    }

    /**
     * 与浮标相同外观: 取当前那一套的 `idle` 那张脸 (带帧就让它自己翻)
     *
     * **没挑外观 (内置画法) 时退回那个品牌标记** (2026-10-17 主人: "人物没有跟 ball 变化"): 球在内置
     * 画法下画的是"一圈水花里一只鲸尾", 而组件这一块原来在那种情形下**什么都不画** —— 于是选了
     * 「跟随浮标显示」反而看着像"人物没了 / 没跟球变"。现在内置那一档画同一份标记
     * (`ic_launcher_foreground`, 与球上那个标记同源), 跟随就是跟随
     */
    private fun bindBallFace(context: Context, view: RemoteViews) {
        val face = runCatching { BallTheme.face(context, FACE_STATE) }.getOrNull()
        if (face == null) {
            view.setViewVisibility(R.id.lw_chat_flipper, View.VISIBLE)
            view.addView(
                R.id.lw_chat_flipper,
                RemoteViews(context.packageName, R.layout.lw_widget_slot_frame).apply {
                    setImageViewResource(R.id.lw_slot_frame_image, R.drawable.ic_launcher_foreground)
                },
            )
            return
        }
        view.setViewVisibility(R.id.lw_chat_flipper, View.VISIBLE)
        val sequence = face.frames
        val frames = sequence?.let { spec ->
            runCatching { BallTheme.framesOf(context, FACE_STATE, spec) }.getOrNull()
        }
        if (sequence == null || frames.isNullOrEmpty()) {
            // 静态那一张: 一段只有一帧的序列 (flipper 自己不动, 等于一张图)
            val uri = WidgetFramesProvider.themeFaceUri(FACE_STATE)
            WidgetHost.grantToHomes(context, listOf(uri))
            view.addView(
                R.id.lw_chat_flipper,
                RemoteViews(context.packageName, R.layout.lw_widget_slot_frame).apply {
                    setImageViewUri(R.id.lw_slot_frame_image, uri)
                },
            )
            return
        }
        val uris = frames.indices.map { WidgetFramesProvider.themeFrameUri(FACE_STATE, it) }
        WidgetHost.grantToHomes(context, uris)
        uris.forEach { uri ->
            view.addView(
                R.id.lw_chat_flipper,
                RemoteViews(context.packageName, R.layout.lw_widget_slot_frame).apply {
                    setImageViewUri(R.id.lw_slot_frame_image, uri)
                },
            )
        }
        view.setInt(R.id.lw_chat_flipper, "setFlipInterval", sequence.intervalMs)
    }

    /**
     * 那颗「说一句」该发什么
     *
     * 两条边界都在**组装这一刻**看清, 而不是点下去再猜:
     *
     * - 浮标权限没给 → 直接打开 Luwi (那一格点了以后屏幕上有反应, 而不是什么都不发生)
     * - 开关位是走法 B → 同上 (那一档还没写, 见 [INPUT_ACTIVITY])
     */
    private fun sayIntent(context: Context): PendingIntent {
        val wantOverlay = input(context) == INPUT_OVERLAY && Settings.canDrawOverlays(context)
        return if (wantOverlay) {
            PendingIntent.getBroadcast(
                context,
                30,
                Intent(context, WidgetChatActionReceiver::class.java).setAction(ACTION_SAY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openIntent(context)
        }
    }

    /** 一条发给 [WidgetChatActionReceiver] 的广播 (清空 / 翻页两条都走它) */
    private fun actionIntent(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, WidgetChatActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 一条发给 `WidgetChatActionReceiver` 的广播 (清空 / 翻页都走它) */
    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        31,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}









