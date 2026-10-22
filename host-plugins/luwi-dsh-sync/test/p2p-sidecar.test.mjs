// test/p2p-sidecar.test.mjs — sidecar 监管（假进程/假 HTTP，不起真 sidecar、不碰端口）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import { P2pSidecar, P2P_MAX_RESTARTS } from '../lib/p2p-sidecar.mjs'

const ENDPOINT_ID = 'a'.repeat(64)

class FakeChild extends EventEmitter {
  constructor(pid) {
    super()
    this.pid = pid
    this.stdout = new PassThrough()
    this.stderr = new PassThrough()
    this.killed = false
  }

  kill() {
    this.killed = true
    queueMicrotask(() => this.emit('exit', null, 'SIGTERM'))
  }
}

function fakeSpawn(plan = {}) {
  const calls = []
  const children = []
  const spawn = (binary, args, options) => {
    const index = calls.length + 1
    const child = new FakeChild(100 + index)
    calls.push({ binary, args, env: options.env })
    children.push(child)
    // READY 是异步的：和真 sidecar 一样晚一拍
    setTimeout(() => child.stdout.write('READY port=41000 dataPort=42000 token_len=32 idleMs=0 inbound=0\n'), 5)
    if (plan.crashAttempts?.includes(index)) {
      setTimeout(() => child.emit('exit', 1, null), 15)
    }
    return child
  }
  return { spawn, calls, children }
}

function makeHttp({ failId = false } = {}) {
  const calls = []
  const httpJson = async (request) => {
    calls.push(request)
    if (request.path === '/v1/shutdown') return { status: 200, body: { ok: true } }
    if (request.path === '/v1/id') {
      if (failId) throw new Error('ECONNREFUSED')
      return { status: 200, body: { ok: true, id: ENDPOINT_ID, ticket: 'endpoint-ticket' } }
    }
    return { status: 200, body: { ok: true } }
  }
  return { httpJson, calls }
}

function makeSidecar({ exists = () => true, plan = {}, http = makeHttp(), config = {} } = {}, t = undefined) {
  const fake = fakeSpawn(plan)
  const sidecar = new P2pSidecar({
    config: { enabled: true, ...config },
    pluginDir: 'C:/lw/plugin',
    platform: 'win32',
    arch: 'x64',
    execPath: 'C:/node/node.exe',
    deps: {
      spawn: fake.spawn,
      exists,
      httpJson: http.httpJson,
      sleep: () => Promise.resolve(),
      restartBackoffMs: [0, 0],
      stopGraceMs: 50,
      randomToken: () => 'test-token',
    },
  })
  if (t !== undefined) t.after(() => sidecar.stop().catch(() => {}))
  return { sidecar, fake, http }
}
async function waitFor(predicate, timeoutMs = 2000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    if (await predicate()) return true
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  return false
}

test('status: no binary is available=false + P2P_UNAVAILABLE, and never throws', async (t) => {
  const { sidecar, fake } = makeSidecar({ exists: () => false }, t)
  const status = await sidecar.status()
  assert.equal(status.available, false)
  assert.equal(status.running, false)
  assert.equal(status.reason, 'P2P_UNAVAILABLE')
  assert.match(status.detail, /no sidecar binary/u)
  assert.equal(fake.calls.length, 0, 'probe must not spawn anything')
  assert.equal((await sidecar.ensure()).available, false, 'ensure also reports instead of throwing')
})

test('ensure: spawns once, parses READY, exposes /v1/id readings', async (t) => {
  const { sidecar, fake, http } = makeSidecar({}, t)
  const status = await sidecar.ensure()
  assert.equal(fake.calls.length, 1)
  assert.deepEqual(fake.calls[0].args, ['daemon'])
  assert.equal(fake.calls[0].env.LW_TOKEN, 'test-token')
  assert.equal(status.available, true)
  assert.equal(status.running, true)
  assert.equal(status.endpointId, ENDPOINT_ID)
  assert.equal(status.ticket, 'endpoint-ticket')
  assert.equal(status.httpPort, 41000)
  assert.equal(status.dataPort, 42000)
  assert.equal(status.pid, fake.children[0].pid)
  assert.equal(http.calls.some(call => call.path === '/v1/id'), true)
})

test('ensure: a disabled p2p section reports P2P_DISABLED and does not spawn', async (t) => {
  const { sidecar, fake } = makeSidecar({ config: { enabled: false } }, t)
  const status = await sidecar.ensure()
  assert.equal(fake.calls.length, 0)
  assert.equal(status.available, true)
  assert.equal(status.running, false)
  assert.equal(status.reason, 'P2P_DISABLED')
})

test('crash: restarts once after an unexpected exit and comes back running', async (t) => {
  const { sidecar, fake } = makeSidecar({ plan: { crashAttempts: [1] } }, t)
  await sidecar.ensure()
  const recovered = await waitFor(async () => {
    const status = await sidecar.status()
    return status.running === true && fake.calls.length === 2
  })
  assert.equal(recovered, true, 'second spawn should be running')
  const status = await sidecar.status()
  assert.equal(status.running, true)
  assert.equal(status.restarts, 1)
})

test('crash: gives up after the restart budget and reports P2P_CRASH', async (t) => {
  const attempts = Array.from({ length: P2P_MAX_RESTARTS + 1 }, (_, index) => index + 1)
  const { sidecar, fake } = makeSidecar({ plan: { crashAttempts: attempts } }, t)
  await sidecar.ensure()
  const gaveUp = await waitFor(async () => {
    const status = await sidecar.status()
    return status.reason === 'P2P_CRASH'
  })
  assert.equal(gaveUp, true)
  const status = await sidecar.status()
  assert.equal(status.running, false)
  assert.equal(status.available, true)
  assert.equal(status.restarts, P2P_MAX_RESTARTS)
  assert.equal(fake.calls.length, P2P_MAX_RESTARTS + 1)
  assert.match(status.detail, /restarts exhausted/u)
})

test('stop: asks /v1/shutdown, then reports stopped', async (t) => {
  const { sidecar, fake, http } = makeSidecar({}, t)
  await sidecar.ensure()
  const status = await sidecar.stop()
  assert.equal(http.calls.some(call => call.path === '/v1/shutdown' && call.method === 'POST'), true)
  assert.equal(status.running, false)
  assert.equal(fake.children[0].killed, true)
  assert.equal(sidecar.state, 'stopped')
})

test('idleMs from config is passed to the sidecar env', async (t) => {
  const { sidecar, fake } = makeSidecar({ config: { power: 'ondemand' } }, t)
  await sidecar.ensure()
  assert.equal(fake.calls[0].env.LW_IDLE_MS, '90000')
  await sidecar.stop()
})

