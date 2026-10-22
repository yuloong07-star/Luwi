// test/p2p-real-sidecar.test.mjs — 对着真 sidecar 二进制跑一次起停（缺省 skip）。
//
// 缺省跳过是因为它要一个现成的二进制（native/iroh-sidecar/rebuild-win-x64.sh 的产物）与
// 真回环端口；CI / 纯单测环境不该被它拖住。开门：LW_P2P_E2E=1。
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, promises as fs } from 'node:fs'
import os from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { P2pSidecar } from '../lib/p2p-sidecar.mjs'

const pluginDir = join(dirname(fileURLToPath(import.meta.url)), '..')
const binary = join(pluginDir, 'native', 'win32-x64', 'lwiroh.exe')
const enabled = process.env.LW_P2P_E2E === '1' && process.platform === 'win32' && existsSync(binary)

test('real sidecar: ensure reports /v1/id, stop shuts it down', { skip: enabled ? false : 'set LW_P2P_E2E=1 with a built lwiroh.exe' }, async () => {
  const sidecar = new P2pSidecar({
    config: { enabled: true, startTimeoutMs: 30000 },
    pluginDir,
    platform: process.platform,
    arch: process.arch,
    execPath: process.execPath,
  })
  try {
    const status = await sidecar.ensure()
    assert.equal(status.available, true)
    assert.equal(status.running, true, `expected running, got ${JSON.stringify(status)}`)
    assert.match(status.endpointId ?? '', /^[0-9a-f]{64}$/u)
    assert.ok(status.dataPort > 0)
    assert.match(status.ticket ?? '', /^endpoint/u)
    const stopped = await sidecar.stop()
    assert.equal(stopped.running, false)
    assert.equal(sidecar.state, 'stopped')
  } finally {
    await sidecar.stop().catch(() => {})
  }
})

test('the key file keeps the endpoint id stable across restarts (and a new key makes a new id)', { skip: enabled ? false : 'set LW_P2P_E2E=1 with a built lwiroh.exe', timeout: 120000 }, async () => {
  const dir = await fs.mkdtemp(join(os.tmpdir(), 'lw-p2p-key-'))
  try {
    const make = (keyFile) => new P2pSidecar({ config: { enabled: true, startTimeoutMs: 30000 }, pluginDir, platform: process.platform, arch: process.arch, execPath: process.execPath, keyFile })
    const first = make(join(dir, 'node.key'))
    const firstStatus = await first.ensure()
    const firstId = firstStatus.endpointId
    await first.stop()
    assert.equal((await fs.stat(join(dir, 'node.key'))).size, 32)

    const second = make(join(dir, 'node.key'))
    const secondStatus = await second.ensure()
    await second.stop()
    assert.equal(secondStatus.endpointId, firstId)

    const third = make(join(dir, 'other.key'))
    const thirdStatus = await third.ensure()
    await third.stop()
    assert.notEqual(thirdStatus.endpointId, firstId)
  } finally {
    await fs.rm(dir, { recursive: true, force: true })
  }
})
