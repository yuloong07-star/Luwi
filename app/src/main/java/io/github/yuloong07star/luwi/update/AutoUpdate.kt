package io.github.yuloong07star.luwi.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.LocalDate

/**
 * 冷启动那一次自动查更新 (2.7.9 第 3 条)
 *
 * **与手动那条的区别只有两件事**: 一天最多查一次 (GitHub 未授权的 60/h 额度), 以及查到了自己弹一次
 * —— 该不该弹的判据全在 [shouldCheck] 那张真值表里, 这一份负责取数 / 记账 / 把结论交出去
 *
 * **不碰 [UpdateCheck] 那份单例**: 它那三个 `phase` / `release` / `detail` 是设置页手动那一趟的账,
 * 自动这一趟写同一份的话, 人打开「检测更新」看到的就是自动查的旧结果 (施工单 F2)。所以自动这条自己
 * 出一份 ([AutoUpdatePrompt]), 与手动那条共用的只有 [UpdateCheck.localVersionOf] 这个纯读数
 *
 * **失败一律静默**: 网络不通 / 版本号认不出来都只落一行读数 ([KEY_LAST]), 不弹任何东西 —— 手动那条
 * 还在, 想查的人自己点
 *
 * 三个键 (与施工单 §2.1 那张表一字不差):
 *
 * | 键 | 取值 | 含义 |
 * | :-- | :-- | :-- |
 * | `update-checked-day` | `2026-10-19` | 哪一天查过 (一天最多一次) |
 * | `update-skip` | 远端 `versionName` | 人点了「不再提示」的那一版 |
 * | `update-last` | 一行 JSON | 查的时间 / 结果 / 远端版本, 给排查用 |
 */
internal object AutoUpdate {

    /** 这一趟的结论; 只有 [SHOW] 会弹 */
    enum class Verdict {
        /** 有新版本, 该弹 */
        SHOW,

        /** 今天已经查过 */
        SKIP_TODAY,

        /** 没拿到结果 (网络不通 / 那份回答不是 Release) */
        SKIP_NO_REMOTE,

        /** 拿到的版本不比手上新 */
        SKIP_NOT_NEWER,

        /** 这一版人点过「不再提示」 */
        SKIP_SKIPPED,
    }

    const val KEY_CHECKED_DAY = "update-checked-day"
    const val KEY_SKIP = "update-skip"
    const val KEY_LAST = "update-last"

    /** 与设置页那些偏好同一个文件 */
    private const val STORE = "luwi"
    private const val TAG = "LwAutoUpdate"

    /**
     * 该不该查 / 该不该弹 (施工单 §2.1 那张真值表)
     *
     * 顺序就是优先级: 今天查过 > 没有结果 > 不比手上新 > 点过不再提示 > 弹。**`skip` 只在"确实新"之后
     * 才看** —— 于是同一版被忽略之后, 再出一版新的仍然会弹
     */
    fun shouldCheck(
        lastDay: String?,
        skip: String?,
        remote: ReleaseJson.Release?,
        local: String,
        today: String,
    ): Verdict {
        if (lastDay == today) return Verdict.SKIP_TODAY
        if (remote == null) return Verdict.SKIP_NO_REMOTE
        if (!ReleaseJson.newer(remote.version, local)) return Verdict.SKIP_NOT_NEWER
        if (skip != null && skip == remote.version) return Verdict.SKIP_SKIPPED
        return Verdict.SHOW
    }

    /** 这一趟查完的结论 (只给日志与读数, 界面不读它 —— 界面读的是 [AutoUpdatePrompt]) */
    data class Outcome(val verdict: Verdict, val release: ReleaseJson.Release?, val detail: String)

    /** 读写那三个键的最小面: 真机上是 SharedPreferences 那一份, 单测上是内存表 */
    interface Prefs {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    /**
     * 查一次并把读数写下去, 重活交给 [fetch] (它抛异常就是"没查到")
     *
     * 顺序是"先看今天查过没有, 再取数" —— 反过来的话每天第一次之外还会白打一次网络。
     *
     * **失败也记这一天的账**: 那个记号是给限流用的, 不是"这次一定查到了"; 失败只落读数, 不弹任何东西
     * (一天最多打一次, 就算答的是 403 也不会越试越凶)
     */
    fun once(
        prefs: Prefs,
        local: String,
        today: String,
        at: Long,
        fetch: () -> ReleaseJson.Release,
    ): Outcome {
        val lastDay = prefs.get(KEY_CHECKED_DAY)
        if (lastDay == today) return Outcome(Verdict.SKIP_TODAY, null, "今天已经查过 ($today)")
        val remote = try {
            fetch()
        } catch (error: Throwable) {
            prefs.put(KEY_CHECKED_DAY, today)
            prefs.put(KEY_LAST, line(at, "failed", null, error.message ?: error.toString()))
            return Outcome(Verdict.SKIP_NO_REMOTE, null, "没查到: ${error.message ?: error}")
        }
        prefs.put(KEY_CHECKED_DAY, today)
        val verdict = shouldCheck(lastDay, prefs.get(KEY_SKIP), remote, local, today)
        prefs.put(KEY_LAST, line(at, resultOf(verdict), remote.version, null))
        return Outcome(verdict, remote, "")
    }

    /** 真机那一趟: 取数走 [ReleaseFetch], 结论交给 [AutoUpdatePrompt] */
    fun run(context: Context) {
        val local = UpdateCheck.localVersionOf(context)
        val outcome = once(prefs(context), local, LocalDate.now().toString(), System.currentTimeMillis()) {
            ReleaseFetch.latest(context)
        }
        val note = if (outcome.detail.isEmpty()) "" else " (${outcome.detail})"
        Log.i(TAG, "auto update: ${outcome.verdict}$note")
        val release = outcome.release
        if (outcome.verdict == Verdict.SHOW && release != null) {
            AutoUpdatePrompt.show(release, local)
        }
    }

    /** 「不再提示这次版本」: 记下远端那个版本号, 它不再是"新的"之前都不再弹 */
    fun skipVersion(context: Context, version: String) {
        prefs(context).put(KEY_SKIP, version)
    }

    /** 读数里那一栏: `newer` / `latest` / `skipped` / `failed` */
    private fun resultOf(verdict: Verdict): String = when (verdict) {
        Verdict.SHOW -> "newer"
        Verdict.SKIP_SKIPPED -> "skipped"
        Verdict.SKIP_NOT_NEWER -> "latest"
        else -> "failed"
    }

    /** 读数那一行: 键就这四个, 一行 JSON, 坏了解析也不影响任何功能 (只给排查看) */
    private fun line(at: Long, result: String, remote: String?, detail: String?): String =
        """{"at":$at,"result":"$result","remote":${quote(remote)},"detail":${quote(detail)}}"""

    /** 只处理这一行里会出现的两种转义 (引号 / 反斜杠) 与换行, 不值当引一份 JSON 编码器 */
    private fun quote(text: String?): String {
        if (text == null) return "null"
        val flat = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
        return "\"$flat\""
    }

    private fun prefs(context: Context): Prefs =
        SharedPrefs(context.getSharedPreferences(STORE, Context.MODE_PRIVATE))

    /** 真机那一份: 三个键都是字符串, 读写各一行 */
    private class SharedPrefs(private val delegate: SharedPreferences) : Prefs {

        override fun get(key: String): String? = delegate.getString(key, null)

        override fun put(key: String, value: String) {
            delegate.edit().putString(key, value).apply()
        }
    }
}
