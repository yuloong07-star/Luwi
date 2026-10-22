// test/p2p-apply.test.mjs — 作者规则、路径穿越、单文件原子写、集合整组落、墓碑与 trash
// （方案 §6.1~§6.3 / §13 的 p2p-apply 一行）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import {
  applyFile,
  applyTombstones,
  classifyPath,
  finalizeSet,
  isCollectionPath,
  mayReceive,
  maySend,
  normalizeP2pPath,
  parseTombstones,
  readDeletedPaths,
  sha256Of,
} from '../lib/p2p-apply.mjs'
import { appendTranscriptEntries, verifyTranscript } from '../lib/luwi-transcript.mjs'
import { SyncError } from '../lib/errors.mjs'

const ME = 'device-me'
const PEER = 'device-peer'

async function tempRepo(t) {
  const root = await fs.mkdtemp(join(os.tmpdir(), 'lw-p2p-apply-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

test('normalizeP2pPath refuses absolute paths, dot segments, NUL and empty input', () => {
  assert.equal(normalizeP2pPath('conv/a/s/chunks/000000-000015.jsonl'), 'conv/a/s/chunks/000000-000015.jsonl')
  assert.equal(normalizeP2pPath('live\\a\\s.json'), 'live/a/s.json')
  for (const bad of ['/etc/passwd', 'C:/x', 'conv/../x', 'conv/a/./x', 'conv//a', '', 'a\0b', 42, null]) {
    assert.equal(normalizeP2pPath(bad), null, `expected null for ${JSON.stringify(bad)}`)
  }
})

test('classifyPath maps every root and keeps sessions out of P2P', () => {
  assert.deepEqual(classifyPath('conv/p1/s1/meta.json'), { path: 'conv/p1/s1/meta.json', root: 'conv', owner: 'p1', session: 's1', p2p: true })
  assert.equal(classifyPath('live/p1/s1.json').owner, 'p1')
  assert.equal(classifyPath('peers/p1.json').owner, 'p1')
  assert.equal(classifyPath('tombstones/p1.json').owner, 'p1')
  assert.equal(classifyPath('inbox/me/m1.json').owner, 'me')
  assert.equal(classifyPath('inbox-ack/me/m1.json').owner, 'me')
  assert.equal(classifyPath('sessions/x.jsonl').p2p, false)
  assert.equal(classifyPath('other/x'), null)
  assert.equal(classifyPath('conv/p1'), null, 'a conv set needs a session segment')
  assert.equal(classifyPath('../conv/p1/s1/x'), null)
})

test('maySend: mailboxes are writable by anyone, owned roots only by their author', () => {
  assert.equal(maySend('inbox/peer/m1.json', ME), true)
  assert.equal(maySend('inbox-ack/peer/m1.json', ME), true)
  assert.equal(maySend(`live/${ME}/s.json`, ME), true)
  assert.equal(maySend(`live/${PEER}/s.json`, ME), false)
  assert.equal(maySend(`conv/${ME}/s1/chunks/x.jsonl`, ME), true)
  assert.equal(maySend(`conv/${PEER}/s1/chunks/x.jsonl`, ME), false)
  assert.equal(maySend(`tombstones/${ME}.json`, ME), true)
  assert.equal(maySend(`peers/${ME}.json`, ME), true)
  assert.equal(maySend('sessions/x.jsonl', ME), false)
  assert.equal(maySend('evil/x', ME), false)
  assert.equal(maySend('live//s.json', ME), false)
})

test('mayReceive: inbox is addressed to me, owned roots must belong to the peer', () => {
  const receive = path => mayReceive(path, { myDeviceId: ME, peerDeviceId: PEER })
  assert.equal(receive(`inbox/${ME}/m1.json`), true)
  assert.equal(receive('inbox/other/m1.json'), false)
  assert.equal(receive(`inbox-ack/${ME}/m1.json`), true)
  assert.equal(receive(`live/${PEER}/s.json`), true)
  assert.equal(receive(`live/${ME}/s.json`), false)
  assert.equal(receive(`conv/${PEER}/s1/meta.json`), true)
  assert.equal(receive(`conv/${ME}/s1/meta.json`), false)
  assert.equal(receive(`tombstones/${PEER}.json`), true)
  assert.equal(receive('sessions/x.jsonl'), false)
  assert.equal(mayReceive(`inbox/${ME}/m1.json`, { myDeviceId: ME, peerDeviceId: '' }), false)
})

test('isCollectionPath only accepts conv sets', () => {
  assert.equal(isCollectionPath(`conv/${PEER}/s1/`), true)
  assert.equal(isCollectionPath(`conv/${PEER}/s1`), true)
  assert.equal(isCollectionPath(`live/${PEER}/s1.json`), false)
  assert.equal(isCollectionPath('conv/'), false)
})

test('applyFile writes atomically, skips identical content and verifies sha256', async (t) => {
  const repo = await tempRepo(t)
  const path = 'live/peer/s1.json'
  const body = '{"a":1}\n'
  const first = await applyFile({ repoDir: repo, path, body, sha256: sha256Of(Buffer.from(body)) })
  assert.equal(first.ok, true)
  assert.equal(first.skipped, false)
  assert.equal(await fs.readFile(join(repo, 'live', 'peer', 's1.json'), 'utf8'), body)

  const second = await applyFile({ repoDir: repo, path, body })
  assert.equal(second.skipped, true)

  await assert.rejects(
    applyFile({ repoDir: repo, path, body, sha256: 'f'.repeat(64) }),
    (error) => error instanceof SyncError && error.code === 'P2P_INTEGRITY',
  )
  await assert.rejects(
    applyFile({ repoDir: repo, path: '../escape.json', body }),
    (error) => error instanceof SyncError && error.code === 'PATH_UNSAFE',
  )
})

test('applyFile honours an alreadyKnown short-circuit', async (t) => {
  const repo = await tempRepo(t)
  const result = await applyFile({ repoDir: repo, path: 'peers/peer.json', body: '{}', alreadyKnown: () => true })
  assert.equal(result.skipped, true)
  await assert.rejects(fs.stat(join(repo, 'peers', 'peer.json')))
})

test('finalizeSet verifies the whole set, then moves it in; a failed verify leaves the mirror untouched', async (t) => {
  const repo = await tempRepo(t)
  const tmp = join(repo, '.p2p', 'tmp', 'set1')
  await appendTranscriptEntries(tmp, [
    { seq: 1, role: 'user', text: 'hello' },
    { seq: 2, role: 'assistant', text: 'hi' },
    { seq: 3, role: 'user', text: 'again' },
  ], { deviceId: PEER, sessionId: 's1' })
  assert.equal((await verifyTranscript(tmp)).ok, true)

  const setPath = `conv/${PEER}/s1`
  const first = await finalizeSet({ repoDir: repo, setPath, tmpDir: tmp, verify: verifyTranscript, now: Date.UTC(2026, 9, 22) })
  assert.equal(first.ok, true)
  assert.deepEqual((await fs.readdir(join(repo, 'conv', PEER, 's1'))).sort(), ['chunks', 'meta.json'])

  // 第二版: 旧的那一份先进 trash, 新的一份落进去
  const tmp2 = join(repo, '.p2p', 'tmp', 'set2')
  await appendTranscriptEntries(tmp2, [
    { seq: 1, role: 'user', text: 'hello' },
    { seq: 2, role: 'assistant', text: 'hi' },
    { seq: 3, role: 'user', text: 'again' },
    { seq: 4, role: 'assistant', text: 'newer' },
  ], { deviceId: PEER, sessionId: 's1' })
  const second = await finalizeSet({ repoDir: repo, setPath, tmpDir: tmp2, verify: verifyTranscript, now: Date.UTC(2026, 9, 22) })
  assert.equal(second.ok, true)
  const trashed = join(repo, '.p2p', 'trash', '2026-10-22', 'conv', PEER, 's1', 'meta.json')
  assert.equal((await fs.stat(trashed)).isFile(), true)
  assert.match(await fs.readFile(join(repo, 'conv', PEER, 's1', 'chunks', '000000-000015.jsonl'), 'utf8'), /newer/u)

  // 校验不过: 镜像里那份保持原样
  const before = await fs.readFile(join(repo, 'conv', PEER, 's1', 'meta.json'), 'utf8')
  const badTmp = join(repo, '.p2p', 'tmp', 'bad')
  await fs.mkdir(badTmp, { recursive: true })
  const failed = await finalizeSet({
    repoDir: repo,
    setPath,
    tmpDir: badTmp,
    verify: async () => ({ ok: false, errors: ['sha256 does not match'] }),
  })
  assert.equal(failed.ok, false)
  assert.deepEqual(failed.errors, ['sha256 does not match'])
  assert.equal(await fs.readFile(join(repo, 'conv', PEER, 's1', 'meta.json'), 'utf8'), before)
})

test('parseTombstones accepts JSON arrays and JSONL, dropping unsafe entries', () => {
  const fromArray = parseTombstones(JSON.stringify([
    { path: `live/${PEER}/s.json`, at: 1 },
    { path: '../escape', at: 2 },
    { path: 'sessions/x.jsonl', at: 3 },
    'garbage',
  ]))
  assert.deepEqual(fromArray.entries, [{ path: `live/${PEER}/s.json`, at: 1 }])
  assert.equal(fromArray.badLines, 3)

  const fromLines = parseTombstones([
    JSON.stringify({ path: `conv/${PEER}/s1/x.jsonl`, at: 5 }),
    JSON.stringify({ path: `peers/${PEER}.json`, at: 6 }),
    'not json',
  ].join('\n'))
  assert.deepEqual(fromLines.entries.map(entry => entry.path), [`conv/${PEER}/s1/x.jsonl`, `peers/${PEER}.json`])
  assert.equal(fromLines.badLines, 1)
  assert.deepEqual(parseTombstones(''), { entries: [], badLines: 0 })
})

test('applyTombstones moves peer-owned paths to trash, records them, and refuses the mailbox', async (t) => {
  const repo = await tempRepo(t)
  const now = Date.UTC(2026, 9, 22, 12)
  await fs.mkdir(join(repo, 'live', PEER), { recursive: true })
  await fs.writeFile(join(repo, 'live', PEER, 's1.json'), '{"x":1}')
  await fs.mkdir(join(repo, 'conv', PEER, 's1', 'chunks'), { recursive: true })
  await fs.writeFile(join(repo, 'conv', PEER, 's1', 'meta.json'), '{}')
  await fs.mkdir(join(repo, 'inbox', ME), { recursive: true })
  await fs.writeFile(join(repo, 'inbox', ME, 'm1.json'), '{}')
  await fs.mkdir(join(repo, 'live', ME), { recursive: true })
  await fs.writeFile(join(repo, 'live', ME, 'mine.json'), '{}')

  const result = await applyTombstones({
    repoDir: repo,
    peerDeviceId: PEER,
    entries: [
      { path: `live/${PEER}/s1.json`, at: 1 },
      { path: `conv/${PEER}/s1/`, at: 2 },
      { path: `inbox/${ME}/m1.json`, at: 3 },
      { path: `live/${ME}/mine.json`, at: 4 },
      { path: `live/${PEER}/absent.json`, at: 5 },
    ],
    now,
  })
  assert.deepEqual(result.deleted.sort(), [`conv/${PEER}/s1`, `live/${PEER}/s1.json`].sort())
  assert.equal(result.warnings.some(warning => /mailbox/u.test(warning)), true)
  assert.equal(result.warnings.some(warning => /author mismatch/u.test(warning)), true)
  assert.equal(await fs.stat(join(repo, 'live', PEER, 's1.json')).catch(() => null), null)
  assert.equal((await fs.stat(join(repo, '.p2p', 'trash', '2026-10-22', 'live', PEER, 's1.json'))).isFile(), true)
  assert.equal((await fs.stat(join(repo, 'inbox', ME, 'm1.json'))).isFile(), true)
  assert.equal((await fs.stat(join(repo, 'live', ME, 'mine.json'))).isFile(), true)
  assert.deepEqual(await readDeletedPaths(repo), [`conv/${PEER}/s1`, `live/${PEER}/s1.json`].sort())
})

test('applyTombstones enforces its per-round budget', async (t) => {
  const repo = await tempRepo(t)
  for (const name of ['a', 'b']) {
    await fs.mkdir(join(repo, 'live', PEER), { recursive: true })
    await fs.writeFile(join(repo, 'live', PEER, `${name}.json`), '{}')
  }
  const result = await applyTombstones({
    repoDir: repo,
    peerDeviceId: PEER,
    entries: [
      { path: `live/${PEER}/a.json`, at: 1 },
      { path: `live/${PEER}/b.json`, at: 2 },
    ],
    limits: { maxPaths: 1, maxBytes: 1024 },
  })
  assert.deepEqual(result.deleted, [`live/${PEER}/a.json`])
  assert.equal(result.warnings.some(warning => /budget reached/u.test(warning)), true)
  assert.equal((await fs.stat(join(repo, 'live', PEER, 'b.json'))).isFile(), true)
})
