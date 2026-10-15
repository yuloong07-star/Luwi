package io.github.yuloong07star.luwi.channel

import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.util.Log
import io.github.yuloong07star.luwi.lock.LockSteps

/**
 * The service the privileged process hosts
 *
 * It is a Binder itself rather than an AIDL stub, and it runs as whatever uid the channel gave
 * it, which is the fact the whole channel exists to establish. Everything here is deliberately
 * something the app process cannot do for itself: report the privileged uid, read the input
 * devices, which needs the `input` group the app does not have, host the virtual screen, whose
 * flags are gated on a permission the app does not hold, and watch the phone's own glass for the
 * finger that has the last word over everything else here
 *
 * The service is created by [LwServiceStarter] through app_process rather than by the platform,
 * so it has no Context of its own; the one it gets may be null and each use has to tolerate that
 */
class LwPrivilegedService(private val context: Context?) : Binder() {

    /** The screen this process hosts, which outlives every connection the app makes to it */
    private val display = LwVirtualDisplay(context)

    /** Touch aimed at a screen, which only works from a uid the platform trusts to inject */
    private val input = LwInput(context)

    /** Pictures of a screen, which the app cannot take once its own preview is gone */
    private val capture = LwCapture()

    /** The settings the app cannot write for itself, of which there is one so far */
    private val permission = LwPermission(context)

    /** Starting an app, which the app cannot ask the activity manager for at all */
    private val launch = LwLaunch()

    /**
     * 录一段真手指的动作 (批次 5, 需求 7)
     *
     * 触屏那个节点一直开着, 应用按一拍取一次 (见 [LwTouchRecord.drain]): 一次事务带走的只是"这一段
     * 里已经走完的笔画", 而手指还按着的那一条留在手里 —— 于是按 2 秒一拍取也不会把一条上滑切成两半
     */
    private val touchRecord = LwTouchRecord()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        when (code) {
            LwServiceProtocol.DESTROY -> {
                data.enforceInterface(LwServiceProtocol.DESCRIPTOR)
                // The app is waiting on this transaction, so ending the process inside it would
                // turn a clean release into a dead binder; the reply goes first, the exit follows
                reply?.writeNoException()
                exitAfterReply()
                true
            }

            LwServiceProtocol.VERSION -> answering(data, reply) { writeString(VERSION) }

            LwServiceProtocol.UID -> answering(data, reply) { writeInt(Process.myUid()) }

            LwServiceProtocol.PID -> answering(data, reply) { writeInt(Process.myPid()) }

            LwServiceProtocol.INPUT_DEVICES -> answering(data, reply) { writeString(readInputDevices()) }

            LwServiceProtocol.DISPLAY_CREATE -> answering(data, reply) {
                val width = data.readInt()
                val height = data.readInt()
                val dpi = data.readInt()
                val surface = data.readSurface()
                writeInt(display.create(width, height, dpi, surface))
            }

            LwServiceProtocol.DISPLAY_SURFACE -> answering(data, reply) {
                val displayId = data.readInt()
                display.setSurface(displayId, data.readSurface())
            }

            LwServiceProtocol.DISPLAY_RESIZE -> answering(data, reply) {
                val displayId = data.readInt()
                val width = data.readInt()
                val height = data.readInt()
                val dpi = data.readInt()
                display.resize(displayId, width, height, dpi)
            }

            LwServiceProtocol.DISPLAY_RELEASE -> answering(data, reply) {
                val displayId = data.readInt()
                display.release(displayId)
                // A screen that went away takes the capturer's idea of its compositor id with it
                capture.forget()
            }

            LwServiceProtocol.INPUT_TAP -> answering(data, reply) {
                val displayId = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                val holdMs = data.readLong()
                input.tap(displayId, x, y, holdMs)
            }

            LwServiceProtocol.INPUT_TOUCH -> answering(data, reply) {
                val displayId = data.readInt()
                val action = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                input.touch(displayId, action, x, y)
            }

            LwServiceProtocol.INPUT_SWIPE -> answering(data, reply) {
                val displayId = data.readInt()
                val fromX = data.readFloat()
                val fromY = data.readFloat()
                val toX = data.readFloat()
                val toY = data.readFloat()
                val durationMs = data.readLong()
                input.swipe(displayId, fromX, fromY, toX, toY, durationMs)
            }

            LwServiceProtocol.INPUT_KEY -> answering(data, reply) {
                val displayId = data.readInt()
                val keyCode = data.readInt()
                val holdMs = data.readLong()
                writeInt(if (input.key(displayId, keyCode, holdMs)) 1 else 0)
            }

            LwServiceProtocol.INPUT_TEXT -> answering(data, reply) {
                val displayId = data.readInt()
                val text = data.readString().orEmpty()
                writeInt(input.text(displayId, text))
            }

            LwServiceProtocol.SCREENSHOT -> answering(data, reply) {
                val displayId = data.readInt()
                val path = data.readString().orEmpty()
                // The phone's own screen is the one display with no name of ours to look up, and
                // naming no display at all is how `screencap` is asked for it
                val problem = if (displayId == LwServiceProtocol.MAIN_DISPLAY) {
                    capture.capturePrimary(path)
                } else {
                    val name = display.nameOf(displayId)
                    if (name == null) {
                        "displayId $displayId is not one of this app's screens any more: it was" +
                            " closed, so create another one (lw_screen_create) and launch the app on it"
                    } else {
                        capture.capture(name, path)
                    }
                }
                writeString(problem)
            }

            LwServiceProtocol.A11Y_SET -> answering(data, reply) {
                val enabled = data.readInt() == 1
                writeInt(if (permission.setAccessibility(enabled)) 1 else 0)
            }

            LwServiceProtocol.NOTIFICATION_LISTEN -> answering(data, reply) {
                val enabled = data.readInt() == 1
                writeInt(if (permission.setNotificationListener(enabled)) 1 else 0)
            }

            LwServiceProtocol.LAUNCH -> answering(data, reply) {
                val displayId = data.readInt()
                val userId = data.readInt()
                val packageName = data.readString().orEmpty()
                val component = data.readString().orEmpty()
                val timeoutMs = data.readLong()
                val result = launch.start(displayId, userId, packageName, component, timeoutMs)
                writeString(result.output)
                writeInt(result.code)
            }

            LwServiceProtocol.USERS -> answering(data, reply) { writeString(launch.users()) }

            LwServiceProtocol.PACKAGES -> answering(data, reply) {
                writeString(launch.packages(data.readInt()))
            }

            // 1.0.2 加的两条: 白名单里的系统命令, 以及屏幕的亮与灭
            LwServiceProtocol.SYSTEM_COMMAND -> answering(data, reply) {
                val operation = data.readString().orEmpty()
                val userId = data.readInt()
                val count = data.readInt()
                val arguments = (0 until count).map { data.readString().orEmpty() }
                val timeoutMs = data.readLong()
                val result = LwSystemCommandTable.run(operation, userId, arguments, timeoutMs)
                writeInt(result.code)
                writeString(result.output)
            }

            LwServiceProtocol.POWER -> answering(data, reply) {
                val result = LwSystemCommandTable.run(
                    if (data.readInt() == 1) "wake" else "sleep",
                    0,
                    emptyList(),
                    SCREEN_KEY_TIMEOUT_MS,
                )
                writeInt(result.code)
                writeString(result.output)
            }

            LwServiceProtocol.GESTURE -> answering(data, reply) {
                val displayId = data.readInt()
                val pathCount = data.readInt()
                val paths = (0 until pathCount).map {
                    val count = data.readInt()
                    LwInput.Path((0 until count).map { data.readFloat() to data.readFloat() })
                }
                val durationMs = data.readLong()
                input.gesture(displayId, paths, durationMs)
            }

            LwServiceProtocol.PINCH -> answering(data, reply) {
                val displayId = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                val scale = data.readFloat()
                val durationMs = data.readLong()
                input.pinch(displayId, x, y, scale, durationMs)
            }

            LwServiceProtocol.TOUCH_RECORD -> answering(data, reply) {
                val finish = data.readInt() == 1
                val started = if (finish) null else touchRecord.start()
                val (problem, steps) = if (started == null) {
                    touchRecord.drain()
                } else {
                    started to emptyList()
                }
                // 这一拍读到几帧要一起回: 一帧都没有时, "为什么没录到"就只剩这一个读数说得清
                val seen = touchRecord.seen()
                if (finish) touchRecord.stop()
                writeString(problem.orEmpty())
                writeString(LockSteps.encode(steps))
                writeInt(seen)
            }

            // Linux 沙盒: 装 / 跑 / 问 / 删 (见 LwLinuxSandbox 的文件头)
            LwServiceProtocol.LINUX -> answering(data, reply) {
                val operation = data.readString().orEmpty()
                val command = data.readString().orEmpty()
                val work = data.readString().orEmpty()
                val timeoutMs = data.readLong()
                val source = data.readString().orEmpty()
                val version = data.readString().orEmpty()
                val result = when (operation) {
                    "install" -> LwLinuxSandbox.install(context, source, version)
                    "run" -> LwLinuxSandbox.run(context, command, work, timeoutMs)
                    "reset" -> LwLinuxSandbox.reset()
                    "status" -> {
                        val status = LwLinuxSandbox.status()
                        // 一行一个键的文本: 这条协议的回执本来就是"给模型看的话", 而状态这几个数
                        // 应用那侧要拿它画设置页, 所以格式是稳的 (谁都不许随手改, 改了要一起改)
                        val text = buildString {
                            append("installed=").append(status.installed).append('\n')
                            append("version=").append(status.version ?: "").append('\n')
                            // 读不出来就写空, 不写 0: "0 B" 与"问不出来"是两件事, 应用那一侧
                            // 把空当成未知 (2026-10-12 真机逮到: 沙盒在跑时 du 的计数行被前面的
                            // Permission denied 顶掉, 这里曾把 null 说成 0)
                            append("sizeKb=").append(status.sizeKb ?: "").append('\n')
                            append("running=").append(status.running)
                        }
                        LwLinuxSandbox.Outcome(0, text)
                    }
                    else -> LwLinuxSandbox.Outcome(
                        2,
                        "op has to be one of install, run, status, reset, not \"$operation\"",
                    )
                }
                writeInt(result.code)
                writeString(result.output)
            }

            else -> super.onTransact(code, data, reply, flags)
        }

    /** A context for anything that needs system services, null when the platform gave none */
    fun context(): Context? = context

    /** Release whatever is held and end the process, which is what the app's destroy asks for */
    fun destroy() {
        Log.i(TAG, "exiting")
        // Screens are the one thing the system keeps alive after this process is gone, and a
        // display nobody releases stays in `dumpsys display` until the device reboots
        display.releaseAll()
        Process.killProcess(Process.myPid())
    }

    /** Answer one transaction: check the token, then write the value into the reply parcel */
    private inline fun answering(data: Parcel, reply: Parcel?, write: Parcel.() -> Unit): Boolean {
        data.enforceInterface(LwServiceProtocol.DESCRIPTOR)
        if (reply == null) return true
        return try {
            reply.writeNoException()
            reply.write()
            true
        } catch (error: Exception) {
            // A refused display has to reach the caller with the reason the device gave, and an
            // empty reply would only read as garbage on that side; this is also the only place the
            // failure is visible, since the app's request thread only sees the rethrown exception
            Log.w(TAG, "a transaction failed", error)
            reply.setDataPosition(0)
            reply.writeException(error)
            true
        }
    }

    /** Give the framework a moment to flush the reply, then end the process */
    private fun exitAfterReply() {
        Thread({
            Thread.sleep(EXIT_DELAY_MS)
            destroy()
        }, "lw-service-exit").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "LwService"

        /** Bumped whenever the protocol changes in a way the app has to know about */
        const val VERSION = "14"

        /** Long enough for the reply to leave the process, short enough to look immediate */
        const val EXIT_DELAY_MS = 100L

        /** 一个按键命令不该跑很久: 它要么立刻返回, 要么就是这台设备不认这个键 */
        const val SCREEN_KEY_TIMEOUT_MS = 5_000L
    }
}
