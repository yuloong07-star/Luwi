package io.github.yuloong07star.luwi.plugin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G3 能力沙盒那两条能力的边界读数 (2026-10-12)
 *
 * 挑的全是"漂开了也不会当场报错"的事: 兄弟目录的字面前缀撞车 (`DSH` 与 `DSH2`)、`..` 与软链跳出、
 * 私有目录那一棵、域名的逐字匹配 (子域与后缀撞车)。装包时那一道闸、运行时每一次判定与这里的判据
 * 用的是同一份纯逻辑 ([PluginFilesReadBoundary] / [PluginNetBoundary])
 */
class PluginBoundaryTest {

    private fun tempDir(tag: String): File {
        val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "lw-$tag-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        return dir
    }

    @Test
    fun declarationMustBeAnAbsolutePathWithoutDotDot() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginFilesReadBoundary.validateDeclaration("files.read:")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginFilesReadBoundary.validateDeclaration("files.read:sdcard/DSH")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginFilesReadBoundary.validateDeclaration("files.read:/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginFilesReadBoundary.validateDeclaration("files.read:/sdcard/../data")
        }
        PluginFilesReadBoundary.validateDeclaration("files.read:/sdcard/DSH")
    }

    @Test
    fun siblingPrefixIsNotInside() {
        // `/sdcard/DSH` 与 `/sdcard/DSH2`: 字面前缀会撞车, 按路径分量比就不会 —— 这条就是它的读数
        val base = tempDir("lw-boundary-sibling")
        try {
            val inside = File(base, "DSH").apply { mkdirs() }
            val sibling = File(base, "DSH2").apply { mkdirs() }
            File(inside, "ok.txt").writeText("在")
            File(sibling, "secret.txt").writeText("不在")
            assertEquals("在", PluginFilesReadBoundary.resolve(inside.path, "ok.txt", emptyList()).readText())
            assertThrows(IllegalArgumentException::class.java) {
                PluginFilesReadBoundary.resolve(inside.path, File(sibling, "secret.txt").path, emptyList())
            }
            assertThrows(IllegalArgumentException::class.java) {
                PluginFilesReadBoundary.resolve(inside.path, "../DSH2/secret.txt", emptyList())
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun symlinkCannotEscape() {
        // 前缀里放一条指向外头的软链 —— 解 canonical 之后就出界了。建不出软链的平台跳过 (与 PluginV2Test 同一套办法)
        val base = tempDir("lw-boundary-link")
        try {
            val root = File(base, "root").apply { mkdirs() }
            val outside = File(base, "outside").apply { mkdirs() }
            File(outside, "secret.txt").writeText("外头")
            val link = File(root, "escape")
            val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            if (linked.isSuccess) {
                assertThrows(IllegalArgumentException::class.java) {
                    PluginFilesReadBoundary.resolve(root.path, "escape/secret.txt", emptyList())
                }
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun luwiPrivateTreeIsOffLimits() {
        // 私有树在宽前缀底下 (形状同 `/data` 与 `/data/user/0/<包名>`): 前缀直指私有树 → 拒;
        // 目标落进私有树 → 也拒; 宽前缀底下的普通文件照读 (闸只拦私有那一棵)
        val base = tempDir("lw-boundary-private")
        try {
            val wide = File(base, "wide").apply { mkdirs() }
            val privateDir = File(wide, "private").apply { mkdirs() }
            File(privateDir, "credentials.yaml").writeText("tok")
            File(wide, "public.txt").writeText("公开")
            assertThrows(IllegalArgumentException::class.java) {
                PluginFilesReadBoundary.resolve(privateDir.path, "credentials.yaml", listOf(privateDir))
            }
            assertThrows(IllegalArgumentException::class.java) {
                PluginFilesReadBoundary.resolve(
                    wide.path,
                    File(privateDir, "credentials.yaml").path,
                    listOf(privateDir),
                )
            }
            assertEquals("公开", PluginFilesReadBoundary.resolve(wide.path, "public.txt", listOf(privateDir)).readText())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun declarationMustBeAHostname() {
        assertThrows(IllegalArgumentException::class.java) { PluginNetBoundary.validateDeclaration("net.http:") }
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetBoundary.validateDeclaration("net.http:https://api.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetBoundary.validateDeclaration("net.http:api.example.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetBoundary.validateDeclaration("net.http:api.example.com:443")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetBoundary.validateDeclaration("net.http:*.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetBoundary.validateDeclaration("net.http:api example.com")
        }
        PluginNetBoundary.validateDeclaration("net.http:api.example.com")
        PluginNetBoundary.validateDeclaration("net.http:127.0.0.1")
    }

    @Test
    fun hostMustMatchExactly() {
        // 名单是逐字比的: 子域 (`www.api.example.com`) 与后缀撞车 (`api.example.com.evil.test`) 都不算
        val domain = PluginNetBoundary.domainOf("net.http:api.example.com")
        assertNull(PluginNetBoundary.checkHop(domain, "https://api.example.com/v1/x".toHttpUrl()))
        assertNull(PluginNetBoundary.checkHop(domain, "https://API.Example.com/v1".toHttpUrl()))
        assertNull(PluginNetBoundary.checkHop(domain, "https://api.example.com:8443/v1".toHttpUrl()))
        assertNotNull(PluginNetBoundary.checkHop(domain, "https://www.api.example.com/v1".toHttpUrl()))
        assertNotNull(PluginNetBoundary.checkHop(domain, "https://api.example.com.evil.test/v1".toHttpUrl()))
        // 只走 https, 地址里不许带用户名密码
        assertNotNull(PluginNetBoundary.checkHop(domain, "http://api.example.com/v1".toHttpUrl()))
        assertNotNull(PluginNetBoundary.checkHop(domain, "https://user:pw@api.example.com/v1".toHttpUrl()))
    }
}
