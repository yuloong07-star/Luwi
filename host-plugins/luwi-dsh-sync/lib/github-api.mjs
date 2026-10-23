// luwi-dsh-sync: 只用 api.github.com 的传输层 (2026-10-20 主人要求, 2026-10-20 晚重修)
//
// 为什么要有它: 这台 PC 上 `github.com:443` 时通时不通 (git 的 smart-HTTP 走的就是它), 而
// `api.github.com` 一直通。于是 push/pull 多一条路: 用 REST API 直接建 blob/tree/commit 并挪
// ref, 一个字节都不碰 github.com 的那条口
//
// **它替换的只是"网络那一段", 合并语义要与 git 那条对齐**。2026-10-20 晚真机上量出来的三条
// 缺口都在这里 (对话不同步的直接原因):
//
// 1. push 以**工作树**为准, 不是 HEAD。原先先 `git commit` 再取 `ls-tree HEAD`, 而 commit 一旦
//    失败 (index.lock / 并发) 就被 catch 掉、继续推旧 HEAD —— 于是刚镜像出来的会话根本没上去。
//    现在 `git add -A` + `git write-tree` 直接拿工作树快照, commit 只是本地记账 (失败不影响推)
// 2. push 是**并集**, 不是覆盖。远端已有、本机没有的文件 (另一台设备的会话 / live 快照) 必须
//    保留; 只有"本机相对自己的 HEAD 明确删掉"的路径才从远端删 (首次配对的清理)
// 3. pull **不删本地文件**。远端没有的路径不代表"应该删", 原先那段会每趟把本机独有的会话与
//    fork 文件从工作树里删掉, 下一趟镜像再补回来, 来回抖; 大文件也不再有 512 KiB 上限 ——
//    会话日志动辄几 MB, 跳过它就等于"对话永远到不了"
//
// 另外: 同一个远端 head 拉过一次就不再重复物化 (lastPulled), 免得一分钟一趟刷屏; 拉回来之后
// 的"取回本地 sessionRoot"由 engine.finishExternalPull 补 (这一份只管工作树)
import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'
import dns from 'node:dns'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { readdir, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { promisify } from 'node:util'
import {
  PARTS_INDEX,
  inspectPartsSet,
  isPartFileRel,
  isPartsIndexRel,
  partsDirRelOf,
  partsSetNames,
  readPartsIndex,
} from './luwi-parts.mjs'
import { safeSegment } from './luwi-inbox.mjs'

// 手机上 Node 的默认解析顺序是 "verbatim", 而这张卡/这个网络给 api.github.com 的 AAAA 记录
// 走不通 —— `fetch` 会一直挂到超时, 而同一个网络的 wget 走 IPv4 却是好的 (2026-10-20 晚量到)。
// 只改手机 (PC 上没必要动别的插件的解析顺序)
if (process.platform === 'android') {
  try {
    dns.setDefaultResultOrder('ipv4first')
  } catch {
    // 老 Node 没有这个 API: 保持默认, 退路仍然会在超时后重试
  }
}

const runFile = promisify(execFile)

/** 一次 API 调用的体上限 (GitHub 单 blob 上限 100 MB, 这里只做保护) */
const MAX_BLOB_BYTES = 90 * 1024 * 1024

/** 同一个 head 拉过之后, 短时间内不再重复 (只在进程内, 重启后拉一次) */
const PULL_HEAD_CACHE_MS = 6 * 60 * 60 * 1000

/**
 * 每趟 pull 最多搬多少段 (2026-10-22)。
 *
 * 段是**整组搬**的 (组内要么全落、要么一个字节都不动), 所以这个数管的是"一趟最多搬完几组":
 * 首次遇到一场 16 MB 的会话是 64 段, 一趟全下会把队列里那几句话压到后面, 也让一趟很容易撞上
 * 240 s 的拉取截止。组按大小从小到大排, 装不下的整组留到下一趟 —— 已经下过的段按 sha 跳过,
 * 于是"续传"不需要任何额外记账
 */
const PARTS_FETCH_COUNT_PER_PULL = 32

/** 每趟 pull 最多搬多少字节的段 (4 MiB); 单组比它还大就单独占一趟 */
const PARTS_FETCH_BYTES_PER_PULL = 4 * 1024 * 1024

/**
 * 把一棵树里的段路径归成"段集合" —— 整组落或整组留的单位。
 *
 * 只有带 `index.json` 的目录才算集合: 段离开索引没有意义, 没有索引的段由取回端整片跳过
 * (见 lib/luwi-adopt.mjs), 这里也就没有可决策的东西
 * @param {Map<string, {sha: string, size?: number}>} blobs - 路径 → blob。
 * @returns {Map<string, {dirRel: string, indexRel: string, indexSha: string, parts: Map<string, {sha: string, size: number}>, bytes: number}>} 段目录 → 集合。
 */
export function groupPartsSets(blobs) {
  const sets = new Map()
  for (const [rel, entry] of blobs ?? []) {
    const dirRel = partsDirRelOf(rel)
    if (dirRel === null) continue
    let set = sets.get(dirRel)
    if (set === undefined) {
      set = { dirRel, indexRel: `${dirRel}/${PARTS_INDEX}`, indexSha: '', parts: new Map(), bytes: 0 }
      sets.set(dirRel, set)
    }
    if (isPartsIndexRel(rel)) {
      set.indexRel = rel
      set.indexSha = String(entry?.sha ?? '')
      continue
    }
    const name = rel.slice(dirRel.length + 1)
    const size = Number(entry?.size ?? 0)
    set.parts.set(name, { sha: String(entry?.sha ?? ''), size })
    set.bytes += size
  }
  for (const dirRel of [...sets.keys()]) {
    if (sets.get(dirRel).indexSha === '') sets.delete(dirRel)
  }
  return sets
}

/**
 * 会话目录里那份扁平会话文件 (不算 fork, 不算子目录); 没有回 null。
 * @param {string} parentRel - 会话目录 (段目录的上一级)。
 * @param {Map<string, unknown>|undefined} blobs - 一棵树的路径表。
 * @returns {string|null} 相对路径。
 */
function flatSessionRelOf(parentRel, blobs) {
  const prefix = `${parentRel}/`
  for (const rel of blobs?.keys() ?? []) {
    if (!rel.startsWith(prefix)) continue
    const rest = rel.slice(prefix.length)
    if (rest === '' || rest.includes('/')) continue
    if (isForkPath(rest)) continue
    return rel
  }
  return null
}

/**
 * 段目录的上一级 (会话目录) 里那份扁平会话文件有多少字节 (盘上); 没有回 0。
 *
 * 用来和段集合比"谁更完整" —— 布局切换的两种方向 (扁平 → 切段 / 切段 → 扁平) 都靠这个数
 * @param {string} partsDirAbs - 段目录绝对路径。
 * @returns {number} 字节数。
 */
function localFlatBytes(partsDirAbs) {
  const dir = dirname(partsDirAbs)
  let biggest = 0
  try {
    for (const entry of readdirSyncSafe(dir)) {
      if (!entry.isFile() || isForkPath(entry.name)) continue
      const size = statSync(join(dir, entry.name)).size
      if (size > biggest) biggest = size
    }
  } catch {
    // 没有就是 0
  }
  return biggest
}

/** readdir 的同步版 (只在盘上量大小用); 失败回空表 */
function readdirSyncSafe(dir) {
  try {
    return readdirSync(dir, { withFileTypes: true })
  } catch {
    return []
  }
}

/**
 * 判断一条错误像不像"网络不通" —— 只有这一档才值得退到 API 传输。
 *
 * **手机那条桥的超时也要算**: 沙盒里的 git 在 120 s 被整组杀掉时, 桥回的是
 * `the sandbox never reported an exit code: … did not finish within 120000ms …`,
 * 它一个字都不含 git 自己的网络措辞。2026-10-20 手机上量到的就是这一句, 而原先的判据
 * 认不出它, 于是 API 退路一次都没跑过
 * @param {unknown} error - 错误或错误消息。
 * @returns {boolean} 该不该退。
 */
export function looksLikeNetworkFailure(error) {
  const text = String(error?.message ?? error ?? '')
  return /unable to access|Failed to connect|Could not connect|Could not resolve host|Connection (?:refused|reset|timed out)|Connection was reset|Recv failure|send failure|Operation timed out|network is unreachable|TLS handshake timeout|socket hang up|ECONNRESET|ECONNREFUSED|ETIMEDOUT|ENETUNREACH|EAI_AGAIN|early EOF|unexpected eof|unexpected disconnect|fetch failed|RPC failed|remote end hung up|GnuTLS|OpenSSL|LibreSSL|schannel|SSL[_ ]|TLS[_ ]|HTTP 5\d\d|HTTP 429|did not finish within \d+ms|never reported an exit code|the app did not answer the sandbox call|git network skipped/iu.test(text)
}

/**
 * git blob 的 SHA-1 (与 git hash-object 同口径, 不启子进程)。
 * @param {Buffer|string} content - 文件字节。
 * @returns {string} 40 位十六进制。
 */
export function blobShaOf(content) {
  const bytes = Buffer.isBuffer(content) ? content : Buffer.from(content)
  return createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex')
}

/**
 * 取一个 blob 并核对 sha: 手机那条网络上大响应偶尔会被截断, 而 undici 仍回 200 (正文是半截
 * JSON), 不校验就会落 0 字节 / 半截会话。最多试三次, 每次都重新核对
 * @param {(method: string, path: string, body?: unknown) => Promise<any>} call - API 调用。
 * @param {string} sha - 期望的 blob sha。
 * @returns {Promise<Buffer>} 核对过的字节。
 */
async function fetchBlobVerified(call, sha) {
  let lastBlobError = null
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (attempt > 0) await new Promise(resolve => setTimeout(resolve, 1200 * attempt))
    try {
      const blob = await call('GET', `/git/blobs/${sha}`)
      const candidate = Buffer.from(String(blob?.content ?? ''), blob?.encoding === 'base64' ? 'base64' : 'utf8')
      if (blobShaOf(candidate) !== sha) {
        lastBlobError = new Error(`blob ${sha.slice(0, 8)} came back corrupt (${candidate.length} bytes, sha ${blobShaOf(candidate).slice(0, 8)})`)
        continue
      }
      return candidate
    } catch (error) {
      lastBlobError = error
    }
  }
  throw lastBlobError ?? new Error(`could not fetch blob ${sha.slice(0, 8)}`)
}

/**
 * 派生文件"谁写的": 决定拉取时该不该 keep-both。
 *
 * `device.txt` / `live/<id>/` / `workspace-peers/<id>.json` / `inbox-ack/<id>/` 都是**某一侧每轮
 * 重写**的派生文件。两边内容一旦不同, 普通的 keep-both 会各留一个 fork, 而且永远收敛不了。
 * @param {string} rel - 工作树里的相对路径 (POSIX)。
 * @param {string} ownId - 本机设备 id (未知时空串)。
 * @returns {'local'|'remote'|''} `local` = 本机那份为准; `remote` = 远端为准; 空串 = 走通用判定。
 */
export function ownershipOfPath(rel, ownId) {
  if (typeof rel !== 'string' || rel === '') return ''
  const owner = typeof ownId === 'string' ? ownId : ''
  if (rel === 'device.txt') return 'local'
  if (rel.startsWith('live/')) {
    return owner !== '' && rel.split('/')[1] === owner ? 'local' : 'remote'
  }
  if (rel.startsWith('workspace-peers/')) {
    return owner !== '' && rel.endsWith(`${owner}.json`) ? 'local' : 'remote'
  }
  if (rel.startsWith('inbox-ack/')) {
    // 回执按"寄件方 id"分目录: 目录名是本机 id 时那份是**对端**写的, 以远端为准
    return owner !== '' && rel.split('/')[1] === owner ? 'remote' : 'local'
  }
  if (rel.startsWith('conv/')) {
    // 对话账 (2026-10-22): 每台设备只写自己那一本, 对端那本只读 —— 两边都写只会在两端各留
    // 一个永远收敛不了的 fork (与 live/ 同一条理由)
    return owner !== '' && rel.split('/')[1] === owner ? 'local' : 'remote'
  }
  return ''
}

/**
 * 是不是一个 fork 文件 (`<名字>.remote-fork-<14 位时间戳>-<设备>`)。这类文件是冲突留存,
 * 只属于产生它的那台机器: 参与同步只会在对端复活并再被拉回来。
 * @param {string} rel - 工作树相对路径。
 * @returns {boolean} 是 fork 文件名就 true。
 */
export function isForkPath(rel) {
  return typeof rel === 'string' && /\.remote-fork-\d{14}-[^/]+$/u.test(rel)
}

/**
 * 远端这个文件该不该落到本地 / 怎么落 (纯函数, 单测钉着)。
 *
 * `localHeadSha` 是本地 HEAD 里同一路径的 blob; 它是判断"本地相对基点改没改"的唯一依据:
 * - 本地没有 → 写
 * - 两边字节一样 → 跳过
 * - 远端 = 本地 HEAD, 而工作树改了 → 本地在基点上继续改, 保留本地 (远端那份就是基点)
 * - 远端 ≠ 本地 HEAD, 工作树没改 → 干净更新, 写
 * - 远端 ≠ 本地 HEAD, 工作树也改了 → 两边都动过, keep-both (写 fork)
 * - 本地没有基点 (untracked): 比 mtime, 本机更晚就保留, 否则 fork
 * @param {object} state - 判定所需的状态。
 * @param {boolean} state.localExists - 本地文件存在吗。
 * @param {string|undefined} state.localSha - 本地文件现在的 blob。
 * @param {string|undefined} state.localHeadSha - 本地 HEAD 里同路径的 blob (没有基点时 undefined)。
 * @param {string} state.remoteSha - 远端 blob。
 * @param {number} state.localMtimeMs - 本地 mtime。
 * @param {number} state.remoteAt - 远端提交时刻。
 * @returns {'skip'|'write'|'fork'|'keepLocal'} 动作。
 */
export function decidePullAction(state) {
  const { localExists, localSha, localHeadSha, remoteSha, localMtimeMs, remoteAt } = state
  if (!localExists) return 'write'
  if (localSha !== undefined && localSha === remoteSha) return 'skip'
  if (localHeadSha === remoteSha) return 'keepLocal'
  if (localHeadSha !== undefined) {
    if (localSha !== undefined && localSha === localHeadSha) return 'write'
    return 'fork'
  }
  if (Number.isFinite(localMtimeMs) && Number.isFinite(remoteAt) && remoteAt > 0 && localMtimeMs > remoteAt) {
    return 'keepLocal'
  }
  return 'fork'
}

/**
 * 把远端树与本机树并起来 (纯函数): 远端已有的保留, 本机的覆盖同名, 再应用本机明确的删除。
 * 这样另一台设备推上来的文件不会因为本机还没拉过就被这次 push 抹掉。
 *
 * **会话日志例外**: 同名时远端的 blob 更大就保留远端 —— 手机取回 PC 会话后本地那一份是旧
 * 快照, 它再 push 时按"本机覆盖"会把远端更完整的版本缩回去 (2026-10-21 真机上量到)
 * @param {Map<string, {mode: string, sha: string, size?: number}>} remoteBlobs - 远端 path → blob。
 * @param {Map<string, {mode: string, sha: string, size?: number}>} localBlobs - 本机 path → blob。
 * @param {Iterable<string>} [deletions] - 本机相对 HEAD 明确删掉的路径。
 * @param {{preferRemoteLargerSessions?: boolean|((rel: string) => boolean)}} [options] - 会话日志同名时是否保留更大的远端版本: 缺省 true (所有 `sessions/`), 也可以给一个逐路径的判定函数 —— 段集合的条目按"整组胜负"走, 传函数时把它们排除掉。
 * @returns {{entries: Map<string, {mode: string, sha: string}>, added: number, changed: number, removed: number}} 并集。
 */
export function mergeTreeEntries(remoteBlobs, localBlobs, deletions = [], options = {}) {
  const prefer = options.preferRemoteLargerSessions ?? true
  const preferRemoteLarger = typeof prefer === 'function'
    ? prefer
    : rel => prefer === true && rel.startsWith('sessions/')
  const entries = new Map()
  for (const [rel, entry] of remoteBlobs ?? []) entries.set(rel, entry)
  let added = 0
  let changed = 0
  for (const [rel, entry] of localBlobs ?? []) {
    const before = entries.get(rel)
    if (before !== undefined
      && preferRemoteLarger(rel)
      && Number(before.size ?? 0) > Number(entry.size ?? 0)) {
      // 本地是远端会话的旧快照: 不覆盖, 让远端保持更长的那一份
      continue
    }
    if (before === undefined) added += 1
    else if (before.sha !== entry.sha) changed += 1
    entries.set(rel, entry)
  }
  let removed = 0
  for (const rel of deletions ?? []) {
    if (entries.delete(rel)) removed += 1
  }
  return { entries, added, changed, removed }
}

/**
 * 建 API 传输层。
 * @param {object} deps 注入面。
 * @param {string} deps.remote - 仓库地址 (可以带令牌, 这里只取 owner/repo)。
 * @param {string} deps.token - GitHub 令牌 (PAT)。
 * @param {string} deps.branch - 分支名。
 * @param {string} deps.repoDir - 本地工作树根 (git 仓)。
 * @param {string} deps.gitBin - git 可执行文件。
 * @param {string} [deps.deviceShort] - fork 名里的设备尾标。
 * @param {(message: string) => void} [deps.warn] - 降级日志。
 * @param {Function} [deps.fetchImpl] - fetch (单测注入)。
 * @param {Function} [deps.runGit] - git runner (与 GitBackend 同一个契约; 手机侧由桥进沙盒)。
 * @param {Function} [deps.clearStaleLock] - 陈旧 index.lock 清理 (单测注入; 缺省不做)。
 * @returns {{enabled: boolean, push: Function, pull: Function, head: Function}} 句柄。
 */
export function makeGithubApi(deps) {
  const rawRemote = String(deps.remote ?? '')
  // 令牌可以单独给, 也可以藏在地址里 (`https://user:token@github.com/owner/repo`) —— 后者是
  // composeRemoteWithToken 的产物, 插件传给 git 的就是它, 所以这条兜底很重要
  const fromUrl = /^https?:\/\/[^/@]+:([^/@]+)@/u.exec(rawRemote)?.[1] ?? ''
  const token = typeof deps.token === 'string' && deps.token.trim() !== '' ? deps.token.trim() : fromUrl
  const branch = typeof deps.branch === 'string' && deps.branch !== '' ? deps.branch : 'main'
  const gitBin = typeof deps.gitBin === 'string' && deps.gitBin !== '' ? deps.gitBin : 'git'
  const repoDir = String(deps.repoDir ?? '')
  // 本机设备 id 由调用方给 (元数据域里有); 拿不到时退回读工作树里的 device.txt
  const ownDeviceId = typeof deps.ownDeviceId === 'function'
    ? deps.ownDeviceId
    : () => (typeof deps.ownDeviceId === 'string' ? deps.ownDeviceId : '')
  const deviceShort = typeof deps.deviceShort === 'string' && deps.deviceShort !== '' ? deps.deviceShort : 'device'
  const warn = typeof deps.warn === 'function' ? deps.warn : () => {}
  /**
   * 每趟 pull 的段预算 (`{parts, bytes}`)。缺省就是上面那两个常数 —— 测试与"小预算演练"
   * 可以压小, 压小只影响一趟搬多少组, 不改协议 (组内永远整组搬)
   */
  const partsBudget = {
    parts: Number.isFinite(deps.partsBudget?.parts) && deps.partsBudget.parts > 0
      ? Number(deps.partsBudget.parts)
      : PARTS_FETCH_COUNT_PER_PULL,
    bytes: Number.isFinite(deps.partsBudget?.bytes) && deps.partsBudget.bytes > 0
      ? Number(deps.partsBudget.bytes)
      : PARTS_FETCH_BYTES_PER_PULL,
  }
  const fetchImpl = typeof deps.fetchImpl === 'function' ? deps.fetchImpl : globalThis.fetch
  /**
   * 默认的 git runner: 本机直接起进程 (PC)。手机侧传的是桥 runner (进 Linux 沙盒), 两者契约
   * 相同 —— **绝不能在这里直接 spawn `git`**, 安卓宿主里没有可 exec 的 git
   */
  const defaultRunGit = async (args, opts = {}) => {
    try {
      const { stdout, stderr } = await runFile(gitBin, ['-C', repoDir, ...args], {
        maxBuffer: 256 * 1024 * 1024,
        encoding: opts.binary === true ? 'buffer' : undefined,
      })
      return { code: 0, stdout, stderr }
    } catch (error) {
      return {
        code: typeof error?.code === 'number' ? error.code : 1,
        stdout: error?.stdout ?? (opts.binary === true ? Buffer.alloc(0) : ''),
        stderr: String(error?.stderr ?? error?.message ?? error),
      }
    }
  }
  const runGit = typeof deps.runGit === 'function' ? deps.runGit : defaultRunGit
  const clearStaleLock = typeof deps.clearStaleLock === 'function' ? deps.clearStaleLock : async () => false
  const ownerRepo = rawRemote
    .replace(/^https?:\/\/[^/]+\//u, '')
    .replace(/\.git$/u, '')
  const enabled = token !== '' && /^[^/]+\/[^/]+$/u.test(ownerRepo)

  const apiBase = `https://api.github.com/repos/${ownerRepo}`
  const headers = {
    authorization: `Bearer ${token}`,
    accept: 'application/vnd.github+json',
    'content-type': 'application/json',
    'user-agent': 'luwi-dsh-sync',
  }

  /** 上一次成功拉/推过的远端 head (省掉同一棵树的重复物化) */
  let lastPulled = null

  /**
   * 一次 REST 调用 (非 2xx 直接抛, 带上正文)。
   *
   * **网络层重试**: 这条路上 `api.github.com` 偶发 `fetch failed` / `Connection was reset`
   * (本机到 GitHub 的边缘时好时坏, 2026-10-20 一晚量到多次)。这种失败重试就会过, 所以这里
   * 自带 4 次退避 (1.5 s / 3 s / 6 s); HTTP 4xx/5xx 是另一端明确的拒绝, **不重试**。
   * @param {'GET'|'POST'|'PATCH'} method - 方法。
   * @param {string} path - 以 / 开头的路径。
   * @param {object} [body] - JSON 体。
   * @returns {Promise<any>} 解析后的响应。
   */
  async function call(method, path, body) {
    let lastError
    // 2026-10-22: 重试 5 次 × 每次 180 s 的最坏情况是十五分钟后才失败, 而这十几分钟里
    // 引擎的串行锁一直被这一趟占着 —— 手机侧表现为"pull 停了十分钟、后面的消息全排队"。
    // 现在每次 60 s、最多 3 次: 慢网络上 2 MB 级的 blob 仍读得完, 卡住的话两分钟内放手
    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (attempt > 0) await new Promise(resolve => setTimeout(resolve, 1500 * 2 ** (attempt - 1)))
      try {
        const response = await fetchImpl(`${apiBase}${path}`, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          // blob 那一对接口走的是大正文 (手机那条网络上 2 MB 要 90 s 左右), 给 180 s;
          // 其余元数据调用 (头/树/提交/改 ref) 45 s 足够, 卡住时也不能一直占着引擎的串行锁
          signal: AbortSignal.timeout(path.includes('/git/blobs') ? 180_000 : 45_000),
        })
        // 读正文也要在 try 里: 断线是在 `response.text()` 那一句上抛的 (`TypeError: terminated`),
        // 只包住 fetch 的话这种最常见的失败一次都不会重试 (2026-10-20 真机上量到的)
        const text = await response.text()
        let parsed = null
        try {
          parsed = text === '' ? null : JSON.parse(text)
        } catch {
          parsed = text
        }
        if (!response.ok) {
          const shown = typeof parsed === 'string' ? parsed : JSON.stringify(parsed)
          const refused = new Error(`api ${method} ${path} -> ${response.status} ${String(shown).slice(0, 300)}`)
          // 4xx/5xx 是另一端明确的拒绝: 不重试
          refused.final = true
          throw refused
        }
        return parsed
      } catch (error) {
        if (error?.final === true) throw error
        // 连不上 / 被重置 / 读到一半断了 / 超时: 值得再试
        lastError = error
      }
    }
    throw new Error(`api ${method} ${path} unreachable after 5 attempt(s): ${String(lastError?.message ?? lastError)}`)
  }

  /** 跑一条本地 git 命令 (不带网络), 非零也回结果而不是抛 */
  async function gitTry(args, opts = {}) {
    try {
      const result = await runGit(args, {
        cwd: repoDir,
        binary: opts.encoding === 'buffer',
        timeoutMs: 120_000,
      })
      return {
        code: Number.isFinite(result?.code) ? result.code : 0,
        stdout: result?.stdout ?? (opts.encoding === 'buffer' ? Buffer.alloc(0) : ''),
        stderr: String(result?.stderr ?? ''),
      }
    } catch (error) {
      return {
        code: typeof error?.code === 'number' ? error.code : 1,
        stdout: error?.stdout ?? (opts.encoding === 'buffer' ? Buffer.alloc(0) : ''),
        stderr: String(error?.stderr ?? error?.message ?? error),
      }
    }
  }

  /** 必成功的本地 git 命令 */
  async function gitMust(args, what = 'git') {
    const result = await gitTry(args)
    if (result.code !== 0) {
      const tail = String(result.stderr ?? '').trim().split('\n').slice(-3).join(' | ') || `exit ${result.code}`
      throw new Error(`${what} failed: ${tail}`)
    }
    return result.stdout
  }

  /** index.lock 是"上一趟被杀掉"留下的: 等一会儿, 还不行就把老锁清掉再来 */
  async function withIndexLockRetry(operation, what) {
    let last
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        return await operation()
      } catch (error) {
        last = error
        if (!/index\.lock/iu.test(String(error?.message ?? error)) || attempt >= 2) throw error
        if (await clearStaleLock() === true) continue
        await new Promise(resolve => setTimeout(resolve, 400 * (attempt + 1)))
      }
    }
    throw last
  }

  /** 把工作树暂存起来 (含未跟踪文件); index.lock 自愈 */
  async function stageWorktree() {
    await withIndexLockRetry(async () => {
      await gitMust(['add', '-A'], 'git add')
    }, 'git add')
  }

  /**
   * 纯 JS 扫一遍工作树 (手机用): rel → {mode, sha, size}。**不碰 git**。
   *
   * 手机沙盒里 `git add -A` / `git ls-tree` 会把桥挂住 (2026-10-21 实测 120 s 超时),
   * 而 API 推送真正需要的只是"每个文件的路径与 blob sha" —— 那件事可以自己算。
   * @returns {Promise<Map<string, {mode: string, sha: string, size: number}>>} blob 清单。
   */
  async function readLocalTreeFromFs() {
    const blobs = new Map()
    const walk = async (prefix) => {
      let entries
      try {
        entries = await readdir(prefix === '' ? repoDir : join(repoDir, prefix), { withFileTypes: true })
      } catch {
        return
      }
      for (const entry of entries) {
        // `.git` 与 `.p2p` 都不进远端: 后者装着私钥、信任表、tmp/trash (P2P 方案 §8.1),
        // 2026-10-22 夜里手机上这一条就是把 node.key 推上去了 —— git 那条路有 .p2p/.gitignore
        // 挡着, 这条纯 JS 扫盘的路不看 gitignore, 必须在这里显式跳过
        if (prefix === '' && (entry.name === '.git' || entry.name === '.p2p')) continue
        const rel = prefix === '' ? entry.name : `${prefix}/${entry.name}`
        if (isForkPath(rel)) continue
        if (entry.isDirectory()) {
          await walk(rel)
          continue
        }
        if (!entry.isFile()) continue
        try {
          const bytes = await readFile(join(repoDir, rel))
          blobs.set(rel, { mode: '100644', sha: blobShaOf(bytes), size: bytes.length })
        } catch {
          // 读不动就跳过: 手机上会话日志随时在写, 竞态里读到一半是常态, 下一趟再推
        }
      }
    }
    await walk('')
    return blobs
  }

  /** 远端分支的 head (没有提交时 `{sha: '', tree: ''}`) */
  async function head() {
    try {
      const ref = await call('GET', `/git/ref/heads/${branch}`)
      const sha = String(ref?.object?.sha ?? '')
      if (sha === '') return { sha: '', tree: '' }
      const commit = await call('GET', `/git/commits/${sha}`)
      return { sha, tree: String(commit?.tree?.sha ?? '') }
    } catch (error) {
      if (String(error?.message ?? '').includes('-> 404')) return { sha: '', tree: '' }
      throw error
    }
  }

  /**
   * 本地某棵树的完整清单: blobs / 目录 sha / 每个目录的直接孩子。
   * @param {string} tree - 树的 sha。
   * @returns {Promise<{tree: string, blobs: Map<string, {mode: string, sha: string}>, treeShas: Map<string, string>, children: Map<string, Map<string, {mode: string, type: string, sha: string}>>}>} 清单。
   */
  async function readLocalTree(tree) {
    // -z: 路径逐字节给出, 不做 `"..."` 引号转义 (中文/空格的 fork 文件名会被 git 引号包起来,
    // 解析错了就会把带引号的路径当成新文件推进远端 —— 2026-10-21 手机 push 真踩到)
    const out = await gitMust(['ls-tree', '-r', '-t', '-l', '-z', tree], 'git ls-tree')
    return parseTreeListing(out, tree)
  }

  /**
   * 解析 `git ls-tree -r -t -l -z` 的输出。
   * @param {string} text - 输出 (NUL 分隔)。
   * @param {string} root - 根树 sha。
   * @returns {{tree: string, blobs: Map<string, {mode: string, sha: string, size: number}>, treeShas: Map<string, string>, children: Map<string, Map<string, {mode: string, type: string, sha: string}>>}} 清单。
   */
  function parseTreeListing(text, root) {
    const blobs = new Map()
    const treeShas = new Map([['', root]])
    const children = new Map()
    const add = (dir, name, entry) => {
      if (!children.has(dir)) children.set(dir, new Map())
      children.get(dir).set(name, entry)
    }
    for (const record of String(text).split('\0')) {
      if (record === '') continue
      const tab = record.indexOf('\t')
      if (tab < 0) continue
      const meta = record.slice(0, tab).split(/\s+/u)
      const rel = record.slice(tab + 1)
      const size = Number.isFinite(Number(meta[3])) ? Number(meta[3]) : 0
      const slash = rel.lastIndexOf('/')
      const dir = slash === -1 ? '' : rel.slice(0, slash)
      const name = slash === -1 ? rel : rel.slice(slash + 1)
      const entry = { mode: meta[0], type: meta[1], sha: meta[2] }
      if (entry.type === 'tree') {
        treeShas.set(rel, entry.sha)
        add(dir, name, entry)
      } else if (entry.type === 'blob') {
        blobs.set(rel, { mode: entry.mode, sha: entry.sha, size })
        add(dir, name, entry)
      }
    }
    return { tree: root, blobs, treeShas, children }
  }

  /**
   * 远端某棵树的完整清单。`?recursive=1` 被 GitHub 截断时退到逐层 BFS (不丢文件)。
   * @param {string} tree - 远端树 sha。
   * @returns {Promise<object>} 与 `readLocalTree` 同形。
   */
  async function readRemoteTree(tree) {
    let listing = []
    const first = await call('GET', `/git/trees/${tree}?recursive=1`)
    if (first?.truncated === true) {
      const walk = async (sha, prefix) => {
        const page = await call('GET', `/git/trees/${sha}`)
        for (const entry of Array.isArray(page?.tree) ? page.tree : []) {
          const path = prefix === '' ? String(entry.path) : `${prefix}/${entry.path}`
          if (entry.type === 'tree') {
            listing.push({ ...entry, path })
            await walk(String(entry.sha), path)
          } else if (entry.type === 'blob') {
            listing.push({ ...entry, path })
          }
        }
      }
      await walk(tree, '')
    } else {
      listing = Array.isArray(first?.tree) ? first.tree : []
    }
    const blobs = new Map()
    const treeShas = new Map([['', tree]])
    const children = new Map()
    const add = (dir, name, entry) => {
      if (!children.has(dir)) children.set(dir, new Map())
      children.get(dir).set(name, entry)
    }
    for (const entry of listing) {
      const rel = String(entry.path ?? '')
      if (rel === '') continue
      const slash = rel.lastIndexOf('/')
      const dir = slash === -1 ? '' : rel.slice(0, slash)
      const name = slash === -1 ? rel : rel.slice(slash + 1)
      if (entry.type === 'blob') {
        blobs.set(rel, { mode: String(entry.mode ?? '100644'), sha: String(entry.sha), size: Number(entry.size ?? 0) })
        add(dir, name, { mode: String(entry.mode ?? '100644'), type: 'blob', sha: String(entry.sha) })
      } else if (entry.type === 'tree') {
        treeShas.set(rel, String(entry.sha))
        add(dir, name, { mode: '040000', type: 'tree', sha: String(entry.sha) })
      }
    }
    return { tree, blobs, treeShas, children }
  }

  /**
   * 本机相对 HEAD 明确删掉的路径 (只认工作树里真的没有的; 用于首次清理的删除传播)。
   * @returns {Promise<string[]>} 路径。
   */
  async function localDeletions() {
    // core.quotepath=false: 中文/空格的路径不套引号 (与 ls-tree -z 同一个口径)
    const result = await gitTry(['-c', 'core.quotepath=false', 'diff', '--name-status', '--no-renames', 'HEAD'])
    if (result.code !== 0) return []
    const paths = []
    for (const line of String(result.stdout).split('\n')) {
      const parts = line.split('\t')
      if (parts.length >= 2 && parts[0].startsWith('D')) paths.push(parts.slice(1).join('\t'))
    }
    return paths
  }

  /** 本机 HEAD 的 blob 表 (unborn 时为空) */
  async function localHeadBlobs() {
    const headSha = await gitTry(['rev-parse', '--verify', 'HEAD'])
    if (headSha.code !== 0) return { sha: undefined, blobs: new Map() }
    const sha = String(headSha.stdout).trim()
    const listing = await readLocalTree(sha)
    return { sha, blobs: listing.blobs }
  }

  /**
   * 把并集文件表建成嵌套 tree 并逐层上传; 远端已有的子树 sha 直接复用, 不重复 POST。
   * @param {Map<string, {mode: string, sha: string}>} files - path → blob。
   * @param {object|undefined} remoteView - `readRemoteTree` 的产物。
   * @returns {Promise<string>} 根 tree sha。
   */
  async function composeTree(files, remoteView) {
    /** 目录 → 直接孩子 (名字 → blob 条目或 tree 占位) */
    const dirs = new Map([['', new Map()]])
    const ensureDir = (dir) => {
      if (dir === '' || dirs.has(dir)) return
      const slash = dir.lastIndexOf('/')
      const parent = slash === -1 ? '' : dir.slice(0, slash)
      const name = slash === -1 ? dir : dir.slice(slash + 1)
      ensureDir(parent)
      dirs.set(dir, new Map())
      dirs.get(parent).set(name, { type: 'tree', name })
    }
    for (const [rel, entry] of files) {
      const slash = rel.lastIndexOf('/')
      const dir = slash === -1 ? '' : rel.slice(0, slash)
      const name = slash === -1 ? rel : rel.slice(slash + 1)
      ensureDir(dir)
      dirs.get(dir).set(name, { type: 'blob', mode: entry.mode ?? '100644', sha: entry.sha })
    }
    const cache = new Map()
    const shaOfDir = async (dir) => {
      if (cache.has(dir)) return cache.get(dir)
      const children = dirs.get(dir) ?? new Map()
      const entries = []
      for (const [name, entry] of children) {
        if (entry.type === 'tree') {
          const childDir = dir === '' ? name : `${dir}/${name}`
          entries.push({ path: name, mode: '040000', type: 'tree', sha: await shaOfDir(childDir) })
        } else {
          entries.push({ path: name, mode: entry.mode ?? '100644', type: 'blob', sha: entry.sha })
        }
      }
      const sameAs = (view) => {
        if (view === undefined) return false
        const expected = view.children.get(dir)
        if (expected === undefined || expected.size !== entries.length) return false
        for (const entry of entries) {
          const other = expected.get(entry.path)
          if (other === undefined || other.sha !== entry.sha || other.type !== entry.type) return false
          if (entry.type === 'blob' && String(other.mode) !== String(entry.mode)) return false
        }
        return true
      }
      let sha
      if (sameAs(remoteView)) {
        sha = dir === '' ? remoteView.tree : remoteView.treeShas.get(dir)
      } else {
        const created = await call('POST', '/git/trees', { tree: entries })
        sha = String(created.sha)
      }
      cache.set(dir, sha)
      return sha
    }
    return shaOfDir('')
  }

  /**
   * 用 API 推一次: 工作树快照 (并集) → 以远端 head 为父的新提交 → 挪 ref。
   * @param {{message?: string}} [options] - 提交信息。
   * @returns {Promise<{ok: boolean, pushed: boolean, head?: string, error?: string, via: string}>} 结果。
   */
  async function push(options = {}) {
    if (!enabled) return { ok: false, pushed: false, via: 'api', error: 'the api transport needs an https remote and a token' }
    // noGit (手机): 工作树清单与字节都由本进程读, 一条本地 git 命令都不发 —— 沙盒里的
    // `git add -A` / `write-tree` 在 2 MB 级会话上会撞 120 s 上限 (2026-10-21 实测)
    const noGit = options.noGit === true
    try {
      const remote = await head()
      let localTree = ''
      let localView
      if (noGit) {
        localView = { blobs: await readLocalTreeFromFs() }
      } else {
        // 引擎在 push 之前已经跑过 `add -A` + commit (镜像那一步), 索引就是工作树快照;
        // 这里再 `add -A` 一次在手机上会去 hash 整个工作区 (含几 MB 图片), 撞 120 s 上限。
        // 只 write-tree; 索引真的不可用时才补一次 add
        try {
          localTree = String(await withIndexLockRetry(
            () => gitMust(['write-tree'], 'git write-tree'),
            'git write-tree',
          )).trim()
        } catch {
          await stageWorktree()
          localTree = String(await withIndexLockRetry(
            () => gitMust(['write-tree'], 'git write-tree'),
            'git write-tree',
          )).trim()
        }
        if (remote.sha !== '' && remote.tree === localTree) {
          // 树一样, 什么都不用推. **不记 lastPulled**: 这一趟并没有把远端的文件物化到本地
          // (本机工作树可能少了另一台设备的文件), 下一趟 pull 还要照常走
          return { ok: true, pushed: false, via: 'api', head: remote.sha, files: 0, uploaded: 0 }
        }
        localView = await readLocalTree(localTree)
      }
      const remoteView = remote.tree === '' ? undefined : await readRemoteTree(remote.tree)
      // 远端口袋里已有的 blob 不用再传 (按 sha 去重; 上一次 push 见过的也算)
      const knownBlobs = new Set(remoteView === undefined ? [] : [...remoteView.blobs.values()].map(entry => entry.sha))
      let uploaded = 0
      /** 这一趟因为"读的时候还在变"而先不推的文件 (远端保留旧版, 下一趟再来) */
      const churning = []
      const view = noGit ? { blobs: await readLocalTreeFromFs() } : localView
      // fork 文件是**本机**的冲突留存, 不是数据: 推上去只会在对端复活, 再被拉回来,
      // 越滚越多 (2026-10-21 深夜实测: 同一个 fork 在两端来回出现)。永不参与推送
      //
      // 对话账 (conv/) 是**单写者**: 对端那一本只由它的主人推。本机工作树里那份是上一趟拉下来的
      // 快照, 对端随后再追加就比它新 —— 拿旧的覆盖新的等于把对端的账往回倒 (2026-10-22)
      const ownConvSegment = safeSegment(ownDeviceId() ?? '')
      const ownRawId = String(ownDeviceId() ?? '')
      const isForeignTranscript = rel => rel.startsWith('conv/')
        && safeSegment(rel.split('/')[1] ?? '') !== ownConvSegment
      // 派生文件的单写者: live/<别人的 id>/ 与 workspace-peers/<别人的 id>.json 是**别人的**，
      // 本机工作树里那份只是上一趟拉下来的旧快照。以前只挡了 conv/，于是手机拿它那份旧 PC 名片
      // 把 PC 刚推上去的新名片盖回去了 (2026-10-23: 手机 apk（PC）分组因此一直是 0 场)
      const isForeignOwned = rel => ownershipOfPath(rel, ownRawId) === 'remote'
      const localBlobs = new Map([...view.blobs].filter(([rel]) => !isForkPath(rel) && !isForeignTranscript(rel) && !isForeignOwned(rel)))
      /**
       * 段集合整组推送计划 (2026-10-22): 哪一侧的组出去, 远端多出来的段要删。
       *
       * 逐文件并集拼不出"整组": 远端那一组比本机长的时候, 逐文件规则只保住它**同名的那些段**,
       * 本机多出来的段照样并进新树里 —— 两条血脉的段就混在一组里了。这里按 D3 整组选边:
       * 本地胜 → 本机条目强制覆盖 + 远端多余的段进 deletions; 远端胜 → 本机这组整组不上传;
       * 扁平与段集合的布局切换 (扁平 ↔ 段) 也按同一口径比 bytes
       * @param {Map<string, {sha: string, size?: number}>|undefined} remoteBlobs - 远端树。
       * @param {Map<string, {sha: string, size?: number}>} localBlobs - 本机清单 (已去 fork)。
       * @returns {Promise<{dropLocal: string[], deletions: string[], notes: string[]}>} 计划。
       */
      const planPartsPush = async (remoteBlobs, localBlobs) => {
        const remoteSets = groupPartsSets(remoteBlobs)
        const localSets = groupPartsSets(localBlobs)
        const dropLocal = new Set()
        const deletions = new Set()
        const notes = []
        for (const dirRel of new Set([...remoteSets.keys(), ...localSets.keys()])) {
          const localSet = localSets.get(dirRel)
          const remoteSet = remoteSets.get(dirRel)
          const localDir = join(repoDir, ...dirRel.split('/'))
          const localIndex = localSet === undefined ? null : await readPartsIndex(localDir)
          let remoteIndex = null
          if (remoteSet !== undefined) {
            try {
              remoteIndex = JSON.parse((await fetchBlobVerified(call, remoteSet.indexSha)).toString('utf8'))
            } catch {
              // 读不到对端索引就按"远端没有可用集合"算: 本地胜, 下一趟再收敛
              remoteIndex = null
            }
          }
          const parentRel = dirRel.slice(0, dirRel.lastIndexOf('/'))
          const localFlatRel = flatSessionRelOf(parentRel, localBlobs)
          const remoteFlatRel = flatSessionRelOf(parentRel, remoteBlobs)
          const localBytes = Number(localIndex?.bytes ?? 0)
            || (localFlatRel === null ? 0 : Number(localBlobs.get(localFlatRel)?.size ?? 0))
          const remoteBytes = Number(remoteIndex?.bytes ?? 0)
            || (remoteFlatRel === null ? 0 : Number(remoteBlobs.get(remoteFlatRel)?.size ?? 0))
          if (localBytes === 0 && remoteBytes === 0) continue
          if (remoteBytes > localBytes) {
            // 远端更完整: 本机这组整组不上传; 扁平那份也别再推上去把远端布局搅混
            if (localSet !== undefined) {
              dropLocal.add(localSet.indexRel)
              for (const name of localSet.parts.keys()) dropLocal.add(`${dirRel}/${name}`)
            }
            if (localFlatRel !== null) {
              dropLocal.add(localFlatRel)
              deletions.add(localFlatRel)
            }
            notes.push(`${dirRel}: the remote set is longer (${remoteBytes} > ${localBytes}), keeping it`)
            continue
          }
          if (localSet !== undefined) {
            // 本地胜: 本机的段覆盖同名路径 (mergeTreeEntries 那边按谓词强制本地), 远端多出来的删掉
            for (const name of remoteSet?.parts.keys() ?? []) {
              if (!localSet.parts.has(name)) deletions.add(`${dirRel}/${name}`)
            }
            if (remoteFlatRel !== null) deletions.add(remoteFlatRel)
            continue
          }
          if (remoteSet !== undefined) {
            // 本机只剩扁平那一份且更长: 推扁平, 远端整组收掉 (布局切回扁平)
            for (const name of remoteSet.parts.keys()) deletions.add(`${dirRel}/${name}`)
            deletions.add(remoteSet.indexRel)
            notes.push(`${dirRel}: keeping the flat session (${localBytes} > ${remoteBytes}) and dropping the remote set`)
          }
        }
        return { dropLocal: [...dropLocal], deletions: [...deletions], notes }
      }
      const pushPlan = await planPartsPush(remoteView?.blobs, localBlobs)
      for (const rel of pushPlan.dropLocal) localBlobs.delete(rel)
      if (pushPlan.notes.length > 0) warn(`api push: ${pushPlan.notes.join('; ')}`)
      let uploadedParts = 0
      for (const [rel, entry] of [...localBlobs]) {
        if (knownBlobs.has(entry.sha)) continue
        let bytes
        if (noGit) {
          bytes = await readFile(join(repoDir, rel))
        } else {
          const content = await gitTry(['cat-file', 'blob', entry.sha], { encoding: 'buffer' })
          if (content.code !== 0) throw new Error(`git cat-file ${entry.sha} failed: ${String(content.stderr).slice(0, 200)}`)
          bytes = Buffer.isBuffer(content.stdout) ? content.stdout : Buffer.from(content.stdout)
        }
        if (bytes.length > MAX_BLOB_BYTES) throw new Error(`${rel} is ${bytes.length} bytes, larger than the api transport's ${MAX_BLOB_BYTES} byte blob limit`)
        if (blobShaOf(bytes) !== entry.sha) {
          // 「列清单时它是这个 sha, 读字节时已经不是了」= 本机正在写它 (最常见的就是本机正在聊的
          // 那场会话日志)。从前这里让整趟 push 失败, 于是"主人在用这台机器"时对端永远收不到
          // 队列里的消息 (2026-10-22 实测: 手机上的 push 每 3 分钟失败一次, PC 端一直等不到)
          // 现在只把这一份剔出本趟: 远端保留旧版, 别的文件照推, 下一趟再补它
          localBlobs.delete(rel)
          churning.push(rel)
          continue
        }
        await call('POST', '/git/blobs', { content: bytes.toString('base64'), encoding: 'base64' })
        knownBlobs.add(entry.sha)
        uploaded += 1
        if (isPartFileRel(rel)) uploadedParts += 1
      }
      if (churning.length > 0) warn(`the api push skipped ${churning.length} file(s) that were still being written (${churning.slice(0, 3).join(', ')})`)
      const deletions = [
        ...(noGit ? [] : await localDeletions()),
        // 引擎在 push 前已经把镜像的删除提交掉了, 所以"相对 HEAD"的那一份看不到它们;
        // 由 pushSync 把最近一次镜像的删除清单传进来 (Luwi 加 2026-10-20 晚)
        ...(Array.isArray(options.deletions) ? options.deletions : []),
      ]
      // 推被拒 (422 not a fast forward) = 对端在同一瞬间也推了一条。从前这一趟就失败,
      // 要等下一轮 (手机上是 3 分钟) 才自愈; 现在**当拍**重读远端 head、重新合并、再推
      // (2026-10-22 实测: 手机与 PC 同拍推送撞过, 日志里那条 422 就是)
      let base = remote
      let baseView = remoteView
      for (let attempt = 0; ; attempt += 1) {
        // 组层的删除只在第一趟做: 422 那一档远端在同一瞬间动过, 这一趟别删别人的新段,
        // 下一趟 push 会收敛 (本机的组还在, 下趟照样能判胜负)
        const deletionsNow = attempt === 0 ? [...deletions, ...pushPlan.deletions] : deletions
        // 段集合内的条目按整组胜负走, 不让"远端单文件更大"再改一次主意
        const merged = mergeTreeEntries(baseView?.blobs, localBlobs, deletionsNow, {
          preferRemoteLargerSessions: rel => rel.startsWith('sessions/') && partsDirRelOf(rel) === null,
        })
        const treeSha = await composeTree(merged.entries, baseView)
        if (base.sha !== '' && treeSha === base.tree) {
          return { ok: true, pushed: false, via: 'api', head: base.sha, files: merged.entries.size, uploaded, uploadedParts }
        }
        const commit = await call('POST', '/git/commits', {
          message: options.message ?? 'dsh-session-sync: push snapshot (api)',
          tree: treeSha,
          ...(base.sha === '' ? {} : { parents: [base.sha] }),
        })
        try {
          if (base.sha === '') {
            await call('POST', '/git/refs', { ref: `refs/heads/${branch}`, sha: commit.sha })
          } else {
            await call('PATCH', `/git/refs/heads/${branch}`, { sha: commit.sha, force: false })
          }
        } catch (error) {
          const text = String(error?.message ?? error)
          if (attempt >= 2 || !/fast forward|422|stale|does not match/iu.test(text)) throw error
          warn(`the api push lost the race (${text.slice(0, 140)}); re-reading the remote head and retrying`)
          base = await head()
          baseView = base.tree === '' ? undefined : await readRemoteTree(base.tree)
          continue
        }
        // 本地记账: 失败不影响已经推上去的那棵树; noGit (手机) 一条本地 git 都不发, 这笔账跳过
        if (!noGit) {
          const committed = await gitTry(['commit', '-m', options.message ?? 'dsh-session-sync: push snapshot (api)'])
          if (committed.code !== 0 && !/nothing to commit|no changes added/iu.test(String(committed.stderr))) {
            warn(`the local checkpoint commit failed, but the api push already succeeded: ${String(committed.stderr).trim().split('\n').slice(-1)[0] ?? ''}`)
          }
        }
        return {
          ok: true,
          pushed: true,
          via: 'api',
          head: String(commit.sha),
          files: merged.entries.size,
          uploaded,
          uploadedParts,
          added: merged.added,
          changed: merged.changed,
          removed: merged.removed,
        }
      }
    } catch (error) {
      return { ok: false, pushed: false, via: 'api', error: String(error?.message ?? error) }
    }
  }

  /**
   * 用 API 拉一次: 把远端那棵树物化进工作树。
   * **只写不删**: 远端没有的本地文件原样保留; 两边都动过的按 keep-both 落 `.remote-fork-*`。
   * @param {{force?: boolean}} [options] - force = 忽略 lastPulled 缓存。
   * @returns {Promise<{ok: boolean, pulled: boolean, head?: string, written?: number, forked?: number, kept?: number, error?: string, via: string}>} 结果。
   */
  async function pull(options = {}) {
    if (!enabled) return { ok: false, pulled: false, via: 'api', error: 'the api transport needs an https remote and a token' }
    try {
      const remote = await head()
      if (remote.tree === '') return { ok: true, pulled: false, via: 'api', head: '', written: 0, forked: 0, kept: 0 }
      if (options.force !== true && lastPulled !== null && lastPulled.head === remote.sha && Date.now() - lastPulled.at < PULL_HEAD_CACHE_MS) {
        return { ok: true, pulled: false, via: 'api', head: remote.sha, written: 0, forked: 0, kept: 0 }
      }
      // 远端这次提交的时刻: 只在"本地没有基点"时用来判谁更晚
      let remoteAt = 0
      try {
        const commit = await call('GET', `/git/commits/${remote.sha}`)
        remoteAt = Date.parse(String(commit?.committer?.date ?? commit?.author?.date ?? '')) || 0
      } catch {
        remoteAt = 0
      }
      const remoteView = await readRemoteTree(remote.tree)
      // 段集合 (2026-10-22): 整组落或整组留。逐文件那一趟**跳过**这些路径, 由下面单独的组趟处理 ——
      // 按路径决策会把两条血脉的段拼进同一组 (见 docs/Luwi-异地同步-实现方案-20261022.md §12)
      const partsSets = groupPartsSets(remoteView.blobs)
      const partsPaths = new Set()
      for (const set of partsSets.values()) {
        partsPaths.add(set.indexRel)
        for (const name of set.parts.keys()) partsPaths.add(`${set.dirRel}/${name}`)
      }
      /**
       * 取一个 blob 并核对 sha (逐文件与段两条路共用, 见文件头的 [fetchBlobVerified])。
       * @param {string} sha - 期望的 blob sha。
       * @returns {Promise<Buffer>} 核对过的字节。
       */
      const fetchVerifiedBlob = sha => fetchBlobVerified(call, sha)
      // 手机上连 `git ls-tree` 都别发 (桥可能已经卡住): 判断只靠工作树字节 + 远端 sha,
      // 会话说到底按"更长的那一份"走, 不依赖本地 HEAD
      const localHead = process.platform === 'android'
        ? { sha: undefined, blobs: new Map() }
        : await localHeadBlobs()
      const stamp = new Date().toISOString().replace(/[-:T]/gu, '').slice(0, 14)
      // 自己也写的那些 (live/<本机 id>/、workspace-peers/<本机 id>.json) **永远以本机为准**:
      // 对端那份只是旧快照, 拿它来 keep-both 只会每趟多出一堆 fork 文件
      let ownId = String(ownDeviceId() ?? '').trim()
      if (ownId === '') {
        try {
          ownId = readFileSync(join(repoDir, 'device.txt'), 'utf8').trim()
        } catch {
          ownId = ''
        }
      }
      const ours = new Set(ownId === '' ? [] : [`live/${ownId}`, `workspace-peers/${ownId}.json`])
      const isOurs = rel => [...ours].some(prefix => rel === prefix || rel.startsWith(`${prefix}/`))
      let written = 0
      let forked = 0
      let kept = 0
      let ignoredForks = 0
      const changedPaths = []
      for (const [rel, entry] of remoteView.blobs) {
        if (isOurs(rel)) continue
        // fork 文件不落到本机: 它们是产生冲突那台机器的本地留存, 拉下来只会在本机再长一份
        if (isForkPath(rel)) {
          ignoredForks += 1
          continue
        }
        // 段路径整组落到下面那个组趟里, 这里一个字节都不碰
        if (partsPaths.has(rel)) continue
        const absolute = join(repoDir, rel)
        let localExists = false
        let localBytes
        let localSha
        let localMtimeMs = 0
        try {
          const info = statSync(absolute)
          if (info.isFile()) {
            localExists = true
            localMtimeMs = info.mtimeMs
            localBytes = readFileSync(absolute)
            localSha = blobShaOf(localBytes)
          }
        } catch {
          localExists = false
        }
        if (localExists && localSha === entry.sha) {
          kept += 1
          continue
        }
        // blob 取回来必须与 tree 里的 sha 对上 (见 fetchVerifiedBlob), 最多试三次
        const content = await fetchVerifiedBlob(entry.sha)
        // 会话日志是 append-only: **更长的那一份就是更完整的那一份**。本地那一份若是远端的前缀,
        // 或者本地/远端在同一个基点之后各自多写了一段 (手机给取回的 PC 会话补过标题之类),
        // 都以远端的更长版本为准 —— 否则手机会永远停在旧的一段对话上。工作区文件不走这条,
        // 仍按本地 HEAD 判断 (前缀延伸才更新)
        const sessionLog = rel.startsWith('sessions/')
        const extendsLocal = localExists === true && localBytes !== undefined && content.length > localBytes.length
        const appendOnly = extendsLocal
          && (sessionLog || content.subarray(0, localBytes.length).equals(localBytes))
        // 反过来的那一半 (2026-10-21 晚): **本地是远端的前缀延伸** = 远端推上来的是同一场会话的旧快照,
        // 本机还在写它。从前这种情况按"两边都改过"判成 fork, 于是主人正在用的那一场每轮被自己的旧
        // 副本撞出一个 fork (23:15 远端那次推带上 session-…-remote-fork-20261021151215-PC 就是这么来的)。
        // 会话日志 append-only, 本地更长且以远端那份开头 → 远端那份被本地完整包含, 丢掉它不会丢字
        const localIsLonger = sessionLog && localExists === true && localBytes !== undefined
          && localBytes.length > content.length && localBytes.subarray(0, content.length).equals(content)
        // 派生文件 (名片 / 投影快照 / 回执 / 设备文件) 不参与 keep-both: 它们的作者每轮都会重写,
        // 两边都"改过"就各留一个 fork, 而这些 fork 永远不会收敛 (2026-10-21 清理时 30 个 fork
        // 里 25 个是这一类)。按"谁写的"单边胜出
        const ownership = ownershipOfPath(rel, ownId)
        const action = appendOnly || ownership === 'remote'
          ? 'write'
          : localIsLonger
            ? 'keepLocal'
            : ownership === 'local'
              ? (localExists ? 'keepLocal' : 'write')
              : decidePullAction({
                localExists,
                localSha,
                localHeadSha: localHead.blobs.get(rel)?.sha,
                remoteSha: entry.sha,
                localMtimeMs,
                remoteAt,
              })
        if (action === 'skip' || action === 'keepLocal') {
          kept += 1
          continue
        }
        mkdirSync(dirname(absolute), { recursive: true })
        if (action === 'fork') {
          writeFileSync(`${absolute}.remote-fork-${stamp}-${deviceShort}`, content)
          changedPaths.push(`${rel}.remote-fork-${stamp}-${deviceShort}`)
          forked += 1
          continue
        }
        writeFileSync(absolute, content)
        changedPaths.push(rel)
        written += 1
      }
      // --- 段集合组趟 (2026-10-22): 整组落或整组留, 并给"首拉一场大会话"一个每趟预算 ---
      // 逐文件那一趟已经把 inbox/ 这类小文件落完了, 所以这里慢也只慢段; 组内整组搬完之后
      // 索引**最后**写 —— 半组不落地, 于是取回端永远看不到"索引说 22 段, 盘上只有 9 段"
      const setStats = { taken: 0, deferred: 0, parts: 0, bytes: 0, kept: 0 }
      const orderedSets = [...partsSets.values()]
        .sort((left, right) => left.bytes - right.bytes || left.dirRel.localeCompare(right.dirRel))
      for (const set of orderedSets) {
        const localDir = join(repoDir, ...set.dirRel.split('/'))
        const localIndex = await readPartsIndex(localDir)
        // 本地这一侧"有多长": 有段集合就看索引的 bytes (只有齐了才算), 只有扁平那份就看它的大小
        const localSet = localIndex === null ? null : await inspectPartsSet(localDir)
        const localBytes = localIndex !== null && localSet?.complete === true
          ? Number(localIndex.bytes)
          : localFlatBytes(localDir)
        if (localIndex !== null && localSet?.complete === true && localBytes >= set.bytes) {
          // 本地那份一样长或更长: 整组留, 一个字节都不读 (空闲一轮的成本还是 0)
          setStats.kept += 1
          continue
        }
        // 真正还缺多少 (同大小的段按"已有"算, 只 stat 不读内容), 再看这一趟的预算
        let needParts = set.parts.size
        let needBytes = set.bytes
        for (const [name, part] of set.parts) {
          try {
            if (statSync(join(localDir, name)).size === part.size) {
              needParts -= 1
              needBytes -= part.size
            }
          } catch {
            // 没有就是缺
          }
        }
        if (setStats.parts > 0 && (setStats.parts + needParts > partsBudget.parts
          || setStats.bytes + needBytes > partsBudget.bytes)) {
          // 组按大小升序, 后面只会更大: 整组留到下一趟 (下一趟按 sha 跳过已下的段)
          setStats.deferred += 1
          continue
        }
        // 索引先取 (只有几百字节), 与树清单对不上就整组不碰 —— 宁可晚一轮, 不落半份
        let indexBytes
        try {
          indexBytes = await fetchVerifiedBlob(set.indexSha)
          const parsed = JSON.parse(indexBytes.toString('utf8'))
          if (parsed === null || typeof parsed !== 'object'
            || Number(parsed.parts) !== set.parts.size
            || Number(parsed.bytes) !== set.bytes
            || typeof parsed.file !== 'string' || parsed.file === ''
            || typeof parsed.sha256 !== 'string' || parsed.sha256.length !== 64) {
            warn(`the remote parts set ${set.dirRel} does not match its index (${set.parts.size} parts / ${set.bytes} bytes); leaving it for the next round`)
            setStats.deferred += 1
            continue
          }
        } catch (error) {
          warn(`the remote parts set ${set.dirRel} could not be read (${String(error?.message ?? error)}); leaving it for the next round`)
          setStats.deferred += 1
          continue
        }
        mkdirSync(localDir, { recursive: true })
        let failedPart = null
        let fetchedParts = 0
        let fetchedBytes = 0
        for (const name of partsSetNames({ parts: set.parts.size })) {
          const part = set.parts.get(name)
          const target = join(localDir, name)
          try {
            if (blobShaOf(readFileSync(target)) === part.sha) continue
          } catch {
            // 没有就是缺
          }
          try {
            const content = await fetchVerifiedBlob(part.sha)
            writeFileSync(target, content)
            changedPaths.push(`${set.dirRel}/${name}`)
            fetchedParts += 1
            fetchedBytes += content.length
          } catch (error) {
            failedPart = error
            break
          }
        }
        setStats.parts += fetchedParts
        setStats.bytes += fetchedBytes
        if (failedPart !== null) {
          // 网络/校验在组中途断掉: 这一趟不写索引 (下一趟看得出它没落齐), 已下的段不重下
          warn(`the remote parts set ${set.dirRel} stopped after ${fetchedParts} part(s) (${String(failedPart?.message ?? failedPart)}); it retries next round`)
          setStats.deferred += 1
          continue
        }
        writeFileSync(join(localDir, PARTS_INDEX), indexBytes)
        changedPaths.push(set.indexRel)
        setStats.taken += 1
      }
      if (changedPaths.length > 0 && process.platform !== 'android') {
        // 本地记账这一步失败**不能**把已经写好的文件判成没拉成: 手机上 `git add -A` 会去
        // hash 整个工作区 (含工作区那几 MB 的图片), 在沙盒里撞 120 s 上限, 而文件已经在
        // 工作树里了。只 add 这一趟真正写过的路径, 下一趟引擎镜像再补全提交。
        // **Android 上整段跳过**: 手机沙盒里 `git add` 一条 2.4 MB 的会话会挂住桥,
        // 把 pull 卡在记账这一步, 后面的取回反而永远到不了; 本地会话库靠 finishExternalPull
        // 更新, 镜像工作树的提交交给下一次引擎镜像
        try {
          const added = await gitTry(['add', '--', ...changedPaths])
          if (added.code !== 0) throw new Error(String(added.stderr).trim().split('\n').slice(-1)[0] ?? `exit ${added.code}`)
          const committed = await gitTry(['commit', '-m', `dsh-session-sync: remote snapshot ${remote.sha.slice(0, 8)} (api)`])
          if (committed.code !== 0 && !/nothing to commit|no changes added/iu.test(String(committed.stderr))) {
            warn(`the api pull wrote the files but the local checkpoint commit failed: ${String(committed.stderr).trim().split('\n').slice(-1)[0] ?? ''}`)
          }
        } catch (error) {
          warn(`the api pull wrote ${changedPaths.length} file(s) but the local checkpoint failed: ${String(error?.message ?? error)}`)
        }
      }
      // 有段集合被延后时**不能**记 head 缓存: 那一条是 6 小时, 记下去就等于把续传堵死
      // (下一分钟那趟会以为自己已经拉过这个 head, 直接 pulled:false 返回)
      if (setStats.deferred === 0) lastPulled = { head: remote.sha, tree: remote.tree, at: Date.now() }
      return {
        ok: true,
        pulled: written + forked + setStats.taken > 0,
        via: 'api',
        head: remote.sha,
        written,
        forked,
        kept,
        ignoredForks,
        // 段集合那一路的读数 (方案文档 §14.5: "省没省"要能看见, 不靠猜)
        partSetsTaken: setStats.taken,
        partSetsDeferred: setStats.deferred,
        partsFetched: setStats.parts,
        bytesFetched: setStats.bytes,
      }
    } catch (error) {
      return { ok: false, pulled: false, via: 'api', error: String(error?.message ?? error) }
    }
  }

  return { enabled, push, pull, head }
}
