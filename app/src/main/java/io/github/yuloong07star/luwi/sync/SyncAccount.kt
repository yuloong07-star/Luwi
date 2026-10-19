package io.github.yuloong07star.luwi.sync

import android.content.Context
import io.github.yuloong07star.luwi.host.DshHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 「sync 签到」那一页的数据面 (2.7.9 第 4 条) —— **这一版只有壳**
 *
 * 真接口 (签到送一天 / 1 元 7 天 / 3 元 30 天 / 30 元 365 天) 要等同步那条的后端契约
 * (施工单 §2.2 的「依赖」那一段); 在那之前这一份只做一件事: **读本地那一份账号状态**, 读不到就
 * 如实说"未连接账号" —— 不假装有额度, 也不在界面里写任何假数字
 *
 * 文件是 `$DSH_HOME/lw/sync-account.json`: 与别的那几份同一个目录, 后端接上之后由它写
 * (`account` 是打码后的账号, `expiresAt` 是 epoch 毫秒)
 */
internal object SyncAccount {

    const val FILE = "sync-account.json"

    /** 账号状态: 打码账号 + 到期时刻 (0 = 还没拿到) */
    data class State(val account: String, val expiresAt: Long)

    private val json = Json { ignoreUnknownKeys = true }

    fun file(context: Context): File =
        File(File(context.filesDir, DshHost.HOME_DIR), "lw/$FILE")

    /** 现在有没有登录着的账号; 文件不在 / 读坏了 / 账号是空的都算"未连接" */
    fun read(context: Context): State? {
        val raw = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return stateOf(root)
    }

    /** 一份 `sync-account.json` -> 状态 (纯函数: 空账号算未连接, 给单测钉) */
    fun stateOf(root: JsonObject): State? {
        val account = root["account"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (account.isEmpty()) return null
        return State(account, root["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L)
    }

    /**
     * 解绑本机: **只删本地这一份**, 不动账号本身
     *
     * 返回"真的删掉了没有" —— 没有绑定过时是 false, 界面据此说"本机没有绑定的账号"
     */
    fun unbind(context: Context): Boolean = runCatching {
        val target = file(context)
        target.isFile && target.delete()
    }.getOrDefault(false)
}
