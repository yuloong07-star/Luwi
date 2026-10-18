package io.github.yuloong07star.luwi.channel

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Linux 沙盒在特权进程这一侧的落点
 *
 * 跑的是随包发的 Alpine minirootfs + Termux 编的 proot, 而 proot 从 [Context] 里那个
 * `nativeLibraryDir` 里 exec —— app 的 data 目录在 targetSdk 37 上是 exec 不了的, 随包的二进制
 * 一律当 native lib 发, 就是这一条 (见 `docs/host-build.md`)
 *
 * **命令形状写死在这里, 一个字节都不由调用方拼**: proot 路径、loader 路径、binds、`-r` 的根、甚至
 * 环境 (env -i 之后只剩几个写死的变量) 都在这个文件里定; 送过来的只有一段交给 `/bin/sh -lc` 的命令
 * 文本与一个 work 目录。`work` 是唯一进得去命令行的外部字符串, 所以它单独过一道形状校验 (见 [workProblem]),
 * 而它只能落在 `-b <work>:/work` 的那个值上 —— 换不了可执行文件, 也拼不出第二个 proot 参数
 *
 * **不是一个安全边界**, 这一点写在设置页与 README 里: 这一层能提供的只有"沙盒看不见 /work 以外的
 * 东西"这条 proot 层的视野约定. 真正内核级的那一条是 uid: 沙盒以 shell (或 root) 身份跑, 而
 * `/data/data/<Luwi>` 是 0700 的 app uid —— 读不到的
 *
 * 数据都放 `/data/local/tmp/lwlinux/`, 理由有两个: 特权那一侧读得到 (app data 目录它读不到),
 * 而且那是真文件系统 —— `/sdcard` 是 FUSE, 符号链接、exec 位、权限位都不完整, 放不下一个 rootfs
 */
internal object LwLinuxSandbox {

    private const val TAG = "LwLinux"

    /** 沙盒的家。`/data/local/tmp` 是这台设备上特权 uid 与 app 都看得到的那个真文件系统 */
    const val BASE_DIRECTORY = "/data/local/tmp/lwlinux"

    /** Alpine 的根, `tar` 就解在这儿 */
    const val ROOTFS_DIRECTORY = "$BASE_DIRECTORY/rootfs"

    /** proot 自己的临时目录 (`PROOT_TMP_DIR`), 也是它的 loader 换名时落东西的地方 */
    const val TEMP_DIRECTORY = "$BASE_DIRECTORY/tmp"

    /** 装好之后写的戳: 内容就是 base 的版本串, 状态那一问按它判"装没装" */
    const val STAMP_FILE = "$BASE_DIRECTORY/stamp"

    /**
     * 组长 pid 的记账目录: **不在 [BASE_DIRECTORY] 里面**, 这是关键
     *
     * pid 文件是给超时的"按进程组收人"用的 (见 [execute]). 它**不能**住在 [TEMP_DIRECTORY] 里:
     * [reset] 的第一步就是 `rm -rf` 整棵 base, 而删得够快时读它的 20 ms 轮询还没来得及看一眼文件
     * 就没了, [waitForLeader] 于是空等满 2 s 兜底 —— 真机实测 reset 墙钟在 ≈1.0 s 与 ≈2.9 s 两个
     * 值之间跳, 差值恰好是那 2 s (2026-10-12 逮到). 挪出被删的那棵树, 读它就没有这层赛跑
     */
    const val RUN_DIRECTORY = "/data/local/tmp/lwlinux-run"

    /** 首装前要留出的空间: 解出来 8 MB 上下, 但随后的 apk add 会把它吃到几百 MB, 所以门槛放宽些 */
    private const val MIN_FREE_KB = 64L * 1024L

    /** apk 仓库的镜像与分支, 首装时写进 rootfs; 分支与随包 base 的版本一起改 (base = Alpine 3.24.x) */
    private const val ALPINE_MIRROR = "https://mirrors.aliyun.com/alpine"
    private const val ALPINE_BRANCH = "v3.24"

    /** 一段命令最多跑多久, 到点就整组收掉 */
    private const val MAX_TIMEOUT_MS = 600_000L
    private const val MIN_TIMEOUT_MS = 1_000L

    /** 装一次 (解压 + 写戳) 的预算 */
    private const val INSTALL_TIMEOUT_MS = 300_000L

    /** 回给调用方的输出: 留最后这么多行, 再多就只说"截断了" */
    private const val OUTPUT_LINES = 200

    /** 读输出时缓冲区最多留多少字节, 超了从头丢 —— 尾巴才是出错时要看的那一段 */
    private const val OUTPUT_BUFFER_BYTES = 512 * 1024

    /** 杀掉一棵进程树时, 先给 SIGTERM 这么久, 再上 SIGKILL */
    private const val KILL_GRACE_MS = 1_500L

    /** 正在跑的命令有几条, `reset` 与 `install` 按它拒 (改一半的 rootfs 比不装更糟) */
    private val running = AtomicInteger(0)

    /** 一次 linux 调用的结论: 退出码 + 特权这一侧自己的话 */
    data class Outcome(val code: Int, val output: String)

    /** 沙盒现在什么样 */
    data class Status(
        val installed: Boolean,
        val version: String?,
        val sizeKb: Long?,
        val running: Int,
    )

    /**
     * 装一份 base
     *
     * 整棵 rootfs 重来一遍 (先 `rm -rf` 再解压) —— 这一条是"修好它"的那扇门, 与 reset 的区别只在
     * 结束状态. 真正的字节来自应用那一侧 (它把 asset 抄进自己的外部媒体目录), 这一侧只做解压与查证
     *
     * @param source 应用那一侧备好的一份 `.tar.gz` 的绝对路径
     * @param version 这一份的版本串, 写进 [STAMP_FILE]
     */
    fun install(context: Context?, source: String, version: String): Outcome {
        if (running.get() > 0) {
            return Outcome(
                2,
                "a command is still running in the sandbox: replacing the rootfs would pull it out" +
                    " from under that command, so this has to wait",
            )
        }
        val archive = File(source)
        val problem = sourceProblem(archive)
        if (problem != null) return Outcome(2, problem)
        val libraryDir = libraryDirectory(context)
            ?: return Outcome(2, "this privileged process cannot see the app's native libraries, so proot has nowhere to come from")
        val binaries = binariesProblem(libraryDir)
        if (binaries != null) return Outcome(2, binaries)

        val free = freeKilobytes()
        if (free != null && free < MIN_FREE_KB) {
            return Outcome(
                2,
                "only ${free} KiB free on /data, and the sandbox wants at least $MIN_FREE_KB KiB" +
                    " before it unpacks its own root filesystem",
            )
        }

        // 戳是"装好了"这件事唯一的那份契约 (与 HostInstaller 同一个形制): 先摘掉旧的, 整棵 rootfs
        // 落齐、网络配置写完才写回去 —— 半途死掉的安装因此读作"没装", 而不是一棵残缺的树冒充已装
        File(STAMP_FILE).delete()
        val rootfs = File(ROOTFS_DIRECTORY)
        if (rootfs.exists() && !deleteTree(rootfs)) {
            return Outcome(2, "could not clear ${rootfs.absolutePath}, so a fresh unpack would be a half tree")
        }
        if (!rootfs.mkdirs() && !rootfs.isDirectory) {
            return Outcome(2, "could not create ${rootfs.absolutePath}")
        }
        File(TEMP_DIRECTORY).mkdirs()
        val extracted = execute(
            command = listOf("tar", "-xzf", archive.absolutePath, "-C", rootfs.absolutePath),
            stdin = null,
            timeoutMs = INSTALL_TIMEOUT_MS,
        )
        if (extracted.code != 0) {
            return Outcome(extracted.code, "unpacking ${archive.name} failed: ${extracted.output}")
        }
        // 两个东西在就说明解出来的是个 Alpine rootfs, 不是一个碰巧叫 base 的 tar
        if (!File(rootfs, "bin/busybox").isFile || !File(rootfs, "etc/apk").isDirectory) {
            return Outcome(2, "${archive.name} does not hold an Alpine root filesystem (no bin/busybox or no etc/apk)")
        }
        val network = writeNetworkConfig(rootfs)
        if (network != null) return Outcome(2, network)
        val stamp = File(STAMP_FILE)
        return try {
            stamp.writeText("version=$version\nat=${System.currentTimeMillis()}\n")
            val sizeKb = directoryKilobytes(rootfs) ?: 0L
            Outcome(
                0,
                "installed $version into $ROOTFS_DIRECTORY" +
                    " ($sizeKb KiB, and `apk add` from inside the sandbox is now the way to grow it)",
            )
        } catch (error: Throwable) {
            Outcome(2, "the rootfs unpacked but the stamp could not be written: ${error.message}")
        }
    }

    /**
     * 删掉整个沙盒
     *
     * 有命令在跑就拒 —— 半删掉的 rootfs 是"看着装了其实坏了", 比没装更难查
     */
    fun reset(): Outcome {
        if (running.get() > 0) {
            return Outcome(2, "a command is still running in the sandbox, so it is not deleted: stop it first")
        }
        val base = File(BASE_DIRECTORY)
        if (!base.exists()) return Outcome(0, "there was nothing at $BASE_DIRECTORY to delete")
        if (!deleteTree(base)) {
            return Outcome(2, "could not delete everything under $BASE_DIRECTORY")
        }
        return Outcome(0, "deleted $BASE_DIRECTORY (the sandbox root filesystem, and everything installed in it)")
    }

    /** 沙盒现在什么样, 只读, 不需要装过 */
    fun status(): Status {
        val rootfs = File(ROOTFS_DIRECTORY)
        val stamp = File(STAMP_FILE)
        val installed = isInstalled()
        val version = if (stamp.isFile) {
            stamp.readLines().firstOrNull { it.startsWith("version=") }?.removePrefix("version=")
        } else {
            null
        }
        return Status(
            installed = installed,
            version = version,
            sizeKb = if (installed) directoryKilobytes(rootfs) else null,
            running = running.get(),
        )
    }

    /**
     * 在沙盒里跑一段命令
     *
     * 一次调用一棵进程树, 调用结束它就没了 (`--kill-on-exit` 收尾, 超时那一侧整组 SIGKILL) ——
     * "后台起一个服务然后退出"在这个形状里活不下来, 那是有意为之: 一条命令一个回执, 不留孤儿
     *
     * @param command 交给 guest 的 `/bin/sh -lc` 的那一段
     * @param work 绑到 guest `/work` 的宿主目录, 会话工作区, 形状先过 [workProblem]
     * @param timeoutMs 跑多久算超时, 到点连整棵树一起收
     */
    fun run(context: Context?, command: String, work: String, timeoutMs: Long): Outcome {
        if (!isInstalled()) {
            return Outcome(
                2,
                "the Linux sandbox has no root filesystem yet: install it first (`lw_linux` op=install," +
                    " or the Linux sandbox section in the app's settings)",
            )
        }
        if (command.isBlank()) return Outcome(2, "op=run needs a command")
        val workProblem = workProblem(work)
        if (workProblem != null) return Outcome(2, workProblem)
        val libraryDir = libraryDirectory(context)
            ?: return Outcome(2, "this privileged process cannot see the app's native libraries, so proot has nowhere to come from")
        val binaries = binariesProblem(libraryDir)
        if (binaries != null) return Outcome(2, binaries)

        val budget = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        File(TEMP_DIRECTORY).mkdirs()
        running.incrementAndGet()
        val result = try {
            execute(
                command = prootCommand(libraryDir, work, command),
                stdin = File("/dev/null"),
                timeoutMs = budget,
            )
        } finally {
            running.decrementAndGet()
        }
        val said = result.output.ifBlank { "(no output)" }
        return when (result.code) {
            124 -> Outcome(
                124,
                "the command did not finish within ${budget}ms and the whole process group was killed:" +
                    " $said (run it again with a larger timeoutMs, up to $MAX_TIMEOUT_MS," +
                    " or write steps that fit; nothing outlives one of these calls)",
            )
            else -> Outcome(result.code, said)
        }
    }

    /**
     * 完整的那条 argv
     *
     * 单独抽出来是为了能被测试直接看见: 这一串就是"命令形状"本身, 而它的每一样都是常量 —— 除了
     * [work] 与 [command]. 三层外面: `setsid` 让整棵树落在自己的进程组里 (超时那一侧按组收人),
     * `env -i` 把特权进程的安卓环境清干净, 只剩四个变量进 guest
     */
    internal fun prootCommand(
        libraryDir: String,
        work: String,
        command: String,
    ): List<String> = buildList {
        add("setsid")
        add("env")
        add("-i")
        add("HOME=/root")
        add("LANG=C.UTF-8")
        add("TERM=dumb")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        add("PROOT_LOADER=$libraryDir/$LOADER_NAME")
        add("PROOT_TMP_DIR=$TEMP_DIRECTORY")
        // 这一层 sh 不是装饰: 安装路径里只要有一个 `=` (真机实测: `...luwi-ztX...myw==/lib/arm64`
        // 这种 base64 尾巴), 把 proot 的路径直接交给 env 就会被它当成一次"名字=值"的赋值吃掉,
        // 于是 env 去 exec 下一个参数, 报出来是 `env: exec -0: No such file or directory`。把路径
        // 放进 `exec "$0"` 的参数位, env 看到的命令就只是 /system/bin/sh, 那一段绕过去
        add("/system/bin/sh")
        add("-c")
        add("exec \"\$0\" \"\$@\"")
        add("$libraryDir/$PROOT_NAME")
        add("-0")
        // 硬链接那一刀: shell 域在 `/data/local/tmp` 上 link() 是被挡的 (实测 EACCES, apk 里
        // binutils / gcc / g++ 三个包装不进去就是因为这个), 而 `--link2symlink` 让 proot 把这些
        // link() 用符号链接顶上 —— proot-distro 在 Termux 上就是靠它过 FUSE 那一关的
        add("--link2symlink")
        add("--kill-on-exit")
        add("-r")
        add(ROOTFS_DIRECTORY)
        add("-b")
        add("$work:/work")
        // guest 的 /dev 里一个节点都没有 (tar 以非 root 解压是 mknod 不了的), 而 >= /dev/null 是
        // 每个正经脚本都假设存在的东西, 所以点名几个直接从宿主 bind 进去. 不整个 bind /dev:
        // 那会把 /dev/block/* 摆到沙盒视野里, 而这里没有什么用得上它们
        DEVICE_BINDS.forEach { device ->
            add("-b")
            add(device)
        }
        add("-w")
        add("/work")
        add("/bin/sh")
        add("-lc")
        add(command)
    }

    /** guest 里那几个从宿主 bind 进去的设备节点与目录 */
    private val DEVICE_BINDS = listOf(
        "/dev/null",
        "/dev/zero",
        "/dev/full",
        "/dev/random",
        "/dev/urandom",
        "/dev/tty",
        "/dev/ptmx",
        "/dev/pts",
    )

    /** 随包的 proot 三个名字, 与 jniLibs 里的一致 (见 tools/lw-linux-prepare.ps1) */
    const val PROOT_NAME = "liblwproot.so"
    const val LOADER_NAME = "liblwprootloader.so"

    /**
     * `work` 的形状
     *
     * 它是唯一一个会落进 proot 命令行参数值里的外部字符串, 所以这里只放行"一个普通绝对路径"该有的
     * 样子: 冒号是 `-b` 的分隔符 (一个带冒号的路径能把参数切成两半), 控制字符与换行同理. 校验在
     * 应用那一侧也做一次, 但**这一侧才是那道门** —— 送过来的东西不因为它自称来自哪里就能过关
     *
     * @return 不合格时的那句话, 合格就是 null
     */
    internal fun workProblem(work: String): String? {
        workShapeProblem(work)?.let { return it }
        val directory = File(work)
        if (!directory.isDirectory) return "the workspace directory \"$work\" is not a directory"
        if (!directory.canRead()) return "the privileged uid cannot read \"$work\", so there is nothing to bind at /work"
        return null
    }

    /**
     * `work` 只看字符串的那一半
     *
     * 抽出来是为了它能在 JVM 上被测试直接摆弄: 这一半是**形状**那条边界 (冒号能把一个 `-b` 值切成
     * 两半, 换行与别的控制字符同理), 与文件系统无关, 而它正是"参数进不了别的位置"这件事的守门人
     */
    internal fun workShapeProblem(work: String): String? {
        if (work.isBlank()) return "op=run needs the session workspace directory to bind at /work"
        if (!work.startsWith("/")) return "the workspace directory has to be an absolute path, not \"$work\""
        if (work.any { it == ':' || it == '\n' || it == '\r' || it.code < 0x20 }) {
            return "the workspace directory \"$work\" has a character that cannot survive being a -b value"
        }
        return null
    }

    /** 一份 base 的路径像不像话: 是文件、够大、开头是 gzip */
    private fun sourceProblem(archive: File): String? {
        if (!archive.isAbsolute) return "the base archive path has to be absolute, not \"${archive.path}\""
        if (!archive.isFile) return "there is no base archive at \"${archive.path}\""
        if (archive.length() < 1L shl 20) return "${archive.name} is only ${archive.length()} bytes, which is not a root filesystem"
        return try {
            val magic = archive.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
            if (magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                null
            } else {
                "${archive.name} is not a gzip stream, so it is not the base this app ships"
            }
        } catch (error: Throwable) {
            "could not read ${archive.name}: ${error.message}"
        }
    }

    /**
     * 首装要写的那两份网络配置
     *
     * minirootfs 里没有 resolv.conf (安卓的 /system/etc 里也没有可绑的), 而上游的 apk 仓库指向
     * dl-cdn —— 从这里够不着或很慢, 所以两者都按 P0 的实测改写成随包一份: 写不上就是一个"装好却
     * 装不了包"的 rootfs, 与其让它带着假象跑, 不如不写戳
     *
     * @return 写成功了是 null, 不然是回给调用方的那句话
     */
    private fun writeNetworkConfig(rootfs: File): String? {
        val files = listOf(
            "etc/resolv.conf" to buildString {
                append("# written by Luwi's Linux sandbox at install time\n")
                append("nameserver 223.5.5.5\n")
                append("nameserver 8.8.8.8\n")
            },
            "etc/apk/repositories" to buildString {
                append("# written by Luwi's Linux sandbox at install time\n")
                append("# the branch matches the bundled base (see LwLinux.BASE_VERSION)\n")
                append("$ALPINE_MIRROR/$ALPINE_BRANCH/main\n")
                append("$ALPINE_MIRROR/$ALPINE_BRANCH/community\n")
            },
        )
        files.forEach { (relative, content) ->
            try {
                File(rootfs, relative).writeText(content)
            } catch (error: Throwable) {
                Log.w(TAG, "could not write $relative", error)
                return "the rootfs unpacked but $relative could not be written (${error.message})," +
                    " and without it `apk add` inside the sandbox cannot work: install it again"
            }
        }
        return null
    }

    /** 随包的 proot 那一套在不在 lib 目录里 */
    private fun binariesProblem(libraryDir: String): String? {
        val missing = listOf(PROOT_NAME, LOADER_NAME, "liblwtalloc.so", "liblwshmem.so")
            .filter { !File(libraryDir, it).isFile }
        if (missing.isEmpty()) return null
        return "the app's native library directory is missing $missing, which means this build cannot run the sandbox"
    }

    /**
     * 特权这一侧看得见的那个 nativeLibraryDir
     *
     * 服务的 Context 是 app_process 里拼出来的 (见 [LwContext]), applicationInfo 是从包管理器问来
     * 的, 所以这一条拿到的是这台设备上真实的安装路径, 不靠应用那一侧送过来 —— 送过来也没有用,
     * 这一侧不认外部给的路径
     */
    private fun libraryDirectory(context: Context?): String? {
        val dir = try {
            context?.applicationInfo?.nativeLibraryDir
        } catch (error: Throwable) {
            Log.w(TAG, "could not read the app's application info", error)
            null
        } ?: return null
        return dir.takeIf { File(it).isDirectory }
    }

    /** `df -k` 说 /data 还有多少可用空间, 问不到就是 null */
    private fun freeKilobytes(): Long? {
        val result = execute(
            command = listOf("df", "-k", "/data/local/tmp"),
            stdin = null,
            timeoutMs = 10_000L,
        )
        if (result.code != 0) return null
        return result.output.lineSequence()
            .drop(1)
            .mapNotNull { line -> line.split(Regex("\\s+")).getOrNull(3)?.toLongOrNull() }
            .firstOrNull()
    }

    /**
     * 一个目录占多少 KiB, `du -sk` 的那一列
     *
     * **不看退出码**: du 碰到读不动的子目录 (rootfs 里出现过 000 的目录) 会以非零退出, 但仍把
     * 数得出来的那部分打出来 —— 那半张账比"没有账"有用, 所以只在第一列解析不出数字时才放弃
     */
    private fun directoryKilobytes(directory: File): Long? {
        val result = execute(
            command = listOf("du", "-sk", directory.absolutePath),
            stdin = null,
            timeoutMs = 30_000L,
        )
        return sizeKilobytesOf(result.output)
    }

    /**
     * `du -sk` 那张账里的数字
     *
     * 取**第一条第一列能解析成数字的行**, 而不是整个输出的开头: 沙盒在跑的时候 rootfs 里有
     * proot 刚摆下的 bind 落点 (`dev/pts` 与 `work`, 权限 000), du 会先把它们的 Permission
     * denied 写进合并流, 计数行落在后面 —— 2026-10-12 真机实测的正是这一条 (空闲时流里只有计数
     * 那一行, 一跑起来就变成两条错误行在前). 只看第一条能解析出数字的行, 退出码与错误行都不参与
     */
    internal fun sizeKilobytesOf(output: String): Long? = output.lineSequence()
        .mapNotNull { line -> line.substringBefore('\t').substringBefore(' ').toLongOrNull() }
        .firstOrNull()

    /** `rm -rf` 一棵树, 必要时先把自己那份权限修回来 (沙盒里 000 的目录出现过) */
    private fun deleteTree(target: File): Boolean {
        var result = execute(
            command = listOf("rm", "-rf", "--", target.absolutePath),
            stdin = null,
            timeoutMs = 120_000L,
        )
        if (result.code != 0 && target.exists()) {
            execute(
                command = listOf("chmod", "-R", "700", "--", target.absolutePath),
                stdin = null,
                timeoutMs = 60_000L,
            )
            result = execute(
                command = listOf("rm", "-rf", "--", target.absolutePath),
                stdin = null,
                timeoutMs = 120_000L,
            )
        }
        return !target.exists()
    }

    /**
     * 跑一条宿主命令, 把输出收回来
     *
     * 与 [LwSystemCommandTable] 那条"读完再等"不同, 这里**先起一个读线程再等** —— 沙盒里的命令
     * 可能是 `sleep 100`, 那样"读"会永远等不到 EOF, 而超时是这里必须能兑现的承诺. 读完的输出按
     * [OUTPUT_LINES] 行收尾, 中间被丢掉的字节在回执里说一句
     *
     * 超时的处置是三层: SIGTERM 给 [KILL_GRACE_MS], 还不走就 SIGKILL 这条命令, 最后按 **进程组**
     * 再收一遍 —— `setsid` 那层就是为最后这一步准备的 (实测: 只 SIGKILL proot 会留下 guest 的孤儿进程)
     */
    private fun execute(
        command: List<String>,
        stdin: File?,
        timeoutMs: Long,
    ): Outcome {
        // 记账目录自己保证在 (install 那一条会在建目录之前就问 df); 它在 base 外面 —— reset 删的
        // 就是 base, 住在里面的 pid 文件会被自己这一趟抹掉, 见 [RUN_DIRECTORY]
        File(RUN_DIRECTORY).mkdirs()
        // 进程组号等于组长 pid, 而组长是 `setsid` —— 它 exec 之后就是命令自己, 所以那个 pid 由
        // 一层 `sh -c 'echo $$ ...'` 写在文件里带回来. **不用 `Process.pid()`**: 那条 API 不在
        // 安卓的 `java.lang.Process` 上 (编译期就找不到), 而超时那一步要按组收人, 少了它就只剩
        // "杀掉 proot 自己", 实测会把 guest 里的子进程留成孤儿
        val pidFile = File(RUN_DIRECTORY, "run-${System.nanoTime()}.pid")
        pidFile.delete()
        val process = try {
            ProcessBuilder(
                listOf("sh", "-c", "echo \$\$ > \"\$0\" && exec \"\$@\"", pidFile.absolutePath) + command,
            )
                .redirectErrorStream(true)
                .apply { if (stdin != null) redirectInput(stdin) }
                .start()
        } catch (error: Throwable) {
            return Outcome(127, "could not start ${command.first()}: ${error.message}")
        }
        val leader = waitForLeader(pidFile)
        val collector = OutputCollector(process.inputStream)
        val reader = Thread({ collector.read() }, "lw-linux-output").apply {
            isDaemon = true
            start()
        }
        try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // 进程结束了, 但它的管道可能还有一点尾巴, 给读线程一个短窗口让最后几行也进缓冲区
                reader.join(300L)
                return Outcome(process.exitValue(), collector.tail())
            }
            process.destroy()
            if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            killProcessGroup(leader)
            reader.join(300L)
            return Outcome(124, collector.tail())
        } finally {
            pidFile.delete()
        }
    }

    /** 等那一层 sh 把组长 pid 写下来, 最多等一小会儿 (写不出来就是命令根本没起来) */
    private fun waitForLeader(pidFile: File): Long? {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val leader = runCatching { pidFile.readText().trim().toLongOrNull() }.getOrNull()
            if (leader != null && leader > 1) return leader
            Thread.sleep(20L)
        }
        return null
    }

    /** 整个进程组一起收. `setsid` 是组长, 所以组号就是它 (也就是这条命令) 的 pid */
    private fun killProcessGroup(pid: Long?) {
        if (pid == null || pid <= 1) return
        runCatching {
            val killer = ProcessBuilder(listOf("kill", "-9", "--", "-$pid"))
                .redirectErrorStream(true)
                .start()
            killer.waitFor(5, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "could not reap the process group $pid", it) }
    }

    /**
     * 装没装: 戳在 + rootfs 像个 rootfs
     *
     * 判据用 `bin/busybox` 而不是更顺眼的 `bin/sh`: Alpine 的 `/bin/sh` 是指向绝对路径
     * `/bin/busybox` 的符号链接, 而宿主上的 `File.exists()` 会拿**宿主**的 `/` 去解析它 —— 落点
     * 跑到宿主自己的 /bin 去了, 明明装好了也判 false (2026-10-12 真机首装后实测踩到的这一条).
     * busybox 是真文件, 不受这件事影响
     */
    private fun isInstalled(): Boolean {
        val rootfs = File(ROOTFS_DIRECTORY)
        return File(STAMP_FILE).isFile && rootfs.isDirectory && File(rootfs, "bin/busybox").isFile
    }

    /**
     * 读到 EOF 为止的一块输出, 只留尾巴
     *
     * 上限按字节算而不是行数: 一行可以是几 MB 的构建日志. 丢掉的字节数记下来, 回执里说"前面还有多少"
     */
    private class OutputCollector(private val stream: java.io.InputStream) {

        private val buffer = StringBuilder()
        private var droppedChars = 0

        fun read() {
            val chunk = ByteArray(16 * 1024)
            try {
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    synchronized(buffer) {
                        buffer.append(String(chunk, 0, read, Charsets.UTF_8))
                        if (buffer.length > OUTPUT_BUFFER_BYTES) {
                            droppedChars += buffer.length - OUTPUT_BUFFER_BYTES
                            buffer.delete(0, buffer.length - OUTPUT_BUFFER_BYTES)
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "reading the sandbox output failed", error)
            }
        }

        fun tail(): String {
            val text = synchronized(buffer) { buffer.toString() }
            val lines = text.trim().lines()
            val kept = lines.takeLast(OUTPUT_LINES)
            val trimmed = lines.size > kept.size || droppedChars > 0
            if (!trimmed) return kept.joinToString("\n")
            val why = buildList {
                if (droppedChars > 0) add("$droppedChars earlier character(s) dropped")
                if (lines.size > kept.size) add("showing the last ${kept.size} line(s)")
            }
            return "(output trimmed: ${why.joinToString(", ")})\n" + kept.joinToString("\n")
        }
    }
}
