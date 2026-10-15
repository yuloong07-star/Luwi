# Luwi-dsh-sync 一键配置 · 施工单 (2026-10-15)

## 0 · 目标 (主人原话)

> 只要双方配置完会话仓库, pc端只需在同步面板里输入, 手机端只需在luwi设置里输入, 设置与luwi同步。
> 因此两者连接简化为只需在pc，luwi各输入github仓库地址即可。
> 在pc端的同步面板加开关, luwi的dsh里同步开关与设置里的一致。

拆成四条可验收的行为:

1. **PC 端**: 官方侧边栏那块「同步」面板里能**填仓库地址**(远端), 填完就能用
2. **手机端**: Luwi 设置页的「同步」段里填同一个地址 (已有, 不用改)
3. **PC 面板加开关**: 面板上有一个「同步」总开关, 与手机那个开关同一套语义
4. **手机 dsh 里的开关与设置里一致**: 手机上 dsh GUI 那块面板的开关, 与 Luwi 设置页那个开关
   是**同一份状态** (读写同一个文件, 两边都改得动、都看得见)

## 1 · 现状 (2026-10-15 摸的底)

| 面 | 现在有什么 | 差什么 |
| :-- | :-- | :-- |
| 插件面板 (PC 与手机的 dsh GUI 共用同一份 `lib/client.js`) | 三个页签: 总览 / 操作 / 诊断;操作页有 拉取 / 推送 / 名片 / 诊断四个按钮 | **没有填地址的地方, 没有开关** |
| 面板宿主半 (`lib/panel-routes.mjs`) | ops = `status` / `pull` / `push` / `peers` / `doctor` | 没有写配置的 op |
| 配置来源 | 三层合并: **显式 cordis 键 > `$DSH_HOME/lw/session-sync.json` > DEFAULTS** | PC 侧覆盖行里写死了 `remote` 与三档 auto, 压住了文件那一层 —— 面板就算写了文件也不生效 |
| 手机设置页 | 「Luwi-dsh-sync」段: ① 仓库 → ② 节奏 → ③ 工作区, 开关在段首;写 `sync/SyncSetting.kt` | 无 (这块是要对齐的基准) |
| 生效方式 | 插件**挂载时**读配置 | 改完必须重启 host (PC = 重启 Desktop;手机 = 顶栏菜单「同步」) |

## 2 · 改动清单

### 2.1 插件: 新增一个写配置的 op

`lib/panel-routes.mjs`

- `PANEL_OPS` 增加 `CONFIGURE: 'configure'`, 并进 `WRITE_OPS` (要 confirm、要单飞)
- 入参: `remote` (非空才算配好) / `branch` / `autoPullOnStart` / `autoPushOnTurnEnd` /
  `pullIntervalMinutes` / `on`
- 语义**照抄手机应用的 `sync/SyncSetting.kt`**, 不许另立一套:
  - `on = false`: `remote` 挪进 `lw.savedRemote`, 三档 auto 挪进 `lw` 段再归零 (缺一不可 ——
    只清远端的话周期拉取照旧按点起来, 白烧电)
  - `on = true`: 从 `lw` 段把地址与三档填回去
  - **保留未知键** (整棵树照抄, 只动自己那几个)
- 应答: `{ ok, action, restartRequired: true, card }` —— 面板据此提示"重启 host 生效"

`index.mjs`

- 接上 `configure` 的写实现 (纯文件那一半, 与 `luwi-bridge-runner.mjs` 读的那份文件同路径)
- 键名与手机侧逐字对齐 (`remote` / `branch` / `autoPullOnStart` / `autoPushOnTurnEnd` /
  `pullIntervalMinutes` / `lw.savedRemote` / `lw.savedAuto*`), 由 `tools/check-sync-setting.mjs`
  那套键表继续钉

### 2.2 插件: 面板 UI

`lib/client.js`

- 「操作」页签顶部加一块**仓库卡片**: 远端输入框 (type=url, 占位说明写成
  `https://github.com/<你>/<仓>.git`)、分支输入框 (空 = main)、保存按钮
- 卡片右侧一个**开关**: 同步总开关 (开/关), 点它就走 `configure` 的 `on`
- 保存/切换成功后: 就地提示一句「重启 host 后生效」+ 沿用现有的确认条 (两次点击) 纪律
- 远端**只回显脱敏形态** (与启动行同一条纪律: 值里可能有 token, 绝不回显原文)

### 2.3 配置: 把 PC 覆盖行让开

`E:\Mobile-AI\dsh-home\profiles\desktop\cordis.patch.yml` 的 `- id: luwi-dsh-sync` 段:

- **删掉** `remote` / `branch` / `autoPullOnStart` / `autoPushOnTurnEnd` / `pullIntervalMinutes`
  —— 这四项交给 `$DSH_HOME/lw/session-sync.json`, 面板才写得动
- **保留** `sessionRoot` (桌面宿主 env 里没有 DSH_HOME, 必须显式给) / `repoDir` / `deviceName` /
  `publicUrl` / `peer` / `live` / `confirmVia` / `panelSeats` / `panelOps`
- 同时把这四项的当前值写进 `E:\Mobile-AI\dsh-home\lw\session-sync.json`, 免得搬完丢配置
- 手机侧覆盖行本来不带 config, 不用动

### 2.4 应用侧

- 设置页已有地址与开关, **一个字不改**
- 要确认的一件事: 面板写文件之后, 应用设置页再次打开/回到前台时能读到新值 (读文件时机)
- 反过来: 设置页改完, 面板下一次快照刷新自然读到 (面板每次都 fetch 快照)

## 3 · 生效与验证

| 步 | 做什么 | 判据 |
| :-- | :-- | :-- |
| 1 | `node --test "test/*.test.mjs"` | 全绿 (现 154 条) |
| 2 | 给 `configure` 补单测: 保留未知键 / 关=挪走并归零 / 开=填回 / 空地址拒 | 新增几条全绿 |
| 3 | PC: `pnpm pack` → 装进 profile → 重启 Desktop | 面板里能填地址、能拨开关 |
| 4 | 手机: 出包 (改的是插件, 随 APK 发) | 手机 dsh 面板里同样能填、能拨;与应用设置页显示同一状态 |
| 5 | 两端填同一个仓库地址 | 各自 `lastPullAt` 在涨, 裸仓库出现两边的新提交 |

## 4 · 这一版**不做**的

- 不碰 adb 隧道那一套: 那是本机(USB 直连)的连通手段, 与"填地址"这件事正交 ——
  主人要的是配置动作简化成"各填一个地址", 不是让插件自己去搭隧道
- 不做"保存即生效": 插件是挂载时读配置, 热生效要动 GitBackend 的生命周期, 留作下一版
