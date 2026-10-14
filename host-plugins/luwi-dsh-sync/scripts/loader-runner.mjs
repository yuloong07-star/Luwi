// scripts/loader-runner.mjs — real Loader composition runner (community
// five-layer model, layer 4). An independent process boots a real Context,
// mounts the vendored Loader with the Include builtin, reads the given
// cordis.yml (real storage stack + service rows + plugin row + config), then
// asserts the plugin's contributions through the authoritative registries and
// executes one real behavior. The `subprocess` capability seam has no concrete
// local provider in this repo's dependency tree, so it is provided as a mock on
// the root Context (the plugin itself is still loaded by the Loader).
//
// Usage: node scripts/loader-runner.mjs <cordis.yml> tool|no-tool|invalid|inactive [reasonRegex]
// Exit 0 prints DSH_LOADER_RESULT <json>; any assertion or load failure exits
// non-zero with the reason on stderr (used by the invalid-config and
// default-export regression cases).
//
// Luwi 改 (G1 收口 C2): 这个 fork 把"配置非法响亮失败"改成了"一行 error + 停用", 于是负例的
// 判据不能再靠"fiber 失败"(那种情况已经不存在), 改成读插件自己的 `mountState`:
//   · invalid  —— 配置非法: mounted 必须是 false, 且 disabledReason 匹配给的原因正则
//   · inactive —— 模块面被 Loader 丢掉 (default 导出把 inject 一起吃了): apply 一次都没跑,
//                 所以 mounted false 而 **disabledReason 是 undefined**(与"被自己停用"分得开)

import { Context } from '@deepseek-ai/cordis'
import Include from '@deepseek-ai/cordis-plugin-include'
import Loader from '@deepseek-ai/cordis-plugin-loader'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const configArgument = process.argv[2]
const expected = process.argv[3]
const reasonPattern = process.argv[4]
if (configArgument === undefined || !['tool', 'no-tool', 'invalid', 'inactive'].includes(expected)) {
  console.error('usage: loader-runner.mjs <cordis.yml> tool|no-tool|invalid|inactive [reasonRegex]')
  process.exit(2)
}

const configPath = resolve(configArgument)
const configRequire = createRequire(resolve(import.meta.dirname, '../package.json'))

const ctx = new Context()
try {
  // Mock subprocess seam (no concrete local provider in the dependency tree);
  // /sync help never touches it, but the plugin's inject must resolve it.
  ctx.provide('subprocess', {
    resolveExecutable: async () => 'git',
    spawn: () => ({
      stdout: (async function* () {})(),
      done: Promise.resolve({ exitCode: 0, signal: null }),
      collected: {
        stdout: { readFrom: () => ({ text: '' }) },
        stderr: { readFrom: () => ({ text: '' }) },
      },
    }),
  })

  ctx.baseUrl = `${pathToFileURL(dirname(configPath)).href}/`
  await ctx.plugin(Loader)
  ctx.loader.internal = /** @type {any} */ ({
    version: 'v2',
    async import(specifier) {
      if (specifier.startsWith('file:')) return import(specifier)
      if (specifier.startsWith('node:')) return import(specifier)
      const absolute = /^([a-zA-Z]:)?[\\/]/u.test(specifier)
      return import(pathToFileURL(absolute ? specifier : configRequire.resolve(specifier)).href)
    },
  })
  ctx.loader.builtins.include = Include
  await ctx.loader.create({
    name: 'cordis:include',
    config: { path: pathToFileURL(configPath).href },
  })
  await ctx.loader.await()

  // cordis-plugin-loader 1.0.4 (the version the 0.1.7 host vendors) no longer
  // propagates a failed mount to this await: `Entry._init` catches an import
  // failure, logs it and returns, and `EntryTree.await` settles its tasks with
  // `Promise.allSettled`, which never rejects. Without the sweep below a
  // negative case would still exit non-zero — but for the *wrong* reason (this
  // runner's own "/sync is missing" check), which is exactly the false-green
  // the invalid-config and default-export regressions exist to prevent.
  // `FiberState` is a `const enum`, so there is no runtime object to import:
  // mirror FAILED by value (PENDING 0, LOADING 1, ACTIVE 2, FAILED 3).
  const FIBER_STATE_FAILED = 3
  for (const runtime of ctx.registry.values()) {
    for (const fiber of runtime.fibers) {
      if (fiber.state !== FIBER_STATE_FAILED) continue
      const cause = /** @type {any} */ (fiber)._error
      throw cause instanceof Error ? cause : new Error(String(cause))
    }
  }

  // Luwi 改 (G1): 负例的判据 —— 两档都读插件自己的 mountState (见文件头的说明)。
  let negativeChecked = false
  if (expected === 'invalid' || expected === 'inactive') {
    const plugin = await import(pathToFileURL(resolve(import.meta.dirname, '../index.mjs')).href)
    const state = /** @type {any} */ (plugin.mountState ?? {})
    if (state.mounted !== false) {
      throw new Error(`Loader composition: negative case mounted anyway (mountState.mounted=${String(state.mounted)})`)
    }
    if (expected === 'invalid') {
      if (typeof state.disabledReason !== 'string' || state.disabledReason === '') {
        throw new Error('Loader composition: invalid config was not disabled with a reason')
      }
      if (reasonPattern === undefined || !new RegExp(reasonPattern, 'u').test(state.disabledReason)) {
        throw new Error(`Loader composition: invalid config disabled for the wrong reason: ${JSON.stringify(state.disabledReason)}`)
      }
    } else if (state.disabledReason !== undefined) {
      // 走到 apply 才会写 disabledReason —— 有它就说明这一行是"自己停用", 不是"被 Loader 丢掉"
      throw new Error(`Loader composition: the row reached apply instead of being discarded: ${JSON.stringify(state.disabledReason)}`)
    }
    negativeChecked = true
    process.stdout.write(`DSH_LOADER_RESULT ${JSON.stringify({ negative: expected, reason: state.disabledReason ?? null })}\n`)
  }

  // Authoritative registries carry the plugin's contributions.
  if (!negativeChecked) {
    const agent = /** @type {any} */ ({
      id: 'agent-1',
      options: { provider: 'deepseek', model: 'demo-model' },
      session: { id: 's1', append() {} },
      inbox: {},
      status: 'idle',
      ctx,
      cancel: () => undefined,
      whenIdle: async () => undefined,
      runMaintenance: async (task) => task(new AbortController().signal),
      send: () => undefined,
      followup: () => undefined,
      steer: () => undefined,
      inject: () => undefined,
    })
    const commands = ctx.commands.list(agent).map((entry) => entry.name)
    if (!commands.includes('sync')) {
      throw new Error('Loader composition: /sync command is missing from the commands registry')
    }

    const toolNames = ctx.tools.schemas().map((schema) => schema.name)
    const toolsPresent = ['sync_status', 'sync_pull', 'sync_push'].every((name) => toolNames.includes(name))
    if (expected === 'tool' && !toolsPresent) {
      throw new Error('Loader composition: sync_* tools are missing (expected registered)')
    }
    if (expected === 'no-tool' && toolsPresent) {
      throw new Error('Loader composition: sync_* tools are registered (registerTools: false was not applied)')
    }

    // Real behavior: /sync help through the real commands service (no git, no confirmation).
    const execution = await ctx.commands.execute(agent, '/sync help', [], new AbortController().signal)
    const text = execution?.result?.text ?? ''
    if (!text.includes('sync: usage')) {
      throw new Error(`Loader composition: /sync help returned ${JSON.stringify(execution?.result)}`)
    }

    const summary = {
      commands,
      syncTools: toolsPresent,
      helpText: text.split('\n')[0],
    }
    process.stdout.write(`DSH_LOADER_RESULT ${JSON.stringify(summary)}\n`)
  }
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error))
  process.exit(1)
} finally {
  await ctx.fiber.dispose()
}
