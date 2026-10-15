package io.github.yuloong07star.luwi.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuloong07star.luwi.channel.PrivilegedChannel
import io.github.yuloong07star.luwi.tool.LwLinux
import java.util.concurrent.Executors

/**
 * 设置页「Linux 沙盒」那一段的状态与两个动作
 *
 * 与 [io.github.yuloong07star.luwi.channel.ChannelSetting] 同一个做法: 每一次问系统 / 走 binder
 * 都落在自己的线程上 —— 装一次要解压几百个文件, 删一次要 rm 掉一棵树, 这两个都不能在主线程上做
 *
 * 状态**照实说**: 通道不在线时读得到的那一半 (装没装、多大) 照旧读出来, 读不到的那一半 (有几条
 * 命令在跑) 就写 0 并在文案里说清"通道未连接" —— 与特权通道那个口径一致
 */
internal object LinuxSetting {

    private const val TAG = "LwLinuxUI"

    /** 一次只做一件事: 装与删互相不能插队 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-linux-ui").apply { isDaemon = true }
    }

    /** 现在这一段要画的东西, null = 还没读过 */
    var state: LwLinux.State? by mutableStateOf(null)
        private set

    /** 装 / 删进行中 (按钮据此禁用) */
    var working: Boolean by mutableStateOf(false)
        private set

    /** 上一步做完之后那一句话, 显示在状态下面 */
    var message: String? by mutableStateOf(null)
        private set

    fun refresh() {
        worker.execute { read() }
    }

    /** 把随包的 base 解开来 (重装也走这一条, 它会把整棵 rootfs 从头来一遍) */
    fun install() {
        if (working) return
        working = true
        message = null
        worker.execute {
            val context = PrivilegedChannel.context()
            message = when {
                context == null ->
                    "the app is not initialized yet, so there is nothing to install with"
                // 先试一次连接: 通道只是掉了、Shizuku 又回来了时这一步自己接上, 接不上才如实拒
                // (拒在抄 asset 之前 —— 通道不在线时那 4 MB 白抄, 而且抄出来还没人删)
                PrivilegedChannel.ensure() == null -> unavailable("unpacking the sandbox")
                else -> runCatching { LwLinux.install(context).text }
                    .onFailure { Log.w(TAG, "installing the sandbox failed", it) }
                    .getOrElse { "installing the sandbox failed: ${it.message}" }
            }
            read()
            working = false
        }
    }

    /** 删掉整个沙盒 */
    fun remove() {
        if (working) return
        working = true
        message = null
        worker.execute {
            message = if (PrivilegedChannel.ensure() == null) {
                unavailable("deleting the sandbox")
            } else {
                runCatching { LwLinux.reset().text }
                    .onFailure { Log.w(TAG, "deleting the sandbox failed", it) }
                    .getOrElse { "deleting the sandbox failed: ${it.message}" }
            }
            read()
            working = false
        }
    }

    /** 通道不在线时那一句话 (与 [LwLinux] 里那两条拒同一个形状) */
    private fun unavailable(what: String): String =
        "the privileged channel is not available, and $what needs it: " +
            (PrivilegedChannel.state().error ?: "no reason reported")

    private fun read() {
        val context = PrivilegedChannel.context()
        state = if (context == null) {
            null
        } else {
            runCatching { LwLinux.readState(context) }
                .onFailure { Log.w(TAG, "reading the sandbox state failed", it) }
                .getOrNull()
        }
    }
}
