package io.github.yuloong07star.luwi.voice

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 语音状态的**只读**查询出口 (2.7.9 批次 9 T17)
 *
 * 给脚本与第三方应用问"她现在在做什么": root 脚本直接读
 * `$DSH_HOME/lw/voice-live.json`, 没有 root 的那一侧读这里 —— 一条 `content query` 就够
 *
 * **默认就是开的** (主人 2026-10-20: "默认打开接口就行了, 使脚本能检测到, 不用多余增加设置段"):
 * 这里没有开关、没有包名白名单, 也没有广播。早先那一版是"缺省关 + 设置页填包名 + 显式广播",
 * 主人把设置与广播一起砍了 —— 出口只留一个能读的接口
 *
 * 契约 (施工单 §8.7):
 *
 * | 列 | 值 |
 * | :-- | :-- |
 * | `state` | `idle` / `listening` / `thinking` / `speaking` |
 * | `session` | 不透明会话 id, 可空 |
 * | `enabled` | 恒 1 (留给老读者: 从前它表示"开关开没开") |
 *
 * **不给的东西**: 麦克风音频、音量电平、反向控制 —— 这是一个只读的出口, 能看出"主人在说话"已经
 * 是它最大的信息量
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
        /** 与施工单 §8.7 钉的是同一个 authority 与 path */
        const val AUTHORITY = "io.github.yuloong07star.luwi.lw.voice"
        const val PATH = "state"

        const val COLUMN_STATE = "state"
        const val COLUMN_SESSION = "session"
        const val COLUMN_ENABLED = "enabled"
    }
}
