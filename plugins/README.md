# plugins —— 这里不再放 `.lwp`

**插件包的家只有一个: [yuloong07-star/luwi-plugins](https://github.com/yuloong07-star/luwi-plugins)** 的
`packages/` (源在它的 `samples/*/plugin/`, 由 `tools/lw-plugin-sign.mjs` 签出来)。

Luwi 这一层从 2026-10-11 起**不再放签好的包** —— 那是插件生态的东西, 不该往应用仓库里塞 (主人当天的
口径: 不要放 Luwi 仓库)。这个目录只留这一页指路, 免得旧文档里那些 `plugins/…` 的地址看起来像还在。

## 怎么装

Luwi → 设置 → 插件 → **从链接装入**, 把下面那一条粘进去 (都钉在内容 tag 上: jsDelivr 对 tag 引用
才缓存, `@main` 那种分支引用会 301 到 raw, 而 raw 在常见网络里不通)。

| 包 | 它是什么 | 链接 |
| :-- | :-- | :-- |
| `art-whale-widget-2.0.0.lwp` | **鲸鱼娘 (`art` 域)**: 零代码、零能力, 桌面那一格由主 APK 的宿主槽画 | `https://cdn.jsdelivr.net/gh/yuloong07-star/luwi-plugins@plugins-2.7.6/packages/art-whale-widget-2.0.0.lwp` |
| `art-ball-theme-1.0.0.lwp` | **浮标外观 (`art` 域)**: 五个状态五张图, 装完在设置页「浮标外观」那一行挑 | `https://cdn.jsdelivr.net/gh/yuloong07-star/luwi-plugins@plugins-2.7.6/packages/art-ball-theme-1.0.0.lwp` |
| `inline-timer-1.0.0.lwp` | 番茄钟 (`inline` 域样例)。**要先把作者指纹写进 `.inline-allow.json`**, 否则会被拒 | `https://cdn.jsdelivr.net/gh/yuloong07-star/luwi-plugins@plugins-2.7.6/packages/inline-timer-1.0.0.lwp` |
| `whale-widget-1.1.4.lwp` | 旧那一片 (companion 那条路; 伴侣 APK 的地址写在包里) —— 已被上面那份 2.0.0 取代 | `https://cdn.jsdelivr.net/gh/yuloong07-star/luwi-plugins@whale-widget-1.1.4/packages/whale-widget-1.1.4.lwp` |

`cdn.jsdelivr.net` 不通时换边缘域名即可: `fastly.` / `gcore.` / `testingcf.` 三个前缀都行, 内容同一份。

**从链接装不比从本地装少验一样东西**: 下到 app 私有目录之后走的是同一条链 —— 验发布者签名与逐文件
哈希、查 protocol / `minLw` / 工具前缀冲突 / 未知能力, 全过才落地。

**私钥不进任何仓库**: 想重签一份, 照 `luwi-plugins` 各样例 README 里那几条命令走一遍。
