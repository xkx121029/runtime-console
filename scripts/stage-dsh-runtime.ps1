<#
.SYNOPSIS
  把已发布的 dsh CLI（与上游固定 SHA 同版本 0.2.2-rc.1）打包成可在设备上运行的运行时目录。

.DESCRIPTION
  产物在 <repo>/.tmp/dsh-npm，并通过 adb 解压到 App 私有目录 files/home/dsh。
  该脚本只做“搬运 + 打补丁”，不构建、不改写上游业务代码。

.NOTES
  文件必须以 UTF-8 BOM 保存，否则 PowerShell 5.1 会把中文当 ANSI。
#>
param(
    [string]$RepoRoot = "C:\rc",
    [string]$DshVersion = "0.2.2-rc.1",
    [string]$Adb = "C:\Users\linch\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$Package = "dev.runtimeconsole.app",
    [switch]$SkipInstall,
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"
$stage = Join-Path $RepoRoot ".tmp\dsh-npm"
$tarball = Join-Path $RepoRoot ".tmp\dsh-runtime.tar.gz"

if (-not $SkipInstall) {
    New-Item -ItemType Directory -Force $stage | Out-Null
    if (-not (Test-Path (Join-Path $stage "package.json"))) {
        '{"name":"dsh-run","private":true}' | Set-Content -Encoding utf8 (Join-Path $stage "package.json")
    }
    Push-Location $stage
    try {
        npm install "@deepseek-ai/dsh@$DshVersion" --omit=dev --ignore-scripts `
            --os=linux --cpu=x64 --registry=https://registry.npmmirror.com --no-audit --no-fund
    }
    finally { Pop-Location }
}

# 应用 P-01 补丁：整体替换该模块的入口文件（内容见 patches/dsh/…patch）
Copy-Item -Force (Join-Path $RepoRoot "patches\dsh\node-addon-require-builtin+0.1.9\lib\index.js") `
    (Join-Path $stage "node_modules\node-addon-require-builtin\lib\index.js")

if (-not $SkipPush) {
    Push-Location $stage
    try {
        if (Test-Path $tarball) { Remove-Item -Force $tarball }
        tar -czf $tarball node_modules package.json
    }
    finally { Pop-Location }

    & $Adb push $tarball /data/local/tmp/dsh-runtime.tar.gz
    & $Adb shell "run-as $Package sh -c 'mkdir -p files/home/dsh files/logs files/tmp && cd files/home/dsh && tar xzf /data/local/tmp/dsh-runtime.tar.gz && echo EXTRACT_OK'"
}
Write-Host "完成：$stage"
