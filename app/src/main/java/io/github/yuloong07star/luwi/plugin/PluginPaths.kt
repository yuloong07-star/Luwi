package io.github.yuloong07star.luwi.plugin

import java.io.File

/**
 * 包内相对路径的校验 (方案 3.2 那条路径纪律)
 *
 * `art` 域是最容易手滑的一档: 它连编译都没有, 一个 `../../` 就是一次越界读 —— 而它又是唯一一个
 * "零审就能装"的域。所以同一条规矩**查两遍**:
 *
 * 1. 装的时候静态查一次 (与签名、逐文件哈希同一趟, 见 [PluginInstaller])
 * 2. 运行时再兜一次 (插件目录是主人能手工改的地方, 装完之后被塞一个符号链接进来是真的)
 *
 * 两遍都走同一个 [resolve]: 拒绝绝对路径 / 任何一段是 `..` 或 `.` / 反斜杠 / 盘符; 解出来必须落在
 * 插件目录之内, 而且比的是 `canonicalPath` 前缀而不是字符串 —— 字符串前缀挡不住符号链接
 */
internal object PluginPaths {

    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * 只查形状 (不碰盘): 相对路径、正斜杠、没有空段 / `.` / `..`
     *
     * 它给两条路共用: [resolve] 里那一段, 以及 `entry.dex` 这种"落地时才解"的路径
     */
    fun requireRelative(relative: String, what: String) {
        val path = relative.trim()
        if (path.isEmpty()) throw IllegalArgumentException("$what 是空的")
        if (path.startsWith("/") || DRIVE.containsMatchIn(path)) {
            throw IllegalArgumentException("$what 只能是包内的相对路径, \"$relative\" 从斜杠或者盘符起头")
        }
        if (path.contains('\\')) {
            throw IllegalArgumentException("$what 要用正斜杠, \"$relative\" 里出现了反斜杠")
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IllegalArgumentException("$what 里不许有空段 / `.` / `..`: \"$relative\"")
        }
    }

    /**
     * 包内相对路径 → 解出来的文件 (不要求存在)
     *
     * @param what 出错那句话里点的名, 例如 `widget.json 的 frames.dir`
     */
    fun resolve(root: File, relative: String, what: String): File {
        requireRelative(relative, what)
        val path = relative.trim()
        val target = File(root, path)
        val base = root.canonicalPath
        val full = target.canonicalPath
        if (full != base && !full.startsWith(base + File.separator)) {
            throw IllegalArgumentException("$what 解出来跑到插件目录外面去了: \"$relative\"")
        }
        return target
    }

    /** 解出来并且要求它就在那儿 (目录或文件都行) */
    fun resolveExisting(root: File, relative: String, what: String): File {
        val file = resolve(root, relative, what)
        if (!file.exists()) throw IllegalArgumentException("$what 说的 \"$relative\" 在包里找不到")
        return file
    }

    fun resolveDirectory(root: File, relative: String, what: String): File {
        val file = resolveExisting(root, relative, what)
        if (!file.isDirectory) throw IllegalArgumentException("$what 说的 \"$relative\" 不是一个目录")
        return file
    }

    fun resolveFile(root: File, relative: String, what: String): File {
        val file = resolveExisting(root, relative, what)
        if (!file.isFile) throw IllegalArgumentException("$what 说的 \"$relative\" 不是一个文件")
        return file
    }

    /**
     * 装的时候把清单里每一处资源路径都走一遍 (只对带 `widget` 的包有意义)
     *
     * 逐帧图那一条查得比"目录在不在"细: 它按 `frame-00.png` 那一套名字**逐张** 查存在, 于是
     * "写了 24 张而包里只有 20 张"在装机时就说清, 而不是装完在桌面上少几张
     */
    fun checkPackage(manifest: PluginManifest, directory: File): String? = try {
        manifest.widget?.let { widget -> checkWidget(widget, directory) }
        checkBallTheme(directory)
        null
    } catch (error: IllegalArgumentException) {
        error.message
    }

    /** 主题那一份 (协议 10.1): 写了 `theme/ball.json` 就把里面每一处路径也查一遍 */
    private fun checkBallTheme(directory: File) {
        val file = File(directory, "theme/ball.json")
        if (!file.isFile) return
        val spec = try {
            io.github.yuloong07star.luwi.plugin.theme.BallTheme.parse(file.readText())
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("theme/ball.json 读不出来: ${error.message}")
        }
        spec.states.forEach { (state, path) ->
            resolveFile(directory, path, "theme/ball.json 的 states.$state")
        }
        spec.frames.forEach { (state, frames) ->
            resolveDirectory(directory, frames.dir, "theme/ball.json 的 frames.$state 的 dir")
            frames.frameNames().forEach { name ->
                resolveFile(directory, "${frames.dir}/$name", "theme/ball.json 的 frames.$state")
            }
        }
    }

    private fun checkWidget(widget: io.github.yuloong07star.luwi.plugin.widget.WidgetSpec, directory: File) {
        if (widget.slot == io.github.yuloong07star.luwi.plugin.widget.WidgetSlot.ANIM) {
            widget.animationSets().forEach { set ->
                val what = "widget.json 那套 \"${set.key}\" 的 dir"
                val dir = resolveDirectory(directory, set.dir, what)
                dir.listFiles()?.forEach { child ->
                    if (child.isFile && child.name != "README.md") {
                        // 目录里除说明之外的东西都要是图, 免得把一份 zip 当成帧交出去
                        val name = child.name.lowercase()
                        if (!name.endsWith(".png") && !name.endsWith(".webp")) {
                            throw IllegalArgumentException("$what 里只能放逐帧图, \"${child.name}\" 不是一张图")
                        }
                    }
                }
                set.frameNames().forEach { name ->
                    val frame = resolve(directory, "${set.dir}/$name", what)
                    if (!frame.isFile) {
                        throw IllegalArgumentException("$what 说 ${set.count} 张, 而 \"$name\" 在包里没有")
                    }
                }
            }
        }
        if (widget.image.isNotBlank()) {
            resolveFile(directory, widget.image, "widget.json 的 image")
        }
    }
}
