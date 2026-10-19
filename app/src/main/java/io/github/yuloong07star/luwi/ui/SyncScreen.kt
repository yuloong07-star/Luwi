package io.github.yuloong07star.luwi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.constants.UiSpacing
import io.github.yuloong07star.luwi.sync.SyncAccount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 「sync 签到」那一页 (2.7.9 第 4 条): **这一版只做壳**
 *
 * 主人 2026-10-19 那份 2.docx 的第 4 条要的是"删捐赠页, 上 sync 页"; 而真接口 (签到送一天 /
 * 1 元 7 天 / 3 元 30 天 / 30 元 365 天) 要等同步那条的后端契约 (施工单 §2.2 的「依赖」)。所以这一页
 * 现在只列五件事: 账号状态 / 到期时间 / 签到 / 充值 / 解绑本机 —— **没有后端时每一件都如实说
 * 「未连接账号」或「还没有接上后端」, 不假装有额度, 也不写任何假日期**
 *
 * 为什么按钮不弹 OverlayDialog: 这一页是 `navigator.push` 出来的, 而 Miuix 的 OverlayDialog 在
 * 这一层挂不上窗口 (2026-10-17 在技能那两页上量到的, 见 [SkillsInstalledScreen]); 所以这里的回复
 * 是一行 [Text], 与那两页的 Notice 同一个做法
 */
@Composable
fun SyncScreen() {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    val account = remember(revision) { SyncAccount.read(context) }
    val backendMissing = stringResource(R.string.settings_sync_account_backend_missing)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_sync_account_title),
                navigationIcon = {
                    IconButton(onClick = navigator.pop) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = innerPadding) {
            item {
                // 状态这两行是纯文字, 自己缩进 (与特权通道那一段同一个做法: 设置项自带 16dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = UiSpacing.Large,
                            end = UiSpacing.Large,
                            top = UiSpacing.Large,
                            bottom = UiSpacing.Medium,
                        ),
                ) {
                    Text(
                        text = account?.account
                            ?: stringResource(R.string.settings_sync_account_not_connected),
                        color = if (account == null) colorScheme.onSurfaceVariantActions else colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_sync_account_note),
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = UiSpacing.Medium),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_sync_account_expiry_line,
                            if (account?.expiresAt?.let { it > 0 } == true) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                    .format(Date(account.expiresAt))
                            } else {
                                "—"
                            },
                        ),
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = UiSpacing.Small),
                    )
                }
            }
            item {
                ArrowPreference(
                    title = stringResource(R.string.settings_sync_account_sign_in),
                    summary = stringResource(R.string.settings_sync_account_sign_in_summary),
                    onClick = {
                        haptic.contextClick()
                        message = backendMissing
                    },
                )
            }
            item {
                ArrowPreference(
                    title = stringResource(R.string.settings_sync_account_topup),
                    summary = stringResource(R.string.settings_sync_account_topup_summary),
                    onClick = {
                        haptic.contextClick()
                        message = backendMissing
                    },
                )
            }
            item {
                ArrowPreference(
                    title = stringResource(R.string.settings_sync_account_unbind),
                    summary = stringResource(R.string.settings_sync_account_unbind_summary),
                    onClick = {
                        haptic.contextClick()
                        message = if (SyncAccount.unbind(context)) {
                            context.getString(R.string.settings_sync_account_unbound)
                        } else {
                            context.getString(R.string.settings_sync_account_nothing_bound)
                        }
                        revision += 1
                    },
                )
            }
            message?.let { line ->
                item {
                    Text(
                        text = line,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiSpacing.Large, vertical = UiSpacing.Medium),
                    )
                }
            }
        }
    }
}
