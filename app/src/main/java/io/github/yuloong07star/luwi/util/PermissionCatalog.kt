package io.github.yuloong07star.luwi.util

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * 一条权限现在是什么状态
 *
 * 分成"能点名要"和"只能去系统页点"两类是必须的: 前者的答案会变 (用户可以随时拒绝), 所以每次
 * 都现读; 后者系统**不会**在运行时弹窗 (`WRITE_SETTINGS` 那种就是), 唯一的入口是一页系统设置,
 * 所以这里只回答"有没有", 至于怎么给, 交给 [Capability.settings]
 */
enum class Grant {
    /** 已经给了 */
    GRANTED,

    /** 还没给, 但可以在应用里点名要 */
    DENIED,

    /**
     * 清单里没有声明这一条
     *
     * 单独一档是为了别把"没声明"说成"没授权": 前者是构建的问题, 后者是用户的问题
     */
    MISSING,
}

/**
 * 一个能力要用的权限, 与它没拿到时该去哪儿拿
 *
 * @property name 能力的名字, 设置页与 `lw_permissions` 输出里看到的都是它
 * @property why 这个能力做什么用, 一句话
 * @property permissions 它要的权限清单; 特殊访问那几条放的是系统页的 action
 * @property special true 表示这几条只能去系统设置页点, 运行时要不来
 * @property settings 去系统设置页的入口, 只有 [special] 的能力才有
 * @property context 那几页系统设置是**哪个包**的 (设置那一侧只认包名, 从 [settings] 的 intent 里解析
 *   不出来); 回退到应用详情页时按它打开对应那一页, null 表示就是本应用自己
 * @property note 给人看的一句说明 (比如"声明了但这一版没有功能用它们")
 */
data class Capability(
    val name: String,
    val why: String,
    val permissions: List<String>,
    val special: Boolean = false,
    val settings: Intent? = null,
    val context: String? = null,
    val note: String? = null,
)

/**
 * 这一版声明了哪些权限, 以及每条权限服务哪个能力
 *
 * 一张表两个用处: 设置页的「权限」段照着它列, `lw_permissions` 也照着它报 —— 两处都从这一份
 * 出来, 就不会出现"设置页说已允许、工具那边却说不支持"这种事
 *
 * 声明本身是 `AndroidManifest.xml` 的事, 这里只管现在有没有。所以这里念到的名字一定能在那份
 * 清单里找到, 反过来不成立: 清单里还有一批是声明了但这一版没有功能用的, 见 [declaredOnly]
 */
object PermissionCatalog {

    /** 能运行时点名要的那些, 一条一个能力, 设置页逐条列 */
    val runtime: List<Capability> = listOf(
        Capability(
            name = "通知",
            why = "lw_notify 发通知, host 的常驻通知也靠它",
            permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
        ),
        Capability(
            name = "震动",
            why = "lw_vibrate 与通知自带的震动",
            permissions = listOf(Manifest.permission.VIBRATE),
        ),
        Capability(
            name = "相机",
            why = "lw_take_photo 让系统相机去拍一张。应用自己也得握着这一条: 声明了相机权限却没有它的" +
                "应用, 系统会直接拒掉它的拍照 intent, 与谁真的按快门无关",
            permissions = listOf(Manifest.permission.CAMERA),
        ),
        Capability(
            name = "麦克风",
            why = "录音与以后的语音输入",
            permissions = listOf(Manifest.permission.RECORD_AUDIO),
        ),
        Capability(
            name = "位置",
            why = "lw_location 读最后已知位置与一次主动定位",
            permissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        ),
        Capability(
            name = "蓝牙",
            why = "读已连接的蓝牙设备, 以及以后的传输",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                listOf(Manifest.permission.BLUETOOTH)
            },
        ),
        Capability(
            name = "媒体读取",
            why = "从公共目录里挑文件分享出去",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                )
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        ),
        Capability(
            name = "身体传感器与活动",
            why = "读计步一类的传感器",
            permissions = listOf(
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.BODY_SENSORS,
            ),
        ),
        Capability(
            name = "日历",
            why = "lw_calendar 读日程 / 建日程 / 改日程 / 查空闲 (2.5.0 批次 7)",
            permissions = listOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            ),
        ),
        Capability(
            name = "通讯录 / 短信 / 通话记录",
            why = "这一版没有功能用它们",
            permissions = emptyList(),
            note = "清单里已经声明 (要的就是都加上), 但没有任何工具读它们; 现在授权只是把授权位占住",
        ),
    )

    /** 只能去系统设置页点的那些, 一条一个系统页 */
    val special: List<Capability> = listOf(
        Capability(
            name = "修改系统设置",
            why = "lw_system 改亮度 / 屏幕超时 / 旋转锁定 / 字体缩放",
            permissions = listOf(Settings.ACTION_MANAGE_WRITE_SETTINGS),
            special = true,
            settings = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData("package:$APP_PACKAGE".toUri()),
        ),
        Capability(
            name = "悬浮窗",
            why = "以后要在别的应用上面画东西时的入口",
            permissions = listOf(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            special = true,
            settings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData("package:$APP_PACKAGE".toUri()),
        ),
        Capability(
            name = "使用情况访问",
            why = "读最近用过哪些应用那类查询",
            permissions = listOf(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            special = true,
            settings = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            context = SETTINGS_PACKAGE,
        ),
        Capability(
            name = "忽略电池优化",
            why = "host 要一直活着, 不然系统会在后台把它冻住",
            permissions = listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            special = true,
            settings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            context = SETTINGS_PACKAGE,
        ),
        Capability(
            name = "精确闹钟",
            why = "定时任务要准点醒",
            permissions = listOf(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
            special = true,
            settings = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:$APP_PACKAGE".toUri()),
        ),
    )

    /** 清单里声明了、这一版却没有对应功能的那几条: 列出来是为了别让人以为它们已经在用 */
    val declaredOnly: List<String> = listOf(
        "READ_CONTACTS", "WRITE_CONTACTS", "READ_SMS", "SEND_SMS", "RECEIVE_SMS",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
        "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
    )

    /** 本应用自己的包名, 几条系统页要按包名打开 */
    private const val APP_PACKAGE = "io.github.yuloong07star.luwi"

    /** 系统设置自己的包名: "使用情况访问"与"忽略电池优化"那两页是它的, 不属于任何应用 */
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /** 一条权限现在的状态 */
    fun state(context: Context, permission: String): Grant = try {
        when (ContextCompat.checkSelfPermission(context, permission)) {
            PackageManager.PERMISSION_GRANTED -> Grant.GRANTED
            else -> Grant.DENIED
        }
    } catch (error: Throwable) {
        // 清单里没有声明这条权限时 checkSelfPermission 会抛, 那是一种状态而不是一个错误
        Grant.MISSING
    }

    /** 一个能力现在的状态: 它要的每一条都给到了才算给到 */
    fun state(context: Context, capability: Capability): Grant {
        if (capability.special) return specialState(context, capability)
        val states = capability.permissions.map { state(context, it) }
        return when {
            states.all { it == Grant.GRANTED } -> Grant.GRANTED
            states.any { it == Grant.MISSING } -> Grant.MISSING
            else -> Grant.DENIED
        }
    }

    /** 特殊访问只能按系统页的答案判: 那几档没有运行时接口 */
    private fun specialState(context: Context, capability: Capability): Grant {
        val setting = capability.permissions.firstOrNull() ?: return Grant.MISSING
        return try {
            val granted = when (setting) {
                Settings.ACTION_MANAGE_WRITE_SETTINGS -> Settings.System.canWrite(context)
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION -> Settings.canDrawOverlays(context)
                Settings.ACTION_USAGE_ACCESS_SETTINGS -> usageAccess(context)
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS ->
                    batteryOptimizationIgnored(context)

                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> exactAlarms(context)
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES -> unknownSources(context)
                else -> false
            }
            if (granted) Grant.GRANTED else Grant.DENIED
        } catch (error: Throwable) {
            Grant.MISSING
        }
    }

    /** 使用情况访问没有直接接口, 只能问那条 app op */
    private fun usageAccess(context: Context): Boolean {
        val manager = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = manager.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 电池优化名单: 在名单里 = 没有被优化 = 已允许 */
    private fun batteryOptimizationIgnored(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 精确闹钟 (Android 12 起可以撤回) */
    private fun exactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** 安装未知应用 (Android 8 起) */
    private fun unknownSources(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(PackageManager::class.java) ?: return false
        return manager.canRequestPackageInstalls()
    }

    /** 一条权限给人看的名字: 系统给的是 `android.permission.CAMERA` 这种, 页面上太长 */
    fun shortName(permission: String): String = permission.substringAfterLast('.')

    /**
     * 一屏说清所有权限现在什么样, 给 `lw_permissions` 用
     *
     * 模型问"某个能力能不能用"时这一条就够: 它比一个个去试要快, 也不会把"没权限"当成
     * "设备不支持"
     */
    fun report(context: Context): String = buildString {
        append("permissions on ${Build.MANUFACTURER} ${Build.MODEL}")
        append(" (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})\n")
        append("\n每个能力要什么, 现在什么样:\n")
        (runtime + special).forEach { capability ->
            val state = state(context, capability)
            val how = when {
                state == Grant.GRANTED -> "已允许"
                capability.special -> "未允许, 只能去系统设置页点"
                else -> "未允许, 应用内可以点名要"
            }
            append("  ${capability.name}: $how\n")
            append("    用途: ${capability.why}\n")
            append("    权限: " + capability.permissions.joinToString(", ") { shortName(it) } + "\n")
            capability.note?.let { append("    说明: $it\n") }
        }
        append("\n只有声明、这一版没有功能用的:\n")
        append("  " + declaredOnly.joinToString(", ") + "\n")
        append("\n缺权限时工具会直接说缺哪一条, 不会假装成功\n")
    }
}
