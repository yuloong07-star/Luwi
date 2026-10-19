/**
 * 把 `presets/docx-preview/src/` 打成宿主树里那份 `lib/client.js` (2.7.9 第 6 条 T9)
 *
 * **不进 Gradle 构建**: 产物提交进仓库, 与 `tools/make-icons.py` 那条"脚本只手工跑"的做法一致 ——
 * 出包时不联网、也不要求构建机装 npm 包
 *
 * 打出来的形状是 dsh 的客户端模块格式, 不是普通 ESM:
 *
 *     window.__ModuleLoader__.load({ id, factory: (require) => { ... return module.exports } })
 *
 * 于是 `react` 必须留成 external: 它由那棵树的模块表提供, 而 `require` 只有 factory 里才有 ——
 * 所以整包按 CJS 打 (外部依赖变成 factory 里那句 `require("react")`), 再由这里套上外层壳
 *
 * 依赖 (版本钉死, 变了要重打):
 *
 * | 包 | 用途 | 许可 |
 * | :-- | :-- | :-- |
 * | `docx-preview` | docx → HTML (分页 / 表格 / 图片 / 页眉页脚) | Apache-2.0 |
 * | `jszip` | 解 pptx 那只 zip (docx-preview 自己也用它) | MIT |
 *
 * 第一次跑会 `npm install` 到 `presets/docx-preview/.deps` (已进 .gitignore); 之后复用。
 *
 * 用法: node tools/build-docx-preview.mjs [--install]
 */

import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const packageDir = join(root, 'presets', 'docx-preview')
const deps = join(packageDir, '.deps')
const entry = join(packageDir, 'src', 'index.js')
const out = join(packageDir, 'lib', 'client.js')

/** 钉死的依赖: 换版本就有新行为, 必须重打并重新验那三种文件 */
const PACKAGES = [
  'docx-preview@0.4.1',
  'dompurify@3.2.6',
  'jszip@3.10.1',
  'esbuild@0.28.2',
]

/** 模块 id 必须与 package.json 的 name 一字不差 (模块图的行 id 就是包名) */
const ID = 'lw-docx-preview'

if (!existsSync(entry)) {
  console.error(`没有源码: ${entry}`)
  process.exit(1)
}

if (process.argv.includes('--install') || !existsSync(join(deps, 'node_modules', 'esbuild'))) {
  console.log(`装依赖到 ${deps} ...`)
  mkdirSync(deps, { recursive: true })
  execFileSync('npm', [
    'install', '--prefix', deps, '--no-audit', '--no-fund', '--no-save', ...PACKAGES,
  ], { stdio: 'inherit', shell: true })
}

const requireFromDeps = createRequire(join(deps, 'package.json'))
const esbuild = requireFromDeps('esbuild')

const result = await esbuild.build({
  entryPoints: [entry],
  bundle: true,
  minify: true,
  format: 'cjs',
  platform: 'browser',
  target: ['chrome88'],
  // react 由宿主的模块表提供, 不进包; 其余的 (docx-preview / jszip) 全打进来
  external: ['react'],
  nodePaths: [join(deps, 'node_modules')],
  define: { 'process.env.NODE_ENV': '"production"' },
  legalComments: 'none',
  write: false,
  logLevel: 'warning',
})

const body = result.outputFiles[0].text.trim()
const wrapped = [
  '/**',
  ' * 由 tools/build-docx-preview.mjs 生成的产物 —— 别手改, 改 src/ 再重打',
  ' *',
  ' * 这一份不是普通 ESM: dsh 的客户端模块按 window.__ModuleLoader__.load 装载, 而顶层写成',
  ' * `export ...` 会被当成经典脚本解析, 报 SyntaxError: Unexpected token export',
  ' */',
  'window.__ModuleLoader__.load({',
  `  id: ${JSON.stringify(ID)},`,
  '  factory: (require) => {',
  '    var module = { exports: {} }',
  '    var exports = module.exports',
  body,
  '    return module.exports',
  '  },',
  '})',
  '',
].join('\n')

mkdirSync(join(packageDir, 'lib'), { recursive: true })
writeFileSync(out, wrapped)

const bytes = Buffer.byteLength(wrapped)
console.log(`build-docx-preview: ${out}`)
console.log(`  ${(bytes / 1024).toFixed(1)} KB, gzip ${(gzipSync(wrapped).length / 1024).toFixed(1)} KB`)
