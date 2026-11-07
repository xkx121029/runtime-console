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
    # 用 zip 资源而不是逐文件 assets：aapt2 的默认忽略规则会丢掉 `_*` 开头的文件/目录
    # （实测 zipfile/_path 被丢，导致 import zipfile 失败），zip 内条目不受该规则影响，
    # 同时也把安装时的逐文件复制变成一次顺序解压。
    $assetDst = Join-Path $Assets "python-stdlib"
    if (Test-Path $assetDst) { Remove-Item $assetDst -Recurse -Force }
    $stageStd = Join-Path $PSScriptRoot "..\.tmp\stdlib-stage"
    if (Test-Path $stageStd) { Remove-Item $stageStd -Recurse -Force }
    New-Item -ItemType Directory -Force $stageStd | Out-Null
    # 只要纯 Python 与 lib-dynload（C 扩展），排除 __pycache__ / 测试数据
    $copy = Get-ChildItem $pyStdAbs -Recurse -File | Where-Object {
        $_.Extension -notin @(".pyc", ".pyo") -and $_.FullName -notmatch '__pycache__|[\\/]tests?[\\/]'
    }
    foreach ($f in $copy) {
        $rel = $f.FullName.Substring($pyStdAbs.Length).TrimStart('\')
        $target = Join-Path $stageStd $rel
        New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
        Copy-Item $f.FullName $target -Force
    }
    $sum = (Get-ChildItem $stageStd -Recurse -File | Measure-Object Length -Sum).Sum
    Write-Output ("STAGE python-stdlib {0} files {1:N1}MB" -f (Get-ChildItem $stageStd -Recurse -File).Count, ($sum/1MB))
    $dyn = Join-Path $stageStd "lib-dynload"
    if (Test-Path $dyn) { Write-Output ("STAGE lib-dynload {0} modules" -f (Get-ChildItem $dyn -File).Count) }

    # sitecustomize：解释器启动时把版本化 soname 的库按绝对路径预加载。
    # 背景：Android 给应用进程的是 classloader-namespace，只搜 APK lib/<abi>/，
    # LD_LIBRARY_PATH 指向 filesDir 会被过滤，所以 C 扩展的 DT_NEEDED（libz.so.1 等）
    # 必须先以绝对路径 dlopen 进进程，之后按 soname 命中缓存。
    $site = @'
# 兼容层（由 scripts/build-runtime.ps1 生成，勿手改）
import os
import sys

_lib_dir = os.environ.get("RUNTIME_COMPAT_LIB_DIR")
if _lib_dir and os.path.isdir(_lib_dir):
    try:
        import ctypes
        for _name in sorted(os.listdir(_lib_dir)):
            if ".so" not in _name:
                continue
            try:
                ctypes.CDLL(os.path.join(_lib_dir, _name), mode=ctypes.RTLD_GLOBAL)
            except OSError as _error:
                print("compat-preload-warn: %s: %s" % (_name, _error), file=sys.stderr)
    except Exception as _error:  # noqa: BLE001
        print("compat-preload-failed: %s" % _error, file=sys.stderr)
'@
    [System.IO.File]::WriteAllText((Join-Path $stageStd "sitecustomize.py"), $site, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "STAGE sitecustomize.py"

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Force $Assets | Out-Null
    $zipPath = Join-Path $Assets "python-stdlib.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    # 手工建条目：.NET Framework 的 CreateFromDirectory 在 Windows 上用反斜杠做分隔符，
    # 解压到 Android 会变成带 "\" 的扁平文件名，导致 stdlib 目录结构全错。
    $stageAbs = (Resolve-Path $stageStd).Path
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($f in Get-ChildItem $stageAbs -Recurse -File) {
            $rel = $f.FullName.Substring($stageAbs.Length).TrimStart('\').Replace('\', '/')
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal)
        }
    }
    finally { $zip.Dispose() }
    Write-Output ("STAGE python-stdlib.zip {0:N1}MB" -f ((Get-Item $zipPath).Length/1MB))
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
    "libandroid-posix-semaphore.so", "libbz2.so.1.0.8", "libexpat.so.1.13.1",
    "libgdbm.so", "libgdbm_compat.so", "liblzma.so.5.8.4", "libncursesw.so.6.5", "libpanelw.so.6.5",
    "libreadline.so.8.3", "libzstd.so.1.5.7", "libz.so.1.4", "libcrypto.so.3", "libssl.so.3"
)
# 说明：libsqlite3.so / libffi.so / libandroid-support.so / libpython3.14.so 在 jniLibs 里已是同名，
# 系统会直接从 nativeLibraryDir 找到，无需重复放进兼容目录。
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

