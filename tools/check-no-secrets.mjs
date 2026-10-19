#!/usr/bin/env node
/**
 * 凭据泄漏前的最后一道闸 (2026-10-19)
 *
 * 三样东西一起扫:
 *   1. 打出来的 APK —— 反编译能拿到的东西里不该有仓库写权限 / 发信身份 / 云账号 token
 *   2. 仓库里的文本文件 —— 别让明文凭据跟着源码进 git
 *   3. 可选的"已知凭据清单"文件 (`--secrets <路径>`, 一行一条) —— 给那些没有固定前缀的凭据
 *      (例如邮箱 SMTP 授权码) 用; 那个文件**只放在仓库外**, 别进 git
 *
 * 只报**位置与命中的模式名**, 永远不把命中的那串字符打印出来 —— 一份泄漏报告本身不该成为第二份泄漏
 *
 * 跑法:
 *   node tools/check-no-secrets.mjs                              # 默认扫 debug APK + 仓库
 *   node tools/check-no-secrets.mjs --apk app/build/.../x.apk    # 只扫指定包
 *   node tools/check-no-secrets.mjs --no-repo                    # 只扫包
 *   node tools/check-no-secrets.mjs --secrets D:\secrets.txt     # 额外比对已知凭据
 *
 * 退出码: 0 = 干净, 1 = 有命中 (构建脚本据此把这一趟判失败)
 */

import { readdir, readFile, stat } from 'node:fs/promises'
import { createReadStream } from 'node:fs'
import { join, relative, extname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))

/** 有固定前缀的那些: 长度门槛就是"占位符不算", 例如注释里写的 github_pat_… */
const PATTERNS = [
  { name: 'github fine-grained PAT', re: /github_pat_[A-Za-z0-9_]{20,}/g },
  { name: 'github classic PAT', re: /ghp_[A-Za-z0-9]{30,}/g },
  { name: 'cloudflare account token', re: /cfat_[A-Za-z0-9]{20,}/g },
  // 不放 AWS 的 AKIA 模式: 本项目一个 AWS 凭据都没有, 而 `AKIA` + 16 个大写字母在 .so 里能随机撞上
  { name: 'private key block', re: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g },
]

/** 只扫这些后缀的文本 (二进制一律跳过; APK 那一趟走 zip 解包) */
const TEXT_EXT = new Set([
  '.kt', '.kts', '.java', '.xml', '.json', '.js', '.mjs', '.cjs', '.ts', '.tsx',
  '.md', '.txt', '.yml', '.yaml', '.toml', '.ps1', '.cmd', '.bat', '.properties', '.gradle',
])

/** 不进扫描的目录 */
const SKIP_DIRS = new Set([
  '.git', 'build', 'node_modules', '.gradle', '.idea', '.lwtmp', 'logs', 'shots', 'backups', '.wrangler',
  // vendored dsh 有几十万个文件, 扫它既慢又没意义 (凭据不会写进 submodule)
  'third_party',
])

/** 单个文本文件的上限: 再大就不是我们写的源码了 */
const MAX_TEXT = 4 * 1024 * 1024

const args = process.argv.slice(2)

function arg(name, fallback = null) {
  const at = args.indexOf(`--${name}`)
  if (at < 0) return fallback
  const value = args[at + 1]
  return value && !value.startsWith('--') ? value : true
}

const apkPath = arg('apk', join(root, 'app/build/outputs/apk/debug/app-debug.apk'))
const scanRepo = arg('no-repo', false) !== true
const secretsFile = arg('secrets', null)

let failures = 0
let checks = 0

function report(ok, target, detail) {
  checks += 1
  if (!ok) failures += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${target}${detail ? `  (${detail})` : ''}`)
}

/** 命中的模式名 (不返回命中的内容) */
function hitsIn(text, extraLiterals) {
  const found = new Set()
  for (const { name, re } of PATTERNS) {
    re.lastIndex = 0
    if (re.test(text)) found.add(name)
  }
  for (const literal of extraLiterals) {
    if (text.includes(literal)) found.add('已知凭据清单命中')
  }
  return [...found]
}

/** 读 APK 的每个条目; zip 的中央目录自己解太费事, 用 zlib 手解 deflate 也就一百来行 —— 这里用 Node 自带的
 *  stream 方式不行, 所以直接读整个文件再按条目偏移解析 (debug 包 200~250 MB, 内存吃得下) */
async function readZipEntries(path) {
  const data = await readFile(path)
  const entries = []
  // 从尾部找中央目录结束记录 (EOCD, 签名 0x06054b50)
  let eocd = -1
  for (let at = data.length - 22; at >= Math.max(0, data.length - 66000); at -= 1) {
    if (data.readUInt32LE(at) === 0x06054b50) {
      eocd = at
      break
    }
  }
  if (eocd < 0) throw new Error('not a zip: EOCD not found')
  const count = data.readUInt16LE(eocd + 10)
  let at = data.readUInt32LE(eocd + 16)
  const { inflateRawSync } = await import('node:zlib')
  for (let i = 0; i < count; i += 1) {
    if (data.readUInt32LE(at) !== 0x02014b50) break
    const method = data.readUInt16LE(at + 10)
    const compressedSize = data.readUInt32LE(at + 20)
    const nameLength = data.readUInt16LE(at + 28)
    const extraLength = data.readUInt16LE(at + 30)
    const commentLength = data.readUInt16LE(at + 32)
    const localOffset = data.readUInt32LE(at + 42)
    const name = data.subarray(at + 46, at + 46 + nameLength).toString('utf8')
    entries.push({ name, method, compressedSize, localOffset })
    at += 46 + nameLength + extraLength + commentLength
  }
  // 逐个解出来
  return entries.map((entry) => {
    const local = entry.localOffset
    if (data.readUInt32LE(local) !== 0x04034b50) return { name: entry.name, bytes: Buffer.alloc(0) }
    const nameLength = data.readUInt16LE(local + 26)
    const extraLength = data.readUInt16LE(local + 28)
    const start = local + 30 + nameLength + extraLength
    const raw = data.subarray(start, start + entry.compressedSize)
    const bytes = entry.method === 0 ? raw : inflateRawSync(raw)
    return { name: entry.name, bytes }
  })
}

async function scanApk() {
  const exists = await stat(apkPath).then(() => true).catch(() => false)
  if (!exists) {
    report(false, `apk ${relative(process.cwd(), String(apkPath))}`, '文件不在, 先跑 build')
    return
  }
  const literals = await knownSecrets()
  const entries = await readZipEntries(String(apkPath))
  const found = new Map()
  for (const { name, bytes } of entries) {
    if (bytes.length === 0 || bytes.length > 400 * 1024 * 1024) continue
    // 二进制按 latin1 看: 一个字节一个字符, ASCII 模式照样能命中, 又不会因编码失败漏掉
    const text = bytes.toString('latin1')
    for (const hit of hitsIn(text, literals)) {
      if (!found.has(hit)) found.set(hit, [])
      if (found.get(hit).length < 3) found.get(hit).push(name)
    }
  }
  report(found.size === 0, 'apk 里没有凭据', found.size === 0 ? `${entries.length} 个条目` : [...found].map(([k, v]) => `${k} @ ${v.join(',')}`).join('; '))
}

async function walk(dir, out) {
  const items = await readdir(dir, { withFileTypes: true }).catch(() => [])
  for (const item of items) {
    if (item.name.startsWith('.') && item.name !== '.github') continue
    const full = join(dir, item.name)
    if (item.isDirectory()) {
      if (SKIP_DIRS.has(item.name)) continue
      await walk(full, out)
      continue
    }
    if (!item.isFile()) continue
    if (!TEXT_EXT.has(extname(item.name).toLowerCase())) continue
    out.push(full)
  }
  return out
}

async function scanRepoFiles() {
  const literals = await knownSecrets()
  const files = await walk(root, [])
  const found = []
  for (const file of files) {
    const info = await stat(file).catch(() => null)
    if (!info || info.size > MAX_TEXT) continue
    const text = await readFile(file, 'utf8').catch(() => '')
    const hits = hitsIn(text, literals)
    if (hits.length > 0) found.push(`${relative(root, file)} (${hits.join(', ')})`)
  }
  report(found.length === 0, '仓库里没有凭据', found.length === 0 ? `${files.length} 个文本文件` : found.slice(0, 5).join('; '))
}

async function knownSecrets() {
  if (!secretsFile || secretsFile === true) return []
  const text = await readFile(String(secretsFile), 'utf8').catch(() => '')
  return text
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter((line) => line.length >= 12 && !line.startsWith('#'))
}

console.log(`check-no-secrets: apk=${apkPath} repo=${scanRepo ? 'yes' : 'no'} secrets=${secretsFile ? 'yes' : 'no'}`)
await scanApk()
if (scanRepo) await scanRepoFiles()

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) {
  console.log('有命中: 把那串凭据换掉 (轮换), 再从源码/包里删掉 —— 只删不换没有意义')
  process.exit(1)
}
