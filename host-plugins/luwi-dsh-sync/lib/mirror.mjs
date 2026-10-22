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
import { createHash } from 'node:crypto'
import path from 'node:path'
import { FORK_NAME_RE, HOST_ARTIFACT_NAME_RE } from './constants.mjs'
import { isShadowWorkspaceRel, isStillOld, relBelongsToSession } from './luwi-baseline.mjs'
import {
  DEFAULT_CHUNK_MIN_BYTES,
  DEFAULT_PART_BYTES,
  PART_FILE_RE,
  PARTS_INDEX,
  PARTS_VERSION,
  partFileName,
  partsDirOf,
  readPartsIndex,
  splitSessionParts,
} from './luwi-parts.mjs'

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

/**
 * 镜像里那一份比本机这一份长吗 (只看大小; 读不动/不存在都算"不更长")。
 *
 * 会话日志是 append-only, **短的那一份是旧的**。本机那一份要是被截断/回退过, 覆写镜像等于把
 * 工作树里完整的那一份一起带走, 而取回只在"远端 head 变了"时才重新物化 (2026-10-21 手机上实测:
 * 一次截断之后本地与镜像双双停在半截, 远端那份 2.8 MB 再也没被取回来)
 * @param {string} target - 镜像里的那份。
 * @param {number} incoming - 本机这一份的字节数。
 * @returns {Promise<boolean>} 镜像里更长就 true。
 */
async function mirrorIsLonger(target, incoming) {
  try {
    return (await fs.stat(target)).size > incoming
  } catch {
    return false
  }
}

/** 回执里的排除表最多列这么多条（工具结果要进模型上下文，别把整棵树抄进去）。 */
const MAX_REPORTED_EXCLUDED = 50

/**
 * 递归收掉空目录 (镜像根自己不算, 失败一律忽略 —— 正被写或权限不对时留着也无害)。
 * @param {string} dir - 目录绝对路径。
 * @returns {Promise<void>} 无返回值。
 */
async function pruneEmptyDirs(dir) {
  let entries
  try {
    entries = await fs.readdir(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const entry of entries) {
    if (!entry.isDirectory() || entry.isSymbolicLink()) continue
    const absolute = path.join(dir, entry.name)
    await pruneEmptyDirs(absolute)
    try {
      if ((await fs.readdir(absolute)).length === 0) await fs.rmdir(absolute)
    } catch {
      // 非空 / 刚被写 / 权限不对: 留着
    }
  }
}

/**
 * 把一份会话日志按 zstd 帧边界切成段写进镜像 (Luwi 加 2026-10-22)。
 *
 * 为什么: PC 上只走 `api.github.com` 的 REST (github.com:443 不通), 那一路没有 delta, 会话文件
 * 改一个字节就重传整份 —— 实测每轮只长 60~80 KB, 却在重传 5 MB。切成段之后只有最末一段随
 * 追加变化, 前面那些段的 sha 不变, 推送按 sha 去重就不再传它们 (见 lib/luwi-parts.mjs 开头)
 * @param {object} deps - {rel, source, size, mirrorRoot, targets, partBytes, minBytes}。
 * @returns {Promise<{keptLonger: boolean, wrote: number}|null>} 不适用于切段时回 null (调用方整份写)。
 */
async function mirrorChunkedFile(deps) {
  if (deps.size <= deps.minBytes) return null
  if (!deps.rel.endsWith('.zstd')) return null
  let bytes
  try {
    bytes = await fs.readFile(deps.source)
  } catch {
    return null
  }
  const parts = splitSessionParts(bytes, deps.partBytes)
  if (parts === null) return null
  const dirRel = partsDirOf(deps.rel)
  const dirAbs = path.join(deps.mirrorRoot, ...dirRel.split('/'))
  // 段与索引先进 targets: 删除阶段清的是"源里已经没有"的东西, 这一趟写下的不能算没有
  deps.targets.add(`${dirRel}/${PARTS_INDEX}`)
  for (let at = 0; at < parts.length; at += 1) deps.targets.add(`${dirRel}/${partFileName(at)}`)
  // 镜像里已拼好的那份比本机这份长 = 本机是旧的/被截断过, 一个字节都不覆盖 (见 mirrorIsLonger)
  const existing = await readPartsIndex(dirAbs)
  if (existing !== null && Number(existing.bytes) > deps.size) {
    // 2026-10-22 复核补的一条: **这一组现有的段全算 targets**。本机源比镜像里那一组短的时候
    // (对端刚追加, 或取回刚落地还没写进本机会话库), 下面那个删除阶段会拿"本机源切出来的段名"
    // 去清工作树 —— 比本机源多出来的那几段会被当成"源里已经没有"收掉, 而下一趟取回还得重下。
    // 现有一律保留, 覆盖与删除两件事都不做
    try {
      for (const name of await fs.readdir(dirAbs)) {
        if (PART_FILE_RE.test(name)) deps.targets.add(`${dirRel}/${name}`)
      }
    } catch {
      // 目录刚被删/读不动: 按原样跳过, 下一趟取回补
    }
    return { keptLonger: true, wrote: 0 }
  }
  let wrote = 0
  for (let at = 0; at < parts.length; at += 1) {
    if (await writeIfDifferent(path.join(dirAbs, partFileName(at)), parts[at])) wrote += 1
  }
  const index = Buffer.from(`${JSON.stringify({
    v: PARTS_VERSION,
    file: path.posix.basename(deps.rel),
    bytes: bytes.length,
    parts: parts.length,
    // 段是逐段各自更新的, 取回端靠它认出"这一版段拼出来的还是不是同一条血脉" (见 assembleParts)
    sha256: createHash('sha256').update(bytes).digest('hex'),
  })}\n`)
  if (await writeIfDifferent(path.join(dirAbs, PARTS_INDEX), index)) wrote += 1
  return { keptLonger: false, wrote }
}

/**
 * 把 sessionRoot 镜像进 <repoDir>/<mirrorDir>/…。
 * 宿主私有会话产物（租约/迁移暂存，HOST_ARTIFACT_NAME_RE）既不复制、也不删除：
 * 它们是运行时状态，同步它们只会把某设备的瞬时状态变成另一侧的删除/噪音。
 *
 * Luwi 加的四个参数：
 * - `exclude`（G1-C 工作区镜面）：相对源根的前缀表，遍历时剪枝（见 makeExcludeMatcher）。被排除的
 *   路径不进 targets，所以工作树里它旧的副本会被删除阶段清掉 —— 排除是"撤回"，不是"冻结"。
 * - `maxFileBytes`：单文件上限（0/缺省 = 不限）。超限的文件**不复制、也不算删除**：
 *   targets 里记一笔，于是工作树里已有那一份原样留着（远端不会被我们悄悄删掉），
 *   结果里如实进 oversized。
 * - `baseline`（2026-10-20，旧对话不同步）：`{相对路径: 配对那一刻的 mtime}`。仍然算旧的
 *   文件**不复制、也不进 targets**（工作树里以前传上去的那一份会被删掉）；配对之后动过的
 *   （mtime 变新）自动放行 —— 判据只有 [isStillOld] 一处
 * - `neverSyncSessionIds`：本机的投影/影子会话 id。这些是"看对端那一场"的本地视图，传上去
 *   会让对端多出一场重复会话；同样不复制、不进 targets，旧的那一份顺手删掉
 * - `chunkSessionLogs`（2026-10-22）：会话日志按 zstd 帧边界切成段写（`<会话目录>/parts/`），
 *   于是追加只改最末一段，REST 那条没有 delta 的推送不再重传整份 —— 见 [mirrorChunkedFile]
 * @param {object} deps - {sessionRoot, repoDir, mirrorDir, forkNameRe?, exclude?, maxFileBytes?, baseline?, neverSyncSessionIds?, chunkSessionLogs?, chunkBytes?, chunkMinBytes?}。
 * @returns {Promise<{mirrored: number, unchanged: number, keptLonger: number, chunked: number, skippedLinks: string[], deleted: string[], forkedPreserved: string[], hostArtifactsSkipped: number, hostArtifactsPreserved: string[], excluded: string[], excludedCount: number, oversized: string[], oversizedCount: number, beforeBaseline: number, neverSynced: number}>}
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
  let keptLonger = 0
  let hostArtifactsSkipped = 0
  let beforeBaseline = 0
  let neverSynced = 0
  let chunked = 0
  const partBytes = Number.isFinite(deps.chunkBytes) && deps.chunkBytes > 0 ? deps.chunkBytes : DEFAULT_PART_BYTES
  const chunkMinBytes = Number.isFinite(deps.chunkMinBytes) && deps.chunkMinBytes >= 0
    ? deps.chunkMinBytes
    : DEFAULT_CHUNK_MIN_BYTES
  const oversized = []
  for (const file of source.files) {
    if (HOST_ARTIFACT_NAME_RE.test(path.posix.basename(file.rel))) {
      hostArtifactsSkipped += 1
      continue
    }
    if (relBelongsToSession(file.rel, deps.neverSyncSessionIds)) {
      // 本机投影会话: 不上传, 也不把以前误传的那一份留在工作树里
      neverSynced += 1
      continue
    }
    if (deps.neverSyncShadowSessions === true && isShadowWorkspaceRel(file.rel)) {
      // 影子工作区里开的会话 = 对端那一场的本地视图, 同上; 2026-10-20 晚补上这条判据,
      // 之前 PC 的 `dsh（手机）` 里那两场被当成新会话推到了远端
      neverSynced += 1
      continue
    }
    const info = await fs.stat(file.absolute)
    if (deps.baseline !== undefined && deps.baseline !== null && isStillOld(deps.baseline, file.rel, info.mtimeMs)) {
      // 配对之前就停住、之后没再动过: 不传, 工作树里旧的副本由删除阶段收掉
      beforeBaseline += 1
      continue
    }
    if (maxBytes > 0 && info.size > maxBytes) {
      // 用 stat 的 size 判断，绝不为了"看看多大"把文件读进内存
      oversized.push(file.rel)
      targets.add(file.rel)
      continue
    }
    // 会话日志切段 (2026-10-22): 只切多帧 zstd, 切不动就回 null 走下面原来的整份写
    if (deps.chunkSessionLogs === true) {
      const cut = await mirrorChunkedFile({
        rel: file.rel,
        source: file.absolute,
        size: info.size,
        mirrorRoot,
        targets,
        partBytes,
        minBytes: chunkMinBytes,
      })
      if (cut !== null) {
        chunked += 1
        if (cut.keptLonger) {
          keptLonger += 1
          continue
        }
        if (cut.wrote > 0) mirrored += 1
        else unchanged += 1
        continue
      }
    }
    const target = path.join(mirrorRoot, ...file.rel.split('/'))
    targets.add(file.rel)
    // 会话日志只往后长: 本机这一份比镜像里那份短 = 本机是旧的/被截断的, **不覆盖**
    // (见 mirrorIsLonger; 覆盖之后取回那条路要靠"远端 head 变了"才会再物化一次)
    if (await mirrorIsLonger(target, info.size)) {
      keptLonger += 1
      continue
    }
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
  // 删完之后收掉空目录 (git 不跟踪目录, 留着只会在工作树里攒 `parts/` 这样的空壳)
  if (deleted.length > 0) await pruneEmptyDirs(mirrorRoot)

  return {
    mirrored,
    unchanged,
    keptLonger,
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
    // Luwi 加 (2026-10-20): 旧对话与投影会话两边各自的计数
    beforeBaseline,
    neverSynced,
    // Luwi 加 (2026-10-22): 这一趟按帧切段写的会话文件数
    chunked,
  }
}

/**
 * 确保设备身份文件存在（**只在没有时写**；返回是否写入）。
 *
 * 两台设备各有自己的 id, 都往同一个 `device.txt` 写就会**每轮互相覆盖**: 每一次 pull 都判
 * DIVERGED, 每轮长一个 fork (2026-10-21 清理时那 11 个 device.txt 的 fork 就是这么来的)。
 * 现在这份文件只当"仓库的创建者"记一次, 本机 id 从元数据来 (`meta.deviceId`)。
 * @param {string} repoDir - 同步仓库根。
 * @param {string} deviceFile - 文件名（repoDir 直下）。
 * @param {string} deviceId - 设备 id。
 * @returns {Promise<boolean>} 是否写入。
 */
export async function ensureDeviceFile(repoDir, deviceFile, deviceId) {
  const target = path.join(repoDir, deviceFile)
  const content = Buffer.from(`${deviceId}\n`)
  try {
    const info = await fs.stat(target)
    if (info.isFile() && info.size > 0) return false
  } catch {
    // 没有 → 照写
  }
  return writeIfDifferent(target, content)
}
