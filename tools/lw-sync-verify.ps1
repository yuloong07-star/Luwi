# lw-sync-verify.ps1 — G1 同步的验证器 (真机与模拟器都行)
#
# **只读**: 不改设备配置、不改两端 host、不推不拉, 只发 GET、读日志、读文件
#
# 六个阶段, 每条判据独立给 PASS / FAIL / TODO / SKIP, 末了一张表 + 退出码
#   A 装机面   —— 设备在不在、包在不在、无障碍在不在、启动行是哪一版
#   B 挂载面   —— 面板握手 (插件真的挂在宿主上, 不是"网页能打开")
#   C 配置面   —— 设备那份 session-sync.json 与面板读数交叉核对 (防"界面存了、插件没读到")
#   D 令牌闸   —— 无令牌 / 错令牌 / 对令牌 三条 (PC 打手机)
#   E 名片面   —— 镜像里的对端名片有没有 url (两端靠它互相知道地址)
#   F 数据面   —— 会话那根的 lastError (判 adb reverse 那条 git 隧道通不通)
#   G 人工判据 —— 双设备那四条只能人做, 这里只把操作步骤打出来
#
# 用法:
#   pwsh -File tools\lw-sync-verify.ps1                        # 真机
#   pwsh -File tools\lw-sync-verify.ps1 -Serial emulator-5554  # 模拟器
#   pwsh -File tools\lw-sync-verify.ps1 -Token '两边同一个口令'  # 不给就从设备那份 JSON 里读
#
# 退出码: 0 = 没有 FAIL (TODO/SKIP 不算失败), 1 = 有 FAIL
param(
    [string]$Serial = 'OJZD5D6TMNSKFYHM',
    [string]$Package = 'io.github.yuloong07star.luwi',
    [int]$PhonePort = 3080,
    [int]$LocalPort = 13080,
    [string]$Token = ''
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$checks = New-Object System.Collections.ArrayList
function Add-Check {
    param([string]$Stage, [string]$Name, [string]$Result, [string]$Detail = '')
    [void]$checks.Add([pscustomobject]@{ Stage = $Stage; Name = $Name; Result = $Result; Detail = $Detail })
    $color = switch ($Result) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'TODO' { 'Yellow' } default { 'DarkGray' } }
    Write-Host ("  [{0,-4}] {1}{2}" -f $Result, $Name, $(if ($Detail) { " — $Detail" } else { '' })) -ForegroundColor $color
}

# 参数一律走**数组**, 不要写成 `Adb logcat -d -s X` 那种形状 ——
# 那个形状里 PowerShell 会把 `-d` 当成公共参数 `-Debug` 吃掉, adb 收到的是不带 -d 的 logcat,
# 于是它开始流式输出、永不退出 (2026-10-13 实测: 脚本在 A 阶段末尾挂死两分钟)
function AdbRun {
    param([string[]]$Argv)
    $output = & $adb -s $Serial @Argv 2>&1
    return ($output | Out-String).Trim()
}

# HTTP 探针: 走 curl.exe (Windows 自带), 自己把状态码从输出的尾巴上摘下来
#
# **超时**必须由调用方给: 手机侧的路由要经 Linux 沙箱跑 git, 面板实测 **~25 s**、冷调 /peer/info ~5 s。
# 用 8 s 去探它只会拿到 http=0, 然后就会得出"网络不通"的假结论 (2026-10-14 就是这么被坑了两轮)
function HttpProbe {
    param([string]$Url, [string]$Bearer = '', [string]$Method = 'GET', [string]$Json = '', [int]$TimeoutSec = 60)
    $argv = @('-s', '-m', "$TimeoutSec", '-o', '-', '-w', '|||%{http_code}')
    if ($Bearer -ne '') { $argv = @('-H', "Authorization: Bearer $Bearer") + $argv }
    if ($Method -ne 'GET') { $argv = @('-X', $Method) + $argv }
    if ($Json -ne '') { $argv = @('-H', 'content-type: application/json', '--data', $Json) + $argv }
    $raw = (& curl.exe @argv $Url 2>$null | Out-String)
    $code = 0
    $at = $raw.LastIndexOf('|||')
    if ($at -ge 0) {
        $tail = $raw.Substring($at + 3).Trim()
        if ($tail -match '^\d+$') { $code = [int]$tail }
        $raw = $raw.Substring(0, $at)
    }
    return [pscustomobject]@{ code = $code; body = $raw.Trim() }
}

function Mask {
    param([string]$Secret)
    if ($Secret -eq '') { return '<unset>' }
    if ($Secret.Length -le 6) { return ('*' * $Secret.Length) }
    return ($Secret.Substring(0, 4) + ('*' * ($Secret.Length - 4)) + " ({0} 字)" -f $Secret.Length)
}

Write-Host ""
Write-Host "G1 同步验证器 — $Serial" -ForegroundColor Cyan

# ---------------------------------------------------------------- A 装机面
Write-Host ""
Write-Host "A 装机面" -ForegroundColor Cyan
$state = (AdbRun @('get-state'))
if ($state -eq 'device') { Add-Check 'A' '设备在线' 'PASS' $Serial } else { Add-Check 'A' '设备在线' 'FAIL' $state }

$apkPath = (AdbRun @('shell', "pm path $Package"))
if ($apkPath -match 'package:') { Add-Check 'A' '包已安装' 'PASS' ($apkPath -split "`n" | Select-Object -First 1) }
else { Add-Check 'A' '包已安装' 'FAIL' 'pm path 空手而归' }

$listed = (AdbRun @('shell', 'settings get secure enabled_accessibility_services'))
$component = "$Package/$Package.channel.LwAccessibility"
if (($listed -split ':') -contains $component) { Add-Check 'A' '无障碍在名单里' 'PASS' '' }
else { Add-Check 'A' '无障碍在名单里' 'FAIL' '重装后没写回去, 可跑 tools\lw-install.ps1' }

$bound = (AdbRun @('shell', "dumpsys activity services $Package")) -match 'LwAccessibility'
if ($bound) { Add-Check 'A' '无障碍真的绑上了' 'PASS' '' } else { Add-Check 'A' '无障碍真的绑上了' 'FAIL' '名单在而实例没了, 用应用内那个开关摘掉再放回' }

# 启动行: 这一版带 G1-2 的判据是那行里出现 peer= 字段
# (peer 关着时它就是 `peer=off` 收尾, live= 那一段只在开着时才打出来 —— 别拿 live= 当版本判据)
$logDump = AdbRun @('logcat', '-d', '-s', 'DshHost')
$line = ($logDump -split "`r?`n" | Select-String -Pattern 'session-sync:' | Select-Object -Last 1)
$startup = if ($line) { $line.Line } else { '' }
if ($startup -ne '') {
    Add-Check 'A' '启动行拿到' 'PASS' ($startup -replace '^.*DshHost\s*:\s*', '')
    if ($startup -match 'peer=') { Add-Check 'A' '这一版带 G1-2 代码' 'PASS' '启动行有 peer=' } else { Add-Check 'A' '这一版带 G1-2 代码' 'FAIL' '启动行没有 peer=, 装的是旧包' }
    if ($startup -match 'peer=on') { Add-Check 'A' 'peer 通道自报开着' 'PASS' '' } else { Add-Check 'A' 'peer 通道自报开着' 'TODO' '配置里还没写 peer 段, 见 C 阶段' }
    if ($startup -match 'live=on') { Add-Check 'A' 'live 投影自报开着' 'PASS' '' } else { Add-Check 'A' 'live 投影自报开着' 'TODO' '启动行没有 live=on (peer 关着时它不打这一段)' }
} else {
    # 不是失败: DshHost 那一行会被别的进程的日志从环形缓冲里挤掉, 而宿主活着的证据是 B 段那面面板
    Add-Check 'A' '启动行拿到' 'SKIP' 'logcat 里已经滚掉了, 版本判据看 B 段的面板'
}

# ---------------------------------------------------------------- 宿主 HTTP 面
Write-Host ""
Write-Host "B 挂载面" -ForegroundColor Cyan
& $adb -s $Serial forward --remove tcp:$LocalPort | Out-Null
$fwd = (& $adb -s $Serial forward tcp:$LocalPort tcp:$PhonePort | Out-String).Trim()
$base = "http://127.0.0.1:$LocalPort"
# 面板这条要 120 s: 手机侧的 /luwi-sync/panel **每次**都要经 Linux 沙箱跑一串 git 状态
# (proot + /sdcard, 实测 25 s 起; 若正撞上一次 pull 排队, 会更久) —— 它不是"心跳", 别用小超时探
$panel = HttpProbe -Url "$base/luwi-sync/panel" -TimeoutSec 120
$snap = $null
if ($panel.code -eq 200) {
    Add-Check 'B' "面板可达 (本机 $LocalPort -> 设备 $PhonePort)" 'PASS' ''
    try { $snap = $panel.body | ConvertFrom-Json } catch { Add-Check 'B' '面板是 JSON' 'FAIL' '解析失败' }
    if ($snap) {
        if ($snap.handshake -eq 'luwi-dsh-sync/panel') { Add-Check 'B' '握手标识' 'PASS' $snap.handshake } else { Add-Check 'B' '握手标识' 'FAIL' "$($snap.handshake)" }
        if ($snap.mount.mounted) { Add-Check 'B' '插件真挂在宿主上' 'PASS' "runner=$($snap.mount.runner)" } else { Add-Check 'B' '插件真挂在宿主上' 'FAIL' '客户端半活着而宿主半没挂' }
        Add-Check 'B' '插件版本' 'PASS' "$($snap.plugin) v$($snap.version)"
        # G1-2 的版本判据 (与启动行那条等价): 面板快照里**有 peer 块**就是这一版
        if ($snap.peer -ne $null) { Add-Check 'B' '这一版带 G1-2 (面板有 peer 块)' 'PASS' "live=$($snap.peer.live.enabled)" } else { Add-Check 'B' '这一版带 G1-2 (面板有 peer 块)' 'FAIL' '快照里没有 peer 块, 装的是 G1-2 之前的包' }
        # G1-3 的版本判据: 影工作区里新开的一场归对端执行 (peer.workspace.newSessions)。
        # 同一条判据也钉着"面板那个发消息框不再是死路" —— 它发出去的正是 kind:'new'
        if ($snap.peer -and $snap.peer.workspace -and $snap.peer.workspace.newSessions -eq $true) {
            Add-Check 'B' '这一版带 G1-3 (影工作区里新开的一场归对端)' 'PASS' "影工作区 $($snap.peer.workspace.shadows.Count) 个 · 投影 $($snap.peer.workspace.proxies.Count) 条"
        } else {
            Add-Check 'B' '这一版带 G1-3 (影工作区里新开的一场归对端)' 'FAIL' 'peer.workspace 里没有 newSessions —— 装的是 G1-3 之前的包'
        }
    }
} else {
    Add-Check 'B' "面板可达 (本机 $LocalPort -> 设备 $PhonePort)" 'FAIL' "http=$($panel.code); 宿主没在监听就先把应用打开 (别用 am start -S)"
}

# ---------------------------------------------------------------- C 配置面
Write-Host ""
Write-Host "C 配置面" -ForegroundColor Cyan
$cfgRaw = (AdbRun @('shell', "run-as $Package cat files/dsh-home/lw/session-sync.json"))
$cfg = $null
if ($cfgRaw -match '^\s*\{') {
    try { $cfg = $cfgRaw | ConvertFrom-Json } catch { }
}
if ($cfg) {
    Add-Check 'C' '设备配置文件读得到' 'PASS' 'files/dsh-home/lw/session-sync.json'
    if ($Token -eq '' -and $cfg.peer -and $cfg.peer.token) { $Token = [string]$cfg.peer.token }
    $peerOn = $cfg.peer -and $cfg.peer.enabled
    $listenOn = $cfg.peer -and $cfg.peer.listen
    if ($peerOn -and $listenOn) { Add-Check 'C' '配置里 peer.enabled/listen 都开' 'PASS' '' } else { Add-Check 'C' '配置里 peer.enabled/listen 都开' 'TODO' '没配就填 peer 段, 改完要点设置页右上角 ⋮ 重启 host' }
    if ($Token -ne '') {
        if ($Token.Length -ge 16) { Add-Check 'C' '口令长度够 (>=16)' 'PASS' (Mask $Token) } else { Add-Check 'C' '口令长度够 (>=16)' 'FAIL' (Mask $Token) }
    } else {
        Add-Check 'C' '口令长度够 (>=16)' 'TODO' '没有口令: 通道关是失败关闭, 不是 bug'
    }
    if ($cfg.publicUrl) { Add-Check 'C' '本机地址 (publicUrl) 写了' 'PASS' $cfg.publicUrl } else { Add-Check 'C' '本机地址 (publicUrl) 写了' 'TODO' '空 = 自己那张名片不带 url, 对端只能靠手填 peer.url' }
    # 交叉核对: 配置文件与面板读数必须一致, 否则就是"存了没生效"
    if ($snap) {
        $sameName = ([string]$cfg.deviceName) -eq ([string]$snap.config.deviceName)
        $sameRepo = ([string]$cfg.remote) -ne '' -and $snap.config.remoteState -eq 'set'
        if ($sameName) { Add-Check 'C' '设备名 配置与面板一致' 'PASS' $snap.config.deviceName } else { Add-Check 'C' '设备名 配置与面板一致' 'FAIL' "文件 '$($cfg.deviceName)' vs 面板 '$($snap.config.deviceName)'" }
        if ($sameRepo) { Add-Check 'C' '远端已设且面板认到' 'PASS' $snap.config.remoteState } else { Add-Check 'C' '远端已设且面板认到' 'FAIL' "remoteState=$($snap.config.remoteState)" }
    }
} else {
    Add-Check 'C' '设备配置文件读得到' 'SKIP' 'run-as 读不到, C 阶段整段跳过'
}

# ---------------------------------------------------------------- D 令牌闸
Write-Host ""
Write-Host "D 令牌闸 (PC 打手机)" -ForegroundColor Cyan
# 判据看**路由自己的回答**, 不看面板快照 (快照拿不到时不该把它读成"通道关"):
#   401/401      = 通道开着, 令牌闸生效 (接着验对令牌 200)
#   404/404      = 通道关 (路由一条都没注册); 配置里写着开就是"存了没生效, 要重启 host"
$info = HttpProbe -Url "$base/luwi-sync/peer/info"
$wrong = HttpProbe -Url "$base/luwi-sync/peer/info" -Bearer 'not-the-right-token-at-all'
if ($info.code -eq 401 -and $wrong.code -eq 401) {
    Add-Check 'D' '通道开着: 无令牌/错令牌都 401' 'PASS' '令牌闸生效'
    if ($Token -ne '') {
        $ok = HttpProbe -Url "$base/luwi-sync/peer/info" -Bearer $Token
        if ($ok.code -eq 200 -and $ok.body -match '"ok":true') {
            $body = $null
            try { $body = $ok.body | ConvertFrom-Json } catch { }
            $counts = if ($body) { "device=$($body.device.name) id=$(@($body.device.id).Length)字 workspaces=$(@($body.workspaces).Count) sessions=$(@($body.sessions).Count) live=$(@($body.live).Count)" } else { '' }
            Add-Check 'D' '对令牌 200' 'PASS' $counts
        } else {
            Add-Check 'D' '对令牌 200' 'FAIL' "http=$($ok.code) $($ok.body)"
        }
    } else {
        Add-Check 'D' '对令牌 200' 'TODO' '没给口令, 这条验不了 (用 -Token 或先在设备上配好)'
    }
} elseif ($info.code -eq 404 -and $wrong.code -eq 404) {
    $cfgOn = ($cfg -ne $null) -and ($cfg.peer -ne $null) -and ($cfg.peer.enabled -eq $true)
    if ($cfgOn) { Add-Check 'D' '通道关: 路由一条都不注册 (404)' 'TODO' '配置文件里已经开了, 而运行中的宿主还是关 —— 重启 host 才生效' }
    else { Add-Check 'D' '通道关: 路由一条都不注册 (404)' 'PASS' '失败关闭成立' }
    Add-Check 'D' '对令牌 200' 'TODO' '把 peer 段配上并重启 host 再来'
} else {
    Add-Check 'D' '令牌闸' 'FAIL' "无令牌 http=$($info.code) / 错令牌 http=$($wrong.code) —— 既不是 401 也不是 404"
    Add-Check 'D' '对令牌 200' 'SKIP' '令牌闸不明, 这一条先不看'
}

# ---------------------------------------------------------------- E 名片面
Write-Host ""
Write-Host "E 名片面 (两端互相知道地址)" -ForegroundColor Cyan
$peers = HttpProbe -Url "$base/luwi-sync/peers"
if ($peers.code -eq 200) {
    $cards = $null
    try { $cards = ($peers.body | ConvertFrom-Json).cards } catch { }
    if ($cards) {
        $self = if ($snap) { [string]$snap.mirrors[0].meta.deviceId } else { '' }
        $others = @($cards | Where-Object { $_.deviceId -ne $self })
        if ($others.Count -gt 0) {
            Add-Check 'E' '镜像里有对端名片' 'PASS' (($others | ForEach-Object { "$($_.name)=$($_.deviceId.Substring(0,8))" }) -join ', ')
            $withUrl = @($others | Where-Object { $_.url -match '^https?://' })
            if ($withUrl.Count -gt 0) { Add-Check 'E' '对端名片带 http(s) 地址' 'PASS' $withUrl[0].url }
            else { Add-Check 'E' '对端名片带 http(s) 地址' 'TODO' '对端没配 publicUrl, peer.url 只能手填 (虚拟网/LAN 地址写进去就行)' }
        } else {
            Add-Check 'E' '镜像里有对端名片' 'TODO' '本地镜像里只有自己: 先让对端推一次、这边拉一次'
        }
    }
} else {
    Add-Check 'E' '名片路由可达' 'FAIL' "http=$($peers.code)"
}

# ---------------------------------------------------------------- F 数据面
Write-Host ""
Write-Host "F 数据面 (会话那根)" -ForegroundColor Cyan
if ($snap -and $snap.mirrors.Count -gt 0) {
    $m = $snap.mirrors[0]
    Add-Check 'F' '会话镜像开着' 'PASS' "$($m.remote.display) head=$($m.status.head.Substring(0,7))"
    # 判"现在通不通"要看会动的证据: FETCH_HEAD 只在 fetch **成功**时才写 (手机侧的 gitdir 在沙盒里)
    # lastError 是**粘性**的 —— 成功不清它 (index.mjs 只在出错时写), 所以它只当"曾经出过什么错"看
    $fetchHit = AdbRun @('shell', 'find /data/local/tmp/lwlinux/rootfs/root/lw-sync -maxdepth 3 -name FETCH_HEAD -mmin -5 2>/dev/null | head -1')
    if ($fetchHit -ne '') { Add-Check 'F' '最近 5 分钟 fetch 成过' 'PASS' 'FETCH_HEAD 成功才写' }
    else { Add-Check 'F' '最近 5 分钟 fetch 成过' 'FAIL' '没有新鲜的 FETCH_HEAD: 隧道 / 远端 / 沙盒三处里有一处不通' }
    $err = [string]$m.meta.lastError
    if ($err -eq '') { Add-Check 'F' '历史错误是空的' 'PASS' '' }
    else { Add-Check 'F' '历史错误是空的' 'TODO' "粘性字段 (成功后不清, 待改的一处): $err" }
    # 这条只在**真的没 fetch 成**时才提隧道: lastError 是粘性的, 拿它当判据会在隧道好好的时候也报一句
    if ($fetchHit -eq '' -and $m.remote.display -match 'git://127\.0\.0\.1:9418') {
        Add-Check 'F' 'git 隧道 (adb reverse 9418)' 'TODO' ("PC 侧: git daemon --base-path=<relay> --export-all --listen=127.0.0.1 --port=9418 加 adb -s $Serial reverse tcp:9418 tcp:9418")
    }
    $forks = @($m.status.forks)
    if ($forks.Count -eq 0) { Add-Check 'F' '无 fork 文件' 'PASS' '' } else { Add-Check 'F' '无 fork 文件' 'TODO' ("$($forks.Count) 个历史 fork 还在, 判据是'不再新增': " + ($forks -join ', ')) }
    if ($m.status.mergeInProgress) { Add-Check 'F' '没有半途的合并' 'FAIL' 'mergeInProgress=true' } else { Add-Check 'F' '没有半途的合并' 'PASS' '' }
} else {
    Add-Check 'F' '会话镜像读数' 'SKIP' '拿不到面板快照'
}

# ---------------------------------------------------------------- G 人工判据
Write-Host ""
Write-Host "G 双设备端到端 (只能人做)" -ForegroundColor Cyan
Write-Host @"
  两端都配好并重启 host 之后, 按这七条量 (每条都有反证, 别只看"出现了"):
   1 投影进分组   手机说一句 -> PC /sync pull -> 右侧出现 名字（设备名） 分组 (对端那一条**永远**带设备名,
                   裸名字 = 本机的), 里面那一场只有文本
                  反证: 手机那份 session 文件 md5 前后不变
   2 回答也在里面 那一场里**用户的话与回答交替出现** (2026-10-14 修: 从前只折得出用户的话)
                  反证: 回答不重复落 (同一批再刷新一次, 事件数不变)
   3 只同步该同步的 **未归档 + 从相连之后有新活动**的会话才出现; 对端搬进来的副本不在本机列表里
                  (2026-10-14 修: 从前两边都把"全部对话"摆出来)
   4 归属方执行   PC 上于手机那场打字 -> 手机真的跑一轮 (在对端搬进来的副本上打字同理,
                  本机日志一个字节都不写 —— 写了就是 APPEND_BOTH)
                  反证: PC 本地日志无 request/header (模型一轮没跑)
   5 回答回到原地 回答出现在 PC 那一场, 没有 reasoning 与工具块
   6 退路不丢     拔掉可达性 (撤 adb reverse) -> 回执 via=queue -> 恢复后收到并回执, 重复投递 0
   7 不分叉       连做 5 次远程指令, 两端仓库零 fork 文件 (F 阶段那条计数回到 0)
"@

# ---------------------------------------------------------------- 汇总
Write-Host ""
Write-Host "汇总" -ForegroundColor Cyan
$byResult = $checks | Group-Object Result
$summary = ($byResult | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join '  '
Write-Host "  $summary"
$fails = @($checks | Where-Object { $_.Result -eq 'FAIL' })
if ($fails.Count -gt 0) {
    Write-Host ""
    Write-Host "  未过:" -ForegroundColor Red
    $fails | ForEach-Object { Write-Host "    [$($_.Stage)] $($_.Name) — $($_.Detail)" -ForegroundColor Red }
}
Write-Host ""
Write-Host "  隧道留着没撤 (双设备要用): 本机 $LocalPort -> 设备 $PhonePort ; 撤: $adb -s $Serial forward --remove tcp:$LocalPort" -ForegroundColor DarkGray
Write-Host ""

if ($fails.Count -gt 0) { exit 1 }
exit 0
