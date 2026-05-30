from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class SourceConfig:
    name: str
    url: str
    kind: str
    region: str | None = None
    category: str | None = None
    enabled: bool = True


@dataclass(frozen=True)
class AppConfig:
    database_path: Path
    host: str
    port: int
    request_timeout: float
    user_agent: str
    sources: tuple[SourceConfig, ...]


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[1] / "sources.example.json"


def load_config(config_path: str | os.PathLike[str] | None = None) -> AppConfig:
    raw = _load_raw_config(config_path)
    database_path = Path(os.getenv("TOPNEWS_DB", raw.get("database_path", "backend/data/topnews.db")))
    host = os.getenv("TOPNEWS_HOST", raw.get("host", "127.0.0.1"))
    port = int(os.getenv("TOPNEWS_PORT", raw.get("port", 8080)))
    timeout = float(os.getenv("TOPNEWS_TIMEOUT", raw.get("request_timeout", 12)))
    user_agent = raw.get("user_agent", "TopNewsBot/0.1 (+https://example.local/topnews)")
    sources = tuple(
        SourceConfig(
            name=str(item["name"]),
            url=str(item["url"]),
            kind=str(item.get("kind", "rss")).lower(),
            region=item.get("region"),
            category=item.get("category"),
            enabled=bool(item.get("enabled", True)),
        )
        for item in raw.get("sources", [])
    )

    return AppConfig(
        database_path=database_path,
        host=host,
        port=port,
        request_timeout=timeout,
        user_agent=user_agent,
        sources=sources,
    )


def _load_raw_config(config_path: str | os.PathLike[str] | None) -> dict[str, Any]:
    path = Path(config_path) if config_path else Path(os.getenv("TOPNEWS_CONFIG", DEFAULT_CONFIG_PATH))
    if not path.exists():
        return {
            "database_path": "backend/data/topnews.db",
            "host": "127.0.0.1",
            "port": 8080,
            "request_timeout": 12,
            "sources": [],
        }

    with path.open("r", encoding="utf-8") as file:
        return json.load(file)
