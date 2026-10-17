package io.github.yuloong07star.luwi.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把三份预设声明落进 profile 的那几段算术 ([CustomPresets])
 *
 * 为什么值得单测: 这一步在设备上**只跑一次** (首启), 跑错了的现象是"新机里没有自定义模式 / 语音
 * 开不了新会话" —— 而那时已经很难看出是哪一段拼错了。抽出来的三段 (取 `- insert:` 段、按行 id 去重、
 * 那份 profile package.json 怎么合) 都是纯的, 在这里逐条钉住
 */
class CustomPresetsTest {

    /** 那张行 id 表: 三份模式预设, 顺序也是补声明的顺序 (2.7.8 批次 7 加的第三条是探索模式) */
    @Test
    fun `三份模式预设的行 id 就是那三对`() {
        assertEquals(
            listOf(
                "preset-mobile-use" to "mobile-use",
                "preset-video" to "video",
                "preset-explore" to "explore",
            ),
            CustomPresets.PRESET_ROWS,
        )
    }

    /** 一份像 `presets/mobile-use/cordis.patch.yml` 那样的文件: 注释 + insert 段 + 后面两条顶层行 */
    private val mobileUse = """
        # 2026-10-04 本机加入：手机模式预设（preset-mobile-use）。
        # 注释还有好几行
        - insert:
            - id: preset-mobile-use
              name: '@deepseek-ai/dsh-agent-preset'
              config:
                id: mobile-use
        - id: agent-preset-registry
          name: "@deepseek-ai/dsh-agent-preset-registry"
          config:
            default: standard
            selectedDefault: mobile-use
        - id: session-log-deepseek
          config:
            enabled: false
    """.trimIndent()

    @Test
    fun `只取开头那段 insert`() {
        val block = CustomPresets.insertBlock(mobileUse)
        assertNotNull(block)
        assertTrue(block!!.startsWith("- insert:"))
        assertTrue(block.contains("id: preset-mobile-use"))
        // 后面那两条顶层行不许跟进来: 它们会改主人的默认预设与会话日志
        assertFalse(block.contains("agent-preset-registry"))
        assertFalse(block.contains("session-log-deepseek"))
    }

    @Test
    fun `没有 insert 段时给出 null`() {
        assertNull(CustomPresets.insertBlock("- id: ui-settings-general\n"))
    }

    /** 行 id 的判据把 `- insert:` 段里那几个子行也算进去 —— 于是重复跑不会追加第二遍 */
    @Test
    fun `按行 id 认已经有的声明`() {
        assertTrue(CustomPresets.hasRow(mobileUse, "preset-mobile-use"))
        assertTrue(CustomPresets.hasRow(mobileUse, "agent-preset-registry"))
        assertFalse(CustomPresets.hasRow(mobileUse, "preset-video"))
    }

    /** dsh 的空模板是 `[]`: 追加前要把它换掉, 不然 `- insert:` 接在空数组后面不是合法 YAML */
    @Test
    fun `空数组模板被换掉且注释留着`() {
        val patched = CustomPresets.appendBlock(CustomPresets.patchTemplate(), "- insert:\n  - id: x\n")
        assertFalse(patched.contains("[]"))
        assertTrue(patched.contains("Your patch layer for this dsh profile"))
        assertTrue(patched.trimEnd().endsWith("- id: x"))
    }

    /** 已经有内容时是追加, 两段之间留一个空行 */
    @Test
    fun `已有内容时追加在后面`() {
        val patched = CustomPresets.appendBlock(
            "- id: ui-settings-general\n  config:\n    welcomeNoticeVersion: 2026-09-28.1\n",
            "- insert:\n  - id: y\n",
        )
        assertTrue(patched.contains("welcomeNoticeVersion"))
        assertTrue(patched.indexOf("welcomeNoticeVersion") < patched.indexOf("- id: y"))
        assertTrue(patched.contains("\n\n- insert:"))
    }

    /** profile 还不存在: 起一份 dsh 的 web 模板, 再加上我们的包 */
    @Test
    fun `缺 profile 时按 web 模板起一份`() {
        val text = CustomPresets.withProfileManifest(null)
        assertTrue(text.contains("\"name\": \"dsh-profile-web\""))
        assertTrue(text.contains("\"private\": true"))
        assertTrue(text.contains("\"@deepseek-ai/dsh-base\""))
        assertTrue(text.contains("\"@deepseek-ai/dsh-web-app\""))
        assertTrue(text.contains("\"dsh-custom-mode\""))
        assertTrue(text.contains("link:../../../plugins/dsh-custom-mode"))
    }

    /** 已经有 profile 时: 别的键一个不动, 只加那两处 */
    @Test
    fun `已有 profile 只加我们那两处`() {
        val existing = """
            {
              "name": "dsh-profile-web",
              "private": true,
              "dependencies": {
                "@deepseek-ai/dsh-web-app": "0.2.0-rc.2"
              },
              "dsh": {
                "profile": {
                  "bundles": [
                    "@deepseek-ai/dsh-base",
                    "@deepseek-ai/dsh-web-app"
                  ],
                  "patchReload": "live"
                }
              },
              "someoneElsesKey": 7
            }
        """.trimIndent()
        val text = CustomPresets.withProfileManifest(existing)
        assertTrue(text.contains("\"@deepseek-ai/dsh-web-app\": \"0.2.0-rc.2\""))
        assertTrue(text.contains("\"someoneElsesKey\": 7"))
        assertTrue(text.contains("\"patchReload\": \"live\""))
        assertTrue(text.contains("link:../../../plugins/dsh-custom-mode"))
        // 两个出厂 bundle 一个都不能丢, 我们的那个排在最后
        val bundles = text.substringAfter("\"bundles\": [").substringBefore("]")
        assertEquals(
            listOf("\"@deepseek-ai/dsh-base\"", "\"@deepseek-ai/dsh-web-app\"", "\"dsh-custom-mode\""),
            bundles.split(",").map { it.trim().trimEnd() },
        )
    }
}
