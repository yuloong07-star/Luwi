package io.github.yuloong07star.luwi.plugin.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yuloong07star.luwi.plugin.PluginManager
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

    /**
     * 一帧序列的上限
     *
     * 2026-10-15 由 32 一路提到 **128**: 主人报"帧率有点低" —— 32 帧盖一个动作周期只有 4 fps,
     * 确实卡。现在这一档配 12 fps 用 (覆盖 10.67 s, 原片 10 s, 动作速度几乎不变)。
     *
     * 卡这个数的**不是包体而是内存**: 解码后的帧同时只留当前那一个状态
     * ([BallView.faceFrames] 换状态就整批换掉), 128 帧按球上那个解码尺寸约 16 MB; 再往上翻
     * 就得先想清楚低端机那点堆
     */
    private const val MAX_FRAMES = 128
    private const val MAX_FPS = 30

    @Serializable
    data class Choice(val id: String = "")

    /** 一串帧: 目录 + 张数 + 每秒几张 */
    data class Frames(val dir: String, val count: Int, val fps: Int) {
        val intervalMs: Int get() = (1000 / fps).coerceAtLeast(1)

        fun frameName(index: Int): String = "frame-%02d.png".format(index)

        fun frameNames(): List<String> = (0 until count).map { frameName(it) }
    }

    /**
     * 素材怎么装进球里
     *
     * [CONTAIN] 是给"人物代替球"那一档的: 素材是全身立绘, 而球体那一层带着圆裁剪
     * ([BallView] 的 `clipToOutline`) —— 裁圆会掉头和脚, 所以这一档要不裁圆、按比例装进方窗。
     * 缺省仍是 [COVER] (有插件之前一直就是这个行为, 不能因为加了字段就变样)
     */
    enum class Fit {
        COVER,
        CONTAIN,
    }

    /** `theme/ball.json` 那一份 (老协议 10.1 的字段, 原样收; `ring` 是 2026-10-16 收的那一档) */
    data class BallSpec(
        val name: String,
        val hideBuiltinText: Boolean,
        val states: Map<String, String>,
        val frames: Map<String, Frames>,
        /** 每个状态一个环色 (已解析成 ARGB), 缺哪个状态就回内置那一套 */
        val rings: Map<String, Int>,
        /** 素材怎么装 (见 [Fit]), 缺省 [Fit.COVER] */
        val fit: Fit = Fit.COVER,
        /**
         * 这份外观**至少要多大**才画得下 (dp), 缺省 0 = 没要求
         *
         * 它不是"抢主人的设置"而是"它自己画不下": 鲸鱼娘那份是全身立绘, 缩到 48 dp 时五官糊成一团,
         * 所以声明 72。球最终多大由 `BallSize.resolve` 取**设置值与这个数里大的那个** ——
         * 主人拉到 80 就用 80, 留在 48 时插件球仍是 72 (2026-10-15 主人:
         * "ball 改为原来的尺寸, 鲸鱼娘 ball 也保持当前尺寸 72")
         */
        val minDp: Int = 0,
    )

    /**
     * "挑一个外观"的候选
     *
     * @property id 插件 id, 空串 = 内置画法
     * @property name 显示用 (取 `theme/ball.json` 的 `name`, 读不出来就退插件 id)
     */
    data class Pick(val id: String, val name: String)

    /** 现在生效的那一份 */
    data class Active(val id: String, val directory: File, val spec: BallSpec)

    /** 某个状态那一张脸: 一张图, 或者一串帧 */
    data class Face(val file: File, val frames: Frames?, val hideBuiltinText: Boolean, val fit: Fit)

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
        return Face(file, active.spec.frames[state], active.spec.hideBuiltinText, active.spec.fit)
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
        val rings = (root["ring"] as? JsonObject).orEmpty().mapValues { (state, value) ->
            parseColor((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?: throw IllegalArgumentException("theme/ball.json 的 ring.$state 要是一个 #RRGGBB 或 #AARRGGBB 的颜色")
        }
        // fit 缺省是 cover (加这个字段之前一直就是这个行为, 不能让老插件因为没写它而变样)
        val fitText = (root["fit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val fit = when (fitText?.lowercase()) {
            null, "cover" -> Fit.COVER
            "contain" -> Fit.CONTAIN
            else -> throw IllegalArgumentException("theme/ball.json 的 fit 只认 cover 或 contain")
        }
        // minDp: 这份外观至少要多大 (缺省 0 = 不要求); 上界 200 是防手滑写成一个天文数字
        val minDp = (root["minDp"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        if (minDp !in 0..200) {
            throw IllegalArgumentException("theme/ball.json 的 minDp 要在 0..200 之间")
        }
        return BallSpec(name, hide, states, frames, rings, fit, minDp)
    }

    /**
     * 解一个 `#RRGGBB` / `#AARRGGBB` 的颜色
     *
     * 纯算术 (不走 `android.graphics.Color.parseColor`), 所以它能在单元测试里钉住 —— 主题里写错一个
     * 颜色不该让球退回内置, 只该那一档用不上
     */
    fun parseColor(text: String?): Int? {
        val raw = text?.trim().orEmpty()
        if (!raw.startsWith("#")) return null
        val digits = raw.substring(1)
        val value = digits.toLongOrNull(16) ?: return null
        return when (digits.length) {
            6 -> (0xFF000000L or value).toInt()
            8 -> value.toInt()
            else -> null
        }
    }

    /**
     * 现在能挑的候选: **内置画法永远在第 0 位**, 后面是装了带 `theme/ball.json` 的那些插件
     *
     * 与设置页那份对话框 (SettingsScreen 的 `BallThemeDialog`) 用**同一个口径** —— 名字取插件的
     * manifest, 不取 ball.json (那一个是素材自己的说法, 主人在界面上看到的是插件名)
     */
    fun choices(context: Context): List<Pick> = buildList {
        add(Pick("", ""))  // 名字由调用方用内置那一条字符串给 (这里不碰 android 资源)
        PluginManager.entries(context).forEach { entry ->
            val directory = entry.directory ?: return@forEach
            if (File(directory, "theme/ball.json").isFile) add(Pick(entry.id, entry.name))
        }
    }

    /**
     * 换下一个: 轮换表就是 [choices] 的顺序 (内置 → 各插件), 只有一项时保持不动
     *
     * @return 换完之后的 id (调用方拿它刷新球)
     */
    fun rotate(context: Context): String {
        val picks = choices(context)
        if (picks.size <= 1) return currentId(context)
        val at = picks.indexOfFirst { it.id == currentId(context) }.let { if (it < 0) 0 else it }
        val next = picks[(at + 1) % picks.size]
        choose(context, next.id)
        return next.id
    }

    /** 现在生效那一套的显示名 (内置时是空串, 由调用方用 `ball_theme_builtin` 那一条给) */
    fun nameOf(context: Context, id: String): String =
        choices(context).firstOrNull { it.id == id }?.name.orEmpty()

    /** 这一套给"某一个状态"的环色 (没有这一档 / 没挑主题 / 没写这个状态, 都是 null = 回内置) */
    fun ring(context: Context, state: String): Int? =
        active(context)?.spec?.rings?.get(state)
}
