// lib/qr-svg.mjs — 文本 → SVG 二维码 (Luwi 加, 2026-10-20)
//
// 只给 PC 面板的"配对手机"那张卡用: 载荷里可能有仓库口令, 所以二维码在**宿主**这一侧画好,
// 面板只拿到一张 SVG, 拿不到载荷原文 (要复制文本是另一个显式动作)
//
// 编码器是 vendored 的 qrcode-generator (MIT, 见 lib/vendor/qrcode.mjs) —— 不引构建步骤,
// 也不加运行时依赖

import qrcode from './vendor/qrcode.mjs'

/** 画出来的默认参数 (面板里就是一个 ~260px 的正方形) */
export const QR_SVG_DEFAULTS = Object.freeze({
  scale: 4,
  margin: 4,
  level: 'M',
})

/** 载荷上限 (字节)。再长就超出二维码的可读密度, 让人改用"复制配对文本" */
export const QR_MAX_TEXT_BYTES = 1200

/**
 * 文本 → SVG 字符串 (深色模块一条 path, 白底一块 rect; 无脚本、无外部引用)。
 * @param {string} text - 要编码的文本。
 * @param {{scale?: number, margin?: number, level?: 'L'|'M'|'Q'|'H'}} [options] - 参数。
 * @returns {string} SVG 文本。
 * @throws {Error} 文本为空 / 太长 / 编码失败。
 */
export function qrSvg(text, options = {}) {
  const value = typeof text === 'string' ? text : String(text ?? '')
  if (value === '') throw new Error('cannot draw a QR code for an empty text')
  const bytes = Buffer.byteLength(value, 'utf8')
  if (bytes > QR_MAX_TEXT_BYTES) throw new Error(`the pairing payload is ${bytes} bytes, over the ${QR_MAX_TEXT_BYTES} byte limit`)
  const scale = Number.isInteger(options.scale) && options.scale > 0 ? options.scale : QR_SVG_DEFAULTS.scale
  const margin = Number.isInteger(options.margin) && options.margin >= 0 ? options.margin : QR_SVG_DEFAULTS.margin
  const level = ['L', 'M', 'Q', 'H'].includes(options.level) ? options.level : QR_SVG_DEFAULTS.level
  const qr = qrcode(0, level)
  qr.addData(value)
  qr.make()
  const count = qr.getModuleCount()
  const size = (count + margin * 2) * scale
  const parts = []
  for (let row = 0; row < count; row += 1) {
    for (let column = 0; column < count; column += 1) {
      if (!qr.isDark(row, column)) continue
      const x = (column + margin) * scale
      const y = (row + margin) * scale
      parts.push(`M${x} ${y}h${scale}v${scale}h-${scale}z`)
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges" role="img" aria-label="Luwi sync pairing code"><rect width="${size}" height="${size}" fill="#ffffff"/><path d="${parts.join('')}" fill="#000000"/></svg>`
}

/**
 * SVG → `data:` URL (面板的 `<img>` 直接用; 不拼 HTML, 也就没有注入面)。
 * @param {string} svg - `qrSvg` 的产物。
 * @returns {string} data URL。
 */
export function svgDataUrl(svg) {
  return `data:image/svg+xml;base64,${Buffer.from(String(svg), 'utf8').toString('base64')}`
}
