// lib/p2p-frame.mjs — P2P 帧的编解码（纯函数 + 一个流式解帧器；零 I/O）。
//
// 线格式（方案 §5.4）: magic 'LW' 2B | ver 1B | type 1B | bodyLen u32be 4B, body ≤ 8 MiB。
// 帧按"每段严格请求-应答、不交错"使用（§5.5），所以没有 reqId / 多路复用 / 去重表 —— 这一层
// 只回答"字节怎么切成帧"，不回答"帧是什么意思"。业务体一律由上层 JSON 编码，只有 BLOB 的
// body 是"JSON 头 + 原始字节"（见 decodeBlobBody / encodeBlobBody）。
//
// 方案 §5.4 的汇总句写"帧只加八种"，但同一张表与 §5.5 的时序里都有 MANIFEST_REQ —— 这里按
// 时序实现九个类型；`HEADS` 的"有变化吗"由摘要比对回答，不需要单独的类型。

import { ERROR_CODES } from './constants.mjs'
import { SyncError } from './errors.mjs'

/** 'LW'，大端 u16。 */
export const P2P_MAGIC = 0x4c57
/** 帧格式版本。 */
export const P2P_VERSION = 1
/** 头长度：2 + 1 + 1 + 4。 */
export const P2P_HEADER_BYTES = 8
/** body 上限，与 `maxBlobBytes` 对齐。 */
export const P2P_MAX_BODY_BYTES = 8 * 1024 * 1024

/** 类型编号（线上只有这一个 u8，名字只用于日志与错误）。 */
export const P2P_FRAME_TYPES = Object.freeze({
  HELLO: 1,
  HEADS: 2,
  MANIFEST_REQ: 3,
  MANIFEST: 4,
  NEED: 5,
  BLOB: 6,
  APPLY_DONE: 7,
  BYE: 8,
  ERROR: 9,
})

/** 编号 → 名字（错误信息用）。 */
const FRAME_NAME_BY_ID = new Map(Object.entries(P2P_FRAME_TYPES).map(([name, id]) => [id, name]))

/**
 * 协议错误（坏 magic / 版本不符 / 超长 / 未知类型）。
 * @param {string} message - 具体说明。
 * @param {object} [details] - 机器可读事实。
 * @returns {SyncError} code=P2P_PROTOCOL。
 */
export function p2pProtocol(message, details = undefined) {
  return new SyncError(ERROR_CODES.P2P_PROTOCOL, `p2p protocol: ${message}`, details)
}

/**
 * 帧类型名或编号 → 编号。
 * @param {string|number} type - P2P_FRAME_TYPES 的键或值。
 * @returns {number} 1..255。
 */
export function frameTypeId(type) {
  if (typeof type === 'number') {
    if (FRAME_NAME_BY_ID.has(type)) return type
    throw p2pProtocol(`unknown frame type ${type}`)
  }
  const id = P2P_FRAME_TYPES[String(type).toUpperCase()]
  if (id === undefined) throw p2pProtocol(`unknown frame type ${JSON.stringify(type)}`)
  return id
}

/**
 * 帧类型编号 → 名字。
 * @param {number} id - 类型编号。
 * @returns {string|undefined} 名字。
 */
export function frameTypeName(id) {
  return FRAME_NAME_BY_ID.get(id)
}

/**
 * 编一帧。
 * @param {string|number} type - 帧类型。
 * @param {Buffer} [body] - 体（缺省空）。
 * @returns {Buffer} 可直接写进 socket 的字节。
 */
export function encodeFrame(type, body = Buffer.alloc(0)) {
  const id = frameTypeId(type)
  if (!Buffer.isBuffer(body)) throw p2pProtocol('frame body must be a Buffer', { type: frameTypeName(id) })
  if (body.length > P2P_MAX_BODY_BYTES) {
    throw p2pProtocol(`frame body is ${body.length} bytes, over the ${P2P_MAX_BODY_BYTES}-byte limit`, { type: frameTypeName(id) })
  }
  const header = Buffer.alloc(P2P_HEADER_BYTES)
  header.writeUInt16BE(P2P_MAGIC, 0)
  header[2] = P2P_VERSION
  header[3] = id
  header.writeUInt32BE(body.length, 4)
  return Buffer.concat([header, body])
}

/**
 * 编一帧，体是 JSON（业务帧都用这个）。
 * @param {string|number} type - 帧类型。
 * @param {unknown} value - 可 JSON 序列化的体。
 * @returns {Buffer} 帧字节。
 */
export function encodeJsonFrame(type, value) {
  return encodeFrame(type, Buffer.from(JSON.stringify(value), 'utf8'))
}

/**
 * BLOB 的体：一行 JSON 头 + 原始字节（JSON 里带 `jsonBytes`，收端据此切开）。
 * @param {object} meta - {path, sha256, size} 等。
 * @param {Buffer} bytes - 文件字节。
 * @returns {Buffer} 体。
 */
export function encodeBlobBody(meta, bytes) {
  if (!Buffer.isBuffer(bytes)) throw p2pProtocol('BLOB payload must be a Buffer')
  const header = Buffer.from(JSON.stringify({ ...meta, jsonBytes: 0 }), 'utf8')
  const withLength = Buffer.from(JSON.stringify({ ...meta, jsonBytes: header.length }), 'utf8')
  if (withLength.length !== header.length) {
    // jsonBytes 的位数变了（极少见）: 按真实长度重算一次，直到稳定
    return encodeBlobBodyWithStableLength(meta, bytes)
  }
  return Buffer.concat([withLength, bytes])
}

/** 上面那个"长度自指"的两步收敛（jsonBytes 的十进制位数变了就再来一次）。 */
function encodeBlobBodyWithStableLength(meta, bytes) {
  let jsonBytes = 0
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const header = Buffer.from(JSON.stringify({ ...meta, jsonBytes }), 'utf8')
    if (header.length === jsonBytes) return Buffer.concat([header, bytes])
    jsonBytes = header.length
  }
  throw p2pProtocol('could not stabilise the BLOB header length')
}

/**
 * 拆 BLOB 的体。
 * @param {Buffer} body - 帧体。
 * @returns {{meta: object, bytes: Buffer}} 结果。
 */
export function decodeBlobBody(body) {
  if (!Buffer.isBuffer(body)) throw p2pProtocol('BLOB body must be a Buffer')
  const probe = body.subarray(0, Math.min(body.length, 4096)).toString('utf8')
  // 不能拿第一个 '}' 当边界 (path 里完全可能有它), 先读 jsonBytes 再精确切
  const match = /"jsonBytes"\s*:\s*(\d+)/u.exec(probe)
  if (!match) throw p2pProtocol('BLOB body has no jsonBytes header')
  const jsonBytes = Number(match[1])
  if (!Number.isInteger(jsonBytes) || jsonBytes < 2 || jsonBytes > body.length) {
    throw p2pProtocol('BLOB header has a bad jsonBytes')
  }
  let meta
  try {
    meta = JSON.parse(body.subarray(0, jsonBytes).toString('utf8'))
  } catch (error) {
    throw p2pProtocol(`BLOB header is not JSON: ${error instanceof Error ? error.message : String(error)}`)
  }
  return { meta, bytes: body.subarray(jsonBytes) }
}

/**
 * 流式解帧器：push 任意切分的字节，吐出完整帧；半帧留在内部缓冲。
 *
 * 坏 magic / 版本不符 / 超长 / 未知类型都立刻抛 P2P_PROTOCOL（不清缓冲，由调用方断流）。
 */
export class P2pFrameDecoder {
  /**
   * @param {object} [options] - {maxBodyBytes}。
   * @param {number} [options.maxBodyBytes] - 覆盖 8 MiB 的上限（测试用）。
   */
  constructor(options = {}) {
    this.maxBodyBytes = options.maxBodyBytes ?? P2P_MAX_BODY_BYTES
    /** @type {Buffer} */
    this.buffer = Buffer.alloc(0)
  }

  /**
   * 喂一段字节。
   * @param {Buffer|Uint8Array} chunk - 收到的字节。
   * @returns {{type: string, body: Buffer}[]} 本次凑齐的帧。
   */
  push(chunk) {
    if (!Buffer.isBuffer(chunk)) chunk = Buffer.from(chunk)
    this.buffer = this.buffer.length === 0 ? Buffer.from(chunk) : Buffer.concat([this.buffer, chunk])
    const frames = []
    while (this.buffer.length >= P2P_HEADER_BYTES) {
      const magic = this.buffer.readUInt16BE(0)
      if (magic !== P2P_MAGIC) {
        throw p2pProtocol(`bad magic 0x${magic.toString(16)} (expected 0x${P2P_MAGIC.toString(16)})`)
      }
      const version = this.buffer[2]
      if (version !== P2P_VERSION) throw p2pProtocol(`unsupported frame version ${version} (expected ${P2P_VERSION})`)
      const typeId = this.buffer[3]
      const name = frameTypeName(typeId)
      if (name === undefined) throw p2pProtocol(`unknown frame type ${typeId}`)
      const length = this.buffer.readUInt32BE(4)
      if (length > this.maxBodyBytes) {
        throw p2pProtocol(`frame body is ${length} bytes, over the ${this.maxBodyBytes}-byte limit`, { type: name })
      }
      if (this.buffer.length < P2P_HEADER_BYTES + length) break
      const body = this.buffer.subarray(P2P_HEADER_BYTES, P2P_HEADER_BYTES + length)
      this.buffer = this.buffer.subarray(P2P_HEADER_BYTES + length)
      frames.push({ type: name, body: Buffer.from(body) })
    }
    return frames
  }

  /** 还有半帧没凑齐吗（收尾时用）。 */
  get pending() {
    return this.buffer.length
  }
}
