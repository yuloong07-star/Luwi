// lib/p2p-peer.mjs — 一次 P2P 会话的状态机（方案 §5.5 / §6）：HELLO → HEADS → 两段半双工。
//
// 传输层只要求一个"帧进帧出"的流（`sendFrame(type, body)` / `nextFrame()`），所以这一层既不
// 认识 TCP 也不认识 QUIC —— 单测用内存里的一对队列驱动，环回 e2e 才把它接到 sidecar 的数据面。
//
// 两段的方向由 `deviceId` 字典序定（小者先拉），两端算出来必然一致：
//   先拉的一方: pull(remote heads) → BYE{reason:'turn'} → serve() → BYE{reason:'done'}
//   先服的一方: serve()（收到 BYE turn 返回） → pull(remote heads) → BYE{reason:'done'}
//
// 帧的用法（与 §5.4 的九个类型一致，不加新类型）：
//   HELLO        {v, deviceId, name, roots, caps}
//   HEADS        {roots:[{root, seq, digest, files, bytes}]}
//   MANIFEST_REQ {root, cursor?}                        → MANIFEST{root, digest, entries, next?}
//   NEED         {paths}                                → BLOB × batchTotal
//   BLOB         {path, sha256, size, jsonBytes, batch, batchTotal, index, set?} + 原始字节
//                        batchTotal=0 用一个 `path:''` 的空 BLOB 当"这次没东西"的应答
//   APPLY_DONE   {root, applied, skipped, bytes}
//   BYE          {reason: 'turn' | 'done'}
//   ERROR        {code, message}
//
// 收端对每条路径都先过 `mayReceive`（作者规则）；不过就 skipped++ 并记警告，不写盘。

import { promises as fs } from 'node:fs'
import { join } from 'node:path'

import { ERROR_CODES } from './constants.mjs'
import { encodeBlobBody, decodeBlobBody } from './p2p-frame.mjs'
import { SyncError } from './errors.mjs'
import {
  applyFile,
  applyTombstones,
  classifyPath,
  finalizeSet,
  isCollectionPath,
  mayReceive,
  maySend,
  parseTombstones,
  sha256Of,
} from './p2p-apply.mjs'
import { buildRootManifest, diffManifest, headsOf, localDigestMap } from './p2p-manifest.mjs'
import { verifyTranscript } from './luwi-transcript.mjs'

/** 会话内部用的帧类型名（与 p2p-frame 解出来的名字一致）。 */
const T = Object.freeze({
  HELLO: 'HELLO',
  HEADS: 'HEADS',
  MANIFEST_REQ: 'MANIFEST_REQ',
  MANIFEST: 'MANIFEST',
  NEED: 'NEED',
  BLOB: 'BLOB',
  APPLY_DONE: 'APPLY_DONE',
  BYE: 'BYE',
  ERROR: 'ERROR',
})

/** 协议版本（HELLO 里比；不符就 ERROR + 断流）。 */
export const P2P_SESSION_VERSION = 1

/** 拉取顺序（§5.6：删除最小最先，队列排在大文件前面）。 */
export const P2P_PULL_ORDER = Object.freeze(['tombstones', 'inbox', 'inbox-ack', 'live', 'peers', 'workspaces', 'conv'])

/** 一次 NEED 最多要多少个路径（再多的下一趟）。 */
export const P2P_NEED_LIMIT = 500

/** 会话能力（写进 HELLO，给以后加特性留的显式开关）。 */
export const P2P_SESSION_CAPS = Object.freeze(['conv-collections', 'tombstones', 'blob-batch'])

/**
 * 两端谁先拉：字典序小的先。
 * @param {string} myDeviceId - 本机 id。
 * @param {string} peerDeviceId - 对端 id。
 * @returns {'pull-first'|'serve-first'} 角色。
 */
export function sessionRole(myDeviceId, peerDeviceId) {
  return String(myDeviceId) < String(peerDeviceId) ? 'pull-first' : 'serve-first'
}

function bodyJson(frame) {
  if (frame === null || frame.body === undefined || frame.body.length === 0) return {}
  try {
    return JSON.parse(frame.body.toString('utf8'))
  } catch {
    throw new SyncError(ERROR_CODES.P2P_PROTOCOL, `p2p protocol: ${frame.type} body is not JSON`)
  }
}

function sendJson(stream, type, value) {
  // 流只搬"帧体"；组帧与解帧是 p2p-frame / 传输那一层的事
  return stream.sendFrame(type, Buffer.from(JSON.stringify(value), 'utf8'))
}

/**
 * 用一条边对边（内存或 socket）的帧流驱动一次会话。
 */
export class P2pPeerSession {
  /**
   * @param {object} options - {stream, repoDir, myDeviceId, peerDeviceId, deviceName, limits, now}。
   * @param {{sendFrame: Function, nextFrame: Function}} options.stream - 帧流。
   * @param {string} options.repoDir - 本机镜像根。
   * @param {string} options.myDeviceId - 本机设备 id。
   * @param {string} options.peerDeviceId - 对端设备 id（信任表那一层已确认）。
   * @param {string} [options.deviceName] - 名片上的名字（HELLO 用）。
   * @param {{maxBlobBytes?: number, needLimit?: number}} [options.limits] - 预算。
   */
  constructor({ stream, repoDir, myDeviceId, peerDeviceId, deviceName = '', limits = {} }) {
    this.stream = stream
    this.repoDir = repoDir
    this.myDeviceId = myDeviceId
    this.peerDeviceId = peerDeviceId
    this.deviceName = deviceName
    this.maxBlobBytes = limits.maxBlobBytes ?? 8 * 1024 * 1024
    this.needLimit = limits.needLimit ?? P2P_NEED_LIMIT
    this.localHeads = new Map()
    this.remoteHeads = new Map()
    this.warnings = []
    this.served = { files: 0, bytes: 0 }
    this.applied = { files: 0, bytes: 0, skipped: 0 }
    this.batch = 0
  }

  /**
   * 跑完整场会话。
   * @returns {Promise<{ok: boolean, role: string, pulled: object, served: object, warnings: string[]}>} 结果。
   */
  async run() {
    await this.#handshake()
    await this.#exchangeHeads()
    const role = sessionRole(this.myDeviceId, this.peerDeviceId)
    if (role === 'pull-first') {
      await this.#pullAll()
      await sendJson(this.stream, T.BYE, { reason: 'turn' })
      await this.#serveUntilTurn()
      await sendJson(this.stream, T.BYE, { reason: 'done' })
    } else {
      await this.#serveUntilTurn()
      await this.#pullAll()
      await sendJson(this.stream, T.BYE, { reason: 'done' })
    }
    return { ok: true, role, pulled: { ...this.applied }, served: { ...this.served }, warnings: [...this.warnings] }
  }

  async #handshake() {
    await sendJson(this.stream, T.HELLO, {
      v: P2P_SESSION_VERSION,
      deviceId: this.myDeviceId,
      name: this.deviceName,
      roots: P2P_PULL_ORDER,
      caps: P2P_SESSION_CAPS,
    })
    const frame = await this.#expect('HELLO')
    const hello = bodyJson(frame)
    if (hello.v !== P2P_SESSION_VERSION) {
      await this.#fail('P2P_PROTOCOL', `peer speaks v${String(hello.v)}, this build speaks v${P2P_SESSION_VERSION}`)
    }
    if (hello.deviceId !== this.peerDeviceId) {
      await this.#fail('P2P_UNTRUSTED', `peer claims ${String(hello.deviceId)}, expected ${this.peerDeviceId}`)
    }
  }

  async #exchangeHeads() {
    const manifests = []
    for (const root of P2P_PULL_ORDER) {
      manifests.push(await buildRootManifest({ repoDir: this.repoDir, root, deviceId: this.myDeviceId }))
    }
    const heads = headsOf({ manifests })
    await sendJson(this.stream, T.HEADS, heads)
    const frame = await this.#expect('HEADS')
    const remote = bodyJson(frame)
    for (const row of Array.isArray(remote.roots) ? remote.roots : []) {
      if (typeof row?.root === 'string') this.remoteHeads.set(row.root, row)
    }
    for (const manifest of manifests) this.localHeads.set(manifest.root, manifest)
  }

  /** 先拉的一方：按顺序把每个"对端摘要不同"的根拉一遍。 */
  async #pullAll() {
    for (const root of P2P_PULL_ORDER) {
      const remote = this.remoteHeads.get(root)
      if (remote === undefined) continue
      const local = this.localHeads.get(root)
      if (local !== undefined && local.digest === remote.digest) continue
      const entries = await this.#fetchManifest(root)
      const localMap = await localDigestMap({ repoDir: this.repoDir, root })
      const { need } = diffManifest({ remoteEntries: entries, local: localMap })
      if (need.length === 0) continue
      const applied = await this.#pullBlobs(root, need)
      if (root === 'tombstones') {
        const removed = await this.#applyPeerTombstones()
        applied.skipped += removed.skipped
        applied.bytes += removed.bytes
        this.tombstonesDeleted = (this.tombstonesDeleted ?? 0) + removed.deleted
      }
      await sendJson(this.stream, T.APPLY_DONE, { root, ...applied })
    }
  }

  /** 取一个根的完整清单（自动翻页）。 */
  async #fetchManifest(root) {
    const entries = []
    let cursor = undefined
    let digest = undefined
    for (let page = 0; page < 1000; page += 1) {
      await sendJson(this.stream, T.MANIFEST_REQ, cursor === undefined ? { root } : { root, cursor })
      const frame = await this.#expect('MANIFEST')
      const body = bodyJson(frame)
      if (body.root !== root) throw new SyncError(ERROR_CODES.P2P_PROTOCOL, `p2p protocol: asked ${root}, got ${String(body.root)}`)
      if (digest === undefined) digest = body.digest
      entries.push(...(Array.isArray(body.entries) ? body.entries : []))
      if (body.next === undefined || body.next === null) break
      cursor = String(body.next)
    }
    const remote = this.remoteHeads.get(root)
    if (remote !== undefined && typeof digest === 'string' && remote.digest !== digest) {
      this.warnings.push(`${root}: manifest digest changed mid-session (${String(remote.digest).slice(0, 12)} → ${digest.slice(0, 12)}), will re-check next round`)
    }
    return entries
  }

  /** 要一批路径并把 BLOB 落盘；集合整组落，单文件原子写。 */
  async #pullBlobs(root, need) {
    const paths = need.slice(0, this.needLimit)
    this.batch += 1
    await sendJson(this.stream, T.NEED, { paths })
    const first = await this.#expect('BLOB')
    const firstMeta = this.#blobMeta(first)
    const batch = firstMeta.batch
    const total = Number(firstMeta.batchTotal) || 0
    const blobs = []
    if (total > 0) blobs.push({ frame: first, meta: firstMeta })
    for (let index = 1; index < total; index += 1) {
      const frame = await this.#expect('BLOB')
      blobs.push({ frame, meta: this.#blobMeta(frame) })
    }
    const applied = { applied: 0, skipped: 0, bytes: 0 }
    const sets = new Map()
    for (const { frame, meta } of blobs) {
      const decoded = decodeBlobBody(frame.body)
      const path = String(meta.path ?? '')
      if (path === '') continue
      if (meta.batch !== batch) throw new SyncError(ERROR_CODES.P2P_PROTOCOL, 'p2p protocol: BLOB batch mismatch')
      if (sha256Of(decoded.bytes) !== meta.sha256) {
        applied.skipped += 1
        this.warnings.push(`${path}: sha256 mismatch, skipped`)
        continue
      }
      if (mayReceive(path, { myDeviceId: this.myDeviceId, peerDeviceId: this.peerDeviceId }) !== true) {
        applied.skipped += 1
        this.warnings.push(`${path}: author mismatch, skipped`)
        continue
      }
      if (typeof meta.set === 'string' && meta.set !== '') {
        const setPath = meta.set.replace(/\/+$/u, '')
        const rel = path.slice(setPath.length + 1)
        if (rel === '' || rel.startsWith('..')) {
          applied.skipped += 1
          this.warnings.push(`${path}: set-relative path is unsafe, skipped`)
          continue
        }
        let bucket = sets.get(setPath)
        if (bucket === undefined) {
          bucket = []
          sets.set(setPath, bucket)
        }
        bucket.push({ rel, bytes: decoded.bytes })
        continue
      }
      const result = await applyFile({ repoDir: this.repoDir, path, body: decoded.bytes, sha256: meta.sha256 })
      applied.applied += result.skipped ? 0 : 1
      applied.skipped += result.skipped ? 1 : 0
      applied.bytes += result.bytes
    }
    for (const [setPath, files] of sets) {
      const tmpRoot = join(this.repoDir, '.p2p', 'tmp', `session-${this.batch}-${setPath.split('/').join('-')}`)
      await fs.rm(tmpRoot, { recursive: true, force: true })
      for (const file of files) {
        const target = join(tmpRoot, ...file.rel.split('/'))
        await fs.mkdir(join(target, '..'), { recursive: true })
        await fs.writeFile(target, file.bytes)
      }
      const result = await finalizeSet({ repoDir: this.repoDir, setPath, tmpDir: tmpRoot, verify: verifyTranscript })
      if (result.ok) {
        applied.applied += files.length
        applied.bytes += files.reduce((total, file) => total + file.bytes.length, 0)
      } else {
        applied.skipped += files.length
        this.warnings.push(`${setPath}: set verification failed (${result.errors.join('; ')}), kept in .p2p/tmp`)
      }
    }
    this.applied.files += applied.applied
    this.applied.skipped += applied.skipped
    this.applied.bytes += applied.bytes
    return applied
  }

  #blobMeta(frame) {
    const decoded = decodeBlobBody(frame.body)
    return decoded.meta
  }

  /** 把对端的墓碑文件落到本机镜像（只删对端名下、只进 trash，并记账）。 */
  async #applyPeerTombstones() {
    const file = join(this.repoDir, 'tombstones', `${this.peerDeviceId}.json`)
    let text
    try {
      text = await fs.readFile(file, 'utf8')
    } catch {
      return { deleted: 0, skipped: 0, bytes: 0 }
    }
    const { entries } = parseTombstones(text)
    if (entries.length === 0) return { deleted: 0, skipped: 0, bytes: 0 }
    const result = await applyTombstones({ repoDir: this.repoDir, peerDeviceId: this.peerDeviceId, entries })
    this.warnings.push(...result.warnings)
    return { deleted: result.deleted.length, skipped: result.skipped.length, bytes: result.bytes }
  }

  /** 服务方：处理请求，直到 BYE{reason:'turn'}。 */
  async #serveUntilTurn() {
    for (;;) {
      const frame = await this.stream.nextFrame()
      if (frame === null || frame === undefined) return
      switch (frame.type) {
        case T.MANIFEST_REQ: {
          const body = bodyJson(frame)
          const root = String(body.root ?? '')
          const manifest = await buildRootManifest({
            repoDir: this.repoDir,
            root,
            deviceId: this.myDeviceId,
            cursor: body.cursor === undefined ? undefined : String(body.cursor),
          })
          await sendJson(this.stream, T.MANIFEST, manifest)
          break
        }
        case T.NEED: {
          const body = bodyJson(frame)
          await this.#serveNeed(Array.isArray(body.paths) ? body.paths : [])
          break
        }
        case T.APPLY_DONE: {
          const body = bodyJson(frame)
          this.served.files += Number(body.applied) || 0
          this.served.bytes += Number(body.bytes) || 0
          break
        }
        case T.BYE: {
          const body = bodyJson(frame)
          if (body.reason === 'turn' || body.reason === 'done') return
          break
        }
        case T.ERROR: {
          const body = bodyJson(frame)
          throw new SyncError(ERROR_CODES.P2P_PROTOCOL, `peer reported ${String(body.code)}: ${String(body.message)}`)
        }
        default:
          await this.#fail('P2P_PROTOCOL', `unexpected frame ${frame.type} while serving`)
      }
    }
  }

  /** 应答一次 NEED：每个请求路径一个 BLOB（集合展开成 meta + chunks）。 */
  async #serveNeed(paths) {
    const files = []
    for (const raw of paths) {
      const info = classifyPath(raw)
      if (info === null || info.p2p !== true) {
        this.warnings.push(`NEED: refusing ${JSON.stringify(raw)} (not a P2P path)`)
        continue
      }
      if (maySend(info.path, this.myDeviceId) !== true) {
        this.warnings.push(`NEED: refusing ${info.path} (author mismatch)`)
        continue
      }
      if (isCollectionPath(raw)) {
        const setPath = info.path
        const dirAbs = join(this.repoDir, ...setPath.split('/'))
        files.push(...await this.#listSetFiles(setPath, dirAbs))
        continue
      }
      files.push(info.path)
    }
    const blobs = []
    for (const path of files) {
      const abs = join(this.repoDir, ...path.split('/'))
      let data
      try {
        data = await fs.readFile(abs)
      } catch {
        this.warnings.push(`NEED: ${path} vanished before it could be sent`)
        continue
      }
      if (data.length > this.maxBlobBytes) {
        this.warnings.push(`NEED: ${path} is ${data.length} bytes, over the ${this.maxBlobBytes}-byte blob limit`)
        continue
      }
      const set = path.startsWith('conv/') ? `${path.split('/').slice(0, 3).join('/')}` : undefined
      blobs.push({ path, data, set })
    }
    this.batch += 1
    const batch = this.batch
    if (blobs.length === 0) {
      await this.stream.sendFrame(T.BLOB, encodeBlobBody({ path: '', sha256: '', size: 0, batch, batchTotal: 0, index: 0 }, Buffer.alloc(0)))
      return
    }
    for (const [index, blob] of blobs.entries()) {
      const meta = { path: blob.path, sha256: sha256Of(blob.data), size: blob.data.length, batch, batchTotal: blobs.length, index }
      if (blob.set !== undefined) meta.set = blob.set
      await this.stream.sendFrame(T.BLOB, encodeBlobBody(meta, blob.data))
    }
  }

  /** 列一个集合里的文件（meta.json + chunks/*.jsonl，排序稳定）。 */
  async #listSetFiles(setPath, dirAbs) {
    const out = [`${setPath}/meta.json`]
    try {
      const names = (await fs.readdir(join(dirAbs, 'chunks'))).filter(name => name.endsWith('.jsonl')).sort()
      for (const name of names) out.push(`${setPath}/chunks/${name}`)
    } catch {
      // 没有 chunks 目录: 只有 meta（坏集合，收端会校验失败）
    }
    return out
  }

  async #expect(type) {
    const frame = await this.stream.nextFrame()
    if (frame === null || frame === undefined) throw new SyncError(ERROR_CODES.P2P_IO, `p2p io: connection closed while waiting for ${type}`)
    if (frame.type === T.ERROR) {
      const body = bodyJson(frame)
      throw new SyncError(ERROR_CODES.P2P_PROTOCOL, `peer reported ${String(body.code)}: ${String(body.message)}`)
    }
    if (frame.type !== type) {
      throw new SyncError(ERROR_CODES.P2P_PROTOCOL, `p2p protocol: expected ${type}, got ${frame.type}`)
    }
    return frame
  }

  async #fail(code, message) {
    try {
      await sendJson(this.stream, T.ERROR, { code, message })
    } catch {
      // 流已经断了也照样抛下面的错
    }
    throw new SyncError(code === 'P2P_UNTRUSTED' ? ERROR_CODES.P2P_UNTRUSTED : ERROR_CODES.P2P_PROTOCOL, `p2p session: ${message}`)
  }
}

