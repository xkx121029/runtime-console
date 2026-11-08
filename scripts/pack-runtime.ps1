<#
.SYNOPSIS
  把两个内核的运行时依赖打成可分发、可校验的「运行时包」（runtime pack）。

.DESCRIPTION
  产物目录（默认 .tmp\runtime-pack\x86_64）：
    manifest.json          包描述：目标目录、大小、SHA-256、分卷名
    dsh.zip                目标 files/home/dsh（node_modules 等）
    python-site.zip        目标 files/python-site（Octop 的 Python 依赖）

  分卷规则：-SplitMb 大于 0 时按字节切成 *.zip.000 / *.zip.001…，
  安装器会按顺序拼接后校验整体 SHA-256，因此分卷不会削弱完整性。

.NOTES
  本脚本只在 PC 侧打包，不碰上游代码。文件必须以 UTF-8 BOM 保存，
  否则 PowerShell 5.1 会把中文当 ANSI 解析。
#>
param(
    [string]$RepoRoot = "C:\rc",
    [string]$DshDir = "",
    [string]$SiteDir = "",
    [string]$OutDir = "",
    [string]$Abi = "x86_64",
    [int]$SplitMb = 0,
    [switch]$SkipDsh,
    [switch]$SkipSite
)

$ErrorActionPreference = "Stop"
# ZipArchiveMode / ZipArchive 在 System.IO.Compression 里，CreateEntryFromFile 在 FileSystem 里，
# 两个都要显式加载（只加载 FileSystem 会在 [IO.Compression.ZipArchiveMode] 上 TypeError）。
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $DshDir)  { $DshDir  = Join-Path $RepoRoot ".tmp\dsh-npm" }
if (-not $SiteDir) { $SiteDir = Join-Path $RepoRoot ".tmp\octop-site" }
if (-not $OutDir)  { $OutDir  = Join-Path $RepoRoot ".tmp\runtime-pack\$Abi" }

New-Item -ItemType Directory -Force $OutDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$packVersion = "$Abi-$stamp"
$parts = New-Object System.Collections.ArrayList

function New-Zip([string]$sourceDir, [string]$zipPath) {
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Write-Host "压缩 $sourceDir → $zipPath" -ForegroundColor Cyan
    # 不能 use CreateFromDirectory：PowerShell 5.1 跑在 .NET Framework 上，它用反斜杠做条目
    # 分隔符，Android 侧解出来会是一堆名字里带 "\" 的扁平文件（实测 dsh 树无法启动）。
    # 这里手工建条目并强制用 "/"，与 zip 规范一致。
    $root = (Resolve-Path $sourceDir).Path
    $archive = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in Get-ChildItem $root -Recurse -File -Force) {
            $rel = $file.FullName.Substring($root.Length).TrimStart('\').Replace('\', '/')
            [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $rel, [IO.Compression.CompressionLevel]::Fastest)
        }
    }
    finally { $archive.Dispose() }
}

function Add-Part([string]$id, [string]$title, [string]$target, [string]$zipPath) {
    $size = (Get-Item $zipPath).Length
    $hash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()

    # 分卷：按字节切分，文件名 <zip>.000、<zip>.001…
    $files = New-Object System.Collections.ArrayList
    if ($SplitMb -gt 0) {
        $chunk = [int64]$SplitMb * 1MB
        $input = [IO.File]::OpenRead($zipPath)
        try {
            $index = 0
            while ($input.Position -lt $input.Length) {
                $name = "$([IO.Path]::GetFileName($zipPath)).{0:D3}" -f $index
                $out = [IO.File]::Create((Join-Path $OutDir $name))
                try {
                    $buffer = New-Object byte[] (1MB)
                    $left = [Math]::Min($chunk, $input.Length - $input.Position)
                    while ($left -gt 0) {
                        $read = $input.Read($buffer, 0, [Math]::Min($buffer.Length, $left))
                        if ($read -le 0) { break }
                        $out.Write($buffer, 0, $read)
                        $left -= $read
                    }
                }
                finally { $out.Close() }
                [void]$files.Add($name)
                $index++
            }
        }
        finally { $input.Close() }
        Remove-Item -Force $zipPath
    }
    else {
        $name = [IO.Path]::GetFileName($zipPath)
        [void]$files.Add($name)
    }

    [void]$parts.Add([ordered]@{
        id      = $id
        title   = $title
        target  = $target
        bytes   = $size
        sha256  = $hash
        files   = @($files)
    })
    Write-Host ("  {0}: {1:N1} MB, sha256={2}" -f $id, ($size / 1MB), $hash.Substring(0, 16)) -ForegroundColor Green
}

if (-not $SkipDsh) {
    if (-not (Test-Path (Join-Path $DshDir "node_modules"))) { throw "缺少 Node 依赖目录：$DshDir\node_modules（先跑 scripts/stage-dsh-runtime.ps1）" }
    $zip = Join-Path $OutDir "dsh.zip"
    New-Zip $DshDir $zip
    Add-Part "home/dsh" "Node 运行时（Web 控制台）" "home/dsh" $zip
}

if (-not $SkipSite) {
    if (-not (Test-Path $SiteDir)) { throw "缺少 Python 依赖目录：$SiteDir（先跑 scripts/octop-runtime.ps1）" }
    $zip = Join-Path $OutDir "python-site.zip"
    New-Zip $SiteDir $zip
    Add-Part "python-site" "Python 运行时（管理接口）" "python-site" $zip
}

$manifest = [ordered]@{
    schema      = 1
    packVersion = $packVersion
    abi         = $Abi
    createdAt   = (Get-Date).ToString("o")
    parts       = @($parts)
}
$manifestPath = Join-Path $OutDir "manifest.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath -Encoding utf8

$total = [int64]0
foreach ($part in $parts) { $total += [int64]$part["bytes"] }
Write-Host ""
Write-Host ("包已生成：{0}" -f $OutDir) -ForegroundColor Green
Write-Host ("版本 {0}，共 {1:N1} MB，{2} 个组件" -f $packVersion, ($total / 1MB), $parts.Count)
Write-Host "把整个目录拷到手机存储（例如 下载/runtime-pack），在 App 里点「导入运行时包」并选择该目录。"
