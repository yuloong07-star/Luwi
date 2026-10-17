package io.github.yuloong07star.luwi.plugin.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.util.Log
import android.widget.RemoteViewsService
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.overlay.DesktopChatFile
import io.github.yuloong07star.luwi.plugin.PluginSettings
import io.github.yuloong07star.luwi.plugin.PluginStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 桌面对话组件的气泡区: **真能滚的集合型组件** (2.7.8 批次 6 之八)
 *
 * 原来是"翻页"(点一下往前翻一页) —— 那是 RemoteViews 里没有 ScrollView 时的退路。但 RemoteViews 白名单
 * 里**有 `ListView`**, 而集合型组件的滚动是桌面那一侧原生做的: 手指一划就滚, 不需要我们再补任何手势。
 * 代价是三件 (都写在这里, 免得以后有人以为是随便换的):
 *
 * 1. 每一项只能是 `RemoteViews` 模板 —— 于是有了 `lw_widget_slot_chat_item_user/_assistant` 这两份
 * 2. 数据要经一个 [RemoteViewsService]: 桌面向我们要"第几条是什么", 而**它跑在我们进程里**
 *    (`android:permission="android.permission.BIND_REMOTEVIEWS"` 那条闸), 所以每次 [Factory.onDataSetChanged]
 *    读一次那份 `desktop-chat.json`
 * 3. 顺序**倒过来**: 最新的一条排在第 0 位, 于是组件一打开看到的就是最新那句 (主人要的"最底下的对话
 *    可见"), 往上划才是更早的
 */
class WidgetChatListService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        // 排障那一行 (2.7.8 批次 6 之九): 桌面到底有没有来要工厂、id 是不是空 —— 一次装机就能分辨
        Log.i(TAG, "the launcher asked for the factory: id=${intent.getStringExtra(EXTRA_ID)}")
        return Factory(applicationContext, intent.getStringExtra(EXTRA_ID).orEmpty())
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val TAG = "LwWidgetChatList"
    }

    /** 给桌面那一侧递"第几条"的那个工厂 */
    private class Factory(private val context: Context, private val pluginId: String) :
        RemoteViewsService.RemoteViewsFactory {

        /** 最新的排最前 (倒序), 于是第 0 条就是刚说的那句 */
        private var lines: List<DesktopChatFile.Line> = emptyList()
        private var bubbleSp = DEFAULT_BUBBLE_SP

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            lines = DesktopChatFile.snapshot(context).lines.reversed()
            Log.i(TAG, "data set changed: ${lines.size} line(s), id=$pluginId, sp=$DEFAULT_BUBBLE_SP")
            // 字号跟着插件设置走: 组装那一刻的那一份与这里读的是同一个文件
            bubbleSp = PluginStore.manifest(context, pluginId)
                ?.let { manifest ->
                    (PluginSettings.values(context, manifest)[WidgetChat.KEY_BUBBLE_SP] as? JsonPrimitive)
                        ?.longOrNull
                        ?.toInt()
                }
                ?: DEFAULT_BUBBLE_SP
        }

        override fun onDestroy() {
            lines = emptyList()
        }

        override fun getCount(): Int {
            Log.i(TAG, "getCount -> ${lines.size}")
            return lines.size
        }

        override fun getViewAt(position: Int): RemoteViews? {
            val line = lines.getOrNull(position) ?: return null
            val mine = line.who == DesktopChatFile.WHO_USER
            return RemoteViews(
                context.packageName,
                if (mine) R.layout.lw_widget_slot_chat_item_user else R.layout.lw_widget_slot_chat_item_assistant,
            ).apply {
                setTextViewText(R.id.lw_chat_bubble_text, line.text)
                setTextViewTextSize(R.id.lw_chat_bubble_text, android.util.TypedValue.COMPLEX_UNIT_SP, bubbleSp.toFloat())
                // 每一项都不挂点击: 整格只有「说一句」与「清空」两颗有动作 (主人 2026-10-17 的口径)
                setViewVisibility(R.id.lw_chat_bubble_text, View.VISIBLE)
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        /** 两种气泡两份模板: user 靠右 / assistant 靠左 */
        override fun getViewTypeCount(): Int = 2
        /** 两种气泡两份模板 (RemoteViewsFactory 没有"按位置给类型"那一条: 类型数由这一句声明) */
        override fun hasStableIds(): Boolean = false

        override fun getItemId(position: Int): Long = position.toLong()

        private companion object {
            const val DEFAULT_BUBBLE_SP = 11
        }
    }
}



