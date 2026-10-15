/**
 * 视频模式独立模型 (3.0.2 需求 2) 的判据表
 *
 * 这一批的两侧是: 应用写 `$DSH_HOME/lw/mode-model.json` (宿主读), 宿主写
 * `$DSH_HOME/lw/model-catalog.json` (应用读)。两份文件谁都不许单边改键名, 而键名漂开的样子
 * 一点都不响亮 —— 应用存下去、宿主读不到, 人会以为"功能坏了"。所以这份脚本查五件事:
 *
 *   1. **文件名与上限两侧一致**: 三份文件名、64 KiB 上限、请求记号的 30 s 窗口
 *   2. **键名两侧一致**: mode-model 的 video/provider/model 与 model-catalog 的
 *      current/items/provider/model/label 在两侧都对得上
 *   3. **设置页引用的字符串键两份 strings.xml 都有, 而且顺序一致** (与 check-sync-setting
 *      同一个理由: `Resources.getString` 少一个就是 `Resources$NotFoundException`, 而它偏偏在
 *      LazyColumn 预取时才炸); 顺手把带占位符的那条与引用处的参数对一遍
 *   4. **纯函数表**: 从宿主插件里抽出 `resolveModeModel` 真的跑一遍, 钉住施工单第 5 节那张表
 *      —— 判定抽成纯函数就是为了让这份脚本能直接量它, 两边别漂
 *   5. **挂载点真的在**: 宿主读文件、写目录、turn/start 挂钩、selectModel 之后还默认、
 *      lw_mode op=status 的 model 读数、桥上的 `model` 方法, 一处都不能少
 *
 * 跑法: node tools/check-mode-model.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const modeModel = await read('app/src/main/java/io/github/yuloong07star/luwi/sync/ModeModel.kt')
const lwModel = await read('app/src/main/java/io/github/yuloong07star/luwi/tool/LwModel.kt')
const bridge = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const screen = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const zh = await read('app/src/main/res/values-zh/strings.xml')
const en = await read('app/src/main/res/values/strings.xml')
const host = await read('host-plugin/index.mjs')

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

/* ── 一、文件名与上限两侧一致 ─────────────────────────────────────────── */

check('mode-model.json 的文件名两侧同名', [
  /const val FILE = "mode-model\.json"/u.test(modeModel),
  /MODE_MODEL_FILE = 'mode-model\.json'/u.test(host),
], [true, true])

check('model-catalog.json 的文件名两侧同名', [
  /FILE_NAME = "model-catalog\.json"/u.test(lwModel),
  /MODEL_CATALOG_FILE = 'model-catalog\.json'/u.test(host),
], [true, true])

check('目录请求记号的文件名两侧同名', [
  /REQUEST_NAME = "model-catalog-request\.json"/u.test(lwModel),
  /MODEL_CATALOG_REQUEST_FILE = 'model-catalog-request\.json'/u.test(host),
], [true, true])

check('请求记号的新鲜窗口两侧同一个数 (30 s)', [
  lwModel.includes('withinMs') && host.includes('MODEL_CATALOG_REQUEST_FRESH_MS = 30 * 1000'),
  /MODEL_CATALOG_REQUEST_FRESH_MS = 30 \* 1000/u.test(host),
], [true, true])

check('宿主读配置的 64 KiB 上限还在', /MODE_MODEL_MAX_BYTES = 64 \* 1024/u.test(host), true)

/* ── 二、键名两侧一致 ─────────────────────────────────────────────────── */

check('mode-model 的键与应用侧声明的是同一组 (video 三个 + endpoint 四个)', (() => {
  const keys = [...modeModel.matchAll(/const val ([A-Z_]+)_KEY = "([^"]+)"/gu)].map((one) => one[2])
  return keys
})(), ['video', 'provider', 'model', 'endpoint', 'url', 'apiKey', 'api'])

check('宿主读的那两段都逐键校验 (video 那一对 + endpoint 那一组)', [
  /pairOf\(parsed\.video\)/u.test(host),
  /typeof section\.provider === 'string'/u.test(host),
  /typeof section\.model === 'string'/u.test(host),
  // 自建端点: 地址与模型名都在才算配好, 而且只有它能给出 luwi-video 这条路由
  /fieldsOf\(parsed\.endpoint\)/u.test(host),
  /if \(endpoint\) return \{ provider: VIDEO_ROUTE, model: endpoint\.model \}/u.test(host),
], [true, true, true, true, true])

check('宿主写的目录与应用解析的键一一对应', [
  // 宿主 writeModelCatalog 写的
  /payload\.items = \(catalog\?\.groups \?\? \[\]\)\.flatMap/u.test(host),
  /provider: String\(group\.id \?\? ''\)/u.test(host),
  /model: String\(model\.id \?\? ''\)/u.test(host),
  /label: `\$\{group\.name \?\? group\.id\} · \$\{model\.name \?\? model\.id\}`/u.test(host),
  // 应用 LwModel.parse 读的
  /field\(current, "provider"\)/u.test(lwModel),
  /field\(current, "model"\)/u.test(lwModel),
  /field\(item, "provider"\)/u.test(lwModel),
  /field\(item, "model"\)/u.test(lwModel),
  /field\(item, "label"\)/u.test(lwModel),
], Array(9).fill(true))

check('ok:false 时应用退文本框, 宿主不假装成功', [
  /payload\.ok = payload\.items\.length > 0/u.test(host),
  /it\.ok && it\.items\.isNotEmpty\(\)/u.test(screen),
], [true, true])

/* ── 三、strings 两份都有、顺序一致、占位符对上 ───────────────────────── */

const MODE_KEYS = [
  'settings_mode_model',
  'settings_mode_model_summary',
  'settings_mode_model_follow',
  'settings_mode_model_none',
  'settings_mode_endpoint',
  'settings_mode_endpoint_summary',
  'settings_mode_endpoint_none',
  'settings_mode_endpoint_using',
  'settings_mode_endpoint_note',
  'settings_mode_endpoint_url_note',
  'settings_mode_endpoint_key_note',
  'settings_mode_endpoint_model_note',
  'settings_mode_endpoint_off',
  'settings_mode_endpoint_off_summary',
]

function stringOrder(text) {
  const positions = MODE_KEYS.map((key) => text.indexOf(`name="${key}"`))
  return positions.every((at, index) => at >= 0 && (index === 0 || at > positions[index - 1]))
}
check('十四个字符串键两份 strings.xml 都有且顺序一致', [stringOrder(zh), stringOrder(en)], [true, true])

check('设置页引用的正是这十四个键', MODE_KEYS.map((key) => screen.includes(`R.string.${key}`)), Array(MODE_KEYS.length).fill(true))

check('模型那一行住在「视频模式」那一段里 (2026-10-16 归并)', [
  // 段名是视频模式, 而且它的 Card 里先 ModeItems 后 LookItems
  /"settings_section_look">视频模式</u.test(zh),
  /"settings_section_look">Video mode</u.test(en),
  /Card \{\n\s+Column \{\n\s+ModeItems\(\)\n\s+LookItems\(\)/u.test(screen),
  // 单独那段已经撤掉
  !screen.includes('R.string.settings_section_mode'),
], [true, true, true, true])

check('带占位符的那几条两份都是同一组说明符, 而且引用处给了参数', [
  /"settings_mode_model_summary">当前: %1\$s</u.test(zh),
  /"settings_mode_model_summary">Current: %1\$s</u.test(en),
  /"settings_mode_endpoint_summary">%1\$s · %2\$s · key %3\$s</u.test(zh),
  /"settings_mode_endpoint_summary">%1\$s · %2\$s · key %3\$s</u.test(en),
  /"settings_mode_endpoint_using">走添加的模型: %1\$s</u.test(zh),
  /"settings_mode_endpoint_using">Using the added model: %1\$s</u.test(en),
  /R\.string\.settings_mode_endpoint_summary,/u.test(screen),
  /R\.string\.settings_mode_endpoint_using,/u.test(screen),
], Array(8).fill(true))

check('那一行与那个对话框都叫「添加模型」, 三个框各自说清填什么 (2026-10-16 主人)', [
  /"settings_mode_endpoint">添加模型</u.test(zh),
  /"settings_mode_endpoint">Add a model</u.test(en),
  /"settings_mode_endpoint_none">还没添加模型/u.test(zh),
  /"settings_mode_endpoint_off">关掉添加的模型</u.test(zh),
  // 三个框的标题都挂在 label 上 (SuperTextField 那个口子), 一份中文一份英文
  /label = stringResource\(R\.string\.settings_mode_endpoint_url_note\)/u.test(screen),
  /label = stringResource\(R\.string\.settings_mode_endpoint_key_note\)/u.test(screen),
  /label = stringResource\(R\.string\.settings_mode_endpoint_model_note\)/u.test(screen),
  /"settings_mode_endpoint_url_note">接口地址: http\(s\) 开头的地址/u.test(zh),
  /"settings_mode_endpoint_url_note">Endpoint URL: an http\(s\) address/u.test(en),
  /"settings_mode_endpoint_key_note">API Key: .*留空</u.test(zh),
  /"settings_mode_endpoint_key_note">API key: .*blank/u.test(en),
  /"settings_mode_endpoint_model_note">模型名: /u.test(zh),
  /"settings_mode_endpoint_model_note">Model id: /u.test(en),
], Array(13).fill(true))

check('自建端点那一段的键与纪律 (App 侧)', [
  /const val ENDPOINT_KEY = "endpoint"/u.test(modeModel),
  /const val URL_KEY = "url"/u.test(modeModel),
  /const val SECRET_KEY = "apiKey"/u.test(modeModel),
  /const val ROUTE = "luwi-video"/u.test(modeModel),
  // key 只在文件里: 那三个入口都经过打码 / 密码框
  /fun maskedKey\(value: String\)/u.test(modeModel),
  /PasswordVisualTransformation\(\)/u.test(screen),
  /ModeModel\.maskedKey\(snapshot\.endpointSecret\)/u.test(screen),
  /ModeModel\.setEndpoint\(context, url, secret, model\)/u.test(screen),
  /ModeModel\.clearEndpoint\(context\)/u.test(screen),
], Array(9).fill(true))

check('带占位符的字符串没有裸 % (check-bare-percent 的那条规矩)', [
  /"settings_mode_model_summary">[^<]*[^%]%[^1$<]/u.test(zh),
  /"settings_mode_model_summary">[^<]*[^%]%[^1$<]/u.test(en),
], [false, false])

/* ── 四、纯函数表: 把 resolveModeModel 抽出来真的跑一遍 ───────────────────── */

const source = host.match(/function resolveModeModel\([\s\S]*?\n\}/u)?.[0] ?? ''
const resolve = new Function('MODE_VIDEO', 'MODE_PHONE', `${source}; return resolveModeModel`)(
  'video',
  'phone',
)
const CFG = { provider: 'video-p', model: 'video-m' }
const OTHER = { provider: 'home-p', model: 'home-m' }

check('纯函数: 配置为空 (跟随当前) → 什么都不做', [
  resolve('video', null, OTHER, null),
  resolve('phone', null, CFG, OTHER),
], [null, null])

check('纯函数: 视频模式 + 模型不同 → 切到配置的那一对', resolve('video', CFG, OTHER, null), {
  provider: 'video-p',
  model: 'video-m',
})

check('纯函数: 视频模式 + 已经是配置的那一对 → 不发', resolve('video', CFG, CFG, null), null)

check('纯函数: 视频模式 + 读不到那一场的模型 → 不猜, 不发', resolve('video', CFG, null, null), null)

check('纯函数: 手机模式 + 还在视频模型上 + 有要还的 → 还回去', resolve('phone', CFG, CFG, OTHER), {
  restore: true,
  provider: 'home-p',
  model: 'home-m',
})

check('纯函数: 手机模式 + 要还的与视频模型一样 → 不发', resolve('phone', CFG, CFG, CFG), null)

check('纯函数: 手机模式 + 已经不在视频模型上 → 不发', resolve('phone', CFG, OTHER, OTHER), null)

check('纯函数: 手机模式 + 没有可还的那一个 (宿主刚重启) → 不发', resolve('phone', CFG, CFG, null), null)

check('纯函数: 别的模式 (退役的 / 未来的) → 什么都不做', resolve('screen', CFG, CFG, OTHER), null)

/* ── 五、挂载点真的在 ───────────────────────────────────────────────── */

check('宿主真的在读 mode-model.json (读不到/坏文件都回 null)', [
  /function readModeModelConfig\(\)/u.test(host),
  /homePath\('lw', MODE_MODEL_FILE\)/u.test(host),
], [true, true])

check('宿主真的在写 model-catalog.json (原子写)', [
  /async function writeModelCatalog\(ctx\)/u.test(host),
  /await writeFile\(temporary/u.test(host),
  /await rename\(temporary, path\)/u.test(host),
], [true, true, true])

check('turn/start 那个触发点挂上了, 且只根会话', [
  /function startModeModel\(ctx\)/u.test(host),
  /event\.type !== 'turn\/start'/u.test(host),
  /session\.meta\?\.origin === 'subagent'/u.test(host),
], [true, true, true])

check('lw_mode 切换那一路也在 (exec 里, 现取 preDefault)', [
  /applyModeModelToSession\(hostCtx, session, toVideo \? MODE_VIDEO : MODE_PHONE, preDefault\)/u.test(host),
  /defaults\.currentSelection\(\)/u.test(host),
], [true, true])

check('selectModel 之后立刻把部署默认还回去 (必须的一步)', [
  /await controller\.selectModel\(\{/u.test(host),
  /await defaults\.saveSelection\(preDefault\)/u.test(host),
  /await defaults\.saveSelection\(\{ provider: conclusion\.provider, model: conclusion\.model \}\)/u.test(host),
], [true, true, true])

check('lw_mode op=status 带着那五项读数', [
  /`model: \$\{JSON\.stringify\(modeModelStatus\(\)\)\}`/u.test(host),
  /configured: readModeModelConfig\(\),/u.test(host),
  /active: modeModelState\.lastDefault,/u.test(host),
  /switches: modeModelState\.switches,/u.test(host),
  /restored: modeModelState\.restored,/u.test(host),
  /lastError: modeModelState\.lastError,/u.test(host),
  /sessions: \[\.\.\.modeModelState\.sessions\],/u.test(host),
], Array(7).fill(true))

check('同一份读数也落成 lw/mode-model-state.json (量它不必解压缩的会话日志)', [
  /MODE_MODEL_STATE_FILE = 'mode-model-state\.json'/u.test(host),
  /async function writeModeModelState\(\)/u.test(host),
  /const payload = \{ v: 1, at: Date\.now\(\), \.\.\.modeModelStatus\(\) \}/u.test(host),
  // 起来写一次 + 每次切换/还原之后写一次 + 配置改了 (而没发 RPC) 时轮询补一次
  /void writeModeModelState\(\)\.catch/u.test(host),
  /await writeModeModelState\(\)\n  return conclusion/u.test(host),
  /await writeModeModelState\(\)\n  return restored/u.test(host),
  /JSON\.stringify\(modeModelStatus\(\)\) !== modeModelState\.writtenState/u.test(host),
], Array(7).fill(true))

check('apply() 里挂了 startModeModel', /startModeModel\(ctx\)/u.test(host), true)

check('lw_mode 的 exec 里用的是 hostCtx 不是 ctx (真机踩过 "ctx is not defined")', [
  /applyModeModelToSession\(hostCtx, session, toVideo \? MODE_VIDEO : MODE_PHONE, preDefault\)/u.test(host),
  /const defaults = hostCtx\.get\('agentDefaultModel'\)/u.test(host),
  // 工具那一层的 execute(args, exec) 里不许出现裸的 ctx.get —— 那句话会在运行期抛
  /const preDefault = typeof defaults\?\.currentSelection === 'function'/u.test(host),
], [true, true, true])

check('目录起来那几次重试还在 (服务可能还没注册)', [
  /MODEL_CATALOG_RETRY_MS = \[3_000, 10_000, 30_000\]/u.test(host),
  /modelCatalogState\.tries >= MODEL_CATALOG_RETRY_MS\.length/u.test(host),
  /if \(payload\.ok\) modelCatalogState\.settled = true/u.test(host),
], [true, true, true])

check('取不到目录时写得清为什么 (不是假装没有模型)', [
  /payload\.reason = 'this host has no session controller at this moment/u.test(host),
], [true])

check('退出那一刻就还原 (不等它的下一轮 —— 真机上第一句仍会用视频模型回)', [
  // 记下被切过的那几场, 并且只还 Agent 还活着的那几场 (running 会漏掉"这一轮已经结束"这一幕)
  /modeModelState\.sessions\.add\(sessionId\)/u.test(host),
  /async function restoreModeModelSessions\(ctx\)/u.test(host),
  /item\.agentAvailable === true && item\.origin !== 'subagent' && !item\.parentSessionId/u.test(host),
  /modeModelState\.sessions\.delete\(sessionId\)/u.test(host),
  // 三个触发点: lw_mode 退出那一路 / 语音命令那一路 / 3 s 轮询里看见"已经不是视频模式"
  /if \(!toVideo\) \{\n\s+const restored = await restoreModeModelSessions\(hostCtx\)/u.test(host),
  /if \(switched && command\.mode === MODE_PHONE\) \{\n\s+await restoreModeModelSessions\(hostCtx\)/u.test(host),
  /modeModelState\.sessions\.size > 0 && activeMode\(\) !== MODE_VIDEO/u.test(host),
], Array(7).fill(true))

check('turn/start 那一条仍在 (主机重启后 lastDefault 没了, 靠它兜)', [
  /void applyModeModelToSession\(ctx, sessionId, mode, null\)/u.test(host),
], [true])

/* ── 六、自建端点那一段真的被挂成一条路由 (3.0.3) ─────────────────────── */

check('宿主把自建端点写进 pi-ai 的设置段 (写整张表的一层, 合并语义不吃别人)', [
  /async function ensureVideoRoute\(ctx\)/u.test(host),
  /settings\.update\(ns, patchAt\(path, \{ \[VIDEO_ROUTE\]: profile \}\)\)/u.test(host),
  // 段名与路径从 pi-ai 自己登记的目录里读, 不写死
  /listConfigurableProviders\?\.\(\)/u.test(host),
  /row\.settingsPath\[0\] === 'providers'/u.test(host),
  /function patchAt\(path, value\)/u.test(host),
], [true, true, true, true, true])

check('profile 那五个字段与路由名 (自建路由要求 api + baseURL + 非空 models)', [
  /const VIDEO_ROUTE = 'luwi-video'/u.test(host),
  /const VIDEO_KEY_REF = 'LUWI_VIDEO_API_KEY'/u.test(host),
  /api: endpoint\.api,/u.test(host),
  /baseURL: endpoint\.url,/u.test(host),
  /models: \[\{ id: endpoint\.model \}\]/u.test(host),
  // 端点不要 key 时就不写 apiKeyEnv (写了而取不到会 MISSING_CREDENTIAL)
  /endpoint\.apiKey === '' \? \{\} : \{ apiKeyEnv: VIDEO_KEY_REF \}/u.test(host),
], Array(6).fill(true))

check('API Key 走凭据缝, 不进设置文件', [
  /credentials\.set\(VIDEO_KEY_REF, endpoint\.apiKey\)/u.test(host),
  // profile 里只出现引用名, 明文 key 一个字都不进那一份 patch
  !/baseURL[^\n]*apiKey\b/u.test(host),
  !/apiKey: endpoint\.apiKey/u.test(host),
], [true, true, true])

check('指纹没变不重写, 失败只记不抛 (它挂在 3 s 轮询上)', [
  /if \(fingerprint === videoRouteState\.written\) return/u.test(host),
  /videoRouteState\.lastError = `mounting the custom endpoint failed/u.test(host),
  /void ensureVideoRoute\(ctx\)\.catch\(\(\) => \{\}\)/u.test(host),
], [true, true, true])

check('三处触发都挂着 ensureVideoRoute (轮询 / lw_mode 切换 / 开机)', [
  /void ensureVideoRoute\(ctx\)\.catch\(\(\) => \{\}\)/u.test(host),
  /await ensureVideoRoute\(hostCtx\)/u.test(host),
  /void ensureVideoRoute\(ctx\)\.catch\(\(error\) => \{\n\s+warn\(ctx, `the custom endpoint was not mounted/u.test(host),
], [true, true, true])

check('读数里带着自建端点那五项', [
  /endpoint: \{/u.test(host),
  /url: endpoint\?\.url \?\? '',/u.test(host),
  /model: endpoint\?\.model \?\? '',/u.test(host),
  /keySet: \(endpoint\?\.apiKey \?\? ''\) !== '',/u.test(host),
  /route: endpoint \? VIDEO_ROUTE : '',/u.test(host),
  /lastError: videoRouteState\.lastError,/u.test(host),
], Array(6).fill(true))

check('桥上有 model 那一档, 转发给 LwModel', [
  /"model" -> appContext \{ LwModel\.dispatch\(it, request\) \}/u.test(bridge),
], [true])

check('应用侧写 mode-model 的入口走 ModeModel.setVideo', [
  /ModeModel\.setVideo\(context, "", ""\)/u.test(screen),
  /ModeModel\.setVideo\(context, item\.provider, item\.model\)/u.test(screen),
  /ModeModel\.setVideo\(context, provider, model\)/u.test(screen),
], [true, true, true])

/* ── 收尾 ─────────────────────────────────────────────────────────────── */

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exitCode = failures === 0 ? 0 : 1
