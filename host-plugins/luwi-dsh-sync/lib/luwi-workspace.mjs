// lib/luwi-workspace.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// G1-C「工作区镜面」的规格解析与校验: 上游只有一根 (会话根), 这里把第二根 (工作区根) 的
// 配置面收成一处 —— 落在哪、工作树在哪、远端是谁、排除表长什么样、单文件上限多少。
// 引擎那边不需要知道"这是工作区": 它只认 deps.mirrorDir / deps.exclude / deps.maxFileBytes
// (见 lib/engine.mjs), 所以同一份代码跑两根镜像。
//
// 与第 2 节那张设计表的对应 (docs/DSH-LW-3.0.0-G1-施工单.md):
// - 工作树根 `<工作区>/.lw-sync/workspace-sync/repo` —— 必须在共享存储里, 宿主 (app uid)
//   写镜像、沙盒 (shell uid) 跑 git, 两边都要看得见 (桥 runner 的 /work 绑的是工作区)
// - 镜像目录名 `workspace/` (会话那根是 `sessions/`), 两个仓库各管各的分支与远端
// - 排除表: `.lw-sync` (同步自己的窝) 与 `.git` (别人的仓库) 是**强制项**, 从配置里删不掉
//   —— 少了 `.lw-sync` 那一条, 工作区镜像会把工作树自己走进去, 直接是无限递归
// - 单文件上限缺省 8 MiB: 超限的文件不进镜像, 但也不删远端已有那一份 (见 lib/mirror.mjs)

import { statSync } from 'node:fs'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { LIMITS, TRASH_DIR, WORKSPACE_BUDGET, WORKSPACE_DEFAULTS, WORKSPACE_LIMITS, WORKSPACE_MIRROR_DIR, WORKSPACES_DIR } from './constants.mjs'
import { badConfig } from './errors.mjs'
import { luwiWorktreeProblem } from './luwi-bridge-runner.mjs'
import { listRelFiles, makeExcludeMatcher } from './mirror.mjs'

/** 工作区镜面缺省的工作树落点 (相对工作区根, 在排除表盖着的 `.lw-sync` 底下)。 */
export const WORKSPACE_REPO_RELATIVE = '.lw-sync/workspace-sync/repo'

/** 一张合法的分支名 (与上游 resolveConfig 同一口径)。 */
const BRANCH_RE = /^[A-Za-z0-9._/-]+$/u

/**
 * 排除表规整: 去空/去重/去 `./` 前缀与尾斜杠, 丢掉越界或形态不对的条目。
 * @param {unknown} entries - 配置里的 exclude。
 * @returns {string[]} 规整后的条目 (保持书写顺序)。
 */
export function normalizeExclude(entries) {
  if (!Array.isArray(entries)) return []
  const kept = []
  for (const raw of entries) {
    if (typeof raw !== 'string') {
      throw badConfig(`workspace.exclude entries must be strings, got ${JSON.stringify(raw)}`)
    }
    const trimmed = raw.trim().replace(/^\.\//u, '').replace(/\/+$/u, '')
    if (trimmed === '' || trimmed === '.') continue
    if (trimmed.length > WORKSPACE_LIMITS.MAX_EXCLUDE_LENGTH) {
      throw badConfig(`workspace.exclude entry ${JSON.stringify(raw)} is longer than ${WORKSPACE_LIMITS.MAX_EXCLUDE_LENGTH} characters`)
    }
    if (trimmed.startsWith('/') || trimmed.includes('\\') || trimmed.split('/').some(segment => segment === '..' || segment === '')) {
      throw badConfig(`workspace.exclude entry ${JSON.stringify(raw)} must be a relative path inside the workspace root`)
    }
    if (!kept.includes(trimmed)) kept.push(trimmed)
  }
  if (kept.length > WORKSPACE_LIMITS.MAX_EXCLUDE_ENTRIES) {
    throw badConfig(`workspace.exclude holds ${kept.length} entries, more than the ${WORKSPACE_LIMITS.MAX_EXCLUDE_ENTRIES} allowed`)
  }
  return kept
}

/** a 是否等于 b 或落在 b 之内 (纯路径算术)。 */
function inside(a, b) {
  const rel = path.relative(b, a)
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel))
}

/** 绝对路径在 root 之内的 POSIX 相对形式; 不在之内返回 undefined。 */
function relativeInside(root, absolute) {
  if (!inside(absolute, root)) return undefined
  const rel = path.relative(root, absolute).split(path.sep).join('/')
  return rel === '' ? undefined : rel
}

/**
 * 解析工作区镜面规格。配置缺省或显式关掉时返回 null (整块不存在, 一条命令一个工具都不多)。
 *
 * 校验全部在加载期响亮失败 (BAD_CONFIG): 排除表形态不对、上限越界、分支名不像话、
 * 工作区根不存在、桥模式下工作树跑到工作区外面 —— 这些都到不了运行时。
 * @param {object} deps 依赖。
 * @param {string} deps.workspace 工作区根 (宿主路径; 手机上就是绑给沙盒 /work 的那一棵)。
 * @param {unknown} [deps.config] 合并后的 `workspace` 配置段 (cordis 配置与 Luwi JSON 合过)。
 * @param {{autoPullOnStart?: boolean, autoPushOnTurnEnd?: boolean, pullIntervalMinutes?: number}} [deps.fallback] 顶层自动模式 (工作区段没写时跟它)。
 * @param {{endpoint: string, token: string}|null} [deps.bridge] 桥凭据 (手机侧非 null)。
 * @returns {object|null} 规格; 关掉时 null。
 */
export function resolveWorkspaceMirror(deps) {
  const raw = deps.config
  if (raw === undefined || raw === null) return null
  if (typeof raw !== 'object' || Array.isArray(raw)) {
    throw badConfig('workspace config must be an object')
  }
  const workspace = deps.workspace
  if (typeof workspace !== 'string' || workspace === '') {
    throw badConfig('the workspace root is unknown, so the workspace mirror cannot be configured')
  }
  const remote = typeof raw.remote === 'string' ? raw.remote : ''
  const enabled = typeof raw.enabled === 'boolean' ? raw.enabled : remote.trim() !== ''
  if (!enabled) return null

  const configuredRoot = typeof raw.root === 'string' && raw.root.trim() !== '' ? path.resolve(raw.root) : path.resolve(workspace)
  try {
    if (!statSync(configuredRoot).isDirectory()) throw new Error('not a directory')
  } catch {
    throw badConfig(`workspace.root ${JSON.stringify(configuredRoot)} is not a directory`)
  }

  const configuredRepo = typeof raw.repoDir === 'string' && raw.repoDir.trim() !== ''
    ? path.resolve(workspace, raw.repoDir)
    : path.join(workspace, ...WORKSPACE_REPO_RELATIVE.split('/'))
  if (configuredRepo === configuredRoot) {
    throw badConfig('workspace.repoDir must not be the workspace root itself (the mirror would copy itself)')
  }
  if (deps.bridge !== null && deps.bridge !== undefined) {
    const problem = luwiWorktreeProblem(workspace, configuredRepo)
    if (problem !== null) throw badConfig(problem)
  }

  const branch = typeof raw.branch === 'string' && raw.branch !== '' ? raw.branch : WORKSPACE_DEFAULTS.BRANCH
  if (!BRANCH_RE.test(branch)) {
    throw badConfig('workspace.branch must be a non-empty ref-safe string')
  }

  const maxFileBytes = raw.maxFileBytes === undefined ? WORKSPACE_DEFAULTS.MAX_FILE_BYTES : raw.maxFileBytes
  if (!Number.isInteger(maxFileBytes) || maxFileBytes < WORKSPACE_LIMITS.MIN_MAX_FILE_BYTES || maxFileBytes > WORKSPACE_LIMITS.MAX_MAX_FILE_BYTES) {
    throw badConfig(`workspace.maxFileBytes must be an integer in [${WORKSPACE_LIMITS.MIN_MAX_FILE_BYTES}, ${WORKSPACE_LIMITS.MAX_MAX_FILE_BYTES}] (0 = no cap)`)
  }

  // 排除表: 强制项 + 用户项 + 两条**算出来的**条目 —— 工作树自己 (它要么在 `.lw-sync`
  // 底下、要么被用户挪到了工作区里别处) 与回收站的家 (缺省在 `.lw-sync` 底下, 一起兜住)
  const mandatory = [...WORKSPACE_DEFAULTS.EXCLUDE]
  const exclude = normalizeExclude([...mandatory, ...(raw.exclude === undefined ? [] : raw.exclude)])
  const repoRelative = relativeInside(configuredRoot, configuredRepo)
  if (repoRelative !== undefined && !exclude.includes(repoRelative)) exclude.push(repoRelative)
  const trashDir = typeof raw.trashDir === 'string' && raw.trashDir.trim() !== ''
    ? path.resolve(workspace, raw.trashDir)
    : path.join(workspace, ...TRASH_DIR.split('/'))
  const trashRelative = relativeInside(configuredRoot, trashDir)
  if (trashRelative !== undefined && !exclude.includes(trashRelative)) exclude.push(trashRelative)

  const fallback = deps.fallback ?? {}
  const hasRemote = remote.trim() !== ''
  const pick = (value, fallbackValue) => (typeof value === 'boolean' ? value : fallbackValue === true)
  const interval = raw.pullIntervalMinutes === undefined ? fallback.pullIntervalMinutes ?? 0 : raw.pullIntervalMinutes
  if (!Number.isInteger(interval) || interval < LIMITS.MIN_PULL_INTERVAL_MINUTES || interval > LIMITS.MAX_PULL_INTERVAL_MINUTES) {
    throw badConfig(`workspace.pullIntervalMinutes must be an integer in [${LIMITS.MIN_PULL_INTERVAL_MINUTES}, ${LIMITS.MAX_PULL_INTERVAL_MINUTES}]`)
  }

  return {
    label: 'workspace',
    root: configuredRoot,
    repoDir: configuredRepo,
    remote,
    branch,
    mirrorDir: WORKSPACE_MIRROR_DIR,
    exclude,
    maxFileBytes,
    trashDir,
    // 工作区那份要跟着远端变 (见 lib/luwi-adopt.mjs 的 mirror 模式), 会话那根不跟着删
    adoptMode: 'mirror',
    // 没有远端时自动模式一律不挂: 每轮末推一次必然失败, 只会刷一屏警告
    autoPullOnStart: hasRemote && pick(raw.autoPullOnStart, fallback.autoPullOnStart),
    autoPushOnTurnEnd: hasRemote && pick(raw.autoPushOnTurnEnd, fallback.autoPushOnTurnEnd),
    pullIntervalMinutes: hasRemote ? interval : 0,
    // 非致命提醒 (加载期照挂, 只是说清哪一块动不了)
    problems: hasRemote ? [] : ['workspace.remote is empty: status/diff work, pull/push will refuse, and the automatic modes stay off'],
  }
}

// ---------------------------------------------------------------------------
// 2026-10-20 新形制: 一个同步仓库, 每台设备一个命名空间
//
// 与上面那根"第二棵工作树 / 第二个仓库"的区别 (主人 2026-10-20 定的口径):
//   · 路径 = `workspaces/<设备名>/<工作区名>/` —— 两台设备的同名工作区**不合并**,
//     手机侧仍然是 `dsh（PC）` 这种带设备名的分组 (来源一眼分得清, 也不互相制造 fork)
//   · 与会话同一次 commit / 同一次 push (引擎的多源形态), 不再有第二个远端
//   · 文件只推不拉: 拉取之后对端那份物化成影子 (lib/luwi-sync-shadow.mjs), 本机工作区不动
// ---------------------------------------------------------------------------

/**
 * 把设备名/工作区名收成一个能当目录段的形状 (CJK 保留; 空白与控制字符/路径分隔符折成 `-`)。
 * @param {unknown} value - 原始名字。
 * @param {number} [maxLength] - 段长度上限。
 * @param {string} [fallback] - 收完为空时的兜底词。
 * @returns {string} 目录段。
 */
export function slugSegment(value, maxLength = 60, fallback = 'workspace') {
  const cleaned = String(value ?? '')
    .replace(/[\u0000-\u001f/\\:*?"<>|]/gu, '-')
    .replace(/\s+/gu, '-')
    .replace(/-+/gu, '-')
    .replace(/^[.\-]+/u, '')
    .replace(/[.\-]+$/u, '')
    .slice(0, Math.max(1, maxLength))
  return cleaned === '' ? fallback : cleaned
}

/**
 * 解析工作区源 (新形制)。**缺省是开的** (配对之后"先同步所有工作区"就是这个意思);
 * `workspace.enabled: false` 才关掉。老的 `remote`/`branch`/`repoDir`/`trashDir` 不再生效,
 * 只回一条 problems 让人知道为什么。
 *
 * 校验在加载期响亮失败: 排除表形态、单文件上限、预算越界。
 * @param {object} deps 依赖。
 * @param {string} deps.workspace - 工作区根 (host 的 cwd; 绑给沙盒 /work 的那一棵)。
 * @param {unknown} [deps.config] - `workspace` 配置段。
 * @param {string} [deps.deviceName] - 本机设备名 (命名空间用)。
 * @param {string} [deps.deviceId] - 本机设备 id (设备名空时兜底)。
 * @param {string} [deps.repoDir] - 同步仓库工作树 (要算进排除表)。
 * @param {string} [deps.shadowRoot] - 影子根 (要算进排除表)。
 * @param {(root: string) => string|undefined} [deps.titleFor] - 由工作区根取注册表标题。
 * @returns {{sources: object[], problems: string[]}} 源清单与提醒。
 */
export function resolveWorkspaceSources(deps = {}) {
  const problems = []
  const raw = deps.config
  if (raw !== undefined && raw !== null && (typeof raw !== 'object' || Array.isArray(raw))) {
    throw badConfig('workspace config must be an object')
  }
  const config = raw ?? {}
  if (config.enabled === false) return { sources: [], problems }
  const workspace = deps.workspace
  if (typeof workspace !== 'string' || workspace === '') {
    problems.push('the workspace root is unknown, so the workspace mirror stays off')
    return { sources: [], problems }
  }
  const legacy = ['remote', 'branch', 'repoDir', 'trashDir'].filter(key => typeof config[key] === 'string' && config[key].trim() !== '')
  if (legacy.length > 0) {
    problems.push(`workspace.${legacy.join(' / workspace.')} no longer applies: sessions and workspaces now share the single sync repository`)
  }
  const root = typeof config.root === 'string' && config.root.trim() !== ''
    ? path.resolve(workspace, config.root.trim())
    : path.resolve(workspace)
  try {
    if (!statSync(root).isDirectory()) throw new Error('not a directory')
  } catch {
    problems.push(`workspace.root ${JSON.stringify(root)} is not a directory, so the workspace mirror stays off`)
    return { sources: [], problems }
  }
  const registryTitle = typeof deps.titleFor === 'function' ? deps.titleFor(root) : undefined
  const title = typeof registryTitle === 'string' && registryTitle.trim() !== '' ? registryTitle.trim() : path.basename(root)
  const name = slugSegment(title, 60, 'workspace')
  const deviceSource = typeof deps.deviceName === 'string' && deps.deviceName.trim() !== ''
    ? deps.deviceName.trim()
    : `device-${String(deps.deviceId ?? '').replace(/[^0-9A-Za-z]/gu, '').slice(0, 8) || 'unknown'}`
  const device = slugSegment(deviceSource, 40, 'device')
  const mirrorDir = `${WORKSPACES_DIR}/${device}/${name}`

  const maxFileBytes = config.maxFileBytes === undefined ? WORKSPACE_DEFAULTS.MAX_FILE_BYTES : config.maxFileBytes
  if (!Number.isInteger(maxFileBytes) || maxFileBytes < WORKSPACE_LIMITS.MIN_MAX_FILE_BYTES || maxFileBytes > WORKSPACE_LIMITS.MAX_MAX_FILE_BYTES) {
    throw badConfig(`workspace.maxFileBytes must be an integer in [${WORKSPACE_LIMITS.MIN_MAX_FILE_BYTES}, ${WORKSPACE_LIMITS.MAX_MAX_FILE_BYTES}] (0 = no cap)`)
  }
  const maxFiles = config.maxFiles === undefined ? WORKSPACE_BUDGET.MAX_FILES : config.maxFiles
  if (!Number.isInteger(maxFiles) || maxFiles < WORKSPACE_LIMITS.MIN_MAX_FILES || maxFiles > WORKSPACE_LIMITS.MAX_MAX_FILES) {
    throw badConfig(`workspace.maxFiles must be an integer in [${WORKSPACE_LIMITS.MIN_MAX_FILES}, ${WORKSPACE_LIMITS.MAX_MAX_FILES}]`)
  }
  const maxBytes = config.maxBytes === undefined ? WORKSPACE_BUDGET.MAX_BYTES : config.maxBytes
  if (!Number.isInteger(maxBytes) || maxBytes < WORKSPACE_LIMITS.MIN_MAX_BYTES || maxBytes > WORKSPACE_LIMITS.MAX_MAX_BYTES) {
    throw badConfig(`workspace.maxBytes must be an integer in [${WORKSPACE_LIMITS.MIN_MAX_BYTES}, ${WORKSPACE_LIMITS.MAX_MAX_BYTES}]`)
  }

  const exclude = normalizeExclude([...WORKSPACE_DEFAULTS.EXCLUDE, ...(config.exclude === undefined ? [] : config.exclude)])
  // 算出来的排除项: 同步仓库工作树 / 影子根 / 回收站 若落在工作区里, 绝不镜像它们自己
  for (const candidate of [deps.repoDir, deps.shadowRoot]) {
    if (typeof candidate !== 'string' || candidate === '') continue
    const rel = relativeInside(root, path.resolve(candidate))
    if (rel !== undefined && !exclude.includes(rel)) exclude.push(rel)
  }

  return {
    sources: [{
      label: `workspace: ${name}`,
      title,
      slug: name,
      device,
      root,
      mirrorDir,
      exclude,
      maxFileBytes,
      // 工作区文件没有"旧对话"这回事
      useBaseline: false,
      budget: { maxFiles, maxBytes },
    }],
    problems,
  }
}

/**
 * 量一棵工作区树 (文件数 + 字节数) 是否在预算内; 超了就停 (不把整棵树量完)。
 * @param {string} root - 源根。
 * @param {string[]} [exclude] - 排除表。
 * @param {{maxFiles: number, maxBytes: number}} [budget] - 预算。
 * @returns {Promise<{over: boolean, files: number, bytes: number, reason?: string}>} 读数。
 */
export async function measureWorkspaceBudget(root, exclude, budget) {
  const maxFiles = Number.isInteger(budget?.maxFiles) ? budget.maxFiles : WORKSPACE_BUDGET.MAX_FILES
  const maxBytes = Number.isInteger(budget?.maxBytes) ? budget.maxBytes : WORKSPACE_BUDGET.MAX_BYTES
  const walked = await listRelFiles(root, makeExcludeMatcher(exclude))
  let files = 0
  let bytes = 0
  for (const file of walked.files) {
    files += 1
    try {
      bytes += (await fs.stat(file.absolute)).size
    } catch {
      // 刚被删掉/读不动: 不算它 (镜像那边也会跳)
    }
    if (files > maxFiles) {
      return { over: true, files, bytes, reason: `${files} files, more than the ${maxFiles} budget` }
    }
    if (bytes > maxBytes) {
      return {
        over: true,
        files,
        bytes,
        reason: `${(bytes / (1024 * 1024)).toFixed(1)} MiB, more than the ${(maxBytes / (1024 * 1024)).toFixed(0)} MiB budget`,
      }
    }
  }
  return { over: false, files, bytes }
}
