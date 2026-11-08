<#
    arm64（aarch64）运行时产物构建

    与 build-runtime.ps1 的区别只在于“从哪来”：Termux 的 aarch64 仓库取同一版本的
    nodejs / python 与依赖，解包后交给 build-runtime.ps1 -Abi arm64-v8a 处理。

    产物默认落在 .tmp\arm64-jniLibs\arm64-v8a 与 .tmp\arm64-assets，
    **不写进 runtime-host/src/main/**：当前 APK 的 assets/python-stdlib.zip 含
    lib-dynload/*.so（架构相关），x86_64 与 arm64 不能共用同一份 assets，
    接进 Gradle 需要按 ABI 拆 flavor/splits，尚未实现（见 docs/STATE.md T-04）。

    用法：
      powershell -ExecutionPolicy Bypass -File scripts\build-runtime-arm64.ps1
#>
param(
    [string]$BaseUrl = "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
    [string]$Work = ".tmp\tools\arm64",
    [string]$JniLibs = ".tmp\arm64-jniLibs",
    [string]$Assets = ".tmp\arm64-assets"
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

$indexDir = Join-Path $Work "deb"
$rootDir = Join-Path $Work "root"
New-Item -ItemType Directory -Force $indexDir, $rootDir | Out-Null
$index = Join-Path $Work "Packages-aarch64"

if (-not (Test-Path $index)) {
    curl.exe -L --silent --show-error --connect-timeout 20 --max-time 300 `
        -o $index "$BaseUrl/dists/stable/main/binary-aarch64/Packages"
}

# 1) 依赖闭包 + 下载
powershell -ExecutionPolicy Bypass -File "scripts\fetch-termux.ps1" `
    -Index $index -OutDir $indexDir -BaseUrl $BaseUrl

# 2) 解包
powershell -ExecutionPolicy Bypass -File "scripts\extract-termux-debs.ps1" `
    -DebDir $indexDir -RootDir $rootDir

# 3) 改名 / 改写 soname / 生成 stdlib zip（与 x86_64 完全同一套处理）
powershell -ExecutionPolicy Bypass -File "scripts\build-runtime.ps1" `
    -Abi "arm64-v8a" `
    -TermuxRoot (Join-Path $rootDir "data\data\com.termux\files\usr") `
    -JniLibs $JniLibs -Assets $Assets

Write-Output "ARM64-DONE jniLibs=$((Join-Path $JniLibs 'arm64-v8a')) assets=$Assets"
Write-Output "注意：产物尚未接入 APK（需按 ABI 拆分 assets），真机未验证（T-04）"
