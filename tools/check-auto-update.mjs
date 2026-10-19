/**
 * 冷启动自动更新那一批的判据 (2.7.9 第 3 条)
 *
 * 这一条有三种**静默坏掉**的样子, 全都不报错:
 *
 *   1. **键名漂了** —— 读数写进另一个键, 排查时打开 `update-last` 永远是空的, 而界面上什么都不缺
 *   2. **自动那条写了 `UpdateCheck` 的单例** —— 手动的「检测更新」打开就看到自动查的旧结果 (施工单 F2)
 *   3. **触发点没挂上 / 没在 IO 上** —— 要么永远不查, 要么和首屏那几秒抢主线程
 *
 * 所以这里查四节: 三个键的名字与注释对不对得上 / 自动那条碰不碰那份单例 / 触发点的位置与线程 /
 * 两份 strings 里新加的两个键。**不需要设备, 也不需要网络**
 *
 * 跑法: node tools/check-auto-update.mjs
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

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

const auto = await read('app/src/main/java/io/github/yuloong07star/luwi/update/AutoUpdate.kt')
const prompt = await read('app/src/main/java/io/github/yuloong07star/luwi/update/AutoUpdatePrompt.kt')
const fetch = await read('app/src/main/java/io/github/yuloong07star/luwi/update/ReleaseFetch.kt')
const update = await read('app/src/main/java/io/github/yuloong07star/luwi/update/UpdateCheck.kt')
const dialogs = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/AboutDialogs.kt')
const nav = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/AppNav.kt')
const main = await read('app/src/main/java/io/github/yuloong07star/luwi/MainActivity.kt')
const en = await read('app/src/main/res/values/strings.xml')
const zh = await read('app/src/main/res/values-zh/strings.xml')

/* ── 一、三个键的名字 ──────────────────────────────────────────────────── */

const declared = [...auto.matchAll(/const val (KEY_[A-Z_]+) = "([^"]+)"/gu)].map((one) => [one[1], one[2]])
check('三个键的名字与施工单 §2.1 一字不差', declared, [
  ['KEY_CHECKED_DAY', 'update-checked-day'],
  ['KEY_SKIP', 'update-skip'],
  ['KEY_LAST', 'update-last'],
])

/** 同一个键至少出现两次: 一次在 `const val` 上, 一次在文件顶上那张说明表里 (文档与代码不漂) */
check('注释里那张表写着同一组名字', declared.map(([, key]) => auto.split(key).length - 1 >= 2), [true, true, true])

check('读数那一行是 JSON Lines 的形状 (at / result / remote / detail)', [
  auto.includes('"at":$at'),
  auto.includes('"result":"$result"'),
  auto.includes('"remote":'),
  auto.includes('"detail":'),
], [true, true, true, true])

/* ── 二、不碰 UpdateCheck 的单例 ───────────────────────────────────────── */

/**
 * 那三个字段 (`phase` / `release` / `detail`) 与 `check` / `reset` 是设置页手动那一趟的账。
 * `localVersionOf` 是纯读数 (只问 PackageManager), 允许共用 —— 自动那条只剩这一处引用
 */
const stateful = ['UpdateCheck.phase', 'UpdateCheck.release', 'UpdateCheck.detail', 'UpdateCheck.check(', 'UpdateCheck.reset(']
check('自动那条一个都不碰那份单例状态', stateful.filter((needle) => auto.includes(needle)), [])
check('自动那条只借 localVersionOf 这个纯读数', auto.includes('UpdateCheck.localVersionOf(context)'), true)

check('自动那条自己出一份弹窗状态', [
  prompt.includes('internal object AutoUpdatePrompt'),
  prompt.includes('by mutableStateOf(null)'),
  prompt.includes('fun dismiss()'),
], [true, true, true])
check('界面读的是自动那一份, 不是手动那一份', [
  nav.includes('AutoUpdatePrompt.release'),
  nav.includes('UpdateCheck.release'),
], [true, false])
check('取数抽成一份, 手动与自动共用 (没有第二份 HttpURLConnection)', [
  fetch.includes('internal object ReleaseFetch'),
  update.includes('ReleaseFetch.raw(url)'),
  auto.includes('ReleaseFetch.latest(context)'),
  auto.includes('HttpURLConnection'),
], [true, true, true, false])

/* ── 三、触发点 ────────────────────────────────────────────────────────── */

const startAt = main.indexOf('DshHostService.start(this)')
check('触发点在 onCreate 里, 排在 host 启动之后', [
  startAt > 0,
  main.indexOf('AutoUpdate.run(this@MainActivity)') > startAt,
], [true, true])
check('晚 3 s 再查 (等 host 起来, 别和首屏抢)', [
  main.includes('private const val AUTO_UPDATE_DELAY_MS = 3_000L'),
  main.includes('delay(AUTO_UPDATE_DELAY_MS)'),
], [true, true])
check('查那一趟在 IO 上', main.includes('withContext(Dispatchers.IO)'), true)
check('失败静默: 自动那条不弹自己的提示, 也不打开任何东西', [
  ['Toast', 'startActivity', 'openPage'].filter((needle) => auto.includes(needle)),
  auto.includes('catch (error: Throwable)'),
  // 最外面还有一层: 查更新这一趟抛到天上去也不该把启动带走
  main.includes('runCatching { AutoUpdate.run('),
], [[], true, true])

/* ── 四、「不再提示」记的是版本号 ─────────────────────────────────────── */

check('「不再提示」把远端那个版本号写进 update-skip', [
  auto.includes('fun skipVersion(context: Context, version: String)'),
  auto.includes('prefs(context).put(KEY_SKIP, version)'),
  nav.includes('AutoUpdate.skipVersion(context, newerRelease.version)'),
], [true, true, true])

/* ── 五、两份 strings ──────────────────────────────────────────────────── */

const updateKeys = (xml) => [...xml.matchAll(/<string name="([^"]+)"/gu)]
  .map((one) => one[1])
  .filter((key) => key.startsWith('settings_update'))

check('两份 strings 里 update 那一段的键与顺序一致', updateKeys(en), updateKeys(zh))
check('对话框用的是新加的两个键', [
  dialogs.includes('R.string.settings_update_auto_title'),
  dialogs.includes('R.string.settings_update_skip'),
], [true, true])
check('新加的两个键两份都有', [
  updateKeys(en).includes('settings_update_auto_title') && updateKeys(zh).includes('settings_update_auto_title'),
  updateKeys(en).includes('settings_update_skip') && updateKeys(zh).includes('settings_update_skip'),
], [true, true])

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
