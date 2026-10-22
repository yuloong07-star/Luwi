package io.github.yuloong07star.luwi.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * 扫码配对的那一份载荷 (2026-10-20)
 *
 * 二维码里就是一串 `luwi-sync://pair?d=<base64url(JSON utf8)>` —— 仓库地址 / 分支 / 对端地址与口令 /
 * 配对 id 一次带过来, 手机扫一次就把 `session-sync.json` 写全, 规则与插件那一侧
 * (`host-plugins/luwi-dsh-sync/lib/luwi-pairing.mjs`) **逐字一致**: 两侧各校验一遍, 跑哪一边都得出
 * 同一个答案, 否则"PC 说配对码是好的而手机说坏了"这种最难查的状态就会出现
 *
 * **这一份是纯 Kotlin, 一点 Android 都不碰** (只有 `java.util.Base64`), 于是它能在单测里直接量
 * (见 [PairPayloadTest]), 也就不用为了量一个正则去起模拟器
 *
 * 错误分九档 ([PairError]), 每一档带一句中文 —— 界面上直接把它念出来, 不再翻译一遍
 */
internal data class PairPayload(
    val v: Int,
    val id: String,
    val remote: String,
    val branch: String,
    val peerUrl: String,
    val peerToken: String,
    val from: String,
    /** 生成时刻 (ms), 插件那一侧是可省略的, 所以这里是可空 */
    val at: Long?,
) {

    companion object {

        /** 二维码文本的前缀 (与插件 `PAIR_PREFIX` 同一个字面量) */
        const val PREFIX = "luwi-sync://pair?d="

        /** 只认这一版载荷 */
        const val VERSION = 1

        /** 载荷上限 (字节), 与插件 `PAIR_MAX_BYTES` 同一个数 */
        const val MAX_BYTES = 1200

        /** 口令长度 (与插件 `PEER_LIMITS` 同一个区间) */
        const val MIN_TOKEN_LENGTH = 16
        const val MAX_TOKEN_LENGTH = 256

        /** 设备名上限 (与插件那一侧同一个数) */
        const val MAX_FROM_LENGTH = 64

        /** base64url 之后最多这么长 (1200 字节的 4/3 再加一点余量, 与插件同一个算式) */
        private const val MAX_ENCODED_LENGTH = (MAX_BYTES * 4 + 2) / 3 + 8

        /** 配对 id: PC 生成的 uuid 形态, 首字符是字母或数字, 后面只收字母数字与减号 */
        private val ID_RE = Regex("^[0-9A-Za-z][0-9A-Za-z-]{7,127}$")

        /** 分支名: 空 = main; 非空只收这几个字符 (与 SyncSetting 那一侧同一个口径) */
        private val BRANCH_RE = Regex("^[A-Za-z0-9._/-]+$")

        /** 对端地址: 空 = 沿用手机现在那一份, 非空必须是 http(s) 且不带空白 */
        private val HTTP_URL_RE = Regex("^https?://\\S+$")

        /** base64url 字母表 (先看字符, 再解码 —— 解码抛异常那条路不好分档) */
        private val BASE64URL_RE = Regex("^[A-Za-z0-9_-]+$")

        private val WHITESPACE_RE = Regex("\\s")

        /** `git@host:you/repo.git` 那一形 */
        private val SCP_LIKE_RE = Regex("^[\\w.-]+@[\\w.-]+:")

        /** `D:/repo.git` 与 `D:\repo.git` 都收 */
        private val DRIVE_RE = Regex("^[A-Za-z]:[\\\\/]")

        /** `https://` / `http://` / `git://` / `ssh://` / `file://` */
        private val SCHEME_RE = Regex("^(https?|git|ssh|file)://", RegexOption.IGNORE_CASE)

        /** 一段扫到 (或粘到) 的文本 → 载荷
         *
         * 顺序就是插件那一侧的顺序: 前缀 → base64url → JSON → 逐项校验, 先看前缀再看解码, 于是
         * "扫到的是别人的二维码"与"配对码坏了"能分开说 —— 前者要人换个码, 后者要人重新生成
         */
        fun parse(text: String): PairResult {
            val raw = text.trim()
            if (raw.isEmpty()) return PairResult.Rejected(PairError.NotPairingCode)
            if (!raw.startsWith(PREFIX)) return PairResult.Rejected(PairError.NotPairingCode)
            val encoded = raw.substring(PREFIX.length)
            if (!BASE64URL_RE.matches(encoded)) return PairResult.Rejected(PairError.CorruptedCode)
            if (encoded.length > MAX_ENCODED_LENGTH) return PairResult.Rejected(PairError.TooLong)
            val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }
                .getOrElse { return PairResult.Rejected(PairError.CorruptedCode) }
            if (bytes.size > MAX_BYTES) return PairResult.Rejected(PairError.TooLong)
            // 坏 UTF-8 不会抛: 它会变成替换字符, 而那样一段文字过不了 JSON 那一关
            val body = String(bytes, Charsets.UTF_8)
            val element = runCatching { Json.parseToJsonElement(body) }
                .getOrElse { return PairResult.Rejected(PairError.CorruptedCode) }
            val root = element as? JsonObject ?: return PairResult.Rejected(PairError.CorruptedCode)
            return validate(root)
        }

        /** 已经解开的 JSON 对象 → 载荷 (宿主那一侧 [validatePairingPayload] 的同一个流程) */
        private fun validate(root: JsonObject): PairResult {
            val version = number(root, "v")?.toInt()
            if (version == null || version != VERSION) {
                return PairResult.Rejected(PairError.UnsupportedVersion)
            }
            val id = string(root, "id")
            if (!ID_RE.matches(id)) return PairResult.Rejected(PairError.BadId)
            val remote = string(root, "remote")
            if (remoteProblem(remote)) return PairResult.Rejected(PairError.BadRemote)
            val branch = string(root, "branch")
            if (branch.isNotEmpty() && !BRANCH_RE.matches(branch)) {
                return PairResult.Rejected(PairError.BadBranch)
            }
            val peerUrl = string(root, "peerUrl")
            if (peerUrl.isNotEmpty() && !HTTP_URL_RE.matches(peerUrl)) {
                return PairResult.Rejected(PairError.BadPeerUrl)
            }
            val peerToken = string(root, "peerToken")
            if (peerTokenProblem(peerToken)) return PairResult.Rejected(PairError.BadPeerToken)
            val from = string(root, "from")
            if (from.length > MAX_FROM_LENGTH) return PairResult.Rejected(PairError.TooLong)
            return PairResult.Ok(
                PairPayload(
                    v = VERSION,
                    id = id,
                    remote = remote,
                    branch = branch.ifEmpty { "main" },
                    peerUrl = peerUrl,
                    peerToken = peerToken,
                    from = from,
                    at = number(root, "at")?.toLong(),
                ),
            )
        }

        /**
         * 地址像不像一个 git 远端 (空 = 非法: 扫码配对就是要一个仓库)
         *
         * 与插件 `remoteProblem` 同一串判断: 有空白就拒; 认 `https/git/ssh/file://`,
         * `user@host:path`, 盘符路径, 以及以 `/` 或 `.` 开头的本机路径
         */
        private fun remoteProblem(value: String): Boolean {
            if (value.isEmpty()) return true
            if (WHITESPACE_RE.containsMatchIn(value)) return true
            if (SCHEME_RE.containsMatchIn(value)) return false
            if (SCP_LIKE_RE.containsMatchIn(value)) return false
            if (DRIVE_RE.containsMatchIn(value)) return false
            return !(value.startsWith("/") || value.startsWith("."))
        }

        /** 口令: 空 = 沿用手机现在那一份; 非空必须 16..256 个非空白字符 */
        private fun peerTokenProblem(value: String): Boolean {
            if (value.isEmpty()) return false
            if (value.length < MIN_TOKEN_LENGTH || value.length > MAX_TOKEN_LENGTH) return true
            return WHITESPACE_RE.containsMatchIn(value)
        }

        /** 取一个字符串键 (不是字符串就当空, 与插件 `typeof === 'string'` 那一条同一个答案) */
        private fun string(root: JsonObject, key: String): String =
            (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

        /** 取一个数字键 (字符串里的数字不算数字, 与插件 `Number.isFinite` 那一条对齐) */
        private fun number(root: JsonObject, key: String): Double? {
            val primitive = (root[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
            return primitive.longOrNull?.toDouble() ?: primitive.doubleOrNull
        }
    }
}

/** 配对码被拒的那九档 (顺序与施工单一致, 每一档一句中文) */
internal enum class PairError(val message: String) {
    /** 扫到的是别的二维码 */
    NotPairingCode("这不是 Luwi 同步的配对码"),

    /** 前缀对但内容坏了 */
    CorruptedCode("配对码坏了, 让 PC 面板重新生成一张"),

    /** 版本不认识 */
    UnsupportedVersion("这个配对码是新版本才有的, 两端都升到最新再试"),

    /** 配对 id 不对 */
    BadId("配对码里的 id 不对 (要 PC 面板生成的那一个)"),

    /** 仓库地址不对 */
    BadRemote("配对码里的仓库地址不对 (可以是 https:// / git:// / ssh:// / user@host:path / 本地路径)"),

    /** 分支名不对 */
    BadBranch("配对码里的分支名不对 (只能字母数字与 . _ / -)"),

    /** 对端地址不对 */
    BadPeerUrl("配对码里的对端地址要是 http(s) 的地址"),

    /** 口令不对 */
    BadPeerToken("配对码里的连接口令要 16 到 256 个非空白字符"),

    /** 太长 */
    TooLong("配对码里的内容太长了 (整段文本与设备名都有上限)"),
}

/**
 * 解析的结果
 *
 * 没用 `kotlin.Result`: 它带一个可以用 `exceptionOrNull()` 取出来的异常, 而这里要的是**一档可以说给
 * 人听的错误**, 不是一个异常对象
 */
internal sealed class PairResult {
    data class Ok(val payload: PairPayload) : PairResult()

    data class Rejected(val error: PairError) : PairResult()
}
