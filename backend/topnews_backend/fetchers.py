from __future__ import annotations

import hashlib
import html
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
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
    content_html: str = ""
    image_url: str | None = None
    image_urls: tuple[str, ...] = ()
    published_at: str | None = None
    category: str = "综合"
    region: str = "境内"
    external_id: str | None = None


class FetchError(RuntimeError):
    pass


@dataclass(frozen=True)
class PortalLink:
    title: str
    url: str
    image_url: str | None = None


@dataclass(frozen=True)
class ArticleDetail:
    description: str = ""
    content: str = ""
    content_html: str = ""
    image_urls: tuple[str, ...] = ()


def fetch_source(source: SourceConfig, timeout: float, user_agent: str, limit: int = 30) -> list[RawArticle]:
    url = _cls_telegraph_url(source.url, limit) if source.kind == "cls_telegraph" else source.url
    body = _download(url, timeout, user_agent)
    if source.kind == "rss":
        articles = parse_rss(body, source, limit=limit)
        return _enrich_short_articles_from_pages(articles, source, timeout, user_agent)
    if source.kind == "portal":
        articles = parse_portal(body, source, limit=limit)
        return _enrich_short_articles_from_pages(articles, source, timeout, user_agent)
    if source.kind == "cls_telegraph":
        return parse_cls_telegraph(body, source, limit=limit)
    if source.kind == "eastmoney_kuaixun":
        return parse_eastmoney_kuaixun(body, source, limit=limit)
    if source.kind == "ths_kuaixun":
        return parse_ths_kuaixun(body, source, limit=limit)
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
        content = _first_text(item, "encoded", "content") or ""
        published_at = _parse_date(_first_text(item, "pubDate", "published", "updated"))
        image_urls = _dedupe_images(
            [
                *_rss_images(item, source.url),
                *_images_from_html(description, source.url),
                *_images_from_html(content, source.url),
            ]
        )
        articles.append(_article_from_values(source, title, link, description, content, content or description, image_urls, published_at))

    return articles


def parse_portal(body: bytes, source: SourceConfig, limit: int = 30) -> list[RawArticle]:
    parser = PortalParser(source.url)
    parser.feed(body.decode("utf-8", errors="ignore"))

    articles: list[RawArticle] = []
    seen_urls: set[str] = set()
    for link in parser.links:
        clean_title = _clean_inline_text(link.title)
        url = link.url
        if not _is_portal_article_link(clean_title, url, source) or url in seen_urls:
            continue
        seen_urls.add(url)
        image_url = link.image_url or parser.image_for_title(clean_title) or parser.image_url
        image_urls = _dedupe_images([image_url] if image_url else [])
        articles.append(_article_from_values(source, clean_title, url, parser.description, "", "", image_urls, None))
        if len(articles) >= limit:
            break

    return articles


def parse_article_detail(body: bytes, base_url: str) -> ArticleDetail:
    parser = ArticleDetailParser(base_url)
    parser.feed(body.decode("utf-8", errors="ignore"))
    content = _clean_text("\n\n".join(parser.text_blocks))
    content_html = "\n".join(parser.html_blocks).strip()
    return ArticleDetail(
        description=parser.description,
        content=content,
        content_html=content_html,
        image_urls=_dedupe_images(parser.image_urls),
    )


def _enrich_short_articles_from_pages(
    articles: list[RawArticle],
    source: SourceConfig,
    timeout: float,
    user_agent: str,
) -> list[RawArticle]:
    enriched: list[RawArticle] = []
    details_fetched = 0
    for article in articles:
        if _should_fetch_article_detail(article) and details_fetched < MAX_DETAIL_PAGES_PER_SOURCE:
            details_fetched += 1
            try:
                detail = parse_article_detail(_download(article.url, timeout, user_agent), article.url)
                article = _merge_article_detail(article, detail, source)
            except FetchError:
                pass
        enriched.append(article)
    return enriched


def _should_fetch_article_detail(article: RawArticle) -> bool:
    if not article.url.startswith(("http://", "https://")):
        return False
    content_length = len(_clean_inline_text(article.content or article.description))
    return content_length < MIN_DETAIL_CONTENT_CHARS or not article.content_html


def _merge_article_detail(article: RawArticle, detail: ArticleDetail, source: SourceConfig) -> RawArticle:
    content = detail.content if len(detail.content) > len(article.content) else article.content
    description = article.description or detail.description
    if detail.description and len(article.description) < 20:
        description = detail.description
    content_html = detail.content_html if detail.content_html and len(detail.content) >= len(article.content) else article.content_html
    image_urls = _dedupe_images([*article.image_urls, *detail.image_urls])
    classification = classify(article.title, description or content, source.name)
    return replace(
        article,
        description=_clean_text(description),
        content=_clean_text(content),
        content_html=content_html.strip(),
        image_url=image_urls[0] if image_urls else article.image_url,
        image_urls=image_urls,
        category=source.category or classification.category,
        region=source.region or classification.region,
    )


def parse_cls_telegraph(body: bytes, source: SourceConfig, limit: int = 30) -> list[RawArticle]:
    data = _json_body(body, source)
    articles: list[RawArticle] = []
    for item in data.get("data", {}).get("roll_data", [])[:limit]:
        article_id = str(item.get("id") or "").strip()
        content = _clean_text(str(item.get("content") or item.get("brief") or ""))
        title = _clean_inline_text(str(item.get("brief") or content))[:100]
        if not title or not article_id:
            continue
        articles.append(
            _article_from_values(
                source,
                title,
                f"https://www.cls.cn/telegraph/{article_id}",
                content,
                content,
                "",
                (),
                _timestamp_to_iso(item.get("ctime")),
            )
        )
    return articles


def parse_eastmoney_kuaixun(body: bytes, source: SourceConfig, limit: int = 30) -> list[RawArticle]:
    text = body.decode("utf-8", errors="ignore")
    match = re.search(r"var\s+ajaxResult\s*=\s*(\{.*\})\s*;?", text, re.DOTALL)
    if not match:
        raise FetchError(f"Eastmoney response parse failed for {source.name}")
    try:
        data = json.loads(match.group(1))
    except json.JSONDecodeError as exc:
        raise FetchError(f"Eastmoney JSON parse failed for {source.name}: {exc}") from exc

    articles: list[RawArticle] = []
    for item in data.get("LivesList", [])[:limit]:
        news_id = str(item.get("newsid") or "").strip()
        title = _clean_inline_text(str(item.get("title") or ""))
        description = _clean_text(str(item.get("digest") or ""))
        if not title or not news_id:
            continue
        articles.append(
            _article_from_values(
                source,
                title,
                f"https://kuaixun.eastmoney.com/a/{news_id}",
                description,
                description,
                "",
                (),
                _parse_china_local_time(str(item.get("showtime") or "")),
            )
        )
    return articles


def parse_ths_kuaixun(body: bytes, source: SourceConfig, limit: int = 30) -> list[RawArticle]:
    data = _json_body(body, source)
    articles: list[RawArticle] = []
    for item in data.get("data", {}).get("list", [])[:limit]:
        seq = str(item.get("seq") or "").strip()
        title = _clean_inline_text(str(item.get("title") or ""))
        description = _clean_text(str(item.get("digest") or item.get("remark") or ""))
        if not title or not seq:
            continue
        articles.append(
            _article_from_values(
                source,
                title,
                f"https://news.10jqka.com.cn/{seq}",
                description,
                description,
                "",
                (),
                _timestamp_to_iso(item.get("ctime")),
            )
        )
    return articles


class PortalParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.links: list[PortalLink] = []
        self.description = ""
        self.image_url: str | None = None
        self.images: list[tuple[str, str]] = []
        self._current_href: str | None = None
        self._current_text: list[str] = []
        self._current_image_url: str | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr_map = {name.lower(): value or "" for name, value in attrs}
        if tag.lower() == "a":
            href = attr_map.get("href", "")
            if href.startswith("#") or href.lower().startswith(("javascript:", "mailto:")):
                return
            self._current_href = urllib.parse.urljoin(self.base_url, href)
            self._current_text = []
            self._current_image_url = None
        elif tag.lower() == "meta":
            name = (attr_map.get("name") or attr_map.get("property") or "").lower()
            content = attr_map.get("content", "")
            if name in {"description", "og:description"} and content and not self.description:
                self.description = _clean_text(content)
            if name in {"og:image", "twitter:image"} and content and not self.image_url:
                self.image_url = urllib.parse.urljoin(self.base_url, content)
        elif tag.lower() == "img":
            image_url = _image_from_attrs(attr_map, self.base_url)
            image_text = _clean_inline_text(attr_map.get("alt", "") or attr_map.get("title", ""))
            if image_url:
                if image_text:
                    self.images.append((image_text, image_url))
                if self._current_href and not self._current_image_url:
                    self._current_image_url = image_url
                if self._current_href and image_text:
                    self._current_text.append(image_text)

    def handle_data(self, data: str) -> None:
        if self._current_href:
            self._current_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "a" and self._current_href:
            text = _dedupe_repeated_title(_clean_inline_text("".join(self._current_text)))
            if text:
                self.links.append(PortalLink(title=text, url=self._current_href, image_url=self._current_image_url))
            self._current_href = None
            self._current_text = []
            self._current_image_url = None

    def image_for_title(self, title: str) -> str | None:
        normalized_title = _clean_inline_text(title)
        if not normalized_title:
            return None
        for image_text, image_url in self.images:
            if image_text and (image_text in normalized_title or normalized_title in image_text):
                return image_url
        return None


class ArticleDetailParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.description = ""
        self.text_blocks: list[str] = []
        self.html_blocks: list[str] = []
        self.image_urls: list[str] = []
        self._ignore_depth = 0
        self._capture_tag: str | None = None
        self._capture_text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        lower_tag = tag.lower()
        attr_map = {name.lower(): value or "" for name, value in attrs}
        if lower_tag in ARTICLE_DETAIL_IGNORED_TAGS:
            self._ignore_depth += 1
            return
        if self._ignore_depth:
            return
        if lower_tag == "meta":
            name = (attr_map.get("name") or attr_map.get("property") or "").lower()
            content = attr_map.get("content", "")
            if name in {"description", "og:description"} and content and not self.description:
                self.description = _clean_text(content)
            if name in {"og:image", "twitter:image"} and content:
                self._add_image(content)
            return
        if lower_tag == "img":
            image_url = _image_from_attrs(attr_map, self.base_url)
            if image_url:
                self._add_image(image_url)
                if self._capture_tag:
                    self.html_blocks.append(f'<img src="{html.escape(image_url, quote=True)}" />')
            return
        if lower_tag in ARTICLE_DETAIL_TEXT_TAGS and not self._capture_tag:
            self._capture_tag = lower_tag
            self._capture_text = []

    def handle_data(self, data: str) -> None:
        if self._ignore_depth or not self._capture_tag:
            return
        self._capture_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        lower_tag = tag.lower()
        if lower_tag in ARTICLE_DETAIL_IGNORED_TAGS and self._ignore_depth:
            self._ignore_depth -= 1
            return
        if self._ignore_depth:
            return
        if self._capture_tag == lower_tag:
            text = _clean_inline_text("".join(self._capture_text))
            if _is_article_text_block(text, lower_tag):
                self.text_blocks.append(text)
                html_tag = "p" if lower_tag in {"p", "li"} else lower_tag
                self.html_blocks.append(f"<{html_tag}>{html.escape(text)}</{html_tag}>")
            self._capture_tag = None
            self._capture_text = []

    def _add_image(self, url: str) -> None:
        image_url = _absolute_image_url(url, self.base_url)
        if image_url and image_url not in self.image_urls and not _looks_decorative_image(image_url):
            self.image_urls.append(image_url)


def _download(url: str, timeout: float, user_agent: str) -> bytes:
    headers = {"User-Agent": user_agent, "Accept": "*/*"}
    parsed_host = urllib.parse.urlparse(url).netloc
    if parsed_host.endswith("cls.cn"):
        headers["Referer"] = "https://www.cls.cn/telegraph"
        headers["Content-Type"] = "application/json;charset=utf-8"
    elif parsed_host.endswith("eastmoney.com"):
        headers["Referer"] = "https://kuaixun.eastmoney.com/"
    elif parsed_host.endswith("10jqka.com.cn"):
        headers["Referer"] = "https://news.10jqka.com.cn/"
    request = urllib.request.Request(url, headers=headers)
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
    content_html: str,
    image_urls: tuple[str, ...],
    published_at: str | None,
) -> RawArticle:
    classification = classify(title, description, source.name)
    category = source.category or classification.category
    region = source.region or classification.region
    external_id = hashlib.sha256(f"{source.name}:{url}".encode("utf-8")).hexdigest()
    return RawArticle(
        title=_clean_inline_text(title),
        url=url,
        source=source.name,
        description=_clean_text(description),
        content=_clean_text(content),
        content_html=content_html.strip(),
        image_url=image_urls[0] if image_urls else None,
        image_urls=image_urls,
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


def _rss_images(item: ET.Element, base_url: str) -> list[str]:
    images: list[str] = []
    for element in item.iter():
        tag = _local_name(element.tag).lower()
        if tag in {"thumbnail", "content"}:
            url = element.attrib.get("url") or element.attrib.get("src")
            if url:
                image_url = _absolute_image_url(url, base_url)
                if image_url:
                    images.append(image_url)
        if tag in {"enclosure", "image"}:
            url = element.attrib.get("url") or (element.text or "").strip()
            mime_type = element.attrib.get("type", "")
            if url and (not mime_type or mime_type.startswith("image/")):
                image_url = _absolute_image_url(url, base_url)
                if image_url:
                    images.append(image_url)
    return images


def _images_from_html(value: str, base_url: str) -> list[str]:
    if not value:
        return []
    images: list[str] = []
    for match in re.finditer(r"<img\b[^>]*>", value, flags=re.IGNORECASE):
        attrs = dict(
            (name.lower(), html.unescape(raw_value or ""))
            for name, raw_value in re.findall(r"""([\w:-]+)\s*=\s*["']([^"']*)["']""", match.group(0))
        )
        image_url = _image_from_attrs(attrs, base_url)
        if image_url:
            images.append(image_url)
    return images


def _image_from_attrs(attrs: dict[str, str], base_url: str) -> str | None:
    srcset = attrs.get("srcset") or attrs.get("data-srcset")
    if srcset:
        first = srcset.split(",", 1)[0].strip().split(" ", 1)[0]
        image_url = _absolute_image_url(first, base_url) if first else None
        if image_url and not _looks_junk_image_candidate(image_url, attrs):
            return image_url

    for key in ("data-original", "data-src", "data-lazy-src", "src"):
        value = attrs.get(key, "").strip()
        image_url = _absolute_image_url(value, base_url)
        if image_url and not _looks_junk_image_candidate(image_url, attrs):
            return image_url
    return None


def _absolute_image_url(value: str | None, base_url: str) -> str | None:
    if not value:
        return None
    clean_value = value.strip()
    if not clean_value or clean_value.startswith("data:image"):
        return None
    return urllib.parse.urljoin(base_url, clean_value)


def _dedupe_images(values: list[str]) -> tuple[str, ...]:
    images: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value and value not in seen and not _looks_decorative_image(value):
            seen.add(value)
            images.append(value)
        if len(images) >= MAX_ARTICLE_IMAGES:
            break
    return tuple(images)


def _is_article_text_block(text: str, tag: str) -> bool:
    if not text:
        return False
    if tag in {"h1", "h2", "h3", "h4", "blockquote"}:
        return len(text) >= 4
    if len(text) < 12:
        return False
    lowered = text.lower()
    return not any(token in lowered for token in ARTICLE_DETAIL_JUNK_TEXT)


def _looks_decorative_image(url: str) -> bool:
    lowered = url.lower()
    return any(token in lowered for token in ("logo", "icon", "avatar", "button", "badge", "qrcode", "qr-code", "placeholder", "default"))


def _looks_junk_image_candidate(url: str, attrs: dict[str, str]) -> bool:
    text = " ".join(
        attrs.get(key, "")
        for key in ("alt", "title", "aria-label", "data-alt")
    ).strip().lower()
    return _looks_decorative_image(url) or any(token in text for token in ARTICLE_IMAGE_JUNK_TEXT)


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


def _json_body(body: bytes, source: SourceConfig) -> dict[str, object]:
    try:
        parsed = json.loads(body.decode("utf-8", errors="ignore"))
    except json.JSONDecodeError as exc:
        raise FetchError(f"JSON parse failed for {source.name}: {exc}") from exc
    if not isinstance(parsed, dict):
        raise FetchError(f"JSON root is not an object for {source.name}")
    return parsed


def _timestamp_to_iso(value: object) -> str | None:
    try:
        timestamp = int(value or 0)
    except (TypeError, ValueError):
        return None
    if timestamp <= 0:
        return None
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat()


def _cls_telegraph_url(base_url: str, limit: int) -> str:
    parsed_url = urllib.parse.urlparse(base_url)
    params = {key: values[-1] for key, values in urllib.parse.parse_qs(parsed_url.query, keep_blank_values=True).items()}
    params.pop("sign", None)
    params.setdefault("app", "CailianpressWeb")
    params.setdefault("category", "")
    params.setdefault("os", "web")
    params["rn"] = str(min(max(limit, 1), 50))
    query = urllib.parse.urlencode(params)
    signature = hashlib.md5(hashlib.sha1(query.encode("utf-8")).hexdigest().encode("utf-8")).hexdigest()
    return urllib.parse.urlunparse(
        (
            parsed_url.scheme,
            parsed_url.netloc,
            parsed_url.path,
            parsed_url.params,
            f"{query}&sign={signature}",
            parsed_url.fragment,
        )
    )


def _parse_china_local_time(value: str) -> str | None:
    if not value:
        return None
    try:
        parsed = datetime.strptime(value, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return None
    return parsed.replace(tzinfo=timezone(timedelta(hours=8))).astimezone(timezone.utc).isoformat()


def _is_portal_article_link(title: str, url: str, source: SourceConfig) -> bool:
    if len(title) < 6:
        return False
    if title.lower() in PORTAL_NON_ARTICLE_TITLES:
        return False
    if re.fullmatch(r"[\d\s+\-()]{6,}", title):
        return False

    parsed_url = urllib.parse.urlparse(url)
    host = parsed_url.netloc.lower()
    path = parsed_url.path.strip("/")
    if not path:
        return False

    if "zaobao.com.sg" in urllib.parse.urlparse(source.url).netloc.lower():
        if host and not host.endswith("zaobao.com.sg"):
            return False
        if path in {"realtime/china", "news/china", "news"}:
            return False
        if path.startswith(("shop", "zshop", "advertise", "subscription", "about", "contact")):
            return False
        if not re.search(r"\d{6,}", path):
            return False

    return True


def _clean_text(value: str) -> str:
    text = html.unescape(value or "")
    text = re.sub(r"(?i)<\s*br\s*/?\s*>", "\n", text)
    text = re.sub(r"(?i)</\s*(p|div|article|section|li|h[1-6])\s*>", "\n", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"[^\S\n]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def _clean_inline_text(value: str) -> str:
    return re.sub(r"\s+", " ", _clean_text(value)).strip()


def _dedupe_repeated_title(value: str) -> str:
    if not value:
        return value
    parts = value.split(" ")
    if len(parts) % 2 == 0:
        midpoint = len(parts) // 2
        first = " ".join(parts[:midpoint])
        second = " ".join(parts[midpoint:])
        if first and first == second:
            return first
    compact = value.replace(" ", "")
    if len(compact) % 2 == 0:
        midpoint = len(compact) // 2
        if compact[:midpoint] == compact[midpoint:]:
            return compact[:midpoint]
    return value


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


MAX_ARTICLE_IMAGES = 9
MIN_DETAIL_CONTENT_CHARS = 260
MAX_DETAIL_PAGES_PER_SOURCE = 12

ARTICLE_DETAIL_TEXT_TAGS = {"p", "h1", "h2", "h3", "h4", "blockquote", "li"}

ARTICLE_DETAIL_IGNORED_TAGS = {
    "script",
    "style",
    "noscript",
    "svg",
    "nav",
    "header",
    "footer",
    "aside",
    "form",
    "button",
}

ARTICLE_DETAIL_JUNK_TEXT = {
    "copyright",
    "版权所有",
    "未经授权",
    "责任编辑",
    "相关阅读",
    "推荐阅读",
    "扫码",
    "关注我们",
}

ARTICLE_IMAGE_JUNK_TEXT = {
    "广告",
    "推广",
    "赞助",
    "阿里云",
    "aliyun",
    "placeholder",
    "default",
}

PORTAL_NON_ARTICLE_TITLES = {
    "zaobao",
    "zaobao.com",
    "zshop 集品店",
    "新报业媒体网站",
}
