/**
 * LW 插件的签名工具 (批次 9, 协议第 3 节)
 *
 * 一份 `.lwp` 能不能装, 全看这里签的那一份载荷。**载荷的规范化规则必须与 Kotlin 那侧逐字节相同**
 * (见 `plugin/PluginSignature.kt`): 对象的键递归按码点排序 / 分隔符只有 `,` 与 `:` / 字符串按 JSON
 * 转义 / 数字只许是整数 / 最后一段是按路径排序的逐文件哈希清单。两条实现漂开的样子是"签出来的包
 * 在设备上验不过", 而那种错在设备上只说一句"签名验不过", 看不出是哪儿差一个字符
 *
 * 跑法:
 *   node tools/lw-plugin-sign.mjs keygen --out <私钥文件> [--name <发布者名>]
 *   node tools/lw-plugin-sign.mjs sign --dir <插件目录> --key <私钥文件> [--name <发布者名>]
 *        [--out <输出目录>] [--zip <输出 .lwp>]
 *
 * keygen 把私钥写成 PKCS#8 PEM (那个文件**绝不进仓库**), 并把公钥 (SPKI-DER base64) 与指纹打在屏上。
 * sign 默认把签好的那一份写到 `--out` 指的地方 (不给就写回 `--dir`, 那会改动仓库里那份), 可选再打一个
 * 单文件 `.lwp` —— 那个 zip 是**只存不压**的, 免得为了几十兆的包引一个压缩库进来
 */

import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign as signBytes, verify as verifyBytes } from 'node:crypto'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, join, relative, resolve, sep } from 'node:path'

const args = process.argv.slice(2)
const command = args[0]

function option(name, fallback = null) {
  const at = args.indexOf(`--${name}`)
  return at < 0 ? fallback : args[at + 1]
}

if (command !== 'keygen' && command !== 'sign') {
  console.error('用法: node tools/lw-plugin-sign.mjs keygen --out <私钥文件> [--name <发布者名>]')
  console.error('      node tools/lw-plugin-sign.mjs sign --dir <插件目录> --key <私钥文件> [--out <目录>] [--zip <文件.lwp>]')
  process.exit(2)
}

/** 规范化 JSON: 与 Kotlin 那侧同一套规则, 见这类头那一段 */
function canonical(value) {
  if (value === null) return 'null'
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`
  }
  if (typeof value === 'string') return JSON.stringify(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') {
    if (!Number.isInteger(value)) throw new Error(`协议里的数字只许是整数, 这一个不是: ${value}`)
    return String(value)
  }
  throw new Error(`规范化不了的 JSON 值: ${String(value)}`)
}

/** zip 里每个条目要的 CRC32 (只存不压, 但校验和还是得对) */
const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let index = 0; index < 256; index += 1) {
    let value = index
    for (let bit = 0; bit < 8; bit += 1) value = value & 1 ? (value >>> 1) ^ 0xedb88320 : value >>> 1
    table[index] = value
  }
  return table
})()

function crc32(buffer) {
  let crc = -1
  for (const byte of buffer) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ byte) & 0xff]
  return (crc ^ -1) >>> 0
}

/** 签名载荷: 去掉 publisher.signature 的那份清单 + 逐文件哈希清单 */
function payloadOf(manifest, files) {
  const withoutSignature = { ...manifest, publisher: { ...(manifest.publisher ?? {}) } }
  delete withoutSignature.publisher.signature
  let text = canonical(withoutSignature)
  text += '\n--files--\n'
  for (const path of Object.keys(files).sort()) text += `${path} ${files[path]}\n`
  return Buffer.from(text, 'utf8')
}

/**
 * 规范化的公钥身份 (方案 3.3): 曲线 OID + 未压缩点 (`0x04 ‖ X ‖ Y`, 各定长 32 字节) 的 sha256
 *
 * **不是 SPKI 原字节的 sha256** —— 同一把 P-256 公钥, 各家编出来的 SPKI 可能不是逐字节一样的东西,
 * 拿原字节去比会造成"明明签对了却被拒"的假阴性。白名单 / `.publishers` / 插件页 / 这个工具四处
 * 认的都是这一串 (Kotlin 那份在 `plugin/PluginTrust.kt`)
 */
function identityOf(spki) {
  const key = createPublicKey({ key: spki, format: 'der', type: 'spki' })
  const jwk = key.export({ format: 'jwk' })
  if (jwk.kty !== 'EC' || jwk.crv !== 'P-256') {
    throw new Error(`协议只收 P-256, 这一把是 ${jwk.kty}/${jwk.crv}`)
  }
  const oid = Buffer.from([0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07])
  const x = Buffer.from(jwk.x, 'base64url')
  const y = Buffer.from(jwk.y, 'base64url')
  if (x.length !== 32 || y.length !== 32) throw new Error('这把 P-256 公钥的点不是定长 32 字节')
  const canonical = Buffer.concat([oid, Buffer.from([0x04]), x, y])
  return `sha256:${createHash('sha256').update(canonical).digest('hex')}`
}

/** 包里除 plugin.json 之外的每一个文件 (相对路径 + sha256 十六进制) */
async function filesOf(directory) {
  const found = {}
  async function walk(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const full = join(current, entry.name)
      if (entry.isDirectory()) {
        await walk(full)
        continue
      }
      const relativePath = relative(directory, full).split(sep).join('/')
      if (relativePath === 'plugin.json') continue
      found[relativePath] = createHash('sha256').update(await readFile(full)).digest('hex')
    }
  }
  await walk(directory)
  return found
}

if (command === 'keygen') {
  const out = option('out')
  if (out === null) throw new Error('keygen 要一个 --out')
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' })
  await writeFile(out, pem, { mode: 0o600 })
  const spki = publicKey.export({ type: 'spki', format: 'der' })
  const fingerprint = identityOf(spki)
  console.log(`私钥写在 ${resolve(out)} (别把它提交上去)`)
  console.log(`发布者公钥: ecdsa-p256:${spki.toString('base64')}`)
  console.log(`公钥指纹: ${fingerprint}`)
} else {
  const directory = option('dir') ?? option('in')
  const keyFile = option('key')
  if (directory === null || keyFile === null) throw new Error('sign 要 --dir 与 --key')
  const manifestPath = join(directory, 'plugin.json')
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  const files = await filesOf(directory)
  const privateKey = createPrivateKey(await readFile(keyFile))
  const spki = createPublicKey(privateKey).export({ type: 'spki', format: 'der' })
  const fingerprint = identityOf(spki)
  const publisherName = manifest.publisher?.name ?? option('name', 'unknown') ?? 'unknown'
  const publisherKey = `ecdsa-p256:${spki.toString('base64')}`

  // **载荷里 publisher 只有 name 与 key** (signature 那一个键要排除掉), 而这一步必须在签名之前就定下来:
  // 2026-10-08 就是这么撞的 —— 签的时候 publisher 还是个空对象, 设备那一侧读的却是带 name / key 的那一份,
  // 于是载荷差了一段, 设备上只说一句"签名验不过" (两边的规范化都对, 差的是被规范化的那份东西)
  const withKey = {
    ...manifest,
    files: Object.fromEntries(Object.entries(files).map(([path, hash]) => [path, `sha256:${hash}`])),
    publisher: { name: publisherName, key: publisherKey },
  }
  const payload = payloadOf(withKey, files)
  const signature = signBytes('sha256', payload, privateKey)
  const signed = { ...withKey, publisher: { ...withKey.publisher, signature: signature.toString('base64') } }
  const out = resolve(option('out', directory))
  await mkdir(out, { recursive: true })
  await writeFile(join(out, 'plugin.json'), `${JSON.stringify(signed, null, 2)}\n`)
  for (const path of Object.keys(files)) {
    const bytes = await readFile(join(directory, path))
    const target = join(out, path)
    await mkdir(dirname(target), { recursive: true })
    await writeFile(target, bytes)
  }
  console.log(`签好了: ${signed.id} ${signed.version} → ${out}`)
  console.log(`文件 ${Object.keys(files).length} 个 · 发布者 ${signed.publisher.name} · 指纹 ${fingerprint}`)

  // 签完自己读回来再验一遍: 读的是**写出去的那一份** (设备那一侧读的就是它), 载荷从它重算 —— 两条路
  // 只要差一个字节, 这里就会当场说出来, 而不是等到设备上那句"签名验不过"
  const reread = JSON.parse(await readFile(join(out, 'plugin.json'), 'utf8'))
  const rereadFiles = Object.fromEntries(
    Object.entries(reread.files ?? {}).map(([path, hash]) => [path, String(hash).replace(/^sha256:/, '')]),
  )
  const rereadPayload = payloadOf(reread, rereadFiles)
  if (Buffer.compare(rereadPayload, payload) !== 0) {
    throw new Error('自检没过: 从写出去那一份重算的载荷与签的时候不是同一串字节')
  }
  const publicKey = createPublicKey(privateKey)
  if (!verifyBytes('sha256', rereadPayload, publicKey, signature)) {
    throw new Error('自检没过: 写出去的那一份验不过这把签名')
  }
  console.log('自检: 从写出去的那一份重算载荷与签名时逐字节相同, 签名也验过了')

  const zipPath = option('zip')
  if (zipPath !== null) {
    const entries = [{ name: 'plugin.json', data: await readFile(join(out, 'plugin.json')) }]
    for (const path of Object.keys(files).sort()) {
      entries.push({ name: path, data: await readFile(join(out, path)) })
    }
    await writeFile(zipPath, zipOf(entries))
    console.log(`打成单文件: ${resolve(zipPath)} (${entries.length} 项)`)
  }
}

/** 一个只存不压的 zip: 局部头 + 中央目录 + 结尾记录, 足够让 `ZipFile` 解开 */
function zipOf(entries) {
  const locals = []
  const central = []
  let offset = 0
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8')
    const crc = crc32(entry.data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6)
    local.writeUInt16LE(0, 8)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(entry.data.length, 18)
    local.writeUInt32LE(entry.data.length, 22)
    local.writeUInt16LE(name.length, 26)
    locals.push(local, name, entry.data)

    const head = Buffer.alloc(46)
    head.writeUInt32LE(0x02014b50, 0)
    head.writeUInt16LE(20, 4)
    head.writeUInt16LE(20, 6)
    head.writeUInt16LE(0x0800, 8)
    head.writeUInt32LE(crc, 16)
    head.writeUInt32LE(entry.data.length, 20)
    head.writeUInt32LE(entry.data.length, 24)
    head.writeUInt16LE(name.length, 28)
    head.writeUInt32LE(offset, 42)
    central.push(head, name)
    offset += local.length + name.length + entry.data.length
  }
  const directory = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}
