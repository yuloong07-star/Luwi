package io.github.yuloong07star.luwi.channel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * Turning the accessibility service on and off, from the app's own settings page
 *
 * Two things are true at once here and both are the reason this is not just a switch: the service
 * can only be enabled by writing a secure setting, which the privileged process does, and whether
 * it is on is not a setting at all but the fact that the system has bound it - so the state shown
 * is read from the service itself and can lag the request by a moment
 *
 * Writing a setting is blocking, so it happens on a thread of its own; the page watches [working]
 * and [lastError] rather than waiting
 */
object AccessibilitySetting {

    private const val TAG = "LwPermission"

    /** One call at a time, since they all write the same setting */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-permission").apply { isDaemon = true }
    }

    /** 两档记号与别的偏好放在同一个文件里: 替他开过一次 / 他自己关过 */
    private const val STORE = "luwi"
    private const val KEY_AUTO = "accessibility-auto"
    private const val KEY_USER_OFF = "accessibility-user-off"

    /** Whether a request is in flight */
    var working: Boolean by mutableStateOf(false)
        private set

    /** Why the last request failed, cleared by the next one that works */
    var lastError: String? by mutableStateOf(null)
        private set

    /**
     * 无障碍现在的真实样子
     *
     * 由 [refreshState] 填: 那几件事要各起一个 `settings` / `cmd` 子进程, 只能在 worker 线程上做,
     * 所以它是个状态而不是一个每次读都现算的属性
     */
    var state: AccessibilityState? by mutableStateOf(null)
        private set

    /** Whether the device has the service bound right now */
    var enabled: Boolean by mutableStateOf(LwAccessibility.running)
        private set

    /** Read the device's answer again, which is all a page needs when it appears */
    fun refresh() {
        enabled = LwAccessibility.running
    }

    /**
     * 把六件事实读齐, 供设置页显示
     *
     * 在 worker 线程上做, 因为 `inspect()` 要起几个子进程 (5 秒超时那一条), 在主线程上等就是 ANR 的
     * 写法。读不出来就把上一次的留着, 不把状态清成 null
     */
    fun refreshState() {
        worker.execute {
            val context = PrivilegedChannel.context() ?: return@execute
            val inspected = try {
                LwPermission(context).inspect()
            } catch (error: Throwable) {
                Log.w(TAG, "could not read the accessibility state", error)
                null
            }
            if (inspected != null) {
                state = inspected
                enabled = inspected.running
            }
        }
    }

    /**
     * Ask for the service to be on or off
     *
     * @param on what the switch was moved to
     * @param done called back on the worker thread once the setting has been written and given a
     *   moment to take effect
     */
    fun set(on: Boolean, done: (Boolean) -> Unit = {}) {
        if (working) return
        working = true
        worker.execute {
            // 这一下是主人自己的意思, 也就是最终口径: 打开记第一档记号 (以后不再替他开),
            // 关掉记第二档 (以后启动也不替他开)
            PrivilegedChannel.context()?.let { context ->
                prefs(context).edit().putBoolean(if (on) KEY_AUTO else KEY_USER_OFF, true).apply()
            }
            val written = try {
                val service = PrivilegedChannel.ensure()
                    ?: throw IllegalStateException(
                        PrivilegedChannel.state().error ?: "the privileged channel is not available",
                    )
                if (service.setAccessibility(on)) {
                    lastError = null
                    true
                } else {
                    throw IllegalStateException("the privileged process could not write the setting")
                }
            } catch (error: Throwable) {
                lastError = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "could not ${if (on) "enable" else "disable"} accessibility", error)
                false
            }
            settle(on)
            working = false
            done(written)
        }
    }

    /** Give the system the moment it takes to bind or unbind the service, then report the truth */
    private fun settle(on: Boolean) {
        var waited = 0L
        while (LwAccessibility.running != on && waited < SETTLE_BUDGET_MS) {
            Thread.sleep(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
        }
        refresh()
        // 读一次真实状态: 写失败的原因分好几层, 只有读齐了才说得清是哪一层, 而含糊的提示已经误导过一次
        state = try {
            PrivilegedChannel.context()?.let { LwPermission(it).inspect() }
        } catch (error: Throwable) {
            Log.w(TAG, "could not read the accessibility state after a write", error)
            null
        }
        state?.let { enabled = it.running }
        if (LwAccessibility.running != on) {
            // 这一句按设备侧的实际情况分流, 不再把几种完全不同的原因并列在一句里
            lastError = state?.reason()
                ?: "系统没有把服务绑上, 设置写进去了但没生效"
        }
    }

    /**
     * 首次拿到特权通道之后替主人开一次 (2.7.9 第 7 条)
     *
     * 时机挂在"通道真的连上"那两处 (宿主起来那一次 / 设置页点连接那一次), 该不该开由
     * [AccessibilityDefault.decide] 算: 记号定过就什么都不做, 已经开着只补记号, 否则写一次设置。
     *
     * **写失败不落记号**: 它其实没被打开过, 也就不算"替他开过了", 下一次通道连上时再试
     */
    fun autoEnableOnce() {
        if (working) return
        val context = PrivilegedChannel.context() ?: return
        when (AccessibilityDefault.decide(
            running = LwAccessibility.running,
            autoMarked = prefs(context).getBoolean(KEY_AUTO, false),
            userOff = prefs(context).getBoolean(KEY_USER_OFF, false),
        )) {
            AccessibilityDefault.Act.NOTHING -> return

            AccessibilityDefault.Act.MARK_ONLY -> {
                prefs(context).edit().putBoolean(KEY_AUTO, true).apply()
                return
            }

            AccessibilityDefault.Act.ENABLE -> Unit
        }
        working = true
        worker.execute {
            val written = runCatching {
                val service = PrivilegedChannel.ensure() ?: return@runCatching false
                service.setAccessibility(true)
            }.getOrDefault(false)
            if (written) {
                prefs(context).edit().putBoolean(KEY_AUTO, true).apply()
                Log.i(TAG, "the accessibility service was turned on by default (first time)")
            } else {
                Log.w(TAG, "could not turn the accessibility service on by default")
            }
            settle(true)
            working = false
        }
    }

    /** 两档记号都落在这个文件里 (与设置页别的偏好同一个) */
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** How long to wait for the binding to follow the setting, in steps so it is not a fixed sleep */
    private const val SETTLE_BUDGET_MS = 3_000L
    private const val SETTLE_STEP_MS = 200L
}
