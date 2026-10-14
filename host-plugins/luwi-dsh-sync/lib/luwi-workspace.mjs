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
import path from 'node:path'
import { LIMITS, WORKSPACE_DEFAULTS, WORKSPACE_LIMITS, WORKSPACE_MIRROR_DIR, TRASH_DIR } from './constants.mjs'
import { badConfig } from './errors.mjs'
import { luwiWorktreeProblem } from './luwi-bridge-runner.mjs'

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
