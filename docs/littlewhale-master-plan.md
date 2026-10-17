# Luwi 改造总计划

本文是**本次会话涉及的全部改动**的合并计划, 分四部分, 每部分可独立执行、独立验证、独立回退

| 部分 | 改什么 | 风险 | 状态 |
| :-- | :-- | :-- | :-- |
| 一 · 虚拟屏修复 | 建屏被拒 | 中 (改特权侧一处) | **已落地**, 真机验过 |
| 二 · 标题栏与浮标 | 顶栏整条去掉, 菜单改成可拖的球 | 低 (纯 UI) | **已落地** (另长出一个小窗, 见第二部分附) |
| 三 · 预装 webui | host 树加 mobile web-ui 插件 | 低 | **已落地**, 已装机 |
| 四 · dsh 升到最新 | fork 跟上游 | **高** (运行时地基) | **批次 1 做完** (只读排查), rebase 未完成 |

**执行顺序建议**: 一 → 二 → 三 → 四。前三个互不依赖, 第四个动的是地基, 放最后

---

# 通用约束 (每一部分都适用)

- **先 dot-source**: `. D:\apk\env.ps1` (构建前必做, 不设系统环境变量)
- **adb 不在 PATH**: 完整路径 `D:\apk\Sdk\platform-tools\adb.exe`
- **两台设备**: `10CEB40568000ZB` (vivo V2417A, 真机) 与 `emulator-5554`, **每条 adb 命令都要带 `-s`**
- **装机必须带 `-i`**:

  ```
  adb -s <设备> install -i com.android.packageinstaller -r -t <apk>
  ```

  **不带 `-i` 的普通 `install -r` 会把 `installerPackageName` 打回 `null`** (实测确认), 而
  `installerPackageName` 为 null 的应用在这台机器上**开不了无障碍服务** —— 详见第六部分
  「无障碍服务开不了 (已定论)」。
- **不改**: `env.ps1` / `third_party/deepseek-harness` 的 pin `e79ffe35e1` / `jniLibs` 那 18 个 `.so` (在 `.gitignore` 里, 清了要重建)
- **改完 Kotlin 先过编译**: `.\gradlew.bat :app:compileDebugKotlin`, 但**它不跑 manifest merger**, 改依赖或 minSdk/targetSdk 后必须 `:app:assembleDebug`
- **这台 vivo 的 logcat 是空的** (已实测四种办法: `logcat -d` / 按 pid / `-g` 看缓冲 / 设备内重定向, 缓冲区报 readable 但一行都出不来)。**验证靠界面可见结果, 不要指望日志**
- **验证优先用触摸实测**: 截图看不出命中测试这类问题 (这个 project 已经因此误判过一次)。能让用户点一下的, 就别只看图

---

# 第一部分 · 虚拟屏修复

## 1.1 现象

真机建一块 720x1280 @320dpi 的屏, 界面显示:

```
虚拟屏: no 720x1280 display at 320dpi:
packageName must match the calling uid
```

## 1.2 两个症状是同一个

用户报的"完全不可用"与"只显示一串字符"是**同一件事**:

那串字符就是上面那条错误, 来自 `HostScreen.kt` 里 `lastError` 的渲染 (虚拟屏段, `selected == null` 且
`lastError != null` 时显示)。**屏建成的瞬间它自己就消失**, `VirtualScreenPreview` 的 `SurfaceView` 接管位置

**所以不需要修"显示成字符串"**

## 1.3 根因

`LwVirtualDisplay.kt` 把系统异常包了一层:

```kotlin
throw IllegalStateException("no ${width}x$height display at ${dpi}dpi: ${error.message}", error)
```

`error.message` 是 system_server 给的。`DisplayManagerService.createVirtualDisplay()` 里:

```java
final int callingUid = Binder.getCallingUid();
if (!validatePackageName(callingUid, packageName)) {
    throw new SecurityException("packageName must match the calling uid");
}
```

`validatePackageName` 主体:

```java
private boolean validatePackageName(int uid, String packageName) {
    if (packageName != null) {
        String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
        ...
    }
}
```

来源: [DisplayManagerService.java (50ebd0232f1a)](https://android.googlesource.com/platform/frameworks/base/+/50ebd0232f1a/services/core/java/com/android/server/display/DisplayManagerService.java), [另一 revision (41299be4)](https://android.googlesource.com/platform/frameworks/base/+/41299be43bf814c9cb9b9545d60f17834199c956/services/core/java/com/android/server/display/DisplayManagerService.java)

链路:

```
Luwi (uid 10384) → Shizuku → newProcess() → 特权进程 (uid 2000 = SHELL)
                                                        ↓
                              createVirtualDisplay(packageName = "io.github.yuloong07star.luwi")
                                                        ↓
              getPackagesForUid(2000) = ["com.android.shell"] ≠ 那个包名 → SecurityException
```

`LwContext.kt` 把包名伪装成 app 的:

```kotlin
override fun getPackageName(): String = packageName
override fun getOpPackageName(): String = packageName
```

**能骗过读 `Context` 的代码, 骗不过 `Binder.getCallingUid()`**

## 1.4 关键实测: 权限不是问题

```
$ adb shell dumpsys package com.android.shell | grep -i CAPTURE_VIDEO
  android.permission.CAPTURE_VIDEO_OUTPUT
  android.permission.CAPTURE_VIDEO_OUTPUT: granted=true
  android.permission.ADD_TRUSTED_DISPLAY: granted=true
  android.permission.INTERNAL_SYSTEM_WINDOW: granted=true
```

`com.android.shell` **已持有全部三个权限**。**卡住的是包名匹配, 不是权限**

## 1.5 修复方向

**方向 A (先做)**: 让建屏那一次调用的 `packageName` 与 uid 2000 对得上 → 报 `com.android.shell`

- 改动点: 只碰 `LwVirtualDisplay.kt` 的建屏路径
- **不能全局改** `LwContext.getPackageName()` —— 它服务 provider 调用与 `createPackageContext`

**方向 B (A 失败再评估)**: 让 app 进程自己建屏

- 问题: app 没有 `CAPTURE_VIDEO_OUTPUT`, `TRUSTED` / `OWN_FOCUS` / `OWN_DISPLAY_GROUP` 会被拒或静默降级
- `LwVirtualDisplay.kt` 顶部注释写明当初把建屏放特权进程的理由就是这些 flag

**明确不做**:
- 不引入 root (设备没有, 用户不需要)
- 不走 PRoot 容器 (那是 DSHA 的路子, 但容器里是独立 Linux, **看不见宿主 app**, 解决不了操控真实应用)

## 1.6 分批

### 批次 1 · 确认包名来源 (只读, 不改码)

**目的**: 动代码前确定 `DisplayManager` 从哪儿取包名

**做**:
1. 读 `LwVirtualDisplay.kt` 的 `manager()` (反射构造 `DisplayManager(Context)`)
2. 查清 `DisplayManager` 构造时是否缓存 `mContext.getOpPackageName()`
3. 确认 `LwContext.getOpPackageName()` 现在返回什么

**验收**: 能一句话说清"改哪个对象能让建屏那次调用的包名变成 `com.android.shell`", 且不影响其它路径
**产出**: 一份结论 (**可能推翻方向 A**)

### 批次 2 · 建屏专用包名

**改动点**: 只碰 `LwVirtualDisplay.kt`, 且只碰建屏路径

**验收**:
- `.\gradlew.bat :app:compileDebugKotlin` 通过
- 装机后建一块 720x1280 @320dpi 的屏 → **界面不再出现那行错误**
- 菜单「虚拟屏」子菜单能看到这块屏

**失败时**: 报错若变了 (不再是 `packageName must match`), **把新报错截图记下来** —— 那是下一批的输入

### 批次 3 · 让画面出来

建屏时 surface 传的是 `null` (`VirtualScreen.create()` 里写死), 真实 surface 靠
`VirtualScreenPreview.kt` 的 `surfaceCreated` 回调经 `VirtualScreen.attach()` 补上

**要验**:
1. `attach` 有没有真的把 surface 交到特权侧 (`setDisplaySurface`)
2. `attach` 里 `if (selected?.displayId != screen.displayId) return` 会不会把回调挡掉
3. `setFixedSize(w, h)` 有没有在 surface 存在之后才调用

**验收**: 预览区出现真实画面 (不是黑块、不是文字)

### 批次 4 · 后台运行

**目的**: 用户核心诉求 —— "能在后台用虚拟屏跑, 不需要显示"

**要验**:
1. 切后台 / 关预览后屏是否继续存在 (注释说 detach 是一等状态, 屏应当活着)
2. 前台服务 `DshHostService` 会不会被系统收掉
3. 不选屏时 (`selected == null`) 建屏能否成功

**验收**: app 退后台, 模型仍能经 `lw_screen` / `lw_tap` / `lw_screenshot` 操作那块屏

---

# 第二部分 · 标题栏与浮标 (**已落地**)

**状态: 做完并装机验证过**。改动全在 `ui/HostScreen.kt`

## 2.1 硬边界 (仍然有效)

**一个字节都不动的文件**: `MainActivity.kt` (含 `enableEdgeToEdge()`)、`theme/SystemBars.kt`、主题 XML、
任何 `WindowInsets` / `statusBarColor` 相关调用

**状态栏与标题栏是两件事**, 这一部分从头到尾没碰状态栏

## 2.2 最终形态

| 项 | 结果 |
| :-- | :-- |
| 顶栏 | **整条去掉**。`Scaffold` 不再传 `topBar`, 原来那条写 `Luwi` 的 `SmallTopAppBar` 连同 `actions` 一起没了 |
| 菜单入口 | 一枚浮标 (`FloatingBall`), 常驻 |
| 浮标底色 | `colorScheme.surface` —— **跟着主题走, 不写死白色** |
| 浮标尺寸 | `BALL_SIZE = 44.dp`, `CircleShape` 裁剪, 2dp 投影 |
| 拖动 | 拖到应用内任何地方; **拖过才记住位置**, 没拖过待右上角 |
| 拖动的手感 | 目标与渲染分开: 指针推目标, 屏幕上是**弹簧追过去**。跟手有阻尼、撞边界是缓停、松手自己收敛 |
| 按住时 | 放大到 1.08、投影 2dp → 6dp |
| 空闲 5 秒 | alpha 渐到 **0.25**, 任何交互再渐回来。**只用 alpha, 不移出视图树** |
| 点按 | 开原来的级联菜单 (虚拟屏 / 虚拟屏触摸控制 / 显示隐藏小窗 / 设置) |
| 淡出 | **删掉了** (见 2.3 ①) |

## 2.3 三个当时没料到的地方

### ① "要能拖" 与 "会自己淡出" 是矛盾的

原来有两处按钮: 顶栏 `actions` 里那个, 和看画面时浮在画面右上角、静 3 秒淡出的 `FloatingMenuButton`。
**一个会淡出的东西没法拖** —— 想拖的时候它正好不在。所以合成一个常驻的球, 并把整条淡出机制删掉:
`controlsVisible` / `controlsTick` / `menuOpen` / `keepControls` / `CONTROLS_IDLE_MS` / `LaunchedEffect`
/ `MenuButton.onExpandedChange`, 以及 `AnimatedVisibility` / `fadeIn` / `fadeOut` / `delay` /
`LocalLayoutDirection` 五个 import

顺带: 白色的球在白色 dsh 界面上只剩一个图标悬着, 看不出是颗球也看不出能拖。**底色改成跟主题走**
(深色主题下它就该是深色的), 再留一点投影

### ② 拖动回调里读组合期的值 = 钉住不动 (踩了两次)

```kotlin
.pointerInput(Unit) {
    detectDragGestures { _, drag ->
        position = Offset(at.x + drag.x, at.y + drag.y)   // at 是组合期算好的死值
    }
}
```

`pointerInput(Unit)` **不会因为位置变化重启**, 所以 `at` 永远是第一次组合时那个值, 每一下拖动都从
同一个位置重算 —— 窗/球被钉在原地。修法: 回调里只读活值

- 小窗: 钳制范围用 `rememberUpdatedState` 送进去, `position` 走 state 委托读当前值
- 浮标: `val from = position ?: Offset(limitXNow, 0f)`

**这个坑在小窗上修好之后, 我又在浮标上原样写了一遍。** 以后凡是在 `pointerInput` 回调里读外层的量,
先问一句"它是活的吗"

### ③ 顶栏去了之后, 状态栏那条 inset 换了主人

顶栏在的时候那条 inset 是顶栏自己吃的; 顶栏一去掉, 改由 `Scaffold` 的 `innerPadding` 给

## 2.4 拖动为什么硬, 后来怎么软的

两处拖动（浮标与小窗）原来都是**指针指到哪就写到哪**、渲染直接读那个值, 没有任何插值。于是跟手是
1:1 的硬跟, 撞到 `coerceIn` 的边界是硬停, 松手也是硬停 —— 用户的原话是"特效有点生硬"

### 第一版改错了: 弹簧跟随反而更硬

第一版让渲染值用**弹簧**去追指针, 想做成"跟手但有阻尼"。结果 `Spring.StiffnessMediumLow`
(=200) 太软, 屏幕上的球明显落在手指后面, 要等手停下来才追得上去 —— 用户的原话是:

> 它没有被拖动, 而是移动停止后才最终落位, 这样导致更加生硬, 也应该随着手指移动

**教训: 跟手这件事上, 严丝合缝比优雅重要。** 弹簧只用在"不在拖动"的时候（头一次落位、可用范围
变了）, 拖动中一律 `snapTo`:

```kotlin
LaunchedEffect(target, dragging) {
    when {
        !placed -> { rendered.snapTo(target); placed = true }
        dragging -> rendered.snapTo(target)          // 拖动中必须严丝合缝
        else -> rendered.animateTo(target, SETTLE_SPRING)
    }
}
```

**"软"改由按住那一下承担** (见下), 它不影响落点、不影响跟手, 但看得见

### 按住时的反馈: 浮标能缩放, 小窗不能

浮标按住时放大到 1.08 并把投影从 2dp 加深到 6dp。**小窗只加深投影（0dp → 6dp）, 不加缩放** ——
窗里的画面是 `SurfaceView`, 而 `graphicsLayer` 是 RenderNode 变换, SurfaceView 的 surface 由
SurfaceFlinger 单独摆一层、**不跟着父节点的变换走**; 给外层加缩放会出现"标题栏放大了、画面没放大"
的错位。投影不影响 surface, 是安全的等效反馈

### 踩过的坑: 绘制修饰符只影响排在它后面的节点

变淡那一版写成了

```kotlin
.shadow(...).clip(...).background(colorScheme.surface).graphicsLayer { alpha = ballAlpha }
```

用户看到的却是**只有图标淡了、球的底色没淡**。原因是 Compose 的绘制修饰符**只作用于它后面的
节点**: `graphicsLayer` 排在 `background` 之后, 那一层就只包住了里头的图标, 底色与投影画在层外。
把它挪到 `shadow` / `background` **之前**就对了

同一个道理还有个副作用要躲开: 层的缩放会作用到后面的命中测试上, 所以 `pointerInput` 要放在
`graphicsLayer` **之前**, 否则拖动位移会被按 1.08 折算, 球走得比手指慢

## 2.5 验收

- 顶栏不再有 `Tune` 图标, `Luwi` 标题也不在了
- 浮标可见、可拖、拖到边缘停住
- 点浮标 → 菜单出来 → 点「设置」→ **能进设置页**

---

# 第二部分附 · 虚拟屏小窗 (**已落地**)

这一块是做的过程中长出来的: 用户要"像 DSHA 那样在一个小窗里看虚拟屏"。改动同样全在
`ui/HostScreen.kt`, 另加 `VirtualScreenPreview` 的一个参数

## 附.1 形态

| 项 | 结果 |
| :-- | :-- |
| 位置 | 浮在会话上, 可拖到整块可用区域 |
| 宽度 | 屏幕宽度的 `1 / WINDOW_SCREEN_FRACTION`, 现在 `= 3` |
| 高度 | 跟着屏自己的形状走 (`width / screen.aspect`) |
| 顶栏 | 36dp, **整条是拖动把手**, 右端一个减号 |
| 减号 | `MiuixIcons.Remove`, **把整扇窗收掉**, 不留把手 |
| 收起来之后 | 走 ⋮ → 虚拟屏 → 「显示虚拟屏小窗」叫回来 (窗口离树时 remember 丢弃, 所以回到起点) |
| 淡出 | 没有 |

## 附.2 四个踩过的坑

### ① 下面一半是黑的 —— `heightLimit` 的含义读反了

```kotlin
val boxHeight = minOf(maxWidth * heightLimit, maxWidth * (screen.height / screen.width))
```

`heightLimit` 说的是**"画面最多给到宽度的几倍"**。竖屏画面要的高度是宽度的
`screen.height / screen.width` 倍 (1260x2800 的屏是 2.22 倍), 而当时传的是 `1f` —— 等于按宽度封顶,
画面只画得出上面 `1 / 2.22`, 底下空着的全是那个盒子的 `background(Color.Black)`。用户看到的就是
"下面一半既没内容也点不动"

**修法**: `heightLimit = 1f / screen.aspect`

### ② 减号不能用 `IconButton`

Miuix 的 `IconButton` 是 48dp, 而窗只有屏宽的几分之一 (手机上 60dp) —— 一个按钮就把整条标题栏占满,
拖动没地方下手。改用 24dp 的 `Box` (`WINDOW_BUTTON_SIZE`)

Miuix 0.9.4 的 156 个图标里**没有 Minus, 只有 `Remove`**

### ③ 顶部那一条是系统手势区

实测: 从 y=250 往下划会被通知栏接走, y=400 才落回应用。所以

- **起始位置** `WINDOW_START_TOP = 120.dp` —— 一上来就落进手势区会抓不住
- **但拖动上限放开到 0** —— 用户要的是整块屏幕都能放。拖进去出不来的退路是: 减号收起来再显示, 窗口回到起点

### ④ 给窗口量尺寸的那层容器

`fillMaxSize` 的空 `Box` 一度被当成"窗口下面点不动"的嫌疑人而删掉。**后来发现真正的原因是 ①** ——
那一半本来就是黑底, 不是被谁吃了触摸。现在的形态是小窗自己量自己:

```kotlin
Box(modifier = modifier.fillMaxSize().onSizeChanged { viewport = it }) { … }
```

比外面套一层更省, 也不再需要 `viewport` 参数

## 附.3 已知取舍

小窗盖住的那块 dsh 界面**是真的看不见** —— WebView 量的是自己的视口, 被浮窗压住的部分不会让位
(`AGENTS.md` 里作者写过这一点)。宽度从 1/6 翻到 1/3 之后这个代价翻了四倍, 觉得碍事就把
`WINDOW_SCREEN_FRACTION` 改回 4

---

# 第三部分 · 预装 webui 插件 (dsh-web-mobile) (**已落地**)

> **落地方式与原计划不同, 差异记在这里**: 计划写的是"加进依赖声明让它跟其它包一起被 npm 装",
> 实际做成了**隔离取包 + 拷进树** (与 `luwi-channel` / `sharp` 同一个模式)。
> 原因是依赖声明会把 npm 的 peer 解析拉到**整棵树**上: `dsh-web-mobile` 的 peer 范围接受已发布的
> 0.1.x 与 0.2.0-rc.1+, 但**不接受本树所基于的那个预发布版 `0.1.5-rc.2`**, 于是整个 install 只能
> 靠 `--legacy-peer-deps` 才过 —— 为了一个对它毫无需求的包, 去改**整个 dsh 闭包**的解析方式, 不值。
> 隔离之后那个放宽只作用在一个用完即删的临时目录上, 主 install 的旗标一个字没动。
>
> 它**没有运行时依赖** (实测 `added 1 package`), 所以拷进来的是一份完整可用的包

## 3.1 版本事实

```dsh-ui
{"title":"版本三要素","gap":12,"items":[{"type":"keyvalue","pairs":[{"key":"webui (dsh-web-mobile) 官方 latest","value":"3.1.0 (2026-10-17 查); 本树装 3.1.0 (2.7.8 批次 4 抬上去)"},{"key":"dsh npm latest / next","value":"0.2.0-rc.2"},{"key":"dsh 当前 submodule pin (fork)","value":"0.2.1-alpha.1 (2026-10-17 实测)"},{"key":"DSHA 在用","value":"0.1.7-rc.2"}]}]}
```

**用 peerDependencies 反推** (2026-10-17 实测: `dsh-web-mobile@3.0.6` 与 `@3.1.0` 的 range **逐条相同**):

```
^0.1.0-rc.6 || >=0.1.1-rc.0 <0.2.0 || >=0.1.2-a <0.2.0 || >=0.2.0-rc.1 <0.3.0-0
```

实测判定: `0.2.1-alpha.1` ✅ (本树) / `0.1.7-rc.2` ✅ / `0.2.0-rc.2` ✅

**三个都过 → 装 webui 与升 dsh 可以解耦, 现在就能装**

> 2026-10-17 补记: 2.7.8 的 D7 定的是 3.0.6, 当时的理由是"3.1.0 的宿主下限 0.1.7-rc.1 与本树 0.1.5-rc.2 冲突"
> —— **今天这条理由已不成立** (本树早已是 0.2.1-alpha.1, 两个版本的 peer range 一模一样)。**批次 4 已抬到
> 3.1.0** (主人 2026-10-17 当天先自己换上那一版, 签回 `tools/pack-host.mjs`): 3.1.0 多的正是两处面板退场
> 快照的修复, 与这一批要量的两条对得上

## 3.2 怎么装

`tools/pack-host.mjs` 里已有的模式:

```javascript
run('npm', ['install', '--no-audit', '--no-fund', '--package-lock=false', '--omit=optional'], out)

// 然后 cpSync 两个东西进去
const installed = join(out, 'node_modules', 'luwi-channel')
cpSync(plugin, installed, { recursive: true })

const sharpened = join(out, 'node_modules', 'sharp')
cpSync(imageBackend, sharpened, { recursive: true })
```

**关键区别**: `luwi-channel` 与 `sharp` 都是**install 之后 cpSync 进去的** (前者不是 npm 依赖, 后者要替换)。
而 `dsh-web-mobile` **是 npm 上的正式包**, 应该**加进依赖声明** (上面第 146 行那个 `package.json` 的
`dependencies`), 让它跟其它包一起被 npm 装。**不要用 cpSync**

**装完还要改 `host/PluginOverlay.kt`**: 它现在只写一行 `insert`:

```kotlin
private const val ENTRY = "node_modules/luwi-channel/index.mjs"
private const val ID = "luwi-channel"

fun write(context: Context): File? {
    val entry = File(DshHost.hostRoot(context), ENTRY)
    if (!entry.isFile) return null          // ← 只认一个入口
    ...
    - insert:
        - id: $ID
          name: '${entry.absolutePath}'
}
```

**要加第二行 `insert`**。注意现在的 `return null` 是"树里没有这个插件就不给 overlay",
加第二个插件后这个判断要改成"两个都没有才返回 null"

## 3.3 批次

### 批次 1 · 加依赖 + 加 overlay 行

**验收**:
- `node tools\pack-host.mjs --dsh third_party\deepseek-harness --out build\host-tree` 成功 (约 5 分钟)
- `build\host-tree\node_modules\dsh-web-mobile` 存在
- `.\gradlew.bat :app:assembleDebug` 通过

### 批次 2 · 真机验证挂载

**验收**: 装机后 Web GUI 出现移动端适配 (这是 `dsh-web-mobile` 的作用)

**失败时**: `npm error code ERESOLVE` → 实测 range 涵盖 `0.1.5-rc.2`, 理论上不撞;
真撞了就 `--legacy-peer-deps`

---

# 第四部分 · dsh 升到官方最新

## 4.1 为什么这是高风险

```dsh-ui
{"title":"升级要面对的事实","gap":12,"items":[{"type":"table","columns":["项","值"],"rows":[["npm latest (目标)","0.2.0-rc.2"],["npm alpha","0.2.1-alpha.1 (更靠前, 但是 alpha)"],["当前 pin (fork)","0.1.5-rc.2"],["fork 落后上游","约 4225 个提交"],["fork 的 sync-upstream workflow","disabled_manually, 最近 10 次定时同步全失败"],["session format","fork 是 V3, 上游是 V4"]]}]}
```

**Luwi 用的不是官方 npm 包, 是 fork 的源码树。** fork 上有为安卓做的补丁, **上游一条都没合**。
已知至少两处 (都是安卓 FUSE 上硬链接会失败的地方, fork 加了 `copyFile(..., COPYFILE_EXCL)` 兜底):

- `packages/session-persistence-jsonl/src/index.ts` 的 `await link(tmp, finalPath)`
- `packages/fs/fs-local/src/fsio.ts` 的 `await linkFile(...)`

## 4.2 批次 1 实测结论 (做过了, 只读)

### 上游根本没有 CHANGELOG

计划原本写着"第一步查上游的 CHANGELOG" —— **那一步执行不了, 上游没有 CHANGELOG.md**。
真正的升级文档是 `docs/upgrade-guide/<版本>/<slug>/`, 而且**只覆盖被明确认定为 breaking 的改动**

**我们这条路径上只有两条**, 都在 `docs/upgrade-guide/v0.1.7-rc.2/`:

| 指南 | 内容 | 对我们的影响 |
| :-- | :-- | :-- |
| `schedule-optional-bundle` | Schedule 那几行从 web 组合搬进「Automation tasks」可选包, 按 id 打开过的 profile 会丢 | **无** —— 我们没用 Schedule |
| `transcript-view-legacy-normal` | 旧的 `normal` 值以后显示成 Detailed | **纯观感**, 设置里选一次 Standard 就回去 |

两条指南都发布在 **v0.1.7-rc.2**, 描述的是"从下一个版本起"的变化 —— **也就是说 breaking 改动落在
`0.1.7-rc.2 → 0.2.0-rc.2` 这一段, 而不是 `0.1.5-rc.2 → 0.1.7-rc.2`**

### session 格式: 我们写 V3, 它写 V4

`docs/session-format-status.md` 是权威: 上游 `latestFinalizedVersion: 4`, 而
`latestReleasedVersion: 3` (证据 tag `dsh-v0.1.5-alpha.1`)。我们这棵树写 **3**

**比计划里担心的轻**: 上游有 `session-format-v3-to-v4` 迁移包, 而迁移脚本的说明是
「把 V4 后继版本发布在**未改动的历史代际旁边**」—— 是新增, 不是覆写。计划里把"旧会话打不开"
列成头号风险, 实际没有那幺重

### 安卓补丁**没有**过时

实测上游 `0.2.0-rc.2` 在这两处**仍然用裸 `link()`**, 没有 FUSE 兜底:

- `packages/fs/fs-local/src/fsio.ts` → `linkFile(tempPath, absolutePath)`
- `packages/session/session-persistence-jsonl/src/index.ts` → `link(tmp, finalPath)`

所以那批"硬链接被拒就改成独占拷贝"的补丁**必须原样带过去**, 不能指望上游替我们修了

### 真正的规模: 47 个文件, 13 个提交

`git diff --stat c291e7961a e79ffe35e1` = **47 files, +1512 −311**, 而且**冲突集中在 fork 的
客户端功能上** (`ui-theme` / `ui-settings` / `apps/web` 的 e2e), 因为上游大改过那几个包;
安卓那批服务端补丁反而相对稳

**实测** (在 `lw-sync` 分支上试过, 之后已 `rebase --abort` 复原): 前 13 个提交里的第 2 个
(`8a40801210 local: persist theme and font size per browser`) 一个提交就有 **15 处冲突**,
分布在 6 个文件。**这是一项以小时计、而且必须逐处判断的工作, 不是一轮能做完的**

### 4.2.1 建议: 把这一跳拆成两跳

`0.1.5-rc.2 → 0.1.7-rc.2` 是**温和的那一跳** (上面两条 breaking 指南都不覆盖它), 而且
**0.1.7-rc.2 正是 DSHA 在用的版本** —— 它在安卓上是已知能跑的, 它的安卓补丁是验过的。
然后再从 0.1.7-rc.2 跳到 0.2.0-rc.2, 那一段才带上格式变更

两个小 rebase 各自可验, 比一次跨 4225 个提交的巨跳可控得多。**先问用户是否接受这个拆法**

## 4.3 怎么做 rebase (可复现)

```powershell
cd D:\apk\Luwi\third_party\deepseek-harness
git switch -c lw-sync
git fetch --no-tags --depth=1 https://github.com/deepseek-ai/deepseek-harness.git refs/tags/dsh-v0.2.0-rc.2   # 5 秒
git rebase --onto FETCH_HEAD c291e7961a
```

**两个前置条件** (都踩过):

- 子模块里**没有 committer 身份**, 不设会以 `Committer identity unknown` 停住。已按 fork 自己的
  提交设成 `Miuzarte <982809597@qq.com>` (只设在子模块本地, 没碰 `--global`)
- `git rebase --continue` 要用 `git -c core.editor=true rebase --continue`, 否则它会开编辑器等输入

**复原**: `git rebase --abort` + `git switch master`, 然后核对
`git rev-parse HEAD` 是否仍等于 `e79ffe35e1`。**实测复原后父仓库看到的子模块是干净的**

## 4.4 还要做的事

### 4.4.0 实测进度 (这一轮做到的)

**备份**: 手机 `files/dsh-home` 已 tar 到 `D:\apk\backups\dsh-home-20261003-213302.tar.gz`
(1.81 MB / 553 条目, 含 `.credentials.yaml` / `settings.yaml` / `profiles/` / sessions)。取法不需要
root: `adb exec-out run-as <pkg> tar -czf - files/dsh-home`。里面能看到 `session.v3.jsonl.zstd`,
独立印证了"我们写 V3"

**rebase: 做完了**。13 个 fork 提交全部重放到上游 0.2.0-rc.2, 外加 1 个补丁提交, 结果在子模块的
分支 **`lw-sync` = `55ac0f67a7`**。`master` 仍是原 pin `e79ffe35e1`, 工作树已切回 master

**补丁量从 47 文件 / ±1823 降到 31 文件 / +1119 −143** —— 因为上游把 fork 的"native 绑定懒加载"
泛化成了 `@deepseek-ai/dsh-lazy-require` 包加各 realm 的 `koffi.ts` / `sharp.ts`, 那一批冲突直接
取上游即可 (fork 那 18 文件 64 处冲突的提交整个作废了)

**七个安卓命根子都验过在位**: fsio 的 `isLinkUnavailable` + `COPYFILE_EXCL`、lease 的
`ERR_FLOCK_UNSUPPORTED_PLATFORM`、session generations 与 index 的独占拷贝、`DSH_BASH`、
web-app 的 LAN 绑定 opt-in

### 4.4.0.1 现在卡在哪 (必须先解决, 它同时挡住新旧两条路)

```
npx tsdown --env.DSH_BUILD_FACE host
ERROR  Error: [@deepseek-ai/dsh-root] Cannot find entry: ["lib/types/{index,invariant,startup}.js"]
```

**这个错在 `master` (旧 pin 0.1.5-rc.2) 上同样复现** —— 所以**不是 rebase 造成的**, 但它现在
把两条构建路一起挡住了

已经查明的边界:

- 仓库根**没有 `src/`**, 想不出 `lib/types/{index,invariant,startup}.js` 该由什么产生
- 全仓**没有任何东西 import `@deepseek-ai/dsh-root`**, 只有 release 脚本按名字提到它
- 根的 `package.json` 与 `tsdown.config.ts` 在新旧两版之间**逐字节相同**
- `lib/` 在 `.gitignore` 里
- 手工建了 `lib/types/index.js` (287 字节) **仍然报同一个错**
- 但用 **tsdown 自己那份 tinyglobby**、以仓库根为 cwd 跑同一个 glob, **能找到这个文件**

→ 说明 tsdown 解析根配置时用的 `cwd` **不是仓库根**, 这一点没查出来

**两个已知会踩的构建坑** (与上面那条无关, 但会让人误判):

- fork 的 `tools/pack-host.mjs` 里 `cleanBuildOutputs` 会删 `packages/*/*/lib` 等, 但**不删根
  `lib/`**
- `tsc -b` 会因为残留的 `tsconfig.host.tsbuildinfo` 认为一切已是最新而**跳过重建包的 `lib/`** ——
  实测必须先删 `tsconfig.host.tsbuildinfo` / `tsconfig.client.tsbuildinfo`, 包产物才会重新生成

### 4.4.0.2 0.2.0 的安卓阻塞: 缺 android-arm64 的原生件 (已修)

**现象**: 0.2.0 的 host 一启动就挂在

```
dsh: host preparation failed: No usable native binding found for
     node-addon-require-builtin-android-arm64 (auto)
```

**根因**: `packages/boot/app-boot` 把 `node-addon-require-builtin ^0.1.6` 列为硬依赖, 而上游的原生
平台包**只发 darwin-arm64 / darwin-x64 / linux-arm64 / linux-x64**, 没有 android-arm64; 它的
README 还写明 "consumer installation never builds native code", 所以装上也绝不会现编

**这个件是干什么的**: 它是通往 **Node 内部 ESM/CJS loader** 的一条路, 而那条路是
`profile-resolution` 的 `installRuntimeInterception()` 要用的 - 也就是"profile 的插件行通过装在
Node 解析器上的拦截来解析裸包名"那套机制。没有它, 那些行就解析不了

**修法** (`packages/boot/app-boot/src/profile-resolution/resolver.ts`, fork 提交 `03745c4c2f`):

1. **首选仍然是那个 addon** - 打包成可执行文件时只能靠它, 而且 darwin/linux 行为一个字没动
2. addon 不在时, 退回 **Node 自己的 `--expose-internals`** 直接 require 同一批 `internal/...`
   模块 id。**这一条是关键**: `DshHost.kt` 本来就已经在传 `--expose-internals` 了, 注释里写着
   正是这个 addon 在安卓上没有构建产物
3. 两条路都不通时, 装一个**空转的 interception** (三个成员全是 no-op), 让启动继续, 而不是抛
   fatal 终止

**实测** (`emulator-5554`, 用 logcat):

| 判据 | 结果 |
| :-- | :-- |
| `host preparation failed` | **没了** |
| `No usable native binding` | **没了** |
| `fatal uncaught` | **没了** |
| `failed to import` / `did not activate` | **没了** |
| 界面 | `htmlLength: 723163`, 会话列表里有已存的会话 |

**验证方法是隔离的**: 先只换 `files/host/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js`
这一个文件跑一遍, 确认纯代码修复就够了, 不是靠改 profile 蒙对的

**顺手查清的一个相关事实**: profile 的 `files/dsh-home/profiles/node_modules/@deepseek-ai/` 是一个
**软链接农场**, 指向安装树。它是在 profile 第一次创建时(0.1.5 时代)建的, 之后没有随 dsh 升级而
增补 - 实测它比安装树少 94 个包, 而 0.2.0 新增的那些插件行正是缺的那些。**在有 addon 的平台上,
拦截机制替它兜住了, 所以上游没发现**。完整化这个农场(实测补 94 个链接)也能让那些行激活, 但
**本修复没有走这条路**: 往用户的 profile 里写东西和本项目"profile 是用户自己的"那条设计相悖,
而且代码修复已经足够

### 批次 0 · 备份 (必做)

拷出 app 沙盒的 `files/dsh-home` 全量, **尤其 `credentials` 与 `sessions`**。
这是唯一能退回来的东西。**注意: 用户目前明确说过不需要备份**, 动手前再确认一次

### 批次 2 · 重打 host 树 (rebase 做完之后)

```powershell
node tools\pack-host.mjs --dsh third_party\deepseek-harness --out build\host-tree
```

### 批次 3 · 真机四级验证

1. 就绪行 `DshHost: dsh web: http://…?token=…`
2. `DshWebView: shell {...}` 里 `rootChildren` 非 0
3. 一次真实会话
4. 虚拟屏 + 特权通道

**哪级挂停哪级**

### 批次 4 · 推进 pin 并推 fork

`AGENTS.md` 写着「**pin 必须是 fork 上推过的提交**」: 只活在子模块工作区的改动, 克隆本仓的人看不见。
所以最后要对 `Miuzarte/deepseek-harness` 的 `master` 做一次 **force-push** ——
**这是不可逆且面向外部的动作, 必须用户明确同意**

### 回退

`third_party/deepseek-harness` 切回 `e79ffe35e1` 重新 pack 即可复原

## 4.5 风险表

| 风险点 | 症状 | 修复方向 |
| :-- | :-- | :-- |
| 会话格式 V3→V4 | 旧会话打不开 | **已降级** —— 迁移是"新增 V4 后继代际", 不覆写历史 |
| fork 安卓补丁失配 | host 起不来 / IO 错 | **实测必须手工带过去**, 上游没修 |
| rebase 本身 | 冲突判断错 | **实测冲突 15 处/提交量级, 拆跳做** |
| peer 依赖 | `ERESOLVE` | 已绕开 (webui 走隔离取包, 不进 dsh 闭包) |
| API 变更 | 插件 `inject` 服务名对不上 | **未核实**, 撞了当场看报错 |

---

# 五 · 待核实清单 (别当结论用)

| 项 | 状态 | 谁解决 |
| :-- | :-- | :-- |
| `DisplayManager` 包名来源 | **未核实** | 第一部分的批次 1 |
| `TRUSTED` 等 flag 卡在哪个权限判断 | **未核实** | 方向 B 才需要 |
| shell uid 在 `DisplayManagerService` 有无免检分支 | **未核实** | 见下 |
| 0.2.0-rc.2 相对 0.1.5-rc.2 的 API 变更 | **未核实** | 第四部分的批次 1 |
| 建屏成功后画面能否真渲染 | **未核实** | 第一部分的批次 3 |
| 后台存活行为 | **未核实** | 第一部分的批次 4 |

**关于 "Allow shell uid without checking the package name"**: 搜索能找到这个 commit
(aosp-mirror `fcef7515`, Bug 230779051), 看着完美支持方向 A。**但它改的是 `MediaSessionService`,
不是 `DisplayManagerService`** —— **不能当依据**。写在这里是防止它被误用

---

# 六 · 无障碍服务开不了 (已定论)

**现象**: 在应用里打开无障碍, 点返回再进来又变成关闭

**结论一句话**: 决定服务生死的是**安装者身份**加**一道 app op**, 而在**这台 vivo ROM 上应用自己写不动
secure settings** —— 写入只在带 `-i` 重装之后很短的一段窗口里被接受。所以这台机器上唯一的正解是
**电脑跑 `tools/lw-install.ps1`**, 应用侧负责把真实状态说清楚并把人指过去

## 6.1 五条实测 (设备 `10CEB40568000ZB`, 无 root 仅 Shizuku)

| # | 实测 | 结论 |
| :-- | :-- | :-- |
| 1 | 带 `-i com.android.packageinstaller` 装 → `installerPackageName` 非 null; 不带 `-i` 的普通 `install -r` 会把它打回 null | **身份是必要条件**: Android 13 起 installer 为 null 的应用不许开无障碍 |
| 2 | 带 `-i` 重装后第 9 秒写 secure settings → 留住了; 第 50 秒再写 → **又被丢弃** | 写入只在重装之后很短的一段窗口里有效, 所以"装"与"写回"要连着做 |
| 3 | 窗口外 uid 2000 (adb shell / Shizuku / 应用特权通道) 的 `settings put` 对**任何 key 都静默失效**: 退出码 0、读回不变; 自定义探针 key 在 secure / global / system 三个命名空间都读回 null, `delete` 报 0 rows | **丢弃发生在 provider 层**, 不是权限问题 —— 换 uid、换通道都没用 |
| 4 | 决定服务去留的是 app op `ACCESS_RESTRICTED_SETTINGS`: `allow` 留, `default` 被剥; 而 installer 只决定这道 op 能不能被设成 allow | 修法是**身份**, 不是那道 op |
| 5 | 打开 vivo 的无障碍设置页会把条目从 `enabled_accessibility_services` 里摘掉 (每次打开都摘), 但**不杀已经绑定的实例**: `ServiceRecord` 的 `createTime` 跨 20 分钟不变, 中间 31 秒脱离列表期间功能一直正常 | 那个页面的"已关闭"是它自己造成的假象; **判据是实例活没活** (`LwAccessibility.running`) |

第 4 条那张对照表 (同一台机器, 同一个设置页):

| 应用 | 包名 | 安装者 | op | 结果 |
| :-- | :-- | :-- | :-- | :-- |
| Luwi | `io.github.yuloong07star.luwi` | `com.android.packageinstaller` | `allow` | 留下 |
| DSHA | `com.dsh.client` | `com.android.packageinstaller` | `allow` | 留下 |
| UU 远程 | `com.netease.uuremote` | `com.bbk.appstore` | `default` | 被剥 |

**另外**: 这道 op 只有应用内开关那条路会去设 (`LwPermission.allowRestrictedSettings()`), 系统设置页
不会替我们设, 所以从设置页开的服务先天少一道保护

## 6.2 修法

**装机走一条命令, 顺序不能变, 而且要在窗口内跑完**:

```
pwsh -File D:\apk\Luwi\tools\lw-install.ps1 [-Serial <设备>] [-Perms]
```

它做六件事: ① 读出现有列表 (设备上还有别人的无障碍服务, 必须读出来改) ② `install -i … -r -t`
③ `cmd appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` ④ `settings put secure accessibility_enabled 1`
⑤ 立刻把组件写回 `enabled_accessibility_services` ⑥ **验收**: 探针写一次读回 (写入通路通不通) 加
`dumpsys activity services` 里找 `LwAccessibility` 的记录 (系统真的绑上了没)。没过就**非零退出**, 并
按缺的那一条打印下一步该跑什么

旧的那条硬规则 (「装的时候带 `-i`」) 不够 —— 身份只是必要条件

**应用侧**: 不再假装能自己开成功, 而是把事实读齐并照实说

- `LwPermission.inspect()` 一次读回六件事实: 组件在不在列表 / `accessibility_enabled` / 实例活没活 /
  `installerPackageName` / 那道 op 的值 / **写入通路通不通** (自己的探针 key 写一次读回再删掉)
- `AccessibilityState.reason()` 按上面的结果给**一句**结论, 不再把几种完全不同的原因并列在一句里
  (旧文案把"别的应用改了回去"与"受限设置挡着, 在应用详情里允许一次"并列, 而后者在这台机器上不是解)
- 设置页「无障碍」段显示这份状态, 外加一条**点一下就复制**的命令; 跳系统设置页那条入口留着并注明它在
  这台机器上没用 (别的 ROM 上仍然是有效路径)
- `lw_probe` 也带出同一份状态, 模型不用 dumpsys 就知道无障碍到底怎么回事

**为什么不做"应用启动时自动写回"**: 看起来是最省事的自恢复, 但第 3 条实测说明窗口外的写入**必然被
丢弃** —— 启动自检只能读到"不在列表里", 写不回去, 于是它只会每次启动都失败一遍。真正能写回的时机是
"重装之后那一刻", 而那正是脚本在做的事。所以应用侧的定位是**如实报告**, 不是自己解决

## 6.3 三个连带事实

- **`am force-stop <pkg>` 会把应用置为 stopped, 而 stopped 的应用不能持有无障碍服务** —— 系统会把它
  的条目剥掉 (DSHA 的无障碍就是这样被弄掉的, 当时只是为了腾出 3080 端口)。腾端口之前先想一下
- **凡是写入都要读回**: 退出码只说命令跑过了。`allowRestrictedSettings()` 早就改成读回判真实值, 现在
  `accessibility_enabled` 那条与探针也走同一条 (`putVerified` / `writeChannelOpen`)
- **别把"没声明"/"没授权"/"设备不接受"混成一句**: 三者的处置完全不同 (改清单 / 去点授权 / 去跑脚本)

---

# 七 · 动手前的检查清单

- [ ] `. D:\apk\env.ps1` 已 dot-source
- [ ] `files/dsh-home` 已备份 (含 credentials 与 sessions)
- [ ] `git -C D:\apk\Luwi status` 干净 (当前 HEAD `cf45649` + 1.0.2 未提交的那批)
- [ ] 确认 submodule pin 仍是 `03745c4c2f`
- [ ] 确认 Shizuku 在跑 (`ps -A | grep shizuku`)
- [ ] 确认 DSHA (`com.dsh.client`) **已停**, 否则它占 3080 端口
      (`adb shell am force-stop com.dsh.client`)
      —— **注意 force-stop 会连带把 DSHA 的无障碍弄掉线** (见第六部分末尾的 side effect)
- [ ] 无障碍那件事走 `tools\lw-install.ps1`, **不要手打 adb install**
- [ ] 确认 `svc power stayon` 的原值已记下 (收尾要写回)

---

# 八 · 相关文件

| 文件 | 作用 |
| :-- | :-- |
| `channel/LwVirtualDisplay.kt` | 特权侧建屏, **第一部分主要改动点** |
| `channel/VirtualScreen.kt` | app 侧对屏的 handle, `create()` 传 `null` surface |
| `channel/LwContext.kt` | 包名伪装 (`getPackageName` / `getOpPackageName`) |
| `channel/LwPrivilegedProcess.kt` | `LwContext` 的构造处 |
| `channel/LwServiceProtocol.kt` | 桥的协议, `createDisplay(width, height, dpi, surface)` |
| `ui/VirtualScreenPreview.kt` | 预览 `SurfaceView`, surface 生命周期来源 |
| `ui/HostScreen.kt` | **第二部分唯一改动点**; 也是 `lastError` 显示处 |
| `ui/AppNav.kt` | `Screen.Settings` 与导航, 不动 |
| `host/PluginOverlay.kt` | **第三部分改动点**, overlay 的 `insert` 行 |
| `tools/pack-host.mjs` | **第三部分改动点**, 依赖声明 |
| `AGENTS.md` | 项目的设计决定与约束, 改前先读 |
