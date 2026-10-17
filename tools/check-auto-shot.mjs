/**
 * 自动识屏那一张图的判据表 (2.5.0 批次 4 的需求 9)
 *
 * 三件事挤在"投递一句话"这条路上, 而它们各有一个会漂的地方:
 *
 *   1. **词表**: 哪几个说法算"指代不明"。它是**子串命中** (与命令表那套整句相等正好相反): 命令表
 *      认错人会把主人真正说的一句话吃掉 (不可逆), 而这里认错人只是多附一张图 —— 所以宁可宽一点,
 *      但"这样"这种泛指不收
 *   2. **两道判据**: 视频模式里不附 (那时"屏幕"指镜头), 设置关着不附。后者那条偏好在应用那一侧
 *      (`ScreenshotBudget.autoShot`), 插件读它走的是 `screenshot op=status` —— 两边的名字与缺省值
 *      必须一致, 所以这里也核一遍源码
 *   3. **接法**: 那一张图必须落在**用户消息**里 (与新加的 `op=status` 那条读设置的路同一条事务)
 *
 * 它不需要设备, 也不需要 host: 词表那一段是从真源码里切出来跑的 (与 `check-voice-commands.mjs`
 * 同一个办法), 其余几条是拿源码对着核。
 *
 * 跑法: node tools/check-auto-shot.mjs
 */

import { readFile } from 'node:fs/promises'

const plugin = await readFile(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const budget = await readFile(
  new URL('../app/src/main/java/io/github/yuloong07star/luwi/channel/ScreenshotBudget.kt', import.meta.url),
  'utf8',
)
const bridge = await readFile(
  new URL('../app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt', import.meta.url),
  'utf8',
)

/** 从插件源码里切一段出来跑, 不复制第二份 */
function slice(from, to) {
  const start = plugin.indexOf(from)
  if (start < 0) throw new Error(`host-plugin/index.mjs 里找不到 ${JSON.stringify(from)}`)
  const end = plugin.indexOf(to, start)
  if (end < 0) throw new Error(`host-plugin/index.mjs 里找不到分界 ${JSON.stringify(to)}`)
  return plugin.slice(start, end)
}

const node = new Function(
  `${slice('const SCREEN_REF_WORDS', '/**\n * 这一句话要不要附图')}\n`
    + 'return { SCREEN_REF_WORDS, refersToScreen }',
)()

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

console.log(`词表: ${node.SCREEN_REF_WORDS.length} 个说法 —— ${node.SCREEN_REF_WORDS.join(' / ')}`)

/* ── 一、词表本身 ─────────────────────────────────────────────────────────── */

check('词表里没有空项, 也没有重复', [
  node.SCREEN_REF_WORDS.filter((one) => !String(one).trim()).length,
  new Set(node.SCREEN_REF_WORDS).size === node.SCREEN_REF_WORDS.length,
], [0, true])

// "这样"是做法, 不是屏幕上的东西: 收进来会让"这样行不行"这种句子也去截一张图
check('"这样"不在表里 (它是泛指, 不是指着屏幕说)', node.SCREEN_REF_WORDS.includes('这样'), false)

/* ── 二、正例: 需求里那三句原话与它们的变体 ───────────────────────────────── */

const shouldHit = [
  '根据这个装修风格',
  '帮我参考这个装修风格',
  '帮我把这个课表加到个人日历',
  '把照片里的白色瓶子 P 掉',
  '屏幕上写着什么',
  '屏幕里那个数字是多少',
  '这张图里有什么',
  '这个图帮我保存一下',
  '图里那个人是谁',
  '这份合同有什么问题',
]
check(
  '指代不明的那几句都命中',
  shouldHit.filter((one) => !node.refersToScreen(one)),
  [],
)

/* ── 三、反例: 普通一句话不该多附一张图 ───────────────────────────────────── */

const shouldMiss = [
  '现在几点了',
  '帮我定个明天早上七点的闹钟',
  '打开视频模式',
  '回到手机模式',
  '打断当前回答',
  '把这个开关关掉',       // ← 这一条**故意**留着会命中, 见下面那条说明
  '',
  '   ',
]
// 上面那条"把这个开关关掉"确实在词表里命中 —— 它是这一套判据**故意接受**的代价: 认错人只是多附一张
// 当前屏的图, 而模型看得见那一句里到底要做什么。所以这里把它挑出来, 免得下一眼看的人以为漏了
const accepted = shouldMiss.filter((one) => node.refersToScreen(one))
check('"这个"这类宽口径的代价写在明面上 (只有这一句是故意接受的)', accepted, ['把这个开关关掉'])
check(
  '不含那几类说法的句子一句都不命中',
  shouldMiss.filter((one) => !accepted.includes(one) && node.refersToScreen(one)),
  [],
)

/* ── 四、三道判据与两边的名字 ─────────────────────────────────────────────── */

const auto = slice('async function autoScreenShot', '/* ── 说出来的那几句命令')
check(
  '视频模式里不附 (那时"屏幕"指的是镜头)',
  auto.includes('activeMode() === MODE_VIDEO'),
  true,
)
check(
  '设置关着不附: 先问一次 screenshot op=status',
  auto.includes("call('screenshot', { op: 'status' })") && auto.includes('setting?.autoShot === false'),
  true,
)
check(
  '截的是手机自己那块屏 (displayId 0)',
  auto.includes("call('screenshot', { displayId: 0 })"),
  true,
)
check(
  '想说的话在词表之前判一次, 不命中就直接跳过',
  auto.includes('if (!refersToScreen(text))'),
  true,
)

// 应用那一侧: 键名与缺省值, 以及那条 op=status 的回执
check(
  '设置那一项缺省是开',
  /private const val AUTO_KEY = "screenshot-auto-shot"/.test(budget)
    && /var autoShot: Boolean by mutableStateOf\(true\)/.test(budget),
  true,
)
check(
  'screenshot op=status 那一条回的是 autoShot',
  bridge.includes('"status"') && /put\("autoShot", ScreenshotBudget\.autoShot\)/.test(bridge),
  true,
)

// 投递那一段: 图要跟着那句话一起进用户消息
const deliver = slice('async function voiceDeliver', '/* ── 回答念出来')
check(
  '附图那一步在投递之前, 且截不出图不挡投递',
  deliver.includes('const auto = await autoScreenShot(line.text)')
    && deliver.includes('if (auto.tried)'),
  true,
)
check(
  '那张图进的是用户消息 (createVoiceMessage 收第二个参数)',
  // 2026-10-15 起造消息那一步挪进了 `deliverToBall` (选场那一段先按候逐个试), 所以这里量两件:
  // 投递带着 `auto` 走, 而造消息那一处把它当第二个参数喂进去
  deliver.includes('deliverToBall(ctx, controller, candidate.sessionId, line, auto)')
    && plugin.includes('createVoiceMessage(line.text, auto.images)')
    && plugin.includes('async function createVoiceMessage(text, images = [])')
    && plugin.includes("...images.map((attachment) => ({ type: 'image', attachment }))"),
  true,
)

console.log(
  failures === 0 ? `\n自动识屏那一张图的判据全过, ${checks} 条` : `\n${failures} / ${checks} 条判据不过`,
)
process.exit(failures === 0 ? 0 : 1)
