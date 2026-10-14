// test/luwi-peer.test.mjs — Luwi 加 (G1-2 跨设备对端) 的配置面与纯函数。
//
// 钉住四件事:
//   1. peer 段: 令牌空 = 通道关 (失败关闭), 令牌太短 / 地址不像话 / 超时轮询越界 = 加载期响亮拒
//   2. live 段: 缺省开, 边界越界响亮拒
//   3. 令牌纪律: maskSecret 只回后四位 (短令牌连尾巴都不回), sameSecret 是定长比较
//   4. 分组名: annotateWorkspaceNames —— **对端那一侧永远带 名字（设备名）** (裸名字 = 本机的),
//      本机那一侧只在同名跨设备时也带, 已经带过本设备后缀的不会再套一层;
//      shadowDirName 把工作区名收成能当目录名的形状
// @module luwi-dsh-sync/test/luwi-peer.test

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  annotateWorkspaceNames,
  maskSecret,
  peerHeaders,
  peerUrlState,
  resolveLiveSpec,
  resolvePeerSpec,
  sameSecret,
  saysText,
  shadowDirName,
  textOfContent,
} from '../lib/luwi-peer.mjs'

const TOKEN = 'a'.repeat(24)

// ---------------------------------------------------------------------------
// 1 · peer 段
// ---------------------------------------------------------------------------

test('the peer section is off without a config, and off without a token', () => {
  assert.equal(resolvePeerSpec(undefined), null, 'no section means no channel')
  assert.equal(resolvePeerSpec({}), null, 'an empty section means no token, so no channel')
  assert.equal(resolvePeerSpec({ token: '   ' }), null, 'a blank token is no token')
  assert.equal(resolvePeerSpec({ enabled: false, token: TOKEN }), null, 'explicit off wins over a token')
  assert.equal(resolvePeerSpec({ token: TOKEN })?.enabled, true, 'a token alone turns the channel on')
})

test('a short token, a non-http url, and out-of-range numbers fail loudly', () => {
  assert.throws(() => resolvePeerSpec({ token: 'short' }), /at least 16/u)
  assert.throws(() => resolvePeerSpec({ token: 'x'.repeat(257) }), /at most 256/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, url: 'ftp://host/x' }), /http\(s\) URL/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, url: '100.64.0.1:3080' }), /http\(s\) URL/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, sendTimeoutMs: 100 }), /sendTimeoutMs/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, pollSeconds: 0 }), /pollSeconds/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, pollSeconds: 61 }), /pollSeconds/u)
  // 上限也有边界: 0 (= 不要影子) 与超大的数都不收 —— 缺省就有值, 这是 2026-10-14 事故之后的口径
  assert.throws(() => resolvePeerSpec({ token: TOKEN, maxShadows: 0 }), /maxShadows/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, maxShadows: 257 }), /maxShadows/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, maxProxies: 0 }), /maxProxies/u)
  assert.throws(() => resolvePeerSpec({ token: TOKEN, maxProxies: 513 }), /maxProxies/u)
  assert.throws(() => resolvePeerSpec([]), /must be an object/u)
})

test('a valid peer section carries defaults for listen, proxy, timeout, poll and the two caps', () => {
  const spec = resolvePeerSpec({ token: TOKEN, url: 'http://100.64.0.7:3080' })
  assert.deepEqual(spec, {
    enabled: true,
    listen: true,
    proxy: true,
    token: TOKEN,
    url: 'http://100.64.0.7:3080',
    sendTimeoutMs: 20000,
    pollSeconds: 3,
    maxShadows: 32,
    maxProxies: 64,
  })
  const narrowed = resolvePeerSpec({ token: TOKEN, listen: false, proxy: false, sendTimeoutMs: 900, pollSeconds: 10, maxShadows: 4, maxProxies: 8 })
  assert.equal(narrowed.listen, false)
  assert.equal(narrowed.proxy, false)
  assert.equal(narrowed.sendTimeoutMs, 900)
  assert.equal(narrowed.pollSeconds, 10)
  assert.equal(narrowed.maxShadows, 4)
  assert.equal(narrowed.maxProxies, 8)
})

// ---------------------------------------------------------------------------
// 2 · live 段
// ---------------------------------------------------------------------------

test('the live section defaults to on with a bounded window and rejects out-of-range numbers', () => {
  assert.deepEqual(resolveLiveSpec(undefined), { enabled: true, maxEntries: 40, maxChars: 16000, keepMinutes: 30 })
  assert.deepEqual(resolveLiveSpec({ enabled: false }), { enabled: false, maxEntries: 40, maxChars: 16000, keepMinutes: 30 })
  assert.equal(resolveLiveSpec({ maxEntries: 200 })?.maxEntries, 200)
  assert.throws(() => resolveLiveSpec({ maxEntries: 0 }), /live\.maxEntries/u)
  assert.throws(() => resolveLiveSpec({ maxEntries: 201 }), /live\.maxEntries/u)
  assert.throws(() => resolveLiveSpec({ maxChars: 199 }), /live\.maxChars/u)
  assert.throws(() => resolveLiveSpec({ keepMinutes: 0 }), /live\.keepMinutes/u)
  assert.throws(() => resolveLiveSpec('nope'), /must be an object/u)
})

// ---------------------------------------------------------------------------
// 3 · 令牌与地址纪律
// ---------------------------------------------------------------------------

test('the token only ever leaves as a masked tail, and comparison is length-first', () => {
  assert.equal(maskSecret(''), '<unset>')
  assert.equal(maskSecret(undefined), '<unset>')
  assert.equal(maskSecret('abc'), '••••', 'a short token gives away nothing, not even its tail')
  assert.equal(maskSecret('abcdefghijkl'), '••••ijkl')
  assert.equal(sameSecret(TOKEN, TOKEN), true)
  assert.equal(sameSecret(TOKEN, `${'a'.repeat(23)}b`), false)
  assert.equal(sameSecret(TOKEN, 'a'), false)
  assert.equal(sameSecret(TOKEN, ''), false)
  assert.equal(sameSecret(undefined, TOKEN), false)
  assert.equal(sameSecret(TOKEN, ''), false, 'an empty expected token never matches')
})

test('the peer url reading is sanitized, and headers carry the bearer token', () => {
  assert.deepEqual(peerUrlState(''), { state: 'unset', display: '' })
  assert.deepEqual(peerUrlState(undefined), { state: 'unset', display: '' })
  assert.deepEqual(peerUrlState('http://100.64.0.7:3080'), { state: 'set', display: 'http://100.64.0.7:3080/' })
  const withSecret = peerUrlState('https://user:hunter2@host:3080/x?token=abc')
  assert.equal(withSecret.state, 'set')
  assert.ok(!withSecret.display.includes('hunter2'), 'the url password is masked')
  assert.ok(!withSecret.display.includes('abc'), 'the token query value is masked')
  assert.deepEqual(peerHeaders(TOKEN), { authorization: `Bearer ${TOKEN}`, accept: 'application/json' })
})

// ---------------------------------------------------------------------------
// 4 · 分组名带设备名
// ---------------------------------------------------------------------------

test('对端那一侧永远带 （设备名）, 本机那一侧只在同名跨设备时带', () => {
  const names = annotateWorkspaceNames({
    localDeviceName: 'PC',
    local: [{ key: 'w1', title: '共享' }, { key: 'w2', title: '只有PC' }],
    peerDeviceName: 'Luwi 手机',
    peer: [{ key: 'p1', title: '共享' }, { key: 'p2', title: '只有手机' }],
  })
  assert.deepEqual(names.local, [
    { key: 'w1', title: '共享', label: '共享（PC）', annotated: true },
    { key: 'w2', title: '只有PC', label: '只有PC', annotated: false },
  ])
  // 影子分组是混在本机那一堆分组里出现的: 只有对端那一条**永远**带设备名,
  // 于是"裸名字 = 本机的"这条读法成立 (2026-10-14 主人报的"没有 pc/Luwi 的标注")
  assert.deepEqual(names.peer, [
    { key: 'p1', title: '共享', label: '共享（Luwi 手机）', annotated: true },
    { key: 'p2', title: '只有手机', label: '只有手机（Luwi 手机）', annotated: true },
  ])
})

test('已经带过本设备后缀的名字不会再套一层 (2026-10-14 那个回声形状)', () => {
  const once = annotateWorkspaceNames({
    localDeviceName: 'PC',
    local: [],
    peerDeviceName: 'Luwi 手机',
    peer: [{ key: 'p1', title: 'apk' }],
  })
  assert.equal(once.peer[0].label, 'apk（Luwi 手机）')
  const twice = annotateWorkspaceNames({
    localDeviceName: 'PC',
    local: [],
    peerDeviceName: 'Luwi 手机',
    peer: [{ key: 'p1', title: once.peer[0].label }],
  })
  assert.equal(twice.peer[0].label, 'apk（Luwi 手机）', '同一台设备的括号只有一层')
  // 同名判定也要看"去掉后缀之后"那一个: 本机的 apk 与对端的 apk（Luwi 手机）算同一个名字
  const collided = annotateWorkspaceNames({
    localDeviceName: 'PC',
    local: [{ key: 'l1', title: 'apk' }],
    peerDeviceName: 'Luwi 手机',
    peer: [{ key: 'p1', title: 'apk（Luwi 手机）' }],
  })
  assert.equal(collided.local[0].label, 'apk（PC）')
  assert.equal(collided.peer[0].label, 'apk（Luwi 手机）')
})

test('the same name on one device twice is not a collision, and blank names fall back to the device', () => {
  const same = annotateWorkspaceNames({
    localDeviceName: 'PC',
    local: [{ key: 'a', title: '重复' }, { key: 'b', title: '重复' }],
    peerDeviceName: 'Luwi 手机',
    peer: [],
  })
  assert.equal(same.local[0].label, '重复', 'one device holding the name twice is still one device')
  assert.equal(same.local[1].label, '重复')
  const blank = annotateWorkspaceNames({ local: [{ key: 'a', title: '  ' }], localDeviceName: '', peer: [] })
  assert.equal(blank.local[0].label, '本机', 'a nameless workspace shows its device instead of nothing')
  assert.equal(blank.peer.length, 0)
})

test('a workspace name becomes a directory name that both platforms can create', () => {
  assert.equal(shadowDirName('DSH', 'dev-1'), 'DSH')
  assert.equal(shadowDirName('a/b\\c:d*e?f"g<h>i|j', 'dev-1'), 'a-b-c-d-e-f-g-h-i-j')
  assert.equal(shadowDirName('', 'abcdef123456'), 'peer-abcdef12')
  assert.equal(shadowDirName('...hidden', 'dev-1'), 'hidden')
  assert.equal(shadowDirName('x'.repeat(80), 'dev-1').length, 64)
})

test('only text blocks become words; reasoning and tools never do', () => {
  assert.equal(textOfContent([{ type: 'text', text: 'hi' }, { type: 'reasoning', text: 'secret' }]), 'hi')
  assert.equal(textOfContent([{ type: 'reasoning', text: 'secret' }]), '')
  assert.equal(textOfContent([{ type: 'image', attachment: {} }]), '[图片]')
  assert.equal(textOfContent([{ type: 'tool-call', name: 'x' }]), '[tool-call]')
  assert.equal(textOfContent([{ type: 'text', text: 'a' }, { type: 'text', text: 'b' }]), 'a\nb')
  assert.equal(textOfContent([]), '')
  assert.equal(textOfContent(undefined), '')
})

test('saysText 只认真的说了话的那一条 (占位不算)', () => {
  assert.equal(saysText([{ type: 'text', text: 'hi' }]), true)
  assert.equal(saysText([{ type: 'reasoning', text: 'x' }, { type: 'text', text: 'hi' }]), true)
  assert.equal(saysText([{ type: 'tool-call', name: 'x' }]), false)
  assert.equal(saysText([{ type: 'image', attachment: {} }]), false)
  assert.equal(saysText([{ type: 'text', text: '' }]), false)
  assert.equal(saysText([]), false)
  assert.equal(saysText(undefined), false)
  assert.equal(saysText('nonsense'), false)
})
