// test/luwi-state-schema.test.mjs — 插件自己的那条 state 记录: 别的键必须活着回来
//
// 钉的是 2026-10-21 那个坑 (手机自动 pull 卡了一下午的根因): storage 域在 **open 时对每条记录
// 跑一次 valueSchema.parse** (packages/storage/storage-domain 的 `open`), 而 z.object 默认把
// **没列出来的键静默删掉**。这条记录里除了那五个元数据字段, 还住着 `sessionBaseline` 与对端那
// 几个 `peer*` 账 —— 于是每启动一次 host, 基线账本就从内存里消失:
// `stored.pairId !== pairing.id` 成立 → `ensureBaselineState` 重捕获 → `purgePending` 又置真
// → 首拉前先扫一遍整个会话存储 (手机上 3669 个文件), 取回永远轮不到.
// 那不是 "pairing id 变了": 配置文件里那个 id 从 2026-10-20 22:04 起一个字没动.

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { syncStateSchema } from '../lib/domain.mjs'

/** 一份真的会被写进 state 表的记录 (与 index.mjs 的 patchPluginState 同形) */
function stateRecord() {
  return {
    deviceId: 'f660e3a2-2808-4f2f-8b88-04aa0a0984cd',
    lastPullAt: 1_792_568_000_000,
    lastPushAt: 1_792_567_000_000,
    lastPushHead: 'a'.repeat(40),
    lastError: '',
    // 下面这些都不是 "那五个字段", 但都是插件自己写的
    sessionBaseline: {
      v: 1,
      pairId: '409db706-e831-43b8-9041-1b6ac9e825f1',
      capturedAt: 1_792_562_318_259,
      windowMinutes: 60,
      entries: 2,
      baseline: { 'p/session-a/log.jsonl': 1 },
      purgePending: true,
    },
    peerProxies: [{ local: 'l', peer: 'p' }],
    peerHidden: ['abc'],
    peerEpochAt: 1_792_000_000_000,
  }
}

test('the stored record keeps the keys the plugin wrote (baseline survives a restart)', () => {
  const stored = stateRecord()
  const readBack = syncStateSchema.parse(stored)
  assert.equal(readBack.sessionBaseline.pairId, stored.sessionBaseline.pairId)
  assert.equal(readBack.sessionBaseline.purgePending, true)
  assert.deepEqual(readBack.peerHidden, ['abc'])
  assert.equal(readBack.peerEpochAt, stored.peerEpochAt)
  assert.equal(readBack.deviceId, stored.deviceId)
})

test('the listed fields are still validated', () => {
  assert.throws(() => syncStateSchema.parse({ lastPullAt: 1 }), 'a missing deviceId is still refused')
  assert.throws(
    () => syncStateSchema.parse({ deviceId: 'd', lastPushHead: 'not-a-sha' }),
    'a malformed head is still refused',
  )
  assert.throws(() => syncStateSchema.parse({ deviceId: 'd', lastError: 'x'.repeat(501) }))
})
