package io.github.yuloong07star.luwi.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.constants.UiSpacing
import io.github.yuloong07star.luwi.plugin.PluginAudit
import io.github.yuloong07star.luwi.plugin.PluginCapabilities
import io.github.yuloong07star.luwi.plugin.PluginManager
import io.github.yuloong07star.luwi.plugin.PluginSettings
import io.github.yuloong07star.luwi.plugin.PluginSignature
import io.github.yuloong07star.luwi.plugin.PluginStore
import io.github.yuloong07star.luwi.plugin.PluginTrust
import io.github.yuloong07star.luwi.scaffolds.LazyColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 插件那一页 (2.7.8 批次 6, 主人 2026-10-17: "点击插件可进入二级界面")
 *
 * 原来点列表里那一条弹出的是一个 `OverlayDialog`, 而现在它是一页 —— 因为要放的东西长过一屏:
 * 状态、**插件自己的设置** (协议 `ui.settings`, 这一批才接通)、能力、审计、停用与两种卸载
 *
 * **不在这一页弹 `OverlayDialog`**: 这一层是 `navigator.push` 出来的, 而 Miuix 的 OverlayDialog 在
 * 这一层挂不上窗口 (2026-10-17 在技能那两页上量到的, 现象是"点了什么都没发生")。所以这一页只有
 * 行与按钮, 每一颗点下去就动手, 结果说在页面顶部那一行
 */
@Composable
fun PluginDetailScreen(id: String) {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val entry = remember(revision) { PluginManager.entries(context).firstOrNull { it.id == id } }

    if (entry == null) {
        // 卸载完这一页就没有主人了: 退回插件列表 (不留在这一页上说"它不在了")
        LaunchedEffectOnce { navigator.pop() }
        return
    }

    val manifest = entry.manifest
    val audit = remember(revision) { PluginAudit.tail(context, id, 5) }
    val settings = remember(revision, manifest) {
        manifest?.let { PluginSettings.values(context, it) }.orEmpty()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = entry.name,
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
                PageNotice(
                    listOfNotNull(
                        "版本 ${entry.version}",
                        "前缀 ${manifest?.toolPrefix ?: "?"}",
                        if (entry.enabled) "在岗" else "停着",
                    ).joinToString(" · "),
                )
            }
            if (entry.problem != null) item { PageNotice(entry.problem.orEmpty()) }
            if (message.isNotBlank()) item { PageNotice(message) }

            // ① 停用 / 启用 (原来那颗按钮, 现在在这一页的最上面)
            item {
                ArrowPreference(
                    title = stringResource(
                        if (entry.enabled) R.string.settings_plugin_disable else R.string.settings_plugin_enable,
                    ),
                    summary = if (entry.enabled) {
                        "停掉之后它带来的工具与外观都不再生效, 数据留着"
                    } else {
                        "让它上岗"
                    },
                    onClick = {
                        haptic.confirm()
                        // 绑宿主那一步是阻塞的 (绑 Service / 等 :plugin 进程回话), 而这里是主线程
                        scope.launch {
                            message = withContext(Dispatchers.IO) {
                                if (entry.enabled) {
                                    PluginManager.disable(context, id)
                                } else {
                                    PluginManager.enable(context, id)
                                }
                            }
                            revision += 1
                        }
                    },
                )
            }

            // ② 插件自己的设置 (协议第 10 节 `ui.settings`): 每一项按它声明的类型画一个控件
            if (manifest != null && manifest.settings.isNotEmpty()) {
                item { PageNotice("它的设置") }
                items(manifest.settings.size) { index ->
                    val setting = manifest.settings[index]
                    PluginSettingRow(
                        setting = setting,
                        value = settings[setting.key] ?: setting.defaultValue,
                        onSet = { next ->
                            haptic.contextClick()
                            message = PluginSettings.set(context, manifest, setting.key, next)
                            // 设置可能直接决定桌面那一格长什么样 (对话组件正是如此): 改完当场重画
                            io.github.yuloong07star.luwi.plugin.widget.WidgetHost.refreshAll(context)
                            revision += 1
                        },
                    )
                }
            }

            // ③ 能力: 装的时候确认过的那几条, 这里随时能收
            if (manifest != null && manifest.declared.isNotEmpty()) {
                item { PageNotice(stringResource(R.string.settings_plugin_audit)) }
                items(manifest.declared.size) { index ->
                    val declared = manifest.declared[index]
                    val dangerous = PluginCapabilities.isDangerous(declared)
                    val wired = PluginCapabilities.isWired(declared)
                    SwitchPreference(
                        title = (if (dangerous) "★ " else "") + declared,
                        summary = buildString {
                            append(PluginCapabilities.summaryOf(declared))
                            if (!wired) append(" · ").append(context.getString(R.string.settings_plugin_not_wired))
                        },
                        checked = declared in entry.granted,
                        onCheckedChange = { on ->
                            message = PluginManager.grant(context, id, declared, on)
                            revision += 1
                        },
                    )
                }
            }

            // ④ inline 那两行: "它与 Luwi 同权限" 与 "它的作者在白名单里" 分开说
            if (manifest?.kind == "inline") {
                item { PageNotice(context.getString(R.string.settings_plugin_inline_same_uid)) }
                val identity = if (manifest.signed) PluginSignature.fingerprint(manifest.publisherKey) else ""
                item {
                    PageNotice(
                        if (identity.isNotBlank() && PluginTrust.allowed(context, identity)) {
                            context.getString(R.string.settings_plugin_inline_whitelisted, identity)
                        } else {
                            "它的作者不在白名单里 —— 这一份是开发者模式下装进来的本地包"
                        },
                    )
                }
            }

            PluginStore.leftover(context, id)?.let { old ->
                item { PageNotice("它以前那个伴侣应用 $old 已经用不上了, 可以卸掉 (这一页不会自动动它)") }
            }

            // ⑤ 自带界面 (只有伴侣是"另一个应用")
            if (manifest?.kind == "companion") {
                item {
                    ArrowPreference(
                        title = stringResource(R.string.settings_plugin_open),
                        summary = manifest.entryActivity.ifBlank { "打开它自带的那个应用" },
                        onClick = {
                            haptic.confirm()
                            scope.launch {
                                message = withContext(Dispatchers.IO) { PluginManager.open(context, id) }
                            }
                        },
                    )
                }
            }

            // ⑥ 审计: 最近五次能力调用
            item {
                PageNotice(
                    stringResource(R.string.settings_plugin_audit) + "\n" +
                        audit.joinToString("\n").ifBlank { stringResource(R.string.settings_plugin_audit_empty) },
                )
            }

            // ⑦ 卸载那两颗: 一颗连数据一起删, 一颗留着数据 (主人点名要"卸载要包括数据")
            item {
                TextButton(
                    text = stringResource(R.string.settings_plugin_uninstall),
                    onClick = {
                        haptic.confirm()
                        scope.launch {
                            val said = withContext(Dispatchers.IO) {
                                PluginManager.uninstall(context, id, keepData = false)
                            }
                            message = said
                            revision += 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            item {
                TextButton(
                    text = stringResource(R.string.settings_plugin_uninstall_keep),
                    onClick = {
                        haptic.confirm()
                        scope.launch {
                            val said = withContext(Dispatchers.IO) {
                                PluginManager.uninstall(context, id, keepData = true)
                            }
                            message = said
                            revision += 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 一项插件设置: 按声明的类型画一个能点的行
 *
 * 三档控件, 都是"点一下就变" (没有滑块也没有下拉): 这一页是 push 出来的, 而 Miuix 的 OverlayDropdown
 * 与本页那类弹窗在这一层挂不上窗口 —— 行式的控件最稳, 也刚好够用 (整数给 ±, 布尔给开关, 枚举轮换)
 */
@Composable
private fun PluginSettingRow(
    setting: PluginSettings.Setting,
    value: kotlinx.serialization.json.JsonElement,
    onSet: (kotlinx.serialization.json.JsonElement) -> Unit,
) {
    when (setting.type) {
        "bool" -> SwitchPreference(
            title = setting.title,
            summary = "开 / 关",
            checked = (value as? JsonPrimitive)?.booleanOrNullValue() ?: false,
            onCheckedChange = { on -> onSet(JsonPrimitive(on)) },
        )

        "enum" -> {
            val current = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val index = setting.options.indexOfFirst { it.value == current }.coerceAtLeast(0)
            ArrowPreference(
                title = setting.title,
                summary = setting.options.getOrNull(index)?.label.orEmpty() + " · 点一下换下一个",
                onClick = {
                    val next = setting.options[(index + 1) % setting.options.size.coerceAtLeast(1)]
                    onSet(JsonPrimitive(next.value))
                },
            )
        }

        "int" -> {
            val current = (value as? JsonPrimitive)?.longOrNullValue() ?: 0L
            val low = setting.min ?: 0L
            val high = setting.max ?: (current + 100)
            ArrowPreference(
                title = setting.title,
                summary = "$current (${low}~$high) · 点一下加一",
                onClick = {
                    val next = if (current + 1 > high) low else current + 1
                    onSet(JsonPrimitive(next))
                },
            )
        }

        else -> ArrowPreference(
            title = setting.title,
            summary = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty() + " · 这一版不能在界面上改文字",
            onClick = { },
        )
    }
}

/** `JsonPrimitive` 上那两个小读法 (界面上只用到这两种) */
private fun JsonPrimitive.booleanOrNullValue(): Boolean? = booleanOrNull

private fun JsonPrimitive.longOrNullValue(): Long? = longOrNull

/** 一行小字 (这一页只有一层: 状态、读数、段标题都用它) */
@Composable
private fun PageNotice(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = colorScheme.onSurfaceVariantActions,
        modifier = Modifier.padding(horizontal = UiSpacing.PageHorizontal),
    )
}

/** 一页只跑一次的副作用 (`LaunchedEffect(Unit)` 的短写, 免得每一处都 import) */
@Composable
private fun LaunchedEffectOnce(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}



