package io.github.yuloong07star.luwi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.AppSkills
import io.github.yuloong07star.luwi.scaffolds.LazyColumn
import io.github.yuloong07star.luwi.scaffolds.SuperTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
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
 * 技能那两页 (2.7.8 批次 5, 主人 2026-10-17 定的形状)
 *
 * - 「查看已装 app-skill」: 检测更新 / 一键升级 / 点一条卸载
 * - 「查看本机可用 app-skill」: 索引里全部技能 + 刷新技能 + 全部安装 + 点一条装或升级
 *
 * **确认不弹 OverlayDialog**, 而是在列表最上面摆一张 [ConfirmCard]: 这两页是 `navigator.push` 出来的,
 * 而 Miuix 的 OverlayDialog 在这一层挂不上窗口 —— 2026-10-17 在模拟器上量到的现象是"点了一条什么都
 * 没发生" (同一份 OverlayDialog 在设置页里、在仓库弹窗里都是好的)。卡片一样是"先问一句再动手",
 * 而且不依赖窗口层级
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
    val installed: List<AppSkills.Installed> = remember(revision) { AppSkills.installed(context) }

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
            // 点一条先问"卸不卸": 卡片摆在列表最上面
            asking?.let { skill ->
                item(key = "confirm") {
                    ConfirmCard(
                        title = skill.id,
                        body = stringResource(R.string.skills_version, skill.version.ifBlank { "?" }, skill.files) +
                            "\n" + stringResource(R.string.skills_uninstall_summary, skill.id),
                        primary = stringResource(R.string.skills_uninstall),
                        onPrimary = {
                            asking = null
                            busy = true
                            scope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    AppSkills.remove(context, listOf(skill.id))
                                }
                                message = context.getString(R.string.skills_removed, report.wrote.size)
                                revision += 1
                                busy = false
                            }
                        },
                        onCancel = { asking = null },
                    )
                }
            }
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
                val editedCount = if (skill.edited > 0) {
                    " · " + stringResource(R.string.skills_edited, skill.edited)
                } else {
                    ""
                }
                ArrowPreference(
                    title = skill.id,
                    summary = stringResource(R.string.skills_version, version, skill.files) + editedCount,
                    onClick = {
                        haptic.contextClick()
                        asking = skill
                    },
                )
            }
            if (message.isNotBlank()) item { Notice(message) }
        }
    }
}

/** 问"装不装 / 升不升"的那一条 (可用页用) */
private data class SkillAsk(val skill: AppSkills.RemoteSkill, val upgrade: Boolean)

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
    var asking by remember { mutableStateOf<SkillAsk?>(null) }
    var edited by remember { mutableStateOf<Pair<AppSkills.RemoteSkill, List<String>>?>(null) }

    val reload: () -> Unit = {
        scope.launch {
            index = withContext(Dispatchers.IO) { AppSkills.index(context) }
            installed = withContext(Dispatchers.IO) { AppSkills.present(context) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    val candidates = remember(index, query) { AppSkills.search(index, query) }

    val runInstall: (AppSkills.RemoteSkill) -> Unit = { skill ->
        if (!busy) {
            busy = true
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppSkills.installFrom(context, skill) }
                message = AppSkills.sentenceOf(report, "install")
                reload()
                busy = false
            }
        }
    }
    val runUpgrade: (AppSkills.RemoteSkill, Boolean) -> Unit = { skill, force ->
        if (!busy) {
            busy = true
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
            // 点一条先问"装不装 / 升不升"
            asking?.let { ask ->
                val skill = ask.skill
                item(key = "confirm") {
                    ConfirmCard(
                        title = skill.title,
                        body = skillSummary(context, skill, skill.id in installed),
                        primary = stringResource(
                            if (ask.upgrade) {
                                R.string.settings_skills_browse_upgrade
                            } else {
                                R.string.settings_skills_browse_install
                            },
                        ),
                        onPrimary = {
                            asking = null
                            if (ask.upgrade) runUpgrade(skill, false) else runInstall(skill)
                        },
                        onCancel = { asking = null },
                    )
                }
            }
            // 升级时"这几个文件本地改过": 同一张确认卡
            edited?.let { (skill, files) ->
                item(key = "edited") {
                    ConfirmCard(
                        title = stringResource(R.string.settings_skills_browse_edited_title),
                        body = stringResource(R.string.settings_skills_browse_edited_summary, files.joinToString(", ")),
                        primary = stringResource(R.string.settings_skills_browse_edited_overwrite),
                        onPrimary = {
                            edited = null
                            runUpgrade(skill, true)
                        },
                        onCancel = { edited = null },
                    )
                }
            }
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
                ArrowPreference(
                    title = listOf(skill.title, skill.version).filter { it.isNotBlank() }.joinToString(" "),
                    summary = skillSummary(context, skill, have),
                    onClick = {
                        if (busy) return@ArrowPreference
                        haptic.contextClick()
                        asking = SkillAsk(skill, have)
                    },
                )
            }
            if (message.isNotBlank()) item { Notice(message) }
        }
    }
}

/** 一行技能的一句话: 版本读数 + 风险 + 描述 (三个地方共用同一份说法) */
@Composable
private fun skillSummary(context: android.content.Context, skill: AppSkills.RemoteSkill, installed: Boolean): String {
    val remote = skill.version.ifBlank { "?" }
    val versions = if (installed) {
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
    return listOf(versions + risk, skill.description).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * 原来那颗「浏览 app-skill 仓库」弹窗 (主人 2026-10-17: "保留原来的浏览 appskill 仓库")
 *
 * 与 [SkillsAvailableScreen] 是同一份数据的两种走法: 这一份是**弹窗**、点行只选中 (前面打勾)、
 * 底部那颗按钮随选中项说话; 那一页是整页列表, 点行先弹一句确认
 */
@Composable
fun SkillBrowseDialog(
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf<List<AppSkills.RemoteSkill>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppSkills.RemoteSkill?>(null) }
    var edited by remember { mutableStateOf<Pair<AppSkills.RemoteSkill, List<String>>?>(null) }

    val reload: () -> Unit = {
        scope.launch {
            index = withContext(Dispatchers.IO) { AppSkills.index(context) }
            installed = withContext(Dispatchers.IO) { AppSkills.present(context) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    val candidates = remember(index, query) { AppSkills.search(index, query) }

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
                .heightIn(max = (screenHeight * 0.45f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                loading -> Notice(stringResource(R.string.settings_skills_browse_loading))
                index.isEmpty() -> Notice(stringResource(R.string.settings_skills_browse_empty))
                candidates.isEmpty() -> Notice(stringResource(R.string.settings_skills_browse_none, query))
            }
            candidates.forEach { skill ->
                val have = skill.id in installed
                val shown = if (selected?.id == skill.id) "\u2713 " else ""
                ArrowPreference(
                    title = shown + listOf(skill.title, skill.version).filter { it.isNotBlank() }.joinToString(" "),
                    summary = skillSummary(context, skill, have),
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
                onClick = {
                    if (refreshing) return@TextButton
                    refreshing = true
                    scope.launch {
                        val said = withContext(Dispatchers.IO) { AppSkills.refresh(context) }
                        refreshing = false
                        onMessage(said.second)
                        reload()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            val picked = selected
            val action = when {
                working -> stringResource(R.string.skills_busy)
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

/** 「要不要装 / 要不要卸」那一张确认卡 (摆在列表最上面, 不依赖窗口层级) */
@Composable
private fun ConfirmCard(
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = title, color = colorScheme.onSurface)
            Text(text = body, color = colorScheme.onSurfaceVariantActions, modifier = Modifier.padding(top = 6.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                TextButton(
                    text = primary,
                    onClick = {
                        haptic.confirm()
                        onPrimary()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.button_cancel),
                    onClick = {
                        haptic.contextClick()
                        onCancel()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 一页里那种"一句说明"的小字 */
@Composable
private fun Notice(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = text, color = colorScheme.onSurfaceVariantActions)
    }
}
