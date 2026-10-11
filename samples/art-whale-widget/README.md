# 鲸鱼娘 (art 插件, 方案 2.0.0)

这是"安装即用"那条路的实物: **装一份 `.lwp` 就完了, 桌面上不多一个应用**。

老那一片 (companion 那条路, 现在已经收进 `archive/samples/whale-widget/`) 里鲸鱼娘是在**伴侣 APK** 里画的, 于是想往桌面
放一只会动的鲸鱼娘, 编辑上必须再装第二个 APK。这一份反过来: 桌面组件由 **Luwi 主 APK** 的清单声明
(方案 3.1 的"宿主槽"), 插件只是一包资源 —— `kind: art`, **一条能力都不要**, 零代码。

## 包里有什么

| 在哪 | 是什么 |
| :-- | :-- |
| `plugin/plugin.json` | 清单: `kind: art`、`minLw`、前缀; 能力与工具都是空的 |
| `plugin/widget/widget.json` | 上桌面的那一份: 占 `anim` 槽、两套帧 (正常 / 慢放)、点一下换下一套 |
| `plugin/art/dance-1/` | 正常那一套的 32 帧 (`frame-00.png` …) |
| `plugin/art/dance-1-slow/` | 慢放那一套: 同一组帧, 由 `widget.json` 里的 `fps` 说了算 |
| `source/dance-1.gif` | 帧的来源 (主人给的那一份动图), **不进包** —— 只用来生成上面那两组帧 |
| `tools/build-frames.py` | 从 `source/` 抽帧的那一条命令 |

## 装它

Luwi → 设置 → 插件 → **从链接装入**, 粘 `luwi-plugins` 仓库里那一份的地址
(`…@plugins-2.7.6/packages/art-whale-widget-2.0.0.lwp` —— 插件包只住那个仓库, Luwi 这边不放);
装完 Luwi 会自己把 `anim` 槽挂到它, 并弹一句"要放到桌面吗" (系统那张"添加到主屏幕"的确认卡)。

**同一时刻只有一只插件能占 `anim` 槽** (同槽互斥): 装了两只会动的插件, 桌面上只能上其中一只,
另一只留在插件页里等主人改主意 —— 这是设计不是缺陷 (见方案 3.1)。

## 重出一份

```powershell
cd samples\art-whale-widget
python tools\build-frames.py
node ..\..\tools\lw-plugin-sign.mjs sign --dir plugin --key ..\..\..\backups\lw-publisher-key.pem `
  --out ..\..\.lwtmp\art-whale --zip ..\..\.lwtmp\art-whale\art-whale-widget-2.0.0.lwp
```

签名的私钥在开发机的 `backups/` 里, **不进仓库**。
