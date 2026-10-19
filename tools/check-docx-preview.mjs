/**
 * docx 试探的判据 (2.7.9 第 6 条 T8)
 *
 * 这一包整条路只有一处会静默坏掉: **它挂不上, 或者挂上了却没抢过上游那个 `fallback` 文本预览** ——
 * 没有报错、日志干净, 现象只是"右栏点开 docx 还是那句不认识"。而挂不上的原因几乎全在接缝上, 四条:
 *
 *   1. **包本身**: `package.json` 要声明 `dsh.client.platform = web` 并导出 `./client` (client-modules
 *      只认 `exports["./client"]`), 客户端那一半还要按 `window.__ModuleLoader__.load` 那个形状写
 *   2. **装载 id**: dsh 拿**包名**当模块图的行 id (`packages/client/modules/src/index.ts` 的
 *      `graphRow(packageName, …)`), 于是 `lib/client.js` 里那个 `id` 必须与 `package.json` 的
 *      `name` 一字不差, 差一个字就是"脚本加载了, 却没人认领"
 *   3. **挂在哪一层**: 命令行覆盖层的 `PLUGINS` 与 `OWN_PLUGINS` **两张表都要有** (官方语音 bundle
 *      开着时走后者), 而它不能出现在 `PluginRows` 里 —— 那一层是给"设置面板要能改的配置"用的
 *   4. **树里得有它**: `tools/pack-host.mjs` 把 `presets/docx-preview` 拷进 `node_modules/lw-docx-preview`,
 *      拷漏了 `PluginOverlay` 那一行会因为入口文件不存在被跳过 (只警告, 不报错)
 *
 * **需要先构建**才有第 4 条的产物可比对 (与 `check-ui-fix.mjs` 同一个口径): 树不在时那一节按"没构建"
 * 说一句, 不假装通过。
 *
 * 用法: node tools/check-docx-preview.mjs
 */

import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'

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

const pkg = JSON.parse(await read('presets/docx-preview/package.json'))
const client = await read('presets/docx-preview/lib/client.js')
const host = await read('presets/docx-preview/index.mjs')
const overlay = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginOverlay.kt')
const rows = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginRows.kt')
const packer = await read('tools/pack-host.mjs')

/** 去掉注释与字符串里的干扰之后, 才拿 `^export` 判"是不是普通 ESM" */
const code = client.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')

/* ── 一、包本身的形状 ──────────────────────────────────────────────────── */

check('名字与导出对得上 client-modules 的认法', [
  pkg.name, pkg.type, pkg.exports['./client'],
], ['lw-docx-preview', 'module', './lib/client.js'])
check('声明了自己是一个 web 客户端插件', pkg.dsh?.client?.platform, 'web')
check('宿主那一半是空的 (只有 name 与空 apply)', [
  host.includes("export const name = 'lw-docx-preview'"),
  host.includes('export function apply() {}'),
], [true, true])

check('客户端那一半按 dsh 的模块装载格式写 (不是普通 ESM)', [
  client.includes('window.__ModuleLoader__.load({'),
  client.includes("id: 'lw-docx-preview'"),
  client.includes('exports.apply = apply'),
  /^export /m.test(code),
], [true, true, true, false])
check('装载 id 与包名一字不差 (模块图的行 id 是包名)', [
  /id: '([^']+)',\s*\n\s*factory:/.exec(client)?.[1],
  pkg.name,
], [pkg.name, pkg.name])
check('两半都交了插件面: apply 与 inject (两个服务)', [
  client.includes('exports.inject = ['),
  /exports\.inject = \[\s*'slots',\s*'sidebarRightTabs'\s*\]/.test(client),
], [true, true])
check('React 从模块表拿, 不自己打一份进来', client.includes("require('react')"), true)

/* ── 二、类型与正文 ────────────────────────────────────────────────────── */

check('类型是 extension 档 (这一整步的关键: 抢的就是上游那个 fallback)', [
  /priority: 'extension'/.test(client),
  client.includes("kind: KIND"),
  /var KIND = 'lw-docx'/.test(client),
], [true, true, true])
/** 客户端里那条类型声明实际用的模式 (源码里是双反斜杠转义, 这里还原成一条能读的 glob) */
const pattern = /patterns: \['([^']+)'\]/.exec(client)?.[1]
const glob = pattern === undefined ? undefined : pattern.replaceAll('\\\\', '\\')
check('只认 .docx 后缀, 且是整条地址匹配', [
  glob,
  // **末尾不许有 `$`**: 模式交给 picomatch, 而它认 glob 不认正则, `$` 在那边是字面字符 ——
  // 带上 `$` 就要求地址以 `$` 结尾, 一个地址都命中不了 (2026-10-19 T8 在模拟器上踩过)
  pattern === undefined ? true : pattern.endsWith('$'),
], ['dsh-resource://file/[\\s\\S]+\\.docx', false])
check('正文挂在 keyed seat 上, key 是定义自己的 id', [
  /ctx\.slots\.register\(\{\s*name: 'sidebar\.right\.pane\.tab',\s*key: ID\s*\}/.test(client),
  client.includes("name: 'sidebar.right.pane.tab'"),
], [true, true])
check('正文只有那一行 hello docx (这一档刻意不读字节)', [
  client.includes("'hello docx'"),
  client.includes("'data-lw-docx-hello'"),
  // 这一档不许出现读文件的调用: 接 mammoth 是 T9 的事 (只看代码, 注释里写着"T9 才会加"不算)
  /readBytes|workspaceFiles|resources\./.test(code),
], [true, true, false])

/* ── 三、挂在哪一层 ────────────────────────────────────────────────────── */

/** 从 `listOf(` 开始到那一段结束: 只数表里的 `Row("…")`, 不吃注释 */
function tableOf(kotlin, marker) {
  const at = kotlin.indexOf(marker)
  if (at === -1) return []
  const body = kotlin.slice(at)
  const end = body.indexOf('\n    )')
  return [...(end === -1 ? body : body.slice(0, end)).matchAll(/Row\(\s*"([^"]+)"/g)].map((one) => one[1])
}

const plugins = tableOf(overlay, 'private val PLUGINS = listOf(')
const own = tableOf(overlay, 'private val OWN_PLUGINS = listOf(')

check('覆盖层那张表里有它, 且入口指向树里的 index.mjs', [
  plugins.includes('lw-docx-preview'),
  overlay.includes('Row("lw-docx-preview", "node_modules/lw-docx-preview/index.mjs")'),
], [true, true])
check('第二张表 (官方语音 bundle 开着时走的那张) 也有它', own.includes('lw-docx-preview'), true)
check('它不在 PluginRows 里 (这一包没有配置要在面板上改)', rows.includes('lw-docx-preview'), false)

/* ── 四、树里有它, 且与源一字不差 (要先构建) ──────────────────────────── */

const tree = new URL('app/build/host-tree/', root)
const installed = new URL('node_modules/lw-docx-preview/', tree)
if (!existsSync(tree)) {
  console.log('note  app/build/host-tree 不在 (还没构建): 第 4 节跳过')
} else if (!existsSync(installed)) {
  check('树里已经有这一份 (改完源要重新跑 pack-hostTree)', false, true)
} else {
  const installedPkg = JSON.parse(await readFile(new URL('package.json', installed), 'utf8'))
  const installedClient = await readFile(new URL('lib/client.js', installed), 'utf8')
  check('树里那一份是拷进去的, 包名与源相同', installedPkg.name, pkg.name)
  check('树里那份客户端与源一字不差 (改完源没重新打包会红)', installedClient === client, true)
}

check('pack-host 从 presets/docx-preview 拷进 node_modules/lw-docx-preview', [
  packer.includes("new URL('../presets/docx-preview', import.meta.url)"),
  packer.includes("join(out, 'node_modules', 'lw-docx-preview')"),
], [true, true])

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
