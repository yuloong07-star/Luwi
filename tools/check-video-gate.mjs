/**
 * 视频模式那两道闸的判据表 (批次 3 的需求 17 与需求 3)
 *
 * 两道闸都挂在 `host-plugin/index.mjs` 的 `ctx.tools.guard` 上, 而它是一个**同步**判据: 给它一个
 * `exec` (名字 / 参数 / `agent.id`) 就回一句理由或者什么都不说。所以这里不用设备、不花模型的钱, 也能
 * 把整张判据表问一遍 —— 做法与 `lw-plugin-call.mjs --guard` 同一套, 只是这里把两种模式、四种占用状态
 * 与那几类工具**逐条摆出来**。
 *
 * 它另外顺手核一件事: 占用表那几个约定 (文件名 / 20 分钟) 在插件与应用那一侧是**两份实现** ——
 * `$DSH_HOME/modes/camera-owner.json` 由插件写、应用读, 漂了就会出现"界面上说有人占着而取景不拦"。
 *
 * 跑法: node tools/check-video-gate.mjs   (不需要设备, 也不需要 host; 临时目录自取自清)
 */

import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { readFileSync } from 'node:fs'

/* ── 插件那一侧: 拿真源码注册一遍, 把 guard 收下来 ───────────────────────── */

const home = await mkdtemp(join(tmpdir(), 'lw-gate-'))
process.env.DSH_HOME = home

const registered = []
const guards = []
const ctx = {
  tools: {
    register: (definition) => registered.push(definition),
    guard: (check) => guards.push(check),
  },
  on() {},
  // 这个桩只要不抛就够了: 插件在 apply() 里挂的那几条定时链 (语音队列) 与这张判据表无关
  effect: () => ({ dispose() {} }),
}

const plugin = await import(pathToFileURL(resolve('host-plugin/index.mjs')).href)
plugin.apply(ctx)

const source = readFileSync(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const ownerSource = readFileSync(
  new URL('../app/src/main/java/io/github/yuloong07star/luwi/channel/CameraOwner.kt', import.meta.url),
  'utf8',
)

// 2026-10-18 (2.7.8 批次 7): 插件里现在是**两道** guard —— 这一道 (视频模式与相机) 仍在最前, 第二条是
// 探索的数步 (`exploreStepGuard`, 只数不拒), 下面那张判据表只问第一条
if (guards.length !== 2) throw new Error(`插件里应当是两道 guard, 读到 ${guards.length} 个`)
const gate = guards[0]

/* ── 判据表自己 ───────────────────────────────────────────────────────────── */

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

const SELF = 'aaaa1111-self-self-self-self'
const OTHER = 'bbbb2222-other-other-other-other'

/** 把这一次的局面摆到盘上: 哪一个模式、谁占着 (null = 没人占) */
async function scene({ mode = 'phone', owner = null, agoMs = 60_000 }) {
  const marker = join(home, 'modes', '.active')
  await mkdir(dirname(marker), { recursive: true })
  await writeFile(marker, `${mode}\n`, 'utf8')
  const table = join(home, 'modes', 'camera-owner.json')
  if (owner === null) {
    await rm(table, { force: true })
    return
  }
  const now = Date.now()
  await writeFile(
    table,
    `${JSON.stringify({ sessionId: owner, since: now - agoMs, at: now - agoMs, lens: 'back' })}\n`,
    'utf8',
  )
}

/** 问一次闸: 答案归成 `allow` 或 `refuse` (理由原样带回, 供下面挑词) */
function ask(tool, args, session = SELF) {
  const reason = gate({ name: tool, arguments: args, agent: { id: session } })
  return reason === undefined ? { verdict: 'allow', reason: '' } : { verdict: 'refuse', reason }
}

/* 一、手机模式: 屏幕工具照旧, 谁占着相机都不管屏幕那条路 */
await scene({ mode: 'phone' })
check('手机模式: lw_screenshot 放行', ask('lw_screenshot', {}).verdict, 'allow')
check('手机模式: lw_ui 放行', ask('lw_ui', {}).verdict, 'allow')
check('手机模式: lw_look 放行', ask('lw_look', {}).verdict, 'allow')
check('手机模式: 切视频模式放行', ask('lw_mode', { mode: 'video' }).verdict, 'allow')

await scene({ mode: 'phone', owner: OTHER })
check('手机模式 + 别场占着相机: 屏幕工具不受影响', ask('lw_screenshot', {}).verdict, 'allow')
check('手机模式 + 别场占着相机: 切回手机模式不受影响', ask('lw_mode', { mode: 'phone' }).verdict, 'allow')

/* 二、视频模式: 屏幕类一律拒, 取景与切模式照旧 */
await scene({ mode: 'video' })
const screenRefusals = [
  'lw_screenshot', 'lw_screen', 'lw_screen_create', 'lw_screen_resize', 'lw_screen_rotate',
  'lw_screen_release', 'lw_ui', 'lw_ui_dump', 'lw_ocr', 'lw_events_subscribe', 'lw_events_wait',
  'lw_wait_for', 'lw_tap', 'lw_swipe', 'lw_type', 'lw_key', 'lw_key_combo', 'lw_gesture',
  'lw_pinch', 'lw_scroll', 'lw_launch', 'lw_take_photo',
]
check(
  `视频模式: 那 ${screenRefusals.length} 个屏幕类 / 抢相机的工具全被拒`,
  screenRefusals.filter((tool) => ask(tool, {}).verdict !== 'refuse'),
  [],
)

// 名单本身也要核一遍: 上面那张表**照着插件里那一份写**, 少读一个名字就是一条漏闸
const pluginDenied = [
  ...source
    .slice(
      source.indexOf('const VIDEO_MODE_DENIED = new Set(['),
      source.indexOf('])', source.indexOf('const VIDEO_MODE_DENIED = new Set([') + 1),
    )
    .matchAll(/'([a-z_]+)'/g),
].map((one) => one[1])
check(
  '插件里那张黑名单与这张判据表逐个相同',
  pluginDenied.slice().sort(),
  screenRefusals.slice().sort(),
)
check('视频模式: 取景放行', ask('lw_look', {}).verdict, 'allow')
check('视频模式: 切回手机模式放行', ask('lw_mode', { mode: 'phone' }).verdict, 'allow')
// 批次 4: 识屏模式摘掉了, 所以这个出口也不在了 —— 但要**拒得住**, 因为老习惯还会说出来
check(
  '视频模式: 切"识屏模式"仍然放行到应用那一侧 (由它回一句没有这个模式)',
  ask('lw_mode', { mode: 'screen' }).verdict,
  'allow',
)
check(
  '视频模式: 非屏幕类的工具照旧放行 (电池 / 通知 / 朗读 / 文件)',
  ['lw_battery', 'lw_notify', 'lw_speak', 'lw_files', 'lw_volume', 'lw_app_control']
    .filter((tool) => ask(tool, {}).verdict !== 'allow'),
  [],
)
const screenReason = ask('lw_screenshot', {}).reason
check(
  '视频模式那条话术说清"屏幕指镜头"与出口 (批次 4 起出口是切回手机模式)',
  ['the camera picture', 'lw_look', 'lw_mode {mode:"phone"}']
    .filter((token) => !screenReason.includes(token)),
  [],
)
check(
  '那条话术不再让人去切识屏模式',
  screenReason.includes('mode:"screen"') ? ['it still says screen'] : [],
  [],
)

/* 三、占用表: 别人占着时只挡取景与切视频模式, 过期与坏文件都当没人占 */
await scene({ mode: 'video', owner: OTHER })
const busyLook = ask('lw_look', {})
const busyMode = ask('lw_mode', { mode: 'video' })
check('别场占着: lw_look 被拒', busyLook.verdict, 'refuse')
check('别场占着: 切视频模式被拒', busyMode.verdict, 'refuse')
check('别场占着: 自己那一场照旧能取景', ask('lw_look', {}, OTHER).verdict, 'allow')
check(
  '那一句点名占用者 (前八个字符) 与开始时间',
  ['session bbbb2222', 'using it since'].filter((token) => !busyLook.reason.includes(token)),
  [],
)
check(
  '那一句指出两条手动释放入口',
  ['release video mode', 'settings'].filter((token) => !busyLook.reason.includes(token)),
  [],
)

await scene({ mode: 'video', owner: OTHER, agoMs: 21 * 60 * 1000 })
check('过期的占用 (21 分钟没有续期) 不再挡取景', ask('lw_look', {}).verdict, 'allow')
// 边界那一侧要留一点余量: `scene` 把 `at` 写成"此刻减 agoMs", 而闸里读的是**它自己那一刻**的
// `Date.now()` —— 两者之间还隔着一次文件写入, 拿"正好 20 分钟"去比就是与墙钟赛跑, 这一条因此在
// 2026-10-08 实测里时过时不过 (跑五次过了一次)。差 250ms 落在同一侧, 判据要说的那件事一个字没变
await scene({ mode: 'video', owner: OTHER, agoMs: 20 * 60 * 1000 - 250 })
check('差一点到 20 分钟还算占着 (边界这一侧)', ask('lw_look', {}).verdict, 'refuse')

await scene({ mode: 'video' })
await writeFile(join(home, 'modes', 'camera-owner.json'), '{"sessionId":"who"', 'utf8')
check('读到半个字符的占用表当没人占', ask('lw_look', {}).verdict, 'allow')
await writeFile(join(home, 'modes', 'camera-owner.json'), 'not json at all\n', 'utf8')
check('完全不是 JSON 的占用表也当没人占', ask('lw_look', {}).verdict, 'allow')

/* 四、没有会话身份的调用 (非模型那条路) 不问占用 */
await scene({ mode: 'phone', owner: OTHER })
check(
  '没有 agent 的调用既不占也不被挡',
  gate({ name: 'lw_look', arguments: {} }),
  undefined,
)

/* 五、占用表那几个约定在插件与应用那一侧是两份实现, 不许漂 */
// Kotlin 给数字加的那些尾巴 (`1000L`) 不参与数值
const number = (expression) =>
  Number(new Function(`return (${expression.replace(/_/g, '').replace(/\s*[LlFfDd]$/, '')})`)())
const pluginFile = /const CAMERA_OWNER_FILE = '([^']+)'/.exec(source)?.[1]
const pluginStale = number(/const CAMERA_OWNER_STALE_MS = ([^\n]+)/.exec(source)[1])
const kotlinFile = /const val FILE = "([^"]+)"/.exec(ownerSource)?.[1]
const kotlinStale = number(/const val STALE_MS = ([^\n]+)/.exec(ownerSource)[1])
check('占用表文件名两边一致', kotlinFile, pluginFile)
check('20 分钟那条超时两边一致', kotlinStale, pluginStale)

await rm(home, { recursive: true, force: true })

console.log(
  failures === 0
    ? `\n两道闸的判据表全过, ${checks} 条 (工具 ${registered.length} 个)`
    : `\n${failures} / ${checks} 条判据不过`,
)
process.exit(failures === 0 ? 0 : 1)
