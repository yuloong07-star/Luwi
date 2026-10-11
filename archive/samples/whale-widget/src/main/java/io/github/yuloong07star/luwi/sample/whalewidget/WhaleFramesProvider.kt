package io.github.yuloong07star.luwi.sample.whalewidget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 把解好的逐帧图交给桌面那一侧
 *
 * 小组件是在**启动器那个进程**里画的, 而 app 私有目录它读不到 —— 所以导入那一份的帧走内容 URI:
 * 宿主自己去解图, 一次 binder 事务里只过一个短字符串 (32 张位图塞进去会撞上 1 MB 的上限)
 *
 * 只读、只认自己写下的那两种名字 (`<键>/frame-00.png`), 别的路径一律拒; provider 由
 * `grantUriPermissions` 放给小组件宿主一条一次性的读权限 (见清单里那一条)
 */
class WhaleFramesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val segments = uri.pathSegments
        if (segments.size != 2) throw FileNotFoundException("unexpected $uri")
        val key = segments[0]
        val name = segments[1]
        // 路径是拼出来的, 所以这里要卡死形状: 只认"我们自己写下的那一个目录名与那一串帧名"
        if (!key.matches(KEY_RULE) || !name.matches(FRAME_RULE)) {
            throw FileNotFoundException("refusing $uri")
        }
        val root = context ?: throw FileNotFoundException("no context")
        val file = File(File(WhaleImports.root(root), key), name)
        if (!file.isFile) throw FileNotFoundException("nothing at $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

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
        const val AUTHORITY = "io.github.yuloong07star.luwi.sample.whalewidget.frames"

        private val KEY_RULE = Regex("[a-z0-9][a-z0-9-]{0,31}")
        private val FRAME_RULE = Regex("frame-\\d{2}\\.png")

        /** 一套导入里第 [frame] 帧的 URI */
        fun uri(key: String, frame: Int): Uri =
            Uri.parse("content://$AUTHORITY/$key/" + "frame-%02d.png".format(frame))
    }
}
