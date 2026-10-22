// test/p2p-config.test.mjs — p2p 段补默认/校验、二进制定位与 status 空壳（纯函数，不碰盘）。

import test from 'node:test'
import assert from 'node:assert/strict'
import { join } from 'node:path'
import {
  P2P_DEFAULTS,
  P2P_POWER_MODES,
  p2pBinaryCandidates,
  p2pStatusShell,
  resolveP2pConfig,
  selectP2pBinary,
} from '../lib/p2p-config.mjs'
import { SyncError } from '../lib/errors.mjs'

test('resolveP2pConfig: defaults keep p2p off and resident', () => {
  const config = resolveP2pConfig()
  assert.equal(config.enabled, false)
  assert.equal(config.power, 'resident')
  assert.equal(config.idleMs, 0)
  assert.equal(config.heartbeatMs, P2P_DEFAULTS.heartbeatMs)
  assert.equal(config.maxBlobBytes, 8 * 1024 * 1024)
  assert.equal(config.fallbackToGit, true)
  assert.equal(config.portmapper, true)
  assert.equal(config.binaryPath, '')
  assert.equal(config.inbound, true)
  assert.ok(Object.isFrozen(config))
  assert.deepEqual([...P2P_POWER_MODES], ['resident', 'charging', 'ondemand'])
})

test('resolveP2pConfig: ondemand defaults idle to 90s, explicit idle wins', () => {
  assert.equal(resolveP2pConfig({ power: 'ondemand' }).idleMs, 90000)
  assert.equal(resolveP2pConfig({ power: 'charging' }).idleMs, 0)
  assert.equal(resolveP2pConfig({ power: 'ondemand', idleMs: 5000 }).idleMs, 5000)
})

test('resolveP2pConfig: rejects bad values loudly (BAD_CONFIG)', () => {
  const cases = [
    { power: 'always' },
    { heartbeatMs: 999 },
    { heartbeatMs: 'soon' },
    { idleMs: -1 },
    { startTimeoutMs: 10 },
    { connectTimeoutMs: 10 },
    { maxBlobBytes: 1024 },
    { enabled: 'yes' },
    { fallbackToGit: 1 },
    { portmapper: 'on' },
    { binaryPath: 42 },
    { inbound: 'yes' },
  ]
  for (const value of cases) {
    assert.throws(
      () => resolveP2pConfig(value),
      (error) => error instanceof SyncError && error.code === 'BAD_CONFIG',
      `expected BAD_CONFIG for ${JSON.stringify(value)}`,
    )
  }
  assert.throws(() => resolveP2pConfig('nope'), (error) => error instanceof SyncError && error.code === 'BAD_CONFIG')
})

test('p2pBinaryCandidates: android looks next to libnode.so, desktop under native/<platform>-<arch>', () => {
  const android = p2pBinaryCandidates({ platform: 'android', arch: 'arm64', execPath: '/data/app/x/lib/arm64/libnode.so', pluginDir: '/x/plugin' })
  assert.deepEqual(android, [join('/data/app/x/lib/arm64', 'liblwiroh.so')])

  const win = p2pBinaryCandidates({ platform: 'win32', arch: 'x64', execPath: 'C:/Program Files/node.exe', pluginDir: 'C:/lw/plugin' })
  assert.equal(win[0], join('C:/lw/plugin', 'native', 'win32-x64', 'lwiroh.exe'))

  const linux = p2pBinaryCandidates({ platform: 'linux', arch: 'arm64', execPath: '/usr/bin/node', pluginDir: '/lw/plugin' })
  assert.equal(linux[0], join('/lw/plugin', 'native', 'linux-arm64', 'lwiroh'))
})

test('selectP2pBinary: first existing candidate wins, probes that throw are skipped', () => {
  const exists = (path) => {
    if (path === 'boom') throw new Error('EACCES')
    return path === 'b'
  }
  assert.equal(selectP2pBinary(['a', 'b'], exists), 'b')
  assert.equal(selectP2pBinary(['boom', 'b'], exists), 'b')
  assert.equal(selectP2pBinary(['a'], exists), null)
  assert.equal(selectP2pBinary([], exists), null)
})

test('p2pStatusShell: missing binary is a status, never a throw', () => {
  const status = p2pStatusShell({ available: false, candidates: ['a'] })
  assert.equal(status.available, false)
  assert.equal(status.running, false)
  assert.equal(status.reason, 'P2P_UNAVAILABLE')
  assert.deepEqual(status.candidates, ['a'])
  assert.equal('endpointId' in status, false)
  assert.doesNotThrow(() => JSON.stringify(status))

  const ready = p2pStatusShell({ available: true, running: true, binary: 'b', endpointId: 'id', ticket: 't', pid: 7, httpPort: 1, dataPort: 2 })
  assert.equal(ready.available, true)
  assert.equal(ready.running, true)
  assert.equal(ready.endpointId, 'id')
  assert.equal(ready.reason, undefined)
})
