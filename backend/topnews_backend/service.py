from __future__ import annotations

from dataclasses import dataclass

from .academic import fetch_arxiv_papers
from .config import AppConfig, SourceConfig
from .fetchers import FetchError, fetch_source
from .storage import NewsStore


@dataclass(frozen=True)
class IngestResult:
    source: str
    ok: bool
    fetched: int
    stored: int
    error: str | None = None


class NewsAggregator:
    MIN_ARTICLES_PER_SOURCE = 80

    def __init__(self, config: AppConfig, store: NewsStore) -> None:
        self.config = config
        self.store = store

    def ingest(self, limit_per_source: int = 30) -> list[IngestResult]:
        effective_limit = max(limit_per_source, self.MIN_ARTICLES_PER_SOURCE)
        results: list[IngestResult] = []
        for source in self.enabled_sources:
            try:
                articles = fetch_source(
                    source,
                    timeout=self.config.request_timeout,
                    user_agent=self.config.user_agent,
                    limit=effective_limit,
                )
                stored = self.store.upsert_articles(articles)
                results.append(IngestResult(source=source.name, ok=True, fetched=len(articles), stored=stored))
            except FetchError as exc:
                results.append(IngestResult(source=source.name, ok=False, fetched=0, stored=0, error=str(exc)))
        return results

    def ingest_papers(self, limit: int = 30, source: str = "auto") -> IngestResult:
        try:
            papers = fetch_arxiv_papers(
                self.store.keyword_rules(),
                timeout=self.config.request_timeout,
                user_agent=self.config.user_agent,
                limit=limit,
                source=source,
            )
            stored = self.store.upsert_papers(papers)
            return IngestResult(source=f"arXiv {source}", ok=True, fetched=len(papers), stored=stored)
        except FetchError as exc:
            return IngestResult(source=f"arXiv {source}", ok=False, fetched=0, stored=0, error=str(exc))

    @property
    def enabled_sources(self) -> tuple[SourceConfig, ...]:
        return tuple(source for source in self.config.sources if source.enabled)
