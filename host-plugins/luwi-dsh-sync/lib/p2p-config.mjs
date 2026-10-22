// lib/p2p-config.mjs — P2P 传输层的配置段与二进制定位（纯函数，零 fs / 零子进程依赖）。
//
// 这一层只回答三件事：p2p 段怎么补默认与校验、两端 sidecar 二进制该去哪儿找、没有二进制时
// status 长什么样。真正起进程、读 READY、崩溃重启在 lib/p2p-sidecar.mjs。
//
// 依据：docs/Luwi-异地同步-P2P实现方案-20261022.md §5.1~§5.3 与 §14 T2。

import { dirname, join } from 'node:path'
import { badConfig } from './errors.mjs'

/** `p2p.power` 的三档（§5.2）：常驻 / 充电时 / 按需。 */
export const P2P_POWER_MODES = Object.freeze(['resident', 'charging', 'ondemand'])

/** 缺省值；JSON 层与显式 cordis 键都只写自己真要改的那几项。 */
export const P2P_DEFAULTS = Object.freeze({
  enabled: false, // 缺省关：与 git 后端并存，必须显式开
  power: 'resident',
  heartbeatMs: 60000, // resident 档 HEADS 心跳；T1 实测 3.5% 单核上限，不放长
  idleMs: 0, // sidecar 空闲自退；resident = 0（不退），ondemand 缺省 90 s
  startTimeoutMs: 20000, // 等 READY 的上限
  connectTimeoutMs: 20000, // 一次拨号的上限（sidecar 内部还有一层 30 s）
  sessionTimeoutMs: 20000, // 一场会话的总预算（§5.6；首同步可以调大）
  maxBlobBytes: 8 * 1024 * 1024, // §5.4 body 上限
  fallbackToGit: true, // 连不上时退 git（§9）
  portmapper: true, // 与 iroh 缺省一致
  binaryPath: '', // 显式指定 sidecar（测试与非常规安装）
  inbound: true, // 收不收对端拨入（resident 档要收；只拨不收的档可以关）
})

/** 可调边界；越界在加载期响亮失败。 */
export const P2P_LIMITS = Object.freeze({
  MIN_HEARTBEAT_MS: 10000,
  MAX_HEARTBEAT_MS: 600000,
  MIN_IDLE_MS: 0,
  MAX_IDLE_MS: 3600000,
  MIN_START_TIMEOUT_MS: 1000,
  MAX_START_TIMEOUT_MS: 120000,
  MIN_CONNECT_TIMEOUT_MS: 1000,
  MAX_CONNECT_TIMEOUT_MS: 120000,
  MIN_SESSION_TIMEOUT_MS: 5000,
  MAX_SESSION_TIMEOUT_MS: 300000,
  MIN_MAX_BLOB_BYTES: 1024 * 1024,
  MAX_MAX_BLOB_BYTES: 64 * 1024 * 1024,
})

const isObject = value => typeof value === 'object' && value !== null && !Array.isArray(value)

function integerIn(name, value, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) {
    throw badConfig(`p2p.${name} must be an integer in [${min}, ${max}] (got ${JSON.stringify(value)})`)
  }
  return value
}

function booleanOf(name, value) {
  if (typeof value !== 'boolean') {
    throw badConfig(`p2p.${name} must be a boolean (got ${JSON.stringify(value)})`)
  }
  return value
}

/**
 * 补默认 + 校验 `p2p` 段。
 *
 * 语义与 resolveConfig 一致：非法配置**抛**（加载期由 tryResolveConfig 接住并停用插件），
 * 而不是悄悄退回缺省。
 * @param {unknown} raw - session-sync.json / cordis 里的 p2p 段（可为空）。
 * @returns {Readonly<{enabled: boolean, power: string, heartbeatMs: number, idleMs: number, startTimeoutMs: number, connectTimeoutMs: number, maxBlobBytes: number, fallbackToGit: boolean, portmapper: boolean, binaryPath: string}>} 规范化配置。
 */
export function resolveP2pConfig(raw) {
  if (raw !== undefined && raw !== null && !isObject(raw)) {
    throw badConfig(`p2p must be an object (got ${JSON.stringify(raw)})`)
  }
  const input = isObject(raw) ? raw : {}
  const power = input.power ?? P2P_DEFAULTS.power
  if (!P2P_POWER_MODES.includes(power)) {
    throw badConfig(`p2p.power must be one of ${P2P_POWER_MODES.join('|')} (got ${JSON.stringify(power)})`)
  }
  // `idleMs` 的缺省跟档走：常驻与充电两档不退，按需档最后一条连接关掉 90 s 后退
  const idleDefault = power === 'ondemand' ? 90_000 : 0
  const idleMs = integerIn('idleMs', input.idleMs ?? idleDefault, P2P_LIMITS.MIN_IDLE_MS, P2P_LIMITS.MAX_IDLE_MS)
  const binaryPath = input.binaryPath ?? P2P_DEFAULTS.binaryPath
  if (typeof binaryPath !== 'string') {
    throw badConfig('p2p.binaryPath must be a string')
  }
  return Object.freeze({
    enabled: booleanOf('enabled', input.enabled ?? P2P_DEFAULTS.enabled),
    power,
    heartbeatMs: integerIn('heartbeatMs', input.heartbeatMs ?? P2P_DEFAULTS.heartbeatMs, P2P_LIMITS.MIN_HEARTBEAT_MS, P2P_LIMITS.MAX_HEARTBEAT_MS),
    idleMs,
    startTimeoutMs: integerIn('startTimeoutMs', input.startTimeoutMs ?? P2P_DEFAULTS.startTimeoutMs, P2P_LIMITS.MIN_START_TIMEOUT_MS, P2P_LIMITS.MAX_START_TIMEOUT_MS),
    connectTimeoutMs: integerIn('connectTimeoutMs', input.connectTimeoutMs ?? P2P_DEFAULTS.connectTimeoutMs, P2P_LIMITS.MIN_CONNECT_TIMEOUT_MS, P2P_LIMITS.MAX_CONNECT_TIMEOUT_MS),
    sessionTimeoutMs: integerIn('sessionTimeoutMs', input.sessionTimeoutMs ?? P2P_DEFAULTS.sessionTimeoutMs, P2P_LIMITS.MIN_SESSION_TIMEOUT_MS, P2P_LIMITS.MAX_SESSION_TIMEOUT_MS),
    maxBlobBytes: integerIn('maxBlobBytes', input.maxBlobBytes ?? P2P_DEFAULTS.maxBlobBytes, P2P_LIMITS.MIN_MAX_BLOB_BYTES, P2P_LIMITS.MAX_MAX_BLOB_BYTES),
    fallbackToGit: booleanOf('fallbackToGit', input.fallbackToGit ?? P2P_DEFAULTS.fallbackToGit),
    portmapper: booleanOf('portmapper', input.portmapper ?? P2P_DEFAULTS.portmapper),
    binaryPath,
    inbound: booleanOf('inbound', input.inbound ?? P2P_DEFAULTS.inbound),
  })
}

/**
 * 从名片里挑"这次要连的对端"。
 *
 * 名片 `peers/<设备id>.json` 里的 `p2p` 段（§8.2）: `{nodeId, ticket?, relay?, updatedAt?}`。
 * 只认带 `nodeId` 且不是本机的那一张；ticket 只用于"发现不到时手拨"。
 * @param {Array<{deviceId?: string, name?: string, p2p?: {nodeId?: string, ticket?: string}}>} cards - 名片。
 * @param {string} myDeviceId - 本机设备 id（跳过自己那一张）。
 * @returns {{id: string, name: string, endpoint: string, ticket?: string}|null} 对端描述。
 */
export function selectP2pPeer(cards, myDeviceId) {
  for (const card of Array.isArray(cards) ? cards : []) {
    if (typeof card?.deviceId !== 'string' || card.deviceId === '' || card.deviceId === myDeviceId) continue
    const p2p = card.p2p !== null && typeof card.p2p === 'object' ? card.p2p : {}
    const endpoint = typeof p2p.nodeId === 'string' && p2p.nodeId !== '' ? p2p.nodeId : ''
    if (endpoint === '') continue
    const peer = {
      id: card.deviceId,
      name: typeof card.name === 'string' ? card.name : '',
      endpoint,
    }
    if (typeof p2p.ticket === 'string' && p2p.ticket !== '') peer.ticket = p2p.ticket
    return peer
  }
  return null
}

/**
 * sidecar 二进制候选路径（按顺序找）。
 *
 * 安卓侧与 `libnode.so` 同目录（真机上 `process.execPath` 就是 `<nativeLibraryDir>/libnode.so`，
 * 2026-10-19 实测）；桌面侧在插件目录的 `native/<平台>-<arch>/` 下。
 * @param {object} input - {platform, arch, execPath, pluginDir}。
 * @param {string} [input.platform] - process.platform。
 * @param {string} [input.arch] - process.arch。
 * @param {string} input.execPath - process.execPath。
 * @param {string} input.pluginDir - 插件根目录（package.json 所在）。
 * @returns {string[]} 候选绝对路径（可能为空）。
 */
export function p2pBinaryCandidates({ platform, arch, execPath, pluginDir }) {
  const candidates = []
  if (platform === 'android') {
    candidates.push(join(dirname(execPath), 'liblwiroh.so'))
    return candidates
  }
  if (platform === 'win32') {
    candidates.push(join(pluginDir, 'native', 'win32-x64', 'lwiroh.exe'))
    candidates.push(join(dirname(execPath), 'lwiroh.exe'))
    return candidates
  }
  if (platform === 'linux') {
    candidates.push(join(pluginDir, 'native', `linux-${arch === 'arm64' ? 'arm64' : 'x64'}`, 'lwiroh'))
    return candidates
  }
  if (platform === 'darwin') {
    candidates.push(join(pluginDir, 'native', `darwin-${arch === 'arm64' ? 'arm64' : 'x64'}`, 'lwiroh'))
    return candidates
  }
  return candidates
}

/**
 * 从候选里挑第一个存在的（`exists` 注入，测试不碰盘）。
 * @param {string[]} candidates - p2pBinaryCandidates 的产物。
 * @param {(path: string) => boolean} exists - 存在性探测。
 * @returns {string|null} 选中的路径或 null。
 */
export function selectP2pBinary(candidates, exists) {
  for (const candidate of candidates) {
    try {
      if (exists(candidate) === true) return candidate
    } catch {
      // 探测本身抛（权限/坏路径）就当这个候选不存在，继续看下一个
    }
  }
  return null
}

/**
 * `status` 的形状（T2 的空壳）：没有二进制时 available=false 而不是抛。
 * @param {object} input - {available, running, reason, detail, binary, candidates, endpointId, ticket, pid, httpPort, dataPort, uptimeMs, restarts, power, idleMs}。
 * @returns {object} JSON 安全的只读状态。
 */
export function p2pStatusShell(input = {}) {
  const available = input.available === true
  const status = {
    available,
    running: available && input.running === true,
    reason: input.reason ?? (available ? undefined : 'P2P_UNAVAILABLE'),
    detail: input.detail,
    binary: input.binary ?? null,
    candidates: input.candidates ?? [],
    endpointId: input.endpointId,
    ticket: input.ticket,
    pid: input.pid,
    httpPort: input.httpPort,
    dataPort: input.dataPort,
    uptimeMs: input.uptimeMs,
    restarts: input.restarts ?? 0,
    power: input.power,
    idleMs: input.idleMs,
  }
  for (const key of Object.keys(status)) {
    if (status[key] === undefined) delete status[key]
  }
  return status
}
