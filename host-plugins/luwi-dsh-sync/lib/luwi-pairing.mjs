// lib/luwi-pairing.mjs — 扫码配对的载荷 (Luwi 加, 2026-10-20)
//
// 主人口径: PC 面板填一次同步仓库、出二维码; 手机在 Luwi 设置里扫码, 一次把仓库 / 分支 /
// 对端地址 / 对端口令 / 配对 id 写进 `$DSH_HOME/lw/session-sync.json`。仓库地址**只在 PC 上填**。
//
// 三条纪律:
//   · 载荷只经 `POST /luwi-sync/op {op:'pair'}` 的应答回给**同一台机器的面板**, 不进快照、
//     不落日志; 面板拿到的是一张画好的 SVG, 要文本得显式点"复制配对文本"
//   · 校验在**两侧各做一遍** (这里是宿主侧; 手机侧是 app 的 sync/PairPayload.kt), 键名与
//     错误分档由 tools/check-sync-pairing.mjs 钉住
//   · scheme 与版本写死在 constants.mjs, 与 App 那一份逐字一致; 不认的版本一律拒, 不猜

import { PAIR_MAX_BYTES, PAIR_SCHEME, PAIR_VERSION, PEER_LIMITS } from './constants.mjs'
import { branchProblem, remoteProblem } from './luwi-configure.mjs'

/** 载荷文本的前缀 (二维码里就是这一串) */
export const PAIR_PREFIX = `${PAIR_SCHEME}://pair?d=`

/** 配对 id 的形态 (PC 生成的 uuid; 手机只存不生成) */
const PAIR_ID_RE = /^[0-9A-Za-z][0-9A-Za-z-]{7,127}$/u

/** http(s) 地址的粗判 (与 lib/luwi-peer.mjs 的 peer.url 同一口径) */
const HTTP_URL_RE = /^https?:\/\/[^\s]+$/iu

/** base64url 字母表 */
const BASE64URL_RE = /^[A-Za-z0-9_-]+$/u

/**
 * 从远端地址里取主机名 (只给面板显示用例; 不碰凭据)。
 * @param {string} remote - 仓库地址。
 * @returns {string} 主机名 (取不到就空串)。
 */
export function remoteHostOf(remote) {
  const text = typeof remote === 'string' ? remote.trim() : ''
  if (text === '') return ''
  const match = /^(?:[A-Za-z][A-Za-z0-9+.-]*:\/\/)?(?:[^@/]+@)?([^/:?#]+)/u.exec(text)
  return match === null ? '' : match[1]
}

/**
 * 校验一份载荷 (宿主与手机两侧同一套规则; 错误分档进 error)。
 * @param {unknown} value - 解析后的 JSON。
 * @returns {{ok: true, payload: object}|{ok: false, error: string}} 结果。
 */
export function validatePairingPayload(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'the pairing payload is not a JSON object' }
  }
  const raw = /** @type {Record<string, unknown>} */ (value)
  if (raw.v !== PAIR_VERSION) {
    return { ok: false, error: `the pairing payload version is ${JSON.stringify(raw.v)}, this build only understands ${PAIR_VERSION}` }
  }
  const id = typeof raw.id === 'string' ? raw.id.trim() : ''
  if (!PAIR_ID_RE.test(id)) return { ok: false, error: 'the pairing id is missing or malformed' }
  const remote = typeof raw.remote === 'string' ? raw.remote.trim() : ''
  if (remote === '') return { ok: false, error: 'the repository address is empty' }
  const remoteIssue = remoteProblem(remote)
  if (remoteIssue !== null) return { ok: false, error: remoteIssue }
  const branch = typeof raw.branch === 'string' ? raw.branch.trim() : ''
  const branchIssue = branchProblem(branch)
  if (branchIssue !== null) return { ok: false, error: branchIssue }
  const peerUrl = typeof raw.peerUrl === 'string' ? raw.peerUrl.trim() : ''
  if (peerUrl !== '' && !HTTP_URL_RE.test(peerUrl)) {
    return { ok: false, error: 'peerUrl must be an http(s) URL (empty = the phone keeps its current one)' }
  }
  const peerToken = typeof raw.peerToken === 'string' ? raw.peerToken.trim() : ''
  if (peerToken !== '' && (peerToken.length < PEER_LIMITS.MIN_TOKEN_LENGTH || peerToken.length > PEER_LIMITS.MAX_TOKEN_LENGTH)) {
    return { ok: false, error: `peerToken must be ${PEER_LIMITS.MIN_TOKEN_LENGTH}..${PEER_LIMITS.MAX_TOKEN_LENGTH} characters long` }
  }
  if (/\s/u.test(peerToken)) return { ok: false, error: 'peerToken must not contain whitespace' }
  const from = typeof raw.from === 'string' ? raw.from.trim() : ''
  if (from.length > 64) return { ok: false, error: 'the sender device name is longer than 64 characters' }
  const at = Number.isFinite(raw.at) ? Number(raw.at) : undefined
  return {
    ok: true,
    payload: {
      v: PAIR_VERSION,
      id,
      remote,
      branch: branch === '' ? 'main' : branch,
      peerUrl,
      peerToken,
      from,
      ...(at !== undefined ? { at } : {}),
    },
  }
}

/**
 * 从 PC 这一侧的配置拼一份载荷 (配对 id 由调用方给 —— 它在写配置那一步生成)。
 * @param {object} input - 取值。
 * @param {string} input.id - 配对 id (uuid)。
 * @param {string} input.remote - 仓库地址。
 * @param {string} [input.branch] - 分支 (空 = main)。
 * @param {string} [input.peerUrl] - 手机要访问的本机地址。
 * @param {string} [input.peerToken] - 对端口令。
 * @param {string} [input.from] - 本机设备名。
 * @param {number} [input.at] - 生成时刻 (ms)。
 * @returns {object} 载荷。
 */
export function pairingPayloadFromConfig(input) {
  const result = validatePairingPayload({
    v: PAIR_VERSION,
    id: input.id,
    remote: input.remote,
    branch: input.branch ?? 'main',
    peerUrl: input.peerUrl ?? '',
    peerToken: input.peerToken ?? '',
    from: input.from ?? '',
    at: Number.isFinite(input.at) ? input.at : Date.now(),
  })
  if (result.ok !== true) throw new Error(result.error)
  return result.payload
}

/**
 * 载荷 → 二维码里的那串文本。
 * @param {object} payload - 已校验的载荷。
 * @returns {string} `luwi-sync://pair?d=…`。
 */
export function encodePairing(payload) {
  const checked = validatePairingPayload(payload)
  if (checked.ok !== true) throw new Error(checked.error)
  const bytes = Buffer.from(JSON.stringify(checked.payload), 'utf8')
  if (bytes.length > PAIR_MAX_BYTES) {
    throw new Error(`the pairing payload is ${bytes.length} bytes, over the ${PAIR_MAX_BYTES} byte limit`)
  }
  return `${PAIR_PREFIX}${bytes.toString('base64url')}`
}

/**
 * 扫码/粘贴得到的文本 → 载荷。
 * 只认 `luwi-sync://pair?d=…` 这一种形态; 别的二维码回一句能读的话, 不猜。
 * @param {string} text - 扫到/粘到的原文。
 * @returns {{ok: true, payload: object}|{ok: false, error: string}} 结果。
 */
export function decodePairing(text) {
  const raw = typeof text === 'string' ? text.trim() : ''
  if (raw === '') return { ok: false, error: 'the pairing text is empty' }
  if (!raw.startsWith(PAIR_PREFIX)) {
    return { ok: false, error: 'this is not a Luwi sync pairing code' }
  }
  const encoded = raw.slice(PAIR_PREFIX.length)
  if (!BASE64URL_RE.test(encoded)) return { ok: false, error: 'the pairing code is corrupted (not base64url)' }
  if (encoded.length > Math.ceil(PAIR_MAX_BYTES * 4 / 3) + 8) {
    return { ok: false, error: 'the pairing code is longer than this build accepts' }
  }
  let parsed
  try {
    const json = Buffer.from(encoded, 'base64url').toString('utf8')
    parsed = JSON.parse(json)
  } catch {
    return { ok: false, error: 'the pairing code is corrupted (not JSON)' }
  }
  return validatePairingPayload(parsed)
}

/**
 * 面板显示用的一句话 (不含仓库口令)。
 * @param {object} payload - 已校验的载荷。
 * @returns {{repoHost: string, branch: string, from: string, peerHost: string, hasPeerToken: boolean}} 摘要。
 */
export function pairingSummary(payload) {
  return {
    repoHost: remoteHostOf(payload.remote),
    branch: payload.branch,
    from: payload.from,
    peerHost: remoteHostOf(payload.peerUrl),
    hasPeerToken: typeof payload.peerToken === 'string' && payload.peerToken !== '',
  }
}
