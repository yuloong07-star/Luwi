// lib/p2p-service.mjs — 把"挑对端 / 出站一趟 / 常驻入站 / 心跳 / 读数"包成一个服务。
//
// index.mjs 只需要三处接触：构造时给 `repoDir` 与 `deviceId`（可以是 getter）、同步前后各调一次
// `beforeSync()`、挂载时 `start()`。它不碰锁、不读镜像内容，失败一律回 `{ok:false}`。

import { P2pRunner } from './p2p-runner.mjs'
import { selectP2pPeer } from './p2p-config.mjs'

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
    this.repoDir = options.repoDir
    this.deviceId = typeof options.deviceId === 'function' ? options.deviceId : () => String(options.deviceId ?? '')
    this.readCards = options.readCards
    this.sidecar = options.sidecar
    this.onSession = options.onSession
    this.peer = null
    this.stopInbound = null
    this.runner = options.runner ?? new P2pRunner({
      sidecar: options.sidecar,
      config: options.config,
      repoDir: options.repoDir,
      deviceId: undefined,
      deviceName: '',
      peerProvider: () => this.peer,
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
      this.peer = selectP2pPeer(Array.isArray(cards) ? cards : [], myDeviceId)
    } catch {
      // 名片读不动 = 这一趟没有可连的对端；下一次再试
      this.peer = null
    }
    return this.peer
  }

  /**
   * 同步前后的一趟：解析对端 → 出站跑一场（内部是双向的）。
   * @param {{timeoutMs?: number}} [options] - 预算。
   * @returns {Promise<{ok: boolean, reason?: string, status?: object}>} 结果（失败不抛）。
   */
  async beforeSync(options = {}) {
    if (this.config.enabled !== true) return { ok: false, reason: 'P2P_DISABLED' }
    await this.refreshPeer()
    if (this.peer === null) return { ok: false, reason: 'P2P_UNAVAILABLE' }
    return this.runner.runOutbound(options)
  }

  /**
   * 常驻：入站循环（enabled 时才起）+ resident 档的心跳。
   * @returns {void}
   */
  start() {
    if (this.config.enabled !== true) return
    if (this.stopInbound !== null) return
    this.stopInbound = this.runner.startInboundLoop({ onSession: this.onSession })
    if (this.config.power === 'resident') this.runner.startHeartbeat()
  }

  /** 读数（§7.4 形状，走 runner）。 */
  async status() {
    return this.runner.status()
  }

  /** 停掉入站、心跳与 sidecar（幂等）。 */
  async stop() {
    if (this.stopInbound !== null) {
      this.stopInbound()
      this.stopInbound = null
    }
    await this.runner.stop()
  }
}
