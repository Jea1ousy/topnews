from __future__ import annotations

import math
import sqlite3
import urllib.parse
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
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
    image_url: str | None
    published_at: str | None
    fetched_at: str


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


class NewsStore:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self.init_schema()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.database_path)
        connection.row_factory = sqlite3.Row
        try:
            yield connection
            connection.commit()
        finally:
            connection.close()

    def init_schema(self) -> None:
        with self.connect() as connection:
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
                    image_url TEXT,
                    published_at TEXT,
                    fetched_at TEXT NOT NULL
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_region ON articles(region)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_category ON articles(category)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_articles_published ON articles(published_at)")
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
            connection.execute("CREATE INDEX IF NOT EXISTS idx_papers_published ON papers(published_at)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_keywords_enabled ON academic_keywords(enabled)")

    def upsert_articles(self, articles: list[RawArticle]) -> int:
        if not articles:
            return 0

        now = datetime.now(timezone.utc).isoformat()
        changed = 0
        with self.connect() as connection:
            for article in articles:
                connection.execute(
                    """
                    INSERT INTO articles (
                        external_id, title, url, source, region, category,
                        description, content, image_url, published_at, fetched_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(external_id) DO UPDATE SET
                        title=excluded.title,
                        source=excluded.source,
                        region=excluded.region,
                        category=excluded.category,
                        description=excluded.description,
                        content=excluded.content,
                        image_url=excluded.image_url,
                        published_at=COALESCE(excluded.published_at, articles.published_at),
                        fetched_at=excluded.fetched_at
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
                        article.image_url,
                        article.published_at,
                        now,
                    ),
                )
                changed += 1
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
                SELECT a.*
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
        where, params = self._filters(category, region, query, source, excluded_ids or [])
        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)
        offset = (page - 1) * page_size

        with self.connect() as connection:
            total_count = connection.execute(f"SELECT COUNT(*) AS total FROM articles a {where}", params).fetchone()["total"]
            rows = connection.execute(
                f"""
                SELECT a.*,
                    CASE
                        WHEN a.image_url IS NOT NULL AND a.image_url != '' THEN 6
                        ELSE 0
                    END
                    + CASE
                        WHEN a.description IS NOT NULL AND length(a.description) > 20 THEN 4
                        ELSE 0
                    END
                    + CASE
                        WHEN a.published_at IS NOT NULL THEN 8
                        ELSE 0
                    END
                    + CASE a.category
                        WHEN '国内' THEN 5
                        WHEN '国际' THEN 5
                        WHEN '科技' THEN 4
                        WHEN '财经' THEN 4
                        ELSE 1
                    END AS rank_score
                FROM articles a
                {where}
                ORDER BY COALESCE(a.published_at, a.fetched_at) DESC, rank_score DESC, a.id DESC
                LIMIT ? OFFSET ?
                """,
                [*params, page_size, offset],
            ).fetchall()
        return Page(items=[_row_to_article(row) for row in rows], page=page, page_size=page_size, total_count=total_count)

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
                SELECT p.*
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
        return cursor.rowcount > 0

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

    def recommend_papers(
        self,
        page: int,
        page_size: int,
        query: str | None = None,
        category: str | None = None,
        excluded_ids: list[str] | None = None,
        use_keywords: bool = True,
    ) -> Page:
        rules = self.keyword_rules() if use_keywords else []
        if query:
            rules.append(parse_keyword_rule(query))

        clauses: list[str] = []
        params: list[str] = []
        if category:
            clauses.append("p.categories LIKE ?")
            params.append(f"%{category}%")
        if excluded_ids:
            placeholders = ", ".join("?" for _ in excluded_ids)
            clauses.append(f"(p.external_id NOT IN ({placeholders}) AND CAST(p.id AS TEXT) NOT IN ({placeholders}))")
            params.extend(excluded_ids)
            params.extend(excluded_ids)
        where = "WHERE " + " AND ".join(clauses) if clauses else ""
        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)

        with self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT p.*
                FROM papers p
                {where}
                ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC
                """,
                params,
            ).fetchall()

        scored: list[Paper] = []
        for row in rows:
            paper = _row_to_paper(row)
            match = score_paper(paper.title, paper.abstract, paper.categories, rules)
            if rules and match.score < 0:
                continue
            if rules and match.score <= 0:
                continue
            scored.append(
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
                    matched_keywords=list(match.matched_keywords),
                    score=match.score,
                )
            )

        scored.sort(key=lambda paper: (paper.score or 0, paper.published_at or paper.fetched_at), reverse=True)
        offset = (page - 1) * page_size
        return Page(items=scored[offset : offset + page_size], page=page, page_size=page_size, total_count=len(scored))

    def ai_frontier(self, page: int, page_size: int, excluded_ids: list[str] | None = None) -> Page:
        page = max(page, 1)
        page_size = min(max(page_size, 1), 100)
        candidate_size = min(page * page_size, 100)
        excluded_ids = excluded_ids or []
        paper_page = self.recommend_papers(
            page=1,
            page_size=candidate_size,
            excluded_ids=excluded_ids,
            use_keywords=False,
        )
        news_page = self.recommend_ai_news(page=1, page_size=candidate_size, excluded_ids=excluded_ids)
        combined = _interleave(news_page.items, paper_page.items)
        offset = (page - 1) * page_size
        return Page(
            items=combined[offset : offset + page_size],
            page=page,
            page_size=page_size,
            total_count=news_page.total_count + paper_page.total_count,
        )

    def recommend_ai_news(
        self,
        page: int,
        page_size: int,
        excluded_ids: list[str] | None = None,
    ) -> Page:
        excluded_ids = excluded_ids or []
        clauses = ["a.category = ?"]
        params: list[str] = ["科技"]
        signal_clauses: list[str] = []
        for signal in AI_NEWS_SIGNALS:
            signal_clauses.append("(a.title LIKE ? OR a.description LIKE ? OR a.content LIKE ?)")
            like = f"%{signal}%"
            params.extend([like, like, like])
        clauses.append("(" + " OR ".join(signal_clauses) + ")")
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
                SELECT a.*
                FROM articles a
                {where}
                ORDER BY COALESCE(a.published_at, a.fetched_at) DESC, a.id DESC
                LIMIT ? OFFSET ?
                """,
                [*params, page_size, offset],
            ).fetchall()
        return Page(items=[_row_to_article(row) for row in rows], page=page, page_size=page_size, total_count=total_count)

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
    payload["item_type"] = "news"
    payload["summary"] = build_summary(article.description, article.content, article.title)
    return payload


def keyword_to_dict(keyword: AcademicKeyword) -> dict[str, object]:
    return asdict(keyword)


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
        image_url=row["image_url"],
        published_at=row["published_at"],
        fetched_at=row["fetched_at"],
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
        image_cached=row["image_data"] is not None,
        categories=paper_list(row["categories"]),
        published_at=row["published_at"],
        updated_at=row["updated_at"],
        fetched_at=row["fetched_at"],
        figure_checked_at=row["figure_checked_at"],
    )


def _ensure_column(connection: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    columns = {row["name"] for row in connection.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in columns:
        connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
