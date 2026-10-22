// test/luwi-configure.test.mjs — 面板写配置那一层 (纯文件, 语义与 app 的 sync/SyncSetting.kt 对齐)
//
// 钉的是四件事: 保留未知键 / 「关」= 地址挪进 lw 段 + 三档归零 / 「开」= 填回来 / 写前校验
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

import { applyConfigure, branchProblem, KEYS, readSyncTree, remoteProblem, syncConfigPath } from '../lib/luwi-configure.mjs'

/** 一个临时 home, 里面放一份给定的配置 (不给就是空). */
function sandbox(initial) {
  const home = mkdtempSync(join(tmpdir(), 'luwi-configure-'))
  const file = syncConfigPath(home)
  mkdirSync(join(home, 'lw'), { recursive: true })
  if (initial !== undefined) {
    writeFileSync(file, JSON.stringify(initial, null, 2))
  }
  return {
    home,
    file,
    read: () => JSON.parse(readFileSync(file, 'utf8')),
    cleanup: () => rmSync(home, { recursive: true, force: true }),
  }
}

test('syncConfigPath points at <home>/lw/session-sync.json', () => {
  assert.equal(syncConfigPath('D:/home'), join('D:/home', 'lw', 'session-sync.json'))
})

test('remoteProblem accepts the shapes people actually paste, refuses the rest', () => {
  for (const good of [
    'https://github.com/you/repo.git',
    'http://127.0.0.1:3000/you/repo.git',
    'git://127.0.0.1:9418/bare-sessions.git',
    'ssh://git@host/you/repo.git',
    'git@github.com:you/repo.git',
    'D:/apk/.lwtmp/g1/relay/bare-sessions.git',
    '/srv/git/repo.git',
  ]) {
    assert.equal(remoteProblem(good), null, good)
  }
  assert.match(remoteProblem(''), /empty/u)
  assert.match(remoteProblem('   '), /empty/u)
  assert.match(remoteProblem('nonsense'), /must look like/u)
  assert.match(remoteProblem('https://host/a b.git'), /whitespace/u)
})

test('branchProblem allows empty (= main) and ordinary names', () => {
  assert.equal(branchProblem(''), null)
  assert.equal(branchProblem('main'), null)
  assert.equal(branchProblem('release/2.0.0'), null)
  assert.match(branchProblem('bad branch'), /whitespace/u)
  assert.match(branchProblem('bad~name'), /refuses/u)
})

test('writing the address keeps every unknown key byte for byte', () => {
  const box = sandbox({
    peer: { enabled: true, token: 'keep-me' },
    live: { maxEntries: 40 },
    unknownTop: { deep: [1, 2, 3] },
    branch: 'main',
  })
  try {
    return applyConfigure(box.file, { remote: 'https://github.com/you/repo.git' }).then((result) => {
      assert.equal(result.ok, true)
      const tree = box.read()
      assert.equal(tree[KEYS.REMOTE], 'https://github.com/you/repo.git')
      assert.deepEqual(tree.peer, { enabled: true, token: 'keep-me' })
      assert.deepEqual(tree.live, { maxEntries: 40 })
      assert.deepEqual(tree.unknownTop, { deep: [1, 2, 3] })
      assert.equal(tree.branch, 'main')
    })
  } finally {
    box.cleanup()
  }
})

test('turning sync off parks the address and zeroes all three auto values', async () => {
  const box = sandbox({
    remote: 'https://github.com/you/repo.git',
    autoPullOnStart: true,
    autoPushOnTurnEnd: true,
    pullIntervalMinutes: 5,
    peer: { enabled: true },
  })
  try {
    const result = await applyConfigure(box.file, { on: false })
    assert.equal(result.ok, true)
    const tree = box.read()
    assert.equal(tree[KEYS.REMOTE], undefined)
    assert.equal(tree[KEYS.AUTO_PULL], false)
    assert.equal(tree[KEYS.AUTO_PUSH], false)
    assert.equal(tree[KEYS.INTERVAL], 0)
    assert.equal(tree[KEYS.LW][KEYS.SAVED_REMOTE], 'https://github.com/you/repo.git')
    assert.equal(tree[KEYS.LW][KEYS.SAVED_AUTO_PULL], true)
    assert.equal(tree[KEYS.LW][KEYS.SAVED_AUTO_PUSH], true)
    assert.equal(tree[KEYS.LW][KEYS.SAVED_INTERVAL], 5)
    assert.deepEqual(tree.peer, { enabled: true })
  } finally {
    box.cleanup()
  }
})

test('turning sync back on puts the address and the three values back', async () => {
  const box = sandbox({
    autoPullOnStart: false,
    autoPushOnTurnEnd: false,
    pullIntervalMinutes: 0,
    lw: { savedRemote: 'git@github.com:you/repo.git', savedAutoPull: true, savedAutoPush: false, savedInterval: 10 },
    peer: { enabled: true },
  })
  try {
    const result = await applyConfigure(box.file, { on: true })
    assert.equal(result.ok, true)
    const tree = box.read()
    assert.equal(tree[KEYS.REMOTE], 'git@github.com:you/repo.git')
    assert.equal(tree[KEYS.AUTO_PULL], true)
    assert.equal(tree[KEYS.AUTO_PUSH], false)
    assert.equal(tree[KEYS.INTERVAL], 10)
    assert.equal(tree[KEYS.LW], undefined, 'lw 段里没有东西留下时整段删掉')
  } finally {
    box.cleanup()
  }
})

test('turning sync on without any address is refused, and nothing is written', async () => {
  const box = sandbox({ peer: { enabled: true } })
  try {
    const result = await applyConfigure(box.file, { on: true })
    assert.equal(result.ok, false)
    assert.match(result.error, /no repository address/u)
    assert.deepEqual(box.read(), { peer: { enabled: true } })
  } finally {
    box.cleanup()
  }
})

test('a bad address or a bad interval is refused before anything is written', async () => {
  const box = sandbox({ branch: 'main' })
  try {
    const bad = await applyConfigure(box.file, { remote: 'nonsense' })
    assert.equal(bad.ok, false)
    assert.match(bad.error, /must look like/u)
    assert.deepEqual(box.read(), { branch: 'main' })

    const far = await applyConfigure(box.file, { pullIntervalMinutes: 99999 })
    assert.equal(far.ok, false)
    assert.match(far.error, /\[0, 10080\]/u)
    assert.deepEqual(box.read(), { branch: 'main' })
  } finally {
    box.cleanup()
  }
})

test('a missing file and a corrupt file both count as an empty tree', async () => {
  const home = mkdtempSync(join(tmpdir(), 'luwi-configure-'))
  try {
    assert.deepEqual(await readSyncTree(join(home, 'lw', 'session-sync.json')), {})
    const file = syncConfigPath(home)
    mkdirSync(join(home, 'lw'), { recursive: true })
    writeFileSync(file, '{ this is not json')
    assert.deepEqual(await readSyncTree(file), {})
    // 坏文件也照写: 面板要能在一片废墟上把地址填进去
    const result = await applyConfigure(file, { remote: 'https://github.com/you/repo.git' })
    assert.equal(result.ok, true)
    assert.equal(JSON.parse(readFileSync(file, 'utf8'))[KEYS.REMOTE], 'https://github.com/you/repo.git')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('an empty patch is refused with a sentence that lists what can be passed', async () => {
  const box = sandbox({})
  try {
    const result = await applyConfigure(box.file, {})
    assert.equal(result.ok, false)
    assert.match(result.error, /nothing to change/u)
  } finally {
    box.cleanup()
  }
})

test('remoteToken is a key of its own: stored apart, cleared by "", never touches the address', async () => {
  const box = sandbox({
    remote: 'https://github.com/you/private.git',
    peer: { token: '0123456789abcdef0123456789abcdef' },
  })
  try {
    const before = box.read()
    const result = await applyConfigure(box.file, { remoteToken: 'ghp_example_token' })
    assert.equal(result.ok, true)
    const after = box.read()
    assert.equal(after[KEYS.REMOTE], 'https://github.com/you/private.git')
    assert.equal(after[KEYS.REMOTE_TOKEN], 'ghp_example_token')
    assert.deepEqual(after.peer, before.peer, 'the peer section is untouched')

    // 空串 = 清掉令牌, 地址照旧
    assert.equal((await applyConfigure(box.file, { remoteToken: '' })).ok, true)
    assert.equal(KEYS.REMOTE_TOKEN in box.read(), false)
    assert.equal(box.read()[KEYS.REMOTE], 'https://github.com/you/private.git')

    // 空白 / 太长一律拒
    assert.equal((await applyConfigure(box.file, { remoteToken: 'has space' })).ok, false)
    assert.equal((await applyConfigure(box.file, { remoteToken: 'x'.repeat(4097) })).ok, false)
  } finally {
    box.cleanup()
  }
})
