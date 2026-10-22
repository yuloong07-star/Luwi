# 真机常驻读数 (J15 的输入): 把 arm64 sidecar 放到 /data/local/tmp 空转 N 秒, 量单核占用与 RSS
# 用法: pwsh -File measure-idle-android.ps1 [-Seconds 600] [-Serial <adb 序列号>]
param(
  [int]$Seconds = 600,
  [string]$Serial = ''
)

$adb = 'D:\apk\Sdk\platform-tools\adb.exe'
$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$binary = Join-Path $PSScriptRoot '..\..\..\..\app\src\main\jniLibs\arm64-v8a\liblwiroh.so'
$remote = '/data/local/tmp/liblwiroh-v2.so'

& $adb @deviceArgs push $binary $remote | Out-Null
& $adb @deviceArgs shell "chmod 755 $remote; rm -f /data/local/tmp/lwiroh-idle.log" | Out-Null

# nohup + & 让它在 adb shell 退出后继续跑; pid 从 ps 里认
& $adb @deviceArgs shell "cd /data/local/tmp; LW_TOKEN=idle-probe LW_PORT=0 LW_DATA_PORT=0 LW_INBOUND=0 LW_IDLE_MS=0 LW_ONLINE_MS=15000 nohup ./liblwiroh-v2.so daemon > /data/local/tmp/lwiroh-idle.log 2>&1 &" | Out-Null
Start-Sleep -Seconds 5
$pidText = (& $adb @deviceArgs shell "pgrep -f 'liblwiroh-v2.so daemon' | head -1").Trim()
if (-not $pidText) { throw "sidecar did not stay alive: $(& $adb @deviceArgs shell 'cat /data/local/tmp/lwiroh-idle.log')" }
$sidecarPid = [int]$pidText
Write-Output "sidecar pid=$sidecarPid on $(& $adb @deviceArgs shell getprop ro.product.model)"

function Read-CpuTicks([int]$target) {
  $stat = (& $adb @deviceArgs shell "cat /proc/$target/stat").Trim()
  $fields = $stat -split '\s+'
  return [int64]$fields[13] + [int64]$fields[14]   # utime + stime, 单位 USER_HZ (通常 100)
}
function Read-RssKb([int]$target) {
  $status = & $adb @deviceArgs shell "cat /proc/$target/status"
  $line = $status | Where-Object { $_ -like 'VmRSS:*' } | Select-Object -First 1
  if (-not $line) { return 0 }
  return [int](($line -split '\s+')[1])
}

Start-Sleep -Seconds 2
$ticks0 = Read-CpuTicks $sidecarPid
$rss0 = Read-RssKb $sidecarPid
$wall0 = Get-Date
Write-Output ("baseline ticks={0} rss={1}KiB" -f $ticks0, $rss0)

Start-Sleep -Seconds $Seconds

$ticks1 = Read-CpuTicks $sidecarPid
$rss1 = Read-RssKb $sidecarPid
$wall = ((Get-Date) - $wall0).TotalSeconds
$hz = 100.0
$cpuSeconds = ($ticks1 - $ticks0) / $hz
Write-Output ("idle {0:N0}s: ticks {1} -> {2}, cpu={3:N3}s => {4:N2}% of one core; rss {5}KiB -> {6}KiB" -f $wall, $ticks0, $ticks1, $cpuSeconds, ($cpuSeconds / $wall * 100.0), $rss0, $rss1)
Write-Output '--- log'
& $adb @deviceArgs shell "cat /data/local/tmp/lwiroh-idle.log"

& $adb @deviceArgs shell "kill $sidecarPid" | Out-Null
