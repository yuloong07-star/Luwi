// test/luwi-inbox.test.mjs — Luwi 加 (G1-2 队列退路) 的落盘形状与读回。
//
// 钉住四件事:
//   1. 路径: inbox/<目标id>/<消息id>.json 与 inbox-ack/<寄件id>/<消息id>.json, 段一律过路径闸
//   2. 形状: buildMessage / buildAck 的字段, parseMessage / parseAck 的拒收面
//   3. 读回: 坏文件与坏形状都跳过 (坏形状不算"收到了"), 目录不在就是空
//   4. 幂等: rememberId 去重并按上限丢最老的
// @module luwi-dsh-sync/test/luwi-inbox.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  ackDirectory,
  buildAck,
  buildMessage,
  inboxDirectory,
  inboxFileName,
  parseAck,
  parseMessage,
  readAcks,
  readInbox,
  rememberId,
  writeJsonFile,
} from '../lib/luwi-inbox.mjs'

test('the queue and the receipt live at the repo top level, with traversal-proof segments', () => {
  assert.equal(inboxDirectory('/repo', 'dev-1'), join('/repo', 'inbox', 'dev-1'))
  assert.equal(ackDirectory('/repo', 'dev-1'), join('/repo', 'inbox-ack', 'dev-1'))
  assert.ok(!inboxDirectory('/repo', '../../etc').includes('..'), 'a device id cannot walk out of the repo')
  assert.equal(inboxDirectory('/repo', ''), join('/repo', 'inbox', 'unknown'))
  const traversing = inboxFileName('m/../1')
  assert.ok(!traversing.includes('/'), 'a separator never survives into a file name')
  assert.ok(!traversing.includes('\\'))
  assert.equal(inboxFileName(''), 'unknown.json')
})

test('a queued message round-trips and rejects the shapes that carry no text', () => {
  const message = buildMessage({
    id: 'm1',
    from: 'dev-a',
    fromName: 'PC',
    text: '帮我看看',
    target: { kind: 'workspace', id: 'w1', name: 'DSH' },
    createdAt: 123,
  })
  assert.deepEqual(message, {
    v: 1,
    id: 'm1',
    from: 'dev-a',
    fromName: 'PC',
    text: '帮我看看',
    target: { kind: 'workspace', id: 'w1', name: 'DSH' },
    createdAt: 123,
  })
  assert.deepEqual(parseMessage(message), message)
  assert.equal(parseMessage({ id: 'm1', text: '   ' }), null, 'a blank body is not a message')
  assert.equal(parseMessage({ text: 'x' }), null, 'no id, no message')
  assert.equal(parseMessage('nope'), null)
  const loose = parseMessage({ id: ' m2 ', text: 'x' })
  assert.equal(loose.id, 'm2', 'ids are trimmed')
  assert.deepEqual(loose.target, { kind: 'session', id: '' }, 'an unknown target kind falls back to session')
})

test('a receipt round-trips and needs an id', () => {
  const ack = buildAck({ id: 'm1', sessionId: 'session-x', running: true, at: 5 })
  assert.deepEqual(ack, { v: 1, id: 'm1', sessionId: 'session-x', running: true, at: 5 })
  assert.deepEqual(parseAck(ack), ack)
  const failed = buildAck({ id: 'm2', at: 6, error: 'boom' })
  assert.equal(failed.error, 'boom')
  assert.equal(parseAck({ sessionId: 'session-x' }), null)
  assert.equal(parseAck(undefined), null)
})

test('reading the queue skips broken files, keeps the good ones, and orders by time', async () => {
  const root = mkdtempSync(join(tmpdir(), 'lw-inbox-'))
  try {
    const directory = inboxDirectory(root, 'dev-me')
    await writeJsonFile(join(directory, 'b.json'), buildMessage({ id: 'b', text: 'second', createdAt: 20 }))
    await writeJsonFile(join(directory, 'a.json'), buildMessage({ id: 'a', text: 'first', createdAt: 10 }))
    // 坏 JSON 与"读得懂但没有正文"两种都要跳过
    await writeJsonFile(join(directory, 'c.json'), { id: 'c' })
    const { writeFileSync } = await import('node:fs')
    writeFileSync(join(directory, 'd.json'), '{ not json')
    const { messages, broken } = await readInbox(directory)
    assert.deepEqual(messages.map(message => message.id), ['a', 'b'])
    assert.equal(broken, 2)
    const missing = await readInbox(join(root, 'nowhere'))
    assert.deepEqual(missing, { messages: [], broken: 0 })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('reading receipts keys them by message id and ignores junk', async () => {
  const root = mkdtempSync(join(tmpdir(), 'lw-ack-'))
  try {
    const directory = ackDirectory(root, 'dev-a')
    await writeJsonFile(join(directory, 'm1.json'), buildAck({ id: 'm1', sessionId: 's1', at: 1 }))
    await writeJsonFile(join(directory, 'm2.json'), { nope: true })
    const acks = await readAcks(directory)
    assert.deepEqual([...acks.keys()], ['m1'])
    assert.equal(acks.get('m1').sessionId, 's1')
    assert.equal((await readAcks(join(root, 'nowhere'))).size, 0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the delivered ledger dedupes, keeps order, and drops the oldest past its cap', () => {
  let ledger = rememberId([], 'a')
  ledger = rememberId(ledger, 'b')
  assert.deepEqual(ledger, ['a', 'b'])
  ledger = rememberId(ledger, 'a')
  assert.deepEqual(ledger, ['b', 'a'], 're-remembering moves the id to the end instead of duplicating it')
  let capped = []
  for (let index = 0; index < 5; index += 1) capped = rememberId(capped, `m${index}`, 3)
  assert.deepEqual(capped, ['m2', 'm3', 'm4'])
  assert.deepEqual(rememberId(['x', 42, null], 'y'), ['x', 'y'], 'junk entries are dropped')
})

test('the atomic writer leaves no temp file behind', async () => {
  const root = mkdtempSync(join(tmpdir(), 'lw-write-'))
  try {
    const file = join(root, 'nested', 'deep', 'one.json')
    await writeJsonFile(file, { hello: 'world' })
    assert.ok(existsSync(file))
    assert.ok(!existsSync(`${file}.tmp`))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
