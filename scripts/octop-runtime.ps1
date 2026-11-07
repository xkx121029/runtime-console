#requires -Version 5.1
<#
.SYNOPSIS
    把 Octop（Python 侧）依赖全量安装到已连接的 Android 设备。

.DESCRIPTION
    M2 阶段是手工在 adb 上一步步做的，本脚本把同一套步骤固化下来，供换机/重装复现。
    产物落在 App 的 filesDir：
        files/python-site      所有 Python 包（纯 Python 轮子 + Termux 原生包 + 自编扩展）
        files/runtime/lib      Termux 原生库（由 APK assets 落盘，本脚本只做校验）
        files/octop-home       Octop 的数据目录（首次启动时由 Octop 自己初始化）

    前提：
      1. 已执行 scripts/build-runtime.ps1 并安装过 APK（设备上要有 libpython_exec.so 与 python-site 目录）
      2. 本机有 JDK/PowerShell/NDK（默认取 ANDROID_SDK_ROOT 下最新的 ndk）
      3. 能访问 pypi.org 与 packages.termux.dev（必要时自己配镜像）

.NOTES
    - 所有版本都写死，避免"今天能装明天装不上"。
    - 本脚本只做安装，不改任何上游代码。
#>
param(
    [string]$Adb = "c:\Users\linch\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$Package = "dev.runtimeconsole.app",
    [string]$WorkDir = "$PSScriptRoot\..\.tmp\octop-runtime",
    [string]$NdkRoot = "",
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $WorkDir | Out-Null
$site = Join-Path $WorkDir "site"
$src = Join-Path $WorkDir "src"
$debs = Join-Path $WorkDir "debs"
$ex = Join-Path $WorkDir "ex"
$build = Join-Path $WorkDir "build"
foreach ($d in @($site, $src, $debs, $ex, $build)) { New-Item -ItemType Directory -Force $d | Out-Null }

function Write-Step([string]$text) { Write-Host "== $text" -ForegroundColor Cyan }

function Invoke-Adb([string[]]$arguments) {
    $out = & $Adb @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($arguments -join ' ') 失败：$out" }
    return ($out | Out-String)
}

# ---------------------------------------------------------------- 1. 纯 Python 轮子
Write-Step "1/5 下载纯 Python 轮子（py3-none-any）"

# 版本与设备上实测通过的一致（python-site 里的 dist-info 版本）
$pureWheels = @(
    "aiohappyeyeballs==2.7.1", "aiosignal==1.4.0", "aiosqlite==0.22.1", "attrs==26.1.0",
    "beautifulsoup4==4.15.0", "bidict==0.24.1", "botocore==1.43.126", "bracex==3.2.2",
    "cbor2==5.9.0", "dingtalk-stream==0.24.3", "discord-py==2.7.1", "et-xmlfile==2.0.0",
    "filetype==1.2.0", "frozenlist==1.8.0", "google-genai==3.0.0", "httpx-sse==0.4.3",
    "jmespath==1.1.0", "jsonschema==4.26.0", "jsonschema-specifications==2025.9.1",
    "langchain-anthropic==1.7.10", "langchain-google-genai==4.4.3", "langchain-mcp-adapters==0.3.2",
    "langchain-openai==1.7.4", "langgraph-checkpoint==4.3.1", "langgraph-checkpoint-sqlite==3.1.3",
    "markdown-it-py==4.2.0", "markdownify==1.2.3", "mcp==1.30.0", "mdurl==0.1.2",
    "multidict==7.2.1", "paho-mqtt==2.2.0", "prompt-toolkit==3.0.53", "propcache==0.5.4",
    "pyasn1==0.6.5", "pyasn1-modules==0.4.2", "pycparser==3.12", "pydantic-settings==2.16.2",
    "pygments==2.21.0", "pyopenssl==26.4.0", "pyrfc3339==2.1.1", "python-dotenv==1.2.4",
    "python-engineio==4.15.1", "python-socketio==5.18.1", "python-telegram-bot==22.8",
    "referencing==0.37.0", "s3transfer==0.19.3", "six==1.17.0", "soupsieve==4.0.1",
    "sse-starlette==3.7.0", "tabulate==0.10.0", "wcmatch==12.0", "wcwidth==0.9.4",
    "websocket-client==1.9.2", "wecom-aibot-sdk==1.0.8", "xlsxwriter==3.3.1", "yarl==1.25.1"
)
# 注意：mcp 必须是 1.x（2.x 删了 McpError，Octop 1.0.3b4 会导入失败，见 DECISIONS D-015）

$wheelDir = Join-Path $WorkDir "wheels"
New-Item -ItemType Directory -Force $wheelDir | Out-Null
& pip download --platform any --only-binary=:all: --python-version 3.14 --no-deps -d $wheelDir @pureWheels
if ($LASTEXITCODE -ne 0) { throw "纯轮子下载失败（本机 pip 若走系统代理，先设 NO_PROXY=*）" }

foreach ($whl in Get-ChildItem $wheelDir -Filter *.whl) {
    $zip = [IO.Path]::ChangeExtension($whl.FullName, ".zip")
    Copy-Item $whl.FullName $zip -Force
    Expand-Archive -Path $zip -DestinationPath $site -Force
}

# ---------------------------------------------------------------- 2. Termux 原生包
Write-Step "2/5 下载 Termux 原生包（x86_64）"

$termuxBase = "https://packages.termux.dev/apt/termux-main/pool/main"
$termuxDebs = @(
    @("python",            "p/python/python_3.14.10_x86_64.deb",                                  "python"),
    @("libffi",            "libf/libffi/libffi_3.8.0_x86_64.deb",                                "libffi"),
    @("python-cryptography","p/python-cryptography/python-cryptography_50.0.2_x86_64.deb",       "site"),
    @("python-rpds-py",    "p/python-rpds-py/python-rpds-py_2026.9.1_x86_64.deb",                "site"),
    @("python-lxml",       "p/python-lxml/python-lxml_6.1.3_x86_64.deb",                         "site"),
    @("python-pillow",     "p/python-pillow/python-pillow_13.0.0_x86_64.deb",                    "site"),
    @("python-pycryptodomex","p/python-pycryptodomex/python-pycryptodomex_3.24.2_x86_64.deb",    "site"),
    @("libxml2",           "libx/libxml2/libxml2_2.15.4-1_x86_64.deb",                           "lib"),
    @("libxslt",           "libx/libxslt/libxslt_1.1.45-1_x86_64.deb",                           "lib"),
    @("libiconv",          "libi/libiconv/libiconv_1.19.1_x86_64.deb",                           "lib"),
    @("libtiff",           "libt/libtiff/libtiff_4.7.2_x86_64.deb",                              "lib"),
    @("libjpeg-turbo",     "libj/libjpeg-turbo/libjpeg-turbo_3.2.1_x86_64.deb",                  "lib"),
    @("openjpeg",          "o/openjpeg/openjpeg_2.5.4_x86_64.deb",                               "lib"),
    @("libimagequant",     "libi/libimagequant/libimagequant_4.4.1_x86_64.deb",                  "lib"),
    @("libxcb",            "libx/libxcb/libxcb_1.17.0-1_x86_64.deb",                             "lib"),
    @("libwebp",           "libw/libwebp/libwebp_1.6.0.0.0.1+really1.6.0_x86_64.deb",            "lib"),
    @("libxau",            "libx/libxau/libxau_1.0.12-2_x86_64.deb",                             "lib"),
    @("libxdmcp",          "libx/libxdmcp/libxdmcp_1.1.5-2_x86_64.deb",                          "lib")
)

$libStage = Join-Path $WorkDir "libs"
New-Item -ItemType Directory -Force $libStage | Out-Null
foreach ($item in $termuxDebs) {
    $name, $rel, $kind = $item
    $deb = Join-Path $debs "$name.deb"
    if (-not (Test-Path $deb)) {
        curl.exe -sSL -o $deb "$termuxBase/$rel"
        if (-not (Test-Path $deb) -or (Get-Item $deb).Length -lt 1024) { throw "下载失败：$rel" }
    }
    $dir = Join-Path $ex $name
    New-Item -ItemType Directory -Force $dir | Out-Null
    Push-Location $dir
    tar -xf $deb 2>$null
    if (Test-Path "data.tar.xz") { tar -xf data.tar.xz }
    Pop-Location

    $usr = Join-Path $dir "data\data\com.termux\files\usr"
    if ($kind -eq "site") {
        $sp = Join-Path $usr "lib\python3.14\site-packages"
        if (Test-Path $sp) { Copy-Item "$sp\*" $site -Recurse -Force }
        # 旧安装残留的 _rust 空壳会遮蔽 _rust.abi3.so，必须删掉
        Remove-Item (Join-Path $site "cryptography\hazmat\bindings\_rust") -Recurse -Force -ErrorAction SilentlyContinue
    }
    elseif ($kind -eq "lib") {
        Get-ChildItem (Join-Path $usr "lib") -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match "\.so" } |
            ForEach-Object { Copy-Item $_.FullName $libStage -Force }
    }
}

# ---------------------------------------------------------------- 3. 自编 C 扩展
Write-Step "3/5 交叉编译 cffi / argon2-cffi-bindings / regex"

if (-not $NdkRoot) {
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "c:\Users\linch\AppData\Local\Android\Sdk" }
    $NdkRoot = (Get-ChildItem (Join-Path $sdkRoot "ndk") -Directory | Sort-Object Name | Select-Object -Last 1).FullName
}
$clang = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"
if (-not (Test-Path $clang)) { throw "找不到 clang：$clang" }

$pyInc = Join-Path $ex "python\data\data\com.termux\files\usr\include\python3.14"
$ffiInc = Join-Path $ex "libffi\data\data\com.termux\files\usr\include"
$jniLibs = Join-Path $PSScriptRoot "..\runtime-host\src\main\jniLibs\x86_64"
if (-not (Test-Path (Join-Path $pyInc "Python.h"))) { throw "缺少 Python 头文件：$pyInc" }

function Invoke-Clang([string[]]$arguments, [string]$outFile) {
    & $clang @arguments
    if ($LASTEXITCODE -ne 0) { throw "clang 编译失败：$outFile" }
}

# 3.1 cffi：Termux 的 cryptography 是 cffi 实现，没有它整条链断
$cffiDir = Join-Path $src "cffi-2.0.0"
if (-not (Test-Path $cffiDir)) {
    & pip download --no-binary :all: --no-deps -d $src cffi==2.0.0
    tar -xzf (Join-Path $src "cffi-2.0.0.tar.gz") -C $src
}
$cffiSo = Join-Path $build "_cffi_backend.cpython-314-x86_64-linux-android.so"
Invoke-Clang @(
    "--target=x86_64-linux-android24", "-shared", "-fPIC", "-O2",
    "-I$pyInc", "-I$pyInc\internal", "-I$ffiInc",
    "-o", $cffiSo, (Join-Path $cffiDir "src\c\_cffi_backend.c"),
    "-L$jniLibs", "-lpython3.14", "-lffi"
) $cffiSo
# 必须显式链接 libpython3.14，否则 bionic 报 cannot locate symbol PyExc_ImportError

if (-not (Test-Path (Join-Path $site "cffi"))) {
    & pip install --no-deps --target (Join-Path $WorkDir "cffi-install") cffi==2.0.0
    Copy-Item (Join-Path $WorkDir "cffi-install\cffi") $site -Recurse -Force
    Copy-Item (Join-Path $WorkDir "cffi-install\cffi-2.0.0.dist-info") $site -Recurse -Force
}
Copy-Item $cffiSo $site -Force

# 3.2 argon2-cffi-bindings：先用宿主机 cffi 生成 _ffi.c，再用 clang 编
$argonDir = Join-Path $src "argon2_cffi_bindings-25.1.0"
if (-not (Test-Path $argonDir)) {
    & pip download --no-binary :all: --no-deps -d $src argon2-cffi-bindings==25.1.0
    tar -xzf (Join-Path $src "argon2_cffi_bindings-25.1.0.tar.gz") -C $src
}
Push-Location $argonDir
python -c "import importlib.util; spec=importlib.util.spec_from_file_location('b','src/_argon2_cffi_bindings/_ffi_build.py'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m); m.ffi.emit_c_code('_ffi.c')"
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "argon2 _ffi.c 生成失败（宿主机需装 cffi）" }
Invoke-Clang @(
    "--target=x86_64-linux-android24", "-shared", "-fPIC", "-O2", "-msse2",
    "-I$pyInc", "-I$pyInc\internal", "-Iextras\libargon2\include",
    "-o", "_ffi.abi3.so", "_ffi.c",
    "extras\libargon2\src\argon2.c", "extras\libargon2\src\blake2\blake2b.c",
    "extras\libargon2\src\core.c", "extras\libargon2\src\encoding.c",
    "extras\libargon2\src\opt.c", "extras\libargon2\src\thread.c",
    "-L$jniLibs", "-lpython3.14", "-lffi"
) "_ffi.abi3.so"
Pop-Location

$argonInstall = Join-Path $WorkDir "argon-install"
if (-not (Test-Path $argonInstall)) {
    & pip install --no-deps --target $argonInstall argon2-cffi-bindings==25.1.0
}
Copy-Item "$argonInstall\*" $site -Recurse -Force
Copy-Item (Join-Path $argonDir "_ffi.abi3.so") (Join-Path $site "_argon2_cffi_bindings") -Force

# 3.3 regex：PyPI 的 manylinux 版在 bionic 上崩溃
$regexDir = Join-Path $src "regex-2026.1.15"
if (-not (Test-Path $regexDir)) {
    & pip download --no-binary :all: --no-deps -d $src regex==2026.1.15
    tar -xzf (Join-Path $src "regex-2026.1.15.tar.gz") -C $src
}
$regexSo = Join-Path $regexDir "_regex.cpython-314-x86_64-linux-android.so"
Push-Location $regexDir
Invoke-Clang @(
    "--target=x86_64-linux-android24", "-shared", "-fPIC", "-O2",
    "-I$pyInc", "-I$pyInc\internal", "-Isrc",
    "-o", "_regex.cpython-314-x86_64-linux-android.so",
    "src\_regex.c", "src\_regex_unicode.c", "-L$jniLibs", "-lpython3.14"
) $regexSo
Pop-Location
New-Item -ItemType Directory -Force (Join-Path $site "regex") | Out-Null
Copy-Item $regexSo (Join-Path $site "regex") -Force

# ---------------------------------------------------------------- 4. 推送到设备
Write-Step "4/5 打包并推送到 $Package"
$archive = Join-Path $WorkDir "site.tgz"
tar -czf $archive -C $site .
if (-not (Test-Path $archive)) { throw "打包失败" }

if (-not $SkipPush) {
    Invoke-Adb @("push", $archive, "/data/local/tmp/octop-site.tgz") | Out-Null
    Invoke-Adb @("shell", "run-as $Package sh -c 'mkdir -p files/python-site && cat /data/local/tmp/octop-site.tgz > files/site.tgz && cd files/python-site && tar -xzf ../site.tgz && rm -f ../site.tgz && echo EXTRACT_OK'") | Out-Null

    # 原生库进 files/runtime/lib（App 启动时由 assets 落盘，这里补的是 Termux 版本的库）
    foreach ($lib in Get-ChildItem $libStage -File) {
        Invoke-Adb @("push", $lib.FullName, "/data/local/tmp/$($lib.Name)") | Out-Null
        Invoke-Adb @("shell", "cat /data/local/tmp/$($lib.Name) | run-as $Package sh -c 'cat > files/runtime/lib/$($lib.Name)'") | Out-Null
    }
}

# ---------------------------------------------------------------- 5. 冒烟
Write-Step "5/5 设备端冒烟"
$smoke = Join-Path $WorkDir "smoke.py"
@'
import importlib, sys
names = ["octop", "octop_harness", "octop_gateway", "octop_memory",
         "cryptography.exceptions", "argon2", "regex", "lxml.etree", "PIL.Image", "pptx"]
bad = 0
for n in names:
    try:
        importlib.import_module(n)
        print("OK", n)
    except Exception as e:
        bad += 1
        print("FAIL", n, type(e).__name__, e)
sys.exit(1 if bad else 0)
'@ | Set-Content -Path $smoke -Encoding utf8
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Get-Content $smoke -Raw).Replace("`r`n", "`n")))
Invoke-Adb @("shell", "run-as $Package sh -c 'echo $b64 | base64 -d > files/smoke.py'") | Out-Null

Write-Host "冒烟脚本已就位：files/smoke.py（用 App 的 RuntimeHost 环境执行，或参考 docs/STATE.md 的 run.sh）"
Write-Host "完成。"
