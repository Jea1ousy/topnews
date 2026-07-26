"""把 res/drawable 下超大的来源头像位图转成 176px WebP，落到 drawable-xxxhdpi。

头像最大显示尺寸 42dp，xxxhdpi(4x) 下约 168px，176px 已有余量。
放进 drawable-xxxhdpi 而不是 drawable(=mdpi)，避免解码时按密度放大 4 倍。
"""
import os
import pathlib

from PIL import Image

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = str(REPO_ROOT / "app/src/main/res/drawable")
DST = str(REPO_ROOT / "app/src/main/res/drawable-xxxhdpi")
MAX_EDGE = 176

os.makedirs(DST, exist_ok=True)

files = [f for f in os.listdir(SRC) if f.lower().endswith((".png", ".jpg", ".jpeg"))]
# 启动图标由 mipmap 管理，drawable 下的 ic_launcher_* 是矢量，不在此列
rows = []
for name in sorted(files):
    src_path = os.path.join(SRC, name)
    stem = os.path.splitext(name)[0]
    before = os.path.getsize(src_path)
    with Image.open(src_path) as im:
        im = im.convert("RGBA")
        w, h = im.size
        scale = min(1.0, MAX_EDGE / max(w, h))
        if scale < 1.0:
            im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
        out_path = os.path.join(DST, stem + ".webp")
        # 台标/文字 logo 用有损会出振铃，统一无损：体积仍远小于原图
        im.save(out_path, "WEBP", lossless=True, quality=100, method=6)
    after = os.path.getsize(out_path)
    rows.append((name, f"{w}x{h}", before, f"{stem}.webp", f"{im.size[0]}x{im.size[1]}", after))
    os.remove(src_path)

total_before = sum(r[2] for r in rows)
total_after = sum(r[5] for r in rows)
for r in rows:
    print(f"{r[0]:24s} {r[1]:>10s} {r[2]:>8d} B  ->  {r[3]:24s} {r[4]:>9s} {r[5]:>7d} B")
print(f"{'TOTAL':24s} {'':>10s} {total_before:>8d} B  ->  {'':24s} {'':>9s} {total_after:>7d} B"
      f"   (-{100 * (total_before - total_after) / total_before:.1f}%)")
