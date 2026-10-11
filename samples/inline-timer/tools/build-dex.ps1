# 从 Java 源码编出 `plugin/main.dex`
#
# 三步:
#   1. 先让 Gradle 把 `:lwplugin-api` 打成 AAR, 取出里面的 classes.jar (插件对着它编译)
#   2. javac 把 `src/` 编成 class (classpath = android.jar + 那份 classes.jar)
#   3. d8 只把**插件自己的那些类**dex 出来 —— API 那些类不进 dex, 运行时由宿主 (Luwi 自己) 提供
#
# 用法 (在本目录下):  pwsh -File tools\build-dex.ps1
param(
    [string]$Out = ""
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
$module = Split-Path -Parent $here
$repo = Split-Path -Parent (Split-Path -Parent $module)

$sdk = $env:ANDROID_HOME
if (-not $sdk) { throw 'ANDROID_HOME 没设置: 先 . D:\apk\env.ps1 (或者用 dev.ps1)' }
$androidJar = Join-Path $sdk 'platforms\android-37.0\android.jar'
$d8 = Join-Path $sdk 'build-tools\36.0.0\d8.bat'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
foreach ($tool in @($androidJar, $d8, $javac)) {
    if (-not (Test-Path $tool)) { throw "缺工具: $tool" }
}
if (-not $Out) { $Out = Join-Path $module 'plugin\main.dex' }

# 1. API 那一份 classes.jar
Push-Location $repo
& (Join-Path $repo 'gradlew.bat') -q ':lwplugin-api:assembleDebug'
Pop-Location
$aar = Join-Path $repo 'lwplugin-api\build\outputs\aar\lwplugin-api-debug.aar'
if (-not (Test-Path $aar)) { throw "没编出 $aar" }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("lw-inline-timer-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp, (Join-Path $tmp 'api'), (Join-Path $tmp 'plugin') | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($aar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if (-not $entry) { throw "$aar 里没有 classes.jar" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $tmp 'api\classes.jar'), $true)
    } finally {
        $zip.Dispose()
    }
    $apiJar = Join-Path $tmp 'api\classes.jar'

    # 2. 编插件本体
    $sources = Get-ChildItem -Recurse -LiteralPath (Join-Path $module 'src') -Filter *.java | ForEach-Object { $_.FullName }
    if (-not $sources) { throw 'src/ 下一份 Java 都没有' }
    # `--release 17`: 这条工具链上的 javac 是 JDK 25, 不压版本的 class 文件 (major 69) d8 读不动
    & $javac --release 17 -encoding UTF-8 -classpath "$androidJar;$apiJar" -d (Join-Path $tmp 'plugin') $sources
    if ($LASTEXITCODE -ne 0) { throw 'javac 没过' }

    # 3. dex: 只喂插件自己的类 (API 那一边当 --classpath, 不进产物)
    $classes = Get-ChildItem -Recurse -LiteralPath (Join-Path $tmp 'plugin') -Filter *.class | ForEach-Object { $_.FullName }
    $dexOut = Join-Path $tmp 'dex'
    New-Item -ItemType Directory -Force -Path $dexOut | Out-Null
    & $d8 --release --min-api 33 --lib $androidJar --classpath $apiJar --output $dexOut $classes
    if ($LASTEXITCODE -ne 0) { throw 'd8 没过' }

    Copy-Item -LiteralPath (Join-Path $tmp 'dex\classes.dex') -Destination $Out -Force
    $size = (Get-Item $Out).Length
    Write-Host ("main.dex 出来了: {0} ({1} KB)" -f $Out, [math]::Round($size / 1KB, 1))
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
