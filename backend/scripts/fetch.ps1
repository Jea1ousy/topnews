[CmdletBinding()]
param(
    [string]$ConfigPath = $env:TOPNEWS_CONFIG,

    [string]$DbPath = $env:TOPNEWS_DB,

    [Alias("news-limit")]
    [int]$NewsLimit = $(if ($env:NEWS_LIMIT_PER_SOURCE) { [int]$env:NEWS_LIMIT_PER_SOURCE } else { 80 }),

    [Alias("papers-limit")]
    [int]$PapersLimit = $(if ($env:PAPERS_LIMIT) { [int]$env:PAPERS_LIMIT } else { 100 }),

    [Alias("paper-source")]
    [ValidateSet("auto", "rss", "api")]
    [string]$PaperSource = $(if ($env:PAPER_SOURCE) { $env:PAPER_SOURCE } else { "auto" }),

    [Alias("figures-limit")]
    [int]$FiguresLimit = $(if ($env:FIGURE_LIMIT) { [int]$env:FIGURE_LIMIT } else { 10 }),

    [Alias("figure-delay")]
    [double]$FigureDelay = $(if ($env:FIGURE_DELAY_SECONDS) { [double]$env:FIGURE_DELAY_SECONDS } else { 3 }),

    [Alias("force-figures")]
    [switch]$ForceFigures,

    [Alias("skip-news")]
    [switch]$SkipNews,

    [Alias("skip-papers")]
    [switch]$SkipPapers,

    [Alias("skip-figures")]
    [switch]$SkipFigures,

    [string]$PythonBin = $(if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { "python" })
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message)
}

function Invoke-BackendCli {
    param([string[]]$CliArgs)

    & $PythonBin -m backend.topnews_backend.cli @CliArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Backend command failed: $PythonBin -m backend.topnews_backend.cli $($CliArgs -join ' ')"
    }
}

$scriptDir = Split-Path -Parent $PSCommandPath
$backendDir = Resolve-Path (Join-Path $scriptDir "..")
$repoRoot = Resolve-Path (Join-Path $backendDir "..")

if ($ConfigPath) {
    $env:TOPNEWS_CONFIG = $ConfigPath
}
if ($DbPath) {
    $env:TOPNEWS_DB = $DbPath
}

Set-Location $repoRoot

Write-Log "Using backend at $backendDir"
Write-Log "Using Python: $PythonBin"

if (-not $SkipNews) {
    Write-Log "Fetching news sources, limit per source: $NewsLimit"
    Invoke-BackendCli @("ingest", "--limit-per-source", "$NewsLimit")
} else {
    Write-Log "Skipping news ingest"
}

if (-not $SkipPapers) {
    Write-Log "Fetching arXiv papers, limit: $PapersLimit, source: $PaperSource"
    Invoke-BackendCli @("papers-ingest", "--limit", "$PapersLimit", "--source", "$PaperSource")
} else {
    Write-Log "Skipping paper ingest"
}

if (-not $SkipFigures) {
    $figureArgs = @("paper-figures-ingest", "--limit", "$FiguresLimit", "--delay-seconds", "$FigureDelay")
    if ($ForceFigures) {
        $figureArgs += "--force"
    }

    Write-Log "Fetching paper figures, limit: $FiguresLimit, delay seconds: $FigureDelay"
    Invoke-BackendCli $figureArgs
} else {
    Write-Log "Skipping paper figure enrichment"
}

Write-Log "Fetch completed"
