from __future__ import annotations

import hashlib
import html
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser

from .classifier import classify
from .config import SourceConfig


@dataclass(frozen=True)
class RawArticle:
    title: str
    url: str
    source: str
    description: str = ""
    content: str = ""
    image_url: str | None = None
    published_at: str | None = None
    category: str = "综合"
    region: str = "境内"
    external_id: str | None = None


class FetchError(RuntimeError):
    pass


def fetch_source(source: SourceConfig, timeout: float, user_agent: str, limit: int = 30) -> list[RawArticle]:
    body = _download(source.url, timeout, user_agent)
    if source.kind == "rss":
        return parse_rss(body, source, limit=limit)
    if source.kind == "portal":
        return parse_portal(body, source, limit=limit)
    raise FetchError(f"Unsupported source kind: {source.kind}")


def parse_rss(body: bytes, source: SourceConfig, limit: int = 30) -> list[RawArticle]:
    try:
        root = ET.fromstring(body)
    except ET.ParseError as exc:
        raise FetchError(f"RSS parse failed for {source.name}: {exc}") from exc

    items = root.findall(".//item")
    if not items:
        items = root.findall(".//{http://www.w3.org/2005/Atom}entry")

    articles: list[RawArticle] = []
    for item in items[:limit]:
        title = _first_text(item, "title")
        if not title:
            continue
        link = _rss_link(item)
        if not link:
            continue
        description = _first_text(item, "description", "summary") or ""
        published_at = _parse_date(_first_text(item, "pubDate", "published", "updated"))
        image_url = _rss_image(item)
        articles.append(_article_from_values(source, title, link, description, "", image_url, published_at))

    return articles


def parse_portal(body: bytes, source: SourceConfig, limit: int = 30) -> list[RawArticle]:
    parser = PortalParser(source.url)
    parser.feed(body.decode("utf-8", errors="ignore"))

    articles: list[RawArticle] = []
    seen_urls: set[str] = set()
    for title, url in parser.links:
        clean_title = _clean_text(title)
        if len(clean_title) < 6 or url in seen_urls:
            continue
        seen_urls.add(url)
        articles.append(_article_from_values(source, clean_title, url, parser.description, "", parser.image_url, None))
        if len(articles) >= limit:
            break

    return articles


class PortalParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.links: list[tuple[str, str]] = []
        self.description = ""
        self.image_url: str | None = None
        self._current_href: str | None = None
        self._current_text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr_map = {name.lower(): value or "" for name, value in attrs}
        if tag.lower() == "a":
            href = attr_map.get("href", "")
            if href.startswith("#") or href.lower().startswith(("javascript:", "mailto:")):
                return
            self._current_href = urllib.parse.urljoin(self.base_url, href)
            self._current_text = []
        elif tag.lower() == "meta":
            name = (attr_map.get("name") or attr_map.get("property") or "").lower()
            content = attr_map.get("content", "")
            if name in {"description", "og:description"} and content and not self.description:
                self.description = _clean_text(content)
            if name in {"og:image", "twitter:image"} and content and not self.image_url:
                self.image_url = urllib.parse.urljoin(self.base_url, content)

    def handle_data(self, data: str) -> None:
        if self._current_href:
            self._current_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "a" and self._current_href:
            text = _clean_text("".join(self._current_text))
            if text:
                self.links.append((text, self._current_href))
            self._current_href = None
            self._current_text = []


def _download(url: str, timeout: float, user_agent: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": user_agent, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()
    except (urllib.error.URLError, TimeoutError) as exc:
        raise FetchError(f"Fetch failed for {url}: {exc}") from exc


def _article_from_values(
    source: SourceConfig,
    title: str,
    url: str,
    description: str,
    content: str,
    image_url: str | None,
    published_at: str | None,
) -> RawArticle:
    classification = classify(title, description, source.name)
    category = source.category or classification.category
    region = source.region or classification.region
    external_id = hashlib.sha256(f"{source.name}:{url}".encode("utf-8")).hexdigest()
    return RawArticle(
        title=_clean_text(title),
        url=url,
        source=source.name,
        description=_clean_text(description),
        content=_clean_text(content),
        image_url=image_url,
        published_at=published_at,
        category=category,
        region=region,
        external_id=external_id,
    )


def _first_text(element: ET.Element, *names: str) -> str | None:
    for name in names:
        for candidate in (
            element.find(name),
            element.find(f"{{http://www.w3.org/2005/Atom}}{name}"),
            element.find(f"{{http://purl.org/rss/1.0/modules/content/}}{name}"),
        ):
            if candidate is not None:
                value = "".join(candidate.itertext()).strip()
                if value:
                    return html.unescape(value)
    return None


def _rss_link(item: ET.Element) -> str | None:
    link = _first_text(item, "link")
    if link:
        return link
    for candidate in item.findall("{http://www.w3.org/2005/Atom}link"):
        href = candidate.attrib.get("href")
        if href:
            return href
    return None


def _rss_image(item: ET.Element) -> str | None:
    for element in item.iter():
        tag = _local_name(element.tag).lower()
        if tag in {"thumbnail", "content"}:
            url = element.attrib.get("url")
            if url:
                return url
        if tag in {"enclosure", "image"}:
            url = element.attrib.get("url") or (element.text or "").strip()
            mime_type = element.attrib.get("type", "")
            if url and (not mime_type or mime_type.startswith("image/")):
                return url
    return None


def _parse_date(value: str | None) -> str | None:
    if not value:
        return None
    try:
        parsed = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat()


def _clean_text(value: str) -> str:
    text = re.sub(r"<[^>]+>", " ", html.unescape(value or ""))
    return re.sub(r"\s+", " ", text).strip()


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag
