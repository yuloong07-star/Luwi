# 从 Termux 的 deb 出随包那一套 proot (归一命名 + ELF 改写), 落进 jniLibs
#
# 为什么必须归一: AGP 会静默丢掉带点的 native lib 名 (`libtalloc.so.2` 那种进不了 APK), 所以四个
# 文件都改成 `liblw*.so` 的形制, **同时**把动态段里的字符串一起改掉 —— 只改文件名会让 proot 满世界
# 找 `libtalloc.so.2`, 而 RUNPATH 里写死的 Termux 路径同理要换成 `$ORIGIN` (让加载器在这个对象
# 自己所在的目录里找, 也就是 nativeLibraryDir). ELF 那一半由 tools/lwlinux-elf.py 做
#
# 用法 (先 `. D:\apk\env.ps1`):
#   pwsh -File tools\lwlinux-prepare.ps1 -Packages D:\apk\.lwtmp\lwlinux-build
#
# 产物: liblwproot.so / liblwprootloader.so / liblwtalloc.so / liblwshmem.so
# 默认落进 `app\src\main\jniLibs\arm64-v8a` (产物提交进仓库), 中间产物在 `build\lwlinux-prepare`
#
# 输入 (Termux 包, 从 packages.termux.dev 取; P0 用的那一套留在 D:\apk\.lwtmp\lwlinux-build):
#   proot_*.deb / libtalloc_*.deb / libandroid-shmem_*.deb, 按 -Abi 取 aarch64 / x86_64 那一份
#
# 跑完照 P0 回执的判据验一次: `llvm-readelf -d liblwproot.so` 里 NEEDED 是 liblwtalloc.so 与
# liblwshmem.so, RUNPATH 是 `$ORIGIN` —— 这一串就是"在设备上能起来"的那条链

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Packages,
    [ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a',
    [string]$Out,
    [string]$Work,
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$termux = if ($Abi -eq 'arm64-v8a') { 'aarch64' } else { 'x86_64' }
if (-not $Out) { $Out = Join-Path $repo "app\src\main\jniLibs\$Abi" }
if (-not $Work) { $Work = Join-Path $repo 'build\lwlinux-prepare' }
$elf = Join-Path $PSScriptRoot 'lwlinux-elf.py'

# 一个 deb 里的源路径 -> 最终名字, 外加要改的动态段字符串 (loader 是静态的, 没有动态段可改)
$specs = @(
    @{
        Deb = 'proot'
        From = 'data/data/com.termux/files/usr/bin/proot'
        To = 'liblwproot.so'
        Patch = @(
            '--needed', 'libtalloc.so.2=liblwtalloc.so',
            '--needed', 'libandroid-shmem.so=liblwshmem.so',
            '--runpath', '$ORIGIN'
        )
    },
    @{
        Deb = 'proot'
        From = 'data/data/com.termux/files/usr/libexec/proot/loader'
        To = 'liblwprootloader.so'
        Patch = @()
    },
    @{
        Deb = 'libtalloc'
        From = 'data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0'
        To = 'liblwtalloc.so'
        Patch = @('--soname', 'liblwtalloc.so', '--runpath', '$ORIGIN')
    },
    @{
        Deb = 'libandroid-shmem'
        From = 'data/data/com.termux/files/usr/lib/libandroid-shmem.so'
        To = 'liblwshmem.so'
        Patch = @('--soname', 'liblwshmem.so', '--runpath', '$ORIGIN')
    }
)

function Expand-Deb([string]$deb, [string]$into) {
    # 只清这个脚本自己那块中间目录 (在 $Work 下), 不做任何 $Work 之外的删除
    if (Test-Path -LiteralPath $into) { Remove-Item -LiteralPath $into -Recurse -Force }
    New-Item -ItemType Directory -Path $into | Out-Null
    & tar -xf $deb -C $into
    if ($LASTEXITCODE -ne 0) { throw "could not unpack $deb" }
    # data.tar.xz 里是 Termux 前缀下的一整棵树, 而里面有些符号链接 Windows 建不出来 —— 整棵解
    # bsdtar 会以 "Invalid argument" 退出, 所以下面按需要点名取那四个成员
    if (-not (Test-Path -LiteralPath (Join-Path $into 'data.tar.xz'))) {
        throw "$deb carries no data.tar.xz"
    }
}

New-Item -ItemType Directory -Path $Out -Force | Out-Null
New-Item -ItemType Directory -Path $Work -Force | Out-Null

$done = @{}
foreach ($spec in $specs) {
    if (-not $done.ContainsKey($spec.Deb)) {
        $deb = Get-ChildItem -LiteralPath $Packages -Filter "$($spec.Deb)_*_$termux.deb" |
            Select-Object -First 1
        if ($null -eq $deb) {
            throw "no $($spec.Deb)_*_$termux.deb under $Packages"
        }
        $into = Join-Path $Work $spec.Deb
        Expand-Deb $deb.FullName $into
        $done[$spec.Deb] = $into
    }
    $into = $done[$spec.Deb]
    $source = Join-Path $into $spec.From
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        & tar -xf (Join-Path $into 'data.tar.xz') -C $into "./$($spec.From)"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "$($spec.From) is not inside $($spec.Deb)"
        }
    }
    $target = Join-Path $Out $spec.To
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ($spec.Patch.Count -gt 0) {
        & $Python $elf $target @($spec.Patch)
        if ($LASTEXITCODE -ne 0) { throw "lwlinux-elf.py failed on $target" }
    }
    Write-Output ("{0}  {1} bytes" -f $spec.To, (Get-Item -LiteralPath $target).Length)
}

Write-Output ''
Write-Output 'sha256:'
Get-ChildItem -LiteralPath $Out -Filter 'liblw*.so' |
    Where-Object Name -in $specs.To |
    Get-FileHash -Algorithm SHA256 |
    ForEach-Object { Write-Output ("  {0}  {1}" -f $_.Hash.ToLower(), (Split-Path -Leaf $_.Path)) }
