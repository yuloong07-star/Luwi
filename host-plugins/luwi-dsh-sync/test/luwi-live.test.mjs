// test/luwi-live.test.mjs — Luwi 加 (G1-2 进行中的回答) 的投影算术。
//
// 钉住五件事:
//   1. **只要回答**: 只收 user/message (source.kind === 'user') 与 assistant/message 的 text 块,
//      reasoning 与 tool 的东西一个都不进
//   2. **落定不看 turn/end**: 主路载体 (sessionQuery.readSurface) 里一条 turn/end 都没有 ——
//      下一轮开了 + (最后那一轮) running 说了算, turn/end 只当退路载体的补充判据
//   3. 草稿不进 entries: 只有"还在跑的那一轮"的回答进 draft
//   4. 窗口: 条数与字数两条都截, 截了就如实标记 truncated (最新那条永远留)
//   5. 从对端读回来的 JSON 是不可信输入, 过 readLiveSnapshot 的闸
// @module luwi-dsh-sync/test/luwi-live.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { condense, liveDirectory, liveFileName, liveSnapshot, readLiveSnapshot } from '../lib/luwi-live.mjs'

/** 一场会话: 第一轮有思考与工具, 第二轮还在流 */
const EVENTS = [
  { seq: 1, time: 10, type: 'turn/start', data: { turn: 1 } },
  { seq: 2, time: 11, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '问题一' }] } },
  {
    seq: 3,
    time: 12,
    type: 'assistant/message',
    data: {
      turn: 1,
      step: 1,
      message: {
        content: [
          { type: 'reasoning', text: '不该出现' },
          { type: 'text', text: '答案一' },
          { type: 'tool-call', name: 'lw_screenshot' },
        ],
      },
    },
  },
  { seq: 4, time: 13, type: 'tool/call', data: { turn: 1, step: 1, name: 'lw_screenshot' } },
  { seq: 5, time: 14, type: 'tool/result', data: { turn: 1, step: 1 } },
  {
    seq: 6,
    time: 15,
    type: 'assistant/message',
    data: { turn: 1, step: 2, message: { content: [{ type: 'text', text: '答案一的下半句' }] } },
  },
  { seq: 7, time: 16, type: 'turn/end', data: { turn: 1 } },
  { seq: 8, time: 17, type: 'turn/start', data: { turn: 2 } },
  { seq: 9, time: 18, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '问题二' }] } },
  { seq: 10, time: 19, type: 'assistant/message', data: { turn: 2, step: 1, message: { content: [{ type: 'text', text: '答案二还在流' }] } } },
]

test('only the human user message and the assistant text blocks become entries', () => {
  const { entries, draft } = condense(EVENTS, { running: true })
  assert.deepEqual(entries.map(entry => [entry.seq, entry.role, entry.text]), [
    [2, 'user', '问题一'],
    // 同一轮里后一条顶掉前一条 (工具调用前后各有一条 assistant/message)
    [6, 'assistant', '答案一的下半句'],
    // 第二轮还没结束, 但用户那句话已经落定, 所以它在 entries 里
    [9, 'user', '问题二'],
  ])
  assert.deepEqual(draft, { seq: 10, text: '答案二还在流' }, 'the still-streaming answer is a draft, not an entry')
  const joined = entries.map(entry => entry.text).join('|')
  assert.ok(!joined.includes('不该出现'), 'reasoning never reaches the projection')
  assert.ok(!joined.includes('lw_screenshot'), 'tool names never reach the projection')
})

// 2026-10-14 主人报的那一条: "同步功能只显示了用户的话, 没有显示 agent 的回答"。
// 根因是落定只认 turn/end, 而主路载体 sessionQuery.readSurface 只回 surface 事件,
// turn/end 是 log-only, 永远不在那一串里 —— 于是每一条回答都判成"还在流", 全卡在 draft。
// 这一份事件流就是 readSurface 的真实形状: 一条 turn/end 都没有。
test('回答的落定不靠 turn/end (主路载体里一条都没有)', () => {
  const surface = [
    { seq: 1, time: 1, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '问题一' }] } },
    { seq: 2, time: 2, type: 'assistant/message', data: { turn: 1, step: 1, message: { content: [{ type: 'text', text: '答案一' }] } } },
    { seq: 3, time: 3, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '问题二' }] } },
    { seq: 4, time: 4, type: 'assistant/message', data: { turn: 2, step: 1, message: { content: [{ type: 'text', text: '答案二' }] } } },
  ]
  const settled = condense(surface, { running: false })
  assert.deepEqual(settled.entries.map(entry => [entry.seq, entry.role, entry.text]), [
    [1, 'user', '问题一'],
    [2, 'assistant', '答案一'],
    [3, 'user', '问题二'],
    [4, 'assistant', '答案二'],
  ], '没在跑: 后面那条回答也算落定, 四句都该进 entries')
  assert.equal(settled.draft, null)

  const live = condense(surface, { running: true })
  assert.deepEqual(live.entries.map(entry => [entry.seq, entry.role]), [[1, 'user'], [2, 'assistant'], [3, 'user']], '在跑: 只有最后那一轮的回答还是草稿')
  assert.deepEqual(live.draft, { seq: 4, text: '答案二' })
})

test('最后一轮: 没在跑就落定, 在跑才是草稿', () => {
  const one = [
    { seq: 1, time: 1, type: 'turn/start', data: { turn: 1 } },
    { seq: 2, time: 2, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '在吗' }] } },
    { seq: 3, time: 3, type: 'assistant/message', data: { turn: 1, step: 1, message: { content: [{ type: 'text', text: '在' }] } } },
  ]
  const done = condense(one, { running: false })
  assert.deepEqual(done.entries.map(entry => entry.seq), [2, 3])
  assert.equal(done.draft, null)
  const running = condense(one, { running: true })
  assert.deepEqual(running.entries.map(entry => entry.seq), [2])
  assert.deepEqual(running.draft, { seq: 3, text: '在' })
})

// 一条只有工具调用的 assistant/message 不是回答: 它既不进 entries, 也不顶掉同一轮里前面那句
// 真说过的话 (宿主的 session-turn-outline 也是"text-bearing 的那条顶前一条")
test('一条只有工具调用的回答不算回答', () => {
  const withToolOnly = [
    { seq: 1, time: 1, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '继续' }] } },
    { seq: 2, time: 2, type: 'assistant/message', data: { turn: 1, step: 1, message: { content: [{ type: 'text', text: '我去看一下' }] } } },
    { seq: 3, time: 3, type: 'assistant/message', data: { turn: 1, step: 2, message: { content: [{ type: 'tool-call', name: 'lw_screenshot' }] } } },
  ]
  const { entries, draft } = condense(withToolOnly)
  assert.deepEqual(entries.map(entry => [entry.seq, entry.role, entry.text]), [[1, 'user', '继续'], [2, 'assistant', '我去看一下']])
  assert.equal(draft, null)

  const toolOnly = condense([
    { seq: 1, time: 1, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '继续' }] } },
    { seq: 2, time: 2, type: 'assistant/message', data: { turn: 1, step: 1, message: { content: [{ type: 'tool-call', name: 'lw_screenshot' }] } } },
  ], { running: true })
  assert.deepEqual(toolOnly.entries.map(entry => entry.role), ['user'], '占位不是回答')
  assert.equal(toolOnly.draft, null, '只有工具调用的那一轮没有草稿可看')
})

test('a turn/end still settles its turn when the fallback carrier supplies one', () => {
  const withEnd = [
    { seq: 1, time: 1, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '问题' }] } },
    { seq: 2, time: 2, type: 'assistant/message', data: { turn: 1, step: 1, message: { content: [{ type: 'text', text: '回答' }] } } },
    { seq: 3, time: 3, type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } },
  ]
  const { entries, draft } = condense(withEnd)
  assert.deepEqual(entries.map(entry => [entry.seq, entry.role]), [[1, 'user'], [2, 'assistant']])
  assert.equal(draft, null)
})

test('injected context lines are not user messages', () => {
  const { entries } = condense([
    { seq: 1, time: 1, type: 'user/message', data: { source: { kind: 'dsh-session-sync' }, content: [{ type: 'text', text: '冲突通知' }] } },
    { seq: 2, time: 2, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: '主人说的' }] } },
  ])
  assert.deepEqual(entries.map(entry => entry.text), ['主人说的'])
})

test('the window cuts by count and by characters, and says so', () => {
  const byCount = condense(EVENTS, { maxEntries: 2, running: true })
  assert.deepEqual(byCount.entries.map(entry => entry.seq), [6, 9])
  assert.equal(byCount.truncated, true)

  // 字数那一档: 配置层的下界是 200 字符 (见 LIVE_LIMITS), 所以拿两段长文本去量
  const long = condense([
    { seq: 1, time: 1, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'x'.repeat(150) }] } },
    { seq: 2, time: 2, type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'y'.repeat(150) }] } },
  ], { maxChars: 200 })
  assert.deepEqual(long.entries.map(entry => entry.seq), [2], 'the newest entry survives; the older one falls out of the budget')
  assert.equal(long.truncated, true)

  const roomy = condense(EVENTS, { maxEntries: 40, maxChars: 16000, running: true })
  assert.equal(roomy.truncated, false)
})

test('condense survives empty and malformed input', () => {
  assert.deepEqual(condense([]), { entries: [], draft: null, truncated: false, all: [] })
  assert.deepEqual(condense(undefined), { entries: [], draft: null, truncated: false, all: [] })
  assert.deepEqual(condense(['nonsense', null, { type: 'user/message', data: null }]).entries, [])
})

test('a snapshot carries the device, the session and the two lists', () => {
  const snapshot = liveSnapshot({
    deviceId: 'dev-1',
    deviceName: 'PC',
    sessionId: 'session-abc',
    title: '标题',
    cwd: 'D:/apk',
    running: true,
    entries: [{ seq: 1, role: 'user', text: 'hi', at: 1 }],
    draft: null,
    truncated: false,
    updatedAt: 123,
  })
  assert.equal(snapshot.v, 2, 'v2 起 entries 里才有已经落定的回答 (读的一侧据此决定要不要重做投影)')
  assert.equal(snapshot.sessionId, 'session-abc')
  assert.equal(snapshot.running, true)
  assert.equal(snapshot.deviceName, 'PC')
  assert.deepEqual(readLiveSnapshot(snapshot), snapshot, 'a snapshot we wrote reads back unchanged')
})

test('a snapshot read from the peer is validated, not trusted', () => {
  assert.equal(readLiveSnapshot(null), null)
  assert.equal(readLiveSnapshot([]), null)
  assert.equal(readLiveSnapshot({}), null, 'no session id means nothing to project')
  const cleaned = readLiveSnapshot({
    sessionId: 'session-x',
    deviceId: 7,
    entries: [
      { seq: 'nope', role: 'user', text: 'ok' },
      { role: 'assistant', text: 'ok2' },
      { role: 'system', text: '不认的 role' },
      null,
      { role: 'user', text: 42 },
    ],
    draft: { text: 'd', seq: 'x' },
    running: 'yes',
  })
  assert.deepEqual(cleaned.entries.map(entry => [entry.role, entry.text, entry.seq]), [['user', 'ok', 0], ['assistant', 'ok2', 0]])
  assert.deepEqual(cleaned.draft, { seq: 0, text: 'd' })
  assert.equal(cleaned.running, false, 'a non-boolean running reads as false')
  assert.equal(cleaned.deviceId, '', 'a non-string device id reads as empty')
})

test('live file names and directories stay inside the repo', () => {
  assert.equal(liveFileName('session-1234-abc'), 'session-1234-abc')
  assert.equal(liveFileName('../../etc/passwd'), 'etc-passwd')
  assert.equal(liveFileName(''), 'session')
  assert.equal(liveFileName('x'.repeat(200)).length, 128)
  assert.ok(liveDirectory('/repo', 'dev-1').includes('live/dev-1'))
  assert.throws(() => liveDirectory('/repo', '***'), /device id/u)
})
