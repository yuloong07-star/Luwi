package io.github.yuloong07star.luwi.plugin.inline

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import io.github.yuloong07star.luwi.plugin.PluginHost
import io.github.yuloong07star.luwi.plugin.PluginManifest
import io.github.yuloong07star.luwi.plugin.PluginStore
import io.github.yuloong07star.luwi.plugin.api.ILwPlugin
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `inline` 域主进程这一侧 (方案 3.3)
 *
 * 三件事: 绑 `:plugin` 进程里那条 Service / 让它把 dex 加载起来拿回 binder / 收工具调用。
 * 崩与卡的两条兜底都在这里:
 *
 * - **抛异常**: 与 companion 同一条, 转成一条工具错误文本, 绝不带走 Luwi
 * - **卡死**: 调用放到自己的线程上做, 主进程那一侧 [CALL_TIMEOUT_SECONDS] 秒拿不到回答就如实报
 *   ("这个插件没在预期时间内回答"), **不重试**
 */
internal object InlineHost {

    private const val TAG = "LwInlineHost"
    private const val BIND_TIMEOUT_SECONDS = 5L
    private const val CALL_TIMEOUT_SECONDS = 15L

    private class Session {
        @Volatile
        var service: IBinder? = null

        @Volatile
        var plugin: ILwPlugin? = null

        val arrived = CountDownLatch(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder
                arrived.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                plugin = null
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 每一次调用在自己的线程上做: 卡死那一条的兜底就是它的超时 */
    private val calls = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lw-inline-call").apply { isDaemon = true }
    }

    fun has(id: String): Boolean = sessions.containsKey(id)

    /**
     * 绑上 `:plugin` 进程, 把 dex 加载起来, 再走与 companion 相同的"对照 → attach → enable"
     */
    fun connect(context: Context, manifest: PluginManifest, grants: Set<String>): String? {
        val directory = PluginStore.packageDir(context, manifest.id)
            ?: return PluginHost.note(manifest.id, "插件的包目录现在不在了")
        val session = Session()
        sessions[manifest.id] = session

        val intent = Intent(context, InlineHostService::class.java)
        val bound = runCatching {
            context.bindService(intent, session.connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            sessions.remove(manifest.id)
            return PluginHost.note(manifest.id, "绑不上 :plugin 进程里那条宿主 Service")
        }
        val arrived = runCatching { session.arrived.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        val service = session.service
        if (!arrived || service == null) {
            disconnect(context, manifest.id)
            return PluginHost.note(manifest.id, ":plugin 进程 $BIND_TIMEOUT_SECONDS 秒里没有回应")
        }

        val answer = load(service, manifest, directory.absolutePath)
        val binder = answer.getBinder(InlineHostService.KEY_PLUGIN)
        val plugin = ILwPlugin.asInterface(binder)
        if (plugin == null) {
            disconnect(context, manifest.id)
            val why = answer.getString(InlineHostService.KEY_ERROR) ?: "它没把插件对象交回来"
            return PluginHost.note(manifest.id, why)
        }
        session.plugin = plugin

        val problem = PluginHost.activate(context, manifest, plugin, grants)
        if (problem != null) {
            disconnect(context, manifest.id)
            return PluginHost.note(manifest.id, problem)
        }
        return null
    }

    fun disconnect(context: Context, id: String) {
        val session = sessions.remove(id) ?: return
        session.plugin?.let { plugin ->
            runCatching { plugin.lifecycle(io.github.yuloong07star.luwi.plugin.api.LwPluginApi.Event.DISABLE, Bundle()) }
            runCatching { plugin.lifecycle(io.github.yuloong07star.luwi.plugin.api.LwPluginApi.Event.DETACH, Bundle()) }
        }
        runCatching { context.unbindService(session.connection) }
    }

    /** 收一次工具调用: 在自己那条线程上做, 超时如实报 */
    fun invoke(context: Context, id: String, tool: String, args: Bundle): Bundle {
        val plugin = sessions[id]?.plugin
            ?: return LwPluginResults.fail("$id 的 inline 宿主现在没接上 (先停用再启用一次)")
        val future = calls.submit<Bundle> { plugin.invoke(tool, args) }
        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            LwPluginResults.fail("这个插件没在预期时间内回答 ($CALL_TIMEOUT_SECONDS 秒)")
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            Log.w(TAG, "$id 的 $tool 抛了异常", cause)
            LwPluginResults.fail("插件那一侧抛了异常: ${cause.message ?: cause.javaClass.simpleName}")
        }
    }

    /** 让 `:plugin` 那边把 dex 读进来 */
    private fun load(service: IBinder, manifest: PluginManifest, directory: String): Bundle {
        val spec = Bundle().apply {
            putString(InlineHostService.KEY_ID, manifest.id)
            putString(InlineHostService.KEY_VERSION, manifest.version)
            putString(InlineHostService.KEY_DEX, manifest.entryDex)
            putString(InlineHostService.KEY_CLASS, manifest.entryClass)
            putString(InlineHostService.KEY_DIR, directory)
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(InlineHostService.DESCRIPTOR)
            data.writeBundle(spec)
            service.transact(InlineHostService.CODE_LOAD, data, reply, 0)
            reply.readBundle(javaClass.classLoader) ?: Bundle().apply {
                putString(InlineHostService.KEY_ERROR, "它什么都没回")
            }
        } catch (error: Throwable) {
            Bundle().apply {
                putString(
                    InlineHostService.KEY_ERROR,
                    "把 dex 交给 :plugin 进程那一步失败了: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
