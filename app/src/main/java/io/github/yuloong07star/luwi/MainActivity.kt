package io.github.yuloong07star.luwi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import io.github.yuloong07star.luwi.channel.LwApps
import io.github.yuloong07star.luwi.channel.LwOcr
import io.github.yuloong07star.luwi.channel.PreviewControl
import io.github.yuloong07star.luwi.channel.ScreenshotBudget
import io.github.yuloong07star.luwi.host.BallReturn
import io.github.yuloong07star.luwi.host.DshHostService
import io.github.yuloong07star.luwi.overlay.OverlayState
import io.github.yuloong07star.luwi.overlay.OverlayService
import io.github.yuloong07star.luwi.theme.ThemeStore
import io.github.yuloong07star.luwi.tool.LwOverlay
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.tool.SpeakSettings
import io.github.yuloong07star.luwi.tool.VideoLooks
import io.github.yuloong07star.luwi.ui.LuwiApp
import io.github.yuloong07star.luwi.util.PermissionRequests

class MainActivity : ComponentActivity() {

    /**
     * 设置页「权限」段点名要的那些, 都在这里落地
     *
     * 申请只能从 Activity 发起, 而按钮在设置页里, 所以设置页只把"要什么"写进
     * [PermissionRequests.pending], 由 `setContent` 里那个 `LaunchedEffect` 看到之后再弹系统框
     *
     * **一次只发一条**: 一次带两条时 `Activity` 会打 `W Can request only one set of permissions at
     * a time` 并丢掉后一条 (真机 logcat 实测), 于是点一下什么都没有发生。所以结果回来之后立刻问队列
     * 要下一条, 直到问完为止
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        PermissionRequests.answered()
        launchNextPermission()
    }

    /** 把队列里下一条还没问过的权限弹出去, 没有了就收尾 */
    private fun launchNextPermission() {
        val next = PermissionRequests.next(consume = true)
        if (next != null) {
            permissionLauncher.launch(next)
            return
        }
        PermissionRequests.done()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 点通知进来的时候, 屏幕可能锁着: 让这一页越过锁屏显示, 是"从通知回到应用"这条路能走通的
        // 前提。它不解锁设备 —— 要看里面的内容仍然要解锁
        setShowWhenLocked(true)
        // 批次 5 那三条路的第三条: 没法拿电源锁、又没有特权通道时, "把应用提到前台"这一条自己就能
        // 点亮屏幕 —— 前提正是这个标志。它只在真的起了这一页时生效, 而且**不解锁** (要看内容仍然要
        // 解锁), 所以它是兜底而不是主路
        setTurnScreenOn(true)
        // 外观设置要在第一帧之前读出来, 否则会先闪一下默认主题
        ThemeStore.initialize(this)
        // 同理: 预览能不能当触摸板, 默认是关, 得在画画面之前就知道
        PreviewControl.initialize(this)
        // 截图缩到多少像素, 桥在第一次截图时就要用上
        ScreenshotBudget.initialize(this)
        // 视频模式取景那三条 (张数 / 间隔 / 清晰度): 相机在第一次取景时就要用上, 而设置页也要在
        // 画之前知道选的是哪几档
        VideoLooks.initialize(this)
        // 朗读的音色与语速: 回答落定就念那条链要用, 而设置页也要在画之前知道选的是哪个
        SpeakSettings.initialize(this)
        // OCR 的模型是懒加载的 (第一次调它才建 session), 这里只把 context 挂上去
        LwOcr.attach(this)
        // 能启动哪些应用要问 PackageManager, 同样只挂 context
        LwApps.attach(this)
        // The host deliberately outlives this activity, so the service owns its lifetime
        DshHostService.start(this)
        // 冷启动那一档: 「回应用」把界面拉起来时带的就是"要落到哪一场对话"那个 id (见 onNewIntent)
        // **带了空串就是"去新会话界面"** ([BallReturn.NEW_SESSION], 2026-10-09): 与"这个 intent 没带
        // 那个键"分得开 —— 没带 (`null`) 时 [BallReturn.ask] 什么都不做, 界面照旧只是被提到前面
        val ball = requestedSession(intent)
        Log.i(TAG, "created with open-session=$ball")
        BallReturn.ask(ball)
        // 唤醒词那个许可是存盘的, 所以应用一起来就照着它把监听恢复起来 —— 不然"允许唤醒"只是个记号,
        // 服务不会自己起, 而主人按下它的意思显然是"让它听着", 缺模型 / 缺权限 / 许可关着时它什么都不做;
        // 起不来也不该把界面带走 (那是前台服务那一侧的事, 它会把原因记在状态里)
        runCatching { LwWakeWord.ensure(this) }
        // 浮标那个开关也是存盘的: 开着就照它把球放出来 —— 缺悬浮窗权限或开关关着时, 它什么都不做
        runCatching { LwOverlay.ensure(this) }
        setContent {
            LuwiApp()
            // 设置页里点过的那条授权申请到这一层才真的弹。挂 Content 外面那一层而不是某一页里,
            // 是因为申请要跟着 Activity 活着, 页面来回切不该把它弄丢
            val pending = PermissionRequests.pending ?: return@setContent
            LaunchedEffect(pending) {
                launchNextPermission()
            }
        }
    }

    /**
     * 「回应用」再点一次时走这一条 (SINGLE_TOP: 界面已经在栈里就不再新建一个)
     *
     * 那一件事本身 (让会话界面切到浮标那一场) 走 [BallReturn]: 这一次的 intent 里带没带那个 id 都要
     * 传给它 —— 没带就什么都不做, 界面照旧只是被提到前面
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val ball = requestedSession(intent)
        // 这一行是「回应用」那一跳的证据: 它出现说明 intent 送到了界面, 不出现就是没送到
        // (而"没送到"那一条已经由 `OverlayService.openApp` 直接写 [BallReturn] 兜住了)
        Log.i(TAG, "onNewIntent open-session=$ball")
        BallReturn.ask(ball)
    }

    /**
     * 这一次 intent 要把会话界面带到哪一场
     *
     * **通知那一条是唯一带 `EXTRA_OPEN_LIVE` 的入口**, 而它那个 id 是在"通知重建那一刻"抄下来的
     * (PendingIntent 的附加数据在创建时就定死了) —— 之后回复一条条落进框、输入投给另一场, 那条账
     * **变了而通知不会跟着重建** (它只在球上那三个字变化时才重建, 而"框里进了一条回复"不改那三个
     * 字)。于是点通知会落到一个**过时**的会话上 —— 与菜单那行「返回应用」各答各的, 而两者本该是同
     * 一个去向 (主人 2026-10-11 报的就是"回到应用"这条路上的这处不一致)。
     *
     * 所以带这个记号时**以此刻那条账为准**: 与菜单那行「返回应用」同一套 (正在想的那一场, 没有就是
     * 一场新对话, 见 [OverlayState.runningSession]) —— 读不到 (冷启动, 进程新起一份, 那份球的账还没
     * 读进来) 才退回 intent 里那个 id。不带记号时就是别处点名的 id (球菜单那行给的), 原样用
     */
    private fun requestedSession(intent: Intent?): String? {
        if (intent == null) return null
        val baked = intent.getStringExtra(OverlayService.EXTRA_OPEN_SESSION)
        if (!intent.getBooleanExtra(OverlayService.EXTRA_OPEN_LIVE, false)) return baked
        return OverlayState.runningSession() ?: baked
    }

    private companion object {
        private const val TAG = "LwMain"
    }
}
