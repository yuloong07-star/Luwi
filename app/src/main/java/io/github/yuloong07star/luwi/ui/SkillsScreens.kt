package io.github.yuloong07star.luwi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.AppSkills
import io.github.yuloong07star.luwi.scaffolds.LazyColumn
import io.github.yuloong07star.luwi.scaffolds.SuperTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 「查看已装 app-skill」那一页 (2.7.8 批次 5, 主人 2026-10-17: 设置里的技能要分两页 —— 已装的那页里
 * 检测更新 / 一键升级 / 卸载, 可用的那页里刷新与全部安装; 技能不再直接摆在技能段下面)
 *
 * 三条读数的口径:
 *
 * - **检测更新** = 拿本机缓存的那份索引挨个比 (版本不同, 或者正文摘要不同), 真正的"盖不盖"仍然由
 *   [AppSkills.upgradeFrom] 按逐文件摘要决定 —— 所以"版本号没变但内容改了"这一档也升得动
 * - **一键升级** 只盖"本地摘要 == 账上摘要"的文件; 主人手改过的那几个不盖, 这里只如实报个数
 * - **卸载** 是这一页唯一不可逆的动作, 先问一句 (与「快捷指令」那一段同一个做法)
 */
@Composable
fun SkillsInstalledScreen() {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<AppSkills.Installed?>(null) }
    val installed = remember(revision) { AppSkills.installed(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.skills_installed_title),
                scrollBehavior = scrollBehavior,
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
    ) { padding ->
        LazyColumn(contentPadding = padding, scrollBehavior = scrollBehavior) {
            item {
                ArrowPreference(
                    title = stringResource(R.string.skills_check_updates),
                    summary = stringResource(R.string.skills_check_updates_summary, AppSkills.REPO),
                    onClick = {
                        if (busy) return@ArrowPreference
                        haptic.contextClick()
                        busy = true
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                AppSkills.refresh(context)
                                AppSkills.updates(context, AppSkills.index(context))
                            }
                            message = context.getString(
                                if (found.isEmpty()) R.string.skills_up_to_date else R.string.skills_updates_found,
                                found.size,
                            )
                            busy = false
                        }
                    },
                )
            }
            item {
                ArrowPreference(
                    title = if (busy) stringResource(R.string.skills_busy) else stringResource(R.string.skills_upgrade_all),
                    summary = stringResource(R.string.skills_upgrade_all_summary),
                    onClick = {
                        if (busy) return@ArrowPreference
                        haptic.confirm()
                        busy = true
                        scope.launch {
                            val reports = withContext(Dispatchers.IO) {
                                AppSkills.upgradeAll(context, AppSkills.index(context))
                            }
                            val wrote = reports.sumOf { it.wrote.size }
                            val skipped = reports.sumOf { it.skipped.size }
                            message = context.getString(R.string.skills_upgraded, wrote, skipped)
                            revision += 1
                            busy = false
                        }
                    },
                )
            }
            if (installed.isEmpty()) {
                item { Notice(stringResource(R.string.skills_none_installed)) }
            }
            items(installed.size) { index ->
                val skill = installed[index]
                val version = skill.version.ifBlank { "?" }
                val edited = if (skill.edited > 0) {
                    " · " + stringResource(R.string.skills_edited, skill.edited)
                } else {
                    ""
                }
                ArrowPreference(
                    title = skill.id,
                    summary = stringResource(R.string.skills_version, version, skill.files) + edited,
                    onClick = {
                        haptic.contextClick()
                        asking = skill
                    },
                )
            }
            if (message.isNotBlank()) item { Notice(message) }
        }
    }

    asking?.let { skill ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.skills_uninstall),
            summary = stringResource(R.string.skills_uninstall_summary, skill.id),
            defaultWindowInsetsPadding = false,
            onDismissRequest = { asking = null },
        ) {
            TextButton(
                text = stringResource(R.string.skills_uninstall),
                onClick = {
                    haptic.confirm()
                    asking = null
                    busy = true
                    scope.launch {
                        val report = withContext(Dispatchers.IO) { AppSkills.remove(context, listOf(skill.id)) }
                        message = context.getString(R.string.skills_removed, report.wrote.size)
                        revision += 1
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    asking = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 「查看本机可用 app-skill」那一页: 索引里全部技能 + 刷新技能 + 全部安装
 *
 * 这一页替掉了原来技能段下面那两行 (「刷新技能目录」与「把装了那些应用的技能一次装上」) —— 那两件事
 * 都属于"挑技能"这个动作, 摆在二级页里才不会把设置页撑长
 */
@Composable
fun SkillsAvailableScreen() {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var index by remember { mutableStateOf<List<AppSkills.RemoteSkill>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    // 升级时"被本地改过"的那几个文件: 先问一句 (与面板那一条同一个口径)
    var edited by remember { mutableStateOf<Pair<AppSkills.RemoteSkill, List<String>>?>(null) }

    val reload: () -> Unit = {
        scope.launch {
            index = withContext(Dispatchers.IO) { AppSkills.index(context) }
            installed = withContext(Dispatchers.IO) { AppSkills.present(context) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    // 候选表在 composable 作用域里算 (LazyListScope 里不能 remember)
    val candidates = remember(index, query) { AppSkills.search(index, query) }

    val install: (AppSkills.RemoteSkill) -> Unit = { skill ->
        if (!busy) {
            busy = true
            haptic.confirm()
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppSkills.installFrom(context, skill) }
                message = AppSkills.sentenceOf(report, "install")
                reload()
                busy = false
            }
        }
    }
    val upgrade: (AppSkills.RemoteSkill, Boolean) -> Unit = { skill, force ->
        if (!busy) {
            busy = true
            haptic.confirm()
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppSkills.upgradeFrom(context, skill, force) }
                if (report.skipped.isNotEmpty() && !force) {
                    edited = skill to report.skipped
                } else {
                    message = AppSkills.sentenceOf(report, "upgrade")
                }
                reload()
                busy = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.skills_available_title),
                scrollBehavior = scrollBehavior,
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
    ) { padding ->
        LazyColumn(contentPadding = padding, scrollBehavior = scrollBehavior) {
            item {
                SuperTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.settings_skills_browse_query),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = if (busy) stringResource(R.string.skills_busy) else stringResource(R.string.skills_refresh),
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            haptic.contextClick()
                            scope.launch {
                                val (count, said) = withContext(Dispatchers.IO) { AppSkills.refresh(context) }
                                message = said
                                if (count > 0) reload()
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(R.string.skills_install_all),
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            haptic.confirm()
                            scope.launch {
                                val reports = withContext(Dispatchers.IO) {
                                    AppSkills.installAll(context, AppSkills.index(context))
                                }
                                message = context.getString(
                                    R.string.skills_installed_count,
                                    reports.count { it.wrote.isNotEmpty() },
                                    reports.count { it.failed.isNotEmpty() },
                                )
                                reload()
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            when {
                loading -> item { Notice(stringResource(R.string.settings_skills_browse_loading)) }
                index.isEmpty() -> item { Notice(stringResource(R.string.settings_skills_browse_empty)) }
                candidates.isEmpty() -> item { Notice(stringResource(R.string.settings_skills_browse_none, query)) }
            }
            items(candidates.size) { position ->
                val skill = candidates[position]
                val have = skill.id in installed
                val remote = skill.version.ifBlank { "?" }
                val versions = if (have) {
                    stringResource(
                        R.string.settings_skills_browse_installed,
                        AppSkills.installedVersion(context, skill.id).ifBlank { "?" },
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
                ArrowPreference(
                    title = listOf(skill.title, skill.version).filter { it.isNotBlank() }.joinToString(" "),
                    summary = listOf(versions + risk, skill.description)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                    onClick = {
                        if (busy) return@ArrowPreference
                        if (skill.id in installed) upgrade(skill, false) else install(skill)
                    },
                )
            }
            if (message.isNotBlank()) item { Notice(message) }
        }
    }

    edited?.let { (skill, files) ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.settings_skills_browse_edited_title),
            summary = stringResource(R.string.settings_skills_browse_edited_summary, files.joinToString(", ")),
            defaultWindowInsetsPadding = false,
            onDismissRequest = { edited = null },
        ) {
            TextButton(
                text = stringResource(R.string.settings_skills_browse_edited_keep),
                onClick = {
                    haptic.contextClick()
                    edited = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.settings_skills_browse_edited_overwrite),
                onClick = {
                    haptic.confirm()
                    edited = null
                    upgrade(skill, true)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 一页里那种"一句说明"的小字 (与设置页那一段的 PlaceholderItems 同一个观感) */
@Composable
private fun Notice(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = text, color = colorScheme.onSurfaceVariantActions)
    }
}
