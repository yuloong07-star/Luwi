package io.github.yuloong07star.luwi.plugin.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.yuloong07star.luwi.overlay.DesktopChatFile
import io.github.yuloong07star.luwi.tool.LwOverlay

/**
 * 对话槽那一格 (2.7.8 批次 6): 组件选择器里叫「Luwi 对话组件」
 *
 * 它与前两个槽的差别只有一处: **内容不来自插件**, 于是 `onUpdate` 除了拼一次 RemoteViews, 还要顺手
 * 把"那份文件一变就重画"那条 inotify 挂上 ([DesktopChatFile.ensure]) —— 桌面那一格没有自己的进程,
 * 它的一切变化都得有人推
 */
class WidgetSlotChat : WidgetSlotBase(WidgetSlot.CHAT) {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DesktopChatFile.ensure(context) { WidgetHost.refreshChat(context) }
        super.onUpdate(context, manager, ids)
    }
}

/**
 * 那颗「说一句」的去向 (走法 A, D5 定下的那一条)
 *
 * 广播 → 浮标服务把它那块输入窗挂在右下角 (**不出 Activity**, 于是没有 Android 12+ 那条后台启动
 * 限制要绕)。发出去的那一句走的是浮标现成的那条投递链 (`voice/inbox.jsonl`), 不新造协议
 *
 * 点完重画一次: 那一格里的「说一句」指向哪一边取决于浮标权限, 而这个权限是主人随时会在系统设置里
 * 改的东西
 */
class WidgetChatActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 两条广播: 说一句 (走法 A) 与清空 (一键清空气泡)
        if (intent.action == WidgetChat.ACTION_CLEAR) {
            val cleared = runCatching { DesktopChatFile.clear(context) }.getOrDefault(false)
            Log.i(TAG, "the widget asked to clear the bubbles: $cleared")
            runCatching { WidgetHost.refreshChat(context) }
            return
        }
        if (intent.action != WidgetChat.ACTION_SAY) return
        val said = runCatching { LwOverlay.chatFromWidget(context) }
            .getOrElse { error ->
                Log.w(TAG, "the widget's say button did not reach the ball", error)
                "failed"
            }
        Log.i(TAG, "the widget asked for the input window: $said")
        runCatching { WidgetHost.refreshChat(context) }
    }

    private companion object {
        const val TAG = "LwWidgetChat"
    }
}

