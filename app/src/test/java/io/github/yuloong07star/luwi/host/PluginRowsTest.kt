package io.github.yuloong07star.luwi.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把生图那一行放进 profile patch 的那几段算术 ([PluginRows])
 *
 * 为什么值得单测: 这件事在设备上每启一次 host 就做一次, 而三种做错都很安静 —— 补行补到 config 行后面
 * (面板里存过的值静默失效, 再点保存又回那句"被覆盖"), 摘行顺手把主人那份 config 删掉, 或者把 `name:`
 * 写成裸路径 (面板**永远**存不进去: Loader 眼里那个名字是 anchoring 之后的 `file://` 形态, 而对不上
 * 名字的 config 行会被 `applyEntryPatches` 按 "name mismatch" 丢掉, 见 [PluginRows] 文件头第二条)。
 * 判据都在纯函数上, 不需要设备
 */
class PluginRowsTest {

    private val row = "imagegen"
    private val entry = "/data/user/0/pkg/files/host/node_modules/@dickpy/dsh-imagegen/lib/index.js"

    /** 盘上该写的那个形态 = Loader 眼里的形态 */
    private val url = PluginRows.fileUrlOf(entry)

    /** dsh 自己那份 profile patch 模板 (空数组) */
    private val template = CustomPresets.patchTemplate()

    /** 面板存过一次之后的形状: 顶端一段 insert, 末尾一行 config */
    private val withConfig = """
        # Your patch layer for this dsh profile, applied after every bundle layer:
        - insert:
            - id: imagegen
              name: '$url'
        - id: imagegen
          name: '$url'
          config:
            defaultChannel: grok
    """.trimIndent()

    /** 旧版本写下的那一版 (裸路径) —— 面板存不进去, 每启一次要被对成上面那个形态 */
    private val legacy = """
        # Your patch layer for this dsh profile, applied after every bundle layer:
        - insert:
            - id: imagegen
              name: '$entry'
    """.trimIndent()

    /** 还有别人那几行时 (预设声明) 的形状 */
    private val withOthers = """
        # Your patch layer for this dsh profile
        - insert:
            - id: preset-mobile-use
              name: '@deepseek-ai/dsh-agent-preset'
        - id: agent-preset-registry
          config:
            selectedDefault: custom
    """.trimIndent()

    @Test
    fun `路径印出来的形态与 dsh 的 anchoring 逐字一致`() {
        assertEquals(
            "file:///data/user/0/pkg/files/host/node_modules/@dickpy/dsh-imagegen/lib/index.js",
            url,
        )
        // Node 会转义的那几个 (树里的路径本来不含, 换个包名/树位时不能悄悄分家)
        assertEquals("file:///a%20b%23c%3Fd%25e", PluginRows.fileUrlOf("/a b#c?d%e"))
    }

    @Test
    fun `空模板里补出一段 insert`() {
        val next = PluginRows.ensuredRow(template, row, entry)
        assertTrue(PluginRows.hasInsertRow(next, row))
        assertTrue(next.contains("- insert:"))
        assertTrue("补出来的必须是 file:// 形态", next.contains("name: '$url'"))
        // 注释那几行要留着 (主人看的那份模板说明)
        assertTrue(next.contains("# Your patch layer for this dsh profile"))
        assertFalse(next.contains("[]"))
    }

    @Test
    fun `旧安装里那个裸路径会被对成 URL 形态, 而且是幂等的`() {
        val healed = PluginRows.ensuredRow(legacy, row, entry)
        assertTrue(healed.contains("name: '$url'"))
        assertFalse(healed.contains("name: '$entry'"))
        // 别的行一个都没动
        assertTrue(healed.contains("# Your patch layer for this dsh profile"))
        // 再叫一遍一个字节都不动
        assertEquals(healed, PluginRows.ensuredRow(healed, row, entry))
        assertEquals(withConfig, PluginRows.ensuredRow(withConfig, row, entry))
    }

    @Test
    fun `补过的行落在 config 行前面, 于是面板里存过的值才真的生效`() {
        val next = PluginRows.withRow(withConfig, row, url)
        // 只补了一次: 原来那一行已经在 (insert 子行), 所以这一次原样返回
        assertEquals(withConfig, next)
        // 而"只有 config 行、没有 insert 行"那一档, 新补进去的 insert 必须在它前面
        val onlyConfig = """
            - id: imagegen
              name: '$url'
              config:
                defaultChannel: grok
        """.trimIndent()
        val repaired = PluginRows.withRow(onlyConfig, row, url)
        assertTrue(
            "insert 行要在 config 行之前",
            repaired.indexOf("- insert:") < repaired.indexOf("- id: $row\n  name: '$url'\n  config:"),
        )
    }

    @Test
    fun `已经有的那一行不写第二遍`() {
        assertEquals(withConfig, PluginRows.withRow(withConfig, row, url))
        assertFalse(PluginRows.hasInsertRow(withOthers, row))
    }

    @Test
    fun `摘行只摘 insert 段里那一行, 主人那份 config 一个字节都不动`() {
        val next = PluginRows.withoutRow(withConfig, row)
        assertFalse(PluginRows.hasInsertRow(next, row))
        // config 行照样在
        assertTrue(next.contains("defaultChannel: grok"))
        assertTrue(PluginRows.hasRow(next, row))
        assertFalse(next.contains("- insert:"))
    }

    @Test
    fun `别人那几行不受影响`() {
        val both = PluginRows.withRow(withOthers, row, url)
        val next = PluginRows.withoutRow(both, row)
        assertTrue(next.contains("preset-mobile-use"))
        assertTrue(next.contains("agent-preset-registry"))
        assertFalse(next.contains(row))
        assertEquals(withOthers.trimEnd() + "\n", next)
    }

    @Test
    fun `只剩注释时回到空数组那一份模板`() {
        val only = PluginRows.withRow("# 只有注释\n", row, url)
        val next = PluginRows.withoutRow(only, row)
        assertTrue(next.trim().endsWith("[]"))
        assertTrue(next.contains("# 只有注释"))
    }

    @Test
    fun `没有这一行时摘行是空操作`() {
        assertEquals(withOthers + "\n", PluginRows.withoutRow(withOthers + "\n", row))
        assertEquals(template, PluginRows.withoutRow(template, row))
    }
}
