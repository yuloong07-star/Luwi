package io.github.yuloong07star.luwi.voice

/**
 * 一句话该投给哪一场: 两条来源的优先级只有这一处说得清
 *
 * 纯函数 (没有设备也能量, 见 `VoiceRouteTest`), 因为"挑会话"这件事有一堆真机上才看得出来的错法:
 * 说出去的话落进别的一场、或者落进一个已经被收掉的会话
 *
 * 两条来源:
 *
 * - **视频模式定格的那一场** ([videoTarget]): 进视频模式那一刻页面正在看的那一场
 *   (`VoiceState.uiSession` → `LwWakeWord.resident` 写到 `modes/video-voice.session`) —— 主人
 *   2026-10-07 的口径是"打开视频模式那句话与视频模式里接着说进的话要落在同一场"
 * - **回复框点名的那一场** ([boxTarget]): 框在屏上、里面有回复时的"接着回答这一场" (2026-10-06)
 *
 * 都没有时回 null, 由宿主那侧按 10 分钟那一笔账挑 (或者开一场新的)
 */
internal object VoiceRoute {

    /**
     * 视频模式常驻时**定格优先**, 其余时候看回复框点名
     *
     * 为什么定格压过回复框: 主人点名要的是"固定投给进入时那一场", 而回复框那条例外说的是"接着回答
     * 那一场" —— 两者不一致时以定格为准, 否则视频模式里的对话又会分到两场去
     *
     * @param boxTarget 回复框点名的那一场 (没有就是 null)
     * @param videoTarget 视频模式定格的那一场 (没有就是 null)
     * @param videoResident 那一路现在是不是视频模式的常驻档
     */
    fun target(boxTarget: String?, videoTarget: String?, videoResident: Boolean): String? {
        if (videoResident) videoTarget?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return boxTarget?.trim()?.takeIf { it.isNotEmpty() }
    }
}
