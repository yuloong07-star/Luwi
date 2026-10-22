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

function makeService({ cards = [], runner = makeRunner(), config = { enabled: true, power: 'resident' }, deviceId = 'me', readCards = undefined } = {}) {
  return new P2pService({
    config,
    repoDir: 'C:/repo',
    deviceId,
    readCards: readCards ?? (async () => cards),
    sidecar: {},
    runner,
  })
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
