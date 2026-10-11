package io.github.yuloong07star.luwi.sample.whalewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 主人自己导进来的那几份动图
 *
 * 一份导入落成 `filesDir/whale-imports/<键>/` 下的三样东西: `art.gif` (原始那一份, 留着),
 * `meta.txt` (名字 + 每帧毫秒), `frame-00.png …` (解出来的逐帧图)。
 *
 * **为什么要解成逐帧**: 桌面小组件是靠 `ViewFlipper` 一张一张翻的 (见 [WhaleWidgetProvider]), 而且
 * 交给宿主的是**内容 URI 而不是位图** —— 32 张位图塞进一次 binder 事务会撞上那个 1 MB 的上限,
 * 而 URI 由宿主自己去解, 一张多大都不关我们的事 (只读那样一份的 provider 见 [WhaleFramesProvider])
 *
 * GIF 的解码用 `android.graphics.Movie`: 它从 API 1 就在, 虽然早被标了 deprecated, 但它是**唯一**能
 * 同步逐帧取图的平台 API (`AnimatedImageDrawable` 只能播, 取不到第 n 帧)
 */
internal object WhaleImports {

    private const val TAG = "WhaleImports"

    /** app 私有目录下那一层, 与 [WhaleStore] 那份偏好一样只属于这个伴侣 */
    private const val DIR = "whale-imports"

    private const val META = "meta.txt"
    private const val ART = "art.gif"

    /** 一份导入最多解多少帧, 以及画布的最大边 (再大对桌面那一格没意义, 只是白占地方) */
    private const val MAX_FRAMES = 32
    private const val MIN_FRAMES = 4
    private const val TARGET_WIDTH = 300
    private const val TARGET_HEIGHT = 370

    /** 一帧大约占多少毫秒 (用来定"解多少张"), 以及每一帧停留的上下限 */
    private const val FRAME_HINT_MS = 80
    private const val MIN_FRAME_MS = 60
    private const val MAX_FRAME_MS = 300

    /** 源文件的上限: 16 MB 的 GIF 已经是一段很长的动画, 再大就不该塞进桌面那一格 */
    private const val MAX_BYTES = 16 * 1024 * 1024

    data class Import(
        val key: String,
        val title: String,
        val directory: File,
        val frames: Int,
        val frameMs: Int,
    )

    fun root(context: Context): File = File(context.filesDir, DIR)

    /** 现在导进来了哪几套 (按目录名排序, 于是顺序是稳的) */
    fun all(context: Context): List<Import> {
        val found = root(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { read(it) }
        return found
    }

    /** 第 [index] 帧那张 png */
    fun frame(context: Context, item: Import, index: Int): File =
        File(item.directory, "frame-%02d.png".format(index))

    private fun read(directory: File): Import? {
        val meta = File(directory, META).takeIf { it.isFile } ?: return null
        val lines = runCatching { meta.readLines() }.getOrNull() ?: return null
        val title = lines.getOrNull(0)?.trim().orEmpty().ifEmpty { directory.name }
        val frameMs = lines.getOrNull(1)?.trim()?.toIntOrNull()
            ?.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS) ?: 120
        val frames = directory.listFiles().orEmpty().count { it.name.startsWith("frame-") && it.name.endsWith(".png") }
        if (frames <= 0) return null
        return Import(directory.name, title, directory, frames, frameMs)
    }

    /**
     * 收一份进来: 抄原始文件 → 解逐帧 → 落 meta
     *
     * 失败时把这一份的目录整个删掉 —— 半份导入比没有更糟 (它会在列表里出现, 点开却少几帧)
     */
    fun add(context: Context, source: Uri, title: String): Import {
        val key = uniqueKey(context, title)
        val directory = File(root(context), key)
        directory.mkdirs()
        return try {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: throw IOException("这一份打不开 (拿不到它的内容)")
            if (bytes.size > MAX_BYTES) {
                throw IOException("这一份 ${bytes.size / 1024 / 1024} MB, 超过 ${MAX_BYTES / 1024 / 1024} MB 的上限")
            }
            File(directory, ART).writeBytes(bytes)
            val (frames, frameMs) = decode(bytes, directory)
            File(directory, META).writeText("$title\n$frameMs\n")
            Log.i(TAG, "imported $key: $frames frames, ${frameMs}ms each")
            Import(key, title, directory, frames, frameMs)
        } catch (problem: Throwable) {
            directory.deleteRecursively()
            throw problem
        }
    }

    /** 删掉一套 (原始文件与帧一起); 回"删掉了没有" */
    fun remove(context: Context, key: String): Boolean {
        val directory = File(root(context), key)
        if (!directory.isDirectory) return false
        return directory.deleteRecursively()
    }

    /**
     * 把一份 GIF 解成逐帧 png
     *
     * 帧数与每帧时长都从它自己的 `duration` 推: 一个 3 秒的 GIF 大约解 32 张 (上限), 半秒的只解
     * 四张多一点 —— 桌面上那点大小看不出比这更细的差别
     */
    private fun decode(bytes: ByteArray, directory: File): Pair<Int, Int> {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("这一份不像 GIF (也可能是个坏文件)")
        if (movie.width() <= 0 || movie.height() <= 0) throw IOException("这一份解出来是空的")

        val scale = min(
            TARGET_WIDTH.toFloat() / movie.width().toFloat(),
            TARGET_HEIGHT.toFloat() / movie.height().toFloat(),
        )
        val width = max(1, (movie.width() * scale).roundToInt())
        val height = max(1, (movie.height() * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)

        val duration = movie.duration().takeIf { it > 0 } ?: (MAX_FRAMES * FRAME_HINT_MS)
        val frames = (duration / FRAME_HINT_MS).coerceIn(MIN_FRAMES, MAX_FRAMES)
        val step = max(1, duration / frames)
        repeat(frames) { index ->
            bitmap.eraseColor(Color.TRANSPARENT)
            movie.setTime(index * step)
            movie.draw(canvas, 0f, 0f)
            File(directory, "frame-%02d.png".format(index)).outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        bitmap.recycle()
        return frames to step.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
    }

    /** 目录名: 用小写字母数字与横线, 重复就加一个 `-2`, 于是"导入两次同一份"也各有各的位置 */
    private fun uniqueKey(context: Context, title: String): String {
        val base = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(24)
            .ifEmpty { "whale" }
        var candidate = base
        var suffix = 2
        while (File(root(context), candidate).exists()) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }
}
