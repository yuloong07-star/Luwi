package io.github.yuloong07star.luwi.sample.whalewidget

import android.content.Context

/**
 * 桌面上现在用的是哪一套
 *
 * 记的是**键**而不是序号: 主人导进来一套新的之后, 后面那几套的序号都会挪, 而"正在用的是哪一套"
 * 不该跟着挪
 *
 * 这一份偏好三处共用 (桌面组件 / 工具那条路 / 伴侣自己的界面), 于是"点一下换一套"与"模型换一套"
 * 改的是同一本账
 */
internal object WhaleStore {

    private const val FILE = "whale-widget"
    private const val KEY_CURRENT = "current"

    /** 现在用的是哪一套的键 */
    fun current(context: Context): String =
        prefs(context).getString(KEY_CURRENT, WhaleDances.bundledKeys.first()).orEmpty()

    /** 现在用的是哪一套 */
    fun dance(context: Context): WhaleDance = WhaleDances.find(context, current(context))

    fun setCurrent(context: Context, key: String) {
        prefs(context).edit().putString(KEY_CURRENT, key).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
