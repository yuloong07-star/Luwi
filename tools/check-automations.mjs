/**
 * 自动指令那一批的判据表 (2.5.0 批次 8)
 *
 * 这一批有五件东西**漂开了也不会当场报错**, 只会在设备上变成"它怎么没响"这种没人查得出来的样子:
 *
 *   1. **工具说明与实现两侧一致**: `lw_automation` 的 op 名单, 以及六个 `when.kind` / 两种 `then.kind`
 *      是否真的在说明里念到了 (模型是照那一份写的)
 *   2. **默认值两侧一致**: 冷却 30 分钟、每天 5 次这两句在说明里, 而 Kotlin 那两份常量是另外一份实现
 *   3. **自动指令那两个来源记号都在**: 响的那一条 (`automation`) 与设置页写规则那一条
 *      (`automation-setup`) 是两个不同的语义, 而插件只对前一个做"跳过命令表 + 新开一场"
 *   4. **桥与插件都挂了这一条工具**: 少了任何一侧都是"工具在但调不通"
 *   5. **设置页引用的字符串键两份 `strings.xml` 都有, 而且顺序一致** (`Resources.getString` 按名字
 *      取, 少一个就是 `Resources$NotFoundException`, 而它在 LazyColumn 预取时才炸)
 *   6. **冷却那个数是主人自己定的** (2026-10-09): 新建时它随提示词交给模型, 管理里还有一条**不经过
 *      模型**的直改 —— 两侧任何一侧掉了, 主人按的那个数就不算数
 *
 * 跑法: node tools/check-automations.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const plugin = await read('host-plugin/index.mjs')
const rule = await read('app/src/main/java/io/github/yuloong07star/luwi/automation/AutomationRule.kt')
const engine = await read('app/src/main/java/io/github/yuloong07star/luwi/automation/AutomationEngine.kt')
const store = await read('app/src/main/java/io/github/yuloong07star/luwi/automation/AutomationStore.kt')
const tool = await read('app/src/main/java/io/github/yuloong07star/luwi/tool/LwAutomation.kt')
const bridge = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const inbox = await read('app/src/main/java/io/github/yuloong07star/luwi/voice/VoiceInbox.kt')
const screen = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const zh = await read('app/src/main/res/values-zh/strings.xml')
const en = await read('app/src/main/res/values/strings.xml')

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

/** Kotlin 里 `val X = listOf("a", "b")` 那张表 */
function kotlinList(source, name) {
  const match = new RegExp(`val\\s+${name}\\s*=\\s*listOf\\(([^)]*)\\)`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return [...match[1].matchAll(/"([^"]+)"/g)].map((one) => one[1])
}

/** Kotlin 里 `const val NAME = 30` 那个数 */
function kotlinNumber(source, name) {
  const match = new RegExp(`const\\s+val\\s+${name}\\s*=\\s*(\\d+)`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return Number(match[1])
}

/** Kotlin 里 `val NAME = listOf(0, 5, ...)` 那张数表 */
function kotlinNumbers(source, name) {
  const match = new RegExp(`val\\s+${name}\\s*=\\s*listOf\\(([^)]*)\\)`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return [...match[1].matchAll(/(\d+)/g)].map((one) => Number(one[1]))
}

/** 插件里某个工具的 `enum: [...]` (从工具名那一行往后切到下一个工具) */
function pluginEnum(toolName) {
  const start = plugin.indexOf(`'${toolName}',`)
  if (start < 0) throw new Error(`插件里找不到 ${toolName}`)
  const rest = plugin.slice(start)
  const next = rest.indexOf('simpleTool(', 1)
  const body = next < 0 ? rest : rest.slice(0, next)
  const match = /enum:\s*\[([^\]]*)\]/.exec(body)
  if (match === null) throw new Error(`${toolName} 的参数表里找不到 enum`)
  return [...match[1].matchAll(/'([^']+)'/g)].map((one) => one[1])
}

/** 插件里某个工具的那一段源码 (从工具名切到下一个工具) */
function pluginBody(toolName) {
  const start = plugin.indexOf(`'${toolName}',`)
  if (start < 0) throw new Error(`插件里找不到 ${toolName}`)
  const rest = plugin.slice(start)
  const next = rest.indexOf('simpleTool(', 1)
  return next < 0 ? rest : rest.slice(0, next)
}

/* ── 一、op 名单 ────────────────────────────────────────────────────────── */

check(
  'lw_automation 的 op: 插件只给模型那五条 (delete 只在桥上有)',
  pluginEnum('lw_automation'),
  kotlinList(tool, 'MODEL_OPERATIONS'),
)

check(
  'lw_automation 的桥上有 delete, 而且它不在给模型的那几条里',
  [kotlinList(tool, 'OPERATIONS').includes('delete'), pluginEnum('lw_automation').includes('delete')],
  [true, false],
)

check(
  '桥与插件都挂了 automation 这一条',
  [
    bridge.includes('"automation" -> appContext'),
    plugin.includes("'lw_automation',"),
  ],
  [true, true],
)

/* ── 二、六个 when.kind 与两种 then.kind 在说明里都念到了 ───────────────── */

const automation = pluginBody('lw_automation')
const kinds = kotlinList(rule, 'WHEN_KINDS')
const thenKinds = kotlinList(rule, 'THEN_KINDS')

check('七个监测器是那七个 (批次 8 加了 once)', kinds, [
  'notice', 'foreground', 'light', 'time', 'place', 'weather', 'once',
])

check(
  '七个 kind 在插件说明里逐个念到 (写成 `notice {` 这样)',
  kinds.map((kind) => automation.includes(`${kind} {`)),
  kinds.map(() => true),
)

check(
  '两种 then.kind 在插件说明里都在',
  thenKinds.map((kind) => automation.includes(`"${kind}"`)),
  thenKinds.map(() => true),
)

/* ── 三、默认值两侧一致 ────────────────────────────────────────────────── */

check(
  '冷却默认 30 分钟: Kotlin 那份与说明里那句一致',
  [
    kotlinNumber(rule, 'DEFAULT_COOLDOWN_MINUTES'),
    automation.includes('cooldownMinutes (30)'),
  ],
  [30, true],
)

check(
  '每天上限默认 5 次: Kotlin 那份与说明里那句一致',
  [
    kotlinNumber(rule, 'DEFAULT_DAILY_LIMIT'),
    automation.includes('dailyLimit (5)'),
  ],
  [5, true],
)

/* ── 四、两个来源记号 ──────────────────────────────────────────────────── */

check(
  '应用那两个来源记号都在 (响的那一条与设置页写规则那一条)',
  [
    /const val SOURCE_AUTOMATION = "automation"/.test(inbox),
    /const val SOURCE_AUTOMATION_SETUP = "automation-setup"/.test(inbox),
  ],
  [true, true],
)

/** 插件里那段投递逻辑: 两处特别处理都得在 (跳过命令表匹配 / 强制新开一场) */
const deliverStart = plugin.indexOf('async function voiceDeliver(')
const deliver = deliverStart < 0 ? '' : plugin.slice(deliverStart, plugin.indexOf('*/', deliverStart) + 2)

check(
  '插件对 automation 那一行的两处特别处理都在 (不进命令表 / 每次新开一场)',
  [
    /const automated = line\.source === 'automation'/.test(plugin),
    /const command = automated \? null : matchVoiceCommand/.test(plugin),
    /const candidates = automated \? \[\] : await voiceTargetCandidates/.test(plugin),
  ],
  [true, true, true],
)

/* ── 五、设置页引用的字符串键: 两份都在, 顺序一致 ──────────────────────── */

/** 设置页里 `R.string.settings_automation...` 那些键, 按出现顺序去重 */
const used = [...screen.matchAll(/R\.string\.(settings_automation\w*)/g)].map((one) => one[1])
const uniqueUsed = [...new Set(used)]

/** 一份 strings.xml 里这些键的出现顺序 */
function orderIn(source) {
  const names = [...source.matchAll(/<string name="(settings_automation\w*)"/g)].map((one) => one[1])
  return uniqueUsed.filter((name) => names.includes(name))
}

const missingZh = uniqueUsed.filter((name) => !zh.includes(`name="${name}"`))
const missingEn = uniqueUsed.filter((name) => !en.includes(`name="${name}"`))
check('设置页用到的键在 values-zh 里都在', missingZh, [])
check('设置页用到的键在 values 里都在', missingEn, [])
check('两份 strings.xml 里这些键的顺序一致', orderIn(zh), orderIn(en))

/** 带参数的键不许出裸 `%` (那条规矩另有 check-bare-percent.py, 这里只查这一批的新键有没有参数) */
check(
  '这一批那几个带参数的键都在 (空态 / 创建提示词 / 改一改提示词 / 状态那两行)',
  ['settings_automation_empty', 'settings_automation_create_prompt', 'settings_automation_edit_prompt',
    'settings_automation_rule_state', 'settings_automation_last']
    .map((name) => zh.includes(`name="${name}"`) && en.includes(`name="${name}"`)),
  [true, true, true, true, true],
)

/* ── 六、主人自己定的冷却 (2026-10-09) ─────────────────────────────────── */

check(
  '冷却那几档在 Kotlin 那一份里 (0 = 不冷却, 1440 = 一天一次)',
  kotlinNumbers(rule, 'COOLDOWN_CHOICES'),
  [0, 5, 15, 30, 60, 120, 360, 1440],
)

check(
  '新建那条提示词把主人定的冷却点名写进 cooldownMinutes',
  [
    zh.includes('cooldownMinutes: %2$d'),
    en.includes('cooldownMinutes: %2$d'),
  ],
  [true, true],
)

check(
  '管理里有一条不经过模型的直改冷却 (三层都挂上)',
  [
    screen.includes('LwAutomation.setCooldown'),
    /fun setCooldown\(context: Context, name: String, minutes: Int\)/.test(tool),
    /fun setCooldown\(context: Context, name: String, minutes: Int\)/.test(store),
    /AutomationRule\.withCooldown\(/.test(store),
  ],
  [true, true, true, true],
)

check(
  '插件说明里点明"用户给的那个数不许改成 30"',
  automation.includes('write that exact number into cooldownMinutes'),
  true,
)

check(
  '规则那一行的摘要报得出冷却 (设置页那两句都在)',
  [
    screen.includes('R.string.settings_automation_rule_cooldown'),
    screen.includes('R.string.settings_automation_rule_no_cooldown'),
  ],
  [true, true],
)

/* ── 七、一次性 once (2.7.8 批次 8) ─────────────────────────────────────── */

check('once 在 Kotlin 名单里, 而且只有这一处多出来', [kinds.length, kinds.at(-1)], [7, 'once'])

check('插件说明里念到 once 与带时区的例子', [
  automation.includes('once {at:"2026-10-18T21:30:00+08:00"}'),
  automation.includes('fires once and then turns itself off'),
], [true, true])

check('once 的时刻必须带时区 (OffsetDateTime 解析)', [
  /OffsetDateTime\.parse/.test(rule),
  /absolute moment with a timezone/.test(rule),
], [true, true])

check('once 的排闹钟与自关都在引擎里', [
  /if \(whenKind == "once"\)/.test(rule),
  /private fun retireOnce\(/.test(engine),
  /retireMissedOnce/.test(engine),
  /AutomationEngine\.onOnce\(context\)/.test(await read('app/src/main/java/io/github/yuloong07star/luwi/automation/AutomationMonitors.kt')),
], [true, true, true, true])

check('设置页那一行念「只响一次 · 时刻」', /"只响一次 · "/.test(rule), true)

check('监测器那一行也进设置页 (两份 strings 都有)', [
  zh.includes('name="settings_automation_monitor_once"'),
  en.includes('name="settings_automation_monitor_once"'),
], [true, true])

/* ── 八、每天最多主动说几次 (D17) ─────────────────────────────────────── */

check('上限那三个数: 3 / 1 / 10', [
  kotlinNumber(rule, 'SAID_LIMIT_DEFAULT'),
  kotlinNumber(rule, 'SAID_LIMIT_MIN'),
  kotlinNumber(rule, 'SAID_LIMIT_MAX'),
], [3, 1, 10])

check('上限落在 settings.json 的 saidLimit, 读时夹进 1..10', [
  /put\("saidLimit", settings\.saidLimit\)/.test(store),
  /root\["saidLimit"\]/.test(store),
  /coerceSaidLimit/.test(store),
], [true, true, true])

check('引擎那条闸: task 且已达上限就 blocked = said', [
  /saidToday >= on\.saidLimit -> "said"/.test(engine),
  /"said" -> "今天已经主动说了/.test(engine),
], [true, true])

check('手动「立刻跑一次」不算进那一本账', [
  /manual = true,/.test(engine),
  /put\("manual", true\)/.test(store),
  /manual = root\["manual"\]/.test(store),
], [true, true, true])

check('那本账从 history.jsonl 现算 (只数 task / 投出去的 / 非手动)', [
  /one\.action == "task"/.test(store),
  /!one\.manual/.test(store),
  /one\.delivered != false/.test(store),
  /fun saidToday\(/.test(store),
], [true, true, true, true])

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
