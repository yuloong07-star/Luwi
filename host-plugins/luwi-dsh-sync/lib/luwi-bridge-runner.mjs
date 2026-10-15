// lib/luwi-bridge-runner.mjs — Luwi 新增文件 (上游 v0.2.24 没有这一份)
//
// 为什么要有它: 安卓 app 里没有 git, 也没有可 exec 的位置 (W^X), 所以 git 交给 G3 的
// Linux 沙盒 (shell uid, alpine + proot) 跑, 整条命令经 loopback 桥转发。桥那一行 JSON
// 只走文本, 所以 stdout / stderr / 退出码都落成共享存储上的文件再回读 —— 二进制
// (`cat-file blob`) 因此天然安全, 不过 JSON
//
// **gitdir 与 worktree 分家** (2026-10-13 真机实测的修法, 见
// docs/DSH-LW-3.0.0-G1-施工单.md 第 9 节): /sdcard (FUSE) 上建不了硬链接也建不了符号
// 链接, 而 proot 的 --link2symlink 恰是靠符号链接仿真 link() —— git 的 loose object
// 落盘 (link 失败回退 rename, 但临时文件已被 proot 改名走了) 在 /sdcard 上必然断。于是:
// worktree 留在共享存储 (宿主直读写, 沙盒经 /work 看见), gitdir 放沙盒自己的真文件系统
// (/root/lw-sync/<key>/gitdir), 两者的桥是 worktree 里那个 .git 文件 (gitfile) ——
// `git init --separate-git-dir` 生成它, 之后的命令一律以 worktree 为 cwd 靠它发现仓库
//
// **刻意不传 `--git-dir` / `--work-tree`**: `--git-dir` 会把 GIT_DIR 导出给子进程, 而
// 推本地路径的远端时 git 会起 `git receive-pack` 子进程 —— 那个子进程拿着别人的 GIT_DIR
// 就找不到自己刚收下的对象 (2026-10-13 harness 实测:
// `error: unpack should have generated <sha>, but I can't find it!` 而 push 被拒)。
// gitfile + cwd 发现没有这个问题, pathspec (`checkout --ours -- <rel>`) 也天然相对工作树
//
// 契约与上游 runner 同形 (index.mjs 的 makeRunGit):
//   run(args, { cwd, binary, signal, timeoutMs }) -> { code, stdout, stderr }
//   stdout 在 binary=false 时是 UTF-8 文本, binary=true 时是 Buffer (原始字节)
//
// 队列: 这一份 runner 自己有一条链, 命令一律排队执行。**不排不行**: 沙盒那一侧一次只该有
// 一条命令在跑, 而 out/err/code 三个文件是每个仓库一套 —— 引擎里 collectStatus 会用
// Promise.all 并发发 5 条只读命令 (上游 subprocess 各有独立管道, 没这个问题), 到桥上就会
// 互相覆盖, 实测症状是 /sync status 的 head 显示成别人 status --porcelain 的一行
// (2026-10-13 真机)
//
// 取消 (abort): 只收回等待 —— 沙盒里那条命令跑完它自己的超时; 它仍占着队列, 后面的命令
// 排在它后面, 顺序因此不受取消影响

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { readFile, stat } from 'node:fs/promises'
import { connect } from 'node:net'
import { join, posix } from 'node:path'

/** 应用拉起 host 时设置的两个变量, 都在才算"这一份跑在 Luwi 里" (与 luwi-channel 同判据) */
const ENDPOINT_VARIABLE = 'LW_CHANNEL_ENDPOINT'
const TOKEN_VARIABLE = 'LW_CHANNEL_TOKEN'

/** 沙盒里工作区挂的那个点 (channel/LwLinuxSandbox.kt 的 `-b <work>:/work`, 命令形状写死在那边) */
const GUEST_WORKSPACE = '/work'

/** 沙盒内 gitdir 的家: rootfs 里的真文件系统, 每次 install 重装会被换掉 (重新 bootstrap 即可) */
const GUEST_GIT_HOME = '/root/lw-sync'

/** run 文件的落点 (共享存储上工作区的 .lw-sync 里, 在工作树之外, 不进 git 的视野) */
const RUN_DIRECTORY = '.lw-sync/session-sync/.run'

/** 一条命令最多跑多久 / 桥这一侧最多等多久 (linux op 自己的上限是 600000) */
const BRIDGE_MAX_TIMEOUT_MS = 600000
const BRIDGE_MIN_TIMEOUT_MS = 1000
const SOCKET_MARGIN_MS = 60000

/**
 * 这一份包跑在 Luwi 主机里吗
 *
 * 判据与 luwi-channel 同一套: 两个环境变量都在 = 应用起的 host (手机侧, 走桥); 少一个 =
 * 别的宿主 (电脑等, 走上游 ctx.subprocess 那条路)
 * @param {Record<string, string|undefined>} [env] 环境变量表 (测试注入)。
 * @returns {{endpoint: string, token: string}|null} 都在时返回两者, 否则 null。
 */
export function luwiBridgeFromEnv(env = process.env) {
  const endpoint = typeof env[ENDPOINT_VARIABLE] === 'string' ? env[ENDPOINT_VARIABLE].trim() : ''
  const token = typeof env[TOKEN_VARIABLE] === 'string' ? env[TOKEN_VARIABLE].trim() : ''
  if (endpoint === '' || token === '') return null
  return { endpoint, token }
}

/**
 * 读 $DSH_HOME/lw/session-sync.json (可选)
 *
 * 手机上没有 profile 编辑器, 这份 JSON 就是用户侧的配置面 (见施工单第 2 节的覆盖示例)。
 * 读不动 / 不是 JSON / 不是对象都回 {} 并留一句警告 —— 配置坏掉不该让插件整个不挂
 * @param {Record<string, string|undefined>} [env] 环境变量表 (测试注入)。
 * @param {string} [homeOverride] 显式 home (桌面宿主 env 里没有 DSH_HOME, 由宿主按 sessionRoot 推给它)。
 * @returns {Record<string, unknown>} 解析出来的对象, 缺省 {}。
 */
export function readLuwiSyncConfig(env = process.env, homeOverride = '') {
  // Luwi 修 (2026-10-15): 多一个显式 home。桌面宿主 (Electron main) 的进程 env 里**没有**
  // DSH_HOME —— 它只按 argv 把 profile 目录传给 host。只认 env 的话, PC 侧这份文件层永远是空
  // 的: 面板把地址写进文件也读不回来 (现象就是 remote 明明写了却报 unset)
  const explicit = typeof homeOverride === 'string' ? homeOverride.trim() : ''
  const home = explicit !== '' ? explicit : (typeof env.DSH_HOME === 'string' ? env.DSH_HOME.trim() : '')
  if (home === '') return {}
  const file = join(home, 'lw', 'session-sync.json')
  let text
  try {
    text = readFileSync(file, 'utf8')
  } catch (error) {
    if (error?.code !== 'ENOENT') {
      console.warn(`luwi-dsh-sync: could not read ${file}: ${error?.message ?? error}`)
    }
    return {}
  }
  // Luwi 加 (G1 收口 C4): 载入期只许读一份小文件 —— 这里是一台机器上唯一在挂载帧里读盘的地方,
  // 给它一条硬上限, 免得一个被写坏的大文件把 host 的启动拖住
  if (text.length > 65536) {
    console.warn(`luwi-dsh-sync: ${file} is larger than 64 KiB, ignoring it`)
    return {}
  }
  try {
    const parsed = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      console.warn(`luwi-dsh-sync: ${file} is not a JSON object, ignoring it`)
      return {}
    }
    return parsed
  } catch (error) {
    console.warn(`luwi-dsh-sync: ${file} is not valid JSON: ${error?.message ?? error}`)
    return {}
  }
}

/**
 * 工作树必须落在绑给 /work 的那棵树里, 不然沙盒看不见它
 * @param {string} workspace 工作区根 (宿主路径)。
 * @param {string} worktree 同步仓库工作树 (宿主路径)。
 * @returns {string|null} 不合格时是人话, 合格 null。
 */
export function luwiWorktreeProblem(workspace, worktree) {
  if (typeof workspace !== 'string' || workspace === '') {
    return 'the workspace root is unknown, so the sandbox has nothing to bind at /work'
  }
  if (typeof worktree !== 'string' || worktree === '') {
    return 'the sync repository directory is unknown'
  }
  const rel = posix.relative(workspace, worktree)
  if (rel === '') return null
  if (rel.startsWith('..') || posix.isAbsolute(rel)) {
    return `the sync repository directory ${worktree} is outside the workspace ${workspace}, and the sandbox`
      + ' only sees the workspace (it is bound at /work) - move repoDir inside the workspace'
  }
  return null
}

/** 单引号 shell 转义 (POSIX): a'b → 'a'\''b' */
function shellQuote(value) {
  return `'${String(value).split("'").join("'\\''")}'`
}

/** 取消的理由: 有 reason 就用它, 没有给一句与上游同形的 */
function abortReason(signal) {
  return signal?.reason instanceof Error ? signal.reason : new Error('sync operation aborted')
}

/**
 * 一次桥调用: 一行 JSON 出去, 一行 JSON 回来 (与 luwi-channel 的调用同一个协议)
 * @param {string} endpoint host:port。
 * @param {string} token 通道令牌。
 * @param {object} body 请求体的其余字段。
 * @param {number} timeoutMs 等回话的上限。
 * @returns {Promise<object>} 解析后的 result 对象。
 */
function channelCall(endpoint, token, body, timeoutMs) {
  const split = endpoint.lastIndexOf(':')
  const host = endpoint.slice(0, split)
  const port = Number(endpoint.slice(split + 1))
  const line = `${JSON.stringify({ method: 'linux', token, ...body })}\n`
  return new Promise((resolve, reject) => {
    const socket = connect({ host, port })
    let buffer = ''
    let settled = false
    const timer = setTimeout(() => {
      settled = true
      socket.destroy()
      reject(new Error(`the app did not answer the sandbox call within ${timeoutMs}ms`))
    }, timeoutMs)
    const finish = (settle) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      socket.end()
      settle()
    }
    socket.setEncoding('utf8')
    socket.on('connect', () => socket.write(line))
    socket.on('data', (chunk) => {
      buffer += chunk
      const end = buffer.indexOf('\n')
      if (end < 0) return
      const answer = buffer.slice(0, end)
      finish(() => {
        let parsed
        try {
          parsed = JSON.parse(answer)
        } catch {
          reject(new Error(`the app answered with something that is not JSON: ${answer.slice(0, 200)}`))
          return
        }
        if (parsed?.ok !== true) {
          reject(new Error(parsed?.error ?? 'the app reported no reason'))
          return
        }
        resolve(parsed.result ?? {})
      })
    })
    socket.on('error', (error) => {
      finish(() => reject(new Error(`the app's privileged channel at ${endpoint} could not be reached: ${error.message}`)))
    })
    socket.on('close', () => {
      finish(() => reject(new Error(`the app closed the channel at ${endpoint} without answering`)))
    })
  })
}

/**
 * 把上游 runner 契约接在 Luwi 的桥 + Linux 沙盒上
 *
 * @param {object} deps 依赖。
 * @param {string} deps.endpoint LW_CHANNEL_ENDPOINT 的值。
 * @param {string} deps.token LW_CHANNEL_TOKEN 的值。
 * @param {string} deps.workspace 工作区根, 宿主路径 (绑到沙盒 /work 的那一棵)。
 * @param {string} [deps.gitBin] git 可执行名 (沙盒 PATH 里的, 默认 git)。
 * @param {number} [deps.commandTimeoutMs] 单条命令超时 (默认 120000)。
 * @param {number} [deps.maxOutputBytes] 文本输出收集上限 (默认 262144, 与上游同值)。
 * @param {{readFile: Function, stat: Function}} [deps.files] 文件读写缝 (默认本进程 fs;
 *   电脑上跑集成 harness 时换成经 adb 读写的适配器)。
 * @returns {(args: string[], runOpts?: object) => Promise<{code: number, stdout: string|Buffer, stderr: string}>} runner。
 */
export function makeLuwiBridgeRunner(deps) {
  const endpoint = deps.endpoint
  const token = deps.token
  const workspace = deps.workspace
  const gitBin = typeof deps.gitBin === 'string' && deps.gitBin !== '' ? deps.gitBin : 'git'
  const commandTimeoutMs = Number.isFinite(deps.commandTimeoutMs) ? deps.commandTimeoutMs : 120000
  const maxOutputBytes = Number.isFinite(deps.maxOutputBytes) ? deps.maxOutputBytes : 262144
  const files = deps.files ?? { readFile, stat }

  /** safe.directory 配过一次就不用再配 (rootfs 重装会丢, 重启 host 后会再配一次) */
  let safeDirectoryReady = false
  /** 一条链: 同一仓库的命令一律排队 (见文件头的"队列") */
  let queue = Promise.resolve()

  /** 读一段文本, 超过上限时留头 (与上游 `{maxBytes}` 的收集语义一致) */
  async function readText(path) {
    const info = await files.stat(path)
    if (info.size <= maxOutputBytes) return await files.readFile(path, 'utf8')
    const whole = await files.readFile(path)
    return whole.subarray(0, maxOutputBytes).toString('utf8')
  }

  async function runOne(args, runOpts) {
    const signal = runOpts.signal ?? undefined
    if (signal?.aborted === true) throw abortReason(signal)

    const hostWorktree = typeof runOpts.cwd === 'string' && runOpts.cwd !== '' ? runOpts.cwd : workspace
    const problem = luwiWorktreeProblem(workspace, hostWorktree)
    if (problem !== null) throw new Error(problem)
    const rel = posix.relative(workspace, hostWorktree)
    const guestWorktree = rel === '' ? GUEST_WORKSPACE : posix.join(GUEST_WORKSPACE, rel)

    const key = createHash('sha1').update(hostWorktree).digest('hex').slice(0, 12)
    const guestGitDir = posix.join(GUEST_GIT_HOME, key, 'gitdir')
    const guestRunDir = posix.join(GUEST_WORKSPACE, RUN_DIRECTORY, key)
    const hostRunDir = posix.join(workspace, RUN_DIRECTORY, key)
    const guestFile = (name) => posix.join(guestRunDir, name)
    const hostFile = (name) => posix.join(hostRunDir, name)

    const asked = Number.isFinite(runOpts.timeoutMs) ? runOpts.timeoutMs : commandTimeoutMs
    const budget = Math.max(BRIDGE_MIN_TIMEOUT_MS, Math.min(BRIDGE_MAX_TIMEOUT_MS, Math.floor(asked)))

    // 命令形状: 备好 gitdir / run 目录与干净的文件, 可选地补一次 safe.directory, 然后
    // cd 进工作树跑 git, stdout / stderr / 退出码落文件。用 `;` 连接, 前面任何一步失败
    // 都不挡住后面 (git 起不来时 stderr 里会有 sh 的那句话)。`cd X && git` 里那个 && 是
    // 刻意的: 工作树不存在时不出假回执, 退出码会留在 code 文件里
    const steps = [
      // 只建 gitdir 的**父目录** (git init --separate-git-dir 需要它在; 少了父目录 init 报
      // "Invalid path ... No such file or directory") —— gitdir 本身交给 init 建
      `mkdir -p ${shellQuote(guestWorktree)} ${shellQuote(guestRunDir)} ${shellQuote(posix.dirname(guestGitDir))}`,
      // 坏掉的一对 (gitdir 没有 HEAD / gitfile 指向的不是仓库) 在这里自愈: 一起摘掉, 下一次
      // init 重建。**gitdir 绝不预先 mkdir** —— 预先建出的空目录会让带着旧 gitfile 的
      // init 直接失败 (`fatal: not a git repository`, 2026-10-13 实测); gitdir 里只有我们
      // 自己写的东西, sessionRoot 与远端都不受影响
      `if [ ! -e ${shellQuote(posix.join(guestGitDir, 'HEAD'))} ]; then rm -rf ${shellQuote(guestGitDir)} 2>/dev/null; rm -f ${shellQuote(posix.join(guestWorktree, '.git'))}; fi`,
      `rm -f ${shellQuote(guestFile('out'))} ${shellQuote(guestFile('err'))} ${shellQuote(guestFile('code'))}`,
      // 工作树里若有货真价实的 .git 目录 (不是我们那个 gitfile), cwd 发现会拐去用它 —— 响亮拒
      `if [ -d ${shellQuote(posix.join(guestWorktree, '.git'))} ] && [ ! -f ${shellQuote(posix.join(guestWorktree, '.git'))} ];`
        + ` then echo session-sync: ${shellQuote(posix.join(guestWorktree, '.git'))} is a directory, not the sync gitfile, refusing >&2; exit 9; fi`,
    ]
    if (!safeDirectoryReady) {
      steps.push(
        "git config --system --get-all safe.directory 2>/dev/null | grep -qxF '*'"
          + " || git config --system --add safe.directory '*' >/dev/null 2>&1 || true",
        // 同一批: loose object 走 rename 而不是 link —— shell 域在 /data 上被 SELinux 禁了
        // link (avc: denied { link } ... shell_data_file), proot 的 --link2symlink 仿真产物
        // (.l2s 隐藏实体 + 符号链接) 在移动与清理时会碎; 换成 rename 后对象目录里全是普通
        // 文件 (2026-10-13 实测, 见 docs/DSH-LW-3.0.0-G1-施工单.md 第 9 节)
        "git config --system core.createObject rename >/dev/null 2>&1 || true",
      )
    }
    // init 多一条 --separate-git-dir: gitfile 指向沙盒里的 gitdir (其余参数原样, -b 等都在后面)
    const gitArgs = args[0] === 'init'
      ? ['init', `--separate-git-dir=${guestGitDir}`, ...args.slice(1)]
      : args
    const git = [
      'GIT_TERMINAL_PROMPT=0',
      'GIT_OPTIONAL_LOCKS=0',
      shellQuote(gitBin),
      // 每条命令都带上 (系统配置是后来补的保险): 让这条命令与它起的子进程都按 rename 落对象
      '-c',
      shellQuote('core.createObject=rename'),
      ...gitArgs.map(shellQuote),
    ].join(' ')
    steps.push(
      `cd ${shellQuote(guestWorktree)} && ${git} > ${shellQuote(guestFile('out'))} 2> ${shellQuote(guestFile('err'))}`,
      `echo $? > ${shellQuote(guestFile('code'))}`,
    )
    const command = steps.join('; ')

    const result = await channelCall(endpoint, token, {
      op: 'run',
      command,
      timeoutMs: budget,
      work: workspace,
    }, budget + SOCKET_MARGIN_MS)

    let codeText = null
    try {
      codeText = await files.readFile(hostFile('code'), 'utf8')
    } catch {
      // 没写出来 (命令根本没起来 / 桥侧拒了): 把桥带回来的那句话当诊断
    }
    if (codeText === null) {
      const said = typeof result?.text === 'string' ? result.text.trim() : ''
      throw new Error(`the sandbox never reported an exit code${said === '' ? '' : `: ${said}`}`)
    }
    safeDirectoryReady = true
    const code = Number.parseInt(String(codeText).trim(), 10)

    let stderr = ''
    try {
      stderr = await readText(hostFile('err'))
    } catch {
      stderr = ''
    }
    let stdout
    if (runOpts.binary === true) {
      try {
        stdout = await files.readFile(hostFile('out'))
      } catch {
        stdout = Buffer.alloc(0)
      }
    } else {
      try {
        stdout = await readText(hostFile('out'))
      } catch {
        stdout = ''
      }
    }
    return { code: Number.isFinite(code) ? code : 1, stdout, stderr }
  }

  /**
   * 队列化的入口
   *
   * 上一条命令完成后下一条才开始; 取消只影响调用方等不等, 不影响沙盒里的命令与队列顺序
   * @param {string[]} args git 参数。
   * @param {object} [runOpts] { cwd, binary, signal, timeoutMs }。
   * @returns {Promise<{code: number, stdout: string|Buffer, stderr: string}>} 与上游 runner 同形。
   */
  return function run(args, runOpts = {}) {
    const task = () => runOne(args, runOpts)
    const work = queue.then(task, task)
    queue = work.then(() => undefined, () => undefined)
    const signal = runOpts.signal ?? undefined
    if (signal === undefined) return work
    if (signal.aborted === true) return Promise.reject(abortReason(signal))
    return new Promise((resolve, reject) => {
      const onAbort = () => reject(abortReason(signal))
      signal.addEventListener('abort', onAbort, { once: true })
      work.then(
        (value) => {
          signal.removeEventListener('abort', onAbort)
          resolve(value)
        },
        (error) => {
          signal.removeEventListener('abort', onAbort)
          reject(error)
        },
      )
    })
  }
}
