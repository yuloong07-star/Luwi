package io.github.yuloong07star.luwi.plugin

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 发布者签名 (协议第 3 节)
 *
 * 载荷 = **规范化之后的 `plugin.json`** (去掉 `publisher.signature` 那一个键) + 一份逐文件哈希清单。
 * 两侧的算法各有各的实现, 而它们必须印出**一模一样**的字节, 所以规范化的规则写死成四条:
 *
 * 1. 对象的键递归按码点排序
 * 2. 分隔符只有 `,` 与 `:`, 一个空格都不留
 * 3. 字符串按 JSON 转义, 数字只许是整数 (协议已在 `plugin.json` 那一层拦住浮点)
 * 4. 载荷的最后一段是 `\n--files--\n` 加上按路径排序的 `<路径> <hex>\n`
 *
 * 算法是 ECDSA P-256 / SHA-256: 公钥是 SPKI-DER 的 base64, 签名是 DER 的 base64 —— 于是 Node 的
 * `crypto.sign('sha256', …)` 与这里的 `SHA256withECDSA` 原生互通, 不需要任何第三方库
 */
internal object PluginSignature {

    /** 签的那一份载荷 (签名工具与这里各实现一遍, 必须逐字节相同) */
    fun payload(manifest: JsonObject, files: Map<String, String>): ByteArray {
        val withoutSignature = stripSignature(manifest)
        val builder = StringBuilder(canonical(withoutSignature))
        builder.append("\n--files--\n")
        files.keys.sorted().forEach { path ->
            builder.append(path).append(' ').append(files.getValue(path).removePrefix("sha256:")).append('\n')
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    /** 验签: 不过就是 false, 由调用方说清是哪一条不过 */
    fun verify(manifest: JsonObject, files: Map<String, String>, key: String, signature: String): Boolean = try {
        val public = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(key.removePrefix("ecdsa-p256:"), Base64.DEFAULT)),
        )
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(public)
            update(payload(manifest, files))
            verify(Base64.decode(signature, Base64.DEFAULT))
        }
    } catch (error: Throwable) {
        false
    }

    /**
     * 公钥指纹: **规范化的公钥身份** (方案 3.3 定死的口径)
     *
     * 不是 SPKI 原字节的 sha256 —— 同一把 P-256 公钥, 各家编出来的 SPKI 可能不是逐字节一样的东西,
     * 拿原字节去比会造成"明明签对了却被拒"的假阴性。白名单 / `.publishers` / 插件页 / 签名工具
     * 四处认的都是 [PluginTrust.identity] 那一串
     */
    fun fingerprint(key: String): String = try {
        PluginTrust.identity(key)
    } catch (error: Throwable) {
        ""
    }

    /** 一个文件的哈希, 与 `files` 里那个前缀同一个形状 */
    fun digestOf(file: java.io.File): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun stripSignature(manifest: JsonObject): JsonObject {
        val publisher = manifest["publisher"] as? JsonObject ?: return manifest
        val kept = publisher.filterKeys { it != "signature" }
        return JsonObject(manifest.toMutableMap().apply { put("publisher", JsonObject(kept)) })
    }

    /** 规范化 JSON: 键排序 + 最紧凑的分隔符, 见这类头那四条 */
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject ->
            element.entries.sortedBy { it.key }
                .joinToString(",", "{", "}") { (key, value) ->
                    JsonPrimitive(key).toString() + ":" + canonical(value)
                }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonNull -> "null"
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(element.content).toString() else element.content
    }
}
