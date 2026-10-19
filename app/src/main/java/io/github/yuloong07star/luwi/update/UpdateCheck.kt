package io.github.yuloong07star.luwi.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「检测更新」: 把远处那一条 Release 读回来, 与手上这个版本比一比
 *
 * **只检测, 不代装** (第 4 条那条的边界, 见开发计划 2.4): 结果那一栏给出新版本号 / 体积 / 说明, 去
 * 下载交给系统浏览器 (`REQUEST_INSTALL_PACKAGES` 早就在清单里), **应用内静默更新不在这一版里**
 *
 * 判据那一半是纯的 ([ReleaseJson]), 这一份只做三件事: 取网络 / 记账 ( [phase] 那几个状态) / 把失败
 * 的原因写成人话 —— 与 [io.github.yuloong07star.luwi.wake.WakeWordDownload] 同一个分工
 */
internal object UpdateCheck {

    /** 现在这一趟走到哪儿了 */
    enum class Phase {
        /** 还没查过 */
        IDLE,

        /** 正在查 */
        CHECKING,

        /** 查到了, 手上这个就是最新的 */
        LATEST,

        /** 查到了, 而且有更新 */
        NEWER,

        /** 没查到 (网络不通 / 那个地址不是 Release): 原因在 [detail] 里 */
        FAILED,
    }

    var phase: Phase by mutableStateOf(Phase.IDLE)
        private set

    /** 一句话结果, 界面直接念它 */
    var detail: String by mutableStateOf("")
        private set

    /** 远端那一条 (查到了才有) */
    var release: ReleaseJson.Release? by mutableStateOf(null)
        private set

    /** 这一趟真的用上了哪一个地址 (失败了也要说, 免得人以为填的那个被忽略了) */
    var answeredBy: String by mutableStateOf("")
        private set

    /** 本机版本 (`versionName`), 由 [check] 顺手记下, 供界面显示 */
    var localVersion: String by mutableStateOf("")
        private set

    /**
     * 查一次, **同步阻塞**: 调用方放在 IO 上 (设置页那边是 `withContext(Dispatchers.IO)`)
     *
     * 试几个地址就取 [AboutSettings.attempts] 那几条 (缺省那条连着镜像), 第一个答上来的算数; 全都不
     * 行就把每一路上的原因都写进 [detail] —— "连不上"这三个字不解决任何问题, 得说清是哪一步
     */
    fun check(context: Context, source: String) {
        phase = Phase.CHECKING
        detail = "正在查 $source"
        release = null
        answeredBy = ""
        localVersion = localVersionOf(context)
        val attempts = if (source == AboutSettings.source(context)) {
            AboutSettings.attempts(context)
        } else {
            listOf(source)
        }
        val failures = mutableListOf<String>()
        for (url in attempts) {
            try {
                val parsed = ReleaseJson.parse(ReleaseFetch.raw(url))
                release = parsed
                answeredBy = url
                val newer = ReleaseJson.newer(parsed.version, localVersion)
                phase = if (newer) Phase.NEWER else Phase.LATEST
                detail = if (newer) {
                    "有更新: ${parsed.version} (手上是 $localVersion)"
                } else {
                    "已经是最新的 ($localVersion)"
                }
                return
            } catch (error: Throwable) {
                failures += "${ReleaseFetch.host(url)}: ${error.message ?: error}"
            }
        }
        phase = Phase.FAILED
        detail = failures.joinToString("; ")
    }

    /** 回到"还没查过": 关掉那个对话框时清掉, 免得下次打开还挂着上一次的结果 */
    fun reset() {
        phase = Phase.IDLE
        detail = ""
        release = null
        answeredBy = ""
    }

    /** 手上这个版本的 `versionName` (取不到就空串: 那是"看不出版本", 不是一条要报的错误) */
    fun localVersionOf(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 那个版本的 `versionCode` (关于那一栏要一起显示, 报 bug 时两头都对得上) */
    fun localCodeOf(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)
}
