package io.github.yuloong07star.luwi.plugin.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.yuloong07star.luwi.plugin.PluginPaths
import io.github.yuloong07star.luwi.plugin.PluginStore
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import java.io.File
import java.io.FileNotFoundException

/**
 * 把插件目录里的帧图 / 配图交给桌面那一侧 (方案 3.1)
 *
 * 桌面那一格是在**启动器那个进程**里画的, 而帧图住在 Luwi 的应用私有目录里 —— 所以走内容 URI:
 * 宿主自己去解图, 一次 binder 事务里只过一个短字符串 (几十张位图塞进去会撞上 1 MB 的上限)。
 * 读权限由 [WidgetHost] 每一趟组装前放给"现在正在当桌面"的那几个包
 *
 * 这里只读, 而且路径**再兜一次** [PluginPaths] —— 插件目录是主人能手工改的地方, 装的时候查过不等于
 * 现在还是那个样子
 */
class WidgetFramesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val segments = uri.pathSegments
        val context = context ?: throw FileNotFoundException("no context")
        val file = when {
            segments.size == 4 && segments[0] == "frame" -> frame(context, segments[1], segments[2], segments[3])
            segments.size == 3 && segments[0] == "theme" -> themeFrame(context, segments[1], segments[2])
            segments.size == 2 && segments[0] == "theme" -> themeFace(context, segments[1])
            segments.size == 2 && segments[0] == "image" -> image(context, segments[1])
            // 插件自己包里的一张图 (2.7.8 批次 6): 对话槽的「显示的人物」可以是插件自带那张
            segments.size == 2 && segments[0] == "file" -> pluginFile(context, segments[1], uri.getQueryParameter("p"))
            else -> throw FileNotFoundException("refusing $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 插件包里的一个文件 (相对路径再过一次 [PluginPaths]: 插件目录是主人能手工改的地方) */
    private fun pluginFile(context: android.content.Context, id: String, path: String?): File {
        if (!ID_RULE.matches(id)) throw FileNotFoundException("refusing plugin file $id")
        val relative = path.orEmpty()
        if (relative.isBlank()) throw FileNotFoundException("refusing plugin file $id (no path)")
        val directory = PluginStore.packageDir(context, id) ?: throw FileNotFoundException("$id 不在了")
        val file = try {
            PluginPaths.resolve(directory, relative, "ui.settings 的 face")
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException(error.message)
        }
        if (!file.isFile) throw FileNotFoundException("nothing at $file")
        return file
    }

    /**
     * 浮标外观那一套的某一帧 (2.7.8 批次 6, 对话槽那块立绘)
     *
     * 对话槽的立绘**不是插件 widget 的帧**, 而是主人挑的那套外观 (`theme/ball.json` 的 `frames`
     * 那一段) —— 于是它要单独一条路由, 解路径仍然走 `BallTheme` (它自己再兜一次 [PluginPaths])
     */
    private fun themeFrame(context: android.content.Context, state: String, name: String): File {
        if (!STATE_RULE.matches(state) || !FRAME_RULE.matches(name)) {
            throw FileNotFoundException("refusing theme frame $state/$name")
        }
        val file = BallTheme.frameFile(context, state, name)
            ?: throw FileNotFoundException("nothing at theme/$state/$name")
        return file
    }

    /** 浮标外观那一套的静态那一张 (`states.<state>`) */
    private fun themeFace(context: android.content.Context, state: String): File {
        if (!STATE_RULE.matches(state)) throw FileNotFoundException("refusing theme face $state")
        val file = BallTheme.face(context, state)?.file
            ?: throw FileNotFoundException("nothing at theme/$state")
        if (!file.isFile) throw FileNotFoundException("nothing at $file")
        return file
    }

    private fun frame(context: android.content.Context, id: String, set: String, name: String): File {
        if (!ID_RULE.matches(id) || !SET_RULE.matches(set) || !FRAME_RULE.matches(name)) {
            throw FileNotFoundException("refusing frame $id/$set/$name")
        }
        val directory = PluginStore.packageDir(context, id) ?: throw FileNotFoundException("$id 不在了")
        val spec = PluginStore.manifest(context, id)?.widget ?: throw FileNotFoundException("$id 没有 widget")
        val frames = spec.animationSets().firstOrNull { it.key == set }
            ?: throw FileNotFoundException("$id 没有 $set 那一套")
        val file = try {
            PluginPaths.resolve(directory, "${frames.dir}/$name", "帧图")
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException(error.message)
        }
        if (!file.isFile) throw FileNotFoundException("nothing at $file")
        return file
    }

    private fun image(context: android.content.Context, id: String): File {
        if (!ID_RULE.matches(id)) throw FileNotFoundException("refusing image $id")
        val directory = PluginStore.packageDir(context, id) ?: throw FileNotFoundException("$id 不在了")
        val path = PluginStore.manifest(context, id)?.widget?.image?.takeIf { it.isNotBlank() }
            ?: throw FileNotFoundException("$id 没有配图")
        val file = try {
            PluginPaths.resolve(directory, path, "widget.json 的 image")
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException(error.message)
        }
        if (!file.isFile) throw FileNotFoundException("nothing at $file")
        return file
    }

    override fun getType(uri: Uri): String = when (uri.pathSegments.firstOrNull()) {
        "image" -> "image/*"
        else -> "image/png"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("this provider is read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("this provider is read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("this provider is read only")

    companion object {

        /** 与清单里那条 `<provider>` 的 authorities 是同一个字**
         *
         * 它不写成 `${applicationId}` 是因为要能被 Kotlin 这一侧拼出来; applicationId 本身是写死的
         * (见 app/build.gradle.kts), 两处漂开时静态校验会拦住 (`tools/check-plugins.mjs`)
         */
        const val AUTHORITY = "io.github.yuloong07star.luwi.lw.widget"

        private val ID_RULE = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        private val SET_RULE = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
        private val FRAME_RULE = Regex("^frame-\\d{2,4}\\.png$")

        /** 浮标外观那五个状态名 (`idle` / `listening` / `thinking` / `speaking` / `failed`) */
        private val STATE_RULE = Regex("^[a-z]{2,16}$")

        fun uri(id: String, set: String, frame: Int): Uri =
            Uri.parse("content://$AUTHORITY/frame/$id/$set/frame-%02d.png".format(frame))

        fun imageUri(id: String): Uri = Uri.parse("content://$AUTHORITY/image/$id")

        /** 当前浮标外观里 `state` 那一张静态图 */
        fun themeFaceUri(state: String): Uri = Uri.parse("content://$AUTHORITY/theme/$state")

        /** 当前浮标外观里 `state` 那一串帧的第 [frame] 张 */
        fun themeFrameUri(state: String, frame: Int): Uri =
            Uri.parse("content://$AUTHORITY/theme/$state/frame-%02d.png".format(frame))

        /** 插件包里的一张图 (对话槽「显示的人物」选了插件自带那张时用) */
        fun pluginFileUri(id: String, path: String): Uri =
            Uri.parse("content://$AUTHORITY/file/$id").buildUpon()
                .appendQueryParameter("p", path)
                .build()
    }
}
