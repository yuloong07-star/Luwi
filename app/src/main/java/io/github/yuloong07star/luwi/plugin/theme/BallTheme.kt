package io.github.yuloong07star.luwi.plugin.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yuloong07star.luwi.plugin.PluginPaths
import io.github.yuloong07star.luwi.plugin.PluginStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 浮标外观那一档 (协议 10.1, 方案 3.2 把它并进 `art` 域)
 *
 * 插件包里带一份 `theme/ball.json` 与几张图, 主人**在设置页里挑一个用** (多个主题插件可以同时装着,
 * 同一时刻只有一个生效)。挑中的那一份:
 *
 * - 每个状态 (`idle` / `listening` / `thinking` / `speaking` / `failed`) 可以给一张图
 * - 也可以给一串帧 (`frames`: 目录 + 张数 + 每秒几张), 球的 `BallView` 按那个节奏换
 * - 读不到 / 解不出来**一律回退内置画法** —— "换一套图标生效; 删掉插件回退内置" 那一条验收
 *
 * 路径那两条纪律在这里同样管: 解出来的图必须落在插件目录里 (装的时候查一次, 读的这一刻再兜一次)
 */
internal object BallTheme {

    private const val CHOICE = ".theme.json"

    /** 一帧序列的上限: 球比桌面那一格小得多, 再多也看不出来, 只是白占内存 */
    private const val MAX_FRAMES = 32
    private const val MAX_FPS = 30

    @Serializable
    data class Choice(val id: String = "")

    /** 一串帧: 目录 + 张数 + 每秒几张 */
    data class Frames(val dir: String, val count: Int, val fps: Int) {
        val intervalMs: Int get() = (1000 / fps).coerceAtLeast(1)

        fun frameName(index: Int): String = "frame-%02d.png".format(index)

        fun frameNames(): List<String> = (0 until count).map { frameName(it) }
    }

    /** `theme/ball.json` 那一份 (老协议 10.1 的字段, 原样收) */
    data class BallSpec(
        val name: String,
        val hideBuiltinText: Boolean,
        val states: Map<String, String>,
        val frames: Map<String, Frames>,
    )

    /** 现在生效的那一份 */
    data class Active(val id: String, val directory: File, val spec: BallSpec)

    /** 某个状态那一张脸: 一张图, 或者一串帧 */
    data class Face(val file: File, val frames: Frames?, val hideBuiltinText: Boolean)

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    /** 现在挑的是哪一个 (空串 = 内置画法) */
    fun currentId(context: Context): String {
        val file = File(PluginStore.root(context), CHOICE)
        if (!file.isFile) return ""
        return runCatching { json.decodeFromString<Choice>(file.readText()).id }.getOrDefault("")
    }

    fun choose(context: Context, id: String?) {
        File(PluginStore.root(context), CHOICE).writeText(json.encodeToString(Choice(id.orEmpty())) + "\n")
    }

    /** 现在生效的那一份; 读不出来 (插件被删 / JSON 坏了 / 图不在) 就是 null = 回内置 */
    fun active(context: Context): Active? {
        val id = currentId(context).takeIf { it.isNotBlank() } ?: return null
        val directory = PluginStore.packageDir(context, id) ?: return null
        val file = File(directory, "theme/ball.json")
        if (!file.isFile) return null
        val spec = runCatching { parse(file.readText()) }.getOrNull() ?: return null
        return Active(id, directory, spec)
    }

    /**
     * 这个状态该画哪一张
     *
     * @param state `idle` / `listening` / `thinking` / `speaking` / `failed` (见 `BallWord`)
     */
    fun face(context: Context, state: String): Face? {
        val active = active(context) ?: return null
        val path = active.spec.states[state] ?: return null
        // 运行时那一半路径纪律: 插件目录是主人能手工改的地方
        val file = runCatching {
            PluginPaths.resolve(active.directory, path, "theme/ball.json 的 states.$state")
        }.getOrNull() ?: return null
        if (!file.isFile) return null
        return Face(file, active.spec.frames[state], active.spec.hideBuiltinText)
    }

    /** 一帧序列里每一张的位置 (同样每张再解一次路径) */
    fun framesOf(context: Context, state: String, frames: Frames): List<File>? {
        val active = active(context) ?: return null
        return runCatching {
            frames.frameNames().map { name ->
                val file = PluginPaths.resolve(active.directory, "${frames.dir}/$name", "theme/ball.json 的 frames.$state")
                if (!file.isFile) throw IllegalArgumentException("$name 不在")
                file
            }
        }.getOrNull()
    }

    /**
     * 解一张图: 球才 48 dp, 按显示尺寸缩着解 —— 原图可能上千像素, 直接解进内存是白占
     *
     * 带一层缓存 (同一个文件第二次不再解码); 文件改了 (大小或时间变) 就重新解
     */
    private val cache = LinkedHashMap<String, Pair<Long, Bitmap>>()

    fun decode(file: File, maxPx: Int): Bitmap? {
        val stamp = file.length() * 31 + file.lastModified()
        synchronized(cache) {
            cache[file.absolutePath]?.let { (at, bitmap) -> if (at == stamp) return bitmap }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        synchronized(cache) {
            if (cache.size > 32) cache.clear()
            cache[file.absolutePath] = stamp to bitmap
        }
        return bitmap
    }

    /** 一份 `ball.json` 的形状校验; 不合法就抛一句点名的话 */
    fun parse(text: String): BallSpec {
        val root = json.parseToJsonElement(text).jsonObject
        val name = (root["name"] as? JsonPrimitive)?.content.orEmpty()
        if (name.length > 40) throw IllegalArgumentException("theme/ball.json 的 name 最多 40 个字")
        val hide = (root["hideBuiltinText"] as? JsonPrimitive)?.booleanOrNull ?: false
        val states = (root["states"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("theme/ball.json 的 states 里每一张都要是一个路径")
        }
        val frames = (root["frames"] as? JsonObject).orEmpty().mapValues { (state, value) ->
            val one = value as? JsonObject
                ?: throw IllegalArgumentException("theme/ball.json 的 frames.$state 要是一个对象")
            val dir = (one["dir"] as? JsonPrimitive)?.content.orEmpty()
            if (dir.isBlank()) throw IllegalArgumentException("theme/ball.json 的 frames.$state 缺 dir")
            val count = (one["count"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: throw IllegalArgumentException("theme/ball.json 的 frames.$state 的 count 要是整数")
            val fps = (one["fps"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: throw IllegalArgumentException("theme/ball.json 的 frames.$state 的 fps 要是整数")
            if (count !in 1..MAX_FRAMES) {
                throw IllegalArgumentException("theme/ball.json 的 frames.$state 的 count 要在 1..$MAX_FRAMES 之间")
            }
            if (fps !in 1..MAX_FPS) {
                throw IllegalArgumentException("theme/ball.json 的 frames.$state 的 fps 要在 1..$MAX_FPS 之间")
            }
            Frames(dir, count, fps)
        }
        if (states.isEmpty() && frames.isEmpty()) {
            throw IllegalArgumentException("theme/ball.json 里 states 与 frames 至少要有一段")
        }
        return BallSpec(name, hide, states, frames)
    }
}
