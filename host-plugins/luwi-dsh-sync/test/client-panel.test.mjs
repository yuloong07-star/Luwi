// test/client-panel.test.mjs — 面板浏览器半的单测 (不出包、不重启、不开浏览器)。
//
// 判据 (与 docs/Luwi-dsh-sync-设计.md §11.8 对上):
//   · bundle 外壳的 id 就是包名 (客户端模块系统按它挂半侧)
//   · 座位齐时三处注册都发生 (左栏图标 + main 整页 + 右侧栏 tab 类型与身体)
//   · 宿主说 panelSeats=left/off 时按它装; 探针失败时退回 both (通道没开也要有入口)
//   · 右侧栏两个可选服务缺失时安静不装, 不抛
//   · 卸载时每一个 disposer 都被调用 (座位与定时器不泄漏)
//   · 面板在"通道没开"与"有快照"两种状态下都渲染得出来
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const CLIENT_URL = new URL('../lib/client.js', import.meta.url)
const PKG_URL = new URL('../package.json', import.meta.url)

/** 桩 React: 只提供本 bundle 用到的那四个口子, 并把状态按队列喂给组件。 */
function fakeReact(stateQueue = []) {
  let cursor = 0
  return {
    createElement(type, props, ...children) {
      return { type, props: props ?? {}, children }
    },
    useState(initial) {
      const injected = cursor < stateQueue.length ? stateQueue[cursor] : initial
      cursor += 1
      return [injected, () => {}]
    },
    useEffect() {},
    useCallback(fn) { return fn },
  }
}

/** 桩客户端上下文: 收注册、收 effect、可选服务按名字查表。 */
function makeCtx(services = {}) {
  const state = { registrations: [], injects: [], effects: [], removed: [], injectedCleanups: [] }
  const ctx = {
    slots: {
      register(spec, component) {
        state.registrations.push({ spec, component })
        return () => { state.removed.push(spec) }
      },
      inject(seat, callback) {
        state.injects.push(seat)
        const cleanup = callback()
        const off = () => { if (typeof cleanup === 'function') cleanup() }
        state.injectedCleanups.push(off)
        return off
      },
    },
    get(name) { return services[name] },
    effect(callback, label) {
      const off = callback()
      state.effects.push({ label, off })
      return off
    },
  }
  return { ctx, state }
}

/** 桩右侧栏的两个可选服务。 */
function sidebarServices() {
  const tabs = { registrations: [], register(definition) { this.registrations.push(definition); return () => {} } }
  const ctrl = { opened: [], openTab(kind) { this.opened.push(kind) }, isExpanded: () => false, toggleExpanded() {} }
  return { sidebarRightTabs: tabs, sidebarRight: ctrl, tabs, ctrl }
}

/** 装一次宿主配置探针的应答。 */
function stubFetch(body, { throwOnFetch = false } = {}) {
  const previous = globalThis.fetch
  globalThis.fetch = async () => {
    if (throwOnFetch) throw new Error('no channel')
    return { status: 200, json: async () => body }
  }
  return () => { globalThis.fetch = previous }
}

/** 把 window 桩装好并求值 bundle, 返回 definition 与还原函数。 */
async function loadBundle() {
  const source = await readFile(CLIENT_URL, 'utf8')
  let definition = null
  const previousWindow = globalThis.window
  globalThis.window = {
    __ModuleLoader__: { load(def) { definition = def } },
    setTimeout: (fn, ms) => setTimeout(fn, ms),
    clearTimeout: (id) => clearTimeout(id),
  }
  // eslint-disable-next-line no-new-func
  new Function(source)()
  return {
    definition,
    restore() { globalThis.window = previousWindow },
  }
}

/** 让探针那条 promise 链落定。 */
async function flush() {
  for (let i = 0; i < 4; i += 1) await new Promise(resolve => setTimeout(resolve, 0))
}

/**
 * 收集元素树里所有文本, 用来断言"面板画出了什么"。
 * 函数型组件就地展开 (桩 React 没有渲染器, 这一步就是那个渲染器)。
 * @param {unknown} element - 元素 / 数组 / 文本。
 * @param {string[]} [out] - 累积数组。
 * @returns {string[]} 文本列表。
 */
function textsOf(element, out = []) {
  if (element === null || element === undefined || typeof element === 'boolean') return out
  if (typeof element === 'string' || typeof element === 'number') { out.push(String(element)); return out }
  if (Array.isArray(element)) { for (const child of element) textsOf(child, out); return out }
  if (typeof element === 'object') {
    if (typeof element.type === 'function') return textsOf(element.type(element.props), out)
    if (Array.isArray(element.children)) for (const child of element.children) textsOf(child, out)
  }
  return out
}

test('bundle 外壳: id 就是包名, 导出 apply 与 inject', async () => {
  const pkg = JSON.parse(await readFile(PKG_URL, 'utf8'))
  const { definition, restore } = await loadBundle()
  try {
    assert.equal(definition.id, pkg.name, 'bundle id 必须等于包名, 否则半侧挂不上')
    assert.equal(pkg.exports['./client'].default, './lib/client.js')
    assert.equal(pkg.dsh.client.platform, 'web')
    const React = fakeReact()
    const mod = definition.factory(name => {
      assert.equal(name, 'react')
      return React
    })
    assert.equal(typeof mod.apply, 'function')
    assert.deepEqual(mod.inject, ['slots'])
  } finally {
    restore()
  }
})

test('座位齐 (both): 左栏图标 + main 整页 + 右侧栏 tab 两段式', async () => {
  const { definition, restore } = await loadBundle()
  const services = sidebarServices()
  const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'both', panelOps: true } })
  try {
    const mod = definition.factory(() => fakeReact())
    const { ctx, state } = makeCtx(services)
    mod.apply(ctx)
    await flush()

    const specs = state.registrations.map(r => r.spec)
    assert.deepEqual(specs, [
      { name: 'sidebar.panellist', id: 'luwi-sync', order: 40, label: specs[0].label },
      { name: 'main', key: 'luwi-sync' },
      { name: 'sidebar.right.pane.tab', key: 'luwi-dsh-sync/panel' },
    ])
    assert.equal(typeof specs[0].label, 'function')
    assert.equal(specs[0].label(), '同步')
    assert.deepEqual(state.injects, ['sidebar.right.pane.tab'])
    assert.equal(services.tabs.registrations.length, 1)
    const tab = services.tabs.registrations[0]
    assert.equal(tab.id, 'luwi-dsh-sync/panel')
    assert.equal(tab.kind, 'luwi-sync')
    assert.equal(tab.keepMounted, true)
    assert.equal(tab.title(), '同步')
    assert.equal(tab.guide.length, 1)
    assert.equal(tab.guide[0].id, 'sync')

    // 卸载: 三处注册全被摘掉
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
    for (const off of state.injectedCleanups) off()
    assert.equal(state.removed.length, 3)
  } finally {
    restoreFetch()
    restore()
  }
})

test('宿主说 left: 右侧栏一条注册都不发生', async () => {
  const { definition, restore } = await loadBundle()
  const services = sidebarServices()
  const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'left', panelOps: true } })
  try {
    const mod = definition.factory(() => fakeReact())
    const { ctx, state } = makeCtx(services)
    mod.apply(ctx)
    await flush()
    assert.deepEqual(state.registrations.map(r => r.spec.name), ['sidebar.panellist', 'main'])
    assert.equal(state.injects.length, 0)
    assert.equal(services.tabs.registrations.length, 0)
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
  } finally {
    restoreFetch()
    restore()
  }
})

test('宿主说 off: 一个座位都不装 (这就是"关掉面板"那条回滚路)', async () => {
  const { definition, restore } = await loadBundle()
  const services = sidebarServices()
  const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'off', panelOps: true } })
  try {
    const mod = definition.factory(() => fakeReact())
    const { ctx, state } = makeCtx(services)
    mod.apply(ctx)
    await flush()
    assert.deepEqual(state.registrations, [])
    assert.equal(state.injects.length, 0)
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
  } finally {
    restoreFetch()
    restore()
  }
})

test('探针失败 (通道没开): 退回 both —— 入口要看得见, 面板自己会说为什么不能用', async () => {
  const { definition, restore } = await loadBundle()
  const services = sidebarServices()
  const restoreFetch = stubFetch(null, { throwOnFetch: true })
  try {
    const mod = definition.factory(() => fakeReact())
    const { ctx, state } = makeCtx(services)
    mod.apply(ctx)
    await flush()
    assert.deepEqual(state.registrations.map(r => r.spec.name), ['sidebar.panellist', 'main', 'sidebar.right.pane.tab'])
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
    for (const off of state.injectedCleanups) off()
  } finally {
    restoreFetch()
    restore()
  }
})

test('右侧栏可选服务缺失: 只装左栏那条, 不抛, 卸载时把轮询定时器收掉', async () => {
  const { definition, restore } = await loadBundle()
  const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'both', panelOps: true } })
  try {
    const mod = definition.factory(() => fakeReact())
    const { ctx, state } = makeCtx({})
    mod.apply(ctx)
    await flush()
    assert.deepEqual(state.registrations.map(r => r.spec.name), ['sidebar.panellist', 'main'])
    // 座位回调跑过 (能力门过了), 只是服务没就绪 → 一圈轮询排着
    assert.deepEqual(state.injects, ['sidebar.right.pane.tab'])
    for (const off of state.injectedCleanups) off()
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
    // 卸载之后再等几拍: 没有迟到的注册
    await flush()
    assert.deepEqual(state.registrations.map(r => r.spec.name), ['sidebar.panellist', 'main'])
  } finally {
    restoreFetch()
    restore()
  }
})

test('面板渲染: 通道没开时画"通道没开"那一屏, 不抛', async () => {
  const { definition, restore } = await loadBundle()
  const React = fakeReact()
  const services = sidebarServices()
  const restoreFetch = stubFetch(null, { throwOnFetch: true })
  const { ctx, state } = makeCtx(services)
  try {
    const mod = definition.factory(() => React)
    mod.apply(ctx)
    await flush()
    const panelComponent = state.registrations.find(r => r.spec.name === 'main').component
    const text = textsOf(panelComponent({})).join(' | ')
    assert.match(text, /面板通道没开/)
    assert.match(text, /webServer/)
  } finally {
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
    for (const off of state.injectedCleanups) off()
    restoreFetch()
    restore()
  }
})

test('面板渲染: 单页里配对在最上方、两根镜像都在, 没有诊断板块与名片', async () => {
  const { definition, restore } = await loadBundle()
  const snapshot = {
    ok: true,
    handshake: 'luwi-dsh-sync/panel',
    v: 1,
    plugin: 'luwi-dsh-sync',
    version: '0.2.24',
    serverTime: 1000,
    mount: { mounted: true, runner: 'subprocess' },
    config: { deviceName: 'PC', remoteState: 'set', panelOps: true, panelSeats: 'both', branch: 'main' },
    peers: { cards: [{ deviceId: 'p1', name: 'PC' }], broken: 0 },
    op: null,
    mirrors: [
      {
        target: 'sessions',
        label: '会话',
        on: true,
        remote: { state: 'set', display: 'https://example.com/r.git' },
        error: undefined,
        cost: { files: 221, bytes: 196_900_000, truncated: false },
        meta: { deviceId: 'dev-1', lastPullAgeMs: 60_000, lastPushAgeMs: 120_000 },
        status: { ok: true, branch: 'main', head: 'f'.repeat(40), remoteHead: 'f'.repeat(40), ahead: 0, behind: 0, dirty: [], forks: [] },
      },
      { target: 'workspace', label: '工作区', on: false, remote: { state: 'unset', display: '' }, cost: null, meta: {}, status: null },
    ],
  }
  // useState 顺序: [bump(useLocaleTick), snap, fault, pending, answer, auto, busy, copied, actionTarget, loadedAt]
  const React = fakeReact([0, snapshot, null])
  const services = sidebarServices()
  const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'both', panelOps: true } })
  const { ctx, state } = makeCtx(services)
  try {
    const mod = definition.factory(() => React)
    mod.apply(ctx)
    await flush()
    const panelComponent = state.registrations.find(r => r.spec.name === 'main').component
    const text = textsOf(panelComponent({})).join(' | ')
    assert.match(text, /已挂载/)
    assert.match(text, /PC/)
    assert.match(text, /runner=subprocess/)
    assert.match(text, /v=0\.2\.24/)
    assert.match(text, /会话/)
    assert.match(text, /工作区/)
    assert.match(text, /221/)
    assert.match(text, /187\.8 MB/)
    // 2026-10-22 主人: 配对操作放最上方 —— 配对卡必须排在仓库卡前面
    const atPair = text.indexOf('配对手机')
    const atRepo = text.indexOf('仓库')
    assert.ok(atPair >= 0, '配对卡要在页面上')
    assert.ok(atRepo > atPair, '配对卡要排在仓库卡前面')
    // 2026-10-22 主人: 诊断板块与名片撤掉
    assert.doesNotMatch(text, /诊断/)
    assert.doesNotMatch(text, /名片/)
    assert.doesNotMatch(text, /自检/)
  } finally {
    for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
    for (const off of state.injectedCleanups) off()
    restoreFetch()
    restore()
  }
})

test('面板渲染: 对端那一块按快照画出来 (令牌只有打码形态), 没有这一段时不多画', async () => {
  const { definition, restore } = await loadBundle()
  const base = {
    ok: true,
    handshake: 'luwi-dsh-sync/panel',
    v: 2,
    plugin: 'luwi-dsh-sync',
    version: '0.2.24',
    serverTime: 1000,
    mount: { mounted: true, runner: 'subprocess' },
    config: { deviceName: 'PC', panelOps: true, panelSeats: 'both', branch: 'main' },
    peers: { cards: [], broken: 0 },
    op: null,
    mirrors: [{ target: 'sessions', label: '会话', on: true, remote: { state: 'set', display: 'https://x/r.git' }, cost: null, meta: {}, status: { ok: true, branch: 'main', ahead: 0, behind: 0, dirty: [], forks: [] } }],
  }
  const peer = {
    enabled: true,
    listen: true,
    proxy: true,
    token: '••••cdef',
    url: { state: 'set', display: 'http://100.64.0.7:3080/' },
    urlSource: 'card',
    reachable: 'ok',
    checkedAt: 1000,
    lastError: '',
    counters: { sent: 3, received: 1 },
    queue: { pending: 2, acks: 0 },
    delivered: 4,
    lastSend: { via: 'http', at: 500 },
    workspace: { on: true, shadows: [{ key: 'a', name: 'DSH', label: 'DSH（Luwi 手机）', dir: '/x', workspaceId: 'ws-1' }], proxies: [{ peerSessionId: 's1', localId: 'proxy-1', running: false, applied: 2 }] },
  }

  const run = async (snapshot) => {
    const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'both', panelOps: true } })
    const React = fakeReact([0, snapshot, null, 'overview'])
    const services = sidebarServices()
    const { ctx, state } = makeCtx(services)
    try {
      const mod = definition.factory(() => React)
      mod.apply(ctx)
      await flush()
      const panelComponent = state.registrations.find(r => r.spec.name === 'main').component
      return textsOf(panelComponent({})).join(' | ')
    } finally {
      for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
      for (const off of state.injectedCleanups) off()
      restoreFetch()
    }
  }

  try {
    const withPeer = await run({ ...base, peer })
    assert.match(withPeer, /跨设备对端/)
    assert.match(withPeer, /可达/)
    assert.match(withPeer, /••••cdef/)
    assert.doesNotMatch(withPeer, /0123456789abcdef/)
    assert.match(withPeer, /影子工作区/)
    assert.match(withPeer, /1 \/ 1/, '影子工作区那一行报的是 工作区数 / 投影会话数')
    assert.match(withPeer, /等着过去/)
    assert.match(withPeer, /发过去/, '写面开着时那一个发消息按钮在')

    // panelOps 关掉时那一个输入框与按钮不出现 (读面照旧)
    const readonly = await run({ ...base, peer, config: { ...base.config, panelOps: false } })
    assert.match(readonly, /跨设备对端/)
    assert.doesNotMatch(readonly, /发过去/)

    // 没有 peer 块的宿主 (老版本那一侧) 那一张卡整块不画
    const without = await run(base)
    assert.doesNotMatch(without, /跨设备对端/)
  } finally {
    restore()
  }
})

// ── 账号登录 (2026-10-23): 未登录不给生成配对码 ──────────────────────────────

/** 收集元素树里所有 button 的 props (断言 disabled / title 用)。 */
function buttonsOf(element, out = []) {
  if (element === null || element === undefined || typeof element === 'boolean') return out
  if (typeof element === 'string' || typeof element === 'number') return out
  if (Array.isArray(element)) { for (const child of element) buttonsOf(child, out); return out }
  if (typeof element === 'object') {
    if (typeof element.type === 'function') return buttonsOf(element.type(element.props), out)
    if (element.type === 'button') out.push(element.props ?? {})
    if (Array.isArray(element.children)) for (const child of element.children) buttonsOf(child, out)
  }
  return out
}

/**
 * 把函数型组件就地展开成普通元素树 (桩 React 没有渲染器; 只展开**一次**, 免得重复渲染把
 * fakeReact 的状态队列耗光 —— 队列一空, 第二次渲染看到的是"通道没开"的壳)
 * @param {unknown} element - 元素 / 数组 / 文本。
 * @returns {unknown} 展开后的树。
 */
function expand(element) {
  if (element === null || element === undefined || typeof element !== 'object') return element
  if (Array.isArray(element)) return element.map(expand)
  const children = Array.isArray(element.children) ? element.children.map(expand) : element.children
  if (typeof element.type === 'function') return expand(element.type(element.props))
  return { ...element, children }
}

test('面板渲染: 未登录时生成配对码是灰的, 登录后显示账号与到期', async () => {
  const { definition, restore } = await loadBundle()
  const snapshot = {
    ok: true,
    handshake: 'luwi-dsh-sync/panel',
    v: 4,
    plugin: 'luwi-dsh-sync',
    version: '0.3.3',
    serverTime: 1000,
    mount: { mounted: true, runner: 'subprocess' },
    config: { deviceName: 'PC', remoteState: 'set', panelOps: true, panelSeats: 'both', branch: 'main' },
    peers: { cards: [{ deviceId: 'p1', name: 'PC' }], broken: 0 },
    op: null,
    mirrors: [
      {
        target: 'sessions',
        label: '会话',
        on: true,
        remote: { state: 'set', display: 'https://example.com/r.git' },
        cost: { files: 3, bytes: 2048, truncated: false },
        meta: { deviceId: 'dev-1' },
        status: { ok: true, branch: 'main', head: 'f'.repeat(40), remoteHead: 'f'.repeat(40), ahead: 0, behind: 0, dirty: [], forks: [] },
      },
    ],
  }
  const services = sidebarServices()
  const restoreFetch = stubFetch({ handshake: 'luwi-dsh-sync/panel', config: { panelSeats: 'both', panelOps: true } })

  /**
   * 用一份 license 渲染一次单页。
   * @param {object|null} license - 快照里的 license 块 (null = 从未登录)。
   * @returns {Promise<{text: string, buttons: object[]}>} 文本与按钮。
   */
  async function render(license) {
    const React = fakeReact([0, license === null ? snapshot : { ...snapshot, license }, null])
    const { ctx, state } = makeCtx(sidebarServices())
    try {
      const mod = definition.factory(() => React)
      mod.apply(ctx)
      await flush()
      const component = state.registrations.find(r => r.spec.name === 'main').component
      const tree = expand(component({}))
      return { text: textsOf(tree).join(' | '), buttons: buttonsOf(tree) }
    } finally {
      for (const effect of state.effects) { if (typeof effect.off === 'function') effect.off() }
      for (const off of state.injectedCleanups) off()
    }
  }

  try {
    const anonymous = await render(null)
    assert.match(anonymous.text, /未登录/)
    assert.match(anonymous.text, /登录 Luwi 账号/)
    const blocked = anonymous.buttons.find(button => button.title === '生成配对码前要先登录')
    assert.ok(blocked, '未登录时那颗按钮要写明原因')
    assert.equal(blocked.disabled, true, '未登录时不能生成配对码')

    const signedIn = await render({ state: 'active', login: 'octo', expiresAt: Date.now() + 86_400_000, hasToken: true })
    assert.match(signedIn.text, /@octo/)
    assert.match(signedIn.text, /退出登录/)
    assert.doesNotMatch(signedIn.text, /登录 Luwi 账号/)
    assert.equal(signedIn.buttons.some(button => button.title === '生成配对码前要先登录'), false)

    // 过期的人仍然能生成配对码 (要去续费), 但面板要说清
    const expired = await render({ state: 'expired', login: 'octo', expiresAt: 1, graceUntil: 2, hasToken: true })
    assert.match(expired.text, /已到期, git 同步照旧可用/)
    assert.equal(expired.buttons.some(button => button.title === '生成配对码前要先登录'), false)
  } finally {
    restoreFetch()
    restore()
  }
})
