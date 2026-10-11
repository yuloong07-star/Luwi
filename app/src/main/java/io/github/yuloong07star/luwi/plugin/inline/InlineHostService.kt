package io.github.yuloong07star.luwi.plugin.inline

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import dalvik.system.DexClassLoader
import dalvik.system.InMemoryDexClassLoader
import io.github.yuloong07star.luwi.plugin.PluginPaths
import io.github.yuloong07star.luwi.plugin.api.ILwPlugin
import io.github.yuloong07star.luwi.plugin.api.LwPluginApi
import java.io.File
import java.nio.ByteBuffer

/**
 * `inline` 域的宿主 (方案 3.3)
 *
 * 它跑在清单里预先声明的 `:plugin` 进程里, 干的事只有一件: 把插件那份 dex 加载起来, 把那个
 * `ILwPlugin` 的 binder 交回主进程。清单是静态的, 所以做不到"一个插件一个进程" —— 先共享这一个,
 * 而每个插件一个 ClassLoader, 插件之间不共享静态状态
 *
 * **信任模型要一直记着**: 这个进程与 Luwi **同一个 uid**。同 uid 的代码可以反射拿到 Luwi 的一切,
 * 运行时那几道能力检查对它而言是体验不是墙 —— 所以这一档只让白名单作者的包进来 (装的时候拦,
 * 见 `PluginInstaller.checkInlineTrust`)
 */
class InlineHostService : Service() {

    /** 已加载的那几个: `id@version` → 那个插件对象 (同一个包里反复绑只加载一次) */
    private val loaded = HashMap<String, ILwPlugin>()

    override fun onBind(intent: Intent?): IBinder = HostBinder()

    /**
     * 那一个"把 dex 读进来"的入口
     *
     * 手写 Binder, 与协议第 9 节同一个体例 (没有 AIDL): transaction code 只有一个, 信封是 `Bundle`,
     * 回的里面要么是 `plugin` 那一个 binder, 要么是 `error` 那一段话
     */
    private inner class HostBinder : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != CODE_LOAD) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            // 这个 Service 是 exported=false, 只有本应用绑得上; 这一条是第二道闸
            if (Binder.getCallingUid() != Process.myUid()) {
                reply?.writeBundle(failure("调用方不是 Luwi 自己"))
                return true
            }
            val spec = data.readBundle(javaClass.classLoader) ?: Bundle()
            val answer = try {
                load(spec)
            } catch (error: Throwable) {
                failure("加载这份插件时抛了异常: ${error.message ?: error.javaClass.simpleName}")
            }
            reply?.writeBundle(answer)
            return true
        }
    }

    private fun load(spec: Bundle): Bundle {
        val id = spec.getString(KEY_ID).orEmpty()
        val version = spec.getString(KEY_VERSION).orEmpty()
        val className = spec.getString(KEY_CLASS).orEmpty()
        val directory = File(spec.getString(KEY_DIR).orEmpty())
        val key = "$id@$version"
        loaded[key]?.let { plugin ->
            return Bundle().apply { putBinder(KEY_PLUGIN, plugin) }
        }

        // 路径纪律在这里**再兜一次** (运行时那一半): 装的时候查过, 但插件目录是主人能改的地方
        val dexFile = try {
            PluginPaths.resolveFile(directory, spec.getString(KEY_DEX).orEmpty(), "entry.dex")
        } catch (error: IllegalArgumentException) {
            return failure(error.message.orEmpty())
        }

        val parent = InlineHostService::class.java.classLoader
        val loader = try {
            // 优先内存里那一份 (InMemoryDexClassLoader): 不落第二个文件, 也不用把 dex 路径交给系统
            InMemoryDexClassLoader(ByteBuffer.wrap(dexFile.readBytes()), parent)
        } catch (error: Throwable) {
            try {
                DexClassLoader(dexFile.absolutePath, directory.absolutePath, null, parent)
            } catch (fallback: Throwable) {
                return failure("这份 dex 加载不起来: ${fallback.message ?: fallback.javaClass.simpleName}")
            }
        }

        val instance = try {
            loader.loadClass(className).getDeclaredConstructor().newInstance()
        } catch (error: Throwable) {
            return failure("$className 起不来: ${error.message ?: error.javaClass.simpleName}")
        }
        val plugin = instance as? ILwPlugin
            ?: return failure("$className 不是 ${ILwPlugin::class.java.name} 的实现 —— 它要对着 lwplugin-api 编译")
        loaded[key] = plugin
        return Bundle().apply { putBinder(KEY_PLUGIN, plugin) }
    }

    private fun failure(why: String): Bundle = Bundle().apply { putString(KEY_ERROR, why) }

    companion object {

        /** 手写 Binder 的描述符 (与 `ILwPlugin` 同一个体例) */
        const val DESCRIPTOR = "io.github.yuloong07star.luwi.plugin.inline.ILwInlineHost"

        const val CODE_LOAD = 1

        const val KEY_ID = "id"
        const val KEY_VERSION = "version"
        const val KEY_DEX = "dex"
        const val KEY_CLASS = "class"
        const val KEY_DIR = "dir"
        const val KEY_PLUGIN = "plugin"
        const val KEY_ERROR = "error"

        /** 主进程那一侧用的那一条权限名 (与协议第 9 节同一个体例, 这里只为了自检脚本对齐) */
        val PROTOCOL = LwPluginApi.PROTOCOL
    }
}
