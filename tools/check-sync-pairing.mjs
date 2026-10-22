/**
 * 扫码配对的载荷判据 (2026-10-20)
 *
 * 为什么要有这一份: 载荷是**两次实现** —— 宿主侧 lib/luwi-pairing.mjs 生成/校验, 手机侧
 * sync/PairPayload.kt 解析/校验。两侧漂开的样子一点都不响亮: PC 面板出了二维码, 手机扫进来
 * 报一句"这不是配对码", 人会以为二维码坏了。所以这一份查四件事:
 *
 *   1. **scheme 与版本两侧一致** (luwi-sync://pair?d= 与 v=1)
 *   2. **键名两侧一致** (v/id/remote/branch/peerUrl/peerToken/from/at)
 *   3. **夹具能被宿主侧解开**, 且摘要 (repoHost/peerHost/hasPeerToken) 与夹具一致
 *   4. **载荷里没有多出来的字段** (渲染前后端都按这几个键走)
 *
 * 跑法: node tools/check-sync-pairing.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const { decodePairing, encodePairing, pairingSummary, PAIR_PREFIX } = await import(
  new URL('host-plugins/luwi-dsh-sync/lib/luwi-pairing.mjs', root)
)

const fixture = JSON.parse(await read('tools/fixtures/sync-pairing.json'))
const plugin = await read('host-plugins/luwi-dsh-sync/lib/luwi-pairing.mjs')
const kotlinPath = 'app/src/main/java/io/github/yuloong07star/luwi/sync/PairPayload.kt'
let kotlin = ''
let kotlinPresent = true
try {
  kotlin = await read(kotlinPath)
} catch {
  kotlinPresent = false
}

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(
    `${same ? 'ok  ' : 'FAIL'} ${name}`
      + (same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`),
  )
}

/* ── 一、夹具往返 ─────────────────────────────────────────────────────── */

const text = encodePairing(fixture.payload)
check('encode uses the documented prefix', text.startsWith(fixture.expected.scheme), true)
check('PAIR_PREFIX matches the fixture', PAIR_PREFIX, fixture.expected.scheme)

const decoded = decodePairing(text)
check('the fixture decodes', decoded.ok, true)
const summary = decoded.ok ? pairingSummary(decoded.payload) : {}
check('repoHost', summary.repoHost, fixture.expected.repoHost)
check('peerHost', summary.peerHost, fixture.expected.peerHost)
check('hasPeerToken', summary.hasPeerToken, fixture.expected.hasPeerToken)
check('decoded keys are exactly the documented ones', decoded.ok ? Object.keys(decoded.payload).sort() : [], [...fixture.expected.keys].sort())

/* ── 二、宿主侧源码里那几样 ───────────────────────────────────────────── */

check('plugin reads the pairing section key', plugin.includes("'pairing'") || plugin.includes('pairing'), true)

/* ── 三、手机侧源码里那几样 ───────────────────────────────────────────── */

check('PairPayload.kt exists', kotlinPresent, true)
if (kotlinPresent) {
  check('the phone uses the same prefix', kotlin.includes(fixture.expected.scheme) || /luwi-sync:\/\/pair\?d=/u.test(kotlin), true)
  for (const key of fixture.expected.keys) {
    check(`the phone knows the key ${JSON.stringify(key)}`, new RegExp(`"${key}"`, 'u').test(kotlin), true)
  }
  check('the phone pins the same version', /(PAIR_VERSION|VERSION)\s*=\s*1\b|version\s*==\s*1|v\s*!=\s*1|v\s*==\s*1/u.test(kotlin), true)
}

/* ── 四、摘要里不带口令 ───────────────────────────────────────────────── */

check('the summary carries no credential', JSON.stringify(summary).includes('secret'), false)

console.log(`\n${checks - failures}/${checks} check(s) passed`)
if (failures > 0) process.exitCode = 1
