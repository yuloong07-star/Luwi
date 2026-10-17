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
import io.github.yuloong07star.luwi.plugin.theme.BallTheme

/**
 * 对话槽那一格的组装 (2.7.8 批次 6): 读那份 `desktop-chat.json` + 取当前浮标外观, 拼一份 RemoteViews
 *
 * 它与前两个槽最大的不同:**没有"挂哪一只插件"这回事**。立绘与气泡都不在插件目录里 ——
 * 立绘是主人挑的那套浮标外观 (`BallTheme`, 与球同一个来源), 气泡是宿主与应用两边写的那份文件。
 * 于是这一格"谁都不用选", 放上桌面就是它该有的样子
 *
 * 三件事按顺序来:
 *
 * 1. **气泡**: 只取最近 [DesktopChatFile.SHOW_LINES] 条 (`who` 决定靠左还是靠右)
 * 2. **立绘**: `idle` 那一张脸; 它带帧序列时走 `ViewFlipper` + 逐帧 `addView` (桌面那一侧只有这条路
 *    真会动, 与动画槽同一个理由), 不带帧就是一张静态图
 * 3. **那颗「说一句」**: 走法 A 时是发给 [WidgetChatActionReceiver] 的广播 (不出 Activity), 而浮标
 *    权限没给 / 开关位挑的是走法 B 时, 它如实退回"打开 Luwi"的 activity 意图
 */
internal object WidgetChat {

    /** 走法 A: 广播给浮标服务, 右下角那块输入窗挂出来 (D5 定下的那一条) */
    const val INPUT_OVERLAY = "overlay"

    /** 走法 B: 透明 Activity (批次 6 先不写, 只留这个开关位) */
    const val INPUT_ACTIVITY = "activity"

    /** 那颗按钮点下去要说的话 (`PendingIntent.getBroadcast` 的 action) */
    const val ACTION_SAY = "io.github.yuloong07star.luwi.widget.SAY"

    private const val STORE = "luwi"
    private const val KEY_INPUT = "chat-widget-input"

    /**
     * 立绘取哪一个状态的脸
     *
     * `idle` 而不是"当前正在想的那一档": 桌面那一格**不在会话里**, 它没有"这一轮"可跟 —— 挑
     * `idle` 是"没在说话"那张脸, 与人看一眼桌面时的预期一致
     */
    private const val FACE_STATE = "idle"

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
     * 立绘那一块的尺寸 (dp)
     *
     * 主人 2026-10-17: "人物应该随边框变大而变大, 但长宽要等比, 防止变形" —— 原来那一格是写死的
     * 64 dp, 把组件拉大之后人物还那么小。现在按**这一格实际占多大**算, 而算式只有一处:
     *
     * - 要的是**正方形**: 正方形 + `fitCenter` (见 `lw_widget_slot_frame.xml`) 是"等比且不变形"
     *   这两件事的最小组合 —— 立绘本身是竖的全身像, 装进方窗里按比例缩, 不会横向拉宽
     * - 先按宽算 (扣掉右边那颗「说一句」留给它的位置), 再按高算 (占格子高度的
     *   [HEIGHT_PERCENT]), 取小的那一个; 两头夹在 [MIN_DP]~[MAX_DP]
     * - **格子尺寸还读不到** (刚装上 / 桌面没报) 时落回 [DEFAULT_DP] (就是原来那个 64)
     *
     * 纯算术: 没有设备也能量 (见 WidgetChatTest)
     */
    internal object Face {
        const val DEFAULT_DP = 64
        const val MIN_DP = 44
        const val MAX_DP = 132

        /** 与 `lw_widget_slot_chat.xml` 根上那个 padding 是同一个数 */
        const val PAD_DP = 8

        /** 立绘最多占可用宽度的百分之多少 (右边那颗「说一句」要留在它旁边) */
        const val WIDTH_PERCENT = 55

        /** 立绘最多占可用高度的百分之多少 (再高就压到气泡了) */
        const val HEIGHT_PERCENT = 34

        fun sizeDp(cellWidthDp: Int, cellHeightDp: Int): Int {
            if (cellWidthDp <= 0 || cellHeightDp <= 0) return DEFAULT_DP
            val usableWidth = (cellWidthDp - PAD_DP * 2).coerceAtLeast(MIN_DP)
            val usableHeight = (cellHeightDp - PAD_DP * 2).coerceAtLeast(MIN_DP)
            val byWidth = usableWidth * WIDTH_PERCENT / 100
            val byHeight = usableHeight * HEIGHT_PERCENT / 100
            return minOf(byWidth, byHeight).coerceIn(MIN_DP, MAX_DP)
        }
    }

    /** 拼一次那一格 (没给格子尺寸时立绘用缺省那一个数) */
    fun views(context: Context): RemoteViews = views(context, 0, 0)

    /**
     * 拼一次那一格
     *
     * @param cellWidthDp / @param cellHeightDp 这一格现在多大 (来自 `AppWidgetManager` 的 options);
     *   读不到就传 0, 立绘落回 [Face.DEFAULT_DP]
     */
    fun views(context: Context, cellWidthDp: Int, cellHeightDp: Int): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.lw_widget_slot_chat)
        val snapshot = DesktopChatFile.snapshot(context)
        val shown = snapshot.shown

        // 立绘那一块多大: 正方形, 两个方向给同一个数 (等比靠 fitCenter, 不靠把图拉满)
        //
        // **第三个参数是 `TypedValue.COMPLEX_UNIT_*` 而不是 MeasureSpec** —— 2026-10-17 在模拟器上
        // 撞过: 传 `View.MeasureSpec.EXACTLY` 会在接收器里抛 "Must be a valid COMPLEX_UNIT_*", 那一格
        // 于是退回 initialLayout (面板还在、气泡与立绘全没了), 看起来像"组件坏了"
        val faceDp = Face.sizeDp(cellWidthDp, cellHeightDp).toFloat()
        view.setViewLayoutWidth(R.id.lw_chat_flipper, faceDp, TypedValue.COMPLEX_UNIT_DIP)
        view.setViewLayoutHeight(R.id.lw_chat_flipper, faceDp, TypedValue.COMPLEX_UNIT_DIP)

        // ① 气泡: `addView` 逐条加, 谁说的决定用哪份版式 (靠左 / 靠右)
        view.removeAllViews(R.id.lw_chat_bubbles)
        shown.forEach { line ->
            val bubble = RemoteViews(
                context.packageName,
                if (line.who == DesktopChatFile.WHO_USER) {
                    R.layout.lw_widget_slot_chat_bubble_user
                } else {
                    R.layout.lw_widget_slot_chat_bubble_assistant
                },
            )
            bubble.setTextViewText(R.id.lw_chat_bubble_text, line.text)
            view.addView(R.id.lw_chat_bubbles, bubble)
        }
        // 一条都没有 (或者那份文件读不懂) 时把话说在明处: 空着与"读不懂"不是一回事
        val empty = if (shown.isEmpty()) {
            snapshot.note.ifBlank { context.getString(R.string.widget_chat_empty) }
        } else {
            ""
        }
        view.setTextViewText(R.id.lw_chat_empty, empty)
        view.setViewVisibility(R.id.lw_chat_empty, if (empty.isBlank()) View.GONE else View.VISIBLE)

        // ② 立绘: 与球同一个来源 (当前浮标外观那一套), 带帧就让它自己翻
        bindFace(context, view)

        // ③ 点哪儿去 (主人 2026-10-17 定的口径): **点气泡或空白处什么都不做** —— 那一格是"看最近几句"
        // 的地方, 一点就跳回应用会把人正看着的桌面顶掉。整格只留那一颗「说一句」有动作
        view.setOnClickPendingIntent(R.id.lw_chat_say, sayIntent(context))
        return view
    }

    /**
     * 立绘那一块
     *
     * 没有挑外观 (内置画法) 时**什么都不画**: 内置那套是球本身 (一个圆 + 一圈水花), 摆在桌面那一格里
     * 只会是一块看不懂的图形 —— 空着比画错好, 而气泡照样在
     */
    private fun bindFace(context: Context, view: RemoteViews) {
        view.removeAllViews(R.id.lw_chat_flipper)
        val face = runCatching { BallTheme.face(context, FACE_STATE) }.getOrNull()
        if (face == null) {
            view.setViewVisibility(R.id.lw_chat_flipper, View.INVISIBLE)
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

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        31,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
