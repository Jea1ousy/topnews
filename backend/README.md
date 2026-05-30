# TopNews Backend

TopNews 后端用于聚合中国境内、境外新闻，支持 RSS 和新闻门户页面抓取，完成规则分类、SQLite 存储，并向客户端提供新闻推荐接口。

## 功能

- RSS 聚合：解析 RSS 2.0 和 Atom 常见字段。
- 门户抓取：从新闻门户页面抽取链接标题、摘要和封面元信息。
- 自动分类：按关键词归类为国内、国际、科技、财经、体育、娱乐、综合，并标记境内、境外。
- 数据库存储：使用 SQLite 去重保存新闻。
- 推荐接口：按内容完整度、发布时间、分类权重进行排序。
- 学术推荐：通过关键词规则抓取并推荐 arXiv 论文。
- AI 前沿：为客户端提供 AI 前沿栏目数据入口。
- 客户端兼容：提供 `/news/channel` 和 `/news/list`，方便现有 Android Retrofit 客户端切换。

## 运行

```powershell
python -m backend.topnews_backend.cli ingest --limit-per-source 20
python -m backend.topnews_backend.cli keyword-add "RAG"
python -m backend.topnews_backend.cli papers-ingest --limit 20 --source auto
python -m backend.topnews_backend.cli serve --host 127.0.0.1 --port 8080
```

接口地址：

- `GET /health`
- `POST /v1/ingest`
- `GET /v1/news?page=1&pageSize=20&category=国内&region=境内`
- `GET /v1/recommendations?page=1&pageSize=20&q=AI`
- `GET /v1/ai-frontier?page=1&pageSize=20`
- `GET /v1/academic/keywords`
- `POST /v1/academic/keywords`
- `DELETE /v1/academic/keywords/{id}`
- `POST /v1/papers/ingest?source=auto`
- `GET /v1/papers/recommendations?page=1&pageSize=20&q=RAG`
- `GET /v1/categories`
- `GET /v1/sources`
- `POST /news/channel`
- `POST /news/list`

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
python -m backend.topnews_backend.cli papers-ingest --limit 20 --source rss
python -m backend.topnews_backend.cli papers-ingest --limit 20 --source api
python -m backend.topnews_backend.cli papers-ingest --limit 20 --source auto
```

- `rss`：按 arXiv 分类订阅抓取，例如 `cs.AI`、`cs.CL`、`cs.CV`、`cs.LG`、`stat.ML`。更适合稳定拉取 AI 前沿最新论文。
- `api`：按关键词构造 arXiv API 查询。更适合精准搜索，但更容易遇到 429 限流。
- `auto`：默认值，先走 RSS；RSS 为空或失败时再尝试 API。

arXiv 官方要求 legacy API、RSS 等访问遵守限速：单连接，并且不要超过每 3 秒 1 次请求。遇到 `HTTP Error 429` 时，通常说明被限流了，可以等待一会儿后改用 `--source rss` 重试。

## 配置

默认读取 `backend/sources.example.json`。可通过环境变量覆盖：

- `TOPNEWS_CONFIG`：来源配置文件路径
- `TOPNEWS_DB`：SQLite 数据库路径
- `TOPNEWS_HOST`：服务监听地址
- `TOPNEWS_PORT`：服务端口
- `TOPNEWS_TIMEOUT`：抓取超时时间

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
