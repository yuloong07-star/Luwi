// lib/luwi-transcript.mjs — Luwi 新增文件 (2026-10-22): 对话账 (方案文档 §5.4 的 conv/)
//
// 为什么要有它: `live/<设备>/<会话>.json` 是**滚动窗口** (40 条 / 16000 字), 对端离线一轮再
// 回来时窗口可能已经滑过本机投影的游标 —— 中间那段既不在窗口里, 也不在本机投影里, 而
// `sessions/` 那份归档是 MB 级的会话日志 (还带工具输出与 reasoning)。这里另存一本"只有对话"
// 的账: 每条几百字节, 封好的块不再改写, 于是推送只带最末那一块。
//
// 形状照方案文档 §5.4, 只改一处命名: **块按"条目序号"切, 不按 seq 切**。方案里的
// `chunks/000128-000255.jsonl` 假定 seq 是稠密的, 而这里的 seq 是宿主事件流的序号, 中间隔着
// tool/reasoning 那些不进账的事件 —— 拿 seq 当块名会留一堆空档。这里用条目序号区间
// (`chunks/000000-000015.jsonl`), 读的一侧按 `meta.lastSeq` 过滤, 语义与方案一致
//
// 三条硬性质 (与切段那一批同一个口径, 见 §14.2):
// - **封好的块不再改写**: 只有最末那一块随追加变大
// - **幂等**: 每条带宿主事件 seq, 追加只认 `seq > meta.lastSeq`, 重放不会长出重复条目
// - **meta 自校验**: `chunkEntries` / `entries` / `chunks` / `bytes` / `sha256`, 读的一侧可以
//   整组验一遍 (不过就整本不认)
//
// 写者唯一: `conv/<设备id>/<会话id>/` 只由那台设备写 (对端靠 ownershipOfPath 只读)

import { createHash } from 'node:crypto'
import { promises as fs } from 'node:fs'
import { join } from 'node:path'

import { safeSegment, writeJsonFile } from './luwi-inbox.mjs'

/** 仓库根下的对话账目录 */
export const TRANSCRIPT_DIR = 'conv'

/** 索引版本 */
export const TRANSCRIPT_VERSION = 1

/**
 * 一块装多少条。
 *
 * 16 条 × 单条上限 16 KB = 256 KB, 正好是方案文档 §5.4 给单块定的上限; 取小值是为了
 * "每轮重写最末一块"这件事只花几 KB (对话是每秒级追加的, 一块 128 条会每轮重传几十 KB)
 */
export const TRANSCRIPT_CHUNK_ENTRIES = 16

/** 单条正文的字节上限 (超了截断并标记; 16 KB 与 live 那条窗口的字数上限同量级) */
export const TRANSCRIPT_MAX_TEXT_BYTES = 16 * 1024

/**
 * 一本账第一次落盘时只种这么多条 (最近的那些)。
 *
 * 一场聊了几百轮的会话有几千条条目, 全种下去就是一次 `turn/end` 里写几百个文件、下一趟推送
 * 几 MB; 而对话账的用途是补 live 窗口 (40 条) 滑过游标的那一段, 256 条已经宽出一截。
 * 更老的历史留在归档 (sessions/) 里, 这本账只往后长
 */
export const TRANSCRIPT_SEED_ENTRIES = 256

/** 一段块文件的相对路径 (`chunks/000000-000015.jsonl`) */
export function chunkFileName(index) {
  const start = Math.max(0, Math.floor(index)) * TRANSCRIPT_CHUNK_ENTRIES
  return `chunks/${String(start).padStart(6, '0')}-${String(start + TRANSCRIPT_CHUNK_ENTRIES - 1).padStart(6, '0')}.jsonl`
}

/**
 * 一本账的相对目录 (`conv/<设备id>/<会话id>`, POSIX)。
 * @param {string} deviceId - 写这本账的设备 id。
 * @param {string} sessionId - 会话 id。
 * @returns {string} 相对路径。
 */
export function transcriptDirRel(deviceId, sessionId) {
  return `${TRANSCRIPT_DIR}/${safeSegment(deviceId)}/${safeSegment(sessionId)}`
}

/**
 * 一本账的绝对目录。
 * @param {string} repoDir - 同步工作树根。
 * @param {string} deviceId - 设备 id。
 * @param {string} sessionId - 会话 id。
 * @returns {string} 绝对路径。
 */
export function transcriptDir(repoDir, deviceId, sessionId) {
  return join(repoDir, TRANSCRIPT_DIR, safeSegment(deviceId), safeSegment(sessionId))
}

/**
 * 读索引 (没有/读不动/形态不对都回 null)。
 * @param {string} dirAbs - 账目录。
 * @returns {Promise<object|null>} 索引。
 */
export async function readTranscriptMeta(dirAbs) {
  try {
    const parsed = JSON.parse(await fs.readFile(join(dirAbs, 'meta.json'), 'utf8'))
    if (parsed === null || typeof parsed !== 'object') return null
    if (!Number.isFinite(parsed.entries) || !Number.isFinite(parsed.chunks)) return null
    return parsed
  } catch {
    return null
  }
}

/**
 * 一条条目的规范化 (对端写的/宿主给的都是不可信输入)。
 * @param {unknown} value - 原始条目。
 * @returns {{seq: number, role: string, text: string, at: number}|null} 合格条目。
 */
function normalizeEntry(value) {
  if (value === null || typeof value !== 'object') return null
  const raw = /** @type {Record<string, unknown>} */ (value)
  const seq = Number.isFinite(raw.seq) ? Math.floor(Number(raw.seq)) : 0
  if (seq <= 0) return null
  const role = raw.role === 'assistant' ? 'assistant' : raw.role === 'user' ? 'user' : ''
  if (role === '') return null
  const text = typeof raw.text === 'string' ? raw.text : ''
  if (text.trim() === '') return null
  return {
    seq,
    role,
    text: clipText(text, TRANSCRIPT_MAX_TEXT_BYTES),
    at: Number.isFinite(raw.at) ? Number(raw.at) : 0,
  }
}

/**
 * 按 UTF-8 字节数截断 (16 KB 那一条上限; 宁可少几个字, 不要一条把一块撑爆)。
 * @param {string} text - 正文。
 * @param {number} maxBytes - 上限。
 * @returns {string} 截断后的正文。
 */
function clipText(text, maxBytes) {
  if (Buffer.byteLength(text, 'utf8') <= maxBytes) return text
  let cut = text.length
  while (cut > 0) {
    cut = Math.floor(cut * 0.9)
    if (Buffer.byteLength(text.slice(0, cut), 'utf8') <= maxBytes) break
  }
  return text.slice(0, cut)
}

/**
 * 读一块 (缺文件回空表; 坏行跳过并计数, 绝不抛)。
 * @param {string} dirAbs - 账目录。
 * @param {number} index - 块序号。
 * @returns {Promise<{entries: Array<object>, badLines: number, bytes: number|null}>} 块内容。
 */
async function readChunk(dirAbs, index) {
  const file = join(dirAbs, ...chunkFileName(index).split('/'))
  let text
  try {
    text = await fs.readFile(file, 'utf8')
  } catch {
    return { entries: [], badLines: 0, bytes: null }
  }
  const entries = []
  let badLines = 0
  for (const line of text.split('\n')) {
    if (line.trim() === '') continue
    try {
      const entry = normalizeEntry(JSON.parse(line))
      if (entry === null) badLines += 1
      else entries.push(entry)
    } catch {
      badLines += 1
    }
  }
  return { entries, badLines, bytes: Buffer.byteLength(text, 'utf8') }
}

/**
 * 写一块 (原子: 先 .tmp 再 rename)。
 * @param {string} dirAbs - 账目录。
 * @param {number} index - 块序号。
 * @param {Array<object>} entries - 这一块的条目。
 * @returns {Promise<void>} 完成。
 */
async function writeChunk(dirAbs, index, entries) {
  const file = join(dirAbs, ...chunkFileName(index).split('/'))
  await fs.mkdir(join(dirAbs, 'chunks'), { recursive: true })
  const body = entries.map(entry => JSON.stringify({ seq: entry.seq, at: entry.at, role: entry.role, text: entry.text })).join('\n')
  const temp = `${file}.tmp`
  await fs.writeFile(temp, `${body}\n`, 'utf8')
  await fs.rename(temp, file)
}

/**
 * 把超出范围的多余块收掉 (追加只会在尾部加块; 出现多余块只能是上次写到一半)。
 * @param {string} dirAbs - 账目录。
 * @param {number} count - 这一本现在有几块。
 * @returns {Promise<number>} 收掉几块。
 */
async function pruneChunks(dirAbs, count) {
  let names
  try {
    names = await fs.readdir(join(dirAbs, 'chunks'))
  } catch {
    return 0
  }
  let removed = 0
  for (const name of names) {
    const match = /^(\d{6,})-\d{6,}\.jsonl$/u.exec(name)
    if (match === null) continue
    if (Number(match[1]) < count * TRANSCRIPT_CHUNK_ENTRIES) continue
    await fs.rm(join(dirAbs, 'chunks', name), { force: true })
    removed += 1
  }
  return removed
}

/**
 * 按顺序把每一块的字节加起来 (字节数与 sha256)。
 *
 * 一次追加要读一遍整本: 一本 6000 条的账约 1.2 MB, 而同一趟镜像本来就要读几 MB 的会话日志,
 * 这一笔不显眼; 换成增量哈希要往 meta 里塞每块摘要, 不值得
 * @param {string} dirAbs - 账目录。
 * @param {number} count - 块数。
 * @returns {Promise<{bytes: number, sha256: string}>} 摘要。
 */
async function digestChunks(dirAbs, count) {
  const hash = createHash('sha256')
  let bytes = 0
  for (let index = 0; index < count; index += 1) {
    try {
      const data = await fs.readFile(join(dirAbs, ...chunkFileName(index).split('/')))
      bytes += data.length
      hash.update(data)
    } catch {
      // 只可能是刚写的块还没落盘: 跳过 (meta 的 bytes 会因此偏小, 校验时如实报)
    }
  }
  return { bytes, sha256: hash.digest('hex') }
}

/**
 * 往一本账里追加条目 (幂等: 只认 `seq > meta.lastSeq` 的那些)。
 *
 * 只重写最末那一块 (加不下就往后开块), 封好的块一个字节都不动 —— 与切段那一批同一条理由:
 * 推送按 blob sha 去重, 封好的块不再上传
 * @param {string} dirAbs - 账目录 (不存在就建)。
 * @param {Array<{seq: number, role: string, text: string, at?: number}>} entries - 这一趟折出来的全部条目 (可以重复给, 幂等)。
 * @param {{deviceId?: string, sessionId?: string, title?: string, cwd?: string}} [extra] - 账面上那几行身份信息。
 * @returns {Promise<{appended: number, entries: number, chunks: number, lastSeq: number}>} 追加结果。
 */
export async function appendTranscriptEntries(dirAbs, entries, extra = {}) {
  const meta = await readTranscriptMeta(dirAbs)
  const lastSeq = Number.isFinite(meta?.lastSeq) ? Number(meta.lastSeq) : 0
  const chunkSize = Number.isFinite(meta?.chunkEntries) && meta.chunkEntries > 0
    ? Number(meta.chunkEntries)
    : TRANSCRIPT_CHUNK_ENTRIES
  /** 规范化之后的候选条目 (顺序就是给的顺序) */
  const candidates = []
  for (const raw of Array.isArray(entries) ? entries : []) {
    const entry = normalizeEntry(raw)
    if (entry !== null) candidates.push(entry)
  }
  // 第一次写这本账: 只种最近那一段 (见 TRANSCRIPT_SEED_ENTRIES), 老历史留在归档里
  const seeded = meta === null && candidates.length > TRANSCRIPT_SEED_ENTRIES
    ? candidates.slice(candidates.length - TRANSCRIPT_SEED_ENTRIES)
    : candidates
  const fresh = []
  const seen = new Set()
  for (const entry of seeded) {
    if (entry.seq <= lastSeq || seen.has(entry.seq)) continue
    seen.add(entry.seq)
    fresh.push(entry)
  }
  fresh.sort((left, right) => left.seq - right.seq)
  if (fresh.length === 0) {
    return {
      appended: 0,
      entries: Number.isFinite(meta?.entries) ? Number(meta.entries) : 0,
      chunks: Number.isFinite(meta?.chunks) ? Number(meta.chunks) : 0,
      lastSeq,
    }
  }
  const oldEntries = Number.isFinite(meta?.entries) ? Number(meta.entries) : 0
  const tailIndex = oldEntries === 0 ? 0 : Math.floor((oldEntries - 1) / chunkSize)
  const base = tailIndex * chunkSize
  const tail = await readChunk(dirAbs, tailIndex)
  const combined = [...tail.entries, ...fresh]
  let written = 0
  for (let at = 0; at < combined.length; at += chunkSize) {
    await writeChunk(dirAbs, tailIndex + written, combined.slice(at, at + chunkSize))
    written += 1
  }
  const chunks = tailIndex + written
  await pruneChunks(dirAbs, chunks)
  const total = base + combined.length
  const digest = await digestChunks(dirAbs, chunks)
  await writeJsonFile(join(dirAbs, 'meta.json'), {
    v: TRANSCRIPT_VERSION,
    deviceId: typeof extra.deviceId === 'string' && extra.deviceId !== '' ? extra.deviceId : String(meta?.deviceId ?? ''),
    sessionId: typeof extra.sessionId === 'string' && extra.sessionId !== '' ? extra.sessionId : String(meta?.sessionId ?? ''),
    title: typeof extra.title === 'string' && extra.title !== '' ? extra.title : String(meta?.title ?? ''),
    cwd: typeof extra.cwd === 'string' && extra.cwd !== '' ? extra.cwd : String(meta?.cwd ?? ''),
    createdAt: Number.isFinite(meta?.createdAt) ? Number(meta.createdAt) : Date.now(),
    chunkEntries: chunkSize,
    entries: total,
    chunks,
    lastSeq: combined[combined.length - 1].seq,
    bytes: digest.bytes,
    sha256: digest.sha256,
  })
  return { appended: fresh.length, entries: total, chunks, lastSeq: combined[combined.length - 1].seq }
}

/**
 * 读回一本账里 `seq > afterSeq` 的条目 (按 seq 升序)。
 * @param {string} dirAbs - 账目录。
 * @param {{afterSeq?: number}} [options] - 游标。
 * @returns {Promise<{ok: boolean, entries: Array<object>, meta: object|null, badLines: number, error?: string}>} 结果。
 */
export async function readTranscriptEntries(dirAbs, options = {}) {
  const afterSeq = Number.isFinite(options.afterSeq) ? Number(options.afterSeq) : 0
  const meta = await readTranscriptMeta(dirAbs)
  if (meta === null) return { ok: false, entries: [], meta: null, badLines: 0, error: 'no transcript index' }
  const chunkSize = Number.isFinite(meta.chunkEntries) && meta.chunkEntries > 0 ? Number(meta.chunkEntries) : TRANSCRIPT_CHUNK_ENTRIES
  const entries = []
  let badLines = 0
  for (let index = 0; index < Number(meta.chunks); index += 1) {
    const chunk = await readChunk(dirAbs, index)
    badLines += chunk.badLines
    for (const entry of chunk.entries) {
      if (entry.seq > afterSeq) entries.push(entry)
    }
  }
  entries.sort((left, right) => left.seq - right.seq || left.role.localeCompare(right.role))
  return { ok: true, entries, meta, badLines }
}

/**
 * 整本校验 (测试与运维用): 块数、条目数、字节数、sha256 四项。
 * @param {string} dirAbs - 账目录。
 * @returns {Promise<{ok: boolean, errors: string[], meta: object|null}>} 结果。
 */
export async function verifyTranscript(dirAbs) {
  const meta = await readTranscriptMeta(dirAbs)
  if (meta === null) return { ok: false, errors: ['no transcript index'], meta: null }
  const errors = []
  const hash = createHash('sha256')
  let bytes = 0
  let entries = 0
  let chunks = 0
  for (let index = 0; index < Number(meta.chunks); index += 1) {
    const chunk = await readChunk(dirAbs, index)
    if (chunk.bytes === null) {
      errors.push(`chunk ${index} is missing`)
      continue
    }
    const data = await fs.readFile(join(dirAbs, ...chunkFileName(index).split('/')))
    bytes += data.length
    hash.update(data)
    entries += chunk.entries.length
    chunks += 1
  }
  if (chunks !== Number(meta.chunks)) errors.push(`meta says ${meta.chunks} chunk(s), ${chunks} on disk`)
  if (entries !== Number(meta.entries)) errors.push(`meta says ${meta.entries} entr(ies), ${entries} on disk`)
  if (bytes !== Number(meta.bytes)) errors.push(`meta says ${meta.bytes} bytes, ${bytes} on disk`)
  if (hash.digest('hex') !== String(meta.sha256 ?? '')) errors.push('sha256 does not match')
  return { ok: errors.length === 0, errors, meta }
}
