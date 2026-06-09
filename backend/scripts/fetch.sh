#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf '%s\n' \
    'Usage: backend/scripts/fetch.sh [options]' \
    '' \
    'Fetch TopNews backend data. By default this runs news ingest, paper ingest,' \
    'and paper figure enrichment in order.' \
    '' \
    'Options:' \
    '  --config PATH             Sources JSON config path.' \
    '  --db PATH                 SQLite database path.' \
    '  --news-limit N            Articles to fetch per news source. Default: 80.' \
    '  --papers-limit N          arXiv papers to fetch. Default: 100.' \
    '  --paper-source SOURCE     arXiv source: auto, rss, or api. Default: auto.' \
    '  --figures-limit N         Papers to enrich with cached figures. Default: 10.' \
    '  --figure-delay SECONDS    Delay between figure fetches. Default: 3.' \
    '  --force-figures           Re-check papers that already have figure status.' \
    '  --skip-news               Do not run news ingest.' \
    '  --skip-papers             Do not run paper ingest.' \
    '  --skip-figures            Do not run paper figure enrichment.' \
    '  -h, --help                Show this help.' \
    '' \
    'Environment overrides:' \
    '  PYTHON_BIN, TOPNEWS_CONFIG, TOPNEWS_DB, NEWS_LIMIT_PER_SOURCE,' \
    '  PAPERS_LIMIT, PAPER_SOURCE, FIGURE_LIMIT, FIGURE_DELAY_SECONDS,' \
    '  FORCE_FIGURES, SKIP_NEWS, SKIP_PAPERS, SKIP_FIGURES.'
}

log() {
  printf '[%(%Y-%m-%d %H:%M:%S)T] %s\n' -1 "$*"
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

bool_enabled() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|y|Y|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

script_path="${BASH_SOURCE[0]}"
script_dir="${script_path%/*}"
if [[ "$script_dir" == "$script_path" ]]; then
  script_dir="."
fi
script_dir="$(cd -- "$script_dir" && pwd)"
backend_dir="$(cd -- "${script_dir}/.." && pwd)"
repo_root="$(cd -- "${backend_dir}/.." && pwd)"

python_bin="${PYTHON_BIN:-python}"
news_limit="${NEWS_LIMIT_PER_SOURCE:-80}"
papers_limit="${PAPERS_LIMIT:-100}"
paper_source="${PAPER_SOURCE:-auto}"
figures_limit="${FIGURE_LIMIT:-10}"
figure_delay="${FIGURE_DELAY_SECONDS:-3}"
force_figures="${FORCE_FIGURES:-0}"
skip_news="${SKIP_NEWS:-0}"
skip_papers="${SKIP_PAPERS:-0}"
skip_figures="${SKIP_FIGURES:-0}"
config_path="${TOPNEWS_CONFIG:-}"
db_path="${TOPNEWS_DB:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      [[ $# -ge 2 ]] || die "--config requires a value"
      config_path="$2"
      shift 2
      ;;
    --db)
      [[ $# -ge 2 ]] || die "--db requires a value"
      db_path="$2"
      shift 2
      ;;
    --news-limit)
      [[ $# -ge 2 ]] || die "--news-limit requires a value"
      news_limit="$2"
      shift 2
      ;;
    --papers-limit)
      [[ $# -ge 2 ]] || die "--papers-limit requires a value"
      papers_limit="$2"
      shift 2
      ;;
    --paper-source)
      [[ $# -ge 2 ]] || die "--paper-source requires a value"
      paper_source="$2"
      shift 2
      ;;
    --figures-limit)
      [[ $# -ge 2 ]] || die "--figures-limit requires a value"
      figures_limit="$2"
      shift 2
      ;;
    --figure-delay)
      [[ $# -ge 2 ]] || die "--figure-delay requires a value"
      figure_delay="$2"
      shift 2
      ;;
    --force-figures)
      force_figures="1"
      shift
      ;;
    --skip-news)
      skip_news="1"
      shift
      ;;
    --skip-papers)
      skip_papers="1"
      shift
      ;;
    --skip-figures)
      skip_figures="1"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

case "$paper_source" in
  auto|rss|api) ;;
  *) die "--paper-source must be one of: auto, rss, api" ;;
esac

[[ -n "$config_path" ]] && export TOPNEWS_CONFIG="$config_path"
[[ -n "$db_path" ]] && export TOPNEWS_DB="$db_path"

cd "$repo_root"

log "Using backend at ${backend_dir}"
log "Using Python: ${python_bin}"

if ! bool_enabled "$skip_news"; then
  log "Fetching news sources, limit per source: ${news_limit}"
  "$python_bin" -m backend.topnews_backend.cli ingest --limit-per-source "$news_limit"
else
  log "Skipping news ingest"
fi

if ! bool_enabled "$skip_papers"; then
  log "Fetching arXiv papers, limit: ${papers_limit}, source: ${paper_source}"
  "$python_bin" -m backend.topnews_backend.cli papers-ingest --limit "$papers_limit" --source "$paper_source"
else
  log "Skipping paper ingest"
fi

if ! bool_enabled "$skip_figures"; then
  figure_args=(paper-figures-ingest --limit "$figures_limit" --delay-seconds "$figure_delay")
  if bool_enabled "$force_figures"; then
    figure_args+=(--force)
  fi

  log "Fetching paper figures, limit: ${figures_limit}, delay seconds: ${figure_delay}"
  "$python_bin" -m backend.topnews_backend.cli "${figure_args[@]}"
else
  log "Skipping paper figure enrichment"
fi

log "Fetch completed"
