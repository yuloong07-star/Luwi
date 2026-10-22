// test/p2p-frame.test.mjs — 8 字节头的编解码、流式拼帧与 BLOB 体（方案 §5.4 / §13）。

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  P2P_FRAME_TYPES,
  P2P_HEADER_BYTES,
  P2P_MAGIC,
  P2P_MAX_BODY_BYTES,
  P2P_VERSION,
  P2pFrameDecoder,
  decodeBlobBody,
  encodeBlobBody,
  encodeFrame,
  encodeJsonFrame,
  frameTypeId,
  frameTypeName,
} from '../lib/p2p-frame.mjs'
import { SyncError } from '../lib/errors.mjs'

const isProtocolError = (error) => error instanceof SyncError && error.code === 'P2P_PROTOCOL'

test('header layout: magic, version, type, big-endian body length', () => {
  const frame = encodeJsonFrame('HELLO', { v: 1 })
  assert.equal(frame.readUInt16BE(0), P2P_MAGIC)
  assert.equal(frame[2], P2P_VERSION)
  assert.equal(frame[3], P2P_FRAME_TYPES.HELLO)
  assert.equal(frame.readUInt32BE(4), frame.length - P2P_HEADER_BYTES)
  assert.equal(frame.subarray(P2P_HEADER_BYTES).toString('utf8'), '{"v":1}')
})

test('roundtrip JSON frames, including an empty body', () => {
  const decoder = new P2pFrameDecoder()
  const frames = decoder.push(Buffer.concat([
    encodeJsonFrame('HEADS', { roots: [] }),
    encodeFrame('BYE'),
  ]))
  assert.deepEqual(frames.map(frame => frame.type), ['HEADS', 'BYE'])
  assert.deepEqual(JSON.parse(frames[0].body.toString('utf8')), { roots: [] })
  assert.equal(frames[1].body.length, 0)
  assert.equal(decoder.pending, 0)
})

test('streaming decoder reassembles a frame split at every byte boundary', () => {
  const frame = encodeJsonFrame('MANIFEST', { root: 'conv', entries: [] })
  const decoder = new P2pFrameDecoder()
  const collected = []
  for (const byte of frame) collected.push(...decoder.push(Buffer.from([byte])))
  assert.equal(collected.length, 1)
  assert.equal(collected[0].type, 'MANIFEST')
  assert.deepEqual(JSON.parse(collected[0].body.toString('utf8')), { root: 'conv', entries: [] })
})

test('partial frame stays buffered and reports pending bytes', () => {
  const frame = encodeJsonFrame('NEED', { paths: ['a'] })
  const decoder = new P2pFrameDecoder()
  assert.deepEqual(decoder.push(frame.subarray(0, 5)), [])
  assert.equal(decoder.pending, 5)
  const rest = decoder.push(frame.subarray(5))
  assert.equal(rest.length, 1)
  assert.equal(decoder.pending, 0)
})

test('bad magic, bad version, unknown type and oversize body all raise P2P_PROTOCOL', () => {
  const good = encodeFrame('BYE')
  const badMagic = Buffer.from(good)
  badMagic.writeUInt16BE(0x4242, 0)
  assert.throws(() => new P2pFrameDecoder().push(badMagic), isProtocolError)

  const badVersion = Buffer.from(good)
  badVersion[2] = 99
  assert.throws(() => new P2pFrameDecoder().push(badVersion), isProtocolError)

  const unknownType = Buffer.from(good)
  unknownType[3] = 200
  assert.throws(() => new P2pFrameDecoder().push(unknownType), isProtocolError)

  const tiny = new P2pFrameDecoder({ maxBodyBytes: 4 })
  assert.throws(() => tiny.push(encodeFrame('BLOB', Buffer.alloc(5))), isProtocolError)
  assert.throws(() => encodeFrame('BLOB', Buffer.alloc(P2P_MAX_BODY_BYTES + 1)), isProtocolError)
  assert.throws(() => encodeFrame('NOPE', Buffer.alloc(0)), isProtocolError)
  assert.throws(() => frameTypeId(0), isProtocolError)
  assert.equal(frameTypeName(1), 'HELLO')
  assert.equal(frameTypeName(200), undefined)
})

test('encodeBlobBody/decodeBlobBody roundtrip keeps the payload bytes and a self-describing header', () => {
  const bytes = Buffer.from(Array.from({ length: 256 }, (_, index) => index & 0xff))
  const body = encodeBlobBody({ path: 'conv/a/b/meta.json', sha256: 'f'.repeat(64), size: bytes.length }, bytes)
  const decoded = decodeBlobBody(body)
  assert.equal(decoded.meta.path, 'conv/a/b/meta.json')
  assert.equal(decoded.meta.size, bytes.length)
  assert.equal(decoded.meta.jsonBytes, body.length - bytes.length)
  assert.deepEqual([...decoded.bytes], [...bytes])
})

test('BLOB header with braces in the path still splits correctly', () => {
  const bytes = Buffer.from('hello')
  const body = encodeBlobBody({ path: 'conv/x/}{ weird name/', sha256: 'a'.repeat(64), size: 5 }, bytes)
  const decoded = decodeBlobBody(body)
  assert.equal(decoded.meta.path, 'conv/x/}{ weird name/')
  assert.equal(decoded.bytes.toString('utf8'), 'hello')
})

test('malformed BLOB bodies raise P2P_PROTOCOL', () => {
  assert.throws(() => decodeBlobBody(Buffer.from('no header here')), isProtocolError)
  assert.throws(() => decodeBlobBody(Buffer.from('{"jsonBytes": 999}')), isProtocolError)
  assert.throws(() => decodeBlobBody(Buffer.from('{"jsonBytes": "x"}')), isProtocolError)
})
