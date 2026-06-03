from __future__ import annotations

import time
from dataclasses import dataclass

from .academic import fetch_arxiv_papers
from .config import AppConfig, SourceConfig
from .fetchers import FetchError, fetch_source
from .paper_figures import fetch_arxiv_primary_figure, fetch_paper_figure_image
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
    MIN_PAPERS_PER_INGEST = 100

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
        effective_limit = max(limit, self.MIN_PAPERS_PER_INGEST)
        try:
            papers = fetch_arxiv_papers(
                self.store.keyword_rules(),
                timeout=self.config.request_timeout,
                user_agent=self.config.user_agent,
                limit=effective_limit,
                source=source,
            )
            stored = self.store.upsert_papers(papers)
            return IngestResult(source=f"arXiv {source}", ok=True, fetched=len(papers), stored=stored)
        except FetchError as exc:
            return IngestResult(source=f"arXiv {source}", ok=False, fetched=0, stored=0, error=str(exc))

    def ingest_paper_figures(
        self,
        limit: int = 10,
        delay_seconds: float = 3.0,
        force: bool = False,
    ) -> IngestResult:
        papers = self.store.papers_pending_figures(limit=limit, force=force)
        fetched = 0
        stored = 0
        errors: list[str] = []
        for index, paper in enumerate(papers):
            if index > 0 and delay_seconds > 0:
                time.sleep(delay_seconds)
            try:
                figure = None
                if paper.image_url and not force:
                    image_url = paper.image_url
                    image_caption = paper.image_caption
                else:
                    figure = fetch_arxiv_primary_figure(
                        paper.url,
                        timeout=self.config.request_timeout,
                        user_agent=self.config.user_agent,
                    )
                    image_url = figure.image_url if figure else None
                    image_caption = figure.caption if figure else None

                image = (
                    fetch_paper_figure_image(
                        image_url,
                        timeout=self.config.request_timeout,
                        user_agent=self.config.user_agent,
                    )
                    if image_url
                    else None
                )
                fetched += 1
                if self.store.update_paper_figure(
                    paper.external_id,
                    image_url=image_url,
                    image_caption=image_caption,
                    image_data=image.data if image else None,
                    image_mime_type=image.mime_type if image else None,
                ) and image:
                    stored += 1
            except FetchError as exc:
                errors.append(f"{paper.external_id}: {exc}")

        return IngestResult(
            source="arXiv HTML figures",
            ok=not errors or fetched > 0,
            fetched=fetched,
            stored=stored,
            error="; ".join(errors[:3]) or None,
        )

    @property
    def enabled_sources(self) -> tuple[SourceConfig, ...]:
        return tuple(source for source in self.config.sources if source.enabled)
