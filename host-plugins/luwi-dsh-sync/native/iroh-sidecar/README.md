# iroh-sidecar (v2)

两端共用的 iroh 端点进程: 手机以 `liblwiroh.so` (aarch64 静态 musl) 从 `nativeLibraryDir` exec,
Windows 以 `lwiroh.exe` 从插件目录 exec。**它只做三件事** —— 端点与身份、拨号/接听、双向字节搬运;
业务帧 (HELLO/HEADS/MANIFEST/…) 全部在 JS 侧, 合并逻辑也只有 JS 一份。

设计依据: `docs/Luwi-异地同步-P2P实现方案-20261022.md` §5 (传输层) 与 §14 的 T1。
为什么不用 napi 包: `@number0/iroh` 在纯 Node 进程里构造端点必 panic (`android context was not
initialized`), 换成普通 Linux target 就没有 `ndk_context` 这条路径。

## 产物

| 平台 | 文件 | 谁用 |
| :-- | :-- | :-- |
| Windows x64 | `native/win32-x64/lwiroh.exe` (mingw/zig, 静态 CRT) | PC 插件 `subprocess.spawn`; 环回测试 |
| Android arm64 | `app/src/main/jniLibs/arm64-v8a/liblwiroh.so` | 手机插件, 与 `libnode.so` 同目录 |

`app/src/main/jniLibs/` 整个被 `.gitignore` 忽略, 所以安卓那半边是**本机生成物**: 打 APK 前跑
`rebuild-arm64.sh`, 否则包里没有这个文件 (表现是插件起 sidecar 时 exec 找不到)。
命名成 `lib*.so` 是借 `useLegacyPackaging = true` 拿可执行位。

## 构建 (都在 WSL Ubuntu 里)

```bash
# 依赖: rustup target add x86_64-pc-windows-gnu aarch64-unknown-linux-musl
#       zig + cargo-zigbuild (zig 在 ~/zig/zig)
bash rebuild-win-x64.sh    # -> native/win32-x64/lwiroh.exe
bash rebuild-arm64.sh      # -> app/src/main/jniLibs/arm64-v8a/liblwiroh.so
```

**必须用 `cargo zigbuild`**: 手写 zig cc 包装器会和 ring 自带的 `--target` 打架; Windows 上也没有
MSVC 的 `link.exe`, 所以走 `x86_64-pc-windows-gnu` (zig 自带 mingw)。

## 运行

```powershell
$env:LW_TOKEN='dev-token'; $env:LW_PORT='7523'; $env:LW_DATA_PORT='7524'; $env:LW_INBOUND='7525'
.\native\win32-x64\lwiroh.exe daemon
# stdout: READY port=7523 dataPort=7524 token_len=9 idleMs=0 inbound=7525
```

| 环境变量 | 缺省 | 说明 |
| :-- | :-- | :-- |
| `LW_PORT` | 0 | 控制面端口, 0 = 随机 (按 READY 行取实际值) |
| `LW_DATA_PORT` | 0 | 数据面端口, 0 = 随机 |
| `LW_INBOUND` | 0 | JS 的入站回调端口, 0 = 不接听 |
| `LW_TOKEN` | `dev-token` | 控制面 Bearer 与数据面首行口令 (**生产必须随机**) |
| `LW_IDLE_MS` | 0 | 没有活动连接超过这么久就自己退出, 0 = 不退 (常驻档) |
| `LW_PRESET` | `n0` | `minimal` = 不碰 relay 与发现 (环回测试) |
| `LW_ONLINE_MS` | 8000 | 等 `online()` 的上限, 超时就继续 (本地直连仍可用) |

## 控制面 (HTTP, 只绑 127.0.0.1)

| 调用 | 鉴权 | 返回 |
| :-- | :-- | :-- |
| `GET /health` | 无 | `{"ok":true}` |
| `GET /status` | Bearer | `id` / `ticket` / `uptimeMs` / `alpn` / `conns{active,total}` / `idleMs` / `dataPort` / `inboundPort` |
| `GET /v1/id` | Bearer | `{id, ticket}` |
| `POST /v1/shutdown` | Bearer | `{"ok":true}`, 应答后退出 |
| `POST /send?bytes=N[&family=4\|6]` | Bearer | 旧探针: body 是对端 ticket, 拨号并发 N 字节, 回吞吐读数 |

## 数据面 (裸 TCP, 只绑 127.0.0.1)

```text
出站 (JS 拨 sidecar):
  JS 连 127.0.0.1:<LW_DATA_PORT>
  JS 写一行 {"op":"connect","to":"<ticket|endpointId>","token":"…"}
  sidecar 回一行 {"ok":true,"path":"ip|relay","rttMs":42,"remote":"[240e:…]:4433","peer":"<id>"}
  之后这条 TCP 是双向字节管道 (JS 写 = 去对端, JS 读 = 从对端来)

入站 (对端拨进来):
  sidecar 收到 QUIC 双向流后, 主动连 127.0.0.1:<LW_INBOUND>
  写一行 {"peer":"<对端 id>"}
  之后是同样的双向字节管道; JS 先查信任表, 不认就 close
```

半关语义: JS 关写 -> sidecar `finish()` QUIC 发送流 (对端读到 EOF); 对端 `finish` -> sidecar
`shutdown()` TCP 写端 (JS 读到 EOF)。一侧出错就整条连接关掉, 不留半份。

## 冒烟 (T1 判据)

```powershell
node smoke.mjs            # 默认 native/win32-x64/lwiroh.exe, 1 MiB 双向
```

它起两个 sidecar, A 拨 B 的 ticket, 入站回调落到脚本自己的 TCP 端口, 双向各发 1 MiB 随机字节并
比对 sha256; 顺带打印 `path` / `rttMs` / `remote` 与吞吐。**这一步过了 = 数据面、入站回调、控制面
三条都通了**。

## 功耗口径

2026-10-19 真机读数 (旧探针满速进程) 空闲 **7.9% 单核**, RSS 13 MB, 所以**不要 24 小时常驻**。
常驻档的真实成本按 T1 重新量 (空闲心跳 / 一轮同步 / 息屏), 数字记进方案文档 §10。
`LW_IDLE_MS` 是给 `ondemand` 档用的: 最后一条连接结束 90 s 后自己消失。

## 模式 (只用于测量, 不参与产品路径)

| 模式 | 用途 |
| :-- | :-- |
| `daemon` | 起端点 + 控制面 + 数据面 + 入站, 插件调这个 |
| `listen` / `listen4` | 最小验证: 等一条连接收字节, 打印路径与吞吐 |
| `dial <ticket> [bytes]` / `dial4` / `dial6` | 最小验证: 拨过去发 N 字节 |

`listen4` 只公布 IPv4 + relay, `dial4`/`dial6` 先把 ticket 裁成只剩对应地址族 —— 用来分档量
"v6 直连 / v4 打洞 / relay", 别拿 `isIp` 当判据 (它分不出 v6 直连与 v4 打洞)。
