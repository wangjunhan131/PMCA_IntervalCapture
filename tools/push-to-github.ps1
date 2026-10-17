# ============================================================================
#  push-to-github.ps1 —— 把本地仓库推送到 GitHub 的一键脚本
#
#  用法（在工程根目录 D:\sony\PMCA_IntervalCapture 下执行）：
#     powershell -ExecutionPolicy Bypass -File tools\push-to-github.ps1 -User 你的GitHub用户名
#
#  可选参数：
#     -User   你的 GitHub 用户名（必填；用来拼 remote 地址）
#     -Repo   仓库名，默认 PMCA_IntervalCapture
#     -Tag    同时创建的版本标签，默认 v1.0.0；传 "" 表示不建标签
#     -Token  改用 HTTPS + Personal Access Token 推送（不传则用 SSH）
#     -Check  只检查环境与 SSH 认证状态，不真正 push
#
#  前置条件（脚本会自检并给出提示）：
#    1. 已把 ~/.ssh/id_ed25519.pub 的内容添加到 GitHub
#       → https://github.com/settings/ssh/new
#    2. GitHub 上已经建好同名空仓库（不要勾选 README / .gitignore / LICENSE）
# ============================================================================

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$User,
    [string]$Repo = 'PMCA_IntervalCapture',
    [string]$Tag  = 'v1.0.0',
    [string]$Token = '',
    [switch]$Check
)

# 注意：这里用 Continue 而不是 Stop。因为 git/ssh 正常运行时也会往 stderr 写提示，
# 在 Stop 模式下 PowerShell 会把它当成终止性错误，导致脚本误判失败。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

# 自动切到脚本所在的工程根目录，这样从任何路径调用都能工作
$scriptRepoRoot = Split-Path $PSScriptRoot -Parent
if ((Test-Path (Join-Path $scriptRepoRoot '.git')) -or (Test-Path (Join-Path $scriptRepoRoot 'app'))) {
    Set-Location $scriptRepoRoot
}

# ---------- 1. 定位 git（优先用随工程下载的便携版 MinGit） ----------
function Find-Git {
    $candidates = @(
        'D:\sony\build-support\mingit\cmd\git.exe',
        "$env:ProgramFiles\Git\cmd\git.exe",
        "${env:ProgramFiles(x86)}\Git\cmd\git.exe",
        "$env:LOCALAPPDATA\Programs\Git\cmd\git.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command git -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw '找不到 git.exe，请先运行 winget install Git.Git 或把 MinGit 解压到 build-support\mingit'
}

$Git = Find-Git
Write-Host "[1/6] git: $Git" -ForegroundColor Cyan
& $Git --version

# ---------- 2. 确认当前在工程根目录 ----------
$root = & $Git rev-parse --show-toplevel 2>$null
if (-not $root) { throw '当前目录不是 git 仓库，请 cd 到 D:\sony\PMCA_IntervalCapture 再运行' }
Write-Host "[2/6] 仓库根目录: $root" -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $root 'app\src\main\AndroidManifest.xml'))) {
    throw '这个目录看起来不是 PMCA_IntervalCapture 工程（找不到 AndroidManifest.xml）'
}

# ---------- 3. 检查是否有提交、是否有不该入库的文件 ----------
$hasCommit = & $Git rev-parse --verify HEAD 2>$null
if (-not $hasCommit) { throw '仓库还没有任何提交，请先执行 git add . 与 git commit' }
$logLine = (& $Git --no-pager log --oneline -1)
Write-Host "[3/6] 当前提交: $logLine" -ForegroundColor Cyan

$tracked = & $Git -c core.quotepath=false ls-files
$forbidden = $tracked | Where-Object { $_ -match 'android-4\.1\.1\.4\.jar$|\.keystore$|\.jks$|^build/|^\.work/' }
if ($forbidden) {
    Write-Warning ('检测到不该入库的文件：' + ($forbidden -join ', '))
    Write-Warning '请先 git rm --cached 这些文件再推送'
    if ($Check) { exit 2 }
} else {
    Write-Host ("      入库文件 {0} 个，未发现大文件/私钥/构建产物 ✔" -f $tracked.Count) -ForegroundColor Green
}

# ---------- 4. 配置远程地址 ----------
if ($Token) {
    # HTTPS + PAT：把 token 嵌进 URL 会写进 .git/config，仅本地使用
    $remoteUrl = "https://$User`:$Token@github.com/$User/$Repo.git"
    Write-Host "[4/6] 远程方式: HTTPS + PAT" -ForegroundColor Cyan
    # 走本机系统代理（如不需要可注释掉这两行）
    & $Git config http.proxy  'http://127.0.0.1:7892'
    & $Git config https.proxy 'http://127.0.0.1:7892'
} else {
    $remoteUrl = "git@github.com:$User/$Repo.git"
    Write-Host "[4/6] 远程方式: SSH -> $remoteUrl" -ForegroundColor Cyan
    & $Git config --unset http.proxy  2>$null
    & $Git config --unset https.proxy 2>$null
}

$existing = & $Git remote 2>$null
if ($existing -contains 'origin') {
    & $Git remote set-url origin $remoteUrl
} else {
    & $Git remote add origin $remoteUrl
}
Write-Host ('      origin = ' + (& $Git remote get-url origin)) -ForegroundColor DarkGray

# ---------- 5. 检查认证是否可用 ----------
if (-not $Token) {
    $sshTest = (& ssh -o BatchMode=yes -T git@github.com 2>&1) -join ' '
    if ($sshTest -match 'successfully authenticated') {
        Write-Host '[5/6] SSH 认证正常 ✔' -ForegroundColor Green
    } else {
        Write-Host '[5/6] SSH 认证未通过' -ForegroundColor Yellow
        Write-Host ('      GitHub 返回: ' + $sshTest)
        Write-Host '      请把你的公钥添加到 https://github.com/settings/ssh/new ，内容为：' -ForegroundColor Yellow
        Write-Host ''
        $pub = Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub" -ErrorAction SilentlyContinue
        if ($pub) { Write-Host $pub -ForegroundColor White } else { Write-Host '(没找到 ~/.ssh/id_ed25519.pub)' -ForegroundColor Red }
        Write-Host ''
        Write-Host '      添加完成后重新运行本脚本即可。' -ForegroundColor Yellow
        if ($Check) { exit 3 }
        throw 'SSH 认证未通过，已停止推送'
    }
} else {
    Write-Host '[5/6] 使用 PAT，跳过 SSH 检查' -ForegroundColor Cyan
}

if ($Check) { Write-Host '[6/6] -Check 模式，不执行推送。' -ForegroundColor Cyan; exit 0 }

# ---------- 6. 推送主分支与标签 ----------
Write-Host '[6/6] 开始推送...' -ForegroundColor Cyan
& $Git branch -M main
& $Git push -u origin main
if ($LASTEXITCODE -ne 0) { throw '推送失败，请检查用户名 / 仓库名 / 认证方式' }

if ($Tag -ne '') {
    & $Git tag -a $Tag -m "PMCA_IntervalCapture $Tag"
    & $Git push origin --tags
}

Write-Host ''
Write-Host '================ 推送完成 ================' -ForegroundColor Green
Write-Host ("仓库地址: https://github.com/{0}/{1}" -f $User, $Repo)
Write-Host '后续建议:'
Write-Host '  1) 仓库页 Releases -> Draft a new release -> 选标签 ' + $Tag + ' -> 上传 build\outputs\PMCA_IntervalCapture.apk'
Write-Host '  2) About 里补 Description 与 Topics（见 docs\06-GitHub上传与发布指南.md 第 6 节）'
