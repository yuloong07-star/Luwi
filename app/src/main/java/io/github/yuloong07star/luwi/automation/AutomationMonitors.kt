package io.github.yuloong07star.luwi.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import io.github.yuloong07star.luwi.channel.LwAccessibility
import io.github.yuloong07star.luwi.channel.LwNotificationListener
import io.github.yuloong07star.luwi.util.PermissionCatalog
import io.github.yuloong07star.luwi.util.PermissionGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 九个监测器: 该起来的起来, 不该起来的**一个都不留**
 *
 * 低功耗那句口径落在这一层, 形状是三条:
 *
 * 1. **按需注册**: [AutomationEngine] 每拍算出"此刻有哪几类启用的规则", 只有那几类被挂上; 规则删掉
 *    或关掉, 下一拍就摘掉 —— 一条规则都没有时, 进程里没有 sensor / location / alarm / 接收器注册
 * 2. **事件优先**: 通知与前台应用是系统推过来的 (一个钩子, 零轮询); 光感是一条 `SENSOR_DELAY_NORMAL`
 *    的监听 (最省的那一档); 解锁与"动过"各一条: 前者是系统广播, 后者挂在既有窗口事件链上; 时间与
 *    一次性**共用一个**闹钟 —— 所有 `time` / `once` 规则里最近的那一个
 * 3. **轮询只剩两条**: 天气按设置页那个分钟档问一次 Open-Meteo, 位置用 `NETWORK_PROVIDER` (balanced:
 *    极少 GPS) 加"至少隔多久 / 至少移动多远"两个数; 省电生效时这两条整个不注册
 *
 * 各条各自的可用性 (权限 / 无障碍 / 有没有光感) 都在 [describe] 里说, 设置页与 `status` 读的是同一份
 */
internal object AutomationMonitors {

    private const val TAG = "LwAutomation"

    /** 位置那条要的那条能力: 与 `PermissionCatalog` 里那一条是同一份 (`LwSystem.location` 用的也是它) */
    private val locationCapability
        get() = PermissionCatalog.runtime.firstOrNull { it.name == "位置" }

    private var notice: AutoCloseable? = null
    private var foreground: AutoCloseable? = null
    private var light: SensorEventListener? = null
    private var location: LocationListener? = null

    /** 解锁那条: 一条动态注册的接收器 (`ACTION_USER_PRESENT` 不需要任何权限) */
    private var unlock: BroadcastReceiver? = null

    /** "动过"那条: 既有的窗口事件链 (无障碍), 与 [foreground] 同一条来源, 各挂各的回调 */
    private var activity: AutoCloseable? = null

    /** 停的时候要注销那条接收器, 而 [stopAll] 从来只收不到 Context, 所以起来时记一份 */
    private var host: Context? = null

    private var alarmAt = 0L
    private var alarmExact = true
    private var wanted: Set<String> = emptySet()

    /** 位置那条挂的是哪个 provider (网络定位关着时会退到 GPS, 这一行给 [describe] 说人话) */
    private var locationProvider: String? = null

    /** 上一次因为缺权限没挂上位置那条的原因 (给 [describe] 用) */
    private var locationRefusal: String? = null

    /**
     * 把六个监测器拨到 [wanted] 说的样子

     * @param paused 省电是不是正生效 (轮询那两条整个不挂)
     */
    fun apply(context: Context, wanted: Set<String>, paused: Boolean) {
        this.wanted = wanted
        host = context.applicationContext
        // 通知: 一条钩子, 零成本; 没有这一类规则时摘掉, 免得白白收一整天
        if ("notice" in wanted) {
            if (notice == null) {
                notice = LwNotificationListener.watch { posted -> AutomationEngine.onNotification(context, posted) }
            }
        } else {
            notice?.close()
            notice = null
        }
        // 前台应用: 挂着无障碍那条队列的进程内回调 (事件到了才叫一声)
        if ("foreground" in wanted && LwAccessibility.running) {
            if (foreground == null) {
                foreground = LwAccessibility.watchWindows { packageName, _ ->
                    AutomationEngine.onForeground(context, packageName)
                }
            }
        } else {
            foreground?.close()
            foreground = null
        }
        // 解锁: 系统广播, 动态注册即可 (清单里声明的那种要 export, 这里反而更省)
        if ("unlock" in wanted) {
            if (unlock == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(from: Context, intent: Intent?) {
                        if (intent?.action == Intent.ACTION_USER_PRESENT) {
                            AutomationEngine.onUnlock(context)
                        }
                    }
                }
                val registered = runCatching {
                    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
                }.getOrNull()
                if (registered != null) unlock = receiver
            }
        } else {
            unlock?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
            unlock = null
        }
        // "动过": 窗口事件那条链 (无障碍开着时才有事件), 解锁那一路在引擎里另外记一笔
        if ("activity" in wanted && LwAccessibility.running) {
            if (activity == null) {
                activity = LwAccessibility.watchWindows { _, _ -> AutomationEngine.onInteraction(context) }
            }
        } else {
            activity?.close()
            activity = null
        }
        // 光感: 最省的那一档
        val sensors = context.getSystemService(SensorManager::class.java)
        if ("light" in wanted) {
            if (light == null && sensors != null) {
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
                if (sensor != null) {
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            val value = event.values.firstOrNull()?.toDouble() ?: return
                            AutomationEngine.onLight(context, value)
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    if (sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                        light = listener
                    }
                }
            }
        } else {
            light?.let { listener -> sensors?.unregisterListener(listener) }
            light = null
        }
        // 位置: 只在有启用的 place 规则时注册; 省电时 wanted 里本来就没有它
        val manager = context.getSystemService(LocationManager::class.java)
        if ("place" in wanted) {
            locationRefusal = locationCapability?.let { PermissionGate.refusal(context, it) }
            if (location == null && locationRefusal == null && manager != null) {
                // 主人定的那一档是"平衡": 优先网络定位 (WiFi 与基站), 极少 GPS。网络定位关着的设备
                // (模拟器上默认就是这样) 退到 GPS —— **节拍一样慢**, 所以它仍然是那一档的脾气, 只是
                // 没有便宜的来源可用; 退回这件事要在读数里说出来
                val provider = when {
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                        LocationManager.NETWORK_PROVIDER

                    manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                        LocationManager.GPS_PROVIDER

                    else -> null
                }
                if (provider == null) {
                    locationRefusal = "这台设备的定位整个关着: 到某地那一条要它"
                } else {
                    val settings = AutomationStore.settings(context)
                    val listener = object : LocationListener {
                        override fun onLocationChanged(fix: Location) {
                            AutomationEngine.onLocation(context, fix.latitude, fix.longitude)
                        }

                        @Deprecated("the platform's own signature")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    try {
                        manager.requestLocationUpdates(
                            provider,
                            settings.placeMinutes * 60_000L,
                            settings.placeMeters.toFloat(),
                            listener,
                            Looper.getMainLooper(),
                        )
                        location = listener
                        locationProvider = provider
                    } catch (problem: SecurityException) {
                        locationRefusal = "位置权限被系统拒了: ${problem.message}"
                    } catch (problem: Throwable) {
                        Log.w(TAG, "could not register the location listener", problem)
                        locationRefusal = "位置那条监听挂不上: ${problem.message}"
                    }
                }
            }
        } else {
            location?.let { listener -> manager?.removeUpdates(listener) }
            location = null
            locationProvider = null
            locationRefusal = null
        }
        // 省电时轮询那两条已经不在 [wanted] 里了 (引擎算的时候就摘掉了), 事件类照常 —— 它们在
        // 引擎眼里几乎不花钱, 而"到点提醒"这种事在省电时段里也还该响
    }

    /**
     * 排下一次闹钟: **`time` 与 `once` 共用, 只排最近的那一个**
     *
     * 精确优先 (`setExactAndAllowWhileIdle`); 主人没给"闹钟和提醒"那条特殊访问时退
     * `setAndAllowWhileIdle` —— 它也会响, 只是可能晚几分钟, 而这件事要说得出来 ([describe])
     */
    fun armTimeAlarm(context: Context, at: Long?) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        if (at == null) {
            if (alarmAt != 0L) manager.cancel(pending)
            alarmAt = 0L
            return
        }
        if (at == alarmAt) return
        val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { Log.w(TAG, "could not arm the alarm", it) }
        alarmAt = at
        alarmExact = exact
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST,
        Intent(context, AutomationAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val ALARM_REQUEST = 41

    /** 全都摘掉 (服务停掉时) */
    fun stopAll() {
        notice?.close()
        notice = null
        foreground?.close()
        foreground = null
        unlock?.let { receiver -> host?.let { runCatching { it.unregisterReceiver(receiver) } } }
        unlock = null
        activity?.close()
        activity = null
        light = null
        location = null
        alarmAt = 0L
        wanted = emptySet()
    }

    /** 此刻真的在跑的几类 (引擎算状态时读它) */
    fun running(): Set<String> = buildSet {
        if (notice != null) add("notice")
        if (foreground != null) add("foreground")
        if (light != null) add("light")
        if (location != null) add("place")
        if (unlock != null) add("unlock")
        if (activity != null) add("activity")
        // 时间与一次性共用一个闹钟: 谁在这一刻的 wanted 里就报谁
        if (alarmAt != 0L) {
            if ("time" in wanted) add("time")
            if ("once" in wanted) add("once")
        }
        // 天气没有"注册"这回事: 它由引擎的心跳按时问一次, 所以只要它还想要就算在跑
        if ("weather" in wanted) add("weather")
    }

    /** 一条监测器现在的样子, 给设置页与 `status` 用 */
    fun describe(context: Context, kind: String): String = when (kind) {
        "notice" -> if (LwNotificationListener.running) {
            "通知使用权开着, 来一条就判一次"
        } else {
            "缺通知使用权: 设置页「通知」那一段拨一下, 或 tools/lw-install.ps1 -Perms"
        }

        "foreground" -> if (LwAccessibility.running) {
            "无障碍开着, 窗口一变就知道 (不着 UsageStats)"
        } else {
            "不可用: 无障碍关着 (这一条只用无障碍事件, 不退回轮询)"
        }

        "unlock" -> if (unlock != null) {
            "解锁广播在听 (今天第一次解锁那一刻记着)"
        } else {
            "不可用: 解锁广播没挂上"
        }

        "activity" -> when {
            activity != null -> "窗口事件链在听 (解锁也记一笔)"
            !LwAccessibility.running -> "不可用: 无障碍关着 (解锁那一路仍算)"
            else -> "窗口事件链没挂上 (解锁那一路仍算)"
        }

        "light" -> {
            val sensors = context.getSystemService(SensorManager::class.java)
            if (sensors?.getDefaultSensor(Sensor.TYPE_LIGHT) == null) {
                "这台设备没有光感"
            } else {
                "光感在听 (SENSOR_DELAY_NORMAL, 阈值有 10% 迟滞)"
            }
        }

        "time" -> when {
            alarmAt == 0L -> "这一刻没有排着的闹钟"
            else -> {
                val when_ = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(alarmAt))
                if (alarmExact) "下一个闹钟 $when_ (准点)" else "下一个闹钟 $when_ (不精确, 可能晚几分钟)"
            }
        }

        "once" -> when {
            alarmAt == 0L -> "这一刻没有排着的一次性规则"
            else -> {
                val when_ = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(alarmAt))
                if (alarmExact) "下一个一次性时刻 $when_ (准点)" else "下一个一次性时刻 $when_ (不精确, 可能晚几分钟)"
            }
        }

        "place" -> locationRefusal
            ?: (if (location != null) {
                val settings = AutomationStore.settings(context)
                val source = if (locationProvider == LocationManager.NETWORK_PROVIDER) {
                    "网络定位, 极少 GPS"
                } else {
                    "网络定位关着, 退到 GPS"
                }
                "每 ${settings.placeMinutes} 分钟 · 移动 ${settings.placeMeters} 米以上问一次 ($source)"
            } else {
                "现在没有这一类启用的规则"
            })

        "weather" -> if ("weather" in wanted) {
            "每 ${AutomationStore.settings(context).weatherMinutes} 分钟问一次 Open-Meteo"
        } else {
            "现在没有这一类启用的规则"
        }

        else -> kind
    }
}

/**
 * `time` / `once` 条件那个闹钟收到的广播
 *
 * 清单里声明、`exported=false`: 它只可能被本应用自己的 `PendingIntent` 叫到, 而系统在应用进程不在
 * 的时候也会把进程拉起来送这一条 (与"动态注册的接收器只在进程活着时收得到"相比, 这一条不会丢)
 *
 * 一次性那条与时间那条共用这个闹钟, 所以两边各判各的 (按 kind 过滤)
 */
class AutomationAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutomationEngine.ensure(context)
        AutomationEngine.onTime(context)
        AutomationEngine.onOnce(context)
        // 响过之后要重排下一次: 这一次的账已经记完, 规则没变也要把闹钟往前挪
        AutomationEngine.invalidate()
    }
}
