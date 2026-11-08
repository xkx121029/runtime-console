param(
    [string[]]$Roots = @("nodejs", "python"),
    [string]$OutDir = ".tmp\tools\termux",
    [string]$Index = ".tmp\tools\termux-Packages",
    [string]$BaseUrl = "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
    [int]$Attempts = 8
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $OutDir | Out-Null

$lines = Get-Content $Index
$pkgTable = @{}
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^Package: (.+)$') {
        $name = $Matches[1].Trim()
        $block = @()
        $j = $i
        while ($j -lt $lines.Count -and $lines[$j].Trim() -ne '') { $block += $lines[$j]; $j++ }
        $fnLine = ($block | Where-Object { $_ -match '^Filename: ' } | Select-Object -First 1)
        $depLine = ($block | Where-Object { $_ -match '^Depends: ' } | Select-Object -First 1)
        $sizeLine = ($block | Where-Object { $_ -match '^Size: ' } | Select-Object -First 1)
        $fn = if ($fnLine) { ($fnLine -join '') -replace '^Filename: ', '' } else { '' }
        $dep = if ($depLine) { ($depLine -join '') -replace '^Depends: ', '' } else { '' }
        $sz = if ($sizeLine) { [long](($sizeLine -join '') -replace '^Size: ', '') } else { 0 }
        if ($name -and $fn) { $pkgTable[$name] = @{ File = $fn.Trim(); Deps = $dep.Trim(); Size = $sz } }
    }
}

$resolved = [System.Collections.Generic.HashSet[string]]::new()
$queue = [System.Collections.Generic.Queue[string]]::new()
$Roots | ForEach-Object { $queue.Enqueue($_) }
while ($queue.Count -gt 0) {
    $name = $queue.Dequeue()
    if ($resolved.Contains($name)) { continue }
    if (-not $pkgTable.ContainsKey($name)) { Write-Output "MISS $name"; continue }
    [void]$resolved.Add($name)
    if ($pkgTable[$name].Deps) {
        foreach ($d in ($pkgTable[$name].Deps -split ',')) {
            $dn = ($d.Trim() -split '\s+')[0]
            if ($dn -and -not $resolved.Contains($dn)) { $queue.Enqueue($dn) }
        }
    }
}

$total = 0
foreach ($n in $resolved) { $total += $pkgTable[$n].Size }
Write-Output ("RESOLVED-COUNT " + $resolved.Count + " TOTAL-MB " + [math]::Round($total/1MB,1))

foreach ($name in $resolved) {
    $file = $pkgTable[$name].File
    $want = $pkgTable[$name].Size
    # Termux 的 deb 文件名里有 epoch 冒号（openssl_1:3.6.5_aarch64.deb）：
    # Test-Path 不接受含 ':' 的路径，直接落盘还会被写成 NTFS 备用数据流。
    # 本地用 '_' 替换，URL 里用 %3A 编码。
    $leaf = (Split-Path $file -Leaf) -replace ':', '_'
    $deb = Join-Path $OutDir $leaf
    $url = "$BaseUrl/" + ($file -replace ':', '%3A')
    for ($a = 1; $a -le $Attempts; $a++) {
        $have = if (Test-Path $deb) { (Get-Item $deb).Length } else { 0 }
        if ($have -ge $want -and $want -gt 0) { break }
        curl.exe -L -C - --silent --show-error --connect-timeout 20 --max-time 600 -o $deb $url 2>$null
    }
    $have = if (Test-Path $deb) { (Get-Item $deb).Length } else { 0 }
    Write-Output ("GET $name " + [math]::Round($have/1MB,2) + "/" + [math]::Round($want/1MB,2) + "MB")
}
