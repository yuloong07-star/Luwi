package io.github.yuloong07star.luwi.plugin

import android.content.Context
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 插件落在哪、状态存在哪 (协议第 7 节)
 *
 * `$DSH_HOME/plugins/` 底下这一套与快捷指令、自动指令同一个理由落在 `$DSH_HOME` 而不是工作区:
 * **应用与宿主都要读它**, 而工作区是用户在界面里选的, 会话自己那一份 fs 又被 dsh 沙箱关在工作区里
 *
 * ```
 * plugins/
 *   <id>/<version>/plugin.json …   一个版本一份, 升级装成新目录
 *   <id>/current                   内容是版本号 (文本, 不是 symlink —— 记号类的东西都这么写)
 *   .state.json                    启用状态 + 逐能力授权 + revision
 *   .publishers.json               见过的公钥 (第一次见到会给一句提示)
 *   .audit/<id>.log                能力调用与被拒的账
 *   .staging/…                     解包与校验时的暂存, 成功才搬进去
 * ```
 */
internal object PluginStore {

    const val DIRECTORY = "plugins"

    /** 单包上限 (协议第 3 节) */
    const val MAX_BYTES = 32L * 1024 * 1024
    const val MAX_FILES = 2000

    private const val CURRENT = "current"
    private const val STATE = ".state.json"
    private const val PUBLISHERS = ".publishers.json"
    private const val STAGING = ".staging"
    private const val AUDIT = ".audit"

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    /** 插件目录根, 不存在就建 */
    fun root(context: Context): File =
        File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY).apply { mkdirs() }

    /** 装过哪几个 id (按目录名, 点开头的都是我们自己的记账文件) */
    fun installedIds(context: Context): List<String> =
        root(context).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun versionDir(context: Context, id: String, version: String): File =
        File(File(root(context), id), version)

    /** `current` 指向的那个版本; 没有就是 null */
    fun currentVersion(context: Context, id: String): String? =
        File(File(root(context), id), CURRENT)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.ifBlank { null }

    fun setCurrent(context: Context, id: String, version: String) {
        val home = File(root(context), id).apply { mkdirs() }
        File(home, CURRENT).writeText("$version\n")
    }

    /** 现在生效的那一份包在哪 */
    fun packageDir(context: Context, id: String): File? =
        currentVersion(context, id)?.let { versionDir(context, id, it).takeIf { it.isDirectory } }

    /** 读一份装好的清单; 读不出来 (文件没了 / JSON 坏了) 就是 null, 调用方要如实说 */
    fun manifest(context: Context, id: String): PluginManifest? {
        val directory = packageDir(context, id) ?: return null
        val file = File(directory, "plugin.json")
        if (!file.isFile) return null
        return runCatching {
            PluginManifest.parse(file.readText(), PluginStore.lwVersion(context), directory)
        }.getOrNull()
    }

    /**
     * 一份装好的插件: 清单 + 它现在落在哪个目录 + 当时的版本
     *
     * [problem] 非空表示"装是装着, 但现在读不出来", 界面与工具都要把这一句念出来, 而不是当它不存在
     */
    data class Installed(val id: String, val manifest: PluginManifest?, val directory: File?, val problem: String?)

    fun installed(context: Context): List<Installed> = installedIds(context).map { id ->
        val manifest = manifest(context, id)
        Installed(
            id = id,
            manifest = manifest,
            directory = packageDir(context, id),
            problem = if (manifest == null) "这一份现在读不出来 (plugin.json 没了或者坏了)" else null,
        )
    }

    /** 暂存目录: 解包与校验都在这里做, 全部过了才搬进 `<id>/<version>` */
    fun staging(context: Context, tag: String): File =
        File(File(root(context), STAGING), tag).apply {
            deleteRecursively()
            mkdirs()
        }

    fun auditFile(context: Context, id: String): File =
        File(File(root(context), AUDIT), "$id.log")

    /** 状态: 启用哪几个 + 各自授权了哪几条能力 + revision (宿主按它决定要不要重注册) */
    @Serializable
    data class State(
        val revision: Long = 0,
        val enabled: Map<String, Boolean> = emptyMap(),
        val grants: Map<String, List<String>> = emptyMap(),
    )

    fun readState(context: Context): State {
        val file = File(root(context), STATE)
        if (!file.isFile) return State()
        return runCatching { json.decodeFromString<State>(file.readText()) }.getOrDefault(State())
    }

    /** 写回状态并把 revision 抬一格 —— 抬的那一格就是"宿主该重注册了"这个信号 */
    fun writeState(context: Context, state: State): State {
        val bumped = state.copy(revision = state.revision + 1)
        File(root(context), STATE).writeText(json.encodeToString(bumped) + "\n")
        return bumped
    }

    /**
     * 只抬 revision, 别的什么都不碰
     *
     * 装一个新版本要走它: 那份清单 (工具名、摘要、参数) 可能变了, 而宿主那一侧是按 revision 决定要不要
     * 重新登记的 —— 只换目录不抬它, 模型看到的还是上一版的工具说明 (2026-10-08 顺着"升级那条路"看出来
     * 的)。启用 / 停用 / 授权 / 卸载那几处走 [writeState], 那边自然就抬了
     */
    fun bumpRevision(context: Context) {
        val current = readState(context)
        writeState(context, current)
    }

    /** 见过的发布者: 公钥 → 第一次见到它的时间 */
    @Serializable
    data class Publisher(val name: String = "", val key: String = "", val firstSeen: Long = 0)

    fun publishers(context: Context): Map<String, Publisher> {
        val file = File(root(context), PUBLISHERS)
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Publisher>>(file.readText()) }.getOrDefault(emptyMap())
    }

    /** 记一个发布者, 回 true 表示**第一次见到这把公钥** (界面要单独提示一句) */
    fun rememberPublisher(context: Context, publisher: Publisher): Boolean {
        val known = publishers(context).toMutableMap()
        if (publisher.key in known) return false
        known[publisher.key] = publisher
        File(root(context), PUBLISHERS).writeText(json.encodeToString(known.toMap()) + "\n")
        return true
    }

    /** 忘掉一把公钥 (等于不再信任它之后发的包); 回 true 表示原来有 */
    fun forgetPublisher(context: Context, key: String): Boolean {
        val known = publishers(context).toMutableMap()
        if (known.remove(key) == null) return false
        File(root(context), PUBLISHERS).writeText(json.encodeToString(known.toMap()) + "\n")
        return true
    }

    /** app 自己的版本号, `minLw` 比的就是它 */
    fun lwVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("0.0.0")

    /** 卸掉之后留的那一条记录 (`plugins/.removed.json`), 谁都能读, 也谁都不改 */
    @Serializable
    data class Removed(val id: String, val version: String, val at: Long)

    fun removed(context: Context): List<Removed> {
        val file = File(root(context), ".removed.json")
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<List<Removed>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun removed(context: Context, id: String, version: String) {
        val all = removed(context) + Removed(id, version, System.currentTimeMillis())
        File(root(context), ".removed.json").writeText(json.encodeToString(all.takeLast(200)) + "\n")
    }

    /** 插件自己那一块可写的地方: `$DSH_HOME/plugins/<id>/.data/` */
    fun dataDir(context: Context, id: String): File =
        File(File(root(context), id), ".data").apply { mkdirs() }

    /**
     * 升级之后"用不上的那一半": `id → 伴侣包名` (方案第 6 节那条迁移)
     *
     * 一个 companion 插件升级成 `art` / `inline` 之后, 机器上那个伴侣 APK 就没用了 —— 但**不自动卸**:
     * 卸载应用是主人的事, 而且那个包里可能有主人自己导进去的东西 (鲸鱼娘那份就有)。这里只记一笔,
     * 界面拿它说一句"可以卸掉了"
     */
    private const val LEFTOVERS = ".leftovers.json"

    fun leftovers(context: Context): Map<String, String> {
        val file = File(root(context), LEFTOVERS)
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrDefault(emptyMap())
    }

    fun leftover(context: Context, id: String): String? = leftovers(context)[id]

    fun rememberLeftover(context: Context, id: String, packageName: String) {
        val known = leftovers(context).toMutableMap()
        known[id] = packageName
        File(root(context), LEFTOVERS).writeText(json.encodeToString(known.toMap()) + "\n")
    }
}
