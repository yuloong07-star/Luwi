/**
 * Office 预览那一包的判据 (2.7.9 第 6 条 T8 试探 + T9 正文)
 *
 * 这一包有两个会静默坏掉的地方, 两边都不报错:
 *
 *   1. **挂不上 / 抢不过**: `package.json` 得声明 `dsh.client.platform = web` 并导出 `./client`;
 *      `lib/client.js` 里那个 `id` 必须与包名一字不差 (模块图的行 id 就是包名); 类型得是
 *      `extension` 档, 而模式末尾**不能带 `$`** (picomatch 认 glob 不认正则, 见 T8 回执)
 *   2. **正文读不出字节 / 隔离漏了**: 地址里的路径是**相对会话工作区**的, 读字节要走
 *      `remote.workspaceFiles.readBytes`; 渲染结果必须进 **shadow root** (从不进宿主 DOM),
 *      进之前过 `dompurify`; 双指放大只改文档自己的 `zoom` (容器的 touch-action 别让浏览器接管)
 *
 * 另外钉住三件"改了就会骗人"的事: 大小闸 10 MB、三种失败分开说 (读不出来 / 太大 / 读不了)、
 * `lib/client.js` 是 `tools/build-docx-preview.mjs` 的产物 (改了 src 没重打会红)。
 *
 * **需要先构建**才有第 5 节那份树里的拷贝可比对 (与 check-ui-fix.mjs 同一个口径)。
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
const source = await read('presets/docx-preview/src/index.js')
const docx = await read('presets/docx-preview/src/docx.js')
const pptx = await read('presets/docx-preview/src/pptx.js')
const host = await read('presets/docx-preview/index.mjs')
const builder = await read('tools/build-docx-preview.mjs')
const overlay = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginOverlay.kt')
const rows = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginRows.kt')
const packer = await read('tools/pack-host.mjs')

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

check('客户端那一半是打出来的 dsh 模块 (不是普通 ESM)', [
  client.includes('window.__ModuleLoader__.load({'),
  client.includes(`id: ${JSON.stringify(pkg.name)},`),
  /^export /m.test(code),
], [true, true, false])
check('这一份是 tools/build-docx-preview.mjs 的产物 (改 src 没重打会红)', [
  client.includes('由 tools/build-docx-preview.mjs 生成的产物'),
  builder.includes(`const ID = '${pkg.name}'`),
  builder.includes("'docx-preview@0.4.1'"),
  builder.includes("'jszip@3.10.1'"),
], [true, true, true, true])
check('react 留成 external, 由宿主的模块表给 (不打第二份进来)', [
  client.includes('require("react")'),
  builder.includes("external: ['react']"),
], [true, true])

/* ── 二、类型与模式 ────────────────────────────────────────────────────── */

const patterns = /patterns: (\[[^\n]+\])/.exec(source)?.[1] ?? ''
check('类型是 extension 档, 认 docx 与 pptx 两条后缀', [
  /priority: 'extension'/.test(source),
  patterns.includes('\\.docx'),
  patterns.includes('\\.pptx'),
  (patterns.match(/dsh-resource:\/\/file\//g) ?? []).length,
], [true, true, true, 2])
check('模式末尾不许带 `$` (picomatch 里它是字面字符, 带了就一条地址都命中不了)', [
  patterns.includes('$'),
  // 正文那个 kind 与 id 各管一件事
  /const KIND = 'lw-office'/.test(source),
  /const ID = 'lw-docx-preview'/.test(source),
], [false, true, true])

/* ── 三、正文怎么读、怎么渲染 ──────────────────────────────────────────── */

check('读字节走 remote.workspaceFiles.readBytes, 会话取地址里那一场', [
  source.includes('remote.workspaceFiles.readBytes'),
  source.includes("inject = ['slots', 'sidebarRightTabs', 'remote', 'remote.workspaceFiles']"),
  /function parseAddress\(address\)/.test(source),
], [true, true, true])
check('渲染结果进 shadow root, 从不进宿主 DOM', [
  source.includes('attachShadow'),
  source.includes('root.innerHTML'),
  // 不许把文档 HTML 直接塞进宿主那一页
  /dangerouslySetInnerHTML/.test(source),
], [true, true, false])
check('进 shadow root 之前过一遍 dompurify (去掉脚本与 on* 属性, 放行 data: 图)', [
  source.includes("from 'dompurify'"),
  source.includes('DOMPurify.sanitize'),
  source.includes("ADD_DATA_URI_TAGS: ['img']"),
  builder.includes("'dompurify@3.2.6'"),
], [true, true, true, true])
check('双指只放大文档: 容器 touch-action 不让浏览器接管, 改的是内层那份文档的 zoom', [
  source.includes("touchAction: 'pan-x pan-y'"),
  source.includes("host.style.zoom"),
  source.includes("container.addEventListener('touchmove', onMove, { passive: false })"),
  source.includes('DOUBLE_TAP_MS'),
  source.includes("'data-lw-office-reset'"),
], [true, true, true, true, true])
check('大小闸 10 MB, 三种失败分开说', [
  source.includes('const MAX_BYTES = 10 * 1024 * 1024'),
  source.includes('读取失败：'),
  source.includes('文件太大'),
  source.includes('这份文件读不了'),
], [true, true, true, true])
check('docx 走 docx-preview (分页/页眉页脚), 图片内联成 base64', [
  docx.includes("from 'docx-preview'"),
  docx.includes('useBase64URL: true'),
  docx.includes('breakPages: true'),
], [true, true, true])
check('"按手机宽度重排"那段排在 docx-preview 自己的样式之后 (A4 宽 794 塞不进 412 的右栏)', [
  source.includes('const FIT_CSS'),
  /\$\{rendered\.css\}\\n\$\{FIT_CSS\}/.test(source),
  source.includes('width: auto !important'),
  // 页面容器必须从 flex + center 改成块级: 页比容器宽时 flex 会让它**两边一起**溢出, 左边被裁
  source.includes('.lw-docx-wrapper { display: block !important'),
], [true, true, true, true])
check('pptx 自己解 OOXML: 按 sldIdLst 排页, 只收图片关系, 有上限', [
  pptx.includes("from 'jszip'"),
  pptx.includes('p:sldId'),
  pptx.includes("endsWith('/image')"),
  pptx.includes('MAX_SLIDES'),
], [true, true, true, true])

/* ── 四、挂在哪一层 ────────────────────────────────────────────────────── */

function tableOf(kotlin, marker) {
  const at = kotlin.indexOf(marker)
  if (at === -1) return []
  const body = kotlin.slice(at)
  const end = body.indexOf('\n    )')
  return [...(end === -1 ? body : body.slice(0, end)).matchAll(/Row\(\s*"([^"]+)"/g)].map((one) => one[1])
}

check('覆盖层那张表里有它, 入口指向树里的 index.mjs', [
  tableOf(overlay, 'private val PLUGINS = listOf(').includes(pkg.name),
  overlay.includes(`Row("${pkg.name}", "node_modules/${pkg.name}/index.mjs")`),
], [true, true])
check('第二张表 (官方语音 bundle 开着时走的那张) 也有它', tableOf(overlay, 'private val OWN_PLUGINS = listOf(').includes(pkg.name), true)
check('它不在 PluginRows 里 (这一包没有配置要在面板上改)', rows.includes(pkg.name), false)
check('pack-host 从 presets/docx-preview 拷进 node_modules/lw-docx-preview', [
  packer.includes("new URL('../presets/docx-preview', import.meta.url)"),
  packer.includes("join(out, 'node_modules', 'lw-docx-preview')"),
], [true, true])

/* ── 五、树里有它, 且与源一字不差 (要先构建) ──────────────────────────── */

const tree = new URL('app/build/host-tree/', root)
const installed = new URL(`node_modules/${pkg.name}/`, tree)
if (!existsSync(tree)) {
  console.log('note  app/build/host-tree 不在 (还没构建): 第 5 节跳过')
} else if (!existsSync(installed)) {
  check('树里已经有这一份 (改完源要重新跑 pack-hostTree)', false, true)
} else {
  const installedPkg = JSON.parse(await readFile(new URL('package.json', installed), 'utf8'))
  const installedClient = await readFile(new URL('lib/client.js', installed), 'utf8')
  check('树里那一份是拷进去的, 包名与源相同', installedPkg.name, pkg.name)
  check('树里那份客户端与源一字不差 (改完重打之后忘了构建会红)', installedClient === client, true)
}

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
