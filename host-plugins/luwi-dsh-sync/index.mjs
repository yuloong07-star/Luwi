// Luwi 改了什么 (对照上游 v0.2.24, commit c5f0c929685aafae94749868b07e874035b18ba4):
// 1. 手机侧改走桥 runner —— LW_CHANNEL_ENDPOINT 在时, git 不再走 ctx.subprocess, 而是经
//    app 的 Linux 沙盒执行 (lib/luwi-bridge-runner.mjs; worktree 在共享存储, gitdir 在
//    沙盒自己的真文件系统, 见 docs/DSH-LW-3.0.0-G1-施工单.md 第 9 节)。电脑与别的主机
//    照旧走上游那条路, 同一份包两边都能跑
// 2. 配置多读一份 $DSH_HOME/lw/session-sync.json (可选, 安装即用; 显式 cordis 配置优先)
// 3. bridge 模式下 repoDir 默认 = <工作区>/.lw-sync/session-sync/repo (共享存储, 宿主与
//    沙盒都看得见), 显式配置的 repoDir 必须落在该工作区内
// 4. pull 合并后把远端带来的新会话取回本地 sessionRoot (lib/luwi-adopt.mjs; 上游只合并进
//    工作树, 本地 dsh 看不见)
// 5. G1-C 工作区镜面: 同一个引擎实例化第二次 —— 工作区根 → 第二棵工作树/第二个仓库,
//    带排除表 (`.lw-sync` / `.git` 是强制项) 与单文件上限; 取回那边是 mirror 模式
//    (远端改了就更新本地、远端删了就搬进回收站)。命令面 `/sync ws <status|pull|push|diff|log>`,
//    工具 `sync_*` 多一个 target 参数; 规格与校验在 lib/luwi-workspace.mjs
// 6. G1-D 互为远端: 配置多两个字段 deviceName / publicUrl ($DSH_HOME/lw/session-sync.json),
//    会话那根每次镜像前把自己的名片写进 peers/<设备 id>.json (lib/luwi-peers.mjs, 跟会话坐
//    同一次提交过去); `/sync peers` 列出镜像里见过的名片, /sync status 多一行对端计数 ——
//    手机设置页 ⋮ 里那几行「打开 <对端名>」读的就是本地镜像里的这一撮
// 正文四处修改点另有 "Luwi 改" 标记
//
// Luwi 改 (G1 收口, 2026-10-14) —— 改名 + 载入期安全契约。这一节是"插件不许让 dsh 起不来"
// 那件事的落点, 每条都在下面正文里有实现 (标 C1..C9):
//   C1 apply() 绝不向外抛: 意外一律降级成一行 error, 宿主照常起来
//   C2 配置非法不抛: 打一行 error + 把插件停用 (schema 也不再带 .default)
//   C3 所有 fire-and-forget 的 promise 都带 .catch —— 未处理的 rejection 会被宿主当致命错误吃
//   C4 载入期只读一份小 JSON 与打一行日志, 镜像/git/扫描全部推迟
//   C6 双挂载自检: bundle 行与 profile 行同时存在时绝不挂第二个引擎 (两个引擎共用一个 repoDir)
//   C8 三层合并: 显式 cordis 键 > $DSH_HOME/lw/session-sync.json > DEFAULTS
//   C9 新增 /sync doctor 与 sync_doctor: peer 解析落点 / ② 层 extraneous / spawn 健康度
// 为什么这么严: 2026-10-13 为了让这个插件的 import 通过, 有人把 54 个 @deepseek-ai 核心包
// 手工拷进了 profile 的 node_modules, 而那一层在模块解析里优先于运行安装的注入表
// (profiles/node_modules), 于是 Host 自己的 spawn 设施被自家 profile 遮蔽 (缺 koffi),
// pwsh / glob / grep 全废; 同一个错误再往前一步就是 Host 起不来 → Electron 原生恢复对话框
// → 251 行的 cordis.patch.yml 被改名成 .bak。所以这里的每一条都是被踩过换来的
//
// index.mjs — luwi-dsh-sync 插件入口（唯一 host 面文件）。
//
// 功能：把 DSH 会话存储目录（默认 $DSH_HOME/sessions）作为不透明字节镜像进
// 一个专用 git 工作树并同步到自定义 remote：
// - 默认 git 后端（ctx.subprocess 跑 git；动词白名单 + 绝不强推）；backend 词汇
//   预留端到端加密后端（age/GPG 类），密钥不入仓库（当前配置即响亮失败）。
// - /sync 命令（status|pull|push|diff|log|help；pull/push 走确认门）与
//   sync_pull / sync_push / sync_status 模型工具（操作走审批，轮次内可用）。
// - 冲突裁决：会话日志 append-only —— 双边纯追加/真实分歧一律"本地保留 +
//   远端留存 fork 文件"，绝不静默覆盖（lib/merge.mjs 语义 + 测试）。
// - 自动模式：autoPullOnStart / autoPushOnTurnEnd（turn/end 会话事件）/
//   pullIntervalMinutes（周期拉取；定时与事件全部走 ctx.effect 可逆）。
// - 同步元数据（设备 id、最近推/拉、最近错误）存 ctx.storageDomain 领域
//   'session-sync'。
//
// 只消费公开服务：sessions / commands / storageDomain / subprocess（inject
// 声明），tools 经 ctx.inject 可选注册；userQuestions / approval 可选查找
// （缺失 = 失败关闭）。lib/ 零 DSH 依赖，服务只在边界接线。

import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, realpathSync, rmdirSync, writeFileSync } from 'node:fs'
import { readFile, readdir, rm, stat } from 'node:fs/promises'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Context } from '@deepseek-ai/cordis'
import Schema from '@deepseek-ai/schemastery'
import SessionStore, { KNOWN_SESSION_EVENT_TYPES } from '@deepseek-ai/dsh-session'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { defineTool } from '@deepseek-ai/dsh-tools'
import {
  API_FALLBACK_COOLDOWN_MS,
  BACKENDS,
  COMMAND_NAME,
  CONFIG_KEYS,
  CONFIRM_CHANNELS,
  DEFAULTS,
  LIMITS,
  MIRROR_DIR,
  PANEL_SEAT_MODES,
  PLUGIN_NAME,
  PLUGIN_VERSION,
  SESSION_EVENTS,
  STATE_KEY,
  TOOL_DOCTOR,
  TOOL_PEER,
  TOOL_PULL,
  TOOL_PUSH,
  TOOL_STATUS,
} from './lib/constants.mjs'
import {
  badConfig,
  backendUnsupported,
  messageOf,
  registryUnavailable,
} from './lib/errors.mjs'
import {
  assertNestingSafe,
  resolveRepoDir,
  resolveSessionRoot,
  sessionIdOfForkPath,
} from './lib/paths.mjs'
import { GitBackend, clearStaleIndexLock } from './lib/git.mjs'
import { SyncEngine } from './lib/engine.mjs'
import { EncryptedBackend, encryptTree, decryptTree, mergeTrees } from './lib/encrypted.mjs'
import { selectEncryptionMode } from './lib/backend.mjs'
import { detectAge, ageEncrypt, ageDecrypt } from './lib/age.mjs'
import { collectStatus } from './lib/status.mjs'
import { renderDiff, renderPeers, renderPull, renderPush, renderStatus, errorValue } from './lib/render.mjs'
import { confirmSync, hasOpenTurn, makeEventGate, maybeAppendSessionEvent, openTurnFromProjection } from './lib/gate.mjs'
import { sessionSyncDomainSpec } from './lib/domain.mjs'
import { luwiBridgeFromEnv, luwiWorktreeProblem, makeLuwiBridgeRunner, readLuwiSyncConfig } from './lib/luwi-bridge-runner.mjs'
// Luwi 加 (2026-10-15): 面板写配置 (主人要"在 PC 面板里填仓库地址、拨开关")
import { applyConfigure, readSyncTree, syncConfigPath } from './lib/luwi-configure.mjs'
// Luwi 加 (2026-10-20): 地址 + 令牌 → 真正给 git 用的地址 (令牌在面板上单独一格)
import { composeRemoteWithToken } from './lib/luwi-remote.mjs'
import { adoptMergedSessions } from './lib/luwi-adopt.mjs'
import { resolveWorkspaceSources, slugSegment } from './lib/luwi-workspace.mjs'
import { readPeerCards, writePeerCard } from './lib/luwi-peers.mjs'
// Luwi 加 (2026-10-20 同步改造): 扫码配对 / 会话基线 / 对端工作区影子
import { captureSessionBaseline, describeBaseline, localStillOld, oldMirrorPaths, scanSessionFiles } from './lib/luwi-baseline.mjs'
import { encodePairing, pairingPayloadFromConfig, pairingSummary, remoteHostOf } from './lib/luwi-pairing.mjs'
import { qrSvg } from './lib/qr-svg.mjs'
import { materializeSyncShadows, readWorkspaceCards, writeWorkspaceCard } from './lib/luwi-sync-shadow.mjs'
// Luwi 加 (G1-2 对端): 通道的配置面 (peer/live 两段)、投影算术与运行时
import { resolveLiveSpec, resolvePeerSpec, annotateWorkspaceNames, shadowDirName } from './lib/luwi-peer.mjs'
import { condense, liveSnapshot, LIVE_SNAPSHOT_VERSION, LIVE_SNAPSHOT_VERSION_LEGACY } from './lib/luwi-live.mjs'
import { appendTranscriptEntries, transcriptDir } from './lib/luwi-transcript.mjs'
import { createPeerRuntime } from './lib/luwi-peer-runtime.mjs'
import { makeGithubApi, looksLikeNetworkFailure } from './lib/github-api.mjs'
import { appendProxyEvents, proxyEventsFrom, proxyNoticeEvents, proxyTitleOf, proxyUserMessage } from './lib/luwi-proxy.mjs'
// Luwi 加 (G1 收口): 只读自检的纯函数 —— peer 落点 / ② 层 extraneous / 汇总文本
import { formatDoctor, runDoctor } from './lib/luwi-doctor.mjs'
// Luwi 加 (P2P T2): sidecar 监管与它的配置段 —— 只定位/起停/读状态, 不实现同步协议
import { P2pSidecar } from './lib/p2p-sidecar.mjs'
import { P2pService } from './lib/p2p-service.mjs'
import { confirmPeer, readTrust, revokePeer, writeTrust } from './lib/p2p-trust.mjs'
import { resolveP2pConfig } from './lib/p2p-config.mjs'
// Luwi 加 (G1 面板): 官方侧边栏那块操作面板的宿主半 (同源 HTTP 数据通道)
import { createPanel, describeRemote, PANEL_PREFIX, PANEL_TARGETS } from './lib/panel-routes.mjs'
// Luwi 加 (2026-10-23): 账号登录与权益 (本地验签 + 7 天宽限 + 到期闸)
import {
  accountBaseOf,
  accountRequest,
  clearLicenseFile,
  licensePathOf,
  loginRequiredForPairing,
  loginUrlOf,
  newLoginState,
  planAllowsP2p,
  readLicenseFile,
  statusOf,
  storedFromPoll,
  writeLicenseFile,
} from './lib/luwi-license.mjs'

export const name = PLUGIN_NAME

/** 必需服务：缺失即加载失败（响亮）。 */
export const inject = ['sessions', 'commands', 'storageDomain', 'subprocess']

/**
 * 宿主 append 是否盖章 ignorable 信封（运行时能力探测）。
 * 在全新 detached Context 上构造 SessionStore（绝不接入宿主持久化，探测
 * 会话不落盘）：追加一条带 { ignorable: true } 的探测事件并回读信封标记。
 * rc.6 的 append 静默丢弃未知选项键；rc.8/rc.2 的 append 对非 surface 类型不接受
 * 信封选项（ignorable 只是读取路径的标记）→ 探测事件均无 ignorable →
 * false（门保持关闭）；
 * append 会盖章 ignorable 信封的宿主 → true（sync/* 以 ignorable 落盘）。
 * @returns {boolean} 宿主支持 ignorable 信封。
 */
export function probeIgnorableAppend() {
  try {
    const store = new SessionStore(new Context())
    const session = store.create()
    const event = session.append(SESSION_EVENTS.PUSH, {
      sessionId: 'probe',
      ok: true,
      pushed: true,
      head: 'probe',
      // @ts-ignore non-surface append takes no envelope option — probing it is the point.
    }, /** @type {any} */ ({ ignorable: true }))
    return event?.ignorable === true
  } catch {
    return false
  }
}

/**
 * 插件配置（Schemastery，全部可 cordis.yml 覆盖；无硬编码 tunable）。
 * @typedef {object} Config
 * @property {boolean} [enabled] 整体开关；false 时不注册任何东西。
 * @property {string} [backend] 同步后端词汇；只有 'git' 实现，其余（加密后端）预留并响亮失败。
 * @property {string} [sessionRoot] 会话存储根；空 = $DSH_HOME/sessions（两者皆缺响亮失败）。
 * @property {string} [repoDir] 同步 git 工作树根；空 = $DSH_HOME/dsh-session-sync/repo。
 * @property {string} [remote] 远端地址（自定义 remote；pull/push 前必须非空，status/diff 不需要）。
 * @property {string} [branch] 远端分支名（默认 main）。
 * @property {string} [gitBin] git 可执行路径（默认 'git'）。
 * @property {string} [ageBin] age 可执行路径（默认 'age'；backend: encrypted 时探测，缺失降级明文）。
 * @property {string} [ageRecipient] age 收件人（公钥/身份串；空 = 无法加密，降级明文）。
 * @property {string} [ageIdentity] age 身份文件路径（无口令私钥；空 = 无法解密，降级明文）。
 * @property {boolean} [autoPullOnStart] 挂载时自动拉取一次（配置即授权，不再确认）。
 * @property {boolean} [autoPushOnTurnEnd] 每个 turn/end 后自动推送（配置即授权）。
 * @property {number} [pullIntervalMinutes] 周期自动拉取间隔分钟（0 = 关闭）。
 * @property {'auto'|'userQuestions'|'approval'} [confirmVia] 确认通道（auto 优先 userQuestions）。
 * @property {number} [graceMs] git 子进程终止宽限（ms）。
 * @property {number} [commandTimeoutMs] 单条 git 命令超时（ms）。
 * @property {number} [maxOutputBytes] git 输出单流收集上限（字节）。
 * @property {string} [commitName] 插件提交者名。
 * @property {string} [commitEmail] 插件提交者邮箱。
 * @property {string} [deviceName] Luwi 加 (G1-D)：自己名片上的名字（空 = 名片无名）。
 * @property {string} [publicUrl] Luwi 加 (G1-D)：自己 GUI 的地址（空 = 对端打不开这一边）。
 * @property {boolean} [registerCommand] 注册 /sync 命令。
 * @property {boolean} [registerTools] tools 服务存在时注册 sync_* 工具。
 * @property {'both'|'right'|'left'|'off'} [panelSeats] Luwi 加 (G1 面板)：浏览器半装哪些座位（off = 关掉面板）。
 * @property {boolean} [panelOps] Luwi 加 (G1 面板)：面板能不能发起 pull/push（false = 只留读面）。
 */
// Service Definition — Config: the plugin's public configuration contract
// (Schemastery schema). 校验仍然在加载期做, 但**一项都不带 `.default`**:
// 装载器会把 schema 的缺省值灌满 config, 于是"显式写了 false"与"没写"再也分不开 ——
// 2026-10-13 手机侧就是被这一条坑的 (JSON 配置垫在下面等于一个字都进不来, 启动行
// 一直 remote=unset)。去掉缺省之后 config 里只剩真的写过的键, 三层合并 (C8) 才成立:
// 显式 cordis 键 > $DSH_HOME/lw/session-sync.json > DEFAULTS。缺省值统一由 DEFAULTS 出
export const Config = Schema.object({
  enabled: Schema.boolean(),
  backend: Schema.union(Object.values(BACKENDS)),
  sessionRoot: Schema.string(),
  repoDir: Schema.string(),
  remote: Schema.string(),
  branch: Schema.string(),
  gitBin: Schema.string(),
  ageBin: Schema.string(),
  ageRecipient: Schema.string(),
  ageIdentity: Schema.string(),
  autoPullOnStart: Schema.boolean(),
  autoPushOnTurnEnd: Schema.boolean(),
  pullIntervalMinutes: Schema.number(),
  confirmVia: Schema.union(Object.values(CONFIRM_CHANNELS)),
  graceMs: Schema.number(),
  commandTimeoutMs: Schema.number(),
  maxOutputBytes: Schema.number(),
  commitName: Schema.string(),
  commitEmail: Schema.string(),
  // Luwi 加 (G1-D): 自己名片的两个字段
  deviceName: Schema.string(),
  publicUrl: Schema.string(),
  registerCommand: Schema.boolean(),
  registerTools: Schema.boolean(),
  // Luwi 加 (G1 面板): 座位与写面
  panelSeats: Schema.union(PANEL_SEAT_MODES),
  panelOps: Schema.boolean(),
  // Luwi 加 (P2P T2): P2P 传输层的配置段 (enabled / power / heartbeatMs / idleMs / ...)
  // 具体形状由 lib/p2p-config.mjs 的 resolveP2pConfig 校验, 这里只放行, 不做一层重复的 schema
  p2p: Schema.any(),
})

/**
 * 显式补齐默认 + 加载期校验（非法配置响亮失败）。
 * @param {Partial<Config>|undefined} config - cordis loader 传入的配置。
 * @returns {Required<Config>} 校验后的配置。
 */
export function resolveConfig(config = {}) {
  const resolved = {
    enabled: config.enabled ?? DEFAULTS.ENABLED,
    backend: config.backend ?? DEFAULTS.BACKEND,
    sessionRoot: config.sessionRoot ?? DEFAULTS.SESSION_ROOT,
    repoDir: config.repoDir ?? DEFAULTS.REPO_DIR,
    remote: config.remote ?? DEFAULTS.REMOTE,
    branch: config.branch ?? DEFAULTS.BRANCH,
    gitBin: config.gitBin ?? DEFAULTS.GIT_BIN,
    ageBin: config.ageBin ?? DEFAULTS.AGE_BIN,
    ageRecipient: config.ageRecipient ?? DEFAULTS.AGE_RECIPIENT,
    ageIdentity: config.ageIdentity ?? DEFAULTS.AGE_IDENTITY,
    autoPullOnStart: config.autoPullOnStart ?? DEFAULTS.AUTO_PULL_ON_START,
    autoPushOnTurnEnd: config.autoPushOnTurnEnd ?? DEFAULTS.AUTO_PUSH_ON_TURN_END,
    pullIntervalMinutes: config.pullIntervalMinutes ?? DEFAULTS.PULL_INTERVAL_MINUTES,
    confirmVia: config.confirmVia ?? DEFAULTS.CONFIRM_VIA,
    graceMs: config.graceMs ?? DEFAULTS.GRACE_MS,
    commandTimeoutMs: config.commandTimeoutMs ?? DEFAULTS.COMMAND_TIMEOUT_MS,
    maxOutputBytes: config.maxOutputBytes ?? DEFAULTS.MAX_OUTPUT_BYTES,
    commitName: config.commitName ?? DEFAULTS.COMMIT_NAME,
    commitEmail: config.commitEmail ?? DEFAULTS.COMMIT_EMAIL,
    // Luwi 加 (G1-D): 自己名片的两个字段
    deviceName: config.deviceName ?? DEFAULTS.DEVICE_NAME,
    publicUrl: config.publicUrl ?? DEFAULTS.PUBLIC_URL,
    registerCommand: config.registerCommand ?? DEFAULTS.REGISTER_COMMAND,
    registerTools: config.registerTools ?? DEFAULTS.REGISTER_TOOLS,
    // Luwi 加 (G1 面板)
    panelSeats: config.panelSeats ?? DEFAULTS.PANEL_SEATS,
    panelOps: config.panelOps ?? DEFAULTS.PANEL_OPS,
    // Luwi 加 (P2P T2): p2p 段与 workspace / peer / live 同一个形制 —— 主要住 JSON 层,
    // 非法值在加载期响亮失败 (BAD_CONFIG), apply 那边由 tryResolveConfig 接住并停用
    p2p: resolveP2pConfig(config.p2p),
  }
  if (resolved.enabled === false) return resolved
  if (!Object.values(BACKENDS).includes(/** @type {any} */ (resolved.backend))) {
    throw backendUnsupported(/** @type {any} */ (resolved.backend))
  }
  if (!Object.values(CONFIRM_CHANNELS).includes(resolved.confirmVia)) {
    throw badConfig(`confirmVia ${JSON.stringify(resolved.confirmVia)} must be one of auto|userQuestions|approval`)
  }
  if (typeof resolved.gitBin !== 'string' || resolved.gitBin.length === 0) {
    throw badConfig('gitBin must be a non-empty string')
  }
  if (typeof resolved.ageBin !== 'string' || resolved.ageBin.length === 0) {
    throw badConfig('ageBin must be a non-empty string')
  }
  if (typeof resolved.ageRecipient !== 'string') {
    throw badConfig('ageRecipient must be a string')
  }
  if (typeof resolved.ageIdentity !== 'string') {
    throw badConfig('ageIdentity must be a string')
  }
  if (typeof resolved.branch !== 'string' || resolved.branch.length === 0 || !/^[A-Za-z0-9._/-]+$/u.test(resolved.branch)) {
    throw badConfig('branch must be a non-empty ref-safe string')
  }
  if (!Number.isFinite(resolved.graceMs) || resolved.graceMs < LIMITS.MIN_GRACE_MS || resolved.graceMs > LIMITS.MAX_GRACE_MS) {
    throw badConfig(`graceMs must be an integer in [${LIMITS.MIN_GRACE_MS}, ${LIMITS.MAX_GRACE_MS}]`)
  }
  if (!Number.isFinite(resolved.commandTimeoutMs) || resolved.commandTimeoutMs < LIMITS.MIN_COMMAND_TIMEOUT_MS || resolved.commandTimeoutMs > LIMITS.MAX_COMMAND_TIMEOUT_MS) {
    throw badConfig(`commandTimeoutMs must be an integer in [${LIMITS.MIN_COMMAND_TIMEOUT_MS}, ${LIMITS.MAX_COMMAND_TIMEOUT_MS}]`)
  }
  if (!Number.isFinite(resolved.maxOutputBytes) || resolved.maxOutputBytes < LIMITS.MIN_MAX_OUTPUT_BYTES || resolved.maxOutputBytes > LIMITS.MAX_MAX_OUTPUT_BYTES) {
    throw badConfig(`maxOutputBytes must be an integer in [${LIMITS.MIN_MAX_OUTPUT_BYTES}, ${LIMITS.MAX_MAX_OUTPUT_BYTES}]`)
  }
  if (!Number.isInteger(resolved.pullIntervalMinutes) || resolved.pullIntervalMinutes < LIMITS.MIN_PULL_INTERVAL_MINUTES || resolved.pullIntervalMinutes > LIMITS.MAX_PULL_INTERVAL_MINUTES) {
    throw badConfig(`pullIntervalMinutes must be an integer in [${LIMITS.MIN_PULL_INTERVAL_MINUTES}, ${LIMITS.MAX_PULL_INTERVAL_MINUTES}]`)
  }
  if (typeof resolved.commitName !== 'string' || resolved.commitName.trim().length === 0) {
    throw badConfig('commitName must be a non-empty string')
  }
  if (typeof resolved.commitEmail !== 'string' || resolved.commitEmail.trim().length === 0) {
    throw badConfig('commitEmail must be a non-empty string')
  }
  // Luwi 加 (G1-D): 名片两个字段的加载期校验 —— name 随便什么话都行 (限长), url 必须长得像
  // http(s) (设置页 ⋮ 的 WebView 只载 http/https, 配出别的值就是一行永远打不开的死行)
  if (typeof resolved.deviceName !== 'string') {
    throw badConfig('deviceName must be a string')
  }
  if (resolved.deviceName.length > 200) {
    throw badConfig('deviceName must be at most 200 characters')
  }
  if (typeof resolved.publicUrl !== 'string') {
    throw badConfig('publicUrl must be a string')
  }
  if (resolved.publicUrl !== '' && !/^https?:\/\//iu.test(resolved.publicUrl.trim())) {
    throw badConfig('publicUrl must be an http(s) URL (the peer rows open it in a WebView)')
  }
  if (resolved.sessionRoot === '' && process.env.DSH_HOME === undefined) {
    throw badConfig('sessionRoot is empty and $DSH_HOME is not set; run under dsh or set sessionRoot explicitly')
  }
  if (resolved.repoDir === '' && process.env.DSH_HOME === undefined) {
    throw badConfig('repoDir is empty and $DSH_HOME is not set; run under dsh or set repoDir explicitly')
  }
  // Luwi 加 (G1 面板): 座位词汇的加载期校验 —— 拼错一个值就是"面板整个不见", 响亮失败比静默好
  if (!PANEL_SEAT_MODES.includes(resolved.panelSeats)) {
    throw badConfig(`panelSeats ${JSON.stringify(resolved.panelSeats)} must be one of ${PANEL_SEAT_MODES.join('|')}`)
  }
  if (typeof resolved.panelOps !== 'boolean') {
    throw badConfig('panelOps must be a boolean')
  }
  return resolved
}

/**
 * Luwi 加 (G1 收口, C2): 接住 resolveConfig 的抛错, 让加载期有一条"不抛"的路。
 *
 * resolveConfig 保留原契约 (测试与 doctor 用它的抛错), 但 apply() 走这一条:
 * 配置非法 = 一行 error + 插件停用, 绝不把整个 profile 加载拖下去
 * @param {Partial<Config>|undefined} config - 合并后的配置。
 * @returns {{ok: true, config: Required<Config>}|{ok: false, error: Error}} 结果。
 */
export function tryResolveConfig(config = {}) {
  try {
    return { ok: true, config: resolveConfig(config) }
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error : new Error(String(error)) }
  }
}

/**
 * Luwi 加 (G1 收口, C6): 单进程双挂载的记号。
 *
 * `/sync doctor` 与启动行都读它; bundle 行 (包自带 patch) 与 profile 的 `- insert:` 行
 * 同时存在时会挂两次, 而两个引擎共用一个 repoDir 是真会互相穿插 git 的 —— 第二次直接拒
 * @type {symbol} 挂载记号 (Symbol.for: 同一进程里同一份包被两份拷贝加载时也认同一个记号)。
 */
export const MOUNT_KEY = Symbol.for('luwi.dsh.sync.mount')

/**
 * 挂载状态 (只读面用; doctor 与启动行从这里取事实)。
 * @type {{mounted: boolean, disabledReason?: string, resolved?: Required<Config>, runner?: string}}
 */
export const mountState = { mounted: false }

/**
 * git runner：ctx.subprocess 上的 collect/pipe 封装。
 * binary=true 时 stdout 走原始字节管道（读 blob 必需），否则 collect 文本。
 * @param {import('@deepseek-ai/dsh-subprocess').SubprocessRuntime} subprocess - 宿主子进程服务。
 * @param {object} opts - {gitBin, graceMs, commandTimeoutMs, maxOutputBytes}。
 * @returns {(args: string[], runOpts?: object) => Promise<{code: number, stdout: string|Buffer, stderr: string}>}
 */
export function makeRunGit(subprocess, opts) {
  return async (args, runOpts = {}) => {
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort(new Error(`git ${args[0] ?? ''} timed out after ${opts.commandTimeoutMs}ms`))
    }, opts.commandTimeoutMs)
    const onAbort = () => {
      controller.abort(runOpts.signal?.reason instanceof Error ? runOpts.signal.reason : new Error('sync operation aborted'))
    }
    if (runOpts.signal !== undefined) {
      runOpts.signal.addEventListener('abort', onAbort, { once: true })
    }
    try {
      const executable = await subprocess.resolveExecutable(opts.gitBin, undefined, controller.signal)
      const handle = subprocess.spawn({
        argv: [executable, ...args],
        cwd: runOpts.cwd,
        stdio: {
          stdin: 'ignore',
          stdout: runOpts.binary === true ? 'pipe' : { maxBytes: opts.maxOutputBytes },
          stderr: { maxBytes: opts.maxOutputBytes },
        },
        graceMs: opts.graceMs,
        signal: controller.signal,
        env: {
          GIT_TERMINAL_PROMPT: '0',
          GIT_OPTIONAL_LOCKS: '0',
        },
      })
      let raw
      if (runOpts.binary === true) {
        const chunks = []
        for await (const chunk of handle.stdout) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)))
        raw = Buffer.concat(chunks)
      }
      const outcome = await handle.done
      if (timedOut) {
        throw new Error(`git ${args[0] ?? ''} timed out after ${opts.commandTimeoutMs}ms`)
      }
      if (runOpts.signal?.aborted === true) {
        throw runOpts.signal.reason instanceof Error ? runOpts.signal.reason : new Error('sync operation aborted')
      }
      const stdout = raw ?? handle.collected.stdout.readFrom(0).text
      const stderr = handle.collected.stderr.readFrom(0).text
      return { code: outcome.exitCode ?? (outcome.signal !== null ? 128 : 0), stdout, stderr }
    } finally {
      clearTimeout(timer)
      if (runOpts.signal !== undefined) {
        runOpts.signal.removeEventListener('abort', onAbort)
      }
    }
  }
}

/**
 * age runner：ctx.subprocess 上的 collect 封装（与 makeRunGit 同构）。
 * args 为「含可执行路径的完整 argv」（lib/age.mjs 的 runner 契约），只做文本
 * 输出收集与退出码/超时/取消语义；age 加解密经文件入参（-o out in），无需 stdin。
 * @param {import('@deepseek-ai/dsh-subprocess').SubprocessRuntime} subprocess - 宿主子进程服务。
 * @param {object} opts - {graceMs, commandTimeoutMs, maxOutputBytes}。
 * @returns {(args: string[], runOpts?: object) => Promise<{code: number, stdout: string, stderr: string}>}
 */
export function makeRunAge(subprocess, opts) {
  return async (args, runOpts = {}) => {
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort(new Error(`age ${args[0] ?? ''} timed out after ${opts.commandTimeoutMs}ms`))
    }, opts.commandTimeoutMs)
    const onAbort = () => {
      controller.abort(runOpts.signal?.reason instanceof Error ? runOpts.signal.reason : new Error('sync operation aborted'))
    }
    if (runOpts.signal !== undefined) {
      runOpts.signal.addEventListener('abort', onAbort, { once: true })
    }
    try {
      const executable = await subprocess.resolveExecutable(args[0], undefined, controller.signal)
      const handle = subprocess.spawn({
        argv: [executable, ...args.slice(1)],
        cwd: runOpts.cwd,
        stdio: {
          stdin: 'ignore',
          stdout: { maxBytes: opts.maxOutputBytes },
          stderr: { maxBytes: opts.maxOutputBytes },
        },
        graceMs: opts.graceMs,
        signal: controller.signal,
      })
      const outcome = await handle.done
      if (timedOut) {
        throw new Error(`age ${args[0] ?? ''} timed out after ${opts.commandTimeoutMs}ms`)
      }
      if (runOpts.signal?.aborted === true) {
        throw runOpts.signal.reason instanceof Error ? runOpts.signal.reason : new Error('sync operation aborted')
      }
      const stdout = handle.collected.stdout.readFrom(0).text
      const stderr = handle.collected.stderr.readFrom(0).text
      return { code: outcome.exitCode ?? (outcome.signal !== null ? 128 : 0), stdout, stderr }
    } finally {
      clearTimeout(timer)
      if (runOpts.signal !== undefined) {
        runOpts.signal.removeEventListener('abort', onAbort)
      }
    }
  }
}

/**
 * 工具参数/输出描述（双语）。
 */
const TOOL_TEXT = {
  en: {
    statusDescription: 'Show the session-sync git mirror state: branch, sanitized remote, ahead/behind counts, uncommitted files, fork files from conflicts, and the last pull/push times. Read-only: never confirms, never touches the network.',
    pullDescription: 'Pull remote session changes into the local sync mirror with append-only keep-both merge semantics (local version kept, remote version preserved as fork files — never silently overwritten). Requires user confirmation.',
    pushDescription: 'Mirror local session files into the sync repo, commit, and push to the configured remote. Never force-pushes; a rejected push reconciles by pulling first and retries once. Requires user confirmation.',
    targetDescription: 'Which mirror to act on. sessions (the default) mirrors the session store. workspace (Luwi, G1-C) mirrors the dsh workspace directory into its own repository with an exclude list and a per-file size cap: a pull of that target also updates or removes the local files the merge changed, and anything it removes goes to <workspace>/.lw-sync/trash.',
  },
}

/**
 * 退下来的投影账最多记这么多条。
 *
 * 它只服务"主人正好开着某一场已经重建/回收掉的投影在打字"这一档 —— 那句话仍要送给归属方, 而不是
 * 就地跑本机模型。超出的按时间丢掉最老的 (投影的重建本来就是一次性的, 老账留着没有别的用处)
 */
const MAX_RETIRED_PROXIES = 200

/**
 * 单场会话日志的同步上限 (字节)。
 *
 * 一场聊了几百轮的会话能长到好几 MB, 而它每说一句就变一次 —— API 推送每趟都要重传整份,
 * 手机那边一条消息要等好几分钟 (小文件的队列消息被它拖着)。超过这个上限的会话不进仓库,
 * 面板状态里的 `oversized` 会把它列出来; 需要完整历史时再人工处理
 *
 * Luwi 改 (2026-10-22 主人: "放开到 16m"): 会话日志现在按 zstd 帧切段 (lib/luwi-parts.mjs),
 * 一趟推送只带增长的最末一段, "整份重传"这个理由就不成立了 —— 上限按"手机首次取回要下多少"
 * 来定, 16 MB 能覆盖绝大多数聊得很久的会话
 */
const SESSION_FILE_LIMIT_BYTES = 16 * 1024 * 1024

/** Luwi 加 (G1-C): 同步目标 —— 会话那根 (缺省) 与工作区那根。 */
export const TARGETS = Object.freeze({
  SESSIONS: 'sessions',  WORKSPACE: 'workspace',
})

/** sync_* 的公共参数: target。 */
const TARGET_PARAMETER = {
  type: 'string',
  enum: [TARGETS.SESSIONS, TARGETS.WORKSPACE],
  description: TOOL_TEXT.en.targetDescription,
}

/**
 * Luwi 加 (G1-C): target → 引擎。
 * 工作区那根没开时给一句人话 (fail closed, 不静默打到会话那根上)。
 * @param {{sessions: object, workspace?: object|null}} engines - 两根镜像的引擎。
 * @returns {(target?: string) => {ok: true, scope: string, engine: object, label: string}|{ok: false, error: string}} 解析器。
 */
export function makeTargetResolver(engines) {
  return (target) => {
    const wanted = typeof target === 'string' && target !== '' ? target : TARGETS.SESSIONS
    if (wanted === TARGETS.WORKSPACE) {
      if (engines.workspace === undefined || engines.workspace === null) {
        return { ok: false, error: 'the workspace mirror is off: set workspace.remote in $DSH_HOME/lw/session-sync.json, then restart the host' }
      }
      return { ok: true, scope: TARGETS.WORKSPACE, engine: engines.workspace, label: 'workspace' }
    }
    return { ok: true, scope: TARGETS.SESSIONS, engine: engines.sessions, label: '' }
  }
}

/** sync_status 工具规范结果（与工具 schema 同构）。 */
/** @typedef {{ok: boolean, branch: string, remote: string, head?: string, remoteHead?: string, ahead: number, behind: number, dirty: string[], forks: string[], diffStat: string, mergeInProgress: boolean, lastCommits: string[], lastPullAt?: number, lastPushAt?: number, lastError?: string, error?: string, warnings?: string[]}} StatusValue */
/** @typedef {{ok: boolean, pulled?: boolean, merged?: boolean, adopted?: number, appended?: number, diverged?: number, forks?: string[], head?: string, error?: string, warnings?: string[]}} PullValue */
/** @typedef {{ok: boolean, pushed?: boolean, head?: string, mirrored?: number, deleted?: number, remote?: string, error?: string, warnings?: string[]}} PushValue */

/**
 * 工具结果必须是**无损 JSON**：值是 `undefined` 的键会被 `JSON.stringify` 丢掉, 而宿主按
 * 无损判据校验工具结果 (`value is not lossless JSON`) —— 整条调用会失败。
 * `collectStatus` 与引擎本来就爱把"没有"写成显式的 `undefined` (`head` / `dirty` / `error` …),
 * 所以在**工具边界**统一剔掉它们, 而不是去改那些共享的读数构造 (那会动到 /sync 命令与测试)
 * @param {object} value - 引擎/状态对象。
 * @returns {object} 浅拷贝, 只去掉值为 undefined 的键。
 */
export function losslessStatus(value) {
  const out = {}
  for (const [key, item] of Object.entries(value ?? {})) {
    if (item !== undefined) out[key] = item
  }
  return out
}

/**
 * sync_status 工具定义（read-only：不确认、不触网）。
 * @param {(target?: string) => object} resolveTarget - Luwi 加：target → 引擎。
 * @param {(scope: string) => Promise<object>} readMeta - 元数据读取（按 scope）。
 * @param {(scope: string) => Promise<number>} [readPeerCount] - Luwi 加 (G1-D)：镜像里的名片数
 *   （按 scope；不传或读失败就没有 peerCount 字段, 工具结果形状保持上游）。
 * @returns {object} 工具定义。
 */
export function makeSyncStatusTool(resolveTarget, readMeta, readPeerCount) {
  return defineTool({
    name: TOOL_STATUS,
    description: TOOL_TEXT.en.statusDescription,
    parameters: { target: TARGET_PARAMETER },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          branch: { type: 'string' },
          remote: { type: 'string' },
          head: { type: 'string' },
          remoteHead: { type: 'string' },
          ahead: { type: 'integer' },
          behind: { type: 'integer' },
          dirty: { type: 'array', items: { type: 'string' } },
          forks: { type: 'array', items: { type: 'string' } },
          diffStat: { type: 'string' },
          mergeInProgress: { type: 'boolean' },
          lastCommits: { type: 'array', items: { type: 'string' } },
          lastPullAt: { type: 'integer' },
          lastPushAt: { type: 'integer' },
          lastError: { type: 'string' },
          error: { type: 'string' },
          warnings: { type: 'array', items: { type: 'string' } },
          // Luwi 加 (G1-C)：工作区那根的镜像目录名与两笔跳过账
          mirrorDir: { type: 'string' },
          excluded: { type: 'array', items: { type: 'string' } },
          excludedCount: { type: 'integer' },
          oversized: { type: 'array', items: { type: 'string' } },
          oversizedCount: { type: 'integer' },
          // Luwi 加 (G1-D)：镜像里见过的对端名片数
          peerCount: { type: 'integer' },
          // Luwi 加 (2026-10-20): 一个仓库里的每一根源各一条读数 (缺了它们工具结果会被判非法)
          sources: {
            type: 'array',
            items: {
              type: 'object',
              additionalProperties: false,
              properties: {
                label: { type: 'string' },
                mirrorDir: { type: 'string' },
                mirrored: { type: 'integer' },
                unchanged: { type: 'integer' },
                deleted: { type: 'integer' },
                excludedCount: { type: 'integer' },
                oversizedCount: { type: 'integer' },
                beforeBaseline: { type: 'integer' },
                neverSynced: { type: 'integer' },
                skipped: { type: 'integer' },
                budget: {
                  type: 'object',
                  additionalProperties: false,
                  properties: {
                    maxFiles: { type: 'integer' },
                    maxBytes: { type: 'integer' },
                  },
                },
              },
            },
          },
          baseline: {
            type: 'object',
            additionalProperties: false,
            properties: {
              beforeBaseline: { type: 'integer' },
              neverSynced: { type: 'integer' },
            },
          },
        },
      },
      render(args, value) {
        return [{ type: 'text', text: renderStatus(value, args?.target === TARGETS.WORKSPACE ? 'workspace' : '') }]
      },
    },
    async execute(args, exec) {
      exec.signal?.throwIfAborted()
      const picked = resolveTarget(args?.target)
      if (!picked.ok) return errorValue(picked.error)
      const status = await picked.engine.status()
      try {
        const meta = await readMeta(picked.scope)
        let peerCount
        if (typeof readPeerCount === 'function') {
          try {
            peerCount = await readPeerCount(picked.scope)
          } catch {
            // 名片只读失败不阻断状态展示。
          }
        }
        return losslessStatus({ ...status, lastPullAt: meta.lastPullAt, lastPushAt: meta.lastPushAt, lastError: meta.lastError, peerCount })
      } catch {
        return losslessStatus(status)
      }
    },
  })
}

/**
 * sync_pull 工具定义（操作走确认门；execute 尊重 exec.signal）。
 * @param {object} deps - {resolveTarget, readMeta, ctx, confirmVia}。
 * @returns {object} 工具定义。
 */
export function makeSyncPullTool(deps) {
  return defineTool({
    name: TOOL_PULL,
    description: TOOL_TEXT.en.pullDescription,
    parameters: { target: TARGET_PARAMETER },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          pulled: { type: 'boolean' },
          merged: { type: 'boolean' },
          adopted: { type: 'integer' },
          // Luwi 加：取回环的读数 (brought)，工作区那根的更新/回收站两条 (updated/trashed)
          brought: { type: 'integer' },
          updated: { type: 'integer' },
          trashed: { type: 'integer' },
          // Luwi 加 (2026-10-22): 段集合那一路 —— 还在落的组 / 这一趟搬的组与字节
          deferred: { type: 'integer' },
          partSets: { type: 'integer' },
          partSetsTaken: { type: 'integer' },
          partSetsDeferred: { type: 'integer' },
          partsFetched: { type: 'integer' },
          bytesFetched: { type: 'integer' },
          appended: { type: 'integer' },
          diverged: { type: 'integer' },
          forks: { type: 'array', items: { type: 'string' } },
          head: { type: 'string' },
          error: { type: 'string' },
          warnings: { type: 'array', items: { type: 'string' } },
        },
      },
      render(args, value) {
        return [{ type: 'text', text: renderPull(value, args?.target === TARGETS.WORKSPACE ? 'workspace' : '') }]
      },
    },
    async execute(args, exec) {
      exec.signal?.throwIfAborted()
      const picked = deps.resolveTarget(args?.target)
      if (!picked.ok) return errorValue(picked.error)
      const where = picked.label === '' ? 'the local sync mirror' : `the ${picked.label} mirror`
      const verdict = await confirmSync(
        { ctx: deps.ctx, confirmVia: deps.confirmVia, action: 'pull', summary: `Download remote changes into ${where} (keep-both merge; local files it changes are updated, ones it deletes go to the trash).` },
        exec.agent,
        exec.signal,
      )
      if (!verdict.allowed) {
        return errorValue(verdict.reason ?? 'confirmation failed (fail closed)')
      }
      const result = await picked.engine.pull({ signal: exec.signal })
      if (exec.agent?.session !== null && exec.agent?.session !== undefined) {
        maybeAppendSessionEvent(exec.agent.session, SESSION_EVENTS.PULL, {
          sessionId: exec.agent.session.id,
          ok: result.ok,
          error: result.error,
          pulled: result.pulled,
          merged: result.merged,
          adopted: result.adopted,
          appended: result.appended,
          diverged: result.diverged,
          forks: result.forks,
          head: result.head,
        }, deps.eventGate, (message) => deps.logger.warn(message))
      }
      return losslessStatus(result)
    },
  })
}

/**
 * sync_push 工具定义（操作走确认门；execute 尊重 exec.signal）。
 * @param {object} deps - {resolveTarget, ctx, confirmVia, eventGate, logger}。
 * @returns {object} 工具定义。
 */
export function makeSyncPushTool(deps) {
  return defineTool({
    name: TOOL_PUSH,
    description: TOOL_TEXT.en.pushDescription,
    parameters: { target: TARGET_PARAMETER },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          pushed: { type: 'boolean' },
          head: { type: 'string' },
          mirrored: { type: 'integer' },
          deleted: { type: 'integer' },
          // Luwi 加 (2026-10-22): API 那条路这一趟上传的段数 (切段传输的成本读数)
          uploadedParts: { type: 'integer' },
          // Luwi 加 (G1-C)：排除表与单文件上限跳过的条数
          excluded: { type: 'integer' },
          oversized: { type: 'integer' },
          // Luwi 加 (2026-10-20): 首次同步那本基线账与每根源一行读数
          beforeBaseline: { type: 'integer' },
          neverSynced: { type: 'integer' },
          sources: {
            type: 'array',
            items: {
              type: 'object',
              additionalProperties: false,
              properties: {
                label: { type: 'string' },
                mirrorDir: { type: 'string' },
                mirrored: { type: 'integer' },
                unchanged: { type: 'integer' },
                deleted: { type: 'integer' },
                excludedCount: { type: 'integer' },
                oversizedCount: { type: 'integer' },
                beforeBaseline: { type: 'integer' },
                neverSynced: { type: 'integer' },
                skipped: { type: 'integer' },
                budget: {
                  type: 'object',
                  additionalProperties: false,
                  properties: {
                    maxFiles: { type: 'integer' },
                    maxBytes: { type: 'integer' },
                  },
                },
              },
            },
          },
          remote: { type: 'string' },
          error: { type: 'string' },
          warnings: { type: 'array', items: { type: 'string' } },
        },
      },
      render(args, value) {
        return [{ type: 'text', text: renderPush(value, args?.target === TARGETS.WORKSPACE ? 'workspace' : '') }]
      },
    },
    async execute(args, exec) {
      exec.signal?.throwIfAborted()
      const picked = deps.resolveTarget(args?.target)
      if (!picked.ok) return errorValue(picked.error)
      const where = picked.label === '' ? 'the sync remote' : `the ${picked.label} remote`
      const verdict = await confirmSync(
        { ctx: deps.ctx, confirmVia: deps.confirmVia, action: 'push', summary: `Upload local changes to ${where} (commit + push; never force-pushes).` },
        exec.agent,
        exec.signal,
      )
      if (!verdict.allowed) {
        return errorValue(verdict.reason ?? 'confirmation failed (fail closed)')
      }
      const result = await picked.engine.push({ signal: exec.signal })
      if (exec.agent?.session !== null && exec.agent?.session !== undefined) {
        maybeAppendSessionEvent(exec.agent.session, SESSION_EVENTS.PUSH, {
          sessionId: exec.agent.session.id,
          ok: result.ok,
          error: result.error,
          pushed: result.pushed,
          mirrored: result.mirrored,
          deleted: result.deleted,
          head: result.head,
        }, deps.eventGate, (message) => deps.logger.warn(message))
      }
      return losslessStatus(result)
    },
  })
}

/**
 * Luwi 加 (G1 收口 C9): sync_doctor 工具定义（只读：不确认、不触网、不写盘）。
 * @param {() => Promise<object>} collectDoctor - 收集报告。
 * @returns {object} 工具定义。
 */
export function makeSyncDoctorTool(collectDoctor) {
  return defineTool({
    name: TOOL_DOCTOR,
    description: 'Read-only self-check for luwi-dsh-sync: where each DeepSeek Harness peer package resolves from (the running installation, or a copy inside the profile that shadows it), whether the profile node_modules holds packages pnpm does not track, whether git can be spawned, and the effective mount state. Never confirms, never touches the network, never writes.',
    parameters: {},
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          status: { type: 'string' },
          report: { type: 'string', required: true },
        },
      },
      render(_args, value) {
        return [{ type: 'text', text: value.report }]
      },
    },
    async execute(_args, exec) {
      exec.signal?.throwIfAborted()
      const report = await collectDoctor()
      return { ok: true, status: report.status, report: formatDoctor(report) }
    },
  })
}

/**
 * Luwi 加 (G1-2 对端): sync_peer 工具定义。
 *
 * 三个动作, 都只是"一句话 + 几个读数":
 *   status  读通道状态 (开没开 / 地址 / 可达 / 队列 / 令牌打码)
 *   send    给对端的 agent 发一句话 (主路 HTTP, 打不通落队列; 回执说清走的哪条)
 *   live    看对端正在跑的那几场里的回答 (只文本 —— 思考与工具不过这条通道)
 * @param {{status: Function, send: Function, peerLive: Function}} peerRuntime - 对端运行时。
 * @returns {object} 工具定义。
 */
export function makeSyncPeerTool(peerRuntime) {
  return defineTool({
    name: TOOL_PEER,
    description: 'Cross-device peer channel (luwi-dsh-sync): read the peer state, send ONE line of text to the agent on the other machine, or read that agent\'s in-progress answers (user and assistant text only — reasoning and tool traffic never cross this channel). Read-only except for send.',
    parameters: {
      action: {
        type: 'string',
        enum: ['status', 'send', 'live'],
        description: 'status reads this host\'s peer channel state; send delivers one line to the other machine; live reads the other machine\'s running sessions with their latest answers.',
      },
      text: { type: 'string', description: 'For action=send: the line to deliver, verbatim.' },
      sessionId: { type: 'string', description: 'For action=send: the target session id on the other machine.' },
      workspace: { type: 'string', description: 'For action=send: the target workspace name on the other machine (used when sessionId is absent; the newest session of that workspace runs the line, or a new one opens).' },
      fresh: { type: 'boolean', description: 'For action=send without sessionId: open a NEW session in that workspace on the other machine instead of reusing its most recent one.' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          action: { type: 'string' },
          via: { type: 'string' },
          sessionId: { type: 'string' },
          running: { type: 'boolean' },
          text: { type: 'string' },
          json: { type: 'string' },
          error: { type: 'string' },
        },
      },
      render(_args, value) {
        return [{ type: 'text', text: value.text ?? value.error ?? '' }]
      },
    },
    async execute(args, exec) {
      exec.signal?.throwIfAborted()
      const action = typeof args?.action === 'string' ? args.action : 'status'
      if (action === 'status') {
        const state = await peerRuntime.status()
        return losslessStatus({ ok: true, action, text: renderPeerState(state), json: JSON.stringify(state) })
      }
      if (action === 'live') {
        const live = await peerRuntime.peerLive()
        if (live.ok !== true) {
          return losslessStatus({ ok: false, action, error: live.error, text: `peer live failed: ${live.error}` })
        }
        return losslessStatus({ ok: true, action, text: renderPeerLive(live), json: JSON.stringify(live) })
      }
      if (action === 'send') {
        const text = typeof args?.text === 'string' ? args.text.trim() : ''
        if (text === '') return { ok: false, action, error: 'nothing to send: text is required', text: 'nothing to send: text is required' }
        const hasSession = typeof args?.sessionId === 'string' && args.sessionId !== ''
        const target = {
          // fresh: 名下的工作区里新开一场 (不复用最近那一场) —— 见 resolvePeerTarget
          kind: hasSession ? 'session' : (args?.fresh === true ? 'new' : 'workspace'),
          id: typeof args?.sessionId === 'string' ? args.sessionId : '',
          name: typeof args?.workspace === 'string' ? args.workspace : '',
        }
        const answer = await peerRuntime.send({ text, target })
        const where = answer.via === 'http' ? 'over http' : answer.via === 'queue' ? 'queued for the next sync' : 'not delivered'
        return losslessStatus({
          ok: answer.ok === true,
          action,
          via: answer.via,
          sessionId: answer.sessionId,
          running: answer.running,
          error: answer.error,
          text: answer.ok === true
            ? `sent to the peer ${where}${answer.sessionId === undefined || answer.sessionId === '' ? '' : ` (session ${answer.sessionId})`}`
            : `could not send to the peer: ${answer.error}`,
        })
      }
      return { ok: false, action, error: `unknown action ${JSON.stringify(action)} (expected status|send|live)` }
    },
  })
}

/**
 * 对端通道读数的人话 (工具结果的那一段)。
 * @param {object} state - peerRuntime.status() 的产物。
 * @returns {string} 文本。
 */
export function renderPeerState(state) {
  if (state?.enabled !== true) {
    return 'the peer channel is off: set peer.token (and optionally peer.url) in $DSH_HOME/lw/session-sync.json, then restart the host'
  }
  const lines = [
    `peer: on · mode ${state.mode ?? 'auto'} · listen ${state.listen === true ? 'yes' : 'no'} · proxy ${state.proxy === true ? 'yes' : 'no'} · token ${state.token}`,
    `  url: ${state.url?.state === 'set' ? state.url.display : 'unset'} (${state.urlSource})`,
    `  reachable: ${state.reachable}${state.lastError === '' ? '' : ` - ${state.lastError}`}`,
    `  counters: sent ${state.counters?.sent ?? 0} · received ${state.counters?.received ?? 0} · queued ${state.counters?.queued ?? 0} · delivered from the queue ${state.counters?.deliveredFromQueue ?? 0}`,
    `  queue: ${state.queue?.pending ?? 0} waiting · ${state.queue?.acks ?? 0} receipt(s) seen`,
  ]
  const live = state.live ?? {}
  lines.push(`  live projection: ${live.enabled === true ? 'on' : 'off'} (window ${live.maxEntries ?? '?'} entries / ${live.maxChars ?? '?'} chars)`)
  return lines.join('\n')
}

/**
 * 对端"进行中的回答"的人话。
 * @param {object} live - peerRuntime.peerLive() 的产物。
 * @returns {string} 文本。
 */
export function renderPeerLive(live) {
  const device = live?.device?.name || live?.device?.id || 'the peer'
  const running = Array.isArray(live?.live) ? live.live : []
  if (running.length === 0) return `${device} has nothing running right now (${Array.isArray(live?.sessions) ? live.sessions.length : 0} session(s) known)`
  const blocks = running.map((entry) => {
    const draft = entry.draft?.text ?? ''
    const tail = (Array.isArray(entry.entries) ? entry.entries.slice(-3) : []).map(item => `    ${item.role}: ${item.text.slice(0, 400)}`).join('\n')
    return [
      `  ${entry.sessionId}${entry.title === '' ? '' : ` (${entry.title})`}${entry.workspace === '' ? '' : ` in ${entry.workspace}`}`,
      tail,
      draft === '' ? '' : `    … ${draft.slice(0, 400)}`,
    ].filter(line => line !== '').join('\n')
  })
  return `${device} is running ${running.length} session(s):\n${blocks.join('\n')}`
}

/**
 * 向 fork 子会话注入冲突通知：说明远端版本已留存为 fork 文件、两边历史
 * 都完整。通知是持久的 user/message（本插件自有的 producer-owned source），
 * 派生历史会投影它。
 *
 * 宿主 0.1.7 起 `MessageSourceMap` 是**可合并扩展的联合**，没有共享的兜底
 * `plugin` kind；`{ kind: 'plugin' }` 已被退役（`session-format-v3-to-v4`
 * 的 `assertV4MessageSources` 直接拒收）。这里声明本插件自己的 kind —— 与
 * `types.d.ts` 的声明合并同名，缺一不可（见 test/source-readback.test.mjs）。
 * @param {import('@deepseek-ai/dsh-session').Session} child - fork 子会话。
 * @param {string[]} forkPaths - 本会话相关 fork 文件路径。
 * @param {string} parentId - 原会话 id。
 */
export function injectForkNotice(child, forkPaths, parentId) {
  const text = [
    'session-sync resolved a cross-device conflict by keeping both sides:',
    `the local lineage of session ${parentId} continues here (this forked session);`,
    'the remote version was preserved as fork file(s):',
    ...forkPaths.slice(0, 10).map(path => `  ${path}`),
    forkPaths.length > 10 ? `  … ${forkPaths.length - 10} more` : '',
    'Both histories are intact in the sync repository; nothing was overwritten.',
  ].filter(line => line.length > 0).join('\n')
  try {
    /** @type {import('@deepseek-ai/dsh-llm').UserMessage} 冲突通知消息（本插件 producer-owned source）。 */
    const notice = createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'dsh-session-sync', form: 'notice', summary: 'session-sync conflict fork' },
    })
    child.append('user/message', notice, { surfaceOp: 'append' })
  } catch (error) {
    // 通知是锦上添花：append 失败绝不能把一次成功的拉取变成失败。
    return
  }
}

/**
 * 插件挂载（内层）。`apply()` 是外面那层"绝不抛"的壳, 这里只管装配。
 *
 * C2: 配置非法 = 一行 error + 停用; C4: 载入期只读一份小 JSON 与打一行日志
 * @param {import('@deepseek-ai/cordis').Context} ctx - Cordis 上下文。
 * @param {Partial<Config>} [config] - 显式配置层（profile 覆盖行 / bundle patch）。
 */
function applyInner(ctx, config = {}) {
  // Luwi 改 (2)+(G1 收口 C8): 三层合并 —— 显式 cordis 键 > $DSH_HOME/lw/session-sync.json
  // > DEFAULTS。schema 已不带 .default, 所以 config 里只有真的写过的键: 写了 false 就是
  // false, 不会被 JSON 翻过来; 手机侧只写 JSON (overlay 行不带 config), 电脑侧只写 profile
  // 的覆盖行 —— 同一份代码两边都对
  // Luwi 修 (2026-10-15): home 从 sessionRoot 的父目录推 —— 桌面宿主 (Electron main) 的进程
  // env 里没有 DSH_HOME, 而 sessionRoot 一定拿得到 (显式键 > DSH_HOME, 都缺会响亮失败)。少了
  // 这一步 PC 侧的文件层永远是空的: 面板 (或手改文件) 写进去的 remote 与三档自动值都读不回来
  const configHome = (() => {
    const explicitSessionRoot = typeof config.sessionRoot === 'string' ? config.sessionRoot.trim() : ''
    if (explicitSessionRoot !== '') return dirname(explicitSessionRoot)
    return typeof process.env.DSH_HOME === 'string' ? process.env.DSH_HOME.trim() : ''
  })()
  const fileLayer = readLuwiSyncConfig(process.env, configHome)
  const merged = {}
  // Luwi 修 (G1 面板, 2026-10-14): 这一圈以前遍历 `Object.keys(DEFAULTS)` (UPPER_SNAKE)
  // 却拿它去 index 两层 camelCase 的字典 —— 每一次都是 undefined, 于是显式 cordis 键与
  // JSON 文件一个字都进不来, 全部落回 DEFAULTS。症状不响亮: profile 覆盖行里的
  // repoDir/remote 被无声吃掉 (启动行报 unset), 只在 resolveConfig 里校验的非法值
  // (`gitBin: ''` / `branch: 'bad branch'`) 则"挂上了"。键表从 DEFAULTS 机械推出 (CONFIG_KEYS)
  for (const key of CONFIG_KEYS) {
    if (config[key] !== undefined) {
      merged[key] = config[key]
      continue
    }
    if (fileLayer[key] !== undefined) merged[key] = fileLayer[key]
  }
  if (config.workspace !== undefined) merged.workspace = config.workspace
  else if (fileLayer.workspace !== undefined) merged.workspace = fileLayer.workspace
  // Luwi 加 (2026-10-20): sessions / pairing 两段与 workspace 同一个形制 (只住 JSON 层)
  if (config.sessions !== undefined) merged.sessions = config.sessions
  else if (fileLayer.sessions !== undefined) merged.sessions = fileLayer.sessions
  if (config.pairing !== undefined) merged.pairing = config.pairing
  else if (fileLayer.pairing !== undefined) merged.pairing = fileLayer.pairing
  // Luwi 加 (2026-10-20): 访问令牌单独一个键 (面板那一格), 与地址一起决定真正给 git 的地址
  if (config.remoteToken !== undefined) merged.remoteToken = config.remoteToken
  else if (fileLayer.remoteToken !== undefined) merged.remoteToken = fileLayer.remoteToken
  // Luwi 加 (G1-2): peer / live 两段与 workspace 同一个形制 —— 它们不在 schema 里, 所以
  // 三层合并要在这里显式带上 (JSON 层是手机侧唯一的配置面)
  if (config.peer !== undefined) merged.peer = config.peer
  else if (fileLayer.peer !== undefined) merged.peer = fileLayer.peer
  if (config.live !== undefined) merged.live = config.live
  else if (fileLayer.live !== undefined) merged.live = fileLayer.live
  // Luwi 加 (P2P T2): p2p 段与 peer/live 同一个形制 (主要住 JSON 层)
  if (config.p2p !== undefined) merged.p2p = config.p2p
  else if (fileLayer.p2p !== undefined) merged.p2p = fileLayer.p2p

  const logger = ctx.logger(PLUGIN_NAME)
  const warn = (message) => logger.warn(message)

  /**
   * 读 `pairing` 段 (形制与 app 的 SyncSettings 一致: `{v,id,at,from}`)。
   * 没有 / 形态不对都回 null —— 后者由调用方打一行 warn, 并退回旧的全量镜像
   * @param {unknown} raw - 合并后的 pairing 段。
   * @returns {{v: number, id: string, at: number, from: string}|null} 规格。
   */
  function normalizePairing(raw) {
    if (raw === undefined || raw === null) return null
    if (typeof raw !== 'object' || Array.isArray(raw)) return null
    const id = typeof raw.id === 'string' ? raw.id.trim() : ''
    if (!/^[0-9A-Za-z][0-9A-Za-z-]{7,127}$/u.test(id)) return null
    return {
      v: Number.isInteger(raw.v) ? raw.v : 1,
      id,
      at: Number.isFinite(raw.at) ? Number(raw.at) : 0,
      from: typeof raw.from === 'string' ? raw.from.slice(0, 64) : '',
    }
  }

  /**
   * 「当前对话」窗口 (分钟): 配对那一刻这段时间内还有写入的会话整场同步。
   * @param {unknown} raw - 合并后的 sessions 段。
   * @returns {number} 0..10080。
   */
  function readCurrentWindowMinutes(raw) {
    const value = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw.currentWindowMinutes : undefined
    if (!Number.isInteger(value) || value < 0 || value > 10080) return 60
    return value
  }

  /**
   * 工作区注册表里这个根叫什么 (标题决定仓库里的目录名); 找不到就用 basename。
   * 注册表是可选服务; 路径比较要折 canonical (junction / 软链接两套拼法)
   * @param {string} root - 工作区根。
   * @returns {string|undefined} 标题。
   */
  function workspaceTitleOf(root) {
    const registry = typeof ctx.get === 'function' ? ctx.get('workspaceRegistry') : undefined
    if (registry === undefined || typeof registry.list !== 'function') return undefined
    try {
      const target = canonicalOf(root)
      for (const entity of registry.list()) {
        const path = typeof entity?.path === 'string' ? entity.path : ''
        if (path === '') continue
        if (path === root || canonicalOf(path) === target) {
          return typeof entity.title === 'string' && entity.title.trim() !== '' ? entity.title.trim() : undefined
        }
      }
    } catch {
      // 注册表坏了只影响"目录叫什么", 不影响同步
    }
    return undefined
  }

  const attempt = tryResolveConfig(merged)
  if (!attempt.ok) {
    mountState.mounted = false
    mountState.disabledReason = messageOf(attempt.error)
    logger.error(`disabled: ${messageOf(attempt.error)}`)
    return
  }
  const resolved = attempt.config
  if (resolved.enabled === false) return
  // Luwi 加 (2026-10-20): 令牌单独一个键。真正交给 git 的地址在这里拼出来, 界面上报的仍然是
  // 干净地址 (`resolved.remote`) —— 令牌不出现在启动行 / 快照里
  const remoteToken = typeof merged.remoteToken === 'string' ? merged.remoteToken.trim() : ''
  const effectiveRemote = composeRemoteWithToken(resolved.remote, remoteToken)

  // Luwi 加 (G1 收口 C6): 已经被挂过一次就拒绝第二次 —— bundle 行与 profile 的 `- insert:`
  // 行并存时会挂两次, 而两个引擎共用一个 repoDir 会互相穿插 git (串行锁是每个 GitBackend
  // 自己那一把, 不跨实例)
  const existing = globalThis[MOUNT_KEY]
  if (existing !== undefined) {
    // 只 warn, 不动 mountState: 那一个是**已经挂上的那一次**的读数, 不能被第二次覆盖
    logger.warn(`refusing a second mount (${String(existing)}): one profile must mount this plugin once`)
    return
  }

  const eventGate = makeEventGate(KNOWN_SESSION_EVENT_TYPES, probeIgnorableAppend())

  const sessionRoot = resolveSessionRoot(resolved.sessionRoot)
  // Luwi 改 (3): bridge 模式 (手机) 下 repoDir 的默认是共享存储里的镜像工作树; 显式配置
  // 照旧生效, 但它必须落在工作区内 —— 沙盒只看得见绑到 /work 的那一棵 (luwi-bridge-runner.mjs)
  const bridge = luwiBridgeFromEnv()
  const workspace = process.cwd()
  const repoDir = resolved.repoDir.trim() !== ''
    ? resolveRepoDir(resolved.repoDir)
    : bridge !== null
      ? join(workspace, '.lw-sync', 'session-sync', 'repo')
      : resolveRepoDir(resolved.repoDir)
  if (bridge !== null) {
    const bridgeProblem = luwiWorktreeProblem(workspace, repoDir)
    if (bridgeProblem !== null) {
      mountState.disabledReason = bridgeProblem
      logger.error(`disabled: ${bridgeProblem}`)
      return
    }
  }
  // C2: 会话根与工作树互相嵌套 = 镜像自己吃自己, 这一条没有任何"降级运行"的意义, 但也不抛
  try {
    assertNestingSafe(sessionRoot, repoDir)
  } catch (error) {
    mountState.disabledReason = messageOf(error)
    logger.error(`disabled: ${messageOf(error)}`)
    return
  }
  // Luwi 加 (2026-10-20): 配置文件的位置 (设置页/面板/配对都写这一份)。
  // 从 sessionRoot 的父目录推 —— 桌面宿主的进程 env 里没有 DSH_HOME, 只有 argv
  const syncConfigFile = syncConfigPath(dirname(sessionRoot))
  /** 影子根: `<DSH_HOME>/lw/peer-workspaces` —— 对端会话影子与同步来的工作区文件都住这儿 */
  const shadowRoot = join(dirname(sessionRoot), 'lw', 'peer-workspaces')
  // Luwi 加 (2026-10-23): 账号权益落在 `<DSH_HOME>/lw/sync-license.json` (与 session-sync.json 同层)
  // 这个文件里只有账号、到期与 device_token —— 没有密码, 也不进仓库/镜像 (lw/ 不在两根镜像的根里)
  const licenseFile = licensePathOf(dirname(sessionRoot))
  /** 登录中的那一次 (state + 起始时刻); 只在内存里, 宿主重启就作废 */
  let loginPending = null
  /** 1 秒内的状态缓存: P2P 每次同步前都问一次, 没必要每次读盘 */
  let licenseCache = { at: 0, status: null }

  /**
   * 读一次权益状态 (文件 → 验签 → 六档)
   * @param {boolean} [force] - 跳过 1 秒缓存 (刚写完文件时用)
   * @returns {Promise<object>} statusOf 的产物
   */
  async function readLicenseStatus(force = false) {
    const now = Date.now()
    if (!force && licenseCache.status !== null && now - licenseCache.at < 1000) return licenseCache.status
    const stored = await readLicenseFile(licenseFile)
    const status = statusOf({ stored, now })
    licenseCache = { at: now, status }
    return status
  }

  /**
   * P2P 收费闸的判据: active / grace 放行, 其余一律 P2P_PLAN_REQUIRED
   * @returns {Promise<{ok: boolean, reason?: string, plan: object|null}>} 闸的答案。
   */
  async function licensePlanGate() {
    const status = await readLicenseStatus()
    const allowed = planAllowsP2p(status)
    return {
      ok: allowed,
      reason: allowed ? undefined : 'P2P_PLAN_REQUIRED',
      plan: {
        state: status.state,
        login: status.login,
        expiresAt: status.expiresAt,
        graceUntil: status.graceUntil,
      },
    }
  }

  /**
   * 面板点「登录」: 只生成一次性 state 与官网地址, 浏览器由面板那一侧打开
   * @returns {Promise<{ok: boolean, url: string, state: string, expiresAt: number}>} 结果。
   */
  async function licenseLoginStart() {
    const state = newLoginState()
    const base = accountBaseOf()
    const startedAt = Date.now()
    loginPending = { state, url: loginUrlOf(base, state), startedAt }
    return { ok: true, url: loginPending.url, state, expiresAt: startedAt + 5 * 60_000 }
  }

  /**
   * 面板轮询: 官网那边登录完了没有
   * 404 = 还没好 (稍后再问), 410 = 这枚 state 已经作废 (要重新点登录)
   * @returns {Promise<object>} {ok, waiting?, signedIn?, license?, error?}
   */
  async function licenseLoginPoll() {
    if (loginPending === null) {
      return { ok: false, error: 'no sign-in is in progress: click "sign in" on this panel first' }
    }
    if (Date.now() - loginPending.startedAt > 5 * 60_000) {
      loginPending = null
      return { ok: false, error: 'the sign-in window expired: click "sign in" again' }
    }
    const base = accountBaseOf()
    const answer = await accountRequest(base, `/api/account/poll?state=${encodeURIComponent(loginPending.state)}`)
    if (answer.status === 404) return { ok: true, waiting: true }
    if (answer.status === 410) {
      loginPending = null
      return { ok: false, error: 'the sign-in code was used or expired before this panel picked it up: sign in again' }
    }
    if (answer.status !== 200 || !answer.body || answer.body.ok !== true) {
      const reason = answer.error ?? (answer.body && answer.body.error) ?? `HTTP ${answer.status}`
      return { ok: false, error: `sign-in did not complete: ${reason}` }
    }
    const stored = storedFromPoll(answer.body, { base })
    if (stored === null) {
      loginPending = null
      return { ok: false, error: 'the server answered without an entitlement: sign in again' }
    }
    await writeLicenseFile(licenseFile, stored)
    licenseCache = { at: 0, status: null }
    loginPending = null
    logger.info(`luwi account: signed in as ${stored.login || '(no login)'} (expires ${new Date(Number(stored.expiresAt) || 0).toISOString()})`)
    return { ok: true, signedIn: true, license: await readLicenseStatus(true) }
  }

  /**
   * 退出登录: 只删本机这一份 (服务端那条会话 90 天后自然过期)
   * @returns {Promise<{ok: boolean}>} 结果。
   */
  async function licenseLogout() {
    loginPending = null
    await clearLicenseFile(licenseFile)
    licenseCache = { at: 0, status: null }
    logger.info('luwi account: signed out on this machine')
    return { ok: true }
  }

  /**
   * 刷新权益: 续费 / 签到之后由面板点一下, 拿一份新签的凭证 (token 不变)
   * @returns {Promise<object>} {ok, license?, error?}
   */
  async function licenseRefresh() {
    const stored = await readLicenseFile(licenseFile)
    const token = stored && typeof stored.token === 'string' ? stored.token : ''
    if (token === '') return { ok: false, error: 'not signed in on this machine' }
    const base = typeof stored.base === 'string' && stored.base !== '' ? stored.base : accountBaseOf()
    const answer = await accountRequest(base, `/api/account/entitlement?token=${encodeURIComponent(token)}`)
    if (answer.status !== 200 || !answer.body || answer.body.ok !== true) {
      const reason = answer.error ?? (answer.body && answer.body.error) ?? `HTTP ${answer.status}`
      return { ok: false, error: `could not refresh: ${reason}` }
    }
    const next = {
      ...stored,
      entitlement: String(answer.body.entitlement || ''),
      login: String((answer.body.account && answer.body.account.login) || stored.login || ''),
      email: String((answer.body.account && answer.body.account.email) || stored.email || ''),
      expiresAt: Number.isFinite(Number(answer.body.expiresAt)) ? Number(answer.body.expiresAt) : stored.expiresAt,
      paidUntil: answer.body.paidUntil === null || answer.body.paidUntil === undefined ? null : Number(answer.body.paidUntil),
      fetchedAt: Date.now(),
    }
    await writeLicenseFile(licenseFile, next)
    licenseCache = { at: 0, status: null }
    return { ok: true, license: await readLicenseStatus(true) }
  }

  /**
   * 签到: 用本机这份 device_token 打官网 POST /api/checkin (2026-10-24)
   *
   * 官网那一条认两种门: 浏览器 cookie 与 Authorization: Bearer —— 面板没有 cookie,
   * 走后者 (与刷新权益同一个 token, 不新增任何凭据)。签到之后顺手刷一次凭证,
   * 面板上那一行「到期」当场变; 刷新失败不算签到失败 (签到本身已经记在服务端了)
   * @returns {Promise<object>} {ok, already?, capped?, days?, expiresAt?, error?}
   */
  async function licenseCheckin() {
    const stored = await readLicenseFile(licenseFile)
    const token = stored && typeof stored.token === 'string' ? stored.token : ''
    if (token === '') return { ok: false, error: 'not signed in on this machine' }
    const base = typeof stored.base === 'string' && stored.base !== '' ? stored.base : accountBaseOf()
    const answer = await accountRequest(base, '/api/checkin', { method: 'POST', token })
    if (answer.status !== 200 || !answer.body || answer.body.ok !== true) {
      const reason = answer.error ?? (answer.body && answer.body.error) ?? `HTTP ${answer.status}`
      return { ok: false, error: `could not check in: ${reason}` }
    }
    const already = answer.body.already === true
    const capped = answer.body.capped === true
    const refreshed = await licenseRefresh()
    return {
      ok: true,
      already,
      capped,
      days: already ? 0 : 1,
      expiresAt: Number.isFinite(Number(answer.body.expiresAt)) ? Number(answer.body.expiresAt) : undefined,
      ...(refreshed.ok === true ? {} : { refreshError: refreshed.error }),
    }
  }
  // Luwi 加 (2026-10-20): 扫码配对段 (插件只读)。配对 id 决定"这是不是一次新的同步开始":
  // 没有它 = 旧模式 (全量镜像); 有它 = 只同步窗口内/之后有写入的会话, 首次同步清旧
  const pairing = normalizePairing(merged.pairing)
  if (merged.pairing !== undefined && pairing === null) {
    warn('luwi-dsh-sync: the pairing section is malformed, this host stays in legacy full-mirror mode')
  }
  const currentWindowMinutes = readCurrentWindowMinutes(merged.sessions)
  // Luwi 加 (2026-10-20): 工作区源 —— 每台设备一根 (host 的 cwd), 仓库里按设备分命名空间
  // (两台设备的同名工作区不合并; 手机侧仍然是 dsh（PC）这种带设备名的分组)
  let workspaceSources = []
  try {
    const resolvedSources = resolveWorkspaceSources({
      workspace,
      config: merged.workspace,
      deviceName: resolved.deviceName,
      repoDir,
      shadowRoot,
      titleFor: (root) => workspaceTitleOf(root),
    })
    workspaceSources = resolvedSources.sources
    for (const problem of resolvedSources.problems) warn(`luwi-dsh-sync: ${problem}`)
  } catch (error) {
    warn(`luwi-dsh-sync: workspace config rejected, the workspace mirror stays off: ${messageOf(error)}`)
  }
  // Luwi 加的一行: host 启动时打一条, 说明这一份走哪条 runner 与两根镜像各落在哪 —— 装机后
  // 排查 "同步怎么没动静" 从这一行开始 (remote 只报 set/unset, 值里可能有 token, 绝不回显)。
  // C6: 挂载记号用 effect 拿, 卸载时自己清 —— 不占任何全局 handler
  const runner = bridge === null ? 'subprocess' : 'luwi-bridge'
  mountState.mounted = true
  mountState.resolved = resolved
  mountState.runner = runner
  // --- Luwi 加 (G1-2 对端): peer / live 两段的规格解析 (启动行要用到, 所以放在它前面)。
  // 各管各的降级 (C2 的同一条口径): peer 段非法只关通道, live 段非法只关投影, 两根镜像照跑。
  // encrypted 后端与这两项互斥 —— 它是另一个引擎类, 派生文件那两笔钩子那边没有
  let peerSpec = null
  let liveSpec = resolveLiveSpec(undefined)
  if (merged.peer !== undefined) {
    try {
      peerSpec = resolvePeerSpec(merged.peer)
    } catch (error) {
      warn(`luwi-dsh-sync: peer config rejected, the cross-device channel stays off: ${messageOf(error)}`)
    }
  }
  if (merged.live !== undefined) {
    try {
      liveSpec = resolveLiveSpec(merged.live)
    } catch (error) {
      warn(`luwi-dsh-sync: live config rejected, the in-progress projection stays off: ${messageOf(error)}`)
    }
  }
  if (resolved.backend === BACKENDS.ENCRYPTED && (peerSpec !== null || liveSpec.enabled)) {
    warn('luwi-dsh-sync: backend=encrypted has no cross-device hooks in this fork, so peer/live are off for this host')
    peerSpec = null
    liveSpec = { ...liveSpec, enabled: false }
  }
  // 对端那一段的启动读数 (与前面两个字段同一纪律: 地址里可能有凭据, 只报形态)
  const peerLine = peerSpec === null
    ? 'peer=off'
    : `peer=on mode=${peerSpec.mode} listen=${peerSpec.listen ? 'on' : 'off'} proxy=${peerSpec.proxy ? 'on' : 'off'} url=${peerSpec.url === '' ? 'card' : 'set'} live=${liveSpec.enabled ? 'on' : 'off'}`
  ctx.effect(() => {
    globalThis[MOUNT_KEY] = `${PLUGIN_NAME}@${PLUGIN_VERSION}`
    return () => {
      delete globalThis[MOUNT_KEY]
    }
  }, `${PLUGIN_NAME}.mount-guard`)
  // 行的前缀与字段顺序**刻意不动** (老 APK 与老读数都按 `session-sync: runner=` 找它),
  // 新身份挂在尾巴上: 两头并排看时一眼能分出哪一行是哪个包
  console.log(
    `session-sync: runner=${runner}`
      + ` repoDir=${repoDir} remote=${resolved.remote.trim() === '' ? 'unset' : 'set'}`
      + ` ws=${workspaceSources.length}`
      + ` baseline=${pairing === null ? 'legacy' : 'set'}`
      + ` ${peerLine}`
      + ` plugin=${PLUGIN_NAME} v=${PLUGIN_VERSION}`,
  )
  // 工作区那根是第二棵工作树、第二个远端, 但它跟会话那根**共用同一条 runner** —— 桥那边的
  // out/err/code 是按工作树分目录的, 共用一条队列反而更稳 (沙盒一次只跑一条 git 命令)
  const runGit = bridge === null
    ? makeRunGit(ctx.subprocess, {
        gitBin: resolved.gitBin,
        graceMs: resolved.graceMs,
        commandTimeoutMs: resolved.commandTimeoutMs,
        maxOutputBytes: resolved.maxOutputBytes,
      })
    : makeLuwiBridgeRunner({
        endpoint: bridge.endpoint,
        token: bridge.token,
        workspace,
        gitBin: resolved.gitBin,
        commandTimeoutMs: resolved.commandTimeoutMs,
        maxOutputBytes: resolved.maxOutputBytes,
      })

  // --- 同步元数据：ctx.storageDomain 领域 'session-sync'（异步打开，操作路径 await）。
  // V2 形态（A02）：effect 在 apply 帧内**同步注册**并持有打开句柄——异步 open 期间
  // 卸载时，disposer 仍会等到 promise 落定后关闭句柄，不会出现「INACTIVE_EFFECT
  // 吞掉注册 ⇒ 域句柄永不关闭」。拒绝显式处理（记录原因 + 一次可见告警），不再用
  // 静默的 `.catch(() => {})` 掩盖。
  /** @type {Promise<object>} 打开后的领域 state 表（消费方各自 await）。 */
  let tablePromise
  ctx.effect(() => {
    const opened = ctx.storageDomain.open(sessionSyncDomainSpec)
    tablePromise = opened.then((domain) => domain.table('state'))
    tablePromise.catch((error) => {
      warn(`storage domain open failed: ${error?.message ?? String(error)}`)
    })
    return () => {
      void opened.then((domain) => domain.close()).catch((error) => {
        warn(`storage domain close failed: ${error?.message ?? String(error)}`)
      })
    }
  }, `${PLUGIN_NAME}.domain.close`)

  /** @type {{deviceId: string, lastPullAt?: number, lastPushAt?: number, lastPushHead?: string, lastError?: string}|undefined} 元数据缓存。 */
  let metaCache
  let metaOps = Promise.resolve()
  const withMeta = (fn) => {
    const run = metaOps.then(fn, fn)
    metaOps = run.then(() => undefined, () => undefined)
    return run
  }

  /** 确保设备 id 已持久化并读入缓存。 */
  async function ensureMeta() {
    if (metaCache !== undefined) return metaCache
    const table = await tablePromise
    let state = await table.get(STATE_KEY)
    if (state === undefined) {
      state = { deviceId: randomUUID() }
      await table.put(STATE_KEY, state)
    }
    metaCache = state
    return state
  }

  /**
   * 元数据补丁（引擎回调；串行写领域）。
   * Luwi 加 (G1-C): 两根镜像各记各的账 —— 会话那根仍写在顶层（与上游同形, 老读数不受影响）,
   * 工作区那根写在 `workspace` 底下 (同一个设备 id 两根共用)。
   * @param {string} scope - TARGETS.SESSIONS | TARGETS.WORKSPACE。
   * @param {object} patch - 要合并进去的字段。
   */
  function reportMetaFor(scope) {
    return (patch) => {
      void withMeta(async () => {
        try {
          const current = await ensureMeta()
          const next = scope === TARGETS.WORKSPACE
            ? { ...current, workspace: { ...(current.workspace ?? {}), ...patch } }
            : { ...current, ...patch }
          const table = await tablePromise
          await table.put(STATE_KEY, next)
          metaCache = next
        } catch (error) {
          warn(`sync metadata write failed: ${messageOf(error)}`)
        }
      })
    }
  }

  /** 元数据读取（按 scope; 工作区那份把设备 id 带上, 界面里两根显示同一个设备）。 */
  function readMetaFor(scope) {
    return async () => {
      const current = await ensureMeta()
      if (scope !== TARGETS.WORKSPACE) return current
      return { deviceId: current.deviceId, ...(current.workspace ?? {}) }
    }
  }

  const reportMeta = reportMetaFor(TARGETS.SESSIONS)
  const reportMetaWorkspace = reportMetaFor(TARGETS.WORKSPACE)

  /** 最近错误（引擎回调；串行写领域）。 */
  function reportError(message) {
    reportMeta({ lastError: message.slice(0, 500) })
  }

  /** Luwi 加：工作区那根的最近错误。 */
  function reportWorkspaceError(message) {
    reportMetaWorkspace({ lastError: message.slice(0, 500) })
  }

  // --- git 后端 + 引擎（backend seam：git = 明文，encrypted = age 加密的 git）。
  // Luwi 改 (1): 手机侧 (bridge 在) git 经桥进 Linux 沙盒跑; 别的宿主照旧走 ctx.subprocess
  // (那一条 runner 在上面就建好了 —— 工作区那根与它共用)
  /** git 网络冷却到什么时候 (Luwi 加 2026-10-20: 退到 api.github.com 之后的一段时间里别再等 git) */
  let apiCooldownUntil = 0
  const apiCooldownActive = () => apiCooldownUntil > Date.now()
  // 手机上 git fetch 会在沙盒里挂满 120 s 并留下一个孤儿 shell, 把桥堵住; API 传输可用时
  // 直接不走 git 网络命令 (apiTransport 在下面才建, 这里先用一个开关占位)
  let androidApiOnly = false
  const git = new GitBackend({
    repoDir,
    // 2026-10-20: 带令牌的地址 (令牌在面板上单独一格) —— 界面上报的仍是干净地址
    remote: effectiveRemote,
    branch: resolved.branch,
    commitName: resolved.commitName,
    commitEmail: resolved.commitEmail,
    run: runGit,
    timeoutMs: resolved.commandTimeoutMs,
    // 冷却期内 push/fetch 直接抛 "git network skipped", 引擎照常走镜像/提交, 再由 pushSync
    // 落到 API 传输 —— 这样手机上不会再每条命令先烧 120 s
    networkSkip: () => apiCooldownActive() || androidApiOnly,
  })

  // --- Luwi 加 (G1-D): 互为远端的名片。自己这一份写在会话仓库的 peers/<设备 id>.json, 每次
  // 镜像前由引擎调一次 (跟会话坐同一次提交过去); 设备 id 在元数据领域就绪后补齐 (与 fork 文件
  // 同一笔账), 没就绪时写卡片会静默跳过 —— 下一次镜像会再写, 不缺这一会儿
  const peerIdentity = { deviceId: '' }
  const writeOwnPeerCard = async () => {
    // Luwi 加 (P2P T5): 顺带把 P2P 身份写进名片 (`p2p.nodeId` / ticket), 对端下一次拉取就拿到;
    // sidecar 还没起来时 identity 回 null, 名片保持原样 (下一次镜像会再写)
    let p2pIdentity = null
    try {
      p2pIdentity = await p2pService.identity()
    } catch {
      // P2P 没开或还没就绪都不该让名片变红
    }
    const wroteCard = await writePeerCard({
      repoDir,
      deviceId: peerIdentity.deviceId,
      name: resolved.deviceName,
      url: resolved.publicUrl,
      p2p: p2pIdentity === null ? undefined : { nodeId: p2pIdentity.nodeId, ticket: p2pIdentity.ticket },
    })
    // Luwi 加 (2026-10-20): 顺手写"我这台设备有哪些工作区"的名片 —— 对端拉取之后靠它
    // 把 `workspaces/<设备名>/<工作区名>/` 物化到自己的影子目录里。fail-soft: 一张卡不该让同步变红
    let wroteWorkspace = false
    try {
      wroteWorkspace = await writeWorkspaceCard({
        repoDir,
        deviceId: peerIdentity.deviceId,
        name: resolved.deviceName,
        workspaces: workspaceCardEntries(),
      })
    } catch (error) {
      warn(`luwi-dsh-sync: writing the workspace card failed: ${messageOf(error)}`)
    }
    return wroteCard || wroteWorkspace
  }

  // Luwi 加 (P2P T4): 调度器 + 对端解析 + 常驻生命周期。deviceId 用 getter —— engine 的 meta
  // 要到挂载之后才读出来; 对端从名片 `peers/*.json` 的 `p2p.nodeId` 那一行来 (T5 写进去)。
  // `.p2p/` 是本机私有的: 先建目录并放一份 `*` 的 .gitignore, 私钥与信任表绝不进镜像/仓库
  const p2pDir = join(repoDir, '.p2p')
  mkdirSync(p2pDir, { recursive: true })
  if (!existsSync(join(p2pDir, '.gitignore'))) writeFileSync(join(p2pDir, '.gitignore'), '*\n')
  // Luwi 加 (2026-10-23): 周期拉取这个数现在也管 P2P 的心跳 —— 有周期拉取时那一趟本来就会先
  // 试一趟 P2P, 再起一条独立心跳等于同一拍拨两次。定时器在下面 (periodic-pull 那个 effect) 会改它,
  // 所以这里用一个可变的值给服务读 getter
  let periodicPullMinutes = resolved.pullIntervalMinutes
  const p2pService = new P2pService({
    config: resolved.p2p,
    repoDir,
    deviceId: () => peerIdentity.deviceId,
    readCards: async () => (await readPeerCards(repoDir)).cards,
    periodicPull: () => periodicPullMinutes > 0,
    // Luwi 加 (2026-10-23): 到期闸 —— 订阅过期 (超过 7 天宽限) 后 P2P 不可用, git 链路一个字不改
    requirePlan: licensePlanGate,
    sidecar: new P2pSidecar({
      config: resolved.p2p,
      pluginDir: fileURLToPath(new URL('.', import.meta.url)),
      keyFile: join(p2pDir, 'node.key'),
    }),
  })
  let p2pWarned = false
  /**
   * 每次 push/pull 之前先试一趟 P2P (两段双向, 见 lib/p2p-peer.mjs)。
   *
   * 失败不挡路: P2P 只是前置传输, 后面那条 git/API 照跑 (99.9% 靠它兜底)。
   * @returns {Promise<void>} 完成。
   */
  async function runP2pBeforeSync() {
    if (p2pService === null || resolved.p2p.enabled !== true) return
    const result = await p2pService.beforeSync()
    if (result.ok === true) return
    if (p2pWarned) return
    // Luwi 加 (2026-10-23): 订阅到期不是"坏了", 是"这一趟走 git" —— 单独一句话, 别混进故障里
    if (result.reason === 'P2P_PLAN_REQUIRED') {
      p2pWarned = true
      warn('sync: the subscription has expired, this round goes over git (see the pairing card on the panel)')
      return
    }
    if (['P2P_TIMEOUT', 'P2P_CRASH', 'P2P_IO', 'P2P_PROTOCOL'].includes(result.reason)) {
      p2pWarned = true
      warn(`sync: p2p ${result.reason}; the existing transport keeps the sync going`)
    }
  }

  /**
   * 面板确认换机（P2P T6）：把信任表里这个设备 id 的 nodeId 换成名片上现在那一个。
   * @param {string} deviceId - 会话仓库里的设备 id。
   * @returns {Promise<{ok: boolean, nodeId?: string, error?: string}>} 结果。
   */
  async function p2pConfirmPeer(deviceId) {
    let nodeId = ''
    let name = ''
    try {
      const card = (await readPeerCards(repoDir)).cards.find(item => item.deviceId === deviceId)
      nodeId = card?.p2p?.nodeId ?? ''
      name = card?.name ?? ''
    } catch {
      // 名片读不动就看 blockedPeer
    }
    if (nodeId === '' && p2pService.blockedPeer?.id === deviceId) nodeId = String(p2pService.blockedPeer.nodeId ?? '')
    if (nodeId === '') return { ok: false, error: 'no p2p node id for that card' }
    const next = confirmPeer(await readTrust(repoDir), { deviceId, nodeId, name })
    await writeTrust(repoDir, next)
    await p2pService.refreshPeer()
    logger.info(`p2p: confirmed device ${deviceId} with node ${nodeId.slice(0, 8)}…`)
    return { ok: true, nodeId }
  }

  /**
   * 面板吊销一个对端（P2P T6）：信任表置 revokedAt，下一次拨号/入站都拒绝。
   * @param {string} deviceId - 会话仓库里的设备 id。
   * @returns {Promise<{ok: boolean, error?: string}>} 结果。
   */
  async function p2pRevokePeer(deviceId) {
    await writeTrust(repoDir, revokePeer(await readTrust(repoDir), { deviceId }))
    await p2pService.refreshPeer()
    logger.info(`p2p: revoked device ${deviceId}`)
    return { ok: true }
  }

  // --- Luwi 加 (2026-10-20): 会话基线 (旧对话不同步 / 当前在聊的要同步)。
  // **懒捕获**: 第一次 status / push / pull 才扫盘, 挂载帧里只读一份小 JSON (守 C4)
  let baselineStatePromise = null
  async function ensureBaselineState() {
    if (pairing === null) return null
    if (baselineStatePromise !== null) return baselineStatePromise
    baselineStatePromise = (async () => {
      const meta = await ensureMeta()
      const stored = meta.sessionBaseline
      if (stored !== null && typeof stored === 'object'
        && stored.pairId === pairing.id
        && stored.baseline !== null && typeof stored.baseline === 'object') {
        // 一次挂载一行: 复用的是哪一份账 (启动读数里"captured at" 变新就说明又被重捕获了)
        logger.info(`session baseline reused: ${stored.entries ?? Object.keys(stored.baseline).length} file(s) captured at ${new Date(stored.capturedAt).toISOString()}${stored.purgePending === true ? ', purge pending' : ''}`)
        return stored
      }
      // 重捕获只有两条正当理由, 打出来 —— 2026-10-21 之前这里每启动一次就重捕获一次而没有任何
      // 痕迹, 于是"purgePending 又变真了"只能靠猜 (根因是 storage 域在 open 时把这条记录过一遍
      // schema, 见 lib/domain.mjs 那段注释)
      const reason = stored === null || typeof stored !== 'object'
        ? 'no stored baseline'
        : stored.pairId !== pairing.id
          ? `the pairing changed (${String(stored.pairId)} -> ${pairing.id})`
          : 'the stored baseline is malformed'
      const entries = await scanSessionFiles(sessionRoot)
      const captured = captureSessionBaseline(entries, { now: Date.now(), windowMinutes: currentWindowMinutes })
      const next = {
        v: captured.v,
        pairId: pairing.id,
        capturedAt: captured.capturedAt,
        windowMinutes: captured.windowMinutes,
        entries: captured.entries,
        currentCount: captured.currentCount,
        totalCount: captured.totalCount,
        truncated: captured.truncated,
        baseline: captured.baseline,
        purgePending: true,
      }
      await patchPluginState({ sessionBaseline: next })
      logger.info(`session baseline captured (${reason}): ${captured.entries} file(s) stay local, ${captured.currentCount} current/new session(s) sync (window ${captured.windowMinutes} min)`)
      return next
    })().catch((error) => {
      warn(`reading or capturing the session baseline failed: ${messageOf(error)}`)
      return null
    })
    return baselineStatePromise
  }
  /** 基线钩子 (引擎只在会话那根上用它: 镜像跳过 + 首次同步的清理) */
  const baseline = pairing === null ? undefined : {
    load: async () => {
      const state = await ensureBaselineState()
      return { paths: state?.baseline ?? {} }
    },
    pending: async () => {
      const state = await ensureBaselineState()
      return state?.purgePending === true
    },
    purgePaths: async () => {
      const state = await ensureBaselineState()
      if (state === null) return []
      // Luwi 改 (2026-10-21): 只走镜像那一侧 (镜像里有多少个文件就查多少个), 不再扫整个
      // sessionRoot —— 手机上 3669 次 stat 会把首拉卡在一个永远走不完的清理里
      return await oldMirrorPaths(join(repoDir, MIRROR_DIR), state.baseline, sessionRoot)
    },
    done: async () => {
      const state = await ensureBaselineState()
      if (state === null || state.purgePending !== true) return
      state.purgePending = false
      state.purgedAt = Date.now()
      await patchPluginState({ sessionBaseline: state })
    },
  }
  /**
   * 这一条会话文件现在还"算旧"么 (取回时用; 与清理同一个判据)。
   *
   * 传进 `adoptMergedSessions` 的 `skipOld`: 我们自己的旧会话从镜像取回本机等于把存档里
   * 早该冷下去的那一场又拉回列表, 所以基线里记过、源文件又没再动过的, 一条都不取
   * @param {string} rel - 会话文件相对路径。
   * @returns {Promise<boolean>} 仍然算旧就 true。
   */
  const isOldSessionRel = async (rel) => {
    const state = await ensureBaselineState()
    return await localStillOld(sessionRoot, rel, state?.baseline)
  }

  // --- Luwi 加 (2026-10-20): 一个仓库里的多根源 —— 会话一根 + 每台设备的工作区各一根。
  // 投影会话的本地 id 账在下面 (代理那一段) 才建; 这里先用一个 Set, 那边直接拿去用
  const neverSyncSessionIds = new Set()
  const engineSources = [
    {
      label: 'sessions',
      root: sessionRoot,
      mirrorDir: MIRROR_DIR,
      useBaseline: true,
      neverSyncSessionIds,
      // 单场会话日志的上限 (2026-10-22): 一场聊得特别久的会话能长到几 MB, 而它每聊一句就变一次,
      // 于是每趟推送都要重传整份 —— 手机上实测一条消息要等好几分钟才到对端 (队列里的小文件
      // 被这场大会话拖着)。超过上限的会话不进仓库, 面板的 oversized 读数会列出来
      maxFileBytes: SESSION_FILE_LIMIT_BYTES,
      // 会话日志按 zstd 帧切段 (2026-10-22, 见 lib/luwi-parts.mjs): 追加时只有最末一段变,
      // 前面的段 sha 不变 —— PC 上只能走 REST (没有 delta), 不切段就是每聊一句重传整份
      chunkSessionLogs: true,
      // 影子工作区 (`dsh（PC）` / `dsh（手机）`) 里开的会话属于对端那一场, 永不进仓库
      neverSyncShadowSessions: true,
      adoptRemote: (context) => adoptMergedSessions({
        sessionRoot,
        repoDir,
        mirrorDir: MIRROR_DIR,
        wasMirrored: context?.wasMirrored,
        // 影子工作区里的会话不取回 (它们是"对端那一场"的本地投影, 取回来会多一场重复对话)
        skipShadowSessions: true,
        // 基线里仍然算旧的那些不取回 (首次清理不再挡在取回前面, 判断挪到了这一层)
        skipOld: isOldSessionRel,
      }),
    },
    ...workspaceSources,
  ]

  const engineDeps = {
    git,
    sessionRoot,
    repoDir,
    mirrorDir: MIRROR_DIR,
    remote: effectiveRemote,
    branch: resolved.branch,
    deviceId: 'pending',
    // Luwi 加 (2026-10-20): 多源 + 会话基线
    sources: engineSources,
    baseline,
    reportMeta,
    reportError,
    logger,
    onForks: (forkPaths) => handleForks(forkPaths),
    // Luwi 加 (G1-D): 镜像前把自己的名片写进 peers/ (工作区那根不传 —— 名片住在会话仓库里)
    writePeerCard: writeOwnPeerCard,
    // Luwi 改 (4): pull 合并后把远端带来的新会话取回本地 sessionRoot (lib/luwi-adopt.mjs)
    adoptRemote: () => adoptMergedSessions({ sessionRoot, repoDir, mirrorDir: MIRROR_DIR, skipOld: isOldSessionRel }),
  }
  const engine = resolved.backend === BACKENDS.ENCRYPTED
    ? new EncryptedBackend({
        ...engineDeps,
        run: makeRunAge(ctx.subprocess, {
          graceMs: resolved.graceMs,
          commandTimeoutMs: resolved.commandTimeoutMs,
          maxOutputBytes: resolved.maxOutputBytes,
        }),
        ageBin: resolved.ageBin,
        ageRecipient: resolved.ageRecipient,
        ageIdentity: resolved.ageIdentity,
        timeoutMs: resolved.commandTimeoutMs,
      })
    : new SyncEngine(engineDeps)

  // Luwi 加 (2026-10-20 主人要求): 这台 PC 上 `github.com:443` 时通时不通 (git 的 smart-HTTP 走的
  // 就是它), 而 `api.github.com` 一直通。于是 push/pull 多一条退路: git 报网络错误时改走 REST API
  // (同一棵树、以远端 head 为父的提交), 语义与 git 那条对齐 —— 只换网络那一段
  const apiTransport = makeGithubApi({
    remote: effectiveRemote,
    // 注意: remoteToken 是 apply 作用域里的局部常量, **不在 resolved 里** —— 之前写成
    // resolved.remoteToken (undefined), 于是 enabled 恒为 false, 退路一次都没跑过 (2026-10-20 排查)
    token: remoteToken,
    branch: resolved.branch,
    repoDir,
    gitBin: resolved.gitBin,
    // 手机上必须走桥 runner (宿主里没有可 exec 的 git); PC 上它就是 ctx.subprocess 那条
    runGit,
    deviceShort: resolved.deviceName,
    // 本机 id 从元数据来: device.txt 只记"仓库创建者", 两台设备都往它写会每轮互相覆盖
    ownDeviceId: () => peerIdentity.deviceId,
    // API 传输自己也会 `git add` / `git write-tree`; 同一个陈旧锁它也要能自愈
    clearStaleLock: () => clearStaleIndexLock(repoDir),
    warn,
  })
  let apiTransportNoted = false
  const noteApiFallback = (what, error) => {
    if (apiTransportNoted) return
    apiTransportNoted = true
    warn(`sync: git cannot reach github.com (${error}); ${what} through api.github.com instead`)
  }
  logger.info(`sync: api.github.com fallback ${apiTransport.enabled ? 'on (used when git cannot reach github.com)' : 'off (no token: git alone must reach github.com)'}`)
  if (!apiTransport.enabled) warn('sync: the api.github.com fallback is off — set the token in the panel, or git has to reach github.com on its own')
  // 手机: API 可用时网络命令一律走 API, 不再先起一条注定要超时的 git fetch
  // (那条 fetch 会在沙盒里留下孤儿 shell, 把后续 git 命令堵死 —— 2026-10-21 实测)
  androidApiOnly = process.platform === 'android' && apiTransport.enabled
  if (androidApiOnly) logger.info('sync: android uses api.github.com for network commands (no sandbox git fetch)')
  /**
   * 推一次: 先走 git, 只有网络不通才退到 api.github.com。
   * **Android 上直接走 API**: 镜像之后一条本地 git 都不发 (`preparePush` + `noGit`),
   * 沙盒里的 `git add -A` / `write-tree` 在 2 MB 级会话上会撞 120 s 上限 (2026-10-21 实测)。
   * @param {object} [options] - 传给引擎的参数。
   * @returns {Promise<object>} 引擎形状的结果 (via: 'api' 表示走了退路)。
   */
  async function pushSync(options = {}) {
    await runP2pBeforeSync()
    if (androidApiOnly) {
      let prepared = null
      try {
        prepared = await engine.preparePush()
      } catch (error) {
        warn(`the mirror before the api push failed: ${messageOf(error)}`)
      }
      const result = await pushViaApi({ ok: false })
      if (prepared !== null && result.ok === true) {
        return {
          ...result,
          mirrored: prepared.totals?.mirrored ?? 0,
          deleted: prepared.totals?.deleted ?? 0,
        }
      }
      return result
    }
    const result = await engine.push(options)
    if (result.ok === true) return result
    const fallback = apiTransport.enabled && looksLikeNetworkFailure(result.error)
    if (!fallback) return result
    apiCooldownUntil = Date.now() + API_FALLBACK_COOLDOWN_MS
    noteApiFallback('pushing', result.error)
    return await pushViaApi(result)
  }

  /**
   * 用 api.github.com 推一次 (Android 的正路与"git 网络失败"的退路共用)。
   * @param {object} base - 失败时要带上的原结果 (Android 那条路给 `{ok:false}`)。
   * @returns {Promise<object>} 引擎形状的结果。
   */
  async function pushViaApi(base) {
    const viaApi = await apiTransport.push({
      message: 'dsh-session-sync: push snapshot (api)',
      // 引擎的镜像在 push 前已经把删除提交掉了, API 的并集推送看不到它们;
      // 把这一趟的删除清单显式带过去, 否则排除掉的影子会话/清理掉的旧对话会从远端复活
      deletions: Array.isArray(engine.lastDeletedPaths) ? engine.lastDeletedPaths : [],
      // 手机: 本地清单与字节由本进程读, 一条本地 git 都不发
      noGit: process.platform === 'android',
    })
    if (viaApi.ok !== true) {
      reportError(viaApi.error ?? 'the api push failed')
      return { ...base, ok: false, error: viaApi.error }
    }
    // API 推成功没有走引擎那一段, 这两笔账在这里补 (否则面板上的"上次推送"永远停在旧值)
    reportMeta({ lastPushAt: Date.now(), lastPushHead: viaApi.head ?? '', lastError: '' })
    return {
      ...base,
      ok: true,
      pushed: viaApi.pushed === true,
      via: 'api',
      head: viaApi.head,
      error: undefined,
      // 这一趟上传的段数 (段集合那条路的成本读数, 2026-10-22)
      uploadedParts: viaApi.uploadedParts ?? 0,
      warnings: [
        ...(Array.isArray(base.warnings) ? base.warnings : []),
        ...(viaApi.pushed === true ? [] : ['api transport: the remote already had this tree, nothing new to push']),
      ],
    }
  }
  /**
   * API 拉到之后的收尾: 取回会话到本地 sessionRoot (不取回 = 手机列表里看不见对方的对话)、
   * 消费队列、首次清理收口。Android 与"git 网络失败退路"两条入口共用。
   * @param {object} viaApi - `apiTransport.pull()` 的结果。
   * @param {object} base - 失败时要带上的原结果。
   * @returns {Promise<object>} 引擎形状的结果。
   */
  async function finishApiPull(viaApi, base) {
    if (viaApi.ok !== true) {
      reportError(viaApi.error ?? 'the api pull failed')
      return { ...base, ok: false, error: viaApi.error }
    }
    let finished = { brought: 0, updated: 0, trashed: 0, purged: 0, warnings: [] }
    // Luwi 改 (2026-10-22): head 没变就什么都不取回。从前这一步是无条件的 —— 每 60 秒的
    // 那一趟即使远端一个字都没动, 也要把镜像里每场切段会话拼一遍 (16 MB 的会话就是 64 段
    // 读进内存), 而队列里的那几句话排在取回后面。远端没动 = 镜像没动 = 没有可取的
    if (viaApi.pulled === true && typeof engine.finishExternalPull === 'function') {
      try {
        finished = await engine.finishExternalPull({ head: viaApi.head, remoteHead: viaApi.head })
      } catch (error) {
        warn(`finishing the api pull failed: ${messageOf(error)}`)
      }
    }
    reportMeta({ lastPullAt: Date.now(), lastError: '' })
    return {
      ...base,
      ok: true,
      pulled: viaApi.pulled === true,
      via: 'api',
      head: viaApi.head,
      error: undefined,
      brought: finished.brought,
      updated: finished.updated,
      purged: finished.purged,
      // 段集合那一路的读数 (2026-10-22): 这一趟搬了哪几组 / 还留着哪几组
      deferred: finished.deferred ?? 0,
      partSetsTaken: viaApi.partSetsTaken ?? 0,
      partSetsDeferred: viaApi.partSetsDeferred ?? 0,
      partsFetched: viaApi.partsFetched ?? 0,
      bytesFetched: viaApi.bytesFetched ?? 0,
      warnings: [
        ...(Array.isArray(base.warnings) ? base.warnings : []),
        ...(Array.isArray(finished.warnings) ? finished.warnings : []),
      ],
    }
  }

  /**
   * 拉一次: 先走 git, 只有网络不通才退到 api.github.com (把远端那棵树物化进工作树)。
   * **Android 上直接走 API**: 沙盒 git 的 fetch/add 会把桥挂住, 一条都不发
   * @param {object} [options] - 传给引擎的参数。
   * @returns {Promise<object>} 引擎形状的结果。
   */
  async function runPullSync(options = {}) {
    await runP2pBeforeSync()
    if (androidApiOnly) {
      try {
        await engine.preparePull()
      } catch (error) {
        warn(`the mirror before the api pull failed: ${messageOf(error)}`)
      }
      return await finishApiPull(await apiTransport.pull(), { ok: false })
    }
    const result = await engine.pull(options)
    if (result.ok === true) return result
    const fallback = apiTransport.enabled && looksLikeNetworkFailure(result.error)
    if (!fallback) return result
    apiCooldownUntil = Date.now() + API_FALLBACK_COOLDOWN_MS
    noteApiFallback('pulling', result.error)
    return await finishApiPull(await apiTransport.pull(), result)
  }
  /**
   * 单飞: 周期拉取 / 自动拉取 / 工具调用可能同时来, 而 API 那条路要在外面跑很久
   * (手机上一次 1.6 MB 的 blob 要 90 s)。不串行的话会叠出好几条同样的下载, 互相抢带宽,
   * 还可能在同一个工作树上交错写 (2026-10-20 晚上量到 0 字节 fork 就是这么来的)
   */
  let pullInFlight = null
  /** 一趟拉取最多占多久 (ms): 到点就把单飞放掉, 下一分钟还能再试 (底层那一趟可能还挂在网络上) */
  const PULL_DEADLINE_MS = 240_000
  /** 把一个 promise 套上硬截止: 到点 reject, 但底层继续跑 (结果丢弃) */
  function withDeadline(promise, ms, what) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${what} did not finish within ${Math.round(ms / 1000)}s`)), ms)
      if (typeof timer.unref === 'function') timer.unref()
      promise.then(value => { clearTimeout(timer); resolve(value) }, error => { clearTimeout(timer); reject(error) })
    })
  }
  function pullSync(options = {}) {
    if (pullInFlight !== null) return pullInFlight
    const run = runPullSync(options)
    // 底层那条链有自己的 catch (这里只是防未处理拒绝), 单飞等的是带截止的那一份
    void run.catch(() => {})
    pullInFlight = withDeadline(run, PULL_DEADLINE_MS, 'the pull').finally(() => { pullInFlight = null })
    return pullInFlight
  }
  // 引擎的对外门面: push/pull 换成带退路的那两个, 其余原样 (bind 住真身, 私有字段才不会丢)
  const engineFacade = new Proxy(engine, {
    get(target, prop) {
      if (prop === 'push') return pushSync
      if (prop === 'pull') return pullSync
      const value = Reflect.get(target, prop)
      return typeof value === 'function' ? value.bind(target) : value
    },
  })
  if (workspaceSources.length > 0 && resolved.backend === BACKENDS.ENCRYPTED) {
    warn('luwi-dsh-sync: backend=encrypted mirrors the session store only; the workspace sources stay off in this fork')
  }

  // 设备 id 来自元数据领域；在领域就绪后补齐（fork 命名/设备文件用）。
  void ensureMeta().then((meta) => {
    engine.deviceId = meta.deviceId
    // Luwi 加 (G1-D): 名片也要它 (写进 peers/<设备 id>.json 的文件名)
    peerIdentity.deviceId = meta.deviceId
  }).catch(error => warn(`sync metadata open failed: ${messageOf(error)}`))

  // --- Luwi 加 (G1-2 对端): 跨设备通道的运行时。
  //
  // peerSpec / liveSpec 在上面的启动行之前就解析好了 (那一行要报它们); 这里只管把运行时装配
  // 起来 —— 投递面、投影面、队列、目标解析与插件自己的那两笔账
  /** 会话摘要的短缓存 (面板与 /peer/info 都会问, 一次拉取就够) */
  let summariesCache = { at: 0, value: [] }

  /**
   * 本机工作区列表 (workspaceRegistry 是可选服务; 缺了就是空)。
   *
   * **影子工作区不算本机的**。它们是"对端工作区在本机的投影", 一旦报给对端, 对端就会给它们再建
   * 一层影子、标题还被 annotateWorkspaceNames 追一个 `（设备名）`, 于是两头互相放大 ——
   * 2026-10-14 真机上真跑出来了: PC 侧 259 个分组、名字套到 `…（Luwi 手机）（Luwi 手机）…`。
   * 会话那半边同理 (投影会话也不该出现在 /peer/info 的 sessions 里), 否则还会回声出一批
   * "投影的投影"。
   * @returns {Array<object>} 工作区摘要。
   */
  function ownWorkspaces() {
    const registry = ctx.get('workspaceRegistry')
    if (registry === undefined || typeof registry.list !== 'function') return []
    try {
      return registry.list()
        .filter(workspace => !isShadowWorkspace(workspace))
        .map(workspace => ({
          id: String(workspace.id ?? ''),
          title: String(workspace.title ?? ''),
          path: String(workspace.path ?? ''),
          sessionIds: Array.isArray(workspace.sessionIds) ? workspace.sessionIds.map(String) : [],
        }))
    } catch (error) {
      warn(`peer: listing workspaces failed: ${messageOf(error)}`)
      return []
    }
  }

  /**
   * 一个 cwd 落在哪个工作区 (影子工作区也算), 拿它的**原始标题**。
   *
   * 从影子组发出的一句话要告诉对端"这一句属于哪个工作区": 从前退回 `basename(cwd)`, 那是
   * safeSegment 之后的影子目录名 (`Mobile-AI-dsh`), 对端注册表里那一条叫 `Mobile-AI\dsh`,
   * 于是永远匹配不上、只能兜底到"最近那个工作区" (2026-10-22 主人实测: 手机在
   * `Mobile-AI\dsh（PC）` 里发的消息, PC 上落进了 `D:\apk`)
   * @param {unknown} cwd - 会话的工作目录。
   * @returns {string} 工作区标题 (已去掉 `（设备名）` 那层标注); 找不到给空串。
   */
  function workspaceNameForCwd(cwd) {
    const path = String(cwd ?? '').trim()
    if (path === '') return ''
    let best
    try {
      const registry = ctx.get('workspaceRegistry')
      const all = registry !== undefined && typeof registry.list === 'function' ? registry.list() : []
      for (const workspace of Array.isArray(all) ? all : []) {
        const candidate = String(workspace?.path ?? '')
        if (candidate === '') continue
        const inside = path === candidate
          || path.startsWith(candidate.endsWith('/') || candidate.endsWith('\\') ? candidate : `${candidate}${process.platform === 'win32' ? '\\' : '/'}`)
        if (!inside) continue
        if (best === undefined || candidate.length > String(best.path).length) best = workspace
      }
    } catch (error) {
      warn(`peer: resolving the workspace of ${JSON.stringify(path)} failed: ${messageOf(error)}`)
      return ''
    }
    return best === undefined ? '' : bareWorkspaceName(best.title)
  }

  /**
   * 这个工作区是不是我们替对端建的那一层影子。
   *
   * **判据的首选是 id**, 不是路径: `shadowRegistry` 里记着每一次 `registry.create` 的产物 (重启后
   * 从插件账上读回来)。路径判据单独用会失效 —— 注册表存的是 `realpath` 之后的路径, 而本机
   * `$DSH_HOME` 若是 junction (`C:\Users\30216\.dsh` → `E:\Mobile-AI\dsh-home`), 影子根拼出来是
   * C: 那一种, 两边永远对不上。2026-10-14 第二次事故 (665 个分组) 就是只写路径判据造成的。
   * @param {object} workspace - `registry.list()` 的一条。
   * @returns {boolean} 是影子就 true。
   */
  function isShadowWorkspace(workspace) {
    const id = String(workspace?.id ?? '')
    if (id !== '') {
      for (const entry of shadowRegistry.values()) {
        if (entry.workspaceId === id) return true
      }
    }
    return isShadowPath(String(workspace?.path ?? ''))
  }

  /**
   * 这个路径是不是影子根底下那一条。两条拼法都试: 原样的, 与 realpath 之后的 (junction 那一路)。
   * 用 relative 判, 免得 `peer-workspaces-x` 这种同前缀的兄弟目录被误伤。
   * @param {string} candidate - 待判路径。
   * @returns {boolean} 是影子就 true。
   */
  function isShadowPath(candidate) {
    if (candidate === '') return false
    if (pathUnder(candidate, shadowRoot)) return true
    if (shadowRootCanonical === undefined) {
      try {
        shadowRootCanonical = realpathSync.native(shadowRoot)
      } catch {
        // 影子根还没建出来 (目录不存在): 这一档只能信原样那条拼法
        shadowRootCanonical = ''
      }
    }
    const root = shadowRootCanonical !== '' ? shadowRootCanonical : shadowRoot
    if (pathUnder(candidate, root)) return true
    // **候选那一边也要折一次**: 会话日志里记的 cwd 可能是别名的形状 —— 手机上是 `/data/data/<包名>/…`
    // 而根是 `/data/user/0/<包名>/…` (同一棵树, 两个拼法)。旧版本建的那些投影会话记的正是别名,
    // 少了这一折它们既进不了影子分组、也不被当成投影 (2026-10-14 真机上 20 个 cwd 目录 / 64 行
    // 会话就是这么"谁都管不着"地堆在会话列表里的)
    const folded = canonicalOf(candidate)
    return folded !== candidate && pathUnder(folded, root)
  }

  /** 折符号链接的缓存 (同一个 cwd 每条会话都要判一次, 不该每题都去问文件系统) */
  const canonicalCache = new Map()

  /** 路径的 canonical 形状 (折不动就原样回, 不抛) */
  function canonicalOf(candidate) {
    const cached = canonicalCache.get(candidate)
    if (cached !== undefined) return cached
    let value = candidate
    try {
      value = realpathSync.native(candidate)
    } catch {
      value = candidate
    }
    if (canonicalCache.size > 512) canonicalCache.clear()
    canonicalCache.set(candidate, value)
    return value
  }

  /**
   * candidate 在 root 之下吗 (含 root 本身)。
   * @param {string} candidate - 待判路径。
   * @param {string} root - 根。
   * @returns {boolean} 在之下就 true。
   */
  function pathUnder(candidate, root) {
    const rel = relative(root, candidate)
    return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
  }

  /**
   * 本机会话摘要 (sessionController.list 的产物 + 工作区名)。
   * 标题在宿主那一侧不是 summary 的字段 (它住投影缓存), 所以这里按 projections.values.title
   * 兜一层; 两个都没有就交给界面按 cwd 兜底
   * @returns {Promise<Array<object>>} 摘要。
   */
  async function ownSessions() {
    if (Date.now() - summariesCache.at < 1000) return summariesCache.value
    // 账本要先读回来 (那条"相连时才开始"的线与"哪些是对端搬进来的副本"都住在账里)
    await proxyStateReady
    const controller = ctx.get('sessionController')
    let value = []
    if (controller !== undefined && typeof controller.list === 'function') {
      try {
        // 12 s 而不是 5 s: 手机上一次冷列举实测就贴着 5 s (见 PEER_DEFAULTS.SEND_TIMEOUT_MS 的注释),
        // 而超时的后果是**静默返回空表** —— 对端会以为本机一场会话都没有, 投影自然全不建
        const listed = await controller.list({}, AbortSignal.timeout(12000))
        const workspaces = ownWorkspaces()
        const archived = ownArchivedSessionIds()
        // 「从相连时才开始同步, 过往不计」: 先问一次那条线 (没有就落成现在) —— 于是相连之前就没有
        // 新活动的老会话一场都不会被报出去, 对端也就不会为它们建投影 (2026-10-14 主人报的"全部对话")
        const epoch = peerEpoch(nowMs())
        value = (listed?.items ?? [])
          // 投影会话不是本机的会话: 报给对端就会回声出"投影的投影"(2026-10-14 真机上 113 条)
          .filter(item => !proxyLocalIds.has(String(item.sessionId ?? '')) && !isShadowPath(String(item.cwd ?? '')))
          // 同步面 = 未归档 + 从相连之后有新活动 (正在跑的那一场永远算"当下")
          .filter((item) => {
            const id = String(item.sessionId ?? '')
            if (archived.has(id)) return false
            // 2026-10-15 真机修正: 正在跑的那一场永远是**本机当下的会话**。它可能同时出现在对端
            // 报来的清单里 (对端把本机这场的副本又报回来了), 但它不是"投影的投影" —— 少了这一条,
            // 对端会以为本机没有它, 反手把自己那场投影归档 (症状: 在手机上说一句, 那场就消失)
            if (peerSessionIds.has(id) && item.running !== true) return false
            if (item.running === true) return true
            return epoch === 0 || Number(item.updatedAt ?? 0) >= epoch
          })
          .map((item) => {
            const cwd = String(item.cwd ?? '')
            const workspace = workspaces.find(entry => entry.path !== '' && entry.path === cwd)
            return {
              id: String(item.sessionId ?? ''),
              title: String(item.title ?? item.projections?.values?.title ?? ''),
              cwd,
              workspace: workspace === undefined ? '' : workspace.title,
              running: item.running === true,
              blank: item.blank === true,
              updatedAt: Number.isFinite(item.updatedAt) ? Number(item.updatedAt) : 0,
            }
          })
      } catch (error) {
        warn(`peer: listing sessions failed: ${messageOf(error)}`)
      }
    }
    summariesCache = { at: Date.now(), value }
    return value
  }

  /** 工作区注册表里那个"归档集" (会话从各个分组面隐掉那一份): 同步面按它过滤 */
  function ownArchivedSessionIds() {
    const registry = ctx.get('workspaceRegistry')
    const ids = registry?.archivedSessionIds
    if (!Array.isArray(ids)) return new Set()
    return new Set(ids.map(String))
  }

  /**
   * "从相连时才开始"那条线 (ms): 没有值时落成**现在**并记进账。
   *
   * 第一次问"本机有多少会话"就落, 于是这台机器上与对端相连之前的老会话一次都不会被报出去;
   * 之后重启沿用账里那一个 (不重新起算, 否则每次重启都会再丢一段)。
   * @param {number} now - 当前毫秒。
   * @returns {number} 那一线; 0 = 账还没读回来 (这一档不过滤, 免得把刚开的会话也滤掉)。
   */
  function peerEpoch(now) {
    if (peerEpochAt === 0) {
      if (!peerStateDone) return 0
      peerEpochAt = now
      void persistPeerSyncState()
    }
    return peerEpochAt
  }

  /**
   * 从 request history 折出等价的事件流 (sessionQuery 不在时的退路)。
   * 每一对 user/assistant 折成一轮, 于是 assistant 的那段话算"已落定", 不会变成草稿
   * @param {unknown} messages - session.deriveMessages() 的产物。
   * @returns {Array<object>} 事件流。
   */
  function eventsFromMessages(messages) {
    const events = []
    let seq = 0
    let turn = 0
    for (const message of Array.isArray(messages) ? messages : []) {
      if (message === null || typeof message !== 'object') continue
      if (message.role === 'user' && message.source?.kind === 'user') {
        turn += 1
        events.push({ type: 'turn/start', seq: ++seq, time: 0, data: { turn } })
        events.push({ type: 'user/message', seq: ++seq, time: 0, data: { content: message.content, source: message.source } })
        continue
      }
      if (message.role === 'assistant') {
        events.push({ type: 'assistant/message', seq: ++seq, time: 0, data: { turn, step: 1, message: { content: message.content } } })
        events.push({ type: 'turn/end', seq: ++seq, time: 0, data: { turn, reason: { kind: 'completed' } } })
      }
    }
    return events
  }

  /**
   * 现算一份投影快照 (对端要的那一份"只要回答")。
   * 事件优先走宿主的 sessionQuery (readSurface 给的是模型历史顺序的 surface 事件), 没有这一层
   * 服务时退回 live Session 的 deriveMessages(); 两条都拿不到 = 没有这一场
   * @param {string} sessionId - 会话 id。
   * @returns {Promise<object|null>} 快照; 没有这一场是 null。
   */
  async function projectSession(sessionId) {
    const live = ctx.sessions.get(sessionId)
    let title = ''
    let cwd = ''
    let running = false
    if (live !== undefined) {
      cwd = String(live.header?.cwd ?? '')
      const projections = ctx.get('sessionProjections')
      try {
        title = String(projections?.stateOf?.(live, 'title')?.title ?? '')
      } catch {
        title = ''
      }
      running = ctx.get('agents')?.get?.(sessionId)?.status === 'running'
    } else {
      const summary = (await ownSessions()).find(entry => entry.id === sessionId)
      if (summary === undefined) return null
      title = summary.title
      cwd = summary.cwd
      running = summary.running
    }
    let events = null
    const query = ctx.get('sessionQuery')
    if (query !== undefined && typeof query.readSurface === 'function') {
      try {
        const surface = await query.readSurface(sessionId)
        if (Array.isArray(surface?.events)) events = surface.events
      } catch (error) {
        warn(`peer: reading the surface of ${sessionId} failed: ${messageOf(error)}`)
      }
    }
    if (events === null && live !== undefined) events = eventsFromMessages(live.deriveMessages())
    if (events === null) return null
    // running 要进来: 主路载体 (readSurface) 里没有 turn/end, "最后那一轮落没落定"只有它说了算 ——
    // 少了这一句, 每一轮的回答都判成"还在流", 投影里就永远只有主人那几句话
    const condensed = condense(events, { maxEntries: liveSpec.maxEntries, maxChars: liveSpec.maxChars, running })
    return {
      snapshot: liveSnapshot({
        deviceId: engine.deviceId === 'pending' ? '' : engine.deviceId,
        deviceName: resolved.deviceName,
        sessionId,
        title,
        cwd,
        running,
        entries: condensed.entries,
        draft: condensed.draft,
        truncated: condensed.truncated,
        updatedAt: Date.now(),
      }),
      // 全部已落定的条目 (不截窗口): 对话账 conv/ 与"补历史"那一侧要的就是这一份
      entries: condensed.all,
    }
  }

  /**
   * 把这一场"只要回答"的那本账追加进 `conv/<本机 id>/<会话>/` (2026-10-22, 方案文档 §5.4)。
   *
   * 与 live 快照同一份条目, 区别只有窗口: live 只留最近 40 条 (滚动), 这本**只追加** ——
   * 对端离线一轮再回来时, 窗口可能已经滑过它的游标, 中间那段只有这里补得回来
   * @param {string} sessionId - 会话 id。
   * @param {{snapshot: object, entries: Array<object>}} projected - `projectSession` 的产物。
   * @returns {Promise<{appended: number, error?: string}>} 追加结果 (失败只记账, 不抛)。
   */
  async function writeTranscript(sessionId, projected) {
    if (projected === null || projected === undefined) return { appended: 0 }
    // 投影会话是对端那一场的本机副本: 对话账要的是"这场对话归谁", 对端自己那本才是权威 ——
    // 本机再记一份只会在仓库里多出一整本重复对话 (镜像也不收它们的会话日志, 同一条理由)
    if (neverSyncSessionIds.has(sessionId)) return { appended: 0, skipped: 'projection' }
    // 在对端影子工作区里开的会话同理 (它们属于对端那一侧)
    if (isShadowPath(String(projected.snapshot?.cwd ?? ''))) return { appended: 0, skipped: 'shadow' }
    const deviceId = engine.deviceId === 'pending' ? '' : engine.deviceId
    if (deviceId === '') return { appended: 0, error: 'no device id yet' }
    try {
      return await appendTranscriptEntries(transcriptDir(repoDir, deviceId, sessionId), projected.entries, {
        deviceId,
        sessionId,
        title: projected.snapshot?.title ?? '',
        cwd: projected.snapshot?.cwd ?? '',
      })
    } catch (error) {
      warn(`peer: writing the transcript of ${sessionId} failed: ${messageOf(error)}`)
      return { appended: 0, error: messageOf(error) }
    }
  }

  /**
   * 把一条用户消息投进某一轮 (归属方执行那一条的落点)。
   *
   * 写法照已经真机跑通的 `Luwi/host-plugin/index.mjs` 的 voiceDeliver: resolveAgent 会把
   * 冷会话恢复起来, 有正在跑的轮就 steer 插进去, 没有就 followup 起一轮 (followup 才是"起一轮",
   * agent.inject 不起), 投完 flush 一次才算真的落了盘
   * @param {string} sessionId - 目标会话。
   * @param {object} message - createUserMessage 的产物。
   * @returns {Promise<{running: boolean}>} 那一轮是插进去的还是新排的。
   */
  async function deliverToAgent(sessionId, message) {
    const controller = ctx.get('sessionController')
    if (controller === undefined || typeof controller.resolveAgent !== 'function') {
      throw new Error('this profile has no session controller, so a cross-device line has nowhere to go')
    }
    const run = async () => {
      const resolvedAgent = await controller.resolveAgent(sessionId)
      if (resolvedAgent !== undefined && 'error' in resolvedAgent) throw resolvedAgent.error
      const agent = resolvedAgent.agent
      const running = agent.status === 'running'
      if (running) agent.steer(message)
      else agent.followup(message)
      await ctx.sessions.flush(agent.session)
      return { running }
    }
    const agents = ctx.get('agents')
    return agents !== undefined && typeof agents.withoutInitiator === 'function'
      ? await agents.withoutInitiator(run)
      : await run()
  }

  /**
   * 目标解析: 会话 id 直接用; 工作区名落到它最近那一场 (没有就新开一场); `new` 一律新开一场。
   *
   * `new` 是 2026-10-14 加的那一种: 手机那侧在影工作区里新开的一场要说"对端也新开一场", 而
   * `workspace` 那一种会**复用最近那一场** —— 那一句就倒进别人正在跑的那场会话里去了
   * @param {{kind: string, id: string, name: string}} target - 规范化后的目标。
   * @returns {Promise<string>} 会话 id (解析不出来给空串)。
   */
  async function resolvePeerTarget(target) {
    if (target.kind === 'session') return target.id
    const wanted = target.name !== '' ? target.name : target.id
    const workspaces = ownWorkspaces()
    const named = wanted === ''
      ? undefined
      : workspaces.find(entry => entry.title === wanted || entry.path === wanted || entry.path.endsWith(`/${wanted}`))
        ?? workspaces.find(entry => bareWorkspaceName(entry.title) === bareWorkspaceName(wanted))
        // 影子目录名把分隔符压成了 `-` (`Mobile-AI\dsh` -> `Mobile-AI-dsh`), 老消息里带的就是它;
        // 光比字符串永远对不上, 于是整句被兜底投到"最近那个工作区" (2026-10-22 主人实测:
        // 在手机的 `Mobile-AI\dsh（PC）` 里发, PC 那边却落在 `D:\apk`)
        ?? workspaces.find(entry => squashWorkspaceName(entry.title) === squashWorkspaceName(wanted))
        ?? workspaces.find(entry => squashWorkspaceName(entry.path) === squashWorkspaceName(wanted) || squashWorkspaceName(entry.path).endsWith(squashWorkspaceName(wanted)))
    // 名字空或对不上都兜底: 队列消息里 kind:'new' 常常不带 name (2026-10-21 两端 5 条消息全是这种),
    // 从前这里直接返回空串, 接收端只能回一条 "could not resolve the target to a session on this host",
    // 于是跨端"新开一场提问"永远投不进去
    const workspace = named ?? await fallbackWorkspaceForDelivery()
    if (workspace === undefined) return ''
    if (named === undefined) {
      // 兜底要留一行日志, 否则"这一句落到了哪个工作区"只能靠猜
      logger.info(`peer: target ${JSON.stringify(wanted === '' ? { kind: target.kind } : target)} matched no workspace on this host; using ${JSON.stringify(workspace.title)}`)
    }
    return await openSessionInWorkspace(workspace, target.kind === 'new')
  }

  /**
   * 名字尾巴上的 `（设备名）` 去掉 —— 对端报来的工作区名可能带一层标注
   * (`annotateWorkspaceNames` 写出来的 `dsh（PC）`), 而本机注册表里那一条叫 `dsh`
   * @param {unknown} value - 原始名字。
   * @returns {string} 裸名字。
   */
  function bareWorkspaceName(value) {
    return String(value ?? '').replace(/（[^（）]*）\s*$/u, '').trim()
  }

  /**
   * 名字的"骨架": 去掉一切非字母数字的东西再比 (`Mobile-AI\dsh` / `Mobile-AI-dsh` /
   * `mobile_ai dsh` 都归一成 `mobileaidsh`)。
   *
   * 影子目录名是 safeSegment 出来的, 分隔符全被压成 `-`; 对端工作区名里还带着 `\` 或 `（设备名）`。
   * 不归一的话, 一条从影子组发出的消息在对端永远匹配不到工作区, 只能落到"最近那个" (见 resolvePeerTarget)
   * @param {unknown} value - 任意名字或路径。
   * @returns {string} 归一后的骨架。
   */
  function squashWorkspaceName(value) {
    return String(value ?? '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')
  }

  /**
   * 兜底投递目标: 最近有活动的那一场会话所在的工作区; 一场都没有就取第一个非影子工作区。
   * @returns {Promise<object|undefined>} 工作区条目 (没有工作区时 undefined)。
   */
  async function fallbackWorkspaceForDelivery() {
    const workspaces = ownWorkspaces()
    if (workspaces.length === 0) return undefined
    let sessions = []
    try {
      sessions = await ownSessions()
    } catch (error) {
      warn(`peer: listing sessions for the delivery fallback failed: ${messageOf(error)}`)
    }
    const recent = [...sessions].sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0))
    for (const entry of recent) {
      const cwd = String(entry.cwd ?? '')
      if (cwd === '') continue
      const workspace = workspaces.find(item => item.path !== '' && item.path === cwd)
      if (workspace !== undefined) return workspace
    }
    return workspaces[0]
  }

  /**
   * 在一个工作区里落一场会话: `fresh` 为真时一律新开, 否则复用最近那一场 (没有就新开)。
   * @param {object} workspace - `ownWorkspaces()` 的一条。
   * @param {boolean} fresh - 是否强制新开一场。
   * @returns {Promise<string>} 会话 id (没有 sessionController 时给空串)。
   */
  async function openSessionInWorkspace(workspace, fresh) {
    const controller = ctx.get('sessionController')
    if (controller === undefined || typeof controller.create !== 'function') return ''
    if (!fresh) {
      const sessions = (await ownSessions()).filter(entry => entry.cwd === workspace.path && entry.blank !== true)
      if (sessions.length > 0) return sessions[0].id
    }
    const opened = await controller.create({ workspaceId: workspace.id })
    return String(opened?.sessionId ?? '')
  }

  /** 插件自己的账 (队列幂等与待发读数都写在这里, 与会话/工作区元数据同一个 storage 记录) */
  async function readPluginState() {
    return await ensureMeta()
  }

  async function patchPluginState(patch) {
    await withMeta(async () => {
      const current = await ensureMeta()
      const next = { ...current, ...patch }
      const table = await tablePromise
      await table.put(STATE_KEY, next)
      metaCache = next
    })
  }

  /**
   * 本机"投影会话"的 id 账 (归属方执行那条闸认它 —— 见下面的 agent/pre-step)。
   * Luwi 加 (2026-10-20): 它同时是镜像的"永不上传"名单 (投影会话是对端那一场的本地视图,
   * 传上去会让对端多出一场 cwd 指向本机影子的重复会话), 所以直接复用上面那个 Set
   */
  const proxyLocalIds = neverSyncSessionIds

  /**
   * 退下来的投影会话: 本机会话 id -> 对端会话 id。
   *
   * 重建投影时老那一场会被归档, 但它仍然是"对端那一场" —— 主人要是正好开着它打字, 那一句还是要
   * 送给归属方执行, 不能就地跑本机模型 (2026-10-14 主人报的那一条)
   */
  const retiredProxies = new Map()

  /**
   * 对端现在有哪几场会话 (它 `/peer/info` 报上来的 id, 只存内存, 每 3 s 刷一次)。
   *
   * 三件事都用它: ① 归属方那条闸 —— 命中它就是"这一场是对端的" (本机那些把**对端会话文件搬进来**
   * 的副本也算在内); ② 只把对端的会话做成投影 (它自己归档掉的不做); ③ 把搬进来的那些副本从本机
   * 列表里隐掉 —— 它们本来是"全部对话"的那一半 (见 `hidePeerCopies()`)
   */
  const peerSessionIds = new Set()

  /**
   * "从相连时才开始同步, 过往不计"那条线 (ms)。
   *
   * 第一次要报本机会话时就落成"现在"并记进账 —— 于是这台机器上与对端相连之前的老会话从此不在
   * 同步面里 (对端不再为它们建投影, 本机也不再把它们报出去)。之后重启沿用账里那一个, 不重新起算
   */
  let peerEpochAt = 0

  /** 账本读回来了没有 (读回来之前 `peerEpoch()` 不过滤, 也不起算) */
  let peerStateDone = false

  /** 哪些"对端搬进来的副本"已经查过一遍了 (列一次会话覆盖全部; 只查对端新报上来的 id) */
  const copiesChecked = new Set()

  /** 无主的投影会话扫过一遍没有 (每趟起来扫一次: 它是给老版本留下的那些行做的一次性收尾) */
  let projectionsSwept = false

  /**
   * **本插件自己隐掉的**那些会话 id (账里存一份)。
   *
   * 两个用处: ① 只对自己隐过的那些做"放回来" (主人的归档是主人的决定, 一个都不碰);
   * ② 万一判据错了把本机自己那一场隐掉, 下一轮就看得出来并放回去
   */
  const peerHidden = new Set()

  /** 当前时刻 (测试与"相连那一条线"共用同一个入口) */
  const nowMs = () => Date.now()

  const peerRuntime = createPeerRuntime({
    spec: peerSpec,
    live: liveSpec,
    repoDir,
    ownDeviceId: () => (engine.deviceId === 'pending' ? '' : engine.deviceId),
    ownDeviceName: () => resolved.deviceName,
    readPeerCards: async () => {
      const { cards } = await readPeerCards(repoDir)
      return cards.map(card => ({
        deviceId: card.deviceId,
        name: card.name,
        url: card.url,
        updatedAt: card.updatedAt,
        self: card.deviceId !== '' && card.deviceId === peerIdentity.deviceId,
      }))
    },
    listWorkspaces: async () => ownWorkspaces(),
    listSessions: async () => ownSessions(),
    projectSession,
    // 对话账 conv/ 的写口 (与 live 快照同一份条目, 见 writeTranscript)
    writeTranscript,
    deliver: deliverToAgent,
    resolveTarget: resolvePeerTarget,
    makeUserMessage: async (text) => createUserMessage({
      content: [{ type: 'text', text }],
      // kind 必须留 user (它决定界面把这条画成"主人自己说的"), via 才是"从对端来的"那个标记
      source: { kind: 'user', via: 'peer' },
    }),
    readState: readPluginState,
    writeState: patchPluginState,
    // 这里返回**真实结果**: 发消息那条路要拿它判断"这一句到底推上去没有" (queue 的 ok/pushError
    // 就是这里来的)。2026-10-22 一度改成 fire-and-forget, 结果 `send()` 收到 undefined,
    // 明明推成功了也写"推送失败" —— 要止住 pull 里的自锁, 改的是 consumeInbox 那一侧不 await
    pushRepo: async () => await pushSync(),
    // 本机投影会话的 id 账 (运行时把它交给归属方那条闸; 现在先保持同一条来源)
    proxySessions: () => [...proxyLocalIds],
    now: () => Date.now(),
    warn,
    logger,
  })

  // --- Luwi 加 (G1-2): 进行中的回答 —— 一场会话跑起来之后, 把"只要回答"的那一份写进
  // <repoDir>/live/<本机id>/<sessionId>.json (退路载体; 主路是对端直接问 /peer/transcript)。
  // 去抖 400 ms: 一轮里 assistant/message 会来好几次, 每来一次重写一份没有必要
  if (liveSpec.enabled) {
    ctx.effect(() => {
      /** sessionId -> 待跑的定时器 */
      const pending = new Map()
      const schedule = (sessionId) => {
        if (pending.has(sessionId)) return
        const timer = setTimeout(() => {
          pending.delete(sessionId)
          void peerRuntime.writeProjection(sessionId)
        }, 400)
        if (typeof timer.unref === 'function') timer.unref()
        pending.set(sessionId, timer)
      }
      const off = ctx.on('session/event', (session, event) => {
        if (event.type !== 'turn/end' && event.type !== 'assistant/message' && event.type !== 'user/message') return
        schedule(String(session.id))
      })
      const sweeper = setInterval(() => {
        void peerRuntime.sweepProjections()
      }, 5 * 60_000)
      if (typeof sweeper.unref === 'function') sweeper.unref()
      return () => {
        // 假 ctx (测试里的最小 Context) 的 on 可能不回 disposer: 有才调, 别为这一下把卸载弄红
        if (typeof off === 'function') off()
        clearInterval(sweeper)
        for (const timer of pending.values()) clearTimeout(timer)
        pending.clear()
      }
    }, `${PLUGIN_NAME}.live-projections`)
  }

  // --- Luwi 加 (2026-10-20): pull 之后的两笔活, 各自 fail-soft (都不许把一次成功的 pull 变成失败):
  //   ① 对端投在仓库队列里的那几句话 (G1-2 的退路队列)
  //   ② 把**别的设备**的工作区命名空间物化到影子目录 —— 手机上的 `dsh（PC）` 那一组里因此
  //     既有投影会话、也能看见 PC 那边的工作区文件 (本机工作区一个字节都不动)
  async function materializeSyncShadowsFor(context) {
    const read = await readWorkspaceCards(repoDir)
    if (read.broken > 0) warn(`luwi-dsh-sync: ${read.broken} workspace card(s) in the mirror are unreadable`)
    if (read.cards.length === 0) return { workspaces: [], brought: 0, updated: 0, trashed: 0, failed: 0 }
    const meta = await ensureMeta()
    const result = await materializeSyncShadows({
      repoDir,
      shadowRoot,
      cards: read.cards,
      ownDeviceId: meta.deviceId,
      trashDir: join(shadowRoot, '.trash'),
      wasMirroredFor: (mirrorDir) => (rel) => git.existsAtRev(context?.head, `${mirrorDir}/${rel}`),
    })
    if (result.brought > 0 || result.updated > 0 || result.trashed > 0) {
      logger.info(`sync: materialised ${result.workspaces.length} peer workspace(s) (${result.brought} new, ${result.updated} updated, ${result.trashed} trashed)`)
    }
    return result
  }

  engine.afterPull = async (context) => {
    let inbox = null
    if (peerSpec !== null) {
      try {
        // 与每 15 s 的收件箱轮询共用同一条单飞, 避免 pull 与轮询同时投递同一条
        inbox = await consumeInboxSoon()
      } catch (error) {
        warn(`peer: consuming the queue after the pull failed: ${messageOf(error)}`)
      }
      // 2026-10-20: 本机排出去的那几句, 回执里带回的会话 id 就是本机这一场的投影目标
      try {
        const bound = await bindQueuedReplies()
        if (bound > 0) logger.info(`peer: ${bound} queued line(s) now project a peer session`)
      } catch (error) {
        warn(`peer: binding the queue receipts after the pull failed: ${messageOf(error)}`)
      }
    }
    let shadows = null
    try {
      shadows = await materializeSyncShadowsFor(context)
    } catch (error) {
      warn(`sync: materialising the peer workspaces failed: ${messageOf(error)}`)
    }
    return { inbox, shadows }
  }

  // 队列消费不该只挂在 pull 的尾巴上: pull 慢、卡住或那一条是 P2P 会话搬进来的 (还没到下一趟
  // pull) 时, 对端发来的话就一直躺在工作树里 —— 2026-10-23 的一条 "你好" 从 12:46 躺到 13:39,
  // 手机那边看到的就是"发送后没回复". 这里每 15 s 主动看一眼, 与 pull 里的那一趟共用单飞.
  let inboxInFlight = null
  function consumeInboxSoon() {
    if (peerSpec === null) return Promise.resolve(null)
    if (inboxInFlight !== null) return inboxInFlight
    inboxInFlight = (async () => {
      try {
        const inbox = await peerRuntime.consumeInbox()
        if (inbox.delivered > 0 || inbox.failed > 0) {
          logger.info(`peer: consumed ${inbox.delivered} queued line(s)${inbox.failed > 0 ? `, ${inbox.failed} failed` : ''} (inbox poll)`)
        }
        return inbox
      } catch (error) {
        warn(`peer: consuming the queue failed: ${messageOf(error)}`)
        return null
      } finally {
        inboxInFlight = null
      }
    })()
    return inboxInFlight
  }
  ctx.effect(() => {
    const timer = setInterval(() => { void consumeInboxSoon() }, 15_000)
    if (typeof timer.unref === 'function') timer.unref()
    return () => clearInterval(timer)
  }, `${PLUGIN_NAME}.peer-inbox-poll`)

  // --- Luwi 加 (G1-2): 对端工作区的**影子目录与投影会话**, 以及「指令只能由归属方执行」那条闸。
  //
  // 为什么要有影子目录: 侧栏把会话归进工作区分组的硬判据是"日志里的 cwd 在本机 canonical 解析
  // 后等于那个工作区路径" (packages/workspace/workspace/src/entity.ts:101-103)。对端会话记的是
  // 对端路径, 本机解析不到 —— 所以本地为每个对端工作区建一个目录并注册成工作区。
  //
  // 为什么组里放的是**投影会话**而不是对端那份文件: 本机不许写对端那份日志 (两边都追加会被判
  // APPEND_BOTH, 每发一次远程指令分叉一次)。投影会话是本地自己的一场 (不同 id, 不同文件),
  // cwd 写在影子里 (于是天然进组), 内容只放 user 与 assistant 文本 —— 本地写它没有任何合并后果
  const proxyRegistry = new Map()
  const shadowRegistry = new Map()
  let shadowDevice = { id: '', name: '' }
  let shadowBusy = false
  /** 已经为哪一场打过"退到仓库那份去折"的那行日志 (只报一次, 不刷屏) */
  let liveFallbackNoted = ''
  /** 已经为哪一场说过"从对话账里补了历史"(一轮只打一行日志) */
  let transcriptFillerNoted = ''
  /**
   * 排进仓库队列、还没等到回执的那几句话 (2026-10-20)。
   * 队列是"对端下次拉取才收到", 所以发出去的那一刻拿不到对端会话 id; 回执里带回来的那个 id
   * 是本机把这一场绑成投影的唯一线索 —— 没有这一步, 队列发出去的回答永远回不到本机窗口
   * @type {Array<{id: string, localId: string, text: string, deviceId: string, at: number}>}
   */
  let queuedLines = []
  /** 队列那几句最多记多少条 (够覆盖一个拉取周期的往返就行) */
  const MAX_QUEUED_LINES = 20
  const shadowsOn = peerSpec !== null && peerSpec.proxy
  // 上一次运行留下的账先读回来 (投影游标 + 影子工作区 id)。刷新之前 await 它 —— 没有这一步,
  // 重启后第一次刷会把对端那一场从头再折一遍
  const proxyStateReady = restoreProxyState()
  // 不开投影那一档没有投影账可读 (逐字节副本模式): "相连那一条线"当场就算定下来
  // Luwi 加 (2026-10-20): 影子账本**总是**读回 —— 同步来的工作区文件也要落在那些影子目录里,
  // 于是关着对端通道时那几组照样看得见
  if (!shadowsOn) peerStateDone = true

  /** 影子目录的根 (2026-10-20 起在更早那一段就定了, 这里只留一行注释; 见 shadowRoot 的定义) */
  /** 影子根的 realpath (junction 那一路的拼法); undefined = 还没算过, '' = 算不出来 */
  let shadowRootCanonical

  /** 一个文件名的安全段 (设备 id 拼进目录名) */
  const safeSegment = (value) => String(value ?? '').replace(/[^0-9A-Za-z._-]/gu, '').slice(0, 64) || 'unknown'

  /** 读回上一次运行留下的账 (投影会话的游标 + 影子工作区的 id) */
  async function restoreProxyState() {
    try {
      const stored = await readPluginState()
      for (const [key, value] of Object.entries(stored?.peerProxies ?? {})) {
        const localId = typeof value?.localId === 'string' ? value.localId : ''
        if (localId === '') continue
        proxyRegistry.set(key, {
          localId,
          lastSeq: Number.isFinite(value?.lastSeq) ? Number(value.lastSeq) : 0,
          openTurn: Number.isFinite(value?.openTurn) ? Number(value.openTurn) : null,
          turnCounter: Number.isFinite(value?.turnCounter) ? Number(value.turnCounter) : 0,
          pendingTexts: Array.isArray(value?.pendingTexts) ? value.pendingTexts.filter(text => typeof text === 'string').slice(-5) : [],
          revision: Number.isFinite(value?.revision) ? Number(value.revision) : -1,
          running: value?.running === true,
          // 老账本没有这一个: 它消费过的是 v1 那版条目 (只有用户那几句), 于是下一轮会被重做
          snapshotV: Number.isFinite(value?.snapshotV) ? Number(value.snapshotV) : LIVE_SNAPSHOT_VERSION_LEGACY,
        })
        proxyLocalIds.add(localId)
      }
      for (const [key, value] of Object.entries(stored?.peerShadows ?? {})) {
        const workspaceId = typeof value?.workspaceId === 'string' ? value.workspaceId : ''
        if (workspaceId !== '') shadowRegistry.set(key, { workspaceId, dir: '', label: '' })
      }
      // 退下来的投影 (本机 id -> 对端会话 id): 归属方那条闸在重建之后还要认得它们
      for (const [localId, peerSessionId] of Object.entries(stored?.peerRetired ?? {})) {
        if (typeof peerSessionId === 'string' && peerSessionId !== '') retiredProxies.set(String(localId), peerSessionId)
      }
      // 「从相连时才开始同步」那一条线: 账里有就沿用 (重启不重新起算)
      if (Number.isFinite(stored?.peerEpochAt) && Number(stored.peerEpochAt) > 0) peerEpochAt = Number(stored.peerEpochAt)
      // 自己隐过的那批本机会话 (只有它们会被"放回来")
      for (const id of Array.isArray(stored?.peerHidden) ? stored.peerHidden : []) {
        if (typeof id === 'string' && id !== '') peerHidden.add(id)
      }
      // 排进队列、还没等到回执的那几句 (重启之后照样要能绑上)
      for (const line of Array.isArray(stored?.peerQueued) ? stored.peerQueued : []) {
        const id = typeof line?.id === 'string' ? line.id : ''
        const localId = typeof line?.localId === 'string' ? line.localId : ''
        if (id === '' || localId === '') continue
        queuedLines.push({
          id,
          localId,
          text: typeof line?.text === 'string' ? line.text : '',
          deviceId: typeof line?.deviceId === 'string' ? line.deviceId : '',
          at: Number.isFinite(line?.at) ? Number(line.at) : 0,
        })
      }
    } catch (error) {
      warn(`peer: restoring the projection ledger failed: ${messageOf(error)}`)
    } finally {
      peerStateDone = true
    }
  }

  /** 把刚排进队列的那一句记下来 (回执回来时靠它把对端那一场绑成本机这一场的投影) */
  function rememberQueuedLine(line) {
    if (typeof line?.id !== 'string' || line.id === '' || line.localId === '') return
    queuedLines = [...queuedLines.filter(entry => entry.id !== line.id), line].slice(-MAX_QUEUED_LINES)
  }

  /**
   * 消费回执: 队列里那几句被对端投递之后, 回执带回来的会话 id 就是本机这一场的投影目标。
   * 没有这一步, 走队列发出去的回答永远回不到本机窗口 (拨不通的常态下全是这条路)。
   * @returns {Promise<number>} 这一趟绑上了几场。
   */
  async function bindQueuedReplies() {
    if (queuedLines.length === 0) return 0
    const acks = await peerRuntime.outboundAcks()
    if (acks.size === 0) return 0
    let bound = 0
    const settled = new Set()
    for (const line of queuedLines) {
      const ack = acks.get(line.id)
      if (ack === undefined) continue
      settled.add(line.id)
      const peerSessionId = typeof ack.sessionId === 'string' ? ack.sessionId : ''
      if (peerSessionId === '') {
        warn(`peer: the peer could not deliver ${line.id}${ack.error ? `: ${ack.error}` : ''}`)
        continue
      }
      const deviceId = shadowDevice.id !== '' ? shadowDevice.id : line.deviceId
      if (deviceId === '') continue
      const key = `${deviceId}|${peerSessionId}`
      if (proxyRegistry.has(key)) continue
      proxyRegistry.set(key, {
        localId: line.localId,
        lastSeq: 0,
        openTurn: null,
        turnCounter: 0,
        pendingTexts: line.text === '' ? [] : [line.text],
        // 逼下一趟去取一次快照 (拨不通时走仓库那份)
        revision: -1,
        running: false,
        snapshotV: 0,
      })
      proxyLocalIds.add(line.localId)
      bound += 1
      logger.info(`peer: the queued line landed in ${peerSessionId}; this session now projects it`)
    }
    if (settled.size === 0) return 0
    queuedLines = queuedLines.filter(line => !settled.has(line.id))
    await persistProxyState()
    return bound
  }

  /**
   * 拨不通对端时的"离线折回": 投影账上已有的那几场, 一份一份从仓库里的 live 拷贝折进来。
   * 不新建影子、不建投影、不归档 —— 那三件事都要对端报一份权威的表, 拨不通时没有。
   * @returns {Promise<{folded: number, appended: number}>} 折了几场 / 追加了几条事件。
   */
  async function foldFromRepo() {
    let folded = 0
    let appended = 0
    for (const [key, record] of proxyRegistry) {
      const peerSessionId = key.split('|')[1] ?? ''
      if (peerSessionId === '') continue
      const session = ctx.sessions.get(record.localId)
      if (session === undefined || session === null) continue
      try {
        const result = await applyTranscript(record, peerSessionId, shadowRegistry.get(key))
        if (result.appended > 0 || result.rebuilt) {
          folded += 1
          appended += result.appended
        }
      } catch (error) {
        warn(`peer: folding ${peerSessionId} from the repository failed: ${messageOf(error)}`)
      }
    }
    if (appended > 0) await persistProxyState()
    return { folded, appended }
  }

  // 这一趟起，只把**此刻之后有更新**的 live 快照补成投影 (主人 2026-10-23: 不补旧对话,
  // 只从现在这个对话开始)。宿主重启后这个线也跟着重置，所以不会把历史快照一次性倒进来。
  const liveProjectSince = nowMs()

  /**
   * queue 模式下的"从 live 快照补投影"：对端每一轮都会把活跃会话写进仓库
   * `live/<它的 id>/<会话>.json`，这里给**还没有投影**的那些现开一场，开在对应的影子工作区里，
   * 于是 `apk（PC）` 这类分组不再只有主动发过消息的那几场 (2026-10-23 实测: 该分组 0 场)。
   * 只收 liveProjectSince 之后更新过的快照；上限沿用 peer.maxProxies。
   * @returns {Promise<number>} 新开了几场。
   */
  async function projectLiveSessionsFromRepo() {
    if (shadowDevice.id === '') return 0
    const dir = join(repoDir, 'live', safeSegment(shadowDevice.id))
    let names = []
    try {
      names = await readdir(dir)
    } catch {
      return 0
    }
    // 对端工作区分组按"名字"对：名片里的 title，或它的 basename (D:\Study -> Study)。
    // 注意 basename 必须两种分隔符都认：手机跑在 Android 上，node:path 的 basename 不认反斜杠，
    // `D:\apk` 会整个当成一个名字 (2026-10-23 实测就是这个把 apk（PC）一直留成 0 场)
    const baseName = (value) => {
      const parts = String(value ?? '').split(/[\\/]/u).filter(Boolean)
      return (parts.pop() ?? '').toLowerCase()
    }
    const byName = new Map()
    for (const [key, entry] of shadowRegistry) {
      const prefix = `${shadowDevice.id}|`
      if (!key.startsWith(prefix)) continue
      const name = key.slice(prefix.length)
      byName.set(name.toLowerCase(), entry)
      byName.set(baseName(name), entry)
    }
    let created = 0
    for (const name of names) {
      if (!name.endsWith('.json')) continue
      if (proxyRegistry.size >= peerSpec.maxProxies) break
      const peerSessionId = name.replace(/\.json$/u, '')
      const key = `${shadowDevice.id}|${peerSessionId}`
      if (proxyRegistry.has(key)) continue
      let snapshot
      try {
        snapshot = JSON.parse(await readFile(join(dir, name), 'utf8'))
      } catch {
        continue
      }
      // "只从现在开始": 正在跑的、或最近半小时内还有更新的快照才算当下 —— 主人 2026-10-23
      // 明确不补旧对话，而当前这场这一轮的快照可能恰好落在本机启动之前
      const updatedAt = Number(snapshot?.updatedAt ?? 0)
      const current = snapshot?.running === true || (updatedAt > 0 && Date.now() - updatedAt <= 30 * 60_000)
      if (!current) continue
      const shadow = byName.get(baseName(snapshot?.cwd))
      if (shadow === undefined) continue
      const record = await ensureProxySession(peerSessionId, { title: String(snapshot?.title ?? '') }, shadow)
      if (record === null) continue
      proxyRegistry.set(key, record)
      created += 1
      try {
        await applyTranscript(record, peerSessionId, shadow)
      } catch (error) {
        warn(`peer: folding the live snapshot of ${peerSessionId} failed: ${messageOf(error)}`)
      }
    }
    if (created > 0) {
      await persistProxyState()
      logger.info(`peer: projected ${created} live session(s) from the repository`)
    }
    return created
  }

  /**
   * queue 模式下的对端刷新: 只读仓库里的东西, 一个 HTTP 请求都不发。
   *
   * 两件事: ① 用 `workspace-peers/<对端 id>.json` 那张名片把影子工作区注册出来 (没有这一步,
   * 全新启动的设备上连 `xxx（PC）` 这个分组都不会出现); ② 把已经绑定的投影按 `live/` 快照折一遍。
   * 名片只是对端自己报的清单, 没有"对端撤了哪一层"的权威语义, 所以这里**只建不收** —— 回收留给
   * HTTP 那条路 (拨得通时它有一份现清单)。
   * @returns {Promise<object>} {ok, queueOnly, shadows, folded, appended, error?}。
   */
  async function refreshPeerFromRepo() {
    const registry = ctx.get('workspaceRegistry')
    if (registry === undefined || typeof registry.create !== 'function') {
      return { ok: false, error: 'this profile has no workspace registry, so shadow workspaces cannot be registered' }
    }
    const read = await readWorkspaceCards(repoDir)
    if (read.broken > 0) warn(`peer: ${read.broken} workspace card(s) in the mirror are unreadable`)
    const meta = await ensureMeta()
    const ownId = String(meta.deviceId ?? '')
    const others = read.cards
      .filter(card => typeof card?.deviceId === 'string' && card.deviceId !== '' && card.deviceId !== ownId)
      .sort((left, right) => Number(right.at ?? 0) - Number(left.at ?? 0))
    let created = 0
    if (others.length > 1) {
      warn(`peer: ${others.length} peer workspace cards in the mirror; only the most recent one (${String(others[0].deviceId)}) becomes the shadow device`)
    }
    const card = others[0]
    if (card !== undefined) {
      shadowDevice = { id: String(card.deviceId), name: String(card.name ?? '') }
      const reported = Array.isArray(card.workspaces) ? card.workspaces : []
      if (reported.length > peerSpec.maxShadows) {
        warn(`peer: the peer card reports ${reported.length} workspaces; only the first ${peerSpec.maxShadows} become shadow groups (peer.maxShadows)`)
      }
      const picked = reported.slice(0, peerSpec.maxShadows)
      const labels = annotateWorkspaceNames({
        localDeviceName: resolved.deviceName,
        local: ownWorkspaces().map(workspace => ({ key: workspace.id, title: workspace.title })),
        peerDeviceName: shadowDevice.name,
        peer: picked.map((workspace, index) => ({ key: `${shadowDevice.id}|${index}`, title: String(workspace?.title ?? workspace?.slug ?? '') })),
      })
      for (const [index, workspace] of picked.entries()) {
        const name = String(workspace?.title ?? workspace?.slug ?? '')
        if (name === '') continue
        const label = labels.peer[index]?.label ?? name
        const key = `${shadowDevice.id}|${name}`
        const dir = join(shadowRoot, safeSegment(shadowDevice.id), shadowDirName(name, shadowDevice.id))
        try {
          mkdirSync(dir, { recursive: true })
        } catch (error) {
          warn(`peer: creating the shadow directory ${dir} failed: ${messageOf(error)}`)
          continue
        }
        const entry = shadowRegistry.get(key)
        try {
          if (entry === undefined) {
            const entity = await registry.create(dir, label)
            shadowRegistry.set(key, { workspaceId: String(entity.id), dir, label })
            created += 1
            continue
          }
          entry.dir = dir
          entry.label = label
        } catch (error) {
          warn(`peer: registering the shadow workspace ${dir} failed: ${messageOf(error)}`)
        }
      }
    }
    await persistProxyState()
    // 2026-10-23: 轮号已改成从 1 起（见 ensureProxySession），这里恢复"从现在开始"的自动补投影
    const projected = await projectLiveSessionsFromRepo()
    const folded = await foldFromRepo()
    return { ok: true, queueOnly: true, shadows: created, projected, ...folded }
  }

  // 名片里报哪些工作区: 开文件同步时就是那一份源; 档 A (workspace.enabled=false) 下也不能空着 ——
  // 对端要靠它建 `apk（PC）` 这类影子分组, 空表会让对端那边所有工作区分组都是 0 场 (2026-10-23 实测)。
  // 只报名字 (slug + title), 一个文件都不进去, 与"不共享工作区文件"并不冲突。
  function workspaceCardEntries() {
    if (workspaceSources.length > 0) {
      return workspaceSources.map(source => ({ slug: source.slug, title: source.title }))
    }
    const entries = []
    const seen = new Set()
    for (const workspace of ownWorkspaces()) {
      const title = String(workspace.title !== '' ? workspace.title : workspace.path).trim()
      if (title === '') continue
      let slug = slugSegment(title, 60, 'workspace')
      while (seen.has(slug)) slug = `${slug}-${seen.size}`
      seen.add(slug)
      entries.push({ slug, title })
    }
    return entries
  }

  /** 把账写回 storage (投影游标 + 影子工作区 id + 退下来的投影 + 相连那一条线) */
  async function persistProxyState() {
    const proxies = {}
    for (const [key, value] of proxyRegistry) {
      proxies[key] = {
        localId: value.localId,
        lastSeq: value.lastSeq,
        openTurn: value.openTurn,
        turnCounter: value.turnCounter,
        pendingTexts: value.pendingTexts.slice(-5),
        revision: value.revision,
        running: value.running === true,
        snapshotV: Number.isFinite(value.snapshotV) ? value.snapshotV : LIVE_SNAPSHOT_VERSION_LEGACY,
      }
    }
    const shadows = {}
    for (const [key, value] of shadowRegistry) shadows[key] = { workspaceId: value.workspaceId }
    try {
      await patchPluginState({ peerProxies: proxies, peerShadows: shadows, peerRetired: retiredObject(), peerEpochAt, peerHidden: [...peerHidden], peerQueued: queuedLines })
    } catch (error) {
      warn(`peer: writing the projection ledger failed: ${messageOf(error)}`)
    }
  }

  /** 只把相连那一条线记下来 (那次写发生在别的东西都还没定下来的时候) */
  async function persistPeerSyncState() {
    try {
      await patchPluginState({ peerEpochAt })
    } catch (error) {
      warn(`peer: writing the sync epoch failed: ${messageOf(error)}`)
    }
  }

  /** 退下来的投影账 (只留最近这么多个: 它只服务"主人正好开着某一场老投影在打字"这一档) */
  function retiredObject() {
    const kept = [...retiredProxies.entries()].slice(-MAX_RETIRED_PROXIES)
    retiredProxies.clear()
    for (const [localId, peerSessionId] of kept) retiredProxies.set(localId, peerSessionId)
    return Object.fromEntries(kept)
  }

  /** 认得出这一场是不是投影会话 (归属方那条闸与行上那枚标记都用它) */
  function proxyOf(localId) {
    for (const [key, value] of proxyRegistry) {
      if (value.localId === localId) return { key, value, peerSessionId: key.split('|')[1] ?? '' }
    }
    // 重建/回收之后老那一场不在账上了, 但它还是"对端那一场" —— 那一句仍要送给归属方
    const retired = retiredProxies.get(localId)
    if (retired !== undefined) return { key: `${shadowDevice.id}|${retired}`, value: undefined, peerSessionId: retired, retired: true }
    return undefined
  }

  /**
   * 这一场是不是"影工作区里新开的本机会话" (Luwi 加, 归属方闸的第 ④ 条判据)。
   *
   * 手机 GUI 里那个 `apk（PC）` 分组是个**真工作区**, 点「新对话」就在本机开一场 cwd 落在影子根
   * 底下的会话 —— 它既不在投影账上, id 也不是对端的, 于是前三条判据一条都不命中, 那一句就地跑了
   * 本机模型 (而且跑在一个空目录里: 手机侧工作区镜像是关的, 连对端的文件都看不见)。
   * 2026-10-14 主人报的第三档就是它。
   *
   * 判据两半: ① cwd 落在影子根底下 (`isShadowPath`, 原样与 canonical 两条拼法都折); ② 它在
   * `shadowRegistry` 里对得上**哪一个**对端工作区 (最长前缀)。cwd 拿不到 (冷会话 header 里没有)
   * 时退回按工作区注册表的 sessionIds 查 —— 与 [isShadowWorkspace] 同一条口径 (id 优先)。
   * 对不上具体工作区、但确实在影子根底下时也给一个空名的结果: 那一句照样转出去, 由对端回一条明确的
   * 错, **本机绝不跑模型** (失败关闭)
   * @param {object} session - 宿主 Session (读 header.cwd 与 id)。
   * @returns {{name: string, deviceId: string}|undefined} 对端工作区名; 不是影子里的会话给 undefined。
   */
  function peerWorkspaceOfShadowSession(session) {
    /** 影子条目那条账的键: `<对端设备 id>|<对端工作区名>` */
    const deviceAndName = (key) => ({
      deviceId: key.split('|')[0] ?? '',
      name: key.split('|').slice(1).join('|'),
    })
    // 影子目录是刷新时现算的; 刚重启还没刷到 (账里 dir 空) 就自己算一遍, 与建目录那一处同一个算式
    const dirOf = (deviceId, name, entry) => {
      if (typeof entry?.dir === 'string' && entry.dir !== '') return entry.dir
      const device = deviceId !== '' ? deviceId : shadowDevice.id
      if (device === '') return ''
      return join(shadowRoot, safeSegment(device), shadowDirName(name, device))
    }
    const cwd = String(session?.header?.cwd ?? '')
    if (cwd === '') {
      const sessionId = String(session?.id ?? '')
      if (sessionId === '') return undefined
      const list = ctx.get('workspaceRegistry')?.list?.()
      if (!Array.isArray(list)) return undefined
      for (const workspace of list) {
        const ids = Array.isArray(workspace?.sessionIds) ? workspace.sessionIds.map(String) : []
        if (!ids.includes(sessionId)) continue
        for (const [key, entry] of shadowRegistry) {
          if (entry.workspaceId !== String(workspace?.id ?? '')) continue
          return deviceAndName(key)
        }
        // 落在影子注册表里没有的那一层 (退役/重建之间): 认得出是影子, 认不出是哪一个工作区
        return { name: '', deviceId: shadowDevice.id }
      }
      return undefined
    }
    if (!isShadowPath(cwd)) return undefined
    const folded = canonicalOf(cwd)
    const candidates = folded === cwd ? [cwd] : [cwd, folded]
    let best
    let bestLength = -1
    for (const [key, entry] of shadowRegistry) {
      const found = deviceAndName(key)
      const dir = dirOf(found.deviceId, found.name, entry)
      if (dir === '') continue
      for (const candidate of candidates) {
        if (!pathUnder(candidate, dir)) continue
        if (dir.length > bestLength) {
          bestLength = dir.length
          best = found
        }
      }
    }
    if (best !== undefined) return best
    // 2026-10-20: 影子注册表是"对端 `/peer/info` 报了什么"的产物 —— 对端拨不通时它是空的, 于是
    // 这一句会带着**空工作区名**发出去, 对端只能回一条"解析不到这个目标" (真机上量到的那一条)。
    // 影子目录名本身就是工作区名 (shadowDirName 的产物), 所以这里退回从 cwd 里取第一段
    const deviceRoot = shadowDevice.id === '' ? '' : join(shadowRoot, safeSegment(shadowDevice.id))
    if (deviceRoot !== '') {
      for (const candidate of candidates) {
        if (!pathUnder(candidate, deviceRoot)) continue
        const first = relative(deviceRoot, candidate).split(/[\\/]+/u).filter(Boolean)[0] ?? ''
        if (first !== '') return { name: first, deviceId: shadowDevice.id }
      }
    }
    // 在影子里但对不上任何一条影子账: 仍然归对端, 只是说不出是哪个工作区 (对端会给一条明确的错)
    return { name: '', deviceId: shadowDevice.id }
  }

  /**
   * 把**对端搬进本机的那批会话副本**从各个分组面隐掉 (归档, 不删文件)。
   *
   * 它们是"同步了全部对话"的另一半: 上游的 `bring-missing` 取回把对端 sessionRoot 里的文件按字节
   * 搬进本机 —— 于是另一台机器上几十场老会话在本机的会话列表里各占一行 (手机本地会话目录里就有
   * `--D-apk--` / `--E-Mobile-AI--` 这些 PC 的 cwd)。而它们**在对端那边才算数**: 会话归属没变,
   * 只是本机存了一份。归档 = 从每个分组面隐掉、还能撤销, 文件与合并语义一个字节都不动。
   * @param {Set<string>} peerIds - 对端报的会话 id。
   * @returns {Promise<number>} 这一趟隐掉了几场。
   */
  async function hidePeerCopies(peerIds) {
    if (peerIds.size === 0 || typeof ctx.get('workspaceRegistry')?.archiveSession !== 'function') return 0
    // 这一趟要不要真的去列一遍会话: 对端报的 id 里还有没查过的才查 (列一次会覆盖所有会话,
    // 查过的记在内存里; 重启之后每个 id 至多再查一次)
    const archived = ownArchivedSessionIds()
    const fresh = [...peerIds].filter(id => !copiesChecked.has(id) && !archived.has(id))
    const needHeal = archived.size > 0 && peerHidden.size > 0
    // 无主的投影扫一遍: 每趟起来扫一次就够 (之后新掉下来的投影由上面那一段当场归档)
    const needSweep = !projectionsSwept
    if (fresh.length === 0 && !needHeal && !needSweep) return 0
    for (const id of peerIds) copiesChecked.add(id)
    const controller = ctx.get('sessionController')
    let listed = []
    try {
      if (controller !== undefined && typeof controller.list === 'function') {
        const answer = await controller.list({}, AbortSignal.timeout(12000))
        listed = Array.isArray(answer?.items) ? answer.items : []
      }
    } catch (error) {
      warn(`peer: listing sessions to hide the peer's copies failed: ${messageOf(error)}`)
      return 0
    }
    /** 本机会话 id -> 它的 cwd (下面两个判据都要看"这个路径在本机存不存在") */
    const cwdById = new Map()
    for (const item of listed) {
      const id = String(item?.sessionId ?? '')
      if (id !== '') cwdById.set(id, String(item?.cwd ?? ''))
    }
    projectionsSwept = true
    let hidden = 0
    for (const [id, cwd] of cwdById) {
      // 无主的投影会话: cwd 在影子根底下、而它已经不在追读的账上 (对端不再报它, 或本机换了一场
      // 新的) —— 也隐掉。2026-10-14 的那一版只会把记录摘掉, 于是这些行留在列表里不走
      // (真机上量到 64 行: 对端全部会话的投影)
      if (isShadowPath(cwd)) {
        // 无主的投影: 影子目录底下的本机会话, 而它已经不在追读的账上 —— 隐掉 (老版本只把记录
        // 摘掉, 行留在列表里; 2026-10-14 真机上量到 64 行)
        if (proxyLocalIds.has(id)) continue
        if (await archiveLocalSession(id, '')) hidden += 1
        continue
      }
      if (!peerIds.has(id)) continue
      // **只隐"对端那一份"**, 判据是它的 cwd 在本机**不存在** —— 对端会话记的是对端文件系统的路径
      // (手机上是 `D:\apk`, 电脑上是 `/storage/emulated/0/DSH`), 本机自己的会话 cwd 一定存在。
      // 少了这一条, "对端把本机会话的副本报回来"那一档 (bring-missing 是双向的) 会把本机**自己**
      // 那一场隐掉 —— 2026-10-14 在手机上真踩到 (三场本机会话被自己的插件隐了)
      if (cwd === '' || existsSync(cwd)) continue
      if (archived.has(id)) continue
      if (await archiveLocalSession(id, id)) {
        peerHidden.add(id)
        hidden += 1
      }
    }
    // 自己隐过、但现在看得出"那一场是本机自己的"(cwd 存在) 就放回来 —— 只碰自己隐过的那些
    let shown = 0
    for (const id of [...peerHidden]) {
      if (!archived.has(id)) { peerHidden.delete(id); continue }
      const cwd = cwdById.get(id)
      if (cwd === undefined || cwd === '' || !existsSync(cwd)) continue
      if (await unarchiveLocalSession(id)) {
        peerHidden.delete(id)
        shown += 1
      }
    }
    if (hidden > 0) logger.info(`peer: hid ${hidden} session(s) the peer owns and this host only carried`)
    if (shown > 0) logger.info(`peer: put back ${shown} local session(s) this host had hidden by mistake`)
    return hidden
  }

  /**
   * 归档一场本机会话 (从不分组面隐掉; 不是删)。
   * @param {string} localId - 本机会话 id (空 = 什么都不做)。
   * @param {string} peerSessionId - 它属于对端哪一场 (只用于日志与"退下来的投影"那一本账)。
   * @returns {Promise<boolean>} 真归档了就 true。
   */
  async function archiveLocalSession(localId, peerSessionId) {
    if (localId === '') return false
    const registry = ctx.get('workspaceRegistry')
    if (registry === undefined || typeof registry.archiveSession !== 'function') return false
    try {
      await registry.archiveSession(localId)
    } catch (error) {
      warn(`peer: hiding ${localId} (the peer's own session) failed: ${messageOf(error)}`)
      return false
    }
    proxyLocalIds.delete(localId)
    if (peerSessionId !== '') retiredProxies.set(localId, peerSessionId)
    return true
  }

  /**
   * 把自己隐错的那一场放回来 (归档是可撤销的, 这一条就是那个"撤销")。
   * @param {string} localId - 本机会话 id。
   * @returns {Promise<boolean>} 真放回来了就 true。
   */
  async function unarchiveLocalSession(localId) {
    const registry = ctx.get('workspaceRegistry')
    if (registry === undefined || typeof registry.unarchiveSession !== 'function') return false
    try {
      await registry.unarchiveSession(localId)
      return true
    } catch (error) {
      warn(`peer: putting ${localId} back failed: ${messageOf(error)}`)
      return false
    }
  }

  /**
   * 确保投影会话存在 (没有就新开一场, 并把标题照对端那一场起好)。
   * @param {string} peerSessionId - 对端会话 id。
   * @param {object} session - 对端报的摘要 {title, workspace, ...}。
   * @param {object|undefined} shadow - 它所属的影子工作区记录。
   * @returns {Promise<object|null>} 投影记录; 建不出来时 null。
   */
  async function ensureProxySession(peerSessionId, session, shadow) {
    const controller = ctx.get('sessionController')
    if (controller === undefined || typeof controller.create !== 'function') return null
    if (shadow === undefined || shadow.workspaceId === '') return null
    try {
      const created = await controller.create({ workspaceId: shadow.workspaceId })
      const localId = String(created?.sessionId ?? '')
      if (localId === '') return null
      try {
        await controller.rename({ sessionId: localId, title: proxyTitleOf(session?.title, peerSessionId) })
      } catch {
        // 标题只是好看: 改不动就用兜底名 (宿主那一边索引是原样的)
      }
      const live = ctx.sessions.get(localId)
      const record = {
        localId,
        lastSeq: 0,
        openTurn: null,
        // 轮号必须从 1 起: 宿主读盘时校验 `turn/start` 的 turn === nextTurn（1,2,3…），
        // 拿 live.seq 起算会写出 5,6,7…，整场日志直接判 corrupt（2026-10-23 实测）
        turnCounter: 0,
        pendingTexts: [],
        revision: -1,
        running: false,
        // 0 = 这一场还没消费过对端任何一版条目: 第一次照对端当前那一版记下, 不重做
        snapshotV: 0,
      }
      proxyLocalIds.add(localId)
      return record
    } catch (error) {
      warn(`peer: opening a projection session for ${peerSessionId} failed: ${messageOf(error)}`)
      return null
    }
  }

  /**
   * 把对端那一场的新条目折进投影会话。
   * @param {object} record - 投影记录。
   * @param {string} peerSessionId - 对端会话 id。
   * @param {object|undefined} shadow - 这一场归的那个影子工作区 (重做投影时要拿它开新会话)。
   * @returns {Promise<{appended: number, rebuilt: boolean}>} 追加了几条事件 / 有没有重做一场。
   */
  async function applyTranscript(record, peerSessionId, shadow) {
    // 要的是**对端那一份** (peerTranscript 是往对端问; transcript 是回答对端的提问)
    let answer = await peerRuntime.peerTranscript(peerSessionId)
    if (answer.ok !== true) {
      // 2026-10-20: 对端拨不通 (手机只监听自己的回环) 时的退路 —— 对端每一轮都会把"只要回答"
      // 的那份写进仓库 `live/<它的 id>/<会话>.json`, 随 pull 落到本机工作树。没有这一步,
      // "在 PC 上发、在 PC 上看回答" 在拨不通的设备上永远只是个空承诺
      const fallback = await peerRuntime.liveTranscript(peerSessionId)
      if (fallback.ok !== true) return { appended: 0, rebuilt: false }
      if (liveFallbackNoted !== peerSessionId) {
        liveFallbackNoted = peerSessionId
        logger.info(`peer: ${peerSessionId} is being folded from the repository copy (the peer is unreachable)`)
      }
      answer = fallback
    }
    const snapshot = answer.snapshot ?? {}
    const snapshotV = Number.isFinite(snapshot.v) ? Number(snapshot.v) : LIVE_SNAPSHOT_VERSION_LEGACY
    let rebuilt = false
    if (record.snapshotV === 0) record.snapshotV = snapshotV
    else if (snapshotV > record.snapshotV) {
      // 对端的条目换了一版语义 (v1 只折得出用户那几句, v2 才有已经落定的回答): 这场投影是拿
      // 旧口径折出来的, 后面补的 seq 都在游标之前 —— 不重做的话它永远只有用户那几句话。
      // 重做不成也把版本记下来: 否则每一轮都会为它多问一次对端, 而结果不会变
      rebuilt = await reopenProjection(record, peerSessionId, snapshot, shadow)
      record.snapshotV = snapshotV
      if (!rebuilt) warn(`peer: ${peerSessionId} is projected with the old entry version (v${snapshotV} is available)`)
    }
    // 折进来的轮次编号必须**接在本机那场会话的后面**: 界面的对话流是按 turn 归组的,
    // 而本机自己的那一轮已经用掉了 turn 1 (而且已经 turn/end)。再插一个 turn 1 进去,
    // 客户端会把它当成"已经结束的那一轮", 新回答就不画出来 (2026-10-22 实测: 会话文件里
    // 有回答, 打开着的界面一片空白, 退出重进才看得见)
    const localSession = ctx.sessions.get(record.localId)
    if (localSession !== undefined && localSession !== null) {
      const usedTurn = maxTurnOf(localSession)
      if (usedTurn > record.turnCounter) {
        record.turnCounter = usedTurn
        record.openTurn = null
      }
    }
    // 2026-10-22: 快照是个**滚动窗口** (最近 40 条)。本机投影的游标要是落在窗口之外
    // (对端离线了几轮), 窗口之前那一段既不在快照里、也不会再出现 —— 从仓库里那本只追加的
    // 对话账 (conv/) 里把它补回来, 再交给 proxyEventsFrom 按 seq 去重排序
    let entries = Array.isArray(snapshot.entries) ? snapshot.entries : []
    if (snapshot.truncated === true && record.lastSeq > 0) {
      const filler = await peerRuntime.repoTranscript(peerSessionId, record.lastSeq)
      if (filler.ok === true && filler.entries.length > 0) {
        const windowStart = entries.length > 0 ? Number(entries[0].seq ?? Number.POSITIVE_INFINITY) : Number.POSITIVE_INFINITY
        const missing = filler.entries.filter(entry => entry.seq > record.lastSeq && entry.seq < windowStart)
        if (missing.length > 0) {
          entries = [...missing, ...entries]
          if (transcriptFillerNoted !== peerSessionId) {
            transcriptFillerNoted = peerSessionId
            logger.info(`peer: ${peerSessionId} got ${missing.length} older entr(ies) from the repository transcript (the live window had moved past the cursor)`)
          }
        }
      }
    }
    const folded = proxyEventsFrom(entries, {
      lastSeq: record.lastSeq,
      openTurn: record.openTurn,
      turnCounter: record.turnCounter,
      pendingTexts: record.pendingTexts,
    })
    record.lastSeq = folded.lastSeq
    record.openTurn = folded.openTurn
    record.turnCounter = folded.turnCounter
    record.pendingTexts = folded.pendingTexts
    if (folded.events.length === 0) return { appended: 0, rebuilt }
    const session = await liveSessionOf(record.localId)
    if (session === null) return { appended: 0, rebuilt }
    const appended = appendProxyEvents(session, folded.events)
    if (appended < folded.events.length) {
      warn(`peer: only ${appended}/${folded.events.length} projection event(s) landed in ${record.localId}`)
    }
    return { appended, rebuilt }
  }

  /**
   * 重做一场投影: 老那一场**归档** (不是删 —— 工作区注册表的归档集就是"从各个分组面隐掉", 还能撤销),
   * 再照对端现在这一份快照开一场新的。
   *
   * 为什么非重做不可: 投影会话是本地自己的一场日志, 只追加、不能往中间插事件。旧口径漏掉的回答
   * 全部在游标之前, 补进去只会落在日志末尾, 折出一段"先一堆用户的话、再一堆回答"的乱序历史。
   * @param {object} record - 投影记录。
   * @param {string} peerSessionId - 对端会话 id。
   * @param {object} snapshot - 对端现在那一份快照 (标题从它取)。
   * @param {object|undefined} shadow - 影子工作区。
   * @returns {Promise<boolean>} 换成了一场新投影就 true (宿主没有归档的口子 = false, 那一场原地不动)。
   */
  async function reopenProjection(record, peerSessionId, snapshot, shadow) {
    const registry = ctx.get('workspaceRegistry')
    if (registry === undefined || typeof registry.archiveSession !== 'function') {
      warn(`peer: this host has no session archive, so the ${record.localId} projection keeps its old entries`)
      return false
    }
    // **先开新的, 再归档旧的**: 新的开不出来 (没有注册表/影子被收了) 就一个字节都不动, 下一轮再说
    const fresh = await ensureProxySession(peerSessionId, snapshot, shadow)
    if (fresh === null) return false
    const old = record.localId
    if (old !== '') {
      try {
        await registry.archiveSession(old)
      } catch (error) {
        // 归档失败 (这一场正开着轮) 时旧的那一场会留在分组里, 于是这一场投影变成两行 —— 如实说
        warn(`peer: archiving the stale projection ${old} failed, so it stays next to the new one: ${messageOf(error)}`)
      }
      proxyLocalIds.delete(old)
      // 认账: 老那一场退回之后**仍然是"对端那一场"** —— 主人要是正好开着它打字, 那一句还是要
      // 送给归属方 (见 proxyOf 与 agent/pre-step 那条闸)
      retiredProxies.set(old, peerSessionId)
    }
    record.localId = fresh.localId
    record.lastSeq = 0
    record.openTurn = null
    record.turnCounter = fresh.turnCounter
    record.pendingTexts = []
    // revision 不动: 外面那一圈刚把它记成对端现在的版本, 而这一场新投影吃的就是这一份快照
    logger.info(`peer: rebuilt the projection of ${peerSessionId} for the entry version (${old} archived)`)
    return true
  }

  /**
   * 这一场会话已经用到的最大轮次编号 (没有就是 0)。
   *
   * 只读 `session.log` 上的 `turn/start` / `user/message` 两类的 `data.turn`: 折回的那几轮
   * 必须接在它后面, 否则客户端会把它们归进"已经结束的那一轮"里, 打开着的界面不刷新
   * @param {object} session - Session 句柄。
   * @returns {number} 已经用掉的最大 turn。
   */
  function maxTurnOf(session) {
    let turn = 0
    const log = Array.isArray(session?.log) ? session.log : []
    for (const event of log) {
      if (event === null || typeof event !== 'object') continue
      const value = Number(event?.data?.turn)
      if (Number.isFinite(value) && value > turn) turn = value
    }
    return turn
  }

  /**
   * 拿一个能 append 的会话句柄 (冷会话就唤醒它一次)。
   * @param {string} localId - 会话 id。
   * @returns {Promise<object|null>} Session 或 null。
   */
  async function liveSessionOf(localId) {
    const live = ctx.sessions.get(localId)
    if (live !== undefined) return live
    const controller = ctx.get('sessionController')
    if (controller === undefined || typeof controller.resolveAgent !== 'function') return null
    try {
      const resolvedAgent = await controller.resolveAgent(localId)
      if (resolvedAgent !== undefined && 'error' in resolvedAgent) return null
      return resolvedAgent.agent?.session ?? null
    } catch (error) {
      warn(`peer: waking ${localId} to write its projection failed: ${messageOf(error)}`)
      return null
    }
  }

  /**
   * 刷一趟: 问对端要 info, 把影子工作区与投影会话对齐, 再把有更新的那几场折进来。
   * @returns {Promise<object>} 读数 (面板/工具看得见)。
   */
  async function refreshPeerProxies() {
    if (!shadowsOn || shadowBusy) return { skipped: true }
    shadowBusy = true
    try {
      // 上一次运行留下的游标要先回来: 没有它就会把对端那一场从头再折一遍 (重复落事件)
      await proxyStateReady
      // 只走队列 (peer.mode=queue): 一次 HTTP 都不拨 —— 拨也只会白等 sendTimeoutMs 再失败,
      // 每轮还要写一行 warn. 影子工作区从工作区名片建, 投影按仓库里的 live/ 快照折
      if (peerSpec.mode === 'queue') {
        return await refreshPeerFromRepo()
      }
      const answer = await peerRuntime.peerInfo()
      if (answer.ok !== true) {
        // 2026-10-20: 拨不通也要把**已有的**投影从仓库里那份 live 拷贝折回来 —— 影子与会话表
        // 这一趟不动 (那要一份权威的表), 但"回答回到本机窗口"这件事不依赖拨通
        const folded = await foldFromRepo()
        return { ok: false, error: answer.error, ...folded }
      }
      const info = answer.info ?? {}
      shadowDevice = { id: String(info.device?.id ?? ''), name: String(info.device?.name ?? '') }
      if (shadowDevice.id === '') return { ok: false, error: 'the peer did not report a device id' }
      const registry = ctx.get('workspaceRegistry')
      if (registry === undefined || typeof registry.create !== 'function') {
        return { ok: false, error: 'this profile has no workspace registry, so shadow workspaces cannot be registered' }
      }
      // 上限 (2026-10-14 事故后加的硬闸): 对端报多少就建多少是不行的 —— 影子工作区与投影会话
      // 都是本机替对端造的东西, 没有上界时任何一处回声都会变成失控的写。超出的部分既不建、
      // 也由下面的回收那一步收掉, 只留一句话在日志里
      const reportedWorkspaces = Array.isArray(info.workspaces) ? info.workspaces : []
      if (reportedWorkspaces.length > peerSpec.maxShadows) {
        warn(`peer: the peer reported ${reportedWorkspaces.length} workspaces; only the first ${peerSpec.maxShadows} become shadow groups (peer.maxShadows)`)
      }
      const peerWorkspaces = reportedWorkspaces.slice(0, peerSpec.maxShadows)
      // 同名则加（）: 名字在几台设备上出现过就写 名字（设备名）
      const labels = annotateWorkspaceNames({
        localDeviceName: resolved.deviceName,
        local: ownWorkspaces().map(workspace => ({ key: workspace.id, title: workspace.title })),
        peerDeviceName: shadowDevice.name,
        peer: peerWorkspaces.map((workspace, index) => ({ key: `${shadowDevice.id}|${String(workspace?.path ?? index)}`, title: String(workspace?.name ?? '') })),
      })
      let created = 0
      let retitled = 0
      for (const [index, workspace] of peerWorkspaces.entries()) {
        const name = String(workspace?.name ?? '')
        const label = labels.peer[index]?.label ?? name
        const key = `${shadowDevice.id}|${name}`
        const dir = join(shadowRoot, safeSegment(shadowDevice.id), shadowDirName(name, shadowDevice.id))
        try {
          mkdirSync(dir, { recursive: true })
        } catch (error) {
          warn(`peer: creating the shadow directory ${dir} failed: ${messageOf(error)}`)
          continue
        }
        let entry = shadowRegistry.get(key)
        try {
          if (entry === undefined) {
            const entity = await registry.create(dir, label)
            shadowRegistry.set(key, { workspaceId: String(entity.id), dir, label })
            created += 1
            continue
          }
          entry.dir = dir
          const entity = typeof registry.get === 'function' ? registry.get(entry.workspaceId) : undefined
          if (entity === undefined) {
            const recreated = await registry.create(dir, label)
            entry.workspaceId = String(recreated.id)
            entry.label = label
            created += 1
            continue
          }
          entry.dir = String(entity.path ?? dir)
          if (label !== '' && entity.title !== label) {
            await entity.setTitle(label)
            retitled += 1
          }
          entry.label = label
        } catch (error) {
          warn(`peer: registering the shadow workspace ${dir} failed: ${messageOf(error)}`)
        }
      }
      await persistProxyState()
      // 无主的影子要收回: 对端这一批不再报某个工作区时 (或它超出了 peer.maxShadows), 我们为它建的
      // 那一层不该继续留着 —— 2026-10-14 的两次自我复制事故就是靠这一步收得回来的。
      // 注册行与**空目录**一起收 (目录是我们 mkdir 出来的, 只删空的, 里面有东西就留着并说一句);
      // 投影会话的日志不在这里删 (插件没有删会话日志的口子)
      const wantedKeys = new Set(peerWorkspaces.map(workspace => `${shadowDevice.id}|${String(workspace?.name ?? '')}`))
      const wantedIds = new Set()
      const retiredDirs = []
      for (const [key, entry] of shadowRegistry) {
        if (wantedKeys.has(key)) wantedIds.add(entry.workspaceId)
        else { shadowRegistry.delete(key); if (typeof entry.dir === 'string' && entry.dir !== '') retiredDirs.push(entry.dir) }
      }
      let retired = 0
      if (typeof registry.list === 'function' && typeof registry.delete === 'function') {
        for (const workspace of registry.list()) {
          const id = String(workspace.id ?? '')
          if (id === '' || wantedIds.has(id)) continue
          if (!isShadowWorkspace(workspace)) continue
          try {
            await registry.delete(id)
            retired += 1
            if (typeof workspace.path === 'string' && workspace.path !== '') retiredDirs.push(workspace.path)
          } catch (error) {
            warn(`peer: retiring the stale shadow workspace ${id} failed: ${messageOf(error)}`)
          }
        }
      }
      // 只删空目录: 里面但凡有东西 (对端真拷进来的文件、人放进去的东西) 就原样留着
      let removedDirs = 0
      for (const dir of retiredDirs) {
        if (!isShadowPath(dir)) continue
        try {
          if (readdirSync(dir).length === 0) { rmdirSync(dir); removedDirs += 1 }
        } catch { /* 目录不在了 / 读不动 / 非空: 都不是错 */ }
      }
      if (retired > 0 || removedDirs > 0) logger.info(`peer: retired ${retired} stale shadow workspace(s), removed ${removedDirs} empty shadow director(ies)`)
      await persistProxyState()
      // 投影会话 (上限 peer.maxProxies: 与影子同一条道理 —— 超过的既不建也不追)。
      // 账上多出来的那些只从**追读的账**里摘掉 (投影会话本身与它的日志都留着: 本机没有删会话日志的口子)
      const shadowByWorkspace = new Map()
      for (const [key, value] of shadowRegistry) shadowByWorkspace.set(key.split('|').slice(1).join('|'), value)
      const reportedSessions = Array.isArray(info.sessions) ? info.sessions : []
      // 「从相连时才开始同步」这一条**本机也自己判一遍**: 对端那边还是旧版插件时, 它会把全部
      // 会话报过来 —— 本机按自己那一条线把"相连之前就老掉的"挡掉, 两头各自成立
      const syncEpoch = peerEpoch(nowMs())
      const stalePeerIds = new Set()
      for (const session of reportedSessions) {
        const id = String(session?.id ?? '')
        if (id === '' || session?.running === true) continue
        if (syncEpoch !== 0 && Number(session?.updatedAt ?? 0) < syncEpoch) stalePeerIds.add(id)
      }
      // 对端现在有哪几场: 归属方那条闸与"哪些是本机替对端留的副本"都认这一份 (超上限截掉的那些
      // 也要记进来 —— 它们仍然是对端的会话)
      peerSessionIds.clear()
      const allPeerIds = new Set()
      for (const session of reportedSessions) {
        const id = String(session?.id ?? '')
        if (id === '') continue
        peerSessionIds.add(id)
        allPeerIds.add(id)
      }
      // 对端搬进本机的那批会话文件 (上游的 bring-missing 取回): 它们在对端那边是**它的**会话,
      // 在本机的分组列表里出现就是"同步了全部对话"那一半 —— 隐掉 (归档集是注册表级的, 不删文件,
      // 还能撤销)
      const hidden = await hidePeerCopies(allPeerIds)
      if (reportedSessions.length > peerSpec.maxProxies) {
        warn(`peer: the peer reported ${reportedSessions.length} sessions; only the first ${peerSpec.maxProxies} get a projection (peer.maxProxies)`)
      }
      const sessions = reportedSessions
        .filter(session => !stalePeerIds.has(String(session?.id ?? '')))
        .slice(0, peerSpec.maxProxies)
      // 对端报的条目语义版本: 比账上那一版高 = 那几场投影是旧口径折出来的, 值得破一次"没变就跳过"
      const peerEntryVersion = Number.isFinite(info.entryVersion) ? Number(info.entryVersion) : LIVE_SNAPSHOT_VERSION_LEGACY
      const wantedProxyKeys = new Set(sessions.map(session => `${shadowDevice.id}|${String(session?.id ?? '')}`))
      let droppedProxies = 0
      for (const [key, record] of [...proxyRegistry.entries()]) {
        if (wantedProxyKeys.has(key)) continue
        proxyRegistry.delete(key)
        droppedProxies += 1
        // 对端不再报这一场 (它归档了 / 会话没了), 或者它本来就属于"相连之前的过往": 本机这一场
        // 投影也该从不分组面隐掉 —— 归档不是删, 它还在, 只是不再占一行
        const peerSessionId = key.split('|')[1] ?? ''
        // 2026-10-15 真机修正: 对端**这一轮报了这一场** (只是没进 wantedProxyKeys: 被上限截掉、
        // 或者被那条 stale 线挡住) 时就只摘账, **绝不归档本机这一场** —— 归档就是"从列表里消失",
        // 而 stale 那条线是拿两端的时钟/口径比出来的, 误判一次主人就丢一场
        // (症状: 手机上说完一句, 那场会话消失, 回复也没处落)
        if (peerSessionId !== '' && allPeerIds.has(peerSessionId)) continue
        await archiveLocalSession(String(record?.localId ?? ''), peerSessionId)
      }
      let fetched = 0
      let opened = 0
      for (const session of sessions) {
        const peerSessionId = String(session?.id ?? '')
        if (peerSessionId === '') continue
        const key = `${shadowDevice.id}|${peerSessionId}`
        const shadow = shadowByWorkspace.get(String(session?.workspace ?? '')) ?? [...shadowRegistry.values()][0]
        let record = proxyRegistry.get(key)
        if (record === undefined) {
          record = await ensureProxySession(peerSessionId, session, shadow)
          if (record === null) continue
          proxyRegistry.set(key, record)
          opened += 1
          await persistProxyState()
        }
        const revision = Number.isFinite(session?.updatedAt) ? Number(session.updatedAt) : 0
        const running = session?.running === true
        // 条目语义升级过 (对端会报出 v2 那种带回答的 entries) 的那几场: 即使"这一场没变"也要问一次,
        // 好把投影重做一场 —— 否则这场投影会一直停在"只有用户那几句话"上
        const staleEntries = peerEntryVersion > record.snapshotV
        if (!staleEntries && record.revision === revision && record.running === running) continue
        record.revision = revision
        record.running = running
        if (fetched >= 5) continue
        fetched += 1
        const applied = await applyTranscript(record, peerSessionId, shadow)
        if (applied.rebuilt) opened += 1
      }
      await persistProxyState()
      return { ok: true, device: shadowDevice, shadows: shadowRegistry.size, proxies: proxyRegistry.size, created, retitled, retired, droppedProxies, opened, fetched, hidden }
    } catch (error) {
      return { ok: false, error: messageOf(error) }
    } finally {
      shadowBusy = false
    }
  }

  /** 面板/工具要的那一份对端工作区与投影读数 */
  async function peerProxyView() {
    const shadows = [...shadowRegistry.entries()].map(([key, value]) => ({
      key,
      name: key.split('|').slice(1).join('|'),
      label: value.label,
      dir: value.dir,
      workspaceId: value.workspaceId,
    }))
    const proxies = [...proxyRegistry.entries()].map(([key, value]) => ({
      peerSessionId: key.split('|')[1] ?? '',
      localId: value.localId,
      running: value.running === true,
      applied: value.lastSeq,
    }))
    // newSessions 是**版本判据** (与 G1-2 那个 peer 块同一个做法): 影工作区里新开的一场会转到对端
    // 新开一场执行 (归属方闸的第 ④ 条)。老包的面板里没有这个字段
    return { on: shadowsOn, device: shadowDevice, root: shadowRoot, newSessions: true, shadows, proxies }
  }

  if (shadowsOn) {
    // 排查/测试要看的那两处 (与 doctor 读 mountState 同一条口径: 只读面)
    mountState.peerRefresh = refreshPeerProxies
    mountState.peerView = peerProxyView
    // 轮询: 对端报了什么工作区/会话, 本地就跟着对齐。间隔取 peer.pollSeconds (最小 5 s),
    // 连续失败时退到 60 s —— 影子分组是常驻可见的, 不能只在打开面板时才刷新
    ctx.effect(() => {
      let timer = null
      let delay = Math.max(5, peerSpec.pollSeconds) * 1000
      let stopped = false
      const tick = async () => {
        if (stopped) return
        const result = await refreshPeerProxies()
        if (result?.ok === false) {
          // 失败原因必须打出来。这一段以前是**静默**的 (`ok:false` 只用来把间隔退到 60 s),
          // 于是"通道开着、影子/投影却一台不出现"只能靠猜 —— 2026-10-14 为它查了两轮,
          // 手机侧连一行线索都没有。退避时每 60 s 一条, 不会刷屏
          warn(`peer: refreshing the peer workspaces failed, retrying in 60s: ${result.error ?? 'unknown'}`)
        }
        delay = result?.ok === false ? 60_000 : Math.max(5, peerSpec.pollSeconds) * 1000
        if (!stopped) timer = setTimeout(tick, delay)
      }
      timer = setTimeout(tick, 2_000)
      return () => {
        stopped = true
        if (timer !== null) clearTimeout(timer)
      }
    }, `${PLUGIN_NAME}.peer-proxies`)

    // --- 「指令只能由会话归属方执行」那条闸。
    // 命中投影会话的那一轮: 把这一句转发给对端 (归属方), 本地**一次模型调用都不花**
    // (`{kind:'reject'}` —— 宿主管它叫 blocked, 界面上不画失败行, 见 ui-chat 的 turn-error)。
    // 转发失败时往这一场落一条本地通知 (本插件自己的 source kind), 人看得见, 模型不看
    ctx.effect(() => ctx.on('agent/pre-step', async (payload, next) => {
      const session = payload?.agent?.session
      const localId = String(session?.id ?? '')
      // 归属方判据有四条 (2026-10-14 补全): ① 本机的投影会话; ② 已经退下来的投影 (重建/回收之后
      // 老那一场还在主人手上开着); ③ 对端搬进来的副本 —— 它记的 id 就是**对端那一场**的 id;
      // ④ 影工作区里新开的一场本机会话 (cwd 在影子根底下) —— 见 [peerWorkspaceOfShadowSession]。
      // 主人在这四种里打字, 这一句都该送给归属方执行, 而不是就地跑本机模型
      const found = proxyOf(localId)
      const peerOwned = found !== undefined ? found.peerSessionId : (peerSessionIds.has(localId) ? localId : '')
      const shadowSession = peerOwned === '' ? peerWorkspaceOfShadowSession(session) : undefined
      if (peerOwned === '' && shadowSession === undefined) return next()
      // 只有投影会话才把这一句落进本地 (那是本机自己的一场日志, 写它没有合并后果);
      // 对端的副本一个字节都不能写 —— 两边都写同一份日志会被判 APPEND_BOTH, 每发一句分叉一次。
      // ④ 那一档也是本机自己的一场 (只是 cwd 在影子里), 写它同样没有合并后果
      const localCopy = found !== undefined && found.retired !== true
      const texts = Array.isArray(payload?.messages)
        ? payload.messages.flatMap(message => (Array.isArray(message?.content)
          ? message.content.filter(block => block?.type === 'text').map(block => String(block.text ?? ''))
          : []))
        : []
      const text = texts.join('\n').trim()
      if (text !== '') {
        if (localCopy) {
          // 先把这一句追进投影会话 (那一轮的开合由宿主自己的 turn/start / turn/end 管着)
          try {
            appendProxyEvents(session, [proxyUserMessage(text, Number(payload?.turn ?? 0))])
            if (found.value !== undefined) found.value.pendingTexts = [...found.value.pendingTexts, text].slice(-5)
          } catch (error) {
            warn(`peer: writing the outgoing line into ${localId} failed: ${messageOf(error)}`)
          }
        } else if (shadowSession !== undefined) {
          // ④: 这一场是本机自己的一场 (只是 cwd 在影子里), 先把它落进自己的日志 ——
          // 送不出去时下面就跟着落一条通知, 人看得见"我说了什么"与"为什么没送到"
          try {
            appendProxyEvents(session, [proxyUserMessage(text, Number(payload?.turn ?? 0))])
          } catch (error) {
            warn(`peer: writing the outgoing line into ${localId} failed: ${messageOf(error)}`)
          }
        }
        // ④ 的目标没有对端会话 id 可指: 让归属方**在它那侧新开一场** (kind:'new'), 回执里带回来的
        // id 随后把本机这一场登记成它的投影 —— 于是对端的回答折回来就落在主人正看着的这一场里
        //
        // **name 必须带出去** (2026-10-21): 影子账在"退役/重建之间"那一档给的是空名字
        // (`{name:'', deviceId}`), 从前就这么发出去了, 对端解析不到工作区, 只回一条
        // "could not resolve the target to a session on this host" —— 队列那条路对"新开一场"完全不通.
        // 空的时候退回本场 cwd 的最后一段: 那是影子目录名, 与对端工作区名同形
        const deliveryName = peerOwned !== ''
          ? ''
          : typeof shadowSession?.name === 'string' && shadowSession.name !== ''
            ? shadowSession.name
            // 影子会话: cwd 是影子目录, basename 是 safeSegment 过的名字 (`Mobile-AI-dsh`);
            // 先问注册表要原始标题 (`Mobile-AI\dsh`), 问不到才退回 basename
            : workspaceNameForCwd(session?.header?.cwd) || basename(String(session?.header?.cwd ?? ''))
        const answer = await peerRuntime.send({
          text,
          target: peerOwned !== ''
            ? { kind: 'session', id: peerOwned }
            : { kind: 'new', id: '', name: deliveryName },
        })
        if (answer.ok === true) {
          const via = String(answer.via ?? '')
          logger.info(`peer: line forwarded to ${shadowDevice.name || 'the peer'} (${via || 'unknown'})`)
          const opened = typeof answer.sessionId === 'string' ? answer.sessionId : ''
          if (shadowSession !== undefined && opened !== '') {
            // 登记成投影: revision -1 逼下一趟刷新去取一次快照; snapshotV 0 = 只记版本, 不重做一场;
            // pendingTexts 让对端回声回来这一句时被认掉一次 (不重复落)
            proxyRegistry.set(`${shadowDevice.id}|${opened}`, {
              localId,
              lastSeq: 0,
              openTurn: null,
              // 轮号从宿主自己的 seq 起算: 我们伪造的轮号就不会与真实轮次撞号
              turnCounter: Number.isFinite(session?.seq) ? Number(session.seq) : 0,
              pendingTexts: [text],
              revision: -1,
              running: false,
              snapshotV: 0,
            })
            proxyLocalIds.add(localId)
            logger.info(`peer: the shadow session ${localId} now projects ${opened} on ${shadowDevice.name || 'the peer'}`)
          } else if ((shadowSession !== undefined || localCopy) && via === 'queue') {
            // 排进仓库队列 = **发出去了**, 只是要等对端下一次拉取 (手机是拉模式, 这一档是常态)。
            // 这里没有对端会话 id 可绑, 所以先不建投影; 回执回来时 bindQueuedReplies 再把它绑上 ——
            // 那时才能把对端的回答折回主人正看着的这一场
            rememberQueuedLine({
              id: typeof answer.messageId === 'string' ? answer.messageId : '',
              localId,
              text,
              // 投影账的键要用对端设备 id: 影子注册表为空时 shadowDevice.id 也是空, 于是退回这次发送
              // 实际用的那一个 (peerBase 从名片里学到的)
              deviceId: shadowSession?.deviceId || shadowDevice.id || (typeof answer.deviceId === 'string' ? answer.deviceId : ''),
              at: Date.now(),
            })
            void persistProxyState()
            const device = shadowDevice.name || 'the peer'
            const pushed = answer.pushed !== false
            try {
              appendProxyEvents(session, proxyNoticeEvents('', device, {
                id: `peer-queued-${Number(payload?.turn ?? 0)}`,
                // 一行本地提示 (2026-10-22 主人要的): 发出去到回答折回来之间通常有一分钟,
                // 界面上得有一句话说明"在等什么", 否则只剩一行冷冰冰的「已完成」
                text: pushed
                  ? `已送到 ${device}，正在等它回答 —— 通常一分钟内自动折回这一场，不用退出重进`
                  : `这一句只写进了本机的同步工作树, 推送失败 (${String(answer.pushedError ?? 'unknown')}), ${device} 拉不到它`,
              }))
            } catch { /* 通知落不下就算了 */ }
          } else if (shadowSession !== undefined) {
            // 对端说 ok 却没带会话 id: 同样是"这一句没有落到任何地方", 也要让人看得见
            const why = 'the peer answered ok without a session id, so the line has nowhere to land'
            logger.warn(`peer: ${why}`)
            try {
              appendProxyEvents(session, proxyNoticeEvents(why, shadowDevice.name || 'the peer'))
            } catch { /* 通知落不下就算了: 本地那一轮已经拦住了 */ }
          }
        } else {
          logger.warn(`peer: the line could not go out: ${answer.error}`)
          if (localCopy || shadowSession !== undefined) {
            try {
              appendProxyEvents(session, proxyNoticeEvents(String(answer.error ?? 'unknown'), shadowDevice.name || 'the peer'))
            } catch { /* 通知落不下就算了: 本地那一轮已经拦住了 */ }
          }
        }
        void persistProxyState()
      }
      // `reject` 是"这一轮不花模型调用"的唯一可靠写法: `enter` + `messages: []` 会被
      // 上下文那几家 (time-context / runtime-context) 在 next() 之后再添回来 ——
      // 它们包住 next() 并往前补自己的消息, 于是本地模型照跑 (2026-10-20 真机上量到的)
      return { kind: 'reject' }
    }))
  }

  /** fork 文件产生回调：尝试把冲突会话在会话层面 fork（安全时）。 */
  function handleForks(forkPaths) {
    const bySession = new Map()
    let unmapped = 0
    for (const forkPath of forkPaths) {
      const sessionId = sessionIdOfForkPath(forkPath)
      if (sessionId === undefined) {
        unmapped += 1
        continue
      }
      const list = bySession.get(sessionId) ?? []
      list.push(forkPath)
      bySession.set(sessionId, list)
    }
    if (unmapped > 0) {
      logger.warn(`sync: ${unmapped} fork path(s) outside the project/session layout were not mapped to a session-level fork (files preserved)`)
    }
    for (const [sessionId, paths] of bySession) {
      const session = ctx.sessions.get(sessionId)
      if (session === undefined) continue
      maybeAppendSessionEvent(session, SESSION_EVENTS.CONFLICT, {
        sessionId: session.id,
        forkPaths: paths,
        diverged: paths.length,
      }, eventGate, warn)
      const openTurn = openTurnFromProjection(ctx, session)
      if (openTurn !== false) {
        // true=轮次进行中；undefined=投影缺席。两者都跳过会话级 fork：拿不到
        // 权威轮次状态时绝不在可能开放的轮次里 fork，fork 文件仍全部保留。
        logger.info(`sync conflict on live session ${sessionId}: ${openTurn === true ? 'turn open' : 'turn state unavailable (turnBoundary projection absent)'}, session-level fork skipped (files preserved as forks)`)
        continue
      }
      try {
        const child = ctx.sessions.fork(session)
        injectForkNotice(child, paths, session.id)
        maybeAppendSessionEvent(session, SESSION_EVENTS.CONFLICT, {
          sessionId: session.id,
          forkPaths: paths,
          childSessionId: child.id,
        }, eventGate, warn)
        logger.info(`sync conflict on session ${sessionId}: forked → ${child.id} (both lineages kept)`)
      } catch (error) {
        logger.warn(`sync conflict session fork failed for ${sessionId}: ${messageOf(error)} (fork files preserved)`)
      }
    }
  }

  // Service Provider — /sync 命令注册进宿主 commands 服务；其 handler 是消费面（Consumer）。
  if (resolved.registerCommand) {
    ctx.commands.register({
      name: COMMAND_NAME,
      // Luwi 改 (G1-C): 多一个 ws 目标 (工作区镜面)
      // Luwi 加 (G1-D): 多一个 peers 动作 (互为远端的名片)
      description: 'Cross-device sync: status, pull/push the git mirror with keep-both conflict resolution, or diff against the last-fetched remote. A leading "ws" acts on the workspace mirror instead of the session store; "peers" lists the device cards seen in the mirror; "peer" is the cross-device channel (state, live, send).',
      input: { hint: '[status | pull | push | diff | log | peers | help] [ws …]' },
      async handler(invocation) {
        return handleSyncCommand(invocation)
      },
    })
  }

  /**
   * /sync 命令处理器。
   *
   * Luwi 加 (G1-C): 第一个词可以是 `ws` —— `/sync ws <status|pull|push|diff|log>` 打工作区
   * 那根, 不带就是会话那根 (缺省行为与上游逐字一致)。
   * Luwi 加 (G1-D): 多一个 `peers` 动作 —— 列出本地镜像里见过的对端名片 (含自己的)。
   * @param {import('@deepseek-ai/dsh-commands').CommandInvocation} invocation - 命令调用。
   * @returns {Promise<import('@deepseek-ai/dsh-commands').CommandResult>} 命令结果。
   */
  async function handleSyncCommand(invocation) {
    const { agent, rawInput, signal } = invocation
    const session = agent?.session
    const parts = rawInput.trim().split(/\s+/u).filter(part => part !== '')
    const first = parts[0]?.toLowerCase() ?? ''
    const target = first === 'ws' || first === 'workspace' ? TARGETS.WORKSPACE : TARGETS.SESSIONS
    const action = (target === TARGETS.WORKSPACE ? parts[1] : parts[0])?.toLowerCase() || 'status'
    const picked = makeTargetResolver({ sessions: engineFacade, workspace: engineFacade })(target)
    try {
      if (action === 'help' || (action !== 'status' && action !== 'pull' && action !== 'push' && action !== 'diff' && action !== 'log' && action !== 'peers' && action !== 'doctor' && action !== 'peer')) {
        return {
          kind: 'success',
          text: [
            'sync: usage — /sync [status | pull | push | diff | log | peers | peer | doctor | help] · /sync ws <same actions>',
            '  status  show mirror state (default): branch, remote, ahead/behind, dirty, forks',
            '  pull    fetch + merge remote changes (keep-both; confirms first)',
            '  push    mirror + commit + push local changes (confirms first)',
            '  diff    uncommitted changes + HEAD..remote stat (read-only)',
            '  log     last commits in the sync repository',
            '  peers   device cards seen in the mirror (names, URLs, device ids)',
            '  peer    the cross-device channel: /sync peer [state] · /sync peer live · /sync peer send <text> [session <id> | workspace <name>]',
            '  doctor  self-check: where each dsh peer resolves from, profile extraneous packages, git spawn',
            '  ws …    the same actions against the workspace mirror (its own repository, exclude list',
            '          and file size cap; a pull updates or trashes the local files the merge changed)',
          ].join('\n'),
        }
      }
      if (!picked.ok) return { kind: 'error', text: `sync: ${picked.error}` }
      const { engine: active, label, scope } = picked
      // Luwi 加 (G1-D): 名片住在会话仓库里, 工作区那根没有这一说
      if (action === 'peers') {
        if (scope === TARGETS.WORKSPACE) {
          return { kind: 'error', text: 'sync: the workspace mirror has no peer cards (they live in the session repository)' }
        }
        const { cards, broken } = await readPeerCards(repoDir)
        const seen = cards.map(card => ({ ...card, self: card.deviceId !== '' && card.deviceId === peerIdentity.deviceId }))
        if (peerIdentity.deviceId !== '' && !seen.some(card => card.self === true)) {
          // 自己的卡片还没坐 git 过来 (没推过 / 远端还没合回来) 也照列 —— 配置说了它是谁
          seen.push({ deviceId: peerIdentity.deviceId, name: resolved.deviceName, url: resolved.publicUrl, updatedAt: '', self: true })
        }
        return { kind: 'success', text: renderPeers(seen, broken) }
      }
      // Luwi 加 (G1 收口 C9): 只读自检 —— ws 那边没有第二份(profile 只有一份), 所以
      // `/sync ws doctor` 与 `/sync doctor` 同一件事
      if (action === 'doctor') {
        const report = await collectDoctor()
        return { kind: report.status === 'PASS' ? 'success' : 'error', text: formatDoctor(report) }
      }
      // Luwi 加 (G1-2 对端): /sync peer [state|live|send <文本>]
      if (action === 'peer') {
        if (!peerRuntime.enabled) {
          return { kind: 'error', text: 'sync: the peer channel is off — set peer.token (and optionally peer.url) in $DSH_HOME/lw/session-sync.json, then restart the host' }
        }
        const rest = target === TARGETS.WORKSPACE ? parts.slice(2) : parts.slice(1)
        const what = (rest[0] ?? 'state').toLowerCase()
        if (what === 'state' || what === 'status') {
          return { kind: 'success', text: renderPeerState(await peerRuntime.status()) }
        }
        if (what === 'live') {
          const live = await peerRuntime.peerLive()
          return { kind: live.ok === true ? 'success' : 'error', text: live.ok === true ? renderPeerLive(live) : `sync: peer live failed: ${live.error}` }
        }
        if (what === 'send') {
          const text = rest.slice(1).join(' ').trim()
          if (text === '') return { kind: 'error', text: 'sync: /sync peer send needs the text to send' }
          const answer = await peerRuntime.send({ text, target: { kind: 'session', id: '', name: '' } })
          return {
            kind: answer.ok === true ? 'success' : 'error',
            text: answer.ok === true
              ? `sync: sent to the peer ${answer.via === 'http' ? 'over http' : 'through the queue (the peer was unreachable)'}${answer.sessionId === undefined || answer.sessionId === '' ? '' : ` (session ${answer.sessionId})`}`
              : `sync: could not send to the peer: ${answer.error}`,
          }
        }
        return { kind: 'error', text: `sync: unknown peer action ${JSON.stringify(rest[0] ?? '')} (expected state|live|send)` }
      }
      if (action === 'status') {
        const status = await active.status()
        let meta = {}
        try {
          meta = await readMetaFor(scope)()
        } catch {
          // 元数据只读失败不阻断状态展示。
        }
        // Luwi 加 (G1-D): 会话那根多一行对端计数 (工作区那根没有名片, 不查)
        let peerCount = 0
        if (scope === TARGETS.SESSIONS) {
          try {
            peerCount = (await readPeerCards(repoDir)).cards.length
          } catch {
            peerCount = 0
          }
        }
        return { kind: status.ok ? 'success' : 'error', text: renderStatus({ ...status, lastPullAt: meta.lastPullAt, lastPushAt: meta.lastPushAt, peerCount }, label) }
      }
      if (action === 'diff') {
        const status = await active.status()
        return { kind: status.ok ? 'success' : 'error', text: renderDiff(status, label) }
      }
      if (action === 'log') {
        const status = await active.status()
        const lines = status.lastCommits.length > 0 ? status.lastCommits : ['(no commits yet)']
        const where = repoDir
        return { kind: 'success', text: `sync: last commits in ${where}:\n${lines.map(line => `  ${line}`).join('\n')}` }
      }
      // pull / push：覆盖性操作必须经确认门（无回答者失败关闭）。
      const summary = action === 'push'
        ? 'Sessions and the workspace files will be mirrored, committed, and pushed to the sync remote in one commit (never force-pushed).'
        : 'Remote changes will be merged into the local mirror with keep-both semantics (local kept, remote preserved as fork files).'
      const verdict = await confirmSync({ ctx, confirmVia: resolved.confirmVia, action, summary }, agent, signal)
      if (!verdict.allowed) {
        const reason = verdict.reason ?? 'no confirmation answerer (fail closed)'
        logger.info(`sync ${action} denied (${verdict.channel}: ${reason})`)
        return { kind: 'error', text: `sync: cancelled: ${reason}` }
      }
      if (action === 'push') {
        const result = await active.push({ signal })
        if (session !== null && session !== undefined) {
          maybeAppendSessionEvent(session, SESSION_EVENTS.PUSH, {
            sessionId: session.id,
            ok: result.ok,
            error: result.error,
            pushed: result.pushed,
            mirrored: result.mirrored,
            deleted: result.deleted,
            head: result.head,
          }, eventGate, warn)
        }
        return { kind: result.ok ? 'success' : 'error', text: renderPush(result, label) }
      }
      const result = await active.pull({ signal })
      if (session !== null && session !== undefined) {
        maybeAppendSessionEvent(session, SESSION_EVENTS.PULL, {
          sessionId: session.id,
          ok: result.ok,
          error: result.error,
          pulled: result.pulled,
          merged: result.merged,
          adopted: result.adopted,
          appended: result.appended,
          diverged: result.diverged,
          forks: result.forks,
          head: result.head,
        }, eventGate, warn)
      }
      return { kind: result.ok ? 'success' : 'error', text: renderPull(result, label) }
    } catch (error) {
      const message = `sync: ${action || 'status'} failed: ${messageOf(error)}`
      logger.error(message)
      return { kind: 'error', text: message }
    }
  }

  /**
   * Luwi 加 (G1 收口 C9): 只读自检的探测面 —— peer 解析落点 / ② 层 extraneous / git 能不能跑。
   *
   * 不触网、不写盘。这一次 profile 污染事故就是它该第一眼看见的东西: 判据从来不是
   * "能不能 import", 而是"解析落点是运行安装还是 ② 层"与"② 层有没有 pnpm 不认账的包"
   * @returns {Promise<object>} 报告对象（渲染见 lib/luwi-doctor.mjs 的 formatDoctor）。
   */
  async function collectDoctor() {
    const selfDir = fileURLToPath(new URL('.', import.meta.url))
    const profileModulesDir = resolve(selfDir, '..')
    return runDoctor({
      selfDir,
      profileModulesDir,
      readText: path => readFile(path, 'utf8'),
      listPresent: async (dir) => {
        /** @type {string[]} */
        const names = []
        for (const entry of await readdir(dir, { withFileTypes: true })) {
          if (!entry.isDirectory() || entry.name.startsWith('.')) continue
          if (entry.name.startsWith('@')) {
            for (const inner of await readdir(join(dir, entry.name), { withFileTypes: true })) {
              if (inner.isDirectory()) names.push(`${entry.name}/${inner.name}`)
            }
            continue
          }
          names.push(entry.name)
        }
        return names
      },
      resolvePeer: name => (typeof import.meta.resolve === 'function' ? fileURLToPath(import.meta.resolve(name)) : undefined),
      gitVersion: async () => {
        // cwd 必须给: 宿主的 subprocess 规范校验会拿 spec.cwd 去做 `value.includes('\0')`,
        // 而 `git --version` 这条探针原先不传 runOpts ⇒ cwd undefined ⇒ 宿主抛
        // `Cannot read properties of undefined (reading 'includes')`, doctor 于是报"git 跑不动"
        // (2026-10-14 实测; 引擎自己的每条 git 都经 GitBackend 带了 cwd, 所以只有这一条中招)
        const out = await runGit(['--version'], { cwd: selfDir })
        const text = String(out.stdout).trim() || String(out.stderr).trim() || `exit ${out.code}`
        return { ok: out.code === 0, text }
      },
      mount: {
        mounted: mountState.mounted,
        runner: mountState.runner,
        version: PLUGIN_VERSION,
        disabledReason: mountState.disabledReason,
      },
      // Luwi 加 (P2P T4): 读数走调度器 (path / rtt / 计数 / cooling / lastError / gc)
      p2p: await p2pService.status(),
    })
  }

  // --- Luwi 加 (G1 面板): 官方侧边栏那块「同步」操作面板的宿主半。
  // 面板只是**增强项**: 它读的是上面这几个已有读数, 写的是两个已有动作, 不新增任何同步语义。
  // webServer 是**可选**服务, 用 ctx.inject 起子 fiber —— 顶层 inject 写了就等于"没有 HTTP
  // 服务的 profile 上一整行停在 pending" (与 tools 那一处同型, 见 §4.4)。路由注册随这个
  // 子 fiber 撤销; 没有 webServer 的宿主上这一整块安静地不发生, 主行照跑。
  // 面板写配置的那一半 —— 写的是 <DSH_HOME>/lw/session-sync.json, 也就是**手机设置页读写的
  // 同一份** (它的位置在 applyInner 开头就算好了: syncConfigFile)。写完必须重启 host:
  // 插件是挂载时读配置的 (周期拉取那一个数除外, 它是热更新的)

  /**
   * 把面板发来的那几个键写进配置文件 (其余键一个字节都不碰, 语义见 lib/luwi-configure.mjs)。
   *
   * Luwi 加 (2026-10-20): 保存仓库 / 显式"重新配对" / 原先没有配对时, 顺手生成一个新的
   * `pairing.id` —— 它决定"这是不是一次新的同步开始"(手机侧扫码时拿到的是同一个 id)
   * @param {object} request - 面板的 POST 体。
   * @returns {Promise<{ok: boolean, changed: string[], error?: string}>} 结果。
   */
  async function configureFromPanel(request) {
    const patch = {}
    for (const key of ['remote', 'remoteToken', 'branch', 'publicUrl', 'autoPullOnStart', 'autoPushOnTurnEnd', 'pullIntervalMinutes', 'on', 'workspace', 'sessions']) {
      if (request[key] !== undefined) patch[key] = request[key]
    }
    const current = await readSyncTree(syncConfigFile)
    const currentRemote = typeof current.remote === 'string' ? current.remote.trim() : ''
    const nextRemote = typeof patch.remote === 'string' ? patch.remote.trim() : currentRemote
    const hasPairing = current.pairing !== null && typeof current.pairing === 'object' && typeof current.pairing.id === 'string'
    // 令牌单独一格: 只给令牌 (地址没变) 时也该生成新的配对 id —— 二维码里的地址会因此变
    const nextToken = typeof patch.remoteToken === 'string' ? patch.remoteToken.trim() : (typeof current.remoteToken === 'string' ? current.remoteToken : '')
    if (nextRemote !== '' && (request.pairing === true || request.repair === true || nextRemote !== currentRemote || nextToken !== (typeof current.remoteToken === 'string' ? current.remoteToken : '') || !hasPairing)) {
      patch.pairing = { v: 1, id: randomUUID(), at: Date.now(), from: resolved.deviceName }
    }
    // 2026-10-20: 保存仓库 (pairing) 这一下就把 PC 这一侧也设成"该自动的自动" —— 与手机扫码
    // 写进去的那一组同值, 否则 PC 只会在手动点推送时才动
    if (request.pairing === true || request.repair === true) {
      if (patch.autoPullOnStart === undefined) patch.autoPullOnStart = true
      if (patch.autoPushOnTurnEnd === undefined) patch.autoPushOnTurnEnd = true
    }
    const result = await applyConfigure(syncConfigFile, patch)
    if (result.ok === true) logger.info(`panel configure wrote ${result.changed.join(', ')} (restart the host to take effect)`)
    else warn(`panel configure refused: ${result.error}`)
    return result
  }

  /**
   * 手机配对码: 一份校验过的载荷 + 画好的 SVG (载荷里有仓库口令, 所以**不进快照**, 只在
   * 这一条应答里出现; 要文本得显式点"复制配对文本")。
   * @returns {Promise<{ok: boolean, payload?: string, svg?: string, summary?: object, error?: string}>} 结果。
   */
  async function buildPairing() {
    // Luwi 加 (2026-10-23): 生成配对码的前提是"登录过" —— 过期不收这个闸
    // (过期的人要能重新配对去续费; 收费的闸在 P2P 那一边, 见 licensePlanGate)
    const licenseStatus = await readLicenseStatus()
    if (loginRequiredForPairing(licenseStatus)) {
      return { ok: false, error: 'sign in with a Luwi account first: the pairing card on this panel has the sign-in button' }
    }
    const tree = await readSyncTree(syncConfigFile)
    const cleanRemote = typeof tree.remote === 'string' ? tree.remote.trim() : resolved.remote.trim()
    if (cleanRemote === '') {
      return { ok: false, error: 'save the repository address first, then generate the pairing code' }
    }
    // 2026-10-20: 令牌单独一格 —— 二维码里带的是**拼好凭据的地址** (手机侧 git 没有 credential helper)
    const token = typeof tree.remoteToken === 'string' ? tree.remoteToken.trim() : remoteToken
    const remote = composeRemoteWithToken(cleanRemote, token)
    const pairingId = tree.pairing !== null && typeof tree.pairing === 'object' && typeof tree.pairing.id === 'string'
      ? tree.pairing.id
      : ''
    if (pairingId === '') {
      return { ok: false, error: 'this repository has no pairing id yet: save it once (the save writes one), restart the host, then generate the code' }
    }
    const payload = pairingPayloadFromConfig({
      id: pairingId,
      remote,
      branch: typeof tree.branch === 'string' ? tree.branch : resolved.branch,
      // 面板那个框写的是文件层; 显式 cordis 键 (profile 覆盖行) 会压住它, 所以这里**先看文件** ——
      // 否则面板让人填了地址却怎么都不生效 (与 G1 收口时 remote 那一条是同一个坑)
      peerUrl: typeof tree.publicUrl === 'string' && tree.publicUrl.trim() !== '' ? tree.publicUrl.trim() : resolved.publicUrl,
      peerToken: peerSpec?.token ?? '',
      from: resolved.deviceName,
      at: Date.now(),
    })
    const text = encodePairing(payload)
    return { ok: true, payload: text, svg: qrSvg(text), summary: pairingSummary(payload) }
  }

  /**
   * 清理仓库里的会话 (2026-10-20 主人要的"清理仓库会话")。
   *
   * 删掉 `sessions/` 下①窗口之外的那些镜像文件、②所有 `*.remote-fork-*` 合并副本
   * (根目录那几个 `device.txt.remote-fork-*` 一起清), 然后推一次 —— 推的时候镜像会把**窗口内**
   * 的会话按需补回来, 窗口外的本来就由基线挡着不再上传, 所以它们不会又冒出来。
   * 只动**仓库工作树**, 本机会话存储一个字节都不碰。
   * @returns {Promise<object>} 读数 {ok, removed, forks, stale, pushed, head, error?}
   */
  async function pruneRepoSessions() {
    const windowMs = readCurrentWindowMinutes(merged.sessions) * 60_000
    const cutoff = Date.now() - windowMs
    const mirrorDir = join(repoDir, MIRROR_DIR)
    const counts = { forks: 0, stale: 0 }
    const dropFile = async (absolute) => {
      await rm(absolute, { force: true })
    }
    const walk = async (dir) => {
      let entries
      try {
        entries = await readdir(dir, { withFileTypes: true })
      } catch {
        return
      }
      for (const entry of entries) {
        const absolute = join(dir, entry.name)
        if (entry.isDirectory()) {
          await walk(absolute)
          continue
        }
        if (!entry.isFile()) continue
        if (entry.name.includes('.remote-fork-')) {
          await dropFile(absolute)
          counts.forks += 1
          continue
        }
        // 源文件还在窗口里 → 留着; 源没了或已经冷下来 → 从镜像里删掉 (本机那份不动)
        let mtime = 0
        try {
          mtime = (await stat(join(sessionRoot, relative(mirrorDir, absolute)))).mtimeMs
        } catch {
          mtime = 0
        }
        if (mtime === 0 || mtime < cutoff) {
          await dropFile(absolute)
          counts.stale += 1
        }
      }
    }
    await walk(mirrorDir)
    // 根目录那几个 device.txt 的合并副本 (keep-both 留下的), 一并清掉
    try {
      for (const entry of await readdir(repoDir, { withFileTypes: true })) {
        if (entry.isFile() && entry.name.includes('.remote-fork-')) {
          await dropFile(join(repoDir, entry.name))
          counts.forks += 1
        }
      }
    } catch {
      // 根目录读不了就只报 sessions 那一趟的结果
    }
    const total = counts.forks + counts.stale
    if (total === 0) return { ok: true, removed: 0, forks: 0, stale: 0, pushed: false }
    const pushed = await pushSync()
    return {
      ok: pushed.ok === true,
      removed: total,
      forks: counts.forks,
      stale: counts.stale,
      pushed: pushed.pushed === true,
      ...(pushed.head === undefined ? {} : { head: pushed.head }),
      ...(pushed.error === undefined ? {} : { error: pushed.error }),
    }
  }

  /** 配对摘要 (快照里那一块; 不带载荷、不带口令) */  async function readPairing() {
    const tree = await readSyncTree(syncConfigFile)
    const section = tree.pairing !== null && typeof tree.pairing === 'object' ? tree.pairing : null
    const paired = section !== null && typeof section.id === 'string' && section.id !== ''
    return {
      paired,
      from: paired && typeof section.from === 'string' ? section.from : '',
      at: paired && Number.isFinite(section.at) ? section.at : undefined,
      repoHost: remoteHostOf(typeof tree.remote === 'string' ? tree.remote : resolved.remote),
      legacy: !paired,
    }
  }

  const panel = createPanel({
    // Luwi 改 (2026-10-20): 一个仓库一根镜像 (会话 + 工作区同一次 commit); 老客户端的
    // target=workspace 会落到这一根上 (panel-routes 里折过来)
    mirrors: [
      {
        target: PANEL_TARGETS.SESSIONS,
        label: '会话与工作区',
        engine: engineFacade,
        repoDir,
        remote: resolved.remote,
        mirrorDir: MIRROR_DIR,
      },
    ],
    readMetaFor,
    readPeers: () => readPeerCards(repoDir),
    // Luwi 加 (G1-2): 快照多一块 peer (通道开关 / 对端地址与可达性 / 队列与计数 / 令牌只出打码),
    // 外加影子工作区与投影会话那一份读数 —— 面板那张卡的「影子工作区」那一行读的就是它
    readPeer: async () => ({ ...(await peerRuntime.status()), workspace: await peerProxyView() }),
    // Luwi 加 (P2P T6): P2P 传输读数 (三档文案与信任/SAS 在面板那张卡里)
    readP2p: () => p2pService.status(),
    // Luwi 加 (P2P T6): 信任表动作 (确认换机 / 吊销) —— 面板卡上那两颗按钮走这里
    p2pConfirm: p2pConfirmPeer,
    p2pRevoke: p2pRevokePeer,
    // Luwi 加 (2026-10-20): 配对摘要与"生成配对码"那一条口
    readPairing,
    buildPairing,
    // Luwi 加 (2026-10-23): 账号登录 (生成配对码的前提) 与权益刷新/退出
    readLicense: () => readLicenseStatus(),
    loginStart: licenseLoginStart,
    loginPoll: licenseLoginPoll,
    logout: licenseLogout,
    refreshLicense: licenseRefresh,
    checkin: licenseCheckin,
    // Luwi 加 (2026-10-15): 面板的写配置口 (宿主半见 lib/panel-routes.mjs 的 CONFIGURE)
    configure: configureFromPanel,
    // Luwi 加 (2026-10-20): 清理仓库会话 (面板那颗按钮 / 手机的同一块面板都走这一口)
    pruneSessions: pruneRepoSessions,
    collectDoctor,
    info: {
      plugin: PLUGIN_NAME,
      version: PLUGIN_VERSION,
      runner,
      mounted: mountState.mounted,
      disabledReason: mountState.disabledReason,
      // 面板看到的配置面: remote 只给 set|unset (值里可能有 token, 绝不外发)
      config: {
        repoDir,
        branch: resolved.branch,
        deviceName: resolved.deviceName,
        backend: resolved.backend,
        confirmVia: resolved.confirmVia,
        autoPullOnStart: resolved.autoPullOnStart,
        autoPushOnTurnEnd: resolved.autoPushOnTurnEnd,
        pullIntervalMinutes: resolved.pullIntervalMinutes,
        remoteState: describeRemote(resolved.remote).state,
        // 2026-10-20: 令牌单独一格 —— 只报"有没有", 绝不回显
        hasRemoteToken: remoteToken !== '',
        panelSeats: resolved.panelSeats,
        panelOps: resolved.panelOps === true,
        // 2026-10-20: 新的工作区/基线/配对读数 (面板与自检都看这几项)
        workspace: {
          enabled: workspaceSources.length > 0,
          root: workspaceSources[0]?.root ?? '',
          device: workspaceSources[0]?.device ?? '',
          name: workspaceSources[0]?.title ?? '',
          mirrorDir: workspaceSources[0]?.mirrorDir ?? '',
          maxFiles: workspaceSources[0]?.budget?.maxFiles ?? null,
          maxBytes: workspaceSources[0]?.budget?.maxBytes ?? null,
        },
        sessions: { currentWindowMinutes },
        pairing: { paired: pairing !== null, id: pairing === null ? '' : pairing.id },
      },
    },
    logger,
  })
  if (resolved.panelSeats !== 'off') {
    ctx.inject(['webServer'], (webCtx) => {
      if (!webCtx.webServer || typeof webCtx.webServer.register !== 'function') return
      webCtx.effect(() => {
        const offs = []
        try {
          for (const route of panel.routes()) offs.push(webCtx.webServer.register(route))
        } catch (error) {
          // 路由撞车 / 形态不符: 摘掉已经装上的, 面板退回"通道没开"那一屏, 绝不抛
          warn(`panel routes not registered: ${messageOf(error)}`)
          for (const off of offs) { try { off() } catch { /* 已经摘掉 */ } }
          return undefined
        }
        logger.info(`panel channel on: ${PANEL_PREFIX}/panel (seats=${resolved.panelSeats}, ops=${resolved.panelOps === true ? 'rw' : 'ro'})`)
        return () => {
          panel.dispose()
          for (const off of offs) { try { off() } catch { /* 已经摘掉 */ } }
        }
      }, `${PLUGIN_NAME}.panel-routes`)
    })
  }

  // --- Luwi 加 (G1-2 对端): 对端那四条路由 (peer/info · peer/transcript · peer/send · send)。
  // 与面板同一个 webServer 面, 但**不受 panelSeats 管**: 通道是同步语义, 面板只是人的界面;
  // 令牌空 (spec 为 null) 时 peerRuntime.routes() 回空表, 一条都不注册
  if (peerRuntime.enabled) {
    ctx.inject(['webServer'], (webCtx) => {
      if (!webCtx.webServer || typeof webCtx.webServer.register !== 'function') return
      webCtx.effect(() => {
        const offs = []
        try {
          for (const route of peerRuntime.routes()) offs.push(webCtx.webServer.register(route))
        } catch (error) {
          warn(`peer routes not registered: ${messageOf(error)}`)
          for (const off of offs) { try { off() } catch { /* 已经摘掉 */ } }
          return undefined
        }
        logger.info(`peer channel on: ${PANEL_PREFIX}/peer/* (listen=${peerSpec.listen}, proxy=${peerSpec.proxy}, url=${peerSpec.url === '' ? 'from the card' : 'configured'})`)
        return () => {
          for (const off of offs) { try { off() } catch { /* 已经摘掉 */ } }
        }
      }, `${PLUGIN_NAME}.peer-routes`)
    })
  }

  // --- sync_* 模型工具（Consumer；tools 服务存在时才注册，随 fiber 卸载撤销）。
  // Luwi 加 (G1-C): 多一个 target 参数 (sessions | workspace), 缺省仍是会话那根。
  const resolveTarget = makeTargetResolver({ sessions: engineFacade, workspace: engineFacade })
  if (resolved.registerTools) {
    ctx.inject(['tools'], (toolCtx) => {
      // Luwi 加 (G1-D): sync_status 带上镜像里的名片数 (会话那根)
      toolCtx.tools.register(makeSyncStatusTool(resolveTarget, readMetaFor, async (scope) => (
        scope === TARGETS.SESSIONS ? (await readPeerCards(repoDir)).cards.length : 0
      )))
      toolCtx.tools.register(makeSyncPullTool({ resolveTarget, ctx, confirmVia: resolved.confirmVia, eventGate, logger }))
      toolCtx.tools.register(makeSyncPushTool({ resolveTarget, ctx, confirmVia: resolved.confirmVia, eventGate, logger }))
      // Luwi 加 (G1 收口 C9): 只读自检 (不确认、不触网)
      toolCtx.tools.register(makeSyncDoctorTool(collectDoctor))
      // Luwi 加 (G1-2 对端): 通道开着才注册 (关着时这个工具只会回一句"通道没开")
      if (peerRuntime.enabled) toolCtx.tools.register(makeSyncPeerTool(peerRuntime))
    })
  }

  // --- 自动模式（全部 effect：事件/定时随插件卸载自动撤销）。
  // 挂载时自动拉取（配置即授权；失败只记日志，绝不阻断启动）。
  if (resolved.autoPullOnStart) {
    ctx.effect(() => {
      void pullSync().then(result => {
        if (!result.ok) warn(`auto pull failed: ${result.error ?? 'unknown error'}`)
      }, error => warn(`auto pull threw: ${messageOf(error)}`))
      return () => {}
    }, `${PLUGIN_NAME}.auto-pull-on-start`)
  }

  // turn/end 自动推送（配置即授权；每轮结束拉一次快照并推送）。
  if (resolved.autoPushOnTurnEnd) {
    ctx.on('session/event', (session, event) => {
      if (event.type !== 'turn/end') return
      void pushSync().then(result => {
        maybeAppendSessionEvent(session, SESSION_EVENTS.PUSH, {
          sessionId: session.id,
          ok: result.ok,
          error: result.error,
          pushed: result.pushed,
          mirrored: result.mirrored,
          deleted: result.deleted,
          head: result.head,
        }, eventGate, warn)
        if (!result.ok) warn(`auto push failed: ${result.error ?? 'unknown error'}`)
      }, error => warn(`auto push threw: ${messageOf(error)}`))
    })
  }

  // 兜底推送 (2026-10-21 深夜): `turn/end` 那条钩子在这台桌面宿主上实测**不触发**
  // (面板读数 autoPushOnTurnEnd=true, 但调试日志里一条 push 都没有), 于是"回合结束即推送"
  // 只在某些会话上生效。加一条定时器: 每 3 分钟无条件推一次 (没有变化时引擎的提交与
  // 远端树比较都会直接返回, 代价很小), 推送因此不再依赖那个钩子
  if (resolved.autoPushOnTurnEnd) {
    ctx.effect(() => {
      const timer = setInterval(() => {
        void pushSync().then(result => {
          if (!result.ok) warn(`periodic push failed: ${result.error ?? 'unknown error'}`)
        }, error => warn(`periodic push threw: ${messageOf(error)}`))
      }, 3 * 60_000)
      if (typeof timer.unref === 'function') timer.unref()
      return () => clearInterval(timer)
    }, `${PLUGIN_NAME}.periodic-push`)
  }

  // Luwi 加 (P2P T4): 常驻那半边 —— sidecar 起来之后收入站; resident 档再起 60 s 心跳。
  // 随 fiber 撤销 (ctx.effect 的收尾), 不占任何全局 handler
  ctx.effect(() => {
    let disposed = false
    void (async () => {
      try {
        await p2pService.sidecar.ensure()
      } catch {
        // 没有二进制 / 起不来: 同步照走 git, 心跳每拍会再试
      }
      if (disposed) return
      p2pService.start()
    })()
    return () => {
      disposed = true
      void p2pService.stop().catch(() => {})
    }
  }, `${PLUGIN_NAME}.p2p`)

  // 周期拉取（pullIntervalMinutes > 0 时；定时器随 fiber 自动撤销）。
  // Luwi 加 (2026-10-20): 一个仓库里的多根源由同一个引擎一次拉完, 所以这里只剩一份定时器
  // (工作区那根不再单独一套)。间隔改成**热更新** —— 手机设置页那个滑块不该逼人重启 host:
  // 每 3 s 看一眼配置文件, 值变了就重挂; remote/branch 变了仍要重启 (引擎与后端构造时定死)
  ctx.effect(() => {
    let timer = null
    let current = resolved.pullIntervalMinutes
    const arm = (minutes) => {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
      // Luwi 加 (2026-10-23): 这个数同时是 P2P 心跳的闸 —— 有周期拉取就不另起心跳
      // (pullSync 之前本来就要先试一趟 P2P), 归零时再让心跳回来兜底
      periodicPullMinutes = minutes
      p2pService.syncHeartbeat()
      if (!(minutes > 0)) return
      timer = setInterval(() => {
        void pullSync().then(result => {
          if (!result.ok) warn(`periodic pull failed: ${result.error ?? 'unknown error'}`)
        }, error => warn(`periodic pull threw: ${messageOf(error)}`))
      }, minutes * 60_000)
      // 不让定时器替宿主的进程"续命" (测试里挂载之后没人 dispose 也不会永远等下去)
      if (typeof timer.unref === 'function') timer.unref()
    }
    arm(current)
    const watch = setInterval(() => {
      void (async () => {
        try {
          const tree = await readSyncTree(syncConfigFile)
          const next = Number.isInteger(tree.pullIntervalMinutes) && tree.pullIntervalMinutes >= 0 ? tree.pullIntervalMinutes : 0
          if (next === current) return
          current = next
          arm(next)
          logger.info(`periodic pull interval is now ${next} minute(s) (hot reloaded)`)
        } catch {
          // 读不动 / 文件正在被写: 下一拍再试
        }
      })()
    }, 3000)
    if (typeof watch.unref === 'function') watch.unref()
    return () => {
      clearInterval(watch)
      if (timer !== null) clearInterval(timer)
    }
  }, `${PLUGIN_NAME}.periodic-pull`)
}

/**
 * 插件挂载（外层, C1）：只负责"绝不抛"。
 *
 * 2026-10-13 的教训: Host 启动失败会走到 Electron 原生恢复对话框, 而它会把这台机器上
 * 251 行的 cordis.patch.yml 改名成 .bak 并让下次启动用空 patch。所以插件的任何意外都必须
 * 在这一层被吃掉并降级成一行 error —— 插件挂不上是小事, 把宿主的配置掀了不是
 * @param {import('@deepseek-ai/cordis').Context} ctx - Cordis 上下文。
 * @param {Partial<Config>} [config] - 显式配置层（profile 覆盖行 / bundle patch）。
 */
export function apply(ctx, config = {}) {
  try {
    return applyInner(ctx, config)
  } catch (error) {
    mountState.mounted = false
    mountState.disabledReason = messageOf(error)
    try {
      ctx.logger(PLUGIN_NAME).error(`mount failed, plugin disabled: ${messageOf(error)}`)
    } catch {
      // 连 logger 都拿不到的时候什么都别做: 绝不因为"报错本身失败"把宿主拖下水
    }
    return undefined
  }
}

export {
  // 复用/测试面：引擎、后端、纯函数与词汇。
  GitBackend,
  SyncEngine,
  EncryptedBackend,
  selectEncryptionMode,
  detectAge,
  ageEncrypt,
  ageDecrypt,
  encryptTree,
  decryptTree,
  mergeTrees,
  collectStatus,
  renderStatus,
  renderDiff,
  renderPull,
  renderPush,
  // Luwi 加 (G1-D): /sync peers 的渲染
  renderPeers,
  confirmSync,
  makeEventGate,
  maybeAppendSessionEvent,
  hasOpenTurn,
  openTurnFromProjection,
  resolveSessionRoot,
  resolveRepoDir,
  assertNestingSafe,
  sessionSyncDomainSpec,
  registryUnavailable,
  errorValue,
}
// 注 (G1 面板): `tryResolveConfig` / `mountState` / `MOUNT_KEY` 与 `makeSyncDoctorTool` 都是
// 文件里就地带 `export` 的声明 (见上面的 `export const` / `export function`)。它们曾被**同时**
// 写进上面那张表, 于是这一份树在任何一次 `import` 上都是 `SyntaxError: Duplicate export`
// —— 整棵树加载不起来, 而不是某一个测试红。往这张表里加名字之前先确认那名字不是就地导出的
