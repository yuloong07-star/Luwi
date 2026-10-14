// lib/luwi-inbox.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// G1-2 的**退路通道**: 对端 HTTP 打不通时, 一句话写成
// `<repoDir>/inbox/<目标设备id>/<消息id>.json` 跟着会话仓库的推拉过去; 对端 pull 之后投递,
// 再把回执写成 `<repoDir>/inbox-ack/<目标设备id>/<消息id>.json` 推回来。发送方凭回执知道
// "那一句真的送到了"
//
// 三条纪律:
//   · **只增不改**: 文件写成之后谁都不重写它。这一条是这套设计能坐在 keep-both 合并上的前提
//     (每次重写的派生文件会判 DIVERGED, 两边各留 fork, 永不收敛 —— 见 lib/merge.mjs)
//   · **不在镜像根之下**: inbox/ inbox-ack/ 都在 <repoDir> 顶层, 与 device.txt / peers/ 同级。
//     写在 <repoDir>/sessions/ 底下的外来文件会被下一次镜像删掉 (见 lib/mirror.mjs)
//   · **不可信输入**: 对端写的 JSON 一律过 parse 闸; 坏文件跳过并计数, 绝不抛
//
// 幂等由两处一起保证: 文件 id 在投递成功之前不会变 (重投是同一份), 且投递方把已投的 id 记在
// storage 域里 (见 rememberId), 于是"同一句被投两次"不会真的进两次会话

import { mkdir, readdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

import { INBOX_ACK_DIR, INBOX_DIR } from './constants.mjs'

/** 一次最多读多少个队列文件 (防一份被塞爆的目录把 pull 拖住) */
const MAX_INBOX_FILES = 500

/** 消息 id 的字符集 (它要拼进文件名, 路径穿越一律在这里打掉) */
const SAFE_ID_RE = /[^0-9A-Za-z._-]/gu

/**
 * 队列目录。
 * @param {string} repoDir - 同步工作树根。
 * @param {string} deviceId - 收件方设备 id。
 * @returns {string} 目录路径。
 */
export function inboxDirectory(repoDir, deviceId) {
  return join(repoDir, INBOX_DIR, safeSegment(deviceId))
}

/**
 * 回执目录。
 * @param {string} repoDir - 同步工作树根。
 * @param {string} deviceId - 寄件方设备 id (谁要收回执)。
 * @returns {string} 目录路径。
 */
export function ackDirectory(repoDir, deviceId) {
  return join(repoDir, INBOX_ACK_DIR, safeSegment(deviceId))
}

/**
 * 队列里一份消息的文件名。
 * @param {string} messageId - 消息 id。
 * @returns {string} `<id>.json`。
 */
export function inboxFileName(messageId) {
  return `${safeSegment(messageId)}.json`
}

/**
 * 造一份队列消息 (发送方)。
 * @param {object} input 输入。
 * @param {string} input.id - 消息 id。
 * @param {string} input.from - 寄件方设备 id。
 * @param {string} [input.fromName] - 寄件方设备名。
 * @param {string} input.text - 正文。
 * @param {{kind?: string, id?: string, name?: string}} [input.target] - 目标 (会话或工作区)。
 * @param {number} input.createdAt - 生成时刻 (毫秒)。
 * @returns {object} 消息 JSON。
 */
export function buildMessage(input) {
  const target = input.target ?? {}
  return {
    v: 1,
    id: String(input.id ?? ''),
    from: String(input.from ?? ''),
    fromName: String(input.fromName ?? ''),
    text: String(input.text ?? ''),
    target: {
      kind: target.kind === 'workspace' ? 'workspace' : 'session',
      id: String(target.id ?? ''),
      ...(typeof target.name === 'string' && target.name !== '' ? { name: target.name } : {}),
    },
    createdAt: Number.isFinite(input.createdAt) ? input.createdAt : 0,
  }
}

/**
 * 造一份回执 (收件方投递成功之后写)。
 * @param {object} input 输入。
 * @param {string} input.id - 对应消息 id。
 * @param {string} [input.sessionId] - 这一句落到了哪一场。
 * @param {boolean} [input.running] - 那一轮是插进正在跑的 (true) 还是新排的 (false)。
 * @param {number} input.at - 投递时刻。
 * @param {string} [input.error] - 失败原因 (投不动时写它, 也留一份回执)。
 * @returns {object} 回执 JSON。
 */
export function buildAck(input) {
  return {
    v: 1,
    id: String(input.id ?? ''),
    sessionId: String(input.sessionId ?? ''),
    running: input.running === true,
    at: Number.isFinite(input.at) ? input.at : 0,
    ...(typeof input.error === 'string' && input.error !== '' ? { error: input.error } : {}),
  }
}

/**
 * 校验一份队列消息 (不可信输入)。
 * @param {unknown} value - 解析出来的对象。
 * @returns {object|null} 合格的消息; 不合格 null。
 */
export function parseMessage(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = /** @type {Record<string, unknown>} */ (value)
  const id = typeof raw.id === 'string' ? raw.id.trim() : ''
  const text = typeof raw.text === 'string' ? raw.text : ''
  if (id === '' || text.trim() === '') return null
  const targetValue = raw.target !== null && typeof raw.target === 'object' ? /** @type {Record<string, unknown>} */ (raw.target) : {}
  return {
    v: 1,
    id,
    from: typeof raw.from === 'string' ? raw.from : '',
    fromName: typeof raw.fromName === 'string' ? raw.fromName : '',
    text,
    target: {
      kind: targetValue.kind === 'workspace' ? 'workspace' : 'session',
      id: typeof targetValue.id === 'string' ? targetValue.id : '',
      ...(typeof targetValue.name === 'string' && targetValue.name !== '' ? { name: targetValue.name } : {}),
    },
    createdAt: Number.isFinite(raw.createdAt) ? Number(raw.createdAt) : 0,
  }
}

/**
 * 校验一份回执。
 * @param {unknown} value - 解析出来的对象。
 * @returns {{id: string, sessionId: string, running: boolean, at: number, error?: string}|null} 合格的回执。
 */
export function parseAck(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = /** @type {Record<string, unknown>} */ (value)
  const id = typeof raw.id === 'string' ? raw.id.trim() : ''
  if (id === '') return null
  return {
    v: 1,
    id,
    sessionId: typeof raw.sessionId === 'string' ? raw.sessionId : '',
    running: raw.running === true,
    at: Number.isFinite(raw.at) ? Number(raw.at) : 0,
    ...(typeof raw.error === 'string' && raw.error !== '' ? { error: raw.error } : {}),
  }
}

/**
 * 读一个队列目录 (坏文件跳过并计数; 目录不在 = 空)。
 * @param {string} directory - 目录路径。
 * @returns {Promise<{messages: object[], broken: number}>} 按 createdAt 升序的消息 (同一时刻按文件名)。
 */
export async function readInbox(directory) {
  let names
  try {
    names = await readdir(directory)
  } catch {
    return { messages: [], broken: 0 }
  }
  const messages = []
  let broken = 0
  for (const name of names.filter(entry => entry.endsWith('.json')).sort().slice(0, MAX_INBOX_FILES)) {
    let parsed = null
    try {
      parsed = parseMessage(JSON.parse(await readFile(join(directory, name), 'utf8')))
    } catch {
      parsed = null
    }
    if (parsed === null) {
      broken += 1
      continue
    }
    messages.push(parsed)
  }
  messages.sort((left, right) => left.createdAt - right.createdAt || left.id.localeCompare(right.id))
  return { messages, broken }
}

/**
 * 读一个回执目录 (以消息 id 为键; 坏文件跳过)。
 * @param {string} directory - 目录路径。
 * @returns {Promise<Map<string, object>>} 回执表。
 */
export async function readAcks(directory) {
  const acks = new Map()
  let names
  try {
    names = await readdir(directory)
  } catch {
    return acks
  }
  for (const name of names.filter(entry => entry.endsWith('.json')).sort().slice(0, MAX_INBOX_FILES)) {
    try {
      const parsed = parseAck(JSON.parse(await readFile(join(directory, name), 'utf8')))
      if (parsed !== null) acks.set(parsed.id, parsed)
    } catch {
      // 坏回执跳过: 回执只影响"送没送到"的显示, 不该把 pull 拖红
    }
  }
  return acks
}

/**
 * 原子写一份 JSON (先写 .tmp 再 rename)。目录不存在就建。
 * @param {string} file - 目标路径。
 * @param {object} value - JSON 可序列化的对象。
 * @returns {Promise<void>} 完成。
 */
export async function writeJsonFile(file, value) {
  await mkdir(dirname(file), { recursive: true })
  const temp = `${file}.tmp`
  await writeFile(temp, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  await rename(temp, file)
}

/**
 * 把一条消息 id 记进"已经投过"的账 (幂等判据)。
 * 上限之外的从最老的开始丢 —— 一串被截断的账只会让一条更老的消息重投一次, 那正是
 * 这套设计允许的退化 (投两次比丢一次好)
 * @param {string[]} list - 现有账 (旧的在前)。
 * @param {string} id - 新投的消息 id。
 * @param {number} [max] - 账本长度上限。
 * @returns {string[]} 新的账。
 */
export function rememberId(list, id, max = 500) {
  const kept = Array.isArray(list) ? list.filter(entry => typeof entry === 'string' && entry !== id) : []
  kept.push(id)
  return kept.length > max ? kept.slice(kept.length - max) : kept
}

/**
 * 文件名的安全段 (路径穿越一律打掉; 空则给一个占位)。
 * @param {unknown} value - 原始段。
 * @returns {string} 安全段。
 */
function safeSegment(value) {
  const cleaned = String(value ?? '').replace(SAFE_ID_RE, '').replace(/^\.+/u, '')
  return cleaned === '' ? 'unknown' : cleaned.slice(0, 128)
}
