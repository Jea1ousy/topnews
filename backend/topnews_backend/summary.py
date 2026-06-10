from __future__ import annotations

import html
import re
from html.parser import HTMLParser


DEFAULT_SUMMARY_LENGTH = 180


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def build_summary(*values: str | None, max_length: int = DEFAULT_SUMMARY_LENGTH) -> str:
    for value in values:
        text = plain_text(value)
        if text:
            return _truncate(text, max_length)
    return ""


def plain_text(value: str | None) -> str:
    if not value:
        return ""
    parser = _TextExtractor()
    parser.feed(html.unescape(value))
    return re.sub(r"\s+", " ", " ".join(parser.parts)).strip()


def _truncate(text: str, max_length: int) -> str:
    if max_length <= 0 or len(text) <= max_length:
        return text

    candidate = text[: max_length + 1]
    sentence_end = max(candidate.rfind(mark) for mark in ("。", "！", "？", ".", "!", "?"))
    if sentence_end >= max_length // 3:
        return candidate[: sentence_end + 1].strip()
    return text[:max_length].rstrip(" ,，。.!！?？;；:：") + "..."
