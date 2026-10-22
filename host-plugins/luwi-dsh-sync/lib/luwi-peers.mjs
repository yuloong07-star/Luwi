// lib/luwi-peers.mjs — Luwi 新增文件 (G1-D 互为远端的对端名片, 上游 v0.2.24 没有这一份)
//
// "进程互相登记"的最小可信形态: 每台设备把自己的名片 (deviceId / name / url / updatedAt)
// 写进会话仓库的 peers/<设备 id>.json, 跟会话一起坐 git 过去 —— 对端 pull 之后, 名片就躺在
// 它的本地镜像工作树里, /sync peers 与手机设置页 ⋮ 里那几行「打开 <对端名>」都读那里
//
// 设计上只有一条硬规矩: **名片只在身份变了的时候才落盘**。updatedAt 跟着每次推拉刷新的话,
// 每一轮自动推送都会让镜像 dirty 一次, "重复推不产生第二份提交" (施工单判据 4) 就再也答
// 不上了 —— 所以盘上那份的 (deviceId, name, url) 与这次要写的一样时, 一个字节都不动,
// updatedAt 保持身份最后一次变化的时间 (语义: "这份名片是什么时候变成现在这样的")

import { promises as fs } from 'node:fs'
import path from 'node:path'
import { PEERS_DIR } from './constants.mjs'

// 名片文件名只认这一档字符 (UUID 由小写十六进制与连字符组成, 绰绰有余):
// 设备 id 会直接拼进路径, 拿不到这一关的值一个字节都不写
const PEER_FILE_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

/** 名片 JSON 的形状 (与施工单第 4 节一致; P2P 那一段见 P2P 方案 §8.2)。 */
/** @typedef {{deviceId: string, name: string, url: string, updatedAt: string, p2p?: {nodeId: string, ticket?: string, relay?: string, updatedAt?: string}}} PeerCard */

/**
 * 只留 p2p 段里认得的键 (对端写进来的字节不是可信输入)。
 * @param {unknown} raw - 名片里的 p2p 段。
 * @returns {{nodeId: string, ticket?: string, relay?: string, updatedAt?: string}|null} 规范化后的段。
 */
function normalizeP2pSection(raw) {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return null
  const nodeId = typeof raw.nodeId === 'string' ? raw.nodeId.trim() : ''
  if (nodeId === '') return null
  const section = { nodeId }
  if (typeof raw.ticket === 'string' && raw.ticket !== '') section.ticket = raw.ticket
  if (typeof raw.relay === 'string' && raw.relay !== '') section.relay = raw.relay
  if (typeof raw.updatedAt === 'string' && raw.updatedAt !== '') section.updatedAt = raw.updatedAt
  return section
}

/**
 * 把自己的名片写进 <repoDir>/peers/<deviceId>.json。
 *
 * 三种情况一个字节都不写: deviceId 缺失或非法 (它要拼进文件名); 身份三元组 (deviceId /
 * name / url) 与盘上那份一致 (updatedAt 不刷新, 见文件头那条硬规矩); **name 与 url 都空**
 * (对端没有可打开的东西, 这张名片没有存在的意义 —— 不配 deviceName/publicUrl 的部署连
 * peers/ 目录都不该出现, 上游部署的镜像输出因此一个字节不变)
 *
 * 返回值是读数不是成败: 一张名片不值得让一次 push 变红, 调用方记日志即可
 * @param {object} deps - {repoDir, deviceId, name?, url?, now?}。
 * @param {string} deps.repoDir - 会话仓库工作树根 (绝对)。
 * @param {string} deps.deviceId - 设备 id (元数据领域里的那个 UUID)。
 * @param {string} [deps.name] - 这台设备的名字 (config.deviceName, 可空)。
 * @param {string} [deps.url] - 这台 GUI 的地址 (config.publicUrl, 可空)。
 * @param {{nodeId: string, ticket?: string, relay?: string}} [deps.p2p] - P2P 身份段 (方案 §8.2)。
 * @param {Date} [deps.now] - 盖在 updatedAt 上的时刻 (测试注入)。
 * @returns {Promise<boolean>} 这次是否真的写了文件。
 */
export async function writePeerCard(deps) {
  const deviceId = typeof deps.deviceId === 'string' ? deps.deviceId.trim() : ''
  if (deviceId === '' || !PEER_FILE_NAME_RE.test(deviceId)) return false
  const name = typeof deps.name === 'string' ? deps.name.trim() : ''
  const url = typeof deps.url === 'string' ? deps.url.trim() : ''
  const p2pIn = deps.p2p === null || typeof deps.p2p !== 'object' ? undefined : deps.p2p
  const p2p = p2pIn === undefined ? undefined : normalizeP2pSection(p2pIn)
  // 没有可打开的东西、也没有 P2P 身份时才不写 (P2P 身份本身就是这张名片的用途)
  if (name === '' && url === '' && p2p === undefined) return false
  const stamp = (deps.now instanceof Date ? deps.now : new Date()).toISOString()
  const card = {
    deviceId,
    name,
    url,
    updatedAt: stamp,
  }
  if (p2p !== undefined) card.p2p = { ...p2p, updatedAt: p2p.updatedAt ?? stamp }
  const target = path.join(deps.repoDir, PEERS_DIR, `${deviceId}.json`)
  let previous = null
  try {
    const parsed = JSON.parse(await fs.readFile(target, 'utf8'))
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) previous = parsed
    if (previous !== null && previous.deviceId === deviceId && previous.name === name && previous.url === url) {
      // updatedAt 是"这一段身份什么时候变成现在这样"的记号, 比较时要忽略它自己
      const before = normalizeP2pSection(previous.p2p)
      const after = p2p === undefined ? null : { ...p2p }
      if (before !== null) delete before.updatedAt
      if (after !== null) delete after.updatedAt
      if (JSON.stringify(before) === JSON.stringify(after)) return false
    }
  } catch {
    // 不在 / 不是 JSON / 不是对象 → 照写
  }
  await fs.mkdir(path.dirname(target), { recursive: true })
  // 保留未知键: 名片是前向兼容的 (新版本写的字段, 老版本不该丢)。p2p 段不进了就删掉,
  // 不能让上一版的坏 nodeId 留在文件里
  const merged = { ...(previous ?? {}), ...card }
  if (p2p === undefined) delete merged.p2p
  await fs.writeFile(target, `${JSON.stringify(merged, null, 2)}\n`)
  return true
}

/**
 * 读本地镜像工作树里的全部名片 (含自己的 —— 调用方拿 deviceId 自行标 self)。
 *
 * 读的是**工作树上的字节**, 也就是"上一次镜像 / 合并之后看见的样子" —— 远端刚推上去、
 * 本地还没拉的名片不在这里, 回执的措辞照这个口径说。坏文件跳过并计数, 不抛
 * @param {string} repoDir - 会话仓库工作树根 (绝对)。
 * @returns {Promise<{cards: PeerCard[], broken: number}>} cards 按文件名排序, broken 是跳过的坏文件数。
 */
export async function readPeerCards(repoDir) {
  const dir = path.join(repoDir, PEERS_DIR)
  /** @type {string[]} */
  let names
  try {
    names = (await fs.readdir(dir)).filter((name) => name.endsWith('.json')).sort()
  } catch {
    return { cards: [], broken: 0 }
  }
  /** @type {PeerCard[]} */
  const cards = []
  let broken = 0
  for (const name of names) {
    try {
      const parsed = JSON.parse(await fs.readFile(path.join(dir, name), 'utf8'))
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object')
      const card = {
        deviceId: typeof parsed.deviceId === 'string' ? parsed.deviceId : '',
        name: typeof parsed.name === 'string' ? parsed.name : '',
        url: typeof parsed.url === 'string' ? parsed.url : '',
        updatedAt: typeof parsed.updatedAt === 'string' ? parsed.updatedAt : '',
      }
      const p2p = normalizeP2pSection(parsed.p2p)
      if (p2p !== null) card.p2p = p2p
      cards.push(card)
    } catch {
      broken += 1
    }
  }
  return { cards, broken }
}
