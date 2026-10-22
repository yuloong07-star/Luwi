// lib/github-api.mjs 的回归测试 (Luwi 加 2026-10-20 晚)
//
// 这一份钉住的是"git 同步方式下对话未同步"那三条根因:
// 1. 沙盒超时 (手机桥) 必须被认成网络失败, 否则 API 退路一次都不跑
// 2. push 取**工作树**快照, 并且是与远端树的**并集** (远端已有、本机没有的文件不能被抹掉)
// 3. pull **不删本地文件**, 且不再有 512 KiB 上限 (会话日志动辄几 MB)
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, test } from 'node:test'
import {
  blobShaOf,
  decidePullAction,
  isForkPath,
  looksLikeNetworkFailure,
  makeGithubApi,
  mergeTreeEntries,
  ownershipOfPath,
} from '../lib/github-api.mjs'
import { mirrorSessionRoot } from '../lib/mirror.mjs'
import { adoptMergedSessions } from '../lib/luwi-adopt.mjs'
import { assembleParts } from '../lib/luwi-parts.mjs'

const made = []
afterEach(async () => {
  while (made.length > 0) await rm(made.pop(), { recursive: true, force: true })
})

/** 建一个临时目录 */
async function tempDir() {
  const dir = await mkdtemp(join(tmpdir(), 'luwi-api-'))
  made.push(dir)
  return dir
}

/** 造一个 fetch 响应 */
function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
  }
}

/** 一条失败了的 git 进程 (execFile 的形状) */
function gitFailure(code, stderr) {
  const error = new Error(stderr)
  error.code = code
  error.stdout = ''
  error.stderr = stderr
  return error
}

test('the sandbox timeout is a network failure, a stale index.lock is not', () => {
  assert.equal(looksLikeNetworkFailure('git fetch failed: the sandbox never reported an exit code: the command did not finish within 120000ms and the whole process group was killed: (no output)'), true)
  assert.equal(looksLikeNetworkFailure('fatal: unable to access \'https://github.com/o/r.git\': Could not resolve host: github.com'), true)
  assert.equal(looksLikeNetworkFailure('fatal: unable to access \'https://github.com/o/r/\': OpenSSL SSL_read: OpenSSL/3.5.6: error:0A000126:SSL routines::unexpected eof while reading, errno 0'), true)
  assert.equal(looksLikeNetworkFailure('git network skipped: the api.github.com fallback is in its cooldown window'), true)
  assert.equal(looksLikeNetworkFailure('git commit failed: fatal: Unable to create \'E:/repo/.git/index.lock\': File exists.'), false)
  assert.equal(looksLikeNetworkFailure('api GET /git/ref/heads/main -> 404 not found'), false)
  assert.equal(looksLikeNetworkFailure(''), false)
})

test('blobShaOf is git hash-object (hello + newline)', () => {
  assert.equal(blobShaOf('hello\n'), 'ce013625030ba8dba906f756967f9e9ca394464a')
})

test('pull decides by local HEAD, not by "remote said so"', () => {
  const base = { localExists: true, localSha: 'a', localHeadSha: 'a', remoteSha: 'a', localMtimeMs: 1, remoteAt: 2 }
  assert.equal(decidePullAction({ ...base, localExists: false }), 'write')
  assert.equal(decidePullAction(base), 'skip')
  assert.equal(decidePullAction({ ...base, remoteSha: 'b' }), 'write')
  assert.equal(decidePullAction({ ...base, localSha: 'c', remoteSha: 'b' }), 'fork')
  assert.equal(decidePullAction({ ...base, localSha: 'c' }), 'keepLocal')
  assert.equal(decidePullAction({ ...base, localSha: 'c', remoteSha: 'b', localHeadSha: undefined, localMtimeMs: 5, remoteAt: 3 }), 'keepLocal')
  assert.equal(decidePullAction({ ...base, localSha: 'c', remoteSha: 'b', localHeadSha: undefined, localMtimeMs: 1, remoteAt: 3 }), 'fork')
})

test('a push tree is the union of remote and local, minus explicit local deletions', () => {
  const merged = mergeTreeEntries(
    new Map([['a', { mode: '100644', sha: '1' }], ['b', { mode: '100644', sha: '2' }]]),
    new Map([['b', { mode: '100644', sha: '3' }], ['c', { mode: '100644', sha: '4' }]]),
    ['a'],
  )
  assert.deepEqual([...merged.entries.keys()].sort(), ['b', 'c'])
  assert.equal(merged.entries.get('b').sha, '3')
  assert.equal(merged.added, 1)
  assert.equal(merged.changed, 1)
  assert.equal(merged.removed, 1)
})

test('pull writes a big session, keeps a local-only file, and does not re-pull the same head', async () => {
  const repo = await tempDir()
  await writeFile(join(repo, 'local-only.txt'), 'mine')
  const big = Buffer.alloc(600 * 1024, 0x41)
  const bigSha = blobShaOf(big)
  const small = Buffer.from('hi')
  const smallSha = blobShaOf(small)
  const blobCalls = []
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha: 'r1' } })
    if (init?.method === 'GET' && path.includes('/git/commits/r1')) return response(200, { tree: { sha: 't1' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/t1')) {
      return response(200, {
        truncated: false,
        tree: [
          { path: 'sessions/big/session.v4.jsonl.zstd', type: 'blob', mode: '100644', sha: bigSha },
          { path: 'sessions/small/session.json', type: 'blob', mode: '100644', sha: smallSha },
        ],
      })
    }
    if (init?.method === 'GET' && path.includes('/git/blobs/')) {
      blobCalls.push(path)
      if (path.endsWith(bigSha)) return response(200, { content: big.toString('base64'), encoding: 'base64' })
      if (path.endsWith(smallSha)) return response(200, { content: small.toString('base64'), encoding: 'base64' })
      return response(404, { message: 'no such blob' })
    }
    return response(404, { message: `unexpected ${path}` })
  }
  const execGit = async (args) => {
    const rest = args
    if (rest[0] === 'rev-parse') throw gitFailure(128, 'fatal: Needed a single revision')
    if (rest[0] === 'add') return { stdout: '', stderr: '' }
    if (rest[0] === 'commit') return { stdout: '', stderr: '' }
    throw gitFailure(1, `unexpected git ${rest.join(' ')}`)
  }
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    fetchImpl,
    runGit: execGit,
  })
  // 本地已经有一份更短的会话 (同一个 append-only 日志的前缀): 远端那一份应当直接续上,
  // 不能因为"本地改过"就 keepLocal/fork (那会让手机永远停在旧的一段对话上)
  await mkdir(join(repo, 'sessions', 'big'), { recursive: true })
  await writeFile(join(repo, 'sessions', 'big', 'session.v4.jsonl.zstd'), big.subarray(0, 1024))
  const first = await api.pull()
  assert.equal(first.ok, true)
  assert.equal(first.written, 2)
  assert.equal(first.forked, 0, 'an append-only extension is a write, not a fork')
  assert.equal(blobCalls.length, 2)
  assert.equal(existsSync(join(repo, 'local-only.txt')), true, 'the api pull must not delete local-only files')
  assert.equal((await readFile(join(repo, 'sessions/big/session.v4.jsonl.zstd'))).length, 600 * 1024)
  assert.equal(await readFile(join(repo, 'sessions/small/session.json'), 'utf8'), 'hi')

  const again = await api.pull()
  assert.equal(again.ok, true)
  assert.equal(again.pulled, false)
  assert.equal(blobCalls.length, 2, 'the same remote head is not materialized twice')
})

test('fork 文件不参与同步: 不推、不拉', async () => {
  // 名字判据
  assert.equal(isForkPath('sessions/--D-apk--/session-a/session.v4.jsonl.zstd.remote-fork-20261021151215-PC'), true)
  assert.equal(isForkPath('device.txt.remote-fork-20261021060638-Luwi 手机'), true)
  assert.equal(isForkPath('sessions/--D-apk--/session-a/session.v4.jsonl.zstd'), false)
  assert.equal(isForkPath('live/dev/session-x.json'), false)

  const repo = await tempDir()
  const forkBytes = Buffer.from('conflict copy')
  const forkSha = blobShaOf(forkBytes)
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha: 'r3' } })
    if (init?.method === 'GET' && path.includes('/git/commits/r3')) return response(200, { tree: { sha: 't3' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/t3')) {
      return response(200, {
        truncated: false,
        tree: [{ path: 'sessions/s/session.v4.jsonl.zstd.remote-fork-20261021151215-PC', type: 'blob', mode: '100644', sha: forkSha }],
      })
    }
    if (init?.method === 'GET' && path.includes('/git/blobs/')) {
      return response(200, { content: forkBytes.toString('base64'), encoding: 'base64' })
    }
    return response(404, { message: `unexpected ${path}` })
  }
  const execGit = async (args) => {
    if (args[0] === 'rev-parse') throw gitFailure(128, 'fatal: Needed a single revision')
    if (args[0] === 'add' || args[0] === 'commit') return { stdout: '', stderr: '' }
    throw gitFailure(1, `unexpected git ${args.join(' ')}`)
  }
  const api = makeGithubApi({ remote: 'https://github.com/o/r.git', token: 't', branch: 'main', repoDir: repo, fetchImpl, runGit: execGit })
  const result = await api.pull()
  assert.equal(result.ok, true)
  assert.equal(result.forked, 0)
  assert.equal(result.ignoredForks, 1, 'fork 文件被跳过并计数')
  assert.equal(existsSync(join(repo, 'sessions/s/session.v4.jsonl.zstd.remote-fork-20261021151215-PC')), false)
})

test('本机正在写的那场会话: 对端推来的是更短的旧快照时不 fork、也不回退', async () => {
  const repo = await tempDir()
  await mkdir(join(repo, 'sessions', 'live'), { recursive: true })
  // 本机这一份更长, 且远端那份是它的前缀 (append-only: 精确包含)
  const localBytes = Buffer.from('frame-1\nframe-2\nframe-3\n')
  const remoteBytes = Buffer.from('frame-1\nframe-2\n')
  const remoteSha = blobShaOf(remoteBytes)
  await writeFile(join(repo, 'sessions', 'live', 'session.v4.jsonl.zstd'), localBytes)
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha: 'r2' } })
    if (init?.method === 'GET' && path.includes('/git/commits/r2')) return response(200, { tree: { sha: 't2' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/t2')) {
      return response(200, {
        truncated: false,
        tree: [{ path: 'sessions/live/session.v4.jsonl.zstd', type: 'blob', mode: '100644', sha: remoteSha }],
      })
    }
    if (init?.method === 'GET' && path.includes('/git/blobs/')) {
      return response(200, { content: remoteBytes.toString('base64'), encoding: 'base64' })
    }
    return response(404, { message: `unexpected ${path}` })
  }
  const execGit = async (args) => {
    if (args[0] === 'rev-parse') throw gitFailure(128, 'fatal: Needed a single revision')
    if (args[0] === 'add' || args[0] === 'commit') return { stdout: '', stderr: '' }
    throw gitFailure(1, `unexpected git ${args.join(' ')}`)
  }
  const api = makeGithubApi({ remote: 'https://github.com/o/r.git', token: 't', branch: 'main', repoDir: repo, fetchImpl, runGit: execGit })
  const result = await api.pull()
  assert.equal(result.ok, true)
  assert.equal(result.forked, 0, '对端的旧快照不该撞出一个 fork')
  assert.equal(await readFile(join(repo, 'sessions', 'live', 'session.v4.jsonl.zstd'), 'utf8'), localBytes.toString())
})

test('push 被拒 (422 not a fast forward) 时当拍重读 head 再推', async () => {
  const repo = await tempDir()
  await mkdir(join(repo, 'sessions'), { recursive: true })
  const mine = Buffer.from('mine')
  await writeFile(join(repo, 'sessions', 'a.json'), mine)
  const warnings = []
  let headCalls = 0
  let patches = 0
  const commits = []
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) {
      headCalls += 1
      return response(200, { object: { sha: headCalls === 1 ? 'r1' : 'r2' } })
    }
    if (init?.method === 'GET' && path.includes('/git/commits/')) return response(200, { tree: { sha: path.includes('r2') ? 'tree-r2' : 'tree-r1' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/')) {
      return response(200, { truncated: false, tree: [] })
    }
    if (init?.method === 'POST' && path.endsWith('/git/blobs')) return response(201, { sha: blobShaOf(mine) })
    if (init?.method === 'POST' && path.endsWith('/git/trees')) return response(201, { sha: `tree-${headCalls}` })
    if (init?.method === 'POST' && path.endsWith('/git/commits')) {
      commits.push(JSON.parse(init.body))
      return response(201, { sha: `c${commits.length}` })
    }
    if (init?.method === 'PATCH' && path.includes('/git/refs/heads/main')) {
      patches += 1
      if (patches === 1) return response(422, { message: 'Update is not a fast forward' })
      return response(200, { object: { sha: 'c2' } })
    }
    return response(404, { message: `unexpected ${init?.method} ${path}` })
  }
  const execGit = async (args) => {
    if (args[0] === 'rev-parse') return { stdout: 'abc\n', stderr: '' }
    if (args[0] === 'write-tree') return { stdout: 'local-tree\n', stderr: '' }
    if (args[0] === 'ls-tree') {
      const bytes = mine
      return { stdout: `100644 blob ${blobShaOf(bytes)} ${bytes.length}\tsessions/a.json\n`, stderr: '' }
    }
    if (args[0] === 'cat-file') return { stdout: mine, stderr: '' }
    if (args[0] === 'diff') return { stdout: '', stderr: '' }
    if (args[0] === 'commit') return { stdout: '', stderr: '' }
    throw gitFailure(1, `unexpected git ${args.join(' ')}`)
  }
  const api = makeGithubApi({ remote: 'https://github.com/o/r.git', token: 't', branch: 'main', repoDir: repo, fetchImpl, runGit: execGit, warn: m => warnings.push(m) })
  const result = await api.push({ message: 'retry' })
  assert.equal(result.ok, true, JSON.stringify(result))
  assert.equal(result.pushed, true)
  assert.equal(patches, 2, '被拒之后应当再推一次')
  assert.equal(headCalls, 2, '重推之前要重读一次远端 head')
  assert.ok(warnings.some(w => /lost the race/u.test(w)))
  assert.deepEqual(commits[1].parents, ['r2'], '第二个提交要接在重读到的 head 上')
})

test('推的时候还在写的那个文件: 跳过它, 别的照推 (不再让整趟失败)', async () => {
  const repo = await tempDir()
  await mkdir(join(repo, 'sessions'), { recursive: true })
  const stable = Buffer.from('stable')
  const churnFirst = Buffer.from('churn-v1')
  const churnSecond = Buffer.from('churn-v2')
  await writeFile(join(repo, 'sessions', 'a.json'), stable)
  await writeFile(join(repo, 'sessions', 'b.json'), churnFirst)
  const warnings = []
  let posted = 0
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha: 'r1' } })
    if (init?.method === 'GET' && path.includes('/git/commits/r1')) return response(200, { tree: { sha: 't1' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/t1')) return response(200, { truncated: false, tree: [] })
    if (init?.method === 'POST' && path.endsWith('/git/blobs')) {
      posted += 1
      // 第一次上传时把 b.json 改掉 —— 模拟"本机正在写它"
      if (posted === 1) await writeFile(join(repo, 'sessions', 'b.json'), churnSecond)
      return response(201, { sha: blobShaOf(Buffer.from(JSON.parse(init.body).content, 'base64')) })
    }
    if (init?.method === 'POST' && path.endsWith('/git/trees')) return response(201, { sha: 'new-tree' })
    if (init?.method === 'POST' && path.endsWith('/git/commits')) return response(201, { sha: 'c1' })
    if (init?.method === 'PATCH' && path.includes('/git/refs/heads/main')) return response(200, { object: { sha: 'c1' } })
    return response(404, { message: `unexpected ${init?.method} ${path}` })
  }
  const execGit = async () => { throw gitFailure(1, 'no git on this path') }
  const api = makeGithubApi({ remote: 'https://github.com/o/r.git', token: 't', branch: 'main', repoDir: repo, fetchImpl, runGit: execGit, warn: m => warnings.push(m) })
  const result = await api.push({ noGit: true })
  assert.equal(result.ok, true, JSON.stringify(result))
  assert.equal(result.pushed, true)
  assert.equal(result.uploaded, 1, '只有没在变的那个文件被推上去')
  assert.ok(warnings.some(w => /still being written/u.test(w)), warnings.join(' | '))
})

test('push snapshots the worktree and keeps remote-only files (union)', async () => {
  const repo = await tempDir()
  await mkdir(join(repo, 'shared'), { recursive: true })
  await mkdir(join(repo, 'local'), { recursive: true })
  await writeFile(join(repo, 'shared', 'file.json'), 'local-version')
  await writeFile(join(repo, 'local', 'new.json'), 'brand-new')

  const localShared = Buffer.from('local-version')
  const localNew = Buffer.from('brand-new')
  const remoteOnly = Buffer.from('from-the-phone')
  const remoteShared = Buffer.from('remote-version')
  const contents = new Map([
    [blobShaOf(localShared), localShared],
    [blobShaOf(localNew), localNew],
    [blobShaOf(remoteOnly), remoteOnly],
    [blobShaOf(remoteShared), remoteShared],
  ])
  const postedBlobs = []
  const postedTrees = []
  let postedCommit = null
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha: 'r1' } })
    if (init?.method === 'GET' && path.includes('/git/commits/r1')) return response(200, { tree: { sha: 'remote-tree' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/remote-tree')) {
      return response(200, {
        truncated: false,
        tree: [
          { path: 'shared/file.json', type: 'blob', mode: '100644', sha: blobShaOf(remoteShared) },
          { path: 'remote/only.json', type: 'blob', mode: '100644', sha: blobShaOf(remoteOnly) },
        ],
      })
    }
    if (init?.method === 'POST' && path.endsWith('/git/blobs')) {
      const body = JSON.parse(init.body)
      postedBlobs.push(Buffer.from(body.content, 'base64').toString('utf8'))
      return response(201, { sha: blobShaOf(Buffer.from(body.content, 'base64')) })
    }
    if (init?.method === 'POST' && path.endsWith('/git/trees')) {
      const body = JSON.parse(init.body)
      postedTrees.push(body.tree)
      return response(201, { sha: `tree-${postedTrees.length}` })
    }
    if (init?.method === 'POST' && path.endsWith('/git/commits')) {
      postedCommit = JSON.parse(init.body)
      return response(201, { sha: 'c1' })
    }
    if (init?.method === 'PATCH' && path.includes('/git/refs/heads/main')) return response(200, { object: { sha: 'c1' } })
    return response(404, { message: `unexpected ${init?.method} ${path}` })
  }
  const execGit = async (args, opts) => {
    const rest = args
    if (rest[0] === 'add') return { stdout: '', stderr: '' }
    if (rest[0] === 'write-tree') return { stdout: 'local-tree\n', stderr: '' }
    if (rest[0] === 'ls-tree') {
      assert.deepEqual(rest, ['ls-tree', '-r', '-t', '-l', '-z', 'local-tree'])
      const blob = (rel, bytes) => `100644 blob ${blobShaOf(bytes)} ${bytes.length}\t${rel}`
      return {
        stdout: [
          '040000 tree deadbeefdeadbeefdeadbeefdeadbeefdeadbeef -\tshared',
          blob('shared/file.json', localShared),
          '040000 tree cafebabecafebabecafebabecafebabecafebabe -\tlocal',
          blob('local/new.json', localNew),
          '',
        ].join('\0'),
        stderr: '',
      }
    }
    if (rest[0] === 'cat-file') {
      const bytes = contents.get(rest[2])
      if (bytes === undefined) throw gitFailure(1, 'missing blob')
      return { stdout: opts?.encoding === 'buffer' ? bytes : bytes.toString('utf8'), stderr: '' }
    }
    if (rest[0] === 'diff' || (rest[0] === '-c' && rest[2] === 'diff')) return { stdout: '', stderr: '' }
    if (rest[0] === 'commit') return { stdout: '', stderr: '' }
    throw gitFailure(1, `unexpected git ${rest.join(' ')}`)
  }
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    fetchImpl,
    runGit: execGit,
  })
  const result = await api.push()
  assert.equal(result.ok, true)
  assert.equal(result.pushed, true)
  assert.equal(result.uploaded, 2, 'the two local blobs are uploaded; the remote one is not')
  assert.deepEqual(postedBlobs.sort(), ['brand-new', 'local-version'].sort())
  assert.ok(postedCommit !== null)
  assert.deepEqual(postedCommit.parents, ['r1'])
  // 根 tree 的 entries 必须同时有 远端独有 / 本地覆盖 / 本地新增
  const root = postedTrees[postedTrees.length - 1]
  const byPath = new Map(root.map(entry => [entry.path, entry]))
  assert.equal(byPath.has('remote'), true, 'remote-only directory survives the push')
  assert.equal(byPath.has('shared'), true)
  assert.equal(byPath.has('local'), true)
})

test('noGit push (手机): 一条本地 git 都不发, 清单与字节由本进程读', async () => {
  const repo = await tempDir()
  await mkdir(join(repo, 'shared'), { recursive: true })
  await mkdir(join(repo, 'local'), { recursive: true })
  await writeFile(join(repo, 'shared', 'file.json'), 'local-version')
  await writeFile(join(repo, 'local', 'new.json'), 'brand-new')
  // P2P 的本机私有目录: 私钥与信任表绝不能进远端 (2026-10-22 的回归)
  await mkdir(join(repo, '.p2p'), { recursive: true })
  await writeFile(join(repo, '.p2p', 'node.key'), 'super-secret-key')
  await writeFile(join(repo, '.p2p', 'trust.json'), '{"v":1}')

  const localShared = Buffer.from('local-version')
  const localNew = Buffer.from('brand-new')
  let gitCalls = 0
  const postedBlobs = []
  let postedCommit = null
  const fetchImpl = async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha: 'r1' } })
    if (init?.method === 'GET' && path.includes('/git/commits/r1')) return response(200, { tree: { sha: 'remote-tree' }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes('/git/trees/remote-tree')) return response(200, { truncated: false, tree: [] })
    if (init?.method === 'POST' && path.endsWith('/git/blobs')) {
      const body = JSON.parse(init.body)
      postedBlobs.push(Buffer.from(body.content, 'base64').toString('utf8'))
      return response(201, { sha: blobShaOf(Buffer.from(body.content, 'base64')) })
    }
    if (init?.method === 'POST' && path.endsWith('/git/trees')) return response(201, { sha: 'tree-1' })
    if (init?.method === 'POST' && path.endsWith('/git/commits')) {
      postedCommit = JSON.parse(init.body)
      return response(201, { sha: 'c1' })
    }
    if (init?.method === 'PATCH' && path.includes('/git/refs/heads/main')) return response(200, { object: { sha: 'c1' } })
    return response(404, { message: `unexpected ${init?.method} ${path}` })
  }
  const execGit = async (args) => {
    gitCalls += 1
    throw gitFailure(1, `noGit must not run git ${args.join(' ')}`)
  }
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    fetchImpl,
    runGit: execGit,
  })
  const result = await api.push({ noGit: true })
  assert.equal(result.ok, true, JSON.stringify(result))
  assert.equal(result.uploaded, 2)
  assert.equal(gitCalls, 0, '手机这条路一条本地 git 都不该发')
  assert.deepEqual(postedBlobs.sort(), ['brand-new', 'local-version'].sort())
  assert.equal(postedBlobs.some(text => text.includes('super-secret-key')), false, '.p2p must never reach the remote')
  assert.deepEqual(postedCommit.parents, ['r1'])
  assert.equal(postedCommit.tree, 'tree-1')
  assert.equal(blobShaOf(localShared).length, 40)
  assert.equal(blobShaOf(localNew).length, 40)
})

test('mirror never carries sessions opened in a shadow workspace', async () => {
  const source = await tempDir()
  const repo = await tempDir()
  const normal = join(source, '--D-apk--', 'session-normal', 'session.v4.jsonl.zstd')
  const shadow = join(source, '--E-Mobile-AI-dsh-home-lw-peer-workspaces-abc-DSH--', 'session-shadow', 'session.v4.jsonl.zstd')
  await mkdir(join(source, '--D-apk--', 'session-normal'), { recursive: true })
  await mkdir(join(source, '--E-Mobile-AI-dsh-home-lw-peer-workspaces-abc-DSH--', 'session-shadow'), { recursive: true })
  await writeFile(normal, 'normal')
  await writeFile(shadow, 'shadow')
  const result = await mirrorSessionRoot({
    sessionRoot: source,
    repoDir: repo,
    mirrorDir: 'sessions',
    neverSyncShadowSessions: true,
  })
  assert.equal(result.mirrored, 1)
  assert.equal(result.neverSynced, 1)
  assert.equal(existsSync(join(repo, 'sessions', '--D-apk--', 'session-normal', 'session.v4.jsonl.zstd')), true)
  assert.equal(existsSync(join(repo, 'sessions', '--E-Mobile-AI-dsh-home-lw-peer-workspaces-abc-DSH--')), false)
})

test('adopt never brings a shadow-workspace session back into the local store', async () => {
  const repo = await tempDir()
  const target = await tempDir()
  const normal = join(repo, 'sessions', '--D-apk--', 'session-normal', 'session.v4.jsonl.zstd')
  const shadow = join(repo, 'sessions', '--E-Mobile-AI-dsh-home-lw-peer-workspaces-abc-DSH--', 'session-shadow', 'session.v4.jsonl.zstd')
  await mkdir(join(repo, 'sessions', '--D-apk--', 'session-normal'), { recursive: true })
  await mkdir(join(repo, 'sessions', '--E-Mobile-AI-dsh-home-lw-peer-workspaces-abc-DSH--', 'session-shadow'), { recursive: true })
  await writeFile(normal, 'normal')
  await writeFile(shadow, 'shadow')
  const result = await adoptMergedSessions({
    targetRoot: target,
    repoDir: repo,
    mirrorDir: 'sessions',
    skipShadowSessions: true,
  })
  assert.equal(result.brought, 1)
  assert.equal(existsSync(join(target, '--D-apk--', 'session-normal', 'session.v4.jsonl.zstd')), true)
  assert.equal(existsSync(join(target, '--E-Mobile-AI-dsh-home-lw-peer-workspaces-abc-DSH--')), false)

  // append-only 延伸: 镜像那一份是本地的前缀延伸时, 取回要跟着更新 (不是 bring-missing 到底)
  await writeFile(normal, 'normal-extended')
  const extended = await adoptMergedSessions({
    targetRoot: target,
    repoDir: repo,
    mirrorDir: 'sessions',
    skipShadowSessions: true,
  })
  assert.equal(extended.updated, 1)
  assert.equal(await readFile(join(target, '--D-apk--', 'session-normal', 'session.v4.jsonl.zstd'), 'utf8'), 'normal-extended')
})

/** 把一段字节按固定大小切成段, 造出与镜像同形状的段集合 (parts + index.json) */
function partsFixture(bytes, partSize) {
  const parts = []
  for (let at = 0; at < bytes.length; at += partSize) parts.push(bytes.subarray(at, Math.min(at + partSize, bytes.length)))
  const index = {
    v: 1,
    file: 'session.v4.jsonl.zstd',
    bytes: bytes.length,
    parts: parts.length,
    sha256: createHash('sha256').update(bytes).digest('hex'),
  }
  return { parts, index }
}

/** 每一段都不一样的字节 (同内容的段会被 blob 去重, 测试就量不到"推了几段") */
function distinctBytes(length, seed) {
  const out = Buffer.alloc(length)
  for (let at = 0; at < length; at += 1) out[at] = (seed + at * 7) % 251
  return out
}

/** 一棵假远端的树: 把段集合与普通文件都塞进去 (sha → 字节 也一并备好) */
function fakeRemoteTree(entries) {
  const tree = []
  const blobs = new Map()
  const add = (rel, bytes) => {
    const sha = blobShaOf(bytes)
    blobs.set(sha, bytes)
    tree.push({ path: rel, type: 'blob', mode: '100644', sha, size: bytes.length })
    return sha
  }
  const addSet = (dirRel, fixture) => {
    add(`${dirRel}/parts/index.json`, Buffer.from(`${JSON.stringify(fixture.index)}\n`))
    for (let at = 0; at < fixture.parts.length; at += 1) {
      add(`${dirRel}/parts/${String(at).padStart(4, '0')}.bin`, fixture.parts[at])
    }
  }
  for (const entry of entries) {
    if (entry.kind === 'set') addSet(entry.dirRel, entry.fixture)
    else add(entry.rel, entry.bytes)
  }
  return { tree, blobs }
}

/** 按一棵假远端的树造 fetchImpl (GET 只有 ref / commit / tree / blob 四种) */
function remoteFetcher({ sha, treeSha, tree, blobs, onTreePost }) {
  return async (url, init) => {
    const parsed = new URL(url)
    const path = `${parsed.pathname}${parsed.search}`
    if (init?.method === 'GET' && path.includes('/git/ref/heads/main')) return response(200, { object: { sha } })
    if (init?.method === 'GET' && path.includes(`/git/commits/${sha}`)) return response(200, { tree: { sha: treeSha }, committer: { date: new Date().toISOString() } })
    if (init?.method === 'GET' && path.includes(`/git/trees/${treeSha}`)) return response(200, { truncated: false, tree })
    if (init?.method === 'GET' && path.includes('/git/blobs/')) {
      const hit = blobs.get(path.split('/git/blobs/')[1])
      if (hit === undefined) return response(404, { message: 'no such blob' })
      return response(200, { content: hit.toString('base64'), encoding: 'base64' })
    }
    if (init?.method === 'POST' && path.endsWith('/git/blobs')) {
      const body = JSON.parse(init.body)
      const bytes = Buffer.from(String(body.content), body.encoding === 'base64' ? 'base64' : 'utf8')
      blobs.set(blobShaOf(bytes), bytes)
      return response(201, { sha: blobShaOf(bytes) })
    }
    if (init?.method === 'POST' && path.endsWith('/git/trees')) {
      const body = JSON.parse(init.body)
      if (typeof onTreePost === 'function') onTreePost(body.tree)
      return response(201, { sha: `tree-${Math.random().toString(16).slice(2, 10)}` })
    }
    if (init?.method === 'POST' && path.endsWith('/git/commits')) return response(201, { sha: 'c-new' })
    if (init?.method === 'PATCH' && path.includes('/git/refs/heads/main')) return response(200, { object: { sha: 'c-new' } })
    return response(404, { message: `unexpected ${init?.method} ${path}` })
  }
}

/** 整组落在预算里: 一趟只搬装得下的那几组, 下一趟接着搬 (2026-10-22) */
test('a pull lands whole parts sets under a per-trip budget and resumes on the next trip', async () => {
  const repo = await tempDir()
  const smallBytes = Buffer.from('s'.repeat(200))
  const bigBytes = Buffer.from('b'.repeat(400))
  const small = partsFixture(smallBytes, 100)
  const big = partsFixture(bigBytes, 100)
  const remote = fakeRemoteTree([
    { kind: 'set', dirRel: 'sessions/p/big', fixture: big },
    { kind: 'set', dirRel: 'sessions/p/small', fixture: small },
    { rel: 'inbox/pt/m1.json', bytes: Buffer.from('{"v":1}') },
  ])
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    fetchImpl: remoteFetcher({ sha: 'r1', treeSha: 't1', tree: remote.tree, blobs: remote.blobs }),
    runGit: async (args) => {
      if (args[0] === 'rev-parse') throw gitFailure(128, 'fatal: Needed a single revision')
      if (args[0] === 'add' || args[0] === 'commit') return { stdout: '', stderr: '' }
      throw gitFailure(1, `unexpected git ${args.join(' ')}`)
    },
    // 预算压到 2 段: 小组 (2 段) 整组落, 大组 (4 段) 整组留
    partsBudget: { parts: 2 },
  })

  const first = await api.pull()
  assert.equal(first.ok, true, JSON.stringify(first))
  assert.equal(first.written, 1, 'inbox 那几句先落, 不被段拖住')
  assert.equal(first.partSetsTaken, 1)
  assert.equal(first.partSetsDeferred, 1)
  assert.equal(existsSync(join(repo, 'sessions/p/small/parts/index.json')), true)
  // 大组一个字节都没落 (整组留, 不是落一半)
  assert.equal(existsSync(join(repo, 'sessions/p/big/parts')), false)
  assert.deepEqual((await assembleParts(join(repo, 'sessions/p/small/parts'))).bytes, smallBytes)

  // 有段延后时**不记 head 缓存**, 于是同一个 head 的下一趟照样继续搬
  const second = await api.pull()
  assert.equal(second.ok, true, JSON.stringify(second))
  assert.equal(second.partSetsTaken, 1)
  assert.equal(second.partSetsDeferred, 0)
  assert.deepEqual((await assembleParts(join(repo, 'sessions/p/big/parts'))).bytes, bigBytes)
  // 两组的 sha256 都由 assembleParts 核对过 (对不上它回 null)
  assert.ok(second.partsFetched >= 1)
})

/** 本地那组更长: 推送按整组覆盖, 远端多出来的段进删除清单 (2026-10-22) */
test('a push keeps the longer parts set as one unit and deletes the remote extra parts', async () => {
  const repo = await tempDir()
  const localBytes = distinctBytes(300, 3)
  const remoteBytes = distinctBytes(250, 41)
  const local = partsFixture(localBytes, 100)   // 3 段
  const remote = partsFixture(remoteBytes, 50)  // 5 段 (总长更短)
  const remoteTree = fakeRemoteTree([{ kind: 'set', dirRel: 'sessions/p/s1', fixture: remote }])
  const posted = []
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    fetchImpl: remoteFetcher({
      sha: 'r1',
      treeSha: 't1',
      tree: remoteTree.tree,
      blobs: remoteTree.blobs,
      onTreePost: (entries) => posted.push(...entries.map(entry => entry.path)),
    }),
    runGit: async (args) => { throw gitFailure(1, `noGit must not run git ${args.join(' ')}`) },
  })
  // 本机工作树: 一组 3 段 (比远端那一组更长)
  const partsDir = join(repo, 'sessions', 'p', 's1', 'parts')
  await mkdir(partsDir, { recursive: true })
  await writeFile(join(partsDir, 'index.json'), `${JSON.stringify(local.index)}\n`)
  for (let at = 0; at < local.parts.length; at += 1) await writeFile(join(partsDir, `${String(at).padStart(4, '0')}.bin`), local.parts[at])

  const result = await api.push({ noGit: true })
  assert.equal(result.ok, true, JSON.stringify(result))
  assert.equal(result.pushed, true)
  assert.equal(result.uploadedParts, 3, '本机那 3 段是新 blob, 照推')  // 远端多出来的 0003/0004 不进新树 (整组删除), 本机那份索引与 3 段都在
  for (const name of ['0000.bin', '0001.bin', '0002.bin', 'index.json']) assert.ok(posted.includes(name), `${name} 应该在新树里: ${posted.join(',')}`)
  for (const name of ['0003.bin', '0004.bin']) assert.ok(!posted.includes(name), `${name} 不该出现在新树里: ${posted.join(',')}`)
})

/** 远端那组更长: 本机这组整组不上传, 推送退化成"什么都不用做" (2026-10-22) */
test('a push leaves a longer remote parts set alone instead of blending two lineages', async () => {
  const repo = await tempDir()
  const local = partsFixture(distinctBytes(300, 7), 100)    // 3 段
  const remote = partsFixture(distinctBytes(500, 19), 100)   // 5 段
  const remoteTree = fakeRemoteTree([{ kind: 'set', dirRel: 'sessions/p/s1', fixture: remote }])
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    fetchImpl: remoteFetcher({ sha: 'r1', treeSha: 't1', tree: remoteTree.tree, blobs: remoteTree.blobs }),
    runGit: async (args) => { throw gitFailure(1, `noGit must not run git ${args.join(' ')}`) },
  })
  const partsDir = join(repo, 'sessions', 'p', 's1', 'parts')
  await mkdir(partsDir, { recursive: true })
  await writeFile(join(partsDir, 'index.json'), `${JSON.stringify(local.index)}\n`)
  for (let at = 0; at < local.parts.length; at += 1) await writeFile(join(partsDir, `${String(at).padStart(4, '0')}.bin`), local.parts[at])

  const result = await api.push({ noGit: true })
  assert.equal(result.ok, true, JSON.stringify(result))
  assert.equal(result.uploadedParts, 0, '输掉的那组一个段都不该上传')
  assert.equal(result.uploaded, 0, '本机这组一个 blob 都不该上传 (索引也算)')
  assert.equal(result.files, remoteTree.blobs.size, '新树就是远端那一组 (5 段 + 索引)')
})

test('mergeTreeEntries takes a per-path predicate so parts sets keep their whole-set winner', () => {
  const merged = mergeTreeEntries(
    new Map([['sessions/p/s/parts/0000.bin', { mode: '100644', sha: 'remote-big', size: 900 }]]),
    new Map([['sessions/p/s/parts/0000.bin', { mode: '100644', sha: 'local', size: 100 }]]),
    [],
    { preferRemoteLargerSessions: rel => rel.startsWith('sessions/') && !rel.includes('/parts/') },
  )
  // 段集合的条目按整组胜负走: 谓词说"不偏袒远端", 这条就是本机的
  assert.equal(merged.entries.get('sessions/p/s/parts/0000.bin').sha, 'local')
  const flat = mergeTreeEntries(
    new Map([['sessions/p/s/session.v4.jsonl.zstd', { mode: '100644', sha: 'remote-big', size: 900 }]]),
    new Map([['sessions/p/s/session.v4.jsonl.zstd', { mode: '100644', sha: 'local', size: 100 }]]),
    [],
    { preferRemoteLargerSessions: rel => rel.startsWith('sessions/') && !rel.includes('/parts/') },
  )
  // 扁平会话文件仍按"更长者胜"
  assert.equal(flat.entries.get('sessions/p/s/session.v4.jsonl.zstd').sha, 'remote-big')
})

test('a push uploads our own transcript but never the peer copy lying in the worktree', async () => {
  const repo = await tempDir()
  // 本机工作树里两本账: 对端那本是上一趟拉下来的快照 (可能已经旧了), 本机那本才是我们写的
  await mkdir(join(repo, 'conv', 'dev-peer', 's1'), { recursive: true })
  await writeFile(join(repo, 'conv', 'dev-peer', 's1', 'meta.json'), '{"v":1,"entries":1,"chunks":0}')
  await mkdir(join(repo, 'conv', 'dev-me', 's1'), { recursive: true })
  await writeFile(join(repo, 'conv', 'dev-me', 's1', 'meta.json'), '{"v":1,"entries":1,"chunks":0}')
  const remote = fakeRemoteTree([])
  const api = makeGithubApi({
    remote: 'https://github.com/o/r.git',
    token: 't',
    branch: 'main',
    repoDir: repo,
    ownDeviceId: 'dev-me',
    fetchImpl: remoteFetcher({ sha: 'r1', treeSha: 't1', tree: remote.tree, blobs: remote.blobs }),
    runGit: async (args) => { throw gitFailure(1, `noGit must not run git ${args.join(' ')}`) },
  })
  const result = await api.push({ noGit: true })
  assert.equal(result.ok, true, JSON.stringify(result))
  // 只有本机那一本进了新树 (对端那本由它的主人推, 我们手里那份可能是旧的)
  assert.equal(result.files, 1, `只有本机那本: ${JSON.stringify(result)}`)
  assert.equal(result.uploaded, 1)
})

test('派生文件按"谁写的"单边胜出: 本机那份不动, 对端那份远端为准, 都不 fork', () => {
  // 本机自己写的: 拉取时保持本机 (否则我们刚写的卡会被对端的旧快照盖回去)
  assert.equal(ownershipOfPath('workspace-peers/dev-me.json', 'dev-me'), 'local')
  assert.equal(ownershipOfPath('live/dev-me/session-a.json', 'dev-me'), 'local')
  assert.equal(ownershipOfPath('device.txt', 'dev-me'), 'local')
  // 对端自己写的: 远端为准 (旧快照没有保留价值)
  assert.equal(ownershipOfPath('workspace-peers/dev-peer.json', 'dev-me'), 'remote')
  assert.equal(ownershipOfPath('live/dev-peer/session-a.json', 'dev-me'), 'remote')
  // 回执目录按寄件方 id: 本机 id 那一份是对端写的 → 远端为准; 对端 id 那一份是本机写的 → 本机为准
  assert.equal(ownershipOfPath('inbox-ack/dev-me/m1.json', 'dev-me'), 'remote')
  assert.equal(ownershipOfPath('inbox-ack/dev-peer/m1.json', 'dev-me'), 'local')
  // 普通文件 (会话 / 工作区 / 收件箱) 仍走 keep-both 那一套
  assert.equal(ownershipOfPath('sessions/--D-apk--/session-a/session.v4.jsonl.zstd', 'dev-me'), '')
  assert.equal(ownershipOfPath('inbox/dev-peer/m1.json', 'dev-me'), '')
  // 不知道本机 id 时不乱判
  assert.equal(ownershipOfPath('live/dev-peer/session-a.json', ''), 'remote')
  assert.equal(ownershipOfPath('', 'dev-me'), '')
})
