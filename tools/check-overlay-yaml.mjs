/**
 * 发之前先把 app 会写出来的那两份 YAML 解析一遍
 *
 * 为什么值得单独一步: 那两份都是 Kotlin 用字符串拼的, 而**拼错了的代价是整台 host 起不来**
 * —— 2026-10-05 就是这样: `name:` 的值没加引号, 而 `@` 开头在 YAML 里是保留字符, 于是
 * "bad indentation of a mapping entry", 真机上 host 直接起不来。这一步不需要设备, 只要 js-yaml
 *
 * 两份分别是:
 * - `files/lw/tool-plugin.yml` (命令行覆盖层, [PluginOverlay]) —— 两种形态各一份
 * - `profiles/web/cordis.patch.yml` 里 [PluginRows] 补出来的那一段 (能被设置面板改的那几行)
 *
 * 判据: 每一份都要能被 js-yaml 解析, 且解析出来的结构与预期一致
 *
 * 用法: node tools/check-overlay-yaml.mjs
 */

import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
let yaml = null
for (const candidate of ['js-yaml', 'yaml']) {
  try {
    yaml = require(candidate)
    break
  } catch {
    // 换下一个
  }
}
// 本仓那棵树 (`app/build/host-tree`) 里带着 `yaml`, 而它不在 tools/ 的解析路径上 —— 打好的树在的
// 时候直接取它, 这样这一步不用先装一个包才能跑 (它量的就是"那份 overlay 拼出来能不能解析")
if (yaml === null) {
  const tree = new URL('../app/build/host-tree/node_modules/yaml/package.json', import.meta.url)
  if (existsSync(fileURLToPath(tree))) {
    try {
      yaml = createRequire(fileURLToPath(tree))('yaml')
    } catch {
      yaml = null
    }
  }
}
if (yaml === null) {
  console.error('neither js-yaml nor yaml is installed; run this from the host tree or the repo root')
  process.exit(2)
}

const parse = (text) => (typeof yaml.load === 'function' ? yaml.load(text) : yaml.parse(text))

/** 与 PluginOverlay.write 拼出来的东西逐字对应: 两种形态各一份 (生图那一行不在这里, 见 profileRows) */
const bundleOff = `# Written by Luwi on every host start, edits are overwritten
- insert:
    - id: luwi-channel
      name: '/data/user/0/pkg/files/host/node_modules/luwi-channel/index.mjs'
    - id: dsh-web-mobile
      name: '/data/user/0/pkg/files/host/node_modules/dsh-web-mobile/lib/index.js'
    - id: luwi-dsh-sync
      name: '/data/user/0/pkg/files/host/node_modules/luwi-dsh-sync/index.mjs'
    - id: speech-to-text
      name: '/data/user/0/pkg/files/host/node_modules/@deepseek-ai/dsh-experimental-speech-to-text/lib/index.js'
      config:
        defaultProvider: lw-native
        language: auto
    - id: speech-to-text-api
      name: '/data/user/0/pkg/files/host/node_modules/@deepseek-ai/dsh-experimental-api-speech-to-text/lib/index.js'
    - id: voice-input
      name: '/data/user/0/pkg/files/host/node_modules/@deepseek-ai/dsh-experimental-client-ui-voice-input/lib/index.js'
`

const bundleOn = `# Written by Luwi on every host start, edits are overwritten
# The official voice-input bundle is on, so its rows already exist:
# this only points that provider at this app's own engine
- id: speech-to-text
  name: '@deepseek-ai/dsh-experimental-speech-to-text'
  config:
    defaultProvider: lw-native
    language: auto
- insert:
    - id: luwi-channel
      name: '/data/user/0/pkg/files/host/node_modules/luwi-channel/index.mjs'
    - id: dsh-web-mobile
      name: '/data/user/0/pkg/files/host/node_modules/dsh-web-mobile/lib/index.js'
    - id: luwi-dsh-sync
      name: '/data/user/0/pkg/files/host/node_modules/luwi-dsh-sync/index.mjs'
`

/** PluginRows.withRow 往 profile patch 里补的那一段 (空数组模板那一档)
 *
 * `name:` 是 `file://` 形态而不是裸路径: dsh 读 patch 时会把裸绝对路径锚成这个形态, 而设置面板存配置
 * 时写的 config 行抄的是**锚过之后**那个名字 —— 盘上留裸路径的话, 那一行会被按 "name mismatch" 丢掉,
 * 面板永远回 "overridden by a home patch or command-line overlay" (2026-10-16 在设备上用真代码量到的)
 */
const profileRows = `# Your patch layer for this dsh profile, applied after every bundle layer:
# a top-level YAML array of loader patch entries (id-targeted config
# overrides, disables, and insert lists; \`!!js\` expressions allowed).
- insert:
    - id: imagegen
      name: 'file:///data/user/0/pkg/files/host/node_modules/@dickpy/dsh-imagegen/lib/index.js'
`

/** 曾经发出去过的那一版: 少了一对引号, 就是它把真机的 host 弄挂的 —— 这一条必须被判为坏 */
const broken = bundleOn.replace("name: '@deepseek-ai/dsh-experimental-speech-to-text'", 'name: @deepseek-ai/dsh-experimental-speech-to-text')

let failures = 0
function check(name, condition, detail = '') {
  if (!condition) failures += 1
  console.log(`${condition ? 'ok  ' : 'FAIL'} ${name}${condition ? '' : `  ${detail}`}`)
}

for (const [name, text, wantRows, wantOverride, wantNative] of [
  ['bundle 关着: 六行 insert', bundleOff, 6, false, true],
  ['bundle 开着: 一条覆盖 + 三行 insert', bundleOn, 3, true, true],
  ['profile patch: 生图那一行', profileRows, 1, false, false],
]) {
  let doc = null
  try {
    doc = parse(text)
  } catch (error) {
    check(name, false, `解析失败: ${error.message}`)
    continue
  }
  const override = doc.filter((entry) => entry.insert === undefined)
  const inserted = doc.flatMap((entry) => entry.insert ?? [])
  check(name, inserted.length === wantRows && override.length === (wantOverride ? 1 : 0),
    `insert 行 ${inserted.length} 条 (想要 ${wantRows}), 覆盖行 ${override.length} 条`)
  if (wantNative) {
    check(`${name} · lw-native 指向没丢`,
      JSON.stringify(doc).includes('lw-native'),
      '解析结果里找不到 defaultProvider: lw-native')
  } else {
    check(`${name} · 生图那一行的入口在里面`,
      JSON.stringify(doc).includes('@dickpy/dsh-imagegen'),
      '解析结果里找不到那一行的 name')
    // **这一条是面板能不能存进去的全部**: 盘上必须是与 Loader 眼里那个 (anchoring 之后的) 形态逐字
    // 一致的 `file://` URL, 裸路径会让面板追加的 config 行对不上名字而被丢掉
    const insertedName = inserted[0]?.name
    check(`${name} · 那一行的 name 是 file:// 形态`,
      typeof insertedName === 'string' && insertedName.startsWith('file:///data/'),
      `实际是 ${String(insertedName)}`)
  }
}

let brokenRejected = false
try {
  parse(broken)
} catch {
  brokenRejected = true
}
check('少了引号的那一版会被判为坏 (这就是它当初的代价)', brokenRejected)

console.log(failures === 0 ? '\n每一份都能解析, 且坏形态确实被拒' : `\n${failures} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
