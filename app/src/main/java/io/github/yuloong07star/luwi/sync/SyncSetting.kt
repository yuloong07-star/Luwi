package io.github.yuloong07star.luwi.sync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * `$DSH_HOME/lw/session-sync.json` 的读写 (设置页「Luwi-dsh-sync」那一段)
 *
 * **这一份文件是手机侧唯一的配置面**: 插件在宿主里读它 (`lib/luwi-bridge-runner.mjs` 的
 * readLuwiSyncConfig), 三层合并的顺序是「显式 cordis 键 > 这份 JSON > 插件的 DEFAULTS」,
 * 而手机侧的 overlay 行不带 config, 所以写在这里的每一个键都会生效 —— 改完要重启 host
 *
 * 三条纪律:
 *   · **保留未知键**: 读回来的整棵树照抄, 只动自己要改的那一个键 (嵌套段也一样)。插件以后加的
 *     字段、人手工写进去的字段, 都不会被这一段吃掉
 *   · **令牌只在文件里**: 界面只显示 [maskedToken] 的产物, 这一段也不往日志里写它
 *   · **写盘前校验**: 越界的值在这里就被拒 (返回一句人话), 而不是等宿主加载期把插件停用
 *
 * 纯文件那一半 ([snapshotOf] / [setTextIn] / [setFlagIn] / [setPullIntervalIn] / [prettyOf])
 * 只吃一个 [File] —— 于是它能在一个临时目录上单测 (见 SyncSettingTest); Context 那一半只是
 * 把路径与界面的重画记号接上
 */
internal object SyncSetting {

    /** 文件名 (与插件、施工单里的那一份是同一个) */
    const val FILE = "session-sync.json"

    // --- 文件里的键 (写盘用的就是它们) ----------------------------------------

    const val SESSION_REMOTE_KEY = "remote"
    const val BRANCH_KEY = "branch"
    const val DEVICE_NAME_KEY = "deviceName"
    const val PUBLIC_URL_KEY = "publicUrl"
    const val AUTO_PULL_KEY = "autoPullOnStart"
    const val AUTO_PUSH_KEY = "autoPushOnTurnEnd"
    const val PULL_INTERVAL_KEY = "pullIntervalMinutes"
    const val WORKSPACE_KEY = "workspace"
    const val WORKSPACE_ENABLED_KEY = "enabled"
    const val WORKSPACE_REMOTE_KEY = "remote"
    const val PEER_KEY = "peer"
    const val PEER_ENABLED_KEY = "enabled"
    const val PEER_LISTEN_KEY = "listen"
    const val PEER_PROXY_KEY = "proxy"
    const val PEER_TOKEN_KEY = "token"
    const val PEER_URL_KEY = "url"

    // --- 扫码配对留下的那一段 (2026-10-20, 键名与插件 `lib/luwi-configure.mjs` 的 PAIRING_KEYS 一致) --
    //
    // 扫一次码就把上面那些值全写好了, 而这一段记的是"这一台手机是被哪一张配对码配过来的" —— 界面靠它
    // 说「已配对: <from>」, 再次扫码时要拿 id 比一下是不是换了一台机器
    const val PAIRING_KEY = "pairing"
    const val PAIRING_VERSION_KEY = "v"
    const val PAIRING_ID_KEY = "id"
    const val PAIRING_AT_KEY = "at"
    const val PAIRING_FROM_KEY = "from"

    /** 配对时补上的缺省值: 设备名没起过就叫这个, 周期拉取的 0 (插件的"关") 抬到 15 分钟 */
    const val DEFAULT_DEVICE_NAME = "Luwi 手机"
    const val DEFAULT_PULL_INTERVAL = 15

    // --- 「同步」总开关记在这里的东西 (`lw` 段是**应用自己那一层**, 插件不读它) ----------
    //
    // 关掉同步时, 用户填过的那些值不是删掉而是**挪到 lw 段里存着**, 再打开时填回去 —— 于是
    // 那个开关可以随便开关而不丢配置。插件那一侧看到的仍然是"远端为空 = 不同步"
    const val LW_KEY = "lw"
    const val SAVED_REMOTE_KEY = "savedRemote"
    const val SAVED_AUTO_PULL_KEY = "savedAutoPull"
    const val SAVED_AUTO_PUSH_KEY = "savedAutoPush"
    const val SAVED_INTERVAL_KEY = "savedInterval"

    // --- 界面上的逻辑名 ('remote' 在会话段与工作区段各有一个, 所以分开叫) --------

    const val FIELD_SESSION_REMOTE = "session.remote"
    const val FIELD_WORKSPACE_REMOTE = "workspace.remote"
    const val FIELD_BRANCH = "branch"
    const val FIELD_DEVICE_NAME = "deviceName"
    const val FIELD_PUBLIC_URL = "publicUrl"
    const val FIELD_PEER_URL = "peer.url"
    const val FIELD_PEER_TOKEN = "peer.token"
    const val FLAG_AUTO_PULL = "autoPullOnStart"
    const val FLAG_AUTO_PUSH = "autoPushOnTurnEnd"
    const val FLAG_WORKSPACE_ON = "workspace.on"
    const val FLAG_PEER_ON = "peer.on"
    const val FLAG_PEER_LISTEN = "peer.listen"
    const val FLAG_PEER_PROXY = "peer.proxy"

    /** 这一段管到的逻辑名 (check 脚本拿它去比插件那一侧的键名) */
    val FIELDS: List<String> = listOf(
        FIELD_SESSION_REMOTE, FIELD_WORKSPACE_REMOTE, FIELD_BRANCH, FIELD_DEVICE_NAME, FIELD_PUBLIC_URL,
        FIELD_PEER_URL, FIELD_PEER_TOKEN,
    )

    val FLAGS: List<String> = listOf(FLAG_AUTO_PULL, FLAG_AUTO_PUSH, FLAG_WORKSPACE_ON, FLAG_PEER_ON, FLAG_PEER_LISTEN, FLAG_PEER_PROXY)

    /** 取值边界 (与插件那一侧保持一致) */
    const val MIN_TOKEN_LENGTH = 16
    const val MAX_PULL_INTERVAL = 10080
    const val MAX_DEVICE_NAME = 200

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val BRANCH_RE = Regex("^[A-Za-z0-9._/-]+$")
    private val DRIVE_RE = Regex("^[A-Za-z]:[\\\\/]")

    /** 从仓库地址里取主机名 (与插件 `remoteHostOf` 同一个式子; 本机路径取不到就回空串) */
    private val HOST_RE = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*://)?(?:[^@/]+@)?([^/:?#]+)")

    /** 设置页那一段要画的全部取值 */
    data class Snapshot(
        val sessionRemote: String = "",
        val branch: String = "",
        val deviceName: String = "",
        val publicUrl: String = "",
        val autoPull: Boolean = false,
        val autoPush: Boolean = false,
        val pullInterval: Int = 0,
        val workspaceOn: Boolean = false,
        val workspaceRemote: String = "",
        val peerOn: Boolean = false,
        val peerListen: Boolean = true,
        val peerProxy: Boolean = true,
        val peerUrl: String = "",
        val peerToken: String = "",
        /** `lw` 段里存着的上一次填过的地址 (关掉同步之后还回得来) */
        val savedRemote: String = "",
        /** 这一台手机被哪一张配对码配过 (id / from / at 都取自 `pairing` 段) */
        val pairedId: String = "",
        val pairedFrom: String = "",
        val pairedAt: Long = 0,
        /** 仓库地址里那个主机名 (只给界面显示, 不碰凭据) */
        val repoHost: String = "",
        val fileExists: Boolean = false,
    ) {
        val sessionRemoteSet: Boolean get() = sessionRemote.isNotBlank()
        val workspaceRemoteSet: Boolean get() = workspaceRemote.isNotBlank()

        /** 配过对没有 (`pairing.id` 有值就是, 手填地址那条老路仍然算未配对) */
        val paired: Boolean get() = pairedId.isNotBlank()

        /**
         * 「同步」总开关现在开着吗
         *
         * 判据就是"会话仓库配了没有" —— 与插件那一侧同一条 (它按 `remote` 认这一根镜像开没开),
         * 所以界面上的开关与插件的行为不会各说各的
         */
        val syncOn: Boolean get() = sessionRemoteSet

        /** 打开同步时会填回去的那个地址 (现在已经配了就用现在的) */
        val resumableRemote: String get() = if (sessionRemoteSet) sessionRemote else savedRemote

        /** 对端通道能不能真的跑起来 (开着 + 有口令) */
        val peerReady: Boolean get() = peerOn && peerToken.isNotBlank()
    }

    /** 界面的重画记号 (与 LockSetting 同一个做法: 存完 +1, 那一段 remember 它) */
    var revision: Int by mutableIntStateOf(0)
        private set

    /** 上一次动作之后那一句话 (失败时说清为什么) */
    var message: String? by mutableStateOf(null)
        private set

    /** 配置文件 (父目录不存在时由 `settingsDirectory` 建出来) */
    fun file(context: Context): File = File(DshHost.settingsDirectory(context), FILE)

    /** 现在的取值 */
    fun snapshot(context: Context): Snapshot = snapshotOf(file(context))

    /** 写一个文本项 */
    fun setText(context: Context, field: String, raw: String): String? =
        finish(setTextIn(file(context), field, raw))

    /** 写一个开关 */
    fun setFlag(context: Context, field: String, value: Boolean): String? =
        finish(setFlagIn(file(context), field, value))

    /** 写拉取间隔 (分钟; 0 = 关) */
    fun setPullInterval(context: Context, minutes: Int): String? =
        finish(setPullIntervalIn(file(context), minutes))

    /** 「同步」总开关现在开着吗 (菜单那一行与设置页那一段都读它) */
    fun syncOn(context: Context): Boolean = snapshot(context).syncOn

    /** 开关「同步」(关的时候把用户填过的东西挪进 `lw` 段存着, 打开时填回来) */
    fun setSyncOn(context: Context, on: Boolean): String? =
        finish(setSyncOnIn(file(context), on))

    /**
     * 把一张扫码配对得到的载荷写成配置 (2026-10-20)
     *
     * 一个动作把该写的全写掉, 于是"扫一次就能用"这件事不是靠界面上按顺序点出来的:
     *   · 会话: `remote` 与 `branch` (配对码里空分支按 main)
     *   · 对端: `enabled=true` / `proxy=true`, 地址与口令**非空才写** (空了就沿用手机现在那一份)
     *   · `pairing` 段: v / id / at / from —— 界面靠它说「已配对: <from>」, 再扫码时拿 id 比
     *   · 缺省值: 设备名没起过补 `Luwi 手机`, 周期拉取是 0 (插件的"关") 就抬到 15 分钟
     *
     * **`lw` 段与 `workspace` 段一个字节都不动** (前者是"关同步时把地址存着"那一笔, 后者是
     * 工作区镜像那一根, 与配对无关), 别的键照旧整棵树抄过去
     * @return null 成功, 否则是一句能给人看的原因
     */
    fun applyPairing(context: Context, payload: PairPayload): String? =
        finish(applyPairingIn(file(context), payload))

    /** 配置文件里那棵树的原文 (令牌已打码; 设置页「看当前配置」用) */
    fun pretty(context: Context): String = prettyOf(file(context))

    /** 令牌的显示形态 (界面只许出现它) */
    fun maskedToken(value: String): String = when {
        value.isBlank() -> "<unset>"
        value.length < 8 -> "••••"
        else -> "••••" + value.takeLast(4)
    }

    // --- 纯文件那一半 (单测直接吃它) -------------------------------------------

    /** 读一份配置的取值 (文件不在或读不懂时全按缺省, 与插件"坏文件 = 没有配置"同一个口径) */
    fun snapshotOf(file: File): Snapshot = runCatching {
        val root = readOf(file)
        val workspace = root[WORKSPACE_KEY] as? JsonObject
        val peer = root[PEER_KEY] as? JsonObject
        val lw = root[LW_KEY] as? JsonObject
        val pairing = root[PAIRING_KEY] as? JsonObject
        val remote = text(root, SESSION_REMOTE_KEY)
        Snapshot(
            sessionRemote = remote,
            branch = text(root, BRANCH_KEY),
            deviceName = text(root, DEVICE_NAME_KEY),
            publicUrl = text(root, PUBLIC_URL_KEY),
            autoPull = flag(root, AUTO_PULL_KEY) ?: false,
            autoPush = flag(root, AUTO_PUSH_KEY) ?: false,
            pullInterval = number(root, PULL_INTERVAL_KEY) ?: 0,
            workspaceOn = workspace?.let { flag(it, WORKSPACE_ENABLED_KEY) ?: text(it, WORKSPACE_REMOTE_KEY).isNotBlank() } ?: false,
            workspaceRemote = workspace?.let { text(it, WORKSPACE_REMOTE_KEY) } ?: "",
            peerOn = peer?.let { flag(it, PEER_ENABLED_KEY) ?: text(it, PEER_TOKEN_KEY).isNotBlank() } ?: false,
            peerListen = peer?.let { flag(it, PEER_LISTEN_KEY) ?: true } ?: true,
            peerProxy = peer?.let { flag(it, PEER_PROXY_KEY) ?: true } ?: true,
            peerUrl = peer?.let { text(it, PEER_URL_KEY) } ?: "",
            peerToken = peer?.let { text(it, PEER_TOKEN_KEY) } ?: "",
            savedRemote = lw?.let { text(it.toMap(), SAVED_REMOTE_KEY) } ?: "",
            pairedId = pairing?.let { text(it, PAIRING_ID_KEY) } ?: "",
            pairedFrom = pairing?.let { text(it, PAIRING_FROM_KEY) } ?: "",
            pairedAt = pairing?.let { (it[PAIRING_AT_KEY] as? JsonPrimitive)?.longOrNull } ?: 0,
            repoHost = remoteHostOf(remote),
            fileExists = file.isFile,
        )
    }.getOrElse { Snapshot() }

    /**
     * 开关「同步」这一件事的收口 (一个动作把四件事一起做完)
     *
     * **关**: 远端地址挪进 `lw.savedRemote` 存着 (不是删), 再把 `autoPullOnStart` / `autoPushOnTurnEnd` /
     * `pullIntervalMinutes` 现在这三个值挪进 `lw` 段, 然后三个都归零 —— 两件事缺一不可: 插件的引擎是
     * **无条件建**的 (远端为空照样建), 只清远端的话周期拉取还在跑, 而那一轮要经 Linux 沙箱起 proot+git
     * (2026-10-14 实测一条最轻的 `git --version` 就 2.1 s), 白烧电
     *
     * **开**: 把 `lw.savedRemote` 填回 `remote`, 三个自动值按关之前的样子还回去 (没存过就保持关着 =
     * 只用时手动拉)
     *
     * 用户填过的东西一样都不丢 —— 这是这个开关敢做成一键的前提
     * @return null 成功; 否则是一句能给人看的原因。
     */
    fun setSyncOnIn(file: File, on: Boolean): String? {
        val root = readOf(file)
        val lwNow = (root[LW_KEY] as? JsonObject)?.toMap() ?: emptyMap()
        if (on && text(root, SESSION_REMOTE_KEY).ifBlank { text(lwNow, SAVED_REMOTE_KEY) }.isBlank()) {
            // 2026-10-20 起手机侧不再有「仓库地址」那一行: 地址只有一个来处 = 扫码配对
            return "还没配对 —— 先在 PC 面板生成二维码, 再点「扫码配对」"
        }
        return runCatching {
            val next = root.toMutableMap()
            val lw = lwNow.toMutableMap()
            if (on) {
                next[SESSION_REMOTE_KEY] = JsonPrimitive(text(root, SESSION_REMOTE_KEY).ifBlank { text(lwNow, SAVED_REMOTE_KEY) })
                lw.remove(SAVED_REMOTE_KEY)
                (lw.remove(SAVED_AUTO_PULL_KEY) as? JsonPrimitive)?.booleanOrNull?.let { next[AUTO_PULL_KEY] = JsonPrimitive(it) }
                (lw.remove(SAVED_AUTO_PUSH_KEY) as? JsonPrimitive)?.booleanOrNull?.let { next[AUTO_PUSH_KEY] = JsonPrimitive(it) }
                (lw.remove(SAVED_INTERVAL_KEY) as? JsonPrimitive)?.intOrNull?.let { next[PULL_INTERVAL_KEY] = JsonPrimitive(it) }
            } else {
                text(root, SESSION_REMOTE_KEY).ifBlank { null }?.let { lw[SAVED_REMOTE_KEY] = JsonPrimitive(it) }
                next.remove(SESSION_REMOTE_KEY)
                flag(root, AUTO_PULL_KEY)?.let { lw[SAVED_AUTO_PULL_KEY] = JsonPrimitive(it) }
                flag(root, AUTO_PUSH_KEY)?.let { lw[SAVED_AUTO_PUSH_KEY] = JsonPrimitive(it) }
                number(root, PULL_INTERVAL_KEY)?.let { lw[SAVED_INTERVAL_KEY] = JsonPrimitive(it) }
                next[AUTO_PULL_KEY] = JsonPrimitive(false)
                next[AUTO_PUSH_KEY] = JsonPrimitive(false)
                next[PULL_INTERVAL_KEY] = JsonPrimitive(0)
            }
            if (lw.isEmpty()) next.remove(LW_KEY) else next[LW_KEY] = JsonObject(lw)
            write(file, JsonObject(next))
            null
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /**
     * 扫码配对写盘那一半 (纯文件, 单测直接吃它)
     *
     * @param now 配对时刻 (载荷里没带 `at` 时用它, 也是写进 `pairing.at` 的那个数)
     */
    fun applyPairingIn(file: File, payload: PairPayload, now: Long = System.currentTimeMillis()): String? {
        if (payload.remote.isBlank()) return "配对码里没有仓库地址"
        return runCatching {
            val root = readOf(file).toMutableMap()
            root[SESSION_REMOTE_KEY] = JsonPrimitive(payload.remote)
            root[BRANCH_KEY] = JsonPrimitive(payload.branch.ifBlank { "main" })
            // 对端那一段: 别的键 (listen / sendTimeoutMs / …) 都留着, 只改这三件
            val peer = (root[PEER_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            peer[PEER_ENABLED_KEY] = JsonPrimitive(true)
            peer[PEER_PROXY_KEY] = JsonPrimitive(true)
            if (payload.peerUrl.isNotEmpty()) peer[PEER_URL_KEY] = JsonPrimitive(payload.peerUrl)
            if (payload.peerToken.isNotEmpty()) peer[PEER_TOKEN_KEY] = JsonPrimitive(payload.peerToken)
            root[PEER_KEY] = JsonObject(peer)
            // 配对那一段: 界面靠它说「已配对: <from>」, 再扫一次时靠 id 认出换了一台机器
            val pairing = (root[PAIRING_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            pairing[PAIRING_VERSION_KEY] = JsonPrimitive(payload.v)
            pairing[PAIRING_ID_KEY] = JsonPrimitive(payload.id)
            pairing[PAIRING_AT_KEY] = JsonPrimitive(payload.at ?: now)
            pairing[PAIRING_FROM_KEY] = JsonPrimitive(payload.from)
            root[PAIRING_KEY] = JsonObject(pairing)
            if (text(root, DEVICE_NAME_KEY).isBlank()) {
                root[DEVICE_NAME_KEY] = JsonPrimitive(DEFAULT_DEVICE_NAME)
            }
            // 配对之后默认就是"该自动的自动": 挂载拉一次, 每轮推一次, 周期拉取 15 分钟
            root[AUTO_PULL_KEY] = JsonPrimitive(true)
            root[AUTO_PUSH_KEY] = JsonPrimitive(true)
            if ((number(root, PULL_INTERVAL_KEY) ?: 0) == 0) {
                root[PULL_INTERVAL_KEY] = JsonPrimitive(DEFAULT_PULL_INTERVAL)
            }
            write(file, JsonObject(root))
            null
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /**
     * 写一个文本项。空串 = 把这个键删掉 (留空就是"没配")。
     * @return null 成功; 否则是一句能给人看的原因。
     */
    fun setTextIn(file: File, field: String, raw: String): String? {
        val value = raw.trim()
        val problem = when (field) {
            FIELD_SESSION_REMOTE, FIELD_WORKSPACE_REMOTE -> remoteProblem(value)
            FIELD_PEER_URL -> peerUrlProblem(value)
            FIELD_PUBLIC_URL -> publicUrlProblem(value)
            FIELD_DEVICE_NAME -> nameProblem(value)
            FIELD_BRANCH -> branchProblem(value)
            FIELD_PEER_TOKEN -> tokenProblem(value)
            else -> "不认识这一项: $field"
        }
        if (problem != null) return problem
        return runCatching {
            val primitive = value.ifEmpty { null }?.let(::JsonPrimitive)
            when (field) {
                FIELD_SESSION_REMOTE -> patchTop(file, SESSION_REMOTE_KEY, primitive)
                FIELD_BRANCH -> patchTop(file, BRANCH_KEY, primitive)
                FIELD_DEVICE_NAME -> patchTop(file, DEVICE_NAME_KEY, primitive)
                FIELD_PUBLIC_URL -> patchTop(file, PUBLIC_URL_KEY, primitive)
                FIELD_WORKSPACE_REMOTE -> patchSection(file, WORKSPACE_KEY, WORKSPACE_REMOTE_KEY, primitive)
                FIELD_PEER_URL -> patchSection(file, PEER_KEY, PEER_URL_KEY, primitive)
                FIELD_PEER_TOKEN -> patchSection(file, PEER_KEY, PEER_TOKEN_KEY, primitive)
            }
            null
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /**
     * 写一个开关。
     * @return null 成功; 否则是原因。
     */
    fun setFlagIn(file: File, field: String, value: Boolean): String? = runCatching {
        when (field) {
            FLAG_AUTO_PULL -> patchTop(file, AUTO_PULL_KEY, JsonPrimitive(value))
            FLAG_AUTO_PUSH -> patchTop(file, AUTO_PUSH_KEY, JsonPrimitive(value))
            FLAG_WORKSPACE_ON -> patchSection(file, WORKSPACE_KEY, WORKSPACE_ENABLED_KEY, JsonPrimitive(value))
            FLAG_PEER_ON -> patchSection(file, PEER_KEY, PEER_ENABLED_KEY, JsonPrimitive(value))
            FLAG_PEER_LISTEN -> patchSection(file, PEER_KEY, PEER_LISTEN_KEY, JsonPrimitive(value))
            FLAG_PEER_PROXY -> patchSection(file, PEER_KEY, PEER_PROXY_KEY, JsonPrimitive(value))
            else -> return "不认识这一个开关: $field"
        }
        null
    }.getOrElse { "写入失败: ${it.message}" }

    /**
     * 写拉取间隔 (分钟; 0 = 关)。
     * @return null 成功; 否则是原因。
     */
    fun setPullIntervalIn(file: File, minutes: Int): String? {
        if (minutes < 0 || minutes > MAX_PULL_INTERVAL) return "拉取间隔要在 0..$MAX_PULL_INTERVAL 分钟之间"
        return runCatching {
            patchTop(file, PULL_INTERVAL_KEY, JsonPrimitive(minutes))
            null
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /** 配置文件的原文 (令牌打码) */
    fun prettyOf(file: File): String = runCatching {
        if (!file.isFile) return "还没有这一份配置 (${file.absolutePath})"
        maskTokensIn(file.readText())
    }.getOrElse { "读不出来: ${it.message}" }

    // --- 读写 -----------------------------------------------------------------

    private fun readOf(file: File): Map<String, JsonElement> {
        if (!file.isFile) return emptyMap()
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject.toMap() }.getOrElse { emptyMap() }
    }

    private fun patchTop(file: File, key: String, value: JsonElement?) {
        val root = readOf(file).toMutableMap()
        if (value == null) root.remove(key) else root[key] = value
        write(file, JsonObject(root))
    }

    private fun patchSection(file: File, section: String, key: String, value: JsonElement?) {
        val root = readOf(file).toMutableMap()
        val current = root[section] as? JsonObject ?: JsonObject(emptyMap())
        val next = current.toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        root[section] = JsonObject(next)
        write(file, JsonObject(root))
    }

    private fun write(file: File, root: JsonObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    private fun text(root: Map<String, JsonElement>, key: String): String =
        (root[key] as? JsonPrimitive)?.jsonPrimitive?.content ?: ""

    private fun flag(root: Map<String, JsonElement>, key: String): Boolean? =
        (root[key] as? JsonPrimitive)?.booleanOrNull

    private fun number(root: Map<String, JsonElement>, key: String): Int? =
        (root[key] as? JsonPrimitive)?.intOrNull

    /** 把配置里出现的令牌换成打码形态 (那一页是给人看的, 截图里也不该带真令牌) */
    private fun maskTokensIn(text: String): String =
        text.replace(Regex("\"$PEER_TOKEN_KEY\"\\s*:\\s*\"([^\"]*)\"")) { match ->
            "\"$PEER_TOKEN_KEY\": \"${maskedToken(match.groupValues[1])}\""
        }

    /** 把纯文件那一半的结果接到界面上 (存完重画那一段) */
    private fun finish(problem: String?): String? {
        message = problem
        if (problem == null) revision += 1
        return problem
    }

    // --- 校验 -----------------------------------------------------------------

    /** 仓库地址里的主机名 (只给界面显示, 不碰凭据; 本机路径取不到就回空串) */
    private fun remoteHostOf(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        return HOST_RE.find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun remoteProblem(value: String): String? {
        if (value.isEmpty()) return null
        if (value.any { it.isWhitespace() }) return "地址里不能有空格或换行"
        val looksLikeRemote = value.contains("://") || value.contains('@') ||
            value.startsWith("/") || DRIVE_RE.containsMatchIn(value)
        return if (looksLikeRemote) null else "这不像一个仓库地址 (http(s):// / git:// / ssh:// / user@host:path / 本地路径)"
    }

    private fun peerUrlProblem(value: String): String? {
        if (value.isEmpty()) return null
        if (value.any { it.isWhitespace() }) return "地址里不能有空格或换行"
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            null
        } else {
            "对端地址要以 http:// 或 https:// 开头 (留空 = 从对端名片里取)"
        }
    }

    private fun publicUrlProblem(value: String): String? {
        if (value.isEmpty()) return null
        if (value.any { it.isWhitespace() }) return "地址里不能有空格或换行"
        return if (value.startsWith("http://") || value.startsWith("https://")) null else "本机地址要以 http:// 或 https:// 开头"
    }

    private fun nameProblem(value: String): String? {
        if (value.length > MAX_DEVICE_NAME) return "设备名最多 $MAX_DEVICE_NAME 个字"
        return if (value.any { it == '\n' || it == '\r' }) "设备名不能换行" else null
    }

    private fun branchProblem(value: String): String? {
        if (value.isEmpty()) return null
        return if (BRANCH_RE.matches(value)) null else "分支名只能用字母数字与 . _ / -"
    }

    private fun tokenProblem(value: String): String? {
        if (value.isEmpty()) return null
        if (value.length < MIN_TOKEN_LENGTH) return "连接口令至少 $MIN_TOKEN_LENGTH 个字符 (两端要写同一个)"
        if (value.any { it.isWhitespace() }) return "连接口令里不能有空格或换行"
        return null
    }
}
