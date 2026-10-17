/**
 * 取景与截图那几个数字的**唯一口径**不许漂
 *
 * 需求 5 的现场是"同一个数在四处各写各的": 插件里那份工具描述、应用那几份 Kotlin 常量、四份提示词、
 * 技能 `android-device-control` 与视频模式那个预置的副本 —— 而 `assets/modes/video.md` 第 22 条专门写
 * 过一句"本模式与技能冲突时以本模式为准", 那句话本身就是漂了之后的补丁。
 *
 * 这一条把它变成可执行的真值, 与 `check-voice-commands.mjs` 同一个套路:
 *
 *   1. **权威是 `docs/shot-params.md` 里那一块 JSON** (两条标记之间), 其余各处都与它逐项比
 *   2. **插件那一份不复制**: 从 `host-plugin/index.mjs` 里切出 `SHOT_PARAMS` 直接跑 (与
 *      `check-wake-words.mjs` 同一个办法), 而工具描述里的数字本来就是由它拼出来的
 *   3. **Kotlin 那几个常量照源码取**: 数字表达式 (`640 * 480` / `1_690_000` / `MAX_PIXELS`) 自己算
 *   4. **提示词与技能那几份比对"那一节里有没有那几个数"**: 说法可以人话, 数字只许是口径表里那几个
 *   5. **设置页「视频识别」那几条文案不许写死数字** (全走 `%1$d` 这类占位): 写死了就又有一处会漂
 *
 * 跑法: node tools/check-shot-params.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const read = (relative) => readFile(new URL(relative, import.meta.url), 'utf8')

const plugin = await read('../host-plugin/index.mjs')
const authority = await read('../docs/shot-params.md')
const video = await read('../app/src/main/assets/modes/video.md')
const skill = await read('../skills/android-device-control/SKILL.md')
const looks = await read('../app/src/main/java/io/github/yuloong07star/luwi/tool/VideoLooks.kt')
const camera = await read('../app/src/main/java/io/github/yuloong07star/luwi/tool/LwCamera.kt')
const bridge = await read('../app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const budget = await read('../app/src/main/java/io/github/yuloong07star/luwi/channel/ScreenshotBudget.kt')
const picture = await read('../app/src/main/java/io/github/yuloong07star/luwi/channel/Picture.kt')
const stringsEn = await read('../app/src/main/res/values/strings.xml')
const stringsZh = await read('../app/src/main/res/values-zh/strings.xml')

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

/* ── 口径表那一块 ─────────────────────────────────────────────────────────── */

const BEGIN = '<!-- shot-params:begin -->'
const END = '<!-- shot-params:end -->'
const from = authority.indexOf(BEGIN)
const to = authority.indexOf(END)
if (from < 0 || to < 0 || to < from) {
  throw new Error(`docs/shot-params.md 里找不到 ${BEGIN} / ${END} 那一对标记`)
}
const fenced = authority.slice(from, to).match(/```json\s*([\s\S]*?)```/)
if (!fenced) throw new Error('docs/shot-params.md 那一对标记之间没有 ```json 那一块')
const table = JSON.parse(fenced[1])

/* ── 插件那一份: 切出来跑, 不复制 ─────────────────────────────────────────── */

function slice(text, opening, closing) {
  const start = text.indexOf(opening)
  if (start < 0) throw new Error(`找不到 ${JSON.stringify(opening)}`)
  const end = text.indexOf(closing, start)
  if (end < 0) throw new Error(`找不到 ${JSON.stringify(opening)} 的结尾 ${JSON.stringify(closing)}`)
  // **连结尾那一截一起要**: 调用方给的 closing 就是"这一段结束在这里"那一行 (`}\n` / `\n    )`)
  return text.slice(start, end + closing.length)
}

const inPlugin = new Function(
  `${slice(plugin, 'const SHOT_PARAMS = {', '\n}\n')}\nreturn SHOT_PARAMS`,
)()

check('插件里的 SHOT_PARAMS 与口径表逐项相同', inPlugin, table)

/* ── 应用那一侧的常量 ─────────────────────────────────────────────────────── */

/** 把一个 Kotlin 里的数字表达式算出来 (`640 * 480` / `1_690_000` / `Picture.DEFAULT_MAX_PIXELS`) */
function number(expression, symbols = {}) {
  // 名字**先换**: 那两个名字自己就带下划线 (`MAX_PIXELS`), 而下面那一步要把数字里的下划线
  // (`1_690_000`) 去掉 —— 换反了顺序就会把名字读成 `MAXPIXELS`
  let source = String(expression).trim()
  // 名字**从长到短**换, 否则 `MAX_PIXELS` 会把 `Picture.DEFAULT_MAX_PIXELS` 切掉一半
  for (const name of Object.keys(symbols).sort((one, two) => two.length - one.length)) {
    source = source.split(name).join(`(${symbols[name]})`)
  }
  // Kotlin 给数字加的那些尾巴 (`120L` / `100f`) 不参与数值
  source = source.replace(/_/g, '').replace(/\s*[LlFfDd]$/, '')
  if (!/^[\d\s+*()\-]+$/.test(source)) throw new Error(`认不出的数字表达式: ${expression}`)
  return Number(new Function(`return (${source})`)())
}

/** Kotlin 里 `val levels: List<Pair<Int, Int>> = listOf(...)` 那三档的像素数 */
function levelsOf(text, symbols = {}) {
  const block = slice(text, 'val levels: List<Pair<Int, Int>> = listOf(', '\n    )')
  return [...block.matchAll(/ to ([^,\n]+),/g)].map((one) => number(one[1], symbols))
}

const maxPixels = number(/const val MAX_PIXELS = ([^\n]+)/.exec(looks)[1])
const pictureDefault = number(/const val DEFAULT_MAX_PIXELS = ([^\n]+)/.exec(picture)[1])
const cameraMaxCount = number(/const val MAX_COUNT = ([^\n]+)/.exec(camera)[1])

const lookInterval = /val intervalRange: ClosedFloatingPointRange<Float> = (\d+)f\.\.(\d+)f/
  .exec(looks)
const lookLevels = levelsOf(looks, { MAX_PIXELS: maxPixels })

check('取景张数缺省 (VideoLooks.DEFAULT_COUNT)', number(/const val DEFAULT_COUNT = ([^\n]+)/.exec(looks)[1]), table.look.framesDefault)
check('取景张数上限 (LwCamera.MAX_COUNT 与 lw_look 的夹取同一个数)', cameraMaxCount, table.look.framesMax)
check('取景间隔缺省 (VideoLooks.DEFAULT_INTERVAL_MS)', number(/const val DEFAULT_INTERVAL_MS = ([^\n]+)/.exec(looks)[1]), table.look.intervalDefaultMs)
check(
  '取景间隔两端 (VideoLooks.intervalRange)',
  lookInterval === null ? null : [Number(lookInterval[1]), Number(lookInterval[2])],
  [table.look.intervalMinMs, table.look.intervalMaxMs],
)
check('取景清晰度三档 (VideoLooks.levels)', lookLevels, table.look.qualityPixels)

check('截屏连拍上限 (PrivilegedBridge.MAX_SERIES)', number(/const val MAX_SERIES = ([^\n]+)/.exec(bridge)[1]), table.shot.countMax)
check(
  '截屏间隔缺省 (PrivilegedBridge.DEFAULT_SERIES_GAP_MS)',
  number(/const val DEFAULT_SERIES_GAP_MS = ([^\n]+)/.exec(bridge)[1]),
  table.shot.intervalDefaultMs,
)
check(
  '截屏间隔两端 (PrivilegedBridge)',
  [
    number(/const val MIN_SERIES_GAP_MS = ([^\n]+)/.exec(bridge)[1]),
    number(/const val MAX_SERIES_GAP_MS = ([^\n]+)/.exec(bridge)[1]),
  ],
  [table.shot.intervalMinMs, table.shot.intervalMaxMs],
)
check(
  '截屏清晰度三档 (ScreenshotBudget.levels)',
  levelsOf(budget, { 'Picture.DEFAULT_MAX_PIXELS': pictureDefault }),
  table.shot.qualityPixels,
)

const byteRange = /val byteRange: ClosedFloatingPointRange<Float> = (\d+)f\.\.(\d+)f/.exec(budget)
const byteTyped = /val byteInputRange: ClosedFloatingPointRange<Float> = (\d+)f\.\.(\d+)f/.exec(budget)
check(
  '截屏字节预算滑块两端 (ScreenshotBudget.byteRange)',
  byteRange === null ? null : [Number(byteRange[1]), Number(byteRange[2])],
  [table.shot.byteMinKiB, table.shot.byteSliderMaxKiB],
)
check(
  '截屏字节预算打字上限 (ScreenshotBudget.byteInputRange)',
  byteTyped === null ? null : [Number(byteTyped[1]), Number(byteTyped[2])],
  [table.shot.byteMinKiB, table.shot.byteTypedMaxKiB],
)

/* ── 提示词 / 技能 / 预置那一份副本 ───────────────────────────────────────── */

/**
 * 一篇提示词里第 N 条那一整段 (从 `N. ` 到 `N+1. `)
 *
 * **前导空白要容忍**: 这些条目有的顶格写, 有的缩进过 —— 同一个函数读几份
 */
function article(text, number) {
  const from = new RegExp(`(^|\\n)\\s*${number}\\. `).exec(text)
  if (from === null) throw new Error(`找不到第 ${number} 条`)
  const rest = text.slice(from.index + from[0].length)
  const to = new RegExp(`(^|\\n)\\s*${number + 1}\\. `).exec(rest)
  return to === null ? rest : rest.slice(0, to.index)
}

/** 这一段里有没有这个数字/说法 (数字按词边界找, 免得 `12` 撞上 `120`) */
function has(fragment, token) {
  if (/^\d+$/.test(String(token))) return new RegExp(`(^|\\D)${token}(\\D|$)`).test(fragment)
  return fragment.includes(String(token))
}

const ruleBook = [
  ['video.md 第 9 条: 缺省张数', video, 9, table.look.framesDefault],
  ['video.md 第 9 条: 第二组张数', video, 9, table.look.secondLook],
  ['video.md 第 9 条: 上限张数', video, 9, table.look.framesMax],
  ['video.md 第 9 条: 最多两组', video, 9, '两组'],
  ['video.md 第 11 条: 动的东西那一档', video, 11, table.look.movingFrames],
  ['video.md 第 12 条: 要看手机自己那块屏先切回手机模式', video, 12, 'lw_mode {mode:"phone"}'],
  ['video.md 第 12 条: 说清"屏幕"指镜头', video, 12, '镜头'],
  ['video.md 第 8 条: 相机被别场占着的出口', video, 8, '释放视频模式'],
  ['video.md 第 22 条: 指到口径表', video, 22, 'docs/shot-params.md'],
  ['技能: 取景缺省张数', skill, null, `缺省 ${table.look.framesDefault}`],
  ['技能: 第二组张数', skill, null, table.look.secondLook],
  ['技能: 取景间隔', skill, null, `${table.look.intervalMinMs}–${table.look.intervalMaxMs}`],
  ['技能: 取景间隔缺省', skill, null, `${table.look.intervalDefaultMs} ms`],
  ['技能: 截屏间隔', skill, null, `${table.shot.intervalMinMs}–${table.shot.intervalMaxMs}`],
  ['技能: 截屏间隔缺省', skill, null, `${table.shot.intervalDefaultMs} ms`],
  ['技能: 视频模式里屏幕指镜头', skill, null, 'lw_mode {mode:"phone"}'],
  ['技能: 释放视频模式那两条入口', skill, null, '释放视频模式'],
  ...table.look.qualityPixels.map((pixels) => [`技能: 取景清晰度 ${pixels}`, skill, null, pixels]),
  ...table.shot.qualityPixels.map((pixels) => [`技能: 截屏清晰度 ${pixels}`, skill, null, pixels]),
  ['技能: 字节预算两端', skill, null, `${table.shot.byteMinKiB}–${table.shot.byteSliderMaxKiB}`],
  ['技能: 字节预算打字上限', skill, null, table.shot.byteTypedMaxKiB],
]

const missing = ruleBook
  .filter(([, text, number, token]) => !has(number === null ? text : article(text, number), token))
  .map(([name]) => name)
check('提示词 / 技能 / 预置那几份都写着口径表里那几个数', missing, [])

/* ── 设置页那几条文案不许写死数字 ─────────────────────────────────────────── */

/** `settings_look_*` 那几条的键与原样 (两份语言一起看) */
function lookStrings(text) {
  return [...text.matchAll(/<string name="(settings_look[a-z_]*)"[^>]*>([\s\S]*?)<\/string>/g)]
    .map((one) => ({ key: one[1], value: one[2] }))
}

const en = lookStrings(stringsEn)
const zh = lookStrings(stringsZh)

check('设置页「视频识别」那几条键两份语言一致 (键与顺序)', en.map((one) => one.key), zh.map((one) => one.key))

const hardcoded = [...en, ...zh]
  .map((one) => ({ ...one, bare: one.value.replace(/%\d+\$[dsf]/g, '').replace(/%[dsf]/g, '') }))
  .filter((one) => /\d/.test(one.bare))
  .map((one) => one.key)
check('那几条文案里没有写死的数字 (全走 %1$d 这类占位)', [...new Set(hardcoded)], [])

console.log(
  failures === 0
    ? `\n取景与截图的数字只有一份, ${checks} 条判据全过`
    : `\n${failures} / ${checks} 条判据不过`,
)
process.exit(failures === 0 ? 0 : 1)
