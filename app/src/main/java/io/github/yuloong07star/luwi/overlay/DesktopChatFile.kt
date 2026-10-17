package io.github.yuloong07star.luwi.overlay

import android.content.Context
import android.os.FileObserver
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 桌面对话组件那本账: `$DSH_HOME/lw/desktop-chat.json` (2.7.8 批次 6)
 *
 * 组件上那块远程视图 (RemoteViews) **装不了输入框**, 也读不了数据库 —— 它每次重画都是"读一份文件,
 * 拼一份 RemoteViews 交出去"。所以这一格的内容只有一个来源, 而这份文件就是它:
 *
 * ```json
 * {"v":1,"at":1759600000000,
 *  "lines":[{"who":"user","text":"今天几号","at":1759599990000},
 *           {"who":"assistant","text":"十月十七号","at":1759600001000}]}
 * ```
 *
 * 三条约定 (与宿主那一侧 `writeDesktopChat` 逐字对齐, 谁都不许单边改):
 *
 * - `who` 只有 `user` / `assistant` 两个值, 别的值一律跳过 (以后加第三种也不会把它们读坏)
 * - **只留最近 [MAX_LINES] 条**, 超了从最老的开始丢 —— 这一格是"刚说过的那几句", 不是会话记录
 * - **两边都原子写** (`tmp` + rename): 应用写 `user` 那一笔 (从输入窗发出去的时候), 宿主写
 *   `assistant` 那一笔 (一轮回答落进那块框的时候)。两个进程各自读-改-写, 所以读的一侧要容忍
 *   "这一刻没有文件 / 文件里只写了一半" —— 原子写让后一种基本不存在, 但 `parse` 仍然不抛
 *
 * 读的一侧是 [Watch] (inotify 盯目录), 与 `BallPhaseFile` 同一个形状: 宿主一写就叫醒, 组件当场重画,
 * **零轮询**
 */
internal object DesktopChatFile {

    /** 与宿主那一侧同一个约定 */
    private const val DIRECTORY = "lw"
    private const val FILE_NAME = "desktop-chat.json"

    /**
     * 手里留几条
     *
     * 6 是"够组件显示 + 够一会儿切回来还能看见上两句"的那个数, 而组件真正画得下的只有最近
     * [SHOW_LINES] 条 (2×2 那一格放不下更多气泡)
     */
    const val MAX_LINES = 6

    /** 组件那一格画几条 (剩下的靠 [MAX_LINES] 留在这里, 以后换大尺寸时直接改这一个数) */
    const val SHOW_LINES = 3

    /**
     * 一条气泡最多存多少字
     *
     * 组件上那两行最多也就显示五六十个字, 所以存储这一层先截一道: 文件小, 每次重画读得也快。
     * 截的时候带一个 `…`, 与 `BoxView` 那边"长回答截一段"是同一个口径
     */
    const val MAX_CHARS = 240

    const val WHO_USER = "user"
    const val WHO_ASSISTANT = "assistant"

    /** 一条气泡: 谁说的 / 说了什么 / 什么时候 */
    internal data class Line(val who: String, val text: String, val at: Long)

    /** 一次读的答案: 那几条气泡, 以及读不出来时的一句人话 (空串 = 一切正常) */
    internal data class Snapshot(val lines: List<Line>, val note: String) {

        /** 组件真正要画的那几条 (最近 [SHOW_LINES] 条, 仍是先来后到) */
        val shown: List<Line> get() = lines.takeLast(SHOW_LINES)
    }

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    fun directory(context: Context): File = File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY)

    fun file(context: Context): File = File(directory(context), FILE_NAME)

    /**
     * 读一次
     *
     * 文件不在 = 一条都没有 (这是最常见的状态, 不是错); 读不懂 / 不是 JSON 时 [Snapshot.note] 里是
     * 一句人话 —— 组件上那一格会把它画出来, 而不是装作"还没说过话"
     */
    fun snapshot(context: Context): Snapshot {
        val source = file(context)
        if (!source.isFile) return Snapshot(emptyList(), "")
        val raw = try {
            source.readText()
        } catch (error: Throwable) {
            return Snapshot(emptyList(), "desktop-chat.json 读不出来: ${error.message ?: error}")
        }
        return parse(raw)
    }

    /**
     * 纯解析: 一份文件的正文 → 那几条气泡
     *
     * 全纯 (不碰 Context, 不碰磁盘), 于是"只留最近几条 / 坏的那一条跳过 / 半个文件不炸"这几件事
     * 没有设备也能量 (见 DesktopChatTest)
     */
    internal fun parse(raw: String): Snapshot {
        if (raw.isBlank()) return Snapshot(emptyList(), "")
        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (error: Throwable) {
            return Snapshot(emptyList(), "desktop-chat.json 不是 JSON: ${error.message ?: error}")
        } ?: return Snapshot(emptyList(), "desktop-chat.json 的顶层不是一个对象")
        val lines = parseLines(root["lines"] as? JsonArray).takeLast(MAX_LINES)
        return Snapshot(lines, "")
    }

    /** 纯解析: `lines` 那一串 (坏的那几条跳过, 不认识 `who` 的也跳过) */
    internal fun parseLines(array: JsonArray?): List<Line> = array.orEmpty().mapNotNull { entry ->
        val one = entry as? JsonObject ?: return@mapNotNull null
        val who = (one["who"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (who != WHO_USER && who != WHO_ASSISTANT) return@mapNotNull null
        val text = clip((one["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        if (text.isEmpty()) return@mapNotNull null
        val at = (one["at"] as? JsonPrimitive)?.longOrNull ?: 0L
        Line(who, text, at)
    }

    /**
     * 纯函数: 把一条气泡接在账尾, 超了从最老的开始丢
     *
     * **一模一样的两条连着写只留一条**: 回答那条链有可能把同一条推两遍 (重连 / 重投), 而组件上看见两条
     * 一字不差的气泡只会让人以为它在自言自语
     */
    internal fun withLine(lines: List<Line>, who: String, text: String, at: Long): List<Line> {
        val line = clip(text)
        if (line.isEmpty()) return lines
        if (lines.lastOrNull()?.let { it.who == who && it.text == line } == true) return lines
        return (lines + Line(who, line, at)).takeLast(MAX_LINES)
    }

    /** 纯函数: 截一道 (带 `…`), 与 `BoxView` 那边"长回答截一段"同一个口径 */
    internal fun clip(text: String): String {
        val line = text.trim().replace(Regex("\\s*\\n+\\s*"), " ")
        return if (line.length <= MAX_CHARS) line else line.take(MAX_CHARS - 1) + "…"
    }

    /** 纯函数: 那几条气泡 → 一份文件的正文 (与应用 / 宿主两侧的编码逐字一致) */
    internal fun encode(lines: List<Line>, at: Long): String {
        val body = lines.joinToString(",") { line ->
            buildString {
                append("{\"who\":").append(JsonPrimitive(line.who))
                append(",\"text\":").append(JsonPrimitive(line.text))
                append(",\"at\":").append(line.at).append('}')
            }
        }
        return "{\"v\":1,\"at\":$at,\"lines\":[$body]}\n"
    }

    /**
     * 写一条 (应用这一侧只写 `user` 那一笔)
     *
     * 读-改-写, 而写是**原子**的: 先写 `desktop-chat.json.tmp` 再 rename 过去, 于是读的一侧永远看不到
     * 半份文件 (与 `BallPhaseFile` / `balance.json` 同一条路)
     *
     * @return 真的动过文件没有 (与上一条一模一样时不动)
     */
    fun append(context: Context, who: String, text: String): Boolean {
        val before = snapshot(context).lines
        val after = withLine(before, who, text, System.currentTimeMillis())
        if (after == before) return false
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val target = file(context)
        val temporary = File(directory, "$FILE_NAME.tmp")
        return try {
            temporary.writeText(encode(after, System.currentTimeMillis()))
            // rename 在同一目录里是原子的 (POSIX), 而这里两边都在 filesDir 下, 不是 /sdcard 那个 FUSE
            temporary.renameTo(target)
        } catch (error: Throwable) {
            runCatching { temporary.delete() }
            false
        }
    }

    /**
     * 清空那本账 (主人 2026-10-17: 组件上加一颗「清空」, 一键清空气泡对话)
     *
     * 只动气泡——**入口、投递链、宿主那一侧的账一个字节都不动** (主人原话: "不动入口")。
     * 写的是"空 lines", 不是删文件: 删了之后下一次读又是"没有文件"那一条路, 两种状态在读数上分不开
     */
    fun clear(context: Context): Boolean {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val temporary = File(directory, "$FILE_NAME.tmp")
        return try {
            temporary.writeText(encode(emptyList(), System.currentTimeMillis()))
            temporary.renameTo(file(context))
        } catch (error: Throwable) {
            runCatching { temporary.delete() }
            false
        }
    }
    /** 那条链上真的写了一笔之后要有人重画组件 (调用方拿它判断要不要 [Watch]) */
    fun lines(context: Context): List<Line> = snapshot(context).lines

    /**
     * 盯着那个目录: 宿主一写就重画组件 (inotify, 见文件头)
     *
     * 盯**目录**而不是那个文件: 文件可能还不存在, 而原子写走的是 `rename` —— 只盯文件的话第一次
     * 改名之后那个 inode 就断了 (与 `BallPhaseFile.Watch` 同一条理由)
     *
     * **一份进程序只盯一次**: [ensure] 是幂等的, 谁先叫谁建, 之后都拿到同一份
     */
    internal class Watch(private val onChanged: () -> Unit) {

        private var observer: FileObserver? = null

        fun start(context: Context) {
            if (observer != null) return
            val directory = directory(context)
            directory.mkdirs()
            val created = object : FileObserver(directory, MASK) {
                override fun onEvent(event: Int, name: String?) {
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
            /** 原子写走的是 `MOVED_TO` (rename), `CLOSE_WRITE` 与 `CREATE` 兜住"直接写"与"刚建出来" */
            private const val MASK =
                FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        }
    }

    private var watcher: Watch? = null

    /**
     * 让"文件一变就重画组件"这件事挂上 (幂等)
     *
     * 两个地方叫它: 组件每一趟组装 (那一格放在桌面上就一定有人叫) 与浮标服务起来那一趟 (从输入窗发出去
     * 的话要落账, 而落完之后要有人重画)
     */
    fun ensure(context: Context, onChanged: () -> Unit) {
        if (watcher != null) return
        val created = Watch(onChanged)
        runCatching { created.start(context) }
            .onFailure { watcher = null; return }
        watcher = created
    }

    /** 服务走了 / 测试收尾: 把 inotify 描述符放掉 (下次 [ensure] 再盯回去) */
    fun release() {
        watcher?.stop()
        watcher = null
    }
}

