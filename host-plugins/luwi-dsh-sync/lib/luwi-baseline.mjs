// lib/luwi-baseline.mjs — 会话基线与「当前对话」窗口 (Luwi 加, 2026-10-20)
//
// 为什么要有它 (主人 2026-10-20 问「同步开始时, 当前正在进行的对话会同步吗? 我希望可以同步」):
// 口径是「旧对话不同步, 当前在聊的与之后聊的要同步」, 而配对要重启 host —— 正在跑的那一轮会被
// 打断, 它最后一次写入就在重启前几秒。所以判据不能只按"配对那一刻是否已存在", 要按"配对那一刻
// 之后有没有动过":
//
//   · 配对时最近 currentWindowMinutes 分钟内有写入的 → **当前**, 不进基线, 整场同步
//   · 更老的 → 进基线 (记下那一刻的 mtime); 之后只要再被写一次, mtime 变新, 就自动开始同步
//   · 进了基线又一直没再动过的 → 永远不传; 首次同步时从仓库里删掉 (purge)
//
// 这一份只做纯算术与一次扫盘 (临时目录上可单测); 基线整份住在 storage 域, 由 index.mjs 读写

import { stat } from 'node:fs/promises'
import path from 'node:path'
import { listRelFiles } from './mirror.mjs'

/** 账本版本 (storage 域里那份的 v 字段) */
export const BASELINE_VERSION = 1

/** 「当前对话」窗口缺省值 (分钟) —— 配对前这段时间内写过字的会话都算"还在聊" */
export const DEFAULT_CURRENT_WINDOW_MINUTES = 60

/** 基线最多记多少条 (超了只记一部分并如实报 truncated —— 多出来的会被当成新会话传上去) */
export const MAX_BASELINE_ENTRIES = 50_000

/** mtime 比较的容差 (毫秒): 不同文件系统的时间精度不一样, 差 1~2 ms 不算动过 */
export const MTIME_TOLERANCE_MS = 2

/**
 * 扫一遍会话存储, 拿到每个文件的相对路径与 mtime。
 * 符号链接与读不动的文件跳过 (镜像那边也会跳它们), 根不存在时回空表。
 * @param {string} sessionRoot - 会话存储根 (宿主路径)。
 * @returns {Promise<Array<{rel: string, absolute: string, mtimeMs: number, size: number}>>} 文件表。
 */
export async function scanSessionFiles(sessionRoot) {
  let walked
  try {
    walked = await listRelFiles(sessionRoot)
  } catch {
    // 根不存在 / 读不动: 当成"一场会话都没有" —— 基线也就是空的, 不拦任何东西
    return []
  }
  const files = []
  for (const file of walked.files) {
    try {
      const info = await stat(file.absolute)
      files.push({ rel: file.rel, absolute: file.absolute, mtimeMs: info.mtimeMs, size: info.size })
    } catch {
      // 刚被删掉的: 不算进基线 (下一次镜像还会再列一次)
      continue
    }
  }
  return files
}

/**
 * 从一份扫盘结果里算出基线。
 *
 * 记的是 `{相对路径: 那一刻的 mtimeMs}` —— 之后判「还是旧的」只比 mtime, 不比路径集合,
 * 于是"配对后继续聊的老会话"会自己走上来, 不需要任何额外的清单
 * @param {Array<{rel: string, mtimeMs: number}>} entries - `scanSessionFiles` 的产物。
 * @param {{now?: number, windowMinutes?: number, maxEntries?: number}} [opts] - 参数。
 * @returns {{v: number, capturedAt: number, windowMinutes: number, baseline: Record<string, number>, currentCount: number, totalCount: number, truncated: boolean, entries: number}} 账本。
 */
export function captureSessionBaseline(entries, opts = {}) {
  const now = Number.isFinite(opts.now) ? opts.now : Date.now()
  const windowMinutes = Number.isInteger(opts.windowMinutes) && opts.windowMinutes >= 0
    ? opts.windowMinutes
    : DEFAULT_CURRENT_WINDOW_MINUTES
  const maxEntries = Number.isInteger(opts.maxEntries) && opts.maxEntries > 0 ? opts.maxEntries : MAX_BASELINE_ENTRIES
  const cutoff = now - windowMinutes * 60_000
  /** @type {Record<string, number>} */
  const baseline = {}
  let currentCount = 0
  let truncated = false
  for (const entry of Array.isArray(entries) ? entries : []) {
    if (entry.mtimeMs >= cutoff) {
      currentCount += 1
      continue
    }
    if (Object.keys(baseline).length >= maxEntries) {
      truncated = true
      continue
    }
    baseline[entry.rel] = Number.isFinite(entry.mtimeMs) ? entry.mtimeMs : 0
  }
  return {
    v: BASELINE_VERSION,
    capturedAt: now,
    windowMinutes,
    baseline,
    currentCount,
    totalCount: Array.isArray(entries) ? entries.length : 0,
    truncated,
    entries: Object.keys(baseline).length,
  }
}

/**
 * 这一条现在还"算旧的"吗 (镜像与清理共用的唯一判据)。
 *
 * 基线里没有它 = 新会话 (或者配对时还在窗口内) → 新, 同步
 * 基线里有它, 但源文件 mtime 变新了 → 配对之后动过 → 新, 同步
 * 基线里有它, mtime 一点没变 → 旧, 不同步
 * @param {Record<string, number>|undefined|null} baseline - `captureSessionBaseline` 的 baseline 表。
 * @param {string} rel - 会话文件相对路径。
 * @param {number} mtimeMs - 现在量到的 mtime。
 * @returns {boolean} 还算旧就 true。
 */
export function isStillOld(baseline, rel, mtimeMs) {
  if (baseline === undefined || baseline === null) return false
  const recorded = baseline[rel]
  if (!Number.isFinite(recorded)) return false
  if (!Number.isFinite(mtimeMs)) return false
  return mtimeMs <= recorded + MTIME_TOLERANCE_MS
}

/**
 * 从一份现在的扫盘结果里挑出"仍然算旧"的相对路径 (首次同步的清理清单)。
 * @param {Array<{rel: string, mtimeMs: number}>} entries - `scanSessionFiles` 的产物。
 * @param {Record<string, number>|undefined|null} baseline - 基线表。
 * @returns {string[]} 要删的相对路径 (保持扫盘顺序)。
 */
export function oldRelPaths(entries, baseline) {
  if (baseline === undefined || baseline === null) return []
  const stale = []
  for (const entry of Array.isArray(entries) ? entries : []) {
    if (isStillOld(baseline, entry.rel, entry.mtimeMs)) stale.push(entry.rel)
  }
  return stale
}

/**
 * 这一条现在还算旧的么 —— **按路径现量一次 mtime** (清理与取回共用这一个判断)。
 *
 * 基线的判据是 mtime, 所以"现在算不算旧"必须看源文件此刻的时间; 源文件已经不在了
 * (删掉/搬走) 回 true —— 它不可能是"当前在聊的那一场", 也就不该再被镜像或取回搬回来
 * @param {string} sourceRoot - 会话存储根。
 * @param {string} rel - 会话文件相对路径。
 * @param {Record<string, number>|undefined|null} baseline - 基线表。
 * @returns {Promise<boolean>} 仍然算旧就 true。
 */
export async function localStillOld(sourceRoot, rel, baseline) {
  if (baseline === undefined || baseline === null) return false
  const recorded = baseline[rel]
  if (!Number.isFinite(recorded)) return false
  const absolute = absoluteOf(sourceRoot, rel)
  if (absolute === undefined) return false
  let mtimeMs
  try {
    mtimeMs = (await stat(absolute)).mtimeMs
  } catch {
    // 源不在本机: 既没在动, 也就不该被取回
    return true
  }
  return isStillOld(baseline, rel, mtimeMs)
}

/**
 * 镜像里"仍然算旧的"那些相对路径 (首次同步的清理清单)。
 *
 * **只走镜像那一侧**: 镜像里有多少个文件就查多少个, 与 sessionRoot 的规模无关 —— 在手机上
 * 扫一遍 sessionRoot 是 3669 次 stat (2026-10-21 首拉卡死的直接原因), 而镜像里通常只有几十个;
 * 基线里没有的那条 = 新会话, 照旧留着
 * @param {string} mirrorRoot - 镜像根 (`<repoDir>/<MIRROR_DIR>`)。
 * @param {Record<string, number>|undefined|null} baseline - 基线表。
 * @param {string} sourceRoot - 会话存储根 (量的还是源文件的 mtime)。
 * @returns {Promise<string[]>} 要从镜像里删掉的相对路径。
 */
export async function oldMirrorPaths(mirrorRoot, baseline, sourceRoot) {
  if (baseline === undefined || baseline === null) return []
  let walked
  try {
    walked = await listRelFiles(mirrorRoot)
  } catch {
    return []
  }
  const stale = []
  for (const file of walked.files) {
    if (!Number.isFinite(baseline[file.rel])) continue
    if (await localStillOld(sourceRoot, file.rel, baseline)) stale.push(file.rel)
  }
  return stale
}

/**
 * 这条相对路径是不是"影子工作区里的会话" (Luwi 加 2026-10-20 晚)。
 *
 * 会话目录的第一段是 cwd 的编码 (`--E-Mobile-AI-dsh-home-lw-peer-workspaces-<对端id>-DSH--`)。
 * 影子工作区是插件替对端建的那一层投影, 在它里面开的会话属于**对端那一场**, 传上去会让
 * 对端多出一场 cwd 指向本机的重复对话。判据只看编码后的第一段, 不解析会话内容
 * @param {string} rel - 会话文件相对路径。
 * @returns {boolean} 是影子工作区就 true。
 */
export function isShadowWorkspaceRel(rel) {
  if (typeof rel !== 'string' || rel === '') return false
  const first = rel.split('/')[0] ?? ''
  return /-lw-peer-workspaces-/iu.test(first) || /-sync-workspaces-/iu.test(first)
}

/**
 * 基线里的一条相对路径 → 会话 id (路径段里那个 `session-<id>` 目录)。
 * 只给"投影会话不进仓库"那条排除用 (与 host 的 `session-<id>` 目录约定同一口径);
 * 对不上就 undefined, 绝不猜
 * @param {string} rel - 会话文件相对路径。
 * @returns {string|undefined} 会话 id。
 */
export function sessionIdOfRelPath(rel) {
  if (typeof rel !== 'string' || rel === '') return undefined
  for (const segment of rel.split('/')) {
    if (segment.startsWith('session-') && segment.length > 'session-'.length) {
      return segment.slice('session-'.length)
    }
  }
  return undefined
}

/**
 * 一条相对路径里是不是"某个会话 id 的文件" (给 neverSyncSessionIds 用)。
 * @param {string} rel - 会话文件相对路径。
 * @param {Set<string>|undefined|null} ids - 不许同步的会话 id。
 * @returns {boolean} 命中就 true。
 */
export function relBelongsToSession(rel, ids) {
  if (!(ids instanceof Set) || ids.size === 0) return false
  const id = sessionIdOfRelPath(rel)
  if (id !== undefined && ids.has(id)) return true
  // 旧扁平布局: <sessionId>/<file> (没有 session- 前缀), 以及投影会话的目录形态
  for (const segment of rel.split('/')) {
    if (ids.has(segment)) return true
  }
  return false
}

/**
 * 基线账本 → 给人看的一句 (面板/status 用)。
 * @param {object|null|undefined} state - storage 域里那份。
 * @returns {string} 一句话。
 */
export function describeBaseline(state) {
  if (state === null || state === undefined) return 'no baseline (legacy full mirror)'
  const entries = state.entries ?? Object.keys(state.baseline ?? {}).length
  const window = state.windowMinutes ?? DEFAULT_CURRENT_WINDOW_MINUTES
  const pending = state.purgePending === true ? ', purge pending' : ''
  return `${entries} old session file(s) stay local (current window ${window} min)${pending}`
}

/**
 * 相对路径是否落在某个前缀目录里 (POSIX 语义; 纯算术)。
 * @param {string} rel - 相对路径。
 * @param {string} prefix - 前缀目录。
 * @returns {boolean} 在之下就 true。
 */
export function relUnder(rel, prefix) {
  if (typeof rel !== 'string' || typeof prefix !== 'string' || prefix === '') return false
  return rel === prefix || rel.startsWith(`${prefix}/`)
}

/**
 * 一条相对路径的父目录 (POSIX; 没有父目录时空串)。
 * @param {string} rel - 相对路径。
 * @returns {string} 父目录。
 */
export function relDirOf(rel) {
  const index = typeof rel === 'string' ? rel.lastIndexOf('/') : -1
  return index <= 0 ? '' : rel.slice(0, index)
}

/**
 * 会话文件绝对路径 (给 purge 用; 越界/形态不对回 undefined)。
 * @param {string} root - 会话存储根。
 * @param {string} rel - 相对路径。
 * @returns {string|undefined} 绝对路径。
 */
export function absoluteOf(root, rel) {
  if (typeof root !== 'string' || root === '' || typeof rel !== 'string' || rel === '') return undefined
  const segments = rel.split('/')
  if (segments.some(segment => segment === '' || segment === '.' || segment === '..')) return undefined
  return path.join(root, ...segments)
}
