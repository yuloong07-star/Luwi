package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把一个本地文件交给系统里接这个类型的那个应用
 *
 * 两条入口走同一条路: `lw_open_file` 那个工具, 以及网页预览里那颗「用系统文件管理器打开」
 * (见 `HostScreen` 的 `luwi-open:` 拦截)。Android 10 起 `file://` 一律被拒 (FileUriExposedException),
 * 所以交出去的一定是 FileProvider 发的 `content://` + 一份读授权
 *
 * **不猜路径**: 网页那一侧传来的路径要先过 [allowed], 只认工作区与 `$DSH_HOME` 两个根 —— 预览里
 * 的文档是用户自己的文件, 但它也可能夹一条 `luwi-open:` 链接, 点一下就把任意路径递给别的应用
 * 这件事不该发生
 */
object OpenFile {

    /** 工作区与 `$DSH_HOME` 之下才许交出去; 别的路径返回 null */
    fun allowed(context: Context, path: String): File? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!file.isFile) return null
        val roots = listOf(
            File(File(context.filesDir, "DSH").absolutePath),
            File(context.filesDir, "dsh-home"),
        ) + runCatching { listOf(io.github.yuloong07star.luwi.workspace.Workspace.resolve(context).directory) }
            .getOrDefault(emptyList())
        val inside = roots.any { root ->
            val prefix = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath) + File.separator
            file.path.startsWith(prefix)
        }
        return if (inside) file else null
    }

    /** 文件 -> 对方能读的 uri 与猜出来的 mime */
    fun uriAndType(context: Context, file: File): Pair<Uri, String> {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.lw.files", file)
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        return uri to mime
    }

    /** 交给系统的那条 intent: 调用方自己 startActivity, 好按自己的方式报"没有应用能开" */
    fun viewIntent(context: Context, file: File): Intent {
        val (uri, mime) = uriAndType(context, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
