from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

from .summary import plain_text


class LlmConfigError(RuntimeError):
    pass


class LlmRequestError(RuntimeError):
    pass


@dataclass(frozen=True)
class LlmClient:
    base_url: str
    api_key: str
    model: str
    timeout: float = 20.0

    def summarize(self, title: str, body: str, source: str = "") -> str:
        if not self.base_url or not self.api_key or not self.model:
            raise LlmConfigError("LLM service is not configured")

        endpoint = self.base_url.rstrip("/")
        if not endpoint.endswith("/chat/completions"):
            endpoint = f"{endpoint}/chat/completions"

        readable_body = plain_text(body)[:6000]
        payload = {
            "model": self.model,
            "temperature": 0.2,
            "max_tokens": 360,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "你是 TopNews 的新闻总结助手。请只基于用户提供的新闻内容总结，"
                        "用简体中文输出 3 到 5 个要点，不编造事实，不添加外部信息。"
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        f"新闻来源：{source or '未知'}\n"
                        f"标题：{title}\n\n"
                        f"正文：{readable_body}"
                    ),
                },
            ],
        }
        request = urllib.request.Request(
            endpoint,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "TopNewsBot/0.1",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")
            raise LlmRequestError(f"LLM request failed: HTTP {exc.code} {message[:300]}") from exc
        except urllib.error.URLError as exc:
            raise LlmRequestError(f"LLM request failed: {exc.reason}") from exc

        try:
            data = json.loads(raw)
            summary = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
            raise LlmRequestError("LLM response format is invalid") from exc

        summary = str(summary).strip()
        if not summary:
            raise LlmRequestError("LLM returned an empty summary")
        return summary
