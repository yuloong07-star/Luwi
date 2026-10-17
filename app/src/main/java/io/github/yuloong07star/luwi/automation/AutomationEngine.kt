package io.github.yuloong07star.luwi.automation

import android.content.Context
import android.util.Log
import io.github.yuloong07star.luwi.channel.LwAccessibility
import io.github.yuloong07star.luwi.channel.LwNotificationListener
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.voice.VoiceInbox
import io.github.yuloong07star.luwi.wake.PowerWindow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 自动指令: 条件成立就动一下
 *
 * 三层里的中间那一层 (条件在 [AutomationMonitors], 动作在这里收口), 它管四件事:
 *
 * 1. **判定**: 六条条件各自的"现在算不算命中", 加上光感那条的迟滞与"持续多少秒"
 * 2. **防骚扰**: 静默时段 / 冷却 / 每日上限 / "允许它自己动手"那道总闸
 * 3. **记账**: 每一次真的决定要不要动都落一行到 `history.jsonl` —— 主人问"它为什么没响"时, 唯一
 *    答得出来的就是那一份
 * 4. **该起来谁**: 六个监测器不是常驻的, `requiredMonitors()` 说此刻该注册哪几个, 省电时把轮询那
 *    两个摘掉 —— 一条启用的规则都没有时, 进程里不该留下任何 sensor / location / alarm 注册
 *
 * 线程: 只有一个工作线程 (事件回调用它), 一个心跳 (60 秒一次: 重读规则 / 拨监测器 / 到点就问天气)。
 * 心跳那 60 秒在 host 那条 150 ms 的投递轮询旁边是噪音级, 而它换来的是"规则文件改了不用重启、
 * 省电时段到点自己生效"这两件不必让人操心的事
 */
internal object AutomationEngine {

    private const val TAG = "LwAutomation"

    /** 心跳多久一次: 重读规则、拨监测器、看一眼天气该不该问 */
    private const val HEARTBEAT_SECONDS = 60L

    /** 时间条件迟到多少还算命中 (闹钟在打盹的设备上晚几分钟是常事, 记 `late` 而不是当没发生) */
    private const val LATE_WINDOW_MINUTES = 10L

    /** 事件型条件不成立时, 每多少条合并成一行历史 (不合并的话通知一来一整天全是它) */
    private const val MISS_MERGE = 20

    /** 同一条规则的同一种"被拦下", 至少隔这么久才再记一行 (换了原因就立刻记) */
    private const val BLOCK_NOTE_MS = 5 * 60_000L

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lw-automation").apply { isDaemon = true }
    }

    /** 网络那条单独一条线程: 一次 HTTP 慢起来不该让通知那条判定排队 */
    private val net = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-automation-net").apply { isDaemon = true }
    }

    @Volatile
    private var started = false
    private var app: Context? = null
    private var beat: ScheduledFuture<*>? = null

    /** 规则目录的戳: 变了就重读 (模型写完一条, 下一拍就该看见它) */
    private var stamp = 0L

    private var rules: List<AutomationRule.Rule> = emptyList()
    private var settings: AutomationStore.Settings? = null

    /** 上次真的动过的时刻 (冷却那笔账) */
    private val lastFired = HashMap<String, Long>()

    /** 今天动了几次 (每日上限那笔账), 每日 0 点按 [todayKey] 归零 */
    private val firedToday = HashMap<String, Int>()
    private var todayKey: String = ""

    /** 事件型条件"没命中"攒了几条 (合并那一行历史用) */
    private val misses = HashMap<String, Int>()

    /**
     * 每条规则上一次"被拦下"是什么时候记的 (拦下那一行要限流)
     *
     * 光感那种条件一秒能成立好几次, 而冷却与静默会一直拦着它 —— 每次都写一行的话, 一条常亮的光感规则
     * 一天能把 `history.jsonl` 写成几千行, 而主人要看的信息一个字都没多
     */
    private val lastBlock = HashMap<String, Pair<String, Long>>()

    /** 光感那条现在的状态与"什么时候开始满足" (迟滞与 forSeconds) */
    private val lightInside = HashMap<String, Boolean>()
    private val lightSince = HashMap<String, Long>()

    /** 省电是不是正生效: 只在变化那一刻记一行 `paused`, 不每拍记 */
    private var paused = false

    /** 地名 → 经纬度 (磁盘上那份的进程内镜像), 省得每次判定都读文件 */
    private var places: Map<String, AutomationStore.Place> = emptyMap()

    /** 最近一轮天气 (给 `status` 看) */
    private var weather: Map<String, String> = emptyMap()

    private var lastWeatherAt = 0L

    private val weatherLock = Any()

    /** 触发这一次判定的是什么 —— 六条条件各自带自己的那点数据 */
    private class Trigger(
        val kind: String,
        val packageName: String? = null,
        val title: String = "",
        val text: String = "",
        val lux: Double? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val readings: Map<String, WeatherNow> = emptyMap(),
    )

    /** 一次实况里的三个数 */
    data class WeatherNow(val temperature: Double?, val precipitation: Double?, val code: Int?)

    private sealed interface Outcome {
        /** 命中了, [reason] 是一句给人看的"为什么算命中" */
        class Hit(val reason: String) : Outcome

        /** 条件不成立 */
        class Miss(val reason: String) : Outcome

        /** 这条条件这一会儿根本用不了 (缺权限 / 无障碍关着 / 地名没解析出来) */
        class Unavailable(val reason: String) : Outcome
    }

    /** 起引擎: 宿主服务起来时叫一次, 重复叫没有副作用 */
    fun ensure(context: Context) {
        val application = context.applicationContext
        synchronized(this) {
            app = application
            if (started) return
            started = true
            todayKey = AutomationRule.dayKey(System.currentTimeMillis())
            places = runCatching { AutomationStore.places(application) }.getOrDefault(emptyMap())
            for ((name, pair) in runCatching {
                AutomationStore.counters(application, todayKey)
            }.getOrDefault(emptyMap())) {
                lastFired[name] = pair.first
                firedToday[name] = pair.second
            }
            refresh(application, force = true)
            beat = worker.scheduleWithFixedDelay(
                { runCatching { heartbeat(application) }.onFailure { warn("heartbeat", it) } },
                5L,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    fun stop() {
        synchronized(this) {
            started = false
            beat?.cancel(false)
            beat = null
        }
        AutomationMonitors.stopAll()
    }

    /** 规则或设置改过了, 下一拍重读 (设置页与工具写完都叫一声) */
    fun invalidate() {
        stamp = 0L
        appNames = null
        val application = app ?: return
        worker.execute { runCatching { refresh(application, force = true) }.onFailure { warn("refresh", it) } }
    }

    /** 现在有哪些规则 (设置页与 `list` 读它) */
    fun rules(): List<AutomationRule.Rule> {
        val application = app
        if (application != null) refresh(application, force = false)
        return rules
    }

    /** 设置页那一份 (改完要 [saveSettings]) */
    fun settings(): AutomationStore.Settings? {
        val application = app ?: return null
        refresh(application, force = false)
        return settings
    }

    fun saveSettings(context: Context, settings: AutomationStore.Settings) {
        this.settings = settings
        AutomationStore.saveSettings(context.applicationContext, settings)
        AutomationMonitors.apply(context.applicationContext, requiredMonitors(settings), paused)
    }

    /**
     * 此刻该注册哪几个监测器
     *
     * 三条判据合起来: 这一类有启用的规则、这一类没被主人关掉、省电时轮询类不注册。**没有启用的规则
     * 就一个都不注册** —— 这一条是"低功耗"那句口径的落点
     */
    private fun requiredMonitors(settings: AutomationStore.Settings?): Set<String> {
        val on = settings ?: return emptySet()
        val wanted = rules.asSequence()
            .filter { it.enabled }
            .map { it.whenKind }
            .filter { on.monitor(it) }
            .toMutableSet()
        if (paused) wanted.removeAll(POLLING_KINDS)
        return wanted
    }

    /** 六条里哪两条是轮询的 (省电时停的就是它们) */
    private val POLLING_KINDS = setOf("weather", "place")

    /**
     * 重读规则与设置, 把监测器拨到该有的样子, 顺手排下一次时间条件的闹钟
     *
     * @param force 目录戳没变也重读 (刚写完一条规则时用)
     */
    private fun refresh(context: Context, force: Boolean) {
        val directory = AutomationStore.directory(context)
        val current = if (directory.isDirectory) directory.lastModified() else 0L
        if (!force && current == stamp) return
        stamp = current
        rules = runCatching { AutomationStore.rules(context) }.getOrDefault(emptyList())
        settings = runCatching { AutomationStore.settings(context) }
            .getOrDefault(
                AutomationStore.Settings(
                    monitors = AutomationRule.WHEN_KINDS.associateWith { true },
                    weatherMinutes = AutomationStore.DEFAULT_WEATHER_MINUTES,
                    placeMinutes = AutomationStore.DEFAULT_PLACE_MINUTES,
                    placeMeters = AutomationStore.DEFAULT_PLACE_METERS,
                    quietEnabled = true,
                    quietWindow = AutomationStore.DEFAULT_QUIET,
                    allowActing = false,
                ),
            )
        places = runCatching { AutomationStore.places(context) }.getOrDefault(emptyMap())
        refreshPowerSave(context)
        AutomationMonitors.apply(context, requiredMonitors(settings), paused)
        AutomationMonitors.armTimeAlarm(context, nextAlarm())
    }

    /**
     * 省电是不是正生效 —— **与规则分开看**
     *
     * 它不在 `automations/` 那个目录里 (是唤醒词那一段的偏好), 所以"目录戳没变就不重读"那条早退会让
     * 它永远停在开机时的值: 现象是主人在设置页把省电打开, 而轮询那两个监测器照跑 (2026-10-08 在模拟器
     * 上实测到的就是这个)。所以这个字节**每一拍都看**, 变了才重新拨监测器
     */
    private fun refreshPowerSave(context: Context) {
        val now = runCatching { LwWakeWord.powerSave(context) }.getOrDefault(false)
        if (now == paused) return
        paused = now
        if (paused) notifyPaused(context)
    }

    /** 省电开始那一刻: 给每一条轮询类的启用规则记一行"这一拍没跑" */
    private fun notifyPaused(context: Context) {
        val now = System.currentTimeMillis()
        rules.filter { it.enabled && it.whenKind in POLLING_KINDS }.forEach { rule ->
            record(context, fired(rule, now, hit = false, blocked = "paused", reason = "省电中, 轮询类这一阵不跑"))
        }
    }

    /**
     * 下一次该排的闹钟 (`time` 与 `once` 两类里最近的那一个, 只排一个)
     *
     * `once` 用一个"往前让 10 分钟"的起点: 刚错过、还在迟到窗口里的那一条也要能补排一次
     * (宿主重启之后闹钟本来就丢过一次, 不让它补排就等于把这一条吞了)
     */
    private fun nextAlarm(now: Long = System.currentTimeMillis()): Long? =
        rules.asSequence()
            .filter {
                it.enabled && (it.whenKind == "time" || it.whenKind == "once") &&
                    settings?.monitor(it.whenKind) != false
            }
            .mapNotNull { rule ->
                val from = if (rule.whenKind == "once") now - LATE_WINDOW_MINUTES * 60_000L - 1 else now
                rule.nextFireAt(from)
            }
            .minOrNull()

    private fun heartbeat(context: Context) {
        refresh(context, force = false)
        // 省电那一个字节每一拍都看: 它不在规则目录的戳里 (见 [refreshPowerSave])
        val wasPaused = paused
        refreshPowerSave(context)
        if (wasPaused != paused) {
            AutomationMonitors.apply(context, requiredMonitors(settings), paused)
            AutomationMonitors.armTimeAlarm(context, nextAlarm())
        }
        val now = System.currentTimeMillis()
        val key = AutomationRule.dayKey(now)
        if (key != todayKey) {
            todayKey = key
            firedToday.clear()
            misses.clear()
        }
        retireMissedOnce(context, now)
        pollWeather(context, now)
    }

    /* ── 事件入口 (监测器叫它们) ─────────────────────────────────────────── */

    fun onNotification(context: Context, posted: LwNotificationListener.Posted) {
        // 本应用自己的**常驻**通知不算"来了一条" (host / 浮球 / 唤醒词那几条一直都在, 收它们只会白判
        // 一整天); 而 `lw_notify` 发出来的普通通知照收 —— 验收里就是用它触发那一条
        if (posted.packageName == context.packageName && posted.ongoing) return
        val application = context.applicationContext
        worker.execute {
            runCatching {
                evaluate(
                    application,
                    Trigger("notice", packageName = posted.packageName, title = posted.title, text = posted.text),
                )
            }.onFailure { warn("notice", it) }
        }
    }

    fun onForeground(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val application = context.applicationContext
        worker.execute {
            runCatching { evaluate(application, Trigger("foreground", packageName = packageName)) }
                .onFailure { warn("foreground", it) }
        }
    }

    fun onLight(context: Context, lux: Double) {
        val application = context.applicationContext
        worker.execute {
            runCatching { evaluate(application, Trigger("light", lux = lux)) }
                .onFailure { warn("light", it) }
        }
    }

    fun onLocation(context: Context, latitude: Double, longitude: Double) {
        val application = context.applicationContext
        worker.execute {
            runCatching {
                evaluate(application, Trigger("place", latitude = latitude, longitude = longitude))
            }.onFailure { warn("place", it) }
        }
    }

    fun onTime(context: Context) {
        val application = context.applicationContext
        worker.execute {
            runCatching { evaluate(application, Trigger("time")) }.onFailure { warn("time", it) }
        }
    }

    /**
     * 一次性规则的时刻到了 (`once` 与 `time` 共用那个闹钟)
     *
     * 与 [onTime] 分开是因为判定按 kind 过滤: 同一拍里两类各判各的, 互不影响
     */
    fun onOnce(context: Context) {
        val application = context.applicationContext
        worker.execute {
            runCatching { evaluate(application, Trigger("once")) }.onFailure { warn("once", it) }
        }
    }

    /* ── 判定 ────────────────────────────────────────────────────────────── */

    private fun evaluate(context: Context, trigger: Trigger) {
        refresh(context, force = false)
        val now = System.currentTimeMillis()
        val candidates = rules.filter { it.enabled && it.whenKind == trigger.kind }
        if (candidates.isEmpty()) return
        if (settings?.monitor(trigger.kind) == false) return
        for (rule in candidates) {
            when (val outcome = condition(rule, trigger, now)) {
                is Outcome.Unavailable -> record(
                    context,
                    fired(rule, now, hit = false, blocked = "unavailable", reason = outcome.reason),
                )

                is Outcome.Miss -> missed(context, rule, now, outcome.reason, trigger.kind)
                is Outcome.Hit -> act(context, rule, now, outcome.reason)
            }
        }
    }

    /** 一条规则此刻算不算命中 (六条各一段, 没有共享的算术) */
    private fun condition(rule: AutomationRule.Rule, trigger: Trigger, now: Long): Outcome = when (trigger.kind) {
        "notice" -> {
            val packages = AutomationRule.strings(rule.whenParams["packages"])
            val who = trigger.packageName.orEmpty()
            val contains = rule.string("contains")
            val titleContains = rule.string("titleContains")
            val body = trigger.text
            val title = trigger.title
            when {
                packages.isNotEmpty() && who !in packages -> Outcome.Miss("不是这些应用发的 ($who)")
                titleContains != null && !title.contains(titleContains) ->
                    Outcome.Miss("标题里没有「$titleContains」")

                contains != null && !body.contains(contains) && !title.contains(contains) ->
                    Outcome.Miss("正文与标题里都没有「$contains」")

                else -> Outcome.Hit("通知来自 $who" + (contains?.let { ", 里面有「$it」" } ?: ""))
            }
        }

        "foreground" -> {
            val wanted = rule.string("package").orEmpty()
            val resolved = resolveApp(wanted)
            when {
                resolved == null -> Outcome.Unavailable("这个应用名这台设备上找不到 ($wanted)")
                resolved == trigger.packageName -> Outcome.Hit("切到了 $resolved")
                else -> Outcome.Miss("现在是 ${trigger.packageName}")
            }
        }

        "light" -> {
            val lux = trigger.lux ?: return Outcome.Unavailable("光感没有读数")
            val below = rule.number("below")
            val above = rule.number("above")
            val was = lightInside[rule.name] ?: false
            val inside = when {
                below != null -> if (was) lux <= below * 1.1 else lux <= below
                above != null -> if (was) lux >= above * 0.9 else lux >= above
                else -> false
            }
            lightInside[rule.name] = inside
            if (!inside) {
                lightSince.remove(rule.name)
                return Outcome.Miss("现在是 ${"%.0f".format(lux)} lux")
            }
            val seconds = (rule.int("forSeconds") ?: 0).toLong()
            if (seconds <= 0) return Outcome.Hit("光照 ${"%.0f".format(lux)} lux")
            val since = lightSince.getOrPut(rule.name) { now }
            return if (now - since >= seconds * 1000) {
                Outcome.Hit("光照 ${"%.0f".format(lux)} lux 已经持续 ${seconds} 秒")
            } else {
                Outcome.Miss("光照到了, 但还没持续够 $seconds 秒")
            }
        }

        "time" -> {
            val minutes = rule.atMinutes
            val days = rule.weekdays
            val target = rule.nextFireAt(now - LATE_WINDOW_MINUTES * 60_000L - 1, timeZone())
                ?: return Outcome.Miss("今天不是这一天")
            val late = now - target
            when {
                days.isNotEmpty() && !days.contains(dayOfWeek(now)) -> Outcome.Miss("今天不是这一天")
                minutes == null -> Outcome.Unavailable("这条规则没有写时间")
                late > LATE_WINDOW_MINUTES * 60_000L -> Outcome.Miss("闹钟晚了 ${late / 60_000} 分钟")
                late > 60_000L -> Outcome.Hit("到点了 (晚了 ${late / 60_000} 分钟)")
                else -> Outcome.Hit("到点了")
            }
        }

        "once" -> {
            val at = rule.onceAt ?: return Outcome.Unavailable("这一条的一次性时刻读不出来")
            val late = now - at
            when {
                late < 0 -> Outcome.Miss("还没到点")
                late > LATE_WINDOW_MINUTES * 60_000L ->
                    Outcome.Miss("晚得太多 (${late / 60_000} 分钟), 这一条不会再响")

                late > 60_000L -> Outcome.Hit("到点了 (晚了 ${late / 60_000} 分钟)")
                else -> Outcome.Hit("到点了")
            }
        }

        "place" -> {
            val name = rule.string("place").orEmpty()
            val latitude = trigger.latitude ?: return Outcome.Unavailable("这一轮没有位置")
            val longitude = trigger.longitude ?: return Outcome.Unavailable("这一轮没有位置")
            val place = places[name] ?: return Outcome.Unavailable("地名「$name」还没解析出来")
            val meters = AutomationRule.distanceMeters(latitude, longitude, place.latitude, place.longitude)
            val radius = (rule.int("radiusMeters") ?: AutomationRule.DEFAULT_RADIUS_METERS).toDouble()
            if (meters <= radius) {
                Outcome.Hit("离「$name」还有 ${"%.0f".format(meters)} 米")
            } else {
                Outcome.Miss("离「$name」还有 ${"%.0f".format(meters / 1000)} 公里")
            }
        }

        "weather" -> {
            val name = rule.string("place").orEmpty()
            val reading = trigger.readings[name]
                ?: return Outcome.Unavailable("「$name」这一轮没拿到天气")
            val metric = rule.string("metric").orEmpty()
            val value = when (metric) {
                "temperature" -> reading.temperature
                "precipitation" -> reading.precipitation
                "weatherCode" -> reading.code?.toDouble()
                else -> null
            } ?: return Outcome.Unavailable("「$name」这一轮没有 $metric 这个数")
            val below = rule.number("below")
            val above = rule.number("above")
            val atLeast = rule.number("atLeast")
            val said = "${metricNameOf(metric)} ${trim(value)}"
            when {
                below != null && value < below -> Outcome.Hit("$said < $below")
                above != null && value > above -> Outcome.Hit("$said > $above")
                atLeast != null && value >= atLeast -> Outcome.Hit("$said >= $atLeast")
                else -> Outcome.Miss(
                    when {
                        below != null -> "$said 不满足 < $below"
                        above != null -> "$said 不满足 > $above"
                        else -> "$said 不满足 >= $atLeast"
                    },
                )
            }
        }

        else -> Outcome.Miss("不认识的 condition")
    }

    /** 命中了: 先过五道闸, 再决定投不投 */
    private fun act(context: Context, rule: AutomationRule.Rule, now: Long, reason: String) {
        val on = settings ?: return
        val cooldownMs = rule.cooldownMinutes * 60_000L
        val last = lastFired[rule.name] ?: 0L
        val today = firedToday[rule.name] ?: 0
        val window = if (on.quietEnabled && rule.quietHours) quietWindow() else null
        // 每日主动说 (D17): 只数 `task` 那一类, 手动「立刻跑一次」不算 (见 AutomationStore.saidToday)
        val saidToday = if (rule.thenKind == "task") AutomationStore.saidToday(context, now) else 0
        val blocked = when {
            window != null && PowerWindow.inside(PowerWindow.minutesOfDay(now), window) -> "quiet"
            last + cooldownMs > now -> "cooldown"
            today >= rule.dailyLimit -> "limit"
            rule.thenKind == "task" && !on.allowActing -> "no_acting"
            rule.thenKind == "task" && saidToday >= on.saidLimit -> "said"
            else -> null
        }
        if (blocked != null) {
            val said = when (blocked) {
                "quiet" -> "现在是静默时段 (${PowerWindow.describe(window!!)})"
                "cooldown" -> "上一条才 ${(now - last) / 60_000} 分钟, 冷却 ${rule.cooldownMinutes} 分钟"
                "limit" -> "今天已经 $today 次, 上限 ${rule.dailyLimit} 次"
                "said" -> "今天已经主动说了 $saidToday 次, 上限 ${on.saidLimit} 次 (设置 → 探索)"
                else -> "这条规则要动手, 而「允许它自己动手」关着"
            }
            noteBlocked(context, rule, blocked, said, now)
            // 一次性规则的时刻已经过去: 拦下也算它用掉了 (不会再有下一拍)
            retireOnce(context, rule)
            return
        }
        val line = prompt(rule)
        val seq = VoiceInbox.append(context, line, source = VoiceInbox.SOURCE_AUTOMATION)
        // 账只在 record 那一处记一次: 这里再记一遍会让"今天几次"翻倍 (2026-10-08 实测到的就是它)
        lastBlock.remove(rule.name)
        record(
            context,
            fired(
                rule = rule,
                now = now,
                hit = true,
                blocked = null,
                reason = reason + if (seq == null) " (队列写不进去)" else "",
                action = rule.thenKind,
                delivered = seq != null,
            ),
        )
        // 一次性规则响过一次就关掉 (它的时刻已经过去, 再留着只会让主人以为它还会响)
        retireOnce(context, rule)
    }

    /**
     * 把一条 `once` 规则关掉 (投出去了 / 被拦下 / 错过窗口, 三种都走它)
     *
     * 内存里那一份也一起改: 同一拍里后面的判定不该再看见它; 顺手把闹钟重排到"下一条"
     */
    private fun retireOnce(context: Context, rule: AutomationRule.Rule) {
        if (rule.whenKind != "once" || !rule.enabled) return
        runCatching { AutomationStore.setEnabled(context, rule.name, false) }
            .onFailure { warn("retireOnce", it) }
        rules = rules.map { if (it.name == rule.name) it.copy(enabled = false) else it }
        AutomationMonitors.armTimeAlarm(context, nextAlarm())
    }

    /**
     * 心跳里那一条: 时刻早就过去、又没赶上窗口的 `once` 规则
     *
     * 宿主重启 / 设备关机几天之后会留下这种规则; 不关掉的话它会一直挂在设置页上, 而永远不会响
     */
    private fun retireMissedOnce(context: Context, now: Long) {
        val window = LATE_WINDOW_MINUTES * 60_000L
        val missed = rules.filter {
            it.enabled && it.whenKind == "once" && ((it.onceAt ?: Long.MAX_VALUE) + window) < now
        }
        if (missed.isEmpty()) return
        missed.forEach { rule ->
            record(
                context,
                fired(
                    rule = rule,
                    now = now,
                    hit = false,
                    blocked = null,
                    reason = "一次性时刻已经过去 (${rule.string("at")}), 不会再响",
                ),
            )
            runCatching { AutomationStore.setEnabled(context, rule.name, false) }
                .onFailure { warn("retireMissedOnce", it) }
        }
        rules = rules.map { rule ->
            if (missed.any { it.name == rule.name }) rule.copy(enabled = false) else rule
        }
        AutomationMonitors.armTimeAlarm(context, nextAlarm(now))
    }

    /**
     * 被拦下那一行, **限流**: 同一条规则的同一种原因至少隔 [BLOCK_NOTE_MS] 才再记一行
     *
     * 换了原因 (冷却 -> 静默) 立刻记: 那句话变了就是新信息
     */
    private fun noteBlocked(
        context: Context,
        rule: AutomationRule.Rule,
        blocked: String,
        said: String,
        now: Long,
    ) {
        val previous = lastBlock[rule.name]
        if (previous != null && previous.first == blocked && now - previous.second < BLOCK_NOTE_MS) return
        lastBlock[rule.name] = blocked to now
        record(context, fired(rule, now, hit = true, blocked = blocked, reason = said))
    }

    /**
     * 条件不成立时那一行
     *
     * 轮询类 (天气 / 地点) 每次都写 —— 它们的节拍本来就慢, 而"上一拍问到的数是多少"正是排查时要看
     * 的; 事件型 (通知 / 前台应用) 每 [MISS_MERGE] 条合并一条, 不然一屏通知就能把历史刷满
     */
    private fun missed(context: Context, rule: AutomationRule.Rule, now: Long, reason: String, kind: String) {
        if (kind in POLLING_KINDS) {
            record(context, fired(rule, now, hit = false, blocked = null, reason = reason))
            return
        }
        val count = (misses[rule.name] ?: 0) + 1
        misses[rule.name] = count
        if (count % MISS_MERGE == 0) {
            record(
                context,
                fired(
                    rule = rule,
                    now = now,
                    hit = false,
                    blocked = null,
                    reason = "$MISS_MERGE 次里没有一次匹配 (最后一次: $reason)",
                    count = MISS_MERGE,
                ),
            )
            misses[rule.name] = 0
        }
    }

    /** 投给会话的那一句话 */
    private fun prompt(rule: AutomationRule.Rule): String {
        val head = "【自动指令】${rule.name}: ${rule.thenText}"
        return if (rule.thenKind == "task") {
            "$head —— 现在按这件事做, 做完用一句汉语说清做了什么。"
        } else {
            "$head —— 现在这件事到了。写一句给主人看的话 (40 字以内), 用 lw_notify 发一条通知" +
                " (title 用这条规则的名字, body 用你写的那句), 然后把同一句话作为回答。不要做别的事, 不要动手机。"
        }
    }

    private fun fired(
        rule: AutomationRule.Rule,
        now: Long,
        hit: Boolean,
        blocked: String?,
        reason: String,
        action: String? = null,
        delivered: Boolean? = null,
        count: Int = 1,
        manual: Boolean = false,
    ) = AutomationStore.Fired(
        at = now,
        rule = rule.name,
        kind = rule.whenKind,
        hit = hit,
        blocked = blocked,
        reason = reason,
        action = action,
        delivered = delivered,
        count = count,
        manual = manual,
    )

    private fun record(context: Context, fired: AutomationStore.Fired) {
        runCatching { AutomationStore.appendHistory(context, fired) }
            .onFailure { warn("history", it) }
        // "真的动过"才算进冷却与每日上限: 投递失败 (队列写不进去) 的那一次不算, 下一拍还会再试
        if (fired.hit && fired.blocked == null && fired.delivered != false) {
            lastFired[fired.rule] = fired.at
            firedToday[fired.rule] = (firedToday[fired.rule] ?: 0) + 1
        }
    }

    /* ── 天气 (轮询里唯一要出门的那一条) ────────────────────────────────── */

    private fun pollWeather(context: Context, now: Long) {
        val on = settings ?: return
        val due = rules.filter { it.enabled && it.whenKind == "weather" && on.monitor("weather") }
        if (due.isEmpty()) return
        if (paused) return
        val interval = on.weatherMinutes * 60_000L
        if (lastWeatherAt != 0L && now - lastWeatherAt < interval) return
        lastWeatherAt = now
        val wanted = due.mapNotNull { it.string("place") }.distinct()
        net.execute {
            val readings = HashMap<String, WeatherNow>()
            for (name in wanted) {
                val point = places[name] ?: resolvePlace(context, name)
                if (point == null) {
                    recordUnavailable(context, due, name, "地名「$name」解析不出来")
                    continue
                }
                val reading = AutomationWeather.fetch(point.latitude, point.longitude)
                if (reading == null) {
                    recordUnavailable(context, due, name, "「$name」这一轮网不通")
                    continue
                }
                readings[name] = reading
                synchronized(weatherLock) {
                    weather = weather + (name to AutomationWeather.describe(reading))
                }
            }
            if (readings.isNotEmpty()) {
                val application = context.applicationContext
                worker.execute {
                    runCatching { evaluate(application, Trigger("weather", readings = readings)) }
                        .onFailure { warn("weather", it) }
                }
            }
        }
    }

    private fun recordUnavailable(
        context: Context,
        rules: List<AutomationRule.Rule>,
        place: String,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        rules.filter { it.string("place") == place }.forEach { rule ->
            record(context, fired(rule, now, hit = false, blocked = "unavailable", reason = reason))
        }
    }

    /** 地名 → 经纬度: 先看 `places.json`, 没有就问一次 geocoding 并记下来 */
    private fun resolvePlace(context: Context, name: String): AutomationStore.Place? {
        places[name]?.let { return it }
        val found = AutomationWeather.geocode(name) ?: return null
        runCatching { AutomationStore.rememberPlace(context, name, found.latitude, found.longitude, System.currentTimeMillis()) }
            .onFailure { warn("places", it) }
        places = places + (name to found)
        return found
    }

    /** 应用名 → 包名: 带点的当包名, 其余按启动器上的名字找一遍 (找不到就是 null) */
    private fun resolveApp(wanted: String): String? {
        if (wanted.contains('.')) return wanted
        val names = appNames ?: run {
            val map = runCatching {
                io.github.yuloong07star.luwi.channel.LwApps.launchable()
                    .associate { it.label to it.packageName }
            }.getOrDefault(emptyMap())
            appNames = map
            map
        }
        return names[wanted] ?: names.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.value
    }

    /** 启动器上那些名字, 建一次就留着 (包里换不了几个应用, 而它是一张表查询) */
    private var appNames: Map<String, String>? = null

    /* ── 给 `status` 与设置页读的几件事 ─────────────────────────────────── */

    /** 六个监测器此刻的状态 (一行一句) */
    fun monitorStates(context: Context): List<Pair<String, String>> {
        val on = settings
        // 有启用的规则、却没能挂上: 那一句要说**为什么** (缺权限 / 无障碍关着 / 网络定位关着), 而不是
        // 一句"现在没有这一类启用的规则" —— 后者在主人眼里等于"这条规则被吃了"
        val wantedKinds = rules.filter { it.enabled }.map { it.whenKind }.toSet()
        return AutomationRule.WHEN_KINDS.map { kind ->
            val enabled = on?.monitor(kind) ?: true
            val running = kind in AutomationMonitors.running()
            val why = when {
                !enabled -> "关着 (这一段整个不评估)"
                running -> AutomationMonitors.describe(context, kind)
                kind !in wantedKinds -> "现在没有这一类启用的规则"
                paused && kind in POLLING_KINDS -> "省电中, 这一阵不跑"
                else -> AutomationMonitors.describe(context, kind)
            }
            kind to why
        }
    }

    /** 设置页那一行总账 */
    fun overview(context: Context): String {
        val on = settings ?: return "自动指令还没有起来"
        val enabled = rules.count { it.enabled }
        val fired = firedToday.values.sum()
        val last = lastFired.values.maxOrNull() ?: 0L
        val said = last.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it))
        } ?: "还没有响过"
        val blocked = when {
            paused -> "省电中, 轮询类这一阵不跑"
            on.quietEnabled && quietWindow() != null &&
                PowerWindow.inside(PowerWindow.minutesOfDay(System.currentTimeMillis()), quietWindow()!!) ->
                "静默时段中"

            else -> "没什么拦着它"
        }
        val saidToday = AutomationStore.saidToday(context)
        return "规则 $enabled 条 (共 ${rules.size} 条) · 今天响了 $fired 次" +
            " · 今天主动说了 $saidToday/${on.saidLimit} 次 · 上一次 $said · $blocked"
    }

    /** 现在是不是静默时段 (给 `status` 与设置页那一行) */
    fun quietNow(context: Context): Boolean {
        val on = settings ?: return false
        if (!on.quietEnabled) return false
        val window = quietWindow() ?: return false
        return PowerWindow.inside(PowerWindow.minutesOfDay(System.currentTimeMillis()), window)
    }

    fun pausedNow(): Boolean = paused

    fun lastWeather(): Map<String, String> = synchronized(weatherLock) { weather }

    /** 这条规则上一次真的动过是什么时候 (0 / null = 还没有) */
    fun lastFiredAt(rule: String): Long? = lastFired[rule]?.takeIf { it > 0 }

    /** 今天这条动了几次 */
    fun firedTodayCount(rule: String): Int = firedToday[rule] ?: 0

    /** 今天主动说了几次 (设置页「探索」段那一行读数) */
    fun saidToday(context: Context): Int = AutomationStore.saidToday(context)

    /**
     * 设置页那个「立刻跑一次」: 不走条件, 也不走冷却与静默 —— 主人自己点的那一下就是"现在做"
     *
     * 只留一道闸: `task` 那一种仍要「允许它自己动手」开着 (它与"点一下看看"不是一回事, 会真的动手机)
     */
    fun fireNow(context: Context, name: String): String {
        val application = context.applicationContext
        refresh(application, force = false)
        val rule = rules.firstOrNull { it.name == name } ?: return "这条规则读不出来, 没投出去"
        if (rule.thenKind == "task" && settings?.allowActing != true) {
            return "这条要动手, 而「允许它自己动手」关着"
        }
        val seq = VoiceInbox.append(application, prompt(rule), source = VoiceInbox.SOURCE_AUTOMATION)
        val now = System.currentTimeMillis()
        record(
            application,
            fired(
                rule = rule,
                now = now,
                hit = true,
                blocked = null,
                reason = "手动跑了一次",
                action = rule.thenKind,
                delivered = seq != null,
                manual = true,
            ),
        )
        return if (seq == null) "队列写不进去, 没投出去" else "投出去了, 它会在新开的一场会话里做"
    }

    /** `$DSH_HOME/automations` 在哪 */
    fun directory(context: Context): File = AutomationStore.directory(context)

    private fun quietWindow(): PowerWindow.Window? =
        settings?.let { PowerWindow.parse(it.quietWindow)?.takeIf { window -> !window.empty } }

    private fun timeZone() = java.time.ZoneId.systemDefault()

    private fun dayOfWeek(now: Long): Int =
        java.time.Instant.ofEpochMilli(now).atZone(timeZone()).dayOfWeek.value

    private fun metricNameOf(metric: String): String = when (metric) {
        "temperature" -> "气温"
        "precipitation" -> "降水量"
        else -> "天气代码"
    }

    private fun trim(value: Double): String = when {
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> "%.1f".format(value)
    }

    private fun warn(where: String, problem: Throwable) {
        Log.w(TAG, "$where failed: ${problem.message ?: problem.javaClass.simpleName}", problem)
    }
}
