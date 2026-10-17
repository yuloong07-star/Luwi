package io.github.yuloong07star.luwi.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.BallReturn
import io.github.yuloong07star.luwi.theme.ApplySystemBarsAppearance
import io.github.yuloong07star.luwi.theme.ThemeStore
import io.github.yuloong07star.luwi.theme.controller
import kotlinx.serialization.Serializable

/** 应用里有哪几页, 可序列化是因为导航要把返回栈存下来 */
@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object Home : Screen

    @Serializable
    data object Settings : Screen

    /** 捐赠那一页: 随包发进来的 `assets/donate.html` (见 [DonateScreen]) */
    @Serializable
    data object Donate : Screen

    /** 设置 → 技能 → 已装的那一页 (2.7.8 批次 5): 检测更新 / 一键升级 / 卸载 */
    @Serializable
    data object SkillsInstalled : Screen

    /** 设置 → 技能 → 可用的那一页: 索引里全部技能, 刷新与全部安装都在那儿 */
    @Serializable
    data object SkillsAvailable : Screen
}

/** 谁想换页就问它, 页面自己不持有返回栈 */
class RootNavigator(
    val push: (Screen) -> Unit,
    val pop: () -> Unit,
)

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator> {
    error("No RootNavigator provided")
}

/**
 * 整个应用: 主题 + 两页之间的导航
 *
 * 外观设置在这里落到 Miuix 上, 所以设置页改完立刻生效, 不需要重启; 导航用的是 miuix-nav,
 * 页面切换的动效与侧滑返回也跟着设置走
 */
@Composable
fun LuwiApp() {
    val settings = ThemeStore.current
    val backStack = rememberNavBackStack<Screen>(Screen.Home)
    val navigator = remember(backStack) {
        RootNavigator(
            push = { backStack.add(it) },
            pop = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        )
    }
    // 「回应用」那一跳 (双击回复框 / 菜单「返回应用」) 落到设置页那一层时: **先回主页** —— 会话界面在
    // 主页上, 不回去的话会话切了也看不见。请求本身由主页那块 WebView 落地 (见 HostScreen), 这里只管
    // 把挡在前面的页收掉
    val ballRequest = BallReturn.request
    LaunchedEffect(ballRequest?.seq) {
        if (ballRequest == null) return@LaunchedEffect
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val controller = remember(
        settings.mode,
        settings.monet,
        settings.seed,
        settings.palette,
        settings.spec,
    ) {
        settings.controller()
    }
    // 2026-10-17 主人: 导航那一段整段撤掉 —— 只用 Miuix 那一套转场, 横滑返回一直开着。
    // 于是这里没有分支可看: 两个值都写死 (存过的旧 pref 不再有人读)
    val swipe = NavSwipeDirection.LeftToRight
    val transition = NavTransitions.MiuixDefault

    // 主页上按返回不等于离开应用: 这一句提示是"再按一次"的第一次, 第二次才把任务放到后台去。
    // 不管任务是去是留, host 与虚拟屏都照跑, 所以这里既不 finish 也不停服务
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    /**
     * 返回键这一下归这里管
     *
     * 第一下弹提示, 第二下把任务放到后台 —— **不是退出**: 服务与虚拟屏照跑, 从最近任务回来还是原样。
     * 提示条挂在主页那层 `SnackbarHost` 上, 所以这里只负责决定"弹"还是"走"
     */
    val onBack = {
        if (ExitHint.press()) {
            activity?.moveTaskToBack(true)
        } else if (ExitHint.showHint()) {
            val message = activity?.getString(R.string.exit_hint_again)
            if (message != null) {
                scope.launch {
                    ExitHint.host.showSnackbar(
                        message,
                        duration = SnackbarDuration.Custom(ExitHint.VISIBLE_MS),
                    )
                }
            }
        }
    }

    MiuixTheme(controller = controller) {
        // 系统栏图标要跟着实际渲染出来的配色, 手动钉成深色时也得跟着变
        ApplySystemBarsAppearance(LocalActivity.current?.window)
        val cornerRadius = rememberNavSystemCornerRadius()
        val backdrop = colorScheme.surface
        val effects = remember(cornerRadius, backdrop) {
            NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = cornerRadius,
                cornerClipMode = NavCornerClipMode.Leading,
                dimAmount = 0.5f,
                blockInputDuringTransition = true,
                backdropColor = backdrop,
            )
        }
        CompositionLocalProvider(
            LocalRootNavigator provides navigator,
            LocalSquircleEnabled provides settings.squircle,
        ) {
            NavDisplay(
                backStack = backStack,
                // 还有上一页就是普通的返回; 只剩主页时那一下交给 onBack 决定
                onBack = { if (backStack.size > 1) navigator.pop() else onBack() },
                transition = transition,
                effects = effects,
            ) {
                entry<Screen.Home>(swipeDismiss = swipe) { HostScreen() }
                entry<Screen.Settings>(swipeDismiss = swipe) { SettingsScreen() }
                entry<Screen.Donate>(swipeDismiss = swipe) { DonateScreen() }
                entry<Screen.SkillsInstalled>(swipeDismiss = swipe) { SkillsInstalledScreen() }
                entry<Screen.SkillsAvailable>(swipeDismiss = swipe) { SkillsAvailableScreen() }
            }
            // **返回键的最后一道**: 没被接住的那一下是平台的默认行为 —— 把这个 activity 关掉, 于是
            // host 与虚拟屏一起没了。所以这里兜底: 要么弹提示, 要么把任务放到后台, 两者都不结束任何
            // 东西
            //
            // **它必须自己先让返回栈弹一层** (2026-10-08 修): `BackHandler` 是**后登记的先赢** (LIFO),
            // 而这一行排在 `NavDisplay` 后面, 于是库里那个"还有上一页就 pop"的 handler 永远轮不到 ——
            // 现象就是**系统返回键在设置页与捐赠页上什么都不做** (只能点左上角那个 ←, 或者开着"横滑
            // 返回"时右滑)。原来那句注释以为这里登记得比库早, 那是错的
            BackHandler {
                if (backStack.size > 1) navigator.pop() else onBack()
            }
        }
    }
}
