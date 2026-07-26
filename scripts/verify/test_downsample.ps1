# Verify /image?w=336 thumbnail downsampling (compare transfer sizes)
$base = "http://193.112.193.205"

function Test-Tab($name, $listUrl) {
    Write-Host "== $name =="
    try { $r = Invoke-RestMethod $listUrl } catch { Write-Host "  list request failed: $_"; return }
    $imgs = @($r.items | Where-Object { $_.image_url } | Select-Object -First 3)
    if ($imgs.Count -eq 0) { Write-Host "  no items with image"; return }
    $totO = 0; $totW = 0
    foreach ($it in $imgs) {
        $u = $base + $it.image_url
        $sep = "?"; if ($u.Contains("?")) { $sep = "&" }
        try {
            $o = (Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 60).Content.Length
            $w = (Invoke-WebRequest -Uri ($u + $sep + "w=336") -UseBasicParsing -TimeoutSec 60).Content.Length
        } catch { Write-Host "  image request failed: $_"; continue }
        $totO += $o; $totW += $w
        "  orig={0,9}B   w336={1,9}B" -f $o, $w
    }
    if ($totO -gt 0) {
        $pct = [math]::Round(100 - 100.0 * $totW / $totO, 1)
        "  total orig=${totO}B  w336=${totW}B  saved ${pct}%"
        if ($totW -lt $totO * 0.8) { Write-Host "  [OK] downsampling works" -ForegroundColor Green }
        else { Write-Host "  [FAIL] no size reduction - check Pillow install and service restart on server" -ForegroundColor Red }
    }
}

Test-Tab "recommendations" "$base/v1/recommendations?page=1&pageSize=30&fields=list"
Test-Tab "papers"          "$base/v1/papers/recommendations?page=1&pageSize=30&fields=list"
