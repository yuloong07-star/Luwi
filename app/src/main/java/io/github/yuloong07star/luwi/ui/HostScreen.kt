package io.github.yuloong07star.luwi.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.constants.UiSpacing
import io.github.yuloong07star.luwi.channel.PreviewControl
import io.github.yuloong07star.luwi.channel.ScreenState
import io.github.yuloong07star.luwi.channel.VirtualScreen
import io.github.yuloong07star.luwi.host.BallReturn
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.HostStatus
import io.github.yuloong07star.luwi.overlay.OverlayState
import io.github.yuloong07star.luwi.sync.SyncSetting
import io.github.yuloong07star.luwi.tool.LwSpeak
import io.github.yuloong07star.luwi.tool.LwWakeWord
import androidx.core.content.ContextCompat
import io.github.yuloong07star.luwi.wake.WakeWordDownload
import io.github.yuloong07star.luwi.voice.VoiceState
import io.github.yuloong07star.luwi.wake.WakeWordService
import io.github.yuloong07star.luwi.wake.WakeWordState
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.icon.extended.Tune
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 主页面: dsh 的 Web GUI 占满, 虚拟屏的画面浮在它上面
 *
 * 画面是一个可拖动的小窗而不是占一条高度: 屏在后台跑着, 人要能一边看着它一边用会话, 而占高度
 * 会把会话挤掉一条。代价反过来了 - 小窗盖住的那一块 dsh 看不见 (WebView 量的是自己的视口), 所以
 * 窗越小界面越完整, 见 [FloatingScreenWindow] 与 `WINDOW_SCREEN_FRACTION`
 *
 * 顶栏末尾的级联菜单第一层就两项, 虚拟屏与设置; 虚拟屏那一项自己带着当前选中的是哪块屏, 展开
 * 才是屏的列表, 选中哪块看哪块, 再点一次同一块就是不看
 *
 * **有画面在看的时候顶栏整个让位** (2026-09-22): `SmallTopAppBar` 的高度是 `CollapsedHeight`
 * 52dp, 与标题长不长无关 (标题空着也一样), 而这一页的标题只是个应用名, 那 52dp 给会话更值。
 * 于是这时不放 topBar, 菜单按钮改成浮在小窗右上角, 静一会儿自己淡出, 碰一下画面再出来 - 与手机
 * 上视频播放器的控件一个脾气。不看画面了 (收起或没选中) 就回到那条默认顶栏
 * @param modifier layout modifier from the caller.
 */
@Composable
fun HostScreen(modifier: Modifier = Modifier) {
    val navigator = LocalRootNavigator.current
    val selected = VirtualScreen.selected
    // 选中就是显示: 没有"收起了但还选着"这种状态, 取消选中就是不看了
    val preview = selected

    // 减号把整扇窗收掉, 收起来之后界面上一点痕迹都不留; 要它回来走 ⋮ 菜单里那一项
    var windowHidden by remember { mutableStateOf(false) }

    // **这一页没有顶栏**: 原来那条写着应用名的 `SmallTopAppBar` 去掉了, 顶上那点位置让给会话。
    // 唯一的菜单入口是浮标 (见 FloatingBall)。`innerPadding` 仍然带着状态栏那条 inset, 所以内容
    // 不会钻到时钟底下 - 顶栏在的时候那条 inset 是由顶栏自己吃的, 现在改由 Scaffold 给
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                // 画面空着就是空着, 页面上不留一行字 - 有没有屏、选中哪一块, ⋮ 菜单里都写着
                // 只有出错时才说话, 否则失败会被静默吞掉
                if (selected == null) {
                    VirtualScreen.lastError?.let { reason ->
                        Text(
                            text = "虚拟屏: $reason",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                when (val status = DshHost.status) {
                    is HostStatus.Running -> HostWebView(url = status.url, modifier = Modifier.weight(1f))
                    else -> HostBootPanel(status = status, modifier = Modifier.weight(1f))
                }
            }
            // 小窗直接挂在 Scaffold 的内容里, **中间不留任何一层容器**: 拖动范围就是这一层按 inset
            // 收好之后的整块地方, 而它由窗口自己量 (见 FloatingScreenWindow 的 onSizeChanged)。
            // 上一版为了拿到这个范围套了一个 fillMaxSize 的空 Box, 而 Compose 的 Box 默认参与命中
            // 测试 - "空盒子不吃事件"是错的, 它把窗口底下 1600 多像素的触摸全吃了, 用户的原话是
            // "虚拟屏下面一半都用不了"。所以这里宁可让窗口自己量, 也不再放一个会占满屏幕的东西
            if (preview != null && !windowHidden) {
                FloatingScreenWindow(
                    screen = preview,
                    onMinimize = { windowHidden = true },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            // 浮标: 顶栏那个按钮的替身, 白底不透明、能拖到任何地方
            FloatingBall(
                selected = selected,
                onSettings = { navigator.push(Screen.Settings) },
                windowHidden = windowHidden,
                onToggleWindow = { windowHidden = !windowHidden },
                modifier = Modifier.padding(innerPadding),
            )
            // 「再按一次退出」走这里。挂在主页而不是脚手架上, 是因为提示只有主页会用到:
            // 按返回从设置页回来的时候不该弹它, 弹的是这一页的返回
            SnackbarHost(
                state = ExitHint.host,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            )
        }
    }
}


/**
 * 顶栏上那个菜单按钮
 *
 * 顶栏版与悬浮版共用它, 免得两处的菜单对不上
 */
@Composable
private fun MenuButton(
    selected: ScreenState?,
    onSettings: () -> Unit,
    windowHidden: Boolean,
    onToggleWindow: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    OverlayIconCascadingDropdownMenu(
        entries = mainMenu(
            selected = selected,
            onSettings = onSettings,
            windowHidden = windowHidden,
            onToggleWindow = onToggleWindow,
        ),
        modifier = modifier,
        backgroundColor = backgroundColor,
        onExpandedChange = onExpandedChange,
    ) {
        Icon(
            imageVector = MiuixIcons.Tune,
            contentDescription = "菜单",
        )
    }
}

/**
 * 虚拟屏的小窗
 *
 * 它浮在会话上面而不是占一条高度: 屏在后台跑着, 人要能一边看着它一边用 dsh 的界面。代价是它盖
 * 住的那一块会话就真看不见了 - WebView 量的是自己的视口, 被盖住的地方缩不回来, 所以它越小,
 * 界面越完整
 *
 * 整条标题栏是拖动把手 (画面本身已经拿去注入触摸了, 见 [VirtualScreenPreview], 在画面上再认拖动
 * 就分不清"移窗口"与"划屏"), 右端那个减号把整扇窗收掉 - 它不自己留一条边, 也不再自己回来, 要
 * 回来走 ⋮ 菜单里的那一项, 这样收起来之后界面上真的一点痕迹都不剩
 *
 * **它自己量自己能拖到哪儿**: 传进来的 modifier 带着 Scaffold 的 inset padding, 于是这一层的尺寸
 * 就是可用范围, 不需要外面再套容器。这一点是踩过才知道的 - 外面套一个 fillMaxSize 的盒子去量,
 * 那个盒子会把窗口底下整片触摸都吃掉
 *
 * @param screen 显示哪块屏, 它同时决定画面的形状
 * @param onMinimize 按下减号时叫一声, 由调用方把整扇窗收掉
 */
@Composable
private fun FloatingScreenWindow(
    screen: ScreenState,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp

    // 小窗头一次出现在哪儿: 顶上那一条是系统自己的手势区 (下拉通知栏), 落在里面的触摸根本到不了
    // 这里, 所以起始位置放在它下面, 免得一上来就抓不住。实测这台设备上 y=250 的下拉被通知栏接走,
    // y=400 才落回应用
    //
    // **只是一个起点, 不是下限**: 用户要的是整块屏幕都能放, 所以上面不设禁入区。真放进手势区里
    // 拖不动了也有退路 - 减号收起来再显示, 窗会重新站回这个起点 (它离开组合时那些 remember 就没了)
    val start = with(density) { WINDOW_START_TOP.roundToPx() }.toFloat()
    var position by remember { mutableStateOf(Offset(0f, start)) }
    // 这一层自己有多大, 就是窗口能走多远 - 量的是自己, 所以没有任何多余的东西参与命中测试
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }

    // 宽度是屏幕宽度的几分之一, 高度跟着屏自己的形状走 - 竖的屏得到一条竖的窗
    val width = with(density) { (screenWidth / WINDOW_SCREEN_FRACTION).dp.roundToPx() }
    val picture = (width / screen.aspect).roundToInt()
    val height = with(density) { WINDOW_BAR_HEIGHT.roundToPx() } + picture

    // 拖出屏幕就回不来了, 所以每次都用当前尺寸夹一遍; 上面不设下限, 整块屏幕都是它的地方
    val limitX = (viewport.width - width).coerceAtLeast(0).toFloat()
    val limitY = (viewport.height - height).coerceAtLeast(0).toFloat()
    // 指针推的是这个目标 (含钳制), 屏幕上跟的是下面那个弹簧的输出
    val target = Offset(position.x.coerceIn(0f, limitX), position.y.coerceIn(0f, limitY))

    // 下面那个拖动回调挂在 pointerInput 上, 而它不会因为位置变化重启, 所以回调里**只能读活的值**:
    // position 走 state 委托, 每次读都是当前值, 而这两个上限是组合期的局部量, 得包一层才是活的。
    // 第一版直接读了组合期算好的位置, 于是每一下拖动都从零重新算 - 窗几乎不动 (在设备上试出来的)
    val limits = rememberUpdatedState(Offset(limitX, limitY))

    // 目标与渲染分开, 与浮标同一套。**拖动中不用弹簧**: 弹簧会落后于手指, 小窗拖起来会像"粘"在
    // 原地、手停下才追上来
    val rendered = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, dragging) {
        when {
            !placed -> {
                rendered.snapTo(target)
                placed = true
            }
            dragging -> rendered.snapTo(target)
            else -> rendered.animateTo(target, SETTLE_SPRING)
        }
    }

    // "拿起来"那一下: 小窗只加投影, **不加缩放**。缩放是 RenderNode 变换, 而窗里的画面是
    // SurfaceView - 它的 surface 由 SurfaceFlinger 单独摆一层, 不跟着父节点的变换走, 一缩放就会
    // 出现"标题栏放大了、画面没放大"的错位。投影不影响 surface, 是安全的等效反馈
    val lift by animateDpAsState(
        targetValue = if (dragging) WINDOW_DRAG_ELEVATION else 0.dp,
        animationSpec = PRESS_LIFT_SPRING,
        label = "windowLift",
    )

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        Column(
            modifier = Modifier
                .offset { IntOffset(rendered.value.x.roundToInt(), rendered.value.y.roundToInt()) }
                .width(with(density) { width.toDp() })
                .shadow(elevation = lift, shape = RoundedCornerShape(UiSpacing.Medium))
                .clip(RoundedCornerShape(UiSpacing.Medium))
                .background(colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WINDOW_BAR_HEIGHT)
                    // 整条标题栏就是拖动把手 (减号那块除外, 它自己收事件, 见下)
                    .pointerInput(screen.displayId) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, drag ->
                            change.consume()
                            val (limitXNow, limitYNow) = limits.value
                            position = Offset(
                                (position.x + drag.x).coerceIn(0f, limitXNow),
                                (position.y + drag.y).coerceIn(0f, limitYNow),
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = screen.label,
                    modifier = Modifier.weight(1f).padding(start = UiSpacing.Small),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 触摸区比图标大一圈, 但仍然**远比 Miuix 的 IconButton 小**: 窗只有屏宽的几分之一,
                // 一个 48dp 的按钮会把整条标题栏占满, 拖动就没地方下手了 (第一版栽在这, 拖不动)
                Box(
                    modifier = Modifier
                        .padding(end = UiSpacing.Small)
                        .size(WINDOW_BUTTON_SIZE)
                        .clip(CircleShape)
                        .clickable(onClick = onMinimize),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Remove,
                        contentDescription = stringResource(R.string.screen_window_hide),
                    )
                }
            }
            // heightLimit 说的是"画面最多给到宽度的几倍", 而竖屏的画面要的高度是宽度的
            // screen.height/screen.width 倍 (这台设备上约 2.2 倍)。传 1 就等于按宽度把画面封顶,
            // 于是竖屏只画得出上面那 1/2.2, 底下空着的全是这个盒子的黑底 - 用户报的"下面一半都用
            // 不了"就是它 (上一版这里写的是 1f, 而 1 的含义我一开始就理解反了)。
            // 传这个比例本身, 盒子就正好是画面要的高度, 一点黑边都不剩
            VirtualScreenPreview(
                screen = screen,
                modifier = Modifier.fillMaxWidth().height(with(density) { picture.toDp() }),
                heightLimit = 1f / screen.aspect,
            )
        }
    }
}

/**
 * 浮标: 顶上那个菜单按钮, 做成一枚跟着主题走的球, 能拖到应用里的任何地方
 *
 * 它接替了两处旧东西 - 顶栏 `actions` 里那个按钮, 和看画面时浮在画面右上角的那个会淡出的按钮。
 * **合成一个是因为"要能拖"和"会自己淡出"是矛盾的**: 想拖的时候它正好不在, 那这个球就没法用。
 * 所以它一直在, 也从不透明 - 底下的东西说不准是什么 (顶栏 / dsh 的界面 / 别人家的画面), 透了就
 * 看不清, 而它是要用手指找的东西。底色跟着配色走 (见下), 不写死成白色
 *
 * 位置只在拖过之后才记住: 没拖过就待在右上角, 与它替掉的那个按钮同一个地方
 *
 * @param selected 菜单里那一栏要显示的屏, 与顶栏那个按钮用的是同一份菜单
 * @param onSettings 菜单里的设置页入口, 原样传下去
 * @param windowHidden 小窗现在收着没有, 菜单里那一项照它显示"显示"还是"隐藏"
 * @param onToggleWindow 菜单里那一项按下去时叫一声
 */
@Composable
private fun FloatingBall(
    selected: ScreenState?,
    onSettings: () -> Unit,
    windowHidden: Boolean,
    onToggleWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val ball = with(density) { BALL_SIZE.roundToPx() }
    // 没被拖过时站在右上角再让开一个图标位, 免得压住对话右边栏的第一个图标
    val startTop = with(density) { BALL_START_TOP.roundToPx() }.toFloat()
    // null = 还没被拖过, 于是它待在右上角而不是 (0,0)
    var position by remember { mutableStateOf<Offset?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // 空闲计时: 每次交互 +1, 这段重跑就是重新计时。菜单开着时不计 - 那时它正在被用
    var interactions by remember { mutableStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(interactions, menuOpen) {
        dimmed = false
        if (menuOpen) return@LaunchedEffect
        delay(BALL_IDLE_MS)
        dimmed = true
    }

    val limitX = (viewport.width - ball).coerceAtLeast(0).toFloat()
    val limitY = (viewport.height - ball).coerceAtLeast(0).toFloat()
    // 指针推的是这个目标 (含钳制), 屏幕上跟的是下面那个弹簧的输出
    val target = (position ?: Offset(limitX, startTop)).let {
        Offset(it.x.coerceIn(0f, limitX), it.y.coerceIn(0f, limitY))
    }
    // 与窗口那边同一个道理: 拖动回调挂在 pointerInput 上, 不会因为位置变化重启, 所以它只能读活值
    val limits = rememberUpdatedState(Offset(limitX, limitY))

    // 目标与渲染分开, 与窗口同一套。**但拖动中不用弹簧**: 弹簧会落后于手指, 用户报的"没有被拖动,
    // 而是移动停止后才最终落位"就是它。拖动中直接 snapTo, 位置变化照样走 layout 阶段
    val rendered = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, dragging) {
        when {
            !placed -> {
                rendered.snapTo(target)
                placed = true
            }
            dragging -> rendered.snapTo(target)
            else -> rendered.animateTo(target, SETTLE_SPRING)
        }
    }

    // 变淡: 只用 alpha, **绝不把它移出视图树**。老代码那个 AnimatedVisibility 淡出会真的把内容拿掉
    // (理由是留着会吃掉点击), 但这里相反 - 25% 的球必须仍然点得到、拖得动, 而 alpha 不影响命中测试
    val ballAlpha by animateFloatAsState(
        targetValue = if (dimmed) BALL_DIM_ALPHA else 1f,
        animationSpec = tween(BALL_FADE_MS),
        label = "ballAlpha",
    )
    // "拿起来"那一下: 球没有 SurfaceView, 缩放是安全的 (小窗就不行, 见那边的注释)
    val scale by animateFloatAsState(
        targetValue = if (dragging) BALL_DRAG_SCALE else 1f,
        animationSpec = PRESS_SCALE_SPRING,
        label = "ballScale",
    )
    val lift by animateDpAsState(
        targetValue = if (dragging) BALL_DRAG_ELEVATION else BALL_IDLE_ELEVATION,
        animationSpec = PRESS_LIFT_SPRING,
        label = "ballLift",
    )

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        Box(
            modifier = Modifier
                .offset { IntOffset(rendered.value.x.roundToInt(), rendered.value.y.roundToInt()) }
                .size(BALL_SIZE)
                // 拖动挂在球上, 点按由里面那个菜单按钮自己收 - 手指一动就超过触摸阈值, 按钮那次点击
                // 会被取消, 于是同一个球既点得开又拖得动。
                // 放在 graphicsLayer **之前**是故意的: 层的缩放会作用到后面的命中测试上, 拖动位移
                // 会跟着被按 1.08 折算, 球就走得比手指慢
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            interactions++
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        val (limitXNow, limitYNow) = limits.value
                        // **只能读活值**: `at` 是组合期算出来的, 而 pointerInput 不会因为位置变化
                        // 重启, 拿它当基准的话每一下拖动都从同一个死位置重算, 球就几乎不动。窗口那边
                        // 栽过一次, 这里又栽了一次 (写的时候明明刚修完)。position 走 state 委托, 每次
                        // 读都是当前值; 还没拖过就用右上角那个起点
                        val from = position ?: Offset(limitXNow, startTop)
                        position = Offset(
                            (from.x + drag.x).coerceIn(0f, limitXNow),
                            (from.y + drag.y).coerceIn(0f, limitYNow),
                        )
                    }
                }
                // **这一层必须写在投影/底色之前**: Compose 的绘制修饰符只影响排在它后面的节点,
                // 写在 background 之后就只淡化了里头那个图标, 底色与投影原样留着 (第一版就是这样,
                // 用户看到的正是"只有三横淡了、背景没淡")
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = ballAlpha
                }
                // 底色跟着主题走, 不写死白色: 浅色主题下纯白会和 dsh 的白底糊在一起, 而深色主题下
                // 它又得是深色的, 所以取的是当前配色里的 surface。投影让它看得出是浮起来的
                .shadow(elevation = lift, shape = CircleShape)
                .clip(CircleShape)
                .background(colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            MenuButton(
                selected = selected,
                onSettings = onSettings,
                windowHidden = windowHidden,
                onToggleWindow = onToggleWindow,
                // 球的底由外面这层给, 菜单按钮自己就不用再画一层
                backgroundColor = Color.Transparent,
                // 菜单开着时不计空闲: 那时用户正在用它, 淡下去没有道理
                onExpandedChange = { open ->
                    menuOpen = open
                    interactions++
                },
            )
        }
    }
}

/**
 * 顶栏菜单
 *
 * 屏是单选: 选中哪块看哪块, 选中的那块再点一次就取消选中 (这就是收起画面, 不再单设一个开关),
 * 而所有屏都还在跑 - 取消选中只是不看了
 */
@Composable
private fun mainMenu(
    selected: ScreenState?,
    onSettings: () -> Unit,
    windowHidden: Boolean,
    onToggleWindow: () -> Unit,
): List<DropdownEntry> {
    val context = LocalContext.current
    val screens = VirtualScreen.screens
    // 「停止朗读」那一条要知道现在有没有在念: `LwSpeak` 那个标记不是 Compose 状态, 而菜单是随手打开
    // 的, 所以这里半秒看一眼 (两次 volatile 读, 可以忽略), 一次都没念过时也就多几次空转
    //
    // 判据与球上、与半双工那道闸**同一个** (`talking`): 只认 `speakingNow` 时, 尾巴窗口里写着「正在说」
    // 而这一条却说自己没东西可停 (2026-10-16 一起对齐)
    var voiceSpeaking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            voiceSpeaking = LwSpeak.talking(context)
            delay(500)
        }
    }
    val idleHint = stringResource(R.string.menu_stop_speaking_idle)
    // 「同步」那一行 (2026-10-14 主人: 加在"进设置之前的那个菜单按钮"里): 它与设置页那一段是**同一件事**
    // —— `session-sync.json` 里的远端地址配没配。插件是**挂载时**读配置的, 所以这一个动作要顺带重启
    // host, 否则这一行等于没点。`SyncSetting.revision` 是它在文件被改过之后重读的记号 (菜单自己改的
    // 那一次也走同一条路 —— setSyncOn 会把它 +1)
    val syncSnapshot = remember(SyncSetting.revision) { SyncSetting.snapshot(context) }
    val syncOnToast = stringResource(R.string.menu_sync_switched_on)
    val syncOffToast = stringResource(R.string.menu_sync_switched_off)
    // 先算好: 下面那个下拉栏是 buildList 里拼的, 而那不是 composable 作用域, stringResource 进不去
    val windowToggle = stringResource(
        if (windowHidden) R.string.screen_window_show else R.string.screen_window_hide,
    )
    return listOf(
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = "虚拟屏",
                    summary = selected?.label,
                    children = buildList {
                        if (screens.isEmpty()) {
                            add(DropdownItem(text = "还没有已创建的虚拟屏", enabled = false))
                        }
                        screens.forEach { screen ->
                            add(
                                DropdownItem(
                                    text = screen.label,
                                    summary = screen.shape,
                                    selected = screen.displayId == selected?.displayId,
                                    onClick = { VirtualScreen.toggle(screen) },
                                ),
                            )
                        }
                        // 手动的刹车: 停的是模型的下一步动作, 不是这块屏也不是预览, 用户自己的手指
                        // 照样能碰画面
                        add(
                            DropdownItem(
                                text = if (selected?.acceptsControl == false) {
                                    "继续接受控制"
                                } else {
                                    "暂停接受控制"
                                },
                                summary = selected?.label,
                                enabled = selected != null,
                                onClick = {
                                    selected?.let { VirtualScreen.setAcceptsControl(it, !it.acceptsControl) }
                                },
                            ),
                        )
                        // 建屏不在这里: 一块屏叫什么、多大, 是工具在造它的时候决定的, 界面只负责看和关
                        // byUser = true: 之后模型再动这个 id, 桥要能说出"这是用户关的"
                        add(
                            DropdownItem(
                                text = "释放虚拟屏",
                                summary = selected?.label,
                                enabled = selected != null,
                                onClick = { selected?.let { VirtualScreen.release(it, byUser = true) } },
                            ),
                        )
                        // 减号收起来的窗从这里叫回来: 它收得干净, 界面上不留把手, 所以这一项是唯一
                        // 的路。放在"虚拟屏"这一栏里而不是一级菜单, 是因为它管的正是这一块屏
                        add(
                            DropdownItem(
                                text = windowToggle,
                                summary = selected?.label,
                                enabled = selected != null,
                                onClick = onToggleWindow,
                            ),
                        )
                    },
                ),
                // 这个开关放在一级菜单而不是设置页: 它是"现在这块画面能不能摸", 边看边切才对
                DropdownItem(
                    text = "虚拟屏触摸控制",
                    summary = "禁用以避免误操作",
                    selected = PreviewControl.allowed,
                    onClick = { PreviewControl.set(context, !PreviewControl.allowed) },
                ),
                // 停止朗读放一级菜单: 念一段长回答时要用它, 而那时人大多不在这块页面上, 一级菜单是
                // 这个界面里最短的一条路 (设置页那一行也能停, 但要先进设置)
                DropdownItem(
                    text = stringResource(R.string.menu_stop_speaking),
                    summary = if (voiceSpeaking) stringResource(R.string.menu_stop_speaking_now)
                    else stringResource(R.string.menu_stop_speaking_idle),
                    onClick = {
                        if (voiceSpeaking) {
                            LwSpeak.stop()
                        } else {
                            Toast.makeText(context, idleHint, Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                // 「同步」: 与设置页那一段同一个开关 (默认关)。关→开需要先有仓库地址, 没有就把人送到
                // 设置页那一段去填; 改完必须重启 host 才生效, 所以这一下顺手重启 (主人点的那一下)
                DropdownItem(
                    text = stringResource(R.string.menu_sync),
                    summary = when {
                        syncSnapshot.syncOn -> stringResource(R.string.menu_sync_on)
                        syncSnapshot.resumableRemote.isNotBlank() -> stringResource(R.string.menu_sync_off)
                        else -> stringResource(R.string.menu_sync_need_remote)
                    },
                    selected = syncSnapshot.syncOn,
                    onClick = {
                        val wanted = !syncSnapshot.syncOn
                        val problem = SyncSetting.setSyncOn(context, wanted)
                        if (problem == null) {
                            Toast.makeText(context, if (wanted) syncOnToast else syncOffToast, Toast.LENGTH_LONG).show()
                            DshHost.restart(context)
                        } else {
                            // 没有地址可填: 把原因说清并把人送到设置页那一段 (那一行就在那儿)
                            Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                            onSettings()
                        }
                    },
                ),
                DropdownItem(
                    text = "设置",
                    onClick = onSettings,
                ),
            ),
        ),
    )
}

/**
 * The dsh GUI itself, which is a normal browser page served from loopback
 *
 * The host authenticates a browser by answering the token URL with a redirect that sets an
 * HttpOnly cookie, so the WebView has to keep that cookie or every later request comes back
 * unauthorized, which is an empty body and therefore a blank page
 *
 * What a browser is expected to do beyond rendering - pick a file, save a download - needs an
 * activity behind it, so those two requests are routed out of the page here
 * @param url the token-carrying URL the host printed once its tree settled.
 * @param modifier layout modifier from the caller.
 */
@Composable
private fun HostWebView(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 「回应用」那一跳: **请求一变就跑一次** (见下面那个 LaunchedEffect), 而不押在 `AndroidView` 的
    // update 上 —— update 什么时候被叫到是 Compose 的事, 而这一跳是主人点名要的反馈 (2026-10-07:
    // "在 dsh 应用里双击回复框没有任何反馈")
    val ballRequest = BallReturn.request
    // WebView 实例与"这个文档加载完过几次": 切换那一步要拿它跑 JS, 还要等重载真的落地
    var page by remember { mutableStateOf<WebView?>(null) }
    var finishes by remember { mutableIntStateOf(0) }
    // A file input can only be answered by an activity, so the page's request parks here until
    // the picker it launched comes back
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val answer = pending ?: return@rememberLauncherForActivityResult
        pending = null
        // The platform helper reads either form of result the picker can return, a single data
        // URI or the clip data of a multiple selection
        answer.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                // dsh keeps theme and font size in localStorage, which is also how a phone and
                // a desktop browser reach the same server with different appearance settings
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                // dsh 的朗读在页面里播放音频, 而 WebView 默认要有用户手势才放音: 消息到了
                // 自动朗读会在没有手势的时候被拦掉, 这一条就是给它的 (系统引擎那一路走
                // 通道的 speak, 不经页面, 不受这条影响)
                settings.mediaPlaybackRequiresUserGesture = false
                // Compose sizes an AndroidView through the modifier and leaves layoutParams at
                // WRAP_CONTENT, and a WebView in that state resolves every viewport unit to 0,
                // which collapses the dialogs, menus and directory picker dsh measures in vh,
                // the visible size in Compose has nothing to do with it, so this has to be set
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // 输入框旁边那个麦克风指示器要问的两件事都在 app 这一侧 (服务在不在听、点一下停)
                addJavascriptInterface(WakeBridge(viewContext.applicationContext), WAKE_BRIDGE)
                // The page is the whole product surface, so its own failures need somewhere to
                // show up: status codes, load errors, and browser console lines all go to logcat
                webViewClient = object : WebViewClient() {
                    // Only the host's own pages belong in this view: everything here is one document,
                    // so a link that navigates this view away **replaces the whole GUI** and there is
                    // no way back to the session (2026-10-18: 轨迹 那个详情预览里的链接就是一例, 点完
                    // 屏幕上只剩那个网站). 别处的链接交给系统浏览器 (主人手机上一直这么用), 非页面
                    // 的 scheme (mail / app) 一律拒掉并记一行, 免得平台弹一个"打开方式"盖住 GUI
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        if (target.scheme != "http" && target.scheme != "https") {
                            Log.w(WEB_TAG, "blocked navigation to $target")
                            return true
                        }
                        if (sameOrigin(target, Uri.parse(url))) return false
                        val problem = openPage(view.context, target.toString())
                        if (problem == null) Log.i(WEB_TAG, "handed $target to the system browser")
                        else Log.w(WEB_TAG, problem)
                        return true
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        Log.i(WEB_TAG, "finished $finishedUrl")
                        // A page that loads without any HTTP or console error can still be blank,
                        // so the shell state itself is asked for and logged
                        view.evaluateJavascript(SHELL_PROBE) { result ->
                            Log.i(WEB_TAG, "shell $result")
                        }
                        // 每次文档加载都把那个指示器装回去: 它是我们画在页面上的, 换一次文档就没了
                        view.evaluateJavascript(WAKE_BADGE_JS, null)
                        // 「回应用」那一跳在等"页面什么时候回来" (重载 / 冷启动那两档都是它)
                        finishes += 1
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        Log.w(WEB_TAG, "http ${errorResponse.statusCode} for ${request.url}")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        Log.w(WEB_TAG, "error ${error.errorCode} ${error.description} for ${request.url}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    // dsh 的语音输入是页面自己录音 (getUserMedia + MediaRecorder), 而 WebView 不覆写
                    // 这一条就等于不给页面麦克风: 请求到这里没人应, Chromium 按拒绝处理。只放音频,
                    // 摄像头与 MIDI 仍然拒掉; 系统侧的 RECORD_AUDIO 由设置页那一项运行时权限管。
                    // http://127.0.0.1 属安全上下文 (localhost 例外), 所以按 host 判不会把自己拦掉
                    override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                        val audio = request.resources.filter {
                            it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        }
                        if (request.origin.host != "127.0.0.1" || audio.isEmpty()) {
                            Log.w(WEB_TAG, "denied ${request.resources.joinToString()} for ${request.origin}")
                            request.deny()
                            return
                        }
                        Log.i(WEB_TAG, "granting ${audio.joinToString()} to ${request.origin}")
                        request.grant(audio.toTypedArray())
                    }
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i(WEB_TAG, "console ${message.messageLevel()} ${message.message()}")
                        return true
                    }

                    // A popup cannot become a second view here, and leaving the request to
                    // Chromium is what can put a platform chooser over the GUI, so the attempt
                    // is refused and logged rather than answered
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean {
                        Log.w(WEB_TAG, "blocked popup dialog=$isDialog gesture=$isUserGesture")
                        return false
                    }

                    // dsh attaches files through a plain file input, and the chooser it needs is
                    // an activity the page cannot open itself
                    override fun onShowFileChooser(
                        view: WebView,
                        callback: ValueCallback<Array<Uri>>,
                        params: FileChooserParams,
                    ): Boolean {
                        Log.i(WEB_TAG, "file chooser ${params.acceptTypes.joinToString()}")
                        // A second request supersedes the first, and every callback has to be
                        // answered exactly once
                        pending?.onReceiveValue(null)
                        pending = callback
                        return try {
                            picker.launch(params.createIntent())
                            true
                        } catch (error: ActivityNotFoundException) {
                            Log.w(WEB_TAG, "no picker for ${params.acceptTypes.joinToString()}: ${error.message}")
                            pending = null
                            callback.onReceiveValue(null)
                            true
                        }
                    }
                }
                setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                    startDownload(context, downloadUrl, contentDisposition, mimeType)
                }
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
            page = view
        },
    )

    /**
     * 等下一次 `onPageFinished` (超时回 false): "重载真的落地了没有"只有它答得上来
     *
     * [finishes] 是那个计数器, 读它是 Compose 的状态读 —— 所以在 `snapshotFlow` 里能等到它变
     */
    suspend fun awaitFinish(after: Int): Boolean = withTimeoutOrNull(RETURN_WAIT_MS) {
        snapshotFlow { finishes }.first { it > after }
    } != null

    /** 跑一段 JS 并把它的字符串结果拿回来 (超时 / 页面被换掉就回 null) */
    suspend fun ask(view: WebView, script: String): String? = withTimeoutOrNull(RETURN_ASK_MS) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { answer ->
                // 超时 / 页面被换掉之后回调还会来一次, 已取消的续体不再喂值
                if (continuation.isActive) continuation.resume(answer.orEmpty())
            }
        }
    }

    // 返回键先问页面一句 (见 [PageBack]): 网页里那一层 (右栏的浏览器格, 手机上它是全屏的) 收得掉就
    // 收掉, 收不掉把这一下还回去。超时按 [PAGE_BACK_MS] 而不是 `ask` 那个 5 s —— 返回键不能挂着等
    val scope = rememberCoroutineScope()
    DisposableEffect(page) {
        val view = page
        PageBack.handle = view?.let { target ->
            { nothing ->
                scope.launch {
                    val answer = withTimeoutOrNull(PAGE_BACK_MS) { ask(target, PAGE_BACK_JS) }
                    val closed = answer?.contains("closed") == true
                    Log.i(WEB_TAG, if (closed) "back: the page closed its own layer" else "back: the page had nothing to close")
                    if (!closed) nothing()
                }
            }
        }
        onDispose { PageBack.handle = null }
    }

    // 「回应用」那一跳 (**在 dsh 里也一定有反馈**, 主人 2026-10-07): 请求一来就切到那一场 —— 需要就
    // 改键 + 重载, 然后**读回那个键验证**; 一次不成再试一次, 两次都不成才把请求放掉。无论哪一种结果
    // 都留一行日志与一笔状态 (`lw_overlay op=state` 的 `said` 读它)
    LaunchedEffect(ballRequest?.seq, page) {
        val request = ballRequest ?: return@LaunchedEffect
        val view = page ?: return@LaunchedEffect
        // 空 = [BallReturn.NEW_SESSION]: 这一跳不是"切到某一场", 而是"回到新会话界面" —— 会话界面自己
        // 没有"新建一场"的公开入口, 所以做法是把"看的是哪一场"那一笔擦掉再重载, dsh 那侧会自己开一场
        // 新的 (与冷启动时键还空着那一条是同一条路, 见 `ui-workspace` 的 `restoreSelection`)
        val fresh = request.session.isEmpty()
        val label = if (fresh) "a new conversation" else request.session
        // 页面还没落在 http 上时 localStorage 还不可用 (about:blank 那一档): 等它的 onPageFinished
        if (view.url?.startsWith("http") != true) {
            noteReturn("holding the return to $label until the GUI is up")
            if (!awaitFinish(finishes)) {
                // 等不到就把这一次请求收干净再说一句: 留在半路上 ("状态里还挂着请求"而界面上没人会
                // 再动它) 比明说一次没成更糟
                noteReturn("return to $label: failed: the GUI never came up")
                BallReturn.done(request.seq)
                return@LaunchedEffect
            }
        }
        var outcome = "failed: the page never answered"
        for (attempt in 1..RETURN_TRIES) {
            val before = finishes
            val answer = ask(view, returnJs(request))?.trim()?.removeSurrounding("\"")
            if (answer == "already") {
                outcome = "already on it"
                break
            }
            if (answer != null && answer.startsWith("failed")) {
                outcome = answer
                continue
            }
            // `reload` (或页面没回话): 等这一次重载落地之后再读回那个键 —— "重载了但没落在那一场"
            // 与"真的切过去了"要分得开
            if (!awaitFinish(before)) {
                outcome = "failed: the page did not come back"
                continue
            }
            // **新会话那一档没有 id 可对**: 做完 (clear + reload) 就算成 —— 读回那个键只会读到
            // dsh 自己新建那一场的新 id, 拿它跟要的那个空值比永远不等
            if (fresh) {
                outcome = "verified on try $attempt (a new conversation)"
                break
            }
            val now = ask(view, CURRENT_SESSION_JS)?.trim()?.removeSurrounding("\"")
            if (now == request.session) {
                outcome = "verified on try $attempt"
                break
            }
            outcome = "failed: the GUI came back on ${now?.takeIf { it.isNotEmpty() } ?: "nothing"}"
        }
        noteReturn("return to $label: $outcome")
        BallReturn.done(request.seq)
    }
}

/**
 * 「回应用」那一跳走到哪一步: 一行日志 + 一笔状态
 *
 * 两个去处都要有: 主人 2026-10-07 报的那条是"在 dsh 应用里双击回复框没有任何反馈", 而排查时能对账的
 * 就是 `lw_overlay op=state` 的 `said`(它读 [OverlayState.lastHint]) 与 `adb logcat -s DshWebView`
 */
private fun noteReturn(text: String) {
    Log.i(WEB_TAG, text)
    OverlayState.lastHint = text
}

/**
 * 那一段 JS: 判一下"已经是这一场了吗", 不是就改键 + 重载
 *
 * **为什么只有这一条路**: "在看哪一场"是 dsh 客户端在浏览器里的路由状态
 * (`localStorage['dsh.sessions.current']`, 值形如 `{"sessionId":"session-…"}`), 它只在页面启动那一步
 * 读一次 (见 `ui-workspace` 的 `restoreSelection`) —— 客户端没有公开的路由可用, 所以唯一拿得到的把手
 * 就是这个键, 改完必须重载一次
 *
 * `location.reload()` 是**故意的**: 那个键只在页面启动那一步读, 改完不重载就等于没改。同步执行
 * (改键与重载在同一个 JS 块里) 是为了不给客户端一个"看了一半的状态又被重载"的窗口
 */
private fun RETURN_TO_BALL_JS(session: String): String =
    "(function(){try{var k='dsh.sessions.current';var want={sessionId:${JSONObject.quote(session)}};" +
        "var cur=null;try{cur=JSON.parse(localStorage.getItem(k)||'null')}catch(e){cur=null}" +
        "if(cur&&cur.sessionId===want.sessionId){return 'already'}" +
        "localStorage.setItem(k,JSON.stringify(want));location.reload();return 'reload'}catch(e){return 'failed:'+e}})()"

/**
 * 这一跳该跑哪一段 JS: 空会话 = **去新会话界面**
 *
 * 是新是旧只看有没有 id, 判据与 [BallReturn.NEW_SESSION] 是同一个: 带 id 就切到那一场, 不带就擦掉
 * "看的是哪一场"那一笔再重载。分成两个函数而不是一个带 `if` 的, 是为了让"新会话"那一段 JS 短到
 * 一眼能读完 (它只做一件事: 删键 + 重载)
 */
private fun returnJs(request: BallReturn.Request): String =
    if (request.session.isEmpty()) BALL_NEW_SESSION_JS else RETURN_TO_BALL_JS(request.session)

/**
 * 那一段 JS: **回到新会话界面**
 *
 * 会话界面的"新建一场"没有公开入口, 而它启动那一步会把 `dsh.sessions.current` 当"上次看的那一场"
 * ([`ui-workspace`] 的 `restoreSelection`) —— 那个键指向的会话还在、而且是个空会话时它会**复用**它,
 * 所以"最坏"也只是复用一场已经空着的会话 —— 那与新建一场对主人是同一件事 (一块干净的新对话), 而
 * dsh 那侧**没有把会话列表写进 localStorage** (那份清单走 rpc), 页面里判不出"现在这本账是不是空
 * 的"。所以这里只有一条动作: 删键 + 重载。验证那一半在 [HostScreen] 里也是"做完就算成" —— 新建
 * 之后的 id 是本机生成的, 拿它跟要的那个空值比永远不等
 */
private const val BALL_NEW_SESSION_JS =
    "(function(){try{localStorage.removeItem('dsh.sessions.current');location.reload();" +
        "return 'reload'}catch(e){return 'failed:'+e}})()"

/**
 * 读回"现在这个键里写着哪一场": 切换那一步的判据 (空串 = 读不出来)
 *
 * 与 [RETURN_TO_BALL_JS] 配成一对: 一个负责改, 一个负责验证 —— 少了后面那条, "重载了但没落在那一场"
 * 会被当成成功
 */
private const val CURRENT_SESSION_JS =
    "(function(){try{var v=JSON.parse(localStorage.getItem('dsh.sessions.current')||'null');" +
        "return v&&v.sessionId?v.sessionId:''}catch(e){return ''}})()"

/**
 * Hand an http(s) download to the system downloader
 *
 * The host authorises a browser with a cookie that the downloader's own process cannot see, so
 * the header travels with the request
 *
 * A `blob:` download never reaches this listener, because the bytes only exist inside the page;
 * those need the page itself to hand them over and are not supported yet
 * @param context context whose download service is used.
 * @param url the URL the page asked to save.
 * @param contentDisposition the page's disposition header, which often carries the file name.
 * @param mimeType the page's content type, absent when the page did not name one.
 */
private fun startDownload(context: Context, url: String, contentDisposition: String?, mimeType: String?) {
    if (!url.startsWith("http")) {
        Log.w(WEB_TAG, "unsupported download $url")
        return
    }
    val request = DownloadManager.Request(Uri.parse(url))
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            URLUtil.guessFileName(url, contentDisposition, mimeType),
        )
    if (!mimeType.isNullOrEmpty()) request.setMimeType(mimeType)
    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
    context.getSystemService(DownloadManager::class.java).enqueue(request)
    Log.i(WEB_TAG, "download enqueued for $url")
}

/**
 * 小窗的宽度是屏幕宽度的几分之一
 *
 * 分母越大窗越小, 而窗越小它盖住的会话越少 - 这是这个窗唯一的取舍, 所以它在这里是一个数。
 * 一开始给的是 6 (手机上 60dp), 实测下来画面小得看不清, 现在按用户要求翻倍成 3
 */
private const val WINDOW_SCREEN_FRACTION = 3

/** 浮标有多大 */
private val BALL_SIZE = 44.dp

/**
 * 浮标没被拖过时停在哪: 右上角, 但**往下让开一个 dsh 侧边栏图标位**
 *
 * 对话时右边栏的图标就贴在右上角, 球压在第一个图标上, 那一个就点不着了。dsh 侧边栏的每一项是
 * 36px 的行高加 4px 间距 (见 `packages/client/ui-sidebar` 的 `SidebarRoot.module.css`), 所以一个
 * 图标位是 40dp - 球从那里往下站, 正好落在第一个图标下面
 */
private val BALL_START_TOP = 40.dp

/** 浮标空闲多久开始变淡, 以及淡到多少 */
private const val BALL_IDLE_MS = 5000L
private const val BALL_DIM_ALPHA = 0.25f

/** 变淡用多久, 和拖动那套弹簧分开: 这是"提示", 不是"运动", 用一条短渐变就够 */
private const val BALL_FADE_MS = 220

/** 按住浮标时的放大, 就是"拿起来"那一下 */
private const val BALL_DRAG_SCALE = 1.08f

/** 闲着与按住的投影高度 */
private val BALL_IDLE_ELEVATION = 2.dp
private val BALL_DRAG_ELEVATION = 6.dp

/**
 * 小窗按住时的投影
 *
 * 小窗只有投影**没有缩放**, 因为它的画面是 `SurfaceView` - 见 [FloatingScreenWindow] 的注释
 */
private val WINDOW_DRAG_ELEVATION = 6.dp

/**
 * 按住/松开那一下的弹簧: 略欠阻尼, 带一点点回弹, "软"就是从这儿来的
 *
 * 分两个是因为 `animateFloatAsState` 与 `animateDpAsState` 要的规格类型不一样
 */
private val PRESS_SCALE_SPRING = spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
private val PRESS_LIFT_SPRING = spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)

/**
 * 不在拖动时 (头一次落位、可用范围变了) 用的弹簧, 让位置变化不是硬切
 *
 * **拖动中不用它**: 第一版让渲染值用弹簧去追指针, 而 `StiffnessMediumLow` 太软, 屏幕上的球明显
 * 落在手指后面, 要等手停下来才追得上去 — 用户的原话是"没有被拖动, 而是移动停止后才最终落位, 这样
 * 导致更加生硬"。跟手这件事上严丝合缝比优雅重要, 所以拖动中直接 `snapTo`, 柔化交给按住那一下
 * 缩放与投影
 */
private val SETTLE_SPRING = spring<Offset>(stiffness = Spring.StiffnessMediumLow, dampingRatio = 1f)

/** 小窗顶栏的高度, 拖动把手与减号都在这条上 */
private val WINDOW_BAR_HEIGHT = 36.dp

/**
 * 小窗上那个减号的触摸区
 *
 * 它比图标大一圈, 但**绝不能换成 Miuix 的 `IconButton`**: 那个是 48dp, 而窗只有屏宽的几分之一
 * (手机上 60dp), 一放上去整条标题栏就只剩按钮了 - 子节点先拿到事件, 拖动再没有地方下手
 */
private val WINDOW_BUTTON_SIZE = 24.dp

/**
 * 小窗头一次出现在离屏幕顶多高的地方
 *
 * 屏幕顶部那一条被系统拿去做下拉通知栏的手势区, 落在里面的触摸不会交给应用, 所以标题栏一进去就
 * 拖不动了 - 起始位置放在这条线以下, 免得一上来就抓不住。实测这台设备上 y=250 的下拉被通知栏接
 * 走, y=400 才回到应用, 这个值要在这条分界之上留出余量
 *
 * 它只管**起点**: 用户要的是整块屏幕都能放, 所以往上不再设禁入区, 拖到顶是允许的
 */
private val WINDOW_START_TOP = 120.dp

/** Logcat tag for what the embedded browser reports */
private const val WEB_TAG = "DshWebView"

/**
 * 这一跳是不是宿主自己那一页 (scheme 与 host:port 都一样)
 *
 * 这个 WebView 里从头到尾**只有一个文档**: 会话界面。别处的页面一旦在这里导航成功, GUI 就被顶掉
 * 了 —— 而它没有"历史后退"那条路可走 (dsh 的客户端根本没有用 `history`, 见 `PAGE_BACK_JS` 那一
 * 段), 屏幕上只剩那个网站。判据只比 origin, 不比路径: 宿主自己的路由与静态资源都在同一个 origin 上
 */
private fun sameOrigin(target: Uri, host: Uri): Boolean =
    target.scheme == host.scheme && target.host == host.host && target.port == host.port

/**
 * 「回应用」那一跳的耐心: 等一次重载落地多久 / 等一次 JS 回话多久, 以及最多试几次
 *
 * 两次的代价是"重载两遍页面", 而收益是"一次没成不会静默什么都不发生" —— 在 dsh 里那一下本来就是
 * 主人点名要的反馈
 */
private const val RETURN_WAIT_MS = 15_000L
private const val RETURN_ASK_MS = 5_000L
private const val RETURN_TRIES = 2

/**
 * 返回键问页面那一句最多等多久
 *
 * `evaluateJavascript` 是同步进渲染进程的, 正常几毫秒就回; 这个上限只兜"页面正忙 / 已经不在了"
 * 那一档 —— 挂得比它久就等于把返回键吃掉了, 而没答话时调用方会把这一下还给「再按一次退出」
 */
private const val PAGE_BACK_MS = 500L

/**
 * 返回键那一下该按页面上的哪个东西: 右栏那个展开着的格右上角的「收起」
 *
 * 手机上 (视口窄于 768 px) 那一格是**全屏**的, 点一条网页链接之后会话连同顶栏一起被它盖住, 而系统
 * 返回键原来只会在主页上弹「再按一次退出」—— 用户的原话是"网页全屏, 无法返回会话" (2026-10-18)
 *
 * 判据只用 dsh 自己写在那块面板上的两个 `data` 属性, 不猜类名也不按文字认: `data-sidebar-right-open`
 * 说明它现在展开着 (`fullscreen` 与 `push` 两档都算, 后者在横屏与平板上就是半屏那一块),
 * `data-sidebar-right-toggle` 就是面板上那颗「收起」(见 dsh 的 `ui-sidebar-right` 里 PanelChrome,
 * 两个属性都由它自己写, 与界面语言无关)
 *
 * 回 `closed` / `none`: 后者是"这一页没有可收的层", 调用方据此把返回键还回去
 */
private const val PAGE_BACK_JS = """
(function () {
  var panel = document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]')
  if (!panel) return 'none'
  var collapse = panel.querySelector('[data-sidebar-right-toggle]')
  if (!collapse) return 'none'
  collapse.click()
  return 'closed'
})()
"""

/** Shell facts worth logging after a load: boot globals, DOM size, and whatever text rendered */
private const val SHELL_PROBE = """
(function () {
  var root = document.getElementById('root')
  var probe = document.createElement('div')
  probe.style.cssText = 'position:absolute;left:-1px;width:1px;height:100vh'
  document.body.appendChild(probe)
  var vh = probe.getBoundingClientRect().height
  probe.remove()
  return JSON.stringify({
    ready: typeof globalThis.__DSH_BOOT_READY__,
    boot: typeof globalThis.__DSH_BOOT__,
    loader: typeof globalThis.__ModuleLoader__,
    rootChildren: root ? root.childElementCount : -1,
    htmlLength: document.documentElement.outerHTML.length,
    innerHeight: innerHeight,
    vh: vh,
    text: (document.body.innerText || '').slice(0, 80)
  })
})()
"""

/** The name the page sees for the bridge above: `window.Luwi` */
private const val WAKE_BRIDGE = "Luwi"

/**
 * 页面与 app 之间那一座桥: 唤醒词那两件事 + "现在在看哪一场"
 *
 * 为什么不是 dsh 的 client 插件加一条私有路由: 那个指示器要显示的状态 (服务在不在听、命中了几次)
 * 只有 app 这一侧知道, 而点它要停的也是 app 里那个前台服务 —— 插件跑在浏览器 JS 里, 两个都拿不到,
 * 还得再连一条回 app 的通道; 而 dsh 的 client 插件是内部协议上的产物 (每个包跟着上游的 descriptors
 * 与 codecs 走), 为一个小徽标挂一个包不划算。这条桥与一条路由的信任边界是一样的: 页面就是本机 host
 * 发的那一个 (见上面 shouldOverrideUrlLoading 只放行 http/https)
 *
 * **三个方法里只有 [session] 带参数** (2026-10-07 加的): 它是页面推上来的"现在在看哪一场", 而那份
 * 状态只有浏览器里有 (`localStorage['dsh.sessions.current']`)。这个参数只当提示用 —— 它最终要过宿主
 * 那一侧的"这一场还在、还是根会话"检查, 认不出来就落回原来的规则, 所以桥上多这一条不改信任边界
 */
private class WakeBridge(private val context: Context) {

    /**
     * 现在什么样: **这一句话的窗口开着没有**、命中几次、看的是哪几个词 —— 一句 JSON, 页面照着画
     *
     * 这里给的是 `voice`, 它读的是 [VoiceState.capturing]: 那个胶囊对应的是"麦克风正在被吃", 所以只有
     * 命中唤醒词 (或球上点一下) 之后那一段时间里才该出现, **纯唤醒词守着的时候它不出来** —— 那是常态
     * (唤醒词一直在守), 不该在会话界面上一直挂一个"正在听"的徽标 (主人 2026-10-05 定的两条链分开说)
     *
     * **它原来读的是 `WakeWordState.voiceActive`** (2026-10-06 改的): 那一个是"常驻语音那半条在不在
     * 跑", 而常驻语音整条链路已经删掉了 —— 现在两者说的是同一件事, 只剩 `capturing` 这一个名字
     */
    @JavascriptInterface
    fun state(): String = JSONObject().apply {
        put("voice", VoiceState.capturing)
        put("listening", WakeWordState.listening)
        put("hits", WakeWordState.hits)
        put("words", LwWakeWord.names(context).joinToString(", "))
        put("ready", WakeWordDownload.present(context))
        put("label", context.getString(R.string.wake_badge_listening))
        put("stop", context.getString(R.string.wake_badge_stop))
    }.toString()

    /**
     * 点一下就是**把这一句话的窗口收掉**
     *
     * **不关唤醒词**: 那才是这个胶囊代表的那件事 (它只在窗口开着时出现), 而唤醒词继续守着 —— 想再要
     * 一次"开口说话", 喊一声就回来了, 整件事收工是通知栏那个「停止」, 那个按钮关的是服务, 与这里
     * 分工不同
     */
    @JavascriptInterface
    fun stop(): String {
        val intent = Intent(context, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_HUSH)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        return state()
    }

    /**
     * 页面报一句: **它现在在看哪一场** (`localStorage['dsh.sessions.current']` 里那个 id)
     *
     * 用处只有一个: 切进视频模式那一刻把这一场**定格**下来 ([LwWakeWord.resident]), 于是视频模式里
     * 说的话与"打开视频模式"那句话落在同一场 (主人 2026-10-07 的口径: 两条投递目标要统一)
     *
     * 空串是合法输入, 意思是"现在没有选中的会话" (页面刚起来 / 选择被清掉那一档) —— 那时把它清成
     * null, 别让一个过期的 id 钉在那儿。长度上限只是防呆: 真伪由宿主那侧的存在性检查兜底
     */
    @JavascriptInterface
    fun session(id: String) {
        val wanted = id.trim()
        VoiceState.uiSession = wanted.takeIf { it.isNotEmpty() && it.length <= SESSION_ID_MAX }
    }

    private companion object {
        /** 会话 id 的长度上限 (dsh 的是 `session-` 加一个 uuid, 给足余量) */
        const val SESSION_ID_MAX = 128
    }
}

/**
 * 输入框上沿那个麦克风指示器
 *
 * 状态只有一条: **"这一句话的窗口"开着的时候才出来**, 用跳动的波形表示"麦克风正在被吃", 点一下把它
 * 关掉 (上面的 [WakeBridge]) —— 一直开着的麦克风必须有一眼看得见、一下就关得掉的地方, 通知栏那一条
 * 是后台时看的, 这一条是看着会话时看的。**唤醒词守着的时候它不出现**: 那是常态 (麦克风本来就开着听
 * 那个词), 一直挂着只会让人以为麦克风在被吃
 *
 * 它原来代表的是"常驻语音" (那条一直不收的链), 而那条链 2026-10-06 已经删掉了 —— 徽标本身留着,
 * 语义收窄成"这一句正在听"
 *
 * 位置是**算出来的**: 找到页面里那个输入框 (textarea 或 contenteditable), 贴在它上沿的左上角; 找不到
 * 就退回右下角一个固定位置。所以不碰输入框自己的控件 (发送键那些还在原地), 也不依赖 dsh 的类名 ——
 * 它换一次前端不该让这个徽标消失
 *
 * 样式一律用 CSSOM (`element.style.x = ...`) 与 JS 计时器来做, **不插样式表也不插 keyframes**: 页面
 * 万一哪天带上 `style-src` 的 CSP, 内联样式表会被挡掉, 而这样写不受影响
 */
private const val WAKE_BADGE_JS = """
(function () {
  if (!window.Luwi) return
  var ID = 'lw-wake-badge'
  var TICK = 220
  var POLL = 1000
  var CURRENT = 'dsh.sessions.current'
  var phase = 0
  var last = null
  var lastSession = null
  function build() {
    if (document.getElementById(ID) || !document.body) return
    var badge = document.createElement('div')
    badge.id = ID
    var style = badge.style
    style.position = 'fixed'
    style.zIndex = '2147483646'
    style.display = 'flex'
    style.alignItems = 'center'
    style.gap = '6px'
    style.padding = '4px 10px 4px 8px'
    style.borderRadius = '999px'
    style.background = 'rgba(24,24,27,0.86)'
    style.color = '#ffffff'
    style.font = '12px/16px system-ui,-apple-system,sans-serif'
    style.boxShadow = '0 2px 10px rgba(0,0,0,0.28)'
    style.opacity = '0'
    style.pointerEvents = 'none'
    style.transition = 'opacity 200ms'
    style.cursor = 'pointer'
    var wave = document.createElement('span')
    wave.style.display = 'flex'
    wave.style.alignItems = 'flex-end'
    wave.style.gap = '2px'
    wave.style.height = '14px'
    for (var i = 0; i < 5; i++) {
      var bar = document.createElement('i')
      bar.style.display = 'block'
      bar.style.width = '2px'
      bar.style.height = '3px'
      bar.style.borderRadius = '1px'
      bar.style.background = '#7ee787'
      wave.appendChild(bar)
    }
    var text = document.createElement('span')
    text.setAttribute('data-role', 'text')
    badge.appendChild(wave)
    badge.appendChild(text)
    badge.onclick = function (event) {
      event.preventDefault()
      event.stopPropagation()
      try { last = JSON.parse(window.Luwi.stop()) } catch (error) {}
      draw(last, false)
      bars(false)
    }
    document.body.appendChild(badge)
  }
  function place(badge) {
    var editable = document.querySelector('textarea, [contenteditable="true"]')
    if (editable) {
      var box = editable.getBoundingClientRect()
      if (box.width > 40 && box.height > 0) {
        badge.style.left = Math.max(6, box.left + 2) + 'px'
        badge.style.top = Math.max(6, box.top - 30) + 'px'
        badge.style.right = 'auto'
        badge.style.bottom = 'auto'
        return
      }
    }
    badge.style.left = 'auto'
    badge.style.top = 'auto'
    badge.style.right = '12px'
    badge.style.bottom = '96px'
  }
  function draw(state, live) {
    var badge = document.getElementById(ID)
    if (!badge) return
    if (!live) {
      badge.style.opacity = '0'
      badge.style.pointerEvents = 'none'
      return
    }
    badge.style.opacity = '1'
    badge.style.pointerEvents = 'auto'
    place(badge)
    var text = badge.querySelector('[data-role="text"]')
    text.textContent = (state && state.label ? state.label : '') + (state && state.hits > 0 ? ' ' + state.hits : '')
    badge.title = (state && state.words ? state.words + ' - ' : '') + (state && state.stop ? state.stop : '')
  }
  function bars(live) {
    var badge = document.getElementById(ID)
    if (!badge) return
    var all = badge.querySelectorAll('i')
    for (var i = 0; i < all.length; i++) {
      var height = 3
      if (live) height = 4 + Math.round(9 * (0.5 + 0.5 * Math.sin(phase + i * 1.7)))
      all[i].style.height = height + 'px'
    }
  }
  function on() { return !!(last && last.voice) }
  // 现在在看哪一场: 变了才过一次桥 (那个 id 是 app 那一侧"进视频模式时定格哪一场"的输入,
  // 见 WakeBridge.session), 一秒问一次 localStorage 是本地读, 不花钱
  function tellSession() {
    var id = ''
    try {
      var saved = JSON.parse(localStorage.getItem(CURRENT) || 'null')
      if (saved && saved.sessionId) id = String(saved.sessionId)
    } catch (error) {}
    if (id === lastSession) return
    lastSession = id
    try { window.Luwi.session(id) } catch (error) {}
  }
  function refresh() {
    build()
    var state = null
    try { state = JSON.parse(window.Luwi.state()) } catch (error) {}
    last = state
    tellSession()
    draw(state, on())
    bars(on())
  }
  // 问 app 一秒一次足够 (它那侧要读一次词表与四个文件的大小), 而波形按 220ms 跳 —— 状态缓存在这里,
  // 不是每一帧都过一次桥
  refresh()
  setInterval(function () {
    phase += 0.9
    bars(on())
  }, TICK)
  setInterval(refresh, POLL)
})()
"""

/**
 * What the host is doing while there is no page to show yet
 * @param status the state to describe.
 * @param modifier layout modifier from the caller.
 */
@Composable
private fun HostBootPanel(status: HostStatus, modifier: Modifier = Modifier) {
    val headline = when (status) {
        is HostStatus.Idle -> stringResource(R.string.host_status_idle)
        is HostStatus.Installing -> stringResource(R.string.host_status_installing, status.done, status.total)
        is HostStatus.Starting -> stringResource(R.string.host_status_starting)
        is HostStatus.Failed -> stringResource(R.string.host_status_failed, status.reason)
        is HostStatus.Running -> stringResource(R.string.host_status_running)
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = headline)
            }
        }
        items(DshHost.log) { line ->
            Text(text = line, modifier = Modifier.fillMaxWidth())
        }
    }
}
