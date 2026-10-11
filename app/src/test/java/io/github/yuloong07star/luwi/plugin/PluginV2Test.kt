package io.github.yuloong07star.luwi.plugin

import io.github.yuloong07star.luwi.plugin.widget.WidgetSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 方案 v2 那几条"漂开了也不会当场报错"的判据
 *
 * 这一份只在 JVM 上跑 (不碰 Android API), 挑的是三件最会静默错的事: **公钥身份的规范化**、
 * 包内路径那条纪律、`widget.json` 两个槽的形状
 */
class PluginV2Test {

    /** 发布者那把 P-256 公钥 (SPKI-DER 的 base64), 与 `tools/lw-plugin-sign.mjs` 印出来的是同一把 */
    private val publisherKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPe9pBGIJSqcDBmIFUUK+Lqu7+cv5T3MDq09jniLd+Q7ZcoQ8D" +
            "mfIyEjMIgsLd7RfmsiW7NKkugE8/tw6x6LSg=="

    @Test
    fun identityMatchesTheSignTool() {
        // 这个数是 `node tools/lw-plugin-sign.mjs sign …` 自己打印出来的那一串; 两处漂开的话,
        // 白名单里写的那一条就会永远对不上 (假阴性), 而那种错要花很久才看得出
        val der = Base64.getDecoder().decode(publisherKey)
        assertEquals(
            "sha256:ea8098f95122611234b96f89912b06bfe4dc218930936844b463088ae89eea52",
            PluginTrust.identityOfSpki(der),
        )
    }

    @Test
    fun reencodedSpkiStillMatches() {
        // 同一把公钥, 用平台自己的 KeyFactory 重新编一遍 SPKI: AlgorithmIdentifier 那条曲线参数
        // 由平台自己写, 与作者那边产出的字节**可能不是同一串** —— 规范化就是为这一条做的 (方案 3.3)
        val der = Base64.getDecoder().decode(publisherKey)
        val reencoded = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)).encoded
        assertEquals(PluginTrust.identityOfSpki(der), PluginTrust.identityOfSpki(reencoded))
    }

    @Test
    fun packagePathsCannotEscape() {
        val root = File(System.getProperty("java.io.tmpdir") ?: ".", "lw-plugin-paths-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        try {
            PluginPaths.requireRelative("art/dance-1", "帧图")
            assertThrows(IllegalArgumentException::class.java) { PluginPaths.requireRelative("../evil.png", "帧图") }
            assertThrows(IllegalArgumentException::class.java) { PluginPaths.requireRelative("art/../evil.png", "帧图") }
            assertThrows(IllegalArgumentException::class.java) { PluginPaths.requireRelative("/sdcard/x.png", "帧图") }
            assertThrows(IllegalArgumentException::class.java) { PluginPaths.requireRelative("C:/x.png", "帧图") }
            assertThrows(IllegalArgumentException::class.java) { PluginPaths.requireRelative("art\\x.png", "帧图") }
            val inside = PluginPaths.resolve(root, "art/x.png", "帧图")
            assertTrue(inside.absolutePath.startsWith(root.absolutePath))

            // 装完之后再塞一个符号链接进来是真的 (插件目录主人能手工改), 所以运行时那一遍要解
            // `canonicalPath` 而不是比字符串 —— 这一条就是它的读数。建不出软链的平台跳过
            val outside = File(System.getProperty("java.io.tmpdir") ?: ".", "lw-plugin-outside-${System.nanoTime()}")
            assertTrue(outside.mkdirs())
            try {
                val link = File(root, "escape")
                val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }
                if (linked.isSuccess) {
                    assertThrows(IllegalArgumentException::class.java) {
                        PluginPaths.resolve(root, "escape/evil.png", "帧图")
                    }
                }
            } finally {
                outside.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun animSlotWantsFrames() {
        val spec = WidgetSpec.parse(
            Json.parseToJsonElement(
                """{"slot":"anim","name":"鲸鱼娘","frames":{"dir":"art/dance-1","count":4,"fps":10},
                   "sets":[{"key":"slow","dir":"art/dance-1-slow","count":4,"fps":5}]}""",
            ).jsonObject,
        )
        assertEquals("anim", spec.slot)
        assertEquals(100, spec.animationSets().first().intervalMs)
        assertEquals("frame-02.png", spec.animationSets().first().frameName(2))
        assertEquals(2, spec.animationSets().size)
        assertThrows(IllegalArgumentException::class.java) {
            WidgetSpec.parse(Json.parseToJsonElement("""{"slot":"anim","name":"没帧的"}""").jsonObject)
        }
    }

    @Test
    fun imageSlotRejectsFrames() {
        val spec = WidgetSpec.parse(
            Json.parseToJsonElement(
                """{"slot":"image","name":"番茄钟","title":"专注","text":"25:00",
                   "buttons":[{"label":"开始","action":"tool","tool":"timer_start"}]}""",
            ).jsonObject,
        )
        assertEquals("image", spec.slot)
        assertEquals("timer_start", spec.buttons.first().tool)
        assertThrows(IllegalArgumentException::class.java) {
            WidgetSpec.parse(
                Json.parseToJsonElement(
                    """{"slot":"image","name":"错的那一份","title":"x",
                       "frames":{"dir":"art/x","count":1,"fps":1}}""",
                ).jsonObject,
            )
        }
    }

    @Test
    fun artKindCannotDeclareAnything() {
        val withCapability = """
            {"protocol":"lw-plugin/1","id":"io.example.art","name":"画","version":"1.0.0","minLw":"2.0.0",
             "api":"1","kind":"art","toolPrefix":"art1","capabilities":{"requested":["device.read"],"dangerous":[]},
             "tools":[]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.parse(withCapability, "2.7.5") }
    }

    @Test
    fun dexIsAnAliasOfInline() {
        val text = """
            {"protocol":"lw-plugin/1","id":"io.example.timer","name":"番茄钟","version":"1.0.0","minLw":"2.0.0",
             "api":"1","kind":"dex","toolPrefix":"timer",
             "entry":{"dex":"main.dex","class":"io.example.timer.TimerPlugin"},
             "capabilities":{"requested":[],"dangerous":[]},"tools":[]}
        """.trimIndent()
        val manifest = PluginManifest.parse(text, "2.7.5")
        assertEquals("inline", manifest.kind)
        assertEquals("main.dex", manifest.entryDex)
        assertThrows(IllegalArgumentException::class.java) {
            PluginManifest.parse(text.replace("\"main.dex\"", "\"../main.dex\""), "2.7.5")
        }
    }

    @Test
    fun ballThemeSpecIsChecked() {
        val spec = io.github.yuloong07star.luwi.plugin.theme.BallTheme.parse(
            """{"name":"青瓷","states":{"idle":"art/idle.png","listening":"art/listening.png"}}""",
        )
        assertEquals("青瓷", spec.name)
        assertEquals("art/idle.png", spec.states["idle"])
        // 一段都不给 (既没有 states 也没有 frames) 就是一份空外观: 那种要在读的时候当场说清
        assertThrows(IllegalArgumentException::class.java) {
            io.github.yuloong07star.luwi.plugin.theme.BallTheme.parse("""{"name":"空的那一份"}""")
        }
        // 一串帧的张数有上限 (球才 48 dp, 再多也看不出来, 只是白占内存)
        assertThrows(IllegalArgumentException::class.java) {
            io.github.yuloong07star.luwi.plugin.theme.BallTheme.parse(
                """{"name":"帧太多","frames":{"idle":{"dir":"art/x","count":99,"fps":10}}}""",
            )
        }
    }
}
