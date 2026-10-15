package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration

/**
 * 系统那条 TTS 引擎的"我还在不在出声"—— 靠**我们给它的一份专属属性**去认
 *
 * 病根: 系统引擎的播放器在引擎自己的进程里 (这台机器上是 vivo 的 AIService), 应用这一侧看不到它
 * 什么时候真的停 —— 而它的 `onDone` 常常在"合成完了"就报, 喇叭里还在放。原来拿
 * `AudioManager.isMusicActive()` 顶这一段, 那是**全系统 STREAM_MUSIC 的活跃度**: 不看是谁在放, 于是
 * 别的应用一放音乐/视频就把「正在说」带起来 (2026-10-16 主人报的那条)
 *
 * 换成这一条: 念之前给引擎 [attributes] (usage=USAGE_ASSISTANT, contentType=CONTENT_TYPE_SPEECH), 之后
 * 用 `getActivePlaybackConfigurations()` (公开 API 26+, 无需权限) 找"带这份属性的活跃播放" ——
 * 非特权调用者拿到的那一份**保留 attributes** (被抹掉的是 uid / pid / 包名), 所以这个做法成立
 *
 * 两条边界写清楚:
 * - **属性不被采用时这一条会一直回 false** —— 那是好事: 退化路径是"只按预算" ([SpeechTail]), 而
 *   **绝不回退到 `isMusicActive`**, 否则这条 bug 就回来了。真机实测见 `speak op=probe`
 * - 别的助手类应用理论上也用 `USAGE_ASSISTANT + CONTENT_TYPE_SPEECH` (概率很低), 而**预算那条上限一直
 *   在**, 所以最坏情况与今天一样, 不会更差
 */
internal object SystemSpeech {

    /** 给引擎那份属性的两半: 助手场景 + 语音内容 */
    const val USAGE = AudioAttributes.USAGE_ASSISTANT
    const val CONTENT_TYPE = AudioAttributes.CONTENT_TYPE_SPEECH

    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(USAGE)
        .setContentType(CONTENT_TYPE)
        .build()

    /**
     * 这两半是不是"我们的那一份"
     *
     * 写成吃两个 `Int` 的那一份是**为了能单测**: 单元测试里 new 一个 `AudioAttributes` 会撞上
     * android.jar 的桩 (`Stub!`), 而这条匹配规则恰恰是整件事里最该被钉住的一段
     */
    fun ours(usage: Int, contentType: Int): Boolean = usage == USAGE && contentType == CONTENT_TYPE

    /** 这一条活跃播放是不是"我们的那一份" */
    fun ours(candidate: AudioAttributes?): Boolean =
        candidate != null && ours(candidate.usage, candidate.contentType)

    /** 活跃播放里有几条是我们那一份 (0 就是"没在放") */
    fun matches(context: Context): Int = active(context).count { ours(it.audioAttributes) }

    fun active(context: Context): List<AudioPlaybackConfiguration> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return runCatching { audio.activePlaybackConfigurations }.getOrNull()?.filterNotNull().orEmpty()
    }

    /** 这一个应用进程现在算不算"系统引擎还在出声" */
    fun playing(context: Context): Boolean = matches(context) > 0

    /** 每一条活跃播放的属性: 读数与 T0 探针用 (`usage=… contentType=…`) */
    fun snapshot(context: Context): List<String> = active(context).map { configuration ->
        val attributes = configuration.audioAttributes
        if (attributes == null) {
            "usage=? contentType=? (no attributes)"
        } else {
            "usage=${attributes.usage} contentType=${attributes.contentType}"
        }
    }

    /** 我们那一份属性的一句话说明 */
    fun describe(): String = "usage=$USAGE contentType=$CONTENT_TYPE"
}
