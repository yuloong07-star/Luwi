/**
 * 两份 strings 的键必须一一对上 (2.7.9 第 4 条顺手补的那一条判据)
 *
 * 项目规矩是「en 与 zh 的键与顺序都保持一致」, 而这条规矩此前只写在 AGENTS.md 里 —— 删掉一个键却忘了
 * 另一份时不会报错 (缺的那份走的是 values 的英文回退, 中文界面上就冒出一句英文), 所以这里用一个不需要
 * 设备的小脚本把它钉住:
 *
 *   1. 两份的键集合相等
 *   2. 顺序也一致 (改名的键容易留在原位, 新加的键常被塞到别处)
 *   3. 各自都没有重复键 (重复时后一条静默覆盖前一条)
 *
 * 跑法: `node tools/check-strings-keys.mjs`
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

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

/** 文件里 `<string name="…">` 的出现顺序 (只认 string, 不认 string-array / plurals) */
function keysOf(xml) {
  return [...xml.matchAll(/<string\s+name="([^"]+)"/gu)].map((one) => one[1])
}

const en = keysOf(await read('app/src/main/res/values/strings.xml'))
const zh = keysOf(await read('app/src/main/res/values-zh/strings.xml'))

const duplicates = (keys) => keys.filter((key, index) => keys.indexOf(key) !== index)
const missingInZh = en.filter((key) => !zh.includes(key))
const missingInEn = zh.filter((key) => !en.includes(key))

check('两份的键集合相等', [missingInZh, missingInEn], [[], []])
check('两份的键顺序一致', en, zh)
check('两份都没有重复键', [duplicates(en), duplicates(zh)], [[], []])
check('键的数量一样', [en.length, zh.length], [en.length, en.length])
check('两份都非空 (读错文件时不要假通过)', [en.length > 100, zh.length > 100], [true, true])

console.log(`\n${checks - failures} / ${checks} 通过`)
if (failures > 0) process.exit(1)
