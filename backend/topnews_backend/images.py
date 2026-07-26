"""缩略图按宽度降采样。

背景：列表接口用 fields=list 之后 JSON 只有 20KB 左右，但缩略图仍然是原图——
一页「学术推荐」的 17 张 arXiv 配图合计约 9.7MB（均值 573KB、最大 3.9MB），
而客户端只把它画在 112x76dp 的位置上。图片占了整页流量的 99%，
弱网/模拟器上列表接口会和图片抢带宽，首屏要等十几秒。

做法：/v1/{articles,papers}/{id}/image?w=336 返回降采样后的 JPEG。
- Pillow 是可选依赖：装了就缩，没装就原样返回（老部署不会因此报错）
- 缩放结果放进程内 LRU，不写库：原图已经在 SQLite 里，重算只要几毫秒，
  再往库里存一份变体会和「控制 DB 体积」的保留策略打对台
"""
from __future__ import annotations

import io
import threading
from collections import OrderedDict

try:  # Pillow 为可选依赖
    from PIL import Image  # type: ignore

    _PILLOW_AVAILABLE = True
except Exception:  # pragma: no cover - 取决于部署环境
    Image = None  # type: ignore
    _PILLOW_AVAILABLE = False

# 允许的宽度档位：避免被任意 w 值撑爆缓存
ALLOWED_WIDTHS = (160, 336, 480, 720)

_CACHE_MAX_BYTES = 32 * 1024 * 1024
_JPEG_QUALITY = 80

_lock = threading.Lock()
_cache: "OrderedDict[tuple, tuple[bytes, str]]" = OrderedDict()
_cache_bytes = 0


def pillow_available() -> bool:
    return _PILLOW_AVAILABLE


def normalize_width(raw: str | None) -> int | None:
    """把查询参数里的 w 归一化到最近的档位；非法值返回 None（= 返回原图）。"""
    if not raw:
        return None
    try:
        requested = int(raw)
    except (TypeError, ValueError):
        return None
    if requested <= 0:
        return None
    for width in ALLOWED_WIDTHS:
        if requested <= width:
            return width
    return ALLOWED_WIDTHS[-1]


def downscaled(
    cache_key: tuple,
    data: bytes,
    mime_type: str,
    width: int,
) -> tuple[bytes, str] | None:
    """返回 (jpeg_bytes, mime)；无法处理时返回 None，调用方回落原图。"""
    if not _PILLOW_AVAILABLE or not data:
        return None

    key = (*cache_key, width)
    with _lock:
        cached = _cache.get(key)
        if cached is not None:
            _cache.move_to_end(key)
            return cached

    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.width <= width:
                # 原图已经够小，缩放只会白费 CPU 还可能变大
                return None
            height = max(1, round(image.height * width / image.width))
            resized = image.convert("RGB").resize((width, height), Image.LANCZOS)
            buffer = io.BytesIO()
            resized.save(buffer, "JPEG", quality=_JPEG_QUALITY, optimize=True)
    except Exception:  # 损坏图 / 不支持的格式：回落原图
        return None

    payload = (buffer.getvalue(), "image/jpeg")
    if len(payload[0]) >= len(data):
        return None

    _remember(key, payload)
    return payload


def _remember(key: tuple, payload: tuple[bytes, str]) -> None:
    global _cache_bytes
    size = len(payload[0])
    with _lock:
        if key in _cache:
            _cache_bytes -= len(_cache[key][0])
        _cache[key] = payload
        _cache.move_to_end(key)
        _cache_bytes += size
        while _cache_bytes > _CACHE_MAX_BYTES and _cache:
            _, evicted = _cache.popitem(last=False)
            _cache_bytes -= len(evicted[0])


def cache_stats() -> dict[str, int]:
    with _lock:
        return {"entries": len(_cache), "bytes": _cache_bytes}
