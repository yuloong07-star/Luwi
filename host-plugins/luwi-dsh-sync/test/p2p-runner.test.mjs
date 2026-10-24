// test/p2p-runner.test.mjs — 调度器的熔断、预算、入站核对与读数（方案 §7.3 / §7.4）。
// sidecar 与会话都由假对象注入，这里不碰真进程。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import { P2pRunner } from '../lib/p2p-runner.mjs'

const ENDPOINT = 'e'.repeat(64)
const PEER = { id: 'peer-device', name: 'PC', ticket: 'endpoint-ticket' }

function makeSidecar(overrides = {}) {
  const calls = { open: 0, accept: 0, closed: 0, stopped: 0 }
  const stream = () => ({
    closed: false,
    close() {
      this.closed = true
      calls.closed += 1
    },
  })
  const sidecar = {
    state: 'running',
    calls,
    status: async () => ({ available: true, running: true, endpointId: ENDPOINT, dataPort: 1 }),
    ensure: async () => ({ available: true, running: true, endpointId: ENDPOINT }),
    openFrameStream: async ({ to }) => {
      calls.open += 1
      calls.to = to
      return { path: 'ip', rttMs: 42, peer: PEER.id, stream: stream() }
    },
    acceptInbound: async () => {
      calls.accept += 1
      return { peer: PEER.id, stream: stream() }
    },
    stop: async () => {
      calls.stopped += 1
    },
    ...overrides,
  }
  return sidecar
}

function makeRunner({ sidecar = makeSidecar(), config = { enabled: true, power: 'resident', heartbeatMs: 60000, connectTimeoutMs: 5000 }, sessionFactory = undefined, now = () => Date.now(), peer = () => PEER, repoDir = os.tmpdir() } = {}) {
  return new P2pRunner({
    sidecar,
    config,
    repoDir,
    deviceId: 'me-device',
    deviceName: 'Me',
    peerProvider: peer,
    sessionFactory: sessionFactory ?? (() => ({ run: async () => ({ ok: true, role: 'pull-first', pulled: { files: 2, bytes: 100 }, served: { files: 1, bytes: 50 }, warnings: [] }) })),
    deps: { now },
  })
}

test('a successful outbound session records path, rtt, counters and the session window', async () => {
  const runner = makeRunner()
  const result = await runner.runOutbound()
  assert.equal(result.ok, true)
  assert.equal(result.status.path, 'ip')
  assert.equal(result.status.rttMs, 42)
  assert.equal(result.status.peerId, PEER.id)
  assert.equal(result.status.peerName, 'PC')
  assert.equal(result.status.received, 2)
  assert.equal(result.status.receivedBytes, 100)
  assert.equal(result.status.sent, 1)
  assert.equal(result.status.sentBytes, 50)
  assert.equal(result.status.cooling, false)
  assert.equal(typeof result.status.lastSessionAt, 'number')
  assert.equal(result.status.lastError, undefined)
})

test('three failures cool the transport down; the next attempt is skipped without opening a stream', async () => {
  let clock = 1000
  const sidecar = makeSidecar({ openFrameStream: async () => { throw Object.assign(new Error('boom'), {}) } })
  const runner = makeRunner({ sidecar, now: () => clock })
  for (let index = 0; index < 3; index += 1) {
    const result = await runner.runOutbound()
    assert.equal(result.ok, false)
    assert.equal(result.status.failures, index + 1)
  }
  const cooled = await runner.status()
  assert.equal(cooled.cooling, true)
  assert.equal(cooled.coolingUntil, clock + 60000)
  assert.equal(cooled.lastError.code, 'P2P_IO')

  const skipped = await runner.runOutbound()
  assert.equal(skipped.ok, false)
  assert.equal(skipped.reason, 'P2P_BUSY')
  assert.equal(skipped.status.skipped, 1)
  assert.equal(sidecar.calls.open, 0, 'no dial while cooling')
})

test('cooldown expires and a success resets the failure counter', async () => {
  let clock = 5000
  let shouldFail = true
  const sidecar = makeSidecar({
    openFrameStream: async () => {
      if (shouldFail) throw new Error('offline')
      return { path: 'relay', rttMs: 800, peer: PEER.id, stream: { close() {} } }
    },
  })
  const runner = makeRunner({ sidecar, now: () => clock })
  for (let index = 0; index < 3; index += 1) await runner.runOutbound()
  assert.equal((await runner.status()).cooling, true)

  clock += 60001
  shouldFail = false
  const recovered = await runner.runOutbound()
  assert.equal(recovered.ok, true)
  assert.equal(recovered.status.cooling, false)
  assert.equal(recovered.status.failures, 0)
  assert.equal(recovered.status.path, 'relay')
})

test('a session over its budget is a P2P_TIMEOUT and the stream is closed', async () => {
  const sidecar = makeSidecar()
  const runner = makeRunner({
    sidecar,
    sessionFactory: () => ({ run: () => new Promise(() => {}) }),
  })
  const result = await runner.runOutbound({ timeoutMs: 30 })
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'P2P_TIMEOUT')
  assert.equal(result.status.lastError.code, 'P2P_TIMEOUT')
  assert.equal(sidecar.calls.closed, 1)
})

test('inbound sessions are refused when the peer id is not the trusted one', async () => {
  let sessionCreated = 0
  const sidecar = makeSidecar({ acceptInbound: async () => ({ peer: 'someone-else', stream: { close() { sidecar.calls.closed += 1 } } }) })
  const runner = makeRunner({ sidecar, sessionFactory: () => { sessionCreated += 1; return { run: async () => ({}) } } })
  const result = await runner.runInbound()
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'P2P_NODE_CHANGED')
  assert.equal(sidecar.calls.closed, 1)
  assert.equal(sessionCreated, 0)
})

test('inbound sessions run the same state machine when the peer matches', async () => {
  const sidecar = makeSidecar()
  const runner = makeRunner({ sidecar })
  const result = await runner.runInbound()
  assert.equal(result.ok, true)
  assert.equal(sidecar.calls.accept, 1)
  assert.equal(result.status.received, 2)
})

test('gc folds the three counters and status carries them', async (t) => {
  const repo = await fs.mkdtemp(join(os.tmpdir(), 'lw-p2p-runner-'))
  t.after(() => fs.rm(repo, { recursive: true, force: true }))
  const runner = makeRunner({ repoDir: repo })
  const summary = await runner.gc({ peers: [] })
  assert.deepEqual(summary.tmp, { removed: 0, bytes: 0, kept: 0 })
  assert.equal(summary.peers.removed, 0)
  const status = await runner.status()
  assert.deepEqual(status.gc, summary)
})

test('heartbeat can be started and stopped; ticks call the runner once', async () => {
  const runner = makeRunner()
  let tick = null
  let cleared = 0
  const deps = runner.deps
  deps.setInterval = (fn) => { tick = fn; return 7 }
  deps.clearInterval = (handle) => { cleared += handle }
  let ticks = 0
  const stop = runner.startHeartbeat({ everyMs: 10, run: () => { ticks += 1; return { ok: true } } })
  assert.equal(typeof tick, 'function')
  assert.equal((await runner.status()).heartbeatOn, true, 'the readings say the heartbeat is up')
  assert.equal((await runner.status()).heartbeatMs, 60000)
  tick()
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(ticks, 1)
  stop()
  assert.equal(cleared, 7)
  assert.equal((await runner.status()).heartbeatOn, false)
})

test('a disabled p2p section never dials', async () => {
  const sidecar = makeSidecar()
  const runner = makeRunner({ sidecar, config: { enabled: false, power: 'resident' } })
  const result = await runner.runOutbound()
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'P2P_DISABLED')
  assert.equal(result.status.path, 'disabled')
  assert.equal(sidecar.calls.open, 0)
})

test('the dial target prefers ticket, then endpoint, then the device id', async () => {
  const cases = [
    [{ id: 'p', name: '', endpoint: 'node', ticket: 'endpoint-ticket' }, 'endpoint-ticket'],
    [{ id: 'p', name: '', endpoint: 'node' }, 'node'],
    [{ id: 'p', name: '' }, 'p'],
  ]
  for (const [peer, expected] of cases) {
    const sidecar = makeSidecar()
    const runner = makeRunner({ sidecar, peer: () => peer })
    await runner.runOutbound()
    assert.equal(sidecar.calls.to, expected)
  }
})

test('the inbound loop keeps accepting until stopped', async () => {
  const sidecar = makeSidecar({ acceptInbound: async () => { throw new Error('no inbound yet') } })
  const runner = makeRunner({ sidecar })
  runner.deps.sleep = () => new Promise(resolve => setTimeout(resolve, 1))
  let seen = 0
  let stop = () => {}
  stop = runner.startInboundLoop({
    retryMs: 1,
    onSession: () => {
      seen += 1
      if (seen >= 3) stop()
    },
  })
  const deadline = Date.now() + 2000
  while (seen < 3 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 5))
  assert.equal(seen >= 3, true)
  await runner.stop()
  assert.equal(sidecar.calls.stopped, 1)
})


test('the device id may be a getter resolved at session time (engine meta arrives after mount)', async () => {
  let current = ''
  let seen = null
  const sidecar = makeSidecar()
  const runner = new P2pRunner({
    sidecar,
    config: { enabled: true, power: 'resident' },
    repoDir: os.tmpdir(),
    deviceId: () => current,
    deviceName: 'Me',
    peerProvider: () => PEER,
    sessionFactory: (input) => {
      seen = input.myDeviceId
      return { run: async () => ({ ok: true, role: 'pull-first', pulled: { files: 0, bytes: 0 }, served: { files: 0, bytes: 0 } }) }
    },
  })
  current = 'device-late'
  const result = await runner.runOutbound()
  assert.equal(result.ok, true)
  assert.equal(seen, 'device-late')
})

test('an inbound accept timeout is idle noise, not the last outbound error', async () => {
  const sidecar = makeSidecar({ acceptInbound: async () => { throw new Error('no inbound yet') } })
  const runner = makeRunner({ sidecar })
  await runner.runInbound()
  assert.equal((await runner.status()).lastError, undefined, 'a quiet inbound timeout must not set lastError')
})

// ── 收费闸 (2026-10-23): 订阅过期后 P2P 不可用, 一个字节都不发 ──────────────────

test('the plan gate blocks both directions before any frame is opened', async () => {
  const sidecar = makeSidecar()
  const runner = new P2pRunner({
    sidecar,
    config: { enabled: true, power: 'resident' },
    repoDir: os.tmpdir(),
    deviceId: 'me-device',
    deviceName: 'Me',
    peerProvider: () => PEER,
    sessionFactory: () => { throw new Error('a gated run must not build a session') },
    planGate: async () => ({ ok: false, reason: 'P2P_PLAN_REQUIRED' }),
  })
  const outbound = await runner.runOutbound()
  assert.equal(outbound.ok, false)
  assert.equal(outbound.reason, 'P2P_PLAN_REQUIRED')
  assert.equal(sidecar.calls.open, 0)
  const inbound = await runner.runInbound()
  assert.equal(inbound.ok, false)
  assert.equal(inbound.reason, 'P2P_PLAN_REQUIRED')
  assert.equal(sidecar.calls.accept, 0)
})

test('a plan gate that throws fails closed, and no gate leaves the run unchanged', async () => {
  const sessionFactory = () => ({
    run: async () => ({ ok: true, role: 'pull-first', pulled: { files: 0, bytes: 0 }, served: { files: 0, bytes: 0 }, warnings: [] }),
  })
  const thrower = new P2pRunner({
    sidecar: makeSidecar(),
    config: { enabled: true, power: 'resident' },
    repoDir: os.tmpdir(),
    deviceId: 'me-device',
    deviceName: 'Me',
    peerProvider: () => PEER,
    sessionFactory,
    planGate: async () => { throw new Error('license disk unreadable') },
  })
  const blocked = await thrower.runOutbound()
  assert.equal(blocked.ok, false)
  assert.equal(blocked.reason, 'P2P_PLAN_REQUIRED')

  const allowed = new P2pRunner({
    sidecar: makeSidecar(),
    config: { enabled: true, power: 'resident' },
    repoDir: os.tmpdir(),
    deviceId: 'me-device',
    deviceName: 'Me',
    peerProvider: () => PEER,
    sessionFactory,
    planGate: async () => ({ ok: true }),
  })
  assert.equal((await allowed.runOutbound()).ok, true)
})
