package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.util.Log
import io.github.yuloong07star.luwi.tool.LwSystemCommand
import java.io.File

/**
 * "把另一半补上": companion 插件的那个伴侣 APK (协议第 8.1 节)
 *
 * 一份 `.lwp` 只说"绑哪个包、哪个 Service", 而工具 / 桌面组件 / 它自己的界面全在那个 APK 里。老
 * 做法是让装的人自己再去找那个包的链接, 于是"从链接装完 `.lwp`"与"插件能用"之间隔着一件不知道该
 * 做什么的事。清单里带了 `entry.apk` 之后 (协议第 4 节), 这一步由这里收口:
 *
 * 1. 那个包已经装着 → 什么都不做
 * 2. 清单里没有地址 (老包) → 什么都不做, 回到设置页那条手动路
 * 3. 有地址 → 下到 [CompanionApk.stagingDirectory] (特权那一侧读得到) → 对一次包名 → **静默装上**
 * 4. 静默那条走不通 (没有 root 也没有 Shizuku, 或者 `pm install` 非 0) → 有人能点的时候交给系统
 *    安装器, 没人能点的时候只把文件备好并说清
 *
 * 静默安装走的是特权那一侧 `installStaged` 那一条 (`pm install -r -t`, 由那一侧先把这份文件抄进
 * `/data/local/tmp` 再交出去)。**为什么不能直接把 `$DSH_HOME` 或应用目录里的路径交给 `pm`**:
 * `pm install <路径>` 不是自己读那个文件, 而是把路径交给 **system_server**, 而 SELinux 不许
 * system_server 读共享存储 (FUSE) 上的东西 —— 2026-10-11 在模拟器上实测撞的, 连 root 也一样。
 * 最后那一下"要不要装"仍然不是应用替谁答应的 —— 能静默装是因为这台设备的主人已经把自己的
 * root / Shizuku 交给了这个应用
 */
internal object PluginCompanion {

    private const val TAG = "LwPluginCompanion"

    /**
     * 补完之后是个什么状态
     *
     * @property text 接在"装上了 …"后面的一句人话; 空 = 没什么要说的 (伴侣本来就装着, 或者这份包
     *   没带地址)
     * @property handled 伴侣现在就位了 —— 装完可以直接往下走"启用"那一步
     * @property waitingForUser 交给了系统安装器, 等人在那一页点安装 (这时**不能**自动启用: 人还在
     *   安装器那一页, 什么时候回来不由我们定)
     */
    data class Outcome(
        val text: String = "",
        val handled: Boolean = false,
        val waitingForUser: Boolean = false,
    )

    /**
     * 把伴侣那一半补上
     *
     * @param allowSystemInstaller 静默那条走不通时允不允许把文件交给系统安装器。设置页那条路给
     *   true (有人在看着屏幕, 点一下就行), 桥那条路给 false (后台没有人点)
     */
    fun ensure(context: Context, packageName: String, apkUrl: String, allowSystemInstaller: Boolean): Outcome {
        if (packageName.isBlank()) return Outcome()
        val installed = CompanionApk.installed(context, packageName)
        if (installed != null) return Outcome("伴侣应用 $packageName $installed 已经在", handled = true)
        if (apkUrl.isBlank()) return Outcome()

        val file = try {
            CompanionApk.fetch(context, apkUrl)
        } catch (problem: Throwable) {
            return Outcome("伴侣 APK 没下下来: ${problem.message ?: problem.javaClass.simpleName}")
        }
        val mismatch = CompanionApk.mismatch(context, file, packageName)
        if (mismatch != null) {
            file.delete()
            return Outcome(mismatch)
        }

        val failure = silentInstall(file)
        if (failure == null) {
            file.delete()
            return Outcome("伴侣应用 $packageName 也装上了", handled = true)
        }
        Log.i(TAG, "静默安装没走通: $failure")
        if (!allowSystemInstaller) {
            return Outcome("伴侣 APK 已经备在 ${file.absolutePath}, 但没装成 ($failure)")
        }
        return Outcome(CompanionApk.install(context, file, packageName), waitingForUser = true)
    }

    /**
     * 走特权那一侧的 `installStaged` (它自己把这份抄进 `/data/local/tmp`, 再 `pm install -r -t`)
     *
     * 回 null = 装成了; 否则回的是一句"为什么没成", 由调用方决定要不要退回系统安装器
     */
    private fun silentInstall(file: File): String? {
        val result = try {
            LwSystemCommand.run(
                operation = "installStaged",
                arguments = listOf(file.absolutePath),
                timeoutMs = LwSystemCommand.INSTALL_TIMEOUT_MS,
            )
        } catch (problem: Throwable) {
            return "特权通道不可用 (${problem.message ?: problem.javaClass.simpleName})"
        }
        if (result.code == 0) return null
        val said = result.output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        return "pm install 回了 ${result.code}" + if (said.isBlank()) "" else ": $said"
    }
}
