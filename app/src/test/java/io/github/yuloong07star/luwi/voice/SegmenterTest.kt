package io.github.yuloong07star.luwi.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静音长度那一档 (T15) 与包名判据 (T17) 的纯算术
 *
 * 真正的断句要用真机上的 mic + silero (`SpeechSegmenter` 是原生那一侧的事), 单测能钉住的是**输入
 * 那一侧**: 0.8 是缺省、越界被夹、非数不把 VAD 带坏。这一条很值得钉 —— 存坏一个数进去, 症状是
 * "有时候一句话永远不出" (静音比说话还长) 或者"半句话就发出去", 两种都不好查
 */
class SegmenterTest {

    @Test
    fun theDefaultIsTheOneThatWasMeasured() {
        // 主人 2026-10-05 定的数, 施工单里写着"别动缺省值"
        assertEquals(0.8f, VoiceSettings.DEFAULT_SILENCE, 0.0001f)
        assertTrue("缺省必须落在滑块那一档里", VoiceSettings.DEFAULT_SILENCE in VoiceSettings.silenceRange)
    }

    @Test
    fun theRangeIsTheAgreedOne() {
        assertEquals(0.5f, VoiceSettings.silenceRange.start, 0.0001f)
        assertEquals(1.5f, VoiceSettings.silenceRange.endInclusive, 0.0001f)
        // 吸附点都在档里, 而且从快到慢排
        assertEquals(listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f), VoiceSettings.silenceKeyPoints)
    }

    @Test
    fun valuesOutsideTheRangeArePulledIn() {
        assertEquals("太快的会被抬到 0.5", 0.5f, VoiceSettings.clamped(0.1f), 0.0001f)
        assertEquals("太慢的会被按到 1.5", 1.5f, VoiceSettings.clamped(9f), 0.0001f)
        assertEquals("档内的原样留下", 1.1f, VoiceSettings.clamped(1.1f), 0.0001f)
    }

    @Test
    fun nonsenseFallsBackToTheDefault() {
        assertEquals("NaN 是存坏的值", VoiceSettings.DEFAULT_SILENCE, VoiceSettings.clamped(Float.NaN), 0.0001f)
        assertEquals(
            "正无穷是越界, 不是数",
            VoiceSettings.DEFAULT_SILENCE,
            VoiceSettings.clamped(Float.POSITIVE_INFINITY),
            0.0001f,
        )
    }

    @Test
    fun packageNamesThatTheExportAccepts() {
        assertTrue(VoiceSettings.packageLooksValid("com.example.led"))
        assertTrue(VoiceSettings.packageLooksValid(" io.github.yuloong07star.luwi "))
        assertTrue(VoiceSettings.packageLooksValid("a.b_c.d1"))

        // 空串不是"错", 那只是"还没填" —— 设置页据此说"整条出口关着"
        assertFalse(VoiceSettings.packageLooksValid(""))
        assertFalse("没有点", VoiceSettings.packageLooksValid("com"))
        assertFalse("开头是数字", VoiceSettings.packageLooksValid("1.com"))
        assertFalse("带空格", VoiceSettings.packageLooksValid("com.exa mple"))
        assertFalse("带横线", VoiceSettings.packageLooksValid("com.exa-mple.led"))
    }
}
