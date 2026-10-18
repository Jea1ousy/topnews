from __future__ import annotations

import argparse
import threading
from dataclasses import asdict

from .api import TopNewsApi
from .config import load_config
from .scheduler import FetchOptions, ScheduleOptions, run_fetch_once, run_scheduler
from .service import NewsAggregator
from .storage import NewsStore


def main() -> None:
    parser = argparse.ArgumentParser(description="TopNews backend")
    parser.add_argument("--config", help="Path to sources JSON config")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ingest_parser = subparsers.add_parser("ingest", help="Fetch configured sources and store articles")
    ingest_parser.add_argument("--limit-per-source", type=int, default=80)

    paper_ingest_parser = subparsers.add_parser("papers-ingest", help="Fetch arXiv papers and store them")
    paper_ingest_parser.add_argument("--limit", type=int, default=100)
    paper_ingest_parser.add_argument(
        "--source",
        choices=("auto", "rss", "api"),
        default="auto",
        help="arXiv fetch source. auto uses RSS first, then API fallback.",
    )

    paper_figure_parser = subparsers.add_parser(
        "paper-figures-ingest",
        help="Fetch primary figures from arXiv HTML papers and cache them",
    )
    paper_figure_parser.add_argument("--limit", type=int, default=10)
    paper_figure_parser.add_argument("--delay-seconds", type=float, default=3.0)
    paper_figure_parser.add_argument("--force", action="store_true")

    fetch_parser = subparsers.add_parser("fetch", help="Run news, paper, and figure ingest once")
    _add_fetch_options(fetch_parser)
    fetch_parser.add_argument("--skip-news", action="store_true")
    fetch_parser.add_argument("--skip-papers", action="store_true")
    fetch_parser.add_argument("--skip-figures", action="store_true")

    scheduler_parser = subparsers.add_parser("scheduler", help="Run scheduled ingest loop")
    _add_fetch_options(scheduler_parser)
    _add_schedule_options(scheduler_parser)

    retention_parser = subparsers.add_parser("retention", help="Apply data retention policy once")
    retention_parser.add_argument("--article-max-age-days", type=int, default=30)
    retention_parser.add_argument("--article-strip-age-days", type=int, default=14)
    retention_parser.add_argument("--paper-max-age-days", type=int, default=90)
    retention_parser.add_argument("--paper-strip-age-days", type=int, default=30)
    retention_parser.add_argument("--no-vacuum", action="store_true")

    compress_parser = subparsers.add_parser("compress-images", help="Batch compress and strip existing stored images")
    compress_parser.add_argument("--paper-strip-age-days", type=int, default=30)
    compress_parser.add_argument("--batch-size", type=int, default=100)
    compress_parser.add_argument("--no-vacuum", action="store_true")

    keyword_parser = subparsers.add_parser("keyword-add", help="Add an academic keyword rule")
    keyword_parser.add_argument("rule")

    serve_parser = subparsers.add_parser("serve", help="Run HTTP API server")
    serve_parser.add_argument("--host")
    serve_parser.add_argument("--port", type=int)
    serve_parser.add_argument(
        "--with-scheduler",
        action="store_true",
        help="Run the ingest scheduler as a daemon thread inside the API process",
    )
    _add_fetch_options(serve_parser)
    _add_schedule_options(serve_parser)

    args = parser.parse_args()
    config = load_config(args.config)
    if args.command == "serve":
        if args.host:
            config = _replace_config(config, host=args.host)
        if args.port:
            config = _replace_config(config, port=args.port)

    store = NewsStore(config.database_path)
    aggregator = NewsAggregator(config, store)

    if args.command == "ingest":
        results = aggregator.ingest(limit_per_source=args.limit_per_source)
        for result in results:
            print(asdict(result))
        return

    if args.command == "papers-ingest":
        print(asdict(aggregator.ingest_papers(limit=args.limit, source=args.source)))
        return

    if args.command == "paper-figures-ingest":
        print(
            asdict(
                aggregator.ingest_paper_figures(
                    limit=args.limit,
                    delay_seconds=args.delay_seconds,
                    force=args.force,
                )
            )
        )
        return

    if args.command == "fetch":
        results = run_fetch_once(
            aggregator,
            _fetch_options_from_args(args),
            skip_news=args.skip_news,
            skip_papers=args.skip_papers,
            skip_figures=args.skip_figures,
        )
        for result in results:
            print(asdict(result))
        return

    if args.command == "scheduler":
        run_scheduler(
            aggregator,
            _fetch_options_from_args(args),
            _schedule_options_from_args(args),
        )
        return

    if args.command == "retention":
        print(
            store.apply_retention(
                article_max_age_days=args.article_max_age_days,
                article_strip_age_days=args.article_strip_age_days,
                paper_max_age_days=args.paper_max_age_days,
                paper_strip_age_days=args.paper_strip_age_days,
                vacuum=not args.no_vacuum,
            )
        )
        return

    if args.command == "compress-images":
        print(
            store.compress_existing_images(
                paper_strip_age_days=args.paper_strip_age_days,
                batch_size=args.batch_size,
                vacuum=not args.no_vacuum,
            )
        )
        return

    if args.command == "keyword-add":
        print(asdict(store.add_academic_keyword(args.rule)))
        return

    if args.command == "serve":
        if args.with_scheduler:
            scheduler_thread = threading.Thread(
                target=run_scheduler,
                args=(aggregator, _fetch_options_from_args(args), _schedule_options_from_args(args)),
                name="topnews-scheduler",
                daemon=True,
            )
            scheduler_thread.start()
        TopNewsApi(config, store, aggregator).serve()


def _replace_config(config, **kwargs):
    values = {
        "database_path": config.database_path,
        "host": config.host,
        "port": config.port,
        "request_timeout": config.request_timeout,
        "user_agent": config.user_agent,
        "sources": config.sources,
        "llm_base_url": config.llm_base_url,
        "llm_api_key": config.llm_api_key,
        "llm_model": config.llm_model,
        "llm_timeout": config.llm_timeout,
    }
    values.update(kwargs)
    return type(config)(**values)


def _add_fetch_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--news-limit", type=int, default=80)
    parser.add_argument("--papers-limit", type=int, default=100)
    parser.add_argument(
        "--paper-source",
        choices=("auto", "rss", "api"),
        default="auto",
        help="arXiv fetch source. auto uses RSS first, then API fallback.",
    )
    parser.add_argument("--figures-limit", type=int, default=10)
    parser.add_argument("--figure-delay-seconds", type=float, default=3.0)
    parser.add_argument("--force-figures", action="store_true")


def _add_schedule_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--news-interval-minutes", type=float, default=10.0)
    parser.add_argument("--papers-interval-minutes", type=float, default=60.0)
    parser.add_argument("--figures-interval-minutes", type=float, default=180.0)
    parser.add_argument("--retention-interval-minutes", type=float, default=1440.0)
    parser.add_argument("--idle-sleep-seconds", type=float, default=30.0)
    parser.add_argument("--no-run-on-start", action="store_true")


def _schedule_options_from_args(args: argparse.Namespace) -> ScheduleOptions:
    return ScheduleOptions(
        news_interval_minutes=args.news_interval_minutes,
        papers_interval_minutes=args.papers_interval_minutes,
        figures_interval_minutes=args.figures_interval_minutes,
        retention_interval_minutes=args.retention_interval_minutes,
        run_on_start=not args.no_run_on_start,
        idle_sleep_seconds=args.idle_sleep_seconds,
    )


def _fetch_options_from_args(args: argparse.Namespace) -> FetchOptions:
    return FetchOptions(
        news_limit=args.news_limit,
        papers_limit=args.papers_limit,
        paper_source=args.paper_source,
        figures_limit=args.figures_limit,
        figure_delay_seconds=args.figure_delay_seconds,
        force_figures=args.force_figures,
    )


if __name__ == "__main__":
    main()
