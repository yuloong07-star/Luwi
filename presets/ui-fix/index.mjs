/**
 * 宿主那一半: 空的
 *
 * 这个包只有客户端那一半 (`./client`), 而 dsh 的 client-modules 只扫**已经挂上的 Loader 行**里
 * 声明了 `dsh.client` 的包 —— 所以它必须有这一行 (见 `PluginOverlay` 的 PLUGINS / OWN_PLUGINS),
 * 但宿主这一半什么都不做。样式全在 `lib/client.js` 里
 */
export const name = 'luwi-ui-fix'

export function apply() {}
