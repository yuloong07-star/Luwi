# Third-party notices

`dsh-session-sync` bundles no third-party source code. All JavaScript in this
repository (`index.mjs` and `lib/`) is original work by the dsh-session-sync
contributors, licensed under Apache-2.0 (see `LICENSE`).

**Luwi 例外 (2026-10-20)**: 扫码配对要在 PC 这一侧把二维码画出来, 而项目不引构建步骤 ——
`lib/vendor/qrcode.mjs` 是 [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator)
`1.4.4` 的 `qrcode.js` + `qrcode_UTF8.js` **逐字拼接** (只在末尾补了一个 ESM 默认导出):

| Bundled file | Upstream | Version | License | Purpose |
|---|---|---|---|---|
| `lib/vendor/qrcode.mjs` | [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) | 1.4.4 | MIT (Copyright (c) 2009 Kazuhiko Arase) | 把配对载荷画成二维码 (PC 面板显示, 手机扫) |

'QR Code' is a registered trademark of DENSO WAVE INCORPORATED.

The package depends on the following software. None of it is bundled into the
published tarball; these are install-time dependencies:

| Package | Version range | License | Purpose |
|---|---|---|---|
| [zod](https://github.com/colinhacks/zod) | `^4.4.3` | MIT | Runtime schema for the `session-sync` storage-domain record (persistence-boundary validator) |
| [typescript](https://github.com/microsoft/TypeScript) | `^5.9.0` | Apache-2.0 | `tsc --checkJs` typecheck gate (`tsconfig.check.json`) |
| [@deepseek-ai/cordis](https://www.npmjs.com/package/@deepseek-ai/cordis) | `^4.0.2` (peer) | See package | The plugin runtime |
| [@deepseek-ai/schemastery](https://www.npmjs.com/package/@deepseek-ai/schemastery) | `^3.18.2` (peer) | See package | Configuration schema |
| `@deepseek-ai/dsh-*` peers | `0.1.0-rc.8` (peer) | See packages | Official harness seams (`dsh-session`, `dsh-storage-domain`, `dsh-tools`) |

Development-only dependencies (not shipped, used by tests and the typecheck
gate) add `@deepseek-ai/dsh-commands`, `@deepseek-ai/dsh-llm`, `@deepseek-ai/dsh-storage`,
`@deepseek-ai/dsh-storage-json`, `@deepseek-ai/dsh-subprocess`, `@deepseek-ai/dsh-user-approval`,
`@deepseek-ai/dsh-user-questions` at `0.1.2-alpha.5`, and `@types/node`.

At runtime the plugin only talks to the harness services listed as
peerDependencies; it performs no network requests of its own — all network
traffic is `git` to the remote the user configures.
