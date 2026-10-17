# Luwi

把 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 搬到安卓上的项目, 用 Miuix 界面库, 自建虚拟屏来控制应用, 把屏幕能力做成 dsh 原生工具交给模型

**原来的八步计划与界面那一轮都已落地并在真机上验过**, 仓库公开在 `Miuzarte/LittleWhale` (`main` 是压缩后的单个提交, 开发历史在本地 `dev` 上)

## 当前版本 (发布记录)

**Luwi 2.7.7 (2026-10-15)**: `versionCode 15 / versionName "2.7.7"`。干净构建 (`:app:clean` +
全量 `assembleDebug`, host 树重打, 5m46s) **232,288,517 字节 (221.5 MiB)**, sha256
`06b1e871a76a19416fb7be5fa43583d0064ab175706368dd93975d8b005d7bfb`; `tools/apk-bytes.py` 量出
**最大无归属区间 4,097 字节 / 总计 4,121 字节** (与 2.6.0 / 2.6.5 / 2.7.4 / 2.7.6 那几份干净包同一个量级);
host 树 330 个 tarball / 32,814 个文件 / 324.8 MiB, `host.zip` 94.3 MB。发在 `yuloong07-star/Luwi`
的 `v2.7.7` (Release 正文在工作区 `docs\Luwi-2.7.7-release-notes.md`)。

这一版进的东西: **手机与电脑的 dsh 连成一套** (luwi-dsh-sync: 会话镜像 + 工作区镜面 + 互为远端名片 +
投影会话与归属方那条闸 + 默认关的两处开关)、**手机里的 Linux 沙盒** (Alpine minirootfs + proot,
`lw_linux` 四个动词)、**能力沙盒两条接通** (`files.read:<前缀>` / `net.http:<域名>`, 能力表 9→11)、
**插件那一层住进 profile patch** (面板存得进设置, 顺带修掉应用内"生图保存"必失败)、
**视频模式能指定另一个模型** (跟随当前模型 / 退出即还原)、**浮标能放人物立绘与球尺寸滑条** (48→72 dp)、
余额查询扩到 11 家与设置页刷新, 以及球上「正在想」不亮、生图保存、退出视频模式后模型不还原三处修复。

**公告口径 (2026-10-15 主人定)**: 对外的公告与封面**一律叫 `Luwi`**, 不写 DSH / LW;
未上过真机的项**不进公告** (例如 3.0.3 的「添加模型」这一版只进包、不进片)。

## 文档地图

| 文档 | 放什么 |
| :-- | :-- |
| `AGENTS.md` (本文) | 现状、决策、约束、怎么操作 |
| `README.md` | 对外的门面: 能力、已知问题、构建、Credits |
| `docs/step2-record.md` … `docs/step8-record.md` | 每一步的清单、实测数字、踩坑过程 |
| `docs/ui-record.md` | 界面那一轮: 设置页缩进与名字、过渡风格、状态栏图标、截图那两条滑块 |
| `docs/host-build.md` | 构建事实: 随 APK 发的二进制、pack / zip、被否的方案、依赖缺口、工具链版本 (**平时不用读**) |
| `B:\Git\deepseek-harness\patch.md` | fork 相对上游的**全部**改动, 唯一权威 |
| `third_party/deepseek-harness/docs/` | dsh 自己的文档 (subsystems / cookbook / user) |

「第 N 步」的对应: 2 = dsh 上安卓, 3 = 特权通道, 4 = 自建虚拟屏, 5 = dsh 原生工具, 6 = 主屏与触摸刹车, 7 = 无障碍读屏, 8 = 端侧 OCR

## 仓库形态

dsh 以 **git submodule** 挂在 `third_party/deepseek-harness/`, 指向 `https://github.com/Miuzarte/deepseek-harness.git` (Miuzarte 的 fork, 不是上游)

**dsh 的构建产物不进 git**: submodule 里只有源码, `pnpm install` 与 `build:official` 都在**构建 APK 时**跑 (Gradle task), 产物直接喂给打包步骤, 这样 submodule 保持干净、能跟上游 rebase

构建机需要 Node + pnpm, 升级 dsh 就是 `git submodule update --remote` 之后重新构建。**pin 必须是 fork 上推过的提交**: 只活在 submodule 工作区里的改动, 克隆本仓的人看不见

**当前基座 (2026-10-17)**: 分支 `lw-sync-rc2` = 上游 **`dsh-v0.2.1-rc.2`** 的树 + fork 那批安卓补丁 (原来的 `lw-sync-alpha1` 是 0.2.1-alpha.1)。本机 `github.com:443` 不通, 所以那一跳的基座是**从 GitHub 的 tag 归档 (codeload) 重建出来的本地提交** (`upstream-rc2`, 只有树、没有上游历史), 再把 fork 提交 rebase 上去; 逐条做法与两条丢掉的提交见 submodule 的 `patch.md`。**这个 pin 还没推到 fork**: 推之前克隆本仓的人拿到的是一条不存在的提交, 别把这一版当发布基线

## 架构

Luwi 自己是一台**远程 dsh 服务器 + 一个安卓控制端**, 两件事共用一个进程:

1. **dsh host** — APK 里的 Node 跑 dsh, 监听回环地址, 界面是它的 Web GUI (装进 WebView)
2. **安卓控制端** — Miuix 界面 + Shizuku / root 特权通道 + 自建虚拟屏, 把屏幕与输入能力做成 dsh 原生工具交给模型

**常驻方式是前台服务** (Node / host / WebView 都在里面), 否则一切后台就被系统收掉

**host 以「上游自带的 pack → 平铺 `npm install`」的形态进 APK**, 不用源码 + tsx —— 代价是改完 dsh 要重新 build + pack 才能进 APK, 没有"机内改源码立刻生效"

dsh 这个 fork 的定位也是**部署在远程服务器上, 从任意浏览器访问** (主题与字号存浏览器 `localStorage`, 手机与 PC 各一套), 移植时**不要把这些改动弄丢**

**监听地址没有写死 `127.0.0.1`**: 局域网开关 (`HostSettings.lanAccess`) 让 host 以 `--host 0.0.0.0 --allow-lan` 启动 (fork 加的第二个旗标, 安全默认一个字没变), 别的设备用浏览器打开 LAN URL; `DshHost.remoteUrl` 从就绪行的 `(LAN: …)` 后缀里取 URL, **不自己枚举网卡**, 这样显示的地址与 host 认的 browser-trust 栅栏是同一个 (**2026-10-06 起设置页没有那个开关了**, 改它要写 `luwi.xml` 再重启 host, 见下面「界面」那一条)

**安卓侧两个已知的坑**: `os.cpus().length` 返回 **0**, dsh 里任何按 CPU 数并行的地方都要能容忍 0; 随包发的 node 有一批**写死的 Termux 路径**, `OPENSSL_CONF` / `SHELL` / `TMPDIR` 三个少一个都起不来 (见 `docs/host-build.md`)

## 术语与参考仓库

- **dsh** — deepseek-harness, 被移植的对象, 以 submodule 挂在 `third_party/deepseek-harness/`, 是 **Miuzarte 的 fork** (不是上游)
- **SFA / ScrcpyForAndroid** — `B:\Git\ScrcpyForAndroid`, 同为 Miuzarte 的项目, **只当 Miuix 界面的参考**; 它的 scrcpy 层与 `new_display=` / `display_id=` / `scrcpy_%08x` socket / 控制报文表那套**全部作废**, 别再翻
- **MAA-Meow** — `B:\Git\MAA-Meow`, 自建虚拟屏 / 输入注入 / 读触摸 + Shizuku 与 root 双通道的**做法**参考 (**AGPL-3.0, 只看做法别抄代码**)
- **host / client 侧** — dsh 的术语, host 侧跑在 Node 里 (发构建产物 `lib/`), client 侧是浏览器产物 (每个 client 包的 `lib/client.js` + `@deepseek-ai/dsh-web-frontend/dist`)

| 路径 | 用途 |
| :-- | :-- |
| `third_party/deepseek-harness/` | **submodule**, 被移植的 dsh, 唯一允许改的第三方代码 |
| `B:\Git\deepseek-harness` | dsh fork 的开发克隆, 在 submodule 之外单独放一份方便比对和推分支 |
| `B:\Git\ScrcpyForAndroid` | Miuix 界面的参考 |
| `B:\Git\MAA-Meow` | 虚拟屏 / 注入 / 读触摸 + 双通道的参考 |

后两个仓库当**只读参考**用, 不要在里面改代码; dsh 不是参考而是**被移植的对象**

## 测试设备

一台随便用的真机, 小米 13, 已连接:

| 项 | 值 |
| :-- | :-- |
| 局域网 IP | `192.168.1.103` |
| adb | `adb connect 192.168.1.103:5555` |
| Termux ssh | `ssh -p 8022 192.168.1.103` (用户 `u0_a441`, 免密 key 已配好) |
| root | KernelSU, `su -c '...'` (`context=u:r:ksu:s0`) |
| 型号 / 代号 | `2211133C` / `fuxi` |
| Android | 16 (API 36) |
| ABI | `arm64-v8a` |
| 内存 | 11 GB |
| 已装 | Termux (含 clang, git, ssh, curl), Shizuku (`moe.shizuku.privileged.api`), KernelSU |

**这是开发机上唯一的一台, 可以随便装东西、重启服务、改配置**, 但 Termux 是 `targetSdk 28`, 别把它升级成 Play 版本; `adb` 在 `B:\Software\AndroidSDK\platform-tools\adb.exe`

## 代码风格

照抄 SFA 的 `AGENTS.md`:

- 注释中英文都行, 中英文/数字之间留空格 (盘古之白), **不要用全角标点**, 用半角 `, . ( ) /`
- **注释里不用句号**, 该断句的地方用逗号, 句末直接结束
- 这条同样管本文档的正文, 不只是代码注释
- UI 字符串同时进 `res/values/strings.xml` (en) 与 `res/values-zh/strings.xml`, **设置页也不例外**; 设置页的键以 `settings_` 开头, 措辞照 SFA 的 `values-zh/strings.xml`, 两份文件**键与顺序都保持一致**, 占位符 (`%1$s`) 一一对上
- 不要用 `;` 把本该分行的语句挤在一行
- 改代码优先小步修改, 不要整文件重写
- 引用符号先 `import` 再用短名, 不要写全限定名
- 多出来的 import 不用手动清, 格式化器会处理

## 界面

**上面原生渲染虚拟屏预览, 下面 `weight(1f)` 的 WebView 渲染 dsh Web GUI, 会话界面不重写** —— 那界面本来就有 ~40 个 client 包 (`packages/client/ui-*`) 且用的是**内部协议** (Host 生成 descriptors + codecs, 不是公开 API), 原生重写等于永久追上游, 详见 `docs/subsystems/web-client.md`

几条要记住的:

- **画面在 `TopAppBar` 下面**而不是页面最顶 (那样会顶进状态栏 inset 里); 手势层贴在 `SurfaceView` 的 modifier 上, 这样 `size` 就是画面本身
- **在看画面时顶栏整条不画** (`SmallTopAppBar` 的 `CollapsedHeight = 52.dp`, 标题空着也一样高, 想省这 52dp 只能不画), 菜单按钮改成浮在画面右上角, 静 3 秒淡出, **菜单开着时不淡出**
- **缩进与段间距统一由脚手架的 `LazyColumn` 给** (`scaffolds/LazyColumn.kt`: 页面左右 12dp + `itemSpacing` 12dp + 横屏限宽 + overscroll + 滚到底触感), **Card 不写水平外边距**; Miuix 的 `Card` 不带内边距, 带内边距的是设置项自己 (16dp), 所以**别再套一层 `padding(16.dp)`** (那就是 32dp); 按钮一律 `fillMaxWidth()`
- **转场只有 Miuix 那一套, 横滑返回一直开着** (2026-10-17 主人: "删除导航板块, 只用 Miuix, 横滑返回开"): 设置里那一段 (过渡风格下拉 + 横滑返回开关) 整段撤了, `AppNav` 里两个值写死 (`NavTransitions.MiuixDefault` / `NavSwipeDirection.LeftToRight`, `cornerClipMode` 固定 `Leading`); `ThemeSettings.transition` 与 `.swipeBack` 两个字段只剩"读旧 pref 不炸"的作用, **没有任何人读它们**, 别再把开关接回去 (AOSP 那套搬来的 `ui/CrossActivityTransition.kt` 因此不再被引用)
- 系统栏图标深浅由 `theme/SystemBars.kt` 按**实际渲染出来的配色**定, 在 `MiuixTheme` 里调一次; **顶栏没有模糊也没有那个选项** (画面自己不透明, 糊了没人看得见)
- **三处图标是同一份标记, 由一张品牌图出** (2026-10-10 主人: "图标全部改用这个, 包括 ball, 注意图案占比要和现在的一样"): `drawable/ic_launcher_foreground.xml` (一圈水花里一只鲸尾, `#01B4FF`) 同时是**自适应图标的前景与单色层**与**球上那个染白的标记** (`overlay/Ball.kt`), `drawable/ic_notification.png` 是通知栏那份白模, 而 `mipmap-*dpi/*.webp` 是密度位图那条回退 —— 它们**全部**从 `tools/brand/luwi-mark.png` (主人给的那张白底单色水彩画) 出: 矢量走 `tools/vector-trace.py` (等值线 -> 多边形 -> `evenOdd`) 再 `tools/vector-ink.py --write --ink-width 0.46` 定尺寸, 位图与白模走 `tools/make-icons.py`。**两份脚本都不进构建, 产物提交进仓库**, 换标记就是换那张品牌图再把这两条跑一遍。**墨迹宽度 0.46 是"占画布多少"那件事唯一的数** (那几张密度位图也按 0.46 摆, 与自适应图标长得一样), 主人点名过"要和现在的一样", 别顺手改它。**只有球那一处比它大** (`BallView.GLYPH_INK_RATIO` = 0.65 个球体直径, 照抄图标那份是 0.378): 同一份矢量, 只是承载它的那块 view 比球大一圈、多出来的透明边被圆裁掉 —— 主人 2026-10-10 看到球之后点名"ball 的图案占比小, 放大一点", 先给 0.55, 看过实机截图之后要的是 0.65
- **设置页右上角有一个 ⋮**: "不用常驻一页"的事全收在那一个菜单里 —— 2026-10-08 起是**检测更新 / 捐赠 / 关于 / 重启 DSH host** 四条, 以后加选项就是往那个 `items` 里再加一条; 它们三个对话框由 `ui/AboutDialogs.kt` 画, 同一刻只开一个 (设置页拿着一个 `AboutPage?`); 注意 **material3 不是本项目的依赖** (只有 `material3-window-size-class`), 没有 `androidx.compose.material3.DropdownMenu` 可用
- **设置页是两层分组 + 逐段可折叠** (2026-10-08): 一级是 `GroupTitle` (常用 / 能力), 二级是 `SectionTitle` (段名 + 一句摘要, 那一行整行可点 = 收起/展开, 折角是 `MiuixIcons.ChevronForward` 转 90 度 —— `ExpandLess` / `ExpandMore` 那两颗在模拟器上量出来是"两个断开的角", 认不出是一颗箭头); 每一段由 `LazyListScope.settingsSection(...)` 生成, **收起来的段存在页面那一层的 `collapsed` 集合里** (`rememberSaveable` + `listSaver`, 转屏不丢)。**缺省只展开省电与「常用」那五段 (导航那一段撤了), 能力那 14 段收着** (2026-10-17 第 1 条, D1 + D2): 白名单是 `SettingsSectionsAlwaysOpenKeys`, 缺省那一份由 `defaultCollapsed(SettingsSectionKeys)` = 全部 - 白名单算出来, 于是**以后新加一段天然是收起的** (从前那句"缺省全展开, 新加的一段自己就是展开的"已经翻面); 判据两条 —— `SettingsSectionsTest` 钉纯函数 (七段开 / 十四段收 / 假想的新段也收), `tools/check-settings-sections.mjs` 把那份名单与页面上 20 个 `settingsSection(key = …)` 逐个对齐 (顺序也管, 漏加一段会红)。**省电那一段排在第一个** (它原来是「唤醒词」里的三行): 省电模式 / 省电时段 / 省电时停自动指令都属于"现在别听我说话"这一笔账, 与"许可不允许唤醒"分开
- **「Luwi-dsh-sync」那一段 (G1-2, 2026-10-15) 排在 `linux` 与 `permissions` 之间**: 它写的是 `$DSH_HOME/lw/session-sync.json` —— **手机侧唯一的配置面** (插件的 overlay 行不带 config, 所以文件里的每个键都生效; 改完要重启 host, 段首那句 `settings_sync_note` 就为它)。读改写全在 `sync/SyncSetting.kt`: **保留未知键** (整棵树照抄, 只动自己那个键), 键名与插件那一侧由 `tools/check-sync-setting.mjs` 逐个对齐, 令牌只出 `maskedToken()` 的打码形态 («看当前配置»那一页也打码)。纯文件那一半 (`snapshotOf` / `setTextIn` / `setFlagIn` / `setPullIntervalIn` / `prettyOf`) 只吃一个 `File`, 于是能在临时目录上单测。**影工作区里的会话一律以电脑为准** (G1-3, 2026-10-14): 手机 GUI 里那个 `xxx（PC）` 分组是个真工作区, 在里面点「新对话」开的会话 `cwd` 落在影子根底下 (`$DSH_HOME/lw/peer-workspaces/…`) —— 归属方那条闸的**第 ④ 条判据**认它, 把这一句送到电脑**那边新开一场** (`kind:'new'`, 不复用最近那一场) 并把回执里的会话 id 绑成本机这一场的投影, 于是电脑的回答折回来落在主人正看着的那一场里; **本机模型一个字都不跑**, 送不出去只落一条「这一句没有送到 PC」的本地通知。面板那张对端卡的发消息框走的也是这条路 (工作区下拉 + `kind:'new'`; 它原来写死 `{kind:'session', id:''}`, 是一条从来没成功过的死路)。**「同步」默认关, 开关有两处** (G1-4, 2026-10-14): 新装没有仓库地址 = 关, 后台一圈都不跑; 打开它的地方是设置页「同步」那一段的开关, 与**进设置之前那个顶栏菜单**(`HostScreen.mainMenu()` 里「设置」正上方那一行, 与设置页读的是同一份状态)。数据面在 `SyncSetting.setSyncOnIn`: **关 = 把 `remote` 挪进 `lw.savedRemote` 存着, 同时把 `autoPullOnStart` / `autoPushOnTurnEnd` / `pullIntervalMinutes` 存进 `lw` 段再归零 —— 三件事缺一不可** (插件的引擎是无条件建的, 只清远端的话周期拉取照旧按点起来, 而那一轮要经 Linux 沙箱起 proot+git, 实测一条最轻的 `git --version` 就 2.1 s, 白烧电); 开 = 全部填回去。`lw` 段是**应用自己那一层**, 插件不读 (所以 PC 侧一点不受影响), 键名由 `tools/check-sync-setting.mjs` 单独钉。改完必须重启 host, 菜单那一行顺手重启。仓库地址/分支的对话框会补一句填写示例 (`settings_sync_remote_hint`), 段内按「① 仓库 → ② 节奏 → ③ 工作区」分步, 关着时只留一句引导; 对端通道与同步无关, **关着同步也能用**
- **「视频模式」那一段 (3.0.2 需求 2 + 3.0.3, 2026-10-16) 落在「常用」组里**: 段名从「视频识别」改成**视频模式**, 而视频模式那几件事都住这一段 —— 最上面是**用哪一个模型说话** (缺省「跟随当前模型」; 它原来是单独一段「模式与模型」, 归并过来是因为"视频模式用哪个模型"就是这一段的事), 下面才是那台相机的参数 (抓几张 / 隔多久 / 多大 / 要不要拼网格)。模型那一行写的是 `$DSH_HOME/lw/mode-model.json` (`sync/ModeModel.kt`, 照 `SyncSetting.kt` 的形状: 保留未知键 / 纯文件那一半只吃 `File` / `revision` 记号), 宿主插件读它: `lw_mode` 切换那一路 (exec 里有会话 id, 切进视频前**现取**一次部署默认记成 `lastDefault`, **不信任旧值**) 与 `turn/start` 钩子 (只根会话, 兜住 GUI 自己开的会话; 投影里 pending 已经等于目标就一个 RPC 都不发)。判定抽成纯函数 `resolveModeModel(activeMode, config, sessionModel, restoreModel)` (宿主 `host-plugin/index.mjs` 里, `tools/check-mode-model.mjs` 把它抽出来跑那张真值表), 三档结论: `null` 什么都不做 / 切到配置那一对 / 带 `restore:true` 还回去。**`sessionController.selectModel` 会顺手把部署默认写成被选中的那一个 (`commands.ts` 里那个 `saveSelection`), 所以每一次切换之后都要立刻 `saveSelection` 把默认还回去 —— 这一条是必须的, 不是锦上添花**, 少它一次切换就把 GUI 里那个「当前模型」改掉了; 会话日志因此会多一条 `model/selection` 与一条 "model changed" 用户消息 (一次切换一条, 无法避免)。**还原不靠"下一轮兜底"**: 退出那一刻就按记下来的那几场当场还 (`modeModelState.sessions`, 三个触发点 = `lw_mode` 退出 / 语音命令退出 / 3 s 轮询看见"已经不是视频模式"——球单击与设备脚本走最后这一条), 只还 `agentAvailable === true` 的会话 (**判据不是 `running`**: 退出那一刻视频那一轮已经结束, 按 running 判过一次, 一场都没还, 于是退出后的第一句仍用视频模型回 —— 2026-10-16 真机上量到的); `turn/start` 那一条留着兜宿主重启后 `lastDefault` 没了的那一档。**工具那一层的 `execute(args, exec)` 里没有 `ctx`, 要用模块级的 `hostCtx`** (写成 `ctx.get(...)` 会在运行期抛 "ctx is not defined", 而它被 catch 住、回执上只少一行 —— 同一天真机上踩的)。读数两处: `lw_mode op=status` 的 `model:{...}` 行, 以及落成文件的 `$DSH_HOME/lw/mode-model-state.json` (`configured` / `active` / `switches` / `restored` / `sessions` / `endpoint`, 配置改了而没发 RPC 时轮询也会补写) —— **要量"发了几个 RPC"看这份文件, 别去解那份 zstd 多帧拼接的会话日志**。目录那一半反过来走: 宿主写 `$DSH_HOME/lw/model-catalog.json` (`sessionController.modelCatalog()`, 与 GUI 那个模型选择器同源; 原子写; **插件挂在命令行覆盖层上, `apply()` 可能早于 web bundle 注册 `sessionController` → 起来按 3/10/30 s 重试三次, 取不到就把 reason 写清**), 应用 `tool/LwModel.kt` 读它 —— 设置页候选列表与桥上的 `model op=catalog` 是**同一份** (`lw.ps1 -Method model` 从外面量到的就是应用里看见的), `ok:false` / items 为空时那一行**退化为两个文本框, 不假装有列表**; 应用打开对话框时写一个 `model-catalog-request.json` 记号, 宿主那条 3 s 轮询看见就刷。键名、文件名、上限与 strings 两侧由 `tools/check-mode-model.mjs` 逐个钉 (45 条)
- **预设现在只有两份: `Luwi` 与 `探索模式`** (2026-10-18 主人: "删除手机模式和视频模式, 因为已经在自定义模式里了" + "自定义模式在预设里的名称也改成 Luwi, 并加简介, 这个要随包"): `presets/mobile-use` 与 `presets/video` 两棵目录已删, `CustomPresets.PRESET_ROWS` 只剩 `preset-explore`, 而**老机器 profile patch 里那两段声明由 `withoutRow` 真的删掉** (只停发的话, 已经装过的机器里那两段还在, 选择器里就永远看得见); `custom` 那个预设的 `preset.yml` 名字改成 `Luwi` 并带一句简介 (`presets/custom-mode/package/preset/preset.yml`), 已经装过的机器由 `presets/custom-mode/package/seed.mjs` 的 `applyShippedIdentity` 在每次激活时订正 (**只认我们自己播种过的名字**, 用户自己改过的不动; 与上游那条 `dropShippedDescription` 同一个契约)。**随包的 `dsh-custom-mode` 版本号必须跟着抬** (这一版 2.0.1 → 2.0.2): `copyDurable` 只在版本不同时刷新耐久副本, 不抬版本的话新 `seed.mjs` 根本到不了设备上, 迁移就白写。判据: `check-custom-preset` 24 条 (含"包内名字是 Luwi / 有一句简介 / 迁移函数在且每次激活都调"), `CustomPresetsTest` 10 条 (含 `withoutRow` 的三条)
- **「添加模型」: 视频模式可以走自己那一条 LLM 路由 (3.0.3, 2026-10-16)**: 那一段设置里有 接口地址 / API Key / 模型名 三个框 (对话框里, API Key 是密码框, **三个框各自的标题挂在 `SuperTextField` 的 `label` 上** —— 主人 2026-10-16 点名的"说清楚哪一空填什么信息": 地址要给 http(s) 带 `/v1` 的例子, key 那句说清留空也行, 模型名那句给一个真 id 当例子; 三条文案的键是 `settings_mode_endpoint_url_note` / `_key_note` / `_model_note`), 写进 `mode-model.json` 的 `endpoint` 段 (`url` + `model` **都非空**才算配好; 清掉地址走 `clearEndpoint` 而 **key 留着**)。**界面上的名字是「添加模型」不是「自建端点」** (同一批主人的要求; 摘要那两句跟着改成"还没添加模型" / "走添加的模型: %1$s", 数据面与那句 `luwi-video` 一个字没动)。宿主 `ensureVideoRoute()` (三处触发: 起来 / 3 s 轮询 / `lw_mode` 切换那一路) 把它**挂成 pi-ai 的一条真路由** `luwi-video`: `ctx.settings.update('<pi-ai 的段>', {providers:{'luwi-video':{api:'openai-completions',baseURL,models:[{id}],apiKeyEnv:'LUWI_VIDEO_API_KEY'}}})` + `ctx.credentials.set('LUWI_VIDEO_API_KEY', key)`。三条要记住的: **段名与那一层路径从 `ctx.llm.listConfigurableProviders()` 里读** (部署换过行 id 也对得上), **写入即校验、下一轮请求生效不用重启** (providers 是 volatile 字段), **key 只进凭据缝不进设置文件** (端点不要 key 时 profile 里干脆不写 `apiKeyEnv`, 写了而取不到是 `MISSING_CREDENTIAL` 而不是悄悄退化)。副作用照实说: 那条路由**也会出现在 dsh 自己的模型选择器里** (展示名「Luwi 视频模式」)。清掉端点**不动设置** (那条路由留着不再被选) —— 理由与那五档风险写在 `docs/DSH-LW-3.0.3-视频模式自建端点-施工单.md`
- **捐赠那一页是随包的** (`ui/DonateScreen` + `app/src/main/assets/donate.html`): ⋮ 与「关于」里那一行都走 `navigator.push(Screen.Donate)`, 在应用内用 WebView 渲染 (`file:///android_asset/`), **不联网也不跳浏览器**; 那一页零 `<script>`, 所以那个 WebView **不开 JS**; 仓库里原来那份 `docs/donate.html` 2026-10-08 已删 (提交 `4f21025`), 页面内容只此一份。**捐赠地址没有任何"可填"的入口** (主人 2026-10-08: "不要让别人填地址, 这是我自己的捐赠项目") —— 要改那一页就换 `assets/donate.html` 并重新出包
- **系统返回键由 `AppNav` 那个 `BackHandler` 自己弹一层** (2026-10-08 修): `BackHandler` 是**后登记的先赢** (LIFO), 而它写在 `NavDisplay` 后面, 于是库里"还有上一页就 pop"那一个永远轮不到 —— 现象是**设置页与捐赠页上按返回什么都不发生**。现在那一行是 `if (backStack.size > 1) navigator.pop() else onBack()`, 主页上仍走「再按一次退出」(把任务放到后台, 不是退出)
- **「正在想」是一份文件说了算, 不是推送** (2026-10-08 主人定的口径): `$DSH_HOME/lw/ball-phase.json` (`{"v":1,"at":…,"turns":[{"id":…,"startedAt":…}]}`, 宿主**原子写** —— `.tmp` + rename, 只在 `turn/start` / `turn/end` 真的改了那本账时写, 另外起来先写一次空表) 由 `app/src/main/java/…/overlay/BallPhaseFile.kt` 读: **`FileObserver` 盯那个目录 (inotify), 一改就重读并当场重画**, 另有"服务起来读一次"与"宿主翻成在跑读一次"两处。两侧都是事件驱动、**零轮询**, 也没有"推送丢了没人补"这回事 —— 那个 20 秒的看门狗 (它对不住主人的体感延迟) 连同 `overlay op=phase` 一起删了。球上取 `startedAt` **最大**的那一场, 它的**环色按会话固定**: 5 个色 (`BallPhaseFile.palette`) 轮转发给见过的会话并记在偏好里, 于是多个会话同时在想时一眼看得出"这是不是我先前那一场"。**那几条会话钩子一律走 `onSessionEvent(ctx, …)`** (2026-10-15; 现在是四条 —— 余额那条 2026-10-17 不挂在会话事件上了, 见下面那条「余额只有主人点了才查」): 会话事件是**带载体**派发的 (`entry.carrier` 当 `this`), 载体的 `Context.filter` 只放行它作用域里的监听器, 而插件挂在 profile 上 —— 所以订阅必须带 `{ global: true }` (`vendor/cordis/src/events.ts` 的 `hook.global || !filter || …`, 见 `core/scope` 的 `scopeTarget`)。同一批还修掉一个把五条钩子一起弄死的东西: `isPeerProjection` 用了 `sep` 而 `node:path` 里没导入它 → 每次事件都在开头抛 `ReferenceError`, 而 dsh 把监听器异常 contained 掉、只写 `ctx.logger`, **app 抓的 node stdout 里一个字都没有** —— 现象就只是"球上什么都不动, 日志干净得像没事"。同一处还修了投影判据的前缀: 影子会话的 `cwd` 写 `/data/data/<包名>/…` 而 `DSH_HOME` 是 `/data/user/0/<包名>/…`, 两边都要过 `realpath` 才比得中 (漏了它, 电脑那一轮的投影会被当成"本机在跑")
- **余额只有主人点「看余额」那一下才会查、才会出现** (2026-10-17 主人: 「余额查询只能用户主动在 ball 菜单主动查询」): 两条链一起收紧 —— **宿主那一侧** (`host-plugin/index.mjs` 的 `startBalance`) 只剩**应用写出来的请求记号**这一条触发 (从前还有三条自己起的: 起来刷一次 / 起来后 3·10·30 s 各补一拍 / 每轮 `turn/end` 刷一次, 它们连同 `BALANCE_MIN_INTERVAL_MS` + `balanceLast` 那套节流一起删了 —— 那是"把 Platform 接口当成心跳", 而应用那一侧根本不会把它们的结果显示出来); **应用那一侧** (`OverlayService.readBalance`) **成功那一档与失败那一档合用一个「主人刚要过」的 30 s 闸** (`balanceAskedAt`; 从前只有失败那条带闸, 于是宿主自己刷出来的那一份会**悄没声地长在主人的回答后面** —— 第一次问答最容易撞上, 宿主起来那三拍补刷正好压在第一轮的尾巴上, 装或切外观插件让 host 重启之后更常撞见)。文件本身仍是那条老路 (`$DSH_HOME/lw/balance.json`, 宿主原子写 + 应用 inotify 盯目录), 只是"文件变了"不再是上屏的理由; 点菜单那一趟仍是"新鲜的直接给, 否则先写「正在取…」再由宿主刷", 端到端最多等一次 3 s 轮询加一次取数。判据在 `tools/check-ball-menu.mjs` (那条 `balanceFeed` 切片: 宿主侧不许再出现会话事件订阅与补刷常量, 应用侧 `readBalance` 必须带 `if (!asked) return`)。**同一个记号只刷一次** (`balanceRequested` 现在返回那个时间戳, `startBalance` 拿 `handled` 比一下): 记号在 30 s 窗口里一直"新鲜", 而轮询是 3 s 一拍, 按布尔读的话点一次就会问十遍账号缝 (2026-10-17 模拟器上量到的)。同一天在模拟器上还量出账号缝那一档的一个真 bug: `readAccountBalance` 里那句 `ctx.locale?.getSnapshot?.()` **直接点服务属性会抛** (`cannot get property "locale" without inject`), `?.` 一个都挡不住 —— 没配 `DEEPSEEK_API_KEY` 的部署点「看余额」永远失败, `⚠` 上念的还是这句内部话, 现在走 `ctx.get('locale')`
- **在想时点一下球就是开语音, 但排在双击窗口之后** (2026-10-08 主人: "点击一次进入语音输入…进入语音输入要比第二次点击慢一点"): 那一下要等 `BallFeel.thinkingTapMs()` (标准档 430 ms, 比双击的 300 ms 与防连击的 350 ms 都晚) 才开麦, 而**双击打断会把那个待办当场取消** (同一档上两个手势靠时间分开, 见 `OverlayService.onTap`)。**状态词的优先级 2026-10-09 改成「在念 > 在想 / 在听 (谁最近变成真的谁上) > 失败」** (`BallStatus.wordFor`, 主人两轮定下来的: "正在想状态的显示不能遮挡正在说, 以方便打断说话" + "在想着点一下球, 球显示听") —— 于是 **"在想+在念"写「正在说」**(单点那一下就是"别念了")、**"在想+在听"看两个人谁刚变成真的**(点一下球开麦那一下写「正在听」, 开麦之后新一轮又起来就写回「正在想」, 同一拍一起变时给想)。两个"最近变成真的"的时刻由 `OverlayService.refresh` 那 400 ms 一拍记, 所以那个字最多晚一拍。**动作那一边仍是"念 > 想 > 听"**(显示写着在想而喇叭也在念时, 单点那一下仍是"别念了"), 两件事分开写在 `BallTouch.act` 上; **手势一个字节都没改** —— 双击只在球上写着「正在想」时打断那一轮, 显示怎么切都不取消任何任务
- **念与听那两档球会呼吸, 外面还有一圈圈涟漪** (2026-10-09 主人: "说话时加个边框闪烁, 要像 vivo 蓝心小v那样" + "给正在听加个与正在说相反的收回来特效"): 描边以当前环色在 0.45~1.0 的透明度之间 1.2 s 一个来回 (`BallView.breathe`, **字色不跟着闪** —— 12 sp 那三个字要一直读得清), 球外**往外扩**(念) / **往回收**(听)的同心环由 `overlay/BallRipple.kt` 画 (纯算式 `BallPulse` + `RippleView`)。那一圈比球大 (96 dp 对 48 dp), **画在球那块窗里会被裁**, 所以它住在自己那块 `TYPE_APPLICATION_OVERLAY` 的窗里, 而那块窗唯一的硬条件是 **`FLAG_NOT_TOUCHABLE`** —— 球"只吃自己那 48 dp"那条性质靠它保住。窗只在说/听两档挂着、落下就摘, 圆心在 `applyBallPosition` 与 `moveX` 每帧跟着球心; 它显式 `setTitle` 了一个名字, 因为**认球窗那几份脚本是拿 `dumpsys window windows` 认的**, 多一块同包的 `NOT_FOCUSABLE` 窗会让它们量到 96 dp 的假帧 (那四份脚本现在一起认类名 `BallView`)。读数在 `lw_overlay op=state` 的 `pulse` (`out` / `in` / 空)
- **球的「休闲」有两个状态: 半隐与半藏, 各自一个开关** (2026-10-17 主人: 「ball 的休闲应分两个阶段, 一个是半透明, 一个是半隐藏 (一半吸附在屏幕外的状态), 给这两个状态加开关」; **叫法以主人为准 (2026-10-17 当场更正): 「半隐」= 淡下去那一档, 「半藏」= 挪一半到屏幕外那一档**, 设置里两个开关就叫这两个名字 —— 注意本文别处与代码注释里那个 `peek` 常被写成"半隐", 那是旧叫法, 与这里的「半隐」不是一件事): **只有一个阈值**: `PEEK_IDLE_MS` = 5 s (没动 —— 主人 2026-10-17 那句"时间与透明度都和以前的一样"要的就是它), 两个状态**同时到点**、各看自己的开关; 判据仍旧只有 `BallRest.wait` 一个纯函数 (它的答案 `BallWait` 两个字段: `translucent` 是半隐, `peek` 是半藏), 而"球在干活"那六条闸**两档共用** (按着 / 菜单 / 正在听 / 键盘 / 输入框开着时既不淡也不藏)。两个开关在 `waitNow` 里各自 `&&` 进来 (`BallSpot.translucent` 管半隐, `BallSpot.autoPeek` 管半藏), **两个都缺省开**。alpha 只留一处算式 —— **纯函数 `BallMinutes.alphaFor(translucent)`, 只吃半隐那一档一个参数** (`TRANSLUCENT_ALPHA` = **0.35** 或 1.0): 半藏**传都传不进去**, 于是"只开半藏"是一颗**全不透明**的半藏球, "只开半隐"是淡着但不挪窝 (2026-10-17 主人: 「改的应该是状态而不是时间」+「当半透明关闭时, 半隐藏可以不透明显示」+「半隐藏关闭时而半透明开时可以常驻半透明, 而不用半隐藏在屏幕外」—— 从前那个算式是 0.7 × 0.5, 两个状态互相耦合), 收边 / 回弹 / 那 400 ms 一拍都问它, 变化带 `EDGE_SNAP_MS` 的过渡。**那个 0.35 是主人点名的数** ("透明度用之前的, 不要 0.7" → "0.35"): 它从前是"半透明 0.7 叠上半藏 ×0.5"凑出来的那个视觉效果, 现在直接是半隐那一档自己的值; `alphaFor` 的参数表本身就是"半藏不参与"那条判据。**「半透明」从前是常驻的** (开着就一直是 70%, 与休闲无关), 主人 2026-10-17 点名那与他要的不符 —— 改的只是"什么时候生效", pref 键 (`ball-translucent`) 与它存过的值都没动 (存过 `false` 的机器照旧读得到那个 false)。判据在 `BallTest` (六条: 两档共用同一个 5 秒 / 到点那一拍两档一起成立 / 干活时两档都不算 / 只关半隐时半藏照旧 / 半藏时半隐跟着闲置账走 / 半藏不改不透明度 / 两个状态互不依赖); 现场表 `files/ball-idle.txt` 多一栏 `pale=`, 而且**半隐那一档变了也落一行** (只在 `reason` 变时写的话, "到点了却没变淡"一个字都看不见); 数值读数在 `lw_overlay op=state` 的 `ballAlpha` (1 或 0.35)。**启动时那个停靠点要按球的真实尺寸重算** (2026-10-17 主人报的"启动 dshhost 时 ball 显示异常"): `showBall` 算停靠点时 `ballView` 还没建, `ballSizePx()` 落回缺省 48 dp, 而挑了要求 72 dp 的外观插件 (带立绘那几份) 时窗口是 189 px —— 于是窗口一半在屏外, 闲置 5 s 半藏之后只剩贴着屏幕的一条边; 现在 `addView` 之后补一拍 `view.post`, 按 `ballSizePx()` 的实数重算 `xForEdge` 与纵向钳制再摆一次窗 (没有主题时幂等), 读数里会有一行 `the ball is 189px but rest was placed for a smaller one: 954 -> 891`。`peek()` / `unpeek()` 现在也当场写 `OverlayState.peeked` —— 它们走动画、不经过 `applyBallPosition`, 从前读完数会看到"球已经收着而 `peeked=false`"
- **两笔自动收口挂在"起了一轮新的"那个边沿上** (2026-10-09 主人: "同一场起了新的一轮就掐播报" + "正在听也一并收掉"): 判据是那份 `ball-phase.json` 里**最新的 `startedAt` 比上一次读到的更新** (第一次读只当基线, 不把"已经有轮在跑"当成"刚刚起了一轮"), 于是 `BallPhaseFile.Snapshot` 现在带回**整份**在跑的清单, 不只是最近那一场。**说**: 念开始那一刻把 `OverlayState.replySession` 抄进 `readingSession` (宿主推回复那一条带着会话 id), 那一场再出现在清单里就 `LwSpeak.stop()` —— 一次念只掐一次, 而**那一笔账是空的时候一个字节都不掐**(手动 `lw_speak` 念的一句不猜)。**听**: 同一个边沿上只要 `VoiceState.capturing` 就 `LwWakeWord.hushWindow()`。两条都**只动播报与麦克风, 一个轮次都不取消**。两条**故意不对称**: 说只掐「同一场」的新轮 (别的会话跑起来不碰这一句), 而听是**任何一轮出现都收** —— 后者与 2.6.5 那条"人还在说就继续听、不会被从中间收掉"有冲突面: 人说到一半时被新一轮收掉, 那下半句就丢了 (主人 2026-10-09 选了这一档)
- **三击不再先开麦: 开麦改走"确认窗"** (2026-10-10 主人: "三击 ball 会打开语音输入, 并且会卡一下"): 单击的动作原来在 UP 那一刻全部当场做掉, 于是一次三击的净效果是"先开麦 → 收麦 → 弹键盘", 而开麦那一步会在主线程上建一遍切段会话, 那几百毫秒就是"卡一下"的来源。现在判据挪进纯函数 `BallCommit.deferMs`, **只有"真的要开麦"那几支排队** (正在想 / 失败 / 有回复框 / 刚召出), 等 `BallFeel.commitWindowMs` —— 它就等于双击窗口 (防误触 220 ms / 标准 300 ms), 因为过了这个窗口就不会再有双击或三击, 而**双击/三击到了就把那个待办当场取消**; 到点时还会再算一次 `BallTouch.act`, 已经不是开麦就作废 (免得替别人把麦克风关掉)。**召出 / 收麦 / 掐播报一律不等** (那三下是"跟手"与"别说了"), 等待由按下那一瞬间的一记 `HapticFeedbackConstants.CONTEXT_CLICK` 轻触觉补偿。回滚那半边是**静默快径** (`VoiceRollback.isFast` + `WakeWordService.VOICE_ROLLBACK_MS` = 300 ms): 开门时把"预热识别模型"与"通知栏那一句"延迟 300 ms 再做, 这一段里收窗且一个人声都没听到就把那条待办摘掉 —— **收尾一步不省** (flush / close / reap 照旧)。读数: `lw_overlay op=state` 的 `tapCommits` / `tapCommitsCancelled` / `lastTripleMs`, 加 `lw_wakeword op=status` 的 `fastRollbacks` / `shortLivedOpens` (最后那个应该趋近于 0)。同一批里还删掉了那条永远触不到的"三击整串 450 ms"闸, 并把防误触档的长按门槛从写死 650 ms 改成 `max(平台 longPressTimeout, 500) + 150` (典型设备仍是 650, 两档的尺度不再差一倍多)
- **视频模式那块预览小窗按"显示方向"摆, 帧不再自己转** (2026-10-10 主人: "视频模式下镜头比例有误, 高度受挤压"): 原来 `CameraWindow` 拿**传感器那套坐标**里的预览尺寸去开窗 (`getOutputSizes(SurfaceTexture)` 报的全是横的, 真机挑到 640x480), 而这一路的帧**是相机自己转成显示方向的** —— 判据是同一时刻那张静态图: 按 1280x720 要, 真机上拿回来的是 **720x1280 的竖图、EXIF=0** (连我们请求的 `JPEG_ORIENTATION` 都没用上)。于是竖着拿手机时: 画面是竖的、窗口是横的 → 被压扁 1.78 倍。现在由纯函数 `PreviewTurn.degrees(传感器方向, 屏幕转过多少, 前摄?)` 算出这一路与显示方向差多少度, 差 90/270 就把**窗口那一块换高宽** (竖屏 → 竖窗), **内容一个像素都不转** (前摄只翻一面) —— 再转一次的话画面会横过来, 2026-10-10 在 vivo 与模拟器上都实拍到了 (`ALT` 键的字躺着)。屏幕转不转由 `DisplayManager` 那个监听盯着, 转了当场重摆窗口与那块面。读数在 `lw_camera op=status`: `sensorOrientation` / `displayDegrees` / `previewDegrees` / `previewChoices` (只读静态特征, 一个相机都不用开)
- **视频模式里球上「正在听」顶格, 单击它连视频模式一起收掉** (2026-10-10 主人: "在 ball 新增一个常驻语音状态…单击关闭后也关闭视频模式" + "显示状态与正在听一致" + "那三个字也是正在听" + "把视频状态下正在听这三个字在 ball 上优先及最高, 不能被其它状态打断"): 那一档**不出新文案** —— 亮起来的时候写的就是「正在听」, 颜色/涟漪/呼吸全不改, 只是它在显示表里升到第一格: 常驻语音那个记号 (`modes/voice-resident.on`, 读 `LwWakeWord.residentWanted`) 在且不在省电模式时, 球上永远写「正在听」, **压过正在想与失败, 只让「正在说」盖一下** (喇叭在念时那三个字亮着才有"别念了"那一下)。它跟的是**记号**而不是 `capturing`: 说的时候麦克风是真关掉的 (见下一条), 跟着 `capturing` 走那几百毫秒字就会掉。**单击**这一档: 走 `LwModes.switchTo(context, PHONE)` —— 与 `lw_mode`、设备上那几个脚本**同一次切换**, 记号/相机/`.active`/提示词四件事一次收口; 读数 `lw_overlay op=state` 的 `residentVoice` / `residentVoiceLeaves`。**双击打断在视频模式里显式关掉** (主人 2026-10-10 选的那一档: 球上不再出现「正在想」, 要打断得先退出视频模式), 判据上补了一条"常驻记号没开着"; 视频模式下 `onNewTurn` 那条"新一轮就把正在听收回来"也跳过 (否则"正在想"那几秒的麦被收掉, "接着说话不用再喊唤醒词"就废了)
- **"说"的时候把麦克风真关掉, 全部模式生效** (2026-10-10 主人: "在说时要关闭麦克风, 否则会录入, 这个是全部模式都生效" + 前一条里那句"说完后/打断后重新拉起听"): 帧级那道半双工闸 (`HalfDuplex.ABANDON`) 只丢帧、麦克风还开着, 现在由唤醒服务那条 400 ms 的观察线程 (`startSpeakingWatch`) 在"说"的边沿上把**采集也收回去** (`closeVoice`), 说完 (或被点球掐断) 再拉起来。判据是 `LwSpeak.speakingNow` (三条 TTS 引擎的播放状态), **不是 `VoiceState.speaking`** —— 后者还包括开门那一声"嘀", 拿它关麦会把刚张开的窗口当场掐掉。关麦之前那几帧仍由那道闸兜住, 所以助手自己的声音进不了切段器。**重开只在常驻那一档** (记号在、没在省电模式): 那时"说完/掐断后重新拉起听"才是主人要的; 别的模式里一句说完就该歇着, 由点球或唤醒词再要下一句
- **"说完切模式 → 听不了话"那条修掉了** (2026-10-10 主人: "我唤醒让她打开视频模式, 她打开后听不了话了…当前语音是开的但输入不了话"): 病根不是 TTS, 而是**切模式那一下正好落在"一句话的窗口"里** —— 唤醒 → 说"打开视频模式" → 宿主切模式, 那条链**已经开着** (`voiceActive = true`) 但它是"只说一句"的档 (`voiceSticky = false`), 而 `ACTION_REFRESH` 那一支原来只写记号、不动这条已经开着的链, 于是 `SENT_TAIL_MS` (0.3 s) 那句尾巴一到就自己收了 —— 之后再没有人拉回来, 球上还按记号写着「正在听」。两处一起修: **① 切模式那一刻链子已经开着就当场转常驻** (`voiceSticky = true`); **② 看门狗加一条电平兜底** —— `voiceResidency && listening && !powerSave && !voiceActive && !ttsPlaying()` 就 `openVoice()`, 于是**任何**一次意外收麦都会在 500 ms 内自己拉回来 (不靠边沿: 收了之后没人举手正是这条 bug 的形态)。实测 (vivo V2417A): 复现原时序 (一句话窗口开着时切进视频模式) 之后 `capturing` / `voiceActive` 一路是 true, 球上「正在听」; 日志里只有 `voice-mode residency is now true` 与 `sticky: false` 那次开门, 再也没有 `the voice chain is down`
- **"她自己的回答还是会被录回去"那条: 判据要连"喇叭还在响"一起算** (2026-10-10 主人: "视频模式下正在听时还是有可以录入正在说"): 日志是铁证 —— `21:32:17.761 the voice chain is down again` (说时关麦生效) → `21:32:20.402 the voice chain is up, sticky: true` (只隔 2.6 s 就按"引擎说完了"把麦拉起来) → `21:32:30.338 segment 3 queued as #187: 权限却是允许的…请主人` —— **那整句是她自己的回答**。原因是这台机器的**系统 TTS 引擎 `onDone` 常常在"合成完了"就报**, 而喇叭里还在放 (合成比实时快)。所以"在说"这一条判据挪进 `LwSpeak.talking(context)`: 引擎自己的标记之外再加两样 —— **按字数估的一份预算** (`VoiceState.spokeUntil` = 350 ms/字 + 4 s, 上限 25 s) 与**媒体流真的还在不在放** (`AudioManager.isMusicActive`), 同时成立才算"还在响"。半双工那道闸、说时关麦/说完拉起、球上「正在说」、通知栏那两句、闲置账挂起判据**五处全部改认这一条**。预算是**上限不是估时**: 别的应用放音乐/视频不会把麦克风一直摁住, 到点就放行
- **取景"一帧也不给"那条留了现场** (2026-10-10 主人: "刚刚她又说看不见镜头"): 那次会话里 `lw_look` 连取六组、前后摄都是 **0 帧**, 而 `lw_camera op=status` 是 `open=true / session=true / window=true / previewError 空 / lastError 空` (连一次都没失败过的样子); photos 目录里那段时间**一张文件都没落地** (确实一帧没到手), 而退回手机模式再进视频模式之后立刻正常 (21:38 取到 4 帧)。当场**没能复现** (随后在视频模式下连念带取试了四组都是 4 帧), 所以先只加一条现场日志: 取帧超时那一刻打 `session / hasPreview / lens / reader / previewError / lastError` 六项 —— 下次再撞上就能一眼看出是会话、镜头还是预览那一处不一致
- **「一次取景也拼成一张网格」那个开关: 网格那一张是字节, 不是路径** (2026-10-11 主人: "「一次取景也拼成一张网格」功能坏掉了，导致视频模式下识别不了"): 那一串图里**第一张**由 host 侧的 `sharp` 当场拼出来 (`gridOf`), 它**从来没有落过盘** —— 而附件那一段 (`attachPictures`) 只认"盘上那条路径", 每一项都 `readFile` 一次: Node 把 Buffer 当路径收下了, 于是报的是 `path must be a string ... without null bytes` 而不是一句类型错, 整批图**一张都交不出去**, 结果就是"开着这个开关, 视频模式读不到任何画面"。本地那一趟 (`node .lwtmp/look/run.mjs` 那个桩桥 + 真 sharp) 量得很清楚: 修之前 `images=0`、回执里那句 "the pictures could not be attached to this result", 修之后 `images=4` (网格 + 三张原图)。**`attachPictures` 现在两种形状都收**: 路径字符串, 或者 `{ data, name }` (手里的字节)。同一批里那行回执原来把相机那一段**念了两遍** (`lead` 里带了一次 `${text}`, 外层又带一次), 也一起去掉了
- **双击打断打的是"正在想"指的那一场** (主人 2026-10-08: "双击暂停不会跟随停止"): `pickInterruptTarget` (纯函数, 在 `tools/check-voice-inbox.mjs` 抽的那一段里) 在"根会话且 `running === true`"里挑 `startedAt` 最大的那场, 没有就什么都不取消; 而**字由文件说了算 —— 应用不再乐观清字** (`interruptBall` 只写队列+记账, `phase` 一个字节都不动), 那一轮真停了之后 `turn/end` 写一次文件, 字才落
- **设置页没有「工作区」与「网络」两段** (2026-10-06 撤掉, 见 `docs/ui-record.md` 第七轮): 工作区落在哪是 host 启动时按 `Workspace.resolve` 那三档自己挑的, 网络那个开关 (局域网) 改完也要重启 host —— 两者都是"平时不用动"的。**能力一个都没删**: `Workspace` 三档解析 / `Workspace.requestAllFilesAccess` / `HostSettings.lanAccess` 与 ⋮ 里那条重启照旧, 只是不再有这两个设置入口 (要开所有文件访问就 `tools/lw-install.ps1`, 要开局域网就写偏好 + 重启 host)
- **「截图」那段是两条预算, 都是滑块** (见 `channel/ScreenshotBudget.kt`): **像素**三档 (低 262144 = dsh 的 `imagePixelBudget: low`、默认 640000 = dsh 的缺省、高 1690000 = DeepSeek 那头的处理预算), **字节** 256 KiB~1 MiB 连续可滑 (吸附点 256/512/768/1024, 打字给到 4096)。两条给的都是 **app 这一半** (截图产生时缩到多少), 路由那一半 (`imagePixelBudget` / `imageMaxBytes`) 在 dsh 自己的 `settings.yaml` 里, **app 读不到也写不到** —— app 这一半超过路由那一半没用, 只会把注定要被重编码的图交出去 (超了要在 host 那边重编码, 多一次往返也多一次质量损失), 所以滑块上端就停在路由缺省那个数。滑块是搬来的 SFA `ArrowSlider` (`scaffolds/`, 点标题那一行可打字给精确值)
- **`AndroidView` 里的 WebView 必须显式设 `layoutParams`** (MATCH_PARENT / MATCH_PARENT), 否则它处在 `WRAP_CONTENT` 状态, **所有 viewport unit 都解析成 0** —— dsh 用 `100vh` / `100dvh` 量弹窗、菜单、设置页与目录选择器, 一塌就是空面板
- **`strings.xml` 里带参数的字符串不能有裸 `%`** (2026-10-06 崩过一次, 见 `docs/ui-record.md` 第七轮): `Resources.getString` 把整条当 `Formatter` 格式串解析, 而它与 `String.format` 不同 —— **`%%` 才是转义**, 裸 `%` 会连着后面那个字一起去当一个转换符, 抛 `UnknownFormatConversionException: Conversion = '是'` 把主线程打死。**崩的位置还特别会骗人**: LazyColumn 预取会在那一行还没进视野时就组合它, 于是现象是"往下拉设置页就重启"。规矩: 只要是进 `stringResource(...)` 的百分号, 一律写 `%%`; `tools/check-bare-percent.py` 扫一遍 (它认 `%1$d` 这类说明符, 跳过合法的 `%%`)
- **虚拟屏预览放不进网页端**: 预览是合成器直接写进原生 `SurfaceView` 的, 浏览器拿不到那个 surface; dsh 的插件 (`ctx.slots` / `ctx.sidebarRightTabs`) 跑在浏览器 JS 里, **拿不到 Shizuku / root 通道**
- **球上那几个字与描边同色** (2026-10-09 主人: "正在想的字体颜色换成对应的颜色"): `BallView.show(next, animate, ring)` 把那一色同时喂给 `circle.setStroke` 与 `label.setTextColor` —— 原来只有描边跟着会话换、字恒白, 两个会话同时在想时要凑近看那一圈才分得出是哪一场。**"字没换、颜色换了"那一下也要真的重画** (`lastColor` 是第二个判据), 否则会话轮换 (字还是「正在想」) 时这次换色会被"字没变"那句早退吃掉
- **回应用回到"我从框里发出去的那一场"** (2026-10-09 主人: "当前是回到最近在 ball 输入会话的那一场。改为谁发送了输入框, 就回到那一场"): 回复框原来只记一笔 `OverlayState.replySession` = "最新那条回复是哪一场推来的", 而一块框可以先后跟好几场说话 (第一次按浮标那本 10 分钟的账投给 `dsh-ball`, 之后别的会话的一轮结束也会把回复推进这块框), 于是双击回到了后者。现在拆成两笔: `inputSession` 由 `OverlayService.ask` 在写收件箱那一刻记下实际用的 `to` (**双击回复框**按它走 (菜单那行与常驻通知那条路 2026-10-11 改成走"正在想的那一场", 见下一条), 见纯函数 `BoxTarget.choose`), `replySession` 只留"框里显示的是谁的话"并当没投过话时的兜底; 投递目标 (`replyTarget()`) 也按 `inputSession` 优先, 于是"点球说的那一句"与"回去看那一场"是同一个场。**两笔账都空着就进新会话界面** (主人那一天追加的: "这里没有 (还没发过话) 就进入新会话界面") —— 不再读浮标那本 10 分钟的账 (`VoiceInbox.currentSession`), 那个记号是 `BallReturn.NEW_SESSION` (空串), 由 `HostScreen` 的 `BALL_NEW_SESSION_JS` 删掉 `dsh.sessions.current` 再重载、让 dsh 自己开一场新的
- **「返回应用」走"正在想的那一场" (没在跑就是新对话), 双击回复框那条不变; 通知那一条改成"点下去那一刻现问"** (2026-10-11 主人: "回到应用走的是正在想的对话，与回复框不一样" → "ball菜单栏里『回到应用』要改成走的是正在想的对话，没有则返回新对话。而双击回复框现在不变，仍返回现在的发送回复的对应对话"): 两扇门由纯函数 `ReturnTarget` 分开 (`BallTest` 钉着) —— **球菜单那行「返回应用」与常驻通知**走 `menu` / `OverlayState.runningSession()`: 只看有没有轮在跑, 有就是球上写着「正在想」的那一场 (`ball-phase.json` 里 `startedAt` 最大的那一条, 连"宿主没在跑就不采纳"那道闸都与球上那三个字同一个, 也是双击打断会打的那一场), **没有就是一场新对话** (`BallReturn.NEW_SESSION`), 与"这块框刚才是跟谁说话"无关; **双击回复框**走 `replyBox` / `OverlayState.viewSession()`: `inputSession` → `replySession` → `boxSession` → 新会话界面 (2026-10-09 那条一个字没改)。`OverlayState.boxSession` 是当天补的第三笔 = "这块框最后跟哪一场打过交道" (**只在框真的跟某一场说过话时才写**: 从框里投出去的那一句记下实际用的 `to`, 落进框的那一条回答记下来源; `closeChannel` **不动它**) —— 框把话发出去就自己收掉, 按双击时它本来就关着, 少了这一笔落到的是 dsh 那一侧的"新会话", 而那个会挑**最近动过的那一场的工作区**。另一处是**常驻通知**: 它把会话 id 写进 `PendingIntent` 的附加数据, 而通知只在球上那三个字变化时才重建, 于是点通知与菜单两条路各答各的; 现在它多带一个 `EXTRA_OPEN_LIVE`, 由 `MainActivity.requestedSession` 在收到 intent 那一刻现问一次 `runningSession()` (读不到 (冷启动) 才退回原来那个 id)。读数: `lw_overlay op=state` 的 `menuTo` / `boxTo` / `boxLast`
- **球那场对话的连续窗口从 20 分钟缩到 10 分钟** (2026-10-11 主人: "连续ball的对话从20min缩短到10min"): `host-plugin` 的 `VOICE_SESSION_MS` 改成 `10 * 60 * 1000` —— 就是"最后一句成功投递之后多久算同一场对话"那个数 (`voice/session.json` 落盘, 宿主重启还记得): 10 分钟之内点球 / 喊唤醒词 / 打字都接在同一场, 过了才新开一场 (最早是 1 小时, 2026-10-07 改成 20 分钟)。判据在 `tools/check-voice-inbox.mjs` (两侧取 9 / 11 分钟贴着那个窗口)
- **ball 的对话落在「未分组」那两条根因都修了** (2.7.8 批次 2, 2026-10-17): ① `dsh-ball` 那个目录从前**只 mkdir、没注册成工作区** —— 而 dsh 的会话列表只遍历已注册的工作区实体 (没入账的一律进「未分组」), 工作区记录又只在宿主启动那一趟按 cwd 补建, 于是装完 / 清过工作区列表后的**第一句一定落进未分组**, 要重启一次才归位; 现在 `ensureBallWorkspace` 建完目录顺手 `workspaceRegistry.create(directory)` (上游那是 "Create or resolve" 的幂等契约, 每投一句问一次也不要紧), 拿不到那项服务就退回原行为并记读数 (`ballWorkspaceState`)。② `voice/session.json` 那本账从前**投递成功就无条件写**, 于是"在 GUI 那场问一句 → 浮标框里答一句"就把账改写成了那场 GUI 会话, 之后 10 分钟的点球与语音全进那场 GUI 会话; 现在账上记 `origin` (`ball` / `replyBox` / `automation`) 与 `cwd`, **只有 `origin === 'ball'` 才写**, 复用之前还要过 `voiceLedgerUsable` 那道 cwd 判据 —— **2026-10-17 之前的老账没有 cwd 字段, 一律当没有** (不搬家, 下一次投递自然写新的)。读数: `lw_voice op=inbox` 的 `origin` / `the ledger points at …` / `the dsh-ball workspace is …` 三行与 JSON 里的 `ledger` / `workspace` / `cwd` / `registered`, 外加同一份读数的镜子 `$DSH_HOME/lw/voice-inbox.json` (没有模型可问的设备验收靠 `adb cat` 它); 判据 `tools/check-voice-inbox.mjs` 64 条 (第 14/15 节是这一批新加的, 并且**这份脚本在 2026-10-15 那次重构之后一直是坏的** —— 它还在引用早就不存在的 `voiceTargetSession` / `voiceTargetId`, 这一批顺手修好, 改测 `voiceTargetCandidates`)
- **「正在想」那一下开语音的等待由手势灵敏度档算出来** (2026-10-09): 原来是常量 `BallMinutes.THINKING_TAP_MS` (450 ms), 而两个手势窗口因防误触档收窄/放宽之后那个常量会排到防连击窗口前面, 双击打断就被单点动作吃掉。现在用 `BallFeel.thinkingTapMs()` = 取双击窗口与防连击窗口里更晚的那个再加 80 ms 余量 (标准档 430 ms, 防误触档 530 ms), 判据在 `BallTest`
- **输入通道那块框是幂等的** (2026-10-09 主人报的"输入框唤出有延迟, 两次三击会出现两个输入框而且会重叠, 发生重叠后第一个窗口无法复原"): 判"框还在不在"从 `boxRoot?.isAttachedToWindow == true` 换成 `boxRoot != null` —— `isAttachedToWindow` 要等下一帧 `performTraversals` 才为真, 两次三击落进那段窗口里就会把第一块摘掉、又建一块新的 (`closeChannel` 原来还拿 `boxRoot ?: return` 早退, 于是旧框的读数停在"开着"上)。现在 `openChannel` 已经开着时只摆位 + 还焦点, `closeChannel` 幂等收尾, 挂窗之后再用 `BoxView.claimInputAfterLayout()` 补要一次焦点与键盘
- **球上手势的"多严"现在有七个门槛** (2026-10-09 主人: "ball 的键盘输入和语音输入经常会误触" + "只收紧防误触档"): 原来 [BallFeel] 只管长按 / 拖动门槛 / 三击总时长 / 刚拖完那一下四个数, 双击窗口与两档防连击窗口是两档共用的常量。现在后三个也归档: **标准档一个字没改** (双击 300 ms / 防连击 350 ms / 正在听 1 s), **防误触档收紧** (双击 220 ms / 防连击 450 ms / 正在听 1.5 s) —— 双击收窄是让"打断"那种点错就真停一场的动作更难凑出来, 两个防连击放宽是让手抖出来的第二下算同一击。`BallTaps.guard(word, feel)` 与 `BallTaps.kind(..., feel)` 都吃这一档
- **「锁屏」那段多了「注入解锁」+「注入密码」** (2026-10-09 主人: "在设置里「锁屏」加上一个注入解锁功能, 并说明这样会更快" + "原来的录制解锁不要删除, 说明如果注入解锁不管用就用这个" + "注入解锁下方加个注入密码, 用户自行输入, 测试一次, 可用于测试注入解锁" + "注意密码要加密"; 文案按"给公众用"重写过一轮, 不用内部口吻): 开着注入时 `LockReplay` 不走录制的那条序列 —— 由 `LockSteps.injected` 把它收成一步 `Secret`, 于是解锁只做"亮屏, 注入密码, 回车", 省掉的不只是录制的滑动与图案, 还有每一步之间那 140 ms 的 `STEP_SETTLE_MS`。缺省**关** (它依赖"这台机器的锁屏收注入的按键", 2026-10-09 在 vivo V2417A / Android 16 上量过: 亮屏后一个手势都不发、只注入那六位数字再回车, 连着四次都到桌面)。**「注入密码」那一行让这条路不必先录手势**: `LockSteps.injected` 对空序列也收 (只有密码就行), 而密码落的还是 `LockSecret` 那唯一一处加密的存法 (Android Keystore 的 AES-GCM, 落盘只有 `iv:密文`, `unlock.json` 里一个字都不进去), 留空保存 = 连密钥一起删。**录制那一条是退路, 只被绕过不被删** (`injected` 是纯函数, `recorded` 与磁盘那份一个字节都不动), 所以三处文案分工是: 开关说"开启后更快"并点明"注入不可用就关闭本项改用录制", 「注入密码」说"仅供注入使用, 加密保存", 「录制解锁」那一行在注入开着时改说"若注入不可用, 关闭它即可改用本录制"。**「测试一次」现在先锁屏再走** (`LwPower op=lock` + 400 ms + `wakeScreen` + 重放): 手机开着锁的时候那一趟一步都走不到, 测出来的"成功"是假的 —— 也正是这一条让它能拿来测注入
- 远期点子: `ActivityOptions#setLaunchDisplayId()` 能把自己的 Activity 启到虚拟屏上, 让模型直接操作 dsh GUI

### 别做的事

- **不要用 Miuix 重写会话界面**
- **不要去跑 `scrcpy-server`, 也不要从 MAA-Meow 抄代码** (那是 AGPL), 只学做法; 反射隐藏 API 的包装若真要抄, 从上游 `Genymobile/scrcpy` 取 (Apache-2.0) 并保留 NOTICE —— 本仓的 LICENSE 也是 Apache-2.0, 那份 NOTICE 留得下
- 不要试图把 `SurfaceView` 塞进 WebView
- **不要把虚拟屏预览放进 `TopAppBar` 的 `bottomContent`**: 高度上完全一样, 只是让画面落进顶栏的 `clipToBounds` 与吞点击的那层 Layout, 还被绑上顶栏的滚动折叠
- 不要给 Card 里的设置项再套一层 `padding(16.dp)`
- 不要为了"让模型能操作屏幕"去写 MCP server —— 先写 dsh 原生工具 (见「dsh 工具怎么加」)

## 特权通道

链路: **模型 → dsh 工具 → Node host → app 的 loopback 桥 → binder → 特权进程 (uid 0 / 2000)**, 全部细节在 `docs/step3-record.md`。要记住的:

- `native/launcher.c` → **`liblauncher.so`** (cmake 出的可执行文件当 native lib 发), setenv `CLASSPATH` 之后 fork + exec `app_process`, **身份不动** (su 给 root, Shizuku 给 shell)
- **不用 Shizuku 的 user service**, 直接经 AIDL binder 调 `IShizukuService.newProcess()` (API 13 里 `Shizuku.newProcess` 是 private 且准备移除)
- **回传 binder 必须经 app 自己的 ContentProvider, 且要用 `IActivityManager.getContentProviderExternal` 拿** —— `ContentResolver.call` 走不通 (`app_process` 起的进程没有 `IApplicationThread`, AMS 直接 `SecurityException`); 副作用正好是要的: provider 里 `Binder.getCallingUid()` 看到的是 0 / 2000 而不是 1000。provider 校验 uid 加一次性 token
- `channel/LwPrivilegedService.kt` 是**手写的 `Binder`** (没有 AIDL), 因为 AIDL 会生成 Java, 而 Java 编译会把 AGP 9.4.0 那条坏掉的资源管线拉进图里
- `channel/PrivilegedBridge.kt` 是给 host 用的 **loopback TCP** (临时端口 + 随机 token, 由 `DshHost` 用 `LW_CHANNEL_ENDPOINT` / `LW_CHANNEL_TOKEN` 传过去); 协议是**一连接一请求一行 JSON**, 方法都在 `dispatch()` 里
- **抽象缝是三条不是两条**: `RemoteAccessPermissionBackend` / `ProcessSpawner` / `RemoteServiceConnectorBackend`
- **「有没有 root」在 app 侧查不到** (KernelSU 对没在名单上的 app 把 `su` 整个收走), 只能试, **而且试也不会弹出任何框**: 授权只能**用户在 root 管理器里手动给一次**。设置页「特权通道」段只列状态加一个**只在没连上时出现的**「连接」按钮 (定位是**重试**, 不是入口), **root 那行在试过之前是"未检测"** (`RouteState.granted` 因此是 `Boolean?`)
- **通道在应用启动时就连** (`DshHostService.onCreate` 里一个后台线程跑 `PrivilegedChannel.ensure()`): 连一次要起一个 `app_process`, 是秒级的, 不做这件事那笔账会落在**模型第一次截图**上。所有问系统的调用都在自己的工作线程上 (第一次连接可能等到人点完框, 主线程上等就是 ANR)
- 依赖: `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`, root 用 `com.github.topjohnwu.libsu:core:6.0.0` (在 jitpack 上, 所以 `settings.gradle.kts` 里有 jitpack 源)

## 虚拟屏

**「预览零 native」成立**: 屏的输出 surface 就是 `SurfaceView` 的 surface, 合成器直接把画面写进去, 不编码不解码、不开 socket、没有 native。代价是它跟着窗口走, 所以 **detach 是一等状态** (屏继续活着), 而**截图不依赖预览** (走 `screencap`, 无头也能拍)

链路: 菜单 → `VirtualScreen.create()` → 特权进程 `DisplayManager.createVirtualDisplay` (隐藏 flag `TRUSTED` / `OWN_FOCUS` / `OWN_DISPLAY_GROUP`, 要 `CAPTURE_VIDEO_OUTPUT`) → 屏的输出面由 app 交进去 → 预览上的手指经 `injectInputEvent` + `setDisplayId` 注回那块屏

要记住的:

- **buffer 要等于屏的尺寸** (`holder.setFixedSize(w, h)`): 合成器不缩放, buffer 小一圈就是**裁掉左上角**; 视图再按屏的宽高比撑高, 缩放是 surface 的事。盒子高度 = `min(宽度 * 0.75, 宽度 * 屏高 / 屏宽)` (竖屏最多 4:3, 横屏按短边收)
- **换屏要换 SurfaceView** (`key(displayId)`): 复用同一个 surface 时旧 buffer 留着上一块屏的最后一帧, 而**空屏不产生新帧把它顶掉**
- **`screencap -d` 要 compositor 的 64 位 id, 不是逻辑 displayId**, 只能按**屏名**从 `dumpsys SurfaceFlinger --display-id` 里找 (所以屏名唯一: `Luwi 1` / `2`), 而且它是无符号 64 位, **别进整数**
- 屏的 `ownerUid` 是 **0** 而 `ownerPackageName` 是我们的包名, `canHostTasks` 报 `false`, 但 `am start --display <id>` 照样起 activity 并正常渲染
- **触摸要排队**: 手比跨进程快, 同步注入会让 move 超过它所属的 down 被平台丢掉, 用一条单线程队列串起来
- **一块屏可以被换成别的形状**: `lw_screen_resize` 与它的别名 `lw_screen_rotate` (宽高对调) → `DISPLAY_RESIZE` → `VirtualDisplay.resize`。**尺寸就是应用拿到的那份配置** —— 只会横屏的游戏要的是一块横屏的屏, 转画面是转不出来的; 应用**不会因为屏换了形状就重排** (跟着屏走的铺满, 声明了方向的原样留一条带子居中), 所以"建屏时就把形状定对"对锁方向的应用是真要紧的。两个实现细节: 预览要按新尺寸重新 `setFixedSize` (buffer 不跟着换就挨裁); **换完尺寸再拍的第一张图是换之前那一帧**, 所以 app 侧按 PNG 宽高重拍到对上为止 (实测第二次就对)
- **⋮ 级联菜单**第一层是 `虚拟屏` (二层: 屏列表单选 / 暂停与继续接受控制 / 关闭这块屏) / `虚拟屏触摸控制` / `设置`; **没有"新建"** —— 建屏是 dsh 工具的事, 桥的 `create` 带 `name` / `width` / `height` / `dpi` (重名自动补序号), 界面只负责看和关。设置页用 `miuix-nav` 推入 (`NavKey` 必须 `@Serializable`)
- **屏上的操作有两道闸, 都在用户手里**: ⋮ 菜单里的「暂停接受控制」让 `lw_tap` / `lw_swipe` / `lw_tap(text=…)` / `lw_launch` 一律回一条点名是哪块屏的错, 而**看的不拦** (`lw_screenshot` / `lw_ui` / `lw_screen` 照旧); 一级菜单里的「虚拟屏触摸控制」**默认不选中**, 关着时手指滑过预览一个触摸事件都不会送到屏上 (故意放一级菜单而不是设置页: 它是"现在这块画面能不能摸")

代码: `channel/LwVirtualDisplay.kt` / `LwInput.kt` / `LwCapture.kt` (特权侧), `channel/VirtualScreen.kt` / `VirtualScreenPreview.kt`; 给 host 的桥是 `screen` / `screenshot` / `create` / `resize` / `release`

## dsh 工具

工具全在 `host-plugin/index.mjs` 一个插件里, 每个一次桥调用: `lw_probe` / `lw_screen` / `lw_screen_create` / `lw_screen_resize` / `lw_screen_rotate` / `lw_screen_release` / `lw_tap` / `lw_swipe` / `lw_screenshot` / `lw_launch` / `lw_key` / `lw_type` / `lw_ui` / `lw_ocr` / `lw_apps`

**1.0.2 又加了这些**, 都在同一个插件里, 由 `simpleTool` 那个工厂生成 (它们的形状一样): `lw_notify` / `lw_vibrate` / `lw_clipboard` / `lw_share` / `lw_open_file` / `lw_download` / `lw_device` / `lw_battery` / `lw_storage` / `lw_running` / `lw_volume` / `lw_media` / `lw_net` / `lw_system` / `lw_sensor` / `lw_location` / `lw_permissions` / `lw_power` / `lw_app_control` / `lw_wait_for` / `lw_ui_dump` / `lw_gesture` / `lw_pinch`

**1.0.3 加的 (共 48 个工具)**: `lw_scroll` (走无障碍的滚动动作, 不注入触摸) / `lw_keep_awake` (`PARTIAL_WAKE_LOCK`, 谁开谁关) / `lw_key_combo` (`input keycombination`, 二到四个键) / `lw_intent` (`openUrl` 打开 http(s) 链接, `intent` 按动作或组件起一个 activity) / `lw_files` (工作区里的列 / 读 / 写, 每一条都带上"手机怎么看这个文件") / `lw_media_scan` (让媒体库看见一个路径) / `lw_take_photo` (系统相机拍一张, 落在工作区的 `photos/`) / `lw_notifications` (读通知栏与清通知) / `lw_events_subscribe` 与 `lw_events_wait` (事件订阅: 等到一件事发生, 而不是反复读屏)。另外 `lw_app_control` 多了 `enable` / `disable` / `setHome`, `lw_screenshot` 多了分区 (`x` / `y` / `width` / `height`) 与连拍 (`count` 最多 12 张 · `intervalMs` · `sheet`), `lw_type` 多了 `x` / `y` (先按那一点再打字), `lw_notify` 多了 `banner` (全屏 intent)。**`disable` 与那四条破坏性的一样算危险档** (2026-10-08 起改由提示词层问用户, 见「破坏性操作」那一节) —— 它比停应用更粘: 被停用的应用从桌面上消失, 要有人记得回去打开

**`lw_files` 是三条路里最容易被写成重复的那一条**: 这个会话自己就带着 `read` / `write` / `glob` / `grep` / `bash` (`packages/fs/*` + `packages/shell/*`), 而工作区就是那套工具的家 (进程 cwd 与 home 都在那儿), 所以"在工作区里读写一个文件"模型本来就会做 —— 加三个同名的工具只会让它在两个都行的选择之间犹豫。所以它**只做 app 这一侧拿得到的三件事**: 手机怎么看这个文件 (媒体库有没有它、系统认的 mime、一张图多大), 一个不随会话目录漂移的锚 (工作区是 `Workspace.resolve` 解析出来的, 而会话的目录是用户在界面里选的), 以及**写完顺手让系统看见** (两步动作模型只会记住一步)。围栏照计划书: 只认工作区里的路径, 越界一律拒

**它们大多不过 binder**: 通知、剪贴板、电池、音量、系统设置这些在 app 进程里用 `Context` 就能做, 走这条回环桥只是为了把结果按同一套协议回给 host。实现都在 `app/src/main/java/.../tool/` 下, 桥那一侧 (`PrivilegedBridge.dispatch`) 只有一行转发

三条写这批时定下来的规矩, 以后加工具照办:

- **每个能力先过权限闸** (`util/PermissionGate` + `PermissionCatalog`): 缺权限时回的是"缺哪一条、怎么给", **不假装成功** —— 这台设备上"退出码 0 而什么都没发生"已经坑过一次 (无障碍那条), 所以写入一律**写完读回**再报结果
- **答案由应用那一侧写**: 桥回来的 `result.text` 才是给人看的那一句 (只有它知道权限、设备、退出码), 插件用 `answerOf` 原样念, 不自己编话
- **只有 app uid 真做不到的才走特权**: 卸载 / 清数据 / 停应用 / 装包 / 停用 / 飞行模式 / 移动数据 / 蓝牙 / 熄屏。它们在特权进程里过一张**写死的白名单表** (`channel/LwSystemCommandTable.kt`), 应用送过去的只是一个操作名与几个参数 —— 那张表就是"模型能不能凑出一条任意命令"这个问题的答案 (**1.0.3 起那张表里还有 `enable` / `setHome` / `openUrl` / `intent` / `keyCombo` 五条**; 通用的 `intent` 是唯一一条参数上限放宽到 8 的, 因为动作、数据、组件与目标屏放不进四个)。**破坏性那五条调用即执行, 闸门在提示词层** (见下)


**每个动作都显式带 `displayId`**, 没有"默认打选中的那块" —— 选中的是用户随时能改的, 而模型手里的坐标是它在某一块屏上量出来的。**用户没说用哪块屏就用虚拟屏**: `displayId 0` 是别人手里那台手机, 动它就是把它从人手里拿走, 而那块屏自己的形状是能给的 (`resize` / `rotate`), 主屏的不行 —— 这条写在 `DISPLAY_ID` 那个共用参数与 `lw_screen` 的描述里

要记住的:

- **双开的应用是另一个 Android user, 不是一个新包名**: HyperOS 的双开 = `user 999` (名字 `XSpace`), 包名 / APK / 启动组件与原版**一模一样**, 只有 userId 不同 —— 所以 `lw_launch` 有 `user` (`am start --user 999`), 那个数字来自 `lw_probe` 里多问的一句 `pm list users` (app uid 问不了这条命令, 它是特权侧的活)
- **列应用只能这么列**: `lw_apps(user?, query?)` 给"能启动什么" (名字 + 包名), `lw_launch` 也认名字 (对上不止一个就什么都不起)。**模型的 bash 列不出来**: `pm list packages` 不带 `--user` 要跨 user, 而 `INTERACT_ACROSS_USERS_FULL` 是 signature 权限; 带上 `--user 0` 又只看得见它自己 (Android 11 包可见性) —— 所以名单来自 app 侧 `queryIntentActivities` 与 manifest 里那行 MAIN/LAUNCHER 的 `<queries>` (窄声明), **每个 user 装了哪些**来自特权侧 `pm list packages --user N`
- **`queryIntentActivities` 看不见一个明明在的应用, 而且它会骗人**: `lw_take_photo` 要用它点名是哪个相机接活, 而 `<queries>` 里只有 MAIN/LAUNCHER 时它**空手而归** —— 于是"这台设备不能拍照"会是一句假话。修法是两条一起: 清单里加一条 `IMAGE_CAPTURE` 的 `<queries>` 声明 (窄声明, 不是 `QUERY_ALL_PACKAGES`), **并且**空手而归时照样去 `startActivity`, 真的没有接收者由 `ActivityNotFoundException` 说出真相
- **`lw_launch` 只能走特权进程**: `am` 以 `com.android.shell` 自居, app uid 调它一律被拒 (模型的 bash 也是 app uid), 所以是特权进程里 `ProcessBuilder("/system/bin/am", …)`; **包名先解析成组件再起** (`cmd package resolve-activity --brief -c LAUNCHER <pkg>` → `am start -n <component>`), 因为 `am start -p` 那条路带 `MATCH_DEFAULT_ONLY`, 而 Flutter / Unity 的 manifest 不写 `CATEGORY_DEFAULT`。不走 `startActivity` + `setLaunchDisplayId` 是因为撞 BAL
- **按键与打字是另外两条路**: `input keyevent` / `input text` 撞的是与 `am` 同一堵墙 (INJECT_EVENTS), 而 BACK / HOME / 音量这类**平台自己处理**的键不在任何屏的树里 —— 所以按键走特权进程 (`INPUT_KEY`), **键传名字不传编号**, 表从 `android/keycodes.h` 生成 (`tools/gen-keycodes.mjs`): 名字不认识可以拒, 编号不认识就是**另一个键** (5 是打电话, 26 是电源键); `HOME` / `POWER` / `SLEEP` / `SOFT_SLEEP` 只在主屏放行 (我们的屏没有 launcher, 发完 HOME 那块屏 `state OFF` 而截图照旧交旧帧)。**打字优先走无障碍**: `ACTION_SET_TEXT` 写焦点字段的文本, 中文与 emoji 都行, 也不需要 IME; 整块屏没有任何字段时才退回按键 (`INPUT_TEXT`, 只有 ASCII, 中文直接拒), 两条路用 `via: field|keys` 分开
- **`tap` / `swipe` / 按住都阻塞到设备收下为止** (队列保顺序, `.get()` 保"做完了"), 而预览的手指仍然只往队列里丢: 工具返回后模型马上会截图看结果, 所以"已排队"对它没用
- **`swipe` 是一次事务**: 特权侧按 `durationMs` 均分 12 步, **每一步至少睡一帧 (16 ms)** —— 一批同毫秒的 move 在平台看来是跳, 分帧读输入的应用 (Unity 那种) 会把"按下又抬起"当成**一次点击**; 分步放到 app 侧又会让手势快慢随 binder 负载漂移
- **按住多久是一个参数, 不再是一个布尔** (`lw_tap(hold=…)` / `lw_key(hold=…)`): 值是**字符串**, 认 `"1s"` / `"500ms"` / `"1.5s"` / 裸数字 (按秒), 也认 `short` (600ms) / `medium` (1.5s) / `long` (3s); 上限 10s。**三个名字都压在平台自己的长按阈值 (500ms) 之上那一小段**, 因为那才是分界线, 真要用 `"8s"` 写出来 (8 秒的电源键在很多机器上是硬重启, 不该有一个 `long` 随手就能碰到)。设备侧: 长按 = 按住那么久 + UP 带 `FLAG_LONG_PRESS`; 只差一点点的 (500-650ms) 补到 650ms, 因为平台的检测器就在那一刻跑; `lw_tap(text=…)` 带 hold 时优先用节点的 `ACTION_LONG_CLICK`, 树里没有就用**按住的手指**落在它的矩形上
- **截图落 `<工作区>/screenshots/screen-<id>.png`** (特权进程先写 app 的 cache, app 再拷进工作区): 模型的文件工具只在工作区里解析路径, 留在 cache 里就是能告诉它路径、它永远打不开
- **截图在产生时同时缩到两个预算** (像素与字节都按设置页那两条): **只按像素缩不够** —— 一整屏游戏画面在 536x1192 就能压到 1.29 MB, 而超了预算就要在 host 那边重编码, 多一次往返也多一次质量损失。`Picture.fit` 会对同一个画面编码到装得下为止 (猜一版 → 量真实字节 → 往预算内放大回去, 最多 4 轮)。**1.0.3 之前这条还会把整轮请求打死**: 那时的 `sharp` 是只解 PNG 的替身, 重编码必抛, 抛出被包成 `TRANSPORT` (可重试), 重试 5 次后本轮失败, 而那张图留在上下文里, 之后每轮都再失败一次 —— 现在 host 侧真能重编码了, 但"截图时就缩到预算内"仍然是省事的那条路。滑块是搬来的 SFA `ArrowSlider` (`scaffolds/`, 点标题那一行可打字给精确值)
- **截图的描述里写明了它会很小** (1080x2400 可能只有 536x1192), 所以"读屏用 `lw_ui` / `lw_ocr`, 截图只用来'像人一样看一眼'"这句话进了 `lw_screenshot` 的描述
- **连拍是"一段过程"那条路, 而且量到的间隔才算数**: 上限 12 张 (原来 5 张), `intervalMs` (50..5000, 默认 120) 说的是**墙钟上两张之间隔多久**, 不是"拍完再歇多久" —— 后者会让真实间隔随设备忙闲漂移, 而一次截图本身要两三百毫秒, 所以做不到时答案报的是量到的 `offsets` 而**不是要的那个数** (`spanMs` 与 `(count-1) × intervalMs` 差得多就直说是这台设备拍不了那么快)。`sheet` 把这几张拼成一张网格 (`VirtualScreen.contactSheet`: 先按预算把每一格缩到位再拼, 而不是拼一张大的再整张缩一遍 —— 后者要在内存里开一张 12 倍大的图), 用一次读图换掉十二次; 但**它是用来看动起来的, 不是用来量坐标或读小字的**, 所以它不进"图上的点乘多少回到屏幕"那一套 (每一格都是小副本, 原图都还在)
- **host 树里的 `sharp` 是官方 0.35.5 的 WebAssembly 构建** (`sharp` + `@img/sharp-wasm32`, 由 `pack-host.mjs` 当普通依赖装进树里): 原生那条路在这台设备上是死的 (平台包裹的 libvips 按 glibc 编, 而且 `--omit=optional` 本来就不装), 而 wasm 不需要任何原生 binding, sharp 自己会挑。**1.0.3 之前树里放的是一个只解 PNG 的纯 JS 替身**, 代价是相册里的 JPEG / WebP / GIF 一律 `INVALID_IMAGE`, 而且超过 route 预算的图必然把整轮请求打成 `TRANSPORT` —— 那条限制随替身一起没了, 历史见 `image-backend/README.md` 与 `docs/step5-record.md`
- 验证用 `tools/lw-bridge.ps1` (单次桥调用) 与 `tools/lw-device-turn.ps1` (run-as 起一次 headless turn, 用设备自己的树和凭据); 但**读图不能在 run-as 里验** —— 它不给 app 的 mount namespace, 而且打不开 `/data/user/0` 的祖先目录
- **桥验的是应用那一侧, 插件那一层要另验**: `defineTool` 的参数表 -> `drop(args)` -> 一行 JSON -> `answerOf` 这段只有在模型调用时才走到, 所以有 `tools/lw-plugin-call.mjs` —— 它用与自检同一个注册表把工具取出来直接 `execute`, 不花模型的钱 (2026-10-04 就是它试出 `lw_media_scan` 只认绝对路径而 `lw_files` 认相对路径这条不一致)。用法: 先把端口 forward 到本机, 再 `LW_CHANNEL_ENDPOINT=127.0.0.1:<端口> LW_CHANNEL_TOKEN=<token> node tools/lw-plugin-call.mjs lw_files '{"op":"list"}'`
- **两个 PS 脚本与 `push-host.mjs` 里的 adb 路径是开发机那一台的** (`B:\Software\AndroidSDK\...`), 换机器时用 `$env:LW_ADB` 覆盖, 不用改文件

## 主屏 (displayId 0)

**`displayId 0` 就是手机自己那块屏**: 看与动都能指它, 与虚拟屏走同一套调用。它**不进 `screens` 列表** (不是我们建的, 没有预览也没有暂停), 尺寸每次现读 (跟着旋转变), 截图走不带 `-d` 的 `screencap`, `release 0` 有一句专门的拒绝理由

**2026-10-08 起, 触摸刹车 (软停) 整个删掉了** —— 主人点名的决定。原来那套是: 特权进程直读触摸屏的 evdev 节点 (`channel/LwTouchWatch.kt`), 摸到玻璃就算数, 于是 app 侧动手前问一次、拖动中每 8 ms 问一次, 真手指一来就**拒绝调用 / 当场把手势掐断**, 并回一句让模型收手。现在这些一个字都不剩:

- **没有任何应用层刹车**: 模型的屏幕调用不会因为"用户正在用手机"被拒, 一个手势一旦开始就跑到底。按住与拖动的时长还在, 但它们不再被中途打断
- **看的那一条路从来就没拦过**: `lw_screenshot` / `lw_ui` / `lw_ocr` 照旧
- **还剩什么**: 虚拟屏那两道**用户自己开的闸** —— 菜单里的「暂停接受控制」与「虚拟屏触摸控制」(那道闸只管虚拟屏, 主屏本来就不归它管)。主屏现在只靠**提示词层**的规矩: 模型要先说清自己在动主屏, 动作做完要如实回报
- **删掉的东西**: `channel/LwTouchWatch.kt` (整个文件) / `TOUCH_STATE` 那条事务 / `LwInput` 里的 `watch` 与 `brake` 参数 (协议里那几个 `brake` 标志一起没了) / `VirtualScreen` 的 `requireUserNotDriving` / `lw_probe` 里的 `touch watch:` 那一行, 以及工具描述里那几句承诺

**留着的量具**: `tools/lw-fake-touch.sh` 还能往 evdev 节点写事件伪造一只真手指 (`sendevent` 进的是 input core)。它现在的用处反过来了 —— 伪造一只按着的手指, 然后看主屏调用**照样成功**, 就是"软停真没了"的那条判据。**伪造的手指会被系统当真**, 挑一个被点到也无所谓的界面

**2026-10-08 起, 指代不明的一句话会自己带上一张主屏截图** (2.5.0 批次 4, 需求的第 9 条): 宿主插件那条投递链 (`voiceDeliver`) 在把浮标输入框 / 语音来的那一句送进会话之前, 先看它有没有那几种指着东西说的口气 —— 词表是 `SCREEN_REF_WORDS` (这个 / 这张 / 这个图 / 这张图 / 屏幕上 / 屏幕里 / 照片里 / 图里 / 这份, **子串命中**, 与命令表那套整句相等正好相反), 命中就按 `displayId 0` 截一张, 把图块与正文一起放进**同一条用户消息** (正文一个字不改, 界面上那张图与主人自己发的长得一样)。两道闸: **视频模式里不附** (那时「屏幕」指镜头), 设置页「截图」段那条「指代不明时自动截图」关着也不附 (插件读它走 `screenshot op=status`, 读不到按开 —— 与朗读那条链同一个口径)。**GUI 里打字的那条路拦不到** (那些消息走 dsh 自己的 rpc), 那一半由 `assets/modes/phone.md` 第 31 条兜底。读数在 `lw_voice op=inbox` 的 `autoShot` 里 (试了几条 / 附上几条 / 最近一条为什么没附), 判据在 `tools/check-auto-shot.mjs` (13 条)。

**识屏模式在同一批里摘掉了**: 模式只剩手机与视频两个。原因就是上面这一条 —— "看手机自己那块屏"本来也在手机模式里 (display 0), 而"用户得先切一个模式, 模型才看得见屏"是多余的心智负担。三处都清了: `LwModes` 的常量与 `ALL` (`SCREEN_RETIRED` 那个常量留着, 只为把老机器 `.active` 里的 `screen` 迁回手机模式) / 浮标菜单那一行 / `assets/modes/screen.md` 与 `screen.sh` (仓库里删掉, 设备上由 `seed` 的退役名单删)。命令词表那一侧: **"退出 / 关闭 / 关掉识屏模式"仍然当收工回手机模式**, 而"打开识屏模式"不再是命令 —— 它照普通一句话进会话。

## 语音 (两档引擎)

「说话 → 出字」这条路有**两档, 由 `engine=` 选**: SenseVoice (sherpa-onnx, 234 M, 在 app 进程里) **快**, 一句话不到 1 秒, 它是**处处缺省的那一档** —— 常驻语音链 (唤醒词命中之后那一路)、浮标对话与 GUI 那个录音按钮都走它; **GLM-ASR-Nano** (智谱, 1.5 B, MIT) **准**, 中英与粤语/方言、小音量的表现明显更好, 代价是一句话几秒到几十秒, **只有点名 `engine=glm` 才会走到它** (2026-10-09 主人定的口径: 输入框与球用同一套模型, 于是这台设备只下 240 MB, 不再为了那个按钮拉 1.6 GB)

要记住的:

- **GLM 那一档是 app fork 出来的常驻进程, 不是一个库**: `app/src/main/native/glmasr/` 编出来的 `libglmasr.so` 是**程序** (静态链 llama.cpp + mtmd), 与 `liblauncher.so` 同一个理由 (安卓 10+ 不让 app exec 自己 data 目录里的东西, `nativeLibraryDir` 里的可以), 起来之后 `{"wav": ...}` 一问一答, 模型只在进程启动时读一次。它在 APK 里只占 **3.7 MB** (strip 过), 但常驻要 **1.8 GB 内存**、权重占 **1.6 GB** (Q4_K 主模型 + Q8_0 音频编码器), 所以 `lw_speech op=release engine=glm` 是"把它还回去"那条路
- **它慢的全部原因是那 30 秒静音垫**: llama.cpp 的 mtmd 走 whisper 那套预处理, 不管录音多长都先补 30 秒, 于是 1.5 秒的一句与 30 秒的一段一样贵 (实测都是 12 秒上下)。**这份集成里唯一改上游的地方**就是把那个常数变成 `LW_ASR_PAD_SECONDS` (缺省 4 秒), 补丁是 `app/src/main/native/glmasr/patch-short-window.cmake`, 由 FetchContent **打完源码之后**打 (本地 checkout 走 `-DLW_LLAMA_CPP_DIR=`, 那一份要自己先打)
- **mel 长度要按 8 帧对齐**: 图里向上取整、`clip_n_output_tokens` 向下取整, 两边只在 8 的倍数上相等, 不然 `clip_encode` 直接 `GGML_ABORT("Invalid number of output tokens")`。补丁里那句 `(n_len + 7) / 8 * 8` 就是这个, 别当装饰删掉
- 实测 (天玑 9300, 4 秒窗口): 在 `/data/local/tmp` 里跑那一份是 1.5 秒的话 3.7 秒、7.8 秒的话 10.6 秒、19 秒的话 22 秒; **装进 app 之后是 5.2 / 22.6 / 27.0 秒** (整机内存见底 + 热降频, cgroup 是 `top-app`, 八核全给); 七条合成样本里六条与原文逐字一致, 唯一那条数字串两种窗口都错 (TTS 念的数字本身难)。常驻 **2.76 GB RSS**, `lw_speech op=release engine=glm` 是还回去那条路
- **它必须是 `-O3`**: AGP 给 externalNativeBuild 的 Debug 变体传的是 `CMAKE_BUILD_TYPE=Debug`, 也就是一个 `-O` 都没有, 而 llama.cpp 在 `-O0` 下慢四十倍 (实测 137 秒 vs 3.7 秒, app 里那条路 225 秒 vs 5.2 秒)。补法在 `app/src/main/native/CMakeLists.txt` 的 `CMAKE_*_FLAGS_DEBUG` 那一段, **别删**: 这份二进制是黑盒, 慢起来 app 里没有一处会喊
- 细节与判据在 `docs/voice-input.md` 的第八点五节, 代码在 `tool/GlmAsr.kt` (进程与协议)、`tool/LwSpeech.kt` (两档的路由)、`host-plugin/index.mjs` (下载与 provider)
- **官方 bundle 开着时, 它自带那个 provider 会被按 id 关掉** (`PluginOverlay` 的 `VOICE_LOCAL_ROW`, 2026-10-09): 那个 provider 靠 `sherpa-onnx-node` 的原生 addon, 而 npm 上没有 android-arm64 那一份 —— 它一挂出来就是插件页上一张写着 `Local speech is unavailable for android-arm64` 的红卡 (那个句子出自 `speech-to-text-sensevoice/src/runtime.ts`), 按「重试准备」永远不会好, 组件那一行还会报 `1 异常`。所以 bundle 开着时 overlay 除了把 `defaultProvider` 指到 `lw-native`, 还给 `speech-to-text-sensevoice` 一行 `disabled: true` (真机上就是这么看到的: 并排两张卡, 一张已就绪、一张永远失败)
- **我们的 provider 报给界面的是 dsh 的 `SpeechPreparationState`**: `downloading` 认 `resource` / `completedBytes` / `totalBytes`, `failed` 认 `message` (外加可选的 `download` 诊断 —— `speechFailureOf` 把 Node 的错误码翻成 `dns` / `timeout` / `http` / `integrity` / `storage` / `network` 那一套, 界面据此给处置建议), `checking` 认 `startedAt`。**名字写错不会有任何报错**: 界面只会把进度显示成 NaN、把失败原因吞掉 (原来写的是 `detail` / `bytes` / `total`)

**这块还没做完的**: 设置页没有引擎开关 (现在只有插件常量与 `engine=` 参数); 1.6 GB 的下载不能续传, 而且**没从零下过一次** (测试那次是两个 GGUF 直接从 adb push 进 `speech-models/glm-asr/` 的, 手机侧的通路另验过: 同一个 URL 4 MB/1.5 s、16 MB/1.9 s); GUI 那个录音按钮的整链还要人按一次才算验过 —— **2026-10-09 那个按钮改成 SenseVoice 之后这一条要在真机上重验** (下 240 MB 那一份, 然后按一次按钮出字)

**另外**: `:app:packHostTree` 在 2026-10-06 升级到 dsh 0.2.1-alpha.1 时坏过一次, 报 `[@deepseek-ai/dsh-root] Cannot find entry: ["lib/types/{index,startup}.js"]` —— **真因不在根包**: 0.2.1 删掉了 `packages/experimental/schedule-bundle` 与 `packages/runtime-diagnostics/invariants` 两个包, 而升级只删文件、留下带 `node_modules` 的空目录; tsdown 的工作区 glob (`packages/*/*` / `vendor/*`) 会把**没有 `package.json` 的目录**当成成员, 读不到 manifest 就向上读到**仓库根**的 manifest, 于是拿根包的名字与根包的入口去解析, 才找不到。两条修法都已落地: 删掉那两个空壳目录 (里面只有被忽略的 `node_modules`), 以及 `tools/pack-host.mjs` 现在会在官方构建之前 `dropOrphanPackages()` 把这类没有 manifest 的包目录清掉。**只换插件、不重打树的绕法** (把 `host-plugin/index.mjs` 直接换进 `app/build/host-tree/node_modules/luwi-channel/`, 再用 `-x packHostTree` 出包) 只够插件改动: **升 dsh 本体必须让 `packHostTree` 真的跑通**, 否则 APK 里带的还是上一次打出来的旧树

## 无障碍读屏

链路: `LwAccessibility` (**跑在 app 进程里**, 系统绑定的服务) → 取树 / 定位 / 点 → `PrivilegedBridge` 的 `ui` / `tapText` (同进程直接调) → 工具 `lw_ui` / `lw_tap(text=…)`。**取树没有 binder, 也没有特权进程**

**开服务这件事与读屏是两码事**, 三条要先记住:

- **带 `-i` 只是必要条件**: `installerPackageName` 非 null 才过得了 Android 13 那道闸, 而重装会把我们
  从 `enabled_accessibility_services` 里抹掉, 所以"装完立刻读出来改再写回去"是同一个动作的两半
- **有些 ROM 上应用自己写不动 secure settings**: 实测那台 vivo 上 `settings put` 对**任何 key** 都
  返回 0 而值不变 (丢弃在 provider 层, 换 uid / 换通道都没用), 写入只在带 `-i` 重装之后很短的一段
  窗口里被接受。**所以正解是电脑跑 `tools/lw-install.ps1`, 应用侧的定位是如实报告**
- **"设置里写着"不等于"服务活着"**: 打开 vivo 那个页面会摘掉条目却不杀已绑定的实例。判据是
  `LwAccessibility.running` (系统的绑定实例), 而 `LwPermission.inspect()` 一次把六件事实读齐
  (在不在列表 / 主开关 / 实例 / installer / 那道 op / **写入通路通不通**), 设置页与 `lw_probe` 共用它

`AccessibilityState.reason()` 按这六件事实给一句结论, 不再把"别的应用改了回去"与"受限设置挡着"并列
—— 两者处置完全不同, 而含糊的提示误导过一次

- **`getWindows()` 只返回默认屏**, 虚拟屏不在里面, 必须用 `getWindowsOnAllDisplays()` (API 30+) —— 只看前者会以为"无障碍读不到虚拟屏", 然后去写贵一个量级的 `UiAutomation`
- **坐标是那块屏自己的原点**; 事件会从虚拟屏来; **`ACTION_CLICK` 在虚拟屏上有效**, 所以按名字点击不需要坐标也不需要注入触摸
- **`getBoundsInScreen(Rect)` 不是表达式**, 返回 `Unit`, 得先 `val rect = Rect()` 再传, 否则是编译期的 `ARGUMENT_TYPE_MISMATCH`
- 只留"值得说的"节点 (有 text/desc 或 可点/可滚/可输入/可勾选), 每个节点还算出**它的可点祖先**当 `target` (所以能打出 `at [...] press row [...]`); 上限 400 节点 / 40 层。**引擎自绘的界面不是空树而是"没用的树"** (Phigros 只有一个全屏 `SurfaceView`), 判据是"有没有值得动手的节点"; Flutter 的语义树**读得到**
- **匹配从窄到宽** (text 全等 → desc 全等 → text 包含 → desc 包含), 所以 `lw_tap(text="返回")` 能命中只有 `desc="返回"` 的返回键
- **一行里的标题和副标题是两个节点一个行**, 候选按"按下去按到哪个节点"去重; 去重后仍不止一个就**什么都不按**, 把候选连矩形返回 (**最多 12 条**, 工具那句"多少个"是上限不是总数)
- `ACTION_CLICK` 返回 `false` 时退回在 target 中心注一次真触摸 (`via: "finger"`), 因为少数自绘控件不吃无障碍动作; 树已经说了东西在哪, 这次注入不是猜
- **开启服务只能由特权进程写 `Settings.Secure`**, 而 `Settings.Secure` 在特权进程里**走不通** (`app_process` 没有 `IApplicationThread`) —— 正解是 `ProcessBuilder("/system/bin/settings", "get"/"put", ...)`。写入必须**读出来改** (设备上还有别人的无障碍服务)、开启时**先**写 `accessibility_enabled=1` 再写组件列表、写完**读回来核对**。**但读取不能走那条命令**: `/system/bin/settings` 是一条 shell 命令, Android 14 起对非 shell 的 uid 直接 `SecurityException: getCurrentUser() ... requires INTERACT_ACROSS_USERS`, 于是应用自己读回来的永远是 null —— 设置页与 `lw_probe` 会把"组件在列表里"说成"不在" (2026-10-04 实测, 已改成读 `ContentResolver`, 那条命令只留给特权进程当兜底)
- **光写设置不够, 还有两件事**: 组件**已经在列表里但没被绑上**时 (强停之后就是这样), 把同样的值再写一遍不一定能让系统重新评估, 所以**先摘掉、停 800ms、再放回**; 而侧载安装的应用 (`installerPackageName=null`) 在 Android 13 起**不许开无障碍**, 那道闸是一个 app op —— 开启时顺手 `cmd appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` (best effort, 失败只记日志), 否则服务可能起不来而没有任何提示
- **重装 APK 会把我们踢出 `enabled_accessibility_services`**, 所以设置页那个开关不是可选项, 而且**开关自己就会经特权通道把服务打开**; 「开没开」不是设置而是"系统有没有绑上", 状态从 `LwAccessibility.running` 读, 写完等最多 3s 再报真实状态

## 通知栏

链路: `LwNotificationListener` (**跑在 app 进程里**, 系统绑定的服务) → 取当前通知 / 清一条 →
`PrivilegedBridge` 的 `notifications` (同进程直接调) → 工具 `lw_notifications`。**既不过 binder 也不需要
特权 uid**, 与无障碍那条一模一样 —— 而它要的那道授权同样由特权进程去给

- **那道授权只有系统自己那条命令给得到**: 通知使用权是 `enabled_notification_listeners` 这个 secure
  setting, 但**直写它是不够的**, 这是 2026-10-04 在模拟器上量出来的 —— 值写进去了、读回来也在、设备
  也留着, 而系统**根本不理它**: 服务不会被绑上, 系统那份"用户设过"的名单里也没有它 (通知那一页会把
  这个应用列在 **Not allowed** 下面, 直到有人点过那个确认框)。正解是 `cmd notification allow_listener
  <组件>` / `disallow_listener`, 也就是设置页上「允许」按钮走的同一条路 —— 双向都实测过:
  `disallow_listener` 之后服务真的解绑 (`running: false`), `allow_listener` 之后真的重新绑上。
  **所以 `LwPermission.setNotificationListener` 先走那条命令, 直写名单只当退路** (没有那条命令的
  设备), 而两者都以名单读回为准
- **首次授权要系统那个确认框**: 一个从没被允许过的应用, 命令/直写都可能不被采纳 —— 那时正解是在
  设置页「通知」那一段的入口里打开 Luwi 并点「允许」。这一段有两个 `ArrowPreference` (通知使用权 /
  全屏通知), 它们的 `startActivity` 都带**退到应用详情页**的回退 —— 那一页在个别 ROM 上没有接收者,
  而点击换来的崩溃最不该有
- **读写都要读出来改**: 那个名单是与别的应用共用的 (模拟器上本来就有 Google 的 AiAi 与 Launcher3
  两条), 比较用 `ComponentName` 而不是字符串 (同一个组件有两种拼法); 写完读回核对
- **重装 APK 会把它收走**, 所以装机脚本在装完那一次 shell 里连着给 (`cmd notification allow_listener`,
  失败才退回写名单), 而判据里多了"listener listed"一条
- **"通知栏是空的"与"读不到通知栏"长得一模一样**: 没有授权时 `activeNotifications` 也是空的。所以
  `LwNotificationListener.active()` 回 **null** 表示没连上, 而 `lw_notifications` 那时说的是"读不到"并
  把怎么开说清楚 (它去读一遍名单, 按"不在名单里 / 系统没绑上 / 系统说没有授权"分三种说) —— 这一条是
  三态纪律里最容易违反的一处
- **`StatusBarNotification.getRanking()` 不是公开 API**: 重要度与渠道要从 `NotificationListenerService
  .currentRanking` 那个 `RankingMap` 里点名取 (`getRanking(key, Ranking())`), 取不到就报"没说"而不是
  某个默认值
- **常驻通知不是"清不掉"**: `isOngoing` 的那些 (音乐、通话、下载) 是应用自己的, 工具如实说"留着没动",
  这不是失败。清完还要**再读一次**确认真的没了 —— 清一条是"请系统去做"
- **横幅那件事 (D4 已定: 只做渠道 + 全屏 intent, 不自绘悬浮窗)**: 渠道的**重要度在创建之后应用改不动**
  (那是用户在管的设置), 所以 1.0.3 新建了 `lw-tools-high` (IMPORTANCE_HIGH, 实测 `mImportance=4`) 并
  **删掉旧的 `lw-tools`** (实测删掉了); 而 `USE_FULL_SCREEN_INTENT` 从 Android 14 起对非闹钟/通话类
  **默认不给**, 判据是 `canUseFullScreenIntent()`, 所以 `lw_notify` 的答案会说自己到底会不会弹成横幅
  (两个分支都实测过: appop deny → "asked for but this app may not use full-screen intents", 恢复
  之后 → "comes up as a banner")
- **模拟器上 `cmd notification post` 投不出通知**: 它打印 `posting: Notification(...)` 而通知栏里一条都
  没有 (系统那份 `NotificationRecord` 列表里查不到), 所以验 `lw_notifications` 时别拿它造数据 ——
  用栏里本来就有的别人的通知, 或者 `lw_notify` 自己发的那条 (它 id 固定, 一次只有一条可清的)

## 事件订阅

链路: 系统把事件送进 `LwAccessibility.onAccessibilityEvent` (**app 进程里**) → 一条有界队列 →
`LwEvents` 的订阅 (游标 + 三个过滤条件) → 工具 `lw_events_subscribe` / `lw_events_wait`。**这一批改的
是模型的读法**: 按一下之后与其反复 `lw_ui`, 不如先说清"我在等什么", 再等它发生

- **掩码就是 `<xml>` 里那四个** (`typeWindowStateChanged|typeWindowContentChanged|typeViewFocused|`
  `typeViewScrolled`), **不用 `typeAllMask`**: 那四个涵盖了"换窗口/内容变/聚焦/滚动", 而全掩码会把每一条
  View 事件都灌进来。`notificationTimeout="100"` 是同一件事的另一半 —— 系统自己就把事件压到每秒十条上下
- **一条订阅是一根游标, 不是一份拷贝**: `Watch` 只记"读到哪了"与三个过滤条件, 事件本身留在队列里。
  所以订阅再多也不涨内存, 而"我订阅之前刚发生了什么"仍然读得到 (订阅那一步回看队列尾部几条)
- **队列是有界的, 而且这件事看得见**: 上限 200 条, **同一类事件 400ms 内连着来就合并成一行** (带 `xN`
  计数, 滚动因此是一行而不是几百行), 满了丢最旧的并累加一个丢弃计数。`lw_events_subscribe` 的答案里
  会印"缓冲里现在有几条 / 上限多少 / 这个会话丢过几条" —— 一个数字涨到上限就不再涨, 那才是"不会无限涨"
  的证据, 而不是一句注释
- **事件上没有 displayId**: `AccessibilityEvent` 只有 `windowId` (display 那个字段不存在, 用 `javap` 查过),
  所以屏是拿 windowId 去 `windowsOnAllDisplays()` 里**反查**出来的, 结果缓存一秒 (读窗口表要过 binder,
  而事件一秒能来十几条)。查不到就是 **-1**, 不猜 0 —— 0 是别人手里那台手机
- **`event.eventTime` 是 uptime 那一套**, 而别处用的是墙钟: 两者相减永远是负数, 于是每一行都印 `+0ms`。
  所以偏移一律拿 `SystemClock.uptimeMillis()` 当基准, 而且基准取**念出来的第一条**而不是"这次调用开始的
  时刻" —— 订阅与 wait 之间发生的事也要被念出来, 那些在调用之前
- **等是轮询队列 (150ms 一次), 不是等服务回调**: 队列读的是内存, 而 `notificationTimeout` 已经把事件
  压到十条每秒, 让服务反向通知反而要引入一套线程协议。**游标在答完之后才前移**, 而且念不下的那些
  (超过 `limit`) 也一起前移 —— 否则下一次 wait 会把它们再念一遍
- **`Heard` 声明在文件顶层**: companion 里嵌套的类, 外面要写成 `LwAccessibility.Companion.Heard` 才引用
  得到, 而这个文件的惯例 (与 `UiNode` / `UiTree` / `UiTap` 一样) 就是顶层放数据类
- 一次性的 wait (不带 `id`) 会建一个临时订阅, 答完就丢; 订阅自己也会过期 (`lifeMs`, 默认十分钟), 所以
  忘了它的订阅不会留下永不消失的状态

## 端侧 OCR

方案在 `docs/step8-plan.md`, 实测数字在 `docs/step8-record.md`:

- **fp16 是前提, 而且模型一个算子都不用改**: fp32 图上 HTP 连 `HardSigmoid` / `Clip` 都造不出来, 而 `--float_bitwidth 16` 之后**原样就过**, 所以 `tools/ocr/rewrite_onnx.py` 只做"钉死 shape"这一件事; 代价是 QNN 那份 I/O 是 **fp16**
- **NPU 走 ORT 的 QNN EP 在设备上 JIT 编译** (`enable_htp_fp16_precision=1`)。开发机上预编译的 context binary **能加载能执行不报错但输出是一张常数图**, 别走那条; 代价是 `libQnnHtpPrepare.so` (70 MB) 必须随 APK 发, 首次建 session 2.2 s
- **性能票别关**: `htp_performance_mode="burst"` 与不投票差 **2.5 倍**; 它**跟着 session 走**, 重建 session 就回得去
- **rec 的每次调用有 5-8 ms 固定开销**, 所以 25 行要 250-400 ms —— 该做的是**批量重导** (batch 8/16) 而不是量化 (int8 治不了固定开销); det 那边 48 → 11 ms, 上 NPU 是值的
- **工具**: `lw_ocr(displayId)` 回每行 `[框] "文字" score 中心`; `lw_tap(text=…, source: auto|a11y|ocr)` 的 `auto` 先问无障碍树, **树里没有这个名字**才退到读屏 (树说 `ambiguous` 时不退, 读像素只会是第三个猜测); 读屏按名字点时的落点是**框中间 60% 里随机取的**, 不是正中心 (每次都落同一个像素本身就是模式)。**别再用"Flutter 需要 OCR"当理由**: 实测 Flutter 一直向无障碍暴露语义树
- **熄屏是静默失败, 这条最阴**: 熄屏时 `screencap` 交的还是**最后一帧** (读屏读到一个已经不在的界面), 而注入的触摸**唤不醒屏** (点进空气里), 两个都不报错。所以 `ocr` / `tap` 的答案里带了 `screen: on|dozing|off`, 工具那侧看到不是 `on` 就直说, 读屏点击则直接不点

设置页那段「OCR」显示计算单元 (`NPU (HTP) · SM8550`) / 说明 / 上次耗时 + 一个**自检**按钮 (加载并预热)

## 权限

**清单里声明了一大批** (1.0.2 那次全量加上的, 60 条): 屏幕上那套工具用的普通权限, 加上相机 / 定位 / 录音 / 蓝牙 / 媒体读取 / 通讯录 / 短信 / 通话记录 / 日历, 以及 `WRITE_SETTINGS` / `PACKAGE_USAGE_STATS` / `SYSTEM_ALERT_WINDOW` / `REQUEST_INSTALL_PACKAGES` 这类特殊访问

**声明不等于拿到**, 三个层次的差别要记住:

- **普通权限**装完就有 (振动、网络状态、蓝牙的旧式那几条)
- **运行时权限**要弹窗点, 应用里的入口是设置页「权限」段 (`PermissionRequests` → `MainActivity` 里的 launcher), **电脑上也可以用 `tools/lw-install.ps1 -Perms` 一次给掉** (`pm grant`)
- **特殊访问**系统不接受运行时申请, 只能去一页系统设置里点; `-Perms` 用 `appops set` 代劳 (`WRITE_SETTINGS` / `GET_USAGE_STATS` / `SYSTEM_ALERT_WINDOW` / `REQUEST_INSTALL_PACKAGES`), 电池优化用 `dumpsid deviceidle whitelist`

**声明但这一版没有功能用的**: 通讯录 / 短信 / 通话记录那一批 (日历 2026-10-08 起从这一档挪走了: `lw_calendar` 真的用它, 所以它是 `runtime` 里的一条独立能力)。写进清单是刻意的 ("都加上"), 但它们没有任何工具去读, 所以 `PermissionCatalog.declaredOnly` 把它们单列出来 —— 页面上不该让人以为它们在用

`util/PermissionCatalog` 是唯一的一份表: 设置页照着它列, `lw_permissions` 照着它报, 所以不会出现"设置页说已允许而工具说不支持"

## 破坏性操作 (2026-10-08 起改口径)

`forceStop` / `clearData` / `uninstall` / `install` / `disable` 这五条**调用即执行**: 应用侧那道
`OverlayDialog` 与它的 100 秒等待**已经撤掉** (`ui/DestructiveConfirm.kt` 删除, `LwSystemCommand`
里不再有等待那一段)。`disable` 仍算这一档 —— 它比停应用更粘, 被停用的应用从桌面上消失

**唯一那道闸在提示词层**: 模型必须先问用户一次 (含糊时要点名包名与后果), 得到明确的"是"才调。
这条规矩落在三处, 改口径时三处一起改:

- `host-plugin/index.mjs` 里 `lw_app_control` 的工具描述
- `skills/android-device-control/SKILL.md` 六·3 那条红线
- `assets/modes/phone.md` 15 (识屏那一份在 2.5.0 批次 4 删了; `presets/mobile-use` 那份随预设本身在 2.7.8 批次 7 之后下线)

要记住的两件事实:

- **这一层拦不住模型**: 工具描述与提示词都是"请求", 一次越权的调用没有任何东西会挡住它。做危险动作前
  要留痕 (至少让回执说清做了什么) 的意义因此更大
- **`enable` / `setHome` 不在这一档** (它们本来也是一调就做); 反过来, "审批全放行"那一条与这件事无关
  —— 它管的是 dsh 自己的审批流, 从来不是这道闸

## 审批 (全部放行)

**我们注册一个 auto-allow answerer, 屏幕操作直接 `allowed-once`**, 就这么一行:

```js
ctx.on('approval/request', (_request, _next) => Promise.resolve('allowed-once'), { prepend: true })
```

理由: 要控制主屏时 Luwi 自己在后台, 用户看不到也点不了审批框, 走 `ask` 的结果不是"更安全", 而是**每次点击都卡在那直到超时/取消**。**`prepend: true` 是必须的**: 覆盖层是最后挂上去的, 不加就是链尾, 而浏览器侧那个 `ui-approval` 面板只要有人开着网页端就会先答 —— 于是"全放行"静默变成"每次都问人"。**审批面板就在 app 内的 WebView 里**, 所以 `prepend` 等于放弃了"人在的时候问人"这条路 (要恢复就把 `prepend: true` 去掉)

要记住的两个:

- **`ApprovalOutcome` 封闭且 fail-closed**: 只有 `allowed-once / rejected / cancelled / unavailable`, answerer 缺失 / 不拥有该请求 / 抛异常 / 返回不合规统统变 `unavailable`, 而消费者**只要不是 `allowed-once` 就拒绝**。所以 **`danger-full-access` 这个预设名有歧义**, 它是"没人回答 → 全拒绝"而**不是全放行**。而策略为 `never` 时服务在派发之前就 `return 'rejected'`, 连 prepend 的 answerer 都不会被叫到 —— 这一行的定位是**保险**: 一旦会话换成带 `ask` 的预设, 它才是"问了没人答 → 拒绝"与"直接放行"之间的那一步
- **放行了就必须有别的刹车**: **审计照记** (`approval/asked` / `decided` 是 log-only, 不进模型 transcript, 但进会话日志, 别为了"反正都放行"就绕过 `ctx.approval`, 那样连审计都没了); **主屏操作尤其危险**, 因为虚拟屏内的操作至少被那个 display 关着。**2026-10-08 起触摸刹车也删了** (见「主屏 (displayId 0)」那一节): 应用侧现在**没有任何**拦住主屏操作的东西, 只剩提示词层的规矩

## dsh 工具怎么加

**先写 dsh 原生工具, 不要先写 MCP**: dsh 仓库里只有 `packages/mcp/mcp-client` —— 它是 MCP **客户端**, 自己写 server 等于新造一套协议再设法接进来; 而原生工具直接就有审批 / UI 呈现 / PTC 模式

**加载路径是一条 `--patch` 覆盖层** (2026-10-16 起生图那一行改走 profile patch, 见下面「生图」那一段的第六条): 插件源在 `host-plugin/index.mjs` (纯 ESM, 用 `@deepseek-ai/dsh-tools` 的 `defineTool`), 由 `tools/pack-host.mjs` 装进 host 树的 `node_modules/luwi-channel`, app 每次启动 host 时写一份 overlay 指过去 (`host/PluginOverlay.kt`); **而"要能被 dsh 设置面板改"的那几行由 `host/PluginRows.kt` 写进 profile 自己的 `cordis.patch.yml`** —— 覆盖层是最上面那一层, 面板的写入命中不了它

一个插件就是一个 ESM 模块, 导出 `name` / `inject` / `apply`:

```js
export const name = 'luwi-channel'
export const inject = ['tools']            // 等 tools 服务就绪再 apply
export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'lw_probe',
    description: '...',                    // 模型看到的描述
    parameters: {},                        // 空对象 = 无参数
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() { return '...' },
  }))
}
```

**开发期的坑**: pnpm 的 `node_modules` 是隔离的, `@deepseek-ai/dsh-tools` 只 link 在 `apps/cli/node_modules` 下, 所以**临时插件文件要放在 `apps/cli/` 里面** (放仓库外或仓库根都解析不到), 用完删掉

**本机验证 (不需要真机)**, 用 fork 的开发克隆:

```sh
cd B:\Git\deepseek-harness
node --import tsx/esm apps/cli/src/bin.ts --profile web --dump-config --patch <overlay.yml>
node --import tsx/esm apps/cli/src/bin.ts --profile headless --patch <overlay.yml> "Call the lw_probe tool and report exactly what it returns."
```

**可以参考的文档 (都有中文版)**: `docs/cookbook/extension-cookbook.zh.md`, `docs/cookbook/adding-a-tool.zh.md`, `docs/user/develop/basic/tool.zh.md`, `docs/cordis-primer.zh.md`

## 技能与快捷指令 (2026-10-08, 2.5.0 批次 7)

两样都是"给模型看的工作流", 落点不同:

| | 技能 | 快捷指令 |
| :-- | :-- | :-- |
| 是什么 | dsh 自己的机制: 一份 `SKILL.md`, 模型按需加载 | 本仓自己的: 一个 md 文件 = 一条工作流 |
| 住哪 | `$DSH_HOME/skills/<name>/SKILL.md` | `$DSH_HOME/quick-commands/<名字>.md` |
| 源在哪 | 仓库 `skills/` (只有随包那几份进 APK) | 仓库 `quick-commands/` |
| 谁读 | 模型 (frontmatter 的 `name` / `description`) | 模型 (`lw_quick op=read`) 与人 (设置页列表) |
| 谁写 | 人 | 模型 (`lw_quick op=write`) + 人在设置页删 |

要记住的:

- **随包那几份是构建拷进去的**: `app/build.gradle.kts` 的 `copySeedAssets` 把 `shippedSkills` 与
  `shippedQuickCommands` 那几张表拷进 `assets/`, 首启由 `host/LwSeed.kt` 落进 `$DSH_HOME`。
  **显式列名字而不是整目录拷**: `skills/android-device-control` 这一版不随包 (它只在仓库里, 装机时
  要另外推), 要收口就把它加进那张表
- **技能"缺什么补什么"、样例快捷指令"只发一次"**: 技能与 `modes/*.md` 同一条规矩 (主人改过的不会被
  覆盖); 样例靠 `$DSH_HOME/.lw-seed` 那个记号, 主人删掉就不再回来 —— 这两条差别是刻意的
- **改完技能要重新装机才有新的一份**: 设备上那份只在缺的时候才写 (与 `modes/phone.md` 同一个已知
  行为), 迭代时要么删掉设备上那一份, 要么直接 push 进去
- **`lw_quick` 的 op 名单两份实现**: `tool/LwQuick.kt` 的 `OPERATIONS` / `MODEL_OPERATIONS` 与
  `host-plugin` 里那条 `enum`, 漂开由 `tools/check-quick-commands.mjs` 拦; **删除只在桥上**
  (`op=delete`), 给模型的只有 list / read / write
- **点一下 = 投一句话, 不是在这里跑**: 设置页那条路往 `voice/inbox.jsonl` 写一行 (来源 `quick`),
  正文就是规范句「用快捷指令: <名字>」, 于是"当前会话没有就新建 / 10 分钟内接同一场 / 正在跑就
  steer"三条语义自动成立。**那条规范句在两种语言里都是中文** (它是要给模型读的, 不是给人读的界面文案)
- **日程那条工具**: `lw_calendar` 六条 op (`list` / `events` / `read` / `create` / `update` / `free`),
  走 app 进程的 `ContentResolver`; 写入落在第一本可写日历 (可用 `calendarId` 指名), **没有可写的就拒**

## 自动指令 (2026-10-08, 2.5.0 批次 8; 2.7.8 批次 8 加了「只响一次」, 批次 9 加了「且」「或」与两个读数源)

"到什么时候做什么"那一条: 一份 JSON 一条规则, 九个监测器看条件, 命中之后把一句话投进会话。
施工单在 `docs/DSH-LW-2.5.0-批次8-开发计划.md`; 要记住的:

| 文件 | 是什么 | 谁写 |
| :-- | :-- | :-- |
| `$DSH_HOME/automations/<名字>.json` | 一条规则 (`name` / `enabled` / `when` / `then` / 冷却 / 上限 / 静默) | 模型 (`lw_automation op=write`) 与设置页那个开关 |
| `$DSH_HOME/automations/settings.json` | 九个监测器开关 + 频率档 + 静默时段 + 「允许它自己动手」+ `saidLimit` | 设置页 |
| `$DSH_HOME/automations/history.jsonl` | 一行一次判定与原因 (冷却 / 上限 / 主动说那本账都是从它现算的; 手动跑的那行带 `manual`) | 引擎 |
| `$DSH_HOME/automations/places.json` | 地名 → 经纬度 (geocoding 的结果) | 引擎 |

- **九个监测器: 通知 / 前台应用 / 光感 / 时间 / 地点 / 天气 / 解锁 / 动手 / 只响一次**. 它们的低功耗口径是这一批的硬约束:
  **没有启用的规则就不注册**; 通知与前台应用是事件驱动 (后者只认无障碍事件, **不退回 UsageStats
  轮询**); 光感一条 `SENSOR_DELAY_NORMAL` 的监听; 解锁是一条动态注册的 `ACTION_USER_PRESENT`
  接收器, 「动手」挂在既有窗口事件链上 (解锁也记一笔); 时间**只排一个**闹钟 (精确优先, 拿不到就退
  `setAndAllowWhileIdle` 并把"不精确"写进读数); 轮询只剩天气 (默认 60 分钟) 与地点
  (`NETWORK_PROVIDER`，默认 10 分钟 · 500 米)
- **「且」「或」与新鲜窗口** (批次 9, D21/D22): `{"kind":"all","conds":[…]}` / `{"kind":"any","conds":[…]}`,
  `conds` 收 2~5 个**八个叶子条件之一** (notice / foreground / light / time / place / weather / unlock /
  activity), **不许嵌套** (校验那一层当场拒, 说明里也写着)。判据不是"同时为真"而是"各自最近一次为真
  落在窗口内": 每个子条件记 `lastTrueAt`, 缺省 time 1800 s / 瞬态 120 s / 状态 300 s, 子条件可写
  `within` 覆盖。两个判定时机 —— 相关监测器送事件时重算一次, 外加 **60 秒 tick** (只在有组合规则时挂,
  熄屏不跑, 只拿缓存重算状态类)。命中之后把这条规则所有子条件的新鲜标记清一次 (**同一次成立只响一声**);
  四道闸 (静默 / 冷却 / 每日上限 / 允许动手) 一个字节没动, 仍在 `act()` 里。**老形状一个字节不动**:
  `{"kind":"time","at":"23:45"}` 照旧
- **省电联动**: `LwWakeWord.powerSave` 生效时只停轮询那两条, 事件类照常; 恢复时不补跑错过的轮询
- **动作层是"投一句话"**: 命中之后写 `voice/inbox.jsonl`, 来源 `automation`。宿主对那一个来源有
  两处特别处理 —— **不进命令表匹配**, 且**每次触发新开一场会话** (不接浮标那 10 分钟的一场);
  设置页的「新建 / 改一改」走另一个来源 `automation-setup`, 它与快捷指令一样落在主人当前那一场
- **提醒的措辞不在规则里**: 规则只写"要做什么", 那句给主人看的话由模型在触发时现写 (它开的是一场
  新会话, 回答照旧被朗读念出来); `task` 那一种会真的动手机, 所以它多一道全局总闸
  「允许它自己动手」(默认关)
- **防骚扰**: 全局静默时段 (默认 23:00-07:00, 解析复用 `PowerWindow`) + 每条规则冷却 30 分钟 +
  每天 5 次; 每一次判定都落 `history.jsonl`, "它为什么没响"只有那一份答得出来
- **`lw_automation` 的 op 名单两份实现**: `tool/LwAutomation.kt` 的 `OPERATIONS` /
  `MODEL_OPERATIONS` 与 `host-plugin` 里那条 `enum`, 漂开由 `tools/check-automations.mjs` 拦;
  **删除只在桥上** (`op=delete`), 给模型的是 list / read / write / status / history
- **工具数**: 这一批把插件从 58 个工具抬到 59 个, `tools/check-host-plugin.mjs` 的 `FLOOR` 跟着改

## 探索 (2026-10-18, 2.7.8 批次 8: 定时 + 会演进的任务卡)

"让她自己定期看一眼这台手机"那一条: **只有一张会演进的任务卡, 每天一个时刻自己开始, 没有任何手动入口**。
主人 2026-10-17 的口径: "探索应该是定时的由用户决定开始时间，不要手动开始探索" + "先做一个初始卡，
并且这个卡会不断变化，agent 由已知记忆、上次探索来决定下一次探索的任务，用户可以手动修改"。

| 文件 / 工具 | 是什么 | 谁写 |
| :-- | :-- | :-- |
| `$DSH_HOME/lw/explore-card.md` | 下一场探索的任务 (一段 Markdown) | 宿主收尾时按 `nextTask` 覆盖 (`tool/ExploreTask` 只读/手改写) |
| `$DSH_HOME/lw/explore-schedule.json` | `enabled` / `at` (`HH:mm`) / `last` (最近一趟的结果) | 设置页「探索」段 (`tool/ExploreSchedule`) |
| `$DSH_HOME/lw/explore.json` | 那一场探索的 run 账 (批次 7) | 宿主; 应用只读 (`ExploreTask.running`) |
| `lw_explore` | `op` 只有 **status / finish** (手动 `start` 已撤) | 宿主工具 |

- **没有手动开始**: 设置页「探索」段只有 开关 / 每天几点 / 那张任务卡 (可手改) / 每天最多主动说几次 +
  两行读数。到点由应用一个 `AlarmManager` 闹钟 (`ExploreAlarmReceiver`, requestCode 42) 读卡写一行
  `voice/inbox.jsonl` (`source:"explore"`), 宿主照批次 7 的 `startExplore` 开一场; **宿主没在跑 /
  省电 / 上一场还在跑 / 卡是空的** 四种情况都记在 `explore-schedule.json` 的 `last` 里 (卡空时按
  "自由探索这台手机"投)
- **卡会演进**: 收尾时 `lw_explore op=finish` 多一个 `nextTask`, 宿主把它原子写进 `explore-card.md`
  (上限 8000 字); 初始卡是随包那份"看手机环境 + 应用 + 用户画像"的任务, 首启抄一次 (记号
  `.lw-seed-explore`), 之后由 agent 与主人改
- **主动说话的上限**: 「每天最多主动说几次」(`saidLimit`, 1~10, 缺省 3) 只数 `then.kind="task"` 的
  投递, 账从 `automations/history.jsonl` 现算 (手动「立刻跑一次」不算), 到顶就 `blocked:"said"`;
  静默时段照旧"跳过、不补发"
- **「只响一次」**: `when.kind="once"` + `at` (带时区的 ISO 8601), 与 `time` 共用闹钟; 时刻一过
  (投出去 / 被拦 / 错过窗口) 就 `enabled:false` 并记一行 —— 收尾时那条主动对话就是它
- 判据: `tools/check-explore.mjs` 106 条 (含初始卡四块 / 排期 / 卡原子写 / 界面无手动入口 /
  `nextTask` 两处); `check-automations` 30 条 (含 once 与 said 上限)

## p 图 (AI 改图, 2026-10-08, 2.5.0 批次 9)

主人说一句「帮我把这张图的背景改为海边」, 本机要自己把它做完: **截当前那张图 → 上游图生图 → 成图进相册
→ 打开它**。这一条由三样东西拼起来, 各管一段:

| 谁 | 做什么 |
| :-- | :-- |
| `@dickpy/dsh-imagegen` 插件 (第三方, **随包**) | 改图那一步: `edit_image` 走主人在「设置 → 生图配置」里配的渠道, 另有 `generate_image` (文生图) 与一整套 GUI (画廊 / 无限画布 / 提示词库 / 电商套图) |
| `skills/photo-edit/SKILL.md` (随包技能) | 那四步的工作流 (认图 → 改图 → 进相册 → 回报) 与红线 |
| `lw_image` (本仓第 60 个工具) | 两头: `op=ref` 把一张图变成 `edit_image` 认的引用, `op=album` 把成图放进相册并打开 |

- **为什么中间那一步必须由本仓做**: `edit_image` 只认一个引用对象 (`attachment_id` / `media_type` /
  `bytes` / `width` / `height`), 而**屏幕上那张图没有这样一个对象** —— 模型看到的是图块, 抄不到 id。
  `lw_image op=ref` 拍一块屏 (缺省 `displayId 0`, 就是主人手里那块) 交给附件库, 再把库给的那份印成
  snake_case 的 JSON; 会话里已经有图时 (`from:"conversation"`) 直接把那条图块的引用原样交回去。
  **拍的是 `fullPath` 那一份** (没按"给模型看的预算"缩过的): 它的去处是上游接口, 不经过模型上下文
- **成图那条路反着走**: 插件把结果存成附件并把引用印进工具结果的 JSON (所以模型拿得到 id), 而字节只有
  宿主这一侧读得出来 —— `lw_image op=album` 把它读回来写进工作区 `pictures/`, 再让应用那一侧经
  `gallery` 这条桥拷进媒体库
- **进相册走 MediaStore, 不往 `Pictures/` 里扔文件**: 从 Android 10 起一个应用可以往媒体库插自己的
  图片而**不需要任何权限**, 而直接写公共目录要"所有文件访问权限" (本机是可选的第三档)。`tool/LwGallery.kt`
  用 `IS_PENDING` 分两半写 (先占行再写字节, 并在写失败时撤掉那一行), 写完**读回**名字与路径, 再
  `ACTION_VIEW` 打开它 —— 后台启动 activity 可能被系统丢掉, 所以答案说的是"请系统打开它", 不承诺
  主人已经在看
- **插件是随包发的, 不是机内装的**: `tools/pack-host.mjs` 用 `npm pack` 把它取进 host 树的
  `node_modules/@dickpy/dsh-imagegen` (只取包本身, **不装它声明的依赖** —— `@deepseek-ai/schemastery`
  这棵树里本来就有, `lucide-react` 只给打好的客户端用)。**裁掉 `docs/` (58 MB 的演示视频与截图) /
  `src/` 里除 `src/templates/` 之外的部分 (`lib/` 才是会跑的, 而 `src/templates/*.json` 是它内置提示词
  库的离线快照, 运行时真读) / `lib/*.map`**, 剩约 7 MB。registry 走 `LW_NPM_REGISTRY`, 缺省
  `registry.npmmirror.com` (这台开发机上 `registry.npmjs.org` 不通)。装完当场 import 一次: 少一个依赖
  时 npm 不会说话, 而它要到加载时才抛
- **装了不等于挂了**: `host/PluginRows.kt` 那一行才是把它挂进 web profile 的东西 (`id imagegen`, 指向
  包里的 `lib/index.js`); 它客户端那一半 (`lib/client.js`) 由 dsh 的 client-modules 按 `dsh.client` 扫
  出来, 与 `dsh-web-mobile` 同一条路。那一行**写在 profile 自己的 patch 里而不是 `--patch` 覆盖层里**,
  而且 `name:` 要写成 `file://` 形态 (**两条都是"面板能不能存进去"的条件**, 见下面那条); 树里没有这个
  文件时那一行会被摘掉, 所以更早打出来的树照旧起得来
- **它挂不上也不会把 host 弄挂**: dsh 那份"必须起来"的名单 (`requiredStartupEntryIds`) 只有
  `agent-loop` / `webserver` / `modules` / `connection` / `headless-runner` / `acp` /
  `sdk-jsonrpc-server` 七个, 我们这一行是 best-effort —— 它失败时打一行
  `warning: 1 entry did not activate` 而 host 照旧起来, 代价只是生图那一块 (工具 + 面板) 一起不在
- **给它补一处上游的小毛病**: 提示词模板快照 / 模板收藏 / "打开数据文件夹"那三处的目录写死成
  `~/.dsh/dsh-imagegen` (没看 `$DSH_HOME`), 而这一棵树上 `HOME` 指着**工作区**, 于是它会在
  `/sdcard/DSH` 下建一个 `.dsh` 并把两兆的模板 JSON 写进去 (2026-10-08 在模拟器上实测到)。历史与画廊
  那两处是对的 (它们走 `imageDataRoot()`), 所以只是那三处。`tools/pack-host.mjs` 在装完之后按它自己
  `image-storage-path.ts` 那句换掉, **并且数一数换了几处: 不是 3 处就让构建失败** —— 上游换个写法时
  悄悄不换的后果, 是主人的工作区里又多一个没人会去查的 `.dsh`
- 判据表在 `tools/check-image-edit.mjs` (20 条): 那五个字段的名字 / 两个 op 两侧一致 / 相册目录只有一份
  口径 / 三个清单 (pack-host 的包名、`PluginRows` 的行、技能的随包表) 对得上 / 那三处 `~/.dsh` 的换法还在
- **第二个随包插件是记忆 `@modusensus/dsh-mneme`** (2.7.8 批次 4, 主人 2026-10-17: "记忆插件要像生图插件
  那样进包"): 与生图同一个形状 —— `tools/pack-host.mjs` 用 `npm pack @modusensus/dsh-mneme@0.9.0` 取包自己,
  **裁 `docs/` / `test/` / `src/` 与 `scripts/` 里除 `mneme-runtime.mjs` 之外的全部** (那一个被 `lib/loader.js`
  的提示行点名), 剩约 2 MB。它声明的那一个运行时依赖 `morphicons` **其实没有被 lib 里任何一处 import**
  (图标数据随客户端那份 bundle 发), 所以 `npm pack` 就够 —— 装完那次 import 是这条判断的判据。
  挂的那一行是 `PluginRows` 的 `id mneme`, 指 `node_modules/@modusensus/dsh-mneme/lib/index.js`, **同在
  profile patch 那一层** (理由与生图一字不差: 它的模型、语言、数据目录都是主人要在面板里改的)。
  数据落点有两处要钉: **记忆库**走那一行 `config:` 里的 `memoryDir`, 由 `PluginRows.memoryConfig` 按本机
  filesDir 代写成 `$DSH_HOME/memory` 的绝对路径 (缺省是 `~/.dsh/memory`, 而 `HOME` 指着工作区, 不钉住
  就会在主人看得见的地方长一个 `.dsh/memory`); **自管运行时**那一档 (本地向量化) 走 `DshHost` 给出的
  `DSH_MNEME_RUNTIME_DIR`。它只用 `node:sqlite` (`lib/store.js` 的 `DatabaseSync`), 所以随包的 node 必须带
  那个内建模块 —— 这一条源码里核不出来, 2026-10-17 在真机那份 node (v26.4.0) 上量过。判据在
  `tools/check-bundled-plugins.mjs` (10 条: 两个包的声明 / 裁法 / 挂的层 / `file://` 形态 / 两处数据目录)。
  **老安装里已经手工装过 mneme 且那一行已在时不动它** ([PluginRows] 的幂等): 那种机器上记忆库仍落
  `~/.dsh/memory`, 要挪进 `$DSH_HOME` 就在面板里把 `memoryDir` 改过去
- **右栏那颗「浏览器」格子在上游是 desktop 专属的, 我们按 id 把它打开** (2.7.8 批次 4 量到的根因):
  `@deepseek-ai/dsh-web-app/cordis.patch.yml` 里那一行是
  `disabled: !!js "ctx.get('profileContext')?.name !== 'desktop'"`, 而 Luwi 起的是 **`web`** profile ——
  于是它**根本没进 Loader**, 页面里连它的客户端模块都没有 (`__DSH_BOOT__.entries` 里没有
  `sidebar-browser`), 右栏 Start 面板上也不出「浏览器」那张卡。**这不是 CSS 的问题, 是那一格压根挂不上**;
  `PluginOverlay.write` 每启一次 host 写一条同 id 同 name 的 `disabled: false` 覆盖 (patch 层序
  `bundle → profile → $DSH_HOME → 命令行覆盖层`, 我们这层最后, 所以压得住), 树里没有那个文件时不写
  (指向不存在的文件会让 Loader 报一条载入失败)。它宿主那一半是空的 (`apply(){}`), 存在的唯一目的是让
  client-modules 扫到它 —— 这个包只提供客户端那一半
- **Luwi 自己的 CSS 覆盖层是 `presets/ui-fix`** (2.7.8 批次 4, D8 = b; 量到的现场与每条补丁的
  "什么时候可以删"都写在 `presets/ui-fix/lib/client.js` 的注释里): 一个**双面**包 —— 宿主那一半
  (`index.mjs`) 是空的, 客户端那一半 (`lib/client.js`) 只做一件事: `ctx.effect` 里往 `document.head`
  插一段 `<style>`, 交回一个摘掉它的函数。**只写选择器与样式, 不复制组件、不 monkey-patch JS** ——
  这是"随时能整块撤掉"的唯一前提。`pack-host` 把整个目录拷进 `node_modules/luwi-ui-fix`, 由
  `PluginOverlay` **两张表**各挂一行 (语音 bundle 开/关各一张), **不在 `PluginRows`**: 它没有配置要在
  面板上改。选择器优先挑上游自己命名的东西 (`span[data-files-path]` / `.mneme-*` 这类), 前端那套
  `_path_1kiio_3` 哈希类名只在没别的抓手时才用 —— 哈希一变补丁静默失效, 所以
  `tools/check-ui-fix.mjs` 把这里出现的**每一个选择器**去已构建的产物里 grep 一遍 (18 条), 一个都
  命中不了就红, 上游一改 DOM 我们当场知道。每条覆盖都带 `!important`: 这一段 `<style>` 与上游那几个
  客户端包同优先级, 谁先插进来不由我们决定
- **验过的 (2026-10-08, 模拟器 `emulator-5554`)**: 出包装机后 host 起来 (`dsh web: http://…`)、
  GUI 渲染 (`rootChildren: 1`)、**两半都挂上了** (宿主那一半把模板快照写进了
  `$DSH_HOME/dsh-imagegen/templates/`; 客户端那一半的名字出现在首页那份模块表里:
  `plugins/??…@dickpy/dsh-imagegen/client.js…`)、没有任何 `did not activate`。应用那一侧单独验过:
  一条 `gallery` 桥调用把 `/sdcard/DSH/screenshots/screen-0.png` 放进相册 —— 文件落在
  `/sdcard/Pictures/Luwi/`, 媒体库读出 `_display_name=lw-gallery-test.png` 带
  `relative_path=Pictures/Luwi/`, 而且**真的弹起来了** (`Displayed
  com.google.android.apps.photos/.pager.HostPhotoPagerActivity`); 大预算的 `screenshot` 回的
  `fullPath` 是 1080x2400、`scale 1.0` 那一份 (没走"给模型看的预算"), 正是 `op=ref` 要的
- **没验的**: 真机上从"说一句话"到"相册里多一张图"的**整链** (要一个配好渠道与密钥的 image API);
  以及客户端那一半在 dsh 0.2.1 上的界面表现 (它是按 0.1.2-alpha.2 的 devDependencies 编的, 只验到
  "在模块表里、首页能起来")
- **这一批随 2.5.0 一起发了** (2026-10-08): `versionCode 7 / versionName "2.5.0"`, 干净构建
  225,777,526 字节 (215.3 MiB), `tools/apk-bytes.py` 量出最大无归属区间 4,098 字节; 发在
  `yuloong07-star/Luwi` 的 `v2.5.0` (Release 正文在 `docs/DSH-LW-2.5.0-release-notes.md`)
- **挂插件要往两张表里各加一行** (2026-10-08 在真机上踩的): `PluginOverlay` 里 `PLUGINS` 与
  `OWN_PLUGINS` 是并列的两张表 —— 官方语音 bundle 关着走前者, 开着走后者, 两张都要有那一条
  (`tools/check-image-edit.mjs` 数着)
- **能被 dsh 设置面板改的那几行不许挂在 `--patch` 覆盖层上, 而且盘上的 `name:` 必须写成 `file://` 形态**
  (2026-10-16 主人报的"生图保存失败, 点保存必失败"; 两件事各自都能单独把面板压死, 只修第一件时仍然是
  同一句错 —— 事后在真机上拿设备自己那棵树里的 dsh 代码复现出来的):
  - **层**: patch 层序是 `bundle → profile 自己的 cordis.patch.yml → $DSH_HOME/cordis.patch.yml →
    命令行 --patch`, 而 `applyEntryPatches` 只让**后面的** patch 命中**前面 insert 出来的行** —— 面板的
    写入 (config-editor 追加在 profile patch 末尾) 永远命中不了覆盖层那一行
  - **名字形态**: dsh 读 patch 时会把 insert 子行里裸绝对路径的 `name` 锚成 `file://` URL
    (`anchorInsertedPluginNames`), 于是 Loader 里那一行的 `options.name` 是 `file:///data/...`; 而面板存
    配置时 config-editor 追加上去的 config 行抄的是**锚过之后**那个名字, 它落盘前的自检用的却是裸
    `yaml.load` (不 anchoring) —— 名字对不上, 那一行被按 `patch: name mismatch for "imagegen"` 丢掉,
    自检看到"生效值还是继承来的那一份", 抛出 `Configuration for "imagegen" is overridden by a home patch
    or command-line overlay` (**没有覆盖层也报"被覆盖层覆盖"**, 就是这么来的)。所以盘上直接写
    `file:///data/...` (`PluginRows.fileUrlOf`): 那不是新造一个形态, 它逐字就是 anchoring 之后喂给 Loader
    的那一份
  - 归 `host/PluginRows.kt`: 它每启一次 host 对一次账 (树里有包就补一行到 profile patch 的**开头**, 已有
    的那一行是裸路径就只把 `name:` 那一行改成 `file://` 形态 (旧安装靠这一步自愈), 树里没有就摘掉,
    主人写给它的 `config:` 一个字节都不动), 面板追加的 config 行排在同一个文件里它后面 —— 顺序天然对得上
  - 判据: `tools/check-overlay-yaml.mjs` (两份 YAML 都要能解析 + 那一行必须是 `file://` 形态) /
    `tools/check-image-edit.mjs` (那一行不在覆盖层两张表里 + `fileUrlOf`/`namedRow` 还在) /
    `app/src/test/.../PluginRowsTest.kt` (补 / 自愈 / 摘 / 保住 config 行四档)

## LW 插件 (2026-10-08, 批次 9 的 P0 / P1 → 2.5.1)

让第三方 (包括只做美术的人) **不重编 Luwi** 就给这台手机加能力那一层。协议正文在
`D:\apk\docs\LW-软件插件协议.md` (冻结版; 决策记录是同一目录的 `-讨论稿`), 施工单在
`D:\apk\docs\DSH-LW-2.5.0-批次9-开发计划.md`

**三层的分工要一直清楚**: 技能只给提示词, dsh 插件只能碰 host 那一侧, 而这一层是**设备侧能力** ——
新工具、新监测器、新的浮标外观、新的面板、新的桌面组件

| 落在哪 | 是什么 |
| :-- | :-- |
| `$DSH_HOME/plugins/<id>/<version>/` | 一个版本一份包 (`plugin.json` + 它带的文件) |
| `$DSH_HOME/plugins/<id>/current` | 内容为版本号的**文本文件** (不是 symlink) |
| `$DSH_HOME/plugins/<id>/.data/` | 插件自己那块可写的 (设置与 `store`) |
| `$DSH_HOME/plugins/.state.json` | 启用状态 + 逐能力授权 + `revision` |
| `$DSH_HOME/plugins/.publishers.json` | 见过的发布者公钥 (第一次见到界面单独提示) |
| `$DSH_HOME/plugins/.audit/<id>.log` | 能力调用与被拒的账 (512 KiB 滚, 留尾部 500 行) |

要记住的:

- **`kind` 现在收三种: `companion` / `art` / `inline`** (2026-10-11 方案 v2 起; 当时这一版只收
  `companion`, 那两行是历史)。`script` 还没接通, `theme` 已并入 `art`; **`dex` 是 `inline` 的别名**,
  两个名字进同一个域
- **两侧都是手写 Binder, 不是 AIDL** (协议第 0 节第 11 条): app 模块里出现 `.aidl` 就会生成 Java,
  而 Java 编译会把 AGP 那条坏掉的资源管线拉回构建 —— 同一个理由让 `LwPrivilegedService` 也手写。
  接口只有一处定义 (`:lwplugin-api` 的 `ILwPlugin` / `ILwPluginContext`, 描述符与 transaction code
  都写在那里), app 与伴侣引的是**同一个模块**; 对外那个 `lwplugin-api-1.jar` 就是它 AAR 里的
  `classes.jar`
- **能力表 23 条, 现接通 11 条**: `device.read` / `sensor.read` / `notify.post` / `speech.speak` /
  `clipboard` / `files.own` / `session.post` + `ui.widget` (2026-10-11 加) + `ui.theme` +
  `files.read:<前缀>` / `net.http:<域名>` (2026-10-12 加, G3 能力沙盒: 边界就是声明里那一截 ——
  前缀按 canonical 判、Luwi 私有目录整棵不在范围里; 域名逐字匹配、只走 https、重定向每一跳重查;
  装包时两条参数都过语法闸, 见 `docs/LW-能力沙盒-回执.md`)。
  其余可声明可勾, 调用时回一句点名的"这一版还没接通", 不假装成功。**检查只有一处**
  (`LwPluginContextBinder.call`): 逐次对照声明与授权、核调用方 uid、每一次都进审计 (被拒的也进)
- **`session.post` 就是投一句话** (走 `voice/inbox.jsonl`, `source` 记 `plugin:<id>`), 它是敏感档,
  默认不勾
- **工具名 = `<toolPrefix>_<动作>`**, 与 `lw_*` 同形; 前缀全局唯一, `lw` / `dsh` / `agent` 保留。
  宿主每 3 秒拉一次 `plugin {op:"snapshot"}`, **revision 变了才**注销旧的、登记新的 —— 于是"装上 →
  模型下一轮就看得见"这条链不靠重启 host
- **签名载荷的两份实现必须逐字节相同** (Node 的 `tools/lw-plugin-sign.mjs` 与 Kotlin 的
  `PluginSignature`): 键递归排序 / 紧凑分隔符 / **数字只许是整数** (浮点在装包时就拒) / 最后一段是
  按路径排序的逐文件哈希清单。`files` 还得覆盖包里除 `plugin.json` 之外的**每一个**文件 ——
  覆盖不到的文件等于没被签
- **`describe()` 与包必须是一对**: 启用时拿 id / 版本 / api / 工具名 / 能力五项对照, 不一致就不启用
  (伴侣 APK 与那份包是分开发的两样东西, 只有这一步能把它们绑在一起)
- **未签名的包只走开发者模式** (设置页那一段里的开关, 默认关), 界面上常驻红字; 发布者指纹是
  **规范化的公钥身份**的 sha256 (2026-10-11 改的口径: 曲线 OID + 定长未压缩点, 不是 SPKI 原字节 ——
  见 `plugin/PluginTrust.kt` 与协议第 11 节, 白名单 / `.publishers` / 插件页 / 签名工具四处同一串)
- 样例在 `samples/companion/` (模块 `:sample-companion` 与 `plugin/` 那一份包), 插件的工具数与
  `lw_plugin` 一起把 `check-host-plugin.mjs` 的 `FLOOR` 抬到 61; 这一批的静态校验是
  `tools/check-plugins.mjs` (41 条, 含"仓库里不许有 .aidl"那一条与"详情对话框封了滚动上限"那一条)
- **插件详情对话框的正文自己封了高度并挂了 `verticalScroll`** (屏高六成, 见 `SettingsScreen.PluginDialog`):
  声明的能力多起来 (最多 22 条) 内容会长过一屏, 而 Miuix 的 `OverlayDialog` **自己不滚也没有 `maxHeight`**
  (参数只有 `maxWidth`) —— 不封顶时超出一屏的那一截被窗裁掉, 那两个「卸载」按钮就点不到 (2026-10-08 在
  模拟器上量到的)。**设置页的入口是 app 自己右上角那颗「菜单」按钮** (`contentDescription="菜单"`), 不是
  overlay 那颗球 —— 球的菜单只有「回应用 / 关掉球」两行
- **这一批随 2.6.0 一起发了** (2026-10-09): `versionCode 8 / versionName "2.6.0"`, 干净构建
  (`:app:clean` 之后全量 `assembleDebug`) 225,980,634 字节 (215.5 MiB), sha256
  `659902c6052d7e273c1c1ed353c13f3255466f598c9151cf0ad56a80938117be`; `tools/apk-bytes.py` 量出最大
  无归属区间 4,098 字节 (同一份源码增量构建是 308.2 MB, 差出来的 90 MB 全是没人认领的字节); 发在
  `yuloong07-star/Luwi` 的 `v2.6.0` (Release 正文在工作区 `docs\DSH-LW-2.6.0-release-notes.md`)。
  同一批里还有: **「正在想」那份文件说了算** (`lw/ball-phase.json` + inotify)、**球上手势的防误触档**、
  **输入框幂等** (三击两次不再叠两块)、**双击回复回到"你发话的那一场"**、**语音输入缺省统一到 SenseVoice**
  (GUI 那个录音按钮不再替人下 1.6 GB 的 GLM, 官方 bundle 那个在安卓上永远失败的 provider 被按 id 关掉)、
  以及**朗读那两条修正** (对端主动关闭要当场把 code 与理由带出来, 内置 Edge 音色表去掉已下线的晓辰)。
  这一份原来是按 "2.5.1" 计划的, 收口时主人定名叫 2.6.0
- **装机冒烟 (2026-10-09, emulator-5554, 这一版那份干净构建的包)**: 无障碍 listed+running+healthy,
  通知使用权 listed+running+granted+banners, 悬浮窗已允许, GUI `rootChildren: 1` (htmlLength 930,168),
  系统 TTS 一条 14 字念完 (`spoken: true` "the engine finished") —— 而**这一版最要紧的一条是插件整链**:
  宿主起来时自己报 `plugin tools now hello_ping, hello_battery, hello_ask`, `lw_plugin op=list` 回
  "你好伴侣 1.0.0 [io.github.yuloong07star.luwi.sample.companion] —— 在岗, 前缀 hello_, 授权 1/3",
  调 `hello_ping` 由**伴侣 APK 自己回话** ("我住在 …plugins/io.github.yuloong07star.luwi.sample.companion/1.0.0,
  对面的 LW 是 2.6.0, 拿到的授权有 device.read") —— 也就是 host 插件 → 通道 → binder → 伴侣这一条全通。
  **这一轮没验的**: 特权通道在模拟器上没起来 (Shizuku 13.6 装了但没跑, 模拟器没有 root, 而它的 starter
  要在界面里点一次), 所以 OCR / 虚拟屏 / 输入那几条这一轮没跑; 真机 `OJZD5D6TMNSKFYHM` 这一轮掉线了,
  它上面装的是 10-09 13:14 那一份 (同一份代码, 只是版本号还写着 2.5.0)
- **2.6.5 (2026-10-09 收口): 唤醒那一摊整批** —— `versionCode 9 / versionName "2.6.5"`, 干净构建
  (`:app:clean` 之后全量 `assembleDebug`) 226,243,234 字节 (215.8 MiB), sha256
  `2b89e2c8d870d8b33bad724fedfce564fbe3b6d0af88acaa48bede907e8569e7`; `tools/apk-bytes.py` 最大无归属
  区间 4,098 字节 (与 2.6.0 那份干净包同一个量级); 发在 `yuloong07-star/Luwi` 的 `v2.6.5`
  (Release 正文在工作区 `docs\DSH-LW-2.6.5-release-notes.md`)。内容: **内置 custom 预设随包安装**
  (`presets/custom-mode/` vendored + `host/CustomPresets.kt`, 新机开箱就有「自定义模式」) 与
  **语音开新会话的预设回退** (`chooseVoicePreset`); **唤醒召回** (`wake/WakeTuning.kt` 四个参数
  0.01 / 3.0 / 1 / 16, `wake/WakeDecision.kt` 冷却 1.5 s + 命令尾 2 s, `op=status` 报 `suppressed`);
  **唤醒到开麦的延迟** (命中那串动作里"耳朵先开", 点亮屏幕与解锁挪出采集线程, 每次命中打一行毫秒数);
  **在线引擎念回答时的半双工闸** (三条引擎都合, 判据 `VoiceState.speaking || LwSpeak.speakingNow` ——
  修的是"她自己的回答被录回去又投成一句话"); **球的状态优先级改成「在想 > 在念 > 在听 > 失败」**
  (互相打断, 只改显示与双击打断打谁, 不动任务); **说完一句就收窗** (投出去之后上限 0.3 s、不挂起、
  人声照旧续期, 看门狗节拍 2 s → 0.5 s); **自动指令的冷却由主人自己定** (新建时选 + 管理里直改)

**插件第二批 (2026-10-10, 未发版)**: 两件事一起落的, 协议一个字没动

- **鲸鱼娘桌面小组件** (当时的 `samples/whale-widget/`, 模块 `:sample-whale-widget` —— 这一份**已经
  收进 `archive/samples/whale-widget/`**, 见插件第四步那一节): 协议第 10.4 节那条
  "桌面组件只能由伴侣 APK 声明"的实物。**动的是启动器那一侧** —— 交出去的是一台
  `ViewFlipper` (`android:autoStart`), 里面一张一帧, 伴侣不常驻任何进程。三条读数定下了这条路:
  动态 GIF 交给 `setImageResource` **不解** (`AnimatedImageDrawable` 那条链没走通),
  `<animation-list>` 也不动 (`gfxinfo` 三秒停在 56 帧), 只有 `ViewFlipper` 那条在动 (56 → 364)。
  **导入一份 GIF** 落在 `filesDir/whale-imports/<键>/` (原始文件 + 解出来的逐帧图), 帧走内容 URI
  交给宿主 (位图过 binder 会撞 1 MB 上限), 读权限由 `grantFramesToHosts` 放给"现在正在当桌面"的
  那几个应用 —— 少放一次桌面那格就是「Can't load widget」, 而**找"谁是桌面"那一步还需要清单里
  一段 `<queries>`** (targetSdk 33 起不声明就看不见别的包, 那一段没了是静默失败)。随包只留主人给的
  那一套 (`art/dance-1.gif` → `tools/build-assets.py` 抽 32 帧), 新动作由用户自己导
- **应用对应的技能**: 随包那份目录在 `app-skills/` (`catalog.json` 一行 + 每个技能一份 `SKILL.md`),
  设置页多了「技能」一段, 桥多了 `skill` 方法 (`op=scan` / `op=install`)。落点是
  `$DSH_HOME/skills/`, **只加不改** (已经在的那一份一个字节都不动); 点名装 (`skills:[…]`) 时
  **不要求那个应用在场**, 不带点名才是"按这台机器上装了的应用来"
- **插件支持从链接安装** (协议第 13 节的裸 URL 那一条): `plugin/PluginDownload.kt` 只收 https
  (每一跳都重查)、边读边数 32 MB 上限、下到 cache 之后交给 `PluginInstaller` 走**与本地包完全
  同一条链**; 设置页那一段多一行「从链接装入」, `lw_plugin` 的 `install` 多收一个 `url` (与 `path`
  二选一)。签好名的 `.lwp` **当时**发在 `plugins/` 下 (旧版本跟 Release 走), 那是给它一条稳定的
  https 地址 —— 这台机器上 `github.com` 与 `raw.githubusercontent.com` 都不通, 所以文档里写的是
  `cdn.jsdelivr.net/gh/yuloong07-star/Luwi@main/plugins/…` 那一条。**2026-10-11 主人定了新口径:
  不要放 Luwi 仓库** —— 那几个 `.lwp` 撤掉了, 插件包的家只有一个 (`yuloong07-star/luwi-plugins`
  的 `packages/`, 钉内容 tag), `plugins/` 只留一页指路, 见下面插件第四步那一节
- **插件段多了「安装伴侣 APK」** (2026-10-11 主人: "插件里新增安装伴侣APK功能"; **同一天傍晚又按主人
  的意思撤掉了** —— 新包把地址写在 `entry.apk` 里、装完顺手就装上, 那一行只剩给没带地址的老包兜底):
  companion 插件装的
  是**两样东西** —— 一份 `.lwp` 说"绑哪个包、哪个 Service", 而**那个包本身要另外装**, 工具 / 桌面
  组件 / 它自己的界面全在里面。少了这一步, 从链接装完 `.lwp` 的人只会看到"启用着但宿主没接上"却
  不知道该去装什么, 所以设置页那一段多一行 (粘一个 https 链接进来), 插件详情里也如实报一句"伴侣
  应用 <包名> 装没装、什么版本"。`plugin/CompanionApk.kt` 是那一条链, 三条与 `PluginDownload` 同
  口径 (**只许 https 且每一跳重查** / 边读边数 256 MB 上限 / 前两个字节得是 `PK`), 另加一条它自己
  的: 装之前拿 `getPackageArchiveInfo` **对一次包名** (点名时要的那个包对不上就不动), 落地那一步
  交给**系统安装器** (`FileProvider` 出一个 `content://` + `ACTION_VIEW`, 装不装由人在那一页点) ——
  那台设备缺「安装未知应用」时先把人送到 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 那一页再说, 不装作
  点了就有反应。**下下来的那份不当场删**: 安装器是另一个进程, 它读这个 URI 在我们返回之后, 所以
  那份留在 cache 里、下一次取新的时扫掉旧的; 装完要回设置页点一下插件的「启用」, 宿主才会重新去绑
  (与"升级插件要重启 host"同一条规矩)。判据在 `tools/check-plugins.mjs` 第一二处新的那五条
- **这一批随 2.7.4 一起发了** (2026-10-10): `versionCode 12 / versionName "2.7.4"`, 干净构建
  (`:app:clean` 之后全量 `assembleDebug`, host 树重打) 226,357,300 字节 (215.9 MiB), sha256
  `723b6298000f506945334187f7dd8f90f3441a000f86c47c8f13a65ce46799d8`; `tools/apk-bytes.py` 量出
  最大无归属区间 4,098 字节 (与 2.6.0 / 2.6.5 / 2.6.7 那几份干净包同一处、同一个量级); 发在
  `yuloong07-star/Luwi` 的 `v2.7.4` (Release 正文在工作区 `docs\DSH-LW-2.7.4-release-notes.md`)。
  同一版里还进了**全仓图标换成 Luwi 自己的标记** (启动器 / 通知栏 / 球上那圈 0.378 → 0.65), 那一批
  自己的记录在 `docs/floating-input.md` 与 `tools/make-icons.py` 的文件头

**插件第三步: 一个地址装完即用 (2026-10-11, 未发版)**: 与上两批不同, 这一批**动了协议一个可选字段**

- **`plugin.json` 的 `entry` 多一个可选的 `apk`** (协议第 4 节与 8.1): 那个伴侣 APK 自己的 https
  地址, 只收 https (与第 13 节同一条口径), 而且它在签名载荷里。**两个方向都向后兼容** —— `entry`
  那一段本来就不做未知键检查, 所以不认这一项的旧 LW 只是把它忽略; 没有这一项的老包照旧走手动那条路
- **装完 `.lwp` 顺手把伴侣补上** (`plugin/PluginCompanion.kt`): 那个包没装着时下下来 → 对一次包名
  → **静默装** → 启用。静默那条走的是特权白名单里本来就有的一条 (`pm install -r -t`, 见
  `LwSystemCommandTable`), 2026-10-11 才接上这个调用点 —— **白名单一个字没动**, 也没有给谁多开一个
  能力面。没有特权 (root 与 Shizuku 都没有) 或 `pm install` 非 0 时退回**系统安装器**, 而那一支
  **不自动启用** (人还在安装器那一页, 什么时候回来不由我们定)
- **这一份要过两个地方, 少一个都装不上** (2026-10-11 在模拟器上实测撞出来的两条, 都不是猜的):
  1. **应用写到哪**: 应用自己的**外部媒体目录** `/sdcard/Android/media/<包名>/lw/companion` —— 不
     需要任何权限就能写, 而**特权那一侧读得到**。应用私有 `cacheDir` 不行: 真机上实测 shell 读
     `/data/data/<包名>` 是 Permission denied, 而同一份文件放进媒体目录后 `test -r` 回 READABLE
  2. **`pm` 从哪读**: **不能把这个路径直接交给 `pm install`** —— `pm` 不是自己读文件, 它把路径交给
     **system_server**, 而 SELinux 不许 system_server 读 FUSE (共享存储) 上的东西:
     `avc: denied { read } ... scontext=u:r:system_server:s0 tcontext=u:object_r:fuse:s0`, **root 也
     一样** (拦的是 system_server 那一侧, 不是调用方)。所以特权那一侧先把它抄进 `/data/local/tmp`
     (system_server 读得到的地方) 再 `pm install -r -t`, 抄的那一份装完就删 —— 这就是
     `LwSystemCommandTable` 里新加的 `installStaged`。**`install` 那一条原样留着** (给
     `lw_app_control` 装 `/data/local/tmp` 或 `/data` 下的包用), `installStaged` **不进模型那张
     op 名单**, 只有插件装机链自己走
- **装机时一次确认** (`SettingsScreen.PluginCapabilityDialog`): 包声明了能力就先摆出来问一次, 普通档
  默认勾、敏感档默认不勾 (协议第 5 节那句"安装时列出, 一次确认")。**一条能力都不声明的包连这一次
  都没有** —— 粘一个地址就装完即用, 鲸鱼娘是那个实物。详情页里"伴侣应用没装"那一行旁边多一颗
  「把伴侣 APK 装上」的按钮 (清单里带了地址才有), 老包看不见它
- **两条路的分工**: 设置页那条有人看着屏幕, 会吊系统安装器、会按勾选授权、会启用; 桥
  (`lw_plugin op=install`) 那条跑在后台, 只允许**静默装**、不弹确认、不自动启用、能力一条不给
- **鲸鱼娘那一份抬到 1.1.3 重签** (`plugins/whale-widget-1.1.3.lwp`): 伴侣的 `describe()` 必须与
  包里的 `version` **逐字对上** (`PluginHost.compare`), 所以插件包抬版本时那个 APK 也得抬
  (`WhalePluginService.VERSION` 与 `build.gradle.kts` 的 `versionName` 一起), 两份资产发在同一个
  Release 里, 地址就写在上面那段 `entry.apk` 中

**插件第四步: 方案 v2 "安装即用" (2026-10-11, 随 2.7.6 一起发了)**: 主人的原话是"安装插件还要再安装 apk, 这不符合
插件理念 …… 应该是安装即用, 不用在桌面多一个应用"。方案全文在工作区
`docs/LW-软件插件协议-v2-安装即用方案.md`, 协议正文按它增补 (第 0 节三条决定 + 第 2 / 4 / 5 / 8 /
10 / 11 / 15 节)。这一批**动了协议本身, 但 `protocol` 字符串没动** (还是 `lw-plugin/1`): 新域是加法,
老包一个字节都不用改

- **宿主槽** (`plugin/widget/`): 桌面上那两格改由**主 APK** 的清单声明 —— `WidgetSlotAnim`
  (「Luwi 动画组件」, `ViewFlipper` 帧序列) 与 `WidgetSlotImage` (「Luwi 图文组件」: 图 + 标题 +
  正文 + 最多两颗按钮), 插件只往里填内容, 于是"往桌面放一只鲸鱼娘"不再需要第二个 APK。**同槽互斥**:
  一个槽同一时刻只挂一只插件, 映射在 `$DSH_HOME/plugins/.widgets.json`, 换一只只重画 (设计不是缺陷)。
  帧图走 `content://` (位图过 binder 会撞 1 MB 上限), 读权限**每一趟组装前重放一次**, 开机 / 解锁 /
  覆盖安装再由 `WidgetRegrant` 补一轮 (清单里那条窄的 `BOOT_COMPLETED` **只干这件事, 不起 host**);
  "插件没了"与"帧图读不到"各有各的空态正文; 卸插件时先清槽再重画
- **`art` 域**: 一包资源, 零代码, 零第二次安装 —— 能提供浮标外观 (`theme/ball.json`)、桌面组件的内容
  (`widget/widget.json`)、面板与设置声明; **不许带工具, 不许声明任何能力**。包里所有指向自己的相对
  路径**查两遍** (装的时候 `PluginPaths.checkPackage`、读图那一刻 `WidgetFramesProvider` 再兜一次),
  比的是 `canonicalPath` 前缀
- **`inline` 域** (`plugin/inline/`): 原 `dex` 那一档 (老名字当别名收)。`InlineHostService` 跑在清单里
  预声明的 `:plugin` 进程里, 优先 `InMemoryDexClassLoader`; 抛异常转成一条工具错误文本, 卡死由主进程
  15 秒超时如实报。**只对白名单作者开放** (与 Luwi 同签名, 或公钥身份写在
  `$DSH_HOME/plugins/.inline-allow.json`): 它与 Luwi 同 uid, 能力检查对它是体验不是墙 —— 开发者模式
  也不开这个口子
- **`companion` 收窄**: 只有"要主 APK 清单里没有的权限 / 特殊清单组件 / 自己的进程"才准用, 而且
  **不许带 MAIN + LAUNCHER** (入口 Activity 写在 `entry.activity` 里, 由插件页那颗「打开」用显式组件
  拉起); `tools/check-plugins.mjs` 静态守住
- **装完即用那一条链**: 零能力 (或声明的能力全勾上了) 的包装完**自动启用**; 声明了 widget 而槽空着就
  顺手挂上, 再问一句"要放到桌面吗" (`requestPinAppWidget`, 系统那张"添加到主屏幕"的卡)
- **插件那一段现在长这样**: 插件列表 → 「桌面组件」两行 (动画 / 图文) → 「浮标外观」一行 → 装入插件包
  → 从链接装入 → 开发者模式。2026-10-11 按主人口径收掉了两处: **「安装伴侣 APK」那一行删掉**
  (新包把地址写在 `entry.apk` 里自动装, 那一行只剩给老包兜底却顶着每一台机器), **「打开它自带的界面」
  只在 `kind == companion` 时才画** (art / inline 点下去只回一句"这一档没有自带的界面", 那是个空按钮)
- **指纹口径改了**: 发布者指纹从"SPKI 原字节的 sha256"改成**规范化的公钥身份** (曲线 OID + 定长未压
  缩点) 的 sha256 —— 白名单 / `.publishers` / 插件页 / `tools/lw-plugin-sign.mjs` 四处同一串
- **样例与包**: `samples/art-whale-widget/` (鲸鱼娘 2.0.0, kind art, 桌面上那一格由宿主槽画; 老那份
  `samples/whale-widget` **已经收进 `archive/samples/whale-widget/`** —— 2026-10-11 主人点头之后
  搬的, 它不再是 Gradle 模块, 能构建的拷贝在 luwi-plugins 仓库)、`samples/art-ball-theme/`
  (浮标外观) 与 `samples/inline-timer/`
  (番茄钟, Java 源码 + `tools/build-dex.ps1` 出 `main.dex`); 签好的包**只在 `luwi-plugins` 仓库的
  `packages/`** (Luwi 这边不放, 2026-10-11 主人定的口径; `plugins/` 只剩一页指路); 判据是
  `tools/check-plugins.mjs` (87 条) 与 `app/src/test/.../plugin/PluginV2Test.kt` (公钥身份那一段读数
  与签名工具打印的那一串是同一个数)
- **浮标外观那一档也接上了** (`plugin/theme/BallTheme.kt` + `BallView` 那条外部图 / 帧序列分支):
  多份主题插件可以同时装着, 设置页「浮标外观」那一行挑一个; 读不到 (插件被删 / 目录被改坏 / 图缺一张)
  一律回内置画法。样例是 `samples/art-ball-theme/` (五张图 + `tools/build-art.py`), 设备上验过:
  挑中那一份球当场换画法, 把那插件卸掉当场回内置
- **还没做的**: `ui.panel` (声明式面板) 仍是"声明可装, 调用即如实拒" (P4); `script` 域未接通 (P3)
- **这一批随 2.7.6 一起发了** (2026-10-11): `versionCode 14 / versionName "2.7.6"`, 干净构建
  (`:app:clean` 之后全量 `assembleDebug`, host 树重打) **226,616,833 字节 (216.1 MiB)**, sha256
  `406c63b3383e0c94e55534141566392a83b82420f58ce832d8dbbce662a594b5`; `tools/apk-bytes.py` 量出最大
  无归属区间 4,124 字节 (与 2.6.0 / 2.6.5 / 2.7.4 那几份干净包同一个量级); 发在 `yuloong07-star/Luwi`
  的 `v2.7.6` (Release 正文在工作区 `docs\DSH-LW-2.7.6-release-notes.md`)。同一版里还进了: **"同一场
  对话"的窗口 20 分钟 → 10 分钟** (`host-plugin` 的 `VOICE_SESSION_MS`, 判据在
  `tools/check-voice-inbox.mjs`)、**「返回应用」与「双击回复框」两扇门的落点分开**
  (`overlay/BallMinutes.kt` 的 `ReturnTarget`, 通知那条 intent 里过时的会话 id 不再作数, 见
  `MainActivity.requestedSession`)、**取景网格那批图交得出去** (`host-plugin` 的 `attachPictures`
  现在两种形状都认: 盘上的路径 / 手里的字节), 以及包里带地址的伴侣 APK 自动补装 (`entry.apk`)

## 工作区与存储

工作区在共享存储里, dsh 自己的配置在 app 沙盒里:

| 用途 | 位置 | 谁需要访问 |
| :-- | :-- | :-- |
| `$DSH_HOME` (配置 / 凭据 / 会话 / profiles) | app 沙盒 `filesDir/dsh-home` | 只有 dsh 自己 |
| 工作区根 | `/sdcard/DSH/`, 没拿到所有文件访问权限时是 `/sdcard/Android/media/<pkg>/DSH` | dsh + 用户 + 文件管理器 |
| 单次会话的工作目录 | 工作区根下的子目录 | 同上 |
| `完全权限` 会话 | 整个 `/sdcard` | 用户在会话里显式启用 |

配置里**含 `credentials` 和 `settings`**, 不该落在任何别的 app 都能读的地方; 而工作区是用户要拿文件管理器翻的, 必须可见 —— `resolveDshHome` 本来就把这两件事分开, 所以直接映射: **`DSH_HOME=<filesDir>/dsh-home`, 工作区是 host 的 cwd 也是它的 home**

### 目录授权 (不走 SAF)

**SAF 用不了, 这是查证后的结论不是取舍**: dsh 的工作区是**真实路径** (目录选择器从 `homedir()` 起逐层列真目录, 会话的 `cwd` 就是 `workspace.path`), 而 SAF 只给 app 一个 `content://` 树, 只有本进程的 `ContentResolver` 能用 —— host 是 **Node 子进程**, 拿不到任何路径权限。所以只有 **`MANAGE_EXTERNAL_STORAGE` (所有文件访问权限)** 一条路

`host/Workspace.kt` 按顺序解析, **每个候选都先写一个探针文件证明可写** (权限可能被撤销, 能列目录不等于能写):

| 顺序 | 目录 | 条件 |
| :-- | :-- | :-- |
| 1 | `/sdcard/DSH` | 拿到所有文件访问权限 |
| 2 | `/sdcard/Android/media/<pkg>/DSH` | 无需任何权限, 文件管理器也看得见 |
| 3 | `filesDir/DSH` | 兜底, 沙盒, 别的应用读不到 |

- 权限只能跳系统设置页 (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`), 不能弹窗申请; 这条权限**在设置页已经没有入口了** (2026-10-06 撤掉「工作区」段), 要么 `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` (`tools/lw-install.ps1 -Perms` 也带这一条), 要么手点那一页, 改完重启 host 生效 (`DshHost.restart` 会等旧进程真的退出再拉起, 否则端口没释放) —— 实测那条 appops 就足以让 `isExternalStorageManager()` 变 true
- **`HOME` 指向工作区**: GUI 的「选择工作区」从 host 的 home 开始列, 所以开屏就在工作区里; dsh 自己的状态仍在 `DSH_HOME` (优先级: 显式配置 > `DSH_HOME` > `~/.dsh`)。进程 cwd 也是工作区
- `完全权限` 是**会话级**开关: 没启用时会话的文件工具与 shell 被限制在工作区根内, 启用后才放开到整个 `/sdcard`; 这个限制落在 dsh 自己的 fs/sandbox 策略上, **不是安卓层面强制的** (安卓给了权限就是全给), 别误解成"系统级隔离"

### `/sdcard` 是 FUSE

`/sdcard` (以及 `/storage/emulated/0`) 是 FUSE 挂的, 不是真 POSIX 文件系统:

- **没有 inotify**, 原生 `fs.watch` 不工作, dsh 有兜底 (`watchFile` 轮询与 chokidar 轮询模式), 所以是**降级为轮询**而不是坏掉, 但大仓库下开销明显
- **symlink / hardlink 受限**, `chmod` 语义不完整, `stat` 里的权限位不可信, 任何依赖符号链接或权限位的逻辑要能容忍失败
- 随机小 I/O 慢, 大量小文件 (比如 `node_modules`) 会很痛
- 安卓 11+ `/sdcard/Android/data/<pkg>/` 别的 app 与 adb 都访问不到 —— 所以我们用 `/sdcard/DSH/` 这种顶层目录

`$DSH_HOME` 在 `filesDir` 里是**真 ext4/f2fs**, 所以配置那部分完全不受这些限制, `credentials` 的文件监听照常工作

### Linux 沙盒 (2026-10-12)

`lw_linux` 那条工具背后是这个: 随包的 Alpine minirootfs (`app/src/main/assets/lwlinux/base.tgz`) 解到
`/data/local/tmp/lwlinux/rootfs` (同目录的 `stamp` 是"装好了"的戳, 照 `HostInstaller` 的形制),
由特权通道以 **shell uid** 起 proot 在里面对话 —— 命令形状全在 `channel/LwLinuxSandbox.kt` 里写死
(proot / loader 从 nativeLibraryDir 来, rootfs 的路径不许搬家: `--link2symlink` 的链子写死了构建时
的宿主绝对路径), 动词只有 install / run / status / reset 四个

- **它不是安全沙盒**: proot 那层路径视野只是视野, 唯一的边界是 uid 那条 (沙盒读不到
  `filesDir/dsh-home` 是因为它是 0700 的 app uid); 反过来说 shell uid 能读的东西沙盒也能,
  **网络是通的且关不掉**, 能外发的就是 `/work` 看得见的东西
- **数据是这个 uid 的**: `/data/local/tmp/lwlinux` 属主 shell (uid 2000), 与别的 app 数据之间靠
  目录权限分隔, 不是容器; 组长 pid 记账住在 `/data/local/tmp/lwlinux-run` (故意不在被 reset 删的树里)
- **清理口径**: 通道断开时这个目录连删除按钮都够不着 (删它要特权), 要清就是重装 Luwi 或 adb 手动删
  (`rm -rf /data/local/tmp/lwlinux /data/local/tmp/lwlinux-run`)
- **`/work` 是 noexec**: 编译出来要执行的二进制别留在 /work 里跑 (`/sdcard` 是 noexec, `./hello` 会
  Permission denied), 把产物落进 rootfs 里再跑 (`cc -o /tmp/hello ...`); 脚本类不受影响
  (python3 / sh 由解释器读文件)
- **数字**: 单条命令额外延迟 ≈ +50 ms (proot 那层), 首装 ≈1.5 s, `apk add` 是网络主导 (整个工具链
  ≈300 MB, 装之前看一眼 /data 余量); 完整一轮见 `docs/LW-Linux沙盒-P5回执.md`
- 设置页「Linux 沙盒」那一段与 `lw_linux` 工具是它的两个入口, 对用户的边界说明写在
  `settings_linux_note` 与 `assets/modes/phone.md` 第十一节

## 构建与验证

**发版前必须干净构建, 而且构建完要验一遍 APK 里没有"无归属区间"** (2026-10-05 实测的教训): 增量构建会在 APK 里留**连续空洞** —— 1.0.3 那个 363.8 MiB 的包里夹着 **96.5 MiB** 死字节 (位置在 `classes7.dex` 末尾与 `assets/ocr/det.onnx` 开头之间, 大段零字节夹零星 `PK\x03\x04`), 而同一份源码干净构建只有 **275.6 MiB** (最大无归属区间 4 KB)。验法: `python tools/apk-bytes.py app/build/outputs/apk/debug/app-debug.apk` —— 它逐条走本地头, 把没人认领的区间按大小列出来, 最大的那条应当只有几 KB (对齐与数据描述符)。同一份脚本配 `python tools/host-tree-size.py <host.zip>` 看 host 树的成分 (按包名分组排大小)

**提交时直接跳过签名, 不要为这个去解锁 key**: 全局 git config 开了 `commit.gpgsign` / `tag.gpgsign` 且 `gpg.format=ssh`, 用的 key 带 passphrase, 而开发机上没有 ssh-agent, 所以非交互提交必然失败; 本仓库已经在本地 config 里关掉了 (`git config commit.gpgsign false` / `tag.gpgsign false`), **新克隆要再跑一次, 或者单次用 `git commit --no-gpg-sign`**

**装机就走 `tools/lw-install.ps1` 一条命令, 别手工推** (2026-10-17 重写成"一键"): 它的顺序是
**流式安装 → 失败才退到"推上去 + `pm install -i com.android.packageinstaller -t`"** —— HyperOS 上
`pm install` 会被 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 挡掉 (22041216C /
OS2.0.12.0 实测), 而 `adb install` 与带 `-i` 的 pm 安装之一能过, 两条都不通才提示去开发者选项开
「USB 安装」。装完它连着一口气把无障碍与通知使用权写回去, 然后**拉起应用并等 `dsh web: http://…`
那一行** (host 树是首启解压的, 不拉起就只是"装了个壳"), 最后打验收读数与设备上的 versionName。
`-Serial` 不填时**优先真机**, 只有模拟器在线才用模拟器; `-Build` 会先跑 `dev.ps1 build`

**看到 APK 体积突然变大, 先怀疑"旧的还没删"**: 2026-10-17 一次 `assembleDebug` 出了 **344 MB**
(正常 232 MB), `tools/apk-bytes.py` 一量就是上一条那种空洞 (旧 apk 还在时新包被追加写进去)。
先 `Remove-Item app\build\outputs\apk\debug\app-debug.apk` 再 build 就回到正常体积

### 常用命令

| 动作 | 命令 |
| :-- | :-- |
| 快速编译检查 | `.\gradlew.bat :app:compileDebugKotlin` |
| 出 APK (**提交前必跑**) | `.\gradlew.bat :app:assembleDebug` |
| 装到设备 | `pwsh -File tools\lw-install.ps1` (**一键**: 自己挑设备 / 装机 / 无障碍与通知使用权回写 / 拉起应用等 host 就绪) |
| 看当前引擎解析出的版本 | `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` |
| 拉日志 | `adb logcat --pid=$(adb shell pidof -s io.github.yuloong07star.luwi)` |

真机: `adb connect 192.168.1.103:5555`; submodule: `git submodule update --init --recursive` (新克隆后) / `git submodule update --remote third_party/deepseek-harness` (升级 dsh)

**改完 Kotlin 先跑 `:app:compileDebugKotlin`, 别跳**: AGP 9 + 内建 Kotlin 的编译错误信息有时候只说 "UNRESOLVED_IMPORT", 真正的文件行号在它上面几行

**但 `compileDebugKotlin` 通过不代表能打包**: 它**不跑 manifest merger**, 所以 `minSdk` 冲突、manifest 问题这类错误完全看不见 —— **每次改完依赖或 `minSdk`/`targetSdk`, 都要跑一次 `assembleDebug`**

### 迭代循环 (改 dsh / 改 Kotlin 各一条路, 照抄即可)

改 dsh 源码之后 (在 fork 开发克隆 `B:\Git\deepseek-harness` 里改, submodule 只接受同步过来的结果):

```powershell
cd B:\Git\LittleWhale
# 1. 生成补丁并同步进 submodule (取的是 fork 工作区里还没提交的改动, 已 commit 的就 diff <pin>..HEAD)
git -C B:\Git\deepseek-harness diff > build\lw-dsh-patches.diff
git -C third_party\deepseek-harness apply (Resolve-Path build\lw-dsh-patches.diff).Path
# 2. 出平铺 host 树 (pack 两个家族 + npm install, 约 5 分钟; 已经顺带做了 build)
node tools\pack-host.mjs --dsh third_party\deepseek-harness --out build\host-tree
# 3. 推进 app 沙盒, 重启 app, 看两个 tag
node tools\push-host.mjs build\host-tree
adb shell am start -S -W -n io.github.yuloong07star.luwi/.MainActivity
adb logcat -d -s DshHost -s DshWebView
```

改 Kotlin 之后: `.\gradlew.bat :app:assembleDebug` → `adb install -r app\build\outputs\apk\debug\app-debug.apk` → 上面第 3 步的启动与看日志

**判据**: `DshHost: dsh web: http://…?token=…` = 加载器整棵树起来了; `DshWebView: shell {...}` 里 `rootChildren` 非 0 = GUI 渲染出来了 (那一行还带 `vh`, 界面"塌了"先看它)

**装完 APK 之后别让设备息屏**: host 树换了版本时首启要解压 3 万个文件, 而 app 一进后台就被系统冻住 (`ps` 里是 `do_freezer_trap`), 解压跟着停在原地**看着像卡死** —— 点亮屏把它拉回前台就会接着解压完

**动 `svc power stayon` 之前先把它读出来, 收尾时写回原值**: 那个开关就是用户自己的「充电时保持亮屏」(全局设置 `stay_on_while_plugged_in`), 无条件写 `false` 会把用户的设置**关掉**而没有任何提示。所以 `settings get global stay_on_while_plugged_in` 记下数字, 验证做完写回原值, **不要默认写 0**

**adb 上出现第二个设备时, 每条命令都要指名设备**: 不带 `-s` 会以 `more than one device/emulator` 失败, 包括 `tools/lw-bridge.ps1` 这种内部调 adb 的脚本; 省事的办法是当前 shell 里 `$env:ANDROID_SERIAL='192.168.1.103:5555'`, 子进程会继承

**每次 `adb install -r` 都会把我们踢出 `enabled_accessibility_services`**: 装完要么在设置页拨一下那个开关, 要么 `settings put secure enabled_accessibility_services <原值>:<我们的组件>` —— **同样要读出来改**, 设备上还有别人的服务

**装机之后别再强停**: `am start -S` 会把系统那个无障碍绑定实例摘掉, 而**条目还留着** —— 现象是"设置里明明开着、服务却没绑上" (`dumpsys activity services <pkg>` 里那条 `LwAccessibility` ServiceRecord 不见了), 于是读屏与事件订阅静默失效。所以装完要用**不带 `-S`** 的 `am start`, 而 `tools/lw-install.ps1` 判过"绑定成功"之后就别再动它。恢复要走"摘掉 → 停 800ms → 放回"那套 (设置页那个开关自己会做), 而**这只在重装打开的写入窗口里写得动** —— 2026-10-04 在 vivo 上实测: 同一次 shell 里 `pm install` 之后立刻写探针也读到 `null`, 也就是窗口没接住时连"装 + 写回"连着一口气做都不行; 而 `tools/lw-install.ps1` **只在条目缺失时才写**, 遇到"条目在而实例没了"它什么都不做, 得自己走那套摘/放

### 推送与发布 (这台开发机上 github.com 被挡着)

**这条网络上 `github.com:443` 不通, 而 `api.github.com` 与 `uploads.github.com` 通** —— 所以 `git push`
会以 `Failed to connect to github.com port 443` 失败, 而 `gh api` 与 `gh release create` 一切正常
(2026-10-04 实测: 五个 github.com 的 IP 全部超时, 换 HTTP/1.1 一样, 没有 IPv6, 本机也没有代理端口)。
三条路:

1. **SSH 走 443 是通的** (`ssh://git@ssh.github.com:443/yuloong07-star/Luwi.git`), 但本机那把
   `id_rsa` 属于**另一个账号** (`Yuloong07`), 对 `yuloong07-star/Luwi` 没有写权限; 而 GitHub 的 SSH
   **不允许端口转发** (`-L` / `-D` 一起来就关), 所以拿它当隧道也不行
2. **用 API 重放提交** (这次用的就是这条): `tools/lw-api-push.ps1` 上传 blob → 按 `base_tree` 建 tree →
   建 commit → **最后才移动 ref**, 而且**每一个对象都与本地算出的 SHA 逐个比对**, 全对才动 ref
   (前几轮只创建没人引用的对象, 不动 ref 就影响不到仓库)。实测那 24 个提交**全部逐字节一致** ——
   等于一次正常 push, 连带注解的 tag 对象重放出来的 SHA 都相同
3. 让有代理的环境推, 推完回这台机器 `gh release create`

要记住的两条: `-input` 送的 JSON **不能带 BOM** (`[Text.Encoding]::UTF8` 会写 BOM, GitHub 直接回
`Problems parsing JSON` 400), 以及提交对象的正文末尾那个换行是**对象里本来就有的**, 送错一个字节
SHA 就变 —— 所以脚本先按原样送, 不一致再去掉一个换行重试

### adb 安装失败时怎么装 (termux / root 兜底)

小米 / HyperOS 上 `gradlew installDebug` 或 `adb install` 会失败 (典型原因是设备上弹了安装确认框而没人点, `INSTALL_FAILED_USER_RESTRICTED`, 或 MIUI 的"USB 安装"开关没开); 本机这台设备上**普通 `adb install -r` 其实是通的**, 下面这条是失败时的兜底, 已实测可用:

```sh
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/lw.apk
adb shell "su -c 'pm install -r /data/local/tmp/lw.apk'"   # root 的 pm 绕过 adb 安装那套授权交互
adb shell "pm list packages | grep luwi"            # 确认
adb shell "su -c 'rm -f /data/local/tmp/lw.apk'"           # 清理
```

经 termux ssh (`ssh -p 8022 192.168.1.103`) 走 `su -c 'pm install -r ...'` 效果一样; `pm` 在 `/system/bin/pm`, root 下可用

## 还能做

第 8 步 (端侧 OCR) 留了三件, 按值得做的顺序: **rec 批量重导** (25 行现在 250-400 ms, 最大的一块) / **试 ORT 自己导出 ctx** (成了能拿掉 70 MB 的 `libQnnHtpPrepare.so`) / **det 换非正方形输入**; 量化排最后

不阻塞的尾巴:

1. **导出走页面内的 `blob:`**, 不经过 `DownloadListener`, 要接得加一座 JS 桥 (`addJavascriptInterface` 或 `WebViewCompat.addWebMessageListener`)
2. **jniLibs 挂 Release**: 那 17 个对象是 Termux 编的, 现在只在本机有一份, 正式形态是挂 GitHub Release 由 Gradle task 下载
3. **体积裁剪** (可选): 只有 `node-pty` 确定能删; 工具链那头考虑塞个 busybox (toybox 缺 `grep -P` / `sed -i` / `find -printf`), 但注意 PATH 顺序

还没验 / 没定的:

- 文件管理器能不能浏览 `/sdcard/DSH` 与 `Android/media/<pkg>/DSH`, 以及工作区没有 inotify 时轮询的实际开销 (必要时给工作区再开个"本地缓存目录")
- dsh 的 fs 工具有没有 fsync / rename-based 原子写, 在 FUSE 上语义可能不同
- 一块虚拟屏加一路预览 surface 到底占多少内存与 GPU (11 GB 设备上不紧张, 但要有数)
- 要不要把 `activity.allow` 换成更粘的刹车 (抬手 1 s 之后就能再动手), 以及那时用户在哪放开

> 本文档只留**现状、决策、约束、怎么操作**。每一步的清单 / 实测数字 / 踩坑在 `docs/step2-record.md` … `docs/step8-record.md`, 构建与依赖的旧账在 `docs/host-build.md`, fork 侧改动的唯一权威是 `B:\Git\deepseek-harness\patch.md`
- **设置页「技能」那段能删技能了; 技能要从 `yuloong07-star/app-skills` 检测** (2026-10-10 主人: "设置里的 skill 板块加上删除 skill 按钮" + "skill 应该检测 https://github.com/yuloong07-star/app-skills 这个"): 删走 `AppSkills.remove` + 桥上 `skill op=remove` (与 install 同一套护栏: 点名的那几个必须在 `$DSH_HOME/skills/` 里真的在, 不许糊弄), 名字只许当**目录名**用 (`isSafeSkillName`: 空 / `.` / `..` / 带斜杠一律拒, 见 AppSkillsTest), 只删 `$DSH_HOME/skills/<名字>/` 那一份且要求下面有 `SKILL.md`; 界面上点一条**已经在**的技能就弹出"删掉这个技能"那一问 (与「快捷指令」那段同一个做法 —— 那个对话框本身就是确认), 删完重扫一遍那一段。**它是不可逆的**: 没有回收站, 主人手改过的内容回不来, 「一键装入」只能把随包那份抄回去 —— 这句写在对话框的摘要里。**更新源那条一度读到 `luwi-plugins` 是错的** (那个仓库没有 Release / Tag, `releases/latest` 404), `AboutSettings.DEFAULT_SOURCE` 已撤回成 `yuloong07-star/Luwi`; 主人真正要的是**技能**从 `yuloong07-star/app-skills` 检测, **这一条当天也接上了** (`AppSkills.REPO` 就是那个仓库, 走 GitHub 的 contents API —— 这台机器上 `api.github.com` 通而 `raw.githubusercontent.com` 不通; 主人点「刷新技能目录」时把 `index.json` 取回来缓存在 `$DSH_HOME/app-skills-index.json`, 平时只读那一份本地缓存、一个网络请求都不发; 装的正文也先问仓库要、取不到才退回随包那份); 那份仓库的形状: 根下 `index.json` (9 KB, 每条 `{id,name,package,path,verified,risk,…}`), 正文在 `skills/<id>/SKILL.md`, 共 21 个 `SKILL.md` (含 `_template`, 真技能 20 个)
