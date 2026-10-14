// test/luwi-doctor.test.mjs — Luwi 加 (G1 收口) 的回归套件: 载入期安全契约 + 只读自检。
//
// 上游测试里没有这些。这里钉住五件事:
//   1. 三层合并的判据 (C8): 显式 cordis 键 > JSON 文件 > DEFAULTS, 且"显式写了 false"不会被
//      JSON 翻成 true —— 2026-10-13 手机侧就是被"schema 默认值灌满 config"坑的
//   2. tryResolveConfig / apply 在毒配置下**不抛** (C1/C2): 插件停用, 宿主照常起来
//   3. 双挂载自检 (C6): 记号在的时候第二次 mount 直接拒, 一行 warn, 不注册任何东西
//   4. doctor 的纯函数: peer 落点判定 / hoistedLocations 解析 / ② 层 extraneous 清单
//   5. runDoctor + formatDoctor: 健康 profile 是 PASS, 污染 profile 把三件事都点出来
// @module luwi-dsh-sync/test/luwi-doctor.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PLUGIN_VERSION } from '../lib/constants.mjs'
import {
  dependenciesFromManifest,
  formatDoctor,
  parseHoistedLocations,
  peersFromManifest,
  profileExtraneous,
  resolvePeerPlacement,
  runDoctor,
} from '../lib/luwi-doctor.mjs'
import * as plugin from '../index.mjs'

/** 一棵临时树; 结束时 caller 自己 rmSync。 */
function makeRoot(prefix) {
  return mkdtempSync(join(tmpdir(), `${prefix}-`))
}

/**
 * 一个够用的假 ctx: apply 只碰 logger / effect / on / inject / commands / storageDomain。
 * @returns {{ctx: object, disposers: Function[], warns: string[], errors: string[], registered: object[]}} 结果。
 */
function makeFakeContext() {
  const disposers = []
  const warns = []
  const errors = []
  const registered = []
  const table = { get: async () => undefined, put: async () => {} }
  const domain = { table: () => Promise.resolve(table), close: async () => {} }
  const ctx = {
    logger: () => ({
      warn: (message) => { warns.push(String(message)) },
      info: () => {},
      error: (message) => { errors.push(String(message)) },
    }),
    effect: (callback) => {
      const disposer = callback()
      disposers.push(typeof disposer === 'function' ? disposer : () => {})
      return disposer
    },
    on: () => {},
    inject: () => {},
    commands: { register: (definition) => { registered.push(definition); return () => {} } },
    storageDomain: { open: () => Promise.resolve(domain) },
    subprocess: undefined,
    sessions: { get: () => undefined },
  }
  return { ctx, disposers, warns, errors, registered }
}

/** 在临时 DSH_HOME 下跑一段 (JSON 层要么不存在, 要么就是自己写的那份)。 */
async function withHome(body) {
  const root = makeRoot('lw-doctor-home')
  const previous = process.env.DSH_HOME
  process.env.DSH_HOME = root
  try {
    await body(root)
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    rmSync(root, { recursive: true, force: true })
  }
}

// ---------------------------------------------------------------------------
// 1 · 三层合并 (C8)
// ---------------------------------------------------------------------------

test('explicit cordis keys beat the JSON layer, and an explicit false is not flipped', async () => {
  await withHome(async (home) => {
    const fs = await import('node:fs')
    fs.mkdirSync(join(home, 'lw'), { recursive: true })
    fs.writeFileSync(join(home, 'lw', 'session-sync.json'), JSON.stringify({
      remote: 'git://from-file/bare.git',
      autoPushOnTurnEnd: true,
      deviceName: '从 JSON 来',
    }))
    // 显式层写了 remote 与 autoPushOnTurnEnd=false: 两项都该压过 JSON
    const attempt = plugin.tryResolveConfig({
      enabled: true,
      sessionRoot: join(home, 'sessions'),
      repoDir: join(home, 'repo'),
      remote: 'D:/explicit/bare.git',
      autoPushOnTurnEnd: false,
    })
    assert.equal(attempt.ok, true)
    assert.equal(attempt.config.remote, 'D:/explicit/bare.git')
    assert.equal(attempt.config.autoPushOnTurnEnd, false)
    // 没写的键由 DEFAULTS 出
    assert.equal(attempt.config.branch, 'main')
  })
})

test('tryResolveConfig reports bad config instead of throwing', () => {
  const attempt = plugin.tryResolveConfig({ enabled: true, branch: 'a b' })
  assert.equal(attempt.ok, false)
  assert.match(String(attempt.error?.message), /branch/u)
})

// ---------------------------------------------------------------------------
// 2 · apply 绝不抛 (C1/C2)
// ---------------------------------------------------------------------------

test('apply swallows a poisoned config, disables the plugin and registers nothing', async () => {
  await withHome(async (home) => {
    const fake = makeFakeContext()
    assert.doesNotThrow(() => {
      plugin.apply(fake.ctx, {
        enabled: true,
        sessionRoot: join(home, 'sessions'),
        repoDir: join(home, 'repo'),
        branch: 'a b',
      })
    })
    assert.equal(plugin.mountState.mounted, false)
    assert.equal(fake.registered.length, 0)
    assert.equal(fake.errors.length, 1)
    assert.match(fake.errors[0], /disabled/u)
  })
})

test('enabled:false mounts nothing and logs nothing', async () => {
  await withHome(async (home) => {
    const fake = makeFakeContext()
    plugin.apply(fake.ctx, { enabled: false, sessionRoot: join(home, 'sessions'), repoDir: join(home, 'repo') })
    assert.equal(fake.registered.length, 0)
    assert.equal(fake.errors.length, 0)
  })
})

// ---------------------------------------------------------------------------
// 3 · 双挂载自检 (C6)
// ---------------------------------------------------------------------------

test('a second mount is refused while the marker is held, without touching the live state', async () => {
  await withHome(async (home) => {
    const before = plugin.mountState.mounted
    globalThis[plugin.MOUNT_KEY] = 'luwi-dsh-sync@test'
    try {
      const fake = makeFakeContext()
      assert.doesNotThrow(() => {
        plugin.apply(fake.ctx, {
          enabled: true,
          sessionRoot: join(home, 'sessions'),
          repoDir: join(home, 'repo'),
        })
      })
      assert.equal(fake.registered.length, 0)
      assert.equal(fake.warns.length, 1)
      assert.match(fake.warns[0], /refusing a second mount/u)
      assert.equal(plugin.mountState.mounted, before)
    } finally {
      delete globalThis[plugin.MOUNT_KEY]
    }
  })
})

test('a clean mount holds the marker (skips when the runtime services are absent)', async () => {
  await withHome(async (home) => {
    delete globalThis[plugin.MOUNT_KEY]
    const fake = makeFakeContext()
    try {
      plugin.apply(fake.ctx, {
        enabled: true,
        sessionRoot: join(home, 'sessions'),
        repoDir: join(home, 'repo'),
        registerTools: false,
      })
      if (plugin.mountState.mounted !== true) {
        // 测试环境缺宿主服务时 apply 会自己停用: 那种情况下这条判据无从谈起, 明说一句
        assert.ok(fake.errors.length > 0, 'disabled without an error line')
        return
      }
      assert.equal(globalThis[plugin.MOUNT_KEY], `luwi-dsh-sync@${PLUGIN_VERSION}`)
    } finally {
      delete globalThis[plugin.MOUNT_KEY]
      plugin.mountState.mounted = false
      for (const dispose of fake.disposers) dispose()
    }
  })
})

// ---------------------------------------------------------------------------
// 4 · doctor 纯函数
// ---------------------------------------------------------------------------

test('manifest helpers read peers and dependencies in a stable order', () => {
  const manifest = {
    peerDependencies: { '@deepseek-ai/dsh-tools': '*', '@deepseek-ai/cordis': '>=4.0.4 <5' },
    dependencies: { zod: '^4.4.3' },
  }
  assert.deepEqual(peersFromManifest(manifest), ['@deepseek-ai/cordis', '@deepseek-ai/dsh-tools'])
  assert.deepEqual(dependenciesFromManifest(manifest), ['zod'])
  assert.deepEqual(peersFromManifest(null), [])
  assert.deepEqual(dependenciesFromManifest({}), [])
})

test('parseHoistedLocations strips scope and version, including url-shaped versions', () => {
  const text = JSON.stringify({
    hoistedLocations: {
      'zod@4.6.1': ['node_modules/zod'],
      '@deepseek-ai/schemastery@3.18.4': ['node_modules/@deepseek-ai/schemastery'],
      '@deepseek-ai/cosmokit@1.8.5': ['node_modules/@deepseek-ai/cosmokit'],
      'dsh-prompt-system@https://codeload.github.com/x/y/tar.gz/abc': ['node_modules/dsh-prompt-system'],
    },
  })
  assert.deepEqual(parseHoistedLocations(text), [
    '@deepseek-ai/cosmokit',
    '@deepseek-ai/schemastery',
    'dsh-prompt-system',
    'zod',
  ])
  assert.throws(() => parseHoistedLocations('not json'), /JSON/u)
  assert.throws(() => parseHoistedLocations('{"schemaVersion":5}'), /hoistedLocations/u)
})

test('parseHoistedLocations strips peer suffixes too (else an in-ledger package reads as extraneous)', () => {
  // 2026-10-14 实测: 缺了这一刀, lastIndexOf('@') 落进 `(react@18.3.1)` 里, 名字被切成一团垃圾,
  // 于是账内的 @huanlin/dsh-plugin-mineru 被 /sync doctor 报成 extraneous
  const text = JSON.stringify({
    hoistedLocations: {
      '@huanlin/dsh-plugin-mineru@0.4.1(react@18.3.1)': ['node_modules/@huanlin/dsh-plugin-mineru'],
      '@modusensus/dsh-mneme@0.8.13(@deepseek-ai/schemastery@3.18.4)(react@18.3.1)': ['node_modules/@modusensus/dsh-mneme'],
      'dsh-computer-use-win@https://codeload.github.com/x/y/tar.gz/abc(react@18.3.1)': ['node_modules/dsh-computer-use-win'],
    },
  })
  assert.deepEqual(parseHoistedLocations(text), [
    '@huanlin/dsh-plugin-mineru',
    '@modusensus/dsh-mneme',
    'dsh-computer-use-win',
  ])
})

test('resolvePeerPlacement separates the running installation from a profile copy', () => {
  const profileModulesDir = 'E:\\dsh-home\\profiles\\desktop\\node_modules'
  const host = resolvePeerPlacement({
    name: '@deepseek-ai/dsh-tools',
    resolved: 'E:\\Mobile-AI\\deepseek\\resources\\app.asar\\dsh\\node_modules\\@deepseek-ai\\dsh-tools\\lib\\index.js',
    profileModulesDir,
  })
  assert.equal(host.where, 'host')
  assert.equal(host.ok, true)
  const copy = resolvePeerPlacement({
    name: '@deepseek-ai/dsh-tools',
    resolved: 'E:/dsh-home/profiles/desktop/node_modules/@deepseek-ai/dsh-tools/lib/index.js',
    profileModulesDir,
  })
  assert.equal(copy.where, 'profile')
  assert.equal(copy.ok, false)
  const missing = resolvePeerPlacement({ name: 'x', resolved: undefined, profileModulesDir })
  assert.equal(missing.where, 'missing')
  assert.equal(missing.ok, false)
})

test('profileExtraneous lists what pnpm does not track, minus the keep list', () => {
  const scan = profileExtraneous({
    tracked: ['@deepseek-ai/schemastery', 'zod', 'react'],
    present: ['@deepseek-ai/schemastery', 'zod', 'react', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-terminal'],
    keep: ['@deepseek-ai/dsh-terminal'],
  })
  assert.deepEqual(scan.extraneous, ['@deepseek-ai/dsh-tools'])
  assert.deepEqual(scan.kept, ['@deepseek-ai/dsh-terminal'])
  assert.equal(scan.trackedCount, 3)
  assert.equal(scan.presentCount, 5)
})

// ---------------------------------------------------------------------------
// 5 · runDoctor + formatDoctor
// ---------------------------------------------------------------------------

/** 注入一组探测 (healthy=真; polluted=三件事都做坏; noLedger=手机侧那种"插件与 peer 同层、没有 pnpm 账本"的树)。 */
function probes({ polluted = false, noLedger = false } = {}) {
  const manifest = JSON.stringify({ peerDependencies: { '@deepseek-ai/dsh-tools': '*' }, dependencies: { zod: '^4.4.3' } })
  const modulesYaml = JSON.stringify({ hoistedLocations: { 'zod@4.6.1': ['node_modules/zod'] } })
  return {
    selfDir: 'E:/dsh-home/profiles/desktop/node_modules/luwi-dsh-sync',
    profileModulesDir: 'E:/dsh-home/profiles/desktop/node_modules',
    readText: async (path) => {
      if (path.endsWith('package.json')) return manifest
      if (path.endsWith('.modules.yaml')) {
        if (noLedger) throw new Error(`ENOENT ${path}`)
        return modulesYaml
      }
      throw new Error(`ENOENT ${path}`)
    },
    listPresent: async () => (polluted
      ? ['zod', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-terminal']
      : ['zod', '@deepseek-ai/dsh-terminal']),
    resolvePeer: (name) => {
      if (name === '@deepseek-ai/dsh-tools') {
        if (polluted || noLedger) {
          return 'E:/dsh-home/profiles/desktop/node_modules/@deepseek-ai/dsh-tools/lib/index.js'
        }
        return 'E:/Mobile-AI/deepseek/resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js'
      }
      if (name === 'zod') return 'E:/dsh-home/profiles/desktop/node_modules/zod/index.js'
      throw new Error(`cannot resolve ${name}`)
    },
    gitVersion: async () => (polluted ? { ok: false, text: 'spawn git ENOENT' } : { ok: true, text: 'git version 2.54.0' }),
    mount: { mounted: true, runner: 'subprocess', version: '0.2.24' },
  }
}

test('runDoctor passes a healthy profile', async () => {
  const report = await runDoctor(probes())
  assert.equal(report.status, 'PASS')
  assert.deepEqual(report.problems, [])
  assert.equal(report.peers[0].where, 'host')
  assert.equal(report.git.ok, true)
  assert.deepEqual(report.kept, ['@deepseek-ai/dsh-terminal'])
})

test('runDoctor fails a polluted profile and names all three faults', async () => {
  const report = await runDoctor(probes({ polluted: true }))
  assert.equal(report.status, 'FAIL')
  const joined = report.problems.join('\n')
  assert.match(joined, /pnpm 不认账/u)
  assert.match(joined, /git 跑不动/u)
  assert.match(report.warnings.join('\n'), /解析落在本插件所在的那一层/u)
  const text = formatDoctor(report)
  assert.match(text, /doctor: FAIL/u)
  assert.match(text, /peer PROFILE!/u)
  assert.match(text, /@deepseek-ai\/dsh-tools/u)
})

test('runDoctor does not fail the phone-shaped tree (plugin and peers in one node_modules, no pnpm ledger)', async () => {
  const report = await runDoctor(probes({ noLedger: true }))
  // 误报的回归: 安卓那棵树里插件与它的 peer 本来就在同一个 node_modules (正规形态), 没有
  // .modules.yaml 只说明"这一层不归 pnpm 管", 不是污染 —— 硬判据只落在 extraneous 一栏
  assert.equal(report.status, 'PASS')
  assert.deepEqual(report.problems, [])
  assert.equal(report.peers[0].where, 'profile')
  assert.match(report.warnings.join('\n'), /解析落在本插件所在的那一层/u)
  assert.match(report.notes.join('\n'), /② 层扫描跳过/u)
})
