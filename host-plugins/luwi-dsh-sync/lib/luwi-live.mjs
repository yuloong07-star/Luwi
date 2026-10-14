// lib/luwi-live.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// G1-2「进行中的回答」的投影算术: 把一场会话的事件折成**只有 user 与 assistant 文本**的一份
// 快照, 再写到 <repoDir>/live/<本机设备id>/<sessionId>.json (退路载体), 同时它也直接回答
// 对端的 GET /luwi-sync/peer/transcript (主路载体)
//
// 两条口径在这里定死:
//   · **只要回答**: 只认 user/message 与 assistant/message 的 text 块。reasoning 与 tool 的
//     东西没有入口 —— reasoning 不是一种事件, 它只是 AssistantMessage.content 里的一个块,
//     所以这里按块过滤; tool/call 与 tool/result 是别的事件类型, 直接不看。**一条回答里没有
//     text 块时它不是回答** (只有工具调用或只有图片的那种), 不进 entries 也不顶掉前面说过的话
//   · **草稿不进日志**: entries 只收"已经落定"的东西 (user 一律收, assistant 只收那一轮
//     已经落定的), 正在流的那一段进 draft。对端把这些 entries 追加进自己的投影会话
//     (幂等), 而 draft 只在界面上看 —— 否则投影会话会随每一次流式分片长出一堆事件
//
// **"落定"不能只看 turn/end** (2026-10-14 修的): 主路载体是宿主的 `sessionQuery.readSurface`,
// 而它只回 **surface 事件** (system/message·user/message·assistant/message·tool/result —
// 见 dsh-session 的 `isSurfaceEvent`), `turn/end` 是 log-only 的一种, **永远不在那一串里**。
// 从前只认 turn/end 的后果是: 每一轮的回答都判成"还在流", 于是投影里永远只有主人那几句话
// (主人报的现象: "同步功能只显示了用户的话, 没有显示 agent 的回答", 而最后那句回答卡在 draft)。
// 所以落定按三个来源判:
//   · 后面还有没有一条主人自己的用户消息 —— 下一轮开了, 这一轮就落定 (主路只有这一条)
//   · turn/end —— 退路载体 (index.mjs 的 eventsFromMessages) 会造它, 认它
//   · 最后那一轮 —— 由 `running` 说话: 没在跑 (或没说) 就算落定, 在跑才进 draft
//
// 一份快照的形状 (v2):
//   {v, deviceId, deviceName, sessionId, title, cwd, running, updatedAt, truncated,
//    entries: [{seq, role: 'user'|'assistant', text, at}], draft: {seq, text}|null}
// v2 = entries 里除了用户那几句, 还有**已经落定的回答** (v1 那条只认 turn/end 的口径下,
// 主路载体折出来的 entries 只有用户那几句) —— 读的一侧据此决定要不要重做一场投影

import { LIVE_LIMITS } from './constants.mjs'
import { badConfig } from './errors.mjs'
import { saysText, textOfContent } from './luwi-peer.mjs'

/** 会话 id 收成文件名时保留的字符集 (会话 id 是宿主给的不透明串, 这里只管能不能当文件名) */
const SAFE_ID_RE = /[^0-9A-Za-z._-]/gu

/** 会话 id 折成文件名的长度上限 */
const MAX_FILE_NAME_LENGTH = 128

/**
 * 投影快照的版本号。
 *
 * v2 (2026-10-14) 起 entries 里除了用户那几句, 还有**已经落定的回答**; v1 只认 turn/end, 而主路
 * 载体 (`sessionQuery.readSurface`) 永远不给 turn/end, 于是 v1 折出来的 entries 只有用户的话。
 * 读的一侧拿它比对自己账上消费过的那一版, 对端报得更高就该把那场投影重做一场 (见 index.mjs)
 */
export const LIVE_SNAPSHOT_VERSION = 2

/** 没有版本号的快照按 v1 读 (老对端写的东西就是这个形状) */
export const LIVE_SNAPSHOT_VERSION_LEGACY = 1

/**
 * 把一场会话的事件折成投影快照里的那两坨。
 *
 * @param {Array<{seq?: number, time?: number, type?: string, data?: unknown}>} events - 日志顺序的事件 (sessionQuery.readSurface / readSession 的产物)。
 * @param {{maxEntries?: number, maxChars?: number, running?: boolean}} [options] - 窗口 (缺省取 LIVE_LIMITS 的下界与常量缺省) 与"这一场此刻在不在跑" (在跑则最后那一轮的回答进 draft, 否则它也算落定)。
 * @returns {{entries: Array<{seq: number, role: string, text: string, at: number}>, draft: {seq: number, text: string}|null, truncated: boolean}} 投影内容。
 */
export function condense(events, options = {}) {
  const maxEntries = clampInt(options.maxEntries, LIVE_LIMITS.MIN_MAX_ENTRIES, LIVE_LIMITS.MAX_MAX_ENTRIES, 40)
  const maxChars = clampInt(options.maxChars, LIVE_LIMITS.MIN_MAX_CHARS, LIVE_LIMITS.MAX_MAX_CHARS, 16000)
  const running = options.running === true
  const list = Array.isArray(events) ? events : []
  /** 有序的收条: 先按日志顺序摆好, 最后一步才截窗口 */
  const items = []
  /**
   * 每一轮的"最后那段回答": 一条 user/message 开一个桶, 之后的 assistant/message 往当前那个桶里写
   * (一轮里可能有好几条 —— 工具调用前后各一条 —— 后一条顶掉前一条)。
   *
   * 用**桶**而不是 turn 号: 主路载体里根本没有 turn/end, 而 turn 号只说明"这条消息属于哪一步",
   * 拿它当轮号会随宿主的号重新起算而错位; 桶由"用户那句话"划界, 与日志顺序一一对上
   * @type {Array<{seq: number, text: string, at: number, ended: boolean}>}
   */
  const turns = []
  for (const event of list) {
    if (event === null || typeof event !== 'object') continue
    const type = typeof event.type === 'string' ? event.type : ''
    const seq = Number.isFinite(event.seq) ? Number(event.seq) : 0
    const at = Number.isFinite(event.time) ? Number(event.time) : 0
    const data = event.data !== null && typeof event.data === 'object' ? /** @type {Record<string, unknown>} */ (event.data) : {}
    if (type === 'turn/end') {
      // 退路载体那一串里才有: 它收的是**现在开着的那一轮**
      const open = turns[turns.length - 1]
      if (open !== undefined) open.ended = true
      continue
    }
    if (type === 'user/message') {
      const source = data.source !== null && typeof data.source === 'object' ? /** @type {Record<string, unknown>} */ (data.source) : {}
      // 只有"主人自己说的"才算一条用户消息 (注入的上下文行与插件通知都不算)
      if (source.kind !== 'user') continue
      const text = textOfContent(data.content)
      if (text === '') continue
      items.push({ seq, role: 'user', text, at })
      turns.push({ seq: 0, text: '', at: 0, ended: false })
      continue
    }
    if (type === 'assistant/message') {
      const message = data.message !== null && typeof data.message === 'object' ? /** @type {Record<string, unknown>} */ (data.message) : {}
      // 只有工具调用 (或只有图片) 的那种不算回答: 宿主自己的 session-turn-outline 也是"text-bearing
      // 的那条顶前一条", 拿占位 (`[tool-call]`) 当回答只会在投影里长出一堆没有内容的东西
      if (!saysText(message.content)) continue
      const text = textOfContent(message.content)
      // 没有开着的那一轮 (回答在自家开一轮之前的也算): 补一个匿名桶, 别把这段回答丢掉
      if (turns.length === 0) turns.push({ seq: 0, text: '', at: 0, ended: false })
      const turn = turns[turns.length - 1]
      turn.seq = seq
      turn.text = text
      turn.at = at
      continue
    }
  }
  // 落定: 桶被 turn/end 收过, 或者**它后面还有一条主人自己的用户消息**(下一轮开了这一轮就落了),
  // 最后那个桶由 running 说话 (在跑 = 还在流, 进 draft)
  const entries = []
  let draft = null
  for (const [index, turn] of turns.entries()) {
    if (turn.text === '') continue
    const settled = turn.ended || index !== turns.length - 1 || !running
    if (settled) entries.push({ seq: turn.seq, role: 'assistant', text: turn.text, at: turn.at })
    else draft = { seq: turn.seq, text: turn.text }
  }
  const merged = [...items, ...entries].sort((left, right) => left.seq - right.seq || left.role.localeCompare(right.role))
  // 窗口: 先按条数, 再按字数 (从最老的开始丢), 丢了就如实标记
  let truncated = false
  let kept = merged
  if (kept.length > maxEntries) {
    kept = kept.slice(kept.length - maxEntries)
    truncated = true
  }
  let budget = maxChars
  // 至少留下最新那一条: 最新一条自己就超预算时, cut 停在"只留它", 而不是因为 break 把整窗留下
  let cut = Math.max(0, kept.length - 1)
  for (let index = kept.length - 1; index >= 0; index -= 1) {
    budget -= kept[index].text.length
    if (budget < 0) break
    cut = index
  }
  if (cut > 0) {
    kept = kept.slice(cut)
    truncated = true
  }
  return { entries: kept, draft, truncated }
}

/**
 * 一份投影快照的完整形状 (落盘与应答共用)。
 * @param {object} input 输入。
 * @param {string} input.deviceId - 本机设备 id。
 * @param {string} input.deviceName - 本机设备名 (空 = 本机)。
 * @param {string} input.sessionId - 会话 id。
 * @param {string} [input.title] - 会话标题。
 * @param {string} [input.cwd] - 会话的工作目录。
 * @param {boolean} [input.running] - 这一场此刻在不在跑。
 * @param {Array<object>} input.entries - condense() 的 entries。
 * @param {{seq: number, text: string}|null} [input.draft] - condense() 的 draft。
 * @param {boolean} [input.truncated] - 窗口有没有截断。
 * @param {number} input.updatedAt - 生成时刻。
 * @returns {object} 快照 JSON。
 */
export function liveSnapshot(input) {
  return {
    // v2 (2026-10-14): entries 里多了"已经落定的回答" —— v1 那条口径下主路载体折出来的只有用户的话,
    // 读的一侧 (index.mjs 的 applyTranscript) 按这个版本号决定要不要把旧投影重做一场
    v: LIVE_SNAPSHOT_VERSION,
    deviceId: String(input.deviceId ?? ''),
    deviceName: String(input.deviceName ?? ''),
    sessionId: String(input.sessionId ?? ''),
    title: String(input.title ?? ''),
    cwd: String(input.cwd ?? ''),
    running: input.running === true,
    updatedAt: Number.isFinite(input.updatedAt) ? input.updatedAt : 0,
    truncated: input.truncated === true,
    entries: Array.isArray(input.entries) ? input.entries : [],
    draft: input.draft ?? null,
  }
}

/**
 * 会话 id 折成文件名的形态 (只留能当文件名的字符)。
 * @param {unknown} sessionId - 会话 id。
 * @returns {string} 文件名 (不含目录, 不含 .json)。
 */
export function liveFileName(sessionId) {
  const cleaned = String(sessionId ?? '')
    .replace(SAFE_ID_RE, '-')
    .replace(/^[.\-]+|[.\-]+$/gu, '')
  const kept = cleaned === '' ? 'session' : cleaned
  return kept.slice(0, MAX_FILE_NAME_LENGTH)
}

/**
 * 校验一份从对端读回来的投影快照 (退路载体是对方写的 JSON, 全是不可信输入)。
 * @param {unknown} value - 解析出来的对象。
 * @returns {object|null} 合格的快照; 不合格 null。
 */
export function readLiveSnapshot(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = /** @type {Record<string, unknown>} */ (value)
  const sessionId = typeof raw.sessionId === 'string' ? raw.sessionId : ''
  if (sessionId === '') return null
  const entries = Array.isArray(raw.entries)
    ? raw.entries.flatMap((entry) => {
      if (entry === null || typeof entry !== 'object') return []
      const item = /** @type {Record<string, unknown>} */ (entry)
      const role = item.role === 'assistant' ? 'assistant' : item.role === 'user' ? 'user' : ''
      if (role === '' || typeof item.text !== 'string') return []
      return [{
        seq: Number.isFinite(item.seq) ? Number(item.seq) : 0,
        role,
        text: item.text,
        at: Number.isFinite(item.at) ? Number(item.at) : 0,
      }]
    })
    : []
  const draftValue = raw.draft !== null && typeof raw.draft === 'object' ? /** @type {Record<string, unknown>} */ (raw.draft) : null
  const draft = draftValue !== null && typeof draftValue.text === 'string'
    ? { seq: Number.isFinite(draftValue.seq) ? Number(draftValue.seq) : 0, text: draftValue.text }
    : null
  return {
    // 没有版本号 = v1 (老对端写的快照就是这个形状: entries 里只有用户那几句)
    v: Number.isFinite(raw.v) ? Number(raw.v) : LIVE_SNAPSHOT_VERSION_LEGACY,
    deviceId: typeof raw.deviceId === 'string' ? raw.deviceId : '',
    deviceName: typeof raw.deviceName === 'string' ? raw.deviceName : '',
    sessionId,
    title: typeof raw.title === 'string' ? raw.title : '',
    cwd: typeof raw.cwd === 'string' ? raw.cwd : '',
    running: raw.running === true,
    updatedAt: Number.isFinite(raw.updatedAt) ? Number(raw.updatedAt) : 0,
    truncated: raw.truncated === true,
    entries,
    draft,
  }
}

/** 整数夹逼 (配置层的边界在 resolveLiveSpec 里已经校验过, 这里只是防御) */
function clampInt(value, min, max, fallback) {
  if (!Number.isInteger(value)) return fallback
  if (value < min) return min
  if (value > max) return max
  return value
}

/**
 * 投影目录 (仓库工作树顶层, **不在镜像根之下** —— 镜像只认 <mirrorDir>, 别处的文件它不删)。
 * @param {string} repoDir - 同步工作树根。
 * @param {string} deviceId - 本机设备 id。
 * @returns {string} 本机投影目录。
 */
export function liveDirectory(repoDir, deviceId) {
  const id = String(deviceId ?? '').replace(/[^0-9A-Za-z._-]/gu, '')
  if (id === '') throw badConfig('cannot place live projections without a device id')
  return `${repoDir}/live/${id}`
}
