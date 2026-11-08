<#
    本地门禁：构建 + 单元测试 + 补丁可应用性

    与 .github/workflows/ci.yml 跑的是同一组命令。当前没有可用远端，
    CI 无法真正触发，因此以本脚本的结果作为提交门禁（见 docs/STATE.md）。

    用法：
      powershell -ExecutionPolicy Bypass -File scripts\ci-local.ps1
#>
param(
    [switch]$SkipPatches
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location $repoRoot

if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot")) {
    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

# 路径含 "&" 时 AGP 无法直接用，优先走目录联接
$gradlew = if (Test-Path "C:\rc\gradlew.bat") { "C:\rc\gradlew.bat" } else { Join-Path $repoRoot "gradlew.bat" }
$gradleArgs = if (Test-Path "C:\rc\gradlew.bat") { @("-p", "C:\rc") } else { @() }

$failed = @()

Write-Host "== 1/2 构建 + 单元测试 ==" -ForegroundColor Cyan
& $gradlew @gradleArgs :app:assembleDebug :runtime-host:testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { $failed += "gradle build/test" }

if (-not $SkipPatches) {
    Write-Host "== 2/2 补丁可应用性 ==" -ForegroundColor Cyan
    $tree = ".tmp\dsh-npm"
    if (-not (Test-Path (Join-Path $tree "node_modules"))) {
        Write-Host "SKIP：没有 $tree\node_modules（先跑 scripts/stage-dsh-runtime.ps1）" -ForegroundColor Yellow
    }
    else {
        foreach ($patch in Get-ChildItem "patches" -Recurse -Filter *.patch) {
            $abs = (Resolve-Path $patch.FullName).Path
            # 注意：不能直接 git -C $tree apply --check —— node_modules 被 .gitignore 忽略，
            # git 会静默 "Skipped patch" 并以 0 退出，检查形同虚设。
            # 把补丁涉及的文件复制到仓库外的临时目录再检查。
            $tmp = Join-Path $env:TEMP ("patchcheck-" + [guid]::NewGuid().ToString("N"))
            $targets = Select-String -Path $abs -Pattern '^\+\+\+ b/(.+)$' |
                ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() }
            $copied = 0
            foreach ($rel in $targets) {
                $src = Join-Path $tree $rel
                if (Test-Path $src) {
                    $dst = Join-Path $tmp $rel
                    New-Item -ItemType Directory -Force (Split-Path -Parent $dst) | Out-Null
                    Copy-Item $src $dst
                    $copied++
                }
            }
            if ($copied -eq 0) {
                Write-Host "SKIP $($patch.Name)：目标文件不在运行时树里" -ForegroundColor Yellow
                Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
                continue
            }

            # git 把“打不上”写到 stderr，而脚本是 Stop 模式：这里临时放宽，只看退出码
            $ErrorActionPreference = "Continue"
            & git -C $tmp apply --check -p1 $abs 2>&1 | Out-Null
            $forward = ($LASTEXITCODE -eq 0)
            if (-not $forward) {
                & git -C $tmp apply --check -R -p1 $abs 2>&1 | Out-Null
                $reverse = ($LASTEXITCODE -eq 0)
            }
            else { $reverse = $false }
            $ErrorActionPreference = "Stop"

            if ($forward) {
                Write-Host "APPLY-OK $($patch.Name)（未应用，可干净应用）" -ForegroundColor Green
            }
            elseif ($reverse) {
                Write-Host "APPLY-OK $($patch.Name)（已应用，反向可还原）" -ForegroundColor Green
            }
            else {
                Write-Host "APPLY-FAIL $($patch.Name)" -ForegroundColor Red
                $failed += "patch $($patch.Name)"
            }
            Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

if ($failed.Count -gt 0) {
    Write-Host ("门禁失败：" + ($failed -join "；")) -ForegroundColor Red
    exit 1
}
Write-Host "门禁通过" -ForegroundColor Green
