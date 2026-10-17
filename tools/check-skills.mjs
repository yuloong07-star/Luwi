/**
 * 技能仓库浏览安装那一条链的判据表 (2.7.8 批次 5)
 *
 * 这条链有**四处接缝**, 每一处漂开了都不会当场报错, 只会在设备上变成"面板里什么都列不出来"或者
 * "升级把主人改过的那一个文件盖掉了":
 *
 *   1. **索引的两种形状**: 新的是 `skills[]` (带 `version` / `description` / `files[]`), 老的是
 *      `apps[]` (一个包一条)。两条都得认 —— 仓库那一侧还没换成新的之前, 老的 Luwi 也得能用
 *   2. **桥与应用两侧的 op 名单**: `AppSkills.dispatch` 认哪几个, 模型那个 `lw_skill` 就只该给哪几个
 *   3. **升级的判据**: 只盖"本地摘要 == 账上摘要"的文件, 被本地改过的记进 `skipped` 让人去问,
 *      `force` 才盖 —— 这一条错了就是悄悄吃掉主人的改动
 *   4. **那一本账的名字**: `.lw-meta.json`, 写入方与读取方各写一遍的话总会有人写错一个字
 *
 * 不需要设备, 也不需要 host: 全是拿真源码对着核。
 *
 * 用法: node tools/check-skills.mjs
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const skills = await read('app/src/main/java/io/github/yuloong07star/luwi/host/AppSkills.kt')
const bridge = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const plugin = await read('host-plugin/index.mjs')
const packer = await read('tools/pack-host.mjs')

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(
    `${same ? 'ok  ' : 'FAIL'} ${name}`
      + (same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`),
  )
}

/* ── 一、索引用两种形状 ───────────────────────────────────────────────── */

check('解析先认 skills[], 认不到再退回 apps[]', [
  skills.includes('val skills = root["skills"] as? JsonArray'),
  skills.includes('return remoteEntries(text).map { entry ->'),
], [true, true])

check('新形状的字段逐个读到 (id/name/description/version/apps/files/risk)', [
  'name',
  'description',
  'version',
  'files',
  'apps',
  'risk',
].every((key) => skills.includes('item["' + key + '"]')), true)

check('文件那一层读到 path 与 sha256 (摘要按小写比)', [
  skills.includes('RemoteFile(path, file["sha256"]'),
  skills.includes('?.lowercase()'),
], [true, true])

check('老形状的映射一个字没改 (remoteEntries 还在, 单测还在用它)', [
  skills.includes('internal fun remoteEntries(text: String): List<Entry>'),
  skills.includes('skills/<id>/SKILL.md'),
], [true, true])

/* ── 二、op 名单: 桥 / AppSkills / 模型那一侧 ──────────────────────────── */

const bridgeOp = /"skill" -> appContext \{ AppSkills\.dispatch\(it, request\) \}/.test(bridge)
check('桥把 skill 这一条交给 AppSkills.dispatch', bridgeOp, true)

const handled = ['scan', 'install', 'remove', 'index', 'search', 'upgrade']
check(
  'dispatch 认这六个 op',
  handled.map((op) => skills.includes(`"${op}"`) || skills.includes(`"${op}",`)),
  handled.map(() => true),
)
check(
  '不认识的 op 会把整份名单念出来 (而不是只说一句错)',
  skills.includes('"skill 这个桥方法的 op 是这些:'),
  true,
)

const skillEnum = plugin.includes("enum: ['scan', 'install', 'remove']")
check('模型那一侧只给三个动手的 op (index/search 是只读, 不必占一项)', skillEnum, true)
check('lw_skill 的说明里点明"不覆盖已经存在的技能"', plugin.includes('never touches a skill that is already there'), true)

/* ── 三、升级只盖没被本地改过的文件 ───────────────────────────────────── */

check('升级的判据是"本地摘要 == 账上摘要"', [
  skills.includes('val known = meta?.files?.get(file.path)'),
  skills.includes('!force && local != null && known != null && local != known'),
  skills.includes('skipped += file.path'),
], [true, true, true])

check('force 只在调用方明说时才为真 (字符串 true, JSON 布尔也走这一条)', skills.includes('request["force"]?.jsonPrimitive?.contentOrNull == "true"'), true)

check('账本名字只有一处', [
  (skills.match(/private const val META = "\.lw-meta\.json"/g) ?? []).length,
  (skills.match(/"\.lw-meta\.json"/g) ?? []).length,
], [1, 1])

check('账本原子写 (tmp 再改名)', [
  skills.includes('File(directory, "$META.tmp")'),
  skills.includes('temp.renameTo(file)'),
], [true, true])

/* ── 四、索引说得出摘要就核摘要, 路径不许跑出去 ────────────────────────── */

check('装的时候核 sha256', [
  skills.includes('if (file.sha256.isNotEmpty() && digest != file.sha256)'),
  (skills.match(/digest != file\.sha256/g) ?? []).length,
], [true, 2])

check('相对路径的护栏 (不绝对 / 不含 .. / 不是账本自己)', [
  skills.includes('internal fun String.isSafeRelativePath(): Boolean'),
  skills.includes('!contains("..")'),
  skills.includes('this != META'),
], [true, true, true])

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
