// test/luwi-config-layer.test.mjs — 文件层 (三层合并里中间那层) 的读法
//
// 钉的是 2026-10-15 那个坑: 桌面宿主 (Electron main) 的进程 env 里**没有** DSH_HOME —— 它只按
// argv 传 profile 目录。只认 env 的话, PC 侧这份文件层永远是空的: 面板 (或手改文件) 写进去的
// remote 与三档自动值都读不回来, 现象是"明明写了却报 unset"
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

import { readLuwiSyncConfig } from '../lib/luwi-bridge-runner.mjs'

/** 造一个 home, 里面写一份给定内容的 session-sync.json。 */
function homeWith(content) {
  const home = mkdtempSync(join(tmpdir(), 'luwi-layer-'))
  mkdirSync(join(home, 'lw'), { recursive: true })
  if (content !== undefined) writeFileSync(join(home, 'lw', 'session-sync.json'), content)
  return home
}

test('env.DSH_HOME is still the default source (phone side has it)', () => {
  const home = homeWith(JSON.stringify({ remote: 'https://github.com/you/repo.git' }))
  try {
    const layer = readLuwiSyncConfig({ DSH_HOME: home })
    assert.equal(layer.remote, 'https://github.com/you/repo.git')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('an explicit home is read even when the host env has no DSH_HOME (desktop side)', () => {
  const home = homeWith(JSON.stringify({ remote: 'D:/apk/.lwtmp/g1/relay/bare-sessions.git', autoPullOnStart: true }))
  try {
    // 桌面宿主就是这个形状: env 里一个 DSH_HOME 都没有
    const layer = readLuwiSyncConfig({ PATH: 'C:/Windows' }, home)
    assert.equal(layer.remote, 'D:/apk/.lwtmp/g1/relay/bare-sessions.git')
    assert.equal(layer.autoPullOnStart, true)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('an explicit home wins over a stale env one', () => {
  const good = homeWith(JSON.stringify({ remote: 'right' }))
  const stale = homeWith(JSON.stringify({ remote: 'wrong' }))
  try {
    assert.equal(readLuwiSyncConfig({ DSH_HOME: stale }, good).remote, 'right')
  } finally {
    rmSync(good, { recursive: true, force: true })
    rmSync(stale, { recursive: true, force: true })
  }
})

test('no home at all, a missing file and a corrupt file all read as an empty layer', () => {
  assert.deepEqual(readLuwiSyncConfig({}), {})
  assert.deepEqual(readLuwiSyncConfig({}, '   '), {})
  const empty = homeWith(undefined)
  const corrupt = homeWith('{ not json')
  const array = homeWith('[1,2,3]')
  try {
    assert.deepEqual(readLuwiSyncConfig({}, empty), {})
    assert.deepEqual(readLuwiSyncConfig({}, corrupt), {})
    assert.deepEqual(readLuwiSyncConfig({}, array), {})
  } finally {
    for (const home of [empty, corrupt, array]) rmSync(home, { recursive: true, force: true })
  }
})

test('a file larger than the 64 KiB load-time ceiling is ignored', () => {
  const home = homeWith(JSON.stringify({ remote: 'x'.repeat(70000) }))
  try {
    assert.deepEqual(readLuwiSyncConfig({}, home), {})
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})
