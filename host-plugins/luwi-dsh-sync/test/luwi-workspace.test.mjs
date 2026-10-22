// test/luwi-workspace.test.mjs — Luwi 加 (G1-C 工作区镜面) 的回归套件。
//
// 上游测试里没有这些: 上游只有一根 (会话根), 排除表 / 单文件上限 / 第二根镜像 / 取回那边
// 的"更新与回收站"都是二开的东西。这里钉住五件事:
//   1. 排除表匹配器与遍历剪枝 (`.lw-sync` 不剪就是无限递归)
//   2. 镜像: 排除的进不去、旧的被撤回、超限的如实报且不动远端已有那份
//   3. 取回: bring-missing 与 mirror 两个模式的分支 (取回 / 更新 / 搬进回收站)
//   4. 规格校验: 缺省、强制排除项、越界配置响亮失败、桥模式下工作树必须落在工作区里
//   5. 判据 6 的双设备全流程: 放 / 改 / 删一个文件, 另一侧 pull 后逐字节一致, `.lw-sync` 不外泄
// @module dsh-session-sync/test/luwi-workspace.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { makeExcludeMatcher, mirrorSessionRoot } from '../lib/mirror.mjs'
import { adoptMergedSessions } from '../lib/luwi-adopt.mjs'
import { GitBackend } from '../lib/git.mjs'
import { SyncEngine } from '../lib/engine.mjs'
import { normalizeExclude, resolveWorkspaceMirror } from '../lib/luwi-workspace.mjs'
import { TOOL_PULL, TOOL_PUSH, TOOL_STATUS } from '../lib/constants.mjs'
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

/** 一棵树里的全部相对文件 (跳过 .git 之类)。 */
function treeOf(root) {
  const found = []
  const walk = (dir, rel) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === '.git') continue
      const next = rel === '' ? entry.name : `${rel}/${entry.name}`
      if (entry.isDirectory()) walk(join(dir, entry.name), next)
      else found.push(next)
    }
  }
  if (existsSync(root)) walk(root, '')
  return found.sort()
}

const EXCLUDE = ['.lw-sync', '.git']

// ---------------------------------------------------------------------------
// 1 · 排除表匹配器
// ---------------------------------------------------------------------------

test('exclude matcher keeps prefixes, drops traversal and absolute entries', () => {
  const hit = makeExcludeMatcher(['.lw-sync', './build/', 'node_modules', '/abs', '../escape', 'a//b'])
  assert.equal(hit('.lw-sync'), true, 'the entry itself matches')
  assert.equal(hit('.lw-sync/repo/sessions/x'), true, 'everything below the entry matches')
  assert.equal(hit('.lw-syncx'), false, 'a sibling with the same prefix is not a match')
  assert.equal(hit('build/out.js'), true, 'leading ./ and trailing / are normalized away')
  assert.equal(hit('node_modules'), true)
  assert.equal(hit('src/a.js'), false)
  assert.equal(makeExcludeMatcher(undefined)('anything'), false, 'no list means no filtering')
})

test('normalizeExclude rejects entries that would point outside the root', () => {
  assert.deepEqual(normalizeExclude([' a ', 'a', './b/']), ['a', 'b'])
  assert.throws(() => normalizeExclude(['../x']), /relative path inside the workspace root/u)
  assert.throws(() => normalizeExclude(['/etc']), /relative path inside the workspace root/u)
  assert.throws(() => normalizeExclude([42]), /must be strings/u)
})

// ---------------------------------------------------------------------------
// 2 · 镜像: 剪枝 / 撤回 / 超限
// ---------------------------------------------------------------------------

test('the mirror prunes excluded trees, withdraws old copies, and reports oversized files', async () => {
  const root = makeRoot('lw-mirror')
  try {
    const source = join(root, 'ws')
    const repo = join(root, 'repo')
    put(source, 'notes/a.txt', 'a')
    put(source, 'notes/deep/b.txt', 'b')
    put(source, '.lw-sync/repo/secret.txt', 'never syncs')
    put(source, 'node_modules/pkg/index.js', 'never syncs either')
    put(source, 'big.bin', 'x'.repeat(64))
    // 工作树里先放一份"上一轮镜像留下的被排除文件"与一份"上一轮交出去过的大文件"
    put(repo, 'workspace/.lw-sync/stale.txt', 'stale')
    put(repo, 'workspace/big.bin', 'previously mirrored')

    const report = await mirrorSessionRoot({
      sessionRoot: source,
      repoDir: repo,
      mirrorDir: 'workspace',
      exclude: [...EXCLUDE, 'node_modules'],
      maxFileBytes: 16,
    })

    assert.equal(report.mirrored, 2, `only the two ordinary files are mirrored: ${JSON.stringify(report)}`)
    assert.deepEqual(treeOf(join(repo, 'workspace')), ['big.bin', 'notes/a.txt', 'notes/deep/b.txt'])
    assert.equal(report.excludedCount, 2)
    assert.deepEqual(report.excluded.sort(), ['.lw-sync', 'node_modules'])
    assert.equal(report.oversizedCount, 1)
    assert.deepEqual(report.oversized, ['big.bin'])
    assert.equal(report.deleted.includes('.lw-sync/stale.txt'), true, 'excluding a path withdraws its old mirror copy')
    assert.equal(take(repo, 'workspace/big.bin'), 'previously mirrored', 'an oversized file does not update the mirror')
    assert.equal(report.deleted.includes('big.bin'), false, 'and it is not withdrawn either (the remote keeps what it had)')
    assert.equal(take(source, '.lw-sync/repo/secret.txt'), 'never syncs', 'the walk prunes, it does not delete the source')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 3 · 取回: bring-missing 与 mirror
// ---------------------------------------------------------------------------

/** 布置一份取回场景: 工作树 (远端合并结果) + 本地根。 */
function seedAdopt(root) {
  const repo = join(root, 'repo')
  const local = join(root, 'ws')
  put(repo, 'workspace/from-remote.txt', 'remote only')
  put(repo, 'workspace/changed.txt', 'remote version')
  put(local, 'changed.txt', 'local old version')
  put(local, 'local-only.txt', 'local only')
  return { repo, local }
}

test('bring-missing only adds what is missing and never touches the local side', async () => {
  const root = makeRoot('lw-adopt-bring')
  try {
    const { repo, local } = seedAdopt(root)
    const report = await adoptMergedSessions({ sessionRoot: local, repoDir: repo, mirrorDir: 'workspace' })
    assert.equal(report.brought, 1)
    assert.equal(report.updated, 0)
    assert.equal(report.trashed, 0)
    assert.equal(take(local, 'from-remote.txt'), 'remote only')
    assert.equal(take(local, 'changed.txt'), 'local old version', 'the local lineage wins in this mode')
    assert.equal(take(local, 'local-only.txt'), 'local only')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('mirror mode brings, updates and trashes, and the trash keeps the removed bytes', async () => {
  const root = makeRoot('lw-adopt-mirror')
  try {
    const { repo, local } = seedAdopt(root)
    const trash = join(local, '.lw-sync', 'trash')
    const report = await adoptMergedSessions({
      targetRoot: local,
      repoDir: repo,
      mirrorDir: 'workspace',
      mode: 'mirror',
      exclude: EXCLUDE,
      trashDir: trash,
      // 删除的前提: 这条路径上一趟镜像交出去过 (引擎按 pull 的 checkpoint 提交回答)
      wasMirrored: async (rel) => rel === 'local-only.txt',
      now: new Date(Date.UTC(2026, 9, 13, 5, 4, 3)),
    })
    assert.equal(report.brought, 1, JSON.stringify(report))
    assert.equal(report.updated, 1)
    assert.equal(report.trashed, 1)
    assert.equal(take(local, 'from-remote.txt'), 'remote only')
    assert.equal(take(local, 'changed.txt'), 'remote version', 'a remote edit reaches the local file')
    assert.equal(take(local, 'local-only.txt'), undefined, 'a file the merge dropped leaves the local root')
    assert.equal(take(trash, '20261013050403/local-only.txt'), 'local only', 'and its bytes are in the trash')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('mirror mode leaves excluded and oversized local files alone', async () => {
  const root = makeRoot('lw-adopt-guards')
  try {
    const repo = join(root, 'repo')
    const local = join(root, 'ws')
    put(repo, 'workspace/keep.txt', 'mirror')
    put(local, 'keep.txt', 'mirror')
    put(local, '.lw-sync/repo/inside.txt', 'the sync home is not ours to move')
    put(local, 'node_modules/pkg/x.js', 'excluded')
    put(local, 'big.bin', 'y'.repeat(64))
    const report = await adoptMergedSessions({
      targetRoot: local,
      repoDir: repo,
      mirrorDir: 'workspace',
      mode: 'mirror',
      exclude: [...EXCLUDE, 'node_modules'],
      maxFileBytes: 16,
      trashDir: join(local, '.lw-sync', 'trash'),
      wasMirrored: async () => true,
    })
    assert.equal(report.trashed, 0, JSON.stringify(report))
    assert.equal(report.brought, 0)
    assert.equal(take(local, '.lw-sync/repo/inside.txt'), 'the sync home is not ours to move')
    assert.equal(take(local, 'node_modules/pkg/x.js'), 'excluded')
    assert.equal(take(local, 'big.bin'), 'y'.repeat(64))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('mirror mode never moves a file the mirror never carried', async () => {
  const root = makeRoot('lw-adopt-proof')
  try {
    const repo = join(root, 'repo')
    const local = join(root, 'ws')
    put(repo, 'workspace/kept.txt', 'in the mirror')
    put(local, 'kept.txt', 'in the mirror')
    put(local, 'written-during-the-pull.txt', 'brand new')
    // 这一份是"上一趟镜像交出去过"的 (在 checkpoint 提交里), 那一份不是
    const report = await adoptMergedSessions({
      targetRoot: local,
      repoDir: repo,
      mirrorDir: 'workspace',
      mode: 'mirror',
      exclude: EXCLUDE,
      trashDir: join(local, '.lw-sync', 'trash'),
      wasMirrored: async (rel) => rel === 'gone-elsewhere.txt',
    })
    assert.equal(report.trashed, 0, JSON.stringify(report))
    assert.equal(take(local, 'written-during-the-pull.txt'), 'brand new', 'a file written mid-pull stays put')
    // 没有证明手段时同样一条都不搬 (缺省就是 fail-safe)
    const cautious = await adoptMergedSessions({
      targetRoot: local,
      repoDir: repo,
      mirrorDir: 'workspace',
      mode: 'mirror',
      exclude: EXCLUDE,
      trashDir: join(local, '.lw-sync', 'trash'),
    })
    assert.equal(cautious.trashed, 0)
    assert.equal(cautious.warnings.length, 1, JSON.stringify(cautious))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 4 · 规格校验
// ---------------------------------------------------------------------------

test('the workspace mirror stays off without a remote and off when disabled', () => {
  const workspace = makeRoot('lw-spec-off')
  try {
    assert.equal(resolveWorkspaceMirror({ workspace, config: undefined }), null)
    assert.equal(resolveWorkspaceMirror({ workspace, config: {} }), null, 'no remote, no mirror')
    assert.equal(resolveWorkspaceMirror({ workspace, config: { enabled: false, remote: 'x' } }), null)
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('an enabled workspace mirror pins the mandatory excludes and its worktree', () => {
  const workspace = makeRoot('lw-spec-on')
  try {
    const spec = resolveWorkspaceMirror({ workspace, config: { remote: 'https://example.com/ws.git' } })
    assert.equal(spec.root, workspace)
    assert.equal(spec.mirrorDir, 'workspace')
    assert.equal(spec.branch, 'main')
    assert.equal(spec.maxFileBytes, 8 * 1024 * 1024)
    assert.equal(spec.repoDir, join(workspace, '.lw-sync', 'workspace-sync', 'repo'))
    assert.equal(spec.exclude.includes('.lw-sync'), true, 'dropping this one would make the mirror recurse')
    assert.equal(spec.exclude.includes('.git'), true)
    assert.equal(spec.adoptMode, 'mirror')
    assert.equal(spec.trashDir, join(workspace, '.lw-sync', 'trash'))

    // 工作树被挪到工作区里别处时, 它自己也要进排除表 (否则镜像把工作树抄进自己)
    const moved = resolveWorkspaceMirror({ workspace, config: { remote: 'r', repoDir: 'mirror/repo' } })
    assert.equal(moved.repoDir, join(workspace, 'mirror', 'repo'))
    assert.equal(moved.exclude.includes('mirror/repo'), true)
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('bad workspace config fails loudly at load time', () => {
  const workspace = makeRoot('lw-spec-bad')
  try {
    assert.throws(() => resolveWorkspaceMirror({ workspace, config: { remote: 'r', maxFileBytes: 1.5 } }), /maxFileBytes/u)
    assert.throws(() => resolveWorkspaceMirror({ workspace, config: { remote: 'r', maxFileBytes: -1 } }), /maxFileBytes/u)
    assert.throws(() => resolveWorkspaceMirror({ workspace, config: { remote: 'r', branch: 'bad branch' } }), /branch/u)
    assert.throws(() => resolveWorkspaceMirror({ workspace, config: { remote: 'r', exclude: ['../up'] } }), /relative path/u)
    assert.throws(() => resolveWorkspaceMirror({ workspace, config: { remote: 'r', root: join(workspace, 'nope') } }), /not a directory/u)
    assert.throws(() => resolveWorkspaceMirror({ workspace, config: [] }), /must be an object/u)
    // 手机上 (bridge 在) 工作树必须在工作区里: 沙盒只看得见绑到 /work 的那一棵
    assert.throws(
      () => resolveWorkspaceMirror({ workspace, config: { remote: 'r', repoDir: 'D:/elsewhere/repo' }, bridge: { endpoint: 'x', token: 'y' } }),
      /outside the workspace/u,
    )
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 5 · 判据 6: 双设备全流程
// ---------------------------------------------------------------------------

/** 一台"设备": 工作区根 + 自己的工作树, 走 mirror 模式的取回。 */
function makeDevice(root, name, remote) {
  const workspace = join(root, name, 'ws')
  const repoDir = join(workspace, '.lw-sync', 'workspace-sync', 'repo')
  const exclude = EXCLUDE
  mkdirSync(workspace, { recursive: true })
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
    sessionRoot: workspace,
    repoDir,
    remote,
    branch: 'main',
    deviceId: name,
    label: 'workspace',
    mirrorDir: 'workspace',
    deviceFile: null,
    exclude,
    maxFileBytes: 1024,
    reportMeta: () => {},
    reportError: () => {},
    onForks: () => {},
    adoptRemote: (context) => adoptMergedSessions({
      targetRoot: workspace,
      repoDir,
      mirrorDir: 'workspace',
      mode: 'mirror',
      exclude,
      maxFileBytes: 1024,
      trashDir: join(workspace, '.lw-sync', 'trash'),
      wasMirrored: context?.wasMirrored,
    }),
    logger: { debug() {}, info() {}, warn() {}, error() {} },
  })
  return { workspace, repoDir, engine }
}

test('criterion 6: put, edit and delete on one side arrive byte-for-byte on the other', async () => {
  const root = makeRoot('lw-ws-e2e')
  try {
    const bare = join(root, 'remote.git')
    assert.equal(runGit(['init', '--bare', '--initial-branch=main', bare]).code, 0)
    const a = makeDevice(root, 'A', bare)
    const b = makeDevice(root, 'B', bare)

    // 放: 一个新文件 + 一个工作区里本来就有的 .lw-sync (它一个字节都不该外泄)
    put(a.workspace, 'notes/hello.txt', 'hello from A\n')
    put(a.workspace, 'docs/readme.md', '# 工作区\n')
    put(a.workspace, '.lw-sync/private.txt', 'the sync home itself')
    const pushA = await a.engine.push()
    assert.equal(pushA.ok, true, pushA.error)
    assert.equal(pushA.excluded, 1, JSON.stringify(pushA))

    const pullB = await b.engine.pull()
    assert.equal(pullB.ok, true, pullB.error)
    assert.equal(pullB.brought, 2, JSON.stringify(pullB))
    assert.equal(take(b.workspace, 'notes/hello.txt'), 'hello from A\n')
    assert.equal(take(b.workspace, 'docs/readme.md'), '# 工作区\n')
    assert.equal(take(b.workspace, '.lw-sync/private.txt'), undefined, 'the sync home never crosses over')
    assert.equal(treeOf(join(b.repoDir, 'workspace')).includes('.lw-sync/private.txt'), false)

    // 改: A 改内容, B pull 之后字节一致 (而且 B 本地原来的内容不被当冲突留下)
    put(a.workspace, 'notes/hello.txt', 'hello again from A\n')
    const pushA2 = await a.engine.push()
    assert.equal(pushA2.ok, true, pushA2.error)
    const pullB2 = await b.engine.pull()
    assert.equal(pullB2.ok, true, pullB2.error)
    assert.equal(pullB2.updated, 1, JSON.stringify(pullB2))
    assert.equal(take(b.workspace, 'notes/hello.txt'), 'hello again from A\n')

    // 删: A 删掉, B pull 之后本地那份进回收站, 原路径没了
    rmSync(join(a.workspace, 'notes', 'hello.txt'))
    const pushA3 = await a.engine.push()
    assert.equal(pushA3.ok, true, pushA3.error)
    assert.equal(pushA3.deleted, 1, JSON.stringify(pushA3))
    const pullB3 = await b.engine.pull()
    assert.equal(pullB3.ok, true, pullB3.error)
    assert.equal(pullB3.trashed, 1, JSON.stringify(pullB3))
    assert.equal(take(b.workspace, 'notes/hello.txt'), undefined)
    assert.equal(treeOf(join(b.workspace, '.lw-sync', 'trash')).length, 1, 'the removed bytes are parked in the trash')

    // 两边的"该同步的东西"此时完全一致; 远端树里从来没有 .lw-sync
    const remoteTree = runGit(['--git-dir', bare, 'ls-tree', '-r', '--name-only', 'main']).stdout.trim().split('\n').filter(Boolean)
    assert.equal(remoteTree.some(path => path.includes('.lw-sync')), false, JSON.stringify(remoteTree))
    assert.deepEqual(treeOf(join(b.repoDir, 'workspace')), ['docs/readme.md'])
    assert.deepEqual(treeOf(join(a.workspace)).filter(path => !path.startsWith('.lw-sync/')), ['docs/readme.md'])
    assert.deepEqual(treeOf(join(b.workspace)).filter(path => !path.startsWith('.lw-sync/')), ['docs/readme.md'])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('a file both sides edited is kept on both sides, never overwritten', async () => {
  const root = makeRoot('lw-ws-conflict')
  try {
    const bare = join(root, 'remote.git')
    assert.equal(runGit(['init', '--bare', '--initial-branch=main', bare]).code, 0)
    const a = makeDevice(root, 'A', bare)
    const b = makeDevice(root, 'B', bare)

    put(a.workspace, 'shared.txt', 'base\n')
    assert.equal((await a.engine.push()).ok, true)
    assert.equal((await b.engine.pull()).ok, true)
    assert.equal(take(b.workspace, 'shared.txt'), 'base\n')

    put(a.workspace, 'shared.txt', 'A rewrote it\n')
    assert.equal((await a.engine.push()).ok, true)
    put(b.workspace, 'shared.txt', 'B rewrote it too\n')
    const pullB = await b.engine.pull()
    assert.equal(pullB.ok, true, pullB.error)
    assert.equal(pullB.diverged, 1, JSON.stringify(pullB))
    assert.equal(take(b.workspace, 'shared.txt'), 'B rewrote it too\n', 'the local side keeps its bytes')
    assert.equal(pullB.forks.length, 1)
    assert.equal(take(b.repoDir, `${pullB.forks[0]}`), 'A rewrote it\n', 'the remote side is parked as a fork file')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('a remote delete against a local edit keeps the local edit and drops no bytes', async () => {
  const root = makeRoot('lw-ws-delete-edit')
  try {
    const bare = join(root, 'remote.git')
    assert.equal(runGit(['init', '--bare', '--initial-branch=main', bare]).code, 0)
    const a = makeDevice(root, 'A', bare)
    const b = makeDevice(root, 'B', bare)

    put(a.workspace, 'doc.txt', 'v1\n')
    assert.equal((await a.engine.push()).ok, true)
    assert.equal((await b.engine.pull()).ok, true)
    // A 删掉, B 同时改了 —— 这一支在 git 里是 modify/delete 冲突, 只有一边有 stage
    rmSync(join(a.workspace, 'doc.txt'))
    assert.equal((await a.engine.push()).ok, true)
    put(b.workspace, 'doc.txt', 'B changed it\n')
    const pullB = await b.engine.pull()
    assert.equal(pullB.ok, true, pullB.error)
    assert.equal(take(b.workspace, 'doc.txt'), 'B changed it\n', 'the local edit survives, it is not trashed')
    assert.equal(pullB.trashed, 0, JSON.stringify(pullB))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// 6 · 目标解析与插件装配 (target: sessions | workspace)
// ---------------------------------------------------------------------------

test('the target resolver picks an engine and refuses loudly when the workspace mirror is off', () => {
  const sessions = { name: 'sessions-engine' }
  const workspace = { name: 'workspace-engine' }
  const both = plugin.makeTargetResolver({ sessions, workspace })
  assert.equal(both(undefined).engine, sessions, 'the default stays the session store')
  assert.equal(both('').engine, sessions)
  assert.equal(both('sessions').label, '')
  assert.equal(both('workspace').engine, workspace)
  assert.equal(both('workspace').label, 'workspace')
  const alone = plugin.makeTargetResolver({ sessions, workspace: null })
  const refused = alone('workspace')
  assert.equal(refused.ok, false)
  assert.match(refused.error, /workspace mirror is off/u)
})

/**
 * 装配一台最小宿主 (与上游 lifecycle 套件同一套路: 真注册表 + mock 存储/子进程)。
 * `mode` 决定工作区那一段开不开 —— 工作区根显式指到临时目录里 (缺省是 process.cwd(),
 * 那是**工作区**, 测试里不能拿插件目录当工作区)。
 */
async function mountPlugin(mode) {
  const { Context } = await import('@deepseek-ai/cordis')
  const { default: SessionStore } = await import('@deepseek-ai/dsh-session')
  const { default: ToolRuntime } = await import('@deepseek-ai/dsh-tools')
  const { default: CommandRuntime } = await import('@deepseek-ai/dsh-commands')
  const root = makeRoot('lw-mount')
  const workspaceRoot = join(root, 'ws')
  const sessionRoot = join(root, 'sessions')
  mkdirSync(workspaceRoot, { recursive: true })
  mkdirSync(sessionRoot, { recursive: true })
  const ctx = new Context()
  await ctx.plugin(SessionStore)
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
  const workspaceSection = mode === 'workspace-on'
    ? { workspace: { remote: 'https://example.com/ws.git', root: workspaceRoot } }
    : {}
  const fiber = await ctx.plugin(plugin, { sessionRoot, repoDir: join(root, 'repo'), ...workspaceSection })
  return { ctx, fiber, root, workspaceRoot }
}

test('mounting with a workspace section adds that source to the single repository', async () => {
  const harness = await mountPlugin('workspace-on')
  try {
    const statusTool = harness.ctx.tools.get(TOOL_STATUS)
    const pull = harness.ctx.tools.get(TOOL_PULL)
    const push = harness.ctx.tools.get(TOOL_PUSH)
    assert.ok(statusTool !== undefined && pull !== undefined && push !== undefined, 'the three tools must register')
    // target 枚举留着 (老调用不炸), 但 2026-10-20 起两种 target 落到**同一个仓库**
    for (const tool of [statusTool, pull, push]) {
      assert.deepEqual(tool.parameters.properties.target.enum, ['sessions', 'workspace'], `${tool.name} must carry the target enum`)
    }

    // 工作区那根走的是配置里那个 root: 放一份 `.lw-sync` 进去, 它必须被排除表挡在门外
    put(harness.workspaceRoot, '.lw-sync/junk.txt', 'not ours to sync')
    const status = await statusTool.execute({}, {})
    assert.equal(status.ok, true, status.error)
    assert.equal(status.mirrorDir, 'sessions')
    assert.ok(Array.isArray(status.sources), 'the unified status lists every source')
    assert.ok(status.sources.some(source => source.label === 'sessions'))
    const workspaceSource = status.sources.find(source => source.label.startsWith('workspace: '))
    assert.ok(workspaceSource !== undefined, `the workspace source is listed: ${JSON.stringify(status.sources)}`)
    assert.ok(workspaceSource.excludedCount >= 1, `the worktree home is excluded: ${JSON.stringify(workspaceSource)}`)

    // 老的 target=workspace 不再换引擎: 它就是同一份统一读数
    const viaTarget = await statusTool.execute({ target: 'workspace' }, {})
    assert.equal(viaTarget.ok, true, viaTarget.error)
    assert.equal(viaTarget.mirrorDir, 'sessions')
  } finally {
    await harness.ctx.fiber.dispose()
    rmSync(harness.root, { recursive: true, force: true })
  }
})

test('without a workspace section the target parameter still resolves to the single repository', async () => {
  const harness = await mountPlugin('sessions-only')
  try {
    const statusTool = harness.ctx.tools.get(TOOL_STATUS)
    const status = await statusTool.execute({ target: 'workspace' }, {})
    assert.equal(status.ok, true, status.error)
    assert.equal(status.mirrorDir, 'sessions')
    const sessions = await statusTool.execute({}, {})
    assert.notEqual(sessions.ok, false, 'the session target still works')
  } finally {
    await harness.ctx.fiber.dispose()
    rmSync(harness.root, { recursive: true, force: true })
  }
})
