# 往设备上的语音队列投一句 (真机验收用: 让设备上的模型真的去调 lw_* 工具)
#
# 为什么需要它: 验收"切模式 / 换模型"这类事要走**设备自己的模型与通道**, 而人在手机前面按语音是
# 一次性的、量不了第二遍。这个脚本把那一句写进宿主一直在盯的那份队列
# (`$DSH_HOME/voice/inbox.jsonl`, 150 ms 一拍), 于是"喊一句"这件事变成可重复、可脚本化的一次投递
#
# 两条要注意的:
#   · **`seq` 必须比 `$DSH_HOME/voice/inbox.cursor` 大** (宿主按它去重, 只投递比游标大的行);
#     先 `adb shell run-as <pkg> cat files/dsh-home/voice/inbox.cursor` 看一眼现在到几了
#   · 一句话的正文会被 [SCREEN_REF_WORDS] 那一套判一次"是不是指着东西说" —— 带了"这个 / 屏幕上"
#     这类词会顺带附一张主屏截图, 验别的事情时避开它们
#
# 用法:
#   pwsh -File tools\lw-voice-line.ps1 -Seq 7 -Text "调用 lw_mode 把模式切到 video" [-WaitSeconds 45]
#   $env:ANDROID_SERIAL=emulator-5554; pwsh -File tools\lw-voice-line.ps1 -Seq 3 -Text "你好"
#   pwsh -File tools\lw-voice-line.ps1 -Seq 9 -Text "自由探索" -Source explore   # 开一场探索
#
# **`-Source explore` 那条是「探索走收件箱进来」唯一的可脚本化入口** (2.7.8 批次 7): 宿主看到
# `source:"explore"` 就不再把它当一句话, 而是照 `startExplore` 开一场探索 (预算缺省 30 分钟 / 60 步)。
# 其余来源 (voice / keyboard / ball / quick / automation / automation-setup) 一律按原来的投递走
param(
    [Parameter(Mandatory = $true)][int]$Seq,
    [Parameter(Mandatory = $true)][string]$Text,
    [string]$Source = 'voice',
    [string]$Serial,
    [string]$Package = 'io.github.yuloong07star.luwi',
    [int]$WaitSeconds = 0
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not $Serial) {
    if ($env:ANDROID_SERIAL) {
        $Serial = $env:ANDROID_SERIAL
    } else {
        $online = (& $adb devices | Select-String -Pattern '\sdevice$').Line -replace '\s+device$', ''
        if (@($online).Count -ne 1) { throw "有 $(@($online).Count) 台设备在线: 用 -Serial 指定, 或设 `$env:ANDROID_SERIAL" }
        $Serial = $online
    }
}

# 一行 JSONL: `text` 交给 ConvertTo-Json 转义 (中文与引号都安全), 写盘用无 BOM 的 UTF-8
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$line = '{"seq":' + $Seq + ',"at":' + $now + ',"text":' + (ConvertTo-Json $Text -Compress) + ',"source":"' + $Source + '"}' + "`n"
$local = Join-Path ([System.IO.Path]::GetTempPath()) "lw-voice-line-$Seq.jsonl"
[System.IO.File]::WriteAllText($local, $line, (New-Object System.Text.UTF8Encoding($false)))

$remote = "/data/local/tmp/lw-voice-line-$Seq.jsonl"
& $adb -s $Serial push $local $remote | Out-Null
# 追加而不是覆盖: 那份队列是宿主的账, 本体 `run-as` 才写得进去 (app 的私有目录)
$appended = & $adb -s $Serial shell "run-as $Package sh -c 'cat $remote >> files/dsh-home/voice/inbox.jsonl && echo APPENDED && rm -f $remote'"
Remove-Item $local -ErrorAction SilentlyContinue
if ("$appended" -notmatch 'APPENDED') { throw "append failed: $appended" }
Write-Host "seq $Seq appended to the voice inbox: $Text"
if ($WaitSeconds -gt 0) { Start-Sleep -Seconds $WaitSeconds }
