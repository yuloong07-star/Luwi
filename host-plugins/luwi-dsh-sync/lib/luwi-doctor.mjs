// lib/luwi-doctor.mjs — 只读自检的纯函数（零 DSH 依赖，零 fs 依赖：探测从外面注入）。
//
// 这一份是 2026-10-13 那次事故的探针。事故形状（摘要，全文见 docs/Luwi-dsh-sync-设计.md）:
// 为了让本插件的 import 通过, 有人把 54 个 @deepseek-ai 核心包手工拷进了
// `$DSH_HOME/profiles/<p>/node_modules`（解析三层里的 ② 层）。② 层是**物理目录**,
// 优先于 ③ 层（`$DSH_HOME/profiles/node_modules`, 运行安装的注入表）—— 而那份拷贝缺
// 非 @deepseek-ai 的依赖（koffi 等）, 于是 Host 自己的 spawn 设施被自家 profile 遮蔽,
// pwsh / glob / grep 全废。判据从来不是"能不能 import", 而是"解析落点是运行安装还是 ② 层",
// 以及"② 层有没有 pnpm 不认账的包"—— `node_modules/.modules.yaml` 的 `hoistedLocations`
// 就是 pnpm 唯一的账本。
//
// 这里只放纯函数与一次 runDoctor 的编排; fs / import.meta.resolve / 子进程探测都由调用方注入,
// 这样它既能被单测驱动, 也能在没有宿主的环境里跑

/**
 * 每个 host peer 为什么被依赖（给人看的一句; 缺项就不显示）。
 * @type {Readonly<Record<string, string>>}
 */
export const HOST_PEER_WHY = Object.freeze({
  '@deepseek-ai/cordis': 'Context / 服务上下文（探针用）',
  '@deepseek-ai/schemastery': 'Config schema 校验',
  '@deepseek-ai/dsh-session': 'SessionStore 与已知事件类型表',
  '@deepseek-ai/dsh-llm': 'createUserMessage（fork 通知）',
  '@deepseek-ai/dsh-tools': 'defineTool',
})

/**
 * 从清单里取 peer 名单（doctor 的探测对象）。
 * @param {unknown} manifest - 解析后的 package.json。
 * @returns {string[]} peerDependencies 的键, 排序稳定。
 */
export function peersFromManifest(manifest) {
  if (typeof manifest !== 'object' || manifest === null) return []
  const peers = /** @type {{peerDependencies?: unknown}} */ (manifest).peerDependencies
  if (typeof peers !== 'object' || peers === null) return []
  return Object.keys(peers).sort()
}

/**
 * 从清单里取第三方依赖名单（doctor 顺带看一眼它们在不在 ② 层）。
 * @param {unknown} manifest - 解析后的 package.json。
 * @returns {string[]} dependencies 的键, 排序稳定。
 */
export function dependenciesFromManifest(manifest) {
  if (typeof manifest !== 'object' || manifest === null) return []
  const deps = /** @type {{dependencies?: unknown}} */ (manifest).dependencies
  if (typeof deps !== 'object' || deps === null) return []
  return Object.keys(deps).sort()
}

/**
 * 解析 `node_modules/.modules.yaml` 的 `hoistedLocations`, 取出 pnpm 认为 ② 层该有的包名。
 *
 * 键的形状是 `<包名>@<版本>`（包名自带 scope 时是 `@scope/name@1.2.3`），所以按**最后一个**
 * `@` 切; 键里还可能有 git/url 形态的版本, 以及**同行依赖后缀** `(...)`:
 *   `dsh-prompt-system@https://codeload.github.com/x/y/tar.gz/abc`
 *   `@modusensus/dsh-mneme@0.8.13(@deepseek-ai/schemastery@3.18.4)(react@18.3.1)`
 * 两种尾巴都要先剁掉 —— 少了后一刀, `lastIndexOf('@')` 会落进后缀里, 名字被切成一团垃圾,
 * 于是一个**账内**的包被误报成 extraneous (2026-10-14 实测: `@huanlin/dsh-plugin-mineru`)
 * @param {string} text - .modules.yaml 的文本。
 * @returns {string[]} 包名（去重、排序）。
 * @throws {Error} 文本不是 JSON 或没有 hoistedLocations 时（调用方负责降级成一句 note）。
 */
export function parseHoistedLocations(text) {
  const parsed = JSON.parse(text)
  const locations = parsed?.hoistedLocations
  if (typeof locations !== 'object' || locations === null) {
    throw new Error('.modules.yaml has no hoistedLocations')
  }
  const names = new Set()
  for (const key of Object.keys(locations)) {
    const withoutUrl = key.replace(/@(?:https?:\/\/|git\+|link:|file:).*$/u, '')
    const withoutPeers = withoutUrl.replace(/(?:\([^()]*\))+$/u, '')
    const at = withoutPeers.lastIndexOf('@')
    const name = at > 0 ? withoutPeers.slice(0, at) : withoutPeers
    if (name !== '') names.add(name)
  }
  return [...names].sort()
}

/**
 * 一个 peer 的解析落点判定：来自运行安装（好）还是 ② 层（坏）。
 *
 * ② 层的拷贝会遮蔽 ③ 层注入表, 也会造出第二份实例 —— 判据就是路径落在哪
 * @param {object} input - {name, resolved, profileModulesDir}。
 * @param {string} input.name - 包名。
 * @param {string|undefined} input.resolved - 解析出来的文件路径（解析失败时 undefined）。
 * @param {string} input.profileModulesDir - 本 profile 的 node_modules 绝对路径。
 * @returns {{name: string, resolved: string|undefined, where: 'host'|'profile'|'missing', ok: boolean}} 判定。
 */
export function resolvePeerPlacement({ name, resolved, profileModulesDir }) {
  if (typeof resolved !== 'string' || resolved === '') {
    return { name, resolved: undefined, where: 'missing', ok: false }
  }
  const norm = (path) => path.replace(/\\/gu, '/').replace(/\/+$/u, '').toLowerCase()
  const under = norm(profileModulesDir)
  const target = norm(resolved)
  const inside = target === under || target.startsWith(`${under}/`)
  return { name, resolved, where: inside ? 'profile' : 'host', ok: !inside }
}

/**
 * ② 层 extraneous 扫描：pnpm 不认账的包（本次事故里那 54 个就是这样被点出来的）。
 * @param {object} input - {tracked, present, keep}。
 * @param {string[]} input.tracked - `.modules.yaml` 的 hoistedLocations 包名。
 * @param {string[]} input.present - ② 层顶层 + `@scope/*` 实际存在的包名。
 * @param {string[]} [input.keep] - 明文保留清单（叶子件, 留着不算污染）。
 * @returns {{extraneous: string[], kept: string[], trackedCount: number, presentCount: number}} 结果。
 */
export function profileExtraneous({ tracked, present, keep = [] }) {
  const trackedSet = new Set(tracked)
  const keepSet = new Set(keep)
  const extraneous = []
  const kept = []
  for (const name of present) {
    if (trackedSet.has(name)) continue
    if (keepSet.has(name)) {
      kept.push(name)
      continue
    }
    extraneous.push(name)
  }
  return {
    extraneous: extraneous.sort(),
    kept: kept.sort(),
    trackedCount: trackedSet.size,
    presentCount: present.length,
  }
}

/**
 * 把探测结果汇成一份报告（纯数据, 渲染交给 formatDoctor）。
 *
 * 注入的探测:
 * - readText(path) -> Promise<string>            读文件（缺文件时抛）
 * - listPresent(dir) -> Promise<string[]>        列 ② 层顶层 + @scope/* 的包名
 * - resolvePeer(name) -> string|undefined        解析 peer 到文件路径（失败返回 undefined）
 * - gitVersion() -> Promise<{ok: boolean, text: string}>  跑一次 `git --version`
 * @param {object} input - {selfDir, profileModulesDir, readText, listPresent, resolvePeer, gitVersion, mount, keep}
 * @returns {Promise<object>} 报告。
 */
export async function runDoctor(input) {
  const {
    selfDir,
    profileModulesDir,
    readText,
    listPresent,
    resolvePeer,
    gitVersion,
    mount = {},
    p2p = undefined,
    keep = ['@deepseek-ai/dsh-terminal'],
  } = input
  const problems = []
  const warnings = []
  const notes = []

  let peerNames = []
  let depNames = []
  try {
    const manifest = JSON.parse(await readText(`${selfDir}/package.json`))
    peerNames = peersFromManifest(manifest)
    depNames = dependenciesFromManifest(manifest)
  } catch (error) {
    notes.push(`package.json 读不到或读不懂: ${String(error?.message ?? error)}`)
    problems.push('package.json unreadable')
  }

  const peers = peerNames.map(name => resolvePeerPlacement({ name, resolved: safeResolve(resolvePeer, name), profileModulesDir }))
  for (const placement of peers) {
    if (placement.where === 'profile') {
      // 不是硬错: 手机侧那个 APK 树里, 插件与它的 peer 本来就在同一个 node_modules 里 (那是**正规**
      // 形态)。真正的判据是"这一层有没有被 pnpm 账本收录", 那件事归下面的 extraneous 一栏
      warnings.push(`${placement.name} 解析落在本插件所在的那一层 (${placement.resolved}) —— 这一层若是 profile 又不在 pnpm 账本里, 就是 §1.2 那个形状; 看下面 extraneous 一栏`)
    } else if (placement.where === 'missing') {
      problems.push(`${placement.name} 解析不到 —— 本插件的 host peer 缺失, 它挂不上`)
    }
  }

  const deps = depNames.map(name => resolvePeerPlacement({ name, resolved: safeResolve(resolvePeer, name), profileModulesDir }))
  for (const placement of deps) {
    if (placement.where === 'missing') notes.push(`第三方依赖解析不到: ${placement.name}`)
  }

  let layout = '未扫'
  let extraneous = []
  let kept = []
  try {
    const tracked = parseHoistedLocations(await readText(`${profileModulesDir}/.modules.yaml`))
    const present = await listPresent(profileModulesDir)
    const scan = profileExtraneous({ tracked, present, keep })
    extraneous = scan.extraneous
    kept = scan.kept
    layout = `pnpm 账本 ${scan.trackedCount} 个 / 盘上 ${scan.presentCount} 个`
    if (extraneous.length > 0) {
      problems.push(`② 层有 ${extraneous.length} 个 pnpm 不认账的包: ${extraneous.slice(0, 8).join(', ')}${extraneous.length > 8 ? ' …' : ''}`)
    }
  } catch (error) {
    layout = `扫不了: ${String(error?.message ?? error)}`
    notes.push(`② 层扫描跳过 (${String(error?.message ?? error)})`)
  }

  let git = { ok: false, text: '未探测' }
  try {
    git = await gitVersion()
  } catch (error) {
    git = { ok: false, text: String(error?.message ?? error) }
  }
  if (!git.ok) problems.push(`git 跑不动: ${git.text}`)

  return {
    status: problems.length === 0 ? 'PASS' : 'FAIL',
    mount,
    selfDir,
    profileModulesDir,
    layout,
    peers,
    deps,
    extraneous,
    kept,
    git,
    p2p,
    problems,
    warnings,
    notes,
  }
}

/**
 * 注入的解析器可能抛（import.meta.resolve 对缺包就是抛），这里统一成 undefined。
 * @param {(name: string) => string|undefined} resolvePeer - 解析器。
 * @param {string} name - 包名。
 * @returns {string|undefined} 文件路径或 undefined。
 */
function safeResolve(resolvePeer, name) {
  try {
    const resolved = resolvePeer(name)
    return typeof resolved === 'string' && resolved !== '' ? resolved : undefined
  } catch {
    return undefined
  }
}

/**
 * 把报告渲染成一段给人看的文本（/sync doctor 与 sync_doctor 共用）。
 * @param {object} report - runDoctor 的报告。
 * @returns {string} 多行文本。
 */
export function formatDoctor(report) {
  const lines = []
  const mount = report.mount ?? {}
  lines.push(`luwi-dsh-sync doctor: ${report.status}`)
  lines.push(`  mount      : ${mount.mounted === true ? `on (runner=${mount.runner ?? '?'})` : `off${mount.disabledReason === undefined ? '' : ` — ${mount.disabledReason}`}`}`)
  if (mount.version !== undefined) lines.push(`  version    : ${mount.version}`)
  lines.push(`  self       : ${report.selfDir}`)
  lines.push(`  profile    : ${report.profileModulesDir}`)
  lines.push(`  layout     : ${report.layout}`)
  if (report.kept !== undefined && report.kept.length > 0) lines.push(`  kept       : ${report.kept.join(', ')}`)
  lines.push(`  git        : ${report.git?.ok === true ? report.git.text : `FAIL — ${report.git?.text ?? '?'}`}`)
  // P2P (T2): 只报"有没有二进制 / 跑没跑 / 什么原因", 没有二进制不算 doctor 失败 —— p2p 缺省关
  if (report.p2p !== undefined && report.p2p !== null) {
    const p2p = report.p2p
    const state = p2p.available !== true
      ? `unavailable — ${p2p.reason ?? '?'}`
      : p2p.running === true
        ? `up — ${typeof p2p.endpointId === 'string' ? `${p2p.endpointId.slice(0, 12)}…` : '?'} via ${p2p.dataPort ?? '?'}`
        : `binary ok, not running${p2p.reason === undefined ? '' : ` — ${p2p.reason}`}`
    lines.push(`  p2p        : ${state}`)
    if (typeof p2p.binary === 'string' && p2p.binary !== '') lines.push(`               ${p2p.binary}`)
    if (typeof p2p.detail === 'string' && p2p.detail !== '') lines.push(`               ${p2p.detail}`)
  }
  for (const placement of report.peers ?? []) {
    const why = HOST_PEER_WHY[placement.name]
    const tag = placement.where === 'host' ? 'host ' : placement.where === 'profile' ? 'PROFILE!' : 'MISSING'
    lines.push(`  peer ${tag} : ${placement.name}${why === undefined ? '' : `  (${why})`}`)
    if (placement.resolved !== undefined && placement.where !== 'host') lines.push(`               ${placement.resolved}`)
  }
  for (const placement of report.deps ?? []) {
    if (placement.where === 'missing') lines.push(`  dep  MISSING: ${placement.name}`)
  }
  if ((report.extraneous ?? []).length > 0) {
    lines.push(`  extraneous : ${report.extraneous.length} 个 (② 层里 pnpm 不认账)`)
    for (const name of report.extraneous.slice(0, 20)) lines.push(`      - ${name}`)
    if (report.extraneous.length > 20) lines.push(`      … 还有 ${report.extraneous.length - 20} 个`)
  }
  for (const note of report.notes ?? []) lines.push(`  note       : ${note}`)
  for (const warning of report.warnings ?? []) lines.push(`  警告       : ${warning}`)
  if ((report.problems ?? []).length > 0) {
    lines.push('  问题:')
    for (const problem of report.problems) lines.push(`    - ${problem}`)
  }
  return lines.join('\n')
}
