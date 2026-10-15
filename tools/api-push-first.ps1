# api-push-first.ps1 -- 把本地一棵**全新的**历史用 API 补种到一个刚建好的 GitHub 仓库上
#
# 为什么需要它: 本机 `github.com:443` 被 SNI 挡住 (记录在 Luwi\tools\lw-api-push.ps1 的文件头),
# 而 `api.github.com` 通 —— 所以走 gh 的 REST: 先建 blob -> 建 tree -> 建 commit -> 最后才动 ref。
#
# **空仓库不能建 blob** (实测 409 "Git Repository is empty"): GitHub 的 Git Data API 要有 ref 才肯
# 收对象。所以先在仓库里建一个初始提交 (Contents API 写一个 README 就会把 main 建出来), 再让本地
# HEAD 挂在那个提交**下面** —— 于是树是本地那棵全新的树 (没有 base_tree), 父是远端那个初始提交。
#
# 每一个对象都与本地算出的 SHA 逐字节比对, 任何一处不对就中止, 远端一个字不改。
#
# 用法:
#   pwsh -File api-push-first.ps1 -RepoRoot <目录> -Repo <owner/name> -Parent <远端初始提交>
#   ... 加 -Apply 才真的移动 main 并打 tag
param(
    [Parameter(Mandatory = $true)][string]$RepoRoot,
    [Parameter(Mandatory = $true)][string]$Repo,
    # 挂在这个父提交下面。**-NoParent 时不用给**: 那种情况推的是"根提交"本身
    [string]$Parent = '',
    # 把远端 main 直接指到本地这个提交 (SHA 会与本地**完全一致**)
    #
    # 为什么值得这么做: 先用 Contents API 建占位提交、再把本地提交挂在它下面, 那样远端 main 的 SHA
    # 与本地不同, 而本机 `github.com:443` 被挡 —— **那个远端提交根本 fetch 不下来**, 于是以后想发新
    # 版本时 `lw-api-push.ps1` 算不出基线 (它要 `git rev-parse <RemoteRef>`)。根提交那一路没有这个
    # 问题: 远端与本地逐字节同一个对象, 基线就在本地手里
    [switch]$NoParent,
    [string]$Branch = 'main',
    [string]$Tag = '',
    [string]$Gh = 'D:\Codex\gh-cli\gh.exe',
    [switch]$Apply
)
if (-not $NoParent -and $Parent -eq '') { throw 'give -Parent <sha> or -NoParent' }

$ErrorActionPreference = 'Stop'
Set-Location $RepoRoot
$tmp = 'D:\apk\.lwtmp\apipush-first'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$jsonFile = Join-Path $tmp 'body.json'
# **不带 BOM** (BOM 会让 GitHub 说 "Problems parsing JSON")
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Api([string[]]$argv) { (& $Gh @argv) }

$commit = (git rev-parse HEAD).Trim()
$tree = (git rev-parse 'HEAD^{tree}').Trim()
Write-Host "replaying $($commit.Substring(0,7)) (first commit: no baseline)" -ForegroundColor Cyan

# **重跑要便宜** (2026-10-15 在 VPN 上实测到的): 对象一旦建成就留在远端了, 只是还没有 ref 指着它,
# 而网络被掐断通常发生在"上传第 N 个 blob"的中途 —— 那时 HEAD 这个提交在远端**已经存在**,
# 于是先问一句, 在就整段上传都不做, 直接去动 ref (这一条也是 -Apply 断了之后能重跑的前提)
$already = (& $Gh api "repos/$Repo/git/commits/$commit" --jq '.sha' 2>$null)
if ($LASTEXITCODE -eq 0 -and "$already".Trim() -eq $commit) {
    Write-Host "  $($commit.Substring(0,7)) is already on the remote - skipping the upload" -ForegroundColor Green
    $seeded = $commit
} else {

# --- 1. 全树递归, 逐条上传 blob (命令行走文件, 免得管道按控制台编码转中文文件名) ---
$entries = @()
$listing = @(git -c core.quotePath=false ls-tree -r -t --full-tree HEAD)
$blobCount = 0
foreach ($line in $listing) {
    if ($line -notmatch '^(\d+) (blob|tree) ([0-9a-f]+)\s+(.+)$') { throw "unparsed ls-tree line: $line" }
    $mode = $Matches[1]; $type = $Matches[2]; $sha = $Matches[3]; $path = $Matches[4]
    if ($type -eq 'tree') { continue }   # 目录由路径隐式建出来, 不用单独上传
    $objFile = Join-Path $tmp 'blob.bin'
    cmd /c "git cat-file blob $sha > `"$objFile`"" | Out-Null
    $body = @{
        content  = [Convert]::ToBase64String([IO.File]::ReadAllBytes($objFile))
        encoding = 'base64'
    } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($jsonFile, $body, $utf8)
    $created = (Api @('api', '-X', 'POST', "repos/$Repo/git/blobs", '--input', $jsonFile, '--jq', '.sha')).Trim()
    if ($created -ne $sha) { throw "blob mismatch for $path : api=$created local=$sha" }
    $entries += @{ path = $path; mode = $mode; type = 'blob'; sha = $sha }
    $blobCount++
}
Write-Host "  $blobCount blob(s) uploaded, all SHAs match" -ForegroundColor Green

# --- 2. 建树 (没有 base_tree: 这是一棵全新的树) ---
$body = @{ tree = $entries } | ConvertTo-Json -Depth 6 -Compress
[IO.File]::WriteAllText($jsonFile, $body, $utf8)
$newTree = (Api @('api', '-X', 'POST', "repos/$Repo/git/trees", '--input', $jsonFile, '--jq', '.sha')).Trim()
if ($newTree -ne $tree) { throw "tree mismatch: api=$newTree local=$tree" }
Write-Host "  tree $tree matches" -ForegroundColor Green

# --- 3. 提交对象 ---
#
# **这里与 lw-api-push.ps1 有一处本质差别**: 那一份是"历史重放", 每个提交对象的 SHA 都要逐字节复刻;
# 这一份是"补种" —— 本地 HEAD 的父与远端那个初始提交不是同一个, 所以新提交的 SHA 必然不等于本地
# 那个。于是判据改成三条必须成立的: **树一致**(内容就是本地那份)、**父就是远端那个初始提交**、
# **正文一致**。三者对上就等于"本地这一棵树挂在了远端 main 下面"。
$objFile = Join-Path $tmp 'commit.obj'
cmd /c "git cat-file commit $commit > `"$objFile`"" | Out-Null
$obj = [IO.File]::ReadAllText($objFile, [Text.Encoding]::UTF8)
$split = $obj.IndexOf("`n`n")
if ($split -lt 0) { throw 'no header/body split' }
$header = $obj.Substring(0, $split)
$message = $obj.Substring($split + 2)
function Stamp([string]$line) {
    if ($line -notmatch '^(?:author|committer) (.+) <([^>]*)> (\d+) ([+-]\d{4})$') { throw "unparsable: $line" }
    $secs = [long]$Matches[3]
    $off = $Matches[4]
    # 那个偏移量是 "+0800" / "-0500": 先算成分钟, 再 FromUnixTimeSeconds + ToOffset
    $sign = if ($off.Substring(0, 1) -eq '-') { -1 } else { 1 }
    $minutes = $sign * ([int]$off.Substring(1, 2) * 60 + [int]$off.Substring(3, 2))
    $when = [DateTimeOffset]::FromUnixTimeSeconds($secs).ToOffset([TimeSpan]::FromMinutes($minutes))
    return @{ name = $Matches[1]; email = $Matches[2]; date = $when.ToString("yyyy-MM-ddTHH:mm:sszzz") }
}
$authorLine = ($header -split "`n") | Where-Object { $_ -like 'author *' } | Select-Object -First 1
$committerLine = ($header -split "`n") | Where-Object { $_ -like 'committer *' } | Select-Object -First 1
$author = Stamp $authorLine
$committer = Stamp $committerLine

$commitBody = @{
    message   = $message
    tree      = $newTree
    author    = $author
    committer = $committer
}
# **没有父提交时干脆不带 parents 这个键**: ConvertTo-Json 会把空数组写成 null, 而 GitHub 对
# `"parents": null` 直接回 422 ("nil is not an array"); 不带这个键才是"根提交"
if (-not $NoParent) { $commitBody.parents = @($Parent) }
$body = $commitBody | ConvertTo-Json -Depth 6 -Compress
[IO.File]::WriteAllText($jsonFile, $body, $utf8)
$seeded = (Api @('api', '--method', 'POST', "repos/$Repo/git/commits", '--input', $jsonFile, '--jq', '.sha')).Trim()
if ($seeded -notmatch '^[0-9a-f]{40}$') { throw "the API did not return a commit sha: $seeded" }

# 三条核对 (不核对就动 ref 等于把没验证过的东西推上去)
$check = (Api @('api', "repos/$Repo/git/commits/$seeded", '--jq', '.tree.sha + " " + (.parents[0].sha // "")')).Trim()
$wantTree = (git rev-parse 'HEAD^{tree}').Trim()
# **别在这里给 $wantTree 补那个空格**: 上面 $check 已经 .Trim() 过, jq 给根提交
# 拼出来的是 "tree sha " (末尾一个空格), trim 之后就只剩 tree sha —— 补空格必然不等
$want = if ($NoParent) { $wantTree } else { "$wantTree $Parent" }
if ($check -ne $want) { throw "seeded commit check failed: got '$check' want '$want'" }
# 正文这一条只比"第一行与总长度": `gh --jq` 把多行正文压成一行输出, 逐字符比会假失败;
# 而"树一致 + 父正确 + 首行与长度一致"已经足够说明挂上去的就是本地那个提交
$remote = (Api @('api', "repos/$Repo/git/commits/$seeded", '--jq', '.message')) -join "`n"
$localFirst = ($message -split "`n")[0]
$remoteFirst = ($remote -split "`n")[0]
if ($remoteFirst.Trim() -ne $localFirst.Trim()) { throw "the remote commit subject differs: '$remoteFirst' vs '$localFirst'" }
if ($remote.Length -lt ($message.Length - 2)) { throw "the remote commit message looks truncated: $($remote.Length) < $($message.Length)" }
Write-Host "  seeded commit $($seeded.Substring(0,7)) : tree matches, parent is the initial commit, subject matches" -ForegroundColor Green
$commit = $seeded
}

if (-not $Apply) {
    Write-Host 'dry run: the ref was NOT moved (rerun with -Apply)' -ForegroundColor Yellow
    exit 0
}

# --- 4. 移动 ref (这才是"字面上真的推上去了") ---
#
# force: 根提交那一路要把远端从"占位提交"改指到本地这个提交 (不是快进, 是换一条线), 所以要 true;
# 挂在父提交下面那一路是快进, 用 false 更保守
$body = @{ sha = $commit; force = [bool]$NoParent } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($jsonFile, $body, $utf8)
$moved = (Api @('api', '--method', 'PATCH', "repos/$Repo/git/refs/heads/$Branch", '--input', $jsonFile, '--jq', '.object.sha')).Trim()
if ($moved -ne $commit) { throw "ref did not land on $commit (got $moved)" }
Write-Host "  $Branch -> $commit" -ForegroundColor Green

# --- 5. 标签 (与提交同一个对象) ---
if ($Tag -ne '') {
    # 标签可能已经存在 (上一次跑留下的): 存在就改指, 不存在就新建 —— 两种都试一遍, 都不成才算失败
    $body = @{ ref = "refs/tags/$Tag"; sha = $commit; force = $true } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($jsonFile, $body, $utf8)
    $tagged = (Api @('api', '--method', 'POST', "repos/$Repo/git/refs", '--input', $jsonFile, '--jq', '.object.sha') 2>$null)
    if ($LASTEXITCODE -ne 0) {
        $tagged = (Api @('api', '--method', 'PATCH', "repos/$Repo/git/refs/tags/$Tag", '--input', $jsonFile, '--jq', '.object.sha')).Trim()
    }
    if ("$tagged".Trim() -ne $commit) { throw "tag did not land on $commit (got $tagged)" }
    Write-Host "  tag $Tag -> $commit" -ForegroundColor Green
}
