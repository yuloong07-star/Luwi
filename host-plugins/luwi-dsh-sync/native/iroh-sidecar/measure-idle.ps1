# T1/J15 的常驻读数: 起一个 sidecar, 空转 N 秒, 量 CPU (单核百分比) 与 RSS
# 用法: pwsh -File measure-idle.ps1 [-Seconds 600] [-Exe ..\win32-x64\lwiroh.exe]
param(
  [int]$Seconds = 600,
  [string]$Exe = (Join-Path $PSScriptRoot '..\win32-x64\lwiroh.exe')
)

$token = 'idle-probe-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:LW_TOKEN = $token
$env:LW_PORT = '0'
$env:LW_DATA_PORT = '0'
$env:LW_IDLE_MS = '0'
$env:LW_ONLINE_MS = '15000'

$stdout = Join-Path $env:TEMP ('lwiroh-idle-' + $token + '.out')
$stderr = Join-Path $env:TEMP ('lwiroh-idle-' + $token + '.err')
$exePath = (Resolve-Path $Exe).Path
$started = Get-Date
$process = Start-Process -FilePath $exePath -ArgumentList 'daemon' -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr

try {
  $port = $null
  for ($i = 0; $i -lt 150; $i++) {
    Start-Sleep -Milliseconds 200
    if (Test-Path $stdout) {
      $line = Get-Content $stdout -ErrorAction SilentlyContinue | Where-Object { $_ -like 'READY *' } | Select-Object -Last 1
      if ($line) {
        $port = [int](($line -split ' ')[1] -replace '^port=', '')
        break
      }
    }
  }
  if (-not $port) { throw "no READY line; stderr: $(Get-Content $stderr -Raw -ErrorAction SilentlyContinue)" }
  Write-Output "READY port=$port pid=$($process.Id) exe=$exePath"

  # 预热 2 s 再取基线, 避开 online / relay 握手
  Start-Sleep -Seconds 2
  $process.Refresh()
  $cpu0 = $process.TotalProcessorTime.TotalSeconds
  $mem0 = $process.WorkingSet64
  $wall0 = Get-Date
  Write-Output ("baseline cpu={0:N3}s rss={1:N1}MiB at {2:HH:mm:ss}" -f $cpu0, ($mem0 / 1MB), $wall0)

  Start-Sleep -Seconds $Seconds

  $process.Refresh()
  $cpu1 = $process.TotalProcessorTime.TotalSeconds
  $mem1 = $process.WorkingSet64
  $wall = ((Get-Date) - $wall0).TotalSeconds
  $oneCore = ($cpu1 - $cpu0) / $wall * 100.0
  Write-Output ("idle {0:N0}s: cpu={1:N3}s delta={2:N3}s => {3:N2}% of one core; rss {4:N1}MiB -> {5:N1}MiB" -f $wall, $cpu1, ($cpu1 - $cpu0), $oneCore, ($mem0 / 1MB), ($mem1 / 1MB))
}
finally {
  try {
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/v1/shutdown" -Headers @{ authorization = "Bearer $token" } -TimeoutSec 5 | Out-Null
  } catch {
    Write-Output "shutdown request failed: $_"
  }
  Start-Sleep -Seconds 1
  if (-not $process.HasExited) { $process.Kill() }
  Remove-Item $stdout, $stderr -ErrorAction SilentlyContinue
}
