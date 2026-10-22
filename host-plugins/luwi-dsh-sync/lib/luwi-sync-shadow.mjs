// lib/luwi-sync-shadow.mjs — 对端工作区的影子副本 (Luwi 加, 2026-10-20)
//
// 主人的两条口径:
//   · 工作区文件按设备分开 (workspaces/<设备名>/<工作区名>/), 不合并
//   · 拉取之后对端那份**不写回本机工作区**, 落到影子目录供查看 —— 也就是手机里现有的
//     `dsh（PC）` 那一组 (目录与命名与对端影子逐字一致, 于是文件与投影会话同在一组里)
//
// 这一份只管"把仓库里的某个命名空间按字节物化到影子目录":
//   · 与影子目录里已有字节相同 → 不写 (pull 每 15 分钟一趟, 不该每次都抄一遍)
//   · 仓库里没有、而上一趟 checkpoint 里有的 → 搬进回收站 (`.trash/<UTC 戳>/`), 不 unlink
//   · 本设备的命名空间**不物化** (那是自己传上去的备份, 没有"查看"的意义)
//
// 影子目录的注册交给既有的对端影子那一套 (refreshPeerProxies): 我们不重复注册, 只管把文件
// 放进去 —— 对端通道一旦连上, 同一组里就同时有文件与投影会话

import { promises as fs } from 'node:fs'
import { join } from 'node:path'
import { WORKSPACES_DIR, WORKSPACE_PEERS_DIR } from './constants.mjs'
import { adoptMergedSessions } from './luwi-adopt.mjs'
import { shadowDirName } from './luwi-peer.mjs'
import { slugSegment } from './luwi-workspace.mjs'

/** 影子设备 id 的安全段 (与 index.mjs 的 safeSegment 同一口径) */
function safeSegment(value) {
  return String(value ?? '').replace(/[^0-9A-Za-z._-]/gu, '').slice(0, 64) || 'unknown'
}

/**
 * 一台设备的工作区名片路径。
 * @param {string} repoDir - 同步仓库工作树。
 * @param {string} deviceId - 设备 id。
 * @returns {string} 文件绝对路径。
 */
export function workspaceCardPath(repoDir, deviceId) {
  return join(repoDir, WORKSPACE_PEERS_DIR, `${safeSegment(deviceId)}.json`)
}

/**
 * 读回仓库里所有设备的工作区名片 (坏文件跳过并记一句)。
 * @param {string} repoDir - 同步仓库工作树。
 * @returns {Promise<{cards: object[], broken: number}>} 名片与坏文件数。
 */
export async function readWorkspaceCards(repoDir) {
  const directory = join(repoDir, WORKSPACE_PEERS_DIR)
  let names = []
  try {
    names = await fs.readdir(directory)
  } catch {
    return { cards: [], broken: 0 }
  }
  const cards = []
  let broken = 0
  for (const name of names) {
    if (!name.endsWith('.json')) continue
    try {
      const parsed = JSON.parse(await fs.readFile(join(directory, name), 'utf8'))
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        broken += 1
        continue
      }
      cards.push(parsed)
    } catch {
      broken += 1
    }
  }
  return { cards, broken }
}

/**
 * 写本机的工作区名片 (只写自己那份; 别的设备的卡一个字节都不碰)。
 * @param {object} input - 取值。
 * @param {string} input.repoDir - 同步仓库工作树。
 * @param {string} input.deviceId - 本机设备 id。
 * @param {string} input.name - 本机设备名。
 * @param {Array<{slug: string, title: string}>} input.workspaces - 本机这一轮的工作区。
 * @param {number} [input.at] - 写入时刻。
 * @returns {Promise<boolean>} 有变化就 true。
 */
export async function writeWorkspaceCard(input) {
  if (typeof input.repoDir !== 'string' || input.repoDir === '') return false
  if (typeof input.deviceId !== 'string' || input.deviceId === '') return false
  const name = typeof input.name === 'string' ? input.name : ''
  const workspaces = (Array.isArray(input.workspaces) ? input.workspaces : [])
    .filter(entry => entry !== null && typeof entry === 'object' && typeof entry.slug === 'string' && entry.slug !== '')
    .map(entry => ({ slug: entry.slug, title: typeof entry.title === 'string' ? entry.title : entry.slug }))
  const target = workspaceCardPath(input.repoDir, input.deviceId)
  // 只有**内容**变了才写: `at` 每趟都变的话两端每次都判 DIVERGED, 每轮 pull 就长出一个 fork
  // (2026-10-21 清理时那 6 个 workspace-peers 的 fork 全是这么来的)
  try {
    const parsed = JSON.parse(await fs.readFile(target, 'utf8'))
    if (
      parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      && parsed.deviceId === input.deviceId && parsed.name === name
      && sameWorkspaceList(parsed.workspaces, workspaces)
    ) {
      return false
    }
  } catch {
    // 不在 / 不是 JSON → 照写
  }
  const card = {
    v: 1,
    deviceId: input.deviceId,
    name,
    at: Number.isFinite(input.at) ? input.at : Date.now(),
    workspaces,
  }
  const text = `${JSON.stringify(card, null, 2)}\n`
  await fs.mkdir(join(input.repoDir, WORKSPACE_PEERS_DIR), { recursive: true })
  const temporary = `${target}.tmp`
  await fs.writeFile(temporary, text, 'utf8')
  await fs.rename(temporary, target)
  return true
}

/**
 * 两份工作区清单是否等价 (顺序也算 —— 它是按工作区源的排列写出来的)。
 * @param {unknown} left - 文件里那一份。
 * @param {Array<{slug: string, title: string}>} right - 这一趟算出来的那一份。
 * @returns {boolean} 一样就 true。
 */
function sameWorkspaceList(left, right) {
  if (!Array.isArray(left) || left.length !== right.length) return false
  return left.every((entry, index) => entry?.slug === right[index].slug && entry?.title === right[index].title)
}

/**
 * 从一张名片算出一台设备某个工作区在仓库里的命名空间与影子目录。
 * @param {object} card - `workspace-peers/<id>.json` 的内容。
 * @param {{slug: string, title: string}} workspace - 名片里的那一条。
 * @param {string} shadowRoot - 影子根 (`$DSH_HOME/lw/peer-workspaces`)。
 * @returns {{srcMirrorDir: string, shadowDir: string, title: string}} 路径。
 */
export function shadowTargetsOf(card, workspace, shadowRoot) {
  const deviceSlug = slugSegment(card.name && String(card.name).trim() !== '' ? card.name : `device-${card.deviceId}`, 40, 'device')
  const title = typeof workspace.title === 'string' && workspace.title.trim() !== '' ? workspace.title : workspace.slug
  return {
    srcMirrorDir: `${WORKSPACES_DIR}/${deviceSlug}/${workspace.slug}`,
    shadowDir: join(shadowRoot, safeSegment(card.deviceId), shadowDirName(title, card.deviceId)),
    title,
  }
}

/**
 * 把仓库里**别的设备**的工作区命名空间物化到影子目录。
 * @param {object} deps - 依赖。
 * @param {string} deps.repoDir - 同步仓库工作树。
 * @param {string} deps.shadowRoot - 影子根 (`$DSH_HOME/lw/peer-workspaces`)。
 * @param {object[]} deps.cards - `readWorkspaceCards` 的 cards。
 * @param {string} deps.ownDeviceId - 本机设备 id (它的命名空间不物化)。
 * @param {(mirrorDir: string) => (rel: string) => Promise<boolean>} deps.wasMirroredFor - 删除前提: 这条路径上一趟 checkpoint 里有没有。
 * @param {string} [deps.trashDir] - 回收站根。
 * @returns {Promise<{workspaces: object[], brought: number, updated: number, trashed: number, failed: number}>} 读数。
 */
export async function materializeSyncShadows(deps) {
  const shadowRoot = deps.shadowRoot
  const trashDir = typeof deps.trashDir === 'string' && deps.trashDir !== ''
    ? deps.trashDir
    : join(shadowRoot, '.trash')
  const results = []
  let brought = 0
  let updated = 0
  let trashed = 0
  let failed = 0
  for (const card of Array.isArray(deps.cards) ? deps.cards : []) {
    if (card === null || typeof card !== 'object') continue
    if (typeof card.deviceId !== 'string' || card.deviceId === '' || card.deviceId === deps.ownDeviceId) continue
    const workspaces = Array.isArray(card.workspaces) ? card.workspaces : []
    for (const workspace of workspaces) {
      if (workspace === null || typeof workspace !== 'object' || typeof workspace.slug !== 'string' || workspace.slug === '') continue
      const { srcMirrorDir, shadowDir, title } = shadowTargetsOf(card, workspace, shadowRoot)
      try {
        const adopted = await adoptMergedSessions({
          targetRoot: shadowDir,
          repoDir: deps.repoDir,
          mirrorDir: srcMirrorDir,
          mode: 'mirror',
          trashDir,
          wasMirrored: typeof deps.wasMirroredFor === 'function' ? deps.wasMirroredFor(srcMirrorDir) : undefined,
        })
        brought += adopted.brought ?? 0
        updated += adopted.updated ?? 0
        trashed += adopted.trashed ?? 0
        failed += Array.isArray(adopted.failed) ? adopted.failed.length : 0
        results.push({ deviceId: card.deviceId, device: card.name ?? '', workspace: title, dir: shadowDir, brought: adopted.brought ?? 0, updated: adopted.updated ?? 0, trashed: adopted.trashed ?? 0 })
      } catch {
        failed += 1
      }
    }
  }
  return { workspaces: results, brought, updated, trashed, failed }
}
