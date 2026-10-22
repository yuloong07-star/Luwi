# Luwi 同步改造 · 扫码配对 + 单仓库 · 施工单 (2026-10-20)

## 0 · 目标 (主人原话)

> Gti 同步: 在电脑端和手机端扫码同步配对, git 仓库填写只要在 pc 端完成, 配对后同步该仓库。
> 用户只需填写同步仓库即可, 不再分会话仓库和工作区仓库, 手机上的同步板块只保留同步开关和周期拉取。
> 开始同步时, 先同步所有工作区, 以及从同步开始后进行会话, 旧对话不同步。

追问后补的两条:

> 关于工作区, 合并是指什么, pc 有 dsh 时, 在有 dsh 的 luwi 会被显示为「dsh（pc）」为什么要合并?
> → **不合并**, 按设备分开 (`workspaces/<设备名>/<工作区名>/`), 手机侧仍然物化进 `dsh（PC）` 那个影子分组。

> 同步开始时, 当前正在进行的对话会同步吗? 我希望可以同步。
> 在对方工作区对话能否传输信息, 使信息仅在对方工作区的 agent 运行而不是在本地运行?
> → 前者: 「旧会话」按**活动**判 (配对时 60 分钟内写过 / 之后写过 / 新建), 当前那一场整场同步;
> 后者: 已经实现 (G1-3 的 `agent/pre-step`), 这一版只是把 peer 段由二维码一并下发。

## 1 · 口径 (定下来的)

| 面 | 决定 |
| :-- | :-- |
| 配对 | PC 面板显示二维码 (`luwi-sync://pair?d=…`); 手机 Luwi 设置里扫码, App 写配置并重启 host |
| 仓库 | 只填一个; 会话与工作区同仓、同一次 commit、同一次 push/pull |
| 会话 | 基线 = 配对时**最近 60 分钟之外**的会话路径与 mtime; 只有「新建」或「之后又写过」的才同步; 首次同步把仍然算旧的从仓库里删掉 |
| 工作区 | 每台设备一根 (host 的 cwd); 仓库里 `workspaces/<设备名>/<工作区名>/`, **不合并** |
| 拉取 | 对端工作区**不写回本机**, 物化进 `$DSH_HOME/lw/peer-workspaces/<设备id>/<名字>/` (即 `dsh（PC）` 那一组); 本机工作区一个字节不动 |
| 投影会话 | 本机的投影/影子会话**永不进仓库** (否则对端多出一场 cwd 指向本机影子的重复会话) |
| 手机 UI | 设置页「同步」段只剩: 总开关 / 扫码配对 / 周期拉取 / 看当前配置 / 一句状态 |
| 手机 dsh 面板 | 只读 (开关与周期在应用设置里改; 面板改完要重启 host, 不做第二处开关) |
| 对端通道 | peer 地址与口令由二维码下发; 设置页不再有手填那几行 |

**明确后果**: 配对会重启 host, 正在跑的那一轮被打断 (它的最后写入落在"当前窗口"里, 整场历史仍同步); 想彻底干净就用一个空仓库配对; 设备改名 = 换一个命名空间。

## 2 · 数据面

### 2.1 仓库布局

```
<repo>/
  sessions/                                  # 不变; fork 命名与 merge 语义都不动
  workspaces/<设备名>/<工作区名>/             # 每台设备各一份; 同名也不合并
  workspace-peers/<deviceId>.json            # 每台设备只写自己那份 {v,deviceId,name,at,workspaces:[{slug,title}]}
  peers/ live/ inbox/ inbox-ack/ device.txt  # 原样
```

### 2.2 `$DSH_HOME/lw/session-sync.json`

新增:

- `pairing: {v:1, id, at, from}` —— 配对 id, 决定"是不是一次新的同步开始"; App 扫码 / PC 面板保存仓库时写
- `sessions.currentWindowMinutes` (缺省 **60**) —— "当前对话"窗口
- `workspace.root` / `workspace.maxFiles` (20000) / `workspace.maxBytes` (1 GiB) —— 工作区源与预算
- `workspace.remote / enabled / branch / repoDir / trashDir` 不再生效 (只 warn)

### 2.3 二维码载荷

`luwi-sync://pair?d=<base64url(JSON utf8)>`, JSON:

```json
{"v":1,"id":"<uuid>","remote":"<git 仓库>","branch":"main",
 "peerUrl":"http://<PC 地址>","peerToken":"<16..256 字符>","from":"PC","at":1770000000000}
```

- 校验两侧各做一遍: `lib/luwi-pairing.mjs` 与 `sync/PairPayload.kt` (scheme / 版本 / 键名由 `tools/check-sync-pairing.mjs` 钉住)
- 载荷里可能有仓库口令: 只经 `POST /luwi-sync/op {op:'pair'}` 的应答回给同一台机器的面板, 不进快照、不落日志; 面板拿到的是一张画好的 SVG, 要文本得显式点「复制配对文本」
- 编码器: `lib/vendor/qrcode.mjs` = qrcode-generator 1.4.4 (MIT) 逐字 vendor

### 2.4 会话基线 (旧对话不同步 / 当前在聊的要同步)

配对 id 变了, 第一次 status/pull/push 时扫一遍 `sessionRoot`:

- `mtime >= now - 60min` → **当前**, 不进基线, 整场同步 (配对重启前的最后写入就落在这里)
- 更老的 → 进基线 `{相对路径: 那一刻的 mtime}`
- 之后: 仍是基线里、且 mtime 一点没变 → 跳过; mtime 变新 (继续聊了) → 自动开始同步
- 首次 pull 合并后、提交前: 把**仍然算旧**的 `sessions/**` 从工作树删掉, 进合并提交; 这一趟不 adopt
- 账本住 storage 域 `session_sync` 的 `sessionBaseline`

## 3 · 改动清单

| 面 | 文件 |
| :-- | :-- |
| 插件 | `lib/luwi-baseline.mjs` `lib/luwi-pairing.mjs` `lib/qr-svg.mjs` `lib/vendor/qrcode.mjs` `lib/luwi-sync-shadow.mjs` (新); `lib/constants.mjs` `lib/mirror.mjs` `lib/engine.mjs` `lib/luwi-workspace.mjs` `lib/luwi-configure.mjs` `lib/panel-routes.mjs` `lib/client.js` `index.mjs` (改); 版本 0.3.0 |
| 应用 | `sync/PairPayload.kt` `sync/QrScanner.kt` `ui/QrScanDialog.kt` (新); `sync/SyncSetting.kt` `ui/SettingsScreen.kt` 两份 `strings.xml` `gradle/libs.versions.toml` `app/build.gradle.kts` (改) |
| 检查 | `tools/check-sync-pairing.mjs` (新), `tools/check-sync-setting.mjs` (改), `tools/fixtures/sync-pairing.json` (新) |
| 打包 | `tools/pack-host.mjs`: 拷插件时裁掉 `node_modules` (单测时挂的 junction 不进 APK) |

## 4 · 验证 (2026-10-20 本机)

| 项 | 读数 |
| :-- | :-- |
| 插件单测 | `node --test "test/*.test.mjs"` → **302 / 302** (新增 github-api 8 条 + `finishExternalPull` 1 条: 网络失败判据 / 大文件 pull / 并集 push / 影子会话排除 / 先清理再取回 / append-only 取回) |
| 应用单测 | `:app:testDebugUnitTest --tests "io.github.yuloong07star.luwi.sync.*"` → **BUILD SUCCESSFUL** |
| 配对契约 | `node tools/check-sync-pairing.mjs` → 20 / 20 |
| 设置段 | `node tools/check-sync-setting.mjs` → 22 / 22 |
| 编译 | `:app:compileDebugKotlin` → BUILD SUCCESSFUL |

### 4.1 · 2026-10-20 晚: git 同步下"对话未同步"的三条根因与读数

真机/PC 一起量出来的现象: 远端只有 3 个会话文件, PC 当前那一场 (`8af83805`) 不在里面;
手机上 `luwi-dsh-sync` 在 22:53 那次 host 重启后**根本没挂上** (`Cannot find module
lib/luwi-remote.mjs`), 挂上之后 git fetch 又先在沙盒里等满 120 s。

| # | 根因 | 修法 |
| :-- | :-- | :-- |
| 1 | 手机 host 树是 18:36 的 APK + 22:52 手工补的几份文件, 漏了 `lib/luwi-remote.mjs`; 22:53 起插件 import 失败, 一台机器上整个同步引擎没起来 | 整包重下插件目录 (0.3.1), 重启 host; 启动行 v=0.3.1 |
| 2 | API 退路认不出手机桥的超时 (`the sandbox never reported an exit code … did not finish within 120000ms`) 与 SSL 断线, 退路一次没跑过 | `looksLikeNetworkFailure` 补上这两类; 命中后置 10 分钟冷却, 冷却期内不再先等git |
| 3 | API 推的是 `git ls-tree HEAD` 的旧树 (commit 失败被 catch 掉), 且 pull 会删本地独有文件、跳过 >512 KiB 的会话日志; pull 之后也没有"取回本地 sessionRoot" | push 改 `git add -A` + `write-tree` 的工作树快照, 并与远端树取**并集**; pull 只写不删, 大文件照下, 再走 `finishExternalPull` 取回会话/消费队列/收口首次清理 |

同一批附带: 陈旧 `.git/index.lock` 自愈 (PC 上真卡过 `Unable to create '…/index.lock'`),
影子工作区 (`dsh（PC）` / `dsh（手机）`) 里开的会话永不进仓库 (PC 上量到 1902 个)。

当晚读数:

- 只读真拉: 临时工作树 `pull()` → `written: 22`, 其中 `session-9b739d66…` **5,830,339 字节** (旧版会跳过)
- 一次性并集推: 远端 head `c5088817`, `sessions/` 里 **3 个** 文件, 含 `8af83805` **937,052 字节**; 两个影子会话已从远端删掉
- 手机 (v0.3.1, 23:17:59 重启) `lastPullAt` 更新, 面板 `meta.lastPullAgeMs` 秒级;
  PC 那一场已在手机本地会话库:
  `files/dsh-home/sessions/--D-apk--/session-8af83805…/session.v4.jsonl.zstd` = **937,052 字节**
- 手机镜像 `sources`: `neverSynced: 1902` (影子会话), `beforeBaseline: 298` (旧对话), `unchanged: 3`

### 4.2 · 2026-10-21 续修: 手机拿到 1.6 MB 会话之后的"大文件/续写"四件事

PC 侧重启 (13:00:43, v0.3.1) 后 `lastError` 为空, `lastPullAt` 秒级; 手机在 06:56 把
`8af83805` 取到 **1,629,987 字节** 并写进本地会话库 —— 说明"取回"这一环通了。但当晚到第二天
又量出四件只在手机上出现的事:

| # | 现象 | 根因 | 修法 |
| :-- | :-- | :-- | :-- |
| 1 | Node 的 `fetch` 对 api.github.com 一直挂到超时, 而同一网络里沙盒的 `wget` 1 s 就通 | 手机 Node 默认按 "verbatim" 解析, 优先连 AAAA, 而这个网络的 IPv6 走不通 | `process.platform === 'android'` 时 `dns.setDefaultResultOrder('ipv4first')` |
| 2 | 1.6 MB 的 blob 只下到 ~850 KB 或 0 字节, undici 仍回 200 (正文是半截 JSON) | `call()` 的 60 s 上限不够 (实测要 93 s), 且截断的 200 没被校验 | 超时提到 180 s; 按 `blobShaOf(content) === entry.sha` 校验, 最多重试 3 次; 坏数据不落盘 |
| 3 | 取回的 1.6 MB 只停在镜像工作树, 本地会话库还是 937 KB | `adopt` 的 bring-missing 只补缺失, 不更新已存在的文件; 而手机上的那一份是**远端会话的旧快照** | 会话日志"更长的那一份就是更完整的那一份": pull 与 adopt 都按长度更新 (工作区文件仍按前缀/HEAD 判断) |
| 4 | 手机 `git add -A` 在沙盒里超过 120 s, 整条 pull 卡死 (留下 25 分钟的 `sh`) | 工作区里还有几 MB 的图片, `add -A` 要把整棵树都 hash 一遍; 而 API pull 的本地记账并不需要全树 | pull 只 `git add -- <这一趟写过的路径>`; 这一步失败只 warn, 不把已写好的文件判成没拉到; `pullSync` 加单飞, 防止多条下载叠在一起 |

同一天读数:

- 远端 head `1d4fa13` (13:07:58), `sessions/--D-apk--/session-8af83805…` = **2,267,231 字节**
- 手机本地 `8af83805` 在 **13:09 = 2,267,231 字节**, `lastPullAt` 13:09:26, `lastError` 为空
  —— 手机把 PC 13:02 之后又长出来的那一版完整拉下来了 (大文件 + 续写两条修法都在真机上过了一遍)
- PC 13:00:43 重启后 `lastPullAt` 13:01:59, `lastError` 为空; 13:02 面板推一次 `via api`,
  `head 103053e2`, 15.7 s; 13:07:58 又自动推了一次到 `1d4fa13`
- 手机 13:10:05 用最新 push 代码重启 (只 write-tree, 不再重复 `add -A`), 13:11 左右 adb 再次掉线

### 4.3 · 手机 push 实测与"引号路径"回马枪 (13:17-13:24)

手机在 13:17:59 推了一次 (`5795491`), 远端第一次出现手机 cwd 的会话:
`sessions/--storage-emulated-0-DSH-Luwi--/session-58a54f59…` = **61,310 字节**。PC 13:23:58
那次 pull 把它取回本地:
`E:\Mobile-AI\dsh-home\sessions\--storage-emulated-0-DSH-Luwi--\session-58a54f59…\session.v4.jsonl.zstd`
= **61,310 字节** (`lastError` 为空)。**两个方向至此都通了。**

但这次 push 也带出两个只在"手机上 push"时才出现的 bug:

| # | 现象 | 根因 | 修法 |
| :-- | :-- | :-- | :-- |
| 1 | 远端多出 `"workspaces/…`、`"workspace-peers/…` 这种**带前引号**的树; PC 的 pull 报 `ENOENT: mkdir '…repo\"workspace-peers\…'` | 手机的 `git ls-tree` 把中文/空格的路径 (`Luwi-手机`、`…-Luwi 手机` 的 fork) 包成 `"…"` 引号形式, 旧解析把引号当成路径的一部分 | `git ls-tree -r -t -l -z` (NUL 分隔、不转义) + `git -c core.quotepath=false diff`; 远端那 3 个引号 blob 用一次性并集推清掉 (`f74df06`, `removed: 3`) |
| 2 | 手机 push 把 PC 的 `8af83805` 从 2,332,851 **缩回** 2,267,231 | 并集规则是"本机同名覆盖", 而手机本地那一份是 PC 会话的旧快照 | `sessions/` 同名时**远端更大就保留远端**; 手机的旧快照不再覆盖更完整的远端版本 |

清完之后的读数 (13:24):

- 远端 head `f74df06`, 引号路径 **0 条**; `8af83805` = **2,452,990 字节**; 手机会话 61,310 字节在
- PC `lastPullAt` 13:23:58, `lastError` 空, 手机会话已在本地会话库
- 手机本地 `8af83805` 还是 2,267,231 (等下一次 pull 按"更长的那一份"更新到 2,452,990)
- **待部署**: `-z`/引号/远端更大 这版只在源码与 PC profile; 手机 adb 又掉线, 手机运行中的
  `github-api.mjs` 还是 13:10 那版。**在把新文件推上手机并重启 host 之前, 手机上再 push 可能
  再次带出引号路径** —— 下一次连上先做这一步

### 4.4 · 13:25-14:00 手机 pull 仍卡 (留给下一场)

这一轮把手机 pull 的 git 依赖一层层剥掉了, 但 `lastPullAt` 仍停在 13:09:

| 时间 | 改动 | 结果 |
| :-- | :-- | :-- |
| 13:25 | 手机加载 `-z` 引号/远端更大版 | 引号路径不再产生; 拉取仍卡 |
| 13:33 | API pull 在 Android 上跳过本地记账 | 镜像写到了 2.4 MB, 但本地会话库没更新 |
| 13:41 | 手机 pull 不提交 (只 `#mirrorOnly`) | 仍卡; lastError 是 skip, lastPullAt 不变 |
| 13:53 | 手机 pull 整条走 `preparePull` + `apiTransport` (一条 git 不发) | 仍卡 |
| 13:58 | 首次清理的 `git.addAll` 在 Android 跳过; `afterPull` 在 Android 不 await | 仍卡 |

当前读数 (14:00): 手机 `lastPullAt` 13:09, `lastError` 仍是旧的 "git network skipped",
`purgePending=True` (baseline `entries=3663 current=5`); 本地 `8af83805` 是手工从 PC
`run-as cp` 进去的 **2,783,015 字节** (13:57), 不是自动取回。

**最可疑的一步**: `finishExternalPull` 里 `purgePending=True` 时, 会先 `purgePaths()` 扫
整个 sessionRoot (3663 条) 并逐条 `stat` 镜像, 然后才 adopt。这个扫描在手机上很慢/卡住, 于是
adopt 永远轮不到, `lastPullAt` 也不更新, `purgePending` 一直 true。为什么 13:xx 又变成 true:
`ensureBaselineState` 发现 `stored.pairId !== pairing.id` 就重新捕获了 baseline (current=5),
并置 `purgePending: true`。

**下一场优先做** (三条已在 §4.5 收口):

1. 查 pairing id 为什么变了 (或 `ensureBaselineState` 的判据), 避免每次重捕获都重新清理;
2. 把首次清理改成**不挡 adopt**: 先 adopt, 或后台跑, 或把扫盘结果缓存一次; 手机上的 3663 条
   扫描绝不该出现在每次 pull 的关键路径上;
3. 手机 host 已加载最新代码 (engine `32c99248`, github-api `f0e5a054`, index `e2efd077`),
   再重启一次即可复测; 复测判据 = `lastPullAt` 前进 + `purgePending` 变 false + 本地
   `8af83805` 自动到 2,783,015/最新远端, 而不是靠手工 cp。

### 4.5 · 2026-10-21 傍晚: 根因是 storage 域 open 时把 `sessionBaseline` 抹掉 (已修, 三条判据全过)

**根因不是 pairing id 变了**, 是插件自己那条 state 记录的 schema 在每次 host 启动时把它剪掉:

- storage 域 `open` 会对每条已存记录跑一次 `valueSchema.parse` (`packages/storage/storage-domain`
  的 `open`), 而 `lib/domain.mjs` 的 `syncStateSchema` 只列了五个元数据字段 —— z.object 默认把
  **没列出来的键静默删掉**, 于是 `sessionBaseline` (还有对端那套 `peerProxies` / `peerHidden` /
  `peerEpochAt`) 读回来就没了;
- 于是 `ensureBaselineState` 里 `stored.pairId !== pairing.id` 成立 → 重捕获 → `purgePending: true`
  → 首拉前先扫整个会话存储 (手机 3669 个文件), adopt 永远轮不到 → `lastPullAt` 不动;
- 铁证两条: 手机那份基线 `capturedAt` = **13:58:38**、PC 那份 = **13:01:02** —— 正好是两台 host
  各自重启的时刻; 而配置文件里 `pairing.id` (`409db706-…`) 从 2026-10-20 22:04 起一个字没动。
  用 host 里那份 zod 4.4.3 复现: 一条带 `sessionBaseline` 的记录 parse 回来只剩那五个字段。

**改了什么** (源码在 `host-plugins/luwi-dsh-sync`):

| 文件 | 改动 |
| :-- | :-- |
| `lib/domain.mjs` | `syncStateSchema` 加 `.catchall(z.unknown())` —— 插件自己的键原样过一遍, 以后往这条记录里加字段不必再回来改 schema |
| `lib/luwi-baseline.mjs` | 新增 `localStillOld` (按路径现量一次 mtime) 与 `oldMirrorPaths` (清理清单**只走镜像**: 镜像里有多少个文件就查多少个, 与 sessionRoot 规模无关) |
| `index.mjs` | `purgePaths` 换成 `oldMirrorPaths`; `done` 顺手记 `purgedAt`; 捕获/复用各打一行 (捕获那行带原因: 没有账 / pairing 变了 / 账坏了), 于是"又被重捕获了"一眼看得见; 取回那两处都带上 `skipOld` |
| `lib/luwi-adopt.mjs` | 新增 `skipOld` 与 `skipped` 读数: 基线里仍然算旧的一条都不取回, 源文件已经不在了也算旧 |
| `lib/engine.mjs` | `finishExternalPull` **先取回、后清理** (取回自己守着"旧的不导回来", 清理不再挡路); `#pullInternal` 不再因 `purged > 0` 整趟跳过取回 |
| `lib/mirror.mjs` | 会话日志**不缩**: 本机那份比镜像里那份短就不覆盖 (新发现, 见下) |

**mirror 那条是实测撞出来的**: 把手机本地 `8af83805` 截到 1,000,000 字节之后, 镜像我手动补回
完整那一份, 而下一趟 pull 又把本地那份短字节覆写进了镜像 —— 于是本地与镜像双双停在半截, 而远端
head 没变时取回根本不会再物化一遍 (API pull 只在 head 变了才重走树)。会话日志是 append-only,
短的那一份一定是旧的/坏的, 所以现在镜像里更长就保留, 让取回那一趟把本地补回去。

**手机复测 (2026-10-21 18:19 与 18:34 各重启一次 host, 插件 v0.3.1 热更)**:

| 判据 | 读数 |
| :-- | :-- |
| `lastPullAt` 前进 | 18:16:38 → 18:23:05 → 18:32:08 → **18:50:25**, `lastError` 为空 |
| `purgePending` 变 false | false; 基线 `capturedAt` **一直停在 13:58:38** (= 复用, 没再重捕获), `pairId` 仍是 `409db706-…` |
| `8af83805` 自动到最新远端 | 本地被截到 1,000,000 之后, **18:50:25 由 pull 自己写回 2,875,930**, 全程没有一次 `run-as cp`; 取回后文件的 git blob sha1 = `aff70185…` = 远端 blob oid (`76aca89` 那个 head 上的那一份) |

单测 302 → **307 全过** (`test/luwi-state-schema.test.mjs` 钉"账本过一遍 schema 还在"、
`luwi-baseline` 钉两个新判据、`luwi-sync-sources` 钉"取回在前、清理在后"、`luwi-baseline` 钉
"镜像不缩 + 取回能补回来")。

**还没收的两件** (都排除了卡死的性质):

- 手机上一趟 pull 要 4~8 分钟 (工作区那根 + API 树物化), 所以 `lastPullAt` 也就 4~8 分钟跳一次;
  它不影响三条判据, 但耗电, 下一步该量的是 `apiTransport.pull` 里 blobs 的下载耗时;
- 镜像根上 `device.txt.remote-fork-*` 每趟 pull 多一条 (14:06 ~ 16:05 共 9 个), 是两端都改过
  `device.txt` 的 keep-both 产物; `pruneRepoSessions` 能清但没自动跑 —— 要么写成每 N 趟清一次,
  要么让 device.txt 不长在根上;
- **PC 侧还没重启 Desktop**: 新代码已经拷进 profile, 下次启动会复用 13:01 那份基线 (不再重捕获),
  在那之前 PC 内存里跑的还是旧 schema。

## 5 · 真机扫码测试步骤

1. **PC 侧装新插件**: 已把 0.3.0 拷进 `E:\Mobile-AI\dsh-home\profiles\desktop\node_modules\luwi-dsh-sync`(旧版备份在 `D:\apk\backups\luwi-dsh-sync-0.2.24-profile-before-0.3.0-*`)。**重启 DSH Desktop** 才会加载 (启动行应出现 `v=0.3.0` 与 `baseline=…`)。
2. **PC 面板**: 打开「同步」面板 → 操作页 → 填仓库地址 (手机能访问到的 git 远端, http(s)/ssh; PC 本地路径只有 PC 自己能用) + 访问令牌 (私有仓库) → 分支按需 → 工作区根按需 (缺省是 host 的 cwd, 通常会被预算跳过) → 保存 (会生成 pairing id)。→ 点「生成配对码」, 面板里出二维码。**2026-10-20 主人要求删掉「手机要访问的本机地址」那一栏** —— 它只给对端通道用, 与同步无关; 二维码里仍用配置里/profile 里已有的值。
3. **手机**: 装新 APK 后打开 Luwi → 设置 → 同步 → 扫码配对 → 扫 PC 面板上的二维码。成功后 App 自动重启 host。
4. **PC**: 配对码是面板直接读文件生成的, 不需要再重启; 但 **PC 的同步引擎仍要重启一次 Desktop 才会按新配置跑** (配对写盘 → 插件挂载时读)。重启后两端开始推拉。
5. 验收看三样: 手机「看当前配置」里 `remote/branch/peer/pairing` 都在; 仓库里出现 `sessions/` 与 `workspaces/<设备名>/`; 手机 GUI 的 `dsh（PC）` 分组里能看见 PC 的工作区文件。

## 6 · 已知边界

- 旧会话 = 配对前 60 分钟外、之后没再写过; 继续聊会自己走上来
- 仓库里早就不在本机的旧会话文件清不掉 (基线只覆盖本机路径)
- 工作区首推可能很大: 预算 (20000 文件 / 1 GiB) 超了整根跳过, 面板会说跳过原因; 排除表默认挡 `.git/.lw-sync/node_modules/build/.gradle/.kotlin/...`
- 设备改名 = 新命名空间 (旧目录留在仓库里, 面板列为未认领)
- PC 的 `publicUrl` 若写在 profile 覆盖行里会压住面板那个框 —— 生成配对码时**优先读文件层**, 面板填的会生效
- 手机 dsh 面板只读; 开关与周期在应用设置里改 (周期拉取热更新, 不用重启; 开关会重启)

## 7 · 私密仓库 (2026-10-20 主人问"这个地址只能是公开地址吗")

**必须是私有仓库, 而且没有任何代码要求它公开** —— 仓库里装的是会话日志与工作区文件, 公开就等于把它们发到 Internet 上。
"地址必须是手机能访问到的"只是可达性, 与公开/私有无关。三种私有形态:

| 形态 | 地址形如 | 说明 |
| :-- | :-- | :-- |
| HTTPS + 令牌 (推荐) | 地址填 `https://github.com/<你>/<私有仓>.git`, 令牌填在**单独那一格** (`remoteToken`) | 手机侧的 git 跑在 Linux 沙盒里, **没有 credential helper** —— 宿主把地址拼成 `https://<用户名或 x-access-token>:<令牌>@host/…` 再交给 git, **二维码里带的是拼好的那一份**。令牌只落两处: PC 的 `remoteToken` 与手机的应用私有目录 (地址里那份) |
| SSH | `git@github.com:<你>/<私有仓>.git` | 手机沙盒里要有对应私钥, 这一版没有做密钥下发; 不推荐先用 |
| 纯局域网 | `git://<PC 局域网 IP>:9418/<仓>.git` 或经 `adb reverse` 的 `git://127.0.0.1:9418/...` | PC 起 `git daemon`; 内容不出局域网, 地址里没有凭据 |

**令牌那一格 (2026-10-20 主人: "令牌单独填写")**: 面板仓库地址下面就是它, 密码框、不回显、留空 = 保持不动。
用户名写在地址里 (`https://<用户名>@github.com/...`) 就按它; 不写就用 `x-access-token` (GitHub 的 PAT 适用; Gitee 这类请把用户名写进地址)。
`lib/luwi-remote.mjs` 的 `composeRemoteWithToken()` 是唯一实现, 规则三条: 非 http(s) 原样 / 地址里已有口令原样 / 其余按上面拼。
令牌建议: GitHub fine-grained PAT, 只授**那一个仓库**的 `Contents: read and write`; 泄露就吊销重发。
**二维码里会带地址原文 (含令牌)**, 所以面板那张卡上写着"别外传"; 截图/发群之前先想一下。
仓库内容本身是明文 git (插件的 `encrypted` 后端这一版只覆盖会话那根, 统一仓库还没接加密) —— 这也是必须私有的原因。
