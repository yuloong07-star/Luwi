package io.github.yuloong07star.luwi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.feedback.Feedback
import io.github.yuloong07star.luwi.feedback.FeedbackChannel
import io.github.yuloong07star.luwi.feedback.FeedbackIssue
import io.github.yuloong07star.luwi.feedback.FeedbackMail
import io.github.yuloong07star.luwi.feedback.FeedbackSubmit
import io.github.yuloong07star.luwi.scaffolds.SuperTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 「反馈」: 500 字 + 方式 + 一张附件 + 联系方式 (2.7.9 第 10 条)
 *
 * **这一版通常一个字都不发出去**: 后端地址 ([FeedbackSubmit.KEY_ENDPOINT]) 缺省是空的, 于是提交那一步
 * 走的是本地兜底 —— 正文连同几项设备读数**复制进剪贴板**, 再打开仓库的 New Issue 页 (私密那一档打开
 * 邮件客户端), 界面上明说「已复制」, 不假装发送成功。后端接上之后**只改那个偏好**就换成真 POST,
 * 这一页一个字都不用动
 *
 * 三条口径 (施工单 §2.8):
 *
 * - **应用里不出现任何 GitHub token 与 SMTP 授权码** —— 收件人那两处都是 README 里公开的信息
 * - **有附件就一定走兜底**: 这一版不传字节, 走 POST 会让那条 issue 少一半信息却没有一句说明
 * - **空正文不提交**, 交出去也只是一条没有内容的 issue
 *
 * 窗口是 Miuix 的 [OverlayDialog], 与「关于」那几页同一个排法 (由设置页拿一个 `AboutPage?` 决定
 * 画哪一个, 同一刻只开一个)
 */
@Composable
internal fun FeedbackDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // 表单那几项: 正文 / 方式 / 联系方式转屏要兜住; 附件只记名字与大小 (这一版不读字节, 于是也
    // 不接持久 URI 权限 —— 那份授权一直留着却没人用, 是一件看不见的债)
    var body by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var contact by rememberSaveable { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Feedback.Attachment?>(null) }

    // 这台机器上有没有"真投递"那两只凭据 (`$DSH_HOME/lw/feedback-channel.json`): 没有就一律走兜底,
    // 别人装这一版时走的就是这一档
    val channel = remember { FeedbackChannel.load(context) }

    // 交完之后那几样: 正在提交 / 真发出去了 / 进了剪贴板 / 为什么走兜底 / 后端与打开页面各自的抱怨
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var backend by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            attachment = withContext(Dispatchers.IO) { readAttachment(context, uri) }
        }
    }

    val kindLabels = listOf(
        stringResource(R.string.settings_feedback_kind_issue),
        stringResource(R.string.settings_feedback_kind_email),
    )
    val kindSummaries = listOf(
        stringResource(R.string.settings_feedback_kind_issue_summary),
        stringResource(R.string.settings_feedback_kind_email_summary),
    )
    // 兜底那一段文字里的几行标签: 先取好, 拼文字的那几个 lambda 不是组合上下文。
    // 大小读不到时改用只有名字的那一条 —— "附件: x ()" 那种半截括号不该出现在 issue 里
    val metaHeading = stringResource(R.string.settings_feedback_meta)
    val metaKind = stringResource(R.string.settings_feedback_meta_kind)
    val metaContact = stringResource(R.string.settings_feedback_meta_contact)
    val attachmentSize = attachment?.let { Feedback.size(it.bytes) }.orEmpty()
    val attachmentLabel = attachment?.let {
        if (attachmentSize.isEmpty()) {
            stringResource(R.string.settings_feedback_attach_chosen_named, it.name)
        } else {
            stringResource(R.string.settings_feedback_attach_chosen, it.name, attachmentSize)
        }
    }
    val attachmentLine = attachment?.let {
        if (attachmentSize.isEmpty()) {
            stringResource(R.string.settings_feedback_meta_attachment_named, it.name)
        } else {
            stringResource(R.string.settings_feedback_meta_attachment, it.name, attachmentSize)
        }
    }
    val noBackend = stringResource(R.string.settings_feedback_no_backend)
    val sendFailed = stringResource(R.string.settings_feedback_send_failed)
    val attachNotSent = stringResource(R.string.settings_feedback_attach_note)
    val mailSubject = stringResource(R.string.settings_feedback_mail_subject)
    val emptyText = stringResource(R.string.settings_feedback_empty)

    /** 交给 issue / 邮件 / 剪贴板的那一段文字 (三条路共用同一份, 于是它们说的是同一件事) */
    fun draftText(): String = Feedback.compose(
        body = body,
        extra = listOfNotNull(
            metaHeading,
            "$metaKind: ${kindLabels[kind]}",
            contact.takeIf { it.isNotBlank() }?.let { "$metaContact: $it" },
            attachmentLine,
            FeedbackSubmit.appLine(context),
            FeedbackSubmit.deviceLine(context),
        ),
    )

    /** 兜底那一下: 复制 + 打开那一个去处 (私密走邮件客户端, 公开走 New Issue 页) */
    fun copyAndOpen(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(metaKind, text))
        copied = clipboard != null
        val target = if (kind == 0) Feedback.ISSUE_PAGE else Feedback.mailUrl(mailSubject)
        // 打不开 (没有浏览器 / 没有邮件客户端) 就把那一句念在窗里: 文字已经在剪贴板里, 一个字都不丢
        refused = openPage(context, target)
    }

    fun submit() {
        if (busy) return
        if (!Feedback.ready(body)) {
            refused = emptyText
            return
        }
        haptic.confirm()
        backend = null
        refused = null
        note = null
        val chosen = if (kind == 0) Feedback.Kind.ISSUE else Feedback.Kind.EMAIL
        val endpoint = FeedbackSubmit.endpoint(context)
        when (Feedback.route(chosen, endpoint, attachment, channel.mailReady, channel.issueReady)) {
            // 设备上有那两只凭据时走真投递 (主人本机那一档); 没配的设备永远走不到这两支
            Feedback.Route.MAIL -> {
                busy = true
                scope.launch {
                    val text = draftText()
                    val failure = withContext(Dispatchers.IO) {
                        FeedbackMail.send(channel, mailSubject, text)
                    }
                    busy = false
                    if (failure == null) {
                        sent = true
                    } else {
                        // 没发成也要把正文留住: 换成复制, 并把服务器那句原文念出来
                        note = sendFailed
                        backend = failure
                        copyAndOpen(text)
                    }
                }
            }

            Feedback.Route.ISSUE -> {
                busy = true
                scope.launch {
                    val text = draftText()
                    val title = FeedbackIssue.titleOf(body, FeedbackSubmit.appLine(context))
                    val failure = withContext(Dispatchers.IO) {
                        FeedbackIssue.create(channel.githubToken, title, text)
                    }
                    busy = false
                    if (failure == null) {
                        sent = true
                    } else {
                        note = sendFailed
                        backend = failure
                        copyAndOpen(text)
                    }
                }
            }

            Feedback.Route.POST -> {
                busy = true
                scope.launch {
                    val payload = FeedbackSubmit.payload(
                        kind = chosen,
                        body = body,
                        contact = contact,
                        app = FeedbackSubmit.appLine(context),
                        device = FeedbackSubmit.deviceLine(context),
                    )
                    val failure = withContext(Dispatchers.IO) { FeedbackSubmit.post(endpoint, payload) }
                    busy = false
                    if (failure == null) {
                        sent = true
                    } else {
                        // 后端没接上: 说清为什么, 然后照旧走兜底 (正文不丢)
                        note = noBackend
                        backend = failure
                        copyAndOpen(draftText())
                    }
                }
            }

            Feedback.Route.COPY_NO_BACKEND -> {
                note = noBackend
                copied = false
                copyAndOpen(draftText())
            }

            Feedback.Route.COPY_WITH_ATTACHMENT -> {
                note = attachNotSent
                copied = false
                copyAndOpen(draftText())
            }
        }
    }

    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_feedback_title),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TabRow(
                tabs = kindLabels,
                selectedTabIndex = kind,
                onTabSelected = { kind = it },
            )
            Text(
                text = kindSummaries[kind],
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            SuperTextField(
                value = body,
                // 上限在这里拦: 框里永远不超过 500, 于是计数器与"交得出去"是同一件事
                onValueChange = { body = Feedback.clamp(it) },
                modifier = Modifier.padding(top = 16.dp),
                label = stringResource(R.string.settings_feedback_body),
                minLines = 5,
                maxLines = 8,
            )
            // 实时计数: 到顶那一格换成错误色 (截断是静默的, 这一行是唯一的提示)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.settings_feedback_count, body.length, Feedback.LIMIT),
                    color = if (body.length >= Feedback.LIMIT) {
                        colorScheme.error
                    } else {
                        colorScheme.onSurfaceVariantSummary
                    },
                )
            }
            SuperTextField(
                value = contact,
                onValueChange = { contact = it },
                modifier = Modifier.padding(top = 12.dp),
                label = stringResource(R.string.settings_feedback_contact),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val current = attachment
                TextButton(
                    text = if (current == null) {
                        stringResource(R.string.settings_feedback_attach_add)
                    } else {
                        attachmentLabel.orEmpty()
                    },
                    onClick = {
                        haptic.contextClick()
                        picker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f),
                )
                if (current != null) {
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        text = stringResource(R.string.settings_feedback_attach_remove),
                        onClick = {
                            haptic.contextClick()
                            attachment = null
                        },
                    )
                }
            }
            // 那句"附件不跟着走"只在真有附件时念: 没附件的人不该读一条与己无关的限制
            if (attachment != null) {
                Text(
                    text = attachNotSent,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            // 交完之后那几行: 真发出去了 / 已复制 / 为什么走兜底 / 后端与打开页面各自说了什么
            if (sent) {
                Text(
                    text = stringResource(R.string.settings_feedback_sent),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.settings_feedback_copied),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            note?.let { line ->
                Text(
                    text = line,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            backend?.let { line ->
                Text(
                    text = line,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            refused?.let { line ->
                Text(
                    text = line,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // 底下那一排: 还没交是"取消 / 提交", 交了之后只剩"关闭" —— 再点一次提交只会多开一条
        if (sent || copied) {
            TextButton(
                text = stringResource(R.string.settings_close),
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
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
                    text = if (busy) {
                        stringResource(R.string.settings_feedback_sending)
                    } else {
                        stringResource(R.string.settings_feedback_submit)
                    },
                    onClick = { submit() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * 挑回来的那个文件叫什么、多大
 *
 * 只查这两列: 名字进 issue 正文, 大小让主人知道"要传的是 12 MiB 还是 200 KiB"。查不到时退回 URI
 * 最后那一段, 大小留 0 (那一格就不写了) —— 这件事不该因为某个 provider 不答 DISPLAY_NAME 就失败
 */
private fun readAttachment(context: Context, uri: Uri): Feedback.Attachment {
    var name = uri.lastPathSegment.orEmpty()
    var bytes = 0L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) bytes = cursor.getLong(sizeIndex)
            }
        }
    }
    return Feedback.Attachment(name.ifBlank { "attachment" }, bytes)
}
