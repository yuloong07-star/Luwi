// test/p2p-peer.test.mjs — 会话状态机：两段半双工、清单/NEED/BLOB、集合整组落、墓碑传播。
//
// 传输用内存里的一对队列（`channelPair`），所以不碰 socket 也不碰 sidecar；环回 e2e 另外一条。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import { P2pPeerSession, sessionRole } from '../lib/p2p-peer.mjs'
import { appendTranscriptEntries, verifyTranscript } from '../lib/luwi-transcript.mjs'
import { readDeletedPaths } from '../lib/p2p-apply.mjs'
import { SyncError } from '../lib/errors.mjs'

const A = 'device-aaa'
const B = 'device-bbb'

/** 一对内存帧流：a.sendFrame 进 b 的队列，反之亦然。 */
function channelPair() {
  const endpoints = {
    a: { inbox: [], waiters: [], closed: false },
    b: { inbox: [], waiters: [], closed: false },
  }
  const make = (selfName, otherName) => {
    const self = endpoints[selfName]
    const other = endpoints[otherName]
    const deliver = (queue, frame) => {
      if (queue.waiters.length > 0) queue.waiters.shift()(frame)
      else queue.inbox.push(frame)
    }
    return {
      sendFrame(type, body) {
        if (self.closed) throw new Error('channel closed')
        deliver(other, { type, body: Buffer.isBuffer(body) ? body : Buffer.from(body) })
      },
      nextFrame() {
        if (self.inbox.length > 0) return Promise.resolve(self.inbox.shift())
        if (self.closed) return Promise.resolve(null)
        return new Promise((resolve) => {
          self.waiters.push(resolve)
        })
      },
      close() {
        self.closed = true
        for (const waiter of self.waiters.splice(0)) waiter(null)
      },
    }
  }
  return { a: make('a', 'b'), b: make('b', 'a') }
}

async function tempRepo(t, name) {
  const root = await fs.mkdtemp(join(os.tmpdir(), `lw-p2p-peer-${name}-`))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

async function write(repo, rel, text) {
  const file = join(repo, ...rel.split('/'))
  await fs.mkdir(join(file, '..'), { recursive: true })
  await fs.writeFile(file, text)
}

async function readOrNull(repo, rel) {
  try {
    return await fs.readFile(join(repo, ...rel.split('/')), 'utf8')
  } catch {
    return null
  }
}

function session(stream, repo, myDeviceId, peerDeviceId) {
  return new P2pPeerSession({ stream, repoDir: repo, myDeviceId, peerDeviceId, deviceName: myDeviceId })
}

test('sessionRole: the lexicographically smaller device pulls first', () => {
  assert.equal(sessionRole(A, B), 'pull-first')
  assert.equal(sessionRole(B, A), 'serve-first')
})

test('two sessions sync both ways: live files, a conv set and a peer card', async (t) => {
  const repoA = await tempRepo(t, 'a')
  const repoB = await tempRepo(t, 'b')
  await write(repoA, `live/${A}/s1.json`, '{"a":1}')
  await write(repoA, `peers/${A}.json`, '{"name":"aaa"}')
  await appendTranscriptEntries(join(repoA, 'conv', A, 's1'), [
    { seq: 1, role: 'user', text: 'hello from a' },
    { seq: 2, role: 'assistant', text: 'hi' },
  ], { deviceId: A, sessionId: 's1' })
  await write(repoB, `live/${B}/s2.json`, '{"b":2}')

  const { a, b } = channelPair()
  const left = session(a, repoA, A, B)
  const right = session(b, repoB, B, A)
  const [leftResult, rightResult] = await Promise.all([left.run(), right.run()])

  assert.equal(leftResult.ok, true)
  assert.equal(rightResult.ok, true)
  assert.equal(leftResult.role, 'pull-first')
  assert.equal(rightResult.role, 'serve-first')

  assert.equal(await readOrNull(repoB, `live/${A}/s1.json`), '{"a":1}')
  assert.equal(await readOrNull(repoA, `live/${B}/s2.json`), '{"b":2}')
  assert.equal(await readOrNull(repoB, `peers/${A}.json`), '{"name":"aaa"}')
  assert.equal(await readOrNull(repoB, `conv/${A}/s1/meta.json`) !== null, true)
  assert.equal((await verifyTranscript(join(repoB, 'conv', A, 's1'))).ok, true)
  assert.equal(await readOrNull(repoA, `conv/${B}/s1/meta.json`), null, 'B has no conv set to send')
})

test('tombstones propagate: the peer deletes the pulled copy into trash and records it', async (t) => {
  const repoA = await tempRepo(t, 'a')
  const repoB = await tempRepo(t, 'b')
  await write(repoA, `live/${A}/old.json`, '{"old":true}')
  await write(repoB, `live/${B}/own.json`, '{"mine":true}')

  const first = channelPair()
  await Promise.all([
    session(first.a, repoA, A, B).run(),
    session(first.b, repoB, B, A).run(),
  ])
  assert.equal(await readOrNull(repoB, `live/${A}/old.json`), '{"old":true}')

  // A 删掉自己名下的文件, 并追加一条墓碑
  await fs.rm(join(repoA, 'live', A, 'old.json'))
  await write(repoA, `tombstones/${A}.json`, `${JSON.stringify({ path: `live/${A}/old.json`, at: 1 })}\n`)

  const second = channelPair()
  await Promise.all([
    session(second.a, repoA, A, B).run(),
    session(second.b, repoB, B, A).run(),
  ])

  assert.equal(await readOrNull(repoB, `live/${A}/old.json`), null)
  const trashed = join(repoB, '.p2p', 'trash')
  const days = await fs.readdir(trashed)
  assert.equal(days.length, 1)
  assert.equal(await readOrNull(repoB, `.p2p/trash/${days[0]}/live/${A}/old.json`), '{"old":true}')
  assert.deepEqual(await readDeletedPaths(repoB), [`live/${A}/old.json`])
  assert.equal(await readOrNull(repoB, `live/${B}/own.json`), '{"mine":true}', 'the tombstone never touches the local device')
})

test('handshake refuses a different protocol version and a different device id', async () => {
  const mismatchVersion = {
    sendFrame() {},
    nextFrame: async () => ({ type: 'HELLO', body: Buffer.from(JSON.stringify({ v: 2, deviceId: B })) }),
  }
  await assert.rejects(
    session(mismatchVersion, os.tmpdir(), A, B).run(),
    (error) => error instanceof SyncError && error.code === 'P2P_PROTOCOL',
  )

  const mismatchDevice = {
    sendFrame() {},
    nextFrame: async () => ({ type: 'HELLO', body: Buffer.from(JSON.stringify({ v: 1, deviceId: 'device-zzz' })) }),
  }
  await assert.rejects(
    session(mismatchDevice, os.tmpdir(), A, B).run(),
    (error) => error instanceof SyncError && error.code === 'P2P_UNTRUSTED',
  )
})
