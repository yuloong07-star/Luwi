// lib/p2p-manifest.mjs — 根清单与差分（方案 §5.4 MANIFEST / §6.2 集合头；零网络）。
//
// 两种形态：
//   - 普通根（live / peers / tombstones / inbox / inbox-ack / workspaces）：每个文件一行
//     `[path, size, sha256]`；
//   - `conv` 根：**每个会话一行** —— `[conv/<设备>/<会话>/, meta.bytes, meta.sha256]`，不再
//     逐条列 chunk（30 场会话的清单从 ~600 行降到 30 行）。
//
// 差分只回答"哪些路径要"，不解释怎么传：`NEED` 的两种形态（文件 / 以 `/` 结尾的集合）都在
// 这里算出来。清单仍按 5000 条一页分页（`next` 游标就是下一页的起点）。

import { createHash } from 'node:crypto'
import { promises as fs } from 'node:fs'
import { join, relative } from 'node:path'

import { classifyPath, maySend, normalizeP2pPath } from './p2p-apply.mjs'

/** 一页多少个条目（§5.4：每页 5000 条）。 */
export const MANIFEST_PAGE_LIMIT = 5000

/**
 * 跨端一致的路径规范形（比较与摘要都用它）。
 *
 * POSIX 分隔符、去尾部斜杠、`toLowerCase()` —— 两端里有一台是 Windows 时同名文件只有大小写
 * 不同是两个盘上文件，而同步镜像只该有一份；摘要也按小写算，否则同一棵树在两端会算出两个
 * digest，HEADS 这一层就永远"有变化"。
 * @param {unknown} path - 原始路径。
 * @returns {string|null} 规范形。
 */
export function canonicalPath(path) {
  const normalized = normalizeP2pPath(typeof path === 'string' ? path.replace(/\/+$/u, '') : path)
  return normalized === null ? null : normalized.toLowerCase()
}

/**
 * 根清单的摘要：按路径排序后对 `path\0size\0sha256\n` 求 sha256。
 * @param {Array<[string, number, string]>} entries - 清单行。
 * @returns {string} 十六进制摘要。
 */
export function digestOfEntries(entries) {
  const lines = entries
    .map(([path, size, sha]) => `${canonicalPath(path) ?? String(path)}\0${Number(size)}\0${String(sha)}`)
    .sort()
  return createHash('sha256').update(lines.join('\n')).digest('hex')
}

/** 递归列出目录下的文件（返回绝对路径，排序稳定）。 */
async function listFiles(dirAbs) {
  const out = []
  let entries
  try {
    entries = await fs.readdir(dirAbs, { withFileTypes: true })
  } catch {
    return out
  }
  for (const entry of entries) {
    if (entry.name.startsWith('.')) continue
    const full = join(dirAbs, entry.name)
    if (entry.isDirectory()) out.push(...await listFiles(full))
    else if (entry.isFile()) out.push(full)
  }
  return out.sort()
}

async function shaAndSize(fileAbs) {
  const data = await fs.readFile(fileAbs)
  return { size: data.length, sha256: createHash('sha256').update(data).digest('hex') }
}

/**
 * 建一个根的清单。
 *
 * @param {object} input - {repoDir, root, deviceId, pageLimit, cursor}。
 * @param {string} input.repoDir - 镜像根。
 * @param {string} input.root - P2P 根名。
 * @param {string} [input.deviceId] - 给了就只列"本机有权发"的路径（作者规则）。
 * @param {number} [input.pageLimit] - 每页条数。
 * @param {string} [input.cursor] - 上一页的 `next`。
 * @returns {Promise<{root: string, digest: string, entries: Array<[string, number, string]>, next?: string, warnings: string[]}>} 清单。
 */
export async function buildRootManifest({ repoDir, root, deviceId = undefined, pageLimit = MANIFEST_PAGE_LIMIT, cursor = undefined }) {
  const warnings = []
  let rows = []
  if (root === 'conv') {
    const convAbs = join(repoDir, 'conv')
    let devices
    try {
      devices = await fs.readdir(convAbs, { withFileTypes: true })
    } catch {
      devices = []
    }
    for (const device of devices.sort((a, b) => a.name.localeCompare(b.name))) {
      if (!device.isDirectory()) continue
      const deviceAbs = join(convAbs, device.name)
      for (const session of (await fs.readdir(deviceAbs, { withFileTypes: true }).catch(() => [])).sort((a, b) => a.name.localeCompare(b.name))) {
        if (!session.isDirectory()) continue
        const sessionAbs = join(deviceAbs, session.name)
        let meta
        try {
          meta = JSON.parse(await fs.readFile(join(sessionAbs, 'meta.json'), 'utf8'))
        } catch {
          warnings.push(`conv/${device.name}/${session.name}: no readable meta.json, session skipped`)
          continue
        }
        const path = `conv/${device.name}/${session.name}/`
        rows.push([path, Number(meta.bytes) || 0, typeof meta.sha256 === 'string' ? meta.sha256 : ''])
      }
    }
  } else {
    for (const fileAbs of await listFiles(join(repoDir, root))) {
      const rel = relative(repoDir, fileAbs).split('\\').join('/')
      if (classifyPath(rel) === null) continue
      const { size, sha256 } = await shaAndSize(fileAbs)
      rows.push([rel, size, sha256])
    }
  }
  if (deviceId !== undefined && deviceId !== '') {
    rows = rows.filter(([path]) => maySend(path, deviceId))
  }
  rows.sort((left, right) => (canonicalPath(left[0]) ?? left[0]).localeCompare(canonicalPath(right[0]) ?? right[0]))
  const digest = digestOfEntries(rows)
  const start = cursor === undefined ? 0 : Math.max(0, Number(cursor) || 0)
  const slice = rows.slice(start, start + Math.max(1, pageLimit))
  const next = start + slice.length < rows.length ? String(start + slice.length) : undefined
  const manifest = { root, digest, entries: slice, warnings }
  if (next !== undefined) manifest.next = next
  return manifest
}

/**
 * 差分：远端清单里"本地没有"或"sha 不同"的条目。
 *
 * 集合行（以 `/` 结尾）原样返回成集合路径 —— 上层拿去发 `NEED('…/')`；普通行返回文件路径。
 * 路径按 `canonicalPath()` 比较（Windows 大小写归一），输出保留远端给的原样。
 * @param {object} input - {remoteEntries, local}。
 * @param {Array<[string, number, string]>} input.remoteEntries - 对端清单。
 * @param {Map<string, string>|Object<string, string>} input.local - 本地 `canonicalPath -> sha256`。
 * @returns {{need: string[], changed: string[], missing: string[]}} 差分。
 */
export function diffManifest({ remoteEntries, local }) {
  const localMap = local instanceof Map ? local : new Map(Object.entries(local ?? {}))
  const need = []
  const changed = []
  const missing = []
  for (const [path, _size, sha] of Array.isArray(remoteEntries) ? remoteEntries : []) {
    const key = canonicalPath(path)
    if (key === null) continue
    const mine = localMap.get(key)
    if (mine === undefined) {
      need.push(path)
      missing.push(path)
    } else if (String(mine) !== String(sha)) {
      need.push(path)
      changed.push(path)
    }
  }
  return { need, changed, missing }
}

/**
 * 本机某个根的 `canonicalPath -> sha256`（给 diffManifest 用；集合行键是目录路径）。
 * @param {object} input - {repoDir, root}。
 * @returns {Promise<Map<string, string>>} 映射。
 */
export async function localDigestMap({ repoDir, root }) {
  const manifest = await buildRootManifest({ repoDir, root, pageLimit: Number.MAX_SAFE_INTEGER })
  const map = new Map()
  for (const [path, _size, sha] of manifest.entries) {
    const key = canonicalPath(path)
    if (key !== null) map.set(key, sha)
  }
  return map
}

/**
 * HEADS 的缩写：把几个根的清单折成 `HEADS` 要的那几行。
 * @param {object} input - {manifests}。
 * @param {Array<{root: string, digest: string, entries: Array<[string, number, string]>}>} input.manifests - 各根清单。
 * @returns {{roots: Array<{root: string, seq: number, digest: string, files: number, bytes: number}>}} HEADS 体。
 */
export function headsOf({ manifests }) {
  const roots = (Array.isArray(manifests) ? manifests : []).map((manifest) => ({
    root: manifest.root,
    // seq 在这里就是条目数（协议这一版只拿它做"变没变"的旁证，真正的判据是 digest）
    seq: manifest.entries.length,
    digest: manifest.digest,
    files: manifest.entries.length,
    bytes: manifest.entries.reduce((total, entry) => total + (Number(entry[1]) || 0), 0),
  }))
  return { roots }
}
