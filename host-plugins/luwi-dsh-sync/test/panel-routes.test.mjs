// test/panel-routes.test.mjs — 面板宿主半的单测 (不出包、不重启、不碰真 git)。
//
// 判据 (与 docs/Luwi-dsh-sync-设计.md §11.8 对上):
//   · 快照有握手标记、两根镜像、成本预告
//   · 只读路径永远不碰写面
//   · 未确认的写一律 400; 并发写 409; panelOps:false 403; 镜像没开 409
//   · remote 只回 set|unset, 不回 token
//   · 引擎抛错降级成 {ok:false}
//   · 路由表的形态与 POST-only
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  createPanel,
  describeRemote,
  measureTree,
  PanelBadRequest,
  PANEL_HANDSHAKE,
  PANEL_PREFIX,
  readJsonBody,
  sendJson,
} from '../lib/panel-routes.mjs'

/** 记录调用的假引擎。 */
function fakeEngine(overrides = {}) {
  const calls = []
  const engine = {
    calls,
    async status() {
      calls.push('status')
      return {
        ok: true,
        branch: 'main',
        head: 'a'.repeat(40),
        remoteHead: 'a'.repeat(40),
        ahead: 0,
        behind: 0,
        dirty: [],
        forks: [],
        diffStat: '',
        mergeInProgress: false,
        lastCommits: [],
      }
    },
    async pull() {
      calls.push('pull')
      return { ok: true, pulled: true, merged: false, head: 'b'.repeat(40) }
    },
    async push() {
      calls.push('push')
      return { ok: true, pushed: true, head: 'c'.repeat(40), mirrored: 2, deleted: 0 }
    },
    ...overrides,
  }
  return engine
}

/**
 * 造一个面板 (两根镜像: 会话开 + 工作区关/开按参数)。
 * @param {object} [opts] - 覆盖项。
 * @returns {object} {panel, engine, workspaceEngine, deps}
 */
function makePanel(opts = {}) {
  const engine = opts.engine ?? fakeEngine()
  const workspaceEngine = opts.workspaceEngine === undefined ? null : opts.workspaceEngine
  const deps = {
    mirrors: [
      { target: 'sessions', label: '会话', engine, repoDir: opts.repoDir ?? '', remote: opts.remote ?? '', mirrorDir: 'sessions' },
      { target: 'workspace', label: '工作区', engine: workspaceEngine, repoDir: '', remote: '', mirrorDir: 'workspace' },
    ],
    readMetaFor: () => async () => ({
      deviceId: opts.deviceId ?? 'dev-1',
      lastPullAt: opts.lastPullAt,
      lastPushAt: opts.lastPushAt,
      lastError: opts.lastError,
    }),
    readPeers: opts.readPeers ?? (async () => ({ cards: [{ deviceId: 'p1', name: 'PC', url: '', updatedAt: '' }], broken: 0 })),
    readP2p: opts.readP2p,
    p2pConfirm: opts.p2pConfirm,
    p2pRevoke: opts.p2pRevoke,
    collectDoctor: opts.collectDoctor ?? (async () => ({ status: 'PASS', mount: { mounted: true, runner: 'subprocess' }, peers: [], problems: [], warnings: [], notes: [] })),
    info: {
      plugin: 'luwi-dsh-sync',
      version: '0.2.24',
      runner: 'subprocess',
      mounted: true,
      disabledReason: undefined,
      config: {
        repoDir: opts.repoDir ?? '',
        branch: 'main',
        deviceName: 'PC',
        backend: 'git',
        confirmVia: 'auto',
        autoPullOnStart: false,
        autoPushOnTurnEnd: false,
        pullIntervalMinutes: 0,
        remoteState: describeRemote(opts.remote ?? '').state,
        panelSeats: 'both',
        panelOps: opts.panelOps !== false,
      },
    },
    logger: { warn() {}, info() {} },
    now: opts.now,
  }
  return { panel: createPanel(deps), engine, workspaceEngine, deps }
}

/** 造一个只会吐一段体的假请求。 */
function fakeRequest(text, extra = {}) {
  return {
    method: 'POST',
    async *[Symbol.asyncIterator]() {
      if (text !== null) yield Buffer.from(text, 'utf8')
    },
    ...extra,
  }
}

/** 造一个记录应答的假响应。 */
function fakeResponse() {
  const res = {
    statusCode: 0,
    headers: {},
    body: undefined,
    setHeader(name, value) { this.headers[name] = value },
    end(payload) { this.body = payload },
  }
  return res
}

// ── describeRemote ──────────────────────────────────────────────────────────

test('describeRemote: 空 = unset, 有值 = set 且脱敏', () => {
  assert.deepEqual(describeRemote(''), { state: 'unset', display: '' })
  assert.deepEqual(describeRemote(undefined), { state: 'unset', display: '' })
  const masked = describeRemote('https://user:secret@example.com/repo.git')
  assert.equal(masked.state, 'set')
  assert.ok(!masked.display.includes('secret'), `token 泄漏了: ${masked.display}`)
  assert.ok(masked.display.includes('***'))
})

// ── readJsonBody ────────────────────────────────────────────────────────────

test('readJsonBody: 空体 = {}, 正常 JSON 解析, 坏输入一律 PanelBadRequest', async () => {
  assert.deepEqual(await readJsonBody(fakeRequest('')), {})
  assert.deepEqual(await readJsonBody(fakeRequest('{"op":"pull"}')), { op: 'pull' })
  await assert.rejects(() => readJsonBody(fakeRequest('not json')), PanelBadRequest)
  await assert.rejects(() => readJsonBody(fakeRequest('[1,2]')), PanelBadRequest)
  await assert.rejects(() => readJsonBody(fakeRequest('null')), PanelBadRequest)
  await assert.rejects(() => readJsonBody(fakeRequest(`"${'x'.repeat(64)}"`), 16), PanelBadRequest)
})

test('sendJson: 一律 JSON + no-store; 不可序列化的体降级成 500 一句话', () => {
  const res = fakeResponse()
  sendJson(res, 200, { ok: true })
  assert.equal(res.statusCode, 200)
  assert.equal(res.headers['Content-Type'], 'application/json; charset=utf-8')
  assert.equal(res.headers['Cache-Control'], 'no-store')
  assert.deepEqual(JSON.parse(res.body), { ok: true })

  const circular = {}
  circular.self = circular
  const bad = fakeResponse()
  sendJson(bad, 200, circular)
  assert.equal(bad.statusCode, 500)
  assert.equal(JSON.parse(bad.body).ok, false)
})

// ── measureTree ─────────────────────────────────────────────────────────────

test('measureTree: 数文件与字节, 跳过 .git, 目录不在 = null', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'luwi-panel-cost-'))
  try {
    await mkdir(join(dir, '.git'), { recursive: true })
    await writeFile(join(dir, '.git', 'huge'), 'x'.repeat(5000))
    await mkdir(join(dir, 'proj', 'sess'), { recursive: true })
    await writeFile(join(dir, 'proj', 'sess', 'a.jsonl'), 'abc')
    await writeFile(join(dir, 'proj', 'b.jsonl'), 'de')
    const measured = await measureTree(dir)
    assert.equal(measured.files, 2)
    assert.equal(measured.bytes, 5)
    assert.equal(measured.truncated, false)
    assert.equal(await measureTree(join(dir, 'nope')), null)
    assert.equal(await measureTree(''), null)
    // 上限一到就停并如实标记
    const capped = await measureTree(dir, 1)
    assert.equal(capped.files, 1)
    assert.equal(capped.truncated, true)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

// ── snapshot ────────────────────────────────────────────────────────────────

test('snapshot: 握手标记 / 两根镜像 / 元数据 / 名片 / 成本预告', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'luwi-panel-snap-'))
  try {
    await writeFile(join(dir, 'one.jsonl'), 'hello')
    const { panel, engine } = makePanel({ repoDir: dir, remote: 'https://example.com/r.git', lastPullAt: 1000 })
    const snap = await panel.snapshot()
    assert.equal(snap.ok, true)
    assert.equal(snap.handshake, PANEL_HANDSHAKE)
    assert.equal(snap.plugin, 'luwi-dsh-sync')
    assert.equal(snap.mirrors.length, 2)
    assert.deepEqual(engine.calls, ['status'])
    const sessions = snap.mirrors[0]
    assert.equal(sessions.on, true)
    assert.equal(sessions.remote.state, 'set')
    assert.equal(sessions.status.head.slice(0, 1), 'a')
    assert.equal(sessions.cost.files, 1)
    assert.equal(sessions.cost.bytes, 5)
    assert.equal(sessions.meta.deviceId, 'dev-1')
    assert.equal(sessions.meta.lastPullAgeMs >= 0, true)
    const workspace = snap.mirrors[1]
    assert.equal(workspace.on, false)
    assert.equal(workspace.status, null)
    assert.equal(workspace.cost, null)
    assert.equal(snap.peers.cards.length, 1)
    assert.equal(snap.op, null)
    assert.equal(snap.config.remoteState, 'set')
    // 快照里不许出现远端原文 (可能带 token)
    assert.ok(!JSON.stringify(snap).includes('example.com/r.git') || !JSON.stringify(snap).includes('secret'))
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('snapshot: withStatus:false 不跑 git', async () => {
  const { panel, engine } = makePanel()
  await panel.snapshot({ withStatus: false })
  assert.deepEqual(engine.calls, [])
})

// ── runOp: 拒绝路径 ─────────────────────────────────────────────────────────

test('runOp: 不认识的 op / 不认识的 target 一律 400', async () => {
  const { panel, engine } = makePanel()
  assert.equal((await panel.runOp({ op: 'nope' })).status, 400)
  assert.equal((await panel.runOp({})).status, 400)
  assert.equal((await panel.runOp({ op: 'status', target: 'elsewhere' })).status, 400)
  assert.deepEqual(engine.calls, [], '拒绝路径不许碰引擎')
})

test('runOp: 面板写面关掉 = 403; target=workspace 兼容到同一个仓库', async () => {
  const { panel } = makePanel()
  // 2026-10-20: 一个仓库之后 target=workspace 不再是"没开", 它落到同一根镜像上
  assert.equal((await panel.runOp({ op: 'status', target: 'workspace' })).status, 200)
  const readOnly = makePanel({ panelOps: false })
  assert.equal((await readOnly.panel.runOp({ op: 'pull', target: 'sessions', confirm: true })).status, 403)
  assert.deepEqual(readOnly.engine.calls, [], '403 之后不许真的拉')
})

test('runOp: 写操作缺 confirm 一律 400 (不做隐式确认)', async () => {
  const { panel, engine } = makePanel()
  for (const op of ['pull', 'push']) {
    const answer = await panel.runOp({ op, target: 'sessions' })
    assert.equal(answer.status, 400, `${op} 没确认也放过了`)
    assert.match(answer.body.error, /confirm/)
  }
  assert.deepEqual(engine.calls, [])
})

// ── runOp: 正常路径 ─────────────────────────────────────────────────────────

test('runOp: 只读三条各回自己的形状', async () => {
  const { panel, engine } = makePanel()
  const status = await panel.runOp({ op: 'status', target: 'sessions' })
  assert.equal(status.status, 200)
  assert.equal(status.body.card.target, 'sessions')
  assert.deepEqual(engine.calls, ['status'])

  const peers = await panel.runOp({ op: 'peers', target: 'sessions' })
  assert.equal(peers.body.cards.length, 1)

  const doctor = await panel.runOp({ op: 'doctor', target: 'sessions' })
  assert.equal(doctor.body.report.status, 'PASS')
})

test('runOp: 确认过的 pull/push 才真的落到引擎, 并把结果记在快照里', async () => {
  const { panel, engine } = makePanel()
  const pulled = await panel.runOp({ op: 'pull', target: 'sessions', confirm: true })
  assert.equal(pulled.status, 200)
  assert.equal(pulled.body.ok, true)
  assert.deepEqual(engine.calls, ['pull'])
  const snap = await panel.snapshot({ withStatus: false })
  assert.equal(snap.op.state, 'done')
  assert.equal(snap.op.action, 'pull')
  assert.equal(snap.op.id, 'panel-1')
  assert.equal(typeof snap.op.elapsedMs, 'number')

  const pushed = await panel.runOp({ op: 'push', target: 'sessions', confirm: true })
  assert.equal(pushed.body.op.action, 'push')
  assert.deepEqual(engine.calls, ['pull', 'push'])
})

test('runOp: 写操作单飞 —— 第二个并发写回 409 并带上正在跑的那个 id', async () => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const engine = fakeEngine({
    async pull() {
      engine.calls.push('pull')
      await gate
      return { ok: true }
    },
  })
  const { panel } = makePanel({ engine })
  const first = panel.runOp({ op: 'pull', target: 'sessions', confirm: true })
  await new Promise(resolve => setImmediate(resolve))
  const second = await panel.runOp({ op: 'push', target: 'sessions', confirm: true })
  assert.equal(second.status, 409)
  assert.match(second.body.error, /still running/)
  assert.equal(second.body.running.id, 'panel-1')
  // 正在跑的读数在快照里也是 running
  const snap = await panel.snapshot({ withStatus: false })
  assert.equal(snap.op.state, 'running')
  assert.equal(snap.op.action, 'pull')
  release()
  assert.equal((await first).status, 200)
})

test('runOp: 引擎抛错 / 回 ok:false 都降级成一句话, 面板不炸', async () => {
  const throwing = fakeEngine({
    async push() { throw new Error('boom') },
  })
  const { panel } = makePanel({ engine: throwing })
  const answer = await panel.runOp({ op: 'push', target: 'sessions', confirm: true })
  assert.equal(answer.status, 200)
  assert.equal(answer.body.ok, false)
  assert.equal(answer.body.op.state, 'failed')
  assert.equal(answer.body.op.error, 'boom')

  const failing = fakeEngine({
    async pull() { return { ok: false, error: 'remote gone' } },
  })
  const second = makePanel({ engine: failing })
  const pulled = await second.panel.runOp({ op: 'pull', target: 'sessions', confirm: true })
  assert.equal(pulled.body.ok, false)
  assert.equal(pulled.body.op.error, 'remote gone')
})

test('runOp: doctor / peers 自己抛错时回 ok:false 而不是 5xx', async () => {
  const { panel } = makePanel({
    collectDoctor: async () => { throw new Error('doctor down') },
    readPeers: async () => { throw new Error('peers down') },
  })
  const doctor = await panel.runOp({ op: 'doctor', target: 'sessions' })
  assert.equal(doctor.status, 200)
  assert.equal(doctor.body.ok, false)
  assert.equal(doctor.body.error, 'doctor down')
  const peers = await panel.runOp({ op: 'peers', target: 'sessions' })
  assert.equal(peers.body.error, 'peers down')
})

// ── routes ─────────────────────────────────────────────────────────────────

test('routes: 四条精确路由, 前缀命中, /op 只收 POST', async () => {
  const { panel } = makePanel()
  const routes = panel.routes()
  assert.deepEqual(routes.map(r => r.path), [
    `${PANEL_PREFIX}/panel`,
    `${PANEL_PREFIX}/doctor`,
    `${PANEL_PREFIX}/peers`,
    `${PANEL_PREFIX}/op`,
  ])
  assert.equal(routes.every(r => r.kind === 'exact'), true)

  const op = routes[3]
  const notPost = fakeResponse()
  await op.handler(fakeRequest(null, { method: 'GET' }), notPost)
  assert.equal(notPost.statusCode, 405)

  const badBody = fakeResponse()
  await op.handler(fakeRequest('not json'), badBody)
  assert.equal(badBody.statusCode, 400)

  const unconfirmed = fakeResponse()
  await op.handler(fakeRequest('{"op":"push","target":"sessions"}'), unconfirmed)
  assert.equal(unconfirmed.statusCode, 400)

  const ok = fakeResponse()
  await op.handler(fakeRequest('{"op":"push","target":"sessions","confirm":true}'), ok)
  assert.equal(ok.statusCode, 200)
  assert.equal(JSON.parse(ok.body).ok, true)

  const panelRoute = routes[0]
  const snap = fakeResponse()
  await panelRoute.handler(fakeRequest(null, { method: 'GET' }), snap)
  assert.equal(snap.statusCode, 200)
  assert.equal(JSON.parse(snap.body).handshake, PANEL_HANDSHAKE)
})

test('routes: dispose 清掉成本缓存 (下一次快照重新量)', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'luwi-panel-dispose-'))
  try {
    await writeFile(join(dir, 'a.jsonl'), 'x')
    const { panel } = makePanel({ repoDir: dir })
    const first = await panel.snapshot({ withStatus: false })
    assert.equal(first.mirrors[0].cost.files, 1)
    await writeFile(join(dir, 'b.jsonl'), 'y')
    const cached = await panel.snapshot({ withStatus: false })
    assert.equal(cached.mirrors[0].cost.files, 1, '一个刷新周期内该吃缓存')
    panel.dispose()
    const fresh = await panel.snapshot({ withStatus: false })
    assert.equal(fresh.mirrors[0].cost.files, 2)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('p2p trust ops: confirm + deviceId + panelOps gate, then the hook runs', async () => {
  const calls = []
  const { panel } = makePanel({
    p2pConfirm: async (deviceId) => { calls.push(`confirm:${deviceId}`); return { ok: true, nodeId: 'n1' } },
    p2pRevoke: async (deviceId) => { calls.push(`revoke:${deviceId}`); return { ok: true } },
  })
  assert.equal((await panel.runOp({ op: 'p2p-confirm', deviceId: 'p1' })).status, 400, 'confirm is required')
  assert.equal((await panel.runOp({ op: 'p2p-confirm', confirm: true })).status, 400, 'deviceId is required')

  const confirmed = await panel.runOp({ op: 'p2p-confirm', confirm: true, deviceId: 'p1' })
  assert.equal(confirmed.status, 200)
  assert.equal(confirmed.body.ok, true)
  assert.equal(confirmed.body.nodeId, 'n1')
  assert.deepEqual(calls, ['confirm:p1'])

  const revoked = await panel.runOp({ op: 'p2p-revoke', confirm: true, deviceId: 'p1' })
  assert.equal(revoked.body.ok, true)
  assert.deepEqual(calls, ['confirm:p1', 'revoke:p1'])
})

test('p2p trust ops respect panelOps:false and a host without hooks', async () => {
  const off = makePanel({ panelOps: false, p2pConfirm: async () => ({ ok: true }) })
  assert.equal((await off.panel.runOp({ op: 'p2p-confirm', confirm: true, deviceId: 'p1' })).status, 403)

  const bare = makePanel()
  assert.equal((await bare.panel.runOp({ op: 'p2p-confirm', confirm: true, deviceId: 'p1' })).status, 501)
})

test('the snapshot carries the p2p readings when the host provides them', async () => {
  const { panel } = makePanel({ readP2p: async () => ({ enabled: true, path: 'relay', rttMs: 812, trustState: 'trusted', sas: '042317' }) })
  const snapshot = await panel.snapshot()
  assert.equal(snapshot.p2p.path, 'relay')
  assert.equal(snapshot.p2p.sas, '042317')

  const without = await makePanel().panel.snapshot()
  assert.equal('p2p' in without, false)
})
