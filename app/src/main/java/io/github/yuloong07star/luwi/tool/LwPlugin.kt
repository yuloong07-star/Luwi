package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.os.Bundle
import io.github.yuloong07star.luwi.host.HostSettings
import io.github.yuloong07star.luwi.plugin.PluginAudit
import io.github.yuloong07star.luwi.plugin.PluginCapabilities
import io.github.yuloong07star.luwi.plugin.PluginCompanion
import io.github.yuloong07star.luwi.plugin.PluginDownload
import io.github.yuloong07star.luwi.plugin.PluginInstaller
import io.github.yuloong07star.luwi.plugin.PluginManager
import io.github.yuloong07star.luwi.plugin.PluginSignature
import io.github.yuloong07star.luwi.plugin.PluginStore
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * LW 插件的那一张脸 (协议第 6 节那条管理工具 + 第 7 节的那几个动作)
 *
 * 与快捷指令、自动指令同一个形状 (app 进程里做 → 桥 → 宿主一条工具), 差别在这一件是**生态的入口**:
 * 装 / 启用 / 停用 / 卸 / 授权都在这里, 而它是唯一一处能碰 `$DSH_HOME/plugins/` 的代码
 *
 * 七条 op 模型都能调 (`install` 是"把一份包收进来"那件事, 危险的是包里的内容而不是这一步);
 * `snapshot` 只在桥上有 —— 宿主拿它决定注册哪些工具, 那不是一个给人用的动作
 */
internal object LwPlugin {

    /** 目录名: 与设置页、提示词、工具说明里说的都是它 */
    const val DIRECTORY = PluginStore.DIRECTORY

    /** op 名单, **插件那边念给模型的与这一份不许漂开** */
    val OPERATIONS = listOf("list", "read", "install", "enable", "disable", "uninstall", "audit")

    /** 桥多一条 [OPERATIONS] 里没有的: 宿主拉快照用它 */
    const val OP_SNAPSHOT = "snapshot"

    /**
     * 桥还多一条 [OPERATIONS] 里没有的: **授权一条能力**
     *
     * 它只在桥上有是刻意的 —— 能力授权是"人在设置页上勾"那一件事 (设置页直接调
     * [PluginManager.grant]), 不该变成模型随手能替主人答应的一个 op。它留在桥上是因为验收与
     * `tools/lw-bridge.ps1` 要能把这一条跑一遍
     */
    const val OP_GRANT = "grant"

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "list" -> list(context)
        "read" -> read(context, request)
        "install" -> buildJsonObject { put("text", installFrom(context, request)) }
        "enable" -> buildJsonObject { put("text", PluginManager.enable(context, request.string("id"))) }
        "disable" -> buildJsonObject { put("text", PluginManager.disable(context, request.string("id"))) }
        "uninstall" -> buildJsonObject {
            put(
                "text",
                PluginManager.uninstall(context, request.string("id"), request.bool("keepData", false)),
            )
        }
        "audit" -> audit(context, request)
        OP_SNAPSHOT -> PluginManager.snapshot(context)
        OP_GRANT -> buildJsonObject {
            put(
                "text",
                PluginManager.grant(
                    context,
                    request.string("id"),
                    request.string("capability"),
                    request.bool("allowed", true),
                ),
            )
        }
        // 宿主那侧转发过来的一次插件工具调用: 参数是一份 JSON, 里层是那条工具自己的参数
        "invoke" -> invoke(context, request)
        else -> throw IllegalArgumentException("op has to be ${OPERATIONS.joinToString(", ")}, not \"$op\"")
    }

    /** 开发者模式: 未签名的本地包只在它开着的时候能被收进来 */
    fun developerMode(context: Context): Boolean = HostSettings.developerPlugins(context)

    fun setDeveloperMode(context: Context, enabled: Boolean) = HostSettings.setDeveloperPlugins(context, enabled)

    /**
     * "装哪一份": 手机上的一个路径, 或者一个 https 链接 (协议第 13 节)
     *
     * 两条在 [install] 之后是**同一条链** —— 从链接装不比从本地装少验一样东西。从链接下的那一份是
     * cache 里的临时文件, 装完 (或者没装成) 都当场删
     */
    private fun installFrom(context: Context, request: JsonObject): String {
        val url = request.stringOrNull("url")?.trim().orEmpty()
        val path = request.stringOrNull("path")?.trim().orEmpty()
        if (url.isNotEmpty() && path.isNotEmpty()) {
            throw IllegalArgumentException("装一份包要么给 path 要么给 url, 两个一起给等于没说要装哪一份")
        }
        if (url.isEmpty() && path.isEmpty()) {
            throw IllegalArgumentException("装一份包要说清是哪一份: 手机上的 path, 或者一个 https 的 url")
        }
        if (url.isEmpty()) return install(context, File(path))
        val fetched = PluginDownload.fetch(context, url)
        return try {
            install(context, fetched)
        } finally {
            fetched.delete()
        }
    }

    /**
     * 装一份包 (设置页与桥走的是**同一条链**): 不经手那边, 失败就抛
     *
     * 与 [install] 分开是因为设置页要在"装"与"启用"之间插两件事 (按勾选授权、把伴侣 APK 也装上),
     * 而桥那条路没有界面, 只能一口做完
     */
    fun installPackage(context: Context, source: File): PluginInstaller.Outcome =
        PluginInstaller.install(context, source, developerMode(context))

    /** 把一次装入的结果念成一句给人看的话 (**不含**"伴侣那一半"那一段) */
    fun describe(outcome: PluginInstaller.Outcome): String = buildString {
        append("装上了 ${outcome.name} ${outcome.version} (${outcome.id})")
        append(
            if (outcome.signed) {
                ", 发布者指纹 ${outcome.fingerprint.removePrefix("sha256:").take(16)}"
            } else {
                ", **没有签名**"
            },
        )
        if (outcome.newPublisher) append("。这是一个新发布者, 第一次见这把公钥")
    }

    /**
     * 装一份包 (桥那条路), 回一句给人看的话
     *
     * 伴侣那一半在这里只允许**静默装** (`allowSystemInstaller = false`): 这条路跑在后台, 没有人会
     * 去安装器那一页点那一下。静默装成不成都如实写进这句话里
     */
    fun install(context: Context, source: File): String {
        val outcome = try {
            installPackage(context, source)
        } catch (error: Throwable) {
            return "装不上: ${error.message ?: error.javaClass.simpleName}"
        }
        return buildString {
            append(describe(outcome))
            val companion = PluginCompanion.ensure(
                context,
                outcome.entryPackage,
                outcome.entryApk,
                allowSystemInstaller = false,
            )
            if (companion.text.isNotBlank()) append("。").append(companion.text)
            append("。它还没有启用: 要哪几条能力就在设置页的插件那一段里勾, 然后点启用")
        }
    }

    private fun list(context: Context): JsonObject {
        val entries = PluginManager.entries(context)
        return buildJsonObject {
            put("count", entries.size)
            put("directory", PluginStore.root(context).absolutePath)
            put("developerMode", developerMode(context))
            putJsonArray("plugins") {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("id", entry.id)
                            put("name", entry.name)
                            put("version", entry.version)
                            put("kind", entry.manifest?.kind.orEmpty())
                            put("toolPrefix", entry.toolPrefix)
                            put("enabled", entry.enabled)
                            put("trust", entry.trust.name.lowercase())
                            put("declared", entry.manifest?.declared?.size ?: 0)
                            put("granted", entry.granted.size)
                            put("problem", entry.problem ?: entry.hostProblem.orEmpty())
                        },
                    )
                }
            }
            put(
                "text",
                if (entries.isEmpty()) {
                    "一个插件都没有装。插件落在 ${PluginStore.root(context).absolutePath}; " +
                        "装一份用 op=install 给一个目录或 .lwp 的路径"
                } else {
                    entries.joinToString("\n") { entry ->
                        val state = when {
                            entry.problem != null -> "读不出来"
                            entry.enabled && entry.hostProblem == null -> "在岗"
                            entry.enabled -> "启用着但宿主没接上"
                            else -> "停着"
                        }
                        "${entry.name} ${entry.version} [${entry.id}] —— $state, 前缀 ${entry.toolPrefix}_, " +
                            "授权 ${entry.granted.size}/${entry.manifest?.declared?.size ?: 0}"
                    }
                },
            )
        }
    }

    private fun read(context: Context, request: JsonObject): JsonObject {
        val id = request.string("id")
        val entry = PluginManager.entries(context).firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("没装过这个插件: $id")
        val manifest = entry.manifest
        return buildJsonObject {
            put("id", entry.id)
            put("enabled", entry.enabled)
            put("trust", entry.trust.name.lowercase())
            put("directory", entry.directory?.absolutePath.orEmpty())
            put("problem", entry.problem ?: entry.hostProblem.orEmpty())
            if (manifest != null) {
                put("kind", manifest.kind)
                put(
                    "entry",
                    when (manifest.kind) {
                        "companion" -> "${manifest.entryPackage}/${manifest.entryService}"
                        "inline" -> "${manifest.entryDex} (${manifest.entryClass})"
                        else -> "(没有宿主)"
                    },
                )
                put("publisher", manifest.publisherName.ifBlank { "(未签名)" })
                put(
                    "fingerprint",
                    if (manifest.signed) PluginSignature.fingerprint(manifest.publisherKey) else "",
                )
                putJsonArray("tools") {
                    manifest.tools.forEach { tool ->
                        add(buildJsonObject { put("name", tool.name); put("summary", tool.summary) })
                    }
                }
                putJsonArray("capabilities") {
                    manifest.declared.forEach { declared ->
                        add(
                            buildJsonObject {
                                put("name", declared)
                                put("dangerous", PluginCapabilities.isDangerous(declared))
                                put("granted", declared in entry.granted)
                                put("wired", PluginCapabilities.isWired(declared))
                            },
                        )
                    }
                }
            }
            put(
                "text",
                if (manifest == null) {
                    "${entry.name} 现在读不出来: ${entry.problem}"
                } else {
                    "${manifest.name} ${manifest.version} (${manifest.id}), " +
                        (if (entry.enabled) "启用着" else "停着") + ", 授权 " +
                        entry.granted.joinToString(", ").ifBlank { "一条都没有" }
                },
            )
        }
    }

    private fun audit(context: Context, request: JsonObject): JsonObject {
        val id = request.string("id")
        val lines = PluginAudit.tail(context, id, request.int("lines", 20))
        return buildJsonObject {
            putJsonArray("lines") { lines.forEach { add(it) } }
            put("text", lines.joinToString("\n").ifBlank { "$id 还没有一条能力调用的账" })
        }
    }

    /**
     * 宿主转发过来的那一次插件工具调用
     *
     * 参数那一层是模型给的 JSON, 而插件那一侧收的是 `Bundle` (手写 Binder 的信封), 所以这里做一次
     * 转换 —— 只认协议允许的那几种标量 (字符串 / 整数 / 布尔 / 嵌套对象), 别的如实回一句
     */
    private fun invoke(context: Context, request: JsonObject): JsonObject {
        val id = request.string("id")
        val tool = request.string("tool")
        val args = request["args"] as? JsonObject ?: JsonObject(emptyMap())
        val result = PluginManager.invoke(context, id, tool, bundleOf(args))
        return buildJsonObject {
            put("ok", LwPluginResults.isOk(result))
            put("text", LwPluginResults.text(result))
            putJsonArray("images") { LwPluginResults.images(result).forEach { add(it) } }
        }
    }

    /** JSON → `Bundle`: 只认标量、字符串数组与嵌套对象, 认不出来的键就不带过去 */
    private fun bundleOf(json: JsonObject): Bundle = Bundle().apply {
        json.forEach { (key, value) ->
            when (value) {
                is JsonPrimitive -> when {
                    value.isString -> putString(key, value.content)
                    value.booleanOrNull != null -> putBoolean(key, value.booleanOrNull == true)
                    value.longOrNull != null -> {
                        val number = value.longOrNull!!
                        if (number in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) putInt(key, number.toInt())
                        else putLong(key, number)
                    }
                    else -> putString(key, value.content)
                }
                is JsonArray -> {
                    val entries = value.map { (it as? JsonPrimitive)?.content ?: it.toString() }
                    putStringArrayList(key, ArrayList(entries))
                }
                is JsonObject -> putBundle(key, bundleOf(value))
            }
        }
    }
}
