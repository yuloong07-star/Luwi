// test/luwi-peer-runtime.test.mjs — Luwi 加 (G1-2 对端通道) 的运行时。
//
// 钉住六件事:
//   1. 通道关 (没有 peer 段) 时一条路由都不注册
//   2. 令牌是唯一门槛: 缺令牌 401, 令牌不对 401, 关了 listen 403; 令牌对了才 200
//   3. receive: 投一句话进某一轮 (followup/steer 由注入面决定), 目标解析不出来就 400
//   4. send: 主路 HTTP 通就用 HTTP, 打不通就落队列并推一次 (回执 by via)
//   5. consumeInbox: 投过的 id 不重投 (幂等), 每次都补一份回执
//   6. 投影: transcript / writeProjection / sweepProjections, 以及 status 里令牌只出打码
// @module luwi-dsh-sync/test/luwi-peer-runtime.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, existsSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createPeerRuntime, normalizeTarget } from '../lib/luwi-peer-runtime.mjs'
import { inboxDirectory, inboxFileName, buildMessage, writeJsonFile } from '../lib/luwi-inbox.mjs'
import { liveDirectory } from '../lib/luwi-live.mjs'
import { appendTranscriptEntries, transcriptDir } from '../lib/luwi-transcript.mjs'

const TOKEN = 't'.repeat(24)

/** 一棵临时树 */
function makeRoot(prefix) {
  return mkdtempSync(join(tmpdir(), `${prefix}-`))
}

/** 一个能当 req 用的最小对象 (readJsonBody 要的可异步迭代体) */
function makeReq({ method = 'GET', url = '/', token, body } = {}) {
  const chunks = body === undefined ? [] : [Buffer.from(JSON.stringify(body))]
  return {
    method,
    url,
    headers: token === undefined ? {} : { authorization: `Bearer ${token}` },
    async *[Symbol.asyncIterator]() {
      for (const chunk of chunks) yield chunk
    },
  }
}

/** 一个能当 res 用的最小对象 */
function makeRes() {
  const record = { status: 0, headers: {}, body: null }
  return {
    record,
    set statusCode(value) {
      record.status = value
    },
    get statusCode() {
      return record.status
    },
    setHeader(name, value) {
      record.headers[name] = value
    },
    end(payload) {
      record.body = payload === undefined ? null : JSON.parse(payload)
    },
  }
}

/** 调一条路由 */
async function callRoute(runtime, path, options = {}) {
  const route = runtime.routes().find(entry => entry.path === path)
  assert.ok(route !== undefined, `route ${path} is registered`)
  const res = makeRes()
  await route.handler(makeReq({ url: options.url ?? path, ...options }), res)
  return res.record
}

/** 一份最小依赖面 (每一项都能被单个测试替换) */
function makeDeps(root, overrides = {}) {
  const calls = { delivered: [], pushes: 0, state: { delivered: [] } }
  const deps = {
    spec: { enabled: true, listen: true, proxy: true, token: TOKEN, url: '', sendTimeoutMs: 1000, pollSeconds: 3 },
    live: { enabled: true, maxEntries: 40, maxChars: 16000, keepMinutes: 30 },
    repoDir: root,
    ownDeviceId: () => 'dev-me',
    ownDeviceName: () => 'PC',
    readPeerCards: async () => [
      { deviceId: 'dev-peer', name: 'Luwi 手机', url: 'http://peer.test:3080', updatedAt: '2026-10-15T00:00:00.000Z', self: false },
    ],
    listWorkspaces: async () => [{ name: 'DSH', title: 'DSH', path: '/work/DSH', sessionIds: ['s1'] }],
    listSessions: async () => [{ id: 's1', title: '一个会话', cwd: '/work/DSH', workspace: 'DSH', running: false, updatedAt: 1 }],
    projectSession: async sessionId => (sessionId === 's1'
      ? {
          snapshot: { v: 1, deviceId: 'dev-me', deviceName: 'PC', sessionId, title: 't', cwd: '/work', running: false, updatedAt: 5, truncated: false, entries: [{ seq: 1, role: 'user', text: 'hi', at: 1 }], draft: null },
          // 2026-10-22: projectSession 现在连"不截窗口的全量条目"一起给 (对话账 conv/ 要它)
          entries: [{ seq: 1, role: 'user', text: 'hi', at: 1 }],
        }
      : null),
    deliver: async (sessionId, message) => {
      calls.delivered.push({ sessionId, text: message.text })
      return { running: true }
    },
    resolveTarget: async target => (target.kind === 'session' ? target.id : 's1'),
    makeUserMessage: async text => ({ text }),
    readState: async () => calls.state,
    writeState: async patch => {
      calls.state = { ...calls.state, ...patch }
    },
    pushRepo: async () => {
      calls.pushes += 1
      return { ok: true }
    },
    logger: { warn() {}, info() {} },
    warn: () => {},
    now: () => 1000,
  }
  return { deps: { ...deps, ...overrides }, calls }
}

// ---------------------------------------------------------------------------
// 1 · 通道开关与令牌
// ---------------------------------------------------------------------------

test('no peer section means no routes at all', () => {
  const root = makeRoot('lw-peer-off')
  try {
    const { deps } = makeDeps(root, { spec: null })
    const runtime = createPeerRuntime(deps)
    assert.deepEqual(runtime.routes(), [])
    assert.equal(runtime.enabled, false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the token is the only door: missing, wrong, and unlistened calls are refused', async () => {
  const root = makeRoot('lw-peer-auth')
  try {
    const { deps } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    assert.equal((await callRoute(runtime, '/luwi-sync/peer/info')).status, 401)
    assert.equal((await callRoute(runtime, '/luwi-sync/peer/info', { token: 'wrong' })).status, 401)
    const ok = await callRoute(runtime, '/luwi-sync/peer/info', { token: TOKEN })
    assert.equal(ok.status, 200)
    assert.equal(ok.body.ok, true)
    assert.equal(ok.body.device.id, 'dev-me')
    assert.equal(ok.body.workspaces[0].name, 'DSH')

    const { deps: muted } = makeDeps(root, { spec: { ...deps.spec, listen: false } })
    const silent = createPeerRuntime(muted)
    const refused = await callRoute(silent, '/luwi-sync/peer/info', { token: TOKEN })
    assert.equal(refused.status, 403)
    assert.match(refused.body.error, /does not accept peer calls/u)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('peer/info and peer/send answer the method they own', async () => {
  const root = makeRoot('lw-peer-methods')
  try {
    const { deps } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    assert.equal((await callRoute(runtime, '/luwi-sync/peer/info', { method: 'POST', token: TOKEN })).status, 405)
    assert.equal((await callRoute(runtime, '/luwi-sync/peer/send', { method: 'GET', token: TOKEN })).status, 405)
    assert.equal((await callRoute(runtime, '/luwi-sync/send', { method: 'GET' })).status, 405)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 2 · 收下对端的一句话
// ---------------------------------------------------------------------------

test('a peer message is delivered into the resolved session and answered with its running state', async () => {
  const root = makeRoot('lw-peer-receive')
  try {
    const { deps, calls } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const res = await callRoute(runtime, '/luwi-sync/peer/send', {
      method: 'POST',
      token: TOKEN,
      body: { text: '帮我看下', target: { kind: 'session', id: 's1' } },
    })
    assert.equal(res.status, 200)
    assert.deepEqual(res.body, { ok: true, sessionId: 's1', running: true })
    assert.deepEqual(calls.delivered, [{ sessionId: 's1', text: '帮我看下' }])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('an empty body and an unresolvable target are refused with a reason', async () => {
  const root = makeRoot('lw-peer-receive-bad')
  try {
    const { deps } = makeDeps(root, { resolveTarget: async () => '' })
    const runtime = createPeerRuntime(deps)
    const empty = await callRoute(runtime, '/luwi-sync/peer/send', { method: 'POST', token: TOKEN, body: { text: '  ' } })
    assert.equal(empty.status, 400)
    assert.match(empty.body.error, /empty message/u)
    const nowhere = await callRoute(runtime, '/luwi-sync/peer/send', { method: 'POST', token: TOKEN, body: { text: 'hi' } })
    assert.equal(nowhere.status, 400)
    assert.match(nowhere.body.error, /could not resolve the target to a session/u)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 2b · 目标语义 (2026-10-14 加了第四种: new = 在对端那个工作区里新开一场)
// ---------------------------------------------------------------------------

test('normalizeTarget 认 new, 未知的一律落 session (对端送来的 JSON 是不可信输入)', () => {
  assert.deepEqual(normalizeTarget({ kind: 'new', id: '', name: 'DSH' }), { kind: 'new', id: '', name: 'DSH' })
  assert.deepEqual(normalizeTarget({ kind: 'workspace', name: 'DSH' }), { kind: 'workspace', id: '', name: 'DSH' })
  assert.deepEqual(normalizeTarget({ kind: 'session', id: 's1' }), { kind: 'session', id: 's1', name: '' })
  // 人写错/老对端写别的: 折成 session + 空 id ⇒ receive() 回一条明确的"解析不到会话"(失败关闭)
  assert.deepEqual(normalizeTarget({ kind: 'nonsense', id: 'x' }), { kind: 'session', id: 'x', name: '' })
  assert.deepEqual(normalizeTarget(undefined), { kind: 'session', id: '', name: '' })
})

test('kind:new 原样交给 resolveTarget —— 是不是"新开一场"由对端那一侧决定', async () => {
  const root = makeRoot('lw-peer-receive-new')
  try {
    const seen = []
    const { deps, calls } = makeDeps(root, {
      resolveTarget: async (target) => { seen.push(target); return 'fresh-1' },
    })
    const runtime = createPeerRuntime(deps)
    const res = await callRoute(runtime, '/luwi-sync/peer/send', {
      method: 'POST',
      token: TOKEN,
      body: { text: '检查电脑状态', target: { kind: 'new', name: 'DSH' } },
    })
    assert.equal(res.status, 200)
    assert.equal(res.body.sessionId, 'fresh-1')
    assert.deepEqual(seen, [{ kind: 'new', id: '', name: 'DSH' }])
    assert.deepEqual(calls.delivered, [{ sessionId: 'fresh-1', text: '检查电脑状态' }])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 3 · 发一句话 (主路 / 退路)
// ---------------------------------------------------------------------------

test('send goes over http when the peer answers, and reports which way it went', async () => {
  const root = makeRoot('lw-peer-send-http')
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      json: async () => ({ ok: true, sessionId: 'remote-1', running: false }),
    })
    const { deps, calls } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const answer = await runtime.send({ text: '去跑一下', target: { kind: 'session', id: 'remote-1' } })
    assert.deepEqual(answer, { ok: true, via: 'http', sessionId: 'remote-1', running: false })
    assert.equal(calls.pushes, 0, 'no queue push when http worked')
    assert.equal(existsSync(inboxDirectory(root, 'dev-peer')), false)
  } finally {
    globalThis.fetch = original
    rmSync(root, { recursive: true, force: true })
  }
})

test('send falls back to the queue and pushes the repository when the peer is unreachable', async () => {
  const root = makeRoot('lw-peer-send-queue')
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => {
      throw new Error('connect ECONNREFUSED')
    }
    const { deps, calls } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const answer = await runtime.send({ text: '先排队', target: { kind: 'session', id: 'remote-1' } })
    assert.equal(answer.ok, true)
    assert.equal(answer.via, 'queue')
    assert.match(answer.error, /ECONNREFUSED/u)
    assert.equal(calls.pushes, 1)
    const queued = inboxDirectory(root, 'dev-peer')
    const names = (await import('node:fs')).readdirSync(queued)
    assert.equal(names.length, 1)
    const body = JSON.parse(readFileSync(join(queued, names[0]), 'utf8'))
    assert.equal(body.text, '先排队')
    assert.equal(body.from, 'dev-me')
    assert.equal(body.target.id, 'remote-1')
  } finally {
    globalThis.fetch = original
    rmSync(root, { recursive: true, force: true })
  }
})

test('peer.mode=queue 时一条 HTTP 都不拨, 直接写队列', async () => {
  const root = makeRoot('lw-peer-send-queue-only')
  const original = globalThis.fetch
  let fetched = 0
  try {
    globalThis.fetch = async () => {
      fetched += 1
      throw new Error('should never be called in queue mode')
    }
    const { deps, calls } = makeDeps(root, {
      spec: { enabled: true, mode: 'queue', listen: false, proxy: true, token: TOKEN, url: '', sendTimeoutMs: 1000, pollSeconds: 3 },
    })
    const runtime = createPeerRuntime(deps)
    const answer = await runtime.send({ text: '只走队列', target: { kind: 'new', id: '', name: 'DSH' } })
    assert.equal(answer.ok, true)
    assert.equal(answer.via, 'queue')
    assert.equal(fetched, 0, 'queue 模式下不该有任何 fetch')
    assert.equal(calls.pushes, 1)
    assert.equal((await runtime.status()).mode, 'queue')
  } finally {
    globalThis.fetch = original
    rmSync(root, { recursive: true, force: true })
  }
})

test('send refuses to queue when there is no peer device id to queue against', async () => {
  const root = makeRoot('lw-peer-send-noid')
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => {
      throw new Error('boom')
    }
    const { deps } = makeDeps(root, { readPeerCards: async () => [] })
    const runtime = createPeerRuntime(deps)
    const answer = await runtime.send({ text: 'hi' })
    assert.equal(answer.ok, false)
    assert.match(answer.error, /no peer device id to queue against/u)
  } finally {
    globalThis.fetch = original
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 4 · 消费队列 (退路的另一半)
// ---------------------------------------------------------------------------

test('consumeInbox delivers once, writes receipts, and never delivers the same id twice', async () => {
  const root = makeRoot('lw-peer-consume')
  try {
    const directory = inboxDirectory(root, 'dev-me')
    await writeJsonFile(join(directory, inboxFileName('m1')), buildMessage({ id: 'm1', from: 'dev-peer', fromName: 'Luwi 手机', text: '第一句', target: { kind: 'session', id: 's1' }, createdAt: 1 }))
    await writeJsonFile(join(directory, inboxFileName('m2')), buildMessage({ id: 'm2', from: 'dev-peer', text: '第二句', target: { kind: 'session', id: 's1' }, createdAt: 2 }))
    const { deps, calls } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const first = await runtime.consumeInbox()
    assert.equal(first.delivered, 2)
    assert.equal(calls.delivered.length, 2)
    assert.equal(calls.pushes, 1, 'receipts are pushed back so the sender can see them')
    const acks = JSON.parse(readFileSync(join(root, 'inbox-ack', 'dev-peer', 'm1.json'), 'utf8'))
    assert.equal(acks.id, 'm1')
    assert.equal(acks.sessionId, 's1')
    assert.equal(acks.running, true)
    const ackText = readFileSync(join(root, 'inbox-ack', 'dev-peer', 'm1.json'), 'utf8')

    const second = await runtime.consumeInbox()
    assert.equal(second.delivered, 0, 'the ledger stops a second delivery')
    assert.equal(calls.delivered.length, 2)
    assert.equal(second.acks, 2, 'receipts are still refreshed for the peer')
    // 补的那份回执内容没变 → 不再重写: `at` 一变就会在对端判 DIVERGED, 每轮长一个 fork
    assert.equal(readFileSync(join(root, 'inbox-ack', 'dev-peer', 'm1.json'), 'utf8'), ackText)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('回执带机器可读的失败码: 解析不到会话写 NO_TARGET', async () => {
  const root = makeRoot('lw-peer-consume-code')
  try {
    const directory = inboxDirectory(root, 'dev-me')
    await writeJsonFile(join(directory, inboxFileName('bad')), buildMessage({
      id: 'bad',
      from: 'dev-peer',
      text: '有人吗',
      target: { kind: 'new', id: '' },
      createdAt: 3,
    }))
    const { deps } = makeDeps(root, { resolveTarget: async () => '' })
    const runtime = createPeerRuntime(deps)
    const result = await runtime.consumeInbox()
    assert.equal(result.failed, 1)
    const ack = JSON.parse(readFileSync(join(root, 'inbox-ack', 'dev-peer', 'bad.json'), 'utf8'))
    assert.equal(ack.code, 'NO_TARGET')
    assert.match(ack.error, /could not resolve the target/u)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('consumeInbox 不等推送: 推送卡住也不会把 pull 拖住', async () => {
  const root = makeRoot('lw-peer-consume-noblock')
  try {
    const directory = inboxDirectory(root, 'dev-me')
    await writeJsonFile(join(directory, inboxFileName('m9')), buildMessage({ id: 'm9', from: 'dev-peer', text: '别等我', target: { kind: 'session', id: 's1' }, createdAt: 9 }))
    let pushes = 0
    const { deps } = makeDeps(root, {
      pushRepo: () => {
        pushes += 1
        // 永远不落定: 模拟"push 卡在网络上"
        return new Promise(() => {})
      },
    })
    const runtime = createPeerRuntime(deps)
    const result = await Promise.race([
      runtime.consumeInbox(),
      new Promise((resolve, reject) => setTimeout(() => reject(new Error('consumeInbox waited for the push')), 800)),
    ])
    assert.equal(result.delivered, 1)
    assert.equal(pushes, 1, '推送仍然被发起, 只是不等它')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 5 · 投影与读数
// ---------------------------------------------------------------------------

test('transcript answers with a projection, or with why it cannot', async () => {
  const root = makeRoot('lw-peer-transcript')
  try {
    const { deps } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const good = await callRoute(runtime, '/luwi-sync/peer/transcript', { token: TOKEN, url: '/luwi-sync/peer/transcript?sessionId=s1' })
    assert.equal(good.status, 200)
    assert.equal(good.body.snapshot.entries[0].text, 'hi')
    const missing = await callRoute(runtime, '/luwi-sync/peer/transcript', { token: TOKEN, url: '/luwi-sync/peer/transcript?sessionId=nope' })
    assert.equal(missing.status, 404)
    const { deps: off } = makeDeps(root, { live: { enabled: false, maxEntries: 40, maxChars: 16000, keepMinutes: 30 } })
    const quiet = createPeerRuntime(off)
    const refused = await quiet.transcript('s1')
    assert.equal(refused.ok, false)
    assert.match(refused.error, /live projection is off/u)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('projections are written under live/<device>/ and stale ones are swept', async () => {
  const root = makeRoot('lw-peer-projection')
  const T = 1_800_000_000_000
  try {
    const { deps } = makeDeps(root, {
      now: () => T,
      projectSession: async sessionId => (sessionId === 's1'
        ? {
            snapshot: { v: 1, deviceId: 'dev-me', deviceName: 'PC', sessionId, title: 't', cwd: '/work', running: false, updatedAt: T, truncated: false, entries: [{ seq: 1, role: 'user', text: 'hi', at: 1 }], draft: null },
            entries: [{ seq: 1, role: 'user', text: 'hi', at: 1 }],
          }
        : null),
    })
    const runtime = createPeerRuntime(deps)
    assert.deepEqual(await runtime.writeProjection('s1'), { written: true })
    const file = join(liveDirectory(root, 'dev-me'), 's1.json')
    assert.equal(existsSync(file), true)
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).entries.length, 1)
    assert.deepEqual(await runtime.writeProjection('nope'), { written: false, error: 'no such session: nope' })

    // 一份过期的投影 (updatedAt 早于 keepMinutes) 要被扫掉; 没有可用时间戳的不动它
    mkdirSync(liveDirectory(root, 'dev-me'), { recursive: true })
    writeFileSync(join(liveDirectory(root, 'dev-me'), 'old.json'), JSON.stringify({ sessionId: 'old', updatedAt: 0 }))
    writeFileSync(join(liveDirectory(root, 'dev-me'), 'older.json'), JSON.stringify({ sessionId: 'older', updatedAt: 1 }))
    assert.equal(await runtime.sweepProjections(), 1)
    assert.equal(existsSync(join(liveDirectory(root, 'dev-me'), 'old.json')), true)
    assert.equal(existsSync(file), true, 'the fresh projection survives the sweep')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the status reading masks the token and reports the queue', async () => {
  const root = makeRoot('lw-peer-status')
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => {
      throw new Error('down')
    }
    const { deps } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    await runtime.send({ text: '排队中', target: { kind: 'session', id: 's1' } })
    const status = await runtime.status()
    assert.equal(status.enabled, true)
    assert.equal(status.token, `••••${TOKEN.slice(-4)}`)
    assert.ok(!JSON.stringify(status).includes(TOKEN), 'the raw token never leaves')
    assert.equal(status.url.state, 'set')
    assert.equal(status.urlSource, 'card', 'no configured url, so it came from the card')
    assert.equal(status.reachable, 'unreachable')
    assert.equal(status.queue.pending, 1)
    assert.equal(status.counters.queued, 1)
  } finally {
    globalThis.fetch = original
    rmSync(root, { recursive: true, force: true })
  }
})

test('a call refused by the peer keeps its reason for the panel', async () => {
  const root = makeRoot('lw-peer-refused')
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => ({
      ok: false,
      status: 401,
      json: async () => ({ ok: false, error: 'the shared token did not match' }),
    })
    const { deps, calls } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const answer = await runtime.send({ text: 'hi', target: { kind: 'session', id: 's1' } })
    assert.equal(answer.via, 'queue', 'a refused call still falls back to the queue')
    assert.match(answer.error, /token did not match/u)
    assert.equal(calls.pushes, 1)
    const status = await runtime.status()
    assert.match(status.lastError, /token did not match/u)
  } finally {
    globalThis.fetch = original
    rmSync(root, { recursive: true, force: true })
  }
})

test('repoTranscript reads the peer transcript and never our own copy', async () => {
  const root = makeRoot('lw-peer-transcript')
  try {
    // 对端写的那一本 + 本机自己那一本 (同一场会话): 只认对端的
    await appendTranscriptEntries(transcriptDir(root, 'dev-peer', 's9'), [
      { seq: 1, role: 'user', text: 'hi', at: 1 },
      { seq: 2, role: 'assistant', text: 'yo', at: 2 },
    ], { deviceId: 'dev-peer', sessionId: 's9' })
    await appendTranscriptEntries(transcriptDir(root, 'dev-me', 's9'), [
      { seq: 1, role: 'user', text: 'mine', at: 1 },
    ], { deviceId: 'dev-me', sessionId: 's9' })
    const { deps } = makeDeps(root)
    const runtime = createPeerRuntime(deps)
    const all = await runtime.repoTranscript('s9', 0)
    assert.equal(all.ok, true)
    assert.equal(all.device, 'dev-peer')
    assert.deepEqual(all.entries.map(entry => entry.text), ['hi', 'yo'])
    const cursor = await runtime.repoTranscript('s9', 1)
    assert.equal(cursor.ok, true)
    assert.deepEqual(cursor.entries.map(entry => entry.seq), [2])
    const missing = await runtime.repoTranscript('nope', 0)
    assert.equal(missing.ok, false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
