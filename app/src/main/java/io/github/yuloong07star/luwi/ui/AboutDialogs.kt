package io.github.yuloong07star.luwi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.scaffolds.SuperTextField
import io.github.yuloong07star.luwi.update.AboutSettings
import io.github.yuloong07star.luwi.update.ReleaseJson
import io.github.yuloong07star.luwi.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * ⋮ 菜单里那几件事的界面: 反馈 / 检测更新 / 关于 (2026-10-08 第 4 条; 捐赠页 2026-10-19 下线,
 * 反馈页 2026-10-19 补上)
 *
 * 四个对话框排成一个"页" ([AboutPage]), 而**同一时刻只开一个** —— 由调用方 (设置页) 拿着那一个
 * [AboutPage] 变量决定画哪一个。这样从"关于"里点进"更新源"再关掉时不会叠两层窗, 也免得两个
 * `OverlayDialog` 同时挂在树上 (它们的窗口层级不是我们能摆的)
 *
 * 三条口径与开发计划 2.4 一致: **只检测不代装** (去下载交给系统浏览器), **更新源可改** (缺省是
 * GitHub 的 Release API, 另有一条镜像), 以及 **sync 那一页这一版只有壳** (后端没接上时如实说未连接)
 *
 * 「反馈」那一页在 `ui/FeedbackDialog.kt`: 它是这一组里唯一带输入框的窗, 单独一份文件比塞进来清楚
 */
internal enum class AboutPage {
    /** 反馈: 写一段话 (500 字) 挑一种方式, 见 `ui/FeedbackDialog.kt` */
    FEEDBACK,

    /** 关于: 版本号 + 那三条入口 */
    ABOUT,

    /** 检测更新: 查一次, 把结果念出来 */
    UPDATE,

    /** 更新源: 改那个地址 */
    SOURCE,
}

/**
 * 「关于」: 版本号与三条入口
 *
 * 版本号那两项直接问系统 (`PackageManager`) 而不是 `BuildConfig` —— 后者要开 `buildConfig` 那个
 * 生成开关, 而这个应用本来一个地方都没用到它
 */
@Composable
internal fun AboutDialog(
    onDismissRequest: () -> Unit,
    onOpen: (AboutPage) -> Unit,
) {
    val context = LocalContext.current
    val version = remember { UpdateCheck.localVersionOf(context) }
    val code = remember { UpdateCheck.localCodeOf(context) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_about_title),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.settings_about_version, version, code))
            ArrowPreference(
                title = stringResource(R.string.settings_about_update),
                summary = stringResource(R.string.settings_about_update_summary),
                onClick = { onOpen(AboutPage.UPDATE) },
            )
            ArrowPreference(
                title = stringResource(R.string.settings_about_source),
                summary = AboutSettings.source(context),
                onClick = { onOpen(AboutPage.SOURCE) },
            )
        }
        DialogButtons(
            other = null,
            main = stringResource(R.string.settings_close) to onDismissRequest,
        )
    }
}

/**
 * 「检测更新」: 打开就查一次, 结果那一行直接念
 *
 * 网络那一步放在 IO 上 (这里从来不阻塞界面线程), 而**结果那一行会跟着状态重画** —— 查的时候是
 * "正在查 …", 查完了换成"有新版本"或者"没查到: 为什么"。失败时那一行说的是**每一路各自的原因**,
 * 而不是"连不上"三个字 (见 [UpdateCheck.check])
 */
@Composable
internal fun UpdateDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase = UpdateCheck.phase
    val release = UpdateCheck.release
    val source = remember { AboutSettings.source(context) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UpdateCheck.check(context, source) }
    }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_update_title),
        summary = stringResource(R.string.settings_update_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (phase) {
                    UpdateCheck.Phase.IDLE,
                    UpdateCheck.Phase.CHECKING,
                    -> stringResource(R.string.settings_update_checking, source)

                    UpdateCheck.Phase.LATEST -> stringResource(
                        R.string.settings_update_latest,
                        UpdateCheck.localVersion,
                    )

                    UpdateCheck.Phase.NEWER -> stringResource(
                        R.string.settings_update_newer,
                        release?.version.orEmpty(),
                        UpdateCheck.localVersion,
                    )

                    UpdateCheck.Phase.FAILED -> stringResource(
                        R.string.settings_update_failed,
                        UpdateCheck.detail,
                    )
                },
            )
            problem?.let { reason ->
                Text(
                    text = reason,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 有新版本时把它的两个数也报出来: 发布于哪天、包多大 —— 那正是"要不要现在下"的判据
            if (phase == UpdateCheck.Phase.NEWER && release != null) {
                ReleaseFacts(release)
            }
        }
        // 第二动作只有查完之后才有: 有新版本是"去下载", 查完没新的 (或者没查到) 是"重试"
        // 这一句要在 `when` 外面先取好: 那个 `to` 右边的 lambda 不是组合上下文, 里面取不到字符串
        val noAddress = stringResource(R.string.settings_update_failed, "那条 Release 没给地址")
        val action: Pair<String, () -> Unit>? = when (phase) {
            UpdateCheck.Phase.NEWER -> stringResource(R.string.settings_update_open) to {
                // 有 APK 那一条就直连它, 否则去 Release 页面 —— 两者都没有时只报一句, 不做别的
                val target = release?.apkUrl?.takeIf { it.isNotEmpty() }
                    ?: release?.pageUrl?.takeIf { it.isNotEmpty() }
                problem = if (target == null) noAddress else openPage(context, target)
            }

            UpdateCheck.Phase.FAILED, UpdateCheck.Phase.LATEST -> stringResource(
                R.string.settings_update_retry,
            ) to {
                // "重试"就是再查一次: 状态换成正在查之后上面那一行会自己跟着变
                problem = null
                scope.launch(Dispatchers.IO) { UpdateCheck.check(context, source) }
            }

            // 正在查的那一档没有第二个动作, 只留"关闭"
            else -> null
        }
        DialogButtons(
            other = action?.let { stringResource(R.string.settings_close) to onDismissRequest },
            main = action ?: (stringResource(R.string.settings_close) to onDismissRequest),
        )
    }
}

/**
 * 冷启动查到的新版本 (2.7.9 第 3 条)
 *
 * 与手动那一趟 ([UpdateDialog]) 共用正文与那两个数, 差别只在按钮: 主「去下载」, 次「不再提示这次版本」
 * —— 后者记的是**远端那个版本号** ([AutoUpdate.skipVersion]), 于是它不再是"新的"之前都不再弹
 *
 * **为什么这一只不用 [OverlayDialog]**: 它是在主页那块 WebView 上面弹的, 而 WebView 是同一个窗口里的
 * **子 View** —— 组合里画的东西都在子 View 下面; Miuix 的 `OverlayDialog` 还要有人 (Scaffold) 托管才
 * 画得出来, 2026-10-19 在模拟器上量到的正是"组合了、看不见"。真正的 `Dialog` 是另一块窗口, 盖得住
 *
 * `onDismiss` 只关窗 (当天不再问, 明天冷启动还会再问一次), 与「不再提示」是两件事
 */
@Composable
internal fun AutoUpdateDialog(
    release: ReleaseJson.Release,
    localVersion: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val noAddress = stringResource(R.string.settings_update_failed, "那条 Release 没给地址")
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scrim = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                // 点窗外面就是"现在别问": 与「不再提示」不同, 明天还会再问
                .clickable(interactionSource = scrim, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    // 卡片自己吃掉点击, 不然点正文也会关窗
                    .clickable(interactionSource = scrim, indication = null) {},
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.settings_update_auto_title),
                        style = MiuixTheme.textStyles.title3,
                    )
                    Text(
                        text = stringResource(R.string.settings_update_newer, release.version, localVersion),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    ReleaseFacts(release)
                    problem?.let { reason ->
                        Text(
                            text = reason,
                            color = colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    // 两只按钮竖着排: 这几个字在手机上一行放不下, 横着排会折成两行 ("不再提示这次 /
                    // 版本"), 竖排各占整行反而干净
                    TextButton(
                        text = stringResource(R.string.settings_update_open),
                        onClick = {
                            // 与手动那条同一个去处: 有 APK 就直连它, 否则去 Release 页面
                            val target = release.apkUrl?.takeIf { it.isNotEmpty() }
                                ?: release.pageUrl.takeIf { it.isNotEmpty() }
                            if (target == null) {
                                problem = noAddress
                            } else {
                                // 打开成功了就把窗收掉: 从浏览器回来时不该还挡着一只"有新版本"的窗
                                val failure = openPage(context, target)
                                if (failure == null) onDismiss() else problem = failure
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = stringResource(R.string.settings_update_skip),
                        onClick = onSkip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** 新版本的那几个数 (发布日 / 包多大 / 说明): 手动与自动两条共用同一段 */
@Composable
private fun ReleaseFacts(release: ReleaseJson.Release) {
    val size = if (release.apkBytes > 0L) {
        stringResource(
            R.string.settings_update_apk,
            release.apkName.orEmpty(),
            megabytes(release.apkBytes),
        )
    } else {
        ""
    }
    val line = listOf(
        release.published.takeIf { it.isNotEmpty() }
            ?.let { stringResource(R.string.settings_update_published, it) },
        size.takeIf { it.isNotEmpty() },
        release.notes.takeIf { it.isNotEmpty() },
    ).filterNotNull().joinToString("\n")
    if (line.isNotEmpty()) {
        Text(
            text = line,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 「更新源」: 那一个地址 */
@Composable
internal fun SourceDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val initial = remember { AboutSettings.source(context) }
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_source_title),
        summary = stringResource(R.string.settings_source_summary, AboutSettings.DEFAULT_SOURCE),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = stringResource(R.string.settings_source_title),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        DialogButtons(
            other = stringResource(R.string.button_cancel) to {
                haptic.contextClick()
                onDismissRequest()
            },
            main = stringResource(R.string.button_confirm) to {
                haptic.confirm()
                AboutSettings.setSource(context, text)
                // 地址换了, 上一次的结果就不作数了
                UpdateCheck.reset()
                onDismissRequest()
            },
        )
    }
}

/**
 * 打开一条外链: 起不来 (没有浏览器 / 地址不合法) 时回一句人话, 而不是静默什么都不发生
 *
 * 对话框里那一处把它念在窗里 (那一句看得见), 而 ⋮ 菜单里那一处**盒子已经关掉了**, 所以它把这一句
 * 交给 Toast (见 [SettingsScreen] 的捐赠那一项)
 */
internal fun openPage(context: Context, url: String): String? = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.exceptionOrNull()?.let { "打不开 $url: ${it.message ?: it}" }

/** 底下那一对按钮: [other] 为空时只画 [main], 而且它撑满整行 */
@Composable
private fun DialogButtons(
    main: Pair<String, () -> Unit>,
    other: Pair<String, () -> Unit>?,
) {
    // 主按钮 (确定 / 打开 / 去下载 / 重试) 用主色, 次要那一个不染色 —— 与这一页别的对话框同一个排法
    if (other == null) {
        TextButton(
            text = main.first,
            onClick = { main.second() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        return
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            text = other.first,
            onClick = { other.second() },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(20.dp))
        TextButton(
            text = main.first,
            onClick = { main.second() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** 字节数报成 MB (与唤醒词那一份同一个写法) */
private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)
