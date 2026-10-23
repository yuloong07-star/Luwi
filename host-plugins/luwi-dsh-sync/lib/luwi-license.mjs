// lib/luwi-license.mjs — Luwi 账号与权益 (本地验签 + 状态机 + 账号接口客户端)
//
// 干什么: 把官网签出来的权益凭证 (`<payload>.<sig>`, ES256) 在本地验一遍, 给出五档状态:
//
//   unset     没有 sync-license.json (从没登录过)
//   active    在有效期内
//   grace     过期了但还在 7 天宽限里 (能用, 面板提示续费)
//   expired   宽限也过了 (P2P 不可用, git 照旧)
//   invalid   文件坏了 / 验签不过 / 时钟不对 (提示重新登录)
//
// 为什么本地验签: 官网挂了、用户断网时, 已缓存的那份凭证照旧能用 —— 这一条是"付了钱却同步不了"
// 的反面。签名私钥只在服务端, 公钥这一份是公开的
//
// 纪律: 这个文件里不许出现 device_token 的日志与快照字段; token 只落在 sync-license.json 里

import { createPublicKey, verify as verifySignature } from 'node:crypto'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

/** 落在 $DSH_HOME/lw/ 下的文件名 (与 session-sync.json 同一层) */
export const LICENSE_FILE_NAME = 'sync-license.json'

/** 签名件的版本 (与官网 functions-lib/license-key.js 的 LICENSE_VERSION 对齐) */
export const LICENSE_VERSION = 1

/** 离线宽限: 到期之后还能用 7 天 (只提示, 不拦) */
export const GRACE_MS = 7 * 24 * 60 * 60 * 1000

/** 时钟容差: issuedAt 比本机现在晚 10 分钟以上就判 invalid (防改系统时间白嫖) */
export const CLOCK_SKEW_MS = 10 * 60 * 1000

/** 权益刷新间隔 (续费 / 签到之后最多这么多小时自动看到) */
export const REFRESH_MS = 6 * 60 * 60 * 1000

/**
 * 官网默认地址。`LUWI_SYNC_ACCOUNT_BASE` 可覆盖 (本地 wrangler pages dev 联调用)
 */
export const ACCOUNT_BASE_DEFAULT = 'https://luwi.dpdns.org'

/**
 * 验签公钥 (SPKI base64, ES256)
 *
 * 与 `D:\apk\.lwtmp\luwi-license-key.txt` 里那把私钥配套; 官网也可以用 `GET /api/account/pubkey`
 * 反查。**换私钥 = 换这一行 + 所有已登录用户重新登录**
 */
export const DEFAULT_PUBLIC_KEY =
  'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZcp3hBJO+JqfAI+b14+t4XPqvQaPK8uKt+zBew6gdGJ2YiXENkVEo/bSHeJ2Y8ytjv9E0CkhHlq7+/ZsogtKGQ=='

/** 状态机认得的那几档 */
export const LICENSE_STATES = Object.freeze(['unset', 'active', 'grace', 'expired', 'invalid'])

const MAX_PAYLOAD_BYTES = 2048
const MAX_ENTITLEMENT_CHARS = 4096

/**
 * base64url → Buffer (签名件里没有填充)
 * @param {string} text
 * @returns {Buffer}
 */
function b64uToBuffer(text) {
  return Buffer.from(String(text || ''), 'base64url')
}

/**
 * 拆开签名件: 两段都在、长度都合法才算拆得开 (验签另算)
 * @param {string} text
 * @returns {{payloadText: string, payload: object, signature: Buffer}|null}
 */
export function decodeEntitlement(text) {
  const value = typeof text === 'string' ? text.trim() : ''
  if (value === '' || value.length > MAX_ENTITLEMENT_CHARS) return null
  const at = value.indexOf('.')
  if (at <= 0 || at === value.length - 1) return null
  const payloadBytes = b64uToBuffer(value.slice(0, at))
  const signature = b64uToBuffer(value.slice(at + 1))
  if (payloadBytes.length === 0 || payloadBytes.length > MAX_PAYLOAD_BYTES) return null
  if (signature.length !== 64) return null
  const payloadText = payloadBytes.toString('utf8')
  let payload
  try {
    payload = JSON.parse(payloadText)
  } catch {
    return null
  }
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return null
  return { payloadText, payload, signature }
}

/**
 * claims 形状校验 (与官网 normalizeClaims 同一套规则)
 * @param {object} payload
 * @returns {{v: number, account: number, login: string, plan: string, expiresAt: number, issuedAt: number}|null}
 */
export function claimsOf(payload) {
  if (!payload || typeof payload !== 'object') return null
  if (Number(payload.v) !== LICENSE_VERSION) return null
  const account = Number(payload.account)
  if (!Number.isInteger(account) || account <= 0) return null
  const login = typeof payload.login === 'string' ? payload.login : ''
  if (login.length > 64 || (login !== '' && !/^[A-Za-z0-9][A-Za-z0-9-]*$/u.test(login))) return null
  const plan = String(payload.plan || '')
  if (plan === '') return null
  const expiresAt = Number(payload.expiresAt)
  const issuedAt = Number(payload.issuedAt)
  if (!Number.isFinite(expiresAt) || expiresAt <= 0) return null
  if (!Number.isFinite(issuedAt) || issuedAt <= 0) return null
  return { v: LICENSE_VERSION, account, login, plan, expiresAt, issuedAt }
}

/**
 * 验一份权益签名件
 * @param {string} text - `<payload>.<sig>`
 * @param {string} [publicKeyB64] - SPKI base64
 * @returns {object|null} claims (验过才算数)
 */
export function verifyEntitlement(text, publicKeyB64 = DEFAULT_PUBLIC_KEY) {
  const decoded = decodeEntitlement(text)
  if (decoded === null) return null
  let key
  try {
    key = createPublicKey({
      key: Buffer.from(String(publicKeyB64), 'base64'),
      format: 'der',
      type: 'spki',
    })
  } catch {
    return null
  }
  let ok = false
  try {
    ok = verifySignature(
      'sha256',
      Buffer.from(decoded.payloadText, 'utf8'),
      // WebCrypto 的 ECDSA 输出是 r||s 定长 64 字节, 不是 DER
      { key, dsaEncoding: 'ieee-p1363' },
      decoded.signature,
    )
  } catch {
    return null
  }
  if (ok !== true) return null
  return claimsOf(decoded.payload)
}

/**
 * 从落地文件那份 JSON 里认出一个账号 (不验签, 只读形状)
 * @param {object|null} stored
 * @returns {{entitlement: string, token: string, login: string, email: string, base: string}|null}
 */
export function describeStored(stored) {
  if (!stored || typeof stored !== 'object') return null
  const entitlement = typeof stored.entitlement === 'string' ? stored.entitlement : ''
  if (entitlement === '') return null
  return {
    entitlement,
    token: typeof stored.token === 'string' ? stored.token : '',
    login: typeof stored.login === 'string' ? stored.login : '',
    email: typeof stored.email === 'string' ? stored.email : '',
    base: typeof stored.base === 'string' ? stored.base : '',
  }
}

/**
 * 状态机: 落地文件 → 六档状态与给人看的那几项
 *
 * @param {{stored: object|null, now?: number, publicKey?: string}} input
 * @returns {{state: string, login: string, email: string, accountId: number|null,
 *            plan: string|null, expiresAt: number|null, graceUntil: number|null,
 *            issuedAt: number|null, reason?: string, hasToken: boolean}}
 */
export function statusOf({ stored = null, now = Date.now(), publicKey = DEFAULT_PUBLIC_KEY } = {}) {
  const empty = {
    state: 'unset',
    login: '',
    email: '',
    accountId: null,
    plan: null,
    expiresAt: null,
    graceUntil: null,
    issuedAt: null,
    hasToken: false,
  }
  const described = describeStored(stored)
  if (described === null) {
    if (stored && typeof stored === 'object') {
      return { ...empty, state: 'invalid', reason: 'no entitlement in the file' }
    }
    return empty
  }
  const claims = verifyEntitlement(described.entitlement, publicKey)
  if (claims === null) {
    return { ...empty, state: 'invalid', reason: 'the entitlement does not verify', login: described.login, email: described.email }
  }
  if (claims.issuedAt > now + CLOCK_SKEW_MS) {
    return { ...empty, state: 'invalid', reason: 'the entitlement was issued in the future', login: claims.login, email: described.email }
  }
  const base = {
    login: claims.login || described.login,
    email: described.email,
    accountId: claims.account,
    plan: claims.plan,
    expiresAt: claims.expiresAt,
    graceUntil: claims.expiresAt + GRACE_MS,
    issuedAt: claims.issuedAt,
    hasToken: described.token !== '',
  }
  // 没买到任何天数时, 官网签的是 expiresAt === issuedAt —— 那是「已登录但没订阅」, 不是 active
  if (claims.expiresAt <= claims.issuedAt) return { ...base, state: 'expired', reason: 'no active subscription' }
  if (now < claims.expiresAt) return { ...base, state: 'active' }
  if (now < base.graceUntil) return { ...base, state: 'grace' }
  return { ...base, state: 'expired' }
}

/**
 * 能不能用 P2P: active 与 grace 都算能用 (宽限只提示不拦)
 * @param {object} status - statusOf 的产物
 * @returns {boolean}
 */
export function planAllowsP2p(status) {
  return status !== null && (status.state === 'active' || status.state === 'grace')
}

/**
 * 生成配对码要不要先登录: unset 与 invalid 都要 (expired 不要 —— 过期的人仍要能重新配对去续费)
 * @param {object} status
 * @returns {boolean}
 */
export function loginRequiredForPairing(status) {
  return status === null || status.state === 'unset' || status.state === 'invalid'
}

/** 权益文件路径 */
export function licensePathOf(dshHome) {
  return join(String(dshHome || ''), 'lw', LICENSE_FILE_NAME)
}

/**
 * 读落地文件 (读不到 / JSON 坏了都回 null, 由状态机判 unset/invalid)
 * @param {string} file
 * @returns {Promise<object|null>}
 */
export async function readLicenseFile(file) {
  let text
  try {
    text = await readFile(file, 'utf8')
  } catch {
    return null
  }
  try {
    const parsed = JSON.parse(text)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : { broken: true }
  } catch {
    return { broken: true }
  }
}

/**
 * 写落地文件 (带 token, 尽量 0600 —— Windows 上 mode 基本无效, 但别的平台有意义)
 * @param {string} file
 * @param {object} data
 * @returns {Promise<void>}
 */
export async function writeLicenseFile(file, data) {
  await mkdir(dirname(file), { recursive: true })
  await writeFile(file, `${JSON.stringify(data, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
}

/** 删落地文件 (退出登录) */
export async function clearLicenseFile(file) {
  await rm(file, { force: true })
}

/**
 * 账号接口的地址 (环境变量可覆盖, 末尾不留斜杠)
 * @param {object} [env]
 * @returns {string}
 */
export function accountBaseOf(env = process.env) {
  const given = env && typeof env.LUWI_SYNC_ACCOUNT_BASE === 'string' ? env.LUWI_SYNC_ACCOUNT_BASE.trim() : ''
  return (given === '' ? ACCOUNT_BASE_DEFAULT : given).replace(/\/+$/u, '')
}

/**
 * 生成插件这一侧的一次性 state (32 字节 hex)
 * @returns {string}
 */
export function newLoginState() {
  // 用 URL 安全的字母表; 与官网 sanitizeState 的 [A-Za-z0-9_-]{16,128} 对上
  const bytes = new Uint8Array(24)
  globalThis.crypto.getRandomValues(bytes)
  let text = ''
  for (const byte of bytes) text += byte.toString(16).padStart(2, '0')
  return text
}

/**
 * 登录页地址 (浏览器打开它; 登录完成后宿主轮询)
 * @param {string} base
 * @param {string} state
 * @returns {string}
 */
export function loginUrlOf(base, state) {
  const url = new URL(`${base}/login`)
  url.searchParams.set('ls', state)
  return url.toString()
}

/**
 * 调一次账号接口 (只认 JSON; 网络错误与超时都折成一个带 reason 的结果, 不抛)
 * @param {string} base
 * @param {string} path
 * @param {{method?: string, token?: string, timeoutMs?: number, fetchImpl?: Function}} [options]
 * @returns {Promise<{status: number, body: object|null, error?: string}>}
 */
export async function accountRequest(base, path, options = {}) {
  const method = typeof options.method === 'string' ? options.method : 'GET'
  const fetchImpl = typeof options.fetchImpl === 'function' ? options.fetchImpl : globalThis.fetch
  const timeoutMs = Number.isFinite(options.timeoutMs) ? options.timeoutMs : 10_000
  const url = `${String(base).replace(/\/+$/u, '')}${path}`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const headers = { Accept: 'application/json' }
    if (typeof options.token === 'string' && options.token !== '') headers.Authorization = `Bearer ${options.token}`
    const response = await fetchImpl(url, { method, headers, signal: controller.signal })
    const text = await response.text()
    let body = null
    try {
      body = JSON.parse(text)
    } catch {
      body = null
    }
    if (body === null) return { status: response.status, body: null, error: `not JSON (HTTP ${response.status})` }
    return { status: response.status, body }
  } catch (error) {
    const reason = error && error.name === 'AbortError' ? `timed out after ${timeoutMs}ms` : String((error && error.message) || error)
    return { status: 0, body: null, error: `cannot reach ${url}: ${reason}` }
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 从 poll / refresh 的应答里拼出要落盘的那份 JSON
 * @param {{account?: object, entitlement?: string, token?: string, expiresAt?: number, issuedAt?: number, paidUntil?: number|null}} body
 * @param {{base: string, now?: number}} options
 * @returns {object|null}
 */
export function storedFromPoll(body, { base, now = Date.now() }) {
  if (!body || typeof body !== 'object') return null
  const entitlement = typeof body.entitlement === 'string' ? body.entitlement : ''
  const token = typeof body.token === 'string' ? body.token : ''
  if (entitlement === '' || token === '') return null
  const account = body.account && typeof body.account === 'object' ? body.account : {}
  return {
    v: 1,
    entitlement,
    token,
    login: typeof account.login === 'string' ? account.login : '',
    email: typeof account.email === 'string' ? account.email : '',
    base: String(base || ''),
    fetchedAt: now,
    expiresAt: Number.isFinite(Number(body.expiresAt)) ? Number(body.expiresAt) : null,
    paidUntil: body.paidUntil === null || body.paidUntil === undefined ? null : Number(body.paidUntil),
  }
}
