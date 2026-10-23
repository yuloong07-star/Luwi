// lib/panel-routes.mjs — 面板的宿主半: 官方侧边栏那块「详细操作面板」的数据通道。
//
// 干什么: 把插件**已有**的读数与动作包成一张只在宿主自己的 HTTP 服务上开着的 JSON 面,
// 浏览器半 (lib/client.js) 同源 fetch 它。不新增任何同步语义。
//
//   GET  /luwi-sync/panel   快照: 挂载态 / 配置面 / 两根镜像 / 运行中的操作 / 成本预告
//   GET  /luwi-sync/doctor  只读自检 (runDoctor, 不触网不写盘)
//   GET  /luwi-sync/peers   镜像里的名片 (readPeerCards)
//   POST /luwi-sync/op      {op, target, confirm} 跑一个动作
//
// 纪律 (与 docs/Luwi-dsh-sync-设计.md §11.3 / §11.4 逐条对上):
//   · 应答一律 JSON, 非 2xx 也是 —— 面板永远解析得动, 不靠状态码分支
//   · remote 只回 set|unset (± 脱敏串), 值里可能有 token, 绝不回显 (与启动行同一条纪律)
//   · 写操作要求 body 里 confirm === true, **不做任何隐式确认**; GET 永不产生写
//   · 写操作单飞: 同一时刻一个, 第二个回 409 + 正在跑的那个操作 id
//   · 面板这一路**不调** confirmSync: 人的两次点击就是这条渠道的确认 (设计 §11.4)
//   · 本模块不向外抛: 任何失败都降级成 {ok:false, error}

import { readdir, stat } from 'node:fs/promises'
import { join } from 'node:path'

import { sanitizeRemote } from './sanitize.mjs'
import { messageOf } from './errors.mjs'

/** 路由前缀 (webserver 的 path 不带尾斜杠)。 */
export const PANEL_PREFIX = '/luwi-sync'

/**
 * 握手标记。浏览器半拿它判"宿主到底开没开这条通道" —— 没有 webServer 的宿主上,
 * 这些路径会落到宿主的 SPA fallback 上 (回 index.html 或 404), 靠这个字段分辨。
 */
export const PANEL_HANDSHAKE = 'luwi-dsh-sync/panel'

/** 快照的版本号 (字段增删时动它, 浏览器半按它决定降级渲染)。 */
// v2 (Luwi, G1-2): 快照多一块 peer (对端通道读数)
// v3 (Luwi, 2026-10-20): 一个仓库多根源 / 扫码配对 / 会话基线 / 手机只读那一档
// v4 (Luwi, 2026-10-23): 账号权益 (license 块) —— 生成配对码的前提
export const PANEL_SCHEMA_VERSION = 4

/** 面板能发的动作。 */
export const PANEL_OPS = Object.freeze({
  STATUS: 'status',
  PULL: 'pull',
  PUSH: 'push',
  PEERS: 'peers',
  DOCTOR: 'doctor',
  // Luwi 加 (2026-10-15): 写配置 —— 主人要"在 PC 面板里填仓库地址、拨开关", 写的就是
  // $DSH_HOME/lw/session-sync.json (手机设置页读写的同一份)
  CONFIGURE: 'configure',
  // Luwi 加 (2026-10-20): 生成手机配对码 —— 回应里是一张画好的 SVG 与可复制的文本,
  // 不进快照、不落日志 (载荷里有仓库口令)
  PAIR: 'pair',
  // Luwi 加 (2026-10-20): 清理仓库会话 —— 把窗口外的镜像会话与合并副本从仓库工作树里删掉并推一次
  // (本机会话存储一个字节都不动, 见 index.mjs 的 pruneRepoSessions)
  PRUNE: 'prune',
  // Luwi 加 (P2P T6): 信任表的两个写动作 —— 确认换机 (换过 nodeId 的对端重新放行) 与吊销
  P2P_CONFIRM: 'p2p-confirm',
  P2P_REVOKE: 'p2p-revoke',
  // Luwi 加 (2026-10-23): 账号 —— 生成配对码的前提; 登录走"浏览器打开官网 + 面板轮询"
  LOGIN_START: 'login-start',
  LOGIN_POLL: 'login-poll',
  LOGOUT: 'logout',
  LICENSE_REFRESH: 'license-refresh',
})

/** 会改远端/本地工作树/配置的那几个: 要 confirm, 要单飞。 */
export const WRITE_OPS = Object.freeze([PANEL_OPS.PULL, PANEL_OPS.PUSH, PANEL_OPS.CONFIGURE, PANEL_OPS.PRUNE, PANEL_OPS.P2P_CONFIRM, PANEL_OPS.P2P_REVOKE])

/** POST 体上限: 这条通道只收一个短 JSON, 大一点的都是误用。 */
export const MAX_BODY_BYTES = 4096

/** 成本预告的缓存时长 —— 目录遍历不是每个刷新周期都该付的账。 */
export const COST_TTL_MS = 60_000

/** 成本预告的遍历上限 (条目数), 防止误指到一棵巨大的树上。 */
export const COST_MAX_ENTRIES = 200_000

/** 目标词汇: 两根镜像各一。 */
export const PANEL_TARGETS = Object.freeze({ SESSIONS: 'sessions', WORKSPACE: 'workspace' })

/**
 * 面板这一层的输入错误 (回 400)。带上给面板看的一句话。
 */
export class PanelBadRequest extends Error {
  /**
   * @param {string} message - 给人看的原因。
   */
  constructor(message) {
    super(message)
    this.name = 'PanelBadRequest'
  }
}

/**
 * 远端读数的统一形状: 只报 set/unset, 脱敏之后才给值。
 * @param {string} remote - 原始远端地址 (可能是本地路径, 也可能带 token)。
 * @returns {{state: 'set'|'unset', display: string}} 远端读数。
 */
export function describeRemote(remote) {
  const text = typeof remote === 'string' ? remote.trim() : ''
  if (text === '') return { state: 'unset', display: '' }
  return { state: 'set', display: sanitizeRemote(text) }
}

/**
 * 相对时间的底数: 面板画"3 分钟前"要一个 (now - at) 的毫秒数。
 * @param {number|undefined} at - 绝对毫秒时间戳。
 * @param {number} now - 当前时间。
 * @returns {number|undefined} 毫秒差 (缺或者不是有限数时 undefined)。
 */
export function ageMsOf(at, now) {
  return Number.isFinite(at) ? Math.max(0, now - /** @type {number} */ (at)) : undefined
}

/**
 * 写应答: 一律 JSON, 非 2xx 也一样。
 * @param {import('node:http').ServerResponse} res - 响应对象。
 * @param {number} status - HTTP 状态码。
 * @param {object} body - JSON 可序列化的应答体。
 * @returns {void}
 */
export function sendJson(res, status, body) {
  let payload
  try {
    payload = JSON.stringify(body ?? { ok: false })
  } catch (error) {
    // 循环引用/大数怪值: 宁可回一句能读的话, 也不让 handler 抛到宿主
    payload = JSON.stringify({ ok: false, error: `panel response is not JSON-serializable: ${messageOf(error)}` })
    status = 500
  }
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(payload)
}

/**
 * 读一个小的 JSON 请求体 (上限 MAX_BODY_BYTES)。
 * @param {import('node:http').IncomingMessage} req - 请求对象。
 * @param {number} [limit] - 字节上限。
 * @returns {Promise<object>} 解析后的对象 (空体 = {})。
 * @throws {PanelBadRequest} 体过大 / 不是 JSON / 不是对象。
 */
export async function readJsonBody(req, limit = MAX_BODY_BYTES) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > limit) throw new PanelBadRequest(`request body exceeds ${limit} bytes`)
    chunks.push(chunk)
  }
  const text = Buffer.concat(chunks).toString('utf8').trim()
  if (text === '') return {}
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new PanelBadRequest('request body is not JSON')
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new PanelBadRequest('request body must be a JSON object')
  }
  return parsed
}

/**
 * 一根镜像的目录成本 (文件数 + 字节数): 面板在 push 之前给的成本预告。
 * 跳过 `.git`; 条目数超上限就停下并标记 truncated (如实报, 不假装量完)。
 * @param {string} root - 镜像工作树根。
 * @param {number} [maxEntries] - 条目上限。
 * @returns {Promise<{files: number, bytes: number, truncated: boolean}|null>} 目录不存在时 null。
 */
export async function measureTree(root, maxEntries = COST_MAX_ENTRIES) {
  if (typeof root !== 'string' || root.trim() === '') return null
  let files = 0
  let bytes = 0
  let truncated = false
  const stack = [root]
  try {
    const top = await stat(root)
    if (!top.isDirectory()) return null
  } catch {
    return null
  }
  while (stack.length > 0) {
    const dir = /** @type {string} */ (stack.pop())
    let entries
    try {
      entries = await readdir(dir, { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      if (entry.name === '.git') continue
      const full = join(dir, entry.name)
      if (entry.isDirectory()) {
        stack.push(full)
        continue
      }
      if (!entry.isFile()) continue
      try {
        const info = await stat(full)
        files += 1
        bytes += info.size
      } catch {
        continue
      }
      if (files >= maxEntries) {
        truncated = true
        return { files, bytes, truncated }
      }
    }
  }
  return { files, bytes, truncated }
}

/**
 * 面板的宿主半。
 *
 * @param {object} deps - 装配面 (全部在 index.mjs 的 applyInner 里现拿)。
 * @param {Array<{target: string, label: string, engine: object|null, repoDir: string, remote: string, mirrorDir: string}>} deps.mirrors 两根镜像的描述 (engine 为 null = 没开)。
 * @param {(scope: string) => () => Promise<object>} deps.readMetaFor 元数据读取 (会话/工作区各一份)。
 * @param {() => Promise<{cards: object[], broken?: number}>} deps.readPeers 读镜像里的名片。
 * @param {() => Promise<object>} [deps.readPeer] Luwi 加 (G1-2)：对端通道读数（缺省不给 peer 字段）。
 * @param {() => Promise<object>} [deps.readP2p] Luwi 加 (P2P T6)：P2P 传输读数（缺省不给 p2p 字段）。
 * @param {() => Promise<object>} deps.collectDoctor 只读自检 (runDoctor 的装配)。
 * @param {(deviceId: string) => Promise<{ok: boolean}>} [deps.p2pConfirm] Luwi 加 (P2P T6)：确认换机。
 * @param {(deviceId: string) => Promise<{ok: boolean}>} [deps.p2pRevoke] Luwi 加 (P2P T6)：吊销对端。
 * @param {object} deps.info - 启动面读数 { plugin, version, runner, disabledReason, mounted, config }。
 * @param {object} [deps.logger] - 宿主 logger (可选)。
 * @param {number} [deps.now] - 取当前时间的钩子 (测试用)。
 * @returns {{snapshot: () => Promise<object>, runOp: (req: object) => Promise<{status: number, body: object}>, routes: () => Array<{kind: string, path: string, handler: Function}>, dispose: () => void}} 面板句柄。
 */
export function createPanel(deps) {
  const now = deps.now ?? (() => Date.now())
  const logger = deps.logger ?? { warn() {}, info() {} }
  /** 成本预告缓存: 每个 target 一份, 各自带时间戳。 */
  const costs = new Map()
  /** 单飞: 正在跑的写操作 (null = 空闲)。 */
  let running = null
  /** 上一次动作的结果 (面板刷新后还看得见)。 */
  let lastOp = null
  /** 递增的操作 id (面板拿它认"这是不是我发的那一次")。 */
  let opSeq = 0

  const mirrorOf = (target) => deps.mirrors.find(m => m.target === target)

  /**
   * 目标词汇校验。
   * @param {unknown} value - 客户端给的值。
   * @returns {string} 合法目标 (缺省 = 会话那根)。
   * @throws {PanelBadRequest} 不认识的目标。
   */
  function normalizeTarget(value) {
    if (value === undefined || value === null || value === '') return PANEL_TARGETS.SESSIONS
    if (value === PANEL_TARGETS.SESSIONS || value === PANEL_TARGETS.WORKSPACE) return value
    throw new PanelBadRequest(`unknown target ${JSON.stringify(value)} (expected sessions|workspace)`)
  }

  /**
   * 成本预告 (带缓存; 目录不存在 = null)。
   * @param {{target: string, repoDir: string}} mirror - 镜像描述。
   * @returns {Promise<object|null>} {files, bytes, truncated, at} 或 null。
   */
  async function costOf(mirror) {
    const cached = costs.get(mirror.target)
    if (cached !== undefined && now() - cached.at < COST_TTL_MS) return cached.value
    const measured = await measureTree(mirror.repoDir)
    const value = measured === null ? null : { ...measured, at: now() }
    costs.set(mirror.target, { at: now(), value })
    return value
  }

  /**
   * 一根镜像的完整读数 (状态 + 元数据 + 名片 + 成本)。
   * @param {object} mirror - 镜像描述。
   * @param {{withStatus: boolean}} opts - 要不要跑 status (它要跑 git, 只读但慢)。
   * @returns {Promise<object>} 卡片数据。
   */
  async function cardOf(mirror, opts) {
    /** @type {object|null} */
    let status = null
    let error
    if (mirror.engine !== null && opts.withStatus) {
      try {
        status = await mirror.engine.status()
        if (status !== null && status.ok === false && typeof status.error === 'string') error = status.error
      } catch (thrown) {
        error = messageOf(thrown)
      }
    }
    let meta = {}
    try {
      meta = await deps.readMetaFor(mirror.target)()
    } catch (thrown) {
      error = error ?? messageOf(thrown)
    }
    const remote = describeRemote(mirror.remote)
    const ts = now()
    return {
      target: mirror.target,
      label: mirror.label,
      on: mirror.engine !== null,
      repoDir: mirror.repoDir,
      mirrorDir: mirror.mirrorDir,
      remote,
      status,
      error,
      meta: {
        deviceId: typeof meta.deviceId === 'string' ? meta.deviceId : '',
        lastPullAt: Number.isFinite(meta.lastPullAt) ? meta.lastPullAt : undefined,
        lastPullAgeMs: ageMsOf(meta.lastPullAt, ts),
        lastPushAt: Number.isFinite(meta.lastPushAt) ? meta.lastPushAt : undefined,
        lastPushAgeMs: ageMsOf(meta.lastPushAt, ts),
        lastPushHead: typeof meta.lastPushHead === 'string' ? meta.lastPushHead : undefined,
        lastError: typeof meta.lastError === 'string' && meta.lastError !== '' ? meta.lastError : undefined,
      },
      cost: mirror.engine === null ? null : await costOf(mirror),
    }
  }

  /**
   * 面板快照。
   * @param {{withStatus?: boolean}} [opts] - withStatus 缺省 true (面板要 ahead/behind)。
   * @returns {Promise<object>} 快照 JSON。
   */
  async function snapshot(opts = {}) {
    const withStatus = opts.withStatus !== false
    const ts = now()
    const mirrors = []
    for (const mirror of deps.mirrors) mirrors.push(await cardOf(mirror, { withStatus }))
    let peers = null
    if (mirrors.some(m => m.target === PANEL_TARGETS.SESSIONS && m.on)) {
      try {
        const read = await deps.readPeers()
        peers = { cards: read.cards, broken: read.broken ?? 0 }
      } catch (thrown) {
        peers = { cards: [], broken: 0, error: messageOf(thrown) }
      }
    }
    // Luwi 加 (G1-2): 对端通道那一块 (令牌只出打码; 没有这一段就整个字段不给)
    let peer = null
    if (typeof deps.readPeer === 'function') {
      try {
        peer = await deps.readPeer()
      } catch (thrown) {
        peer = { enabled: false, error: messageOf(thrown) }
      }
    }
    // Luwi 加 (2026-10-22, P2P T6): P2P 传输读数 (path / rtt / 信任 / SAS / 冷却; 没给这一段就整个字段不给)
    let p2p = null
    if (typeof deps.readP2p === 'function') {
      try {
        p2p = await deps.readP2p()
      } catch (thrown) {
        p2p = { enabled: false, error: messageOf(thrown) }
      }
    }
    // Luwi 加 (2026-10-20): 配对摘要 (paired / from / 仓库主机; **绝不带载荷**)
    let pairing = null
    if (typeof deps.readPairing === 'function') {
      try {
        pairing = await deps.readPairing()
      } catch (thrown) {
        pairing = { paired: false, error: messageOf(thrown) }
      }
    }
    // Luwi 加 (2026-10-23): 账号权益 (state / login / 到期 / 有没有 token; **token 本身永不外发**)
    let license = null
    if (typeof deps.readLicense === 'function') {
      try {
        license = await deps.readLicense()
      } catch (thrown) {
        license = { state: 'invalid', reason: messageOf(thrown) }
      }
    }
    return {
      ok: true,
      handshake: PANEL_HANDSHAKE,
      v: PANEL_SCHEMA_VERSION,
      plugin: deps.info.plugin,
      version: deps.info.version,
      serverTime: ts,
      mount: {
        mounted: deps.info.mounted === true,
        runner: deps.info.runner ?? '',
        disabledReason: deps.info.disabledReason ?? undefined,
      },
      config: deps.info.config,
      mirrors,
      peers,
      ...(peer === null ? {} : { peer }),
      ...(p2p === null ? {} : { p2p }),
      ...(pairing === null ? {} : { pairing }),
      ...(license === null ? {} : { license }),
      op: running === null ? lastOp : runningView(ts),
    }
  }

  /**
   * 正在跑的那个操作的对外读数 (面板画进度条要"已用时")。
   * @param {number} ts - 当前时间。
   * @returns {object} 操作读数。
   */
  function runningView(ts) {
    return {
      id: running.id,
      target: running.target,
      action: running.action,
      state: 'running',
      startedAt: running.startedAt,
      elapsedMs: Math.max(0, ts - running.startedAt),
      result: undefined,
      error: undefined,
    }
  }

  /**
   * 跑一个动作。只读的动作直接跑; 写动作要求 confirm 且单飞。
   * @param {object} request - {op, target, confirm}。
   * @returns {Promise<{status: number, body: object}>} HTTP 状态码 + JSON 应答。
   */
  async function runOp(request) {
    const op = typeof request.op === 'string' ? request.op : ''
    if (!Object.values(PANEL_OPS).includes(op)) {
      return { status: 400, body: { ok: false, error: `unknown op ${JSON.stringify(request.op)}` } }
    }

    // configure 是**全局**的 (写那份配置文件, 不针对哪一根镜像), 所以排在 target/mirror 那两道
    // 检查前面 —— 否则远端还没配上 (mirror.engine 为 null) 时, 恰恰是最需要写配置的那一刻被挡住
    if (op === PANEL_OPS.PAIR) {
      if (deps.info.config.panelOps !== true) {
        return { status: 403, body: { ok: false, error: 'panel-initiated writes are disabled (panelOps: false)' } }
      }
      if (typeof deps.buildPairing !== 'function') {
        return { status: 501, body: { ok: false, error: 'this host cannot build a pairing code' } }
      }
      try {
        const built = await deps.buildPairing(request)
        if (built === null || built?.ok !== true) {
          return { status: 200, body: { ok: false, action: op, error: built?.error ?? 'the pairing code was not built' } }
        }
        return { status: 200, body: { ok: true, action: op, payload: built.payload, svg: built.svg, summary: built.summary } }
      } catch (thrown) {
        return { status: 200, body: { ok: false, action: op, error: messageOf(thrown) } }
      }
    }

    if (op === PANEL_OPS.CONFIGURE) {
      if (deps.info.config.panelOps !== true) {
        return { status: 403, body: { ok: false, error: 'panel-initiated writes are disabled (panelOps: false)' } }
      }
      if (request.confirm !== true) {
        return { status: 400, body: { ok: false, error: 'op configure requires confirm: true (the panel asks twice on purpose)' } }
      }
      if (typeof deps.configure !== 'function') {
        return { status: 501, body: { ok: false, error: 'this host cannot write the sync config (no configure hook)' } }
      }
      try {
        const result = await deps.configure(request)
        if (result === null || result.ok !== true) {
          return { status: 200, body: { ok: false, action: op, error: result?.error ?? 'the configuration was not written' } }
        }
        // 插件是**挂载时**读配置的, 所以写完必须重启 host 才生效 —— 这一句由面板转告给人
        return { status: 200, body: { ok: true, action: op, changed: result.changed ?? [], restartRequired: true } }
      } catch (thrown) {
        return { status: 200, body: { ok: false, action: op, error: messageOf(thrown) } }
      }
    }

    // Luwi 加 (2026-10-23): 账号相关的四个动作 —— 都是"本机这一份", 不针对哪根镜像,
    // 所以排在 target 归一前面。登录本身是明确的点击, 不要第二次确认 (与 pair 同一口径)
    if (
      op === PANEL_OPS.LOGIN_START ||
      op === PANEL_OPS.LOGIN_POLL ||
      op === PANEL_OPS.LOGOUT ||
      op === PANEL_OPS.LICENSE_REFRESH
    ) {
      if (deps.info.config.panelOps !== true) {
        return { status: 403, body: { ok: false, error: 'panel-initiated writes are disabled (panelOps: false)' } }
      }
      const hook = op === PANEL_OPS.LOGIN_START
        ? deps.loginStart
        : op === PANEL_OPS.LOGIN_POLL
          ? deps.loginPoll
          : op === PANEL_OPS.LOGOUT
            ? deps.logout
            : deps.refreshLicense
      if (typeof hook !== 'function') {
        return { status: 501, body: { ok: false, error: 'this host has no account hook' } }
      }
      try {
        const result = await hook()
        return { status: 200, body: { ok: result?.ok === true, action: op, ...(result ?? {}) } }
      } catch (thrown) {
        return { status: 200, body: { ok: false, action: op, error: messageOf(thrown) } }
      }
    }

    // Luwi 加 (P2P T6): 信任表动作不碰镜像, 在 target 归一之前处理
    if (op === PANEL_OPS.P2P_CONFIRM || op === PANEL_OPS.P2P_REVOKE) {      if (deps.info.config.panelOps !== true) {
        return { status: 403, body: { ok: false, error: 'panel-initiated writes are disabled (panelOps: false)' } }
      }
      if (request.confirm !== true) {
        return { status: 400, body: { ok: false, error: `op ${op} requires confirm: true (the panel asks twice on purpose)` } }
      }
      const deviceId = typeof request.deviceId === 'string' ? request.deviceId.trim() : ''
      if (deviceId === '') return { status: 400, body: { ok: false, error: 'op p2p-* requires deviceId' } }
      const hook = op === PANEL_OPS.P2P_CONFIRM ? deps.p2pConfirm : deps.p2pRevoke
      if (typeof hook !== 'function') {
        return { status: 501, body: { ok: false, error: 'this host has no p2p trust hook' } }
      }
      try {
        const result = await hook(deviceId)
        return { status: 200, body: { ok: result?.ok === true, action: op, deviceId, ...(result ?? {}) } }
      } catch (thrown) {
        return { status: 200, body: { ok: false, action: op, deviceId, error: messageOf(thrown) } }
      }
    }

    let target
    try {
      target = normalizeTarget(request.target)
    } catch (thrown) {
      return { status: 400, body: { ok: false, error: messageOf(thrown) } }
    }
    const mirror = target === PANEL_TARGETS.WORKSPACE
      ? deps.mirrors[0]
      : mirrorOf(target)
    if (mirror === undefined || mirror.engine === null) {
      return { status: 409, body: { ok: false, error: `the ${target} mirror is not enabled on this host` } }
    }
    if (WRITE_OPS.includes(op) && deps.info.config.panelOps !== true) {
      return { status: 403, body: { ok: false, error: 'panel-initiated writes are disabled (panelOps: false)' } }
    }
    if (WRITE_OPS.includes(op) && request.confirm !== true) {
      // 两次点击的第二下才带 confirm: true; 缺它一律拒绝, 不做隐式确认
      return { status: 400, body: { ok: false, error: `op ${op} requires confirm: true (the panel asks twice on purpose)` } }
    }

    if (op === PANEL_OPS.DOCTOR) {
      try {
        const report = await deps.collectDoctor()
        return { status: 200, body: { ok: true, action: op, target, report } }
      } catch (thrown) {
        return { status: 200, body: { ok: false, action: op, target, error: messageOf(thrown) } }
      }
    }
    if (op === PANEL_OPS.PEERS) {
      try {
        const read = await deps.readPeers()
        return { status: 200, body: { ok: true, action: op, target, cards: read.cards, broken: read.broken ?? 0 } }
      } catch (thrown) {
        return { status: 200, body: { ok: false, action: op, target, error: messageOf(thrown) } }
      }
    }
    if (op === PANEL_OPS.STATUS) {
      const card = await cardOf(mirror, { withStatus: true })
      return { status: 200, body: { ok: true, action: op, target, card } }
    }

    // 到这里只剩 pull / push / prune: 单飞 + 记录
    if (running !== null) {
      return {
        status: 409,
        body: {
          ok: false,
          error: `another panel operation is still running (${running.action} on ${running.target})`,
          running: runningView(now()),
        },
      }
    }
    const id = `panel-${++opSeq}`
    running = { id, target, action: op, startedAt: now() }
    try {
      // Luwi 加 (2026-10-20): 清理仓库会话走宿主自己的口 (它要知道窗口与源文件)
      const result = op === PANEL_OPS.PRUNE
        ? await (typeof deps.pruneSessions === 'function'
          ? deps.pruneSessions()
          : Promise.reject(new Error('this host has no pruneSessions hook')))
        : await mirror.engine[op]()
      const finishedAt = now()
      lastOp = {
        id,
        target,
        action: op,
        state: result !== null && result.ok === true ? 'done' : 'failed',
        startedAt: running.startedAt,
        finishedAt,
        elapsedMs: Math.max(0, finishedAt - running.startedAt),
        result,
        error: result !== null && typeof result.error === 'string' ? result.error : undefined,
      }
      // 动作改了工作树: 成本预告的缓存当场作废
      costs.delete(target)
      return { status: 200, body: { ok: result !== null && result.ok === true, action: op, target, op: lastOp } }
    } catch (thrown) {
      const finishedAt = now()
      const error = messageOf(thrown)
      lastOp = {
        id,
        target,
        action: op,
        state: 'failed',
        startedAt: running.startedAt,
        finishedAt,
        elapsedMs: Math.max(0, finishedAt - running.startedAt),
        result: undefined,
        error,
      }
      logger.warn(`panel op ${op} on ${target} threw: ${error}`)
      return { status: 200, body: { ok: false, action: op, target, op: lastOp } }
    } finally {
      running = null
    }
  }

  /**
   * 路由表 (交给 `webCtx.webServer.register`)。
   * @returns {Array<{kind: string, path: string, handler: Function}>} 四条路由。
   */
  function routes() {
    /** @type {Array<{kind: string, path: string, handler: Function}>} */
    const table = []
    table.push({
      kind: 'exact',
      path: `${PANEL_PREFIX}/panel`,
      handler: async (req, res) => {
        try {
          // 快照要跑两根镜像的 status (只读 git), 面板每 5 s 拉一次 —— 这个频率下它够快
          sendJson(res, 200, await snapshot())
        } catch (thrown) {
          sendJson(res, 200, { ok: false, error: messageOf(thrown) })
        }
      },
    })
    table.push({
      kind: 'exact',
      path: `${PANEL_PREFIX}/doctor`,
      handler: async (req, res) => {
        try {
          sendJson(res, 200, { ok: true, action: PANEL_OPS.DOCTOR, report: await deps.collectDoctor() })
        } catch (thrown) {
          sendJson(res, 200, { ok: false, action: PANEL_OPS.DOCTOR, error: messageOf(thrown) })
        }
      },
    })
    table.push({
      kind: 'exact',
      path: `${PANEL_PREFIX}/peers`,
      handler: async (req, res) => {
        try {
          const read = await deps.readPeers()
          sendJson(res, 200, { ok: true, action: PANEL_OPS.PEERS, cards: read.cards, broken: read.broken ?? 0 })
        } catch (thrown) {
          sendJson(res, 200, { ok: false, action: PANEL_OPS.PEERS, error: messageOf(thrown) })
        }
      },
    })
    table.push({
      kind: 'exact',
      path: `${PANEL_PREFIX}/op`,
      handler: async (req, res) => {
        if (req.method !== 'POST') {
          sendJson(res, 405, { ok: false, error: `${PANEL_PREFIX}/op is POST-only` })
          return
        }
        let body
        try {
          body = await readJsonBody(req)
        } catch (thrown) {
          sendJson(res, 400, { ok: false, error: messageOf(thrown) })
          return
        }
        try {
          const { status, body: answer } = await runOp(body)
          sendJson(res, status, answer)
        } catch (thrown) {
          sendJson(res, 500, { ok: false, error: messageOf(thrown) })
        }
      },
    })
    return table
  }

  return {
    snapshot,
    runOp,
    routes,
    dispose: () => {
      costs.clear()
      running = null
    },
  }
}
