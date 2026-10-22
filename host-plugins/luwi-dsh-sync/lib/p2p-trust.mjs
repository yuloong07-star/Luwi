// lib/p2p-trust.mjs — 信任表：首次见到即信任、换 nodeId 拒绝、吊销、SAS（方案 §8.3）。
//
// 信任表住在**本机** `<repoDir>/.p2p/trust.json`（不进仓库、不进镜像），键是会话仓库里的设备 id，
// 值里记对端的 iroh nodeId。判据只有一条：**同一个设备 id 换了 nodeId 就拒绝**（换机/重装由面板
// 确认一次再放行），吊销过的直接拒。SAS 是给"手输 ticket"与换机那条路准备的人工比对数字。

import { createHash } from 'node:crypto'
import { promises as fs } from 'node:fs'
import { join } from 'node:path'

/** 信任表版本。 */
export const TRUST_VERSION = 1
/** `.p2p/` 目录名（本机私有，不进镜像）。 */
export const P2P_LOCAL_DIR = '.p2p'
/** 信任表文件名。 */
export const TRUST_FILE = 'trust.json'

/**
 * 确保 `.p2p/` 存在且被 git 忽略（里面是私钥、信任表、trash、tmp）。
 * @param {string} repoDir - 镜像根。
 * @returns {Promise<string>} `.p2p` 的绝对路径。
 */
export async function ensureP2pLocalDir(repoDir) {
  const dir = join(repoDir, P2P_LOCAL_DIR)
  await fs.mkdir(dir, { recursive: true })
  const ignore = join(dir, '.gitignore')
  try {
    await fs.access(ignore)
  } catch {
    await fs.writeFile(ignore, '*\n', 'utf8')
  }
  return dir
}

/**
 * 读信任表（缺文件/坏文件都当空表；保留未知顶层键）。
 * @param {string} repoDir - 镜像根。
 * @returns {Promise<{v: number, peers: Record<string, object>}>} 信任表。
 */
export async function readTrust(repoDir) {
  try {
    const parsed = JSON.parse(await fs.readFile(join(repoDir, P2P_LOCAL_DIR, TRUST_FILE), 'utf8'))
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return { v: TRUST_VERSION, peers: {} }
    const peers = parsed.peers !== null && typeof parsed.peers === 'object' && !Array.isArray(parsed.peers) ? parsed.peers : {}
    return { ...parsed, v: Number(parsed.v) || TRUST_VERSION, peers }
  } catch {
    return { v: TRUST_VERSION, peers: {} }
  }
}

/**
 * 写信任表（原子：tmp + rename）。
 * @param {string} repoDir - 镜像根。
 * @param {object} trust - 表。
 * @returns {Promise<void>} 完成。
 */
export async function writeTrust(repoDir, trust) {
  const dir = await ensureP2pLocalDir(repoDir)
  const file = join(dir, TRUST_FILE)
  const temp = `${file}.tmp`
  await fs.writeFile(temp, `${JSON.stringify({ ...trust, v: TRUST_VERSION }, null, 2)}\n`, 'utf8')
  await fs.rename(temp, file)
}

/**
 * 两端 nodeId 的 SAS：排序后 sha256，取前 3 字节折成 6 位数字。
 * @param {string} left - nodeId A。
 * @param {string} right - nodeId B。
 * @returns {string} 6 位数字（不足补 0）。
 */
export function sasDigits(left, right) {
  const sorted = [String(left), String(right)].sort()
  const digest = createHash('sha256').update(sorted.join('\n'), 'utf8').digest()
  const value = (digest[0] << 16) | (digest[1] << 8) | digest[2]
  return String(value % 1_000_000).padStart(6, '0')
}

/**
 * 看一个对端现在是什么状态（不改表）。
 * @param {{peers?: Record<string, object>}} trust - 信任表。
 * @param {{deviceId: string, nodeId: string, now?: number}} input - 对端。
 * @returns {{ok: boolean, state: 'new'|'trusted'|'changed'|'revoked', entry?: object, previousNodeId?: string}} 判定。
 */
export function evaluatePeer(trust, { deviceId, nodeId, now = Date.now() }) {
  const entry = trust?.peers?.[deviceId]
  if (entry === undefined || entry === null || typeof entry !== 'object') return { ok: true, state: 'new' }
  if (Number(entry.revokedAt) > 0) return { ok: false, state: 'revoked', entry }
  if (typeof entry.nodeId === 'string' && entry.nodeId === nodeId) return { ok: true, state: 'trusted', entry }
  return { ok: false, state: 'changed', entry, previousNodeId: typeof entry.nodeId === 'string' ? entry.nodeId : '', at: now }
}

/**
 * 记住一个对端：首次见到即信任（与"填了仓库地址即互信"同一边界），之后只刷新 lastSeenAt/name；
 * 换 nodeId 或吊销过都不改表。
 * @param {object} trust - 信任表（会被浅拷贝，不原地改）。
 * @param {{deviceId: string, nodeId: string, name?: string, now?: number}} input - 对端。
 * @returns {{trust: object, decision: object}} 新表与判定。
 */
export function rememberPeer(trust, { deviceId, nodeId, name = '', now = Date.now() }) {
  const decision = evaluatePeer(trust, { deviceId, nodeId, now })
  const next = { ...trust, peers: { ...(trust?.peers ?? {}) } }
  if (decision.state === 'new') {
    next.peers[deviceId] = {
      nodeId,
      name: typeof name === 'string' ? name : '',
      firstSeenAt: now,
      trustedAt: now,
      lastSeenAt: now,
    }
    return { trust: next, decision: { ok: true, state: 'new' } }
  }
  if (decision.state === 'trusted') {
    const entry = { ...decision.entry, lastSeenAt: now }
    if (typeof name === 'string' && name !== '' && name !== entry.name) entry.name = name
    next.peers[deviceId] = entry
    return { trust: next, decision: { ok: true, state: 'trusted' } }
  }
  return { trust, decision }
}

/**
 * 面板确认换机/重装：把这个设备 id 的 nodeId 换成新的，清掉吊销位。
 * @param {object} trust - 信任表。
 * @param {{deviceId: string, nodeId: string, name?: string, now?: number}} input - 对端。
 * @returns {object} 新表。
 */
export function confirmPeer(trust, { deviceId, nodeId, name = '', now = Date.now() }) {
  const previous = trust?.peers?.[deviceId]
  const next = { ...trust, peers: { ...(trust?.peers ?? {}) } }
  next.peers[deviceId] = {
    ...(previous !== null && typeof previous === 'object' ? previous : {}),
    nodeId,
    name: typeof name === 'string' && name !== '' ? name : String(previous?.name ?? ''),
    firstSeenAt: Number(previous?.firstSeenAt) || now,
    trustedAt: now,
    lastSeenAt: now,
    revokedAt: undefined,
  }
  delete next.peers[deviceId].revokedAt
  return next
}

/**
 * 吊销一个对端（出站不拨、入站 close）。
 * @param {object} trust - 信任表。
 * @param {{deviceId: string, now?: number}} input - 对端。
 * @returns {object} 新表。
 */
export function revokePeer(trust, { deviceId, now = Date.now() }) {
  const previous = trust?.peers?.[deviceId]
  const next = { ...trust, peers: { ...(trust?.peers ?? {}) } }
  next.peers[deviceId] = {
    ...(previous !== null && typeof previous === 'object' ? previous : {}),
    revokedAt: now,
  }
  return next
}

/**
 * 日志/面板里的 nodeId 打码（只留前 8 位）。
 * @param {unknown} nodeId - 原始 id。
 * @returns {string} 打码形态。
 */
export function maskNodeId(nodeId) {
  if (typeof nodeId !== 'string' || nodeId === '') return ''
  return `${nodeId.slice(0, 8)}…`
}

/** 信任表文件的绝对路径（诊断用）。 */
export function trustPath(repoDir) {
  return join(repoDir, P2P_LOCAL_DIR, TRUST_FILE)
}


