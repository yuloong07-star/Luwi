// lib/luwi-proxy.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// G1-2「投影会话」的折叠算术: 把对端那一场的**文本条目** (lib/luwi-live.mjs 的 condense 产物)
// 折成本地投影会话要追加的那一串事件。
//
// 为什么要有这一层 (而不是直接把对端那份文件当本地会话):
//   · 本机不许写对端那份日志 —— 两边都往同一个文件追加会被判 APPEND_BOTH (lib/merge.mjs),
//     每发一次远程指令分叉一次。投影会话是**本地自己的一场** (不同 session id, 不同文件),
//     所以本地写什么都没有合并的后果
//   · 投影会话的 cwd 由我们写在影子目录里, 于是它天然落在对端那个工作区分组里
//   · 「归属方执行」那条闸认出投影会话的 id 就拦下本地那一轮 (index.mjs 的 agent/pre-step)
//
// 事件的形状照宿主自己的日志: 一条用户消息开一轮 (turn/start + user/message), 一条回答收一轮
// (assistant/message + turn/end)。assistant/message 与 user/message 是 surface 事件, 追加时
// 必须带 surfaceOp (与 index.mjs 的 injectForkNotice 同一个写法)
//
// 幂等靠三样: 条目带对端日志的 seq (只追加 seq 更大的), 每一轮的开合状态 (openTurn) 跨轮保存,
// 以及"本地已经先追加过的那句话" (pendingTexts) 在应用对端回声时被认掉一次

import { LIVE_LIMITS } from './constants.mjs'

/**
 * 把对端的文本条目折成本地要追加的事件。
 *
 * @param {Array<{seq?: number, role?: string, text?: string, at?: number}>} entries - 对端的条目 (按 seq 升序)。
 * @param {object} [state] 现有状态 (跨轮保存的那三笔)。
 * @param {number} [state.lastSeq] - 已经应用过的最大 seq。
 * @param {number|null} [state.openTurn] - 现在开着的那一轮 (null = 没有)。
 * @param {number} [state.turnCounter] - 已经用掉的最大轮号。
 * @param {string[]} [state.pendingTexts] - 本地先追加过、等对端回声认掉的那几句 (FIFO)。
 * @param {number} [maxEvents] - 一次最多折出多少事件 (防止对端一口气推来一大段)。
 * @returns {{events: Array<{type: string, data: object, surface: boolean}>, lastSeq: number, openTurn: number|null, turnCounter: number, pendingTexts: string[], applied: number, skipped: number, truncated: boolean}} 结果。
 */
export function proxyEventsFrom(entries, state = {}, maxEvents = LIVE_LIMITS.MAX_MAX_ENTRIES * 4) {
  let lastSeq = Number.isFinite(state.lastSeq) ? Number(state.lastSeq) : 0
  let openTurn = Number.isFinite(state.openTurn) ? Number(state.openTurn) : null
  let turnCounter = Number.isFinite(state.turnCounter) ? Number(state.turnCounter) : 0
  let pendingTexts = Array.isArray(state.pendingTexts) ? state.pendingTexts.filter(text => typeof text === 'string') : []
  const events = []
  let applied = 0
  let skipped = 0
  let truncated = false
  const list = Array.isArray(entries) ? [...entries].sort((left, right) => Number(left?.seq ?? 0) - Number(right?.seq ?? 0)) : []
  for (const entry of list) {
    if (entry === null || typeof entry !== 'object') continue
    const seq = Number.isFinite(entry.seq) ? Number(entry.seq) : 0
    if (seq <= lastSeq) continue
    const role = entry.role === 'assistant' ? 'assistant' : entry.role === 'user' ? 'user' : ''
    const text = typeof entry.text === 'string' ? entry.text : ''
    if (role === '' || text === '') continue
    if (events.length >= maxEvents) {
      // 这一条与后面那些留给下一轮: 但游标不能前移, 否则它们永远补不上
      truncated = true
      break
    }
    lastSeq = seq
    if (role === 'user') {
      // 本地先追加过的那一句: 对端把它回推回来了, 认掉一次, 不重复落
      const at = pendingTexts.indexOf(text)
      if (at !== -1) {
        pendingTexts = [...pendingTexts.slice(0, at), ...pendingTexts.slice(at + 1)]
        skipped += 1
        continue
      }
      if (openTurn !== null) {
        events.push({ type: 'turn/end', data: { turn: openTurn, reason: { kind: 'completed' } }, surface: false })
        openTurn = null
      }
      turnCounter += 1
      openTurn = turnCounter
      events.push({ type: 'turn/start', data: { turn: openTurn }, surface: false })
      events.push({
        type: 'user/message',
        data: { id: `peer-${seq}`, role: 'user', content: [{ type: 'text', text }], source: { kind: 'user', via: 'peer' } },
        surface: true,
      })
      applied += 1
      continue
    }
    // assistant: 没有开着的轮就自己开一轮 (中间那条回答也可能是这一批的第一条)
    if (openTurn === null) {
      turnCounter += 1
      openTurn = turnCounter
      events.push({ type: 'turn/start', data: { turn: openTurn }, surface: false })
    }
    // step/start 与 step/end 不能省: 界面的对话流把一条 assistant 消息归进**某一步**里,
    // 没有这一对边界时它只画出"已完成"、不画正文 (2026-10-22 真机实测: 会话文件里明明有回答,
    // 打开着的界面只有两行「已完成」)
    events.push({ type: 'step/start', data: { turn: openTurn, step: 1 }, surface: false })
    events.push({
      type: 'assistant/message',
      data: {
        turn: openTurn,
        step: 1,
        message: {
          id: `peer-${seq}`,
          role: 'assistant',
          content: [{ type: 'text', text }],
          // 2026-10-22 晚补: 宿主自己写的 assistant/message **一定**带 source.kind='model'
          // (provider / model / replayState 那一坨), 界面拿它画模型标签与回放信息。折回来的
          // 消息从前没有这一个字段 —— 真机上那场会话整页空白 (日志里问答都齐, 界面上什么都没有),
          // 而宿主自己写的会话渲染正常。补一个同形的 source, 让折回的那条与宿主写的没有区别
          source: { kind: 'model', provider: 'luwi-peer', model: 'luwi-peer' },
        },
        stream: [],
      },
      surface: true,
    })
    events.push({ type: 'step/end', data: { turn: openTurn, step: 1 }, surface: false })
    events.push({ type: 'turn/end', data: { turn: openTurn, reason: { kind: 'completed' } }, surface: false })
    openTurn = null
    applied += 1
  }
  return { events, lastSeq, openTurn, turnCounter, pendingTexts, applied, skipped, truncated }
}

/**
 * "我在这台机器上发的这一句"要追加的那一条用户消息。
 *
 * 归属方那条闸 (`agent/pre-step`) 命中投影会话时会**先**把这一句追进日志再返回"不调模型" ——
 * 那一轮的开合由宿主自己的 turn/start 与 turn/end 管着, 所以这里只回一条消息, 不再开一轮
 * (自己再开一轮会把同一句话写进两个轮里)
 * @param {string} text - 正文。
 * @param {number} turn - 宿主已经开着的那一轮的轮号。
 * @returns {{type: string, data: object, surface: boolean}} 一条事件。
 */
export function proxyUserMessage(text, turn) {
  return {
    type: 'user/message',
    data: {
      id: `peer-local-${turn}`,
      role: 'user',
      content: [{ type: 'text', text }],
      source: { kind: 'user', via: 'peer' },
    },
    surface: true,
  }
}

/**
 * 一条"这一句没能送到对端"的本地通知 (进投影会话的日志, 并且**不调模型**)。
 * 用的是本插件自己的 source kind (与冲突通知同一形制), 界面会把它画成注入的上下文行。
 *
 * **`id` 与 `role` 一个都不能少** (2026-10-20 真机事故): 投影的 `deriveEventMessage` 把
 * `user/message` 的 `data` 原样当作历史消息交给 provider, 少了 `role` 就会发出
 * `{"content":[…]}` 这样的消息, DeepSeek 那头回 `messages[1]: missing field role`
 * (400 INVALID_REQUEST) —— 而且这条日志留在会话里, 之后每一轮都撞同一堵墙。
 * 少了 `id`, 从盘上重新装载这份会话时事件形状校验也会拒 (`lacks an identified message`)
 * @param {string} reason - 为什么没送到。
 * @param {string} device - 对端叫什么。
 * @param {{text?: string, id?: string}} [options] - 改写正文 (排进仓库队列那一档) 与显式消息 id。
 * @returns {Array<{type: string, data: object, surface: boolean}>} 事件。
 */
export function proxyNoticeEvents(reason, device, options = {}) {
  const text = typeof options.text === 'string' && options.text.trim() !== ''
    ? options.text.trim()
    : `这一句没有送到 ${device}: ${reason}`
  const id = typeof options.id === 'string' && options.id !== ''
    ? options.id
    : `peer-notice-${Math.random().toString(36).slice(2, 10)}`
  return [{
    type: 'user/message',
    data: {
      id,
      role: 'user',
      content: [{ type: 'text', text }],
      source: { kind: 'dsh-session-sync', form: 'notice', summary: 'a cross-device line did not go out' },
    },
    surface: true,
  }]
}

/**
 * 投影会话的标题: 与对端那一场同名 (分组名已经带了设备名, 这里不再加后缀); 空了给一个兜底。
 * @param {unknown} peerTitle - 对端报的标题。
 * @param {string} peerSessionId - 对端会话 id。
 * @returns {string} 标题。
 */
export function proxyTitleOf(peerTitle, peerSessionId) {
  const text = typeof peerTitle === 'string' ? peerTitle.trim() : ''
  if (text !== '') return text
  const short = String(peerSessionId ?? '').slice(-8)
  return `对端会话 ${short === '' ? '?' : short}`
}

/**
 * 折叠出的一串事件追加进会话。
 * @param {object} session - 宿主 Session (有 append)。
 * @param {Array<{type: string, data: object, surface: boolean}>} events - proxyEventsFrom 的产物。
 * @returns {number} 真正追加了几条 (append 抛的那条之后停下, 已经追进去的不回滚)。
 */
export function appendProxyEvents(session, events) {
  let appended = 0
  for (const event of events) {
    try {
      if (event.surface) session.append(event.type, event.data, { surfaceOp: 'append' })
      else session.append(event.type, event.data)
      appended += 1
    } catch {
      // 一条落不下就停: 后面的都挂在它后面, 硬塞只会写出一段读不懂的日志
      break
    }
  }
  return appended
}
