package io.github.yuloong07star.luwi.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.automation.AutomationEngine
import io.github.yuloong07star.luwi.automation.AutomationRule
import io.github.yuloong07star.luwi.automation.AutomationStore
import io.github.yuloong07star.luwi.channel.AccessibilitySetting
import io.github.yuloong07star.luwi.channel.CameraOwner
import io.github.yuloong07star.luwi.channel.ChannelSetting
import io.github.yuloong07star.luwi.channel.LwOcr
import io.github.yuloong07star.luwi.channel.NotificationSetting
import io.github.yuloong07star.luwi.channel.RemoteBackend
import io.github.yuloong07star.luwi.channel.RouteState
import io.github.yuloong07star.luwi.channel.ScreenshotBudget
import io.github.yuloong07star.luwi.lock.LockRecord
import io.github.yuloong07star.luwi.lock.LockReplay
import io.github.yuloong07star.luwi.lock.LockScript
import io.github.yuloong07star.luwi.lock.LockSecret
import io.github.yuloong07star.luwi.lock.LockSecretData
import io.github.yuloong07star.luwi.lock.LockSetting
import io.github.yuloong07star.luwi.tool.LwCamera
import io.github.yuloong07star.luwi.tool.LwAutomation
import io.github.yuloong07star.luwi.tool.LwLinux
import io.github.yuloong07star.luwi.tool.LwModel
import io.github.yuloong07star.luwi.tool.LwQuick
import io.github.yuloong07star.luwi.tool.VideoLooks
import io.github.yuloong07star.luwi.tool.bytes
import io.github.yuloong07star.luwi.voice.VoiceInbox
import io.github.yuloong07star.luwi.constants.UiSpacing
import io.github.yuloong07star.luwi.host.AppSkills
import io.github.yuloong07star.luwi.host.DshHost
import io.github.yuloong07star.luwi.host.HostStatus
import io.github.yuloong07star.luwi.plugin.CompanionApk
import io.github.yuloong07star.luwi.plugin.PluginDownload
import io.github.yuloong07star.luwi.plugin.PluginIndex
import io.github.yuloong07star.luwi.overlay.BallGeometry
import io.github.yuloong07star.luwi.overlay.BallSize
import io.github.yuloong07star.luwi.overlay.BallFeel
import io.github.yuloong07star.luwi.overlay.BallSpot
import io.github.yuloong07star.luwi.overlay.BallSwitch
import io.github.yuloong07star.luwi.overlay.OverlayState
import io.github.yuloong07star.luwi.plugin.PluginAudit
import io.github.yuloong07star.luwi.plugin.PluginCapabilities
import io.github.yuloong07star.luwi.plugin.PluginCompanion
import io.github.yuloong07star.luwi.plugin.PluginInstaller
import io.github.yuloong07star.luwi.plugin.PluginManager
import io.github.yuloong07star.luwi.plugin.PluginManifest
import io.github.yuloong07star.luwi.plugin.PluginStore
import io.github.yuloong07star.luwi.plugin.PluginSignature
import io.github.yuloong07star.luwi.plugin.PluginTrust
import io.github.yuloong07star.luwi.plugin.widget.WidgetHost
import io.github.yuloong07star.luwi.plugin.widget.WidgetPin
import io.github.yuloong07star.luwi.plugin.widget.WidgetSlot
import io.github.yuloong07star.luwi.plugin.widget.WidgetSlots
import io.github.yuloong07star.luwi.plugin.theme.BallTheme
import io.github.yuloong07star.luwi.scaffolds.ArrowSlider
import io.github.yuloong07star.luwi.scaffolds.GroupTitle
import io.github.yuloong07star.luwi.scaffolds.LazyColumn
import io.github.yuloong07star.luwi.scaffolds.SectionSmallTitle
import io.github.yuloong07star.luwi.scaffolds.SectionTitle
import io.github.yuloong07star.luwi.scaffolds.SuperTextField
import io.github.yuloong07star.luwi.sync.ModeModel
import io.github.yuloong07star.luwi.sync.SyncSetting
import io.github.yuloong07star.luwi.theme.MonetKeyColorOptions
import io.github.yuloong07star.luwi.theme.ThemeSettings
import io.github.yuloong07star.luwi.theme.ThemeStore
import io.github.yuloong07star.luwi.tool.LwEdgeSpeech
import io.github.yuloong07star.luwi.tool.LwOverlay
import io.github.yuloong07star.luwi.tool.LwPlugin
import io.github.yuloong07star.luwi.tool.LwPower
import io.github.yuloong07star.luwi.tool.LwSpeak
import io.github.yuloong07star.luwi.tool.LwTts
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.tool.SpeakSettings
import io.github.yuloong07star.luwi.update.AboutSettings
import io.github.yuloong07star.luwi.update.UpdateCheck
import io.github.yuloong07star.luwi.util.Grant
import io.github.yuloong07star.luwi.util.PermissionCatalog
import io.github.yuloong07star.luwi.util.PermissionGate
import io.github.yuloong07star.luwi.util.PermissionRequests
import io.github.yuloong07star.luwi.wake.WakeWordDownload
import io.github.yuloong07star.luwi.wake.PowerWindow
import io.github.yuloong07star.luwi.wake.WakeWordState
import io.github.yuloong07star.luwi.wake.WakeWordWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 设置页的字符串都在 `strings.xml` 里 (en 与 zh 各一份), 名字以 `settings_` 开头, 措辞照 SFA 的
 * `values-zh/strings.xml` - 名字对齐了才不用记两套说法
 *
 * 这一页原来硬编码中文 (2026-09-21 定的"不做多语言"), 2026-09-22 改成跟着资源走
 */

/** 外观模式, 与 `ThemeSettings.mode` 的下标一一对应 (SFA 的 theme_follow_system / light / dark) */
private val themeModes = listOf(
    R.string.settings_theme_follow_system,
    R.string.settings_theme_light,
    R.string.settings_theme_dark,
)

/**
 * 过渡风格, 与 `ThemeSettings.transition` 的下标一一对应
 *
 * 就是 SFA 的 `pref_transition_style_miuix` 与 `pref_transition_style_aosp` 两项, 没有 "无": 页面
 * 切换要么是 Miuix 那种整页平移, 要么是平台那种缩小让位
 */
private val transitionStyles = listOf(
    R.string.settings_transition_miuix,
    R.string.settings_transition_aosp,
)

/**
 * 浮标手势灵敏度那两项 (见 [BallFeel]): **顺序就是下拉里从上到下那两行**
 *
 * 防误触排第一: 它是缺省那一档, 于是"点开下拉最上面那个就是现在生效的"这件事在多数人那里一眼可见
 */
private val ballFeels = listOf(BallFeel.GUARD, BallFeel.STANDARD)

/** 那一档的名字 */
private fun feelLabel(feel: BallFeel): Int = when (feel) {
    BallFeel.STANDARD -> R.string.settings_ball_feel_standard
    BallFeel.GUARD -> R.string.settings_ball_feel_guard
}

/** 调色板风格与色彩规范直接取 Miuix 的枚举名, 它们是 Google 那边的术语 */
private val paletteStyles = ThemePaletteStyle.entries.map { it.name }
private val colorSpecs = ThemeColorSpec.entries.map { it.name }

/**
 * 收起来的那几段怎么存 (见 [SettingsScreen] 里的 `collapsed`)
 *
 * 直接 `rememberSaveable` 一个 `Set<String>` 不行 (它不是可保存类型), 于是过一趟 List —— `listSaver`
 * 就是给这种"几步就能还原"的形状准备的。**它不是偏好**: 收起来的那几段是"这一眼怎么看", 与这台
 * 设备的配置无关, 所以它只活在这一次界面里 (转屏与进程被回收时由 saved instance state 兜住)
 */
private val CollapsedSections = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * 缺省展开的白名单 (D1, 2026-10-17 主人拍板): 省电那一段 + 「常用」一级分组下那六段
 *
 * 省电为什么也算: 它排在页面上第一个、只有三行, 是"现在别听我说话"那一下子, 属于天天要碰的
 * (主人 2026-10-08: "省电模式单独拿出来放第一排")
 */
internal val SettingsSectionsAlwaysOpenKeys = listOf(
    "power", "appearance", "navigation", "channel", "ball", "wake", "speak",
)

/** 白名单的集合形态, 给 [defaultCollapsed] 算差集用 */
internal val SettingsSectionsAlwaysOpen: Set<String> = SettingsSectionsAlwaysOpenKeys.toSet()

/** 「能力」那一组的 13 段, 缺省全收着 (装完之后偶尔来调一次的那些) */
private val AbilitySectionKeys = listOf(
    "accessibility", "notifications", "screenshot", "look", "ocr", "lock",
    "automation", "quick", "plugin", "skills", "linux", "sync", "permissions",
)

/**
 * 页面上那 20 段的段名, **顺序就是从上到下**
 *
 * 它只为一件事存在: 算"缺省收起来的那几段" ([defaultCollapsed])。**新加一段时必须同时加到这里** ——
 * 漏掉它不会报错, 只是那一段会恰好展开着 (与 D2 那条"新加的段就收着"相反), 界面上一点异常都看不出来,
 * 所以 `tools/check-settings-sections.mjs` 把这一份与下面那些 `settingsSection(key = …)` 逐个对齐 (顺序也管)
 */
internal val SettingsSectionKeys: List<String> = SettingsSectionsAlwaysOpenKeys + AbilitySectionKeys

/**
 * 缺省收起来的那几段 = 页面上所有段 - 白名单
 *
 * **D2**: 按"不在白名单里就收着"算, 于是以后新加的一段天然是收起来的。2026-10-08 那条注释写的是
 * 反过来 ("存的是收起来的那几段, 于是新加一段时它自己就是展开的") —— 2026-10-16 第 1 条把这条账翻了个面,
 * 那位主人要的就是"进设置页先看见常用那几段"
 */
internal fun defaultCollapsed(
    all: List<String>,
    expanded: Set<String> = SettingsSectionsAlwaysOpen,
): Set<String> = (all - expanded).toSet()

/**
 * 这一次应用里主人手动调过的那一份 (退出设置页再进来要接着用), **进程死了回缺省**
 *
 * `rememberSaveable` 只活一次界面: 转屏兜得住, 但**退出设置页再进来**是一次新建的组合, 它手上没有
 * 上一次那份 —— 2026-10-17 在模拟器上量到的就是这个 (手动点开「通知」, 返回主页再进设置页, 它又收起
 * 来了), 而第 1 条的判据要的是"手动点开一段再退出再进, 那一段仍是开的"。所以记一份在进程里:
 * 它仍然**不是偏好** (不落盘, 冷启动就是缺省), 只是"这一眼怎么看"在这一次应用里记着
 */
private var sessionCollapsed: Set<String>? = null

/**
 * 进页面时该用那一份 `collapsed`: 主人这一次调过就照他调的, 没调过就是缺省那一份
 *
 * 抽成纯函数是为了那条"退出再进"的判据能在单测里量 ([SettingsSectionsTest]),
 * 不必真的去点界面
 */
internal fun initialCollapsed(
    session: Set<String>?,
    all: List<String> = SettingsSectionKeys,
): Set<String> = session ?: defaultCollapsed(all)

/**
 * 页面上的一段: 可收起的标题 + 展开时那一坨内容
 *
 * **标题那两句话在这一层取**: [LazyListScope] 的 lambda 不是组合上下文 (它画的是"有哪些 item"),
 * 所以 `stringResource` 只能在 item 里面调 —— 传进来的是资源 id 而不是取好的字符串
 *
 * 内容整块包在 [AnimatedVisibility] 里, 而不是只把 `Card` 藏起来: 段里除了卡片还有 TabRow 与提示
 * (见外观那一段), 那些也要跟着一起收, 否则收起来之后页面上会留下半截东西
 */
private fun LazyListScope.settingsSection(
    key: String,
    title: Int,
    summary: Int,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    item {
        val closed = key in collapsed
        SectionTitle(
            title = stringResource(title),
            summary = stringResource(summary),
            collapsed = closed,
            onToggle = { onToggle(key) },
        )
        AnimatedVisibility(!closed) { content() }
    }
}

/**
 * 设置页
 *
 * 它自己是滚动的, 顶栏跟着滚; 缩进与段间距都由 [LazyColumn] 那个脚手架统一给, 一段一个 item,
 * Card 自己不写外边距 - 这是照 SFA 的排法, 免得每段各缩各的
 *
 * 外观那几项改完立刻作用到整个应用, 因为主题是 [LuwiTheme] 外面那一层在管; 工作区与网络从
 * 原来的两个面板搬到这里, 平铺成两段, 它们要重启 host 才生效
 */
@Composable
fun SettingsScreen() {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val settings = ThemeStore.current
    val scrollBehavior = MiuixScrollBehavior()
    // 右上角那个刷新 (2026-10-17 主人): 这一页里那些"进页面读一次"的设置都活在下面那棵子树的
    // remember 里 (同步 / 视频模式模型 / 模型目录 / 各个偏好), 加一下这个数就把它们整棵重建一遍 ——
    // 每个 remember 重新去读文件, 每个 LaunchedEffect(Unit) 重新跑一次。收起来的那几段是
    // rememberSaveable, 刷新不动它 (那记的是"看哪儿", 不是数据)
    var reload by remember { mutableIntStateOf(0) }
    // ⋮ 里那三件事 (检测更新 / 捐赠 / 关于) 共用这一个"现在开着哪一页"的记号, null = 都没开
    // 同一刻只画一个对话框, 见 AboutDialogs 的文件头
    var about by remember { mutableStateOf<AboutPage?>(null) }
    // 捐赠页那一行要跟着"刚填过的那个地址"变, 而它在偏好里 —— 对话框关掉时重新读一次
    // 收起来的那几段 (主人 2026-10-08: "把各设置版块设置为可折叠的"; 2026-10-16 第 1 条: 进页面只展开
    // 省电与「常用」那六段, 能力那 13 段收着): **缺省只展开白名单那七段** ([SettingsSectionsAlwaysOpen]),
    // 存的是"收起来的那几段"而不是"展开的那几段", 于是以后新加一段时它天然是收着的 (D2) —— 与 2026-10-08
    // 那版相反, 那时缺省全展开, 新加的一段自然是展开的。主人手动点过的那一段 (开或收) 都记在这一份里:
    // 转屏由 `rememberSaveable` 兜, **退出设置页再进来**由那份进程里的记忆兜 (见 [sessionCollapsed])
    var collapsed by rememberSaveable(stateSaver = CollapsedSections) {
        mutableStateOf(initialCollapsed(sessionCollapsed))
    }
    val toggleSection: (String) -> Unit = { key ->
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        sessionCollapsed = collapsed
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigator.pop) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                // 这一页里"不用常驻"的事全收在这里: 检测更新 / 捐赠 / 关于 / 要重启 host 才生效的改动
                // 页面上就不必每个面板各放一个按钮 (2026-10-08 加了前三条, 见开发计划 2.4 与 2.14)
                // **G1-D 那几行「打开 <对端名>」2026-10-17 撤掉了** (主人: "没用就删掉"): 它载入的
                // 是对端名片里手填的 publicUrl, 而电脑侧填的是回环地址 —— 手机上 127.0.0.1 指的是
                // 自己 (实测那台真机只在 127.0.0.1:3080 上听本机 host, 也没有 adb reverse 隧道),
                // 点开必白页; 真要看/操作电脑那份 GUI, 走浏览器与「（PC）」影子工作区那两条路
                // (插件侧名片与 `/sync peers` 不受影响, 那两样是 G1-D 的读数面)
                actions = {
                    // 「更多」左边那一个刷新 (2026-10-17 主人): 把这一页的设置当场重读一遍, 不必退出去
                    // 再进来 —— 改完**要重启 host** 的那几项仍然要重启 (那是另一件事, 仍在右边那个 ⋮ 里)
                    IconButton(onClick = {
                        reload += 1
                        Toast.makeText(context, R.string.settings_refreshed, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.settings_refresh),
                        )
                    }
                    OverlayIconDropdownMenu(
                        entry = DropdownEntry(
                            items = listOf(
                                DropdownItem(
                                    text = stringResource(R.string.settings_menu_update),
                                    summary = stringResource(R.string.settings_about_update_summary),
                                    onClick = {
                                        UpdateCheck.reset()
                                        about = AboutPage.UPDATE
                                    },
                                ),
                                DropdownItem(
                                    text = stringResource(R.string.settings_menu_donate),
                                    summary = stringResource(R.string.settings_menu_donate_summary),
                                    // 直接开随包那一页: **没有可填的地址**, 那一页是作者自己的捐赠项目
                                    // (主人 2026-10-08), 别人不该有机会把它指到别处
                                    onClick = { navigator.push(Screen.Donate) },
                                ),
                                DropdownItem(
                                    text = stringResource(R.string.settings_menu_about),
                                    summary = stringResource(
                                        R.string.settings_about_version,
                                        UpdateCheck.localVersionOf(context),
                                        UpdateCheck.localCodeOf(context),
                                    ),
                                    onClick = { about = AboutPage.ABOUT },
                                ),
                                DropdownItem(
                                    text = stringResource(R.string.settings_restart_host),
                                    summary = stringResource(R.string.settings_restart_host_summary),
                                    onClick = { DshHost.restart(context) },
                                ),
                            ),
                        ),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.settings_more),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        key(reload) {
            LazyColumn(
                contentPadding = innerPadding,
                scrollBehavior = scrollBehavior,
            ) {
                // **省电那一段单独排在第一个** (主人 2026-10-08: "省电模式单独拿出来放第一排"): 它原本是
                // 「唤醒词」里的三行, 而"现在别听我说话"这件事与唤醒词本身是两笔账 —— 一个是许可, 一个是
                // 此刻的开关 (见 [PowerItems])。它自己一段之后, 要静音一次不必先展开唤醒词那一段
                settingsSection(
                    key = "power",
                    title = R.string.settings_section_power,
                    summary = R.string.settings_section_power_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { PowerItems() }
                }

                // 两层分组的第一层 (2026-10-08 主人: "优化设置页排版"): 上面这一组是"天天要碰的",
                // 下面那一组是"装完之后偶尔来调一次的能力", 而权限那一段仍然单独放在最后
                item { GroupTitle(stringResource(R.string.settings_group_common)) }

                settingsSection(
                    key = "appearance",
                    title = R.string.settings_section_appearance,
                    summary = R.string.settings_section_appearance_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Column {
                        TabRow(
                            tabs = themeModes.map { stringResource(it) },
                            selectedTabIndex = settings.mode.coerceIn(0, themeModes.lastIndex),
                            onTabSelected = { ThemeStore.update(settings.copy(mode = it)) },
                        )
                        Spacer(modifier = Modifier.height(UiSpacing.ContentVertical))
                        Card {
                            SwitchPreference(
                                title = stringResource(R.string.settings_monet),
                                summary = stringResource(R.string.settings_monet_summary),
                                checked = settings.monet,
                                onCheckedChange = { ThemeStore.update(settings.copy(monet = it)) },
                            )
                            AnimatedVisibility(settings.monet) {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.settings_monet_key_color),
                                    summary = stringResource(R.string.settings_monet_key_color_summary),
                                    items = MonetKeyColorOptions,
                                    selectedIndex = settings.seed.coerceIn(0, MonetKeyColorOptions.lastIndex),
                                    onSelectedIndexChange = { ThemeStore.update(settings.copy(seed = it)) },
                                )
                            }
                            AnimatedVisibility(settings.monet && settings.seed > 0) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.settings_monet_palette_style),
                                        summary = stringResource(R.string.settings_monet_palette_style_summary),
                                        items = paletteStyles,
                                        selectedIndex = settings.palette.coerceIn(0, paletteStyles.lastIndex),
                                        onSelectedIndexChange = { ThemeStore.update(settings.copy(palette = it)) },
                                    )
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.settings_monet_color_spec),
                                        summary = stringResource(R.string.settings_monet_color_spec_summary),
                                        items = colorSpecs,
                                        selectedIndex = settings.spec.coerceIn(0, colorSpecs.lastIndex),
                                        onSelectedIndexChange = { ThemeStore.update(settings.copy(spec = it)) },
                                    )
                                }
                            }
                            SwitchPreference(
                                title = stringResource(R.string.settings_squircle),
                                summary = stringResource(R.string.settings_squircle_summary),
                                checked = settings.squircle,
                                onCheckedChange = { ThemeStore.update(settings.copy(squircle = it)) },
                            )
                        }
                    }
                }

                settingsSection(
                    key = "navigation",
                    title = R.string.settings_section_navigation,
                    summary = R.string.settings_section_navigation_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_transition),
                            summary = stringResource(R.string.settings_transition_summary),
                            items = transitionStyles.map { stringResource(it) },
                            selectedIndex = settings.transition.coerceIn(0, transitionStyles.lastIndex),
                            onSelectedIndexChange = { ThemeStore.update(settings.copy(transition = it)) },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.settings_swipe_back),
                            summary = stringResource(R.string.settings_swipe_back_summary),
                            checked = settings.swipeBack,
                            onCheckedChange = { ThemeStore.update(settings.copy(swipeBack = it)) },
                        )
                    }
                }

                settingsSection(
                    key = "channel",
                    title = R.string.settings_section_channel,
                    summary = R.string.settings_section_channel_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { ChannelItems() }
                }

                // 浮标 / 唤醒词 / 说话 三段的顺序 (2026-10-08 一起排的): 先"球在不在", 再"喊一声", 最后
                // "回答怎么念" —— 这正是第一次用的时候会依次碰到的三件事
                settingsSection(
                    key = "ball",
                    title = R.string.settings_section_ball,
                    summary = R.string.settings_section_ball_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { BallItems() }
                }

                settingsSection(
                    key = "wake",
                    title = R.string.settings_section_wake,
                    summary = R.string.settings_section_wake_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { WakeItems() }
                }

                settingsSection(
                    key = "speak",
                    title = R.string.settings_section_speak,
                    summary = R.string.settings_section_speak_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { SpeakItems() }
                }

                // 「模式与模型」那一段 (3.0.2 需求 2) 已并进「视频模式」那一段 (2026-10-16): 视频模式用
                // 哪一个模型、走哪个端点, 与那台相机的参数是同一件事的两个面, 分两段反而要人两头找

                item { GroupTitle(stringResource(R.string.settings_group_ability)) }

                settingsSection(
                    key = "accessibility",
                    title = R.string.settings_section_accessibility,
                    summary = R.string.settings_section_accessibility_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { AccessibilityItems() }
                }

                settingsSection(
                    key = "notifications",
                    title = R.string.settings_section_notifications,
                    summary = R.string.settings_section_notifications_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { NotificationItems() }
                }

                settingsSection(
                    key = "screenshot",
                    title = R.string.settings_section_screenshot,
                    summary = R.string.settings_section_screenshot_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { ScreenshotItems() }
                }

                settingsSection(
                    key = "look",
                    title = R.string.settings_section_look,
                    summary = R.string.settings_section_look_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    // 视频模式那几件事都住这一段 (2026-10-16 主人: "视频模式选择的 llm 的 url, api 等放
                    // 到 luwi 的视频模式设置里"): **用哪一个模型说话**在最上面 (它原来是单独一段
                    // 「模式与模型」, 归并过来是因为"视频模式用哪个模型"就是这一段的事), 后面才是那台
                    // 相机自己的参数 (抓几张 / 隔多久 / 多大 / 要不要拼网格)
                    Card {
                        Column {
                            ModeItems()
                            LookItems()
                        }
                    }
                }

                settingsSection(
                    key = "ocr",
                    title = R.string.settings_section_ocr,
                    summary = R.string.settings_section_ocr_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { OcrItems() }
                }

                // 锁屏这一段是批次 5 的落点 (点亮屏幕 + 按主人自己录的那一条解锁); 自动指令那一段仍是
                // 批次 8 留下的占位 —— "先有那一段"比"功能做完了再多插一段"更不容易漏
                settingsSection(
                    key = "lock",
                    title = R.string.settings_section_lock,
                    summary = R.string.settings_section_lock_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { LockItems() }
                }

                settingsSection(
                    key = "automation",
                    title = R.string.settings_section_automation,
                    summary = R.string.settings_section_automation_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { AutomationItems() }
                }

                // 快捷指令那一段 (批次 7): 与「自动指令」挨着, 因为两段都是"主人自己定的那几件事",
                // 而这一段先做 (自动指令是批次 8)
                settingsSection(
                    key = "quick",
                    title = R.string.settings_section_quick,
                    summary = R.string.settings_section_quick_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { QuickItems() }
                }

                // 插件那一段 (2.5.1 批次 9 的 P0 / P1): 这一段是生态的入口 —— 装 / 启用 / 停用 / 卸 /
                // 勾能力 / 看审计都在这里, 而"能力勾了才给"这一条只有这一页能答应
                settingsSection(
                    key = "plugin",
                    title = R.string.settings_section_plugin,
                    summary = R.string.settings_section_plugin_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { PluginItems() }
                }

                // 技能那一段 (2.7.0): 与插件挨着 —— 两段都是"给这台机器加东西", 区别是技能只加提示词与
                // 作业流程, 插件才碰得到能力。这一段的活儿只有一件: 按已安装的应用把对应技能装进 dsh
                settingsSection(
                    key = "skills",
                    title = R.string.settings_section_skills,
                    summary = R.string.settings_section_skills_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { SkillItems() }
                }

                // Linux 沙盒那一段 (2.7.7): 也是"给这台机器加东西", 加的是一个能装包能编译的用户空间。
                // 活儿有三件: 看状态与占用 / 装入 (或重装) / 删掉它 —— 第三件是这一页上唯二不可逆的动作
                // (另一件是删技能), 所以走的也是"点一下先问一句"那条路
                settingsSection(
                    key = "linux",
                    title = R.string.settings_section_linux,
                    summary = R.string.settings_section_linux_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { LinuxItems() }
                }

                // Luwi-dsh-sync 那一段 (G1-2): 两台机器怎么连上这件事全在这里 —— 会话仓库 / 工作区仓库 /
                // 设备名 / 本机地址 / 对端地址与连接口令。写的是 `$DSH_HOME/lw/session-sync.json`,
                // 改完要重启 host, 所以段首把那句话摆在人眼前 (重启那一条在右上角 ⋮ 里)
                settingsSection(
                    key = "sync",
                    title = R.string.settings_section_sync,
                    summary = R.string.settings_section_sync_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { SyncItems() }
                }

                // 工作区与网络这两段已经从这里撤掉 (主人 2026-10-06): 工作区落在哪是 host 启动时按
                // Workspace.resolve 那三档自己挑的, 网络那个开关改完也要重启 host —— 两件事都是"平时
                // 不用动"的, 留在这里只把下面那些常用的段推得更远。**能力一个都没删**: 三档解析、
                // Workspace.requestAllFilesAccess、HostSettings.lanAccess 与上面 ⋮ 里那条重启 host 照旧,
                // 只是不再有这两个设置入口 (要改是 `tools/lw-install.ps1` 与重启那一步的事)

                // 权限这一段放最后: 它是这一页最长的一段 (十七个能力), 放中间会把后面每一段都推到很远,
                // 而这些权限本来就是装完之后偶尔来调一次的东西
                settingsSection(
                    key = "permissions",
                    title = R.string.settings_section_permissions,
                    summary = R.string.settings_section_permissions_summary,
                    collapsed = collapsed,
                    onToggle = toggleSection,
                ) {
                    Card { PermissionsItems() }
                }
            }
        }

        // ⋮ 里那三件事的对话框: 同一刻只画一个, 由那一个 [about] 记号决定是哪一页 (见 AboutDialogs)
        when (about) {
            AboutPage.ABOUT -> AboutDialog(
                onDismissRequest = { about = null },
                onOpen = { about = it },
                // 「关于」里那一行捐赠也是同一个去处: 先把对话框收掉, 再把随包那一页推出来
                onDonate = {
                    about = null
                    navigator.push(Screen.Donate)
                },
            )

            AboutPage.UPDATE -> UpdateDialog(onDismissRequest = { about = null })
            AboutPage.SOURCE -> SourceDialog(onDismissRequest = { about = null })

            null -> Unit
        }
    }
}

/**
 * 特权通道: 动屏幕的能力都要先有它, 而它要么经 root 要么经 Shizuku
 *
 * 连接本身**不在这里发起**: 应用启动时 `DshHostService` 就在后台连了一次 (连一次要起一个
 * app_process, 是秒级的, 不该落在模型第一次截图上), 所以这一页通常进来就是"已连接"。按钮只在
 * 没连上时出现, 它是**重试** —— 真正的用处是"刚在 KernelSU 里给完授权, 不想重启应用"
 *
 * 只列状态, 不解释: 两条路各自在哪一档一眼能看完。**root 那行可能是"未检测"**, 这不是偷懒 ——
 * 有没有 root 在应用侧查不到 (KernelSU 对没在名单上的应用把 `su` 整个收走, 而不是拿出来问一句),
 * 所以"没授权"与"还没试过"在试之前是同一件事
 */
@Composable
private fun ChannelItems() {
    val state = ChannelSetting.state
    val routes = ChannelSetting.routes
    val working = ChannelSetting.working
    LaunchedEffect(Unit) { ChannelSetting.refresh() }
    // fillMaxWidth 是必要的: 这一段的子项全是纯文字, 撑不满宽度, 而 Miuix 的 Card 是包着内容的 ——
    // 不填满, 卡片就比这一页别的卡片窄一截 (设置项自己会撑开, 所以别处不用管)
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                state == null -> stringResource(R.string.settings_channel_reading)
                state.connected -> stringResource(
                    R.string.settings_channel_connected,
                    state.backend.orEmpty(),
                    state.uid ?: -1,
                )

                else -> stringResource(
                    R.string.settings_channel_disconnected,
                    state.error.orEmpty(),
                )
            },
        )
        routes.forEach { route ->
            Text(
                text = stringResource(
                    R.string.settings_channel_route,
                    route.backend.label,
                    stringResource(routeState(route)),
                ),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        if (state?.connected != true) {
            Button(
                onClick = { ChannelSetting.connect() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().padding(top = UiSpacing.Medium),
            ) {
                Text(
                    text = stringResource(
                        if (working) R.string.settings_channel_connecting else R.string.settings_channel_connect,
                    ),
                )
            }
        }
    }
}

/** 一条路在哪一档, 只有 root 会有"未检测": 别的那两个问题不用试就能答 */
private fun routeState(route: RouteState): Int = when (route.backend) {
    RemoteBackend.ROOT -> when (route.granted) {
        true -> R.string.settings_channel_state_allowed
        false -> R.string.settings_channel_state_denied
        null -> R.string.settings_channel_state_unknown
    }

    RemoteBackend.SHIZUKU -> when {
        !route.available -> R.string.settings_channel_state_stopped
        route.granted == true -> R.string.settings_channel_state_allowed
        else -> R.string.settings_channel_state_denied
    }
}

/**
 * 「锁屏」那一段 (批次 5): 叫醒时点亮屏幕, 以及按主人自己录的那一条解锁
 *
 * 三件事在这一段里一眼看完: **两条开关** / **录了什么** / **上一次重放成不成**。最后那一条最要紧 ——
 * 解锁失败不弹任何东西, 只留一行通知, 所以页面上也得看得见
 *
 * [LockSetting.revision] 是刷新信号: 拨开关、录完、清掉都会让它 +1, 它当 remember 的键用, 于是改完
 * 当场重画
 */
@Composable
private fun LockItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val snapshot = remember(LockSetting.revision) {
        val (secret, problem) = LockSecret.load(context)
        LockSummary(
            wakeScreen = LockSetting.wakeScreen(context),
            autoUnlock = LockSetting.autoUnlock(context),
            injectUnlock = LockSetting.injectUnlock(context),
            steps = LockSetting.steps(context).size,
            recordedAt = LockSetting.recordedAt(context),
            secretKind = secret?.kind,
            secretLength = secret?.let { if (it.kind == LockSecretData.PATH) it.points.size else it.text.length }
                ?: 0,
            problem = problem,
        )
    }
    val recording = LockRecord.recording
    // 密码格现在是什么样的: 这一句同时给「录制解锁」与「注入密码」两行用, 所以只说"存了没有、多少位"
    val secretLabel = when {
        snapshot.secretKind == null -> stringResource(R.string.settings_lock_secret_none)
        snapshot.secretKind == LockSecretData.PATH ->
            stringResource(R.string.settings_lock_secret_pattern, snapshot.secretLength)

        else -> stringResource(R.string.settings_lock_secret_password, snapshot.secretLength)
    }

    SwitchPreference(
        title = stringResource(R.string.settings_lock_wake_screen),
        summary = stringResource(R.string.settings_lock_wake_screen_summary),
        checked = snapshot.wakeScreen,
        onCheckedChange = { LockSetting.setWakeScreen(context, it) },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_lock_auto_unlock),
        summary = stringResource(R.string.settings_lock_auto_unlock_summary),
        checked = snapshot.autoUnlock,
        // 没录过就不许打开: 打开了只会"喊了没反应", 那是这一批最难查的一档
        onCheckedChange = { on -> message = LockSetting.setAutoUnlock(context, on) },
    )
    // 注入解锁: 开着时解锁只做"注入密码再回车", 不走录制的那条序列, 所以快得多
    SwitchPreference(
        title = stringResource(R.string.settings_lock_inject_unlock),
        summary = stringResource(R.string.settings_lock_inject_unlock_summary),
        checked = snapshot.injectUnlock,
        // 图案锁与"还没存过密码"这两档开不起来, 理由由 LockSetting 那句话给
        onCheckedChange = { on -> message = LockSetting.setInjectUnlock(context, on) },
    )
    // 注入密码: 不录手势也能有一段可注入的密码 —— 落在 LockSecret 那唯一一处加密的存法里
    ArrowPreference(
        title = stringResource(R.string.settings_lock_inject_password),
        summary = if (snapshot.secretKind == LockSecretData.TEXT && snapshot.secretLength > 0) {
            stringResource(R.string.settings_lock_inject_password_set, snapshot.secretLength)
        } else {
            stringResource(R.string.settings_lock_inject_password_none)
        },
        onClick = { password = true },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_lock_record),
        summary = when {
            recording -> stringResource(R.string.settings_lock_record_running, LockRecord.collected)
            snapshot.steps > 0 -> stringResource(
                // 注入开着时这一行顺带说清"它一直留着, 是退路" (主人 2026-10-09 点名的口径)
                if (snapshot.injectUnlock) {
                    R.string.settings_lock_record_done_inject
                } else {
                    R.string.settings_lock_record_done
                },
                snapshot.steps,
                LockSetting.recordedSentence(snapshot.recordedAt),
                secretLabel,
            )

            else -> stringResource(R.string.settings_lock_record_none)
        },
        onClick = { dialog = true },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_lock_replay),
        summary = if (testing) {
            stringResource(R.string.settings_lock_replay_running)
        } else {
            stringResource(R.string.settings_lock_replay_summary)
        },
        onClick = {
            if (!testing) {
                testing = true
                message = null
                scope.launch {
                    // **先锁上再测**: 手机正开着锁的时候, 这一趟一步都走不到, 那个"成功"是假的
                    // (锁屏、亮屏、重放三件都不该落在主线程上, 所以整段在 IO 线程里等结果)
                    val report = withContext(Dispatchers.IO) {
                        runCatching {
                            LwPower.dispatch(context, buildJsonObject { put("op", "lock") })
                            Thread.sleep(LOCK_BEFORE_TEST_MS)
                            LockReplay.wakeScreen(context, allowConnecting = true)
                            LockReplay.replayNow(context, "the settings page")
                        }.getOrNull()
                    }
                    testing = false
                    message = report?.detail
                }
            }
        },
    )
    // 批次 5 追加: 不想走一遍 (或者录完之后想改两处) 就用一份脚本替掉现在这一条
    ArrowPreference(
        title = stringResource(R.string.settings_lock_script),
        summary = if (snapshot.steps > 0) {
            stringResource(R.string.settings_lock_script_done, snapshot.steps)
        } else {
            stringResource(R.string.settings_lock_script_none)
        },
        onClick = { script = true },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_lock_clear),
        summary = stringResource(R.string.settings_lock_clear_summary),
        onClick = {
            LockSetting.clearSteps(context)
            LockReplay.forgetWarnings()
            message = context.getString(R.string.settings_lock_cleared)
        },
    )

    // 最后一行: 上一次重放的结果 (或者刚拨开关时说的一句), 没有就写"还没试过"
    PlaceholderItems(
        message
            ?: LockReplay.lastReport
            ?: LockRecord.lastResult
            ?: stringResource(R.string.settings_lock_note_none),
    )
    if (snapshot.problem != null) PlaceholderItems(snapshot.problem)

    if (dialog) {
        LockRecordDialog(
            onDismissRequest = { dialog = false },
            onStart = { password ->
                dialog = false
                message = null
                scope.launch {
                    // 起录要先连特权通道、再把屏锁上, 两件都不该落在主线程上
                    val problem = withContext(Dispatchers.IO) {
                        runCatching { LockRecord.start(context, password) }.getOrNull()
                    }
                    message = problem
                        ?: context.getString(R.string.settings_lock_record_running, 0)
                }
            },
        )
    }
    if (password) {
        LockPasswordDialog(
            onDismissRequest = { password = false },
            onSave = { text ->
                password = false
                message = if (text.isEmpty()) {
                    // 留空保存 = 清除那一格 (密文与那把 Keystore 密钥一起删)
                    LockSetting.setPassword(context, "")
                    context.getString(R.string.settings_lock_inject_password_cleared)
                } else {
                    LockSetting.setPassword(context, text)
                        ?: context.getString(R.string.settings_lock_inject_password_saved)
                }
            },
        )
    }
    if (script) {
        // 草稿 = 现在这一条 (有就编辑它, 没有就给模板): 主人一眼看得到"现在是什么样"
        val draft = remember(script) {
            val current = LockSetting.steps(context)
            if (current.isEmpty()) LockScript.template() else LockScript.format(current)
        }
        LockScriptDialog(
            initial = draft,
            onDismissRequest = { script = false },
            onImport = { text, password ->
                script = false
                message = null
                val parsed = LockScript.parse(text)
                if (parsed.problem != null) {
                    message = context.getString(R.string.settings_lock_import_failed, parsed.problem)
                } else if (parsed.steps.isEmpty()) {
                    message = context.getString(
                        R.string.settings_lock_import_failed,
                        "that script has no steps",
                    )
                } else {
                    scope.launch {
                        // 加密那一份要碰 Keystore, 放 IO 上做
                        val trouble = withContext(Dispatchers.IO) {
                            if (password.isEmpty()) {
                                null
                            } else {
                                LockSecret.save(
                                    context,
                                    LockSecretData(kind = LockSecretData.TEXT, text = password),
                                )
                            }
                        }
                        LockSetting.saveSteps(context, parsed.steps)
                        LockSetting.setTries(context, 0)
                        LockReplay.forgetWarnings()
                        message = trouble ?: context.getString(
                            R.string.settings_lock_imported,
                            parsed.steps.size,
                            LockSetting.describe(parsed.steps).joinToString(" then "),
                        )
                    }
                }
            },
        )
    }
}

/** 这一段要显示的几件事, 一次读齐 (见 [LockItems] 那个 remember) */
private data class LockSummary(
    val wakeScreen: Boolean,
    val autoUnlock: Boolean,
    val injectUnlock: Boolean,
    val steps: Int,
    val recordedAt: Long,
    /** 密码格里放的是什么: `null` 是空的, 其余是 [LockSecretData] 的 kind */
    val secretKind: String?,
    /** 密码的位数, 或者图案的点数 (给"已保存 N 位"那一句用) */
    val secretLength: Int,
    val problem: String?,
)

/**
 * 「测试一次」按下之后, 先锁屏再等这么久才叫醒它
 *
 * 锁屏那一屏画出来要一点时间 (与 [LockReplay] 那条 `GUARD_READY_MS` 是同一个理由), 而这一条是给人
 * 按的, 多等这一下换"测出来的结果是真的"
 */
private const val LOCK_BEFORE_TEST_MS = 400L

/**
 * 「注入密码」那一个对话框: 只写密码格, 不录任何手势 (2026-10-09)
 *
 * 与「录制解锁」那个对话框同一副样子 (密码样式, 点一下可以看明文), 而它只在内存里过一手 —— 存下去走
 * [LockSetting.setPassword], 也就是 [LockSecret] 那唯一一处加密的存法。留空保存 = 清除
 */
@Composable
private fun LockPasswordDialog(
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_lock_inject_password_dialog),
        summary = stringResource(R.string.settings_lock_inject_password_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_lock_inject_password_confirm),
                onClick = {
                    haptic.confirm()
                    onSave(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 「导入解锁脚本」那一个对话框 (批次 5 追加): 脚本正文 + 密码 (可留空) + 导入
 *
 * **框里先放着现在这一条** (没有就是模板): 所以它同时是一份"看得见的导出" —— 主人想改两处坐标, 打开
 * 这个框改一下再导入即可, 不必先删掉重录
 *
 * 校验在 [LockScript.parse] 那一份纯函数里 (第几行、差在哪儿), 这里只负责把那一句念出来
 */
@Composable
private fun LockScriptDialog(
    initial: String,
    onDismissRequest: () -> Unit,
    onImport: (String, String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_lock_script_dialog),
        summary = stringResource(R.string.settings_lock_script_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        var password by rememberSaveable { mutableStateOf("") }
        SuperTextField(
            modifier = Modifier.padding(bottom = 12.dp),
            value = text,
            onValueChange = { text = it },
            maxLines = 10,
            minLines = 6,
        )
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = stringResource(R.string.settings_lock_script_password),
            useLabelAsPlaceholder = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_lock_script_confirm),
                onClick = {
                    haptic.confirm()
                    onImport(text, password)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 「录制解锁」那一个对话框: 密码 (可留空) + 开始
 *
 * **密码框是密码样式的** (点一下就显示明文), 而它只在内存里过一手: 按开始之后加密存, 明文跟着这一次
 * 录制一起消失
 */
@Composable
private fun LockRecordDialog(
    onDismissRequest: () -> Unit,
    onStart: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_lock_record_dialog),
        summary = stringResource(R.string.settings_lock_record_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_lock_record_start),
                onClick = {
                    haptic.confirm()
                    onStart(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 占位那一行 (2026-10-08 批次 2 留下的写法)
 *
 * 它是**故意做出来的东西**: 先摆一行"还没做, 哪一批做"比"功能做完再插一段"更不容易漏 —— 顺序已经
 * 写在开发计划那一张表里了 (批次 5 的「锁屏」段已经从占位换成真东西, 现在只剩「自动指令」那一段).
 * 字用**次级色**画, 于是它一眼看得出是"还不能动的"; 不用一个 `enabled = false` 的开关, 是因为那样
 * 看起来像"这条功能只是被关掉了"。锁屏那一段的最后一行也用它, 那里是"上一次重放成不成"的读数
 */
/**
 * 应用对应的技能 (2.7.0 那一批)
 *
 * 一段话说完它做什么: **这台机器上装了哪个应用, 就把那个应用的技能装进 dsh 的技能目录**。目录随包
 * 发 (`app-skills/catalog.json`, 源在仓库的 `app-skills/`), 技能落在 `$DSH_HOME/skills/`, 装完 dsh
 * 下一轮就看得见 —— 不用重启 host, 也不用去动技能文件
 *
 * **只加不改**: 已经在的那一份一个字节都不动, 这一条与随包技能那一摊 ([io.github.yuloong07star.luwi.host.LwSeed])
 * 是同一条规矩 —— "一键"这个词最招人烦的地方就是把人自己改过的东西盖回去
 */
@Composable
private fun SkillItems() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 点哪一条要问"删不删" (null = 没在问): 与「快捷指令」那一段同一个做法 —— 那个对话框就是确认
    var asking by remember { mutableStateOf<AppSkills.Match?>(null) }
    // 「浏览技能仓库」那一页开着吗 (2.7.8 批次 5): 它自己读索引 / 装 / 升级, 关掉之后这一页重扫一遍
    var browsing by remember { mutableStateOf(false) }
    // 扫一次就够: 这一页开着的时候"装了哪些应用"不会变 (点完按钮再扫一次, 那是另一回事)
    val scanned = remember(revision) { AppSkills.scan(context) }

    if (scanned.matches.isEmpty()) {
        PlaceholderItems(stringResource(R.string.settings_skills_empty))
    } else {
        scanned.matches.forEach { match ->
            ArrowPreference(
                title = match.title,
                summary = stringResource(
                    if (match.present) R.string.settings_skills_have else R.string.settings_skills_missing,
                    match.hits.joinToString(", "),
                ),
                // **已经在的那一条点一下就是"删"这一扇门** (主人 2026-10-10: "设置里的 skill 板块加上
                // 删除 skill 按钮"): 缺着的那一条点它什么都不做 —— 没有东西可删, 而"装"是下面那一颗
                onClick = {
                    if (!match.present) return@ArrowPreference
                    haptic.contextClick()
                    asking = match
                },
            )
        }
    }
    // **目录的来源是 app-skills 那个仓库** (主人 2026-10-10): 这一颗按钮去把它取回来缓存到本机,
    // 而平时那一遍扫描只读本机 (不联网, 也就不会把这一段卡住)
    ArrowPreference(
        title = stringResource(if (busy) R.string.settings_skills_busy else R.string.settings_skills_refresh),
        summary = stringResource(R.string.settings_skills_refresh_summary, AppSkills.REPO),
        onClick = {
            if (busy) return@ArrowPreference
            haptic.contextClick()
            busy = true
            scope.launch {
                val (_, said) = withContext(Dispatchers.IO) { AppSkills.refresh(context) }
                message = said
                revision += 1
                busy = false
            }
        },
    )
    ArrowPreference(
        title = stringResource(if (busy) R.string.settings_skills_busy else R.string.settings_skills_install),
        summary = stringResource(R.string.settings_skills_install_summary),
        onClick = {
            if (busy) return@ArrowPreference
            haptic.contextClick()
            busy = true
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppSkills.install(context) }
                message = report.error.ifEmpty {
                    context.getString(
                        R.string.settings_skills_done,
                        report.wrote.size,
                        report.matches.count { it.present },
                    )
                }
                busy = false
            }
        },
    )
    // **浏览技能仓库** (2.7.8 批次 5): 上面那两颗是"按已装的应用一把装", 这一颗是"我自己挑一条"
    ArrowPreference(
        title = stringResource(R.string.settings_skills_browse),
        summary = stringResource(R.string.settings_skills_browse_summary, AppSkills.REPO),
        onClick = {
            if (busy) return@ArrowPreference
            haptic.contextClick()
            browsing = true
        },
    )
    PlaceholderItems(message ?: stringResource(R.string.settings_skills_note))

    if (browsing) {
        SkillIndexDialog(
            onDismiss = { browsing = false },
            onMessage = { message = it },
            onChanged = { revision += 1 },
        )
    }

    asking?.let { match ->
        SkillDeleteDialog(
            title = match.title,
            name = match.skill,
            onDismiss = { asking = null },
            onDelete = {
                asking = null
                busy = true
                scope.launch {
                    val report = withContext(Dispatchers.IO) { AppSkills.remove(context, listOf(match.skill)) }
                    message = if (report.failed.isEmpty()) {
                        context.getString(R.string.settings_skills_deleted, report.wrote.size)
                    } else {
                        context.getString(R.string.settings_skills_delete_failed, report.failed.size)
                    }
                    revision += 1
                    busy = false
                }
            },
        )
    }
}

/**
 * 删一个技能前那一问
 *
 * 它是**这条链上唯一不可逆的动作**的确认: 删的是 `$DSH_HOME/skills/<名字>/`, 没有回收站, 而
 * "一键装入"能把目录里那份再抄回来 (主人自己改过的那一份抄不回来 —— 那正是要问这一句的理由)
 */
@Composable
private fun SkillDeleteDialog(
    title: String,
    name: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = title,
        summary = stringResource(R.string.settings_skills_delete_summary, name),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.settings_skills_delete),
            onClick = {
                haptic.confirm()
                onDelete()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「浏览技能仓库」(2.7.8 批次 5, 主人 2026-10-17: "技能改成像插件那样浏览技能仓库进行安装")
 *
 * 与 [PluginIndexDialog] 同一套骨架 (搜索框 / 点行只选中 / 底部那颗按钮随选中项变话), 差别在三处:
 *
 * - 每行多一段**版本读数**: 「已装 x.y.z · 远端 a.b.c」。已装但账上没有版本 (老安装) 时前半截写
 *   「已装 ? · ...」, 不编一个版号
 * - 已经在的那条点它 = **升级**, 不是再装一遍 ([AppSkills.upgradeFrom] 只盖没被本地改过的文件)
 * - 升级回来带着 `skipped` 时**先问一句**「这几个文件本地改过」, 选「保留我的」就什么都不盖
 */
@Composable
private fun SkillIndexDialog(
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf<List<AppSkills.RemoteSkill>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localVersions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppSkills.RemoteSkill?>(null) }
    // 升级时"被本地改过"的那几个文件: 先问一句再决定 force (那一句就是覆盖面确认)
    var edited by remember { mutableStateOf<Pair<AppSkills.RemoteSkill, List<String>>?>(null) }

    val reload: () -> Unit = {
        scope.launch {
            val next = withContext(Dispatchers.IO) { AppSkills.index(context) }
            val have = withContext(Dispatchers.IO) { AppSkills.present(context) }
            val versions = withContext(Dispatchers.IO) {
                have.associateWith { AppSkills.installedVersion(context, it) }
            }
            index = next
            installed = have
            localVersions = versions
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                val said = withContext(Dispatchers.IO) { AppSkills.refresh(context) }
                refreshing = false
                onMessage(said.second)
                reload()
            }
        }
    }
    val install: (AppSkills.RemoteSkill) -> Unit = { skill ->
        if (!working) {
            working = true
            haptic.confirm()
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppSkills.installFrom(context, skill) }
                working = false
                onMessage(AppSkills.sentenceOf(report, "install"))
                reload()
                onChanged()
            }
        }
    }
    val upgrade: (AppSkills.RemoteSkill, Boolean) -> Unit = { skill, force ->
        if (!working) {
            working = true
            haptic.confirm()
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppSkills.upgradeFrom(context, skill, force) }
                working = false
                // 有被本地改过的那几个 -> 先问; 否则直接把结论念出来
                if (report.skipped.isNotEmpty() && !force) {
                    edited = skill to report.skipped
                } else {
                    onMessage(AppSkills.sentenceOf(report, "upgrade"))
                }
                reload()
                onChanged()
            }
        }
    }

    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_skills_browse),
        summary = stringResource(R.string.settings_skills_browse_summary, AppSkills.REPO),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        SuperTextField(
            modifier = Modifier.padding(bottom = 12.dp),
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.settings_skills_browse_query),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.5f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val candidates = remember(index, query) { AppSkills.search(index, query) }
            when {
                loading -> PlaceholderItems(stringResource(R.string.settings_skills_browse_loading))
                index.isEmpty() -> PlaceholderItems(stringResource(R.string.settings_skills_browse_empty))
                candidates.isEmpty() -> PlaceholderItems(stringResource(R.string.settings_skills_browse_none, query))
            }
            candidates.forEach { skill ->
                val have = skill.id in installed
                val remote = skill.version.ifBlank { "?" }
                val versions = if (have) {
                    stringResource(
                        R.string.settings_skills_browse_installed,
                        localVersions[skill.id].orEmpty().ifBlank { "?" },
                        remote,
                    )
                } else {
                    stringResource(R.string.settings_skills_browse_fresh, remote)
                }
                val risk = if (skill.risk.isBlank()) {
                    ""
                } else {
                    " · " + stringResource(R.string.settings_skills_browse_risk, skill.risk)
                }
                val summary = listOf(versions + risk, skill.description)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val shown = if (selected?.id == skill.id) "\u2713 " else ""
                ArrowPreference(
                    title = shown + listOf(skill.title, skill.version).filter { it.isNotBlank() }.joinToString(" "),
                    summary = summary,
                    onClick = {
                        haptic.contextClick()
                        selected = skill
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(top = 12.dp)) {
            TextButton(
                text = if (refreshing) {
                    stringResource(R.string.settings_skills_browse_refreshing)
                } else {
                    stringResource(R.string.settings_skills_browse_refresh)
                },
                onClick = refresh,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            val picked = selected
            // 按钮随选中项变话: 没选 -> 完成; 选中的已经装着 -> 升级; 否则 -> 安装
            val action = when {
                working -> stringResource(R.string.settings_skills_browse_working)
                picked == null -> stringResource(R.string.settings_skills_browse_close)
                picked.id in installed -> stringResource(R.string.settings_skills_browse_upgrade)
                else -> stringResource(R.string.settings_skills_browse_install)
            }
            TextButton(
                text = action,
                onClick = {
                    haptic.contextClick()
                    if (!working) {
                        when {
                            picked == null -> onDismiss()
                            picked.id in installed -> upgrade(picked, false)
                            else -> install(picked)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    edited?.let { (skill, files) ->
        SkillOverwriteDialog(
            name = skill.id,
            files = files,
            onDismiss = {
                edited = null
                onMessage(AppSkills.sentenceOf(AppSkills.InstallReport(skill.id, skill.version), "upgrade"))
            },
            onOverwrite = {
                edited = null
                upgrade(skill, true)
            },
        )
    }
}

/** 升级时"这几个文件本地改过"那一问: 盖掉 (force) 还是留着 */
@Composable
private fun SkillOverwriteDialog(
    name: String,
    files: List<String>,
    onDismiss: () -> Unit,
    onOverwrite: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_skills_browse_edited_title),
        summary = stringResource(R.string.settings_skills_browse_edited_summary, files.joinToString(", ")),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.settings_skills_browse_edited_keep),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.settings_skills_browse_edited_overwrite),
            onClick = {
                haptic.confirm()
                onOverwrite()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun PlaceholderItems(text: String) {    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(text = text, color = colorScheme.onSurfaceVariantActions)
    }
}

/**
 * 「Linux 沙盒」那一段 (2.7.7)
 *
 * 状态这一行是这一段的重点: 未安装 / 已安装 (版本·占用) / 有几条命令在跑 / 通道连没连 —— 四种事实
 * 各说各的, 通道不在线时也把读得到的那一半照实读出来 (见 [LinuxSetting] 的文件头)
 *
 * **"删掉它"是这一页上不可逆的两件事之一**: 删掉的是 `/data/local/tmp/lwlinux` 下的整棵 rootfs,
 * 里面 `apk add` 装的一切一起没, 所以点它先弹那一问; 而那一问里也要说清另一件事 —— 通道断开之后
 * 那个目录连删除按钮都够不着 (残留只能走 adb 或重装应用清)
 */
/** 设置页「Luwi-dsh-sync」那一段的文本项 (逻辑名 → 标题/摘要/键盘形态) */
private enum class SyncField(
    val field: String,
    val title: Int,
    val summary: Int,
    val secret: Boolean = false,
    val uri: Boolean = false,
    /** 对话框里那一格底下再补一句"该填成什么样" (0 = 不补) */
    val note: Int = 0,
) {
    SESSION_REMOTE(SyncSetting.FIELD_SESSION_REMOTE, R.string.settings_sync_session_remote, R.string.settings_sync_session_remote_summary, uri = true, note = R.string.settings_sync_remote_hint),
    WORKSPACE_REMOTE(SyncSetting.FIELD_WORKSPACE_REMOTE, R.string.settings_sync_workspace_remote, R.string.settings_sync_workspace_remote_summary, uri = true, note = R.string.settings_sync_remote_hint),
    DEVICE_NAME(SyncSetting.FIELD_DEVICE_NAME, R.string.settings_sync_device_name, R.string.settings_sync_device_name_summary),
    PUBLIC_URL(SyncSetting.FIELD_PUBLIC_URL, R.string.settings_sync_public_url, R.string.settings_sync_public_url_summary, uri = true),
    PEER_URL(SyncSetting.FIELD_PEER_URL, R.string.settings_sync_peer_url, R.string.settings_sync_peer_url_summary, uri = true),
    PEER_TOKEN(SyncSetting.FIELD_PEER_TOKEN, R.string.settings_sync_peer_token, R.string.settings_sync_peer_token_summary, secret = true),
    BRANCH(SyncSetting.FIELD_BRANCH, R.string.settings_sync_branch, R.string.settings_sync_branch_summary),
}

/** 每一项现在写的是什么 (对话框的初值) */
private fun syncInitial(field: SyncField, snapshot: SyncSetting.Snapshot): String = when (field) {
    SyncField.SESSION_REMOTE -> snapshot.sessionRemote
    SyncField.WORKSPACE_REMOTE -> snapshot.workspaceRemote
    SyncField.DEVICE_NAME -> snapshot.deviceName
    SyncField.PUBLIC_URL -> snapshot.publicUrl
    SyncField.PEER_URL -> snapshot.peerUrl
    SyncField.PEER_TOKEN -> snapshot.peerToken
    SyncField.BRANCH -> snapshot.branch
}

/**
 * 设置页「Luwi-dsh-sync」那一段 (G1-2 对端)
 *
 * 这一段写的是 `$DSH_HOME/lw/session-sync.json` —— **手机侧唯一的配置面** (插件在宿主里读它,
 * 手机侧的 overlay 行不带 config, 所以这里写下的每个键都会生效)。三条口径:
 *
 * - **远端地址与连接口令都不回显原文**: 地址只报"已配置 / 未配置" (值里可能有 token), 口令显示
 *   打码形态。要看原文去「看当前配置」那一页 (那里的口令也是打码的, 只有文件里才有真的)
 * - **改完要重启 host**: 那一条在右上角 ⋮ 里, 所以段首把那句话摆在人眼前, 而不是在这里再放一个按钮
 * - **对端那三行是一组**: 通道开关 + 对端地址 + 连接口令, 缺口令时通道是关的 (插件那一边是
 *   失败关闭), 所以状态行会把这件事说出来
 */
@Composable
private fun SyncItems() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 存完由 SyncSetting.revision 叫醒这一段 (与锁屏那一段同一个做法)
    val snapshot = remember(SyncSetting.revision) { SyncSetting.snapshot(context) }
    var field by remember { mutableStateOf<SyncField?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var interval by remember(snapshot.pullInterval) { mutableIntStateOf(snapshot.pullInterval) }

    PlaceholderItems(stringResource(R.string.settings_sync_note))

    // ---- 第一排: 让 agent 帮你配置 (主人 2026-10-14) ----
    // 这一段要填的东西不少, 而"照着上下文改一份配置"正好是 agent 拿手的: 点一下就把一段提示词投给
    // 会话 (走的就是快捷指令那条路 —— VoiceInbox + SOURCE_QUICK), 它拿 `lw_sync` 工具自己看状态、
    // 问主人要仓库地址、写下去。**它够不到 `$DSH_HOME` 是这条工具存在的理由**: dsh 的 fs 工具关在
    // 工作区, lw_linux 的 proot 也只 bind 工作区
    var asked by remember { mutableStateOf<String?>(null) }
    ArrowPreference(
        title = stringResource(R.string.settings_sync_ask_agent),
        summary = stringResource(R.string.settings_sync_ask_agent_summary),
        onClick = {
            val seq = VoiceInbox.append(
                context,
                context.getString(R.string.settings_sync_agent_prompt),
                source = VoiceInbox.SOURCE_QUICK,
            )
            asked = context.getString(
                if (seq == null) R.string.settings_sync_agent_failed else R.string.settings_sync_agent_sent,
            )
        },
    )
    asked?.let { said -> PlaceholderItems(said) }

    // ---- 总开关: **默认关** (新装没有远端地址 = 关), 打开之后才出现下面那几件该填的东西 ----
    // 打开时如果还没填过地址, 直接就把地址那一行的对话框摆出来 (少走一次"自己去找那一行"), 存完
    // 由 pendingSyncOn 把开关补上 —— 于是"想开同步"这件事是两次点击而不是四次
    var pendingSyncOn by remember { mutableStateOf(false) }
    SwitchPreference(
        title = stringResource(R.string.settings_sync_master),
        summary = stringResource(
            if (snapshot.syncOn) R.string.settings_sync_master_on else R.string.settings_sync_master_off,
        ),
        checked = snapshot.syncOn,
        onCheckedChange = { wanted ->
            val problem = SyncSetting.setSyncOn(context, wanted)
            if (problem == null && wanted && snapshot.sessionRemote.isBlank()) {
                pendingSyncOn = true
                field = SyncField.SESSION_REMOTE
            }
        },
    )

    if (!snapshot.syncOn) {
        PlaceholderItems(stringResource(R.string.settings_sync_off_hint))
    } else {
        PlaceholderItems(stringResource(R.string.settings_sync_on_hint))

        // ① 仓库: 会话那根是同步的主体 (工作区那根是可选的第二根)
        PlaceholderItems(stringResource(R.string.settings_sync_step_git))
        for (entry in listOf(SyncField.SESSION_REMOTE, SyncField.WORKSPACE_REMOTE)) {
            val set = if (entry == SyncField.SESSION_REMOTE) snapshot.sessionRemoteSet else snapshot.workspaceRemoteSet
            ArrowPreference(
                title = stringResource(entry.title),
                summary = stringResource(if (set) R.string.settings_sync_remote_set else R.string.settings_sync_remote_unset),
                onClick = { field = entry },
            )
        }

        // ② 什么时候同步 (三个都不选 = 只用时手动点, 这是刚打开时的样子)
        PlaceholderItems(stringResource(R.string.settings_sync_step_auto))
        SwitchPreference(
            title = stringResource(R.string.settings_sync_auto_pull),
            summary = stringResource(R.string.settings_sync_auto_pull_summary),
            checked = snapshot.autoPull,
            onCheckedChange = { SyncSetting.setFlag(context, SyncSetting.FLAG_AUTO_PULL, it) },
        )
        SwitchPreference(
            title = stringResource(R.string.settings_sync_auto_push),
            summary = stringResource(R.string.settings_sync_auto_push_summary),
            checked = snapshot.autoPush,
            onCheckedChange = { SyncSetting.setFlag(context, SyncSetting.FLAG_AUTO_PUSH, it) },
        )
        ArrowSlider(
            title = stringResource(R.string.settings_sync_interval),
            summary = stringResource(R.string.settings_sync_interval_summary, interval),
            value = interval.toFloat(),
            onValueChange = { raw ->
                val next = raw.roundToInt().coerceIn(0, 60)
                // 拖动时每一帧都写一次文件没有必要: 取整之后变了才存
                if (next != interval) {
                    interval = next
                    SyncSetting.setPullInterval(context, next)
                }
            },
            valueRange = 0f..60f,
            steps = 0,
            showKeyPoints = true,
            keyPoints = listOf(0f, 1f, 5f, 15f, 30f, 60f),
            unit = "min",
            inputSummary = stringResource(R.string.settings_sync_interval_dialog),
            inputLabel = "min",
            inputInitialValue = interval.toString(),
            inputFilter = { text -> text.filter(Char::isDigit) },
            inputValueRange = 0f..60f,
            onInputConfirm = { raw ->
                raw.toIntOrNull()?.let { typed ->
                    val next = typed.coerceIn(0, 60)
                    interval = next
                    SyncSetting.setPullInterval(context, next)
                }
            },
        )
        ArrowPreference(
            title = stringResource(SyncField.BRANCH.title),
            summary = if (snapshot.branch.isBlank()) "main" else snapshot.branch,
            onClick = { field = SyncField.BRANCH },
        )

        // ③ 工作区那一根 (可选): 文件走的是另一个远端, 与上面会话那根各记各的账
        PlaceholderItems(stringResource(R.string.settings_sync_step_workspace))
        SwitchPreference(
            title = stringResource(R.string.settings_sync_workspace_on),
            summary = stringResource(R.string.settings_sync_workspace_on_summary),
            checked = snapshot.workspaceOn,
            onCheckedChange = { SyncSetting.setFlag(context, SyncSetting.FLAG_WORKSPACE_ON, it) },
        )
    }

    // ---- 身份与跨设备通道: **与同步开关无关**, 关着同步时这几行照样能用 ----
    PlaceholderItems(stringResource(R.string.settings_sync_step_peer))
    ArrowPreference(
        title = stringResource(SyncField.DEVICE_NAME.title),
        summary = if (snapshot.deviceName.isBlank()) stringResource(R.string.settings_sync_device_name_none) else snapshot.deviceName,
        onClick = { field = SyncField.DEVICE_NAME },
    )
    ArrowPreference(
        title = stringResource(SyncField.PUBLIC_URL.title),
        summary = if (snapshot.publicUrl.isBlank()) stringResource(R.string.settings_sync_remote_unset) else snapshot.publicUrl,
        onClick = { field = SyncField.PUBLIC_URL },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_sync_peer_on),
        summary = stringResource(R.string.settings_sync_peer_on_summary),
        checked = snapshot.peerOn,
        onCheckedChange = { SyncSetting.setFlag(context, SyncSetting.FLAG_PEER_ON, it) },
    )
    ArrowPreference(
        title = stringResource(SyncField.PEER_URL.title),
        summary = if (snapshot.peerUrl.isBlank()) stringResource(R.string.settings_sync_peer_url_none) else snapshot.peerUrl,
        onClick = { field = SyncField.PEER_URL },
    )
    ArrowPreference(
        title = stringResource(SyncField.PEER_TOKEN.title),
        summary = stringResource(R.string.settings_sync_peer_token_state, SyncSetting.maskedToken(snapshot.peerToken)),
        onClick = { field = SyncField.PEER_TOKEN },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_sync_peer_listen),
        summary = stringResource(R.string.settings_sync_peer_listen_summary),
        checked = snapshot.peerListen,
        onCheckedChange = { SyncSetting.setFlag(context, SyncSetting.FLAG_PEER_LISTEN, it) },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_sync_peer_proxy),
        summary = stringResource(R.string.settings_sync_peer_proxy_summary),
        checked = snapshot.peerProxy,
        onCheckedChange = { SyncSetting.setFlag(context, SyncSetting.FLAG_PEER_PROXY, it) },
    )

    ArrowPreference(
        title = stringResource(R.string.settings_sync_show),
        summary = stringResource(R.string.settings_sync_show_summary),
        onClick = { showRaw = true },
    )

    SyncSetting.message?.let { said -> PlaceholderItems(said) }

    field?.let { picking ->
        SyncFieldDialog(
            field = picking,
            initial = syncInitial(picking, snapshot),
            onDismissRequest = {
                field = null
                pendingSyncOn = false
            },
            onSave = { text ->
                val problem = SyncSetting.setText(context, picking.field, text)
                field = null
                // "想开同步 → 先填地址"那一趟: 地址存下去了就把开关当场补上, 不用再点第二下
                if (problem == null && pendingSyncOn && picking.field == SyncSetting.FIELD_SESSION_REMOTE) {
                    pendingSyncOn = false
                    SyncSetting.setSyncOn(context, true)
                }
            },
        )
    }

    if (showRaw) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.settings_sync_show),
            summary = stringResource(R.string.settings_sync_show_dialog),
            defaultWindowInsetsPadding = false,
            onDismissRequest = { showRaw = false },
        ) {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(text = SyncSetting.pretty(context), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.settings_sync_close),
                    onClick = {
                        haptic.confirm()
                        showRaw = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 一个文本项的对话框 (与「注入密码」那一个同一个形制: 取 `rememberSaveable` 的初值 + 确认写回) */
@Composable
private fun SyncFieldDialog(
    field: SyncField,
    initial: String,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(field.title),
        summary = stringResource(field.summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = if (field.note != 0) 8.dp else 16.dp),
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            useLabelAsPlaceholder = true,
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (field.uri) KeyboardType.Uri else KeyboardType.Text,
            ),
        )
        // 「说清楚这一空该填什么」: 地址那两格底下补一句例子 (与「添加模型」那三个框同一个做法)
        if (field.note != 0) {
            Text(
                text = stringResource(field.note),
                color = colorScheme.onSurfaceVariantActions,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.settings_sync_confirm),
                onClick = {
                    haptic.confirm()
                    onSave(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun LinuxItems() {
    val haptic = LocalHapticFeedback.current
    val state = LinuxSetting.state
    // 只在这个进程里读过一次的状态: 每次进这一页读一次, 装/删完由 LinuxSetting 自己再读
    LaunchedEffect(Unit) { LinuxSetting.refresh() }
    var asking by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(text = linuxStateSentence(state))
        state?.let { current ->
            if (current.installed && current.sizeKb != null) {
                Text(
                    text = stringResource(
                        R.string.settings_linux_size,
                        bytes(current.sizeKb * 1024),
                    ),
                    modifier = Modifier.padding(top = UiSpacing.Small),
                )
            }
            Text(
                text = if (current.channelConnected) {
                    stringResource(R.string.settings_linux_running, current.running)
                } else {
                    stringResource(R.string.settings_linux_no_channel, current.error.orEmpty())
                },
                modifier = Modifier.padding(top = UiSpacing.Small),
                color = colorScheme.onSurfaceVariantActions,
            )
        }
    }

    // 诚实那一条: 三个"弱"写在界面上, 免得"沙盒"这两个字给人一个它兑现不了的承诺
    PlaceholderItems(stringResource(R.string.settings_linux_note))

    ArrowPreference(
        title = stringResource(if (state?.installed == true) R.string.settings_linux_reinstall else R.string.settings_linux_install),
        summary = stringResource(R.string.settings_linux_install_summary),
        onClick = {
            // 通道不在线也放进 [LinuxSetting.install]: 那边会先试着接一次, 接不上才如实拒 ——
            // 在这里静默 return 的话, 通道掉着时这颗按钮点了没反应, 用户不知道发生了什么
            if (LinuxSetting.working) return@ArrowPreference
            haptic.contextClick()
            LinuxSetting.install()
        },
    )
    if (state?.installed == true) {
        ArrowPreference(
            title = stringResource(R.string.settings_linux_remove),
            summary = stringResource(R.string.settings_linux_remove_summary),
            onClick = {
                if (LinuxSetting.working) return@ArrowPreference
                haptic.contextClick()
                asking = true
            },
        )
    }
    LinuxSetting.message?.let { said -> PlaceholderItems(said) }

    if (asking) {
        LinuxRemoveDialog(
            onDismiss = { asking = false },
            onDelete = {
                asking = false
                LinuxSetting.remove()
            },
        )
    }
}

/** 状态那一行的一句话 */
@Composable
private fun linuxStateSentence(state: LwLinux.State?): String = when {
    state == null -> stringResource(R.string.settings_linux_reading)
    !state.installed -> stringResource(R.string.settings_linux_absent)
    state.version.isNullOrBlank() -> stringResource(R.string.settings_linux_installed_unknown)
    else -> stringResource(R.string.settings_linux_installed, state.version)
}

/**
 * 删掉整个沙盒之前那一问
 *
 * 与删技能那一问是同一件事的另一个名字: 这里没有回收站, 而"再装回来"能拿回来的只有随包那一份
 * —— 沙盒里自己 `apk add` 装的全都没了
 */
@Composable
private fun LinuxRemoveDialog(
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_linux_remove_dialog_title),
        summary = stringResource(R.string.settings_linux_remove_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.settings_linux_remove_dialog_ok),
            onClick = {
                haptic.confirm()
                onDelete()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「快捷指令」那一段 (2.5.0 批次 7)
 *
 * 列表与 `lw_quick op=list` 读的是同一份 ([LwQuick.entries]), 所以"界面上看得见而模型读不到"这种
 * 分裂不会发生
 *
 * **点一下做的事不是在这里执行那条工作流**, 而是把它投进会话: 投递走语音那条老路 ([VoiceInbox] 的
 * `quick` 来源), 于是"当前会话没有就新建 / 10 分钟内接同一场 / 正在跑就插进当前轮"三条语义自动成立,
 * 而"主人说的是什么"这句话也原封不动地出现在会话里
 *
 * **新建不弹表单**: 只要一行"这条快捷指令要做什么", 正文交给模型写 (工作流这件事它写得比界面里拼
 * 字符串稳), 它用 `lw_quick op=write` 落盘, 再回到这一页时列表里就有了
 *
 * 删除在这页上做 (模型侧没有 delete): 点一条先问"运行还是删除", 那一个对话框本身就是那道确认
 */
@Composable
private fun QuickItems() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var revision by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var acting by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 新建与删除都发生在这一页上, 所以列表读一次就够; 变一次重读一次
    val entries = remember(revision) { LwQuick.entries(context) }

    if (entries.isEmpty()) {
        PlaceholderItems(
            stringResource(R.string.settings_quick_empty, LwQuick.directory(context).absolutePath),
        )
    } else {
        entries.forEach { entry ->
            ArrowPreference(
                title = entry.name,
                summary = entry.summary.ifEmpty { stringResource(R.string.settings_quick_no_summary) },
                onClick = {
                    haptic.contextClick()
                    acting = entry.name
                },
            )
        }
    }
    ArrowPreference(
        title = stringResource(R.string.settings_quick_new),
        summary = stringResource(R.string.settings_quick_new_summary),
        onClick = {
            haptic.contextClick()
            creating = true
        },
    )
    PlaceholderItems(message ?: stringResource(R.string.settings_quick_note))

    acting?.let { name ->
        QuickActionDialog(
            name = name,
            onDismiss = { acting = null },
            onRun = {
                acting = null
                message = sendQuickCommand(context, context.getString(R.string.settings_quick_run_sentence, name))
            },
            onDelete = {
                acting = null
                message = deleteQuickCommand(context, name)
                revision += 1
            },
        )
    }

    if (creating) {
        QuickCreateDialog(
            onDismiss = { creating = false },
            onCreate = { said ->
                creating = false
                message = sendQuickCommand(
                    context,
                    context.getString(R.string.settings_quick_create_prompt, said),
                )
            },
        )
    }
}

/**
 * 「插件」那一段 (2.5.1 批次 9 的 P0 / P1)
 *
 * 这一页是**生态的入口**, 也是"能力勾了才给"那句话唯一能答应的地方: 装进来的包默认什么都拿不到,
 * 敏感档默认不勾。列表与 `lw_plugin op=list` 读的是同一份 ([PluginManager.entries]), 所以"界面上
 * 看得见而模型读不到"这种事不会发生
 *
 * 三条与别处不同的口径:
 *
 * - **装一份包不等于启用它**: 装完还要勾能力再点启用, 而启用的那一步才真去绑伴侣的 Service
 * - **卸载要问那一句**: 它自己那份数据删不删, 两个按钮各是一个答案 (协议第 7 节)
 * - **未签名的包只在开发者模式里进得来**: 那一个开关开着时, 上面常驻一句红字提示
 */
@Composable
private fun PluginItems() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var developer by remember { mutableStateOf(LwPlugin.developerMode(context)) }
    var acting by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 从链接装那一档: 粘贴框开着没有 / 正在下
    var linking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    // 浏览插件仓库那一档 (2026-10-16): 官方索引的搜索/安装弹窗开着没有
    var browsing by remember { mutableStateOf(false) }
    // 一次确认那一档 (2026-10-11): 包声明了能力就先摆出来问一次, 答完才装 —— 这是"输入地址,
    // 装完即可用"里唯一的一次问答 (零能力插件连这一次都没有)
    var confirming by remember { mutableStateOf<PendingInstall?>(null) }
    var confirmingBusy by remember { mutableStateOf(false) }
    // 桌面组件那两个槽 (方案 3.1): 点一行打开"挂哪一只 / 空着 / 放到桌面"
    var slotDialog by remember { mutableStateOf<String?>(null) }
    // 浮标外观 (协议 10.1): 挑一个主题插件, 或者换回内置画法
    var themeDialog by remember { mutableStateOf(false) }
    val entries = remember(revision) { PluginManager.entries(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val started = withContext(Dispatchers.IO) { startPickedInstall(context, uri) }
                when (started) {
                    is StartedInstall.Done -> message = started.text
                    is StartedInstall.Ask -> confirming = started.pending
                }
                revision += 1
            }
        }
    }

    if (entries.isEmpty()) {
        PlaceholderItems(stringResource(R.string.settings_plugin_empty, PluginStore.root(context).absolutePath))
    } else {
        entries.forEach { entry ->
            ArrowPreference(
                title = listOf(entry.name, entry.version).filter { it.isNotBlank() }.joinToString(" "),
                summary = pluginSummary(context, entry),
                onClick = {
                    haptic.contextClick()
                    acting = entry.id
                },
            )
        }
    }

    // 桌面那两个槽 (方案 3.1): 一个槽一行 —— 它现在挂着谁, 点开可以换 / 清空 / 直接放到桌面。
    // 同槽互斥那一件事在这里看得见: 换一只只重画, 另一只仍然留在上面那份列表里
    PlaceholderItems(
        stringResource(R.string.settings_widget_section) + " · " + stringResource(R.string.settings_widget_section_summary),
    )
    WidgetSlot.ALL.forEach { slot ->
        val holder = remember(revision) { WidgetSlots.holder(context, slot) }
        val holderName = holder?.let { id ->
            entries.firstOrNull { it.id == id }?.name ?: id
        }
        ArrowPreference(
            title = WidgetPin.slotLabel(context, slot),
            summary = holderName ?: stringResource(R.string.settings_widget_vacant_choice),
            onClick = {
                haptic.contextClick()
                slotDialog = slot
            },
        )
    }

    // 浮标外观那一条 (协议 10.1 / 方案 3.2): 多个主题插件可以同时装着, 同一时刻只生效一个
    val themeId = remember(revision) { BallTheme.currentId(context) }
    val themeName = themeId.takeIf { it.isNotBlank() }?.let { id ->
        entries.firstOrNull { it.id == id }?.name ?: id
    } ?: stringResource(R.string.settings_theme_builtin)
    ArrowPreference(
        title = stringResource(R.string.settings_theme_row),
        summary = themeName,
        onClick = {
            haptic.contextClick()
            themeDialog = true
        },
    )

    ArrowPreference(
        title = stringResource(R.string.settings_plugin_install),
        summary = stringResource(R.string.settings_plugin_install_summary),
        onClick = {
            haptic.contextClick()
            // 不写死 mime: `.lwp` 就是一个 zip, 而各家文件管理给的 type 并不统一
            picker.launch(arrayOf("*/*"))
        },
    )
    // 协议第 13 节那条"从 URL 安装": 粘一个 https 链接进来, 验与装与本地那份走同一条链
    ArrowPreference(
        title = stringResource(R.string.settings_plugin_link),
        summary = stringResource(R.string.settings_plugin_link_summary),
        onClick = {
            haptic.contextClick()
            linking = true
        },
    )
    // 官方索引那一档 (2026-10-16): 读索引、在里头搜、点一下下下来装 —— 装走的是同一条链, 索引只多
    // 做"装之前核整包 sha256 与大小"这一件
    ArrowPreference(
        title = stringResource(R.string.settings_plugin_browse),
        summary = stringResource(R.string.settings_plugin_browse_summary),
        onClick = {
            haptic.contextClick()
            browsing = true
        },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_plugin_developer),
        summary = stringResource(R.string.settings_plugin_developer_summary),
        checked = developer,
        onCheckedChange = { on ->
            developer = on
            LwPlugin.setDeveloperMode(context, on)
            revision += 1
        },
    )
    PlaceholderItems(message ?: stringResource(R.string.settings_plugin_note))

    if (linking) {
        PluginLinkDialog(
            busy = downloading,
            title = stringResource(R.string.settings_plugin_link_title),
            summary = stringResource(R.string.settings_plugin_link_dialog_summary),
            hint = stringResource(R.string.settings_plugin_link_hint),
            action = stringResource(R.string.settings_plugin_link_install),
            busyText = stringResource(R.string.settings_plugin_link_busy),
            onDismiss = { if (!downloading) linking = false },
            onInstall = { url ->
                downloading = true
                scope.launch {
                    // 下与验都在 IO 上做: 一份几十 KB 的包也要过一次网, 而这一句可能等好几秒
                    val started = withContext(Dispatchers.IO) { startLinkedInstall(context, url) }
                    downloading = false
                    linking = false
                    when (started) {
                        is StartedInstall.Done -> message = started.text
                        is StartedInstall.Ask -> confirming = started.pending
                    }
                    revision += 1
                }
            },
        )
    }

    if (browsing) {
        PluginIndexDialog(
            installed = entries,
            onDismiss = { browsing = false },
            onMessage = { said ->
                message = said
                revision += 1
            },
            onAsk = { pending ->
                confirming = pending
                browsing = false
            },
        )
    }

    // 装之前那一问 (2026-10-11): 只有这个包声明了能力才走到这里, 零能力插件一路直通
    confirming?.let { asking ->        PluginCapabilityDialog(
            manifest = asking.manifest,
            busy = confirmingBusy,
            onDismiss = {
                if (!confirmingBusy) {
                    asking.file.delete()
                    confirming = null
                }
            },
            onConfirm = { chosen ->
                confirmingBusy = true
                scope.launch {
                    val said = withContext(Dispatchers.IO) {
                        finishInstall(context, asking.file, chosen, allowSystemInstaller = true)
                    }
                    confirmingBusy = false
                    confirming = null
                    message = said
                    revision += 1
                }
            },
        )
    }

    acting?.let { id ->
        PluginDialog(
            id = id,
            revision = revision,
            onDismiss = { acting = null },
            onChanged = { said ->
                message = said
                revision += 1
            },
        )
    }

    slotDialog?.let { slot ->
        WidgetSlotDialog(
            slot = slot,
            revision = revision,
            onDismiss = { slotDialog = null },
            onChanged = { said ->
                message = said
                revision += 1
            },
        )
    }

    if (themeDialog) {
        BallThemeDialog(
            revision = revision,
            onDismiss = { themeDialog = false },
            onChanged = { said ->
                message = said
                revision += 1
            },
        )
    }
}

/** 一行的读数: 现在什么状态 · 谁发的 · 授权几条 */
private fun pluginSummary(context: Context, entry: PluginManager.Entry): String {
    val state = when {
        entry.problem != null -> context.getString(R.string.settings_plugin_state_broken)
        entry.enabled && entry.hostProblem == null -> context.getString(R.string.settings_plugin_state_enabled)
        entry.enabled -> context.getString(R.string.settings_plugin_state_unlinked, entry.hostProblem.orEmpty())
        else -> context.getString(R.string.settings_plugin_state_stopped)
    }
    val trust = when (entry.trust) {
        PluginManager.Trust.OFFICIAL -> context.getString(R.string.settings_plugin_trust_official)
        PluginManager.Trust.SIGNED -> context.getString(R.string.settings_plugin_trust_signed)
        PluginManager.Trust.NEW_PUBLISHER -> context.getString(R.string.settings_plugin_trust_new)
        PluginManager.Trust.UNSIGNED -> context.getString(R.string.settings_plugin_trust_unsigned)
    }
    val publisher = entry.manifest?.publisherName?.takeIf { it.isNotBlank() } ?: trust
    val declared = entry.manifest?.declared?.size ?: 0
    return context.getString(R.string.settings_plugin_summary_line, state, publisher, entry.granted.size, declared)
}

/** 一份正要装的包: 已经下到 (或拷到) 本地, 还没落地 */
private class PendingInstall(val file: java.io.File, val manifest: PluginManifest)

/** 「开始装」的结果: 要么装完了, 要么得先把该给的能力摆出来问一次 */
private sealed interface StartedInstall {
    data class Done(val text: String) : StartedInstall
    data class Ask(val pending: PendingInstall) : StartedInstall
}

/**
 * 装之前那半段: 先看一眼这份包声明了什么
 *
 * 声明了能力 → 交给 [StartedInstall.Ask], 由界面摆出来问一次 (协议第 5 节那句"普通档安装时列出,
 * 一次确认"); 一条都不要 (鲸鱼娘那一档) → 没有要问的, 一口气做完
 */
private fun startInstall(context: Context, file: java.io.File): StartedInstall {
    val manifest = try {
        PluginInstaller.preview(context, file)
    } catch (error: Throwable) {
        file.delete()
        return StartedInstall.Done("装不上: ${error.message ?: error.javaClass.simpleName}")
    }
    if (manifest.declared.isNotEmpty()) return StartedInstall.Ask(PendingInstall(file, manifest))
    return StartedInstall.Done(finishInstall(context, file, emptySet(), allowSystemInstaller = true))
}

/** 系统选择器给的是一个 uri, 而这一条链要的是文件: 先拷进 cache 再走同一条 */
private fun startPickedInstall(context: Context, uri: Uri): StartedInstall {
    val picked = java.io.File(context.cacheDir, "picked-plugin-${System.currentTimeMillis()}")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            picked.outputStream().use { output -> input.copyTo(output) }
        } ?: return StartedInstall.Done("读不到你选的那一份")
        startInstall(context, picked)
    } catch (error: Throwable) {
        picked.delete()
        StartedInstall.Done("装不上: ${error.message ?: error.javaClass.simpleName}")
    }
}

/**
 * 从链接装一份 (协议第 13 节): 下到 cache → 走同一条链
 *
 * "只许 https / 不许跳到 http / 大小上限 / 是不是一份 zip" 这几条都是 [PluginDownload] 那一侧说的,
 * 这里只把它的那句错话原样交出去
 */
private fun startLinkedInstall(context: Context, url: String): StartedInstall = try {
    startInstall(context, PluginDownload.fetch(context, url))
} catch (error: Throwable) {
    StartedInstall.Done("装不上: ${error.message ?: error.javaClass.simpleName}")
}

/**
 * 从索引装一份 (2026-10-16): 比「从链接装入」多两道它才有资格做的闸, 其余同一条链
 *
 * - **装之前核整包**: 下下来的字节算 sha256, 与索引里那一个对上、大小也对上才往下走 —— 这是"索引被
 *   替换成同名不同发布者的包"那一洞的封条 (协议第 14 节第 3 条)
 * - **核发布者指纹**: 预览那份清单算出来的指纹要与索引里说的一致 (发布者换钥匙 = 换了一个人)
 * - 之后再走 [startInstall]: 能力问答 / 伴侣 APK / 桌面槽 / 启用, 一个字都不省
 */
private fun startIndexInstall(context: Context, entry: PluginIndex.Entry): StartedInstall {
    val file = try {
        // 主址 + 镜像逐条试 (2026-10-15): 主址 cdn.jsdelivr.net 在有些网络里连不上, 而能通的
        // 那几条都在索引的 mirrors 里 —— 只试主址就是"点安装装不上"
        PluginDownload.fetchAny(context, entry.packageUrls())
    } catch (error: Throwable) {
        return StartedInstall.Done("装不上: ${error.message ?: error.javaClass.simpleName}")
    }
    val digest = PluginIndex.digestOf(file)
    if (entry.sha256.isNotBlank() && digest != entry.sha256) {
        file.delete()
        return StartedInstall.Done("装不上: 这份包与索引对不上 (整包摘要 ${digest ?: "算不出来"} ≠ 索引里那一个)")
    }
    if (entry.size > 0 && file.length() != entry.size) {
        file.delete()
        return StartedInstall.Done("装不上: 这份包 ${file.length()} 字节, 索引里说的是 ${entry.size}")
    }
    val manifest = try {
        PluginInstaller.preview(context, file)
    } catch (error: Throwable) {
        file.delete()
        return StartedInstall.Done("装不上: ${error.message ?: error.javaClass.simpleName}")
    }
    if (manifest.signed && entry.publisher.fingerprint.isNotBlank()) {
        val seen = PluginSignature.fingerprint(manifest.publisherKey)
        if (seen != entry.publisher.fingerprint) {
            file.delete()
            return StartedInstall.Done(
                "装不上: 这份包的发布者指纹 (${seen.take(20)}…) 与索引里说的 " +
                    "(${entry.publisher.fingerprint.take(20)}…) 不是同一把钥匙",
            )
        }
    }
    return startInstall(context, file)
}

/**
 * 装的后半段: 落地 → 按勾选授权 → 把伴侣那一半也装上 → 启用
 *
 * "输入地址, 装完即可用"就是这一串。唯一一处不自动启用的是**伴侣交到了系统安装器手里**那一支 ——
 * 人还在安装器那一页, 什么时候回来不由我们定, 启了也白启
 */
private fun finishInstall(
    context: Context,
    file: java.io.File,
    chosen: Set<String>,
    allowSystemInstaller: Boolean,
): String {
    val outcome = try {
        LwPlugin.installPackage(context, file)
    } catch (error: Throwable) {
        return "装不上: ${error.message ?: error.javaClass.simpleName}"
    } finally {
        file.delete()
    }
    chosen.forEach { capability -> runCatching { PluginManager.grant(context, outcome.id, capability, true) } }
    val granted = PluginManager.granted(context, outcome.id)
    // 自动启用那一条 (方案第 4 节): 能力为空, 或者声明的每一条都已经勾上了 —— 才自动启用;
    // 敏感档留着没勾的那一种不自动启用, 让主人自己勾完再点
    val ready = outcome.declared.all { it in granted }
    return buildString {
        append(LwPlugin.describe(outcome))
        val companion = PluginCompanion.ensure(
            context,
            outcome.entryPackage,
            outcome.entryApk,
            allowSystemInstaller = allowSystemInstaller,
        )
        if (companion.text.isNotBlank()) append("。").append(companion.text)
        // 桌面那一格 (方案 3.1): 它声明了 widget、而那个槽正好空着, 就顺手挂上, 再问一句"要放到桌面吗"
        outcome.widgetSlot?.let { slot ->
            when (WidgetSlots.holder(context, slot)) {
                null -> {
                    WidgetSlots.assign(context, slot, outcome.id)
                    WidgetHost.refresh(context, slot)
                    append("。它已经挂到「").append(WidgetPin.slotLabel(context, slot)).append("」—— ")
                    append(WidgetPin.request(context, slot))
                }
                outcome.id -> Unit
                else -> {
                    append("。它声明的是「").append(WidgetPin.slotLabel(context, slot))
                    append("」, 而那个槽现在挂着别的插件 —— 要换成它就去插件列表里点那两行「桌面组件」")
                }
            }
        }
        when {
            companion.waitingForUser -> append("。装完回到这一页点一下这个插件的「启用」")
            !ready -> append("。它还有能力没勾上, 所以先不自动启用 —— 进它的详情勾上再点「启用」")
            else -> append("。").append(PluginManager.enable(context, outcome.id))
        }
    }
}

/**
 * 装之前那一问 (2026-10-11): 这个包要哪几条能力
 *
 * 普通档默认勾上、敏感档默认不勾 (协议第 5 节)。**只有声明了能力才走到这里** —— 零能力插件装完
 * 即可用, 连这一次问答都没有
 *
 * 正文与插件详情那一个同一条规矩: 自己封一个屏高六成的上限并挂 `verticalScroll`。Miuix 的
 * `OverlayDialog` 自己不滚也没有 `maxHeight`, 22 条能力全列出来会顶穿一屏, 底下那颗按钮就点不到
 */
@Composable
private fun PluginCapabilityDialog(
    manifest: PluginManifest,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val chosen = remember(manifest.id) {
        mutableStateMapOf<String, Boolean>().apply {
            manifest.declared.forEach { capability -> put(capability, !PluginCapabilities.isDangerous(capability)) }
        }
    }
    OverlayDialog(
        show = true,
        title = manifest.name,
        summary = stringResource(R.string.settings_plugin_capabilities_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.6f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            manifest.declared.forEach { capability ->
                SwitchPreference(
                    title = (if (PluginCapabilities.isDangerous(capability)) "★ " else "") + capability,
                    summary = PluginCapabilities.summaryOf(capability) +
                        if (PluginCapabilities.isWired(capability)) {
                            ""
                        } else {
                            " · " + stringResource(R.string.settings_plugin_not_wired)
                        },
                    checked = chosen[capability] == true,
                    onCheckedChange = { on -> chosen[capability] = on },
                )
            }
        }
        TextButton(
            text = stringResource(
                if (busy) R.string.settings_plugin_capabilities_busy else R.string.settings_plugin_capabilities_action,
            ),
            onClick = {
                if (busy) return@TextButton
                haptic.confirm()
                onConfirm(manifest.declared.filter { chosen[it] == true }.toSet())
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                if (busy) return@TextButton
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 粘一个链接进来 (协议第 13 节那条路)
 *
 * 这一页只管把链接收下来: 真正的门槛在下下来之后那一整条链上 (签名 / 逐文件哈希 / 版本 / 前缀 /
 * 未知能力), 所以这里不替它预判什么
 */
@Composable
private fun PluginLinkDialog(
    busy: Boolean,
    title: String,
    summary: String,
    hint: String,
    action: String,
    busyText: String,
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = hint,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = if (busy) busyText else action,
                onClick = {
                    if (!busy && text.isNotBlank()) {
                        haptic.confirm()
                        onInstall(text.trim())
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 官方索引的搜索与安装 (2026-10-16): 读一份 `lw-plugin-index/1`, 在里头搜, 点一下下下来装
 *
 * - **平时只读缓存** ([PluginIndex.cached]), 只有那颗「刷新索引」才联网 —— 与技能目录那条路同一个
 *   分工, 设置页不该被一次慢网卡住
 * - 装之前核整包 sha256 与大小、核发布者指纹 (见 [startIndexInstall]), 其余走与「从链接装入」完全
 *   同一条链 (能力问答 / 伴侣 APK / 桌面槽 / 启用)
 * - 列表把三种"点了也不会装"的状态标在行上: 本机已装 / 需要 Luwi ≥ x / (敏感能力那条只标不拦, 装的
 *   时候那道问答会再问一次)
 */
@Composable
private fun PluginIndexDialog(
    installed: List<PluginManager.Entry>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onAsk: (PendingInstall) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf<PluginIndex.Index?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var installingId by remember { mutableStateOf<String?>(null) }
    // 选中哪一条 (2026-10-15 主人: "点击插件没有反应...改成选择某插件后「完成」按钮变成「安装」"):
    // 点行只做选中, 装不装由底部那颗按钮决定 —— 原来点行就直接开装, 而不可装的行只弹一句, 看着像坏了
    var selected by remember { mutableStateOf<PluginIndex.Entry?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val luwi = remember { PluginIndex.luwiVersion(context) }

    // 进来先读缓存 (一次文件读, 不联网); 只有那颗「刷新」才出网
    LaunchedEffect(Unit) {
        index = withContext(Dispatchers.IO) { PluginIndex.cached(context) }
        loading = false
    }
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                val said = withContext(Dispatchers.IO) { PluginIndex.refresh(context) }
                index = withContext(Dispatchers.IO) { PluginIndex.cached(context) }
                refreshing = false
                onMessage(said)
            }
        }
    }
    val install: (PluginIndex.Entry) -> Unit = { entry ->
        if (installingId == null) {
            haptic.confirm()
            installingId = entry.id
            scope.launch {
                val started = withContext(Dispatchers.IO) { startIndexInstall(context, entry) }
                installingId = null
                when (started) {
                    is StartedInstall.Done -> onMessage(started.text)
                    is StartedInstall.Ask -> onAsk(started.pending)
                }
            }
        }
    }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_plugin_browse),
        summary = stringResource(R.string.settings_plugin_browse_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        SuperTextField(
            modifier = Modifier.padding(bottom = 12.dp),
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.settings_plugin_index_query),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.5f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val candidates = remember(index, query) { index?.let { PluginIndex.search(it, query) } }
            when {
                loading -> PlaceholderItems(stringResource(R.string.settings_plugin_index_loading))
                index == null -> PlaceholderItems(stringResource(R.string.settings_plugin_index_empty))
                candidates.isNullOrEmpty() -> PlaceholderItems(stringResource(R.string.settings_plugin_index_none, query))
            }
            candidates.orEmpty().forEach { entry ->
                val have = installed.firstOrNull { it.id == entry.id }?.version.orEmpty()
                val sameOrNewer = have.isNotBlank() && PluginIndex.compareVersion(have, entry.version) >= 0
                val tooNew = entry.minLw.isNotBlank() && luwi.isNotBlank() &&
                    PluginIndex.compareVersion(luwi, entry.minLw) < 0
                // 这些行要在 composable 作用域里先拼好 (onClick 不是 composable, stringResource 进不去)
                val installedLine = stringResource(R.string.settings_plugin_index_installed, have)
                val minLwLine = stringResource(R.string.settings_plugin_index_minlw, entry.minLw, luwi)
                val meta = stringResource(
                    R.string.settings_plugin_index_meta,
                    entry.kind,
                    entry.publisher.name.ifBlank { "?" },
                    entry.requested.size + entry.dangerous.size,
                ) + if (entry.dangerous.isNotEmpty()) {
                    " · " + stringResource(R.string.settings_plugin_index_danger, entry.dangerous.size)
                } else {
                    ""
                }
                val summary = listOf(meta, entry.description, when {
                    sameOrNewer -> installedLine
                    tooNew -> minLwLine
                    else -> ""
                }).filter { it.isNotBlank() }.joinToString("\n")
                val selectable = !sameOrNewer && !tooNew
                // 选中态: 前面打一个勾 (Miuix 那一层没有"选中"这个参数, 而勾是最省事又看得见的说法)
                val shown = if (selected?.id == entry.id) "\u2713 " else ""
                ArrowPreference(
                    title = shown + listOf(entry.name, entry.version)
                        .filter { it.isNotBlank() }.joinToString(" "),
                    summary = summary,
                    onClick = {
                        haptic.contextClick()
                        // 点一下只是选中; 不能装的那两条当场把原因说出来, 别让人以为是没反应
                        when {
                            sameOrNewer -> onMessage(installedLine)
                            tooNew -> onMessage(minLwLine)
                            else -> {
                                selected = entry
                                onMessage("")
                            }
                        }
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(top = 12.dp)) {
            TextButton(
                text = if (refreshing) {
                    stringResource(R.string.settings_plugin_index_refreshing)
                } else {
                    stringResource(R.string.settings_plugin_index_refresh)
                },
                onClick = refresh,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            // 这一颗按选中项说话: 选了能装的 -> 「安装」, 否则 -> 「完成」(关掉)
            val picked = selected
            val busy = installingId != null
            val action = when {
                busy -> stringResource(R.string.settings_plugin_index_installing)
                picked != null -> stringResource(R.string.settings_plugin_index_install)
                else -> stringResource(R.string.settings_plugin_index_close)
            }
            TextButton(
                text = action,
                onClick = {
                    haptic.contextClick()
                    if (!busy) {
                        if (picked != null) install(picked) else onDismiss()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 一条插件的详情: 能力逐条开关 + 启用停用 + 打开它的界面 + 卸载 (问那一句) + 最近的审计
 *
 * `revision` 传进来是为了"勾一条能力, 这一页上的读数立刻跟着变" —— 每一次改动都由外面把 revision
 * 加一, 于是这里读到的永远是最新那一份
 */
@Composable
private fun PluginDialog(
    id: String,
    revision: Int,
    onDismiss: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 伴侣那一半 (协议第 8.1 节): 清单里带了地址就能在这一页直接补上, 不用再去找那条链接
    var companionSaid by remember { mutableStateOf<String?>(null) }
    var companionBusy by remember { mutableStateOf(false) }
    val entry = remember(revision) { PluginManager.entries(context).firstOrNull { it.id == id } }
    val audit = remember(revision) { PluginAudit.tail(context, id, 5) }

    if (entry == null) {
        onDismiss()
        return
    }

    OverlayDialog(
        show = true,
        title = entry.name,
        summary = entry.manifest?.let { "${it.id} · ${it.version}" } ?: entry.id,
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        // **正文要自己能滚, 而且得封一个上限** (2026-10-08 在设备上量到的那条毛病): 这一份内容会
        // 长过一屏 —— 22 条能力全勾上就是 22 行开关, 底下还压着审计与五个按钮 —— 而 Miuix 的
        // `OverlayDialog` 自己不滚、也没有 maxHeight 那个口子 (参数只有 maxWidth)。不封顶时它按
        // 内容量高度, 超出去的那一截被窗裁掉: 这台 1080x2400 的模拟器上「卸载 (连数据一起删)」与
        // 「卸载 (留下数据)」两个按钮就在屏幕外面, **点都点不到**, 而"卸载要问那一句"是这一段的
        // 验收判据。上限取屏高的六成, 剩下的留给标题、那一行摘要与对话框自己的上下边距
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.6f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (entry.problem != null) {
                PlaceholderItems("${entry.problem}")
            }
            // 伴侣 APK 那一半 (协议第 8.1 节): 工具 / 桌面组件 / 它自己的界面都在那个包里, 而插件包
            // 只说"绑谁" —— 于是"启用着却接不上宿主"最常见的原因就是这一行说的那件事。
            // **清单里带了地址时这一页能直接把它补上** (协议第 4 节的 `entry.apk`): 老包没带地址,
            // 这一行还是只报状态, 去插件那一段粘一个链接
            val manifestHere = entry.manifest
            manifestHere?.entryPackage?.takeIf { it.isNotBlank() }?.let { companion ->
                val version = CompanionApk.installed(context, companion)
                PlaceholderItems(
                    companionSaid ?: if (version == null) {
                        context.getString(R.string.settings_plugin_companion_missing, companion)
                    } else {
                        context.getString(R.string.settings_plugin_companion_installed, companion, version)
                    },
                )
                val apkUrl = manifestHere.entryApk
                if (version == null && apkUrl.isNotBlank()) {
                    TextButton(
                        text = stringResource(
                            if (companionBusy) R.string.settings_plugin_companion_busy
                            else R.string.settings_plugin_companion_install,
                        ),
                        onClick = {
                            if (companionBusy) return@TextButton
                            haptic.confirm()
                            companionBusy = true
                            scope.launch {
                                val said = withContext(Dispatchers.IO) {
                                    val done = PluginCompanion.ensure(
                                        context,
                                        companion,
                                        apkUrl,
                                        allowSystemInstaller = true,
                                    )
                                    done.text.ifBlank {
                                        context.getString(
                                            R.string.settings_plugin_companion_installed,
                                            companion,
                                            CompanionApk.installed(context, companion).orEmpty(),
                                        )
                                    }
                                }
                                companionBusy = false
                                companionSaid = said
                                onChanged(said)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            entry.manifest?.let { manifest ->
                manifest.declared.forEach { declared ->
                    val wired = PluginCapabilities.isWired(declared)
                    val dangerous = PluginCapabilities.isDangerous(declared)
                    SwitchPreference(
                        title = (if (dangerous) "★ " else "") + declared,
                        summary = buildString {
                            append(PluginCapabilities.summaryOf(declared))
                            if (!wired) append(" · ").append(context.getString(R.string.settings_plugin_not_wired))
                        },
                        checked = declared in entry.granted,
                        onCheckedChange = { on ->
                            onChanged(PluginManager.grant(context, id, declared, on))
                        },
                    )
                }
            }

            // `inline` 域那两行 (方案 3.3): "它与 Luwi 同权限"与"它的作者在白名单里"**分两行写**,
            // 不合成一句 —— 第一行是告知, 第二行才是那条真正的闸
            manifestHere?.takeIf { it.kind == "inline" }?.let { manifest ->
                PlaceholderItems(context.getString(R.string.settings_plugin_inline_same_uid))
                val identity = if (manifest.signed) PluginSignature.fingerprint(manifest.publisherKey) else ""
                PlaceholderItems(
                    if (identity.isNotBlank() && PluginTrust.allowed(context, identity)) {
                        context.getString(R.string.settings_plugin_inline_whitelisted, identity)
                    } else {
                        "它的作者不在白名单里 —— 这一份是开发者模式下装进来的本地包"
                    },
                )
            }

            // 迁移那一条 (方案第 6 节): 它以前那个伴侣 APK 现在用不上了 —— 只说不做, 卸不卸由主人定
            PluginStore.leftover(context, id)?.let { old ->
                PlaceholderItems("它以前那个伴侣应用 $old 已经用不上了, 可以卸掉 (这一页不会自动动它)")
            }

            TextButton(
                text = stringResource(
                    if (entry.enabled) R.string.settings_plugin_disable else R.string.settings_plugin_enable,
                ),
                onClick = {
                    haptic.confirm()
                    // 绑宿主那一步是阻塞的 (绑 Service / 等 :plugin 进程回话), 而这里是主线程 ——
                    // 放 IO 上做, 不然 onServiceConnected 那个回调排不进来, 每一次都白等五秒
                    scope.launch {
                        val said = withContext(Dispatchers.IO) {
                            if (entry.enabled) {
                                PluginManager.disable(context, id)
                            } else {
                                PluginManager.enable(context, id)
                            }
                        }
                        onChanged(said)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            // 「打开它自带的界面」只对 companion 画 (方案 3.4: 只有伴侣是"另一个应用") ——
            // 以前它对每一档都画, art / inline 点下去只回一句"这一档没有自带的界面", 那是个空按钮
            if (manifestHere?.kind == "companion") {
                TextButton(
                    text = stringResource(R.string.settings_plugin_open),
                    onClick = {
                        haptic.confirm()
                        scope.launch {
                            onChanged(withContext(Dispatchers.IO) { PluginManager.open(context, id) })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PlaceholderItems(
                stringResource(R.string.settings_plugin_audit) + "\n" +
                    audit.joinToString("\n").ifBlank { stringResource(R.string.settings_plugin_audit_empty) },
            )
            TextButton(
                text = stringResource(R.string.settings_plugin_uninstall),
                onClick = {
                    haptic.confirm()
                    scope.launch {
                        onChanged(withContext(Dispatchers.IO) { PluginManager.uninstall(context, id, keepData = false) })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.settings_plugin_uninstall_keep),
                onClick = {
                    haptic.confirm()
                    scope.launch {
                        onChanged(withContext(Dispatchers.IO) { PluginManager.uninstall(context, id, keepData = true) })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 一个宿主槽的对话框 (方案 3.1): 挂哪一只插件 / 清空 / 直接把它放到桌面
 *
 * **同槽互斥**在这里看得见: 候选是"声明了这个槽的每一只插件", 而同一时刻只有一只在槽上 ——
 * 换一只只重画, 另一只仍然留在插件列表里, 这是设计不是缺陷
 */
@Composable
private fun WidgetSlotDialog(
    slot: String,
    revision: Int,
    onDismiss: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val entries = remember(revision) { PluginManager.entries(context) }
    val holder = remember(revision) { WidgetSlots.holder(context, slot) }
    // 候选 = 声明了这个槽的插件; 现在挂着的那一份无论如何都留着, 免得"挂着谁"与列表对不上
    val candidates = entries.filter { it.manifest?.widget?.slot == slot || it.id == holder }
    OverlayDialog(
        show = true,
        title = WidgetPin.slotLabel(context, slot),
        summary = stringResource(R.string.settings_widget_section_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.5f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ArrowPreference(
                title = stringResource(R.string.settings_widget_vacant_choice),
                summary = if (holder == null) "现在就是空着" else "把这一格清空",
                onClick = {
                    haptic.contextClick()
                    val said = WidgetSlots.assign(context, slot, null)
                    WidgetHost.refresh(context, slot)
                    onChanged(said)
                },
            )
            if (candidates.isEmpty()) {
                PlaceholderItems("还没有插件声明这个槽 —— 装一只带 widget 的插件 (art 域最省事), 它就会出现在这一行")
            }
            candidates.forEach { entry ->
                val assigned = entry.id == holder
                ArrowPreference(
                    title = entry.name,
                    summary = buildList {
                        add(if (assigned) "现在挂着它" else "挂到这一格")
                        add(if (entry.enabled) "在岗" else "停着")
                    }.joinToString(" · "),
                    onClick = {
                        haptic.confirm()
                        val said = WidgetSlots.assign(context, slot, entry.id)
                        WidgetHost.refresh(context, slot)
                        onChanged(said)
                    },
                )
            }
        }
        TextButton(
            text = stringResource(R.string.settings_widget_pin),
            onClick = {
                haptic.confirm()
                onChanged(WidgetPin.request(context, slot))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 浮标外观那个对话框 (协议 10.1 / 方案 3.2)
 *
 * 候选 = 装着 `theme/ball.json` 的那几份插件。**多个可以同时装着, 同一时刻只生效一个**;
 * 读不到就回内置画法 —— 那一句写在摘要里, 因为它是这一档最容易让人误以为坏了的地方
 */
@Composable
private fun BallThemeDialog(
    revision: Int,
    onDismiss: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val current = remember(revision) { BallTheme.currentId(context) }
    // 候选那一份口径住在 [BallTheme.choices] 里 (2026-10-16 起球菜单那行「换个形象」与这里是同一份):
    // 第 0 位是内置画法, 它在下面有自己单独的一行, 所以这里丢掉它
    val candidates = remember(revision) { BallTheme.choices(context).drop(1) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_theme_row),
        summary = stringResource(R.string.settings_theme_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.5f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ArrowPreference(
                title = stringResource(R.string.settings_theme_builtin),
                summary = if (current.isBlank()) {
                    stringResource(R.string.settings_theme_now)
                } else {
                    stringResource(R.string.settings_theme_use)
                },
                onClick = {
                    haptic.contextClick()
                    BallTheme.choose(context, null)
                    onChanged("已经换回内置画法")
                },
            )
            if (candidates.isEmpty()) {
                PlaceholderItems(stringResource(R.string.settings_theme_empty))
            }
            candidates.forEach { entry ->
                ArrowPreference(
                    title = entry.name,
                    summary = if (entry.id == current) {
                        stringResource(R.string.settings_theme_now)
                    } else {
                        stringResource(R.string.settings_theme_use)
                    },
                    onClick = {
                        haptic.confirm()
                        BallTheme.choose(context, entry.id)
                        onChanged("已经把浮标外观换成「${entry.name}」")
                    },
                )
            }
        }
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 把一句话投给会话
 *
 * 回的是**给人看的那一句**: 投出去了 (给了序号) 与没投出去 (磁盘满 / 目录建不出来) 是两件事, 这一页
 * 上要一眼看得出是哪一件
 */
private fun sendQuickCommand(context: Context, line: String): String {
    val seq = VoiceInbox.append(context, line, source = VoiceInbox.SOURCE_QUICK)
    return if (seq == null) {
        context.getString(R.string.settings_quick_send_failed)
    } else {
        context.getString(R.string.settings_quick_sent)
    }
}

/** 删掉一条 (名字是从这一页列出来的, 所以已经是规范化的那一个) */
private fun deleteQuickCommand(context: Context, name: String): String = try {
    val file = java.io.File(LwQuick.directory(context), "$name.md")
    if (file.delete()) {
        context.getString(R.string.settings_quick_deleted, name)
    } else {
        context.getString(R.string.settings_quick_delete_failed, name)
    }
} catch (problem: Throwable) {
    context.getString(R.string.settings_quick_delete_failed, name)
}

/** 点一条快捷指令之后那一问: 运行它, 还是把它删掉 */
@Composable
private fun QuickActionDialog(
    name: String,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = name,
        summary = stringResource(R.string.settings_quick_action_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.settings_quick_run),
            onClick = {
                haptic.confirm()
                onRun()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        TextButton(
            text = stringResource(R.string.settings_quick_delete),
            onClick = {
                haptic.confirm()
                onDelete()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 新建那一问: 只要一行"这条快捷指令要做什么" */
@Composable
private fun QuickCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_quick_new_title),
        summary = stringResource(R.string.settings_quick_new_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.settings_quick_new_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_quick_create),
                onClick = {
                    // 空的一行什么都不做: 投出去也只是一句"请把这件事做成快捷指令:" —— 没有内容
                    if (text.isNotBlank()) {
                        haptic.confirm()
                        onCreate(text)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 六个监测器的名字: 与 `AutomationRule.WHEN_KINDS` 一对一 (顺序就是那一份的顺序) */
private val AutomationMonitorTitles = linkedMapOf(
    "notice" to R.string.settings_automation_monitor_notice,
    "foreground" to R.string.settings_automation_monitor_foreground,
    "light" to R.string.settings_automation_monitor_light,
    "time" to R.string.settings_automation_monitor_time,
    "place" to R.string.settings_automation_monitor_place,
    "weather" to R.string.settings_automation_monitor_weather,
)

/**
 * 「自动指令」那一段 (2.5.0 批次 8)
 *
 * 这一页**只读规则、只动开关**: 正文是模型写的 JSON, 所以"新建"与"改一改"都是往会话里投一句话
 * (与批次 7 的快捷指令同形), 界面里不做条件/参数表单
 *
 * 三行读数在这一页上最要紧, 因为"它怎么没响"是一个没有读数就答不出来的问题:
 *
 * - 最上面那一行总账: 几条规则、今天响了几次、此刻什么拦着它 (省电 / 静默 / 无障碍 / 精确闹钟)
 * - 每一行规则: 条件 → 动作, 今天几次、上限几次, 上次什么时候响的
 * - 六个监测器逐行: 此刻能不能用 (缺哪条权限、这台设备有没有光感、无障碍关着)
 *
 * 规则删掉或关掉, 下一拍监测器就跟着摘 —— "没启用的规则一个都不注册"那句口径的落点就在这里
 */
@Composable
private fun AutomationItems() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var revision by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var editingQuiet by rememberSaveable { mutableStateOf(false) }
    var acting by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    // 那条"直接改冷却"的路 (2026-10-09 主人: 由用户决定冷却闸多少时间)
    var cooling by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // 这一页上的每一次改动都让 revision 变一下, 上面那几份读出来时自然就重读了
    val entries = remember(revision) { LwAutomation.entries(context) }
    val rules = entries.mapNotNull { it.rule }
    val settings = remember(revision) { LwAutomation.settings(context) }
    val overview = remember(revision) { LwAutomation.overview(context) }
    val monitors = remember(revision) { LwAutomation.monitorStates(context) }
    val history = remember(revision) { LwAutomation.history(context, 10) }

    fun save(next: AutomationStore.Settings) {
        LwAutomation.saveSettings(context, next)
        revision += 1
    }

    PlaceholderItems(overview)

    if (entries.isEmpty()) {
        PlaceholderItems(
            stringResource(
                R.string.settings_automation_empty,
                LwAutomation.directory(context).absolutePath,
            ),
        )
    } else {
        entries.forEach { entry ->
            val rule = entry.rule
            if (rule == null) {
                PlaceholderItems(
                    stringResource(R.string.settings_automation_broken, entry.problem.orEmpty()),
                )
            } else {
                val parts = mutableListOf(rule.summary())
                parts += context.getString(
                    R.string.settings_automation_rule_state,
                    AutomationEngine.firedTodayCount(entry.name),
                    rule.dailyLimit,
                )
                // 冷却也要摆在规则那一行上: 它是主人自己定的那个数, 而"它怎么又响了 / 怎么不响"看的就是它
                parts += if (rule.cooldownMinutes <= 0) {
                    context.getString(R.string.settings_automation_rule_no_cooldown)
                } else {
                    context.getString(R.string.settings_automation_rule_cooldown, rule.cooldownMinutes)
                }
                AutomationEngine.lastFiredAt(entry.name)?.let { at ->
                    parts += context.getString(
                        R.string.settings_automation_last,
                        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at)),
                    )
                }
                SwitchPreference(
                    title = entry.name,
                    summary = parts.joinToString(" · "),
                    checked = rule.enabled,
                    onCheckedChange = { on ->
                        LwAutomation.setEnabled(context, entry.name, on)
                        revision += 1
                    },
                )
            }
        }
    }

    ArrowPreference(
        title = stringResource(R.string.settings_automation_new),
        summary = stringResource(R.string.settings_automation_new_summary),
        onClick = {
            haptic.contextClick()
            creating = true
        },
    )
    if (rules.isNotEmpty()) {
        ArrowPreference(
            title = stringResource(R.string.settings_automation_manage),
            summary = stringResource(R.string.settings_automation_manage_summary),
            onClick = {
                haptic.contextClick()
                managing = true
            },
        )
    }

    PlaceholderItems(stringResource(R.string.settings_automation_monitors))
    AutomationMonitorTitles.forEach { (kind, title) ->
        SwitchPreference(
            title = stringResource(title),
            summary = monitors.firstOrNull { it.first == kind }?.second.orEmpty(),
            checked = settings.monitor(kind),
            onCheckedChange = { on ->
                save(settings.copy(monitors = settings.monitors + (kind to on)))
            },
        )
    }
    // 频率那三个数都能改: 它们是"低功耗"与"及时"之间那根绳子, 而主人自己知道要哪一头
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_automation_weather_every),
        summary = stringResource(R.string.settings_automation_weather_every_summary),
        items = AutomationStore.WEATHER_CHOICES.map {
            context.getString(R.string.settings_automation_every_minutes, it)
        },
        selectedIndex = AutomationStore.WEATHER_CHOICES.indexOf(settings.weatherMinutes)
            .coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            save(settings.copy(weatherMinutes = AutomationStore.WEATHER_CHOICES[index]))
        },
    )
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_automation_place_every),
        summary = stringResource(R.string.settings_automation_place_every_summary),
        items = AutomationStore.PLACE_MINUTE_CHOICES.map {
            context.getString(R.string.settings_automation_every_minutes, it)
        },
        selectedIndex = AutomationStore.PLACE_MINUTE_CHOICES.indexOf(settings.placeMinutes)
            .coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            save(settings.copy(placeMinutes = AutomationStore.PLACE_MINUTE_CHOICES[index]))
        },
    )
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_automation_place_meters),
        summary = stringResource(R.string.settings_automation_place_meters_summary),
        items = AutomationStore.PLACE_METER_CHOICES.map {
            context.getString(R.string.settings_automation_every_meters, it)
        },
        selectedIndex = AutomationStore.PLACE_METER_CHOICES.indexOf(settings.placeMeters)
            .coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            save(settings.copy(placeMeters = AutomationStore.PLACE_METER_CHOICES[index]))
        },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_automation_quiet),
        summary = stringResource(R.string.settings_automation_quiet_summary),
        checked = settings.quietEnabled,
        onCheckedChange = { save(settings.copy(quietEnabled = it)) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_automation_quiet_title),
        summary = settings.quietWindow,
        onClick = {
            haptic.contextClick()
            editingQuiet = true
        },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_automation_allow_acting),
        summary = stringResource(R.string.settings_automation_allow_acting_summary),
        checked = settings.allowActing,
        onCheckedChange = { save(settings.copy(allowActing = it)) },
    )

    PlaceholderItems(stringResource(R.string.settings_automation_history))
    if (history.isEmpty()) {
        PlaceholderItems(stringResource(R.string.settings_automation_history_empty))
    } else {
        PlaceholderItems(history.joinToString("\n") { AutomationStore.line(it) })
    }
    PlaceholderItems(message ?: stringResource(R.string.settings_automation_note))

    if (creating) {
        AutomationCreateDialog(
            onDismiss = { creating = false },
            onCreate = { said, cooldownMinutes ->
                creating = false
                message = sendAutomationSetup(
                    context,
                    context.getString(R.string.settings_automation_create_prompt, said, cooldownMinutes),
                )
            },
        )
    }

    if (managing) {
        AutomationPickDialog(
            names = rules.map { it.name },
            onDismiss = { managing = false },
            onPick = { name ->
                managing = false
                acting = name
            },
        )
    }

    acting?.let { name ->
        AutomationActionDialog(
            name = name,
            onDismiss = { acting = null },
            onRun = {
                acting = null
                message = LwAutomation.fireNow(context, name)
                revision += 1
            },
            onEdit = {
                acting = null
                editing = name
            },
            onCooldown = {
                acting = null
                cooling = name
            },
            onDelete = {
                acting = null
                message = if (LwAutomation.delete(context, name)) {
                    context.getString(R.string.settings_automation_deleted, name)
                } else {
                    context.getString(R.string.settings_automation_delete_failed, name)
                }
                revision += 1
            },
        )
    }

    editing?.let { name ->
        AutomationEditDialog(
            name = name,
            onDismiss = { editing = null },
            onEdit = { said ->
                editing = null
                message = sendAutomationSetup(
                    context,
                    context.getString(R.string.settings_automation_edit_prompt, name, said),
                )
            },
        )
    }

    cooling?.let { name ->
        val current = rules.firstOrNull { it.name == name }?.cooldownMinutes
            ?: AutomationRule.DEFAULT_COOLDOWN_MINUTES
        AutomationCooldownDialog(
            current = current,
            onDismiss = { cooling = null },
            onSave = { minutes ->
                cooling = null
                message = if (LwAutomation.setCooldown(context, name, minutes)) {
                    context.getString(R.string.settings_automation_cooldown_saved, minutes)
                } else {
                    context.getString(R.string.settings_automation_cooldown_failed)
                }
                revision += 1
            },
        )
    }

    if (editingQuiet) {
        AutomationQuietDialog(
            current = settings.quietWindow,
            onDismiss = { editingQuiet = false },
            onSave = { text ->
                editingQuiet = false
                if (PowerWindow.parse(text) == null) {
                    message = context.getString(R.string.settings_automation_quiet_bad)
                } else {
                    save(settings.copy(quietWindow = text.trim()))
                    message = null
                }
            },
        )
    }
}

/**
 * 新建 / 改一改投出去的那一句: 与自动指令自己响的那条路**分开一个来源**
 *
 * 响的那一条 (`SOURCE_AUTOMATION`) 宿主会新开一场会话; 而"帮我写一条规则"这件事要和批次 7 的快捷
 * 指令一样落在主人此刻看着的那一场里 —— 写规则的过程主人要看得见
 */
private fun sendAutomationSetup(context: Context, line: String): String {
    val seq = VoiceInbox.append(context, line, source = VoiceInbox.SOURCE_AUTOMATION_SETUP)
    return if (seq == null) {
        context.getString(R.string.settings_automation_send_failed)
    } else {
        context.getString(R.string.settings_automation_sent)
    }
}

/**
 * 新建那一问: 一行"什么时候、要做什么", 加上主人自己定的冷却
 *
 * 冷却那一个数是 2026-10-09 主人加进来的 ("由用户决定冷却闸多少时间后再次触发") —— 它随那一句话一起
 * 交给模型 (提示词里点名 `cooldownMinutes: N`), 而写完之后还能在「删掉或改一改一条」里直接改,
 * 所以模型万一没照写, 主人也不必再说一遍
 */
@Composable
private fun AutomationCreateDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_automation_new_title),
        summary = stringResource(R.string.settings_automation_new_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        var cooldown by rememberSaveable { mutableStateOf(AutomationRule.DEFAULT_COOLDOWN_MINUTES.toString()) }
        val minutes = AutomationRule.coerceCooldown(
            cooldown.trim().toIntOrNull() ?: AutomationRule.DEFAULT_COOLDOWN_MINUTES,
        )
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.settings_automation_new_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        // 冷却: 只收数字, 最多四位 (1440 就是上限), 空着或超出范围都收到那一份边界里
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = cooldown,
            onValueChange = { typed -> cooldown = typed.filter { it.isDigit() }.take(4) },
            label = stringResource(R.string.settings_automation_cooldown_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_automation_create),
                onClick = {
                    // 空的一行什么都不做: 投出去也只是一句"请把这件事做成自动指令:" —— 没有内容
                    if (text.isNotBlank()) {
                        haptic.confirm()
                        onCreate(text, minutes)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 先选一条: 规则多了之后, 一行一行摆四个动作会把这一段撑得很长 */
@Composable
private fun AutomationPickDialog(
    names: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_automation_manage_title),
        summary = stringResource(R.string.settings_automation_pick_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        names.take(MAX_PICKED_RULES).forEach { name ->
            TextButton(
                text = name,
                onClick = {
                    haptic.confirm()
                    onPick(name)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 一条规则能做什么: 立刻跑一次 / 改冷却 / 改一改 / 删掉 */
@Composable
private fun AutomationActionDialog(
    name: String,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onCooldown: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = name,
        summary = stringResource(R.string.settings_automation_open_title),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.settings_automation_run),
            onClick = {
                haptic.confirm()
                onRun()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        TextButton(
            text = stringResource(R.string.settings_automation_edit),
            onClick = {
                haptic.confirm()
                onEdit()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.settings_automation_cooldown),
            onClick = {
                haptic.confirm()
                onCooldown()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.settings_automation_delete),
            onClick = {
                haptic.confirm()
                onDelete()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 改一改那一问: 只要一行"要怎么改" */
@Composable
private fun AutomationEditDialog(name: String, onDismiss: () -> Unit, onEdit: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_automation_edit_title),
        summary = stringResource(R.string.settings_automation_edit_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.settings_automation_edit_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_automation_edit),
                onClick = {
                    if (text.isNotBlank()) {
                        haptic.confirm()
                        onEdit(text)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 改冷却那一问: **不经过模型的直改** (2026-10-09 主人)
 *
 * 同一条规则两次触发之间至少隔这么久, 数是主人当场按的, 所以它落盘就是最终值 —— 模型那条路只在
 * **新建**时用一次 (把数写进提示词), 而这里读出现有那份 JSON 只换 `cooldownMinutes` 一个键
 */
@Composable
private fun AutomationCooldownDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_automation_cooldown_title),
        summary = stringResource(R.string.settings_automation_cooldown_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        var text by rememberSaveable { mutableStateOf(current.toString()) }
        val minutes = AutomationRule.coerceCooldown(text.trim().toIntOrNull() ?: current)
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { typed -> text = typed.filter { it.isDigit() }.take(4) },
            label = stringResource(R.string.settings_automation_cooldown_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_automation_edit),
                onClick = {
                    haptic.confirm()
                    onSave(minutes)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 静默时段那一问: 与省电时段同一个写法 (纯文本, 解析是 `PowerWindow` 的事) */
@Composable
private fun AutomationQuietDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_automation_quiet_title),
        summary = stringResource(R.string.settings_automation_quiet_dialog_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        var text by rememberSaveable { mutableStateOf(current) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.settings_automation_quiet_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onSave(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 一次最多列出几条规则 (再多就该去会话里让模型删了) */
private const val MAX_PICKED_RULES = 12

/**
 * 权限那一段
 *
 * 每一条是一个能力, 不是一个权限名: 模型问的是"能不能发通知", 而不是"有没有 POST_NOTIFICATIONS"。
 * 状态每次重进这一页现读 (用户可能在系统设置页里改过), 所以 [PermissionCatalog] 的答案不缓存
 *
 * 两条路: 能点名要的直接弹系统框 (`PermissionRequests` -> MainActivity 里的 launcher), 只能去系
 * 统页点的就开那一页。**没有第三步** —— 这里不做"假装已授权"
 */
@Composable
private fun PermissionsItems() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // 申请的结果回来要重画, 所以留一个能变的记号
    var revision by remember { mutableStateOf(0) }
    val runtime = PermissionCatalog.runtime
    val special = PermissionCatalog.special

    // 从系统设置页回来时把每一条重读一遍
    //
    // 这一步是给"安装未知应用"那类权限用的: 它的开关在系统那一页上 (设置 → 安装未知应用),
    // **应用这边读的是 canRequestPackageInstalls() 的实时值**, 但只在重组时读 —— 用户去那一页把开关
    // 打开再回来, 页面不重组, 于是显示的还是旧状态。ON_RESUME 正好是"从别处回来"的那一刻
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 这一段自己是纯文本, 不像设置项那样自带内边距, 所以**自己缩进**:
    //   左 16dp 与设置项的标题对齐, 上 16dp 让第一行离开卡片的圆角 —— 圆角是按卡片边裁的, 文字贴着
    //   左上角时前几个字会被那道弧切掉 (实测就是这样)
    // 颜色用主题里的次要文本档, 不写死灰度: 它在浅色与深色下各自是对的那一支
    Text(
        text = stringResource(R.string.settings_permissions_summary),
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(
            start = UiSpacing.Large,
            end = UiSpacing.Large,
            top = UiSpacing.Large,
            bottom = UiSpacing.Medium,
        ),
    )
    (runtime + special).forEach { capability ->
        // revision 变了就重算: 这是"授权之后状态要跟着变"的那一处
        val state = remember(revision, capability) { PermissionCatalog.state(context, capability) }
        ArrowPreference(
            title = capability.name,
            // 副标题是"状态 · 它是干什么的"。**但有一条例外**: 「安装未知应用」这台设备上读出来的值
            // (canRequestPackageInstalls) 与系统页上看到的对不上, 与其在屏幕上放一个会和系统打架的
            // 结论, 那一条只留说明 —— 点一下就是去它自己那一页, 那里才是权威
            summary = if (capability.noState) {
                capability.note ?: capability.why
            } else {
                buildString {
                    append(
                        when (state) {
                            Grant.GRANTED -> stringResource(R.string.settings_permission_granted)
                            Grant.DENIED -> stringResource(R.string.settings_permission_denied)
                            Grant.MISSING -> stringResource(R.string.settings_permission_missing)
                        },
                    )
                    append(" · ")
                    append(capability.note ?: capability.why)
                }
            },
            onClick = {
                // 特殊访问只能开系统页; 能点名的才弹框。`ask` 返回 false 有两种情况 —— 特殊访问,
                // 或者用户已经"拒绝且不再问", 两种都只能让它去设置页
                //
                // 三级回退在 `openSettings` 里: 它自己那页 → 应用详情页 → 都没有就返回 false。
                // 最后那一种**不再弹提示**: 一句解释性文案换不来一次跳转, 而它已经删掉了
                if (!(activity != null && PermissionGate.ask(activity, capability))) {
                    PermissionGate.openSettings(context, capability)
                }
                // 系统框是异步的, 这一下是把"点了之后可能已经变了"重算一次; 真正的答案回来时
                // PermissionRequests.pending 会变, 那时再重算一次
                revision++
            },
        )
    }
}

/** 无障碍读屏: 模型按名字操作, 而不是在缩过的截图上量坐标 */
@Composable
private fun AccessibilityItems() {
    val context = LocalContext.current
    val working = AccessibilitySetting.working
    LaunchedEffect(Unit) { AccessibilitySetting.refresh() }
    SwitchPreference(
        title = stringResource(R.string.settings_accessibility),
        summary = stringResource(R.string.settings_accessibility_summary),
        checked = AccessibilitySetting.enabled,
        enabled = !working,
        onCheckedChange = { AccessibilitySetting.set(it) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_accessibility_manual),
        onClick = {
            context.startActivity(accessibilitySettingsIntent())
        },
    )
}

/** 系统那页, 万一特权通道写不进去还有一个能手动开的地方 */
private fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 通知栏的读数: 与无障碍那一段同一个形状
 *
 * 这一段还兼着一件事: **横幅 (全屏通知) 的授权也是在这一页说明的**。从 Android 14 起它不是装完就有
 * 的 —— 非闹钟/通话类的应用要人去「特殊应用权限」里手动开 —— 所以那件事的状态跟着这一段一起显示,
 * 而 `lw_notify` 也会自己说一遍它现在到底会不会弹成横幅
 */
@Composable
private fun NotificationItems() {
    val context = LocalContext.current
    val working = NotificationSetting.working
    LaunchedEffect(Unit) { NotificationSetting.refresh() }
    SwitchPreference(
        title = stringResource(R.string.settings_notifications),
        summary = stringResource(R.string.settings_notifications_summary),
        checked = NotificationSetting.enabled,
        enabled = !working,
        onCheckedChange = { NotificationSetting.set(it) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_notifications_manual),
        onClick = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            // 那一页在个别 ROM 上没有接收者 (`startActivity` 会抛, 而这是一个点击换来的崩溃), 所以
            // 与横幅那条一样退到应用详情页
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!opened) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
    // 横幅是另一道授权, 而且系统把它藏在「特殊应用权限」里, 从这一页直接跳过去省得找
    ArrowPreference(
        title = stringResource(R.string.settings_notifications_banner),
        onClick = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            val opened = runCatching {
                context.startActivity(
                    Intent(FULL_SCREEN_INTENT_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            // 那一页要 API 34 才有, 低版本或没有它的 ROM 退到应用详情页 (权限开关都在上面)
            if (!opened) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/** 全屏通知 (横幅) 那一页, API 34 起才有 */
private const val FULL_SCREEN_INTENT_SETTINGS = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

/**
 * 相机占用表那一行多久重读一次 (毫秒)
 *
 * 它只在「视频识别」那一段**展开着**的时候跑 (收起 / 离开这一页, effect 就跟着取消), 所以这不是一条
 * 常驻轮询; 两秒是"人点完释放之后那一行立刻跟着变"与"读一个几十字节的文件"之间的折中
 */
private const val OWNER_POLL_MS = 2_000L

/**
 * 视频模式取景那三条: 每张间隔、截图张数、截图清晰度
 *
 * 与上面「截图」那两条是**两笔账**: 那两条管的是 `lw_screenshot` 那条路 (整屏画面缩到多少像素),
 * 而这三条管的是视频模式那一台**自己开的相机** (见 `LwCamera` 与 `VideoLooks`): 抓几张 JPEG 帧、
 * 两张之间隔多久、抓帧按多大挑尺寸
 *
 * 三条的形状各不相同, 理由是它们要对的数字性质不一样:
 *
 * - **间隔**连着滑 (中间那些值都能用), 吸附点只是帮着停到好看的几个数上; 它说的是"墙钟上两张之间
 *   隔多久", 所以摘要里写着"这是要的那个数, 不是做到的那个数" —— 抓一张本身就要两三百毫秒
 * - **张数**是整数档 (1..12), 与 `lw_look` 那个 `frames` 参数同一个上限; 它就是"缺省"这个意思
 * - **清晰度**是三档 (与上面像素那条同一个道理): 每一档要对的是一张图交出去之后的处理预算, 中间的
 *   值没有对应的预算可言。它要**重开相机**才生效, 所以拖完立刻让相机重开一次 (`camera op=rule`) ——
 *   不然"我改了清晰度而它还是老样子"会是一个说不清的中间态
 */
@Composable
private fun LookItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val levels = VideoLooks.levels
    val last = levels.lastIndex
    val levelNames = levels.map { stringResource(it.first, it.second) }
    // 相机占用表 (批次 3 的需求 3): 现在哪一场会话占着视频模式那台相机。判据在宿主插件那一侧 (它才
    // 拿得到会话 id), 这里只显示它 —— 外加**手动强制释放**这条出口: 表卡住时 (一场崩了而它的
    // `turn/end` 没来) 这是唯一能让相机回到手里的地方
    //
    // 表是一个文件, 没法用 Compose 状态观察, 所以这一段**展开着的时候**每两秒读一次, 收起或离开这一页
    // 就停 (effect 跟着 composable 一起取消)。这一行说的必须与闸判的是同一件事, 否则会出现"界面上说
    // 有人占着而取景根本不拦"这种最费解的状态
    var owner by remember { mutableStateOf(CameraOwner.read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(OWNER_POLL_MS)
            owner = CameraOwner.read(context)
        }
    }
    ArrowPreference(
        title = stringResource(R.string.settings_look_owner),
        summary = owner?.let {
            stringResource(
                R.string.settings_look_owner_busy,
                CameraOwner.short(it.sessionId),
                CameraOwner.clock(it.since),
            )
        } ?: stringResource(R.string.settings_look_owner_free),
        onClick = {
            CameraOwner.release(context)
            owner = CameraOwner.read(context)
        },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_look_sheet),
        summary = stringResource(R.string.settings_look_sheet_summary),
        checked = VideoLooks.sheet,
        onCheckedChange = { VideoLooks.setSheet(context, it) },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_look_interval),
        summary = stringResource(R.string.settings_look_interval_summary, VideoLooks.intervalMs),
        value = VideoLooks.intervalMs.toFloat(),
        onValueChange = { VideoLooks.setInterval(context, it.roundToInt()) },
        valueRange = VideoLooks.intervalRange,
        // 连续滑动: 吸附点只帮着停到常见的那几个数上, 中间的值照样给
        steps = 0,
        showKeyPoints = true,
        keyPoints = VideoLooks.intervalKeyPoints,
        magnetThreshold = VideoLooks.INTERVAL_MAGNET,
        unit = "ms",
        inputSummary = stringResource(
            R.string.settings_look_interval_dialog,
            VideoLooks.intervalRange.start.toInt(),
            VideoLooks.intervalRange.endInclusive.toInt(),
        ),
        inputLabel = "ms",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = VideoLooks.intervalRange,
        onInputConfirm = { raw -> raw.toIntOrNull()?.let { VideoLooks.setInterval(context, it) } },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_look_count),
        summary = stringResource(R.string.settings_look_count_summary, VideoLooks.count),
        value = VideoLooks.count.toFloat(),
        onValueChange = { VideoLooks.setCount(context, it.roundToInt()) },
        valueRange = VideoLooks.countRange,
        // 整数: 两端之间每一个数都是一个可选值 (Compose 那个 steps 的算法)
        steps = (VideoLooks.countRange.endInclusive.toInt() - 2).coerceAtLeast(0),
        showKeyPoints = false,
        inputLabel = "frames",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = VideoLooks.countRange,
        onInputConfirm = { raw -> raw.toIntOrNull()?.let { VideoLooks.setCount(context, it) } },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_look_quality),
        summary = stringResource(R.string.settings_look_quality_summary, VideoLooks.pixels),
        value = VideoLooks.level.toFloat(),
        onValueChange = { VideoLooks.setLevel(context, it.roundToInt().coerceIn(0, last)) },
        valueRange = 0f..last.toFloat(),
        steps = (levels.size - 2).coerceAtLeast(0),
        showKeyPoints = true,
        keyPoints = levels.indices.map { it.toFloat() },
        displayFormatter = { levelNames[it.roundToInt().coerceIn(0, last)] },
        inputSummary = levelNames.joinToString(" / "),
        inputLabel = "px",
        inputInitialValue = VideoLooks.pixels.toString(),
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = levels.first().second.toFloat()..levels.last().second.toFloat(),
        onInputConfirm = { raw ->
            val typed = raw.toIntOrNull() ?: 0
            VideoLooks.setLevel(context, levels.indices.minBy { abs(levels[it].second - typed) })
        },
        // 改完立刻让相机按新的一档重开: 抓帧的尺寸是开相机那一刻定死的, 不重开就要等下一次
        onValueChangeFinished = { scope.launch { LwCamera.reapply(context) } },
    )
    // 一段话说明这一半与那一半: 与上面截图那两条同一个口径 (超预算的图会在 host 那边重编码一次)
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = stringResource(R.string.settings_look_note),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 一屏画面交给模型之前要过的两条预算: 先按**像素**缩到一个尺寸, 再按**字节**缩到装得下
 *
 * 两条都用滑块而不是下拉, 但形状不一样, 因为它们要对的数字性质不一样:
 *
 * - **像素那条是三档**, 滑块就只有三个位置。那三个数分别对应 dsh 的 `low` / 缺省 / DeepSeek 那头
 *   处理图片的预算, 中间的值没有对应的预算可言, 而越过那条路由的预算会让整个模型请求失败 (设备上
 *   编不了图) —— 所以打字也只会落到最接近的一档
 * - **字节那条是连续的**, 中间那几个吸附点把 256 KiB 到 1 MiB 三等分。它是连续的是因为要对齐的数字
 *   在 `settings.yaml` 里, 那个数可以是任何一个, 而滑块只负责给常见的几个, 真要对上就点标题那一行打字
 *
 * **一句话分清楚两半账**: 这两条给的都是 **app 这一半** —— 截图在产生时缩到多少, 而路由那一半
 * (`imagePixelBudget` / `imageMaxBytes`) 在 dsh 自己的 `settings.yaml` 里, app 读不到也写不到。app 这一半
 * 高于路由那一半不但没用, 还会把注定被拒的图交出去, 所以**两条滑块的上端都停在路由缺省那个数**
 * (像素那条的"高"档是唯一的例外, 见上), 而**打字**能给的更宽 —— 那是给"路由也一起调高了"的人留的口子
 *
 * 两条都写着那句"超了会失败": 这不是吓唬, 是这台设备上真实的下场 (见 `Picture` 的注释)
 */
@Composable
private fun ScreenshotItems() {
    val context = LocalContext.current
    val levels = ScreenshotBudget.levels
    val last = levels.lastIndex
    val levelNames = levels.map { stringResource(it.first, it.second) }
    // 指代不明时自动附图 (批次 4 的需求 9): 这一条要**排在两条预算前面**, 因为它管的是"要不要截图",
    // 而下面两条管的是"截图产生时缩到多大"。开关的值由宿主插件在投递一句话之前问一次 (它读不到应用
    // 这一侧的偏好, 走的是 `screenshot op=status`)
    SwitchPreference(
        title = stringResource(R.string.settings_screenshot_auto),
        summary = stringResource(R.string.settings_screenshot_auto_summary),
        checked = ScreenshotBudget.autoShot,
        onCheckedChange = { ScreenshotBudget.setAutoShot(context, it) },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_screenshot_budget),
        summary = stringResource(R.string.settings_screenshot_budget_summary),
        value = ScreenshotBudget.index.toFloat(),
        onValueChange = { ScreenshotBudget.set(context, it.roundToInt().coerceIn(0, last)) },
        valueRange = 0f..last.toFloat(),
        // 三档 = 端点之间只有一个离散点, 这是 Compose 那个 steps 的算法
        steps = (levels.size - 2).coerceAtLeast(0),
        showKeyPoints = true,
        keyPoints = levels.indices.map { it.toFloat() },
        displayFormatter = { levelNames[it.roundToInt().coerceIn(0, last)] },
        // 打字给的是像素数, 但只落到最接近的一档, 所以对话框里把那三档列出来
        inputSummary = levelNames.joinToString(" / "),
        inputLabel = "px",
        inputInitialValue = ScreenshotBudget.pixels.toString(),
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = levels.first().second.toFloat()..levels.last().second.toFloat(),
        onInputConfirm = { raw ->
            val typed = raw.toIntOrNull() ?: 0
            ScreenshotBudget.set(
                context,
                levels.indices.minBy { abs(levels[it].second - typed) },
            )
        },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_screenshot_bytes),
        summary = stringResource(R.string.settings_screenshot_bytes_summary, ScreenshotBudget.bytes),
        value = ScreenshotBudget.kib,
        onValueChange = { ScreenshotBudget.setBytes(context, it.roundToInt()) },
        valueRange = ScreenshotBudget.byteRange,
        // 连续滑动: 吸附交给 keyPoints 与 magnetThreshold, 设了 steps 反而滑不到中间的值
        steps = 0,
        showKeyPoints = true,
        keyPoints = ScreenshotBudget.byteKeyPoints,
        magnetThreshold = ScreenshotBudget.BYTE_MAGNET,
        unit = "KiB",
        // 打字能给的比滑块宽: 滑块上的每个值都该是能过那条路由的, 而打字是配另一条路由用的
        inputSummary = stringResource(
            R.string.settings_screenshot_bytes_dialog,
            ScreenshotBudget.byteRange.endInclusive.toInt(),
            ScreenshotBudget.byteInputRange.endInclusive.toInt(),
        ),
        inputLabel = "KiB",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = ScreenshotBudget.byteInputRange,
        onInputConfirm = { raw ->
            raw.toIntOrNull()?.let { ScreenshotBudget.setBytes(context, it) }
        },
    )
}

/**
 * 「模式与模型」那一行 (3.0.2 需求 2)
 *
 * 只有一行: 视频模式用哪一个模型。点开是候选列表 (宿主写的 model-catalog.json, 与
 * `lw.ps1 -Method model` 量到的是同一份), 目录取不到就退化为两个文本框 —— 不假装有列表
 */
@Composable
private fun ModeItems() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var dialog by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(false) }
    // 存完重画那一段 (与 SyncSetting.revision 同一个做法)
    val snapshot = remember(ModeModel.revision) { ModeModel.snapshot(context) }
    ArrowPreference(
        title = stringResource(R.string.settings_mode_model),
        // 配了自建端点时这一行说的是它 —— 那一档下"从目录里挑的那一对"不生效, 遮着不说才是骗人
        summary = if (snapshot.endpointSet) {
            stringResource(R.string.settings_mode_endpoint_using, snapshot.endpointModel)
        } else {
            stringResource(
                R.string.settings_mode_model_summary,
                if (snapshot.followsCurrent) {
                    stringResource(R.string.settings_mode_model_follow)
                } else {
                    "${snapshot.videoProvider} / ${snapshot.videoModel}"
                },
            )
        },
        onClick = {
            haptic.contextClick()
            dialog = true
        },
    )
    // 「添加模型」那一行 (2026-10-16 主人: "视频模式选择的 llm 的 url, api 等放到视频模式的设置里";
    // 2026-10-16 主人后来把这一行的名字从「自建端点」改成「添加模型」)
    ArrowPreference(
        title = stringResource(R.string.settings_mode_endpoint),
        summary = if (snapshot.endpointKeySet) {
            stringResource(
                R.string.settings_mode_endpoint_summary,
                snapshot.endpointUrl.ifEmpty { "<unset>" },
                snapshot.endpointModel.ifEmpty { "<unset>" },
                ModeModel.maskedKey(snapshot.endpointSecret),
            )
        } else {
            stringResource(R.string.settings_mode_endpoint_none)
        },
        onClick = {
            haptic.contextClick()
            endpoint = true
        },
    )
    if (dialog) ModeModelDialog(onDismiss = { dialog = false })
    if (endpoint) ModeEndpointDialog(onDismiss = { endpoint = false })
}

/**
 * 「添加模型」的对话框: 接口地址 / API Key / 模型名 (三个文本框, 骨架照 [SpeakTextFieldDialog])
 *
 * **每个框上面那一行小字说清填什么** (主人 2026-10-16: "说清楚哪一空填什么信息"): 接口地址是
 * http(s) 地址 (一般带 /v1), API Key 可以留空, 模型名是接口那边认的 id —— 三个框原来没有标题,
 * 只靠对话框那一句说明去猜哪一行是哪一个
 *
 * 它写进 `lw/mode-model.json` 的 `endpoint` 段; 宿主那一侧把这一段**挂成 pi-ai 的一条真路由**
 * (`luwi-video`, 见 3.0.3 施工单), 于是视频模式那一次请求就走这个端点。两条要说清的:
 * 那条路由**也出现在 dsh 自己的模型选择器里** (名字带「Luwi 视频模式」), 以及 API Key 存在这一份
 * 文件里 (0600, 与对端口令同一个存法) —— 界面只出打码形态
 */
@Composable
private fun ModeEndpointDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snapshot = remember(ModeModel.revision) { ModeModel.snapshot(context) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_mode_endpoint),
        summary = stringResource(R.string.settings_mode_endpoint_note),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.5f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            var url by rememberSaveable(ModeModel.revision) { mutableStateOf(snapshot.endpointUrl) }
            var secret by rememberSaveable(ModeModel.revision) { mutableStateOf(snapshot.endpointSecret) }
            var model by rememberSaveable(ModeModel.revision) { mutableStateOf(snapshot.endpointModel) }
            SuperTextField(
                label = stringResource(R.string.settings_mode_endpoint_url_note),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            SuperTextField(
                label = stringResource(R.string.settings_mode_endpoint_key_note),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                value = secret,
                onValueChange = { secret = it },
                singleLine = true,
                // 与「说话」那一段的 API Key 同一个做法: 界面上是圆点, 明文只在文件里
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            SuperTextField(
                label = stringResource(R.string.settings_mode_endpoint_model_note),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                value = model,
                onValueChange = { model = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    val problem = ModeModel.setEndpoint(context, url, secret, model)
                    if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            if (snapshot.endpointSet) {
                ArrowPreference(
                    title = stringResource(R.string.settings_mode_endpoint_off),
                    summary = stringResource(R.string.settings_mode_endpoint_off_summary),
                    onClick = {
                        haptic.contextClick()
                        ModeModel.clearEndpoint(context)
                    },
                )
            }
        }
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「视频模式使用的模型」的对话框 (骨架照 [WidgetSlotDialog]: 半屏上限 + 竖向滚动)
 *
 * 第一条永远是「跟随当前模型」, 其后是宿主目录里的候选; 打开那一下写一个"现在刷一次"的记号
 * (与余额那条同一个形状), 宿主本来就有的那条 3 s 轮询看见就刷。目录取不到 (宿主没写过 /
 * 还没刷出来 / items 为空) 就退化为两个文本框, **不假装有列表**
 */
@Composable
private fun ModeModelDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var catalog by remember { mutableStateOf<LwModel.Catalog?>(null) }
    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) {
            LwModel.request(context)
            // 记号要等宿主的一个轮询才变成新目录: 最多等 3 s, 每 500 ms 读一次
            repeat(6) {
                LwModel.snapshot(context)?.takeIf { it.ok && it.items.isNotEmpty() }
                    ?.let { found -> return@withContext found }
                delay(500)
            }
            LwModel.snapshot(context)
        }
    }
    val snapshot = remember(ModeModel.revision) { ModeModel.snapshot(context) }
    // 目录要么整份可用 (有候选), 要么整个退回手填 —— "半份目录"不拿来假装是列表
    val listed = catalog?.takeIf { it.ok && it.items.isNotEmpty() }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_mode_model),
        summary = stringResource(R.string.settings_section_look_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismiss,
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeight * 0.5f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val follow = snapshot.followsCurrent
            ArrowPreference(
                title = stringResource(R.string.settings_mode_model_follow),
                summary = if (follow) "现在就是它" else "退出视频模式后与平时用同一个模型",
                onClick = {
                    haptic.confirm()
                    ModeModel.setVideo(context, "", "")
                },
            )
            if (listed == null) {
                ArrowPreference(
                    title = stringResource(R.string.settings_mode_model_none),
                    summary = if (catalog == null) "宿主还没写过目录" else "宿主这一次没取到目录",
                )
                // 两个文本框: 提供方与模型名, 与文件里那两个键一一对应
                var provider by rememberSaveable(ModeModel.revision) { mutableStateOf(snapshot.videoProvider) }
                var model by rememberSaveable(ModeModel.revision) { mutableStateOf(snapshot.videoModel) }
                SuperTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = provider,
                    onValueChange = { provider = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                SuperTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = model,
                    onValueChange = { model = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextButton(
                    text = stringResource(R.string.button_confirm),
                    onClick = {
                        haptic.confirm()
                        val problem = ModeModel.setVideo(context, provider, model)
                        if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                listed.items.forEach { item ->
                    val chosen = !snapshot.followsCurrent &&
                        snapshot.videoProvider == item.provider &&
                        snapshot.videoModel == item.model
                    ArrowPreference(
                        title = item.label,
                        summary = if (chosen) {
                            "现在用它"
                        } else {
                            "${item.provider} / ${item.model}"
                        },
                        onClick = {
                            haptic.confirm()
                            ModeModel.setVideo(context, item.provider, item.model)
                        },
                    )
                }
            }
        }
        TextButton(
            text = stringResource(R.string.button_cancel),
            onClick = {
                haptic.contextClick()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 朗读: 音色与语速
 *
 * 这两件事与"引擎是谁、要不要下语音包"是两半账: **引擎与语音包只能去系统的「文字转语音输出」那
 * 一页换** (应用改不了别人家的引擎), 而"用这条引擎的哪个中文音色"与"多快"应用这一侧就能定 ——
 * 于是这里直接调, 那一页留一个跳转
 *
 * 音色那一串是**引擎报上来的**, 不是这一页写死的字符串, 所以名字跟着引擎走; 引擎还没起来时
 * 只显示一句"还没就绪", 不假装列了一张空表
 *
 * 语速默认**跟随系统**: 跟随的时候我们一个数都不设, 系统里调的就是生效的。拖一下滑块 = 要一个
 * 具体的数, 于是自动改成不跟随 (拖动这个动作本身就是"我要自己定")
 */
@Composable
private fun SpeakItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<Voice>?>(null) }
    var speaking by remember { mutableStateOf(false) }
    // 要打字的那几行 (API 四项 + Edge 的自定义音色): 一次开一个
    var editing by remember { mutableStateOf<SpeakText?>(null) }
    // 引擎初始化要几百毫秒, 不能在组合里做: 放到 IO 上, 回来了再画音色那一段
    LaunchedEffect(Unit) {
        voices = withContext(Dispatchers.IO) { LwSpeak.chineseVoices(context) }
    }
    // 关掉朗读: 只关"回答落定就自动念"那条链, 手动让模型念 (lw_speak) 与下面那个试听不受影响 ——
    // 有时就是不想要它出声, 而"要它念一句"仍然该是能做到的
    SwitchPreference(
        title = stringResource(R.string.settings_speak_auto),
        summary = stringResource(R.string.settings_speak_auto_summary),
        checked = SpeakSettings.readAloud,
        onCheckedChange = { SpeakSettings.setReadAloud(context, it) },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_speak_follow),
        summary = stringResource(R.string.settings_speak_follow_summary),
        checked = SpeakSettings.followsSystem,
        onCheckedChange = { SpeakSettings.setFollowsSystem(context, it) },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_speak_rate),
        summary = if (SpeakSettings.followsSystem) {
            stringResource(R.string.settings_speak_rate_following)
        } else {
            stringResource(R.string.settings_speak_rate_summary, SpeakSettings.rate)
        },
        value = SpeakSettings.rate,
        onValueChange = { SpeakSettings.setRate(context, it) },
        valueRange = SpeakSettings.range,
        // 连续滑动: 吸附点只帮着停到好看的那几个数上, 中间的值照样给
        steps = 0,
        showKeyPoints = true,
        keyPoints = SpeakSettings.keyPoints,
        magnetThreshold = SpeakSettings.MAGNET,
        displayFormatter = { "%.2f x".format(it) },
        // 打字给的是精确倍数: 耳朵认的那个数常常落在两个吸附点之间
        inputSummary = stringResource(
            R.string.settings_speak_rate_dialog,
            SpeakSettings.range.start,
            SpeakSettings.range.endInclusive,
        ),
        inputLabel = "x",
        inputInitialValue = "%.2f".format(SpeakSettings.rate),
        inputValueRange = SpeakSettings.range,
        onInputConfirm = { raw -> raw.toFloatOrNull()?.let { SpeakSettings.setRate(context, it) } },
    )

    // 引擎四条: 系统那条即时但只有它自己的音色; 自带那条能换音色 (主人自己放模型) 但慢一截;
    // Edge 那条免费、在线、不要密钥; API 那条要自己填地址与密钥
    val onDevice = SpeakSettings.engine == SpeakSettings.Engine.ON_DEVICE
    val edge = SpeakSettings.usesEdge()
    val api = SpeakSettings.engine == SpeakSettings.Engine.API
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_system),
        summary = stringResource(
            if (!onDevice) R.string.settings_speak_voice_current else R.string.settings_speak_voice_pick,
        ),
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.SYSTEM) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_on_device),
        summary = when {
            onDevice && SpeakSettings.model != null -> stringResource(
                R.string.settings_speak_engine_on_device_using,
                SpeakSettings.model.orEmpty(),
            )

            onDevice -> stringResource(R.string.settings_speak_engine_on_device_pick)

            else -> stringResource(R.string.settings_speak_engine_on_device_summary)
        },
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.ON_DEVICE) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_edge),
        summary = if (edge) {
            stringResource(R.string.settings_speak_engine_edge_using, SpeakSettings.edgeVoice)
        } else {
            stringResource(R.string.settings_speak_engine_edge_summary)
        },
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.EDGE) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_api),
        summary = when {
            api && SpeakSettings.apiUrl.isNotBlank() ->
                stringResource(R.string.settings_speak_engine_api_using, SpeakSettings.apiModel)

            api -> stringResource(R.string.settings_speak_engine_api_pick)

            else -> stringResource(R.string.settings_speak_engine_api_summary)
        },
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.API) },
    )

    if (onDevice) {
        // 自己放进来的音色: 目录里有什么就列什么, 用不了的连原因一起列出来
        var models by remember { mutableStateOf<List<LwTts.Voice>?>(null) }
        val rescan: () -> Unit = {
            scope.launch { models = withContext(Dispatchers.IO) { LwTts.list(context) } }
        }
        LaunchedEffect(Unit) { rescan() }
        val listed = models
        when {
            listed == null -> ArrowPreference(
                title = stringResource(R.string.settings_speak_models),
                summary = stringResource(R.string.settings_speak_models_scanning),
                onClick = rescan,
            )

            listed.isEmpty() -> ArrowPreference(
                title = stringResource(R.string.settings_speak_models),
                summary = stringResource(R.string.settings_speak_models_none, LwTts.root(context).absolutePath),
                onClick = rescan,
            )

            else -> listed.forEach { voice ->
                ArrowPreference(
                    title = voice.name,
                    summary = when {
                        !voice.usable -> voice.problem.orEmpty()
                        SpeakSettings.model == voice.name -> stringResource(R.string.settings_speak_voice_current)
                        else -> stringResource(
                            R.string.settings_speak_models_usable,
                            voice.family?.label.orEmpty(),
                        )
                    },
                    onClick = { if (voice.usable) SpeakSettings.setModel(context, voice.name) },
                )
            }
        }
        // 目录路径要显示出来: 不然没人知道该把模型拷到哪, 也没法自己核对
        ArrowPreference(
            title = stringResource(R.string.settings_speak_models_directory),
            summary = stringResource(R.string.settings_speak_models_directory_summary, LwTts.root(context).absolutePath),
            onClick = rescan,
        )
        // 音量: 放在这一段里, 因为它只对自带这条引擎有意义 —— 系统那条的增益在引擎自己手里, 应用
        // 这一侧没有 API 能碰, 所以换到上面那条引擎时这一行干脆不出现, 而不是留个拖了没反应的滑块
        //
        // 基准是**这条音色本来的电平**: 100% 一个增益都不加, 100 往上才是加。这不是拍脑袋的刻度,
        // 是 2026-10-06 在开发机上量出来的 (见 SpeakSettings 那一段注释)
        val hint = stringResource(
            when {
                SpeakSettings.volume <= 0f -> R.string.settings_speak_volume_hint_muted
                SpeakSettings.volume <= SpeakSettings.VOLUME_UNITY ->
                    R.string.settings_speak_volume_hint_unity

                else -> R.string.settings_speak_volume_hint_boosted
            },
        )
        ArrowSlider(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_volume_summary, SpeakSettings.volume.toInt(), hint),
            value = SpeakSettings.volume,
            onValueChange = { SpeakSettings.setVolume(context, it) },
            valueRange = SpeakSettings.volumeRange,
            // 连续滑动: 吸附点只帮着停到好看的那几个数上 (100% 与 300% 都在里面)
            steps = 0,
            showKeyPoints = true,
            keyPoints = SpeakSettings.volumeKeyPoints,
            magnetThreshold = SpeakSettings.MAGNET,
            displayFormatter = { "${it.toInt()}%" },
            inputSummary = stringResource(
                R.string.settings_speak_volume_dialog,
                SpeakSettings.volumeRange.start.toInt(),
                SpeakSettings.volumeRange.endInclusive.toInt(),
            ),
            inputLabel = "%",
            inputInitialValue = SpeakSettings.volume.toInt().toString(),
            inputValueRange = SpeakSettings.volumeRange,
            onInputConfirm = { raw -> raw.toFloatOrNull()?.let { SpeakSettings.setVolume(context, it) } },
        )
    } else if (edge) {
        // Edge 那条: 音色是它在线的那几个, 点一个就用; 还能自己填名字 (它那边有几百条)
        LwEdgeSpeech.VOICES.forEach { (name, label) ->
            ArrowPreference(
                title = label,
                summary = stringResource(
                    if (SpeakSettings.edgeVoice == name) R.string.settings_speak_voice_current
                    else R.string.settings_speak_voice_pick,
                ),
                onClick = { SpeakSettings.setEdgeVoice(context, name) },
            )
        }
        ArrowPreference(
            title = stringResource(R.string.settings_speak_edge_custom),
            summary = SpeakSettings.edgeVoice,
            onClick = { editing = SpeakText.EDGE_VOICE },
        )
        // 响度不在应用手里: 这一行说实话, 点它去系统声音设置 (媒体音量在那里)
        ArrowPreference(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_online_volume),
            onClick = { openSoundSettings(context) },
        )
    } else if (api) {
        // API 那条: 地址与密钥是钥匙, 模型与音色是给服务看的
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_url),
            summary = if (SpeakSettings.apiUrl.isBlank()) {
                stringResource(R.string.settings_speak_api_url_empty)
            } else {
                stringResource(R.string.settings_speak_api_url_value, SpeakSettings.apiUrl)
            },
            onClick = { editing = SpeakText.API_URL },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_key),
            summary = stringResource(
                if (SpeakSettings.apiKey.isBlank()) R.string.settings_speak_api_key_empty
                else R.string.settings_speak_api_key_set,
            ),
            onClick = { editing = SpeakText.API_KEY },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_model),
            summary = SpeakSettings.apiModel,
            onClick = { editing = SpeakText.API_MODEL },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_voice),
            summary = SpeakSettings.apiVoice,
            onClick = { editing = SpeakText.API_VOICE },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_online_volume),
            onClick = { openSoundSettings(context) },
        )
    } else {
        // 系统那条也有音量这回事, 只是它不由应用定: 给一句实话, 而不是一个拖不动的控件
        ArrowPreference(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_volume_system),
            onClick = { openTtsSettings(context) },
        )
        val listed = voices
        when {
            listed == null -> ArrowPreference(
                title = stringResource(R.string.settings_speak_voice),
                summary = stringResource(R.string.settings_speak_voice_unknown),
                onClick = {
                    scope.launch {
                        voices = withContext(Dispatchers.IO) { LwSpeak.chineseVoices(context) }
                    }
                },
            )

            listed.isEmpty() -> ArrowPreference(
                title = stringResource(R.string.settings_speak_voice),
                summary = stringResource(R.string.settings_speak_voice_none),
                onClick = { openTtsSettings(context) },
            )

            else -> {
                // 引擎自带的那个也列出来: 选过别的之后要能回到它
                ArrowPreference(
                    title = stringResource(R.string.settings_speak_voice_default),
                    summary = stringResource(
                        if (SpeakSettings.voice == null) R.string.settings_speak_voice_current
                        else R.string.settings_speak_voice_pick,
                    ),
                    onClick = { SpeakSettings.setVoice(context, null) },
                )
                listed.forEach { voice ->
                    ArrowPreference(
                        title = voiceLabel(voice),
                        summary = stringResource(
                            if (SpeakSettings.voice == LwSpeak.voiceKey(voice)) R.string.settings_speak_voice_current
                            else R.string.settings_speak_voice_pick,
                        ),
                        onClick = { SpeakSettings.setVoice(context, LwSpeak.voiceKey(voice)) },
                    )
                }
            }
        }
    }
    // 引擎与语音包只能去系统那一页: 这里给的是跳转, 而不是一个做不到的下拉 (那两条在线引擎与
    // 那一页无关, 所以选着它们时不摆这一行)
    if (!edge && !api) {
        ArrowPreference(
            title = stringResource(R.string.settings_speak_system),
            summary = stringResource(R.string.settings_speak_system_summary),
            onClick = { openTtsSettings(context) },
        )
    }
    // 试听: 音色与语速是耳朵判断的东西, 看一眼数字没有意义 —— 而**正在念的时候这一行就是停止**,
    // 不然一段长回答只能等它念完 (两条引擎都停得下来, 见 LwSpeak.stop)
    ArrowPreference(
        title = stringResource(if (speaking) R.string.settings_speak_stop else R.string.settings_speak_preview),
        summary = stringResource(
            if (speaking) R.string.settings_speak_previewing else R.string.settings_speak_preview_summary,
        ),
        onClick = {
            if (speaking) {
                scope.launch { withContext(Dispatchers.IO) { runCatching { LwSpeak.stop() } } }
                return@ArrowPreference
            }
            speaking = true
            scope.launch {
                val answer = withContext(Dispatchers.IO) { runCatching { LwSpeak.preview(context) } }
                speaking = false
                // 没念出来必须当场说一句: 在线那两条的失败只写在回执的 detail 里 (服务端会因为它不认的
                // 那个音色当场关掉这条 WebSocket), 原来这里把回执整个丢掉 —— 于是现象是"点了试听没声音,
                // 屏幕上什么也没有, 那行字 60 秒后才变回试听" (2026-10-09 真机上报的那一次)
                val problem = answer.fold(
                    onSuccess = { LwSpeak.spokenProblem(it) },
                    onFailure = { it.message ?: it.toString() },
                )
                if (problem != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_speak_preview_failed, problem),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
    )
    editing?.let { field ->
        SpeakTextFieldDialog(
            field = field,
            onDismissRequest = { editing = null },
            onConfirm = { value ->
                when (field) {
                    SpeakText.API_URL -> SpeakSettings.setApiUrl(context, value)
                    SpeakText.API_KEY -> SpeakSettings.setApiKey(context, value)
                    SpeakText.API_MODEL -> SpeakSettings.setApiModel(context, value)
                    SpeakText.API_VOICE -> SpeakSettings.setApiVoice(context, value)
                    SpeakText.EDGE_VOICE -> SpeakSettings.setEdgeVoice(context, value)
                }
                editing = null
            },
        )
    }
}

/** 音色在列表里的样子: 名字加地区, 因为同一个引擎常有 zh-CN 与 zh-TW 两条 */
private fun voiceLabel(voice: Voice): String = "${voice.name} (${voice.locale.toLanguageTag()})"

/**
 * 浮标: 一颗球, 点一下说话
 *
 * 这一段的落点是**一颗球在不在屏幕上**, 而不是"那段代码跑没跑": 球是那块常驻的 overlay 窗, 而它的
 * 开关是存盘的 ([BallSpot.on]) —— 开关管"下一次应用起来要不要把它放出来", 服务管"它现在在不在",
 * 所以状态那一行必须说三件事: 缺权限 / 球在(停在哪边) / 球不在
 *
 * 缺权限时**不替人打开开关**: 屏幕上什么都不会出现, 而状态那一行直说缺哪一条 (与别的段同一条纪律)
 *
 * 每秒看一眼: 球的位置是拖动那一侧写进偏好的, 而权限可能在系统设置里被改掉, 不轮询界面就是死的
 */
@Composable
private fun BallItems() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BallSpot.on(context)) }
    var permission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showing by remember { mutableStateOf(OverlayState.showing) }
    var edge by remember { mutableStateOf(BallSpot.read(context)?.first ?: BallGeometry.EDGE_RIGHT) }
    var hostUp by remember { mutableStateOf(DshHost.status is HostStatus.Running) }
    var hideProblem by remember { mutableStateOf<String?>(null) }
    var feel by remember { mutableStateOf(BallSpot.feel(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            permission = Settings.canDrawOverlays(context)
            showing = OverlayState.showing
            edge = BallSpot.read(context)?.first ?: BallGeometry.EDGE_RIGHT
            hostUp = DshHost.status is HostStatus.Running
            feel = BallSpot.feel(context)
            // **开关跟的是球的真实存在, 不是"上一次点了什么"** (2026-10-06): 菜单里、通知栏上、
            // 或者服务自己那边都能把球收掉, 那几条路都不会回来改这个页面里的记忆值 —— 只认本地那一个
            // 记号的话, 球被菜单关掉之后这个开关会一直画着"开", 而屏幕上什么都没有。
            // 判据本身在 [BallSwitch] 里 (纯函数, 见 BallSwitchTest)
            on = BallSwitch.onFor(showing = OverlayState.showing, remembered = BallSpot.on(context))
            OverlayState.hideFailed?.let { hideProblem = it }
            delay(POLL_MS)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                !permission -> stringResource(R.string.settings_ball_permission)
                showing -> stringResource(
                    R.string.settings_ball_state_up,
                    stringResource(
                        if (edge == BallGeometry.EDGE_LEFT) R.string.ball_dock_left else R.string.ball_dock_right,
                    ),
                )

                else -> stringResource(R.string.settings_ball_state_down)
            },
        )
        if (showing && !hostUp) {
            Text(
                text = stringResource(R.string.settings_ball_missing_host),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        // 球没起来时把上一次的原因摊开说 (系统拒了那块窗 / 没有窗口管理器): 失败不许静默
        if (!showing) {
            OverlayState.lastError?.let { reason ->
                Text(text = reason, modifier = Modifier.padding(top = UiSpacing.Medium))
            }
        }
        // **关不掉不许假装关掉了** (2026-10-06): 那一次的毛病正是"开关画成关、而球还在屏幕上",
        // 所以服务那边读回发现窗没摘下来时, 这里要把原因说出来
        hideProblem?.let { reason ->
            Text(
                text = stringResource(R.string.settings_ball_hide_failed, reason),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
    }
    SwitchPreference(
        title = stringResource(R.string.settings_ball_on),
        summary = stringResource(R.string.settings_ball_on_summary),
        checked = on,
        onCheckedChange = { wanted ->
            on = wanted
            hideProblem = null
            LwOverlay.setOn(context, wanted)
            showing = OverlayState.showing
        },
    )
    // 手势有多严 (2026-10-08 第 13 条那一档): 两套常数都在 BallMinutes 里, 这一行只挑一个名字。
    // **换了当场生效**: 那两个门槛是球每次手指按下时读的 (见 BallView.onTouchEvent), 不必重开服务
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_ball_feel),
        summary = stringResource(R.string.settings_ball_feel_summary, stringResource(feelLabel(feel))),
        items = ballFeels.map { stringResource(feelLabel(it)) },
        selectedIndex = ballFeels.indexOf(feel).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            val picked = ballFeels[index.coerceIn(0, ballFeels.lastIndex)]
            feel = picked
            BallSpot.setFeel(context, picked)
        },
    )
    // 球多大 (主人 2026-10-15: "在 luwi 浮标设置里, 增加 ball 尺寸大小")
    //
    // **两个来源取大的那个** (见 [BallSize.resolve]): 这一档是主人设的基准, 而外观插件可以声明
    // 它自己至少要多大 (鲸鱼娘那份是 72 —— 全身立绘缩到 48 时五官糊成一团)。所以挑了插件之后
    // 这一行会把插件那个数**一并说出来**, 免得"我明明设了 48, 它却是 72"看着像坏了
    BallSizeSlider()
    // 休闲那两个开关 (2026-10-15 主人: "在浮标设置里加上半透明和半隐藏开关"; 2026-10-17 主人把叫法
    // 定成: **「半隐」= 淡下去那一档, 「半藏」= 挪一半到屏幕外那一档**): 两档**同一个 5 秒到点**
    // (主人: "时间与透明度都和以前的一样" + "0.35"), 上一行是半隐 (淡到三成半), 下一行是半藏
    // (把球挪出去一半) —— 各管一个状态, 判据仍旧只有 `BallRest.wait` 那一处
    //
    // **不必重启**: 那个 alpha 是 `OverlayService.waitNow()` 每一拍 (400 ms) 校正的, 改完当场看得见
    var translucentNow by remember { mutableStateOf(BallSpot.translucent(context)) }
    SwitchPreference(
        title = stringResource(R.string.settings_ball_translucent),
        summary = stringResource(R.string.settings_ball_translucent_summary),
        checked = translucentNow,
        onCheckedChange = { wanted ->
            translucentNow = wanted
            BallSpot.setTranslucent(context, wanted)
        },
    )
    // 「半藏」: 闲置之后自动贴边收起一半。**关掉不是"不能收边"**, 只是不再自动收 —— 碰一下照旧
    // 滑回来。它独立于上面那一行: 半隐关着时这一档到点仍然收, 而且球是**全不透明**的
    var autoPeekNow by remember { mutableStateOf(BallSpot.autoPeek(context)) }
    SwitchPreference(
        title = stringResource(R.string.settings_ball_peek),
        summary = stringResource(R.string.settings_ball_peek_summary),
        checked = autoPeekNow,
        onCheckedChange = { wanted ->
            autoPeekNow = wanted
            BallSpot.setAutoPeek(context, wanted)
        },
    )
    // 拖球那条经验 (主人 2026-10-06 让写在这儿): 球贴在屏幕最左/最右时, 直接往中间拖会先经过系统的
    // **返回手势区** (屏幕左右边缘那一条竖带) —— 那一下被系统吃掉, 表现是"球没挪动, 反而退出了当前
    // 应用"。先往上拖一点, 离开那条竖带再横着挪, 就不会撞上它
    //
    // 样子按主人点的来: **灰色小字**, 而且**上面空一行** —— 紧贴着上面那张卡片时, 最后一行会被卡片的
    // 圆角与描边压住看着像被切掉; 左右缩进跟页面一致 (卡片没有水平缩进, 这一行是页面级文字)
    Spacer(modifier = Modifier.height(UiSpacing.PageItem))
    Text(
        text = stringResource(R.string.settings_ball_drag_tip),
        color = colorScheme.onSurfaceVariantActions,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = UiSpacing.PageHorizontal),
    )
}

/**
 * 「球大小」那一档
 *
 * 球多大原来是个常量 (D16 定的 48 dp), 换人物立绘时又被写死成 72 dp —— 两件事都不对, 这个取舍
 * 只有主人自己知道 (球小不挡屏, 球大人物五官看得清), 于是它成了一档设置。
 *
 * **换了当场生效**: 尺寸是球每次 `onMeasure` 现算的 (与手势灵敏度同一个做法), 不必重开服务 ——
 * 服务那边收到尺寸变化会把贴边 / 半隐 / 回复框一起重摆 (见 `OverlayService.ballListener`)
 *
 * 摘要里那句"插件要多大"只在挑了带 `minDp` 的外观时才有意义, 所以两者都列出来
 */
@Composable
private fun BallSizeSlider() {
    val context = LocalContext.current
    val stored = remember { BallSize.sizeDp(context) }
    // 当前主题 (内部有缓存, 不是每次读盘)
    val themeMin = remember { runCatching { BallTheme.active(context)?.spec?.minDp ?: 0 }.getOrDefault(0) }
    val showing = remember { BallSize.resolve(context, themeMin) }
    val summary =
        if (themeMin > stored) {
            stringResource(R.string.settings_ball_size_summary_theme, stored, themeMin, showing)
        } else {
            stringResource(R.string.settings_ball_size_summary, stored)
        }
    ArrowSlider(
        title = stringResource(R.string.settings_ball_size),
        summary = summary,
        value = stored.toFloat(),
        onValueChange = { BallSize.set(context, it.roundToInt()) },
        valueRange = BallSize.range,
        // 连续滑动: 吸附交给 keyPoints 与 magnetThreshold (设了 steps 反而滑不到中间那些值)
        steps = 0,
        showKeyPoints = true,
        keyPoints = BallSize.keyPoints,
        magnetThreshold = BallSize.MAGNET,
        unit = "dp",
        inputSummary = stringResource(
            R.string.settings_ball_size_dialog,
            BallSize.range.start.toInt(),
            BallSize.range.endInclusive.toInt(),
        ),
        inputLabel = "dp",
        // 打字按 dp 收, 范围与滑块同一份 —— 越界的值由 BallSize.set 自己夹回范围内
        onInputConfirm = { raw -> raw.toIntOrNull()?.let { BallSize.set(context, it) } },
    )
}

/**
 * 「省电」那一段 (主人 2026-10-08: "省电模式单独拿出来放第一排")
 *
 * 它原来是「唤醒词」里的三行 (手动开关 / 定时那一段 / 省电时停自动指令), 而这三件事与"许可不允许
 * 唤醒"是两笔账: 那一段管的是**我许可你一直听着**, 这一段管的是**现在别听 / 到点再听**, 以及省电
 * 的时候连自动指令那几个费电的监测一起停。分开之后"现在静音一次"不必先展开唤醒词那一段
 *
 * 三行的口径 (都写在各自的说明里):
 *
 * - **省电模式**: 只停唤醒词监听 (麦克风整个关掉, 喊不醒), 而 host / 浮标 / 通知都留着、点球照样
 *   能说一句 —— 关掉它时**顺手把监听拉回来**, 但许可、麦克风权限与模型三者都对得上才做
 * - **定时那一段**: `23:00-07:00` 这种一段, 到点自己进省电、出了时段自己回来 (解析在 [PowerWindow])
 * - **省电时停自动指令**: 第 15 条那一条, 此刻只记选择 (自动指令在批次 8 才落地)
 *
 * 每秒看一眼: 三个偏好都可能被别的入口改 (定时那一段是到点自己生效的), 不轮询界面就是死的
 */
@Composable
private fun PowerItems() {
    val context = LocalContext.current
    var manual by remember { mutableStateOf(LwWakeWord.powerSaveManual(context)) }
    var active by remember { mutableStateOf(LwWakeWord.powerSave(context)) }
    var automation by remember { mutableStateOf(LwWakeWord.powerSaveAutomation(context)) }
    var window by remember { mutableStateOf(LwWakeWord.powerWindowText(context)) }
    var editingWindow by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            manual = LwWakeWord.powerSaveManual(context)
            active = LwWakeWord.powerSave(context)
            automation = LwWakeWord.powerSaveAutomation(context)
            window = LwWakeWord.powerWindowText(context)
            delay(POLL_MS)
        }
    }
    /**
     * 省电关掉之后把监听拉回来 —— 许可、麦克风权限与模型三者都对得上才做
     *
     * 缺哪一条都**不在这里报错**: 缺什么由「唤醒词」那一段的状态行说清, 这里插一句只会指向别处
     */
    fun resumeListening(): String? {
        if (active) return null
        if (!LwWakeWord.allow(context)) return null
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (mic != PackageManager.PERMISSION_GRANTED) return null
        if (WakeWordDownload.readyCount(context) < WakeWordDownload.files.size) return null
        if (WakeWordState.listening) return null
        return runCatching { LwWakeWord.listen(context) }.exceptionOrNull()
            ?.let { throwable -> throwable.message ?: throwable.toString() }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                // 手动那个开关开着: 说清"喊不醒, 但点球还能说一句"
                manual -> stringResource(R.string.settings_wake_state_power_save)
                // 手动关着而定时那一段正生效: 说清"是定时让它省电的"
                active -> stringResource(R.string.settings_wake_power_window_now, window)
                else -> stringResource(R.string.settings_power_state_idle)
            },
        )
        note?.let { problem ->
            Text(text = problem, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
    }
    SwitchPreference(
        title = stringResource(R.string.settings_wake_power_save),
        summary = stringResource(R.string.settings_wake_power_save_summary),
        checked = manual,
        onCheckedChange = { wanted ->
            manual = wanted
            LwWakeWord.setPowerSave(context, wanted)
            active = LwWakeWord.powerSave(context)
            note = resumeListening()
        },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_wake_power_window),
        summary = when {
            window.isEmpty() -> stringResource(
                R.string.settings_wake_power_window_none,
                stringResource(R.string.settings_wake_power_window_hint),
            )

            // 现在生效的是定时那一段 (手动开关关着): 说清"此刻在省电"
            LwWakeWord.powerWindowActive(context) -> stringResource(
                R.string.settings_wake_power_window_now,
                window,
            )

            else -> stringResource(R.string.settings_wake_power_window_set, window)
        },
        onClick = { editingWindow = true },
    )
    // 第 15 条那一条: 省电时连自动指令里那几个**费电的**监测一起停 —— 停哪些、为什么保留通知与时间,
    // 写在那一句说明里 (那正是主人要看的口径)。**这一行此刻只记选择**: 自动指令在批次 8 才落地
    SwitchPreference(
        title = stringResource(R.string.settings_wake_power_save_automation),
        summary = stringResource(R.string.settings_wake_power_save_automation_summary),
        checked = automation,
        onCheckedChange = { wanted ->
            automation = wanted
            LwWakeWord.setPowerSaveAutomation(context, wanted)
        },
    )
    if (editingWindow) {
        PowerWindowDialog(
            initial = window,
            onDismissRequest = { editingWindow = false },
            onConfirm = { text ->
                editingWindow = false
                // 看不懂的那一段由 setPowerWindow 拒绝 (它回一句人话), 拒绝时什么都不写 —— 那时
                // 不必再试"把监听拉回来" (省电状态一个字节都没动)
                val problem = runCatching { LwWakeWord.setPowerWindow(context, text) }
                    .getOrElse { throwable -> throwable.message ?: throwable.toString() }
                window = LwWakeWord.powerWindowText(context)
                active = LwWakeWord.powerSave(context)
                // 定时那一段刚关掉 (或者改成"现在不在里面") 而监听没起: 让它照许可回来
                note = problem ?: resumeListening()
            },
        )
    }
}

/**
 * 唤醒词: 词表、模型与那个许可
 *
 * 三件事分三段说清, 因为它们的处置完全不同: **词表**是用户自己写的 (写错时 sherpa-onnx 会把整行
 * 静默丢掉, 所以这里逐 token 核对后才写), **模型**是那 5.3 MB 的下载 (装完才对得上符号表), 而
 * **那个开关是许可、不是"现在就常驻"**:
 *
 * - 「允许唤醒」= 允不允许这个应用听着唤醒词 (缺省开), 它只决定服务起不起来
 * - ~~「允许常驻语音」~~ = **已经删掉了** (2026-10-06): 那个许可开着时识别链一直不收, 对话会一直
 *   进行下去。现在命中 (或球上点一下) 只买一句话, 闲置 10 s 由服务自己收回来, 没有开关可给
 *
 * 改动之前这里只有一个开关, 而它直接等于常驻监听 (`checked = WakeWordState.listening`) —— 主人
 * 2026-10-05 判定的那个错就落在这一行: 打开"允许唤醒"顺手把常驻麦克风也打开了, 现在这个许可
 * **只写偏好、什么都不启动**, 运行时状态另外如实显示 (状态那一行说三件事: 唤醒词在守着哪几个词 /
 * 许可开着而服务没起 / 服务在跑而这一句的窗口也开着)
 *
 * 每秒看一眼: 下载进度、那个状态、命中数都是别处改的 (服务在另一个进程状态里跑), 不轮询界面就是
 * 死的 —— 这几条读的都是内存、两份偏好与四次 `File.length()`, 一秒一次的花销可以忽略
 */
@Composable
private fun WakeItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 编辑框的初值: 人存过就是那一份, **没存过就是真正的缺省那张词表** —— 不能给空
    // (空框会让人以为"没有词", 而把敲进去的那一句当成"新增"), 也不能给另一份缺省
    var words by remember {
        mutableStateOf(LwWakeWord.words(context).ifEmpty { WakeWordWords.defaultText() })
    }
    var names by remember { mutableStateOf(LwWakeWord.names(context)) }
    var allowWake by remember { mutableStateOf(LwWakeWord.allow(context)) }
    var onHit by remember { mutableStateOf(LwWakeWord.onHit(context)) }
    var vibrate by remember { mutableStateOf(LwWakeWord.vibrate(context)) }
    var listening by remember { mutableStateOf(WakeWordState.listening) }
    var resident by remember { mutableStateOf(WakeWordState.voiceActive) }
    var hits by remember { mutableStateOf(WakeWordState.hits) }
    // 省电模式此刻生不生效: 那三行搬去 [PowerItems] 了, 而状态那一行还要拿它说"为什么没在听"
    var powerSave by remember { mutableStateOf(LwWakeWord.powerSave(context)) }
    var ready by remember { mutableStateOf(WakeWordDownload.readyCount(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val total = WakeWordDownload.files.size
    val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    // 这几句是给开关那些分支念的, 而 stringResource 不能进 when 的 lambda 里之后再取, 所以先取出来
    val needModel = stringResource(R.string.settings_wake_need_model)
    val micMissing = stringResource(R.string.settings_wake_mic_missing)
    LaunchedEffect(Unit) {
        WakeWordDownload.refresh(context)
        while (true) {
            allowWake = LwWakeWord.allow(context)
            onHit = LwWakeWord.onHit(context)
            vibrate = LwWakeWord.vibrate(context)
            listening = WakeWordState.listening
            resident = WakeWordState.voiceActive
            hits = WakeWordState.hits
            powerSave = LwWakeWord.powerSave(context)
            ready = WakeWordDownload.readyCount(context)
            names = LwWakeWord.names(context)
            delay(POLL_MS)
        }
    }
    // fillMaxWidth 是必要的: 这一段的子项是纯文字, 撑不满宽度, 而 Miuix 的 Card 是包着内容的
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                // 省电模式开着时, 下面那两句说的都不是真相 (服务活着而麦克风是关着的) —— 这一档最先说
                powerSave -> stringResource(R.string.settings_wake_state_power_save)
                // 常驻那一档只会在视频模式里出现 (识别链留着 = 常驻语音在跑)
                listening && resident -> stringResource(R.string.settings_wake_state_voice)
                listening -> stringResource(
                    R.string.settings_wake_state_listening,
                    // **念的是 `keywords.txt` 里那几个词** (真正在守的那一份), 不是偏好里那一份:
                    // 2026-10-06 主人报的"设置说明的唤醒词和真实的不一致"就是这里念错了来源
                    names.joinToString("」「").ifEmpty { WakeWordWords.displayName(WakeWordWords.DEFAULT) },
                )
                // 许可开着而服务没起: 这一句与"许可关着"必须分得开, 否则人不知道是许可没给还是没起来
                allowWake && microphone && ready == total ->
                    stringResource(R.string.settings_wake_state_allowed)
                else -> stringResource(R.string.settings_wake_state_idle)
            },
        )
        if (hits > 0) {
            Text(
                text = stringResource(R.string.settings_wake_hits, hits),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        if (!microphone) {
            Text(text = micMissing, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
        val problem = WakeWordState.lastError ?: note
        if (problem != null) {
            Text(text = problem, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
    }
    // 第一个开关是**许可**, 不是"现在就常驻": 它只决定服务起不起来听唤醒词
    SwitchPreference(
        title = stringResource(R.string.settings_wake_allow),
        summary = stringResource(R.string.settings_wake_allow_summary),
        checked = allowWake,
        onCheckedChange = { wanted ->
            LwWakeWord.setAllow(context, wanted)
            allowWake = wanted
            note = if (wanted) {
                when {
                    // 缺什么先说什么, 不去替人按下那个 5 MB 的下载 (开关不该是一个下载按钮)
                    !microphone -> micMissing
                    ready < total -> needModel
                    // 起不来时把原因原样说出来 (缺模型 / 缺词表各有各的说法), 不装作打开了
                    else -> runCatching { LwWakeWord.listen(context) }.exceptionOrNull()?.let { throwable ->
                        throwable.message ?: throwable.toString()
                    }
                }
            } else {
                LwWakeWord.hush(context)
                null
            }
        },
    )
    // **第二个开关 (「允许常驻语音」) 拿掉了, 而常驻语音本身留着** (主人 2026-10-06 定的口径):
    // 那个开关能在手机模式下把识别链打开, 结果就是对话一直进行下去 —— 主人判定多余的是那两个入口
    // (它和浮标菜单里那行「一直听」), 不是这条链。现在**只有视频模式能要求它留着** (切模式那一步写
    // `modes/voice-resident.on` 那个记号), 所以这里没有开关可给, 但状态那一行照旧如实说
    //
    // **原来在这里的省电三行搬走了** (主人 2026-10-08: "省电模式单独拿出来放第一排"): 手动开关 /
    // 定时那一段 / 省电时停自动指令, 三条现在都在页面最上面那一段 ([PowerItems])。它留在这一段时
    // 有个说不通的地方 —— "现在别听我说话"与"我许可你听"是两笔账, 而后者才是这一段管的事
    // 第三个设置: **命中之后干什么** (批次 4.4)。两条"切模式"走的是与说出来一样的那条命令路 ——
    // 命令词表只有一份, 在宿主插件里 (可行性稿 2.7), 这里再写一遍就成了第二份实现
    val hitActions = listOf(LwWakeWord.HIT_WAKE, LwWakeWord.HIT_VIDEO, LwWakeWord.HIT_PHONE)
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_wake_action),
        summary = stringResource(R.string.settings_wake_action_summary),
        items = listOf(
            stringResource(R.string.settings_wake_action_wake),
            stringResource(R.string.settings_wake_action_video),
            stringResource(R.string.settings_wake_action_phone),
        ),
        selectedIndex = hitActions.indexOf(onHit).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            onHit = hitActions[index.coerceIn(0, hitActions.lastIndex)]
            LwWakeWord.setHit(context, onHit, vibrate)
            LwWakeWord.refresh(context)
        },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_wake_vibrate),
        summary = stringResource(R.string.settings_wake_vibrate_summary),
        checked = vibrate,
        onCheckedChange = { wanted ->
            vibrate = wanted
            LwWakeWord.setHit(context, onHit, wanted)
            LwWakeWord.refresh(context)
        },
    )
    // 下载那一条: 装完把缺省词表写上, 并**只把唤醒词起起来** —— 常驻语音那半条不跟着开
    // (改动之前这里顺手 listen() 等于把常驻也打开, 是同一个错的第二个入口)
    ArrowPreference(
        title = stringResource(R.string.settings_wake_download),
        summary = when {
            WakeWordDownload.phase == WakeWordDownload.Phase.DOWNLOADING -> stringResource(
                R.string.settings_wake_downloading,
                "%.1f MB".format(WakeWordDownload.received / 1024.0 / 1024.0),
                "%.1f MB".format(WakeWordDownload.total / 1024.0 / 1024.0),
            )

            WakeWordDownload.phase == WakeWordDownload.Phase.FAILED ->
                stringResource(R.string.settings_wake_failed, WakeWordDownload.detail)

            ready == total -> stringResource(R.string.settings_wake_model_ready, total)
            ready == 0 -> stringResource(
                R.string.settings_wake_model_missing,
                "%.1f MB".format(WakeWordDownload.files.sumOf { it.bytes } / 1024.0 / 1024.0),
            )

            else -> stringResource(R.string.settings_wake_model_partial, ready, total)
        },
        onClick = {
            // 下载中再按一下不该开第二趟: 两个线程往同一个 .part 上写就是坏文件
            if (WakeWordDownload.phase == WakeWordDownload.Phase.DOWNLOADING) return@ArrowPreference
            scope.launch {
                note = withContext(Dispatchers.IO) {
                    runCatching {
                        val said = WakeWordDownload.download(context)
                        // 模型没下过时 keywords.txt 也还没写过: 补上**缺省那张词表** (不是偏好里那一份)
                        if (LwWakeWord.names(context).isEmpty()) {
                            LwWakeWord.setWords(context, WakeWordWords.defaultText())
                        }
                        // **许可是前提**: 没允许唤醒就只把模型放好, 不替人把监听起起来
                        // (改动之前这里无条件 listen(), 那是"开关 = 常驻监听"那个错的第二个入口)
                        if (!LwWakeWord.allow(context)) {
                            "$said, 模型已就位; 上面那个「允许唤醒」开着才会开始听"
                        } else if (!WakeWordState.listening) {
                            runCatching { LwWakeWord.listen(context) }
                                .exceptionOrNull()
                                ?.let { "$said, 但是没能开始听: ${it.message ?: it}" }
                                ?: "$said, 已经开始听唤醒词"
                        } else {
                            // 换了模型文件时正在跑的那一份要重新读: 停一下再起
                            LwWakeWord.hush(context)
                            runCatching { LwWakeWord.listen(context) }
                                .exceptionOrNull()
                                ?.let { "$said, 但是没能重新开始听: ${it.message ?: it}" }
                                ?: "$said, 已重新开始听唤醒词"
                        }
                    }.getOrElse { it.message ?: it.toString() }
                }
            }
        },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_wake_words),
        summary = if (names.isEmpty()) {
            stringResource(R.string.settings_wake_words_none, WakeWordWords.displayName(WakeWordWords.DEFAULT))
        } else {
            stringResource(R.string.settings_wake_words_now, names.joinToString(", "))
        },
        onClick = { editing = true },
    )
    if (editing) {
        // stringResource 不能在 runCatching 里调 (它不是组合上下文), 先把要念的两句取出来
        val savedText = stringResource(R.string.settings_wake_words_saved, "")
        val unsavedText = stringResource(R.string.settings_wake_words_unsaved, "")
        WakeWordDialog(
            initial = words,
            onDismissRequest = { editing = false },
            onConfirm = { text ->
                editing = false
                note = runCatching {
                    val saved = LwWakeWord.setWords(context, text)
                    words = text
                    names = saved
                    val said = savedText + saved.joinToString(", ")
                    // 词表换了正在跑的那一份要重新读: 停一下再起才算真的换上了
                    if (WakeWordState.listening) {
                        LwWakeWord.hush(context)
                        runCatching { LwWakeWord.listen(context) }
                            .exceptionOrNull()
                            ?.let { "$said, 但是重启监听失败: ${it.message ?: it}" }
                            ?: said
                    } else {
                        said
                    }
                }.getOrElse { throwable ->
                    unsavedText + (throwable.message ?: throwable.toString())
                }
            },
        )
    }
}

/**
 * 词表那个编辑框
 *
 * 显示的是**人写的那种格式** (`词=带音调数字的拼音`), 不是 keywords.txt 里的 token 序列 —— 后者是
 * 给 sherpa-onnx 看的, 让人编辑等于让人手算声调符号。两种写法由 [LwWakeWord.setWords] 翻译, 翻不
 * 过去时它会说清是哪一个 token 对不上
 */
@Composable
private fun WakeWordDialog(
    initial: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_wake_words),
        summary = stringResource(R.string.settings_wake_words_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onConfirm(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 省电时段那个编辑框 (主人 2026-10-07: "定时开关…由用户自己定时间")
 *
 * 形状与词表那个一样: 一个文本框 + 取消/确定。**看不懂的写法不写** —— 由
 * [io.github.yuloong07star.luwi.tool.LwWakeWord.setPowerWindow] 拒绝, 拒绝的那一句由调用方念在
 * 状态那一行上, 所以填错了这里什么都不变 (而不是存下一句谁也不认识的时间)
 */
@Composable
private fun PowerWindowDialog(
    initial: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_wake_power_window),
        summary = stringResource(R.string.settings_wake_power_window_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.settings_wake_power_window_hint),
            // 空着时那行提示就是"该长什么样" (这一行本来就允许空 = 关掉定时)
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onConfirm(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 「说话」那一段里要打字的几行: API 的四项 + Edge 的自定义音色 */
private enum class SpeakText { API_URL, API_KEY, API_MODEL, API_VOICE, EDGE_VOICE }

/**
 * 那几行的编辑框
 *
 * 标题与说明按 [field] 取; **密钥那一行用密码变换** —— 它不该明文躺在屏幕上 (存也只存在本应用的
 * 偏好文件里, 回执与日志里只报"有没有", 见 LwSpeak.status)
 */
@Composable
private fun SpeakTextFieldDialog(
    field: SpeakText,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val title = stringResource(
        when (field) {
            SpeakText.API_URL -> R.string.settings_speak_api_url
            SpeakText.API_KEY -> R.string.settings_speak_api_key
            SpeakText.API_MODEL -> R.string.settings_speak_api_model
            SpeakText.API_VOICE -> R.string.settings_speak_api_voice
            SpeakText.EDGE_VOICE -> R.string.settings_speak_edge_custom
        },
    )
    val summary = stringResource(
        when (field) {
            SpeakText.API_URL -> R.string.settings_speak_api_url_dialog
            SpeakText.API_KEY -> R.string.settings_speak_api_key_dialog
            SpeakText.API_MODEL -> R.string.settings_speak_api_model_dialog
            SpeakText.API_VOICE -> R.string.settings_speak_api_voice_dialog
            SpeakText.EDGE_VOICE -> R.string.settings_speak_edge_custom_dialog
        },
    )
    val initial = when (field) {
        SpeakText.API_URL -> SpeakSettings.apiUrl
        SpeakText.API_KEY -> SpeakSettings.apiKey
        SpeakText.API_MODEL -> SpeakSettings.apiModel
        SpeakText.API_VOICE -> SpeakSettings.apiVoice
        SpeakText.EDGE_VOICE -> SpeakSettings.edgeVoice
    }
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            visualTransformation = if (field == SpeakText.API_KEY) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onConfirm(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 设置页那一段的轮询间隔: 只喂状态文字, 不用更快 */
private const val POLL_MS = 1000L

/**
 * 系统那个「文字转语音输出」页
 *
 * 用字面值而不是 `Settings.ACTION_TTS_SETTINGS`: 与全屏通知那一页同一个理由 —— 那个常量在某些
 * 编译 SDK 上取不到, 而这一页本身是各 ROM 都有的
 */
private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

/**
 * 跳去系统的「文字转语音输出」
 *
 * 与通知那两条同一个写法: 个别 ROM 上这一页没有接收者 (`startActivity` 会抛, 而这是点击换来的
 * 崩溃), 所以退到应用详情页
 */
private fun openTtsSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(TTS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 系统的「声音」页: 响度不在应用手里时那一行点它去这里 (媒体音量在那里) */
private fun openSoundSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 端侧 OCR: 自绘界面上的字只能从像素里读
 *
 * 状态是**懒加载**来的: 模型要等第一次用到才建 session (NPU 那边第一次还要现场编译图, 约 2 秒),
 * 所以这里平时显示"未加载", 那个按钮既是自检也是预热
 */
@Composable
private fun OcrItems() {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val backend = LwOcr.backend
    // label 与 note 来自模型那一侧 (桥也把它们交给工具), 所以它们不是这一页的字符串, 只有那一圈
    // 说明文字跟着资源走
    val unit = if (backend == LwOcr.Backend.NPU) {
        "${backend.label} (${Build.SOC_MODEL})"
    } else {
        backend.label
    }
    Column(modifier = Modifier.padding(UiSpacing.Large)) {
        Text(text = stringResource(R.string.settings_ocr_backend, unit))
        if (LwOcr.note.isNotEmpty()) {
            Text(text = LwOcr.note, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
        Button(
            onClick = {
                working = true
                scope.launch {
                    withContext(Dispatchers.IO) { LwOcr.probe(3, null, false, "burst") }
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().padding(top = UiSpacing.Medium),
        ) {
            Text(
                text = stringResource(
                    if (working) R.string.settings_ocr_probe_busy else R.string.settings_ocr_probe,
                ),
            )
        }
    }
}
