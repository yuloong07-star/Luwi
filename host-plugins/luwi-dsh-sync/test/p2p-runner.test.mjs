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
  assert.equal(result.reason, 'P2P_UNTRUSTED')
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
  tick()
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(ticks, 1)
  stop()
  assert.equal(cleared, 7)
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
