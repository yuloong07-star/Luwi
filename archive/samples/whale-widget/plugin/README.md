# 鲸鱼娘桌面小组件 (companion 插件)

> **这一份已经归档** (2026-10-11, 方案 v2 "安装即用"): 桌面组件现在默认走**主 APK 的宿主槽**,
> 新的那一份在 `samples/art-whale-widget/` (零代码、零第二次安装)。这一份留在 `archive/` 里当
> 旧路的实物 —— 它**不再是 `settings.gradle.kts` 里的模块**, 想重新出那个 APK 用
> `luwi-plugins` 仓库里那份能构建的拷贝; 下面这些路径照当时的样子保留

这是协议第 10.4 节那条路的第一份实物: **桌面组件只能由伴侣 APK 声明**, 所以"把会动的鲸鱼娘放在
vivo 桌面上、并且能在桌面上切换"这件事只有一个正当落点 —— 一个伴侣 APK。

## 三样东西

| 在哪 | 是什么 |
| :-- | :-- |
| `archive/samples/whale-widget/src/main/java/…` | 伴侣本体: 一个 `AppWidgetProvider`、一个自带界面、一个实现 `ILwPlugin` 的 Service |
| `archive/samples/whale-widget/src/main/res/drawable-nodpi/` | 三套动作那一份**真的动态 GIF** (桌面组件吃的就是它) |
| `archive/samples/whale-widget/plugin/` | 装进 LW 的那一份包 (`plugin.json` + 这份 README) |

## 那只会动的鲸鱼娘是怎么动的

**动的是启动器那一侧, 我们不常驻任何进程**: 交出去的是动态 GIF 的**资源 id**
(`RemoteViews.setImageViewResource`), API 28 起它会被解成 `AnimatedImageDrawable`, 由宿主自己播。
桌面小组件最怕的"后台被冻住动画就停了"在这里不存在 —— 没有定时推帧, 也没有前台服务。

遇到不吃 GIF 的桌面, 伴侣界面里有一个开关换走**逐帧**那条路 (`<animation-list>`, 一份帧图加一份
XML), 代价只是包大一点。

## 随包那一套

| 键 | 名字 | 来源 |
| :-- | :-- | :-- |
| `dance-1` | 吹蒲公英 | 主人自己那份参考动图 (`art/dance-1.gif`), 由 `tools/build-assets.py` 抽成 32 帧进包 |

**随包只留这一套是刻意的** (主人 2026-10-10): 新动作不预生成, **用户自己导**。所以这一份包里没有
"凑数的三套", 桌面上能切几套取决于主人导了几份。

## 自己导一份进来

伴侣界面里那颗「导入一份 GIF」打开系统文件选择器, 选中那一份会被**抄进 app 私有目录**并当场解成
逐帧图 (`filesDir/whale-imports/<键>/`), 然后**立刻切到桌面上**。原来那个文件一个字节都不动, 所以
「删掉」删的只是这一份副本。

两条要记住的:

- **导入那一份走内容 URI, 不走位图**: 32 张图塞进一次 binder 事务会撞上 1 MB 的上限, 而 URI 只
  过一个短字符串, 图由桌面那一侧自己解 (只读的 provider 见 `WhaleFramesProvider`)
- **帧数是解的时候定的**: 按那一份自己的时长推, 4 到 32 帧之间, 一帧 60 到 300 毫秒 —— 桌面上
  那一格看不出比这更细的差别
- **读权限要自己放**: provider 是 `exported=false` 的, 那条 URI 的读权限由
  `WhaleWidgetProvider.grantFramesToHosts` 放给"现在正在当桌面"的那几个应用。忘了放, 桌面那一格
  会变成「Can't load widget」(2026-10-10 就是这么撞的); 而**找"谁是桌面"还需要清单里那段
  `<queries>`** —— targetSdk 33 起不声明就看不见别的包, 那一段没了这一步会静默地什么都不放

## 怎么装

**粘一条链接就够**: `luwi-plugins` 仓库的 `packages/README.md` 有一份签好名的包与它的 https 地址, Luwi 的设置页
→ 插件 → 「从链接装入」粘进去就行 (下下来之后走的是与本地包完全同一条校验链)。这一份包的
`entry.apk` 写着它自己那个伴侣 APK 的地址 (2026-10-11 加的, 协议第 8.1 节), 所以**装完 `.lwp`
会顺手把伴侣也装上** —— 有 root / Shizuku 时静默装, 没有时交给系统安装器让你点一下, 这一份一条
能力都不要, 所以没有要勾的。下面这三步是从零构建那一份时的走法。

**那两条地址都在插件生态仓库** (`yuloong07-star/luwi-plugins` 的 `packages/`) **走 jsDelivr**: 这台
开发机与常见的国内网络都连不上 `github.com` 的 Release 直链 (2026-10-11 在真机上实测: ICMP 通而
443 超时), 而 jsDelivr 是通的 —— 协议那边认的是"任意 https", 换一条能下到字节的就行

```powershell
# 1. 装伴侣 APK (同签名那一档, 系统自动给 signature 级权限)
#    **这一份已经不是本仓库的 Gradle 模块了**; 能构建的拷贝在 luwi-plugins 仓库的
#    samples/whale-widget/ 下: ./gradlew :sample-whale-widget:assembleDebug
D:\apk\Sdk\platform-tools\adb.exe -s emulator-5554 install -r `
  <你构建出来的>sample-whale-widget-debug.apk

# 2. 签一份包出来 (私钥落 .lwtmp, 不进仓库)
node D:\apk\Luwi\tools\lw-plugin-sign.mjs keygen --out D:\apk\.lwtmp\whale\whale.key
node D:\apk\Luwi\tools\lw-plugin-sign.mjs sign --dir D:\apk\Luwi\archive\samples\whale-widget\plugin `
  --key D:\apk\.lwtmp\whale\whale.key --name 样例发布者 `
  --out D:\apk\.lwtmp\whale\signed --zip D:\apk\.lwtmp\whale\whale-widget.lwp

# 3. 把包推到手机上交给 LW (这条链与样例伴侣那条一模一样)
D:\apk\Sdk\platform-tools\adb.exe -s emulator-5554 push D:\apk\.lwtmp\whale\signed /sdcard/DSH/plugins-whale
pwsh -File D:\apk\dev.ps1 bridge -Method plugin -Params '{\"op\":\"install\",\"path\":\"/sdcard/DSH/plugins-whale\"}'
pwsh -File D:\apk\dev.ps1 bridge -Method plugin -Params '{\"op\":\"enable\",\"id\":\"io.github.yuloong07star.luwi.sample.whalewidget\"}'
```

**它一条能力都不要** (协议第 5 节那张表里一条都不声明): 小组件画的是自己的动图, 切哪一套也是本地的
偏好。所以它是"零能力伴侣"那一档的样例 —— 装上就能用, 一个勾选框都不用点。

## 怎么把她放到桌面上

1. 先在伴侣界面里选好哪一套 (三种都能当场预览)
2. 长按桌面空白处 → 小组件 (vivo 上叫卡片) → 找「Luwi 鲸鱼娘」→ 把「鲸鱼娘」拖到桌面上
3. 点桌面上的她一下 = 换下一套; 伴侣界面里选哪一套 = 桌面上当场跟着换

模型那一侧也有三条工具: `whale_list` / `whale_next` / `whale_set` —— 切完桌面上那只当场重画。
