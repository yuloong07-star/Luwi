// lib/luwi-peer-runtime.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// G1-2「跨设备对端」的运行时: 通道 (带令牌的 HTTP 路由)、投递 (把一句话变成那一轮的用户消息)、
// 队列退路 (HTTP 打不通时走会话仓库)、进行中的回答投影 (live/<本机id>/<sessionId>.json 与
// GET /peer/transcript)、以及这一切的读数
//
// 它**只依赖注入面**: DSH 的服务与工厂由 index.mjs 现拿现传 (createUserMessage / sessionController /
// agents / workspaceRegistry / sessions ...), 于是这一份在 lib/ 里仍然零 DSH import —— 与
// lib/luwi-bridge-runner.mjs 同一个形制
//
// 三条硬纪律 (与 docs/DSH-LW-3.0.0-G1-施工单.md 第 14 节逐条对上):
//   1. **令牌是唯一门槛**: 每个 /peer/* 路由都过 sameSecret; 令牌空 = 通道关 (失败关闭, 连路由都不注册),
//      界面/日志/工具结果里只许出现 maskSecret() 的产物
//   2. **只投一句话**: receive 只能把文本投进某一轮的 inbox (followup 起一轮 / steer 插一轮),
//      绝不执行对端给的任何"命令" —— 没有 shell, 没有文件路径, 没有第二个动词
//   3. **本模块不向外抛**: 任何失败都降级成 {ok:false, error}, 且一定带一句能读的原因
//
// 归属方执行那一条 (本机的投影会话里打字 → 只由对端跑) 在 index.mjs 的 agent/pre-step 里落地,
// 投影会话的 id 账由 index.mjs 通过 deps.proxySessions() 交给这里读

import { readdir, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'

import { messageOf } from './errors.mjs'
import {
  ackDirectory,
  buildAck,
  buildMessage,
  inboxDirectory,
  inboxFileName,
  readAcks,
  readInbox,
  rememberId,
  writeJsonFile,
} from './luwi-inbox.mjs'
import { liveDirectory, liveFileName, readLiveSnapshot, LIVE_SNAPSHOT_VERSION } from './luwi-live.mjs'
import { TRANSCRIPT_DIR, readTranscriptEntries, transcriptDir } from './luwi-transcript.mjs'
import { maskSecret, peerHeaders, peerUrlState, sameSecret } from './luwi-peer.mjs'
import { readJsonBody, sendJson } from './panel-routes.mjs'

/** 对端在线判据: 最近一次成功调用在这么久之内 (超过就报 unreachable, 但错误面照旧保留) */
const REACHABLE_WINDOW_MS = 60_000

/** 队列读数里最多列多少条待发 (多了只报计数) */
const MAX_QUEUE_LISTED = 20

/** 一次 transcript 应答最多给多远的历史 (条数由 live 段管, 这里只管字节上限) */
const MAX_TRANSCRIPT_BYTES = 256 * 1024

/**
 * 建对端运行时。
 *
 * @param {object} deps 注入面。
 * @param {object|null} deps.spec - resolvePeerSpec() 的产物 (null = 通道关, 所有路由都不注册)。
 * @param {{enabled: boolean, maxEntries: number, maxChars: number, keepMinutes: number}} deps.live - resolveLiveSpec() 的产物。
 * @param {string} deps.repoDir - 同步工作树根 (队列与投影都落在它顶层)。
 * @param {() => string} deps.ownDeviceId - 本机设备 id (元数据领域就绪后才有值)。
 * @param {() => string} deps.ownDeviceName - 本机设备名 (可能为空)。
 * @param {() => Promise<Array<{deviceId: string, name: string, url: string, updatedAt: string, self: boolean}>>} deps.readPeerCards - 本地镜像里的名片。
 * @param {() => Promise<Array<{name: string, path: string, sessionIds: readonly string[]}>>} deps.listWorkspaces - 本机工作区 (workspaceRegistry.list() 的形状)。
 * @param {() => Promise<Array<{id: string, title: string, cwd: string, workspace: string, running: boolean, updatedAt: number}>>} deps.listSessions - 本机会话摘要 (给 /peer/info 用)。
 * @param {(sessionId: string) => Promise<object|null>} deps.projectSession - 现算一份投影快照 (index.mjs 里接 sessionQuery / live Session)。
 * @param {(sessionId: string) => Promise<object>} deps.deliver - 把一条用户消息投进某一轮 (index.mjs 里接 sessionController.resolveAgent + followup/steer)。
 * @param {(target: object) => Promise<string|null>} deps.resolveTarget - 把 {kind,id,name} 解析成一个会话 id (工作区目标要落到它最近那一场, 没有就新建)。
 * @param {(text: string) => Promise<object>} deps.makeUserMessage - 造用户消息 (index.mjs 传 createUserMessage 的包装)。
 * @param {() => Promise<object>} deps.readState - 读插件自己的账 (delivered / outbox)。
 * @param {(patch: object) => Promise<void>} deps.writeState - 改插件自己的账。
 * @param {() => Promise<object>} deps.pushRepo - 退路队列要走的那一次推 (index.mjs 接 engine.push())。
 * @param {() => string[]} [deps.proxySessions] - 本机"投影会话"的 id 列表 (归属方执行那一条要用)。
 * @param {number} [deps.now] - 取时间 (测试注入)。
 * @param {object} [deps.logger] - 宿主 logger。
 * @param {(message: string) => void} [deps.warn] - 降级用的一行警告。
 * @returns {object} 运行时句柄。
 */
export function createPeerRuntime(deps) {
  const now = deps.now ?? (() => Date.now())
  const warn = deps.warn ?? (() => {})
  const spec = deps.spec
  /** 最近一次对端读数的缓存 (面板与工具都读它) */
  const state = {
    reachable: 'unknown', // unknown | ok | unreachable
    checkedAt: 0,
    lastError: '',
    lastOkAt: 0,
    sent: 0,
    received: 0,
    queued: 0,
    deliveredFromQueue: 0,
    lastSend: null,
    peer: null,
  }
  let disposed = false

  /** 本机设备 id (元数据领域是异步打开的, 没就绪时给空串) */
  const ownDeviceId = () => {
    try {
      return String(deps.ownDeviceId() ?? '')
    } catch {
      return ''
    }
  }

  /** 本机设备名 */
  const ownDeviceName = () => {
    try {
      return String(deps.ownDeviceName() ?? '')
    } catch {
      return ''
    }
  }

  /**
   * 对端的 base URL 与**设备 id**: 地址优先用配置, 没有配置就用名片里最新那张带 http(s) 的
   * (把自己摘掉; 名片是随会话推拉过来的, 所以这一步不额外触网)。
   *
   * 设备 id 一律从**最新那张非自己的名片**取, 与地址从哪儿来无关 —— 它是队列的投递地址
   * (`inbox/<id>/`)。2026-10-20 真机事故: 配置里写着地址时 `deviceId` 恒为空, 于是
   * 手机打不通 (它只监听自己的回环) 的那一档连队列都排不进去, 只回一句
   * "there is no peer device id to queue against" —— 消息就那么没了
   * @returns {Promise<{url: string, source: 'config'|'card'|'none', deviceId: string, deviceName: string}>} 结果。
   */
  async function peerBase() {
    if (spec === null) return { url: '', source: 'none', deviceId: '', deviceName: '' }
    let cards = []
    try {
      cards = await deps.readPeerCards()
    } catch (error) {
      warn(`peer: could not read the peer cards: ${messageOf(error)}`)
    }
    const others = cards
      .filter(card => card.self !== true)
      .sort((left, right) => String(right.updatedAt ?? '').localeCompare(String(left.updatedAt ?? '')))
    // 队列的地址要一个 id; 地址那一半要一张真能拨的 URL —— 两件事分开挑
    const owner = others.find(card => /^https?:\/\//iu.test(String(card.url ?? ''))) ?? others[0]
    const cardUrl = owner === undefined ? '' : String(owner.url ?? '').replace(/\/+$/u, '')
    const url = spec.url !== ''
      ? spec.url
      : (/^https?:\/\//iu.test(cardUrl) ? cardUrl : '')
    return {
      url,
      source: spec.url !== '' ? 'config' : (url === '' ? 'none' : 'card'),
      deviceId: owner === undefined ? '' : String(owner.deviceId ?? ''),
      deviceName: owner === undefined ? '' : String(owner.name ?? ''),
    }
  }

  /**
   * 一次对端调用 (永不抛)。结果进 state, 面板与工具都读同一份。
   * @param {string} path - 以 / 开头的路径。
   * @param {object} [init] - {method, body}。
   * @returns {Promise<{ok: boolean, status: number, body: object|null, error?: string}>} 结果。
   */
  async function call(path, init = {}) {
    const base = await peerBase()
    state.checkedAt = now()
    state.peer = { url: base.url, source: base.source, deviceId: base.deviceId, deviceName: base.deviceName }
    if (base.url === '') {
      state.reachable = 'unreachable'
      state.lastError = 'no peer url: set peer.url, or give this host a publicUrl so its card carries one'
      return { ok: false, status: 0, body: null, error: state.lastError }
    }
    const method = typeof init.method === 'string' ? init.method : 'GET'
    let response
    try {
      response = await fetch(`${base.url}${path}`, {
        method,
        headers: {
          ...peerHeaders(spec.token),
          ...(init.body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        signal: AbortSignal.timeout(spec.sendTimeoutMs),
      })
    } catch (error) {
      state.reachable = 'unreachable'
      state.lastError = messageOf(error)
      return { ok: false, status: 0, body: null, error: state.lastError }
    }
    let body = null
    try {
      body = await response.json()
    } catch {
      body = null
    }
    if (!response.ok || body === null || body.ok !== true) {
      state.reachable = response.status === 0 ? 'unreachable' : 'ok'
      // 401/403 是"令牌不对/对端没开这条通道", 与网络不通分开说 —— 排查方向完全不同
      const why = body !== null && typeof body.error === 'string'
        ? body.error
        : `the peer answered ${response.status} without a JSON body`
      state.lastError = why
      return { ok: false, status: response.status, body, error: why }
    }
    state.reachable = 'ok'
    state.lastOkAt = now()
    state.lastError = ''
    return { ok: true, status: response.status, body }
  }

  /**
   * 发一句话 (面板/工具/浏览器那一侧都走它): 主路 HTTP, 打不通落队列。
   * @param {{text: string, target?: object}} request - 正文与目标。
   * @returns {Promise<object>} {ok, via, sessionId?, error?}。
   */
  async function send(request) {
    const text = typeof request?.text === 'string' ? request.text.trim() : ''
    if (text === '') return { ok: false, error: 'nothing to send: the text is empty' }
    const target = normalizeTarget(request?.target)
    const base = await peerBase()
    if (spec.mode === 'queue') {
      // 只走队列 (2026-10-21 主人选定): 一句话都不拨 —— 从前每条消息先白等一次
      // sendTimeoutMs (20 s) 才落队列, 而这条路本来就是不通的
      if (base.deviceId === '') {
        return { ok: false, via: 'none', error: 'queue-only mode, but there is no peer device id to queue against (no peer card in the mirror yet)' }
      }
      try {
        return await queuedAnswer(base, text, target)
      } catch (error) {
        return { ok: false, via: 'none', error: `queueing failed: ${messageOf(error)}` }
      }
    }
    const http = await call('/luwi-sync/peer/send', {
      method: 'POST',
      body: {
        text,
        target,
        from: { deviceId: ownDeviceId(), name: ownDeviceName() },
      },
    })
    if (http.ok) {
      state.sent += 1
      state.lastSend = { at: now(), via: 'http', sessionId: String(http.body?.sessionId ?? ''), target, textLength: text.length }
      return { ok: true, via: 'http', sessionId: String(http.body?.sessionId ?? ''), running: http.body?.running === true }
    }
    if (base.deviceId === '') {
      // 队列要按设备 id 分目录: 没有对端 id 就没有投递地址 (名片没带上 URL 的那一档)
      return { ok: false, via: 'none', error: `${http.error ?? 'the peer is unreachable'}; and there is no peer device id to queue against` }
    }
    try {
      return { ...(await queuedAnswer(base, text, target)), error: http.error }
    } catch (error) {
      return { ok: false, via: 'none', error: `${http.error ?? 'the peer is unreachable'}; and queueing failed: ${messageOf(error)}` }
    }
  }

  /**
   * 把一句话写进队列表并记读数 (HTTP 与只走队列两条路共用)。
   * @param {{deviceId: string}} base - 对端名片那一份。
   * @param {string} text - 正文。
   * @param {object} target - 规范化后的目标。
   * @returns {Promise<object>} 引擎形状的投递结果。
   */
  async function queuedAnswer(base, text, target) {
    const queued = await enqueue({ text, target, deviceId: base.deviceId })
    state.queued += 1
    state.lastSend = { at: now(), via: 'queue', sessionId: '', target, textLength: text.length, messageId: queued.id }
    // `pushed` 必须带出去: 写进本地工作树却没推上去的话, 对端一次也拉不到 ——
    // 那是"没送到", 不能按成功报 (2026-10-20)
    return {
      ok: true,
      via: 'queue',
      messageId: queued.id,
      // 回执回来时要用它拼投影账的键 (<设备 id>|<对端会话 id>)
      deviceId: base.deviceId,
      pushed: queued.pushed,
      pushedError: queued.pushed ? undefined : (queued.pushError ?? 'the push did not report ok'),
    }
  }

  /**
   * 把一句话写进队列并推一次 (退路)。
   * @param {{text: string, target: object, deviceId: string}} input - 输入。
   * @returns {Promise<{id: string, pushed: boolean, pushError?: string}>} 结果。
   */
  async function enqueue(input) {
    const id = `${now()}-${ownDeviceId().slice(0, 8) || 'local'}-${Math.random().toString(36).slice(2, 8)}`
    const message = buildMessage({
      id,
      from: ownDeviceId(),
      fromName: ownDeviceName(),
      text: input.text,
      target: input.target,
      createdAt: now(),
    })
    await writeJsonFile(join(inboxDirectory(deps.repoDir, input.deviceId), inboxFileName(id)), message)
    let pushed = false
    let pushError
    try {
      const result = await deps.pushRepo()
      pushed = result?.ok === true
      if (!pushed) pushError = typeof result?.error === 'string' ? result.error : 'the push did not report ok'
    } catch (error) {
      pushError = messageOf(error)
    }
    return { id, pushed, ...(pushError === undefined ? {} : { pushError }) }
  }

  /**
   * 消费自己的队列 (对端投过来、随 pull 落到本机的那一批)。pull 之后调一次。
   * @returns {Promise<{delivered: number, failed: number, acks: number, broken: number}>} 结果。
   */
  async function consumeInbox() {
    const id = ownDeviceId()
    if (id === '') return { delivered: 0, failed: 0, acks: 0, broken: 0 }
    const { messages, broken } = await readInbox(inboxDirectory(deps.repoDir, id))
    if (messages.length === 0) return { delivered: 0, failed: 0, acks: 0, broken }
    const ledger = await readLedger()
    let delivered = 0
    let failed = 0
    for (const message of messages) {
      if (ledger.includes(message.id)) {
        // 已经投过: 只在**没有回执**时补一份 (覆盖成功那份既误导读数, 又多一次写)
        await writeAck(message, { sessionId: '', running: false, code: 'ALREADY_DELIVERED' }, 'already delivered', { onlyIfMissing: true })
        continue
      }
      const outcome = await receive(message)
      await writeAck(message, outcome.ok ? outcome : { sessionId: '', running: false, code: outcome.code }, outcome.ok ? undefined : outcome.error)
      if (outcome.ok) {
        delivered += 1
        state.deliveredFromQueue += 1
        await updateLedger(message.id)
      } else {
        failed += 1
      }
    }
    if (delivered > 0) {
      // 回执也要让对端看见: 推一次。**不等它** —— 这一趟正跑在 pull 里, 而 push 要抢引擎
      // 同一把串行锁, await 就是自己等自己 (2026-10-22 实测: pull 停在这一行十几分钟,
      // 之后每次 pull 都排不上)。回执已经落在工作树里, 定时推送会把它带上去
      void deps.pushRepo().catch(error => warn(`peer: pushing the receipts failed: ${messageOf(error)}`))
    }
    return { delivered, failed, acks: messages.length, broken }
  }

  /**
   * 收下对端发来的一句话 (投进它指定的那一场)。
   * @param {object} message - 队列消息或路由体的规范化形态。
   * @returns {Promise<{ok: boolean, sessionId?: string, running?: boolean, error?: string}>} 结果。
   */
  async function receive(message) {
    const text = typeof message?.text === 'string' ? message.text.trim() : ''
    if (text === '') return { ok: false, code: 'EMPTY_MESSAGE', error: 'the peer sent an empty message' }
    let sessionId = ''
    try {
      sessionId = await deps.resolveTarget(normalizeTarget(message?.target))
    } catch (error) {
      return { ok: false, code: 'NO_TARGET', error: `could not resolve the target: ${messageOf(error)}` }
    }
    if (typeof sessionId !== 'string' || sessionId === '') {
      return { ok: false, code: 'NO_TARGET', error: 'could not resolve the target to a session on this host' }
    }
    try {
      const userMessage = await deps.makeUserMessage(text)
      const outcome = await deps.deliver(sessionId, userMessage)
      state.received += 1
      return { ok: true, sessionId, running: outcome?.running === true }
    } catch (error) {
      return { ok: false, code: 'DELIVERY_FAILED', sessionId, error: messageOf(error) }
    }
  }

  /**
   * 向对端要某一场的投影快照 —— 投影会话靠它把"对端的回答"折进本地 (只取 user 与 assistant 文本)。
   *
   * 与下面的 `transcript()` 正好是**两个方向**: 那一个回答对端的提问 (给出去), 这一个去问对端
   * @param {string} sessionId - 对端那一场的会话 id。
   * @returns {Promise<{ok: boolean, snapshot?: object, error?: string}>} 结果。
   */
  async function peerTranscript(sessionId) {
    if (typeof sessionId !== 'string' || sessionId === '') return { ok: false, error: 'sessionId is required' }
    const answer = await call(`/luwi-sync/peer/transcript?sessionId=${encodeURIComponent(sessionId)}`)
    if (!answer.ok) return { ok: false, error: answer.error }
    // 对端写的东西是不可信输入: 过一遍闸再交出去
    const snapshot = readLiveSnapshot(answer.body?.snapshot)
    return snapshot === null ? { ok: false, error: 'the peer answered without a readable snapshot' } : { ok: true, snapshot }
  }

  /**
   * 仓库里"别人写的"投影快照所在的设备目录名 (不含自己那一份)。
   * @returns {Promise<string[]>} 设备 id 列表 (最近写过的先排)。
   */
  async function liveDeviceIds() {
    const own = ownDeviceId()
    const names = await listNames(join(deps.repoDir, 'live'))
    return names.filter(name => name !== own).sort().reverse()
  }

  /**
   * **退路**: 实时通道 (HTTP) 拨不通时, 从仓库里读对端写的那一份投影快照。
   *
   * 每一台设备都会把"只要回答"的那份写进 `<repoDir>/live/<自己的 id>/<会话>.json`
   * (`writeProjection`, 每个 turn/end 与 assistant/message 之后 400 ms 去抖写一次), 随 pull
   * 落到对端的工作树里。手机只监听自己的回环时 (拨不通), 这是唯一能拿到回答的路 ——
   * 2026-10-20 之前只写了没人读, 于是"对端回答回到本机"在拨不通的设备上永远不成立
   * @param {string} sessionId - 对端那一场的会话 id。
   * @returns {Promise<{ok: boolean, snapshot?: object, via?: string, device?: string, error?: string}>} 结果。
   */
  async function liveTranscript(sessionId) {
    if (typeof sessionId !== 'string' || sessionId === '') return { ok: false, error: 'sessionId is required' }
    const file = `${liveFileName(sessionId)}.json`
    for (const device of await liveDeviceIds()) {
      let text
      try {
        text = await readFile(join(liveDirectory(deps.repoDir, device), file), 'utf8')
      } catch {
        continue
      }
      let parsed
      try {
        parsed = JSON.parse(text)
      } catch {
        continue
      }
      const snapshot = readLiveSnapshot(parsed)
      if (snapshot !== null) return { ok: true, via: 'repo', device, snapshot }
    }
    return { ok: false, error: `no repository copy of ${sessionId} (the peer has not written one yet)` }
  }

  /**
   * 仓库里那本**对话账** (`conv/<对端 id>/<会话>/`) —— 2026-10-22, 方案文档 §5.4。
   *
   * 与 [liveTranscript] 的分工: 那一份是滚动窗口 (最近 40 条), 这一本只追加、装整本对话。
   * 本机投影的游标落在窗口之外时 (对端离线了几轮), 中间那段只有这里补得回来
   * @param {string} sessionId - 对端那一场的会话 id。
   * @param {number} [afterSeq] - 只要 `seq` 大于它的条目。
   * @returns {Promise<{ok: boolean, entries: Array<object>, meta?: object, device?: string, error?: string}>} 结果。
   */
  async function repoTranscript(sessionId, afterSeq = 0) {
    if (typeof sessionId !== 'string' || sessionId === '') return { ok: false, entries: [], error: 'sessionId is required' }
    const own = ownDeviceId()
    let devices = []
    try {
      devices = (await listNames(join(deps.repoDir, TRANSCRIPT_DIR))).filter(name => name !== own)
    } catch {
      devices = []
    }
    for (const device of devices) {
      const answer = await readTranscriptEntries(transcriptDir(deps.repoDir, device, sessionId), { afterSeq })
      if (answer.ok !== true) continue
      // 目录名可以伪造, meta 里的会话 id 才是这本账的身份证
      if (answer.meta !== null && typeof answer.meta.sessionId === 'string'
        && answer.meta.sessionId !== '' && answer.meta.sessionId !== sessionId) continue
      if (answer.entries.length === 0) continue
      return { ok: true, entries: answer.entries, meta: answer.meta, device }
    }
    return { ok: false, entries: [], error: `no repository transcript of ${sessionId}` }
  }

  /**
   * 对端对本机排进队列那些话的回执 (`acks/<本机 id>/<消息 id>.json`)。
   * 队列这条路是"对端下次拉取才收到", 回执里带回来的会话 id 是本机把那一场绑成投影的唯一线索。
   * @returns {Promise<Map<string, {sessionId: string, running: boolean, at: number, error?: string}>>} 消息 id → 回执。
   */
  async function outboundAcks() {
    const id = ownDeviceId()
    if (id === '') return new Map()
    try {
      return await readAcks(ackDirectory(deps.repoDir, id))
    } catch (error) {
      warn(`peer: reading the outbound receipts failed: ${messageOf(error)}`)
      return new Map()
    }
  }

  /**
   * 现算一份投影快照 (对端要的那一份"只要回答")。
   * @param {string} sessionId - 会话 id。
   * @returns {Promise<object>} {ok, snapshot?|error}。
   */
  async function transcript(sessionId) {
    if (!deps.live.enabled) return { ok: false, error: 'the live projection is off on this host (live.enabled: false)' }
    if (typeof sessionId !== 'string' || sessionId === '') return { ok: false, error: 'sessionId is required' }
    try {
      const projected = await deps.projectSession(sessionId)
      const snapshot = projected?.snapshot ?? null
      if (snapshot === null) return { ok: false, error: `no such session: ${sessionId}` }
      const text = JSON.stringify(snapshot)
      if (text.length > MAX_TRANSCRIPT_BYTES) {
        return { ok: true, snapshot: { ...snapshot, entries: snapshot.entries.slice(-5), truncated: true } }
      }
      return { ok: true, snapshot }
    } catch (error) {
      return { ok: false, error: messageOf(error) }
    }
  }

  /**
   * 向对端要一份它的 info (工作区 / 会话 / 正在跑的那几场)。影子工作区与投影会话靠它刷新。
   * @returns {Promise<{ok: boolean, info?: object, error?: string}>} 结果。
   */
  async function peerInfo() {
    // 只走队列时这一条注定失败 (而且要先等满 sendTimeoutMs): 直接给一句原因
    if (spec.mode === 'queue') return { ok: false, error: 'peer.mode is queue: peer info needs the http channel' }
    const answer = await call('/luwi-sync/peer/info')
    if (!answer.ok) return { ok: false, error: answer.error }
    return { ok: true, info: answer.body }
  }

  /**
   * 向对端要一份"进行中的回答" (只读): 先问它的 info, 再把它正在跑的那几场逐场取一份投影。
   * 这里最多取 3 场 —— 面板与模型要的是"现在在说什么", 不是把对端整棵会话树搬过来
   * @returns {Promise<object>} {ok, device?, workspaces?, sessions?, live: Array<{sessionId, title, running, draft, entries}>, error?}。
   */
  async function peerLive() {
    if (spec.mode === 'queue') return { ok: false, error: 'peer.mode is queue: live answers need the http channel', live: [] }
    const answer = await call('/luwi-sync/peer/info')
    if (!answer.ok) return { ok: false, error: answer.error, live: [] }
    const info = answer.body ?? {}
    const running = Array.isArray(info.live) ? info.live.slice(0, 3) : []
    const live = []
    for (const entry of running) {
      const sessionId = String(entry?.sessionId ?? '')
      if (sessionId === '') continue
      const snapshot = await call(`/luwi-sync/peer/transcript?sessionId=${encodeURIComponent(sessionId)}`)
      const value = snapshot.ok ? snapshot.body?.snapshot : null
      live.push({
        sessionId,
        title: String(entry?.title ?? value?.title ?? ''),
        workspace: String(entry?.workspace ?? ''),
        running: true,
        draft: value?.draft ?? null,
        entries: Array.isArray(value?.entries) ? value.entries.slice(-8) : [],
        error: snapshot.ok ? undefined : snapshot.error,
      })
    }
    return {
      ok: true,
      device: info.device ?? { id: '', name: '' },
      workspaces: Array.isArray(info.workspaces) ? info.workspaces : [],
      sessions: Array.isArray(info.sessions) ? info.sessions : [],
      live,
    }
  }

  /**
   * 本机的对外读数 (/peer/info 的应答体)。
   * @returns {Promise<object>} 设备、工作区、会话与进行中的回答。
   */
  async function info() {
    let workspaces = []
    try {
      workspaces = await deps.listWorkspaces()
    } catch (error) {
      warn(`peer: listing workspaces failed: ${messageOf(error)}`)
    }
    let sessions = []
    try {
      sessions = await deps.listSessions()
    } catch (error) {
      warn(`peer: listing sessions failed: ${messageOf(error)}`)
    }
    const live = []
    if (deps.live.enabled) {
      for (const session of sessions.filter(entry => entry.running === true).slice(0, 8)) {
        live.push({ sessionId: session.id, title: session.title, workspace: session.workspace, running: true })
      }
    }
    return {
      ok: true,
      v: 1,
      device: { id: ownDeviceId(), name: ownDeviceName() },
      // 投影条目的语义版本: v2 起 entries 里才有已经落定的回答。对端拿它决定要不要把手里那场
      // 旧口径折出来的投影重做一场 (重做前得先知道对面到底报哪一版, 免得每一轮都白问一次)
      entryVersion: LIVE_SNAPSHOT_VERSION,
      workspaces: workspaces.map(workspace => ({
        name: workspace.title ?? workspace.name ?? '',
        path: workspace.path ?? '',
        sessions: Array.isArray(workspace.sessionIds) ? workspace.sessionIds.length : 0,
      })),
      sessions,
      live,
    }
  }

  /**
   * 面板/工具读的对端状态。
   * @returns {Promise<object>} 读数 (令牌只出 maskSecret 的产物)。
   */
  async function status() {
    const base = spec === null
      ? { url: '', source: 'none', deviceId: '', deviceName: '' }
      : await peerBase()
    const ledger = await readLedger()
    const queue = await readQueue()
    return {
      enabled: spec !== null,
      mode: spec === null ? 'off' : spec.mode,
      listen: spec !== null && spec.listen,
      proxy: spec !== null && spec.proxy,
      token: maskSecret(spec === null ? '' : spec.token),
      url: peerUrlState(base.url),
      urlSource: base.source,
      device: { id: base.deviceId, name: base.deviceName },
      reachable: spec === null ? 'off' : state.reachable,
      checkedAt: state.checkedAt,
      lastOkAt: state.lastOkAt,
      lastError: state.lastError,
      counters: { sent: state.sent, received: state.received, queued: state.queued, deliveredFromQueue: state.deliveredFromQueue },
      lastSend: state.lastSend,
      queue,
      delivered: ledger.length,
      live: deps.live,
    }
  }

  /** 队列读数 (本机发出去、还没被对端确认的那一批) */
  async function readQueue() {
    const base = await peerBase()
    if (base.deviceId === '') return { pending: 0, sent: [], acks: 0 }
    const outboxDirectory = inboxDirectory(deps.repoDir, base.deviceId)
    const names = await listNames(outboxDirectory)
    // 回执按"寄件方 id"分目录: 我们发出去的消息, 对端把回执写进 **本机 id** 那一格
    // (writeAck 用的是 message.from)。从前这里拿 base.deviceId (对端 id) 去读, 读到的
    // 是"对端发给我们的消息的回执", 与自己发出去的那一批对不上 —— 于是 sync_peer 的
    // queue 读数永远报着早就送达的那几条 (2026-10-21 实测: 5 条全是已送达)
    const acks = await readAcks(ackDirectory(deps.repoDir, ownDeviceId()))
    const pending = names
      .map(name => name.replace(/\.json$/u, ''))
      .filter(name => !acks.has(name))
    return { pending: pending.length, sent: pending.slice(-MAX_QUEUE_LISTED), acks: acks.size }
  }

  /** 读"已经投过"的账 (插件自己的 storage 域) */
  async function readLedger() {
    try {
      const stored = await deps.readState()
      return Array.isArray(stored?.delivered) ? stored.delivered.filter(entry => typeof entry === 'string') : []
    } catch (error) {
      warn(`peer: reading the delivered ledger failed: ${messageOf(error)}`)
      return []
    }
  }

  /** 记一笔"已经投过" */
  async function updateLedger(id) {
    try {
      const current = await readLedger()
      await deps.writeState({ delivered: rememberId(current, id) })
    } catch (error) {
      warn(`peer: writing the delivered ledger failed: ${messageOf(error)}`)
    }
  }

  /**
   * 写一份回执 (失败原因带机器可读的 `code`, 面板与日志按它分类)。
   * @param {object} message - 队列消息。
   * @param {object} outcome - {sessionId, running, code}。
   * @param {string} [error] - 失败原因。
   * @param {{onlyIfMissing?: boolean}} [options] - `onlyIfMissing`: 已经有一份就不再动它
   *   (补回执那一支用 —— 覆盖已经成功的回执既会误导读数, 又会每轮写一次长出 fork)。
   */
  async function writeAck(message, outcome, error, options = {}) {
    try {
      const from = typeof message.from === 'string' && message.from !== '' ? message.from : 'unknown'
      const code = typeof outcome.code === 'string' ? outcome.code : undefined
      const target = join(ackDirectory(deps.repoDir, from), inboxFileName(message.id))
      if (options.onlyIfMissing === true) {
        try {
          await readFile(target)
          return
        } catch {
          // 没有 → 照写
        }
      }
      // 内容一样的回执就别重写: `at` 每趟都变, 两端每次都判 DIVERGED, 每轮 pull 长出一个 fork
      // (2026-10-21 清理时那 8 个 inbox-ack 的 fork 全是"已投过, 补一份回执"这一支刷出来的)
      try {
        const current = JSON.parse(await readFile(target, 'utf8'))
        if (
          current !== null && typeof current === 'object' && !Array.isArray(current)
          && current.id === message.id
          && String(current.sessionId ?? '') === String(outcome.sessionId ?? '')
          && (current.running === true) === (outcome.running === true)
          && String(current.error ?? '') === String(error ?? '')
          && String(current.code ?? '') === String(code ?? '')
        ) {
          return
        }
      } catch {
        // 不在 / 不是 JSON → 照写
      }
      await writeJsonFile(target, buildAck({
        id: message.id,
        sessionId: outcome.sessionId,
        running: outcome.running === true,
        at: now(),
        error,
        code,
      }))
    } catch (thrown) {
      warn(`peer: writing a receipt failed: ${messageOf(thrown)}`)
    }
  }

  /**
   * 写一份投影文件 (<repoDir>/live/<本机id>/<sessionId>.json), 供 git 退路那一侧读。
   * @param {string} sessionId - 会话 id。
   * @returns {Promise<{written: boolean, error?: string}>} 结果。
   */
  async function writeProjection(sessionId) {
    if (!deps.live.enabled || spec === null) return { written: false }
    const id = ownDeviceId()
    if (id === '') return { written: false, error: 'no device id yet' }
    try {
      const projected = await deps.projectSession(sessionId)
      const snapshot = projected?.snapshot ?? null
      if (snapshot === null) return { written: false, error: `no such session: ${sessionId}` }
      await writeJsonFile(join(liveDirectory(deps.repoDir, id), `${liveFileName(sessionId)}.json`), snapshot)
      // 对话账 (conv/) 跟 live 快照同一趟写: 窗口之外的历史只有这一本留得住 (2026-10-22)
      if (typeof deps.writeTranscript === 'function') {
        const written = await deps.writeTranscript(sessionId, projected)
        if (Number.isFinite(written?.appended) && written.appended > 0) {
          deps.logger?.info?.(`peer: transcript ${sessionId} +${written.appended} entr(ies) (${written.entries ?? '?'} total)`)
        }
      }
      return { written: true }
    } catch (error) {
      return { written: false, error: messageOf(error) }
    }
  }

  /**
   * 清掉过期的投影文件 (超过 live.keepMinutes 的)。
   * @returns {Promise<number>} 删掉几份。
   */
  async function sweepProjections() {
    const id = ownDeviceId()
    if (id === '' || !deps.live.enabled) return 0
    const directory = liveDirectory(deps.repoDir, id)
    const cutoff = now() - deps.live.keepMinutes * 60_000
    let removed = 0
    for (const name of await listNames(directory)) {
      if (!name.endsWith('.json')) continue
      try {
        const parsed = JSON.parse(await readFile(join(directory, name), 'utf8'))
        const updatedAt = Number.isFinite(parsed?.updatedAt) ? Number(parsed.updatedAt) : 0
        if (updatedAt !== 0 && updatedAt < cutoff) {
          await rm(join(directory, name), { force: true })
          removed += 1
        }
      } catch {
        // 读不懂的投影文件不动它: 可能是对端刚写的, 也可能是人放进去的
      }
    }
    return removed
  }

  /**
   * 路由表: 四条对端面 + 一条本机面板面。令牌空 (spec 为 null) 时**一条都不注册**。
   * @returns {Array<{kind: string, path: string, handler: Function}>} 路由。
   */
  function routes() {
    if (spec === null) return []
    return [
      {
        kind: 'exact',
        path: '/luwi-sync/peer/info',
        handler: async (req, res) => {
          const gate = authorize(req)
          if (gate !== null) return sendJson(res, gate.status, gate.body)
          if (req.method !== 'GET') return sendJson(res, 405, { ok: false, error: 'peer/info is GET-only' })
          try {
            sendJson(res, 200, await info())
          } catch (error) {
            sendJson(res, 200, { ok: false, error: messageOf(error) })
          }
        },
      },
      {
        kind: 'exact',
        path: '/luwi-sync/peer/transcript',
        handler: async (req, res) => {
          const gate = authorize(req)
          if (gate !== null) return sendJson(res, gate.status, gate.body)
          const sessionId = queryOf(req).get('sessionId') ?? ''
          const answer = await transcript(sessionId)
          sendJson(res, answer.ok ? 200 : 404, answer.ok ? { ok: true, snapshot: answer.snapshot } : { ok: false, error: answer.error })
        },
      },
      {
        kind: 'exact',
        path: '/luwi-sync/peer/send',
        handler: async (req, res) => {
          const gate = authorize(req)
          if (gate !== null) return sendJson(res, gate.status, gate.body)
          if (req.method !== 'POST') return sendJson(res, 405, { ok: false, error: 'peer/send is POST-only' })
          let body
          try {
            body = await readJsonBody(req, 8192)
          } catch (error) {
            return sendJson(res, 400, { ok: false, error: messageOf(error) })
          }
          const answer = await receive(body)
          sendJson(res, answer.ok ? 200 : 400, answer)
        },
      },
      {
        kind: 'exact',
        path: '/luwi-sync/peer-live',
        handler: async (req, res) => {
          // 本机界面读对端进行中的回答: 同源那一路, 令牌留在宿主里
          if (req.method !== 'GET') return sendJson(res, 405, { ok: false, error: '/luwi-sync/peer-live is GET-only' })
          try {
            sendJson(res, 200, await peerLive())
          } catch (error) {
            sendJson(res, 200, { ok: false, error: messageOf(error), live: [] })
          }
        },
      },
      {
        kind: 'exact',
        path: '/luwi-sync/send',
        handler: async (req, res) => {
          // 本机面板发起的发送: 同源 (浏览器) 那一侧, 令牌不出宿主
          if (req.method !== 'POST') return sendJson(res, 405, { ok: false, error: '/luwi-sync/send is POST-only' })
          let body
          try {
            body = await readJsonBody(req, 8192)
          } catch (error) {
            return sendJson(res, 400, { ok: false, error: messageOf(error) })
          }
          const answer = await send(body)
          sendJson(res, answer.ok ? 200 : 502, answer)
        },
      },
    ]
  }

  /**
   * 对端调用的门槛: 通道开了没有 / 令牌对不对 / 方法对不对。
   * @param {import('node:http').IncomingMessage} req - 请求。
   * @returns {{status: number, body: object}|null} 拒绝时给应答; 放行 null。
   */
  function authorize(req) {
    if (spec === null) return { status: 403, body: { ok: false, error: 'the peer channel is off on this host' } }
    if (!spec.listen) return { status: 403, body: { ok: false, error: 'this host does not accept peer calls (peer.listen: false)' } }
    const header = req.headers?.authorization
    const bearer = typeof header === 'string' && header.toLowerCase().startsWith('bearer ')
      ? header.slice(7).trim()
      : ''
    if (!sameSecret(bearer, spec.token)) {
      state.lastError = 'a peer call was refused: the bearer token did not match'
      return { status: 401, body: { ok: false, error: 'the shared token did not match' } }
    }
    return null
  }

  return {
    send,
    receive,
    info,
    peerInfo,
    peerLive,
    peerTranscript,
    liveTranscript,
    // 仓库里的对话账 (conv/): 补 live 窗口之外的那一段历史
    repoTranscript,
    outboundAcks,
    transcript,
    status,
    consumeInbox,
    writeProjection,
    sweepProjections,
    routes,
    /** 通道开着没有 (index.mjs 决定要不要挂路由与轮询) */
    get enabled() {
      return spec !== null
    },
    get spec() {
      return spec
    },
    dispose() {
      disposed = true
    },
    /** 给测试用: 现在这一份还行不行 */
    get disposed() {
      return disposed
    },
  }
}

/**
 * 目标规范化: 只认三种目标, 别的字段一律丢掉 —— 对端送过来的 JSON 是不可信输入。
 *
 * `new` 是 Luwi 加的那一种 (2026-10-14): 名下的工作区里**新开一场**。旧对端认不出它时会被折成
 * `session` + 空 id, 于是 receive() 回一条"解析不到会话"的明确错 (失败关闭), 不会悄悄降级成本地执行
 * @param {unknown} target - 客户端给的目标。
 * @returns {{kind: 'session'|'workspace'|'new', id: string, name: string}} 规范化结果。
 */
export function normalizeTarget(target) {
  const raw = target !== null && typeof target === 'object' ? /** @type {Record<string, unknown>} */ (target) : {}
  const kind = raw.kind === 'workspace' ? 'workspace' : raw.kind === 'new' ? 'new' : 'session'
  return {
    kind,
    id: typeof raw.id === 'string' ? raw.id : '',
    name: typeof raw.name === 'string' ? raw.name : '',
  }
}

/** 请求路径上的查询表 */
function queryOf(req) {
  try {
    return new URL(req.url ?? '/', 'http://localhost').searchParams
  } catch {
    return new URLSearchParams()
  }
}

/** 列目录里的文件名 (目录不在 = 空, 绝不抛) */
async function listNames(directory) {
  try {
    return (await readdir(directory)).sort()
  } catch {
    return []
  }
}
