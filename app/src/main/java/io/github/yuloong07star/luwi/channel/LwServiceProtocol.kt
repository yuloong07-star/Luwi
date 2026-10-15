package io.github.yuloong07star.luwi.channel

import android.os.IBinder
import android.os.Parcel
import android.view.Surface

/**
 * The one interface crossing between the app and the privileged process
 *
 * It is written out here rather than declared in AIDL, and that is not a style choice: enabling
 * the AIDL build feature makes this app compile Java, and AGP 9.4 answers any Java source by
 * routing the build through a legacy resource task whose dependency transform cannot run in this
 * project. Five hand-written transactions keep the app Kotlin-only, which keeps that task out of
 * the graph, and they never have to change because the interface does not either
 *
 * Every transaction is prefixed with the same interface token and, except for the one that ends
 * the process, answers with a parcel exception slot followed by its value
 */
object LwServiceProtocol {

    /** Binder interface name, which every transaction starts with */
    const val DESCRIPTOR = "io.github.yuloong07star.luwi.channel.LwService"

    /** Release what the service holds and end the process */
    const val DESTROY = IBinder.FIRST_CALL_TRANSACTION

    /** Version of the interface, so the app can tell what it is talking to */
    const val VERSION = IBinder.FIRST_CALL_TRANSACTION + 1

    /** The uid the privileged process really runs as */
    const val UID = IBinder.FIRST_CALL_TRANSACTION + 2

    /** The privileged process's pid */
    const val PID = IBinder.FIRST_CALL_TRANSACTION + 3

    /** Raw `getevent -p` output as the privileged uid sees it */
    const val INPUT_DEVICES = IBinder.FIRST_CALL_TRANSACTION + 4

    /** Build the virtual screen, replacing any screen already there, and answer with its id */
    const val DISPLAY_CREATE = IBinder.FIRST_CALL_TRANSACTION + 5

    /** Point one of the virtual screens at another output surface, or at none */
    const val DISPLAY_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 6

    /** Tear one virtual screen down */
    const val DISPLAY_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 7

    /** A whole press and lift on the screen, in the screen's own coordinates */
    const val INPUT_TAP = IBinder.FIRST_CALL_TRANSACTION + 8

    /** One moment of a finger on the screen, so a gesture can follow the finger that made it */
    const val INPUT_TOUCH = IBinder.FIRST_CALL_TRANSACTION + 9

    /** A picture of the screen, written to a file the caller names */
    const val SCREENSHOT = IBinder.FIRST_CALL_TRANSACTION + 10

    /** A drag from one point to another, paced out over a duration */
    const val INPUT_SWIPE = IBinder.FIRST_CALL_TRANSACTION + 11

    /** Turn this app's accessibility service on or off, which is a secure setting to write */
    const val A11Y_SET = IBinder.FIRST_CALL_TRANSACTION + 12

    /**
     * 把本应用的通知监听放进 (或拿出) 设备的已授权名单
     *
     * 与 [A11Y_SET] 是同一件事的另一个名字: 通知使用权也是 `Settings.Secure` 里的一条, 应用自己
     * 写不动, 而重装 APK 会把它收走
     */
    const val NOTIFICATION_LISTEN = IBinder.FIRST_CALL_TRANSACTION + 24

    /** Start an activity on one of the virtual screens, which only a privileged uid may ask for */
    const val LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 13

    /** A whole press and release of one key, which is how a button the platform handles is pressed */
    const val INPUT_KEY = IBinder.FIRST_CALL_TRANSACTION + 15

    /** A whole string typed as key presses, for a screen that reports no field to set it on */
    const val INPUT_TEXT = IBinder.FIRST_CALL_TRANSACTION + 16

    /**
     * Give one virtual screen another size
     *
     * A screen is made at one size and can be given another afterwards, which is the only way to
     * change the shape of what is on it: an app decides its layout from the display's own size, so
     * a game that only knows how to be landscape needs a landscape screen rather than a rotated
     * picture of a portrait one
     */
    const val DISPLAY_RESIZE = IBinder.FIRST_CALL_TRANSACTION + 17

    /**
     * The Android users this device has
     *
     * An app installed for more than one user is more than one copy of itself: the same package and
     * the same activity, with a user and a set of data of their own, which is what a cloned app
     * (双开, a parallel space) is. Naming one is how a launch picks between them, and the list is a
     * privileged question - `cmd user list` refuses this app's uid the way `am` does
     */
    const val USERS = IBinder.FIRST_CALL_TRANSACTION + 18

    /**
     * The packages installed for one user
     *
     * The other half of the same question [USERS] answers: which of the things that can be started
     * exist for a given user, a cloned app being the case that makes the user part matter. It is
     * asked of the privileged process for the same reason `pm list users` is - the command refuses
     * an app uid, and an app cannot see another user's packages at all
     */
    const val PACKAGES = IBinder.FIRST_CALL_TRANSACTION + 19

    /**
     * One of a short list of things only a privileged uid can do
     *
     * 卸载、清数据、停应用、装包、飞行模式 / 移动数据 / 蓝牙开关、熄屏 —— `am` 与 `pm` 对 app uid
     * 一律拒绝, 而 app_process 那侧以 root 或 shell 自居, 正是能做这些的身份
     *
     * 送过去的是一个**操作名**加几个参数, 不是一条命令: 具体怎么拼、能不能拼, 由特权进程里那张白名单
     * 表决定, 这里只负责把参数搬运过去
     */
    const val SYSTEM_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 20

    /**
     * 点亮或熄灭屏幕
     *
     * 走的是按键 (`KEYCODE_WAKEUP` / `KEYCODE_SLEEP`) 而不是电源服务: 平台自己处理这两个键, 而
     * app_process 起的进程没有 Context 去申请电源锁。这是熄屏那条死结 (熄屏后 `screencap` 交旧帧、
     * 注入的触摸唤不醒) 唯一能用的解
     */
    const val POWER = IBinder.FIRST_CALL_TRANSACTION + 21

    /**
     * 多指手势: 每条路径一根手指, 一起走
     *
     * 一次事务而不是一根手指一个往返: 平台按 downTime 认出这几根手指属于同一次触摸, 而分开的往返会
     * 让它们在时间上散开, 于是捏合变成两次普通的拖
     */
    const val GESTURE = IBinder.FIRST_CALL_TRANSACTION + 22

    /** 捏合: 两根手指在中心两侧分合 */
    const val PINCH = IBinder.FIRST_CALL_TRANSACTION + 23

    /**
     * 录一段真手指的动作 (批次 5, 需求 7)
     *
     * 与"看触摸屏上有没有手指"那条路 (`LwTouchRecord` 的读法) 是同一件事的坐标版: `/dev/input` 是
     * `input` 组的东西, 应用读不到, 而注入的事件不会出现在那个节点上 —— 所以这里读到的每一帧都是
     * 真手指。一次事务带走一段时间的样本, 回来的是一串 `LockStep`
     */
    const val TOUCH_RECORD = IBinder.FIRST_CALL_TRANSACTION + 25

    /**
     * Linux 沙盒的四件事: 装 / 跑 / 问 / 删
     *
     * 单独一条事务而不是挤进 [SYSTEM_COMMAND] 那张白名单表, 是因为它的参数形状不一样: 那一张表
     * 每个参数最多 512 个字符, 而"交给沙盒里 /bin/sh -lc 的那一段"可以是一整段脚本 —— 在这里被
     * 悄悄截断是最坏的一种错. **命令形状仍然写死在特权那一侧** (见 `LwLinuxSandbox`), 这条事务送
     * 过去的只有操作名、一段命令文本、一个 work 目录与一个超时, 其余 (proot 路径 / binds / 根 / 环境)
     * 全在那边拼
     */
    const val LINUX = IBinder.FIRST_CALL_TRANSACTION + 26

    /** The three moments [INPUT_TOUCH] can report */
    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2

    /**
     * The phone's own screen, which every acting call can name like any other
     *
     * It is the one screen that exists without being created: id zero is the display the phone
     * itself shows, it belongs to the person holding it rather than to this app, and it can be
     * looked at and driven but never released
     */
    const val MAIN_DISPLAY = 0

    /**
     * The platform's own long press threshold (`ViewConfiguration.getLongPressTimeout`)
     *
     * It is the shortest hold that means anything to an app: a press held at least this long is the
     * long press a widget or the policy is waiting for, and one held for less is the press it is
     * not. Longer holds are about how long a finger stays down rather than about that flag
     */
    const val LONG_PRESS_MS = 500L

    /**
     * No hold lasts longer than this, whatever a caller asks for
     *
     * The gesture queue is blocked for the whole of one, so this is the difference between a tool
     * call and a tool call that looks like it hung
     */
    const val MAX_HOLD_MS = 10_000L

    /** The app's side of the protocol over any binder, local or remote */
    fun proxy(binder: IBinder): LwServiceProxy = LwServiceProxy(binder)
}

/**
 * 一条特权命令的结果
 *
 * @property code 那条命令的退出码, 0 不代表事情真的做成了 (这台设备上见过退出码 0 而值不变)
 * @property output 它打印的原话, 出错原因就在这里面
 */
data class SystemOutput(val code: Int, val output: String)

/**
 * 录制那一侧一拍带回来的东西
 *
 * @property problem 读不成的理由, 成了就是 null
 * @property steps 这一拍里**已经走完**的那几条笔画 (JSON)
 * @property samples 这一拍里一共读到几帧 —— 它是"这条节点真的在读吗"的唯一读数: 一帧都没有时, 问题在
 *   采集那一侧 (节点不对 / 屏灭着 / 手指没落到那个节点上), 而不在步骤那一侧
 */
data class TouchChunk(val problem: String?, val steps: String, val samples: Int)

/**
 * Calls the privileged service
 *
 * The same code path covers both directions it is used in: across the process boundary from the
 * app, and in-process from the starter, because a local binder's transact runs its own onTransact
 */
class LwServiceProxy(private val remote: IBinder) {

    /** Whether the process behind this binder is still there */
    val isAlive: Boolean get() = remote.pingBinder()

    fun destroy() {
        transact(LwServiceProtocol.DESTROY) { }
    }

    fun version(): String = transact(LwServiceProtocol.VERSION) { readString().orEmpty() }

    fun uid(): Int = transact(LwServiceProtocol.UID) { readInt() }

    fun pid(): Int = transact(LwServiceProtocol.PID) { readInt() }

    fun inputDevices(): String = transact(LwServiceProtocol.INPUT_DEVICES) { readString().orEmpty() }

    /**
     * Ask for a virtual screen of this size, drawn into this surface if there is one
     *
     * Every call makes a new screen and answers with its id: more than one can exist at a time, and
     * a screen is given back by name rather than replaced. The surface is the app's own - a
     * `SurfaceView`'s - and the privileged side only ever hands it to the compositor, which is what
     * keeps the preview free of encoders and native code
     */
    fun createDisplay(width: Int, height: Int, dpi: Int, surface: Surface?): Int = transact(
        code = LwServiceProtocol.DISPLAY_CREATE,
        write = {
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
            writeSurface(surface)
        },
        read = { readInt() },
    )

    /** Move one screen to another output surface, which the app does whenever the preview moves */
    fun setDisplaySurface(displayId: Int, surface: Surface?): Unit = transact(
        code = LwServiceProtocol.DISPLAY_SURFACE,
        write = {
            writeInt(displayId)
            writeSurface(surface)
        },
        read = { },
    )

    /** Give one screen back, which also takes its content down with it */
    fun releaseDisplay(displayId: Int): Unit = transact(
        code = LwServiceProtocol.DISPLAY_RELEASE,
        write = { writeInt(displayId) },
        read = { },
    )

    /**
     * Give one screen another size, with whatever is on it left running
     *
     * The shape a display has is what an app lays itself out for, so this is the only way to put a
     * landscape-only app on a screen that was made portrait: nothing about the picture can be
     * rotated into one. A screen can be resized as often as the caller likes, and the surface it is
     * drawing into stays attached throughout - the app has to re-fix the buffer it handed over for
     * the new size, because the compositor draws at the screen's size and does not scale it
     */
    fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Unit = transact(
        code = LwServiceProtocol.DISPLAY_RESIZE,
        write = {
            writeInt(displayId)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
        },
        read = { },
    )

    /**
     * The users this device has, in the platform's own words
     *
     * Asked of the privileged process for the same reason every other question here is: the command
     * that answers it is one this app's uid is refused
     */
    fun users(): String = transact(LwServiceProtocol.USERS) { readString().orEmpty() }

    /**
     * The packages installed for one user, in the platform's own words
     *
     * @param userId the Android user, zero being the person holding the phone.
     */
    fun packages(userId: Int): String = transact(
        code = LwServiceProtocol.PACKAGES,
        write = { writeInt(userId) },
        read = { readString().orEmpty() },
    )

    /**
     * 一件事: 白名单里的一个操作, 配几个参数
     *
     * 回的是"退出码 + 原话", 因为读回校验在这条路的上一层 (`LwSystemCommand`) 做: 这里只如实搬运,
     * 不判断成功 —— 这台设备上"退出码 0 但什么都没发生"已经见过不止一次
     *
     * @param arguments 最多四个, 由特权那边再截一次
     * @param timeoutMs 那一条命令自己能跑多久
     */
    fun systemCommand(
        operation: String,
        userId: Int,
        arguments: List<String>,
        timeoutMs: Long,
    ): SystemOutput = transact(
        code = LwServiceProtocol.SYSTEM_COMMAND,
        write = {
            writeString(operation)
            writeInt(userId)
            writeStringList(arguments)
            writeLong(timeoutMs)
        },
        read = { SystemOutput(readInt(), readString().orEmpty()) },
    )

    /**
     * Linux 沙盒的一问一答
     *
     * @param operation install / run / status / reset
     * @param command op=run 时交给沙盒里 `/bin/sh -lc` 的那一段, 别的操作是空串
     * @param work op=run 时绑到 guest `/work` 的宿主目录, 别的操作是空串
     * @param timeoutMs op=run 那条命令自己最多跑多久
     * @param source op=install 时那一份 `.tar.gz` 的路径 (应用那一侧的外部媒体目录里)
     * @param version op=install 时写进戳里的版本串
     */
    fun linux(
        operation: String,
        command: String,
        work: String,
        timeoutMs: Long,
        source: String,
        version: String,
    ): SystemOutput = transact(
        code = LwServiceProtocol.LINUX,
        write = {
            writeString(operation)
            writeString(command)
            writeString(work)
            writeLong(timeoutMs)
            writeString(source)
            writeString(version)
        },
        read = { SystemOutput(readInt(), readString().orEmpty()) },
    )

    /** 点亮或熄灭屏幕 */
    fun power(on: Boolean): SystemOutput = transact(
        code = LwServiceProtocol.POWER,
        write = { writeInt(if (on) 1 else 0) },
        read = { SystemOutput(readInt(), readString().orEmpty()) },
    )

    /**
     * 多指手势
     *
     * 与 `swipe` 一样是一次事务: 步伐的快慢决定平台读到的是手势还是一次跳, 而一步一个往返会让它跟着
     * binder 的忙闲飘
     */
    fun gesture(
        displayId: Int,
        paths: List<LwInput.Path>,
        durationMs: Long,
    ) = transact(
        code = LwServiceProtocol.GESTURE,
        write = {
            writeInt(displayId)
            writeInt(paths.size)
            paths.forEach { path ->
                writeInt(path.points.size)
                path.points.forEach { (x, y) ->
                    writeFloat(x)
                    writeFloat(y)
                }
            }
            writeLong(durationMs)
        },
        read = { },
    )

    /** 捏合, 由特权那边按两条直线路径展开 */
    fun pinch(
        displayId: Int,
        x: Float,
        y: Float,
        scale: Float,
        durationMs: Long,
    ) = transact(
        code = LwServiceProtocol.PINCH,
        write = {
            writeInt(displayId)
            writeFloat(x)
            writeFloat(y)
            writeFloat(scale)
            writeLong(durationMs)
        },
        read = { },
    )

    /** 一个字符串列表, 长度跟着内容走 */
    private fun Parcel.writeStringList(values: List<String>) {
        writeInt(values.size)
        values.forEach { writeString(it) }
    }

    /**
     * 录一段真手指的动作, 回来的是一串步骤 (JSON)
     *
     * @param finish true 表示这一段录完了: 那一侧把手里的节点关掉, 没走完的那条笔画不再留着
     * @return 读不成的理由 (成了就是 null), 以及这一段里**已经走完**的那几条笔画
     */
    fun touchRecord(finish: Boolean): TouchChunk = transact(
        code = LwServiceProtocol.TOUCH_RECORD,
        write = { writeInt(if (finish) 1 else 0) },
        read = {
            val problem = readString()?.takeIf { it.isNotBlank() }
            val steps = readString().orEmpty()
            TouchChunk(problem, steps, readInt())
        },
    )

    /**
     * Press and lift at one point
     *
     * @param holdMs how long the finger stays down. A hold at or past [LONG_PRESS_MS] is what a long
     *   press is, and a longer one is a press that is kept down - a voice message being recorded, a
     *   drag that has to be started by holding - so the duration is the caller's to choose
     */
    fun tap(displayId: Int, x: Float, y: Float, holdMs: Long) = transact(
        code = LwServiceProtocol.INPUT_TAP,
        write = {
            writeInt(displayId)
            writeFloat(x)
            writeFloat(y)
            writeLong(holdMs)
        },
        read = { },
    )

    /** One moment of a finger, which is what makes a drag feel like a drag */
    fun touch(displayId: Int, action: Int, x: Float, y: Float): Unit = transact(
        code = LwServiceProtocol.INPUT_TOUCH,
        write = {
            writeInt(displayId)
            writeInt(action)
            writeFloat(x)
            writeFloat(y)
        },
        read = { },
    )

    /**
     * Drag from one point to another over the given duration
     *
     * One transaction rather than a caller's worth of `touch` calls: the pacing decides whether the
     * platform reads a gesture or a jump, and a round trip per step would make that pacing depend
     * on how busy the binder is
     *
     */
    fun swipe(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ) = transact(
        code = LwServiceProtocol.INPUT_SWIPE,
        write = {
            writeInt(displayId)
            writeFloat(fromX)
            writeFloat(fromY)
            writeFloat(toX)
            writeFloat(toY)
            writeLong(durationMs)
        },
        read = { },
    )

    /**
     * Press and release one key
     *
     * A key is not a touch and cannot be one: BACK, HOME and the volume keys are handled by the
     * platform rather than drawn by an app, so no tree has them and no coordinate can reach them.
     * The `input` command would, and it is refused for this app's uid exactly like `am` is, which
     * leaves this transaction as the only way to press one
     *
     * @param holdMs how long the key is held down before it is released. What a widget or the policy
     *   reads as a long press is the flag on the release rather than the time between the two
     *   events, so a hold at or past [LONG_PRESS_MS] is lifted with `FLAG_LONG_PRESS`, which is what
     *   the platform's own `input keyevent --longpress` does
     * @returns whether the device took both halves of the press
     */
    fun key(displayId: Int, keyCode: Int, holdMs: Long): Boolean = transact(
        code = LwServiceProtocol.INPUT_KEY,
        write = {
            writeInt(displayId)
            writeInt(keyCode)
            writeLong(holdMs)
        },
        read = { readInt() == 1 },
    )

    /**
     * Type a string as key presses
     *
     * This is the narrow half of typing and exists for one case: a screen with no field in its tree
     * to set the text on, so the only thing left to do is press the keys a virtual keyboard would
     * have pressed. What that keyboard can produce is all it can produce
     *
     * @returns how many characters were delivered, or -1 when this device's keyboard cannot
     *   produce the text at all (Chinese, an emoji)
     */
    fun text(displayId: Int, text: String): Int = transact(
        code = LwServiceProtocol.INPUT_TEXT,
        write = {
            writeInt(displayId)
            writeString(text)
        },
        read = { readInt() },
    )

    /**
     * What the phone's own touchscreen has seen lately
     *
     * Asked over the channel rather than read here because the nodes belong to the `input` group,
     * and asked once per acting call rather than subscribed to, because a caller that is about to
     * touch the phone wants to know now
     */
    /**
     * Ask for a picture of the screen at a path this side can read
     *
     * The file is written by the privileged process, so the caller has to be able to read what
     * that uid leaves behind - which holds for the app's own directories and nothing else
     *
     * @returns an empty string once a picture is there, otherwise why there is none: 那句话要能分开
     *   "屏已经不在"与"屏在而这一帧没画出来", 两件事的处置完全不同, 所以这里回的是理由而不是布尔
     */
    fun screenshot(displayId: Int, path: String): String = transact(
        code = LwServiceProtocol.SCREENSHOT,
        write = {
            writeInt(displayId)
            writeString(path)
        },
        read = { readString().orEmpty() },
    )

    /**
     * Turn this app's accessibility service on or off
     *
     * One more thing the app cannot do for itself, and the only reason the user does not have to
     * find the service in system settings: it is a secure setting, and this process holds the
     * permission to write one
     */
    fun setAccessibility(enabled: Boolean): Boolean = transact(
        code = LwServiceProtocol.A11Y_SET,
        write = { writeInt(if (enabled) 1 else 0) },
        read = { readInt() == 1 },
    )

    /**
     * 把本应用的通知监听放进 (或拿出) 设备的已授权名单
     *
     * 与 [setAccessibility] 同一个理由: 这是一条 `Settings.Secure`, 而只有特权进程能写
     */
    fun setNotificationListener(enabled: Boolean): Boolean = transact(
        code = LwServiceProtocol.NOTIFICATION_LISTEN,
        write = { writeInt(if (enabled) 1 else 0) },
        read = { readInt() == 1 },
    )

    /**
     * Start an activity on one screen, answering with the command's exit code and its output
     *
     * This is the `am` command, run where it is allowed to run. It calls itself the shell's package
     * name, so an app uid is refused it outright - measured, not assumed - and that is the whole
     * reason starting an app for a screen has to come through here
     *
     * @param userId the Android user to start it for. Zero is the person holding the phone; any
     *   other is a user of their own, which is the only thing that tells a cloned app from the one
     *   it was cloned from: same package, same activity, different data
     */
    fun launch(
        displayId: Int,
        userId: Int,
        packageName: String,
        component: String,
        timeoutMs: Long,
    ): Pair<Int, String> = transact(
        code = LwServiceProtocol.LAUNCH,
        write = {
            writeInt(displayId)
            writeInt(userId)
            writeString(packageName)
            writeString(component)
            writeLong(timeoutMs)
        },
        read = {
            val output = readString().orEmpty()
            val code = readInt()
            code to output
        },
    )

    /**
     * One transaction, with both parcels handled in exactly one place
     *
     * A dead process makes transact throw, and a service that failed writes its exception into the
     * reply, which readException rethrows on this side - so both are the caller's to catch
     */
    private fun <T> transact(code: Int, write: Parcel.() -> Unit = { }, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(LwServiceProtocol.DESCRIPTOR)
            data.write()
            remote.transact(code, data, reply, 0)
            reply.readException()
            return reply.read()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Write a surface, or the fact that there is none
 *
 * It is spelled out rather than left to `writeTypedObject` because the value is legitimately
 * absent: a screen outlives the preview that shows it, and detaching has to be sayable
 */
private fun Parcel.writeSurface(surface: Surface?) {
    if (surface == null) {
        writeInt(0)
        return
    }
    writeInt(1)
    surface.writeToParcel(this, 0)
}

/** The other half of [writeSurface] */
internal fun Parcel.readSurface(): Surface? = if (readInt() == 0) null else Surface.CREATOR.createFromParcel(this)
