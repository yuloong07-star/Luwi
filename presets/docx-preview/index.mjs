/**
 * 宿主那一半: 空的
 *
 * 与 `presets/ui-fix` 同一个形状 —— dsh 的 client-modules 只扫**已经挂上的 Loader 行**里声明了
 * `dsh.client` 的包, 所以这一行必须有 (见 `PluginOverlay` 的 PLUGINS / OWN_PLUGINS), 但宿主这一半
 * 什么都不做: 客户端那一半是 `src/` 打出来的 `lib/client.js` (见 `tools/build-docx-preview.mjs`)
 */
export const name = 'lw-docx-preview'

export function apply() {}
