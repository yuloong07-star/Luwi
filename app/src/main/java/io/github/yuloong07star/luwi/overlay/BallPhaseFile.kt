package io.github.yuloong07star.luwi.overlay

import android.content.Context
import android.os.FileObserver
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * 「现在哪几场在跑」: **一份文件, 宿主写, 应用读** (2026-10-08 主人定的口径)
 *
 * 原来这件事是宿主**推送**的 (`overlay op=phase`), 而推送有个躲不掉的毛病: 送不到就没人补 ——
 * 应用那一刻被系统冻住、通道一时连不上, 球上那三个字就永远留着 (我为此加过一个 20 秒的看门狗,
 * 而主人嫌它延迟太大)。改成文件之后这条毛病整个消失:
 *
 * - **文件就是真相**: 谁什么时候读都读得到同一份, 没有"送丢了"这回事
 * - **两侧都是事件驱动, 零轮询**: 宿主只在集合真的变化时写 (一轮两次), 应用用
 *   [FileObserver] 盯着那个目录, 一改就被叫醒 —— `$DSH_HOME` 在 `filesDir` 下, 是真文件系统而
 *   不是 `/sdcard` 那个 FUSE, 所以 inotify 可用 (与 `voice/inbox.jsonl` 是同一条理由, 只是方向相反)
 * - **重来一遍是免费的**: 应用重启、服务重建、宿主重启, 读一次文件就能对上
 *
 * 文件由宿主原子写 (先写 `.tmp` 再 rename), 所以这里读到的永远是完整的一份:
 *
 * ```json
 * {"v":1,"at":1759600000000,"turns":[{"id":"session-…","startedAt":1759599990000}],
 *  "last":{"id":"session-…","at":1759600000000,"kind":"error","why":"…"}}
 * ```
 *
 * `turns` 是"哪几场在跑", `last` 是"刚结束的那一轮是怎么收的" —— [Snapshot] 一次读同时回答这两件,
 * 因为球上那两个字段（「正在想」与「失败」）出自同一份文件。`last` 是 2026-10-08 加的**可选**键:
 * 没有它 (旧宿主 / 还没结束过任何一轮 / 宿主刚重启) 就是"没有失败", 与 `v` 的版本号无关
 *
 * 球上只显示**最近开始的那一场** ([Snapshot.latest]), 而它的颜色按会话固定 ([colorFor]) —— 于是
 * 多个会话同时在想时, 一眼看得出"现在这个正在想是不是我先前那一场"
 */
internal object BallPhaseFile {

    /** 与宿主那侧同一个约定 (谁都不许单边改) */
    private const val DIRECTORY = "lw"
    private const val FILE_NAME = "ball-phase.json"

    /** 五个环色, **轮转着发给会话**: 琥珀是原来"正在想"那个色, 所以它当第 0 个 */
    private val palette = intArrayOf(
        0xFFFFC24D.toInt(),
        0xFFFF7A6B.toInt(),
        0xFF4FC3F7.toInt(),
        0xFFE879C6.toInt(),
        0xFFC7F04A.toInt(),
    )

    /** 记多少个会话的颜色 (超过就丢最老的): 5 个色轮转, 记住这点量足够"认得出我之前那一场" */
    private const val KEEP = 64

    private const val STORE = "luwi"
    private const val KEY_COLORS = "ball-session-colors"

    /** 在跑的一场: 会话 id 与它什么时候开始的 */
    internal data class Turn(val id: String, val startedAt: Long)

    /**
     * 最近结束的那一轮: 谁、什么时候收的、以及**怎么收的**
     *
     * [kind] 是 dsh 那张表的原样透传 (`completed` / `aborted` / `blocked` / `error` / `max-tokens` /
     * `interrupted` / `forked`), [why] 只有失败带结构化理由时才有。**哪几种该在球上写成「失败」由
     * 应用这一侧定** (见 [BallFailure.counts]) —— 于是 dsh 以后多一种原因, 宿主那一侧一个字都不用改
     */
    internal data class Ended(val id: String, val at: Long, val kind: String, val why: String)

    /**
     * 一次读的答案: 在跑的那几场、最近结束的那一轮、以及读不出来的那句人话
     *
     * [running] 是**整份**在跑的清单 (2026-10-09 加的): 球上那个字只要 [latest] 一场, 而"同一场起了
     * 新的一轮"那条判据要的是"某一场的 id 还在不在这一批里" —— 只留最近那一场就答不了 (见
     * `OverlayService.refresh` 那两笔自动收口)
     */
    internal data class Snapshot(val running: List<Turn>, val last: Ended?, val note: String) {

        /** 球上只显示**最近开始的那一场**: 那一场决定字与环色 (见文件头) */
        val latest: Turn? get() = running.maxByOrNull { it.startedAt }

        /** 这一批里最新的开始时刻 (0 = 一场都没有): "起了一轮新的"那个边沿靠它认 */
        val newestAt: Long get() = latest?.startedAt ?: 0L
    }

    fun directory(context: Context): File = File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY)

    fun file(context: Context): File = File(directory(context), FILE_NAME)

    /**
     * 读一次那份文件
     *
     * 文件不在 / 空表 / 读不懂: 那几项各自是 null / 空表, 而 [Snapshot.note] 里是一句人话
     * (`overlay op=state` 会把它报出去 —— "球为什么不显示正在想"这件事得有个读数, 而不是只能猜)
     */
    fun snapshot(context: Context): Snapshot {
        val source = file(context)
        if (!source.isFile) return Snapshot(emptyList(), null, "")
        val raw = try {
            source.readText()
        } catch (error: Throwable) {
            return Snapshot(emptyList(), null, "ball-phase.json 读不出来: ${error.message ?: error}")
        }
        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (error: Throwable) {
            return Snapshot(emptyList(), null, "ball-phase.json 不是 JSON: ${error.message ?: error}")
        }
        root?.get("turns") as? JsonArray
            ?: return Snapshot(emptyList(), null, "ball-phase.json 里没有 turns")
        return Snapshot(parseRunning(root), parseEnded(root), "")
    }

    /**
     * 纯解析: `turns` 那一串里在跑的每一场 (空表 / 读不懂都是空表, 坏的那几条跳过)
     *
     * 纯函数是为了没有设备也能量 (见 BallPhaseFileTest)
     */
    internal fun parseRunning(root: JsonObject?): List<Turn> {
        val turns = root?.get("turns") as? JsonArray ?: return emptyList()
        return turns.mapNotNull { entry ->
            val one = entry as? JsonObject ?: return@mapNotNull null
            val id = one["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val startedAt = runCatching { one["startedAt"]?.jsonPrimitive?.long }.getOrNull()
            if (id.isEmpty() || startedAt == null) null else Turn(id, startedAt)
        }
    }

    /** 纯解析: `turns` 那一串里**最近开始**的那一场 (空表 / 读不懂都是 null) */
    internal fun parseTurns(root: JsonObject?): Turn? = parseRunning(root).maxByOrNull { it.startedAt }

    /**
     * 纯解析: `last` 那一条
     *
     * **它读不懂就是 null, 而且一个字都不往外抛**: 那一条坏了不许把"哪几场在跑"一起带走 ——
     * 球上第一个字段 (正在想) 比第二个字段要紧
     */
    internal fun parseEnded(root: JsonObject?): Ended? {
        val one = root?.get("last") as? JsonObject ?: return null
        val id = one["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val at = runCatching { one["at"]?.jsonPrimitive?.long }.getOrNull()
        val kind = one["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isEmpty() || at == null || kind.isEmpty()) return null
        val why = runCatching { one["why"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        return Ended(id, at, kind, why)
    }

    /** 那一场该用哪个色 (缺省给一个, 之后每次都一样) */
    fun colorFor(context: Context, session: String): Int = palette[colorIndex(context, session)]

    /** 那一场分到的是第几号色 (排障用; 顺手落盘) */
    fun colorIndex(context: Context, session: String): Int {
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val table = Colors.parse(store.getString(KEY_COLORS, null))
        val (assigned, index) = table.assign(session, palette.size)
        if (assigned !== table) store.edit().putString(KEY_COLORS, assigned.encode()).apply()
        return index
    }

    /**
     * 盯着那个目录: 宿主一改文件就叫醒 (inotify, 见文件头)
     *
     * 盯**目录**而不是那个文件: 文件可能还不存在 (宿主一次都没写过), 而它是原子写出来的 ——
     * `rename` 在目录上留下的是 `MOVED_TO`, 直接盯文件只能拿到第一次那个 inode 的事
     */
    internal class Watch(private val onChanged: () -> Unit) {

        private var observer: FileObserver? = null

        /**
         * 开始盯这个目录
         *
         * **`startWatching()` 是 `void`, 盯没盯上问不到** —— 2026-10-15 真机上它就是死的 (球上
         * 「正在想」一直不亮, 而这里没有任何读数), 所以兜底不放在这儿: `OverlayService` 那一拍
         * 顺手看一眼文件的 mtime (见它 `tick` 里那一段)
         */
        fun start(context: Context) {
            if (observer != null) return
            val directory = directory(context)
            directory.mkdirs()
            val created = object : FileObserver(directory, MASK) {
                override fun onEvent(event: Int, name: String?) {
                    // 只认这一份 (它的临时名也算): 那个目录里以后可能还有别的东西
                    if (name != null && name != FILE_NAME && name != "$FILE_NAME.tmp") return
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

    /**
     * 会话 → 色号那一张表, 与一个"下一个发几号"的计数
     *
     * 全纯: 解析 / 编码 / 分号都不碰设备, 于是"同一个会话永远同色、第 6 个与第 1 个同色"这些事
     * 单测里就能量 (见 `BallPhaseFileTest`)
     */
    internal class Colors(val next: Int, val entries: List<Pair<String, Int>>) {

        /** 里面存成数组而不是对象: 顺序就是"先来后到", 淘汰最老的那些才说得清 */
        fun index(session: String): Int? = entries.lastOrNull { it.first == session }?.second

        /**
         * 给一个会话分号: 分过就还它原来那个, 没分过就发下一个 (轮转), 并回一张新的表
         *
         * **没分过时回的是一张新对象**, 调用方据此决定要不要落盘 (分过时回的就是 `this`)
         */
        fun assign(session: String, size: Int): Pair<Colors, Int> {
            index(session)?.let { return this to it }
            val at = next % size
            val kept = (entries + (session to at)).takeLast(KEEP)
            return Colors(next + 1, kept) to at
        }

        fun encode(): String = buildString {
            append("{\"next\":").append(next).append(",\"ids\":[")
            entries.forEachIndexed { at, (id, index) ->
                if (at > 0) append(',')
                append('[').append(JsonPrimitive(id).toString())
                append(',').append(index).append(']')
            }
            append("]}")
        }

        companion object {
            fun parse(raw: String?): Colors {
                if (raw.isNullOrBlank()) return Colors(0, emptyList())
                val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return Colors(0, emptyList())
                val next = runCatching { root["next"]?.jsonPrimitive?.int }.getOrNull() ?: 0
                val entries = runCatching { root["ids"]?.jsonArray }.getOrNull()
                    ?.mapNotNull { entry ->
                        val pair = entry as? JsonArray ?: return@mapNotNull null
                        val id = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
                        val index = runCatching { pair.getOrNull(1)?.jsonPrimitive?.int }.getOrNull()
                        if (id.isEmpty() || index == null) null else id to index
                    }
                    .orEmpty()
                return Colors(next, entries)
            }
        }
    }
}
