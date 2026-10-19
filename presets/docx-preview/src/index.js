/**
 * Office 文件的行内预览 (2.7.9 第 6 条 T9)
 *
 * 这一包在 T8 里只画一行 `hello docx`, 用来判 `priority: 'extension'` 抢不抢得过上游那个
 * `fallback`; 判过了之后正文在这里补齐。**认的只有 docx 与 pptx** —— xlsx / pdf / md / 图片 /
 * 代码上游自己就能开, 一条都不要碰
 *
 * | 后缀 | 怎么读 | 保真 |
 * | :-- | :-- | :-- |
 * | `.docx` | `docx-preview` 转 HTML, 再进 `sandbox=""` 的 iframe | 分页 / 表格 / 图片 / 页眉页脚 |
 * | `.pptx` | 自己解 OOXML, 每页抽文字与图片 | 只出内容, 不出版式 |
 * | `.doc` / `.ppt` | 这一档不认, 交给上游那行"读取失败" (下一批把它们换成"用系统应用打开") | — |
 *
 * 读字节走 `remote.workspaceFiles.readBytes`; 地址里那一段是**相对会话工作区**的路径, 所以
 * 直接把它交给端点就行 (`sessionId` 取地址里那个, 不是当前页签所在会话)
 *
 * 失败三档必须分开说, 不许假装空白: 读不出来 (宿主给的失败) / 文件太大 / 这份文件读不了
 */

import React from 'react'
import { renderDocx } from './docx.js'
import { renderPptx } from './pptx.js'

/** 类型 id: 正文在 keyed `sidebar.right.pane.tab` 里的 key 就是它 (不是 kind) */
const ID = 'lw-docx-preview'
/** 页面类型名 */
const KIND = 'lw-office'

/** 大小闸: 超过 10 MB 直接说"太大", 不去解 */
const MAX_BYTES = 10 * 1024 * 1024

/** 认这两个后缀 —— 与 `patterns` 两条一一对应 */
const CLAIMED = /\.(docx|pptx)$/i

/** 地址形如 `dsh-resource://file/session/<sid>/<相对工作区的路径>` */
const ADDRESS = 'dsh-resource://file/session/'

/**
 * 把地址拆成会话与路径
 * @param {string} address 页签的地址
 * @returns {{ sessionId: string, path: string } | null} 拆不出来时 null
 */
function parseAddress(address) {
  if (!address.startsWith(ADDRESS)) return null
  const rest = address.slice(ADDRESS.length)
  const slash = rest.indexOf('/')
  if (slash <= 0) return null
  const path = rest
    .slice(slash + 1)
    .split('/')
    .map((segment) => {
      try {
        return decodeURIComponent(segment)
      } catch (error) {
        return segment
      }
    })
    .join('/')
  return { sessionId: rest.slice(0, slash), path }
}

/** 页签上那个名字: 路径最后一段 (解不开就照原样给) */
function titleOf(address) {
  const name = address.slice(address.lastIndexOf('/') + 1)
  if (name === '') return 'Office 文件'
  try {
    return decodeURIComponent(name)
  } catch (error) {
    return name
  }
}

/** 这个类型认什么: 两条后缀, `extension` 档 —— 关键那一档见 T8 的回执 */
const definition = {
  id: ID,
  kind: KIND,
  patterns: ['dsh-resource://file/[\\s\\S]+\\.docx', 'dsh-resource://file/[\\s\\S]+\\.pptx'],
  priority: 'extension',
  canOpen: (address) => typeof address === 'string' && CLAIMED.test(address),
  title: titleOf,
}

/** 进 iframe 的那层基础样式: 两档共用, 只求读得清 */
const BASE_CSS = `
:root { color-scheme: light dark; }
body { margin: 0; padding: 16px; font: 14px/1.65 system-ui, -apple-system, "Noto Sans CJK SC", sans-serif;
  color: #1f2328; background: #fff; overflow-wrap: anywhere; }
@media (prefers-color-scheme: dark) { body { color: #e6e6e6; background: #16181c; } }
.lw-slide { border: 1px solid rgba(128,128,128,.35); border-radius: 10px; padding: 14px 16px; margin: 0 0 14px; }
.lw-slide-title { font-size: 12px; opacity: .55; margin: 0 0 8px; }
.lw-slide p { margin: 0 0 6px; white-space: pre-wrap; }
.lw-slide p:last-child { margin-bottom: 0; }
.lw-slide img { display: block; max-width: 100%; height: auto; margin: 10px 0 0; border-radius: 6px; }
.lw-note { opacity: .7; }
`

/**
 * 让文档按手机宽度重排的那一段, 必须在 docx-preview 自己那份样式**之后**注入
 *
 * 量到的问题 (2026-10-19 模拟器上): docx-preview 按文档里声明的页面尺寸摆 (A4 约 794 px), 而右栏
 * 只有 412 px, 于是标题左边被裁掉一截。它把宽高写在 section 的**行内样式**里, 所以这里只能用
 * `!important` 压过去 (行内样式斗不过带 important 的样式表规则), 顺带把表格与图片收进宽度
 */
const FIT_CSS = `
.lw-docx-wrapper { width: auto !important; padding: 0 !important; }
section.lw-docx { width: auto !important; min-width: 0 !important; min-height: 0 !important;
  padding: 12px 14px !important; margin: 0 0 12px !important; box-shadow: none !important; }
section.lw-docx table { max-width: 100% !important; }
section.lw-docx img { max-width: 100% !important; height: auto !important; }
`

/** 文本进 HTML 前一律转义 (pptx 那一档是我们自己拼字符串) */
function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

/** 把一页页内容拼成一份能进 iframe 的文档 */
function pptxDocument(slides) {
  const body = slides
    .map((slide, index) => {
      const paragraphs = slide.paragraphs.map((line) => `<p>${escapeHtml(line)}</p>`).join('')
      const images = slide.images
        .map((image) => `<img src="data:${image.mime};base64,${image.base64}" alt="">`)
        .join('')
      const empty = paragraphs === '' && images === '' ? '<p class="lw-note">这一页没有文字与图片</p>' : ''
      return `<section class="lw-slide"><p class="lw-slide-title">第 ${String(index + 1)} 页</p>${paragraphs}${images}${empty}</section>`
    })
    .join('')
  return frameDocument(`${BASE_CSS}\n.lw-slide-title { position: sticky; top: -16px; }`, body)
}

/** 一份最小的 iframe 文档: 自带 charset, 两段样式与正文 */
function frameDocument(css, body) {
  return '<!doctype html><html><head><meta charset="utf-8">'
    + `<style>${css.replaceAll('</style', '<\\/style')}</style>`
    + `</head><body>${body}</body></html>`
}

/** 宿主那条 RemoteResult 的失败: 只念它给的 code / message, 不自己编 */
function failureText(error) {
  if (error === undefined || error === null) return '读不出来'
  if (typeof error.message === 'string' && error.message !== '') return error.message
  if (typeof error.code === 'string') return error.code
  return '读不出来'
}

/**
 * 正文: 读字节 -> 按后缀转 -> 塞进 sandbox iframe
 * @param {object} props 槽位组合出来的属性 (`useTabInfo` 与注入面都在里面)
 */
function OfficeBody(props) {
  const info = props.useTabInfo()
  const address = info.tab.navigation.address
  const [state, setState] = React.useState({ phase: 'loading' })
  const [generation, setGeneration] = React.useState(0)

  React.useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    const run = async () => {
      setState({ phase: 'loading' })
      const file = parseAddress(address)
      if (file === null) {
        setState({ phase: 'failed', message: '这个地址不认识, 打不开' })
        return
      }
      try {
        const result = await props.readBytes(file.sessionId, file.path, controller.signal)
        if (cancelled) return
        if (!result.ok) {
          setState({ phase: 'failed', message: '读取失败：' + failureText(result.error) })
          return
        }
        const bytes = result.value.data
        // `eof` 为假说明这一个窗口没读到文件末尾 —— 对预览来说那就是"太大"
        if (result.value.eof === false || bytes.byteLength > MAX_BYTES) {
          setState({ phase: 'failed', message: '文件太大（超过 10 MB），请在电脑上打开' })
          return
        }
        if (/\.docx$/i.test(file.path)) {
          const rendered = await renderDocx(bytes)
          if (!cancelled) {
            // 顺序要紧: 我们那段"按宽度重排"必须在 docx-preview 自己那份之后
            setState({ phase: 'ready', doc: frameDocument(`${BASE_CSS}\n${rendered.css}\n${FIT_CSS}`, rendered.html) })
          }
          return
        }
        const slides = await renderPptx(bytes)
        if (!cancelled) setState({ phase: 'ready', doc: pptxDocument(slides) })
      } catch (error) {
        if (cancelled) return
        const reason = error instanceof Error ? error.message : String(error)
        setState({ phase: 'failed', message: `这份文件读不了：${reason}` })
      }
    }
    void run()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [address, generation])

  if (state.phase === 'loading') {
    return React.createElement('div', { 'data-lw-office-state': 'loading', style: { padding: '16px', opacity: '0.7' } }, '正在读取…')
  }
  if (state.phase === 'failed') {
    return React.createElement(
      'div',
      { 'data-lw-office-state': 'failed', style: { padding: '16px', lineHeight: '1.6' } },
      React.createElement('div', { 'data-lw-office-message': '' }, state.message),
      React.createElement(
        'button',
        {
          type: 'button',
          onClick: () => { setGeneration((value) => value + 1) },
          style: { marginTop: '12px', padding: '6px 14px', borderRadius: '8px', border: '1px solid rgba(128,128,128,.5)', background: 'transparent', color: 'inherit', cursor: 'pointer' },
        },
        '重试',
      ),
    )
  }
  return React.createElement('div', {
    'data-lw-office-state': 'ready',
    style: { height: '100%', width: '100%', display: 'flex', flexDirection: 'column' },
  }, React.createElement('iframe', {
    // 这一层是真边界: 不带 allow-scripts, 里面一个脚本都跑不了, 也不与宿主同源
    sandbox: '',
    srcDoc: state.doc,
    title: 'Office 预览',
    style: { flex: '1 1 auto', width: '100%', minHeight: '0', border: '0', display: 'block', background: 'transparent' },
  }))
}

/**
 * @param {object} ctx 客户端插件 context
 */
export function apply(ctx) {
  ctx.effect(() => ctx.sidebarRightTabs.register(definition), 'lw-docx-preview: office tab type')
  ctx.effect(() => ctx.slots.inject('sidebar.right.pane.tab', () => ctx.slots.register({
    name: 'sidebar.right.pane.tab',
    key: ID,
    inject: () => ({
      /**
       * 读一个工作区文件的全部字节
       * @param {string} addressSessionId 地址里那一场 (不是当前页签所在会话)
       * @param {string} path 相对工作区的路径
       * @param {AbortSignal} signal 页签生命周期
       * @returns {Promise<object>} 宿主那条 RemoteResult
       */
      readBytes: (addressSessionId, path, signal) => ctx.remote.workspaceFiles.readBytes(addressSessionId, path, {}, signal),
    }),
  }, OfficeBody)), 'lw-docx-preview: office body')
}

/** 这两个服务不在就不挂; `remote.workspaceFiles` 是读字节那条端点 */
export const inject = ['slots', 'sidebarRightTabs', 'remote', 'remote.workspaceFiles']
