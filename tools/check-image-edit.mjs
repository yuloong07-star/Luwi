/**
 * p 图那一条的判据表 (2.5.0 批次 9)
 *
 * 这一条有四段接缝, 每一段**漂开了都不会当场报错**, 只会在设备上变成"看起来能用":
 *
 *   1. **图从哪儿来**: `lw_image op=ref` 印出来的那几个字段就是生图插件 `edit_image` 的
 *      `source_image` 认的那几个 (`attachment_id` / `media_type` / `bytes` / `width` / `height`)。
 *      少一个或者换个拼法, 模型照抄过去会被插件拒, 而拒的那句话说的是"引用无效", 与真正的原因
 *      (这里印错了) 差着一层
 *   2. **两个 op 两侧一致**: 工具那侧 (`enum: ['ref','album']`) 与应用那侧 (`LwGallery` 只接
 *      `album` 走的那条 `gallery` 桥) 是两份实现 —— 漂开的样子是"工具说明里有一个 op 其实没实现"
 *   3. **随包的那两样都在**: 生图插件在 `tools/pack-host.mjs` 里被取进树, 而 `PluginRows` 要把那一行
 *      写进 **profile 自己的 patch** (不是命令行覆盖层 —— 写在那一层上面, dsh 的设置面板就改不了它);
 *      `photo-edit` 技能要在 `build.gradle.kts` 的随包表与 `LwSeed` 的名单里各出现一次
 *   4. **相册目录只有一份口径**: 技能正文里写的那个目录名要与 `LwGallery.ALBUM` 一样
 *
 * 不需要设备, 也不需要 host: 全是拿真源码对着核 (与 `check-quick-commands.mjs` 同一个办法)。
 *
 * 用法: node tools/check-image-edit.mjs
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const plugin = await read('host-plugin/index.mjs')
const packer = await read('tools/pack-host.mjs')
const overlay = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginOverlay.kt')
const gallery = await read('app/src/main/java/io/github/yuloong07star/luwi/tool/LwGallery.kt')
const bridge = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const gradle = await read('app/build.gradle.kts')
const seed = await read('app/src/main/java/io/github/yuloong07star/luwi/host/LwSeed.kt')
const skill = await read('skills/photo-edit/SKILL.md')

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

/** build.gradle.kts 里的 `val <name> = listOf("a", "b")` 那张表 */
function kotlinList(source, name) {
  const match = new RegExp(`val ${name} = listOf\\(([^)]*)\\)`).exec(source)
  if (match === null) return []
  return [...match[1].matchAll(/"([^"]*)"/g)].map((one) => one[1])
}

/* ── 一、工具那一侧 ────────────────────────────────────────────────────── */

check(
  'lw_image 在插件里注册了',
  plugin.includes("name: 'lw_image'"),
  true,
)

/** 工具那两个 op: `enum` 那一行是模型看到的清单 */
const ops = /name: 'lw_image'[\s\S]*?enum: \['ref', 'album'\]/.test(plugin)
check('lw_image 的 op 是 ref 与 album', ops, true)

check(
  '两个 op 都有实现 (execute 里各一条分支)',
  [plugin.includes('stagePictureForImageTools(args, exec)'), plugin.includes('fileFinishedPicture(args)')],
  [true, true],
)

/**
 * 印出来的字段就是 `edit_image` 要的那几个 (snake_case)
 *
 * 两侧都是源码, 所以这里量的是"我们印出来的那一组名字" —— 插件那一侧的 schema 在另一个人手里, 而
 * 那正是要核的地方: 这五个名字与它 `imageRefSchema` 的 `properties` 逐个相同
 */
const projection = /function projectImageRef\(ref\) \{([\s\S]*?)\n\}/.exec(plugin)
const projectedKeys = projection === null
  ? []
  : [...projection[1].matchAll(/^\s{4}([a-z_]+):/gm)].map((one) => one[1])
check(
  'ref 那条链印的是 edit_image 认的那五个字段',
  projectedKeys,
  ['attachment_id', 'media_type', 'bytes', 'width', 'height'],
)

check(
  'op=ref 走的还是"拍全分辨率那一份" (fullPath), 不是给模型看的那份缩图',
  plugin.includes('String(shot?.fullPath ?? \'\').trim() || String(shot?.path ?? \'\').trim()'),
  true,
)

check(
  '成图那条链通过 gallery 这条桥交给应用 (几兆的图不过那条一行 JSON 的短连接)',
  plugin.includes("await call('gallery', { source: staged, name, open: args?.open !== false })"),
  true,
)

/* ── 二、应用那一侧 ────────────────────────────────────────────────────── */

check(
  '桥上有 gallery 这一条分支',
  bridge.includes('"gallery" -> appContext { LwGallery.dispatch(it, request) }'),
  true,
)

check(
  'LwGallery 走的是 MediaStore 且分了 IS_PENDING 两半 (不依赖所有文件访问权限)',
  [
    gallery.includes('MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)'),
    gallery.includes('put(MediaStore.MediaColumns.IS_PENDING, 1)'),
    gallery.includes('put(MediaStore.MediaColumns.IS_PENDING, 0)'),
    gallery.includes('Environment.DIRECTORY_PICTURES'),
  ],
  [true, true, true, true],
)

const album = /private const val ALBUM = "([^"]+)"/.exec(gallery)
check(
  '相册里那个目录名与技能正文里写的一样',
  [album?.[1], skill.includes(`Pictures/${album?.[1]}`)],
  ['Luwi', true],
)

/* ── 三、随包的那两样 ──────────────────────────────────────────────────── */

const packed = /const imagePlugin = \{ name: '([^']+)', version: '([^']+)' \}/.exec(packer)
check(
  '要取进树的插件是 @dickpy/dsh-imagegen',
  packed?.[1],
  '@dickpy/dsh-imagegen',
)
check(
  '那棵树里裁掉的是"只有开发时才用"的那三样 (docs / src 里除 templates / lib 的 map)',
  [
    packer.includes("rmSync(join(imageRoot, 'docs')"),
    packer.includes("if (name !== 'templates') rmSync(join(imageSources, name)"),
    packer.includes("if (name.endsWith('.map')) rmSync(join(imageLib, name)"),
  ],
  [true, true, true],
)

check(
  '上游那三处 `~/.dsh` 的数据目录被打包这一步改到 $DSH_HOME (数目对不上就让构建失败)',
  [
    packer.includes('const workspaceHome = \'path.join(homedir(), ".dsh", "dsh-imagegen")\''),
    packer.includes('if (rewritten !== 3)'),
    packer.includes('writeFileSync(imageLibEntry, imageBundle.split(workspaceHome).join(knownHome))'),
  ],
  [true, true, true],
)

/** 包名与挂它的那一行必须指同一个地方: 前者决定"树里有没有", 后者决定"挂不挂" */
const rows = await read('app/src/main/java/io/github/yuloong07star/luwi/host/PluginRows.kt')
const owned = /"imagegen" to "([^"]+)"/.exec(rows)
check(
  'PluginRows 按同一个入口挂了这一行 (id imagegen)',
  owned?.[1],
  'node_modules/@dickpy/dsh-imagegen/lib/index.js',
)
// **这一行不许回到 overlay 那两张表里** (2026-10-16 主人报的"生图保存失败"): 命令行的 patch 是最上面
// 那一层, 挂在它上面时 dsh 的设置面板存什么都命中不了那一行, config-editor 会回一句
// "overridden by a home patch or command-line overlay"。面板要能改的行必须由 profile patch 自己插入
check(
  '那一行不在覆盖层那两张表里 (只在 PluginRows 里)',
  (overlay.match(/Row\("imagegen"/g) ?? []).length,
  0,
)
// **第二件同样能把它压死的事**: 盘上那一行的形态要对上 Loader 眼里那个 —— dsh 读 patch 时会把裸绝对
// 路径锚成 `file://` URL, 而设置面板追加的 config 行抄的是锚过之后那个名字, 且它落盘前的自检不
// anchoring; 盘上留裸路径, 那条 config 行就会被按 "patch: name mismatch" 丢掉, 面板照样回同一句错
// (2026-10-16 在设备上拿真代码复现到的)
check(
  'profile patch 那一段写的是 file:// 形态 (即 Loader 眼里那个名字)',
  [
    rows.includes('fun fileUrlOf('),
    rows.includes('append("file://")'),
    rows.includes('ensuredRow(patch, id, target.absolutePath)'),
    rows.includes('fileUrlOf(entryPath)'),
  ],
  [true, true, true, true],
)
check(
  '旧安装里那个裸路径每启一次 host 会被对成 file:// 形态 (只改 name 那一行)',
  [
    rows.includes('fun namedRow(patch: String, rowId: String, name: String)'),
    rows.includes('if (hasInsertRow(patch, rowId)) namedRow(patch, rowId, name) else withRow(patch, rowId, name)'),
  ],
  [true, true],
)
check(
  'profile patch 那一段的形状对得上 (insert 子行 + 加引号的 file:// 名字)',
  [
    rows.includes('"- insert:"'),
    rows.includes('"    - id: $rowId"'),
    rows.includes('"      name: \'$name\'"'),
  ],
  [true, true, true],
)

/** 那一行只由 PluginRows 那一段写出来: id 行 + 名字行 */
check(
  'rowBlock 就是那两行 (id 与它自己那个入口)',
  [rows.includes('- insert:'), rows.includes("name: '$name'")],
  [true, true],
)

check(
  'photo-edit 技能在 build.gradle.kts 的随包表里',
  kotlinList(gradle, 'shippedSkills').includes('photo-edit'),
  true,
)
check(
  'photo-edit 技能在 LwSeed 的名单里 (两份必须对得上)',
  /private val skills = listOf\([^)]*"photo-edit"[^)]*\)/.test(seed),
  true,
)

check(
  '技能正文点到了那三个名字 (lw_image / edit_image / 相册)',
  [skill.includes('lw_image'), skill.includes('edit_image'), skill.includes('op=album')],
  [true, true, true],
)

check(
  '技能点名了"没配渠道时去设置里配", 而不是自己拼 HTTP 请求',
  skill.includes('设置 → 生图配置'),
  true,
)

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
