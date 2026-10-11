# 随仓库发的插件包

这一层放**签好名、可以直接装的那几份 `.lwp`** (源在 `samples/*/plugin/`, 由 `tools/lw-plugin-sign.mjs`
签出来)。放这里的理由只有一个: 它们需要一个**稳定的 https 链接** —— 协议第 13 节那条"从链接安装"
要的就是一个能下到字节的地址, 而仓库里的文件天然有 (raw / jsDelivr / Release 都指向同一份)。

## 怎么装

Luwi → 设置 → 插件 → **从链接装入**, 把下面那一条粘进去。

| 包 | 它是什么 | 链接 |
| :-- | :-- | :-- |
| `art-whale-widget-2.0.0.lwp` | **鲸鱼娘 (art 域, 方案 v2)**: 装一份 `.lwp` 就完了, 桌面上不多一个应用 —— 桌面那一格由 Luwi 自己的宿主槽画 | `https://cdn.jsdelivr.net/gh/yuloong07-star/Luwi@main/plugins/art-whale-widget-2.0.0.lwp` |
| `art-ball-theme-1.0.0.lwp` | **浮标外观 (art 域, 方案 v2)**: 五个状态五张图, 装完在设置页「浮标外观」那一行挑; 读不到回内置 | `https://cdn.jsdelivr.net/gh/yuloong07-star/Luwi@main/plugins/art-ball-theme-1.0.0.lwp` |
| `inline-timer-1.0.0.lwp` | 番茄钟 (inline 域样例): 一份 dex 跑在 Luwi 的 `:plugin` 进程里, 会把状态推到图文槽上。**要先把作者的指纹加进 `.inline-allow.json`** | `https://cdn.jsdelivr.net/gh/yuloong07-star/Luwi@main/plugins/inline-timer-1.0.0.lwp` |
| `whale-widget-1.1.4.lwp` | 鲸鱼娘桌面小组件 (**旧那一片**, companion 那条路; 伴侣 APK 的地址写在包里, 装完顺手就装上) —— 已经被上面那份 2.0.0 取代 | `https://cdn.jsdelivr.net/gh/yuloong07-star/luwi-plugins@whale-widget-1.1.4/packages/whale-widget-1.1.4.lwp` |

**这一层只留最新那一份**: 旧的版本在 Releases 里 (每一份插件包都跟一版 Release 走)。链接也可以直接用
仓库原地址 (`https://raw.githubusercontent.com/yuloong07-star/Luwi/main/plugins/…`), 只不过在国内
这台开发机上它不通, 所以文档里写 jsDelivr 那一条 —— 协议那边认的是"任意 https", 两条都行。

**从链接装不比从本地装少验一样东西**: 下到 app 私有目录之后走的是同一条链 —— 验发布者签名与逐文件
哈希、查 protocol / `minLw` / 工具前缀冲突 / 未知能力, 全过才落地; 而**装完还要在设置页勾能力、
再启用**, 光装上什么都不做。

## 一条纪律

**私钥不进仓库** (它在开发机 `backups/` 里)。这一层只放签好的结果; 想重签一份就照
`archive/samples/whale-widget/plugin/README.md` 里那三条命令走一遍 (那一份样例已经归档, 想重新
构建它那个 APK 用 `luwi-plugins` 仓库里能构建的拷贝)。
