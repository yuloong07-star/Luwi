package io.github.yuloong07star.luwi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她还在出声吗」那条判据的算术 ([SpeechTail])
 *
 * 为什么值得单测: 这条判据的**唯一一条错法**很难在设备上看见 —— 它不会崩, 只是"别的应用一放音乐,
 * 球上就写正在说" (2026-10-16 主人报的那条), 而这类错只会在偶然的组合里出现。所以三个输入
 * (自带/在线那两条的播放标记、系统那条的 utterance 与预算、带我们那份属性的活跃播放) 的每一种组合
 * 都在这儿钉死
 */
class SpeechTailTest {

    /** 预算: 350 ms/字 + 4 s, 封顶 25 s */
    @Test
    fun `预算按字数算, 并且有上限`() {
        assertEquals(4_000L, SpeechTail.budgetMs(""))
        assertEquals(4_350L, SpeechTail.budgetMs("一"))
        assertEquals(4_000L + 40 * 350L, SpeechTail.budgetMs("一".repeat(40)))
        // 上限: 长回答也封在 25 s (100 个字符本来是 39 s)
        assertEquals(25_000L, SpeechTail.budgetMs("一".repeat(100)))
    }

    @Test
    fun `自带与在线那两条, 只看进程内的播放标记`() {
        val now = 1_000_000L
        // 在放就是在说
        assertTrue(SpeechTail.holding(now, enginePlaying = true, false, 0L, ownPlayback = false))
        // 停了就是停了 —— **哪怕预算窗还开着** (那是给系统那条留的)。这两条的 ownPlayback 在生产里
        // 永远是 false (`LwSpeak.talking` 那一行是 `lastWasSystem && SystemSpeech.playing`), 所以这
        // 一条量的是"预算窗不能把自带/在线那两条留成在说"
        assertFalse(SpeechTail.holding(now, enginePlaying = false, false, now + 10_000L, ownPlayback = false))
    }

    @Test
    fun `系统那条, 挂着 utterance 就算在说`() {
        val now = 1_000_000L
        assertTrue(SpeechTail.holding(now, enginePlaying = false, utterancePending = true, tailUntil = 0L, ownPlayback = false))
    }

    @Test
    fun `系统那条的尾巴窗, 预算没过期而且我们那一份还在放才算`() {
        val now = 1_000_000L
        val tail = now + 5_000L
        // 引擎报了结束, 而喇叭还在响 (onDone 比真实放音早 —— 这条 bug 的根)
        assertTrue(SpeechTail.holding(now, false, false, tail, ownPlayback = true))
        // 引擎报了结束, 我们自己没在放 —— 别人在放不算 (这就是 2026-10-16 修掉的那条)
        assertFalse(SpeechTail.holding(now, false, false, tail, ownPlayback = false))
        // 预算到点了: 我们那份还挂着也不认 (上限, 免得麦克风被摁住)
        assertFalse(SpeechTail.holding(tail, false, false, tail, ownPlayback = true))
        assertFalse(SpeechTail.holding(tail + 1, false, false, tail, ownPlayback = true))
        // 预算过期之后, 引擎真在放还是算
        assertTrue(SpeechTail.holding(tail + 1, enginePlaying = true, false, tail, ownPlayback = false))
    }

    @Test
    fun `一句都没在说的时候三种输入都是假`() {
        assertFalse(SpeechTail.holding(5L, false, false, 0L, ownPlayback = false))
    }

    /**
     * 我们那一份属性的判据只认那两半
     *
     * **只拿两个 Int 测**: 单元测试里 new 一个 `AudioAttributes` 会撞上 android.jar 的桩 (`Stub!`),
     * 而这条匹配规则是整件事里最该被钉住的一段 (它错了就是"别人的音乐被认成她还在说")
     */
    @Test
    fun `属性匹配只认助手场景加语音内容`() {
        assertTrue(SystemSpeech.ours(SystemSpeech.USAGE, SystemSpeech.CONTENT_TYPE))
        // 别的应用的音乐 (USAGE_MEDIA + CONTENT_TYPE_MUSIC) 不在我们这一份里
        assertFalse(SystemSpeech.ours(1, 2))
        // 只对上其中一半也不算
        assertFalse(SystemSpeech.ours(SystemSpeech.USAGE, 2))
        assertFalse(SystemSpeech.ours(1, SystemSpeech.CONTENT_TYPE))
    }
}
