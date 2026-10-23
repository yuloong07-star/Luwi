// test/luwi-license.test.mjs — 权益凭证的本地验签与状态机。
//
// 覆盖:
// - 五档状态 (unset / active / grace / expired / invalid) 与「已登录但没订阅」那一档
// - 7 天宽限的两个边界 (差一秒还在宽限, 过一秒就到期)
// - 时钟回拨防御 (issuedAt 在未来 10 分钟以上 → invalid)
// - 篡改 payload / 换公钥 都验不过
// - 配对码与 P2P 两道判据各自的边界 (过期仍可生成配对码)
// - 落地文件读写 (读不到回 null)
// @module luwi-dsh-sync/test/luwi-license.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync, sign } from 'node:crypto'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  DEFAULT_PUBLIC_KEY,
  GRACE_MS,
  accountBaseOf,
  claimsOf,
  clearLicenseFile,
  decodeEntitlement,
  describeStored,
  licensePathOf,
  loginRequiredForPairing,
  loginUrlOf,
  newLoginState,
  planAllowsP2p,
  readLicenseFile,
  statusOf,
  storedFromPoll,
  verifyEntitlement,
  writeLicenseFile,
} from '../lib/luwi-license.mjs'

const pair = generateKeyPairSync('ec', { namedCurve: 'P-256' })
const publicKeyB64 = pair.publicKey.export({ format: 'der', type: 'spki' }).toString('base64')

function b64u(buffer) {
  return Buffer.from(buffer).toString('base64url')
}

/** 用测试私钥签一份 claims (与官网 signEntitlement 同一形状: payload 原样文本 + r||s 签名) */
function mint(claims) {
  const payload = {
    v: 1,
    account: claims.account ?? 7,
    login: claims.login ?? 'octo',
    plan: claims.plan ?? 'sync',
    expiresAt: claims.expiresAt,
    issuedAt: claims.issuedAt,
  }
  const text = JSON.stringify(payload)
  const signature = sign('sha256', Buffer.from(text, 'utf8'), {
    key: pair.privateKey,
    dsaEncoding: 'ieee-p1363',
  })
  return `${b64u(Buffer.from(text, 'utf8'))}.${b64u(signature)}`
}

function storedOf(entitlement, extra = {}) {
  return { v: 1, entitlement, token: 'tok-123', login: 'octo', email: 'octo@example.com', ...extra }
}

const NOW = 1_790_000_000_000

test('the built-in public key is a P-256 SPKI, not a placeholder', () => {
  assert.equal(Buffer.from(DEFAULT_PUBLIC_KEY, 'base64').length, 91)
  assert.match(DEFAULT_PUBLIC_KEY, /^MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/u)
})

test('unset: no file at all', () => {
  const status = statusOf({ stored: null, now: NOW })
  assert.equal(status.state, 'unset')
  assert.equal(status.hasToken, false)
  assert.equal(loginRequiredForPairing(status), true)
})

test('active: inside the paid window', () => {
  const text = mint({ expiresAt: NOW + 3 * 86_400_000, issuedAt: NOW - 1000 })
  const status = statusOf({ stored: storedOf(text), now: NOW, publicKey: publicKeyB64 })
  assert.equal(status.state, 'active')
  assert.equal(status.login, 'octo')
  assert.equal(status.accountId, 7)
  assert.equal(status.graceUntil, NOW + 3 * 86_400_000 + GRACE_MS)
  assert.equal(planAllowsP2p(status), true)
  assert.equal(loginRequiredForPairing(status), false)
})

test('grace: expired but inside the 7-day cushion', () => {
  const text = mint({ expiresAt: NOW - 1000, issuedAt: NOW - 86_400_000 })
  const status = statusOf({ stored: storedOf(text), now: NOW, publicKey: publicKeyB64 })
  assert.equal(status.state, 'grace')
  assert.equal(planAllowsP2p(status), true)
  assert.equal(loginRequiredForPairing(status), false)
})

test('grace boundary: one second before is grace, one second after is expired', () => {
  const expiresAt = NOW - 1000
  const text = mint({ expiresAt, issuedAt: NOW - 86_400_000 })
  const last = statusOf({ stored: storedOf(text), now: expiresAt + GRACE_MS - 1, publicKey: publicKeyB64 })
  const gone = statusOf({ stored: storedOf(text), now: expiresAt + GRACE_MS, publicKey: publicKeyB64 })
  assert.equal(last.state, 'grace')
  assert.equal(gone.state, 'expired')
  assert.equal(planAllowsP2p(gone), false)
  // 过期的人仍然能生成配对码去续费 —— 这是有意留的入口
  assert.equal(loginRequiredForPairing(gone), false)
})

test('expired without a subscription (expiresAt === issuedAt)', () => {
  const text = mint({ expiresAt: NOW, issuedAt: NOW })
  const status = statusOf({ stored: storedOf(text), now: NOW, publicKey: publicKeyB64 })
  assert.equal(status.state, 'expired')
  assert.equal(status.reason, 'no active subscription')
  assert.equal(planAllowsP2p(status), false)
})

test('invalid: a single flipped character does not verify', () => {
  const text = mint({ expiresAt: NOW + 1000, issuedAt: NOW })
  const head = text[0] === 'e' ? 'f' : 'e'
  const broken = head + text.slice(1)
  const status = statusOf({ stored: storedOf(broken), now: NOW, publicKey: publicKeyB64 })
  assert.equal(status.state, 'invalid')
  assert.equal(loginRequiredForPairing(status), true)
  assert.equal(planAllowsP2p(status), false)
})

test('invalid: another key pair does not verify', () => {
  const text = mint({ expiresAt: NOW + 1000, issuedAt: NOW })
  assert.equal(verifyEntitlement(text, DEFAULT_PUBLIC_KEY), null)
  assert.ok(verifyEntitlement(text, publicKeyB64))
})

test('invalid: issued in the future by more than the clock skew', () => {
  const text = mint({ expiresAt: NOW + 30 * 86_400_000, issuedAt: NOW + 11 * 60_000 })
  const status = statusOf({ stored: storedOf(text), now: NOW, publicKey: publicKeyB64 })
  assert.equal(status.state, 'invalid')
  assert.match(String(status.reason), /future/u)
})

test('invalid: a file with no entitlement in it', () => {
  const status = statusOf({ stored: { v: 1, token: 'x' }, now: NOW })
  assert.equal(status.state, 'invalid')
})

test('decodeEntitlement rejects two-part strings of the wrong shape', () => {
  assert.equal(decodeEntitlement(''), null)
  assert.equal(decodeEntitlement('no-dot-here'), null)
  assert.equal(decodeEntitlement('a.'), null)
  assert.equal(decodeEntitlement(`${b64u(Buffer.from('{"v":1}'))}.${b64u(Buffer.alloc(10))}`), null)
  const text = mint({ expiresAt: NOW + 1000, issuedAt: NOW })
  assert.ok(decodeEntitlement(text))
})

test('claimsOf enforces the same field rules as the server', () => {
  assert.equal(claimsOf(null), null)
  assert.equal(claimsOf({ v: 2, account: 1, login: 'a', plan: 'sync', expiresAt: 1, issuedAt: 1 }), null)
  assert.equal(claimsOf({ v: 1, account: 0, login: 'a', plan: 'sync', expiresAt: 1, issuedAt: 1 }), null)
  assert.equal(claimsOf({ v: 1, account: 1, login: 'bad login', plan: 'sync', expiresAt: 1, issuedAt: 1 }), null)
  assert.ok(claimsOf({ v: 1, account: 1, login: 'ok', plan: 'sync', expiresAt: 10, issuedAt: 5 }))
})

test('describeStored only reads the shape, never the signature', () => {
  assert.equal(describeStored(null), null)
  assert.equal(describeStored({}), null)
  const described = describeStored({ entitlement: 'a.b', token: 't', login: 'l', email: 'e', base: 'https://x' })
  assert.equal(described.entitlement, 'a.b')
  assert.equal(described.token, 't')
})

test('license file round-trips and clears', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'luwi-license-'))
  try {
    const file = licensePathOf(dir)
    assert.equal(await readLicenseFile(file), null)
    await writeLicenseFile(file, storedOf('a.b'))
    const read = await readLicenseFile(file)
    assert.equal(read.entitlement, 'a.b')
    await writeFile(file, '{broken json')
    const broken = await readLicenseFile(file)
    assert.equal(broken.broken, true)
    await clearLicenseFile(file)
    assert.equal(await readLicenseFile(file), null)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('account base respects the override and strips trailing slashes', () => {
  assert.equal(accountBaseOf({}), 'https://luwi.dpdns.org')
  assert.equal(accountBaseOf({ LUWI_SYNC_ACCOUNT_BASE: 'http://127.0.0.1:8788/' }), 'http://127.0.0.1:8788')
})

test('login url carries the one-time state', () => {
  const state = newLoginState()
  assert.match(state, /^[0-9a-f]{48}$/u)
  const url = loginUrlOf('https://luwi.dpdns.org', state)
  assert.equal(new URL(url).searchParams.get('ls'), state)
  assert.equal(new URL(url).pathname, '/login')
})

test('storedFromPoll refuses an answer without both entitlement and token', () => {
  assert.equal(storedFromPoll(null, { base: 'x' }), null)
  assert.equal(storedFromPoll({ entitlement: 'a.b' }, { base: 'x' }), null)
  assert.equal(storedFromPoll({ token: 't' }, { base: 'x' }), null)
  const stored = storedFromPoll(
    { account: { login: 'octo', email: 'a@b.c' }, entitlement: 'a.b', token: 't', expiresAt: 5, paidUntil: null },
    { base: 'https://luwi.dpdns.org', now: NOW },
  )
  assert.equal(stored.login, 'octo')
  assert.equal(stored.expiresAt, 5)
  assert.equal(stored.fetchedAt, NOW)
})
