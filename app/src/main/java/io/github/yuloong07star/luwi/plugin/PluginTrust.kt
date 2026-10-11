package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec

/**
 * `inline` 域那座白名单 (方案 3.3)
 *
 * `inline` 与 Luwi **同 uid**: 同 uid 的代码可以反射拿到 Luwi 的一切内部对象, 运行时那几道能力
 * 检查对它而言是**体验, 不是安全**。所以这一档只对白名单作者开放:
 *
 * - 与 Luwi 同签名的包 (主人自己的代码)
 * - 或者主人手工把作者的公钥指纹写进 `$DSH_HOME/plugins/.inline-allow.json`
 *
 * **白名单比的是"规范化的公钥身份", 不是 `publisher.key` 那串字节** (2026-10-11 主人点出来的):
 * 同一把 P-256 公钥, 作者那边产出的 SPKI 与平台重新编码出来的 SPKI 可能不是逐字节一样的东西
 * (`AlgorithmIdentifier` 里那条曲线参数写不写, 各家不一定照同一个写法来), 拿原字节去 hash 迟早
 * 造出"明明签对了却被拒"的假阴性。所以身份按下面这一条算, 四处 (白名单 / `.publishers/` / 插件页 /
 * `tools/lw-plugin-sign.mjs`) 都是同一串:
 *
 * 1. 解出 SPKI DER, 先走一遍平台自己的解析 ([KeyFactory]) —— 解不出来就是硬拒
 * 2. 取**曲线 OID** + **未压缩公钥点** (`0x04 ‖ X ‖ Y`, X / Y 各**定长 32 字节**大端左补零)
 * 3. 指纹 = `sha256:` + SHA-256(那个规范串) 的十六进制
 */
internal object PluginTrust {

    const val FILE = ".inline-allow.json"

    /** P-256 (`secp256r1`) 的曲线 OID `1.2.840.10045.3.1.7` 的 DER 编码 */
    private val P256_OID = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

    private val P256_P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val P256_N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val P256_A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val P256_B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)

    @Serializable
    data class Allow(val allowed: List<String> = emptyList())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 一把公钥的规范身份; 解不出来 / 曲线不对 → 抛一句点名的话
     *
     * 抛而不是回空串是刻意的: "这不是一把能用的公钥"与"名单里没有它"是两件事, 报错也要是两句
     */
    fun identity(key: String): String {
        val der = try {
            Base64.decode(key.removePrefix("ecdsa-p256:"), Base64.DEFAULT)
        } catch (error: Throwable) {
            throw IllegalArgumentException("publisher.key 不是一份 base64: ${error.message}")
        }
        return identityOfSpki(der)
    }

    /** SPKI DER → 规范身份 (与我们自己那把签名证书走的是同一条算法) */
    fun identityOfSpki(der: ByteArray): String {
        val public = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        } catch (error: Throwable) {
            throw IllegalArgumentException("这不是一把能用的 EC 公钥: ${error.message}")
        }
        val ec = public as? ECPublicKey ?: throw IllegalArgumentException("这不是一把 EC 公钥")
        val field = ec.params.curve.field as? ECFieldFp
            ?: throw IllegalArgumentException("这条曲线不是素域上的, 协议只收 P-256")
        val curve = ec.params.curve
        if (field.p != P256_P || ec.params.order != P256_N || curve.a != P256_A || curve.b != P256_B) {
            throw IllegalArgumentException("这条曲线不是 P-256, 协议只收 P-256")
        }
        val point = ec.w
        val canonical = P256_OID + byteArrayOf(0x04) + fixed32(point.affineX) + fixed32(point.affineY)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
        return "sha256:$digest"
    }

    /** 定长 32 字节大端左补零 —— 这里少补一步就是一次假阴性 */
    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val kept = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(kept, 0, out, 32 - kept.size, kept.size)
        return out
    }

    /** 白名单里有没有这个身份 (与 Luwi 同签名的那一份自动算在里头) */
    fun allowed(context: Context, identity: String): Boolean {
        if (identity.isBlank()) return false
        if (identity == ownIdentity(context)) return true
        return read(context).allowed.contains(identity)
    }

    /** 主人手工维护的那份名单 */
    fun read(context: Context): Allow {
        val file = File(PluginStore.root(context), FILE)
        if (!file.isFile) return Allow()
        val text = file.readText()
        runCatching { json.decodeFromString<Allow>(text) }.getOrNull()?.let { return it }
        // 也认裸的一串: ["sha256:…"] —— 手写的时候少写一层最容易少写
        return runCatching { json.decodeFromString<List<String>>(text) }.getOrNull()?.let { Allow(it) } ?: Allow()
    }

    fun write(context: Context, allow: Allow) {
        File(PluginStore.root(context), FILE).writeText(json.encodeToString(allow) + "\n")
    }

    /** Luwi 自己那把签名公钥的规范身份; 读不出来就是空串 (那种情况下白名单只剩文件那一条) */
    fun ownIdentity(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
        // `android.content.pm.Signature` 是那张证书的原始字节, 公钥要从它里面取
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(signer.toByteArray().inputStream()) as java.security.cert.X509Certificate
        identityOfSpki(certificate.publicKey.encoded)
    } catch (error: Throwable) {
        ""
    }
}
