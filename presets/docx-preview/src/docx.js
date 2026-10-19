/**
 * docx 正文 (2.7.9 第 6 条 T9a)
 *
 * 转换交给 `docx-preview` (Apache-2.0, 打包进来约 171 KB): 它按 OOXML 出分页、表格、图片、
 * 页眉页脚与样式, 比 mammoth 那种"只出语义 HTML"的保真高, 而且体积还小一半
 *
 * 两步走, 一步都不省:
 *
 * 1. 在**游离**的容器里渲染 (不进实时 DOM), 样式灌进一个游离 `<style>`
 * 2. 把两段文本拼成 `srcdoc` 塞进 **`sandbox=""` 的 iframe** —— 那一层是真边界: 不带
 *    `allow-scripts` 时里面一个脚本都跑不了, 也不与宿主同源, 于是文档里的样式与脚本碰不到界面
 *
 * 不引 DOMPurify: 进 iframe 的是 `innerHTML` 文本, 而 iframe 的 sandbox 已经挡住了脚本执行;
 * 再加一层净化只会在 `data:` 图片这类地方制造"图被洗掉"的新毛病
 */

import { renderAsync } from 'docx-preview'

/** docx 的 MIME: `Blob` 的类型只在下载时有意义, 但给对了不亏 */
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

/**
 * 把 docx 字节转成一段可以进 iframe 的文档
 * @param {Uint8Array} bytes 原生字节
 * @returns {Promise<{ css: string, html: string }>} 样式与正文, 两段都由 docx-preview 生成
 */
export async function renderDocx(bytes) {
  const holder = document.createElement('div')
  const style = document.createElement('style')
  const blob = new Blob([bytes], { type: DOCX_MIME })
  await renderAsync(blob, holder, style, {
    className: 'lw-docx',
    inWrapper: true,
    // 图片内联成 base64: iframe 是 opaque origin, 拿不到宿主的 blob: URL
    useBase64URL: true,
    breakPages: true,
    renderHeaders: true,
    renderFooters: true,
    renderFootnotes: true,
    renderEndnotes: true,
  })
  return { css: style.textContent ?? '', html: holder.innerHTML }
}
