package io.github.yuloong07star.luwi.voice

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * 语音状态的**只读**查询出口 (2.7.9 批次 9 T17, 施工单 §8.3 级二)
 *
 * 给没有 root 的普通用户: 广播是"跳变时推一条", provider 是"我想看一眼现在是什么" —— 两条通道由
 * 同一个开关管着 ([VoiceSettings.exportTarget]), 关着时这里回 `enabled=false` 且其余两列为空
 *
 * 契约 (施工单 §8.7):
 *
 * | 列 | 值 |
 * | :-- | :-- |
 * | `state` | `idle` / `listening` / `thinking` / `speaking` |
 * | `session` | 不透明会话 id, 可空 |
 * | `enabled` | 1 = 出口开着且这次查询被允许, 0 = 没有 |
 *
 * **不给的东西**: 麦克风音频、音量电平、反向控制 —— 这是一个只读的、被开关关着的出口,
 * 能看出"主人在说话"已经是它最大的信息量
 *
 * 调用者限制: 设置页点名了那个包时, 只答它 (以及本应用自己)。`callingPackage` 拿不到时
 * (系统 / shell 的某些查询路径) 按"设置页已经点过名"放行 —— 那时没有更细的判据可用了
 */
class VoiceStateProvider : ContentProvider() {

    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = arrayOf(COLUMN_STATE, COLUMN_SESSION, COLUMN_ENABLED)
        val cursor = MatrixCursor(columns)
        val app = context
        val target = app?.let { VoiceSettings.exportTarget(it) }
        if (app == null || target == null) {
            cursor.addRow(arrayOf<Any>("", "", 0))
            return cursor
        }
        val caller = runCatching { callingPackage }.getOrNull()
        val permitted = caller == null || caller == target || caller == app.packageName
        // 一行读数: 谁问的、放没放行。这一条通道没有任何音频与文本, 记调用者包名是排障最省事的一笔
        Log.i(TAG, "a query from ${caller ?: "an unknown caller"} against $target: permitted=$permitted")
        if (!permitted) {
            cursor.addRow(arrayOf<Any>("", "", 0))
            return cursor
        }
        cursor.addRow(arrayOf<Any>(VoiceLive.state, VoiceLive.session.orEmpty(), 1))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/voice-state"

    /** 只读: 三个写入口一律拒 (拒得响亮, 不假装成功) */
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("the voice state is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("the voice state is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("the voice state is read-only")

    companion object {
        private const val TAG = "VoiceStateProvider"
        /** 与施工单 §8.7 钉的是同一个 authority 与 path */
        const val AUTHORITY = "io.github.yuloong07star.luwi.lw.voice"
        const val PATH = "state"

        const val COLUMN_STATE = "state"
        const val COLUMN_SESSION = "session"
        const val COLUMN_ENABLED = "enabled"
    }
}
