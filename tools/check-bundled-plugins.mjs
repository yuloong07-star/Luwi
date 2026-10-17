/**
 * 随包发的第三方插件 (2.7.8 批次 4)
 *
 * 现在有两个: 生图 `@dickpy/dsh-imagegen` 与记忆 `@modusensus/dsh-mneme`。后者是这一批新加的, 而
 * "加一个随包插件"这件事有**四段接缝**, 每一段漂开了都不会当场报错, 只会在设备上变成"这个功能
 * 整块不见了"或者"工作区里多出一坨东西":
 *
 *   1. **树里得有它**: `tools/pack-host.mjs` 把它取进 `node_modules/<包名>`, 并且**装完 import 一次**
 *      —— 少一个运行时依赖时 npm 不会说话, 到加载时才抛
 *   2. **挂的那一层要对**: 配置要在 dsh 设置面板里改得动的那些行必须写进 **profile 自己的 patch**
 *      (`PluginRows`), 不能写进命令行覆盖层 —— 写在那一层上面时 config-editor 会回
 *      "overridden by a home patch or command-line overlay"
 *   3. **盘上 `name:` 要是 `file://` 形态**: dsh 读 patch 时会把绝对路径锚成 URL, 而面板追加的
 *      config 行抄的是锚过之后那个名字 —— 盘上留裸路径, 那条 config 行会被按 "name mismatch" 丢掉
 *   4. **数据目录不许落在工作区**: mneme 的缺省记忆库是 `~/.dsh/memory` 而 `HOME` 指着工作区, 所以
 *      它的 `memoryDir` 由 `PluginRows` 按本机 filesDir 代写成绝对路径; 自管运行时那一档走
 *      `DSH_MNEME_RUNTIME_DIR`, 由 `DshHost` 给
 *
 * 另外它只用 `node:sqlite` (`lib/store.js` 的 `DatabaseSync`), 所以随包的 node 必须带那个内建模块
 * — 这一条拿不到源码里核, 是在真机上量的 (2026-10-17, node v26.4.0 上 ok)
 *
 * 不需要设备, 也不需要 host: 全是拿真源码对着核。
 *
 * 用法: node tools/check-bundled-plugins.mjs
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const packer = await read('tools/pack-host.mjs')
const rows = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginRows.kt')
const overlay = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginOverlay.kt')
const host = await read('app/src/main/java/io/github/yuloong07star/luwi/host/DshHost.kt')

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

/* ── 一、取进树的那两样 ────────────────────────────────────────────────── */

const image = /const imagePlugin = \{ name: '([^']+)', version: '([^']+)' \}/.exec(packer)
const mneme = /const mnemePlugin = \{ name: '([^']+)', version: '([^']+)' \}/.exec(packer)

check('生图插件仍按自己那份声明取进树', [image?.[1], image?.[2]], ['@dickpy/dsh-imagegen', '1.6.7'])
check('记忆插件按自己那份声明取进树', [mneme?.[1], mneme?.[2]], ['@modusensus/dsh-mneme', '0.9.0'])

check(
  '两个包都走 `npm pack` + 解 tarball (不装依赖树), 并且各自 import 一次',
  [
    (packer.match(/'pack',\n\s+`\$\{imagePlugin\.name\}@\$\{imagePlugin\.version\}`/g) ?? []).length,
    packer.includes('`npm pack produced no tarball for ${mnemePlugin.name}'),
    packer.includes('await import(pathToFileURL(join(mnemeRoot, mnemeEntry)).href)'),
    packer.includes('await import(pathToFileURL(join(imageRoot, imageEntry)).href)'),
  ],
  [1, true, true, true],
)

check(
  '记忆插件裁掉的是"只有开发时才用"的那四样 (docs / test / src / scripts 里除运行时那一个 / lib 的 map)',
  [
    packer.includes("for (const name of ['docs', 'test', 'src']) rmSync(join(mnemeRoot, name)"),
    packer.includes("if (name !== 'mneme-runtime.mjs') rmSync(join(mnemeRoot, 'scripts', name)"),
    packer.includes("if (name.endsWith('.map')) rmSync(join(mnemeRoot, 'lib', name)"),
  ],
  [true, true, true],
)

/* ── 二、挂在哪一层 ────────────────────────────────────────────────────── */

/** `PluginRows` 的 OWNED 表: 每一行的 id 与入口 (config 那一段另算) */
const owned = [...rows.matchAll(/Owned\(\s*"([^"]+)",\s*"([^"]+)"/g)].map((one) => [one[1], one[2]])

check(
  'profile patch 里那两行指的都是树里的 lib/index.js',
  owned,
  [
    ['imagegen', 'node_modules/@dickpy/dsh-imagegen/lib/index.js'],
    ['mneme', 'node_modules/@modusensus/dsh-mneme/lib/index.js'],
  ],
)

// 面板要能改的行挂在覆盖层上就永远存不进去, 见文件头第 2 条
check(
  '这两行都不在覆盖层那两张表里',
  [
    (overlay.match(/Row\("imagegen"/g) ?? []).length,
    (overlay.match(/Row\("mneme"/g) ?? []).length,
  ],
  [0, 0],
)

check(
  '盘上写的是 file:// 形态 (即 Loader 眼里那个名字)',
  [rows.includes('append("file://")'), rows.includes("name: '$name'")],
  [true, true],
)

/* ── 三、记忆库与运行时都不许落在工作区 ────────────────────────────────── */

check(
  'mneme 那一行由本应用代写 memoryDir (落在 $DSH_HOME/memory 里)',
  [
    rows.includes('config = ::memoryConfig'),
    rows.includes('memoryDir: \'${File(File(context.filesDir, DshHost.HOME_DIR), "memory").absolutePath}\''),
    rows.includes('config.forEach { add("        $it") }'),
  ],
  [true, true, true],
)

check(
  '自管运行时那一档由 DshHost 钉到 $DSH_HOME 里 (DSH_MNEME_RUNTIME_DIR)',
  [
    host.includes('put("DSH_MNEME_RUNTIME_DIR", File(File(home, "mneme"), "runtime").absolutePath)'),
  ],
  [true],
)

/* ── 四、别人那几行别被顺手改了 ─────────────────────────────────────────── */

check(
  '拆行那几段仍然是"只动 insert 段里自己那一行"',
  [
    rows.includes('fun withoutRow(patch: String, rowId: String): String'),
    rows.includes('val shell = (at - 1 downTo 0).firstOrNull { indentOf(lines[it]) < indent'),
  ],
  [true, true],
)

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
