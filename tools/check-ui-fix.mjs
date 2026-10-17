/**
 * Luwi 自己那个 CSS 覆盖层的判据 (2.7.8 批次 4, D8 = b)
 *
 * 这一层只有一处会"静默坏掉": **上游一改 DOM, 我们那条选择器就命中不了任何东西** —— 没有报错、
 * 没有日志, 现象只是"那个毛病又回来了", 而没人会想到是补丁失效。所以这里做三件事:
 *
 *   1. **选择器必须命中**: 拿 `presets/ui-fix/lib/client.js` 里出现的每一个选择器, 去**已构建的**
 *      宿主树里 grep 一遍 (记忆插件那一半在 `@modusensus/dsh-mneme/lib/client.js`, dsh 前端那一半在
 *      `@deepseek-ai/dsh-web-frontend/dist/assets/*.css` 与各个 client 包里)。一个都命中不了就红
 *   2. **包得能被扫到**: `package.json` 要声明 `dsh.client.platform = web` 并导出 `./client`
 *      (client-modules 只认 `exports["./client"]`, 缺了它连加载都不发生)
 *   3. **挂的位置对**: 那一行在 `PluginOverlay` 的两张表里都要有 (官方语音 bundle 开着时走另一张),
 *      而**不许**出现在 `PluginRows` 里 —— 它没有配置要在面板上改
 *
 * **需要先构建**: 这个脚本读的是 `app/build/host-tree` 里那份产物 (与真机上的树同一份)。树不在时
 * 它会说一句"先构建", 而不是假装通过。
 *
 * 用法: node tools/check-ui-fix.mjs
 */

import { existsSync } from 'node:fs'
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

const pkg = JSON.parse(await read('presets/ui-fix/package.json'))
const client = await read('presets/ui-fix/lib/client.js')
const host = await read('presets/ui-fix/index.mjs')
const overlay = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginOverlay.kt')
const rows = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginRows.kt')
const packer = await read('tools/pack-host.mjs')

/* ── 一、包本身的形状 ──────────────────────────────────────────────────── */

check('名字与导出对得上 client-modules 的认法', [pkg.name, pkg.type, pkg.exports['./client']], ['luwi-ui-fix', 'module', './lib/client.js'])
check('声明了自己是一个 web 客户端插件', pkg.dsh?.client?.platform, 'web')
check('宿主那一半是空的 (apply 里什么都不做)', [host.includes('export function apply()'), host.includes("export const name = 'luwi-ui-fix'")], [true, true])
check('客户端那一半按 dsh 的模块装载格式写 (不是普通 ESM)', [
  client.includes('window.__ModuleLoader__.load({'),
  client.includes("id: 'luwi-ui-fix'"),
  client.includes('exports.apply = apply'),
  // 写成 `export ...` 会被当成经典脚本解析: 2026-10-17 在模拟器上就是这句趴下的
  /^export /m.test(client.replace(/\/\*[\s\S]*?\*\//g, '')),
], [true, true, true, false])
check('客户端那一半在 ctx.effect 里挂一段 <style>, 并且交回摘掉它的函数', [
  client.includes("document.createElement('style')"),
  client.includes('document.head.appendChild(tag)'),
  client.includes('tag.remove()'),
  client.includes('ctx.effect(install)'),
], [true, true, true, true])

/* ── 二、选择器必须在已构建的树里命中 ──────────────────────────────────── */

const selectors = [...client.matchAll(/'[ ]{2}((?:[a-z-]+\[[^\]]+\]|[.\w-]+(?:\[[^\]]+\])?)) ?\{'/g)]
  .map((one) => one[1])

const tree = new URL('../app/build/host-tree/node_modules/', import.meta.url)
const treePath = (rel) => new URL(rel, tree).pathname.replace(/^\/([A-Za-z]:)/, '$1')

/** 已构建的产物里那一串字在不在: 先把可能很大的文件读成文本 */
async function contains(rel, needle) {
  const file = treePath(rel)
  if (!existsSync(file)) return 'no-file'
  return (await readFile(file, 'utf8')).includes(needle)
}

if (!existsSync(treePath('@modusensus/dsh-mneme/lib/client.js'))) {
  console.log('SKIP 选择器这一节: 宿主树里没有 @modusensus/dsh-mneme (先跑一次构建)')
} else {
  const mnemeHost = '@modusensus/dsh-mneme/lib/client.js'
  const frontendDir = treePath('@deepseek-ai/dsh-web-frontend/dist/assets')
  const frontendCss = (await readdir(frontendDir)).filter((name) => name.endsWith('.css')).map((name) => `@deepseek-ai/dsh-web-frontend/dist/assets/${name}`)
  const frontendText = (await Promise.all(frontendCss.map((rel) => readFile(treePath(rel), 'utf8')))).join('\n')

  for (const selector of selectors) {
    // `span[data-files-path]` 那一条查的是属性名 (它由文件面板那个 client 包写上去, 不在前端那份 CSS 里),
    // `.mneme-*` 查的是记忆插件自己那份客户端产物
    const needle = selector.startsWith('span[') ? 'data-files-path' : selector
    const where = needle === 'data-files-path'
      ? ((await contains('@deepseek-ai/dsh-client-ui-sidebar-files/lib/client.js', needle)) === true ? 'files' : 'missing')
      : (await contains(mnemeHost, needle)) === true ? 'mneme' : 'missing'
    check(`选择器 ${selector} 在已构建的产物里命中`, where, needle === 'data-files-path' ? 'files' : 'mneme')
  }

  check('两段补丁用到的那个 CSS 变量在这棵树里有定义', frontendText.includes('--dsw-alias-border-l2'), true)
}

/* ── 三、挂的位置 ──────────────────────────────────────────────────────── */

check('两张表里各有一行 (语音 bundle 开着时走另一张)', [
  (overlay.match(/Row\("luwi-ui-fix"/g) ?? []).length,
  overlay.includes('node_modules/luwi-ui-fix/index.mjs'),
], [2, true])
check('它不在 PluginRows 里 (那一层是给"面板要能改配置"的行)', rows.includes('luwi-ui-fix'), false)
check('打包时把 presets/ui-fix 拷进树里', [
  packer.includes("new URL('../presets/ui-fix', import.meta.url)"),
  packer.includes("join(out, 'node_modules', 'luwi-ui-fix')"),
], [true, true])

/* ── 四、右栏的浏览器那一格: 上游默认在 web profile 上关掉它 ────────────── */

check('覆盖层里按 id 把 ui-sidebar-browser 打开 (disabled: false)', [
  overlay.includes('private const val BROWSER_ROW = "ui-sidebar-browser"'),
  overlay.includes('"@deepseek-ai/dsh-client-ui-sidebar-browser"'),
  overlay.includes('appendLine("  disabled: false")'),
  // 文件不在树里就不写那一行: 指向不存在的文件会让 Loader 报一条载入失败
  overlay.includes('val browserPresent = File(root, BROWSER_ENTRY).isFile'),
], [true, true, true, true])

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
