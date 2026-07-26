# 冷启动 + 滚动帧率基准。用法：
#   .\scripts\verify\bench.ps1 -Apk out.apk -Label "after" -Rounds 3
# 注意必须用 release 构建测：debug 的 JIT 开销会把帧率差异全部盖掉（详见 optimization-round2.md §5）。
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Label = "run",
    [int]$Rounds = 3,
    [string]$Adb = ""
)

$adb = if ($Adb) { $Adb }
       elseif ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }
       else { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { throw "找不到 adb：$adb（用 -Adb 指定）" }
$pkg = "com.example.topnews"

& $adb uninstall $pkg 2>&1 | Out-Null
& $adb install -r $Apk 2>&1 | Out-Null
& $adb shell pm grant $pkg android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant $pkg android.permission.ACCESS_COARSE_LOCATION

# 冷启动：连测 3 次，取中位数（首次含 dex 校验/profile 安装，单独记录）
$cold = @()
for ($i = 0; $i -lt 3; $i++) {
    & $adb shell am force-stop $pkg
    Start-Sleep -Seconds 2
    $out = & $adb shell am start -W -n "$pkg/.MainActivity"
    $t = ($out | Select-String "TotalTime:\s*(\d+)").Matches.Groups[1].Value
    $cold += [int]$t
    Start-Sleep -Seconds 8
}

# 预热一轮滚动，把图片灌进 Coil 磁盘/内存缓存，之后测的才是渲染而非网络
for ($i = 0; $i -lt 8; $i++) { & $adb shell input swipe 640 2200 640 700 300; Start-Sleep -Milliseconds 400 }
for ($i = 0; $i -lt 8; $i++) { & $adb shell input swipe 640 700 640 2200 300; Start-Sleep -Milliseconds 400 }
Start-Sleep -Seconds 3

$results = @()
for ($r = 0; $r -lt $Rounds; $r++) {
    & $adb shell dumpsys gfxinfo $pkg reset | Out-Null
    for ($i = 0; $i -lt 8; $i++) { & $adb shell input swipe 640 2200 640 700 300; Start-Sleep -Milliseconds 350 }
    for ($i = 0; $i -lt 8; $i++) { & $adb shell input swipe 640 700 640 2200 300; Start-Sleep -Milliseconds 350 }
    $dump = & $adb shell dumpsys gfxinfo $pkg
    $total = [int]($dump | Select-String "Total frames rendered:\s*(\d+)").Matches.Groups[1].Value
    $janky = [int]($dump | Select-String "Janky frames:\s*(\d+)").Matches.Groups[1].Value
    $p50 = [int]($dump | Select-String "50th percentile:\s*(\d+)ms").Matches.Groups[1].Value
    $p90 = [int]($dump | Select-String "90th percentile:\s*(\d+)ms").Matches.Groups[1].Value
    $p99 = [int]($dump | Select-String "99th percentile:\s*(\d+)ms").Matches.Groups[1].Value
    $slowUi = [int]($dump | Select-String "Number Slow UI thread:\s*(\d+)").Matches.Groups[1].Value
    $results += [pscustomobject]@{
        Round = $r + 1; Total = $total; Janky = $janky
        JankyPct = [math]::Round(100.0 * $janky / [math]::Max($total, 1), 2)
        P50 = $p50; P90 = $p90; P99 = $p99; SlowUi = $slowUi
    }
    Start-Sleep -Seconds 2
}

"=== $Label ==="
$coldSorted = @($cold | Sort-Object)
$coldMedian = $coldSorted[[math]::Floor(($coldSorted.Count - 1) / 2)]
"cold start (ms): $($cold -join ', ')  median=$coldMedian"
$results | Format-Table -AutoSize | Out-String
$avgJank = [math]::Round((($results | Measure-Object -Property JankyPct -Average).Average), 2)
$avgP50 = [math]::Round((($results | Measure-Object -Property P50 -Average).Average), 1)
$avgP90 = [math]::Round((($results | Measure-Object -Property P90 -Average).Average), 1)
$avgP99 = [math]::Round((($results | Measure-Object -Property P99 -Average).Average), 1)
$avgSlow = [math]::Round((($results | Measure-Object -Property SlowUi -Average).Average), 1)
"AVG  janky=$avgJank%  p50=${avgP50}ms  p90=${avgP90}ms  p99=${avgP99}ms  slowUiThread=$avgSlow"
