"""对多个来源的真实文章页，对比"开启/关闭导航过滤"抽出的正文块，确认没有误杀正文。"""
import sys
import pathlib
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from topnews_backend import fetchers  # noqa: E402

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)


def blocks_for(url: str, nav_filter: bool):
    saved = fetchers.ARTICLE_NAV_MIN_ANCHORS
    if not nav_filter:
        fetchers.ARTICLE_NAV_MIN_ANCHORS = 10**6  # 等效关闭
    try:
        raw = urllib.request.urlopen(
            urllib.request.Request(url, headers={"User-Agent": UA}), timeout=25
        ).read()
        parser = fetchers.ArticleDetailParser(url)
        parser.feed(raw.decode("utf-8", errors="ignore"))
        return parser.text_blocks
    finally:
        fetchers.ARTICLE_NAV_MIN_ANCHORS = saved


for url in sys.argv[1:]:
    try:
        off = blocks_for(url, nav_filter=False)
        on = blocks_for(url, nav_filter=True)
    except Exception as exc:  # noqa: BLE001
        print(f"\n### {url}\n  FAILED: {type(exc).__name__}: {exc}")
        continue
    dropped = [b for b in off if b not in on]
    total_chars_off = sum(len(b) for b in off)
    total_chars_on = sum(len(b) for b in on)
    print(f"\n### {url}")
    print(f"  blocks {len(off)} -> {len(on)}   chars {total_chars_off} -> {total_chars_on}")
    print(f"  dropped {len(dropped)} block(s):")
    for b in dropped[:6]:
        print(f"    - {b[:100]}")
    print("  first 3 kept:")
    for b in on[:3]:
        print(f"    + {b[:100]}")
