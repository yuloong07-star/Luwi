package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults
import io.github.yuloong07star.luwi.plugin.widget.WidgetHost
import io.github.yuloong07star.luwi.plugin.widget.WidgetSlots
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 装着的那些插件、它们的开关与授权、以及宿主那侧的注册表 (协议第 2 节那一层)
 *
 * 这里是**唯一**做能力检查、授权、审计与注册的地方 —— 插件永远够不到模型, 它只注册成工具;
 * 于是"加一个新域"不用再想一遍安全
 *
 * 两份事实分开放: **目录**是装了什么 (谁都能读), **`.state.json`** 是启用与授权 (只有这里写)。
 * 宿主那一侧按 [_state].revision 拉快照 —— 它变了才重注册工具
 */
internal object PluginManager {

    private val lock = Any()

    @Volatile
    private var state: PluginStore.State = PluginStore.State()

    @Volatile
    private var loadedFrom: String? = null

    /** 信任分档 (只影响界面怎么标, 不改变能力检查) */
    enum class Trust { OFFICIAL, SIGNED, NEW_PUBLISHER, UNSIGNED }

    /**
     * 一条装着的插件
     *
     * [manifest] 是 null 时 [problem] 一定有一句话 (装是装着, 现在读不出来), 界面与工具都要把它念出来
     */
    data class Entry(
        val id: String,
        val manifest: PluginManifest?,
        val directory: java.io.File?,
        val problem: String?,
        val enabled: Boolean,
        val granted: Set<String>,
        val trust: Trust,
        val hostProblem: String?,
    ) {
        val name: String get() = manifest?.name ?: id
        val version: String get() = manifest?.version.orEmpty()
        val toolPrefix: String get() = manifest?.toolPrefix.orEmpty()
    }

    /** 强制下一次读盘 (装 / 卸 / 改状态之后调) */
    fun invalidate() {
        loadedFrom = null
    }

    /** 现在装着的那一份 */
    fun entries(context: Context): List<Entry> {
        val installed = PluginStore.installed(context)
        val current = ensureState(context)
        return installed.map { one ->
            val manifest = one.manifest
            Entry(
                id = one.id,
                manifest = manifest,
                directory = one.directory,
                problem = one.problem,
                enabled = current.enabled[one.id] == true && manifest != null,
                granted = current.grants[one.id].orEmpty().toSet(),
                trust = trustOf(context, manifest),
                hostProblem = PluginHost.problem(one.id),
            )
        }
    }

    /** 一条插件现在能不能干活: 装着、启用、宿主接上了 */
    fun ready(context: Context, id: String): Entry? =
        entries(context).firstOrNull { it.id == id }?.takeIf { it.enabled && it.problem == null }

    /** 启用: 落状态 + 接宿主 + 走 attach / enable */
    fun enable(context: Context, id: String): String {
        val entry = entries(context).firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("没装过这个插件: $id")
        val manifest = entry.manifest ?: throw IllegalArgumentException("$id 这一份现在读不出来, 先重新装一次")
        synchronized(lock) {
            writeState(context) { it.copy(enabled = it.enabled + (id to true)) }
        }
        val problem = PluginHost.connect(context, manifest, granted(context, id))
        if (problem != null) {
            // 起不来就不算启用: 状态回退, 并把原因说清 (协议第 7 节"启用"那一格)
            synchronized(lock) {
                writeState(context) { it.copy(enabled = it.enabled - id) }
            }
            return "启用失败: $problem"
        }
        return "${manifest.name} 已经上岗"
    }

    /** 停用: 先把宿主叫停再落状态 */
    fun disable(context: Context, id: String): String {
        val entry = entries(context).firstOrNull { it.id == id } ?: return "没装过这个插件: $id"
        PluginHost.disconnect(context, id)
        synchronized(lock) {
            writeState(context) { it.copy(enabled = it.enabled - id) }
        }
        return "${entry.name} 已经停用"
    }

    /** 卸载: 先停 → 撤注册 → (问过的那一句由界面负责) → 删目录 → 留一条记录 */
    fun uninstall(context: Context, id: String, keepData: Boolean): String {
        val entry = entries(context).firstOrNull { it.id == id } ?: return "没装过这个插件: $id"
        val version = entry.version
        PluginHost.disconnect(context, id)
        // 桌面那一格跟着收口 (方案 3.1): 先把它从槽里摘掉再重画 —— 少了这一步, 那一格会挂着一条
        // 已经删掉的插件, "插件没了"与"主人故意清空"两种状态就分不清了
        if (WidgetSlots.clear(context, id).isNotEmpty()) WidgetHost.refreshAll(context)
        // 浮标外观那一条同理: 挑中的那一份被卸掉就回内置画法 (协议 10.1)
        if (BallTheme.currentId(context) == id) BallTheme.choose(context, null)
        val home = java.io.File(PluginStore.root(context), id)
        if (keepData) {
            val data = java.io.File(home, ".data")
            home.listFiles()?.forEach { if (it != data) it.deleteRecursively() }
        } else {
            home.deleteRecursively()
        }
        PluginStore.removed(context, id, version)
        synchronized(lock) {
            writeState(context) {
                it.copy(enabled = it.enabled - id, grants = it.grants - id)
            }
        }
        return "${entry.name} 已经卸掉" + if (keepData) " (它那份数据留下了)" else ""
    }

    /** 授权 / 撤权一条能力: 只能授权插件自己声明过的那些 */
    fun grant(context: Context, id: String, capability: String, allowed: Boolean): String {
        val entry = entries(context).firstOrNull { it.id == id } ?: throw IllegalArgumentException("没装过这个插件: $id")
        val manifest = entry.manifest ?: throw IllegalArgumentException("$id 这一份现在读不出来")
        if (capability !in manifest.declared || PluginCapabilities.find(capability) == null) {
            throw IllegalArgumentException("$id 没有声明 \"$capability\", 授权只能给声明过的那些")
        }
        synchronized(lock) {
            writeState(context) {
                val current = it.grants[id].orEmpty().toMutableSet()
                if (allowed) current += capability else current -= capability
                // 顺序照清单里原样, 于是界面与工具念出来的次序一直是同一个
                it.copy(grants = it.grants + (id to manifest.declared.filter { one -> one in current }))
            }
        }
        return if (allowed) "已经允许 \"$capability\"" else "已经收回 \"$capability\""
    }

    fun granted(context: Context, id: String): Set<String> = ensureState(context).grants[id].orEmpty().toSet()

    /**
     * 宿主那侧要的那份快照
     *
     * 里面只有**启用着**的插件, 每条带它的工具表 (名字 / 摘要 / 参数) —— 宿主照着它 `defineTool`,
     * 于是"装了插件但没启用"在模型眼里就是不存在的
     */
    fun snapshot(context: Context): JsonObject {
        val current = ensureState(context)
        val rows = entries(context).filter { it.enabled && it.manifest != null }
        return buildJsonObject {
            put("revision", current.revision)
            putJsonArray("plugins") {
                rows.forEach { entry ->
                    val manifest = entry.manifest!!
                    add(buildJsonObject {
                        put("id", manifest.id)
                        put("name", manifest.name)
                        put("version", manifest.version)
                        put("kind", manifest.kind)
                        put("toolPrefix", manifest.toolPrefix)
                        put("package", manifest.entryPackage)
                        putJsonArray("tools") {
                            manifest.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("summary", tool.summary)
                                    put("params", tool.params)
                                })
                            }
                        }
                        putJsonObject("capabilities") {
                            putJsonArray("requested") { manifest.requested.forEach { add(it) } }
                            putJsonArray("dangerous") { manifest.dangerous.forEach { add(it) } }
                            putJsonArray("granted") { entry.granted.forEach { add(it) } }
                        }
                    })
                }
            }
        }
    }

    /** 模型调一个插件工具: 必须启用, 且参数由宿主那侧先校验过一遍 */
    fun invoke(context: Context, id: String, tool: String, args: Bundle): Bundle {
        val entry = entries(context).firstOrNull { it.id == id }
            ?: return LwPluginResults.fail("没装过这个插件: $id")
        val manifest = entry.manifest
            ?: return LwPluginResults.fail("$id 这一份现在读不出来, 先重新装一次")
        if (!entry.enabled) return LwPluginResults.fail("${manifest.name} 现在停用着, 先在设置页那一节点一下启用")
        if (manifest.tools.none { it.name == tool }) {
            return LwPluginResults.fail("${manifest.name} 没有这个工具: $tool")
        }
        return PluginHost.invoke(context, id, tool, args)
    }

    /**
     * 起这个插件自带的界面 (协议 10.3); 回一句结果, 起不来就说清为什么
     *
     * 方案 3.4 起伴侣**不许带 LAUNCHER**, 于是入口 Activity 的名字写在 `entry.activity` 里; 老包
     * 没有这一项, 沿用 launcher 那条路 (已经装着的不管)
     */
    fun open(context: Context, id: String): String {
        val entry = entries(context).firstOrNull { it.id == id } ?: throw IllegalArgumentException("没装过这个插件: $id")
        val manifest = entry.manifest ?: throw IllegalArgumentException("$id 这一份现在读不出来")
        if (manifest.kind != "companion") {
            return "这一档没有自带的界面 (${manifest.kind} 域不是另一个应用)"
        }
        val explicit = manifest.entryActivity.takeIf { it.isNotBlank() }
        val launch = if (explicit != null) {
            Intent().setComponent(ComponentName(manifest.entryPackage, explicit))
        } else {
            context.packageManager.getLaunchIntentForPackage(manifest.entryPackage)
                ?: return "这个伴侣没有能起来的界面 (它没声明 launcher activity, 也没在清单里给 entry.activity)"
        }
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            "已经打开 ${manifest.name} 的界面"
        } catch (error: Throwable) {
            "起不来: ${error.message}"
        }
    }

    /** app 起来的时候把状态重放一遍: 启用着的伴侣重新接上 (协议第 7 节"状态恢复") */
    fun restore(context: Context) {
        val current = ensureState(context)
        entries(context).filter { it.enabled && it.manifest != null }.forEach { entry ->
            PluginHost.connect(context, entry.manifest!!, current.grants[entry.id].orEmpty().toSet())
        }
    }

    /** 先起宿主的都在这儿收口: 读盘一次, 之后靠 [invalidate] */
    private fun ensureState(context: Context): PluginStore.State {
        val root = PluginStore.root(context).absolutePath
        val cached = state
        if (loadedFrom == root) return cached
        synchronized(lock) {
            if (loadedFrom == root) return state
            state = PluginStore.readState(context)
            loadedFrom = root
            return state
        }
    }

    private inline fun writeState(context: Context, change: (PluginStore.State) -> PluginStore.State) {
        state = PluginStore.writeState(context, change(ensureState(context)))
        loadedFrom = PluginStore.root(context).absolutePath
    }

    private fun trustOf(context: Context, manifest: PluginManifest?): Trust = when {
        manifest == null -> Trust.UNSIGNED
        !manifest.signed -> Trust.UNSIGNED
        sameSignature(context, manifest.entryPackage) -> Trust.OFFICIAL
        manifest.publisherKey in PluginStore.publishers(context) -> Trust.SIGNED
        else -> Trust.NEW_PUBLISHER
    }

    /** 伴侣与 LW 是不是同一把签名 (协议第 11 节的"官方"那一档) */
    private fun sameSignature(context: Context, other: String): Boolean = try {
        context.packageManager.checkSignatures(context.packageName, other) == PackageManager.SIGNATURE_MATCH
    } catch (error: Throwable) {
        false
    }
}
