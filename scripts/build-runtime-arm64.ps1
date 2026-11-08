<#
    arm64（aarch64）运行时产物构建

    与 build-runtime.ps1 的区别只在于“从哪来”：Termux 的 aarch64 仓库取同一版本的
    nodejs / python 与依赖，解包后交给 build-runtime.ps1 -Abi arm64-v8a 处理。

    产物默认落在 .tmp\arm64-jniLibs\arm64-v8a 与 .tmp\arm64-assets，
    **不写进 runtime-host/src/main/**：当前 APK 的 assets/python-stdlib.zip 含
    lib-dynload 下架构相关的动态库，x86_64 与 arm64 不能共用同一份 assets。
    接入方式是 Gradle 属性整套替换（runtime-host/build.gradle.kts）：

      gradlew :app:assembleDebug -PruntimeAbi=arm64-v8a `
          -PruntimeAssetsDir=<repo>\.tmp\arm64-assets `
          -PruntimeJniLibsDir=<repo>\.tmp\arm64-jniLibs

    真机未验证（docs/STATE.md T-04）。

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
#    Roots 与 x86_64 保持同一份清单：node/python 本体之外，管理接口内核的原生扩展
#    （lxml / Pillow / tiktoken）需要的 Termux 原生库也要一起取，否则 arm64 包能装但
#    Octop 起不来。缺哪个包 fetch 会打 MISS，不会中断。
$roots = @(
    "nodejs", "python",
    "libxml2", "libxslt", "libtiff", "libjpeg-turbo", "openjpeg", "libimagequant",
    "libxcb", "libxau", "libxdmcp", "libwebp", "libiconv"
)
powershell -ExecutionPolicy Bypass -File "scripts\fetch-termux.ps1" `
    -Index $index -OutDir $indexDir -BaseUrl $BaseUrl -Roots $roots

# 2) 解包
powershell -ExecutionPolicy Bypass -File "scripts\extract-termux-debs.ps1" `
    -DebDir $indexDir -RootDir $rootDir

# 3) 改名 / 改写 soname / 生成 stdlib zip（与 x86_64 完全同一套处理）
powershell -ExecutionPolicy Bypass -File "scripts\build-runtime.ps1" `
    -Abi "arm64-v8a" `
    -TermuxRoot (Join-Path $rootDir "data\data\com.termux\files\usr") `
    -JniLibs $JniLibs -Assets $Assets

# 4) 与架构无关的静态 assets（内核启动脚本、自检脚本）
#    Gradle 里 -PruntimeAssetsDir 是**整套替换** src/main/assets，不补这一步
#    arm64 包会缺 launcher/selftest，内核启动直接找不到入口脚本。
$staticAssets = Join-Path $repoRoot "runtime-host\src\main\assets"
foreach ($dir in @("launcher", "selftest")) {
    $src = Join-Path $staticAssets $dir
    if (-not (Test-Path $src)) { Write-Output "WARN 静态 assets 缺失：$dir"; continue }
    $dst = Join-Path $Assets $dir
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    Copy-Item $src $dst -Recurse -Force
}
Write-Output ("STAGE static-assets " + ((Get-ChildItem (Join-Path $Assets "launcher"),(Join-Path $Assets "selftest") -Recurse -File -ErrorAction SilentlyContinue).Count) + " files")

Write-Output "ARM64-DONE jniLibs=$((Join-Path $JniLibs 'arm64-v8a')) assets=$Assets"
Write-Output "下一步：gradlew :app:assembleDebug -PruntimeAbi=arm64-v8a -PruntimeAssetsDir=$Assets -PruntimeJniLibsDir=$JniLibs"
Write-Output "注意：真机未验证（T-04），本产物只证明构建链路可出包"
