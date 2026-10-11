package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 伴侣 APK: 一份 companion 插件装的**另一半** (协议第 8.1 节)
 *
 * 插件包只说"我要绑哪个包、哪个 Service" (`entry.package` / `entry.service`), 而**那个包本身要另外
 * 装** —— 工具、桌面组件、它自己的界面全在那个 APK 里。所以插件页上少了这一步, 一个人从链接装完
 * `.lwp` 之后只会看到"启用着但宿主没接上", 却不知道该去装什么: 这一份就是那一步
 *
 * 三条与 [PluginDownload] 同样的口径, 一个都不省:
 *
 * - **只许 https**: 每一跳都重查, 从 https 跳到 http 就地中止
 * - **不信任任何头**: 上限靠边读边数, 超了删掉半份
 * - **下完先看它像不像一份 APK**: 前两个字节不是 `PK` (APK 就是一个 zip) 就当场说, 而不是让系统
 *   安装器去报一句看不懂的错; 拿得到包名时**再对一次**它是不是这个插件要的那个包 (协议第 7 节那
 *   条"describe() 与包必须是一对"的同一件事, 只是提前到了装之前)
 *
 * **落地那一步交给系统安装器**: 我们只把文件变成一个 `content://` 交出去 —— 装应用这件事的决定权
 * 在用户手里那一次点击上, 应用自己不去替谁按 (权限清单里那条 `REQUEST_INSTALL_PACKAGES` 管的是
 * "能不能问", 不是"能不能不问")
 *
 * **下载走 OkHttp, 不走 `HttpURLConnection`** (2026-10-11 换的, 与 [PluginDownload] 同一条理由):
 * 那一套不做 Happy Eyeballs —— 双栈设备上这台 CDN 解析出 IPv6 时会直接去连 IPv6, 而移动网络里
 * 那条路常常是黑洞, 于是浏览器下得动、应用只拿到 "Connection reset"
 */
internal object CompanionApk {

    private const val TAG = "LwCompanionApk"

    /**
     * 一份伴侣 APK 的上限
     *
     * 比 `.lwp` 那个 32 MB 宽得多是故意的: 包本身只是几行清单与工具表, 而伴侣 APK 里有它自己的
     * 界面与资源。256 MB 这条线不是"要多大给多大", 是"下错了东西时别把磁盘填满"
     */
    private const val MAX_BYTES = 256L * 1024 * 1024

    /** 一个短链加一次 302 到头了, 再多是环 */
    private const val MAX_HOPS = 5

    /** 一条地址最多试几次: 移动网络上一个地址连不上很常见, 换一个就成了 */
    private const val ATTEMPTS = 3

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000

    private const val USER_AGENT = "Luwi-companion-apk"

    /** 自己看 302 (每一跳都要重查 https), 所以两个 follow 都关掉 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(0L, TimeUnit.MILLISECONDS)
            .build()
    }

    /** zip 的头两个字节: `P` 与 `K` */
    private const val ZIP_FIRST = 0x50
    private const val ZIP_SECOND = 0x4B

    /** 系统安装器认的那个 MIME */
    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * 这个包现在装着没有 (装着回它的版本名, 没装回 null)
     *
     * 读的是系统那一份事实, 不是我们的记录 —— "插件启用着但伴侣没装"这件事只有这里答得出来,
     * 而它正是"启用了却接不上宿主"最常见的原因
     */
    fun installed(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        val manager = context.packageManager
        return try {
            val info = manager.getPackageInfo(packageName, 0)
            info.versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 下下来的那一份放哪
     *
     * 首选**应用自己的外部媒体目录** (`/sdcard/Android/media/<包名>/lw/companion`): 它不需要任何权限
     * 就能写, 而特权那一侧 —— root 与 Shizuku 的 shell uid —— 都读得到。静默安装那条路要的正是后
     * 一点: 应用私有 `cacheDir` 只有 root 读得到, shell 连列都列不动 (2026-10-11 在真机上实测过)。
     * 拿不到外部媒体目录时退回 `cacheDir/companion-apk`, 那时只有 root 那份装得动
     */
    @Suppress("DEPRECATION") // 只有它给得到 `/sdcard/Android/media/<包名>` 这一条路, 换不掉
    fun stagingDirectory(context: Context): File =
        context.getExternalMediaDirs()?.firstOrNull()?.let { File(it, "lw/companion") }
            ?: File(context.cacheDir, "companion-apk")

    /**
     * 下到 [stagingDirectory] 里的一份临时文件并返回它
     *
     * 抛出来的每一句都点名叫人不明白的那一处: 不是 https / 跳太多 / 回了别的状态码 / 太大 / 不像
     * 一份 APK。**调用方负责删掉这一份** —— 系统安装器读完之后它就没用了
     */
    fun fetch(context: Context, url: String): File {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("链接是空的")
        val directory = stagingDirectory(context).apply { mkdirs() }
        // 上一次留下的那一份扫掉: 装成了的已经被调用方删过, 还留在这里的都是半路断了的那几份。
        // **交给系统安装器那一份不算断**: 安装器是另一个进程, 它读这个路径在我们返回之后
        directory.listFiles()?.forEach { old -> if (old.isFile && old.name.endsWith(".apk")) old.delete() }
        val target = File(directory, "companion-${System.currentTimeMillis()}.apk")
        try {
            val landed = downloadTo(trimmed, target)
            if (target.length() == 0L) throw IllegalArgumentException("这个链接下下来是空的")
            looksLikeApk(target, landed)
            Log.i(TAG, "fetched ${target.length()} bytes from $trimmed")
            return target
        } catch (problem: Throwable) {
            target.delete()
            throw problem
        }
    }

    /** 落到哪一条地址上 (跳完最后那一条), 只给"这不是一份 APK"那句错话用 */
    private fun downloadTo(url: String, target: File): String {
        var current = url
        var hops = 0
        while (true) {
            requireHttps(current)
            val response = connect(current)
            val code = response.code
            if (code in 300..399) {
                val where = response.header("Location")
                val next = response.request.url.resolve(where.orEmpty())?.toString()
                response.close()
                if (where.isNullOrBlank()) throw IllegalArgumentException("这个链接回了 $code, 却没说要跳到哪")
                hops += 1
                if (hops > MAX_HOPS) throw IllegalArgumentException("这个链接跳了 $MAX_HOPS 次还没到头, 不跟了")
                current = next ?: throw IllegalArgumentException("这个链接回了 $code, 跳的目标读不出来: $where")
                continue
            }
            if (code != 200) {
                response.close()
                throw IllegalArgumentException("这个链接回了 $code (要 200 才能拿到字节)")
            }
            try {
                val declared = response.body.contentLength()
                if (declared > MAX_BYTES) {
                    throw IllegalArgumentException(
                        "这一份 ${declared / 1024 / 1024} MB, 超过 ${MAX_BYTES / 1024 / 1024} MB 的上限",
                    )
                }
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_BYTES) {
                                throw IllegalArgumentException("这一份超过 ${MAX_BYTES / 1024 / 1024} MB 的上限")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                response.close()
            }
            return current
        }
    }

    /**
     * 把这一份交给系统安装器
     *
     * @param expected 这个插件要的那个包名 (缺省 null = 不点名); 点名时**包名对不上就拒** —— 装错一
     *   份 APK 与装错一份插件包是同一类错, 而这里还来得及说
     * @return 一句给人看的话: 交给系统了, 或者为什么没交出去
     */
    fun install(context: Context, file: File, expected: String?): String {
        val problem = mismatch(context, file, expected)
        if (problem != null) return problem
        val found = packageNameOf(context, file).orEmpty()
        if (!context.packageManager.canRequestPackageInstalls()) {
            // **这一步不是可选的**: 没有"安装未知应用"这条授权时, 系统在安装器那一层就把这次安装
            // 挡掉, 而那一下长得像"点了没反应"。所以先把人送到那一页, 让他回来再点一次
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "the unknown-sources page did not open: ${it.message}") }
            return "还缺「安装未知应用」这条授权, 已经打开那一页: 打开之后回来再点一次安装"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.lw.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (found.isBlank()) {
                "交给系统安装器了: 在它那一页点「安装」。装完回到这一页点一下插件的「启用」"
            } else {
                "交给系统安装器了 ($found): 在它那一页点「安装」。装完回到这一页点一下插件的「启用」"
            }
        } catch (missing: Throwable) {
            Log.w(TAG, "no installer took the apk: ${missing.message}")
            "这台设备上没有接收安装的那个界面: ${missing.message ?: missing.javaClass.simpleName}"
        }
    }

    /** 读一份 APK 自己的包名; 读不出来 (不是一份能装的 APK) 回 null */
    fun packageNameOf(context: Context, file: File): String? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName

    /**
     * 这一份是不是那个包: 对得上回 null, 否则回一句人话
     *
     * 静默安装 (协议第 8.1 节那条 `entry.apk`) 与交给系统安装器两条路都先过这一道 —— 装错一份 APK
     * 与装错一份插件包是同一类错, 而静默那条路没人会在安装器那一页替我们看一眼
     */
    fun mismatch(context: Context, file: File, expected: String?): String? {
        val found = packageNameOf(context, file)
            ?: return "这一份不是一份能装的 APK, 系统读不出它的清单"
        if (!expected.isNullOrBlank() && found != expected) {
            return "这一份的包名是 $found, 而它这个插件要的是 $expected —— 装错了, 没有动"
        }
        return null
    }

    /** 只许 https: 这一句在每一跳都跑一次, 所以"跳到 http"也是当场中止 */
    private fun requireHttps(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("只收 https 的链接, 这一条是 ${url.take(80)}")
        }
    }

    /** 试几次再放弃: 连不上多半是"这一个地址不通", 换一个就成了 */
    private fun connect(url: String): Response {
        var last: IOException? = null
        repeat(ATTEMPTS) {
            try {
                return client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "*/*")
                        .header("User-Agent", USER_AGENT)
                        .build(),
                ).execute()
            } catch (problem: IOException) {
                last = problem
            }
        }
        val why = last
        throw IllegalArgumentException("连不上这个链接: ${why?.message ?: why?.javaClass?.simpleName}")
    }

    /** 前两个字节得是 `PK`: 不是的话下下来的多半是一页 JSON 或者一页网页 */
    private fun looksLikeApk(file: File, landed: String) {
        val head = ByteArray(2)
        val read = file.inputStream().use { it.read(head) }
        if (read < 2 ||
            (head[0].toInt() and 0xFF) != ZIP_FIRST ||
            (head[1].toInt() and 0xFF) != ZIP_SECOND
        ) {
            val peek = ByteArray(60)
            val got = file.inputStream().use { it.read(peek) }.coerceAtLeast(0)
            val text = (0 until got).map { index ->
                val value = peek[index].toInt() and 0xFF
                if (value in 32..126) value.toChar() else '.'
            }.joinToString("")
            throw IllegalArgumentException(
                "这个链接下的不是一份 APK (APK 是一个 zip)。它最后落在 $landed, 开头是这段: $text",
            )
        }
    }
}
