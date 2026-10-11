package io.github.yuloong07star.luwi.tool

import io.github.yuloong07star.luwi.channel.KeyCodes
import io.github.yuloong07star.luwi.channel.LwServiceProtocol
import io.github.yuloong07star.luwi.channel.PrivilegedChannel
import kotlinx.serialization.json.JsonObject

/**
 * 那些只有特权 uid 才做得动的事
 *
 * 卸载、清数据、停应用、装包、飞行模式、移动数据与蓝牙开关、熄屏 —— 非 root / shell 的应用调不动
 * 它们 (`am` 与 `pm` 对 app uid 直接拒绝), 所以一律转给特权进程。**白名单写在特权那一侧**
 * (`LwSystemCommandTable`): 这一层送过去的只是一个操作名与几个参数, 那边对不上就拒
 *
 * **2026-10-08 起应用这一侧不再弹确认框** (主人的口径: 那五条改由提示词层问一次)。所以这一层
 * 只剩"送过去并如实报回执": 该不该做, 由模型在会话里问过用户之后自己决定 —— `lw_app_control`
 * 的工具描述与技能 `android-device-control` 里那条红线就是这条规矩的落点
 */
internal object LwSystemCommand {

    /** 一次调用送过去的参数上限, 与特权那边的表对齐 (通用 intent 那条更宽, 由那张表自己说) */
    private const val MAX_ARGS = 4

    /** 组合键最多几个键: 三个修饰键加一个主键, 再长已经不是快捷键了 */
    private const val MAX_COMBO_KEYS = 4

    /** 一个包名 / 一个组件名 / 一个动作长什么样。这一层就是参数拼法的那道校验 */
    private val PACKAGE = Regex("""[\w.]+""")
    private val COMPONENT = Regex("""[\w.]+/[\w.$]+""")
    private val ACTION = Regex("""[\w.]+""")

    /**
     * 一件事做完没有
     *
     * 走 [LwServiceProtocol.SYSTEM_COMMAND] 那个事务: 目标操作、用户、参数与超时一起送过去, 回来
     * 的是特权进程自己的话说做了什么
     */
    fun run(
        operation: String,
        arguments: List<String> = emptyList(),
        userId: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val service = PrivilegedChannel.ensure()
            ?: throw IllegalStateException(
                "the privileged channel is not available, so ${operation} cannot be run:" +
                    " " + (PrivilegedChannel.state().error ?: "no reason reported"),
            )
        val bounded = arguments.take(MAX_ARGS)
        val answer = service.systemCommand(operation, userId, bounded, timeoutMs)
        return Result(answer.code, answer.output)
    }

    /**
     * 一件破坏性的事: 直接做, 并把做了什么原样报回去
     *
     * 应用侧那道确认框已经撤掉, 所以这里不再有等待与超时 —— 该不该做由提示词那一层管 (见文件头)
     */
    private fun destructive(
        operation: String,
        target: String,
        arguments: List<String> = emptyList(),
        userId: Int = 0,
        timeoutMs: Long = INSTALL_TIMEOUT_MS,
    ): JsonObject {
        val what = when (operation) {
            "uninstall" -> "uninstall $target"
            "clearData" -> "clear all data of $target"
            "forceStop" -> "force stop $target"
            "install" -> "install $target"
            // 从这一侧读得到的一份文件装 (`pm` 给的是路径, system_server 读不了 FUSE 上的东西,
            // 所以那一侧先抄进 /data/local/tmp —— 见 LwSystemCommandTable 的 installStaged)
            "installStaged" -> "install $target (staged through /data/local/tmp first)"
            "disable" -> "disable $target, which takes it off the launcher until it is enabled again"
            else -> "$operation $target"
        }
        val result = run(operation, arguments.ifEmpty { listOf(target) }, userId, timeoutMs)
        return text(
            "$what: " + result.output.ifBlank { "the privileged side said nothing" } +
                if (result.code == 0) "" else " (exit code ${result.code})",
        )
    }

    /**
     * 这一批完整的入口: 破坏性的那五条与开关那几条都直接做, 区别只在回执怎么写
     *
     * **`disable` 仍然算破坏性**: 它比停应用更粘 —— 停掉的东西下次启动就回来了, 而被停用的应用从
     * 桌面上消失, 要有人记得回设置里打开。它在提示词那一层与另外四条一个待遇 (先问用户)
     */
    suspend fun dispatch(request: JsonObject): JsonObject = when (val op = request.string("op")) {
        // `installStaged` 与 `install` 的差别只有一个: 前者把路径当"这一侧自己读得到的一份文件",
        // 先抄进 /data/local/tmp 再交出去。它**不在** `lw_app_control` 那个 op 名单里 —— 那是插件
        // 装机链自己走的 (PluginCompanion), 不给模型多开一个口子, 与 `plugin op=grant` 同一个道理
        "forceStop", "clearData", "uninstall", "install", "installStaged", "disable" -> destructive(op, request)
        "airplane", "data", "wifi", "bluetooth" -> text(toggle(request, op))
        "enable", "setHome", "openUrl", "intent" -> appControl(op, request)
        "keyCombo" -> keyCombo(request)
        else -> throw IllegalArgumentException(
            "op has to be one of forceStop, clearData, uninstall, install, installStaged, disable, enable," +
                " setHome, openUrl, intent, keyCombo, airplane, data, wifi, bluetooth," +
                " not \"$op\"",
        )
    }

    /**
     * 1.0.3 那几条应用控制
     *
     * 参数在**这一侧**拼好再送过去 (白名单那一侧只认操作名与几个参数, 拼法不归调用方管), 所以这里的
     * 校验就是那道边界: 一个不像包名的字符串、一个不像组件的字符串都在这里被拒成一句人话, 而不是
     * 交给 `am` / `pm` 去猜
     */
    private fun appControl(op: String, request: JsonObject): JsonObject {
        val userId = request.int("user", 0)
        // **intent 那条不读 package**: 它的组件是可选的, 而 `string()` 在缺字段时会直接抛 —— 一个
        // "必须给包名" 的要求会把不带组件的 intent 整条堵死 (第一版就是这么写的)
        val target = if (op == "intent") "" else request.string("package").trim()
        val arguments = when (op) {
            "enable" -> listOf(require(target, PACKAGE, "a package name"))
            "setHome" -> listOf(require(target, COMPONENT, "a component, as package/class"))
            "openUrl" -> {
                require(target.startsWith("http://") || target.startsWith("https://")) {
                    "openUrl takes an http or https address, not \"$target\""
                }
                // `-d <url>` 之后跟目标屏: 不带 userId, 否则 `--user` 会插进 `-d` 与它的值中间
                listOf("-d", target, "--display", request.int("displayId", 0).toString())
            }

            else -> intentArguments(request)
        }
        // openUrl 的 --user 位置已经被 -d 占了, 所以那一条按主用户跑
        val result = run(op, arguments, if (op == "openUrl") 0 else userId)
        val said = result.output.ifBlank { "the privileged side said nothing" }
        val what = when (op) {
            "enable" -> "enabled $target"
            "setHome" -> "made $target the home app"
            "openUrl" -> "opened $target"
            else -> "started " + (
                request.string("component", "").trim().ifEmpty { request.string("action", "").trim() }
                    .ifEmpty { "the intent" }
                )
        }
        return text(
            "$what: $said" + if (result.code == 0) "" else " (exit code ${result.code})",
        )
    }

    /**
     * 通用 intent 的参数: 动作 / 数据 / 组件 / 目标屏
     *
     * 每一样都占两个位置 (`-a <action>`), 所以这一条在特权那张表里有一个更宽的上限。**至少要有动作或
     * 组件**: 只给一块屏等于让 `am start` 去起一个空 intent, 那不是调用方想要的
     */
    private fun intentArguments(request: JsonObject): List<String> {
        val arguments = mutableListOf<String>()
        val action = request.string("action", "").trim()
        if (action.isNotEmpty()) {
            arguments += listOf("-a", require(action, ACTION, "an action, as android.intent.action.X"))
        }
        val data = request.string("data", "").trim()
        if (data.isNotEmpty()) arguments += listOf("-d", data)
        val component = request.string("component", "").trim()
        if (component.isNotEmpty()) {
            arguments += listOf("-n", require(component, COMPONENT, "a component, as package/class"))
        }
        require(arguments.isNotEmpty()) {
            "an intent needs an action or a component: on its own, a display is not something to start"
        }
        arguments += listOf("--display", request.int("displayId", 0).toString())
        return arguments
    }

    /**
     * 组合键: `input keycombination KEYCODE_CTRL_LEFT KEYCODE_A`
     *
     * 键**传名字不传编号**的规矩在这里一样: 名字不认识可以拒, 而编号不认识就是另一个键
     */
    private fun keyCombo(request: JsonObject): JsonObject {
        val asked = request.strings("keys").map { it.trim() }.filter { it.isNotEmpty() }
        require(asked.size >= 2) {
            "a combination is at least two keys, and \"${asked.joinToString("\" \"")}\" is" +
                " ${asked.size}: send them in the order they should be held, modifiers first"
        }
        val codes = asked.take(MAX_COMBO_KEYS).map { name ->
            KeyCodes.resolve(name) ?: throw IllegalArgumentException(
                "Android has no key called \"$name\". Some of the ones it has: " +
                    KeyCodes.COMMON.joinToString(", "),
            )
        }
        // `input` 认 KEYCODE_ 开头的名字, 也认裸编号; 表里有名字的给名字, 没有的给编号
        val keys = codes.map { code ->
            val name = KeyCodes.nameOf(code)
            if (name.toIntOrNull() == null) "KEYCODE_$name" else name
        }
        val result = run("keyCombo", keys)
        return text(
            "held ${keys.joinToString(" + ")}: " +
                result.output.ifBlank { "the privileged side said nothing" } +
                if (result.code == 0) "" else " (exit code ${result.code})",
        )
    }

    /** 一个参数长得对才让它过去 */
    private fun require(value: String, pattern: Regex, what: String): String {
        require(pattern.matches(value)) { "\"$value\" is not $what" }
        return value
    }

    /**
     * 破坏性的一条: 要做什么直接说清楚
     *
     * 四种操作的目标都是那一个参数 (要装的包给的是 APK 路径), 所以参数表在这里拼, 不由调用方拼
     */
    private fun destructive(operation: String, request: JsonObject): JsonObject =
        destructive(
            operation = operation,
            target = request.string("package"),
            arguments = listOf(request.string("package")),
            userId = request.int("user", 0),
        )

    /** 开关类的: 先读现在什么样, 再改成相反的那个 */
    private fun toggle(request: JsonObject, what: String): String {
        val wanted = request.bool("on", true)
        val result = run(what, listOf(if (wanted) "on" else "off"))
        return "turned $what " + (if (wanted) "on" else "off") + ": " +
            result.output.ifBlank { "no output" } +
            if (result.code == 0) "" else " (exit code ${result.code})"
    }

    /** 熄屏 / 唤醒: 由 [LwPower] 调 */
    fun power(request: JsonObject): JsonObject {
        val on = request.string("op") == "on"
        val result = run(if (on) "wake" else "sleep")
        return text(
            (if (on) "woke" else "turned off") + " the screen through the privileged side: " +
                result.output.ifBlank { "no output" } +
                if (result.code == 0) "" else " (exit code ${result.code})",
        )
    }

    /** 网络开关: 由 [LwSystem.net] 的路由调 */
    fun network(request: JsonObject): JsonObject {
        val what = request.string("op")
        if (what !in setOf("airplane", "data", "wifi", "bluetooth")) {
            throw IllegalArgumentException("net can change airplane, data, wifi or bluetooth")
        }
        return text(toggle(request, what))
    }

    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** 装一个包要解压、要校验, 30 秒不够 */
    const val INSTALL_TIMEOUT_MS = 120_000L
}

/** 一次特权命令的结果 */
data class Result(val code: Int, val output: String)
