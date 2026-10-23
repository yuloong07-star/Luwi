// test/p2p-service.test.mjs — 对端解析 + 生命周期（调度器用假对象，不碰真进程）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { P2pService } from '../lib/p2p-service.mjs'

function makeRunner(overrides = {}) {
  const calls = { outbound: 0, inbound: 0, heartbeat: 0, stopped: 0, stopInbound: 0 }
  const runner = {
    deviceId: '',
    peerProvider: null,
    calls,
    async runOutbound() {
      calls.outbound += 1
      return { ok: true }
    },
    async status() {
      return { enabled: true, path: 'ip' }
    },
    startInboundLoop() {
      calls.inbound += 1
      return () => { calls.stopInbound += 1 }
    },
    startHeartbeat() {
      calls.heartbeat += 1
    },
    async stop() {
      calls.stopped += 1
    },
    ...overrides,
  }
  return runner
}

function makeService({ cards = [], runner = makeRunner(), config = { enabled: true, power: 'resident' }, deviceId = 'me', readCards = undefined, trustStore = undefined } = {}) {
  const writes = []
  const store = trustStore ?? {
    read: async () => ({ v: 1, peers: {} }),
    write: async (_repoDir, trust) => { writes.push(trust) },
  }
  const service = new P2pService({
    config,
    repoDir: 'C:/repo',
    deviceId,
    readCards: readCards ?? (async () => cards),
    sidecar: {},
    runner,
    trustStore: store,
  })
  service._writes = writes
  service._store = store
  return service
}

const PC = { deviceId: 'pc', name: 'PC', p2p: { nodeId: 'pc-node' } }

test('refreshPeer picks the other device with a nodeId and beforeSync dials once', async () => {
  const runner = makeRunner()
  const service = makeService({ cards: [{ deviceId: 'me', p2p: { nodeId: 'my-node' } }, PC], runner })
  const result = await service.beforeSync()
  assert.equal(result.ok, true)
  assert.equal(runner.calls.outbound, 1)
  assert.equal(service.peer.id, 'pc')
  assert.equal(service.peer.endpoint, 'pc-node')
  assert.equal(runner.peerProvider(), service.peer, 'runner dials the resolved peer')
})

test('no usable card means P2P_UNAVAILABLE and no dial', async () => {
  const runner = makeRunner()
  const service = makeService({ cards: [{ deviceId: 'me', p2p: { nodeId: 'x' } }, { deviceId: 'phone', p2p: {} }], runner })
  const result = await service.beforeSync()
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'P2P_UNAVAILABLE')
  assert.equal(runner.calls.outbound, 0)
})

test('a card reader that throws is a quiet no-peer, not a crash', async () => {
  const runner = makeRunner()
  const service = makeService({ runner, readCards: async () => { throw new Error('EACCES') } })
  const result = await service.beforeSync()
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'P2P_UNAVAILABLE')
  assert.equal(runner.calls.outbound, 0)
})

test('a disabled p2p section never reads cards or dials', async () => {
  let reads = 0
  const runner = makeRunner()
  const service = makeService({ cards: [PC], runner, config: { enabled: false, power: 'resident' }, readCards: async () => { reads += 1; return [PC] } })
  const result = await service.beforeSync()
  assert.equal(result.reason, 'P2P_DISABLED')
  assert.equal(reads, 0)
  assert.equal(runner.calls.outbound, 0)
})

test('the device id can arrive later (engine meta is read after mount)', async () => {
  let deviceId = ''
  const service = makeService({ cards: [PC], deviceId: () => deviceId })
  assert.equal((await service.beforeSync()).reason, 'P2P_UNAVAILABLE')
  deviceId = 'me'
  assert.equal((await service.beforeSync()).ok, true)
})

test('start runs inbound and, in resident mode, the heartbeat too', async () => {
  const resident = makeRunner()
  makeService({ cards: [PC], runner: resident }).start()
  assert.equal(resident.calls.inbound, 1)
  assert.equal(resident.calls.heartbeat, 1)

  const ondemand = makeRunner()
  makeService({ cards: [PC], runner: ondemand, config: { enabled: true, power: 'ondemand' } }).start()
  assert.equal(ondemand.calls.inbound, 1)
  assert.equal(ondemand.calls.heartbeat, 0)

  const disabled = makeRunner()
  makeService({ cards: [PC], runner: disabled, config: { enabled: false, power: 'resident' } }).start()
  assert.equal(disabled.calls.inbound, 0)
})

test('stop stops the inbound loop before the runner', async () => {
  const runner = makeRunner()
  const service = makeService({ cards: [PC], runner })
  service.start()
  await service.stop()
  assert.equal(runner.calls.stopInbound, 1)
  assert.equal(runner.calls.stopped, 1)
})

test('the first sight of a peer is written to the trust table and then dialled', async () => {
  const runner = makeRunner()
  const service = makeService({ cards: [PC], runner })
  const result = await service.beforeSync()
  assert.equal(result.ok, true)
  assert.equal(service.trustState, 'new')
  assert.equal(service._writes.length, 1)
  assert.equal(service._writes[0].peers.pc.nodeId, 'pc-node')
  assert.equal(service._writes[0].peers.pc.trustedAt > 0, true)
})

test('a changed node id blocks the dial and is reported instead of trusted', async () => {
  const runner = makeRunner()
  const trustStore = {
    read: async () => ({ v: 1, peers: { pc: { nodeId: 'old-node', trustedAt: 1, revokedAt: 0 } } }),
    write: async () => { throw new Error('must not write on a changed node') },
  }
  const service = makeService({ cards: [PC], runner, trustStore })
  const result = await service.beforeSync()
  assert.equal(result.ok, false)
  assert.equal(runner.calls.outbound, 0)
  assert.equal(service.trustState, 'changed')
  const status = await service.status()
  assert.equal(status.blockedPeer.state, 'changed')
  assert.equal(status.blockedPeer.previousNodeId, 'old-node')
})

test('a revoked peer is never dialled and the table is left alone', async () => {
  const runner = makeRunner()
  const trustStore = {
    read: async () => ({ v: 1, peers: { pc: { nodeId: 'pc-node', trustedAt: 1, revokedAt: 5 } } }),
    write: async () => { throw new Error('must not write for a revoked peer') },
  }
  const service = makeService({ cards: [PC], runner, trustStore })
  const result = await service.beforeSync()
  assert.equal(result.ok, false)
  assert.equal(runner.calls.outbound, 0)
  assert.equal(service.trustState, 'revoked')
})

test('status carries the peer endpoint, trust state and the six-digit SAS', async () => {
  const runner = makeRunner({
    async status() { return { enabled: true, endpointId: 'a'.repeat(64) } },
  })
  const service = makeService({ cards: [PC], runner })
  await service.beforeSync()
  await runner.status()
  const status = await service.status()
  assert.equal(status.peerEndpoint, 'pc-node')
  assert.equal(status.trustState, 'new')
  assert.match(status.sas, /^\d{6}$/u)
})

test('only the lexicographically smaller device dials (the session itself is bidirectional)', async () => {
  const runner = makeRunner()
  const service = makeService({ cards: [PC], runner, deviceId: 'zzz-device' })
  const result = await service.beforeSync()
  assert.equal(result.reason, 'P2P_WAITING')
  assert.equal(runner.calls.outbound, 0)

  const dialer = makeRunner()
  const dialing = makeService({ cards: [PC], runner: dialer, deviceId: 'aaa-device' })
  assert.equal((await dialing.beforeSync()).ok, true)
  assert.equal(dialer.calls.outbound, 1)
})

// ── 收费闸 (2026-10-23) ─────────────────────────────────────────────────────

test('the plan gate blocks beforeSync before it resolves a peer, and status carries the plan', async () => {
  const runner = makeRunner()
  const service = new P2pService({
    config: { enabled: true, power: 'resident' },
    repoDir: 'C:/repo',
    deviceId: 'me',
    readCards: async () => [PC],
    sidecar: {},
    runner,
    requirePlan: async () => ({ ok: false, reason: 'P2P_PLAN_REQUIRED', plan: { state: 'expired', login: 'octo', expiresAt: 1, graceUntil: 2 } }),
  })
  const result = await service.beforeSync()
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'P2P_PLAN_REQUIRED')
  assert.equal(runner.calls.outbound, 0)
  assert.equal(service.peer, null, 'it does not even resolve a peer when the plan is gone')
  const status = await service.status()
  assert.equal(status.plan.state, 'expired')
  assert.equal(status.plan.login, 'octo')
})

test('a plan gate that throws fails closed; a missing gate stays permissive', async () => {
  const runner = makeRunner()
  const thrower = new P2pService({
    config: { enabled: true, power: 'resident' },
    repoDir: 'C:/repo',
    deviceId: 'me',
    readCards: async () => [PC],
    sidecar: {},
    runner,
    requirePlan: async () => { throw new Error('boom') },
  })
  assert.equal((await thrower.beforeSync()).reason, 'P2P_PLAN_REQUIRED')
  assert.equal((await thrower.status()).plan, null)

  const permissive = makeService({ cards: [PC], runner: makeRunner() })
  assert.equal((await permissive.beforeSync()).ok, true)
  assert.equal((await permissive.status()).plan, null)
})
