# 技能仓库的索引格式 (app-skills 那一侧要照着改的那一份)

2026-10-17, 2.7.8 批次 5 定下来的形状。写在 Luwi 仓库里是因为**改的是另一个仓库**
(`yuloong07-star/app-skills` 的根下 `index.json`), 而那一份不在这个工作区里 —— 这一段就是拿过去
照着改的唯一依据。

## 一句话

**保留老的 `apps[]` 一个字不动, 另加一段 `skills[]`**。老的 Luwi 只读 `apps[]`, 新的先读 `skills[]`,
读不到才退回 `apps[]` —— 于是仓库换成新格式的那一刻, 老版本的人不会坏。

## 长什么样

```json
{
  "repo": "app-skills",
  "updated": "2026-10-18",
  "apps": [
    { "id": "wechat", "name": "微信", "package": "com.tencent.mm", "path": "skills/wechat/SKILL.md" }
  ],
  "skills": [
    {
      "id": "wechat",
      "name": "微信",
      "description": "发消息、看朋友圈、收付款",
      "version": "1.2.0",
      "apps": ["com.tencent.mm"],
      "risk": "low",
      "danger_steps": [],
      "tools_used": ["lw_ui", "lw_tap", "lw_type"],
      "status": "verified",
      "files": [
        { "path": "SKILL.md", "sha256": "6f1e…" },
        { "path": "scripts/tap-send.sh", "sha256": "aa11…" }
      ]
    }
  ]
}
```

## 每一栏的规矩

| 栏 | 必填 | 用途 |
| :-- | :-- | :-- |
| `id` | 是 | 技能目录名 (`$DSH_HOME/skills/<id>/`)。**只许是目录名**: 不带 `/` `\`, 不是 `.` / `..` |
| `name` | 否 | 显示名, 缺了用 `id` |
| `description` | 建议 | 面板那一行的一句话与搜索的第三个字段 |
| `version` | 建议 | 升级判据的一半: 面板上写「已装 x.y.z · 远端 a.b.c」就是靠它 |
| `apps` | 否 | 包名列表; 与老 `apps[]` 一个口径 (命中才算"这台机器上有这个应用") |
| `files[]` | 建议 | 每个文件一条: `path` (相对技能目录, 不许 `..` 与绝对路径) + `sha256` (小写十六进制) |
| `risk` / `danger_steps` / `tools_used` / `status` | 否 | 沿用仓库里已有的那几栏, 客户端现在只显示 `risk` |

## 摘要要真算

`files[].sha256` **不是装饰**: 装的时候取回来的字节与它不一致, 那一个文件就不落地 (而不是装个
半新半旧的技能); 升级的时候它又是"上游到底改没改"的判据之一。算的时候是对**文件原文的字节**算:

```bash
sha256sum SKILL.md            # 十六进制那个, 小写
```

## 与本地那一本账的关系

装上之后客户端在 `$DSH_HOME/skills/<id>/.lw-meta.json` 里记一份 `{skill, version, at, files:{路径:摘要}}`。
升级只盖"本地文件摘要 == 账上摘要"的那几个; 主人改过的文件不盖, 面板上会单独问一句
(「本地改过的那几个文件」), 确认之后才用 `force` 覆盖。

所以**同一个技能改了内容就要抬 `version`**, 否则面板上那句「已装 1.2.0 · 远端 1.2.0」永远不变,
主人也就没有理由点升级。
