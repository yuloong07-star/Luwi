package io.github.yuloong07star.luwi.tool

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.yuloong07star.luwi.automation.AutomationRule
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.HostStatus
import io.github.yuloong07star.luwi.voice.VoiceInbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * 探索的排期 (2.7.8 批次 8): **每天一个时刻, 到点自己开始**
 *
 * 主人 2026-10-17 的口径: "探索应该是定时的由用户决定开始时间，不要手动开始探索" —— 所以设置页没有
 * 任何"现在开一场"的入口, 应用里只有这一条产品级入口:
 *
 * 1. 主人设定 `HH:mm` 与总开关 (缺省关)
 * 2. [arm] 把下一次排进 `AlarmManager` (精确优先, 退 `setAndAllowWhileIdle`)
 * 3. 到点 [fire]: 宿主没在跑 / 省电 / 上一场还在跑 → 跳过并记读数; 否则读 [ExploreTask] 的卡
 *    (空就是"自由探索这台手机"), 写一行收件箱 (`source:"explore"`) —— 宿主那边照批次 7 的
 *    `startExplore` 开一场, 预算 / `Luwi` 工作区 / `explore` 预设都不变
 * 4. 不管跑没跑, 都写一份 `last` 读数并重排明天
 *
 * 配置与读数都落在 `$DSH_HOME/lw/explore-schedule.json` (应用单写者); `explore.json` 是宿主的账,
 * 这里只读 [ExploreTask.running]
 */
internal object ExploreSchedule {

    private const val TAG = "LwExplore"

    const val DIRECTORY = "lw"
    const val FILE = "explore-schedule.json"

    /** 缺省时刻: 夜里 (探索本身不发声, 不打扰人) */
    const val DEFAULT_AT = "03:00"

    /** 与自动指令那个闹钟 (41) 分开, 两边互不覆盖 */
    private const val REQUEST = 42

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-explore-schedule").apply { isDaemon = true }
    }

    /** 上一次排期的那一趟 (给人看的三个值) */
    data class Last(val at: Long, val outcome: String, val why: String?)

    /** 排期配置: 开没开 / 每天几点 / 上一趟 */
    data class Plan(val enabled: Boolean, val at: String, val last: Last?)

    fun file(context: Context): File = File(File(context.filesDir, DshHost.HOME_DIR), "$DIRECTORY/$FILE")

    /** 现在这份配置 (读不到 / 读坏了都按缺省: 关着, 03:00) */
    fun plan(context: Context): Plan {
        val raw = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()
            ?: return Plan(false, DEFAULT_AT, null)
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return Plan(false, DEFAULT_AT, null)
        return planOf(root)
    }

    /** 一份 JSON -> 配置 (纯函数; 坏 `at` 退回缺省, 不认识的键不看) */
    fun planOf(root: JsonObject): Plan {
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
        val asked = root["at"]?.jsonPrimitive?.contentOrNull
        val at = if (asked != null && AutomationRule.minutesOf(asked) != null) asked else DEFAULT_AT
        val last = (root["last"] as? JsonObject)?.let { one ->
            Last(
                at = one["at"]?.jsonPrimitive?.longOrNull ?: 0L,
                outcome = one["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                why = one["why"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return Plan(enabled, at, last)
    }

    /** 写配置 + 重排闹钟 (单写者, 原子写) */
    fun save(context: Context, plan: Plan) = synchronized(lock) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val root = buildJsonObject {
            put("v", 1)
            put("updatedAt", System.currentTimeMillis())
            put("enabled", plan.enabled)
            put("at", plan.at)
            plan.last?.let { last ->
                put("last", buildJsonObject {
                    put("at", last.at)
                    put("outcome", last.outcome)
                    last.why?.let { put("why", it) }
                })
            }
        }
        val text = json.encodeToString(JsonObject.serializer(), root) + "\n"
        val tmp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "the explore schedule was not written", it) }
        arm(context)
    }

    /**
     * 下一次该响的时刻 (epoch 毫秒): 今天还没到就今天, 否则明天
     *
     * 纯函数, 单测钉着; `at` 认不出来就是 null
     */
    fun nextAt(at: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val minutes = AutomationRule.minutesOf(at) ?: return null
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val today = date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
        if (today > now) return today
        return date.plusDays(1).atStartOfDay(zone)
            .plusMinutes(minutes.toLong())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * 到点这一趟要不要跳过 (纯函数): 跳过的原因 (中文) 或 null
     *
     * 顺序就是"先看宿主活没活, 再看省电, 最后看上一场": 三条都成立时报最靠前的那一条
     */
    fun skipReason(hostRunning: Boolean, powerSave: Boolean, running: Boolean): String? = when {
        !hostRunning -> "宿主没在跑"
        powerSave -> "现在在省电模式"
        running -> "上一场探索还在跑"
        else -> null
    }

    /** 宿主起来 / 设置页改完时叫一次: 开着就排, 关着就撤 */
    fun ensure(context: Context) {
        arm(context)
    }

    /**
     * 按当前配置排下一次 (关着就撤销)
     *
     * 精确档拿不到时退 `setAndAllowWhileIdle` —— 与自动指令那个闹钟同一个退路
     */
    fun arm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = intent(context)
        val current = plan(context)
        if (!current.enabled) {
            manager.cancel(pending)
            return
        }
        val next = nextAt(current.at, System.currentTimeMillis()) ?: return
        val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            }
        }.onFailure { Log.w(TAG, "the explore alarm could not be armed", it) }
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, ExploreAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 接收器那条: 放到自己的工作线程上做, 不占主线程 (做完才 finish goAsync) */
    fun fireAsync(context: Context, done: () -> Unit) {
        val application = context.applicationContext
        worker.execute {
            try {
                fire(application, System.currentTimeMillis())
            } catch (problem: Throwable) {
                Log.w(TAG, "the scheduled exploration could not be fired", problem)
            } finally {
                done()
            }
        }
    }

    /**
     * 到点那一趟: 决定跑不跑, 跑就写一行收件箱, 然后记 `last` 并重排明天
     *
     * 那行 `why` 是给人看的 (设置页那行"上一次"与回执都读它), 与 `history.jsonl` 的 `reason` 同一种写法
     */
    private fun fire(context: Context, now: Long) {
        val current = plan(context)
        if (!current.enabled) return
        val skip = skipReason(
            hostRunning = DshHost.status is HostStatus.Running,
            powerSave = LwWakeWord.powerSave(context),
            running = ExploreTask.running(context, now) != null,
        )
        if (skip != null) {
            Log.i(TAG, "the scheduled exploration at ${current.at} was skipped: $skip")
            save(context, current.copy(last = Last(now, "skipped", skip)))
            return
        }
        val card = ExploreTask.read(context)
        val text = card.ifEmpty { ExploreTask.FALLBACK_TEXT }
        val seq = VoiceInbox.append(context, text, source = VoiceInbox.SOURCE_EXPLORE)
        if (seq == null) {
            Log.w(TAG, "the scheduled exploration could not be queued")
            save(context, current.copy(last = Last(now, "failed", "收件箱写不进去")))
            return
        }
        val why = if (card.isEmpty()) "卡是空的, 按自由探索投的" else null
        Log.i(TAG, "the scheduled exploration was queued as line #$seq (card ${card.length} chars)")
        save(context, current.copy(last = Last(now, "started", why)))
    }

    /** 设置页那行读数用: `started` / `skipped` / `failed` -> 一句中文 */
    fun describeLast(last: Last): String = when (last.outcome) {
        "started" -> "开始了一场探索"
        "skipped" -> "没跑: ${last.why.orEmpty()}"
        "failed" -> "没投出去: ${last.why.orEmpty()}"
        else -> last.outcome
    }
}

/**
 * 探索排期的闹钟广播 (与自动指令那个接收器分开, 两个 requestCode 互不覆盖)
 *
 * `exported=false`: 只可能被本应用自己的 `PendingIntent` 叫到; 系统在进程不在时也会把进程拉起来送
 * 这一条, 所以"到点了"这件事不依赖界面开着
 */
class ExploreAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        ExploreSchedule.fireAsync(context) { pending.finish() }
    }
}
