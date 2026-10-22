// lib/luwi-adopt.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// 为什么要有它 (2026-10-13 实测挖到的上游缺口): 上游的镜像只有单程 —— sessionRoot →
// 工作树 (外加删除传播); pull 只把远端内容合并进**工作树**, 没有任何一步把它们取回
// sessionRoot (test/engine.test.mjs 的断言也只查工作树)。真机上的表现: 另一台设备推来的
// 会话在本地 dsh 的会话列表里不会出现; 更糟的是, 下一次镜像会把工作树里那些"本地还没有"
// 的文件当陈旧副本删掉 (实测: B pull 后一跑 status, 文件就从工作树消失)。
//
// 两个模式 (G1-C 工作区镜面加的第二档):
//
// - `bring-missing` (缺省, 会话那根): 只把**本地还没有**的文件按字节取回来, 本地已有的
//   一律不碰 —— 会话日志是 append-only, 本地那一份是本地血脉, 冲突的远端版本早已由 merge
//   存成 fork 文件。与上游"两边保留"的语义一致。
// - `mirror` (工作区那根): 工作区文件不是 append-only, 一台改一台看的场景要求**本地那份
//   真的跟着远端变**。规则是"合并结果说了算" ——
//     工作树有 / 本地没有            → 取回 (brought)
//     工作树有 / 本地有但字节不同    → 用工作树那一份覆盖 (updated)
//     工作树没有 / 本地有            → 搬进回收站 (trashed)
//   第三条不是想当然: pull 的第一步 (checkpoint) 已经把本地每个文件镜像进了工作树, 所以
//   "合并之后工作树里没有"只可能是**这一趟合并把它删掉了** (远端删了、本地又没改过);
//   本地改过、远端删掉的那一支在 merge.mjs 里判成 diverged, 结果保留 ours, 文件还在工作树
//   里, 于是这一条碰不到它 (两边保留)。第二条之所以安全, 是因为 merge 只在"本地相对基点是
//   原样"时才采纳 theirs, 真实分歧一律保留 ours + fork —— 于是"工作树 != 本地"这件事本身
//   就是"本地该更新了"。覆盖掉的旧字节不丢: pull 的第一步那个 checkpoint 提交里就是它。
//
//   还有一条**必须由调用方证明**的前置: 被搬走的那条路径得是"上一趟镜像真的交出去过"的
//   (deps.wasMirrored) —— 镜像跑完到取回之间本地新写的文件同样不在工作树里, 但它从来没被
//   同步过, 拿它当"远端把它删了"就是把用户刚写的东西扔进回收站 (手机上一次 pull 是十几秒)。
//
// 无论哪个模式: fork 文件与宿主私有产物都不取、不删; 单文件失败记下来继续 (决不把一次成功
// 的 pull 变成失败); 被删的本地文件走 rename 进回收站 (`.lw-sync/trash/<UTC 戳>/…`), 不 unlink。

import { promises as fs } from 'node:fs'
import path from 'node:path'
import { FORK_NAME_RE, HOST_ARTIFACT_NAME_RE } from './constants.mjs'
import { isShadowWorkspaceRel } from './luwi-baseline.mjs'
import { listRelFiles, makeExcludeMatcher } from './mirror.mjs'
import { PARTS_INDEX, assembleParts, inspectPartsSet, isPartFileRel, isPartsIndexRel } from './luwi-parts.mjs'

/**
 * 上一次取回成功时那条会话的段版本 (进程内): 段目录绝对路径 → `bytes:parts:sha256`。
 *
 * 只为一件事存在: `assembleParts` 要把整份段读进内存拼一遍, 而 pull 是每分钟一趟 ——
 * 一场 16 MB 的会话加上几场几 MB 的, 就是每分钟把镜像整棵树读一遍, 而队列里的那几句话
 * 还排在取回后面。索引没变、本地文件大小也对得上时直接跳过拼装
 */
const partsApplied = new Map()

/** 索引的版本指纹 (取回端只关心这三样)。 */
function fingerprintOf(index) {
  return `${index.bytes}:${index.parts}:${typeof index.sha256 === 'string' ? index.sha256 : ''}`
}

/** 目标是否已存在 (lstat: 坏掉的符号链接也算占位, 不去覆盖别人的东西) */
async function exists(target) {
  try {
    await fs.lstat(target)
    return true
  } catch {
    return false
  }
}

/** 目标是不是一个常规文件 (目录/符号链接都不算) */
async function isRegularFile(target) {
  try {
    return (await fs.lstat(target)).isFile()
  } catch {
    return false
  }
}

/** 单文件字节: 读不动就 undefined (调用方按 fail-soft 处理) */
async function readIfPossible(target) {
  try {
    return await fs.readFile(target)
  } catch {
    return undefined
  }
}

/** 本地根内的相对路径 → 绝对路径 (越界一律 undefined, 绝不往根外写) */
function underRoot(root, rel) {
  const segments = rel.split('/')
  if (segments.some(segment => segment === '' || segment === '.' || segment === '..')) return undefined
  return path.join(root, ...segments)
}

/** 原子落盘: 临时文件 + rename (半个文件不会被 dsh 看见; FUSE 上 rename 可用) */
async function writeAtomic(target, bytes) {
  await fs.mkdir(path.dirname(target), { recursive: true })
  const tmp = `${target}.lw-adopt-${process.pid}-${Date.now()}.tmp`
  await fs.writeFile(tmp, bytes)
  await fs.rename(tmp, target)
}

/** UTC 14 位戳 (与 fork 文件名同一个口径, 见 engine.mjs 的冲突时间戳) */
function utcStamp(now) {
  return now.toISOString().replace(/[-:T]/gu, '').slice(0, 14)
}

/**
 * 把工作树里的镜像文件取回 (或同步回) 本地根。
 * @param {object} deps 依赖。
 * @param {string} [deps.sessionRoot] 本地根 (旧调用名)。
 * @param {string} [deps.targetRoot] 本地根 (新调用名; 两个都给时以它为准)。
 * @param {string} deps.repoDir 同步仓库工作树根。
 * @param {string} deps.mirrorDir 工作树里的镜像目录名。
 * @param {'bring-missing'|'mirror'} [deps.mode] 取回模式 (缺省 bring-missing)。
 * @param {string[]} [deps.exclude] 排除表 (与镜像那份必须是同一张, 否则会把排除掉的东西搬走)。
 * @param {number} [deps.maxFileBytes] 单文件上限 (超限的本地文件不覆盖也不搬走, 与镜像同一口径)。
 * @param {string} [deps.trashDir] 回收站根 (mirror 模式下删本地文件要它)。
 * @param {(rel: string) => Promise<boolean>} [deps.wasMirrored] mirror 模式的删除前提: 这条路径上一趟镜像交出去过吗 (缺省 = 不删, 只取回)。
 * @param {boolean} [deps.skipShadowSessions] 影子工作区里的会话不取回 (Luwi 加 2026-10-20 晚:
 *   它们是"对端那一场"在本机的投影, 取回来只会多出一场 cwd 指向别处的重复对话)。
 * @param {(rel: string) => Promise<boolean>} [deps.skipOld] 会话基线里仍然算旧的那些不取回 (Luwi 加
 *   2026-10-21: 首次清理不再挡在取回前面 —— 判断挪到这一层, 只对镜像里真有的那几条量一次 mtime;
 *   源文件已经不在了也算旧, 于是"本机删掉的老会话"不会被镜像又搬回来)。
 * @param {Date} [deps.now] 时间戳 (测试注入)。
 * @returns {Promise<{brought: number, updated: number, trashed: number, skipped: number, deferred: number, deferredSets: string[], files: string[], updatedFiles: string[], trashedFiles: string[], skippedFiles: string[], failed: string[], warnings: string[]}>} 取回/更新/搬走/跳过(old)的清单与失败清单 (POSIX 相对路径); `deferred` = 这一趟还没落齐、故意没动的段集合数。
 */
export async function adoptMergedSessions(deps) {
  const targetRoot = deps.targetRoot ?? deps.sessionRoot
  if (typeof targetRoot !== 'string' || targetRoot === '') {
    throw new Error('adopt needs a local root (targetRoot)')
  }
  const mode = deps.mode === 'mirror' ? 'mirror' : 'bring-missing'
  const isExcluded = makeExcludeMatcher(deps.exclude)
  const maxBytes = Number.isFinite(deps.maxFileBytes) && deps.maxFileBytes > 0 ? deps.maxFileBytes : 0
  const mirrorRoot = path.join(deps.repoDir, deps.mirrorDir)
  const brought = []
  const updated = []
  const trashed = []
  const skippedOld = []
  const failed = []
  const warnings = []
  /** 还没落齐的段集合 (下一趟 pull 再拼): 这不是失败, 只是"这一趟不该动它" */
  const deferredSets = []

  const walked = await listRelFiles(mirrorRoot)
  const carried = new Set()

  /**
   * 把一份字节取回本机 (mirror 模式下按合并结果覆盖), 只回归类不落账。
   * @param {string} rel - 相对路径 (POSIX)。
   * @param {Buffer} bytes - 要落盘的字节。
   * @returns {Promise<'brought'|'updated'|'kept'|'failed'>} 归类。
   */
  const adoptOne = async (rel, bytes) => {
    const target = underRoot(targetRoot, rel)
    if (target === undefined) return 'failed'
    try {
      if (!(await exists(target))) {
        await writeAtomic(target, bytes)
        return 'brought'
      }
      // 本地已经有了: bring-missing 只在"远端那一份更长"时更新
      // (会话日志 append-only; 手机取回 PC 的会话后, PC 继续聊, 本地那一份必须跟上,
      // 否则手机上永远停在第一次取回的那一段 —— 2026-10-20 晚真机上量到的)
      if (mode !== 'mirror') {
        if (!(await isRegularFile(target))) return 'kept'
        if (maxBytes > 0 && (await fs.stat(target)).size > maxBytes) return 'kept'
        const localBytes = await readIfPossible(target)
        if (localBytes === undefined) return 'failed'
        // 更长的那一份就是更完整的那一份: 不要求前缀 (两端各自补过一小段也照更长的来)
        if (bytes.length > localBytes.length) {
          await writeAtomic(target, bytes)
          return 'updated'
        }
        return 'kept'
      }
      if (!(await isRegularFile(target))) return 'kept'
      if (maxBytes > 0 && (await fs.stat(target)).size > maxBytes) return 'kept'
      const localBytes = await readIfPossible(target)
      if (localBytes !== undefined && localBytes.equals(bytes)) return 'kept'
      await writeAtomic(target, bytes)
      return 'updated'
    } catch {
      return 'failed'
    }
  }

  /** 一条结果归到对应的清单里。 */
  const record = (rel, outcome) => {
    if (outcome === 'brought') brought.push(rel)
    else if (outcome === 'updated') updated.push(rel)
    else if (outcome === 'failed') failed.push(rel)
  }

  // 切段存的会话 (2026-10-22): 段目录里有 index.json 的, 那里装的是一份会话而不是一堆文件,
  // 段本身没有意义 —— 先按索引拼回整份, 再当一条会话取回 (见 lib/luwi-parts.mjs)
  const partDirs = new Map()
  for (const file of walked.files) {
    if (!isPartsIndexRel(file.rel)) continue
    const dirRel = file.rel.slice(0, file.rel.lastIndexOf('/'))
    partDirs.set(dirRel, path.join(mirrorRoot, ...dirRel.split('/')))
  }
  /** 这条路径是不是落在某个段目录里 (那些由下面那一轮整体处理) */
  const insideParts = (rel) => {
    for (const dirRel of partDirs.keys()) if (rel.startsWith(`${dirRel}/`)) return true
    return false
  }

  /**
   * 这条会话是不是"上一次取回之后一点没变" (段版本指纹相同 + 本地那份大小对得上)。
   * @param {string} dirAbs - 段目录绝对路径。
   * @param {string} rel - 会话文件相对路径。
   * @param {object} index - 段索引。
   * @returns {Promise<boolean>} 可以跳过拼装就 true。
   */
  const alreadyApplied = async (dirAbs, rel, index) => {
    if (partsApplied.get(dirAbs) !== fingerprintOf(index)) return false
    const target = underRoot(targetRoot, rel)
    if (target === undefined) return false
    try {
      const info = await fs.stat(target)
      return info.isFile() && info.size === index.bytes
    } catch {
      return false
    }
  }

  for (const file of walked.files) {
    const basename = path.posix.basename(file.rel)
    if (FORK_NAME_RE.test(basename)) continue
    if (HOST_ARTIFACT_NAME_RE.test(basename)) continue
    if (insideParts(file.rel)) continue
    // 没有索引的段目录同样一个字都不取: 段离开索引没有意义, 当普通文件落进会话库只会多出
    // 一个 `parts/0004.bin` (2026-10-22 复查时发现的洞, 与"半组不拼"同一条)
    if (isPartFileRel(file.rel)) continue
    if (isExcluded(file.rel)) continue
    if (deps.skipShadowSessions === true && isShadowWorkspaceRel(file.rel)) continue
    if (typeof deps.skipOld === 'function') {
      let old = false
      try {
        old = (await deps.skipOld(file.rel)) === true
      } catch {
        // 量不出来就当成"不知道": 不往本机写, 也不算失败 (下一次 pull 还会再列一次)
        skippedOld.push(file.rel)
        continue
      }
      if (old) {
        skippedOld.push(file.rel)
        continue
      }
    }
    carried.add(file.rel)
    const bytes = await readIfPossible(file.absolute)
    if (bytes === undefined) {
      failed.push(file.rel)
      continue
    }
    record(file.rel, await adoptOne(file.rel, bytes))
  }

  for (const [dirRel, dirAbs] of partDirs) {
    const state = await inspectPartsSet(dirAbs)
    const index = state.index
    if (index === null) {
      failed.push(`${dirRel}/${PARTS_INDEX}`)
      continue
    }
    const rel = `${dirRel.slice(0, dirRel.lastIndexOf('/'))}/${index.file}`
    if (isExcluded(rel)) continue
    if (deps.skipShadowSessions === true && isShadowWorkspaceRel(rel)) continue
    if (typeof deps.skipOld === 'function') {
      let old = false
      try {
        old = (await deps.skipOld(rel)) === true
      } catch {
        skippedOld.push(rel)
        continue
      }
      if (old) {
        skippedOld.push(rel)
        continue
      }
    }
    // 索引没变、本地那份也还是上次取回的大小 → 不必把整份段再拼一遍。
    // 这一步是"读放大"的闸: 16 MB 的会话每分钟拼一次, 与 1 分钟的拉取间隔叠起来就是
    // 每分钟把镜像整棵树读一遍, 队列里的那几句话还排在它后面
    if (await alreadyApplied(dirAbs, rel, index)) {
      carried.add(rel)
      continue
    }
    // 半组不拼 (2026-10-22): 拉取端整组落, 所以半组只可能来自网络中断或对端还在写。
    // 这一趟留着不动, 下一趟接着搬 —— 记成 deferred 而不是 failed: 它既不是错误, 也不该
    // 每趟在 warnings 里喊一句
    if (state.complete !== true) {
      deferredSets.push(dirRel)
      continue
    }
    const assembled = await assembleParts(dirAbs)
    if (assembled === null) {
      // 段齐了却拼不出索引说的那份 = 两条血脉的段混在一组里, 或字节被改过;
      // 这一份谁也不认, 让下一趟镜像用本地源重新切段
      failed.push(`${dirRel}/${PARTS_INDEX}`)
      continue
    }
    carried.add(rel)
    const outcome = await adoptOne(rel, assembled.bytes)
    if (outcome !== 'failed') partsApplied.set(dirAbs, fingerprintOf(index))
    record(rel, outcome)
  }

  if (mode === 'mirror') {
    // 工作树里的符号链接算"还在" (镜像不复制链接, 但也不该因为它没被复制就把本地那份搬走)
    for (const absolute of walked.skipped) {
      carried.add(path.relative(mirrorRoot, absolute).split(path.sep).join('/'))
    }
    if (typeof deps.trashDir !== 'string' || deps.trashDir === '') {
      warnings.push('deletions were requested but no trash directory is configured, so nothing was moved')
    } else if (typeof deps.wasMirrored !== 'function') {
      warnings.push('deletions were requested without a way to prove what the mirror carried before, so nothing was moved')
    } else {
      const stamp = utcStamp(deps.now ?? new Date())
      const trashRoot = path.join(deps.trashDir, stamp)
      const local = await listRelFiles(targetRoot, isExcluded)
      for (const file of local.files) {
        if (carried.has(file.rel)) continue
        const basename = path.posix.basename(file.rel)
        if (FORK_NAME_RE.test(basename)) continue
        if (HOST_ARTIFACT_NAME_RE.test(basename)) continue
        if (deps.skipShadowSessions === true && isShadowWorkspaceRel(file.rel)) continue
        if (maxBytes > 0 && (await fs.stat(file.absolute)).size > maxBytes) continue
        // 只有"这一趟合并真的删掉了它"才搬: 上一趟镜像交出去过 (在 pull 的 checkpoint 提交
        // 里) 而现在工作树里没有。镜像跑完才写下的本地文件两条都不满足, 于是原地不动
        try {
          if (!(await deps.wasMirrored(file.rel))) continue
        } catch {
          failed.push(file.rel)
          continue
        }
        const destination = underRoot(trashRoot, file.rel)
        if (destination === undefined) {
          failed.push(file.rel)
          continue
        }
        try {
          await fs.mkdir(path.dirname(destination), { recursive: true })
          await fs.rename(file.absolute, destination)
          trashed.push(file.rel)
        } catch {
          failed.push(file.rel)
        }
      }
    }
  }

  return {
    brought: brought.length,
    updated: updated.length,
    trashed: trashed.length,
    skipped: skippedOld.length,
    // 段集合那一路的读数: 还没落齐、这一趟故意没动的组 (下一趟 pull 接着搬)
    deferred: deferredSets.length,
    deferredSets,
    files: brought,
    updatedFiles: updated,
    trashedFiles: trashed,
    skippedFiles: skippedOld,
    failed,
    warnings,
  }
}
