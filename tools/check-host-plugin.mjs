/**
 * 装进 host 树之前先问一句: 这个插件到底能注册出几个工具
 *
 * 为什么要有这一步: `defineTool` 在**模块求值的时候**就把参数 schema 编译一遍, 一张不合规的
 * schema 会当场抛出, 而插件的 `TOOLS` 是模块级的常量数组 —— 于是抛出的位置是 import, 整包一起死,
 * 会话里一个 `lw_*` 工具都没有, 而不是"某一个工具不好用"。2026-10-04 在真机上就是这么中招的:
 * `lw_gesture` 的两个嵌套对象节点少了 `additionalProperties` (dsh 的 ObjectValueSchemaSpec 把这个
 * 键定成必填, 只有参数根是隐式开放的), 升到 dsh 0.2.0-rc.2 之后整包 UNSUPPORTED_SCHEMA
 *
 * 判据是"注册出来的工具数不低于下限", 不是"没抛异常": 少一个工具同样要拦住
 *
 * 用法: node tools/check-host-plugin.mjs [插件路径, 默认 host-plugin/index.mjs]
 * 依赖: `@deepseek-ai/dsh-tools` 要能被解析到 —— 本仓库自己那棵树在 submodule 里, 所以开发时在
 * `D:\apk\node_modules\@deepseek-ai\` 下放一个指向它的链接即可 (打包出来的 host 树里本来就有一份)
 */

import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

/**
 * 工具数的下限: 加工具的时候连同这个数一起加一
 *
 * 它是给"静默少一个"兜底的 —— 比如某个工具的定义被条件包住而条件不成立, 那时没有异常,
 * 只有数字会变
 */
const FLOOR = 64

const pluginPath = resolve(process.argv[2] ?? 'host-plugin/index.mjs')

/** 注册进来的工具, 顺序就是插件里的定义顺序 */
const registered = []

const ctx = {
  tools: {
    register(definition) {
      registered.push(definition)
      return () => {}
    },
  },
  on() {},
}

try {
  const plugin = await import(pathToFileURL(pluginPath).href)
  plugin.apply(ctx)
} catch (error) {
  console.error(`插件加载失败: ${error?.message ?? error}`)
  process.exit(1)
}

for (const tool of registered) console.log(`  ${tool.name}`)
console.log(`${registered.length} 个工具`)

if (registered.length < FLOOR) {
  console.error(`工具数 ${registered.length} 低于下限 ${FLOOR}: 有工具没注册上`)
  process.exit(1)
}
