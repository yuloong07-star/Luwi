// lib/luwi-remote.mjs — 仓库地址与访问令牌 (Luwi 加, 2026-10-20)
//
// 主人 2026-10-20 的要求: 令牌单独填一格, 不要塞进地址里。于是配置面分成两个键:
//   · `remote`      —— 干净的地址 (`https://github.com/you/private.git`, 可以带用户名)
//   · `remoteToken` —— 访问令牌 (私有仓库的 PAT)
//
// 真正交给 git 的那一份由 [composeRemoteWithToken] 拼出来: `https://<user>:<token>@host/path`。
// 手机侧没有 credential helper (git 跑在 Linux 沙盒里), 所以**二维码里带的仍然是拼好的那一份** ——
// 手机配置里的 `remote` 与以前一样是带凭据的地址, 这一层只影响 PC 怎么存
//
// 三条规则:
//   · 地址不是 http(s) (ssh / git / 本地路径) 时**原样返回** —— 令牌只对 HTTPS basic auth 有意义
//   · 地址里已经有口令 (`https://u:p@host/...`) 时原样返回 —— 不要把人写下的东西改掉
//   · 没写用户名时用 `x-access-token` (GitHub 的 PAT / Actions token 都是这个)
//     Gitee 这类要用户名的, 在地址里写 `https://<用户名>@gitee.com/...` 就行

/**
 * 地址 + 令牌 → 真正给 git 用的地址。
 * @param {string} remote - 配置里的干净地址。
 * @param {string} token - 访问令牌 (空 = 不动)。
 * @returns {string} 拼好的地址 (原样或带 userinfo)。
 */
export function composeRemoteWithToken(remote, token) {
  const url = typeof remote === 'string' ? remote.trim() : ''
  const secret = typeof token === 'string' ? token.trim() : ''
  if (url === '' || secret === '') return url
  if (!/^https?:\/\//iu.test(url)) return url
  const match = /^(https?:\/\/)([^/]*)(\/.*)?$/iu.exec(url)
  if (match === null) return url
  const authority = match[2]
  const at = authority.lastIndexOf('@')
  const userInfo = at >= 0 ? authority.slice(0, at) : ''
  const host = at >= 0 ? authority.slice(at + 1) : authority
  if (host === '') return url
  const colon = userInfo.indexOf(':')
  if (colon >= 0 && userInfo.slice(colon + 1) !== '') return url
  const user = colon >= 0 ? userInfo.slice(0, colon) : userInfo
  const name = user === '' ? 'x-access-token' : user
  return `${match[1]}${encodeURIComponent(name)}:${encodeURIComponent(secret)}@${host}${match[3] ?? ''}`
}

/**
 * 从地址里拿掉口令 (只留用户名) —— 给"看当前配置"那一页的展示用, 绝不回显令牌。
 * @param {string} remote - 可能是拼好的地址。
 * @returns {string} 抹掉口令的地址。
 */
export function stripRemotePassword(remote) {
  const url = typeof remote === 'string' ? remote : ''
  return url.replace(/^([a-z][a-z0-9+.-]*:\/\/)([^/@:]+):[^/@]*@/iu, '$1$2@')
}
