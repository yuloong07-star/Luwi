// test/luwi-pairing.test.mjs — 扫码配对的载荷 (编/解/校验)
//
// 这份与 App 的 sync/PairPayload.kt 是同一套规则的两次实现; 键名与错误分档由
// tools/check-sync-pairing.mjs 钉住

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PAIR_PREFIX,
  decodePairing,
  encodePairing,
  pairingPayloadFromConfig,
  pairingSummary,
  remoteHostOf,
  validatePairingPayload,
} from '../lib/luwi-pairing.mjs'
import { PAIR_VERSION } from '../lib/constants.mjs'

const base = {
  v: PAIR_VERSION,
  id: '4d5c70a7-0000-4000-8000-000000000001',
  remote: 'https://user:secret@example.com/dsh/sync.git',
  branch: 'main',
  peerUrl: 'http://192.168.1.23:19387',
  peerToken: '0123456789abcdef0123456789abcdef',
  from: 'PC',
  at: 1_800_000_000_000,
}

test('round trip: encode then decode yields the same payload', () => {
  const text = encodePairing(base)
  assert.ok(text.startsWith(PAIR_PREFIX))
  const decoded = decodePairing(text)
  assert.equal(decoded.ok, true)
  assert.deepEqual(decoded.payload, { ...base, branch: 'main' })
})

test('decode accepts surrounding whitespace and no peer fields', () => {
  const text = encodePairing({ ...base, peerUrl: '', peerToken: '', branch: '' })
  const decoded = decodePairing(`  ${text}\n`)
  assert.equal(decoded.ok, true)
  assert.equal(decoded.payload.branch, 'main')
  assert.equal(decoded.payload.peerUrl, '')
  assert.equal(decoded.payload.peerToken, '')
})

test('decode rejects anything that is not a pairing code', () => {
  assert.equal(decodePairing('').ok, false)
  assert.equal(decodePairing('https://example.com/').ok, false)
  const notJson = `${PAIR_PREFIX}${Buffer.from('not json', 'utf8').toString('base64url')}`
  const parsed = decodePairing(notJson)
  assert.equal(parsed.ok, false)
  assert.match(parsed.error, /not JSON/u)
  assert.equal(decodePairing(`${PAIR_PREFIX}@@@@`).ok, false)
})

test('validation: version, id, remote, branch, peer url, token', () => {
  assert.equal(validatePairingPayload({ ...base, v: 2 }).ok, false)
  assert.equal(validatePairingPayload({ ...base, id: '' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, id: 'x' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, remote: '' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, remote: 'not a remote at all' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, branch: 'bad branch' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, peerUrl: 'ftp://x' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, peerToken: 'short' }).ok, false)
  assert.equal(validatePairingPayload({ ...base, peerToken: 'has space in the token value' }).ok, false)
  assert.equal(validatePairingPayload(null).ok, false)
  assert.equal(validatePairingPayload([]).ok, false)
})

test('validation accepts the shapes real remotes use', () => {
  for (const remote of [
    'https://github.com/you/repo.git',
    'git@github.com:you/repo.git',
    'ssh://git@host/you/repo.git',
    'git://127.0.0.1:9418/bare-sessions.git',
    'D:/apk/.lwtmp/g1/relay/bare-sessions.git',
  ]) {
    const result = validatePairingPayload({ ...base, remote })
    assert.equal(result.ok, true, remote)
  }
})

test('an over-long payload is refused with a readable reason', () => {
  const huge = { ...base, remote: `https://example.com/${'x'.repeat(2000)}.git` }
  const result = validatePairingPayload(huge)
  // 地址本身还是"像地址"的, 但它会让二维码超出上限 —— encode 那一侧拒
  assert.equal(result.ok, true)
  assert.throws(() => encodePairing(huge), /over the \d+ byte limit/u)
})

test('pairingPayloadFromConfig fills branch and timestamp', () => {
  const payload = pairingPayloadFromConfig({
    id: base.id,
    remote: base.remote,
    branch: '',
    peerUrl: base.peerUrl,
    peerToken: base.peerToken,
    from: 'PC',
    at: 1234,
  })
  assert.equal(payload.v, PAIR_VERSION)
  assert.equal(payload.branch, 'main')
  assert.equal(payload.at, 1234)
})

test('summary never carries the credential', () => {
  const summary = pairingSummary(validatePairingPayload(base).payload)
  assert.equal(summary.repoHost, 'example.com')
  assert.equal(summary.peerHost, '192.168.1.23')
  assert.equal(summary.hasPeerToken, true)
  assert.equal(JSON.stringify(summary).includes('secret'), false)
})

test('remoteHostOf handles credentials, scp-style and bare hosts', () => {
  assert.equal(remoteHostOf('https://u:p@github.com/a/b.git'), 'github.com')
  assert.equal(remoteHostOf('git@github.com:you/repo.git'), 'github.com')
  assert.equal(remoteHostOf('git://127.0.0.1:9418/x.git'), '127.0.0.1')
  assert.equal(remoteHostOf(''), '')
})
