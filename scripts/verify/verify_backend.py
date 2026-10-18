"""本地临时库验证两处后端改动：
1) 重复 ingest 不再刷新 fetched_at（时间衰减 / 保留策略恢复生效）
2) 推荐排序里旧的 portal 文章不再永久压住新文章
"""
import sys
import pathlib
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from topnews_backend.academic import RawPaper
from topnews_backend.fetchers import RawArticle  # noqa: E402
from topnews_backend.storage import NewsStore  # noqa: E402

db = Path(tempfile.mkdtemp()) / "verify.db"
store = NewsStore(db)

now = datetime.now(timezone.utc)


def article(ext_id: str, title: str, published_at=None) -> RawArticle:
    return RawArticle(
        title=title,
        url=f"https://example.com/{ext_id}",
        source="新华社首页",
        description="正文" * 60,
        content="正文" * 200,
        content_html="<p>" + "正文" * 200 + "</p>",
        published_at=published_at,
        category="时政",
        region="境内",
        external_id=ext_id,
    )


def fetched_at_of(ext_id: str) -> str:
    with store.connect() as conn:
        row = conn.execute(
            "SELECT fetched_at, published_at FROM articles WHERE external_id = ?", (ext_id,)
        ).fetchone()
    return row["fetched_at"]


# --- 1) 同一篇 portal 文章连续 ingest 三轮 ---
store.upsert_articles([article("old-portal", "一篇三天前就在首页的稿子")])
first_seen = fetched_at_of("old-portal")
for _ in range(2):
    store.upsert_articles([article("old-portal", "一篇三天前就在首页的稿子")])
after_reingest = fetched_at_of("old-portal")

print("=== 1) 重复 ingest 后的 fetched_at ===")
print(f"  首次入库: {first_seen}")
print(f"  再抓两轮: {after_reingest}")
print(f"  是否保持不变: {first_seen == after_reingest}")
assert first_seen == after_reingest, "fetched_at 仍在被刷新"

# --- 2) 把这篇的 fetched_at 手工改成 3 天前，再插入一篇刚抓到的，看推荐顺序 ---
three_days_ago = (now - timedelta(days=3)).isoformat()
with store.connect() as conn:
    conn.execute(
        "UPDATE articles SET fetched_at = ? WHERE external_id = ?", (three_days_ago, "old-portal")
    )

store.upsert_articles([article("new-portal", "刚上首页的新稿子")])

page = store.recommend(page=1, page_size=10)
print("\n=== 2) 推荐顺序（3 天前的 portal 文章 vs 刚抓到的）===")
for i, item in enumerate(page.items):
    print(f"  [{i}] {item.title}  fetched_at={item.fetched_at[:19]}  published_at={item.published_at}")
titles = [item.title for item in page.items]
assert titles.index("刚上首页的新稿子") < titles.index("一篇三天前就在首页的稿子"), "时间衰减未生效"

# --- 3) 保留任务能删掉真正的旧文章 ---
with store.connect() as conn:
    before = conn.execute("SELECT COUNT(*) c FROM articles").fetchone()["c"]
removed = store.apply_retention(article_max_age_days=1, article_strip_age_days=1, paper_max_age_days=90, vacuum=False)
with store.connect() as conn:
    after = conn.execute("SELECT COUNT(*) c FROM articles").fetchone()["c"]
print("\n=== 3) 保留任务（article_days=1）===")
print(f"  文章数 {before} -> {after}   返回: {removed}")
assert after < before, "3 天前的文章没有被保留任务清掉"

# --- 4) 论文图片剥离保留任务 ---
raw_paper = RawPaper(
    title="测试论文",
    authors=["Author A"],
    abstract="摘要内容",
    source="arXiv",
    url="https://arxiv.org/abs/test",
    pdf_url=None,
    categories=["cs.AI"],
    published_at=three_days_ago,
    updated_at=three_days_ago,
    external_id="paper-old",
)
store.upsert_papers([raw_paper])
store.update_paper_figure(
    "paper-old",
    "https://arxiv.org/fig.png",
    "caption",
    image_data=b"dummy image data" * 100,
    image_mime_type="image/jpeg",
)
assert store.get_paper_image("paper-old") is not None, "论文图片未能写入"

retention_res = store.apply_retention(paper_strip_age_days=1, vacuum=False)
print("\n=== 4) 论文图片剥离（paper_strip_days=1）===")
print(f"  返回: {retention_res}")
assert retention_res.get("stripped_papers", 0) >= 1, "论文图片未被剥离"
assert store.get_paper_image("paper-old") is None, "旧论文图片应已被置空"

print("\nALL CHECKS PASSED")
