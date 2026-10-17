package io.github.yuloong07star.luwi.tool

import android.content.Context
import io.github.yuloong07star.luwi.automation.AutomationEngine
import io.github.yuloong07star.luwi.automation.AutomationRule
import io.github.yuloong07star.luwi.automation.AutomationStore
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动指令: `$DSH_HOME/automations/` 那几条规则的脸
 *
 * 与快捷指令同一个形状 (app 进程里做 → 桥 → 插件一条工具), 差别在两处:
 *
 * - 这里的正文是**一份 JSON**, 所以 `write` 先校验再落盘 —— 写坏了的规则在界面上就是"它从来没响过",
 *   而那种错没人查得出来
 * - 多两条只读的: `status` (九个监测器此刻的可用性 + 为什么现在不可能响) 与 `history` (最近几次判定
 *   与原因)。主人问"它怎么没响"时, 答案在这两条里
 *
 * 六条 op 里 `delete` **只在桥上有** (设置页那个删除按钮用它), 给模型的是 [MODEL_OPERATIONS] 那五条
 */
internal object LwAutomation {

    /** 目录名: 与设置页、创建提示词、工具说明里说的都是它 */
    const val DIRECTORY = AutomationStore.DIRECTORY

    /**
     * op 名单, **插件那边念给模型的与这一份不许漂开**
     *
     * `tools/check-automations.mjs` 拿这两行去比插件那一份说明
     */
    val OPERATIONS = listOf("list", "read", "write", "delete", "status", "history")

    /** 模型那一侧只给这五条: 删掉一条规则由人在设置页点 */
    val MODEL_OPERATIONS = listOf("list", "read", "write", "status", "history")

    /** 一次读回多少字节: 一条规则是一个小 JSON, 这么大已经是"写得太多"那一档 */
    private const val MAX_READ_BYTES = 64 * 1024

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "list" -> list(context)
        "read" -> read(context, request)
        "write" -> write(context, request)
        "delete" -> delete(context, request)
        "status" -> status(context)
        "history" -> history(context, request)
        else -> throw IllegalArgumentException(
            "op has to be ${OPERATIONS.joinToString(", ")}, not \"$op\"",
        )
    }

    /** 目录在哪 (设置页与创建提示词都用它) */
    fun directory(context: Context) = AutomationStore.directory(context)

    /** 现在有哪些规则, 读不出来的也在里面 (界面要说得出"这一条坏了") */
    fun entries(context: Context): List<AutomationStore.Entry> = AutomationStore.entries(context)

    /** 设置页那个开关 */
    fun setEnabled(context: Context, name: String, enabled: Boolean): Boolean {
        val ok = AutomationStore.setEnabled(context, name, enabled)
        if (ok) AutomationEngine.invalidate()
        return ok
    }

    /**
     * 设置页那条**直接改冷却** (2026-10-09 主人加的: 由用户决定冷却闸多少时间)
     *
     * 与 [setEnabled] 同一个形状, 而它改的是 `cooldownMinutes` —— 改完引擎要重读, 否则正在跑的那一份
     * 规则还是旧数 (冷却那笔账就在 [AutomationEngine.act] 里读它)
     */
    fun setCooldown(context: Context, name: String, minutes: Int): Boolean {
        val ok = AutomationStore.setCooldown(context, name, minutes)
        if (ok) AutomationEngine.invalidate()
        return ok
    }

    /** 设置页那个删除按钮 */
    fun delete(context: Context, name: String): Boolean {
        val ok = AutomationStore.delete(context, name)
        if (ok) AutomationEngine.invalidate()
        return ok
    }

    /** 设置页读的那一份设置 */
    fun settings(context: Context): AutomationStore.Settings = AutomationStore.settings(context)

    fun saveSettings(context: Context, settings: AutomationStore.Settings) =
        AutomationEngine.saveSettings(context, settings)

    /** 最近几次判定 (设置页那 10 条与 `op=history` 同一份) */
    fun history(context: Context, limit: Int = 10, rule: String? = null): List<AutomationStore.Fired> =
        AutomationStore.history(context, limit, rule)

    /** 一行总账 (设置页最上面那一行) */
    fun overview(context: Context): String = AutomationEngine.overview(context)

    /** 设置页那个「立刻跑一次」 */
    fun fireNow(context: Context, name: String): String = AutomationEngine.fireNow(context, name)

    /** 九个监测器此刻的状态 (设置页逐行显示) */
    fun monitorStates(context: Context): List<Pair<String, String>> =
        AutomationEngine.monitorStates(context)

    /** 今天主动说了几次 (设置页「探索」段那一行读数) */
    fun saidToday(context: Context): Int = AutomationEngine.saidToday(context)

    /* ── 六条 op ────────────────────────────────────────────────────────── */

    private fun list(context: Context): JsonObject {
        val entries = entries(context)
        if (entries.isEmpty()) {
            return text(
                "there are no automatic commands yet (${directory(context)}). Write one with" +
                    " op=write: a name and a JSON object shaped like" +
                    " {\"name\",\"enabled\",\"when\":{\"kind\",...},\"then\":{\"kind\":\"remind\"|\"task\"," +
                    "\"text\"},\"cooldownMinutes\",\"dailyLimit\",\"quietHours\"}; the kinds are" +
                    " ${AutomationRule.WHEN_KINDS.joinToString(", ")} (all/any take 2..5 conds and do not" +
                    " nest)",
            )
        }
        val rows = entries.joinToString("\n") { entry ->
            val state = when {
                entry.problem != null -> "BROKEN (${entry.problem})"
                entry.rule?.enabled == true -> "on"
                else -> "off"
            }
            val what = entry.rule?.summary() ?: "this file could not be read"
            val last = AutomationEngine.lastFiredAt(entry.name)?.let {
                ", last fired " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it))
            }.orEmpty()
            "  ${entry.name}  [$state]  $what$last"
        }
        return text("${entries.size} automatic command(s) in ${directory(context)}:\n$rows")
    }

    private fun read(context: Context, request: JsonObject): JsonObject {
        val name = normalizeName(request.string("name"))
        val file = AutomationStore.file(context, name)
        if (!file.isFile) {
            return text(
                "there is no automatic command called \"$name\". What is there: " +
                    (AutomationStore.names(context).joinToString(", ").ifEmpty { "nothing yet" }),
            )
        }
        val raw = file.readText()
        val head = if (raw.length > MAX_READ_BYTES) raw.take(MAX_READ_BYTES) else raw
        val summary = runCatching { AutomationRule.parse(name, raw).summary() }
            .getOrElse { "this file cannot be read: ${it.message}" }
        val suffix = if (raw.length > MAX_READ_BYTES) "\n\n(the file is longer than $MAX_READ_BYTES bytes, so this is the head of it)" else ""
        return text("automatic command \"$name\" (${file.absolutePath}): $summary\n\n$head$suffix")
    }

    private fun write(context: Context, request: JsonObject): JsonObject {
        val name = normalizeName(request.string("name"))
        val json = request.string("json")
        val existed = AutomationStore.file(context, name).isFile
        val (file, warnings) = try {
            AutomationStore.write(context, name, json)
        } catch (problem: Throwable) {
            throw IllegalArgumentException("this rule was not accepted: ${problem.message}")
        }
        AutomationEngine.invalidate()
        val said = buildString {
            append(if (existed) "updated" else "created")
            append(" automatic command \"$name\" (${bytes(file.length())}, ${file.absolutePath})")
            append("; it shows up in the app's Settings -> 自动指令 right away")
            if (warnings.isNotEmpty()) {
                append("\nnote: ")
                append(warnings.joinToString("; "))
            }
        }
        return text(said)
    }

    private fun delete(context: Context, request: JsonObject): JsonObject {
        val name = normalizeName(request.string("name"))
        val file = AutomationStore.file(context, name)
        if (!file.isFile) return text("there is no automatic command called \"$name\", so nothing was deleted")
        val removed = AutomationStore.delete(context, name)
        if (removed) AutomationEngine.invalidate()
        return text(
            if (removed) "deleted automatic command \"$name\"" else "could not delete \"$name\" (${file.absolutePath})",
        )
    }

    /** 九个监测器此刻能不能用, 以及此刻为什么可能不响 */
    private fun status(context: Context): JsonObject {
        val rules = AutomationEngine.rules()
        val settings = AutomationEngine.settings()
        val rows = mutableListOf<Pair<String, String>>()
        rows += "rules" to "${rules.size} (${rules.count { it.enabled }} on)"
        rows += "directory" to directory(context).absolutePath
        rows += "quiet hours" to when {
            settings == null -> "unknown"
            !settings.quietEnabled -> "off"
            else -> settings.quietWindow + (if (AutomationEngine.quietNow(context)) " (quiet now)" else "")
        }
        rows += "acting" to when {
            settings == null -> "unknown"
            settings.allowActing -> "allowed: a task rule may act on its own"
            else -> "off: a 'task' rule will be held back"
        }
        rows += "proactive today" to "${AutomationEngine.saidToday(context)} / " +
            "${settings?.saidLimit ?: AutomationRule.SAID_LIMIT_DEFAULT}" +
            " (task rules only; a manual run does not count)"
        rows += "power save" to if (AutomationEngine.pausedNow()) {
            "on: the weather and place watches are not registered"
        } else {
            "off"
        }
        rows += "weather now" to (AutomationEngine.lastWeather().entries.joinToString("; ") { "${it.key}: ${it.value}" }
            .ifEmpty { "not asked yet" })
        val (firstUnlock, lastUnlock, lastInteraction) = AutomationEngine.interactionReadings()
        rows += "unlock" to ("today's first " + stamp(firstUnlock) + ", last " + stamp(lastUnlock))
        rows += "activity" to ("last " + stamp(lastInteraction))
        val (ticks, lastTick) = AutomationEngine.tickReadings()
        rows += "combo tick" to "$ticks run(s), last " + stamp(lastTick)
        val monitors = AutomationEngine.monitorStates(context)
        val text = buildString {
            append(table(rows))
            append("\nthe nine watches:\n")
            append(monitors.joinToString("\n") { "  ${it.first}: ${it.second}" })
            if (rules.isNotEmpty()) {
                append("\nthe rules:\n")
                append(
                    rules.joinToString("\n") { rule ->
                        val last = AutomationEngine.lastFiredAt(rule.name)
                        val fired = AutomationEngine.firedTodayCount(rule.name)
                        "  ${rule.name}  ${if (rule.enabled) "on" else "off"}  ${rule.summary()}" +
                            "  (today $fired/${rule.dailyLimit}" +
                            (last?.let { ", last " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it)) } ?: "") +
                            ")"
                    },
                )
                // 组合规则缺这一行, "为什么没响"只剩一句"没成立", 看不出是哪个子条件一直没新鲜过
                val combos = rules.filter { it.whenKind in AutomationRule.COMBO_KINDS }
                if (combos.isNotEmpty()) {
                    append("\nthe sub-conditions of the combinations (last time each held):\n")
                    append(
                        combos.joinToString("\n") { rule ->
                            val subs = AutomationEngine.subFreshness(rule.name)
                            val said = subs.joinToString("; ") { one ->
                                "#${one.index + 1} ${one.kind} " + stamp(one.at)
                            }
                            "  ${rule.name}: $said"
                        },
                    )
                }
            }
        }
        return text(text)
    }

    /** 一个时刻读数: 0 就是"还没有" */
    private fun stamp(at: Long): String =
        if (at <= 0L) {
            "never"
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
        }

    private fun history(context: Context, request: JsonObject): JsonObject {
        val limit = request.int("limit", 20)
        val rule = request.stringOrNull("rule")
        val fired = history(context, limit, rule)
        if (fired.isEmpty()) {
            return text(
                "nothing has been decided yet (no line in ${AutomationStore.HISTORY_FILE})." +
                    " If a rule should have fired by now, read op=status: it says which watch is missing" +
                    " or what is holding the rule back",
            )
        }
        return text(fired.joinToString("\n") { "  " + AutomationStore.line(it) })
    }

    /** 名字规范化: 与快捷指令**同一份判据** */
    private fun normalizeName(name: String): String =
        normalizeArtifactName(name, "an automatic command", ".json")
}
