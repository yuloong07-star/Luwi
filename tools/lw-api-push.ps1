# 用 GitHub API 把本地领先的提交送上远端 (github.com:443 被 SNI 挡住, api.github.com 通)
#
# 做法: 上传 blob -> 用 base_tree 建 tree -> 建 commit -> 最后才移动 ref
# 关键纪律: **每一个对象都与本地算出的 SHA 比对**, 全对才动 ref; 有一个不对就中止, 远端一个字不改
# (前面几轮只创建"没人引用的对象", 不移动 ref 就不会影响仓库)
#
# 用法:
#   pwsh -File lw-api-push.ps1 -Limit 1        # 先拿第一个提交试, 只验证不动 ref
#   pwsh -File lw-api-push.ps1                 # 全量验证, 还是不动 ref
#   pwsh -File lw-api-push.ps1 -Apply          # 验证全过之后移动 ref, 并打标签
param(
    [int]$Limit = 0,
    [switch]$Apply,
    [string]$RepoRoot = 'D:\apk\Luwi',
    [string]$Repo = 'yuloong07-star/Luwi',
    [string]$Gh = 'D:\Codex\gh-cli\gh.exe',
    [string]$RemoteRef = 'luwi/main',
    [string]$Tag = 'v1.0.3'
)

$ErrorActionPreference = 'Stop'
Set-Location $RepoRoot
$tmp = 'D:\apk\.lwtmp\apipush'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$blobFile = Join-Path $tmp 'blob.bin'
$jsonFile = Join-Path $tmp 'body.json'
# **不带 BOM**: `[Text.Encoding]::UTF8` 写的文件开头有 EF BB BF, 而 GitHub 的 JSON 解析器会
# 直接说 "Problems parsing JSON" (HTTP 400) —— 这个坑在第一次跑就踩了
$utf8 = New-Object System.Text.UTF8Encoding($false)

function GitText([string[]]$a) {
    # 走文件而不是管道: 提交对象与 blob 里可能有中文, 管道会按控制台编码来回转
    $out = Join-Path $tmp 'git.out'
    cmd /c ("git " + ($a -join ' ') + " > `"$out`"") | Out-Null
    return [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8)
}

function Iso([string]$name, [string]$email, [string]$stamp) {
    if ($stamp -notmatch '^(\d+) ([+-])(\d{2})(\d{2})$') { throw "unparsable date: $stamp" }
    $seconds = [long]$Matches[1]
    $offset = [TimeSpan]::FromMinutes([int]($Matches[3]) * 60 + [int]($Matches[4]))
    if ($Matches[2] -eq '-') { $offset = -$offset }
    $when = [DateTimeOffset]::FromUnixTimeSeconds($seconds).ToOffset($offset)
    return @{ name = $name; email = $email; date = $when.ToString("yyyy-MM-ddTHH:mm:sszzz") }
}

$base = (git rev-parse $RemoteRef).Trim()
$remoteTree = (& $Gh api "repos/$Repo/git/commits/$base" --jq '.tree.sha').Trim()
$localTree = (git rev-parse "$base^{tree}").Trim()
if ($remoteTree -ne $localTree) { throw "base tree differs: remote=$remoteTree local=$localTree" }
Write-Host "base $base : tree $localTree matches the API" -ForegroundColor Green

$commits = @(git rev-list --reverse "$RemoteRef..HEAD")
if ($Limit -gt 0) { $commits = @($commits[0..($Limit - 1)]) }
Write-Host "replaying $($commits.Count) of $((@(git rev-list --reverse "$RemoteRef..HEAD")).Count) commit(s)"

$known = @{}
# 提交 -> 它那棵树的 SHA。**历史里有 merge**, 所以"上一次重放的那个提交"不等于"这个提交的父提交":
# 每个提交的 base_tree 必须取它**自己的第一父**那棵树。按反向拓扑序重放时, 父要么是远端那个 base,
# 要么是前面已经重放过的提交, 两种都在这张表里
$trees = @{}
$trees[$base] = $remoteTree
$parentSha = $base
$parentTree = $remoteTree
$index = 0

foreach ($commit in $commits) {
    $index++
    $line = (git rev-list --parents -n 1 $commit).Trim()
    $parts = $line -split ' '
    if ($parts.Count -lt 2) { throw "$commit has no parent to base its tree on" }
    $parentSha = $parts[1]
    if (-not $trees.ContainsKey($parentSha)) {
        throw "$($commit.Substring(0, 7)) 的第一父 $($parentSha.Substring(0, 7)) 还没被重放过"
    }
    $parentTree = $trees[$parentSha]

    $entries = @()
    # 与第一父逐条比: merge 提交直接 `git diff-tree <commit>` 什么都不输出, 显式给两棵树才是那个 diff
    #
    # **`core.quotePath=false` 是必须的** (2026-10-08 踩的): 默认那一档下 git 把非 ASCII 路径印成
    # `"quick-commands/\346\226\207..."` 这种转义形式, 而下面那个正则把整段当成路径 —— 建出来的树里
    # 于是多了一个转义过的怪名字, 树 SHA 自然对不上 (那一次卡在 `tree mismatch at 89494b4`), 而
    # 本仓的提交里真有中文文件名 (`quick-commands/制定旅游计划.md`, `skills/photo-edit` 那一批)
    foreach ($raw in @(git -c core.quotePath=false diff-tree -r --raw --no-commit-id $parentSha $commit)) {
        if ($raw -notmatch '^:(\d+) (\d+) ([0-9a-f]+) ([0-9a-f]+) ([A-Z])\s+(.+)$') {
            throw "unparsed diff line: $raw"
        }
        $oldMode = $Matches[1]; $newMode = $Matches[2]; $newBlob = $Matches[4]
        $status = $Matches[5]; $path = $Matches[6]
        if ($status -eq 'D') {
            $kind = if ($oldMode -eq '160000') { 'commit' } else { 'blob' }
            $entries += @{ path = $path; mode = $oldMode; type = $kind; sha = $null }
            continue
        }
        if ($newMode -eq '160000') {
            # submodule: 这个 sha 指的是**另一个仓库里的提交**, 树条目原样带过去就行 —— 拿它去
            # `git cat-file blob` 会失败 (它不是 blob), 建出来的树也就对不上了
            $entries += @{ path = $path; mode = '160000'; type = 'commit'; sha = $newBlob }
            continue
        }
        if (-not $known.ContainsKey($newBlob)) {
            cmd /c "git cat-file blob $newBlob > `"$blobFile`"" | Out-Null
            $body = @{
                content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($blobFile))
                encoding = 'base64'
            } | ConvertTo-Json -Compress
            [IO.File]::WriteAllText($jsonFile, $body, $utf8)
            $sha = (& $Gh api -X POST "repos/$Repo/git/blobs" --input $jsonFile --jq '.sha').Trim()
            if ($sha -ne $newBlob) { throw "blob mismatch for $path : api=$sha local=$newBlob" }
            $known[$newBlob] = $sha
        }
        $entries += @{ path = $path; mode = $newMode; type = 'blob'; sha = $newBlob }
    }

    $body = @{ base_tree = $parentTree; tree = $entries } | ConvertTo-Json -Depth 6 -Compress
    [IO.File]::WriteAllText($jsonFile, $body, $utf8)
    $tree = (& $Gh api -X POST "repos/$Repo/git/trees" --input $jsonFile --jq '.sha').Trim()
    $want = (git rev-parse "$commit^{tree}").Trim()
    if ($tree -ne $want) { throw "tree mismatch at $($commit.Substring(0,7)) : api=$tree local=$want" }
    $trees[$commit] = $tree

    # 提交对象逐字节重放: header + 空行 + 正文, 正文末尾那个换行是对象里本来就有的
    $objFile = Join-Path $tmp 'commit.obj'
    cmd /c "git cat-file commit $commit > `"$objFile`"" | Out-Null
    $obj = [IO.File]::ReadAllText($objFile, [Text.Encoding]::UTF8)
    $split = $obj.IndexOf("`n`n")
    if ($split -lt 0) { throw "no header/body split in $commit" }
    $header = $obj.Substring(0, $split)
    $message = $obj.Substring($split + 2)
    $authorLine = ($header -split "`n") | Where-Object { $_ -like 'author *' } | Select-Object -First 1
    $committerLine = ($header -split "`n") | Where-Object { $_ -like 'committer *' } | Select-Object -First 1
    if ($authorLine -notmatch '^author (.+) <([^>]*)> (.+)$') { throw "unparsable author: $authorLine" }
    $author = Iso $Matches[1] $Matches[2] $Matches[3]
    if ($committerLine -notmatch '^committer (.+) <([^>]*)> (.+)$') { throw "unparsable committer: $committerLine" }
    $committer = Iso $Matches[1] $Matches[2] $Matches[3]
    $subject = ($message -split "`n")[0]

    # GitHub 网页上改过的提交带 gpgsig (GitHub 的 PGP 签名), 而签名是**提交对象的一部分** ——
    # 少了它重建出来的 SHA 必然不同 (2026-10-16 实测: 51ae7ea "Update README.md" 卡在这里,
    # 前面 23 个提交都 byte-for-byte 对上了)。Git Data API 的 commits 端点收 `signature` 字段,
    # 内容就是 armored 签名原文 (去掉 "gpgsig " 前缀、以及续行那个前导空格, 再拼回来)
    $signature = $null
    $headerLines = $header -split "`n"
    for ($i = 0; $i -lt $headerLines.Count; $i++) {
        if ($headerLines[$i] -notlike 'gpgsig *') { continue }
        $sig = [System.Collections.Generic.List[string]]::new()
        $sig.Add($headerLines[$i].Substring('gpgsig '.Length))
        for ($j = $i + 1; $j -lt $headerLines.Count; $j++) {
            if ($headerLines[$j] -notlike ' *') { break }
            $sig.Add($headerLines[$j].Substring(1))
        }
        $signature = ($sig -join "`n")
        break
    }

    $created = $null
    foreach ($candidate in @($message, $message.TrimEnd("`n"))) {
        $body = @{
            message   = $candidate
            tree      = $tree
            # merge 提交有**两个**父: 少送一个 SHA 就不一样 (实测 12c891f 卡在这里), 所以按
            # `git rev-list --parents` 那一行原样送, 不写死第一个
            parents   = @($parts[1..($parts.Count - 1)])
            author    = $author
            committer = $committer
        }
        if ($null -ne $signature) { $body.signature = $signature }
        $body = $body | ConvertTo-Json -Depth 6 -Compress
        [IO.File]::WriteAllText($jsonFile, $body, $utf8)
        $created = (& $Gh api -X POST "repos/$Repo/git/commits" --input $jsonFile --jq '.sha').Trim()
        if ($created -eq $commit) { break }
    }
    if ($created -ne $commit) {
        throw "commit mismatch at $($commit.Substring(0,7)) : api=$created local=$commit (message differs?)"
    }

    Write-Host ("  [{0}/{1}] {2} {3}" -f $index, $commits.Count, $commit.Substring(0, 7), $subject) -ForegroundColor Green
    $parentSha = $commit
    $parentTree = $tree
}

Write-Host ""
Write-Host "all $($commits.Count) objects reproduced byte for byte; tip would be $parentSha"

if (-not $Apply) {
    Write-Host "dry run: the ref was NOT moved (rerun with -Apply to move it)" -ForegroundColor Yellow
    exit 0
}
if ($commits.Count -ne @(git rev-list --reverse "$RemoteRef..HEAD").Count) { throw "refusing -Apply on a partial replay" }

$body = @{ sha = $parentSha; force = $false } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($jsonFile, $body, $utf8)
$moved = (& $Gh api -X PATCH "repos/$Repo/git/refs/heads/main" --input $jsonFile --jq '.object.sha').Trim()
if ($moved -ne $parentSha) { throw "ref update returned $moved, wanted $parentSha" }
Write-Host "main is now $moved" -ForegroundColor Green

# 标签: 与 v1.0.2 一样是**带注解的 tag**, 所以连 tag 对象也逐字节重放并比对
$tagObjFile = Join-Path $tmp 'tag.obj'
cmd /c "git cat-file tag $Tag > `"$tagObjFile`"" | Out-Null
$tagObj = [IO.File]::ReadAllText($tagObjFile, [Text.Encoding]::UTF8)
$tSplit = $tagObj.IndexOf("`n`n")
if ($tSplit -lt 0) { throw "no header/body split in tag $Tag" }
$taggerLine = ($tagObj.Substring(0, $tSplit) -split "`n") |
    Where-Object { $_ -like 'tagger *' } | Select-Object -First 1
if ($taggerLine -notmatch '^tagger (.+) <([^>]*)> (.+)$') { throw "unparsable tagger: $taggerLine" }
$tagger = Iso $Matches[1] $Matches[2] $Matches[3]
$tMessage = $tagObj.Substring($tSplit + 2)
$wantTag = (git rev-parse $Tag).Trim()
$tagSha = $null
$made = ''
foreach ($candidate in @($tMessage, $tMessage.TrimEnd("`n"))) {
    $body = @{
        tag = $Tag; message = $candidate; object = $parentSha; type = 'commit'; tagger = $tagger
    } | ConvertTo-Json -Depth 6 -Compress
    [IO.File]::WriteAllText($jsonFile, $body, $utf8)
    $made = (& $Gh api -X POST "repos/$Repo/git/tags" --input $jsonFile --jq '.sha').Trim()
    if ($made -eq $wantTag) { $tagSha = $made; break }
}
if (-not $tagSha) { throw "tag object mismatch: api=$made local=$wantTag" }
$body = @{ ref = "refs/tags/$Tag"; sha = $tagSha } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($jsonFile, $body, $utf8)
$ref = (& $Gh api -X POST "repos/$Repo/git/refs" --input $jsonFile --jq '.ref').Trim()
Write-Host "annotated tag $Tag -> $tagSha ($ref)" -ForegroundColor Green
