<#
    运行时打包脚本（生成 jniLibs 与 assets）

    作用：把 Termux 预编译的 Node / CPython 及其依赖库整理成 Android APK 可加载的形态：
      1. 可执行文件与共享库统一改名成 `lib*.so`（Android 只从 APK 的 lib/<abi>/ 里解出可执行原生库）；
      2. 版本化 soname（如 libcrypto.so.3）在同长度内改写为不冲突的名字（如 libxcrypto.so），
         避免落到系统 /system/lib64 下的同名库；
      3. Python 标准库作为 assets 打入 APK，首启解压到 filesDir（纯数据，不需要执行权限）。

    输入：Termux 包解包树（默认 .tmp/tools/termux-root/.../usr，由 scripts/fetch-termux.ps1 产出）
    输出：runtime-host/src/main/jniLibs/<abi>/lib*.so 与 runtime-host/src/main/assets/python-stdlib/
#>
param(
    [string]$Abi = "x86_64",
    [string]$TermuxRoot = ".tmp/tools/termux-root/data/data/com.termux/files/usr",
    [string]$JniLibs = "runtime-host/src/main/jniLibs",
    [string]$Assets = "runtime-host/src/main/assets",
    [string]$PythonTag = "3.14"
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = Split-Path -Parent $scriptRoot
Set-Location $repoRoot

if (-not (Test-Path $TermuxRoot)) { throw "找不到 Termux 解包树：$TermuxRoot（先运行 scripts/fetch-termux.ps1 + 解包）" }

$dst = Join-Path $JniLibs $Abi
New-Item -ItemType Directory -Force $dst | Out-Null

# 原文件名 -> 目标文件名；Patch 为需要改写的 DT_NEEDED / SONAME 字符串映射
$plan = @(
    @{ Src = "bin/node";                  Dst = "libnode_exec.so";       Patch = $true },
    @{ Src = "bin/python3.14";            Dst = "libpython_exec.so";     Patch = $true },
    @{ Src = "lib/libpython3.14.so";      Dst = "libpython3.14.so";      Patch = $true },
    @{ Src = "lib/libcares.so";           Dst = "libcares.so";           Patch = $true },
    @{ Src = "lib/libsqlite3.so";         Dst = "libsqlite3.so";         Patch = $true },
    @{ Src = "lib/libffi.so";             Dst = "libffi.so";             Patch = $true },
    @{ Src = "lib/libc++_shared.so";      Dst = "libc++_shared.so";      Patch = $true },
    @{ Src = "lib/libandroid-support.so"; Dst = "libandroid-support.so"; Patch = $true },
    @{ Src = "lib/libcrypto.so.3";        Dst = "libxcrypto.so";         Patch = $true },
    @{ Src = "lib/libssl.so.3";           Dst = "libxssl.so";            Patch = $true },
    @{ Src = "lib/libz.so.1.4";           Dst = "libxz.so";              Patch = $true },
    @{ Src = "lib/libicuuc.so.78.3";      Dst = "libxicuuc.so";          Patch = $true },
    @{ Src = "lib/libicui18n.so.78.3";    Dst = "libxicui18n.so";        Patch = $true },
    @{ Src = "lib/libicudata.so.78.3";    Dst = "libxicudata.so";        Patch = $true }
)

# 需要改写的 soname 映射（原名 -> 新名），要求新名不短于原名以外的约束：新名必须更短或等长
$renameMap = @{
    "libcrypto.so.3"    = "libxcrypto.so"
    "libssl.so.3"       = "libxssl.so"
    "libz.so.1"         = "libxz.so"
    "libicuuc.so.78"    = "libxicuuc.so"
    "libicui18n.so.78"  = "libxicui18n.so"
    "libicudata.so.78"  = "libxicudata.so"
}

$latin1 = [System.Text.Encoding]::GetEncoding(28591)

function Patch-Elf([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $text = $latin1.GetString($bytes)
    $changed = $false
    foreach ($k in $renameMap.Keys) {
        $new = $renameMap[$k]
        if ($new.Length -gt $k.Length) { throw "改名后更长，无法原地替换：$k -> $new" }
        $padded = $new.PadRight($k.Length, [char]0)
        if ($text.Contains($k)) {
            $text = $text.Replace($k, $padded)
            $changed = $true
        }
    }
    if ($changed) {
        [System.IO.File]::WriteAllBytes($path, $latin1.GetBytes($text))
    }
    return $changed
}

$total = 0
foreach ($item in $plan) {
    $src = Join-Path $TermuxRoot $item.Src
    if (-not (Test-Path $src)) { throw "缺少文件：$src" }
    $out = Join-Path $dst $item.Dst
    Copy-Item $src $out -Force
    $patched = Patch-Elf $out
    $len = (Get-Item $out).Length
    $total += $len
    Write-Output ("STAGE {0} <- {1} {2:N2}MB patched={3}" -f $item.Dst, $item.Src, ($len/1MB), $patched)
}

# 校验：改名后的库不应再引用旧 soname
$readelf = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (Test-Path $readelf) {
    foreach ($f in Get-ChildItem $dst -Filter "lib*.so") {
        $needed = & $readelf -d $f.FullName | Select-String 'NEEDED' | ForEach-Object { ($_ -split '\[|\]')[1] }
        $bad = $needed | Where-Object { $renameMap.ContainsKey($_) }
        if ($bad) { throw ("仍是旧 soname：{0} -> {1}" -f $f.Name, ($bad -join ',')) }
    }
    Write-Output "VERIFY-ELF OK"
}

# ---- Python 标准库 -> assets ----
$pyStd = Join-Path $TermuxRoot "lib/python$PythonTag"
if (Test-Path $pyStd) {
    $pyStdAbs = (Resolve-Path $pyStd).Path
    $assetDst = Join-Path $Assets "python-stdlib"
    if (Test-Path $assetDst) { Remove-Item $assetDst -Recurse -Force }
    New-Item -ItemType Directory -Force $assetDst | Out-Null
    # 只要纯 Python 与 lib-dynload（C 扩展），排除 __pycache__ / 测试数据
    $copy = Get-ChildItem $pyStdAbs -Recurse -File | Where-Object {
        $_.Extension -notin @(".pyc", ".pyo") -and $_.FullName -notmatch '__pycache__|/test/|/tests/'
    }
    foreach ($f in $copy) {
        $rel = $f.FullName.Substring($pyStdAbs.Length).TrimStart('\')
        $target = Join-Path $assetDst $rel
        New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
        Copy-Item $f.FullName $target -Force
    }
    $sum = (Get-ChildItem $assetDst -Recurse -File | Measure-Object Length -Sum).Sum
    Write-Output ("STAGE python-stdlib {0} files {1:N1}MB" -f (Get-ChildItem $assetDst -Recurse -File).Count, ($sum/1MB))
    $dyn = Join-Path $assetDst "lib-dynload"
    if (Test-Path $dyn) { Write-Output ("STAGE lib-dynload {0} modules" -f (Get-ChildItem $dyn -File).Count) }
} else {
    Write-Output "WARN 未找到 Python 标准库：$pyStd"
}

Write-Output ("DONE jniLibs {0:N1}MB -> {1}" -f ($total/1MB), $dst)

# ---- 兼容库（保留原始 soname）-> assets/runtime-libs ----
# 原因：Python 的 C 扩展（lib-dynload、pip 装的原生轮子）在 DT_NEEDED 里写死了版本化 soname
# （libz.so.1 / libcrypto.so.3 / …），而 Android 只允许 lib/<abi>/ 下 `lib*.so` 形态的文件，
# 这些名字无法直接放进 jniLibs。做法是把它们原样打进 assets，首启解压到 filesDir/runtime/lib，
# 再把该目录加进 LD_LIBRARY_PATH（实测 bionic 允许从 app 数据目录 dlopen 库，只是不允许 exec）。
$compat = @(
    "libandroid-posix-semaphore.so", "libandroid-support.so", "libbz2.so.1.0.8", "libexpat.so.1.13.1",
    "libgdbm.so", "libgdbm_compat.so", "liblzma.so.5.8.4", "libncursesw.so.6.5", "libpanelw.so.6.5",
    "libreadline.so.8.3", "libzstd.so.1.5.7", "libz.so.1.4", "libcrypto.so.3", "libssl.so.3",
    "libffi.so", "libsqlite3.so", "libpython3.14.so"
)
$compatDst = Join-Path $Assets "runtime-libs"
if (Test-Path $compatDst) { Remove-Item $compatDst -Recurse -Force }
New-Item -ItemType Directory -Force $compatDst | Out-Null
$compatBytes = 0
foreach ($name in $compat) {
    $src = Join-Path $TermuxRoot "lib/$name"
    if (-not (Test-Path $src)) { Write-Output "WARN 兼容库缺失：$name"; continue }
    Copy-Item $src (Join-Path $compatDst $name) -Force
    $compatBytes += (Get-Item $src).Length
}
Write-Output ("STAGE runtime-libs {0} files {1:N1}MB" -f (Get-ChildItem $compatDst -File).Count, ($compatBytes/1MB))

