// test/luwi-sync-sources.test.mjs — 一个仓库多根源 / 按设备分命名空间 / 会话基线清理
//
// 2026-10-20 同步改造的三件事在这里钉住:
//   · 会话与工作区同一次 commit / 同一次 push (workspaces/<设备>/<工作区>/ 不合并)
//   · 配对之后第一次拉取把"仍然算旧"的会话从工作树里删掉 (并进合并提交, push 之后远端也没有)
//   · 拉取之后别的工作区物化到影子目录 (不写本机工作区)

import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { promises as fs } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { GitBackend } from '../lib/git.mjs'
import { SyncEngine } from '../lib/engine.mjs'
import { adoptMergedSessions } from '../lib/luwi-adopt.mjs'
import { localStillOld } from '../lib/luwi-baseline.mjs'
import { resolveWorkspaceSources, measureWorkspaceBudget } from '../lib/luwi-workspace.mjs'
import { materializeSyncShadows, writeWorkspaceCard, readWorkspaceCards } from '../lib/luwi-sync-shadow.mjs'

const GIT_OK = (() => {
  try {
    return spawnSync('git', ['--version'], { stdio: 'ignore' }).status === 0
  } catch {
    return false
  }
})()

/** 测试专用真实 git runner */
function realRunner(args, opts = {}) {
  const res = spawnSync('git', args, {
    cwd: opts.cwd,
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_OPTIONAL_LOCKS: '0' },
    encoding: opts.binary === true ? null : 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    windowsHide: true,
  })
  return Promise.resolve({
    code: res.status ?? 0,
    stdout: opts.binary === true ? (res.stdout ?? Buffer.alloc(0)) : String(res.stdout ?? ''),
    stderr: String(res.stderr ?? ''),
  })
}

async function makeTemp(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'lw-sync-sources-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}

async function writeFileUnder(root, rel, content) {
  const target = path.join(root, ...rel.split('/'))
  await fs.mkdir(path.dirname(target), { recursive: true })
  await fs.writeFile(target, content)
}

function makeEngine({ root, name, remote, sources, baseline, deviceId }) {
  const sessionRoot = path.join(root, name, 'sessions')
  const repoDir = path.join(root, name, 'repo')
  const git = new GitBackend({
    repoDir,
    remote,
    branch: 'main',
    commitName: 'test-sync',
    commitEmail: 'test@local',
    run: realRunner,
    timeoutMs: 60000,
  })
  const engine = new SyncEngine({
    git,
    sessionRoot,
    repoDir,
    remote,
    branch: 'main',
    deviceId: deviceId ?? name,
    sources,
    baseline,
    reportMeta() {},
    reportError() {},
    onForks() {},
    logger: { debug() {}, info() {}, warn() {}, error() {} },
  })
  return { engine, git, sessionRoot, repoDir }
}

test('resolveWorkspaceSources: one namespace per device, repo/shadow excluded, budget defaults', async (t) => {
  const root = await makeTemp(t)
  const workspace = path.join(root, 'dsh')
  await fs.mkdir(workspace, { recursive: true })
  const repoDir = path.join(workspace, '.lw-sync', 'session-sync', 'repo')
  const shadowRoot = path.join(root, 'lw', 'peer-workspaces')
  const resolved = resolveWorkspaceSources({
    workspace,
    config: {},
    deviceName: 'PC',
    repoDir,
    shadowRoot,
    titleFor: () => 'dsh',
  })
  assert.equal(resolved.sources.length, 1)
  const source = resolved.sources[0]
  assert.equal(source.mirrorDir, 'workspaces/PC/dsh')
  assert.equal(source.title, 'dsh')
  assert.ok(source.exclude.includes('.lw-sync'))
  assert.ok(source.exclude.includes('node_modules'))
  assert.ok(source.exclude.includes('.lw-sync/session-sync/repo'))
  assert.deepEqual(source.budget, { maxFiles: 20000, maxBytes: 1024 * 1024 * 1024 })
})

test('resolveWorkspaceSources: disabled / legacy keys / bad root', async (t) => {
  const root = await makeTemp(t)
  const workspace = path.join(root, 'dsh')
  await fs.mkdir(workspace, { recursive: true })
  assert.deepEqual(resolveWorkspaceSources({ workspace, config: { enabled: false } }).sources, [])
  const legacy = resolveWorkspaceSources({ workspace, config: { remote: 'D:/x.git', autoPullOnStart: true } })
  assert.equal(legacy.sources.length, 1)
  assert.match(legacy.problems.join(' '), /no longer applies/u)
  const missing = resolveWorkspaceSources({ workspace, config: { root: 'not-there' } })
  assert.equal(missing.sources.length, 0)
  assert.match(missing.problems.join(' '), /not a directory/u)
  assert.throws(() => resolveWorkspaceSources({ workspace, config: { maxFiles: 0 } }), /maxFiles/u)
})

test('measureWorkspaceBudget stops over budget', async (t) => {
  const root = await makeTemp(t)
  await writeFileUnder(root, 'a.txt', '1')
  await writeFileUnder(root, 'b.txt', '2')
  const small = await measureWorkspaceBudget(root, [], { maxFiles: 1, maxBytes: 1024 })
  assert.equal(small.over, true)
  const ok = await measureWorkspaceBudget(root, [], { maxFiles: 10, maxBytes: 1024 })
  assert.equal(ok.over, false)
  assert.equal(ok.files, 2)
})

test('one commit carries sessions and the device workspace', { skip: !GIT_OK && 'git binary not available' }, async (t) => {
  const root = await makeTemp(t)
  const remote = path.join(root, 'remote.git')
  spawnSync('git', ['init', '--bare', '-b', 'main', remote], { stdio: 'ignore' })
  const workspace = path.join(root, 'dsh')
  await fs.mkdir(workspace, { recursive: true })
  await writeFileUnder(workspace, 'src/app.ts', 'export const a = 1\n')
  const sessionRoot = path.join(root, 'sessions')
  await writeFileUnder(sessionRoot, 'p/session-a/log.jsonl', 'hello\n')

  const { engine, repoDir } = makeEngine({
    root,
    name: 'pc',
    remote,
    sources: [
      { label: 'sessions', root: sessionRoot, mirrorDir: 'sessions', useBaseline: true },
      {
        label: 'workspace: dsh',
        root: workspace,
        mirrorDir: 'workspaces/PC/dsh',
        exclude: ['.lw-sync'],
        maxFileBytes: 0,
        budget: { maxFiles: 100, maxBytes: 10 * 1024 * 1024 },
      },
    ],
  })
  const pushed = await engine.push()
  assert.equal(pushed.ok, true, pushed.error)
  assert.equal(await fs.readFile(path.join(repoDir, 'sessions', 'p', 'session-a', 'log.jsonl'), 'utf8'), 'hello\n')
  assert.equal(await fs.readFile(path.join(repoDir, 'workspaces', 'PC', 'dsh', 'src', 'app.ts'), 'utf8'), 'export const a = 1\n')
  const tree = spawnSync('git', ['ls-tree', '-r', '--name-only', 'HEAD'], { cwd: repoDir, encoding: 'utf8' })
  const names = tree.stdout.trim().split('\n')
  assert.ok(names.includes('sessions/p/session-a/log.jsonl'))
  assert.ok(names.includes('workspaces/PC/dsh/src/app.ts'))
})

test('first pull purges sessions that are still old, keeps the new ones', { skip: !GIT_OK && 'git binary not available' }, async (t) => {
  const root = await makeTemp(t)
  const remote = path.join(root, 'remote.git')
  spawnSync('git', ['init', '--bare', '-b', 'main', remote], { stdio: 'ignore' })

  // 设备 A: 先把一场"旧会话"推上去 (模拟配对之前仓库里已有的东西)
  const aRoot = path.join(root, 'a')
  const aSessions = path.join(aRoot, 'sessions')
  await writeFileUnder(aSessions, 'p/session-old/log.jsonl', 'old bytes\n')
  const a = makeEngine({
    root,
    name: 'a',
    remote,
    sources: [{ label: 'sessions', root: aSessions, mirrorDir: 'sessions', useBaseline: true }],
  })
  const aPush = await a.engine.push()
  assert.equal(aPush.ok, true, aPush.error)

  // 设备 B: 同一场还在本机 (基线里), 另有一场当前会话
  const bRoot = path.join(root, 'b')
  const bSessions = path.join(bRoot, 'sessions')
  await writeFileUnder(bSessions, 'p/session-old/log.jsonl', 'old bytes\n')
  await writeFileUnder(bSessions, 'p/session-new/log.jsonl', 'new bytes\n')
  const oldStat = await fs.stat(path.join(bSessions, 'p', 'session-old', 'log.jsonl'))
  let pending = true
  let doneCalls = 0
  const b = makeEngine({
    root,
    name: 'b',
    remote,
    baseline: {
      load: async () => ({ paths: { 'p/session-old/log.jsonl': oldStat.mtimeMs } }),
      pending: async () => pending,
      purgePaths: async () => (pending ? ['p/session-old/log.jsonl'] : []),
      done: async () => { pending = false; doneCalls += 1 },
    },
    sources: [{ label: 'sessions', root: bSessions, mirrorDir: 'sessions', useBaseline: true }],
  })
  const pulled = await b.engine.pull()
  assert.equal(pulled.ok, true, pulled.error)
  assert.equal(pulled.purged, 1)
  assert.equal(doneCalls, 1)
  assert.equal(pending, false)
  // 旧的那一份从工作树里没了, 新的那一份在
  const tree = spawnSync('git', ['ls-tree', '-r', '--name-only', 'HEAD'], { cwd: b.repoDir, encoding: 'utf8' })
  const names = tree.stdout.trim().split('\n')
  assert.equal(names.includes('sessions/p/session-old/log.jsonl'), false)
  assert.ok(names.includes('sessions/p/session-new/log.jsonl'))

  const pushed = await b.engine.push()
  assert.equal(pushed.ok, true, pushed.error)
  const remoteTree = spawnSync('git', ['--git-dir', remote, 'ls-tree', '-r', '--name-only', 'main'], { encoding: 'utf8' })
  assert.equal(remoteTree.stdout.includes('session-old'), false)
  assert.ok(remoteTree.stdout.includes('session-new'))
})

test('shadow materialisation writes other devices only and keeps deleted bytes in the trash', async (t) => {
  const root = await makeTemp(t)
  const repoDir = path.join(root, 'repo')
  const shadowRoot = path.join(root, 'peer-workspaces')
  await writeFileUnder(repoDir, 'workspaces/PC/dsh/src/app.ts', 'pc version\n')
  await writeFileUnder(repoDir, 'workspaces/Luwi 手机/dsh/mine.ts', 'phone version\n')
  await writeWorkspaceCard({ repoDir, deviceId: 'pc-id-0001', name: 'PC', workspaces: [{ slug: 'dsh', title: 'dsh' }] })
  await writeWorkspaceCard({ repoDir, deviceId: 'phone-id-01', name: 'Luwi 手机', workspaces: [{ slug: 'dsh', title: 'dsh' }] })
  const cards = (await readWorkspaceCards(repoDir)).cards
  assert.equal(cards.length, 2)

  const result = await materializeSyncShadows({
    repoDir,
    shadowRoot,
    cards,
    ownDeviceId: 'phone-id-01',
    wasMirroredFor: () => async () => true,
  })
  assert.equal(result.workspaces.length, 1, 'only the peer device is materialised')
  // 影子目录与对端影子同一算式: <shadowRoot>/<id>/<shadowDirName(name,id)>
  const target = path.join(shadowRoot, 'pc-id-0001', 'dsh', 'src', 'app.ts')
  assert.equal(await fs.readFile(target, 'utf8'), 'pc version\n')
  await assert.rejects(fs.access(path.join(shadowRoot, 'phone-id-01')))

  // 仓库里那份改了 → 影子跟着改; 删了 → 进回收站
  await writeFileUnder(repoDir, 'workspaces/PC/dsh/src/app.ts', 'pc version 2\n')
  await materializeSyncShadows({ repoDir, shadowRoot, cards, ownDeviceId: 'phone-id-01', wasMirroredFor: () => async () => true })
  assert.equal(await fs.readFile(target, 'utf8'), 'pc version 2\n')
  await fs.rm(path.join(repoDir, 'workspaces', 'PC', 'dsh', 'src', 'app.ts'))
  const afterDelete = await materializeSyncShadows({ repoDir, shadowRoot, cards, ownDeviceId: 'phone-id-01', wasMirroredFor: () => async () => true })
  assert.equal(afterDelete.trashed, 1)
  await assert.rejects(fs.access(target))
})

test('finishExternalPull adopts first, then purges the mirror (a slow purge cannot block the fetch)', async (t) => {
  if (!GIT_OK) return
  const root = await makeTemp(t)
  const sessionRoot = path.join(root, 'sessions')
  const repoDir = path.join(root, 'repo')
  // 本地会话库: 一场本地自己的会话
  await writeFileUnder(sessionRoot, '--D-apk--/session-local/session.v4.jsonl.zstd', 'local')
  // 镜像: 一场要取回的新会话 + 一场应当被首次清理删掉的旧会话
  await writeFileUnder(repoDir, 'sessions/--D-apk--/session-remote/session.v4.jsonl.zstd', 'remote')
  await writeFileUnder(repoDir, 'sessions/--D-apk--/session-old/session.v4.jsonl.zstd', 'old')
  const git = new GitBackend({
    repoDir,
    remote: 'file:///nonexistent',
    branch: 'main',
    commitName: 'test-sync',
    commitEmail: 'test@local',
    run: realRunner,
    timeoutMs: 60000,
  })
  await git.bootstrap()
  const baseline = { '--D-apk--/session-old/session.v4.jsonl.zstd': 0 }
  let purgeDone = false
  let purgeCalls = 0
  let adopted = 0
  let purgeSawAdopt = false
  const engine = new SyncEngine({
    git,
    sessionRoot,
    repoDir,
    remote: 'file:///nonexistent',
    branch: 'main',
    deviceId: 'test',
    sources: [{
      label: 'sessions',
      root: sessionRoot,
      mirrorDir: 'sessions',
      useBaseline: true,
      adoptRemote: (context) => {
        adopted += 1
        return adoptMergedSessions({
          sessionRoot,
          repoDir,
          mirrorDir: 'sessions',
          wasMirrored: context?.wasMirrored,
          // 与 index.mjs 的接线同一个形状: 基线里仍然算旧的那条不取回
          skipOld: async rel => localStillOld(sessionRoot, rel, baseline),
        })
      },
    }],
    baseline: {
      load: async () => ({ paths: baseline }),
      pending: async () => !purgeDone,
      purgePaths: async () => {
        purgeCalls += 1
        purgeSawAdopt = adopted > 0
        return ['--D-apk--/session-old/session.v4.jsonl.zstd']
      },
      done: async () => { purgeDone = true },
    },
    reportMeta() {},
    reportError() {},
    onForks() {},
    logger: { debug() {}, info() {}, warn() {}, error() {} },
  })
  const consumed = []
  engine.afterPull = async context => { consumed.push(context) }
  const result = await engine.finishExternalPull({ head: 'h', remoteHead: 'h' })
  // 清理走镜像那一侧 (oldMirrorPaths), 不再扫整个 sessionRoot
  assert.equal(purgeCalls, 0, 'the purge walks the mirror, not the whole sessionRoot')
  assert.equal(result.purged, 1)
  // 新会话取回, 旧会话一条都不取 (基线里仍然算旧)
  assert.equal(result.brought, 1)
  assert.equal(result.skipped, 1)
  assert.equal(purgeDone, true)
  assert.equal(adopted > 0, true, 'adopt ran before the purge')
  assert.equal(consumed.length, 1)
  assert.equal(consumed[0].purged, 1)
  // 新会话取回到本地库
  assert.equal(await fs.readFile(path.join(sessionRoot, '--D-apk--', 'session-remote', 'session.v4.jsonl.zstd'), 'utf8'), 'remote')
  // 旧会话既没被留在镜像里, 也没被导进本地库
  await assert.rejects(fs.access(path.join(repoDir, 'sessions', '--D-apk--', 'session-old', 'session.v4.jsonl.zstd')))
  await assert.rejects(fs.access(path.join(sessionRoot, '--D-apk--', 'session-old', 'session.v4.jsonl.zstd')))
})

test('工作区名片内容没变就不再写 (at 不再每轮刷新, 否则每轮 pull 长一个 fork)', async (t) => {
  const root = await makeTemp(t)
  const repoDir = path.join(root, 'repo')
  const workspaces = [{ slug: 'dsh', title: 'dsh' }]
  assert.equal(await writeWorkspaceCard({ repoDir, deviceId: 'pc-id-0001', name: 'PC', workspaces, at: 111 }), true)
  const first = await fs.readFile(path.join(repoDir, 'workspace-peers', 'pc-id-0001.json'), 'utf8')
  // 第二趟内容一模一样, 只有 at 不同 → 不写, 文件字节一个不改
  assert.equal(await writeWorkspaceCard({ repoDir, deviceId: 'pc-id-0001', name: 'PC', workspaces, at: 999 }), false)
  assert.equal(await fs.readFile(path.join(repoDir, 'workspace-peers', 'pc-id-0001.json'), 'utf8'), first)
  assert.equal(JSON.parse(first).at, 111, 'at 记的是"上一次内容变更"的时刻')
  // 工作区清单变了 → 写, 且换新的 at
  assert.equal(await writeWorkspaceCard({ repoDir, deviceId: 'pc-id-0001', name: 'PC', workspaces: [{ slug: 'dsh', title: 'Dsh' }], at: 222 }), true)
  const second = JSON.parse(await fs.readFile(path.join(repoDir, 'workspace-peers', 'pc-id-0001.json'), 'utf8'))
  assert.equal(second.at, 222)
  assert.deepEqual(second.workspaces, [{ slug: 'dsh', title: 'Dsh' }])
})
