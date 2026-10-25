package io.github.yuloong07star.luwi

import io.github.yuloong07star.luwi.tool.ClipsKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 垫音跟随的那个指纹 (见 `LwClips.follow`): 音色或语速一变就必须不一样
 *
 * 它是"要不要重新合成"的**唯一判据** —— 写错了的形态有两种, 都在这里钉住: 变了却没发现 (一直放旧的
 * 声音), 或者没变却反复重合成 (每问一句就联网合成一遍)
 */
class ClipsFollowTest {

    @Test
    fun voiceAndRateMakeTheKey() {
        assertEquals("zh-CN-YunxiaNeural|0.90", ClipsKey.of("zh-CN-YunxiaNeural", 0.9f))
        assertEquals("zh-CN-XiaoxiaoNeural|1.00", ClipsKey.of("zh-CN-XiaoxiaoNeural", 1f))
    }

    @Test
    fun blankVoiceFallsBackToAWord() {
        // 空音色 = 用引擎自己的缺省, 那一份与任何点名音色都不是一回事
        assertEquals("default|1.00", ClipsKey.of("", 1f))
        assertEquals("default|1.00", ClipsKey.of("   ", 1f))
    }

    @Test
    fun aChangedVoiceChangesTheKey() {
        assertNotEquals(
            ClipsKey.of("zh-CN-YunxiaNeural", 1f),
            ClipsKey.of("zh-CN-YunyangNeural", 1f),
        )
    }

    @Test
    fun aChangedRateChangesTheKey() {
        assertNotEquals(ClipsKey.of("zh-CN-YunxiaNeural", 0.9f), ClipsKey.of("zh-CN-YunxiaNeural", 1f))
    }

    @Test
    fun theSameSettingsGiveTheSameKey() {
        // 同一个数反复读: 不能每次都算出一个新串, 否则每一趟都重合成
        assertEquals(
            ClipsKey.of("zh-CN-YunxiaNeural", 0.9f),
            ClipsKey.of("zh-CN-YunxiaNeural", 0.9f),
        )
    }

    @Test
    fun theRateIsRoundedToTwoDecimals() {
        // 滑杆给的数是 Float, 0.8999999 与 0.9 必须算同一个 (否则拖一下就重合成一次)
        assertEquals(ClipsKey.of("v", 0.9f), ClipsKey.of("v", 0.9000001f))
        // 但差得多一点就要分开
        assertNotEquals(ClipsKey.of("v", 0.9f), ClipsKey.of("v", 0.95f))
    }
}
