// lib/luwi-configure.mjs — 从面板写会话同步配置 (Luwi 加, 2026-10-15)
//
// 为什么要有这一层: 主人要"两端各填一个仓库地址就能连", 而 PC 侧的地址原先写死在 profile 覆盖行
// 里 (显式 cordis 键 > 文件 > DEFAULTS), 面板改了也不生效。所以面板这一路要能写
// `$DSH_HOME/lw/session-sync.json` —— 也就是**手机应用设置页读写的同一个文件**。
//
// 纪律只有三条, 每条都对着 sync/SyncSetting.kt 抄:
//   · **保留未知键**: 整棵树照抄, 只动自己那几个 (文件里还有 peer / live / workspace 段)
//   · **「关」是三件事**: 地址挪进 `lw.savedRemote`, 三档自动值挪进 `lw.saved*`, 再一起归零 ——
//     少一件都不行 (只清远端的话周期拉取照旧按点起来, 白烧电)
//   · **写前校验, 写后如实报**: 地址不像地址 / 分支带空格 / 间隔越界一律拒, 并说清是哪一条
//
// 这一层只吃一个文件路径, 不碰 DSH 的任何东西, 所以能在临时目录上单测。

import { readFile, writeFile, rename } from 'node:fs/promises'
import { dirname, join } from 'node:path'

/** 顶层键 (与 app 的 SyncSetting.kt 逐字对齐; tools/check-sync-setting.mjs 也在钉这几个). */
export const KEYS = Object.freeze({
  REMOTE: 'remote',
  BRANCH: 'branch',
  AUTO_PULL: 'autoPullOnStart',
  AUTO_PUSH: 'autoPushOnTurnEnd',
  INTERVAL: 'pullIntervalMinutes',
  LW: 'lw',
  SAVED_REMOTE: 'savedRemote',
  SAVED_AUTO_PULL: 'savedAutoPull',
  SAVED_AUTO_PUSH: 'savedAutoPush',
  SAVED_INTERVAL: 'savedInterval',
})

/** 间隔上限 (分钟), 与 SyncSetting.MAX_PULL_INTERVAL 同值. */
export const MAX_INTERVAL_MINUTES = 10080

/**
 * 配置文件的规范位置.
 * @param {string} home - `$DSH_HOME`。
 * @returns {string} 文件绝对路径。
 */
export function syncConfigPath(home) {
  return join(home, 'lw', 'session-sync.json')
}

/**
 * 地址像不像一个 git 远端. 空的单独判 (空 = 没填).
 * @param {string} value - 候选地址。
 * @returns {string|null} 不像时回一句原因, 像就回 null。
 */
export function remoteProblem(value) {
  const text = value.trim()
  if (text === '') return 'the repository address is empty'
  if (/\s/u.test(text)) return 'the repository address contains whitespace'
  if (/^(https?|git|ssh|file):\/\//iu.test(text)) return null
  if (/^[\w.-]+@[\w.-]+:/u.test(text)) return null          // git@github.com:you/repo.git
  if (/^[A-Za-z]:[\\/]/u.test(text)) return null            // D:/path/to/repo.git
  if (text.startsWith('/') || text.startsWith('.')) return null
  return 'the repository address must look like https://…, git://…, ssh://…, git@host:you/repo.git or a local path'
}

/**
 * 分支名能不能用.
 * @param {string} value - 候选分支名。
 * @returns {string|null} 不能用时回一句原因。
 */
export function branchProblem(value) {
  const text = value.trim()
  if (text === '') return null                              // 空 = 缺省 main
  if (/\s/u.test(text)) return 'the branch name contains whitespace'
  if (/[~^:?*[\\]/u.test(text)) return 'the branch name contains a character git refuses'
  return null
}

/**
 * 读那份文件 (不存在或坏掉都当空树 —— 面板要能在一片空白上把地址填进去).
 * @param {string} file - 文件路径。
 * @returns {Promise<object>} 解析出来的树。
 */
export async function readSyncTree(file) {
  let text
  try {
    text = await readFile(file, 'utf8')
  } catch {
    return {}
  }
  try {
    const parsed = JSON.parse(text)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

/**
 * 原子写回 (`.tmp` + rename, 与插件别处同一个写法).
 * @param {string} file - 文件路径。
 * @param {object} tree - 整棵树。
 * @returns {Promise<void>} 写完就回。
 */
export async function writeSyncTree(file, tree) {
  const temporary = `${file}.tmp`
  await writeFile(temporary, `${JSON.stringify(tree, null, 2)}\n`, 'utf8')
  await rename(temporary, file)
}

/** 取一个布尔键 (非布尔当没有). */
function boolOf(tree, key) {
  const value = tree[key]
  return typeof value === 'boolean' ? value : undefined
}

/**
 * 拨总开关. 语义与 SyncSetting.setSyncOnIn 一字不差.
 * @param {object} tree - 整棵树 (会被就地改).
 * @param {boolean} on - 开还是关。
 * @returns {string|null} 拒了就回原因。
 */
function applyOn(tree, on) {
  const lw = tree[KEYS.LW] !== null && typeof tree[KEYS.LW] === 'object' && !Array.isArray(tree[KEYS.LW])
    ? { ...tree[KEYS.LW] }
    : {}
  const current = typeof tree[KEYS.REMOTE] === 'string' ? tree[KEYS.REMOTE] : ''
  const saved = typeof lw[KEYS.SAVED_REMOTE] === 'string' ? lw[KEYS.SAVED_REMOTE] : ''

  if (on) {
    const address = current.trim() === '' ? saved : current
    if (address.trim() === '') {
      return 'there is no repository address to turn sync on with — fill one in first, or set it in the phone\'s settings page'
    }
    tree[KEYS.REMOTE] = address
    delete lw[KEYS.SAVED_REMOTE]
    for (const [savedKey, topKey] of [[KEYS.SAVED_AUTO_PULL, KEYS.AUTO_PULL], [KEYS.SAVED_AUTO_PUSH, KEYS.AUTO_PUSH], [KEYS.SAVED_INTERVAL, KEYS.INTERVAL]]) {
      const previous = lw[savedKey]
      if (typeof previous === 'boolean' || typeof previous === 'number') tree[topKey] = previous
      delete lw[savedKey]
    }
  } else {
    if (current.trim() !== '') lw[KEYS.SAVED_REMOTE] = current
    delete tree[KEYS.REMOTE]
    for (const [topKey, savedKey] of [[KEYS.AUTO_PULL, KEYS.SAVED_AUTO_PULL], [KEYS.AUTO_PUSH, KEYS.SAVED_AUTO_PUSH], [KEYS.INTERVAL, KEYS.SAVED_INTERVAL]]) {
      const previous = tree[topKey]
      if (typeof previous === 'boolean' || typeof previous === 'number') lw[savedKey] = previous
    }
    tree[KEYS.AUTO_PULL] = false
    tree[KEYS.AUTO_PUSH] = false
    tree[KEYS.INTERVAL] = 0
  }

  if (Object.keys(lw).length === 0) delete tree[KEYS.LW]
  else tree[KEYS.LW] = lw
  return null
}

/**
 * 把一个补丁写进那份配置. **只动传进来的键**, 别的键一个字节都不碰.
 *
 * @param {string} file - `$DSH_HOME/lw/session-sync.json`。
 * @param {object} patch - `{remote?, branch?, autoPullOnStart?, autoPushOnTurnEnd?, pullIntervalMinutes?, on?}`。
 * @returns {Promise<{ok: boolean, changed: string[], error?: string}>} 结果; 一处被拒就停在那一条上。
 */
export async function applyConfigure(file, patch) {
  const tree = await readSyncTree(file)
  const changed = []

  if (Object.prototype.hasOwnProperty.call(patch, 'remote')) {
    const raw = typeof patch.remote === 'string' ? patch.remote.trim() : String(patch.remote ?? '')
    const problem = remoteProblem(raw)
    if (problem !== null) return { ok: false, changed, error: problem }
    tree[KEYS.REMOTE] = raw
    changed.push(`remote = ${raw}`)
  }

  if (Object.prototype.hasOwnProperty.call(patch, 'branch')) {
    const raw = typeof patch.branch === 'string' ? patch.branch.trim() : String(patch.branch ?? '')
    const problem = branchProblem(raw)
    if (problem !== null) return { ok: false, changed, error: problem }
    if (raw === '') delete tree[KEYS.BRANCH]
    else tree[KEYS.BRANCH] = raw
    changed.push(`branch = ${raw === '' ? 'main' : raw}`)
  }

  if (Object.prototype.hasOwnProperty.call(patch, 'pullIntervalMinutes')) {
    const next = Number(patch.pullIntervalMinutes)
    if (!Number.isInteger(next) || next < 0 || next > MAX_INTERVAL_MINUTES) {
      return { ok: false, changed, error: `pullIntervalMinutes must be an integer in [0, ${MAX_INTERVAL_MINUTES}]` }
    }
    tree[KEYS.INTERVAL] = next
    changed.push(`pullIntervalMinutes = ${next}`)
  }

  for (const [field, key] of [['autoPullOnStart', KEYS.AUTO_PULL], ['autoPushOnTurnEnd', KEYS.AUTO_PUSH]]) {
    if (!Object.prototype.hasOwnProperty.call(patch, field)) continue
    const value = patch[field]
    if (typeof value !== 'boolean') return { ok: false, changed, error: `${field} must be true or false` }
    tree[key] = value
    changed.push(`${field} = ${value}`)
  }

  if (Object.prototype.hasOwnProperty.call(patch, 'on')) {
    const value = patch.on
    if (typeof value !== 'boolean') return { ok: false, changed, error: 'on must be true or false' }
    const problem = applyOn(tree, value)
    if (problem !== null) return { ok: false, changed, error: problem }
    changed.push(value ? 'sync = on' : 'sync = off (address parked, autos zeroed)')
  }

  if (changed.length === 0) {
    return { ok: false, changed, error: 'nothing to change: pass remote, branch, autoPullOnStart, autoPushOnTurnEnd, pullIntervalMinutes or on' }
  }

  // 目录可能还不存在 (全新机器上 lw/ 还没建)
  try {
    await writeSyncTree(file, tree)
  } catch (error) {
    // 目录不存在时补建一次
    try {
      const { mkdir } = await import('node:fs/promises')
      await mkdir(dirname(file), { recursive: true })
      await writeSyncTree(file, tree)
    } catch (inner) {
      return { ok: false, changed: [], error: `could not write ${file}: ${inner?.message ?? inner}` }
    }
  }
  return { ok: true, changed }
}
