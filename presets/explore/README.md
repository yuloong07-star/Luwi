# dsh-preset-explore（本机版「探索模式」预设）

一次探索 = 落在 `Luwi` 工作区的一场会话: 在宿主数的预算内（缺省 30 分钟 / 60 步）**只读地**自由探索这台手机，最后把这一趟折成一份结构化摘要（外加若干记忆候选卡）。它不是"替主人办事"的一档，目标只有查漏补缺地看清这台机器。

## 用途

- **目标不是完成任务**：先 `lw_apps` 看这台机器上有什么，再挑没看过的应用、没进过的页面、没展开的设置项，以及上一份摘要里写着的未知点。
- **观察优先级**：`lw_ui`（无障碍树，精确文本 + 像素矩形）→ `lw_ocr`（树里拿不到字时）→ `lw_screenshot` + `read_image`（两者都不行时）。截图是最后一档，同一个屏幕状态不重复截图、不重复 OCR。
- **屏幕纪律**：默认在自建虚拟屏（`lw_screen_create`）上做，不是自己建的屏不要释放；`display 0` 只在虚拟屏做不到时用，并说明这是例外。
- **收尾只有一条出口**：`lw_explore {op:"finish", summary, candidates}` —— 摘要分五节（已确认事实 / 仍不确定的点 / 下一场建议优先探索 / 风险与拦截记录 / 证据引用），8 千字以内；候选卡一张只写一件事，最多 5 张。
- **下一场先读上一份**：`lw_files` 列 `$DSH_HOME/lw/explore/` 取最新那份，接着上次的结论往下看。

## 与「Luwi」本体（`presets/custom-mode`）的差异

- plugins 列表**逐行照抄**手机模式那一份（19 行，一个工具都不删）—— D9 的拍板是"闸在提示词层"，所以本预设**不叠 `read-only` 权限档，也不 deny 任何工具**。手机模式（`presets/mobile-use`）与视频模式（`presets/video`）那两份独立预设已在 2026-10-18 下线：那两套提示词本来就装在 `assets/modes/*.md` 里，切模式时由应用写进 Luwi 本体的 `prompt.md`。
- 只换 `id / name / description / order` 与 persona：persona 20 条，三条红线是**不对任何应用做不可逆操作 / 不改任何已有文件 / 不发任何信息**，另有软红线（输入框、登录页、验证码、支付页、权限弹窗、删除确认框：不输入、不点正向确认）。
- 预算由**宿主**数：工具调用次数（缺省 60 步，到步只插一句"该收尾了"，**不拒调用**）与墙钟（缺省 30 分钟，到时取消那一轮并投一句"到点了，立刻收尾"）。省电模式在场时这一档直接拒开。
- 摘要与候选卡由**宿主**落盘，模型不许用别的工具写（红线 2）。

## 运行期落位与安装

- 预设声明落在 `<DSH_HOME>/profiles/web/cordis.patch.yml`，行 id `preset-explore`，`config.id = explore`，显示名「探索模式」，`order: 7`。
- **2026-10-18 起这一步由应用自己做**（`app/src/main/java/.../host/CustomPresets.kt` 的 `PRESET_ROWS`）：host spawn 之前把本目录 `cordis.patch.yml` 那段 `- insert:` 按行 id 追加进 profile patch（随 host 树走的那一份在 `lw-presets/explore.patch.yml`，由 `tools/pack-host.mjs` 拷进去）；`dsh-hmr` 监听该文件，保存即 reconcile 热生效，GUI 的预设选择器刷新页面后可见。
- 也支持作为 bundle 安装（`dsh.bundle.patch` 已声明）；本机 `plugin_manager install_bundle` 被策略拦截，走既定直改安装范式。

## 卸载回滚

把 profile patch 里那段 `- id: preset-explore` 所在的 `- insert:` 整段删掉即可，保存后由 `dsh-hmr` 热生效。已经建出来的 `Luwi` 工作区与 `$DSH_HOME/lw/explore/` 下的摘要都是普通文件，删不删不影响预设本身。

## 已知边界

- **子代理不计数**：persona 写明"不派子代理"，但宿主**不拦**；子代理的会话 id 不同，它的工具调用不进 `exploreRun.steps`。这是 D9 同一口径下的已知边界，收紧留给 2.7.9。
- **三条红线没有硬闸**：越没越界只能靠 `lw_explore op=status` 与摘要里的风险那一节如实记，验收里也按"如实记"算，不当成闸的判据。
- **一场结束就靠摘要接上**：本版没有 `op=resume`（那需要完整状态文件：屏幕图 / frontier / 已访问）。
- **候选卡只落不提**：`$DSH_HOME/lw/explore/candidates/` 里的卡在批次 7 只写不读；晋升闸门（低敏多场交叉验证 / 涉敏须主人点头）与界面留批次 8。
- 摘要是**文本**：模型写成什么样就是什么样，宿主只钉长度（8000 字截断记 `truncated: true`）与候选卡字段是否合法（不合法的整条丢掉并记读数）。

许可：MIT（与 `presets/custom-mode` 同）。
