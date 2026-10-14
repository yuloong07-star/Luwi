// test/luwi-peer-wiring.test.mjs — Luwi 加 (G1-2) 在**真装配路径**上的接线。
//
// 前面几份单测测的是纯函数与运行时; 这一份测的是"index.mjs 把它们接上"这件事本身:
//   1. 启动行多出 peer=... / live=... 那一段 (装机后排查第一条)
//   2. 对端路由真的注册进 webServer (四条对端面 + 一条本机面), 且面板快照里带 peer 块
//   3. /sync peer 与 sync_peer 工具真的可用 (状态那一条走到底, 令牌只出打码)
//   4. 毒 peer 配置只关通道、不关会话同步 (C2 的同一条口径): 一行 warn + 面板照挂
// @module luwi-dsh-sync/test/luwi-peer-wiring.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as plugin from '../index.mjs'

const TOKEN = 'w'.repeat(24)

/** 一棵临时树; 结束时 caller 自己 rmSync。 */
function makeRoot(prefix) {
  return mkdtempSync(join(tmpdir(), `${prefix}-`))
}

/**
 * 一个够用的假 ctx: 必需服务齐, 可选服务用 ctx.get 给。
 * `inject` 立刻回调 (测试要的就是"装配发生了"), 于是 tools / webServer 两处都能收到注册。
 * @param {object} [overrides] - 覆盖可选服务。
 * @returns {object} {ctx, commands, routes, tools, warns, errors, disposers, consoleLines}
 */
function makeWiredContext(overrides = {}) {
  const commands = []
  const routes = []
  const tools = []
  const warns = []
  const errors = []
  const disposers = []
  const consoleLines = []
  const table = { get: async () => undefined, put: async () => {} }
  const domain = { table: () => Promise.resolve(table), close: async () => {} }
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
    on: () => () => {},
    inject: (_names, callback) => { callback(ctx) },
    commands: { register: (definition) => { commands.push(definition) } },
    storageDomain: { open: () => Promise.resolve(domain) },
    subprocess: undefined,
    sessions: {
      get: () => undefined,
      list: () => [],
      flush: async () => {},
      create: () => ({ id: 'session-new', header: { cwd: '/w' }, append() {}, deriveMessages: () => [] }),
    },
    tools: { register: (definition) => { tools.push(definition) } },
    webServer: { register: (route) => { routes.push(route); return () => {} } },
    sessionController: {
      list: async () => ({ items: [] }),
      resolveAgent: async () => ({ agent: { status: 'idle', session: { id: 's', append() {} } } }),
      create: async () => ({ sessionId: 'session-new' }),
    },
    agents: { get: () => undefined },
    sessionQuery: { readSurface: async () => ({ events: [] }) },
    sessionProjections: { stateOf: () => undefined },
    workspaceRegistry: { list: () => [], create: async () => ({ id: 'w1', title: 'DSH', path: '/w' }) },
    ...overrides,
  }
  return { ctx, commands, routes, tools, warns, errors, disposers, consoleLines }
}

/** 在临时 DSH_HOME 下跑一段 (可用 JSON 层配 peer 段)。 */
async function withHome(json, body) {
  const root = makeRoot('lw-peer-wiring')
  const previous = process.env.DSH_HOME
  process.env.DSH_HOME = root
  try {
    if (json !== undefined) {
      mkdirSync(join(root, 'lw'), { recursive: true })
      writeFileSync(join(root, 'lw', 'session-sync.json'), JSON.stringify(json))
    }
    await body(root)
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    delete globalThis[plugin.MOUNT_KEY]
    plugin.mountState.mounted = false
    rmSync(root, { recursive: true, force: true })
  }
}

/** 装上插件, 顺便收下启动行 */
function mount(home, ctx, config) {
  const lines = []
  const original = console.log
  console.log = (...args) => { lines.push(args.join(' ')) }
  try {
    plugin.apply(ctx, config)
  } finally {
    console.log = original
  }
  return lines
}

// ---------------------------------------------------------------------------
// 1 · 装上之后: 启动行 / 路由 / 命令 / 工具
// ---------------------------------------------------------------------------

test('a configured peer channel shows up in the startup line, the routes, the command and the tool', async () => {
  await withHome({
    remote: 'D:/relay/bare-sessions.git',
    deviceName: 'Luwi 手机',
    peer: { token: TOKEN },
    live: { maxEntries: 20 },
  }, async (home) => {
    delete globalThis[plugin.MOUNT_KEY]
    const wired = makeWiredContext()
    const lines = mount(home, wired.ctx, {
      enabled: true,
      sessionRoot: join(home, 'sessions'),
      repoDir: join(home, 'repo'),
      registerTools: true,
    })
    if (plugin.mountState.mounted !== true) {
      assert.ok(wired.errors.length > 0, 'a plugin that did not mount has to say why')
      return
    }
    const startup = lines.find(line => line.startsWith('session-sync: '))
    assert.ok(startup !== undefined, 'the startup line is printed')
    assert.match(startup, /peer=on listen=on proxy=on url=card live=on/u)
    assert.ok(!startup.includes(TOKEN), 'the token never reaches the startup line')
    assert.match(startup, /ws=off/u, 'the workspace mirror is untouched by the peer section')

    const paths = wired.routes.map(route => route.path).sort()
    assert.deepEqual(paths, [
      '/luwi-sync/doctor',
      '/luwi-sync/op',
      '/luwi-sync/panel',
      '/luwi-sync/peer-live',
      '/luwi-sync/peer/info',
      '/luwi-sync/peer/send',
      '/luwi-sync/peer/transcript',
      '/luwi-sync/peers',
      '/luwi-sync/send',
    ].sort())

    const command = wired.commands.find(entry => entry.name === 'sync')
    assert.ok(command !== undefined)
    const answer = await command.handler({ rawInput: 'peer', agent: undefined, signal: undefined })
    assert.equal(answer.kind, 'success')
    assert.match(answer.text, /peer: on/u)
    assert.match(answer.text, /••••/u)
    assert.ok(!answer.text.includes(TOKEN))
    const help = await command.handler({ rawInput: 'help', agent: undefined, signal: undefined })
    assert.match(help.text, /\/sync peer \[state\]/u)

    const peerTool = wired.tools.find(entry => entry.name === 'sync_peer')
    assert.ok(peerTool !== undefined, 'sync_peer is registered while the channel is on')
    for (const dispose of wired.disposers) dispose()
  })
})

test('the panel snapshot carries the peer block (masked) so the browser half can draw it', async () => {
  await withHome({ peer: { token: TOKEN } }, async (home) => {
    delete globalThis[plugin.MOUNT_KEY]
    const wired = makeWiredContext()
    mount(home, wired.ctx, {
      enabled: true,
      sessionRoot: join(home, 'sessions'),
      repoDir: join(home, 'repo'),
      registerTools: false,
    })
    if (plugin.mountState.mounted !== true) return
    const route = wired.routes.find(entry => entry.path === '/luwi-sync/panel')
    assert.ok(route !== undefined)
    const record = { status: 0, body: null }
    const res = {
      setHeader() {},
      end(payload) { record.body = JSON.parse(payload) },
    }
    await route.handler({ method: 'GET', url: '/luwi-sync/panel', headers: {} }, res)
    assert.equal(record.body.ok, true)
    assert.equal(record.body.peer.enabled, true)
    assert.equal(record.body.peer.token, `••••${TOKEN.slice(-4)}`)
    assert.ok(!JSON.stringify(record.body).includes(TOKEN), 'no token anywhere in the snapshot')
    // 影子工作区那一份读数要跟着走: 少了它, 面板那张卡的「影子工作区」那一行永远是"关"
    assert.equal(typeof record.body.peer.workspace, 'object')
    assert.equal(record.body.peer.workspace.on, true)
    assert.equal(Array.isArray(record.body.peer.workspace.shadows), true)
    assert.equal(Array.isArray(record.body.peer.workspace.proxies), true)
    for (const dispose of wired.disposers) dispose()
  })
})

// ---------------------------------------------------------------------------
// 2 · 毒配置只关这一块 (C2)
// ---------------------------------------------------------------------------

test('a poisoned peer section only turns the channel off, never the session mirror', async () => {
  await withHome({ peer: { token: 'too-short' } }, async (home) => {
    delete globalThis[plugin.MOUNT_KEY]
    const wired = makeWiredContext()
    const lines = mount(home, wired.ctx, {
      enabled: true,
      sessionRoot: join(home, 'sessions'),
      repoDir: join(home, 'repo'),
      registerTools: true,
    })
    assert.equal(plugin.mountState.mounted, true, 'the session mirror still mounts')
    assert.ok(wired.warns.some(line => /peer config rejected/u.test(line)), 'the reason is said out loud')
    const startup = lines.find(line => line.startsWith('session-sync: '))
    assert.match(startup, /peer=off/u)
    const peerRoutes = wired.routes.filter(route => route.path.startsWith('/luwi-sync/peer') && route.path !== '/luwi-sync/peers')
    assert.deepEqual(peerRoutes, [], 'no peer route when the channel is off')
    assert.equal(wired.routes.some(route => route.path === '/luwi-sync/panel'), true, 'the panel still mounts')
    assert.equal(wired.tools.some(entry => entry.name === 'sync_peer'), false)
    const command = wired.commands.find(entry => entry.name === 'sync')
    const answer = await command.handler({ rawInput: 'peer', agent: undefined, signal: undefined })
    assert.equal(answer.kind, 'error')
    assert.match(answer.text, /peer channel is off/u)
    for (const dispose of wired.disposers) dispose()
  })
})

test('an explicit peer section beats the JSON layer, and live can be narrowed from it', async () => {
  await withHome({ peer: { token: TOKEN, listen: false }, live: { maxEntries: 20 } }, async (home) => {
    delete globalThis[plugin.MOUNT_KEY]
    const wired = makeWiredContext()
    const lines = mount(home, wired.ctx, {
      enabled: true,
      sessionRoot: join(home, 'sessions'),
      repoDir: join(home, 'repo'),
      registerTools: false,
      peer: { token: 'e'.repeat(24), listen: true, proxy: false },
      live: { enabled: false },
    })
    if (plugin.mountState.mounted !== true) return
    const startup = lines.find(line => line.startsWith('session-sync: '))
    assert.match(startup, /peer=on listen=on proxy=off/u, 'the explicit cordis keys win over the JSON layer')
    assert.match(startup, /live=off/u)
    for (const dispose of wired.disposers) dispose()
  })
})

// ---------------------------------------------------------------------------
// 3 · /peer/info 里的 device.id 不能是空串
//
// 症状 (2026-10-14 真机): 两端 /peer/info 的 `device.id` 都是 "" —— 根因是引擎的 deviceId
// 只有 setter 没有 getter, 读出来是 `undefined`, 而 index.mjs 只拦 `=== 'pending'`, 于是
// undefined 一路漏成空串。后果不是"少一行读数": refreshPeerProxies 遇到空 id 直接返回
// ("the peer did not report a device id"), 对端的影子工作区与投影会话一台都建不起来, 轮询
// 还会退到 60 秒 —— 整个跨设备那一块静默失效。
// ---------------------------------------------------------------------------

test('the peer info answers with this host device id, not an empty string', async () => {
  await withHome({ peer: { token: TOKEN } }, async (home) => {
    delete globalThis[plugin.MOUNT_KEY]
    const wired = makeWiredContext()
    mount(home, wired.ctx, {
      enabled: true,
      sessionRoot: join(home, 'sessions'),
      repoDir: join(home, 'repo'),
      registerTools: false,
    })
    if (plugin.mountState.mounted !== true) return
    const route = wired.routes.find(entry => entry.path === '/luwi-sync/peer/info')
    assert.ok(route !== undefined)
    // 设备 id 是挂载时那一次 ensureMeta 补的 (异步): 等它一拍再问
    await new Promise(resolve => setTimeout(resolve, 20))
    const record = { status: 0, body: null }
    const res = { setHeader() {}, end(payload) { record.body = JSON.parse(payload) } }
    await route.handler({ method: 'GET', url: '/luwi-sync/peer/info', headers: { authorization: `Bearer ${TOKEN}` } }, res)
    assert.equal(record.body.ok, true)
    assert.match(
      String(record.body.device.id),
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u,
      'device.id 空了, 对端的影子工作区与投影会话就一台都建不起来',
    )
    // 不释放 effect 的话, 影子轮询的 setTimeout 会把测试进程吊住 (node --test 不退出)
    for (const dispose of wired.disposers) dispose()
  })
})
