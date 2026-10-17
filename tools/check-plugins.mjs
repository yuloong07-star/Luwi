/**
 * LW 插件的判据表 (2.5.1 批次 9 的 P0 / P1)
 *
 * 这一批有六处**漂开了也不会当场报错**, 只会在设备上变成"看起来能用"的样子, 所以在这里核:
 *
 *   1. **能力表三处一致**: Kotlin (`PluginCapabilities.kt`) / 协议文档第 5 节 / "接通"那一列的取法。
 *      漂开的样子是"界面上有一条能力, 插件的调用却回'表里没有它'"
 *   2. **手写 Binder 的那几个常量**: 描述符、transaction code、权限名。手写意味着没有编译器帮忙对,
 *      而错了以后设备上只会说"绑不上"或"没回应"
 *   3. **桥那一条与工具那一张脸**: `plugin` 这个桥方法两侧都在、`lw_plugin` 的 op 名单与 Kotlin
 *      那一行一致, 且 `snapshot` 只在桥上
 *   4. **样例包**: 能过协议里那几条规则 (协议 / kind / 前缀 / 工具名 / 能力 / 不许有浮点), 而且它的
 *      工具名与样例伴侣 `describe()` 里那三个常量对得上
 *   5. **界面那一段**: 两份 strings 的键一致、设置页里有 `key = "plugin"` 那一段, 而且详情对话框的
 *      正文封了高度并挂上 `verticalScroll` (不封顶时超出一屏的那一截被窗裁掉, 「卸载」那两个按钮点不到)
 *   6. **没有 .aidl**: 冻结时那条决定 (第 0 节第 11 条) —— app 模块里出现 AIDL 就会把 Java 编译
 *      拉回构建
 *
 * 协议与施工单在**工作区**那一层 (`../docs/`), 按仓库单独 clone 出来时读不到: 那几条会跳过并打一行
 * 说明, 不是失败
 *
 * 跑法: node tools/check-plugins.mjs   (不需要设备, 也不需要 host)
 */

import { readFile, readdir } from 'node:fs/promises'

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

/** 工作区那一层的文档: 没有就跳过依赖它的那几条 */
async function optional(path) {
  try {
    return await read(path)
  } catch {
    return null
  }
}

const api = await read('lwplugin-api/src/main/java/io/github/yuloong07star/luwi/plugin/api/LwPluginApi.kt')
const pluginIface = await read('lwplugin-api/src/main/java/io/github/yuloong07star/luwi/plugin/api/ILwPlugin.kt')
const contextIface = await read('lwplugin-api/src/main/java/io/github/yuloong07star/luwi/plugin/api/ILwPluginContext.kt')
const capabilities = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginCapabilities.kt')
const manifestRules = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginManifest.kt')
const lwPlugin = await read('app/src/main/java/io/github/yuloong07star/luwi/tool/LwPlugin.kt')
const bridge = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const manifest = await read('app/src/main/AndroidManifest.xml')
const host = await read('host-plugin/index.mjs')
const hostCheck = await read('tools/check-host-plugin.mjs')
const settings = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const appGradle = await read('app/build.gradle.kts')
const sampleGradle = await read('samples/companion/build.gradle.kts')
const sampleManifest = await read('samples/companion/src/main/AndroidManifest.xml')
const sampleService = await read('samples/companion/src/main/java/io/github/yuloong07star/luwi/sample/companion/CompanionPluginService.kt')
const samplePlugin = JSON.parse(await read('samples/companion/plugin/plugin.json'))
const rootGradle = await read('settings.gradle.kts')
const stringsZh = await read('app/src/main/res/values-zh/strings.xml')
const stringsEn = await read('app/src/main/res/values/strings.xml')
const protocol = await optional('../docs/LW-软件插件协议.md')

/** Kotlin 里一处 `const val NAME = "value"` */
function kotlinConst(source, name) {
  const match = new RegExp(`const val\\s+${name}\\s*=\\s*"([^"]+)"`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return match[1]
}

/** Kotlin 里一处 `const val NAME = 3` */
function kotlinNumber(source, name) {
  const match = new RegExp(`const val\\s+${name}\\s*=\\s*(\\d+)`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return Number(match[1])
}

/** 能力表: 每条一行 `Capability("名字", Level.X, "…", wired),` */
const table = [...capabilities.matchAll(/Capability\("([^"]+)",\s*Level\.(\w+),[\s\S]*?,\s*(true|false)\),/g)]
  .map((one) => ({ name: one[1], level: one[2], wired: one[3] === 'true' }))
/** 带参数的那两条在表里以冒号收尾 (`files.read:`), 文档里写成 `files.read:<路径前缀>` */
const decorated = (name) => {
  const bare = name.endsWith(':') ? name.slice(0, -1) : name
  return bare === 'files.read' || bare === 'net.http' ? `${bare}:` : bare
}
const byName = Object.fromEntries(table.map((one) => [decorated(one.name), one]))

/* ── 一、能力表 ─────────────────────────────────────────────────────────── */

check('能力表 23 条 (12 普通 + 11 敏感)', [table.length, table.filter((one) => one.level === 'NORMAL').length], [23, 12])
check(
  '带参数的那两条在源里以冒号结尾',
  table.filter((one) => one.name.endsWith(':')).map((one) => one.name).sort(),
  ['files.read:', 'net.http:'],
)

if (protocol === null) {
  console.log('note 工作区里没有 ../docs/LW-软件插件协议.md, 跳过与文档对照的那几条')
} else {
  const section = /## 5 · 能力表([\s\S]*?)## 6 ·/.exec(protocol)
  const rows = (section === null ? '' : section[1])
    .split('\n')
    .map((line) => /^\| `([^`]+)` \| [^|]* \| ([^|]*)\|$/.exec(line))
    .filter((one) => one !== null)
    // "接通"两个字在别的行里也出现过 (那些写的是"调用即如实拒 (P2 接通)"), 所以只认以它开头的那一格
    .map((one) => ({ name: decorated(one[1].replace(/<[^>]*>/g, '')), wired: one[2].trim().startsWith('**接通**') }))
  check('协议第 5 节那张表与 Kotlin 的能力表一样长', rows.length, table.length)
  check(
    '两张表的名字一一对上',
    rows.map((one) => one.name).sort(),
    table.map((one) => decorated(one.name)).sort(),
  )
  check(
    '接通那几条 (P0/P1) 两侧一致',
    rows.filter((one) => one.wired).map((one) => one.name).sort(),
    table.filter((one) => one.wired).map((one) => decorated(one.name)).sort(),
  )
  check(
    '文档里的接通数是 11 条 (7 + ui.widget + ui.theme + files.read + net.http)',
    rows.filter((one) => one.wired).length,
    11,
  )
}

const reserved = ['lw', 'dsh', 'agent']
check('保留前缀写在 Kotlin 那一份里', reserved.every((one) => manifestRules.includes(`"${one}"`)), true)
if (protocol !== null) {
  const reservedLine = protocol.split('\n').find((line) => line.includes('三个前缀是保留字'))
  check('保留前缀在协议里点了名', reserved.every((one) => (reservedLine ?? '').includes(one)), true)
}

/* ── 二、手写 Binder 的那几个常量 ───────────────────────────────────────── */

check(
  '描述符两个 (与协议第 9 节同名)',
  [kotlinConst(api, 'DESCRIPTOR_PLUGIN'), kotlinConst(api, 'DESCRIPTOR_CONTEXT')],
  [
    'io.github.yuloong07star.luwi.plugin.ILwPlugin',
    'io.github.yuloong07star.luwi.plugin.ILwPluginContext',
  ],
)
const descriptors = [kotlinConst(api, 'DESCRIPTOR_PLUGIN'), kotlinConst(api, 'DESCRIPTOR_CONTEXT')]
if (protocol !== null) {
  check('两个描述符在协议里也写了一遍', descriptors.every((one) => protocol.includes(one)), true)
}
check(
  'ILwPlugin 的 transaction code 是 1 / 2 / 3',
  [kotlinNumber(pluginIface, 'CODE_DESCRIBE'), kotlinNumber(pluginIface, 'CODE_LIFECYCLE'), kotlinNumber(pluginIface, 'CODE_INVOKE')],
  [1, 2, 3],
)
check(
  'ILwPluginContext 的 transaction code 是 1..5',
  [
    kotlinNumber(contextIface, 'CODE_CALL'),
    kotlinNumber(contextIface, 'CODE_SETTINGS'),
    kotlinNumber(contextIface, 'CODE_STORE'),
    kotlinNumber(contextIface, 'CODE_LOG'),
    kotlinNumber(contextIface, 'CODE_API'),
  ],
  [1, 2, 3, 4, 5],
)
check('协议版本与 api 版本', [kotlinConst(api, 'PROTOCOL'), String(kotlinNumber(api, 'API_VERSION'))], ['lw-plugin/1', '1'])

const permission = kotlinConst(api, 'PLUGIN_PERMISSION')
check('权限名在 app 的清单里声明了', manifest.includes(`android:name="${permission}"`), true)
check('app 自己也 uses-permission 了它', manifest.includes(`<uses-permission android:name="${permission}" />`), true)
check('样例伴侣的 Service 用它护住了', sampleManifest.includes(`android:permission="${permission}"`), true)

/* ── 三、桥、工具与界面 ─────────────────────────────────────────────────── */

check('桥上多了一条 plugin', bridge.includes('"plugin" -> appContext { LwPlugin.dispatch(it, request) }'), true)
check('宿主两侧都在调它 (snapshot 与 invoke)', (host.match(/call\('plugin'/g) ?? []).length >= 2, true)

const operations = [.../val OPERATIONS = listOf\(([^)]*)\)/.exec(lwPlugin)[1].matchAll(/"([^"]+)"/g)].map((one) => one[1])
const hostEnums = [.../enum: \['list', 'read', 'install', 'enable', 'disable', 'uninstall', 'audit'\]/.exec(host)[0].matchAll(/'([a-z]+)'/g)]
  .map((one) => one[1])
check('lw_plugin 的 op 名单两侧一致', hostEnums, operations)
check('snapshot 只在桥上', [lwPlugin.includes('const val OP_SNAPSHOT = "snapshot"'), operations.includes('snapshot')], [true, false])

check('工具数的下限抬到 64', Number(/(?:const FLOOR = )(\d+)/.exec(hostCheck)[1]), 64)
check('宿主里注册了 lw_plugin', host.includes("'lw_plugin',"), true)
check('设置页有插件那一段', settings.includes('key = "plugin"'), true)
check(
  '插件详情对话框的正文封了高度并挂上 verticalScroll',
  // 2026-10-08 在设备上量到的那条毛病: 声明的能力多起来 (最多 22 条) 内容会长过一屏, 而 Miuix 的
  // `OverlayDialog` **自己不滚也没有 maxHeight** (参数只有 `maxWidth`) —— 不封顶时超出一屏的那一截
  // 被窗裁掉, 底下那两个「卸载」按钮点都点不到。改 `PluginDialog` 时别把这两行拆掉
  [settings.includes('.heightIn(max = (screenHeight * 0.6f).dp)'), settings.includes('.verticalScroll(rememberScrollState())')],
  [true, true],
)

/* ── 三之二、伴侣 APK 那一档 (2026-10-11) ───────────────────────────────── */

// companion 插件的那另一半: 工具 / 它自己的界面都在那个 APK 里, 而插件包只说"绑谁" ——
// 少了这一档, 从链接装完 `.lwp` 的人只会看到"启用着但宿主没接上", 不知道该去装什么。几处**漂开了
// 也不会当场报错**: 装之前不再对包名、落地那条链断了
//
// **2026-10-11 主人把"插件段里粘链接装伴侣 APK"那一行删掉了**: 新包把地址写在 `entry.apk` 里、
// 装完顺手就装上了, 那一行只剩"给包里没地址的老包兜底"这一种用途, 却把每一台机器都顶着一行;
// 现在只留插件详情里那颗「把伴侣 APK 装上」(包里有地址而那个包没装着时才出现)
const companionApk = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/CompanionApk.kt')
check(
  '插件段里那一行「安装伴侣 APK」已经删掉 (字与代码都不留)',
  [
    settings.includes('R.string.settings_plugin_apk'),
    stringsZh.includes('settings_plugin_apk'),
    stringsEn.includes('settings_plugin_apk'),
  ],
  [false, false, false],
)
check(
  '「打开它自带的界面」只对 companion 画 (art / inline 没有另一个应用可开)',
  settings.includes('if (manifestHere?.kind == "companion")'),
  true,
)
check(
  '伴侣 APK 只收 https, 而且每一跳都重查',
  [companionApk.includes('startsWith("https://"'), companionApk.includes('requireHttps(current)')],
  [true, true],
)
check(
  '装之前对过包名 (对不上就拒)',
  [companionApk.includes('getPackageArchiveInfo'), companionApk.includes('found != expected')],
  [true, true],
)
check(
  '落地交给系统安装器 (content:// 出自我们那个 provider)',
  [
    companionApk.includes('FileProvider.getUriForFile'),
    companionApk.includes('application/vnd.android.package-archive'),
  ],
  [true, true],
)
check(
  '详情里报了伴侣应用装没装',
  [
    settings.includes('settings_plugin_companion_missing'),
    settings.includes('settings_plugin_companion_installed'),
  ],
  [true, true],
)

const keysOf = (xml) => [...xml.matchAll(/<string name="(settings_plugin_[a-z_]+)"/g)].map((one) => one[1]).sort()
check('两份 strings 的 settings_plugin_* 键一致', keysOf(stringsZh), keysOf(stringsEn))
check('插件那一段的字符串有 20 条以上', keysOf(stringsZh).length >= 20, true)

/* ── 三之三、一个地址装完即用 (2026-10-11) ──────────────────────────────── */

// 清单里 `entry.apk` 那一条: 装完 `.lwp` 顺手把伴侣 APK 也装上, 于是"输入地址"与"能用"之间不再
// 夹着第二件事。五处漂开都不会当场报错 —— 清单不再只收 https、静默那条不走特权白名单、落点挪回
// 应用私有目录 (shell 读不到, Shizuku 那份就装不动)、装机时那次确认没了、装完不启用
const companionStep = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginCompanion.kt')
const commandTable = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/LwSystemCommandTable.kt')
check(
  '清单里那个地址只收 https (与从链接装同一条口径)',
  [manifestRules.includes('entryApk'), manifestRules.includes('startsWith("https://"')],
  [true, true],
)
check(
  '静默安装走特权那一侧, 而且先抄进 system_server 读得到的 /data/local/tmp',
  [
    companionStep.includes('LwSystemCommand'),
    companionStep.includes('installStaged'),
    commandTable.includes('installStaged'),
    commandTable.includes('/data/local/tmp'),
  ],
  [true, true, true, true],
)
check(
  '伴侣 APK 落在特权壳读得到的地方',
  [companionApk.includes('getExternalMediaDirs'), companionApk.includes('stagingDirectory')],
  [true, true],
)
check('静默装之前也先对一次包名', companionStep.includes('CompanionApk.mismatch'), true)
// 2026-10-11 在真机上踩的: HttpURLConnection 不做 Happy Eyeballs, 双栈设备上 CDN 解析出 IPv6 时
// 它会直接去连 IPv6, 而那一条在移动网络里常常是黑洞 —— 浏览器下得动, 应用只拿到 "Connection reset"。
// 两条下载链都必须走 OkHttp, 而且都自己看 302 (每一跳都要重查 https)
const pluginDownload = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginDownload.kt')
check(
  '两条下载链都走 OkHttp, 而且都自己看 302',
  [
    pluginDownload.includes('OkHttpClient'),
    pluginDownload.includes('followRedirects(false)'),
    companionApk.includes('OkHttpClient'),
    companionApk.includes('followRedirects(false)'),
  ],
  [true, true, true, true],
)
check(
  '一次确认在界面里: 普通档默认勾, 敏感档默认不勾',
  [settings.includes('PluginCapabilityDialog'), settings.includes('!PluginCapabilities.isDangerous(capability)')],
  [true, true],
)
check(
  '装完把伴侣补上再启用',
  [settings.includes('PluginCompanion.ensure'), settings.includes('PluginManager.enable(context, outcome.id)')],
  [true, true],
)

/* ── 四、样例包 ─────────────────────────────────────────────────────────── */

check('样例的 protocol', samplePlugin.protocol, 'lw-plugin/1')
check('样例的 kind 是这一版收的那一个', samplePlugin.kind, 'companion')
check('样例的前缀合规且不是保留字', /^[a-z][a-z0-9_]{1,15}$/.test(samplePlugin.toolPrefix) && !reserved.includes(samplePlugin.toolPrefix), true)
check(
  '样例的工具名都带自己的前缀',
  samplePlugin.tools.every((one) => one.name.startsWith(`${samplePlugin.toolPrefix}_`)),
  true,
)
check(
  '样例声明的能力都在表里',
  [...samplePlugin.capabilities.requested, ...samplePlugin.capabilities.dangerous].every((one) => byName[one] !== undefined),
  true,
)
check(
  '样例把敏感档写在 dangerous 里',
  samplePlugin.capabilities.dangerous.every((one) => byName[one].level === 'DANGEROUS'),
  true,
)
check('样例的 api 与这一版一致', samplePlugin.api, String(kotlinNumber(api, 'API_VERSION')))

/** 协议里不许出现浮点: 两种语言的规范化只对整数印得一模一样 */
function floatsIn(value, path = '') {
  if (typeof value === 'number') return Number.isInteger(value) ? [] : [path]
  if (Array.isArray(value)) return value.flatMap((one, index) => floatsIn(one, `${path}[${index}]`))
  if (value !== null && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, one]) => floatsIn(one, path === '' ? key : `${path}.${key}`))
  }
  return []
}
check('样例的 plugin.json 里一个浮点都没有', floatsIn(samplePlugin), [])

const sampleTools = [...sampleService.matchAll(/const val TOOL_[A-Z]+ = "([^"]+)"/g)].map((one) => one[1]).sort()
check('样例包里的工具名与它 describe() 里那三个常量一致', samplePlugin.tools.map((one) => one.name).sort(), sampleTools)
check(
  '样例的服务组件与包里 entry 对得上',
  samplePlugin.entry.service === `${samplePlugin.entry.package}.CompanionPluginService`
    && sampleManifest.includes('android:name=".CompanionPluginService"')
    && sampleGradle.includes(`namespace = "${samplePlugin.entry.package}"`),
  true,
)

const appVersion = /versionName = "([^"]+)"/.exec(await read('app/build.gradle.kts'))[1]
const [major, minor, patch] = samplePlugin.minLw.split('.').map(Number)
const [appMajor, appMinor, appPatch] = appVersion.split('.').map(Number)
const newer = major - appMajor || minor - appMinor || patch - appPatch
check(`样例的 minLw (${samplePlugin.minLw}) 不比这一版 (${appVersion}) 高`, newer <= 0, true)

/* ── 五、模块与那条"不许有 AIDL" ────────────────────────────────────────── */

/* ── 四之二、鲸鱼娘那一份 (2.7.0 的旧路, 已收进 archive) ────────────────── */

// 第二条伴侣: 当时协议 10.4 说"桌面组件只能由伴侣 APK 声明", 这一份就是那条路的实物。方案 v2 起
// 桌面组件默认走主 APK 的宿主槽, 所以它**整个目录收进了 `archive/samples/whale-widget/`** (不再是
// 一个 Gradle 模块), 而这里那批判据照旧守着它 —— 归档不等于没人管: 它那份 `plugin.json` 与
// `describe()` 仍要一一对上, 否则谁哪天想照着它做会照着错的做
const whaleGradle = await read('archive/samples/whale-widget/build.gradle.kts')
const whaleManifest = await read('archive/samples/whale-widget/src/main/AndroidManifest.xml')
const whaleService = await read(
  'archive/samples/whale-widget/src/main/java/io/github/yuloong07star/luwi/sample/whalewidget/WhalePluginService.kt',
)
const whalePlugin = JSON.parse(await read('archive/samples/whale-widget/plugin/plugin.json'))

check('鲸鱼娘的 protocol', whalePlugin.protocol, 'lw-plugin/1')
check('鲸鱼娘的 kind 是这一版收的那一个', whalePlugin.kind, 'companion')
check(
  '鲸鱼娘的前缀合规且不是保留字',
  /^[a-z][a-z0-9_]{1,15}$/.test(whalePlugin.toolPrefix) && !reserved.includes(whalePlugin.toolPrefix),
  true,
)
check(
  '鲸鱼娘的工具名都带自己的前缀',
  whalePlugin.tools.every((one) => one.name.startsWith(`${whalePlugin.toolPrefix}_`)),
  true,
)
check(
  '鲸鱼娘声明的能力都在表里',
  [...whalePlugin.capabilities.requested, ...whalePlugin.capabilities.dangerous].every((one) => byName[one] !== undefined),
  true,
)
check(
  '鲸鱼娘把敏感档写在 dangerous 里',
  whalePlugin.capabilities.dangerous.every((one) => byName[one].level === 'DANGEROUS'),
  true,
)
check('鲸鱼娘一条能力都不要 (零能力伴侣那一档)', [whalePlugin.capabilities.requested, whalePlugin.capabilities.dangerous], [[], []])
check(
  '鲸鱼娘那份包带了伴侣 APK 的地址 (老包没有这一项也不影响)',
  typeof whalePlugin.entry.apk === 'string' && whalePlugin.entry.apk.startsWith('https://'),
  true,
)
check('鲸鱼娘的 api 与这一版一致', whalePlugin.api, String(kotlinNumber(api, 'API_VERSION')))
check('鲸鱼娘的 plugin.json 里一个浮点都没有', floatsIn(whalePlugin), [])
const whaleTools = [...whaleService.matchAll(/const val TOOL_[A-Z]+ = "([^"]+)"/g)].map((one) => one[1]).sort()
check('鲸鱼娘包里的工具名与它 describe() 里那三个常量一致', whalePlugin.tools.map((one) => one.name).sort(), whaleTools)
check(
  '鲸鱼娘的服务组件与包里 entry 对得上',
  whalePlugin.entry.service === `${whalePlugin.entry.package}.WhalePluginService`
    && whaleManifest.includes('android:name=".WhalePluginService"')
    && whaleGradle.includes(`namespace = "${whalePlugin.entry.package}"`),
  true,
)
const [whaleMajor, whaleMinor, whalePatch] = whalePlugin.minLw.split('.').map(Number)
const [appMajorW, appMinorW, appPatchW] = appVersion.split('.').map(Number)
const whaleNewer = whaleMajor - appMajorW || whaleMinor - appMinorW || whalePatch - appPatchW
check(`鲸鱼娘的 minLw (${whalePlugin.minLw}) 不比这一版 (${appVersion}) 高`, whaleNewer <= 0, true)
check(
  '鲸鱼娘把桌面组件登记在自己清单里 (协议 10.4)',
  [
    whaleManifest.includes('android.appwidget.action.APPWIDGET_UPDATE'),
    whaleManifest.includes('android.appwidget.provider'),
    whaleManifest.includes(`android:permission="${permission}"`),
  ],
  [true, true, true],
)

/* ── 五、模块与那条"不许有 AIDL" ────────────────────────────────────────── */

check(
  '两个在用的模块都在 settings.gradle.kts 里, 归档那一份已经不在',
  [
    rootGradle.includes('include(":lwplugin-api")'),
    rootGradle.includes('include(":sample-companion")'),
    rootGradle.includes(':sample-whale-widget'),
  ],
  [true, true, false],
)
check('app 引了接口那一份', appGradle.includes('implementation(project(":lwplugin-api"))'), true)
check('样例也引了同一份', sampleGradle.includes('implementation(project(":lwplugin-api"))'), true)

async function aidlFiles(directory) {
  const found = []
  async function walk(current) {
    // 走的是整棵仓库, 而 submodule 的 node_modules 里有断掉的软链 (2026-10-10 实测:
    // `packages/boot/cmdline/node_modules/.bin/` 是一个指向不存在的目录): 读不到就跳过那一级,
    // 这一条判的是"仓库里有没有 .aidl", 不是"每个目录都读得动"
    const entries = await readdir(current, { withFileTypes: true }).catch(() => [])
    for (const entry of entries) {
      if (entry.name === 'build' || entry.name === '.git') continue
      const full = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, current)
      if (entry.isDirectory()) await walk(full)
      else if (entry.name.endsWith('.aidl')) found.push(full.pathname)
    }
  }
  await walk(directory)
  return found
}
check('仓库里没有 .aidl (冻结时那条决定)', await aidlFiles(new URL('.', root)), [])

/* ── 六、方案 v2: 安装即用 (2026-10-11) ─────────────────────────────────── */

// 宿主槽 + art 域 + inline 白名单那一批。这一批里有几处**漂开了也不会当场报错**: 槽的
// authorities 两处写的不一样 (provider 永远 404)、授权忘了重放 (桌面那一格时好时坏)、
// 白名单算法与签名工具不是同一串 (假阴性)、art 的 frames 张数与包里的文件对不上 (桌面少几帧)
const widgetHost = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/widget/WidgetHost.kt')
const widgetSlots = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/widget/WidgetSlots.kt')
const widgetProvider = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/widget/WidgetFramesProvider.kt')
const widgetRegrant = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/widget/WidgetRegrant.kt')
const widgetPin = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/widget/WidgetPin.kt')
const pluginPaths = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginPaths.kt')
const pluginTrust = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginTrust.kt')
const pluginInstaller = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginInstaller.kt')
const pluginManager = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginManager.kt')
const inlineService = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/inline/InlineHostService.kt')
const inlineHost = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/inline/InlineHost.kt')
const signTool = await read('tools/lw-plugin-sign.mjs')
const artPlugin = JSON.parse(await read('samples/art-whale-widget/plugin/plugin.json'))
const artWidget = JSON.parse(await read('samples/art-whale-widget/plugin/widget/widget.json'))
const inlinePlugin = JSON.parse(await read('samples/inline-timer/plugin/plugin.json'))

check(
  '清单里声明了两个宿主槽 / 帧图 provider / 开机补授权 (都不许少)',
  [
    manifest.includes('.plugin.widget.WidgetSlotAnim'),
    manifest.includes('.plugin.widget.WidgetSlotImage'),
    manifest.includes('.plugin.widget.WidgetFramesProvider'),
    manifest.includes('android.permission.RECEIVE_BOOT_COMPLETED'),
    manifest.includes('android.intent.action.BOOT_COMPLETED'),
    manifest.includes('android.intent.action.USER_UNLOCKED'),
    manifest.includes('android.intent.action.MY_PACKAGE_REPLACED'),
    manifest.includes('android.intent.category.HOME'),
  ],
  [true, true, true, true, true, true, true, true],
)
check(
  'provider 的 authorities 两处是同一个字',
  [
    widgetProvider.includes('io.github.yuloong07star.luwi.lw.widget'),
    manifest.includes('android:authorities="io.github.yuloong07star.luwi.lw.widget"'),
  ],
  [true, true],
)
check('每一趟组装前重放一次帧图授权', widgetHost.includes('grantUriPermission'), true)
check(
  '"插件没了"与"帧图读不到"两种空态分开写',
  [widgetHost.includes('settings_widget_slot_gone'), widgetHost.includes('settings_widget_slot_frames')],
  [true, true],
)
check(
  '同槽互斥: 一个槽一份映射, 卸载时先清槽再重画',
  [
    widgetSlots.includes('data class Assignments'),
    widgetSlots.includes('fun clear('),
    pluginManager.includes('WidgetSlots.clear'),
  ],
  [true, true, true],
)
check(
  '开机那一条只补授权与重画 (不起 host)',
  [widgetRegrant.includes('WidgetHost.refreshAll'), widgetRegrant.includes('startService')],
  [true, false],
)
check(
  'requestPinAppWidget 那一颗按钮接上了',
  [widgetPin.includes('requestPinAppWidget'), settings.includes('WidgetPin.request')],
  [true, true],
)
check(
  'art / inline 两域在 Kotlin 那一份清单里 (dex 当 inline 的别名收)',
  [
    manifestRules.includes('setOf("companion", "art", "inline")'),
    manifestRules.includes('if (rawKind == "dex") "inline" else rawKind'),
  ],
  [true, true],
)
check(
  'art 域不许声明能力 / 工具',
  [manifestRules.includes('art 域不许声明任何能力'), manifestRules.includes('art 域不许带工具')],
  [true, true],
)
check(
  '路径纪律查两遍: 装的时候一次, 运行时再兜一次',
  [pluginPaths.includes('canonicalPath'), widgetProvider.includes('PluginPaths.resolve')],
  [true, true],
)
check(
  'inline 那座白名单: 规范化的公钥身份 + 装机那道闸',
  [
    pluginTrust.includes('val P256_OID'),
    pluginTrust.includes('fun identityOfSpki'),
    pluginTrust.includes('.inline-allow.json'),
    pluginInstaller.includes('checkInlineTrust'),
    pluginInstaller.includes('这个作者不在白名单里'),
  ],
  [true, true, true, true, true],
)
check(
  'sign 工具印的是同一串规范化身份 (不是 SPKI 原字节)',
  [signTool.includes('function identityOf'), signTool.includes('P-256')],
  [true, true],
)
check(
  'inline 有 :plugin 进程与超时兜底',
  [
    manifest.includes('android:process=":plugin"'),
    inlineService.includes('InMemoryDexClassLoader'),
    inlineHost.includes('CALL_TIMEOUT_SECONDS'),
    inlineHost.includes('没在预期时间内回答'),
  ],
  [true, true, true, true],
)

check(
  'art 样例: kind / 零能力 / 零工具',
  [artPlugin.kind, artPlugin.capabilities.requested, artPlugin.capabilities.dangerous, artPlugin.tools],
  ['art', [], [], []],
)
check('art 样例: 声明的是 anim 槽, 点一下换下一套', [artWidget.slot, artWidget.tap], ['anim', 'next'])
const artSets = [artWidget.frames, ...(artWidget.sets ?? [])]
const artFramesOk = []
for (const one of artSets) {
  const names = await readdir(new URL(`samples/art-whale-widget/plugin/${one.dir}/`, root)).catch(() => [])
  artFramesOk.push(names.filter((name) => /^frame-\d{2}\.png$/.test(name)).length === one.count)
}
check('art 样例: 每一套的帧数与包里的文件一一对上', artFramesOk, artSets.map(() => true))

check(
  'inline 样例: kind / entry / 那条能力',
  [inlinePlugin.kind, inlinePlugin.entry.dex, inlinePlugin.entry.class, inlinePlugin.capabilities.requested],
  ['inline', 'main.dex', 'io.example.timer.TimerPlugin', ['ui.widget']],
)
check(
  'inline 样例的 main.dex 真的在包里',
  (await readdir(new URL('samples/inline-timer/plugin/', root)).catch(() => [])).includes('main.dex'),
  true,
)
check(
  'inline 那两行在界面上分开写',
  [
    settings.includes('settings_plugin_inline_same_uid'),
    settings.includes('settings_plugin_inline_whitelisted'),
    settings.includes('WidgetPin.slotLabel'),
  ],
  [true, true, true],
)

// 浮标外观那一档 (协议 10.1): 外观是设置页里挑的, 读不到一律回内置画法 —— 两处漂开都不会当场
// 报错, 只会让人觉得"换了没反应"或者"插件删了球就空了"
const ballView = await read('app/src/main/java/io/github/yuloong07star/luwi/overlay/Ball.kt')
const ballTheme = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/theme/BallTheme.kt')
const themePlugin = JSON.parse(await read('samples/art-ball-theme/plugin/plugin.json'))
const themeSpec = JSON.parse(await read('samples/art-ball-theme/plugin/theme/ball.json'))
check(
  '外观那一条接上了: 挑一份 / 回内置两条路都在',
  [
    ballView.includes('BallTheme.face'),
    ballView.includes('core.background = circle'),
    settings.includes('BallThemeDialog'),
    ballTheme.includes('fun currentId'),
  ],
  [true, true, true, true],
)
check(
  '外观样例: kind art / 零能力 / 每个状态都有图, 而且图真的在包里',
  [
    themePlugin.kind,
    themePlugin.capabilities.requested,
    Object.keys(themeSpec.states).sort().join(','),
  ],
  ['art', [], ['failed', 'idle', 'listening', 'speaking', 'thinking'].sort().join(',')],
)
const themeArtNames = await readdir(new URL('samples/art-ball-theme/plugin/art/', root)).catch(() => [])
check(
  '外观样例那五张图一张不少',
  Object.values(themeSpec.states).every((path) => themeArtNames.includes(path.replace('art/', ''))),
  true,
)

// 伴侣那三条硬约束 (方案 3.4): 不许带 MAIN + LAUNCHER —— 出现就判这份包不过。
// 老那一片鲸鱼娘 (1.1.x, 已经发出去过) 按"只对新装的包生效"那一条不在此列, 而它整个目录已经收进
// `archive/samples/whale-widget/` (见方案第 6 节), 所以下面这一条扫的就是在用的那份样例伴侣
const companionManifest = await read('samples/companion/src/main/AndroidManifest.xml')
check('伴侣样例清单里不许出现 MAIN + LAUNCHER', companionManifest.includes('android.intent.category.LAUNCHER'), false)

// 归档那一份: 目录要在 archive 里, 而 samples/ 下不该再留着它 (两处同时存在就是没搬干净)
const archivedWhale = await readdir(new URL('archive/samples/', root)).catch(() => [])
const samplesNow = await readdir(new URL('samples/', root)).catch(() => [])
check(
  '鲸鱼娘那一份已经收进 archive/samples/, samples/ 下不再有它',
  [archivedWhale.includes('whale-widget'), samplesNow.includes('whale-widget')],
  [true, false],
)

/* ── 七、G3 能力沙盒 (2026-10-12) ───────────────────────────────────────── */

// 两条带参数的能力从"声明可装"推到"接通": 边界就是声明里那一截。几处**漂开了也不会当场报错** ——
// 装包时不再查参数形状 (包能装进去, 到调用才炸)、运行时的前缀不按 canonical 判 (软链一下就出去)、
// net.http 跟着 302 走而不重查 (跳到谁家都去)。这里对着源码字面守, 真跑数的在 JVM 测试
// `PluginBoundaryTest` 与回执里 (docs/LW-能力沙盒-回执.md)
const boundaries = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginBoundaries.kt')
const sandboxRuntime = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginCapabilitySandbox.kt')
const capabilityRuntime = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginCapabilityRuntime.kt')
check(
  '两条带参数的能力在运行时里接通 (不是回"还没接通")',
  [
    capabilityRuntime.includes('PluginCapabilitySandbox.filesRead'),
    capabilityRuntime.includes('PluginCapabilitySandbox.netHttp'),
  ],
  [true, true],
)
check(
  '装包时两条参数都过语法闸 (清单解析那条链上)',
  [
    manifestRules.includes('PluginFilesReadBoundary.validateDeclaration'),
    manifestRules.includes('PluginNetBoundary.validateDeclaration'),
  ],
  [true, true],
)
check(
  'files.read 的前缀按 canonical 判, 私有目录装与调用各拦一遍',
  [
    boundaries.includes('canonicalFile'),
    boundaries.includes('forbiddenRoots'),
    pluginInstaller.includes('checkSandboxScopes'),
    sandboxRuntime.includes('context.dataDir'),
  ],
  [true, true, true, true],
)
check(
  'net.http 只走 https, 重定向每一跳都重查',
  [
    boundaries.includes('.isHttps'),
    boundaries.includes('checkHop'),
    sandboxRuntime.includes('followRedirects(false)'),
    (sandboxRuntime.match(/checkHop/g) ?? []).length >= 2,
  ],
  [true, true, true, true],
)
check(
  '两条新能力的边界有单测 (兄弟前缀 / 软链 / 私有目录 / 子域)',
  [
    (await read('app/src/test/java/io/github/yuloong07star/luwi/plugin/PluginBoundaryTest.kt'))
      .includes('siblingPrefixIsNotInside'),
  ],
  [true],
)

/* ── 七、官方索引那一档 (2026-10-16) ───────────────────────────────────── */

const pluginIndex = await read('app/src/main/java/io/github/yuloong07star/luwi/plugin/PluginIndex.kt')
const settingsSource = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const repoIndex = await readFile(new URL('../../luwi-plugins/index.json', import.meta.url), 'utf8').catch(() => '')

check(
  '索引只认 lw-plugin-index/1, 缓存落 $DSH_HOME/plugins/.index.json',
  [
    pluginIndex.includes('"lw-plugin-index/1"'),
    pluginIndex.includes('.index.json'),
    pluginIndex.includes('PluginStore.root(context)'),
  ],
  [true, true, true],
)
check(
  '取 JSON 不走 PluginDownload.fetch (它按 PK 头判死), 而且只收 https',
  [
    // 只查"有没有调用" (KDoc 里那一条链接引用不算)
    !/PluginDownload\.fetch\(/.test(pluginIndex),
    pluginIndex.includes('startsWith("https://")'),
    pluginIndex.includes('MAX_BYTES = 1L * 1024 * 1024'),
  ],
  [true, true, true],
)
check(
  '地址表是多镜像 + 旧 tag 兜底, 默认钉在 index-2',
  [
    pluginIndex.includes('REF_CURRENT = "index-'),
    pluginIndex.includes('REF_PREVIOUS = "index-'),
    pluginIndex.includes('fastly.jsdelivr.net'),
    pluginIndex.includes('gcore.jsdelivr.net'),
  ],
  [true, true, true, true],
)
check(
  '装之前两道闸都在: 整包 sha256 + 大小, 以及发布者指纹',
  [
    settingsSource.includes('PluginIndex.digestOf(file)'),
    settingsSource.includes('file.length() != entry.size'),
    settingsSource.includes('PluginSignature.fingerprint(manifest.publisherKey)'),
  ],
  [true, true, true],
)
check(
  '平时只读缓存, 刷新那一颗才联网 (与技能目录同一个分工)',
  [
    settingsSource.includes('PluginIndex.cached(context)'),
    settingsSource.includes('PluginIndex.refresh(context)'),
  ],
  [true, true],
)
check(
  '生态仓那份索引的每一条都带描述与标签 (侧车合并进去了)',
  repoIndex !== '' && (JSON.parse(repoIndex).plugins ?? []).every((one) =>
    typeof one.description === 'string' && one.description.length > 0 && Array.isArray(one.tags)),
  true,
)
check(
  'README 里那三处联网的口径改成了四处 (索引是第四处)',
  (await read('README.md')).includes('④'),
  true,
)

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
