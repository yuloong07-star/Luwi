package io.github.yuloong07star.luwi.update

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 自动查更新那只对话框的那一份状态 (2.7.9 第 3 条)
 *
 * **必须是单独一份**: [UpdateCheck] 那三个字段是设置页手动「检测更新」的账, 自动这一趟若写在同一个
 * 地方, 人再去点手动那一条就会看到自动查的旧结果 (施工单 F2)。这里只留"要弹的那一条"与"手上是哪个
 * 版本", 弹不弹的判断在 [AutoUpdate.shouldCheck], 写盘在 [AutoUpdate.once] —— 这一份纯给界面看
 *
 * 写它的那一方是 IO 线程 ([AutoUpdate.run]), 读它的是组合 (AppNav) —— 快照状态本身是线程安全的,
 * 所以这里不加锁也不 post 回主线程
 */
internal object AutoUpdatePrompt {

    /** 要弹的那一条; null = 不弹 */
    var release: ReleaseJson.Release? by mutableStateOf(null)
        private set

    /** 手上这个版本 (`versionName`), 对话框正文里要一起念 */
    var localVersion: String by mutableStateOf("")
        private set

    fun show(release: ReleaseJson.Release, localVersion: String) {
        Log.i("LwAutoUpdate", "prompt: ${release.version} (local $localVersion)")
        this.release = release
        this.localVersion = localVersion
    }

    fun dismiss() {
        release = null
    }
}
