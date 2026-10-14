// test/luwi-peer-proxy-wiring.test.mjs — Luwi 加 (G1-2) 的影子工作区、投影会话与归属方那条闸。
//
// 前面的单测测的是折叠算术; 这一份测的是"真装配里它跑起来"这件事:
//   1. 对端报的工作区在本机变成一个影子目录 + 注册好的工作区, 同名时标题写 名字（设备名）
//   2. 对端那几场在本机变成投影会话 (cwd 在影子里 → 进那个分组), 标题照对端那一场
//   3. 对端的文本条目被折成本地事件追加进去 (turn/start + user/message … assistant/message + turn/end),
//      重复刷不重复落 (幂等), 新条目照落
//   4. 「指令只能由归属方执行」: 在投影会话里打字 → 转发给对端 + 本地返回 messages: [] (不调模型);
//      别的会话照旧走 next()
// @module luwi-dsh-sync/test/luwi-peer-proxy-wiring.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as plugin from '../index.mjs'

const TOKEN = 'p'.repeat(24)
const PEER_URL = 'http://peer.test:3080'

/** 一棵临时树 */
function makeRoot(prefix) {
  return mkdtempSync(join(tmpdir(), `${prefix}-`))
}

/** 一场假会话 (append 收集事件) */
function fakeSession(id) {
  return {
    id,
    seq: 0,
    header: { cwd: '/w' },
    appended: [],
    append(type, data, opts) {
      this.seq += 1
      this.appended.push({ type, data, surface: opts?.surfaceOp === 'append' })
    },
    deriveMessages: () => [],
  }
}

/**
 * 真装配需要的假 ctx: 必需服务齐, 可选服务按 ctx.get 给。
 * @param {object} [options] 选项: {localWorkspaces, peerSessions}
 * @returns {object} 句柄
 */
function makeWiredContext(options = {}) {
  const commands = []
  const routes = []
  const tools = []
  const warns = []
  const errors = []
  const disposers = []
  const handlers = new Map()
  const sessions = new Map()
  const workspaces = new Map()
  const created = []
  const renamed = []
  const archivedSessions = []
  const table = { get: async () => undefined, put: async () => {} }
  const domain = { table: () => Promise.resolve(table), close: async () => {} }
  const registry = {
    list: () => [...workspaces.values()].map(entry => ({ id: entry.id, title: entry.title, path: entry.path, sessionIds: entry.sessionIds })),
    get: id => workspaces.get(id),
    create: async (dir, label) => {
      const existing = [...workspaces.values()].find(entry => entry.path === dir)
      if (existing !== undefined) return existing
      const entity = {
        id: `ws-${workspaces.size + 1}`,
        title: label,
        path: dir,
        sessionIds: [],
        setTitle: async (next) => { entity.title = next },
      }
      workspaces.set(entity.id, entity)
      return entity
    },
    archiveSession: async (id) => { archivedSessions.push(id) },
  }
  for (const [index, workspace] of (options.localWorkspaces ?? []).entries()) {
    workspaces.set(`local-${index + 1}`, { id: `local-${index + 1}`, title: workspace, path: `/local/${workspace}`, sessionIds: [], setTitle: async () => {} })
  }
  const controller = {
    list: async () => ({ items: [] }),
    create: async (request) => {
      const id = `proxy-${created.length + 1}`
      created.push({ id, request })
      sessions.set(id, fakeSession(id))
      return { sessionId: id }
    },
    rename: async (request) => { renamed.push(request); return { title: request.title } },
    resolveAgent: async (id) => ({ agent: { id, status: 'idle', session: sessions.get(id) } }),
  }
  const optionals = {
    sessionController: controller,
    agents: { get: () => undefined },
    sessionQuery: { readSurface: async () => ({ events: [] }) },
    sessionProjections: { stateOf: () => undefined },
    workspaceRegistry: registry,
  }
  const ctx = {
    logger: () => ({
      warn: (message) => { warns.push(String(message)) },
      info: () => {},
      error: (message) => { errors.push(String(message)) },
    }),
    effect: (callback) => {
      const disposer = callback()
      disposers.push(typeof disposer === 'function' ? disposer : () => {})
      return disposer
    },
    on: (name, handler) => {
      handlers.set(name, handler)
      return () => { handlers.delete(name) }
    },
    inject: (_names, callback) => { callback(ctx) },
    get: (name) => optionals[name],
    commands: { register: (definition) => { commands.push(definition) } },
    storageDomain: { open: () => Promise.resolve(domain) },
    subprocess: undefined,
    sessions: { get: id => sessions.get(id), list: () => [...sessions.values()], flush: async () => {} },
    tools: { register: (definition) => { tools.push(definition) } },
    webServer: { register: (route) => { routes.push(route); return () => {} } },
    ...optionals,
  }
  return { ctx, commands, routes, tools, warns, errors, disposers, handlers, sessions, workspaces, created, renamed, archivedSessions }
}

/** 一个能当 req 用的最小对象 */
function makeReq({ method = 'GET', url = '/', body } = {}) {
  const chunks = body === undefined ? [] : [Buffer.from(JSON.stringify(body))]
  return {
    method,
    url,
    headers: {},
    async *[Symbol.asyncIterator]() { for (const chunk of chunks) yield chunk },
  }
}

/** 一个能当 res 用的最小对象 */
function makeRes() {
  const record = { status: 0, body: null }
  return {
    record,
    setHeader() {},
    set statusCode(value) { record.status = value },
    get statusCode() { return record.status },
    end(payload) { record.body = payload === undefined ? null : JSON.parse(payload) },
  }
}

/** 把 fetch 换成"对端那一侧"的桩: info 与 transcript 两张表 */
function stubPeer(peer) {
  const original = globalThis.fetch
  const posts = []
  globalThis.fetch = async (url, init = {}) => {
    const target = new URL(String(url))
    if (init.method === 'POST' && target.pathname === '/luwi-sync/peer/send') {
      const body = JSON.parse(String(init.body))
      posts.push(body)
      // kind:'new' 那一档: 对端**新开一场**, 回执里带回来的 id 才是本机要绑成投影的那一场
      const opened = body?.target?.kind === 'new'
      return { ok: true, status: 200, json: async () => ({ ok: true, sessionId: opened ? 'session-fresh-1' : 'session-phone-1', running: false }) }
    }
    if (target.pathname === '/luwi-sync/peer/info') {
      return { ok: true, status: 200, json: async () => peer.info() }
    }
    if (target.pathname === '/luwi-sync/peer/transcript') {
      const sessionId = target.searchParams.get('sessionId')
      const value = peer.transcript(sessionId)
      return value === null
        ? { ok: false, status: 404, json: async () => ({ ok: false, error: 'no such session' }) }
        : { ok: true, status: 200, json: async () => ({ ok: true, snapshot: value }) }
    }
    return { ok: false, status: 404, json: async () => ({ ok: false, error: 'no route' }) }
  }
  return { posts, restore: () => { globalThis.fetch = original } }
}

/**
 * 夹具里"对端那一场"的时间戳: 取一个**固定的未来值**。
 *
 * 两个理由: ① "从相连时才开始同步, 过往不计" —— 对端报的会话要有相连之后的活动才会做成投影;
 * ② 跨两次装配 (重启那一条测试) 必须是同一个值, 否则第二次会被当成"刚动过"重折一遍
 */
const PEER_UPDATED_AT = Date.now() + 3_600_000

/** 对端那一侧的读数 (可由测试改写) */
function makePeer() {
  const state = {
    updatedAt: PEER_UPDATED_AT,
    entries: [
      { seq: 1, role: 'user', text: '你好' },
      { seq: 2, role: 'assistant', text: '好的' },
    ],
  }
  return {
    state,
    info: () => ({
      ok: true,
      v: 1,
      device: { id: 'dev-peer', name: 'Luwi 手机' },
      workspaces: [{ name: 'DSH', path: '/storage/emulated/0/DSH', sessions: 1 }],
      sessions: [{ id: 'session-phone-1', title: '修一下登录', cwd: '/storage/emulated/0/DSH', workspace: 'DSH', running: false, updatedAt: state.updatedAt }],
      live: [],
    }),
    transcript: sessionId => (sessionId === 'session-phone-1'
      ? { v: 1, deviceId: 'dev-peer', deviceName: 'Luwi 手机', sessionId, title: '修一下登录', cwd: '/storage/emulated/0/DSH', running: false, updatedAt: state.updatedAt, truncated: false, entries: state.entries, draft: null }
      : null),
  }
}

/** 装一次 (在临时 DSH_HOME 下; 结束后自己清理) */
async function withMount({ config, localWorkspaces, state, handler }) {
  const root = makeRoot('lw-peer-proxy')
  const previous = process.env.DSH_HOME
  process.env.DSH_HOME = root
  delete globalThis[plugin.MOUNT_KEY]
  plugin.mountState.mounted = false
  const wired = makeWiredContext({ localWorkspaces })
  // state 给了就当成"上一次运行留下的账本" (storage 里那一份), 用来演跨重启的那几档
  if (state !== undefined) {
    const table = { get: async () => state, put: async (_key, value) => { Object.assign(state, value) } }
    wired.ctx.storageDomain = { open: () => Promise.resolve({ table: () => Promise.resolve(table), close: async () => {} }) }
  }
  const peer = makePeer()
  const stub = stubPeer(peer)
  const lines = []
  const originalLog = console.log
  console.log = (...args) => { lines.push(args.join(' ')) }
  try {
    plugin.apply(wired.ctx, {
      enabled: true,
      sessionRoot: join(root, 'sessions'),
      repoDir: join(root, 'repo'),
      registerTools: true,
      peer: { token: TOKEN, url: PEER_URL },
      ...(config ?? {}),
    })
    if (plugin.mountState.mounted !== true) {
      assert.ok(wired.errors.length > 0, 'a plugin that did not mount has to say why')
      return
    }
    await handler({ wired, peer, stub, root, lines })
  } finally {
    console.log = originalLog
    stub.restore()
    for (const dispose of wired.disposers) dispose()
    delete globalThis[plugin.MOUNT_KEY]
    plugin.mountState.mounted = false
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    rmSync(root, { recursive: true, force: true })
  }
}

// ---------------------------------------------------------------------------
// 1 · 影子工作区 + 投影会话
// ---------------------------------------------------------------------------

test('the peer workspace becomes a local shadow group and its session becomes a projection session', async () => {
  await withMount({
    localWorkspaces: ['DSH'],
    handler: async ({ wired, root }) => {
      const result = await plugin.mountState.peerRefresh()
      assert.equal(result.ok, true, JSON.stringify(result))
      assert.equal(result.created, 1)
      assert.equal(result.opened, 1)

      const shadows = [...wired.workspaces.values()]
      const shadow = shadows.find(entry => entry.path.startsWith(root))
      assert.ok(shadow !== undefined, 'the shadow workspace was registered')
      assert.equal(shadow.title, 'DSH（Luwi 手机）', '对端那一侧永远带设备名 (裸名字 = 本机的)')
      assert.ok(existsSync(shadow.path), 'the shadow directory exists on disk')

      assert.deepEqual(wired.created, [{ id: 'proxy-1', request: { workspaceId: shadow.id } }])
      assert.deepEqual(wired.renamed, [{ sessionId: 'proxy-1', title: '修一下登录' }])
      const proxy = wired.sessions.get('proxy-1')
      assert.deepEqual(proxy.appended.map(event => event.type), ['turn/start', 'user/message', 'assistant/message', 'turn/end'])
      assert.equal(proxy.appended[1].surface, true)
      assert.equal(proxy.appended[1].data.content[0].text, '你好')
      assert.equal(proxy.appended[2].data.message.content[0].text, '好的')
      assert.equal(proxy.appended[2].surface, true)

      // 同一批再刷一次: revision 没变, 一条都不重复落
      const again = await plugin.mountState.peerRefresh()
      assert.equal(again.fetched, 0)
      assert.equal(proxy.appended.length, 4)
    },
  })
})

test('a fresh revision appends only the new entries, and the view reports the ledger', async () => {
  await withMount({
    localWorkspaces: ['别的名字'],
    handler: async ({ wired, peer, root }) => {
      await plugin.mountState.peerRefresh()
      const shadow = [...wired.workspaces.values()].find(entry => entry.path.startsWith(root))
      assert.equal(shadow.title, 'DSH（Luwi 手机）', '名字只有对端用也一样带设备名, 好一眼看出它是对面那台机器上的')
      const proxy = wired.sessions.get('proxy-1')
      assert.equal(proxy.appended.length, 4)

      peer.state.entries = [...peer.state.entries, { seq: 3, role: 'user', text: '再问一句' }, { seq: 4, role: 'assistant', text: '再答一句' }]
      peer.state.updatedAt = PEER_UPDATED_AT + 1000
      await plugin.mountState.peerRefresh()
      assert.deepEqual(proxy.appended.slice(4).map(event => event.type), ['turn/start', 'user/message', 'assistant/message', 'turn/end'])
      assert.equal(proxy.appended[5].data.content[0].text, '再问一句')

      const view = await plugin.mountState.peerView()
      assert.equal(view.on, true)
      assert.equal(view.device.name, 'Luwi 手机')
      assert.equal(view.shadows.length, 1)
      assert.deepEqual(view.proxies, [{ peerSessionId: 'session-phone-1', localId: 'proxy-1', running: false, applied: 4 }])
      assert.ok(view.root.includes('peer-workspaces'))
    },
  })
})

// ---------------------------------------------------------------------------
// 1b · 回声: 本机给对端建的那一层不能被当成"本机的"再报回去
//
// 症状 (2026-10-14 真机, 投影刚跑通那一刻): 影子工作区进了本机注册表 → 下一次刷的时候
// `ownWorkspaces()` 把它连同投影会话一起报给对端 → 对端再为它建影子、标题追一层
// `（设备名）` → 两头互相放大。PC 侧 10 秒内长到 259 个分组 / 113 个投影会话, 名字套成
// `Mobile-AI\Study（Luwi 手机）（Luwi 手机）（Luwi 手机）…`。
// 判据: /peer/info 报的工作区与会话里都不能有本机自己造的那些。
// ---------------------------------------------------------------------------

test('本机的影子工作区与投影会话不会出现在 /peer/info 里', async () => {
  await withMount({
    localWorkspaces: ['DSH'],
    handler: async ({ wired, root }) => {
      const first = await plugin.mountState.peerRefresh()
      assert.equal(first.created, 1, JSON.stringify(first))
      // 影子确实进了本机注册表 (两处: 本机的 DSH 与对端那一个的影子)
      assert.equal(wired.workspaces.size, 2)
      const shadowPath = [...wired.workspaces.values()].find(entry => entry.path.startsWith(root))?.path
      assert.ok(shadowPath !== undefined)

      // 投影会话也要能被 /peer/info 看见一次 (否则这条断言是空转)
      const proxy = wired.sessions.get('proxy-1')
      assert.ok(proxy !== undefined, 'the projection session was created')
      wired.ctx.sessionController.list = async () => ({
        items: [
          { sessionId: 'proxy-1', title: '修一下登录', cwd: shadowPath, workspace: 'DSH', running: false, updatedAt: 1 },
        ],
      })

      const route = wired.routes.find(entry => entry.path === '/luwi-sync/peer/info')
      assert.ok(route !== undefined)
      const req = makeReq({ method: 'GET', url: '/luwi-sync/peer/info' })
      req.headers = { authorization: `Bearer ${TOKEN}` }
      const res = makeRes()
      await route.handler(req, res)

      const names = (res.record.body.workspaces ?? []).map(workspace => workspace.name)
      assert.deepEqual(names, ['DSH'], '影子报给对端就是回声 —— 两端会一层层套（设备名）')
      assert.deepEqual((res.record.body.sessions ?? []).map(session => session.id), [], '投影会话同样不报')
      assert.equal(String(res.record.body.device.id).length > 0, true)
    },
  })
})

// ---------------------------------------------------------------------------
// 1c · 条目语义换了一版: 旧口径折出来的那场投影要重做一场
//
// 2026-10-14 主人报的"同步功能只显示了用户的话"有两半: 折条目那一半只认 turn/end (见
// test/luwi-live.test.mjs), 而已经落地的那些投影会话只追加、不能往中间插事件 —— 补进去的
// 回答全在游标之前。所以对端报出更高的一版条目时, 那场投影要**归档重做**, 否则它永远只有
// 用户那几句话。
// ---------------------------------------------------------------------------

test('对端报出 v2 条目时, 旧口径那场投影重做一场 (旧的归档, 回答落进新的那场)', async () => {
  const state = {
    peerProxies: {
      'dev-peer|session-phone-1': { localId: 'proxy-old', lastSeq: 1, openTurn: null, turnCounter: 0, pendingTexts: [], revision: 100, running: false, snapshotV: 1 },
    },
  }
  await withMount({
    state,
    localWorkspaces: ['别的名字'],
    handler: async ({ wired, peer }) => {
      const baseInfo = peer.info
      peer.info = () => ({ ...baseInfo(), entryVersion: 2 })
      const baseTranscript = peer.transcript
      peer.transcript = sessionId => {
        const value = baseTranscript(sessionId)
        return value === null ? null : { ...value, v: 2 }
      }
      const result = await plugin.mountState.peerRefresh()
      assert.equal(result.ok, true, JSON.stringify(result))
      assert.equal(result.opened, 1, '换了一场新的投影')
      assert.deepEqual(wired.archivedSessions, ['proxy-old'], '旧那一场归档 (不是删)')
      const view = await plugin.mountState.peerView()
      assert.equal(view.proxies.length, 1)
      assert.equal(view.proxies[0].localId, 'proxy-1')
      const proxy = wired.sessions.get('proxy-1')
      assert.deepEqual(proxy.appended.map(event => event.type), ['turn/start', 'user/message', 'assistant/message', 'turn/end'])
      assert.equal(proxy.appended[2].data.message.content[0].text, '好的', '回答落进重做的那一场')
      assert.equal(state.peerProxies['dev-peer|session-phone-1'].snapshotV, 2, '账上记住已经消费到 v2')
      // 下一轮: 版本不再落后, 没变就照旧跳过 (不会每轮都重做)
      const again = await plugin.mountState.peerRefresh()
      assert.equal(again.fetched, 0)
      assert.equal(again.opened, 0)
      assert.deepEqual(wired.archivedSessions, ['proxy-old'])
    },
  })
})

test('宿主没有归档的口子时, 旧投影原地不动 (只记一句, 不每轮重试)', async () => {
  const state = {
    peerProxies: {
      'dev-peer|session-phone-1': { localId: 'proxy-old', lastSeq: 1, revision: 100, running: false, snapshotV: 1 },
    },
  }
  await withMount({
    state,
    handler: async ({ wired, peer }) => {
      delete wired.ctx.workspaceRegistry.archiveSession
      const baseInfo = peer.info
      peer.info = () => ({ ...baseInfo(), entryVersion: 2 })
      const baseTranscript = peer.transcript
      peer.transcript = sessionId => {
        const value = baseTranscript(sessionId)
        return value === null ? null : { ...value, v: 2 }
      }
      await plugin.mountState.peerRefresh()
      assert.deepEqual(wired.archivedSessions, [])
      assert.equal(wired.warns.some(line => /no session archive/u.test(line)), true, '重做不到要说一句')
      assert.equal(state.peerProxies['dev-peer|session-phone-1'].snapshotV, 2, '版本记住, 免得每轮都白问一次对端')
      assert.equal(state.peerProxies['dev-peer|session-phone-1'].localId, 'proxy-old')
    },
  })
})

// ---------------------------------------------------------------------------
// 1d · 硬上限: 对端报多少都只建前 N 个
// 2026-10-14 事故之后加的闸: 影子工作区与投影会话都是本机替对端造的东西, 没有上界时任何一处
// 回声都会变成失控的写 (那天真跑出 800 多个分组)。这两个数是 peer 段的配置项。
// ---------------------------------------------------------------------------

test('对端报的工作区与会话超过上限时只建前 N 个', async () => {
  await withMount({
    config: { peer: { token: TOKEN, url: PEER_URL, maxShadows: 2, maxProxies: 1 } },
    localWorkspaces: ['DSH'],
    handler: async ({ wired, peer }) => {
      peer.info = () => ({
        ok: true,
        v: 1,
        device: { id: 'dev-peer', name: 'Luwi 手机' },
        workspaces: [
          { name: 'A', path: '/a', sessions: 0 },
          { name: 'B', path: '/b', sessions: 0 },
          { name: 'C', path: '/c', sessions: 0 },
        ],
        sessions: [
          { id: 's1', title: '一', cwd: '/a', workspace: 'A', running: false, updatedAt: PEER_UPDATED_AT },
          { id: 's2', title: '二', cwd: '/b', workspace: 'B', running: false, updatedAt: PEER_UPDATED_AT },
        ],
        live: [],
      })
      const result = await plugin.mountState.peerRefresh()
      assert.equal(result.ok, true, JSON.stringify(result))
      assert.equal(result.created, 2, '只建两个影子')
      assert.equal(result.shadows, 2)
      assert.equal(result.opened, 1, '只给一场开投影')
      assert.equal(result.proxies, 1)
      assert.equal(wired.warns.some(line => /maxShadows/u.test(line)), true, '截断了要说一句')
      assert.equal(wired.warns.some(line => /maxProxies/u.test(line)), true, '截断了要说一句')
    },
  })
})

// ---------------------------------------------------------------------------
// 1e · 「只同步未归档的 + 从相连时才开始」与「归属方执行」
//
// 2026-10-14 主人的四条:
//   · 不要同步全部对话, 只同步未归档的
//   · 从相连时才开始同步, 过往不计
//   · luwi 给 pc 工作区发消息, 回复却用本机 agent 并在本机执行 —— 该送给归属方 (pc 端同理)
// 落脚处: ① 报给对端的会话表只留"未归档 + 相连之后有活动"的那一份; ② 对端搬进来的副本
// (本机存了一份对端会话的文件) 归档隐掉, 在它上面打字照样送给对端; ③ 退下来的投影仍归对端
// ---------------------------------------------------------------------------

test('报给对端的会话只留未归档的那一批, 且从相连时才开始 (老会话与归档的都不报)', async () => {
  // 账上那一线: 一分钟前连上的 —— 在那之前动过的一律算"过往"
  const epoch = Date.now() - 60_000
  await withMount({
    state: { peerEpochAt: epoch },
    localWorkspaces: ['DSH'],
    handler: async ({ wired }) => {
      // 本机四场: 相连之后动过的 / 相连之前就老的 / 正在跑的老的 / 归档掉的
      wired.ctx.workspaceRegistry.archivedSessionIds = ['session-archived']
      wired.ctx.sessionController.list = async () => ({
        items: [
          { sessionId: 'session-fresh', title: '刚动过', cwd: '/local/DSH', updatedAt: epoch + 1000, running: false },
          { sessionId: 'session-ancient', title: '很老', cwd: '/local/DSH', updatedAt: epoch - 1000, running: false },
          { sessionId: 'session-running', title: '正在跑', cwd: '/local/DSH', updatedAt: epoch - 1000, running: true },
          { sessionId: 'session-archived', title: '归档了', cwd: '/local/DSH', updatedAt: epoch + 1000, running: false },
        ],
      })
      const route = wired.routes.find(entry => entry.path === '/luwi-sync/peer/info')
      const req = makeReq({ method: 'GET', url: '/luwi-sync/peer/info' })
      req.headers = { authorization: `Bearer ${TOKEN}` }
      const res = makeRes()
      await route.handler(req, res)
      assert.deepEqual(
        (res.record.body.sessions ?? []).map(session => session.id).sort(),
        ['session-fresh', 'session-running'],
        '很老的不报 (过往不计), 归档的不报; 正在跑的那一场算"当下", 照报',
      )
    },
  })
})

test('对端报来的老会话本机也不做投影 (从相连时才开始, 这条本机自己也判一遍)', async () => {
  const epoch = Date.now() - 60_000
  await withMount({
    state: { peerEpochAt: epoch },
    localWorkspaces: ['DSH'],
    handler: async ({ peer }) => {
      // 对端报了老会话 (旧版插件就是这么报的: 它那一边还没有"从相连起算"): 本机按自己那条线一场都不建
      peer.info = () => ({
        ok: true,
        v: 1,
        device: { id: 'dev-peer', name: 'Luwi 手机' },
        workspaces: [{ name: 'DSH', path: '/storage/emulated/0/DSH', sessions: 0 }],
        sessions: [
          { id: 'session-old-1', title: '老一', cwd: '/storage/emulated/0/DSH', workspace: 'DSH', running: false, updatedAt: epoch - 1000 },
          { id: 'session-old-2', title: '老二', cwd: '/storage/emulated/0/DSH', workspace: 'DSH', running: false, updatedAt: 1 },
        ],
        live: [],
      })
      const result = await plugin.mountState.peerRefresh()
      assert.equal(result.ok, true, JSON.stringify(result))
      assert.equal(result.opened, 0, '相连之前就老掉的一场都不建')
      assert.equal((await plugin.mountState.peerView()).proxies.length, 0)
      // 相连之后动过的那一场照建
      peer.info = () => ({
        ok: true,
        v: 1,
        device: { id: 'dev-peer', name: 'Luwi 手机' },
        workspaces: [{ name: 'DSH', path: '/storage/emulated/0/DSH', sessions: 0 }],
        sessions: [{ id: 'session-now', title: '刚动的', cwd: '/storage/emulated/0/DSH', workspace: 'DSH', running: false, updatedAt: epoch + 1000 }],
        live: [],
      })
      const second = await plugin.mountState.peerRefresh()
      assert.equal(second.opened, 1)
      assert.equal((await plugin.mountState.peerView()).proxies.length, 1)
    },
  })
})

test('对端搬进来的副本: 归档隐掉, 在它上面打字仍然送给对端执行 (本机一个字节都不写)', async () => {
  await withMount({
    localWorkspaces: ['DSH'],
    handler: async ({ wired, stub, root }) => {
      // 本机的会话表里有一场 id 就是对端那一场的 (bring-missing 取回来的副本; 它的 cwd 是对端
      // 文件系统的路径, 在本机不存在), 另一场是本机自己的
      wired.ctx.sessionController.list = async () => ({
        items: [
          { sessionId: 'session-phone-1', title: '对端那一场', cwd: 'Z:/definitely-not-here/DSH', updatedAt: 1, running: false },
          { sessionId: 'session-local-1', title: '本机自己的一场', cwd: root, updatedAt: Date.now(), running: false },
        ],
      })
      const result = await plugin.mountState.peerRefresh()
      assert.equal(result.hidden, 1, '对端那一场的副本被隐掉')
      assert.deepEqual(wired.archivedSessions, ['session-phone-1'], '本机自己那一场一个都不碰')

      // 在它上面打字: 送给对端 (target 就是它自己的 id), 本地不追
      const handler = wired.handlers.get('agent/pre-step')
      const copy = fakeSession('session-phone-1')
      const before = stub.posts.length
      const decision = await handler({
        agent: { session: copy },
        messages: [{ role: 'user', content: [{ type: 'text', text: '在 pc 那一场里说的' }] }],
        turn: 3,
        step: 1,
      }, async () => ({ kind: 'enter', messages: 'the model would have run' }))
      assert.deepEqual(decision, { kind: 'enter', messages: [] }, '本机不跑模型')
      assert.equal(stub.posts.length, before + 1)
      assert.deepEqual(stub.posts.at(-1).target, { kind: 'session', id: 'session-phone-1', name: '' }, '执行方是那一场的归属方')
      assert.equal(copy.appended.length, 0, '对端那一份日志一个字节都不写 (写了就是 APPEND_BOTH)')

      // 本机自己那一场照旧走 next()
      let called = false
      const own = fakeSession('session-local-1')
      const answer = await handler({
        agent: { session: own },
        messages: [{ role: 'user', content: [{ type: 'text', text: '本机的一句' }] }],
        turn: 1,
        step: 1,
      }, async () => { called = true; return { kind: 'enter', messages: ['as-is'] } })
      assert.equal(called, true)
      assert.deepEqual(answer, { kind: 'enter', messages: ['as-is'] })
    },
  })
})

test('对端把本机会话的副本报回来时, 本机自己那一场不会被隐掉 (2026-10-14 真机上踩到的那一条)', async () => {
  await withMount({
    localWorkspaces: ['DSH'],
    handler: async ({ wired, root }) => {
      // 对端报了一个 id, 而本机那一场的 cwd 是**本机存在**的目录 —— 这是"对端存了我的一份",
      // 不是"我存了对端的一份": 一个都不该隐
      wired.ctx.sessionController.list = async () => ({
        items: [{ sessionId: 'session-mine', title: '我自己的', cwd: root, updatedAt: Date.now(), running: false }],
      })
      const result = await plugin.mountState.peerRefresh()
      assert.equal(result.hidden, 0)
      assert.deepEqual(wired.archivedSessions, [], '本机自己那一场动都不动')
    },
  })
})

test('退下来的投影仍然归对端: 在它上面打字还是送给对端', async () => {
  const state = { peerRetired: { 'proxy-old': 'session-phone-1' } }
  await withMount({
    state,
    localWorkspaces: ['DSH'],
    handler: async ({ wired, stub }) => {
      await plugin.mountState.peerRefresh()
      const handler = wired.handlers.get('agent/pre-step')
      const retired = fakeSession('proxy-old')
      const before = stub.posts.length
      const decision = await handler({
        agent: { session: retired },
        messages: [{ role: 'user', content: [{ type: 'text', text: '老那一场里的一句话' }] }],
        turn: 5,
        step: 1,
      }, async () => ({ kind: 'enter', messages: 'the model would have run' }))
      assert.deepEqual(decision, { kind: 'enter', messages: [] })
      assert.equal(stub.posts.length, before + 1)
      assert.deepEqual(stub.posts.at(-1).target, { kind: 'session', id: 'session-phone-1', name: '' })
    },
  })
})

// ---------------------------------------------------------------------------
// 2 · 归属方那条闸
// ---------------------------------------------------------------------------

test('typing in a projection session forwards the line and never runs the local model', async () => {
  await withMount({
    handler: async ({ wired, stub }) => {
      await plugin.mountState.peerRefresh()
      const handler = wired.handlers.get('agent/pre-step')
      assert.ok(handler !== undefined, 'the ownership guard is registered')
      const proxy = wired.sessions.get('proxy-1')
      const before = proxy.appended.length
      const decision = await handler({
        agent: { session: proxy },
        messages: [{ role: 'user', content: [{ type: 'text', text: '把登录改一下' }] }],
        turn: 7,
        step: 1,
        signal: undefined,
      }, async () => ({ kind: 'enter', messages: 'the model would have run' }))
      assert.deepEqual(decision, { kind: 'enter', messages: [] }, 'the local turn spends no model call')
      assert.equal(stub.posts.length, 1)
      const post = stub.posts[0]
      assert.equal(post.text, '把登录改一下')
      assert.deepEqual(post.target, { kind: 'session', id: 'session-phone-1', name: '' })
      // 发送方身份必须是本机设备 id。这条原来写的是 `=== undefined ? '' : ''` —— 把当时的 bug
      // (引擎只有 setter 没有 getter, deviceId 读出来是 undefined 再漏成空串) 钉成了"预期"
      assert.match(String(post.from.deviceId), /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u, 'from.deviceId 不能是空串')
      assert.equal(post.from.name, '')
      assert.equal(proxy.appended.length, before + 1, 'the outgoing line lands in the projection session')
      assert.equal(proxy.appended[before].type, 'user/message')
      assert.equal(proxy.appended[before].surface, true)
    },
  })
})

test('a line that cannot go out leaves a local notice instead of a silent drop', async () => {
  await withMount({
    handler: async ({ wired, stub }) => {
      await plugin.mountState.peerRefresh()
      stub.restore()
      globalThis.fetch = async () => { throw new Error('connect ECONNREFUSED') }
      const handler = wired.handlers.get('agent/pre-step')
      const proxy = wired.sessions.get('proxy-1')
      const before = proxy.appended.length
      const decision = await handler({
        agent: { session: proxy },
        messages: [{ role: 'user', content: [{ type: 'text', text: '发不出去的一句' }] }],
        turn: 8,
        step: 1,
        signal: undefined,
      }, async () => ({ kind: 'enter', messages: [] }))
      assert.deepEqual(decision, { kind: 'enter', messages: [] })
      const notice = proxy.appended.at(-1)
      assert.equal(notice.data.source.kind, 'dsh-session-sync', 'the failure is logged as our own notice')
      assert.match(notice.data.content[0].text, /没有送到/u)
      assert.ok(proxy.appended.length >= before + 2, 'the outgoing line and the notice are both there')
    },
  })
})

test('any other session keeps running locally', async () => {
  await withMount({
    handler: async ({ wired }) => {
      await plugin.mountState.peerRefresh()
      const handler = wired.handlers.get('agent/pre-step')
      const other = fakeSession('session-local')
      let called = false
      const decision = await handler({
        agent: { session: other },
        messages: [{ role: 'user', content: [{ type: 'text', text: '本地的一句话' }] }],
        turn: 1,
        step: 1,
      }, async () => {
        called = true
        return { kind: 'enter', messages: ['as-is'] }
      })
      assert.equal(called, true, 'the chain is not short-circuited for a local session')
      assert.deepEqual(decision, { kind: 'enter', messages: ['as-is'] })
      assert.equal(other.appended.length, 0)
    },
  })
})

// ---------------------------------------------------------------------------
// 2b · 归属方那条闸的第 ④ 条: 影工作区里**新开的一场**
//
// 2026-10-14 主人报的: "在手机上给电脑工作区发送信息还是会在本地 agent 完成"。手机 GUI 里那个
// `apk（PC）` 分组是个真工作区, 点「新对话」就在本机开一场 cwd 落在影子根底下的会话 —— 它不在
// 投影账上, id 也不是对端的, 前三条判据一条都不命中 ⇒ 那一句就地跑了本机模型 (而且跑在一个空
// 目录里: 手机侧工作区镜像是关的, 连对端的文件都看不见)。
//
// 判据: 它的归属方仍是对端 ⇒ 让对端在同一个工作区里**新开一场** (kind:'new') 跑这一句, 把回执里
// 带回来的会话 id 绑成本机这一场的投影, 于是对端的回答折回来就落在主人正看着的这一场里。
// ---------------------------------------------------------------------------

test('影工作区里新开的一场: 送到对端新开一场, 回答折回主人正看着的这一场', async () => {
  const state = {}
  await withMount({
    state,
    localWorkspaces: ['DSH'],
    handler: async ({ wired, peer, root, stub }) => {
      await plugin.mountState.peerRefresh()
      const shadowPath = [...wired.workspaces.values()].find(entry => entry.path.startsWith(root))?.path
      assert.ok(shadowPath !== undefined, 'the shadow workspace was registered')
      // 真机上对端当然会把新开的那一场报回来 (否则本机这一场会被当成"退下来的投影"归档)
      const baseInfo = peer.info
      peer.info = () => ({
        ...baseInfo(),
        sessions: [...baseInfo().sessions, { id: 'session-fresh-1', title: '检查一下电脑', cwd: '/storage/emulated/0/DSH', workspace: 'DSH', running: false, updatedAt: PEER_UPDATED_AT }],
      })
      const baseTranscript = peer.transcript
      peer.transcript = id => (id === 'session-fresh-1'
        ? {
            v: 1, deviceId: 'dev-peer', deviceName: 'Luwi 手机', sessionId: id, title: '检查一下电脑',
            cwd: '/storage/emulated/0/DSH', running: false, updatedAt: PEER_UPDATED_AT, truncated: false, draft: null,
            entries: [{ seq: 1, role: 'user', text: '检查电脑状态' }, { seq: 2, role: 'assistant', text: '好的, 我看一下' }],
          }
        : baseTranscript(id))

      const handler = wired.handlers.get('agent/pre-step')
      const shadow = fakeSession('session-shadow-1')
      shadow.header = { cwd: shadowPath }
      wired.sessions.set('session-shadow-1', shadow)
      let called = false
      const decision = await handler({
        agent: { session: shadow },
        messages: [{ role: 'user', content: [{ type: 'text', text: '检查电脑状态' }] }],
        turn: 1,
        step: 1,
      }, async () => { called = true; return { kind: 'enter', messages: 'the model would have run' } })
      assert.deepEqual(decision, { kind: 'enter', messages: [] }, '本机不跑模型')
      assert.equal(called, false, 'next() 没被调 —— 影工作区里的一切都归对端')
      assert.deepEqual(stub.posts.at(-1).target, { kind: 'new', id: '', name: 'DSH' }, '让对端在这个工作区里新开一场')
      assert.equal(shadow.appended.length, 1, '主人自己那一句落进本机这一场')
      assert.equal(shadow.appended[0].type, 'user/message')
      assert.equal(shadow.appended[0].surface, true)
      assert.equal(shadow.appended[0].data.content[0].text, '检查电脑状态')

      // 下一趟刷新: 对端的回答折回来, 而且不重复落主人自己那一句 (pendingTexts 认掉一次)
      const refreshed = await plugin.mountState.peerRefresh()
      assert.equal(refreshed.ok, true, JSON.stringify(refreshed))
      assert.deepEqual(wired.archivedSessions, [], '对端报着这一场, 它不能算"退下来的投影"')
      assert.deepEqual(shadow.appended.slice(1).map(event => event.type), ['turn/start', 'assistant/message', 'turn/end'])
      assert.equal(shadow.appended[2].data.message.content[0].text, '好的, 我看一下')
      assert.equal(state.peerProxies['dev-peer|session-fresh-1'].localId, 'session-shadow-1', '本机这一场就是那一场的投影')

      // 再打一句: 已经绑上了, 走第 ① 条判据 (目标是对端那一场的 id), 不会再新开一场
      const before = stub.posts.length
      const again = await handler({
        agent: { session: shadow },
        messages: [{ role: 'user', content: [{ type: 'text', text: '顺便看一下磁盘' }] }],
        turn: 2,
        step: 1,
      }, async () => ({ kind: 'enter', messages: 'the model would have run' }))
      assert.deepEqual(again, { kind: 'enter', messages: [] })
      assert.equal(stub.posts.length, before + 1)
      assert.deepEqual(stub.posts.at(-1).target, { kind: 'session', id: 'session-fresh-1', name: '' })
    },
  })
})

test('影工作区里的一场送不出去: 落一条本地通知, 本机一个字都不跑', async () => {
  const state = {}
  await withMount({
    state,
    localWorkspaces: ['DSH'],
    handler: async ({ wired, root, stub }) => {
      await plugin.mountState.peerRefresh()
      const shadowPath = [...wired.workspaces.values()].find(entry => entry.path.startsWith(root))?.path
      stub.restore()
      globalThis.fetch = async () => { throw new Error('connect ECONNREFUSED') }
      const handler = wired.handlers.get('agent/pre-step')
      const shadow = fakeSession('session-shadow-2')
      shadow.header = { cwd: shadowPath }
      let called = false
      const decision = await handler({
        agent: { session: shadow },
        messages: [{ role: 'user', content: [{ type: 'text', text: '发不出去的一句' }] }],
        turn: 3,
        step: 1,
      }, async () => { called = true; return { kind: 'enter', messages: 'the model would have run' } })
      assert.equal(called, false, '送不出去也不是本机跑的理由')
      assert.deepEqual(decision, { kind: 'enter', messages: [] })
      const notice = shadow.appended.at(-1)
      assert.equal(notice.data.source.kind, 'dsh-session-sync', '失败落成我们自己的通知')
      assert.match(notice.data.content[0].text, /没有送到/u)
      assert.equal(shadow.appended.length, 2, '主人那一句与通知都在')
      assert.equal(state.peerProxies?.['dev-peer|session-fresh-1'], undefined, '没接上就不登记投影')
    },
  })
})

test('影子里但认不出是哪个工作区: 仍然转给对端 (空名), 本机不跑', async () => {
  await withMount({
    handler: async ({ wired, root, stub }) => {
      await plugin.mountState.peerRefresh()
      const handler = wired.handlers.get('agent/pre-step')
      const orphan = fakeSession('session-orphan')
      orphan.header = { cwd: join(root, 'lw', 'peer-workspaces', 'dev-peer', 'no-such-workspace') }
      let called = false
      const decision = await handler({
        agent: { session: orphan },
        messages: [{ role: 'user', content: [{ type: 'text', text: '在空影子里说一句' }] }],
        turn: 1,
        step: 1,
      }, async () => { called = true; return { kind: 'enter', messages: 'the model would have run' } })
      assert.equal(called, false)
      assert.deepEqual(decision, { kind: 'enter', messages: [] })
      assert.deepEqual(stub.posts.at(-1).target, { kind: 'new', id: '', name: '' }, '说不出是哪个工作区也要送出去')
    },
  })
})

test('cwd 拿不到时按工作区注册表认影子 (id 优先的那一条口径)', async () => {
  await withMount({
    localWorkspaces: ['DSH'],
    handler: async ({ wired, root }) => {
      await plugin.mountState.peerRefresh()
      const shadow = [...wired.workspaces.values()].find(entry => entry.path.startsWith(root))
      assert.ok(shadow !== undefined)
      shadow.sessionIds = ['session-cold-shadow']
      wired.ctx.sessionController.list = async () => ({
        items: [{ sessionId: 'session-cold-shadow', title: '影子里的一场', cwd: '', updatedAt: 1, running: false }],
      })
      const handler = wired.handlers.get('agent/pre-step')
      const cold = fakeSession('session-cold-shadow')
      cold.header = {}
      let called = false
      const decision = await handler({
        agent: { session: cold },
        messages: [{ role: 'user', content: [{ type: 'text', text: '冷会话里的一句' }] }],
        turn: 1,
        step: 1,
      }, async () => { called = true; return { kind: 'enter', messages: [] } })
      assert.equal(called, false, '问得到工作区注册表就不该放它跑本机')
      assert.deepEqual(decision, { kind: 'enter', messages: [] })
    },
  })
})

test('面板那条发送路 (/luwi-sync/send) 现在真的能把一句话送到对端新开一场', async () => {
  await withMount({
    handler: async ({ wired, stub }) => {
      await plugin.mountState.peerRefresh()
      const route = wired.routes.find(entry => entry.path === '/luwi-sync/send')
      assert.ok(route !== undefined, 'the panel send route is registered')
      const req = makeReq({ method: 'POST', url: '/luwi-sync/send', body: { text: '检查电脑状态', target: { kind: 'new', id: '', name: 'DSH' } } })
      const res = makeRes()
      await route.handler(req, res)
      assert.equal(res.record.status, 200)
      assert.equal(res.record.body.ok, true)
      assert.equal(res.record.body.sessionId, 'session-fresh-1')
      assert.deepEqual(stub.posts.at(-1).target, { kind: 'new', id: '', name: 'DSH' })
    },
  })
})

// ---------------------------------------------------------------------------
// 3 · 跨重启的游标
// ---------------------------------------------------------------------------

test('a restart does not fold the peer transcript twice', async () => {
  const root = makeRoot('lw-peer-restart')
  const previous = process.env.DSH_HOME
  try {
    process.env.DSH_HOME = root
    /** 用同一份 storage 装一次 (第二次装配 = 宿主重启) */
    const runOnce = async () => {
      delete globalThis[plugin.MOUNT_KEY]
      plugin.mountState.mounted = false
      const wired = makeWiredContext()
      const peer = makePeer()
      const stub = stubPeer(peer)
      const table = { get: async () => (Object.keys(stored).length === 0 ? undefined : stored), put: async (_key, value) => { Object.assign(stored, value) } }
      wired.ctx.storageDomain = { open: () => Promise.resolve({ table: () => Promise.resolve(table), close: async () => {} }) }
      const originalLog = console.log
      console.log = () => {}
      try {
        plugin.apply(wired.ctx, {
          enabled: true,
          sessionRoot: join(root, 'sessions'),
          repoDir: join(root, 'repo'),
          peer: { token: TOKEN, url: PEER_URL },
        })
        if (plugin.mountState.mounted !== true) return null
        const refresh = await plugin.mountState.peerRefresh()
        const session = wired.sessions.get('proxy-1')
        for (const dispose of wired.disposers) dispose()
        delete globalThis[plugin.MOUNT_KEY]
        plugin.mountState.mounted = false
        return { refresh, session }
      } finally {
        console.log = originalLog
        stub.restore()
      }
    }
    const stored = {}
    const first = await runOnce()
    assert.ok(first !== null)
    assert.equal(first.session.appended.length, 4, 'the first run folds the whole transcript')
    assert.equal(first.refresh.fetched, 1)
    assert.equal(stored.peerProxies['dev-peer|session-phone-1'].lastSeq, 2, 'the cursor lands in the ledger')

    const second = await runOnce()
    assert.ok(second !== null)
    assert.equal(second.refresh.fetched, 0, 'a restart re-reads the ledger, so nothing is replayed')
    assert.equal(stored.peerProxies['dev-peer|session-phone-1'].lastSeq, 2, 'the cursor did not move')
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    rmSync(root, { recursive: true, force: true })
  }
})
