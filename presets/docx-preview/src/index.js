/**
 * Office 文件的行内预览 (2.7.9 第 6 条 T9)
 *
 * 这一包在 T8 里只画一行 `hello docx`, 用来判 `priority: 'extension'` 抢不抢得过上游那个
 * `fallback`; 判过了之后正文在这里补齐。**认的只有 docx 与 pptx** —— xlsx / pdf / md / 图片 /
 * 代码上游自己就能开, 一条都不要碰
 *
 * | 后缀 | 怎么读 | 保真 |
 * | :-- | :-- | :-- |
 * | `.docx` | `docx-preview` 转 HTML | 分页 / 表格 / 图片 / 页眉页脚 |
 * | `.pptx` | 自己解 OOXML, 每页抽文字与图片 | 只出内容, 不出版式 |
 * | `.doc` / `.ppt` | 这一档不认, 交给上游那行"读取失败" (T9c 换成"用系统应用打开") | — |
 *
 * **隔离与脚本**: 正文进 **shadow root**, 从不进宿主 DOM; 进之前过一遍 `dompurify` 去掉脚本与
 * `on*` 之类的事件属性。不用 iframe 了 —— 双指手势要落在我们自己的元素上才收得到 (见下一条)
 *
 * **缩放 (2026-10-19 主人问"不支持双指放大吗")**: 整页双指放大是 WebView 自己的 (会把标签栏一起
 * 放大), 而文档要的是"只放大这一页"。所以预览容器 `touch-action: pan-x pan-y` (不让浏览器接管
 * 双指), 两指捏合改的是内层那份文档的 `zoom`; 双击在 1x / 2x 之间切换, 放大了右下角有一颗
 * `1:1` 复位。单指仍然是原生滚动
 *
 * 失败三档必须分开说, 不许假装空白: 读不出来 (宿主给的失败) / 文件太大 / 这份文件读不了
 */

import React from 'react'
import DOMPurify from 'dompurify'
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

/** 缩放上下限: 再小看不清, 再大只剩一个字 */
const MIN_ZOOM = 0.8
const MAX_ZOOM = 4

/** 双击的判定窗口 */
const DOUBLE_TAP_MS = 320
const DOUBLE_TAP_PX = 28

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

/** 进 shadow root 的那层基础样式: 两档共用, 只求读得清 */
const BASE_CSS = `
:root, :host { color-scheme: light dark; }
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
/* docx-preview 把页面容器摆成 flex + align-items:center; 页面比容器宽时它**两边一起溢出** ——
   左边被裁掉, 就是"放大后左边看不见" (2026-10-19 量到 section.left = -112)。改成块级, 页面
   在放得下时用 margin:auto 居中 (块级布局里超宽时 auto 外侧距算 0, 只往右边溢出, 不会再裁左边) */
.lw-docx-wrapper { display: block !important; width: auto !important; padding: 0 !important; }
section.lw-docx { width: auto !important; min-width: 0 !important; min-height: 0 !important;
  padding: 12px 14px !important; margin: 0 auto 12px !important; box-shadow: none !important; }
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

/** 把一页页内容拼成正文 (样式在 BASE_CSS 里) */
function pptxBody(slides) {
  return slides
    .map((slide, index) => {
      const paragraphs = slide.paragraphs.map((line) => `<p>${escapeHtml(line)}</p>`).join('')
      const images = slide.images
        .map((image) => `<img src="data:${image.mime};base64,${image.base64}" alt="">`)
        .join('')
      const empty = paragraphs === '' && images === '' ? '<p class="lw-note">这一页没有文字与图片</p>' : ''
      return `<section class="lw-slide"><p class="lw-slide-title">第 ${String(index + 1)} 页</p>${paragraphs}${images}${empty}</section>`
    })
    .join('')
}

/**
 * 进 shadow root 之前洗一遍: 去掉脚本、表单与事件属性
 *
 * shadow root 里 `innerHTML` 插进来的 `<script>` 本来就不会执行, 但 `onerror=` 这类**属性**会 ——
 * 所以这里要洗; 图片走 `data:` 内联, 得显式放行, 否则整份文档的图都没了
 * @param {string} html docx-preview 或我们自己拼出来的正文
 * @returns {string} 可以注入的 HTML
 */
function sanitize(html) {
  return DOMPurify.sanitize(html, {
    ADD_DATA_URI_TAGS: ['img'],
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'link'],
  })
}

/** 宿主那条 RemoteResult 的失败: 只念它给的 code / message, 不自己编 */
function failureText(error) {
  if (error === undefined || error === null) return '读不出来'
  if (typeof error.message === 'string' && error.message !== '') return error.message
  if (typeof error.code === 'string') return error.code
  return '读不出来'
}

/** 两指之间的距离 */
function spread(touches) {
  const [first, second] = touches
  return Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY)
}

/** 两指的中点 */
function middle(touches) {
  const [first, second] = touches
  return { x: (first.clientX + second.clientX) / 2, y: (first.clientY + second.clientY) / 2 }
}

/**
 * 正文: 读字节 -> 按后缀转 -> 洗一遍 -> 进 shadow root, 两指捏合改文档自己的 zoom
 * @param {object} props 槽位组合出来的属性 (`useTabInfo` 与注入面都在里面)
 */
function OfficeBody(props) {
  const info = props.useTabInfo()
  const address = info.tab.navigation.address
  const [state, setState] = React.useState({ phase: 'loading' })
  const [generation, setGeneration] = React.useState(0)
  const [zoom, setZoom] = React.useState(1)
  const zoomRef = React.useRef(1)
  const containerRef = React.useRef(null)
  const hostRef = React.useRef(null)

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
            setState({ phase: 'ready', style: `${BASE_CSS}\n${rendered.css}\n${FIT_CSS}`, body: sanitize(rendered.html) })
          }
          return
        }
        const slides = await renderPptx(bytes)
        if (!cancelled) setState({ phase: 'ready', style: BASE_CSS, body: sanitize(pptxBody(slides)) })
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

  // 文档进 shadow root: 样式与正文都关在里面, 宿主那一页一点不受影响
  React.useEffect(() => {
    const host = hostRef.current
    if (state.phase !== 'ready' || host === null) return
    const root = host.shadowRoot ?? host.attachShadow({ mode: 'open' })
    root.innerHTML = `<style>${state.style}</style>${state.body}`
  }, [state])

  // 两指捏合与双击: 落在容器自己身上 (不再隔着 iframe 的边界), 所以收得到
  React.useEffect(() => {
    const container = containerRef.current
    if (state.phase !== 'ready' || container === null) return

    /**
     * 改一份文档的缩放 —— **视窗式**: 版面一点不动, 放大的是整页 (文字、表格、图片一起),
     * 捏合/双击那一处留在指下, 然后靠拖动看其余部分
     *
     * 两条算式是配套的, 少一条就成"重排":
     * 1. 宿主那份文档 `zoom: z` **并且** `width: (100·z)%` (在 zoom 里百分比按容器宽/z 折算,
     *    于是改写之后布局宽仍是容器宽 —— 版面不变, 只是画得更大)
     * 2. 滚动静止位置按 `布局点 × 新倍率 - 屏幕点` 摆, 于是指下那一点不跳
     * @param {number} next 目标倍率
     * @param {{ x: number, y: number } | undefined} anchor 屏幕坐标上的锚点
     */
    const apply = (next, anchor) => {
      const host = hostRef.current
      if (host === null) return
      const clamped = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next))
      const ratio = clamped / zoomRef.current
      if (Math.abs(ratio - 1) < 0.001) return
      const box = container.getBoundingClientRect()
      const cx = (anchor === undefined ? box.width / 2 : anchor.x - box.left)
      const cy = (anchor === undefined ? box.height / 2 : anchor.y - box.top)
      const ox = container.scrollLeft + cx
      const oy = container.scrollTop + cy
      zoomRef.current = clamped
      host.style.zoom = String(clamped)
      host.style.width = `${String(100 * clamped)}%`
      // 读一次 scrollTop 之前浏览器必须先把新布局算出来, 之后再把同一点摆回指下
      container.scrollLeft = ox * ratio - cx
      container.scrollTop = oy * ratio - cy
      setZoom(clamped)
    }

    let pinch = null
    let lastTap = null

    const onStart = (event) => {
      if (event.touches.length === 2) {
        pinch = { distance: spread(event.touches), zoom: zoomRef.current }
        lastTap = null
      }
    }
    const onMove = (event) => {
      if (pinch === null || event.touches.length !== 2) return
      // 双指这一路不给浏览器: 它会把整页 (连标签栏) 一起放大
      event.preventDefault()
      const distance = spread(event.touches)
      if (distance <= 0) return
      apply(pinch.zoom * (distance / pinch.distance), middle(event.touches))
    }
    const onEnd = (event) => {
      if (event.touches.length >= 2) return
      pinch = null
      // 双击: 两下都在 (大约) 同一处, 且间隔够短
      if (event.touches.length > 0 || event.changedTouches.length !== 1) return
      const touch = event.changedTouches[0]
      const now = Date.now()
      const samePlace = lastTap !== null
        && Math.hypot(touch.clientX - lastTap.x, touch.clientY - lastTap.y) < DOUBLE_TAP_PX
      if (samePlace && now - lastTap.at < DOUBLE_TAP_MS) {
        apply(zoomRef.current > 1.05 ? 1 : 2, { x: touch.clientX, y: touch.clientY })
        lastTap = null
        return
      }
      lastTap = { x: touch.clientX, y: touch.clientY, at: now }
    }

    container.addEventListener('touchstart', onStart, { passive: true })
    container.addEventListener('touchmove', onMove, { passive: false })
    container.addEventListener('touchend', onEnd, { passive: true })
    return () => {
      container.removeEventListener('touchstart', onStart)
      container.removeEventListener('touchmove', onMove)
      container.removeEventListener('touchend', onEnd)
    }
  }, [state.phase])

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
  return React.createElement(
    'div',
    {
      'data-lw-office-state': 'ready',
      'data-lw-office-zoom': String(zoom),
      style: { position: 'relative', height: '100%', width: '100%' },
    },
    React.createElement('div', {
      ref: containerRef,
      // 单指拖动照旧是原生滚动; **双指不交给浏览器** (否则整页连标签栏一起放大)
      // 视窗式放大: 版面按容器宽排 (所以 width 跟着倍率走), `zoom` 只把它画大
      style: { position: 'absolute', inset: '0', overflow: 'auto', touchAction: 'pan-x pan-y', WebkitOverflowScrolling: 'touch' },
    }, React.createElement('div', { ref: hostRef, style: { zoom: String(zoom), width: `${String(100 * zoom)}%` } })),
    zoom > 1.05
      ? React.createElement('button', {
        type: 'button',
        'data-lw-office-reset': '',
        onClick: () => {
          zoomRef.current = 1
          if (hostRef.current !== null) hostRef.current.style.zoom = '1'
          setZoom(1)
        },
        style: { position: 'absolute', right: '10px', bottom: '10px', padding: '4px 10px', borderRadius: '999px', border: '1px solid rgba(128,128,128,.5)', background: 'rgba(128,128,128,.18)', color: 'inherit', font: '12px/1.4 system-ui, sans-serif', cursor: 'pointer' },
      }, '1:1')
      : null,
  )
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
