# 番茄钟 (inline 域)

协议第 8.2 节那一条 (`kind: inline` / 老名字 `dex`) 的实物: 一份 `main.dex` 跑在 Luwi 的
`:plugin` 进程里, 与 Luwi **同一个 uid**。

## 两条要记住的

1. **它只对白名单作者开放** (方案 3.3): 与 Luwi 同签名的包, 或者主人手工写进
   `$DSH_HOME/plugins/.inline-allow.json` 的公钥指纹。别的 `inline` 包装的时候就拒
2. **能力检查对它是体验不是墙**: 同 uid 的代码可以反射拿到 Luwi 的一切 —— 所以这一档的定位是
   "主人自己的代码", 不是"更方便的伴侣"。第三方能投的是 `art` (零代码) 与 `companion` (独立 uid)

## 它长什么样

```
plugin.json          kind: inline, entry.dex = main.dex, entry.class = io.example.timer.TimerPlugin
widget/widget.json   它往图文槽里填的那一份静态内容 (动态那半由 ui.widget 推)
main.dex             tools/build-dex.ps1 的产物
```

插件的类**必须**是 `ILwPlugin` 的子类, 而且 `describe()` 要回与 `plugin.json` 逐项一致的那一份
(id / version / api / 工具名集合 / 能力集合) —— 对不上就不启用, 那一步是"这个包与这份代码是一对"
的唯一凭据。
