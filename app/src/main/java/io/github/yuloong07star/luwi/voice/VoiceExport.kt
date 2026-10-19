package io.github.yuloong07star.luwi.voice

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 语音状态的两级出口里的"广播"那一级 (2.7.9 批次 9 T17, 施工单 §8.3 级二)
 *
 * 给**没有 root 的普通用户**: 别的应用收不到文件 (它在应用私有目录里), 也不该去轮询, 所以这里在
 * 状态跳变时发一条**显式**广播 (`setPackage`), 收件人只有设置页点名的那一个包
 *
 * 三条闸, 一条都不能省 (施工单 §8.7):
 *
 * 1. **缺省关** ([VoiceSettings.exportEnabled] 缺省 false)
 * 2. **必须点名一个包** ([VoiceSettings.exportTarget] 回 null 就什么都不发)
 * 3. **只带 state 与 session**, 不带任何文本、不带会话标题 —— `at` 也不进广播
 */
internal object VoiceExport {

    private const val TAG = "VoiceExport"

    /** 契约里那个 action (只加键不删键, 见施工单 §8.7) */
    const val ACTION = "io.github.yuloong07star.luwi.VOICE_STATE"

    /** extra 的名字与文件里的键同名: 两个通道读出来是同一个词 */
    const val EXTRA_STATE = "state"
    const val EXTRA_SESSION = "session"

    fun announce(context: Context, state: String, session: String?) {
        // 开关关着、包名没填、或包名不像包名: 整条出口就是关着 (见 VoiceSettings.exportTarget)
        val target = VoiceSettings.exportTarget(context)
        if (target == null) {
            Log.i(TAG, "no broadcast for $state: the export is closed (${VoiceSettings.exportPackage(context)})")
            return
        }
        val intent = Intent(ACTION)
            .setPackage(target)
            .putExtra(EXTRA_STATE, state)
        // 会话 id 可空: 空的时候整个键都不放, 而不是放一个空串让对面去判
        if (!session.isNullOrBlank()) intent.putExtra(EXTRA_SESSION, session)
        runCatching { context.sendBroadcast(intent) }
            .onSuccess { Log.i(TAG, "sent $state to $target") }
            .onFailure {
                Log.w(TAG, "could not send the voice state to $target: ${it.message ?: it}")
            }
    }
}
