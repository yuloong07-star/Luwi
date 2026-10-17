package io.github.yuloong07star.luwi.plugin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.github.yuloong07star.luwi.R

/**
 * 「装完即用」最后那一下 (方案 3.1): `requestPinAppWidget` 直接出系统那张"添加到主屏幕"的确认卡
 *
 * 一手点到位, 不用长按桌面 → 小组件 → 翻半天。不支持的桌面 (vivo 那套要实测) 退回那句老话术 ——
 * 这里如实说"这台桌面不支持", 不装作点了就有反应
 */
internal object WidgetPin {

    fun supported(context: Context): Boolean = try {
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    } catch (error: Throwable) {
        false
    }

    /** 请系统弹那张卡; 回的是一句给人看的话 */
    fun request(context: Context, slot: String): String {
        val label = slotLabel(context, slot)
        if (!supported(context)) {
            return "这台设备的桌面不支持直接放组件, 长按桌面 → 小组件 里找「$label」"
        }
        val manager = AppWidgetManager.getInstance(context)
        val asked = runCatching {
            manager.requestPinAppWidget(ComponentName(context, WidgetHost.receiverOf(slot)), null, null)
        }.getOrDefault(false)
        return if (asked) {
            "系统那张「添加到主屏幕」的卡已经弹出来了, 点一下就把「$label」放上去"
        } else {
            "系统没有接受这次放组件的请求, 长按桌面 → 小组件 里找「$label」"
        }
    }

    fun slotLabel(context: Context, slot: String): String = context.getString(
        when (slot) {
            WidgetSlot.ANIM -> R.string.settings_widget_slot_anim_name
            WidgetSlot.CHAT -> R.string.settings_widget_slot_chat_name
            else -> R.string.settings_widget_slot_image_name
        },
    )
}
