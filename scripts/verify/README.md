# 验收脚本

配合 [`optimization-round2.md`](../../optimization-round2.md) 使用。除 `to_webp.py` 外都是只读的，可以反复跑。

| 脚本 | 用途 | 用法 |
|---|---|---|
| `bench.ps1` | 冷启动 + 滚动帧率基准（预热一轮后取三轮均值） | `.\scripts\verify\bench.ps1 -Apk out.apk -Label after` |
| `check_parser.py` | 单页看 `ArticleDetailParser` 抽出的正文块 | `python scripts/verify/check_parser.py <文章URL>` |
| `compare_parser.py` | 对比"开/关导航过滤"的抽取差异，确认没误杀正文 | `python scripts/verify/compare_parser.py <URL> <URL> ...` |
| `verify_backend.py` | 临时库验证 `fetched_at` 不再被刷新、时间衰减与保留策略生效 | `python scripts/verify/verify_backend.py` |
| `to_webp.py` | **会改动仓库**：把 `res/drawable` 下的位图头像转成无损 WebP 并移到 `drawable-xxxhdpi` | 已执行过，仅在新增头像时再跑 |

注意：

- 后端脚本需要 **Python 3.9+**（`storage.py`/`paper_figures.py` 用了 `str.removesuffix` 等）。
- Windows 上中文输出请加 `PYTHONIOENCODING=utf-8`，否则控制台会乱码。
- 截图不要用 PowerShell 重定向 `adb exec-out screencap -p > x.png`，会破坏二进制；用
  `adb shell screencap -p /sdcard/x.png` 再 `adb pull`。
- 帧率必须在 **release** 构建上测，debug 的 JIT 开销会把差异全部盖掉。
