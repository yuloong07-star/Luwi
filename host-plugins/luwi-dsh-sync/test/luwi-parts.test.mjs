// test/luwi-parts.test.mjs — 会话日志按 zstd 帧切段 (2026-10-22)。
// 覆盖: 只切多帧 zstd / 段拼回逐字节相同 / 追加只动最末一段 / 镜像落成 parts 布局 /
// 取回时拼回整份 / 源删除时段与索引一起收掉。

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import { randomBytes } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import { mirrorSessionRoot } from '../lib/mirror.mjs'
import { adoptMergedSessions } from '../lib/luwi-adopt.mjs'
import {
  assembleParts,
  choosePartsSetSide,
  inspectPartsSet,
  isPartFileRel,
  partFileName,
  partsDirOf,
  partsDirRelOf,
  partsSetNames,
  readPartsIndex,
  splitSessionParts,
  zstdFrameOffsets,
} from '../lib/luwi-parts.mjs'

async function makeTemp() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-session-sync-parts-'))
  return { root, clean: () => fs.rm(root, { recursive: true, force: true }) }
}

/** 造一份"多帧 zstd": 每段单独压一帧再拼起来, 与宿主的 append-only 写入口径一致 */
function framesOf(count, size = 120) {
  const out = []
  for (let at = 0; at < count; at += 1) {
    // 用随机字节: 压缩率恒定, 段大小才可预期 (全零那份一帧只有十几个字节)
    out.push(zlib.zstdCompressSync(randomBytes(size)))
  }
  return out
}

test('frame scan only cuts real multi-frame zstd', () => {
  const frames = framesOf(3)
  const joined = Buffer.concat(frames)
  assert.equal(zstdFrameOffsets(joined).length, 3)
  assert.equal(zstdFrameOffsets(Buffer.from('not zstd at all\n')).length, 0)
  // 单帧不值得切 (切了也只是把整份换一个名字)
  assert.equal(splitSessionParts(frames[0], 64), null)
  const parts = splitSessionParts(joined, 64)
  assert.ok(Array.isArray(parts))
  assert.ok(parts.length >= 2)
  assert.deepEqual(Buffer.concat(parts), joined)
})

test('parts dir naming is derived from the session file', () => {
  assert.equal(partsDirOf('a/b/session.v4.jsonl.zstd'), 'a/b/parts')
  assert.equal(partsDirOf('session.jsonl.zstd'), 'parts')
  assert.equal(partFileName(0), '0000.bin')
  assert.equal(partFileName(11), '0011.bin')
})

test('parts set helpers: file vs index, dir derivation, completeness, side choice', async (t) => {
  assert.equal(isPartFileRel('a/parts/0004.bin'), true)
  assert.equal(isPartFileRel('a/parts/index.json'), false)
  assert.equal(isPartFileRel('a/0004.bin'), false)
  assert.equal(isPartFileRel('a/parts/0004.bin.tmp'), false)
  assert.equal(partsDirRelOf('a/parts/0004.bin'), 'a/parts')
  assert.equal(partsDirRelOf('a/parts/index.json'), 'a/parts')
  assert.equal(partsDirRelOf('a/session.v4.jsonl.zstd'), null)
  assert.deepEqual(partsSetNames({ parts: 3 }), ['0000.bin', '0001.bin', '0002.bin'])
  assert.deepEqual(partsSetNames(null), [])
  // 整组胜负: bytes 大的更完整, 一样大留本地
  assert.equal(choosePartsSetSide({ bytes: 10 }, { bytes: 20 }), 'theirs')
  assert.equal(choosePartsSetSide({ bytes: 20 }, { bytes: 10 }), 'ours')
  assert.equal(choosePartsSetSide({ bytes: 10 }, { bytes: 10 }), 'ours')
  assert.equal(choosePartsSetSide({ bytes: 10 }, null), 'ours')
  assert.equal(choosePartsSetSide(null, { bytes: 10 }), 'theirs')
  assert.equal(choosePartsSetSide(null, null), 'none')

  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  await fs.writeFile(path.join(dir, 'session.v4.jsonl.zstd'), Buffer.concat(framesOf(4)))
  await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 160,
  })
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  const complete = await inspectPartsSet(partsDir)
  assert.equal(complete.index.file, 'session.v4.jsonl.zstd')
  assert.equal(complete.complete, true)
  assert.deepEqual(complete.missing, [])
  assert.deepEqual(complete.extra, [])

  // 少一段 = 没落齐 (取回端记 deferred, 而不是拿它去拼)
  const missingName = partFileName(1)
  const missingBytes = await fs.readFile(path.join(partsDir, missingName))
  await fs.rm(path.join(partsDir, missingName))
  const missing = await inspectPartsSet(partsDir)
  assert.equal(missing.complete, false)
  assert.deepEqual(missing.missing, [missingName])

  // 多出来的一段不算"没落齐", 但要认出来 (下一趟镜像会把它收掉)
  await fs.writeFile(path.join(partsDir, missingName), missingBytes)
  await fs.writeFile(path.join(partsDir, '0099.bin'), Buffer.from('stray'))
  const extra = await inspectPartsSet(partsDir)
  assert.equal(extra.complete, true)
  assert.deepEqual(extra.extra, ['0099.bin'])
})

test('mirror stores parts, not the flat file; appends only touch the last part', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  const frames = framesOf(6)
  const file = path.join(dir, 'session.v4.jsonl.zstd')
  await fs.writeFile(file, Buffer.concat(frames))
  const opts = {
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 200,
  }

  const first = await mirrorSessionRoot(opts)
  assert.equal(first.chunked, 1)
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  const index = await readPartsIndex(partsDir)
  assert.ok(index !== null)
  assert.equal(index.file, 'session.v4.jsonl.zstd')
  assert.equal(index.bytes, Buffer.concat(frames).length)
  assert.equal(await fs.readFile(path.join(repo, 'sessions', 'w1', 'session-1', 'session.v4.jsonl.zstd')).then(() => 'there', () => 'gone'), 'gone')

  // 封好的段先记下来, 追加一帧之后再逐个对上
  const sealedBefore = new Map()
  for (let at = 0; at < index.parts; at += 1) {
    sealedBefore.set(partFileName(at), await fs.readFile(path.join(partsDir, partFileName(at))))
  }

  const extra = framesOf(1, 40)[0]
  await fs.appendFile(file, extra)
  const second = await mirrorSessionRoot(opts)
  assert.equal(second.chunked, 1)
  assert.equal(second.mirrored, 1)
  const after = await readPartsIndex(partsDir)
  assert.equal(after.bytes, index.bytes + extra.length)
  // 除最末一段之外, 前面那些段一个字节都没变
  for (let at = 0; at < index.parts - 1; at += 1) {
    const now = await fs.readFile(path.join(partsDir, partFileName(at)))
    assert.deepEqual(now, sealedBefore.get(partFileName(at)))
  }

  // 源删除 → 段与索引一起收掉 (删除阶段不留下孤儿段)
  await fs.rm(dir, { recursive: true, force: true })
  const third = await mirrorSessionRoot(opts)
  assert.ok(third.deleted.some(rel => rel.startsWith('w1/session-1/parts/')))
  assert.equal(await fs.stat(partsDir).then(() => 'there', () => 'gone'), 'gone')
})

test('mirror keeps a longer parts set whole when the local source is still short', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  const frames = framesOf(5)
  const file = path.join(dir, 'session.v4.jsonl.zstd')
  await fs.writeFile(file, Buffer.concat(frames))
  const opts = {
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 200,
  }
  await mirrorSessionRoot(opts)
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  const before = await readPartsIndex(partsDir)
  assert.ok(before.parts >= 2)
  const sealed = new Map()
  for (let at = 0; at < before.parts; at += 1) sealed.set(partFileName(at), await fs.readFile(path.join(partsDir, partFileName(at))))

  // 本机源比镜像里那一组短 (截断 / 取回还没写进会话库): 镜像一个字节都不覆盖,
  // 而且**多出来的那几段也不能被删除阶段收掉** —— 收掉就要重下
  await fs.writeFile(file, Buffer.concat(frames.slice(0, 2)))
  const second = await mirrorSessionRoot(opts)
  assert.equal(second.keptLonger, 1)
  assert.equal((await readPartsIndex(partsDir)).bytes, before.bytes)
  for (let at = 0; at < before.parts; at += 1) {
    assert.deepEqual(await fs.readFile(path.join(partsDir, partFileName(at))), sealed.get(partFileName(at)))
  }
})

test('adopt reassembles the parts back into a byte-identical session file', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  const joined = Buffer.concat(framesOf(5))
  await fs.writeFile(path.join(dir, 'session.v4.jsonl.zstd'), joined)
  await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 180,
  })

  // 另一半 (另一台设备) 的会话库是空的
  const local = path.join(root, 'local')
  await fs.mkdir(local, { recursive: true })
  const adopted = await adoptMergedSessions({ targetRoot: local, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(adopted.brought, 1)
  assert.deepEqual(adopted.files, ['w1/session-1/session.v4.jsonl.zstd'])
  const got = await fs.readFile(path.join(local, 'w1', 'session-1', 'session.v4.jsonl.zstd'))
  assert.deepEqual(got, joined)

  // 段目录本身不该作为文件落到本地会话库里
  assert.equal(await fs.stat(path.join(local, 'w1', 'session-1', 'parts')).then(() => 'there', () => 'gone'), 'gone')
})

test('adopt skips an incomplete parts set instead of writing half a session', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  await fs.writeFile(path.join(dir, 'session.v4.jsonl.zstd'), Buffer.concat(framesOf(4)))
  await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 160,
  })

  // 模拟"拉到一半": 拿走最后一段
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  const index = await readPartsIndex(partsDir)
  await fs.rm(path.join(partsDir, partFileName(index.parts - 1)))
  const assembled = await assembleParts(partsDir)
  assert.equal(assembled, null)

  const local = path.join(root, 'local')
  await fs.mkdir(local, { recursive: true })
  const adopted = await adoptMergedSessions({ targetRoot: local, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(adopted.brought, 0)
  // 半组是"还没落齐", 不是失败: 记 deferred, 下一趟 pull 接着搬 (2026-10-22 改口)
  assert.equal(adopted.deferred, 1)
  assert.deepEqual(adopted.deferredSets, ['w1/session-1/parts'])
  assert.deepEqual(adopted.failed, [])
  assert.equal(await fs.stat(path.join(local, 'w1')).then(() => 'there', () => 'gone'), 'gone')
})

test('adopt never brings a parts file in without its index', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const repo = path.join(root, 'repo')
  const local = path.join(root, 'local')
  // 只有段、没有索引 (拉取端半路停下 / 索引坏了): 段离开索引没有意义, 一个字节都不该落进会话库
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  await fs.mkdir(partsDir, { recursive: true })
  await fs.writeFile(path.join(partsDir, '0000.bin'), Buffer.from('half a session'))
  await fs.mkdir(local, { recursive: true })
  const adopted = await adoptMergedSessions({ targetRoot: local, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(adopted.brought, 0)
  assert.equal(await fs.stat(path.join(local, 'w1')).then(() => 'there', () => 'gone'), 'gone')
})

test('a tampered part is refused: the index sha256 catches two lineages mixed together', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  await fs.writeFile(path.join(dir, 'session.v4.jsonl.zstd'), Buffer.concat(framesOf(4)))
  await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 160,
  })
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  const index = await readPartsIndex(partsDir)
  assert.equal(typeof index.sha256, 'string')
  assert.equal(index.sha256.length, 64)

  // 拿同长度的别处字节换掉最后一段: 段数、总字节都对得上, 只有 sha256 能看出来
  const last = path.join(partsDir, partFileName(index.parts - 1))
  const bytes = await fs.readFile(last)
  const swapped = Buffer.from(bytes)
  swapped[swapped.length - 1] ^= 0xff
  await fs.writeFile(last, swapped)
  assert.equal(await assembleParts(partsDir), null)

  const local = path.join(root, 'local')
  await fs.mkdir(local, { recursive: true })
  const adopted = await adoptMergedSessions({ targetRoot: local, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(adopted.brought, 0)
  assert.equal(await fs.stat(path.join(local, 'w1')).then(() => 'there', () => 'gone'), 'gone')
})

test('an unchanged index is not reassembled on the next pull (no per-minute read amplification)', async (t) => {
  const { root, clean } = await makeTemp()
  t.after(clean)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const dir = path.join(src, 'w1', 'session-1')
  await fs.mkdir(dir, { recursive: true })
  await fs.writeFile(path.join(dir, 'session.v4.jsonl.zstd'), Buffer.concat(framesOf(4)))
  await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    chunkSessionLogs: true,
    chunkMinBytes: 0,
    chunkBytes: 160,
  })
  const local = path.join(root, 'local')
  await fs.mkdir(local, { recursive: true })

  const first = await adoptMergedSessions({ targetRoot: local, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(first.brought, 1)

  // 索引一个字没动: 第二趟不该再去读那几段 (把段换成等长的垃圾, 拼装会失败 —— 但根本不该拼)
  const partsDir = path.join(repo, 'sessions', 'w1', 'session-1', 'parts')
  const index = await readPartsIndex(partsDir)
  const last = path.join(partsDir, partFileName(index.parts - 1))
  const bytes = await fs.readFile(last)
  const swapped = Buffer.from(bytes)
  swapped[0] ^= 0xff
  await fs.writeFile(last, swapped)

  const second = await adoptMergedSessions({ targetRoot: local, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(second.brought, 0)
  assert.equal(second.updated, 0)
  assert.deepEqual(second.failed, [])
})
