from __future__ import annotations

import json
import sys
import traceback
import urllib.parse
from dataclasses import asdict
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .config import AppConfig
from .fetchers import FetchError
from .llm import LlmClient, LlmConfigError, LlmRequestError
from .paper_figures import fetch_remote_image
from .service import NewsAggregator
from .storage import NewsStore, Page, article_to_dict, keyword_to_dict, paper_to_dict


class TopNewsApi:
    def __init__(self, config: AppConfig, store: NewsStore, aggregator: NewsAggregator) -> None:
        self.config = config
        self.store = store
        self.aggregator = aggregator

    def make_handler(self) -> type[BaseHTTPRequestHandler]:
        app = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "TopNewsBackend/0.1"

            def log_message(self, format: str, *args: Any) -> None:
                sys.stderr.write("%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), format % args))

            def do_GET(self) -> None:
                app.handle(self, "GET")

            def do_POST(self) -> None:
                app.handle(self, "POST")

            def do_DELETE(self) -> None:
                app.handle(self, "DELETE")

            def do_OPTIONS(self) -> None:
                app.write_json(self, HTTPStatus.NO_CONTENT, None)

        return Handler

    def serve(self) -> None:
        server = ThreadingHTTPServer((self.config.host, self.config.port), self.make_handler())
        print(f"TopNews backend listening on http://{self.config.host}:{self.config.port}")
        server.serve_forever()

    def handle(self, request: BaseHTTPRequestHandler, method: str) -> None:
        try:
            parsed = urllib.parse.urlparse(request.path)
            params = urllib.parse.parse_qs(parsed.query)
            body = self._read_body(request)
            if method == "GET" and parsed.path == "/health":
                return self.write_json(request, HTTPStatus.OK, {"ok": True, "time": datetime.now(timezone.utc).isoformat()})
            if method == "GET" and parsed.path == "/v1/sources":
                return self.write_json(request, HTTPStatus.OK, [asdict(source) for source in self.aggregator.enabled_sources])
            if method == "GET" and parsed.path == "/v1/categories":
                return self.write_json(request, HTTPStatus.OK, self.store.categories())
            if method == "GET" and parsed.path.startswith("/v1/articles/") and parsed.path.endswith("/image"):
                external_id = urllib.parse.unquote(parsed.path[len("/v1/articles/") : -len("/image")].rstrip("/"))
                image = self.store.get_article_image(external_id)
                if not image:
                    image_urls = self.store.get_article_image_sources(external_id)
                    if not image_urls:
                        return self.write_json(request, HTTPStatus.NOT_FOUND, {"error": "article_image_not_found"})
                    errors: list[str] = []
                    fetched_image = None
                    fetched_image_url = None
                    for image_url in image_urls:
                        try:
                            fetched_image = fetch_remote_image(
                                image_url,
                                timeout=self.config.request_timeout,
                                user_agent=self.config.user_agent,
                            )
                            fetched_image_url = image_url
                            break
                        except FetchError as exc:
                            errors.append(f"{image_url}: {exc}")
                    if not fetched_image or not fetched_image_url:
                        return self.write_json(
                            request,
                            HTTPStatus.BAD_GATEWAY,
                            {"error": "article_image_fetch_failed", "message": "; ".join(errors[:3])},
                        )
                    self.store.update_article_image_cache(
                        external_id,
                        image_data=fetched_image.data,
                        image_mime_type=fetched_image.mime_type,
                        image_url=fetched_image_url,
                    )
                    image = (fetched_image.data, fetched_image.mime_type)
                return self.write_binary(request, HTTPStatus.OK, image[0], image[1])
            if method == "GET" and parsed.path.startswith("/v1/papers/") and parsed.path.endswith("/image"):
                external_id = urllib.parse.unquote(parsed.path[len("/v1/papers/") : -len("/image")].rstrip("/"))
                image = self.store.get_paper_image(external_id)
                if not image:
                    return self.write_json(request, HTTPStatus.NOT_FOUND, {"error": "paper_image_not_found"})
                return self.write_binary(request, HTTPStatus.OK, image[0], image[1])
            if method == "GET" and parsed.path == "/v1/news":
                page = self.store.list_articles(**self._page_args(params))
                return self.write_json(request, HTTPStatus.OK, self._page_to_dict(page))
            if method == "GET" and parsed.path == "/v1/recommendations":
                page = self.store.recommend(**self._page_args(params))
                return self.write_json(request, HTTPStatus.OK, self._page_to_dict(page))
            if method == "POST" and parsed.path.startswith("/v1/articles/") and parsed.path.endswith("/ai-summary"):
                external_id = urllib.parse.unquote(parsed.path[len("/v1/articles/") : -len("/ai-summary")].rstrip("/"))
                return self._write_article_ai_summary(request, external_id, force=self._force_summary(params, body))
            if method == "GET" and parsed.path == "/v1/ai-frontier":
                page = self.store.ai_frontier(
                    page=int(self._first(params, "page", "1") or 1),
                    page_size=int(self._first(params, "pageSize", "20") or 20),
                    excluded_ids=self._csv(params, "exclude"),
                )
                return self.write_json(request, HTTPStatus.OK, self._page_to_dict(page))
            if method == "POST" and parsed.path.startswith("/v1/papers/") and parsed.path.endswith("/ai-summary"):
                external_id = urllib.parse.unquote(parsed.path[len("/v1/papers/") : -len("/ai-summary")].rstrip("/"))
                return self._write_paper_ai_summary(request, external_id, force=self._force_summary(params, body))
            if method == "GET" and parsed.path == "/v1/academic/keywords":
                include_disabled = self._first(params, "includeDisabled") == "true"
                keywords = self.store.list_academic_keywords(include_disabled=include_disabled)
                return self.write_json(request, HTTPStatus.OK, [keyword_to_dict(keyword) for keyword in keywords])
            if method == "POST" and parsed.path == "/v1/academic/keywords":
                raw_rule = str(body.get("keyword") or body.get("rule") or "").strip()
                if not raw_rule:
                    return self.write_json(request, HTTPStatus.BAD_REQUEST, {"error": "keyword_required"})
                keyword = self.store.add_academic_keyword(raw_rule)
                return self.write_json(request, HTTPStatus.CREATED, keyword_to_dict(keyword))
            if method == "DELETE" and parsed.path.startswith("/v1/academic/keywords/"):
                keyword_id = int(parsed.path.rstrip("/").rsplit("/", 1)[-1])
                deleted = self.store.delete_academic_keyword(keyword_id)
                return self.write_json(request, HTTPStatus.OK, {"deleted": deleted})
            if method == "POST" and parsed.path == "/v1/papers/ingest":
                limit = int(body.get("limit", self._first(params, "limit", "100")) or 100)
                source = str(body.get("source") or self._first(params, "source", "auto"))
                result = self.aggregator.ingest_papers(limit=limit, source=source)
                return self.write_json(request, HTTPStatus.OK, asdict(result))
            if method == "POST" and parsed.path == "/v1/papers/figures/ingest":
                limit = int(body.get("limit", self._first(params, "limit", "10")) or 10)
                delay = float(body.get("delay_seconds", self._first(params, "delaySeconds", "3")) or 3)
                force = str(body.get("force") or self._first(params, "force", "false")).lower() == "true"
                result = self.aggregator.ingest_paper_figures(limit=limit, delay_seconds=delay, force=force)
                return self.write_json(request, HTTPStatus.OK, asdict(result))
            if method == "GET" and parsed.path == "/v1/papers/recommendations":
                page = self.store.recommend_papers(
                    page=int(self._first(params, "page", "1") or 1),
                    page_size=int(self._first(params, "pageSize", "20") or 20),
                    query=self._first(params, "q"),
                    category=self._first(params, "category"),
                    excluded_ids=self._csv(params, "exclude"),
                )
                return self.write_json(request, HTTPStatus.OK, self._page_to_dict(page))
            if method == "POST" and parsed.path == "/v1/ingest":
                limit = int(body.get("limit_per_source", self._first(params, "limitPerSource", "80")) or 80)
                results = self.aggregator.ingest(limit_per_source=limit)
                return self.write_json(request, HTTPStatus.OK, {"results": [asdict(result) for result in results]})
            if method == "POST" and parsed.path == "/news/channel":
                return self.write_json(request, HTTPStatus.OK, self._compat_channels())
            if method == "POST" and parsed.path == "/news/list":
                page = self.store.recommend(**self._compat_page_args(params, body))
                return self.write_json(request, HTTPStatus.OK, self._compat_news_page(page))
            return self.write_json(request, HTTPStatus.NOT_FOUND, {"error": "not_found", "path": parsed.path})
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):  # pragma: no cover - client disconnected
            return
        except Exception as exc:  # pragma: no cover - API safety net
            traceback.print_exc()
            try:
                self.write_json(request, HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "internal_error", "message": str(exc)})
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                return

    def write_json(self, request: BaseHTTPRequestHandler, status: HTTPStatus, payload: Any) -> None:
        request.send_response(status.value)
        request.send_header("Access-Control-Allow-Origin", "*")
        request.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        request.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        if payload is None:
            request.end_headers()
            return
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request.send_header("Content-Type", "application/json; charset=utf-8")
        request.send_header("Content-Length", str(len(data)))
        request.end_headers()
        request.wfile.write(data)

    def write_binary(
        self,
        request: BaseHTTPRequestHandler,
        status: HTTPStatus,
        data: bytes,
        content_type: str,
    ) -> None:
        request.send_response(status.value)
        request.send_header("Access-Control-Allow-Origin", "*")
        request.send_header("Content-Type", content_type)
        request.send_header("Content-Length", str(len(data)))
        request.send_header("Cache-Control", "public, max-age=86400")
        request.end_headers()
        request.wfile.write(data)

    def _read_body(self, request: BaseHTTPRequestHandler) -> dict[str, Any]:
        length = int(request.headers.get("Content-Length", "0") or 0)
        if length <= 0:
            return {}
        raw = request.rfile.read(length).decode("utf-8")
        content_type = request.headers.get("Content-Type", "")
        if "application/json" in content_type:
            return json.loads(raw or "{}")
        return {key: values[-1] for key, values in urllib.parse.parse_qs(raw).items()}

    def _write_article_ai_summary(self, request: BaseHTTPRequestHandler, external_id: str, force: bool) -> None:
        article = self.store.get_article(external_id)
        if not article:
            return self.write_json(request, HTTPStatus.NOT_FOUND, {"error": "article_not_found"})
        if article.ai_summary and not force:
            return self.write_json(request, HTTPStatus.OK, {"summary": article.ai_summary, "cached": True})

        body = "\n\n".join(
            value
            for value in (article.description, article.content, article.content_html)
            if value and value.strip()
        )
        if not body.strip():
            body = article.title
        try:
            summary = self._llm_client().summarize(article.title, body, source=article.source)
        except LlmConfigError as exc:
            return self.write_json(request, HTTPStatus.SERVICE_UNAVAILABLE, {"error": "llm_not_configured", "message": str(exc)})
        except LlmRequestError as exc:
            return self.write_json(request, HTTPStatus.BAD_GATEWAY, {"error": "llm_request_failed", "message": str(exc)})

        self.store.update_article_ai_summary(article.external_id, summary)
        return self.write_json(request, HTTPStatus.OK, {"summary": summary, "cached": False})

    def _write_paper_ai_summary(self, request: BaseHTTPRequestHandler, external_id: str, force: bool) -> None:
        paper = self.store.get_paper(external_id)
        if not paper:
            return self.write_json(request, HTTPStatus.NOT_FOUND, {"error": "paper_not_found"})
        if paper.ai_summary and not force:
            return self.write_json(request, HTTPStatus.OK, {"summary": paper.ai_summary, "cached": True})

        body = "\n\n".join(
            value
            for value in (
                "Authors: " + ", ".join(paper.authors) if paper.authors else "",
                paper.image_caption or "",
                paper.abstract,
            )
            if value and value.strip()
        )
        try:
            summary = self._llm_client().summarize(paper.title, body, source=paper.source)
        except LlmConfigError as exc:
            return self.write_json(request, HTTPStatus.SERVICE_UNAVAILABLE, {"error": "llm_not_configured", "message": str(exc)})
        except LlmRequestError as exc:
            return self.write_json(request, HTTPStatus.BAD_GATEWAY, {"error": "llm_request_failed", "message": str(exc)})

        self.store.update_paper_ai_summary(paper.external_id, summary)
        return self.write_json(request, HTTPStatus.OK, {"summary": summary, "cached": False})

    def _llm_client(self) -> LlmClient:
        return LlmClient(
            base_url=self.config.llm_base_url,
            api_key=self.config.llm_api_key,
            model=self.config.llm_model,
            timeout=self.config.llm_timeout,
        )

    def _page_args(self, params: dict[str, list[str]]) -> dict[str, Any]:
        return {
            "page": int(self._first(params, "page", "1") or 1),
            "page_size": int(self._first(params, "pageSize", "20") or 20),
            "category": self._first(params, "category"),
            "region": self._first(params, "region"),
            "query": self._first(params, "q"),
            "source": self._first(params, "source"),
            "excluded_ids": self._csv(params, "exclude"),
        }

    def _force_summary(self, params: dict[str, list[str]], body: dict[str, Any]) -> bool:
        raw = body.get("force", self._first(params, "force", "false"))
        return str(raw).lower() == "true"

    def _compat_page_args(self, params: dict[str, list[str]], body: dict[str, Any]) -> dict[str, Any]:
        channel_name = body.get("channelName") or self._first(params, "channelName")
        category = None if channel_name in {None, "", "推荐", "头条"} else str(channel_name)
        return {
            "page": int(body.get("page") or self._first(params, "page", "1") or 1),
            "page_size": int(body.get("pageSize") or self._first(params, "pageSize", "20") or 20),
            "category": category,
            "region": body.get("region") or self._first(params, "region"),
            "query": body.get("title") or self._first(params, "title"),
        }

    @staticmethod
    def _first(params: dict[str, list[str]], key: str, default: str | None = None) -> str | None:
        values = params.get(key)
        if not values:
            return default
        return values[-1]

    @staticmethod
    def _csv(params: dict[str, list[str]], key: str) -> list[str]:
        raw = TopNewsApi._first(params, key)
        if not raw:
            return []
        return [value.strip() for value in raw.split(",") if value.strip()]

    @staticmethod
    def _page_to_dict(page: Page) -> dict[str, Any]:
        return {
            "items": [_item_to_dict(item) for item in page.items],
            "page": page.page,
            "pageSize": page.page_size,
            "totalPage": page.total_page,
            "totalCount": page.total_count,
            "hasMore": page.page < page.total_page,
        }

    @staticmethod
    def _compat_channels() -> dict[str, Any]:
        channels = ["推荐", "国内", "财经", "综合"]
        return {
            "code": 200,
            "msg": "ok",
            "data": {
                "items": [{"channelId": channel, "name": channel} for channel in channels],
            },
        }

    @staticmethod
    def _compat_news_page(page: Page) -> dict[str, Any]:
        return {
            "code": 200,
            "msg": "ok",
            "data": {
                "page": page.page,
                "totalPage": page.total_page,
                "totalCount": page.total_count,
                "items": [
                    {
                        "id": str(article.id),
                        "title": article.title,
                        "source": article.source,
                        "pubDate": article.published_at or article.fetched_at,
                        "desc": article_to_dict(article)["summary"],
                        "content": article.content,
                        "html": article.content_html,
                        "link": article.url,
                        "channelId": article.category,
                        "channelName": article.category,
                        "imageUrls": article_to_dict(article)["image_urls"],
                    }
                    for article in page.items
                ],
            },
        }


def _item_to_dict(item: Any) -> dict[str, object]:
    if hasattr(item, "abstract"):
        return paper_to_dict(item)
    return article_to_dict(item)
