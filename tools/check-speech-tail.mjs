/**
 * 「她还在出声吗」这条判据的判据 (2026-10-16)
 *
 * 为什么值得单独一步: 这条链上的错法**都不会报错**, 只会偶尔表现得不对 ——
 *
 *   1. 判据又回到 `AudioManager.isMusicActive()`: 那是全系统 STREAM_MUSIC 的活跃度, 不看是谁在放,
 *      于是别的应用一放音乐/视频, 球上就写「正在说」(主人 2026-10-16 报的那条), 而"窗口有多长"还与
 *      这一句真实播了多久无关
 *   2. 五处消费各读各的符号: 球上写着「正在说」而点一下不掐播报 (显示与动作两套判据), 或者半双工
 *      那道闸不认新判据 —— 她自己的回答被录回去
 *   3. 系统那条引擎拿不到"我们那一份声音"的属性: 判据会退化成"只按预算" (可以接受, 但必须**知道**
 *      —— 真机上用 `speak op=probe` 量, 见 [probe])
 *
 * 不需要设备: 全是拿真源码对着核 (与 `check-image-edit.mjs` 同一个办法)。
 *
 * 用法: node tools/check-speech-tail.mjs
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const src = (name) => `app/src/main/java/io/github/yuloong07star/luwi/${name}`
const speak = await read(src('tool/LwSpeak.kt'))
const tail = await read(src('tool/SpeechTail.kt'))
const system = await read(src('tool/SystemSpeech.kt'))
const wake = await read(src('wake/WakeWordService.kt'))
const overlay = await read(src('overlay/OverlayService.kt'))
const host = await read(src('ui/HostScreen.kt'))

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

/** 取一个 Kotlin 函数的函数体 (从签名那一行到下一个顶层 `}`), 用来断言"里面没有某样东西" */
function bodyOf(source, signature) {
  const start = source.indexOf(signature)
  if (start < 0) return ''
  const end = source.indexOf('\n    }', start)
  return end < 0 ? source.slice(start) : source.slice(start, end)
}

const talking = bodyOf(speak, 'internal fun talking(context: Context)')
check('talking 那一段能取到', talking.length > 0 && talking.includes('SpeechTail.holding'), true)
check(
  'talking 里**不再**看系统媒体流 (这条 bug 的根)',
  [talking.includes('isMusicActive'), talking.includes('SystemSpeech.playing'), talking.includes('utterancePending')],
  [false, true, true],
)
check(
  '读数的探针还在 (真机上量"我们那份属性吃不吃的"就靠它)',
  [speak.includes('"probe" -> probe(context)'), speak.includes('put("musicActive"')],
  [true, true],
)
check(
  '给系统引擎那份属性是 助手场景 + 语音内容 两半',
  [system.includes('AudioAttributes.USAGE_ASSISTANT'), system.includes('AudioAttributes.CONTENT_TYPE_SPEECH')],
  [true, true],
)
check(
  '引擎真的收到了那份属性 (setAudioAttributes 紧挨着 engine = instance)',
  /setAudioAttributes\(SystemSpeech\.attributes\(\)\)[\s\S]{0,400}engine = instance/.test(speak),
  true,
)
check(
  '预算那条只是上限: 算术在 SpeechTail 里 (能单测)',
  [
    tail.includes('fun budgetMs(text: String): Long'),
    tail.includes('TAIL_MAX_MS = 25_000L'),
    speak.includes('SpeechTail.budgetMs(text)'),
  ],
  [true, true, true],
)

/* ── 五处消费: 一个都不能各读各的符号 ─────────────────────────────────── */

check(
  '半双工那道闸: 我们的喇叭 + 那一声「嘀」',
  wake.includes('HalfDuplex.forward(LwSpeak.talking(this) || VoiceState.speaking)'),
  true,
)
check(
  '说时关麦 / 说完拉起: 同一个 talking',
  [
    wake.includes('private fun ttsPlaying(): Boolean = LwSpeak.talking(this)'),
    // 看门狗那条兜底也认它 (`!ttsPlaying()`)
    wake.includes('!ttsPlaying()'),
  ],
  [true, true],
)
check(
  '球上那三个字: 同一个 talking',
  overlay.includes('val speaking = LwSpeak.talking(this)'),
  true,
)
check(
  '点球那一下的动作: 与显示同一个判据 (原来读 speakingNow, 于是显示与动作会不一致)',
  overlay.includes('speaking = LwSpeak.talking(this) || VoiceState.speaking'),
  true,
)
check(
  '通知栏那两句 (唤醒词那条 + 浮窗那条) 都走同一条',
  [
    wake.includes('if (LwSpeak.talking(this)) return "$names · 正在说话, 先不听"'),
    overlay.includes('notice(word)'),
  ],
  [true, true],
)
check(
  '闲置账挂起那条也认它',
  /VoiceIdle\.held\([\s\S]{0,700}speaking = LwSpeak\.talking\(this\)/.test(wake),
  true,
)
check(
  '⋮ 菜单「停止朗读」那条与球上同一个判据',
  host.includes('voiceSpeaking = LwSpeak.talking(context)'),
  true,
)

/* ── 读数里那三样必须报出来 (不然真机上判不出"退化了没有") ────────────── */

check(
  'status 报 tailUntil / lastWasSystem / systemMatches',
  [
    speak.includes('put("tailUntil"'),
    speak.includes('put("lastWasSystem"'),
    speak.includes('put("systemMatches"'),
  ],
  [true, true, true],
)
check(
  'status 的 speaking 与 speakingNow 是同一个答案 (原来漏了自带那条引擎)',
  speak.includes('put("speaking", speakingNow)'),
  true,
)

console.log(failures === 0 ? `\n${checks} / ${checks} 条通过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
