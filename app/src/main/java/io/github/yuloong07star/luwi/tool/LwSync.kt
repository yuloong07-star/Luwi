package io.github.yuloong07star.luwi.tool

import android.content.Context
import io.github.yuloong07star.luwi.sync.SyncSetting
import kotlinx.serialization.json.JsonObject

/**
 * `lw_sync`: 让 agent 自己把会话同步配好 (主人 2026-10-14: "让 agent 帮你配置")
 *
 * **为什么非有这条工具不可**: 同步那份配置 (`$DSH_HOME/lw/session-sync.json`) 住在应用的文件目录
 * 里, 而模型手里的 `read` / `write` / `bash` 被 dsh 的 fs 沙箱关在工作区, `lw_linux` 的 proot 也只
 * `-b <work>:/work` 绑了工作区 —— 少了这一条, 设置页第一排那句"让 agent 帮你配置"就是一句做不到的
 * 承诺
 *
 * **语义只有一份实现**: 读写全走 [SyncSetting] 的纯文件那一半 (保留未知键 / 越界在手机上就拒 /
 * 「关」= 地址挪进 lw 段 + 三个自动值一起归零), 这里只把 op 翻成那几个函数, 再写回一句人话
 *
 * **生效方式照实说**: 插件是**挂载时**读配置的, 所以写完必须重启 host。设置页与顶栏菜单那两处开关
 * 都替主人把这件事做掉了 (菜单那一行顺手重启), 而模型这一条没有重启的口子 —— 答案里那句话必须带上,
 * 否则主人会以为"改了没用"
 */
internal object LwSync {

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> text(statusText(context))
        "set" -> set(context, request)
        else -> throw IllegalArgumentException("op has to be status or set, not \"$op\"")
    }

    /** 那份配置在哪儿 (与应用那侧、与插件那一侧同一个约定: `$DSH_HOME/lw/session-sync.json`) */
    private fun file(context: Context) = SyncSetting.file(context)

    /**
     * 现在的取值, 一行一项 —— `op=status` 与 `op=set` 的尾巴读的是**同一份** (两处各写一遍就会出现
     * "写完了却看不清写成了什么")
     */
    private fun statusText(context: Context): String {
        val file = file(context)
        val snapshot = SyncSetting.snapshotOf(file)
        return buildList {
            add("sync: ${if (snapshot.syncOn) "on" else "off"}")
            add("  remote: ${snapshot.sessionRemote.ifBlank { "(unset)" }}")
            add("  branch: ${snapshot.branch.ifBlank { "main" }}")
            add("  autoPullOnStart: ${snapshot.autoPull}")
            add("  autoPushOnTurnEnd: ${snapshot.autoPush}")
            add("  pullIntervalMinutes: ${snapshot.pullInterval}")
            add("  workspace: ${if (snapshot.workspaceOn) "on ${snapshot.workspaceRemote}" else "off"}")
            add(
                "  peer: ${if (snapshot.peerOn) "on" else "off"} (token ${SyncSetting.maskedToken(snapshot.peerToken)}," +
                    " url ${snapshot.peerUrl.ifBlank { "from the peer card" }})",
            )
            add("  file: ${file.absolutePath}${if (snapshot.fileExists) "" else " (not written yet)"}")
            if (!snapshot.syncOn) {
                val resumable = snapshot.resumableRemote
                add(
                    if (resumable.isBlank()) {
                        "  note: sync is off. Set the git remote first: op=set remote=<url>"
                    } else {
                        "  note: sync is off; the address filled in before is kept ($resumable)" +
                            " - op=set on=true brings it back"
                    },
                )
            }
        }.joinToString("\n")
    }

    /**
     * 写配置。**只动传进来的那几项**, 别的键一个字节都不碰 (底下那层就带这条纪律)
     *
     * 认识这六个: `remote` (空串 = 清掉 = 关同步) / `branch` / `interval` / `autoPull` / `autoPush` /
     * `on` (`true` 把「同步」总开关打开 —— 用现在配的地址, 没有就用上次存下的; `false` 关掉它,
     * 地址挪进 lw 段存着, 三个自动值一起归零)
     *
     * **一处被拒就停在那一条上并如实说**: 校验在 [SyncSetting] 那一层 (地址不像地址 / 分支名有空格 /
     * 间隔越界), 它回一句原因 —— 把原因吞掉、只报"写了 N 项"是最坏的一种诚实
     */
    private fun set(context: Context, request: JsonObject): JsonObject {
        val file = file(context)
        val done = mutableListOf<String>()
        var problem: String? = null

        fun run(label: String, change: () -> String?) {
            if (problem != null) return
            val said = change()
            if (said != null) problem = said else done += label
        }

        request.stringOrNull("remote")?.let { raw ->
            run(if (raw.isBlank()) "remote cleared (sync off)" else "remote = $raw") {
                SyncSetting.setTextIn(file, SyncSetting.FIELD_SESSION_REMOTE, raw)
            }
        }
        request.stringOrNull("branch")?.let { raw ->
            run("branch = ${raw.ifBlank { "main" }}") {
                SyncSetting.setTextIn(file, SyncSetting.FIELD_BRANCH, raw)
            }
        }
        if (request.containsKey("interval")) {
            val next = request.number("interval", 0.0).toInt()
            run("pullIntervalMinutes = $next") { SyncSetting.setPullIntervalIn(file, next) }
        }
        if (request.containsKey("autoPull")) {
            val next = request.bool("autoPull", false)
            run("autoPullOnStart = $next") { SyncSetting.setFlagIn(file, SyncSetting.FLAG_AUTO_PULL, next) }
        }
        if (request.containsKey("autoPush")) {
            val next = request.bool("autoPush", false)
            run("autoPushOnTurnEnd = $next") { SyncSetting.setFlagIn(file, SyncSetting.FLAG_AUTO_PUSH, next) }
        }
        if (request.containsKey("on")) {
            val next = request.bool("on", false)
            run(if (next) "sync = on (the address is back in place)" else "sync = off (address parked, autos zeroed)") {
                SyncSetting.setSyncOnIn(file, next)
            }
        }

        val head = when {
            problem != null -> "nothing was written past the point of failure: $problem"
            done.isEmpty() -> "nothing to change: pass remote=, branch=, interval=, autoPull=, autoPush= or on="
            else -> "wrote ${done.size} setting(s): ${done.joinToString(", ")}"
        }
        val tail = "\n**Restart the DSH host for this to take effect** (the plugin reads this file when it " +
            "mounts). Whoever can tap: the top-bar menu -> 同步, or the app's Settings -> 同步, both " +
            "restart it for them.\n" + statusText(context)
        return text(head + tail)
    }
}
