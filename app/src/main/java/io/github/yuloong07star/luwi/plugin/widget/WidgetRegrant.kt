package io.github.yuloong07star.luwi.plugin.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 解锁 / 覆盖安装之后补一轮 (方案 3.1 第 2 条)
 *
 * `grantUriPermission` 发出去的是一条**内存里的**授权 (ActivityManager 那一份, 不落盘): 重启一次、
 * 或者桌面重新 inflate 一遍, 它就可能没了 —— 空了的样子与"插件被卸了"长得一样, 而人会先以为是 bug
 *
 * 所以收到这三条中的任何一条, 就把每个槽重画一遍: 重画那一趟自己会把读权限重放一次。**它不起
 * host, 也不做开机自启** —— 只负责把桌面上那几格重新点亮, 与"这一版监测不自启"那条设计不冲突
 */
class WidgetRegrant : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.i(TAG, "补一轮授权与重画 (${intent.action})")
                WidgetHost.refreshAll(context)
            }
        }
    }

    private companion object {
        const val TAG = "LwWidgetRegrant"
    }
}
