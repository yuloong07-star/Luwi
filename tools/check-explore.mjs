/**
 * 探索那一档的判据表 (2.7.8 批次 7)
 *
 * 这一批的四件东西**分开就会静默出问题**, 而它们都在仓库里, 不需要设备:
 *
 *   1. **预设那一段** (`presets/explore/cordis.patch.yml`): 行 id / id / order 与 plugins 那 19 行 ——
 *      plugins 少一行就是"探索里有一类工具用不了", 而 persona 换掉就是"三条红线不在那一档里"
 *   2. **persona 与摘要口径**: 三条红线、软红线、五节摘要、记忆那几条 (2026-10-18 起候选卡下线,
 *      值得留下的由它自己用记忆插件写) —— 它们是**这一档唯一的闸**
 *      (D9 拍板闸在提示词层), 漂开一个字都没有第二道闸兜住
 *   3. **预算**: 30 / 60 / 3 / 两个范围 / 8000 字 / 留 30 份, 以及 [exploreBudget] 那张真值表
 *      (59 / 60 / 61 步, 到点前一拍与当拍, over 之后不再判)
 *   4. **落位与收尾**: 工作区注册路 / 省电闸 / 数步只数不拒 / `lw_explore` 的工具面 /
 *      摘要的文件形状
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

/**
 * 探索那一档的 plugins 行**该是哪 19 条** (persona 之后那些)
 *
 * 2026-10-18 手机/视频那两份预设下线之后, 这份名单就没有仓库里的对照物了 —— 于是把它写成字面量:
 * 少一条的现象是"探索里有一类工具用不了", 而这一条正是 D9 说的"一个工具都不删"
 */
const EXPECTED_PLUGINS = [
  'agent-instructions', 'tool-bash', 'tool-pwsh', 'tool-fs', 'tool-fs-search', 'tool-jobs',
  'skill-filesystem', 'tool-skill', 'command-goal', 'tool-goal', 'planning', 'compaction',
  'delegation', 'tool-ask-user', 'tool-todo', 'tool-web', 'present', 'tool-plugin-manager',
]

/* ── 抽那一段纯函数 (预算 / 摘要名 / 开场正文) ─────────────────────────── */

const start = host.indexOf('/* ── 探索 (2.7.8 批次 7')
const end = host.indexOf('/* ── 余额 (2026-10-16')
if (start < 0 || end < 0 || end < start) throw new Error('the explore section is not in host-plugin/index.mjs')
const section = host.slice(start, end)

const api = new Function(
  'readFile', 'readdir', 'join', 'dirname', 'mkdir', 'writeFile', 'rename', 'rm',
  `${section}\nreturn { exploreBudget, exploreClamp, exploreSummaryName, exploreStartLine,`
    + ' exploreDayKey, EXPLORE_MINUTES_DEFAULT, EXPLORE_MINUTES_RANGE,'
    + ' EXPLORE_STEPS_DEFAULT, EXPLORE_STEPS_RANGE, EXPLORE_SAID_DEFAULT, EXPLORE_SUMMARY_CHARS,'
    + ' EXPLORE_SUMMARIES_KEPT, EXPLORE_TICK_MS, EXPLORE_WORKSPACE_NAME,'
    + ' EXPLORE_PRESET, EXPLORE_TASK_FILE, EXPLORE_TASK_CHARS, EXPLORE_STATE_FILE,'
    + ' proactiveSaidCount }',
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
  'plugins 的 19 行一条不少、顺序也不变 (一个工具都不删, D9)',
  pluginRows(exploreBlock)
    // 只认 `plugins:` 那一层的 10 个空格: 组里的子行 (plan-mode / compaction-basic …) 更深
    .map((line) => /^ {10}- id: (.+)$/.exec(line)?.[1])
    .filter((name) => name !== undefined),
  EXPECTED_PLUGINS,
)

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
needed('memory_save')
needed('sensitivity: high')
needed('临时界面状态')
// 2.7.8 批次 8: 收尾写下一场任务卡 + 那条主动对话走自动指令 (不走 schedule_create)
needed('nextTask')
needed('lw_automation')
needed('schedule_create')
needed('画像里"看到的"与"推断的"分开写')

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
check('摘要 8000 字 / 留 30 份', [
  api.EXPLORE_SUMMARY_CHARS,
  api.EXPLORE_SUMMARIES_KEPT,
], [8000, 30])
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
check('op 就是 status / finish 两个 (2.7.8 批次 8 撤掉了手动 start)', /enum: \['status', 'finish'\]/u.test(toolFace), true)
check('候选卡那一套从工具面撤了 (参数留着只为旧 prompt 不报错)', [
  toolFace.includes('ignored: the candidate-card list was removed'),
  !toolFace.includes('candidate cards below'),
  toolFace.includes('memory_save'),
], [true, true, true])
check('nextTask 的参数表与说明都在 (下一场任务卡)', [
  /nextTask:/u.test(toolFace),
  toolFace.includes('NEXT exploration'),
], [true, true])
check('工具说明里写清"宿主自己按排期开, 这条工具不开"', [
  toolFace.includes('never starts one') || toolFace.includes('never starts'),
  toolFace.includes('daily start time'),
], [true, true])
check('工具数的下限抬到 65', Number(/(?:const FLOOR = )(\d+)/.exec(await read('tools/check-host-plugin.mjs'))[1]), 65)

/* ── 八、文件与键名 ───────────────────────────────────────────────────── */

check('那份任务卡的名字与上限 (宿主与应用同一个约定)', [
  api.EXPLORE_TASK_FILE,
  api.EXPLORE_TASK_CHARS,
  /join\(home, 'lw', EXPLORE_TASK_FILE\)/u.test(section),
], ['explore-card.md', 8000, true])
check('宿主收尾写卡: 原子写 + nextTask 只收非空', [
  /async function exploreTaskWrite\(text\)/u.test(section),
  /const temporary = `\$\{path\}\.tmp`/u.test(section),
  /nextTask: args\?\.nextTask/u.test(host),
  /exploreTaskWrite\(askedNext\)/u.test(section),
], [true, true, true, true])
check('收尾回执念到下一场那张卡', [
  section.includes("next task: no nextTask was given") || section.includes('next task:'),
], [true])
check('摘要目录就在 lw 底下 (候选目录 2026-10-18 已删)', [
  /const EXPLORE_SUMMARY_DIR = 'explore'/u.test(section),
  !/explore\/candidates/u.test(section),
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
check('pack-host 摆的就是探索那一份 (手机/视频两条 2026-10-18 下线)', /for \(const name of \['explore'\]\)/u.test(pack), true)
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
check('兜底那一份桩摘要记 stub 且不带候选卡', [
  /async function exploreStub\(\)/u.test(section),
  /ended: 'deadline',\n {4}stub: true,/u.test(section),
  !/candidates:/u.test(section),
], [true, true, true])
check(
  '同一场再交一次 finish 时如实说"已经落过了" (真机 2026-10-17: 到点取消之后还会再交一次)',
  [
    /already: true,/u.test(section),
    /const EXPLORE_ALREADY_SAVED_MS = 5 \* 60 \* 1000/u.test(section),
    /saved\.stub !== true/u.test(section),
    host.includes('}, sessionIdOf(exec))'),
  ],
  [true, true, true, true],
)

/* ── 十一、候选卡已下线 (2026-10-18 主人拍板) ────────────────────────────── */

check('宿主里没有候选卡那一套了 (没有读取方, 也没人再写)', [
  !/EXPLORE_CANDIDATES/u.test(host),
  !/exploreCandidateBody/u.test(host),
  !/exploreCandidatesDir/u.test(host),
  !/candidates\//u.test(host),
], [true, true, true, true])
check('摘要 frontmatter 不再记 candidates 那一行', !/candidates: \$\{/u.test(section), true)

/* ── 十二、探索卡与排期 (2.7.8 批次 8) ─────────────────────────────────── */

const task = await read('app/src/main/java/io/github/yuloong07star/luwi/tool/ExploreTask.kt')
const schedule = await read('app/src/main/java/io/github/yuloong07star/luwi/tool/ExploreSchedule.kt')
const manifest = await read('app/src/main/AndroidManifest.xml')
const service = await read('app/src/main/java/io/github/yuloong07star/luwi/host/DshHostService.kt')
const screen = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const initialCard = await read('explore-card.md')

check('初始卡就是那一场画像探索 (主人 2026-10-17 点名)', [
  initialCard.includes('手机环境'),
  initialCard.includes('装了哪些应用'),
  initialCard.includes('用户画像'),
  initialCard.includes('lw_apps'),
  initialCard.includes('已确认事实'),
  initialCard.includes('仍不确定的点'),
  initialCard.includes('sensitivity: high'),
  initialCard.includes('nextTask'),
], [true, true, true, true, true, true, true, true])

check('卡文件与种子记号 (应用与初始卡对得上)', [
  /const val DIRECTORY = "lw"/.test(task),
  /const val FILE = "explore-card.md"/.test(task),
  /const val STAMP = ".lw-seed-explore"/.test(task),
  /const val MAX_CHARS = 8000/.test(task),
  task.includes('assets.open(FILE)'),
], [true, true, true, true, true])

check('卡是原子写 (tmp + rename, 单写者)', [
  /val tmp = File\(target\.parentFile, "\$\{target\.name\}\.tmp"\)/.test(task),
  /tmp\.renameTo\(target\)/.test(task),
], [true, true])

check('排期: 每天一个时刻 / 缺省 03:00 / requestCode 42', [
  /const val DEFAULT_AT = "03:00"/.test(schedule),
  /private const val REQUEST = 42/.test(schedule),
  /fun nextAt\(at: String, now: Long/.test(schedule),
  /fun skipReason\(hostRunning: Boolean, powerSave: Boolean, running: Boolean\)/.test(schedule),
], [true, true, true, true])

check('到点那几件事都在: 读卡 / 写收件箱 / 记 last', [
  /ExploreTask\.read\(context\)/.test(schedule),
  /VoiceInbox\.append\(context, text, source = VoiceInbox\.SOURCE_EXPLORE\)/.test(schedule),
  /save\(context, current\.copy\(last = Last\(now, "skipped", skip\)\)\)/.test(schedule),
  /save\(context, current\.copy\(last = Last\(now, "started", why\)\)\)/.test(schedule),
], [true, true, true, true])

check('排期配置文件: lw/explore-schedule.json + 三个读数键', [
  /const val FILE = "explore-schedule.json"/.test(schedule),
  schedule.includes('put("enabled", plan.enabled)'),
  schedule.includes('put("outcome", last.outcome)'),
  schedule.includes('put("why", it)'),
], [true, true, true, true])

check('接收器在清单里且 exported=false', [
  /<receiver\s+android:name="\.tool\.ExploreAlarmReceiver"\s+android:exported="false" \/>/u.test(manifest),
], [true])

check('宿主起来时两个 ensure 都调', [
  /ExploreTask\.ensure\(this\)/.test(service),
  /ExploreSchedule\.ensure\(this\)/.test(service),
], [true, true])

check('设置页那段: 开关 / 时刻 / 卡 / 上限 / 读数, 没有手动开始', [
  /settingsSection\(\s*key = "explore"/u.test(screen),
  screen.includes('R.string.settings_explore_toggle'),
  screen.includes('R.string.settings_explore_at'),
  screen.includes('R.string.settings_explore_card'),
  screen.includes('R.string.settings_explore_said_title'),
  screen.includes('ExploreSchedule.save('),
  screen.includes('ExploreTask.write('),
  !screen.includes('settings_explore_start'),
], [true, true, true, true, true, true, true, true])

check('构建把初始卡拷进 assets', (await read('app/build.gradle.kts')).includes('projectDirectory.file("explore-card.md")'), true)

check('宿主那本"主动说"的账: 只数 task / 非手动 / 投出去的 / 同一天', api.proactiveSaidCount([
  '{"hit":true,"action":"task","delivered":true,"at":1792230000000}',
  '{"hit":true,"action":"task","delivered":true,"manual":true,"at":1792230000000}',
  '{"hit":true,"action":"remind","delivered":true,"at":1792230000000}',
  '{"hit":true,"action":"task","blocked":"quiet","at":1792230000000}',
  '{"hit":true,"action":"task","delivered":false,"at":1792230000000}',
  '{"hit":false,"action":"task","at":1792230000000}',
  '{"hit":true,"action":"task","delivered":true,"at":0}',
], api.exploreDayKey(1792230000000)), 1)

check('status 那两行读数换了账本 (不再写"下一批")', [
  section.includes('task rules only; a manual run does not count'),
  section.includes('next task card:'),
  !section.includes('the daily limit lands in the next batch'),
], [true, true, true])

/* ── 收尾 ─────────────────────────────────────────────────────────────── */

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
