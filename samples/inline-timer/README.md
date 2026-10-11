# 番茄钟 (inline 插件样例)

协议 `kind: inline` 的实物: **一份 dex 直接跑在 Luwi 自己的 `:plugin` 进程里**, 不装第二个 APK。

选型规矩里它排第二条 —— "能写成 `inline` 的, 绝不写伴侣"。它能加的是: 新工具、新监测器、有逻辑的
面板、**会自己变的桌面组件** (走 `ui.widget` 那条能力往宿主槽里推内容)。

## 包里有什么

| 在哪 | 是什么 |
| :-- | :-- |
| `src/io/example/timer/TimerPlugin.java` | 插件本体: 一个 `ILwPlugin` 的实现, 三个工具 |
| `plugin/plugin.json` | 清单: `kind: inline`, `entry.dex` + `entry.class` |
| `plugin/widget/widget.json` | 它在**图文槽**上写的那一份静态内容 (动态那半由 `ui.widget` 推) |
| `plugin/main.dex` | `tools/build-dex.ps1` 从上面那份 Java 编出来的 (产物, 进签名载荷) |

## 三个工具

| 工具 | 干什么 |
| :-- | :-- |
| `timer_start` | 开一轮, 并把"在跑"推到桌面上那一格 |
| `timer_state` | 报现在跑到哪了 |
| `timer_boom` | **故意抛一个异常**: 它是"插件抛异常时 Luwi 不受影响"那一条的读数 |

## 装它之前: 白名单那一步

`inline` 与 Luwi **同一个 uid** (同一个进程空间), 同 uid 的代码可以反射拿到 Luwi 的一切 —— 运行时
那几道能力检查对它而言是**体验, 不是墙**。所以这一档**只收白名单作者的包**:

```sh
# 先把发布者指纹算出来 (与插件页显示的那一串、白名单里那一串是同一个算法)
node tools/lw-plugin-sign.mjs keygen --out <私钥>       # 新作者先 keygen
node tools/lw-plugin-sign.mjs sign --dir plugin --key <私钥>   # 它会把指纹打出来

# 把那一串写进设备上的白名单
adb shell "run-as io.github.yuloong07star.luwi sh -c 'mkdir -p files/dsh-home/plugins'"
# 或者用 Luwi 的桥上那一条 (文件落在 \$DSH_HOME/plugins/.inline-allow.json):
#   { "allowed": ["sha256:…"] }
```

不在名单里的那一份**装的时候就拒**, 而且话术说的是"这个作者不在白名单里", 不是"格式不对"。
**开发者模式也不开这个口子** —— 它只放未签名的本地包, 白名单照样要过。

## 重出 main.dex

```powershell
cd samples\inline-timer
pwsh -File tools\build-dex.ps1
```

它干三件事: 编 `lwplugin-api` (拿那份 classes.jar 当编译期依赖) → `javac` 编这一份 → `d8` 出 dex。
**dex 里只有插件自己的类**, API 那些类由宿主提供 (同一个进程里就是 Luwi 自己那份)。
