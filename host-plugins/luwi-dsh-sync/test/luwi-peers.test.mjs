// test/luwi-peers.test.mjs — Luwi 加 (G1-D 互为远端的对端名片) 的回归套件。
//
// 上游测试里没有这些: 上游没有 peers/ 这回事。这里钉住五件事:
//   1. 名片落盘: peers/<设备 id>.json 的形状, 以及"身份没变就一个字节不动" (判据 4 的一条命 ——
//      updatedAt 每轮都刷新的话, 自动推送永远 dirty, "重复推不产生第二份提交"就再也答不上)
//   2. 读名片: 工作树里的 peers/ 按文件名列出, 坏文件跳过并计数, 目录不在就是空
//   3. 判据 7 的引擎那一半: A 推 B 拉、B 推 A 拉, 两边的镜像里都出现对方的名片, 名片坐的是
//      正经提交 (裸仓库的树里看得见), 改名字之后再推也只多一个提交
//   4. renderPeers: 自己排最前, 无名/无 URL 的照列, 坏文件计数进回执
//   5. 插件装配: deviceName/publicUrl 进配置, sync_status 带 peerCount, /sync peers 真的能跑
// @module dsh-session-sync/test/luwi-peers.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { readPeerCards, writePeerCard } from '../lib/luwi-peers.mjs'
import { GitBackend } from '../lib/git.mjs'
import { SyncEngine } from '../lib/engine.mjs'
import { renderPeers } from '../lib/render.mjs'
import * as plugin from '../index.mjs'

/** git runner (与上游 makeRunGit 同形的契约, 这里直接问 PATH 上的 git)。 */
function runGit(args, opts = {}) {
  const result = spawnSync('git', args, {
    cwd: opts.cwd,
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_OPTIONAL_LOCKS: '0' },
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  })
  return { code: result.status ?? 1, stdout: result.stdout ?? '', stderr: result.stderr ?? '' }
}

/** 一棵临时树; 结束时 caller 自己 rmSync。 */
function makeRoot(prefix) {
  return mkdtempSync(join(tmpdir(), `${prefix}-`))
}

/** 相对路径写文件 (自动建父目录)。 */
function put(root, rel, content) {
  const target = join(root, ...rel.split('/'))
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, content)
  return target
}

/** 相对路径读文件 (不存在给 undefined)。 */
function take(root, rel) {
  const target = join(root, ...rel.split('/'))
  return existsSync(target) ? readFileSync(target, 'utf8') : undefined
}

// ---------------------------------------------------------------------------
// 1 · 名片落盘
// ---------------------------------------------------------------------------

test('a peer card lands as peers/<id>.json and only rewrites when the identity changes', async () => {
  const root = makeRoot('lw-peers-write')
  try {
    const repoDir = join(root, 'repo')
    const now = new Date(Date.UTC(2026, 9, 13, 5, 4, 3))
    const wrote = await writePeerCard({
      repoDir,
      deviceId: '11111111-2222-3333-4444-555555555555',
      name: 'Luwi 手机',
      url: 'http://192.168.1.103:3210',
      now,
    })
    assert.equal(wrote, true)
    const card = JSON.parse(take(repoDir, 'peers/11111111-2222-3333-4444-555555555555.json'))
    assert.equal(card.deviceId, '11111111-2222-3333-4444-555555555555')
    assert.equal(card.name, 'Luwi 手机')
    assert.equal(card.url, 'http://192.168.1.103:3210')
    assert.equal(card.updatedAt, '2026-10-13T05:04:03.000Z')

    // 同一个身份再写: 不落盘, updatedAt 不刷新 (判据 4 的那条命)
    const again = await writePeerCard({
      repoDir,
      deviceId: '11111111-2222-3333-4444-555555555555',
      name: 'Luwi 手机',
      url: 'http://192.168.1.103:3210',
      now: new Date(Date.UTC(2026, 9, 14, 5, 4, 3)),
    })
    assert.equal(again, false, JSON.stringify(again))
    assert.equal(JSON.parse(take(repoDir, 'peers/11111111-2222-3333-4444-555555555555.json')).updatedAt, '2026-10-13T05:04:03.000Z')

    // 身份变了 (换了 URL): 落盘, updatedAt 跟着盖新章
    const renamed = await writePeerCard({
      repoDir,
      deviceId: '11111111-2222-3333-4444-555555555555',
      name: 'Luwi 手机',
      url: 'http://192.168.1.103:4321',
      now: new Date(Date.UTC(2026, 9, 14, 5, 4, 3)),
    })
    assert.equal(renamed, true)
    assert.equal(JSON.parse(take(repoDir, 'peers/11111111-2222-3333-4444-555555555555.json')).url, 'http://192.168.1.103:4321')
    assert.equal(JSON.parse(take(repoDir, 'peers/11111111-2222-3333-4444-555555555555.json')).updatedAt, '2026-10-14T05:04:03.000Z')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('an unsafe or missing device id writes nothing', async () => {
  const root = makeRoot('lw-peers-unsafe')
  try {
    const repoDir = join(root, 'repo')
    assert.equal(await writePeerCard({ repoDir, deviceId: '' }), false)
    assert.equal(await writePeerCard({ repoDir, deviceId: '   ' }), false)
    assert.equal(await writePeerCard({ repoDir, deviceId: '../evil' }), false, 'path traversal must not become a file name')
    assert.equal(await writePeerCard({ repoDir, deviceId: 'a/b' }), false)
    assert.equal(await writePeerCard({ repoDir, deviceId: 42 }), false)
    // name 与 url 都空: 名片没有存在的意义, 连 peers/ 目录都不该出现
    assert.equal(await writePeerCard({ repoDir, deviceId: '11111111-2222-3333-4444-555555555555' }), false)
    assert.equal(existsSync(join(repoDir, 'peers')), false, 'no directory should even exist')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 2 · 读名片
// ---------------------------------------------------------------------------

test('readPeerCards lists the mirror, skips broken files, and answers empty without a directory', async () => {
  const root = makeRoot('lw-peers-read')
  try {
    const repoDir = join(root, 'repo')
    // 目录不在: 空, 不抛
    assert.deepEqual(await readPeerCards(repoDir), { cards: [], broken: 0 })

    put(repoDir, 'peers/b-second.json', JSON.stringify({ deviceId: 'b', name: '电脑', url: 'http://192.168.1.50:19387', updatedAt: '2026-10-13T02:00:00.000Z' }))
    put(repoDir, 'peers/a-first.json', JSON.stringify({ deviceId: 'a', name: '', url: '', updatedAt: '2026-10-13T01:00:00.000Z' }))
    put(repoDir, 'peers/broken.json', '{not json')
    put(repoDir, 'peers/not-object.json', '[1,2,3]')
    put(repoDir, 'notes.txt', 'not a card at all')

    const { cards, broken } = await readPeerCards(repoDir)
    assert.deepEqual(cards.map((card) => card.deviceId), ['a', 'b'], 'cards come back sorted by file name')
    assert.equal(cards[1].name, '电脑')
    assert.equal(broken, 2, `broken files are counted, not thrown: ${JSON.stringify({ cards, broken })}`)

    // 字段缺失一律落成空串, 不抛
    put(repoDir, 'peers/partial.json', '{"deviceId":"c"}')
    const partial = await readPeerCards(repoDir)
    assert.deepEqual(partial.cards.find((card) => card.deviceId === 'c'), { deviceId: 'c', name: '', url: '', updatedAt: '' })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 3 · 判据 7 (引擎那一半): 名片跟会话一起坐 git
// ---------------------------------------------------------------------------

/** 一台"设备": 会话根 + 自己的工作树, 带名片写入 (会话那根的形状)。 */
function makeDevice(root, name, deviceId, remote, card) {
  const sessionRoot = join(root, name, 'sessions')
  const repoDir = join(root, name, 'repo')
  mkdirSync(sessionRoot, { recursive: true })
  const git = new GitBackend({
    repoDir,
    remote,
    branch: 'main',
    commitName: 'luwi-test',
    commitEmail: 'luwi-test@local',
    run: runGit,
    timeoutMs: 60000,
  })
  const engine = new SyncEngine({
    git,
    sessionRoot,
    repoDir,
    remote,
    branch: 'main',
    deviceId,
    reportMeta: () => {},
    reportError: () => {},
    onForks: () => {},
    // 与 index.mjs 里会话那根同一个挂法: 镜像前写自己的名片
    writePeerCard: () => writePeerCard({ repoDir, deviceId, name: card.deviceName, url: card.publicUrl }),
    logger: { debug() {}, info() {}, warn() {}, error() {} },
  })
  return { sessionRoot, repoDir, engine, deviceId }
}

test('criterion 7: cards ride push/pull, both mirrors end up with the other side, re-push stays clean', async () => {
  const root = makeRoot('lw-peers-e2e')
  try {
    const bare = join(root, 'remote.git')
    assert.equal(runGit(['init', '--bare', '--initial-branch=main', bare]).code, 0)
    const phone = makeDevice(root, 'phone', '11111111-2222-3333-4444-555555555555', bare, {
      deviceName: 'Luwi 手机', publicUrl: 'http://192.168.1.103:3210',
    })
    const desk = makeDevice(root, 'desk', 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', bare, {
      deviceName: '电脑', publicUrl: 'http://192.168.1.50:19387',
    })

    // 手机推: 名片跟着第一次提交进裸仓库 (判据 2 那条路的顺带读数)
    put(phone.sessionRoot, '--phone--/session-1/session.v4.jsonl.zstd', 'phone bytes')
    const pushPhone = await phone.engine.push()
    assert.equal(pushPhone.ok, true, pushPhone.error)
    const bareTree = runGit(['--git-dir', bare, 'ls-tree', '-r', '--name-only', 'main']).stdout.trim().split('\n')
    assert.equal(bareTree.includes('peers/11111111-2222-3333-4444-555555555555.json'), true, JSON.stringify(bareTree))

    // 电脑拉: 镜像里出现手机的名片, 名字与 URL 都在 (/sync peers 与 ⋮ 那几行读的就是它)
    const pullDesk = await desk.engine.pull()
    assert.equal(pullDesk.ok, true, pullDesk.error)
    const seenByDesk = await readPeerCards(desk.repoDir)
    const phoneCard = seenByDesk.cards.find((card) => card.deviceId === '11111111-2222-3333-4444-555555555555')
    assert.ok(phoneCard !== undefined, JSON.stringify(seenByDesk))
    assert.equal(phoneCard.name, 'Luwi 手机')
    assert.equal(phoneCard.url, 'http://192.168.1.103:3210')

    // 电脑推, 手机拉: 反方向同样成立
    put(desk.sessionRoot, '--desk--/session-2/session.v4.jsonl.zstd', 'desk bytes')
    assert.equal((await desk.engine.push()).ok, true)
    assert.equal((await phone.engine.pull()).ok, true)
    const seenByPhone = await readPeerCards(phone.repoDir)
    const deskCard = seenByPhone.cards.find((card) => card.deviceId === 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    assert.ok(deskCard !== undefined, JSON.stringify(seenByPhone))
    assert.equal(deskCard.name, '电脑')

    // 判据 4 的顺带读数: 名片身份没变时, 重复推不产生第二份提交 (第一次重推会把 pull
    // 产生的那个合并提交发出去, 所以连推两次, 第三次的 head 必须一动不动)
    assert.equal((await phone.engine.push()).ok, true)
    const headSteady = runGit(['--git-dir', bare, 'rev-parse', 'main']).stdout.trim()
    assert.equal((await phone.engine.push()).ok, true)
    assert.equal(runGit(['--git-dir', bare, 'rev-parse', 'main']).stdout.trim(), headSteady)

    // 改了名字: 下一次推恰好把新名字坐过去
    const renamed = makeDevice(root, 'phone', '11111111-2222-3333-4444-555555555555', bare, {
      deviceName: 'Luwi 手机 (改)', publicUrl: 'http://192.168.1.103:3210',
    })
    assert.equal((await renamed.engine.push()).ok, true)
    put(desk.sessionRoot, '.keep', 'poke the mirror so pull has a checkpoint to commit')
    assert.equal((await desk.engine.pull()).ok, true)
    const renamedCard = (await readPeerCards(desk.repoDir)).cards.find((card) => card.deviceId === '11111111-2222-3333-4444-555555555555')
    assert.equal(renamedCard.name, 'Luwi 手机 (改)')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 4 · renderPeers
// ---------------------------------------------------------------------------

test('renderPeers puts this device first, names the unnamed, and counts broken files', () => {
  const text = renderPeers([
    { deviceId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', name: '电脑', url: 'http://192.168.1.50:19387', updatedAt: '2026-10-13T02:00:00.000Z' },
    { deviceId: '11111111-2222-3333-4444-555555555555', name: '', url: '', updatedAt: '2026-10-13T03:00:00.000Z', self: true },
  ], 1)
  const lines = text.split('\n')
  assert.match(lines[0], /peers/u)
  assert.match(lines[1], /this device/, 'the self card comes first')
  assert.match(lines[1], /11111111/, 'the device id is shown short')
  assert.match(lines[2], /电脑/)
  assert.match(lines[2], /http:\/\/192\.168\.1\.50:19387/u)
  assert.match(text, /\(no URL configured\)/, 'a card without a URL says so instead of rendering an empty row')
  assert.match(text, /1 unreadable card file/u, 'broken files are reported, not swallowed')

  const empty = renderPeers([])
  assert.match(empty, /none yet/u, 'an empty mirror gets a sentence, not a bare header')
})

// ---------------------------------------------------------------------------
// 5 · 配置校验 (deviceName / publicUrl)
// ---------------------------------------------------------------------------

test('deviceName and publicUrl validate loudly at load time', () => {
  const base = { sessionRoot: 's', repoDir: 'r' }
  assert.throws(() => plugin.resolveConfig({ ...base, publicUrl: 'ftp://example.com' }), /http\(s\)/u)
  assert.throws(() => plugin.resolveConfig({ ...base, publicUrl: '192.168.1.50:19387' }), /http\(s\)/u)
  assert.throws(() => plugin.resolveConfig({ ...base, deviceName: 'x'.repeat(201) }), /200/u)
  assert.equal(plugin.resolveConfig({ ...base, deviceName: 'Luwi 手机', publicUrl: 'http://192.168.1.103:3210' }).deviceName, 'Luwi 手机')
  // 都空 (缺省) 也是合法的: 名片干脆不写, 上游部署的镜像输出一个字节不变
  const quiet = plugin.resolveConfig(base)
  assert.equal(quiet.deviceName, '')
  assert.equal(quiet.publicUrl, '')
})

// ---------------------------------------------------------------------------
// 6 · 插件装配: 名片进启动面 (status 的 peerCount 与 /sync peers)
// ---------------------------------------------------------------------------

/** 一台最小宿主 (与 luwi-workspace 套件同一套路: 真注册表 + mock 存储/子进程)。 */
async function mountPlugin(extraConfig = {}) {
  const { Context } = await import('@deepseek-ai/cordis')
  const { default: SessionStore } = await import('@deepseek-ai/dsh-session')
  const { default: ToolRuntime } = await import('@deepseek-ai/dsh-tools')
  const { default: CommandRuntime } = await import('@deepseek-ai/dsh-commands')
  const root = makeRoot('lw-peers-mount')
  const sessionRoot = join(root, 'sessions')
  mkdirSync(sessionRoot, { recursive: true })
  const ctx = new Context()
  await ctx.plugin(SessionStore)
  const session = ctx.sessions.create()
  ctx.provide('systemPrompt', { tools: () => () => undefined, section: () => () => undefined })
  ctx.provide('storageDomain', {
    open(spec) {
      const store = new Map()
      return Promise.resolve({
        name: spec.name,
        table: () => ({
          get: (key) => store.get(key),
          put: async (key, value) => { store.set(key, value) },
          delete: async (key) => { store.delete(key) },
        }),
        close: async () => {},
      })
    },
  })
  ctx.provide('subprocess', {
    resolveExecutable: async () => 'git',
    spawn: () => ({
      stdout: (async function* () {})(),
      done: Promise.resolve({ exitCode: 0, signal: null }),
      collected: { stdout: { readFrom: () => ({ text: '' }) }, stderr: { readFrom: () => ({ text: '' }) } },
    }),
  })
  await ctx.plugin(ToolRuntime)
  await ctx.plugin(CommandRuntime)
  const agent = {
    id: session.id,
    options: { provider: 'deepseek', model: 'demo-model' },
    session,
    inbox: {},
    status: 'idle',
    ctx: new Context(),
    cancel: () => undefined,
    whenIdle: async () => undefined,
    runMaintenance: async (task) => task(new AbortController().signal),
    send: () => undefined,
    followup: () => undefined,
    steer: () => undefined,
    inject: () => undefined,
  }
  const fiber = await ctx.plugin(plugin, {
    sessionRoot,
    repoDir: join(root, 'repo'),
    deviceName: 'Luwi 手机',
    publicUrl: 'http://192.168.1.103:3210',
    ...extraConfig,
  })
  return { ctx, fiber, root, sessionRoot, agent }
}

test('mounting with a card config writes the card and answers status and /sync peers', async () => {
  const harness = await mountPlugin()
  try {
    // status 工具带 peerCount (自己的名片在镜像之后就该在)
    const status = harness.ctx.tools.get('sync_status')
    assert.ok(status !== undefined)
    const value = await status.execute({}, {})
    assert.equal(value.ok, true, value.error)
    assert.equal(value.peerCount, 1, JSON.stringify({ peerCount: value.peerCount }))

    // /sync peers: 自己的名字与 URL 都在, 且标着 this device
    const run = await harness.ctx.commands.execute(harness.agent, '/sync peers', [], new AbortController().signal)
    assert.ok(run !== undefined, '/sync must accept the peers action')
    assert.equal(run.result.kind, 'success', JSON.stringify(run.result))
    assert.match(run.result.text, /this device/u)
    assert.match(run.result.text, /Luwi 手机/u)
    assert.match(run.result.text, /http:\/\/192\.168\.1\.103:3210/u)

    // /sync status 的文本里也有对端计数那一行
    const ran = await harness.ctx.commands.execute(harness.agent, '/sync status', [], new AbortController().signal)
    assert.ok(ran !== undefined)
    assert.match(ran.result.text, /peers seen in the mirror: 1/u)
  } finally {
    await harness.ctx.fiber.dispose()
    rmSync(harness.root, { recursive: true, force: true })
  }
})

test('without a card config /sync peers still answers, with a nameless self card', async () => {
  const harness = await mountPlugin({ deviceName: '', publicUrl: '' })
  try {
    const run = await harness.ctx.commands.execute(harness.agent, '/sync peers', [], new AbortController().signal)
    assert.ok(run !== undefined)
    assert.equal(run.result.kind, 'success', JSON.stringify(run.result))
    assert.match(run.result.text, /this device/u)
    assert.match(run.result.text, /\(no URL configured\)/u)
    // status 不带 peers 那一行 (peerCount 0 → 不加行, 与上游逐字一致)
    const ran = await harness.ctx.commands.execute(harness.agent, '/sync status', [], new AbortController().signal)
    assert.ok(ran !== undefined)
    assert.doesNotMatch(ran.result.text, /peers seen in the mirror/u)
  } finally {
    await harness.ctx.fiber.dispose()
    rmSync(harness.root, { recursive: true, force: true })
  }
})
