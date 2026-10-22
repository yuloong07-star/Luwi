// lib/luwi-parts.mjs — Luwi 新增文件 (2026-10-22): 会话日志按 zstd 帧边界切段
//
// 为什么要有它: 会话日志是 append-only 的多帧 zstd (每写一批追加一帧, 实测 5.5 MB 的会话是
// 4077 帧, 每帧平均 1.4 KB), 而同步链路在 PC 上只走 `api.github.com` 的 REST (github.com:443
// 不通), 那一路**没有 delta** —— 文件改一个字节就要重传整份。于是每聊一句, 5 MB 的会话就整
// 份再传一次, 实测每轮只长 60~80 KB, 98% 的流量是重复的
//
// 切段的口径:
// - **只切整帧**: 帧与帧之间可以任意拼接 (zstd 多帧文件本来就是这个形态), 拼回来的字节与
//   源文件逐字节相同, 于是取回那一侧不需要懂事件格式, 也不用重压缩
// - **按字节上限贪心累积**: 前面切好的段在追加时一个字节都不变 (已验证: 镜像里那份 5046 KB
//   是源文件 5556 KB 的精确前缀), 于是只有"最末一段"会随新帧增长, 一次推送最多重传一段
// - 段名 `parts/0000.bin` …, 再放一份小索引 `parts/index.json` 记 `file` / `bytes` / `parts`,
//   取回端靠它判断"这份会话是不是切段存的"以及"段齐了没有"
//
// 这一份只做纯算术与两次读盘, 不碰引擎也不碰宿主

import { promises as fs } from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'

/** 段目录名 (与 `session.v4.jsonl.zstd` 同级) */
export const PARTS_DIR = 'parts'

/** 索引文件名 (段目录里) */
export const PARTS_INDEX = 'index.json'

/** 索引版本 */
export const PARTS_VERSION = 1

/** 段文件名 (零填充四位, 最多 10000 段 ≈ 2.5 GB 的量级) */
export const PART_FILE_RE = /^(\d{4,})\.bin$/u

/** 单段上限的缺省值 (256 KiB): 一次推送最多重传这么大一块 */
export const DEFAULT_PART_BYTES = 256 * 1024

/** 低于这个大小不值得切段 (整份传一次也不贵) */
export const DEFAULT_CHUNK_MIN_BYTES = 256 * 1024

/** zstd 帧的魔数 */
const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd])

/**
 * 一份 zstd 文件里每一帧的起始偏移。
 *
 * 只认文件头那一个魔数 (不是 zstd 帧就不切), 其余从第 4 个字节往后扫。索引共用的那个字节序
 * 出现在压缩正文里的概率很低, 而且就算扫错, 切出来的段**拼回去仍是原文件** (段边界不必是帧
 * 边界), 代价只是那一段会随追加跟着重写
 * @param {Buffer} bytes - 整份文件。
 * @returns {number[]} 帧起始偏移 (升序); 不是 zstd 时回空数组。
 */
export function zstdFrameOffsets(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < ZSTD_MAGIC.length) return []
  if (!bytes.subarray(0, ZSTD_MAGIC.length).equals(ZSTD_MAGIC)) return []
  const offsets = [0]
  let at = bytes.indexOf(ZSTD_MAGIC, ZSTD_MAGIC.length)
  while (at !== -1) {
    offsets.push(at)
    at = bytes.indexOf(ZSTD_MAGIC, at + ZSTD_MAGIC.length)
  }
  return offsets
}

/**
 * 把一份会话日志切成若干段 (按帧边界贪心累积到 maxBytes)。
 *
 * 回来的段是按顺序拼得回原文件的连续切片; 只有**最末一段**会随追加变化, 前面那些是稳定的
 * @param {Buffer} bytes - 整份文件。
 * @param {number} maxBytes - 单段目标上限 (至少 1 字节; 单帧超过上限时它自己成一段)。
 * @returns {Buffer[]|null} 段; 不是多帧 zstd (或只有一帧) 时回 null, 调用方照旧整份写。
 */
export function splitSessionParts(bytes, maxBytes) {
  const limit = Number.isFinite(maxBytes) && maxBytes > 0 ? Math.floor(maxBytes) : DEFAULT_PART_BYTES
  const offsets = zstdFrameOffsets(bytes)
  if (offsets.length < 2) return null
  const parts = []
  let start = offsets[0]
  for (let index = 1; index < offsets.length; index += 1) {
    const end = offsets[index]
    if (end - start >= limit) {
      parts.push(bytes.subarray(start, end))
      start = end
    }
  }
  parts.push(bytes.subarray(start, bytes.length))
  return parts
}

/**
 * 段文件名 (`0000.bin` …)。
 * @param {number} index - 段序号 (从 0 起)。
 * @returns {string} 文件名。
 */
export function partFileName(index) {
  return `${String(index).padStart(4, '0')}.bin`
}

/**
 * 某份会话文件的段目录相对路径 (`<会话目录>/parts`)。
 * @param {string} baseRel - 会话文件相对路径 (POSIX)。
 * @returns {string} 段目录相对路径。
 */
export function partsDirOf(baseRel) {
  const cut = baseRel.lastIndexOf('/')
  return cut === -1 ? PARTS_DIR : `${baseRel.slice(0, cut)}/${PARTS_DIR}`
}

/**
 * 一条相对路径是不是"段目录里的索引" (取回那侧用它认出切段存的会话)。
 * @param {string} rel - 相对路径 (POSIX)。
 * @returns {boolean} 是索引就 true。
 */
export function isPartsIndexRel(rel) {
  if (typeof rel !== 'string') return false
  const segments = rel.split('/')
  return segments.length >= 3 && segments[segments.length - 1] === PARTS_INDEX
    && segments[segments.length - 2] === PARTS_DIR
}

/**
 * 读一份索引 (读不动/形态不对都回 null, 调用方当"没有切段"处理)。
 * @param {string} dir - 段目录绝对路径。
 * @returns {Promise<{v?: number, file?: string, bytes?: number, parts?: number}|null>} 索引。
 */
export async function readPartsIndex(dir) {
  try {
    const text = await fs.readFile(path.join(dir, PARTS_INDEX), 'utf8')
    const parsed = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object') return null
    if (typeof parsed.file !== 'string' || parsed.file === '') return null
    if (!Number.isFinite(parsed.bytes) || !Number.isFinite(parsed.parts)) return null
    return parsed
  } catch {
    return null
  }
}

/**
 * 一条相对路径是不是段目录里的段文件 (`…/parts/0004.bin`)。
 *
 * 与 [isPartsIndexRel] 分开的理由: 索引缺了的时候段还在, 而那些段**绝不能**当普通会话文件
 * 取回本机 (否则会话库里会多出一个 `parts/0004.bin`) —— 取回端拿它把"没有索引的段"整片跳过
 * @param {string} rel - 相对路径 (POSIX)。
 * @returns {boolean} 是段文件就 true。
 */
export function isPartFileRel(rel) {
  if (typeof rel !== 'string' || rel === '') return false
  const segments = rel.split('/')
  if (segments.length < 2) return false
  return segments[segments.length - 2] === PARTS_DIR && PART_FILE_RE.test(segments[segments.length - 1])
}

/**
 * 一条相对路径所在的段目录 (`…/parts/0004.bin` 与 `…/parts/index.json` 都给 `…/parts`)。
 * @param {string} rel - 相对路径 (POSIX)。
 * @returns {string|null} 段目录; 不在段目录里回 null。
 */
export function partsDirRelOf(rel) {
  if (isPartsIndexRel(rel)) return rel.slice(0, rel.lastIndexOf('/'))
  if (isPartFileRel(rel)) {
    const segments = rel.split('/')
    return segments.slice(0, segments.length - 1).join('/')
  }
  return null
}

/**
 * 一份索引写明的段文件名 (顺序即拼接顺序)。
 * @param {{parts?: number}|null} index - 段索引。
 * @returns {string[]} 段文件名。
 */
export function partsSetNames(index) {
  const count = Number.isFinite(index?.parts) ? Math.max(0, Math.floor(Number(index.parts))) : 0
  const names = []
  for (let at = 0; at < count; at += 1) names.push(partFileName(at))
  return names
}

/**
 * 一个段目录的现状: 索引、缺哪些段、多了哪些段、齐不齐。
 *
 * 只 stat 索引写明的那些名字加一次 readdir, **不读段内容** —— 取回端"拼装之前先问这里"，
 * 齐了才调 [assembleParts]，于是空闲的一趟不会因为"看看组齐没齐"把整棵树读一遍
 * @param {string} dir - 段目录绝对路径。
 * @returns {Promise<{index: object|null, missing: string[], extra: string[], complete: boolean}>} 现状。
 */
export async function inspectPartsSet(dir) {
  const index = await readPartsIndex(dir)
  if (index === null) return { index: null, missing: [], extra: [], complete: false }
  const names = partsSetNames(index)
  const missing = []
  let total = 0
  for (const name of names) {
    try {
      const info = await fs.stat(path.join(dir, name))
      if (info.isFile()) total += info.size
      else missing.push(name)
    } catch {
      missing.push(name)
    }
  }
  let present = []
  try {
    present = await fs.readdir(dir)
  } catch {
    present = []
  }
  const wanted = new Set(names)
  const extra = present.filter(name => PART_FILE_RE.test(name) && !wanted.has(name))
  const complete = missing.length === 0 && names.length > 0 && total === Number(index.bytes)
  return { index, missing, extra, complete }
}

/**
 * 两边各有一份段集合时选哪一边 (整组落或整组留)。
 *
 * 口径只有一条: `bytes` 大的那一份更完整 (会话日志 append-only, 短的是旧的); 一样大时留本边 ——
 * 段内容不同而总长相同的概率极低, 那时候宁可不动, 让下一趟镜像按本地源重切自愈
 * @param {{bytes?: number}|null} ours - 本地索引 (没有回 null)。
 * @param {{bytes?: number}|null} theirs - 对端索引 (没有回 null)。
 * @returns {'ours'|'theirs'|'none'} 选中的那一侧; 两边都没有可用字节时回 none。
 */
export function choosePartsSetSide(ours, theirs) {
  const ourBytes = Number.isFinite(ours?.bytes) ? Number(ours.bytes) : null
  const theirBytes = Number.isFinite(theirs?.bytes) ? Number(theirs.bytes) : null
  if (ourBytes === null && theirBytes === null) return 'none'
  if (theirBytes === null) return 'ours'
  if (ourBytes === null) return 'theirs'
  return theirBytes > ourBytes ? 'theirs' : 'ours'
}

/**
 * 把段目录里的段按序拼起来 (只认索引里写明的段数, 少一段就回 null)。
 *
 * 索引里带 `sha256` 的一律核对: 段是**逐段各自更新**的 (对端推来的那一版可能与本地这一版
 * 不是同一条血脉), 拼出来的字节一旦对不上就整份不认 —— 宁可这一趟不取回, 让下一次镜像
 * 用本地源文件重新切段, 也不能把两条血脉的段拼成一份会话写进会话库
 * @param {string} dir - 段目录绝对路径。
 * @returns {Promise<{bytes: Buffer, index: object}|null>} 拼好的字节与索引; 不完整/对不上时回 null。
 */
export async function assembleParts(dir) {
  const index = await readPartsIndex(dir)
  if (index === null || index.parts <= 0) return null
  const chunks = []
  let total = 0
  for (let at = 0; at < index.parts; at += 1) {
    let bytes
    try {
      bytes = await fs.readFile(path.join(dir, partFileName(at)))
    } catch {
      return null
    }
    chunks.push(bytes)
    total += bytes.length
  }
  if (total !== index.bytes) return null
  const bytes = Buffer.concat(chunks, total)
  if (typeof index.sha256 === 'string' && index.sha256 !== '') {
    const digest = createHash('sha256').update(bytes).digest('hex')
    if (digest !== index.sha256) return null
  }
  return { bytes, index }
}
