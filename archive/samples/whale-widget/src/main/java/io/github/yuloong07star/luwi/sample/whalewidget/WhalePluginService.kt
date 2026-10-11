package io.github.yuloong07star.luwi.sample.whalewidget

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import io.github.yuloong07star.luwi.plugin.api.ILwPlugin
import io.github.yuloong07star.luwi.plugin.api.ILwPluginContext
import io.github.yuloong07star.luwi.plugin.api.LwPluginApi
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults

/**
 * 伴侣的本体 —— 与 `:sample-companion` 同一副骨架, 只是工具换成"切桌面上那一只鲸鱼娘"
 *
 * **它一条 LW 能力都不要**: 小组件画的是自己的动图 (随包那三套与主人导进来的那几份), 切哪一套也是
 * 本地的偏好, 一件设备侧的权限都不需要。于是它是"零能力伴侣"那一档的样例 —— 装上就能用, 没有
 * 一个勾选框
 *
 * 三条工具各演示一条路: 读账 (`whale_list`)、改账 (`whale_next` / `whale_set`) —— 后两条改完都会
 * 顺手把桌面上那一只重画, 于是模型切完, 人低头看桌面就已经是新的了
 *
 * **导入不在这三条工具里**: 那一份要人去系统文件选择器里挑一个文件, 是界面上的事 (见 [WhaleScreen])
 */
class WhalePluginService : Service() {

    override fun onBind(intent: Intent?): IBinder = plugin

    private val plugin = object : ILwPlugin() {

        private var context: ILwPluginContext? = null
        private var directory: String = ""
        private var lwVersion: String = ""

        override fun describe(): Bundle = Bundle().apply {
            putString(LwPluginApi.Plugin.ID, ID)
            putString(LwPluginApi.Plugin.NAME, "鲸鱼娘桌面小组件")
            putString(LwPluginApi.Plugin.VERSION, VERSION)
            putString(LwPluginApi.Plugin.API, LwPluginApi.API_VERSION.toString())
            putStringArrayList(LwPluginApi.Plugin.TOOLS, arrayListOf(TOOL_LIST, TOOL_NEXT, TOOL_SET))
            // 一条能力都不要: 这一份插件的活全在它自己那个 APK 里
            putStringArrayList(LwPluginApi.Plugin.CAPABILITIES, arrayListOf())
            putStringArrayList(LwPluginApi.Plugin.DANGEROUS, arrayListOf())
        }

        override fun lifecycle(event: String, args: Bundle?): Bundle = when (event) {
            LwPluginApi.Event.ATTACH -> {
                context = ILwPluginContext.asInterface(args?.getBinder(LwPluginApi.Attach.CONTEXT))
                directory = args?.getString(LwPluginApi.Attach.DIR).orEmpty()
                lwVersion = args?.getString(LwPluginApi.Attach.LW).orEmpty()
                LwPluginResults.ok("鲸鱼娘挂上了, 目录 $directory, 对面的 LW 是 $lwVersion")
            }
            LwPluginApi.Event.ENABLE -> {
                // 起这一条只是为了让已经放在桌面上的那只立刻对齐到现在的账, 不然后台换过一套要等宿主自己刷
                WhaleWidgetProvider.refresh(this@WhalePluginService)
                LwPluginResults.ok("鲸鱼娘开始在岗, 桌面上那一只是「${WhaleStore.dance(this@WhalePluginService).title}」")
            }
            LwPluginApi.Event.DISABLE -> LwPluginResults.ok("鲸鱼娘下班了, 桌面上那一只留着不动")
            LwPluginApi.Event.DETACH -> {
                context = null
                LwPluginResults.ok("摘下来了")
            }
            else -> LwPluginResults.fail("不认识这个事件: $event")
        }

        override fun invoke(tool: String, args: Bundle?): Bundle = when (tool) {
            TOOL_LIST -> LwPluginResults.ok(catalog())
            TOOL_NEXT -> {
                val chosen = WhaleWidgetProvider.next(this@WhalePluginService)
                LwPluginResults.ok("桌面上换成「${chosen.title}」了 (第 ${position(chosen)} 套, 共 ${WhaleDances.catalog(this@WhalePluginService).size} 套)")
            }
            TOOL_SET -> {
                val token = args?.getString("which").orEmpty()
                val index = WhaleDances.indexOf(this@WhalePluginService, token)
                if (index < 0) {
                    LwPluginResults.fail(
                        "哪一套? 认这些键 (见 whale_list), 也认从 1 起的序号 —— 收到的是「$token」",
                    )
                } else {
                    val chosen = WhaleDances.at(this@WhalePluginService, index)
                    WhaleWidgetProvider.select(this@WhalePluginService, chosen.key)
                    LwPluginResults.ok("桌面上换成「${chosen.title}」了")
                }
            }
            else -> LwPluginResults.fail("鲸鱼娘没有这个工具: $tool")
        }

        private fun position(dance: WhaleDance): Int =
            WhaleDances.catalog(this@WhalePluginService).indexOfFirst { it.key == dance.key } + 1

        /** 现在能切到那几套都摆出来, 正在用的那套点一个记号 —— 模型看这一句就知道能切到哪去 */
        private fun catalog(): String {
            val all = WhaleDances.catalog(this@WhalePluginService)
            val key = WhaleStore.current(this@WhalePluginService)
            return all.mapIndexed { index, dance ->
                val mark = if (dance.key == key) "← 正在桌面" else ""
                val where = if (dance.imported != null) "导入" else "随包"
                "${index + 1}. ${dance.key}　${dance.title} ($where, ${dance.frames} 帧) $mark".trim()
            }.joinToString("\n")
        }
    }

    companion object {
        const val ID = "io.github.yuloong07star.luwi.sample.whalewidget"
        const val VERSION = "1.1.4"
        const val TOOL_LIST = "whale_list"
        const val TOOL_NEXT = "whale_next"
        const val TOOL_SET = "whale_set"
    }
}
