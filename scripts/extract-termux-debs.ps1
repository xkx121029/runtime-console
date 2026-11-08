param(
    [string]$DebDir = ".tmp\tools\termux",
    [string]$RootDir = ".tmp\tools\termux-root"
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $RootDir | Out-Null
$work = ".tmp\tools\deb-work"
New-Item -ItemType Directory -Force $work | Out-Null

foreach ($deb in Get-ChildItem $DebDir -Filter *.deb) {
    $tmp = Join-Path $work $deb.BaseName
    New-Item -ItemType Directory -Force $tmp | Out-Null
    tar -xf $deb.FullName -C $tmp
    $data = Get-ChildItem $tmp -Filter "data.tar.*" | Select-Object -First 1
    if ($data) {
        tar -xf $data.FullName -C $RootDir
        Write-Output ("EXTRACTED " + $deb.Name)
    } else {
        Write-Output ("NO-DATA " + $deb.Name)
    }
}
$usr = Join-Path $RootDir "data/data/com.termux/files/usr"
if (Test-Path $usr) {
    Write-Output ("USR-SIZE-MB " + [math]::Round((Get-ChildItem $usr -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1))
    Get-ChildItem (Join-Path $usr "bin") -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -in @("node","python3.14","python3","sh") } | Select-Object Name,Length
}
