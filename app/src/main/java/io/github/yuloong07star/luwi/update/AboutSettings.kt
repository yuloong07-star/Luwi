package io.github.yuloong07star.luwi.update

import android.content.Context
import android.content.SharedPreferences

/**
 * 「关于」那一摊要记住的事: **从哪儿查新版本**
 *
 * **更新源留成可改的**: 缺省是我们自己那个 Release 的 API (`api.github.com/.../releases/latest`)。这台
 * 开发机上 `github.com:443` 不通而 `api.github.com` 通, 但**别人手里的网络不一定** —— 所以除了缺省值,
 * 再加一条镜像 (与 [io.github.yuloong07star.luwi.wake.WakeWordDownload] 同一个套路: `ghfast.top`
 * 那一条只是把原地址当路径接在后面), 以及一个自己填的口子 (自建 / 别的镜像)
 *
 * **sync 签到不在这里**: 那一页 (2.7.9 第 4 条) 这一版只做壳, 数据面在 `sync/SyncAccount.kt` —— 这一份
 * 偏好里因此只有更新源一件事
 */
internal object AboutSettings {

    /** 与设置页那些偏好同一个文件 */
    private const val STORE = "luwi"
    private const val KEY_SOURCE = "update-source"

    /**
     * 缺省更新源: Luwi 自己那个仓库的"最新一条 Release"
     *
     * **2026-10-10 那天一度改成过 `luwi-plugins`**, 那是我读错了主人那一句 (他说的"检测来源"指的是
     * **技能**从哪儿检测, 不是应用更新) —— 而且那个仓库没有 Release 也没有 Tag
     * (`releases/latest` 直接 404), 改过去只会查到"查不到发布"。已撤回: 应用自己的更新仍然认这一条
     */
    const val DEFAULT_SOURCE = "https://api.github.com/repos/yuloong07-star/Luwi/releases/latest"

    /** 缺省那一条的镜像前缀: 拿不到直连时按同一个路径再试一次 */
    const val MIRROR_PREFIX = "https://ghfast.top/"

    /**
     * 现在该查哪个地址
     *
     * 存过就用存的那一条, 没存过 (或者存了个空的) 就是 [DEFAULT_SOURCE] —— 于是"清空文本框"这件事
     * 的净效果是"回到缺省", 不需要第二个"恢复缺省"的按钮
     */
    fun source(context: Context): String =
        prefs(context).getString(KEY_SOURCE, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SOURCE

    /** 存下这一条更新源 (存的就是原样那一段文字, 校验留给真正去取的那一步) */
    fun setSource(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SOURCE, url.trim()).apply()
    }

    /**
     * 按缺省与镜像凑出这一趟要试的几个地址: 存的就是缺省那一条时多试一次镜像, 否则只试存的那一条
     *
     * **顺序是"先直连后镜像"**: 镜像是别人的服务, 能直连就不该把查询内容经它过一遍
     */
    fun attempts(context: Context): List<String> {
        val chosen = source(context)
        if (chosen != DEFAULT_SOURCE) return listOf(chosen)
        return listOf(chosen, MIRROR_PREFIX + chosen)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
