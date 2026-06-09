from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Callable

from .service import IngestResult, NewsAggregator


@dataclass(frozen=True)
class FetchOptions:
    news_limit: int = 80
    papers_limit: int = 100
    paper_source: str = "auto"
    figures_limit: int = 10
    figure_delay_seconds: float = 3.0
    force_figures: bool = False


@dataclass(frozen=True)
class ScheduleOptions:
    news_interval_minutes: float = 30.0
    papers_interval_minutes: float = 180.0
    figures_interval_minutes: float = 180.0
    run_on_start: bool = True
    idle_sleep_seconds: float = 30.0


@dataclass
class _ScheduledJob:
    name: str
    interval_seconds: float
    next_run: float
    run: Callable[[], list[IngestResult]]


def run_fetch_once(
    aggregator: NewsAggregator,
    options: FetchOptions,
    *,
    skip_news: bool = False,
    skip_papers: bool = False,
    skip_figures: bool = False,
) -> list[IngestResult]:
    results: list[IngestResult] = []
    if not skip_news:
        results.extend(aggregator.ingest(limit_per_source=options.news_limit))
    if not skip_papers:
        results.append(aggregator.ingest_papers(limit=options.papers_limit, source=options.paper_source))
    if not skip_figures:
        results.append(
            aggregator.ingest_paper_figures(
                limit=options.figures_limit,
                delay_seconds=options.figure_delay_seconds,
                force=options.force_figures,
            )
        )
    return results


def run_scheduler(
    aggregator: NewsAggregator,
    fetch_options: FetchOptions,
    schedule_options: ScheduleOptions,
) -> None:
    now = time.monotonic()
    jobs = _build_jobs(aggregator, fetch_options, schedule_options, now)
    if not jobs:
        raise ValueError("at least one scheduled job interval must be greater than 0")

    _log_event(
        "scheduler_start",
        {
            "jobs": [
                {
                    "name": job.name,
                    "interval_seconds": job.interval_seconds,
                    "run_at_start": schedule_options.run_on_start,
                }
                for job in jobs
            ]
        },
    )

    while True:
        current_time = time.monotonic()
        due_jobs = [job for job in jobs if job.next_run <= current_time]
        if not due_jobs:
            next_run = min(job.next_run for job in jobs)
            time.sleep(min(schedule_options.idle_sleep_seconds, max(1.0, next_run - current_time)))
            continue

        for job in due_jobs:
            _log_event("job_start", {"job": job.name})
            try:
                results = job.run()
                _log_event(
                    "job_complete",
                    {
                        "job": job.name,
                        "results": [asdict(result) for result in results],
                    },
                )
            except Exception as exc:  # pragma: no cover - defensive for long-running service logs
                _log_event("job_failed", {"job": job.name, "error": str(exc)})
            finally:
                job.next_run = time.monotonic() + job.interval_seconds


def _build_jobs(
    aggregator: NewsAggregator,
    fetch_options: FetchOptions,
    schedule_options: ScheduleOptions,
    now: float,
) -> list[_ScheduledJob]:
    first_delay = 0.0 if schedule_options.run_on_start else None
    jobs: list[_ScheduledJob] = []

    def add_job(name: str, interval_minutes: float, run: Callable[[], list[IngestResult]]) -> None:
        if interval_minutes <= 0:
            return
        interval_seconds = interval_minutes * 60
        jobs.append(
            _ScheduledJob(
                name=name,
                interval_seconds=interval_seconds,
                next_run=now + (first_delay if first_delay is not None else interval_seconds),
                run=run,
            )
        )

    add_job(
        "news",
        schedule_options.news_interval_minutes,
        lambda: aggregator.ingest(limit_per_source=fetch_options.news_limit),
    )
    add_job(
        "papers",
        schedule_options.papers_interval_minutes,
        lambda: [aggregator.ingest_papers(limit=fetch_options.papers_limit, source=fetch_options.paper_source)],
    )
    add_job(
        "figures",
        schedule_options.figures_interval_minutes,
        lambda: [
            aggregator.ingest_paper_figures(
                limit=fetch_options.figures_limit,
                delay_seconds=fetch_options.figure_delay_seconds,
                force=fetch_options.force_figures,
            )
        ],
    )
    return jobs


def _log_event(event: str, payload: dict[str, object]) -> None:
    print(
        json.dumps(
            {
                "time": datetime.now(timezone.utc).isoformat(),
                "event": event,
                **payload,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
