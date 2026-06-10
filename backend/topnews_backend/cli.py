from __future__ import annotations

import argparse
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
    scheduler_parser.add_argument("--news-interval-minutes", type=float, default=30.0)
    scheduler_parser.add_argument("--papers-interval-minutes", type=float, default=180.0)
    scheduler_parser.add_argument("--figures-interval-minutes", type=float, default=180.0)
    scheduler_parser.add_argument("--idle-sleep-seconds", type=float, default=30.0)
    scheduler_parser.add_argument("--no-run-on-start", action="store_true")

    keyword_parser = subparsers.add_parser("keyword-add", help="Add an academic keyword rule")
    keyword_parser.add_argument("rule")

    serve_parser = subparsers.add_parser("serve", help="Run HTTP API server")
    serve_parser.add_argument("--host")
    serve_parser.add_argument("--port", type=int)

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
            ScheduleOptions(
                news_interval_minutes=args.news_interval_minutes,
                papers_interval_minutes=args.papers_interval_minutes,
                figures_interval_minutes=args.figures_interval_minutes,
                run_on_start=not args.no_run_on_start,
                idle_sleep_seconds=args.idle_sleep_seconds,
            ),
        )
        return

    if args.command == "keyword-add":
        print(asdict(store.add_academic_keyword(args.rule)))
        return

    if args.command == "serve":
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
