// test/luwi-transcript.test.mjs — 对话账 conv/ (2026-10-22, 方案文档 §5.4)。
// 覆盖: 追加与幂等 / 只有最末一块会变 / 块切分 / meta 自校验 / 游标过滤 / 坏条目被挡 /
// 超长正文截断 / 目录名清洗, 以及"live 是窗口, 对话账是整本"那一对口径。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { collectLiveEntries, condense } from '../lib/luwi-live.mjs'
import {
  TRANSCRIPT_CHUNK_ENTRIES,
  TRANSCRIPT_MAX_TEXT_BYTES,
  TRANSCRIPT_SEED_ENTRIES,
  appendTranscriptEntries,
  chunkFileName,
  readTranscriptEntries,
  transcriptDir,
  transcriptDirRel,
  verifyTranscript,
} from '../lib/luwi-transcript.mjs'

async function makeTemp() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'luwi-transcript-'))
  return { root, clean: () => fs.rm(root, { recursive: true, force: true }) }
}

/** 造 n 条条目 (seq 从 from 起, 交替 user/assistant) */
function entriesOf(count, from = 1) {
  const out = []
  for (let at = 0; at < count; at += 1) {
    const seq = from + at
    out.push({ seq, role: seq % 2 === 1 ? 'user' : 'assistant', text: `第 ${seq} 条`, at: 1_800_000_000_000 + seq })
  }
  return out
}

test('appending the same entries twice changes nothing (idempotent by seq)', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const dir = transcriptDir(root, 'dev-me', 's1')
  const first = await appendTranscriptEntries(dir, entriesOf(3), { deviceId: 'dev-me', sessionId: 's1', title: '问候', cwd: '/work' })
  assert.equal(first.appended, 3)
  assert.equal(first.entries, 3)
  assert.equal(first.chunks, 1)
  const again = await appendTranscriptEntries(dir, entriesOf(3), { deviceId: 'dev-me', sessionId: 's1' })
  assert.equal(again.appended, 0, '同一条 seq 不会再进一次')
  assert.equal(again.entries, 3)
  const verified = await verifyTranscript(dir)
  assert.equal(verified.ok, true, JSON.stringify(verified.errors))
  assert.equal(verified.meta.sessionId, 's1')
  assert.equal(verified.meta.title, '问候')
  assert.equal(await fs.readFile(path.join(dir, ...chunkFileName(0).split('/')), 'utf8').then(text => text.trim().split('\n').length), 3)
})

test('only the tail chunk is rewritten: sealed chunks stay byte-identical', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const dir = transcriptDir(root, 'dev-me', 's1')
  await appendTranscriptEntries(dir, entriesOf(TRANSCRIPT_CHUNK_ENTRIES + 2), { deviceId: 'dev-me', sessionId: 's1' })
  const sealed = await fs.readFile(path.join(dir, ...chunkFileName(0).split('/')))
  const second = await appendTranscriptEntries(dir, entriesOf(5, TRANSCRIPT_CHUNK_ENTRIES + 3), { deviceId: 'dev-me', sessionId: 's1' })
  assert.equal(second.appended, 5)
  assert.equal(second.chunks, 2, '16 条一块: 23 条落在 2 块里')
  const after = await fs.readFile(path.join(dir, ...chunkFileName(0).split('/')))
  assert.deepEqual(after, sealed, '封好的第一块一个字节都没变')
  const verified = await verifyTranscript(dir)
  assert.equal(verified.ok, true, JSON.stringify(verified.errors))
  const read = await readTranscriptEntries(dir)
  assert.equal(read.entries.length, TRANSCRIPT_CHUNK_ENTRIES + 2 + 5)
  assert.equal(read.entries[0].seq, 1)
  assert.equal(read.entries[read.entries.length - 1].seq, TRANSCRIPT_CHUNK_ENTRIES + 7)
})

test('the cursor only returns entries after it, and bad entries never land', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const dir = transcriptDir(root, 'dev-me', 's1')
  const good = entriesOf(4)
  const result = await appendTranscriptEntries(dir, [
    ...good,
    { seq: 0, role: 'user', text: 'no seq' },
    { seq: 99, role: 'system', text: 'not a dialogue role' },
    { seq: 100, role: 'assistant', text: '   ' },
  ], { deviceId: 'dev-me', sessionId: 's1' })
  assert.equal(result.appended, 4, '坏条目一条都不进账')
  const read = await readTranscriptEntries(dir, { afterSeq: 2 })
  assert.deepEqual(read.entries.map(entry => entry.seq), [3, 4])
  assert.equal(read.meta.entries, 4)
  assert.equal(read.meta.lastSeq, 4)
})

test('an over-long entry is clipped to the byte cap', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const dir = transcriptDir(root, 'dev-me', 's1')
  await appendTranscriptEntries(dir, [
    { seq: 1, role: 'user', text: '长'.repeat(TRANSCRIPT_MAX_TEXT_BYTES) },
  ], { deviceId: 'dev-me', sessionId: 's1' })
  const read = await readTranscriptEntries(dir)
  assert.ok(read.entries.length === 1)
  assert.ok(Buffer.byteLength(read.entries[0].text, 'utf8') <= TRANSCRIPT_MAX_TEXT_BYTES)
  assert.ok(read.entries[0].text.length > 0)
})

test('the first write seeds only the recent stretch, later appends continue forward', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const dir = transcriptDir(root, 'dev-me', 'long')
  const first = await appendTranscriptEntries(dir, entriesOf(300, 1), { deviceId: 'dev-me', sessionId: 'long' })
  // 只种最近 256 条 (45..300), 一次写 16 块而不是 19 块
  assert.equal(first.appended, TRANSCRIPT_SEED_ENTRIES)
  assert.equal(first.entries, TRANSCRIPT_SEED_ENTRIES)
  assert.equal(first.chunks, TRANSCRIPT_SEED_ENTRIES / TRANSCRIPT_CHUNK_ENTRIES)
  const read = await readTranscriptEntries(dir)
  assert.equal(read.entries[0].seq, 300 - TRANSCRIPT_SEED_ENTRIES + 1)
  const more = await appendTranscriptEntries(dir, entriesOf(2, 301), { deviceId: 'dev-me', sessionId: 'long' })
  assert.equal(more.appended, 2)
  assert.equal((await verifyTranscript(dir)).ok, true)
})

test('verify catches a tampered chunk (meta sha256 is the anchor)', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const dir = transcriptDir(root, 'dev-me', 's1')
  await appendTranscriptEntries(dir, entriesOf(3), { deviceId: 'dev-me', sessionId: 's1' })
  assert.equal((await verifyTranscript(dir)).ok, true)
  const file = path.join(dir, ...chunkFileName(0).split('/'))
  await fs.writeFile(file, `${JSON.stringify({ seq: 1, at: 1, role: 'user', text: '被改过' })}\n`)
  const verified = await verifyTranscript(dir)
  assert.equal(verified.ok, false)
  assert.ok(verified.errors.some(error => error.includes('sha256')), verified.errors.join('; '))
})

test('transcript paths sanitize both segments', () => {
  assert.equal(transcriptDirRel('dev me', '../../../etc'), 'conv/devme/etc')
  assert.equal(transcriptDirRel('', ''), 'conv/unknown/unknown')
  assert.equal(chunkFileName(0), 'chunks/000000-000015.jsonl')
  assert.equal(chunkFileName(3), 'chunks/000048-000063.jsonl')
})

test('the live window is a slice of the whole dialogue the transcript keeps', () => {
  const events = []
  let seq = 0
  for (let round = 1; round <= 40; round += 1) {
    events.push({ type: 'user/message', seq: ++seq, time: round, data: { content: [{ type: 'text', text: `u${round}` }], source: { kind: 'user' } } })
    events.push({ type: 'assistant/message', seq: ++seq, time: round, data: { message: { content: [{ type: 'text', text: `a${round}` }] } } })
  }
  const windowed = condense(events, { maxEntries: 10, maxChars: 16000, running: false })
  assert.equal(windowed.entries.length, 10)
  assert.equal(windowed.truncated, true)
  // 窗口之外的那些只有 all (对话账) 里还在
  assert.equal(windowed.all.length, 80)
  assert.deepEqual(collectLiveEntries(events, { running: false }).entries, windowed.all)
  assert.equal(windowed.entries[windowed.entries.length - 1].seq, 80)
})
