package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.util.Log
import io.github.yuloong07star.luwi.channel.LwLinuxSandbox
import io.github.yuloong07star.luwi.channel.PrivilegedChannel
import io.github.yuloong07star.luwi.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Linux 沙盒: 模型那一侧的四件事 (装 / 跑 / 问 / 删)
 *
 * 这一层是**应用进程**里的那一半, 它做三件特权进程做不了或不该管的事:
 *
 * 1. 把随包的 base 从 APK 里抄出来 —— asset 只有应用进程读得到, 而特权那一侧要不就是读不到
 *    (app data 目录), 要不就不该拿着一条"从哪读"的路径当参数 (它只认自己那本账里的东西)
 * 2. 校验 `work`: 会话工作区必须是这个 uid 读得到的真目录, 且不能是 app 自己的 files 目录
 *    (沙盒跑在另一个 uid 上, 那个目录它进不去 —— 早说一句比让 proot 在 bind 上摔一跤好)
 * 3. 状态: 通道不在线时也能把"装没装、多大"读出来 (两个目录都是世界可读的, 见 docs 里的实测)
 *
 * 真正跑命令的那一半在特权进程里 (`LwLinuxSandbox`), 命令形状由那边拼 —— 这里只送操作名、一段
 * 命令文本、一个 work 目录与一个超时
 */
internal object LwLinux {

    private const val TAG = "LwLinux"

    /**
     * 随包的 base, 一个 ABI 一份不需要: 这个 app 只有 arm64 一套原生库
     *
     * 名字是 `.tgz` 不是 `.tar.gz`, 这是被 AGP 逼的: mergeDebugAssets 会把 `.gz` 结尾的资产
     * **解压并把后缀去掉** (实测 `base.tar.gz` 进 APK 就成了明文 `base.tar`), 而那一步之后没法再按
     * gzip 校验, 也在 APK 里找不到原来那个名字 —— `.tgz` 不受这条规矩管, 原样进 APK (实测)
     */
    private const val ASSET = "lwlinux/base.tgz"

    /** 装进戳里的版本串, 换 base 的时候与上面那份 asset 一起换 */
    const val BASE_VERSION = "alpine-3.24.0"

    /** 抄 asset 的落脚处: 应用的外部媒体目录 (不需要任何权限, 特权那一侧也读得到) */
    private const val STAGING_DIRECTORY = "lwlinux"
    private const val STAGED_ARCHIVE = "base.tar.gz"

    /** 一次 run 的默认预算, 与特权那一侧的钳制同一套数 */
    const val DEFAULT_TIMEOUT_MS = 120_000L
    const val MAX_TIMEOUT_MS = 600_000L

    /** 一次操作的结果: 退出码 + 给人看的那段话 */
    data class Report(val code: Int, val text: String) {
        val ok: Boolean get() = code == 0
    }

    /** 设置页那一段要的东西 */
    data class State(
        val installed: Boolean,
        val version: String?,
        val sizeKb: Long?,
        val running: Int,
        val channelConnected: Boolean,
        val error: String?,
    )

    /**
     * 桥方法的那张表: host 那一侧的 `lw_linux` 一条一条落到这里
     *
     * 与别的工具同一个规矩: 出一句人话放在 `{"text": ...}` 里, 出错就抛 (桥会把原因送回模型)
     */
    fun dispatch(request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> text(statusSentence(readState(requireContext())))
        "install" -> {
            val report = install(requireContext())
            text(report.text)
        }

        "reset" -> {
            val report = reset()
            text(report.text)
        }

        "run" -> {
            val command = request.string("command")
            val work = request.string("work", "")
            val timeoutMs = request["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
            val report = run(requireContext(), work, command, timeoutMs)
            text(report.text)
        }

        else -> throw IllegalArgumentException(
            "op has to be one of run, install, status or reset, not \"$op\"",
        )
    }

    /**
     * 在沙盒里跑一段命令
     *
     * @param work 会话工作区, 空串表示"按默认工作区算" (没有会话的调用者)
     */
    fun run(context: Context, work: String, command: String, timeoutMs: Long): Report {
        val resolved = resolveWork(context, work) ?: return Report(
            2,
            "this call has no workspace to bind at /work: give it a session with a directory inside" +
                " the app's workspace, or set the workspace up first",
        )
        val problem = workProblem(context, resolved)
        if (problem != null) return Report(2, problem)
        val service = PrivilegedChannel.ensure() ?: return Report(
            2,
            "the privileged channel is not available, and the sandbox runs through it:" +
                " " + (PrivilegedChannel.state().error ?: "no reason reported"),
        )
        val answer = service.linux(
            operation = "run",
            command = command,
            work = resolved,
            timeoutMs = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS),
            source = "",
            version = "",
        )
        return Report(answer.code, answer.output)
    }

    /**
     * 装一份 base: 抄 asset -> 特权那一侧解压 -> 写戳
     *
     * 这是个慢动作 (几秒到十几秒) 且**会盖掉整个 rootfs**, 所以只该在用户点头之后做 (设置页那颗
     * 按钮, 或者模型问过之后)
     */
    fun install(context: Context): Report {
        val archive = try {
            stage(context)
        } catch (error: Throwable) {
            return Report(2, "could not copy the bundled base out of this app: ${error.message}")
        }
        val service = PrivilegedChannel.ensure() ?: return Report(
            2,
            "the privileged channel is not available, and unpacking the sandbox needs it:" +
                " " + (PrivilegedChannel.state().error ?: "no reason reported"),
        )
        return try {
            val answer = service.linux(
                operation = "install",
                command = "",
                work = "",
                timeoutMs = 0L,
                source = archive.absolutePath,
                version = BASE_VERSION,
            )
            val report = Report(answer.code, answer.output)
            if (report.ok) Log.i(TAG, "sandbox installed: ${report.text}")
            report
        } finally {
            // 这一份是应用自己的临时拷贝, 装完就收掉 —— 要重装随时能再抄一份出来
            archive.delete()
        }
    }

    /** 删掉整个沙盒 (rootfs 与里面装的一切), 由特权那一侧执行 */
    fun reset(): Report {
        val service = PrivilegedChannel.ensure() ?: return Report(
            2,
            "the privileged channel is not available, and deleting the sandbox needs it:" +
                " " + (PrivilegedChannel.state().error ?: "no reason reported"),
        )
        val answer = service.linux(
            operation = "reset",
            command = "",
            work = "",
            timeoutMs = 0L,
            source = "",
            version = "",
        )
        return Report(answer.code, answer.output)
    }

    /**
     * 读一次状态
     *
     * 通道在线就问特权那一侧 (它那个 `du` 与"有几条命令在跑"只有它有); 不在线就在这一侧读那两个
     * 目录 —— 装没装、多大与通道无关, 而设置页在 Shizuku 没连的时候更要如实说话
     */
    fun readState(context: Context): State {
        val service = PrivilegedChannel.state().takeIf { it.connected }
        if (service != null) {
            val proxy = PrivilegedChannel.ensure()
            if (proxy != null) {
                runCatching {
                    val answer = proxy.linux("status", "", "", 0L, "", "")
                    if (answer.code == 0) {
                        return parseState(answer.output, channelConnected = true, error = null)
                    }
                    Log.w(TAG, "the privileged side refused a status call: ${answer.output}")
                }.onFailure { Log.w(TAG, "asking the privileged side for the sandbox status failed", it) }
            }
        }
        return localState(context)
    }

    /** 那一行给人看的状态 */
    private fun statusSentence(state: State): String = buildList {
        add(
            when {
                state.installed -> "the Linux sandbox is installed" + (state.version?.let { " ($it)" } ?: "")
                else -> "the Linux sandbox is not installed"
            },
        )
        state.sizeKb?.let { add("it takes ${bytes(it * 1024)} under ${LwLinuxSandbox.BASE_DIRECTORY}") }
        add("${state.running} command(s) running right now")
        add(
            if (state.channelConnected) {
                "the privileged channel is up, so op=install / op=run / op=reset all work"
            } else {
                "the privileged channel is down (${state.error ?: "no reason reported"}), so only this" +
                    " answer works: run, install and reset all need it"
            },
        )
    }.joinToString("; ")

    /** 特权那一侧给的四行文本 */
    private fun parseState(output: String, channelConnected: Boolean, error: String?): State {
        val fields = output.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        return State(
            installed = fields["installed"] == "true",
            version = fields["version"]?.takeIf { it.isNotBlank() },
            sizeKb = fields["sizeKb"]?.toLongOrNull(),
            running = fields["running"]?.toIntOrNull() ?: 0,
            channelConnected = channelConnected,
            error = error,
        )
    }

    /** 不问通道也能说的那一半: 目录在不在、戳里写的 version、自己走一遍算大小 */
    private fun localState(context: Context): State {
        val base = File(LwLinuxSandbox.BASE_DIRECTORY)
        val rootfs = File(LwLinuxSandbox.ROOTFS_DIRECTORY)
        val stamp = File(LwLinuxSandbox.STAMP_FILE)
        val version = if (stamp.isFile) {
            runCatching {
                stamp.readLines().firstOrNull { it.startsWith("version=") }?.removePrefix("version=")
            }.getOrNull()
        } else {
            null
        }
        // bin/busybox 而不是 bin/sh: Alpine 的 /bin/sh 是指向绝对路径 /bin/busybox 的软链, 宿主
        // 解析它会落到宿主自己的 /bin 上 (见 LwLinuxSandbox.isInstalled 的注释)
        val installed = rootfs.isDirectory && File(rootfs, "bin/busybox").isFile
        val sizeKb = if (installed) walkKilobytes(rootfs) else null
        val channel = PrivilegedChannel.state()
        return State(
            installed = installed,
            version = version,
            sizeKb = sizeKb,
            running = 0,
            channelConnected = channel.connected,
            error = channel.error,
        )
    }

    /**
     * 应用这一侧数一遍大小 (通道不在线时用)
     *
     * 往下走不动的目录跳过就是了: 沙盒里 700 的目录 (比如 /root) 这一侧读不到, 少算一点比整段
     * 失败好 —— 它本来就只是设置页里的一行
     */
    private fun walkKilobytes(directory: File): Long {
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(directory)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = runCatching { current.listFiles() }.getOrNull() ?: continue
            children.forEach { child ->
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    bytes += runCatching { child.length() }.getOrDefault(0L)
                }
            }
        }
        return bytes / 1024
    }

    /** 把 asset 抄进应用的外部媒体目录, 返回抄出来那一份 */
    private fun stage(context: Context): File {
        @Suppress("DEPRECATION")
        val media = context.getExternalMediaDirs()?.firstOrNull()
            ?: throw IllegalStateException("this device gives the app no external media directory")
        val directory = File(media, STAGING_DIRECTORY)
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("could not create ${directory.absolutePath}")
        }
        val target = File(directory, STAGED_ARCHIVE)
        context.assets.open(ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "staged ${target.absolutePath} (${target.length()} bytes)")
        return target
    }

    /** 这一场会话的 work 目录; 空的就是"按默认工作区算", 拿不到就 null */
    private fun resolveWork(context: Context, work: String): String? {
        if (work.isNotBlank()) return work
        return Workspace.resolve(context).directory.absolutePath
    }

    /**
     * work 在这一侧先过的几道形状
     *
     * 与特权那一侧那道校验是同一套判据, 这里多说一句话是为了两种常见情形能早点讲清楚: 会话的目录
     * 在 app 私有存储里 (沙盒那个 uid 进不去), 或者给的不是个目录
     */
    private fun workProblem(context: Context, work: String): String? {
        if (!work.startsWith("/")) {
            return "the workspace directory has to be an absolute path, not \"$work\""
        }
        if (work.any { it == ':' || it.code < 0x20 }) {
            return "\"$work\" has a character that cannot be part of a bind value (a colon or a control character)"
        }
        val directory = File(work)
        if (!directory.isDirectory) {
            return "the workspace directory \"$work\" does not exist (or is not a directory): the sandbox" +
                " binds it at /work, so it has to be there before the command runs"
        }
        val privateRoot = context.filesDir?.canonicalPath
        if (privateRoot != null && directory.canonicalPath.startsWith(privateRoot)) {
            return "\"$work\" is inside this app's private storage, which the sandbox cannot read: it runs" +
                " as a different uid. Put the workspace on shared storage (the usual /sdcard/DSH)" +
                " and the same command works"
        }
        return null
    }
}
