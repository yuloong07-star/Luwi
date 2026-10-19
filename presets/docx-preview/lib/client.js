/**
 * docx 试探 (2.7.9 第 6 条 T8) — **这一份的全部作用就是一句判断**
 *
 * 上游的右栏预览只有一个 `text` 类型, 它用 `priority: 'fallback'` 认下每一个
 * `dsh-resource://file/...` 地址, 再到自己那套按扩展名的登记表里挑渲染器; docx 那一格要**宿主**把
 * 文件转成 PDF (`office-to-pdf`), 而随包的树里没有转换引擎 (`libreoffice-kit-wasm` 只声明
 * darwin / win32), 于是在设备上点开 `.docx` 只会看到一行失败提示
 *
 * 这里要验的只有一件事: **一个 `priority: 'extension'` 的类型抢不抢得过那个 `fallback`**,
 * 抢得过这条自写渲染器的路就走得通 (T9 才去接 mammoth), 抢不过就别再往下花时间, 转施工单 §2.4
 * 里那条退路 (宿主转 HTML)
 *
 * 所以正文**故意只有一行 `hello docx`**: 它不读字节、不校验文件、也不碰 manifest, 出得来就说明
 * 类型已经被认下, 出不来就说明还被上游那个 fallback 挡着。判据在模拟器上做: 右栏点开一个 `.docx`,
 * 看到的是这张卡而不是上游那句失败提示
 *
 * 这一份不是普通 ESM: dsh 的客户端模块按 `window.__ModuleLoader__.load({ id, factory })` 装载,
 * 写成 `export ...` 会被当成经典脚本解析 (`SyntaxError: Unexpected token 'export'`) —— 照
 * `presets/ui-fix/lib/client.js` 与各 client 包 `lib/client.js` 的形状写
 */
window.__ModuleLoader__.load({
  id: 'lw-docx-preview',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports

    var React = require('react')

    /**
     * 类型 id 就是正文在 `sidebar.right.pane.tab` 里那个 key
     *
     * 上游那个 seat 的文档写的是"以定义自己的 `id` 派发" (`contract/slots.ts`), 不是 `kind` ——
     * 所以 `id` 与 `kind` 各管一件事: `id` 找正文, `kind` 是这扇页的类型名
     */
    var ID = 'lw-docx-preview'
    var KIND = 'lw-docx'

    /** 地址的最后一段, 与上游那个 basename 同一条口径 (解不开就照原样给) */
    function titleOf(address) {
      var name = address.slice(address.lastIndexOf('/') + 1)
      if (name === '') return 'docx'
      try {
        return decodeURIComponent(name)
      } catch (error) {
        return name
      }
    }

    /**
     * 这个类型认什么: 只看后缀, 不问字节
     *
     * `priority: 'extension'` 是这一整步的关键 —— 上游 `text` 那个类型是 `fallback`, 而三档是
     * `extension` > `builtin` > `fallback`; 同一地址两个候选时按档位排, 不看谁先注册
     *
     * **模式末尾不能写 `$`**: 这里的模式交给 picomatch, 它认的是 glob 而不是正则 —— `$` 在那里面是
     * 一个字面字符, 于是 `…\.docx$` 要求地址以 `$` 结尾, 一个地址都命中不了 (2026-10-19 T8 在模拟器上
     * 就是这么空转了一轮: 类型注册上了, 候选里却没有它, 右栏照旧落到上游那个 fallback)
     * picomatch 默认整串锚定, 所以省掉 `$` 之后 "以 .docx 结尾" 这条意思一点没少
     */
    var definition = {
      id: ID,
      kind: KIND,
      patterns: ['dsh-resource://file/[\\s\\S]+\\.docx'],
      priority: 'extension',
      // 地址已经被上面那条后缀管住, 这里不再加第二个条件 (加错了会静默退回上游那一档)
      canOpen: function (address) {
        return typeof address === 'string' && address.indexOf('.docx') !== -1
      },
      title: titleOf,
    }

    /**
     * 正文: 一行字, 加一个给判据用的 `data-` 属性
     *
     * **不声明 `inject`**: 这一档不需要会话、字节、地址里的任何东西, 只要它能被渲染出来就说明
     * 类型认下了。T9 接 mammoth 时才会加 `remote.workspaceFiles.readBytes` 那一类东西
     */
    function HelloDocx() {
      return React.createElement(
        'div',
        {
          'data-lw-docx-hello': '',
          style: {
            padding: '16px',
            fontSize: '14px',
            lineHeight: '1.5',
            opacity: '0.85',
          },
        },
        'hello docx',
      )
    }

    function apply(ctx) {
      ctx.effect(function () {
        return ctx.sidebarRightTabs.register(definition)
      }, 'lw-docx-preview: tab type')
      ctx.effect(function () {
        return ctx.slots.inject('sidebar.right.pane.tab', function () {
          return ctx.slots.register({ name: 'sidebar.right.pane.tab', key: ID }, HelloDocx)
        })
      }, 'lw-docx-preview: hello body')
    }

    exports.apply = apply
    exports.inject = ['slots', 'sidebarRightTabs']
    return module.exports
  },
})
