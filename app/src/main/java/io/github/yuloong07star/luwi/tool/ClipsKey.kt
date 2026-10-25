package io.github.yuloong07star.luwi.tool

import java.util.Locale

/**
 * 垫音跟随的那个指纹: 音色 + 语速拼成一个串, **只吃参数、不碰 Android**
 *
 * 为什么要单独一个对象: [LwClips] 那份 object 一初始化就建 `SoundPool` 与 `Handler` (要真设备),
 * JVM 单测里碰不到它 —— 而"改了声色就该重合成、没改就不该重合成"这条判据必须能单测 (见
 * `ClipsFollowTest`), 所以判据本身抽在这里
 *
 * 语速固定两位小数: 滑杆给的是 Float, 0.9 与 0.8999999 必须算同一个, 否则拖一下就重合成一遍
 */
internal object ClipsKey {

    fun of(voice: String, rate: Float): String =
        "${voice.ifBlank { "default" }}|${String.format(Locale.US, "%.2f", rate)}"
}
