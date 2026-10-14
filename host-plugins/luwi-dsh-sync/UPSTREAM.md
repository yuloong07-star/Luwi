# luwi-dsh-sync (vendored from dsh-session-sync)

**上游**: <https://github.com/PerryLink/dsh-session-sync> (Apache-2.0)
**pin**: commit `c5f0c929685aafae94749868b07e874035b18ba4` (package.json `version` 0.2.24)
**本仓的名字**: 包名 `luwi-dsh-sync`, 展示名 `Luwi-dsh-sync` (2026-10-14 改的名, 之前叫
`dsh-session-sync`; 改名的理由与波及点见 `docs/Luwi-dsh-sync-设计.md` §3。**落盘布局键一个字节没动**:
`.lw-sync/session-sync/repo` / storage 域 `session_sync` / `$DSH_HOME/lw/session-sync.json` 还是老名字)
**怎么来的**: 2026-10-12 clone 到工作区 `.lwtmp\g1\dsh-session-sync`, 2026-10-13 拷进本目录
(D:\apk 工作区的 G1 施工项, 见 `docs/DSH-LW-3.0.0-G1-施工单.md`)

## 树上动了什么 (对照上游 pin)

| 文件 | 改动 |
| :-- | :-- |
| `index.mjs` | 文件头 "Luwi 改了什么" 六条, 正文多处 "Luwi 改" 标记: ① 手机侧 git 经桥进 Linux 沙盒 (LW_CHANNEL_ENDPOINT 在时), 电脑侧照旧走 ctx.subprocess; ② 配置多读一份 `$DSH_HOME/lw/session-sync.json` (显式 cordis 配置优先); ③ bridge 模式 repoDir 默认 = `<工作区>/.lw-sync/session-sync/repo`, 必须落在工作区内; ④ 工作区镜面 (G1-C) —— 第二根引擎 + `/sync ws` + `sync_*` 的 target 参数 + 两根各记各的账; ⑤ 互为远端 (G1-D) —— 配置多 `deviceName` / `publicUrl` (加载期校验, url 必须 http(s)), 会话那根镜像前写自己的名片, `/sync peers` 子命令 + `/sync status` 与 `sync_status` 的对端计数 |
| `lib/luwi-bridge-runner.mjs` | **新增** (上游没有, G1-A): 上游 runner 契约接在 loopback 桥 + Linux 沙盒上; worktree 在共享存储, gitdir 在沙盒真文件系统, 两者用 worktree 里的 gitfile (`--separate-git-dir`) 相连, 且每条命令带 `-c core.createObject=rename` (修 /sdcard FUSE 与 SELinux 禁 link 这两条, 见施工单第 9 节) |
| `lib/luwi-adopt.mjs` | **新增** (上游没有): 补上"取回"那一程 —— pull 合并后把工作树里本地还没有的会话文件写回 sessionRoot (上游只合并进工作树, 本地 dsh 看不见, 下一次镜像还会把它们删掉)。G1-C 泛化出第二档 `mirror` 模式: 本地没有就取回 / 字节不同就用合并结果覆盖 / 工作树里没有了就搬进回收站, 且删除必须先由调用方证明"这条路径上一趟真的交出去过" |
| `lib/luwi-workspace.mjs` | **新增** (上游没有, G1-C): 工作区镜面的规格解析与加载期校验 —— 工作区根 / 工作树 / 远端 / 分支 / 排除表 (`.lw-sync` 与 `.git` 是强制项) / 单文件上限 / 回收站, 以及桥模式下工作树必须落在工作区内 |
| `lib/luwi-peers.mjs` | **新增** (上游没有, G1-D): 对端名片的写与读 —— 自己那份落 `<repoDir>/peers/<设备 id>.json`, 身份没变不落盘 (`updatedAt` 只在身份变化时刷新, 否则每轮自动推送都 dirty), `name` 与 `url` 都空不写, 非法设备 id 拒; 读侧跳过坏文件并计数 |
| `lib/engine.mjs` | 文件头标注; 四处: ① deps 多一个可选 `adoptRemote`, 在 commitMerge 之后调用 (不传时行为与上游一致), pull 结果多 `brought` / `updated` / `trashed` 与取回失败的 warnings; ② 多根参数化 —— `mirrorDir` / `deviceFile` / `exclude` / `maxFileBytes` 四个可选依赖 (缺省全是上游值), status 里多排除表与超限两笔账; ③ 单侧删除 (modify/delete) 的冲突兜底 —— 缺席的那一边不 checkout 也不 fork; ④ 可选依赖 `writePeerCard` (G1-D), 每次镜像前调且其返回值参与"要不要提交"的判定 (不传时行为与上游一致) |
| `lib/mirror.mjs` | 文件头标注; `exclude` 在遍历时剪枝 (不下钻) 且被排除的旧副本会被撤回, `maxFileBytes` 超限的文件不复制也不删远端已有那份, 两者如实进回执; 新增导出 `makeExcludeMatcher` 与 `listRelFiles` (取回那边共用同一张排除表) |
| `lib/git.mjs` | 一个方法: `existsAtRev` (`cat-file -e`) —— G1-C 删除传播的判据, "这条路径上一趟镜像真的交出去过吗" |
| `lib/render.mjs` | 文件头标注; 四处: renderPull 多 `brought` / `updated` / `trashed` 三行, 三个渲染多一个可选的 `label` (空串时与上游逐字一致), renderStatus 多排除表与超限两行以及 peers 计数一行 (`peerCount > 0` 才有), 另有 G1-D 的 `renderPeers` |
| `test/luwi-workspace.test.mjs` | **新增** (G1-C): 16 条判据 —— 排除表匹配器 / 镜像剪枝与撤回 / 取回两档 / 规格校验 / 判据 6 的双设备全流程 / 插件装配与 target 参数。**不进 APK** |
| `test/luwi-peers.test.mjs` | **新增** (G1-D): 8 条判据 —— 名片落盘与"身份没变不落盘" / 非法设备 id / 读名片与坏文件计数 / 判据 7 的双设备全流程 (名片坐正经提交, 重推不产生第二份提交, 改名恰好多一次) / renderPeers / 配置校验 / 装配后 status 与 `/sync peers`。**不进 APK** |
| `UPSTREAM.md` | **新增** (本文件) |
| `package.json` | 2026-10-14 收口: `name` → `luwi-dsh-sync`, 描述前缀加 Luwi 身份, 加 `luwi` 段 (forkOf / forkVersion / upstreamCommit / pluginId), `peerDependencies` 的 `@deepseek-ai/cordis` 由 `^4.0.5-alpha.1` 放宽到 `>=4.0.4 <5` (desktop 运行安装是 4.0.4), `dshWorkshop.compatibility.dshVersions` 补 `0.2.0-rc.2` |
| `cordis.patch.yml` | 2026-10-14 收口: 行 id / 包名 → `luwi-dsh-sync`; config 只留与部署无关的开关 (路径与远端交给 profile 覆盖行或 `$DSH_HOME/lw/session-sync.json`) |
| `index.mjs` (收口) | 除了文件头那六条之外, 2026-10-14 又加了载入期安全契约 C1..C9 (见文件头那一节与 `docs/Luwi-dsh-sync-设计.md` §5): 外层 `apply` 只包"绝不抛"、`tryResolveConfig`、7 处 fire-and-forget 补 `.catch`、双挂载自检、Config schema 去掉 `.default` (三层合并才成立)、新增 `/sync doctor` 与 `sync_doctor` |
| `lib/luwi-doctor.mjs` | **新增** (上游没有, G1 收口): 只读自检的纯函数 —— `peersFromManifest` / `parseHoistedLocations` / `resolvePeerPlacement` / `profileExtraneous` / `runDoctor` / `formatDoctor`。零 DSH 依赖、零 fs 依赖 (探测从外面注入), 单测可直接驱动。它量的是 2026-10-13 事故的三件事: peer 解析落点、② 层 extraneous、git 能不能 spawn |
| `lib/luwi-bridge-runner.mjs` (收口) | `readLuwiSyncConfig` 加 64 KiB 上限 (载入期只许读一份小文件), 日志前缀改成 `luwi-dsh-sync:` |
| `lib/constants.mjs` / `lib/errors.mjs` | 标识改成 `luwi-dsh-sync`, 加 `PLUGIN_VERSION` / `TOOL_DOCTOR`, 提交者名与错误前缀跟着改 |
| `test/luwi-doctor.test.mjs` | **新增** (G1 收口): 13 条判据 —— 三层合并 (含"显式 false 不被 JSON 翻") / `tryResolveConfig` 与 `apply` 在毒配置下不抛 / `enabled:false` 不注册任何东西 / 双挂载自检 / doctor 四个纯函数 / `runDoctor` 的健康、污染与"手机那种插件与 peer 同层的树"三条 (最后那条是误报回归: 硬判据只落在 extraneous 一栏) + `formatDoctor` 文本。**不进 APK** |
| `lib/panel-routes.mjs` | **新增** (上游没有, G1 面板): 官方侧边栏面板的宿主半 —— 快照 (`GET /luwi-sync/panel`) / 只读自检与名片 (`/doctor` `/peers`) / 动作 (`POST /luwi-sync/op`)。写操作要 `confirm: true` 且**单飞** (第二个并发写回 409), 读操作随时可跑; `remote` 只回 set/unset + 脱敏串; 目录成本预告带 60 s 缓存。设计见 `docs/Luwi-dsh-sync-设计.md` §11 |
| `lib/client.js` | **新增** (上游没有, G1 面板): 浏览器半, 手写官方动态 bundle 外壳 (`window.__ModuleLoader__.load({id, factory})`, id 必须等于包名)。两个座位 —— 官方右侧栏 tab (`sidebar.right.pane.tab` 两段式 + 能力门 + 可选服务短轮询) 与左侧栏全局入口 (`sidebar.panellist` + `main`); 面板三档页签 (总览 / 操作 / 诊断), 写操作走"两次点击"确认。只依赖基线 (`require("react")`), 不请求任何非基线模块 |
| `index.mjs` (面板) | 接线: `ctx.inject(['webServer'], …)` 起子 fiber 注册四条路由 (webServer 是可选服务, **不写进顶层 inject**); `info` 里带上面板要的配置面。**同时修掉两个既存故障**: ① 末尾 `export {}` 表里重复导出 4 个就地导出的名字 (`SyntaxError: Duplicate export` —— 整棵树 import 不起来); ② 三层合并那圈拿 DEFAULTS 的 UPPER_SNAKE 键去 index 两层 camelCase 的字典, 于是显式 cordis 键与 `lw/session-sync.json` 一个字都进不来, 改按 `CONFIG_KEYS` 遍历 |
| `lib/constants.mjs` (面板) | 加 `PANEL_SEATS` / `PANEL_OPS` 两个缺省、`PANEL_SEAT_MODES`, 以及 `configKeyOf` + `CONFIG_KEYS` (从 DEFAULTS 机械推出的 camelCase 键表, 供三层合并用) |
| `package.json` (面板) | 加 `exports["./client"] = "./lib/client.js"` 与 `dsh.client = { platform: "web" }` (`files` 里的 `lib` 已经覆盖两份新文件) |
| `test/panel-routes.test.mjs` | **新增** (G1 面板): 16 条判据 —— 脱敏 / JSON 体边界 / 成本预告遍历 / 快照形状 / 拒绝路径 (未知 op、未知 target、镜像没开、写面关、缺 confirm) / 只读三条 / 单飞 409 / 引擎抛错降级 / 路由表与 POST-only / dispose 清缓存。**不进 APK** |
| `test/client-panel.test.mjs` | **新增** (G1 面板): 8 条判据 —— bundle id 等于包名 / 三处注册 (座位齐) / `panelSeats` 三档 / 探针失败退回 both / 可选服务缺失时安静不装且轮询定时器随卸载收掉 / 两档渲染 (通道没开 + 有快照)。**不进 APK** |
| `scripts/loader-runner.mjs` | 加两档负例判据 (`invalid` / `inactive`): 按这个 fork 的 C2 契约读插件自己的 `mountState` (裸 Context 没有 logger, 原因印不到 stderr 上; 而"fiber FAILED"在加载器 1.0.4 之后也判不出来) |
| `scripts/verify-artifacts.mjs` | 语法检查把 `lib/client.js` (它是 `.js` 不是 `.mjs`, 原先那张门看不见它) 一并覆盖; host 面契约的断言从旧名改成 `luwi-dsh-sync` |
| `test/manifest.test.mjs` / `test/lifecycle.test.mjs` / `test/composition.test.mjs` | 对齐这个 fork 的现状与契约: 改名后的 `luwi-dsh-sync`; 两条负例的判据从上游的"Loader 响亮失败"改成 C2 的"一行 error + 停用" |
| 装机现场修的三处 (2026-10-14, 首次真挂上才暴露) | ① `index.mjs` 的 `collectDoctor` 给 `git --version` 探针补 `cwd` —— 宿主 subprocess 会拿 `spec.cwd` 去 `value.includes('\0')`, cwd 为 undefined 时抛 `reading 'includes'`, doctor 于是误报"git 跑不动"; ② `index.mjs` 加 `losslessStatus()` 并在四个工具的结果边界使用 —— `collectStatus` 的读数里显式写着 `undefined`, 宿主按"无损 JSON"拒收整条结果 (上游形态在更严的宿主上必失败); ③ `lib/luwi-doctor.mjs` 的 `parseHoistedLocations` 补一刀剁掉同行依赖后缀 `(...)`, 否则账内的包会被误报成 extraneous |
| `test/tool-output.test.mjs` | **新增**: 3 条判据钉"工具结果无损 JSON"这条契约 (掉 undefined 键 / 状态工具交出去的东西 JSON 往返逐键相同 / 读失败那一路也无损)。**不进 APK** |
| 主人报"只有用户的话"那两处 (2026-10-14) | ① `lib/luwi-live.mjs` 的落定判据不再只认 `turn/end` (主路载体 `readSurface` 只回 surface 事件, `turn/end` 是 log-only, 永远不在那一串里) —— 换成"下一轮开了就落定 + 最后那一轮由 `running` 说话", 快照版本 v1 → v2; ② `lib/luwi-peer.mjs` 的对端分组名**永远**带 `（设备名）` (裸名字 = 本机的), 加之前去掉同一设备名的旧后缀 (幂等)。起因、读数与验收清单见 `docs/DSH-LW-3.0.0-G1-施工单.md` §14.14 |
| 主人报"不要同步全部对话"那三条 (2026-10-14) | `index.mjs`: ① `ownSessions()` 只报**未归档 + 相连之后有活动**的会话 (`peerEpochAt` 记在账里, 第一次要报时落成"现在", 重启沿用); ② `hidePeerCopies()` 把上游 `bring-missing` 搬进本机的**对端会话副本**归档隐掉 (判据是它的 cwd 在本机**不存在** —— 对端会话记的是对端文件系统的路径; 少了这半条会把本机自己那一场隐掉, 真机上踩过) + 收掉影子根底下**无主的投影会话**; ③ 对端不再报的投影归入 `peerRetired` 那本账并在本机归档; ④ `agent/pre-step` 的归属方闸三条判据 (投影 / 退下来的投影 / 对端副本) —— 在**对端副本**上打字一个字节都不写本机 (写了就是 APPEND_BOTH); ⑤ `isShadowPath()` 候选那一边也折 canonical (`/data/data/…` 与 `/data/user/0/…` 是同一棵树)。镜像那根没动 (见 `docs/DSH-LW-3.0.0-G1-施工单.md` §14.16 末尾) |
| `lib/luwi-peer.mjs` | **新增** (上游没有, G1-2): `peer` / `live` 两段配置的解析与加载期校验 (令牌 ≥16 字、`peer.url` 只收 http(s)、超时与轮询越界响亮拒)、令牌打码 (`maskSecret` / `sameSecret` 定长比较)、`annotateWorkspaceNames()` (**分组名的唯一实现**: 对端那一侧永远带设备名, 本机那一侧只在同名跨设备时带, 都先去同设备旧后缀)、`shadowDirName()`、`textOfContent()` (只认 text 块, 没有 text 块时给占位) 与 `saysText()` (有没有真的说了话 —— 占位不算) |
| `lib/luwi-live.mjs` | **新增** (上游没有, G1-2): `condense()` —— 把一场会话折成"只有 user 与 assistant 文本"的投影 (回答的落定看"下一轮开没开"与 `running`, `turn/end` 只当退路判据; 只有工具调用的不算回答; 草稿不进条目; 条数与字数两条窗口, 截了如实标记), `liveSnapshot` / `readLiveSnapshot` (对端写的是不可信输入; 快照 v2 = 条目里有已经落定的回答), `liveDirectory` / `liveFileName` |
| `lib/luwi-inbox.mjs` | **新增** (上游没有, G1-2): 退路队列与回执的落盘形状 —— `inbox/<目标设备id>/<消息id>.json` 与 `inbox-ack/…`, 只增不改、路径穿越打掉、按消息 id 幂等 (`rememberId`), 原子写 |
| `lib/luwi-proxy.mjs` | **新增** (上游没有, G1-2): 投影会话的事件折叠 —— `proxyEventsFrom()` (一条用户消息开一轮、一条回答收一轮, 跨轮保存 `openTurn` 与轮号, 本地先追加过的那一句在对端回声里认掉一次), `proxyUserMessage()` / `proxyNoticeEvents()` / `titleOf` / `appendProxyEvents()` |
| `lib/luwi-peer-runtime.mjs` | **新增** (上游没有, G1-2): 通道运行时 —— 四条对端路由 + 一条本机路由、投递 (`resolveAgent` + `followup`/`steer` + `flush`, 照 `Luwi/host-plugin` 的 voiceDeliver)、队列消费与回执、`peerTranscript` (出去问) 与 `transcript` (回答对端的提问) 两个方向、投影读写与清扫、`status` 读数。`/peer/info` 的应答里多一个 `entryVersion` (条目语义版本, 对端拿它决定要不要把旧口径折出来的投影重做一场)。**只依赖注入面, lib/ 仍然零 DSH import** |
| `lib/engine.mjs` (G1-2) | 多一个公开字段 `afterPull`: pull 合并之后调一次 (收队列), fail-soft —— 它抛了不影响这一次 pull 的结果 |
| `lib/sanitize.mjs` (G1-2) | `sanitizeRemote` 修一处漏: 查询键那一圈改成**先**打码 —— 原来它在 userinfo 那一支的早退之后才跑, 于是 `https://u:p@host/x?token=abc` 这种"两个凭据位都占着"的地址只糊了密码 |
| `index.mjs` (G1-2) | ① peer / live 两段与 workspace 一样并进三层合并; ② 规格解析 (各管各的降级, encrypted 后端互斥) + 启动行尾那一段 `peer=on listen=on proxy=on url=card live=on`; ③ 影子工作区与投影会话的轮询 (`mountState.peerRefresh` / `peerView` 是排查与测试的只读面) + 会话镜面的存储域账; ④ `agent/pre-step` 的归属方闸; ⑤ 对端五条路由 (独立于面板座位开关) + 面板快照的 `peer` 块; ⑥ `sync_peer` 工具与 `/sync peer [state\|live\|send]`; ⑦ 条目版本升级时把旧口径那场投影**归档重做** (`reopenProjection`: 先开新的再归档旧的, 没有归档口子就原地不动) —— 账本多记一个 `snapshotV` |
| `lib/panel-routes.mjs` (G1-2) | 快照多一块 `peer` (可选依赖 `deps.readPeer`), `PANEL_SCHEMA_VERSION` 升到 2 |
| `lib/client.js` (G1-2) | 总览多一张「跨设备对端」卡 (状态 / 地址与可达性 / 口令打码 / 影子与投影条数 / 队列与计数) 与一个发消息框 —— 走本机 `/luwi-sync/send`, 令牌留在宿主那一侧 |
| `test/luwi-peer.test.mjs` / `test/luwi-live.test.mjs` / `test/luwi-inbox.test.mjs` / `test/luwi-peer-runtime.test.mjs` / `test/luwi-proxy.test.mjs` / `test/luwi-peer-wiring.test.mjs` / `test/luwi-peer-proxy-wiring.test.mjs` | **新增** (G1-2): 68 条判据 —— 规格与令牌纪律 / 投影折叠与窗口 (含"回答的落定不靠 turn/end"与"只有工具调用的不算回答"两档) / 分组名带设备名与后缀幂等 / 队列幂等与回执 / 通道四条路由与两种投递走向 / 投影会话折叠 / 装配 (启动行、路由表、命令、工具) / 影子工作区与投影会话 + 归属方那条闸 (含"退下来的投影"与"对端搬进来的副本"两档) + 跨重启的游标 + 条目版本升级时的归档重做 + 未归档/从相连起算的同步面。**不进 APK** |
| `test/client-panel.test.mjs` (G1-2) | 多一条: 对端那一块按快照画出来 (令牌只有打码形态)、`panelOps:false` 时不画写面、老版本那一侧没有 `peer` 块时整块不画 |
| 影工作区里新开的一场 (2026-10-14, G1-3) | `index.mjs`: ① 归属方闸补**第 ④ 条判据** —— 新增 `peerWorkspaceOfShadowSession()` 认"cwd 落在影子根底下的本机会话" (cwd 拿不到时按工作区注册表的 `sessionIds` 认, 与 `isShadowWorkspace` 同一条口径; 对不上具体工作区也给空名的结果 —— 失败关闭), 命中就**让对端在同一个工作区里新开一场** (`kind:'new'`) 并把回执里的会话 id 绑成本机这一场的投影 (此后走第 ① 条判据), 送不出去只落一条本地通知, 本机一个字都不跑; ② `resolvePeerTarget()` 多认一种目标 `new` (名下的工作区里**无条件** `controller.create`, 不复用最近那一场); ③ `peerProxyView()` 多一个只读读数 `newSessions: true` (**版本判据**, 与 G1-2 的 `peer` 块同一个做法); ④ `sync_peer` 多一个 `fresh` 参数。起因与验收见 `docs/DSH-LW-3.0.0-G1-施工单.md` §14.17 |
| `lib/luwi-peer-runtime.mjs` (第四种目标) | `normalizeTarget()` 认 `new`; 未知的一律落 `session` + 空 id ⇒ `receive()` 回一条"解析不到会话"的明确错 —— 老对端认不出新目标时**失败关闭**, 不会静默降级成本地执行 |
| `lib/client.js` (G1-3) | 总览那张对端卡的发消息框加一个**工作区下拉** (候选 = 快照里的 `peer.workspace.shadows`), 发出去的目标从 `{kind:'session', id:''}` (对端永远解析不到会话, 这个框从来没成功过) 改成 `{kind:'new', name:选中的对端工作区}`; 候选为空时禁掉输入框并说明 |
| `test/luwi-peer-proxy-wiring.test.mjs` / `test/luwi-peer-runtime.test.mjs` (G1-3) | 多 7 条判据 —— 影工作区里新开的一场 (送到对端新开一场 + 回答折回本机这一场 + 不重复落主人那一句 + 下一句改走第 ① 条) / 送不出去只落通知 / 影子里认不出工作区也照样送 / cwd 拿不到时按注册表认 / 面板发送路真的送到 / `normalizeTarget` 认 `new` / `kind:new` 原样交给 `resolveTarget`。全套 **237/237**。**不进 APK** |
| 其余 | 一个字节未动 |

## 拷贝时排掉的上游仓杂物 (与包本身无关)

`.git/` `docs/` (演示图) `pnpm-lock.yaml` `pnpm-workspace.yaml` `renovate.json5`
`screenshots.json` `.github/` `.gitignore` `.gitattributes` `AGENTS.md`

## 打包时还会再裁 (见 `tools/pack-host.mjs`)

装进 APK 树之前裁掉 `test/ scripts/ tsconfig.check*.json` 与非中文 README —— 本目录保留
它们作对照, 只是不进包

## 怎么对回上游

上游克隆还留在 `.lwtmp\g1\dsh-session-sync` (工作区内), 换 pin 时在那边 fetch 之后
把差异按上表逐个重放; `index.mjs` 的改动都带 "Luwi 改" 注释, lib/ 里改过的三份
(`engine.mjs` `mirror.mjs` `render.mjs`) 与加过方法的 `git.mjs` 在文件头写了 "Luwi 改了什么",
**新增的五份** (`luwi-bridge-runner.mjs` `luwi-adopt.mjs` `luwi-workspace.mjs` `luwi-peers.mjs`
与两份 Luwi 测试) 在上游没有对应物, 直接整份带过去

## 本机怎么验 (不出包)

`D:\apk\node_modules\@deepseek-ai\` 下有指向 submodule 各包的 junction (`dsh-tools` 本来就有,
2026-10-13 为跑测试补齐了其余与 zod), 于是这份 vendored 树能在电脑上直接跑测试:

```powershell
cd D:\apk\Luwi\host-plugins\dsh-session-sync
D:\apk\tools\node\node.exe --test "test/*.test.mjs"
```

上游 89 条 + Luwi 24 条 (G1-C 16 + G1-D 8) 应全绿。改动落进 APK 之外的路 (pack-host 会把 `test/`
与 `scripts/` 裁掉) 不受影响
