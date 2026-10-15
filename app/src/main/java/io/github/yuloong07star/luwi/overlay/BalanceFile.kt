package io.github.yuloong07star.luwi.overlay

import android.content.Context
import android.os.FileObserver
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 「余额还有多少」: **一份文件, 宿主写, 应用读** (与 `ball-phase.json` 同一条路, 2026-10-16)
 *
 * 余额住在 dsh 的账号缝里 (宿主那一侧才有), 而球与那块文本框在应用进程里 —— 所以又是"宿主把事实落成
 * 文件, 应用想什么时候看就什么时候读"。谁都不许推送: 那条路送不到就没人补
 *
 * ```json
 * {"v":2,"at":1760000000000,"ok":true,"provider":"moonshot","model":"kimi-k2","label":"Moonshot",
 *  "kind":"money","currency":"CNY","value":"12.34","bonus":"5.00","percent":0,"reason":""}
 * ```
 *
 * `kind` 两档 (2026-10-17): `money` 是金额, `quota` 是订阅制那种"还剩百分之多少"的额度 —— 后者认
 * [Snapshot.percent], 货币与金额那两栏没有意义。旧宿主 (2026-10-17 之前) 只写金额那一档, 那几个新
 * 键一个都没有, 于是缺省按 `money` 读
 *
 * `ok:false` 时 [Snapshot.reason] 是一句**宿主自己写的人话** (未登录 / 没有账号缝 / 网络失败), 应用只
 * 负责把它递到主人眼前, 绝不自己编一个 0 出来
 *
 * 应用→宿主那条"现在刷一次"的方向走**请求记号** (`balance-request.json`, `{"at":ms}`): 宿主在它本来就
 * 有的那条 3 s 轮询里顺手看一眼, 有就刷一次。余额自己平时也按它自己的节拍刷新 (起来一次 / 每轮结束),
 * 所以这一条只是"主人正看着呢, 别让他等"
 */
internal object BalanceFile {

    /** 与宿主那侧同一个约定 (谁都不许单边改) */
    private const val DIRECTORY = "lw"
    private const val FILE_NAME = "balance.json"
    private const val REQUEST_NAME = "balance-request.json"

    /** 答案的那两档: 金额 (带币种) 与订阅额度 (带百分比) —— 与宿主那侧的 `kind` 一一对应 */
    const val KIND_MONEY = "money"
    const val KIND_QUOTA = "quota"

    /** 一次读的答案 */
    internal data class Snapshot(
        val at: Long,
        val ok: Boolean,
        /** 当前模型的提供方没有余额源 (宿主那侧 [ BALANCE_SOURCES ] 表里没有它) —— 应用说「不支持」 */
        val unsupported: Boolean,
        /** 这一笔钱是谁家的 (宿主给的展示名; 旧宿主写的那一份没有这个键, 那就是空串) */
        val label: String,
        /**
         * 这份答案是哪一档: [KIND_MONEY] 是金额, [KIND_QUOTA] 是订阅制那种"还剩百分之多少"
         *
         * 旧宿主 (2026-10-17 之前那一份) 只写金额, 没有这个键 —— 缺省按金额读
         */
        val kind: String,
        val currency: String,
        val value: String,
        /** 赠金那一份 (宿主那侧叫 bonusWallets), 空串 = 没有 */
        val bonus: String,
        /** [KIND_QUOTA] 那一档的剩余百分比 (其余档位没有意义) */
        val percent: Int,
        val reason: String,
    )

    /** 余额那一份在哪儿 (读与 Watch 共用) */
    fun file(context: Context): File = File(File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY), FILE_NAME)

    private fun requestFile(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY), REQUEST_NAME)

    /** 现在那份余额 (文件不存在或读不出来就是 null —— 那是"宿主还没写过", 不是"没余额") */
    fun snapshot(context: Context): Snapshot? {
        val file = file(context)
        if (!file.isFile) return null
        return runCatching { parse(file.readText()) }.getOrNull()
    }

    /** 解析那一份 JSON: 纯函数 (只吃一个 String), 判据在 `BalanceFileTest` */
    fun parse(text: String): Snapshot? {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val at = (root["at"] as? JsonPrimitive)?.longOrNull ?: return null
        // `ok` 与 `at` 一样是宿主一定会写的那两个键: 缺一个那一份就不可信, 当"还没写过"
        // (旧文件里 `unsupported` 可以缺, 那一条另有口径, 见下面)
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        fun text(key: String): String = (root[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Snapshot(
            at = at,
            ok = ok,
            // 旧宿主写的文件没有这一个键 (那是 2026-10-16 之前那一份), 当 false —— 老文件走 reason 那条
            unsupported = (root["unsupported"] as? JsonPrimitive)?.booleanOrNull ?: false,
            // 2026-10-17 之前那份文件没有 label / kind / percent 三个键: 那是只有一家 (DeepSeek 账号缝)
            // 的年代, 按"没有名字的金额"读 —— 老宿主配新应用照样显示得出来
            label = text("label"),
            kind = text("kind").ifBlank { KIND_MONEY },
            currency = text("currency"),
            value = text("value"),
            bonus = text("bonus"),
            percent = (root["percent"] as? JsonPrimitive)?.intOrNull ?: 0,
            reason = text("reason"),
        )
    }

    /**
     * 写一个"现在刷一次"的请求记号
     *
     * 同一个记号写多少遍都无所谓 (宿主只看它的新旧), 所以这里不做去重 —— 一句 `writeText` 的事
     */
    fun request(context: Context) {
        runCatching {
            requestFile(context).parentFile?.mkdirs()
            requestFile(context).writeText("{\"at\":${System.currentTimeMillis()}}\n")
        }
    }

    /** 这个记号是不是主人刚写的 (宿主那侧判"要不要刷"用; 两侧同一个数, 谁都不许单边改) */
    fun requestFresh(context: Context, withinMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val file = requestFile(context)
        if (!file.isFile) return false
        val at = runCatching {
            (Json.parseToJsonElement(file.readText()).jsonObject["at"] as? JsonPrimitive)?.longOrNull
        }.getOrNull() ?: return false
        return now - at in 0..withinMs
    }

    /**
     * 盯着那个目录: 宿主一写余额 (或那个请求记号被写出来) 就叫醒
     *
     * 形状与 [BallPhaseFile.Watch] 一模一样 —— 盯**目录**而不是文件 (文件可能是第一次写, 而它是
     * 原子写出来的, `rename` 在目录上留下的是 `MOVED_TO`)
     */
    internal class Watch(private val onChanged: () -> Unit) {

        private var observer: FileObserver? = null

        fun start(context: Context) {
            if (observer != null) return
            val directory = file(context).parentFile ?: return
            directory.mkdirs()
            val created = object : FileObserver(directory, MASK) {
                override fun onEvent(event: Int, name: String?) {
                    if (name != FILE_NAME && name != "$FILE_NAME.tmp" && name != REQUEST_NAME) return
                    onChanged()
                }
            }
            observer = created
            created.startWatching()
        }

        fun stop() {
            observer?.stopWatching()
            observer = null
        }

        private companion object {
            /** 原子写走的是 `CLOSE_WRITE` (直接写) 与 `MOVED_TO` (rename), `CREATE` 兜住新文件 */
            private const val MASK =
                FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        }
    }
}
