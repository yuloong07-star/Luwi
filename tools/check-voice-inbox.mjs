/**
 * 投递队列那半边逻辑的实测: 谁被投出去、谁不许被投第二遍
 *
 * 这一段的真值只有三条, 而三条都不是"看起来对"就够的:
 *   1. **第一次跑不许把历史倒进会话** (游标文件不在时从当前尾部开始)
 *   2. **投递成功才前移** (失败的那句留在下游, 下次接着投, 不是静默丢掉)
 *   3. **文件被轮转 / 截断 / 半行都不许重放** (去重靠 seq, 不靠字节游标)
 *
 * 而第 2 条有个反面, 2026-10-06 在真机上撞到过: 只重试不跳过的写法会让**一行必败的句子把整条队列
 * 永久停摆** (当时是唤醒词那一句拿了个 null 会话)。所以这里还量三件: "到底投给谁"的判据
 * (`voiceTargetId`, 两种"没有"收成同一个 null)、试满就跳过的判据 (`voiceAfterFailure`)、以及一次
 * 投递的超时 (`withTimeout`)
 *
 * 跑法: node tools/check-voice-inbox.mjs
 * 它**直接取 host-plugin/index.mjs 里的那段源码**来跑 (不复制第二份), 所以测的是上线那份
 */

import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const source = await readFile(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const start = source.indexOf('/** 投递队列的位置')
// 抽到 `function voiceBlockEnd() {}` 那一行为止 (见插件里那段注释): 队列怎么读、游标怎么走、
// "当前对话"那一笔账 (批次 5)、目标会话那四个判据 —— 全在这两行之间, 所以下面测的是上线那一份
// 源码, 而不是一份复制品
const end = source.indexOf('function voiceBlockEnd() {}')
if (start < 0 || end < 0) throw new Error('the voice inbox block is not in host-plugin/index.mjs')
const body = source.slice(start, end)

const api = new Function(
  'readFile', 'mkdir', 'stat', 'writeFile', 'join', 'dirname',
  `${body}\nreturn { voiceInboxPath, voiceReadNew, voiceCursorStore, voiceInboxState,` +
    ' voiceCurrentSession, voiceSessionBump, voiceSessionReset, voiceTargetSession,' +
    ' voiceTargetId, voiceAfterFailure, withTimeout, VOICE_DELIVER_TRIES, pickInterruptTarget,' +
    ' chooseVoicePreset, describePresetFailure }',
)(readFile, mkdir, stat, writeFile, join, dirname)

const home = mkdtempSync(join(tmpdir(), 'lw-inbox-'))
process.env.DSH_HOME = home
const inbox = api.voiceInboxPath()
await mkdir(dirname(inbox), { recursive: true })

const line = (seq, text) => `${JSON.stringify({ seq, at: 1759600000000 + seq, text, source: 'voice' })}\n`

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(`${same ? 'ok  ' : 'FAIL'} ${name}${same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`)
}

// 1. 第一次跑: 文件里已经有 3 句 (装之前说的), 一句都不许投出去
await writeFile(inbox, line(1, '旧话一') + line(2, '旧话二') + line(3, '旧话三'))
check('第一次跑不倒历史', await api.voiceReadNew(inbox), [])

// 2. 新来的两句按顺序投出去
await writeFile(inbox, await readFile(inbox, 'utf8') + line(4, '新话四') + line(5, '新话五'))
const fresh = await api.voiceReadNew(inbox)
check('只投新的两句', fresh.map((item) => item.text), ['新话四', '新话五'])
check('序号是文件里的那个', fresh.map((item) => item.seq), [4, 5])

// 3. 没前移游标就是重投 (投递失败时的那条路), 前移之后就不再出现
check('没前移就是重投', (await api.voiceReadNew(inbox)).length, 2)
await api.voiceCursorStore(inbox, 4)
check('前移一条之后只剩另一条', (await api.voiceReadNew(inbox)).map((item) => item.seq), [5])
await api.voiceCursorStore(inbox, 5)
check('都投过就没有了', await api.voiceReadNew(inbox), [])

// 4. 轮转: 文件从尾部重写, 只留最后一行 —— 不许因为"文件短了"而重放
await writeFile(inbox, line(5, '新话五'))
check('轮转之后不重放', await api.voiceReadNew(inbox), [])

// 5. 末尾留着半行 (进程被杀): 跳过它, 不抛, 也不影响前面那行
await writeFile(inbox, line(6, '新话六') + '{"seq":7,"at":1759600')
check('半行被跳过, 前面那行照投', (await api.voiceReadNew(inbox)).map((item) => item.text), ['新话六'])
await api.voiceCursorStore(inbox, 6)

// 6. 半行补齐成完整的一行之后它是新的, 该投
await writeFile(inbox, line(6, '新话六') + line(7, '新话七'))
check('补齐之后投出来', (await api.voiceReadNew(inbox)).map((item) => item.text), ['新话七'])

// 7. 没有正文的行 (空串) 不当它是一句话
await writeFile(inbox, line(8, '   ') + line(9, '新话九'))
check('空正文不算一句', (await api.voiceReadNew(inbox)).map((item) => item.text), ['新话九'])

// 8. 状态报得出队列与游标
const state = await api.voiceInboxState()
check('状态里的游标', state.cursor, 6)

// 9. `wake` 记号 (2026-10-05 加的: 唤醒词命中之后的头一句要开一个新对话)
//
// 它只有三种来源, 而三种都要对: 应用那侧**只有 true 才写这个键** (见 VoiceInbox.append), 所以
// 老版本写的行根本没有它, 而两种读起来必须是同一个意思 —— 多一个真的记号, 少一个假的记号,
// 那条链就会要么开不出新对话, 要么把每一句都开成新对话
const marked = (seq, text, wake) =>
  `${JSON.stringify(wake === undefined
    ? { seq, at: 1759600000000 + seq, text, source: 'voice' }
    : { seq, at: 1759600000000 + seq, text, source: 'voice', wake })}\n`
await api.voiceCursorStore(inbox, 9)
await writeFile(inbox, marked(10, '喊完之后的第一句', true) + marked(11, '接着说的第二句'))
const two = await api.voiceReadNew(inbox)
check('记号跟着那一行出来', two.map((item) => item.wake), [true, false])
check('没有这个键的行读成 false', two[1].wake, false)
check('两句还是一句一行', two.map((item) => item.text), ['喊完之后的第一句', '接着说的第二句'])

// 9b. `to` 记号 (2026-10-06 加: 回复框点名的那一场)
//
// 它只有一种来源: 回复框在屏上时, 框里发出去的那句话带上"发出那条回复的会话"(见 VoiceInbox.append)。
// 老版本写的行没有这个键, 读出来必须是"没点名"(空串) —— 空串在 [voiceTargetSession] 里与 undefined
// 一个意思, 于是老行照旧走那两条老判据
const addressed = (seq, text, to) =>
  `${JSON.stringify({ seq, at: 1759600000000 + seq, text, source: 'keyboard', to })}\n`
await api.voiceCursorStore(inbox, 11)
await writeFile(inbox, addressed(12, '框里问的一句', 'session-9f2c') + addressed(13, '没有点名的'))
const pointed = await api.voiceReadNew(inbox)
check('点名的会话跟着那一行出来', pointed.map((item) => item.to), ['session-9f2c', ''])
check('没有这个键的行读成"没点名"', pointed[1].to, '')
check('点名那两句还是一句一行', pointed.map((item) => item.text), ['框里问的一句', '没有点名的'])

// 10. "当前对话"那一笔账 (批次 5: 每次发送完成后, 那个时间窗内那一场就是可复用的当前对话)
//
// 四条判据: 投出去之后记得住 / 记的是**落盘**的那一份 (宿主重启还认得) / 窗内那一场还在
// / 过了窗就当没有 (**2026-10-07 主人把 1 小时改成 20 分钟, 2026-10-11 又缩到 10 分钟**,
// 见 VOICE_SESSION_MS —— 这里两边取 9 / 11 分钟, 贴着那 10 分钟的两侧)
async function sessionOf(text) {
  await api.voiceSessionBump(text)
  return api.voiceCurrentSession()
}
check('投过之后记得住那一场', await sessionOf('184099533039071249'), '184099533039071249')
const sessionFile = join(dirname(inbox), 'session.json')
const stored = JSON.parse(await readFile(sessionFile, 'utf8'))
check('id 按字符串落盘 (64 位不进整数)', typeof stored.id, 'string')
check('落盘的是同一个 id', stored.id, '184099533039071249')
// 差一点到点: 9 分钟前那一刻仍然算当前对话
const stillFresh = JSON.parse(await readFile(sessionFile, 'utf8'))
await writeFile(sessionFile, `${JSON.stringify({ id: stillFresh.id, at: Date.now() - 9 * 60 * 1000 })}\n`)
api.voiceSessionReset()
check('9 分钟前那一场还算当前对话', await api.voiceCurrentSession(), '184099533039071249')
// 过点: 直接改文件里那个时刻, 再把内存那一笔账清掉重读一次 —— 那与"宿主重启之后读到一份旧账"
// 是同一条路
const hourAgo = JSON.parse(await readFile(sessionFile, 'utf8'))
await writeFile(sessionFile, `${JSON.stringify({ id: hourAgo.id, at: Date.now() - 11 * 60 * 1000 })}\n`)
api.voiceSessionReset()
check('过了 10 分钟就不算当前对话', await api.voiceCurrentSession(), null)

// 11. 目标会话: **浮标只投自己那一场, 而选场只看那个时间窗** (主人 2026-10-06: "只要是 1 小时内,
// 无论点球/喊唤醒词 都只在同一场对话"; 2026-10-07 那个数改成 20 分钟, 2026-10-11 缩到 10 分钟)
//
// 四条判据: 10 分钟内那一场浮标对话被复用 (唤醒头句也一样) / 没有当前对话就开一场新的 (落在浮标
// 工作区) / 过了 10 分钟连唤醒头句也开一场新的 / 那一场不在了就开新的。**"正在界面里用的那一场"与
// "最近动过的那个"都不许被挑中** —— 那正是这句话落进 `/sdcard/DSH` 那场会话的原因 (它一被挑中,
// "浮标的对话都在 dsh-ball 里"就不成立了)
const listed = [
  { sessionId: 'aaa', updatedAt: 300, running: false, origin: 'user' },
  { sessionId: 'bbb', updatedAt: 200, running: false, origin: 'user' },
  { sessionId: 'ccc', updatedAt: 100, running: false, origin: 'subagent' },
]
const controller = { list: async () => ({ items: listed }) }
const signal = new AbortController().signal
check(
  '没有当前对话时开一场新的 (唤醒头句也一样)',
  (await api.voiceTargetSession(controller, signal, true)).sessionId,
  null,
)
check(
  '没有当前对话时不碰"最近动过的那个"',
  (await api.voiceTargetSession(controller, signal, false)).sessionId,
  null,
)
await api.voiceSessionBump('bbb')
check(
  '10 分钟内那一场浮标对话被复用',
  (await api.voiceTargetSession(controller, signal, false)).sessionId,
  'bbb',
)
check(
  '10 分钟内唤醒词那一句也复用那一场 (不再另开)',
  (await api.voiceTargetSession(controller, signal, true)).sessionId,
  'bbb',
)
// 11b. **回复框点名的那一场 (`to`)** —— 优先级最高, 但只在它还是活着的根会话时算数
// (2026-10-06 那条例外: "有回复框时, 框里进的输入走发出回复那个窗口")
check(
  '回复框点名的那一场优先于 10 分钟那一场',
  await api.voiceTargetSession(controller, signal, false, 'aaa'),
  { sessionId: 'aaa', running: false, why: 'the reply box asked for its own conversation' },
)
check(
  '点名 + 唤醒头句也投它 (不另开)',
  (await api.voiceTargetSession(controller, signal, true, 'aaa')).sessionId,
  'aaa',
)
check(
  '点名那一场正在跑时交给调用方去 steer',
  (await api.voiceTargetSession(
    { list: async () => ({ items: [{ ...listed[0], running: true }] }) },
    signal,
    false,
    'aaa',
  )).running,
  true,
)
check(
  '点名的那一场不在了就落回 10 分钟那一场',
  await api.voiceTargetSession(controller, signal, false, 'gone'),
  { sessionId: 'bbb', running: false, why: 'the ball conversation, reused' },
)
check(
  '点名指到子代理时不算数 (落回 10 分钟那一场)',
  (await api.voiceTargetSession(controller, signal, false, 'ccc')).sessionId,
  'bbb',
)
check(
  '那一场正在跑的话交给调用方去 steer',
  (await api.voiceTargetSession(
    { list: async () => ({ items: [{ ...listed[1], running: true }] }) },
    signal,
    false,
  )).running,
  true,
)
// 过点: 把落盘那一刻改成 21 分钟前, 清掉内存那一笔账再读 (与宿主重启读到一份旧账是同一条路)
const hourUp = JSON.parse(await readFile(sessionFile, 'utf8'))
await writeFile(sessionFile, `${JSON.stringify({ id: hourUp.id, at: Date.now() - 21 * 60 * 1000 })}\n`)
api.voiceSessionReset()
check(
  '过了 10 分钟, 唤醒头句也开一场新的',
  (await api.voiceTargetSession(controller, signal, true)).sessionId,
  null,
)
check(
  '过了 10 分钟, 点名的会话仍然有效 (框在屏上就按框的)',
  (await api.voiceTargetSession(controller, signal, true, 'aaa')).sessionId,
  'aaa',
)
await api.voiceSessionBump('gone')
check(
  '那一场不在了就开一场新的 (而不是退回最近动过的)',
  await api.voiceTargetSession(controller, signal, false),
  { sessionId: null, running: false, why: 'the ball conversation is gone, so this opened one' },
)
const subOnly = { list: async () => ({ items: [listed[2]] }) }
await api.voiceSessionBump('ccc')
check(
  '子代理那一场不算浮标的对话',
  (await api.voiceTargetSession(subOnly, signal, false)).why,
  'the ball conversation is gone, so this opened one',
)

// 12. "到底投给谁"只有一处判 (2026-10-06 真机上那次"键盘输入谈不了话"的根因)
//
// 上一节那条判据说的是**目标会话挑得对不对**, 而它旁边还有一个更容易错的半步: 挑的结果有**两种
// "没有"** —— 会话列表为空时回 `null`, 而没有可复用的当前对话时回一张 `sessionId: null` 的记账卡
// (那张卡要顺带说明"为什么开新对话")。投递那一段只判 `target === null` 就会把后一种当成"有会话", 拿着
// 一个 null 去 `resolveAgent`, 那一步必然失败, 而队列是"投成功才前移游标" —— 于是**它后面每一句
// (键盘打的在内) 全卡在队列里, 屏幕上还没有任何痕迹**。所以两种写法必须由 [voiceTargetId] 收成
// 同一个 `null`, 而"该新建"的判据就是它回 null
check('列表为空那种"没有"', api.voiceTargetId(null), null)
check('记账卡上那种"没有"也是没有', api.voiceTargetId({ sessionId: null }), null)
check('有会话时给的是那个 id', api.voiceTargetId({ sessionId: '184099533039071249' }), '184099533039071249')

// 13. 投不动的那一行: 试满就跳过 (只重试不跳过的写法 = 一行必败的句子把整条队列永久停摆)
check('第一次失败还要试', api.voiceAfterFailure(1), { retry: true, skip: false })
check('差一次还要试', api.voiceAfterFailure(api.VOICE_DELIVER_TRIES - 1), { retry: true, skip: false })
check('试满就跳过', api.voiceAfterFailure(api.VOICE_DELIVER_TRIES), { retry: false, skip: true })

// 14. 超时那一层: 下游某一处 await 永不落定也要放掉 (不然队列的 running 闸就一直在那里)
const hung = api.withTimeout(new Promise(() => {}), 20)
check('永不落定的那一步会被超时打断', await hung.then(() => 'resolved', () => 'timed out'), 'timed out')
check('答上了就原样透过去', await api.withTimeout(Promise.resolve('ok'), 200), 'ok')

// 15. 双击打断打哪一场 (2026-10-08): 与球上那三个字同源 —— "哪几场在跑"里最近开始的那一场。
// 这是"双击暂停不跟随停止"那条毛病的根: 原来只打 `voiceCurrentSession` 记的浮标那一场, 于是任务跑在
// 别的会话里时双击等于什么都没停, 而字却落了
const runningOne = [{ sessionId: 's1', running: true, origin: 'user' }]
check('没有在跑的轮就没有目标', api.pickInterruptTarget(runningOne, []), null)
check('只有一场在跑: 就是它', api.pickInterruptTarget(runningOne, [{ id: 's1', startedAt: 5 }]), 's1')
check(
  '两场在跑: 取最近开始的那一场',
  api.pickInterruptTarget(
    [{ sessionId: 's1', running: true, origin: 'user' }, { sessionId: 's2', running: true, origin: 'user' }],
    [{ id: 's1', startedAt: 5 }, { id: 's2', startedAt: 9 }],
  ),
  's2',
)
check(
  '账上有而会话列表说没在跑: 跳过它 (不去 resolve 一场冷会话)',
  api.pickInterruptTarget(
    [{ sessionId: 's2', running: false, origin: 'user' }],
    [{ id: 's2', startedAt: 9 }],
  ),
  null,
)
check(
  '子代理的轮不算"正在想"',
  api.pickInterruptTarget(
    [{ sessionId: 's3', running: true, origin: 'subagent' }],
    [{ id: 's3', startedAt: 9 }],
  ),
  null,
)

// 16. 新开会话用哪个预设, 以及选中的那个没注册时怎么退 (2026-10-09)
//
// 真机上的那条: 新装的手机没有 `custom` 预设的声明, 而设备上存着"上次选的是它" —— 于是
// `sessionController.create()` 直接抛 `agent-preset/not-found`, 表现是**语音输入开不了新的会话**。
// 这一段的判据是"逐级回退 + 说实话": 能解析出来的就用, 换过的要在 `why` 里写清换了谁
function presetContext(resolve) {
  return { get: (name) => (name === 'agentPresets' ? { resolve } : undefined) }
}

/** 一个"没注册"的错: 形状与 dsh 的 RemoteError 一致 (`code` + `details.available`) */
function notRegistered(wanted, available) {
  const error = new Error(`Unknown agent preset: ${wanted}`)
  error.code = 'agent-preset/not-found'
  error.details = { agentPreset: wanted, available }
  return error
}

check(
  '默认预设解析得出来就直接用它',
  await api.chooseVoicePreset(presetContext(async (id) => {
    if (id !== undefined) throw new Error(`unexpected ${String(id)}`)
    return { id: 'custom' }
  })),
  { id: 'custom', why: 'the current default preset (custom)' },
)

check(
  '默认那个没注册: 退到 standard, 并且说明是退过来的',
  await api.chooseVoicePreset(presetContext(async (id) => {
    if (id === undefined) throw notRegistered('custom', ['custom', 'standard', 'mobile-use'])
    if (id === 'standard') return { id: 'standard' }
    throw new Error(`should not try ${id}`)
  })),
  {
    id: 'standard',
    why: 'preset "custom" is not registered (agent-preset/not-found); fell back to standard',
  },
)

check(
  'standard 也没了: 按注册表报出来的顺序再试一个',
  await api.chooseVoicePreset(presetContext(async (id) => {
    if (id === undefined || id === 'standard') throw notRegistered(String(id ?? 'custom'), ['video', 'mobile-use'])
    if (id === 'video') return { id: 'video' }
    throw new Error(`should not try ${id}`)
  })),
  {
    id: 'video',
    why: 'preset "custom" is not registered (agent-preset/not-found); fell back to video',
  },
)

check(
  '一个都解析不出来: 回 undefined 让 create 去报真正的原因',
  (await api.chooseVoicePreset(presetContext(async (id) => {
    throw notRegistered(String(id ?? 'custom'), ['custom'])
  }))).id,
  undefined,
)

check(
  '没有预设注册表那一路照旧 (id undefined)',
  (await api.chooseVoicePreset({ get: () => undefined })).id,
  undefined,
)

check(
  '那个短句不会说 [object Object]',
  api.describePresetFailure(notRegistered('custom', [])),
  'preset "custom" is not registered (agent-preset/not-found)',
)

rmSync(home, { recursive: true, force: true })
console.log(failures === 0 ? `\n投递队列的 ${checks} 条判据全过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
