package io.github.yuloong07star.luwi.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.scaffolds.SuperTextField
import io.github.yuloong07star.luwi.sync.PairPayload
import io.github.yuloong07star.luwi.sync.PairResult
import io.github.yuloong07star.luwi.sync.QrScanner
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 扫码配对那一只窗 (2026-10-20)
 *
 * 里面三样东西, 由下往上说: **一块取景面** (Camera2 + ZXing, 见 [QrScanner])、一个取景框与一句提示、
 * 以及一条兜底 —— **粘贴配对文本** (相机打不开、或者二维码在另一块屏上复制得下来的时候, 那一条路还能走)
 *
 * 几条口径:
 *   · **第一张合法的码就交出去**: 解出来的是 [PairResult.Ok] 就调 `onPayload` 并由调用方关窗, 于是
 *     "扫过"与"关上"是同一件事; 解出来不合法的话把那句中文说在窗里, 相机继续扫 (扫错了码不该收窗)
 *   · **相机一关就真的是关**: [DisposableEffect] 里 stop, 窗被系统回收 / 转屏 / 按返回都走这一条
 *   · **每次打开都是新的一次**: 这个窗没有"记住上次", `remember` 的生命周期就是这一次组合
 *   · 布局参数要显式给 MATCH_PARENT: `AndroidView` 里的 view 默认处在 WRAP_CONTENT, 于是那块取景面
 *     会缩成 0 —— 这个坑 WebView 那边踩过一次 (见 AGENTS.md), 这里同一个
 *
 * @param onDismissRequest 关窗 (返回键 / 点窗外面 / 那颗「关闭」都走它)
 * @param onPayload 解出一张合法配对码
 */
@Composable
internal fun QrScanDialog(
    onDismissRequest: () -> Unit,
    onPayload: (PairPayload) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scanner = remember { QrScanner(context) }
    // 回调随重组换引用, 而相机的回调只在开窗那一下传进去 —— 所以走 rememberUpdatedState 取最新的那个
    val currentPayload by rememberUpdatedState(onPayload)

    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    val torchRefused = stringResource(R.string.settings_sync_pair_torch_none)

    /** 一段文本 → 配对码; 合法就交出去, 不合法把原因留在窗里 */
    fun accept(text: String) {
        when (val parsed = PairPayload.parse(text)) {
            is PairResult.Ok -> {
                error = null
                haptic.confirm()
                currentPayload(parsed.payload)
            }

            is PairResult.Rejected -> error = parsed.error.message
        }
    }

    // 窗一收就把相机还回去 (按返回 / 点外面 / 解出码之后调用方关窗, 三条都走到这里)
    DisposableEffect(Unit) {
        onDispose { scanner.stop() }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sync_pair),
                    style = MiuixTheme.textStyles.title3,
                    color = Color.White,
                )
                // 取景面: 中间那块方框就是"把二维码放进这里"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraPreview(
                        scanner = scanner,
                        onText = { accept(it) },
                        onError = { error = it },
                    )
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(24.dp)),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_sync_pair_hint),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                error?.let { said ->
                    Text(
                        text = said,
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // 手电: 暗处那一档 —— 没有闪光灯 / 相机还没起来时 setTorch 回 false, 这里把那句话说到窗里
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_sync_pair_torch),
                        summary = stringResource(R.string.settings_sync_pair_torch_summary),
                        checked = torch,
                        onCheckedChange = { wanted ->
                            if (scanner.setTorch(wanted)) {
                                torch = wanted
                                error = null
                            } else {
                                torch = false
                                error = torchRefused
                            }
                        },
                    )
                }
                // 兜底: 整段配对文本粘进来 (相机打不开、或者二维码在别的屏幕上)
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SuperTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            useLabelAsPlaceholder = true,
                            label = stringResource(R.string.settings_sync_pair_paste),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.settings_sync_pair_paste_note),
                            color = colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(
                            text = stringResource(R.string.settings_sync_pair_paste_action),
                            onClick = {
                                haptic.contextClick()
                                accept(pasted)
                            },
                            enabled = pasted.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.settings_sync_close),
                        onClick = {
                            haptic.contextClick()
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 那块取景面
 *
 * `SurfaceView` 而不是 `TextureView`: 相机直接往 surface 上出帧, 没有那一层纹理拷贝, 而这一页要的
 * 只是"看得见码在哪里" —— 顺带它也是项目里别处 (虚拟屏预览) 已经用过的那一条路
 *
 * 相机开在 `surfaceCreated` 里、收在 `surfaceDestroyed` 里: 这两个回调由窗口自己看管, 于是"窗还没
 * 画出来就先开相机"与"窗已经没了相机还开着"两种状态都不会出现
 */
@Composable
private fun CameraPreview(
    scanner: QrScanner,
    onText: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val currentText by rememberUpdatedState(onText)
    val currentError by rememberUpdatedState(onError)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                // **必须显式给**: AndroidView 里的 view 默认是 WRAP_CONTENT, 相机会拿到一块 0 尺寸的
                // 取景面 (同一条坑见 AGENTS.md 里 WebView 那个 viewport 全变 0 的记录)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            scanner.start(
                                holder.surface,
                                onText = { text -> currentText(text) },
                                onError = { message -> currentError(message) },
                            )
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            scanner.stop()
                        }
                    },
                )
            }
        },
        update = { },
    )
}
