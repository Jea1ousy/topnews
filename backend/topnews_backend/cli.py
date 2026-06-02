from __future__ import annotations

import argparse
from dataclasses import asdict

from .api import TopNewsApi
from .config import load_config
from .service import NewsAggregator
from .storage import NewsStore


def main() -> None:
    parser = argparse.ArgumentParser(description="TopNews backend")
    parser.add_argument("--config", help="Path to sources JSON config")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ingest_parser = subparsers.add_parser("ingest", help="Fetch configured sources and store articles")
    ingest_parser.add_argument("--limit-per-source", type=int, default=80)

    paper_ingest_parser = subparsers.add_parser("papers-ingest", help="Fetch arXiv papers and store them")
    paper_ingest_parser.add_argument("--limit", type=int, default=30)
    paper_ingest_parser.add_argument(
        "--source",
        choices=("auto", "rss", "api"),
        default="auto",
        help="arXiv fetch source. auto uses RSS first, then API fallback.",
    )

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
    }
    values.update(kwargs)
    return type(config)(**values)


if __name__ == "__main__":
    main()
