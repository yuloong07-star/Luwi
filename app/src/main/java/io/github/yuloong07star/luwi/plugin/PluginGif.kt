package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.net.Uri
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * 桌面对话组件那个「显示的人物」里的 gif (2.7.8 批次 6)
 *
 * 主人 2026-10-17: "显示的人物支持自行导入 gif", 而点开那一行是一个真弹窗, 从上到下三条:
 * **跟随浮标显示 / 选择 gif / 导入 gif**
 *
 * **gif 不直接交给桌面那一格**: RemoteViews 的 `ImageView` 到启动器那一侧只有一张静态图 (`setImageViewUri`
 * 走的是"解一次、画一张"), 动不了 —— 与 `AnimationDrawable` / `setImageResource` 在桌面上不动是同一条
 * 性质 (2026-10-10 在伴侣那一份上量过)。所以导入那一步**把 gif 拆成 PNG 帧序列**放在插件自己那块盘上
 * (`$DSH_HOME/plugins/<id>/.data/faces/<名字>/frame-XX.png`), 组件那边用 `ViewFlipper` 逐帧放 ——
 * 那是这一层唯一真会动的做法 (与动画槽同一条路)
 *
 * 拆帧用 [Movie] (已废弃但仍在): 按时间步进采样, 每 [STEP_MS] 一帧, 最多 [MAX_FRAMES] 帧。gif 很短
 * 时也不会只剩一帧, 很长的会被截到上限 —— 桌面那一格只有几十 dp, 帧再多也看不出差别
 */
internal object PluginGif {

    const val DIRECTORY = "faces"
    const val MAX_FRAMES = 32
    const val STEP_MS = 90

    /** 一次导入的结果: 名字 + 写了几帧 (失败时 [error] 是那句人话) */
    internal data class Imported(val name: String, val frames: Int, val error: String)

    private fun facesDir(context: Context, id: String): File = File(PluginStore.dataDir(context, id), DIRECTORY)

    /** 这个插件导入过哪几个 gif (名字 = 目录名, 按名字排序) */
    internal fun imported(context: Context, id: String): List<String> {
        val root = facesDir(context, id)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.listFiles().orEmpty().any { frame -> frame.name.startsWith("frame-") } }
            .map { it.name }
            .sorted()
    }

    /**
     * 把一个 gif 导入成帧序列
     *
     * 名字取文件名去掉扩展名 (重复导入同名的那一份就覆盖), 结果是 `.data/faces/<名字>/frame-XX.png`
     */
    internal fun import(context: Context, id: String, uri: Uri): Imported {
        val resolver = context.contentResolver
        val movie = try {
            resolver.openInputStream(uri)?.use { input -> Movie.decodeStream(input) }
        } catch (error: Throwable) {
            null
        } ?: return Imported("", 0, "这个文件解不成 gif (换成一张动图试试)")

        val width = movie.width().coerceAtLeast(1)
        val height = movie.height().coerceAtLeast(1)
        val duration = movie.duration().let { if (it <= 0) STEP_MS else it }
        val count = (duration / STEP_MS).coerceIn(1, MAX_FRAMES)

        val name = uniqueName(facesDir(context, id), sanitize(uri))
        val target = File(facesDir(context, id), name)
        if (!target.isDirectory && !target.mkdirs()) return Imported(name, 0, "写不进插件自己那块目录")
        // 先清掉上一次同名的那些帧 (帧数可能变了)
        target.listFiles().orEmpty().filter { it.name.startsWith("frame-") }.forEach { it.delete() }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var written = 0
        try {
            for (index in 0 until count) {
                val at = if (count == 1) 0 else duration * index / count
                bitmap.eraseColor(0)
                movie.setTime(at)
                movie.draw(canvas, 0f, 0f)
                val file = File(target, String.format(java.util.Locale.ROOT, "frame-%02d.png", index))
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                written += 1
            }
        } catch (error: Throwable) {
            return Imported(name, written, "拆帧写到一半停了: ${error.message ?: error}")
        } finally {
            bitmap.recycle()
        }
        return Imported(name, written, "")
    }

    /** 一个名字对应的帧文件 (给组件那边拼 content:// 用) */
    internal fun frames(context: Context, id: String, name: String): List<File> =
        File(File(facesDir(context, id), name), "")
            .let { directory ->
                directory.listFiles().orEmpty()
                    .filter { it.isFile && it.name.startsWith("frame-") && it.name.endsWith(".png") }
                    .sortedBy { it.name }
            }

    /** 存在设置里的那个值: `gif:<名字>` */
    fun valueOf(name: String): JsonPrimitive = JsonPrimitive("gif:$name")

    /** 从设置里那个值里取名字 (不是 gif: 开头就回 null) */
    fun nameOf(value: String): String? = value.removePrefix("gif:").takeIf { value.startsWith("gif:") && it.isNotBlank() }

    /** 导入时那个名字: 文件名的最后一段 (去扩展名), 只留字母数字与下划线连字符 */
    private fun sanitize(uri: Uri): String {
        val raw = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
        val base = raw.substringBeforeLast('.').ifBlank { "gif" }
        return base.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifBlank { "gif" }
    }

    /** 重名就加序号 (两份 gif 不许互相覆盖) */
    private fun uniqueName(root: File, base: String): String {
        if (!File(root, base).exists()) return base
        var index = 2
        while (File(root, "$base-$index").exists()) index += 1
        return "$base-$index"
    }

    /** 那一份 gif 的帧率固定写成一个数: gif 自己的时长已经拆进帧数里了, 播放节奏只要求"看起来在动" */
    const val FLIP_INTERVAL_MS = STEP_MS
}

