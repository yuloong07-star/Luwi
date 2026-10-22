// test/p2p-trust.test.mjs — 信任表、SAS、换机/吊销与 .p2p 的 git 忽略（方案 §8.3）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import {
  confirmPeer,
  ensureP2pLocalDir,
  evaluatePeer,
  maskNodeId,
  readTrust,
  rememberPeer,
  revokePeer,
  sasDigits,
  trustPath,
  writeTrust,
} from '../lib/p2p-trust.mjs'

const DEVICE = 'device-pc'
const NODE = 'node-aaaaaaaa'

async function tempRepo(t) {
  const root = await fs.mkdtemp(join(os.tmpdir(), 'lw-p2p-trust-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

test('SAS is six digits and independent of argument order', () => {
  const left = sasDigits(NODE, 'node-bbbbbbbb')
  assert.match(left, /^\d{6}$/u)
  assert.equal(sasDigits('node-bbbbbbbb', NODE), left)
  assert.notEqual(sasDigits(NODE, 'node-cccccccc'), left)
})

test('the trust table round-trips and .p2p is git-ignored', async (t) => {
  const repo = await tempRepo(t)
  assert.deepEqual(await readTrust(repo), { v: 1, peers: {} })
  const dir = await ensureP2pLocalDir(repo)
  assert.equal(await fs.readFile(join(dir, '.gitignore'), 'utf8'), '*\n')

  const trust = { v: 1, peers: { [DEVICE]: { nodeId: NODE, trustedAt: 5 } }, extra: 'kept' }
  await writeTrust(repo, trust)
  const back = await readTrust(repo)
  assert.deepEqual(back.peers[DEVICE], { nodeId: NODE, trustedAt: 5 })
  assert.equal(back.extra, 'kept')
  assert.equal(trustPath(repo), join(repo, '.p2p', 'trust.json'))
})

test('evaluatePeer tells new, trusted, changed and revoked apart', () => {
  const trust = { v: 1, peers: {} }
  assert.equal(evaluatePeer(trust, { deviceId: DEVICE, nodeId: NODE }).state, 'new')

  const remembered = rememberPeer(trust, { deviceId: DEVICE, nodeId: NODE, name: 'PC', now: 100 }).trust
  assert.equal(evaluatePeer(remembered, { deviceId: DEVICE, nodeId: NODE }).state, 'trusted')

  const changed = evaluatePeer(remembered, { deviceId: DEVICE, nodeId: 'node-new' })
  assert.equal(changed.ok, false)
  assert.equal(changed.state, 'changed')
  assert.equal(changed.previousNodeId, NODE)

  const revoked = revokePeer(remembered, { deviceId: DEVICE, now: 200 })
  assert.equal(revoked.peers[DEVICE].revokedAt, 200)
  assert.equal(evaluatePeer(revoked, { deviceId: DEVICE, nodeId: NODE }).state, 'revoked')
})

test('rememberPeer trusts on first sight, refreshes lastSeen, and never overwrites a changed node', () => {
  const empty = { v: 1, peers: {} }
  const first = rememberPeer(empty, { deviceId: DEVICE, nodeId: NODE, name: 'PC', now: 100 })
  assert.equal(first.decision.ok, true)
  assert.equal(first.decision.state, 'new')
  assert.deepEqual(first.trust.peers[DEVICE], { nodeId: NODE, name: 'PC', firstSeenAt: 100, trustedAt: 100, lastSeenAt: 100 })

  const again = rememberPeer(first.trust, { deviceId: DEVICE, nodeId: NODE, name: 'PC', now: 300 })
  assert.equal(again.decision.state, 'trusted')
  assert.equal(again.trust.peers[DEVICE].lastSeenAt, 300)
  assert.equal(again.trust.peers[DEVICE].firstSeenAt, 100)

  const changed = rememberPeer(again.trust, { deviceId: DEVICE, nodeId: 'node-other', now: 400 })
  assert.equal(changed.decision.ok, false)
  assert.equal(changed.decision.state, 'changed')
  assert.equal(changed.trust, again.trust, 'a changed node id leaves the table untouched')

  const revoked = revokePeer(again.trust, { deviceId: DEVICE, now: 500 })
  const afterRevoke = rememberPeer(revoked, { deviceId: DEVICE, nodeId: NODE, now: 600 })
  assert.equal(afterRevoke.decision.state, 'revoked')
  assert.equal(afterRevoke.trust.peers[DEVICE].lastSeenAt, 300, 'revoked peers are not refreshed')
})

test('confirmPeer replaces the node id and clears the revocation', () => {
  const trust = revokePeer(rememberPeer({ v: 1, peers: {} }, { deviceId: DEVICE, nodeId: NODE, now: 100 }).trust, { deviceId: DEVICE, now: 200 })
  const confirmed = confirmPeer(trust, { deviceId: DEVICE, nodeId: 'node-new', now: 300 })
  assert.equal(confirmed.peers[DEVICE].nodeId, 'node-new')
  assert.equal(confirmed.peers[DEVICE].trustedAt, 300)
  assert.equal(confirmed.peers[DEVICE].revokedAt, undefined)
  assert.equal(evaluatePeer(confirmed, { deviceId: DEVICE, nodeId: 'node-new' }).state, 'trusted')
})

test('maskNodeId keeps only the first eight characters', () => {
  assert.equal(maskNodeId(NODE), 'node-aaa…')
  assert.equal(maskNodeId(''), '')
  assert.equal(maskNodeId(undefined), '')
})
