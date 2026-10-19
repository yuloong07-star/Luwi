/**
 * pptx 的"能看就行"那一档 (2.7.9 第 6 条 T9b)
 *
 * **这不是渲染器, 是抽取器**: 每页出它自己的文字 (按段落顺序) 与本页引用的图片, 不出版式。
 * 主人 2026-10-19 定的口径是"哪个简单就选哪个, 能看就行", 而现成那个前端 pptx 库虽然接进来只要
 * 几行, 但它是**闭源 dist** (1.33 MB, 还拖着 echarts), 坏了没法修 —— 所以这里自己解 OOXML:
 * pptx 就是一只 zip, 页面文字在 `p:sldIdLst` 指到的 `ppt/slides/slideN.xml` 里, 图片在同目录的
 * `_rels` 里, 两样都读得出来
 *
 * 三条上限, 免得一份坏文件把右栏拖死: 最多 200 页 / 每页最多 20 张图 / 单张图 8 MB
 */

import JSZip from 'jszip'

/** 页数上限: 超过就当"读不了", 不猜后面的 */
const MAX_SLIDES = 200
/** 每页图片上限 */
const MAX_IMAGES_PER_SLIDE = 20
/** 单张图片上限 (base64 之后还要再大 1/3) */
const MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** 后缀到 MIME: `ppt/media/` 里常见的几种, 认不出就当 png */
const MIME = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  bmp: 'image/bmp',
  webp: 'image/webp',
  svg: 'image/svg+xml',
  tif: 'image/tiff',
  tiff: 'image/tiff',
  emf: 'image/emf',
  wmf: 'image/wmf',
}

/**
 * 把一段相对的 zip 路径按 base 所在目录解析出来 (`..` 与 `.` 都处理)
 * @param {string} base zip 里的绝对路径 (作为基准)
 * @param {string} target 相对于 base 目录的路径
 * @returns {string} zip 里的绝对路径
 */
function resolvePath(base, target) {
  const parts = base.split('/').slice(0, -1)
  for (const segment of target.split('/')) {
    if (segment === '..') parts.pop()
    else if (segment !== '.' && segment !== '') parts.push(segment)
  }
  return parts.join('/')
}

/** 从一段 XML 里按文档顺序收 `a:t` 的文字, 一个 `a:p` 一行 */
function paragraphsOf(xml, parser) {
  const doc = parser.parseFromString(xml, 'application/xml')
  const out = []
  for (const paragraph of doc.getElementsByTagName('a:p')) {
    const line = Array.from(paragraph.getElementsByTagName('a:t'), (node) => node.textContent ?? '').join('')
    const text = line.trim()
    if (text !== '') out.push(text)
  }
  return out
}

/** 把 rels 那份 XML 读成 rId → { target, type, external } */
function relationshipsOf(xml, parser) {
  const doc = parser.parseFromString(xml, 'application/xml')
  const out = new Map()
  for (const node of doc.getElementsByTagName('Relationship')) {
    const id = node.getAttribute('Id')
    const target = node.getAttribute('Target')
    if (id === null || target === null) continue
    out.set(id, {
      target,
      type: node.getAttribute('Type') ?? '',
      external: node.getAttribute('TargetMode') === 'External',
    })
  }
  return out
}

/**
 * 按 `p:sldIdLst` 的顺序给出每一页的路径, 读不到那份清单时退回按文件名数字排
 * @param {JSZip} zip 整只 pptx
 * @param {DOMParser} parser XML 解析器
 * @returns {Promise<string[]>} 形如 `ppt/slides/slide1.xml`
 */
async function slidePaths(zip, parser) {
  const presentation = zip.file('ppt/presentation.xml')
  const relsFile = zip.file('ppt/_rels/presentation.xml.rels')
  if (presentation !== null && relsFile !== null) {
    const rels = relationshipsOf(await relsFile.async('string'), parser)
    const doc = parser.parseFromString(await presentation.async('string'), 'application/xml')
    const ordered = []
    for (const node of doc.getElementsByTagName('p:sldId')) {
      const id = node.getAttribute('r:id')
      const relationship = id === null ? undefined : rels.get(id)
      if (relationship === undefined || relationship.external) continue
      ordered.push(resolvePath('ppt/presentation.xml', relationship.target))
    }
    if (ordered.length > 0) return ordered
  }
  return Object.keys(zip.files)
    .filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name))
    .sort((left, right) => Number(left.replace(/\D+/g, '')) - Number(right.replace(/\D+/g, '')))
}

/**
 * 读出一页幻灯片引用的图片 (只认包内文件, 外链图不放行 —— 这一档不联网)
 * @param {JSZip} zip 整只 pptx
 * @param {string} slidePath 这一页的路径
 * @param {DOMParser} parser XML 解析器
 * @returns {Promise<{ mime: string, base64: string }[]>} 可以直接进 `<img src>`
 */
async function imagesOf(zip, slidePath, parser) {
  const relsPath = resolvePath(slidePath, '_rels/' + slidePath.split('/').pop() + '.rels')
  const relsFile = zip.file(relsPath)
  if (relsFile === null) return []
  const rels = relationshipsOf(await relsFile.async('string'), parser)
  const out = []
  for (const relationship of rels.values()) {
    if (out.length >= MAX_IMAGES_PER_SLIDE) break
    // 只认图片关系: 外链、以及音频/视频/超链接那些一律不放行
    if (relationship.external || !relationship.type.endsWith('/image')) continue
    const path = resolvePath(slidePath, relationship.target)
    const file = zip.file(path)
    if (file === null) continue
    const bytes = await file.async('uint8array')
    if (bytes.length > MAX_IMAGE_BYTES) continue
    const extension = path.split('.').pop()?.toLowerCase() ?? ''
    out.push({ mime: MIME[extension] ?? 'image/png', base64: await file.async('base64') })
  }
  return out
}

/**
 * 抽出一份 pptx 的每页内容
 * @param {Uint8Array} bytes 原生字节
 * @returns {Promise<{ paragraphs: string[], images: { mime: string, base64: string }[] }[]>} 按页顺序
 * @throws 当它不是一只 zip, 或页数超过上限时
 */
export async function renderPptx(bytes) {
  const zip = await JSZip.loadAsync(bytes)
  const parser = new DOMParser()
  const paths = await slidePaths(zip, parser)
  if (paths.length > MAX_SLIDES) throw new Error(`too many slides (${String(paths.length)} > ${String(MAX_SLIDES)})`)
  const slides = []
  for (const path of paths) {
    const file = zip.file(path)
    if (file === null) continue
    slides.push({
      paragraphs: paragraphsOf(await file.async('string'), parser),
      images: await imagesOf(zip, path, parser),
    })
  }
  return slides
}
