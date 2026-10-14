// Luwi 改了什么 (对照上游 v0.2.24): 多了排除表与单文件上限 (G1-C 工作区镜面要的) ——
// deps.exclude 里的前缀在**遍历时就剪掉**(工作区那根不剪 `.lw-sync` 就是递归镜像), 超过
// deps.maxFileBytes 的文件如实记进 oversized 而不是假装同步。不传这两个参数时行为与上游一致
//
// lib/mirror.mjs — 会话目录 → git 工作树的字节镜像（node:fs；零 DSH 依赖）。
//
// 会话文件一律按不透明字节复制（JSONL/zstd 等物理编码属于宿主，插件不解析）。
// 安全边界：
// - 绝不跟随符号链接（源或目标为链接一律跳过并报告，杜绝写出根外）；
// - 删除仅限工作树内、源已不存在、且非 fork 文件、非宿主私有产物的常规文件；
// - fork 文件（FORK_NAME_RE）永不复制、永不删除——冲突双方字节的持久载体；
// - 宿主私有会话产物（HOST_ARTIFACT_NAME_RE：session.lock / 迁移暂存）同样
//   永不复制、永不删除——运行时状态不属于可同步内容；
// - Luwi 加：排除表里的前缀（Luwi D：`.lw-sync` / `.git` / 用户自配）既不复制也不
//   进入"该不该删"的账 —— 所以一条排除加进去之后，工作树里那份旧的会被下一次镜像删掉。

import { promises as fs } from 'node:fs'
import path from 'node:path'
import { FORK_NAME_RE, HOST_ARTIFACT_NAME_RE } from './constants.mjs'

/**
 * 排除表 → 匹配器（纯函数）。
 *
 * 条目写成相对源根的 POSIX 前缀：命中 `<条目>` 本身或它底下的一切（`<条目>/…`）。
 * 前导 `./` 与结尾 `/` 都规整掉，`..` 与绝对路径条目直接丢掉（越界条目没有意义）。
 * 空表 → 恒 false。
 * @param {string[]|undefined} exclude - 排除表。
 * @returns {(rel: string) => boolean} 相对路径是否被排除。
 */
export function makeExcludeMatcher(exclude) {
  const entries = []
  for (const raw of exclude ?? []) {
    if (typeof raw !== 'string') continue
    const trimmed = raw.trim().replace(/^\.\//u, '').replace(/\/+$/u, '')
    if (trimmed === '' || trimmed === '.' || trimmed.startsWith('/') || trimmed.includes('\\')) continue
    if (trimmed.split('/').some(segment => segment === '..' || segment === '')) continue
    if (!entries.includes(trimmed)) entries.push(trimmed)
  }
  if (entries.length === 0) return () => false
  return (rel) => {
    for (const entry of entries) {
      if (rel === entry || rel.startsWith(`${entry}/`)) return true
    }
    return false
  }
}

/**
 * 递归枚举 root 下全部常规文件（POSIX 相对路径；符号链接跳过并回报）。
 *
 * 被排除的目录**不下钻**（工作区那根要靠这一条不走进自己的窝），命中的路径原样记进
 * excluded 供回执与状态展示。
 * @param {string} root - 源根（绝对路径）。
 * @param {(rel: string) => boolean} [isExcluded] - 排除表匹配器。
 * @returns {Promise<{files: Array<{absolute: string, rel: string}>, skipped: string[], excluded: string[]}>}
 */
export async function listRelFiles(root, isExcluded) {
  const files = []
  const skipped = []
  const excluded = []
  const walk = async (dir, rel) => {
    const entries = await fs.readdir(dir, { withFileTypes: true })
    for (const entry of entries) {
      const absolute = path.join(dir, entry.name)
      const next = rel === '' ? entry.name : `${rel}/${entry.name}`
      if (entry.isSymbolicLink()) {
        skipped.push(absolute)
        continue
      }
      if (isExcluded !== undefined && isExcluded(next)) {
        excluded.push(next)
        continue
      }
      if (entry.isDirectory()) {
        await walk(absolute, next)
        continue
      }
      if (entry.isFile()) {
        files.push({ absolute, rel: next })
      }
    }
  }
  await walk(root, '')
  return { files, skipped, excluded }
}

/** 递归枚举 root 下全部常规文件（旧调用面：只给路径与链接跳过）。 */
async function listFiles(root, isExcluded) {
  const walked = await listRelFiles(root, isExcluded)
  return { files: walked.files, skipped: walked.skipped, excluded: walked.excluded }
}

/** 内容一致则跳过写（返回 false），否则覆写（返回 true）。 */
async function writeIfDifferent(target, content) {
  try {
    const existing = await fs.readFile(target)
    if (existing.equals(content)) return false
  } catch {
    // 目标不存在 → 照写。
  }
  await fs.mkdir(path.dirname(target), { recursive: true })
  await fs.writeFile(target, content)
  return true
}

/** 回执里的排除表最多列这么多条（工具结果要进模型上下文，别把整棵树抄进去）。 */
const MAX_REPORTED_EXCLUDED = 50

/**
 * 把 sessionRoot 镜像进 <repoDir>/<mirrorDir>/…。
 * 宿主私有会话产物（租约/迁移暂存，HOST_ARTIFACT_NAME_RE）既不复制、也不删除：
 * 它们是运行时状态，同步它们只会把某设备的瞬时状态变成另一侧的删除/噪音。
 *
 * Luwi 加的两个参数（G1-C 工作区镜面）：
 * - `exclude`：相对源根的前缀表，遍历时剪枝（见 makeExcludeMatcher）。被排除的路径
 *   不进 targets，所以工作树里它旧的副本会被删除阶段清掉 —— 排除是"撤回"，不是"冻结"。
 * - `maxFileBytes`：单文件上限（0/缺省 = 不限）。超限的文件**不复制、也不算删除**：
 *   targets 里记一笔，于是工作树里已有那一份原样留着（远端不会被我们悄悄删掉），
 *   结果里如实进 oversized。
 * @param {object} deps - {sessionRoot, repoDir, mirrorDir, forkNameRe?, exclude?, maxFileBytes?}。
 * @returns {Promise<{mirrored: number, unchanged: number, skippedLinks: string[], deleted: string[], forkedPreserved: string[], hostArtifactsSkipped: number, hostArtifactsPreserved: string[], excluded: string[], excludedCount: number, oversized: string[], oversizedCount: number}>}
 */
export async function mirrorSessionRoot(deps) {
  const forkRe = deps.forkNameRe ?? FORK_NAME_RE
  const isExcluded = makeExcludeMatcher(deps.exclude)
  const maxBytes = Number.isFinite(deps.maxFileBytes) && deps.maxFileBytes > 0 ? deps.maxFileBytes : 0
  const source = await listFiles(deps.sessionRoot, isExcluded)
  const mirrorRoot = path.join(deps.repoDir, deps.mirrorDir)
  const targets = new Set()

  let mirrored = 0
  let unchanged = 0
  let hostArtifactsSkipped = 0
  const oversized = []
  for (const file of source.files) {
    if (HOST_ARTIFACT_NAME_RE.test(path.posix.basename(file.rel))) {
      hostArtifactsSkipped += 1
      continue
    }
    if (maxBytes > 0 && (await fs.stat(file.absolute)).size > maxBytes) {
      // 用 stat 的 size 判断，绝不为了"看看多大"把文件读进内存
      oversized.push(file.rel)
      targets.add(file.rel)
      continue
    }
    const target = path.join(mirrorRoot, ...file.rel.split('/'))
    targets.add(file.rel)
    const wrote = await writeIfDifferent(target, await fs.readFile(file.absolute))
    if (wrote) mirrored += 1
    else unchanged += 1
  }

  // 删除：目标树中源已不存在、且非 fork 命名、非宿主私有产物的文件。
  const deleted = []
  const forkedPreserved = []
  const hostArtifactsPreserved = []
  const targetFiles = []
  try {
    const walk = async (dir) => {
      const entries = await fs.readdir(dir, { withFileTypes: true })
      for (const entry of entries) {
        const absolute = path.join(dir, entry.name)
        if (entry.isSymbolicLink()) continue
        if (entry.isDirectory()) {
          await walk(absolute)
          continue
        }
        if (entry.isFile()) targetFiles.push(absolute)
      }
    }
    await walk(mirrorRoot)
  } catch {
    // 镜像根尚不存在 → 无删除可做。
  }
  for (const absolute of targetFiles) {
    const rel = path.relative(mirrorRoot, absolute).split(path.sep).join('/')
    const basename = path.posix.basename(rel)
    if (forkRe.test(basename)) {
      forkedPreserved.push(rel)
      continue
    }
    if (HOST_ARTIFACT_NAME_RE.test(basename)) {
      hostArtifactsPreserved.push(rel)
      continue
    }
    if (!targets.has(rel)) {
      await fs.unlink(absolute)
      deleted.push(rel)
    }
  }

  return {
    mirrored,
    unchanged,
    skippedLinks: source.skipped,
    deleted,
    forkedPreserved,
    hostArtifactsSkipped,
    hostArtifactsPreserved,
    // Luwi 加：排除表与单文件上限的读数（截断只影响展示，计数照实）
    excluded: source.excluded.slice(0, MAX_REPORTED_EXCLUDED),
    excludedCount: source.excluded.length,
    oversized: oversized.slice(0, MAX_REPORTED_EXCLUDED),
    oversizedCount: oversized.length,
  }
}

/**
 * 确保设备身份文件内容为 deviceId（不同才写；返回是否写入）。
 * @param {string} repoDir - 同步仓库根。
 * @param {string} deviceFile - 文件名（repoDir 直下）。
 * @param {string} deviceId - 设备 id。
 * @returns {Promise<boolean>} 是否写入。
 */
export async function ensureDeviceFile(repoDir, deviceFile, deviceId) {
  const target = path.join(repoDir, deviceFile)
  const content = Buffer.from(`${deviceId}\n`)
  return writeIfDifferent(target, content)
}
