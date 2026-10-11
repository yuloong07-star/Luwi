package io.github.yuloong07star.luwi.plugin.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.yuloong07star.luwi.plugin.PluginManager

/**
 * 两个宿主槽的 `AppWidgetProvider` (方案 3.1)
 *
 * 组件的名字与版式是**主 APK 编译期定的**, 插件只能往里填内容 —— 这是 Android 的限制, 不是没做。
 * 也正因为组件住在主 APK 里, "装完即用"这条路上不再需要第二个 APK
 */
open class WidgetSlotBase internal constructor(private val slot: String) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, WidgetHost.views(context, slot)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetHost.ACTION_NEXT) {
            WidgetHost.nextSet(context, WidgetSlots.holder(context, slot))
            WidgetHost.refresh(context, slot)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        // 桌面上最后一格被移走时这里什么都不用做: 槽的映射留在 .widgets.json 里, 下次放上去还是它
    }
}

/** 动画槽那一格 (组件选择器里叫「Luwi 动画组件」) */
class WidgetSlotAnim : WidgetSlotBase(WidgetSlot.ANIM)

/** 图文槽那一格 (组件选择器里叫「Luwi 图文组件」) */
class WidgetSlotImage : WidgetSlotBase(WidgetSlot.IMAGE)

/**
 * 图文槽那两颗按钮里 `action: "tool"` 那一支的路口
 *
 * 它从桌面那一侧被点出来, 跑在广播接收器里 —— 插件工具可能要走一次 binder, 所以不在主线程上做,
 * 做完把挂着这个插件的槽重画一次 (番茄钟按一下"开始", 卡片上的时间就该动)
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetHost.ACTION_TOOL) return
        val id = intent.getStringExtra(WidgetHost.EXTRA_ID).orEmpty()
        val tool = intent.getStringExtra(WidgetHost.EXTRA_TOOL).orEmpty()
        if (id.isBlank() || tool.isBlank()) return
        val pending = goAsync()
        Thread {
            try {
                PluginManager.invoke(context, id, tool, Bundle())
            } catch (error: Throwable) {
                Log.w(TAG, "桌面上点的那颗按钮没走通: $id / $tool", error)
            } finally {
                WidgetHost.refreshHolding(context, id)
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "LwWidgetAction"
    }
}
