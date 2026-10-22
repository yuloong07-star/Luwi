// lib/p2p-sidecar.mjs — sidecar 监管：定位二进制、起进程、等 READY、查状态、崩溃重启、关停。
//
// 它**不实现**同步协议：帧与合并在 JS 的其它文件里，sidecar 只是端点与字节管道（方案 §2 / §5.1）。
// 这一层对外的承诺只有三条（§14 T2）：
//   1. 没有二进制时 status 回 available=false，不抛；
//   2. 起不来看得见原因（P2P_UNAVAILABLE / P2P_CRASH），不静默；
//   3. 崩一次能重启（退避 2 s / 10 s，最多两次），两次都崩就放弃并如实记账。
//
// 依赖注入（spawn / exists / httpJson / sleep / now / randomToken）是为了单测不碰真进程真端口。

import { spawn as nodeSpawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { request } from 'node:http'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  p2pBinaryCandidates,
  p2pStatusShell,
  resolveP2pConfig,
  selectP2pBinary,
} from './p2p-config.mjs'

/** 崩溃后的退避（§5.2：重启两次，退避 2 s / 10 s）。 */
export const P2P_RESTART_BACKOFF_MS = Object.freeze([2000, 10000])
/** 最多重启几次；超过就停在 crashed。 */
export const P2P_MAX_RESTARTS = P2P_RESTART_BACKOFF_MS.length

const defaultDeps = () => ({
  spawn: nodeSpawn,
  exists: existsSync,
  httpJson: nodeHttpJson,
  sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: handle => clearTimeout(handle),
  now: () => Date.now(),
  randomToken: () => randomBytes(16).toString('hex'),
})

/**
 * 一次 HTTP 调用（只用于回环控制面）。
 * @param {object} input - {port, path, method, token, body, timeoutMs}。
 * @returns {Promise<{status: number, body: any}>} 响应。
 */
function nodeHttpJson({ port, path, method = 'GET', token, body, timeoutMs = 3000 }) {
  return new Promise((resolve, reject) => {
    const headers = {}
    if (token !== undefined) headers.authorization = `Bearer ${token}`
    if (body !== undefined) headers['content-length'] = Buffer.byteLength(body)
    const req = request({ host: '127.0.0.1', port, path, method, headers, timeout: timeoutMs }, (res) => {
      let text = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => {
        text += chunk
      })
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode ?? 0, body: text === '' ? null : JSON.parse(text) })
        } catch (error) {
          reject(new Error(`bad json from ${path}: ${error instanceof Error ? error.message : String(error)}`))
        }
      })
    })
    req.on('timeout', () => req.destroy(new Error(`timeout after ${timeoutMs}ms`)))
    req.on('error', reject)
    if (body !== undefined) req.write(body)
    req.end()
  })
}

/**
 * sidecar 监管者。一个实例对应一台设备上的一个 sidecar 进程。
 */
export class P2pSidecar {
  /**
   * @param {object} [options] - {config, pluginDir, platform, arch, execPath, deps}。
   * @param {object} [options.config] - `p2p` 段（未规范化也行，这里会过 resolveP2pConfig）。
   * @param {string} [options.pluginDir] - 插件根目录。
   * @param {string} [options.platform] - process.platform。
   * @param {string} [options.arch] - process.arch。
   * @param {string} [options.execPath] - process.execPath。
   * @param {object} [options.deps] - 注入的进程/时钟/HTTP 依赖。
   */
  constructor(options = {}) {
    this.config = resolveP2pConfig(options.config ?? {})
    this.pluginDir = options.pluginDir ?? join(dirname(fileURLToPath(import.meta.url)), '..')
    this.platform = options.platform ?? process.platform
    this.arch = options.arch ?? process.arch
    this.execPath = options.execPath ?? process.execPath
    this.deps = { ...defaultDeps(), ...(options.deps ?? {}) }

    /** @type {'idle'|'starting'|'running'|'restarting'|'stopping'|'stopped'|'crashed'} */
    this.state = 'idle'
    this.child = null
    this.token = ''
    this.httpPort = 0
    this.dataPort = 0
    this.endpoint = null
    this.restarts = 0
    this.lastExit = null
    this.lastDetail = undefined
    this.startedAt = 0
    this.stopping = false
    this.starting = null
    this.restartTimer = null
    this.stdoutBuffer = ''
    this.readyWaiters = []
  }

  /**
   * 定位二进制（每次都查盘；文件是构建产物，装完就可能出现）。
   * @returns {{binary: string|null, candidates: string[]}} 结果。
   */
  locate() {
    const candidates = this.config.binaryPath.trim() === ''
      ? p2pBinaryCandidates({ platform: this.platform, arch: this.arch, execPath: this.execPath, pluginDir: this.pluginDir })
      : [this.config.binaryPath]
    return { binary: selectP2pBinary(candidates, this.deps.exists), candidates }
  }

  /**
   * 只读状态：不 spawn、不写盘、不触网（控制面是回环 HTTP）。
   * @returns {Promise<object>} p2pStatusShell 的形状。
   */
  async status() {
    const { binary, candidates } = this.locate()
    const base = {
      available: binary !== null,
      running: this.state === 'running',
      binary,
      candidates,
      power: this.config.power,
      idleMs: this.config.idleMs,
      restarts: this.restarts,
    }
    if (binary === null) {
      return p2pStatusShell({
        ...base,
        reason: 'P2P_UNAVAILABLE',
        detail: 'no sidecar binary found; run native/iroh-sidecar/rebuild-win-x64.sh (desktop) or rebuild-arm64.sh (Android)',
      })
    }
    if (this.state !== 'running') {
      return p2pStatusShell({
        ...base,
        reason: this.state === 'crashed' ? 'P2P_CRASH' : this.config.enabled ? undefined : 'P2P_DISABLED',
        detail: this.state === 'crashed' ? (this.lastDetail ?? 'sidecar crashed and restarts are exhausted') : this.lastDetail,
      })
    }
    let endpoint = this.endpoint
    let detail = this.lastDetail
    try {
      const response = await this.deps.httpJson({ port: this.httpPort, path: '/v1/id', token: this.token, timeoutMs: 2000 })
      const body = response?.body ?? {}
      if (body && typeof body.id === 'string') {
        endpoint = { id: body.id, ticket: body.ticket }
        this.endpoint = endpoint
        detail = undefined
      }
    } catch (error) {
      detail = `sidecar is running but /v1/id failed: ${error instanceof Error ? error.message : String(error)}`
    }
    return p2pStatusShell({
      ...base,
      endpointId: endpoint?.id,
      ticket: endpoint?.ticket,
      pid: this.child?.pid,
      httpPort: this.httpPort,
      dataPort: this.dataPort,
      uptimeMs: this.startedAt === 0 ? undefined : Math.max(0, this.deps.now() - this.startedAt),
      detail,
    })
  }

  /**
   * 确保 sidecar 在跑（配置关着就只回状态）。
   *
   * 不因"起不来"抛错：缺二进制、spawn 失败、超时都会落成 status 里的 reason/detail。
   * @returns {Promise<object>} 状态。
   */
  async ensure() {
    if (this.config.enabled !== true) return this.status()
    if (this.state === 'running') return this.status()
    if (this.starting !== null) {
      await this.starting
      return this.status()
    }
    this.starting = this.#start()
    try {
      await this.starting
    } finally {
      this.starting = null
    }
    return this.status()
  }

  /**
   * 关停：先 `POST /v1/shutdown`，等它自己退；超时才 kill。
   * @returns {Promise<object>} 关停后的状态。
   */
  async stop() {
    this.stopping = true
    if (this.restartTimer !== null) {
      this.deps.clearTimeout(this.restartTimer)
      this.restartTimer = null
    }
    const child = this.child
    if (child === null) {
      this.state = 'stopped'
      return this.status()
    }
    this.state = 'stopping'
    if (this.httpPort !== 0) {
      try {
        await this.deps.httpJson({ port: this.httpPort, path: '/v1/shutdown', method: 'POST', token: this.token, timeoutMs: 3000 })
      } catch {
        // 已经死了/端口关了都算正常; 后面还有 kill 兜底
      }
      const deadline = this.deps.stopGraceMs ?? 3000
      let waited = 0
      while (this.child !== null && waited < deadline) {
        await this.deps.sleep(100)
        waited += 100
      }
    }
    if (this.child !== null) {
      try {
        this.child.kill()
      } catch {
        // ignore
      }
    }
    this.child = null
    this.httpPort = 0
    this.dataPort = 0
    this.endpoint = null
    this.state = 'stopped'
    // 故意不把 stopping 复位: kill() 的 exit 事件晚一拍才到, 复位早了它会当成一次崩溃去重启;
    // 下一次 ensure() 的 #start 会把它置回 false (2026-10-22 单测逮到的)
    return this.status()
  }

  /** 起一次进程并等 READY。内部方法，失败只记账不抛。 */
  async #start() {
    const { binary, candidates } = this.locate()
    if (binary === null) {
      this.state = 'unavailable'
      this.lastDetail = `no sidecar binary found (${candidates.join(', ') || 'no candidates'})`
      return
    }
    this.state = 'starting'
    this.stopping = false
    this.lastDetail = undefined
    this.lastExit = null
    this.stdoutBuffer = ''
    this.token = this.deps.randomToken()
    const env = {
      ...process.env,
      LW_TOKEN: this.token,
      LW_PORT: '0',
      LW_DATA_PORT: '0',
      LW_INBOUND: '0', // 入站回调端口由 T4 接（这里先不收入站）
      LW_IDLE_MS: String(this.config.idleMs),
      LW_ONLINE_MS: String(Math.max(1000, this.config.startTimeoutMs - 5000)),
      LW_PORTMAPPER: this.config.portmapper ? '1' : '0',
    }
    let child
    try {
      child = this.deps.spawn(binary, ['daemon'], { env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    } catch (error) {
      this.state = 'crashed'
      this.lastDetail = `spawn failed: ${error instanceof Error ? error.message : String(error)}`
      return
    }
    this.child = child
    this.startedAt = this.deps.now()
    child.stdout?.setEncoding?.('utf8')
    child.stderr?.setEncoding?.('utf8')
    child.stdout?.on('data', chunk => this.#onStdout(String(chunk)))
    child.stderr?.on('data', chunk => {
      const text = String(chunk).trim()
      if (text !== '') this.lastDetail = text
    })
    child.on('error', (error) => {
      this.lastDetail = `sidecar process error: ${error instanceof Error ? error.message : String(error)}`
    })
    child.on('exit', (code, signal) => this.#onExit(code, signal))

    const ready = await this.#waitReady(this.config.startTimeoutMs)
    if (!ready.ok) {
      this.lastDetail = ready.detail
      if (this.child === child) {
        try {
          child.kill()
        } catch {
          // ignore
        }
      }
      // 退出处理器可能已经排好了下一次重启；排上了就把状态交给它
      if (this.restartTimer === null) this.state = 'crashed'
      return
    }
    this.httpPort = ready.httpPort
    this.dataPort = ready.dataPort
    this.state = 'running'
    this.lastExit = null
    try {
      const response = await this.deps.httpJson({ port: this.httpPort, path: '/v1/id', token: this.token, timeoutMs: 2000 })
      const body = response?.body ?? {}
      if (typeof body.id === 'string') this.endpoint = { id: body.id, ticket: body.ticket }
    } catch (error) {
      this.lastDetail = `/v1/id failed after READY: ${error instanceof Error ? error.message : String(error)}`
    }
  }

  /** 等 READY 行；超时 / 进程先退都落成 {ok:false}。 */
  #waitReady(timeoutMs) {
    return new Promise((resolve) => {
      if (this.lastExit !== null) {
        resolve({ ok: false, detail: `sidecar exited before READY (code=${this.lastExit.code ?? 'null'} signal=${this.lastExit.signal ?? 'null'})` })
        return
      }
      const timer = this.deps.setTimeout(() => {
        this.#settleReady({ ok: false, detail: `sidecar did not print READY within ${timeoutMs}ms` })
      }, timeoutMs)
      this.readyWaiters.push((result) => {
        this.deps.clearTimeout(timer)
        resolve(result)
      })
    })
  }

  #settleReady(result) {
    const waiters = this.readyWaiters
    this.readyWaiters = []
    for (const waiter of waiters) waiter(result)
  }

  #onStdout(chunk) {
    this.stdoutBuffer += chunk
    let index = this.stdoutBuffer.indexOf('\n')
    while (index >= 0) {
      const line = this.stdoutBuffer.slice(0, index).trim()
      this.stdoutBuffer = this.stdoutBuffer.slice(index + 1)
      if (line.startsWith('READY ')) {
        const fields = {}
        for (const pair of line.slice('READY '.length).split(' ')) {
          const at = pair.indexOf('=')
          if (at > 0) fields[pair.slice(0, at)] = pair.slice(at + 1)
        }
        const httpPort = Number(fields.port)
        const dataPort = Number(fields.dataPort)
        if (Number.isInteger(httpPort) && httpPort > 0) {
          this.#settleReady({ ok: true, httpPort, dataPort: Number.isInteger(dataPort) ? dataPort : 0 })
        }
      }
      index = this.stdoutBuffer.indexOf('\n')
    }
  }

  #onExit(code, signal) {
    this.child = null
    this.httpPort = 0
    this.dataPort = 0
    this.endpoint = null
    this.lastExit = { code, signal }
    this.#settleReady({ ok: false, detail: `sidecar exited before READY (code=${code ?? 'null'} signal=${signal ?? 'null'})` })
    if (this.stopping) {
      this.state = 'stopped'
      return
    }
    if (this.lastDetail === undefined) this.lastDetail = `sidecar exited (code=${code ?? 'null'} signal=${signal ?? 'null'})`
    if (this.restarts >= P2P_MAX_RESTARTS) {
      this.state = 'crashed'
      this.lastDetail = `sidecar crashed ${this.restarts + 1} time(s); restarts exhausted (last: code=${code ?? 'null'} signal=${signal ?? 'null'})`
      return
    }
    const backoff = this.deps.restartBackoffMs ?? P2P_RESTART_BACKOFF_MS
    const delay = backoff[Math.min(this.restarts, backoff.length - 1)] ?? 0
    this.restarts += 1
    this.state = 'restarting'
    this.restartTimer = this.deps.setTimeout(() => {
      this.restartTimer = null
      void this.#start()
    }, delay)
  }
}
