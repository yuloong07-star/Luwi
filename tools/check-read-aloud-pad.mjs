// 「开口前先垫一句」的判据: 分档纯函数跑真值表, 外加"它真的被接在自动朗读那条路上"
// 抽取的是 host-plugin/index.mjs 里的源码本体, 不复制一份出来 (复制出来测的就不是上线的那份了)
// 用法: node tools/check-read-aloud-pad.mjs
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')

// ── 一、纯函数: 该垫哪一段 ────────────────────────────────────────────────
const start = source.indexOf('const PAD_MIN_CHARS')
if (start < 0) throw new Error('PAD_MIN_CHARS is not in host-plugin/index.mjs')
const fnStart = source.indexOf('function padClipFor(')
if (fnStart < 0) throw new Error('padClipFor is not in host-plugin/index.mjs')
const fnEnd = source.indexOf('\n}\n', fnStart)
if (fnEnd < 0) throw new Error('padClipFor has no end')
const padClipFor = new Function(`${source.slice(start, fnEnd + 2)}; return padClipFor`)()

const long = (n) => '好'.repeat(n)
const cases = [
  // 短句不垫: 合成本来就快, 多一声反而平白多出一段声音
  ['', null],
  ['   ', null],
  ['好了', null],
  ['好 了', null],
  [long(19), null],
  // 到了门槛才垫, 两档用 ok / thinking 这两个"接在回答前面不像拟声词"的
  [long(20), 'ok'],
  [long(30), 'ok'],
  [long(79), 'ok'],
  [long(80), 'thinking'],
  [long(200), 'thinking'],
  // 空白不计入字数: 带换行与空格的长回答按"去掉空白之后"算
  [`${long(10)}\n\n${long(10)}`, 'ok'],
  [`${long(10)}\n\n${long(9)}`, null],
  // 非中文字符也按码点算, 代理对不拆成两个
  ['a'.repeat(19), null],
  ['a'.repeat(20), 'ok'],
  ['😀'.repeat(20), 'ok'],
]

let bad = 0
for (const [input, want] of cases) {
  const got = padClipFor(input)
  const ok = got === want
  if (!ok) bad += 1
  const label = input.length > 24 ? `${JSON.stringify(input.slice(0, 12))}…(${input.length})` : JSON.stringify(input)
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label} -> ${JSON.stringify(got)}`)
  if (!ok) console.log(`     wanted ${JSON.stringify(want)}`)
}

// ── 二、接线: 自动朗读那条路真的在调它 ────────────────────────────────────
// 只留函数不接线, 就是 2026-10-24 量到的那个形态: "能垫, 但没人垫"
const hookStart = source.indexOf('function startReadAloud(')
if (hookStart < 0) throw new Error('startReadAloud is not in host-plugin/index.mjs')
const hookEnd = source.indexOf('\n}\n', hookStart)
if (hookEnd < 0) throw new Error('startReadAloud has no end')
const hook = source.slice(hookStart, hookEnd + 2)

const wiring = [
  ['自动朗读在挑垫音', hook.includes('padClipFor(spoken)')],
  ['自动朗读真的发了 op=clip', hook.includes("op: 'clip'")],
  // 先垫后念: clip 那一段必须排在 speak 之前, 否则那一句已经开口了才垫, 填不上空白
  ['垫音排在念之前', hook.indexOf("op: 'clip'") < hook.lastIndexOf("op: 'speak'")],
  ['垫音失败不挡着念', hook.includes('catch(')],
]

for (const [label, ok] of wiring) {
  if (!ok) bad += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}`)
}

const total = cases.length + wiring.length
console.log(bad === 0 ? `all ${total} checks pass` : `${bad} of ${total} checks differ`)
process.exit(bad === 0 ? 0 : 1)
