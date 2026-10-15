/**
 * Luwi-dsh-sync 设置段那一批的判据表 (G1-2 对端)
 *
 * 这一段与别的段最大的不同: **它写的键必须是插件那一侧真的认的键**。两侧漂开的样子一点都不响亮 ——
 * 界面存下去、重启 host、启动行还是 `remote=unset`, 人会以为"重启没生效"。所以这份脚本查四件事:
 *
 *   1. **键名两侧一致**: `SyncSetting.kt` 里每一个键名都能在插件的 `lib/constants.mjs` (DEFAULTS)、
 *      `lib/luwi-workspace.mjs`、`lib/luwi-peer.mjs` (peer/live 两段) 里找到
 *   2. **落在正确的段里**: 会话那根写顶层, 工作区那根写 `workspace` 段, 对端那几项写 `peer` 段
 *   3. **设置页引用的字符串键两份 strings.xml 都有, 而且顺序一致** (`Resources.getString` 少一个
 *      就是 `Resources$NotFoundException`, 而它偏偏在 LazyColumn 预取时才炸)
 *   4. **令牌纪律**: 设置页那一段只出现打码形态, 没有任何一处把原文交给界面
 *
 * 跑法: node tools/check-sync-setting.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const setting = await read('app/src/main/java/io/github/yuloong07star/luwi/sync/SyncSetting.kt')
const screen = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const hostScreen = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/HostScreen.kt')
const zh = await read('app/src/main/res/values-zh/strings.xml')
const en = await read('app/src/main/res/values/strings.xml')
const constants = await read('host-plugins/luwi-dsh-sync/lib/constants.mjs')
const workspace = await read('host-plugins/luwi-dsh-sync/lib/luwi-workspace.mjs')
const peer = await read('host-plugins/luwi-dsh-sync/lib/luwi-peer.mjs')
const overlayYaml = await read('host-plugins/luwi-dsh-sync/cordis.patch.yml')

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

/* ── 一、键名两侧一致 ─────────────────────────────────────────────────── */

/** 插件那一侧认的键: DEFAULTS 的 UPPER_SNAKE 转 camelCase, 加 workspace / peer / live 三段 */
const defaults = constants.match(/export const DEFAULTS = Object\.freeze\(\{([\s\S]*?)\}\)/u)?.[1] ?? ''
const pluginKeys = new Set(
  [...defaults.matchAll(/^\s{2}([A-Z][A-Z0-9_]*):/gmu)]
    .map((one) => one[1].toLowerCase().replace(/_(\w)/gu, (_, char) => char.toUpperCase())),
)
// workspace / live 段由 constants.mjs 的缺省表出, peer 段在 luwi-peer.mjs 的 resolvePeerSpec 里逐项校验;
// 'workspace' / 'peer' 两段名本身也是文件里的键
for (const name of ['workspace', 'peer', 'branch', 'exclude', 'maxFileBytes', 'enabled', 'remote', 'root', 'repoDir', 'trashDir']) {
  pluginKeys.add(name)
}
for (const name of ['token', 'url', 'sendTimeoutMs', 'pollSeconds', 'listen', 'proxy']) pluginKeys.add(name)
for (const name of ['maxEntries', 'maxChars', 'keepMinutes']) pluginKeys.add(name)

/** SyncSetting.kt 里声明的文件键 (`const val XXX_KEY = "..."`) */
const declaredKeys = [...setting.matchAll(/const val ([A-Z_]+)_KEY = "([^"]+)"/gu)].map((one) => [one[1], one[2]])

/**
 * **应用自己那一层**的键 (`lw` 段): 插件不读它们, 它们是「同步」总开关用来"把用户填过的东西先存着"
 * 的地方 (关掉同步 = 远端地址挪进这里, 再打开时填回去)。所以它们**允许**不在插件那一侧
 */
const appOnlyKeys = new Set(['lw', 'savedRemote', 'savedAutoPull', 'savedAutoPush', 'savedInterval'])

const unknownKeys = declaredKeys
  .filter(([, value]) => !pluginKeys.has(value) && !appOnlyKeys.has(value))
  .map(([name, value]) => `${name}=${value}`)
check('SyncSetting 里每一个键名插件那一侧都认 (lw 段那五个是应用自己的, 除外)', unknownKeys, [])

check(
  'lw 段那几个键一个不少 (关掉同步时要把用户填过的东西原样存回来)',
  [...appOnlyKeys].filter((key) => !declaredKeys.some(([, value]) => value === key)),
  [],
)

const duplicateKeys = declaredKeys
  .map(([, value]) => value)
  .filter((value, index, all) => all.indexOf(value) !== index)
// remote 与 enabled 各出现在两个段里 (会话/工作区, 工作区/对端) —— 这正是"分属不同的段"那件事本身
check('重名的只有分属不同段的那两个 (remote / enabled)', duplicateKeys, ['remote', 'enabled'])

const expectedPair = Object.fromEntries(declaredKeys)
check('顶层那几个键写的是插件 DEFAULTS 里的名字', [
  expectedPair.SESSION_REMOTE,
  expectedPair.BRANCH,
  expectedPair.DEVICE_NAME,
  expectedPair.PUBLIC_URL,
  expectedPair.AUTO_PULL,
  expectedPair.AUTO_PUSH,
  expectedPair.PULL_INTERVAL,
], ['remote', 'branch', 'deviceName', 'publicUrl', 'autoPullOnStart', 'autoPushOnTurnEnd', 'pullIntervalMinutes'])
check('对端那一组写在 peer 段里', [
  expectedPair.PEER,
  expectedPair.PEER_TOKEN,
  expectedPair.PEER_URL,
  expectedPair.PEER_LISTEN,
  expectedPair.PEER_PROXY,
], ['peer', 'token', 'url', 'listen', 'proxy'])
check('工作区那一根写在 workspace 段里', [
  expectedPair.WORKSPACE,
  expectedPair.WORKSPACE_REMOTE,
  expectedPair.WORKSPACE_ENABLED,
], ['workspace', 'remote', 'enabled'])

/* ── 二、段与键真的对应上 (读 SyncSetting 的两个 patch 表) ───────────── */

check(
  '会话那根走 patchTop, 工作区与对端走 patchSection',
  [
    /FIELD_SESSION_REMOTE -> patchTop\(file, SESSION_REMOTE_KEY/u.test(setting),
    /FIELD_WORKSPACE_REMOTE -> patchSection\(file, WORKSPACE_KEY, WORKSPACE_REMOTE_KEY/u.test(setting),
    /FIELD_PEER_URL -> patchSection\(file, PEER_KEY, PEER_URL_KEY/u.test(setting),
    /FIELD_PEER_TOKEN -> patchSection\(file, PEER_KEY, PEER_TOKEN_KEY/u.test(setting),
    /FLAG_PEER_ON -> patchSection\(file, PEER_KEY, PEER_ENABLED_KEY/u.test(setting),
  ],
  [true, true, true, true, true],
)

check(
  '键名常量与字符串字面量对得上 (写盘用的就是它们)',
  [
    /const val SESSION_REMOTE_KEY = "remote"/u.test(setting),
    /const val PEER_TOKEN_KEY = "token"/u.test(setting),
    /const val PEER_KEY = "peer"/u.test(setting),
    /const val WORKSPACE_KEY = "workspace"/u.test(setting),
    /const val PULL_INTERVAL_KEY = "pullIntervalMinutes"/u.test(setting),
  ],
  [true, true, true, true, true],
)

/* ── 三、插件那一侧真的读这几段 ───────────────────────────────────────── */

check(
  '插件把 peer / live 两段与 workspace 一样并进三层合并',
  [
    /config\.peer !== undefined/u.test(await read('host-plugins/luwi-dsh-sync/index.mjs')),
    /resolvePeerSpec\(merged\.peer\)/u.test(await read('host-plugins/luwi-dsh-sync/index.mjs')),
    /resolveLiveSpec\(merged\.live\)/u.test(await read('host-plugins/luwi-dsh-sync/index.mjs')),
  ],
  [true, true, true],
)

check(
  'peer 段认的键与设置页一致 (token / url / listen / proxy)',
  ['token', 'url', 'listen', 'proxy'].map((key) => peer.includes(`raw.${key}`)),
  [true, true, true, true],
)

check(
  'workspace 段认的键与设置页一致 (remote / enabled)',
  [
    /const remote = typeof raw\.remote === 'string'/u.test(workspace),
    /const enabled = typeof raw\.enabled === 'boolean'/u.test(workspace),
  ],
  [true, true],
)

check(
  '插件的缺省表里那份 bundle patch 不会被手机侧用上 (手机走 overlay 行, JSON 才是配置面)',
  overlayYaml.includes('peer') === false || overlayYaml.includes('autoPullOnStart'),
  true,
)

/* ── 四、设置页引用的字符串键: 两份都在, 顺序一致 ─────────────────────── */

const used = [...screen.matchAll(/R\.string\.(settings_sync\w*)/g)].map((one) => one[1])
const uniqueUsed = [...new Set(used)]
function orderIn(source) {
  const names = [...source.matchAll(/<string name="(settings_sync\w*)"/g)].map((one) => one[1])
  return uniqueUsed.filter((name) => names.includes(name))
}
check('设置页用到的键在 values-zh 里都在', uniqueUsed.filter((name) => !zh.includes(`name="${name}"`)), [])
check('设置页用到的键在 values 里都在', uniqueUsed.filter((name) => !en.includes(`name="${name}"`)), [])
check('两份 strings.xml 里这些键的顺序一致', orderIn(zh), orderIn(en))
check('这一段至少引用了 20 个键 (段标题 + 十来行 + 两个对话框)', uniqueUsed.length >= 20, true)

check(
  '带参数的键都在 (状态行与令牌那两行)',
  ['settings_sync_peer_token_state', 'settings_sync_interval_summary']
    .map((name) => zh.includes(`name="${name}"`) && en.includes(`name="${name}"`)),
  [true, true],
)

check(
  '带参数的键在两份里占位符一致',
  [
    zh.includes('现在 %1$s') && en.includes('now %1$s'),
    zh.includes('%1$d 分钟一次') && en.includes('%1$d minute(s)'),
  ],
  [true, true],
)

/* ── 五、令牌纪律 ─────────────────────────────────────────────────────── */

check(
  '设置页那一段只出现打码形态 (没有一处把原文交给界面)',
  [
    /maskedToken\(snapshot\.peerToken\)/u.test(screen),
    // 打码只有一处实现 (SyncSetting.maskedToken), 别处只能调它
    /fun maskedToken\(value: String\): String/u.test(setting),
    /SyncSetting\.maskedToken/u.test(screen),
    // 「看当前配置」那一页也要打码
    /maskTokensIn\(file\.readText\(\)\)/u.test(setting),
  ],
  [true, true, true, true],
)

check(
  '这一段没有把口令写进任何日志',
  [
    /Log\.\w+\([^)]*peerToken/u.test(setting) === false,
    /Log\.\w+\([^)]*token/u.test(setting) === false,
  ],
  [true, true],
)

check(
  '改完要重启 host 那句话在段里 (那句话在两份 strings 里)',
  [
    zh.includes('重启 DSH host 才生效') && en.includes('restart the DSH host'),
    /PlaceholderItems\(stringResource\(R\.string\.settings_sync_note\)\)/u.test(screen),
  ],
  [true, true],
)

check(
  '「同步」总开关在位: 默认关 / 设置页与顶栏菜单同一件事 / 关的时候三个自动值归零',
  [
    // 数据面: 一个动作把"存地址 + 三个自动值归零"一起做完 (只清远端的话周期拉取还在跑, 白烧电)
    /fun setSyncOnIn\(file: File, on: Boolean\): String\?/u.test(setting),
    /next\[PULL_INTERVAL_KEY\] = JsonPrimitive\(0\)/u.test(setting),
    /next\[AUTO_PULL_KEY\] = JsonPrimitive\(false\)/u.test(setting),
    /next\[AUTO_PUSH_KEY\] = JsonPrimitive\(false\)/u.test(setting),
    // 设置页那一段: 总开关 + 关着时走的是引导句而不是把那些项画出来
    /R\.string\.settings_sync_master\b/u.test(screen) && /SyncSetting\.setSyncOn\(context, wanted\)/u.test(screen),
    /if \(!snapshot\.syncOn\) \{/u.test(screen),
    // 进设置之前那个顶栏菜单里也有一行, 而且改完顺手重启 host (插件是挂载时读配置的)
    /R\.string\.menu_sync\b/u.test(hostScreen) && /DshHost\.restart\(context\)/u.test(hostScreen),
  ],
  [true, true, true, true, true, true, true],
)

/* ── 结果 ─────────────────────────────────────────────────────────────── */

console.log(`\n${checks - failures}/${checks} passed`)
if (failures > 0) process.exitCode = 1
