package io.github.yuloong07star.luwi.tool

import android.content.Context
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 探索那张任务卡 (2.7.8 批次 8)
 *
 * **只有一张**, 落 `$DSH_HOME/lw/explore-card.md`: 它是"下一场探索做什么"的一段话 —— 宿主在收尾时
 * 按 `lw_explore op=finish` 的 `nextTask` 改写它, 主人也可以在设置页手改, 排期到点就把它当开场正文投
 *
 * 与快捷指令 (`LwQuick`) 的区别: 快捷指令是"点一下交给模型去跑"的工作流, 这一张是**排期每天用的那一段
 * 任务**; 它不参与 `/` 菜单、不列在快捷指令那一段, 也不让 `lw_quick` 写 (那是另一条链)
 *
 * **种子只发一次**: 随包那份初始卡在首启/升级后第一次抄进来 (已存在就一个字节不动, agent 与主人都可能
 * 改过); 记号 `.lw-seed-explore` 落在 `$DSH_HOME` 根上, 与 [io.github.yuloong07star.luwi.host.LwSeed]
 * 的 `.lw-seed` 分开 —— 老机器上那个记号早就有了, 新卡等不到它
 */
internal object ExploreTask {

    private const val TAG = "LwExplore"

    /** 目录与文件名: 宿主那一侧写卡时用的是同一个约定 */
    const val DIRECTORY = "lw"
    const val FILE = "explore-card.md"

    /** 种子记号 (放 `$DSH_HOME` 根上) 与卡正文上限 */
    const val STAMP = ".lw-seed-explore"
    const val MAX_CHARS = 8000

    /** 卡是空的时候, 排期到点投什么 (与宿主 `exploreStartLine` 的缺省同一句) */
    const val FALLBACK_TEXT = "自由探索这台手机"

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    /** `$DSH_HOME/lw/explore-card.md` */
    fun file(context: Context): File = File(File(context.filesDir, DshHost.HOME_DIR), "$DIRECTORY/$FILE")

    /** 首启/升级后第一次把随包的初始卡抄进来 (已经有卡 / 已经发过就什么都不做) */
    fun ensure(context: Context) {
        val card = file(context)
        if (card.isFile) return
        val home = File(context.filesDir, DshHost.HOME_DIR)
        if (File(home, STAMP).isFile) return
        card.parentFile?.mkdirs()
        val copied = runCatching {
            context.assets.open(FILE).use { input ->
                card.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
        if (copied) {
            runCatching { File(home, STAMP).writeText("explore-card\n") }
                .onFailure { android.util.Log.w(TAG, "the explore seed stamp could not be written", it) }
        }
    }

    /** 现在这张卡 (trim 过; 读不到就是空串) */
    fun read(context: Context): String = runCatching {
        file(context).takeIf { it.isFile }?.readText().orEmpty()
    }.getOrDefault("").trim().take(MAX_CHARS)

    /** 设置页那一行: 第一行不是标题的字 (与快捷指令同一套摘要口径) */
    fun summary(context: Context): String {
        val card = file(context)
        if (!card.isFile) return ""
        return LwQuick.summaryOf(card)
    }

    /**
     * 写卡 (主人手改 / 以后别处调): 原子写, 空正文也收 (空 = 到点自由探索)
     *
     * 单写者加锁: 设置页与任何后台调用都在同一个进程里, 串起来就不会出现两个 `.tmp` 抢 rename
     */
    fun write(context: Context, text: String): Boolean = synchronized(lock) {
        val target = file(context)
        return runCatching {
            target.parentFile?.mkdirs()
            val body = text.trim().take(MAX_CHARS)
            val content = if (body.isEmpty()) "" else "$body\n"
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** 现在那一场探索 (只读宿主写的 `explore.json`, 不碰它) */
    data class Running(val session: String, val deadline: Long, val steps: Int, val stepLimit: Int)

    fun running(context: Context, now: Long = System.currentTimeMillis()): Running? {
        val state = File(file(context).parentFile, "explore.json")
        val text = runCatching { state.takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        val root = runCatching {
            json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return null
        return runningOf(root, now)
    }

    /**
     * `explore.json` 里那本 run 账 -> "还在跑的那一场"
     *
     * 三种情况都算没在跑: 没有 `run`、`over === true` (到点了等收尾)、`deadline` 已经过去
     */
    fun runningOf(state: JsonObject, now: Long): Running? {
        val run = state["run"] as? JsonObject ?: return null
        if (run["over"]?.jsonPrimitive?.booleanOrNull == true) return null
        val session = run["session"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val deadline = run["deadline"]?.jsonPrimitive?.longOrNull ?: 0L
        if (session.isBlank() || deadline <= now) return null
        return Running(
            session = session,
            deadline = deadline,
            steps = run["steps"]?.jsonPrimitive?.intOrNull ?: 0,
            stepLimit = run["stepLimit"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}
