from __future__ import annotations

import hashlib
import math
import json
import re
import sqlite3
import time
import urllib.parse
from collections import defaultdict
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import RLock
from typing import Iterator
from .academic import KeywordRule, RawPaper, paper_json, paper_list, parse_keyword_rule, score_paper
from .fetchers import RawArticle
from .summary import build_summary


AI_NEWS_SIGNALS = (
    "AI",
    "人工智能",
    "大模型",
    "LLM",
    "Agent",
    "智能体",
    "多模态",
    "机器人",
    "OpenAI",
    "Anthropic",
    "DeepSeek",
    "Claude",
    "Gemini",
)


@dataclass(frozen=True)
class Article:
    id: int
    external_id: str
    title: str
    url: str
    source: str
    region: str
    category: str
    description: str
    content: str
    content_html: str
    image_url: str | None
    image_urls: list[str]
    image_cached: bool
    published_at: str | None
    fetched_at: str
    ai_summary: str | None = None


@dataclass(frozen=True)
class AcademicKeyword:
    id: int
    raw_rule: str
    term: str
    display_name: str
    is_required: bool
    is_excluded: bool
    is_regex: bool
    enabled: bool
    created_at: str


@dataclass(frozen=True)
class Paper:
    id: int
    external_id: str
    title: str
    authors: list[str]
    abstract: str
    source: str
    url: str
    pdf_url: str | None
    image_url: str | None
    image_caption: str | None
    image_cached: bool
    categories: list[str]
    published_at: str | None
    updated_at: str | None
    fetched_at: str
    figure_checked_at: str | None
    ai_summary: str | None = None
    matched_keywords: list[str] | None = None
    score: int | None = None


@dataclass(frozen=True)
class Page:
    items: list[Article | Paper]
    page: int
    page_size: int
    total_count: int

    @property
    def total_page(self) -> int:
        if self.total_count == 0:
            return 1
        return math.ceil(self.total_count / self.page_size)


@dataclass
class _PaperRecommendationCache:
    key: tuple[object, ...]
    created_at: float
    items: list[Paper]


@dataclass
class _NewsRecommendationCache:
    key: tuple[object, ...]
    created_at: float
    ranked_external_ids: list[str]
    candidate_limit: int
    total_count: int


_PAPER_SELECT_COLUMNS = """
    p.id,
    p.external_id,
    p.title,
    p.authors,
    p.abstract,
    p.source,
    p.url,
    p.pdf_url,
    p.image_url,
    p.image_caption,
    p.image_data IS NOT NULL AS image_cached,
    p.categories,
    p.published_at,
    p.updated_at,
    p.fetched_at,
    p.figure_checked_at,
    p.ai_summary
"""

_PAPER_RECOMMENDATION_CACHE_TTL_SECONDS = 600.0
_NEWS_RECOMMENDATION_CACHE_TTL_SECONDS = 90.0


class NewsStore:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._paper_recommendation_cache: _PaperRecommendationCache | None = None
        self._paper_recommendation_cache_lock = RLock()
        self._news_recommendation_cache: _NewsRecommendationCache | None = None
        self._news_recommendation_cache_lock = RLock()
        self.init_schema()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 5000")
        connection.execute("PRAGMA synchronous = NORMAL")
        try:
            yield connection
            connection.commit()
        finally:
            connection.close()

    def invalidate_paper_recommendation_cache(self) -> None:
        with self._paper_recommendation_cache_lock:
            self._paper_recommendation_cache = None

    def invalidate_news_recommendation_cache(self) -> None:
        with self._news_recommendation_cache_lock:
            self._news_recommendation_cache = None

    def init_schema(self) -> None:
        with self.connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS articles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    external_id TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    url TEXT NOT NULL,
                    source TEXT NOT NULL,
                    region TEXT NOT NULL,
                    category TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    content_html TEXT NOT NULL DEFAULT '',
                    image_url TEXT,
                    image_urls TEXT NOT NULL DEFAULT '[]',
                    published_at TEXT,
                    fetched_at TEXT NOT NULL
                )
                """
            )
            _ensure_column(connection, "articles", "content_html", "TEXT NOT NULL DEFAULT ''")
            _ensure_column(connection, "articles", "image_urls", "TEXT NOT NULL DEFAULT '[]'")
            _ensure_column(connection, "articles", "image_data", "BLOB")
            _ensure_column(connection, "articles", "image_mime_type", "TEXT")
            _ensure_column(connection, "articles", "image_cached_at", "TEXT")
            _ensure_column(connection, "articles", "ai_summary", "TEXT")
            _ensure_column(connection, "articles", "ai_summary_at", "TEXT")
            # 入库时计算的派生列：请求期不再全文扫描/重算
            _ensure_column(connection, "articles", "quality_score", "REAL NOT NULL DEFAULT 0")
            _ensure_column(connection, "articles", "is_ai_news", "INTEGER NOT NULL DEFAULT 0")
            _ensure_column(connection, "articles", "content_len", "INTEGER NOT NULL DEFAULT 0")
            _ensure_column(connection, "articles", "title_fingerprint", "TEXT NOT NULL DEFAULT ''")
            connection.execute("UPDATE articles SET category = '时政' WHERE category = '国内'")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_region ON articles(region)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_category ON articles(category)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_published ON articles(published_at)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_ai_news ON articles(is_ai_news, published_at)")
            self._backfill_article_derived_columns(connection)
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS academic_keywords (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    raw_rule TEXT NOT NULL UNIQUE,
                    term TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    is_required INTEGER NOT NULL DEFAULT 0,
                    is_excluded INTEGER NOT NULL DEFAULT 0,
                    is_regex INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS papers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    external_id TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    authors TEXT NOT NULL DEFAULT '[]',
                    abstract TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL,
                    url TEXT NOT NULL,
                    pdf_url TEXT,
                    categories TEXT NOT NULL DEFAULT '[]',
                    published_at TEXT,
                    updated_at TEXT,
                    fetched_at TEXT NOT NULL
                )
                """
            )
            _ensure_column(connection, "papers", "image_url", "TEXT")
            _ensure_column(connection, "papers", "image_caption", "TEXT")
            _ensure_column(connection, "papers", "image_data", "BLOB")
            _ensure_column(connection, "papers", "image_mime_type", "TEXT")
            _ensure_column(connection, "papers", "image_cached_at", "TEXT")
            _ensure_column(connection, "papers", "figure_checked_at", "TEXT")
            _ensure_column(connection, "papers", "ai_summary", "TEXT")
            _ensure_column(connection, "papers", "ai_summary_at", "TEXT")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_papers_published ON papers(published_at)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_keywords_enabled ON academic_keywords(enabled)")

    def _backfill_article_derived_columns(self, connection: sqlite3.Connection) -> None:
        """旧库迁移：为存量行补齐派生列（只在指纹为空的行上执行一次）。"""
        rows = connection.execute(
            """
            SELECT id, title, description, content, image_url, published_at, category
            FROM articles
            WHERE title_fingerprint = ''
            """
        ).fetchall()
        for row in rows:
            quality, is_ai, content_len, fingerprint = _derived_article_values(
                title=row["title"],
                description=row["description"],
                content=row["content"],
                image_url=row["image_url"],
                published_at=row["published_at"],
                category=row["category"],
            )
            connection.execute(
                """
                UPDATE articles
                SET quality_score = ?, is_ai_news = ?, content_len = ?, title_fingerprint = ?
                WHERE id = ?
                """,
                (quality, is_ai, content_len, fingerprint, row["id"]),
            )

    def existing_external_ids(self, external_ids: list[str]) -> set[str]:
        """返回给定 external_id 中已入库的子集（用于增量丰富跳过详情抓取）。"""
        if not external_ids:
            return set()
        found: set[str] = set()
        with self.connect() as connection:
            for start in range(0, len(external_ids), 500):
                chunk = external_ids[start : start + 500]
                placeholders = ", ".join("?" for _ in chunk)
                rows = connection.execute(
                    f"SELECT external_id FROM articles WHERE external_id IN ({placeholders})",
                    chunk,
                ).fetchall()
                found.update(row["external_id"] for row in rows)
        return found

    def upsert_articles(self, articles: list[RawArticle]) -> int:
        if not articles:
            return 0

        now = datetime.now(timezone.utc).isoformat()
        changed = 0
        with self.connect() as connection:
            for article in articles:
                quality, is_ai, content_len, fingerprint = _derived_article_values(
                    title=article.title,
                    description=article.description,
                    content=article.content,
                    image_url=article.image_url,
                    published_at=article.published_at,
                    category=article.category,
                )
                connection.execute(
                    """
                    INSERT INTO articles (
                        external_id, title, url, source, region, category,
                        description, content, content_html, image_url, image_urls, published_at, fetched_at,
                        quality_score, is_ai_news, content_len, title_fingerprint
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(external_id) DO UPDATE SET
                        title=excluded.title,
                        source=excluded.source,
                        region=excluded.region,
                        category=excluded.category,
                        ai_summary=CASE
                            WHEN excluded.title IS NOT articles.title
                              OR length(excluded.content) > length(articles.content)
                              OR length(excluded.content_html) > length(articles.content_html) THEN NULL
                            ELSE articles.ai_summary
                        END,
                        ai_summary_at=CASE
                            WHEN excluded.title IS NOT articles.title
                              OR length(excluded.content) > length(articles.content)
                              OR length(excluded.content_html) > length(articles.content_html) THEN NULL
                            ELSE articles.ai_summary_at
                        END,
                        -- 保留更长的正文：跳过详情抓取的轮次不会用短的 feed 版本覆盖已丰富正文
                        description=CASE
                            WHEN length(excluded.description) >= length(articles.description) THEN excluded.description
                            ELSE articles.description
                        END,
                        content=CASE
                            WHEN length(excluded.content) >= length(articles.content) THEN excluded.content
                            ELSE articles.content
                        END,
                        content_html=CASE
                            WHEN length(excluded.content_html) >= length(articles.content_html) THEN excluded.content_html
                            ELSE articles.content_html
                        END,
                        quality_score=CASE
                            WHEN length(excluded.content) >= length(articles.content) THEN excluded.quality_score
                            ELSE articles.quality_score
                        END,
                        content_len=CASE
                            WHEN length(excluded.content) >= length(articles.content) THEN excluded.content_len
                            ELSE articles.content_len
                        END,
                        is_ai_news=MAX(excluded.is_ai_news, articles.is_ai_news),
                        title_fingerprint=excluded.title_fingerprint,
                        image_data=CASE
                            WHEN excluded.image_url IS NOT articles.image_url THEN NULL
                            ELSE articles.image_data
                        END,
                        image_mime_type=CASE
                            WHEN excluded.image_url IS NOT articles.image_url THEN NULL
                            ELSE articles.image_mime_type
                        END,
                        image_cached_at=CASE
                            WHEN excluded.image_url IS NOT articles.image_url THEN NULL
                            ELSE articles.image_cached_at
                        END,
                        image_url=excluded.image_url,
                        image_urls=excluded.image_urls,
                        published_at=COALESCE(excluded.published_at, articles.published_at),
                        -- 保留首次入库时间，不要每轮抓取都刷新。
                        -- 时间衰减与保留策略都走 COALESCE(published_at, fetched_at)，而 portal 源
                        -- （36氪快讯/新华社首页等）的 published_at 恒为 NULL；一旦每轮 ingest 都把
                        -- fetched_at 改成 now，这些文章会永远处于"刚发布"状态：既永久霸占推荐首页，
                        -- 也永远不会被 30 天保留任务清掉。取最早值即可同时修好这两件事。
                        fetched_at=MIN(COALESCE(articles.fetched_at, excluded.fetched_at), excluded.fetched_at)
                    """,
                    (
                        article.external_id,
                        article.title,
                        article.url,
                        article.source,
                        article.region,
                        article.category,
                        article.description,
                        article.content,
                        article.content_html,
                        article.image_url,
                        json.dumps(article.image_urls, ensure_ascii=False),
                        article.published_at,
                        now,
                        quality,
                        is_ai,
                        content_len,
                        fingerprint,
                    ),
                )
                changed += 1
        if changed:
            self.invalidate_news_recommendation_cache()
        return changed

    def list_articles(
        self,
        page: int,
        page_size: int,
        category: str | None = None,
        region: str | None = None,
        query: str | None = None,
        source: str | None = None,
        excluded_ids: list[str] | None = None,
    ) -> Page:
        where, params = self._filters(category, region, query, source, excluded_ids or [])
        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)
        offset = (page - 1) * page_size

        with self.connect() as connection:
            total_count = connection.execute(f"SELECT COUNT(*) AS total FROM articles a {where}", params).fetchone()["total"]
            rows = connection.execute(
                f"""
                SELECT
                    a.id, a.external_id, a.title, a.url, a.source, a.region, a.category,
                    a.description, a.content, a.content_html, a.image_url, a.image_urls,
                    a.published_at, a.fetched_at, a.ai_summary,
                    a.image_data IS NOT NULL AS image_cached
                FROM articles a
                {where}
                ORDER BY COALESCE(a.published_at, a.fetched_at) DESC, a.id DESC
                LIMIT ? OFFSET ?
                """,
                [*params, page_size, offset],
            ).fetchall()
        return Page(items=[_row_to_article(row) for row in rows], page=page, page_size=page_size, total_count=total_count)

    def recommend(
        self,
        page: int,
        page_size: int,
        category: str | None = None,
        region: str | None = None,
        query: str | None = None,
        source: str | None = None,
        excluded_ids: list[str] | None = None,
    ) -> Page:
        """新闻推荐：入库时已算好 quality_score/title_fingerprint，
        请求期只做「轻量候选查询（无全文列）→ 时间衰减排序 → 指纹软去重」，
        排好的 external_id 列表进短 TTL 缓存，翻页/下拉大多命中。"""
        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)
        offset = (page - 1) * page_size
        needed = offset + page_size

        cache_key = (category or "", region or "", query or "", source or "")
        now = time.monotonic()
        ranked_ids: list[str] | None = None
        total_count = 0
        with self._news_recommendation_cache_lock:
            cache = self._news_recommendation_cache
            if (
                cache
                and cache.key == cache_key
                and now - cache.created_at <= _NEWS_RECOMMENDATION_CACHE_TTL_SECONDS
                # 深翻页需要更大的候选集时视为未命中
                and (len(cache.ranked_external_ids) >= needed or cache.candidate_limit >= cache.total_count)
            ):
                ranked_ids = cache.ranked_external_ids
                total_count = cache.total_count

        if ranked_ids is None:
            where, params = self._filters(category, region, query, source, [])
            with self.connect() as connection:
                total_count = connection.execute(
                    f"SELECT COUNT(*) AS total FROM articles a {where}", params
                ).fetchone()["total"]
                # 候选集只取排序需要的轻量列，避免把全文拖进内存
                candidate_limit = min(total_count, max(needed + 200, 300))
                rows = connection.execute(
                    f"""
                    SELECT a.external_id, a.quality_score, a.title_fingerprint,
                           a.published_at, a.fetched_at
                    FROM articles a
                    {where}
                    ORDER BY COALESCE(a.published_at, a.fetched_at) DESC, a.id DESC
                    LIMIT ?
                    """,
                    [*params, candidate_limit],
                ).fetchall()
            ranked_ids = _rank_candidate_rows(rows)
            with self._news_recommendation_cache_lock:
                self._news_recommendation_cache = _NewsRecommendationCache(
                    key=cache_key,
                    created_at=now,
                    ranked_external_ids=ranked_ids,
                    candidate_limit=candidate_limit,
                    total_count=total_count,
                )

        if excluded_ids:
            excluded = set(excluded_ids)
            visible_ids = [external_id for external_id in ranked_ids if external_id not in excluded]
        else:
            visible_ids = ranked_ids
        page_ids = visible_ids[offset : offset + page_size]
        return Page(
            items=self._articles_by_external_ids(page_ids),
            page=page,
            page_size=page_size,
            total_count=total_count,
        )

    def _articles_by_external_ids(self, external_ids: list[str]) -> list[Article]:
        if not external_ids:
            return []
        placeholders = ", ".join("?" for _ in external_ids)
        with self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT
                    a.id, a.external_id, a.title, a.url, a.source, a.region, a.category,
                    a.description, a.content, a.content_html, a.image_url, a.image_urls,
                    a.published_at, a.fetched_at, a.ai_summary,
                    a.image_data IS NOT NULL AS image_cached
                FROM articles a
                WHERE a.external_id IN ({placeholders})
                """,
                external_ids,
            ).fetchall()
        by_id = {row["external_id"]: _row_to_article(row) for row in rows}
        return [by_id[external_id] for external_id in external_ids if external_id in by_id]

    def categories(self) -> list[dict[str, object]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT category, region, COUNT(*) AS total
                FROM articles
                GROUP BY category, region
                ORDER BY total DESC, category ASC, region ASC
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def add_academic_keyword(self, raw_rule: str) -> AcademicKeyword:
        rule = parse_keyword_rule(raw_rule)
        now = datetime.now(timezone.utc).isoformat()
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO academic_keywords (
                    raw_rule, term, display_name, is_required, is_excluded,
                    is_regex, enabled, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT(raw_rule) DO UPDATE SET
                    term=excluded.term,
                    display_name=excluded.display_name,
                    is_required=excluded.is_required,
                    is_excluded=excluded.is_excluded,
                    is_regex=excluded.is_regex,
                    enabled=1
                """,
                (
                    rule.raw_rule,
                    rule.term,
                    rule.display_name,
                    int(rule.is_required),
                    int(rule.is_excluded),
                    int(rule.is_regex),
                    now,
                ),
            )
            row = connection.execute(
                "SELECT * FROM academic_keywords WHERE raw_rule = ?",
                (rule.raw_rule,),
            ).fetchone()
        self.invalidate_paper_recommendation_cache()
        return _row_to_keyword(row)

    def list_academic_keywords(self, include_disabled: bool = False) -> list[AcademicKeyword]:
        where = "" if include_disabled else "WHERE enabled = 1"
        with self.connect() as connection:
            rows = connection.execute(
                f"SELECT * FROM academic_keywords {where} ORDER BY id ASC"
            ).fetchall()
        return [_row_to_keyword(row) for row in rows]

    def delete_academic_keyword(self, keyword_id: int) -> bool:
        with self.connect() as connection:
            cursor = connection.execute(
                "UPDATE academic_keywords SET enabled = 0 WHERE id = ? AND enabled = 1",
                (keyword_id,),
            )
        changed = cursor.rowcount > 0
        if changed:
            self.invalidate_paper_recommendation_cache()
        return changed

    def get_article_image(self, external_id: str) -> tuple[bytes, str] | None:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT image_data, image_mime_type
                FROM articles
                WHERE external_id = ? AND image_data IS NOT NULL
                """,
                (external_id,),
            ).fetchone()
        if not row:
            return None
        return bytes(row["image_data"]), row["image_mime_type"] or "application/octet-stream"

    def get_article_image_sources(self, external_id: str) -> list[str]:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT image_url, image_urls
                FROM articles
                WHERE external_id = ?
                """,
                (external_id,),
            ).fetchone()
        if not row:
            return []
        return _article_images(row["image_url"], _json_string_list(row["image_urls"]))

    def get_article(self, external_id: str) -> Article | None:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT
                    a.id, a.external_id, a.title, a.url, a.source, a.region, a.category,
                    a.description, a.content, a.content_html, a.image_url, a.image_urls,
                    a.published_at, a.fetched_at, a.ai_summary,
                    a.image_data IS NOT NULL AS image_cached
                FROM articles a
                WHERE a.external_id = ? OR CAST(a.id AS TEXT) = ?
                """,
                (external_id, external_id),
            ).fetchone()
        return _row_to_article(row) if row else None

    def update_article_ai_summary(self, external_id: str, summary: str) -> bool:
        summarized_at = datetime.now(timezone.utc).isoformat()
        with self.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE articles
                SET ai_summary = ?, ai_summary_at = ?
                WHERE external_id = ? OR CAST(id AS TEXT) = ?
                """,
                (summary, summarized_at, external_id, external_id),
            )
        return cursor.rowcount > 0

    def update_article_image_cache(
        self,
        external_id: str,
        image_data: bytes,
        image_mime_type: str,
        image_url: str | None = None,
    ) -> bool:
        cached_at = datetime.now(timezone.utc).isoformat()
        image_clause = ", image_url = ?" if image_url else ""
        params: tuple[object, ...] = (
            (image_data, image_mime_type, cached_at, image_url, external_id)
            if image_url
            else (image_data, image_mime_type, cached_at, external_id)
        )
        with self.connect() as connection:
            cursor = connection.execute(
                f"""
                UPDATE articles
                SET image_data = ?, image_mime_type = ?, image_cached_at = ?{image_clause}
                WHERE external_id = ?
                """,
                params,
            )
        return cursor.rowcount > 0

    def keyword_rules(self) -> list[KeywordRule]:
        return [
            KeywordRule(
                raw_rule=keyword.raw_rule,
                term=keyword.term,
                display_name=keyword.display_name,
                is_required=keyword.is_required,
                is_excluded=keyword.is_excluded,
                is_regex=keyword.is_regex,
            )
            for keyword in self.list_academic_keywords()
        ]

    def upsert_papers(self, papers: list[RawPaper]) -> int:
        if not papers:
            return 0

        now = datetime.now(timezone.utc).isoformat()
        changed = 0
        with self.connect() as connection:
            for paper in papers:
                connection.execute(
                    """
                    INSERT INTO papers (
                        external_id, title, authors, abstract, source, url, pdf_url,
                        categories, published_at, updated_at, fetched_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(external_id) DO UPDATE SET
                        title=excluded.title,
                        authors=excluded.authors,
                        abstract=excluded.abstract,
                        ai_summary=CASE
                            WHEN excluded.title IS NOT papers.title
                              OR excluded.abstract IS NOT papers.abstract THEN NULL
                            ELSE papers.ai_summary
                        END,
                        ai_summary_at=CASE
                            WHEN excluded.title IS NOT papers.title
                              OR excluded.abstract IS NOT papers.abstract THEN NULL
                            ELSE papers.ai_summary_at
                        END,
                        source=excluded.source,
                        url=excluded.url,
                        pdf_url=excluded.pdf_url,
                        categories=excluded.categories,
                        published_at=COALESCE(excluded.published_at, papers.published_at),
                        updated_at=COALESCE(excluded.updated_at, papers.updated_at),
                        fetched_at=excluded.fetched_at
                    """,
                    (
                        paper.external_id,
                        paper.title,
                        paper_json(paper.authors),
                        paper.abstract,
                        paper.source,
                        paper.url,
                        paper.pdf_url,
                        paper_json(paper.categories),
                        paper.published_at,
                        paper.updated_at,
                        now,
                    ),
                )
                changed += 1
        self.invalidate_paper_recommendation_cache()
        return changed

    def papers_pending_figures(self, limit: int = 10, force: bool = False) -> list[Paper]:
        limit = min(max(limit, 1), 100)
        where = "" if force else """
            WHERE p.figure_checked_at IS NULL
               OR (p.image_url IS NOT NULL AND p.image_url != '' AND p.image_data IS NULL)
        """
        with self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT {_PAPER_SELECT_COLUMNS}
                FROM papers p
                {where}
                ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [_row_to_paper(row) for row in rows]

    def update_paper_figure(
        self,
        external_id: str,
        image_url: str | None,
        image_caption: str | None,
        image_data: bytes | None = None,
        image_mime_type: str | None = None,
    ) -> bool:
        checked_at = datetime.now(timezone.utc).isoformat()
        cached_at = checked_at if image_data else None
        with self.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE papers
                SET image_url = ?, image_caption = ?, image_data = ?,
                    image_mime_type = ?, image_cached_at = ?, figure_checked_at = ?
                WHERE external_id = ?
                """,
                (image_url, image_caption, image_data, image_mime_type, cached_at, checked_at, external_id),
            )
        changed = cursor.rowcount > 0
        if changed:
            self.invalidate_paper_recommendation_cache()
        return changed

    def get_paper_image(self, external_id: str) -> tuple[bytes, str] | None:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT image_data, image_mime_type
                FROM papers
                WHERE external_id = ? AND image_data IS NOT NULL
                """,
                (external_id,),
            ).fetchone()
        if not row:
            return None
        return bytes(row["image_data"]), row["image_mime_type"] or "application/octet-stream"

    def get_paper(self, external_id: str) -> Paper | None:
        with self.connect() as connection:
            row = connection.execute(
                f"""
                SELECT {_PAPER_SELECT_COLUMNS}
                FROM papers p
                WHERE p.external_id = ? OR CAST(p.id AS TEXT) = ?
                """,
                (external_id, external_id),
            ).fetchone()
        return _row_to_paper(row) if row else None

    def update_paper_ai_summary(self, external_id: str, summary: str) -> bool:
        summarized_at = datetime.now(timezone.utc).isoformat()
        with self.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE papers
                SET ai_summary = ?, ai_summary_at = ?
                WHERE external_id = ? OR CAST(id AS TEXT) = ?
                """,
                (summary, summarized_at, external_id, external_id),
            )
        changed = cursor.rowcount > 0
        if changed:
            self.invalidate_paper_recommendation_cache()
        return changed

    def recommend_papers(
        self,
        page: int,
        page_size: int,
        query: str | None = None,
        category: str | None = None,
        excluded_ids: list[str] | None = None,
        use_keywords: bool = True,
        force_refresh: bool = False,
    ) -> Page:
        rules = self.keyword_rules() if use_keywords else []
        if query:
            rules.append(parse_keyword_rule(query))

        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)
        cache_key = (
            query or "",
            category or "",
            use_keywords,
            tuple(
                (
                    rule.raw_rule,
                    rule.term,
                    rule.display_name,
                    rule.is_required,
                    rule.is_excluded,
                    rule.is_regex,
                )
                for rule in rules
            ),
        )
        now = time.monotonic()
        if not force_refresh:
            with self._paper_recommendation_cache_lock:
                cache = self._paper_recommendation_cache
                if (
                    cache
                    and cache.key == cache_key
                    and now - cache.created_at <= _PAPER_RECOMMENDATION_CACHE_TTL_SECONDS
                ):
                    return _paper_page_from_items(cache.items, page, page_size, excluded_ids)

        clauses: list[str] = []
        params: list[str] = []
        if category:
            clauses.append("p.categories LIKE ?")
            params.append(f"%{category}%")
        where = "WHERE " + " AND ".join(clauses) if clauses else ""

        with self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT {_PAPER_SELECT_COLUMNS}
                FROM papers p
                {where}
                ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC
                """,
                params,
            ).fetchall()

        scored: list[tuple[float, Paper]] = []
        has_positive_rules = any(not rule.is_excluded for rule in rules)
        for row in rows:
            paper = _row_to_paper(row)
            match = score_paper(paper.title, paper.abstract, paper.categories, rules)
            # 有规则时只保留正分论文（命中且未被排除规则判负）。
            if rules and match.score <= 0:
                continue
            if has_positive_rules and not match.matched_keywords:
                continue
            scored.append(
                (
                    float(match.score),
                    Paper(
                        id=paper.id,
                        external_id=paper.external_id,
                        title=paper.title,
                        authors=paper.authors,
                        abstract=paper.abstract,
                        source=paper.source,
                        url=paper.url,
                        pdf_url=paper.pdf_url,
                        image_url=paper.image_url,
                        image_caption=paper.image_caption,
                        image_cached=paper.image_cached,
                        categories=paper.categories,
                        published_at=paper.published_at,
                        updated_at=paper.updated_at,
                        fetched_at=paper.fetched_at,
                        figure_checked_at=paper.figure_checked_at,
                        ai_summary=paper.ai_summary,
                        matched_keywords=list(match.matched_keywords),
                        score=match.score,
                    ),
                )
            )

        # 基于标题指纹的分组去重，复杂度降至 O(n)
        grouped: dict[str, list[tuple[float, Paper]]] = defaultdict(list)
        for score, paper in scored:
            fingerprint = _title_fingerprint(paper.title)
            grouped[fingerprint].append((score, paper))

        result: list[tuple[float, Paper]] = []
        for group in grouped.values():
            group.sort(key=lambda pair: pair[0], reverse=True)
            best_score, best_paper = group[0]
            result.append((best_score, best_paper))
            for score, paper in group[1:]:
                result.append((score * _PAPER_DEDUP_PENALTY, paper))

        result.sort(key=lambda pair: (pair[0], pair[1].published_at or pair[1].fetched_at), reverse=True)

        items = [paper for _, paper in result]
        with self._paper_recommendation_cache_lock:
            self._paper_recommendation_cache = _PaperRecommendationCache(
                key=cache_key,
                created_at=now,
                items=items,
            )
        return _paper_page_from_items(items, page, page_size, excluded_ids)

    def ai_frontier(self, page: int, page_size: int, excluded_ids: list[str] | None = None) -> Page:
        return self.recommend_ai_news(
            page=page,
            page_size=page_size,
            excluded_ids=excluded_ids,
        )

    def recommend_ai_news(
        self,
        page: int,
        page_size: int,
        excluded_ids: list[str] | None = None,
    ) -> Page:
        excluded_ids = excluded_ids or []
        # is_ai_news 在入库时算好并建了索引，不再对全文做 LIKE 扫描
        clauses = ["a.is_ai_news = 1"]
        params: list[str] = []
        if excluded_ids:
            placeholders = ", ".join("?" for _ in excluded_ids)
            clauses.append(f"(a.external_id NOT IN ({placeholders}) AND CAST(a.id AS TEXT) NOT IN ({placeholders}))")
            params.extend(excluded_ids)
            params.extend(excluded_ids)

        where = "WHERE " + " AND ".join(clauses)
        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)
        offset = (page - 1) * page_size
        with self.connect() as connection:
            total_count = connection.execute(f"SELECT COUNT(*) AS total FROM articles a {where}", params).fetchone()["total"]
            rows = connection.execute(
                f"""
                SELECT
                    a.id, a.external_id, a.title, a.url, a.source, a.region, a.category,
                    a.description, a.content, a.content_html, a.image_url, a.image_urls,
                    a.published_at, a.fetched_at, a.ai_summary,
                    a.image_data IS NOT NULL AS image_cached
                FROM articles a
                {where}
                ORDER BY COALESCE(a.published_at, a.fetched_at) DESC, a.id DESC
                LIMIT ? OFFSET ?
                """,
                [*params, page_size, offset],
            ).fetchall()
        return Page(items=[_row_to_article(row) for row in rows], page=page, page_size=page_size, total_count=total_count)

    def apply_retention(
        self,
        article_max_age_days: int = 30,
        article_strip_age_days: int = 14,
        paper_max_age_days: int = 90,
        vacuum: bool = True,
    ) -> dict[str, int]:
        """数据保留策略，控制 SQLite 体积：

        - 删除 30 天前的文章（时间衰减下早已不可能进入推荐）
        - 14 天前的文章置空 content_html 与图片 BLOB（正文文本保留）
        - 删除 90 天前的论文
        - 有删除时执行 VACUUM 回收磁盘
        """
        now = datetime.now(timezone.utc)
        cutoff_delete = (now - timedelta(days=article_max_age_days)).isoformat()
        cutoff_strip = (now - timedelta(days=article_strip_age_days)).isoformat()
        cutoff_paper = (now - timedelta(days=paper_max_age_days)).isoformat()

        with self.connect() as connection:
            deleted_articles = connection.execute(
                "DELETE FROM articles WHERE COALESCE(published_at, fetched_at) < ?",
                (cutoff_delete,),
            ).rowcount
            stripped_articles = connection.execute(
                """
                UPDATE articles
                SET content_html = '', image_data = NULL, image_mime_type = NULL, image_cached_at = NULL
                WHERE COALESCE(published_at, fetched_at) < ?
                  AND (content_html != '' OR image_data IS NOT NULL)
                """,
                (cutoff_strip,),
            ).rowcount
            deleted_papers = connection.execute(
                "DELETE FROM papers WHERE COALESCE(published_at, fetched_at) < ?",
                (cutoff_paper,),
            ).rowcount

        if deleted_articles or stripped_articles:
            self.invalidate_news_recommendation_cache()
        if deleted_papers:
            self.invalidate_paper_recommendation_cache()
        if vacuum and (deleted_articles or deleted_papers or stripped_articles):
            vacuum_connection = sqlite3.connect(self.database_path, timeout=30)
            try:
                vacuum_connection.execute("VACUUM")
            finally:
                vacuum_connection.close()

        return {
            "deleted_articles": deleted_articles,
            "stripped_articles": stripped_articles,
            "deleted_papers": deleted_papers,
        }

    def _filters(
        self,
        category: str | None,
        region: str | None,
        query: str | None,
        source: str | None,
        excluded_ids: list[str],
    ) -> tuple[str, list[str]]:
        clauses: list[str] = []
        params: list[str] = []
        if category:
            clauses.append("a.category = ?")
            params.append(category)
        if region:
            clauses.append("a.region = ?")
            params.append(region)
        if source:
            clauses.append("a.source = ?")
            params.append(source)
        if query:
            clauses.append(
                "(a.title LIKE ? OR a.description LIKE ? OR a.content LIKE ?)"
            )
            like = f"%{query}%"
            params.extend([like, like, like])
        if excluded_ids:
            placeholders = ", ".join("?" for _ in excluded_ids)
            clauses.append(f"(a.external_id NOT IN ({placeholders}) AND CAST(a.id AS TEXT) NOT IN ({placeholders}))")
            params.extend(excluded_ids)
            params.extend(excluded_ids)
        if not clauses:
            return "", params
        return "WHERE " + " AND ".join(clauses), params


def article_to_dict(article: Article) -> dict[str, object]:
    payload = asdict(article)
    payload.pop("image_cached", None)
    payload["image_source_url"] = article.image_url
    payload["image_url"] = (
        f"/v1/articles/{urllib.parse.quote(article.external_id, safe='')}/image?v={_image_version(article.image_url)}"
        if article.image_url
        else None
    )
    payload["html"] = article.content_html
    payload["image_urls"] = _article_images(article.image_url, article.image_urls)
    payload["item_type"] = "news"
    payload["summary"] = build_summary(article.description, article.content, article.title)
    payload["ai_summary"] = article.ai_summary
    return payload


def article_to_list_dict(article: Article) -> dict[str, object]:
    """列表接口的瘦身负载：不带 content/content_html/description 全文。"""
    return {
        "id": article.id,
        "external_id": article.external_id,
        "title": article.title,
        "url": article.url,
        "source": article.source,
        "region": article.region,
        "category": article.category,
        "summary": build_summary(article.description, article.content, article.title),
        "image_source_url": article.image_url,
        "image_url": (
            f"/v1/articles/{urllib.parse.quote(article.external_id, safe='')}/image?v={_image_version(article.image_url)}"
            if article.image_url
            else None
        ),
        "published_at": article.published_at,
        "fetched_at": article.fetched_at,
        "ai_summary": article.ai_summary,
        "item_type": "news",
    }


def paper_to_list_dict(paper: Paper) -> dict[str, object]:
    """论文列表瘦身负载：不带完整 abstract。"""
    return {
        "id": paper.id,
        "external_id": paper.external_id,
        "title": paper.title,
        "authors": paper.authors,
        "source": paper.source,
        "url": paper.url,
        "pdf_url": paper.pdf_url,
        "summary": build_summary(paper.abstract, paper.title),
        "image_caption": paper.image_caption,
        "image_source_url": paper.image_url,
        "image_url": (
            f"/v1/papers/{urllib.parse.quote(paper.external_id, safe='')}/image"
            if paper.image_cached
            else None
        ),
        "categories": paper.categories,
        "published_at": paper.published_at,
        "updated_at": paper.updated_at,
        "fetched_at": paper.fetched_at,
        "ai_summary": paper.ai_summary,
        "matched_keywords": paper.matched_keywords,
        "score": paper.score,
        "item_type": "paper",
    }


def keyword_to_dict(keyword: AcademicKeyword) -> dict[str, object]:
    return asdict(keyword)


def _image_version(image_url: str | None) -> str:
    return hashlib.sha1((image_url or "").encode("utf-8")).hexdigest()[:12]


def paper_to_dict(paper: Paper) -> dict[str, object]:
    payload = asdict(paper)
    payload.pop("figure_checked_at", None)
    payload.pop("image_cached", None)
    payload["image_source_url"] = paper.image_url
    payload["image_url"] = (
        f"/v1/papers/{urllib.parse.quote(paper.external_id, safe='')}/image"
        if paper.image_cached
        else None
    )
    payload["item_type"] = "paper"
    payload["summary"] = build_summary(paper.abstract, paper.title)
    payload["ai_summary"] = paper.ai_summary
    return payload


def _interleave(first: list[Article | Paper], second: list[Article | Paper]) -> list[Article | Paper]:
    combined: list[Article | Paper] = []
    max_length = max(len(first), len(second))
    for index in range(max_length):
        if index < len(first):
            combined.append(first[index])
        if index < len(second):
            combined.append(second[index])
    return combined


def _paper_page_from_items(
    items: list[Paper],
    page: int,
    page_size: int,
    excluded_ids: list[str] | None,
) -> Page:
    excluded = set(excluded_ids or [])
    if excluded:
        filtered = [
            paper
            for paper in items
            if paper.external_id not in excluded and str(paper.id) not in excluded
        ]
    else:
        filtered = items
    offset = (page - 1) * page_size
    return Page(
        items=filtered[offset : offset + page_size],
        page=page,
        page_size=page_size,
        total_count=len(filtered),
    )


def _row_to_article(row: sqlite3.Row) -> Article:
    return Article(
        id=row["id"],
        external_id=row["external_id"],
        title=row["title"],
        url=row["url"],
        source=row["source"],
        region=row["region"],
        category=row["category"],
        description=row["description"],
        content=row["content"],
        content_html=row["content_html"],
        image_url=row["image_url"],
        image_urls=_article_images(row["image_url"], _json_string_list(row["image_urls"])),
        image_cached=bool(row["image_cached"]),
        published_at=row["published_at"],
        fetched_at=row["fetched_at"],
        ai_summary=row["ai_summary"],
    )


def _row_to_keyword(row: sqlite3.Row) -> AcademicKeyword:
    return AcademicKeyword(
        id=row["id"],
        raw_rule=row["raw_rule"],
        term=row["term"],
        display_name=row["display_name"],
        is_required=bool(row["is_required"]),
        is_excluded=bool(row["is_excluded"]),
        is_regex=bool(row["is_regex"]),
        enabled=bool(row["enabled"]),
        created_at=row["created_at"],
    )


def _row_to_paper(row: sqlite3.Row) -> Paper:
    columns = set(row.keys())
    image_cached = bool(row["image_cached"]) if "image_cached" in columns else row["image_data"] is not None
    return Paper(
        id=row["id"],
        external_id=row["external_id"],
        title=row["title"],
        authors=paper_list(row["authors"]),
        abstract=row["abstract"],
        source=row["source"],
        url=row["url"],
        pdf_url=row["pdf_url"],
        image_url=row["image_url"],
        image_caption=row["image_caption"],
        image_cached=image_cached,
        categories=paper_list(row["categories"]),
        published_at=row["published_at"],
        updated_at=row["updated_at"],
        fetched_at=row["fetched_at"],
        figure_checked_at=row["figure_checked_at"],
        ai_summary=row["ai_summary"],
    )


def _ensure_column(connection: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    columns = {row["name"] for row in connection.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in columns:
        connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


def _json_string_list(value: str | None) -> list[str]:
    try:
        parsed = json.loads(value or "[]")
    except json.JSONDecodeError:
        return []
    return [item for item in parsed if isinstance(item, str) and item]


def _article_images(image_url: str | None, image_urls: list[str]) -> list[str]:
    values = [image_url, *image_urls] if image_url else image_urls
    return list(dict.fromkeys(value for value in values if value))


NEWS_DECAY_LAMBDA = 0.04  # 指数衰减：24h≈38%，72h≈5.6%，7天≈0.1%
_NEWS_DEDUP_THRESHOLD = 0.6
_NEWS_DEDUP_PENALTY = 0.3
_PAPER_DEDUP_THRESHOLD = 0.6
_PAPER_DEDUP_PENALTY = 0.3


def _derived_article_values(
    title: str,
    description: str,
    content: str,
    image_url: str | None,
    published_at: str | None,
    category: str,
) -> tuple[float, int, int, str]:
    """入库时计算派生列：质量分、AI 前沿标记、正文长度、标题指纹。"""
    content_len = len(content or "")
    score = 1.0
    if content_len >= 400:
        score += 2.0
    elif content_len >= 120:
        score += 1.0
    elif content_len < 40:
        score *= 0.6
    score += _ai_signal_hits(title or "", description or "") * 1.5
    if image_url:
        score += 0.3
    if published_at:
        score += 0.2
    is_ai_news = int(
        category == "科技"
        and _ai_signal_hits(title or "", f"{description or ''} {content or ''}") > 0
    )
    fingerprint = _title_fingerprint(title or "")
    return score, is_ai_news, content_len, fingerprint or "-"


def _rank_candidate_rows(rows: list[sqlite3.Row]) -> list[str]:
    """对轻量候选行做精排：final = 入库质量分 × 时间衰减，指纹分组软去重。"""
    now = datetime.now(timezone.utc)
    scored: list[tuple[float, str, str]] = []
    for row in rows:
        decay = _time_decay(row["published_at"] or row["fetched_at"], now)
        scored.append((float(row["quality_score"]) * decay, row["external_id"], row["title_fingerprint"] or "-"))
    scored.sort(key=lambda item: item[0], reverse=True)

    grouped: dict[str, list[tuple[float, str]]] = defaultdict(list)
    for score, external_id, fingerprint in scored:
        grouped[fingerprint].append((score, external_id))

    result: list[tuple[float, str]] = []
    for group in grouped.values():
        best_score, best_id = group[0]
        result.append((best_score, best_id))
        for score, external_id in group[1:]:
            result.append((score * _NEWS_DEDUP_PENALTY, external_id))

    result.sort(key=lambda pair: pair[0], reverse=True)
    return [external_id for _, external_id in result]


def _rank_articles(articles: list[Article]) -> list[Article]:
    """新闻精排：final_score = 质量分 × 时间衰减，再对同事件重复篇软去重降权。

    优化后的去重使用标题指纹 + 分组只保留最高分，复杂度降至 O(n)。
    """
    now = datetime.now(timezone.utc)
    scored: list[tuple[float, Article]] = []
    for article in articles:
        quality = _article_quality_score(article)
        decay = _time_decay(article.published_at or article.fetched_at, now)
        scored.append((quality * decay, article))
    scored.sort(key=lambda pair: pair[0], reverse=True)

    # 基于标准化标题指纹的分组去重，同组只保留最高分
    grouped: dict[str, list[tuple[float, Article]]] = defaultdict(list)
    for score, article in scored:
        fingerprint = _title_fingerprint(article.title)
        grouped[fingerprint].append((score, article))

    result: list[tuple[float, Article]] = []
    for group in grouped.values():
        group.sort(key=lambda pair: pair[0], reverse=True)
        best_score, best_article = group[0]
        result.append((best_score, best_article))
        # 同组其余文章降权追加
        for score, article in group[1:]:
            result.append((score * _NEWS_DEDUP_PENALTY, article))

    result.sort(key=lambda pair: pair[0], reverse=True)
    return [article for _, article in result]


def _article_quality_score(article: Article) -> float:
    score = 1.0
    # 内容丰富度：正文越长越扎实，过短降权
    content_len = len(article.content or "")
    if content_len >= 400:
        score += 2.0
    elif content_len >= 120:
        score += 1.0
    elif content_len < 40:
        score *= 0.6
    # AI 信号加分（单人信息流的轻量个性化）
    score += _ai_signal_hits(article.title, article.description) * 1.5
    # 小幅元数据信号
    if article.image_url:
        score += 0.3
    if article.published_at:
        score += 0.2
    return score


def _ai_signal_hits(title: str, description: str) -> int:
    text = f"{title} {description}".lower()
    return sum(1 for signal in AI_NEWS_SIGNALS if signal.lower() in text)


def _time_decay(iso_time: str | None, now: datetime) -> float:
    if not iso_time:
        return 0.0
    parsed = _parse_iso(iso_time)
    if not parsed:
        return 0.0
    hours = max((now - parsed).total_seconds() / 3600.0, 0.0)
    return math.exp(-NEWS_DECAY_LAMBDA * hours)


def _parse_iso(value: str) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _title_similarity(a: str, b: str) -> float:
    """计算两个标题的相似度，基于字符 bigram（保留兼容性）。

    新增 _title_fingerprint 用于快速分组去重，此函数仍可被外部调用。
    """
    set_a = _char_bigrams(a)
    set_b = _char_bigrams(b)
    if not set_a or not set_b:
        return 0.0
    intersection = len(set_a & set_b)
    return intersection / len(set_a | set_b)


def _title_fingerprint(text: str) -> str:
    """生成标准化标题指纹，用于快速分组去重。

    算法：
    1. 提取中文字符和数字（过滤英文、标点和空格）
    2. 保留前 8 个关键字符作为指纹
    3. 如果中文字符不足，退回到前 6 个字符
    """
    # 提取中文字符和数字
    chinese_and_digits = re.sub(r"[^一-鿿\d]", "", text or "")
    if len(chinese_and_digits) >= 8:
        return chinese_and_digits[:8]
    # 中文字符不足，退回到 cleaned 前 6 个字符
    cleaned = re.sub(r"\s+", "", text or "")
    return cleaned[:6] if cleaned else ""


def _char_bigrams(text: str) -> set[str]:
    cleaned = re.sub(r"\s+", "", text or "")
    if len(cleaned) < 2:
        return {cleaned} if cleaned else set()
    return {cleaned[i : i + 2] for i in range(len(cleaned) - 1)}
