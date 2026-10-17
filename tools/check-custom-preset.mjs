/**
 * 内置 custom 预设那一批的判据表 (2026-10-09, 主人报的"新机语音开不了新会话")
 *
 * 这一条链上有四份**分开就会静默出问题**的东西, 而它们都在仓库里, 不需要设备也不需要 host:
 *
 *   1. **vendored 的 dsh-custom-mode 副本齐不齐**: 它是"那个 `custom` 预设注册进 dsh 注册表"的
 *      唯一来源, 少一个文件的现象是"新机上没有「自定义模式」"
 *   2. **它那个助手的五个文件与 Kotlin 那一份表一致**: 名字漂开的现象是"装了包而没有助手目录"
 *   3. **四段声明各有那段 `- insert:` 与那个行 id**: 应用那一侧按行 id 去重, id 换了就会重复追加
 *   4. **打包那一步真的把它们拷进树** (`tools/pack-host.mjs`): 拷漏了的现象是"代码全对, 设备上没有"
 *
 * 跑法: node tools/check-custom-preset.mjs
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

/* ── 一、vendored 副本齐不齐 ───────────────────────────────────────────── */

const manifest = JSON.parse(await read('presets/custom-mode/package/package.json'))
check('那份包就是上游 `dsh-custom-mode`', manifest.name, 'dsh-custom-mode')

const REQUIRED = [
  'index.mjs',
  'client.js',
  'seed.mjs',
  'assistants.mjs',
  'composition.mjs',
  'base-composition.mjs',
  'cordis.patch.yml',
  'preset-backend/index.mjs',
  'preset-backend/declarative.mjs',
  'preset/agent.cordis.yml',
  'preset/preset.yml',
  'preset/prompt.md',
  'preset/prompt-reader.mjs',
  'preset/prompt-tool.mjs',
]
const present = []
for (const path of REQUIRED) present.push(await exists(`presets/custom-mode/package/${path}`))
check('那几份要紧文件一个都不少', present, REQUIRED.map(() => true))

check('它是 bundle (自带 patch 层, profile 才能登记它)', typeof manifest.dsh?.bundle?.patch, 'string')
check('它有浏览器那一半 (设置页那个「自定义模式」)', manifest.dsh?.client?.platform, 'web')
check('它没有安装脚本 (pnpm 也拦, 我们不靠它)', manifest.scripts?.postinstall ?? null, null)

/* ── 二、助手那五个文件与 Kotlin 那一份表一致 ─────────────────────────── */

const kotlin = await read('app/src/main/java/io/github/yuloong07star/luwi/host/CustomPresets.kt')
const listed = /val PRESET_FILES = listOf\(([^)]*)\)/.exec(kotlin)
if (listed === null) throw new Error('CustomPresets.kt 里找不到 PRESET_FILES')
const names = [...listed[1].matchAll(/"([^"]+)"/g)].map((one) => one[1])
check('Kotlin 那份表就是上游 `PRESET_FILES`', names, [
  'agent.cordis.yml', 'preset.yml', 'prompt.md', 'prompt-reader.mjs', 'prompt-tool.mjs',
])
const seeded = []
for (const name of names) seeded.push(await exists(`presets/custom-mode/package/preset/${name}`))
check('表里那五个文件包内都有', seeded, names.map(() => true))

/* ── 三、四段声明: 各自有 `- insert:` 与那个行 id ─────────────────────── */

/** 一份 patch 里开头那段 `- insert:` (到下一个顶层 `- ` 之前, 与 Kotlin 那个 `insertBlock` 同一个认法) */
function insertBlock(source) {
  const lines = source.split('\n')
  const start = lines.findIndex((line) => line.startsWith('- insert:'))
  if (start < 0) return null
  let end = lines.length
  for (let i = start + 1; i < lines.length; i += 1) {
    if (lines[i].startsWith('- ')) {
      end = i
      break
    }
  }
  return lines.slice(start, end).join('\n')
}

for (const [file, row] of [
  ['presets/mobile-use/cordis.patch.yml', 'preset-mobile-use'],
  ['presets/video/cordis.patch.yml', 'preset-video'],
  ['presets/explore/cordis.patch.yml', 'preset-explore'],
]) {
  const block = insertBlock(await read(file))
  check(`${file} 有那段 insert`, block !== null, true)
  check(`${file} 那段里就是 ${row}`, block !== null && block.includes(`- id: ${row}`), true)
}

check(
  'Kotlin 那一侧认的三个行 id 与上面那三份一致',
  [
    kotlin.includes('ROW_MOBILE = "preset-mobile-use"'),
    kotlin.includes('ROW_VIDEO = "preset-video"'),
    kotlin.includes('ROW_EXPLORE = "preset-explore"'),
  ],
  [true, true, true],
)

check(
  'Kotlin 那张 PRESET_ROWS 就是那三对 (补声明与报缺哪一份读同一张表)',
  /val PRESET_ROWS: List<Pair<String, String>> = listOf\(\s*ROW_MOBILE to "mobile-use",\s*ROW_VIDEO to "video",\s*ROW_EXPLORE to "explore",\s*\)/.test(kotlin),
  true,
)

check(
  '默认预设是 custom、而且注册表那一段只在没有时才追加',
  [
    /const val DEFAULT_PRESET = "custom"/.test(kotlin),
    /if \(!hasRow\(patch, ROW_REGISTRY\)\) patch = appendBlock\(patch, REGISTRY_ROW\)/.test(kotlin),
  ],
  [true, true],
)

/* ── 四、打包那一步真的把它们拷进树 ───────────────────────────────────── */

const pack = await read('tools/pack-host.mjs')
check(
  'pack-host 拷了 dsh-custom-mode 进 node_modules',
  /cpSync\(customMode, customInstalled, \{ recursive: true \}\)/.test(pack),
  true,
)
check(
  'pack-host 把三份声明摆到 lw-presets/ 下',
  /\['mobile-use', 'video', 'explore'\]/.test(pack) && /lw-presets/.test(pack),
  true,
)
check(
  '那一步也被 Gradle 当成输入 (改了 preset 会重打树)',
  (await read('app/build.gradle.kts')).includes('inputs.dir(rootProject.file("presets"))'),
  true,
)

/* ── 五、两处调用都在 (缺一处这一条链就是半截) ────────────────────────── */

const service = await read('app/src/main/java/io/github/yuloong07star/luwi/host/DshHostService.kt')
const host = await read('app/src/main/java/io/github/yuloong07star/luwi/host/DshHost.kt')
check(
  '两处调用都在 (服务起时落文件 / spawn 前改 profile)',
  [service.includes('CustomPresets.ensure(this)'), host.includes('CustomPresets.ensureProfile(application)')],
  [true, true],
)

/* ── 六、那三份组成里每一行都在这棵打包树上装得起来 (树在时才查) ──────── */

/**
 * 一份组成里引用的包名 (相对路径 `./xxx` 与 `cordis:group` 不算)
 *
 * 这一条查的是"预设有声明、设备上却加载失败"那种失败面: `01` 那种行名写错了, 在界面上就是一张
 * 红色「加载失败」的卡, 而它离"语音开不了新会话"只差一步
 */
function moduleNames(source) {
  return [...source.matchAll(/name:\s*'([^']+)'/g)]
    .map((one) => one[1])
    .filter((name) => name.startsWith('@') || (!name.startsWith('.') && !name.includes(':') && name.includes('-')))
}

const treeModules = new URL('app/build/host-tree/node_modules/', root)
const treeBuilt = await exists('app/build/host-tree/node_modules')
if (!treeBuilt) {
  console.log('skip 打包树不在 (先跑一次 build), 组成那几行这一轮不查')
} else {
  const rows = [
    ...moduleNames(await read('presets/custom-mode/package/preset/agent.cordis.yml')),
    ...moduleNames(insertBlock(await read('presets/mobile-use/cordis.patch.yml')) ?? ''),
    ...moduleNames(insertBlock(await read('presets/video/cordis.patch.yml')) ?? ''),
    ...moduleNames(insertBlock(await read('presets/explore/cordis.patch.yml')) ?? ''),
  ]
  const unique = [...new Set(rows)].sort()
  const missing = []
  for (const name of unique) {
    // 子路径导出 (`@scope/pkg/tools`) 看的是它那个包本身
    const parts = name.split('/')
    const candidate = name.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0]
    if (!(await stat(new URL(`${candidate}/package.json`, treeModules)).then(() => true, () => false))) {
      missing.push(name)
    }
  }
  check(`那四份组成引用的 ${unique.length} 个包在这棵树上都有`, missing, [])
}

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
