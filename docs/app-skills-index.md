# 技能仓库的索引格式 (app-skills 那一侧)

2026-10-17, 2.7.8 批次 5 定下来的形状。写在 Luwi 仓库里是因为**改的是另一个仓库**
(`yuloong07-star/app-skills` 的根下 `index.json`)。

## 先说结论: 现在**不改也能用**

把远端那份真索引取回来量过 (2026-10-17, 34,486 字节, `format: app-skills-index/1`, 20 条):

| 索引里已经有的 | 客户端已经怎么用 |
| :-- | :-- |
| 每条 `description` / `risk` / `status` / `tools_used` / `danger_steps` | 面板上的描述、搜索的第三个字段、风险那一行 |
| 每条 `url` + `mirrors[]` + `sha256` (`sha256:` 前缀) + `size` | 正文去 jsDelivr 取 (不必走 api.github.com); 装之前核**摘要与大小** |
| 顶层 `index_url` + `index_mirrors` | 刷新索引时按这几条挨个试 (`cdn` 在主址名单里排第二, 它在本机网络上 SSL 会失败) |
| **没有**逐技能的 `version` | 面板上「已装 x · 远端 a」两边都写 `?`; 要真版本号得加下面那一栏 |

所以**只有一件事值得改**: 给每一条加一个 `version`。加了之后面板能显示真实的版本对比,
升级那一颗也就有了判据; 不加也不会坏, 只是"已装 / 远端"两半永远是问号。

## 只加一栏的话: `version`

```json
{
  "id": "android-vivo-calculator",
  "name": "计算器",
  "package": "com.android.calculator2",
  "version": "1.1.0",
  "...": "其余各栏原样不动"
}
```

**改内容就要抬 version**: 面板那句「已装 1.1.0 · 远端 1.2.0」是主人点「升级」的唯一理由;
内容改了而不抬版本, 面板上永远显示"已经是最新的"。

## 客户端怎么判断"要不要盖"

装上之后客户端在 `$DSH_HOME/skills/<id>/.lw-meta.json` 里记一份 `{skill, version, at, files:{路径:摘要}}`。
升级只盖"本地文件摘要 == 账上摘要"的那几个; 主人改过的文件不盖, 面板上单独问一句, 确认之后才用
`force` 覆盖 (`AppSkills.upgradeFrom`)。

## 多文件 (`skills[]`) —— 现在用不上, 留着后路

索引另一种形状 (`skills[]` + `files[]`) 客户端也认: 一个技能可以带多个文件 (脚本之类), 每个文件
一条 `{path, sha256}`, `path` 必须是相对技能目录、不含 `..`。现在仓库里 20 条都是单文件 (`SKILL.md`),
走 `apps[]` 那条路就够; 哪天某个技能需要一个脚本, 再把它挪进 `skills[]`。
