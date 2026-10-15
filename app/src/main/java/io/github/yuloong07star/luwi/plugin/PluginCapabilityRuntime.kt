package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.os.Bundle
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults
import io.github.yuloong07star.luwi.plugin.widget.WidgetHost
import io.github.yuloong07star.luwi.plugin.widget.WidgetPush
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import io.github.yuloong07star.luwi.tool.LwNotify
import io.github.yuloong07star.luwi.tool.LwSpeak
import io.github.yuloong07star.luwi.tool.LwSystem
import io.github.yuloong07star.luwi.voice.VoiceInbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 能力真正干活的地方 (协议第 5 节里"接通"的那七条)
 *
 * 一条能力底下是**已经做过的那件事**: `device.read` 就是电池那个读数, `notify.post` 就是 `lw_notify`,
 * `session.post` 就是语音那条投递队列。不重写一遍的理由与 `lw_files` 那一条同一个: 同一件事有两份
 * 实现, 迟早会漂开
 *
 * 没接通的那几条由 [LwPluginContextBinder] 在更外一层拦住 (它先说"这一版还没接通"), 到不了这里
 */
internal object PluginCapabilityRuntime {

    /** 读插件自己目录里的文件时一次最多读多少 */
    private const val MAX_OWN_READ = 256 * 1024

    fun call(context: Context, manifest: PluginManifest, capability: String, args: Bundle): Bundle =
        when (capability) {
            "device.read" -> sentence(LwSystem.battery(context))
            "sensor.read" -> sentence(
                LwSystem.sensor(
                    context,
                    buildJsonObject {
                        args.getString("name")?.let { put("name", it) }
                        args.getInt("timeoutMs", 0).takeIf { it > 0 }?.let { put("timeoutMs", it) }
                    },
                ),
            )
            "notify.post" -> {
                val text = args.getString("text").orEmpty()
                if (text.isBlank()) {
                    LwPluginResults.fail("notify.post 得给一句 text")
                } else {
                    sentence(
                        LwNotify.notify(
                            context,
                            buildJsonObject {
                                put("title", args.getString("title")?.takeIf { it.isNotBlank() } ?: manifest.name)
                                put("text", text)
                            },
                        ),
                    )
                }
            }
            "speech.speak" -> {
                val text = args.getString("text").orEmpty()
                if (text.isBlank()) {
                    LwPluginResults.fail("speech.speak 得给一句 text")
                } else {
                    sentence(LwSpeak.dispatch(context, buildJsonObject { put("op", "speak"); put("text", text) }))
                }
            }
            "clipboard" -> {
                val op = args.getString("op") ?: "get"
                if (op != "get" && op != "set") {
                    LwPluginResults.fail("clipboard 的 op 只能是 get 或 set")
                } else {
                    sentence(
                        LwNotify.clipboard(
                            context,
                            buildJsonObject {
                                put("op", op)
                                args.getString("text")?.let { put("text", it) }
                            },
                        ),
                    )
                }
            }
            "files.own" -> own(context, manifest, args)
            // 桌面那几格的内容 (方案 3.3): 带代码的域可以自己推 —— 番茄钟那种"每秒都要变"的组件
            // 于是不需要伴侣 APK
            "ui.widget" -> pushWidget(context, manifest, args)
            "ui.theme" -> theme(context, manifest)
            "session.post" -> {
                val text = args.getString("text").orEmpty()
                if (text.isBlank()) {
                    LwPluginResults.fail("session.post 得给一句 text")
                } else {
                    val seq = VoiceInbox.append(context, text, source = "plugin:${manifest.id}")
                    if (seq == null) {
                        LwPluginResults.fail("投递队列写不进去 (目录建不出来或者磁盘满了)")
                    } else {
                        LwPluginResults.ok("已经把「${text.take(40)}」投进会话 (队列第 $seq 条)")
                    }
                }
            }
            // G3 能力沙盒 (2026-10-12): 两条带参数的能力, 边界就是声明里那一截
            else -> when {
                capability.startsWith("files.read:") -> PluginCapabilitySandbox.filesRead(context, capability, args)
                capability.startsWith("net.http:") -> PluginCapabilitySandbox.netHttp(capability, args)
                else -> LwPluginResults.fail("\"$capability\" 这一版还没接通")
            }
        }

    /** 只读自己那个插件目录: 路径不许跑出去, 单次读取有上限 */
    private fun own(context: Context, manifest: PluginManifest, args: Bundle): Bundle {
        val directory = PluginStore.packageDir(context, manifest.id)
            ?: return LwPluginResults.fail("插件目录现在不在了")
        val op = args.getString("op") ?: "list"
        val requested = args.getString("path").orEmpty().trim('/')
        if (requested.contains("..")) return LwPluginResults.fail("路径不许带 ..")
        val target = if (requested.isEmpty()) directory else File(directory, requested)
        if (!target.canonicalPath.startsWith(directory.canonicalPath)) {
            return LwPluginResults.fail("只能读自己目录里的东西")
        }
        return when (op) {
            "list" -> {
                if (!target.isDirectory) return LwPluginResults.fail("$requested 不是一个目录")
                val rows = target.listFiles()?.sortedBy { it.name }?.map {
                    if (it.isDirectory) "${it.name}/" else "${it.name} (${it.length()} 字节)"
                }.orEmpty()
                LwPluginResults.ok(rows.joinToString("\n").ifBlank { "(空)" })
            }
            "read" -> {
                if (!target.isFile) return LwPluginResults.fail("$requested 不是一个文件")
                if (target.length() > MAX_OWN_READ) {
                    return LwPluginResults.fail("$requested 有 ${target.length()} 字节, 超过一次能读的 $MAX_OWN_READ")
                }
                LwPluginResults.ok(target.readText())
            }
            else -> LwPluginResults.fail("files.own 的 op 只能是 list 或 read")
        }
    }

    /** `lw_*` 那套工具回的都是 `{"text": …}`; 能力这一侧只要那一句话 */
    private fun sentence(json: JsonObject): Bundle =
        LwPluginResults.ok(json["text"]?.jsonPrimitive?.contentOrNull ?: json.toString())

    /**
     * `ui.widget`: 把标题 / 正文 / 图 / 按钮推给桌面那几格
     *
     * 一个字段都不给 = **清掉之前推的那一份**, 回到包里 `widget.json` 那一段 (于是"临时推一个状态"
     * 与"回到静态样子"都有路走)
     */
    private fun pushWidget(context: Context, manifest: PluginManifest, args: Bundle): Bundle {
        val content = WidgetPush.Content(
            label = args.getString("label").orEmpty().take(40),
            title = args.getString("title").orEmpty().take(60),
            text = args.getString("text").orEmpty().take(300),
            image = args.getString("image").orEmpty().take(200),
            buttons = widgetButtons(args),
        )
        if (content.label.isBlank() && content.title.isBlank() && content.text.isBlank() &&
            content.image.isBlank() && content.buttons.isEmpty()
        ) {
            WidgetPush.clear(context, manifest.id)
            WidgetHost.refreshHolding(context, manifest.id)
            return LwPluginResults.ok("已经把推上去的那一份清掉, 回到包里那一份")
        }
        WidgetPush.write(context, manifest.id, content)
        WidgetHost.refreshHolding(context, manifest.id)
        return LwPluginResults.ok("已经把内容推给桌面上那几格")
    }

    /** 图文槽那两颗按钮: 一串 `{label, action, tool}`; 不合法的那一颗直接拒, 不静默丢 */
    @Suppress("DEPRECATION")
    private fun widgetButtons(args: Bundle): List<WidgetPush.Content.Button> {
        val raw = args.getParcelableArrayList<Bundle>("buttons") ?: return emptyList()
        if (raw.size > 2) throw IllegalArgumentException("桌面上最多两颗按钮")
        return raw.map { one ->
            val label = one.getString("label").orEmpty().take(10)
            val action = one.getString("action").orEmpty().ifBlank { "open" }
            val tool = one.getString("tool").orEmpty().take(32)
            if (action !in setOf("open", "tool", "none")) {
                throw IllegalArgumentException("按钮的 action 只能是 open / tool / none, 这一颗写的是 \"$action\"")
            }
            if (action == "tool" && !Regex("^[a-z][a-z0-9_]{1,31}$").matches(tool)) {
                throw IllegalArgumentException("按钮要调工具就得给 tool (例如 timer_start)")
            }
            WidgetPush.Content.Button(label, action, tool)
        }
    }

    /**
     * `ui.theme`: 浮标外观 (协议 10.1)
     *
     * 它是一件**被动**的能力: 外观是主人在设置页那一行里挑的, 插件调不出"给我换上" —— 所以这里
     * 如实说清"现在用哪一套、去哪儿换", 不假装自己掌握着开关
     */
    private fun theme(context: Context, manifest: PluginManifest): Bundle {
        val active = BallTheme.currentId(context)
        if (active == manifest.id) {
            val name = BallTheme.active(context)?.spec?.name.orEmpty().ifBlank { manifest.name }
            return LwPluginResults.ok("现在用的就是它的外观「$name」")
        }
        val current = active.takeIf { it.isNotBlank() }
            ?.let { id -> PluginStore.manifest(context, id)?.name ?: id }
            ?: "内置画法"
        return LwPluginResults.ok(
            "${manifest.name} 带了浮标外观; 现在用的是「$current」—— 在设置页「浮标外观」那一行里挑",
        )
    }
}
