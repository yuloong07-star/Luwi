// test/qr-svg.test.mjs — 文本 → SVG 二维码

import test from 'node:test'
import assert from 'node:assert/strict'
import { QR_MAX_TEXT_BYTES, qrSvg, svgDataUrl } from '../lib/qr-svg.mjs'

test('draws a square SVG with modules', () => {
  const svg = qrSvg('luwi-sync://pair?d=abc')
  assert.match(svg, /^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg"/u)
  assert.match(svg, /<path d="M/u)
  const size = /width="(\d+)"/u.exec(svg)
  assert.ok(size !== null && Number(size[1]) > 21)
})

test('the same text draws the same SVG (deterministic)', () => {
  assert.equal(qrSvg('hello'), qrSvg('hello'))
  assert.notEqual(qrSvg('hello'), qrSvg('hello2'))
})

test('scale and margin change the canvas size', () => {
  const small = qrSvg('hello', { scale: 2, margin: 1 })
  const big = qrSvg('hello', { scale: 4, margin: 3 })
  const width = (svg) => Number(/width="(\d+)"/u.exec(svg)[1])
  assert.ok(width(big) > width(small))
})

test('refuses an empty text and an over-long one', () => {
  assert.throws(() => qrSvg(''), /empty/u)
  assert.throws(() => qrSvg('x'.repeat(QR_MAX_TEXT_BYTES + 1)), /over the/u)
})

test('data url is a plain image URL, not HTML', () => {
  const url = svgDataUrl(qrSvg('x'))
  assert.ok(url.startsWith('data:image/svg+xml;base64,'))
  assert.equal(url.includes('<script'), false)
})
