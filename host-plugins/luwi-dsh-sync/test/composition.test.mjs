// test/composition.test.mjs — 真实 Loader composition 套件（社区五层模型 4–5 层）：
// 独立进程挂载 Loader + Include builtin，读 cordis.yml（真实 storage 栈 + service 行 +
// 插件行 + config），证明模块解包、inject 解析、config 应用与注册表贡献。同时携带
// 两类负例：非法 config 按预期原因响亮失败（U4），default 导出以 missing-inject 失败（C2）。
// @module dsh-session-sync/test/composition.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const runner = join(repositoryRoot, 'scripts', 'loader-runner.mjs')
const entry = join(repositoryRoot, 'index.mjs')

/** One cordis.yml: real storage stack + service rows, then the plugin row with config. */
function configFor(pluginRow, storageRoot, dirs, configLines = []) {
  const pluginConfig = [
    `sessionRoot: ${JSON.stringify(dirs.sessionRoot)}`,
    `repoDir: ${JSON.stringify(dirs.repoDir)}`,
    ...configLines,
  ]
  return [
    "- name: '@deepseek-ai/dsh-storage'",
    "- name: '@deepseek-ai/dsh-storage-json'",
    '  config:',
    `    root: ${JSON.stringify(storageRoot)}`,
    "- name: '@deepseek-ai/dsh-storage-domain'",
    '  config:',
    '    backend: json',
    "- name: '@deepseek-ai/dsh-session'",
    "- name: '@deepseek-ai/dsh-system-prompt'",
    "- name: '@deepseek-ai/dsh-tools'",
    "- name: '@deepseek-ai/dsh-commands'",
    `- name: ${JSON.stringify(pluginRow)}`,
    '  config:',
    ...pluginConfig.map(line => `    ${line}`),
    '',
  ].join('\n')
}

function runRunner(configPath, expected, reasonPattern) {
  const argv = [runner, configPath, expected]
  if (reasonPattern !== undefined) argv.push(reasonPattern)
  const result = spawnSync(process.execPath, argv, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: { ...process.env },
    timeout: 120_000,
  })
  if (result.error !== undefined) throw result.error
  return { status: result.status, stdout: result.stdout, stderr: result.stderr }
}

const temporaryRoot = mkdtempSync(join(tmpdir(), 'dsh-session-sync-loader-'))
const storageRoot = join(temporaryRoot, 'storage')
const dirs = { sessionRoot: join(temporaryRoot, 'sessions'), repoDir: join(temporaryRoot, 'repo') }
mkdirSync(storageRoot, { recursive: true })

test('Loader composition mounts the plugin and applies its default config', () => {
  const configPath = join(temporaryRoot, 'valid.yml')
  writeFileSync(configPath, configFor(pathToFileURL(entry).href, storageRoot, dirs))
  const evidence = runRunner(configPath, 'tool')
  assert.equal(evidence.status, 0, `stdout:\n${evidence.stdout}\nstderr:\n${evidence.stderr}`)
  const marker = evidence.stdout.match(/DSH_LOADER_RESULT (.+)$/mu)
  const summary = JSON.parse(marker[1])
  assert.ok(summary.commands.includes('sync'))
  assert.equal(summary.syncTools, true)
  assert.match(summary.helpText, /sync: usage/u)
})

test('Loader composition applies the special config value (registerTools: false)', () => {
  const configPath = join(temporaryRoot, 'no-tool.yml')
  writeFileSync(configPath, configFor(pathToFileURL(entry).href, storageRoot, dirs, [
    'registerTools: false',
  ]))
  const evidence = runRunner(configPath, 'no-tool')
  assert.equal(evidence.status, 0, `stdout:\n${evidence.stdout}\nstderr:\n${evidence.stderr}`)
  const marker = evidence.stdout.match(/DSH_LOADER_RESULT (.+)$/mu)
  const summary = JSON.parse(marker[1])
  assert.equal(summary.syncTools, false)
  assert.ok(summary.commands.includes('sync'), '/sync stays registered when only the tools are disabled')
})

test('invalid config never mounts: schema violations fail at the Loader, the rest is disabled by apply (C2)', () => {
  // 两档是两回事, 判据也两样:
  //   ① **schema 违规** (类型/联合成员不对) —— 装载器在调用 apply 之前就拒了这一行, 那行文案
  //      出现在 stderr 上 (C2 管不到这一层, 它管的是 apply 里的校验)
  //   ② **schema 过得了、语义非法** (gitBin 空串 / branch 里有空格) —— apply 里 resolveConfig
  //      抛, 被 C2 接住 → 一行 error + 停用; 原因记在插件自己的 mountState.disabledReason 上
  //      (裸 Context 没有 logger 把它印出来, 所以靠 runner 的 invalid 档读, 不靠 stderr)
  const entryUrl = pathToFileURL(entry).href
  const schemaCases = [
    { lines: ["enabled: 'yes'"], reason: /expected boolean/u },
    { lines: ["backend: 's3'"], reason: /backend/u },
    { lines: ["confirmVia: 'x'"], reason: /confirmVia/u },
  ]
  for (const entryCase of schemaCases) {
    const configPath = join(temporaryRoot, 'invalid-schema.yml')
    writeFileSync(configPath, configFor(entryUrl, storageRoot, dirs, entryCase.lines))
    const evidence = runRunner(configPath, 'tool')
    assert.notEqual(evidence.status, 0, `schema violation unexpectedly mounted: ${entryCase.lines.join(' ')}`)
    assert.match(evidence.stderr, entryCase.reason, `failed for the wrong reason:\n${evidence.stderr}`)
  }

  const applyCases = [
    { lines: ["gitBin: ''"], reason: 'gitBin' },
    { lines: ["branch: 'bad branch'"], reason: 'branch' },
  ]
  for (const entryCase of applyCases) {
    const configPath = join(temporaryRoot, 'invalid-apply.yml')
    writeFileSync(configPath, configFor(entryUrl, storageRoot, dirs, entryCase.lines))
    const evidence = runRunner(configPath, 'invalid', entryCase.reason)
    assert.equal(
      evidence.status,
      0,
      `invalid config was not disabled as expected:\n${entryCase.lines.join('\n')}\nstderr:\n${evidence.stderr}`,
    )
    assert.match(evidence.stdout, /"negative":"invalid"/u)
  }
})

test('a default export makes the Loader drop inject, and C1 catches the fallout', () => {
  const wrapper = join(temporaryRoot, 'default-export.mjs')
  const builtUrl = pathToFileURL(entry).href
  writeFileSync(wrapper, [
    `export { name, inject, Config, apply } from ${JSON.stringify(builtUrl)}`,
    `export { apply as default } from ${JSON.stringify(builtUrl)}`,
    '',
  ].join('\n'))
  const configPath = join(temporaryRoot, 'invalid-default.yml')
  writeFileSync(configPath, configFor(pathToFileURL(wrapper).href, storageRoot, dirs))
  // 有 default 导出 ⇒ 装载器丢掉整个命名空间里的 inject ⇒ apply 拿到一个没有 storageDomain 的
  // ctx ⇒ 它抛, 而 C1 那一层把它接住并停用。判据 = "没挂上 + 原因是 without inject", 由 runner
  // 的 invalid 档从 mountState 读 (上游那句诊断来自加载器旧版, 1.0.4 之后不再走 stderr)
  const evidence = runRunner(configPath, 'invalid', 'without inject')
  assert.equal(evidence.status, 0, `stdout:\n${evidence.stdout}\nstderr:\n${evidence.stderr}`)
  assert.match(evidence.stdout, /"negative":"invalid"/u)
})

test.after(() => {
  rmSync(temporaryRoot, { recursive: true, force: true })
})
