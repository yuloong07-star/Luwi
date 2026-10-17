package io.github.yuloong07star.luwi.plugin.widget

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * `widget/widget.json` (方案 3.1) —— 插件往宿主槽里填的那一份内容
 *
 * 两个槽两种形状, 都用同一份解析:
 *
 * - `anim`: `frames` (基础那一套) + 可选的 `sets` (点一下换下一套)
 * - `image`: `title` / `text` / `image` / `buttons` (最多两颗)
 *
 * 这里只查"形状对不对" —— 路径解出来在不在插件目录里、逐帧图在不在包里, 由
 * [io.github.yuloong07star.luwi.plugin.PluginPaths] 在装的时候另查一遍 (运行时还会再兜一次)
 */
internal data class WidgetSpec(
    val slot: String,
    val name: String,
    val tap: String,
    val frames: Frames?,
    val sets: List<Frames>,
    val title: String,
    val text: String,
    val image: String,
    val buttons: List<Button>,
) {

    /** 一套帧: 目录 + 张数 + 每秒几张 */
    data class Frames(val key: String, val dir: String, val count: Int, val fps: Int) {
        /** 一张停多少毫秒 (`setFlipInterval` 收的就是它) */
        val intervalMs: Int get() = (1000 / fps).coerceAtLeast(1)

        /** 第 [index] 张在目录里的名字, 两遍校验与 provider 都用它 */
        fun frameName(index: Int): String = String.format(Locale.ROOT, "frame-%02d.png", index)

        fun frameNames(): List<String> = (0 until count).map { frameName(it) }
    }

    /** 图文槽的一颗按钮: `open` = 打开 Luwi, `tool` = 调插件自己的一条工具 */
    data class Button(val label: String, val action: String, val tool: String)

    /** `anim` 槽: 基础那一套排最前, `sets` 按声明顺序跟在后面 */
    fun animationSets(): List<Frames> = listOfNotNull(frames) + sets

    companion object {

        private val KEY_RULE = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
        private val TOOL_RULE = Regex("^[a-z][a-z0-9_]{1,31}$")
        private val ACTIONS = setOf("next", "open", "none")
        private val BUTTON_ACTIONS = setOf("open", "tool", "none")

        /**
         * 解析一份 `widget.json`; 不合法就抛一句点名的话 (与 `plugin.json` 那边同一个口径)
         *
         * @param where 出错那句话里点的名 (`widget/widget.json` 或者 `plugin.json 的 widget`)
         */
        fun parse(raw: JsonObject, where: String = "widget/widget.json"): WidgetSpec {
            val slot = raw.string("slot")
            if (!WidgetSlot.known(slot)) {
                throw IllegalArgumentException(
                    "$where 的 slot 只能是 ${WidgetSlot.PLUGIN_FILLED.joinToString(" / ")}, 这一份写的是 \"$slot\"",
                )
            }
            // chat 那一格**不收插件内容** (2.7.8 批次 6): 立绘取当前浮标外观, 气泡取那份
            // `lw/desktop-chat.json`, 而这两样都不在插件目录里 —— 收下一份 widget.json 也无处可画,
            // 于是当场说清楚, 而不是留一个"装上了但什么都不显示"的槽
            if (slot == WidgetSlot.CHAT) {
                throw IllegalArgumentException(
                    "$where 的 slot 不能是 chat: 那一格由应用自己画 (立绘取当前浮标外观, 气泡取" +
                        " lw/desktop-chat.json), 插件不往这一格填内容, 能填的是 ${WidgetSlot.PLUGIN_FILLED.joinToString(" / ")}",
                )
            }
            val name = raw.string("name")
            if (name.length > 40) throw IllegalArgumentException("$where 的 name 最多 40 个字, 这一份是 ${name.length} 个")
            val tap = raw.stringOrEmpty("tap").ifBlank { if (slot == WidgetSlot.ANIM) "next" else "none" }
            if (tap !in ACTIONS) {
                throw IllegalArgumentException("$where 的 tap 只能是 ${ACTIONS.joinToString(" / ")}, 这一份写的是 \"$tap\"")
            }

            val frames = (raw["frames"] as? JsonObject)?.let { one ->
                frames(one, "frames", "$where 的 frames")
            }
            val sets = (raw["sets"] as? JsonArray).orEmpty().mapIndexed { index, element ->
                val one = element as? JsonObject
                    ?: throw IllegalArgumentException("$where 的 sets[$index] 要是一个对象")
                val key = one.string("key")
                if (!KEY_RULE.matches(key)) {
                    throw IllegalArgumentException("$where 的 sets[$index].key 要匹配 [a-z0-9][a-z0-9-]{0,31}, 这一份写的是 \"$key\"")
                }
                if (key == "frames") {
                    throw IllegalArgumentException("$where 里 \"frames\" 这个名字留给基础那一套, sets 里换一个 key")
                }
                frames(one, key, "$where 的 sets[$index]")
            }
            if (sets.map { it.key }.toSet().size != sets.size) {
                throw IllegalArgumentException("$where 里有两个 set 用了同一个 key")
            }

            val title = raw.stringOrEmpty("title")
            val text = raw.stringOrEmpty("text")
            val image = raw.stringOrEmpty("image")
            val buttons = (raw["buttons"] as? JsonArray).orEmpty().mapIndexed { index, element ->
                val one = element as? JsonObject
                    ?: throw IllegalArgumentException("$where 的 buttons[$index] 要是一个对象")
                val label = one.string("label")
                if (label.length > 10) {
                    throw IllegalArgumentException("$where 的 buttons[$index].label 最多 10 个字, 这一份是 ${label.length} 个")
                }
                val action = one.stringOrEmpty("action").ifBlank { "open" }
                if (action !in BUTTON_ACTIONS) {
                    throw IllegalArgumentException(
                        "$where 的 buttons[$index].action 只能是 ${BUTTON_ACTIONS.joinToString(" / ")}, 这一份写的是 \"$action\"",
                    )
                }
                val tool = one.stringOrEmpty("tool")
                if (action == "tool") {
                    if (!TOOL_RULE.matches(tool)) {
                        throw IllegalArgumentException("$where 的 buttons[$index] 要调工具就得给 tool (例如 timer_start)")
                    }
                } else if (tool.isNotBlank()) {
                    throw IllegalArgumentException("$where 的 buttons[$index] 写了 tool 而 action 不是 tool, 两者要一致")
                }
                Button(label, action, tool)
            }
            if (buttons.size > 2) throw IllegalArgumentException("$where 的 buttons 最多两颗")

            if (slot == WidgetSlot.ANIM) {
                if (frames == null) throw IllegalArgumentException("$where 是 anim 槽, frames 那一段是必填的")
            } else {
                // 走到这里的只剩 image 槽 (chat 上面已经拒了)
                if (frames != null || sets.isNotEmpty()) {
                    throw IllegalArgumentException("$where 是 image 槽, frames / sets 那两段是 anim 槽用的")
                }
                if (title.isBlank() && text.isBlank() && image.isBlank() && buttons.isEmpty()) {
                    throw IllegalArgumentException("$where 是 image 槽, title / text / image / buttons 至少得有一样")
                }
            }
            return WidgetSpec(
                slot = slot,
                name = name,
                tap = tap,
                frames = frames,
                sets = sets,
                title = title,
                text = text,
                image = image,
                buttons = buttons,
            )
        }

        /** 一套帧那一段 (多了个 key 的差别由调用方给) */
        private fun frames(raw: JsonObject, key: String, where: String): Frames {
            val dir = raw.string("dir")
            val count = raw.int("count", where)
            val fps = raw.int("fps", where)
            if (count !in 1..240) throw IllegalArgumentException("$where 的 count 要在 1..240 之间, 这一份是 $count")
            if (fps !in 1..60) throw IllegalArgumentException("$where 的 fps 要在 1..60 之间, 这一份是 $fps")
            return Frames(key, dir, count, fps)
        }

        private fun JsonObject.string(key: String): String = stringOrEmpty(key).ifBlank {
            throw IllegalArgumentException("widget.json 缺 $key 这个字段")
        }

        private fun JsonObject.stringOrEmpty(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        /** 整数; 浮点在协议这一层就不许出现 (与 plugin.json 同一条) */
        private fun JsonObject.int(key: String, where: String): Int {
            val value = this[key] as? JsonPrimitive
                ?: throw IllegalArgumentException("$where 缺 $key 这个字段")
            val number = value.longOrNull
                ?: throw IllegalArgumentException("$where 的 $key 要是整数, 这一份写的是 ${value.content}")
            if (number !in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) {
                throw IllegalArgumentException("$where 的 $key 太大了")
            }
            return number.toInt()
        }
    }
}
