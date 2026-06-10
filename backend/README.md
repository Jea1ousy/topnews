# TopNews Backend

TopNews 后端用于聚合中国境内、境外新闻，支持 RSS 和新闻门户页面抓取，完成规则分类、SQLite 存储，并向客户端提供新闻推荐接口。

## 功能

- RSS 聚合：解析 RSS 2.0 和 Atom 常见字段。
- 门户抓取：从新闻门户页面抽取链接标题、摘要和封面元信息。
- 自动分类：按关键词归类为国内、国际、科技、财经、体育、娱乐、综合，并标记境内、境外。
- 数据库存储：使用 SQLite 去重保存新闻。
- 推荐接口：按内容完整度、发布时间、分类权重进行排序。
- 学术推荐：通过关键词规则抓取并推荐 arXiv 论文。
- 论文主图：从 arXiv 官方 HTML 版本提取主要图像与图注，将图片二进制缓存到 SQLite。
- 卡片摘要：新闻和论文接口统一返回适合列表卡片展示的 `summary` 字段。
- AI 总结：通过 OpenAI 兼容的大模型接口，为新闻详情和论文详情生成要点总结，并缓存到 SQLite。
- AI 前沿：返回 AI 科技资讯；学术论文保留在“学术推荐”中展示。
- 客户端兼容：提供 `/news/channel` 和 `/news/list`，方便现有 Android Retrofit 客户端切换。

## 运行

```powershell
python -m backend.topnews_backend.cli ingest --limit-per-source 20
python -m backend.topnews_backend.cli keyword-add "RAG"
python -m backend.topnews_backend.cli papers-ingest --limit 100 --source auto
python -m backend.topnews_backend.cli paper-figures-ingest --limit 10
python -m backend.topnews_backend.cli fetch --news-limit 80 --papers-limit 100 --figures-limit 10
python -m backend.topnews_backend.cli scheduler --news-interval-minutes 30 --papers-interval-minutes 180 --figures-interval-minutes 180
python -m backend.topnews_backend.cli serve --host 0.0.0.0 --port 8080
```

Bash 一键抓取：

```bash
bash backend/scripts/fetch.sh --news-limit 80 --papers-limit 100 --figures-limit 10
```

Windows PowerShell 一键抓取：

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/fetch.ps1 -NewsLimit 80 -PapersLimit 100 -FiguresLimit 10
```

启动后端服务：

```powershell
python -m backend.topnews_backend.cli serve --host 0.0.0.0 --port 8080
```

Android 本地后端访问方式：

- 模拟器：`local.properties` 可配置 `TOPNEWS_BACKEND_BASE_URL=http://127.0.0.1:8080/` 或 `http://10.0.2.2:8080/`。客户端会在模拟器中自动把 `localhost`、`127.0.0.1`、`0.0.0.0` 改写为 `10.0.2.2`。
- USB 真机：可以执行 `adb reverse tcp:8080 tcp:8080` 后继续使用 `TOPNEWS_BACKEND_BASE_URL=http://127.0.0.1:8080/`。
- 同一局域网真机：后端用 `--host 0.0.0.0` 启动，并在 `local.properties` 增加 `TOPNEWS_BACKEND_LAN_BASE_URL=http://电脑局域网IP:8080/`，例如 `http://192.168.1.23:8080/`。真机会优先使用这个地址。
- 云后端：直接把 `TOPNEWS_BACKEND_BASE_URL` 改为云端地址即可，例如 `https://api.example.com/`；未配置 LAN 地址时模拟器和真机都会使用云端地址。

常用覆盖项：

```bash
TOPNEWS_CONFIG=backend/sources.example.json TOPNEWS_DB=backend/data/topnews.db bash backend/scripts/fetch.sh
bash backend/scripts/fetch.sh --paper-source rss --skip-figures
bash backend/scripts/fetch.sh --skip-news --papers-limit 50 --force-figures
```

```powershell
$env:TOPNEWS_CONFIG = "backend/sources.example.json"
$env:TOPNEWS_DB = "backend/data/topnews.db"
powershell -ExecutionPolicy Bypass -File backend/scripts/fetch.ps1 -PaperSource rss -SkipFigures
powershell -ExecutionPolicy Bypass -File backend/scripts/fetch.ps1 -SkipNews -PapersLimit 50 -ForceFigures
python -m backend.topnews_backend.cli serve --host 0.0.0.0 --port 8080
```

接口地址：

- `GET /health`
- `POST /v1/ingest`
- `GET /v1/news?page=1&pageSize=20&category=国内&region=境内`
- `GET /v1/recommendations?page=1&pageSize=20&q=AI`
- `POST /v1/articles/{external_id}/ai-summary`
- `GET /v1/ai-frontier?page=1&pageSize=20`
- `GET /v1/academic/keywords`
- `POST /v1/academic/keywords`
- `DELETE /v1/academic/keywords/{id}`
- `POST /v1/papers/ingest?source=auto`
- `POST /v1/papers/figures/ingest?limit=10`
- `GET /v1/papers/recommendations?page=1&pageSize=20&q=RAG`
- `POST /v1/papers/{external_id}/ai-summary`
- `GET /v1/categories`
- `GET /v1/sources`
- `POST /news/channel`
- `POST /news/list`

新闻、论文和 AI 前沿接口中的每个条目都会包含：

- `summary`：从新闻描述、正文或论文摘要生成的短摘要，适合卡片式预览。
- `ai_summary`：由大模型生成并缓存的详情页总结。未生成时为 `null`。
- `item_type`：内容类型，值为 `news` 或 `paper`。
- 论文条目还可能包含 `image_url`、`image_source_url` 与 `image_caption`。`image_url` 指向 TopNews 后端图片接口，`image_source_url` 保留原始 arXiv 图片地址。

## AI 总结

AI 总结接口使用 OpenAI 兼容的 `/chat/completions` 协议。服务端读取新闻或论文正文后调用模型，返回并缓存 `summary`，后续再次请求同一条内容会直接返回缓存。

环境变量配置：

```bash
export TOPNEWS_LLM_BASE_URL=https://api.example.com/v1
export TOPNEWS_LLM_API_KEY=your-api-key
export TOPNEWS_LLM_MODEL=your-model-name
export TOPNEWS_LLM_TIMEOUT=20
```

也可以在 `backend/sources.example.json` 增加可选配置：

```json
{
  "llm": {
    "base_url": "https://api.example.com/v1",
    "model": "your-model-name",
    "timeout": 20
  }
}
```

接口示例：

```bash
curl -X POST http://127.0.0.1:8080/v1/articles/{external_id}/ai-summary
curl -X POST http://127.0.0.1:8080/v1/papers/{external_id}/ai-summary
```

返回示例：

```json
{
  "summary": "1. ...\n2. ...",
  "cached": false
}
```

如需强制重新生成，可加 `?force=true`。如果未配置大模型服务，接口会返回 `503` 和 `llm_not_configured`。

## 学术关键词规则

关键词规则参考 TrendRadar 的轻量写法：

- `RAG`：普通关键词，标题和摘要命中会加分。
- `+retrieval`：必须命中，否则不推荐。
- `!survey`：过滤词，命中后排除。
- `/multi.?modal/`：正则匹配。
- `retrieval => RAG`：匹配 retrieval，但展示名为 RAG。

第一版推荐算法使用规则评分，保持可解释、可调试：

- 标题命中权重大于摘要命中。
- 必须词命中额外加分。
- 排除词命中直接过滤。
- arXiv AI 分类会加权。

## arXiv 抓取方式

论文采集支持 RSS 和 API 两种方式：

```powershell
python -m backend.topnews_backend.cli papers-ingest --limit 100 --source rss
python -m backend.topnews_backend.cli papers-ingest --limit 100 --source api
python -m backend.topnews_backend.cli papers-ingest --limit 100 --source auto
```

- `rss`：按 arXiv 分类订阅抓取，例如 `cs.AI`、`cs.CL`、`cs.CV`、`cs.LG`、`cs.IR`、`cs.NE`、`cs.RO`、`stat.ML`。更适合稳定拉取 AI 前沿最新论文。
- `api`：按关键词构造 arXiv API 查询。更适合精准搜索，但更容易遇到 429 限流。
- `auto`：默认值，合并 RSS 与 API 结果并按论文 ID 去重；其中一种方式失败时仍可使用另一种方式的结果。

arXiv 官方要求 legacy API、RSS 等访问遵守限速：单连接，并且不要超过每 3 秒 1 次请求。遇到 `HTTP Error 429` 时，通常说明被限流了，可以等待一会儿后改用 `--source rss` 重试。

## 定时抓取

后端内置了两个适合上云使用的抓取命令：

```bash
python -m backend.topnews_backend.cli fetch --news-limit 80 --papers-limit 100 --paper-source auto --figures-limit 10
python -m backend.topnews_backend.cli scheduler --news-interval-minutes 30 --papers-interval-minutes 180 --figures-interval-minutes 180
```

- `fetch`：执行一次完整抓取，适合初始化数据库、手动补数据或 crontab 调用。
- `scheduler`：常驻进程，默认启动时立即抓一次，之后每 30 分钟抓新闻、每 180 分钟抓论文和论文主图。
- 如需关闭某类定时任务，可把对应间隔设为 `0`，例如 `--figures-interval-minutes 0`。
- 日志以 JSON 行输出，适合通过 `journalctl` 查看。

服务器建议同时运行两个 systemd 服务：一个负责 HTTP API，一个负责定时抓取。

`/etc/systemd/system/topnews-api.service`：

```ini
[Unit]
Description=TopNews API
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/topnews
Environment=TOPNEWS_CONFIG=/opt/topnews/backend/sources.example.json
Environment=TOPNEWS_DB=/opt/topnews/backend/data/topnews.db
Environment=TOPNEWS_LLM_BASE_URL=https://api.example.com/v1
Environment=TOPNEWS_LLM_API_KEY=your-api-key
Environment=TOPNEWS_LLM_MODEL=your-model-name
ExecStart=/opt/topnews/.venv/bin/python -m backend.topnews_backend.cli serve --host 127.0.0.1 --port 8080
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/topnews-scheduler.service`：

```ini
[Unit]
Description=TopNews scheduled fetch
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/topnews
Environment=TOPNEWS_CONFIG=/opt/topnews/backend/sources.example.json
Environment=TOPNEWS_DB=/opt/topnews/backend/data/topnews.db
ExecStart=/opt/topnews/.venv/bin/python -m backend.topnews_backend.cli scheduler --news-interval-minutes 30 --papers-interval-minutes 180 --figures-interval-minutes 180 --paper-source auto
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启用服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now topnews-api topnews-scheduler
sudo systemctl status topnews-api
sudo systemctl status topnews-scheduler
journalctl -u topnews-scheduler -f
```

## arXiv 论文主图

arXiv RSS 和 API 不直接提供论文配图。论文入库后，可以运行独立的主图富化任务：

```powershell
python -m backend.topnews_backend.cli paper-figures-ingest --limit 10 --delay-seconds 3
```

- 富化任务访问 arXiv 官方 HTML 论文页面，从正文前几张图中选择尺寸较大的主要图像。
- 图片二进制、原始地址和图注会缓存到 SQLite，之后真机只访问 TopNews 后端，不需要直接访问 arXiv 图片域名。
- 缓存图片通过 `GET /v1/papers/{external_id}/image` 返回。
- 部分论文没有 HTML 版本或没有可用图片，此时保持无图，不影响论文摘要和推荐。
- 默认每篇论文之间等待 3 秒，避免短时间内产生过多请求。
- 如需重新选择已经检查过的论文主图，可增加 `--force`。

## 配置

默认读取 `backend/sources.example.json`。可通过环境变量覆盖：

- `TOPNEWS_CONFIG`：来源配置文件路径
- `TOPNEWS_DB`：SQLite 数据库路径
- `TOPNEWS_HOST`：服务监听地址
- `TOPNEWS_PORT`：服务端口
- `TOPNEWS_TIMEOUT`：抓取超时时间
- `TOPNEWS_LLM_BASE_URL`：OpenAI 兼容大模型接口地址，例如 `https://api.example.com/v1`
- `TOPNEWS_LLM_API_KEY`：大模型接口密钥
- `TOPNEWS_LLM_MODEL`：用于生成 AI 总结的模型名
- `TOPNEWS_LLM_TIMEOUT`：AI 总结请求超时时间，默认跟随 `TOPNEWS_TIMEOUT`

新增来源示例：

```json
{
  "name": "示例RSS",
  "kind": "rss",
  "url": "https://example.com/feed.xml",
  "region": "境内",
  "category": "科技"
}
```

门户抓取来源将 `kind` 设置为 `portal`。
