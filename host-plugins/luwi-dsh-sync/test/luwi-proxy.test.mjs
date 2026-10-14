// test/luwi-proxy.test.mjs — Luwi 加 (G1-2 投影会话) 的事件折叠。
//
// 钉住五件事:
//   1. 一条用户消息开一轮、一条回答收一轮; surface 事件带 surfaceOp 的标记, 别的带的是 false
//   2. 幂等: 只追加 seq 更大的; 同一批重放不产生重复事件
//   3. 本地先追加过的那一句 (pendingTexts) 在对端回声里被认掉一次, 不重复落
//   4. 开着的轮 (openTurn) 与轮号跨轮保存; 一批被截断时游标不前移 (留下一次补)
//   5. 没送到的那一句落一条本地通知 (本插件自己的 source kind, 不调模型)
// @module luwi-dsh-sync/test/luwi-proxy.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { appendProxyEvents, proxyEventsFrom, proxyNoticeEvents, proxyTitleOf, proxyUserMessage } from '../lib/luwi-proxy.mjs'

/** 对端条目的简写 */
const entry = (seq, role, text) => ({ seq, role, text, at: seq })

test('a user entry opens a turn and an assistant entry closes it', () => {
  const first = proxyEventsFrom([entry(1, 'user', '问一句')])
  assert.deepEqual(first.events.map(event => event.type), ['turn/start', 'user/message'])
  assert.equal(first.openTurn, 1)
  assert.equal(first.events[0].surface, false)
  assert.equal(first.events[1].surface, true, 'user/message is a surface event')
  assert.equal(first.events[1].data.source.via, 'peer')
  assert.equal(first.events[1].data.source.kind, 'user', 'the bubble has to read as the human speaking')

  const second = proxyEventsFrom([entry(2, 'assistant', '答一句')], first)
  assert.deepEqual(second.events.map(event => event.type), ['assistant/message', 'turn/end'])
  assert.equal(second.events[0].data.turn, 1)
  assert.equal(second.events[0].surface, true)
  assert.equal(second.events[1].surface, false)
  assert.equal(second.openTurn, null)
  assert.equal(second.turnCounter, 1)
})

test('an assistant entry with no open turn opens one of its own', () => {
  const result = proxyEventsFrom([entry(7, 'assistant', '没有问题的回答')])
  assert.deepEqual(result.events.map(event => event.type), ['turn/start', 'assistant/message', 'turn/end'])
  assert.equal(result.turnCounter, 1)
  assert.equal(result.openTurn, null)
})

test('replaying the same entries adds nothing, and only larger seqs move the cursor', () => {
  const first = proxyEventsFrom([entry(1, 'user', 'a'), entry(2, 'assistant', 'b')])
  assert.equal(first.applied, 2)
  assert.equal(first.lastSeq, 2)
  const replay = proxyEventsFrom([entry(1, 'user', 'a'), entry(2, 'assistant', 'b')], first)
  assert.deepEqual(replay.events, [])
  assert.equal(replay.lastSeq, 2)
  const mixed = proxyEventsFrom([entry(2, 'assistant', 'b'), entry(3, 'user', 'c')], replay)
  assert.deepEqual(mixed.events.map(event => event.type), ['turn/start', 'user/message'])
  assert.equal(mixed.lastSeq, 3, 'a smaller seq never moves the cursor')
})

test('the text we already appended locally is recognized once when the peer echoes it', () => {
  const local = proxyUserMessage('在 PC 上打的这一句', 1)
  assert.equal(local.type, 'user/message')
  assert.equal(local.surface, true)
  assert.equal(local.data.turn, undefined, 'the local append rides the turn the host already opened')
  const state = { lastSeq: 0, openTurn: null, turnCounter: 1, pendingTexts: ['在 PC 上打的这一句'] }
  const applied = proxyEventsFrom([entry(1, 'user', '在 PC 上打的这一句'), entry(2, 'assistant', '手机那边的回答')], state)
  assert.deepEqual(applied.events.map(event => event.type), ['turn/start', 'assistant/message', 'turn/end'], 'the echo does not become a second user bubble')
  assert.equal(applied.skipped, 1)
  assert.deepEqual(applied.pendingTexts, [])
})

test('a batch that does not fit moves no cursor past what it emitted', () => {
  const many = [entry(1, 'user', 'a'), entry(2, 'assistant', 'b'), entry(3, 'user', 'c')]
  const result = proxyEventsFrom(many, {}, 2)
  assert.equal(result.truncated, true)
  assert.equal(result.applied, 1, 'only the first entry made it in')
  assert.deepEqual(result.events.map(event => event.type), ['turn/start', 'user/message'])
  assert.equal(result.lastSeq, 1, 'the cursor stops at what was emitted, so nothing is lost')
  const next = proxyEventsFrom(many, result, 8)
  assert.deepEqual(next.events.map(event => event.type), ['assistant/message', 'turn/end', 'turn/start', 'user/message'])
})

test('malformed entries are ignored instead of breaking the fold', () => {
  const result = proxyEventsFrom([null, { seq: 1 }, { seq: 2, role: 'system', text: 'x' }, { seq: 3, role: 'user', text: '' }, entry(4, 'user', 'ok')])
  assert.deepEqual(result.events.map(event => event.type), ['turn/start', 'user/message'])
  assert.equal(result.lastSeq, 4)
  assert.deepEqual(proxyEventsFrom(undefined).events, [])
  assert.deepEqual(proxyEventsFrom('nope').events, [])
})

test('a missing answer still says so in the session log without waking a model', () => {
  const notice = proxyNoticeEvents('the peer is unreachable', 'Luwi 手机')
  assert.equal(notice.length, 1)
  assert.equal(notice[0].type, 'user/message')
  assert.equal(notice[0].surface, true)
  assert.equal(notice[0].data.source.kind, 'dsh-session-sync')
  assert.match(notice[0].data.content[0].text, /没有送到 Luwi 手机/u)
})

test('the projection title follows the peer session, with a fallback', () => {
  assert.equal(proxyTitleOf('修复登录', 'session-1234'), '修复登录')
  assert.equal(proxyTitleOf('  ', 'session-abcd1234'), '对端会话 abcd1234')
  assert.equal(proxyTitleOf(undefined, ''), '对端会话 ?')
})

test('appending stops at the first event the host refuses, without throwing', () => {
  const appended = []
  const session = {
    append(type, data, opts) {
      if (type === 'turn/end') throw new Error('nope')
      appended.push([type, data.turn, opts?.surfaceOp === 'append'])
    },
  }
  const count = appendProxyEvents(session, [
    { type: 'turn/start', data: { turn: 1 }, surface: false },
    { type: 'user/message', data: { turn: 1 }, surface: true },
    { type: 'turn/end', data: { turn: 1 }, surface: false },
    { type: 'turn/start', data: { turn: 2 }, surface: false },
  ])
  assert.equal(count, 2)
  assert.deepEqual(appended, [['turn/start', 1, false], ['user/message', 1, true]])
})
