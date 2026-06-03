from __future__ import annotations

import html
import mimetypes
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser

from .fetchers import FetchError


MAX_IMAGE_BYTES = 8 * 1024 * 1024
SUPPORTED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp", "image/gif"}


@dataclass(frozen=True)
class PaperFigure:
    image_url: str
    caption: str
    width: int = 0
    height: int = 0


@dataclass(frozen=True)
class PaperFigureImage:
    data: bytes
    mime_type: str


class ArxivFigureParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.figures: list[PaperFigure] = []
        self._in_figure = False
        self._in_caption = False
        self._image_url: str | None = None
        self._width = 0
        self._height = 0
        self._caption_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr_map = {name.lower(): value or "" for name, value in attrs}
        if tag.lower() == "figure" and "ltx_figure" in attr_map.get("class", "").split():
            self._in_figure = True
            self._image_url = None
            self._width = 0
            self._height = 0
            self._caption_parts = []
            return
        if not self._in_figure:
            return
        if tag.lower() == "img" and not self._image_url:
            src = attr_map.get("src", "").strip()
            if src and not _looks_decorative(src):
                self._image_url = urllib.parse.urljoin(self.base_url, src)
                self._width = _positive_int(attr_map.get("width"))
                self._height = _positive_int(attr_map.get("height"))
        elif tag.lower() == "figcaption":
            self._in_caption = True

    def handle_data(self, data: str) -> None:
        if self._in_caption:
            self._caption_parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "figcaption":
            self._in_caption = False
        elif tag.lower() == "figure" and self._in_figure:
            if self._image_url:
                self.figures.append(
                    PaperFigure(
                        image_url=self._image_url,
                        caption=_clean_text(" ".join(self._caption_parts)),
                        width=self._width,
                        height=self._height,
                    )
                )
            self._in_figure = False
            self._in_caption = False


def fetch_arxiv_primary_figure(
    paper_url: str,
    timeout: float,
    user_agent: str,
) -> PaperFigure | None:
    html_url = build_arxiv_html_url(paper_url)
    request = urllib.request.Request(
        html_url,
        headers={"User-Agent": user_agent, "Accept": "text/html,application/xhtml+xml"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return parse_arxiv_primary_figure(response.read(), html_url)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise FetchError(f"Fetch failed for arXiv HTML {html_url}: {exc}") from exc
    except (TimeoutError, urllib.error.URLError) as exc:
        raise FetchError(f"Fetch failed for arXiv HTML {html_url}: {exc}") from exc


def fetch_paper_figure_image(
    image_url: str,
    timeout: float,
    user_agent: str,
) -> PaperFigureImage:
    request = urllib.request.Request(
        image_url,
        headers={"User-Agent": user_agent, "Accept": "image/jpeg,image/png,image/webp,image/gif"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content_length = int(response.headers.get("Content-Length", "0") or 0)
            if content_length > MAX_IMAGE_BYTES:
                raise FetchError(f"Paper figure is too large: {content_length} bytes")
            data = response.read(MAX_IMAGE_BYTES + 1)
            if len(data) > MAX_IMAGE_BYTES:
                raise FetchError(f"Paper figure is too large: more than {MAX_IMAGE_BYTES} bytes")
            mime_type = response.headers.get_content_type()
    except (TimeoutError, urllib.error.URLError) as exc:
        raise FetchError(f"Fetch failed for paper figure {image_url}: {exc}") from exc

    if mime_type == "application/octet-stream":
        mime_type = mimetypes.guess_type(image_url)[0] or mime_type
    if mime_type not in SUPPORTED_IMAGE_TYPES:
        raise FetchError(f"Unsupported paper figure type: {mime_type}")
    if not data:
        raise FetchError(f"Paper figure is empty: {image_url}")
    return PaperFigureImage(data=data, mime_type=mime_type)


def parse_arxiv_primary_figure(body: bytes, base_url: str) -> PaperFigure | None:
    parser = ArxivFigureParser(base_url)
    parser.feed(body.decode("utf-8", errors="ignore"))
    candidates = parser.figures[:8]
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda figure: _figure_score(figure, candidates.index(figure)),
    )


def build_arxiv_html_url(paper_url: str) -> str:
    parsed = urllib.parse.urlparse(paper_url)
    path = parsed.path.rstrip("/")
    for marker in ("/abs/", "/pdf/", "/html/"):
        if marker in path:
            paper_id = path.split(marker, 1)[1].removesuffix(".pdf")
            if paper_id:
                return f"https://arxiv.org/html/{paper_id}"
    raise FetchError(f"Unsupported arXiv paper URL: {paper_url}")


def _looks_decorative(src: str) -> bool:
    lower = src.lower()
    return any(token in lower for token in ("logo", "icon", "button", "badge", "avatar"))


def _figure_score(figure: PaperFigure, index: int) -> tuple[int, int, int, int]:
    text = f"{figure.image_url} {figure.caption}".lower()
    signals = (
        "teaser",
        "overview",
        "architecture",
        "framework",
        "pipeline",
        "our method",
        "our model",
        "we present",
        "system overview",
    )
    return (
        int(any(signal in text for signal in signals)),
        int(index == 0),
        figure.width * figure.height,
        -index,
    )


def _positive_int(value: str | None) -> int:
    try:
        return max(int(float(value or "0")), 0)
    except ValueError:
        return 0


def _clean_text(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()
