/**
 * Luwi's dsh tools
 *
 * These run in the host process, which is the app's own Node child and shares its uid, so the
 * privileged channel is one loopback connection away: the app process serves it and holds the
 * binder to the root or shell process, which is something Node cannot do for itself
 *
 * Both the endpoint and the token arrive in environment variables the app sets when it spawns the
 * host, so the same tools on a machine that is not a phone report exactly that instead of failing
 * obscurely
 *
 * Every call that changes a screen names the screen it means. Which screen the app's preview shows
 * is the user's to change at any moment, so a tool that named nothing could act on the one the user
 * had just switched to - the coordinates a caller holds are only meaningful against the screen it
 * measured them on
 */

import { connect } from 'node:net'

import { readFileSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { appendFile, mkdir, readFile, readdir, realpath, rename, rm, stat, writeFile } from 'node:fs/promises'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { basename, dirname, isAbsolute, join, resolve, sep } from 'node:path'

import { defineTool } from '@deepseek-ai/dsh-tools'

/** Set by the app when it starts the host; both absent everywhere else */
const ENDPOINT_VARIABLE = 'LW_CHANNEL_ENDPOINT'
const TOKEN_VARIABLE = 'LW_CHANNEL_TOKEN'

/** How long a swipe takes when the caller does not say, which is a deliberate drag */
const DEFAULT_SWIPE_MS = 300

/** 按名字点一个从像素里读出来的框时, 落点从框中间这一块里随机取, 见 scatterInside */
const SCATTER_INSET = 0.2

/**
 * What the three names for a hold mean, in milliseconds
 *
 * Android's own long press threshold is 500ms (`ViewConfiguration.getLongPressTimeout`), and that
 * is the whole of what the platform reads into a hold: everything at or past it is a long press and
 * everything past that is only "kept down longer", which matters to the few apps that count - a
 * voice message being recorded, a drag that starts by holding. So the names sit just above that
 * line rather than in the seconds: `short` is the shortest hold that is reliably a long press
 * (600ms, which leaves the main thread's own detector its margin), and the other two are the steps
 * above it. A hold nobody named - "8s", "250ms" - is written out instead, which is also how a
 * deliberate long one is asked for
 */
const HOLD_NAMES = { short: 600, medium: 1500, long: 3000 }

/** No hold lasts longer than this: the device's gesture queue is blocked for the whole of one */
const MAX_HOLD_MS = 10000

/** How many controls one lw_ui lists before it says the rest are off screen */
const MAX_UI_NODES = 200

/** How many queued sentences lw_voice prints: the tail is what a person is asking about */
const MAX_VOICE_LINES = 20

export const name = 'luwi-channel'

/** The tool registry has to exist before anything can be registered on it */
export const inject = ['tools', 'agents', 'sessions']

export function apply(ctx) {
  hostCtx = ctx
  for (const tool of TOOLS) ctx.tools.register(tool)

  // 两道闸 (批次 3, 需求 17 与需求 3): 视频模式里屏幕类工具一律拒; 相机与视频模式同时只许一场会话用。
  // 名单外的工具在这道闸里只多一次 Set 查找 (见 [cameraAndModeGate])
  try {
    ctx.tools.guard(cameraAndModeGate)
  } catch (error) {
    warn(ctx, `the mode and camera gate was not registered: ${error?.message ?? error}`)
  }

  // 一场的轮结束就把相机占用放掉 (批次 3): 占用是"每轮结束就放"那一条口径
  try {
    startCameraOwnerWatch(ctx)
  } catch (error) {
    warn(ctx, `the camera owner watch was not started: ${error?.message ?? error}`)
  }

  // Answer every approval request with a grant, so nothing on the screen waits for a person
  //
  // An answerer is not registered on a service: it is a listener on the `approval/request`
  // waterfall, and returning an outcome answers for every agent while `next()` would delegate.
  // `prepend` is what makes it decisive - this plugin is mounted from the last patch layer, so
  // without it the web GUI's own approval panel would claim a request first whenever a browser
  // has the GUI open, and the user would be asked to confirm something they cannot see
  //
  // This is a deliberate trade, recorded in AGENTS.md: a screen being driven at a distance has
  // nobody watching it, so a question is not a safety net, it is a stall. The approval log still
  // records that a decision was made and what it was
  ctx.on('approval/request', (_request, _next) => Promise.resolve('allowed-once'), { prepend: true })

  // 语音链投进来的话: 一直看着应用写的那份队列, 有新句子就送进会话 (批次 2 的投递)
  //
  // `sessionController` 走 ctx.get 而不是 inject: 它是 web 那一套里的服务, 而这个插件在别的
  // profile 里也装 (那些 profile 没有它)。inject 少了会让整个插件的 53 个工具跟着挂, 而这里
  // 真正想表达的只是"这个能力在这套 profile 里可能没有"
  //
  // 这一行是**故意的**: 队列在哪个文件、这条链起没起来, 是排查"说了话没进会话"的第一个问题,
  // 而它只靠日志才回答得了 (2026-10-05 就是在这里瞎猜了一轮)
  console.log(`luwi-channel: voice inbox is ${voiceInboxPath() ?? 'unavailable (no DSH_HOME)'}`)
  try {
    startVoiceInbox(ctx, (line) => voiceDeliver(ctx, line))
  } catch (error) {
    warn(ctx, `the voice inbox was not started: ${error?.message ?? error}`)
  }

  // 一轮说完就把回答念出来 (批次 2.3): 挂在 `session/event` 的 `turn/end` 上, 只有正常结束的那一轮
  // 才念 —— 理由写在 startReadAloud 上面
  try {
    startReadAloud(ctx)
  } catch (error) {
    warn(ctx, `reading replies aloud was not started: ${error?.message ?? error}`)
  }

  // 浮标上那三个字里的「正在想」(批次 4): 一轮在跑就推 thinking, 跑完推 idle —— 只有宿主知道这件事
  try {
    startBallPhase(ctx)
  } catch (error) {
    warn(ctx, `the ball phase was not started: ${error?.message ?? error}`)
  }

  // **`{{cwd}}` 这个提示词变量在 0.2.1-rc.2 里没有了** (2026-10-17): 上游把工作目录改成一条
  // required 的 context 段 (`Current working directory: …`, 见 `working-directory`), 于是任何
  // 还写着 `{{cwd}}` 的模板在组装时都抛 `unknown prompt variable "{{cwd}}"` —— 本仓的
  // prompt.md、explore 预设的 persona suffix, 以及设备上用户自己写过的提示词都是这样。这里按
  // 原名字再注册一次 (它只影响引用 `{{cwd}}` 的模板, 不引用的一步都不经过), 值取会话当前目录:
  // `workingDirectory.get` 是"中途 cd 过"之后真正生效的那个, 那个服务不在时退回 header 的 cwd
  try {
    ctx.inject(['systemPrompt'], (scope) => {
      scope.systemPrompt.variable('cwd', (context) => {
        const agent = context.agent
        const directory = scope.get('workingDirectory')
        return (agent === undefined ? undefined : directory?.get(agent.session))
          ?? agent?.session?.header?.cwd
          ?? process.cwd()
      })
    })
  } catch (error) {
    warn(ctx, `the cwd prompt variable was not registered: ${error?.message ?? error}`)
  }

  // 「看余额」那一档 (2026-10-16): 账号缝里那个数只有宿主拿得到, 落成文件应用才看得见 ——
  // **只有应用点「看余额」写出来的那个请求记号能触发它** (见 startBalance), 宿主自己不按节拍刷
  try {
    startBalance(ctx)
  } catch (error) {
    warn(ctx, `the balance feed was not started: ${error?.message ?? error}`)
  }

  // 视频模式的独立模型 (3.0.2 需求 2): turn/start 那个触发点 + 目录喂给应用的那条 (见 startModeModel)
  try {
    startModeModel(ctx)
  } catch (error) {
    warn(ctx, `the mode model correction was not started: ${error?.message ?? error}`)
  }

  // 探索那一档的预算 (2.7.8 批次 7): 数步那道闸 (只数不拒) 与到点那一拍 (5 s), 与上面两道闸并列
  try {
    ctx.tools.guard(exploreStepGuard)
  } catch (error) {
    warn(ctx, `the explore step guard was not registered: ${error?.message ?? error}`)
  }
  try {
    startExploreClock(ctx)
  } catch (error) {
    warn(ctx, `the explore budget was not started: ${error?.message ?? error}`)
  }

  // 语音输入是 dsh 里可选的一套 (voice-input bundle 带的那个客户端录音按钮): 服务在才注册
  // 本机的转写 provider, 不在就什么都不做 —— 一个可选能力不该让 lw_* 那堆工具跟着挂
  try {
    if (typeof ctx.inject === 'function') {
      ctx.inject(['speechToText'], (speechCtx) => {
        registerVoiceInput(speechCtx)
      })
    }
  } catch (error) {
    warn(ctx, `the on-device speech provider was not registered: ${error.message}`)
  }

  // LW 插件 (批次 9): 应用那一侧是唯一做能力检查、授权与审计的地方, 这里只按它给的快照把工具登记上。
  // 每几秒拉一次快照, revision 变了才重注册 —— "装一个插件 → 模型下一轮就能看到它"这条链靠的就是它
  try {
    startPluginTools(ctx)
  } catch (error) {
    warn(ctx, `the plugin tools were not registered: ${error?.message ?? error}`)
  }
}

/**
 * 报一句警告
 *
 * **两处都写**: 控制台那一行是保证看得见的 (主机日志里带 `DshHost`), 而 cordis 的 logger 是给
 * profile 自己那一套用的。2026-10-05 踩的就是只写 logger: 投递链没起来, 而 `ctx.logger?.warn?.()`
 * 把它吞得干干净净, 日志里一个字都没有, 只能在文件系统上一点点反推
 */
function warn(ctx, message) {
  console.warn(`luwi-channel: ${message}`)
  if (typeof ctx.logger?.warn === 'function') ctx.logger.warn(message)
}

/** The display a call is about, which is the id `lw_screen` reports and nothing else */
const DISPLAY_ID = {
  type: 'integer',
  required: true,
  description:
    'The displayId of the screen to act on, exactly as lw_screen reports it. Display 0 is the '
    + "phone's own screen, the glass the person holding it is looking at; every other id is a "
    + 'virtual screen this host made. Prefer a virtual screen whenever the user has not named one: '
    + 'display 0 is the phone in somebody\'s hand, so anything aimed at it takes the phone away '
    + 'from them. Make one with lw_screen_create instead of reaching for the id that is '
    + 'already there. When a call comes back saying the screen is gone or is paused, read the '
    + 'reason it gives: the user did it from the phone, or an agent closed '
    + 'it with lw_screen_release. Either way stop, say which screen it is, and ask the user what '
    + 'they want - do not retry the id, and do not move on to another screen on your own.',
}

/**
 * 屏参数的另一版: 可以省略, 省了就是"每一块屏"
 *
 * 与 [DISPLAY_ID] 的区别是刻意的: 那一版把 displayId 定成必填, 因为**动手**必须点名是哪一块屏
 * (选中的屏是用户随时能改的); 而事件订阅只是**看**, 省略就等于全看, 那才是它的默认用法
 *
 * **声明必须在 `TOOLS` 数组之前**: `const` 不提升, 放到下面就是模块求值期抛错, 而那张表是模块级的
 * —— 整包 UNSUPPORTED_SCHEMA, 会话里一个 `lw_*` 工具都不剩 (自检会报 "Cannot access ... before
 * initialization")
 */
const WATCHED_DISPLAY = {
  type: 'integer',
  description: "Only this screen's changes; leave it out to watch every screen, which is the usual "
    + 'thing here because watching is reading rather than acting',
}

/**
 * What one acting call is for, said by the caller in its own words
 *
 * Every call that changes something on a screen carries one, the way an agent framework asks for a
 * line of explanation beside a command. It is required rather than optional on purpose: saying what
 * a step is for before taking it is the difference between a transcript a person can follow and a
 * list of coordinates, and there is no human in the approval path to ask instead
 */
const NOTE = {
  type: 'string',
  required: true,
  description: 'One short sentence, in your own words, saying what this step is doing and why',
}

/**
 * 插件上下文
 *
 * 工具定义在一个模块级的数组里, 而附件通路 (`ctx.attachments`) 只在 `apply(ctx)` 那里拿得到 ——
 * 所以 apply 的时候留一份, 供 `lw_look` 把截图帧当附件直接交给模型
 */
let hostCtx = null

/**
 * 把几张图变成工具结果里的图: 少了这一步, 模型要自己一张张 read_image (每张一次往返)
 *
 * **每一项有两种形状**: 盘上那条**路径** (相机与截屏交出来的都是这个), 或者**已经在手里的字节**
 * (`{ data, name }` —— `lw_look` 那张网格就是后一种: 它由 sharp 当场拼出来, 从来没有落过盘)。
 * 少了后一种, 那一份字节会被拿去当**路径** `readFile` (Node 认 Buffer 当路径, 报的是
 * `path must be a string ... without null bytes` 或者 ENOENT, 都不是一句类型错), 于是整批图一张都
 * 交不出去 —— 2026-10-11 那条"开着『一次取景也拼成一张网格』时视频模式识别不了"就是这一条
 *
 * **声明的类型要与字节一致**: 附件库拿声明的类型与字节比对, 对不上直接拒 (`IMAGE_TYPE_MISMATCH`),
 * 那时工具只会退成"给你路径, 自己 read_image" —— 而相机交出来的是 JPEG, 截屏那条路交出来的是 PNG,
 * 所以类型按字节认, 不写死
 *
 * @param items 每一盘是"路径字符串"或者 `{ data, name }` (name 只为了在界面上看得出是哪一张)
 */
async function attachPictures(items) {
  const attachments = hostCtx?.get?.('attachments')
  if (!attachments?.saveImage) return { images: [], note: 'no attachment store on this host' }
  const images = []
  for (const item of items) {
    const inline = item !== null && typeof item === 'object' && !Array.isArray(item)
    const data = inline ? item.data : await readFile(item)
    const name = inline ? String(item.name ?? 'image') : String(item).split('/').pop()
    images.push(await attachments.saveImage({ data, mediaType: pictureType(data, name), name }))
  }
  return { images, note: null }
}

/**
 * 这张图的字节到底是什么格式
 *
 * 只看头几个字节: 相机那条路给的是 JPEG (`FF D8`), 旧那条截屏链路给的是 PNG (`89 50 4E 47`)。认不
 * 出来就抛 —— 附件库本来也会拒, 而这里的报错能带上路径, 排起来省一步
 */
/**
 * 应用那一侧的取景规则 (设置页「视频识别」): 张数 / 间隔 / 清晰度
 *
 * 插件读不到应用的偏好文件, 而这三个数只有应用知道 —— 所以走一次桥问相机状态。**问不到就用应用那边
 * 同样的缺省值**: 一台没装浮标/相机起不来的设备不该让"取景"这条链整个报错, 它该退成"照老样子来"
 */
async function cameraLook() {
  try {
    const state = await call('camera', { op: 'status' })
    return {
      count: Number.isFinite(state?.lookCount) ? state.lookCount : 4,
      intervalMs: Number.isFinite(state?.lookIntervalMs) ? state.lookIntervalMs : 200,
      pixels: Number.isFinite(state?.lookPixels) ? state.lookPixels : 0,
      // 拼不拼网格: 应用把那个开关写成 host 目录里的一个文件 (`$DSH_HOME/lw/look-sheet`), 而
      // `lookSheet` 就是它此刻在不在 —— 关着时文件被删掉, 所以这里读到的 false 是"设置页关着"
      sheet: state?.lookSheet === true,
    }
  } catch {
    return { count: 4, intervalMs: 200, pixels: 0, sheet: false }
  }
}

/**
 * 把这一趟的几张帧拼成**一张网格** (主人 2026-10-06 要的那个开关)
 *
 * **不往应用那侧加桥方法**: 拼图这件事插件本来就做得到 (host 树里那个 `sharp` 是官方的 wasm 构建),
 * 而在 host 上拼还少了十几张图过桥的字节数
 *
 * 三条与 `lw_screenshot` 那张网格同一个口径:
 * - **顺序就是拍的顺序**, 从左到右再换行; 空着的格子留黑, 这样"这里没有一张"与"整格是黑的"分得开
 * - **每一格先缩到位再拼**, 而不是拼一张大的再整张缩一遍 —— 后者要在内存里开一张 N 倍大的图
 * - **它是用来看动起来的**: 每格都是缩略图, 读小字仍要看那几张原图 (所以原图照旧一起交出去)
 *
 * @returns `{ buffer, columns, rows, bytes, tile }`, 拼不出来时回 `{ error }`
 */
async function gridOf(paths) {
  if (paths.length < 2) return { error: 'a grid needs at least two frames' }
  try {
    const sharp = (await import('sharp')).default
    const cell = await sharp(paths[0]).metadata()
    if (!cell?.width || !cell?.height) return { error: `could not read ${paths[0]}` }
    const columns = Math.max(1, Math.ceil(Math.sqrt(paths.length)))
    const rows = Math.ceil(paths.length / columns)
    // 每格缩到 480 px 那一档: 拼出来 4 格是 960 宽, 9 格是 1440 宽, 都还落在路由的图像预算里
    const scale = Math.min(1, GRID_CELL_PX / Math.max(cell.width, cell.height))
    const tileWidth = Math.max(1, Math.round(cell.width * scale))
    const tileHeight = Math.max(1, Math.round(cell.height * scale))
    const cells = await Promise.all(
      paths.map((path) =>
        sharp(path).resize(tileWidth, tileHeight, { fit: 'fill' }).jpeg({ quality: 88 }).toBuffer()),
    )
    const blank = await sharp({
      create: {
        width: tileWidth * columns,
        height: tileHeight * rows,
        channels: 3,
        background: { r: 0, g: 0, b: 0 },
      },
    }).png().toBuffer()
    const buffer = await sharp(blank)
      .composite(paths.map((_path, index) => ({
        input: cells[index],
        left: (index % columns) * tileWidth,
        top: Math.floor(index / columns) * tileHeight,
      })))
      .jpeg({ quality: 88 })
      .toBuffer()
    return { buffer, columns, rows, bytes: buffer.length, tile: `${tileWidth}x${tileHeight}` }
  } catch (error) {
    return { error: error?.message ?? String(error) }
  }
}

/** 网格每一格最长那一边缩到多少: 480 是"看得清走向、又装得进图像预算"那一档 */
const GRID_CELL_PX = 480

/**
 * 那几拍量出来是多少 (应用给的是每一张距起点的毫秒数, 逗号分开)
 *
 * 隔到下一张该拍的那一刻为止是"要的", 而一次抓帧本身要两三百毫秒 —— 所以这个数经常大于要的那个,
 * 而那正是要紧的信息: 模型按时间轴理解画面时, 用的是量到的这个
 */
function gapText(offsets, elapsedMs, frames) {
  const at = String(offsets ?? '')
    .split(',')
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isFinite(value))
  if (at.length < 2) return ''
  const gaps = at.slice(1).map((value, index) => value - at[index])
  const each = Math.round(gaps.reduce((sum, value) => sum + value, 0) / gaps.length)
  return `${each}ms apart on average (${gaps.join('/')}ms)`
    + (frames > at.length ? `, only ${at.length} of ${frames} frames arrived` : '')
}

function pictureType(data, path) {
  if (data[0] === 0xff && data[1] === 0xd8) return 'image/jpeg'
  if (data[0] === 0x89 && data[1] === 0x50 && data[2] === 0x4e && data[3] === 0x47) return 'image/png'
  throw new Error(`${path} is neither a JPEG nor a PNG picture`)
}

/**
 * 这张图是附件库认识的哪一种 (四种都认, 与 dsh 的 `ImageMediaType` 同一份表)
 *
 * `pictureType` 只给相机与截图那条路用, 那两处交出来的不是 JPEG 就是 PNG; 而 p 图那一侧进来的
 * 可能是相册里任何一张图 (WebP 与 GIF 都在 dsh 接受的那四个里)。认不出来就抛, **不猜** ——
 * 附件库会拿声明的类型与字节比对, 猜错那一步的报错在库里面, 比这里少一个路径
 */
function attachmentImageType(data, path) {
  if (data[0] === 0xff && data[1] === 0xd8) return 'image/jpeg'
  if (data[0] === 0x89 && data[1] === 0x50 && data[2] === 0x4e && data[3] === 0x47) return 'image/png'
  if (data.length > 11 && Buffer.from(data.subarray(4, 8)).toString('latin1') === 'WEBP') return 'image/webp'
  if (data[0] === 0x47 && data[1] === 0x49 && data[2] === 0x46) return 'image/gif'
  throw new Error(
    `${path} is not a picture the image tools take: the attachment store accepts PNG, JPEG,` +
      ' WebP and GIF, and the bytes name none of them',
  )
}

/** 一个引用对象给不给得动附件库: 四个字段一个都不能少, 类型也要在那四个里面 */
function isAttachmentRef(value) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const ref = value
  return typeof (ref.attachmentId ?? ref.attachment_id) === 'string'
    && ['image/png', 'image/jpeg', 'image/webp', 'image/gif']
      .includes(ref.mediaType ?? ref.media_type)
    && Number.isInteger(ref.bytes) && ref.bytes > 0
    && Number.isInteger(ref.width) && ref.width > 0
    && Number.isInteger(ref.height) && ref.height > 0
}

/**
 * 模型手里那个引用对象 (snake_case, 与 `edit_image` 的 `source_image` 同一份形状) 换成附件库的
 *
 * 两种拼法都收 (`attachment_id` 与 `attachmentId`): 模型从工具结果里抄的是前者, 而有人手工拼一个
 * 出来时会按 TypeScript 那份写后者。**错的形状要在这里拒**, 不要带着一个 `undefined` 往下走 ——
 * 那时失败会发生在附件库里, 报的是一句与真正原因无关的话
 */
function attachmentRefOf(value) {
  if (!isAttachmentRef(value)) {
    throw new Error(
      'that is not an image reference: pass the whole object a generation or an edit answered' +
        ` with (attachment_id, media_type, bytes, width, height), and nothing else`,
    )
  }
  const name = value.name
  return {
    attachmentId: value.attachmentId ?? value.attachment_id,
    mediaType: value.mediaType ?? value.media_type,
    bytes: value.bytes,
    width: value.width,
    height: value.height,
    ...typeof name === 'string' && name !== '' ? { name } : {},
  }
}

/** 一条消息的 content 里最新的那张图 (图块可能包在 tool-result 里面) */
function imageRefInContent(content) {
  if (!Array.isArray(content)) return undefined
  for (let index = content.length - 1; index >= 0; index -= 1) {
    const block = content[index]
    if (typeof block !== 'object' || block === null || Array.isArray(block)) continue
    if (block.type === 'image' && isAttachmentRef(block.attachment)) return block.attachment
    if (block.type === 'tool-result') {
      const nested = imageRefInContent(block.content)
      if (nested !== undefined) return nested
    }
  }
  return undefined
}

/**
 * 这一场会话里最新的一张图
 *
 * 它可能是主人自己在会话里发的那一张 (相册里挑的 / 拍下来的), 也可能是指代不明时应用自动附上的
 * 那张主屏截图。两者对 p 图是同一样东西: **主人指的是这一张**
 *
 * 读的是 dsh 自己的会话对象 (`exec.agent.session`), 与生图插件里那条 `/edit_image` 命令同一条路
 * (`latestSessionImage`), 所以"哪一张最新"两处算出来的是同一张
 */
function newestConversationImage(exec) {
  const messages = exec?.agent?.session?.deriveMessages?.()
  if (!Array.isArray(messages)) return undefined
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const found = imageRefInContent(messages[index]?.content)
    if (found !== undefined) return found
  }
  return undefined
}

/** 路径按调用方写的形状落地: 绝对的照原样, 相对的按宿主的工作区算 (它是进程的 cwd) */
function picturePath(asked) {
  return isAbsolute(asked) ? asked : resolve(process.cwd(), asked)
}

/** 附件库给的那份引用, 印成生图工具要的形状 (snake_case, 与 `edit_image` 的 source_image 一样) */
function projectImageRef(ref) {
  return {
    attachment_id: String(ref.attachmentId),
    media_type: ref.mediaType,
    bytes: ref.bytes,
    width: ref.width,
    height: ref.height,
    ...ref.name === undefined || ref.name === '' ? {} : { name: ref.name },
  }
}

/**
 * 交给生图接口的那一份图按它自己的像素来, **不按"模型的上下文预算"**
 *
 * 设置页「截图」那两条预算管的是"一张图进模型上下文有多大", 而这里这张图的去处是上游的生图接口:
 * 它不经过模型, 所以先压到 64 万像素只会让改图少一半细节。落进模型上下文的仍然只是改完之后的
 * 结果 (那条结果照旧受预算管), 两件事不冲突
 */
const IMAGE_SOURCE_PIXELS = 4_000_000
const IMAGE_SOURCE_BYTES = 16 * 1024 * 1024

/** 拍一块屏, 拿最清楚的那一份 (`fullPath` 是没缩过的那张, `path` 是按预算缩过的) */
async function photographScreen(displayId) {
  const id = Number.isInteger(displayId) ? displayId : 0
  const shot = await call('screenshot', {
    displayId: id,
    maxPixels: IMAGE_SOURCE_PIXELS,
    maxBytes: IMAGE_SOURCE_BYTES,
  })
  const path = String(shot?.fullPath ?? '').trim() || String(shot?.path ?? '').trim()
  if (path === '') {
    throw new Error(
      `no picture came back from screen ${id}: ${shot?.error || 'the device did not say why'}`,
    )
  }
  return path
}

/** 读一个文件当图片, 读不到时把真正读的那个路径说清楚 (相对路径换算过之后才知道是哪一个) */
async function readPictureFile(path, asked) {
  try {
    return await readFile(path)
  } catch (error) {
    throw new Error(
      `there is no picture to read at ${path} (asked as "${asked}"): ${error?.message ?? error}`,
    )
  }
}

/**
 * `lw_image op=ref`: 把一张图交给附件库, 回那个引用对象
 *
 * 三个来源共用后半段 —— 拍屏 / 读文件 / 会话里最新那张。**会话那一档直接交回已有的引用** (它本来就是
 * 附件库给的), 不再存一遍: 存两次会得到两个不同的 id, 而它们在模型眼里是同一张图
 */
async function stagePictureForImageTools(args, exec) {
  const attachments = hostCtx?.get?.('attachments')
  if (typeof attachments?.saveImage !== 'function') {
    throw new Error(
      'this host has no attachment store, so a picture cannot be handed to the image tools',
    )
  }
  const asked = typeof args?.path === 'string' ? args.path.trim() : ''
  const from = String(args?.from ?? 'screen').trim().toLowerCase()
  if (asked === '' && from === 'conversation') {
    const ref = newestConversationImage(exec)
    if (ref === undefined) {
      throw new Error(
        'this conversation holds no picture yet: ask the person to send it, or call op=ref' +
          ' without `from` to photograph the screen they are pointing at',
      )
    }
    return JSON.stringify(projectImageRef(ref))
  }
  let data
  let source
  if (asked !== '') {
    source = picturePath(asked)
    data = await readPictureFile(source, asked)
  } else if (from === 'screen') {
    source = await photographScreen(args?.displayId)
    data = await readPictureFile(source, source)
  } else {
    throw new Error(`from has to be screen or conversation, not "${args.from}"`)
  }
  const mediaType = attachmentImageType(data, source)
  const ref = await attachments.saveImage({ data, mediaType, name: basename(source) })
  return JSON.stringify(projectImageRef(ref))
}

/**
 * 相册那份的名字: 去掉路径成分与控制字符, 空的时候用一个带时间戳的
 *
 * 没带扩展名就按真实的媒体类型补一个 —— 相册与文件管理器按后缀认东西, 一份叫 `edited` 的 PNG 在
 * 有些地方是打不开的
 */
function albumName(asked, extension) {
  const cleaned = String(asked ?? '')
    .split(/[\\/]/)
    .pop()
    .trim()
    .replace(/[\u0000-\u001f]/g, '')
  const base = cleaned === '' || cleaned === '.' || cleaned === '..' ? '' : cleaned
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  const named = base === '' ? `edited-${stamp}` : base
  return named.includes('.') ? named : `${named}.${extension}`
}

/**
 * `lw_image op=album`: 把成图放进相册 (那一步只有应用这一侧做得到) 并打开它
 *
 * 字节先落在工作区的 `pictures/` 下, 再让应用那一侧从那儿拷进媒体库: 桥是一条一行 JSON 的短连接,
 * 几兆的图不该从它上面过。工作区那份**留着** (与 `lw_screenshot` / `lw_take_photo` 同一个规矩:
 * 模型手里要有一个能指的路径), 相册那份才是给主人看的
 */
async function fileFinishedPicture(args) {
  const attachments = hostCtx?.get?.('attachments')
  const asked = typeof args?.path === 'string' ? args.path.trim() : ''
  const given = args?.image
  let data
  let mediaType
  let name = typeof args?.name === 'string' ? args.name.trim() : ''
  if (given !== undefined && given !== null) {
    if (typeof attachments?.readImage !== 'function') {
      throw new Error('this host has no attachment store, so an image reference cannot be read back')
    }
    const stored = await attachments.readImage(attachmentRefOf(given))
    data = stored.data
    mediaType = stored.ref.mediaType
    if (name === '' && typeof stored.ref.name === 'string') name = stored.ref.name
  } else if (asked !== '') {
    const source = picturePath(asked)
    data = await readPictureFile(source, asked)
    mediaType = attachmentImageType(data, source)
    if (name === '') name = basename(source)
  } else {
    throw new Error(
      'op=album has to be given the picture: pass the reference a generation or an edit answered' +
        ' with in `image`, or a picture file in `path`',
    )
  }
  const extension = mediaType === 'image/jpeg' ? 'jpg' : mediaType.split('/')[1]
  name = albumName(name, extension)
  const staged = join(process.cwd(), 'pictures', name)
  await mkdir(dirname(staged), { recursive: true })
  await writeFile(staged, data)
  const answer = await call('gallery', { source: staged, name, open: args?.open !== false })
  return answerOf(answer)
}

/**
 * 切模式的**唯一一条实现**: 一次桥调用, 应用那一侧把这一个模式要的三件事一起做完
 * (提示词 / 摄像头 / 常驻语音, 见 `LwModes.set`)
 *
 * **这里只发一条命令, 不做第二步**: 早先是"先调 `mode`, 再调 `camera`"两趟往返, 而语音那一半还要在
 * 应用里白等 600 ms; 现在三件事都在应用那一次调用里, 而且**相机那一半不等人** —— 回执说的是"正在开",
 * 开好了 `lw_look` 直接用那一台 (它自己会等那把锁)。切模式快不快就差在这一条上 (2026-10-06 主人的
 * 口径: 切模式只跑对应的那一个脚本, 别的什么都不做)
 *
 * 三个调用方共用它, 所以只有一份实现: 模型调 `lw_mode`、主人说了一句命令句 ([matchVoiceCommand]),
 * 以及设备上那三个脚本 (`modes/{phone,video,screen}.sh` —— 各自只管自己那一个模式)
 */
async function applyMode(mode) {
  return call('mode', { mode })
}

/**
 * 切模式回执那几行 (谁切的都念同一份): 换成了什么、相机在干什么、语音那一半怎么样
 *
 * **相机那一行说的是"正在开 / 正在收"**, 不是"开好了": 那两半不堵回执, 真相由 `lw_look` 或
 * `lw_wakeword op=status` 去读 —— 报一个还没发生的事实是最不该有的那种错
 */
function modeLines(answer) {
  return [
    `mode -> ${answer.mode} (${answer.name}); ${answer.detail}`,
    `camera: ${answer.camera}`,
    `voice: ${answer.voice}`,
  ]
}

/* ── 取景与截图那几个数字: 整个仓库只有这一份 (批次 3 的需求 5) ───────────────
 *
 * 口径表 (权威) 在 `docs/shot-params.md`, 而这里是它在代码这一侧的那一份: 工具描述里的每个数字都由
 * 它拼出来, 夹取 (`Math.min` 那几处) 也读它 —— **写死一个字面量就是下一次漂移**。
 *
 * `tools/check-shot-params.mjs` 拿口径表跟五处对着核: 这一份、应用那几个 Kotlin 常量、两份提示词
 * (`assets/modes/video.md`)、技能 (`skills/android-device-control/SKILL.md`) 与视频模式那个预置的
 * 副本 (`presets/video/*`)。改数字先改 `docs/shot-params.md`, 再让这个脚本告诉你还差哪几处
 */
const SHOT_PARAMS = {
  /** 取景: 视频模式那一台相机 (`lw_look`), 数字与应用侧 `VideoLooks` / `LwCamera` 同源 */
  look: {
    framesMin: 1,
    framesMax: 12,
    framesDefault: 4,
    /** 第二组 (仍不确定时那一次) 的张数, 也是"最多两组"里第二组的级差 */
    secondLook: 9,
    /** 东西在动、要看清走向时的张数 */
    movingFrames: 12,
    /** 一次取景最多来几组 */
    groups: 2,
    intervalMinMs: 100,
    intervalMaxMs: 2000,
    intervalDefaultMs: 200,
    /** 清晰度三档的字面 (低 / 默认 / 高), 与 `VideoLooks.levels` 逐项相同 */
    qualityPixels: [640 * 480, 1280 * 720, 1920 * 1080],
  },
  /** 截屏: 屏幕那一条路 (`lw_screenshot`), 数字与应用侧 `ScreenshotBudget` / `PrivilegedBridge` 同源 */
  shot: {
    countMin: 1,
    countMax: 12,
    intervalMinMs: 50,
    intervalMaxMs: 5000,
    intervalDefaultMs: 120,
    qualityPixels: [262_144, 640_000, 1_690_000],
    byteMinKiB: 256,
    byteSliderMaxKiB: 1024,
    byteTypedMaxKiB: 4096,
  },
}

/* ── 模式闸与相机占用表 (批次 3 的需求 17 与需求 3) ───────────────────────────
 *
 * 两道闸都挂在 dsh 的 `ctx.tools.guard` 上: 它是**同步**判据, 回一句理由就把这次调用拒掉 (拒绝在
 * 模型眼里是 `Error: <理由>` + 失败), 与提示词层那一条「拒绝即终态 (策略拦截)」是同一条路。
 *
 * 为什么是这个落点: 这两道闸管的是"这台手机现在的状态", 而调用方是任意一场会话 —— 应用那一侧看不见
 * `exec.agent.id`, 所以占用表这一半只能判在宿主插件里。模式那一半读的是应用写的那个记号
 * (`$DSH_HOME/modes/.active`), 与提示词、摄像头、常驻语音同一个真值源。
 */

/** `.active` 与占用表都住在 `$DSH_HOME/modes/` 下 (应用那一侧: `LwModes` / `CameraOwner`) */
const MODES_DIRECTORY = 'modes'

/** 现在在哪一个模式的记号: 应用每次切模式都重写它 */
const MODE_MARKER = '.active'

/** 本机那两个模式 (批次 4 把识屏模式摘掉了, 现在只剩这两个) */
const MODE_PHONE = 'phone'
const MODE_VIDEO = 'video'

/**
 * 退役的模式名: 识屏模式在 2.5.0 批次 4 摘掉了, 而老机器的 `$DSH_HOME/modes/.active` 里可能还写着它
 * (应用那一侧会把它迁回手机模式, 但那要等应用起过一次) —— 读到它一律当手机模式
 */
const RETIRED_MODES = new Set(['screen'])

/** 占用表的文件名: 应用那一侧同一个约定 (`CameraOwner.FILE`) */
const CAMERA_OWNER_FILE = 'camera-owner.json'

/** 多久没有续期就算过期 (20 分钟): 一场崩了 / 被收掉了, 它的 `turn/end` 就不来了 */
const CAMERA_OWNER_STALE_MS = 20 * 60 * 1000

/**
 * 视频模式里**一律拒**的工具: 读屏 / 在屏上动手 / 起界面或抢相机那三类 (需求 17)
 *
 * 名单是"屏幕这一类"的黑名单, 不是白名单 —— 这一模式的提示词已经写清了只做识图那几件事, 而名单
 * 本身要能一眼看完。**代价写在回执里**: 以后新加的屏幕类工具, 谁加谁负责把它补进这张表
 * (`lw_take_photo` 就是照这条补进来的: 它不是读屏, 但它会起系统相机, 把本模式那台相机抢走)
 */
const VIDEO_MODE_DENIED = new Set([
  // 读屏
  'lw_screenshot', 'lw_screen', 'lw_screen_create', 'lw_screen_resize', 'lw_screen_rotate',
  'lw_screen_release', 'lw_ui', 'lw_ui_dump', 'lw_ocr',
  'lw_events_subscribe', 'lw_events_wait', 'lw_wait_for',
  // 在屏上动手
  'lw_tap', 'lw_swipe', 'lw_type', 'lw_key', 'lw_key_combo', 'lw_gesture', 'lw_pinch',
  'lw_scroll', 'lw_launch',
  // 起系统相机: 它不是屏幕类, 但相机是**一台**, 它一起来本模式的取景就断了
  'lw_take_photo',
])

/** `$DSH_HOME` 底下的一条路径, 没设 DSH_HOME 时回 null (那台机器上没有应用那一侧) */
function homePath(...parts) {
  const home = process.env.DSH_HOME
  return home ? join(home, ...parts) : null
}

/**
 * 现在在哪一个模式: 读 `$DSH_HOME/modes/.active` (应用每次切模式都重写它)
 *
 * guard 是同步的, 所以模式只能从盘上读, 而**这里每次都读**: 这个文件是几十个字节, 而这道闸只对名单里
 * 那二十几个工具走到这一步 (别的工具在 [cameraAndModeGate] 第一个 if 就返回了) —— 模型的工具调用是
 * 秒级的, 省这一次读换不来什么, 而"切完模式下一步就生效"才要紧。
 *
 * 曾经按 mtime 记过一次, 那笔账不值得记: `video` 与 `phone` 正好一样长, 同一毫秒里写的两次从 mtime
 * 与字节数上看着一模一样
 *
 * 认不出来的一律当**手机模式** (缺省那一个): 文件不在 (还没切过 / 被删了)、读到半个字符、或者写进了
 * 一个我们不认识的名字 —— 那时"闸不开"是安全的那一边 (屏幕工具照旧能用), 而真相由
 * `lw_mode {mode:"status"}` 与提示词那一侧管
 */
function activeMode() {
  const file = homePath(MODES_DIRECTORY, MODE_MARKER)
  if (file === null) return MODE_PHONE
  try {
    const stored = readFileSync(file, 'utf8').trim().toLowerCase()
    if (!stored) return MODE_PHONE
    return RETIRED_MODES.has(stored) ? MODE_PHONE : stored
  } catch {
    return MODE_PHONE
  }
}

/** 占用表的位置: `$DSH_HOME/modes/camera-owner.json` */
function cameraOwnerPath() {
  return homePath(MODES_DIRECTORY, CAMERA_OWNER_FILE)
}

/**
 * 现在谁占着相机 (**没人占 / 文件坏了 / 已经过期都回 null**)
 *
 * 过期那一条是**读的时候顺手判的**, 不挂定时器也不加功耗: 一场崩了 (它的 `turn/end` 不会来) 之后
 * 最多挡 20 分钟, 而那 20 分钟里手动释放那两条入口照旧能用
 */
function cameraOwner(now = Date.now()) {
  const file = cameraOwnerPath()
  if (file === null) return null
  let parsed
  try {
    parsed = JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    // 文件不在 / 读到半截 / 不是 JSON: 三种都当"没人占" —— 占用表是一道礼貌的闸, 不是安全边界
    return null
  }
  const sessionId = typeof parsed?.sessionId === 'string' ? parsed.sessionId.trim() : ''
  const at = Number(parsed?.at)
  if (!sessionId || !Number.isFinite(at)) return null
  if (now - at > CAMERA_OWNER_STALE_MS) return null
  const since = Number(parsed?.since)
  return {
    sessionId,
    since: Number.isFinite(since) ? since : at,
    at,
    lens: typeof parsed?.lens === 'string' ? parsed.lens : '',
  }
}

/**
 * 落账 / 续期: 谁在用相机就写谁
 *
 * 回**挡路的那一条** (别人占着) 或 null (写成了, 或者写不成)。写不成时按"没占上"往下走 —— 表写不进
 * 去 (目录只读之类) 也该让取景照常能用, 只记一行日志; 而 `since` 沿用上一条自己的记录, 所以续期不会
 * 把"什么时候开始的"往前挪
 */
async function claimCamera(session, lens = '') {
  const file = cameraOwnerPath()
  if (file === null || !session) return null
  const now = Date.now()
  const current = cameraOwner(now)
  if (current !== null && current.sessionId !== session) return current
  // 续期时**不把已经记下的镜头抹掉**: 大多数调用不点名镜头, 而那一栏是上一次量到的真相
  const lensName = lens || current?.lens || ''
  const table = {
    sessionId: session,
    since: current?.since ?? now,
    at: now,
    ...(lensName ? { lens: lensName } : {}),
  }
  try {
    await mkdir(dirname(file), { recursive: true })
    await writeFile(file, `${JSON.stringify(table)}\n`, 'utf8')
    return null
  } catch (error) {
    warn(hostCtx, `the camera owner table could not be written: ${error?.message ?? error}`)
    return null
  }
}

/**
 * 把占用放掉
 *
 * `session` 给了就是"只放这一场那一条" (那一轮的 `turn/end` 走这条, 别的场不会被顺手放掉); 不给就是
 * 强制放 —— 过期的残留 (文件还在, 而 [cameraOwner] 已经当它没人占了) 也走这一条
 */
async function releaseCameraOwner(session = null) {
  const file = cameraOwnerPath()
  if (file === null) return false
  if (session !== null) {
    const current = cameraOwner()
    if (current === null || current.sessionId !== session) return false
  }
  try {
    await rm(file, { force: true })
    return true
  } catch (error) {
    warn(hostCtx, `the camera owner table could not be released: ${error?.message ?? error}`)
    return false
  }
}

/** 这一场会话的 id, 非会话调用 (没有 agent) 回 null */
function sessionIdOf(exec) {
  const id = exec?.agent?.id
  return id === undefined || id === null ? null : String(id)
}

/**
 * 这一场会话的工作目录, 拿不到就是 null
 *
 * 与 dsh 自己的 shell 工具读的是同一个东西 (`agent.session.header.cwd`, 见 tool-bash 的
 * resolveWorkdir), 所以 `lw_linux` 里那个 /work 与"别的工具说的当前目录"是同一个目录
 */
function sessionCwdOf(exec) {
  const cwd = exec?.agent?.session?.header?.cwd
  return typeof cwd === 'string' && cwd.length > 0 ? cwd : null
}

/** 这一次 `lw_mode` 是不是要切进视频模式 */
function wantsVideo(args) {
  return String(args?.mode ?? '').trim().toLowerCase() === MODE_VIDEO
}

/** 镜头名字归一化 (与 `lw_look` 送过桥的那一份同一个写法) */
function normalizeLens(lens) {
  return typeof lens === 'string' ? lens.trim().toLowerCase() : ''
}

/** 会话 id 报前八个字符: 占用表里放的是整条, 而回执里那一句要能读 */
function shortSession(sessionId) {
  return String(sessionId).slice(0, 8)
}

/** 一个时间点的钟点 (设备本地时间), 用在"什么时候开始占的"那一句里 */
function clockOf(ms) {
  const at = new Date(Number(ms))
  const pad = (value) => String(value).padStart(2, '0')
  return `${pad(at.getHours())}:${pad(at.getMinutes())}`
}

/** 视频模式里碰到屏幕类工具的回执 (需求 17): 说清"这个模式的屏幕指镜头, 出口是切模式" */
function screenToolRefusal(name) {
  return `refused: ${name} is not available in video mode. Here "the screen" means the camera`
    + ' picture and nothing else: look with lw_look, or switch back to phone mode first'
    + ' (lw_mode {mode:"phone"}) and take the screenshot there, when the user really means the'
    + ' display in their hand.'
}

/** 相机被别场占着时的回执 (需求 3): 点名哪一场、从什么时候起, 再指两条手动释放的入口 */
function cameraBusyRefusal(owner, name) {
  return `refused: ${name} needs the camera, and another conversation holds it right now`
    + ` (session ${shortSession(owner.sessionId)}, using it since ${clockOf(owner.since)}).`
    + ' Ask the user to switch that one back to phone mode, or to release it from the ball'
    + ' menu\'s "release video mode" row or the settings page\'s video recognition section.'
}

/**
 * 两道闸 (需求 17 与需求 3), 一个 guard
 *
 * 顺序是刻意的: **名单外的工具一次都不碰盘**。宿主里还跑着 dsh 自己那几十个工具 (read / write /
 * bash / grep …), 而 guard 是全局的 —— 它们每次调用都只多一次 Set 查找
 *
 * 名单里的工具则问两件事:
 * 1. 现在是不是视频模式 (读那一个记号) —— 是, 且它是屏幕类 → 拒
 * 2. 相机有没有被别场占着 —— 有, 而这一次调用要用相机 (`lw_look` / 切视频模式) → 拒
 *
 * 占用表那一条**只挡取景与切模式**, 不挡别的: 别场在用相机与"这一场读个电池"无关
 */
function cameraAndModeGate(exec) {
  const name = typeof exec?.name === 'string' ? exec.name : ''
  const screened = VIDEO_MODE_DENIED.has(name)
  const camera = name === 'lw_look' || (name === 'lw_mode' && wantsVideo(exec?.arguments))
  if (!screened && !camera) return undefined
  if (screened && activeMode() === MODE_VIDEO) return screenToolRefusal(name)
  if (!camera) return undefined
  const session = sessionIdOf(exec)
  // 没有会话身份的调用 (非模型那条路) 不问占用: 它既占不上, 也不该被一道它没参与的表挡住
  if (session === null) return undefined
  const owner = cameraOwner()
  if (owner !== null && owner.sessionId !== session) return cameraBusyRefusal(owner, name)
  return undefined
}

/**
 * 订阅一次会话事件 —— **必须走这一条, 不要直接写 `ctx.on('session/event', ...)`**
 *
 * 2026-10-15 真机上查出来的: 会话事件是**带载体**派发的 (`Session.append` 拿 `entry.carrier` 当
 * `this`), 而载体的 `Context.filter` 只放行它那个作用域里的监听器 (cordis
 * `vendor/cordis/src/events.ts` 那句 `hook.global || !filter || filter.call(thisArg, hook.ctx)`)。
 * 插件挂在 profile 上, 于是**本机 agent 跑出来的轮次一条都到不了它** —— 球上「正在想」与回复框
 * 因此一直是空的, 而同步插件那条 `ctx.emit('session/event', session, event)` 是裸主体派发 (没有
 * filter), 所以电脑侧的投影轮次反倒点亮了球。`{ global: true }` 就是 cordis 给的绕开筛选那一条
 * (见 `packages/core/scope/README.md` 的 `scopeTarget`), 加上之后本机的轮次处处看得见
 *
 * @param {object} ctx - 插件上下文。
 * @param {Function} listener - `(session, event)`。
 * @returns {Function} cordis 那句 disposer。
 */
function onSessionEvent(ctx, listener) {
  return ctx.on('session/event', listener, { global: true })
}

/**
 * 一场的轮结束就把相机占用放掉 (需求 3)
 *
 * 主人的口径是**每轮结束就放**: 一轮就是"这一场真的在用相机"那一小段, 所以只有两场同时在跑时才会
 * 撞上, 而一场答完就把相机让出来。轮结束那条链 (`session/event` 的 `turn/end`) 是 dsh 一定会发的;
 * 崩了 / 被中断的那一轮不发, 那就由 20 分钟那条超时兜底 (见 [cameraOwner])
 */
function startCameraOwnerWatch(ctx) {
  onSessionEvent(ctx, (session, event) => {
    // **便宜的判据排最前**: 这几条钩子跑在每条会话事件的同步链上, 而 `isPeerProjection` 要读会话头
    // —— 逐字流与工具结果这些与这里无关的事件, 一个字都不该为它们做
    if (event.type !== 'turn/end') return
    if (isPeerProjection(session)) return
    if (cameraOwnerPath() === null) return
    void releaseCameraOwner(String(session.id))
  })
}

/**
 * 这一场是不是「对端会话在本机的投影」(luwi-dsh-sync 建的影子工作区里那一场)
 *
 * 投影会话的 `turn/start` / `turn/end` / `assistant/message` 是**同步插件自己追加**的 —— 那台机器
 * 上的问与答在本机被折成了正常事件, 于是所有 `session/event` 的听众都会把它们当成"这台机器上刚刚
 * 发生的一轮"。对**主人自己的对话**有效的那些动作不能这么算:
 *   · 念回答/推进回复框: 那不是主人在跟球说话, 而且在真机上撞出过崩溃 (见下)
 *   · 借出/收回相机、写「正在想」那本账、切视频模式那一对模型: 同理
 * 判据用影子根 (`$DSH_HOME/lw/peer-workspaces`, 由 luwi-dsh-sync 的 `shadowRoot` 定死) 而不是
 * 会话 id/来源 —— 投影会话的 user 消息虽然带 `source.via = 'peer'`, 但 `turn/start` 与 `turn/end`
 * 上没有这个记号, 而这几条钩子看的正是轮次
 *
 * 2026-10-14 真机事故: 投影里第一次带上回答之后, 每一句对端的回答都被推去念、也被画进球的回复框,
 * 而那块框超过 5000 字时 `BoxView.render` 会把光标设到截断前的长度上 → `IndexOutOfBoundsException`
 * → 应用每 100 秒崩一次 (logcat 里三条 `setSpan ... ends beyond length 5000`)
 * @param {object} session - 会话 (有 `header.cwd`)。
 * @returns {boolean} 是对端投影就 true。
 */
function isPeerProjection(session) {
  const cwd = session?.header?.cwd
  const root = peerWorkspaceRoot()
  if (typeof cwd !== 'string' || cwd === '' || root === null) return false
  const canonical = normalizePath(cwd)
  return canonical === root || canonical.startsWith(root + sep)
}

/**
 * 影子根 (`$DSH_HOME/lw/peer-workspaces`) 的归一写法
 *
 * **这里一个文件系统调用都不许有**: 这几条钩子跑在**每条会话事件的同步链**上 (`Session.append` 直接
 * 叫监听器), 而会话的 `cwd` 常在 `/storage/emulated/0` (FUSE) 上 —— 2026-10-15 真机上就是一次
 * `realpath` 把 host 拖慢到"投一句话要十几秒" (流式事件一多, 每个事件都卡在 FUSE 上)
 */
function peerWorkspaceRoot() {
  const home = process.env.DSH_HOME
  if (!home) return null
  return normalizePath(join(home, 'lw', 'peer-workspaces'))
}

/**
 * 把一条路径的两种写法归一
 *
 * Android 上 `/data/user/0/<包名>` 与 `/data/data/<包名>` 指同一处: `DSH_HOME` 是前一种, 而影子会话
 * 的 `cwd` 写的是后一种 —— 逐字比会漏 (漏了它, 电脑那一轮的投影会被当成"本机在跑"), 所以比之前先
 * 都换成同一种
 */
function normalizePath(value) {
  return String(value).replace(/^\/data\/data\//, '/data/user/0/')
}

/* ── 视频模式的独立模型 (3.0.2 需求 2) ──────────────────────────────────────
 *
 * 口径: 视频模式那一次请求用主人挑的模型 (`$DSH_HOME/lw/mode-model.json` 的 video 段),
 * 回手机模式再变回来; **GUI 里那个「当前模型」在来回切换后必须与切换前一致** ——
 * `controller.selectModel` 会顺手把部署默认写成被选中的那一个 (commands.ts:171-175 的
 * `saveSelection`), 所以每一次切换之后都要立刻把默认还回去, 这一步是必须的不是锦上添花。
 * 没挑 (两个键都空 / 只填一半) 时一个 RPC 都不发, 每一步都与没有这个功能时完全一样。
 */

/** 应用写的那份配置: `$DSH_HOME/lw/mode-model.json` (与应用侧 `ModeModel.FILE` 同一个约定) */
const MODE_MODEL_FILE = 'mode-model.json'

/**
 * 自建端点挂成哪一条路由 (与应用侧 `ModeModel.ROUTE` 同一个字面量), 以及它的凭据引用
 *
 * 名字必须是**小写连字符**标识: pi-ai 的手写路由只认这种名字, 而这类名字不能走它的登录流 ——
 * 我们本来就用 `apiKeyEnv`, 正好 (见 `llm-pi-ai/README.md` 的 "Sign in to a provider")
 */
const VIDEO_ROUTE = 'luwi-video'
const VIDEO_KEY_REF = 'LUWI_VIDEO_API_KEY'

/** 自建端点那条路由的展示名 (它会出现在 dsh 自己的模型选择器里, 名字要能一眼认出是谁加的) */
const VIDEO_ROUTE_LABEL = 'Luwi 视频模式'

/** 自建端点写进设置与凭据的账 (内存): 指纹没变就不重复写 */
const videoRouteState = { written: null, lastError: null }

/** 宿主写给应用的那份目录: `$DSH_HOME/lw/model-catalog.json` (与应用侧 `LwModel` 同一个约定) */
const MODEL_CATALOG_FILE = 'model-catalog.json'

/** 应用→宿主那条"现在刷一次目录"的记号: `{"at":ms}` (与 balance-request.json 同一个形状) */
const MODEL_CATALOG_REQUEST_FILE = 'model-catalog-request.json'

/** 那份读数: 切了几次 / 还了几次 / 哪几场在视频模型上 (上面那个函数写的) */
const MODE_MODEL_STATE_FILE = 'mode-model-state.json'

/** 应用那个请求记号多久之内算数 (与应用侧 `LwModel.requestFresh` 同一个数, 谁都不许单边改) */
const MODEL_CATALOG_REQUEST_FRESH_MS = 30 * 1000

/**
 * 起来那几次重试的间隔 (2026-10-16 真机上量出来的)
 *
 * 插件挂在**命令行覆盖层**上, 它的 `apply()` 可能早于 web bundle 把 `sessionController` 注册上 ——
 * 那时写出去的目录是 `ok:false` + 空 items, 而这份文件看起来与"这台机器一个模型都没有"一模一样。
 * 所以起来之后按这三个间隔再试, 成了就不再试; 之后还有应用那条请求记号兜着 (打开设置那一段就刷)
 */
const MODEL_CATALOG_RETRY_MS = [3_000, 10_000, 30_000]

/** 载入期只许读一份小文件: 与 luwi-bridge-runner.mjs 读 session-sync.json 那条上限同一个数 */
const MODE_MODEL_MAX_BYTES = 64 * 1024

/** 读数与"要还回去的那一个" (内存): 宿主重启就没了 —— 重启后第一轮本来就没有可还的 */
const modeModelState = {
  switches: 0,
  restored: 0,
  lastError: null,
  lastDefault: null,
  /** 被我们切到视频模型的那几场: 退出那一刻要按名字还回去 (见 [restoreModeModelSessions]) */
  sessions: new Set(),
  /** 那份读数文件是按哪一份配置写的 (配置改了但什么 RPC 都没发时, 靠它把文件补上) */
  writtenConfig: null,
  /** 上一次落盘的那份读数 (含自建端点那一段): 变了才重写 */
  writtenState: null,
}

/** 目录那份文件的取数状态: 起来那几次重试 (`settled` 之后不再自己试, 只认应用的请求记号) */
const modelCatalogState = { settled: false, tries: 0, nextAt: 0 }

/**
 * 读应用写的那份视频模型配置
 *
 * 64 KiB 上限、不是 JSON / 不是对象都回 null 并留一句警告 —— 配置坏掉不该让 lw_mode 整个不干。
 * 文件不在是常态 (没配过), 静默回 null。两个键**都**非空才算配置; 只填一半与全空一样按
 * 「跟随当前模型」算 (与应用侧 `ModeModel.Snapshot.followsCurrent` 同一个口径)
 *
 * 这里**每次都读** (不加缓存): 与 [activeMode] 同一条路 —— 这个文件是几十个字节, 而读它的两个
 * 触发点 (lw_mode 切换 / turn/start) 都是人的节奏, 省这一次读换不来什么, 而"改完设置下一次就
 * 生效"才要紧
 * @returns {{provider: string, model: string}|null} 配置好了的一对, null = 跟随当前模型。
 */
function readModeModelFile() {
  const path = homePath('lw', MODE_MODEL_FILE)
  if (path === null) return { video: null, endpoint: null }
  let text
  try {
    text = readFileSync(path, 'utf8')
  } catch {
    return { video: null, endpoint: null }
  }
  if (text.length > MODE_MODEL_MAX_BYTES) {
    console.warn(`luwi-channel: ${path} is larger than 64 KiB, ignoring it`)
    return { video: null, endpoint: null }
  }
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch (error) {
    console.warn(`luwi-channel: ${path} is not valid JSON, ignoring it: ${error?.message ?? error}`)
    return { video: null, endpoint: null }
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    console.warn(`luwi-channel: ${path} is not a JSON object, ignoring it`)
    return { video: null, endpoint: null }
  }
  const pairOf = (section) => {
    if (section === null || typeof section !== 'object' || Array.isArray(section)) return null
    const provider = typeof section.provider === 'string' ? section.provider.trim() : ''
    const model = typeof section.model === 'string' ? section.model.trim() : ''
    return provider === '' || model === '' ? null : { provider, model }
  }
  const fieldsOf = (section) => {
    if (section === null || typeof section !== 'object' || Array.isArray(section)) return null
    const str = (key) => (typeof section[key] === 'string' ? section[key].trim() : '')
    const url = str('url')
    const model = str('model')
    // 自建端点要**地址与模型名都在**才算配好 (pi-ai 对手写路由要求非空 models, 只有地址起不来)
    if (url === '' || model === '') return null
    return {
      url,
      model,
      api: str('api') || 'openai-completions',
      apiKey: str('apiKey'),
    }
  }
  return { video: pairOf(parsed.video), endpoint: fieldsOf(parsed.endpoint) }
}

/**
 * 读应用写的那份视频模型配置
 *
 * 两种档**只有一份生效**: 填了自建端点 (`endpoint` 段地址与模型名都在) 就走它, 否则是 `video` 段
 * 那一对 (目录里挑的 / 跟随当前)。两个都空 = 跟随当前模型 = 一个 RPC 都不发
 *
 * 这里**每次都读** (不加缓存): 与 [activeMode] 同一条路 —— 这个文件是几十个字节, 而读它的两个
 * 触发点 (lw_mode 切换 / turn/start) 都是人的节奏, 省这一次读换不来什么, 而"改完设置下一次就
 * 生效"才要紧
 * @returns {{provider: string, model: string}|null} 视频模式该用的那一对, null = 跟随当前模型。
 */
function readModeModelConfig() {
  const { video, endpoint } = readModeModelFile()
  if (endpoint) return { provider: VIDEO_ROUTE, model: endpoint.model }
  return video
}

/** 自建端点那一段 (没配好就是 null) */
function readModeEndpoint() {
  return readModeModelFile().endpoint
}

/**
 * 「切不切, 切到哪」的判定 (纯函数, `tools/check-mode-model.mjs` 量的是这一份)
 *
 * @param {string} activeMode 现在的模式 (读 `modes/.active` 的那一个)。
 * @param {{provider: string, model: string}|null} config 应用写的那对 (null = 跟随当前)。
 * @param {{provider: string, model: string}|null} sessionModel 这一场当前生效的模型
 *   (modelSelection 投影的 `pending ?? lastUsed`, 见 [readSessionModel])。
 * @param {{provider: string, model: string}|null} restoreModel 要还回去的那一个
 *   (进入视频那一刻记下的 lastDefault, 与部署默认同步; 没有就是 null)。
 * @returns {null|{provider: string, model: string}|{restore: true, provider: string, model: string}}
 *   null = 什么都不做 (默认 / 相同 / 读不到那一场的模型 / 别的模式); 无 restore = 切到这一对;
 *   restore = 把这一场切回 restoreModel。
 */
function resolveModeModel(activeMode, config, sessionModel, restoreModel) {
  if (!config) return null
  const same = (left, right) => left !== null && right !== null
    && left.provider === right.provider && left.model === right.model
  if (activeMode === MODE_VIDEO) {
    if (!sessionModel) return null
    if (same(sessionModel, config)) return null
    return { provider: config.provider, model: config.model }
  }
  if (activeMode === MODE_PHONE) {
    if (!sessionModel || !restoreModel) return null
    // 还没切过去 (或已经还回来了) 就不用还; 要还的与视频模型一样也不发 (发了也是同一个)
    if (!same(sessionModel, config)) return null
    if (same(sessionModel, restoreModel)) return null
    return { restore: true, provider: restoreModel.provider, model: restoreModel.model }
  }
  return null
}

/**
 * 这一场当前生效的模型: modelSelection 投影的 pending, 没有就是 lastUsed
 *
 * 投影由 dsh 的 `sessionProjections` 服务养着 (modelSelection 单元, session-controller 挂的),
 * 与 `sessionController.selectModel` 写的是同一份账 —— 所以"要不要发 RPC"的判据直接读它,
 * pending 已经等于目标的那一轮一个 RPC 都不发 (每轮只发一次)
 */
function readSessionModel(ctx, agent) {
  try {
    const state = ctx.get('sessionProjections')?.stateOf?.(agent?.session, 'modelSelection')
    const current = state?.pending ?? state?.lastUsed
    if (current && typeof current.provider === 'string' && typeof current.model === 'string') {
      return { provider: current.provider, model: current.model }
    }
  } catch (error) {
    console.warn(`luwi-channel: could not read the session's model: ${error?.message ?? error}`)
  }
  return null
}

/**
 * 把一场会话的模型按口径校正一次
 *
 * @param ctx 宿主上下文。
 * @param {string|null} sessionId 会话 id (null = 没有会话身份, 不做)。
 * @param {string} mode 目标模式 (video | phone)。
 * @param {{provider: string, model: string}|null} preDefault 切进视频**前**那一刻的部署默认
 *   (调用方现取的, 不信任旧值): selectModel 之后立刻把它 saveSelection 回去。
 * @returns {object|null} 实际做了的那一次 (与 [resolveModeModel] 的结论同形), 没做就是 null。
 */
async function applyModeModelToSession(ctx, sessionId, mode, preDefault) {
  if (sessionId === null) return null
  const controller = ctx.get('sessionController')
  const defaults = ctx.get('agentDefaultModel')
  if (!controller || !defaults) return null
  const resolved = await ctx.agents.withoutInitiator(() => controller.resolveAgent(sessionId))
  if ('error' in resolved) throw resolved.error
  const sessionModel = readSessionModel(ctx, resolved.agent)
  const restore = modeModelState.lastDefault
    ?? (typeof defaults.currentSelection === 'function' ? defaults.currentSelection() : null)
  const conclusion = resolveModeModel(activeMode(), readModeModelConfig(), sessionModel, restore)
  if (!conclusion) return null
  await controller.selectModel({
    sessionId, provider: conclusion.provider, model: conclusion.model,
  })
  if (conclusion.restore) {
    // selectModel 顺手把部署默认写成了被选中的那一个 —— 这里把它还回去 (必须的一步)
    if (typeof defaults.saveSelection === 'function') {
      await defaults.saveSelection({ provider: conclusion.provider, model: conclusion.model })
    }
    modeModelState.restored += 1
    modeModelState.lastDefault = null
    modeModelState.sessions.delete(sessionId)
  } else {
    if (preDefault && typeof defaults.saveSelection === 'function') {
      await defaults.saveSelection(preDefault)
    }
    modeModelState.lastDefault = preDefault
    modeModelState.switches += 1
    // 记下这一场: 退出视频模式那一刻要按名字还回去, 而不是等它的下一轮 (见下面那个函数)
    modeModelState.sessions.add(sessionId)
  }
  modeModelState.lastError = null
  await writeModeModelState()
  return conclusion
}

/**
 * 退出视频模式那一刻, 把**被我们切过的那几场**当场还回去 (2026-10-16 真机上量出来的那一笔)
 *
 * 为什么不能只靠 `turn/start` 那一个兜底: 真机上量到的是 —— 轮开始之后 selectModel 才落地, 而那一轮
 * 的**第一个请求**已经发出去了, 于是"退出后再发一句"的第一句仍然用视频模型回, 与主人要的"回到 A"
 * 差一轮。所以退出那一刻就按记下来的名字还 (只还**当前在跑**的: `resolveAgent` 会把冷会话恢复起来,
 * 那等于替主人开一场)
 *
 * 与 [resolveModeModel] 同一份算术: 那一场还在视频模型上、并且与"要还的那一个"不同, 才发 RPC
 * @param ctx 宿主上下文。
 * @returns 这一次真的还回去几场。
 */
async function restoreModeModelSessions(ctx) {
  if (modeModelState.sessions.size === 0) return 0
  const controller = ctx.get('sessionController')
  const defaults = ctx.get('agentDefaultModel')
  if (!controller || !defaults) return 0
  const config = readModeModelConfig()
  if (config === null) {
    // 配置被清掉了 (跟随当前模型): 没有"视频模型"可认, 也就没有什么要还原的
    modeModelState.sessions.clear()
    return 0
  }
  const listed = await controller.list({}, new AbortController().signal)
  // 判据是 `agentAvailable` 而不是 `running`: 退出那一刻视频那一轮**已经结束** (running=false),
  // 而会话自己的 Agent 还活着 —— 真机上按 running 判过一次, 结果是"退出时一场都没还, 第一句仍用
  // 视频模型回"。而 `agentAvailable` 为真时 resolveAgent 拿的是活着的那个 Agent, **不会唤醒冷会话**
  const live = new Set(
    (listed?.items ?? [])
      .filter((item) => item && item.agentAvailable === true && item.origin !== 'subagent' && !item.parentSessionId)
      .map((item) => String(item.sessionId)),
  )
  let restored = 0
  for (const sessionId of [...modeModelState.sessions]) {
    // Agent 已经不在的那几场从这里摘掉: 它下一次开轮时由 turn/start 那一条兜
    if (!live.has(sessionId)) {
      modeModelState.sessions.delete(sessionId)
      continue
    }
    try {
      const resolved = await ctx.agents.withoutInitiator(() => controller.resolveAgent(sessionId))
      if ('error' in resolved) throw resolved.error
      const sessionModel = readSessionModel(ctx, resolved.agent)
      const restore = modeModelState.lastDefault ?? defaults.currentSelection()
      const conclusion = resolveModeModel(MODE_PHONE, config, sessionModel, restore)
      if (!conclusion) {
        // 已经不在视频模型上 (或没有可还的): 这一场不用再记着
        modeModelState.sessions.delete(sessionId)
        continue
      }
      await controller.selectModel({
        sessionId, provider: conclusion.provider, model: conclusion.model,
      })
      if (typeof defaults.saveSelection === 'function') {
        await defaults.saveSelection({ provider: conclusion.provider, model: conclusion.model })
      }
      modeModelState.restored += 1
      modeModelState.sessions.delete(sessionId)
      restored += 1
    } catch (error) {
      modeModelState.lastError = `restoring ${sessionId} after the mode switch failed: ${error?.message ?? error}`
    }
  }
  if (restored > 0) {
    modeModelState.lastDefault = null
    modeModelState.lastError = null
  }
  await writeModeModelState()
  return restored
}

/** `lw_mode op=status` 里 model 那一行的读数 (与施工单里那份字段一一对应) */
function modeModelStatus() {
  const endpoint = readModeEndpoint()
  return {
    configured: readModeModelConfig(),
    active: modeModelState.lastDefault,
    switches: modeModelState.switches,
    restored: modeModelState.restored,
    lastError: modeModelState.lastError,
    sessions: [...modeModelState.sessions],
    endpoint: {
      // 地址与模型名 (没配好时都是空), 以及**这一档现在挂的是哪条路由**
      url: endpoint?.url ?? '',
      model: endpoint?.model ?? '',
      api: endpoint?.api ?? '',
      keySet: (endpoint?.apiKey ?? '') !== '',
      route: endpoint ? VIDEO_ROUTE : '',
      lastError: videoRouteState.lastError,
    },
  }
}

/** 把一段路径拼成一层的 patch: `['providers']` + 值 → `{providers: 值}` (pi-ai 的 settingsPath) */
function patchAt(path, value) {
  return path.reduceRight((inner, key) => ({ [key]: inner }), value)
}

/**
 * 把自建端点挂成 pi-ai 的一条真路由 (2026-10-16 主人: url / api 等放进视频模式设置)
 *
 * 三件事, 顺序有意:
 * 1. **用户设置**: `settings.update('<pi-ai 的段>', {providers: {'luwi-video': profile}})` ——
 *    providers 是 volatile 字段, 写入即校验, 而且下一轮请求就生效 (不用重启 host)。这条 patch 是
 *    **合并**语义, 所以别人那几条路由 (自建网关 / 目录里的那几家) 一个字节都不动
 * 2. **凭据**: `credentials.set('LUWI_VIDEO_API_KEY', key)` —— key 不进设置文件, 由 pi-ai 每轮请求
 *    经凭据缝取; 端点不要 key 时 profile 里干脆不写 `apiKeyEnv`
 * 3. 指纹没变就一个字节都不写 (这条函数挂在 3 s 轮询上)
 *
 * 清掉自建端点时**不动设置**: 那条路由留在那儿不再被选 (读数里写 route 空), 免得把一条别人可能
 * 也在用的配置删掉 —— 见 3.0.3 施工单第 4 节
 * @param ctx 宿主上下文。
 * @returns {Promise<void>} 失败只写进 [videoRouteState.lastError], 不抛 (轮询那一拍不该因此停摆)。
 */
async function ensureVideoRoute(ctx) {
  const endpoint = readModeEndpoint()
  if (!endpoint) {
    videoRouteState.lastError = null
    return
  }
  const fingerprint = [endpoint.url, endpoint.model, endpoint.api, endpoint.apiKey].join('\u0000')
  if (fingerprint === videoRouteState.written) return
  const settings = ctx.get('settings')
  if (!settings || typeof settings.update !== 'function') {
    videoRouteState.lastError = 'this host has no settings service, so the custom endpoint cannot be mounted'
    return
  }
  try {
    // 段名与那一层的路径**从 pi-ai 自己登记的目录里读**, 不写死: 部署换过行 id 也照样对得上
    const entry = (ctx.get('llm')?.listConfigurableProviders?.() ?? [])
      .find((row) => Array.isArray(row?.settingsPath) && row.settingsPath[0] === 'providers')
    const ns = typeof entry?.settingsNs === 'string' && entry.settingsNs !== '' ? entry.settingsNs : 'llm-pi-ai'
    const path = entry?.settingsPath ?? ['providers']
    const profile = {
      displayName: VIDEO_ROUTE_LABEL,
      api: endpoint.api,
      baseURL: endpoint.url,
      models: [{ id: endpoint.model }],
      ...(endpoint.apiKey === '' ? {} : { apiKeyEnv: VIDEO_KEY_REF }),
    }
    await settings.update(ns, patchAt(path, { [VIDEO_ROUTE]: profile }))
    let credentialProblem = null
    if (endpoint.apiKey !== '') {
      const credentials = ctx.get('credentials')
      if (credentials && typeof credentials.set === 'function') {
        await credentials.set(VIDEO_KEY_REF, endpoint.apiKey)
      } else {
        // 没有凭据缝: 那条路由下一轮会以 MISSING_CREDENTIAL 失败 —— 这句话要留在读数里
        credentialProblem = 'this host has no credentials service, so the endpoint has no key'
      }
    }
    videoRouteState.written = fingerprint
    videoRouteState.lastError = credentialProblem
  } catch (error) {
    // 写入即校验: 地址 / 模型名 / 协议不对时 pi-ai 会在这里拒, 照实记下来给读数看
    videoRouteState.lastError = `mounting the custom endpoint failed: ${error?.message ?? error}`
    videoRouteState.written = null
  }
}

/**
 * 那份读数也落成文件: `$DSH_HOME/lw/mode-model-state.json` (2026-10-16)
 *
 * 三个理由: **量它不必再解那份压缩的会话日志** (`cat` 一下就是"发了几个 RPC"这个数 —— 真机验收
 * 用到的那一条), 应用那一侧以后要看一眼也有个地方, 而"这一场被切走了"这件事因此不是只活在内存里。
 * 原子写, 与 ball-phase.json / balance.json 同一条路
 */
async function writeModeModelState() {
  const path = homePath('lw', MODE_MODEL_STATE_FILE)
  if (path === null) return
  const configured = readModeModelConfig()
  // `configured` 由 [modeModelStatus] 现读给出, 这里只是把它记下来看下一拍要不要补写
  const payload = { v: 1, at: Date.now(), ...modeModelStatus() }
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${JSON.stringify(payload)}\n`)
    await rename(temporary, path)
    // 记下"这份文件是按哪一份配置写的": 配置改了而这一档什么都没做时, 轮询要把它补上 (见 startModeModel)
    modeModelState.writtenConfig = JSON.stringify(configured)
    modeModelState.writtenState = JSON.stringify(modeModelStatus())
  } catch (error) {
    console.warn(`luwi-channel: the mode model state was not written: ${error?.message ?? error}`)
  }
}

/**
 * 模型目录喂给应用的那条 (需求 2 的 T3)
 *
 * 目录只有宿主拿得到 (`sessionController.modelCatalog`, 与 GUI 那个模型选择器同源), 而挑模型的那
 * 一行在应用进程里 —— 还是「文件即真相」: 原子写 (`.tmp` + rename)。起来刷一次, 应用打开那一段时
 * 写的请求记号一出现就刷 (3 s 轮询顺手看一眼)。取不到目录就回 `items: []`, 设置页退两个文本框
 */
async function writeModelCatalog(ctx) {
  const path = homePath('lw', MODEL_CATALOG_FILE)
  if (path === null) return false
  const payload = { v: 1, at: Date.now(), ok: false, current: { provider: '', model: '' }, items: [] }
  try {
    const defaults = ctx.get('agentDefaultModel')
    const current = typeof defaults?.currentSelection === 'function' ? defaults.currentSelection() : null
    if (current) payload.current = { provider: String(current.provider ?? ''), model: String(current.model ?? '') }
    const controller = ctx.get('sessionController')
    if (controller && typeof controller.modelCatalog === 'function') {
      const catalog = await controller.modelCatalog()
      payload.items = (catalog?.groups ?? []).flatMap((group) => (group?.models ?? []).map((model) => ({
        provider: String(group.id ?? ''),
        model: String(model.id ?? ''),
        label: `${group.name ?? group.id} · ${model.name ?? model.id}`,
      })))
    } else {
      // **说得清为什么空**: 起得太早时服务还没挂上, 那不是"这台机器没有模型" (真机 2026-10-16)
      payload.reason = 'this host has no session controller at this moment, so the catalog cannot be read yet'
    }
    payload.ok = payload.items.length > 0
  } catch (error) {
    // 目录取不到不算失败: ok:false + 空 items, 应用那边退文本框, 不让请求死在这
    payload.reason = `building the model catalog failed: ${error?.message ?? error}`
  }
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${JSON.stringify(payload)}\n`)
    await rename(temporary, path)
  } catch (error) {
    console.warn(`luwi-channel: the model catalog was not written: ${error?.message ?? error}`)
    return false
  }
  if (payload.ok) modelCatalogState.settled = true
  return payload.ok
}

/** 应用那个"现在刷一次目录"的记号是不是刚写的 (30 s 之内) */
async function modelCatalogRequested() {
  const path = homePath('lw', MODEL_CATALOG_REQUEST_FILE)
  if (path === null) return false
  try {
    const at = JSON.parse(await readFile(path, 'utf8'))?.at
    const age = Date.now() - at
    return typeof at === 'number' && age >= 0 && age <= MODEL_CATALOG_REQUEST_FRESH_MS
  } catch {
    return false
  }
}

/**
 * 视频模式独立模型的两处挂载
 *
 * 1. **`turn/start` 钩子 (只根会话)**: 兜住 GUI 自己开的会话 —— 每轮开始读那一场的当前模型,
 *    与 [resolveModeModel] 的结论不同才发 RPC (投影里 pending 已经等于目标就跳过)。
 *    视频模式里顺手认一遍「要还回去的那一个」: 主人在 GUI 里换过模型就以当下的为准重记。
 * 2. **目录那一条**: 起来刷一次 + 应用的请求记号 (3 s 轮询, unref 掉不挡退出)。
 *
 * `lw_mode` 切换那一路不在这里 (它要走 exec 里的 `exec.agent.id`, 见工具定义那一段)
 */
function startModeModel(ctx) {
  onSessionEvent(ctx, (session, event) => {
    if (event.type !== 'turn/start') return
    if (session.meta?.origin === 'subagent') return
    // 对端投影的那一轮不是在这台机器上跑的: 它不该换本地那一对模型 (见 isPeerProjection)
    if (isPeerProjection(session)) return
    const mode = activeMode()
    if (mode !== MODE_VIDEO && mode !== MODE_PHONE) return
    const sessionId = String(session.id)
    if (mode === MODE_VIDEO) {
      // 主人在 GUI 里换过模型: 以当下的 currentSelection() 为准重记 lastDefault, 不信任旧值
      const defaults = ctx.get('agentDefaultModel')
      const config = readModeModelConfig()
      const current = typeof defaults?.currentSelection === 'function' ? defaults.currentSelection() : null
      if (config && current && modeModelState.lastDefault
        && (current.provider !== modeModelState.lastDefault.provider
          || current.model !== modeModelState.lastDefault.model)
        && (current.provider !== config.provider || current.model !== config.model)) {
        modeModelState.lastDefault = current
      }
    }
    void applyModeModelToSession(ctx, sessionId, mode, null).catch((error) => {
      modeModelState.lastError = `correcting the session model at turn start failed: ${error?.message ?? error}`
    })
  })
  void writeModelCatalog(ctx).catch((error) => {
    warn(ctx, `the model catalog was not written: ${error?.message ?? error}`)
  })
  // 应用配了自建端点的话, 起来就把它挂上 (第一次进视频模式不必等那 3 s 轮询)
  void ensureVideoRoute(ctx).catch((error) => {
    warn(ctx, `the custom endpoint was not mounted: ${error?.message ?? error}`)
  })
  // 读数也先落一份: 起来就有文件可看 (设备上 `cat` 一下就知道这一档现在是"跟随"还是配了什么)
  void writeModeModelState().catch((error) => {
    warn(ctx, `the mode model state was not written: ${error?.message ?? error}`)
  })
  const timer = setInterval(() => {
    // 自建端点那一段变了就先把它挂成路由 (写设置 + 写凭据) —— 排在校正与目录前面: 那一轮要用的
    // 路由得先在
    void ensureVideoRoute(ctx).catch(() => {})
    // 已经不是视频模式了, 而我们手上还记着被切过的会话: 当场还回去 (球单击 / 设备上那几个脚本
    // 切回手机模式时, 宿主这一段一个 RPC 都没参与, 只能这样看见)
    if (modeModelState.sessions.size > 0 && activeMode() !== MODE_VIDEO) {
      void restoreModeModelSessions(ctx).catch((error) => {
        modeModelState.lastError = `restoring the recorded sessions failed: ${error?.message ?? error}`
      })
    }
    // 配置改了但这一档什么都没做 (比如改回「跟随当前模型」): 读数文件也要跟上, 免得它拿着一份
    // 过期配置给人看 (读数本身是现读的, 过期的是那份落盘的文件)
    if (JSON.stringify(modeModelStatus()) !== modeModelState.writtenState) {
      void writeModeModelState().catch(() => {})
    }
    void modelCatalogRequested().then((wanted) => {
      if (wanted) {
        // 应用在看着呢: 记号一来就刷, 顺便把"起来那几次没成"那笔账重置掉
        modelCatalogState.tries = 0
        modelCatalogState.nextAt = 0
        void writeModelCatalog(ctx).catch(() => {})
        return
      }
      // 起来那几次没成 (服务还没注册) 时按 [MODEL_CATALOG_RETRY_MS] 再试 —— 试满就停手, 之后靠记号
      if (modelCatalogState.settled || modelCatalogState.tries >= MODEL_CATALOG_RETRY_MS.length) return
      if (Date.now() < modelCatalogState.nextAt) return
      modelCatalogState.nextAt = Date.now() + MODEL_CATALOG_RETRY_MS[modelCatalogState.tries]
      modelCatalogState.tries += 1
      void writeModelCatalog(ctx).catch(() => {})
    })
  }, PLUGIN_POLL_MS)
  if (typeof timer.unref === 'function') timer.unref()
}

/**
 * LW 插件 (批次 9): 把应用那一侧的快照变成这个宿主上的工具
 *
 * 分工是刻意的 —— **应用那一侧才是唯一做能力检查、授权与审计的地方**, 这里只负责"让模型看得见"。
 * 装一份插件包不会让工具自动出现, 也没有一条路能绕开那边: 这个函数拿的就是那边给的那份清单
 *
 * 每 [PLUGIN_POLL_MS] 拉一次快照 (回环上一次调用, 代价可以忽略), **revision 没变就什么都不做** ——
 * 于是"在设置页勾一条能力 / 停用 / 卸掉"这些事下一拍就反映到模型眼前, 不用重启 host
 */
const PLUGIN_POLL_MS = 3000

const pluginRegistry = { revision: null, disposers: new Map() }

function startPluginTools(ctx) {
  const tick = async () => {
    let snapshot
    try {
      snapshot = await call('plugin', { op: 'snapshot' })
    } catch (error) {
      // 应用那一侧还没起来 (或者通道还没接上): 下一拍再问, 这不是一个错误
      return
    }
    if (snapshot === null || typeof snapshot !== 'object') return
    if (snapshot.revision === pluginRegistry.revision) return
    applyPluginTools(ctx, snapshot)
  }
  const timer = setInterval(() => void tick(), PLUGIN_POLL_MS)
  if (typeof timer.unref === 'function') timer.unref()
  void tick()
}

/** 一份快照换一套工具: 先把旧的都注销, 再按新的登记 */
function applyPluginTools(ctx, snapshot) {
  pluginRegistry.revision = snapshot.revision
  for (const dispose of pluginRegistry.disposers.values()) {
    try {
      dispose()
    } catch (error) {
      warn(ctx, `unregistering a plugin tool failed: ${error?.message ?? error}`)
    }
  }
  pluginRegistry.disposers.clear()
  for (const plugin of Array.isArray(snapshot.plugins) ? snapshot.plugins : []) {
    for (const tool of Array.isArray(plugin?.tools) ? plugin.tools : []) {
      if (pluginRegistry.disposers.has(tool.name)) continue
      try {
        pluginRegistry.disposers.set(tool.name, ctx.tools.register(pluginTool(plugin, tool)))
      } catch (error) {
        warn(ctx, `the plugin tool ${tool.name} was not registered: ${error?.message ?? error}`)
      }
    }
  }
  const names = [...pluginRegistry.disposers.keys()]
  console.log(`luwi-channel: plugin tools now ${names.length === 0 ? '(none)' : names.join(', ')}`)
}

/**
 * 一个插件工具在模型眼里的样子
 *
 * 名字与参数表全部来自 `plugin.json` (插件不能运行时改自己的工具表), 而**说不说得清它来自插件**很
 * 要紧: 模型要知道这一条不是内置能力, 它背后是别人写的包
 */
function pluginTool(plugin, tool) {
  return defineTool({
    name: tool.name,
    description: `${tool.summary || '(这个插件没给它写摘要)'} —— 来自 LW 插件 ${plugin.name}`
      + ` ${plugin.version} (${plugin.id}), 前缀 ${plugin.toolPrefix}_。它是别人写的包: 每一次能力调用`
      + ' 都由应用那一侧逐次检查声明与授权, 并记一份审计',
    parameters: pluginParameters(tool.params),
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          // additionalProperties 必须显式写出来 —— dsh-tools 在 import 那一刻就编译 schema
          text: { type: 'string', required: true },
          images: { type: 'array', items: { type: 'object', additionalProperties: true } },
        },
      },
      render: (_args, value) => {
        const blocks = [{ type: 'text', text: value.text }]
        for (const image of Array.isArray(value.images) ? value.images : []) {
          blocks.push({ type: 'image', attachment: image })
        }
        return blocks
      },
    },
    async execute(args) {
      const result = await call('plugin', { op: 'invoke', id: plugin.id, tool: tool.name, args: drop(args) })
      return await pluginAnswer(result)
    },
  })
}

/** 协议里那份参数窄子集, 原样交给 dsh-tools (多一个键在装包的时候就拒了) */
function pluginParameters(params) {
  if (params === null || typeof params !== 'object') return {}
  const kept = {}
  for (const [name, value] of Object.entries(params)) {
    if (value === null || typeof value !== 'object') continue
    const one = { type: String(value.type ?? 'string') }
    if (typeof value.description === 'string') one.description = value.description
    if (value.required === true) one.required = true
    if (Array.isArray(value.enum)) one.enum = value.enum
    if (value.items !== undefined) one.items = pluginParameters({ items: value.items }).items
    kept[name] = one
  }
  return kept
}

/** 插件回的那一份: 一句话 + 它交出来的几张图 (路径), 图按 `lw_look` 那条路当附件交给模型 */
async function pluginAnswer(result) {
  const text = answerOf(result)
  const paths = Array.isArray(result?.images)
    ? result.images.filter((one) => typeof one === 'string' && one.length > 0)
    : []
  if (paths.length === 0) return { text, images: [] }
  const { images, note } = await attachPictures(paths)
  if (images.length === 0) {
    return {
      text: `${text}\n(图没有随结果交上来: ${note ?? '读不到那几张图'}; 路径是 ${paths.join(', ')})`,
      images: [],
    }
  }
  return { text, images }
}

const TOOLS = [
  defineTool({
    name: 'lw_probe',
    description:
      'Probe the Luwi privileged channel on the Android device this host runs on. '
      + 'Reports the uid the privileged helper process runs as (0 means root), its pid, and the '
      + 'touchscreen ranges that getevent -p reports for it. Use this to check that the channel '
      + 'is up before anything relies on controlling the screen.',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return formatProbe(await call('probe'))
    },
  }),
  defineTool({
    name: 'lw_mode',
    description:
      'Switch which mode this phone assistant is in. There are two. "phone" is the usual one: '
      + 'operate the phone through the lw_* tools, and that includes the phone\'s own screen '
      + '(display 0) - looking at it (lw_ui / lw_ocr / lw_screenshot) and acting on it (lw_tap / '
      + 'lw_swipe / lw_type / lw_key / lw_launch and the rest) are ordinary phone-mode work, so use '
      + 'phone mode when the user points at what their phone\'s own screen shows, whether they ask '
      + 'what it says or ask for something to be done on it. "video" points this phone\'s own '
      + 'camera at what is in front of the user (it runs inside the app, previewing in a small '
      + 'floating window) and answers what it is, in one to three sentences. A switch replaces the '
      + 'assistant\'s prompt text, so it takes effect on the NEXT model step rather than this one: '
      + 'call it, say the mode changed, and stop there - **this one call already does everything that '
      + 'mode needs** (the prompt, the camera, the resident voice chain), so do not open the camera, '
      + 'build a screen or tidy anything up yourself. "video" asks for the voice chain to stay '
      + 'resident (the user can keep talking without saying the wake word again) and brings the '
      + 'camera up in the background; "phone" puts the camera away and releases that chain - '
      + 'outside video mode a wake word or a tap on the ball buys exactly one sentence. Call it '
      + 'with "video" when the user asks to look at something through the camera, and with "phone" '
      + 'when that work is over (they said to quit, close the camera or stop looking). **There is '
      + 'no "screen" mode any more**: the phone\'s own display is handled in phone mode, so a call '
      + 'asking for "screen" is refused and says exactly that. mode "status" reports which one is '
      + 'active right now.',
    parameters: {
      mode: {
        type: 'string',
        required: true,
        description: 'phone, video, or status',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args, exec) {
      const session = sessionIdOf(exec)
      const toVideo = wantsVideo(args)
      // 切进视频模式 = 这一场要点住相机 (需求 3): 别场占着就先拒, **一个字的模式都不换** —— 模式是
      // 全局的 (那一个提示词文件), 换掉了等于把别场的场子也掀了
      if (toVideo && session !== null) {
        const holder = cameraOwner()
        if (holder !== null && holder.sessionId !== session) return cameraBusyRefusal(holder, 'lw_mode')
      }
      const answer = await applyMode(args.mode)
      let note = ''
      if (answer.switched === true && toVideo && session !== null) {
        // 换模式那一步会把占用表清掉 (应用那一侧: 换人设就是放弃相机), 所以要在**切换返回之后**重新
        // 落账。这一瞬被人抢先时如实说一句: 模式确实换了, 而相机不在自己手里
        const busy = await claimCamera(session, '')
        if (busy !== null) {
          note = `\ncamera: session ${shortSession(busy.sessionId)} took it in the meantime -`
            + ' lw_look will be refused until the user releases it'
        }
      }
      if (answer.switched === true && session !== null) {
        // 需求 2 (3.0.2): 切模式那一刻把那一场的模型也校正了。切进视频之前**现取**一次部署默认
        // (不信任旧值): selectModel 会把它写成视频模型, 所以切换之后要立刻把这一个还回去
        //
        // **这里用 hostCtx 不是 ctx** (2026-10-16 真机上踩的): 工具那一层是 `execute(args, exec)`,
        // 作用域里没有 `ctx` 这个名字, 而插件挂在模块级的 hostCtx 上 —— 写成 `ctx.get(...)` 会在
        // 运行期抛 "ctx is not defined", 而它被下面那个 catch 兜住, 回执上只是少了一行、看不出哪里错
        try {
          const defaults = hostCtx.get('agentDefaultModel')
          const preDefault = typeof defaults?.currentSelection === 'function'
            ? defaults.currentSelection()
            : null
          // 自建端点那一段要是刚改过, 先把它挂成路由 —— 这一轮就要用它了 (3 s 轮询可能还没轮到)
          await ensureVideoRoute(hostCtx)
          const corrected = await applyModeModelToSession(hostCtx, session, toVideo ? MODE_VIDEO : MODE_PHONE, preDefault)
          if (corrected?.restore) {
            note += `\nmodel: restored ${corrected.provider}/${corrected.model} for this conversation`
          } else if (corrected) {
            note += `\nmodel: this conversation now uses ${corrected.provider}/${corrected.model}`
          }
          // 退出视频模式: 别场 (被我们切过的那几场) 也当场还回去, 不等它们的下一轮
          if (!toVideo) {
            const restored = await restoreModeModelSessions(hostCtx)
            if (restored > 0) note += `\nmodel: restored ${restored} more conversation(s) that were on the video model`
          }
        } catch (error) {
          modeModelState.lastError = `correcting the model after the mode switch failed: ${error?.message ?? error}`
          note += `\nmodel: left as it was (${modeModelState.lastError})`
        }
      }
      if (answer.switched === false) return answer.detail
      if (answer.modes) {
        return `modes: ${answer.modes}\nactive: ${answer.active}\n`
          + `model: ${JSON.stringify(modeModelStatus())}`
      }
      if (answer.switched === true) return modeLines(answer).join('\n') + note
      return `mode: ${answer.mode} (${answer.name})\n`
        + `prompt file: ${answer.promptWritten ? 'written' : 'missing'} (${answer.promptFile})`
    },
  }),
  defineTool({
    name: 'lw_look',
    description:
      'Look through the camera in ONE call: opens this phone\'s own camera inside the app if it is not '
      + 'up yet, takes frames straight off it (no screen is created, no camera app is launched, no '
      + 'screenshot is taken), and hands you the pictures themselves — no separate read_image step for '
      + 'each one. In video mode use this instead of lw_screen + lw_ui + lw_screenshot. Either camera '
      + 'works and the choice sticks (see the lens parameter). **How many frames and how far apart are '
      + 'the user\'s settings** (the app\'s "Video recognition" section: frames per look, ms between '
      + 'frames, capture quality) and this call takes those as its defaults — do not name a number '
      + 'unless the user asked for something different in so many words. A first look is usually '
      + `${SHOT_PARAMS.look.framesDefault} frames; if that leaves you unsure, look ONE more time `
      + `(${SHOT_PARAMS.look.secondLook} frames), and that is the second and last group — `
      + `${SHOT_PARAMS.look.groups} groups is the ceiling. If the second look still does not settle `
      + 'it, say what you cannot see and ask for a single adjustment; never guess, and never ask for '
      + `a third group. ${SHOT_PARAMS.look.movingFrames} frames is for something that is moving, `
      + 'not for a first look. **In video mode "the screen" means this camera picture and nothing '
      + 'else**: the screen tools (lw_screenshot, lw_ui, lw_ocr, lw_tap and the rest) are refused '
      + 'while that mode is on, so when the user means the display in their hand, switch back to '
      + 'phone mode first (lw_mode {mode:"phone"}) and take the screenshot there. **The user decides whether '
      + 'these frames also come back as one grid** (the same settings section, "one grid per look"): '
      + 'when that is on, the FIRST picture in this result is that grid and the full frames follow it '
      + '— read the grid to see what moved between frames, and go to a full frame when a cell is too '
      + 'small to read. The answer also says how far apart '
      + 'the frames actually came out: a capture costs a couple of hundred ms by itself, so the gap is '
      + 'often longer than the setting asks for, and that measured number is what your timeline is.',
    parameters: {
      frames: {
        type: 'integer',
        description: 'How many frames, only when the user asked for a specific number: otherwise leave'
          + ` it out and the app's setting is used (${SHOT_PARAMS.look.framesDefault} by default,`
          + ` up to ${SHOT_PARAMS.look.framesMax})`,
      },
      intervalMs: {
        type: 'integer',
        description: 'Milliseconds between frames, only when the user asked for a specific spacing:'
          + ` otherwise leave it out and the app's setting is used (${SHOT_PARAMS.look.intervalDefaultMs}`
          + ` by default, ${SHOT_PARAMS.look.intervalMinMs} to ${SHOT_PARAMS.look.intervalMaxMs} is what`
          + ' this device can do)',
      },
      sheet: {
        type: 'boolean',
        description: 'Also lay these frames out as one grid picture, overriding the setting for this'
          + ' one call. The setting already decides it in the app, so only name it when the user asks'
          + ' for the other behaviour right now',
      },
      lens: {
        type: 'string',
        description: '"front" when the user wants to be seen (a selfie, "look at me", "what do I look '
          + 'like"): it is the camera pointing at the user. "back" (the default) for what is in front '
          + 'of the phone. The choice sticks across looks, so name it whenever the user changes which '
          + 'way the phone is pointing',
      },
      note: NOTE,
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          text: { type: 'string', required: true },
          // 每一张的 attachment ref (store 给的那个对象): render 里原样包成图块
          // additionalProperties 必须显式写出来 —— dsh-tools 在 defineTool 那一刻就编译 schema,
          // 少了它整份插件 import 就失败 (手机上表现成"1 entry did not activate")
          images: { type: 'array', items: { type: 'object', additionalProperties: true } },
        },
      },
      render: (_args, value) => {
        const blocks = [{ type: 'text', text: value.text }]
        for (const image of Array.isArray(value.images) ? value.images : []) {
          blocks.push({ type: 'image', attachment: image })
        }
        return blocks
      },
    },
    async execute(args, exec) {
      // 相机是**一台** (需求 3): 别场占着就在这一步停下, 一次都不碰相机。guard 已经拦过一次, 而这里
      // 是真正落账的那一步 —— 再查一次是为了把 guard 与落账之间那一瞬收紧 (两场同时穿过去的窗口)
      const session = sessionIdOf(exec)
      const busy = session === null ? null : await claimCamera(session, normalizeLens(args?.lens))
      if (busy !== null) return { text: cameraBusyRefusal(busy, 'lw_look'), images: [] }
      // **张数的缺省在设置页**, 不在这里: 主人 2026-10-06 加了「视频识别」那一段 (张数 / 间隔 /
      // 清晰度), 而这三个数只有应用那一侧读得到 —— 所以先问一次相机状态。同一次调用里只问一次,
      // 不跨调用缓存: 主人改完设置马上就该生效, 而多一次几百微秒的桥调用值这个价
      const look = await cameraLook()
      const frames = Math.max(
        SHOT_PARAMS.look.framesMin,
        Math.min(SHOT_PARAMS.look.framesMax, args?.frames ?? look.count),
      )
      // 拼不拼网格: 设置页那个开关是缺省, 而模型可以就这一次点名要另一种 (args.sheet)
      const wantGrid = typeof args?.sheet === 'boolean' ? args.sheet : look.sheet
      // 视频模式看的**就是我们自己开的那台摄像头**: 预览画在手机上一块小窗里, 抓帧走 ImageReader
      // (2026-10-05 起不再借虚拟屏与相机应用 —— 那条路要起一个别人的进程、再截屏、再把 PNG 读回来,
      // 而这条路一次 capture 是两百多毫秒, 而且相机就握在自己手里)
      //
      // `lens` 原样交给应用那一侧: 换一头要收一次再开一次 (前后摄是两个设备), 而那件事只有它知道
      const request = { op: 'snapshot', count: frames }
      if (args?.intervalMs !== undefined) request.intervalMs = Number(args.intervalMs)
      if (args?.lens) request.lens = String(args.lens).trim().toLowerCase()
      const shot = await call('camera', request)
      // 续期, 并顺手把量到的镜头那一头写进占用表 (调用里没点名时, 只有应用那一侧知道现在在哪一头)。
      // 这一笔的返回值**不报**: 走到这里帧已经抓完了, 而"这一瞬被别人抢了"那条路要两场同时取景才撞得
      // 上 —— 表上留谁由最后一次写入决定, 而 20 分钟那条超时兜底
      if (session !== null) await claimCamera(session, normalizeLens(shot?.lens) || normalizeLens(args?.lens))
      // **应用那一侧回的 `paths` 是一条逗号连成的字符串, 不是数组** (2026-10-10 主人查出来的那条:
      // 同一秒磁盘上落了四张 cam-*.jpg, 而回执写着 "0 frame(s)")。两种形状都认 —— 少了这一条,
      // 图拍到了、落了盘, 也会被读成"零帧"
      const rawPaths = shot?.paths
      const paths = (Array.isArray(rawPaths) ? rawPaths : String(rawPaths ?? '').split(','))
        .map((path) => String(path).trim())
        .filter((path) => path)
      // **间隔要报量到的那个数**: 一次抓帧本身两三百毫秒, 所以"要了 200ms 而实际每拍 420ms"是常态。
      // 报"要的那个数"就是一句假话, 而模型接下来会拿它当时间轴用
      const gaps = gapText(shot?.offsets, shot?.elapsedMs, paths.length)
      const text = `camera: ${shot?.lens ?? '?'} lens, ${shot?.shot ?? '?'},`
        + ` ${paths.length} frame(s) in ${shot?.elapsedMs ?? '?'}ms`
        + (shot?.intervalMs ? `, asked ${shot.intervalMs}ms apart` : '')
        + (gaps ? `, measured ${gaps}` : '')
      if (!paths.length) return { text, images: [] }
      try {
        // **要拼网格时那一张先交出去** (主人 2026-10-06 要的开关): 一次读图换掉十来次, 而它每一格
        // 都是缩略图 —— 所以那几张原图照旧跟在后面, 格子看不清时还能看原图
        const grid = wantGrid && paths.length > 1 ? await gridOf(paths) : null
        const wide = grid?.buffer ?? null
        // 网格那一张是**手里的字节** (它没落过盘), 后面几张原图是盘上的路径 —— 两种形状一起交出去
        const { images, note } = await attachPictures(
          wide ? [{ data: wide, name: 'look-grid.jpg' }, ...paths] : paths,
        )
        // 这一句**不带 `${text}`**: 下面那行回执已经把它写在最前面了, 带上就是同一句念两遍
        const lead = wide
          ? `\nFirst picture: all ${paths.length} frames as one ${grid.columns}x${grid.rows} grid`
            + ` (${(grid.bytes / 1024).toFixed(1)} KB, in the order they were taken); the ${paths.length}`
            + ' full frames follow it.'
          : ''
        return {
          text: images.length
            ? `${text}${lead}\n${images.length} picture(s) are in this result already.`
            : `${text}${lead}\n(the pictures are at those paths; read them with read_image${note ? `: ${note}` : ''})`,
          images,
        }
      } catch (error) {
        // 附件通路出问题时**退回老路**: 说清路径让模型自己读, 不假装图已经给出去了
        return {
          text: `${text}\n(the pictures could not be attached to this result: ${error?.message ?? error};`
            + ' read them with read_image)',
          images: [],
        }
      }
    },
  }),

  defineTool({
    name: 'lw_screen',
    description:
      'List the screens this device can be asked to act on: the phone\'s own screen, and every '
      + 'virtual screen the host is running. A virtual screen is a separate Android display with '
      + 'its own size, its own apps and its own picture, and it keeps running whether or not the '
      + 'phone is showing it. Call this before any other lw_ tool that acts on a screen: the '
      + 'displayId it reports is what those calls name, and the size it reports is the coordinate '
      + 'space their points are in. Display 0 is the phone\'s own screen, the one a person is '
      + 'holding - it is always there, and nothing can be created or released there. Unless the '
      + 'user asked for that screen in so many words, work on a '
      + 'virtual one instead: it is theirs to keep using, and only a screen of ours can be given '
      + 'the shape an app wants (lw_screen_create, then lw_screen_resize or lw_screen_rotate). Two '
      + 'things here are not yours to undo: a screen you were working '
      + 'on may be missing, and a screen may say its control is paused. A paused screen says so in '
      + 'this list; a missing one does not, but a call naming its id answers with the reason - the '
      + 'user closed it from the phone, or an agent closed it with lw_screen_release, which may '
      + 'have been another session, since screens are shared. In every one of those cases stop, say '
      + 'which screen it is, and ask the user what they want instead of retrying or carrying on '
      + 'with a different screen.',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return formatScreens(await call('screen'))
    },
  }),

  defineTool({
    name: 'lw_screen_create',
    description:
      'Create a virtual screen on the Android device and answer with its displayId. This is the '
      + 'usual way to work: a screen of its own leaves the phone in the user\'s hands, where '
      + 'acting on display 0 would take it away from them. The screen '
      + 'starts empty: nothing is drawn on it until an app is launched onto it with lw_launch, and '
      + 'the apps you launch stay on the display you launched them on - so **give launch=<app> when '
      + 'you already know what the screen is for**, and read the answer either way: an empty screen '
      + 'is black, and a black screenshot is not a failure. Give it a name to tell your '
      + 'screens apart - a name that is taken gets a number appended. Width, height and dpi default '
      + "to the device's own screen, so leave them out to get a screen the size of the phone, and "
      + 'give them when you already know the shape the app wants - a game or any other '
      + 'landscape-only app wants a width larger than its height, and an app that declares an '
      + 'orientation of its own is left in a band in the middle of a screen shaped the other way '
      + 'rather than laid out to fill it. It is '
      + 'not final either way, since lw_screen_resize and lw_screen_rotate change a screen that '
      + "already exists with its apps still running. The phone's own screen is not created here: "
      + 'it is displayId 0 and it is always there.',
    parameters: {
      name: {
        type: 'string',
        description: 'What to call this screen, for example the app you mean to put on it',
      },
      launch: {
        type: 'string',
        description:
          'An app to start on the screen as soon as it exists, by package or by the name a person'
          + ' uses for it (the same names lw_launch takes). **Give this whenever you already know'
          + ' what the screen is for** - a screen nobody has launched anything onto is black, and a'
          + ' black picture reads like a failure even though everything worked',
      },
      width: {
        type: 'integer',
        description: 'Width in pixels; the device\'s own screen when omitted. Larger than height'
          + ' for a landscape app, smaller for a portrait one',
      },
      height: {
        type: 'integer',
        description: 'Height in pixels; the device\'s own screen when omitted',
      },
      dpi: {
        type: 'integer',
        description: 'Pixel density; the device\'s own density when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const created = await call('create', drop(args, 'launch'))
      const app = (args?.launch ?? '').trim()
      if (app === '') {
        // 建了屏而上面什么都没有: 这句话必须说出来, 否则下一次截图是一张全黑的图, 而那看起来
        // 像"截图坏了" (2026-10-05 主人报的就是这个)
        return `${formatCreated(created)}\n\nNothing is running on this screen: a virtual screen starts`
          + ' empty and shows black until an app is launched onto it (lw_launch with this displayId,'
          + ' or lw_screen_create with launch=<app>). A screenshot of it right now is a black'
          + ' picture, which is not a failure - it is an empty screen.'
      }
      const displayId = Number(created?.displayId)
      if (!Number.isFinite(displayId)) {
        return `${formatCreated(created)}\n\nlaunch=${app} was asked for, but the screen came back`
          + ' without a displayId, so nothing was started on it'
      }
      const launched = await call('launch', { displayId, package: app })
      return `${formatCreated(created)}\n\n${formatLaunched(launched)}`
    },
  }),

  defineTool({
    name: 'lw_screen_resize',
    description:
      'Give an existing virtual screen another size, with whatever is running on it left running. '
      + 'A display\'s size is what an app lays itself out for, so this is how a screen made '
      + 'portrait becomes the landscape one a game wants: the picture cannot be rotated into it. '
      + 'Name the width, the height, the dpi, or as many of those as you mean to change - every '
      + 'one you leave out keeps the value the screen has. The apps on the screen are not '
      + 'restarted: the new size reaches them as a configuration change, so one that follows its '
      + 'screen lays itself out again and fills it, while one that declares an orientation of its '
      + 'own - most system apps are portrait-only - keeps that layout and sits in a band in the '
      + 'middle of the screen instead. Take a fresh picture before '
      + 'reading coordinates off this screen, since every one of them has moved. The phone\'s own '
      + 'screen (displayId 0) has no size of ours to change and is refused.',
    parameters: {
      displayId: DISPLAY_ID,
      width: {
        type: 'integer',
        description: 'The screen\'s new width in pixels; it keeps its own when omitted',
      },
      height: {
        type: 'integer',
        description: 'The screen\'s new height in pixels; it keeps its own when omitted',
      },
      dpi: {
        type: 'integer',
        description: 'The screen\'s new pixel density; it keeps its own when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const width = Number(args?.width ?? 0)
      const height = Number(args?.height ?? 0)
      const dpi = Number(args?.dpi ?? 0)
      if (!(width > 0 || height > 0 || dpi > 0)) {
        throw new Error('lw_screen_resize needs at least one of width, height or dpi to change')
      }
      return formatResized(await call('resize', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_screen_rotate',
    description:
      'Turn a virtual screen a quarter turn: exactly lw_screen_resize with the width and the '
      + 'height traded for each other. A screen has a shape rather than an orientation of its '
      + 'own, so the two directions are the same turn and there is no clockwise to name. Reach for '
      + 'this when an app turns out to want the other shape - and take a fresh picture afterwards, '
      + 'because what is on the screen is laid out again at the new size rather than rotated, so '
      + 'all of its coordinates move.',
    parameters: {
      displayId: DISPLAY_ID,
      dpi: {
        type: 'integer',
        description: 'The screen\'s new pixel density; it keeps its own when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatResized(await call('resize', { ...drop(args), swap: true }))
    },
  }),

  defineTool({
    name: 'lw_screen_release',
    description:
      'Give one virtual screen back, which takes everything running on it down with it. '
      + 'Irreversible: any app you launched onto that display has to be launched again on a new '
      + 'one. Name the displayId to release - there is no default, because releasing the wrong '
      + 'screen destroys work nobody asked to lose, and displayId 0 is the phone\'s own screen, '
      + 'which cannot be released at all. Screens are shared with every other session on '
      + 'this host, so a screen you did not create for this task is not yours to close: ask the '
      + 'user first. Whoever calls this is recorded as the closer, so a later call on that id is '
      + 'told an agent closed it rather than the user.',
    parameters: { displayId: DISPLAY_ID, note: NOTE },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatReleased(await call('release', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_ui',
    description:
      'Read what one screen says: every control on it that has text, a description or an '
      + 'action, each with the rectangle it occupies in the screen\'s own pixels. Works on a '
      + 'virtual screen and on the phone\'s own screen (displayId 0) alike, including what the '
      + 'person holding the phone is looking at. Use this instead '
      + 'of lw_screenshot whenever it answers: the text is exact and the coordinates are exact, '
      + 'while coordinates measured on a picture have been scaled and rounded on the way. Press '
      + 'what it lists with lw_tap(text="..."), which needs no coordinate at all. An app that '
      + 'draws its own interface - a game, Flutter, a canvas or a WebGL view - has no such tree, '
      + 'and this answers that it found nothing: fall back to lw_ocr there, which reads the text '
      + 'off the pixels.',
    parameters: { displayId: DISPLAY_ID },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatUi(await call('ui', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_ocr',
    description:
      'Read the text on a screen from its pixels. This is the fallback for a screen whose controls '
      + 'cannot be named: an app that draws its own interface - a game, a Unity or Cocos canvas, a '
      + 'WebGL view - has no control tree, but its text is still on the screen. Every line comes '
      + 'back with the rectangle it occupies in the screen\'s own pixels, so lw_tap(x=..., y=...) '
      + 'can press it; the centre is given too. It reads Chinese and English, runs on the device '
      + 'with no network and no cloud, and unlike lw_screenshot it does **not** scale the picture '
      + 'down, so small text stays readable. Prefer lw_ui whenever that answers: it is exact and '
      + 'free, while this is a reading of a picture, and it can mistake an icon for a character '
      + '(those come back with a low score).',
    parameters: { displayId: DISPLAY_ID, note: NOTE },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return said(formatOcr(await call('ocr', drop(args, 'note'))), args?.note)
    },
  }),

  defineTool({
    name: 'lw_tap',
    description:
      'Press something on a screen, either by name or at a point - on a virtual screen, or on the '
      + "phone's own screen with displayId 0. By name is the reliable "
      + 'way: pass text exactly as lw_ui reported it and the device finds that control and presses '
      + 'it, reporting what it pressed. If the name reaches more than one control nothing is '
      + 'pressed and the candidates come back, so the next attempt can be more specific. A point '
      + 'is for when there is no name to use - a canvas, or a place you measured on a screenshot - '
      + 'and it is in the screen\'s own pixels, with 0,0 at its top left. Either way the call '
      + 'returns once the device has delivered the press, so a screenshot or an lw_ui taken after '
      + 'it shows the result. Pass hold to make it a long press, which is how a context menu, a '
      + 'selection or a drag handle is reached; by name that uses the control\'s own long click '
      + 'action, and a control without one is pressed with a held finger instead.',
    parameters: {
      displayId: DISPLAY_ID,
      text: {
        type: 'string',
        description: 'Name of the control to press: its text if it has one, otherwise the desc= '
          + 'value lw_ui reported for it',
      },
      x: { type: 'number', description: 'Horizontal position of the point, instead of a text' },
      y: { type: 'number', description: 'Vertical position of the point, instead of a text' },
      source: {
        type: 'string',
        enum: ['auto', 'a11y', 'ocr'],
        description: 'Where to look the name up. auto (the default) asks the control tree first '
          + 'and, only if that tree has no such name, reads the screen\'s pixels with lw_ocr - '
          + 'which is what a self-drawn screen needs. a11y is the tree alone, ocr is the pixels '
          + 'alone. An ambiguous name is never followed by a guess, whichever way it was found',
      },
      hold: holdParameter(
        'A hold past a long press is a press kept down, which is what a voice message being '
        + 'recorded or a drag that has to be started by holding expects.',
      ),
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const named = args?.text !== undefined
      const placed = args?.x !== undefined && args?.y !== undefined
      if (named && placed) {
        throw new Error('lw_tap takes either a text or a point, not both')
      }
      if (!named && !placed) {
        throw new Error('lw_tap needs either a text to press by name or both x and y')
      }
      const held = holdMs(args?.hold)
      if (named) {
        const source = args.source ?? 'auto'
        const asked = { displayId: args.displayId, text: args.text, holdMs: held }
        if (source === 'ocr') {
          return said(await tapByReading(args.displayId, args.text, held), args.note)
        }
        const tree = await call('tapText', asked)
        // ambiguous 也直接交回去: 树里说有两个同名控件的时候, 读像素只会是第三个猜测
        // 这里看的是 clicked 而不是 outcome: 无障碍动作被拒之后桥会用一根按住的手指兜底,
        // 那一下 outcome 仍是 unclicked 而 clicked 已经是真, 再退到读屏就会按第二次
        if (source === 'a11y' || tree.clicked === true || tree.outcome === 'ambiguous') {
          return formatNamedTap(tree, args.note)
        }
        const reading = await tapByReading(args.displayId, args.text, held)
        return said(`${namedTapReport(tree)}\n${reading}`, args.note)
      }
      return formatGesture('tapped', await call('tap', {
        displayId: args.displayId,
        x: args.x,
        y: args.y,
        holdMs: held,
      }), args.note)
    },
  }),

  defineTool({
    name: 'lw_key',
    description:
      'Press a key on a screen: BACK, HOME, APP_SWITCH, ENTER, DEL, the volume keys, the arrows, and '
      + 'anything else Android has a keycode for. This is how a button the platform itself handles '
      + 'is pressed - BACK, HOME and the volume keys are on no screen\'s tree, so lw_ui and lw_tap '
      + 'cannot reach them - and it is the only way: the input command is refused for this app\'s '
      + 'uid, exactly like am, so a shell cannot do it either. Name the key the way Android names '
      + 'it, with or without the KEYCODE_ prefix (BACK, keycode_home, APP_SWITCH, DPAD_DOWN, '
      + 'MOVE_END), or pass its number as code. A name Android does not have, or a number it does '
      + 'not define, is refused and the answer says what to use instead - so do not guess a number, '
      + 'the wrong one is a different key rather than an error. Pass hold to keep the key down: a '
      + 'long BACK force-stops the app in front, a long HOME calls the assistant, a long press on a '
      + 'power key opens its menu. Works on a virtual screen and on the '
      + 'phone\'s own screen (displayId 0). HOME and the power and sleep keys are refused on a screen '
      + 'of this host has no launcher, so they belong on displayId 0.',
    parameters: {
      displayId: DISPLAY_ID,
      key: {
        type: 'string',
        description: 'The Android name of the key, for example BACK, HOME, APP_SWITCH, ENTER, DPAD_DOWN',
      },
      code: {
        type: 'integer',
        description: 'The keycode number instead of a name, for example 4 for BACK - exactly one of key and code',
      },
      hold: holdParameter(
        'Holding a key down does not repeat it - send the key again for that - so this is for what '
        + 'the hold itself means to the platform.',
      ),
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args?.key !== undefined && args?.code !== undefined) {
        throw new Error('lw_key takes either a key name or a code, not both')
      }
      if (args?.key === undefined && args?.code === undefined) {
        throw new Error('lw_key needs the key to press, by name (key) or by number (code)')
      }
      const held = holdMs(args?.hold)
      return formatPressed(await call('key', {
        displayId: args.displayId,
        key: args.key,
        code: args.code,
        holdMs: held,
      }), args.note)
    },
  }),

  defineTool({
    name: 'lw_swipe',
    description:
      'Drag a finger across a screen, as one continuous gesture: down at the first point, '
      + 'moved across, up at the second - on a virtual screen, or on the phone\'s own screen with '
      + 'displayId 0. Use it to scroll, to dismiss, or to drag something. The '
      + 'points are in the screen\'s own pixels and the duration decides the speed, which is what '
      + 'tells a scroll apart from a fling - a short duration over a long distance flings.',
    parameters: {
      displayId: DISPLAY_ID,
      fromX: { type: 'number', required: true, description: 'Horizontal position the finger starts at' },
      fromY: { type: 'number', required: true, description: 'Vertical position the finger starts at' },
      toX: { type: 'number', required: true, description: 'Horizontal position the finger ends at' },
      toY: { type: 'number', required: true, description: 'Vertical position the finger ends at' },
      durationMs: {
        type: 'integer',
        description: `How long the drag takes in milliseconds, ${DEFAULT_SWIPE_MS} by default`,
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatGesture('swiped', await call('swipe', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_type',
    description:
      'Type text into a screen: search, fill a form, write a message. The device finds the field '
      + 'itself - the one with focus, or the only editable thing on the screen - and writes the '
      + 'text into it, so any character works, Chinese included. By default it adds at the cursor; '
      + 'pass replace to overwrite what the field already says instead. If the screen reports no '
      + 'field at all (a game, a canvas), the text goes in as key presses, which only produces what '
      + 'a keyboard has - letters, digits, punctuation - and lands wherever focus is. The answer '
      + 'says which of the two happened and what the field reads afterwards, so read it rather than '
      + 'assuming. Give x and y to put the text into the field **at that point** - the tool presses '
      + 'it first, which is what decides where the text lands on a screen with more than one field. '
      + 'Typing does not submit anything: press the app\'s own button, or ENTER with '
      + 'lw_key, if it needs that.',
    parameters: {
      displayId: DISPLAY_ID,
      text: {
        type: 'string',
        required: true,
        description: 'The text to type, exactly as it should appear in the field',
      },
      replace: {
        type: 'boolean',
        description: 'Overwrite what the field already says, instead of adding to it at the cursor',
      },
      x: { type: 'integer', description: 'Press this point first, then type into the field there' },
      y: { type: 'integer', description: 'Press this point first, then type into the field there' },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const text = typeof args?.text === 'string' ? args.text : ''
      if (text === '') throw new Error('lw_type needs the text to type')
      return formatTyped(await call('type', drop(args, 'note')), args.note)
    },
  }),

  defineTool({
    name: 'lw_apps',
    description:
      'List the apps this device can start: the name a person sees, and the package to start it '
      + 'with. Pass query to filter by either one - that is how you find a single app rather than '
      + 'reading the whole list - and pass user to ask about a user other than the person holding '
      + 'the phone. A cloned app (双开, a parallel space) is the same package in another user rather '
      + 'than a second package, so it appears in that user\'s list. This is the listing a shell '
      + 'cannot give you: pm refuses an app uid on this device, and with an explicit --user it '
      + 'answers with Luwi alone, so call this instead of running pm or cmd package.',
    parameters: {
      user: {
        type: 'integer',
        description: 'The Android user to list for; the person holding the phone (0) when left out.'
          + ' A cloned app lives in another user, and lw_probe lists the users this device has',
      },
      query: {
        type: 'string',
        description: 'Only the apps whose name or package contains this, for example 设置 or bili',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatApps(await call('apps', drop(args, 'note')))
    },
  }),

  defineTool({
    name: 'lw_launch',
    description:
      'Start an app on a screen and wait until it is up: on a virtual screen, or on the phone\'s '
      + 'own screen with displayId 0, which is what puts it in front of the person holding the '
      + 'phone. Name the app by package, or by the name a person uses for it ("设置") when that '
      + 'name reaches exactly one app - its '
      + 'launcher activity is what starts, and the answer says which one that turned out to be - '
      + 'or name an exact component like com.android.settings/.Settings. An app that is cloned - '
      + '双开, a parallel space - is the same package in another Android user rather than a second '
      + 'package, so it is started by naming that user, and lw_probe lists the ones this device '
      + 'has. Call lw_apps when you do not know the package. The app stays on that '
      + 'display until the screen is released, and the phone does not have to be showing it. For a '
      + 'game or another app that only knows one orientation, give the screen that shape first - '
      + 'lw_screen_create takes a width and a height - because an app that declares an orientation '
      + 'of its own keeps it and sits in a band in the middle of a screen shaped the other way. Use '
      + 'this rather than a shell: the am command refuses an app uid on this device, so a launch '
      + 'attempted from a shell fails. Call lw_ui or lw_screenshot after it to see the result.',
    parameters: {
      displayId: DISPLAY_ID,
      package: {
        type: 'string',
        description: 'Package to start, for example com.android.settings, or the name a person'
          + ' uses for the app when it reaches exactly one - lw_apps lists both',
      },
      component: {
        type: 'string',
        description: 'Exact component to start instead of a package, for example com.android.settings/.Settings',
      },
      user: {
        type: 'integer',
        description: 'The Android user to start it for; the person holding the phone (0) when left'
          + ' out. A cloned app - 双开, a parallel space - is the same package in another user,'
          + ' with its own data, and lw_probe lists the users this device has',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const packageName = (args?.package ?? '').trim()
      const component = (args?.component ?? '').trim()
      if ((packageName === '') === (component === '')) {
        throw new Error('lw_launch takes either a package or a component, and exactly one of them')
      }
      return formatLaunched(await call('launch', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_screenshot',
    description:
      'Take a picture of one screen and leave it as a PNG file in the workspace: a virtual screen, '
      + "or the phone's own screen with displayId 0, which is exactly what a person would be "
      + 'looking at. Works '
      + 'whether or not the phone is showing that screen, so it is how you see what is on a screen '
      + 'you are driving: read the file back with the read_image tool to look at it. Prefer lw_ui '
      + 'when it answers - it gives text and exact coordinates, and this gives neither - and use '
      + 'this for what has no readable tree: games, Flutter, canvases, or to see the picture as a '
      + 'person would. **The picture can come back much smaller than the screen**: the phone caps a '
      + 'screenshot at a pixel budget and then shrinks it further to fit a byte budget, so a '
      + '1080x2400 screen can arrive as 536x1192 - or smaller when there is a lot on it - and small '
      + 'text is then unreadable. **To read a screen, use the two reading tools rather than a '
      + 'picture of it**: lw_ui reads the accessibility tree, which gives every control\'s exact '
      + 'text and position, and lw_ocr runs the on-device OCR over the screen\'s own pixels without '
      + 'scaling them - so both are exact where a screenshot of the same screen is a small copy. '
      + 'Take this when you want to see the screen as a person would, or when neither of those two '
      + 'answers. When a scaled picture is what you have, the answer says what to multiply '
      + 'coordinates measured on it by to get coordinates for lw_tap and lw_swipe. The file is '
      + 'overwritten by the next capture of the same screen, unless you ask for more than one at a'
      + ' time.',
    parameters: {
      displayId: DISPLAY_ID,
      // 只要屏幕的一块: 文字太小看不清时, 把那一块按它自己的像素交给模型, 比整屏缩一遍有用
      x: { type: 'integer', description: 'Left edge of the part to capture, for a partial picture' },
      y: { type: 'integer', description: 'Top edge of the part to capture, for a partial picture' },
      width: { type: 'integer', description: 'Width of the part to capture, with x, y and height' },
      height: { type: 'integer', description: 'Height of the part to capture, with x, y and width' },
      count: {
        type: 'integer',
        description: 'How many pictures in a row, '
          + `${SHOT_PARAMS.shot.countMin} to ${SHOT_PARAMS.shot.countMax}, for something that is`
          + ' moving. Each one is a separate file; leave it out for a single picture. That many'
          + ' pictures are that many images in the conversation, so ask for the grid too unless you'
          + ' need to read each one',
      },
      intervalMs: {
        type: 'integer',
        description: 'How far apart those pictures are, in milliseconds,'
          + ` ${SHOT_PARAMS.shot.intervalMinMs} to ${SHOT_PARAMS.shot.intervalMaxMs}`
          + ` (${SHOT_PARAMS.shot.intervalDefaultMs} by default). It is wall-clock time from one`
          + ' capture to the next, and one capture takes a couple of hundred milliseconds here, so'
          + ' anything faster is impossible: the answer says when each picture was actually taken'
          + ' rather than what was asked for',
      },
      sheet: {
        type: 'boolean',
        description: 'Also lay the pictures out as ONE picture - a grid in the order they were'
          + ' taken. Reading one grid costs a twelfth of reading twelve pictures, so this is how to'
          + ' see where the screen went between them. It is not for reading small text or for'
          + ' measuring positions: every cell is a small copy, and the separate files are still'
          + ' there when a cell is not enough',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const answer = await call('screenshot', drop(args))
      return formatScreenshot(answer) + (await emptyScreenNote(args, answer))
    },
  }),

  // ---- 1.0.2 加的那一批: 通知与震动、剪贴板、传输、设备与系统信息、输入增强 ----
  //
  // 它们的共同点是不需要特权: 应用那一侧用 Context 自己做, 走桥只是为了把结果按同一套协议送回来。
  // 所以每个工具就是一行 `simpleTool` —— 参数表照实写, 说明里说清"什么情况下它做不到"

  simpleTool(
    'lw_notify',
    'Post a notification on the phone, optionally vibrating or coming up as a banner. Use it to tell'
    + ' the person holding the device something they should see outside this app - a job finished, a'
    + ' decision is waiting. The same notification id is reused, so a second call replaces the first'
    + ' rather than stacking; tapping it brings Luwi to the front. Needs the notification'
    + ' permission, and says so if it is missing. **banner is a permission of its own**: since'
    + ' Android 14 a full-screen intent has to be granted by hand, so the answer says whether the'
    + ' banner will actually appear rather than assuming it. The channel is read back too - it is'
    + ' created at high importance, but only the user can change that afterwards, and a channel the'
    + ' user has turned down does not banner.',
    'notify',
    {
      title: { type: 'string', required: true, description: 'The notification title, one short line' },
      text: { type: 'string', required: true, description: 'The notification body' },
      vibrateMs: {
        type: 'integer',
        description: 'Vibrate for this many milliseconds as well (up to 3000). Needs the vibration'
          + ' permission, which the device grants on its own at install',
      },
      banner: {
        type: 'boolean',
        description: 'Also give it a full-screen intent, so it comes up over whatever is on the'
          + ' screen (and over the lock screen). For something worth interrupting a person for;'
          + ' whether it is allowed is a separate switch the user owns',
      },
    },
  ),

  simpleTool(
    'lw_notifications',
    'Read the notification shade, and dismiss what is in it. This is how the agent finds out what'
    + ' the phone has been telling its owner: a message that arrived, a download that finished, a'
    + ' two-factor code. op=list gives one line per notification, newest first, each starting with'
    + ' the key that op=cancel takes - it names the app, the importance, whether it can be cleared'
    + ' at all, and the title and text. cancelling takes one key, or a package to clear everything'
    + ' that app posted. **Needs 通知使用权**, which no app can request at runtime: the answer says'
    + ' so, and the switch for it is in Luwi\'s own Settings -> 通知. Ongoing notifications (a'
    + ' music player, a call) are left alone and reported as such rather than as a failure.',
    'notifications',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list what is in the shade, or cancel',
        enum: ['list', 'cancel'],
      },
      key: {
        type: 'string',
        description: 'For op=cancel: the key of one notification, exactly as op=list printed it',
      },
      package: {
        type: 'string',
        description: 'For op=cancel: clear every clearable notification from this app instead of'
          + ' naming one key',
      },
    },
  ),

  simpleTool(
    'lw_vibrate',
    'Vibrate the phone for a moment, as a nudge with no notification at all.',
    'vibrate',
    { ms: { type: 'integer', description: 'How long, in milliseconds. Default 200, at most 3000' } },
  ),

  simpleTool(
    'lw_clipboard',
    'Read or write the system clipboard. Writing works at any time and is the way to hand a long'
    + ' piece of text to another app without typing it. Reading only works while Luwi is the'
    + ' foreground app - that is an Android 10 rule, not this app\'s - and the answer says so when'
    + ' it cannot.',
    'clipboard',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get to read the clipboard, set to replace it with text',
        enum: ['get', 'set'],
      },
      text: { type: 'string', description: 'What to write, required when op is set' },
    },
  ),

  simpleTool(
    'lw_share',
    'Hand text or a file to the system share sheet, so the person picks which app takes it. This is'
    + ' how a file the agent wrote in the work area gets into a chat app or an editor. The chooser'
    + ' opening is all this reports: which app takes it is the user\'s pick.',
    'share',
    {
      text: { type: 'string', description: 'Text to share, or the message to go beside a file' },
      path: { type: 'string', description: 'A file to share, as an absolute path' },
      mime: { type: 'string', description: 'The MIME type, when the extension does not say it' },
    },
  ),

  simpleTool(
    'lw_open_file',
    'Open a file with whichever app on the device handles its type. Use it to show the user a'
    + ' picture, a PDF or a document the agent produced.',
    'openFile',
    {
      path: { type: 'string', required: true, description: 'The file to open, as an absolute path' },
      mime: { type: 'string', description: 'The MIME type, when the extension does not say it' },
    },
  ),

  simpleTool(
    'lw_download',
    'Ask the system to download a URL into the shared Downloads folder, which is where a file has'
    + ' to be for the user to find it with a file manager. The system downloads it on its own'
    + ' schedule, so this reports that it was queued rather than that it finished.',
    'download',
    {
      url: { type: 'string', required: true, description: 'The http or https URL to download' },
      to: { type: 'string', description: 'The file name to save it as, taken from the URL by default' },
    },
  ),

  simpleTool(
    'lw_device',
    'What this device is: model, Android version, ABI, screen size and density, locale, uptime, the'
    + ' privileged channel\'s state and this app\'s own version. Call it once at the start of a'
    + ' session rather than guessing.',
    'device',
  ),

  simpleTool(
    'lw_battery',
    'Battery level, whether it is charging and from what, temperature, voltage, current draw and the'
    + ' screen\'s own state. The screen state matters before anything else: a screenshot of a'
    + ' sleeping device is the last frame, not what is on it.',
    'battery',
  ),

  simpleTool(
    'lw_storage',
    'How full the storage and the memory are: data, shared storage and system partitions, plus total'
    + ' and available memory and this app\'s own heap.',
    'storage',
  ),

  simpleTool(
    'lw_running',
    'How loaded the device is: core count, the load average from the kernel, the memory situation.'
    + ' Worth checking before starting anything heavy.',
    'running',
  ),

  simpleTool(
    'lw_volume',
    'Read or set the device\'s volume: media, ring, notification, alarm and call streams, plus the'
    + ' ringer mode (normal, vibrate, silent). A write is read back, because this device has been'
    + ' seen accepting a write and keeping the old value.',
    'volume',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get or set for one stream\'s level, adjust for one step, mode for the ringer',
        enum: ['get', 'set', 'adjust', 'mode'],
      },
      system: {
        type: 'string',
        description: 'Which stream: media, ring, notification, alarm or call. Default media',
        enum: ['media', 'ring', 'notification', 'alarm', 'call'],
      },
      level: { type: 'integer', description: 'The level to set, for op=set' },
      direction: {
        type: 'string',
        description: 'up, down or mute, for op=adjust',
        enum: ['up', 'down', 'mute'],
      },
      mode: {
        type: 'string',
        description: 'normal, vibrate or silent, for op=mode',
        enum: ['normal', 'vibrate', 'silent'],
      },
    },
  ),

  simpleTool(
    'lw_media',
    'Send a media transport key: play, pause, playPause, next, previous or stop. It goes to whatever'
    + ' the device considers its current media session.',
    'media',
    {
      op: {
        type: 'string',
        required: true,
        description: 'The transport control to send',
        enum: ['play', 'pause', 'playPause', 'next', 'previous', 'stop'],
      },
    },
  ),

  simpleTool(
    'lw_net',
    'Read the network state (active transport, WiFi name, IP addresses, airplane mode), or change'
    + ' one of the radios: airplane, data, wifi, bluetooth. Reading is free; changing needs the'
    + ' privileged channel and may be refused by the ROM, which the answer says.',
    'net',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get to read, or airplane / data / wifi / bluetooth to turn one on or off',
        enum: ['get', 'airplane', 'data', 'wifi', 'bluetooth'],
      },
      on: { type: 'boolean', description: 'For the changing ops: true to turn it on, false to turn it off' },
    },
  ),

  simpleTool(
    'lw_system',
    'Read or write a few system settings: screen brightness, screen timeout, auto-rotate and font'
    + ' scale. Reading also covers bluetooth, NFC and airplane mode. The protected ones need the'
    + ' WRITE_SETTINGS grant, which is given from a system settings page; a write is read back and'
    + ' the answer says when the device did not keep it.',
    'system',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get to read one key (or all of them), set to write one',
        enum: ['get', 'set'],
      },
      key: {
        type: 'string',
        description: 'Which setting: brightness, screen_off_timeout, accelerometer_rotation, font_scale,'
          + ' bluetooth, nfc or airplane_mode',
      },
      value: { type: 'integer', description: 'The value to write, for op=set' },
    },
  ),

  // Linux 沙盒 (2.7.7): 一台真正能装包 / 能编译的 Alpine, 命令一条一条跑, /work 就是会话工作区
  defineTool({
    name: 'lw_linux',
    description:
      'Run commands inside a small Alpine Linux (musl) userland this app carries with it, on this'
      + ' device. It exists because the phone itself has neither a package manager nor a compiler:'
      + ' inside it, `apk add python3`, `apk add build-base` or `apk add git` reach the internet and'
      + ' install normally, and `cc`/`python3` then work on anything under /work. **op=run** runs one'
      + ' shell command and answers with its output; /work is this session\'s workspace (the same'
      + ' directory the file and shell tools work in), /work is the only part of the phone it can'
      + ' see, and the network is up. **op=install** unpacks the Alpine base the app ships (it'
      + ' replaces the whole root filesystem, so ask the user before re-running it once things are'
      + ' installed inside); op=status reports whether it is installed, how large it is and whether'
      + ' anything is running; op=reset deletes it and everything installed into it (destructive -'
      + ' only when the user asks for that). Things to know: each call is one shot - the working'
      + ' directory, environment and any background process do not survive it, so chain what belongs'
      + ' together with `&&`, or write a script under /work and run that; `apk add` can take several'
      + ' minutes on a slow mirror, and timeoutMs (default 120000, up to 600000) bounds one call.'
      + ' It is not a security sandbox: output has to be treated like output from any shell.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'run a command, install (or reinstall) the bundled Alpine, read status, or reset',
        enum: ['run', 'install', 'status', 'reset'],
      },
      command: {
        type: 'string',
        description: 'For op=run: the shell command, as it would be typed into `sh -lc` inside the sandbox',
      },
      timeoutMs: {
        type: 'integer',
        description: 'For op=run: how long that one command may take. Default 120000, at most 600000',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args, exec) {
      // 会话工作区是插件这一侧补上的: 应用那一侧只认"一个真目录", 而"哪一场会话在哪工作"这件事
      // 只有 dsh 的会话对象说了算 (见 sessionCwdOf)
      const work = sessionCwdOf(exec)
      return answerOf(await call('linux', drop({ ...args, work: work ?? undefined })))
    },
  }),

  simpleTool(
    'lw_sensor',
    'List the device\'s sensors, or read one of them once. The value is a single sample, so a sensor'
    + ' that is not moving reports its resting value.',
    'sensor',
    {
      name: { type: 'string', description: 'A sensor name or type; omit it to list them all' },
      timeoutMs: { type: 'integer', description: 'How long to wait for a sample. Default 1500' },
    },
  ),

  simpleTool(
    'lw_location',
    'Where the device is, from its last known fix, or - with fresh=true - by waiting for a new one.'
    + ' Needs the location permission; indoors a fresh fix often does not arrive, and the answer'
    + ' says that rather than guessing.',
    'location',
    {
      fresh: { type: 'boolean', description: 'Wait for a new fix instead of reading the last known one' },
      timeoutMs: { type: 'integer', description: 'How long to wait for a fresh fix. Default 10000' },
    },
  ),

  simpleTool(
    'lw_permissions',
    'Which permissions this app has right now, per capability, and how each missing one is granted.'
    + ' Call it when a tool says it is missing a permission, or before relying on the camera, the'
    + ' location, notifications or the clipboard.',
    'permissions',
  ),

  simpleTool(
    'lw_power',
    'The screen and the lock: state to see, on to wake it, off to put it to sleep, lock to lock it.'
    + ' This is the way out of the sleeping device trap - a screenshot of a sleeping device is the'
    + ' last frame, and injected touches do not wake it.',
    'power',
    {
      op: {
        type: 'string',
        required: true,
        description: 'state, on, off or lock',
        enum: ['state', 'on', 'off', 'lock'],
      },
    },
  ),

  // 锁屏那一条 (2.5.0 批次 5): 点亮屏幕与"按主人自己录的那一条解锁"都在这一条上
  simpleTool(
    'lw_lock',
    'The lock screen: what state it is in, and the recorded walk-through that gets a wake word past'
    + ' it. status reads whether the screen is on, whether the phone is locked, how many steps have'
    + ' been recorded, how many attempts are left before automatic unlock switches itself off, and'
    + ' whether the privileged channel is up. unlock lights the screen and walks the recording'
    + ' through once, then says how far it got. steps lists what was recorded - never the password,'
    + ' which lives in an encrypted slot and is only read while unlocking. clear forgets the'
    + ' recording, its plain copy in the workspace and the encrypted password. record starts'
    + ' (action=start) or stops (action=stop) a walk-through: with password given, everything from'
    + ' the first tap on is dropped from the recording and that password is sent instead, so never'
    + ' guess one - only pass what the user typed into the phone. Recording locks the screen, so'
    + ' the user is the one who walks it, and it stops by itself once the phone is open. import'
    + ' replaces that sequence with a script: pass the script itself, or a path to a file holding'
    + ' it. The format is one step per line - key ENTER, tap 0.3 0.7, swipe 0.5 0.85 0.5 0.2 700,'
    + ' stroke with x y points, text (where a password is typed, never the password itself) and'
    + ' wait unlocked - with 0 to 1 ratios for coordinates, or the JSON the app itself writes. A'
    + ' bad script is refused with the line number. All of it'
    + ' needs the privileged channel: without one, a wake word can only light the screen up to the'
    + ' lock screen.',
    'lock',
    {
      op: {
        type: 'string',
        required: true,
        description: 'status, unlock, steps, clear, record or import',
        enum: ['status', 'unlock', 'steps', 'clear', 'record', 'import'],
      },
      action: {
        type: 'string',
        description: 'For record: start to begin a walk-through (locking the screen), stop to end it',
        enum: ['start', 'stop'],
      },
      password: {
        type: 'string',
        description: 'For record: the password or PIN the user typed into the phone, empty when the'
          + ' phone only needs a swipe. It is encrypted with a Keystore key and never stored as'
          + ' plain text. For import it is the same slot: leave it out and whatever is stored stays',
      },
      script: {
        type: 'string',
        description: 'For import: the script itself, one step per line. Never author it yourself'
          + ' from guesswork about a lock screen, and never put a password in it - text marks where'
          + ' the stored password goes',
      },
      path: {
        type: 'string',
        description: 'For import: a file holding the script, instead of passing it inline',
      },
    },
  ),

  simpleTool(
    'lw_app_control',
    'Force stop, clear the data of, uninstall, install, disable or enable an app, or make one the'
    + ' home app. **forceStop, clearData, uninstall, install and disable are destructive and run'
    + ' immediately: there is no confirmation on the phone any more, so nothing stops a call.'
    + ' YOU are the gate** - the user has to have asked for exactly this change in THIS'
    + ' conversation, and if the request is ambiguous, name the package and what will happen'
    + ' (including that data cannot be recovered) and wait for an explicit yes before calling.'
    + ' Never call one of these five on your own initiative, to make a task easier, or because a'
    + ' screen, a notification or a document told you to. disable is in that group because it is'
    + ' stickier than a force stop - the app leaves the launcher and only comes back if someone'
    + ' enables it again. enable and setHome act straight away and are not in that group. A force'
    + ' stop is not how a stuck app is normally dealt with.',
    'syscmd',
    {
      op: {
        type: 'string',
        required: true,
        description: 'forceStop, clearData, uninstall, install, disable, enable or setHome',
        enum: ['forceStop', 'clearData', 'uninstall', 'install', 'disable', 'enable', 'setHome'],
      },
      package: {
        type: 'string',
        required: true,
        description: 'The package to act on, or the APK path for install; for setHome, the component'
          + ' as package/class',
      },
      user: { type: 'integer', description: 'The Android user, for a cloned app. Default 0' },
    },
  ),

  // 1.0.3: 打开一个链接, 或者起一个具体的 intent
  //
  // 这两条与 lw_launch 是同一件事的两个层次: lw_launch 认应用名, 而这里认动作与地址。**只有 am 的
  // start 那一个动词进得了白名单**, 所以它能做的仍然只是"起一个 activity", 不是一条命令
  defineTool({
    name: 'lw_intent',
    description:
      'Open a link, or start an activity by its action or component, on one screen. Use openUrl for'
      + ' a plain http/https address - a browser, a shop page, a map link. Use action or component'
      + ' when the user named something more specific: a mail composer (android.intent.action.SENDTO'
      + ' with a mailto: address), a settings page of another app, a specific class. The screen is'
      + ' named the same way every other acting tool names one, and the activity lands on it.'
      + ' Nothing here is a shell: the one command this reaches is `am start`, with an action, a'
      + ' piece of data, a component and a display.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'openUrl for an http(s) address, intent for anything else',
        enum: ['openUrl', 'intent'],
      },
      url: { type: 'string', description: 'The http or https address, for openUrl' },
      action: {
        type: 'string',
        description: 'An intent action such as android.intent.action.SENDTO, for intent',
      },
      data: {
        type: 'string',
        description: 'What the action is about - a mailto: address, a content:// uri, for intent',
      },
      component: {
        type: 'string',
        description: 'A class to start, as package/class, for intent',
      },
      displayId: DISPLAY_ID,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const op = args?.op === 'openUrl' ? 'openUrl' : 'intent'
      const target = op === 'openUrl' ? args?.url : args?.component || args?.action
      if (typeof target !== 'string' || target.length === 0) {
        return op === 'openUrl'
          ? 'openUrl needs a url: give the http or https address to open'
          : 'intent needs an action or a component: on its own, a display is not something to start'
      }
      return answerOf(await call('syscmd', {
        op,
        // openUrl 把地址放在 package 上 (那一侧的 `-d` 就是它), intent 认的是 action / data / component
        package: op === 'openUrl' ? args?.url : undefined,
        component: op === 'intent' ? args?.component : undefined,
        action: args?.action,
        data: args?.data,
        displayId: args?.displayId,
        note: args?.note,
      }))
    },
  }),

  // 键与打字的补充: 一次按住几个键 (Ctrl+A 那种), 以及把文本放进指定的字段
  defineTool({
    name: 'lw_key_combo',
    description:
      'Hold two or more keys down together and let go - Ctrl+A, Shift+Tab, Alt+Tab, Ctrl+Shift+T.'
      + ' Use it for the shortcuts a single key cannot express: select all, the app switcher, a new'
      + ' tab. Send the keys in the order they should be held, modifiers first. Names are the'
      + ' Android ones without the KEYCODE_ prefix (CTRL_LEFT, SHIFT_LEFT, ALT_LEFT, A, TAB, ...),'
      + ' and a name Android does not have is refused instead of being pressed as some other key.',
    parameters: {
      keys: {
        type: 'array',
        required: true,
        description: 'The keys to hold, modifiers first, at least two, at most four',
        items: { type: 'string' },
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const keys = Array.isArray(args?.keys) ? args.keys : []
      return answerOf(await call('syscmd', { op: 'keyCombo', keys, note: args?.note }))
    },
  }),

  // 让设备别睡
  simpleTool(
    'lw_keep_awake',
    'Hold a wake lock so the device does not sleep while something takes a while: a long download,'
    + ' a batch of screenshots, a step that must not be interrupted. **The screen may still go off**'
    + ' - this keeps the CPU awake, not the display - and a screen that has gone off hands back its'
    + ' last frame without saying so, which is why lw_screenshot and lw_ocr report the screen state'
    + ' too. **It does not let go on its own**: nothing here times out, so call it again with off'
    + ' when the work is done, or the device stays awake until something else stops it. Ask with op'
    + ' status to see whether it is held and for how long.',
    'keepAwake',
    {
      op: {
        type: 'string',
        description: 'on to hold it, off to let go, status to ask. Default on',
        enum: ['on', 'off', 'status'],
      },
      note: NOTE,
    },
  ),

  simpleTool(
    'lw_wait_for',
    'Wait until a screen shows something by name, then answer where it is. Use it after an action'
    + ' that makes a screen change: reading the tree immediately afterwards reads the old screen.'
    + ' The match is the same one lw_tap uses, so a name this finds is a name that can be pressed.',
    'waitFor',
    {
      displayId: DISPLAY_ID,
      text: { type: 'string', required: true, description: 'The text or description to wait for' },
      timeoutMs: {
        type: 'integer',
        description: 'How long to wait before giving up. Default 15000, at most 90000',
      },
    },
  ),

  simpleTool(
    'lw_ui_dump',
    'Write the whole view hierarchy of a screen to a file in the work area, layout containers'
    + ' included, and answer with the first lines of it. lw_ui keeps only the controls worth naming;'
    + ' this is for when the question is why the screen is laid out the way it is.',
    'uiDump',
    {
      displayId: DISPLAY_ID,
      to: { type: 'string', description: 'Where to write it; the work area by default' },
      limit: { type: 'integer', description: 'At most this many nodes. Default 4000' },
    },
  ),

  simpleTool(
    'lw_gesture',
    'Draw a multi-finger gesture: one path per finger, all moving together. Use it for the shapes'
    + ' neither a tap nor a straight swipe can make - two fingers at once, a curve, a drag that has'
    + ' to go round something. A point per path is not a gesture: paths need at least two.',
    'gesture',
    {
      displayId: DISPLAY_ID,
      paths: {
        type: 'array',
        required: true,
        description: 'One object per finger: { "points": [ { "x": 1, "y": 2 }, ... ] }, in the'
          + ' screen\'s own pixels',
        items: {
          type: 'object',
          // 对象节点必须自己声明开闭: dsh 的作者侧 schema 把 additionalProperties 定成必填, 少一个
          // 就是模块求值期抛错, 整包 UNSUPPORTED_SCHEMA, 会话里一个 lw_ 工具都不剩
          additionalProperties: false,
          properties: {
            points: {
              type: 'array',
              items: {
                type: 'object',
                additionalProperties: false,
                properties: { x: { type: 'number' }, y: { type: 'number' } },
              },
            },
          },
        },
      },
      durationMs: { type: 'integer', description: 'How long the whole gesture takes. Default 300' },
    },
  ),

  simpleTool(
    'lw_pinch',
    'Pinch in or out around a point: two fingers starting either side of it and moving together or'
    + ' apart. scale above 1 zooms in (the fingers spread), below 1 zooms out.',
    'pinch',
    {
      displayId: DISPLAY_ID,
      x: { type: 'number', required: true, description: 'The centre of the pinch, in screen pixels' },
      y: { type: 'number', required: true, description: 'The centre of the pinch, in screen pixels' },
      scale: { type: 'number', description: 'Above 1 spreads the fingers (zoom in), below 1 closes them' },
      durationMs: { type: 'integer', description: 'How long the pinch takes. Default 300' },
    },
  ),
  simpleTool(
    'lw_scroll',
    'Scroll a screen by a screenful, through the scrollable control\'s own accessibility action'
    + ' rather than an injected drag. Use it wherever a list or a form is longer than the screen:'
    + ' a control that has not been laid out yet cannot be pressed by name, and on this device an'
    + ' injected drag does not move a list at all. It scrolls the largest scrollable control on the'
    + ' screen, or the container around the control that a name points at. Read the screen again'
    + ' afterwards - every row has moved, and the rectangles with them.',
    'scroll',
    {
      displayId: DISPLAY_ID,
      direction: {
        type: 'string',
        description: 'forward reads on (the content moves up), backward goes back. Default forward',
        enum: ['forward', 'backward'],
      },
      text: {
        type: 'string',
        description: 'Name of a control whose scrollable container should be scrolled, as lw_ui'
          + ' reported it. Leave it out to scroll the largest scrollable control on the screen',
      },
      times: { type: 'integer', description: 'How many screenfuls, 1 to 10. Default 1' },
    },
  ),

  // ---- 1.0.3 批次 4: 工作区里的文件、媒体库、拍照 ----
  //
  // 这三条与 1.0.2 那一批同形 (app 进程里用 Context 做, 走桥只为把结果按同一套协议送回来), 但
  // `lw_files` 的说明要多说一句它**不**是什么: 这个会话自己就带着 read / write / glob, 而工作区
  // 就是那套工具的家, 所以模型不该为了"在工作区里读写一个文件"来这里

  simpleTool(
    'lw_files',
    'Look at the work area the way the phone does: list a directory, read a text file, write one.'
    + ' Reach for this when the question is about what the *device* makes of a file - whether the'
    + ' media library lists it (that is what the gallery, the music player and a file manager read),'
    + ' what type the system calls it, how big a picture is - or when a file has to be written and'
    + ' the phone has to see it in the same step. Writing scans the file afterwards, so a picture'
    + ' lands in the gallery without a second call. **Paths are resolved against the work area**'
    + ' (a relative one starts there, and anything outside it is refused), which is the one anchor'
    + ' that does not drift with the session\'s own directory. This is not a replacement for this'
    + ' session\'s own read / write / glob tools: those work anywhere the session is allowed to, and'
    + ' they are the ones to use inside the work area for anything else.',
    'files',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list a directory, read a text file, or write one',
        enum: ['list', 'read', 'write'],
      },
      path: {
        type: 'string',
        description: 'The file or directory, relative to the work area or absolute inside it.'
          + ' Required for read and write; list defaults to the work area itself',
      },
      text: {
        type: 'string',
        description: 'What to write, required when op is write. Text only - a binary file has to'
          + ' be built by something else',
      },
    },
  ),

  simpleTool(
    'lw_media_scan',
    'Ask the Android media library to index a file or a directory, and answer with what it made'
    + ' of each file. Use it after something wrote a picture, a video or a piece of audio that the'
    + ' gallery still cannot see: a file that lands in shared storage is not in the library until'
    + ' something scans it, and that is the difference between "the file is there" and "the person'
    + ' can find it". A relative path starts at the work area and an absolute one is taken as it is,'
    + ' so a file inside the work area works either way and one outside it (Downloads, for instance)'
    + ' has to be named in full - this only asks the system to index it and hands back the library\'s'
    + ' own answer, so it discloses no content. A file the scanner does not add (a `.nomedia`'
    + ' directory, a type it does not index) is reported as exactly that rather than as a failure.',
    'mediaScan',
    { path: { type: 'string', required: true, description: 'The file or directory to index' } },
  ),

  simpleTool(
    'lw_take_photo',
    'Take a photo with the device\'s own camera app, and leave it in the work area. This is for'
    + ' "show me what is in front of the phone" or "photograph this": it opens the system camera on'
    + ' the phone\'s own screen and waits (up to waitMs) for a picture to be written. **A person has'
    + ' to press the shutter** - the shutter is on the phone\'s screen, not on a virtual screen of'
    + ' ours, so this only works with someone holding the device. It answers whether the camera'
    + ' really came up, and whether a photo landed; when nothing did, the empty file it made is'
    + ' removed rather than left behind. Read the result back with read_image to see it, or hand it'
    + ' to the person with lw_open_file.',
    'takePhoto',
    {
      waitMs: {
        type: 'integer',
        description: 'How long to wait for the photo, in milliseconds. Default 20000, at most'
          + ' 120000 - a person has to take it in that time',
      },
      note: NOTE,
    },
  ),

  // ---- 2.5.0 批次 7: 日程与快捷指令 ----

  simpleTool(
    'lw_calendar',
    'The phone\'s own calendar: which calendars exist, what is in a stretch of time, one event in'
    + ' full, create an event, change one, and where the free windows are. Use it whenever something'
    + ' is being planned or looked up "on the calendar" - a meeting, a trip, "when am I free", "what'
    + ' have I got on Thursday" - and reach for the `calendar` skill before the first call of a task'
    + ' (it has the workflow, this is the machine). Times are read two ways: epoch milliseconds, or a'
    + ' local time like 2026-10-09T14:00 (a bare date means that day at 00:00), and both the local'
    + ' rendering and the milliseconds come back. **The timezone is the device\'s own**, so "3pm" is'
    + ' 3pm where the phone is. op=events reads at most 200 rows and refuses ranges longer than 62'
    + ' days, so read a window rather than a year. Writing goes to the first writable calendar unless'
    + ' calendarId names another one; **when no calendar is writable this refuses and lists them**'
    + ' instead of pretending an event was created. There is no delete: say so and let the person'
    + ' remove it in their calendar app.',
    'calendar',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list (the calendars), events (a range), read (one event), create, update or'
          + ' free (the gaps in a range)',
        enum: ['list', 'events', 'read', 'create', 'update', 'free'],
      },
      from: {
        type: 'string',
        description: 'The start of the range, required by events and free: epoch milliseconds or a'
          + ' local time like 2026-10-09T14:00',
      },
      to: {
        type: 'string',
        description: 'The end of the range (exclusive), required by events and free, same two'
          + ' spellings as from; at most 62 days after it',
      },
      calendarId: {
        type: 'string',
        description: 'Which calendar, as op=list reported the ids. Leave it out to read all of them,'
          + ' or to write into the first writable one',
      },
      limit: {
        type: 'integer',
        description: 'How many events op=events lists, default 50, at most 200',
      },
      query: {
        type: 'string',
        description: 'A word to filter op=events by, matched against the title and the notes',
      },
      eventId: {
        type: 'string',
        description: 'The event, as a previous call reported it. Required by read and update',
      },
      title: {
        type: 'string',
        description: 'What the event is called, required by create',
      },
      start: {
        type: 'string',
        description: 'When it starts, required by create - same two spellings as from',
      },
      end: {
        type: 'string',
        description: 'When it ends. Leave it out and durationMinutes decides; an all-day event runs'
          + ' to the next midnight',
      },
      durationMinutes: {
        type: 'integer',
        description: 'How long it runs, when end is not given. Default 60',
      },
      allDay: {
        type: 'boolean',
        description: 'True for an event that takes the whole day, which is how a birthday or a trip'
          + ' is usually recorded',
      },
      location: {
        type: 'string',
        description: 'Where it happens, as the phone\'s calendar shows it',
      },
      description: {
        type: 'string',
        description: 'The notes on the event. Never put a password, a code or a key in here',
      },
      minMinutes: {
        type: 'integer',
        description: 'For op=free: how long a gap has to be to count. Default 30',
      },
      dayStart: {
        type: 'string',
        description: 'For op=free: the earliest time of day to consider, HH:MM. Default 09:00',
      },
      dayEnd: {
        type: 'string',
        description: 'For op=free: the latest time of day to consider, HH:MM. Default 22:00',
      },
    },
  ),

  // ---- 会话同步那份配置 (2026-10-14): "让 agent 帮你配置" ----
  //
  // 那个开关住在应用的设置页「同步」那一段 (与进设置之前的顶栏菜单同一份状态), 而**模型碰不到它**:
  // dsh 的 fs 工具关在工作区, lw_linux 的 proot 也只 `-b <work>:/work`。所以配了这一条 —— 设置页
  // 第一排那句"让 agent 帮你配置"就是把主人领到这里, 而语义 (保留未知键 / 「关」= 地址挪进 lw 段 +
  // 三个自动值归零) 只有应用那一侧一份实现
  simpleTool(
    'lw_sync',
    'The session sync of this phone (luwi-dsh-sync): whether it is on, which git remote it pushes'
    + ' to, and how often it runs by itself. **When the user says "让 agent 帮你配置" / "帮我配置同步"'
    + ' (or that sentence arrives from the settings page\'s first row), this is the tool that does it.**'
    + ' op=status reads the current configuration (the token is masked). op=set writes only the fields'
    + ' you pass: remote (the git URL both machines push to - **ask the user for it and never invent'
    + ' one**; an empty string clears it and turns sync off), branch, interval (minutes between'
    + ' periodic pulls, 0 = off), autoPull (pull once as the plugin mounts), autoPush (mirror and push'
    + ' after every turn), on (true turns the master switch on using the address already configured or'
    + ' the one kept from last time; false turns it off, parks the address and zeroes the three auto'
    + ' values - that is the default state). **Sync is off by default and nothing runs in the'
    + ' background while it is off**, so a fresh configuration is: remote + optionally branch/interval.'
    + ' The file is DSH_HOME/lw/session-sync.json and only this tool may change it: the agent\'s own'
    + ' file tools are sandboxed to the workspace and cannot see DSH_HOME. **Changes take effect only'
    + ' after the DSH host restarts** (the plugin reads the file when it mounts), and you cannot'
    + ' restart it from here - tell the user to tap 同步 in the top-bar menu (it restarts for them) or'
    + ' Settings -> 同步. Telling the user that sentence is part of doing this job.',
    'sync',
    {
      op: {
        type: 'string',
        required: true,
        description: 'status reads the configuration, set writes the fields you pass',
        enum: ['status', 'set'],
      },
      remote: {
        type: 'string',
        description: 'set only: the git remote for sessions (https://user:token@host/repo.git, git://,'
          + ' ssh:// or a local path). Ask the user for it. Empty string clears it and turns sync off',
      },
      branch: { type: 'string', description: 'set only: the branch in that repository (default main)' },
      interval: {
        type: 'number',
        description: 'set only: minutes between periodic pulls, 0..10080, 0 turns the periodic pull off',
      },
      autoPull: { type: 'boolean', description: 'set only: pull once when the plugin mounts' },
      autoPush: { type: 'boolean', description: 'set only: mirror and push after every conversation round' },
      on: {
        type: 'boolean',
        description: 'set only: the master switch. false parks the address in lw.savedRemote and zeroes'
          + ' the three auto values (nothing runs); true puts the address back',
      },
    },
  ),

  simpleTool(
    'lw_quick',
    'The quick commands on this phone: the small workflow files that live in DSH_HOME/quick-commands,'
    + ' one Markdown file each, listed on the app\'s Settings -> Quick commands page. **When the user'
    + ' says "用快捷指令: <name>" (run quick command <name>), this is the whole of what that means:'
    + ' read that file with op=read and then do what it says, step by step, loading whichever skill'
    + ' it names.** The same sentence works when it came from the settings page, from the ball or'
    + ' typed in the chat by hand, so treat it identically wherever it arrives. op=list shows what'
    + ' exists. **op=write creates or overwrites one** - reach for it when the user asks for a new'
    + ' quick command ("做成一条快捷指令"), give it a short Chinese name of your own and a body that'
    + ' says which skill to load first and what to do, one step per line: the file is the workflow,'
    + ' so write the steps rather than a description of them. A name cannot contain a path separator'
    + ' or "..". Deleting is not available here: that is a button on the settings page.',
    'quick',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list the quick commands, read one, or write one (create or overwrite)',
        enum: ['list', 'read', 'write'],
      },
      name: {
        type: 'string',
        description: 'Which quick command, without the .md. Required by read and write',
      },
      text: {
        type: 'string',
        description: 'The whole body to write, required by write: the steps of the workflow, one per'
          + ' line, starting with which skill to load',
      },
    },
  ),

  // ---- 2.7.8 批次 5: 技能仓库 ----
  //
  // 桥与应用两侧早就齐了 (`PrivilegedBridge` 的 "skill" → `AppSkills.dispatch`), 缺的只是模型这一侧:
  // 以前只有设置页那颗「一键」按钮能装技能, 模型自己装不了。这一条补齐之后, "把这个应用的技能装上"
  // 与"删掉那个技能"都不必让主人去设置页点

  simpleTool(
    'lw_skill',
    'The skills on this phone: the per-app guides that live in DSH_HOME/skills, one folder each with'
    + ' a SKILL.md, mirrored from the app-skills repository. **Reach for this when the user asks to'
    + ' install a skill for an app, or which apps already have one, or to delete a skill.** op=scan'
    + ' reports what that repository holds and which of those are installed here, matched against the'
    + ' apps that actually appear on the launcher (system components do not count). op=install writes'
    + ' the named skills in: **it never touches a skill that is already there**, so an install can'
    + ' never overwrite something the user edited - to replace one, remove it first, and say so.'
    + ' op=remove deletes exactly the named skill folders and nothing else. Names are folder names:'
    + ' no path separators, no "..". Say what you did in one line when you are done.',
    'skill',
    {
      op: {
        type: 'string',
        required: true,
        description: 'see what the repository holds, install the named skills, or delete the named ones',
        enum: ['scan', 'install', 'remove'],
      },
      skills: {
        type: 'array',
        items: { type: 'string' },
        description: 'The skill folder names to install or remove (the ids the repository lists).'
          + ' Required by install and remove; scan ignores it',
      },
    },
  ),

  // ---- 2.5.0 批次 8: 自动指令 ----
  //
  // 与 lw_quick 同一个形状 (一份文件一条), 差别在"正文是一份 JSON, 而且它会自己响": 所以说明里要把
  // 六个 kind 与两种动作写死 (模型是照这一段写的), 而 `op=status` / `op=history` 是它自己排查用的两条
  // —— "它怎么没响"这个问题只有那两条答得出来

  simpleTool(
    'lw_automation',
    'The automatic commands on this phone: the rules that fire by themselves, one JSON file each in'
    + ' DSH_HOME/automations, listed on the app\'s Settings -> 自动指令 page. **Reach for op=write'
    + ' when the user says something like "每天八点提醒我带伞", "到了公司提醒我", "收到微信就告诉我",'
    + ' "打开王者时别吵我"** - sitting on the rule and remembering it only in your head loses it. A'
    + ' rule is {"name","enabled","when":{"kind",...},"then":{"kind","text"},"cooldownMinutes",'
    + '"dailyLimit","quietHours"}. The eight condition kinds: notice {packages:[], contains, titleContains}'
    + ' fires on a posted notification; foreground {package} fires when that app comes to the front'
    + ' (needs accessibility, and only works while it is on); light {below|above, forSeconds} on a'
    + ' light level; time {at:"07:30", weekdays:[1..7], 1 is Monday} on the clock; place {place,'
    + ' radiusMeters} when the phone comes within that many metres of a named place; weather {place,'
    + ' metric:"temperature"|"precipitation"|"weatherCode", below|above|atLeast} on a periodic'
    + ' Open-Meteo reading; unlock {firstOfDay} when the screen is unlocked (firstOfDay means the'
    + ' first unlock of the day); activity {withinMinutes} when the phone was unlocked or touched'
    + ' within that many minutes (default 10). Two more shapes combine those eight: all {conds:[...]}'
    + ' needs every sub-condition, any {conds:[...]} needs one of them; conds holds 2 to 5 conditions'
    + ' and **they do not nest** (putting an all or an any inside a combination is refused, so'
    + ' "A and B, or C and D" is not writable yet). A combination judges on **freshness**: each'
    + ' sub-condition remembers the last time it held, and it counts as true while that moment is'
    + ' inside its window (defaults: time 1800 s; notice, foreground and unlock 120 s; light, place,'
    + ' weather and activity 300 s; a sub-condition may write within to override it). That is why'
    + ' "23:45 之后半小时内切到微信" is {kind:"all",conds:[{kind:"time",at:"23:45"},{kind:"foreground",'
    + ' package:"com.tencent.mm",within:120}]}. The old single-kind shape is unchanged:'
    + ' {"kind":"time","at":"23:45"} still means exactly what it did before.'
    + ' once {at:"2026-10-18T21:30:00+08:00"} at an absolute moment with a'
    + ' timezone - it fires once and then turns itself off, and it is what to use for a one-off'
    + ' follow-up (the time must carry its offset, never a bare local time). then.kind is "remind" (say a line, post a notification and answer) or'
    + ' "task" (open a conversation and do the thing - that only runs while the user has turned on'
    + ' 允许它自己动手). **Do not write the wording of a reminder into then.text: that sentence is'
    + ' written when the rule fires, in the conversation the rule opens**; put there what the user'
    + ' asked for (for example "提醒主人今天是妈妈的生日"). Leave the two limits out unless the user'
    + ' asked for something else: the defaults are cooldownMinutes (30) and dailyLimit (5). **When the'
    + ' user names a cooldown, write that exact number into cooldownMinutes instead of 30** - the'
    + ' settings page lets them set it themselves too, and 0 means no cooldown at all;'
    + ' at most one rule with the same name exists,'
    + ' so op=write overwrites. A "task" rule also counts toward the user\'s daily proactive limit'
    + ' (Settings -> 探索); a manual run from that page does not count. op=list shows the rules, op=read one, op=status says which of the nine'
    + ' watches can run right now, what is holding a rule back (no notification access,'
    + ' accessibility off, no exact alarms, power save, quiet hours) and, for every combination,'
    + ' the last time each sub-condition held (that line is how you answer "why did it not fire"), op=history says what was'
    + ' decided recently and why it did not fire. Deleting is not available here: that is a button on'
    + ' the settings page.',
    'automation',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list the rules, read one, write one (create or overwrite), the watches\' state,'
          + ' or the recent decisions',
        enum: ['list', 'read', 'write', 'status', 'history'],
      },
      name: {
        type: 'string',
        description: 'Which rule, without the .json. Required by read and write',
      },
      json: {
        type: 'string',
        description: 'The whole rule as one JSON object, required by write',
      },
      limit: {
        type: 'integer',
        description: 'For op=history: how many decisions to show. Default 20, at most 200',
      },
      rule: {
        type: 'string',
        description: 'For op=history: only the decisions of this rule',
      },
    },
  ),

  // ---- 2.7.8 批次 7: 探索 ----
  //
  // 这一档的三件事在这条说明里就要写死 (模型是照这一段做的): 预算由宿主数 (到步只提醒 / 到点取消那一轮)、
  // 三条红线在 persona 里而工具面不拦 (D9)、摘要只能走 op=finish 而且宿主落文件 (见 [startExplore] /
  // [finishExplore])

  defineTool({
    name: 'lw_explore',
    description:
      'Explore mode. One exploration is one conversation in the Luwi workspace whose budget the HOST'
      + ' keeps: 30 minutes and 60 tool calls unless the caller says otherwise. **The budget is not'
      + ' yours to count**: when the calls run out you are told once to finish, and when the time'
      + ' runs out that turn is cancelled and you are told to finish immediately - neither of those'
      + ' refuses a tool call, so nothing here stops you, and nothing here counts what a subagent'
      + ' does. **The user sets a daily start time and the host opens the exploration itself from the'
      + ' task card it keeps**, so this tool never starts one: op=status reports the current run, and'
      + ' op=finish hands in the report. The three red'
      + ' lines (no irreversible act on an app, no writing any existing file, no sending anything)'
      + ' live in this preset\'s prompt text, not in this tool: **this call cannot enforce them and'
      + ' does not check them.** The summary has to come through op=finish, and the host is what'
      + ' writes it to $DSH_HOME/lw/explore/<time>.md together with the candidate cards below'
      + ' `candidates/` - **do not write that report or those cards with any other tool**, and do'
      + ' not put it into long-term memory yourself: promotion is the host\'s and the user\'s call.'
      + ' Write the summary in five sections - 已确认事实 / 仍不确定的点 / 下一场建议优先探索 /'
      + ' 风险与拦截记录 / 证据引用 - at most 8000 characters (longer is cut and marked truncated).'
      + ' A candidate card is ONE sentence worth having next time (kind fact / preference / todo /'
      + ' date, plus sensitivity low or high, confidence, evidence) - **not a snapshot of the'
      + ' screen**: battery level or "it is on this page" is not a card. A date (birthday,'
      + ' anniversary, due date) is always sensitivity high, and at most 5 cards go in one finish.'
      + ' op=finish also takes `nextTask`: the task for the NEXT exploration, one short paragraph,'
      + ' decided from this run, the previous summary and the memory you already have. The host'
      + ' writes it over the task card, so the next scheduled run starts from it - leave it out and'
      + ' the card stays as it is.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status reads this exploration, finish hands in the report',
        enum: ['status', 'finish'],
      },
      summary: {
        type: 'string',
        description: 'finish only, required: the whole report, the five sections, at most 8000'
          + ' characters',
      },
      candidates: {
        type: 'array',
        items: { type: 'object', additionalProperties: true },
        description: 'finish only: at most 5 cards, each {fact, kind, sensitivity, confidence,'
          + ' evidence}. kind is fact / preference / todo / date; sensitivity is low or high'
          + ' (date is always high)',
      },
      nextTask: {
        type: 'string',
        description: 'finish only: the task of the next exploration, one paragraph (what to look'
          + ' at next time). It becomes the card the next scheduled run uses',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args, exec) {
      const op = String(args?.op ?? '').trim()
      // 工具那一层没有 `ctx` 这个名字 (见 lw_mode 上面那段), 一律用模块级的 hostCtx
      if (op === 'status') return exploreStatusText(hostCtx)
      if (op === 'finish') {
        const outcome = await finishExplore(hostCtx, {
          summary: args?.summary,
          candidates: args?.candidates,
          nextTask: args?.nextTask,
        }, sessionIdOf(exec))
        return exploreFinishText(outcome)
      }
      throw new Error('lw_explore takes op status or finish')
    },
  }),

  // ---- 1.0.3 批次 6: 事件订阅 ----
  //
  // 这一批要改的是模型的**读法**: 按一下之后不要马上反复 lw_ui, 而是先说清"我在等什么", 再等它发生。
  // 事件由系统送进服务那条有界队列 (掩码里就四个类型), 这两条工具读的就是那根游标

  simpleTool(
    'lw_events_subscribe',
    'Start watching a screen for changes, so a later call can be told what happened instead of'
    + ' asking again and again. Use it right after an action that makes something change: the'
    + ' answer lists what had just happened in the same scope (so you are not blind to the moments'
    + ' before you started) and hands back an id. Then lw_events_wait with that id collects what'
    + ' happens next. **This is how to stop polling**: lw_ui reads a snapshot and costs a round'
    + ' trip, while a subscription costs nothing until something actually changes. A subscription'
    + ' lives for lifeMs (ten minutes by default) and then forgets itself; op=stop ends one early,'
    + ' op=list shows the live ones. The four kinds are window (a different window or activity came'
    + ' up), content (the inside of a window changed), focus (a field was focused) and scroll.',
    'eventsSubscribe',
    {
      op: {
        type: 'string',
        description: 'start watching (the default), stop one, or list the live ones',
        enum: ['start', 'stop', 'list'],
      },
      id: { type: 'string', description: 'For op=stop: the id the subscription was given' },
      displayId: WATCHED_DISPLAY,
      kind: {
        type: 'string',
        description: 'Only this kind of change; leave it out for all four',
        enum: ['window', 'content', 'focus', 'scroll'],
      },
      package: { type: 'string', description: 'Only changes from this app, by package name' },
      lifeMs: {
        type: 'integer',
        description: 'How long the subscription lives, in milliseconds. Default 600000 (ten'
          + ' minutes), at most 1800000',
      },
    },
  ),

  simpleTool(
    'lw_events_wait',
    'Wait until something changes on a screen, then answer with what happened. Give it the id of a'
    + ' subscription from lw_events_subscribe to carry on from where that one got to, or pass the'
    + ' filters instead and it watches just for this one call. It answers with the changes in order,'
    + ' with how long after the wait started each one landed, and says so plainly when nothing'
    + ' changed in that time - which is itself a useful answer, and cheaper than reading the screen'
    + ' twice. Events arrive throttled by the system (about ten a second) and identical ones in a'
    + ' row are collapsed into one line with a count, so a scroll shows up as a line rather than as'
    + ' hundreds.',
    'eventsWait',
    {
      displayId: WATCHED_DISPLAY,
      id: {
        type: 'string',
        description: 'The subscription to carry on from; leave it out to watch just for this call'
          + ' using the filters below',
      },
      kind: {
        type: 'string',
        description: 'Only this kind of change; leave it out for all four',
        enum: ['window', 'content', 'focus', 'scroll'],
      },
      package: { type: 'string', description: 'Only changes from this app, by package name' },
      timeoutMs: {
        type: 'integer',
        description: 'How long to wait before answering that nothing happened. Default 15000, at'
          + ' most 90000',
      },
      limit: { type: 'integer', description: 'At most this many events to print. Default 20' },
    },
  ),

  defineTool({
    name: 'lw_speech',
    description:
      'Transcribe speech on this phone with no network and no API key: everything runs in the '
      + 'app process, so the audio never leaves the device. Two engines, and engine= picks one: '
      + 'engine=glm is Zhipu GLM-ASR-Nano (1.5 B, Q4_K, samples at a time of CPU: a couple of '
      + 'seconds for a short phrase, ten seconds or more for a long sentence), which is the accurate '
      + 'one and is reached only by naming it, and engine=sherpa is SenseVoice '
      + '(Chinese, English, Cantonese, Japanese and Korean, with punctuation), which is the fast one '
      + 'and the default everywhere - the GUI voice input button and the ball both use it. '
      + 'op=status reports both engines and whether their models are on disk; op=prepare downloads '
      + 'the one named, from the hf-mirror copy (huggingface.co itself is unreachable from this '
      + 'phone) - GLM-ASR is about 1.6 GB, SenseVoice about 240 MB plus the 1.8 MB silero '
      + 'voice-activity model that the one-sentence window cuts segments with; op=transcribe turns '
      + 'one 16 kHz mono PCM16 WAV file into text.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status, prepare or transcribe',
      },
      engine: {
        type: 'string',
        description:
          'glm (accurate, 1.5 B, slow, and 1.6 GB of weights of its own) or sherpa (fast, small); '
          + 'the default is sherpa, the same engine the GUI voice input button uses, and op=status '
          + 'ignores this',
      },
      wav: {
        type: 'string',
        description:
          'Absolute path of a 16 kHz mono PCM16 WAV file, required for op=transcribe',
      },
      language: {
        type: 'string',
        description:
          'auto, zh, en, yue, ja or ko (default auto, which lets the model decide)',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const engine = speechEngineOf(args.engine) ?? SPEECH_ENGINE_DEFAULT
      if (args.op === 'status') {
        const info = await speechInspect()
        return [
          `SenseVoice: engine ${info.engine} ${info.sherpa} (onnxruntime ${info.onnxruntime})`,
          `  model ${info.model} in ${info.directory}`,
          info.present
            ? `  downloaded: ${info.modelBytes} bytes of weights, ${info.tokensBytes} bytes of tokens`
            : '  not downloaded yet: op=prepare engine=sherpa fetches it (about 240 MB)',
          `GLM-ASR-Nano: llama.cpp with mtmd, ${info.glmPresent ? 'downloaded' : 'not downloaded yet'}`
            + ` in ${info.glm?.directory ?? 'nowhere yet'}`,
          info.glmPresent
            ? `  Q4_K ${info.glm.modelBytes} bytes, audio encoder ${info.glm.mmprojBytes} bytes,`
              + ` loaded in memory: ${info.glm.loaded ? 'yes' : 'no'}`
            : '  op=prepare engine=glm fetches it (about 1.6 GB), then transcribe with engine=glm',
          info.vad
            ? `silero VAD downloaded: ${info.vadBytes} bytes at ${info.vadPath}`
            : `the silero VAD is missing (${info.vadBytes} bytes at ${info.vadPath}): the wake word`
              + ' still listens, but nothing gets cut into segments until op=prepare fetches it',
          `SenseVoice loaded in memory: ${info.loaded ? 'yes' : 'no'}`,
          `languages: ${info.languages}`,
          `the voice input button in the GUI uses: ${SPEECH_ENGINE_DEFAULT}`,
        ].join('\n')
      }
      if (args.op === 'prepare') {
        if (engine === SPEECH_ENGINE_GLM) {
          const info = await speechPrepareGlm()
          return `the GLM-ASR-Nano model is ready in ${info.glm.directory}, about 1.6 GB of it;`
            + ' transcribe with engine=glm (the GUI voice input button stays on SenseVoice)'
        }
        const info = await speechPrepare(SPEECH_ENGINE_SHERPA)
        return `the SenseVoice model is ready in ${info.directory}; op=transcribe engine=sherpa`
          + ' can use it now, and the GUI voice input button is on it'
      }
      if (args.op === 'transcribe') {
        if (!args.wav) throw new Error('op=transcribe names the recording with wav=<a 16 kHz mono WAV>')
        const answer = await speechTranscribe(args.wav, args.language, engine)
        const detail = engine === SPEECH_ENGINE_GLM
          ? `${(answer.elapsedMs / 1000).toFixed(1)}s of CPU for the whole request`
          : `${answer.seconds.toFixed(1)}s of audio, ${answer.language}`
        return `${answer.text}\n\n(${detail}, ${answer.elapsedMs} ms)`
      }
      throw new Error(`op has to be status, prepare or transcribe, not "${args.op}"`)
    },
  }),
  defineTool({
    name: 'lw_speak',
    description:
      'Speak a line out loud on this phone through its own text-to-speech engine: nothing goes over '
      + 'the network and no API key is involved. op=status reports the engine, how many voices it '
      + 'has and whether Chinese is usable; op=speak says the text and waits until the engine '
      + 'reports it finished; op=stop cuts off whatever is being said; op=release lets the engine go '
      + '(bringing it up costs a few hundred milliseconds, so it is kept while in use). Use it to '
      + 'read a result back to the person holding the phone, for instance one line when a task is '
      + 'done. Whether sound actually came out is theirs to confirm: the answer only reports what '
      + 'the engine said. The engine and its voice are chosen on the settings page: the system '
      + 'engine keeps its own volume and follows the phone\'s media volume; an imported on-device '
      + 'voice reads at its own quiet level and has a volume (percent) the settings page owns; the '
      + 'free Edge online engine and a self-hosted OpenAI-compatible endpoint (address and key live '
      + 'on the settings page) both take their loudness from the service they talk to.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status, speak, stop or release',
      },
      text: {
        type: 'string',
        description: 'The line to speak, required for op=speak. Keep it short: this is read aloud.',
      },
      interrupt: {
        type: 'boolean',
        description: 'Cut off whatever is being said before this line (default true)',
      },
      rate: {
        type: 'number',
        description: 'Speech rate from 0.5 to 2.0 (default 1.0)',
      },
      volume: {
        type: 'number',
        description: 'How loud, in percent, from 0 to 300 (default: whatever the settings page '
          + 'holds). 100 is the on-device model\'s own level, which is a quiet one; the gain only '
          + 'exists on the on-device engine — the system engine and both online engines keep their '
          + 'own loudness, and the answer says so when a number is ignored',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const request = { op: args.op }
      if (args.text !== undefined) request.text = args.text
      if (args.interrupt !== undefined) request.interrupt = args.interrupt
      if (args.rate !== undefined) request.rate = args.rate
      if (args.volume !== undefined) request.volume = args.volume
      const answer = await call('speak', request)
      if (args.op === 'status') {
        const onDevice = answer.readingWith === 'on-device'
        return [
          `reading with: ${onDevice
            ? `the on-device voice ${answer.onDeviceModel}`
            : 'the system engine'}`,
          `rate: ${answer.rateFollowsSystem ? 'whatever the system says' : `${answer.rate} x`}`
            + `, voice: ${answer.selectedVoice}`,
          answer.volume === undefined
            ? 'volume: this app build does not report one'
            : onDevice
              ? `volume: ${answer.volume}% of the model's own level (range ${answer.volumeRange}%;`
                + ' the system engine has no such knob, it keeps its own)'
              : `volume: ${answer.volume}% is set for the on-device engine, which is not the one in`
                + ' use right now; the system engine follows the phone\'s media volume',
          answer.readAloud === false
            ? 'read-aloud: off (a finished reply is not read on its own; a line asked for by name still is)'
            : 'read-aloud: on (every finished reply is read out loud)',
          `system engine ${answer.engine}`,
          `voices ${answer.voices}, Chinese: ${answer.chinese}`,
          answer.chineseVoices
            ? `Chinese voices: ${answer.chineseVoices}`
            : 'no Chinese voice is listed by the engine',
          answer.onDeviceVoices
            ? `on-device voices: ${answer.onDeviceVoices}`
            : `on-device voices: none imported under ${answer.voicesDirectory}`,
          `speaking right now: ${answer.speaking ? 'yes' : 'no'} (${answer.utterances} utterances so far)`,
        ].join('\n')
      }
      if (args.op === 'speak') {
        if (!answer.spoken) return `the engine did not report finishing: ${answer.detail}`
        // 自带那条要报出用的是哪个音色目录, 系统那条没有这一项
        const withVoice = answer.readingWith === 'on-device' ? ` with the on-device voice ${answer.voice}` : ''
        // 点了音量却没落到这条引擎上时要把这句带上: 不然模型以为它调过了, 而人听到的还是原样
        const volumeNote = answer.volumeIgnored && answer.volumeNote ? `; ${answer.volumeNote}` : ''
        return `the engine took ${answer.characters} characters` + withVoice
          + (answer.pieces > 1 ? ` in ${answer.pieces} pieces` : '')
          + ` and reported it finished${volumeNote}`
      }
      if (args.op === 'stop') {
        return answer.stopped
          ? `stopped: ${answer.detail}`
          : `nothing was stopped: ${answer.detail}`
      }
      return answer.released
        ? 'the engine was let go; the next call brings it up again'
        : answer.detail
    },
  }),
  defineTool({
    name: 'lw_voice',
    description:
      'The voice inbox on this phone: the app cuts what you say into sentences with a silero VAD '
      + '(3 s of silence ends one, 15 s at most each), transcribes each one on-device, and drops it '
      + 'into a queue the host sends into the '
      + 'conversation as a `voice`-sourced message. Where a line lands is fixed: **the ball has a '
      + 'conversation of its own, in the `dsh-ball` folder of the workspace**, and every line either '
      + 'continues that one (steering into it while its turn runs) or opens a new one there - it is '
      + 'never spliced into whatever conversation the person happens to have open in the GUI. One '
      + 'exception: **the ball\'s reply box can name its own conversation** - a reply carries the '
      + 'session it came from, and anything typed or spoken back while that box is up is delivered '
      + 'there (`to`), so answering the box keeps answering that same window even after an hour. A line '
      + 'that matches a command sentence is different: '
      + 'it is executed here and never delivered (for example 打开视频模式, 回到手机模式 - see '
      + 'VOICE_COMMANDS; the app itself writes 打断当前回答 when the ball is double-tapped while it '
      + 'says 正在想, and that one cancels the turn running in the ball conversation). '
      + 'op=inbox counts those apart from the delivered ones, and op=say says so '
      + 'when a line was taken that way. op=inbox reports that queue, where the reader has '
      + 'got to and how the last few deliveries landed; op=clean shows what a piece of markdown '
      + 'would sound like when read aloud (code blocks, tables and links are stripped); op=read '
      + 'speaks a line right now through the same cleaning and the same engine the automatic reading '
      + 'uses; op=say delivers a line by hand, exactly as if it had been spoken. What opens that '
      + 'cutting + recognition chain is lw_wakeword: the wake word is started and stopped with it, '
      + 'and a hit (or a tap on the ball) opens the chain for exactly ONE sentence, which closes '
      + 'again 10 s after the last thing it heard. Video mode is the only thing that keeps it '
      + 'resident instead of closing after one sentence.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'inbox, clean, read or say',
      },
      text: {
        type: 'string',
        description: 'The text op=clean, op=read and op=say work on',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args.op === 'say') {
        if (!args.text) throw new Error('op=say needs text=<the line to deliver>')
        // **工具里要用 hostCtx**: `TOOLS` 那张表是模块级的, `apply(ctx)` 里那个形参在它里面看不见 ——
        // 写成 `ctx` 就是 `ctx is not defined` (2026-10-05 本批走工具那一层时抓到的, 而那之前它只会在
        // 模型真调 op=say 时才炸)
        if (hostCtx === null) throw new Error('the plugin was never applied, so there is no context to deliver with')
        const outcome = await voiceDeliver(hostCtx, { seq: 0, text: args.text, source: 'voice', at: Date.now() })
        // 命令句会被吃掉 (批次 4.5): 这时没有会话可报, 报的是"它把那句话当成了命令, 以及切成了没成"
        if (outcome.command === true) {
          return `that was a command, not a line for the conversation: mode -> ${outcome.mode},`
            + ` ${outcome.switched ? 'switched' : 'NOT switched'}`
            + (outcome.detail ? `\n${outcome.detail}` : '')
        }
        return `delivered to ${String(voiceDelivery.sessionId)}: `
          + (outcome.running ? 'steered into the running turn' : 'queued for the next turn')
          // 这一行是"它到底看没看见那块屏"的现场证据: op=say 就是拿它来验自动识屏那一条的
          + (outcome.screenshot === true
            ? `\n(one picture of the phone\'s own screen went in with it: ${voiceDelivery.autoShot.last?.why ?? 'attached'})`
            : `\n(no screenshot went in: ${voiceDelivery.autoShot.last?.why ?? 'no reason recorded'})`)
      }
      if (args.op === 'clean' || args.op === 'read') {
        if (!args.text) throw new Error(`op=${args.op} needs text=<what to say>`)
        const spoken = readAloudText(args.text)
        if (!spoken) {
          return 'nothing in that text is worth reading aloud: it is all code, tables, links or'
            + ' blank lines, so the cleaner left an empty line'
        }
        if (args.op === 'clean') return spoken
        const answer = await call('speak', { op: 'speak', text: spoken })
        // 自带那条引擎会回报用的是哪个音色目录; 系统那条没有这一项
        const via = answer.readingWith === 'on-device' ? ` with the on-device voice ${answer.voice}` : ''
        return answer.spoken
          ? `said ${answer.characters} characters${via} (from ${args.text.length} of markdown)`
          : `the engine did not report finishing: ${answer.detail}`
      }
      if (args.op === 'inbox') {
        const state = await voiceInboxState()
        if (!state.inbox) return 'the voice inbox has no place to live: DSH_HOME is not set'
        const lines = state.queued.slice(-MAX_VOICE_LINES)
        // 2.7.8 批次 2 的三项读数 (根因一 / 根因二的现场): 这一场属于哪个工作区 / 它的 cwd /
        // `dsh-ball` 那条工作区注册没注册 —— 以及那本账现在能不能用
        const ledger = await voiceLedgerSnapshot()
        const readings = await voiceWorkspaceReadings(hostCtx, ledger.id || voiceDelivery.sessionId || '')
        return withJson(
          [
            `inbox ${state.inbox}`,
            `${state.queued.length} sentence(s) in the file, reader has delivered up to #`
              + `${state.cursor === null ? 'nothing yet' : state.cursor}`,
            `${voiceDelivery.lines} delivered`
              + ` (${voiceDelivery.steered} steered, ${voiceDelivery.queued} queued)`
              + `${voiceDelivery.sessionId ? `, last to ${voiceDelivery.sessionId}` : ''}`
              + `${voiceDelivery.opened ? `, ${voiceDelivery.opened} opened a new conversation` : ''}`
              + `${voiceDelivery.origin ? `, the last one came from ${voiceDelivery.origin}` : ''}`,
            `${voiceDelivery.current
              ? `the current conversation is ${voiceDelivery.current}`
              : 'there is no current conversation yet'}`
              + `${voiceDelivery.reused ? ` (${voiceDelivery.reused} line(s) reused it)` : ''}`
              + `${voiceDelivery.why ? `, last line: ${voiceDelivery.why}` : ''}`,
            // 那本账 (2.7.8 批次 2): 它指向谁、能不能用、为什么不能用
            `the ledger points at ${ledger.id || 'nothing'}`
              + `${ledger.origin ? ` (written by a ${ledger.origin} line)` : ''}`
              + `${ledger.usable ? ', reusable' : `, not reusable - ${ledger.why}`}`
              + `${ledger.cwd ? `, its cwd is ${ledger.cwd}` : ''}`,
            `the ${BALL_WORKSPACE_NAME} workspace is `
              + `${readings.registered === true
                ? `registered (${ballWorkspaceState.path ?? ballWorkspace()})`
                : readings.registered === false
                  ? `not registered - ${readings.registeredWhy ?? 'no reason recorded'}`
                  : 'not known yet (nothing has asked the registry)'}`
              + `${readings.workspace ? `; that session belongs to ${readings.workspace}` : ''}`
              + `${readings.member === true
                ? ' and is on that workspace\'s list'
                : readings.member === false
                  ? ' but is NOT on that workspace\'s list (it shows up under Ungrouped)'
                  : ''}`
              + `${readings.cwd ? ` (${readings.cwd})` : ''}`,
            // 新开一场时用的是哪个预设 (2026-10-09): 选中的那个没注册时这里会写"退到了哪一个"
            `preset for a new conversation: ${voiceDelivery.preset || 'the registry default'}`
              + `${voiceDelivery.presetWhy ? ` - ${voiceDelivery.presetWhy}` : ''}`,
            // **跳过的那几句要说出来** (见 [startVoiceInbox]): 订阅的人看不到日志, 而"你说了话, 没有
            // 回音"与"这一句没能送出去"是两件事
            `${voiceDelivery.skipped} line(s) gave up after ${VOICE_DELIVER_TRIES} tries`
              + `${voiceDelivery.lastSkip
                ? `, last was #${voiceDelivery.lastSkip.seq}: ${voiceDelivery.lastSkip.reason}`
                : ''}`,
            // 指代不明那一张图 (批次 4): "这一句投出去了"与"它带着一张图投出去"是两件事, 而主人问
            // "为什么它没看见我屏幕上的东西"时只有这一行答得出来
            `${voiceDelivery.autoShot.tried} line(s) asked for a picture of the phone\'s own screen`
              + ` (${voiceDelivery.autoShot.attached} attached)`
              + `${voiceDelivery.autoShot.skipped ? `, ${voiceDelivery.autoShot.skipped} skipped` : ''}`
              + `${voiceDelivery.autoShot.last
                ? `, last: ${voiceDelivery.autoShot.last.attached ? 'attached' : 'not attached'}`
                  + ` - ${voiceDelivery.autoShot.last.why}`
                : ''}`,
            `${voiceReading.count} reply(ies) read aloud`
              + `${voiceReading.skipped ? `, ${voiceReading.skipped} skipped because read-aloud is off` : ''}`
              + `${voiceReading.reply
                ? `, last one ${voiceReading.reply.channel ? 'went into' : 'was kept for'} the floating channel`
                : ''}`
              + `${voiceReading.error ? `, last problem: ${voiceReading.error}` : ''}`,
            `${voiceCommands.count} spoken command(s)`
              + `${voiceCommands.last ? `, last was "${voiceCommands.last.said}" -> ${voiceCommands.last.mode}`
                + ` (${voiceCommands.last.switched ? 'switched' : 'not switched'})` : ''}`
              + `${voiceCommands.error ? `, last problem: ${voiceCommands.error}` : ''}`,
            // 双击打断那一条 (主人 2026-10-06 加的): "要了几次 / 真的取消了几场" 与"切没切模式"一样,
            // 是订阅的人判断"那一下到底做了什么"的唯一读数
            `${voiceInterrupt.count} interrupt(s) asked (${voiceInterrupt.cancelled} cancelled a turn)`
              + `${voiceInterrupt.last
                ? `, last: ${voiceInterrupt.last.cancelled ? 'cancelled' : 'nothing to cancel'}`
                  + `${voiceInterrupt.last.sessionId ? ` in ${voiceInterrupt.last.sessionId}` : ''}`
                  + ` - ${voiceInterrupt.last.detail}`
                : ''}`
              + `${voiceInterrupt.error ? `, last problem: ${voiceInterrupt.error}` : ''}`,
            ...lines.map((line) => `  #${line.seq} ${line.text}`),
          ],
          {
            inbox: state.inbox,
            cursor: state.cursor,
            queued: lines,
            delivery: { ...voiceDelivery },
            ledger,
            workspace: readings.workspace,
            workspaceId: readings.workspaceId ?? '',
            member: readings.member ?? null,
            cwd: readings.cwd,
            registered: readings.registered,
            reading: { ...voiceReading },
            commands: { ...voiceCommands },
            interrupts: { ...voiceInterrupt },
          },
        )
      }
      throw new Error(`op has to be inbox, clean, read or say, not "${args.op}"`)
    },
  }),
  defineTool({
    name: 'lw_overlay',
    description:
      'Float a small ball over other apps, so this phone\'s assistant is one tap away from anywhere. '
      + 'The ball does not take focus, so taps outside it still reach the app underneath, and it is '
      + 'dragged to an edge where it tucks itself half out of the screen and stays dim until '
      + 'something happens (the way the system\'s own assistant ball behaves). Its face says what it '
      + 'is doing: 正在听 while it is listening for the sentence you are saying, 正在想 while a turn is '
      + 'running here on the host, 正在说 while a reply is being spoken, and the app icon when it '
      + 'is idle. '
      + 'Tap it to speak (that goes into the app\'s own recognition chain, not the page microphone; '
      + 'the first tap only summons the ball, the next one starts listening), hold '
      + 'it for the menu (keyboard input channel, screen mode on/off, close the ball) and drag it to '
      + 'dock it. Double-tap it while it says 正在想 and that turn is interrupted (the ball asks this '
      + 'host to cancel the turn running in the ball conversation and drops the word again). '
      + 'op=show puts the ball up - expand=true opens the keyboard strip as well, and that strip IS '
      + 'focusable, so while it is open touches outside it no longer pass through; op=expand and '
      + 'op=collapse open and close that strip; op=channel opens the text channel instead (a 650 px '
      + 'box the app draws itself: it grows with the text up to seven lines, follows the ball, sends '
      + 'on Enter, closes as soon as that line went out, comes back up by itself - without taking '
      + 'focus - when the reply arrives, closes by itself once nobody has touched it for 20 s, and '
      + 'closes after a double tap on empty space - the second tap has to land within 300 ms of the '
      + 'first one, a slower one just starts the count again); op=reply is the '
      + 'host pushing a finished reply into that channel and is not something the model calls (it '
      + 'does nothing while the ball is down, and it never brings the ball back); op=note is the same '
      + 'push without being an answer - it is how a spoken or typed line that could not be delivered '
      + 'says so, in the ball\'s own box; op=hide takes the ball away - it removes the '
      + 'window itself, stops the service and clears the stored "keep it on screen" flag, and its '
      + 'answer carries a problem field when the window would not come off; op=state reports the '
      + 'permission, whether the ball is up and whether its service is running, where it '
      + 'is docked, which word it is showing, whether it has tucked itself away at the edge yet and '
      + 'which of the six reasons is holding it out (a finger on it, the menu, the voice chain, the '
      + 'keyboard, the text channel, or simply not idle for 5 s yet), how long the text channel has '
      + 'been untouched, how many times it was double-tapped to interrupt, the text channel and its '
      + 'replies, the active mode and '
      + 'the last problem.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'show, expand, collapse, hide, state, channel, reply or note'
          + ' (the last three are host-side pushes)',
      },
      expand: {
        type: 'boolean',
        description: 'For op=show: also open the keyboard strip (it takes focus while open)',
      },
      text: {
        type: 'string',
        description: 'For op=reply: the text to put into the floating channel',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const request = { op: args.op }
      if (args.expand !== undefined) request.expand = args.expand
      if (args.text !== undefined) request.text = args.text
      const answer = await call('overlay', request)
      if (args.op === 'show' || args.op === 'expand') {
        return `the ball is up${answer.expanded ? ', with the keyboard strip open' : ''}:`
          + ` docked at ${answer.x},${answer.y}`
          + (answer.word ? `, showing ${answer.word}` : '')
          + (answer.mode ? `, mode ${answer.mode}` : '')
          + (answer.url ? '' : ' (no GUI address yet, so the strip cannot open)')
      }
      if (args.op === 'hide' || args.op === 'collapse') return answer.detail
      if (args.op === 'channel' || args.op === 'reply') {
        const where = answer.channel === true ? 'open' : 'closed'
        return `${answer.detail} (channel ${where})`
      }
      return [
        `overlay permission: ${answer.permission ? 'granted' : 'not granted'}`,
        `ball: ${answer.showing ? `up at ${answer.x},${answer.y}${answer.expanded ? ' (strip open)' : ''}` : 'not up'}`
          + `, remembered: ${answer.remembered ? 'yes' : 'no'}`,
        `showing: ${answer.word || 'idle'}${answer.phase === 'thinking' ? ', a turn is running' : ''}`,
        // 收边那一档: "为什么它还没半隐"就是这一行 —— 这个原因只有五种 (held / menu / listening /
        // keyboard / activity 那一档开始了) 加"还没到点", 而主人 2026-10-06 报的毛病就落在 keyboard 上
        `idle edge: ${answer.peeked ? 'peeked away' : 'out'}`
          + `${answer.ballWait ? `, ${answer.ballWait === 'waiting' ? 'waiting' : answer.ballWait}` : ''}`
          + `${answer.keyboard ? ', the keyboard counts as in use' : ''}`
          + `${typeof answer.idleMs === 'number' ? `, idle ${answer.idleMs}ms` : ''}`,
        `text channel: ${answer.channel ? 'open' : 'closed'}`
          + `, ${answer.replies ?? 0} reply(ies) in it`,
        `mode: ${answer.mode}`,
        `host: ${answer.host}`,
        answer.said ? `last said: ${answer.said}` : '',
        answer.page ? `last problem: ${answer.page}` : '',
      ].filter(Boolean).join('\n')
    },
  }),
  defineTool({
    name: 'lw_wakeword',
    description:
      'Listen for a wake word on this phone, so the agent can be called by voice instead of by '
      + 'typing. The listening runs in the app itself with sherpa-onnx keyword spotting - a 3.3M '
      + 'parameter zipformer, 16 kHz mono, nothing leaves the device and no API key is involved. '
      + 'The wake word is a low-power gatekeeper and the cutting + recognition chain (silero VAD + '
      + 'SenseVoice) is a second stage: the keyword spotter guards the word the whole time, and a '
      + 'hit opens that second stage. **A hit always buys one sentence**: the chain opens, what you '
      + 'say goes into the ball\'s conversation, and then it closes again (10 s without a word is the '
      + 'idle limit, and that is when the 240 MB model goes back). '
      + '**Video mode is the one and only thing that keeps that chain resident** - the app\'s setting '
      + 'page has no resident-voice switch of its own (the switch it used to have was removed in '
      + '2.0.0, because turning it on in phone mode is exactly what made a conversation run on and '
      + 'on). Switch with lw_mode: "video" makes it resident, "phone" or "screen" releases it. '
      + 'Where that first sentence lands is decided by the clock alone (2026-10-06): inside the hour '
      + 'since the last delivered line it joins the ball\'s current conversation (steering into its '
      + 'running turn when there is one), and once that hour is up the next line opens a new one - a '
      + 'wake word and a tap on the ball behave the same way. The app\'s settings page also decides '
      + 'what a hit does '
      + 'beyond waking - just wake, or also switch to video mode / back to phone mode (those two go '
      + 'out as command sentences the host executes, so they are never delivered) - and whether it '
      + 'buzzes at all. '
      + 'op=status reports the model, the words being watched for, whether the microphone '
      + 'permission is granted, whether the listener is up, how many times it has fired, and '
      + 'whether that second stage is resident right now; '
      + 'op=prepare downloads the model once (about 5.3 MB, four files, each checked against a '
      + 'pinned sha256) and writes the default word table 肥鱼肥鱼 (the word itself plus three '
      + 'tolerance spellings); '
      + 'op=keywords replaces the word table (each word is given as 词=拼音, for example '
      + '肥鱼肥鱼=fei2 yu2 fei2 yu2 - the pinyin is what the model needs, see '
      + 'docs/wake-word.md); op=start '
      + 'starts the foreground listener, which keeps a notification with a 停止 button; op=stop '
      + 'ends it. What happens on a hit is onWake: overlay (the default) brings up **the floating '
      + 'ball**, whose face then says it is listening - the one-sentence window a hit already '
      + 'opened is exactly what the ball points at, so nothing else runs and no activity is pulled '
      + 'forward; app brings the app forward, which '
      + 'is also where a hit falls back when there is no ball (the switch is off, the overlay '
      + 'permission is missing, or the host is not up), so nothing goes silent either way. Two '
      + 'things to say plainly: the microphone is really on the whole time while it listens, and a '
      + 'short word does get false hits, so threshold is worth tuning on the real device.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status, prepare, keywords, start or stop',
      },
      words: {
        type: 'array',
        items: { type: 'string' },
        description:
          'For op=keywords (and for op=prepare, to override the default word): one "词=拼音" entry'
          + ' per word, for example "肥鱼肥鱼=fei2 yu2 fei2 yu2" or'
          + ' "小爱同学=xiao3 ai4 tong2 xue2". Tone numbers'
          + ' are what the model wants; pinyin already carrying tone marks is accepted as it is',
      },
      lines: {
        type: 'array',
        items: { type: 'string' },
        description:
          'For op=keywords: raw keyword lines in the model\'s own token form, for example'
          + ' "d à f éi y ú @大肥鱼" (the model\'s own keywords.txt shows this form). Use this only when the'
          + ' pinyin route cannot say what you mean',
      },
      threshold: {
        type: 'number',
        description: 'For op=start: how sure the spotter has to be before it fires (default 0.25)',
      },
      score: {
        type: 'number',
        description: 'For op=start: how strongly the keyword is boosted (default 1.5)',
      },
      onWake: {
        type: 'string',
        description:
          'For op=start: "overlay" (the default) wakes the floating ball\'s own voice input, "app" '
          + 'brings the app forward. Either way a hit falls back to the app when there is no ball '
          + '(its switch is off, the overlay permission is missing, or the host is not up). This is '
          + 'read once, when the listener starts, and is not stored: leaving it out means "overlay"',
      },
      vibrateMs: {
        type: 'integer',
        description: 'For op=start: how long to buzz on a hit (default 500, 0 for silent)',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args.op === 'status') {
        const info = await wakeWordInspect()
        return [
          `engine sherpa-onnx keyword spotting, model ${info.model}`,
          `model in ${info.directory}: ${info.present ? 'downloaded' : 'not downloaded yet'}`
            + (info.modelFiles ? ` (${info.modelFiles})` : ''),
          `watching for: ${info.keywordNames || 'nothing yet'}`,
          `microphone permission: ${info.permission ? 'granted' : `not granted - ${info.permission}`}`,
          `listener: ${info.listening ? 'up' : 'not running'}, ${info.hits} hit(s)`
            + (info.lastKeyword ? `, last was ${info.lastKeyword} at ${new Date(info.lastHitAt).toLocaleString()}` : ''),
          `voice chain: ${info.voiceActive
            ? 'resident (video mode) - keep talking, no wake word needed'
            : 'not resident - a hit or a tap on the ball buys one sentence'}`,
          // 视频模式里说的话投给哪一场 (2026-10-07): 定格的那个 id 与页面现在报上来的那个 id, 两个一起
          // 看才说得清"打开视频模式那句话与之后说的话落在同一场"这件事
          `video-mode voice target: ${info.videoTarget || 'not pinned'}`
            + ` (the GUI is showing: ${info.uiSession || 'nothing reported yet'})`,
          info.unknownTokens
            ? `these tokens are not in the model's table, so their lines would be dropped silently:`
              + ` ${info.unknownTokens}`
            : '',
          info.lastError ? `last problem: ${info.lastError}` : '',
        ].filter(Boolean).join('\n')
      }
      if (args.op === 'prepare') {
        const info = await wakeWordPrepare(args.words)
        return `the model is in ${info.directory} (${WAKEWORD_FILES.length} files, each checked`
          + ` against its pinned sha256; downloaded ${info.fetched.length} just now:`
          + ` ${info.fetched.join(', ') || 'nothing, it was already there'})`
          + (info.pruned.length
            ? `, removed ${info.pruned.length} file(s) from the other build: ${info.pruned.join(', ')}`
            : '')
          + ` and the table holds: ${info.keywordNames || 'nothing'}. op=start is what begins listening`
      }
      if (args.op === 'keywords') {
        const lines = await wakeWordLinesFor(args)
        const answer = await call('wakeword', { op: 'keywords', lines })
        return answer.written
          ? `the table now holds ${answer.keywords}`
          : `the table was not changed: ${answer.keywords || 'it is empty'}`
      }
      if (args.op === 'start') {
        const request = { op: 'start' }
        for (const key of ['threshold', 'score', 'onWake', 'vibrateMs']) {
          if (args[key] !== undefined) request[key] = args[key]
        }
        const answer = await call('wakeword', request)
        return `listening for ${answer.keywords}; the microphone is on until op=stop, and a hit`
          + ' shows up in op=status. A hit (or a tap on the ball) opens the cutting + recognition'
          + ' chain for exactly ONE sentence, and what you say lands in the ball conversation -'
          + ' reused while it is inside the hour, a new one once the hour is up; the'
          + ' chain closes again 10s after the last thing it heard. **Video mode is what keeps that'
          + ' chain resident** (lw_mode mode="video") - there is no resident-voice switch in the app'
      }
      if (args.op === 'stop') {
        const answer = await call('wakeword', { op: 'stop' })
        return `${answer.detail} (${answer.hits} hit(s) this run)`
      }
      throw new Error(`op has to be status, prepare, keywords, start or stop, not "${args.op}"`)
    },
  }),

  // ---- p 图那一条: 把一张图变成生图插件认的引用, 再把成图放进相册 ----
  //
  // 生图插件 (`@dickpy/dsh-imagegen`) 的 `edit_image` 只认一个引用对象 (attachment_id / media_type /
  // bytes / width / height), 而**屏幕上那张图没有这样一个对象**: 模型看到的是图块, 抄不到 id。所以
  // 中间那一步只有应用这一侧做得了 —— 它能把屏幕拍下来、把字节交给附件库, 再把库给的那份引用原样
  // 印出来。成图那条路反着走同样的道理: 引用里的字节只有在宿主这一侧读得出来, 而"进相册"只有应用
  // 那一侧做得到 (媒体库是它的表)
  defineTool({
    name: 'lw_image',
    description:
      'Move a picture between this phone and the image tools. **Two jobs, told apart by op.** '
      + 'op=ref turns a picture into the exact reference object the image-generation tools take: '
      + 'pass that whole object, unchanged, as their source image. It photographs one of this '
      + "phone's screens (the person's own screen, displayId 0, unless you name another), or reads "
      + 'a picture file, or takes the newest picture already in this conversation (one the person '
      + 'sent, or the screenshot that was attached automatically when they pointed at their '
      + 'screen) - and answers with the reference. Nothing else here can make what is on a screen '
      + 'into something an image edit will accept, so this is the step between "look at this '
      + 'picture" and "change it". op=album takes a finished picture - the reference a generation '
      + "or an edit answered with, or a file - puts a copy in the phone's own album "
      + '(Pictures/Luwi, where the gallery app lists it), and opens it so the person is looking '
      + 'at the result; it answers with where the album copy landed. This tool does not change a '
      + 'picture itself: the configured image service does that, through the generation tools.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'ref: turn a picture into the reference the image tools take. album: put a '
          + "finished picture into the phone's album and show it",
        enum: ['ref', 'album'],
      },
      path: {
        type: 'string',
        description: 'For op=ref: a picture file to stage instead of photographing a screen '
          + '(relative paths start at the work area). For op=album: the finished picture to file '
          + 'when you have a file rather than an image reference',
      },
      from: {
        type: 'string',
        description: 'For op=ref: where the picture comes from. "screen" (the default, and what '
          + '"this picture" almost always means) photographs a screen. "conversation" takes the '
          + 'newest picture already in this conversation instead - use that when the person sent '
          + 'the picture themselves rather than pointing at their screen. A path overrides both',
        enum: ['screen', 'conversation'],
      },
      displayId: {
        type: 'integer',
        description: "For op=ref from a screen: which screen to photograph. Defaults to 0, the "
          + "person's own screen, which is the picture they mean by \"this\" while holding the "
          + 'phone; name a virtual screen\'s id only when the picture to change is on that screen',
      },
      image: {
        type: 'object',
        additionalProperties: true,
        description: 'For op=album: the image reference a generate_image / edit_image / '
          + 'get_image_generation_task result carried - pass that entire object unchanged',
      },
      name: {
        type: 'string',
        description: 'For op=album: the file name to give the album copy. Defaults to the '
          + "reference's own name, or a timestamped one",
      },
      open: {
        type: 'boolean',
        description: 'For op=album: open the picture once it is filed, so the person sees the '
          + 'result. Defaults to true; set false only when they asked for it to be saved and said '
          + 'nothing about looking at it',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args, exec) {
      if (args.op === 'ref') return await stagePictureForImageTools(args, exec)
      if (args.op === 'album') return await fileFinishedPicture(args)
      throw new Error(`op has to be ref or album, not "${args.op}"`)
    },
  }),

  // ---- 2.5.1 批次 9: LW 插件 (P0 / P1) ----
  //
  // 这一条是**管理**插件的那张脸 (装 / 启用 / 停用 / 卸 / 审计), 而插件自己的工具是这份模块之外的
  // 东西: 它们由 startPluginTools 按应用那一侧的快照动态登记 (`<toolPrefix>_<动作>`), 于是模型眼里
  // 插件工具与 lw_* 长得一样。**装一份包不等于启用它**: 能力要人在设置页逐条勾, 然后点启用

  simpleTool(
    'lw_plugin',
    'The LW plugins installed on this phone: packages that add device-side abilities to Luwi'
    + ' without rebuilding it (one JSON manifest each, one package per plugin, living in'
    + ' DSH_HOME/plugins). Their tools are registered on this host under their own prefix -'
    + ' <toolPrefix>_<action> - so once a plugin is enabled they look like any lw_* tool. op=list'
    + ' says what is installed, its state and how many of its capabilities are allowed; op=read one'
    + ' plugin in full (它的工具、要的每一条能力、发布者与指纹); op=install takes a path to an'
    + ' unzipped package directory or a .lwp file and puts it in - **that does not enable it**,'
    + ' or an https link to a .lwp (op=install with url: it downloads first and then goes through'
    + ' exactly the same checks - signature, per-file hashes, version, tool prefix, declared'
    + ' capabilities -, so a link is never a shortcut past anything),'
    + ' and a package whose capabilities the user has not allowed yet can do nothing; op=enable /'
    + ' op=disable turn one on or off (a companion plugin is an installed APK that Luwi'
    + ' binds, and a package that says where its APK lives brings it along as it installs);'
    + ' op=uninstall asks before it deletes anything (keepData keeps the plugin\'s own'
    + ' settings directory); op=audit prints the recent capability calls, refused ones included.'
    + ' **A plugin is somebody else\'s code**: tell the user what it wants before enabling it, and'
    + ' never turn on a capability they did not ask for. Capability names look like device.read,'
    + ' notify.post, session.post (that one lets it drive the conversation).',
    'plugin',
    {
      op: {
        type: 'string',
        required: true,
        description: 'what to do with the plugin list',
        enum: ['list', 'read', 'install', 'enable', 'disable', 'uninstall', 'audit'],
      },
      id: {
        type: 'string',
        description: 'For read / enable / disable / uninstall / audit: the plugin\'s id, exactly as'
          + ' op=list prints it',
      },
      path: {
        type: 'string',
        description: 'For op=install: an absolute path to an unzipped package directory (the one'
          + ' holding plugin.json) or to a .lwp file',
      },
      url: {
        type: 'string',
        description: 'For op=install: an https link to a .lwp instead of a local path. Only https,'
          + ' and a redirect to http aborts. Give either url or path, never both',
      },
      keepData: {
        type: 'boolean',
        description: 'For op=uninstall: keep the plugin\'s own settings directory. Defaults to'
          + ' false; ask the user which they want',
      },
      lines: {
        type: 'integer',
        description: 'For op=audit: how many recent lines to print. Defaults to 20',
      },
    },
  ),
]

/** One request, one response: the app answers a single line and closes the connection */
async function call(method, params = {}) {
  const endpoint = process.env[ENDPOINT_VARIABLE]
  const token = process.env[TOKEN_VARIABLE]
  if (!endpoint || !token) {
    throw new Error(
      `no privileged channel: ${ENDPOINT_VARIABLE} and ${TOKEN_VARIABLE} are unset,`
      + ' which means this host was not started by Luwi',
    )
  }
  const answer = await request(endpoint, JSON.stringify({ method, token, ...params }))
  let parsed
  try {
    parsed = JSON.parse(answer)
  } catch (error) {
    throw new Error(`the app answered with something that is not JSON: ${answer.slice(0, 200)}`)
  }
  if (parsed.ok !== true) throw new Error(parsed.error ?? 'the app reported no reason')
  return parsed.result
}

/** Send one line and resolve with the first line that comes back */
function request(endpoint, line) {
  const split = endpoint.lastIndexOf(':')
  const host = endpoint.slice(0, split)
  const port = Number(endpoint.slice(split + 1))
  return new Promise((resolve, reject) => {
    const socket = connect({ host, port })
    let buffer = ''
    let settled = false
    socket.setEncoding('utf8')
    socket.on('connect', () => socket.write(`${line}\n`))
    socket.on('data', (chunk) => {
      buffer += chunk
      const end = buffer.indexOf('\n')
      if (end < 0) return
      settled = true
      socket.end()
      resolve(buffer.slice(0, end))
    })
    socket.on('error', (error) => {
      settled = true
      reject(new Error(`could not reach the app's privileged channel at ${endpoint}: ${error.message}`))
    })
    socket.on('close', () => {
      if (settled) return
      settled = true
      reject(new Error(`the app closed the channel at ${endpoint} without answering`))
    })
  })
}

/** A tool's arguments as a request body, without the ones the caller left out or that are ours */
function drop(args, ...ours) {
  const skipped = new Set(ours)
  return Object.fromEntries(
    Object.entries(args ?? {}).filter(([key, value]) => value !== undefined && !skipped.has(key)),
  )
}

/**
 * How long something is to be held, as a number of milliseconds
 *
 * A string rather than a number because a bare number does not say what unit it is in: "1s",
 * "500ms", "1.5s" all work, and so do the three names above. A number that arrives anyway is read
 * as seconds, which is what "1" nearly always means
 *
 * @param value what the caller passed as hold, absent when it left it out
 * @param fallback what to answer when it did, which is "a plain press" for both tools
 */
function holdMs(value, fallback = 0) {
  if (value === undefined || value === null || value === '') return fallback
  const text = String(value).trim().toLowerCase()
  const names = Object.keys(HOLD_NAMES).join(' / ')
  if (Object.hasOwn(HOLD_NAMES, text)) return HOLD_NAMES[text]
  const parsed = /^(\d+(?:\.\d+)?)\s*(ms|s)?$/.exec(text)
  if (!parsed) {
    throw new Error(`hold has to be a duration like "1s" or "500ms", or one of ${names}`
      + ` - not ${JSON.stringify(value)}`)
  }
  const ms = Math.round(Number(parsed[1]) * (parsed[2] === 'ms' ? 1 : 1000))
  if (ms > MAX_HOLD_MS) {
    throw new Error(`hold of ${ms}ms is longer than this tool allows (${MAX_HOLD_MS}ms): the screen`
      + ' is held for the whole of it and nothing else can be done meanwhile')
  }
  return ms
}

/**
 * 一个"做一件事, 把答案原样念出来"的工具
 *
 * 1.0.2 加的这批能力里大部分都是这个样子: 名字、一段给模型的说明、几个参数, 然后一次桥调用。说明在
 * 这里统一生成, 免得二十个工具各写一遍同样的几句
 *
 * 答案由应用那一侧写好放在 `result.text` 里 —— **它才是唯一知道真的发生了什么的那一边** (有没有
 * 权限、设备认不认、命令的退出码), 插件不该自己编一句
 *
 * @param name 工具名, `lw_` 开头
 * @param description 模型看到的说明
 * @param method 桥上的方法名
 * @param parameters 参数表, 照 `defineTool` 的形状
 */
function simpleTool(name, description, method, parameters = {}) {
  return defineTool({
    name,
    description,
    parameters,
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return answerOf(await call(method, drop(args)))
    },
  })
}

/** 应用那一侧写好的答案, 兜底是让人能读的 JSON —— 正常不该走到那一条 */
function answerOf(result) {
  if (typeof result === 'string') return result
  if (result && typeof result.text === 'string' && result.text.length > 0) return result.text
  return JSON.stringify(result)
}

/**
 * The `hold` parameter of an acting tool, said once and shared by both of them
 */
function holdParameter(tail) {
  const names = Object.entries(HOLD_NAMES).map(([name, ms]) => `${name} (${ms}ms)`).join(', ')
  return {
    type: 'string',
    description: 'How long to hold it down: a duration like "1s" or "500ms", or one of '
      + `${names}. Android reads anything held 500ms or more as a long press, which is why the`
      + ' names start just above that line - short is the ordinary long press. Leave it out for a'
      + ` plain, instant press. ${tail}`,
  }
}

/** A duration as shortly as it can be said back: 600ms, 1.5s, 8s */
function formatMs(ms) {
  return ms >= 1000 ? `${Number((ms / 1000).toFixed(2))}s` : `${ms}ms`
}

/** How long a press was held, as a clause, or an empty string when it was not held */
function holdSaid(result) {
  const ms = result.holdMs ?? 0
  if (!(ms > 0)) return ''
  return `, held for ${formatMs(ms)}`
}

/**
 * The caller's own words about a step, put on the first line of its report
 *
 * It goes into the tool result rather than only into a log, because the result is what the session
 * keeps: a person reading the conversation afterwards sees why each press happened, right where it
 * happened
 */
function said(report, note) {
  const text = (note ?? '').trim()
  if (text === '') return report
  const end = report.indexOf('\n')
  return end < 0
    ? `${report} - "${text}"`
    : `${report.slice(0, end)} - "${text}"${report.slice(end)}`
}

/** The channel's own report, which says whether the device is reachable at all */
function formatProbe(result) {
  const channel = result.channel ?? {}
  const lines = []
  if (channel.connected === true) {
    const identity = channel.uid === 0 ? 'root' : `uid ${channel.uid}`
    lines.push(
      `privileged channel: ${channel.backend} as ${identity} (pid ${channel.pid}, service v${channel.version})`,
    )
  } else {
    lines.push(`privileged channel: not connected${channel.error ? ` - ${channel.error}` : ''}`)
  }

  const input = result.input ?? {}
  if (input.available !== true) {
    lines.push(`input devices: unreadable (${input.reason ?? 'no reason reported'})`)
  } else {
    lines.push(`input devices: ${input.deviceCount}`)
    const touch = input.touchscreen
    if (touch && typeof touch === 'object') {
      lines.push(
        `touchscreen: ${touch.path} "${touch.name}"`
        + ` x=[${touch.x[0]},${touch.x[1]}] y=[${touch.y[0]},${touch.y[1]}]`
        + ` protocolB=${touch.protocolB} direct=${touch.direct} btnTouch=${touch.touchKey}`,
      )
    } else {
      lines.push('touchscreen: none recognised')
    }
  }
  lines.push(...usersLines(result.users))
  return withJson(lines, result)
}

/**
 * The device's Android users, which is only worth saying when there is more than one
 *
 * One user is every phone as it comes, and saying so in every probe would be noise. A second one is
 * what a cloned app is - the same package installed again with its own data - so that is the case
 * the answer exists for: it is the number lw_launch has to be given to start the clone rather than
 * the app it was cloned from
 */
function usersLines(users) {
  const found = Array.isArray(users) ? users : []
  if (found.length < 2) return []
  const named = found.map(
    (user) => `${user.id} (${user.name}${user.running === false ? ', not running' : ''})`,
  )
  return [
    `users: ${named.join(', ')} - an app installed for two of these is two copies with their own`
    + ' data, so name the second one as lw_launch\'s user to start the copy rather than the original',
  ]
}

/** The screens that exist, which is the list every other call's displayId comes from */
function formatScreens(result) {
  const screens = result.screens ?? []
  const lines = []
  const primary = result.primary
  if (primary && typeof primary.displayId === 'number') {
    lines.push(
      `- displayId ${primary.displayId} "${primary.label}" `
      + `${primary.width}x${primary.height} at ${primary.dpi}dpi, the phone's own screen: always `
      + 'there, and no preview of it exists',
    )
  }
  if (screens.length === 0) {
    lines.push('no virtual screen exists yet, create one with lw_screen_create')
  } else {
    lines.push(`${screens.length} virtual screen${screens.length === 1 ? '' : 's'}`)
    for (const screen of screens) {
      const shown = screen.displayId === result.selected ? ', shown in the preview on the phone' : ''
      const paused = screen.acceptsControl === false ? ', control paused by the user' : ''
      lines.push(
        `- displayId ${screen.displayId} "${screen.label}" `
        + `${screen.width}x${screen.height} at ${screen.dpi}dpi${shown}${paused}`,
      )
    }
  }
  // 通道不通才说那句话: `lastError` 是**上一次**出错留下的, 通道已经连上时它还在, 念出来就是一句
  // 已经不成立的话 (2026-10-04 在模拟器上就是这么误导的)
  const channel = result.channel ?? {}
  if (channel.connected !== true) {
    lines.push(
      'the screen side is not connected right now: '
      + `${channel.error || result.lastError || 'no reason reported'}`,
    )
  }
  return withJson(lines, result)
}

/** What was just made */
function formatCreated(result) {
  if (result.created !== true) {
    return `the device refused to create a screen: ${result.error || 'no reason reported'}`
  }
  return withJson(
    [`created "${result.label}" as displayId ${result.displayId}, and the phone is showing it`],
    result,
  )
}

/** What a screen is now, after being given another shape */
function formatResized(result) {
  if (result.resized !== true) {
    return `nothing was resized: ${result.error || `no screen with displayId ${result.displayId}`}`
  }
  const how = result.swapped === true ? 'turned a quarter turn' : 'resized'
  return withJson(
    [
      `displayId ${result.displayId} "${result.label}" is now ${result.width}x${result.height}`
      + ` at ${result.dpi}dpi (${how}): anything on it that follows its screen has been laid out`
      + ' again, so take a new picture rather than reusing coordinates from the old one',
    ],
    result,
  )
}

/** What was just given back */
function formatReleased(result, note) {
  const lines = result.released === true
    ? [`released displayId ${result.displayId}`]
    : [`nothing was released: ${result.error || `no screen with displayId ${result.displayId}`}`]
  return said(withJson(lines, result), note)
}

/** One key press, said the way Android names it and the way the caller asked for it */
function formatPressed(result, note) {
  const how = result.longPress === true ? 'long-pressed' : 'pressed'
  return said(
    withJson(
      [
        `${how} ${result.key} (keycode ${result.code}) on displayId ${result.displayId}`
        + ` "${result.label}"${holdSaid(result)}`,
      ],
      result,
    ),
    note,
  )
}

/** What typing did, and what the field says now */
function formatTyped(result, note) {
  const where = result.via === 'keys'
    ? ' as key presses, because that screen reports no text field, so it went wherever focus is'
    : ` into ${fieldName(result.field)}`
  const count = `${result.written} character${result.written === 1 ? '' : 's'}`
  const lines = [
    `typed ${count}${where} on displayId ${result.displayId} "${result.label}"`,
  ]
  if (result.password === true) {
    lines.push('the field hides what is in it, so it is not read back')
  } else if (typeof result.text === 'string' && result.text !== '') {
    lines.push(`the field now reads ${JSON.stringify(result.text)}`)
  }
  return said(withJson(lines, result), note)
}

/** A field, named the way it says itself and placed where it is */
function fieldName(field) {
  if (!field || !field.className) return 'the field'
  const said = field.text || field.description
  return `${field.className}${said ? ` "${said}"` : ''} at ${formatRect(field.bounds)}`
}

/** What a gesture was delivered as, which is all a caller can know without looking */
function formatGesture(verb, result, note) {
  if (result.tapped === true || result.swiped === true) {
    const lines = result.swiped === true
      ? [`${verb} displayId ${result.displayId} over ${result.durationMs}ms`]
      : [`${verb} displayId ${result.displayId} at ${result.x},${result.y}${holdSaid(result)}`]
    // 熄屏时注入是**静默失效**的: 平台收下了事件, 界面没有任何反应, 所以这句话必须说在明面上
    if (result.screen && result.screen !== 'on') {
      lines.push(`careful: that screen is ${result.screen}, and a sleeping screen does not take`
        + ' injected presses - the gesture was delivered to a display that is not listening.'
        + ' Send WAKEUP with lw_key first if something was supposed to happen.')
    }
    return said(withJson(lines, result), note)
  }
  return said(withJson([`the device did not report the gesture as delivered`], result), note)
}

/** 这张图是哪一块, 以及图上的点怎么换算回屏幕上的点 */
function pictureNote(picture) {
  const left = picture.left ?? 0
  const top = picture.top ?? 0
  const scale = picture.scale ?? 1
  const partial = left !== 0 || top !== 0
  const where = partial ? `the part of the screen at ${left},${top}` : 'the whole screen'
  let how
  if (scale > 1 && partial) {
    how = `multiply a coordinate on the picture by ${scale.toFixed(2)}, then add ${left},${top}`
  } else if (scale > 1) {
    how = `multiply a coordinate on the picture by ${scale.toFixed(2)} to get the screen coordinate`
  } else if (partial) {
    how = `add ${left},${top} to a coordinate on the picture to get the screen coordinate`
  } else {
    how = 'coordinates on it are screen coordinates already'
  }
  return { where, how }
}

/** Where the picture went, and how to read coordinates off it */
function formatScreenshot(result) {
  if (!result.path) {
    // 没有图的原因现在分两种, 而它们要做的下一步不一样: 屏没了 (再建一块) 与屏在而还没画出来
    // (在它上面起个应用再拍) —— 理由由设备那一侧写, 这里原样念
    return `no picture was written: ${result.error || 'no reason reported'}`
  }
  const shots = Array.isArray(result.shots) ? result.shots : []
  if (shots.length > 1) {
    const span = ((result.spanMs ?? 0) / 1000).toFixed(1)
    // 要说的是"量到的间隔", 不是"要的间隔": 一次截图本身要两三百毫秒, 所以比它小的那个数做不到。
    // 判据拿**真实跨度**与**要的跨度**比, 而且要的是"没有比要的更长" —— 反着写永远成立 (设备慢下来
    // 只会让跨度更大, 那正是"做不到"的样子)
    const asked = result.intervalMs ?? 0
    const kept = (result.spanMs ?? 0) <= asked * (shots.length - 1) * 1.1
    const first = pictureNote(shots[0].picture ?? {})
    const lines = [
      `took ${shots.length} pictures of displayId ${result.displayId} "${result.label}", one every`
      + ` ${asked} ms as far as this device keeps up - ${span} s from the first to the last.`
      + ` Each is ${first.where}, and on each one ${first.how}: separate files, nothing`
      + ' overwritten, so read them in order - the screen moved between them, which is the whole'
      + ' point of taking more than one.',
    ]
    if (!kept) {
      lines.push(
        'They are further apart than that in practice, because one capture takes a couple of'
        + ' hundred milliseconds here: the times below are what happened, not what was asked for.',
      )
    }
    shots.forEach((shot, index) => {
      const note = pictureNote(shot.picture ?? {})
      const at = ((result.offsets?.[index] ?? 0) / 1000).toFixed(1)
      // 一张自己那一句只在它与第一张不同时才写: 同一块屏同一个预算, 重复十二遍是纯噪音
      const own = note.how === first.how ? '' : `, on it ${note.how}`
      lines.push(
        `  ${index + 1}. at +${at}s ${shot.path} - ${(shot.bytes / 1024).toFixed(1)} KB${own}`,
      )
    })
    if (result.sheet?.path) {
      const twin = result.sheet.fullPath && result.sheet.fullPath !== result.sheet.path
        ? `; its full-size version is ${result.sheet.fullPath}`
        : ''
      lines.push(
        `All ${shots.length} are also laid out in one picture, ${result.sheet.path}`
        + ` (${result.sheet.columns}x${result.sheet.rows} cells, in the order they were taken,`
        + ` ${(result.sheet.bytes / 1024).toFixed(1)} KB${twin}). Read that for where the screen`
        + ' went; open a numbered file above when you need to read what is written on it.',
      )
    }
    if (result.sheetError) lines.push(`no grid was made: ${result.sheetError}`)
    const twin = shots[0].fullPath
    if (twin) {
      lines.push(
        `Each one also left its full-size twin beside it (the first is ${twin}) - the two belong`
        + ' together, so deleting a picture means deleting both.',
      )
    }
    return withJson(lines, result)
  }
  const kilobytes = (result.bytes / 1024).toFixed(1)
  const picture = result.picture ?? {}
  const note = pictureNote(picture)
  const scaled = (picture.scale ?? 1) > 1
  const size = scaled
    ? `${picture.width}x${picture.height} px, so ${note.how}`
    : `the same size as the screen (${note.where})`
  // 一次截图落两个文件: 全尺寸那份给人看, 缩过的那份给模型。哪一份叫什么由设备那一侧说 (它给
  // fullPath), 这里不猜文件名 —— `.model` 那个后缀猜错过一次。**只要了一块时另外说**: 那时
  // 全尺寸那份是整屏, 不是这张图的"孪生兄弟", 把它说成一对会让人以为删一张就够
  const partial = (picture.left ?? 0) !== 0 || (picture.top ?? 0) !== 0
  const other = result.fullPath && result.fullPath !== result.path ? result.fullPath : ''
  const pair = other
    ? partial
      ? `; the whole screen it was cut from is ${other}, so that file is a different picture`
      : `; its full-size twin is ${other} - the two belong together, so deleting the screenshot`
        + ' means deleting both'
    : ''
  const lines = [
    `displayId ${result.displayId} "${result.label}" captured to ${result.path}`
    + ` (screen ${result.width}x${result.height}, picture ${size}; ${kilobytes} KB, overwritten`
    + ` by the next capture of this screen${pair})`,
  ]
  // 要了网格而只拍了一张 (或只拍了这一张): 理由必须念出来, 否则答案里少了一件它问过的事
  if (result.sheetError) lines.push(`no grid was made: ${result.sheetError}`)
  return withJson(lines, result)
}

/** What a screen says about itself, which is the answer that saves measuring anything */
function formatUi(result) {
  if (result.enabled !== true) {
    return 'the phone has the accessibility service off, so it will not report what is on a'
      + ' screen. Turn it on in Luwi\'s settings, under 无障碍. Until then use lw_screenshot'
      + ' and read the picture.'
  }
  if (result.error) {
    return `displayId ${result.displayId}: ${result.error}`
  }
  const nodes = result.nodes ?? []
  // A screen that draws its own picture does not report nothing: it reports one full-screen
  // surface with no text and nothing to press, which reads like a control to a caller that does
  // not look closely. What matters is whether anything here is worth acting on
  const usable = nodes.filter((node) => node.clickable === true
    || node.editable === true
    || node.checkable === true
    || node.scrollable === true
    || (node.text ?? '') !== '')
  if (usable.length === 0) {
    const surfaces = nodes
      .map((node) => `${node.className || 'control'}${node.description ? ` (${node.description})` : ''}`)
      .join(', ')
    return `displayId ${result.displayId} "${result.label}" draws its own picture: the device`
      + ` reports ${nodes.length} control${nodes.length === 1 ? '' : 's'} with no text and nothing`
      + ` to press${surfaces ? ` - ${surfaces}` : ''}. There is no name to press here, so use`
      + ' lw_screenshot and coordinates on this screen.'
  }
  const lines = [
    `displayId ${result.displayId} "${result.label}" ${result.width}x${result.height}, `
    + `${result.package || 'unknown package'}, ${usable.length} readable controls.`
    + ' The rectangles are in the screen\'s own pixels, the same ones lw_tap and lw_swipe use.',
  ]
  const shown = usable.slice(0, MAX_UI_NODES)
  for (const node of shown) lines.push(describeNode(node))
  if (usable.length > shown.length) {
    lines.push(
      `… ${usable.length - shown.length} more controls are not listed, they are off screen or far`
      + ' down the list; scroll the screen to bring what you need into view',
    )
  }
  lines.push('Press one of these by name with lw_tap(text="..."), or by a point from its rectangle.')
  return lines.join('\n')
}

/** One control, in one line: what it is, what to call it, and where it is */
function describeNode(node) {
  const indent = '  '.repeat(Math.min(node.depth ?? 0, 8))
  const parts = [node.className || 'control']
  if (node.text) parts.push(JSON.stringify(node.text))
  if (node.description) parts.push(`desc=${JSON.stringify(node.description)}`)
  if (node.viewId) parts.push(`id=${node.viewId}`)
  const flags = []
  if (node.clickable) flags.push('clickable')
  if (node.scrollable) flags.push('scrollable')
  if (node.editable) flags.push('editable')
  if (node.checkable) flags.push(node.checked ? 'checked' : 'checkable')
  if (flags.length) parts.push(`[${flags.join(' ')}]`)
  // A control that is not clickable is usually a label inside a row that is, so the row's
  // rectangle is what a point would have to land in
  if (node.target && !sameRect(node.target, node.bounds)) {
    parts.push(`at ${formatRect(node.bounds)} press row ${formatRect(node.target)}`)
  } else {
    parts.push(`at ${formatRect(node.bounds)}`)
  }
  return indent + parts.join(' ')
}

/** What a launch turned out to be, with the command's own words kept */
function formatLaunched(result, note) {
  const target = result.package || result.component
  const whose = result.user > 0 ? ` as user ${result.user}` : ''
  const from = result.asked ? ` (from ${JSON.stringify(result.asked)})` : ''
  const lines = result.started === true
    ? [`launched ${target}${from}${whose} on displayId ${result.displayId} "${result.label}"`]
    : [
      `the launch of ${target}${from}${whose} on displayId ${result.displayId} did not report`
      + ` success (am exited ${result.code}); what it said follows`,
    ]
  const output = (result.output ?? '').trim()
  if (output) lines.push(output)
  // 起完的自查: `am start` 报成功不等于窗口落在这块屏上, 而"没有窗口"这件事以前只有模型自己去发现
  // (windowOnDisplay 为 null 表示这次查不了: 无障碍没开, 窗口列表读不到)
  if (result.windowOnDisplay === false || result.windowOnDisplay === null) {
    lines.push(result.windowNote
      || `displayId ${result.displayId} has no window on it after the launch`)
    lines.push('Read the screen back with lw_ui before acting on it.')
  } else {
    lines.push('Call lw_ui to see what the screen says now.')
  }
  return said(lines.join('\n'), note)
}

/** What the launcher's apps are, as one line per app */
function formatApps(result) {
  const where = `user ${result.user}`
  if (result.error) return `could not list the apps for ${where}: ${result.error}`
  const apps = result.apps ?? []
  const wanted = result.query ? ` matching ${JSON.stringify(result.query)}` : ''
  if (apps.length === 0) {
    return result.query
      ? `no app for ${where} matches ${JSON.stringify(result.query)} (of ${result.launchable}` +
        ' that can be started) - try a shorter query, or no query at all to see them'
      : `nothing installed for ${where} has a launcher entry, so there is nothing here to start`
  }
  const lines = [
    `${apps.length} app${apps.length === 1 ? '' : 's'}${wanted} for ${where}` +
      (result.truncated ? ' (the list was cut short, narrow it with query)' : '') + ':',
  ]
  for (const app of apps) lines.push(`  ${app.label} - ${app.package}`)
  lines.push('Start one with lw_launch(package="..."), which takes the package or that name.')
  return lines.join('\n')
}

/** What pressing by name did, which is the one call that can be ambiguous */
function formatNamedTap(result, note) {
  return said(namedTapReport(result), note)
}

/** What reading a screen's pixels found */
function formatOcr(result) {
  const where = `displayId ${result.displayId}`
  if (result.error) return `could not read ${where}: ${result.error}`
  const lines = result.lines ?? []
  const cost = `${result.captureMs ?? 0}ms to capture, ${result.detMs ?? 0}ms to find the text, `
    + `${result.recMs ?? 0}ms per line, on ${result.backend}`
  const asleep = asleepLine(result)
  if (lines.length === 0) {
    return `no text was found on ${where} (${cost}). The screen may be blank or hold only`
      + ' pictures, and lw_screenshot shows it as it is.'
  }
  const report = [
    `${lines.length} line${lines.length === 1 ? '' : 's'} read on ${where} (${cost}). The`
    + ' rectangles are in the screen\'s own pixels, the same ones lw_tap and lw_swipe use: press a'
    + ' line with lw_tap(x=..., y=...) at its centre.',
  ]
  if (asleep) report.unshift(asleep)
  for (const line of lines) {
    const box = line.box ?? []
    report.push(`  [${box.join(',')}] ${JSON.stringify(line.text)}`
      + ` score ${(line.score ?? 0).toFixed(2)} centre ${(line.center ?? []).join(',')}`)
  }
  report.push('A low score means it was probably not text - an icon, a picture or a stray mark.')
  report.push('Press one of these by name with lw_tap(text="...") and no coordinate at all, or by'
    + ' a point from its rectangle; pressing by name lands on a random point inside the rectangle'
    + ' rather than its exact centre.')
  return report.join('\n')
}

/**
 * The warning for a screen that is not awake, or null when it is
 *
 * A sleeping display still hands out the last frame it drew and still swallows injected touches,
 * both without saying so: what comes back reads like a live screen that ignores presses
 */
function asleepLine(result) {
  const state = result.screen
  if (!state || state === 'on') return null
  return `Careful: this screen is ${state}, and a sleeping screen goes on showing its last frame`
    + ' while ignoring injected presses. What is listed below may no longer be on the screen, and'
    + ' none of it can be pressed. Wake it first with lw_key(key="WAKEUP") and read it again.'
}

/**
 * Press a name that exists only as pixels
 *
 * Reading is a guess, so it never guesses twice: an exact match wins, otherwise a line that
 * contains the name, and more than one candidate presses nothing at all
 */
async function tapByReading(displayId, text, hold = 0) {
  const result = await call('ocr', { displayId })
  if (result.error) return `the screen could not be read: ${result.error}`
  if (result.screen && result.screen !== 'on') {
    return `the screen is ${result.screen}, so nothing was pressed: a sleeping screen keeps showing`
      + ' its last frame but does not take a press. Send WAKEUP with lw_key first, then try again.'
  }
  const lines = result.lines ?? []
  const needle = String(text).trim()
  const exact = lines.filter((line) => String(line.text ?? '').trim() === needle)
  const candidates = exact.length > 0
    ? exact
    : lines.filter((line) => String(line.text ?? '').includes(needle))
  if (candidates.length === 0) {
    return lines.length === 0
      ? 'reading the screen found no text at all'
      : `reading the screen found no line saying ${JSON.stringify(text)} among the ${lines.length}`
        + ' lines it could read; call lw_ocr to see them'
  }
  if (candidates.length > 1) {
    return `${candidates.length} lines on the screen say that, so nothing was pressed: `
      + candidates.map((line) => `${JSON.stringify(line.text)} at [${(line.box ?? []).join(',')}]`)
        .join(', ')
      + '. Press the one you meant with lw_tap(x=..., y=...) at its centre.'
  }
  const only = candidates[0]
  const point = scatterInside(only.box ?? [])
  const pressed = await call('tap', { displayId, x: point.x, y: point.y, holdMs: hold })
  return `pressed the line ${JSON.stringify(only.text)} (read off the screen with score`
    + ` ${(only.score ?? 0).toFixed(2)}) at ${point.x},${point.y} on displayId`
    + ` ${pressed.displayId}, a point drawn inside its rectangle [${(only.box ?? []).join(',')}]`
    + ` rather than its exact centre${hold > 0 ? `, holding it for ${formatMs(hold)}` : ''}`
}

/**
 * A point inside a rectangle, drawn at random rather than being the centre
 *
 * Two reasons. The centre of a wide row is often the label inside it rather than the part that
 * takes the press, while anywhere in the row works. And a tool that lands on the same coordinate
 * every single time is a pattern: a game that watches its own input can tell it apart from a
 * person, and a person never taps the same pixel twice
 *
 * The draw is confined to the middle of the rectangle - a fifth of the way in from each edge - so
 * it stays clear of the border and of whatever sits next to the row
 */
function scatterInside(box) {
  const [left, top, right, bottom] = box
  const insetX = (right - left) * SCATTER_INSET
  const insetY = (bottom - top) * SCATTER_INSET
  const x = Math.round(left + insetX + Math.random() * Math.max(0, right - left - 2 * insetX))
  const y = Math.round(top + insetY + Math.random() * Math.max(0, bottom - top - 2 * insetY))
  return { x, y }
}

/** The report itself, before the caller's own note is put on it */
function namedTapReport(result) {
  const matches = result.matches ?? []
  // clicked 而不是 outcome: 无障碍动作被拒之后那一根按住的手指算数, 见 lw_tap 里的注释
  if (result.clicked === true) {
    const hit = matches[0] ?? {}
    const how = result.via === 'finger'
      ? 'a touch at its centre, because it does not take an accessibility action'
      : result.long === true ? 'its own long-click action' : 'its own click action'
    return `pressed ${nameOf(hit)} on displayId ${result.displayId} by ${how}${holdSaid(result)}`
  }
  if (result.outcome === 'ambiguous') {
    return [
      `${matches.length} controls on displayId ${result.displayId} say that, so nothing was pressed:`
      + ' this tool does not guess which one you meant. Pick one from this list and press it by a'
      + ' point inside its rectangle with lw_tap(x=..., y=...), or call lw_ui and name a control'
      + ' that only appears once.',
      ...matches.map((node) => `- ${nameOf(node)} at ${formatRect(node.bounds)}`
        + (node.target && !sameRect(node.target, node.bounds)
          ? `, the row that takes the press is ${formatRect(node.target)}`
          : '')),
    ].join('\n')
  }
  if (result.outcome === 'none') {
    return `nothing matched: ${result.error || 'no reason reported'}. Call lw_ui to see what the`
      + ' screen actually says.'
  }
  if (result.outcome === 'unavailable') {
    return result.error || 'the accessibility service is off, so nothing can be pressed by name'
  }
  return `the press did not go through: ${result.error || result.outcome}`
}

/** How a control is named back to a caller, preferring the text it was found by */
function nameOf(node) {
  const label = node.text || node.description
  const id = node.viewId ? ` id=${node.viewId}` : ''
  return `${node.className || 'control'}${id} ${label ? JSON.stringify(label) : '(no text)'}`
}

/** A rectangle as it is easiest to read, which is also how a point is picked out of it */
function formatRect(rect) {
  if (!Array.isArray(rect) || rect.length < 4) return '[?]'
  return `[${rect[0]},${rect[1]},${rect[2]},${rect[3]}]`
}

function sameRect(a, b) {
  return Array.isArray(a) && Array.isArray(b) && a.every((value, index) => value === b[index])
}

/** A report a model can read, with the structured answer kept underneath it */
function withJson(lines, result) {
  return [...lines, '', JSON.stringify(result, null, 2)].join('\n')
}

/* ------------------------------------------------------------------ on-device speech */

/**
 * dsh's voice input records in the page (getUserMedia + MediaRecorder) and hands the host a
 * canonical 16 kHz mono PCM16 WAV to transcribe. The official local provider wants
 * sherpa-onnx-node, and that package ships native addons for darwin / linux / win only - there is
 * no android-arm64 build to load - so this host registers a provider that hands the audio back to
 * the app: the APK links sherpa-onnx statically and answers the `speech` channel method.
 *
 * The model is not in the APK (SenseVoice int8 is around 240 MB) and is not in this repository:
 * it is downloaded into the app's private directory on demand. huggingface.co cannot be reached
 * from this phone, so the hf-mirror copy is tried first. Both files are checked against the sha256
 * the official voice-input bundle publishes, so a mirror cannot quietly hand over something else.
 */
const SPEECH_PROVIDER_ID = 'lw-native'

const SPEECH_LANGUAGES = ['auto', 'zh', 'en', 'yue', 'ja', 'ko']

const SPEECH_SOURCES = [
  'https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main',
  'https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main',
]

const SPEECH_FILES = [
  {
    name: 'model.int8.onnx',
    bytes: 239233841,
    sha256: 'c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51',
  },
  {
    name: 'tokens.txt',
    bytes: 315894,
    sha256: 'f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc',
  },
]

/**
 * The silero voice-activity model the one-sentence window cuts segments with
 *
 * Same file and same sha256 as the one dsh's own `speech-to-text-sensevoice` package pins, so the
 * segmentation behaves the way that package's does. It is 1.8 MB - small enough that the app hashes
 * it on every start instead of trusting the download - and it is *not* part of the voice input
 * button's needs, so it is tracked separately from `present`: a phone that already downloaded the
 * 240 MB recogniser must not be told to download it again just because the VAD arrived later
 */
const SPEECH_VAD = {
  name: 'silero_vad.onnx',
  bytes: 1807522,
  sha256: 'a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28',
}

/** Same repo layout as the recogniser: the hf-mirror copy first, huggingface.co as the fallback */
const SPEECH_VAD_SOURCES = [
  'https://hf-mirror.com/csukuangfj/vad/resolve/main',
  'https://huggingface.co/csukuangfj/vad/resolve/main',
]

/**
 * The second engine: Zhipu's GLM-ASR-Nano-2512 (1.5 B params, MIT)
 *
 * It is a different animal from SenseVoice: an audio encoder plus a small Llama decoder, run by
 * llama.cpp inside the APK (the app spawns libglmasr.so and keeps it resident). It is markedly
 * better on Mandarin, dialects and quiet speech - and markedly slower: every utterance costs
 * seconds, not fractions of a second.
 *
 * The weights are the community GGUF conversion of the official release. The main model has no
 * quantised audio encoder to go with it - upstream only published BF16 and Q8_0 for the mmproj -
 * so Q8_0 is the one that travels, and Q4_K is the main model because that is the balanced one.
 *
 * Weights come from the hf-mirror copy first for the same reason as SenseVoice: the phone cannot
 * reach huggingface.co. Both files are checked against the sha256 the mirror publishes.
 */
const SPEECH_ENGINE_SHERPA = 'sherpa'
const SPEECH_ENGINE_GLM = 'glm'

/**
 * Which engine everything that does not name one uses, the page's own voice input button included
 *
 * SenseVoice, the same one the ball and the wake-word chain use (2026-10-09 主人定的口径: 输入框与
 * 球用同一套模型, 于是这台设备只需要下 240 MB, 而不是再拉 1.6 GB). GLM-ASR-Nano 仍在, 但**只有
 * 点名 `engine=glm` 才会走到它**: 它不再是谁的缺省, 也没有一条路会替人把它下下来
 */
const SPEECH_ENGINE_DEFAULT = SPEECH_ENGINE_SHERPA

const SPEECH_GLM_SOURCES = [
  'https://hf-mirror.com/concedo/GLM-ASR-Nano-2512-GGUF/resolve/main',
  'https://huggingface.co/concedo/GLM-ASR-Nano-2512-GGUF/resolve/main',
]

const SPEECH_GLM_FILES = [
  {
    name: 'model-q4k.gguf',
    remote: 'GLM-ASR-Nano-1.6B-2512-Q4_K.gguf',
    bytes: 980472032,
    sha256: '5d2fc1b22f90286b0d7141c821d9eac4e294cd6d6cc480d2d9bd7427c9c718af',
  },
  {
    name: 'mmproj-q8.gguf',
    remote: 'mmproj-GLM-ASR-Nano-2512-Q8_0.gguf',
    bytes: 720211744,
    sha256: '764227793db868b41b2e8dbe04ab2633cc503e1020466928ef95c60fd7fbb0d4',
  },
]

/**
 * How far the model is, in the shape the page's voice input reads it
 *
 * **字段名按 dsh 的 `SpeechPreparationState` 来**: `downloading` 那一档认 `resource` /
 * `completedBytes` / `totalBytes`, `failed` 认 `message` (外加可选的 `download` 诊断), `checking` /
 * `loading` / `waking` 认 `startedAt` —— 写错名字不会有任何报错, 界面只会把进度显示成 NaN、把失败
 * 原因吞掉 (2026-10-09 真机上就是这样: 那张卡写着"准备失败", 后面什么都没有)
 */
const speechState = { phase: 'checking', startedAt: Date.now() }
const speechListeners = new Set()

/** 每次宣布先把上一档的字段清掉: 留着会让界面读到"这一档不该有的"东西 (残留的 message / 进度) */
const SPEECH_STATE_FIELDS = [
  'message', 'download', 'resource', 'completedBytes', 'totalBytes', 'startedAt',
]

function speechAnnounce(phase, detail, extra = {}) {
  for (const key of SPEECH_STATE_FIELDS) delete speechState[key]
  speechState.phase = phase
  if (phase === 'failed') {
    speechState.message = String(detail ?? 'the preparation failed')
    if (extra.download) speechState.download = extra.download
  }
  if (phase === 'checking' || phase === 'loading' || phase === 'waking') {
    speechState.startedAt = Date.now()
  }
  if (phase === 'downloading') {
    speechState.resource = String(extra.resource ?? '')
    speechState.completedBytes = Number(extra.completed ?? 0)
    if (extra.total !== undefined) speechState.totalBytes = Number(extra.total)
  }
  for (const listener of speechListeners) {
    try {
      listener()
    } catch {
      // one observer throwing must not stop the others
    }
  }
}

async function speechSize(path) {
  try {
    return (await stat(path)).size
  } catch {
    return -1
  }
}

/** What the engine and the model say right now, without downloading anything */
async function speechInspect() {
  const info = await call('speech', { op: 'status' })
  const sizes = await Promise.all(
    SPEECH_FILES.map((file) => speechSize(join(info.directory, file.name))),
  )
  const present = SPEECH_FILES.every((file, index) => sizes[index] === file.bytes)
  const vad = await speechSize(info.vadPath) === SPEECH_VAD.bytes
  const glmDirectory = info.glm?.directory
  const glmSizes = glmDirectory
    ? await Promise.all(SPEECH_GLM_FILES.map((file) => speechSize(join(glmDirectory, file.name))))
    : []
  // A size match is not a hash check; the download path is what checks sha256, this only answers
  // "is it worth asking the engine to load that file"
  // 名字别叫 glm: 那个字段是 app 报回来的状态对象 (目录 / 字节数 / 有没有常驻), 这一个才是
  // "两个文件的大小都对得上" 那件事 —— 混用一个名字会让 provider 那一路拿到 undefined
  const glmPresent = Boolean(glmDirectory)
    && SPEECH_GLM_FILES.every((file, index) => glmSizes[index] === file.bytes)
  // 这张卡说的就是这个 provider 能不能用, 而它用的那一档是 SenseVoice (2026-10-09 统一之后):
  // GLM 在不在盘上不再是判据 —— 它只是 `engine=glm` 那条路自己的事
  speechAnnounce(present && vad ? 'ready' : 'unprepared')
  return { ...info, present, vad, glmPresent }
}

/** Which engine an `engine` argument (or the default) names, anything unknown falls back */
function speechEngineOf(value) {
  const asked = String(value ?? '').trim().toLowerCase()
  if (asked === SPEECH_ENGINE_GLM) return SPEECH_ENGINE_GLM
  if (asked === SPEECH_ENGINE_SHERPA) return SPEECH_ENGINE_SHERPA
  return null
}

/** Fetch the GLM-ASR-Nano weights (about 1.6 GB) into the app's private directory */
async function speechPrepareGlm() {
  const info = await speechInspect()
  const directory = info.glm?.directory
  if (!directory) throw new Error('the app did not name a directory for the GLM-ASR model')
  await mkdir(directory, { recursive: true })
  for (const file of SPEECH_GLM_FILES) {
    const target = join(directory, file.name)
    if (await speechSize(target) === file.bytes) continue
    await speechDownload(file, target, SPEECH_GLM_SOURCES)
  }
  speechAnnounce('ready', 'GLM-ASR-Nano (Q4_K) is on disk')
  return { ...info, glmPresent: true }
}

/** Fetch the model once, from whichever mirror answers */
async function speechPrepare(engine = SPEECH_ENGINE_DEFAULT) {
  if (engine === SPEECH_ENGINE_GLM) return await speechPrepareGlm()
  const info = await speechInspect()
  await mkdir(info.directory, { recursive: true })
  if (!info.present) {
    for (const file of SPEECH_FILES) {
      const target = join(info.directory, file.name)
      if (await speechSize(target) === file.bytes) continue
      await speechDownload(file, target)
    }
  }
  if (!info.vad) {
    await mkdir(dirname(info.vadPath), { recursive: true })
    await speechDownload(SPEECH_VAD, info.vadPath, SPEECH_VAD_SOURCES)
  }
  speechAnnounce('ready', `sherpa-onnx ${info.sherpa}`)
  return { ...info, present: true, vad: true }
}

/**
 * 把一次下载失败翻成界面认的那种诊断 (`SpeechDownloadFailure`)
 *
 * 界面按 `reason` 取本地化的那一对文案与建议 (`download.timeout` 与 `downloadAdvice.timeout`), 而
 * "未知" 与 "超时" 在最需要人动手的时候给的处置完全不同 —— 所以这里按错误码认一遍, 认不出来才回
 * unknown。`resource` / `source` / `status` / `code` 都是给人看的, 里面不放任何凭据
 */
function speechFailureOf(file, source, error) {
  const code = error?.cause?.code ?? error?.code
  const message = String(error?.message ?? error)
  let reason = 'unknown'
  if (code === 'ENOTFOUND' || code === 'EAI_AGAIN') reason = 'dns'
  else if (code === 'ETIMEDOUT' || code === 'UND_ERR_CONNECT_TIMEOUT' || error?.name === 'TimeoutError') {
    reason = 'timeout'
  } else if (code === 'ENOSPC' || code === 'EACCES' || code === 'EROFS' || code === 'EPERM') {
    reason = 'storage'
  } else if (code === 'ECONNRESET' || code === 'ECONNREFUSED' || code === 'EPIPE') reason = 'network'
  else if (code === 'ENETUNREACH' || code === 'EHOSTUNREACH') reason = 'network'
  else if (typeof error?.status === 'number') reason = 'http'
  else if (message.includes('sha256')) reason = 'integrity'
  else if (/certificate|self.signed|unable to verify/i.test(message)) reason = 'certificate'
  const failure = { resource: file.remote ?? file.name, source, reason }
  if (typeof error?.status === 'number') failure.status = error.status
  if (typeof code === 'string' && code.length > 0) failure.code = code
  return failure
}

async function speechDownload(file, target, sources = SPEECH_SOURCES) {
  const remote = file.remote ?? file.name
  let failure = null
  let failedSource = ''
  for (const base of sources) {
    const partial = `${target}.part`
    try {
      speechAnnounce('downloading', null, { resource: remote, completed: 0, total: file.bytes })
      const response = await fetch(`${base}/${remote}`)
      if (!response.ok) {
        const refused = new Error(`HTTP ${response.status}`)
        refused.status = response.status
        throw refused
      }
      const digest = createHash('sha256')
      let received = 0
      const meter = new Transform({
        transform(chunk, _encoding, callback) {
          digest.update(chunk)
          received += chunk.length
          speechState.completedBytes = received
          speechState.totalBytes = file.bytes
          callback(null, chunk)
        },
      })
      await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(partial))
      const actual = digest.digest('hex')
      if (actual !== file.sha256) throw new Error(`sha256 ${actual} is not ${file.sha256}`)
      await rename(partial, target)
      return
    } catch (error) {
      failure = error
      failedSource = new URL(base).origin
      await rm(partial, { force: true })
    }
  }
  speechAnnounce('failed', `${remote}: ${failure?.message ?? failure}`, {
    download: speechFailureOf(file, failedSource, failure),
  })
  throw new Error(`could not download ${file.name}: ${failure?.message ?? failure}`)
}

/**
 * One recording through the app's own engine
 *
 * A missing model is an error naming the prepare call, not a surprise multi-hundred-MB download
 */
async function speechTranscribe(wav, language, engine = SPEECH_ENGINE_DEFAULT) {
  const info = await speechInspect()
  if (engine === SPEECH_ENGINE_GLM && !info.glmPresent) {
    throw new Error(
      'the GLM-ASR model is not downloaded yet: run lw_speech op=prepare engine=glm once'
      + ' (about 1.6 GB), or ask for engine=sherpa to use the small one',
    )
  }
  if (engine === SPEECH_ENGINE_SHERPA && !info.present) {
    throw new Error(
      'the SenseVoice model is not downloaded yet: run lw_speech op=prepare engine=sherpa once',
    )
  }
  return await call('speech', { op: 'transcribe', wav, language: language ?? 'auto', engine })
}

/**
 * 留住刚认完的那一段录音与它的结果 (最近 [SPEECH_KEEP] 段)
 *
 * 为什么留: 2026-10-07 主人报"输入框上麦克风识别时会出现乱码", 而真正的现场 —— 那一段 16 kHz
 * 单声道 WAV —— 原来在 `finally` 里被删掉了, 于是事后只剩"我见过一串怪字"。留最近几段之后,
 * 下一次出乱码可以拿同一段音频喂回模型 (`lw_speech op=transcribe wav=…`) 对账: 是模型听错了,
 * 还是别的地方把它写坏了
 *
 * 分寸: 只留 [SPEECH_KEEP] 段 (每段几百 KB 量级), 结果写成一行 JSON 的日志也只留 [SPEECH_LOG_KEEP]
 * 行; 目录在 `files/speech-models/recordings/` (应用私有, 与模型同一个父目录), 清理时按文件名排序
 * 丢最旧的 —— 这几件事失败**只写日志**, 不该让一次转写白跑
 */
const SPEECH_KEEP = 5
const SPEECH_LOG_KEEP = 200

async function speechKeep(directory, wav, answer, engine) {
  const home = dirname(directory)
  const recordings = join(home, 'recordings')
  const log = join(home, 'transcripts.log')
  try {
    await mkdir(recordings, { recursive: true })
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    const kept = join(recordings, `recording-${stamp}.wav`)
    await rename(wav, kept)
    const line = JSON.stringify({
      at: new Date().toISOString(),
      engine,
      wav: kept,
      // 应用那侧回的是两份: `text` 是过了清洁口的 (真正插进输入框的那一份), `raw` 是引擎原话 ——
      // 两个不一样时, 那就是"乱码被修回来 / 被丢掉"的那一次
      text: answer?.text ?? null,
      raw: answer?.raw ?? null,
      ...(answer?.error === undefined ? {} : { error: String(answer.error) }),
    })
    await appendFile(log, `${line}\n`)
    const files = (await readdir(recordings)).filter((name) => name.endsWith('.wav')).sort()
    for (const name of files.slice(0, Math.max(0, files.length - SPEECH_KEEP))) {
      await rm(join(recordings, name), { force: true })
    }
    const lines = (await readFile(log, 'utf8')).split('\n').filter((item) => item !== '')
    if (lines.length > SPEECH_LOG_KEEP) {
      await writeFile(log, `${lines.slice(-SPEECH_LOG_KEEP).join('\n')}\n`)
    }
  } catch (error) {
    // 现场失败不该把这一次转写也带坏: 那一段录音本来就是要删的
    await rm(wav, { force: true }).catch(() => {})
    console.warn(`luwi-channel: keeping the recording failed: ${error?.message ?? error}`)
  }
}

/* ── 唤醒词 ─────────────────────────────────────────────────────────────────
 * 引擎在 app 那一侧 (同一个 sherpa-onnx, 换成了 KeywordSpotter), 这一侧只做两件事: 把模型取到
 * 手机上、把词表写成 sherpa-onnx 认的那一行。
 *
 * 词表为什么不能写中文原文: keywords 文件每一行是**模型的 token 序列**加 `@显示名`, 而
 * EncodeKeywords 只认模型 tokens.txt 里的符号 —— 中文原文一个都不在表里, 它不报错, 只是把那
 * 一行静默丢掉, 结果是"照做了但永远不触发"。所以这里把拼音拆成声母 + 带调韵母, 逐个对一遍
 * 符号表, 对不上就当面报错
 */

/**
 * 模型从我们自己那个 Release 取, 不从上游取
 *
 * 上游那两处 (ModelScope 与 sherpa-onnx 的 Release) 同名文件是**另外几次构建** —— 字节数略差
 * 几百个, sha256 自然不同 (2026-10-05 逐文件比过)。既然这台手机上跑通的就是我们自己这一套, 那
 * 清单、下载与运行时就对同一份文件说话, 不留下"文件表说的不是盘上那份"这种说不清的状态
 */
const WAKEWORD_RELEASE =
  'https://github.com/yuloong07-star/Luwi/releases/download/models-kws-2024-01-01'

/**
 * 镜像优先: 这台设备上 github.com 直连只回 302, 真正的字节在 objects.githubusercontent.com 那
 * 一跳上, 出不去; ghfast.top 前面挂一层实测 200, 而且逐字节对得上。两个都不是就把失败原样报出去
 */
const WAKEWORD_SOURCES = [
  `https://ghfast.top/${WAKEWORD_RELEASE}`,
  WAKEWORD_RELEASE,
]

/**
 * 只留 int8 那一套 (4 个文件, 约 5.3 MB, 适合手机 CPU)
 *
 * 字节数与 sha256 都是 GitHub Release 自己算的那份 (`assets[].digest`), 与本地文件逐个核过。
 * 没量化那一套 (encoder 12 MB) 刻意不在表里: 两套并存时 [wakeWordModelOf] 按 `.int8.` 优先取,
 * 取到哪一套就变成"谁先下的算谁的", 所以 [wakeWordPrune] 会把不属于这张表的 .onnx 删掉
 */
const WAKEWORD_FILES = [
  {
    name: 'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    bytes: 4777666,
    sha256: 'dd784973fc9d2fabb3b800d6dcd20fc3b0ca84f8e2415afe54b032878e447f4d',
  },
  {
    name: 'decoder-epoch-12-avg-2-chunk-16-left-64.onnx',
    bytes: 675349,
    sha256: 'fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774',
  },
  {
    name: 'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    bytes: 65242,
    sha256: 'f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24',
  },
  {
    name: 'tokens.txt',
    bytes: 1627,
    sha256: '72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e',
  },
]

/**
 * 缺省那一张词表; 改词表就是改这一组 (见 op=keywords)
 *
 * 应用那一侧 (`WakeWordWords.DEFAULT_WORDS`) 的缺省与这里必须**同字** —— 两边都会写 keywords.txt, 而
 * 只认内容不认谁写的。`tools/check-wake-words.mjs` 逐行比对两份实现, 所以谁单独改了都会被抓住
 *
 * **2026-10-06 主人定的是「肥鱼肥鱼」**: 六音节那一版「大肥鱼大肥鱼」作废, 而这一版不是一条, 是
 * 本体加三条容错读音 —— 声母 f / h (肥 -> huí) 与韵母 ü / i (鱼 -> yí) 两处口音各一条, 再加两个
 * 都改的那一条。四条同名, 设置页与通知去重之后仍然只念一个「肥鱼肥鱼」
 */
const WAKEWORD_DEFAULT_WORDS = [
  '肥鱼肥鱼=fei2 yu2 fei2 yu2',
  '肥鱼肥鱼=hui2 yu2 hui2 yu2',
  '肥鱼肥鱼=fei2 yi2 fei2 yi2',
  '肥鱼肥鱼=hui2 yi2 hui2 yi2',
]

/** 模型的符号表: `tokens.txt` 每行第一个字段 */

/** 声母: 长在前, 免得 zh 被拆成 z + h */
const PINYIN_INITIALS = [
  'zh', 'ch', 'sh', 'b', 'p', 'm', 'f', 'd', 't', 'n', 'l',
  'g', 'k', 'h', 'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w',
]

/** 声调往哪个元音上标 */
const TONE_MARKS = {
  a: 'āáǎà',
  o: 'ōóǒò',
  e: 'ēéěè',
  i: 'īíǐì',
  u: 'ūúǔù',
  'ü': 'ǖǘǚǜ',
}

/** su4 -> sù; 已经带调号的 (sù) 原样回来; 轻声 (第 5 声) 不带调号 */
function markedSyllable(raw) {
  let syllable = raw.trim().toLowerCase().replace(/u:/g, 'ü').replace(/v/g, 'ü')
  const tone = syllable.match(/([1-5])$/)
  if (!tone) return syllable
  syllable = syllable.slice(0, -1)
  if (tone[1] === '5') return syllable
  const at = toneIndex(syllable)
  const marks = at >= 0 ? TONE_MARKS[syllable[at]] : null
  if (!marks) return syllable
  return syllable.slice(0, at) + marks[Number(tone[1]) - 1] + syllable.slice(at + 1)
}

/** 一个音节里调号落在哪个字符上: a / o / e 优先, iu 落在 u, ui 落在 i, 其余落在最后一个元音 */
function toneIndex(syllable) {
  for (const vowel of ['a', 'o', 'e']) {
    const at = syllable.indexOf(vowel)
    if (at >= 0) return at
  }
  if (syllable.endsWith('iu') || syllable.endsWith('ui')) return syllable.length - 1
  for (let at = syllable.length - 1; at >= 0; at -= 1) {
    if ('iuü'.includes(syllable[at])) return at
  }
  return -1
}

/** 模型认得的那张符号表: tokens.txt 每行第一个字段 */
async function wakeWordSymbols(directory) {
  const text = await readFile(join(directory, 'tokens.txt'), 'utf8')
  const symbols = new Set()
  for (const line of text.split('\n')) {
    const token = line.split(' ')[0].trim()
    if (token) symbols.add(token)
  }
  return symbols
}

/** 一个词 + 它的拼音 -> sherpa-onnx 要的那一行; 有符号表里没有的 token 就直接报错 */
function wakeWordLine(word, pinyin, symbols) {
  const tokens = []
  for (const raw of String(pinyin).trim().split(/\s+/)) {
    const syllable = markedSyllable(raw)
    if (!syllable) throw new Error(`"${pinyin}" has an empty syllable`)
    const initial = PINYIN_INITIALS.find(
      (one) => syllable.startsWith(one) && syllable.length > one.length,
    )
    const parts = initial ? [initial, syllable.slice(initial.length)] : [syllable]
    for (const token of parts) {
      if (!symbols.has(token)) {
        throw new Error(
          `the model's tokens.txt has no "${token}" (from ${raw} of "${word}"), so sherpa-onnx`
          + ' would drop the whole line without saying so',
        )
      }
    }
    tokens.push(...parts)
  }
  return `${tokens.join(' ')} @${word}`
}

/** `肥鱼肥鱼=fei2 yu2 fei2 yu2` -> 一行; 没写等号就当成拼音与词同名的一对, 报错让人补上 (见 slice 那一头) */
async function wakeWordLinesFor(args, directory) {
  const raw = args?.lines?.length ? args.lines : null
  if (raw) return raw.map((line) => String(line).trim()).filter(Boolean)
  const pairs = args?.words?.length ? args.words : null
  if (!pairs) throw new Error('op=keywords names the words: words=["肥鱼肥鱼=fei2 yu2 fei2 yu2", ...]')
  const folder = directory ?? (await call('wakeword', { op: 'status' })).directory
  const symbols = await wakeWordSymbols(folder)
  return pairs.map((pair) => {
    const at = String(pair).indexOf('=')
    if (at <= 0) throw new Error(`"${pair}" has to be 词=拼音, for example 肥鱼肥鱼=fei2 yu2 fei2 yu2`)
    return wakeWordLine(String(pair).slice(0, at).trim(), String(pair).slice(at + 1), symbols)
  })
}

/** 现在什么样: 引擎报的 + 磁盘上那几个文件的大小对不对 */
async function wakeWordInspect() {
  const info = await call('wakeword', { op: 'status' })
  const sizes = await Promise.all(
    WAKEWORD_FILES.map((file) => speechSize(join(info.directory, file.name))),
  )
  const present = WAKEWORD_FILES.every((file, index) => sizes[index] === file.bytes)
  return { ...info, present }
}

/** 取一次模型, 顺便把缺省词表写上; 已经有了的就不动它 */
async function wakeWordPrepare(words) {
  const before = await wakeWordInspect()
  await mkdir(before.directory, { recursive: true })
  const fetched = []
  for (const file of WAKEWORD_FILES) {
    const target = join(before.directory, file.name)
    if (await speechSize(target) === file.bytes) continue
    await wakeWordDownload(file, target)
    fetched.push(file.name)
  }
  // 装完只留这一套: 见 WAKEWORD_FILES 上面那段, 两套并存时取哪一套不由这里说了算
  const pruned = await wakeWordPrune(before.directory)
  if (words?.length) {
    const lines = await wakeWordLinesFor({ words }, before.directory)
    await call('wakeword', { op: 'keywords', lines })
  } else if (!before.keywords) {
    const lines = await wakeWordLinesFor({ words: WAKEWORD_DEFAULT_WORDS }, before.directory)
    await call('wakeword', { op: 'keywords', lines })
  }
  return { ...(await wakeWordInspect()), fetched, pruned }
}

/**
 * 把不属于 [WAKEWORD_FILES] 的 .onnx 删掉, 回删掉的那些文件名
 *
 * tokens.txt 与词表留着不动 (词表是用户写的), 别的文件也不碰 —— 只清"同一件事的另一份构建"
 */
async function wakeWordPrune(directory) {
  const keep = new Set(WAKEWORD_FILES.map((file) => file.name))
  let entries = []
  try {
    entries = await readdir(directory)
  } catch {
    return []
  }
  const removed = []
  for (const entry of entries) {
    if (!entry.endsWith('.onnx') || keep.has(entry)) continue
    await rm(join(directory, entry), { force: true })
    removed.push(entry)
  }
  return removed
}

/**
 * 与 speech 那一套同一个写法: 换镜像、对 sha256、失败不留半条文件
 *
 * 每一跳失败都把**是谁失败、为什么**记下来, 最后一跳也失败时把这几句一起报出去 —— 只回"下载失败"
 * 会让"镜像没缓存"与"网络真不通"看起来一样
 */
async function wakeWordDownload(file, target) {
  const attempts = []
  for (const base of WAKEWORD_SOURCES) {
    const partial = `${target}.part`
    try {
      const response = await fetch(`${base}/${file.name}`)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const digest = createHash('sha256')
      const meter = new Transform({
        transform(chunk, _encoding, callback) {
          digest.update(chunk)
          callback(null, chunk)
        },
      })
      await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(partial))
      const actual = digest.digest('hex')
      if (actual !== file.sha256) throw new Error(`sha256 ${actual} is not ${file.sha256}`)
      await rename(partial, target)
      return
    } catch (error) {
      attempts.push(`${new URL(base).host}: ${error?.message ?? error}`)
      await rm(partial, { force: true })
    }
  }
  throw new Error(`could not download ${file.name} (${attempts.join('; ')})`)
}

/* ------------------------------------------------------------------ reading aloud */

/**
 * What of a reply is worth saying out loud
 *
 * A reply is markdown written for the eye. Read aloud, the parts that only make sense on a screen
 * turn into noise: "```" and "|" and "](https://...)" are not words. So this strips exactly those
 * and returns whatever prose is left, and the answer says so when nothing is left - a reply that is
 * all code reads as an empty line, and "nothing worth reading" is a truer thing to say than silence
 *
 * The rules live here and only here (a decision D15 pinned): fenced code goes, inline code keeps its
 * contents, images go, links keep their text, bare URLs go, table rows go, heading/list/quote
 * markers go, emphasis markers go, rules go, then whitespace collapses
 */
function readAloudText(markdown) {
  let text = String(markdown ?? '')
  text = text.replace(/```[\s\S]*?```/g, ' ')
  text = text.replace(/`([^`]*)`/g, '$1')
  text = text.replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
  text = text.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  text = text.replace(/https?:\/\/\S+/g, ' ')
  text = text.replace(/^[^\n]*\|[^\n]*$/gm, '')
  text = text.replace(/^\s{0,3}(#{1,6}|>|[-*+]|\d+[.)])\s+/gm, '')
  text = text.replace(/(\*\*|__)([\s\S]*?)\1/g, '$2')
  text = text.replace(/(?<![A-Za-z0-9])(\*|_)(?=\S)([^*_\n]*?)(?<=\S)\1(?![A-Za-z0-9])/g, '$2')
  text = text.replace(/^\s*([-*_]\s*){3,}$/gm, ' ')
  text = text.replace(/^[ \t]+$/gm, '')
  return text.replace(/[ \t]+/g, ' ').replace(/\n{2,}/g, '\n').replace(/^ | $/gm, '').trim()
}

/* ── 语音投递 ───────────────────────────────────────────────────────────────
 * 应用那一侧把认出来的话追加进 `$DSH_HOME/voice/inbox.jsonl` (一行一句, 带自增 `seq`), 这一侧
 * 读出来送进会话,
 *
 * 为什么是文件而不是让应用连过来: 现有的回环桥是"宿主问、应用答", 反过来的话要么让宿主开一个
 * 监听口 (新的攻击面, 应用还得知道那个口在哪), 要么让应用去猜 dsh 的内部 HTTP 接口,文件这条路
 * 三个好处一次拿到 —— 宿主重启不丢、应用先写宿主后起也投得出去、`$DSH_HOME` 是真文件系统所以
 * 轮询的开销可以忽略,
 *
 * 去重靠 `seq` 不靠字节游标: 文件满了会从尾部留若干行重写, 那一刻游标会指到新文件之外, 只靠游标
 * 就会重放,游标记的是**已投递的最大 seq**, 所以重写与截断都不会让一句话被说两遍,
 */

/** 投递队列的位置, 与应用那一侧同一个约定 */
function voiceInboxPath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'voice', 'inbox.jsonl') : null
}

/**
 * 浮标那一路的**专用工作区**: 由通道问出来的对话都落在这个目录里
 *
 * 主人 2026-10-06: "给浮标上的对话通道建一个专门的工作区, 对话都放在这个工作区中"。宿主进程的
 * cwd 与 `HOME` 都是工作区根 (见 app 的 DshHost.spawn), 所以这里只取根下一个固定的子目录 ——
 * 会按需创建, 但**不建在别处**: 主人要的是"对话们有个自己的家", 而它的文件也该由主人的文件管理器
 * 看得见 (工作区本来就在共享存储里)
 *
 * 名字是中英混合的一处取舍: 目录名要能在文件管理器里一眼认出是什么, 所以用 `dsh-ball`, 而不是
 * 一串没有意义的 hash —— 中文目录名在少数工具链里会踩编码, 这一条不值得赌
 */
const BALL_WORKSPACE_NAME = 'dsh-ball'

/**
 * 一条通道工作区的目录: 宿主进程的 cwd 与 `HOME` 都是工作区根 (见 app 的 DshHost.spawn), 所以
 * 只取根下一个固定的子目录
 */
function channelWorkspace(name) {
  const home = process.env.HOME || process.cwd()
  return join(home, name)
}

function ballWorkspace() {
  return channelWorkspace(BALL_WORKSPACE_NAME)
}

/**
 * `dsh-ball` 那条工作区注册没注册 —— 上一次 [ensureBallWorkspace] 的读数
 *
 * `registered` 三态: `true` / `false` (试过, 不通) / `null` (这个 profile 没有那项服务, 问不出来)
 */
const ballWorkspaceState = { path: null, id: null, registered: null, why: null }

/**
 * 一条通道工作区的注册路 (浮标那条与探索那条共用的就是这一段)
 *
 * **光建目录不够, 还得把它注册成工作区** (2.7.8 批次 2 根因一): dsh 的会话列表只遍历**已注册的工作区
 * 实体**, 没入账的会话一律进「未分组」(`packages/client/ui-workspace/src/client/tree.ts` 的
 * `groupByWorkspace`); 而工作区记录原本只在宿主启动那一趟按会话头的 cwd 补建 —— 于是**新装 / 清过
 * 工作区列表之后的第一句一定落在未分组**, 要等重启一次才归位。`create` 的契约是 "Create or resolve
 * one Workspace over an existing directory", 幂等, 所以每投一句问一次也不要紧
 *
 * **建新会话时还要把那条工作区的 id 交出去**: `sessionController.create` 只在请求带 `workspaceId`
 * 时才 `attachSession` (`packages/api/session-controller/src/commands.ts:105-141`), 只给 `cwd` 的话
 * 会话建出来但不入账 —— 界面上那条工作区在、里面一场都没有 (2026-10-17 模拟器上量到的)
 *
 * @param name 目录名 (`dsh-ball` / `Luwi`)
 * @param state 这条通道自己的那份读数 (见 [ballWorkspaceState] / [exploreWorkspaceState])
 */
async function ensureChannelWorkspace(ctx, name, state) {
  const directory = channelWorkspace(name)
  try {
    await mkdir(directory, { recursive: true })
  } catch (error) {
    // `hostCtx` 是 apply() 里挂上的 (见上面那个声明), 而这条路径只在投递时走到 —— 那时它一定在
    warn(hostCtx, `the channel workspace ${directory} could not be created: ${error?.message ?? error}`)
    state.why = `the directory could not be created: ${error?.message ?? error}`
    state.registered = false
    return null
  }
  let registered = null
  let entity = null
  try {
    const registry = ctx?.get?.('workspaceRegistry') ?? hostCtx?.get?.('workspaceRegistry')
    if (!registry || typeof registry.create !== 'function') {
      registered = null
      state.why = 'this profile has no workspace registry, so the ball conversations stay ungrouped'
    } else {
      entity = await registry.create(directory)
      registered = true
      state.why = null
    }
  } catch (error) {
    registered = false
    state.why = `the workspace could not be registered: ${error?.message ?? error}`
    warn(hostCtx, `the channel workspace ${directory} was not registered: ${error?.message ?? error}`)
  }
  state.path = directory
  state.registered = registered
  state.id = entity === null ? null : String(entity.id ?? '')
  return { path: directory, id: state.id }
}

/** 浮标那条: 目录 `dsh-ball`, 读数记在 [ballWorkspaceState] 上 (判据钉着这个函数名) */
async function ensureBallWorkspace(ctx) {
  return ensureChannelWorkspace(ctx, BALL_WORKSPACE_NAME, ballWorkspaceState)
}



/**
 * 轮询那个队列的间隔
 *
 * 一次 tick 只是一次 `stat` (内容没变时连文件都不读, 见 voiceUnchanged), 所以这里可以很密。
 * 500 ms 是"半秒对说话这件事足够快"的估计, 但它落在**主人说完到那句话真的进会话**这段等待里 ——
 * 而那段等待的另一半 (VAD 等静音) 已经从 3.0 s 收到 0.8 s, 这里不收就显得不成比例。150 ms 时
 * 最坏多等 150 ms, 而平均只多等 75 ms
 */
const VOICE_POLL_MS = 150

/** 同一句话最多投几次 (投不动就跳过, 队列继续走, 见 [startVoiceInbox]) */
const VOICE_DELIVER_TRIES = 3

/** 一次投递最多等多久: 下游某一处 await 永不落定时把它放掉, 不许一直占着队列 */
const VOICE_DELIVER_TIMEOUT_MS = 20_000

/** 失败之后隔多久再试同一行 (150 ms 一拍上连着重试只会把日志刷满) */
const VOICE_RETRY_MS = 3_000

/** 刚失败过的那一行 (`seq`) 与"什么时候可以再试它" */
const voiceRetry = { seq: null, at: 0 }

/** 现在每一行试了几次 (`seq` -> 次数), 投成功或跳过都清掉 */
const voiceAttempts = new Map()

/**
 * 超时那一层
 *
 * 它管的是"下游某一处卡住"这件事 —— 那一步既不 resolve 也不 reject, 而队列的 `running` 那一道闸
 * 只在 `finally` 复位, 于是整条队列会静静地停在那里 (2026-10-06 真机上查到的就是这种形态)
 *
 * **被超时放掉的那一步并没有被取消** (JS 的 Promise 取消不了): 它可能过一会儿自己成了, 于是那一句
 * 会在"已经报过没送出去"之后再进会话一次。这是刻意留下的一头 —— 宁可让主人多看到一句已经送进去的
 * 话, 也不要让整条队列为它停摆
 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`nothing answered within ${ms}ms`)),
      ms,
    )
    Promise.resolve(promise).then(
      (value) => { clearTimeout(timer); resolve(value) },
      (error) => { clearTimeout(timer); reject(error) },
    )
  })
}

/**
 * 这一行投失败之后该怎么办: 还试 (`retry`) 还是跳过 (`skip`)
 *
 * 纯函数, 没有设备也能量 (`tools/check-voice-inbox.mjs`): "试满就跳过"是这一批最要紧的一条 ——
 * 只重试不跳过的写法在一行必败的句子面前就是"整条队列永久停摆"
 */
function voiceAfterFailure(tries) {
  return tries >= VOICE_DELIVER_TRIES ? { retry: false, skip: true } : { retry: true, skip: false }
}

function voiceCursorPath(inbox) {
  return join(dirname(inbox), 'inbox.cursor')
}

/** 已投递到哪 (最大 seq) 与最近一次读到的文件指纹 */
const voiceCursor = { path: null, seq: null, fingerprint: null }

/** 尾部那行的 seq, 认不出来就是 0 */
function voiceLastSeq(raw) {
  const lines = String(raw).split('\n')
  for (let at = lines.length - 1; at >= 0; at -= 1) {
    const line = lines[at].trim()
    if (!line) continue
    try {
      const seq = Number(JSON.parse(line)?.seq)
      if (Number.isFinite(seq)) return seq
    } catch {
      // 末尾可能留着半行 (进程被杀), 那一行就是没有序号, 继续往回找
    }
  }
  return 0
}

/**
 * 读到哪了
 *
 * 游标文件不在时**从当前的尾部开始**, 不把装之前说的那些话一次性倒进会话 —— 一个刚装上的功能
 * 不该拿一堆积压的句子打断主人
 */
async function voiceCursorLoad(inbox, raw) {
  if (voiceCursor.path !== inbox || voiceCursor.seq === null) {
    voiceCursor.path = inbox
    const stored = await readFile(voiceCursorPath(inbox), 'utf8').catch(() => null)
    const parsed = stored === null ? Number.NaN : Number.parseInt(stored.trim(), 10)
    voiceCursor.seq = Number.isFinite(parsed) ? parsed : voiceLastSeq(raw)
  }
  return voiceCursor.seq
}

/** 投递成功之后才前移: 投失败的那一句留在下游, 下一次接着投, 不静默丢掉 */
async function voiceCursorStore(inbox, seq) {
  voiceCursor.seq = seq
  await mkdir(dirname(inbox), { recursive: true })
  await writeFile(voiceCursorPath(inbox), `${seq}\n`)
}

/**
 * 文件没变就别解析: 500 ms 一次的轮询里绝大多数时候什么都没发生
 *
 * 但**"上次读出来却还没确认投出去"时不许跳过** —— 那正是投递失败之后要重试的那一刻, 而文件当然
 * 没变。少了 `unread` 这一个条件, 一句投失败的话就会一直躺在文件里, 直到主人再说一句才被顺带带
 * 出去 (实测出来的: tools/check-voice-inbox.mjs 第 3 条判据)
 */
async function voiceUnchanged(inbox) {
  if (voiceCursor.unread) return false
  const info = await stat(inbox).catch(() => null)
  if (info === null) return true
  const fingerprint = `${info.size}:${info.mtimeMs}`
  if (voiceCursor.fingerprint === fingerprint) return true
  voiceCursor.fingerprint = fingerprint
  return false
}

/** 还没投递过的那几行, 按说的顺序 */
async function voiceReadNew(inbox) {
  if (await voiceUnchanged(inbox)) return []
  const raw = await readFile(inbox, 'utf8').catch(() => null)
  if (raw === null) return []
  const since = await voiceCursorLoad(inbox, raw)
  const fresh = []
  for (const line of raw.split('\n')) {
    const text = line.trim()
    if (!text) continue
    let record = null
    try {
      record = JSON.parse(text)
    } catch {
      continue
    }
    const seq = Number(record?.seq)
    const said = typeof record?.text === 'string' ? record.text.trim() : ''
    if (!Number.isFinite(seq) || seq <= since || !said) continue
    // `wake` 是"这一句是唤醒之后的头一句"那个记号 (应用那侧只有头一句带它, 见 VoiceInbox.append) ——
    // **2026-10-06 起它只是记号, 不再是"另开一场"的判据**: 选哪一场由 [voiceCurrentSession] 那一笔
    // 账 (2026-10-07 起 20 分钟, 2026-10-11 起 10 分钟) 定, 见 [voiceTargetCandidates]。老版本写的行没有这个键, 读起来与
    // false 是一回事
    //
    // `to` 是**回复框点名的那一场** (2026-10-06 加): 框在屏上时, 框里发出去的那句话投给"发出那条
    // 回复的会话" (见 [voiceTargetCandidates] 的第一条判据)。老版本写的行没有这个键, 读起来就是"没点名"
    const to = typeof record?.to === 'string' ? record.to.trim() : ''
    fresh.push({
      seq,
      text: said,
      source: record.source ?? 'voice',
      at: Number(record.at) || 0,
      wake: record.wake === true,
      to,
    })
  }
  // 留一条"还没确认投出去"的记号, 见 voiceUnchanged: 它让失败的那句话下一次还被读出来
  voiceCursor.unread = fresh.length > 0
  return fresh
}

/**
 * 一直看着那个队列, 有新句子就送进会话
 *
 * 一句一句地送, 送成功才前移游标: 顺序就是主人说话的顺序, 而中途失败不会让后面的话插到前面去
 *
 * **一行投不动不许把整条队列堵死** (2026-10-06 真机上发生的那一次): 只"投成功才前移"是必要的,
 * 但它有个反面 —— 某一句话因为一步必败 (当时是唤醒词那一句拿了个 null 会话) 而永远投不出去时,
 * 后面**每一句** (键盘打的那些在内) 都排在它后面, 而屏幕上没有任何东西说得出这件事。所以现在三条:
 *
 * - 一次投递有 [VOICE_DELIVER_TIMEOUT_MS] 的上限: 下游某一处 await 永不落定也不再占着 `running`
 * - 同一行最多 [VOICE_DELIVER_TRIES] 次, 两次之间隔 [VOICE_RETRY_MS] (150 ms 一拍上连着重试只是刷日志)
 * - 试满就**跳过它并前移游标**, 队列继续走; 同时记进 `voiceDelivery` 并往浮标那块框推一条提示
 *   ([reportNote]) —— 跳过不等于静默丢掉
 */
function startVoiceInbox(ctx, deliver) {
  const inbox = voiceInboxPath()
  if (!inbox) {
    warn(ctx, 'the voice inbox has no place to live: DSH_HOME is not set')
    return
  }
  let running = false
  const tick = async () => {
    if (running) return
    running = true
    try {
      for (const line of await voiceReadNew(inbox)) {
        // 刚失败过的那一行要等一会儿再试: 后面的句子按顺序等它, 不许插队
        if (voiceRetry.seq === line.seq && Date.now() < voiceRetry.at) break
        console.log(`luwi-channel: voice line #${line.seq} picked up from the queue`)
        try {
          await withTimeout(deliver(line), VOICE_DELIVER_TIMEOUT_MS)
          voiceAttempts.delete(line.seq)
          voiceRetry.seq = null
          await voiceCursorStore(inbox, line.seq)
        } catch (error) {
          const tries = (voiceAttempts.get(line.seq) ?? 0) + 1
          voiceAttempts.set(line.seq, tries)
          const reason = error?.message ?? String(error)
          const next = voiceAfterFailure(tries)
          warn(
            ctx,
            `voice line #${line.seq} was not delivered (try ${tries} of ${VOICE_DELIVER_TRIES}): ${reason}`,
          )
          if (next.retry) {
            voiceRetry.seq = line.seq
            voiceRetry.at = Date.now() + VOICE_RETRY_MS
            break
          }
          voiceAttempts.delete(line.seq)
          voiceRetry.seq = null
          voiceDelivery.skipped += 1
          voiceDelivery.lastSkip = { at: Date.now(), seq: line.seq, text: line.text, reason }
          voiceDelivery.error = `line #${line.seq} was skipped after ${VOICE_DELIVER_TRIES} tries: ${reason}`
          reportNote(ctx, `这句话没能送进会话 (试了 ${VOICE_DELIVER_TRIES} 次): ${reason}`)
          await voiceCursorStore(inbox, line.seq)
        }
      }
    } catch (error) {
      warn(ctx, `the voice inbox could not be read: ${error?.message ?? error}`)
    } finally {
      running = false
    }
  }
  // 定时器注册失败也必须把第一次读跑了: 一条"没起来"的警告比一个不吭声的死功能好得多, 而且
  // 第一次读本身就能把"路径对不对、游标读到哪"这两件事说清楚
  try {
    ctx.effect(
      () => {
        const timer = setInterval(() => void tick(), VOICE_POLL_MS)
        return () => clearInterval(timer)
      },
      'luwi-channel: voice inbox',
    )
  } catch (error) {
    warn(ctx, `the voice inbox timer could not be registered: ${error?.message ?? error}`)
  }
  void tick()
}

/** 队列现在什么样, 给 lw_voice 用 */
async function voiceInboxState() {
  const inbox = voiceInboxPath()
  if (!inbox) return { inbox: null, queued: [], cursor: null }
  const raw = await readFile(inbox, 'utf8').catch(() => null)
  const queued = []
  if (raw !== null) {
    for (const line of raw.split('\n')) {
      const text = line.trim()
      if (!text) continue
      try {
        const record = JSON.parse(text)
        if (typeof record?.text === 'string' && record.text.trim()) {
          queued.push({ seq: Number(record.seq), at: Number(record.at) || 0, text: record.text.trim() })
        }
      } catch {
        // 半行, 不当它是队列里的一句
      }
    }
  }
  // 游标还没建立时报的是**读者这次会从哪开始** (即文件尾部), 不是"什么都没有": 第一次跑本来
  // 就不会把历史倒进会话, 而"什么都不会投"与"都会投"这两句话在故障排查时是反的
  const cursor = voiceCursor.path === inbox ? voiceCursor.seq : voiceLastSeq(raw ?? '')
  return { inbox, queued, cursor }
}

/**
 * "当前对话"那一笔账: 一个会话 id 加它是什么时候拿到的
 *
 * **为什么落盘**: 宿主重启之后"10 分钟内那一场对话"还该是同一场 —— 记在内存里的话, 重启一次主人
 * 刚才说的话就接不上了 (而重启在这台设备上是常事: 装一次包、改一次设置都要重来)
 *
 * 可变部分全在 [voiceSession.state] 这一个对象上: 换掉它就等于"什么都没记住", 而那正是
 * `tools/check-voice-inbox.mjs` 要模拟的两件事 (宿主重启 / 过了很久)
 */
const voiceSession = {
  state: {
    file: { path: null, read: false },
    current: { id: null, at: 0, origin: '', cwd: '' },
    /** 上一次读到的账为什么不能用 (`lw_voice op=inbox` 的读数, 见 [voiceCurrentSession]) */
    rejected: null,
  },
}

/** 把这一笔账清干净, 下一次读会重新从文件里读 (测试与"重启"用) */
function voiceSessionReset() {
  voiceSession.state = { file: { path: null, read: false }, current: { id: null, at: 0, origin: '', cwd: '' }, rejected: null }
}

/**
 * 多久之内那一场算"当前对话", 过了就当没有 —— 这是需求点名的那个数
 *
 * **2026-10-07 主人把 1 小时改成 20 分钟** ("ball 开新对话间隔 1h 改为 20min"): 从最后一句
 * 成功投递起算, 那个窗口之内点球 / 喊唤醒词 / 打字都接在同一场, 过了才新开一场
 *
 * **2026-10-11 又缩到 10 分钟** (主人: "连续ball的对话从20min缩短到10min") —— 20 分钟那一档
 * 主人用下来觉得"隔了十几分钟还接着上一场"太粘, 所以缩到 10 分钟
 */
const VOICE_SESSION_MS = 10 * 60 * 1000

/** 会话 id 是无符号 64 位, **别进整数**: 与虚拟屏那块屏的 id 同一个坑 */
function voiceSessionPath() {
  const inbox = voiceInboxPath()
  return inbox === null ? null : join(dirname(inbox), 'session.json')
}

/** 账本读一次就够: 之后都以内存里那份为准 (写的时候顺手落盘) */
async function voiceSessionLoad() {
  const path = voiceSessionPath()
  const state = voiceSession.state
  if (path === null || state.file.read) return
  state.file = { path, read: true }
  const raw = await readFile(path, 'utf8').catch(() => null)
  if (raw === null) return
  try {
    const record = JSON.parse(raw)
    const id = record?.id === undefined || record?.id === null ? '' : String(record.id)
    const at = Number(record?.at)
    // `origin` / `cwd` 是 2.7.8 批次 2 加的: 老账没有这两个字段, 读起来就是空 (于是它进不了
    // [voiceLedgerUsable] 那条判据 —— 见那里的注释)
    if (id && Number.isFinite(at)) {
      state.current = {
        id,
        at,
        origin: typeof record?.origin === 'string' ? record.origin : '',
        cwd: typeof record?.cwd === 'string' ? record.cwd : '',
      }
    }
  } catch {
    // 半行 / 坏文件: 当没有, 下一次投递会重新写一份
  }
}

/**
 * 这本账指向的那一场**是不是真的落在 `dsh-ball` 里** (2.7.8 批次 2 根因二)
 *
 * 判据只看账上记下的 `cwd`: 2026-10-17 之前写的账没有这个字段 (那时**每次投递都无条件写**, 于是
 * 主人"在 GUI 那场里问一句 -> 在框里答一句"就能把它改写成那场 GUI 会话) —— 那种账**一律当没有**,
 * 不做数据搬家, 下一次投递自然写一份新的。有 cwd 但不在浮标那个目录底下, 同样当没有
 *
 * 抽成纯字符串判据是为了让 `tools/check-voice-inbox.mjs` 直接量它 (两边都先去尾斜杠, 于是
 * `/x/dsh-ball/` 与 `/x/dsh-ball` 是同一个地方)
 *
 * @param record - `voice/session.json` 读出来的那一份 (`{ id, at, origin, cwd }`)
 * @param directory - 浮标那个工作区目录
 * @returns 能不能拿它当"10 分钟内可复用的那一场"
 */
function voiceLedgerUsable(record, directory) {
  return voicePathUnder(record?.cwd, directory) && voicePathUnder(directory, directory)
}

/** 纯字符串那一半: 同一个路径, 或者 `child` 在 `parent` 底下 (两边都去掉尾斜杠再比) */
function voicePathUnder(child, parent) {
  const inner = voicePathNorm(child)
  const outer = voicePathNorm(parent)
  if (inner === '' || outer === '') return false
  return inner === outer || inner.startsWith(`${outer}/`)
}

/** 去尾斜杠并把反斜杠归一成正斜杠 (`/` 与空串都归一成空串, 于是"没填"与"根"不会被当成同一个地方) */
function voicePathNorm(value) {
  return String(value ?? '').replace(/\\/gu, '/').replace(/\/+$/u, '')
}

/** 现在那一场"当前对话"的 id, 没有 (或者过了 [VOICE_SESSION_MS]) 就是 null */
async function voiceCurrentSession() {
  await voiceSessionLoad()
  const current = voiceSession.state.current
  if (!current.id) return null
  if (Date.now() - current.at > VOICE_SESSION_MS) return null
  // 这一场得真的是浮标开出来的: 那本账被 GUI 会话改写过 / 是 2026-10-17 之前的老账, 都当没有
  const directory = await canonicalVoicePath(ballWorkspace())
  if (!voiceLedgerUsable(current, directory)) {
    voiceSession.state.rejected = {
      id: current.id,
      cwd: current.cwd,
      why: current.cwd === ''
        ? 'the ledger predates the cwd field, so it cannot prove it points at the ball workspace'
        : `the ledger points outside ${directory}`,
    }
    return null
  }
  voiceSession.state.rejected = null
  return current.id
}

/**
 * 一句话投出去之后的记账: **每次发送都重算这一笔账**, 所以"10 分钟内可复用"是从最后一句起算
 *
 * **不是每一次投递都该记** (2.7.8 批次 2 根因二): 只有球自己那一句 (`origin === 'ball'`) 才改这本账,
 * 见 [voiceDeliver] 那一处调用 —— 回复框点名与自动指令各有各的落点
 *
 * @param sessionId - 投给哪一场
 * @param extra - `{ origin, cwd }`: 这一句是谁发的、那一场的 cwd (cwd 先过 realpath, 见
 *   [canonicalVoicePath] —— 影子会话的 cwd 写 `/data/data/…` 而 `DSH_HOME` 是 `/data/user/0/…`)
 */
async function voiceSessionBump(sessionId, extra = {}) {
  const path = voiceSessionPath()
  const next = { id: String(sessionId), at: Date.now() }
  if (typeof extra.origin === 'string' && extra.origin !== '') next.origin = extra.origin
  if (typeof extra.cwd === 'string' && extra.cwd !== '') next.cwd = await canonicalVoicePath(extra.cwd)
  voiceSession.state.file = path === null ? { path: null, read: true } : { path, read: true }
  voiceSession.state.current = next
  voiceSession.state.rejected = null
  if (path === null) return
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${JSON.stringify(next)}\n`)
}

/** 一个路径的 realpath; 读不到就原样回 (拿不到不等于不相等, 但那时也没有更好的答案) */
async function canonicalVoicePath(value) {
  const path = String(value ?? '')
  if (path === '') return ''
  try {
    return await realpath(path)
  } catch {
    return voicePathNorm(path)
  }
}

/**
 * 那本账现在长什么样 (给 `lw_voice op=inbox` 与那份读数文件看)
 *
 * `usable` 是**能不能拿它当"10 分钟内可复用的那一场"**: 有 id、在 10 分钟以内、而且 cwd 落在
 * `dsh-ball` 底下 ([voiceLedgerUsable])。`why` 在不 usable 时说清是哪一条不成立
 */
async function voiceLedgerSnapshot() {
  await voiceSessionLoad()
  const record = voiceSession.state.current
  const directory = await canonicalVoicePath(ballWorkspace())
  const exists = record.id !== ''
  const fresh = exists && Date.now() - record.at <= VOICE_SESSION_MS
  const usable = fresh && voiceLedgerUsable(record, directory)
  const why = usable
    ? ''
    : !exists
      ? 'there is no ledger yet'
      : !fresh
        ? 'the ledger is older than the reuse window'
        : voiceSession.state.rejected?.why ?? 'the ledger does not point at the ball workspace'
  return {
    id: record.id ?? '',
    at: record.at ?? 0,
    origin: record.origin ?? '',
    cwd: record.cwd ?? '',
    usable,
    why,
  }
}

/**
 * 三项读数 (2.7.8 批次 2 第一步): **这一场属于哪个工作区 / 它的 cwd / `dsh-ball` 注册没注册**
 *
 * 只读, 而且一律 try —— 读数不许把 `op=inbox` 弄挂。cwd 从会话存储里拿 (`ctx.sessions.get` 是便宜
 * 的那一条, 不为了读数去 `resolveAgent` 把一场冷会话复活)
 *
 * @param ctx - host context (工具那一层要用模块级的 `hostCtx`)
 * @param sessionId - 要问的那一场 (那本账指向的那一场), 空串就只报"注册没注册"
 * @param state - 哪一条通道的工作区读数 (浮标那条缺省; 探索那条传 [exploreWorkspaceState])
 */
async function voiceWorkspaceReadings(ctx, sessionId, state = ballWorkspaceState) {
  const readings = {
    sessionId: String(sessionId ?? ''),
    workspace: '',
    workspaceId: '',
    member: null,
    cwd: '',
    // 缺省那两行就是浮标那条的读数 (判据钉着这个字面量, 见 tools/check-voice-inbox.mjs 第 15 节);
    // 探索那条把自己的 state 传进来, 下面那两行覆盖成它的
    registered: ballWorkspaceState.registered,
    registeredWhy: ballWorkspaceState.why,
  }
  if (state !== ballWorkspaceState) {
    readings.registered = state.registered
    readings.registeredWhy = state.why
  }
  const id = readings.sessionId
  if (id === '') return readings
  try {
    const cwd = String(ctx?.sessions?.get?.(id)?.header?.cwd ?? '')
    readings.cwd = cwd
    if (cwd === '') return readings
    const canonical = await canonicalVoicePath(cwd)
    const list = ctx?.get?.('workspaceRegistry')?.list?.() ?? []
    for (const workspace of list) {
      if (await canonicalVoicePath(workspace.path) !== canonical) continue
      readings.workspace = String(workspace.title ?? '') || String(workspace.path ?? '')
      readings.workspaceId = String(workspace.id ?? '')
      // **在不在那条工作区的账上**才是会话列表分组的那条判据 (cwd 对得上但不入账就是「未分组」)
      readings.member = Array.isArray(workspace.sessionIds)
        ? workspace.sessionIds.some((sessionId) => String(sessionId) === id)
        : null
      break
    }
  } catch (error) {
    readings.error = String(error?.message ?? error)
  }
  return readings
}

/** 那份读数文件放哪 (`$DSH_HOME/lw/voice-inbox.json`) */
function voiceReadingsPath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', 'voice-inbox.json') : null
}

/**
 * 把同一份读数落一盘, **原子写** (`.tmp` + rename, 与其他那几份 `lw/*.json` 同一个做法)
 *
 * 为什么要有这个文件: `lw_voice op=inbox` 是**给模型看的**读数, 而在没有模型可问的时候 (纯设备验收)
 * 那份回执根本打印不出来 —— 这一批的病根在运行期, 只靠读代码只能猜到"大概是哪一条", 所以要有一个
 * `adb cat` 就能看的东西。文件里的字段与那份回执同一份来源
 */
async function voiceReadingsWrite(ctx) {
  const path = voiceReadingsPath()
  if (path === null) return
  const ledger = await voiceLedgerSnapshot()
  const readings = await voiceWorkspaceReadings(ctx, ledger.id)
  const snapshot = {
    v: 1,
    at: Date.now(),
    delivery: {
      lines: voiceDelivery.lines,
      origin: voiceDelivery.origin,
      current: voiceDelivery.current,
      last: voiceDelivery.sessionId,
      opened: voiceDelivery.opened,
      why: voiceDelivery.why,
    },
    ledger,
    workspace: readings.workspace,
    workspaceId: readings.workspaceId ?? '',
    member: readings.member ?? null,
    cwd: readings.cwd,
    registered: readings.registered,
    registeredWhy: readings.registeredWhy,
    ballWorkspace: ballWorkspaceState.path ?? ballWorkspace(),
    ballWorkspaceId: ballWorkspaceState.id ?? '',
  }
  try {
    await mkdir(dirname(path), { recursive: true })
    const temporary = `${path}.tmp`
    await writeFile(temporary, `${JSON.stringify(snapshot, null, 2)}\n`)
    await rename(temporary, path)
  } catch (error) {
    warn(hostCtx, `the voice readings could not be written: ${error?.message ?? error}`)
  }
}

/**
 * 目标会话: **默认只投浮标自己那一场, 而选场按优先级问三条** —— 回复框点名 > 那一笔账 > 新开
 *
 * 三条判据 (前两条是主人 2026-10-06 的口径: "只要是 1 小时内, 无论点球/喊唤醒词 都只在同一场
 * 对话" + 当天那条例外 "有回复框时, 框里进的输入走发出回复那个窗口"; **2026-10-07 那个"1 小时"
 * 改成 20 分钟, 2026-10-11 又缩到 10 分钟**):
 *
 * - **回复框点名的 `to`** ([voiceReadNew] 那一行读出来的): 它还在、还是根会话就投它 —— 框里
 *   发出的键盘与语音都从这条路进来, 于是"接着回答那个窗口"不会因为那笔账过了就换场
 * - 10 分钟内那一场 ([voiceCurrentSession], 需求点名的那个数): 复用它 —— 它本来就是浮标开出来的,
 *   唤醒词命中之后的头一句也一样接上去 (**`fresh` 不再另开一场**, 2026-10-05 那版"喊一声就是换
 *   一件事说"作废)
 * - 那笔账过了 / 那一场不在了 / 从来没有过: 开一场新的 —— 而新对话落在浮标那个工作区里 (见
 *   [ensureBallWorkspace])
 *
 * 也就是说"浮标的对话"与"主人在界面里用哪一场"是两件事
 *
 * **改过什么, 为什么改**: 原来是四条, 还多两条 —— "有正在跑的那一轮就 `steer` 插进去"与"都没有就
 * 投给会话列表里最近动过的那个根会话"。那两条会把一句浮标里说的话落进**主人此刻正在界面里用的那一场**
 * (它的 cwd 是 `/sdcard/DSH` 之类, 不是 `dsh-ball`), 于是"浮标的对话都在 dsh-ball 里"这件事就不成立
 * 了 —— 主人报的正是这一条。现在 [voiceDeliver] 只问这一个函数, 而它只认浮标自己那一场: 那一场正在跑
 * 就 `steer`, 没在跑就 `followup`, 两种都落在浮标工作区
 */
/**
 * 这一句话该试哪些会话, **按优先级排**: 点名的那一场 → 浮标那一笔账 (10 分钟); 空表就是"开一场新的"
 *
 * (前两条是主人 2026-10-06 的口径: "只要是 1 小时内, 无论点球/喊唤醒词 都只在同一场对话" +
 * 当天那条例外 "有回复框时, 框里进的输入走发出回复那个窗口"; 那个 1 小时 2026-10-07 改成 20 分钟,
 * 2026-10-11 又缩到 10 分钟。`fresh` 只是记号, 不决定开不开新场)
 *
 * **这里一次会话表都不扫** (2026-10-15 真机上量到 `controller.list` 要 **11~12.8 秒** —— 投递总耗时
 * 的 99%, 因为它要读全部会话头与投影缓存, 而这台机器 38 个工作区里堆了上千场): 目标本来就有 id,
 * 那两场真的不在了 (被删 / 换过工作区), 由投递时 `resolveAgent` 的失败来发现 —— 调用方按这个顺序
 * 逐个试, 全试不出去才开新的一场 ([voiceDeliver] 里那个 for)
 */
async function voiceTargetCandidates(to) {
  const candidates = []
  const asked = String(to ?? '').trim()
  if (asked !== '') {
    candidates.push({ sessionId: asked, why: 'the reply box asked for its own conversation' })
  }
  const current = await voiceCurrentSession()
  if (current !== null && current !== asked) {
    candidates.push({ sessionId: current, why: 'the ball conversation, reused' })
  }
  return candidates
}

/**
 * 开一场新的浮标对话
 *
 * **这一跳要带一个能解析出来的预设** (2026-10-09): 见 [chooseVoicePreset] 上面那段; 解析得出来但
 * 起不来 (broken mount) 时再退一次 `standard`, 别的错照抛
 *
 * @returns `{ sessionId, preset, presetWhy }` (后两个进读数, 见 `voiceDelivery.preset`)
 */
async function openBallConversation(ctx, controller, workspace) {
  const preset = await chooseVoicePreset(ctx)
  const request = {
    ...ballCreatePlace(workspace),
    ...(preset.id === undefined ? {} : { agentPreset: preset.id }),
  }
  try {
    const created = await controller.create(request)
    return { sessionId: created.sessionId, preset: preset.id ?? '', presetWhy: preset.why }
  } catch (error) {
    if (preset.id === undefined || preset.id === 'standard') throw error
    const retry = await controller.create({
      ...ballCreatePlace(workspace),
      agentPreset: 'standard',
    })
    return {
      sessionId: retry.sessionId,
      preset: 'standard',
      presetWhy: `${preset.why}; the create failed (${describePresetFailure(error)}),`
        + ' so standard was used instead',
    }
  }
}

/**
 * 开新会话那一个请求里"落在哪"的那一半
 *
 * **有工作区 id 就带 id**: `sessionController.create` 只在带 `workspaceId` 时才把这一场 `attachSession`
 * 进那条工作区的账 (`packages/api/session-controller/src/commands.ts:105-141`), 而"在不在账上"正是会话
 * 列表分组的那条判据 —— 只给 `cwd` 的话会话落对了目录却仍是「未分组」。没有 id (这个 profile 没有
 * 工作区注册表) 才退回 `cwd`
 */
function ballCreatePlace(workspace) {
  if (workspace === null || workspace === undefined) return {}
  if (typeof workspace.id === 'string' && workspace.id !== '') return { workspaceId: workspace.id }
  return typeof workspace.path === 'string' && workspace.path !== '' ? { cwd: workspace.path } : {}
}

/**
 * 把一句话投进那一场: 正在跑就插进当前轮 (D6), 否则排上并唤醒它
 *
 * **`resolveAgent` 是这条链上唯一的"这一场还在不在"判据** —— 它快 (真机实测 60 ms 上下), 于是
 * "要不要扫会话表"这件事整个不需要了 (见 [voiceTargetCandidates])
 */
async function deliverToBall(ctx, controller, sessionId, line, auto) {
  return ctx.agents.withoutInitiator(async () => {
    const resolved = await controller.resolveAgent(sessionId)
    if ('error' in resolved) throw resolved.error
    const { agent } = resolved
    const running = agent.status === 'running'
    const message = await createVoiceMessage(line.text, auto.images)
    if (running) agent.steer(message)
    else agent.followup(message)
    const flushed = await ctx.sessions.flush(agent.session)
    // `cwd` 一起带回去: 投递完那一句之后要拿它写账 (2.7.8 批次 2), 而这一步已经把 header 拿在手里了
    return { running, flushed, screenshot: auto.images.length > 0, cwd: String(agent.session?.header?.cwd ?? '') }
  })
}

/**
 * 双击打断该打哪一场
 *
 * 判据与球上那三个字同源: 球显示的是"哪几场在跑"里**最近开始的那一场** (`ball-phase.json` 里
 * `startedAt` 最大的), 所以打断也打那一场 —— 显示什么就停什么, 中间没有第二套口径
 *
 * @param items `controller.list` 的那些条目 (只有 `running === true` 且是根会话的才算在跑)
 * @param turns 宿主那本账: `{ id, startedAt }` 的数组
 * @returns 要取消的 `sessionId`; 没有在跑的轮就是 `null` (**不是空字符串**)
 */
function pickInterruptTarget(items, turns) {
  const list = Array.isArray(items) ? items : []
  const running = new Set(
    list
      .filter((item) => item && item.running === true && item.origin !== 'subagent' && !item.parentSessionId)
      .map((item) => String(item.sessionId)),
  )
  let best = null
  for (const turn of Array.isArray(turns) ? turns : []) {
    if (turn === null || typeof turn !== 'object') continue
    const id = String(turn.id ?? '')
    const startedAt = Number(turn.startedAt)
    if (!id || !running.has(id) || !Number.isFinite(startedAt)) continue
    if (best === null || startedAt > best.startedAt) best = { id, startedAt }
  }
  return best === null ? null : best.id
}

/**
 * 开新会话之前先把预选定下来 (2026-10-09)
 *
 * 主人 2026-10-09 报的那条: 新装的手机上 `custom` 预设的声明没装进去, 而设备上存着"上次选的就是
 * 它", 于是 `sessionController.create()` 直接抛 `agent-preset/not-found` —— 表现是**语音输入开不了
 * 新的会话** (那句话根本没地方去)。一句话不该被一个缺失的预设整个掐掉, 所以这里逐级回退:
 * **当前默认 -> standard -> 注册表报出来的其他候选**, 谁先解析得出来用谁
 *
 * 回退不是静默降级: 用了哪一个、为什么换, 原文进 `voiceDelivery.presetWhy` (与 `op=status` 那一行),
 * 也进日志。判据在 `tools/check-voice-inbox.mjs`
 *
 * @param ctx - host context carrying `agentPresets`.
 * @returns {Promise<{id?: string, why: string}>} `id` 为 undefined = 这个 profile 没有预设注册表,
 *   照老样子让 `create()` 自己挑
 */
async function chooseVoicePreset(ctx) {
  const presets = ctx.get('agentPresets')
  if (!presets || typeof presets.resolve !== 'function') {
    return { id: undefined, why: 'this profile has no preset registry, so the create picks for itself' }
  }
  try {
    const current = await presets.resolve(undefined)
    return { id: current.id, why: `the current default preset (${current.id})` }
  } catch (error) {
    const first = describePresetFailure(error)
    const available = Array.isArray(error?.details?.available) ? error.details.available : []
    // standard 是 dsh 出厂那一个, 所以它排在最前; 其余候选按注册表报出来的顺序试
    for (const id of ['standard', ...available]) {
      try {
        const resolved = await presets.resolve(id)
        return { id: resolved.id, why: `${first}; fell back to ${resolved.id}` }
      } catch {
        // 这一个也不行, 试下一个
      }
    }
    return { id: undefined, why: `${first}; no registered preset resolved, so the create below reports it` }
  }
}

/** 一个预设解析失败的短句 (回退那句"为什么换"要用它, 而不是 `[object Object]`) */
function describePresetFailure(error) {
  const code = error?.code ?? error?.message ?? String(error)
  const wanted = error?.details?.agentPreset
  return wanted === undefined
    ? `the default preset could not be resolved (${code})`
    : `preset "${wanted}" is not registered (${code})`
}

function voiceBlockEnd() {}

/* ── 指代不明时自动附一张主屏截图 (批次 4 的需求 9) ───────────────────────────
 *
 * "看屏"这件事不该是一件用户先做的事 (以前要先切识屏模式): 用户说"根据这个装修风格""把照片里的
 * 白色瓶子 P 掉"的时候, 他指的是眼前那块屏, 而不是在给助手布置一道题。所以投递之前先判一句话里
 * 有没有那种指代的口气, 有就顺手截一张主屏 (display 0) 一起投进去。
 *
 * **只覆盖浮标输入框与语音这两条投递路径**: GUI 页面里打字的那些消息走 dsh 自己的 rpc, 插件拦不到
 * —— 那一半由 `assets/modes/phone.md` 里"指代不明先看屏"那条提示词兜底
 *
 * 词表是**子串命中**, 与命令表那套"整句相等"正好相反, 理由也相反: 命令表认错人会把主人真正说的一句
 * 话吃掉 (那是不可逆的), 而这里认错人只是多附一张图 (模型看得见, 主人在正文里一个字都没少)。所以
 * 宁可宽一点 —— 但"这样"这种泛指不收: 它说的是做法, 不是屏幕上的东西
 */

/** 命中就附图的那几个说法: 改这一份要连着跑 `tools/check-auto-shot.mjs` */
const SCREEN_REF_WORDS = [
  '这个', '这张', '这个图', '这张图', '屏幕上', '屏幕里', '照片里', '图里', '这份',
]

/** 这一句话是不是"指着屏幕上的东西在说" (纯函数: 没有设备也能量) */
function refersToScreen(text) {
  const said = String(text ?? '')
  if (!said.trim()) return false
  return SCREEN_REF_WORDS.some((word) => said.includes(word))
}

/**
 * 这一句话要不要附图, 要附就把它截出来
 *
 * 三道判据按"便宜的先问"排: 词表 (纯字符串) → 模式 (读一个几十字节的文件) → 设置 (一次桥调用)。
 * 所以普通一句话在这条路上只多一次 `String.includes`
 *
 * 设置读的是应用那一侧「截图」段那一条 (`screenshot` 事务的 `op=status`)。**读不到就照附**: 那条链
 * 本来就要过桥, 而"桥没起来"与"主人把它关掉了"是两件事 —— 照附的代价是白截一张图, 而那次截屏自己
 * 也会失败并如实报出来 (与朗读那条链同一个口径)。视频模式里**不附**: 那时"屏幕"指的是镜头 (需求 17)
 *
 * @returns `{tried, images, why}` —— `tried` 是"真的去截了", 而 `images` 空时 `why` 必须说得出理由
 */
async function autoScreenShot(text) {
  if (!refersToScreen(text)) return { tried: false, images: [], why: 'nothing in the line points at the screen' }
  if (activeMode() === MODE_VIDEO) {
    return { tried: false, images: [], why: 'video mode is on, so "the screen" means the camera' }
  }
  try {
    const setting = await call('screenshot', { op: 'status' })
    if (setting?.autoShot === false) return { tried: false, images: [], why: 'the setting is off' }
  } catch (error) {
    warn(hostCtx, `the auto-screenshot setting could not be read: ${error?.message ?? error}`)
  }
  try {
    // displayId 0 = 主人手里那块屏; 像素与字节预算不点名, 那两档由设置页给 (与 lw_screenshot 同一条)
    const answer = await call('screenshot', { displayId: 0 })
    const path = String(answer?.path ?? '')
    if (!path) {
      return { tried: true, images: [], why: `the screenshot came back without a path: ${answer?.error ?? 'no reason reported'}` }
    }
    const { images, note } = await attachPictures([path])
    if (images.length === 0) {
      return { tried: true, images: [], why: note ?? 'the attachment store would not take the picture' }
    }
    return { tried: true, images, why: null }
  } catch (error) {
    return { tried: true, images: [], why: `the screenshot failed: ${error?.message ?? error}` }
  }
}

/* ── 说出来的那几句命令 ────────────────────────────────────────────────────── */

/**
 * 主人说的哪几句话是**命令**, 而不是要投进会话的话 (批次 4.5)
 *
 * 两级设计 (可行性稿 2.7): 短唤醒词只做"有人在叫我", 紧随其后的那一句交给常驻 ASR, 由文本里解析意图
 * —— **所以命令词表在宿主这一侧**: 改它不必重下关键词表, 也不必重建 APK (推一个文件就行), 这正是
 * 当初选 host 侧那张表而不是 app 侧那张的理由
 *
 * 只有两个模式 (批次 4 把识屏模式摘掉了): `video` 是"提示词换成视频那份 + 常驻语音许可靠上 + 摄像头
 * 起来", `phone` 是收工那一条。**一句命令 = 一次桥调用** (见 [applyMode]): 这两句与模型调 `lw_mode`、
 * 与设备上那两个脚本 (`modes/{phone,video}.sh`) 走的是同一个切换。`say` 里那几行是说法上的变体 ——
 * 识别出来的句子不会被人念得一模一样, 而 `say` 里**没有列出来的**说法照旧当普通一句话投进会话 (宁可多
 * 一句对话, 也不要因为"猜它想切模式"而吃掉主人真正说的一句)
 *
 * 应用那一侧只保留三条规范句子 (`voice/VoiceCommands.kt`: 视频 / 手机 / 打断): 浮标菜单、浮标上那一记
 * 双击与「叫醒之后」那个开关写的就是它们, 而 `tools/check-voice-commands.mjs` 拿两份源码对着核, 两份
 * 不许漂
 *
 * **识屏那两句只留"关"这一半**: 模式本身摘掉了 (那块屏现在由手机模式管, 见 `phone.md`), 而"退出识屏
 * 模式 / 关闭识屏模式 / 关掉识屏模式"照旧是"收工回手机模式" —— 说惯了的人不会因为一个模式消失就切不动。
 * "打开识屏模式"那一半**不再是命令**: 它照普通一句话进会话, 由手机模式自己去看那块屏
 *
 * **`interrupt` 那一支不是模式** (主人 2026-10-06 加的): 它说的是"把浮标那一场正在跑的轮打断",
 * 入口是**球上那一记双击** (见 [runVoiceInterrupt]), 与那三句模式命令共用"整句相等"这一套
 */
const VOICE_COMMANDS = [
  {
    mode: 'video',
    say: ['打开视频模式', '进入视频模式', '切到视频模式', '换成视频模式', '视频模式'],
  },
  {
    mode: 'phone',
    say: [
      '回到手机模式',
      '退出视频模式',
      '关闭视频模式',
      '关掉视频模式',
      '手机模式',
      // 识屏那两句的"关" (兼容说法): 识屏模式没了之后它就是收工那一条, 与回到手机模式同一件事;
      // "打开识屏模式"不在这里 —— 它已经不是命令了, 照一句话投进会话
      '退出识屏模式',
      '关闭识屏模式',
      '关掉识屏模式',
    ],
  },
  {
    // 打断: 与那三句模式命令同一类 (整句相等, 不进会话), 但做的事是"取消浮标那一场正在跑的轮"
    // 说法只加需要的这两条 —— 命令词表认错人比少认一句糟得多 ("打断"开头的话里很容易夹着一句
    // 主人真想问的事)
    interrupt: true,
    say: ['打断当前回答', '打断这一轮'],
  },
]

/** 空白与标点会被吃掉 (说出来的句子末尾会带句号), 其余一个字都不许差 */
function normalizeCommand(text) {
  return String(text ?? '')
    .replace(/[\s，。！？、,.!?;；:：'"“”「」『』]/g, '')
    .toLowerCase()
}

/**
 * 整句相等才算命令
 *
 * **不做包含匹配**是这一处最容易写错的地方: "视频模式怎么改" 里就含着那四个字, 而它是一句要投进
 * 会话的话 —— 命令词表认错了人, 主人会看到自己的问题没了
 */
function matchVoiceCommand(text) {
  const said = normalizeCommand(text)
  if (said === '') return null
  for (const command of VOICE_COMMANDS) {
    if (command.say.some((one) => normalizeCommand(one) === said)) return command
  }
  return null
}

/** 被当成命令吃掉的句子: 几条、最近一条是什么、成没成 (lw_voice 会念它) */
const voiceCommands = { count: 0, last: null, error: null }

/**
 * 双击打断那一条的去向: 要了几次、真的取消了几场、最近一次为什么没取消 (lw_voice 会念它)
 *
 * 与 [voiceCommands] 分开是两个理由: 它不是"切模式"那一类 (没有 `mode`), 而它的成功判据也不是
 * "切没切过去" —— "那一场没在跑"是一个**正常结果** (球上那三个字还该落下), 不是失败
 */
const voiceInterrupt = { count: 0, cancelled: 0, last: null, error: null }

/**
 * 打断: **取消"正在想"所指的那一轮** (主人 2026-10-08 定的范围)
 *
 * 入口是球上那一记双击 ("正在想"里 300 ms 之内两下, 见 `OverlayService.onTap`), 而它落到这里只
 * 经过一份队列 ([startVoiceInbox] 那一份) —— 应用那侧写一句 [VoiceCommands.INTERRUPT], 这一侧认出
 * 来就地执行, **绝不进会话** (命令是"对这台手机说的", 不是"对助手说的一句话")
 *
 * 三件事与"切模式"那三句不同, 都要记住:
 *
 * 1. **打的是"正在想"指的那一场** (主人 2026-10-08: "双击暂停不会跟随停止"): 球上那三个字是
 *    [startBallPhase] 按"哪几场在跑"写的, 所以打断也要打同一件事 —— 挑正在跑的那几场里**最近开始
 *    的那一场** ([pickInterruptTarget])。原来只打 [voiceCurrentSession] 记的"浮标那一场", 于是任务
 *    跑在别的会话里时双击等于什么都没停
 * 2. **不推状态**: 那一轮真的停了, 宿主写 `ball-phase.json` (见 [startBallPhase]), 球上那三个字才落。
 *    没有在跑的轮时什么都不取消、也**不清字** —— 原来那句无条件的 `pushBallIdle()` 正是"字落了而任务
 *    还在跑"的来源
 * 3. **不 resolve 冷会话**: `controller.resolveAgent` 会把一个没活着的会话恢复起来, 于是"打断"
 *    会变成一个"启动" —— 所以先问 `controller.list` 那一份 `running`, 不在跑就不打它
 *
 * `agent.cancel({ kind: 'user' }, { keepInbox: true })` 与界面那个停止键是同一条
 * (`packages/api/session-controller/src/commands.ts`): `keepInbox` 保住还没投出去的几句,
 * 而不是把它们一起丢掉
 */
async function runVoiceInterrupt(ctx, line) {
  const controller = ctx.get('sessionController')
  if (!controller) {
    throw new Error('this profile has no session controller, so an interrupt has nowhere to go')
  }
  const signal = new AbortController().signal
  const listed = await controller.list({}, signal)
  // **这里是 [ballTurnList] 不是 [ballTurns]** (2026-10-12 修): 后者是一张 Map, 当函数调会在宿主侧抛
  // "ballTurns is not a function" —— 双击打断从 2.6.0 起一直死在这一行 (真机上量到: 语句进了收件箱、
  // 宿主也认出它了, 却在挑目标这一步整个失败, 那一轮照跑), 判据见 tools/check-voice-inbox.mjs
  const sessionId = pickInterruptTarget(listed.items, ballTurnList())
  let cancelled = false
  let detail = 'no turn was running, so there was nothing to interrupt'
  if (sessionId !== null) {
    const resolved = await ctx.agents.withoutInitiator(() => controller.resolveAgent(sessionId))
    if ('error' in resolved) throw resolved.error
    resolved.agent.cancel({ kind: 'user' }, { keepInbox: true })
    cancelled = true
    detail = `cancelled the turn running in ${sessionId}`
  }
  voiceInterrupt.count += 1
  if (cancelled) voiceInterrupt.cancelled += 1
  voiceInterrupt.error = null
  voiceInterrupt.last = { at: Date.now(), said: line.text, sessionId, cancelled, detail }
  return { interrupt: true, cancelled, sessionId, said: line.text, detail }
}

/**
 * 执行一句命令: 走 [applyMode], 不投会话
 *
 * **失败也把游标前移**: 认出"这是一句命令"这件事已经做对了, 而"切模式没成"是一句要报出来的结果,
 * 不是一条要反复重投的句子 (那条队列每 150 ms 读一次, 抛异常会把它变成每 150 ms 重试一次)
 */
async function runVoiceCommand(command, line) {
  try {
    const answer = await applyMode(command.mode)
    const switched = answer.switched === true
    // 退出视频模式那一路也当场还原 (语音命令没有会话身份, 所以按记下来的那几场还) ——
    // 只靠 turn/start 兜的话, 退出后的**第一句**仍然会用视频模型回 (真机 2026-10-16 量到的)
    if (switched && command.mode === MODE_PHONE) {
      await restoreModeModelSessions(hostCtx)
    }
    voiceCommands.count += 1
    voiceCommands.error = switched ? null : `"${line.text}" did not switch the mode: ${answer.detail}`
    voiceCommands.last = { at: Date.now(), said: line.text, mode: answer.mode ?? command.mode, switched }
    return {
      command: true,
      switched,
      mode: answer.mode ?? command.mode,
      said: line.text,
      detail: switched ? modeLines(answer).join('\n') : String(answer.detail ?? ''),
    }
  } catch (error) {
    const reason = error?.message ?? String(error)
    voiceCommands.count += 1
    voiceCommands.error = `running "${line.text}" failed: ${reason}`
    voiceCommands.last = { at: Date.now(), said: line.text, mode: command.mode, switched: false }
    return { command: true, switched: false, mode: command.mode, said: line.text, detail: reason }
  }
}

/* ── 语音投递: 一句话怎么变成会话里的一条消息 ─────────────────────────────── */

/** 投递的去向与结果, 给 lw_voice 看: 投了几条、投给谁、是插进去的还是排上的、最近一次为什么失败 */
const voiceDelivery = {
  lines: 0,
  sessionId: null,
  /** 现在那一场"当前对话" (10 分钟内可复用的那个) */
  current: null,
  /** 这里面有几条是**复用**了当前对话 (而不是新开一场 / 投给最近动过的) */
  reused: 0,
  /** 最近一次为什么投给那一场: 正在跑的轮 / 复用的当前对话 / 最近动过的 / 新开的 */
  why: null,
  /**
   * 最近那一句是谁发的 (2.7.8 批次 2 的读数): `ball` (点球 / 唤醒词 / 浮标框里的键盘输入) /
   * `replyBox` (回复框点名了某一场) / `automation` (自动指令到点)
   *
   * 这三个值同时决定"要不要改 `voice/session.json` 那本账" —— 只有 `ball` 才改
   */
  origin: null,
  steered: 0,
  queued: 0,
  /** 其中几条真的**新开了一场** (那笔账过了 / 那一场不在了, 见 [voiceTargetCandidates]) */
  opened: 0,
  /** 试满 [VOICE_DELIVER_TRIES] 次也没投出去、被跳过的句子有几条 */
  skipped: 0,
  /** 最近一条被跳过的 (序号 / 正文 / 为什么) —— 它必须答得出来, 跳过不等于静默丢掉 */
  lastSkip: null,
  /**
   * 最近一次新开会话时用的预设, 以及为什么用它 (2026-10-09)
   *
   * 新装的手机上 `custom` 预设的声明没装进去时, 开新会话会以 `agent-preset/not-found` 失败 ——
   * 那条路上现在逐级回退, 而这两个字段就是"最后用了哪一个、为什么换"的读数
   */
  preset: null,
  presetWhy: null,
  /**
   * 自动附图那条路 (批次 4 的需求 9): 真的去截了几条 / 附上了几条 / 命中词表但没附几条 / 最近一条
   *
   * 它与投递本身分开记, 因为"这一句投出去了"与"它带着一张图投出去"是两件事 —— 主人问"为什么它没看见
   * 我屏幕上的东西"时, 唯一答得出来的就是这几个数
   */
  autoShot: { tried: 0, attached: 0, skipped: 0, last: null },
  last: null,
  error: null,
}

/** `@deepseek-ai/dsh-llm` 的模块命名空间, 按需加载一次 */
let messageFactory = null

/**
 * 造一条用户消息
 *
 * 为什么按需 import 而不是写在文件头上: 这个插件的**模块级依赖只有一个** (工具 schema 在模块求值
 * 时就编译完了), 而多一个静态 import 就多一个"整包加载失败"的理由 —— `tools/check-host-plugin.mjs`
 * 那种只准备了 `dsh-tools` 的环境会在 import 那一步就死, 53 个工具跟着一起没有,按需加载把"投递
 * 这条路缺东西"与"所有工具都没有"分开: 拿不到工厂时投递如实报错, 工具照旧
 *
 * `source` 那个写法是这批里最容易写错的一处, 理由见 [voiceDeliver]
 *
 * [images] 是指代词命中时 [autoScreenShot] 截下来的那张主屏截图 (0 或 1 张): 图块与正文一起进这一条
 * 用户消息 —— 界面上与主人自己发的图长得一样, 而正文一个字都没改
 */
async function createVoiceMessage(text, images = []) {
  if (messageFactory === null) messageFactory = await import('@deepseek-ai/dsh-llm')
  return messageFactory.createUserMessage({
    content: [
      { type: 'text', text },
      ...images.map((attachment) => ({ type: 'image', attachment })),
    ],
    source: { kind: 'user', via: 'voice' },
  })
}

/**

/**
 * 把一句话送进会话
 *
 * D6/D7 (主人 2026-10-05 定): **有正在跑的轮就 `steer` 插进去; 没有会话就新建一个再发**,
 * 会话不活着也能投: `resolveAgent` 会把它恢复起来 (与 dsh 自己的 schedule 那条路同一个做法), 所以
 * "应用在后台说了一句话"不会因为界面没开着而丢掉
 *
 * **`line.wake` 不再改写目标** (主人 2026-10-06 改口径: "只要是 1 小时内, 无论点球/喊唤醒词 都只在
 * 同一场对话"): 它只是"这一句是唤醒之后的头一句"那个记号, 选哪一场由 [voiceTargetCandidates] 按那一
 * 时间定 —— 10 分钟内一律复用浮标那一场 (正在跑就 steer), 那笔账过了才新开一场。界面**不会跟着切过去**:
 * 会话是这里建的, 而"在看哪一个"是浏览器自己的路由状态, 应用那侧没有一条让页面切会话的路 —— 所以
 * 新对话真的在跑、回答也会念出来, 但人可能正看着另一个会话。这是主人选的"改动最小"那一档
 *
 * **来源标记怎么写是这批里最容易写错的一处** (批次 2.2)。两种写法都"有来源", 但只有一个是对的:
 *
 * - `{ kind: 'user', via: 'voice' }` —— 对了,`MessageSourceMap` 是合并扩展的, 各生产者声明自己
 *   的键, 而 `user-rpc` 就是这么干的 (`{ kind: 'user', rpcId … }`, GUI 自己发的每一条都是它)
 * - `{ kind: 'voice' }` —— 错了,会话界面按 `source.kind !== 'user'` 分流: 认不出的一律画成
 *   **注入的上下文行**, 而不是主人自己的那个气泡; 会话标题、活动、steering 历史那一整套也都以
 *   `kind === 'user'` 为准,所以新造一个 kind 等于把"主人说的话"降级成"系统塞进来的东西"
 *
 * 也就是说: kind 必须留 `user` (它决定这条消息是不是"主人说的"), `via: 'voice'` 才是那个标记
 *
 * **flush 之后才算投出去**: 游标只在投递成功之后前移, 中途崩了下次会重投, 而不是静默丢掉
 */
async function voiceDeliver(ctx, line) {
  // 探索那一档走**收件箱**进来 (2.7.8 批次 7; 批次 8 的探索卡片走的就是这条): 它不是一句话, 而是
  // "开一场探索" —— 于是它不进命令表、不截图、也不碰 `voice/session.json` 那本账 (那本账只认球自己
  // 那一句, 见 [voiceSessionBump])。检查 `source` 放在最前面: 这一步只多一次字符串比较
  if (line.source === 'explore') return startExplore(ctx, { text: line.text, via: 'inbox' })
  // 先看它是不是一句命令 (批次 4.5): 是的话走 applyMode, 不进会话 —— 所以这一句既不插正在跑的那
  // 一轮, 也不开新对话, 更不理会 `line.wake`。命令是"对这台手机说的", 不是"对助手说的一句话"
  //
  // **自动指令那一路不进命令表** (2.5.0 批次 8): 它投的是"某件事到了", 而一句话的正文由我们拼 (里面
  // 有规则名与它让模型做的事) —— 万一凑巧等于"手机模式"这种词, 那也不该变成切模式
  const automated = line.source === 'automation'
  const command = automated ? null : matchVoiceCommand(line.text)
  if (command !== null) {
    // 打断那一支与三句模式命令不是同一种动作 (一边切模式, 一边取消一轮), 所以从这里分开走 ——
    // 理由写在 [runVoiceInterrupt] 上面
    if (command.interrupt === true) {
      try {
        const outcome = await runVoiceInterrupt(ctx, line)
        console.log(
          `luwi-channel: the ball was double-tapped -> ${outcome.cancelled ? 'cancelled' : 'nothing to cancel'}`,
        )
        return outcome
      } catch (error) {
        const reason = error?.message ?? String(error)
        voiceInterrupt.count += 1
        voiceInterrupt.error = `interrupting failed: ${reason}`
        voiceInterrupt.last = { at: Date.now(), said: line.text, sessionId: null, cancelled: false, detail: reason }
        console.warn(`luwi-channel: the interrupt failed: ${reason}`)
        // **这里不碰状态** (2026-10-08): 球上那三个字是 `ball-phase.json` 说了算, 而"打断没成"时那一轮
        // 多半还在跑 —— 把字清掉只会变成"字落了而任务还在跑"
        return { interrupt: true, cancelled: false, said: line.text, detail: reason }
      }
    }
    const outcome = await runVoiceCommand(command, line)
    console.log(
      `luwi-channel: voice line #${line.seq} was a command -> ${outcome.mode} (${outcome.switched ? 'switched' : 'not switched'})`,
    )
    return outcome
  }
  const controller = ctx.get('sessionController')
  if (!controller) {
    throw new Error('this profile has no session controller, so a spoken line has nowhere to go')
  }
  const fresh = line.wake === true
  // **每一段各花多少毫秒要量** (2026-10-15 主人: "从 ball 到会话发送能缩到最小吗"): 投一句要 5~11 秒,
  // 而链上有五段各自可能很贵 (`list` 要扫全部会话头, 截图要过特权通道, `resolveAgent` 要把冷会话
  // 连预设一起复活) —— 不分开量就只能猜
  const startedAt = Date.now()
  const timing = {}
  const clock = { mark: startedAt }
  const beat = (key) => {
    timing[key] = Date.now() - clock.mark
    clock.mark = Date.now()
  }
  // **自动指令每次新开一场** (主人 2026-10-08 定): 不接回复框点名, 也不复用浮标那 10 分钟的一场 ——
  // "到点了提醒我"不是接着刚才那件事说, 而一次性提醒也不该把上下文带进主人的聊天里
  const candidates = automated ? [] : await voiceTargetCandidates(line.to)
  // 这一句是谁发的 (2.7.8 批次 2 的读数与判据): 自动指令 / 回复框点名 / 球自己
  const asked = String(line.to ?? '').trim()
  const origin = automated ? 'automation' : asked !== '' ? 'replyBox' : 'ball'
  // **新开的对话落在浮标那个专用工作区里** (主人 2026-10-06): 只对"这一句要开新对话"那一条路生效
  // (没有可复用的当前对话时: 那笔账过了 / 那一场不在了 / 从来没有过); 复用一个已经在跑的会话时不改它
  // 的目录 —— 一个会话的 cwd 是它自己的事, 中途换掉就是 `ApiSessionCwdConflict`
  const workspace = await ensureBallWorkspace(ctx)
  beat('workspace')
  // **投递之前先把"指代不明"那一张图备好** (批次 4 的需求 9): 截的是主人手里那块屏 (display 0), 而
  // 它不进正文 —— 图块与那句话一起构成这一条用户消息。截不出来照投文字, 理由记在读数里 (见
  // [autoScreenShot]): "这一句没投出去"与"这句投出去了但没带图"是两件事, 前者会重试, 后者不会
  const auto = await autoScreenShot(line.text)
  beat('shot')
  if (auto.tried) {
    voiceDelivery.autoShot.tried += 1
    if (auto.images.length > 0) voiceDelivery.autoShot.attached += 1
  } else {
    voiceDelivery.autoShot.skipped += 1
  }
  voiceDelivery.autoShot.last = {
    at: Date.now(),
    text: line.text,
    attached: auto.images.length > 0,
    why: auto.images.length > 0 ? 'the phone\'s own screen went in with the line' : auto.why,
  }
  // **按候选逐个试, 一个都投不出去才开新的一场** (2026-10-15): 这些 id 本来就是手上有的, 不用扫
  // 会话表; "那一场不在了"由 [deliverToBall] 里 `resolveAgent` 的失败来说
  let used = null
  let sessionId = null
  let outcome = null
  let failure = null
  for (const candidate of candidates) {
    try {
      outcome = await deliverToBall(ctx, controller, candidate.sessionId, line, auto)
      sessionId = candidate.sessionId
      used = candidate
      break
    } catch (error) {
      failure = error
      voiceDelivery.retried = (voiceDelivery.retried ?? 0) + 1
    }
  }
  beat('candidates')
  const opened = outcome === null
  if (opened) {
    const created = await openBallConversation(ctx, controller, workspace)
    sessionId = created.sessionId
    voiceDelivery.preset = created.preset
    voiceDelivery.presetWhy = created.presetWhy
    beat('open')
    outcome = await deliverToBall(ctx, controller, sessionId, line, auto)
  }
  beat('agent')
  // 投出去了才记"当前对话": 这一笔是从**这一句**起算的 10 分钟 (需求: 每次发送完成后 10 分钟内)
  //
  // **只有球自己那一句才改这本账** (2.7.8 批次 2 根因二): 从前是"投成功就无条件写", 于是主人
  // "在 GUI 那场里问一句 -> 在浮标框里答一句"就把账改写成了那场 GUI 会话 —— 之后 10 分钟的点球 /
  // 语音全都投进那场 GUI 会话, 它当然不在 `dsh-ball` 分组里。回复框点名的那一句只对那一场生效,
  // 自动指令每次新开一场, 两者都不该动这本账
  if (origin === 'ball') {
    await voiceSessionBump(sessionId, { origin, cwd: outcome.cwd ?? workspace?.path ?? '' })
  }
  voiceDelivery.origin = origin
  voiceDelivery.lines += 1
  voiceDelivery.sessionId = String(sessionId)
  voiceDelivery.reused = (voiceDelivery.reused ?? 0) + (used?.why === 'the ball conversation, reused' ? 1 : 0)
  voiceDelivery.current = String(sessionId)
  voiceDelivery.why = used?.why
    ?? (failure === null
      ? fresh
        ? 'the hour was up, so the wake word opened a new conversation'
        : 'the ball had no conversation, so this opened one'
      : `the conversation we knew was gone (${failure?.message ?? failure}), so this opened one`)
  if (opened) voiceDelivery.opened += 1
  if (outcome.running) voiceDelivery.steered += 1
  else voiceDelivery.queued += 1
  voiceDelivery.last = {
    at: Date.now(),
    text: line.text,
    sessionId: String(sessionId),
    steered: outcome.running,
    newConversation: opened,
    reused: used?.why === 'the ball conversation, reused',
    source: line.source ?? 'voice',
    screenshot: outcome.screenshot === true,
    // 新开一场时用的是哪个预设 (复用那一场时不记: 那是它自己的事, 见 [chooseVoicePreset])
    preset: opened ? (voiceDelivery.preset ?? '') : null,
    presetWhy: opened ? voiceDelivery.presetWhy : null,
  }
  voiceDelivery.error = null
  // 同一份读数落一盘 (见 [voiceReadingsWrite]): 没有模型可问的时候, 设备验收就靠它
  await voiceReadingsWrite(ctx)
  // **一段一段报出来** (主人 2026-10-15: "从 ball 到会话发送能缩到最小吗"): `console.log` 走 stdout,
  // 也就是应用抓的 logcat 那一份 (`ctx.logger.info` 不进那里), 所以要用它才量得到
  timing.total = Date.now() - startedAt
  voiceDelivery.timing = timing
  console.log(
    `luwi-channel: line #${line.seq} delivery took ${timing.total}ms`
      + ` (workspace ${timing.workspace ?? 0}, shot ${timing.shot ?? 0},`
      + ` candidates ${timing.candidates ?? 0} of ${candidates.length},`
      + ` ${opened ? 'open' : 'reuse'} ${timing.open ?? 0}, agent ${timing.agent ?? 0})`,
  )
  ctx.logger?.info?.(
    `voice line #${line.seq} ${opened ? 'opened a new conversation' : outcome.running ? 'steered into' : 'queued on'}`
      + ` ${String(sessionId)} (${voiceDelivery.why})`
      + `${opened && voiceDelivery.preset ? ` on preset ${voiceDelivery.preset}` : ''}`
      + `${outcome.screenshot === true ? ' + one screenshot of the phone\'s own screen' : ''}`,
  )
  // 预设被换过的那一档单独说一句: 它不是正常的选预设, 而是"选中的那个用不了"
  if (opened && typeof voiceDelivery.presetWhy === 'string' && voiceDelivery.presetWhy.includes('fell back')) {
    console.warn(`luwi-channel: line #${line.seq} opened its conversation on a fallback preset - ${voiceDelivery.presetWhy}`)
  }
  if (auto.tried && auto.images.length === 0) {
    console.warn(`luwi-channel: line #${line.seq} went in without its screenshot: ${auto.why}`)
  }
  return outcome
}

/* ── 回答念出来 ───────────────────────────────────────────────────────────── */

/** 最近一次朗读的去向, 给 lw_voice 看 */
const voiceReading = { count: 0, skipped: 0, last: null, error: null }

/**
 * 一轮说完才念 (批次 2.3)
 *
 * 触发点是 `session/event` 里的 `turn/end`, 而且**只有 `reason.kind === 'completed'` 才念**: 一个
 * 轮次里每一步都有一条 `assistant/message` (中间还夹着工具调用), 而只有"这一轮正常结束"才说明
 * 模型不再欠回复 —— 被打断的、出错的、撞上 max-tokens 的那一轮念出来是半句话, 那不叫念回答,
 * 所以不按消息念, 也不在 `agent/turn-stopping` 里念 (那是 serial 钩子, 模型会等我们念完)
 *
 * 读的是这一轮最后那条助手消息的正文, 先过 [readAloudText] 洗一遍 (批次 2.4), 洗空了就什么都不念
 *
 * 半双工在应用那一侧 (批次 2.5): `LwSpeak` 出声时把麦克风那条链关上, 所以念出来的字不会被录回去,
 * 顺带也不会把唤醒词自己叫醒
 *
 * 任何一轮结束都会念, 包括主人在界面里打字问的那些 —— "只在语音问的时候才念"是一个开关的事,
 * 那属于浮标与设置那一批 (D14)
 */
function startReadAloud(ctx) {
  // sessionId -> 这一轮最后那条助手正文,一轮里会有好几条, 后一条顶掉前一条
  const pending = new Map()
  onSessionEvent(ctx, (session, event) => {
    if (event.type !== 'assistant/message' && event.type !== 'turn/end') return
    if (session.meta?.origin === 'subagent') return
    // **对端投影的回答不念、也不推进回复框** (2026-10-14 真机): 那不是在跟球说话, 而这个钩子当初
    // 只见过"本机跑出来的一轮"。真机上是这么炸的: 投影带上回答之后, 对端那句长回答被推去念 →
    // 也画进球的回复框 → 超过 5000 字时 BoxView.render 的光标越界 → 应用每 100 秒崩一次
    if (isPeerProjection(session)) return
    if (event.type === 'assistant/message') {
      // 被打断的那条不作数: 它后面还可能跟着真正的回答
      if (event.data.interrupted === true) {
        pending.delete(session.id)
        return
      }
      const content = Array.isArray(event.data.message?.content) ? event.data.message.content : []
      pending.set(
        session.id,
        content
          .filter((block) => block.type === 'text' && typeof block.text === 'string')
          .map((block) => block.text)
          .join('\n'),
      )
      return
    }
    if (event.type !== 'turn/end') return
    const text = pending.get(session.id)
    pending.delete(session.id)
    if (text === undefined || event.data.reason?.kind !== 'completed') return
    const spoken = readAloudText(text)
    if (!spoken) return
    // **回复框与「自动朗读」是两件事** (2026-10-15 真机: 朗读关着时主人"完成了也没有回复框"):
    // 把回答推回浮标那块框从来不是"念出来"的附属品 —— 问一句、看一眼回答, 与要不要出声无关,
    // 所以这一句排在开关前面, 开关只管念不念
    reportReply(replyChannelText(spoken), session.id)
    // 设置页那个「自动朗读回答」关掉之后就不念 —— 开关在应用那一侧 (它与音色/语速放在一起),
    // 所以这里先问一句。**读不到设定时照念**: 桥出错不该让这个功能静默消失, 只记一行
    void call('speak', { op: 'status' })
      .then((state) => {
        if (state?.readAloud === false) {
          voiceReading.skipped += 1
          voiceReading.error = null
          return null
        }
        voiceReading.count += 1
        voiceReading.last = { at: Date.now(), characters: spoken.length, text: spoken }
        voiceReading.error = null
        // 不 await: 这是 emit 钩子, 念多久都不该把会话的事挡在后面
        return call('speak', { op: 'speak', text: spoken })
      })
      .then((answer) => {
        if (!answer || answer.spoken) return
        voiceReading.error = `the engine did not report finishing: ${answer.detail}`
        ctx.logger?.warn?.(voiceReading.error)
      })
      .catch((error) => {
        voiceReading.error = `speaking failed: ${error?.message ?? error}`
        ctx.logger?.warn?.(voiceReading.error)
      })
  })
}

/* ── 桌面对话那一格 (2.7.8 批次 6) ────────────────────────────────────────── */

/** 那份文件: `$DSH_HOME/lw/desktop-chat.json` (与应用那侧的 `DesktopChatFile` 是同一个约定) */
function desktopChatPath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', 'desktop-chat.json') : null
}

/**
 * 与应用那侧同一对数: 留几条 / 一条最多多少字 (谁都不许单边改)
 *
 * `MAX_LINES` 是"手里留几条", 而组件那一格画得下的是更少的几条 (`DesktopChatFile.SHOW_LINES`) ——
 * 两个数归应用管, 这一侧只需要别把它撑大
 */
const DESKTOP_CHAT_LINES = 40
const DESKTOP_CHAT_CHARS = 240

/** 截一道 (带 `…`), 顺带把换行压成空格: 组件上那两行气泡装不下换行 */
function clipDesktopChat(text) {
  const line = String(text ?? '').trim().replace(/\s*\n+\s*/g, ' ')
  return line.length <= DESKTOP_CHAT_CHARS ? line : `${line.slice(0, DESKTOP_CHAT_CHARS - 1)}…`
}

/**
 * 把一句回答写进 `desktop-chat.json` 的 `assistant` 那一笔
 *
 * 这一格是**桌面组件**, 它没有进程也没有推送通道 —— 它每次重画都是"读这份文件"。所以宿主这一侧
 * 只做一件事: 把刚说的那句接在账尾。应用那侧用 inotify 盯着这个目录, 一改就重画 (零轮询)
 *
 * 三条与应用那侧逐字对齐的约定: `who` 只有 `user` / `assistant`; 只留最近
 * [DESKTOP_CHAT_LINES] 条; **原子写** (`.tmp` + rename), 于是读的一侧永远不会看到半份文件。
 * 读-改-写, 读不懂就当成空表 (应用下一次写会整份换掉它)
 *
 * 写不进去只记一行日志: 没装应用 / 目录还没建 (宿主起来了而应用一次都没跑过) 都不是要报给模型的错
 */
async function writeDesktopChat(who, text) {
  const path = desktopChatPath()
  if (path === null) return
  const line = clipDesktopChat(text)
  if (!line) return
  let lines = []
  try {
    const root = JSON.parse(await readFile(path, 'utf8'))
    if (Array.isArray(root?.lines)) lines = root.lines
  } catch {
    // 文件不在 / 读不懂: 从空表开始, 这一笔照样要落下去
  }
  const last = lines.at(-1)
  if (last?.who === who && last?.text === line) return
  const kept = [...lines, { who, text: line, at: Date.now() }].slice(-DESKTOP_CHAT_LINES)
  const payload = { v: 1, at: Date.now(), lines: kept }
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${JSON.stringify(payload)}\n`)
    await rename(temporary, path)
  } catch (error) {
    console.warn(`luwi-channel: the desktop chat file was not written: ${error?.message ?? error}`)
  }
}

/**
 * 推给浮标那块框的正文
 *
 * **2000 字是留的余量**: 应用那侧 `BoxView.render` 的硬上限是 5000, 超过它光标会落到截断前的长度上
 * 越界崩 (2026-10-14 真机: 对端那句长回答把应用每 100 秒崩一次)。框本来就是"看一眼回答"的地方,
 * 截了不丢信息 —— 完整的还在会话里
 */
function replyChannelText(text) {
  const line = String(text ?? '').trim()
  return line.length <= 2000 ? line : `${line.slice(0, 2000)}…`
}

/**
 * 把一句回答推回浮标那个输入通道 (主人 2026-10-05: "回复结果也传此通道")
 *
 * 与「正在想」那一句同一个去向 (`overlay` 那一个通道方法), 只是 op=reply: 应用那侧把它画进那块
 * 650 px 的文字框里 —— 问与答落在同一块框, 眼睛不必在浮标与页面之间来回找
 *
 * **通道没开着时那一句不丢**: 服务那边记着它 ([OverlayState.pendingReply]), 主人下次把通道张开
 * 时补上 (问完就去别的应用里是常态, 而"回来时回答不见了"是最让人恼火的那种丢)
 *
 * **`session` 是那条回复是哪一场发来的** (2026-10-06 加): 应用那一侧的回复框记住它, 之后从框里
 * 发出去的话就点名投回这一场 (见 `voiceTargetCandidates` 的第一条判据) —— 少了它, "接着回答"就只能
 * 靠那笔账撞运气
 *
 * 推不出去只记一行: 没装应用 / 应用在后台被杀掉的时候它本来就没有落脚处, 而不是一条要报给模型的错
 */
function reportReply(text, session) {
  const line = String(text ?? '').trim()
  if (!line) return
  const from = String(session ?? '').trim()
  // 桌面上那一格也要看得见这一句 (2.7.8 批次 6): 与推给浮标那块框是**同一句**, 只是去向不同 ——
  // 写不进去不影响下面那一跳, 所以两条互不等待
  void writeDesktopChat('assistant', line)
  void call('overlay', { op: 'reply', text: line, ...(from ? { session: from } : {}) })
    .then((answer) => {
      voiceReading.reply = { at: Date.now(), characters: line.length, channel: answer?.channel === true }
    })
    .catch((error) => {
      voiceReading.replyError = `the reply did not reach the floating channel: ${error?.message ?? error}`
    })
}

/**
 * 推一条"不是回答"的话给浮标那块框 (`overlay op=note`)
 *
 * 投递失败这件事实在屏幕上本来一个字都没有, 而主人看到的只是"我说了话, 没有回音" —— 这一批里最难查
 * 的一条就是这么来的 (2026-10-06: 12 句话卡在队列里, 界面上毫无痕迹). 所以被跳过的句子必须从浮标
 * 那侧说出来: 与 [reportReply] 同一个去向 (`overlay` 那一个通道方法), 只是记号不同, 应用那侧把它
 * 画成一条 `⚠` 提示
 *
 * 推不出去只记一行: 没装应用 / 球被关掉的时候它本来就没有落脚处, 而不是一条要报给模型的错
 */
function reportNote(ctx, text) {
  const line = String(text ?? '').trim()
  if (!line) return
  void call('overlay', { op: 'note', text: line }).catch((error) => {
    warn(ctx, `a note for the floating channel did not arrive: ${error?.message ?? error}`)
  })
}

/**
 * 一张全黑的图是不是"这块屏上什么都没有"
 *
 * 主人 2026-10-05 报的那一条: 虚拟屏可以单独开出来, 那时它上面一个窗口都没有, 截出来是一张全黑
 * 的图 —— 而**黑图看起来像"截图坏了"**, 于是模型会去重试、换工具、报故障, 一圈下来什么都没干成。
 * 真相是"这块屏是空的", 处置完全不同 (往上面 launch 一个应用), 所以这句话必须由工具说出来
 *
 * 量的是**像素**: 只读 PNG 头几 KB, 解出一小块缩略图再取最大亮度 —— 全黑是"一个亮点都没有", 而不是
 * "看起来暗"。判据留一点余量 (阈值 8): 纯黑屏在真机上量到的就是 0
 */
async function emptyScreenNote(args, answer) {
  const displayId = Number(args?.displayId ?? 0)
  if (!Number.isFinite(displayId) || displayId === 0) return ''
  const path = typeof answer?.path === 'string' ? answer.path : ''
  if (!path) return ''
  const dark = await isAllBlack(path)
  if (dark !== true) return ''
  return `\n\n**This screen is empty**: the picture is black because nothing has been launched onto`
    + ` display ${displayId} yet, not because the capture failed. Start an app on it with lw_launch`
    + ` (displayId ${displayId}, package=...) or make the next screen with lw_screen_create`
    + ' launch=<app>, then take the picture again.'
}

/** 一张 PNG 是不是全黑: 缩到 64 格取**逐像素**最亮值, 读不出来时回 null (不猜) */
async function isAllBlack(path) {
  try {
    const sharp = (await import('sharp')).default
    // **不能只看 `stats().channels[].max`**: 那是"每个通道各自的极值", 一张右下角有个白点的黑图
    // 也会让三个通道都报 255。逐像素取最大才是"这张图上有没有亮过" (实测: 全黑 0, 真截图 255)
    const { data } = await sharp(path)
      .resize(64, 64, { fit: 'inside' })
      .greyscale()
      .raw()
      .toBuffer({ resolveWithObject: true })
    let brightest = 0
    for (const value of data) if (value > brightest) brightest = value
    return brightest <= 8
  } catch {
    return null
  }
}

/* ── 浮标上那个「正在想」 ──────────────────────────────────────────────────── */

/**
 * 现在有哪几场在跑轮: `sessionId -> 开始那一刻`
 *
 * **提到模块级是为了两件事共用同一本账**: 写给应用的那份文件 ([writeBallPhase]) 与双击打断挑目标
 * ([pickInterruptTarget])。子代理的轮次不进这本账 —— 那是模型自己在用的, 不是主人在等的那一轮
 */
const ballTurns = new Map()

/**
 * 最近一次结束的轮是怎么收的: `{ id, at, kind, why? }` (还没结束过就是 null)
 *
 * 与 [ballTurns] 分开是两件事: 那一本记**在跑**, 这一条记**刚跑完那一下是怎么收的** —— 应用据此
 * 在球上写「失败」(2026-10-08)。两条口径要记住:
 *
 * - **`turn/start` 不清它**: 又开了一轮不代表上一次失败翻篇, 认没认过由应用那边"主人点了一下球"
 *   说了算 (见 `OverlayState.failedAckAt`)
 * - **宿主重启自然清掉**: 这条记忆随进程走, 新进程起来写的第一份文件里没有 `last`
 */
let ballEnded = null

/** 那份文件: `$DSH_HOME/lw/ball-phase.json` (与应用那侧的 `BallPhaseFile` 是同一个约定) */
function ballPhasePath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', 'ball-phase.json') : null
}

/** 给纯函数看的那一本账: `{ id, startedAt }` 的数组 */
function ballTurnList() {
  return [...ballTurns].map(([id, startedAt]) => ({ id, startedAt }))
}

/**
 * 一条结束记录: `{ id, at, kind, why? }`
 *
 * `kind` **原样透传** dsh 那张表 (`completed` / `aborted` / `blocked` / `error` / `max-tokens` /
 * `interrupted` / `forked`) —— 哪几种写成球上那两个字由**应用**定, 于是以后 dsh 多一种原因, 这一侧
 * 一个字都不用改
 *
 * `why` 只有 `error` 那条带结构化失败时才有 (截 200 字): 球上不显示它, 它是 `overlay op=state`
 * 里的排障读数。`undefined` 会被 `JSON.stringify` 整个丢掉, 所以没有它时那份 JSON 里就没这个键
 */
function ballEndedFrom(id, reason) {
  const kind = typeof reason?.kind === 'string' ? reason.kind : 'completed'
  const message = reason?.error?.message
  const why = typeof message === 'string' && message.length > 0 ? message.slice(0, 200) : undefined
  return { id, at: Date.now(), kind, why }
}

/**
 * 把"现在哪几场在跑"写给应用 (2026-10-08 主人定的口径: **文件即真相**)
 *
 * 为什么不是推送 (`overlay op=phase`, 那是原来那套): 推送有个躲不掉的毛病 —— **送不到就没人补**。
 * 应用那一刻被系统冻住、通道一时连不上, 球上那三个字就永远留着 (我为它加过一个 20 秒的看门狗, 而
 * 主人嫌延迟太大)。写成文件之后: 谁什么时候读都读得到同一份, 应用用 inotify 盯着那个目录**一改就
 * 醒**, 两侧都是事件驱动、零轮询, 也没有会丢的东西
 *
 * **原子写** (先写 `.tmp` 再 rename): 应用那边从来不锁, 只 rename 才不会让它读到半份 JSON。
 * 写不进去只记一行日志 —— 没装应用 / 浮标那一刻没在, 都不是要报给模型的错误
 */
async function writeBallPhase() {
  const path = ballPhasePath()
  if (path === null) return
  const payload = { v: 1, at: Date.now(), turns: ballTurnList() }
  // `last` 是**可选**键: 没有结束记录时整条不写, 版本号于是还是 1 —— 旧应用忽略这个键, 新应用
  // 容忍它缺席 (两边本来都不校验 v)
  if (ballEnded !== null) payload.last = ballEnded
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${JSON.stringify(payload)}\n`)
    await rename(temporary, path)
  } catch (error) {
    console.warn(`luwi-channel: the ball phase file was not written: ${error?.message ?? error}`)
  }
}

/**
 * 一轮在跑就写着「正在想」: 由宿主把"哪几场在跑"写进那份文件, 应用读它 (批次 4 / 2026-10-08 改)
 *
 * **为什么这件事得由宿主做**: 「正在听」「正在念」在应用那一侧 (`VoiceState`), 而"模型正在干活"
 * 只有宿主知道 —— 应用那侧没有第二条路看得出这件事
 *
 * `turn/start` / `turn/end` 两个事件成对, 于是这本账也是成对的: `turn/start` 记入, `turn/end` 划掉,
 * 一轮两次。两个会话同时跑时不会一个结束就把另一个的"正在想"抹掉; 子代理的轮次跳过
 *
 * **`turn/end` 那一次不论在不在账上都写**: 即使这一场没被记进账 (宿主是它跑着的时候才起来的),
 * "这一轮是怎么收的"这件事照样要写进 `last` —— 那正是球上「失败」的判据
 *
 * **没有看门狗, 也没有定时器** (2026-10-08 撤掉): 原来还担心"事件缺一半"——一轮崩了、被中断而回执
 * 没回来 —— 而现在**那本账就是文件**, 应用每次读到的都是最新的一份; 真正"不知道"的情形只有宿主自己
 * 重启 (那本账随进程没了, 所以起来时先写一次空表), 以及进程被杀 (应用那边会看到宿主不在跑)
 */
function startBallPhase(ctx) {
  onSessionEvent(ctx, (session, event) => {
    if (event.type !== 'turn/start' && event.type !== 'turn/end') return
    if (session.meta?.origin === 'subagent') return
    // 投影追加的那些轮次不进球上那本账: 球上写着「正在想」的必须是**这台机器上真的在跑的那一场**
    if (isPeerProjection(session)) return
    if (event.type === 'turn/start') {
      ballTurns.set(session.id, Date.now())
      void writeBallPhase()
      return
    }
    if (event.type !== 'turn/end') return
    ballTurns.delete(session.id)
    ballEnded = ballEndedFrom(session.id, event.data?.reason)
    void writeBallPhase()
  })
  // **起来先写一次空表**: 宿主刚起来时账上一定是空的, 这一下顺手把应用里可能残留的旧字清掉
  // (应用重启时它自己也会读一次, 所以两份都对得上)
  void writeBallPhase()
}

/* ── 探索 (2.7.8 批次 7, 需求第 6 条) ────────────────────────────────────────
 *
 * 一次探索 = 落在 `Luwi` 工作区的一场会话: 宿主给它一小段预算 (缺省 30 分钟 / 60 步), 它只读地自由
 * 探索这台手机, 最后折成一份结构化摘要 (外加若干记忆候选卡) 落盘, 下一场先读它接着看
 *
 * 四件事的边界先写清 (D9 / D10 / D13 / D14):
 *  - **"只读"只是提示词层**: 三条红线写在 persona 里, 宿主**不加硬拦**、不叠 read-only 权限档, 也不
 *    deny 工具 —— 越没越界靠验收里那一趟如实记 (见方案 §8 的已知边界)
 *  - **预算由宿主数**: 工具调用次数 (`ctx.tools.guard`, 同步, 只数不拒) 与墙钟 (5 s 一拍),
 *    模型自报的一律不算
 *  - **一场只允许一场**: `op` 只有 status / start / finish, 没有 resume —— 结束之后靠摘要接上
 *  - **摘要只走 op=finish**: 宿主落文件与候选卡; 批次 7 只落卡, 不晋升、不读、不删 (闸门留批次 8)
 *
 * 判据在 `tools/check-explore.mjs`: 它用 `new Function` 把下面那几段纯函数抽出来真跑, 测的是上线这份
 */

/** 探索那一场的工作区名 (主人 2026-10-18 拍板: D10 改成 `Luwi`, 目录 `$DSH_HOME/Luwi`) */
const EXPLORE_WORKSPACE_NAME = 'Luwi'

/** 那一档的预设 id: 与 `presets/explore/cordis.patch.yml` 里的 `config.id` 是同一个字面量 */
const EXPLORE_PRESET = 'explore'

/** 缺省时长与步数 (度选是主人 2026-10-18 的口径: §1 第 1 条把步数做成硬预算) */
const EXPLORE_MINUTES_DEFAULT = 30
const EXPLORE_MINUTES_RANGE = [1, 180]
const EXPLORE_STEPS_DEFAULT = 60
const EXPLORE_STEPS_RANGE = [1, 1000]

/** 每日主动说的上限 (D17; 应用那一侧按这个缺省, 实际值在 automations/settings.json 的 saidLimit) */
const EXPLORE_SAID_DEFAULT = 3

/** 那几份文件的文件名 (与应用那一侧同一个约定, 谁都不许单边改) */
const EXPLORE_TASK_FILE = 'explore-card.md'
const EXPLORE_TASK_CHARS = 8000
const EXPLORE_STATE_FILE = 'explore.json'
const EXPLORE_SUMMARY_DIR = 'explore'
const EXPLORE_CANDIDATES_DIR = 'explore/candidates'

/** 摘要正文上限 (超出截断并在 frontmatter 记 `truncated: true`) 与只留最近多少份 */
const EXPLORE_SUMMARY_CHARS = 8000
const EXPLORE_SUMMARIES_KEPT = 30

/** 一次 finish 最多交几张候选卡, 候选目录只留最近多少张, 一张卡最多几个字 */
const EXPLORE_CANDIDATES_MAX = 5
const EXPLORE_CANDIDATES_KEPT = 100
const EXPLORE_FACT_MAX = 200

/** 候选卡的 `kind` 与 `sensitivity` 只收这几个 (不合法的那一条整条丢掉, 不静默改写) */
const EXPLORE_KINDS = ['fact', 'preference', 'todo', 'date']
const EXPLORE_SENSITIVITY = ['low', 'high']

/** 到点那一拍: 5 s 一次 (与应用那侧 3 s 那条轮询同一个量级, 但这里只比一个数) */
const EXPLORE_TICK_MS = 5000

/**
 * 同一场收尾之后多久之内再交一次, 当成"这一份已经落过了"
 *
 * 到点那一下是"先取消再 followup", 而模型可能在被取消那一轮里已经把 finish 交了 ——
 * 紧跟的 followup 再交一次时 run 已经清掉, 那时该说"已经落过了"而不是"没有正在跑的探索"
 */
const EXPLORE_ALREADY_SAVED_MS = 5 * 60 * 1000

/**
 * 这一场探索现在花到哪了 (纯函数)
 *
 * `due` 三态是**这一批最要紧的一条判据**: `time` 是到点 (要取消那一轮), `steps` 是步数用尽
 * (只提醒, 不拒), `null` 是都还没到。`over` 之后一律回 `null` —— 到点只处理一次
 */
function exploreBudget(run, now = Date.now()) {
  if (!run?.session) return { active: false, due: null, remainingMs: 0, stepsLeft: 0 }
  const remainingMs = Math.max(0, Number(run.deadline) - now)
  const stepsLeft = Math.max(0, Number(run.stepLimit) - Number(run.steps))
  const due = run.over === true ? null
    : remainingMs === 0 ? 'time'
      : stepsLeft === 0 ? 'steps' : null
  return { active: remainingMs > 0 && run.over !== true, due, remainingMs, stepsLeft }
}

/** 一段数的夹取 (分钟 / 步数都用它): 认不出来的给缺省, 认出来的一律夹进范围 (纯函数) */
function exploreClamp(value, range, fallback) {
  const number = Number(value)
  if (value === undefined || value === null || !Number.isFinite(number)) return fallback
  return Math.min(range[1], Math.max(range[0], Math.round(number)))
}

/** 那一份摘要叫什么: `yyyyMMdd-HHmm` (本地时间, 按这一场开始那一刻算 —— 纯函数) */
function exploreSummaryName(startedAt) {
  const at = new Date(Number(startedAt) || Date.now())
  const pad = (value) => String(value).padStart(2, '0')
  return `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}`
    + `-${pad(at.getHours())}${pad(at.getMinutes())}`
}

/** 应用那一侧记"今天说过几次"用的日期键, 与 `$DSH_HOME/lw/explore.json` 里 `said.date` 同一个形状 */
function exploreDayKey(now = Date.now()) {
  const at = new Date(now)
  const pad = (value) => String(value).padStart(2, '0')
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`
}

/**
 * 探索开场那一段正文 (纯函数)
 *
 * 三件事写进同一段: 这一趟要做什么 (正文, 缺省"自由探索这台手机")、**预算那两行** (步数用完只提醒,
 * 到点那一轮会被取消)、以及**上一份摘要的路径** —— 第 17 条 persona 要它先读那份, 而模型没有别的
 * 办法知道最新那份在哪 (宿主替它算, 不麻烦它再列一次目录)
 */
function exploreStartLine(text, run, summaryPath) {
  const asked = String(text ?? '').trim() || '自由探索这台手机'
  return [
    asked,
    '',
    `预算: ${Number(run?.minutes) || EXPLORE_MINUTES_DEFAULT} 分钟 / `
      + `${Number(run?.stepLimit) || EXPLORE_STEPS_DEFAULT} 步, 由宿主数`
      + ' —— 到步会收到一句插话 (只提醒, 不拒调用), 到点那一轮会被取消并收到"立刻收尾";',
    `上一份摘要: ${String(summaryPath ?? '') || '还没有 (这是第一场)'}`,
  ].join('\n')
}

/**
 * 一张候选卡的正文 (纯函数)
 *
 * 校验三件事: `fact` 非空、`kind` 在四个里、`sensitivity` 在 low / high 里 (缺省 low) ——
 * **`date` 类一律 `high`** (生日 / 纪念日 / 到期日写错一次就很难补回来, 见 §4.4)
 *
 * 不合法的回 `{ error }`, 由调用方**整条丢掉并记读数** —— 不静默改写成 low: 那会把"模型写错了"
 * 变成一个看起来合法的低敏事实
 */
function exploreCandidateBody(card, extra = {}) {
  const fact = String(card?.fact ?? '').trim()
  if (fact === '') return { error: 'the card has no fact' }
  const kind = String(card?.kind ?? '').trim()
  if (!EXPLORE_KINDS.includes(kind)) {
    return { error: `kind "${kind}" is not one of ${EXPLORE_KINDS.join(' / ')}` }
  }
  const asked = String(card?.sensitivity ?? '').trim()
  if (asked !== '' && !EXPLORE_SENSITIVITY.includes(asked)) {
    return { error: `sensitivity "${asked}" is not low or high` }
  }
  const sensitivity = kind === 'date' ? 'high' : asked === '' ? 'low' : asked
  const confidence = Number(card?.confidence)
  const kept = Number.isFinite(confidence) ? Math.min(1, Math.max(0, confidence)) : null
  const evidence = String(card?.evidence ?? '').trim()
  const said = fact.slice(0, EXPLORE_FACT_MAX)
  const body = [
    '---',
    `at: ${extra.at ?? new Date().toISOString()}`,
    `session: ${extra.session ?? ''}`,
    `kind: ${kind}`,
    `sensitivity: ${sensitivity}`,
    ...(kept === null ? [] : [`confidence: ${kept}`]),
    `evidence: ${evidence === '' ? 'not recorded' : evidence}`,
    '---',
    '',
    said,
    '',
  ].join('\n')
  return { kind, sensitivity, fact: said, confidence: kept, evidence, body }
}

/**
 * 那一场探索的账: 谁在跑、预算从什么时候起、用了几步
 *
 * **内存里这一份是活的那一份**, `$DSH_HOME/lw/explore.json` 是它的镜子 —— 宿主重启之后按那份文件
 * 重挂预算 (`deadline` 与 `steps` 都从文件回来), 于是"装一次包"不会把一场探索变成没人管的孤儿
 */
const exploreRun = {
  session: null,
  workspace: '',
  startedAt: 0,
  deadline: 0,
  minutes: 0,
  steps: 0,
  stepLimit: 0,
  reminded: false,
  over: false,
}

/** 探索那一场的工作区注册读数 (形状与 [ballWorkspaceState] 同, 判据见 [ensureChannelWorkspace]) */
const exploreWorkspaceState = { path: null, id: null, registered: null, why: null }

/**
 * 探索那条: 目录 `Luwi`, 读数记在 [exploreWorkspaceState] 上
 *
 * 主人 2026-10-18 拍板 (D10 改口径): 探索会话落在 `Luwi`, 与 `dsh-ball` 那条各记各的账 —— 注册路
 * 共用 [ensureChannelWorkspace], 一条会话该带 `workspaceId` 的那件事与浮标那条完全相同
 */
async function ensureExploreWorkspace(ctx) {
  return ensureChannelWorkspace(ctx, EXPLORE_WORKSPACE_NAME, exploreWorkspaceState)
}

/** 这一档的读数 (给 `lw_explore op=status`, 也是"省电探针抛错"与"丢掉几张卡"的唯一落点) */
const exploreState = {
  preset: null,
  presetWhy: null,
  started: 0,
  finished: 0,
  stubs: 0,
  dropped: 0,
  refusals: 0,
  lastRefusal: null,
  powerSaveUnknown: false,
  lastError: null,
  /**
   * 最近一次落盘的那份摘要 (`{ session, path, chars, candidates, stub, at }`)
   *
   * 2026-10-17 真机上量到的形态: 到点那一下先取消再 followup, 而模型**在被取消那一轮里**已经把 finish
   * 交了 (摘要落盘), 紧跟的 followup 又交一次 —— 第二次那时 run 已经清掉, 回执是 "no exploration is
   * running", 于是她回主人一句"报告没能交上去" (其实落过盘了)。所以这里记一份, 同一场五分钟内再交
   * 就如实回"已经落过了" (桩摘要不算: 那种情况模型补一份真的正是我们要的)
   */
  lastSaved: null,
}

/**
 * `$DSH_HOME/lw/explore.json` 那个文件
 *
 * **合并式原子写**: 先读, 只改自己的键 (`run`), `.tmp` + rename, 未知键一律保留 —— 批次 8 要往同一个
 * 文件写 `said` (每日主动说的那本账), 那时这一侧一个字节都不用改
 */
function exploreStatePath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', EXPLORE_STATE_FILE) : null
}

function exploreSummaryDir() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', EXPLORE_SUMMARY_DIR) : null
}

function exploreCandidatesDir() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', EXPLORE_CANDIDATES_DIR) : null
}

/** 探索那张任务卡 (批次 8): `$DSH_HOME/lw/explore-card.md` —— 应用排期到点读它, 收尾时由这里改写 */
function exploreTaskPath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', EXPLORE_TASK_FILE) : null
}

/**
 * 把下一场探索的任务写进那张卡 (收尾时调; 原子写)
 *
 * 与应用那侧 `ExploreTask` 同一个文件: 应用只读 (与主人手改), 这里只在收尾时覆盖
 */
async function exploreTaskWrite(text) {
  const path = exploreTaskPath()
  if (path === null) return { path: '', chars: 0, error: 'this host has no DSH_HOME' }
  const body = String(text ?? '').trim().slice(0, EXPLORE_TASK_CHARS)
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${body}\n`)
    await rename(temporary, path)
    return { path, chars: body.length }
  } catch (error) {
    return { path, chars: 0, error: String(error?.message ?? error) }
  }
}

/** 那张卡现在什么样 (status 的读数用) */
async function exploreTaskInfo() {
  const path = exploreTaskPath()
  if (path === null) return { path: '', chars: 0 }
  try {
    const text = await readFile(path, 'utf8')
    return { path, chars: text.trim().length }
  } catch {
    return { path, chars: 0 }
  }
}

/** 那份状态文件读成对象: 读不到 / 不是对象都给空对象 (调用方一律按缺省读) */
async function exploreStateRead() {
  const path = exploreStatePath()
  if (path === null) return {}
  try {
    const text = await readFile(path, 'utf8')
    const parsed = JSON.parse(text)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

/**
 * 写状态文件那条链: **一次只写一份**
 *
 * 2026-10-17 真机上量到的: 数到第 10 步那一下会连着写两次 (每五步落一次盘 + 提醒落一次盘), 两处都用
 * 同一个 `.tmp` 路径 —— 先完成的那一次把临时文件 rename 走了, 后一次 rename 就 ENOENT
 * (`rename '…explore.json.tmp' -> '…explore.json': no such file or directory`), 那一份状态于是没落地。
 * 串成一条链之后, 后一次读到的是前一次写完的盘上内容, 两次都写得进去
 */
let exploreStateQueue = Promise.resolve()

function exploreStateWrite() {
  exploreStateQueue = exploreStateQueue.then(
    () => exploreStateWriteNow(),
    () => exploreStateWriteNow(),
  )
  return exploreStateQueue
}

/** 把内存里那一份写进文件: **未知键原样留着**, 只换 `v` / `updatedAt` / `run` */
async function exploreStateWriteNow() {
  const path = exploreStatePath()
  if (path === null) return
  const base = await exploreStateRead()
  const payload = {
    ...base,
    v: 1,
    updatedAt: Date.now(),
    run: exploreRun.session === null ? null : { ...exploreRun },
  }
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${JSON.stringify(payload)}\n`)
    await rename(temporary, path)
  } catch (error) {
    console.warn(`luwi-channel: the explore state file was not written: ${error?.message ?? error}`)
  }
}

/** 宿主起来时把那一场接回来 (`run` 还在且没 over): 只按文件重挂预算, 不做别的 */
async function exploreStateRestore() {
  const record = await exploreStateRead()
  const run = record?.run
  if (run === null || typeof run !== 'object') return false
  if (typeof run.session !== 'string' || run.session === '') return false
  if (run.over === true) return false
  exploreRun.session = run.session
  exploreRun.workspace = typeof run.workspace === 'string' ? run.workspace : ''
  exploreRun.startedAt = Number(run.startedAt) || 0
  exploreRun.deadline = Number(run.deadline) || 0
  exploreRun.minutes = Number(run.minutes) || EXPLORE_MINUTES_DEFAULT
  exploreRun.steps = Number(run.steps) || 0
  exploreRun.stepLimit = Number(run.stepLimit) || EXPLORE_STEPS_DEFAULT
  exploreRun.reminded = run.reminded === true
  exploreRun.over = false
  return true
}

/** 把那一场清干净 (收尾 / 桩摘要 / 开会话失败都用它) */
function exploreReset() {
  exploreRun.session = null
  exploreRun.workspace = ''
  exploreRun.startedAt = 0
  exploreRun.deadline = 0
  exploreRun.minutes = 0
  exploreRun.steps = 0
  exploreRun.stepLimit = 0
  exploreRun.reminded = false
  exploreRun.over = false
}

/** 最新那一份摘要的路径 (没有就是空串) —— pc=start 那一段要把它交给模型 (persona 第 17 条) */
async function exploreLatestSummary() {
  const directory = exploreSummaryDir()
  if (directory === null) return ''
  const names = (await readdir(directory).catch(() => [])).filter((one) => one.endsWith('.md')).sort()
  return names.length === 0 ? '' : join(directory, names[names.length - 1])
}

/** 候选目录里现在有几张卡 (`op=status` 那一行读数) */
async function exploreCandidatesCount() {
  const directory = exploreCandidatesDir()
  if (directory === null) return 0
  return (await readdir(directory).catch(() => [])).filter((one) => one.endsWith('.md')).length
}

/** 只留最近 `keep` 份: 目录按文件名排序 (那就是时间序), 多出来的从最旧的删起 */
async function explorePrune(directory, extension, keep) {
  const names = (await readdir(directory).catch(() => [])).filter((one) => one.endsWith(extension)).sort()
  const extra = names.length - keep
  if (extra <= 0) return 0
  for (const name of names.slice(0, extra)) {
    await rm(join(directory, name), { force: true }).catch(() => {})
  }
  return extra
}

/** 省电探针: 桥上有 `wakeword op=status` 带 powerSave (见 §2 第 7 条); 探针抛错不算省电 */
async function explorePowerSave() {
  try {
    const status = await call('wakeword', { op: 'status' })
    return { known: true, powerSave: status?.powerSave === true, why: null }
  } catch (error) {
    return { known: false, powerSave: null, why: String(error?.message ?? error) }
  }
}

/**
 * 往那一场说一句 (开场正文 / 到步提醒 / 到点收尾都用它)
 *
 * 形状与 [deliverToBall] 相同: `withoutInitiator` -> `resolveAgent` -> (取消) -> `steer` / `followup`
 * -> `flush`。**到点那一下先取消再 followup** —— 与 [runVoiceInterrupt] 同一条语义
 * (`cancel({kind:'user'}, {keepInbox:true})`), 但那个函数一个字节都不动 (它被 check-voice-inbox 钉着)
 */
async function exploreSay(ctx, text, options = {}) {
  const controller = ctx?.get?.('sessionController')
  if (!controller) throw new Error('this profile has no session controller, so the explore session cannot be reached')
  const sessionId = exploreRun.session
  if (!sessionId) throw new Error('no exploration is running')
  return ctx.agents.withoutInitiator(async () => {
    const resolved = await controller.resolveAgent(sessionId)
    if ('error' in resolved) throw resolved.error
    const { agent } = resolved
    const message = await createVoiceMessage(text)
    const running = agent.status === 'running'
    if (options.cancel === true) {
      agent.cancel({ kind: 'user' }, { keepInbox: true })
      agent.followup(message)
    } else if (running && options.steer === true) {
      agent.steer(message)
    } else {
      agent.followup(message)
    }
    const flushed = await ctx.sessions.flush(agent.session)
    return { sessionId, running, flushed }
  })
}

/**
 * 到步那一句: **只提醒, 不拒** (D9 那一档: 主机不拦工具调用)
 *
 * 只在第 stepLimit 步发一次 (`exploreRun.reminded` 是那道闸); 失败只记读数 —— 这个函数是从同步的
 * guard 里 `void` 出去的, 抛出来没人接
 */
async function exploreStepNote(ctx) {
  if (ctx === null) return
  const text = `预算用完了 (${exploreRun.steps} 步), 该收尾了: 立刻 lw_explore `
    + '{op:"finish", summary:"..."}, 不要再开新的事'
  try {
    const sent = await exploreSay(ctx, text, { steer: true })
    console.log(`luwi-channel: exploration ${sent.sessionId} used up its budget - told it to finish`)
  } catch (error) {
    exploreState.lastError = `the step note was not sent: ${error?.message ?? error}`
    console.warn(`luwi-channel: ${exploreState.lastError}`)
  }
}

/**
 * 数步那一件事 (第二个 `ctx.tools.guard`, 与 [cameraAndModeGate] 并列)
 *
 * **只数不拒**: 同步、最便宜的那条判据排最前, 三个 return 都是 `undefined`。子代理的会话 id 不同,
 * 它的工具调用天然不计数 —— 这是 D9 同一口径下的**已知边界** (persona 写"不派", 宿主不拦)
 */
function exploreStepGuard(exec) {
  if (exploreRun.session === null) return undefined
  if (sessionIdOf(exec) !== exploreRun.session) return undefined
  if (exploreRun.over === true) return undefined
  exploreRun.steps += 1
  // 每五步落一次盘 (宿主重启之后步数不从零开始), 不是每一步都写文件
  if (exploreRun.steps % 5 === 0) void exploreStateWrite()
  if (exploreRun.steps === exploreRun.stepLimit && !exploreRun.reminded) {
    exploreRun.reminded = true
    void exploreStateWrite()
    void exploreStepNote(hostCtx)
  }
  return undefined
}

/** 到点那一拍: 看到 deadline 且没 over 就取消那一轮, 再投一句"立刻收尾" */
async function exploreTick(ctx) {
  if (exploreRun.session === null || exploreRun.over === true) return
  if (exploreBudget(exploreRun).due !== 'time') return
  const sessionId = exploreRun.session
  const minutes = exploreRun.minutes
  exploreRun.over = true
  await exploreStateWrite()
  console.log(`luwi-channel: exploration ${sessionId} hit its deadline (${minutes} min) - the turn was cancelled`)
  try {
    await exploreSay(ctx, `到点了 (${minutes} 分钟), 立刻 lw_explore op=finish 收尾`, { cancel: true })
  } catch (error) {
    exploreState.lastError = `the deadline notice was not sent: ${error?.message ?? error}`
    console.warn(`luwi-channel: ${exploreState.lastError}`)
  }
}

/**
 * 写一份摘要 (+ 逐条落候选卡), 然后清 run
 *
 * 两件事都**只由宿主做**: persona 第 16 条明说不要用别的工具写 (那会撞红线 2), 所以这里也是唯一
 * 落 `$DSH_HOME/lw/explore/` 的地方。桩摘要 (`stub`) 不写候选卡 —— 没有正文就没有可晋升的东西
 */
async function exploreWriteSummary({ summary, candidates = [], ended = 'finish', stub = false }) {
  const directory = exploreSummaryDir()
  if (directory === null) return { error: 'this host has no DSH_HOME, so the summary has nowhere to go' }
  const at = new Date().toISOString()
  const text = String(summary ?? '')
  const truncated = text.length > EXPLORE_SUMMARY_CHARS
  const body = truncated ? text.slice(0, EXPLORE_SUMMARY_CHARS) : text
  const asked = Array.isArray(candidates) ? candidates : []
  const kept = []
  const dropped = []
  if (!stub) {
    if (asked.length > EXPLORE_CANDIDATES_MAX) {
      dropped.push({
        index: 0,
        why: `${asked.length - EXPLORE_CANDIDATES_MAX} card(s) over the ${EXPLORE_CANDIDATES_MAX}`
          + ' one finish may hand in were not taken',
      })
    }
    for (const card of asked.slice(0, EXPLORE_CANDIDATES_MAX)) {
      const one = exploreCandidateBody(card, { at, session: exploreRun.session })
      if (one.error) {
        dropped.push({ index: kept.length + dropped.length + 1, why: one.error })
        continue
      }
      kept.push(one)
    }
  }
  const front = [
    '---',
    `at: ${at}`,
    `session: ${exploreRun.session ?? ''}`,
    `workspace: ${exploreRun.workspace}`,
    `minutes: ${exploreRun.minutes}`,
    `steps: ${exploreRun.stepLimit}`,
    `usedSteps: ${exploreRun.steps}`,
    `ended: ${ended}`,
    `candidates: ${kept.length}`,
    ...(truncated ? ['truncated: true'] : []),
    ...(stub ? ['stub: true'] : []),
    '---',
    '',
  ]
  const name = exploreSummaryName(exploreRun.startedAt)
  const file = join(directory, `${name}.md`)
  try {
    await mkdir(directory, { recursive: true })
    await writeFile(file, `${front.join('\n')}${body}\n`)
  } catch (error) {
    return { error: `the summary could not be written: ${error?.message ?? error}` }
  }
  let cards = 0
  if (kept.length > 0) {
    const cardsDir = exploreCandidatesDir()
    try {
      await mkdir(cardsDir, { recursive: true })
      for (const [index, one] of kept.entries()) {
        const cardFile = join(cardsDir, `${name}-${index + 1}.md`)
        await writeFile(cardFile, one.body)
        cards += 1
      }
      await explorePrune(cardsDir, '.md', EXPLORE_CANDIDATES_KEPT)
    } catch (error) {
      dropped.push({ index: 0, why: `the candidate cards could not be written: ${error?.message ?? error}` })
    }
  }
  await explorePrune(directory, '.md', EXPLORE_SUMMARIES_KEPT)
  exploreState.dropped += dropped.length
  if (dropped.length > 0) {
    console.warn(`luwi-channel: ${dropped.length} explore candidate card(s) were dropped - ${JSON.stringify(dropped)}`)
  }
  // 记一份"刚落到哪" (见 [exploreState] 的 lastSaved): 同一场再交一次时如实回"已经落过了"
  exploreState.lastSaved = {
    session: exploreRun.session,
    path: file,
    chars: body.length,
    candidates: cards,
    stub,
    at: Date.now(),
  }
  exploreReset()
  await exploreStateWrite()
  return { path: file, chars: body.length, truncated, candidates: cards, dropped }
}

/** 兜底: 到点了, 那一轮结束了而模型没交摘要 —— 写一份桩 (不写候选卡) */
async function exploreStub() {
  if (exploreRun.session === null) return
  const sessionId = exploreRun.session
  exploreState.stubs += 1
  console.log(`luwi-channel: exploration ${sessionId} ended without a summary - a stub was written instead`)
  const saved = await exploreWriteSummary({
    summary: '(这一场没有留下摘要: 到点/到步时没有调 lw_explore op=finish)',
    candidates: [],
    ended: 'deadline',
    stub: true,
  })
  if (saved.error) {
    exploreState.lastError = saved.error
    warn(hostCtx, saved.error)
  }
}

/**
 * 开一场探索 (op=start 与收件箱那条 `source:"explore"` 都走它)
 *
 * 判据顺序照方案 §5.4(d), 一步都不许换: 已经有活着的 -> 省电 -> 建工作区 -> 定预设 -> 建会话 ->
 * 投开场正文 -> 落 run。**不传 workspaceId 与 cwd 两个** (二选一, 与 [openBallConversation] 同形)
 */
async function startExplore(ctx, options = {}) {
  const via = typeof options?.via === 'string' && options.via !== '' ? options.via : 'tool'
  if (exploreRun.session !== null && exploreRun.over !== true) {
    exploreState.refusals += 1
    exploreState.lastRefusal = { at: Date.now(), why: 'running', session: exploreRun.session }
    return {
      started: false,
      refused: 'running',
      session: exploreRun.session,
      reason: `another exploration is still running (${exploreRun.session})`,
    }
  }
  // 上一场到点了而摘要还没交: 先按桩收掉, 再开这一场 (一场只允许一场)
  if (exploreRun.session !== null && exploreRun.over === true) await exploreStub()
  const power = await explorePowerSave()
  if (power.known && power.powerSave === true) {
    exploreState.refusals += 1
    exploreState.lastRefusal = { at: Date.now(), why: 'powerSave' }
    return {
      started: false,
      refused: 'powerSave',
      reason: 'the phone is in power save mode right now, so an exploration is not opened',
    }
  }
  if (!power.known) exploreState.powerSaveUnknown = true
  const controller = ctx?.get?.('sessionController')
  if (!controller) throw new Error('this profile has no session controller, so an exploration has nowhere to go')
  const workspace = await ensureExploreWorkspace(ctx)
  const preset = await chooseExplorePreset(ctx)
  exploreState.preset = preset.id ?? null
  exploreState.presetWhy = preset.why
  const minutes = exploreClamp(options?.minutes, EXPLORE_MINUTES_RANGE, EXPLORE_MINUTES_DEFAULT)
  const stepLimit = exploreClamp(options?.steps, EXPLORE_STEPS_RANGE, EXPLORE_STEPS_DEFAULT)
  const startedAt = Date.now()
  const summaryPath = await exploreLatestSummary()
  const request = {
    ...ballCreatePlace(workspace),
    ...(preset.id === undefined ? {} : { agentPreset: preset.id }),
  }
  let created = null
  try {
    created = await controller.create(request)
  } catch (error) {
    if (preset.id === undefined || preset.id === 'standard') throw error
    created = await controller.create({ ...ballCreatePlace(workspace), agentPreset: 'standard' })
    exploreState.preset = 'standard'
    exploreState.presetWhy = `${preset.why}; the create failed (${describePresetFailure(error)}),`
      + ' so standard was used instead'
  }
  const sessionId = String(created.sessionId)
  exploreRun.session = sessionId
  exploreRun.workspace = workspace?.path ?? ''
  exploreRun.startedAt = startedAt
  exploreRun.minutes = minutes
  exploreRun.deadline = startedAt + minutes * 60 * 1000
  exploreRun.steps = 0
  exploreRun.stepLimit = stepLimit
  exploreRun.reminded = false
  exploreRun.over = false
  await exploreStateWrite()
  try {
    await exploreSay(ctx, exploreStartLine(options?.text, exploreRun, summaryPath))
  } catch (error) {
    exploreState.lastError = `the first line was not delivered: ${error?.message ?? error}`
    exploreReset()
    await exploreStateWrite()
    throw error
  }
  exploreState.started += 1
  console.log(
    `luwi-channel: exploration ${sessionId} started in ${exploreRun.workspace}`
      + ` (${minutes} min / ${stepLimit} steps, via ${via}, preset ${exploreState.preset ?? 'the registry default'})`,
  )
  return {
    started: true,
    session: sessionId,
    workspace: exploreRun.workspace,
    minutes,
    stepLimit,
    deadline: exploreRun.deadline,
    via,
    preset: exploreState.preset,
    presetWhy: exploreState.presetWhy,
    summary: summaryPath,
  }
}

/**
 * 这一档用哪个预设: **解析不了就退 `standard` 并把原因记进读数** (照 [chooseVoicePreset] 的 why 形状)
 *
 * 与语音那一条不同的是它**不再往下试别的候选**: 探索的 persona 与工具面是绑在一起的 (那三条红线
 * 只在 explore 那一份里), 换到别的预设上去跑就不是"探索"了 —— 宁可退 standard 并如实说
 */
async function chooseExplorePreset(ctx) {
  const presets = ctx?.get?.('agentPresets')
  if (!presets || typeof presets.resolve !== 'function') {
    return { id: undefined, why: 'this profile has no preset registry, so the create picks for itself' }
  }
  try {
    const resolved = await presets.resolve(EXPLORE_PRESET)
    return { id: resolved.id, why: `the explore preset (${resolved.id})` }
  } catch (error) {
    return { id: 'standard', why: `${describePresetFailure(error)}; fell back to standard` }
  }
}

/** 收尾: 交摘要与候选卡 (唯一的出口) */
async function finishExplore(_ctx, args = {}, sessionId = '') {
  // 下一场那张卡 (批次 8): 收尾时模型交一段话, 宿主覆盖 `lw/explore-card.md`。**先算出来**,
  // 因为"已经落过了"那条分支也允许补交一次 nextTask (第二次 finish 常常是子代理发的)
  const askedNext = typeof args?.nextTask === 'string'
    ? args.nextTask.trim().slice(0, EXPLORE_TASK_CHARS)
    : ''
  if (exploreRun.session === null) {
    // **同一场刚交过就如实说"已经落过了"** (2026-10-17 真机上量到的形态, 见 [exploreState] 的
    // lastSaved): 桩摘要不算, 那种情况模型补一份真的正是我们要的
    const saved = exploreState.lastSaved
    if (saved !== null) {
      // 一行读数 (console 走应用抓的 stdout, 也就是 logcat): 这条路上两个 id 常常不是同一个
      // (2026-10-17 真机上第二次 finish 是子代理那一场发的), 所以判据不绑调用方
      console.log(
        `luwi-channel: a finish came with no run - session ${String(sessionId)},`
          + ` last saved for ${String(saved.session)}`
          + ` (${Date.now() - Number(saved.at ?? 0)}ms ago, stub ${String(saved.stub)})`,
      )
    }
    // **判据不绑调用方那一场**: 一场探索同时只许一场, 所以"刚有一份真的落了盘"这件事本身就是答案;
    // 而把 sessionId 也算进去会把子代理那条路 (它问的正是"我这一份交上去了吗") 挡成 "nothing to finish"
    const fresh = saved !== null && saved.stub !== true
      && Date.now() - Number(saved.at ?? 0) <= EXPLORE_ALREADY_SAVED_MS
    if (fresh) {
      const task = askedNext === '' ? null : await exploreTaskWrite(askedNext)
      if (task?.error) console.warn(`luwi-channel: the next task card was not written: ${task.error}`)
      return {
        saved: true,
        already: true,
        path: saved.path,
        chars: saved.chars,
        truncated: false,
        candidates: saved.candidates,
        dropped: [],
        nextTask: task,
      }
    }
    return { saved: false, why: 'no exploration is running, so there is nothing to finish' }
  }
  const summary = typeof args?.summary === 'string' ? args.summary : ''
  if (summary.trim() === '') {
    return { saved: false, why: 'the summary is empty: op=finish needs the body of the report' }
  }
  const ended = exploreRun.over === true ? 'deadline' : exploreRun.reminded === true ? 'steps' : 'finish'
  const saved = await exploreWriteSummary({
    summary,
    candidates: Array.isArray(args?.candidates) ? args.candidates : [],
    ended,
    stub: false,
  })
  if (saved.error) return { saved: false, why: saved.error }
  const task = askedNext === '' ? null : await exploreTaskWrite(askedNext)
  if (task?.error) console.warn(`luwi-channel: the next task card was not written: ${task.error}`)
  else if (task !== null) {
    console.log(`luwi-channel: the next exploration's task card now says ${task.chars} characters`)
  }
  exploreState.finished += 1
  console.log(
    `luwi-channel: exploration finished -> ${saved.path} (${saved.chars} chars,`
      + ` ${saved.candidates} candidate card(s), ${saved.dropped.length} dropped)`,
  )
  return { saved: true, ...saved, nextTask: task }
}

/**
 * 今天主动说了几次 (D17 的读数)
 *
 * 与 `AutomationStore.saidToday` 同一套过滤: **真的投出去的 `task`** (hit / 无 blocked /
 * delivered 不是 false / 不是手动 / action 是 task / 同一日期键), `remind` 与手动跑都不算。
 * 上限读 `automations/settings.json` 的 `saidLimit` (缺省 3, 夹 1..10)
 */
function proactiveSaidCount(lines, day) {
  let count = 0
  for (const line of lines) {
    if (typeof line !== 'string' || line.trim() === '') continue
    let one = null
    try {
      one = JSON.parse(line)
    } catch {
      continue
    }
    if (one?.hit !== true) continue
    if (one?.blocked !== undefined) continue
    if (one?.delivered === false) continue
    if (one?.manual === true) continue
    if (one?.action !== 'task') continue
    if (exploreDayKey(Number(one?.at)) !== day) continue
    count += 1
  }
  return count
}

async function proactiveSaid(now = Date.now()) {
  const home = process.env.DSH_HOME
  const answer = { today: 0, limit: EXPLORE_SAID_DEFAULT }
  if (!home) return answer
  const day = exploreDayKey(now)
  try {
    const raw = await readFile(join(home, 'automations', 'settings.json'), 'utf8')
    const asked = Number(JSON.parse(raw)?.saidLimit)
    if (Number.isFinite(asked)) answer.limit = Math.min(10, Math.max(1, Math.round(asked)))
  } catch {
    // 没有这一份就是缺省, 不是错
  }
  try {
    const raw = await readFile(join(home, 'automations', 'history.jsonl'), 'utf8')
    answer.today = proactiveSaidCount(raw.split('\n'), day)
  } catch {
    // 还没有历史
  }
  return answer
}

/** 这一档现在什么样 (给 `lw_explore op=status`) */
async function exploreStatus(ctx) {
  const budget = exploreBudget(exploreRun)
  const latest = await exploreLatestSummary()
  const cards = await exploreCandidatesCount()
  const readings = await voiceWorkspaceReadings(ctx, exploreRun.session ?? '', exploreWorkspaceState)
  const power = await explorePowerSave()
  const said = await proactiveSaid()
  const task = await exploreTaskInfo()
  return {
    running: exploreRun.session !== null && exploreRun.over !== true,
    over: exploreRun.over,
    due: budget.due,
    remainingMs: budget.remainingMs,
    stepsLeft: budget.stepsLeft,
    session: exploreRun.session ?? '',
    workspace: readings.workspace || exploreRun.workspace,
    workspacePath: exploreRun.workspace,
    workspaceId: readings.workspaceId || exploreWorkspaceState.id || '',
    member: readings.member,
    cwd: readings.cwd,
    registered: readings.registered,
    registeredWhy: readings.registeredWhy,
    startedAt: exploreRun.startedAt,
    deadline: exploreRun.deadline,
    minutes: exploreRun.minutes || EXPLORE_MINUTES_DEFAULT,
    steps: exploreRun.steps,
    stepLimit: exploreRun.stepLimit || EXPLORE_STEPS_DEFAULT,
    reminded: exploreRun.reminded,
    powerSave: power.powerSave,
    powerSaveKnown: power.known,
    powerSaveWhy: power.why,
    summary: latest,
    candidates: cards,
    said: said.today,
    saidToday: said.today,
    saidLimit: said.limit,
    saidDefault: EXPLORE_SAID_DEFAULT,
    nextTask: task,
    preset: exploreState.preset,
    presetWhy: exploreState.presetWhy,
    started: exploreState.started,
    finished: exploreState.finished,
    stubs: exploreState.stubs,
    dropped: exploreState.dropped,
    refusals: exploreState.refusals,
    lastRefusal: exploreState.lastRefusal,
    powerSaveUnknown: exploreState.powerSaveUnknown,
    lastError: exploreState.lastError,
  }
}

/**
 * `lw_explore op=status` 那一段回执: 一行一句, 结构化那份垫在下面 (`withJson`, 照 lw_automation 的形状)
 */
async function exploreStatusText(ctx) {
  const readings = await exploreStatus(ctx)
  const when = (stamp) => (Number(stamp) > 0 ? new Date(Number(stamp)).toISOString() : '')
  const lines = [
    readings.running
      ? `exploration: running in ${shortSession(readings.session)} - ${readings.steps}/${readings.stepLimit} steps,`
        + ` ${Math.round(readings.remainingMs / 1000)}s left${readings.due ? `, due ${readings.due}` : ''}`
      : readings.session === ''
        ? 'exploration: not running'
        : `exploration: over in ${shortSession(readings.session)}, waiting for lw_explore op=finish`,
    `budget: ${readings.minutes} minutes / ${readings.stepLimit} steps`
      + `${readings.reminded ? ' (the step note was sent)' : ''}, started ${when(readings.startedAt) || '(never)'}`,
    `workspace: ${readings.workspace || '(not registered)'} (id ${readings.workspaceId || 'none'}),`
      + ` cwd ${readings.cwd || '(unknown)'}, member ${readings.member === null ? 'unknown' : String(readings.member)},`
      + ` registered ${String(readings.registered)}${readings.registeredWhy ? ` - ${readings.registeredWhy}` : ''}`,
    `power save: ${readings.powerSaveKnown ? String(readings.powerSave) : `unknown (${readings.powerSaveWhy})`}`
      + `${readings.powerSaveUnknown ? ', the probe failed once this run' : ''}`,
    `latest summary: ${readings.summary || '(none yet)'}`,
    `candidate cards: ${readings.candidates} in all`,
    `said today: ${readings.saidToday} of ${readings.saidLimit} (task rules only; a manual run does not count)`,
    `next task card: ${readings.nextTask.path || '(none yet)'} (${readings.nextTask.chars} chars)`,
    `this run: ${readings.started} started, ${readings.finished} finished, ${readings.stubs} stub(s),`
      + ` ${readings.refusals} refused, ${readings.dropped} card(s) dropped`,
    `preset: ${readings.preset ?? '(the registry default)'}${readings.presetWhy ? ` - ${readings.presetWhy}` : ''}`,
  ]
  if (readings.lastRefusal) {
    lines.push(`the last refusal: ${readings.lastRefusal.why}`
      + `${readings.lastRefusal.session ? ` (${shortSession(readings.lastRefusal.session)})` : ''}`)
  }
  if (readings.lastError) lines.push(`the last error: ${readings.lastError}`)
  return withJson(lines, readings)
}

/** `op=finish` 那一段回执 */
function exploreFinishText(outcome) {
  const nextTask = outcome.nextTask === null || outcome.nextTask === undefined
    ? 'next task: no nextTask was given, so the card stays as it is'
    : outcome.nextTask.error
      ? `next task: the card was NOT written (${outcome.nextTask.error})`
      : `next task: the card for the next run now has ${outcome.nextTask.chars} characters`
  if (outcome.already === true) {
    return [
      `this exploration's summary was already saved: ${outcome.path}`,
      `body: ${outcome.chars} characters, candidate cards: ${outcome.candidates} written`,
      'nothing was written a second time - do not hand the same report in again',
      nextTask,
    ].join('\n')
  }
  if (outcome.saved !== true) return `the summary was not saved: ${outcome.why}`
  return [
    `exploration finished: ${outcome.path}`,
    `body: ${outcome.chars} characters${outcome.truncated ? ' (cut at the 8000-character limit)' : ''}`,
    `candidate cards: ${outcome.candidates} written`
      + `${outcome.dropped.length > 0 ? `, ${outcome.dropped.length} dropped (see the log)` : ''}`,
    nextTask,
  ].join('\n')
}

/**
 * 到点那一拍与"那一场接回来"的入口 (在 [apply] 里叫一次)
 *
 * 两个钩子: 5 s 一拍的预算检查, 以及"那一场的一轮结束了而摘要没交"那一条兜底 (写桩)
 */
function startExploreClock(ctx) {
  try {
    ctx.effect(
      () => {
        const timer = setInterval(() => void exploreTick(ctx), EXPLORE_TICK_MS)
        return () => clearInterval(timer)
      },
      'luwi-channel: the explore budget',
    )
  } catch (error) {
    warn(ctx, `the explore budget timer could not be registered: ${error?.message ?? error}`)
  }
  void exploreStateRestore()
    .then((restored) => {
      if (!restored) return
      console.log(`luwi-channel: an exploration from before the restart is back on its budget: ${exploreRun.session}`)
      return exploreTick(ctx)
    })
    .catch((error) => warn(ctx, `the explore state could not be read: ${error?.message ?? error}`))
  onSessionEvent(ctx, (session, event) => {
    if (event.type !== 'turn/end') return
    if (exploreRun.session === null || session.id !== exploreRun.session) return
    if (exploreRun.over !== true) return
    // 到点那一下**先取消再 followup**: 被取消那一轮的 turn/end 是 interrupted / aborted, 不许拿它写桩
    // (否则"取消了 / 模型还没开始收尾"就成了"没有留下摘要")
    const kind = event.data?.reason?.kind
    if (kind === 'interrupted' || kind === 'aborted') return
    void exploreStub()
  })
}

/* ── 余额 (2026-10-16, 「看余额」那一档) ──────────────────────────────────── */

/**
 * 应用那个请求记号多久之内算数 (与应用那侧 `BalanceFile.requestFresh` 同一个数, 谁都不许单边改)
 */
const BALANCE_REQUEST_FRESH_MS = 30 * 1000

/** 一次取数最多等多久: 主人正看着那块框, 网络不通也要尽快给个答案 (失败也是答案) */
const BALANCE_TIMEOUT_MS = 8000

/**
 * 试 DeepSeek 登录账号那条缝的 route (dsh 的 `llm-deepseek-account` 注册的那一个)
 *
 * **`deepseek-official` 不在这里**, 这一点正是 2026-10-17 修掉的那条真 bug: 它是
 * `llm-deepseek-api-key` 注册的那一条 (**API Key 路线**), 余额要拿那把 key 去问
 * `api.deepseek.com/user/balance` —— 而 2026-10-16 那一版把它接在账号缝上, 而真机这台部署里**根本
 * 没加载 `llm-deepseek-account`**, 于是 `balance.json` 上一直写着
 * `this host has no deepseekAccount service`, 主人看到的就是「当前模型不支持查询余额」
 */
const ACCOUNT_ROUTES = ['deepseek-account']

/** DeepSeek 系的 route: 拿不到 API Key 时这几个都试一次账号缝 (登录过的部署照样查得出来) */
const DEEPSEEK_ROUTES = ['deepseek-official', 'deepseek-account', 'deepseek']

/** 那条账号缝的服务名 */
const ACCOUNT_SEAM = 'deepseekAccount'

/** 那份文件: `$DSH_HOME/lw/balance.json` (与应用那侧的 `BalanceFile` 是同一个约定) */
function balancePath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', 'balance.json') : null
}

/** 应用→宿主那条"现在刷一次"的记号: `$DSH_HOME/lw/balance-request.json` ({"at":ms}) */
function balanceRequestPath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'lw', 'balance-request.json') : null
}

/**
 * 那个记号的时间戳 (主人刚写的那一下), 没有 / 过期就是 0
 *
 * **同一个记号只算一次** (判据在 [startBalance] 里那个 handled): 记号在 [BALANCE_REQUEST_FRESH_MS]
 * 之内一直"新鲜", 而这条轮询是 3 s 一拍 —— 按布尔读的话, 点一次「看余额」会在半分钟里问十遍账号缝
 */
async function balanceRequested() {
  const path = balanceRequestPath()
  if (!path) return 0
  try {
    const at = JSON.parse(await readFile(path, 'utf8'))?.at
    const age = Date.now() - at
    return typeof at === 'number' && age >= 0 && age <= BALANCE_REQUEST_FRESH_MS ? at : 0
  } catch {
    return 0
  }
}

/** 金额那一档: 定点两位的十进制字符串 (算出来的数也走它, 不把浮点的尾巴露给人) */
function decimalOf(value) {
  return Number.isFinite(value) ? (Math.round(value * 100) / 100).toFixed(2) : ''
}

/** 订阅制那几家给的是「已用百分比」, 取最紧的那一档算剩余 (几档窗口里最紧的才是主人要问的) */
function quotaPercentOf(body) {
  const limits = Array.isArray(body?.data?.limits) ? body.data.limits : []
  const used = limits
    .map((row) => Number(row?.TOKENS_LIMIT?.percentage))
    .filter((value) => Number.isFinite(value))
  if (used.length === 0) return null
  return { kind: 'quota', percent: Math.round(100 - Math.max(...used)) }
}

/** 月之暗面双站同一份响应的读法 (国内站与国际站是两个独立账号体系) */
function moonshotWallet(body) {
  const data = body?.data
  const value = data?.available_balance ?? data?.balance
  if (value === undefined || value === null) return null
  const bonus = data?.voucher_balance
  return {
    kind: 'money',
    currency: 'CNY',
    value: String(value),
    bonus: bonus === undefined || bonus === null ? '' : String(bonus),
  }
}

/** MiniMax 那份额度: 两个窗口 (本周期 / 本周) 里更紧的那个 */
function minimaxQuota(body) {
  const rows = Array.isArray(body?.model_remains) ? body.model_remains : []
  const left = rows
    .map((row) => Number(row?.current_interval_remaining_percent ?? row?.current_weekly_remaining_percent))
    .filter((value) => Number.isFinite(value))
  if (left.length === 0) return null
  return { kind: 'quota', percent: Math.round(Math.min(...left)) }
}

/**
 * 哪些提供方有"余额"这件事可查, 以及怎么问它 (2026-10-17 主人: 「能查看主流 llm 的余额」)
 *
 * 「看余额」仍然只查**当前模型**那一家 (2026-10-16 主人定的口径没变), 变的是那一家从只有 DeepSeek
 * 官方扩到主流厂商: 一张表 + 一段通用取数, 蓝本是社区里把"哪家真能查、字段在哪"写全的那一份
 * (pigeon189/zcode-fox-widget 的 `lib/vendors.mjs`, MIT)。**能查不到的一律回 `unsupported`,
 * 绝不自己编一个 0 出来** —— OpenAI 那条 `dashboard/billing` 官方已经下线, 阿里云百炼 / 火山方舟 /
 * 百度千帆 / 腾讯混元要云账号的 AK/SK (与推理用的 API Key 是两套凭据), Anthropic 只在 Admin API 上
 * 给组织级成本报表, 硅基流动 2026-08-14 停掉了 `/user/info` —— 这几家都不进这张表
 *
 * 答案两档: `money` 是金额 (带币种与赠金), `quota` 是订阅制那种"还剩百分之多少"的额度窗口
 *
 * 命中判据两条, **baseURL 的 host 优先**: route 名是用户自己起的 (自建 / 中转站对不上), 而地址骗
 * 不了人 —— 只要 baseURL 的主机是 api.moonshot.cn, 那一条路由叫什么查的都是月之暗面
 *
 * 以后哪一家有了余额接口, 在这张表里加一行就行 (取数的样子照下面那几条写)
 */
const BALANCE_SOURCES = [
  {
    label: 'DeepSeek',
    hosts: ['api.deepseek.com'],
    routes: ['deepseek-official', 'deepseek'],
    url: 'https://api.deepseek.com/user/balance',
    parse: (body) => {
      const wallets = Array.isArray(body?.balance_infos) ? body.balance_infos : []
      const wallet = wallets.find((row) => row?.currency === 'CNY') ?? wallets[0]
      if (wallet === undefined) return null
      return {
        kind: 'money',
        currency: wallet.currency ?? 'CNY',
        value: String(wallet.total_balance ?? ''),
        bonus: String(wallet.granted_balance ?? ''),
      }
    },
  },
  {
    label: 'Moonshot',
    hosts: ['api.moonshot.cn'],
    routes: ['moonshot', 'moonshot-cn', 'kimi'],
    url: 'https://api.moonshot.cn/v1/users/me/balance',
    parse: moonshotWallet,
  },
  {
    label: 'Moonshot (intl)',
    hosts: ['api.moonshot.ai'],
    routes: ['moonshot-intl'],
    url: 'https://api.moonshot.ai/v1/users/me/balance',
    parse: moonshotWallet,
  },
  {
    label: 'OpenRouter',
    hosts: ['openrouter.ai'],
    routes: ['openrouter'],
    url: 'https://openrouter.ai/api/v1/credits',
    parse: (body) => {
      // 这一家给两个独立的数 (买过多少 / 用过多少), 余额是算出来的那个差
      const credits = Number(body?.data?.total_credits)
      const usage = Number(body?.data?.total_usage)
      if (!Number.isFinite(credits) || !Number.isFinite(usage)) return null
      return { kind: 'money', currency: 'USD', value: decimalOf(credits - usage) }
    },
  },
  {
    label: 'StepFun',
    hosts: ['api.stepfun.com'],
    routes: ['stepfun'],
    url: 'https://api.stepfun.com/v1/accounts',
    parse: (body) => {
      const value = body?.balance ?? body?.data?.balance
      if (value === undefined || value === null) return null
      return { kind: 'money', currency: 'CNY', value: String(value) }
    },
  },
  {
    label: 'Novita',
    hosts: ['api.novita.ai'],
    routes: ['novita'],
    url: 'https://api.novita.ai/v3/user/balance',
    parse: (body) => {
      // 这一家的余额以 1e-4 美元为单位 (官方口径), 换算成元那两位才给人看
      const raw = Number(body?.availableBalance)
      if (!Number.isFinite(raw)) return null
      return { kind: 'money', currency: 'USD', value: decimalOf(raw / 10000) }
    },
  },
  {
    label: 'PPIO',
    hosts: ['api.ppio.com'],
    routes: ['ppio'],
    url: 'https://api.ppio.com/openapi/v1/billing/balance/detail',
    parse: (body) => {
      const value = body?.availableBalance ?? body?.data?.availableBalance
      if (value === undefined || value === null) return null
      return { kind: 'money', currency: 'CNY', value: String(value) }
    },
  },
  {
    label: 'Zhipu GLM',
    hosts: ['open.bigmodel.cn'],
    routes: ['zai', 'zhipu', 'glm'],
    url: 'https://open.bigmodel.cn/api/monitor/usage/quota/limit',
    // 智谱这一家认裸 key, 前面**不带** Bearer (照那一份蓝本的 auth 模板, 它没给任何一家写特殊头)
    headers: (key) => ({ Authorization: key }),
    parse: quotaPercentOf,
  },
  {
    label: 'Z.ai GLM',
    hosts: ['api.z.ai'],
    routes: ['zai-intl', 'zhipu-intl'],
    url: 'https://api.z.ai/api/monitor/usage/quota/limit',
    headers: (key) => ({ Authorization: key }),
    parse: quotaPercentOf,
  },
  {
    label: 'MiniMax',
    hosts: ['api.minimaxi.com'],
    routes: ['minimax'],
    url: 'https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains',
    parse: minimaxQuota,
  },
  {
    label: 'MiniMax (intl)',
    hosts: ['api.minimax.io'],
    routes: ['minimax-intl'],
    url: 'https://api.minimax.io/v1/api/openplatform/coding_plan/remains',
    parse: minimaxQuota,
  },
]

/** 一条地址的主机名 (取不出来就是空串: 自建端点写的可能是半截地址) */
function hostOf(url) {
  try {
    return new URL(url).host.toLowerCase()
  } catch {
    return ''
  }
}

/** 这一次查哪一家: host 优先, 其次是 route 名 */
function balanceSourceFor(provider, baseURL) {
  const host = hostOf(baseURL)
  const route = String(provider ?? '').toLowerCase()
  return BALANCE_SOURCES.find((source) => host !== '' && source.hosts.includes(host))
    ?? BALANCE_SOURCES.find((source) => source.routes.includes(route))
    ?? null
}

/**
 * 某条路由的 profile (baseURL / apiKeyEnv / displayName)
 *
 * 段名与那一层路径**从适配器自己登记的目录里读**, 不写死 (与「添加模型」那条同一个来源, 部署换过
 * 行 id 也对得上); 值从 `settings.describe()` 里取 —— 那是这份配置的 live 值
 *
 * 路径有两档, 都得认: pi-ai 那几家是 `['providers']` (再按 route 取一层), 而 dsh 自家那几家
 * (`llm-deepseek`) 登记的是**空路径** —— 那一段本身就是 profile
 */
function providerProfile(ctx, provider) {
  const entry = (ctx.get('llm')?.listConfigurableProviders?.() ?? []).find((row) => row?.provider === provider)
  if (entry === undefined) return null
  const section = (ctx.get('settings')?.describe?.() ?? []).find((row) => row.ns === entry.settingsNs)
  const path = Array.isArray(entry.settingsPath) ? entry.settingsPath : []
  let node = section?.value
  for (const key of path) {
    if (node === null || node === undefined || typeof node !== 'object') return null
    node = node[key]
  }
  const profile = path.length === 0 || node === null || node === undefined ? node : node[provider]
  return profile !== undefined && profile !== null && typeof profile === 'object' ? profile : null
}

/** 给人看的名字: 优先适配器自己登记的那个 displayName, 没有就用表里写的那一个 */
function profileLabel(profile, fallback) {
  return typeof profile?.displayName === 'string' && profile.displayName !== '' ? profile.displayName : fallback
}

/**
 * 那条路由的 API Key, 以及"为什么没有"
 *
 * 名字挂在 profile 的 apiKeyEnv 上, 值住在凭据缝里 (与 dsh 每轮请求取的是同一份)。没有的时候
 * [problem] 是一句能照着修的人话 —— 它会被拼进失败那句原话, 于是一次查询就能看出卡在哪一环
 */
async function providerKey(ctx, profile) {
  if (profile === null) return { key: '', problem: 'this host knows no profile for that route' }
  const ref = typeof profile.apiKeyEnv === 'string' ? profile.apiKeyEnv : ''
  if (ref === '') return { key: '', problem: 'that route names no credential reference' }
  const credentials = ctx.get('credentials')
  if (credentials === undefined || typeof credentials.resolve !== 'function') {
    return { key: '', problem: 'this host has no credentials service' }
  }
  const resolved = await credentials.resolve(ref)
  if (typeof resolved?.value !== 'string' || resolved.value === '') {
    return { key: '', problem: `the credential ${ref} is not configured on this host` }
  }
  return { key: resolved.value, problem: '' }
}

/**
 * 一次真正的取数
 *
 * 失败一律抛, 调用方把那句话写进 [payload].reason —— 应用那一侧只把原话递上去, 不自己编一句
 */
async function readWallet(source, key) {
  const response = await fetch(source.url, {
    headers: {
      Accept: 'application/json',
      ...(typeof source.headers === 'function' ? source.headers(key) : { Authorization: `Bearer ${key}` }),
    },
    signal: AbortSignal.timeout(BALANCE_TIMEOUT_MS),
  })
  if (!response.ok) throw new Error(`${source.label} answered HTTP ${response.status}`)
  const wallet = source.parse(await response.json())
  if (wallet === null) throw new Error(`${source.label} answered something this build cannot read`)
  return wallet
}

/**
 * DeepSeek 登录账号那一条: 走它的账号缝 (`deepseekAccount.getBalance`, 与 GUI 里那个账户余额同源)
 *
 * 这条**不需要 API Key**, 所以它不进 [BALANCE_SOURCES] 那张 HTTP 表。它只是 DeepSeek 系的**兜底**:
 * 手上有 `DEEPSEEK_API_KEY` 时走的是 HTTP 那一条 (见 [refreshBalance])。部署里没加载这条缝时
 * (真机上量到的正是这一档) 回一句「不支持」, 绝不编一个数出来
 *
 * @param ctx 宿主上下文 (拿服务与 locale)
 * @param payload 正在攒的那份答案 (就地改写)
 * @param note 另一条路为什么也没走通 —— 拼在"没有那条缝"后面, 一句原话要说清卡在哪一环
 */
async function readAccountBalance(ctx, payload, note = '') {
  const account = ctx.get(ACCOUNT_SEAM)
  if (account === undefined || typeof account.getBalance !== 'function') {
    // 那条缝整个不在: 对主人来说与"不支持"是一回事 (这一档永远答不出数)
    // [note] 是"另一条路为什么也没走通", 拼在后面 —— 失败那句原话要能一次说清卡在哪
    payload.unsupported = true
    payload.reason = `this host has no ${ACCOUNT_SEAM} service${note === '' ? '' : `; ${note}`}`
    return
  }
  // client 元数据与 GUI 那一侧同一个形状 (version / locale / 时区偏移): 凭据不过这一层
  // **`ctx.locale` 不能直接点** (2026-10-17 模拟器上量到的): 插件没 inject 那个服务时, 代理在
  // 取属性的那一刻就抛 `cannot get property "locale" without inject`, `?.` 一个都挡不住 ——
  // 现象是「看余额」在"没配 API Key、退账号缝"那一档永远失败, `⚠` 上念的还是这句内部话
  const client = {
    version: process.env.DSH_CLIENT_VERSION || 'luwi',
    locale: ctx.get('locale')?.getSnapshot?.()?.active || 'zh-CN',
    timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
  }
  const result = await account.getBalance(client)
  if (result === null) {
    payload.reason = 'the DeepSeek account is not signed in on this host'
  } else if (result.status !== 'ready' || !Array.isArray(result.value) || result.value.length === 0) {
    payload.reason = 'the account service reported a failure'
  } else {
    payload.ok = true
    payload.currency = result.value[0]?.currency ?? ''
    payload.value = result.value[0]?.balance ?? ''
    payload.bonus = Array.isArray(result.bonusWallets) ? (result.bonusWallets[0]?.balance ?? '') : ''
  }
}

/**
 * 把"当前模型那一边的余额"写给应用
 *
 * 数只有宿主拿得到, 而球与那块文本框在应用进程里 —— 所以还是那条老路: **文件即真相** (与
 * ball-phase.json 同一条), 原子写 (`.tmp` + rename), 应用用 inotify 盯着那个目录
 *
 * 四种结果各说各的话, **绝不编 0**:
 * - 主流厂商那几家 → 读那条路由的 baseURL 与凭据, 按 [BALANCE_SOURCES] 取一次数 (DeepSeek 官方
 *   这一档就是这么查的: 它是 `llm-deepseek-api-key` 那条路线, 手上有 `DEEPSEEK_API_KEY`)
 * - DeepSeek 系而手上没有 API Key → 退到登录账号那条缝 (`deepseekAccount`)
 * - 其余主流厂商 → 读那条路由的 baseURL 与凭据, 按 [BALANCE_SOURCES] 取一次数
 * - 不在表里 (或连"当前模型"都取不到) → `unsupported: true`, 应用说「当前模型不支持查询余额」
 * - 取数本身失败 (没配 key / 网络 / 那家答的东西读不出来) → `ok: false` + 一句原话, 应用照念
 *
 * 应用那一侧点「看余额」才会走到这儿 ([startBalance] 那条轮询看见请求记号), 所以这里没有节流那一层
 *
 * @param ctx 宿主上下文 (拿服务与 locale)
 */
async function refreshBalance(ctx) {
  const now = Date.now()
  const path = balancePath()
  if (path === null) return
  const payload = {
    v: 2, at: now, ok: false, unsupported: false, provider: '', label: '', model: '',
    kind: '', currency: '', value: '', bonus: '', percent: 0, reason: '',
  }
  try {
    // 先认当前模型: 没有这条缝 / 取不到, 都是"不支持查询当前模型"那一档
    const defaults = ctx.get('agentDefaultModel')
    const current = typeof defaults?.currentSelection === 'function' ? defaults.currentSelection() : null
    const provider = typeof current?.provider === 'string' ? current.provider : ''
    payload.provider = provider
    payload.model = typeof current?.model === 'string' ? current.model : ''
    if (provider === '') {
      payload.unsupported = true
      payload.reason = 'the current model is unknown on this host'
    } else {
      const profile = await providerProfile(ctx, provider)
      const source = balanceSourceFor(provider, profile?.baseURL)
      if (source === null && !DEEPSEEK_ROUTES.includes(provider)) {
        payload.unsupported = true
        payload.reason = `no balance source for the current model (${provider}/${payload.model})`
      } else if (source === null) {
        // 表里没有这一家, 但它是 DeepSeek 系的: 还有登录账号那一条路
        payload.kind = 'money'
        payload.label = profileLabel(profile, 'DeepSeek')
        await readAccountBalance(ctx, payload, 'that route is not in the balance table either')
      } else {
        payload.label = profileLabel(profile, source.label)
        const { key, problem } = await providerKey(ctx, profile)
        if (key !== '') {
          const wallet = await readWallet(source, key)
          payload.ok = true
          payload.kind = wallet.kind
          payload.currency = wallet.currency ?? ''
          payload.value = wallet.value ?? ''
          payload.bonus = wallet.bonus ?? ''
          payload.percent = wallet.percent ?? 0
        } else if (DEEPSEEK_ROUTES.includes(provider)) {
          // 手边没有那把 key: DeepSeek 系还有那条账号缝 (与 GUI 里的账户余额同源)
          payload.kind = 'money'
          payload.label = profileLabel(profile, 'DeepSeek')
          await readAccountBalance(ctx, payload, `no API key: ${problem}`)
        } else {
          payload.reason = `this host holds no API key for ${provider}: ${problem}`
        }
      }
    }
  } catch (error) {
    payload.reason = `fetching the balance failed: ${error?.message ?? error}`
  }
  const temporary = `${path}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(temporary, `${JSON.stringify(payload)}\n`)
    await rename(temporary, path)
  } catch (error) {
    console.warn(`luwi-channel: the balance file was not written: ${error?.message ?? error}`)
  }
}

/**
 * 余额那一条**唯一的**触发: 应用写出来的那个请求记号
 *
 * 2026-10-17 主人: 「余额查询只能用户主动在 ball 菜单主动查询」。从前这里还有三条自己起的 —— 起来刷
 * 一次、起来后 3 / 10 / 30 s 各补一拍、每轮结束 (`turn/end`) 刷一次 —— 它们一起拿掉了, 两个理由:
 *
 *   1. **那三条查出来的数一个字都不会被主人看见**: 应用那一侧只在"他刚点过菜单"那 30 s 里才把那份
 *      文件写进框 ([OverlayService.readBalance]), 于是那些刷新只是白问一次
 *   2. **它们本来也不该问**: 每轮结束都去问一次账号缝等于把 Platform 那个接口当成心跳, 起来那三拍更
 *      是"刚开机就先替你查一次钱" —— 这两件事都不该由应用替主人决定
 *
 * 所以链路收成一条: 点「看余额」→ 应用写 `balance-request.json` → 这条轮询看见 → 查一次 → 落
 * `balance.json` → 应用当场把「正在取…」换成真数。**同一个记号只认一次** (30 s 的新鲜窗口配一拍 3 s 的
 * 轮询, 不记住处理过哪个就会点一次问十遍), 一次取数最多 [BALANCE_TIMEOUT_MS]
 */
function startBalance(ctx) {
  /** 已经处理过的那个记号: 同一个只刷一次 ([balanceRequested] 那 30 s 窗口里它会一直被读成"新鲜") */
  let handled = 0
  const timer = setInterval(() => {
    void balanceRequested().then((stamp) => {
      if (stamp === 0 || stamp <= handled) return
      handled = stamp
      void refreshBalance(ctx)
    })
  }, PLUGIN_POLL_MS)
  if (typeof timer.unref === 'function') timer.unref()
}

/**
 * Register the provider the page's voice input button resolves to
 *
 * 与球同一个引擎 (2026-10-09): 下载那一栏走的是 SenseVoice 那 240 MB, 界面里的磁盘 / 内存 / 时间
 * 估算也跟着换成它那一档 —— 那三个数只给"这台设备够不够"当提示, 不是任何判据
 */
function registerVoiceInput(ctx) {
  const speech = ctx.speechToText
  if (!speech || typeof speech.register !== 'function') return
  speech.register({
    info: {
      id: SPEECH_PROVIDER_ID,
      name: 'On-device SenseVoice',
      location: 'host-local',
      languages: SPEECH_LANGUAGES,
      downloadSources: SPEECH_SOURCES,
      // 磁盘那一栏是量的 (SenseVoice 228 MiB + tokens + 1.8 MB 的 silero), 内存那一栏是估的
      setupEstimate: {
        recommendedDiskBytes: 260 * 1024 * 1024,
        expectedMemoryBytes: 1000 * 1024 * 1024,
        minimumMinutes: 1,
        maximumMinutes: 15,
      },
    },
    preparation: {
      snapshot: () => ({ ...speechState }),
      /**
       * 起一次准备
       *
       * **这一条一个异常都不许往外冒**: dsh 那一头 (`SpeechToText.prepare` 与 `SpeechController.prepare`)
       * 是 `registration.provider.preparation?.prepare(options)` —— 返回值直接丢掉, 而 app-boot 把
       * 未处理的 rejection 当致命错误 (`fatal load failure` 之后 `proc.exit(1)`)。2026-10-10 真机上
       * 就是这么断的: 模型没下成 (`could not download model.int8.onnx: fetch failed`), 整个 host 跟着
       * 退出, 界面只剩 ERR_CONNECTION_REFUSED。上游那套 sensevoice provider 正是照这个形状写的:
       * 同步签名 + 异步那半条链自己 catch, 失败只写进状态。所以这里也照办 —— 失败的原因与下载诊断
       * 早就由 `speechDownload` 写进 `speechState` (界面那张卡读的就是它), 这里只补一行日志
       */
      prepare: () => {
        void speechPrepare(SPEECH_ENGINE_SHERPA).catch((error) => {
          warn(ctx, `preparing the speech model failed: ${error?.message ?? error}`)
        })
      },
      cancel: async () => {
        speechAnnounce('cancelled')
      },
      subscribe: (listener) => {
        speechListeners.add(listener)
        return () => speechListeners.delete(listener)
      },
    },
    async transcribe({ audio, language }) {
      const info = await speechInspect()
      // 这条按钮与球走同一套 (2026-10-09): 盘上那一个模型就是它, 不再按"GLM 在不在"分岔 ——
      // 分岔的代价是别人为了按一次按钮要多下 1.6 GB, 而 GLM 的准是**点名要它**时才值得的
      const engine = SPEECH_ENGINE_SHERPA
      if (!info.present) {
        throw new Error('the SenseVoice model is not downloaded yet: run lw_speech op=prepare once')
      }
      const directory = info.directory
      await mkdir(directory, { recursive: true })
      const wav = join(directory, `recording-${randomUUID()}.wav`)
      await writeFile(wav, Buffer.from(audio))
      let answer = null
      try {
        answer = await speechTranscribe(wav, language, engine)
        return { text: answer.text }
      } finally {
        // **不再当场删掉**: 最近几段录音与它们的转写结果留一份 (见 [speechKeep]) —— 主人 2026-10-07
        // 报的"识别时会出乱码"需要那一段音频才查得下去, 而删掉之后连现场都没了
        await speechKeep(directory, wav, answer, engine)
      }
    },
  })
  // Look once at load so the page knows whether this provider is usable, without downloading
  void speechInspect().catch((error) => speechAnnounce('failed', String(error?.message ?? error)))
}

