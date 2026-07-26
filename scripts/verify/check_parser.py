"""用线上真实文章页验证 ArticleDetailParser 的导航过滤效果。"""
import sys
import pathlib
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from topnews_backend.fetchers import ArticleDetailParser  # noqa: E402

URL = sys.argv[1] if len(sys.argv) > 1 else "https://36kr.com/p/3910351337821318"

req = urllib.request.Request(
    URL,
    headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    },
)
raw = urllib.request.urlopen(req, timeout=25).read()
text = raw.decode("utf-8", errors="replace")

parser = ArticleDetailParser(URL)
parser.feed(text)

print(f"URL: {URL}")
print(f"blocks: {len(parser.text_blocks)}  images: {len(parser.image_urls)}")
print("--- first 8 blocks ---")
for i, block in enumerate(parser.text_blocks[:8]):
    print(f"[{i}] {block[:110]}")
