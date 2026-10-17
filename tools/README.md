# tools/ 里这些脚本

这份表是 `tools/` 的**唯一入口说明**: 哪个脚本干什么、在哪跑、要什么参数、产物落在哪。
新增脚本时照着下面那三条约定写, 然后跑一次 `node tools/check-tools.mjs` (它会替你检查命名与用法段)

## 三条约定

1. **命名**: 面向设备与工作流的用 `lw-<动作>.ps1|mjs|sh|py`; 静态校验的用 `check-<对象>.mjs|py`;
   构建产物类的用 `pack-` / `zip-` / `push-` / `gen-` / `make-` / `vector-` / `apk-` / `host-` 开头
2. **用法段**: 每个脚本开头都要有一段能看懂怎么调的字 (中文写 `用法:`, 英文写 `usage:`);
   PowerShell 的还要支持 `-Help` (打一遍用法就退出, 不干活)
3. **落位**: 日志进 `logs\build\` 或 `logs\device\`, 截图进 `shots\`, 临时产物进 `.lwtmp\`。
   **不许在 `D:\apk` 根下新开一个目录** —— 根目录只放工具链与入口 (见工作区 `AGENTS.md`)

## 一、静态校验 (不需要设备, 也不花模型的钱)

改动提交前该跑的那一批。它们量的都是"两份实现漂没漂"或"拼出来的东西能不能解析", 这一类错在真机上
的代价通常是一整轮返工

| 脚本 | 干什么 |
| :-- | :-- |
| `check-host-plugin.mjs` | 装进 host 树之前先问一句: 这个插件到底能注册出几个工具 (schema 在模块求值时编译, 一张不合规的就整包一起死) |
| `check-overlay-yaml.mjs` | 把 app 会写出来的那两份 YAML (命令行覆盖层 + profile patch 里 `PluginRows` 补的那一行) 解析一遍 (拼错了的代价是整台 host 起不来, 而 profile patch 里那一行的 `name:` 写成裸路径的代价是**设置面板永远存不进去**) |
| `check-read-aloud.mjs` | 念之前那次清洗: 直接拿 `host-plugin/index.mjs` 里的源码跑, 不复制一份出来 |
| `check-voice-commands.mjs` | 命令句词表的**两份实现不许漂开** (宿主插件那张表 vs `voice/VoiceCommands.kt`) |
| `check-auto-shot.mjs` | 自动识屏那一张图的判据表: 词表 (正例 / 反例) + 视频模式与设置那两道闸 + 图要进用户消息 |
| `check-lock-steps.mjs` | 锁屏那一条的判据表: 坐标一律比例 / 密码与图案不落明文也不进日志 / 失败三次停 / 点亮三条路都在 / 插件与应用的 op 名单一致 / **脚本动词表与 `docs/lock-script.md` 不许漂** |
| `check-quick-commands.mjs` | 快捷指令与技能那一条的判据表: `lw_calendar` / `lw_quick` 的 op 名单两侧一致 (删除只在桥上有) / 四份技能与两条样例都在 / 技能 frontmatter 合规 / `build.gradle.kts` 那张随包表与仓库里的文件对得上 |
| `check-image-edit.mjs` | p 图那一条的判据表: `lw_image` 的两个 op 与 `.mjs` / 桥两侧对得上 (ref 那条链与 `edit_image` 的 `source_image` 同一份形状) / 生图插件与 `photo-edit` 技能都在随包表里 / 那两处清单与 `PluginOverlay` 那一行三边一致 / 面板要能改的那一行不在覆盖层两张表里, 而且盘上写的是 `file://` 形态 |
| `check-automations.mjs` | 自动指令那一条的判据表: `lw_automation` 的 op 名单两侧一致 (删除只在桥上有) / 六个 `when.kind` 与两种 `then.kind` 在插件说明里逐个念到 / 冷却与上限那两个默认值两侧一致 / 两个来源记号 (`automation` 与 `automation-setup`) 都在且插件对前一个有那两处特别处理 / 设置页引用的字符串键两份 `strings.xml` 都有而且顺序一致 |
| `check-wake-words.mjs` | 唤醒词表算法的两份实现不许漂开 (`WakeWordWords.kt` vs 插件里的 `wakeWordLine`) |
| `check-voice-inbox.mjs` | 投递队列那三条真值: 第一次跑不倒历史 / 投递成功才前移 / 断线要留话 |
| `check-shot-params.mjs` | 取景与截图那几个数字的**唯一口径** (`docs/shot-params.md`) 与插件 / Kotlin / 提示词 / 技能 / 预置五处逐项对照 |
| `check-video-gate.mjs` | 视频模式那两道闸 (屏幕类工具一律拒 / 相机同时只许一场会话用) 的判据表, 临时 `DSH_HOME` 自己搭 |
| `check-explore.mjs` | 探索那一档的判据表 (2.7.8 批次 7): 预设那一段与 mobile-use 逐行相同 / persona 那三条红线与五节摘要 / 预算常数与 `exploreBudget` 真值表 / 工作区与预设回退 / 省电闸 / 数步只数不拒 / `lw_explore` 的工具面 / 摘要与候选卡的文件形状 (抽那几段纯函数真跑) |
| `check-bare-percent.py` | 扫 `strings.xml` 里的裸 `%` (`Resources.getString` 会把它当转换符, 主线程崩) |
| `check-tools.mjs` | 扫本目录自己: 命名 / 用法段 / 根目录落位 (就是本文那三条约定) |
| `gen-keycodes.mjs` | 从 Android 自己的头文件重新生成 `channel/KeyCodes.kt` 那张名字表 |

## 二、设备脚本 (要 adb, 一律收 `-Serial`)

设备解析顺序与 `dev.ps1` 一致: `-Serial` → `$env:ANDROID_SERIAL` → 唯一在线设备

| 脚本 | 干什么 |
| :-- | :-- |
| `lw-doctor.ps1` | **先跑这个**: 环境 + 设备 + 关键权限一次读完, 缺什么直接说去哪补 |
| `lw-install.ps1` | 装 APK 并顺手给权限 (`-i` 装机 + 无障碍与通知监听写回 + `-Perms` 批量授权) |
| `lw-bridge.ps1` | 跳过模型与插件, 直接往桥里发一次调用 (最快的一条通道验法) |
| `lw-device-turn.ps1` | 让模型在设备上真跑一轮 (走 `run-as`, 用应用自己的 host 树与 home) |
| `lw-device-prepare.mjs` | 在**手机上**把插件那一层跑一遍 (下载 / sha256 / 建屏 / 关屏) |
| `lw-channel-call.mjs` | 没有 root 时的那条: `run-as` 读宿主环境 + 端口 forward, 直接发一次通道调用 |
| `lw-channel-probe.sh` | 找出 host 进程环境里的通道地址与 token |
| `lw-device/host-env.sh` | 推上设备的取环境脚本 (宿主环境只有 root 读得到) |
| `lw-device/headless.sh` | 在设备上跑一轮无头 dsh turn |
| `lw-plugin-call.mjs` | 让插件那一层真的调一次桥 (`defineTool` → `execute`, 不花模型的钱) |
| `lw-session-log.mjs` | 读一份会话日志 (逐帧解 zstd 的 `session.v4.jsonl.zstd`) |
| `lw-fake-touch.sh` | 伪造一只真手指 (写 evdev): 触摸刹车删掉之后, 它的用处是反过来证明"按着手指主屏调用照样成功" |
| `lw-ball-pixels.py` | 在整屏截图里量那颗球 (窗口坐标说位置, **看得见多少只有像素说了算**) |

**浮标与模式那几组专项实测** (对应历次主人报的问题, 每一条都是一个可复算的判据)

| 脚本 | 量什么 |
| :-- | :-- |
| `lw-ball-check.ps1` | 半隐在左必须是负 x、在右必须越界, 唤回后必须回到停靠位 (`-NoDrag` 只读) |
| `lw-rotate-check.ps1` | **转屏**: 竖/横各转一轮, 球 / 输入条 / 文本框三块窗都必须还在屏里 (读的是 `overlay op=state` 那四个新读数) |
| `lw-ball-hide-check.ps1` | 「关掉浮标」那三个入口: 窗没了 / 服务停了 / 记号是 false / 通知没了, 四件事实一起对 |
| `lw-ball-tap-check.ps1` | 批次 5 那三条: 半隐失效 / 状态字落下的账 / 现场表尾部 |
| `lw-batch5-check.ps1` | 空闲 5 s 收边那个时刻 / 通道回执 / 通道像素宽 / 框外双击才收 / 回车发送 / 掐断播报 |
| `lw-mode-voice-check.ps1` | 进视频模式常驻语音真的在跑、切走真的收回去 (不是"回执说它开了") |
| `lw-lock-check.ps1` | **锁屏**: 熄屏点亮那三条路 / 录一遍再重放到桌面 / 注入不算真手指那条控制 / 一次失败与三次失败两条路 (`-FakeWalk` 只对模拟器: 它会造一个真锁屏) |

**相机链路专题** (作者自己在脚本头标了"临时"的那几个, 用完可清)

| 脚本 | 干什么 |
| :-- | :-- |
| `lw-camera-chain-test.ps1` | 新链路 (Camera2 直连) 与旧链路 (建屏 + 起相机 + 截屏) 在同一台设备上打表 |
| `lw-camera-failures.ps1` | 三类真实失败: 缺权限 / 应用在后台 / 相机被占 |
| `lw-camera-debug.sh` | 把设备侧那条 `ask` 的原始回包打出来 |

## 三、构建与产物

| 脚本 | 干什么 | 谁在调 |
| :-- | :-- | :-- |
| `pack-host.mjs` | 把 dsh 的 host 树真正落成 APK 能带的那一份 (平铺 `npm install`) | Gradle 的 `packHostTree` |
| `zip-host.mjs` | 把 host 树打成一个归档 + 一份版本戳 (app 首次启动解它) | Gradle |
| `push-host.mjs` | 直接推一份 host 树进 debug 应用的沙箱, 改一行不必重打 APK | 人 |
| `ocr/build-models.ps1` | 重建随包发的那三个 OCR 文件 (det / rec / 字典) | 人 |
| `ocr/rewrite_onnx.py` | 把 PaddleOCR 的导出改成 QNN 建得出来的样子 (钉死 shape) | 上面那个 |
| `ocr/extract_dict.py` | 从官方 `inference.yml` 里抽出识别器的字表 | 上面那个 |
| `make-icons.py` | 把 dsh 官方图标做成 Android 要的那几档位图 | 人 |
| `vector-ink.py` | 算矢量前景的墨迹框与该用的 scale/translate | 人 |
| `apk-bytes.py` | 找 APK 里没有被任何 zip 条目算进去的字节 (体积账) | 人 |
| `host-tree-size.py` | 按包把 host 树的条目归并 (要裁的时候得先有数) | 人 |

**这几个是构建链路的一部分, 改之前先看 `docs/host-build.md`**: `pack-host.mjs` / `zip-host.mjs`
被 Gradle 直接调用, 名字与参数都不能随手换

## 四、工作区

| 脚本 | 干什么 |
| :-- | :-- |
| `lw-api-push.ps1` | 用 GitHub API 把本地领先的提交送上远端 (`github.com:443` 被 SNI 挡, `api.github.com` 通); 每个对象都与本地 SHA 比对, 全对才动 ref |

## 五、怎么用

```powershell
# 环境与设备一次读完 (新会话第一件事)
pwsh -File D:\apk\Luwi\tools\lw-doctor.ps1 -Serial emulator-5554

# 改完插件或字符串之后, 提交前跑这一组 (不需要设备)
cd D:\apk\Luwi
node tools/check-host-plugin.mjs
node tools/check-voice-commands.mjs
node tools/check-auto-shot.mjs
node tools/check-wake-words.mjs
node tools/check-shot-params.mjs
node tools/check-video-gate.mjs
node tools/check-explore.mjs
python tools/check-bare-percent.py
node tools/check-tools.mjs
```

更常用的那几条 (编译、出包、装机、截图、logcat) 走工作区入口 `D:\apk\dev.ps1`, 见工作区 `AGENTS.md`
