// test/p2p-gc.test.mjs — 四类残留的前三类：tmp / 撤销或久不见的对端缓存 / 连续缺席兜底（§6.3）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import { gcSummary, sweepAbsent, sweepPeerState, sweepTmp } from '../lib/p2p-gc.mjs'
import { readDeletedPaths } from '../lib/p2p-apply.mjs'

const DAY = 24 * 60 * 60 * 1000
const PEER = 'device-peer'
const ME = 'device-me'

async function tempRepo(t) {
  const root = await fs.mkdtemp(join(os.tmpdir(), 'lw-p2p-gc-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

async function write(repo, rel, text) {
  const file = join(repo, ...rel.split('/'))
  await fs.mkdir(join(file, '..'), { recursive: true })
  await fs.writeFile(file, text)
  return file
}

async function age(file, ms, now) {
  const then = (now - ms) / 1000
  await fs.utimes(file, then, then)
}

test('sweepTmp removes stale entries, keeps fresh ones, and can clear everything at startup', async (t) => {
  const repo = await tempRepo(t)
  const now = Date.now()
  const oldDir = join(repo, '.p2p', 'tmp', 'session-old')
  const oldChunk = await write(repo, '.p2p/tmp/session-old/chunk.jsonl', 'old')
  await age(oldChunk, 2 * DAY, now)
  await age(oldDir, 2 * DAY, now)
  await write(repo, '.p2p/tmp/session-new/chunk.jsonl', 'new')

  const byAge = await sweepTmp({ repoDir: repo, now, maxAgeMs: DAY })
  assert.equal(byAge.removed, 1)
  assert.equal(byAge.kept, 1)
  assert.equal(await fs.stat(join(repo, '.p2p', 'tmp', 'session-new')).then(() => true), true)

  const all = await sweepTmp({ repoDir: repo, now, all: true })
  assert.equal(all.removed, 1)
  assert.equal(await fs.readdir(join(repo, '.p2p', 'tmp')).then(entries => entries.length), 0)

  assert.deepEqual(await sweepTmp({ repoDir: repo, now }).then(result => result.removed), 0)
})

test('sweepPeerState only drops the base cache of revoked or 90-day-unseen peers', async (t) => {
  const repo = await tempRepo(t)
  const now = Date.now()
  await write(repo, 'base/revoked/x.json', '{}')
  await write(repo, 'base/stale/x.json', '{}')
  await write(repo, 'base/active/x.json', '{}')

  const result = await sweepPeerState({
    repoDir: repo,
    now,
    peers: [
      { id: 'revoked', revoked: true },
      { id: 'stale', lastSeen: now - 100 * DAY },
      { id: 'active', lastSeen: now - DAY },
    ],
  })
  assert.deepEqual(result.removedPeers.sort(), ['revoked', 'stale'])
  assert.equal(await fs.stat(join(repo, 'base', 'active', 'x.json')).then(() => true), true)
  assert.equal(await fs.stat(join(repo, 'base', 'revoked')).catch(() => null), null)
})

test('sweepAbsent needs two rounds plus a 30-day-old copy, then deletes through trash', async (t) => {
  const repo = await tempRepo(t)
  const now = Date.now()
  const file = await write(repo, `live/${PEER}/old.json`, '{"old":true}')
  await age(file, 40 * DAY, now)
  const stale = `live/${PEER}/old.json`

  const first = await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: [stale], now })
  assert.deepEqual(first.deleted, [])
  assert.deepEqual(first.waiting, [stale])
  assert.equal(await fs.stat(file).then(() => true), true, 'first round only records the absence')

  const second = await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: [stale], now: now + 1000 })
  assert.deepEqual(second.deleted, [stale])
  assert.equal(await fs.stat(file).catch(() => null), null)
  assert.deepEqual(await readDeletedPaths(repo), [stale])
})

test('sweepAbsent leaves a fresh copy alone until it is old enough, and drops the record when it reappears', async (t) => {
  const repo = await tempRepo(t)
  const now = Date.now()
  const file = await write(repo, `live/${PEER}/fresh.json`, '{"fresh":true}')
  const fresh = `live/${PEER}/fresh.json`

  await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: [fresh], now })
  const second = await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: [fresh], now: now + 1000 })
  assert.deepEqual(second.deleted, [])
  assert.deepEqual(second.waiting, [fresh], 'too new to delete even after two rounds')

  const back = await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: [], now: now + 2000 })
  assert.deepEqual(back.deleted, [])
  assert.deepEqual(back.waiting, [])
  assert.equal(await fs.stat(file).then(() => true), true)
})

test('sweepAbsent refuses the mailbox, other devices and unknown roots', async (t) => {
  const repo = await tempRepo(t)
  const now = Date.now()
  const mail = await write(repo, `inbox/${ME}/m1.json`, '{}')
  const mine = await write(repo, `live/${ME}/mine.json`, '{}')
  await age(mail, 40 * DAY, now)
  await age(mine, 40 * DAY, now)

  const paths = [`inbox/${ME}/m1.json`, `live/${ME}/mine.json`, '../escape', 'not-a-root/x']
  await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: paths, now })
  const second = await sweepAbsent({ repoDir: repo, peerDeviceId: PEER, absent: paths, now: now + 1000 })
  assert.deepEqual(second.deleted, [])
  assert.equal(await fs.stat(mail).then(() => true), true)
  assert.equal(await fs.stat(mine).then(() => true), true)
})

test('gcSummary folds the three counters into the status shape', () => {
  const summary = gcSummary({
    tmp: { removed: 2, bytes: 30, kept: 1 },
    peers: { removedPeers: ['a'], bytes: 7 },
    absent: { deleted: ['x'], waiting: ['y'], bytes: 5 },
  })
  assert.deepEqual(summary.tmp, { removed: 2, bytes: 30, kept: 1 })
  assert.deepEqual(summary.peers, { removed: 1, bytes: 7 })
  assert.deepEqual(summary.absent, { deleted: 1, waiting: 1, bytes: 5 })
  assert.deepEqual(gcSummary(), { tmp: undefined, peers: undefined, absent: undefined })
})
