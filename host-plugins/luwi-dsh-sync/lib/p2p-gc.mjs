// lib/p2p-gc.mjs — P2P 的兜底回收（方案 §6.3 的四类残留里的前三类）。
//
// 墓碑之外的清理**必须保守**：只动 P2P 根、只动"对端名下"的路径、删下来的进 trash 并记账，
// 且缺席删除要"连续两轮缺席 + 本地副本超过 30 天"两个条件同时成立。四类残留：
//   ① `.p2p/tmp`：启动清一次、超过 24 h 的也清；
//   ② 撤销 / 90 天不见的对端：只删它的 `base/<peerId>/`（清单缓存），不碰镜像内容；
//   ③ 连续两轮缺席 + 本地副本 > 30 天：当墓碑的兜底（调用方把"对端清单里没有"的路径给进来）；
//   ④ 邮箱 v1 不回收（方案明确不做）。
//
// 所有结果都带计数，进 `status.p2p.gc`。

import { promises as fs } from 'node:fs'
import { dirname, join } from 'node:path'

import { applyTombstones, classifyPath, readDeletedPaths } from './p2p-apply.mjs'

/** 缺省阈值（方案 §6.3）。 */
export const P2P_GC_DEFAULTS = Object.freeze({
  tmpMaxAgeMs: 24 * 60 * 60 * 1000,
  absentMinAgeMs: 30 * 24 * 60 * 60 * 1000,
  absentRounds: 2,
  peerUnseenMs: 90 * 24 * 60 * 60 * 1000,
})

const ABSENT_LEDGER = ['.p2p', 'absent.json']

/**
 * 目录/文件的字节数（不存在回 0）。
 * @param {string} path - 绝对路径。
 * @returns {Promise<number>} 字节数。
 */
async function sizeOf(path) {
  try {
    const stats = await fs.stat(path)
    if (stats.isFile()) return stats.size
    let total = 0
    for (const entry of await fs.readdir(path, { withFileTypes: true })) total += await sizeOf(join(path, entry.name))
    return total
  } catch {
    return 0
  }
}

/** 目录/文件的最晚修改时间（不存在回 null）。 */
async function newestMtime(path) {
  try {
    const stats = await fs.stat(path)
    if (stats.isFile()) return stats.mtimeMs
    let newest = stats.mtimeMs
    for (const entry of await fs.readdir(path, { withFileTypes: true })) {
      const child = await newestMtime(join(path, entry.name))
      if (child !== null && child > newest) newest = child
    }
    return newest
  } catch {
    return null
  }
}

/**
 * 清 `.p2p/tmp`：启动时全清，或只清超过 `maxAgeMs` 的；两种模式由 `all` 选。
 * @param {object} input - {repoDir, now, maxAgeMs, all}。
 * @param {string} input.repoDir - 镜像根。
 * @param {number} [input.now] - 时钟。
 * @param {number} [input.maxAgeMs] - 年龄阈值（缺省 24 h）。
 * @param {boolean} [input.all] - true = 不看年龄，全清（启动那一趟）。
 * @returns {Promise<{removed: number, bytes: number, kept: number}>} 结果。
 */
export async function sweepTmp({ repoDir, now = Date.now(), maxAgeMs = P2P_GC_DEFAULTS.tmpMaxAgeMs, all = false }) {
  const tmpRoot = join(repoDir, '.p2p', 'tmp')
  let entries
  try {
    entries = await fs.readdir(tmpRoot, { withFileTypes: true })
  } catch {
    return { removed: 0, bytes: 0, kept: 0 }
  }
  let removed = 0
  let bytes = 0
  let kept = 0
  for (const entry of entries) {
    const full = join(tmpRoot, entry.name)
    const newest = await newestMtime(full)
    if (newest === null) continue
    if (all === true || now - newest > maxAgeMs) {
      bytes += await sizeOf(full)
      await fs.rm(full, { recursive: true, force: true })
      removed += 1
    } else {
      kept += 1
    }
  }
  return { removed, bytes, kept }
}

/**
 * 收掉"撤销 / 90 天不见"的对端缓存（只删 `base/<peerId>/`）。
 * @param {object} input - {repoDir, peers, now, unseenMs}。
 * @param {string} input.repoDir - 镜像根。
 * @param {Array<{id: string, lastSeen?: number, revoked?: boolean}>} input.peers - 对端读数。
 * @param {number} [input.now] - 时钟。
 * @param {number} [input.unseenMs] - 多久算不见（缺省 90 天）。
 * @returns {Promise<{removedPeers: string[], bytes: number}>} 结果。
 */
export async function sweepPeerState({ repoDir, peers, now = Date.now(), unseenMs = P2P_GC_DEFAULTS.peerUnseenMs }) {
  const removedPeers = []
  let bytes = 0
  for (const peer of Array.isArray(peers) ? peers : []) {
    if (typeof peer?.id !== 'string' || peer.id === '' || peer.id.includes('/') || peer.id === '.' || peer.id === '..') continue
    const stale = peer.revoked === true || (Number.isFinite(peer.lastSeen) && now - Number(peer.lastSeen) > unseenMs)
    if (!stale) continue
    const dir = join(repoDir, 'base', peer.id)
    try {
      await fs.stat(dir)
    } catch {
      continue
    }
    bytes += await sizeOf(dir)
    await fs.rm(dir, { recursive: true, force: true })
    removedPeers.push(peer.id)
  }
  return { removedPeers, bytes }
}

/** 读缺席账本（坏文件当空）。 */
async function readAbsentLedger(repoDir) {
  try {
    const parsed = JSON.parse(await fs.readFile(join(repoDir, ...ABSENT_LEDGER), 'utf8'))
    return parsed !== null && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

async function writeAbsentLedger(repoDir, ledger) {
  const file = join(repoDir, ...ABSENT_LEDGER)
  await fs.mkdir(dirname(file), { recursive: true })
  const temp = `${file}.tmp`
  await fs.writeFile(temp, `${JSON.stringify(ledger)}\n`, 'utf8')
  await fs.rename(temp, file)
}

/**
 * 缺席兜底：只处理"对端名下、不在邮箱里、连续两轮缺席、本地副本超过 30 天"的路径。
 *
 * `absent` 是这一轮"对端清单里没有"的候选路径（调用方给）。连续两轮都缺席才会走到删除，
 * 删下来仍走 `applyTombstones`（trash + `.p2p/deleted.json`），所以和墓碑是同一条安全路。
 * @param {object} input - {repoDir, peerDeviceId, absent, now, rounds, minAgeMs}。
 * @param {string} input.repoDir - 镜像根。
 * @param {string} input.peerDeviceId - 对端设备 id。
 * @param {string[]} input.absent - 这一轮缺席的路径。
 * @param {number} [input.now] - 时钟。
 * @param {number} [input.rounds] - 连续缺席几轮才删（缺省 2）。
 * @param {number} [input.minAgeMs] - 本地副本至少多旧（缺省 30 天）。
 * @returns {Promise<{deleted: string[], waiting: string[], dropped: string[], bytes: number, warnings: string[]}>} 结果。
 */
export async function sweepAbsent({ repoDir, peerDeviceId, absent, now = Date.now(), rounds = P2P_GC_DEFAULTS.absentRounds, minAgeMs = P2P_GC_DEFAULTS.absentMinAgeMs }) {
  const ledger = await readAbsentLedger(repoDir)
  const mine = ledger[peerDeviceId] !== null && typeof ledger[peerDeviceId] === 'object' ? ledger[peerDeviceId] : {}
  const next = {}
  const candidates = []
  const seen = new Set()
  for (const raw of Array.isArray(absent) ? absent : []) {
    const info = classifyPath(raw)
    if (info === null || info.p2p !== true || info.owner !== peerDeviceId) continue
    if (info.root === 'inbox' || info.root === 'inbox-ack') continue
    if (seen.has(info.path)) continue
    seen.add(info.path)
    const previous = mine[info.path]
    const count = previous !== null && typeof previous === 'object' ? Number(previous.rounds) || 0 : 0
    const firstAt = previous !== null && typeof previous === 'object' && Number.isFinite(previous.firstAt) ? Number(previous.firstAt) : now
    next[info.path] = { rounds: count + 1, firstAt }
    if (count + 1 >= rounds) candidates.push(info.path)
  }
  const deleted = []
  const dropped = []
  let bytes = 0
  if (candidates.length > 0) {
    const entries = []
    for (const path of candidates) {
      const full = join(repoDir, ...path.split('/'))
      const mtime = await newestMtime(full).catch(() => null)
      if (mtime === null) {
        dropped.push(path) // 本地早就没有了: 不需要删, 也就不用等年龄
        delete next[path]
        continue
      }
      if (now - mtime < minAgeMs) continue // 太新, 再等一轮
      entries.push({ path, at: now })
    }
    if (entries.length > 0) {
      const result = await applyTombstones({ repoDir, peerDeviceId, entries, now })
      deleted.push(...result.deleted)
      bytes += result.bytes
      for (const path of result.deleted) delete next[path]
      for (const path of result.skipped) delete next[path]
    }
  }
  ledger[peerDeviceId] = next
  // 账本里没有任何候选时也别留空对象
  if (Object.keys(next).length === 0) delete ledger[peerDeviceId]
  await writeAbsentLedger(repoDir, ledger)
  const waiting = Object.keys(next).filter(path => !deleted.includes(path) && !dropped.includes(path))
  return { deleted, waiting, dropped, bytes, warnings: [] }
}

/**
 * 一次 GC 的读数（进 `status.p2p.gc`）。
 * @param {object} input - {tmp, peers, absent}。
 * @returns {object} 计数。
 */
export function gcSummary({ tmp = undefined, peers = undefined, absent = undefined } = {}) {
  return {
    tmp: tmp === undefined ? undefined : { removed: tmp.removed, bytes: tmp.bytes, kept: tmp.kept },
    peers: peers === undefined ? undefined : { removed: peers.removedPeers.length, bytes: peers.bytes },
    absent: absent === undefined ? undefined : { deleted: absent.deleted.length, waiting: absent.waiting.length, bytes: absent.bytes },
  }
}

/** 本机被 P2P 删过的路径（面板读数用；与 apply 的账本同一份）。 */
export { readDeletedPaths }
