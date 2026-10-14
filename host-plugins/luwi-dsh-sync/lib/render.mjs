// Luwi 改了什么 (对照上游 v0.2.24):
// 1. renderPull 多一行 "brought ... into the local session store" (取回步骤的读数; 见 lib/luwi-adopt.mjs)
// 2. 三个渲染多一个可选的 label ('' 时与上游逐字一致) 与工作区那根的读数: 排除表/单文件上限
//    跳过了什么、取回之外还更新/搬走了什么 (G1-C; 见 lib/luwi-workspace.mjs)
// 3. renderStatus 多一行 peers 读数 (status.peerCount > 0 时才有, 上游仓库里没有 peers/,
//    缺省输出一个字不变), 另有 renderPeers (G1-D /sync peers 用)
//
// lib/render.mjs — 结果文本渲染（纯函数，零依赖；输入全部 JSON 安全）。

import { MERGE_KINDS } from './constants.mjs'

/** 时间戳 → 本地可读时间（缺省 '-'）。 */
function timeOf(value) {
  if (typeof value !== 'number' || value <= 0) return '-'
  return new Date(value).toISOString()
}

/** `sync: ` 与第一行的前缀: label 为空时与上游一致。 */
function head(label) {
  return label === undefined || label === '' ? 'sync: ' : `sync: ${label} `
}

/**
 * /sync 状态文本（sync_status 工具共用同一渲染）。
 * @param {object} status - collectStatus 产物。
 * @param {string} [label] - Luwi 加：这一根叫什么（'' = 与上游逐字一致）。
 * @returns {string}
 */
export function renderStatus(status, label) {
  if (!status.ok) {
    return `${head(label)}status unavailable: ${status.error ?? 'unknown error'}`
  }
  const lines = [
    `${head(label)}repo branch ${status.branch} · remote ${status.remote}`,
    `      head ${status.head ?? '(no commits yet)'} · remote ${status.remoteHead ?? '(never fetched)'}`,
    `      ahead ${status.ahead} · behind ${status.behind}${status.mergeInProgress ? ' · merge in progress (resolve or re-pull)' : ''}`,
  ]
  if (status.dirty.length > 0) {
    lines.push(`      uncommitted (${status.dirty.length}): ${status.dirty.slice(0, 10).join(', ')}${status.dirty.length > 10 ? ' …' : ''}`)
  } else {
    lines.push('      mirror clean (nothing uncommitted)')
  }
  if (status.forks.length > 0) {
    lines.push(`      fork files (both-sides-kept conflicts): ${status.forks.slice(0, 10).join(', ')}${status.forks.length > 10 ? ' …' : ''}`)
  }
  // Luwi 加：工作区那根的两条账（排除表挡了什么、哪些超了单文件上限）
  if ((status.excludedCount ?? 0) > 0) {
    lines.push(`      excluded by the exclude list (${status.excludedCount}): ${status.excluded.slice(0, 10).join(', ')}${status.excludedCount > 10 ? ' …' : ''}`)
  }
  if ((status.oversizedCount ?? 0) > 0) {
    lines.push(`      over the file size cap (${status.oversizedCount}): ${status.oversized.slice(0, 10).join(', ')}${status.oversizedCount > 10 ? ' …' : ''}`)
  }
  if (status.diffStat !== '') {
    lines.push(`      diff HEAD..remote:\n${status.diffStat.split('\n').map(line => `        ${line}`).join('\n')}`)
  }
  // Luwi 加 (G1-D)：镜像里见过几张对端名片 (没有这个字段 = 上游行为, 一行都不加)
  if ((status.peerCount ?? 0) > 0) {
    lines.push(`      peers seen in the mirror: ${status.peerCount} (run "sync peers" for names and URLs)`)
  }
  if (status.lastCommits.length > 0) {
    lines.push(`      last commits:\n${status.lastCommits.map(line => `        ${line}`).join('\n')}`)
  }
  if (status.error !== undefined) lines.push(`      last error: ${status.error}`)
  for (const warning of (status.warnings ?? [])) lines.push(`      warning: ${warning}`)
  return lines.join('\n')
}

/**
 * /sync diff 文本：本地未提交变更 + HEAD..远端统计（只读）。
 * @param {object} status - collectStatus 产物。
 * @param {string} [label] - Luwi 加：这一根叫什么。
 * @returns {string}
 */
export function renderDiff(status, label) {
  if (!status.ok) return `${head(label)}diff unavailable: ${status.error ?? 'unknown error'}`
  const lines = [`${head(label)}diff (local worktree vs last-fetched remote; read-only)`]
  if (status.dirty.length === 0) lines.push('  uncommitted changes: none')
  else {
    lines.push(`  uncommitted changes (${status.dirty.length}):`)
    for (const path of status.dirty.slice(0, 50)) lines.push(`    M ${path}`)
    if (status.dirty.length > 50) lines.push(`    … ${status.dirty.length - 50} more`)
  }
  if (status.diffStat === '') lines.push('  HEAD..remote: no difference (or remote never fetched)')
  else lines.push(`  HEAD..remote:\n${status.diffStat.split('\n').map(line => `    ${line}`).join('\n')}`)
  return lines.join('\n')
}

/**
 * pull 结果文本。
 * @param {{ok: boolean, pulled?: boolean, merged?: boolean, adopted?: number, brought?: number, updated?: number, trashed?: number, appended?: number, diverged?: number, forks?: string[], head?: string, error?: string, warnings?: string[]}} result - pull 产物。
 * @param {string} [label] - Luwi 加：这一根叫什么。
 * @returns {string}
 */
export function renderPull(result, label) {
  if (!result.ok) return `${head(label)}pull failed: ${result.error ?? 'unknown error'}`
  const lines = [`${head(label)}pull ok`]
  if (!result.pulled) {
    lines.push('  already up to date (no remote changes or remote never fetched)')
    return lines.join('\n')
  }
  lines.push(`  remote changes merged${result.merged === true ? ' (merge commit)' : ''} → head ${result.head ?? ''}`)
  if ((result.adopted ?? 0) > 0) lines.push(`  adopted ${result.adopted} remote-only append(s) (local side was unchanged)`)
  if ((result.brought ?? 0) > 0) lines.push(`  brought ${result.brought} remote session file(s) into the local session store`)
  // Luwi 加：工作区那根的两条账（本地那份跟着远端变 / 跟着远端删）
  if ((result.updated ?? 0) > 0) lines.push(`  updated ${result.updated} local file(s) to the merged remote bytes (local side was unchanged)`)
  if ((result.trashed ?? 0) > 0) lines.push(`  moved ${result.trashed} local file(s) to the trash (deleted on the remote, unmodified here)`)
  if ((result.appended ?? 0) > 0) lines.push(`  both sides appended in ${result.appended} file(s): local version kept, remote version preserved as fork file(s)`)
  if ((result.diverged ?? 0) > 0) lines.push(`  diverged in ${result.diverged} file(s) (non-append rewrite): local version kept, remote version preserved as fork file(s) — inspect before continuing`)
  for (const fork of (result.forks ?? []).slice(0, 20)) lines.push(`    fork ${fork}`)
  if ((result.forks ?? []).length > 20) lines.push(`    … ${result.forks.length - 20} more`)
  for (const warning of (result.warnings ?? [])) lines.push(`    warning: ${warning}`)
  return lines.join('\n')
}

/**
 * push 结果文本。
 * @param {{ok: boolean, pushed?: boolean, head?: string, mirrored?: number, deleted?: number, excluded?: number, oversized?: number, remote?: string, error?: string, warnings?: string[]}} result - push 产物。
 * @param {string} [label] - Luwi 加：这一根叫什么。
 * @returns {string}
 */
export function renderPush(result, label) {
  if (!result.ok) return `${head(label)}push failed: ${result.error ?? 'unknown error'}`
  if (!result.pushed) return `${head(label)}push ok — nothing new to push (mirror already committed and remote up to date)`
  const lines = [
    `${head(label)}push ok`,
    `  mirrored ${result.mirrored ?? 0} file(s)${(result.deleted ?? 0) > 0 ? `, deleted ${result.deleted} (removed locally)` : ''}`,
    `  pushed to ${result.remote ?? 'remote'} → head ${result.head ?? ''}`,
  ]
  for (const warning of (result.warnings ?? [])) lines.push(`  warning: ${warning}`)
  return lines.join('\n')
}

/**
 * 工具规范结果里的错误块（pull/push/status 共用）。
 * @param {string} message - 已脱敏的错误消息。
 * @returns {{ok: boolean, error: string}}
 */
export function errorValue(message) {
  return { ok: false, error: message }
}

/**
 * Luwi 加 (G1-D)：/sync peers 的文本。
 *
 * 名片来自本地镜像工作树 —— 措辞按这个口径说 ("seen in the mirror"), 远端刚推、本地还没
 * 拉的不在这里。self 的排最前, 其余按更新时刻倒序
 * @param {Array<{deviceId: string, name: string, url: string, updatedAt: string, self?: boolean, file?: string}>} cards - 名片 (readPeerCards 产物 + self 标记)。
 * @param {number} [broken] - 读不出来的名片文件数。
 * @returns {string}
 */
export function renderPeers(cards, broken = 0) {
  const lines = ['sync: peers (device cards in the local mirror, as of the last pull/push)']
  if (cards.length === 0) {
    lines.push('  (none yet - cards ride the next push/pull against a shared remote)')
    return lines.join('\n')
  }
  const ordered = [...cards].sort((a, b) => {
    if (a.self === true && b.self !== true) return -1
    if (b.self === true && a.self !== true) return 1
    return String(b.updatedAt).localeCompare(String(a.updatedAt))
  })
  for (const card of ordered) {
    const who = card.self === true
      ? (card.name !== '' ? `${card.name} (this device)` : 'this device')
      : (card.name !== '' ? card.name : '(unnamed)')
    const where = card.url !== '' ? card.url : '(no URL configured)'
    const id = card.deviceId !== '' ? card.deviceId.slice(0, 8) : '????????'
    const when = card.updatedAt !== '' ? card.updatedAt : 'never written'
    lines.push(`  ${who} · ${where} · ${id} · ${when}`)
  }
  if (broken > 0) lines.push(`  ${broken} unreadable card file(s) skipped`)
  return lines.join('\n')
}

export { MERGE_KINDS, timeOf }
