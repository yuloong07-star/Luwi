# Luwi 一键装机: 装 APK → 写回无障碍与通知使用权 → 拉起应用等 host 就绪
#
# 从真机上手工做这件事有几个坑, 这个脚本就是为了绕开它们:
#
#   1. **必须带 `-i`**: 不带 installer 身份的安装会把 installerPackageName 打回 null, 而 Android 13
#      起 installerPackageName 为 null 的应用不许开无障碍 (中间隔着 ACCESS_RESTRICTED_SETTINGS 那道 app op)
#   2. **重装会把无障碍配置清掉**: 装完要重新把我们的组件写回 enabled_accessibility_services,
#      **而且要读出来改** (设备上还有别人的无障碍服务)。vivo 那台机器上还多一条: secure settings
#      的写入只在重装之后很短的一段时间里被接受, 所以"装"与"写回"要连着做, 中间不要插别的事
#   3. **HyperOS 上 `pm install` 会被挡, `adb install` 能过** (2026-10-17 在 22041216C / OS2.0.12.0
#      上实测: 推上去再 `pm install -r` 一律 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by
#      user`, 而 `adb -s <串> install -r` 的流式安装直接 Success)。所以这里**先试流式**, 不行才退
#      回"推上去就地装"那条老路
#   4. **装完要拉起一次应用**: host 树是首启解压的, 不拉起来, 设备上只有 2.7.x 的壳而没有 host
#
# 用法:
#   pwsh -File tools/lw-install.ps1                       # 一键: 自动挑设备 (优先真机), 装完拉起等就绪
#   pwsh -File tools/lw-install.ps1 -Build                # 先出包再装 (等价于 dev.ps1 build + install)
#   pwsh -File tools/lw-install.ps1 -Serial emulator-5554 # 点名设备
#   pwsh -File tools/lw-install.ps1 -Perms                # 顺带批量授权 (见下)
#   pwsh -File tools/lw-install.ps1 -SkipInstall          # 只重跑权限与无障碍那几步
#   pwsh -File tools/lw-install.ps1 -NoLaunch             # 装完不拉起 (调试用)
#
# 权限那一步给的是两类东西, 都只能从 adb 给:
#   - 运行时权限 (`pm grant`): 相机 / 定位 / 媒体读取 / 蓝牙 / 麦克风 / 日历
#   - 特殊访问 (`appops set`): 修改系统设置 / 使用情况访问 / 悬浮窗 / 忽略电池优化
# **失败不报错**: 有的 ROM 不认某条 op, 有的权限在这一版里本来就没声明, 所以逐条打印结果
param(
    [string]$Serial = '',
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Package = 'io.github.yuloong07star.luwi',
    [switch]$Perms,
    [switch]$SkipInstall,
    [switch]$Build,
    [switch]$NoLaunch,
    [int]$WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$adb = 'D:\apk\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

# 参数名不能叫 $Args: 那是 PowerShell 的自动变量, 拿它当参数名会让调用在展开时打转
function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CommandArgs)
    $output = & $adb -s $Serial @CommandArgs 2>&1
    return ($output | Out-String).Trim()
}

# 在线设备 (只要状态是 device 的): 输出一串序列号
function Get-OnlineDevices {
    $lines = & $adb devices 2>&1
    return @($lines | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
}

# 设备怎么挑 (不填 -Serial 时): 真机优先, 只有模拟器就用模拟器
#
# 为什么不是"唯一在线才算": 开发机上模拟器常年开着, 于是每次装真机都要多点一个 -Serial ——
# 一键那条路必须自己挑对
if (-not $Serial) {
    if ($env:ANDROID_SERIAL) {
        $Serial = $env:ANDROID_SERIAL
    } else {
        $online = @(Get-OnlineDevices)
        if ($online.Count -eq 0) {
            throw "没有设备在线: 先 adb connect <手机> 或用 dev.ps1 emu 起模拟器"
        }
        if ($online.Count -eq 1) {
            $Serial = $online[0]
        } else {
            $phones = @($online | Where-Object { $_ -notmatch '^emulator-' })
            if ($phones.Count -eq 1) {
                $Serial = $phones[0]
            } else {
                throw "有 $($online.Count) 台设备在线 ($($online -join ', ')): 用 -Serial 指定, 或设 `$env:ANDROID_SERIAL"
            }
        }
    }
}

# 设备必须在, 而且必须是 device 状态 (unauthorized 会在这里就停下, 而不是等到 install 才失败)
$state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') { throw "device $Serial is $state, not device" }
$named = (& $adb -s $Serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
Write-Host "device $Serial is up ($named)" -ForegroundColor Green

# 要出包就先出包: `-Build` 走 dev.ps1 (它会激活环境并把日志落进 logs\build)
if ($Build) {
    Write-Host "building first (dev.ps1 build) ..." -ForegroundColor Cyan
    & pwsh -NoProfile -File 'D:\apk\dev.ps1' build -Name "install-$(Get-Date -Format 'HHmm')" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "the build failed (see logs\build)" }
}

if (-not (Test-Path $Apk)) { throw "no APK at ${Apk}: 先跑 dev.ps1 build 或加 -Build" }
$full = (Resolve-Path $Apk).Path
$apkAge = [int]((Get-Date) - (Get-Item $full).LastWriteTime).TotalMinutes
Write-Host "APK $([math]::Round((Get-Item $full).Length / 1MB, 1)) MB, built $apkAge min ago" -ForegroundColor Cyan

# 装 : 两条路, 先流式 (HyperOS 只认这一条), 再退"推上去就地装"
$component = "$Package/$Package.channel.LwAccessibility"
$listener = "$Package/$Package.channel.LwNotificationListener"
$wrote = $false
if (-not $SkipInstall) {
    # 判"到底装上了没有"的基准: 设备现在记的 lastUpdateTime。装完它必须变新, 否则就是没装成功 ——
    # `pm install` 失败时会把异常栈打到 stderr, 只匹配 "Failure" 会漏掉 (实测真机上一次就是漏了,
    # 脚本还报了成功)
    $before = (& $adb -s $Serial shell "dumpsys package $Package | grep lastUpdateTime" 2>&1 | Out-String).Trim()

    Write-Host "installing (streamed, with the package-installer identity)" -ForegroundColor Cyan
    $streamed = (& $adb -s $Serial install -r -t -i com.android.packageinstaller $full 2>&1 | Out-String).Trim()
    $okInstall = $streamed -match 'Success'
    if (-not $okInstall) {
        Write-Host "  streamed install did not go through: $($streamed -split "`n" | Select-Object -Last 1)" -ForegroundColor Yellow
        # 次选: 先 push 到设备上再就地装 (大包在慢链路上更快, 而且有些 ROM 只认这一条)
        $remote = '/data/local/tmp/lw-install.apk'
        Write-Host "  falling back to: push + pm install" -ForegroundColor Yellow
        $pushed = (& $adb -s $Serial push $full $remote 2>&1 | Out-String).Trim()
        if ($pushed -match '1 file pushed') {
            $inPlace = (& $adb -s $Serial shell "pm install -r -i com.android.packageinstaller -t $remote" 2>&1 | Out-String).Trim()
            Write-Host "  $inPlace"
            $okInstall = $inPlace -match 'Success'
            & $adb -s $Serial shell "rm -f $remote" | Out-Null
        } else {
            Write-Host "  push failed: $pushed" -ForegroundColor Red
        }
    }
    if (-not $okInstall) {
        Write-Host ""
        Write-Host "装不进去。HyperOS / MIUI 上最常见的一条是开发者选项里的「USB 安装」没开:" -ForegroundColor Red
        Write-Host "  设置 -> 更多设置 -> 开发者选项 -> 「USB 安装」(允许通过 USB 安装应用)" -ForegroundColor Red
        Write-Host "  那个开关有时还要求插着 SIM 且登录小米帐号; 开完再跑一次本脚本即可" -ForegroundColor Red
        exit 2
    }
    Write-Host "installed" -ForegroundColor Green

    # 装完立刻把两条授权写回去 (vivo 上那个写入窗口就在装完这一会儿, 中间别插别的事)
    $script = @"
cur=`$(settings get secure enabled_accessibility_services)
case "`$cur" in
  *"$component"*) echo "ACCESSIBILITY_ALREADY_LISTED" ;;
  *) if [ -z "`$cur" ] || [ "`$cur" = "null" ]; then next="$component"; else next="`$cur:$component"; fi
     settings put secure accessibility_enabled 1
     settings put secure enabled_accessibility_services "`$next"
     echo "ACCESSIBILITY_WRITTEN" ;;
esac
ncur=`$(settings get secure enabled_notification_listeners)
case "`$ncur" in
  *"$listener"*) echo "LISTENER_ALREADY_LISTED" ;;
  *) cmd notification allow_listener $listener >/dev/null 2>&1
     if settings get secure enabled_notification_listeners | grep -qF "$listener"; then
       echo "LISTENER_ALLOWED"
     else
       if [ -z "`$ncur" ] || [ "`$ncur" = "null" ]; then nnext="$listener"; else nnext="`$ncur:$listener"; fi
       settings put secure enabled_notification_listeners "`$nnext"
       echo "LISTENER_WRITTEN"
     fi ;;
esac
"@
    Write-Host "writing the accessibility and notification entries back" -ForegroundColor Cyan
    $installed = ($script | & $adb -s $Serial shell "sh -s" 2>&1 | Out-String).Trim()
    Write-Host $installed

    $after = (& $adb -s $Serial shell "dumpsys package $Package | grep lastUpdateTime" 2>&1 | Out-String).Trim()
    if ($after -eq $before -or $after -eq '') {
        throw "the app was not installed: lastUpdateTime is still '$after' (the install output above says why)"
    }
    Write-Host "installed: $after" -ForegroundColor Green

    # 版本读数: 装的是不是"刚刚那一包", 一眼看这里
    $version = (& $adb -s $Serial shell "dumpsys package $Package | grep -E 'versionName|versionCode' | head -2" 2>&1 | Out-String).Trim()
    Write-Host "on device: $($version -replace '\s+', ' ')" -ForegroundColor Green

    # 判据: 我们那一条有没有留在名单里 (写进去了 / 本来就在, 两种都算留住了)。**判据是名单本身**,
    # 不是自造 key 的探针 —— 探针在这台机器上永远是 null, 拿它当判据只会得出一个假的"没写进去"
    if ($installed -match 'ACCESSIBILITY_(WRITTEN|ALREADY_LISTED)') {
        Write-Host "accessibility written back" -ForegroundColor Green
        $wrote = $true
    } else {
        Write-Host "the device did not keep the accessibility write" -ForegroundColor Red
    }
}

# 拉起应用并等 host 就绪 : 装完不拉起, 设备上就只是"装了个壳" (host 树首启才解压)
#
# 判据是 `dsh web: http://…` 那一行 —— 它晚于 host 树解压、晚于浏览器信任栅栏, 是"能用了"那一刻。
# 只看**这一次启动之后新出的行** (启动前先数一遍), 否则上一轮留下的那一行会让它当场报"好了"
if (-not $NoLaunch) {
    $base = @(& $adb -s $Serial logcat -d -s DshHost 2>&1).Count
    Write-Host "launching the app and waiting for the host ..." -ForegroundColor Cyan
    & $adb -s $Serial shell am start -n "$Package/.MainActivity" 2>&1 | Out-Null
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $ready = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $lines = @(& $adb -s $Serial logcat -d -s DshHost 2>&1)
        if ($lines.Count -le $base) { continue }
        $fresh = @($lines[$base..($lines.Count - 1)]) -join "`n"
        if ($fresh -match 'dsh web: http') { $ready = $fresh; break }
        if ($fresh -match 'failed to import|code could not be extracted|host tree') {
            Write-Host "host 起不来, 上面这段日志是原因:" -ForegroundColor Red
            Write-Host $fresh -ForegroundColor DarkGray
            break
        }
    }
    if ($null -ne $ready) {
        Write-Host "the host is up: $((($ready -split "`n") | Where-Object { $_ -match 'dsh web: http' } | Select-Object -Last 1).Trim())" -ForegroundColor Green
    } elseif ($SkipInstall) {
        Write-Host "没等到 host 就绪行 (可能本来就在跑): $WaitSeconds s" -ForegroundColor Yellow
    } else {
        Write-Host "没等到 host 就绪行 (等了 $WaitSeconds s): 看设备上的 logcat -s DshHost" -ForegroundColor Yellow
    }
}

# 验收 : 两条判据, 缺一条就说清缺哪一条
#
#   1. **我们那一条到底在不在名单里** —— 判据就是 `enabled_accessibility_services` 本身。
#      **自造一个探针 key 不能当判据**: vivo 这台上 SettingsProvider 把不认识的 key 一律拒掉
#      (2026-10-10 实测: 无障碍那一条写得进去, 而探针 key 读回来永远是 null), 于是探针只会给出
#      一个假的"不接受写入", 再拿它解释失败就把人引到"再装一次"那条死路上
#   2. **系统有没有真的绑上** —— 设置里写着不等于服务活着。`dumpsys activity services` 里那条
#      ServiceRecord 才是证据; 它的输出格式各 ROM 略有差别, 所以找不到时只标"未确认"而不判失败
Write-Host ""
Write-Host "checking" -ForegroundColor Cyan

$listed = Adb shell "settings get secure enabled_accessibility_services"
$isListed = ($listed -split ':' | Where-Object { $_ -eq $component }).Count -gt 0
Write-Host "  component listed: $(if ($isListed) { 'yes' } else { 'no' })" -ForegroundColor $(if ($isListed) { 'Green' } else { 'Red' })

# 通知使用权与无障碍走同一条路 (但**接口不同**): 它由 `cmd notification allow_listener` 给, 而那条
# 命令与设置页上"允许"按钮走的是同一条路 —— 直写 `enabled_notification_listeners` 在 Android 14 上
# 实测**不够**: 值写进去了、设备也留着, 而系统不看它, 服务不会被绑上
$listeners = Adb shell "settings get secure enabled_notification_listeners"
$listenerListed = ($listeners -split ':' | Where-Object { $_ -eq $listener }).Count -gt 0
Write-Host "  listener listed: $(if ($listenerListed) { 'yes' } else { 'no' })" -ForegroundColor $(if ($listenerListed) { 'Green' } else { 'Red' })

$services = Adb shell "dumpsys activity services $Package"
$bound = $services -match 'LwAccessibility'
if ($bound) {
    Write-Host "  service bound: yes (there is an LwAccessibility record)" -ForegroundColor Green
} else {
    Write-Host "  service bound: not confirmed (no LwAccessibility record in dumpsys activity services)" -ForegroundColor Yellow
}
$listenerBound = $services -match 'LwNotificationListener'
if ($listenerBound) {
    Write-Host "  listener bound: yes (there is an LwNotificationListener record)" -ForegroundColor Green
} else {
    Write-Host "  listener bound: not confirmed" -ForegroundColor Yellow
}

# 什么时候算过: 组件在列表里、而且系统真的绑着。写入通路不通时这两条都不可能成立, 所以要分开说。
# 通知使用权一起算进来: 它是 lw_notifications 的前提, 而"这条没给上"正是装完就该知道的事
$ok = $isListed -and $bound -and $listenerListed
Write-Host ""
if ($ok) {
    Write-Host "acceptance: accessibility is listed and bound, notification access is listed" -ForegroundColor Green
    Write-Host "  重启手机之后再确认一次: adb -s $Serial shell dumpsys activity services $Package"
} else {
    Write-Host "acceptance: not done yet" -ForegroundColor Red
    if (-not $isListed -or -not $listenerListed) {
        Write-Host "  名单里没有我们那一条: 写进去之后被系统当场摘了。2026-10-10 在 vivo 上实测的先后是:" -ForegroundColor Red
        Write-Host "    装完那一刻、以及之后两分钟里单独写, 都留不住; 这道 op 读出来是 default 带一个新鲜的 rejectTime" -ForegroundColor Red
        Write-Host "      adb -s $Serial shell appops get $Package ACCESS_RESTRICTED_SETTINGS"
        Write-Host "    给它 allow、再把组件接回名单尾巴, 那一条才留住 (现象可复现, 机制没往下追)" -ForegroundColor Red
        Write-Host "      adb -s $Serial shell appops set $Package ACCESS_RESTRICTED_SETTINGS allow"
        Write-Host "      adb -s $Serial shell settings put secure enabled_accessibility_services '<现有名单>:$component'"
    } else {
        Write-Host "  组件在名单里但系统没绑上: 用应用内那个开关 (它会先摘掉、等一下、再放回," -ForegroundColor Red
        Write-Host "  那一下是让系统重新评估), 或者重启手机。" -ForegroundColor Red
    }
}

# 权限 : 运行时权限用 pm grant, 特殊访问用 appops
if ($Perms) {
    $runtime = @(
        'android.permission.CAMERA',
        'android.permission.RECORD_AUDIO',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.READ_MEDIA_IMAGES',
        'android.permission.READ_MEDIA_VIDEO',
        'android.permission.READ_MEDIA_AUDIO',
        'android.permission.BLUETOOTH_CONNECT',
        'android.permission.BLUETOOTH_SCAN',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.ACTIVITY_RECOGNITION',
        'android.permission.BODY_SENSORS',
        # 2.5.0 批次 7: lw_calendar 读日程与建日程要这两条
        'android.permission.READ_CALENDAR',
        'android.permission.WRITE_CALENDAR'
    )
    foreach ($permission in $runtime) {
        $answer = Adb shell "pm grant $Package $permission"
        $short = $permission -replace '^android\.permission\.', ''
        if ($answer -eq '') {
            Write-Host "  granted $short" -ForegroundColor Green
        } else {
            Write-Host "  $short : $answer" -ForegroundColor DarkGray
        }
    }

    # 特殊访问那几条走 appops: 应用侧查的是同一个 op, 所以这里设了它就显示"已允许"
    #
    # MANAGE_EXTERNAL_STORAGE 在这张表里是**功能性的**而不是顺带: 它决定工作区落在 /sdcard/DSH
    # 还是退到 Android/media 或沙盒, 而 2026-10-06 设置页撤掉「工作区」段之后就没有那个按钮了
    $ops = @(
        'WRITE_SETTINGS',
        'GET_USAGE_STATS',
        'SYSTEM_ALERT_WINDOW',
        'REQUEST_INSTALL_PACKAGES',
        'MANAGE_EXTERNAL_STORAGE'
    )
    foreach ($op in $ops) {
        Adb shell "appops set $Package $op allow" | Out-Null
        $kept = Adb shell "appops get $Package $op"
        Write-Host "  $op -> $kept"
    }
    # 忽略电池优化: host 要一直活着, 不然系统会在后台把它冻住
    Adb shell "dumpsys deviceidle whitelist +$Package" | Out-Null
    Write-Host "  battery optimisation: whitelisted"
}

Write-Host ""
Write-Host "装完别再用 am start -S 强停" -ForegroundColor Yellow
Write-Host "  强停会把系统那个无障碍绑定实例摘掉, 而**条目还留着** —— 现象是"设置里写着开着、服务却没绑上": "
Write-Host "  上面那条 service bound 判据随后就会变成 0, 读屏与事件订阅静默失效。拉动应用请用不带 -S 的:"
Write-Host "    adb -s $Serial shell am start -n $Package/.MainActivity"
Write-Host "  真被强停了: 恢复要走"摘掉 -> 停 800ms -> 放回"那套 (设置页那个开关自己会做), 而它只在重装打开的"
Write-Host "  写入窗口里写得动 —— 本脚本也只在**条目缺失**时才写, 遇到"条目在而实例没了"它什么都不做"

Write-Host ""
Write-Host "剩下这些只能人去系统页里点 (脚本给不了):" -ForegroundColor Cyan
Write-Host "  - 全屏通知 (横幅): 设置 -> 应用 -> Luwi -> 特殊应用权限里那一条, lw_notify 的 banner 靠它"
Write-Host "  - 录屏授权 (以后要用到时再说)"
Write-Host "  - 桌面图标与通知栏图标现在是 Luwi 自己那个标记: 一圈水花里一只鲸尾 (见 tools/make-icons.py)"

# 退出码: 只看**这一次要它做的事**成没成, 免得 `-Perms` 因为无障碍那一条而报失败
#
#   - 带了 `-Perms`: 权限那一段每一批都发过一遍就算成功 (它们是逐个打印的, 失败自己会说话)
#   - 没带 `-Perms` 且做了安装: 按无障碍那两条判据判
#   - `-SkipInstall` 又没带 `-Perms`: 按无障碍判据判
if ($Perms) { exit 0 }
if ($ok) { exit 0 } else { exit 1 }
