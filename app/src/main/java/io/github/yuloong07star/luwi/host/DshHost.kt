package io.github.yuloong07star.luwi.host

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuloong07star.luwi.channel.PrivilegedBridge
import io.github.yuloong07star.luwi.workspace.Workspace
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Supervises the one dsh host process
 *
 * Node and the packed host tree both ship inside the app, so a start has two phases: unpack the
 * tree the APK carries if it is not there yet, then spawn the process with an explicit
 * environment - `DSH_HOME` inside the app sandbox, the shared libraries that live beside
 * `libnode.so` on `LD_LIBRARY_PATH`, a writable temp directory, and the `HOME` that dsh falls
 * back to, because an app process inherits none of that from a shell
 *
 * State sits in Compose snapshot state rather than a flow: the whole app is one process and
 * one screen, and this way the status screen observes the process directly with no plumbing
 */
object DshHost {
    /** Loopback port the GUI binds */
    const val PORT = 3080

    /**
     * dsh's own state directory inside the app sandbox, handed to it as `DSH_HOME`
     *
     * Anything the app and the host have to agree on a path for hangs off this one name, so it is
     * spelled out here rather than in each of them (the voice inbox is the first such file)
     */
    const val HOME_DIR = "dsh-home"

    /**
     * 这台 host 自己那些小配置文件的目录 (`$DSH_HOME/lw`)
     *
     * **它存在是为了"应用这一半与插件那一半读同一份账"**: 插件跑在 host 的进程里, 读不到应用
     * 的偏好 (那是另一个进程的 `SharedPreferences`), 而桥那一侧也没有一条"给我读个偏好"的调用。
     * 于是凡是**两半都要知道**的开关就落在这里的一个文件上 (视频模式那个"要不要拼网格"是第一条;
     * 视频模式那个常驻语音记号在 `modes/` 下, 是同一个道理的先例)
     *
     * 目录不存在时 `mkdirs` 一次 —— 它是 app 自己那个沙盒里的路径, 写不进去只说明盘满了
     */
    fun settingsDirectory(context: Context): File =
        File(File(context.filesDir, HOME_DIR), "lw").apply { mkdirs() }

    /** Host output lines kept for the status screen */
    private const val LOG_LINES = 300

    /** The readiness line dsh prints once the tree settled, LAN suffix and all */
    private val READY = Regex("""dsh web: (http://\S+)""")

    /** The LAN suffix dsh adds to that line only when it bound every interface */
    private val LAN_READY = Regex("""\(LAN: (http://\S+?)\)""")

    /** dsh's all-interfaces bind literal, which it refuses without the matching flag */
    private const val ALL_INTERFACES = "0.0.0.0"

    /** Current process state, written by the output reader and the exit hook */
    var status: HostStatus by mutableStateOf(HostStatus.Idle)
        private set

    /** Where sessions keep their files, resolved on every start */
    var workspace: Workspace.Root? by mutableStateOf(null)
        private set

    /** Where another device can open the GUI, reported by the host itself, null on a loopback bind */
    var remoteUrl: String? by mutableStateOf(null)
        private set

    /** Tail of the host's merged output */
    val log = mutableStateListOf<String>()

    private var process: Process? = null

    /** A start is in flight, anywhere between the unpack and the spawn */
    private var starting = false

    /** Set by stop() so a start that is still unpacking does not spawn afterwards */
    private var cancelled = false

    private val lock = Any()

    /** Root of the packed host tree inside the app sandbox */
    fun hostRoot(context: Context): File = File(context.filesDir, "host")

    /** Prepare the tree and spawn the host, unless one is already alive or on its way */
    fun start(context: Context) {
        synchronized(lock) {
            if (process?.isAlive == true || starting) return
            starting = true
            cancelled = false
        }
        val application = context.applicationContext
        Thread({ begin(application) }, "dsh-host-start").apply { isDaemon = true }.start()
    }

    /**
     * 那个监听口现在有没有人在听
     *
     * **为什么需要这一步** (2026-10-06 真机上抓到的): app 只知道**自己这个进程**spawn 过的那个
     * `Process` 对象, 而 host 是子进程 —— app 被强停/被杀之后子进程可能活着变成孤儿 (Android 不会
     * 顺手杀它), 端口还在它手里。那时新起的 host 一 bind 就死:
     *
     * ```
     * dsh: startup failed: 2 required plugins did not activate
     *     Error: listen EADDRINUSE: address already in use 127.0.0.1:3080
     * ```
     *
     * 表现是"球能点、界面打得开, 但**没有一个工具在**、语音发出去也没有回音" —— 因为插件一个都没挂上。
     * 所以起之前先探一次: 有人在听就说明**已经有一个 host 活着** (它只可能是我们自己上一次留下的),
     * 那就别起第二个 —— 让端口留给它, 而它的插件本来就是好的
     */
    private fun portIsTaken(): Boolean = try {
        ServerSocket().use { probe ->
            probe.reuseAddress = false
            probe.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 1)
        }
        false
    } catch (problem: IOException) {
        Log.i(TAG, "port $PORT is already taken, so a host is up: ${problem.message}")
        true
    }

    /** Stop the host and wait briefly for the process to go away */
    fun stop() {
        val running = detach() ?: return
        status = HostStatus.Idle
        running.destroy()
        if (running.isAlive) running.destroyForcibly()
    }

    /** Restart the host, which is how a changed workspace takes effect */
    fun restart(context: Context) {
        val application = context.applicationContext
        Thread({
            stopAndWait()
            start(application)
        }, "dsh-host-restart").apply { isDaemon = true }.start()
    }

    /**
     * Stop the host and wait for the process to actually exit
     *
     * A restart needs the listen port back before it spawns a successor, and a process that was
     * only asked to die still holds it
     */
    private fun stopAndWait() {
        val running = detach() ?: return
        status = HostStatus.Idle
        running.destroy()
        val exited = try {
            running.waitFor(EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited) running.destroyForcibly()
    }

    /** Take the process out of the supervisor's hands and stop any start that is in flight */
    private fun detach(): Process? = synchronized(lock) {
        cancelled = true
        val current = process
        process = null
        current
    }

    /** Unpack the shipped tree if it is missing or older, then spawn the host */
    private fun begin(application: Context) {
        log.clear()
        remoteUrl = null
        val problem = HostInstaller.ensure(application) { done, total ->
            status = HostStatus.Installing(done, total)
        }
        val abandoned = synchronized(lock) {
            starting = false
            cancelled
        }
        if (abandoned) return
        if (problem != null) return fail(problem)
        val root = Workspace.resolve(application)
        workspace = root
        log.add("workspace ${root.directory.absolutePath} (${root.kind})")
        // **已经有人在听那个口就不要再起一个** (见 [portIsTaken]): 端口属于一个活着的 host —— 最可能
        // 是我们上一次留下的孤儿, 而它的插件是好的; 硬起第二个只会得到 EADDRINUSE, 然后"工具一个都
        // 没有、语音发出去没有回音" (2026-10-06 真机上就是这么断的)
        if (portIsTaken()) {
            status = HostStatus.Running("http://127.0.0.1:$PORT/")
            log.add("port $PORT already answers, so the host that owns it is kept")
            return
        }
        spawn(application, root)
    }

    /** Start the host process against the tree and workspace that are now in place */
    private fun spawn(application: Context, root: Workspace.Root) {
        val libraryDir = application.applicationInfo.nativeLibraryDir
        // The runtime is shipped as a native library so the installer extracts it into a
        // directory SELinux lets the app execute from, which the app's data directory is not
        val node = File(libraryDir, "libnode.so")
        // Android ships mksh and no bash, and the agent writes bash, so a real bash travels
        // with the app under the same native-library rule; ripgrep is the same story
        val bash = File(libraryDir, "liblwbash.so")
        val ripgrep = File(libraryDir, "liblwrg.so")
        val entry = File(hostRoot(application), "node_modules/@deepseek-ai/dsh/lib/bin.js")
        val home = File(application.filesDir, HOME_DIR)
        val temp = File(application.cacheDir, "tmp")
        home.mkdirs()
        temp.mkdirs()
        // The runtime came from an environment whose libssl was built with OPENSSLDIR
        // pointing at a directory an app uid cannot read, and Node aborts during
        // bootstrap with exit 13 and no output at all until OPENSSL_CONF names a config
        // file it can open, so an empty readable file is materialised here
        val openssl = File(application.filesDir, "openssl.cnf")
        if (!openssl.exists()) openssl.writeText("")
        if (!node.isFile) return fail("node runtime is missing at ${node.absolutePath}")
        if (!entry.isFile) return fail("host tree is missing at ${entry.absolutePath}")
        val shellEnv = shellEnvFile(application, libraryDir)
        // Reaching the GUI from another device means binding every interface, which dsh gates
        // behind its own flag because the URL token is then the only thing guarding it
        val lanAccess = HostSettings.lanAccess(application)
        // The host's tools cannot hold a binder, so the privileged channel is reached over a
        // socket this process serves; starting it here keeps "the host knows where it is" and
        // "something is listening there" from depending on which of the two started first
        val channel = PrivilegedBridge.start()
        // The overlay is what adds this app's tools to the profile the host boots
        // 三份预设声明与内置的 dsh-custom-mode 也要在 spawn 之前落进 profile: 那一步做完, 第一次启动的
        // host 就把「自定义模式 / 手机模式 / 视频模式」一起读进来, 不需要重启一次才生效
        //
        // **两处都叫一遍是刻意的**: 首启那次 `DshHostService.onCreate` 跑在 host 树解压之前 (它那一步
        // 要几分钟), 所以那时树里还没有那个包; 这里排在解压之后, 于是"第一次启动就补齐"这件事
        // 有一个真的会成的落点。两个函数都是幂等的
        CustomPresets.ensure(application)
        CustomPresets.ensureProfile(application)
        // 能被 dsh 设置面板改的那几行 (现在只有生图) 走 profile 自己的 patch 而不是下面那份覆盖层
        // —— 覆盖层是最上面那一层, 面板的写入命中不了它, 见 PluginRows 的文件头
        PluginRows.ensure(application)
        val overlay = PluginOverlay.write(application)
        log.add(if (overlay != null) "tools overlay ${overlay.absolutePath}" else "tools overlay is missing from the host tree")

        status = HostStatus.Starting
        val spawned = try {
            ProcessBuilder(
                node.absolutePath,
                // Live patch reload reads Node's own module loader, and the addon that
                // normally exposes it (node-addon-require-builtin) has no Android build
                "--expose-internals",
                entry.absolutePath,
                "web",
                // The overlay has to be named before any of the web app's own flags: the web
                // subcommand hands every unknown option, and everything after it, to the app,
                // which then rejects --patch as an unknown option of its own
                *(if (overlay != null) arrayOf("--patch", overlay.absolutePath) else emptyArray()),
                "--no-open",
                "--port",
                PORT.toString(),
                *(if (lanAccess) arrayOf("--host", ALL_INTERFACES, "--allow-lan") else emptyArray()),
            ).apply {
                // A session created without an explicit directory lands in the workspace, and
                // relative paths the host resolves for itself land there too
                directory(root.directory)
                redirectErrorStream(true)
                environment().apply {
                    put("DSH_HOME", home.absolutePath)
                    put("LD_LIBRARY_PATH", libraryDir)
                    // The GUI's directory picker starts at the host's home, so pointing home at
                    // the workspace opens its "choose a workspace" screen where the files are;
                    // dsh's own state stays in DSH_HOME, which outranks home
                    put("HOME", root.directory.absolutePath)
                    put("TMPDIR", temp.absolutePath)
                    put("DSH_TELEMETRY_DISABLED", "1")
                    put("NO_COLOR", "1")
                    put("OPENSSL_CONF", openssl.absolutePath)
                    // dsh resolves the shell itself: DSH_BASH names the bash that travels with
                    // the app, and SHELL is what any other child sees as the user's shell
                    put("DSH_BASH", bash.absolutePath)
                    put("SHELL", if (bash.isFile) bash.absolutePath else "/system/bin/sh")
                    if (ripgrep.isFile) put("DSH_RG_PATH", ripgrep.absolutePath)
                    // A shipped binary cannot also carry the bare name PATH needs, so the
                    // shell-level names come from a startup file bash sources for every
                    // non-interactive command
                    if (shellEnv != null) put("BASH_ENV", shellEnv.absolutePath)
                    // Where the tools find the app's privileged channel, and the token that says
                    // the request came from this host; both unset when the channel is down, and
                    // the tools then report it as unavailable
                    if (channel != null) {
                        put("LW_CHANNEL_ENDPOINT", channel.address)
                        put("LW_CHANNEL_TOKEN", channel.token)
                    }
                    // V8 keeps compiled bytecode in this cache, which is what makes the
                    // second boot of a 300k file tree much faster than the first
                    put("NODE_COMPILE_CACHE", File(application.cacheDir, "node-compile-cache").absolutePath)
                }
            }.start()
        } catch (error: Exception) {
            return fail("could not spawn ${node.name}: ${error.message}")
        }
        val keep = synchronized(lock) {
            if (cancelled) false else { process = spawned; true }
        }
        if (!keep) {
            spawned.destroyForcibly()
            return
        }
        Thread({ watch(spawned) }, "dsh-host-watch").apply { isDaemon = true }.start()
        Thread({ read(spawned) }, "dsh-host-output").apply { isDaemon = true }.start()
    }

    /** Report the exit once the process is gone, unless stop() asked for it */
    private fun watch(source: Process) {
        val code = source.waitFor()
        // stop() clears the field before destroying, so a cleared field means the exit was asked for
        val stopped = synchronized(lock) {
            if (process !== source) true else { process = null; false }
        }
        if (stopped) return
        status = if (status is HostStatus.Running) {
            HostStatus.Failed("host exited with code $code")
        } else {
            HostStatus.Failed("host exited with code $code before reporting its URL")
        }
    }

    /** Read merged host output until the process closes its side */
    private fun read(source: Process) {
        try {
            source.inputStream.bufferedReader().useLines { lines -> lines.forEach(::append) }
        } catch (error: Exception) {
            append("output reader stopped: ${error.message}")
        }
    }

    /**
     * Write the bash startup file that gives shipped binaries their shell names
     *
     * A binary inside the APK has to be named `lib*.so` to survive packaging, so it cannot
     * also be the bare name PATH resolution needs. bash sources the file named by BASH_ENV
     * before every non-interactive command, which is where those names can live instead.
     * The functions call the binary rather than `exec` it, so `node -e x && echo ok` keeps
     * its `echo`
     * @param application context that owns the file.
     * @param libraryDir the directory the shipped binaries live in.
     * @return the written startup file, or null when the APK shipped none of them.
     */
    private fun shellEnvFile(application: Context, libraryDir: String): File? {
        val names = listOf("liblwbash.so" to "bash", "liblwrg.so" to "rg", "libnode.so" to "node")
            .filter { (file, _) -> File(libraryDir, file).isFile }
        if (names.isEmpty()) return null
        val file = File(application.filesDir, "shell-env.sh")
        file.writeText(
            names.joinToString("\n") { (file, name) ->
                """$name() { "$libraryDir/$file" "${'$'}@"; }"""
            } + "\n",
        )
        return file
    }

    /** Keep one output line, publish the readiness line as the GUI URL */
    private fun append(line: String) {
        // The status screen only shows while the host is down, so the host's own output also
        // goes to logcat where it stays readable through the running phase
        Log.i(TAG, line)
        log.add(line)
        while (log.size > LOG_LINES) log.removeAt(0)
        val match = READY.find(line) ?: return
        if (status !is HostStatus.Running) status = HostStatus.Running(match.groupValues[1])
        // The host picks the address itself, so the URL shown to the user is the same one the
        // host's own browser-trust fence accepts
        LAN_READY.find(line)?.let { remoteUrl = it.groupValues[1] }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        status = HostStatus.Failed(reason)
    }

    /** Logcat tag for the host's merged output */
    private const val TAG = "DshHost"

    /** How long a restart waits for the departing host to release its port */
    private const val EXIT_TIMEOUT_MS = 5_000L
}
