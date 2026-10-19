package io.github.yuloong07star.luwi.voice

import android.content.Context
import android.content.SharedPreferences

/**
 * 语音模块这两个设置 (2.7.9 批次 9): 断句的静音长度 (T15) 与状态出口 (T17)
 *
 * 两个都落 app 自己的偏好文件 (`luwi`, 与 [io.github.yuloong07star.luwi.tool.SpeakSettings] 同一个),
 * 因为写它们的与读它们的是同一侧: 设置页写、`WakeWordService` 与 [VoiceStateProvider] 读
 *
 * **静音长度缺省不动**: 0.8 s 是主人 2026-10-05 量过之后定的数 (施工单 §8.2 "别动缺省值") ——
 * 这里给的档只让想更快的人调小, 代价是正常的停顿时可能被切成两句
 *
 * **状态出口缺省关, 而且必须点名一个包**: 语音状态能看出"主人正在说话", 这两道闸都不能省
 * (施工单 §8.7 第二条)。包名写得不像包名时**整条出口也等于关着** —— 与其发出去一份没人认领的
 * 状态, 不如什么都不发, 读数里那句 `exportTarget` = null 就是答案
 */
object VoiceSettings {

    /** 与 `SpeakSettings` 同一个偏好文件, 全 app 一个 */
    private const val STORE = "luwi"

    private const val SILENCE_KEY = "voice-vad-silence"
    private const val EXPORT_KEY = "voice-export"
    private const val EXPORT_PACKAGE_KEY = "voice-export-package"

    /**
     * 静音多长算一句说完: 两端与缺省
     *
     * 缺省取 [SpeechSegmenter.MIN_SILENCE_SECONDS] (0.8 s, 量过的那个数), 不在这里再写一遍
     */
    val silenceRange: ClosedFloatingPointRange<Float> = 0.5f..1.5f

    const val DEFAULT_SILENCE: Float = SpeechSegmenter.MIN_SILENCE_SECONDS

    /** 吸附点: 0.5 是"最快", 0.8 是缺省, 1.5 是"最有耐心" */
    val silenceKeyPoints: List<Float> = listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f)

    /** 离吸附点多近算吸住 (与 Miuix 的 `magnetThreshold` 一个算法) */
    const val MAGNET = 0.03f

    /** 包名的形状: 至少两段, 每段字母或下划线开头 (系统自己那几条规则里最要紧的部分) */
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    // ── T15 · 断句 ────────────────────────────────────────────────────────

    /** 现在用多长的静音收尾 (已经夹进 [silenceRange]; 老版本存坏的值也不会带出去) */
    fun silence(context: Context): Float =
        clamped(preferences(context).getFloat(SILENCE_KEY, DEFAULT_SILENCE))

    /** 收下一个新值: 夹进那一档再存 (存的是带一位小数的秒数) */
    fun setSilence(context: Context, value: Float) {
        preferences(context).edit().putFloat(SILENCE_KEY, clamped(value)).apply()
    }

    /** 把任意一个数收进合法档位: 非数 / 越界都回缺省的两端, 不让它把 VAD 带坏 */
    internal fun clamped(value: Float): Float = when {
        !value.isFinite() -> DEFAULT_SILENCE
        value < silenceRange.start -> silenceRange.start
        value > silenceRange.endInclusive -> silenceRange.endInclusive
        else -> value
    }

    // ── T17 · 状态出口 ────────────────────────────────────────────────────

    /** 出口总开关 (缺省关) */
    fun exportEnabled(context: Context): Boolean =
        preferences(context).getBoolean(EXPORT_KEY, false)

    fun setExportEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(EXPORT_KEY, value).apply()
    }

    /** 设置页里填的包名 (原样的 trim; 没填就是空串 = 整条关闭) */
    fun exportPackage(context: Context): String =
        preferences(context).getString(EXPORT_PACKAGE_KEY, "").orEmpty().trim()

    fun setExportPackage(context: Context, value: String) {
        preferences(context).edit().putString(EXPORT_PACKAGE_KEY, value.trim()).apply()
    }

    /**
     * 现在真的往哪儿发: 开关开着、点了名、而且那个名字像一个包名, 才回那一个; 否则 null
     *
     * 广播与 provider 都只认这一个答案 —— 于是"开关开着而包名写错"不会留下一半开着的一扇门,
     * 设置页上那句状态与真的发不发也是同一份判据
     */
    fun exportTarget(context: Context): String? {
        if (!exportEnabled(context)) return null
        val named = exportPackage(context)
        return named.takeIf { PACKAGE.matches(it) }
    }

    /** 包名写得对不对 (设置页要据此说一句; 空串不算错, 那只是"还没填") */
    internal fun packageLooksValid(raw: String): Boolean = PACKAGE.matches(raw.trim())

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
