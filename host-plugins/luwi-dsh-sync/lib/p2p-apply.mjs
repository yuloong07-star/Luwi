// lib/p2p-apply.mjs — P2P 的落盘语义：路径分类与作者规则、单文件原子写、集合整组原子落、
// 墓碑删除与 trash（方案 §6.1~§6.3）。
//
// 三条硬规则（全部是"宁可少写，不写半份"）：
//   1. 作者不匹配的路径既不发也不收（收端记 skipped + warning，不写盘）；
//   2. 集合（`conv/<设备>/<会话>/`）整组校验通过才 rename 进镜像，中途失败留 tmp；
//   3. 删除只认墓碑，只删 P2P 根，只动镜像 —— 删下来的东西进 `.p2p/trash/<日期>/` 保 7 天，
//      并记进 `.p2p/deleted.json`，让下一趟 git 推送把"我删的"从删除清单里减掉。
//
// 这一层只碰文件系统与路径，不解析帧、不连网（帧在 lib/p2p-frame.mjs）。

import { createHash, randomUUID } from 'node:crypto'
import { promises as fs } from 'node:fs'
import { dirname, join, relative } from 'node:path'

import { ERROR_CODES } from './constants.mjs'
import { SyncError } from './errors.mjs'

/** P2P 的根（`sessions/` 是明确不进 P2P 的那一个）。 */
export const P2P_ROOTS = Object.freeze({
  INBOX: 'inbox',
  INBOX_ACK: 'inbox-ack',
  LIVE: 'live',
  CONV: 'conv',
  TOMBSTONES: 'tombstones',
  PEERS: 'peers',
  WORKSPACES: 'workspaces',
  SESSIONS: 'sessions',
})

/** 所有根名的数组（校验用）。 */
export const P2P_ROOT_NAMES = Object.freeze(Object.values(P2P_ROOTS))

/** 只由"作者自己"写的根。 */
const OWNED_ROOTS = new Set([P2P_ROOTS.LIVE, P2P_ROOTS.CONV, P2P_ROOTS.TOMBSTONES, P2P_ROOTS.PEERS, P2P_ROOTS.WORKSPACES])
/** 邮箱类根：收件人/发件人是路径里的 owner，写的人是对端。 */
const MAILBOX_ROOTS = new Set([P2P_ROOTS.INBOX, P2P_ROOTS.INBOX_ACK])

/** 单轮墓碑上限（§6.3：每轮最多 200 个路径 / 32 MiB）。 */
export const TOMBSTONE_LIMITS = Object.freeze({ maxPaths: 200, maxBytes: 32 * 1024 * 1024 })

/**
 * 不安全的相对路径（绝对路径 / `..` / 空段 / 反斜杠 / NUL）一律拒绝。
 * @param {unknown} path - 帧里给的路径（POSIX 形态）。
 * @returns {string|null} 规范化后的路径，或 null。
 */
export function normalizeP2pPath(path) {
  if (typeof path !== 'string' || path === '' || path.includes('\0')) return null
  const value = path.replace(/\\/gu, '/')
  if (value.startsWith('/') || /^[A-Za-z]:/u.test(value)) return null
  const segments = value.split('/')
  if (segments.some(segment => segment === '' || segment === '.' || segment === '..')) return null
  return segments.join('/')
}

/**
 * 一个路径属于哪个根、归哪台设备。
 *
 * - `inbox/<收件人>/<msgId>.json` / `inbox-ack/<发件人>/<msgId>.json`：owner 是收件人 / 发件人；
 * - `live|conv|tombstones|peers|workspaces/<设备>/…`：owner 是那台设备；
 * - `sessions/…`：p2p=false（归档面留在 git）；
 * - 其它：null。
 * @param {unknown} path - 相对路径。
 * @returns {{path: string, root: string, owner: string, session?: string, p2p: boolean}|null} 分类。
 */
export function classifyPath(path) {
  // 墓碑与 NEED 会把集合写成 `conv/<设备>/<会话>/`（尾部斜杠），先剁掉再分类
  const trimmed = typeof path === 'string' ? path.replace(/\/+$/u, '') : path
  const normalized = normalizeP2pPath(trimmed)
  if (normalized === null) return null
  const parts = normalized.split('/')
  const root = parts[0]
  if (!P2P_ROOT_NAMES.includes(root)) return null
  if (root === P2P_ROOTS.SESSIONS) return { path: normalized, root, owner: '', p2p: false }
  if (parts.length < 2) return null
  let owner = parts[1]
  if (root === P2P_ROOTS.PEERS || root === P2P_ROOTS.TOMBSTONES) owner = owner.replace(/\.json$/u, '')
  const info = { path: normalized, root, owner, p2p: true }
  if (root === P2P_ROOTS.CONV) {
    if (parts.length < 3) return null
    info.session = parts[2]
  }
  return info
}

/**
 * 本机能不能把这条路径**发出去**（作者规则）。
 * @param {string} path - 相对路径。
 * @param {string} deviceId - 本机设备 id。
 * @returns {boolean} 能不能发。
 */
export function maySend(path, deviceId) {
  const info = classifyPath(path)
  if (info === null || info.p2p !== true || typeof deviceId !== 'string' || deviceId === '') return false
  if (MAILBOX_ROOTS.has(info.root)) return true // 发件人写别人的信箱，owner 是收件人
  return OWNED_ROOTS.has(info.root) && info.owner === deviceId
}

/**
 * 对端这条路径能不能写进本机镜像（作者规则）。
 *
 * 邮箱是唯一例外：`inbox/<我>/…` 与 `inbox-ack/<我>/…` 谁写都收（写的人是对端，可信与否在
 * 信任表那一层判），其余根只收"对端自己名下"的路径。
 * @param {string} path - 相对路径。
 * @param {{myDeviceId: string, peerDeviceId: string}} input - 两端 id。
 * @returns {boolean} 能不能收。
 */
export function mayReceive(path, { myDeviceId, peerDeviceId }) {
  const info = classifyPath(path)
  if (info === null || info.p2p !== true) return false
  if (typeof peerDeviceId !== 'string' || peerDeviceId === '') return false
  if (MAILBOX_ROOTS.has(info.root)) return info.owner === myDeviceId
  return OWNED_ROOTS.has(info.root) && info.owner === peerDeviceId
}

/**
 * 是不是集合路径（`NEED` 里以 `/` 结尾，或 `conv/<设备>/<会话>/meta.json` 的父目录）。
 * @param {string} path - 相对路径。
 * @returns {boolean} 是否集合。
 */
export function isCollectionPath(path) {
  if (typeof path !== 'string') return false
  const trimmed = path.replace(/\/+$/u, '')
  if (trimmed === '') return false
  const info = classifyPath(trimmed)
  return info !== null && info.root === P2P_ROOTS.CONV
}

/** sha256 十六进制。 */
export function sha256Of(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

/**
 * 原子写一个文件（先写 `.p2p-tmp-*` 再 rename）。
 * @param {string} target - 绝对路径。
 * @param {Buffer|string} body - 内容。
 * @returns {Promise<void>} 完成。
 */
async function writeFileAtomic(target, body) {
  await fs.mkdir(dirname(target), { recursive: true })
  const temp = `${target}.p2p-tmp-${randomUUID().slice(0, 8)}`
  await fs.writeFile(temp, body)
  try {
    await fs.rename(temp, target)
  } catch (error) {
    await fs.rm(temp, { force: true })
    throw error
  }
}

/**
 * 落一个单文件集合（邮箱 / 名片 / live / tombstones）：校验 sha 后原子写；内容相同就跳过。
 *
 * @param {object} input - {repoDir, path, body, sha256, alreadyKnown}。
 * @param {string} input.repoDir - 镜像根。
 * @param {string} input.path - 相对路径（调用方已过 mayReceive）。
 * @param {Buffer|string} input.body - 内容。
 * @param {string} [input.sha256] - 期望的 sha256（给了就校验）。
 * @param {(path: string) => boolean} [input.alreadyKnown] - 可选的"已见过"短路（幂等）。
 * @returns {Promise<{ok: boolean, path: string, skipped: boolean, bytes: number}>} 结果。
 */
export async function applyFile({ repoDir, path, body, sha256, alreadyKnown = undefined }) {
  const info = classifyPath(path)
  if (info === null || info.p2p !== true) throw pathUnsafe(`refusing to write ${JSON.stringify(path)}`)
  const data = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8')
  const digest = sha256Of(data)
  if (typeof sha256 === 'string' && sha256 !== '' && sha256 !== digest) {
    throw new SyncError(ERROR_CODES.P2P_INTEGRITY, `p2p integrity: ${info.path} sha256 ${digest} != expected ${sha256}`, { path: info.path })
  }
  if (alreadyKnown?.(info.path) === true) return { ok: true, path: info.path, skipped: true, bytes: data.length }
  const target = join(repoDir, ...info.path.split('/'))
  try {
    const existing = await fs.readFile(target)
    if (existing.equals(data)) return { ok: true, path: info.path, skipped: true, bytes: data.length }
  } catch {
    // 不存在或读不动都按"要写"处理
  }
  await writeFileAtomic(target, data)
  return { ok: true, path: info.path, skipped: false, bytes: data.length }
}

/**
 * 路径越界（写入前校验失败）。
 * @param {string} reason - 说明。
 * @returns {SyncError} code=PATH_UNSAFE。
 */
export function pathUnsafe(reason) {
  return new SyncError(ERROR_CODES.PATH_UNSAFE, `unsafe sync path refused: ${reason}`, { reason })
}

/**
 * 集合整组落：先校验，再把 tmp 目录整组 rename 进镜像；旧的那一份先进 trash。
 *
 * 校验不过 = 整组丢弃（tmp 留着给跨轮续传，调用方决定清不清），镜像里一个字节都不动。
 * @param {object} input - {repoDir, setPath, tmpDir, verify, trashRoot, now}。
 * @param {string} input.repoDir - 镜像根。
 * @param {string} input.setPath - 集合相对路径（`conv/<设备>/<会话>`）。
 * @param {string} input.tmpDir - 已经收齐的临时目录。
 * @param {(dir: string) => Promise<{ok: boolean, errors?: string[]}>} input.verify - 整组校验器。
 * @param {string} [input.trashRoot] - trash 相对根，缺省 `.p2p/trash`。
 * @param {number} [input.now] - 时钟（测试注入）。
 * @returns {Promise<{ok: boolean, path: string, errors: string[]}>} 结果。
 */
export async function finalizeSet({ repoDir, setPath, tmpDir, verify, trashRoot = '.p2p/trash', now = Date.now() }) {
  const info = classifyPath(setPath)
  if (info === null || info.root !== P2P_ROOTS.CONV) throw pathUnsafe(`refusing to finalise ${JSON.stringify(setPath)}`)
  const check = await verify(tmpDir)
  if (check.ok !== true) {
    return { ok: false, path: info.path, errors: Array.isArray(check.errors) ? check.errors : ['set verification failed'] }
  }
  const target = join(repoDir, ...info.path.split('/'))
  try {
    const targetStats = await fs.stat(target)
    if (targetStats.isDirectory()) {
      const stamp = new Date(now).toISOString().slice(0, 10)
      let trash = join(repoDir, ...trashRoot.split('/'), stamp, ...info.path.split('/'))
      // 同名下再撞就加时间戳后缀（trash 不允许覆盖）
      try {
        await fs.stat(trash)
        trash = `${trash}-${now}`
      } catch {
        // 不存在，正好
      }
      await fs.mkdir(dirname(trash), { recursive: true })
      await fs.rename(target, trash)
    }
  } catch {
    // 目标不存在 = 第一次落这一组
  }
  await fs.mkdir(dirname(target), { recursive: true })
  try {
    await fs.rename(tmpDir, target)
  } catch (error) {
    throw new SyncError(ERROR_CODES.P2P_IO, `p2p io: could not move ${info.path} into place: ${error instanceof Error ? error.message : String(error)}`, { path: info.path })
  }
  return { ok: true, path: info.path, errors: [] }
}

/**
 * 解析墓碑文件（容忍 JSON 数组与 JSONL 两种形态；坏行计数，不抛）。
 * @param {string} text - `tombstones/<设备>.json` 的正文。
 * @returns {{entries: Array<{path: string, at: number}>, badLines: number}} 结果。
 */
export function parseTombstones(text) {
  if (typeof text !== 'string' || text.trim() === '') return { entries: [], badLines: 0 }
  const entries = []
  let badLines = 0
  const push = (value) => {
    if (value === null || typeof value !== 'object') {
      badLines += 1
      return
    }
    const info = classifyPath(value.path)
    if (info === null || info.p2p !== true) {
      badLines += 1
      return
    }
    entries.push({ path: info.path, at: Number.isFinite(value.at) ? Number(value.at) : 0 })
  }
  const trimmed = text.trim()
  if (trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed)
      if (Array.isArray(parsed)) for (const value of parsed) push(value)
      else badLines += 1
      return { entries, badLines }
    } catch {
      badLines += 1
    }
  }
  for (const line of text.split('\n')) {
    if (line.trim() === '') continue
    try {
      push(JSON.parse(line))
    } catch {
      badLines += 1
    }
  }
  return { entries, badLines }
}

/**
 * 把一批墓碑落到镜像：只删 P2P 根、只删"对端名下"的路径、邮箱永不删；删下来的进 trash。
 *
 * @param {object} input - {repoDir, peerDeviceId, entries, limits, trashRoot, now}。
 * @param {string} input.repoDir - 镜像根。
 * @param {string} input.peerDeviceId - 墓碑的作者。
 * @param {Array<{path: string, at: number}>} input.entries - parseTombstones 的产物。
 * @param {{maxPaths?: number, maxBytes?: number}} [input.limits] - 单轮上限。
 * @param {string} [input.trashRoot] - trash 相对根。
 * @param {number} [input.now] - 时钟。
 * @returns {Promise<{deleted: string[], skipped: string[], bytes: number, warnings: string[]}>} 结果。
 */
export async function applyTombstones({ repoDir, peerDeviceId, entries, limits = TOMBSTONE_LIMITS, trashRoot = '.p2p/trash', now = Date.now() }) {
  const maxPaths = limits.maxPaths ?? TOMBSTONE_LIMITS.maxPaths
  const maxBytes = limits.maxBytes ?? TOMBSTONE_LIMITS.maxBytes
  const deleted = []
  const skipped = []
  const warnings = []
  let bytes = 0
  const stamp = new Date(now).toISOString().slice(0, 10)
  for (const entry of entries) {
    if (deleted.length >= maxPaths) {
      warnings.push(`tombstone budget reached (${maxPaths} paths)`)
      break
    }
    const info = classifyPath(entry.path)
    if (info === null || info.p2p !== true) {
      skipped.push(String(entry.path))
      warnings.push(`tombstone path is not a P2P path: ${JSON.stringify(entry.path)}`)
      continue
    }
    if (MAILBOX_ROOTS.has(info.root)) {
      skipped.push(info.path)
      warnings.push(`tombstones never delete the mailbox: ${info.path}`)
      continue
    }
    if (OWNED_ROOTS.has(info.root) === false || info.owner !== peerDeviceId) {
      skipped.push(info.path)
      warnings.push(`tombstone author mismatch: ${info.path}`)
      continue
    }
    const target = join(repoDir, ...info.path.split('/'))
    let targetSize = 0
    try {
      targetSize = await sizeOf(target)
    } catch {
      skipped.push(info.path)
      continue
    }
    if (bytes + targetSize > maxBytes) {
      warnings.push(`tombstone budget reached (${maxBytes} bytes)`)
      break
    }
    let trash = join(repoDir, ...trashRoot.split('/'), stamp, ...info.path.split('/'))
    try {
      await fs.stat(trash)
      trash = `${trash}-${now}`
    } catch {
      // 不存在
    }
    await fs.mkdir(dirname(trash), { recursive: true })
    try {
      await fs.rename(target, trash)
      bytes += targetSize
      deleted.push(info.path)
      await recordDeleted(repoDir, { path: info.path, at: entry.at, peer: peerDeviceId, deletedAt: now })
    } catch (error) {
      skipped.push(info.path)
      warnings.push(`could not move ${info.path} to trash: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
  return { deleted, skipped, bytes, warnings }
}

/**
 * 文件或目录的字节数。
 * @param {string} path - 绝对路径。
 * @returns {Promise<number>} 字节数。
 */
async function sizeOf(path) {
  const stats = await fs.stat(path)
  if (stats.isFile()) return stats.size
  let total = 0
  for (const entry of await fs.readdir(path, { withFileTypes: true })) {
    total += await sizeOf(join(path, entry.name))
  }
  return total
}

/** 被 P2P 删过的路径账（`.p2p/deleted.json`）。 */
const DELETED_LEDGER = ['.p2p', 'deleted.json']

/**
 * 记一条"这份是我按墓碑删的"（git 推送的删除清单要减掉它）。
 * @param {string} repoDir - 镜像根。
 * @param {object} entry - {path, at, peer, deletedAt}。
 * @returns {Promise<void>} 完成。
 */
export async function recordDeleted(repoDir, entry) {
  const file = join(repoDir, ...DELETED_LEDGER)
  let list = []
  try {
    const parsed = JSON.parse(await fs.readFile(file, 'utf8'))
    if (Array.isArray(parsed)) list = parsed
  } catch {
    // 账本不存在 / 坏了都从空开始（只影响"少减几条"，不影响正确性）
  }
  list.push(entry)
  await writeFileAtomic(file, `${JSON.stringify(list)}\n`)
}

/**
 * 读被 P2P 删过的路径（下一趟 git 推送的删除清单要减去这一份）。
 * @param {string} repoDir - 镜像根。
 * @returns {Promise<string[]>} 路径（去重、排序）。
 */
export async function readDeletedPaths(repoDir) {
  try {
    const parsed = JSON.parse(await fs.readFile(join(repoDir, ...DELETED_LEDGER), 'utf8'))
    if (!Array.isArray(parsed)) return []
    const paths = new Set()
    for (const entry of parsed) {
      if (typeof entry?.path === 'string') paths.add(entry.path)
    }
    return [...paths].sort()
  } catch {
    return []
  }
}

/**
 * 镜像里的相对路径（诊断读数用；只接受 P2P 根之内的）。
 * @param {string} repoDir - 镜像根。
 * @param {string} path - 绝对路径。
 * @returns {string|null} 相对 POSIX 路径。
 */
export function repoRelative(repoDir, path) {
  const rel = relative(repoDir, path)
  if (rel === '' || rel.startsWith('..')) return null
  return rel.split('\\').join('/')
}
