package io.github.yuloong07star.luwi.tool

import android.content.Context
import io.github.yuloong07star.luwi.host.DshHost
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快捷指令: `$DSH_HOME/quick-commands/<名字>.md`, 一个文件一条
 *
 * **为什么落在 `$DSH_HOME` 而不是工作区**: 会话自己的 `read` / `write` 被 dsh 的 fs 沙箱关在工作区里,
 * 而 `lw_files` 明确只认工作区里的路径 —— 两边都够不到 dsh 自己的家。快捷指令是**应用与模型共用**的
 * 一份东西 (设置页列它、模型读它、模型按创建提示词写它), 所以它必须有一个两个进程都拿得到的家, 而
 * `$DSH_HOME` 正是那一处 (语音投递队列用的是同一个理由, 见 `voice/VoiceInbox.kt`)
 *
 * **正文不写 frontmatter**: 一份快捷指令其实就是一段工作流, 与技能 (`SKILL.md`) 不同 —— 技能要给
 * dsh 解析 name / description, 而这一份没有谁去解析, 只写"先加载哪个技能、再做什么"就行
 *
 * 四条 op 里 `delete` **只在桥上有**: 设置页那个删除按钮用它, 而工具那一侧不暴露 (主人 2026-10-08
 * 选的口径: 删掉一条快捷指令这件事由人在界面上做)
 */
internal object LwQuick {

    /** 目录名: 与设置页、创建提示词里说的都是它 */
    const val DIRECTORY = "quick-commands"

    /**
     * op 名单, **插件那边念给模型的也是这四个** (`host-plugin/index.mjs` 的 `lw_quick`)
     *
     * 两份实现不许漂开, 而"漂开"的样子是"工具说明里有一个 op 其实没实现"; `tools/check-quick-commands.mjs`
     * 拿这一行去比插件那一行
     */
    val OPERATIONS = listOf("list", "read", "write", "delete")

    /** 模型那一侧只给这三个: 删除由人在设置页点 */
    val MODEL_OPERATIONS = listOf("list", "read", "write")

    /** 一次最多列多少条 */
    private const val MAX_LISTED = 200

    /** 一次读回多少字节: 快捷指令是给人看的工作流, 64 KiB 已经是"写得太多"那一档 */
    private const val MAX_READ_BYTES = 64 * 1024

    /** 摘要取正文第一行, 最多这么长 */
    private const val MAX_SUMMARY = 40

    /** 找摘要时最多看正文前多少行 (摘要在第一行附近, 不必把整份读进内存) */
    private const val MAX_SUMMARY_LINES = 20

    /** 一条快捷指令: 设置页与 `op=list` 都读这一份 */
    data class Entry(val name: String, val summary: String, val bytes: Long, val modified: Long)

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "list" -> list(context)
        "read" -> read(context, request)
        "write" -> write(context, request)
        "delete" -> delete(context, request)
        else -> throw IllegalArgumentException(
            "op has to be ${OPERATIONS.joinToString(", ")}, not \"$op\"",
        )
    }

    /** `$DSH_HOME/quick-commands` */
    fun directory(context: Context): File =
        File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY)

    /**
     * 现在有哪些快捷指令, 按名字排
     *
     * 设置页那一段与 `op=list` 用的是同一份 —— 两处各写一遍就会出现"界面上看得见而模型读不到"
     */
    fun entries(context: Context): List<Entry> {
        val directory = directory(context)
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".md") }
            ?: return emptyList()
        return files
            .sortedBy { it.name }
            .take(MAX_LISTED)
            .map { file ->
                Entry(
                    name = nameOf(file),
                    summary = summaryOf(file),
                    bytes = file.length(),
                    modified = file.lastModified(),
                )
            }
    }

    /** 一条按名字找 (设置页点击时也要它): 找不到就是 null */
    fun command(context: Context, name: String): File? {
        val normalized = normalize(name)
        return File(directory(context), "$normalized.md").takeIf { it.isFile }
    }

    /**
     * 名字规范化: **只在这一处** (实现搬到 [normalizeArtifactName], 与自动指令共用同一份判据)
     *
     * 两处各写一遍的代价不是重复, 而是"一条能建出来却读不回来" —— 名字那几条规则长一个样, 所以
     * 只留一份实现, 这里只是把"这一份是谁"说清楚
     */
    fun normalize(name: String): String = normalizeArtifactName(name, "a quick command", ".md")

    /** 文件名去掉 `.md` 就是它的名字 */
    private fun nameOf(file: File): String = file.name.removeSuffix(".md")

    /**
     * 摘要 = 正文里第一行"不是标题"的字
     *
     * 顺序是先挑非井号行、再退回标题行: 样例的第一行就是 `# 名字`, 直接拿它当摘要会与设置页那一行的
     * 标题一模一样 ("制定旅游计划 / 制定旅游计划"), 看着像坏了 —— 而第二行那句"这份工作流做什么"才是
     * 想给人看的
     *
     * 2.7.8 批次 8 起也由 [ExploreTask.summary] 复用: 探索卡是同一形状的 Markdown, 两处各写一份
     * 摘要算法迟早会漂开
     */
    internal fun summaryOf(file: File): String = runCatching {
        val lines = file.useLines { it.take(MAX_SUMMARY_LINES).toList() }.map { it.trim() }
        val first = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: lines.firstOrNull { it.isNotEmpty() }?.trimStart('#')?.trim()
            ?: ""
        if (first.length > MAX_SUMMARY) first.take(MAX_SUMMARY) + "…" else first
    }.getOrDefault("")

    private fun list(context: Context): JsonObject {
        val entries = entries(context)
        val directory = directory(context)
        if (entries.isEmpty()) {
            return text(
                "there are no quick commands yet ($directory). Write one with op=write: a name and" +
                    " a body that says which skill to load first and what to do, one step per line",
            )
        }
        val rows = entries.joinToString("\n") { entry ->
            val when_ = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(entry.modified))
            "  ${entry.name}  (${entry.summary.ifEmpty { "no summary line" }}, ${bytes(entry.bytes)}, $when_)"
        }
        return text("${entries.size} quick command(s) in $directory:\n$rows")
    }

    private fun read(context: Context, request: JsonObject): JsonObject {
        val name = normalize(request.string("name"))
        val file = command(context, name)
            ?: return text(
                "there is no quick command called \"$name\". What is there: " +
                    (entries(context).joinToString(", ") { it.name }.ifEmpty { "nothing yet" }),
            )
        if (file.length() > MAX_READ_BYTES) {
            val head = file.readText().take(MAX_READ_BYTES)
            return text(
                "quick command \"$name\" is ${bytes(file.length())}, which is longer than the" +
                    " $MAX_READ_BYTES byte limit, so this is the first part of it:\n\n$head",
            )
        }
        return text("quick command \"$name\" (${file.absolutePath}):\n\n" + file.readText())
    }

    private fun write(context: Context, request: JsonObject): JsonObject {
        val name = normalize(request.string("name"))
        val body = request.string("text")
        val file = File(directory(context), "$name.md")
        val existed = file.isFile
        file.parentFile?.mkdirs()
        file.writeText(if (body.endsWith("\n")) body else "$body\n")
        return text(
            (if (existed) "updated" else "created") + " quick command \"$name\"" +
                " (${bytes(file.length())}, ${file.absolutePath}); it shows up in the app's" +
                " Settings -> Quick commands right away",
        )
    }

    private fun delete(context: Context, request: JsonObject): JsonObject {
        val name = normalize(request.string("name"))
        val file = File(directory(context), "$name.md")
        if (!file.isFile) return text("there is no quick command called \"$name\", so nothing was deleted")
        val removed = file.delete()
        return text(
            if (removed) "deleted quick command \"$name\"" else "could not delete \"$name\" (${file.absolutePath})",
        )
    }
}
