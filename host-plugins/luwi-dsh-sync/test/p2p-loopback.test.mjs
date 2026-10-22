// test/p2p-loopback.test.mjs — 真环回 e2e：两个 sidecar + 两个临时 repo + 真数据面（缺省 skip）。
//
// 判据（§12 J1/J2 的一部分）：A 名下的一份 live 文件与一本 conv 账，经 QUIC 数据面到 B 之后
// 逐字节一致；B 的那份也反向到 A。跑它要现成的 native/win32-x64/lwiroh.exe：`LW_P2P_E2E=1`。
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, promises as fs } from 'node:fs'
import os from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { P2pSidecar } from '../lib/p2p-sidecar.mjs'
import { P2pPeerSession } from '../lib/p2p-peer.mjs'
import { readDeletedPaths } from '../lib/p2p-apply.mjs'
import { appendTranscriptEntries, verifyTranscript } from '../lib/luwi-transcript.mjs'

const pluginDir = join(dirname(fileURLToPath(import.meta.url)), '..')
const binary = join(pluginDir, 'native', 'win32-x64', 'lwiroh.exe')
const enabled = process.env.LW_P2P_E2E === '1' && process.platform === 'win32' && existsSync(binary)

async function tempRepo(name) {
  return fs.mkdtemp(join(os.tmpdir(), `lw-p2p-loopback-${name}-`))
}

async function write(repo, rel, text) {
  const file = join(repo, ...rel.split('/'))
  await fs.mkdir(join(file, '..'), { recursive: true })
  await fs.writeFile(file, text)
}

test('loopback: two sidecars move a live file and a conv set both ways', { skip: enabled ? false : 'set LW_P2P_E2E=1 with a built lwiroh.exe', timeout: 120000 }, async () => {
  const repoA = await tempRepo('a')
  const repoB = await tempRepo('b')
  const a = new P2pSidecar({ config: { enabled: true, startTimeoutMs: 30000, inbound: true }, pluginDir })
  const b = new P2pSidecar({ config: { enabled: true, startTimeoutMs: 30000, inbound: true }, pluginDir })
  try {
    const [statusA, statusB] = await Promise.all([a.ensure(), b.ensure()])
    assert.equal(statusA.running, true, JSON.stringify(statusA))
    assert.equal(statusB.running, true, JSON.stringify(statusB))
    assert.match(statusA.endpointId ?? '', /^[0-9a-f]{64}$/u)
    assert.match(statusB.endpointId ?? '', /^[0-9a-f]{64}$/u)

    await write(repoA, `live/${statusA.endpointId}/s1.json`, '{"from":"a"}')
    await appendTranscriptEntries(join(repoA, 'conv', statusA.endpointId, 's1'), [
      { seq: 1, role: 'user', text: 'hello over loopback' },
    ], { deviceId: statusA.endpointId, sessionId: 's1' })
    await write(repoB, `live/${statusB.endpointId}/s2.json`, '{"from":"b"}')

    const outbound = await a.openFrameStream({ to: statusB.ticket, timeoutMs: 30000 })
    assert.equal(outbound.path === 'ip' || outbound.path === 'relay', true)
    const inbound = await b.acceptInbound({ timeoutMs: 30000 })
    assert.equal(inbound.peer, statusA.endpointId)

    const runSession = async (aStream, bStream) => {
      const left = new P2pPeerSession({ stream: aStream, repoDir: repoA, myDeviceId: statusA.endpointId, peerDeviceId: statusB.endpointId })
      const right = new P2pPeerSession({ stream: bStream, repoDir: repoB, myDeviceId: statusB.endpointId, peerDeviceId: statusA.endpointId })
      const [leftResult, rightResult] = await Promise.all([left.run(), right.run()])
      assert.equal(leftResult.ok, true, JSON.stringify(leftResult))
      assert.equal(rightResult.ok, true, JSON.stringify(rightResult))
    }

    await runSession(outbound.stream, inbound.stream)
    outbound.stream.close()
    inbound.stream.close()

    assert.equal(await fs.readFile(join(repoB, 'live', statusA.endpointId, 's1.json'), 'utf8'), '{"from":"a"}')
    assert.equal(await fs.readFile(join(repoA, 'live', statusB.endpointId, 's2.json'), 'utf8'), '{"from":"b"}')
    assert.equal((await verifyTranscript(join(repoB, 'conv', statusA.endpointId, 's1'))).ok, true)

    // 第二趟: A 删掉自己那份 live 文件并追加墓碑, B 应当把它挪进 trash (J14)
    await fs.rm(join(repoA, 'live', statusA.endpointId, 's1.json'))
    await write(repoA, `tombstones/${statusA.endpointId}.json`, `${JSON.stringify({ path: `live/${statusA.endpointId}/s1.json`, at: 1 })}\n`)
    const outbound2 = await a.openFrameStream({ to: statusB.ticket, timeoutMs: 30000 })
    const inbound2 = await b.acceptInbound({ timeoutMs: 30000 })
    await runSession(outbound2.stream, inbound2.stream)
    outbound2.stream.close()
    inbound2.stream.close()
    assert.equal(await fs.stat(join(repoB, 'live', statusA.endpointId, 's1.json')).catch(() => null), null)
    assert.deepEqual(await readDeletedPaths(repoB), [`live/${statusA.endpointId}/s1.json`])
  } finally {
    await a.stop().catch(() => {})
    await b.stop().catch(() => {})
    await fs.rm(repoA, { recursive: true, force: true })
    await fs.rm(repoB, { recursive: true, force: true })
  }
})
