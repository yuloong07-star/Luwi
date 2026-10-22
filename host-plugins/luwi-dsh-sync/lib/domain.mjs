// lib/domain.mjs — 'session_sync' 存储领域声明（唯一允许 zod/DSH 包的 lib 模块：
// 领域记录 schema 是持久边界校验器，zod 与 defineDomain 是 harness 自身词汇）。

import { z } from 'zod'
import { defineDomain, domainTable } from '@deepseek-ai/dsh-storage-domain'
import { DOMAIN_NAME, LIMITS } from './constants.mjs'

/**
 * 同步元数据记录（state 表单例键）：设备 id（fork 归属/提交署名）、最近
 * 推/拉时间、最近推送头与最近错误。介质上每条记录在 open 时按此校验
 * （invalid-record 响亮拒绝，绝不静默跳过）。
 *
 * **这条记录不只装这五项**。插件自己的账也住在里面: `sessionBaseline` (会话基线),
 * `peerProxies` / `peerShadows` / `peerRetired` / `peerEpochAt` / `peerHidden` /
 * `peerQueued` (对端那一套), 由 index.mjs 的 `patchPluginState` 与 `reportMeta` 用
 * `{...current, ...patch}` 写进来。而 storage 域在 **open 时对每条记录跑一次 `valueSchema.parse`**
 * (packages/storage/storage-domain 的 `open`), z.object 默认把**没列出来的键静默删掉** ——
 * 于是文件里那 700 KB 的基线, 下一次启动读回来就没了:
 * `stored.pairId !== pairing.id` 成立 → `ensureBaselineState` 重捕获 → `purgePending` 又置真
 * → 首拉前先扫一遍整个会话存储 (手机上 3669 个文件), adopt 永远轮不到.
 * 2026-10-21 手机自动 pull 卡死 ("lastPullAt 停在 13:09 / purgePending 一直 true") 就是这个,
 * 不是什么 "pairing id 变了": 配置文件里那个 id 从 2026-10-20 22:04 起一个字没动.
 *
 * `.catchall(z.unknown())` = 没列出来的键原样留着 (zod 4 里等价 looseObject); 以后往这条记录
 * 里加字段不必再回来改 schema, 而列出来的那五项照旧受校验
 */
export const syncStateSchema = z.object({
  deviceId: z.string().min(1).max(LIMITS.MAX_DEVICE_ID_LENGTH),
  lastPullAt: z.number().int().nonnegative().optional(),
  lastPushAt: z.number().int().nonnegative().optional(),
  lastPushHead: z.string().regex(/^[0-9a-f]{40,64}$/iu).optional(),
  lastError: z.string().max(500).optional(),
}).catchall(z.unknown())

/**
 * 'session_sync' 领域 spec：一张 'state' 表，键为单例键。
 * version 变更即废弃整介质（预发布立场，无迁移）。
 */
export const sessionSyncDomainSpec = defineDomain({
  name: DOMAIN_NAME,
  version: 1,
  tables: { state: domainTable(syncStateSchema) },
})
