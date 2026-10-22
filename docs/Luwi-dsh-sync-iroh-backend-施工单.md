# Luwi-dsh-sync · iroh P2P 后端 · 施工单 (2026-10-19)

## 0 · 目标

两端 (PC 与手机) 的会话与工作区**不经 GitHub 仓库也能同步**, 且延迟从「一轮 push + 一轮 pull」
压缩到秒级, 对标的是两份通义会话给出的方案 (无服务器 iroh + 扫码配对), 但按本仓的现状重写过:

> 一句话: **iroh 只负责「把字节送到对端」, 同步语义继续用 `lib/merge.mjs` 那套 append-only 三方合并**
> 不另立协议, 不重写冲突策略, 不把 GUI 端口挂出去

四条可验收的行为:

1. 两端在线时, 手机改完一轮 (`sync_pull` / 自动拉) **端到端 < 2 s**, 且不经过任何公网仓库
2. 对端离线时行为与今天一样 (走 git), 只是日志里多一行「对端不在, 这一趟走仓库」
3. 对端地址**不需要公网 URL 或端口映射**: 首连靠 ticket + 6 位指纹确认, 之后靠 iroh 的发现与打洞
4. 冲突语义与今天**逐字节等价**: `identical` / `ours-only` / `theirs-only` / `append-both` / `diverged`
   五档一个不改, `remote-fork-<14位戳>-<设备短id>` 文件名照旧

## 1 · 现状 (2026-10-19 摸的底, 带行号)

| 面 | 事实 | 对这份施工单的含义 |
| :-- | :-- | :-- |
| 传输缝 | `lib/backend.mjs` 里只有 `selectEncryptionMode()` 一个纯函数 + 一段契约注释; `BACKENDS` 只有 `git` / `encrypted` (`lib/constants.mjs:136`) | 「backend seam」是**名义上的**: 真正的编排绑在 git 上 |
| 引擎 | `SyncEngine` (`lib/engine.mjs`) 直接吃 `deps.git`: `schedule` / `bootstrap` / `commitAll` / `push` / `fetch` / `beginMerge` / `conflictedPaths` / `readStageBlob('1'\|'2'\|'3')` / `checkoutStage` / `addAll` / `commitMerge` / `existsAtRev` | 换传输**不能**只换一个类; 要么换 git 远程的传输, 要么另写一个数据面引擎 |
| 冲突语义 | `#resolveConflicts()` 用 `readStageBlob(1/2/3)` 取 base/ours/theirs → `planMerge()` → fork 文件名 `forkFileName()`; 单侧删除有 presence 兜底 (`lib/engine.mjs:398`) | 这套语义是**可以脱离 git 复用的** —— `planMerge` 只吃三个 Buffer |
| 对端通道 | 已经有一条**设备对设备 HTTP 面**: `/luwi-sync/peer/info` `/peer/transcript` `/peer/send` `/peer-live`, 鉴权 `peerHeaders()` 的 Bearer + `sameSecret()` 定长比较 | iroh 最省的第一刀是**给这条面加隧道**, 不是新写一条面 |
| 今天怎么找到对端 | 靠 `publicUrl` / `peer.url` 一个可达地址; 手机上每次状态读要跑一串 git 经 Linux 沙盒, **每条 0.7~1.3 s**, `/peer/info` 冷调实测 5.0 s | 地址是**配置负担**: 没有公网地址就只能靠仓库传话 |
| 手机侧 git | 经桥进 Linux 沙盒 (proot) 起 git, `git --version` 就 2.1 s | 纯 JS 的数据面在手机上比 git 便宜一个数量级 |
| 原生库现状 | `jniLibs/arm64-v8a/libnode.so` (v24.18.0), `useLegacyPackaging = true`; 单 ABI `arm64-v8a`; 宿主树**一个 Android 版 `.node` 都没有** (node-pty 只有 glibc 预编译) | `dlopen` 数据目录下的 `.node` 这条路**没被本仓验证过** —— 这是 T-1 探针要回答的事 |

## 2 · 路线选择

| 路线 | 做法 | 代价 | 结论 |
| :-- | :-- | :-- | :-- |
| A · `ext::` 远程助手 | 保留 git 全部语义, 把 `git fetch/push` 的远端换成一条走 iroh 的 `ext::` 助手脚本 | 改动最小, 合并语义零风险 | **否**: 手机上每条 git 仍是 0.7~1.3 s, 且要维护 `git upload-pack/receive-pack` 的对端形态 |
| B · 只做隧道 | iroh 把现有 `/luwi-sync/*` HTTP 面打通, 仓库同步不动 | 一天的量, 立刻消掉 `publicUrl` 依赖 | **先做 (T0)**: 独立价值, 不赌后面 |
| C · 新数据面 | 另写 `P2pEngine`, 清单差分 + 内容寻址 + 复用 `planMerge` | 2~3 天, 与 git 并存 | **主菜 (T1)** |
| D · 自建 relay + 自写 DDNS | 通义 3239 那份流程图 (固定 UDP 端口 / IPv6 前缀上报) | 一台 VPS + 一个接口, 宿舍与 CGNAT 下不成立 | **不做** |

C 之所以划算: 冲突与合并已经是纯函数, 传输是唯一缺的部分; 而 D 那套把「找得到对方」重新变成运维问题

## 3 · 协议 (T1 数据面)

**ALPN**: `luwi-sync/1` (`Buffer.from('luwi-sync/1')`), 一个对端一条 `Connection`, 每次操作开一条
`openBi()`; 对端用 `acceptNext()` → `Incoming.accept()` → `Accepting.connect()`
**不使用 datagram** (会话文件是几 KB 到几 MB 的块, 走流自带有背压)

**帧**: 每个 `SendStream` 上先写一帧 8 字节头再写体, 体分两种 —— 控制帧是 UTF-8 JSON, 数据帧是原始字节

```text
magic 'LW' 2B | ver 1B | type 1B | bodyLen u32be 4B     合计正好 8 字节
```

`bodyLen` 是体的**精确**字节数; 超过 `MAX_FRAME_BYTES` (64 MiB, 进 `P2P_LIMITS`) 直接拒帧并断流,
数据帧读满 `readExact(bodyLen)` 才算完

> 具体编码以 `lib/p2p-frame.mjs` 的 `encodeFrame/decodeFrame` 为唯一实现, 单测钉死;
> **不引入 msgpack** —— `ARCHITECTURE.md` 的 Roles 一节写着 `lib/` 只依赖 `node:` 内置,
> 控制帧量级是几百字节到几 MB 的 JSON, `node:zlib` 的 gzip 已经够

| type | 方向 | 体 |
| :-- | :-- | :-- |
| `HELLO` | 双向 | `{ver, deviceId, deviceName, caps:['sessions.v1','workspace.v1']}` |
| `MANIFEST_REQ` | 拉方 → 被拉方 | `{root:'sessions'\|'workspace', cursor?, reqId}` |
| `MANIFEST` | 被拉方 → 拉方 | `{root, seq, entries:[[path,size,sha256]], next?}` (gzip; 每页 5000 条, `next` 空 = 完) |
| `NEED` | 拉方 → 被拉方 | `{paths:[...], reqId}` |
| `BLOB` | 被拉方 → 拉方 | `{path, sha256, size}` + 体为文件字节 |
| `COMMIT` | 拉方 → 被拉方 | `{root, seq, merged:[...]}` —— 拉方落盘**之后**的最终清单 (gzip); 被拉方据此更新自己的 base, 不写盘 |
| `RESULT` | 被拉方 → 拉方 | `{ok, adopted, appended, diverged, forks, warnings}` |
| `DIRTY` | 双向 (单向流) | `{root, seq}` —— 变更通告, 对端在线即触发一次 pull |
| `ERROR` | 双向 | `{code, message}` |

**一次 pull 的完整时序**:

```text
拉方                                     被拉方
 │ openBi + HELLO ───────────────────────▶│
 │◀─────────────────────── HELLO (caps)  │  版本/能力不匹配 → ERROR 后断流
 │ MANIFEST_REQ(root) ───────────────────▶│
 │◀───────────────────────────── MANIFEST │
 │ 本地算 diff: 对端有 && (本地没有 || sha256 不同) → NEED
 │ NEED(paths) ──────────────────────────▶│
 │◀──────── BLOB × N (逐文件一条, 校验 sha256)
 │ 逐路径 classifyPath(base, ours, theirs) → 写 / 不写 / fork
 │ COMMIT ───────────────────────────────▶│  对端把它存成新 base, 一个文件都不动
 │◀────────────────────────────── RESULT  │
 │ 更新 base 快照 → adoptRemote() → afterPull()
```

**只在一侧写盘的纪律**: p2p 下 `push` **不传输**, 只发 `DIRTY` 通告; 真正的字节永远是**拉方**写自己
的盘, 于是同一份镜像在同一时刻只可能被一个写者改, 不存在双写竞态, 也不需要 `seqId + ACK + WAL`
重传队列 —— 差分是**状态型**的 (重连后重算 diff 即可), 原方案里那套重传队列在这条路上是多余的

**面板上那个「推送」要改口径**: p2p 后端下它等于「发 `DIRTY` + 尽力等对端拉一次」, 超时就如实
报「对端没来取」(`peerOffline: true`), **不再有「推上去」这个动作** —— `turn/end` 的自动推送同理
这一点必须在面板文案与 `status()` 里说清, 否则用户会以为「点了推送就同步好了」

**幂等**: 每个请求带 `reqId` (16 字节随机 hex), 对端维护 `seenReq` LRU (64 条) 去重; 与
`lib/luwi-inbox.mjs` 按 `msgId` 幂等的纪律同一条

**断点与限流**: 传输以 `BLOB` 为粒度, 中断后重连重算 diff 自然续传; 单文件上限 `maxBlobBytes`
(缺省 8 MiB, 与 `WORKSPACE_DEFAULTS.MAX_FILE_BYTES` 对齐), 超限跳过并进 `warnings` (对齐
`mirror` 的 `oversized` 行为: 不假装同步了)

## 4 · 数据面与合并

**目录布局** (`<repoDir>/.p2p/`, 与 `sessions/` `workspace/` 并列, 不进任何镜像):

```text
node.key                     # 32B 私钥, 0600 (见 §5)
trust.json                   # {peers:[{nodeId,alias,perms,sasOkAt,revokedAt}]}
objects/<xx>/<sha256>        # 内容寻址的字节仓 (base 与历史块的去重载体)
base/<peerId>/<root>.json    # 上一轮同步完成时对端的清单 (合并基点)
hash-cache.json              # path → [size, mtimeMs, sha256] 的缓存 (免得每轮全量哈希)
state.json                   # 每根的 lastSeq / lastError / lastPullAt
```

**合并基点怎么来** (这是 p2p 相对 git 唯一真正缺的东西): git 有 `merge-base`, p2p 没有历史,
所以每完成一次 pull, 就把对端那一份清单落成 `base/<peerId>/<root>.json`, 合并时按路径取:

- `base` 字节从 `objects/` 按 sha256 取 (取不到 = 该路径无基点);
- **无基点时保守化**: `ours == theirs → identical`, 否则 `diverged` + fork, 绝不猜「谁更新」;
- 落盘后的新内容进 `objects/`, 同时更新 base 清单, 旧对象由 `gcObjects()` 保 `base ∪ 当前镜像` 两集合的并集删除其余

**两侧的 base 必须收敛** (这一条写错, 下一轮就会把「对端已经收下的内容」再判一次冲突):

- 拉方落盘完成 → 把**合并后的最终清单**放进 `COMMIT.merged` 发出去;
- 被拉方收到 `COMMIT` 后, 用它**覆盖**自己 `base/<peerId>/<root>.json`, 但**不动任何文件**;
- 于是两侧对「共同祖先」的认知是同一份; 下一轮无论谁拉, diff 的起点相同
- 中途断在 `COMMIT` 之前 (对端没收到) 也安全: 下一轮 diff 以旧 base 起算, 结论只会是
  `identical` 或更保守的 fork, 不会丢字节 —— 代价是多一份 fork, 不是数据错

**复用点 (逐条照抄, 不许另写)**:

| 复用 | 位置 | 说明 |
| :-- | :-- | :-- |
| 三档分类 | `planMerge()` / `classifyPath()` (`lib/merge.mjs`) | 纯函数, 只吃 Buffer |
| fork 文件名 | `forkFileName()` (`lib/paths.mjs`) | 严格 14 位 UTC 戳 + 8 位设备短 id |
| 单侧删除兜底 | `#resolveConflicts()` 的 `presence` 那一段 (`lib/engine.mjs:398-429`) | p2p 的 `theirs === undefined` 就是 modify/delete, **必须照抄**: 该 fork 的一边不在就不 fork, 该保留的一边不在就删 |
| 取回本地 | `adoptMergedSessions()` (`lib/luwi-adopt.mjs`) | 会话根取回; 工作区根走 `mode:'mirror'` + 回收站 |
| 镜像 | `mirrorSessionRoot()` (`lib/mirror.mjs`) | push/pull 前置照旧先镜像 |
| 排除表 | `exclude` / `maxFileBytes` | 工作区根的 `.lw-sync` `.git` 照旧 |
| 脱敏 | `redactText()` / `sanitizeRemote()` | 错误与日志一律过它 |

**`P2pEngine` 的接口**: 与 `SyncEngine` 同形 (`status()` / `push()` / `pull()` + `afterPull` 钩子 +
`set/get deviceId`), 于是 `index.mjs` 的装配点 (`index.mjs:1250`) 只多一个分支
`status()` 额外给读数: `path: 'direct'|'relay'|'offline'`, `rttMs`, `peerDevice`, `lastPullAt`

## 5 · 身份、信任与权限

| 项 | 设计 |
| :-- | :-- |
| 身份 | iroh 端点 ID = ed25519 公钥; 私钥 `secretKey().toBytes()` 落 `<repoDir>/.p2p/node.key` (`mode: 0o600`) |
| 为什么不用 Keystore | 插件在 Node 里跑, 拿不到 Android Keystore; 退而求其次 —— 该目录在 `/data/user/0/<包名>/` 内, SELinux 已把别的应用挡在外面, 再加 0600 与「不打印」纪律 (`maskSecret()` 只回显尾 4 位) |
| 首连 | 一端 `EndpointTicket.fromAddr(ep.addr()).toString()` → 另一端 `EndpointTicket.fromString()` → `ep.connect(addr, alpn)`; ticket 是 base32 串, 可以扫码也可以手输 |
| 指纹确认 (SAS) | 两端把 `[nodeIdA, nodeIdB]` 排序后 `sha256(concat)`, 取前 3 字节对 1000000 取模 → **6 位数字**, 两端必须一致才写 `trust.json`, 这就替代了原方案的 `pair_offer/pair_answer + nonce + 60 s 过期`: iroh 的连接本身就是 Noise + 公钥认证, 缺的只是「这个公钥是不是我要的那台机器」 |
| 权限 | `perms` 三档: `read:sessions` (缺省) / `read:workspace` / `write:prompt`; **没有** `write:workspace` (一条都别给, 先跑起来再说) |
| 吊销 | `revokedAt` 置位后, 该 `nodeId` 的连接在 `acceptNext()` 处直接 `Incoming.refuse()`; 已建的连接按 `Connection.remoteId()` 逐条 `close(0, ...)` |
| 传输加密 | QUIC + Noise 自带, **不再套 TLS**; 需要内容级加密时复用 `encrypted` 后端那套 age 门 (`selectEncryptionMode()` 的判据与降级警告照用) |

## 6 · 配置 (JSON 层 `p2p` 段)

按 `peer` / `live` 两段的形制 (`lib/constants.mjs:66` 那套): **只住 JSON 层, 不进 Schemastery schema**,
缺省值与边界进 `P2P_DEFAULTS` / `P2P_LIMITS`, 由 `lib/luwi-p2p-config.mjs` 读改写 (保留未知键)

```jsonc
"p2p": {
  "enabled": false,          // 缺省关: 新装没有信任表, 开着只会每轮报一次"对端不在"
  "secretKey": "",           // 空 = 首次生成并写回 (hex); 非空 = 32 字节
  "peers": [
    { "nodeId": "…base32…", "alias": "手机", "ticket": "…", "perms": ["read:sessions"], "sasOkAt": 0 }
  ],
  "relay": "default",        // default | none | https://relay.example/
  "bindAddr": "",            // 空 = 默认
  "connectTimeoutMs": 8000,
  "pollSeconds": 5,
  "maxBlobBytes": 8388608,
  "notifyPeer": true,        // 本地变更后发 DIRTY 通告
  "fallbackToGit": true      // 对端不在 / 载入失败时退 git, 但要留一行警告 (不是静默)
}
```

边界 (越界加载期响亮失败): `peers ≤ 8`; `ticket` 长度 ≤ 512; `connectTimeoutMs` 1000~30000;
`pollSeconds` 1~120; `maxBlobBytes` 0~2 GiB; `relay` 三档白名单
`enabled` 与 `backend` 是**两个开关**: `backend` 决定走哪条同步路 (`git` / `encrypted` / `p2p`),
`enabled` 决定插件活不活 (`resolveConfig` 的 `enabled === false` 早退那条)

## 7 · 平台落地

### 7.1 PC

`pnpm add @number0/iroh` 即得 `@number0/iroh-win32-x64-msvc` 预编译 (napi-rs), 无 NDK、无 Rust

### 7.2 Android —— 已实测: 载入没问题, **构造端点必 panic**

| 项 | 实测 | 结论 |
| :-- | :-- | :-- |
| 包 | `@number0/iroh-android-arm64` 1.1.0, tgz 6.61 MB, 解出 `iroh.android-arm64.node` **18.29 MB** | 体积照旧 +18 MB |
| ELF | `AArch64 DYN`, `NEEDED` 只有 `libc/libm/libdl` | 确是 Bionic 构建 |
| 页对齐 | `LOAD ... Align 0x1000` (4 KB) | 16 KB 页机型仍会拒载; 对照 `libnode.so` 是 `0x4000/0x10000` |
| **载入** | **成功**: app 域里 `require('/data/user/0/<包名>/files/iroh.node')` → 25 个导出, 290 ms (模拟器 Luwi_API36, x86_64 + ndk_translation, app 自己的 arm64 `libnode.so` v26.4.0) | 载入路径不是问题 |
| **构造** | **panic**: `Endpoint.bind({alpns})` 与 `presetMinimal(builder)+builder.bind()` **都**在 tokio worker 上炸: `panicked at ndk-context-0.1.1/src/lib.rs:72: android context was not initialized` | 这条路**不可用** |

**根因** (官方 PR [n0-computer/iroh#4183](https://github.com/n0-computer/iroh/pull/4183), 2026-05 合并):
iroh 构造 `DnsResolver` 时会调 `hickory_resolver::read_system_conf`, 它在 Android 上经 `ndk_context`
调 JNI 的 `LinkProperties.getDnsServers()` —— **纯 Node 进程里没有 JavaVM, 必然 panic**。
PR 只补了文档与一个手动的 `iroh_dns::install_android_jni_context`, 而且明确: debug 构建吞 panic
回落公共 DNS, **release 构建让 panic 照常抛** —— 发布出来的 napi 二进制正是 release。

**这推翻了本节原来的「三档载入路径」** (直接 require / 软链 / jniLibs): 三种都能载入,
但三种都逃不掉构造期 panic。手机侧只剩三条路:

| 路 | 做法 | 代价 | 备注 |
| :-- | :-- | :-- | :-- |
| **A · Kotlin 宿主** | app 里用 `computer.iroh:iroh` (UniFFI, 天然有 JavaVM) 托管端点, 经 loopback + token 暴露给 JS 插件 | 官方 Kotlin 产物不含 Android, 需按 iroh-ffi 文档从源码构建; 改的是 app 不只插件 | 与现有 `channel/PrivilegedBridge.kt` 的 loopback 模式同构 |
| **B · Rust sidecar** | 自己编一个 bin: 链接 iroh, 直接给 `DnsResolver` 指定 nameserver (绕开 `read_system_conf`), 监听 loopback | Rust + NDK 交叉编译, 产物进 jniLibs (照 `liblauncher.so` 的做法), 加 `-Wl,-z,max-page-size=16384` | 插件保持纯 JS; 但"自编的 iroh 是否还有别的 Android 特化路径"要实测 |
| **C · 手机侧不用 iroh** | 同网直接走 LAN HTTP (现有 `/luwi-sync/peer/*`), 异地走 git; 或 PC 侧挂 `cloudflared` 隧道把 peer 面映射成一个 HTTPS 子域 | 零原生依赖 | 放弃"无服务器", 但今天就能用 |

PC 侧不受影响, 已实测可用: `bind` 110 ms, `online` 1.26 s (relay `usw1-1.relay.n0.iroh.link`),
两个端点在同机直连 `paths=[{ip:true}]`、`rtt=0`, 一条双向流 `ping` 收得到。
所以三条路都不改动 PC 那一半。

### 7.2.1 Linux 用户态这条 —— 已实测, 而且比 A/B 都省

panic 的前提是 `target_os = android`; 换成普通 Linux, iroh 读的是 `/etc/resolv.conf`, 一个 JNI 都不会碰。

| 实测 | 环境 | 读数 |
| :-- | :-- | :-- |
| Linux 目标能跑 | WSL Ubuntu 24.04 / node 22.23.3 / `@number0/iroh-linux-x64-gnu` | `bind` **36 ms**, `online` **1.16 s**, 连对端 **2 ms**, 路径 `ip:true`; 8 MiB 传完 **856 ms ≈ 9.35 MiB/s** |
| 静态 musl 二进制能直接在 Android 内核上跑 | 模拟器 `Luwi_API36` (kernel `6.6.66-android15`), shell uid, `/data/local/tmp` | `busybox-static` (x86_64-linux-musl) 正常执行, `uname -a` 与 `sh -c` 都通 |
| proot + node | WSL proot 5.1 + Alpine 3.24 + node 24.18.1 | **没验成**: node 在 proot 下连入口脚本都加载不了 (`Cannot find module /tmp/t.cjs`), `-e` 正常; 像是 WSL 特有的 ptrace 行为, 与 iroh 无关, 真机沙盒那条另说 |
| **静态 musl 的 iroh sidecar 在 Android 上端到端跑通** | 模拟器, shell uid, 无 root / 无 proot / 无沙盒 | `bind` **39 ms**, `online` **1.25 s** (relay `usw1-1`), 接受 PC 连接, 收到 8 MiB 用了 537 ms; 这个 `14.89 MiB/s` 是**发送侧**且两端同机, 不作数, 真机读数见 §7.2.3 |

**结论: 手机侧走「静态 musl 的 Rust sidecar」, 不进 proot、不进沙盒**:

1. 用 Rust 交叉编译 `aarch64-unknown-linux-musl` 的 sidecar (链接 iroh, **不**是 android target, 因此没有
   `ndk_context`), 命令是 `cargo zigbuild --release --target aarch64-unknown-linux-musl`
   (zig 当 C 编译器与链接器, `ring` 那部分 C 代码才编得动);
2. 产物当 `jniLibs/arm64-v8a/liblwiroh.so` 发 (与 `libnode.so` 同一个机制: `useLegacyPackaging = true`
   会解出可执行位, 从 `nativeLibraryDir` exec 是 untrusted_app 唯一稳的位置);
3. sidecar 监听 `127.0.0.1:<临时端口>` + 随机 token (照 `PrivilegedBridge` 那一套), 插件用 `fetch` 调它;
4. 于是插件侧不需要原生模块, 也不需要 18 MB 的 napi 包, 体积只加 sidecar 自身 (~21.5 MB 静态).

**这一轮验证到的细节** (都是写代码时会撞上的):

- Rust 侧 `Endpoint::bind(presets::N0)` **不带 ALPN** —— 对端会报 `peer doesn't support any known protocol`,
  必须走 `Endpoint::builder(presets::N0).alpns(vec![ALPN.to_vec()]).bind()`;
- `EndpointAddr` 上是 `relay_urls()` (迭代器), `Connection` 上是 `remote_id()` (不是 `remote_node_id()`);
- Android 上**没有 `/etc/resolv.conf`** (实测 `/etc` 与 `/system/etc` 都没有), sidecar 仍然 online ——
  说明 iroh 走了自带/回退的解析路径, 不需要系统 DNS;
- 产物: x86_64 静态 **21.5 MB**, aarch64 静态 **13.13 MB** (`cargo zigbuild` 出的那份, `NEEDED` 为空,
  `LOAD` 对齐 **0x10000** —— 顺手就满足 16 KB 页机型, 不用再手动加链接参数);
- 工具链: Windows 侧 `D:\apk\rust` 的 rustc 能编 musl, 但 iroh 的 `ring` 要 C 编译器,
  Windows 上没有 `x86_64-linux-musl-gcc` → **实际在 WSL 里编** (`musl-gcc` + `build-essential`);
  aarch64 用 `cargo zigbuild` (`zig 0.14.1`) 最省心。

**剩余待做**: loopback 协议已经接出来并在真机上验过 (§7.2.2 / §7.2.3: daemon 的 `/health` / `/status` /
`/send` 三条都通), 所以只剩两件事 —— 打进 `jniLibs/arm64-v8a/liblwiroh.so`, 以及**真机功耗**
(UDP/QUIC 的连通性已经在真机上量过, 电量还没量)。粗估: 打包 0.5 天, 功耗验收 0.5 天。

### 7.2.2 loopback HTTP 面 —— 已实测 (2026-10-19)

sidecar 的 `daemon` 模式加了三条路由 (`GET /health` 免 token, `GET /status`, `POST /send?bytes=N` body 是 ticket),
只绑 `127.0.0.1`, 鉴权是 `authorization: Bearer <LW_TOKEN>` (定长比较)。模拟器上实测:

| 调用 | 结果 |
| :-- | :-- |
| `GET /health` 不带 token | `200 {"ok":true}` |
| `GET /status` 不带 token | `401 Unauthorized` |
| `GET /status` 带 token | `200` + `id` / `ticket` / `uptimeMs` / `alpn` |
| `POST /send?bytes=8388608` body=ticket | `200 {"ok":true,"bytes":8388608,"connectMs":5760,"sendMs":2247,"mibps":3.56}`; 对端 (WSL 那份 sidecar) 收到 `8388608 bytes in 1966 ms = 4.07 MiB/s` |

**速率要按路径读, 别混成一个数** —— 这台机器上三组配对差别很大:

| 配对 | 路径 (读数) | 吞吐 |
| :-- | :-- | :-- |
| PC(node) ↔ WSL(sidecar) | `ip:true`, rtt 0 ms | **9.35 MiB/s** |
| PC(node) ↔ 模拟器(sidecar) | `relay:true`, rtt 1197 ms | **14.89 MiB/s** (发送侧, 两端同机, 不作数) |
| 模拟器(sidecar) ↔ WSL(sidecar) | 不稳定: 一次 `ip` rtt 2 ms, 重测 `timed out` | 3.56 / 4.07 MiB/s |

第三组是这台机器上最别扭的配对 (两个 NAT 叠在同一个 Windows 后面), 它的 5.76 s 建连与 3.5 MiB/s
**不能当成 iroh 的代表值**。

**这张表上的速率数字要打折看, 2026-10-19 真机复测之后才发现问题**: 前两组的吞吐都是**发送侧**
(写进 QUIC 缓冲就返回) 算出来的, 而且两组配对的两端都跑在同一台物理机的不同容器/虚拟设备里,
所以 `14.89 MiB/s` 这种数在 1197 ms 的 rtt 上根本不可能 —— 那是缓冲区吃下了。**真实速率一律取
接收侧那个数**, 真机上的复测见 §7.2.3 (同一对网络, relay 实际只有 1.24~1.30 MiB/s)。

**一条要记住的**: 用 node 侧 `conn.acceptBi()` 去接 Rust 侧 `open_bi()` 打出来的流, 那个 promise **不返回**
(两端都停在原地: Rust 卡在第一个 `write_all`, node 卡在 `acceptBi`)。同一对立两端都用 sidecar 时一切正常。
所以**两端都跑 sidecar, 插件的 node 只调 HTTP, 不碰 QUIC 流** —— 这正好也是原设计的方向。

**打包流程**: 宿主树在 Windows 上打 (`tools/pack-host.mjs`), pnpm 只解 win32 子包 —— sidecar 要
**显式**放进 `jniLibs/arm64-v8a/` (命名成 `lib*.so`, 借 `useLegacyPackaging` 拿到可执行位);
漏了的表现是手机上 `exec` 找不到文件。

### 7.2.3 真机 (22041216C / Android 14 / 蜂窝) —— 三条通路都过了一遍 (2026-10-19)

设备与两端网络:

- 真机 `22041216C` (xaga), Android 14 / HyperOS V816, `arm64-v8a`, Luwi 2.7.8 在装;
- 手机**只开了蜂窝**: `ccmni0` / `ccmni2` 各拿一个全局 IPv6 (`240e:478:a60:25d4:…` / `240e:578:b08:eb4:…`),
  IPv4 是运营商 CGNAT `10.54.190.208/8`, WiFi 关着;
- PC: 家宽 WLAN 4, 公网 IPv4 (路由 NAT 后) + **全局 IPv6 `240e:479:a78:93e::/64`**
  (`curl -6` 与 `ping6 2400:3200::1` 都通, 默认路由 `fe80::50a1:ffff:fe7f:ed3b`)。

**顺手纠正一句错的**: 前面几轮口头结论里说过"这台机器没有可用 IPv6" —— 那是错的 (当时多半是在 WSL 里
查的, WSL2 默认没有全局 v6)。主机原生有 v6, 所以 IPv6 那条路此前"测不了"其实是**没测**, 不是不可能。

sidecar 在真机上的形态 (与模拟器结论一致):

- `aarch64-linux-musl` 静态二进制推到 `/data/local/tmp/lwiroh`, shell uid 直接 `exec`, 不需要 root / proot / 沙盒;
- 不是 android target, 因此不碰 `ndk_context`; 手机上**没有 `/etc/resolv.conf`**, 照样 online;
- daemon 的 loopback 面用 `adb forward tcp:17522 tcp:7522` 拉到主机上, `curl` 打 `/health` 得
  `200 {"ok":true}`、不带 token 的 `/status` 得 `401`、带 token 得 `id` / `ticket` / `uptimeMs` / `alpn` ——
  **插件将来调的就是这三条**, 真机上已经通了。

五条读数 (8 MiB 与 32 MiB 各一档):

| 场景 | 选中路径 (读数) | 建连 | rtt | 吞吐 (发送侧 / 接收侧) |
| :-- | :-- | :-- | :-- | :-- |
| PC(node) → 真机, 自动选路 | `ip:[240e:478:a60:25d4:…]` **IPv6 直连** | 68 ms | 60 ms | — / **4.20 MiB/s** |
| PC(node) → 真机, 32 MiB | 同上 | **44 ms** | 43 ms | 4.40 / **4.34 MiB/s** |
| 真机 daemon `/send` → PC 监听端 | `ip:[240e:479:a78:93e:f1b2:…]` **IPv6 直连** | 3093 ms | 105 ms | 4.19 / **3.10 MiB/s** |
| 真机只公布 v4 (`listen4`), PC 拨过来 | `relay:https://usw1-1.relay.n0.iroh.link./` | 613 ms | 611 ms | 1.30 / **1.24 MiB/s** |
| WSL (纯 v4) 拨真机 | 同上 relay | — | 1019 ms | 没传完 (`connection lost` / `timed out`) |

四条结论:

1. **IPv6 直连成立, 而且是这里最好的路**: 家宽 ↔ 蜂窝两个独立网络, 44~68 ms 建连、4.3~4.4 MiB/s,
   一个字节都不经过 relay。**这就把"打洞"这件事的必要性拿掉了大半** —— 两端都有 v6 时根本不需要穿透。
2. **打洞在这对网络上不成立**: 把 ticket 裁到只剩 v4 + relay (`listen4`) 之后, iroh 试完直连就退到
   `usw1-1` relay。家宽 NAT ↔ 运营商 CGNAT 属于两端都很难穿透的组合 (CGNAT 多为对称型), 与官方
   [n0-computer/iroh#3183](https://github.com/n0-computer/iroh/issues/3183) 反映的实际打洞率一致。
   **这不是缺陷, 是设计内的降级** —— 对外的连不上率因此要按"有没有 v6"分档说话 (见 §8.1)。
3. **公共 relay 在这个网络上只有 ~1.3 MiB/s、rtt 611~1019 ms**: n0 给这台手机挑的中转是 `usw1-1` (美西)。
   对 Luwi 的含义是"能同步但慢": 8 MB 的会话文件约 6 秒, 全量 275 MB 首同步约 3.5 分钟。要改善只有自建
   香港/日本中转那一条 (前置条件见 §9)。**这条也把 §7.2.2 里 14.89 MiB/s 那个数作废了**。
4. **首连抖动比带宽更值得盯**: `online()` 在同一台手机同一个网络上量到 **1.7 / 2.9 / 3.0 / 17.3 / 28.9 s**
   五个值, 而直连建连只要 44~68 ms。慢的那几次花在 relay 选择与地址发布上, 与传数据无关 —— 所以插件
   要做的是"预热 + 先把隧道起好", 而不是去优化吞吐。

方向性结论: **有 IPv6 就走 v6 直连 (实测 4+ MiB/s), 没有就 relay (1.3 MiB/s)**, 打洞在这套网络组合上
指望不上, 别写进对外口径。

两项没测, 以及为什么:

- **同局域网直连** —— 手机没存这台路由的 WiFi 凭据, 而密码这种东西不索要, 所以没测;
  同网段直连的上限由 WiFi 链路决定 (iroh 只是把 QUIC 跑上去), 不是这套方案的风险点;
- **自建 relay** —— 没有境外 VPS, 施工单 §9 已写清部署前置条件。

### 7.2.4 打包进 jniLibs 与真机功耗 (2026-10-19)

**打包** (已完成, 差最后一步装机验证):

| 项 | 读数 |
| :-- | :-- |
| 落点 | `app/src/main/jniLibs/arm64-v8a/liblwiroh.so`, **13,947,232 字节 (13.3 MiB)** |
| sha256 | `75aeeea75b608f962f46bf10a6e23361137d5b3736d2aa8aa9b2982597399797` |
| ELF | `ET_EXEC` / AArch64 / 静态 / 已 stripped / `NEEDED` 为空 / 四个 `LOAD` 对齐 **`0x10000`** |
| 合并检查 | `:app:mergeDebugNativeLibs` 之后中间产物里那份与源文件 **sha256 一致** —— AGP 不 strip 它 (本来也没有符号), `abiFilters` 与 QNN 那串 `excludes` 都不挡它 |
| 体积代价 | 包里是 **DEFLATED**: 13.3 MiB 原文压成 **6.4 MB**, APK 实际只涨约 **6.4 MiB** (与 `libnode.so` 同一笔账: 68.5 MB 压成 17.9 MB) |

**装机验证 (2026-10-19 22:43, 已完成)** —— 全量 `assembleDebug` + `lw-install.ps1` 之后逐条量到:

- `base.apk` 338,723,481 字节; 包内 `lib/arm64-v8a/liblwiroh.so` 13,947,232 字节 (DEFLATED, 压缩后 6,416,343);
- 解出来的位置是 **`<codePath>/lib/arm64/liblwiroh.so`** —— legacy 布局的目录叫 `arm64`, **不是 `arm64-v8a`**
  (照 `nativeLibraryDir` 拼路径时别想当然), 权限 **`-rwxr-xr-x`**, 大小与源文件一致;
- SELinux 标签与 `libnode.so` / `liblauncher.so` 同为 `u:object_r:apk_data_file:s0` —— libnode 能 exec 的位置它一样能;
- `run-as <包名> <lib>/liblwiroh.so nosuchrole` 打出 `Error: unknown role nosuchrole` (**exec 得动**);
- 以 **app uid** 起 daemon: `bind` **20 ms**, `online` **3.4 s**, `READY port=7523`; 经 `adb forward` 打
  `/health` 得 `{"ok":true}`, 带 token 的 `/status` 有 `id` / `ticket` / `uptimeMs` / `alpn`;
- 从 app uid 的 daemon 推 2 MiB: `path:"ip"`, `remote ip:[240e:479:…]` (IPv6 直连), 建连 1227 ms;
  接收侧 2 MiB / 1076 ms = **1.86 MiB/s** (手机上行, 小载荷里爬坡占大头)。

一条实测出来的注意事项: sidecar 以 app uid 跑起来之后 **shell 杀不掉它** (`pkill: Operation not permitted`),
要用 `run-as <包名> kill <pid>` —— 插件集成时同样要按"自己起的自己收"来管, 别指望外面替它停。

**源码进了 `tools/iroh-sidecar/`** (`Cargo.toml` / `Cargo.lock` / `src/main.rs` / `rebuild-arm64.sh` /
`README.md`)。**注意 `app/src/main/jniLibs/` 整个被 `.gitignore` 忽略** (第 32 行), 所以这个 `.so`
从来不是提交物, 而是本机生成物 —— 与 proot 那套 (`lw-linux-prepare.ps1` 产出的 `liblwproot.so` 等)
同一个处置: 打 APK 之前先跑一次 `tools/iroh-sidecar/rebuild-arm64.sh`, 否则包里没有它
(表现是插件起 sidecar 时 `exec` 找不到文件)。

**功耗** (真机 22041216C; 量的时候电量 8→11%, 正插 USB 充电, 负载 ~12):

| 场景 | CPU (单核占比) | 读数 |
| :-- | :-- | :-- |
| daemon 空闲, 稳态 (180 s 窗口) | **7.88%** | 14.18 s CPU / 180 s |
| 传 8 MiB (91 s 内 8 次 1 MiB) | 10.75% | 9.78 s CPU / 91 s |
| 传输那部分, 扣掉空闲后 | — | 约 **0.4 CPU 秒 / MiB** |
| 内存 | — | RSS **13 MB** (VIRT 36 MB, 9 个线程) |

空闲那个 7.88% **不是启动瞬态**: 30 秒后取第一个窗口是 7.0%, 隔一轮再取 3 分钟是 7.88%;
线程级 `top -H` 看到 8 个 `tokio-rt-worker` 累计时间几乎均摊 (各 12~13 s), 与 25 分钟里
合计约 101 s 的进程 CPU 对得上。

**为什么给不出 mAh** (三条路都堵着, 别把这当成没量):

- 这台机器的 `dumpsys batterystats` 里 **`cpu=` 恒为 0** (没有 CPU 功耗模型), 全机所有 uid 都一样;
- sidecar 跑在 shell uid, `UID 2000` **根本不进功耗账** (没有条目);
- `/sys/class/power_supply/battery/{current_now,voltage_now,power_now}` 是空文件 (SELinux 挡着),
  而 `dumpsys battery` 的 `Charge counter` 分辨率只有 **5 mAh**, 90 秒窗口里我们那点差异小于一格。

**粗估**(只当估计看): 8% 单核按 0.15~0.4 W 计, 电池 4518 mAh × 3.76 V ≈ 17 Wh → **0.9~2.4 %/h**。

**结论 (要改的是生命周期, 不改协议)**: **sidecar 不该 24 小时常驻**。按需起 (同步窗口内),
闲下来就关 —— 正好与插件的 `autoPullOnStart` / `autoPushOnTurnEnd` 对得上, 也与「PC 醒着只有
60~80% 时间」那条产品判断同向。真要做常驻, 得先把这 7.9% 花在哪查清 (大概率是 relay 连接与
net_report/路径探测的周期动作, 没在真机上单独 A/B 过, 别当结论)。

## 8 · 降级与错误码

**绝不静默降级** —— 与 `selectEncryptionMode()` 同一条纪律: 要退就退, 但要留一行警告进 `status` 与日志

| 情形 | 行为 |
| :-- | :-- |
| `@number0/iroh` 载不进来 | `backend: 'p2p'` 时 **响亮失败** `P2P_UNAVAILABLE` (照 `backendUnsupported` 的形制); `p2p.fallbackToGit: true` 时退 git + `warnings` |
| 对端不在 / 超时 | `P2P_TIMEOUT`; 配置了 fallback 就走一趟 git, 结果里带 `peerOffline: true` |
| 指纹没确认 / 被吊销 | `P2P_UNTRUSTED`; 不建连, 不回任何清单 |
| 协议版本不匹配 | `P2P_PROTOCOL`; 断流, 不 half-work |
| relay 限速 | 不报错, `status` 里 `path:'relay'` + 一行「本趟走中转, 可能慢」 |

新错误码进 `ERROR_CODES` (`lib/constants.mjs:177`): `P2P_UNAVAILABLE` / `P2P_TIMEOUT` / `P2P_UNTRUSTED` /
`P2P_PROTOCOL` / `P2P_REFUSED`

### 8.1 连不上的真实面 (2026-10-19 实测 + 官方文档)

iroh 的中继是 **TCP 443 上的 HTTPS → WebSocket**, UDP 只用于直连加速 (官方 enterprise 网那页写明
「outbound UDP 可选, 没有它 iroh 也工作」)。所以失败面**不是**「UDP 被拦」, 而是这四条:

| 失败面 | 说明 | 本仓能做的 |
| :-- | :-- | :-- |
| WebSocket 升级被拦 | 只放行普通 HTTPS、拦 WS 升级的应用层防火墙会让 relay 完全不工作 | doctor 里做一次 WS 握手探测, 结果进 `status` |
| `dns.iroh.link` 被 DNS 过滤 | 地址发布靠它的记录; 被过滤后两端互相找不到 (但 relay 通) | doctor 里查一条 TXT, 失败就退回「手填直连地址」 |
| 公共 relay 抖动 / 限速 | 免费档无 SLA、只支持最新稳定版、按连接限速 | 配多 relay (`RelayMode.customFromUrls`), 客户端按延迟选一个 |
| 打洞失败 | 对称 NAT 打不通; 特定网络下打洞率可能远低于预期 (iroh#3183 有「锥形 NAT 也连不上」的反馈, 2025-09 才关) | 只当加速器, 不当作承诺; 落到 relay 就如实显示「中转」 |

**从上海实测 (2026-10-19 12:18)**: `euw1-1` / `use1-1` / `aps1-1` 三个公共 relay 的 TCP 443 全通,
RTT 198~338 ms (中位约 240); HTTPS 拿到 200, 含 TLS 握手 0.9~1.6 s。结论: 中转档**能连上**,
但一次 pull 要来回若干次, 落在秒级 —— **「实时」这个词只属于直连档**。

所以口径分四档, 别再合成一个「成功率」:

| 档 | 触发 | 实测 / 估 | 延迟 |
| :-- | :-- | :-- | :-- |
| 同网直连 | 手机与 PC 同一 AP | 未测; 上限由 WiFi 链路决定, 与 iroh 无关 | < 10 ms |
| 异地直连 · **v6** | 两端都有全局 IPv6 | **实测成立** (§7.2.3): 建连 44~68 ms, rtt 43~105 ms, 4.3~4.4 MiB/s | 40~110 ms |
| 异地直连 · v4 打洞 | 没有 v6、且两端 NAT 都是锥形 | **实测不成立** (家宽 NAT ↔ 移动 CGNAT): 直接退 relay | — |
| 异地中转 | 直连不成 → relay | 实测能同步但慢: rtt 611~1019 ms, **1.24~1.30 MiB/s** | 往返 2×240 ms 起 |
| 离线 | PC 休眠 / 关机 | — | 退 git, 分钟级 |

**该承诺的三条指标** (可读数验收, 不靠估): ① 同步最终成功 **99.9%+** (git 兜底, 这条是硬指标);
② 直连档可用率按"有没有 v6"分档说 —— **两端都有全局 IPv6 时直连实测成立**, 没有 v6 时**不承诺打洞**
(2026-10-19 真机实测就是失败退 relay); ③ 中转档 p50 延迟 **< 5 s**, 但吞吐要按 **~1.3 MiB/s** 交代。
「PC 醒着」本身只有 60~80% 的时间, 用户体感的失败率主要来自它 —— 那是产品问题 (离线模式 + 提示),
不该混进网络成功率里。

## 9 · 安全边界

1. **不把 host 的 3080 挂到任何 P2P 通道上**: T0 隧道只转发白名单路由 (`/luwi-sync/*`), 且必须过
   `peerHeaders()` + `sameSecret()` 那一道; 转发目标写死 `127.0.0.1:<host 端口>`
2. p2p 数据面**只读写镜像目录与 `<repoDir>/.p2p/`**, 不触任何别的路径; 落盘前过 `paths.mjs` 的
   路径包含检查 (与 mirror 同一条纪律)
3. 清单里的 path 一律按 `path.posix` 归一化后再拼绝对路径; 拒绝 `..`、绝对路径、含 NUL 的路径
   —— 对端是**别人**, 它的清单就是不可信输入
4. 所有对外读数过 `sanitizeRemote()` / `maskSecret()`: ticket 与 key 只回显尾部 4 位
5. relay 默认用 n0 公共网 (免费、限速、官方口径是 hobby 档); 要稳就 `relay: "none"` 只走直连,
   或自建 `iroh-relay` (`Limits::client_rx` 缺省就是不限速) —— **不自建也能用, 只是慢**

## 10 · 测试与验收

**单测** (无原生依赖, 进 `node --test`):

| 文件 | 钉什么 |
| :-- | :-- |
| `test/p2p-frame.test.mjs` | 帧编解码往返 / 截断帧 / 超长 body 拒绝 / 非法 magic |
| `test/p2p-manifest.test.mjs` | 差分 (对端有本地无 / 哈希不同 / 删除传播 / 大小写与路径归一化) |
| `test/p2p-merge.test.mjs` | 无基点退化 / 有基点五档 / 单侧删除 presence 兜底 (与 `engine` 同结论) |
| `test/p2p-trust.test.mjs` | SAS 6 位计算 (排序无关) / 吊销后拒绝 / 保留未知键 |
| `test/p2p-degrade.test.mjs` | 载入失败三条分支 (含「响亮失败」与「退 git 带警告」) |

**e2e** (`test/p2p-loopback.test.mjs`, 缺省 skip, `LW_P2P_E2E=1` 才跑): 同进程两个 Endpoint,
各自 `Endpoint.builder()` + `presetMinimal(b)` + `b.alpns([alpn])` + `b.bind()` (不碰 relay 与发现, 纯本地),
传 3 个文件 + 造一个 `append-both` 冲突, 断言 fork 文件名与 `summary` 与 `SyncEngine` 同路径结论一致

**判据**:

```powershell
. D:\apk\env.ps1
cd D:\apk\Luwi\host-plugins\luwi-dsh-sync
pnpm install            # 本机这份目录里没有 node_modules, 不装会 ERR_MODULE_NOT_FOUND, 不是测试坏了
node --test "test/*.test.mjs"
$env:LW_P2P_E2E=1; node --test test/p2p-loopback.test.mjs
```

**真机三档** (每次出包后跑, 读数进 `logs\device\`):

| 档 | 网络 | 判据 |
| :-- | :-- | :-- |
| 同 Wi-Fi | 两端同一 AP | `status` 里 `path:'direct'`, `sync_pull` 端到端 **< 2 s** |
| 异地 · 有 v6 | PC 宽带 / 手机 5G, 两端都拿到全局 IPv6 | `conn.paths()` 里那条选中的 `remote` **带 `[…]` 方括号** = v6 直连 (2026-10-19 实测: 44~68 ms 建连) |
| 异地 · 无 v6 | 关掉 v6 或走 CGNAT | **不要求**直连; 落到 relay 就如实显示 `path:'relay'` + 「本趟走中转, 可能慢」 |
| 离线 | 手机关 Wi-Fi 与数据 | `P2P_TIMEOUT` + 一行警告, 配置了 fallback 时仍能走 git 完成一次同步 |

**判直连别只看 `isIp`** —— v6 直连与 v4 打洞在 `paths()` 里都是 `isIp:true`, 区别在 `remote` 的地址族;
2026-10-19 那次"看着像打洞成功"的读数, 加上地址一查就是 v6 直连 (§7.2.3)。

**量化对比** (同一批会话, 10 轮): 现有 git 路径 (手机侧每条 git 经 proot 0.7~1.3 s) vs p2p 路径,
把「一次 pull 的墙钟时间」与「一次 `sync_pull` 触发的 git 进程数 (应为 0)」都记下来

## 11 · T-1 探针 (先做, 半天)

三个问题, 每个都有明确的读法:

```powershell
# ① PC 这一侧能不能载入 (预期: 能 —— 这包是 CommonJS, 可以直接 require)
. D:\apk\env.ps1
cd D:\apk\Luwi\host-plugins\luwi-dsh-sync
pnpm add @number0/iroh
node -e "const i=require('@number0/iroh'); console.log(Object.keys(i).length, typeof i.Endpoint.bind)"

# ② 手机 app 域里能不能 dlopen 数据目录下的 .node (debug 包可 run-as; 不行就用 su)
#    nativeLibraryDir 从包路径推: /data/app/~~<hash>/<包名>-<hash>/lib/arm64
adb push .lwtmp\iroh-probe\package\iroh.android-arm64.node /data/local/tmp/iroh.node
adb shell "run-as <包名> cp /data/local/tmp/iroh.node files/iroh.node"
adb shell "run-as <包名> /data/app/~~<hash>/<包名>-<hash>/lib/arm64/libnode.so -e \"const m=require('/data/user/0/<包名>/files/iroh.node'); console.log('LOADED', Object.keys(m).length)\""
#    看到 LOADED 就说明路径 1 可行; 看到 dlopen failed / not accessible for namespace 就走路径 2 或 3

# ③ 打通率 (要 ① ② 都过; 不需要 iroh 账号, 靠真的连一次读路径)
#    PC 起端点、打出 ticket; 手机拿 ticket 连; 两端各读 conn.paths() 的 isIp
node -e "
const { Endpoint, EndpointTicket } = require('@number0/iroh')
const alpn = Array.from(Buffer.from('luwi-sync/1'))
Endpoint.bind({ alpns: [alpn] }).then(async ep => {
  console.log('TICKET', EndpointTicket.fromAddr(ep.addr()).toString())
  const inc = await ep.acceptNext()
  const conn = await (await inc.accept()).connect()
  console.log('PATHS', JSON.stringify(conn.paths().map(p => ({ ip: p.isIp, relay: p.isRelay, rtt: p.rttMs }))))
  console.log('RTT', conn.rtt()); await ep.close()
})"
#    isIp:true → 打洞成功; 只有 isRelay:true → 只能中转, 登记进风险表
```

**判定**:

- ① 失败 → 整个方案要先解决 napi 载入, 别的都别写
- ② **已量完, 结论是坏的**: 载入成功 (25 导出 / 290 ms), 但 `Endpoint.bind()` 在 `ndk_context` 上 panic,
  `presetMinimal` 也照炸 —— 纯 Node 进程里没有 JavaVM, 这条路在手机上直接判死 (详见 §7.2 的三条替代路线)
- ③ 已量过一半 (2026-10-19 上海): 三个公共 relay 的 TCP 443 全通, RTT 中位约 240 ms, HTTPS 200。
  **还差两项**, 都要在同一台机器上补: 一次 WebSocket 升级握手 (只放行 HTTPS 的网关会卡在这),
  以及 `dns.iroh.link` 的地址发布记录能不能查到 (被 DNS 过滤则两端互相找不到)。
  若只能走 relay, 「无服务器」的收益就只剩「不依赖 GitHub 私仓」这一条 —— 这时先做 T0 更划算, T1 排后面

## 12 · 排期

| 阶段 | 交付 | 估时 | 依赖 |
| :-- | :-- | :-- | :-- |
| T-1 | 三个探针的读数 + 结论 (含载入路径选型) | 0.5 d | 真机 / 模拟器 |
| T0 | iroh 隧道打通 `/luwi-sync/peer/*`, 面板加一档「走 P2P」读数 | 1 d | T-1 ① |
| T1 | `lib/p2p-{frame,manifest,trust,config}.mjs` + `P2pEngine` + 单测 | 2.5 d | T-1 ① (与 T0 并行也行) |
| T2 | Android 侧落地: **L2 静态 musl sidecar** (见 §7.2.1) — Rust 交叉编译 + jniLibs + loopback 协议 | 1.5 d (另加工具链 0.5 d) | T1 |
| T3 | (可选) 真机上量 UDP/QUIC 与三档网络; 16 KB 页对齐已在自编时带上 | 1 d | T2 |

**合计 5.5 人日** (不含 T3 与 Android UI 上的配对页)

## 13 · 这一版不做

- **不做多端 mesh**: 先「一台 PC ↔ 一台手机」; `peers` 表留数组形状, 但实现只取第一条已确认的
- **不自建 relay、不写 DDNS 接口**: 那是 3239 那份流程图的路子, 本单不采用
- **不动 `SyncEngine` 的 git 语义**: git 那条路一个字节不改, p2p 是并列的另一条
- **不在浏览器侧做 P2P**: WASM 那档 (`wasm-browser`) 与 `WebRTC` 都不碰, GUI 只显示读数
- **不做 iOS**
- **不把 `write:workspace` 权限放出去**: 工作区镜像第一版只给 `read`

## 14 · 风险登记

| 风险 | 概率 | 影响 | 缓解 |
| :-- | :-- | :-- | :-- |
| **Android 侧 napi 模块构造端点必 panic** (`ndk_context` 无 JavaVM) — **已复现** | 已发生 | 手机端不能在 Node 里用 iroh; 必须换形态 | 走 §7.2 的三条路之一 (Kotlin 宿主 / Rust sidecar / 手机不用 iroh); 上游 PR #4183 只补了文档, release 构建不吞 panic |
| 4 KB 对齐在 16 KB 页机型拒载 | 中 | 新机型不可用 | 自编时加 `-Wl,-z,max-page-size=16384`; 或在 `status` 里如实报「这台机器不支持 P2P」 |
| 公共 relay 限速 / 不稳 | 高 | 跨网时慢 | `relay:'none'` 只走直连 + 明显降级到 git |
| 与 git 引擎抢同一个 `repoDir` | 低 | 两套写同一棵镜像树 | `MOUNT_KEY` 已防双挂; 再加一条: `backend` 单值, p2p 与 git **不同时启用** |
| 内容寻址仓膨胀 | 中 | 磁盘涨 | `gcObjects()` 只保 `base ∪ 当前镜像`; `maxBlobBytes` 上限 |
| iroh 1.x API 漂移 | 中 | 升级即坏 | pin 死 `1.1.0`; 载入处集中在一个 `lib/p2p-iroh.mjs` 里, 换版本只改那一处 |

## 15 · 参考

- iroh JavaScript 绑定: <https://docs.iroh.computer/languages/javascript> (`@number0/iroh` 1.1.0, 要求 node ≥ 20.3)
- relay 与限速: <https://docs.iroh.computer/relays/rate-limiting>
- 本仓设计文档: `D:\apk\docs\Luwi-dsh-sync-设计.md` (§11.x 对端通道与面板)
- 相邻施工单: `D:\apk\Luwi\docs\Luwi-dsh-sync-一键配置-施工单.md`
- 本单用到的实测素材: `D:\apk\.lwtmp\iroh-probe\` (两个 tgz 与解出来的 `.node`), 验完可删