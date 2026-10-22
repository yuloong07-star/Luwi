// test/p2p-manifest.test.mjs — 根清单、集合行、分页与差分（方案 §5.4 / §6.2 / §13）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import {
  buildRootManifest,
  canonicalPath,
  diffManifest,
  digestOfEntries,
  headsOf,
  localDigestMap,
} from '../lib/p2p-manifest.mjs'
import { appendTranscriptEntries } from '../lib/luwi-transcript.mjs'
import { sha256Of } from '../lib/p2p-apply.mjs'

async function tempRepo(t) {
  const root = await fs.mkdtemp(join(os.tmpdir(), 'lw-p2p-manifest-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

test('canonicalPath normalises separators, trailing slashes and case', () => {
  assert.equal(canonicalPath('Live/A/S.JSON'), 'live/a/s.json')
  assert.equal(canonicalPath('conv/A/S/'), 'conv/a/s')
  assert.equal(canonicalPath('../x'), null)
})

test('buildRootManifest lists live files with size and sha, and its digest is order-independent', async (t) => {
  const repo = await tempRepo(t)
  await fs.mkdir(join(repo, 'live', 'peer'), { recursive: true })
  await fs.writeFile(join(repo, 'live', 'peer', 'a.json'), 'aaa')
  await fs.writeFile(join(repo, 'live', 'peer', 'b.json'), 'bbbb')

  const manifest = await buildRootManifest({ repoDir: repo, root: 'live' })
  assert.equal(manifest.root, 'live')
  assert.deepEqual(manifest.entries.map(entry => entry[0]).sort(), ['live/peer/a.json', 'live/peer/b.json'])
  const a = manifest.entries.find(entry => entry[0].endsWith('a.json'))
  assert.equal(a[1], 3)
  assert.equal(a[2], sha256Of(Buffer.from('aaa')))
  assert.equal(manifest.digest, digestOfEntries([...manifest.entries].reverse()))
  assert.equal(manifest.next, undefined)
})

test('buildRootManifest gives one conv row per session and pages with next', async (t) => {
  const repo = await tempRepo(t)
  for (const session of ['s1', 's2']) {
    await appendTranscriptEntries(join(repo, 'conv', 'peer', session), [
      { seq: 1, role: 'user', text: `hello ${session}` },
    ], { deviceId: 'peer', sessionId: session })
  }
  const manifest = await buildRootManifest({ repoDir: repo, root: 'conv' })
  assert.deepEqual(manifest.entries.map(entry => entry[0]), ['conv/peer/s1/', 'conv/peer/s2/'])
  const meta = JSON.parse(await fs.readFile(join(repo, 'conv', 'peer', 's1', 'meta.json'), 'utf8'))
  const row = manifest.entries[0]
  assert.equal(row[1], meta.bytes)
  assert.equal(row[2], meta.sha256)

  const page = await buildRootManifest({ repoDir: repo, root: 'conv', pageLimit: 1 })
  assert.equal(page.entries.length, 1)
  assert.equal(page.next, '1')
  const next = await buildRootManifest({ repoDir: repo, root: 'conv', pageLimit: 1, cursor: page.next })
  assert.deepEqual(next.entries.map(entry => entry[0]), ['conv/peer/s2/'])
  assert.equal(next.next, undefined)
  // digest 覆盖整本，不是当前页
  assert.equal(page.digest, manifest.digest)
})

test('buildRootManifest with a deviceId only lists what that device may send', async (t) => {
  const repo = await tempRepo(t)
  await fs.mkdir(join(repo, 'live', 'me'), { recursive: true })
  await fs.mkdir(join(repo, 'live', 'peer'), { recursive: true })
  await fs.writeFile(join(repo, 'live', 'me', 'a.json'), 'a')
  await fs.writeFile(join(repo, 'live', 'peer', 'b.json'), 'b')
  const manifest = await buildRootManifest({ repoDir: repo, root: 'live', deviceId: 'me' })
  assert.deepEqual(manifest.entries.map(entry => entry[0]), ['live/me/a.json'])
})

test('diffManifest reports missing, changed and unchanged; paths compare case-insensitively', async (t) => {
  const repo = await tempRepo(t)
  await fs.mkdir(join(repo, 'live', 'peer'), { recursive: true })
  await fs.writeFile(join(repo, 'live', 'peer', 'same.json'), 'same')
  await fs.writeFile(join(repo, 'live', 'peer', 'changed.json'), 'new')
  const local = await localDigestMap({ repoDir: repo, root: 'live' })

  const remoteEntries = [
    ['Live/Peer/Same.json', 4, sha256Of(Buffer.from('same'))],
    ['live/peer/changed.json', 3, sha256Of(Buffer.from('old'))],
    ['live/peer/missing.json', 1, sha256Of(Buffer.from('x'))],
    ['conv/peer/s1/', 100, 'f'.repeat(64)],
  ]
  const diff = diffManifest({ remoteEntries, local })
  assert.deepEqual(diff.missing, ['live/peer/missing.json', 'conv/peer/s1/'])
  assert.deepEqual(diff.changed, ['live/peer/changed.json'])
  assert.deepEqual(diff.need, ['live/peer/changed.json', 'live/peer/missing.json', 'conv/peer/s1/'])
})

test('headsOf folds manifests into the HEADS body', async (t) => {
  const repo = await tempRepo(t)
  await fs.mkdir(join(repo, 'live', 'peer'), { recursive: true })
  await fs.writeFile(join(repo, 'live', 'peer', 'a.json'), 'abc')
  const live = await buildRootManifest({ repoDir: repo, root: 'live' })
  const heads = headsOf({ manifests: [live] })
  assert.equal(heads.roots.length, 1)
  assert.equal(heads.roots[0].root, 'live')
  assert.equal(heads.roots[0].files, 1)
  assert.equal(heads.roots[0].bytes, 3)
  assert.equal(heads.roots[0].digest, live.digest)
})
