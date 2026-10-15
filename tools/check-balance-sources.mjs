/**
 * 「看余额」那张余额源表 (2026-10-17 主人: 「能查看主流 llm 的余额」) 的判据
 *
 * 这一批的答案由宿主那一侧写 (数只有它拿得到), 而它漂开的样子**一声不响**: 认错一家就把别人家的钱
 * 念给主人听, 少读一个字段就变成空串, 类型不对就变成 ¥0.00。所以这份脚本查四件事:
 *
 *   1. **认门**: baseURL 的 host 优先、其次 route 名 —— 认不出来就是 null (那时应用说「不支持」)
 *   2. **各家那一份响应的读法**: 金额取哪个字段、单位怎么换、额度怎么算剩余
 *   3. **读不出来必须是 null**: 少字段 / 类型不对 / 空对象, 一个都不许变成 0
 *   4. **profile 的两档路径**: pi-ai 那几家是 `['providers']` 再按 route 取一层, 而 dsh 自家那几家
 *      (`llm-deepseek`) 登记的**空路径** —— 那一段本身就是 profile (DeepSeek 官方那把 key 就住那儿)
 *
 * 纯函数表那一节与 `check-mode-model.mjs` 同一个办法: 从宿主插件里把函数抽出来真的跑一遍, 两边别漂
 *
 * 跑法: node tools/check-balance-sources.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const host = await readFile(new URL('host-plugin/index.mjs', root), 'utf8')

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

/* ── 一、把纯函数抽出来 (照 check-mode-model.mjs 那个办法) ───────────────── */

const pick = (start, end) => {
  const from = host.indexOf(start)
  const to = host.indexOf(end, from)
  if (from < 0 || to < 0) throw new Error(`宿主插件里找不到这一段: ${start}`)
  return host.slice(from, to)
}
const source = [
  pick('/** 金额那一档: 定点两位', '/**\n * 哪些提供方有"余额"这件事可查'),
  pick('const BALANCE_SOURCES = [', '/** 一条地址的主机名'),
  pick('function hostOf(url) {', '/** 这一次查哪一家'),
  pick('function balanceSourceFor(provider, baseURL) {', '/**\n * 某条路由的 profile'),
  pick('function providerProfile(ctx, provider) {', '/** 给人看的名字'),
].join('\n')
const { BALANCE_SOURCES, balanceSourceFor, providerProfile } = new Function(
  `${source}; return { BALANCE_SOURCES, balanceSourceFor, providerProfile }`,
)()

/** 各家的解析函数 (按表里那个展示名取) */
const walletOf = (label) => BALANCE_SOURCES.find((row) => row.label === label).parse

/* ── 二、认门: host 优先, 其次 route 名 ─────────────────────────────────── */

/** [route, baseURL, 期望认到的展示名 或 null] */
const ROUTES = [
  ['deepseek-official', '', 'DeepSeek'],
  ['deepseek-official', 'https://api.deepseek.com', 'DeepSeek'],
  ['deepseek', 'https://api.deepseek.com/v1', 'DeepSeek'],
  ['DeepSeek-Official', '', 'DeepSeek'],
  ['moonshot', '', 'Moonshot'],
  ['moonshot-intl', 'https://api.moonshot.ai/v1', 'Moonshot (intl)'],
  ['openrouter', 'https://openrouter.ai/api/v1', 'OpenRouter'],
  ['zai', 'https://api.z.ai/api/paas/v4', 'Z.ai GLM'],
  ['zhipu', '', 'Zhipu GLM'],
  ['minimax', '', 'MiniMax'],
  ['stepfun', '', 'StepFun'],
  ['novita', '', 'Novita'],
  ['ppio', '', 'PPIO'],
  ['luwi-video', 'https://gateway.example.com/v1', null],
  ['xiaomi', 'https://api.xiaomi.example/v1', null],
  ['some-route', 'https://api.moonshot.cn/v1', 'Moonshot'],
]
check(
  '认门: 表里那几家各认各的, 认不出来的给 null',
  ROUTES.map(([provider, baseURL]) => balanceSourceFor(provider, baseURL)?.label ?? null),
  ROUTES.map(([, , want]) => want),
)
check(
  '半截地址不算 host 命中 (自建端点写的可能是半截地址, 那只能靠 route 名)',
  balanceSourceFor('whatever', 'api.deepseek.com/user/balance'),
  null,
)

/* ── 三、各家那一份响应的读法 (真响应样本) ─────────────────────────────── */

/** [展示名, 一份响应, 期望读出来的那份钱] */
const WALLETS = [
  ['DeepSeek', {
    balance_infos: [
      { currency: 'USD', total_balance: '1.00', granted_balance: '0.00' },
      { currency: 'CNY', total_balance: '12.34', granted_balance: '5.00', topped_up_balance: '7.34' },
    ],
  }, { kind: 'money', currency: 'CNY', value: '12.34', bonus: '5.00' }],
  ['Moonshot', { code: 0, data: { available_balance: 8.5, voucher_balance: 1.2 } },
    { kind: 'money', currency: 'CNY', value: '8.5', bonus: '1.2' }],
  ['Moonshot (intl)', { data: { balance: 3 } },
    { kind: 'money', currency: 'CNY', value: '3', bonus: '' }],
  ['OpenRouter', { data: { total_credits: 10, total_usage: 3.5 } },
    { kind: 'money', currency: 'USD', value: '6.50' }],
  ['StepFun', { balance: '7.77' }, { kind: 'money', currency: 'CNY', value: '7.77' }],
  ['Novita', { availableBalance: 123456 }, { kind: 'money', currency: 'USD', value: '12.35' }],
  ['PPIO', { availableBalance: '9.90' }, { kind: 'money', currency: 'CNY', value: '9.90' }],
  ['Zhipu GLM', { data: { limits: [{ TOKENS_LIMIT: { percentage: 38 } }, { TOKENS_LIMIT: { percentage: 12 } }] } },
    { kind: 'quota', percent: 62 }],
  ['MiniMax', { model_remains: [{ current_interval_remaining_percent: 40, current_weekly_remaining_percent: 75 }] },
    { kind: 'quota', percent: 40 }],
]
check(
  '各家那份响应各读各的字段 (金额带币种, 额度算剩余, Novita 那 1e-4 美元要换算)',
  WALLETS.map(([label, body]) => walletOf(label)(body)),
  WALLETS.map(([, , want]) => want),
)

/** [展示名, 一份读不出来的响应] */
const EMPTY = [
  ['DeepSeek', {}],
  ['Moonshot', {}],
  ['Moonshot (intl)', { data: {} }],
  ['OpenRouter', { data: { total_credits: 'x', total_usage: 1 } }],
  ['StepFun', {}],
  ['Novita', { availableBalance: 'not-a-number' }],
  ['PPIO', {}],
  ['Zhipu GLM', { data: {} }],
  ['MiniMax', {}],
]
check(
  '读不出来的响应一律 null (绝不编一个 0 出来)',
  EMPTY.map(([label, body]) => walletOf(label)(body)),
  Array(EMPTY.length).fill(null),
)

/* ── 四、profile 的两档路径 ─────────────────────────────────────────────── */

const ctxOf = (entries, sections) => ({
  get: (name) => name === 'llm'
    ? { listConfigurableProviders: () => entries }
    : name === 'settings' ? { describe: () => sections } : undefined,
})
const pi = ctxOf(
  [{ provider: 'moonshot', displayName: 'Moonshot', settingsNs: 'llm-pi-ai', settingsPath: ['providers'] }],
  [{ ns: 'llm-pi-ai', value: { providers: { moonshot: { baseURL: 'https://api.moonshot.cn/v1', apiKeyEnv: 'MOONSHOT_API_KEY' } } } }],
)
check(
  'profile: pi-ai 那几家要再按 route 取一层',
  providerProfile(pi, 'moonshot'),
  { baseURL: 'https://api.moonshot.cn/v1', apiKeyEnv: 'MOONSHOT_API_KEY' },
)
const deepseek = ctxOf(
  [{ provider: 'deepseek-official', displayName: 'DeepSeek', settingsNs: 'llm-deepseek', settingsPath: [] }],
  [{ ns: 'llm-deepseek', value: { apiKeyEnv: 'DEEPSEEK_API_KEY' } }],
)
check(
  'profile: dsh 自家那几家是空路径, 那一段本身就是 profile (DeepSeek 官方那把 key 就住这儿)',
  providerProfile(deepseek, 'deepseek-official'),
  { apiKeyEnv: 'DEEPSEEK_API_KEY' },
)
check('profile: 没登记的路由就是 null', providerProfile(pi, 'unknown'), null)

console.log(failures === 0 ? `\n${checks} / ${checks} 条通过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
