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
import { oldMirrorPaths } from './luwi-baseline.mjs'
import { measureWorkspaceBudget } from './luwi-workspace.mjs'
import { collectStatus } from './status.mjs'
import { forkFileName } from './paths.mjs'
import { messageOf, SyncError } from './errors.mjs'
import { redactText, sanitizeRemote } from './sanitize.mjs'
import {
  PARTS_INDEX,
  PART_FILE_RE,
  choosePartsSetSide,
  isPartsIndexRel,
  partsDirRelOf,
  partsSetNames,
  readPartsIndex,
} from './luwi-parts.mjs'

/**
 * 冲突阶段里的一份段索引: 那一边没有索引、或不是合法 JSON 都回 null。
 * @param {Buffer|undefined} bytes - stage 2/3 的原始字节。
 * @returns {object|null} 解析出来的索引。
 */
function parsePartsIndexBlob(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length === 0) return null
  try {
    const parsed = JSON.parse(bytes.toString('utf8'))
    if (parsed === null || typeof parsed !== 'object') return null
    if (typeof parsed.file !== 'string' || parsed.file === '') return null
    if (!Number.isFinite(parsed.bytes) || !Number.isFinite(parsed.parts)) return null
    return parsed
  } catch {
    return null
  }
}

/** 工作树里有没有这个路径 (坏掉的符号链接也算占位, 与 other modules 同一口径) */
async function pathExistsOnDisk(target) {
  try {
    await fs.lstat(target)
    return true
  } catch {
    return false
  }
}

/**
 * 一根镜像源 (Luwi 加 2026-10-20: 一个仓库里放多根)。
 * @typedef {object} EngineSource
 * @property {string} label - 日志与读数里叫什么 ('sessions' / 'workspace: 名字')。
 * @property {string} root - 源根 (绝对路径)。
 * @property {string} mirrorDir - 工作树里的镜像目录 (相对仓库根)。
 * @property {string[]} [exclude] - 相对源根的排除前缀表。
 * @property {number} [maxFileBytes] - 单文件上限 (0/缺省 = 不限)。
 * @property {boolean} [useBaseline] - 这个源要不要套会话基线 (只有会话那根要)。
 * @property {Set<string>} [neverSyncSessionIds] - 不许同步的本地会话 id (投影会话)。
 * @property {(context: object) => Promise<object>} [adoptRemote] - pull 之后的取回钩子 (只有会话那根有)。
 */

/**
 * 同步引擎。
 * @typedef {object} EngineDeps
 * @property {import('./git.mjs').GitBackend} git - git 后端。
 * @property {string} sessionRoot - 会话存储根（绝对；单源缺省时用）。
 * @property {string} repoDir - 同步仓库根（绝对）。
 * @property {string} remote - 远端地址原文（展示时 sanitize）。
 * @property {string} branch - 分支名。
 * @property {string} deviceId - 设备 id（fork 归属）。
 * @property {(patch: object) => void} reportMeta - 元数据补丁回调（index.mjs 写领域）。
 * @property {(message: string) => void} reportError - 最近错误回调（写领域 lastError）。
 * @property {(forkRelPaths: string[]) => void} onForks - fork 文件产生回调（index.mjs 做会话级 fork + 事件）。
 * @property {EngineSource[]} [sources] - Luwi 加 (2026-10-20)：多根镜像；缺省由 sessionRoot/mirrorDir 单源兜底。
 * @property {{load: () => Promise<{paths: Record<string, number>}|null>, pending: () => Promise<boolean>, purgePaths: () => Promise<string[]>, done: () => Promise<void>}} [baseline] - Luwi 加：会话基线 (旧对话不同步 + 首次清理)。
 * @property {() => Promise<{brought: number, files?: string[], failed?: string[]}>} [adoptRemote] - Luwi 加：合并后把远端带来的新会话取回本地 sessionRoot（缺省时不取，行为与上游一致；多源时用 source.adoptRemote）。
 * @property {string} [mirrorDir] - Luwi 加：工作树里的镜像目录名（缺省 MIRROR_DIR='sessions'；单源兜底用）。
 * @property {string|null} [deviceFile] - Luwi 加：设备身份文件名（缺省 DEVICE_FILE='device.txt'；null = 不写）。
 * @property {string[]} [exclude] - Luwi 加：单源兜底时的排除表。
 * @property {number} [maxFileBytes] - Luwi 加：单源兜底时的单文件上限。
 * @property {string} [label] - Luwi 加：单源兜底时日志里那根叫什么。
 * @property {() => Promise<boolean>} [writePeerCard] - Luwi 加 (G1-D)：镜像前把自己的名片写进 peers/
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

  /**
   * 最近一次镜像从工作树里删掉的路径 (仓库相对; Luwi 加 2026-10-20 晚)。
   * API 推送要用它补删除传播 —— 引擎在 push 前会把镜像结果先提交, 于是 API 那条路
   * 已经看不到"相对 HEAD 的删除"了 (union 会把这些文件从远端又留下来)
   * @type {string[]}
   */
  #lastDeletedPaths = []

  /** 最近一次镜像删掉的仓库相对路径 (读面)。 */
  get lastDeletedPaths() {
    return this.#lastDeletedPaths
  }

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

  /**
   * 这一趟要镜像的源。
   *
   * 缺省 = 单源（上游与旧调用同形）；Luwi 加 (2026-10-20) 之后一个仓库里可以挂多根
   * （会话一根 + 每台设备的工作区各一根），它们同一次 commit、同一次 push
   * @returns {EngineSource[]} 源清单。
   */
  #sources() {
    if (Array.isArray(this.#deps.sources) && this.#deps.sources.length > 0) return this.#deps.sources
    return [{
      label: this.#deps.label ?? 'sessions',
      root: this.#deps.sessionRoot,
      mirrorDir: this.#deps.mirrorDir ?? MIRROR_DIR,
      exclude: this.#deps.exclude,
      maxFileBytes: this.#deps.maxFileBytes,
      useBaseline: true,
      neverSyncShadowSessions: true,
      adoptRemote: this.#deps.adoptRemote,
    }]
  }

  /** 会话那一根（基线与取回只套它）。 */
  #sessionSource() {
    return this.#sources().find(source => source.useBaseline === true) ?? this.#sources()[0]
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

  /** 基线读一次就够 (一整套操作里每个源都用这一份)。 */
  async #baselinePaths() {
    if (this.#deps.baseline === undefined) return undefined
    try {
      const loaded = await this.#deps.baseline.load()
      return loaded?.paths
    } catch (error) {
      this.#deps.logger.warn(`reading the session baseline failed: ${messageOf(error)}`)
      return undefined
    }
  }

  /**
   * 镜像一次全部源（不提交）：写设备文件与名片、逐源镜像。
   * 只写本地工作树，不触网、不确认、不提交。调用方已持有 schedule 锁，
   * 这里绝不能再走 #schedule。
   * @returns {Promise<{deviceWritten: boolean, cardWritten: boolean, sources: object[], totals: object}>}
   */
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
    const baselinePaths = await this.#baselinePaths()
    const sources = []
    for (const source of this.#sources()) {
      // 工作区那几根先过预算: 超了就整根跳过 (不清远端、不假装同步), 原因进读数
      let budget
      if (source.budget !== undefined) {
        let measured
        try {
          measured = await measureWorkspaceBudget(source.root, source.exclude, source.budget)
        } catch (error) {
          this.#deps.logger.warn(`measuring ${source.label} failed: ${messageOf(error)}`)
          measured = { over: false }
        }
        if (measured.over === true) {
          sources.push({
            label: source.label,
            mirrorDir: source.mirrorDir,
            maxFileBytes: source.maxFileBytes ?? 0,
            budget: { files: measured.files ?? 0, bytes: measured.bytes ?? 0, over: true },
            mirrored: 0,
            unchanged: 0,
            keptLonger: 0,
            deleted: [],
            forkedPreserved: [],
            skippedLinks: [],
            hostArtifactsSkipped: 0,
            hostArtifactsPreserved: [],
            excluded: [],
            excludedCount: 0,
            oversized: [],
            oversizedCount: 0,
            beforeBaseline: 0,
            neverSynced: 0,
            skipped: measured.reason ?? 'over budget',
          })
          continue
        }
        budget = { files: measured.files ?? 0, bytes: measured.bytes ?? 0, over: false }
      }
      const mirror = await mirrorSessionRoot({
        sessionRoot: source.root,
        repoDir: this.#deps.repoDir,
        mirrorDir: source.mirrorDir,
        exclude: source.exclude,
        maxFileBytes: source.maxFileBytes,
        // 基线只套会话那根: 工作区文件没有"旧对话"这回事
        baseline: source.useBaseline === true ? baselinePaths : undefined,
        neverSyncSessionIds: source.neverSyncSessionIds,
        neverSyncShadowSessions: source.neverSyncShadowSessions === true,
        // Luwi 加 (2026-10-22): 会话日志按 zstd 帧切段, 追加只重传最末一段 (REST 没有 delta)
        chunkSessionLogs: source.chunkSessionLogs === true,
        chunkBytes: source.chunkBytes,
        chunkMinBytes: source.chunkMinBytes,
      })
      sources.push({
        label: source.label,
        mirrorDir: source.mirrorDir,
        maxFileBytes: source.maxFileBytes ?? 0,
        ...(budget !== undefined ? { budget } : {}),
        ...mirror,
      })
    }
    const totals = sources.reduce((acc, one) => ({
      mirrored: acc.mirrored + one.mirrored,
      unchanged: acc.unchanged + one.unchanged,
      deleted: acc.deleted + one.deleted.length,
      beforeBaseline: acc.beforeBaseline + (one.beforeBaseline ?? 0),
      neverSynced: acc.neverSynced + (one.neverSynced ?? 0),
    }), { mirrored: 0, unchanged: 0, deleted: 0, beforeBaseline: 0, neverSynced: 0 })
    this.#lastDeletedPaths = sources.flatMap(source =>
      (Array.isArray(source.deleted) ? source.deleted : []).map(rel => `${source.mirrorDir}/${rel}`))
    return { deviceWritten, cardWritten, sources, totals }
  }

  /** Luwi 加：一根源里被跳过的东西 → 给人看的话（没有跳过就是空数组）。 */
  #sourceWarnings(source) {
    const warnings = []
    if (typeof source.skipped === 'string' && source.skipped !== '') {
      warnings.push(`${source.label}: skipped this round (${source.skipped})`)
      return warnings
    }
    if (source.excludedCount > 0) {
      warnings.push(`${source.label}: ${source.excludedCount} path(s) skipped by the exclude list (${source.excluded.slice(0, 5).join(', ')}${source.excludedCount > 5 ? ', …' : ''})`)
    }
    if (source.oversizedCount > 0) {
      const cap = source.maxFileBytes > 0 ? `${source.maxFileBytes} byte` : 'size'
      warnings.push(`${source.label}: ${source.oversizedCount} file(s) larger than the ${cap} cap were left as they were (${source.oversized.slice(0, 5).join(', ')}${source.oversizedCount > 5 ? ', …' : ''})`)
    }
    if (source.beforeBaseline > 0) {
      warnings.push(`${source.label}: ${source.beforeBaseline} file(s) from before the pairing stay local until they change again`)
    }
    if (source.neverSynced > 0) {
      warnings.push(`${source.label}: ${source.neverSynced} local projection file(s) are never uploaded`)
    }
    return warnings
  }

  /** 全部源的跳过说明 (结果块用)。 */
  #mirrorWarnings(sources) {
    return (Array.isArray(sources) ? sources : []).flatMap(source => this.#sourceWarnings(source))
  }

  // 前置镜像 + 提交。调用方（push/pull）已持有 schedule 锁，这里绝不能再走
  // #schedule —— 嵌套 schedule 会在同一 git 链上自锁（fnM 等 fnP 完成、fnP 等 fnM）。
  async #mirrorAndCommit(message) {
    const { deviceWritten, cardWritten, sources, totals } = await this.#mirrorOnly()
    // **无条件提交**（2026-10-21 晚）: 仓库根下还有一批不归镜像管的文件 —— 收件箱、回执、
    // 名片、live 投影 —— 它们由别的代码写。从前只在"镜像这一趟有变化"时才 commitAll,
    // 于是 consumeInbox 刚写出的回执永远停在未跟踪状态, API 推送的 write-tree 里没有它,
    // 对端也就永远收不到"这一句送到了"（2026-10-21 端到端实测踩到）
    const committed = await this.#deps.git.commitAll(message)
    return { ...totals, sources, committed }
  }

  /**
   * 状态：先本地初始化 + 镜像（不提交），使未同步的会话变更以"未提交文件"
   * 形式进入 dirty；只写本地工作树，不触网、不确认。
   * @returns {Promise<{ok: boolean, branch?: string, remote?: string, head?: string, remoteHead?: string, ahead: number, behind: number, dirty: string[], forks: string[], diffStat: string, mergeInProgress: boolean, lastCommits: string[], error?: string}>}
   */
  status() {
    return this.#schedule(async () => {
      /** @type {{sources: object[], totals: object}|undefined} 镜像读数 */
      let mirrored
      try {
        await fs.mkdir(this.#deps.repoDir, { recursive: true })
        await this.#deps.git.ensureRepo()
        mirrored = await this.#mirrorOnly()
      } catch {
        // 初始化/镜像失败不阻断状态：collectStatus 会返回带 error 的快照。
      }
      const snapshot = await collectStatus(this.#deps.git, { branch: this.#deps.branch, remote: this.#deps.remote ?? '' })
      if (mirrored === undefined) return snapshot
      const sessionSource = this.#sessionSource()
      const sessionMirror = mirrored.sources.find(source => source.mirrorDir === (sessionSource?.mirrorDir ?? MIRROR_DIR))
        ?? mirrored.sources[0]
      return {
        ...snapshot,
        mirrorDir: sessionSource?.mirrorDir ?? MIRROR_DIR,
        excluded: sessionMirror?.excluded ?? [],
        excludedCount: sessionMirror?.excludedCount ?? 0,
        oversized: sessionMirror?.oversized ?? [],
        oversizedCount: sessionMirror?.oversizedCount ?? 0,
        // Luwi 加 (2026-10-20): 一个仓库里的每一根源各一条读数 (面板与工具看的都是它)
        sources: mirrored.sources.map(source => ({
          label: source.label,
          mirrorDir: source.mirrorDir,
          mirrored: source.mirrored,
          unchanged: source.unchanged,
          deleted: source.deleted.length,
          excludedCount: source.excludedCount,
          oversizedCount: source.oversizedCount,
          beforeBaseline: source.beforeBaseline,
          neverSynced: source.neverSynced,
          ...(source.skipped !== undefined ? { skipped: source.skipped } : {}),
          ...(source.budget !== undefined ? { budget: source.budget } : {}),
        })),
        baseline: mirrored.totals.beforeBaseline > 0 || mirrored.totals.neverSynced > 0
          ? { beforeBaseline: mirrored.totals.beforeBaseline, neverSynced: mirrored.totals.neverSynced }
          : undefined,
        warnings: [...(snapshot.warnings ?? []), ...this.#mirrorWarnings(mirrored.sources)],
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
        // Luwi 加 (2026-10-20): 首次同步的清理还没做就先补一趟 pull —— 只清本地工作树是
        // 删不掉"远端已有、本机没有"那些旧会话的 (push 不会删远端文件)
        if (this.#deps.baseline !== undefined && await this.#deps.baseline.pending()) {
          await this.#mirrorAndCommit('dsh-session-sync: checkpoint before the first purge')
          try {
            const prepared = await this.#pullInternal(opts)
            if (prepared.ok !== true) throw new Error(prepared.error ?? 'the first purge could not run')
          } catch (error) {
            // 网络那一趟交给 API 传输时 fetch 会被冷却闸拦下; 清理由 finishExternalPull 收口,
            // 不能因此把这一轮 push 整个 abort 掉 —— 否则手机第一次同步永远出不了门
            if (!/git network skipped/iu.test(String(error?.message ?? error))) throw error
            this.#deps.logger.warn('the first purge pull goes through the api transport instead')
          }
        }
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
          deleted: mirror.deleted,
          // Luwi 加：排除表 / 单文件上限 / 旧对话 / 投影会话的读数（被跳过的东西如实报）
          excluded: mirror.sources.reduce((sum, source) => sum + (source.excludedCount ?? 0), 0),
          oversized: mirror.sources.reduce((sum, source) => sum + (source.oversizedCount ?? 0), 0),
          beforeBaseline: mirror.beforeBaseline,
          neverSynced: mirror.neverSynced,
          sources: mirror.sources.map(source => ({
            label: source.label,
            mirrorDir: source.mirrorDir,
            mirrored: source.mirrored,
            deleted: source.deleted.length,
            beforeBaseline: source.beforeBaseline,
          })),
          remote: sanitizeRemote(this.#deps.remote),
          warnings: this.#mirrorWarnings(mirror.sources),
        }
      } catch (error) {
        return this.#fail(error)
      }
    })
  }

  /**
   * 只做镜像、不碰 git (Luwi 加 2026-10-21 晚)。
   *
   * 手机沙盒里 `git add/commit/fetch` 会把桥挂住 (实测 5+ 分钟), 而 API 传输的 pull 只需要
   * 工作树里的源字节; 于是手机上的 pull 走 `preparePull` + API, 一条 git 命令都不发
   * @returns {Promise<object>} `#mirrorOnly` 的读数。
   */
  preparePull() {
    return this.#schedule(async () => {
      await fs.mkdir(this.#deps.repoDir, { recursive: true })
      return await this.#mirrorOnly()
    })
  }

  /**
   * 只做镜像、不碰 git 的推送前置 (Luwi 加 2026-10-21 晚)。
   *
   * 与 `preparePull` 同一个理由: 手机沙盒里的 `git add -A` 在 2 MB 级会话上会把桥挂住,
   * 而 API 推送只需要工作树字节与这一趟的删除清单 (两样都在 `#mirrorOnly` 里算好了)。
   * @returns {Promise<object>} `#mirrorOnly` 的读数。
   */
  preparePush() {
    return this.#schedule(async () => {
      await fs.mkdir(this.#deps.repoDir, { recursive: true })
      return await this.#mirrorOnly()
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
        if (process.platform === 'android') {
          // 手机沙盒里 `git add -A` 一条 2 MB 级的会话会挂住桥 (2026-10-21 实测 5+ 分钟),
          // 而 pull 只需要把源写进工作树; 本地 HEAD 的记账留给 push 或下一次成功的 commit,
          // API 那条路的取回/判断全看工作树字节, 不依赖 HEAD
          await this.#mirrorOnly()
        } else {
          await this.#mirrorAndCommit('dsh-session-sync: local checkpoint before pull')
        }
        return await this.#pullInternal(opts)
      } catch (error) {
        return this.#fail(error)
      }
    })
  }

  /**
   * API 传输拉过一次之后的收尾 (Luwi 加 2026-10-20 晚)。
   *
   * `pullSync` 走 API 时引擎的 `#pullInternal` 根本没跑, 于是 pull 之后那三笔活全缺:
   * ① 把远端带来的新会话取回本地 sessionRoot (不取回, 手机 dsh 的会话列表里永远看不见)；
   * ② 消费对端投在仓库队列里的那几句；③ 首次配对的清理收口。
   * 这里一次补齐, 与 `#pullInternal` 的语义对齐；失败都不把一次成功的 API 拉取变红
   *
   * **取回排在清理前面** (Luwi 改 2026-10-21): 从前那是"先清理再取回", 理由是"旧会话必须先
   * 从工作树里消失, 否则 bring-missing 会把它们又导回本机"。现在这条顾虑由取回自己负责 ——
   * 它拿着基线逐条判 `skipOld`, 仍旧算旧的一条都不往本机写, 于是清理这一步不再需要挡在
   * 前面: 清理慢 (手机上曾扫 3669 个文件) 就只拖慢清理自己, 拉取该完成的照旧完成
   * @param {{head?: string, remoteHead?: string}} [context] - API 拉到的 head。
   * @returns {Promise<{brought: number, updated: number, trashed: number, skipped: number, deferred: number, purged: number, failed: string[], warnings: string[]}>}
   */
  finishExternalPull(context = {}) {
    return this.#schedule(async () => {
      let brought = 0
      let updated = 0
      let trashed = 0
      let skipped = 0
      let deferred = 0
      let purged = 0
      const failed = []
      const warnings = []
      for (const source of this.#sources()) {
        if (typeof source.adoptRemote !== 'function') continue
        try {
          const adopted = await source.adoptRemote({
            mirrorDir: source.mirrorDir,
            label: source.label,
            // API 拉取不保留"上一趟 checkpoint"的提交, 删除传播这一档明确放弃 (只取回, 不搬走)
            wasMirrored: () => false,
          })
          brought += adopted?.brought ?? 0
          updated += adopted?.updated ?? 0
          trashed += adopted?.trashed ?? 0
          skipped += adopted?.skipped ?? 0
          deferred += adopted?.deferred ?? 0
          failed.push(...(adopted?.failed ?? []))
          warnings.push(...(adopted?.warnings ?? []))
        } catch (error) {
          warnings.push(`bringing merged files into the local ${source.label} store failed: ${messageOf(error)}`)
        }
      }
      // 首次配对的清理: 只删镜像里**仍然算旧**的那些 (见 lib/luwi-baseline.mjs 的 oldMirrorPaths),
      // 取回已经走完, 它成不成都不再决定这一趟 pull 拉不拉得到会话
      if (this.#deps.baseline !== undefined) {
        try {
          if (await this.#deps.baseline.pending()) {
            const session = this.#sessionSource()
            const mirrorDir = session.mirrorDir
            // 只走镜像那一侧: 手机上扫一遍 sessionRoot 是 3000+ 次 stat, 首拉就是卡在这里
            const state = await this.#deps.baseline.load()
            const paths = await oldMirrorPaths(
              path.join(this.#deps.repoDir, mirrorDir),
              state?.paths,
              session.root,
            )
            for (const rel of paths) {
              const absolute = path.join(this.#deps.repoDir, mirrorDir, ...rel.split('/'))
              try {
                await fs.stat(absolute)
              } catch {
                // 本地镜像里本来就没有这一份 (手机首拉常见): 不算清掉的文件
                continue
              }
              await fs.rm(absolute, { force: true })
              purged += 1
            }
            // 手机上不要在这里 `git add -A`: 清掉的旧会话动辄上千条, 沙盒里的 add 会把桥
            // 挂住, 而后面的"取回会话"就永远轮不到 (2026-10-21 实测 5+ 分钟孤儿 shell)。
            // 删除账下一次镜像的 deleted 列表会带着走, API push 的 lastDeletedPaths 也能补
            if (purged > 0 && process.platform !== 'android') await this.#deps.git.addAll()
            await this.#deps.baseline.done()
            if (purged > 0) this.#deps.logger.info(`first sync (api): removed ${purged} pre-pairing session file(s) from the mirror`)
          }
        } catch (error) {
          warnings.push(`the first-sync purge after the api pull failed: ${messageOf(error)}`)
        }
      }
      if (typeof this.afterPull === 'function') {
        const after = this.afterPull({
          head: context.head,
          remoteHead: context.remoteHead ?? context.head,
          brought,
          updated,
          purged,
        })
        if (process.platform === 'android') {
          // 手机: 对端队列/工作区物化可能还要经沙盒 git, 不能让它把"已经取回完"的 pull
          // 继续拖住 (拖住的后果是 lastPullAt 不更新、下一次 pull 也不来)
          void after.catch(error => this.#deps.logger.warn(`the cross-device queue after the api pull failed: ${messageOf(error)}`))
        } else {
          try {
            await after
          } catch (error) {
            warnings.push(`the cross-device queue after the api pull failed: ${messageOf(error)}`)
          }
        }
      }
      return { brought, updated, trashed, skipped, deferred, purged, failed, warnings }
    })
  }

  /**
   * 拉取内部步骤（push 重试路径复用；前置已保证仓库存在且本地已提交）。
   * @param {object} [opts] - {signal?: AbortSignal}。
   * @returns {Promise<object>}
   */
  async #pullInternal(opts = {}) {
    // Luwi 加 (2026-10-20): 配对之后的第一次拉取要顺手把"仓库里那些配对前就停住的旧会话"
    // 清掉 (只删本机基线里仍然没再动过的那些, 见 lib/luwi-baseline.mjs)
    const purgePending = this.#deps.baseline !== undefined && await this.#deps.baseline.pending()
    let purged = 0
    const finishPurge = async () => {
      if (!purgePending) return
      try {
        await this.#deps.baseline.done()
      } catch (error) {
        this.#deps.logger.warn(`marking the first purge done failed: ${messageOf(error)}`)
      }
    }
    const empty = { ok: true, pulled: false, forks: [], adopted: 0, appended: 0, diverged: 0, purged: 0 }
    const fetched = await this.#deps.git.fetch({ signal: opts.signal })
    if (!fetched) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      await finishPurge()
      return empty
    }
    const [head, remoteHead] = [await this.#deps.git.headSha(), await this.#deps.git.remoteHeadSha()]
    if (remoteHead === undefined) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      await finishPurge()
      return empty
    }
    if (head === remoteHead) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      await finishPurge()
      return empty
    }
    const behind = await this.#deps.git.countCommitsSafe(head, remoteHead)
    if (behind === 0) {
      // 本地领先（远端无新提交）：无拉取内容。
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      await finishPurge()
      return empty
    }
    const merged = await this.#deps.git.beginMerge(remoteHead)
    if (!merged.inProgress) {
      this.#deps.reportMeta({ lastPullAt: Date.now() })
      await finishPurge()
      return empty
    }
    let result
    if (merged.conflicted) {
      result = await this.#resolveConflicts()
    } else {
      result = { adopted: 0, appended: 0, diverged: 0, forks: [], partSets: 0 }
      await this.#deps.git.addAll()
    }
    // 清理: 合并之后、提交之前, 把仍然算旧的会话从工作树里删掉 —— 这一趟的删除会进合并提交,
    // 于是 push 之后远端也就没有它们了
    if (purgePending) {
      try {
        const session = this.#sessionSource()
        const state = await this.#deps.baseline.load()
        const paths = await oldMirrorPaths(
          path.join(this.#deps.repoDir, session.mirrorDir),
          state?.paths,
          session.root,
        )
        for (const rel of paths) {
          await fs.rm(path.join(this.#deps.repoDir, session.mirrorDir, ...rel.split('/')), { force: true })
        }
        purged = paths.length
        if (purged > 0) {
          await this.#deps.git.addAll()
          this.#deps.logger.info(`first sync: removed ${purged} pre-pairing session file(s) from the mirror`)
        }
      } catch (error) {
        this.#deps.logger.warn(`the first-sync purge failed: ${messageOf(error)}`)
      }
    }
    const newHead = await this.#deps.git.commitMerge()
    await finishPurge()
    // Luwi 改: 把远端带来的新会话取回本地 sessionRoot —— 上游只把远端内容合并进工作树, 而
    // 本地 dsh 读的是 sessionRoot (见 lib/luwi-adopt.mjs 的文件头)。fail-soft: 取回失败不把
    // 一次成功的 pull 变成失败, 只记日志并在结果里带一句提醒
    // Luwi 改 (2026-10-21): 从前这一趟在 `purged > 0` 时整个跳过取回, 理由是"旧会话正是要留下的
    // 东西, 不能顺着合并又被导进本机"。现在这条由取回自己守着 —— 基线里仍然算旧的一条都不取回
    // (`skipOld`), 而真正新的会话照旧取回, 于是清理那一下不再等于"这一趟什么都补不进来"
    let brought = 0
    let updated = 0
    let trashed = 0
    let skippedOld = 0
    /** 还没落齐的段集合 (下一趟 pull 接着搬; 不是失败, 单独记一笔) */
    let deferredSets = 0
    let bringFailed = []
    let adoptWarnings = []
    for (const source of this.#sources()) {
      if (typeof source.adoptRemote !== 'function') continue
      try {
        const adoptedNow = await source.adoptRemote({
          mirrorDir: source.mirrorDir,
          label: source.label,
          // Luwi 加: 删除传播的前提 —— `head` 是 pull 第一步那个 checkpoint 提交 (本地每个
          // 文件都在它的树里), 所以"在它里面 + 合并后工作树里没有"就是"这一趟合并删掉了它"
          wasMirrored: (rel) => this.#deps.git.existsAtRev(head, `${source.mirrorDir}/${rel}`),
        })
        brought += adoptedNow?.brought ?? 0
        updated += adoptedNow?.updated ?? 0
        trashed += adoptedNow?.trashed ?? 0
        skippedOld += adoptedNow?.skipped ?? 0
        deferredSets += adoptedNow?.deferred ?? 0
        bringFailed = [...bringFailed, ...(adoptedNow?.failed ?? [])]
        adoptWarnings = [...adoptWarnings, ...(adoptedNow?.warnings ?? [])]
      } catch (error) {
        this.#deps.logger.warn(`bringing merged files into the local ${source.label} store failed: ${messageOf(error)}`)
      }
    }
    this.#deps.reportMeta({ lastPullAt: Date.now() })
    // Luwi 加 (G1-2 对端): pull 之后的第二笔活 —— 把对端投在仓库队列里的那几句话收进来
    // (lib/luwi-peer-runtime.mjs 的 consumeInbox), 回执写回队列目录。与 adoptRemote 一样
    // fail-soft: 队列消费失败不许把一次成功的 pull 变成失败
    if (typeof this.afterPull === 'function') {
      try {
        await this.afterPull({ head, remoteHead, brought, updated, purged })
      } catch (error) {
        this.#deps.logger.warn(`the cross-device queue after the pull failed: ${messageOf(error)}`)
      }
    }
    this.#deps.logger.info(`sync pull ok: merged remote head ${remoteHead} → ${newHead ?? ''} (${result.appended} appended-both, ${result.diverged} diverged, ${result.forks.length} forks, ${result.partSets ?? 0} parts sets${deferredSets > 0 ? `, ${deferredSets} set(s) still landing` : ''})`)
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
      skipped: skippedOld,
      // 段集合还在落的那些 (下一趟接着搬): 正常状态, 不是失败
      deferred: deferredSets,
      partSets: result.partSets ?? 0,
      purged,
      appended: result.appended,
      diverged: result.diverged,
      forks: result.forks,
      head: newHead ?? undefined,
      warnings: warnings.length > 0 ? warnings : undefined,
    }
  }

  /**
   * 段集合整组选边 (2026-10-22; 方案文档 §14.2 第 3 条"集合要原子")。
   *
   * 逐路径 `classifyPath` 会把两条血脉的段留在一组里: 索引冲突出自一侧、某个段冲突出自另一侧,
   * 拼出来 sha256 对不上, 取回端只能整组不认, 而镜像里那组一直坏着。这里在进入 `planMerge`
   * 之前先按整组定一次输赢 —— 比 `bytes`, 一样大留本地 (见 [choosePartsSetSide]) —— 组内
   * 冲突路径之后由 [#applyPartsDecision] 一次落完
   * @param {Array<{path: string, base?: Buffer, ours?: Buffer, theirs?: Buffer}>} entries - 冲突路径与三方字节。
   * @returns {Promise<{decisions: object[], handled: Set<string>}>} 决定与已被这里接手的路径。
   */
  async #planPartsSets(entries) {
    const session = this.#sessionSource()
    const mirrorRoot = path.join(this.#deps.repoDir, session.mirrorDir)
    /** 段目录相对路径 → 这一组在合并里出了什么事 */
    const groups = new Map()
    const groupOf = (partsRel) => {
      let group = groups.get(partsRel)
      if (group === undefined) {
        group = { partsRel, indexEntry: undefined, flatEntry: undefined }
        groups.set(partsRel, group)
      }
      return group
    }
    // ① 索引本身冲突 = 两侧都动过这一组 (真正的分叉)
    for (const entry of entries) {
      if (!isPartsIndexRel(entry.path)) continue
      groupOf(partsDirRelOf(entry.path)).indexEntry = entry
    }
    // ② 扁平会话文件的修改/删除冲突, 且同目录里有段集合 = 布局切换 (扁平 ↔ 切段)
    for (const entry of entries) {
      if (partsDirRelOf(entry.path) !== null) continue
      if (!/\.zstd$/u.test(path.posix.basename(entry.path))) continue
      const partsRel = `${path.posix.dirname(entry.path)}/parts`
      if (!groups.has(partsRel)
        && !(await pathExistsOnDisk(path.join(mirrorRoot, ...partsRel.split('/'), PARTS_INDEX)))) continue
      // 两边都改过那份扁平文件、同时又有段集合 = 说不清谁是谁, 留给逐路径 (不猜)
      groupOf(partsRel).flatEntry = entry
    }
    const decisions = []
    const handled = new Set()
    for (const group of groups.values()) {
      const dirAbs = path.join(mirrorRoot, ...group.partsRel.split('/'))
      let ourIndex = null
      let theirIndex = null
      if (group.indexEntry !== undefined) {
        ourIndex = parsePartsIndexBlob(group.indexEntry.ours)
        theirIndex = parsePartsIndexBlob(group.indexEntry.theirs)
      } else {
        const worktreeIndex = await readPartsIndex(dirAbs)
        if (worktreeIndex === null) continue
        const flatOurs = group.flatEntry?.ours !== undefined
        const flatTheirs = group.flatEntry?.theirs !== undefined
        if (flatOurs === flatTheirs) continue
        // 有段集合的那一侧 = 没有扁平文件的那一侧
        if (flatOurs) theirIndex = worktreeIndex
        else ourIndex = worktreeIndex
      }
      // 每一侧"有多长": 有段集合看索引 bytes, 只有扁平那份看它的大小
      const ourFlat = group.flatEntry?.ours
      const theirFlat = group.flatEntry?.theirs
      const ourBytes = ourIndex !== null ? Number(ourIndex.bytes) : (ourFlat === undefined ? 0 : ourFlat.length)
      const theirBytes = theirIndex !== null ? Number(theirIndex.bytes) : (theirFlat === undefined ? 0 : theirFlat.length)
      if (ourBytes === 0 && theirBytes === 0) continue
      const side = choosePartsSetSide({ bytes: ourBytes }, { bytes: theirBytes })
      if (side === 'none') continue
      const keep = side === 'ours' ? ourIndex : theirIndex
      const flatRel = group.flatEntry?.path
        ?? (keep !== null && typeof keep.file === 'string'
          ? path.posix.join(path.posix.dirname(group.partsRel), keep.file)
          : null)
      const stages = new Map()
      for (const entry of entries) {
        if (partsDirRelOf(entry.path) !== group.partsRel && entry.path !== flatRel) continue
        stages.set(entry.path, { ours: entry.ours !== undefined, theirs: entry.theirs !== undefined })
      }
      for (const rel of stages.keys()) handled.add(rel)
      decisions.push({
        partsRel: group.partsRel,
        side,
        keep,
        flatRel,
        stages,
        indexRel: `${group.partsRel}/${PARTS_INDEX}`,
        ourBytes,
        theirBytes,
      })
    }
    return { decisions, handled }
  }

  /**
   * 把一组段按 [.#planPartsSet] 定的边落到工作树: 冲突路径取选中那一侧, 之后把落选的段与
   * 不该在的扁平文件收掉 —— 留下的一定是一组自洽的段 (拼得回来, 且与索引 sha256 对得上)
   * @param {object} decision - `#planPartsSets` 的一条决定。
   * @returns {Promise<void>}
   */
  async #applyPartsDecision(decision) {
    const repoDir = this.#deps.repoDir
    const dirAbs = path.join(repoDir, ...decision.partsRel.split('/'))
    const keepNames = decision.keep === null ? null : new Set(partsSetNames(decision.keep))
    for (const [rel, stages] of decision.stages) {
      if (rel === decision.flatRel) continue
      const has = decision.side === 'ours' ? stages.ours : stages.theirs
      if (has) await this.#deps.git.checkoutStage(decision.side, rel)
      else await fs.rm(path.join(repoDir, ...rel.split('/')), { force: true })
    }
    let present = []
    try {
      present = await fs.readdir(dirAbs)
    } catch {
      present = []
    }
    if (keepNames === null) {
      // 选中的那一侧根本没有段集合 (布局切回扁平): 整个段目录收掉
      await fs.rm(dirAbs, { recursive: true, force: true })
    } else {
      for (const name of present) {
        if (name === PARTS_INDEX || !PART_FILE_RE.test(name) || keepNames.has(name)) continue
        await fs.rm(path.join(dirAbs, name), { force: true })
      }
    }
    this.#deps.logger.info(`parts set ${decision.partsRel}: kept ${decision.side} (${decision.ourBytes} vs ${decision.theirBytes} bytes${keepNames === null ? ', flat layout' : ''})`)
    if (decision.flatRel === null) return
    const flatAbs = path.join(repoDir, ...decision.flatRel.split('/'))
    if (keepNames !== null) {
      // 选中段集合: 扁平那份收掉 (§9 的布局切换是自愈的, 同一趟就要切干净)
      await fs.rm(flatAbs, { force: true })
      return
    }
    const stages = decision.stages.get(decision.flatRel)
    if (stages === undefined) return
    const has = decision.side === 'ours' ? stages.ours : stages.theirs
    if (has) await this.#deps.git.checkoutStage(decision.side, decision.flatRel)
    else await fs.rm(flatAbs, { force: true })
  }

  /**
   * 冲突解决：逐路径分类 → ours/theirs 检出 → 远端侧留存为 fork 文件 →
   * 全量暂存（提交由调用方落合并提交）。失败时 abortMerge 恢复。
   * @returns {Promise<{adopted: number, appended: number, diverged: number, forks: string[], partSets: number}>}
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
      // 段集合先按整组定输赢 (2026-10-22): 组内那些路径不再进 planMerge, 由下面单独落盘
      const partsPlan = await this.#planPartsSets(entries)
      const remaining = entries.filter(entry => !partsPlan.handled.has(entry.path))
      const { resolutions, summary } = planMerge(remaining)
      // Luwi 加: 单侧删除 (modify/delete) 的冲突只有一边有 stage —— 工作区镜面里删文件是
      // 常事, 而 `checkout --theirs` / `--ours` 对缺席的那一边会整条失败。这里记住哪一边在,
      // 该 fork 的一边不在就不 fork, 该保留的一边不在就把工作树里那个文件删掉 (add -A 收尾)
      const presence = new Map(remaining.map(entry => [entry.path, { ours: entry.ours !== undefined, theirs: entry.theirs !== undefined }]))
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
      // 整组段: 冲突路径按选中那一侧落盘, 之后把落选的段与扁平那份收干净
      for (const decision of partsPlan.decisions) await this.#applyPartsDecision(decision)
      await this.#deps.git.addAll()
      return {
        adopted: summary.adopted,
        appended: summary.appended,
        diverged: summary.diverged,
        forks,
        // 这一趟按整组选边的段集合数 (0 = 没有段集合参与这次合并)
        partSets: partsPlan.decisions.length,
      }
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
