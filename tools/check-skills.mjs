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
  skills.includes('val apps = root["apps"] as? JsonArray ?: return emptyList()'),
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

const handled = ['scan', 'install', 'remove', 'index', 'search', 'upgrade', 'refresh']
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

check('装的时候核摘要与大小 (两个调用点都走同一个判据)', [
  skills.includes('internal fun matchesIndex('),
  (skills.match(/matchesIndex\(skill, file, bytes\)/g) ?? []).length,
], [true, 2])

check('相对路径的护栏 (不绝对 / 不含 .. / 不是账本自己)', [
  skills.includes('internal fun String.isSafeRelativePath(): Boolean'),
  skills.includes('!contains("..")'),
  skills.includes('this != META'),
], [true, true, true])

/* ── 五、设置页那两页与两份 strings ────────────────────────────────────── */

const settings = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const screens = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SkillsScreens.kt')
const nav = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/AppNav.kt')
const en = await read('app/src/main/res/values/strings.xml')
const zh = await read('app/src/main/res/values-zh/strings.xml')

// 主人 2026-10-17: 技能不再直接摆在技能段下面, 而是分两页 (已装 / 可用)
check('技能那一段只剩两颗入口, 各自推到一页', [
  settings.includes('navigator.push(Screen.SkillsInstalled)'),
  settings.includes('navigator.push(Screen.SkillsAvailable)'),
  settings.includes('R.string.settings_skills_installed'),
  settings.includes('R.string.settings_skills_available'),
  !settings.includes('AppSkills.scan('),
], [true, true, true, true, true])

check('两页都注册在导航上', [
  nav.includes('data object SkillsInstalled : Screen'),
  nav.includes('data object SkillsAvailable : Screen'),
  nav.includes('entry<Screen.SkillsInstalled>') || nav.includes('entry<Screen.SkillsInstalled>'),
], [true, true, true])

check('已装那一页: 检测更新 / 一键升级 / 卸载 三件都在', [
  screens.includes('fun SkillsInstalledScreen('),
  screens.includes('AppSkills.updates(context'),
  screens.includes('AppSkills.upgradeAll(context'),
  screens.includes('AppSkills.remove(context, listOf(skill.id))'),
], [true, true, true, true])

check('可用那一页: 刷新技能 / 全部安装 / 挑一条装 (或升级) 三件都在', [
  screens.includes('fun SkillsAvailableScreen('),
  screens.includes('AppSkills.refresh(context)'),
  screens.includes('AppSkills.installAll(context'),
  screens.includes('if (skill.id in installed) upgrade(skill, false) else install(skill)'),
], [true, true, true, true])

check('升级时"本地改过"那一问有两个选择 (留我的 / 连它们一起盖)', [
  screens.includes('R.string.settings_skills_browse_edited_keep'),
  screens.includes('R.string.settings_skills_browse_edited_overwrite'),
  screens.includes('upgrade(skill, true)'),
], [true, true, true])

check('AppSkills 这一侧补了那四件 (列表 / 更新 / 一键升级 / 全部安装)', [
  skills.includes('data class Installed('),
  skills.includes('fun installed(context: Context): List<Installed>'),
  skills.includes('fun updates(context: Context, index: List<RemoteSkill>): List<Update>'),
  skills.includes('fun upgradeAll(context: Context, index: List<RemoteSkill>): List<InstallReport>'),
  skills.includes('fun installAll(context: Context, index: List<RemoteSkill>): List<InstallReport>'),
], [true, true, true, true, true])

/** 两份 strings 里同一组键的个数与顺序必须一致 */
const groupOf = (text, prefix) => [...text.matchAll(new RegExp(`<string name="(${prefix}[a-z_]*)`, 'g'))].map((one) => one[1])
for (const prefix of ['settings_skills_', 'skills_']) {
  const left = groupOf(en, prefix)
  const right = groupOf(zh, prefix)
  check(`两份 strings 里 ${prefix}* 那一组的键与顺序一致`, [left.length > 0, left.join(',')], [true, right.join(',')])
}
/* ── 六、索引自己给的那几条正文信息 (2026-10-17 取回真索引之后加的) ────────── */

check('老形状那条也读 description / risk / sha256(剥前缀) / size / url / mirrors', [
  skills.includes('description = item["description"]'),
  skills.includes('removePrefix("sha256:")'),
  skills.includes('size = item["size"]'),
  skills.includes('url = item["url"]'),
  skills.includes('mirrors = jsonStrings(item["mirrors"])'),
], [true, true, true, true, true])

check('取正文的顺序是 主地址 -> 镜像 -> contents API, 而且只认 https', [
  skills.includes('internal fun sourcesFor(skill: RemoteSkill, path: String): List<String>'),
  skills.includes('if (!url.startsWith("https://")) return null'),
], [true, true])

check('摘要与大小两道闸都在 (单文件时才核大小)', [
  skills.includes('internal fun matchesIndex(skill: RemoteSkill, file: RemoteFile, bytes: ByteArray): Boolean'),
  skills.includes('bytes.size.toLong() != skill.size'),
], [true, true])

check('刷新先试索引自己写的 index_url / index_mirrors, 再落到 API', [
  skills.includes('private const val DEFAULT_INDEX = "https://fastly.jsdelivr.net/gh/yuloong07-star/app-skills@index-1/index.json"'),
  skills.includes('root["index_url"]'),
  skills.includes('addAll(jsonStrings(root["index_mirrors"]))'),
], [true, true, true])

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)