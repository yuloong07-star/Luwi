// Luwi 改了什么 (对照上游 v0.2.24):
// 1. pull 合并提交之后多一步"取回" —— 调 deps.adoptRemote() 把远端带来的新会话写回本地
//    sessionRoot (上游只把远端内容合并进工作树, 本地 dsh 看不见, 且下一次镜像会把它当陈旧
//    副本删掉; 见 lib/luwi-adopt.mjs)。不传这个依赖时行为与上游一致
// 2. 多根 (G1-C 工作区镜面): mirrorDir / deviceFile / exclude / maxFileBytes 四个可选依赖,
//    引擎本身不认识"会话"与"工作区" —— 同一个类实例化两次就是两根镜像。缺省值全是上游的
// 3. 冲突解决对"缺一边"的路径兜底: 单侧删除 (远端删了本地改了 / 本地删了远端改了) 在
//    git 里是 modify/delete 冲突, 只有一边有 stage —— 上游那条 `checkout --theirs/--ours`
//    在这两种路径上会整条失败 (fatal: path ... does not have their version), 而工作区镜面
//    里删文件是常事。现在: 要 fork 的那一边不存在就不 fork, 要保留的那一边不存在就把它删掉
// 4. 可选依赖 writePeerCard (G1-D 互为远端): 每次镜像前把自己的名片写进 peers/ (缺省不写,
//    只有会话那根要), 名片跟会话坐同一次提交过去; 见 lib/luwi-peers.mjs
//
// lib/engine.mjs — 同步编排（业务层；DSH 服务经回调注入，本模块零 DSH 依赖）。
//
// 操作语义：
// - push：镜像 → 提交 → push；被拒（远端领先）→ fetch + 拉取调和 → 重推一次；
//   绝不强推。
// - pull：先镜像 + 提交本地（保证合并基点是已提交状态）→ fetch → 三分支合并
//   （lib/merge.mjs 的 append-only 语义）→ 合并提交。冲突双方字节永不丢弃：
//   本地保留原路径，远端留存为 fork 文件。
// - 全部操作经 git.schedule 串行（同一仓库互不穿插）。

import { promises as fs } from 'node:fs'
import path from 'node:path'
import { MERGE_KINDS, MIRROR_DIR, DEVICE_FILE } from './constants.mjs'
import { planMerge } from './merge.mjs'
import { mirrorSessionRoot, ensureDeviceFile } from './mirror.mjs'
import { collectStatus } from './status.mjs'
import { forkFileName } from './paths.mjs'
import { messageOf, SyncError } from './errors.mjs'
import { redactText, sanitizeRemote } from './sanitize.mjs'

/**
 * 同步引擎。
 * @typedef {object} EngineDeps
 * @property {import('./git.mjs').GitBackend} git - git 后端。
 * @property {string} sessionRoot - 会话存储根（绝对）。
 * @property {string} repoDir - 同步仓库根（绝对）。
 * @property {string} remote - 远端地址原文（展示时 sanitize）。
 * @property {string} branch - 分支名。
 * @property {string} deviceId - 设备 id（fork 归属）。
 * @property {(patch: object) => void} reportMeta - 元数据补丁回调（index.mjs 写领域）。
 * @property {(message: string) => void} reportError - 最近错误回调（写领域 lastError）。
 * @property {(forkRelPaths: string[]) => void} onForks - fork 文件产生回调（index.mjs 做会话级 fork + 事件）。
 * @property {() => Promise<{brought: number, files?: string[], failed?: string[]}>} [adoptRemote] - Luwi 加：合并后把远端带来的新会话取回本地 sessionRoot（缺省时不取，行为与上游一致）。
 * @property {string} [mirrorDir] - Luwi 加：工作树里的镜像目录名（缺省 MIRROR_DIR='sessions'；工作区那根是 'workspace'）。
 * @property {string|null} [deviceFile] - Luwi 加：设备身份文件名（缺省 DEVICE_FILE='device.txt'；null = 不写，工作区那根不需要它）。
 * @property {string[]} [exclude] - Luwi 加：相对源根的排除前缀表（缺省空 = 不过滤，与上游一致）。
 * @property {number} [maxFileBytes] - Luwi 加：单文件上限（缺省 0 = 不限）。
 * @property {string} [label] - Luwi 加：日志里的那根叫什么（'sessions' / 'workspace'），只影响措辞。
 * @property {() => Promise<boolean>} [writePeerCard] - Luwi 加 (G1-D)：镜像前把自己的名片写进 peers/
 *   （缺省不写 —— 工作区那根没有名片，只有会话那根要）。fail-soft，写失败不拦同步。
 * @property {{debug: Function, info: Function, warn: Function, error: Function}} logger - 日志。
 */
export class SyncEngine {
  /** @type {EngineDeps} */
  #deps

  /**
   * Luwi 加 (G1-2 对端): pull 合并之后的第二笔活, 由 index.mjs 挂上来。
   *
   * 与 `deps.adoptRemote` 分开是因为它**不是取回会话**, 而是"把对端投在仓库队列里的那几句话
   * 收进来" (lib/luwi-peer-runtime.mjs 的 consumeInbox: 投递 + 写回执)。fail-soft: 它抛了
   * 也不影响这一次 pull 的结果, 只记一行 warn
   * @type {((context: {head: string|undefined, remoteHead: string, brought: number, updated: number}) => Promise<unknown>)|undefined}
   */
  afterPull

  /** @param {EngineDeps} deps - 依赖。 */
  constructor(deps) {
    this.#deps = deps
  }

  /** 设备 id（fork 文件名后缀；领域就绪后由 index.mjs 补齐）。 */
  set deviceId(value) {
    this.#deps.deviceId = value
  }

  /**
   * 设备 id 的读面。**必须有这一对**：只有 setter 时 `engine.deviceId` 读出来是 `undefined`,
   * 而 index.mjs 那边按 `=== 'pending' ? '' : …` 判空 —— `undefined` 会漏过那道闸, 于是
   * `/peer/info` 的 `device.id` 一直是空串, 对端的影子工作区与投影会话一台都建不起来
   * (refreshPeerProxies 遇到空 id 直接返回)。2026-10-14 真机上就是这个症状。
   */
  get deviceId() {
    return this.#deps.deviceId
  }

  /** 镜像目录名（工作树里那一层；上游固定 'sessions'）。 */
  get #mirrorDir() {
    return this.#deps.mirrorDir ?? MIRROR_DIR
  }

  /** 这一根叫什么（只进日志措辞）。 */
  get #label() {
    return this.#deps.label ?? 'sessions'
  }

  /** 设备短 id（fork 文件名后缀，≤8 位小写十六进制）。 */
  get deviceShort() {
    return this.#deps.deviceId.replaceAll('-', '').slice(0, 8).toLowerCase() || 'unknown'
  }

  /** 串行执行一次同步操作（同仓库互斥）。 */
  #schedule(fn) {
    return this.#deps.git.schedule(fn)
  }

  /** 失败包装：脱敏 → reportError → 结构化错误结果。 */
  #fail(error) {
    const message = redactText(messageOf(error))
    this.#deps.reportError(message)
    this.#deps.logger.warn(`sync failed: ${message}`)
    return { ok: false, error: message }
  }

  /** remote 未配置时响亮失败（状态类操作不拦）。 */
  #requireRemote() {
    if (this.#deps.remote === undefined || this.#deps.remote.trim() === '') {
      throw new Error('no sync remote configured — set config.remote (status/diff work without one)')
    }
  }

  /**
   * 镜像 + 提交（push/pull 共用前置）。
   * @returns {Promise<{deviceWritten: boolean, cardWritten: boolean, mirror: {mirrored: number, unchanged: number, deleted: string[], forkedPreserved: string[], skippedLinks: string[]}}>}
   */
  // 镜像（不提交）：把 sessionRoot 的字节镜像进工作树 + 确保设备文件 + 写自己的名片。
  // 只写本地工作树，不触网、不确认、不提交。调用方已持有 schedule 锁，
  // 这里绝不能再走 #schedule。
  async #mirrorOnly() {
    // Luwi 加 (G1-D)：名片在镜像之前写 —— 它跟会话一起坐同一次提交过去（push 与 pull 的
    // 前置都会走到这里，status 也走，于是配置了 deviceName/publicUrl 之后哪怕还没推过，
    // 名片也已经在工作树里等着进下一次提交）。fail-soft：一张名片不该让同步变红
    let cardWritten = false
    if (typeof this.#deps.writePeerCard === 'function') {
      try {
        cardWritten = (await this.#deps.writePeerCard()) === true
      } catch (error) {
        this.#deps.logger.warn(`peer card write failed: ${messageOf(error)}`)
      }
    }
    const deviceFile = this.#deps.deviceFile === undefined ? DEVICE_FILE : this.#deps.deviceFile
    const deviceWritten = deviceFile === null || deviceFile === ''
      ? false
      : await ensureDeviceFile(this.#deps.repoDir, deviceFile, this.#deps.deviceId)
    const mirror = await mirrorSessionRoot({
      sessionRoot: this.#deps.sessionRoot,
      repoDir: this.#deps.repoDir,
      mirrorDir: this.#mirrorDir,
      exclude: this.#deps.exclude,
      maxFileBytes: this.#deps.maxFileBytes,
    })
    return { deviceWritten, cardWritten, mirror }
  }

  /** Luwi 加：镜像跳过的东西 → 给人看的一句话（没有跳过就是空数组）。 */
  #mirrorWarnings(mirror) {
    const warnings = []
    if (mirror.excludedCount > 0) {
      warnings.push(`${mirror.excludedCount} path(s) skipped by the exclude list (${mirror.excluded.slice(0, 5).join(', ')}${mirror.excludedCount > 5 ? ', …' : ''})`)
    }
    if (mirror.oversizedCount > 0) {
      const cap = this.#deps.maxFileBytes
      warnings.push(`${mirror.oversizedCount} file(s) larger than the ${cap} byte cap were left as they were (${mirror.oversized.slice(0, 5).join(', ')}${mirror.oversizedCount > 5 ? ', …' : ''})`)
    }
    return warnings
  }

  // 前置镜像 + 提交。调用方（push/pull）已持有 schedule 锁，这里绝不能再走
  // #schedule —— 嵌套 schedule 会在同一 git 链上自锁（fnM 等 fnP 完成、fnP 等 fnM）。
  async #mirrorAndCommit(message) {
    const { deviceWritten, cardWritten, mirror } = await this.#mirrorOnly()
    if (deviceWritten || cardWritten || mirror.mirrored > 0 || mirror.deleted.length > 0) {
      const committed = await this.#deps.git.commitAll(message)
      return { ...mirror, committed }
    }
    return { ...mirror, committed: null }
  }

  /**
   * 状态：先本地初始化 + 镜像（不提交），使未同步的会话变更以"未提交文件"
   * 形式进入 dirty；只写本地工作树，不触网、不确认。
   * @returns {Promise<{ok: boolean, branch?: string, remote?: string, head?: string, remoteHead?: string, ahead: number, behind: number, dirty: string[], forks: string[], diffStat: string, mergeInProgress: boolean, lastCommits: string[], error?: string}>}
   */
  status() {
    return this.#schedule(async () => {
      /** @type {object|undefined} 镜像跳过读数（Luwi 加：排除表与单文件上限） */
      let mirror
      try {
        await fs.mkdir(this.#deps.repoDir, { recursive: true })
        await this.#deps.git.ensureRepo()
        mirror = (await this.#mirrorOnly()).mirror
      } catch {
        // 初始化/镜像失败不阻断状态：collectStatus 会返回带 error 的快照。
      }
      const snapshot = await collectStatus(this.#deps.git, { branch: this.#deps.branch, remote: this.#deps.remote ?? '' })
      if (mirror === undefined) return snapshot
      // Luwi 加: 状态里也带上"哪些被排除表挡了、哪些超了上限" (工作区那根主要看这个)
      return {
        ...snapshot,
        mirrorDir: this.#mirrorDir,
        excluded: mirror.excluded,
        excludedCount: mirror.excludedCount,
        oversized: mirror.oversized,
        oversizedCount: mirror.oversizedCount,
        warnings: [...(snapshot.warnings ?? []), ...this.#mirrorWarnings(mirror)],
      }
    })
  }

  /**
   * 推送：镜像 → 提交 → push；被拒时拉取调和后重推一次。
   * @param {object} [opts] - {signal?: AbortSignal}。
   * @returns {Promise<{ok: boolean, pushed?: boolean, head?: string, mirrored?: number, deleted?: number, remote?: string, error?: string}>}
   */
  push(opts = {}) {
    return this.#schedule(async () => {
      try {
        this.#requireRemote()
        // git 命令以 repoDir 为 cwd，首次操作前必须保证它存在（cwd 缺失时
        // spawn 静默失败，甚至伪装成 exit 0）。
        await fs.mkdir(this.#deps.repoDir, { recursive: true })
        await this.#deps.git.bootstrap()
        const mirror = await this.#mirrorAndCommit('dsh-session-sync: push snapshot')
        try {
          await this.#deps.git.push({ signal: opts.signal })
        } catch (error) {
          if (!(error instanceof SyncError && error.code === 'PUSH_REJECTED')) {
            throw error
          }
          // 远端领先：先拉取调和（保留双方），再重推一次。
          this.#deps.logger.info('push rejected (remote moved); pulling and reconciling before one retry')
          const pulled = await this.#pullInternal(opts)
          if (!pulled.ok) throw error
          await this.#deps.git.push({ signal: opts.signal })
        }
        const head = await this.#deps.git.headSha()
        this.#deps.reportMeta({ lastPushAt: Date.now(), lastPushHead: head })
        this.#deps.logger.info(`sync push ok: head ${head ?? ''} (${mirror.mirrored} mirrored, ${mirror.deleted} deleted)`)
        return {
          ok: true,
          pushed: true,
          head,
          mirrored: mirror.mirrored,
          deleted: mirror.deleted.length,
          // Luwi 加：排除表与单文件上限的读数（被跳过的东西如实报，不假装同步了）
          excluded: mirror.excludedCount,
          oversized: mirror.oversizedCount,
          remote: sanitizeRemote(this.#deps.remote),
          warnings: this.#mirrorWarnings(mirror),
        }
      } catch (error) {
        return this.#fail(error)
      }
    })
  }

  /**
   * 拉取：本地提交 → fetch → 三分支合并（append-only 语义）。
   * @param {object} [opts] - {signal?: AbortSignal}。
   * @returns {Promise<{ok: boolean, pulled?: boolean, merged?: boolean, adopted?: number, appended?: number, diverged?: number, forks?: string[], head?: string, error?: string}>}
   */
  pull(opts = {}) {
    return this.#schedule(async () => {
      try {
        this.#requireRemote()
        await fs.mkdir(this.#deps.repoDir, { recursive: true })
        await this.#deps.git.bootstrap()
        await this.#mirrorAndCommit('dsh-session-sync: local checkpoint before pull')
        return await this.#pullInternal(opts)
      } catch (error) {
        return this.#fail(error)
      }
    })
  }

  /**
   * 拉取内部步骤（push 重试路径复用；前置已保证仓库存在且本地已提交）。
   * @param {object} [opts] - {signal?: AbortSignal}。
   * @returns {Promise<object>}
   */
  async #pullInternal(opts = {}) {
    const fetched = await this.#deps.git.fetch({ signal: opts.signal })
    if (!fetched) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      return { ok: true, pulled: false, forks: [], adopted: 0, appended: 0, diverged: 0 }
    }
    const [head, remoteHead] = [await this.#deps.git.headSha(), await this.#deps.git.remoteHeadSha()]
    if (remoteHead === undefined) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      return { ok: true, pulled: false, forks: [], adopted: 0, appended: 0, diverged: 0 }
    }
    if (head === remoteHead) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      return { ok: true, pulled: false, forks: [], adopted: 0, appended: 0, diverged: 0 }
    }
    const behind = await this.#deps.git.countCommitsSafe(head, remoteHead)
    if (behind === 0) {
      // 本地领先（远端无新提交）：无拉取内容。
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      return { ok: true, pulled: false, forks: [], adopted: 0, appended: 0, diverged: 0 }
    }
    const merged = await this.#deps.git.beginMerge(remoteHead)
    if (!merged.inProgress) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      return { ok: true, pulled: false, forks: [], adopted: 0, appended: 0, diverged: 0 }
    }
    let result
    if (merged.conflicted) {
      result = await this.#resolveConflicts()
    } else {
      result = { adopted: 0, appended: 0, diverged: 0, forks: [] }
      await this.#deps.git.addAll()
    }
    const newHead = await this.#deps.git.commitMerge()
    // Luwi 改: 把远端带来的新会话取回本地 sessionRoot —— 上游只把远端内容合并进工作树, 而
    // 本地 dsh 读的是 sessionRoot (见 lib/luwi-adopt.mjs 的文件头)。fail-soft: 取回失败不把
    // 一次成功的 pull 变成失败, 只记日志并在结果里带一句提醒
    let brought = 0
    let updated = 0
    let trashed = 0
    let bringFailed = []
    let adoptWarnings = []
    if (typeof this.#deps.adoptRemote === 'function') {
      try {
        const adoptedNow = await this.#deps.adoptRemote({
          mirrorDir: this.#mirrorDir,
          label: this.#label,
          // Luwi 加: 删除传播的前提 —— `head` 是 pull 第一步那个 checkpoint 提交 (本地每个
          // 文件都在它的树里), 所以"在它里面 + 合并后工作树里没有"就是"这一趟合并删掉了它"
          wasMirrored: (rel) => this.#deps.git.existsAtRev(head, `${this.#mirrorDir}/${rel}`),
        })
        brought = adoptedNow?.brought ?? 0
        updated = adoptedNow?.updated ?? 0
        trashed = adoptedNow?.trashed ?? 0
        bringFailed = adoptedNow?.failed ?? []
        adoptWarnings = adoptedNow?.warnings ?? []
      } catch (error) {
        this.#deps.logger.warn(`bringing merged sessions into the local store failed: ${messageOf(error)}`)
      }
    }
    this.#deps.reportMeta({ lastPullAt: Date.now() })
    // Luwi 加 (G1-2 对端): pull 之后的第二笔活 —— 把对端投在仓库队列里的那几句话收进来
    // (lib/luwi-peer-runtime.mjs 的 consumeInbox), 回执写回队列目录。与 adoptRemote 一样
    // fail-soft: 队列消费失败不许把一次成功的 pull 变成失败
    if (typeof this.afterPull === 'function') {
      try {
        await this.afterPull({ head, remoteHead, brought, updated })
      } catch (error) {
        this.#deps.logger.warn(`the cross-device queue after the pull failed: ${messageOf(error)}`)
      }
    }
    this.#deps.logger.info(`sync pull ok: merged remote head ${remoteHead} → ${newHead ?? ''} (${result.appended} appended-both, ${result.diverged} diverged, ${result.forks.length} forks)`)
    if (result.forks.length > 0) this.#deps.onForks(result.forks)
    const warnings = [...adoptWarnings]
    if (bringFailed.length > 0) {
      warnings.push(`${bringFailed.length} merged file(s) could not be brought into the local session store (they stay in the mirror worktree)`)
    }
    return {
      ok: true,
      pulled: true,
      merged: true,
      adopted: result.adopted,
      brought,
      updated,
      trashed,
      appended: result.appended,
      diverged: result.diverged,
      forks: result.forks,
      head: newHead ?? undefined,
      warnings: warnings.length > 0 ? warnings : undefined,
    }
  }

  /**
   * 冲突解决：逐路径分类 → ours/theirs 检出 → 远端侧留存为 fork 文件 →
   * 全量暂存（提交由调用方落合并提交）。失败时 abortMerge 恢复。
   * @returns {Promise<{adopted: number, appended: number, diverged: number, forks: string[]}>}
   */
  async #resolveConflicts() {
    try {
      const conflicts = await this.#deps.git.conflictedPaths()
      const entries = []
      for (const conflict of conflicts) {
        const [base, ours, theirs] = await Promise.all([
          this.#deps.git.readStageBlob('1', conflict.path),
          this.#deps.git.readStageBlob('2', conflict.path),
          this.#deps.git.readStageBlob('3', conflict.path),
        ])
        entries.push({ path: conflict.path, base, ours, theirs })
      }
      const { resolutions, summary } = planMerge(entries)
      // Luwi 加: 单侧删除 (modify/delete) 的冲突只有一边有 stage —— 工作区镜面里删文件是
      // 常事, 而 `checkout --theirs` / `--ours` 对缺席的那一边会整条失败。这里记住哪一边在,
      // 该 fork 的一边不在就不 fork, 该保留的一边不在就把工作树里那个文件删掉 (add -A 收尾)
      const presence = new Map(entries.map(entry => [entry.path, { ours: entry.ours !== undefined, theirs: entry.theirs !== undefined }]))
      const forks = []
      const now = new Date()
      // 严格 14 位 UTC 戳（yyyyMMddHHmmss）：与 lib/encrypted.mjs 同口径剔除 ISO
      // 分隔符（`-`/`:`/`T`）后再截断。仅去掉 `-`/`:` 会留下 `T`，使
      // forkFileName 的 /^\d{14}$/ 失配并静默回退到纪元戳。
      const stamp = now.toISOString().replace(/[-:T]/gu, '').slice(0, 14)
      for (const resolution of resolutions) {
        const side = presence.get(resolution.path) ?? { ours: true, theirs: true }
        const absolute = path.join(this.#deps.repoDir, ...resolution.path.split('/'))
        if (resolution.adoptTheirs) {
          if (side.theirs) await this.#deps.git.checkoutStage('theirs', resolution.path)
          else await fs.rm(absolute, { force: true })
          continue
        }
        if (resolution.forkTheirs && side.theirs) {
          // 先取 theirs 工作树字节 → 写 fork 文件 → 再回到 ours（或保持删除）。
          await this.#deps.git.checkoutStage('theirs', resolution.path)
          const content = await fs.readFile(absolute)
          const forkName = forkFileName(path.posix.basename(resolution.path), stamp, this.deviceShort)
          await fs.writeFile(path.join(path.dirname(absolute), forkName), content)
          if (side.ours) await this.#deps.git.checkoutStage('ours', resolution.path)
          else await fs.rm(absolute, { force: true })
          const forkRel = path.posix.join(path.posix.dirname(resolution.path), forkName)
          forks.push(forkRel)
          continue
        }
        // 保留 ours (远端那一边没有内容可 fork, 例如远端把它删了): 本地改过的留本地,
        // 本地删过的就让它保持删除 —— 两种都不静默丢掉任何一方的字节
        if (side.ours) await this.#deps.git.checkoutStage('ours', resolution.path)
        else await fs.rm(absolute, { force: true })
      }
      await this.#deps.git.addAll()
      return { adopted: summary.adopted, appended: summary.appended, diverged: summary.diverged, forks }
    } catch (error) {
      try {
        await this.#deps.git.abortMerge()
      } catch {
        // 恢复失败以原始错误为主，恢复失败只记录。
      }
      throw error
    }
  }
}

export { MERGE_KINDS }
