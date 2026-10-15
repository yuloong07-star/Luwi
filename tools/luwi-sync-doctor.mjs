/**
 * luwi-dsh-sync 的作者侧预检 (不需要把插件挂上, 不需要设备)
 *
 * 为什么值得单独一步: 2026-10-13 为了让 session-sync 插件的 import 通过, 有人把 54 个
 * @deepseek-ai 核心包手工拷进了 `profiles/desktop/node_modules` —— 那一层在模块解析里优先于
 * 运行安装的注入表 (`profiles/node_modules`), 而那份拷贝缺 koffi, 于是 Host 自己的 spawn
 * 设施被自家 profile 遮蔽, pwsh / glob / grep 全废。所以**装插件之前**先问一句:
 * ② 层有没有 pnpm 不认账的包?
 *
 * 权威判据是 `node_modules/.modules.yaml` 的 `hoistedLocations` (pnpm 唯一的账本)。
 * 本脚本还顺带看: profile 存不存在 / 插件装没装 / 它在 bundles 里选没选中 / git 在不在 PATH。
 *
 * **判据边界 (别把这一步当全部)**: "peer 解析落点" 这一半在本脚本里只能给出提示 ——
 * dsh 的注入表 (③ 层) 只活在 Host 进程里, 裸 node 复现不出来。权威读数是宿主里的 `/sync doctor`
 *
 * 用法:
 *   node tools/luwi-sync-doctor.mjs                       # 默认看 $DSH_HOME/profiles/desktop
 *   node tools/luwi-sync-doctor.mjs --profile <目录>
 *   node tools/luwi-sync-doctor.mjs --json
 */

import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { parseHoistedLocations, profileExtraneous } from '../host-plugins/luwi-dsh-sync/lib/luwi-doctor.mjs'

/** 明文保留清单: 这些包留在 ② 层不算污染 (叶子件, 无第三方依赖) */
const KEEP = ['@deepseek-ai/dsh-terminal']

/** 插件包名 */
const PLUGIN = 'luwi-dsh-sync'

/** 改名之前的包名 (patch 里出现它 = 历史残留) */
const OLD_PLUGIN = 'dsh-session-sync'

/**
 * 找出 patch 里**手写插入行**用的包名 —— 只有 `- insert:` 块里的行会多插一行。
 *
 * 顶层的 `- id: X` 是覆盖行 (改写 bundle 插进来的那一行), 不算重复挂载, 所以不在这里。
 * 这个文件是手写的 YAML, 不引 yaml 依赖: 按顶层 `- ` 起止扫块就够 (块内再缩进的 `name:` 才是行名)。
 * @param {string} text - cordis.patch.yml 的文本。
 * @returns {string[]} 手写 insert 块里的行名。
 */
function handWrittenInsertNames(text) {
  const names = []
  let inInsert = false
  for (const line of text.split('\n')) {
    if (/^-\s/u.test(line)) inInsert = /^-\s*insert:\s*$/u.test(line)
    if (!inInsert) continue
    const match = /^\s+name:\s*['"]?([^'"\s]+)['"]?\s*$/u.exec(line)
    if (match) names.push(match[1])
  }
  return names
}

/**
 * 列一个 profile 的 node_modules 顶层 + @scope/* 的包名。
 * @param {string} dir - node_modules 目录。
 * @returns {string[]} 包名。
 */
function listPresent(dir) {
  const names = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue
    if (entry.name.startsWith('@')) {
      for (const inner of readdirSync(join(dir, entry.name), { withFileTypes: true })) {
        if (inner.isDirectory()) names.push(`${entry.name}/${inner.name}`)
      }
      continue
    }
    names.push(entry.name)
  }
  return names
}

/**
 * 读 profile 的 manifest（缺文件给 undefined）。
 * @param {string} profile - profile 目录。
 * @returns {object|undefined} package.json 的内容。
 */
function readManifest(profile) {
  const file = join(profile, 'package.json')
  if (!existsSync(file)) return undefined
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return undefined
  }
}

/**
 * 主流程。
 * @returns {number} 退出码（0=PASS, 1=FAIL, 2=用不了）。
 */
function main() {
  const { values } = parseArgs({ options: { profile: { type: 'string' }, json: { type: 'boolean', default: false } } })
  const home = process.env.DSH_HOME ?? ''
  const profile = resolve(values.profile ?? (home === '' ? '' : join(home, 'profiles', 'desktop')))
  const lines = []
  const problems = []
  const notes = []
  const say = (line) => lines.push(line)

  if (!profile || !existsSync(profile)) {
    process.stderr.write(`profile 目录不存在: ${profile === '' ? '(DSH_HOME 没设, 也没给 --profile)' : profile}\n`)
    return 2
  }
  const modules = join(profile, 'node_modules')
  say(`profile    : ${profile}`)
  if (!existsSync(modules)) {
    process.stderr.write(`${modules} 不存在 (profile 没初始化?)\n`)
    return 2
  }

  // ② 层 extraneous —— 这一步就是本次事故的探针
  let extraneous = []
  let layout = '未扫'
  try {
    const tracked = parseHoistedLocations(readFileSync(join(modules, '.modules.yaml'), 'utf8'))
    const present = listPresent(modules)
    const scan = profileExtraneous({ tracked, present, keep: KEEP })
    extraneous = scan.extraneous
    layout = `pnpm 账本 ${scan.trackedCount} 个 / 盘上 ${scan.presentCount} 个`
    if (extraneous.length > 0) {
      problems.push(`② 层有 ${extraneous.length} 个 pnpm 不认账的包 —— 它们在解析里优先于运行安装, 是本次事故的形状: 搬走它们 (见 .lwtmp/g1/repair-pc-profile.ps1)`)
    }
  } catch (error) {
    problems.push(`读 .modules.yaml 失败: ${error?.message ?? error}`)
  }
  say(`layout     : ${layout}`)
  for (const name of extraneous.slice(0, 40)) say(`  extraneous: ${name}`)
  if (extraneous.length > 40) say(`  extraneous: … 还有 ${extraneous.length - 40} 个`)

  // 插件装没装 / 选没选中
  const installed = existsSync(join(modules, PLUGIN))
  const manifest = readManifest(profile)
  const bundles = Array.isArray(manifest?.dsh?.profile?.bundles) ? manifest.dsh.profile.bundles : []
  const selected = bundles.includes(PLUGIN)
  say(`plugin     : ${PLUGIN} ${installed ? '已装' : '没装'} / bundles ${selected ? '已选中' : '没选中'}`)
  if (selected && !installed) problems.push('bundles 里选了它, 但 node_modules 里没有它 —— 这一行会解析失败')
  if (installed && !selected) notes.push('装了但没选 bundle: 它不会挂 (要想它跑起来就在插件页选中)')

  // patch 里有没有**手写的插入行** (手写插入行 + bundle 行并存 = 挂两次)
  //
  // 判据必须分清两种写法 (2026-10-14 实测, 用宿主里的 cordis_inspect Config 目录核对过):
  //   · 顶层 `- id: luwi-dsh-sync` + config —— 这是**覆盖行**: 它改写 bundle patch 插进来的那一行
  //     的 config, 不会多出一行。这台机器上 dsh-doc / dsh-mneme / prompt-system 等一串插件都是
  //     这个形态, 而活着的 Loader 里每个 id 只有一个 entry
  //     (`include:luwi-dsh-sync` / patchId `luwi-dsh-sync`, 唯一)
  //   · `- insert:` 块里的行 —— 那才**多插一行**, 与 bundle 行并存才是挂两次 (C6 只 warn)
  // 所以只认第二种: 进了 insert 块之后才认 name 行。早先按"patch 文本里出现过这个包名"就报警,
  // 把正规的覆盖行判成了重复挂载 —— 设计文档 §7.5 要的正是"覆盖行 + 选中 bundle"这一对
  const patch = join(profile, 'cordis.patch.yml')
  if (existsSync(patch)) {
    const text = readFileSync(patch, 'utf8')
    const inserted = handWrittenInsertNames(text)
    const handWritten = inserted.some((name) => name === PLUGIN || name === OLD_PLUGIN)
    say(`patch      : ${handWritten ? `里有手写的 insert 行 (${inserted.join(', ')})` : '没有手写的 insert 行 (覆盖行不算重复挂载)'}`)
    if (handWritten && selected) {
      problems.push('bundle 行与 patch 里手写的 insert 行同时存在 —— 会挂两次 (插件自己会拒第二次并 warn, 但别让它走到那一步)')
    }
    if (new RegExp(OLD_PLUGIN, 'u').test(text)) problems.push(`patch 里还有旧名字 ${OLD_PLUGIN}`)
  }

  // git 在不在
  const git = spawnSync('git', ['--version'], { encoding: 'utf8' })
  const gitOk = git.status === 0
  say(`git        : ${gitOk ? String(git.stdout).trim() : `跑不动 (${git.error?.message ?? `exit ${git.status}`})`}`)
  if (!gitOk) problems.push('PATH 上没有可用的 git —— 电脑侧的 runner 走 ctx.subprocess, 必须能起 git')

  notes.push('peer 解析落点这一半在宿主里才准: 挂上之后跑 /sync doctor 拿权威读数')

  const json = { profile, extraneous, layout, installed, selected, git: gitOk, problems, notes }
  if (values.json) {
    process.stdout.write(`${JSON.stringify(json, undefined, 2)}\n`)
  } else {
    for (const line of lines) process.stdout.write(`${line}\n`)
    for (const note of notes) process.stdout.write(`note       : ${note}\n`)
    if (problems.length > 0) {
      process.stdout.write(`\n预检 FAIL (${problems.length} 条):\n`)
      for (const problem of problems) process.stdout.write(`  - ${problem}\n`)
    } else {
      process.stdout.write('\n预检 PASS\n')
    }
  }
  return problems.length === 0 ? 0 : 1
}

process.exit(main())
