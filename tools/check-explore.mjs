/**
 * 探索那一档的判据表 (2.7.8 批次 7)
 *
 * 这一批的四件东西**分开就会静默出问题**, 而它们都在仓库里, 不需要设备:
 *
 *   1. **预设那一段** (`presets/explore/cordis.patch.yml`): 行 id / id / order 与 plugins 那 19 行 ——
 *      plugins 少一行就是"探索里有一类工具用不了", 而 persona 换掉就是"三条红线不在那一档里"
 *   2. **persona 与摘要口径**: 三条红线、软红线、五节摘要、候选卡那几条 —— 它们是**这一档唯一的闸**
 *      (D9 拍板闸在提示词层), 漂开一个字都没有第二道闸兜住
 *   3. **预算**: 30 / 60 / 3 / 两个范围 / 8000 字 / 留 30 份, 以及 [exploreBudget] 那张真值表
 *      (59 / 60 / 61 步, 到点前一拍与当拍, over 之后不再判)
 *   4. **落位与收尾**: 工作区注册路 / 省电闸 / 数步只数不拒 / `lw_explore` 的工具面 /
 *      摘要与候选卡的文件形状
 *
 * 跑法: `node tools/check-explore.mjs`
 * 它**直接取 `host-plugin/index.mjs` 里那一段源码来跑** (不复制第二份), 所以测的是上线那一份
 */

import { readFile, stat } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')
const exists = (path) => stat(new URL(path, root)).then(() => true, () => false)

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

const host = await read('host-plugin/index.mjs')
const preset = await read('presets/explore/cordis.patch.yml')
const mobileUse = await read('presets/mobile-use/cordis.patch.yml')

/* ── 抽那一段纯函数 (预算 / 摘要名 / 开场正文 / 候选卡) ─────────────────── */

const start = host.indexOf('/* ── 探索 (2.7.8 批次 7')
const end = host.indexOf('/* ── 余额 (2026-10-16')
if (start < 0 || end < 0 || end < start) throw new Error('the explore section is not in host-plugin/index.mjs')
const section = host.slice(start, end)

const api = new Function(
  'readFile', 'readdir', 'join', 'dirname', 'mkdir', 'writeFile', 'rename', 'rm',
  `${section}\nreturn { exploreBudget, exploreClamp, exploreSummaryName, exploreStartLine,`
    + ' exploreCandidateBody, exploreDayKey, EXPLORE_MINUTES_DEFAULT, EXPLORE_MINUTES_RANGE,'
    + ' EXPLORE_STEPS_DEFAULT, EXPLORE_STEPS_RANGE, EXPLORE_SAID_DEFAULT, EXPLORE_SUMMARY_CHARS,'
    + ' EXPLORE_SUMMARIES_KEPT, EXPLORE_CANDIDATES_MAX, EXPLORE_CANDIDATES_KEPT, EXPLORE_FACT_MAX,'
    + ' EXPLORE_KINDS, EXPLORE_SENSITIVITY, EXPLORE_TICK_MS, EXPLORE_WORKSPACE_NAME,'
    + ' EXPLORE_PRESET, EXPLORE_CARDS_FILE, EXPLORE_STATE_FILE }',
)(() => '', async () => [], () => '', () => '', async () => {}, async () => {}, async () => {}, async () => {})

/* ── 一、预设那一段 ────────────────────────────────────────────────────── */

/** 一份 patch 里开头那段 `- insert:` (到下一个顶层 `- ` 之前, 与 Kotlin 那个 `insertBlock` 同一个认法) */
function insertBlock(source) {
  const lines = source.split('\n')
  const at = lines.findIndex((line) => line.startsWith('- insert:'))
  if (at < 0) return ''
  let stop = lines.length
  for (let i = at + 1; i < lines.length; i += 1) {
    if (lines[i].startsWith('- ')) {
      stop = i
      break
    }
  }
  return lines.slice(at, stop).join('\n')
}

/** 一份 insert 段里 plugins 那些行 (从 `<10 空格>- id: agent-instructions` 开始, 到段尾) */
function pluginRows(block) {
  const lines = block.split('\n')
  const at = lines.findIndex((line) => line.trimStart() === '- id: agent-instructions')
  if (at < 0) return []
  // 段尾可能留着文件末尾那一两个空行: 它们不是 plugins 的哪一行
  const rows = lines.slice(at)
  while (rows.length > 0 && rows[rows.length - 1].trim() === '') rows.pop()
  return rows
}

const exploreBlock = insertBlock(preset)
check('预设文件有那段 insert', exploreBlock.startsWith('- insert:'), true)
check('行 id 是 preset-explore', exploreBlock.includes('- id: preset-explore'), true)
check('config.id 是 explore', /\n {8}id: explore\n/u.test(exploreBlock), true)
check('显示名就是探索模式', exploreBlock.includes('name: 探索模式'), true)
check('order 是 7', /\n {8}order: 7\n/u.test(exploreBlock), true)
check('persona 那一行在 (prefix 与 suffix 都在)', [
  exploreBlock.includes('- id: persona'),
  exploreBlock.includes('prefix: |-'),
  exploreBlock.includes('suffix: |-'),
], [true, true, true])
check(
  'plugins 的 19 行与 mobile-use 逐行相同 (一个工具都不删, D9)',
  pluginRows(exploreBlock),
  pluginRows(insertBlock(mobileUse)),
)
check('persona 与手机模式不同 (换的就是它)', exploreBlock !== insertBlock(mobileUse), true)

/* ── 二、persona 与摘要口径 ─────────────────────────────────────────────── */

const persona = exploreBlock.slice(exploreBlock.indexOf('prefix: |-'), exploreBlock.indexOf('suffix: |-'))
const needed = (phrase) => check(`persona 里有「${phrase}」`, persona.includes(phrase), true)
needed('这一趟的目标 (不是"完成任务")')
needed('不对任何应用做不可逆操作')
needed('不改任何已有文件')
needed('不发任何信息')
needed('不输入、不点正向确认')
needed('lw_ui (无障碍树')
needed('收尾只走 lw_explore')
needed('已确认事实')
needed('仍不确定的点')
needed('下一场建议优先探索')
needed('风险与拦截记录')
needed('证据引用')
needed('不要用别的工具写这份摘要')
needed('不要自己往长期记忆里写')
needed('一律 sensitivity: high')
needed('临时界面状态')

const toolFace = host.slice(host.indexOf("name: 'lw_explore'"), host.indexOf("name: 'lw_events_subscribe'"))
check('lw_explore 的说明里也念到了那五节 (两处都要)', [
  toolFace.includes('已确认事实'),
  toolFace.includes('仍不确定的点'),
  toolFace.includes('下一场建议优先探索'),
  toolFace.includes('风险与拦截记录'),
  toolFace.includes('证据引用'),
], [true, true, true, true, true])
check('工具说明里写清"三条红线工具面不拦"', [
  toolFace.includes('live in this preset') || toolFace.includes('prompt text'),
  toolFace.includes('does not check them') || toolFace.includes('cannot enforce'),
], [true, true])
check('工具说明里写清摘要不许用别的工具写', toolFace.includes('do not write that report'), true)

/* ── 三、预算常数与那张真值表 ─────────────────────────────────────────── */

check('缺省 30 分钟 / 60 步 / 每天 3 次', [
  api.EXPLORE_MINUTES_DEFAULT,
  api.EXPLORE_STEPS_DEFAULT,
  api.EXPLORE_SAID_DEFAULT,
], [30, 60, 3])
check('两个范围就是 1..180 与 1..1000', [api.EXPLORE_MINUTES_RANGE, api.EXPLORE_STEPS_RANGE], [[1, 180], [1, 1000]])
check('摘要 8000 字 / 留 30 份 / 一次最多 5 张卡 / 目录留 100 张 / 一张 200 字', [
  api.EXPLORE_SUMMARY_CHARS,
  api.EXPLORE_SUMMARIES_KEPT,
  api.EXPLORE_CANDIDATES_MAX,
  api.EXPLORE_CANDIDATES_KEPT,
  api.EXPLORE_FACT_MAX,
], [8000, 30, 5, 100, 200])
check('到点那一拍是 5 秒', api.EXPLORE_TICK_MS, 5000)
check('工作区名是 Luwi (D10 改口径), 预设 id 是 explore', [
  api.EXPLORE_WORKSPACE_NAME,
  api.EXPLORE_PRESET,
], ['Luwi', 'explore'])

const now = 1_760_000_000_000
const run = (over, steps, stepLimit, deadline) => ({ session: 's1', over, steps, stepLimit, deadline })
const table = [
  [api.exploreBudget(run(false, 59, 60, now + 1000), now).due, null],
  [api.exploreBudget(run(false, 59, 60, now + 1000), now).stepsLeft, 1],
  [api.exploreBudget(run(false, 60, 60, now + 1000), now).due, 'steps'],
  [api.exploreBudget(run(false, 61, 60, now + 1000), now).due, 'steps'],
  [api.exploreBudget(run(false, 61, 60, now + 1000), now).stepsLeft, 0],
  [api.exploreBudget(run(false, 0, 60, now + 1), now).due, null],
  [api.exploreBudget(run(false, 0, 60, now), now).due, 'time'],
  [api.exploreBudget(run(false, 0, 60, now - 5000), now).remainingMs, 0],
  [api.exploreBudget(run(true, 60, 60, now), now).due, null],
  [api.exploreBudget(run(true, 0, 60, now + 1000), now).active, false],
  [api.exploreBudget({ session: null }, now), { active: false, due: null, remainingMs: 0, stepsLeft: 0 }],
]
for (const [got, want] of table) check(`exploreBudget: ${JSON.stringify(got)}`, got, want)

check('时长与步数的夹取 (认不出来的给缺省, 认出来的夹进范围)', [
  api.exploreClamp(undefined, api.EXPLORE_MINUTES_RANGE, 30),
  api.exploreClamp(0, api.EXPLORE_MINUTES_RANGE, 30),
  api.exploreClamp(500, api.EXPLORE_MINUTES_RANGE, 30),
  api.exploreClamp('45', api.EXPLORE_MINUTES_RANGE, 30),
  api.exploreClamp('abc', api.EXPLORE_MINUTES_RANGE, 30),
  api.exploreClamp(0, api.EXPLORE_STEPS_RANGE, 60),
  api.exploreClamp(1001, api.EXPLORE_STEPS_RANGE, 60),
], [30, 1, 180, 45, 30, 1, 1000])

/* ── 四、工作区与预设 ─────────────────────────────────────────────────── */

check('工作区那一路泛化之后探索也走得通', [
  /async function ensureChannelWorkspace\(ctx, name, state\)/u.test(host),
  /async function ensureExploreWorkspace\(ctx\) \{\n {2}return ensureChannelWorkspace\(ctx, EXPLORE_WORKSPACE_NAME, exploreWorkspaceState\)/u.test(host),
  /entity = await registry\.create\(directory\)/u.test(host),
  /const workspace = await ensureExploreWorkspace\(ctx\)/u.test(host),
], [true, true, true, true])
check('建会话时把工作区 id 交出去 (否则落未分组)', [
  /\.\.\.ballCreatePlace\(workspace\),\n {4}\.\.\.\(preset\.id === undefined/u.test(host),
], [true])
check('预设解析不了就退 standard, 并把原因记进读数', [
  /presets\.resolve\(EXPLORE_PRESET\)/u.test(host),
  /fell back to standard/u.test(host),
  /exploreState\.presetWhy = preset\.why/u.test(host),
], [true, true, true])
check('不叠权限档也不 deny 工具 (D9)', [
  /agentPreset: preset\.id/u.test(host),
  !/readOnly: true/u.test(section),
  !/toolFilter/u.test(section),
], [true, true, true])

/* ── 五、省电闸 ───────────────────────────────────────────────────────── */

check('省电探针与判据都在', [
  /call\('wakeword', \{ op: 'status' \}\)/u.test(section),
  /status\?\.powerSave === true/u.test(section),
  /the phone is in power save mode right now, so an exploration is not opened/u.test(section),
], [true, true, true])
check('探针抛错就放行并记 powerSaveUnknown', [
  /powerSave: null, why: String\(error\?\.message \?\? error\)/u.test(section),
  /if \(!power\.known\) exploreState\.powerSaveUnknown = true/u.test(section),
], [true, true])
check('status 里带着 powerSave 那一行读数', /powerSave: power\.powerSave,/u.test(section), true)

/* ── 六、数步 (只数不拒) ──────────────────────────────────────────────── */

const guard = section.slice(section.indexOf('function exploreStepGuard(exec)'), section.indexOf('async function exploreTick'))
check('guard 只认这一场 (会话 id 不同就什么都不做)', [
  /if \(exploreRun\.session === null\) return undefined/u.test(guard),
  /if \(sessionIdOf\(exec\) !== exploreRun\.session\) return undefined/u.test(guard),
  /if \(exploreRun\.over === true\) return undefined/u.test(guard),
], [true, true, true])
check('数一步, 每五步落一次盘', [
  /exploreRun\.steps \+= 1/u.test(guard),
  /exploreRun\.steps % 5 === 0/u.test(guard),
], [true, true])
check('提醒只发一次 (置 reminded 再发)', [
  /exploreRun\.steps === exploreRun\.stepLimit && !exploreRun\.reminded/u.test(guard),
  /exploreRun\.reminded = true/u.test(guard),
], [true, true])
check('guard 一个字符串都不返回 (不做硬闸, D9)', /\n {2}return `/u.test(guard), false)
check('那道闸与到点那一拍都在 apply 里挂上', [
  /ctx\.tools\.guard\(exploreStepGuard\)/u.test(host),
  /startExploreClock\(ctx\)/u.test(host),
], [true, true])
check('到点那一下先取消再 followup (与双击打断同一条语义)', [
  /if \(options\.cancel === true\) \{\n {6}agent\.cancel\(\{ kind: 'user' \}, \{ keepInbox: true \}\)/u.test(section),
  /agent\.followup\(message\)/u.test(section),
], [true, true])
check('步数用尽的正文点名 op=finish 并说"不要再开新的事"', [
  /该收尾了/u.test(section),
  /不要再开新的事/u.test(section),
], [true, true])

/* ── 七、工具面 ───────────────────────────────────────────────────────── */

check("宿主里注册了 'lw_explore'", host.includes("name: 'lw_explore'"), true)
check('op 就是 status / start / finish 三个', /enum: \['status', 'start', 'finish'\]/u.test(toolFace), true)
check('candidates 的参数表在, 四个 kind 都在说明里', [
  /candidates:/u.test(toolFace),
  ['fact', 'preference', 'todo', 'date'].every((one) => toolFace.includes(one)),
], [true, true])
check('工具数的下限抬到 65', Number(/(?:const FLOOR = )(\d+)/.exec(await read('tools/check-host-plugin.mjs'))[1]), 65)

/* ── 八、文件与键名 ───────────────────────────────────────────────────── */

check('三份文件的名字与目录', [
  api.EXPLORE_STATE_FILE,
  api.EXPLORE_CARDS_FILE,
  /join\(home, 'lw', EXPLORE_STATE_FILE\)/u.test(section),
], ['explore.json', 'explore-cards.json', true])
check('探索卡清单的键名 (批次 8 才读写, 本批只定下它)', section.includes('{"names"'), true)
check('候选目录就在摘要目录底下', [
  /const EXPLORE_CANDIDATES_DIR = 'explore\/candidates'/u.test(section),
  /const EXPLORE_SUMMARY_DIR = 'explore'/u.test(section),
], [true, true])
check('探索状态是合并式原子写 (未知键留着, .tmp + rename)', [
  /\.\.\.base,/u.test(section),
  /const temporary = `\$\{path\}\.tmp`/u.test(section),
  /await rename\(temporary, path\)/u.test(section),
], [true, true, true])
check('宿主起来时若 run 还在就重挂预算', [
  /async function exploreStateRestore\(\)/u.test(section),
  /if \(run\.over === true\) return false/u.test(section),
  /exploreRun\.deadline = Number\(run\.deadline\) \|\| 0/u.test(section),
], [true, true, true])
check(
  '状态文件那条写链串起来 (两次并发写同一个 .tmp 会 ENOENT, 2026-10-17 真机上量到的)',
  [
    /let exploreStateQueue = Promise\.resolve\(\)/u.test(section),
    /exploreStateQueue = exploreStateQueue\.then\(/u.test(section),
  ],
  [true, true],
)

/* ── 九、落位 (三处常量不许漂开) ──────────────────────────────────────── */

const pack = await read('tools/pack-host.mjs')
const custom = await read('app/src/main/java/io/github/yuloong07star/luwi/host/CustomPresets.kt')
const inbox = await read('app/src/main/java/io/github/yuloong07star/luwi/voice/VoiceInbox.kt')
check('pack-host 摆三份声明', /for \(const name of \['mobile-use', 'video', 'explore'\]\)/u.test(pack), true)
check('Kotlin 那张 PRESET_ROWS 是三对', /ROW_EXPLORE to "explore",/u.test(custom), true)
check('应用与宿主同一个来源字面量 (`explore`)', [
  /const val SOURCE_EXPLORE = "explore"/u.test(inbox),
  /if \(line\.source === 'explore'\) return startExplore\(ctx, \{ text: line\.text, via: 'inbox' \}\)/u.test(host),
], [true, true])
check('收件箱那条 `source:explore` 支在 voiceDeliver 里, 排在命令表之前', [
  host.indexOf("line.source === 'explore'") < host.indexOf('const automated = line.source'),
], [true])

/* ── 十、结构化摘要 ───────────────────────────────────────────────────── */

check('摘要按这一场开始那一刻起名 (yyyyMMdd-HHmm)', api.exploreSummaryName(Date.UTC(2026, 9, 18, 21, 30)) !== '', true)
check('摘要名是 13 个字符且中间那条横线在', [
  /^\d{8}-\d{4}$/u.test(api.exploreSummaryName(Date.now())),
], [true])
check('开场正文带预算那两行与上一份摘要的路径', (() => {
  const line = api.exploreStartLine('看看新装了什么', run(false, 0, 60, now), '/x/lw/explore/20261018-2130.md')
  return [line.startsWith('看看新装了什么'), line.includes('30 分钟'), line.includes('60 步'), line.includes('/x/lw/explore/20261018-2130.md')]
})(), [true, true, true, true])
check('没有正文与没有上一份摘要时如实说', (() => {
  const line = api.exploreStartLine('', run(false, 0, 60, now), '')
  return [line.startsWith('自由探索这台手机'), line.includes('还没有 (这是第一场)')]
})(), [true, true])
check('摘要写文件时截 8000 字并记 truncated', [
  /const truncated = text\.length > EXPLORE_SUMMARY_CHARS/u.test(section),
  /'truncated: true'/u.test(section),
], [true, true])
check('目录只留最近 30 份 (prune 到 EXPLORE_SUMMARIES_KEPT)', /explorePrune\(directory, '\.md', EXPLORE_SUMMARIES_KEPT\)/u.test(section), true)
check('兜底那一份桩摘要不写候选卡', [
  /async function exploreStub\(\)/u.test(section),
  /candidates: \[\],\n {4}ended: 'deadline',\n {4}stub: true,/u.test(section),
], [true, true])

/* ── 十一、候选卡 ─────────────────────────────────────────────────────── */

const card = (over) => ({ fact: '主人周五晚上通常打游戏', kind: 'preference', ...over })
check('合法的一张: low 是缺省', (() => {
  const one = api.exploreCandidateBody(card({}), { at: '2026-10-18T21:30:00+08:00', session: 's1' })
  return [one.kind, one.sensitivity, one.error ?? null]
})(), ['preference', 'low', null])
check('date 类一律 high (写错一次很难补回来)', (() => {
  const one = api.exploreCandidateBody({ fact: '妈妈的生日是 5 月 3 日', kind: 'date' }, {})
  return [one.kind, one.sensitivity]
})(), ['date', 'high'])
check('date 类写了 low 也还是 high', api.exploreCandidateBody({ fact: '到期日 12 月 1 日', kind: 'date', sensitivity: 'low' }, {}).sensitivity, 'high')
check('非法 kind 整条丢掉 (报错, 不改写)', (() => {
  const one = api.exploreCandidateBody({ fact: 'x', kind: 'guess' }, {})
  return typeof one.error === 'string' && one.body === undefined
})(), true)
check('非法 sensitivity 也整条丢掉', typeof api.exploreCandidateBody({ fact: 'x', kind: 'fact', sensitivity: 'medium' }, {}).error, 'string')
check('没有 fact 也丢掉', typeof api.exploreCandidateBody({ kind: 'fact' }, {}).error, 'string')
check('一张卡最多 200 字 (超出截断)', api.exploreCandidateBody({ fact: '一'.repeat(500), kind: 'fact' }, {}).fact.length, 200)
check('正文带 frontmatter 那六个键', (() => {
  const body = api.exploreCandidateBody({ fact: 'a', kind: 'todo', confidence: 0.6, evidence: 'lw_ui' }, { at: 'T', session: 'S' }).body
  return ['at: T', 'session: S', 'kind: todo', 'sensitivity: low', 'confidence: 0.6', 'evidence: lw_ui']
    .every((one) => body.includes(one))
})(), true)

/* ── 收尾 ─────────────────────────────────────────────────────────────── */

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
