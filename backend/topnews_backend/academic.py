from __future__ import annotations

import hashlib
import html
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

from .fetchers import FetchError


AI_ARXIV_CATEGORIES = ("cs.AI", "cs.CL", "cs.CV", "cs.LG", "stat.ML")


@dataclass(frozen=True)
class KeywordRule:
    raw_rule: str
    term: str
    display_name: str
    is_required: bool = False
    is_excluded: bool = False
    is_regex: bool = False


@dataclass(frozen=True)
class RawPaper:
    external_id: str
    title: str
    authors: tuple[str, ...]
    abstract: str
    source: str
    url: str
    pdf_url: str | None
    categories: tuple[str, ...]
    published_at: str | None
    updated_at: str | None


@dataclass(frozen=True)
class PaperMatch:
    matched_keywords: tuple[str, ...]
    score: int


def parse_keyword_rule(raw_rule: str) -> KeywordRule:
    value = raw_rule.strip()
    if not value:
        raise ValueError("keyword rule cannot be empty")

    display_name = value
    if "=>" in value:
        value, display_name = [part.strip() for part in value.split("=>", 1)]

    is_required = value.startswith("+")
    is_excluded = value.startswith("!")
    if is_required or is_excluded:
        value = value[1:].strip()

    is_regex = len(value) >= 2 and value.startswith("/") and value.endswith("/")
    term = value[1:-1] if is_regex else value
    if not term:
        raise ValueError("keyword rule term cannot be empty")

    return KeywordRule(
        raw_rule=raw_rule,
        term=term,
        display_name=display_name.strip() or term,
        is_required=is_required,
        is_excluded=is_excluded,
        is_regex=is_regex,
    )


def score_paper(title: str, abstract: str, categories: list[str], rules: list[KeywordRule]) -> PaperMatch:
    matched: list[str] = []
    score = 0
    text = f"{title}\n{abstract}"
    for rule in rules:
        title_hits = _count_matches(title, rule)
        abstract_hits = _count_matches(abstract, rule)
        total_hits = title_hits + abstract_hits
        if rule.is_excluded and total_hits > 0:
            return PaperMatch(matched_keywords=tuple(), score=-1)
        if rule.is_required and total_hits == 0:
            return PaperMatch(matched_keywords=tuple(), score=-1)
        if total_hits > 0:
            matched.append(rule.display_name)
            score += title_hits * 8 + abstract_hits * 3
            if rule.is_required:
                score += 10

    if any(category in AI_ARXIV_CATEGORIES for category in categories):
        score += 5
    if _contains_recent_ai_signal(text):
        score += 4

    return PaperMatch(matched_keywords=tuple(dict.fromkeys(matched)), score=score)


def fetch_arxiv_papers(
    rules: list[KeywordRule],
    timeout: float,
    user_agent: str,
    limit: int = 30,
    source: str = "auto",
) -> list[RawPaper]:
    source = source.lower()
    if source == "rss":
        return fetch_arxiv_rss(timeout=timeout, user_agent=user_agent, limit=limit)
    if source == "api":
        return fetch_arxiv_api(rules=rules, timeout=timeout, user_agent=user_agent, limit=limit)
    if source != "auto":
        raise FetchError(f"Unsupported arXiv source: {source}")

    rss_error: FetchError | None = None
    try:
        papers = fetch_arxiv_rss(timeout=timeout, user_agent=user_agent, limit=limit)
        if papers:
            return papers
    except FetchError as exc:
        rss_error = exc

    try:
        return fetch_arxiv_api(rules=rules, timeout=timeout, user_agent=user_agent, limit=limit)
    except FetchError as api_error:
        if rss_error:
            raise FetchError(f"arXiv RSS failed: {rss_error}; arXiv API failed: {api_error}") from api_error
        raise


def fetch_arxiv_api(rules: list[KeywordRule], timeout: float, user_agent: str, limit: int = 30) -> list[RawPaper]:
    url = build_arxiv_url(rules, limit)
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return parse_arxiv_feed(response.read())
    except (TimeoutError, urllib.error.URLError) as exc:
        raise FetchError(f"Fetch failed for arXiv API: {exc}") from exc


def fetch_arxiv_rss(timeout: float, user_agent: str, limit: int = 30) -> list[RawPaper]:
    url = build_arxiv_rss_url()
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return parse_arxiv_rss(response.read(), limit=limit)
    except (TimeoutError, urllib.error.URLError) as exc:
        raise FetchError(f"Fetch failed for arXiv RSS: {exc}") from exc


def build_arxiv_url(rules: list[KeywordRule], limit: int = 30) -> str:
    positive_terms = [
        rule.term
        for rule in rules
        if not rule.is_excluded and not rule.is_regex and rule.term.strip()
    ]
    if positive_terms:
        query = " OR ".join(f'all:"{term}"' for term in positive_terms[:8])
    else:
        query = " OR ".join(f"cat:{category}" for category in AI_ARXIV_CATEGORIES)

    params = urllib.parse.urlencode(
        {
            "search_query": query,
            "start": 0,
            "max_results": min(max(limit, 1), 100),
            "sortBy": "submittedDate",
            "sortOrder": "descending",
        }
    )
    return f"https://export.arxiv.org/api/query?{params}"


def build_arxiv_rss_url(categories: tuple[str, ...] = AI_ARXIV_CATEGORIES) -> str:
    category_path = "+".join(categories)
    return f"https://rss.arxiv.org/rss/{category_path}"


def parse_arxiv_feed(body: bytes) -> list[RawPaper]:
    try:
        root = ET.fromstring(body)
    except ET.ParseError as exc:
        raise FetchError(f"arXiv parse failed: {exc}") from exc

    ns = {"atom": "http://www.w3.org/2005/Atom", "arxiv": "http://arxiv.org/schemas/atom"}
    papers: list[RawPaper] = []
    for entry in root.findall("atom:entry", ns):
        entry_id = _text(entry.find("atom:id", ns))
        title = _clean(_text(entry.find("atom:title", ns)))
        abstract = _clean(_text(entry.find("atom:summary", ns)))
        if not entry_id or not title:
            continue

        authors = tuple(
            _clean(_text(author.find("atom:name", ns)))
            for author in entry.findall("atom:author", ns)
            if _clean(_text(author.find("atom:name", ns)))
        )
        categories = tuple(
            category.attrib.get("term", "")
            for category in entry.findall("atom:category", ns)
            if category.attrib.get("term")
        )
        url = entry_id
        pdf_url: str | None = None
        for link in entry.findall("atom:link", ns):
            rel = link.attrib.get("rel", "")
            title_attr = link.attrib.get("title", "")
            href = link.attrib.get("href", "")
            if rel == "alternate" and href:
                url = href
            if title_attr == "pdf" and href:
                pdf_url = href

        papers.append(
            RawPaper(
                external_id=_paper_external_id(entry_id),
                title=title,
                authors=authors,
                abstract=abstract,
                source="arXiv",
                url=url,
                pdf_url=pdf_url,
                categories=categories,
                published_at=_iso_datetime(_text(entry.find("atom:published", ns))),
                updated_at=_iso_datetime(_text(entry.find("atom:updated", ns))),
            )
        )
    return papers


def parse_arxiv_rss(body: bytes, limit: int = 30) -> list[RawPaper]:
    try:
        root = ET.fromstring(body)
    except ET.ParseError as exc:
        raise FetchError(f"arXiv RSS parse failed: {exc}") from exc

    papers: list[RawPaper] = []
    for item in root.findall(".//item")[:limit]:
        title = _clean(_text(item.find("title")))
        link = _clean(_text(item.find("link")))
        description = _clean(_text(item.find("description")))
        if not title or not link:
            continue
        categories = tuple(_rss_categories(item))
        authors = tuple(_rss_authors(item, description))
        published_at = _rss_date(item)
        papers.append(
            RawPaper(
                external_id=_paper_external_id(link),
                title=title,
                authors=authors,
                abstract=description,
                source="arXiv RSS",
                url=link,
                pdf_url=_abs_to_pdf_url(link),
                categories=categories,
                published_at=published_at,
                updated_at=published_at,
            )
        )
    return papers


def paper_json(value: tuple[str, ...] | list[str]) -> str:
    return json.dumps(list(value), ensure_ascii=False)


def paper_list(value: str | None) -> list[str]:
    if not value:
        return []
    try:
        loaded = json.loads(value)
    except json.JSONDecodeError:
        return []
    if not isinstance(loaded, list):
        return []
    return [str(item) for item in loaded]


def _count_matches(text: str, rule: KeywordRule) -> int:
    if rule.is_regex:
        try:
            return len(re.findall(rule.term, text, flags=re.IGNORECASE))
        except re.error:
            return 0
    return text.lower().count(rule.term.lower())


def _contains_recent_ai_signal(text: str) -> bool:
    signals = ("llm", "rag", "agent", "multimodal", "diffusion", "transformer", "大模型", "多模态")
    lower = text.lower()
    return any(signal in lower for signal in signals)


def _paper_external_id(entry_id: str) -> str:
    tail = entry_id.rstrip("/").rsplit("/", 1)[-1]
    return "arxiv:" + tail if tail else hashlib.sha256(entry_id.encode("utf-8")).hexdigest()


def _abs_to_pdf_url(url: str) -> str | None:
    if "/abs/" not in url:
        return None
    return url.replace("/abs/", "/pdf/")


def _rss_categories(item: ET.Element) -> list[str]:
    values: list[str] = []
    for element in item.iter():
        if _local_name(element.tag).lower() == "category":
            value = (element.text or element.attrib.get("term", "")).strip()
            if value:
                values.append(value)
    return list(dict.fromkeys(values))


def _rss_authors(item: ET.Element, description: str) -> list[str]:
    authors: list[str] = []
    for element in item.iter():
        tag = _local_name(element.tag).lower()
        if tag in {"creator", "author"}:
            value = _clean(_text(element))
            if value:
                authors.append(value)
    if authors:
        return list(dict.fromkeys(authors))

    match = re.search(r"(?:Authors?:|作者[:：])\s*(.+?)(?:<br|\\n|$)", description, flags=re.IGNORECASE)
    if not match:
        return []
    return [part.strip() for part in re.split(r",| and ", match.group(1)) if part.strip()]


def _rss_date(item: ET.Element) -> str | None:
    for element in item.iter():
        tag = _local_name(element.tag).lower()
        if tag in {"pubdate", "published", "updated", "date"}:
            value = _clean(_text(element))
            parsed = _parse_datetime(value)
            if parsed:
                return parsed
    return None


def _iso_datetime(value: str) -> str | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat()


def _parse_datetime(value: str) -> str | None:
    parsed = _iso_datetime(value)
    if parsed:
        return parsed
    try:
        date = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None
    if date.tzinfo is None:
        date = date.replace(tzinfo=timezone.utc)
    return date.astimezone(timezone.utc).isoformat()


def _text(element: ET.Element | None) -> str:
    if element is None:
        return ""
    return "".join(element.itertext())


def _clean(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag
