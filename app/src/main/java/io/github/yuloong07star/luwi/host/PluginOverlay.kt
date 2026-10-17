package io.github.yuloong07star.luwi.host

import android.content.Context
import java.io.File

/**
 * The dsh patch overlay that loads Luwi's tools into the host's profile
 *
 * The tools ship inside the host tree the APK carries, but nothing in that tree mentions them:
 * a profile composes its plugins from the profile directory plus whatever patch overlays the
 * command line names, and this app is what composes that command line
 *
 * The rows that a user edits from the dsh settings panel do NOT live here: they are written into the
 * profile's own patch by [PluginRows]. This file is a **command-line overlay**, and the highest
 * patch layer there is - `applyEntryPatches` only lets a later patch target a row an earlier layer
 * inserted, so a config write landing in the profile patch can never reach a row this overlay
 * inserted, and `dsh-config-editor` answers it with "overridden by a home patch or command-line
 * overlay". What is left here are the rows whose own config the panel never writes
 *
 * 三份包由三条不同的路进树 (本应用自己那条通道插件由打包拷进来, `dsh-web-mobile` 是那棵树的普通
 * 依赖, 语音那三个同理), 而"装了不等于挂了" —— 所以行还是得写在这儿
 */
object PluginOverlay {

    /**
     * One row of the overlay: the id it gets, the file that carries it, and any config it needs
     *
     * @property id the row id the loader reports in diagnostics
     * @property entry path inside the host tree
     * @property config YAML lines written verbatim under `config:`, empty for rows that take none
     */
    private data class Row(val id: String, val entry: String, val config: List<String> = emptyList())

    /**
     * What this app adds, as the row id to the path that carries it, relative to the host tree root
     *
     * `dsh-web-mobile` points at the host half (`lib/index.js`); its browser half is a separate
     * `lib/client.js`, which the tree's `modules` row finds by scanning for packages that declare
     * `dsh.client` - so naming the host half here is what puts both halves in play
     *
     * 语音输入那三个包同理: 装了不等于挂了, 而**不挂就没有草稿框旁边那个麦克风按钮**。这里只挂
     * 这三个, 不挂官方那个 voice-input bundle —— bundle 会把 `defaultProvider` 设成它自带的本地
     * SenseVoice provider, 而那个 provider 靠 `sherpa-onnx-node` 的原生 addon, 安卓上没有
     * (npm 上也没有 `sherpa-onnx-android-arm64`)。本机那份转写走 app 进程, 由
     * `luwi-channel` 注册成 `lw-native`
     *
     * **`imagegen` 不在这张表里**: 它的渠道与密钥要在 dsh 的设置面板里改, 所以那一行归 [PluginRows]
     * (写 profile patch), 见文件头那一段
     */
    private val PLUGINS = listOf(
        Row("luwi-channel", "node_modules/luwi-channel/index.mjs"),
        Row("dsh-web-mobile", "node_modules/dsh-web-mobile/lib/index.js"),
        // G1 会话同步 (2026-10-13): 上游 dsh-session-sync 的 vendored 拷贝, Luwi 改名 luwi-dsh-sync
        // (host-plugins/luwi-dsh-sync, 2026-10-14)。手机侧 git 经 Linux 沙盒执行, 电脑侧同一份包
        // 走 ctx.subprocess —— 见 docs/DSH-LW-3.0.0-G1-施工单.md 与 docs/Luwi-dsh-sync-设计.md
        Row("luwi-dsh-sync", "node_modules/luwi-dsh-sync/index.mjs"),
        // 只含 CSS 覆盖的自有 client 包 (2.7.8 批次 4, D8 = b): 它没有配置要改, 所以挂在覆盖层上
        // (那两行要能被设置面板改的归 PluginRows)。宿主那一半是空的, 客户端那一半在 lib/client.js
        Row("luwi-ui-fix", "node_modules/luwi-ui-fix/index.mjs"),
        Row(
            "speech-to-text",
            "node_modules/@deepseek-ai/dsh-experimental-speech-to-text/lib/index.js",
            config = listOf("defaultProvider: lw-native", "language: auto"),
        ),
        Row("speech-to-text-api", "node_modules/@deepseek-ai/dsh-experimental-api-speech-to-text/lib/index.js"),
        Row("voice-input", "node_modules/@deepseek-ai/dsh-experimental-client-ui-voice-input/lib/index.js"),
    )

    /**
     * 官方那个 bundle 一旦被打开 (它的 row id 与上面这几个不一样), 再插一遍就是同一份包挂两次,
     * 而 `api-speech-to-text` 里的 `speechController` 不许重复注册 —— 现象是那一行带着
     * "service speechController has been registered" 变成异常, 而语音模型的下载正好走它
     *
     * 所以 bundle 开着的时候**只留自己这几个包, 再用"按 id 改配置"把 provider 指到本机那份**:
     * `speech-to-text` 这个 id 两边一样, 于是是一次配置覆盖而不是第二次挂载
     *
     * **这张表与 [PLUGINS] 是并列的两份, 加插件时两边都要加** (2026-10-08 真机上踩的): 只加进
     * [PLUGINS] 的那一份, 在这台开着官方语音 bundle 的手机上根本不会挂, 而模拟器上 bundle 关着,
     * 一点异常都看不见。`tools/check-image-edit.mjs` 会数这两张表里的行与 [PluginRows] 那份名单
     * (生图那一条现在归后者, 所以它不在下面这两张表里)
     */
    private val OWN_PLUGINS = listOf(
        Row("luwi-channel", "node_modules/luwi-channel/index.mjs"),
        Row("dsh-web-mobile", "node_modules/dsh-web-mobile/lib/index.js"),
        Row("luwi-dsh-sync", "node_modules/luwi-dsh-sync/index.mjs"),
        Row("luwi-ui-fix", "node_modules/luwi-ui-fix/index.mjs"),
    )

    /** 官方 bundle 的名字: 它在 profile 的 package.json 里出现就说明被打开了 */
    private const val VOICE_BUNDLE = "@deepseek-ai/dsh-experimental-voice-input-bundle"

    /**
     * 右栏那个"浏览器"格: 上游在 web profile 上**默认关掉它** (2.7.8 批次 4 量到的根因)
     *
     * `@deepseek-ai/dsh-web-app/cordis.patch.yml` 里那一行是
     * `disabled: !!js "ctx.get('profileContext')?.name !== 'desktop'"`, 而 Luwi 起的是 `web` profile,
     * 于是那一行**根本没进 Loader** —— 页面里连它的 client 模块都没有 (`__DSH_BOOT__.entries` 里
     * 没有 `sidebar-browser`), Start 面板上也不出「浏览器」那张卡。也就是说主人的第 5 条需求
     * 「右侧边栏的浏览器显示问题」在这一版上根本不是 CSS 的问题, **那一格压根挂不上**
     *
     * 打开的方式是按 id 同 name 覆盖 `disabled: false` (patch 层是 `bundle → profile → $DSH_HOME →
     * 命令行覆盖层`, 我们这一层最后, 所以压得住)。`name` 必须与那棵树里那条逐字一致, 否则
     * `applyEntryPatches` 会按 "name mismatch" 整条丢掉
     */
    private const val BROWSER_ROW = "ui-sidebar-browser"
    private const val BROWSER_NAME = "@deepseek-ai/dsh-client-ui-sidebar-browser"
    private const val BROWSER_ENTRY = "node_modules/@deepseek-ai/dsh-client-ui-sidebar-browser/lib/index.js"

    /** 官方 bundle 里那条 provider 行的 id, 也是我们要覆盖的那一条 */
    private const val VOICE_PROVIDER_ROW = "speech-to-text"

    /**
     * 会撞车的那一条 row id: 它才是判"官方那几行是不是已经挂了"的准信号
     *
     * 不能拿 `speech-to-text` 去 contains —— 它是 `@deepseek-ai/dsh-experimental-speech-to-text`
     * 的子串, 于是任何提到那个包名的 patch 都会让它误判成"bundle 开着"
     */
    private const val VOICE_API_ROW = "api-speech-to-text"

    /**
     * 覆盖行的 `name` 与 profile 自己那条 `locale` 覆盖行同一个形状 (带 id 与 name 的配置覆盖),
     * 那是本机上正在生效的写法, 照它办最稳
     */
    private const val VOICE_PROVIDER_NAME = "@deepseek-ai/dsh-experimental-speech-to-text"

    private val VOICE_PROVIDER_CONFIG = listOf("defaultProvider: lw-native", "language: auto")

    /**
     * 官方 bundle 自带那个本地 provider 的行 id, 以及那一行的包名
     *
     * **它在安卓上起不来**: 那个 provider 靠 `sherpa-onnx-node` 的原生 addon, 而 npm 上没有
     * android-arm64 那一份 —— 现象是插件页上多一张红卡 (`Local speech is unavailable for
     * android-arm64`), 按「重试准备」也永远不会好, 而本机那份转写是 `luwi-channel` 注册的
     * `lw-native`。所以官方 bundle 开着时把这一行**按 id 关掉**: 客户端与 api 那两行照旧, 只是不再
     * 留一个注定失败的 provider (2026-10-09 真机上踩的: 主人打开官方 bundle 之后, 界面上并排两张
     * 卡, 一张已就绪、一张永远失败)
     */
    private const val VOICE_LOCAL_ROW = "speech-to-text-sensevoice"

    /** 上面那一行的包名: patch 的 `name` 校验用它, 认错了就只警告不关 (宁可不关, 也别关错一条) */
    private const val VOICE_LOCAL_NAME = "@deepseek-ai/dsh-experimental-speech-to-text-sensevoice"

    private const val DIRECTORY = "lw"
    private const val FILE = "tool-plugin.yml"

    /** profile 目录在哪: 主机是以 `web` 这个 profile 起来的 */
    private fun profileDirectory(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), "profiles"), "web")

    /**
     * 官方那个 bundle 现在开着吗
     *
     * 看两个地方: profile 的 `package.json` 里那份 bundle 名单 (插件列表上那个开关写的就是它),
     * 以及 profile 自己的 patch (有人手工按 row id 挂过也算)
     */
    private fun voiceBundleEnabled(context: Context): Boolean {
        val profile = profileDirectory(context)
        val declared = File(profile, "package.json").takeIf { it.isFile }?.readText().orEmpty()
        val patched = File(profile, "cordis.patch.yml").takeIf { it.isFile }?.readText().orEmpty()
        return declared.contains(VOICE_BUNDLE) || patched.contains(VOICE_API_ROW)
    }

    /**
     * Write the overlay the host should boot with
     *
     * Rewritten on every start so a stale file cannot outlive the tree it points into
     * @param context context whose sandbox holds both the tree and the overlay.
     * @returns the overlay to pass on the command line, or null when the tree carries none of them.
     */
    fun write(context: Context): File? {
        val root = DshHost.hostRoot(context)
        val bundle = voiceBundleEnabled(context)
        val rows = if (bundle) OWN_PLUGINS else PLUGINS
        val present = rows.mapNotNull { row ->
            File(root, row.entry).takeIf { it.isFile }?.let { row to it.absolutePath }
        }
        if (present.isEmpty()) return null
        val browserPresent = File(root, BROWSER_ENTRY).isFile
        val file = File(File(context.filesDir, DIRECTORY).apply { mkdirs() }, FILE)
        file.writeText(
            buildString {
                appendLine("# Written by Luwi on every host start, edits are overwritten")
                if (browserPresent) {
                    // 见 [BROWSER_ROW]: 上游只在 desktop profile 上挂这一格, 而 Luwi 起的是 web
                    appendLine("# The right sidebar's Browser tab is desktop-only upstream; Luwi wants it")
                    appendLine("- id: $BROWSER_ROW")
                    appendLine("  name: '$BROWSER_NAME'")
                    appendLine("  disabled: false")
                }
                if (bundle) {
                    // 官方 bundle 把 provider 行挂在它自己那份 patch 里, 而 patch 层是**按 id 覆盖**
                    // 的, 所以这里给一条同 id 的配置行就够, 不需要 (也不能) 再 insert 一遍
                    appendLine("# The official voice-input bundle is on, so its rows already exist:")
                    appendLine("# this only points that provider at this app's own engine")
                    appendLine("- id: $VOICE_PROVIDER_ROW")
                    // **引号是必须的**: `@` 开头在 YAML 里是保留字符, 裸着写会
                    // "bad indentation of a mapping entry", 而那是整份 overlay 解析失败 —— 主机
                    // 直接起不来 (2026-10-05 在真机上就是这么把 host 弄挂的)
                    appendLine("  name: '$VOICE_PROVIDER_NAME'")
                    appendLine("  config:")
                    VOICE_PROVIDER_CONFIG.forEach { appendLine("    $it") }
                    // 再把 bundle 自带那一行关掉: 它在本机起不来, 而它的失败会一直挂在界面那一页上
                    // 当成"这张卡是不是坏了"的一处误导 (见 [VOICE_LOCAL_ROW])
                    appendLine("- id: $VOICE_LOCAL_ROW")
                    appendLine("  name: '$VOICE_LOCAL_NAME'")
                    appendLine("  disabled: true")
                }
                appendLine("- insert:")
                present.forEach { (row, path) ->
                    appendLine("    - id: ${row.id}")
                    appendLine("      name: '$path'")
                    if (!bundle && row.config.isNotEmpty()) {
                        appendLine("      config:")
                        row.config.forEach { appendLine("        $it") }
                    }
                }
            },
        )
        return file
    }
}
