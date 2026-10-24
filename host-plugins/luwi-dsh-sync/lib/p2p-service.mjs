// lib/p2p-service.mjs — 把"挑对端 / 出站一趟 / 常驻入站 / 心跳 / 读数"包成一个服务。
//
// index.mjs 只需要三处接触：构造时给 `repoDir` 与 `deviceId`（可以是 getter）、同步前后各调一次
// `beforeSync()`、挂载时 `start()`。它不碰锁、不读镜像内容，失败一律回 `{ok:false}`。

import { P2pRunner } from './p2p-runner.mjs'
import { selectP2pPeer } from './p2p-config.mjs'
import { evaluatePeer, readTrust, rememberPeer, sasDigits, writeTrust } from './p2p-trust.mjs'

/**
 * P2P 服务：对端解析 + 调度器 + 生命周期。
 */
export class P2pService {
  /**
   * @param {object} options - {config, repoDir, deviceId, readCards, sidecar, runner, deps, onSession}。
   * @param {object} options.config - 规范化的 p2p 配置。
   * @param {string} options.repoDir - 镜像根。
   * @param {string|(() => string)} options.deviceId - 本机设备 id（可以晚一点才有）。
   * @param {() => Promise<Array<object>>} options.readCards - 读 `peers/` 名片（返回 cards 数组）。
   * @param {object} options.sidecar - P2pSidecar（或同形状假对象）。
   * @param {P2pRunner} [options.runner] - 注入调度器（测试用）。
   * @param {object} [options.deps] - 透传给 runner 的时钟/定时器。
   * @param {(result: object) => void} [options.onSession] - 入站会话结束时的回调。
   */
  constructor(options) {
    this.config = options.config
    // Luwi 加 (2026-10-23): 有周期拉取时不再起独立心跳 —— P2P 本来就是每次 pullSync 的前置传输
    // (index.mjs 的 runP2pBeforeSync), 设置页那个 pullIntervalMinutes 已经把节奏定死了, 再叠一条
    // 60 s 心跳等于同一拍拨两条会话 (主人 2026-10-23: "间隔能用设置里周期拉取的间隔")
    this.periodicPull = typeof options.periodicPull === 'function' ? options.periodicPull : () => false
    this.heartbeatArmed = false
    this.started = false
    this.repoDir = options.repoDir
    this.deviceId = typeof options.deviceId === 'function' ? options.deviceId : () => String(options.deviceId ?? '')
    this.readCards = options.readCards
    this.sidecar = options.sidecar
    this.onSession = options.onSession
    // Luwi 加 (2026-10-23): 收费闸的判据由宿主注入 (它读 license 文件), lib 层只问"能不能用"
    // 缺省恒放行 —— 老调用方 (测试 / 没有账号模块的宿主) 行为一个字都不变
    this.requirePlan = typeof options.requirePlan === 'function' ? options.requirePlan : () => ({ ok: true })
    this.trustStore = options.trustStore ?? {
      read: repoDir => readTrust(repoDir),
      write: (repoDir, trust) => writeTrust(repoDir, trust),
    }
    this.peer = null
    this.trustState = undefined
    this.blockedPeer = undefined
    this.stopInbound = null
    this.runner = options.runner ?? new P2pRunner({
      sidecar: options.sidecar,
      config: options.config,
      repoDir: options.repoDir,
      deviceId: undefined,
      deviceName: '',
      peerProvider: () => this.peer,
      // 入站循环与出站也要过同一道闸 (只挡 beforeSync 的话, 对端仍能拨进来)
      planGate: () => this.requirePlan(),
      deps: options.deps,
    })
    // runner 的 deviceId 要跟着 getter 走（engine 的 meta 可能到挂载之后才读出来）
    this.runner.deviceId = this.deviceId
    this.runner.peerProvider = () => this.peer
  }

  /**
   * 重新解析当前对端（每次同步前调一次；读不到卡片不算错）。
   * @returns {Promise<{id: string, name: string, endpoint: string, ticket?: string}|null>} 对端。
   */
  async refreshPeer() {
    if (typeof this.readCards !== 'function') {
      this.peer = null
      return null
    }
    try {
      const myDeviceId = this.deviceId()
      if (myDeviceId === '') {
        // engine 的 meta 还没读出来: 不知道"谁是我"就分不清名片里哪张是自己的
        this.peer = null
        return null
      }
      const cards = await this.readCards()
      const candidate = selectP2pPeer(Array.isArray(cards) ? cards : [], myDeviceId)
      if (candidate === null) {
        this.peer = null
        this.trustState = 'no-peer'
        return null
      }
      // 信任表: 首次见到即信任; 换 nodeId 或吊销过都拒绝 (方案 §8.3)
      const trust = await this.trustStore.read(this.repoDir)
      const decision = evaluatePeer(trust, { deviceId: candidate.id, nodeId: candidate.endpoint })
      if (decision.ok !== true) {
        this.peer = null
        this.trustState = decision.state
        this.blockedPeer = { id: candidate.id, nodeId: candidate.endpoint, state: decision.state, previousNodeId: decision.previousNodeId }
        return null
      }
      const remembered = rememberPeer(trust, { deviceId: candidate.id, nodeId: candidate.endpoint, name: candidate.name })
      await this.trustStore.write(this.repoDir, remembered.trust)
      this.trustState = remembered.decision.state
      this.blockedPeer = undefined
      this.peer = candidate
      return this.peer
    } catch {
      // 名片/信任表读不动 = 这一趟没有可连的对端；下一次再试
      this.peer = null
    }
    return null
  }

  /**
   * 本机 P2P 身份（给名片写入用）：sidecar 就绪后才有 nodeId。
   * @returns {Promise<{nodeId: string, ticket?: string}|null>} 身份。
   */
  async identity() {
    if (this.config.enabled !== true) return null
    let debug = { at: new Date().toISOString(), enabled: true }
    try {
      const status = await this.runner.status()
      debug = { ...debug, endpointId: status.endpointId, running: status.running, available: status.available, reason: status.reason }
      if (typeof status.endpointId !== 'string' || status.endpointId === '') {
        await this.#debugIdentity(debug)
        return null
      }
      const identity = { nodeId: status.endpointId }
      if (typeof status.ticket === 'string' && status.ticket !== '') identity.ticket = status.ticket
      await this.#debugIdentity(debug)
      return identity
    } catch (error) {
      debug.error = error instanceof Error ? error.message : String(error)
      await this.#debugIdentity(debug)
      return null
    }
  }

  async #debugIdentity(value) {
    try {
      const { promises: fs } = await import('node:fs')
      await fs.mkdir(`${this.repoDir}/.p2p`, { recursive: true })
      await fs.writeFile(`${this.repoDir}/.p2p/identity-debug.json`, `${JSON.stringify(value)}\n`)
    } catch {
      // 诊断写盘失败不影响正事
    }
  }

  /**
   * 同步前后的一趟：解析对端 → 出站跑一场（内部是双向的）。
   * @param {{timeoutMs?: number}} [options] - 预算。
   * @returns {Promise<{ok: boolean, reason?: string, status?: object}>} 结果（失败不抛）。
   */
  async beforeSync(options = {}) {
    if (this.config.enabled !== true) return { ok: false, reason: 'P2P_DISABLED' }
    const gate = await this.#askPlan()
    if (gate.ok !== true) return { ok: false, reason: gate.reason }
    await this.refreshPeer()
    if (this.peer === null) return { ok: false, reason: 'P2P_UNAVAILABLE' }
    // 只让 deviceId 小的一方拨号: 一场会话本身是双向的 (先拉再服), 两端同时拨会撞成两条
    // 并发会话, 在同一个镜像上互相踩 (2026-10-22 真机上 MANIFEST 阶段被关流就是这么来的)
    const myDeviceId = this.deviceId()
    if (myDeviceId !== '' && myDeviceId > this.peer.id) return { ok: false, reason: 'P2P_WAITING' }
    return this.runner.runOutbound(options)
  }

  /**
   * 常驻：入站循环（enabled 时才起）+ 心跳（resident 档且**没有**周期拉取时给）。
   * @returns {void}
   */
  start() {
    if (this.config.enabled !== true) return
    if (this.stopInbound !== null) return
    this.started = true
    this.stopInbound = this.runner.startInboundLoop({ onSession: this.onSession })
    this.#armHeartbeat()
  }

  /**
   * 按"现在有没有周期拉取"起停心跳（幂等；设置页改了间隔由 index.mjs 那个 3 s 的 watch 叫它）
   *
   * 判据: `power === 'resident'` 且 `periodicPull()` 不为真 —— 有周期拉取时那一趟已经先试过
   * P2P, 再拨一条心跳就是同一拍两场会话 (2026-10-23)
   * @returns {boolean} 现在心跳开着吗。
   */
  syncHeartbeat() {
    this.#armHeartbeat()
    return this.heartbeatArmed
  }

  /** 起停的唯一一处（调用前先问过 started）。 */
  #armHeartbeat() {
    if (this.started !== true) return
    const wants = this.config.power === 'resident' && this.periodicPull() !== true
    if (wants === this.heartbeatArmed) return
    if (wants) {
      this.runner.startHeartbeat()
      this.heartbeatArmed = true
      return
    }
    if (typeof this.runner.stopHeartbeat === 'function') this.runner.stopHeartbeat()
    this.heartbeatArmed = false
  }

  /** 读数（§7.4 形状 + 信任状态与 SAS）。 */
  async status() {
    const base = await this.runner.status()
    const status = {
      ...base,
      peerEndpoint: this.peer?.endpoint,
      trustState: this.trustState,
      blockedPeer: this.blockedPeer,
      // Luwi 加 (2026-10-23): 订阅读数 —— 面板与 /sync status 都要一行"到期没到期"
      plan: await this.#planView(),
    }
    if (typeof base.endpointId === 'string' && base.endpointId !== '' && typeof this.peer?.endpoint === 'string') {
      status.sas = sasDigits(base.endpointId, this.peer.endpoint)
    }
    return status
  }

  /**
   * 问一次闸: 只有明确 ok 才放行; 判不出来按不放行 (失败关闭)
   * @returns {Promise<{ok: boolean, reason?: string}>}
   */
  async #askPlan() {
    try {
      const answer = await this.requirePlan()
      if (answer && answer.ok === true) return { ok: true }
      return { ok: false, reason: (answer && answer.reason) || 'P2P_PLAN_REQUIRED' }
    } catch {
      return { ok: false, reason: 'P2P_PLAN_REQUIRED' }
    }
  }

  /**
   * 读数里那一小段 (不含 token; 闸判不出来就给 null)
   * @returns {Promise<object|null>}
   */
  async #planView() {
    try {
      const answer = await this.requirePlan()
      if (!answer || typeof answer !== 'object') return null
      return answer.plan ?? null
    } catch {
      return null
    }
  }

  /** 停掉入站、心跳与 sidecar（幂等）。 */
  async stop() {
    this.started = false
    this.heartbeatArmed = false
    if (this.stopInbound !== null) {
      this.stopInbound()
      this.stopInbound = null
    }
    await this.runner.stop()
  }
}
