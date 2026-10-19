package io.github.yuloong07star.luwi.voice

import android.content.Context
import android.util.Log
import io.github.yuloong07star.luwi.host.DshHost
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 语音状态那一份文件: `$DSH_HOME/lw/voice-live.json` (2.7.9 批次 9 T16)
 *
 * 为什么是文件而不是推送: 主人那个呼吸灯脚本是 **root 侧**的 shell, 它只能读文件 (见施工单 §8.3
 * 的两级出口)。文件当那一级出口有三个好处 —— 脚本一个字都不用改 (它从前读的是插件猜出来的
 * `lw-live.json`)、应用重启/脚本重启都是读一次就对、没有任何"推送丢了没人补"的窗口
 *
 * **零轮询**: 这不是"每 400 ms 写一次", 而是**只在状态真的跳变时写一次** —— [publish] 拿新状态与
 * 上一次相比, 一样就一个字节都不碰。三个状态各自的判据就长在 `OverlayService.refresh` 那一处
 * (`speaking` / `thinking` / `listening` 与球上那三个字**同一套**, 见施工单 §8.7 第一条)
 *
 * 形状 (只加键不删键):
 *
 * ```json
 * {"v":1,"state":"idle|listening|thinking|speaking","session":"…","at":1759600000000}
 * ```
 *
 * `at` **只在这里**, 不进广播、也不进 provider (主人口径: "这两个就够了")。`session` 可为空 ——
 * 它只是"回应用"那一场的不透明 id, 没有任何文本或标题
 */
internal object VoiceLive {

    private const val TAG = "VoiceLive"

    /** 四个状态, 与广播 / provider 暴露的那一套同一个拼写 */
    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val SPEAKING = "speaking"

    private val STATES = setOf(IDLE, LISTENING, THINKING, SPEAKING)

    /** 与施工单 §8.7 钉的是同一个文件 */
    private const val FILE_NAME = "voice-live.json"

    private val lock = Any()

    /** 最近一次真的写出去的状态 (进程级; 进程重启后第一次 [publish] 一定写) */
    private var lastState: String = ""

    @Volatile
    var state: String = IDLE
        private set

    @Volatile
    var session: String? = null
        private set

    @Volatile
    var at: Long = 0L
        private set

    fun file(context: Context): File = File(DshHost.settingsDirectory(context), FILE_NAME)

    /**
     * 报一次状态: **真的跳变才写**, 返回写没写
     *
     * [sessionOf] 是一个取值函数而不是值 —— 状态没变时它根本不会被调用, 于是"每一拍都去读
     * `voice/session.json`"这件事不存在; 而状态变了时它正好给出那一刻该记的会话
     */
    fun publish(context: Context, next: String, sessionOf: () -> String?): Boolean {
        val wanted = next.lowercase().takeIf { it in STATES } ?: IDLE
        synchronized(lock) {
            if (wanted == lastState) return false
            lastState = wanted
            state = wanted
            val id = sessionOf()?.trim()?.takeIf { it.isNotEmpty() }
            session = id
            at = System.currentTimeMillis()
            write(context, wanted, id, at)
            // 广播与文件同一条边沿: 只在跳变时发一次 (缺省关, 只发给设置页点名的那个包)
            VoiceExport.announce(context, wanted, id)
            return true
        }
    }

    /** 原子写 (先 `.tmp` 再 rename): 读的那一侧永远不会拿到半份 JSON */
    private fun write(context: Context, state: String, session: String?, at: Long) {
        val target = file(context)
        runCatching {
            val parent = target.parentFile
            parent?.mkdirs()
            val body = buildJsonObject {
                put("v", 1)
                put("state", state)
                // 没有会话就不写这个键 (契约里它可空; 空串反而要读的一侧去判)
                if (session != null) put("session", session)
                put("at", at)
            }.toString()
            val tmp = File(parent, "$FILE_NAME.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                // 有的文件系统上 rename 会失败 (目标存在 + 不同挂载点): 退回直接覆盖, 也是一份完整 JSON
                target.writeText(body)
                tmp.delete()
            }
        }.onFailure {
            Log.w(TAG, "could not write ${target.absolutePath}: ${it.message ?: it}")
        }
    }
}
