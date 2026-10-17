package io.github.yuloong07star.luwi.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.yuloong07star.luwi.MainActivity
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.automation.AutomationEngine
import io.github.yuloong07star.luwi.channel.LwModes
import io.github.yuloong07star.luwi.channel.PrivilegedBridge
import io.github.yuloong07star.luwi.channel.PrivilegedChannel
import io.github.yuloong07star.luwi.channel.VirtualScreen
import io.github.yuloong07star.luwi.plugin.PluginManager
import io.github.yuloong07star.luwi.tool.ExploreSchedule
import io.github.yuloong07star.luwi.tool.ExploreTask
import io.github.yuloong07star.luwi.tool.LwWakeWord

/**
 * Keeps the host process alive while the app is not in front
 *
 * Android reclaims a backgrounded app process, and the host is meant to keep answering the
 * browser GUI (and later the model's tools) whether or not the activity is visible, so the
 * process rides a special-use foreground service
 */
class DshHostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // The privileged channel belongs to the app process, which outlives any activity, and the
        // socket the host's tools call it through is opened here so that whoever starts the host
        // finds it already there
        PrivilegedChannel.initialize(this)
        warmUpChannel()
        // 模式: 首启把默认那份 (手机模式) 落到那个助手的提示词上, 也让主人有文件可改
        LwModes.ensureDefault(this)
        // 随包那六项 (四份技能 + 两条样例快捷指令): 技能缺什么补什么, 样例只发一次
        LwSeed.ensure(this)
        // 探索那张任务卡 (2.7.8 批次 8): 首启/升级后第一次把初始卡抄进来 (已经有就一个字节不动);
        // 排期按主人设的那个时刻排上 (关着就撤) —— 它与宿主的存活无关, 但到点那一下要宿主在跑
        ExploreTask.ensure(this)
        ExploreSchedule.ensure(this)
        // **唤醒词自愈** (2026-10-10 主人: "让它自愈"): 那个开关开着、又不在省电模式, 就把监听拉起来
        //
        // 原来只有设置页那几处会 `LwWakeWord.listen` —— 于是**重装 APK / 进程被杀之后没人再拉它**,
        // 而视频模式那个记号还在, 球上就写着「正在听」而麦克风一动不动 (2026-10-10 当场量到的那次:
        // `listening: false` / `hits: 0` / `segments: 0`)。宿主起来这一下是"应用又活了"最早的钩子
        if (runCatching { LwWakeWord.allow(this) && !LwWakeWord.powerSave(this) }.getOrDefault(false)) {
            runCatching { LwWakeWord.listen(this) }
                .onFailure { Log.w(TAG, "the wake word listener did not come back: ${it.message}") }
        }
        // 内置的 dsh-custom-mode 与它那个助手的五个文件: 新机开箱就有那个 `custom` 预设, 语音才能
        // 开新会话 (主人 2026-10-09); profile 那一半在 host spawn 之前做 (见 CustomPresets)
        CustomPresets.ensure(this)
        // 自动指令: 六个监测器按"有没有启用的规则"起落, 而这一句只是把引擎挂上 (一条规则都没有时
        // 它什么都不注册, 只留一拍 60 秒的心跳)
        AutomationEngine.ensure(this)
        // LW 插件: 启用着的伴侣重新接上 (协议第 7 节的"状态恢复")。绑 Service 是异步的, 所以它
        // 在自己的线程上走, 与特权通道那一句同一个理由 —— 谁在等它, 谁就要多等几秒
        restorePlugins()
        VirtualScreen.initialize(this)
        PrivilegedBridge.start()
        DshHost.start(this)
    }

    /**
     * Connect the privileged channel now rather than when something first needs it
     *
     * Connecting means starting an `app_process` and waiting for its binder, which is seconds - and
     * without this the bill would land on whichever call comes first, which is the model's first
     * screenshot. Doing it in the background at startup moves that cost to where nobody is waiting,
     * and it is also what lets the settings page show a real state the moment it is opened
     *
     * What it may ask for: `su` (a root manager decides whether that is a prompt or nothing at all)
     * and Shizuku's own permission dialog when Shizuku is running but this app is not allowed yet.
     * Both appear while the user is opening the app, which is the moment they can answer
     */
    private fun warmUpChannel() {
        Thread({
            try {
                PrivilegedChannel.ensure()
            } catch (error: Throwable) {
                Log.w(TAG, "the warm-up connection failed", error)
            }
        }, "lw-channel-warmup").apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DshHost.start(this)
        return START_STICKY
    }

    /**
     * 启用着的插件重新接上 (协议第 7 节的"状态恢复")
     *
     * 绑一个伴侣的 Service 是异步的, 而整个恢复过程要做完"绑上 → 对照 describe → attach → enable"
     * 四步, 所以它只能在自己的线程上走: 放在 `onCreate` 里同步做, 主线程要等它几秒
     */
    private fun restorePlugins() {
        val alone = applicationContext
        Thread({
            try {
                PluginManager.restore(alone)
            } catch (error: Throwable) {
                Log.w(TAG, "restoring the enabled plugins failed", error)
            }
        }, "lw-plugin-restore").apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        DshHost.stop()
        AutomationEngine.stop()
        PrivilegedBridge.stop()
        // The privileged process would exit on its own once this one dies, but releasing root
        // explicitly is the difference between a service restart and a root process left running
        // on a thread of its own, because the call is a binder round trip and this is not
        Thread({ PrivilegedChannel.close() }, "lw-channel-close").apply { isDaemon = true }.start()
        super.onDestroy()
    }

    /** Notification channel for the host, low importance because the host is not an event */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.host_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.host_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /** Persistent notification that is also the way back into the GUI */
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.host_notification_title))
            .setContentText(getString(R.string.host_notification_text))
            // 通知的小图标不是启动器图标: 它按模板染色, 只认 alpha 通道, 所以给的是一张纯白的
            // 水花鲸尾剪影。原来这里用的是 `ic_launcher_foreground` (108dp 的启动器前景), 系统把它
            // 缩进 24dp 的状态栏位里, 糊成一团
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DshHostService"

        private const val CHANNEL_ID = "dsh-host"
        private const val NOTIFICATION_ID = 1

        /** Bring the host service up, from an activity or a receiver */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DshHostService::class.java))
        }
    }
}
