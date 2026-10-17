package io.github.yuloong07star.luwi.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能删除那三条护栏 ([AppSkills.remove]) 里唯一没有设备也能量的那一条: 名字能不能当目录名用
 *
 * 为什么值得单测: 删技能是这条链上**唯一不可逆**的动作, 而"删哪儿"由一个名字决定 —— 带斜杠的名字
 * 会指到 `$DSH_HOME/skills/` 外面去 (主人 2026-10-10: "设置里的 skill 板块加上删除 skill 按钮")
 */
class AppSkillsTest {

    /**
     * 远端那份 `index.json` (`yuloong07-star/app-skills`) → 目录条目: 字段对应与容错
     *
     * 那一份的形状与本机随包的 `catalog.json` **不是同一个** (它是 `apps: [{id,name,package,path,…}]`),
     * 所以这一个映射写错了就是"设置页那段什么都列不出来" —— 没有设备也能量
     */
    @Test
    fun `远端 index 映射成目录条目`() {
        val json = """
            {"repo":"app-skills","updated":"2026-10-11","apps":[
              {"id":"android-vivo-notes","name":"原子笔记","package":"com.android.notes","path":"skills/android-vivo-notes/SKILL.md"},
              {"id":"android-vivo-camera","package":"com.android.camera"},
              {"id":"no-package-here","name":"坏条目"},
              {"name":"没有 id"}
            ]}
        """.trimIndent()
        val entries = AppSkills.remoteEntries(json)
        // 缺 package 的那条与缺 id 的那条都不要, 剩下两条 (其中一条缺 name, 用 id 顶上)
        assertTrue("缺 package / 缺 id 的都不要", entries.size == 2)
        assertTrue(entries[0].skill == "android-vivo-notes")
        assertTrue(entries[0].title == "原子笔记")
        assertTrue(entries[0].apps == listOf("com.android.notes"))
        assertTrue("缺 name 时用 id 当显示名", entries[1].title == "android-vivo-camera")
        assertTrue("缺 package 的那条不要", entries.none { it.skill == "no-package-here" })
    }

    @Test
    fun `远端 index 不是 JSON 或没有 apps 时回空表`() {
        assertTrue(AppSkills.remoteEntries("not json").isEmpty())
        assertTrue(AppSkills.remoteEntries("""{"repo":"app-skills"}""").isEmpty())
    }

    @Test
    fun `技能名只许是目录名`() {
        with(AppSkills) {
            assertTrue("android-vivo-camera".isSafeSkillName())
            assertTrue("camp-schedule-sync".isSafeSkillName())
            assertTrue("中文名也行".isSafeSkillName())
            for (bad in listOf("", ".", "..", "a/b", "/etc/passwd", "a\\b", "../dsh-home")) {
                assertFalse("$bad 不该过", bad.isSafeSkillName())
            }
        }
    }

    /* ── 2.7.8 批次 5: 索引第二种形状 / 搜索 / 摘要 / frontmatter / 那一本账 ──────────── */

    /** 新形状: `skills[]` 带 version / description / files[] / apps[] / risk */
    private val newIndex = """
        {"repo":"app-skills","skills":[
          {"id":"wechat","name":"微信","description":"发消息与看朋友圈","version":"1.2.0",
           "apps":["com.tencent.mm"],"risk":"low",
           "files":[{"path":"SKILL.md","sha256":"aa11"},{"path":"scripts/tap.sh","sha256":"bb22"}]},
          {"id":"no-files","name":"没有文件清单的"},
          {"id":"../逃出去","name":"坏名字"},
          {"id":"escape","files":[{"path":"../../etc/passwd"}]}
        ]}
    """.trimIndent()

    @Test
    fun `索引第二种形状映射成技能表`() {
        val skills = AppSkills.parseIndex(newIndex)
        assertTrue("坏名字那条被丢掉", skills.none { it.id == "../逃出去" })
        val wechat = skills.first { it.id == "wechat" }
        assertTrue(wechat.title == "微信")
        assertTrue(wechat.version == "1.2.0")
        assertTrue(wechat.description == "发消息与看朋友圈")
        assertTrue(wechat.apps == listOf("com.tencent.mm"))
        assertTrue(wechat.risk == "low")
        assertTrue("两个文件都在", wechat.files.map { it.path } == listOf("SKILL.md", "scripts/tap.sh"))
        assertTrue("摘要原样", wechat.files[1].sha256 == "bb22")
        // 没有 files[] 的那条退回 SKILL.md 一个文件; 越界的那个文件被丢掉之后也是同一条兜底
        assertTrue(skills.first { it.id == "no-files" }.files.map { it.path } == listOf("SKILL.md"))
        assertTrue(skills.first { it.id == "escape" }.files.map { it.path } == listOf("SKILL.md"))
    }

    @Test
    fun `老的 apps 形状仍然认, 只是没有版本`() {
        val legacy = """
            {"repo":"app-skills","apps":[
              {"id":"douyin","name":"抖音","package":"com.ss.android.ugc.aweme","path":"skills/douyin/SKILL.md"}
            ]}
        """.trimIndent()
        val skills = AppSkills.parseIndex(legacy)
        assertTrue(skills.size == 1)
        assertTrue(skills[0].id == "douyin")
        assertTrue("老形状没有版本", skills[0].version.isEmpty())
        assertTrue("老形状只有一个正文文件", skills[0].files.map { it.path } == listOf("SKILL.md"))
        assertTrue("包名进 apps", skills[0].apps == listOf("com.ss.android.ugc.aweme"))
    }

    @Test
    fun `搜索只看 id 名字与描述, 空串给全部`() {
        val skills = AppSkills.parseIndex(newIndex)
        assertTrue("空串给全部", AppSkills.search(skills, "  ").size == skills.size)
        assertTrue("按 id", AppSkills.search(skills, "wech").map { it.id } == listOf("wechat"))
        assertTrue("按名字", AppSkills.search(skills, "微信").map { it.id } == listOf("wechat"))
        assertTrue("按描述", AppSkills.search(skills, "朋友圈").map { it.id } == listOf("wechat"))
        assertTrue("大小写不分", AppSkills.search(skills, "WECHAT").map { it.id } == listOf("wechat"))
        assertTrue("搜不到就是空", AppSkills.search(skills, "支付宝").isEmpty())
    }

    @Test
    fun `相对路径的护栏`() {
        with(AppSkills) {
            assertTrue("SKILL.md".isSafeRelativePath())
            assertTrue("scripts/tap.sh".isSafeRelativePath())
            assertTrue("中文路径也行.md".isSafeRelativePath())
            for (bad in listOf("", "/etc/passwd", "a\\b", "../x", "scripts/../../x", "scripts//x", "./x", ".lw-meta.json")) {
                assertFalse("$bad 不该过", bad.isSafeRelativePath())
            }
        }
    }

    @Test
    fun `frontmatter 只认第一段, 坏了给空表`() {
        val body = """
            ---
            name: 微信
            description: "发消息与看朋友圈"
            risk: low
            ---
            正文从这里开始
        """.trimIndent()
        val fields = AppSkills.frontmatterOf(body)
        assertTrue(fields["name"] == "微信")
        assertTrue("引号要剥掉", fields["description"] == "发消息与看朋友圈")
        assertTrue(fields["risk"] == "low")
        assertTrue("没有 frontmatter 给空表", AppSkills.frontmatterOf("# 标题\n正文").isEmpty())
        assertTrue("没闭合也给空表", AppSkills.frontmatterOf("---\nname: x\n").isEmpty())
    }

    @Test
    fun `sha256 与标准向量一致`() {
        assertTrue(
            AppSkills.sha256Of("abc".toByteArray()) ==
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
    }

    @Test
    fun `那一本账写得进去也读得回来, 而且不留 tmp`() {
        val directory = kotlin.io.path.createTempDirectory("lw-meta").toFile()
        val meta = AppSkills.LocalMeta("wechat", "1.2.0", mapOf("SKILL.md" to "aa11", "scripts/tap.sh" to "bb22"))
        assertTrue("写得进去", AppSkills.writeMeta(directory, meta))
        assertTrue("没有留下 .tmp", java.io.File(directory, ".lw-meta.json.tmp").exists().not())
        val back = AppSkills.readMeta(directory)
        assertTrue(back != null)
        if (back != null) {
            assertTrue(back.skill == "wechat")
            assertTrue(back.version == "1.2.0")
            assertTrue(back.files == meta.files)
        }
        directory.deleteRecursively()
    }
}
