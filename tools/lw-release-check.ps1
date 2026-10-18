<#
.SYNOPSIS
  Luwi 发布前的验签闸

.DESCRIPTION
  发版只认 :app:assembleDebug 出来的 app-debug.apk, 而且上传前必须先过这一道:
    - 文件名必须是 app-debug.apk (release 变体不进发布流程)
    - apksigner verify 必须通过 (v2 / v3 签名在)
    - 证书必须是项目那把固定的 debug key (D:\apk\.android\debug.keystore)
  任何一条不过就以非零退出码结束, 发布 / 上传那一步看见非零就不许传.

.PARAMETER Apk
  要验的包, 默认 app\build\outputs\apk\debug\app-debug.apk

.PARAMETER AnyName
  只做诊断时用: 跳过文件名检查 (仍然验签与核对证书), 例如验一份归档包

.EXAMPLE
  pwsh -File tools\lw-release-check.ps1

.EXAMPLE
  pwsh -File tools\lw-release-check.ps1 -Apk D:\apk\archive\releases\Luwi-2.7.7.apk -AnyName
#>
param(
    [string]$Apk = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'),
    [switch]$AnyName
)

$ErrorActionPreference = 'Stop'

# 项目固定的那把 key, 换 key = 所有人卸载重装 (理由见 app/build.gradle.kts 里那段注释)
$ExpectedCertSha256 = '652084a902c50f95bf71e6ce6cd0bad8fa5a9b231a146f99016bc17265c97359'

function Fail([string]$Message, [int]$Code = 1) {
    Write-Host "FAIL  $Message" -ForegroundColor Red
    exit $Code
}

if (-not (Test-Path -LiteralPath $Apk)) {
    Fail "找不到 APK: $Apk (用 -Apk 指一个路径)"
}
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$item = Get-Item -LiteralPath $Apk

# 1. 名字: 发版只认 app-debug.apk
if (-not $AnyName -and $item.Name -ne 'app-debug.apk') {
    Fail "只发 app-debug.apk, 这个是 $($item.Name) (release 变体不进发布流程)"
}

# 2. 工具链: apksigner 要 JAVA_HOME, 没设就先激活工作区环境
if (-not $env:JAVA_HOME) {
    $envScript = 'D:\apk\env.ps1'
    if (Test-Path $envScript) { . $envScript | Out-Null }
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\apk\Sdk' }
$buildToolsDir = Join-Path $sdk 'build-tools'
if (-not (Test-Path $buildToolsDir)) { Fail "找不到 build-tools: $buildToolsDir" 2 }
$buildTools = Get-ChildItem $buildToolsDir -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path $apksigner)) { Fail "找不到 apksigner: $apksigner" 2 }

# 3. 验签
$output = & $apksigner verify --verbose --print-certs $Apk 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host $output
    Fail 'apksigner 验签不通过 —— 这个包不能发'
}

$v2 = $output -match 'APK Signature Scheme v2\): true'
$v3 = $output -match 'APK Signature Scheme v3\): true'
if (-not ($v2 -or $v3)) {
    Write-Host $output
    Fail 'v2 / v3 签名都没有'
}

$certLine = ($output -split "`r?`n" |
    Where-Object { $_ -match 'certificate SHA-256 digest:' } |
    Select-Object -First 1)
if (-not $certLine) { Fail '证书里没有 SHA-256 摘要' }
$certSha = ($certLine -replace '.*certificate SHA-256 digest:\s*', '').Trim().ToLowerInvariant()
if ($certSha -ne $ExpectedCertSha256) {
    Fail "证书不是项目那把 debug key: $certSha (应为 $ExpectedCertSha256; 换 key 等于所有人卸载重装)"
}

$schemes = @()
if ($v2) { $schemes += 'v2' }
if ($v3) { $schemes += 'v3' }
$sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host 'PASS  可以发' -ForegroundColor Green
Write-Host "  APK      $Apk"
Write-Host ("  大小     {0:N0} 字节 ({1:N1} MiB)" -f $item.Length, ($item.Length / 1MB))
Write-Host "  SHA-256  $sha256"
Write-Host "  签名     固定 debug key $($certSha.Substring(0, 8))… ($($schemes -join '/'))"
exit 0
