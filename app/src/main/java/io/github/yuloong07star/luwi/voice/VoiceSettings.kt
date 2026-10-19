package io.github.yuloong07star.luwi.voice

import android.content.Context
import android.content.SharedPreferences

/**
 * 语音输入那个参数: 静音多长算一句说完 (2.7.9 批次 9 T15)
 *
 * 落在 app 自己的偏好文件 (`luwi`, 与 [io.github.yuloong07star.luwi.tool.SpeakSettings] 同一个),
 * 因为写它的是设置页、读它的是 `WakeWordService` —— 同一侧
 *
 * **缺省不动**: 0.8 s 是主人 2026-10-05 量过之后定的数 (施工单 §8.2 "别动缺省值") —— 这里给的档
 * 只让想更快的人调小, 代价是正常的停顿时可能被切成两句
 *
 * 语音状态那几条出口 (文件 / 只读 provider) **没有开关**: 主人 2026-10-20 定的口径是"默认打开,
 * 使脚本能检测到", 所以这里没有第二个设置
 */
object VoiceSettings {

    /** 与 `SpeakSettings` 同一个偏好文件, 全 app 一个 */
    private const val STORE = "luwi"

    private const val SILENCE_KEY = "voice-vad-silence"

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

    /** 现在用多长的静音收尾 (已经夹进 [silenceRange]; 老版本存坏的值也不会带出去) */
    fun silence(context: Context): Float =
        clamped(preferences(context).getFloat(SILENCE_KEY, DEFAULT_SILENCE))

    /** 收下一个新值: 夹进那一档再存 */
    fun setSilence(context: Context, value: Float) {
        preferences(context).edit().putFloat(SILENCE_KEY, clamped(value)).apply()
    }

    /** 把任意一个数收进合法档位: 非数 / 越界都回缺省, 不让它把 VAD 带坏 */
    internal fun clamped(value: Float): Float = when {
        !value.isFinite() -> DEFAULT_SILENCE
        value < silenceRange.start -> silenceRange.start
        value > silenceRange.endInclusive -> silenceRange.endInclusive
        else -> value
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
