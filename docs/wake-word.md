# 唤醒词 (本机离线)

「喊一声肥鱼肥鱼, 把 dsh 叫起来」这条路的落点。这份文档记的是它由哪几块拼成, 模型与关键词的
格式为什么是那样, 以及哪些地方验过、哪些还没有。

## 一、四块分工

| 在哪 | 做什么 | 文件 |
|---|---|---|
| 宿主 (app 里那个 Node) | 取模型、写词表、把「开始听」交给 app | `host-plugin/index.mjs` 里的 `lw_wakeword` 与 `WAKEWORD_*` |
| app 进程 (Kotlin) | `KeywordSpotter` 建识别器、读麦克风、命中之后的四件事 | `wake/WakeWordService.kt`、`wake/WakeWordModel.kt` |
| app 进程 (Kotlin) | 设置页那个按钮背后的下载 (逐文件 sha256 + 进度) | `wake/WakeWordDownload.kt`、`wake/WakeWordWords.kt` |
| 通道方法 | 把上面那些暴露成 `wakeword` 一件事 | `tool/LwWakeWord.kt` + `channel/PrivilegedBridge.kt` 一行 |
| 设置页 | 状态、下载、改词、那个开关 | `ui/SettingsScreen.kt` 的 `WakeItems` |
| 页面上的指示器 | 输入框上沿那个麦克风, 在听的时候才出来, 点一下停 | `ui/HostScreen.kt` 的 `WakeBridge` 与 `WAKE_BADGE_JS` |

识别引擎与语音输入是**同一份 sherpa-onnx AAR** (`app/build.gradle.kts` 里那个取 AAR 的任务):
语音输入用 `OfflineRecognizer` (SenseVoice), 这里用 `KeywordSpotter` (zipformer KWS), 所以这一块
不新增依赖, 只多一个模型目录。

## 二、模型

中文的 zipformer KWS, 3.3M 参数, 放在 `filesDir/wake-word/kws-zipformer-wenetspeech-3.3M/`。**只留
int8 那一套** (4 个文件, 5 519 884 B, 约 5.3 MB):

| 文件 | 字节 | sha256 |
|---|---|---|
| `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 4777666 | `dd784973fc9d2fabb3b800d6dcd20fc3b0ca84f8e2415afe54b032878e447f4d` |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | 675349 | `fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774` |
| `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 65242 | `f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24` |
| `tokens.txt` | 1627 | `72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e` |

- 来源是**我们自己的 Release**: `yuloong07-star/Luwi` 的标签 `models-kws-2024-01-01`, 四个文件都
  在那一版里。字节数与 sha256 都是 GitHub 自己算的那份 (`assets[].digest`), 与本地文件逐个核过。
- **镜像优先**: `https://ghfast.top/https://github.com/...`。这台手机上 `github.com` 直连只回 302, 真正
  的字节在 `objects.githubusercontent.com` 那一跳上, 出不去, 而镜像那一层实测 200 且逐字节一致。
- **为什么不从上游取**: ModelScope 与 sherpa-onnx 自己 Release 里同名的文件是**另外几次构建**, 字节数
  差几百个、sha256 自然不同 (2026-10-05 逐文件比过)。既然手机上跑通的是我们自己这一份, 清单、下载与
  运行时就要对同一份文件说话。
- **装完清掉另一套**: 目录里两套并存时 `wakeWordModelOf` 按 `.int8.` 优先取, 于是"取到哪一套"变成
  "谁先下的算谁的"。所以两边 (插件的 `wakeWordPrune` 与 app 的 `prune`) 都会把不属于这张表的 `.onnx`
  删掉。
- 这份表有**两处实现**: `host-plugin/index.mjs` 的 `WAKEWORD_FILES` 与 `WakeWordDownload.files`。宿主
  那条是给模型用的 (`lw_wakeword op=prepare`), app 那条是给设置页那个按钮用的 (app 里没有一条"叫一次
  宿主工具"的路 —— 回环通道是 host → app 方向的)。改一边就要改另一边。
- 模型不进 APK, 也不进仓库。

## 三、词表那一份算法也有两处实现, 而它们不许漂

`词=拼音` 那套拆法 (声母先长后短、调号落在哪个元音、`v` 与 `u:` 归一到 `ü`) 同样是**两份实现**:

| 在哪 | 谁走这条路 |
| :-- | :-- |
| `wake/WakeWordWords.kt` | 设置页那个编辑框 (`LwWakeWord.setWords`) |
| `host-plugin/index.mjs` 的 `wakeWordLine` + `markedSyllable` + `toneIndex` | 模型那条路 `lw_wakeword op=keywords` |

源码里那句"改了一边记得看另一边"是口头纪律, 而口头纪律会漂。所以有一条可执行的检查:

```
node tools/check-wake-words.mjs      # 不需要设备, 也不需要 host
```

它做四件事, 而第 1 件是它存在的理由:

1. **两份实现跑同一张判据表, 逐行比对**。表是直接从 `WakeWordWordsTest.kt` 里读的 —— 那边已经由单元
   测试钉住、而且对过模型自带的例子, 所以**不抄第二遍**(抄一遍就是第三份实现)
2. **声母表与调号表逐项比对** (顺序也要一样: 长的在前, 否则 `zh` 会被拆成 `z` + `h`)
3. **符号表取"期望值并集再少一个"**, 于是"表里没有就抛"那一关是真的在考 —— 拿实际输出当表会让它空转
4. **读到的判据条数必须与 Kotlin 那份写的条数对上**: 一条没被跑的判据比一条红的判据危险 (红的那条
   会被人看见)

这份检查是**自己验过会红的**: 故意把一条期望值改坏 (`ch ī` → `ch i`) 之后它立刻报出来并印出两边,
还原之后又绿 —— 一条没见过它红的判据可能是死代码。

写这两份判据时顺带核清了三处容易看错的地方 (以实测为准, 不是读代码猜的):

- **`er2` 是 `ér` 而不是 `er`**: 零声母的音节整个当一个 token, 但那个 token 本身带调号
- **`nv3` 与 `nü3` 得到同一行 `n ǚ`**: `v` 在拆声母**之前**就变成了 `ü`, 所以 `v` 不会单独成为一个
  token (`INITIALS` 里那个 `v` 实际上永远匹配不到 —— 留着无害, 但别指望它)
- **JS 的 `\w` 不认 `ü`**: 用 `\w` 去读那张调号表的键会安静地少读一行 (`ü` 那一行), 于是判据变成
  "五行的表与六行的表相等"然后红 —— 读源码的脚本要按字符类写, 别用 `\w`

## 四、关键词为什么要写成 token

sherpa-onnx 的 keywords 文件每一行是**模型的 token 序列**加一个 `@显示名`, 例如模型自带的那份:

```
n ǐ h ǎo j ūn g ē @你好军哥
x iǎo ài t óng x ué @小爱同学
```

中文原文写进去**不会报错**, 只是那一行被静默丢掉 (`EncodeKeywords` 只认 `tokens.txt` 里的符号, 一个
不认就整行作废)。那等于「照做了, 但永远不触发」, 所以写词表之前先对一遍符号表: 宿主侧把「词=拼音」
拆成声母加带调韵母逐个核对, app 侧 (设置页) 走的是同一套算法的另一份实现。

缺省那一张词表是四条 (第一条是本体, 后面三条是容错读音, 四条同名; 设置页与通知念的是去重之后的
显示名, 所以还是只念一个「肥鱼肥鱼」):

```
肥鱼肥鱼=fei2 yu2 fei2 yu2     ->     f éi y ú f éi y ú @肥鱼肥鱼
肥鱼肥鱼=hui2 yu2 hui2 yu2     ->     h uí y ú h uí y ú @肥鱼肥鱼
肥鱼肥鱼=fei2 yi2 fei2 yi2     ->     f éi y í f éi y í @肥鱼肥鱼
肥鱼肥鱼=hui2 yi2 hui2 yi2     ->     h uí y í h uí y í @肥鱼肥鱼
```

用法 (`op=keywords`):

```
lw_wakeword op=keywords words=["肥鱼肥鱼=fei2 yu2 fei2 yu2","小爱同学=xiao3 ai4 tong2 xue2"]
```

拼音写声调数字 (`da4`) 或直接写带调号的 (`dà`) 都行, 声调落在哪个元音上按普通话的规则 (a/o/e 优先,
`iu` 落 u, `ui` 落 i, 其余落最后一个元音)。这个拆法不是猜的: 拿同样的函数跑模型自带的例子逐字节对上
了上游那几行 —— `你好军哥 → n ǐ h ǎo j ūn g ē`、`小爱同学 → x iǎo ài t óng x ué`、`周望军 → zh ōu w
àng j ūn`、`女儿 → n ǚ ér`。

## 五、听着的时候是什么样

- 一个前台服务 (`foregroundServiceType="microphone|specialUse"`), 16 kHz 单声道, 每 100 ms 一段送进
  识别器, `isReady` 为真就解码, 命中即复位。
- **服务里是两层, 不是一层**: 唤醒词那一路一直在守 (低功耗守门人), 而识别那一路 (silero VAD
  切段 + SenseVoice 出字) **只在要听一句话的时候才铺开** —— 唤醒词命中一次、或者球上点一下。两条
  路都**用完就收**: 静 10 秒没有新的活动就收回去 (`VOICE_IDLE_MS`, 活动指**出了一句字**或者
  **VAD 说这一窗有人声** —— 一口气不停顿地说下去不会被从句子中间收回)。**唯一的例外是视频模式**:
  那个模式要求这一路留着 ([LwModes] 的 `resident`, 记号 `modes/voice-resident.on`), 因为它是"看着
  东西连着说话"的模式; 退出视频模式时收回。**设置页那个「允许常驻语音」开关与浮标菜单那行「一直
  听」在 2026-10-06 删掉了** —— 那两条路能在手机模式下把这一路打开, 结果就是对话一直进行下去
  (主人判定多余的是那两个入口)。分工与理由见 `docs/wake-voice-states.md`
- **视频模式里说的话投给哪一场: 进去那一刻定格的** (2026-10-07 主人点名: "说「打开视频模式」那句话
  落的会话, 与视频模式里接着说进的话不是同一场, 想统一到同一场")。页面那头一秒一次把
  `localStorage['dsh.sessions.current']` 里那个 id 经桥报给 app (`HostScreen` 注入脚本 →
  `WakeBridge.session` → `VoiceState.uiSession`), 而 `LwWakeWord.resident(on)` (切模式三条路 +
  `wakeword op=voice` 的唯一入口) 把它**定格**写进 `modes/video-voice.session`; 出视频模式时删掉。
  服务那一侧由 `VoiceRoute.target` 挑: **定格优先** (视频模式里每一句都算), 其次才是回复框点名那一场,
  都没有就按 10 分钟账本走。`lw_wakeword op=status` 的 `videoTarget` / `uiSession` 两个数就是它的判据
- 通知栏留一条常驻, 文字随状态走, 而且**按两条链分别说**: 只有唤醒词在守时就是"正在听「…」"
  (2026-10-06 主人定的文案; 词表里几条容错读音同名, 显示名去重之后只念一个), 识别那一路在跑时说
  "正在听「…」· 听着你说这一句"(视频模式常驻那一档说"常驻语音在跑"), 正在念回答那一小段说
  "「…」· 正在说话, 先不听" (半双工那道闸是合着的, 那时叫不醒)。上面一个「停止」, 关的是整件事。
  **一直开着的麦克风必须有一眼看得见、一下就关得掉的地方**, 这是那一条通知存在的理由。
- 命中之后的七件事: 震动 (默认 500 ms)、一声短提示音 (120 ms 的 `TONE_PROP_BEEP`, 它占住半双工那
  一小段所以不会被录进去)、更新通知、**为这一句话开门** ([openForOneSentence] → [openVoice], 视频
  模式那一次会留着)、预热识别器、把"唤醒窗口"打开
  (随后头一句带上"这是唤醒头句"的记号 —— 投给哪一场由宿主那侧按时间那一笔账定 (2026-10-07 起
  20 分钟, 2026-10-11 起 10 分钟), 见
  `docs/floating-input.md`; **回复框在屏上时例外**: 那一句随框投给"发出那条回复的会话", 取的是
  `OverlayState.replyTarget()`; **视频模式里另有它自己那一条**: 进场时定格的那一场压过上面两条,
  见本节上面那条), 以及唤起 —— `onWake=overlay` (**2026-10-05 起的缺省**) **只唤醒浮标
  那颗球的语音输入** (见 `docs/floating-input.md`); `onWake=app` 把应用提到前面。
  **2026-10-05 收窄过的那一条**: 浮标那一路以前发的是 `ACTION_SHOW` (只把球/页面放出来), 现在是
  **还是 `ACTION_SHOW`** —— 中间试过 `ACTION_LISTEN`, 那是错的: 球上 `ACTION_LISTEN` 是一个开关
  ("正听着再点一下就收回来"), 而命中时那一句话的窗口**刚刚由 `openForOneSentence` 开好** —— 再发
  一次就把刚开的那一次收掉, 于是头一句话谁都听不见。所以命中只做"把球放出来"这一件事, 而球出来
  时球上那三个字正是"正在听" (状态词读 `VoiceState.capturing`)。它**不把 `MainActivity` 提到
  前台**: 唤醒是"我要说一句话", 不是"我要看界面" (想回到界面那条路一直在: 输入条上那个按钮、通知栏,
  以及**双击回复框**—— 它 2026-10-07 起替掉了长按菜单里那一行「回应用」, 顺带会把会话界面带到那一场
  对话上, 见 `docs/floating-input.md` 那一节)。
  球上点一下与唤醒词命中走的是**同一个方法** (`listenNow` / `openForOneSentence` 两条都在
  `WakeWordService` 那一侧), 所以"唤醒词叫起来的语音输入"与"球上点出来的语音输入"是同一份实现、
  同一个 10 s 闲置超时、同一个手动关闭 (`ACTION_HUSH`, 2026-10-06 从 `ACTION_STOP_VOICE` 改名 ——
  它收的从来不是"常驻那一半", 而是主人此刻在说的那一句)。
  **退回应用那一档仍然在, 而且它才是真判据**: 服务那一侧要同时满足三件事才走浮标 (浮标那个开关
  存着、悬浮窗授权在、host 在跑), 有一件不成立就退回应用 —— 没有浮标的人照旧得到"把界面叫起来"
  这唯一做得到的事, 所以"缺悬浮窗授权"那一条不会变成一次什么都不发生的唤醒。工具那条路
  (`lw_wakeword op=start`) 仍可以点名 `onWake: "app"` 覆盖缺省
- 参数 (**2026-10-09 调过两轮, 唯一的一份表在 `wake/WakeTuning.kt`**): `threshold` (默认 **0.01**, 旧值
  0.25 —— 越低越容易触发、误报越多)、`score` (**3.0**)、`numTrailingBlanks` (**1**)、`maxActivePaths`
  (**16**)、`vibrateMs` (默认 500, 0 就是不振)。四个 KWS 参数都能在 `lw_wakeword op=start` 上点名覆盖,
  `op=status` 报的是**正在跑的那一份** (不是缺省表)
- **`numTrailingBlanks` 越大越难唤醒, 这一条最容易搞反**: 它说的不是"确认几帧", 而是 sherpa 的解码器
  要求关键词 tokens 之后**还要出现这么多帧空白**才肯收 (upstream `transducer-keyword-decoder.cc` 里
  `num_trailing_blanks > num_trailing_blanks_`, 一帧 subsampling 之后是 40 ms)。第一轮按"连续 2-3 帧"
  把它抬到 3, 等于要求喊完之后有 160 ms 静音 —— 而"肥鱼肥鱼 + 紧接一句指令"中间根本没有那个空档,
  于是**那一句永远唤不醒** (主人 2026-10-09 第二轮报的"还是难唤醒"就有这一条)。现在回到 1 (要 80 ms),
  多出来的抖动交给冷却窗压
- **`op=status` 的 `hits` / `suppressed` 是调这两个数的仪表**: `hits` 低而你怎么喊都不动, 先看
  `threshold` 是不是被谁覆盖了、再看省电模式; `suppressed` 涨得快说明冷却窗吃掉了太多真实唤醒
  (那就要把 `HIT_COOLDOWN_MS` 或 `COMMAND_TAIL_MAX_MS` 收小)
- **命中之后有一段去抖窗** (同一天加的): 阈值放开之后"同一个词连着报两遍"与"指令前半段被当成下一次
  唤醒"都会变多, 所以命中之后 KWS 照样解码但**结果一律不算**, 直到这一句话的第一段出字投递 (下限
  `HIT_COOLDOWN_MS` 1.5 s, 上限 `COMMAND_TAIL_MAX_MS` 5 s); 窗里被吃掉的次数记在
  `op=status` 的 `suppressed` 上。判据是纯函数 `wake/WakeDecision.kt`
- **分数 EMA 没做, 也做不了**: 钉的这份 sherpa-onnx AAR (1.13.8, upstream 最新也是) 里
  `KeywordSpotterResult` 只有 keyword / tokens / timestamps, **不吐任何分数** (上游源码里就是
  `// TODO: Add more fields`)。所以"对分数做 EMA"拿不到输入值; 能做的那一半就是 `numTrailingBlanks=3`
  这个模型内建的帧确认, 加上上面那层判定去抖。要真做 EMA 得自己重编 AAR 把 token 概率暴露出来
- **一句话什么时候算说完由 silero VAD 那个静音窗定**: `SpeechSegmenter.MIN_SILENCE_SECONDS` 现在是
  **0.8 s** (2026-10-05 从 3.0 s 收下来的, 主人反馈"说话结束到发送等得太久")。它同时定两件事: 等够
  它才把段交出去, 而段尾就落在"最后一段人声 + 它" —— 所以它多长, 主人就多等多久。说话中间停顿长的
  人会被切成两段, 真机上真被切了就把它调回 1.0-1.2 s (那一处改一行)
- **投出去一句之后窗口就进入"短尾巴"那一档** (2026-10-09 主人: "说完后发送问题, 正在听状态没有结束,
  应该结束掉才对" + "要看还在不在说"): 还没说到一句时是"闲置 10 s + 想/念期间挂起"; 一句出字**投出去
  之后**上限换成 `WakeTuning.SENT_TAIL_MS` (0.3 s) 且不再挂起, 而**人声照样续期** —— 还在说就继续听,
  一停下来就收 (看门狗节拍 500 ms, 所以实际是 0.3-0.8 s)。代价写在 `WakeTuning` 那段注释里: 一句话
  中间停顿超过 0.8 s 被切成两段时, 后半句要重新喊一次唤醒词
- 同一批里另两条与延迟有关的改动: 命中那一下另起线程**预热识别器** (`LwSpeech.warmUp`, 把 240 MB
  的加载挪到主人还在说话的那几秒里), 以及宿主侧读队列的间隔从 500 ms 收到 **150 ms**
  (`VOICE_POLL_MS`)。三处加起来大约把"说完到发出去"从 3-4 秒压到 1 秒出头

## 六、设置页那一段 (「唤醒词」)

六行, 各自说一件事。**现在只剩一个许可** (2026-10-06 起, 细节见 `docs/wake-voice-states.md`),
而 2026-10-07 又多了"省电模式"那两行:

| 行 | 是什么 |
|---|---|
| 状态文字 | 三种组合分开说: 只有唤醒词在守 / 识别那一路也在跑 (视频模式常驻那一档) / 许可开着而服务没起; 另加命中过几次、麦克风权限缺没缺、上一次出的问题 |
| 下载唤醒词模型 | 缺哪个下哪个 (逐文件 sha256, 界面上一秒刷一次进度), 下完补上缺省词表。**只在「允许唤醒」开着时才顺手起监听**, 而且起的只是唤醒词那一路 |
| 唤醒词 | 弹出的编辑框里写 `词=带音调数字拼音`, 一行一个词。**逐 token 核对后才写**, 对不上就把是哪一个 token 说出来 |
| **允许唤醒** | 允不允许这个应用一直听着唤醒词 (缺省**开**)。它只决定服务起不起来 —— 这是"设置项只作前置许可"的全部 |
| **省电模式** | 主人 2026-10-07: "只停唤醒词监听 (麦克风整个关掉, 喊不醒), host、浮标、通知都留着; 点球照样能说一句话"。它是**此刻的开关**, 不动上面那个许可 —— 关掉之后原有的许可照旧生效 |
| **省电时段** | 定时那一半 ("由用户自己定时间"): 一行 `23:00-07:00` 这种, 到点自己进省电模式, 出了时段自己回来。跨零点认, 起止相同 = 空窗 (永不省电), 看不懂的写法当场拒绝 |

**省电模式落在哪**: 服务**不跟着停** (定时那一段走完要有人把麦克风打开, 而"到点了"只有活着的服务
知道), 停的只是采集与唤醒词那一路。于是通知栏那条常驻照旧在, 只是写着"省电模式 · 麦克风关着 ·
点球还能说一句"; 点球那一下会把麦克风**临时借过来**说一句, 说完 (闲置 10 s 或手动收) 由
`WakeWordService.closeVoice` 还回去。视频模式那个"把那一路留着"的记号在省电模式里**不作数**
(出了时段照旧恢复)。

两个来源合成一个判据 (`LwWakeWord.powerSave`): 手动那个开关是设置页当场发 `ACTION_REFRESH` 过去的
(立刻生效); 定时那一段靠服务里那条 15 s 的观察者 (`WakeWordService.startPowerWatch`) —— 一次 tick
只是读两个偏好加一次时刻比较。解析与"此刻在不在里面"是 `wake/PowerWindow.kt` 那一份纯算术
(`PowerWindowTest`), 跨零点与空窗那两条口径都在那里。

**「允许常驻语音」那一行删掉了** (2026-10-06): 开着它等于识别链一直不收, 而它在手机模式下也能被
打开 —— 主人判定多余的就是这个。现在**只有视频模式能要求那一留** (`LwModes.set` → `LwWakeWord.
resident`), 所以设置页没有开关可给, 但状态那一行照旧如实说。改动之前这里只有一个开关「一直听着」,
而它直接等于常驻监听 (`checked = WakeWordState.listening`): 一打开, 麦克风、silero VAD 与那
240 MB 的识别模型全跟着起来。现在这个许可**只写偏好、什么都不启动** (除了"允许唤醒"打开时把唤醒词
那一路起起来), 识别那一路只在**命中唤醒词 / 球上点一下 / 视频模式**之后才铺开, 前两条静 10 秒自动
收回去。

缺权限或缺模型时不替人按下那个 5 MB 的下载, 只把原因说出来。改完词表或换完模型, 正在跑的那一份
必须重启才会读新的, 所以这两条路都会"停一下再起"。

**许可是存盘的**, 所以重启应用时 `MainActivity.onCreate` 会照着它把监听恢复起来 (`LwWakeWord.ensure`)
—— 否则"允许唤醒"只是个记号, 服务不会自己起。通知栏那个「停止」关的是**整件事** (服务停掉),
会话界面那个胶囊关的是**这一句话的窗口** (`ACTION_HUSH`, 2026-10-06 从 `ACTION_STOP_VOICE` 改名),
唤醒词接着守。

## 七、输入框旁边那个麦克风

- **识别那一路在跑的时候才出来**: 输入框上沿一个胶囊, 里面五根跳动的绿柱加一个「正在听」, 点一下就是
  把**这一句话的窗口**关掉 (`ACTION_HUSH`), 关了就被收回, 而唤醒词继续守着 —— 想再要一次
  "开口说话", 喊一声就回来了 (视频模式那档也一样, 再点球或者再喊一声就又开)。整件事收工是通知栏
  那个「停止」。**纯唤醒词守着时它不出现**: 那是常驻
  状态, 一直挂着只会让人以为麦克风在被吃 (桥给页面的是 `voice`, 不是 `listening`)
- 实现是 `addJavascriptInterface(WakeBridge, "Luwi")` 加一段每次 `onPageFinished` 注入的 JS。
  **不是 dsh 的 client 插件加一条私有路由**: 那个指示器要的状态 (服务在不在听、命中几次) 只有 app
  这一侧知道, 而点它要停的也是 app 里那个前台服务 —— 插件跑在浏览器 JS 里两个都拿不到, 还得再连一
  条回 app 的通道。桥与路由的信任边界是一样的: 这个 WebView 只加载本机 host 那一个页面, 而暴露出去
  的两个方法都没有参数。
- 位置是**算出来的**: 找到页面里的 `textarea` / `contenteditable`, 贴在它上沿的左上角; 找不到就退回
  右下角一个固定位置。所以不碰输入框自己的控件, 也不依赖 dsh 的类名。
- 样式一律用 CSSOM (`element.style.x = ...`) 与 JS 计时器, **不插样式表也不插 keyframes**: 页面哪天
  带上 `style-src` 的 CSP 时内联样式表会被挡掉, 这样写不受影响。波形每 220 ms 跳一次, 而问 app 是
  一秒一次 (它那侧要读一次词表与四个文件的大小)。

## 八、判它成了没有

```
lw_wakeword op=status     # 模型在不在、词表是什么、权限、监听状态、命中几次、上一次听到什么
lw_wakeword op=prepare    # 取模型 (逐个对 sha256) + 清掉另一套 + 写缺省词表
lw_wakeword op=keywords   # 换词表
lw_wakeword op=start      # 开始听
lw_wakeword op=stop       # 停止
```

`op=status` 的 `hits` / `lastKeyword` / `lastHitAt` 就是"有没有听到"的证据; 通知栏那条常驻也能一眼
看出服务在不在跑; 设置页那一段与输入框上沿那个胶囊是给人看的同一件事。

### 出 APK 之前就能跑的两条 (不需要设备)

词表那一半最阴的失败面是"**词表存下来了、界面也说保存好了, 而那个词永远不触发**", 所以它有两条检查,
一条钉正确性、一条钉两份实现不许漂:

```
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.yuloong07star.luwi.wake.WakeWordWordsTest"
node tools/check-wake-words.mjs
```

第一条是 15 对判据 (含模型自带的那几个例子) 加"对不上的 token 必须抛且点名是哪一个"。它的符号表是
**从期望值推出来的**而不是手抄的 —— 手抄一张表会把"抄漏一个韵母"误报成代码错 (确实踩过: 漏了 `ōu` /
`éi` / `iǎo` / `ér`, 八条判据一起红而代码一行没错)。

第二条把第三节那张"两份实现"的表变成可执行的, 而且**自己验过会红**: 故意把一条期望值改坏
(`ch ī` → `ch i`) 之后它立刻报出来并印出两边, 还原之后又绿。

真机上验过的 (2026-10-05, vivo V2417A `10CEB40568000ZB`, Android 16):

- **模型那一趟是真从手机上走通的**: `op=prepare` 从镜像下到 `encoder…int8.onnx` (4 777 666 B) 与
  `joiner…int8.onnx` (65 242 B), 两个 sha256 都对, 同一次里删掉了目录里那两个非 int8 的 `.onnx`, 之后
  `op=status` 报"4 个文件都在"且字节数一个不差。
- **词表写入也过了符号表那一关**: `大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2` 写成了 `d à f éi y ú d à
  f éi y ú @大肥鱼大肥鱼`。
- 剩下要人自己试的: 喊一句触发一次 (命中会震一下、响一声、通知栏改字, 随后那句话落在浮标那一场
  对话里 —— 10 分钟内复用, 过了才新开一场 —— 并被念出来), 以及设置页那五行的手感。

## 九、已知边界与风险

- **"喊不醒"的第一件事是看省电模式**: 省电模式生效时**麦克风整个关着** (服务、通知、浮标都还在),
  喊什么都没有用 —— 通知栏那条会写"省电模式 · 麦克风关着 · 点球还能说一句", `lw_wakeword op=status`
  里则有 `powerSave` / `powerSaveManual` / `powerWindow` / `powerWindowActive` 四个数。2026-10-09
  真机上那一轮"还是难唤醒"里就是它: 日志里 `power save is on: the microphone stays closed`, 而主人
  在设置页关掉之后同一份代码立刻能唤醒了 —— 排查顺序因此是 **省电 → 省电时段 → 权限/模型 → 参数**
- **误触发看词的长短**。「肥鱼肥鱼」是四个音节, 比两字词稳得多 (两字词与同音的日常词太近), 但阈值
  仍值得真机调。三到六字的词都建议先实测一遍 `threshold` / `score`。缺省那张表另外带了三条容错读音
  (声母 f / h 与韵母 ü / i 两处口音合并), 词条多了误报面也跟着大一点 —— 先用 `op=status` 的
  `hits` / `lastKeyword` 看哪几条真的用得上, 再决定留几条
- **阈值 0.01 这一档故意放得很开** (2026-10-09 主人按"漏唤醒太多"给的): 它压的是"模型分够了而
  后处理太硬"那一种漏检, 代价是**孤立误触发不会被冷却窗吃掉** (冷却只管"连着来"的那一类)。真机上
  回调时先看 `op=status` 的 `hits` 与 `suppressed` 两个数: 前者高得离谱而后者不高, 就说明该把
  `threshold` 往回抬一点, 或者把词表里那三条容错读音收掉几条 —— 四个值都能在 `op=start` 上单独覆盖,
  不必改包
- **后台起麦克风前台服务是受限的** (Android 14 起): 带 `microphone` 类型从后台启动需要豁免, 持有
  `SYSTEM_ALERT_WINDOW` 是官方豁免之一, 但不是所有 ROM 都认。被拒时退到 `specialUse` 并把这件事记在
  `status.microphoneForeground` 上 —— **前台时照常, 退到后台可能就听不到了**。稳妥的用法是把设置页的
  「允许唤醒」打开之后让应用在前台起一次 (应用启动时会自己 `ensure` 一次)。
- **电与热**: 唤醒词那一路一直开着的代价是"麦克风与一份 3.3M 的模型"; 真正的耗电大头是识别那一路
  (240 MB 的识别器 + VAD), 而它只在命中/点球买来的那一句里开着 (静 10 秒收回), 或者视频模式要求它
  留着的时候。不想听唤醒词就把「允许唤醒」关掉 (那一个关掉之后视频模式也开不了识别链 —— 它长在唤醒
  词那一路的采集中间)。
- **静默失败只有一处**: 中文原文、错音的 token 会整行被丢, 所以词表写入前必须过符号表那一关 (两边都
  在做)。
- **换词之后不重启等于没换**: 识别器是启动时读的词表, 所以设置页改词、换模型都会自己重启监听。
- **新开的那一场对话 (小时过了那一次) 不会把界面切过去**: 会话是在宿主那侧 `sessionController.create`
  建的, 而"在看哪一个"是浏览器自己的路由状态 —— 回答会念出来, 但人可能正看着另一个会话 (主人选的
  "改动最小"那一档)。
- **`allowVoice` 与 `voiceAllowed` 会短暂不一致**: 前者是设置页存的, 后者是服务读到的; 改完许可要发
  一条 `ACTION_REFRESH` 才同步, 所以 `op=status` 把两个都报出来。

## 十、叫醒之后做什么 + 那两句命令（批次 4.4 / 4.5，2026-10-05）

### 「叫醒之后」是一个设置，不是一个写死的动作

命中那一下原本写死三件（震动 / 通知 / 唤起）。现在设置页那一段多了两行：

| 行 | 是什么 |
|---|---|
| **叫醒之后** | 只叫醒（缺省，与改动之前一致）/ 顺带切到视频模式 / 顺带切回手机模式 |
| **叫醒时震动** | 命中那一下震 500 ms；关掉只剩那一声短提示音（界面照旧叫起来） |

后两条**不在服务里切模式**：`WakeWordService.afterHit()` 只往收件箱写一句规范命令
（`VoiceCommands.VIDEO` / `VoiceCommands.PHONE`），由宿主插件那张表去认并执行 —— 命令词表只有
一份。插件按 `seq` 顺序读，所以这条命令一定比主人随后说的那句话先落地：切模式发生在投递之前。

改完这两个值会发一条 `ACTION_REFRESH`，正在跑的服务立刻读新的（不必重启监听）。

### 命令词表在宿主那一侧

主人说的哪几句话是**命令**、哪几句是"要投进会话的话"，由 `host-plugin/index.mjs` 的
`VOICE_COMMANDS` 一张表说了算（可行性稿 2.7：改词表不必重下关键词表、也不必重建 APK）：

| 模式 | 说法（整句归一化之后相等才算） |
|---|---|
| `video` | 打开视频模式 / 进入视频模式 / 切到视频模式 / 换成视频模式 / 视频模式 |
| `phone` | 回到手机模式 / 退出视频模式 / 关闭视频模式 / 关掉视频模式 / 手机模式 / 退出识屏模式 / 关闭识屏模式 / 关掉识屏模式 |
| `interrupt`（**不是模式**） | 打断当前回答 / 打断这一轮 |

- **识屏那一支整个没了**（2.5.0 批次 4）：模式摘掉了，而"退出 / 关闭 / 关掉识屏模式"这三句**留在
  `phone` 那一支**当兼容说法（说出来仍然等于收工回手机模式）；"打开识屏模式"不再被认成命令 —— 它照
  普通一句话进会话，由手机模式自己去看那块屏
- **归一化只吃空白与标点**（说出来的句子末尾会带句号），还认大小写；其余一个字都不许差
- **整句相等，不做包含匹配**：`"视频模式怎么改"` 是一句要进会话的话，表认错了人主人就会发现自己的
  问题没了。这一条有 `tools/check-voice-commands.mjs` 里的反例钉着
- 命中之后**不进会话**（既不插正在跑的那一轮，也不开新对话，更不理会 `wake` 记号）：它是对这台手机
  说的，不是对助手说的一句话。`lw_voice op=inbox` 把"投递了几条"与"吃掉了几条命令"分开报，
  `op=say` 遇到命令句会直接说"这是一句命令，切成了没成"
- 表里认不出来的说法照旧当普通一句话投进会话 —— 宁可多一句对话，也不要因为"猜它想切模式"而吃掉
  主人真正说的一句
- **`interrupt` 那一支是 2026-10-06 加的**（主人："正在想"状态下双击球打断状态和对话）：它不是切
  模式，做的事是**取消浮标那一场正在跑的轮**（`runVoiceInterrupt`：`agent.cancel({kind:'user'},
  {keepInbox:true})`，与界面那个停止键同一条）并把球上那三个字推回 `idle`。范围**只有浮标那一场**
  （`voice/session.json` 记的那一场）—— 别的会话里正在跑的那一轮不动，只把字落下。写这一句的人是
  应用那一侧（`OverlayService.interruptBall`），而因为它走的是同一张表，**张嘴说"打断当前回答"也
  一样管用**；"那一场没在跑"是一个正常结果（照样把字落下），不是失败

### 三个触发口现在都在

`lw_mode`（模型自己调）/ 唤醒词命中（`onHit`）/ 浮标菜单那两个模式项 —— 后两个**都走这一张表**，
所以不会有两份实现（`tools/check-voice-commands.mjs` 拿 Kotlin 那五个规范句子与插件那张表对着核，
其中最后那一个是打断）。
**一条命令 = 一次桥调用**：提示词、摄像头、常驻语音三件事都在应用那一次 `mode` 里做完，而设备上那三个
脚本（`modes/{phone,video,screen}.sh`）各自就是同一个切换的另一扇门 —— 切模式只跑对应的那一个。

## 十一、这一块改了什么

```
host-plugin/index.mjs                             lw_wakeword 工具、模型清单 (重钉到自建 Release)、镜像优先、装完清另一套; voiceDeliver 按 voice/session.json 那笔账挑对话 (2026-10-06 起 wake 只是头句记号, 不再另开一场; 2026-10-07 起那笔账是 20 分钟, 2026-10-11 起是 10 分钟); VOICE_COMMANDS 那张命令表 + applyMode (与 lw_mode 共用, 切模式时相机与常驻语音两件一起做); startBallPhase 推"正在想"; chooseVoicePreset 开新会话前把预设解析出来并逐级回退 (2026-10-09)
app/src/main/java/.../wake/WakeWordService.kt     前台服务、两层 (唤醒词一直守 / 识别链按命中·点球·视频模式开)、命中之后那几件事、闲置超时、半双工闸、ACTION_LISTEN_NOW (浮标点一下)、ACTION_HUSH (收回这一句) 与 afterHit (叫醒之后那两句命令)
app/src/main/java/.../wake/WakeTuning.kt          四个 KWS 参数与两个窗的**唯一一份表** (2026-10-09: 0.01 / 2.0 / 3 / 8 + 冷却 1.5 s + 命令尾上限 5 s), 替掉两处重复的 DEFAULT_THRESHOLD / DEFAULT_SCORE
app/src/main/java/.../wake/WakeDecision.kt        命中之后的去抖纯函数 (冷却判定 / 命中立窗 / 第一段出字收窗), 判据在 WakeDecisionTest
app/src/main/java/.../host/CustomPresets.kt       内置 dsh-custom-mode 与三份预设声明的首启安装 (耐久副本 / profile 合并 / 行 id 去重), 判据在 CustomPresetsTest
presets/custom-mode/                              vendored 的 dsh-custom-mode@2.0.1 (MIT) + 它怎么进设备的那份说明
presets/{mobile-use,video}/cordis.patch.yml       随包进 profile 的那两段声明 (正文就是原本那两份 preset)
app/src/main/java/.../automation/AutomationRule.kt 自动指令的冷却档位与 withCooldown (主人自己定冷却那一条)
app/src/main/java/.../automation/AutomationStore.kt setCooldown: 不经过模型、只换 cooldownMinutes 一个键
tools/check-custom-preset.mjs                     内置预设有四份东西不许漂 (包 / 助手文件 / 三段声明 / 打包那一步)
app/src/main/java/.../voice/SpeechSegmenter.kt    silero VAD 切段、abandon() 丢半句话、静音窗 0.8 s
app/src/main/java/.../voice/VoiceState.kt         capturing = "这一句话的窗口开着" (视频模式常驻那一档也在里面)
app/src/main/java/.../voice/VoiceInbox.kt         投递队列, 头一句带 wake 记号
app/src/main/java/.../voice/VoiceCommands.kt      三条规范命令句 (与插件那张表对着核)
app/src/main/java/.../wake/WakeWordModel.kt       按前缀找模型、符号表核对
app/src/main/java/.../wake/WakeWordDownload.kt    设置页那个按钮背后的下载 (逐文件 sha256 + 进度 + 清理)
app/src/main/java/.../wake/WakeWordWords.kt       「词=带音调数字拼音」转 token 行
app/src/main/java/.../tool/LwSpeech.kt            端侧识别; warmUp() 只加载不出字 (命中时预热)
app/src/main/java/.../tool/LwWakeWord.kt          通道方法 wakeword 的五个动作 (status / keywords / start / stop / voice); 一个许可 + onHit / vibrate; speakNow (浮标点一下那条路); resident (常驻那一半的唯一开关) 与 residentWanted (记号)
app/src/main/java/.../channel/LwModes.kt          切模式那一次调用: 两份正文 (手机 / 视频) + resident + 相机的开与收都在它里面, 一次桥调用做完; 还负责把老机器上退役的 `screen` 记号迁回手机模式
app/src/main/java/.../channel/PrivilegedBridge.kt 一行: "wakeword" 进方法表
app/src/main/java/.../ui/SettingsScreen.kt        「唤醒词」那一段 (状态 / 下载 / 改词 / 一个许可 / 叫醒之后 / 震动) 与「浮标」那一段
app/src/main/java/.../ui/HostScreen.kt            输入框上沿那个麦克风 (JS 桥 + 注入的脚本, 只在识别链跑时出现)
app/src/main/assets/modes/{phone,video}.sh        两个模式各自的**整个切换** (一条命令; 视频那份顺带把常驻语音留上)
app/src/main/java/.../MainActivity.kt             启动时照着许可把监听恢复起来 (LwWakeWord.ensure) 并按存盘开关放球 (LwOverlay.ensure)
app/src/main/AndroidManifest.xml                  一个前台服务声明
tools/check-voice-commands.mjs                    命令表两份实现不许漂 (9 条判据)
tools/lw-mode-voice-check.ps1                     切模式那两半在设备上量: 记号 / allowVoice / voiceActive / 三个脚本那条路
tools/lw-ball-hide-check.ps1                      「关掉浮标」三个入口在设备上量 (窗 / 服务 / 记号 / 不许被拉回来)
docs/wake-word.md                                 本文
```
