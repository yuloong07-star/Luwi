// lib/p2p-runner.mjs — 把 sidecar、会话状态机与 GC 编成一趟"可调度、可退路"的同步动作（方案 §7）。
//
// 它不碰 index.mjs 的锁，也不读镜像内容：只回答四件事：
//   1. 现在能不能试（熔断：连续 3 次 P2P_TIMEOUT / P2P_CRASH → 60 s 冷却）；
//   2. 试哪一端、从哪条路（出站拨对端 / 入站 accept 之后核对 peer id）；
//   3. 这一趟的读数（path / rtt / 收发文件与字节 / lastError / cooling），形状照 §7.4；
//   4. GC 的三个计数。
//
// 失败**不抛给调用方**：回 `{ok:false, status}`，让引擎安静地退回 git/API（§7.3 的"不拖慢已有的路"）。

import { ERROR_CODES } from './constants.mjs'
import { SyncError } from './errors.mjs'
import { P2P_GC_DEFAULTS, gcSummary, sweepAbsent, sweepPeerState, sweepTmp } from './p2p-gc.mjs'
import { P2pPeerSession } from './p2p-peer.mjs'

/** 熔断与预算的缺省（§7.3 / §7.2）。 */
export const P2P_RUN_DEFAULTS = Object.freeze({
  failureLimit: 3,
  cooldownMs: 60_000,
  sessionTimeoutMs: 20_000,
  heartbeatMs: 60_000,
  inboundTimeoutMs: 30_000,
})

/**
 * 一次 P2P 动作的调度器。
 */
export class P2pRunner {
  /**
   * @param {object} options - {sidecar, config, repoDir, deviceId, deviceName, peerProvider, sessionFactory, deps}。
   * @param {object} options.sidecar - P2pSidecar 实例（或同形状的假对象）。
   * @param {object} [options.config] - 已规范化的 p2p 配置。
   * @param {string} options.repoDir - 镜像根。
   * @param {string} options.deviceId - 本机设备 id。
   * @param {string} [options.deviceName] - 名片名。
   * @param {() => ({id: string, name?: string, ticket?: string}|null)} [options.peerProvider] - 当前要连的对端。
   * @param {(input: object) => {run: Function}} [options.sessionFactory] - 会话工厂（测试注入）。
   * @param {object} [options.deps] - {now, setInterval, clearInterval}。
   */
  constructor(options) {
    this.sidecar = options.sidecar
    this.config = options.config ?? { enabled: true, power: 'resident', heartbeatMs: 60000 }
    this.repoDir = options.repoDir
    this.deviceId = options.deviceId
    this.deviceName = options.deviceName ?? ''
    this.peerProvider = options.peerProvider ?? (() => null)
    this.sessionFactory = options.sessionFactory ?? (input => new P2pPeerSession(input))
    this.deps = {
      now: () => Date.now(),
      setInterval: (fn, ms) => setInterval(fn, ms),
      clearInterval: handle => clearInterval(handle),
      sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
      ...(options.deps ?? {}),
    }
    this.failures = 0
    this.coolingUntil = 0
    this.skipped = 0
    this.gcState = undefined
    this.lastError = undefined
    this.lastSession = undefined
    this.lastPath = undefined
    this.lastRttMs = undefined
    this.sent = 0
    this.received = 0
    this.sentBytes = 0
    this.receivedBytes = 0
    this.heartbeat = null
    this.inboundLoop = null
    this.stopped = false
  }

  /** 现在能不能试（冷却中就不试）。 */
  canAttempt() {
    return this.deps.now() >= this.coolingUntil
  }

  /**
   * 出站：拨当前对端并跑一趟。
   * @param {{timeoutMs?: number}} [options] - 预算。
   * @returns {Promise<{ok: boolean, status: object}>} 结果（失败不抛）。
   */
  async runOutbound(options = {}) {
    if (this.config.enabled !== true) return this.#result(false, 'P2P_DISABLED')
    if (!this.canAttempt()) {
      this.skipped += 1
      return this.#result(false, 'P2P_BUSY')
    }
    const peer = this.peerProvider()
    if (peer === null || typeof peer?.id !== 'string' || peer.id === '') return this.#result(false, 'P2P_UNAVAILABLE')
    try {
      const status = await this.sidecar.ensure()
      if (status?.running !== true) {
        return this.#failure(new SyncError(ERROR_CODES.P2P_UNAVAILABLE, `sidecar is not running (${status?.reason ?? 'unknown'})`), 'P2P_UNAVAILABLE')
      }
      const connection = await this.sidecar.openFrameStream({ to: peer.ticket ?? peer.endpoint ?? peer.id, timeoutMs: this.config.connectTimeoutMs })
      return await this.#run(connection, peer, options.timeoutMs ?? P2P_RUN_DEFAULTS.sessionTimeoutMs)
    } catch (error) {
      return this.#failure(error, errorCodeOf(error, 'P2P_IO'))
    }
  }

  /**
   * 入站：等一条对端拨入的会话，核对 peer id 后跑同一套状态机。
   * @param {{timeoutMs?: number}} [options] - 接受与跑会话的预算。
   * @returns {Promise<{ok: boolean, status: object}>} 结果（失败不抛）。
   */
  async runInbound(options = {}) {
    if (this.config.enabled !== true || this.config.inbound === false) return this.#result(false, 'P2P_DISABLED')
    if (this.sidecar.state !== 'running') return this.#result(false, 'P2P_UNAVAILABLE')
    const peer = this.peerProvider()
    const expectedNode = peer?.endpoint ?? peer?.id
    let inbound
    try {
      inbound = await this.sidecar.acceptInbound({ timeoutMs: options.acceptTimeoutMs ?? P2P_RUN_DEFAULTS.inboundTimeoutMs })
    } catch (error) {
      return this.#failure(error, 'P2P_TIMEOUT', { silent: true })
    }
    if (peer === null || inbound.peer !== expectedNode) {
      inbound.stream.close()
      return this.#failure(new SyncError(ERROR_CODES.P2P_NODE_CHANGED, `inbound node ${inbound.peer} is not the expected ${String(expectedNode)}`), 'P2P_NODE_CHANGED')
    }
    return this.#run({ path: 'ip', rttMs: 0, peer: inbound.peer, stream: inbound.stream }, peer, options.timeoutMs ?? P2P_RUN_DEFAULTS.sessionTimeoutMs)
  }

  /** 跑一场会话（带总预算；超时先关流再记账）。 */
  async #run(connection, peer, timeoutMs) {
    const started = this.deps.now()
    const session = this.sessionFactory({
      stream: connection.stream,
      repoDir: this.repoDir,
      myDeviceId: this.deviceId,
      peerDeviceId: peer.id,
      deviceName: this.deviceName,
    })
    let timer = null
    try {
      const result = await Promise.race([
        session.run(),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new SyncError(ERROR_CODES.P2P_TIMEOUT, `p2p session exceeded ${timeoutMs}ms`)), timeoutMs)
        }),
      ])
      this.failures = 0
      this.coolingUntil = 0
      this.lastError = undefined
      this.lastSession = { at: started, ms: this.deps.now() - started, role: result.role }
      this.lastPath = connection.path
      this.lastRttMs = connection.rttMs
      this.sent += Number(result.served?.files) || 0
      this.sentBytes += Number(result.served?.bytes) || 0
      this.received += Number(result.pulled?.files) || 0
      this.receivedBytes += Number(result.pulled?.bytes) || 0
      return this.#result(true)
    } catch (error) {
      try {
        connection.stream.close()
      } catch {
        // 已经断了
      }
      return this.#failure(error, errorCodeOf(error, 'P2P_IO'))
    } finally {
      if (timer !== null) clearTimeout(timer)
    }
  }

  async #failure(error, code, options = {}) {
    if (options.silent !== true) {
      this.failures += 1
      if (this.failures >= P2P_RUN_DEFAULTS.failureLimit) {
        this.coolingUntil = this.deps.now() + P2P_RUN_DEFAULTS.cooldownMs
      }
    }
    this.lastError = {
      code,
      message: error instanceof Error ? error.message : String(error),
      at: this.deps.now(),
    }
    return this.#result(false, code)
  }

  async #result(ok, reason = undefined) {
    return { ok, reason, status: await this.status() }
  }

  /**
   * 合并 sidecar 读数与此处的计数（形状照 §7.4）。
   * @returns {Promise<object>} 状态。
   */
  async status() {
    let sidecar = { available: false, running: false }
    try {
      sidecar = typeof this.sidecar.status === 'function' ? await this.sidecar.status() : (this.sidecar.status ?? sidecar)
    } catch (error) {
      sidecar = { available: false, running: false, reason: 'P2P_UNAVAILABLE', detail: error instanceof Error ? error.message : String(error) }
    }
    const peer = this.peerProvider() ?? null
    const cooling = !this.canAttempt()
    const path = this.config.enabled !== true
      ? 'disabled'
      : sidecar.available !== true
        ? 'offline'
        : this.lastPath ?? 'offline'
    return {
      enabled: this.config.enabled === true,
      available: sidecar.available === true,
      running: sidecar.running === true,
      mode: this.config.power ?? 'resident',
      path,
      rttMs: this.lastRttMs,
      peerId: peer?.id,
      peerName: peer?.name,
      endpointId: sidecar.endpointId,
      ticket: sidecar.ticket,
      lastSessionAt: this.lastSession?.at,
      lastSessionMs: this.lastSession?.ms,
      sent: this.sent,
      received: this.received,
      sentBytes: this.sentBytes,
      receivedBytes: this.receivedBytes,
      skipped: this.skipped,
      cooling,
      coolingUntil: cooling ? this.coolingUntil : undefined,
      failures: this.failures,
      gc: this.gcState,
      lastError: this.lastError,
      sidecarReason: sidecar.reason,
    }
  }

  /**
   * 跑一次 GC 并记读数。
   * @param {{peers?: Array<object>, peerId?: string, absent?: string[]}} [options] - 对端读数与缺席候选。
   * @returns {Promise<object>} gcSummary 的产物。
   */
  async gc(options = {}) {
    const now = this.deps.now()
    const tmp = await sweepTmp({ repoDir: this.repoDir, now })
    const peers = options.peers === undefined ? undefined : await sweepPeerState({ repoDir: this.repoDir, peers: options.peers, now })
    const absent = options.peerId === undefined || options.absent === undefined
      ? undefined
      : await sweepAbsent({ repoDir: this.repoDir, peerDeviceId: options.peerId, absent: options.absent, now, minAgeMs: P2P_GC_DEFAULTS.absentMinAgeMs })
    this.gcState = gcSummary({ tmp, peers, absent })
    return this.gcState
  }

  /**
   * 常驻心跳：每 `everyMs` 出站跑一趟（冷却中跳过）。返回的函数停掉它。
   * @param {{everyMs?: number, onTick?: Function, run?: Function}} [options] - 节奏与回调。
   * @returns {() => void} 停止函数。
   */
  startHeartbeat(options = {}) {
    this.stopHeartbeat()
    const everyMs = options.everyMs ?? this.config.heartbeatMs ?? P2P_RUN_DEFAULTS.heartbeatMs
    const run = options.run ?? (() => this.runOutbound())
    const handle = this.deps.setInterval(() => {
      void Promise.resolve(run()).then((result) => {
        if (typeof options.onTick === 'function') options.onTick(result)
      }).catch(() => {
        // run 自己已经吞了错误; 这里只防定时器把进程带崩
      })
    }, everyMs)
    this.heartbeat = handle
    return () => this.stopHeartbeat()
  }

  /** 停掉心跳（幂等）。 */
  stopHeartbeat() {
    if (this.heartbeat !== null) {
      this.deps.clearInterval(this.heartbeat)
      this.heartbeat = null
    }
  }

  /**
   * 常驻入站：一直等对端拨入并跑会话，直到 `stop()` 或返回的停止函数。
   *
   * 每轮都走 `runInbound` 的同一套核对与预算；被拒（P2P_UNTRUSTED）时歇一下再等，
   * 避免有人拿错误设备号刷回调。
   * @param {{onSession?: Function, retryMs?: number}} [options] - 回调与重试间隔。
   * @returns {() => void} 停止函数。
   */
  startInboundLoop(options = {}) {
    this.stopped = false
    const retryMs = options.retryMs ?? 1000
    const loop = async () => {
      while (!this.stopped) {
        const result = await this.runInbound().catch(() => ({ ok: false, reason: 'P2P_IO' }))
        if (typeof options.onSession === 'function') {
          try {
            options.onSession(result)
          } catch {
            // 回调自己的错不该终止入站循环
          }
        }
        if (this.stopped) return
        if (result.reason === 'P2P_DISABLED') return
        if (result.ok !== true && result.reason !== 'P2P_TIMEOUT') {
          // sidecar 还没起来时不要空转: 歇久一点再试 (心跳那条会负责把它拉起来)
          await this.deps.sleep(result.reason === 'P2P_UNAVAILABLE' ? retryMs * 5 : retryMs)
        }
      }
    }
    const pending = loop()
    this.inboundLoop = pending
    return () => {
      this.stopped = true
    }
  }

  /** 停掉心跳与 sidecar。 */
  async stop() {
    this.stopped = true
    this.stopHeartbeat()
    if (this.inboundLoop !== null && typeof this.inboundLoop.then === 'function') await Promise.race([this.inboundLoop, this.deps.sleep(2000)])
    if (this.sidecar !== null && typeof this.sidecar.stop === 'function') await this.sidecar.stop()
  }
}

/**
 * 错误 → 稳定错误码（不认识的按 IO 算）。
 * @param {unknown} error - 抛出的值。
 * @param {string} fallback - 兜底码。
 * @returns {string} 码。
 */
function errorCodeOf(error, fallback) {
  if (error instanceof SyncError && typeof error.code === 'string') return error.code
  return fallback
}

