// test/luwi-baseline.test.mjs — 会话基线（旧对话不同步 / 当前在聊的要同步）
//
// 判据只有一条: 基线里记着"配对那一刻的 mtime", 只有 mtime 一点没变才算旧; 变新就同步 ——
// 于是"当前正在进行的对话"（最后写入就在配对前几秒）与"配对后继续聊的老会话"都会自己走上来

import test from 'node:test'
import assert from 'node:assert/strict'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  MTIME_TOLERANCE_MS,
  captureSessionBaseline,
  isStillOld,
  localStillOld,
  oldMirrorPaths,
  oldRelPaths,
  relBelongsToSession,
  scanSessionFiles,
  sessionIdOfRelPath,
} from '../lib/luwi-baseline.mjs'
import { mirrorSessionRoot } from '../lib/mirror.mjs'
import { adoptMergedSessions } from '../lib/luwi-adopt.mjs'

async function makeTemp(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'lw-sync-baseline-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

/** 造一个会话文件并把 mtime 设成指定时刻 */
async function writeSession(dir, rel, content, mtimeMs) {
  const absolute = path.join(dir, ...rel.split('/'))
  await fs.mkdir(path.dirname(absolute), { recursive: true })
  await fs.writeFile(absolute, content)
  if (Number.isFinite(mtimeMs)) await fs.utimes(absolute, mtimeMs / 1000, mtimeMs / 1000)
  return absolute
}

const HOUR = 60 * 60 * 1000

test('capture: recent sessions are current, older ones become the baseline', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  await writeSession(root, '--D-apk--/session-old/log.jsonl', 'old\n', now - 3 * HOUR)
  await writeSession(root, '--D-apk--/session-old2/log.jsonl', 'older\n', now - 10 * HOUR)
  await writeSession(root, '--D-apk--/session-live/log.jsonl', 'live\n', now - 2 * 60 * 1000)
  const entries = await scanSessionFiles(root)
  const state = captureSessionBaseline(entries, { now, windowMinutes: 60 })
  assert.deepEqual(Object.keys(state.baseline).sort(), [
    '--D-apk--/session-old/log.jsonl',
    '--D-apk--/session-old2/log.jsonl',
  ])
  assert.equal(state.currentCount, 1)
  assert.equal(state.totalCount, 3)
  assert.equal(state.truncated, false)
  assert.equal(state.v, 1)
})

test('isStillOld: unchanged mtime stays old, a newer write makes it new', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  const rel = '--D-apk--/session-a/log.jsonl'
  const absolute = await writeSession(root, rel, 'v1\n', now - 5 * HOUR)
  const state = captureSessionBaseline(await scanSessionFiles(root), { now, windowMinutes: 60 })
  assert.equal(isStillOld(state.baseline, rel, now - 5 * HOUR), true)
  // 容差以内仍算"没动过"
  assert.equal(isStillOld(state.baseline, rel, now - 5 * HOUR + MTIME_TOLERANCE_MS), true)
  assert.equal(isStillOld(state.baseline, rel, now - 5 * HOUR + 5000), false)
  // 真的写一次之后就不再算旧
  await fs.appendFile(absolute, 'v2\n')
  const again = await scanSessionFiles(root)
  assert.equal(oldRelPaths(again, state.baseline).length, 0)
})

test('window 0 = only "still being written right now" counts as current', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  await writeSession(root, 'p/session-a/log.jsonl', 'a\n', now - 1000)
  const state = captureSessionBaseline(await scanSessionFiles(root), { now, windowMinutes: 0 })
  assert.equal(state.currentCount, 0)
  assert.equal(Object.keys(state.baseline).length, 1)
})

test('mirror: baseline files are not copied and their old copies are removed', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  await writeSession(src, 'p/session-old/log.jsonl', 'old\n', now - 5 * HOUR)
  // 窗口内（10 分钟前）还有写入 = “当前对话”，不进基线
  await writeSession(src, 'p/session-new/log.jsonl', 'new\n', now - 10 * 60 * 1000)
  const state = captureSessionBaseline(await scanSessionFiles(src), { now, windowMinutes: 60 })
  // 先假装仓库里已经有旧会话那一份（上一版插件传上去的）
  await fs.mkdir(path.join(repo, 'sessions', 'p', 'session-old'), { recursive: true })
  await fs.writeFile(path.join(repo, 'sessions', 'p', 'session-old', 'log.jsonl'), 'old\n')

  const result = await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    baseline: state.baseline,
  })
  assert.equal(result.mirrored, 1)
  assert.equal(result.beforeBaseline, 1)
  assert.equal(await fs.readFile(path.join(repo, 'sessions', 'p', 'session-new', 'log.jsonl'), 'utf8'), 'new\n')
  await assert.rejects(fs.access(path.join(repo, 'sessions', 'p', 'session-old', 'log.jsonl')))
})

test('mirror: a baseline session that was written again is uploaded', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const rel = 'p/session-old/log.jsonl'
  await writeSession(src, rel, 'old\n', now - 5 * HOUR)
  const state = captureSessionBaseline(await scanSessionFiles(src), { now, windowMinutes: 60 })
  // 配对之后又聊了一句
  await fs.appendFile(path.join(src, ...rel.split('/')), 'more\n')
  const result = await mirrorSessionRoot({ sessionRoot: src, repoDir: repo, mirrorDir: 'sessions', baseline: state.baseline })
  assert.equal(result.mirrored, 1)
  assert.equal(result.beforeBaseline, 0)
  assert.equal(await fs.readFile(path.join(repo, 'sessions', ...rel.split('/')), 'utf8'), 'old\nmore\n')
})

test('mirror: a shorter local session log never shrinks the mirror (a pull heals the local copy)', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  const rel = 'p/session-log/log.jsonl'
  // 镜像里是完整的那一份 (远端取回来的), 本机这一份被截断了
  await writeSession(repo, `sessions/${rel}`, 'full bytes\nand more\n', now - 10 * HOUR)
  await writeSession(src, rel, 'full\n', now - 5 * HOUR)
  const result = await mirrorSessionRoot({ sessionRoot: src, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(result.keptLonger, 1)
  assert.equal(result.mirrored, 0)
  assert.equal(
    await fs.readFile(path.join(repo, 'sessions', ...rel.split('/')), 'utf8'),
    'full bytes\nand more\n',
    '短的那一份不覆盖镜像里完整的那一份',
  )
  // 取回这一步于是能把本机补回完整的那一份 (这正是"手机自动到最新远端"那条路)
  const adopted = await adoptMergedSessions({ sessionRoot: src, repoDir: repo, mirrorDir: 'sessions' })
  assert.equal(adopted.updated, 1)
  assert.equal(await fs.readFile(path.join(src, ...rel.split('/')), 'utf8'), 'full bytes\nand more\n')
})

test('mirror: projection sessions are never uploaded', async (t) => {
  const root = await makeTemp(t)
  const src = path.join(root, 'sessions')
  const repo = path.join(root, 'repo')
  await writeSession(src, 'p/session-11111111-1111-1111-1111-111111111111/log.jsonl', 'projection\n', 1)
  await writeSession(src, 'p/session-22222222-2222-2222-2222-222222222222/log.jsonl', 'real\n', 1)
  // 仓库里以前误传过那一份
  await fs.mkdir(path.join(repo, 'sessions', 'p', 'session-11111111-1111-1111-1111-111111111111'), { recursive: true })
  await fs.writeFile(path.join(repo, 'sessions', 'p', 'session-11111111-1111-1111-1111-111111111111', 'log.jsonl'), 'projection\n')

  const result = await mirrorSessionRoot({
    sessionRoot: src,
    repoDir: repo,
    mirrorDir: 'sessions',
    neverSyncSessionIds: new Set(['11111111-1111-1111-1111-111111111111']),
  })
  assert.equal(result.neverSynced, 1)
  assert.equal(result.mirrored, 1)
  await assert.rejects(fs.access(path.join(repo, 'sessions', 'p', 'session-11111111-1111-1111-1111-111111111111', 'log.jsonl')))
  assert.equal(await fs.readFile(path.join(repo, 'sessions', 'p', 'session-22222222-2222-2222-2222-222222222222', 'log.jsonl'), 'utf8'), 'real\n')
})

test('session id helpers read the host layout and never guess', () => {
  assert.equal(sessionIdOfRelPath('p/session-abcd/log.jsonl'), 'abcd')
  assert.equal(sessionIdOfRelPath('p/log.jsonl'), undefined)
  assert.equal(relBelongsToSession('p/session-abcd/log.jsonl', new Set(['abcd'])), true)
  assert.equal(relBelongsToSession('p/session-abcd/log.jsonl', new Set(['other'])), false)
  assert.equal(relBelongsToSession('p/session-abcd/log.jsonl', new Set()), false)
  // 老扁平布局 <id>/<file>
  assert.equal(relBelongsToSession('abcd/log.jsonl', new Set(['abcd'])), true)
})

test('scan tolerates a missing session root', async () => {
  assert.deepEqual(await scanSessionFiles(path.join(os.tmpdir(), 'lw-sync-not-there-xyz')), [])
})

test('capture reports truncation instead of silently dropping entries', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  for (let index = 0; index < 5; index += 1) {
    await writeSession(root, `p/session-${index}/log.jsonl`, 'x\n', now - 5 * HOUR)
  }
  const state = captureSessionBaseline(await scanSessionFiles(root), { now, windowMinutes: 60, maxEntries: 2 })
  assert.equal(state.truncated, true)
  assert.equal(state.entries, 2)
})

test('localStillOld: missing source counts as old, a rewrite does not', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  const rel = 'p/session-a/log.jsonl'
  await writeSession(root, rel, 'a\n', now - 5 * HOUR)
  const state = captureSessionBaseline(await scanSessionFiles(root), { now, windowMinutes: 60 })
  assert.equal(await localStillOld(root, rel, state.baseline), true)
  await fs.appendFile(path.join(root, ...rel.split('/')), 'more\n')
  assert.equal(await localStillOld(root, rel, state.baseline), false)
  // 源文件不在了: 不可能是"当前在聊的那一场", 于是仍然算旧
  await fs.rm(path.join(root, ...rel.split('/')))
  assert.equal(await localStillOld(root, rel, state.baseline), true)
  // 基线里没有的一条一律不算旧 (新会话)
  assert.equal(await localStillOld(root, 'p/session-b/log.jsonl', state.baseline), false)
})

test('oldMirrorPaths walks the mirror only: current files and unknowns stay', async (t) => {
  const root = await makeTemp(t)
  const now = Date.now()
  const src = path.join(root, 'sessions')
  const mirror = path.join(root, 'repo', 'sessions')
  await writeSession(src, 'p/session-old/log.jsonl', 'old\n', now - 5 * HOUR)
  await writeSession(src, 'p/session-live/log.jsonl', 'live\n', now - 60 * 1000)
  const state = captureSessionBaseline(await scanSessionFiles(src), { now, windowMinutes: 60 })
  // 镜像里: 一条旧的 (基线里), 一条当前的 (基线里没有), 一条远端带来的新会话 (基线里也没有)
  for (const rel of ['p/session-old/log.jsonl', 'p/session-live/log.jsonl', 'p/session-remote/log.jsonl']) {
    await writeSession(mirror, rel, 'mirrored\n', now - 6 * HOUR)
  }
  const stale = await oldMirrorPaths(mirror, state.baseline, src)
  assert.deepEqual(stale, ['p/session-old/log.jsonl'])
  // 源不在本机 (别人会话的一种): 只要基线里记过也算旧
  await fs.rm(path.join(src, 'p', 'session-old'), { recursive: true })
  assert.deepEqual(await oldMirrorPaths(mirror, state.baseline, src), ['p/session-old/log.jsonl'])
  // 没有基线 = 什么都不清
  assert.deepEqual(await oldMirrorPaths(mirror, null, src), [])
  // 镜像根本不存在时安静地回空表 (手机首拉就是这一档)
  assert.deepEqual(await oldMirrorPaths(path.join(root, 'no-mirror'), state.baseline, src), [])
})
