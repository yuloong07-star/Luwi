# Luwi 酒馆 · 账号 / 登录门闸 / 开通入口 (施工单)

2026-10-25。酒馆首页多两样东西: 顶上一行**账号**, 四行之后一个**开通入口** (原标题是签到, 同日改); 「进入酒馆」要登录;
首次进酒馆弹一次说明。

## 0. 一句话口径

**签到这个动作只在官网 `/sync` 页面里发生, 应用里没有任何签到写操作。** 应用对账号只做两次**读**:
poll 取登录结果、entitlement 读到期。

> **2026-10-25 追加 (口径变更, 以本节为准)**: 签到**整条下线**, 同步页也不再卖时长; 唯一办理入口改成
> 官网 `/tavern` (酒馆页), `/sync` 只剩一整页跳转 (旧包点进来会被送到酒馆)。应用里:
> `AccountWire.tavernUrl()` = `https://luwi.dpdns.org/tavern`, 首页底部那一格从「签到」改成
> 「去开通 / 续费」(`TavernPlanButton`), 设置页 ⋮ 那一行从「签到」改成「账号」,
> `/api/checkin` 与 `settings_tavern_checkin*` 已在官网与两语字符串里删掉。下面各节凡是
> 提到"签到 / `/sync` 办理"的, 都按这一条读。

## 1. 为什么不用改官网

官网早就有设备码登录那条路 (PC 同步面板在用), Android 端直接接:

| 步骤 | 接口 | 语义 |
| :-- | :-- | :-- |
| 起登录 (读) | `GET /login?ls=<state>` | 页面把「用 GitHub 登录」指向 `/api/account/github/start?ls=`; 回调把 `account_id` 回填 `login_states` |
| 取登录结果 (读) | `GET /api/account/poll?state=` | **200** = `{token, account:{login,email}, expiresAt, paidUntil}`; **404** = 还没登录完; **410** = 已用/过期; 429 = 轮询太密 |
| 读到期 (读) | `GET /api/account/entitlement?token=` | 200 重签一份权益; 401 = token 失效 |
| 签到 (**2026-10-25 已下线**) | 原先是 `site/sync.html` 的「签到送 1 天」→ 页面自己 `POST /api/checkin` | 接口与页面动作都已删除; 现在 `/sync` 只跳 `/tavern` |
| 退出 | `/api/account/logout` | 只认 cookie, 所以应用退出登录 = 只清本地 (服务器那条 90 天后自然过期, 与 PC 插件同一做法) |

`state` 是 16~128 位 `[A-Za-z0-9_-]` (应用发 24 字节的 base64url, 32 位)。官网限流
`POLL_LIMIT = 60 次 / 10 分钟 / IP`, 所以应用是 **15 秒一拍**, 一次登录最多等 **10 分钟**。

## 2. 落点

新包 `app/src/main/java/io/github/yuloong07star/luwi/account/`:

| 文件 | 干什么 |
| :-- | :-- |
| `AccountWire.kt` | 纯 Kotlin: 三条地址 / state / poll 与 entitlement 的解析 / 到期三档 / 首字母 / 日期 |
| `AccountApi.kt` | 两条 GET (OkHttp 5 s / 10 s), 不重试 |
| `LuwiAccount.kt` | 快照状态 + `luwi` 偏好 + 登录轮询 + 退出登录 |
| `ui/TavernAccount.kt` | 账号行 / 开通入口 (`TavernPlanButton`) / 三个窗 / 回前台那两下 |

界面只动两处: `TavernManageScreen.kt` 的 `TavernHomeScreen` (插账号行与开通入口、门闸、挂窗) 与
`TavernChatScreen.kt` 的 `when` (未登录停说明卡, 不建 WebView)。

## 3. 状态机

```
IDLE ──startSignIn──▶ WAITING ──poll 200──▶ IDLE (account 有了)
                        │
                        ├──poll 410────────▶ EXPIRED
                        ├──poll 4xx/5xx────▶ FAILED (reason 原样念出来)
                        └──10 分钟到点─────▶ TIMEOUT
```

- 网络不通 (`transient = true`) **不算失败**, 接着等
- `ON_RESUME` 立刻补一拍 (2 秒去抖) —— 人在浏览器里点完就回来了
- 进程被系统收过: `account-login-state` 与 `account-login-started-at` 还在窗口里就接着问
- 读到期遇 401: 清本地账号 (唯一会因为服务端而退登录的地方)

## 4. 偏好键 (文件 `luwi`, 与 `TavernStore` / `FeedbackSubmit` 同一个)

| 键 | 是什么 |
| :-- | :-- |
| `account-token` | device_token; **不进日志、不上屏** |
| `account-login` / `account-email` | 账号读数 |
| `account-expires-at` / `account-paid-until` | 到期与付费到期 (0 = 没有) |
| `account-fetched-at` | 上次读到到期的时间 (60 秒之内不重复问) |
| `account-login-state` / `account-login-started-at` | 进行中的那次登录 |
| `tavern-notice-seen` | 首次那句说明看过没有 (点按钮 / 点空白 / 按返回都记) |

## 5. 界面顺序与门闸

首页自上而下: 状态读数 → **账号** → 进入酒馆 → 选择存档 → 选择主题 → 打开配置 → **去开通 / 续费** → 出错那句。

- 账号行: 未登录写「未登录 · 点一下用 Luwi 官网登录」; 登录后是首字母圆头像 + `@login` + 服务到期
- 点账号行: 未登录 → 起官网登录; 已登录 → 账号窗 (刷新 / 打开官网账号页 / 退出登录)
- 点「进入酒馆」: **未登录弹「酒馆需登录才可使用」** (去登录 / 取消), 不跳页
- 点签到: **已登录 → 浏览器开 `/sync`**; 未登录 → 起登录并原地提示「登录之后才能签到」
  (**2026-10-25 已作废**: 这一格现在是「去开通 / 续费」, 已登录 → 浏览器开 `/tavern`; 未登录 → 起登录并原地提示「未登录 · 点一下用 Luwi 官网登录」)
- 聊天页: 未登录只显示说明卡 + 去登录, 不加载 WebView (从存档页那一跳也进不来)
- 门闸只拦「进聊天页」这两条路; 存档 / 主题 / 配置 / 资料照常可用。到期**不拦**

## 6. 文案

两语各 17 个键, 插在 `settings_tavern_card_detail` 之后, 两边顺序一致
(`settings_tavern_account*` / `settings_tavern_login_*` / `settings_tavern_notice*`;
两个 `settings_tavern_checkin*` 2026-10-25 随签到一起删了)。
需求原文写的「登陆」按项目与官网既有写法统一成「登录」。

## 7. 判据

- 单测 `app/src/test/java/…/account/AccountWireTest.kt`: 地址 / state 字符集 / poll 四种结论 /
  refresh 三档 / 到期三档 / 首字母 / 日期 (固定时区) / 基址必须 https
- `node tools/check-tavern-account.mjs`: 应用里没有 `/api/checkin` 地址、账号层只有读、
  token 不进日志、偏好键、首页与聊天页的接线、两语这 **19** 个键 (签到两个键 2026-10-25 已删)
- 既有: `check-strings-keys` / `check-tavern` / `check-no-secrets` / `check-bare-percent`

## 8. 验收 (真机)

1. 未登录进酒馆首页: 账号行「未登录」, 开通入口那行小字「未登录 · 点一下用 Luwi 官网登录」
2. 首次进入弹一次说明, 点「不再提醒」/ 空白 / 返回之后不再出现
3. 未登录点「进入酒馆」: 弹「酒馆需登录才可使用」; 去登录 → 浏览器开 `/login?ls=…`
4. 浏览器里登录完回到应用: 账号行变 `@login` + 服务到期
5. 已登录点「去开通 / 续费」: 浏览器开 `/tavern`; 在官网买完回到应用, 小字日期跟着变
6. 未登录进聊天页: 说明卡 + 去登录, 没有 WebView
7. 杀进程重进仍是登录态; 账号窗「退出登录」之后回到未登录
8. 日志里没有 token

## 9. 不做

- 不做应用内签到 / 应用内付费 (不调 `POST /api/checkin`; 开通与续费一律在官网 `/tavern`)
- 不取 GitHub 头像 (只用首字母圆形头像, 零第三方请求)
- 不做"到期就锁酒馆"、不做签到提醒 / 自动签到 (签到已下线)
- 改的官网只有 `/sync` 跳转页与 `/tavern`, 不动版本号
