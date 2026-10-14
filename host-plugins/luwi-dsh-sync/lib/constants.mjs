// lib/constants.mjs — 词汇表与协议常量（零依赖）。

// 插件标识、命令名与工具名。
// Luwi 改 (G1 收口, 2026-10-14): 包名与插件名从 session-sync / dsh-session-sync 改成
// luwi-dsh-sync。**落盘布局的键一个字节都没动** —— 下面 DOMAIN_NAME / MIRROR_DIR /
// '$DSH_HOME/lw/session-sync.json' 都还是老名字, 这样两台设备零迁移
export const PLUGIN_NAME = 'luwi-dsh-sync'
export const COMMAND_NAME = 'sync'
export const TOOL_PUSH = 'sync_push'
export const TOOL_PULL = 'sync_pull'
export const TOOL_STATUS = 'sync_status'
// Luwi 加 (G1 收口): 只读自检 —— 每个 peer 解析落在哪 / ② 层有没有 pnpm 不认账的包 /
// spawn 能不能跑 git。这一次事故就是它该第一眼看见的东西
export const TOOL_DOCTOR = 'sync_doctor'
// Luwi 加 (G1-2 对端): 跨设备那一条 —— 读对端状态 / 给对端的 agent 发一句话 / 看对端进行中的回答
export const TOOL_PEER = 'sync_peer'

// 版本号（启动行与 /sync doctor 报出来; 与 package.json 的 version 手工保持一致 ——
// 读 package.json 要走 JSON import, 而载入期"少一个 loader 特性"就是一种启动风险）
export const PLUGIN_VERSION = '0.2.24'

// ctx.storageDomain 领域名（同步元数据：设备 id、最近推/拉时间与错误）。
// 下划线命名：storage-domain 的域名正则不允许连字符。
export const DOMAIN_NAME = 'session_sync'

// git 工作树内镜像目录：sessionRoot 的相对镜像落在 <repoDir>/<MIRROR_DIR>/…。
export const MIRROR_DIR = 'sessions'

// Luwi 加（G1-C 工作区镜面）：第二根 —— 工作区根镜像进 <repoDir>/<WORKSPACE_MIRROR_DIR>/…。
// 引擎本身不认这两根，它只认 deps.mirrorDir；两个常量是"两条镜像各自叫什么"的词汇表。
// 会话那根的名字一个字节不动（fork 文件名与 sessionIdOfForkPath 都钉着 'sessions'）。
export const WORKSPACE_MIRROR_DIR = 'workspace'

// Luwi 加：工作区镜面的缺省值（$DSH_HOME/lw/session-sync.json 的 workspace 段）。
export const WORKSPACE_DEFAULTS = Object.freeze({
  // 空 remote 时缺省关（没有远端就没有可推可拉的东西，开着只会每轮报一次失败）
  BRANCH: 'main',
  // `.lw-sync` 是同步自己的窝（工作树、运行文件、回收站都在里面，不排它就是递归镜像），
  // `.git` 是别人的仓库（把一整棵树当字节同步没有意义） —— 这两条从配置里删不掉
  EXCLUDE: ['.lw-sync', '.git'],
  MAX_FILE_BYTES: 8 * 1024 * 1024,
})

// Luwi 加：工作区镜面的配置边界（加载期校验，越界响亮失败）。
export const WORKSPACE_LIMITS = Object.freeze({
  MIN_MAX_FILE_BYTES: 0, // 0 = 不设上限（照推）
  MAX_MAX_FILE_BYTES: 2 * 1024 * 1024 * 1024,
  MAX_EXCLUDE_ENTRIES: 64,
  MAX_EXCLUDE_LENGTH: 256,
})

// Luwi 加：回收站落点（工作区内、`.lw-sync` 底下 —— 天然被排除表挡住，不进镜像）。
// 远端删掉一个文件时，本地那一份搬进这里而不是直接消失（谁也不替用户裁决）。
export const TRASH_DIR = '.lw-sync/trash'

// Luwi 加 (G1-2 对端)：仓库根下的两个派生目录 (都在 <mirrorDir> 之外, 镜像不会碰它们)。
// LIVE_DIR 是"进行中的回答"投影的退路载体 (主路是 /luwi-sync/peer/transcript);
// INBOX_DIR / INBOX_ACK_DIR 是 HTTP 打不通时的消息队列与回执。三处都**只增不改** ——
// 每次重写的派生文件会被判 DIVERGED (见 lib/merge.mjs), 两边各留 fork, 永不收敛
export const LIVE_DIR = 'live'
export const INBOX_DIR = 'inbox'
export const INBOX_ACK_DIR = 'inbox-ack'

// Luwi 加 (G1-2 对端)：`$DSH_HOME/lw/session-sync.json` 的 peer 段缺省值。
// 与 workspace 段同一个形制: **只住 JSON 层**, 不进 Schemastery schema
export const PEER_DEFAULTS = Object.freeze({
  ENABLED: true,
  LISTEN: true,
  PROXY: true,
  TOKEN: '',
  URL: '',
  // 4 s 是"两端都在本机快盘上"的假设, 安卓那一侧不成立: 手机镜像的状态读要跑一串 git 经
  // Linux 沙箱 (proot + /sdcard, 每条 0.7~1.3 s), 冷调一次 /peer/info 实测 **5.0 s**。
  // 于是 4 s 的调用方永远超时 → 退避 60 s → 缓存又过期 → 冷调用再来一次, **永远失败**
  // (2026-10-14 真机上"手机能投影 PC, PC 永远看不到手机"就是这个)。缺省抬到 20 s
  SEND_TIMEOUT_MS: 20000,
  POLL_SECONDS: 3,
  // Luwi 加 (2026-10-14 事故后): **上限**。影子工作区与投影会话是"本机替对端造出来的东西",
  // 一旦没有上界, 任何一处回声都会变成失控的写 (那天真跑出 800 多个分组)。这两个数是硬闸:
  // 超出的部分既不建、也不留 (轮询会把超出来的那些收回)
  MAX_SHADOWS: 32,
  MAX_PROXIES: 64,
})

// Luwi 加 (G1-2 对端)：peer 段的配置边界 (加载期校验)。
export const PEER_LIMITS = Object.freeze({
  MIN_TOKEN_LENGTH: 16,
  MAX_TOKEN_LENGTH: 256,
  MIN_SEND_TIMEOUT_MS: 500,
  MAX_SEND_TIMEOUT_MS: 30000,
  MIN_POLL_SECONDS: 1,
  MAX_POLL_SECONDS: 60,
  MAX_BODY_BYTES: 8192,
  MIN_MAX_SHADOWS: 1,
  MAX_MAX_SHADOWS: 256,
  MIN_MAX_PROXIES: 1,
  MAX_MAX_PROXIES: 512,
})

// Luwi 加 (G1-2 对端)：live 段 (进行中的回答投影) 缺省值与边界。
export const LIVE_DEFAULTS = Object.freeze({
  ENABLED: true,
  MAX_ENTRIES: 40,
  MAX_CHARS: 16000,
  KEEP_MINUTES: 30,
})

export const LIVE_LIMITS = Object.freeze({
  MIN_MAX_ENTRIES: 1,
  MAX_MAX_ENTRIES: 200,
  MIN_MAX_CHARS: 200,
  MAX_MAX_CHARS: 65536,
  MIN_KEEP_MINUTES: 1,
  MAX_KEEP_MINUTES: 1440,
})

// Luwi 加 (G1-2 对端)：影子工作区与投影会话的家。
// 目录在 `$DSH_HOME/lw/` 底下 (从 sessionRoot 的父目录推出来, 不依赖 DSH_HOME 环境变量 ——
// 桌面宿主没有那个变量, 见 docs/Luwi-dsh-sync-设计.md §11.10), 名字里带对端设备 id
// 与工作区名, 于是"每个对端工作区一个本地目录", 且不需要目录联接、不受 /sdcard 的 FUSE 限制
export const SHADOW_ROOT_DIR = 'peer-workspaces'

// 仓库根下的设备身份文件（随机 UUID，提交进仓库供跨设备归属 fork）。
export const DEVICE_FILE = 'device.txt'

// Luwi 加 (G1-D 互为远端)：仓库根下的对端名片目录。每台设备把自己的名片写成
// <repoDir>/peers/<设备 id>.json（deviceId / name / url / updatedAt），跟会话一起坐 git
// 过去 —— /sync peers 与手机设置页 ⋮ 里那几行「打开 <对端名>」读的就是本地镜像里的这一撮
export const PEERS_DIR = 'peers'

// 存储领域 state 表的单例键。
export const STATE_KEY = 'singleton'

// 后端词汇：git 为明文传输，encrypted 为 age 加密的 git 传输（镜像内容
// 加密后再推，见 lib/backend.mjs 的 seam 契约与 lib/encrypted.mjs）。
export const BACKENDS = Object.freeze({
  GIT: 'git',
  ENCRYPTED: 'encrypted',
})

// 预留后端（接口占位，尚未实现）：配置即加载期响亮失败（backendUnsupported）。
// 与 git/encrypted 共享同一 seam（status/push/pull 三方法），只是传输介质不同。
export const RESERVED_BACKENDS = Object.freeze({
  OBJECT_STORAGE: 'object-storage',
})

// encrypted 后端在 git 工作树内放置密文镜像的目录名与单个密文文件后缀
// （每会话文件一个 .age 密文；明文镜像 sessions/ 只存在于本地、绝不提交）。
export const ENC_DIR = 'encrypted'
export const ENC_EXT = '.age'

// Config.confirmVia 的取值。
export const CONFIRM_CHANNELS = Object.freeze({
  AUTO: 'auto',
  USER_QUESTIONS: 'userQuestions',
  APPROVAL: 'approval',
})

// 冲突分类（lib/merge.mjs 三分支合并的产物，命令/工具渲染与事件共用）。
export const MERGE_KINDS = Object.freeze({
  IDENTICAL: 'identical',
  OURS_ONLY: 'ours-only',
  THEIRS_ONLY: 'theirs-only',
  APPEND_BOTH: 'append-both',
  DIVERGED: 'diverged',
})

// 会话事件类型（插件自有；运行时是否 append 取决于宿主是否收录该类型，
// 或宿主 append 是否支持 ignorable 信封，见 lib/gate.mjs 的自适应门）。
export const SESSION_EVENTS = Object.freeze({
  PUSH: 'sync/push',
  PULL: 'sync/pull',
  CONFLICT: 'sync/conflict',
})

// 领域错误码（稳定、可路由）。
export const ERROR_CODES = Object.freeze({
  BAD_CONFIG: 'BAD_CONFIG',
  BACKEND_UNSUPPORTED: 'BACKEND_UNSUPPORTED',
  REGISTRY_UNAVAILABLE: 'REGISTRY_UNAVAILABLE',
  GIT_FAILED: 'GIT_FAILED',
  PUSH_REJECTED: 'PUSH_REJECTED',
  SYNC_DENIED: 'SYNC_DENIED',
  PATH_UNSAFE: 'PATH_UNSAFE',
  AGE_FAILED: 'AGE_FAILED',
})

// fork 文件名模式：<basename>.remote-fork-<UTC 时间戳>-<设备短 id>；镜像与
// 清理永不删除、永不覆盖匹配该模式的文件（两边保留的持久载体）。
export const FORK_NAME_RE = /\.remote-fork-\d{14}-[0-9a-z]{8}(?:\.|$)/iu

// 宿主私有会话产物：会话租约与迁移暂存文件。宿主在会话目录内自建、自清，属于
// 运行时状态而非可同步内容——镜像既不把源侧同名文件复制进工作树，也绝不删除
// 目标侧同名文件（否则一次 pull/commit 会把某设备的瞬时锁状态传播成删除）。
// 名称来源（session-persistence-jsonl）：
// - lease.ts:40  LEASE_FILENAME = 'session.lock'（flock 租约；仅会话打开期间存在）
// - generation.ts:725  `session.migration.<token><suffix>.tmp`（迁移暂存，失败自清）
// 注意：`session.jsonl` / `session.v<N>.jsonl[.zstd]`（session-format/filename.ts 的
// sessionFormatLogFilename + format.ts 的 logSuffix）是**同步载荷**，不在此列。
export const HOST_ARTIFACT_NAME_RE = /^(?:session\.lock|session\.migration\..+\.tmp)$/u

// 默认值（Config schema 的默认与 DEFAULT_* 常量同源；cordis.yml 可整体覆盖）。
export const DEFAULTS = Object.freeze({
  ENABLED: true,
  BACKEND: BACKENDS.GIT,
  SESSION_ROOT: '',
  REPO_DIR: '',
  REMOTE: '',
  BRANCH: 'main',
  GIT_BIN: 'git',
  AGE_BIN: 'age',
  AGE_RECIPIENT: '',
  AGE_IDENTITY: '',
  AUTO_PULL_ON_START: false,
  AUTO_PUSH_ON_TURN_END: false,
  PULL_INTERVAL_MINUTES: 0,
  CONFIRM_VIA: CONFIRM_CHANNELS.AUTO,
  GRACE_MS: 10000,
  COMMAND_TIMEOUT_MS: 120000,
  MAX_OUTPUT_BYTES: 262144,
  COMMIT_NAME: 'luwi-dsh-sync',
  COMMIT_EMAIL: 'luwi-dsh-sync@localhost',
  // Luwi 加 (G1-D)：自己名片的两个字段（$DSH_HOME/lw/session-sync.json 里 deviceName /
  // publicUrl）。都空 = 名片只报"我来过"，/sync peers 与 ⋮ 那几行拿不到 URL 就不渲染打开行
  DEVICE_NAME: '',
  PUBLIC_URL: '',
  REGISTER_COMMAND: true,
  REGISTER_TOOLS: true,
  // Luwi 加 (G1 面板): 官方侧边栏那块「同步」操作面板 (lib/client.js + lib/panel-routes.mjs)。
  // PANEL_SEATS 决定浏览器半装哪些座位 (both | right | left | off —— off 就是"把面板关掉"),
  // PANEL_OPS 决定面板能不能发起写操作 (pull/push); 读面不受它管
  PANEL_SEATS: 'both',
  PANEL_OPS: true,
})

// 面板座位的合法取值 (Config 校验与 resolveConfig 共用; 见 docs/Luwi-dsh-sync-设计.md §11.1)。
export const PANEL_SEAT_MODES = Object.freeze(['both', 'right', 'left', 'off'])

/**
 * 配置键的 camelCase 形态 (DEFAULTS 的 UPPER_SNAKE 键按机械规则转出来)。
 *
 * 三层合并 (C8) 必须按**这一份**键去查显式配置与 JSON 文件层 —— 那两层的键都是 camelCase
 * (`autoPullOnStart` / `panelSeats`), 而 DEFAULTS 是 `AUTO_PULL_ON_START`: 拿 UPPER 去 index
 * camelCase 的字典永远 undefined, 于是"显式 cordis 键 > JSON 文件 > DEFAULTS"整条规则塌成
 * 只有 DEFAULTS。2026-10-14 就是这么被发现的 (composition 套件里 `gitBin: ''` 这类只在
 * resolveConfig 里校验的非法值"挂上了")。从 DEFAULTS 推出来是为了这两份清单不可能漂
 * @param {string} key - UPPER_SNAKE 键。
 * @returns {string} 同一项配置的 camelCase 键。
 */
export function configKeyOf(key) {
  return key.toLowerCase().replace(/_(\w)/gu, (_, char) => char.toUpperCase())
}

/** 三层合并要遍历的 camelCase 键表 (与 DEFAULTS 一一对应)。 */
export const CONFIG_KEYS = Object.freeze(Object.keys(DEFAULTS).map(configKeyOf))

// 配置字段合法性边界（加载期校验，越界响亮失败）。
export const LIMITS = Object.freeze({
  MIN_GRACE_MS: 1000,
  MAX_GRACE_MS: 2_147_483_647, // SubprocessSpawnSpec.graceMs 上限（Node 定时器上限）。
  MIN_COMMAND_TIMEOUT_MS: 1000,
  MAX_COMMAND_TIMEOUT_MS: 3_600_000,
  MIN_MAX_OUTPUT_BYTES: 4096,
  MAX_MAX_OUTPUT_BYTES: 16 * 1024 * 1024,
  MIN_PULL_INTERVAL_MINUTES: 0,
  MAX_PULL_INTERVAL_MINUTES: 10080,
  MAX_COMMIT_MESSAGE_LENGTH: 200,
  MAX_DEVICE_ID_LENGTH: 128,
})

// git 白名单动词（运行时断言；见 lib/git.mjs 的 ALLOWED_VERBS）。
// 归入 lib/git.mjs 会形成 lib 内循环依赖提示，故这里只放展示用的说明常量。
export const GIT_ENV = Object.freeze({
  // 终端凭据提示会让 git 挂起等待输入；可选锁避免只读原语触发锁竞争。
  GIT_TERMINAL_PROMPT: '0',
  GIT_OPTIONAL_LOCKS: '0',
})
