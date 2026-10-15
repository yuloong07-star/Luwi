package io.github.yuloong07star.luwi.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.yuloong07star.luwi.channel.LwModes
import io.github.yuloong07star.luwi.plugin.api.ILwPlugin
import io.github.yuloong07star.luwi.plugin.api.ILwPluginContext
import io.github.yuloong07star.luwi.plugin.api.LwPluginApi
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults
import io.github.yuloong07star.luwi.plugin.inline.InlineHost
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * companion 那一档的宿主 (协议 8.1)
 *
 * 三件事: 绑它的 Service / 把 `plugin.json` 与它 `describe()` 回的那份**对照** / 收工具调用。
 * 对照这一条是刻意的: 伴侣是别人写的包, 它装的与它自述的**必须**是同一件东西
 *
 * 绑不上、接不上、自述对不上, 都落一句话在 [problem] 里 —— 界面与工具念的就是那一句, 而不是
 * "这个插件不好使"
 */
internal object PluginHost {

    private const val TAG = "LwPluginHost"

    /** 绑上之后等 Service 回话的上限: 伴侣就在本机, 五秒已经是"它根本没起来" */
    private const val BIND_TIMEOUT_SECONDS = 5L

    private class Session {
        @Volatile
        var plugin: ILwPlugin? = null

        val arrived = CountDownLatch(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                plugin = ILwPlugin.asInterface(service)
                arrived.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                plugin = null
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val problems = ConcurrentHashMap<String, String>()

    /** 这个插件现在为什么不能干活; null 表示能 */
    fun problem(id: String): String? = problems[id]

    /**
     * 接上并把它叫起来
     *
     * 按 `kind` 路由到三个域 (方案第 3 节): `companion` 去绑那个 APK, `inline` 去 `:plugin` 进程里
     * 加载它的 dex, 而 `art` **没有宿主** —— 它落地就算完成
     *
     * @return null 表示接好了, 否则是一句点名的话 (并且这个插件不会留在启用状态)
     */
    fun connect(context: Context, manifest: PluginManifest, grants: Set<String>): String? {
        disconnect(context, manifest.id)
        problems.remove(manifest.id)
        return when (manifest.kind) {
            "art" -> null
            "inline" -> InlineHost.connect(context, manifest, grants)
            else -> connectCompanion(context, manifest, grants)
        }
    }

    /** companion 那一档: 绑它的 Service */
    private fun connectCompanion(context: Context, manifest: PluginManifest, grants: Set<String>): String? {
        val installed = runCatching {
            context.packageManager.getPackageInfo(manifest.entryPackage, 0)
        }.getOrNull()
        if (installed == null) {
            return note(manifest.id, "伴侣 ${manifest.entryPackage} 没装在这台设备上, 先把那个 APK 装上")
        }

        val session = Session()
        sessions[manifest.id] = session

        val intent = Intent(LwPluginApi.BIND_ACTION)
            .setComponent(ComponentName(manifest.entryPackage, manifest.entryService))
        val bound = runCatching {
            context.bindService(intent, session.connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            sessions.remove(manifest.id)
            return note(
                manifest.id,
                "绑不上 ${manifest.entryPackage}/${manifest.entryService} —— 那个伴侣没声明这一条 Service," +
                    " 或者我们没有它那条自定义权限",
            )
        }

        val arrived = runCatching { session.arrived.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        val plugin = session.plugin
        if (!arrived || plugin == null) {
            disconnect(context, manifest.id)
            return note(manifest.id, "伴侣的 Service ${BIND_TIMEOUT_SECONDS} 秒里没有回应")
        }
        val problem = activate(context, manifest, plugin, grants)
        if (problem != null) {
            disconnect(context, manifest.id)
            return note(manifest.id, problem)
        }
        return null
    }

    /**
     * 接上之后的共同那一段: 自述对照 → `attach` → `enable`
     *
     * companion 与 inline 走的是同一份 —— 两个域的差别只在"插件那个对象从哪来"
     */
    fun activate(context: Context, manifest: PluginManifest, plugin: ILwPlugin, grants: Set<String>): String? {
        val mismatch = compare(manifest, runCatching { plugin.describe() }.getOrNull())
        if (mismatch != null) return mismatch

        val attach = Bundle().apply {
            putBinder(LwPluginApi.Attach.CONTEXT, contextBinder(context, manifest))
            putString(LwPluginApi.Attach.DIR, PluginStore.packageDir(context, manifest.id)?.absolutePath.orEmpty())
            putString(LwPluginApi.Attach.LW, PluginStore.lwVersion(context))
            putString(LwPluginApi.Attach.LANGUAGE, java.util.Locale.getDefault().language)
            putString(LwPluginApi.Attach.MODE, LwModes.active(context))
            putStringArrayList(LwPluginApi.Attach.GRANTS, ArrayList(grants))
        }
        val attached = runCatching { plugin.lifecycle(LwPluginApi.Event.ATTACH, attach) }
        if (attached.isFailure || !LwPluginResults.isOk(attached.getOrNull())) {
            val why = attached.exceptionOrNull()?.message ?: LwPluginResults.text(attached.getOrNull())
            return "插件的 attach 没走通: $why"
        }
        val enabled = runCatching { plugin.lifecycle(LwPluginApi.Event.ENABLE, Bundle()) }
        if (enabled.isFailure || !LwPluginResults.isOk(enabled.getOrNull())) {
            val why = enabled.exceptionOrNull()?.message ?: LwPluginResults.text(enabled.getOrNull())
            return "插件的 enable 没走通: $why"
        }
        return null
    }

    /** 叫停并松开: disable → detach → unbind; 两个域都问一遍, 没有会话的那一边是空转 */
    fun disconnect(context: Context, id: String) {
        disconnectCompanion(context, id)
        InlineHost.disconnect(context, id)
    }

    private fun disconnectCompanion(context: Context, id: String) {
        val session = sessions.remove(id) ?: return
        session.plugin?.let { plugin ->
            runCatching { plugin.lifecycle(LwPluginApi.Event.DISABLE, Bundle()) }
            runCatching { plugin.lifecycle(LwPluginApi.Event.DETACH, Bundle()) }
        }
        runCatching { context.unbindService(session.connection) }
        contexts.remove(id)
    }

    /** 收一次工具调用 */
    fun invoke(context: Context, id: String, tool: String, args: Bundle): Bundle {
        val plugin = sessions[id]?.plugin
        if (plugin == null) {
            if (InlineHost.has(id)) return InlineHost.invoke(context, id, tool, args)
            return LwPluginResults.fail(problems[id] ?: "这个插件现在没接上 (先停用再启用一次)")
        }
        return runCatching { plugin.invoke(tool, args) }.getOrElse { error ->
            // 插件抛异常绝不带走 LW: 转成一条工具错误文本 (协议 8.2 那条"崩"的兜底)
            Log.w(TAG, "$id 的 $tool 抛了异常", error)
            LwPluginResults.fail("插件那一侧抛了异常: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun note(id: String, why: String): String {
        Log.w(TAG, "$id: $why")
        problems[id] = why
        return why
    }

    /**
     * `plugin.json` 与 `describe()` 对照
     *
     * 只比"敢不一样就不让启用"的那几样: id / 版本 / 工具名集合 / 能力集合。名字与摘要不比对,
     * 那两样是给人看的, 改了不影响安全
     */
    private fun compare(manifest: PluginManifest, described: Bundle?): String? {
        if (described == null) return "伴侣没有回 describe()"
        val id = described.getString(LwPluginApi.Plugin.ID)
        if (id != manifest.id) return "它自述的 id 是 \"$id\", 而包里写的是 \"${manifest.id}\""
        val version = described.getString(LwPluginApi.Plugin.VERSION)
        if (version != manifest.version) {
            return "它自述的版本是 \"$version\", 而包里写的是 \"${manifest.version}\" —— 那个 APK 与这份包不是一对"
        }
        val api = described.getString(LwPluginApi.Plugin.API)
        if (api != manifest.api) return "它自述的 api 是 \"$api\", 而包里写的是 \"${manifest.api}\""
        val tools = described.getStringArrayList(LwPluginApi.Plugin.TOOLS).orEmpty().toSet()
        if (tools != manifest.tools.map { it.name }.toSet()) {
            return "它自述的工具是 ${tools.joinToString(", ")}, 而包里写的是 " +
                manifest.tools.joinToString(", ") { it.name }
        }
        val declared = described.getStringArrayList(LwPluginApi.Plugin.CAPABILITIES).orEmpty() +
            described.getStringArrayList(LwPluginApi.Plugin.DANGEROUS).orEmpty()
        if (declared.toSet() != manifest.declared.toSet()) {
            return "它自述的能力是 ${declared.joinToString(", ")}, 而包里写的是 ${manifest.declared.joinToString(", ")}"
        }
        return null
    }

    /** 每个插件一份 context binder: 它知道自己是谁, 于是能校验调用方的 uid */
    private val contexts = ConcurrentHashMap<String, LwPluginContextBinder>()

    fun contextBinder(context: Context, manifest: PluginManifest): IBinder =
        contexts.getOrPut(manifest.id) { LwPluginContextBinder(context, manifest) }
}

/**
 * LW 交给插件的那一个 `ILwPluginContext` (协议第 9 节: 插件**只能**从这里够到设备)
 *
 * 三道检查都在这里: 调用方的 uid 必须就是这个伴侣 (跨进程那条路)、能力必须声明过且被授权、
 * 每一次调用都进审计 —— **被拒的也进**
 */
internal class LwPluginContextBinder(
    private val context: Context,
    private val manifest: PluginManifest,
) : ILwPluginContext() {

    private val store = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 这个伴侣在本机的 uid; 读不到就是 -1, 那种情况下一次也不放行 */
    private val expectedUid: Int = runCatching {
        context.packageManager.getPackageUid(manifest.entryPackage, 0)
    }.getOrDefault(-1)

    override fun call(capability: String, args: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        if (caller != expectedUid && caller != Process.myUid()) {
            return refuse(capability, args, "调用方 uid $caller 不是 ${manifest.entryPackage} (uid $expectedUid)")
        }
        val declared = PluginCapabilities.find(capability)
        if (declared == null || capability !in manifest.declared) {
            return refuse(capability, args, "${manifest.name} 没有声明 \"$capability\"")
        }
        if (capability !in PluginManager.granted(context, manifest.id)) {
            return refuse(capability, args, "\"$capability\" 还没被允许 (在设置页的插件那一段里勾它)")
        }
        if (!PluginCapabilities.isWired(capability)) {
            return refuse(capability, args, "\"$capability\" 这一版还没接通")
        }
        val outcome = runCatching { PluginCapabilityRuntime.call(context, manifest, capability, args ?: Bundle()) }
        val result = outcome.getOrElse { error ->
            LwPluginResults.fail(error.message ?: error.javaClass.simpleName)
        }
        PluginAudit.record(context, manifest.id, capability, describe(args), LwPluginResults.text(result))
        return result
    }

    override fun settings(defaults: Bundle?): Bundle {
        val file = java.io.File(PluginStore.dataDir(context, manifest.id), "settings.json")
        val current = runCatching { store.parseToJsonElement(file.readText()).let { it } }.getOrNull()
        val merged = Bundle(defaults ?: Bundle())
        val stored = current as? kotlinx.serialization.json.JsonObject ?: return merged
        stored.forEach { (key, value) ->
            val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
            when {
                primitive.isString -> merged.putString(key, primitive.content)
                primitive.content == "true" || primitive.content == "false" ->
                    merged.putBoolean(key, primitive.content == "true")
                primitive.content.toLongOrNull() != null -> merged.putLong(key, primitive.content.toLong())
            }
        }
        return merged
    }

    /** 插件自己那一小块存储: 一个 JSON 对象, 只碰得到自己那一段 */
    override fun store(op: String, args: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        if (caller != expectedUid && caller != Process.myUid()) {
            return LwPluginResults.fail("调用方 uid $caller 不是 ${manifest.entryPackage}")
        }
        val file = java.io.File(PluginStore.dataDir(context, manifest.id), "store.json")
        val current = runCatching {
            (store.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject)
        }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val key = args?.getString("key").orEmpty()
        return when (op) {
            "list" -> {
                val result = Bundle()
                current.forEach { (name, value) ->
                    (value as? kotlinx.serialization.json.JsonPrimitive)?.let { result.putString(name, it.content) }
                }
                result
            }
            "get" -> LwPluginResults.ok(current[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
            "put" -> {
                val value = args?.getString("value").orEmpty()
                val next = kotlinx.serialization.json.JsonObject(
                    current.toMutableMap().apply { put(key, kotlinx.serialization.json.JsonPrimitive(value)) },
                )
                file.parentFile?.mkdirs()
                file.writeText(store.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), next))
                LwPluginResults.ok("记下了 $key")
            }
            "remove" -> {
                val next = kotlinx.serialization.json.JsonObject(current.toMutableMap().apply { remove(key) })
                file.parentFile?.mkdirs()
                file.writeText(store.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), next))
                LwPluginResults.ok("抹掉了 $key")
            }
            else -> LwPluginResults.fail("store 的 op 只能是 list / get / put / remove, 不是 \"$op\"")
        }
    }

    override fun log(level: Int, message: String) {
        Log.println(level.coerceIn(2, 7), "LwPlugin", "[${manifest.id}] $message")
    }

    override fun api(): Int = LwPluginApi.API_VERSION

    private fun refuse(capability: String, args: Bundle?, why: String): Bundle {
        PluginAudit.record(context, manifest.id, capability, describe(args), "被拒: $why")
        return LwPluginResults.fail("${manifest.name} 要的 \"$capability\" 被拒: $why")
    }

    @Suppress("DEPRECATION")
    private fun describe(args: Bundle?): String =
        args?.keySet()?.sorted()?.joinToString(", ") { key ->
            val value = args.get(key)
            val shown = when (value) {
                is String -> value.take(60)
                null -> "null"
                else -> value.toString()
            }
            "$key=$shown"
        }.orEmpty().ifBlank { "(没有参数)" }
}
