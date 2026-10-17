package io.github.yuloong07star.luwi.automation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 一条自动指令: 形状、校验与"说人话"的摘要
 *
 * 规则是**模型写、应用读**的一份 JSON, 所以这一层最要紧的两件事是"写得坏也读得出来"与"坏在哪儿
 * 说得清": [validate] 把毛病收成一串短句, 一次全还给写的人, 不许静默地当它不存在 (一条读不出来的
 * 规则在界面上就是"它从来没响过", 那是这一批最坏的失败样子)
 *
 * 定义键只有七个 (`name` / `enabled` / `when` / `then` / `cooldownMinutes` / `dailyLimit` /
 * `quietHours`), 运行账 (上次什么时候响的、今天响了几次) **不在这里** —— 那些从 `history.jsonl`
 * 现算, 于是这份 JSON 只有一个写者一种职责
 */
internal object AutomationRule {

    /**
     * 八条**叶子条件** (2.7.8 批次 9 加了 `unlock` / `activity`)
     *
     * 它们是"能算出来此刻真不真"的那一类, 也是 `all` / `any` 里唯一允许出现的子条件 (组合**不许嵌套**,
     * 见 [validateWhen])。`once` 不在这张表里: 它是一次性的绝对时刻, 自己被 [Rule.nextFireAt] 排进闹钟
     * 并在响过之后关掉, 塞进组合里没有可解释的语义
     */
    val LEAF_KINDS = listOf(
        "notice", "foreground", "light", "time", "place", "weather", "unlock", "activity",
    )

    /**
     * 九条"监测源": 八条叶子加上 `once`
     *
     * 这是设置页那张监视器名单, 也是 `settings.json` 里 `monitors` 那张开关表的键。`all` / `any` 不在
     * 这里: 组合自己没有监测源, 它由子条件的开关管 (子条件关掉, 整个组合就不评估)
     */
    val WATCH_KINDS = LEAF_KINDS + "once"

    /** 两种组合: 都成立 / 任一成立 */
    val COMBO_KINDS = listOf("all", "any")

    /**
     * 全部认得的 kind (叶子 + `once` + 两种组合)
     *
     * 这个名单是**两份实现的同一份真值** (`host-plugin/index.mjs` 里 `lw_automation` 的说明也念
     * 这十一个词), 漂开的样子是"说明里有一个 kind 其实没人认"; `tools/check-automations.mjs` 拿这一行
     * 去比插件那一份
     *
     * `once` = 只响一次: 参数 `at` 是**带时区的绝对时刻** (ISO 8601, 例如 `2026-10-18T21:30:00+08:00`),
     * 与 `time` 共用同一个闹钟, 响过 (或被拦 / 错过) 就把 `enabled` 关掉 —— 见 [Rule.nextFireAt] 与
     * `AutomationEngine.act`
     */
    val WHEN_KINDS = WATCH_KINDS + COMBO_KINDS

    /** 一个组合收几个子条件 */
    const val COMBO_MIN_CONDS = 2
    const val COMBO_MAX_CONDS = 5

    /** `within` 那条覆盖的边界 (秒): 5 秒到 24 小时 */
    const val MIN_WINDOW_SECONDS = 5
    const val MAX_WINDOW_SECONDS = 86_400

    /**
     * 新鲜窗口的缺省 (秒): 子条件"最近一次为真"落在窗口内就算此刻成立 (D21)
     *
     * `time` 这一档 1800 秒不是新发明的数 —— 它是既有 `LATE_WINDOW_MINUTES` 那条迟到闸的显式化
     * (那条闸只让"晚了一点"的闹钟算命中; 组合里它变成"这个时刻最近半小时内都还算成立"), 于是
     * "23:45 之后半小时内切到微信"这种话写得出来
     */
    const val TIME_WINDOW_SECONDS = 1800

    /** 瞬态三条 (来了就过): 解锁 / 通知 / 前台应用 */
    const val EVENT_WINDOW_SECONDS = 120

    /** 状态类 (每轮都能重算): 光感 / 地点 / 天气 / 活动 */
    const val STATE_WINDOW_SECONDS = 300

    /** `activity` 那条缺省看最近多少分钟 (主人可以写 `withinMinutes` 覆盖) */
    const val ACTIVITY_DEFAULT_MINUTES = 10
    const val ACTIVITY_MAX_MINUTES = 1440

    /** 一条子条件的新鲜窗口 (秒): 先看它自己写的 `within`, 否则按 kind 取缺省 */
    fun windowSeconds(kind: String, params: JsonObject): Int {
        val written = params["within"]?.jsonPrimitive?.intOrNull
        if (written != null) return written.coerceIn(MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS)
        return when (kind) {
            "time" -> TIME_WINDOW_SECONDS
            "foreground", "notice", "unlock" -> EVENT_WINDOW_SECONDS
            else -> STATE_WINDOW_SECONDS
        }
    }

    /**
     * 组合的真值表: **纯函数, 单测钉着** (`AutomationCombineTest`)
     *
     * 只有两种组合认得出来, 别的 kind 一律假 —— 调用方 (引擎) 只会在自己已经确认过 kind 的时候问它,
     * 那个兜底是给"盘上的规则被手改成了第三种组合"这一档留的, 而那种规则在 [validateWhen] 就进不来
     */
    fun combine(kind: String, holds: List<Boolean>): Boolean = when (kind) {
        "all" -> holds.isNotEmpty() && holds.all { it }
        "any" -> holds.any { it }
        else -> false
    }

    /** 两种动作: 报一声, 或者真的去干活 */
    val THEN_KINDS = listOf("remind", "task")

    const val DEFAULT_COOLDOWN_MINUTES = 30
    const val DEFAULT_DAILY_LIMIT = 5
    const val DEFAULT_RADIUS_METERS = 300

    /**
     * 冷却与上限的边界: 越界一律拒
     *
     * 冷却那一头的**下限是 0**: 主人 2026-10-09 要的是"由用户决定冷却闸多少时间", 而"不要冷却"
     * (0 分钟) 是其中一个正当选择 —— 引擎读 `last + 0 > now` 正好就是每次都放行, 所以这里不再替人挡
     */
    const val MAX_COOLDOWN_MINUTES = 1440
    const val MAX_DAILY_LIMIT = 200
    const val MIN_RADIUS_METERS = 50
    const val MAX_RADIUS_METERS = 5000

    /**
     * 「每天最多主动说几次」(D17, 2.7.8 批次 8)
     *
     * 数的是 `then.kind = "task"` 的投递 (打开一场会话说起来的那一类), 手动「立刻跑一次」不算;
     * 那本账从 `history.jsonl` 现算 (见 `AutomationStore.saidToday`), 上限存在
     * `automations/settings.json` 的 `saidLimit`
     */
    const val SAID_LIMIT_DEFAULT = 3
    const val SAID_LIMIT_MIN = 1
    const val SAID_LIMIT_MAX = 10

    /** 把上限收进 1..10 (设置页那颗下拉与读文件都走它) */
    fun coerceSaidLimit(value: Int): Int = value.coerceIn(SAID_LIMIT_MIN, SAID_LIMIT_MAX)

    /**
     * 设置页给主人挑的那几档冷却 (分钟)
     *
     * 0 = 不冷却, 30 是缺省, 1440 = 一天最多一次。**这张表只是给人挑的档位**, 真正落盘的仍然是
     * `cooldownMinutes` 这个数 —— 想写 45 这种档位外的数, 走"改一改"那条路让模型写, 校验照样认
     */
    val COOLDOWN_CHOICES = listOf(0, 5, 15, 30, 60, 120, 360, 1440)

    /** 把一个冷却数收进合法范围 (设置页那条直改与模型那条 write 用的是同一个边界) */
    fun coerceCooldown(minutes: Int): Int = minutes.coerceIn(0, MAX_COOLDOWN_MINUTES)

    /**
     * 把一份规则 JSON 里的 `cooldownMinutes` 换成 [minutes], **别的键一个都不动**
     *
     * 设置页那条"直接改冷却"用它: 那条路不经过模型, 所以不能重新拼一份规则 —— 只替换这一个键,
     * 写回去之后再走 [AutomationStore.write] 那次校验。纯函数, 判据在 `AutomationCooldownTest`
     */
    fun withCooldown(root: JsonObject, minutes: Int): JsonObject = buildJsonObject {
        root.forEach { (key, value) -> if (key != "cooldownMinutes") put(key, value) }
        put("cooldownMinutes", coerceCooldown(minutes))
    }

    /** 光感那条最多允许"持续多久" (秒) */
    const val MAX_LIGHT_SECONDS = 600

    private val KNOWN_KEYS = setOf(
        "name", "enabled", "when", "then", "cooldownMinutes", "dailyLimit", "quietHours",
    )

    /**
     * 一条规则
     *
     * [whenParams] 原样留着那一份子对象: 六条条件各要哪几个参数由 [condition] 与引擎各自读, 这里
     * 不做第二次建模 —— 参数是会长的 (以后加"通知里的关键字"这种), 而每加一个就要动一次数据类
     */
    data class Rule(
        val name: String,
        val enabled: Boolean,
        val whenKind: String,
        val whenParams: JsonObject,
        val thenKind: String,
        val thenText: String,
        val cooldownMinutes: Int,
        val dailyLimit: Int,
        val quietHours: Boolean,
    ) {

        /** 条件那句话里要的那几个参数, 没有就是 null */
        fun string(key: String): String? =
            whenParams[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        fun number(key: String): Double? = whenParams[key]?.jsonPrimitive?.doubleOrNull

        fun int(key: String): Int? = whenParams[key]?.jsonPrimitive?.intOrNull

        fun bool(key: String): Boolean? = whenParams[key]?.jsonPrimitive?.booleanOrNull

        /** 只给 `time` 用: `07:30` 是当天的第几分钟 */
        val atMinutes: Int? get() = string("at")?.let(::minutesOf)

        /**
         * 只给 `once` 用: 那个绝对时刻 (epoch 毫秒)
         *
         * 读不出来就是 null —— 正常情况 [validateWhen] 已经拦过一道, 这里是"文件在盘上被手改坏了"
         * 那一档的兜底
         */
        val onceAt: Long? get() = string("at")?.let(::momentOf)

        /** 只给 `time` 用: 空表示每天 */
        val weekdays: List<Int>
            get() = (whenParams["weekdays"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                .orEmpty()

        /** 组合规则的那几个子条件 (`all` / `any` 之外的 kind 一律空表) */
        val conds: List<JsonObject>
            get() = if (whenKind in COMBO_KINDS) {
                (whenParams["conds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            } else {
                emptyList()
            }

        /** 子条件各自的 kind (顺序就是 `conds` 的顺序) */
        val subKinds: List<String>
            get() = conds.mapNotNull { it["kind"]?.jsonPrimitive?.contentOrNull }

        /**
         * 这一条规则跟 [kind] 这类事件有没有关系
         *
         * 引擎拿它决定"这条 trigger 要不要送进来判一次": 本身就是这个 kind, 或者组合里有这**一类子条件**。
         * 别的 event 送进来只会白判一整天 (通知一来就把所有规则扫一遍), 所以这一条是省电那条口径的落点
         */
        fun involves(kind: String): Boolean =
            whenKind == kind || (whenKind in COMBO_KINDS && kind in subKinds)

        /**
         * 下一次该响的时刻 (epoch 毫秒), 从 [from] 之后算
         *
         * 三条需要一个"未来时刻", 所以排闹钟那一件事也在这里: `time` 按每天那个时刻算, `once` 直接
         * 就是它的绝对时刻 (过了就没有下一次, 回 null), 而**组合里有 `time` 子条件时排到那个子条件上**
         * —— 少了这一条, `all{unlock, time}` 只能靠解锁那一下送到, 而到点时根本没人叫它
         */
        fun nextFireAt(from: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
            if (whenKind == "once") {
                val at = onceAt ?: return null
                return at.takeIf { it > from }
            }
            if (whenKind in COMBO_KINDS) {
                return conds
                    .filter { it["kind"]?.jsonPrimitive?.contentOrNull == "time" }
                    .mapNotNull { one ->
                        val minutes = one["at"]?.jsonPrimitive?.contentOrNull?.let(::minutesOf) ?: return@mapNotNull null
                        val days = (one["weekdays"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.intOrNull }
                            .orEmpty()
                        nextTimeAt(minutes, days, from, zone)
                    }
                    .minOrNull()
            }
            if (whenKind != "time") return null
            val minutes = atMinutes ?: return null
            return nextTimeAt(minutes, weekdays, from, zone)
        }

        /** 条件那一半, 给人看的一句 (设置页、`list`、`status` 都用它) */
        fun condition(zone: ZoneId = ZoneId.systemDefault()): String =
            if (whenKind in COMBO_KINDS) {
                val joiner = if (whenKind == "any") " 或 " else " 且 "
                // 叶子那几句里有的自带结尾的「时」, 组合里要的是一段短语, 所以拼之前把尾巴收掉
                val said = conds.joinToString(joiner) { one ->
                    describeOne(one["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(), one, zone)
                        .removeSuffix("时")
                        .trimEnd()
                }
                "当 $said 时"
            } else {
                describeOne(whenKind, whenParams, zone)
            }

        /** 动作那一半 */
        fun action(): String = when (thenKind) {
            "task" -> "开一场会话去干: $thenText"
            else -> "提醒一声: $thenText"
        }

        /** 设置页与 `list` 上那一行 */
        fun summary(zone: ZoneId = ZoneId.systemDefault()): String = condition(zone) + " → " + action()

        /** 写回磁盘时的那一份 (定义键, `enabled` 也在里面) */
        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("enabled", enabled)
            put("when", buildJsonObject {
                put("kind", whenKind)
                whenParams.forEach { (key, value) -> put(key, value) }
            })
            put("then", buildJsonObject {
                put("kind", thenKind)
                put("text", thenText)
            })
            put("cooldownMinutes", cooldownMinutes)
            put("dailyLimit", dailyLimit)
            put("quietHours", quietHours)
        }
    }

    /**
     * 从一段 JSON 文本读出一条规则
     *
     * 读不出来或校验不过时抛 [IllegalArgumentException], 正文是**所有**毛病拼起来的 —— 一次把话说
     * 完比"改一处再报下一处"省一轮往返
     */
    fun parse(name: String, text: String): Rule {
        val root = try {
            io.github.yuloong07star.luwi.automation.AutomationJson.parse(text)
        } catch (problem: Throwable) {
            throw IllegalArgumentException("this is not valid JSON: ${problem.message}")
        }
        val problems = validate(name, root)
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("; "))
        }
        return of(name, root)
    }

    /** 已知的键之外还写了什么 (只警告, 不拒 —— 以后加参数的规则要能往前兼容) */
    fun unknownKeys(root: JsonObject): List<String> =
        root.keys.filterNot { it in KNOWN_KEYS }.sorted()

    /** 一遍说清哪儿不对; 空表示这一条能收 */
    fun validate(name: String, root: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        if (name.isBlank()) problems += "the rule needs a name"
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull
        if (root.containsKey("enabled") && enabled == null) {
            problems += "enabled has to be true or false"
        }
        val when_ = root["when"] as? JsonObject
        if (when_ == null) {
            problems += "when has to be an object with a kind"
        } else {
            problems += validateWhen(when_)
        }
        val then = root["then"] as? JsonObject
        if (then == null) {
            problems += "then has to be an object with a kind and a text"
        } else {
            val kind = then["kind"]?.jsonPrimitive?.contentOrNull
            if (kind == null || kind !in THEN_KINDS) {
                problems += "then.kind has to be one of ${THEN_KINDS.joinToString(", ")}"
            }
            val text = then["text"]?.jsonPrimitive?.contentOrNull
            if (text.isNullOrBlank()) problems += "then.text has to say what to do"
        }
        number(root, "cooldownMinutes", 0, MAX_COOLDOWN_MINUTES)?.let { problems += it }
        number(root, "dailyLimit", 1, MAX_DAILY_LIMIT)?.let { problems += it }
        root["quietHours"]?.let { value ->
            if (value.jsonPrimitive.booleanOrNull == null) {
                problems += "quietHours has to be true or false"
            }
        }
        return problems
    }

    /**
     * 一条 `when` 要什么: 先认 kind, 组合交给 [validateCombo], 叶子交给 [validateOne]
     *
     * 组合与叶子**共用同一套子条件校验** ([validateOne]) —— 否则"组合里写一个坏的光感"会静默通过,
     * 界面上只表现为"它从来没响过", 而那正是这一层最想拦掉的失败样子
     */
    private fun validateWhen(when_: JsonObject): List<String> {
        val kind = when_["kind"]?.jsonPrimitive?.contentOrNull
        if (kind == null || kind !in WHEN_KINDS) {
            return listOf("when.kind has to be one of ${WHEN_KINDS.joinToString(", ")}")
        }
        return if (kind in COMBO_KINDS) validateCombo(when_, kind) else validateOne(when_, kind)
    }

    /**
     * 组合 (`all` / `any`): 2~5 个子条件, **不许嵌套**
     *
     * 不许嵌套是这一批的已知边界 (计划 §10): "（A 且 B）或（C 且 D）"写不出来, 真需要时是给 `conds`
     * 加一层 `any` 包两个 `all`, 那是以后的事。写在说明里, 也在这里当场拒 —— 收下一条没人会判的组合,
     * 比一句"不收"更坏
     */
    private fun validateCombo(when_: JsonObject, kind: String): List<String> {
        val problems = mutableListOf<String>()
        val list = when_["conds"] as? JsonArray
        if (list == null) {
            problems += "when.conds has to be a list of $COMBO_MIN_CONDS..$COMBO_MAX_CONDS conditions"
            return problems
        }
        if (list.size !in COMBO_MIN_CONDS..COMBO_MAX_CONDS) {
            problems += "when.conds has to hold $COMBO_MIN_CONDS..$COMBO_MAX_CONDS conditions, not ${list.size}"
        }
        list.forEachIndexed { index, element ->
            val one = element as? JsonObject
            if (one == null) {
                problems += "when.conds[$index] has to be an object with a kind"
                return@forEachIndexed
            }
            val sub = one["kind"]?.jsonPrimitive?.contentOrNull
            when {
                sub == null || sub !in LEAF_KINDS -> problems += "when.conds[$index].kind has to be one of" +
                    " ${LEAF_KINDS.joinToString(", ")}" +
                    if (sub in COMBO_KINDS) " (nesting an $sub inside $kind is not supported)" else ""
                else -> {
                    problems += validateOne(one, sub).map { "when.conds[$index]: $it" }
                    problems += validateWindow(one).map { "when.conds[$index]: $it" }
                }
            }
        }
        return problems
    }

    /** `within` 那条覆盖: 5 秒到 24 小时 */
    private fun validateWindow(one: JsonObject): List<String> {
        val value = one["within"] ?: return emptyList()
        val seconds = value.jsonPrimitive.intOrNull
        if (seconds == null || seconds < MIN_WINDOW_SECONDS || seconds > MAX_WINDOW_SECONDS) {
            return listOf("within has to be $MIN_WINDOW_SECONDS..$MAX_WINDOW_SECONDS seconds")
        }
        return emptyList()
    }

    /** 一条叶子条件各自要什么 (组合里的子条件也走它) */
    private fun validateOne(when_: JsonObject, kind: String): List<String> {
        val problems = mutableListOf<String>()
        problems += validateWindow(when_)
        when (kind) {
            "notice" -> {
                when_["packages"]?.let { value ->
                    val list = value as? JsonArray
                    if (list == null || list.any { it.jsonPrimitive.contentOrNull.isNullOrBlank() }) {
                        problems += "when.packages has to be a list of package names"
                    }
                }
                problems += textOrNull(when_, "contains")
                problems += textOrNull(when_, "titleContains")
            }

            "foreground" -> {
                if (when_["package"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    problems += "when.package has to name the app (a package name or an app name)"
                }
            }

            "light" -> {
                val below = when_["below"]?.jsonPrimitive?.doubleOrNull
                val above = when_["above"]?.jsonPrimitive?.doubleOrNull
                if (below == null && above == null) {
                    problems += "when needs below or above (the light level in lux)"
                }
                if (below != null && above != null && above >= below) {
                    problems += "when.above has to be lower than when.below"
                }
                when_["forSeconds"]?.let { value ->
                    val seconds = value.jsonPrimitive.intOrNull
                    if (seconds == null || seconds < 0 || seconds > MAX_LIGHT_SECONDS) {
                        problems += "when.forSeconds has to be 0..$MAX_LIGHT_SECONDS"
                    }
                }
            }

            "time" -> {
                val at = when_["at"]?.jsonPrimitive?.contentOrNull
                if (at.isNullOrBlank() || minutesOf(at) == null) {
                    problems += "when.at has to be a time of day like 07:30"
                }
                when_["weekdays"]?.let { value ->
                    val days = (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
                    if (days == null || days.any { it !in 1..7 }) {
                        problems += "when.weekdays has to be a list of 1..7 (1 is Monday)"
                    }
                }
            }

            "place" -> {
                if (when_["place"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    problems += "when.place has to name a place"
                }
                when_["radiusMeters"]?.let { value ->
                    val radius = value.jsonPrimitive.intOrNull
                    if (radius == null || radius < MIN_RADIUS_METERS || radius > MAX_RADIUS_METERS) {
                        problems += "when.radiusMeters has to be $MIN_RADIUS_METERS..$MAX_RADIUS_METERS"
                    }
                }
            }

            "weather" -> {
                if (when_["place"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    problems += "when.place has to name a place"
                }
                val metric = when_["metric"]?.jsonPrimitive?.contentOrNull
                if (metric == null || metric !in WEATHER_METRICS) {
                    problems += "when.metric has to be one of ${WEATHER_METRICS.joinToString(", ")}"
                }
                val comparators = listOf("below", "above", "atLeast").count {
                    when_[it]?.jsonPrimitive?.doubleOrNull != null
                }
                if (comparators != 1) {
                    problems += "when needs exactly one of below, above or atLeast"
                }
            }

            "once" -> {
                val at = when_["at"]?.jsonPrimitive?.contentOrNull
                if (at.isNullOrBlank() || momentOf(at) == null) {
                    problems += "when.at has to be an absolute moment with a timezone," +
                        " like 2026-10-18T21:30:00+08:00"
                }
            }

            "unlock" -> {
                when_["firstOfDay"]?.let { value ->
                    if (value.jsonPrimitive.booleanOrNull == null) {
                        problems += "when.firstOfDay has to be true or false"
                    }
                }
            }

            "activity" -> {
                when_["withinMinutes"]?.let { value ->
                    val minutes = value.jsonPrimitive.intOrNull
                    if (minutes == null || minutes < 1 || minutes > ACTIVITY_MAX_MINUTES) {
                        problems += "when.withinMinutes has to be 1..$ACTIVITY_MAX_MINUTES"
                    }
                }
            }
        }
        return problems
    }

    /** 一条规则能收下来时的那一份 */
    private fun of(name: String, root: JsonObject): Rule {
        val when_ = root["when"]!!.jsonObject
        val then = root["then"]!!.jsonObject
        val params = buildJsonObject {
            when_.forEach { (key, value) -> if (key != "kind") put(key, value) }
        }
        return Rule(
            name = name,
            enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            whenKind = when_["kind"]!!.jsonPrimitive.contentOrNull!!,
            whenParams = params,
            thenKind = then["kind"]!!.jsonPrimitive.contentOrNull!!,
            thenText = then["text"]!!.jsonPrimitive.contentOrNull!!.trim(),
            cooldownMinutes = root["cooldownMinutes"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_COOLDOWN_MINUTES,
            dailyLimit = root["dailyLimit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_DAILY_LIMIT,
            quietHours = root["quietHours"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }

    /** 天气那三条能问什么 */
    val WEATHER_METRICS = listOf("temperature", "precipitation", "weatherCode")

    /**
     * 一条条件说人话的那一句 —— 叶子规则与组合里的子条件**共用这一份**
     *
     * 参数一律从 [params] 现取 (叶子就是 `when` 那一份, 子条件就是 `conds[i]`), 于是"组合里那条
     * 到 23:45"与"单独一条到 23:45"念出来一模一样, 不必维护两套措辞
     */
    private fun describeOne(kind: String, params: JsonObject, zone: ZoneId): String {
        fun s(key: String) = params[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun n(key: String) = params[key]?.jsonPrimitive?.doubleOrNull
        fun i(key: String) = params[key]?.jsonPrimitive?.intOrNull
        return when (kind) {
            "notice" -> {
                val who = when {
                    s("contains") != null && s("titleContains") != null ->
                        "标题或正文里有「${s("titleContains")}」「${s("contains")}」"
                    s("titleContains") != null -> "标题里有「${s("titleContains")}」"
                    s("contains") != null -> "正文里有「${s("contains")}」"
                    else -> "任何一条通知"
                }
                val packages = (params["packages"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                "收到$who" + (if (packages.isEmpty()) "" else " (只看 ${packages.joinToString(", ")} 发的)")
            }

            "foreground" -> "切到「${s("package").orEmpty()}」这个应用时"

            "light" -> {
                val below = n("below")
                val above = n("above")
                val seconds = i("forSeconds") ?: 0
                val hold = if (seconds > 0) "持续 $seconds 秒" else "一亮就"
                when {
                    below != null && above != null -> "$hold 光照在 ${trim(above)} ~ ${trim(below)} lux 之间时"
                    below != null -> "$hold 光照低于 ${trim(below)} lux 时"
                    above != null -> "$hold 光照高于 ${trim(above)} lux 时"
                    else -> "光照变化时 (没有给阈值)"
                }
            }

            "time" -> {
                val at = s("at").orEmpty()
                val days = (params["weekdays"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.intOrNull }
                    .orEmpty()
                val dayText = when {
                    days.isEmpty() -> "每天"
                    days.size == 7 -> "每天"
                    else -> days.sorted().joinToString("") { weekdayName(it) }
                }
                "$dayText $at"
            }

            "place" -> {
                val radius = i("radiusMeters") ?: DEFAULT_RADIUS_METERS
                "到「${s("place").orEmpty()}」$radius 米以内时"
            }

            "weather" -> {
                val metric = s("metric").orEmpty()
                val place = s("place").orEmpty()
                val said = when {
                    n("below") != null -> "${metricName(metric)}低于 ${trim(n("below"))}"
                    n("above") != null -> "${metricName(metric)}高于 ${trim(n("above"))}"
                    n("atLeast") != null -> "${metricName(metric)}达到 ${trim(n("atLeast"))}"
                    else -> "${metricName(metric)}变化时"
                }
                "「$place」的$said"
            }

            "unlock" -> if (params["firstOfDay"]?.jsonPrimitive?.booleanOrNull == true) {
                "今天第一次解锁后"
            } else {
                "最近一次解锁后"
            }

            "activity" -> "最近 ${i("withinMinutes") ?: ACTIVITY_DEFAULT_MINUTES} 分钟在动"

            "once" -> {
                val at = s("at")
                "只响一次 · " + (at?.let(::momentOf)?.let { formatMoment(it, zone) } ?: at.orEmpty())
            }

            else -> kind
        }
    }

    /** 从 [from] 之后最近一次"每天的 [minutes] 那个时刻" (周几可选); 排不出来就是 null */
    private fun nextTimeAt(minutes: Int, days: List<Int>, from: Long, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        for (step in 0..8) {
            val date = today.plusDays(step.toLong())
            if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
            val at = date.atStartOfDay(zone)
                .plusMinutes(minutes.toLong())
                .toInstant()
                .toEpochMilli()
            if (at > from) return at
        }
        return null
    }

    /** `07:30` / `7:30` -> 450; 认不出来就是 null */
    fun minutesOf(text: String): Int? {
        val pieces = text.trim().replace('：', ':').split(':')
        if (pieces.size != 2) return null
        val hour = pieces[0].trim().toIntOrNull() ?: return null
        val minute = pieces[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /**
     * 一个带时区的 ISO 8601 时刻 -> epoch 毫秒; 认不出来就是 null
     *
     * **必须带时区** (`Z` 或 `+08:00`): 不带时区的本机时刻不能当绝对时刻用, 所以 `LocalDateTime` 那条
     * 路不收 (校验那一层会把它当"没写时区"拒掉)
     */
    fun momentOf(text: String): Long? =
        runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()

    /** epoch 毫秒 -> `2026-10-18 21:30` (设置页那一行与 `summary` 都用它) */
    fun formatMoment(at: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    /** 一个整数键的边界检查, 有问题就是那一句, 没有就 null */
    private fun number(root: JsonObject, key: String, low: Int, high: Int): String? {
        val value = root[key] ?: return null
        val number = value.jsonPrimitive.intOrNull ?: return "$key has to be a whole number"
        if (number < low || number > high) return "$key has to be $low..$high"
        return null
    }

    private fun textOrNull(when_: JsonObject, key: String): List<String> {
        val value = when_[key] ?: return emptyList()
        return if (value.jsonPrimitive.contentOrNull == null) {
            listOf("when.$key has to be text")
        } else {
            emptyList()
        }
    }

    /** 1..7 -> 周一…周日 */
    fun weekdayName(day: Int): String = when (day) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    private fun metricName(metric: String): String = when (metric) {
        "temperature" -> "气温"
        "precipitation" -> "降水量"
        "weatherCode" -> "天气代码"
        else -> metric
    }

    /** 数字说短一点: 12.0 -> 12, 12.34 -> 12.3 */
    private fun trim(value: Double?): String = when {
        value == null -> "?"
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> "%.1f".format(value)
    }

    /** 今天 0 点 (本地日): 每日上限按它切 */
    fun startOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()

    /** 两个点之间的大圆距离 (米); 只给"到某地"那一条用 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** 本地日期戳 (`2026-10-08`), 每日上限与 `history` 都用它 */
    fun dayKey(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDate.from(Instant.ofEpochMilli(now).atZone(zone)).toString()

    /** 一顿数组, 收成字符串表 */
    fun strings(value: kotlinx.serialization.json.JsonElement?): List<String> =
        (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    /** 一串整数, 收成 Int 表 */
    fun ints(value: kotlinx.serialization.json.JsonElement?): List<Int> =
        (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()

    /** 一个子对象, 不是对象就是空 */
    fun objectOrEmpty(value: kotlinx.serialization.json.JsonElement?): JsonObject =
        (value as? JsonObject) ?: JsonObject(emptyMap())

    /** 一个布尔, 没有就是默认值 */
    fun boolOr(value: kotlinx.serialization.json.JsonElement?, fallback: Boolean): Boolean =
        value?.jsonPrimitive?.booleanOrNull ?: fallback

    /** 一个整数, 没有就是默认值 */
    fun intOr(value: kotlinx.serialization.json.JsonElement?, fallback: Int): Int =
        value?.jsonPrimitive?.intOrNull ?: fallback

    /** 一个字符串, 没有就是默认值 */
    fun stringOr(value: kotlinx.serialization.json.JsonElement?, fallback: String): String =
        value?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallback

    /** 一串数组, 收成整数表 */
    fun arrayOrEmpty(value: kotlinx.serialization.json.JsonElement?): JsonArray =
        (value as? JsonArray) ?: buildJsonArray { }

}
