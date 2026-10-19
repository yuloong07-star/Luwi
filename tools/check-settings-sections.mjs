/**
 * 设置页「哪些段缺省展开」那一份名单的判据 (2.7.8 批次 1, 第 1 条 / 决策闸 D1 + D2)
 *
 * 这一批改的是一条**很安静**的东西: `collapsed` 的初值从空集 (缺省全展开) 换成
 * "页面上所有段 - 白名单" (缺省只展开省电与「常用」那六段)。两个做错的样子都不响亮:
 *
 *   1. 白名单里多写一个 / 少写一个段名 —— 那一段的方向反了, 界面上只是"它开着"或"它收着"
 *   2. 页面上新加一段而忘了加进 `SettingsSectionKeys` —— 它会**恰好展开着** (与 D2 那条
 *      "新加的段就收着"相反), 而且 `SettingsSectionsTest` 也看不出来 (那份测的是纯函数,
 *      不知道页面上真有几段)
 *
 * 所以这份脚本把**页面里那 18 个 `settingsSection(key = …)` 的实况**与名单逐个对齐 (顺序也管),
 * 与 `check-automations.mjs` / `check-mode-model.mjs` 那种"两处名单必须一致"同一个道理
 *
 * 跑法: node tools/check-settings-sections.mjs   (不需要设备)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const screen = await readFile(
  new URL('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt', root),
  'utf8',
)

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

/** 那一份 `listOf("a", "b", …)` 的字面量 (按声明里的顺序) */
function listOf(name) {
  const at = screen.indexOf(`val ${name}`)
  if (at < 0) return null
  const body = screen.slice(at, screen.indexOf('\n)', at))
  return [...body.matchAll(/"([a-z-]+)"/gu)].map((m) => m[1])
}

const open = listOf('SettingsSectionsAlwaysOpenKeys')
const ability = listOf('AbilitySectionKeys')
/** 页面实况: 每个 `settingsSection(` 后面那个 key, 按出现顺序 */
const page = [...screen.matchAll(/settingsSection\(\s*key = "([a-z-]+)"/gu)].map((m) => m[1])

/* ── 一、名单本身 ─────────────────────────────────────────────────────── */

check('白名单就是 D1 那六段 (省电 + 常用五段; 导航那一段 2026-10-17 撤了)', open, [
  'power', 'appearance', 'channel', 'ball', 'wake', 'speak',
])

check('能力那一组 12 段 (无障碍与 OCR 2026-10-19 并进「特权通道」了)', ability, [
  'notifications', 'screenshot', 'look', 'lock',
  'explore', 'automation', 'quick', 'plugin', 'skills', 'linux', 'sync', 'permissions',
])

check('页面上真是 18 段', page.length, 18)

check('两份名单拼起来与页面逐个对齐 (顺序也管)', [...(open ?? []), ...(ability ?? [])], page)

check('白名单里没有重复的段名', new Set(open ?? []).size, (open ?? []).length)

check('两份名单不相交', (ability ?? []).filter((key) => (open ?? []).includes(key)), [])

/* ── 二、算缺省那一份的写法 ───────────────────────────────────────────── */

check('缺省那一份按"全部 - 白名单"算', /\(all - expanded\)\.toSet\(\)/u.test(screen), true)

check('缺省的默认参数就是白名单', /expanded: Set<String> = SettingsSectionsAlwaysOpen,/u.test(screen), true)

check('`collapsed` 的初值走那个函数, 不是空集', [
  /mutableStateOf\(initialCollapsed\(sessionCollapsed\)\)/u.test(screen),
  /mutableStateOf\(emptySet<String>\(\)\)/u.test(screen),
], [true, false])

check('退出再进那一份记在进程里 (点一下顺手写回去)', [
  /private var sessionCollapsed: Set<String>\? = null/u.test(screen),
  /sessionCollapsed = collapsed/u.test(screen),
  /session: Set<String>\?,\s*\n\s*all: List<String> = SettingsSectionKeys,\s*\n\): Set<String> = session \?: defaultCollapsed\(all\)/u.test(screen),
], [true, true, true])

check('白名单的集合形态由那份名单算出来 (不是又抄一遍)', [
  /internal val SettingsSectionsAlwaysOpen: Set<String> = SettingsSectionsAlwaysOpenKeys\.toSet\(\)/u.test(screen),
], [true])

/* ── 收尾 ─────────────────────────────────────────────────────────────── */

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exitCode = failures === 0 ? 0 : 1
