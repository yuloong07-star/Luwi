package io.github.yuloong07star.luwi.plugin

import android.content.Context
import io.github.yuloong07star.luwi.plugin.PluginStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 插件自己的设置项 (协议第 10 节 `ui.settings`, 2.7.8 批次 6 的后半)
 *
 * 插件在 `plugin.json` 里**声明**它有哪些设置, 而值由主人在设置页里调 —— 插件本身一行代码都跑不了
 * (`art` 域), 于是"设置"这件事只有两半: 声明 (清单里) 与值 (落在插件自己那一块盘上)
 *
 * 声明那条形状 (协议里早就写了, 这一批才接通):
 *
 * ```json
 * "ui": { "settings": [
 *   { "key": "bubbleSp", "type": "int", "default": 11, "title": "气泡字号", "min": 9, "max": 18 },
 *   { "key": "face", "type": "enum", "default": "ball", "title": "显示的人物",
 *     "options": [ { "value": "ball", "label": "与浮标相同外观" } ] }
 * ] }
 * ```
 *
 * 四种类型: `int` (可给 `min` / `max`) / `bool` / `enum` (要 `options`) / `string`。
 * **值一律先夹一遍再落盘** ([normalize]) —— 插件升级之后把范围改了, 盘上那个旧值不许让界面画一个
 * 不可能的档位
 *
 * 值落 `$DSH_HOME/plugins/<id>/.data/ui-settings.json` (与 `widget-push.json` 同一个目录): 卸载时
 * "连数据一起删"动的是同一个目录, 于是"停用/卸载"与"设置"不必各有一套存储
 */
internal object PluginSettings {

    const val FILE = "ui-settings.json"

    /** 一条声明: 类型 + 缺省 + 界面上的名字 (可选的上下限与候选项) */
    internal data class Setting(
        val key: String,
        val type: String,
        val title: String,
        val defaultValue: JsonElement,
        val min: Long?,
        val max: Long?,
        val options: List<Option>,
    ) {
        internal data class Option(val value: String, val label: String)
    }

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    private val KEY_RULE = Regex("^[a-z][a-zA-Z0-9]{0,31}$")
    val TYPES = setOf("int", "bool", "enum", "string")

    /**
     * 解析 `ui.settings` 那一串; 不合法就抛一句点名的话
     *
     * 纯解析 (不碰磁盘), 于是"哪一条写得不对"这件事没有设备也能量 (见 PluginUiTest)
     */
    internal fun parse(array: JsonArray?): List<Setting> = array.orEmpty().mapIndexed { index, element ->
        val one = element as? JsonObject
            ?: throw IllegalArgumentException("ui.settings[$index] 要是一个对象")
        val key = (one["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (!KEY_RULE.matches(key)) {
            throw IllegalArgumentException("ui.settings[$index].key 要匹配 [a-z][a-zA-Z0-9]{0,31}, 这一份写的是 \"$key\"")
        }
        val type = (one["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (type !in TYPES) {
            throw IllegalArgumentException("ui.settings[$index].type 只能是 ${TYPES.joinToString(" / ")}, 这一份写的是 \"$type\"")
        }
        val title = (one["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (title.isBlank() || title.length > 24) {
            throw IllegalArgumentException("ui.settings[$index].title 要 1..24 个字 (界面上那一行就是它)")
        }
        val unknown = one.keys - setOf("key", "type", "title", "default", "min", "max", "options")
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("ui.settings[$index] 有协议不认的键: ${unknown.joinToString(", ")}")
        }
        val default = one["default"] ?: throw IllegalArgumentException("ui.settings[$index] 要给 default")
        val min = (one["min"] as? JsonPrimitive)?.longOrNull
        val max = (one["max"] as? JsonPrimitive)?.longOrNull
        val options = (one["options"] as? JsonArray).orEmpty().mapIndexed { at, entry ->
            val option = entry as? JsonObject
                ?: throw IllegalArgumentException("ui.settings[$index].options[$at] 要是一个对象")
            val value = (option["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (value.isBlank()) {
                throw IllegalArgumentException("ui.settings[$index].options[$at] 缺 value")
            }
            val label = (option["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            Setting.Option(value, label.ifBlank { value })
        }
        val setting = Setting(key, type, title, default, min, max, options)
        // 缺省值先自己过一遍: 一份自己都夹不住缺省的声明, 装的时候就该拒。
        // **整数另外查一道**: [normalize] 对整数是"夹"而不是"拒", 于是一个 `default: 99` 配
        // `max: 10` 会被悄悄夹成 10 —— 那份声明自己就是矛盾的, 该在装的时候说出来
        normalize(setting, default)
            ?: throw IllegalArgumentException("ui.settings[$index] 的 default 不是合法的 $type")
        if (type == "int") {
            val number = (default as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("ui.settings[$index] 的 default 要是整数")
            if (min != null && number < min) {
                throw IllegalArgumentException("ui.settings[$index] 的 default $number 小于 min $min")
            }
            if (max != null && number > max) {
                throw IllegalArgumentException("ui.settings[$index] 的 default $number 大于 max $max")
            }
        }
        if (type == "enum" && options.isEmpty()) {
            throw IllegalArgumentException("ui.settings[$index] 是 enum, options 那一串不能是空的")
        }
        setting
    }.also { list ->
        if (list.map { it.key }.toSet().size != list.size) {
            throw IllegalArgumentException("ui.settings 里有两条用了同一个 key")
        }
    }

    /**
     * 把一个值夹成这条声明允许的形状; 夹不住就回 null (调用方退回缺省)
     *
     * 纯函数: 界面读值、工具读值、插件升级之后读旧值都走它, 于是"盘上那个值还合不合法"只有一处判断
     */
    internal fun normalize(setting: Setting, value: JsonElement?): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        return when (setting.type) {
            "int" -> {
                val number = primitive.longOrNull ?: return null
                val low = setting.min ?: Long.MIN_VALUE
                val high = setting.max ?: Long.MAX_VALUE
                JsonPrimitive(number.coerceIn(low, high))
            }
            "bool" -> JsonPrimitive(primitive.booleanOrNull ?: return null)
            "enum" -> {
                val text = primitive.takeIf { it.isString }?.content ?: return null
                val hit = setting.options.firstOrNull { it.value == text } ?: return null
                JsonPrimitive(hit.value)
            }
            else -> JsonPrimitive(primitive.takeIf { it.isString }?.content ?: return null)
        }
    }

    private fun file(context: Context, id: String): File = File(PluginStore.dataDir(context, id), FILE)

    /** 盘上存着的那一份 (只读; 读不懂就是空表) */
    internal fun stored(context: Context, id: String): Map<String, JsonElement> {
        val file = file(context, id)
        if (!file.isFile) return emptyMap()
        if (!file.canonicalPath.startsWith(PluginStore.root(context).canonicalPath)) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val values = root["values"] as? JsonObject ?: return emptyMap()
        return values.toMap()
    }

    /**
     * 一个插件现在的设置: **声明里的缺省 + 盘上那个值** (盘上的先按声明夹一遍)
     *
     * 读不到 / 没存过 / 旧值不合法, 一律落回缺省 —— 界面上永远有一个能画的状态
     */
    internal fun values(context: Context, manifest: PluginManifest): Map<String, JsonElement> {
        val stored = stored(context, manifest.id)
        return manifest.settings.associate { setting ->
            setting.key to (normalize(setting, stored[setting.key]) ?: setting.defaultValue)
        }
    }

    /** 改一个键 (值先按声明夹一道; 夹不住就当没改, 回一句给人看的话) */
    internal fun set(context: Context, manifest: PluginManifest, key: String, value: JsonElement): String {
        val setting = manifest.settings.firstOrNull { it.key == key }
            ?: return "这个插件没有 \"$key\" 这一项设置"
        val normalized = normalize(setting, value)
            ?: return "${setting.title} 只收 ${typeName(setting)} 这一种值"
        val next = HashMap(stored(context, manifest.id))
        next[key] = normalized
        val directory = PluginStore.dataDir(context, manifest.id)
        if (!directory.isDirectory && !directory.mkdirs()) return "写不进插件自己那块目录"
        val payload = JsonObject(mapOf("v" to JsonPrimitive(1), "values" to JsonObject(next)))
        return try {
            val temporary = File(directory, "$FILE.tmp")
            temporary.writeText("$payload\n")
            temporary.renameTo(file(context, manifest.id))
            "${setting.title} 改成了 $normalized"
        } catch (error: Throwable) {
            "写不进设置: ${error.message ?: error}"
        }
    }

    /** 那一档在界面上叫什么 (给回执那句话用) */
    internal fun typeName(setting: Setting): String = when (setting.type) {
        "int" -> "${setting.min ?: 0}~${setting.max ?: "∞"} 的整数"
        "bool" -> "开 / 关"
        "enum" -> setting.options.joinToString(" / ") { it.label }
        else -> "一段文字"
    }
}
