package io.github.yuloong07star.luwi.feedback

import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocketFactory

/**
 * 私密那一档的投递: 直接说 SMTP (QQ 邮箱 465 隐式 TLS)
 *
 * **它只用主人自己那台机器上的凭据** ([FeedbackChannel], `$DSH_HOME/lw/feedback-channel.json`),
 * 包里一个字都没有; 文件不在时这一条根本不选 (见 [Feedback.route])
 *
 * 为什么手写而不是引 JavaMail: 安卓上没有 `javax.mail`, 而这里要的东西只有五条命令
 * (EHLO / AUTH LOGIN / MAIL FROM / RCPT TO / DATA) —— 一份依赖换不回来
 *
 * **报错要留原文**: 登录失败时服务器答的是 `535 Login fail …`, 那句话要原样回到界面上 ——
 * "发送失败"四个字查不出是授权码过期还是网络不通
 */
internal object FeedbackMail {

    /** 等服务器说一句话的上限: 发一封信不该比一次下载等得久 */
    private const val TIMEOUT_MS = 20_000

    /**
     * 这一封信的正文 (纯函数, 单测钉): 头 + base64 的正文
     *
     * 正文一律 base64: 中文与换行都不必操心, 也不会出现"以 . 开头的一行"被当成 DATA 结束
     */
    fun message(from: String, to: String, subject: String, body: String, at: Long): String {
        val when_ = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(Date(at))
        val encoded = Base64.getMimeEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        return buildString {
            append("From: ").append(from).append("\r\n")
            append("To: ").append(to).append("\r\n")
            append("Subject: =?UTF-8?B?")
                .append(Base64.getEncoder().encodeToString(subject.toByteArray(Charsets.UTF_8)))
                .append("?=\r\n")
            append("Date: ").append(when_).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encoded).append("\r\n")
        }
    }

    /** 发出去: 成功回 `null`, 失败回一句人话 (服务器原文) */
    fun send(channel: FeedbackChannel, subject: String, body: String): String? {
        var socket: Socket? = null
        return try {
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(channel.smtpHost, channel.smtpPort)
            socket.soTimeout = TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val output = socket.getOutputStream()

            fun write(line: String) {
                output.write((line + "\r\n").toByteArray(Charsets.US_ASCII))
                output.flush()
            }

            /** 读一句应答; 多行应答 (`250-…`) 只把首行交给调用方 */
            fun reply(): String {
                var line = input.readLine() ?: error("smtp: 服务器把连接关掉了")
                while (line.length >= 4 && line[3] == '-') {
                    line = input.readLine() ?: break
                }
                return line
            }

            fun expect(prefix: String, what: String) {
                val answer = reply()
                if (!answer.startsWith(prefix)) error("$what: $answer")
            }

            expect("220", "打招呼")
            write("EHLO luwi")
            expect("250", "EHLO")
            write("AUTH LOGIN")
            expect("334", "AUTH LOGIN")
            write(Base64.getEncoder().encodeToString(channel.smtpUser.toByteArray(Charsets.UTF_8)))
            expect("334", "AUTH 用户名")
            write(Base64.getEncoder().encodeToString(channel.smtpCode.toByteArray(Charsets.UTF_8)))
            expect("235", "登录")
            write("MAIL FROM:<${channel.smtpUser}>")
            expect("25", "MAIL FROM")
            write("RCPT TO:<${channel.mailTo}>")
            expect("25", "RCPT TO")
            write("DATA")
            expect("354", "DATA")
            val letter = message(
                from = channel.smtpUser,
                to = channel.mailTo,
                subject = subject,
                body = body,
                at = System.currentTimeMillis(),
            )
            output.write((letter + "\r\n.\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            expect("250", "投递")
            write("QUIT")
            null
        } catch (error: Throwable) {
            error.message ?: error.toString()
        } finally {
            runCatching { socket?.close() }
        }
    }
}
