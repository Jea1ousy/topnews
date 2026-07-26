# TopNews 整体优化方案

> 基于当前工作区代码（分支 `optimize-1782576585`，含未提交改动）与远端 `origin/master` 的 diff 分析。
> 三个目标：①前端流畅性 ②后端推荐与调度 ③部署资源数。

---

## 0. 现状盘点

项目为 Android（Kotlin + Compose + Retrofit + Coil）客户端 + Python 标准库后端（`ThreadingHTTPServer` + SQLite）。远端 master 之后，本地分支已经完成了一轮有效优化：`academic.py` 加入了泛词消歧（agent/diffusion 语境降权）并提高 AI 信号加分；`storage.py` 修复了 `recommend()` 分页遗漏、把新闻/论文去重从 O(n²) 相似度比较改为标题指纹分组 O(n)、给论文推荐加了 10 分钟进程内缓存、SQLite 开启 WAL + busy_timeout、论文查询不再 `SELECT *`（避免把图片 BLOB 拖进内存）。未提交的工作区改动是这轮优化的延续（`refresh` 参数、`_flag` 工具、缓存失效钩子），方向正确，建议先提交。

在此基础上，下面是仍然存在的主要瓶颈与对应方案。

---

## 1. 前端流畅性

### 1.1 最大瓶颈其实在接口负载（前后端一起改，收益最大）

列表接口（`/v1/news`、`/v1/recommendations`、`/v1/ai-frontier`）目前把每篇文章的 `description`、`content`、`content_html`、`summary`、全部 `image_urls` 都返回给客户端。一页 20 条长文，JSON 很容易到数百 KB 甚至 MB 级：网络传输慢、Gson 反序列化慢、`NewsArticle` 常驻内存大，首屏与翻页的卡顿大头都在这里。

方案：给分页接口加 `fields=list` 模式（或默认瘦身），列表只返回 `external_id / title / summary(180字) / source / category / image_url / published_at / ai_summary是否存在`；点开文章时再调 `GET /v1/articles/{id}` 拿全文。客户端在打开预览卡片时预取正文即可，用户感知不到延迟。同时后端对 JSON 响应加 gzip（`Accept-Encoding` 判断 + `gzip.compress`，中文长文压缩比通常 5–10 倍），OkHttp 会自动解压。这一项对"前端流畅性"和"部署带宽成本"同时起效。

### 1.2 Compose 层面的具体问题

**每帧触发重组的状态读法。** `NewsPreviewOverlay` 里 `cardOffsetPx` 是逐帧动画状态，`offset { }` 用 lambda 延迟读取是对的，但 `scrimAlpha` 和 `cornerRadius` 是在组合阶段直接算出来的，导致拖拽/入场动画期间整个 Overlay 每帧重组。改法：scrim 用 `Modifier.drawBehind { drawRect(Color.Black.copy(alpha = ...)) }` 在 draw 阶段读状态；圆角用 `graphicsLayer { shape = ...; clip = true }` 或在 `drawWithCache` 中读，保证帧动画只走布局/绘制，不走重组。

**正文解析在主线程。** `FullArticleDetail` 里 `detailBlocks()` 用多个正则解析整篇 HTML，虽然包在 `remember` 里，但首次展开长文会在主线程做一次重解析，容易掉帧。改为 `produceState`/`withContext(Dispatchers.Default)` 异步解析，解析期间先展示 `summary` 占位；或者配合 1.1 的详情接口，让后端直接返回结构化 blocks（后端本来就有 `ArticleDetailParser`），客户端不再做 HTML 解析。

**图片布局抖动。** `PreviewImage` 用 `onSuccess` 回调改 `aspectRatio` 并叠加 `animateContentSize()`，图片加载完成瞬间正文整体跳动、滚动位置漂移。建议后端抓图时顺带存宽高（正文接口返回 `width/height`），客户端首帧就用正确比例占位；至少去掉 `animateContentSize()`，把比例缓存从 `remember(imageUrl)` 提升到全局 `object AspectRatioCache`，同一图片二次进入不再跳。

**图片解码尺寸与缓存。** `NewsListItem` 缩略图 112×76dp 直接 `AsyncImage(model = imageUrl)`，Coil 会按原图解码再缩小。建一个全局 `ImageLoader`（Application 级）：`crossfade(true)`、显式 `memoryCache`/`diskCache` 上限（如 64MB 磁盘），列表项用 `ImageRequest.Builder().size()` 限制解码尺寸；后端 `/image` 已带 `Cache-Control: max-age=86400`，Coil 磁盘缓存会直接命中，弱网下滚动明显更稳。

**稳定性注解减少无效重组。** `NewsArticle`、`CategoryFeedState` 含 `List`/`Set` 字段，Compose 默认视为不稳定，父级任何状态变化都会让整列表 item 重组。给 domain model 加 `@Immutable`，或在 `stability config` 里声明；`LazyColumn` 的 `items` 补上 `contentType`。配合现有的 `key = article.id`，滚动重组开销会显著下降。

**Release 构建。** `isMinifyEnabled = false`：开启 R8（minify + resources shrink），并添加 Baseline Profile（`androidx.baselineprofile` 插件，录制首页滚动路径），冷启动和首次滚动 jank 通常能降 20–30%。资源里 `lhzb.png`(154KB)、`xhs.jpg`(137KB)、`ic_source_36kr.png`(81KB) 等图标建议压到 WebP ≤20KB。

### 1.3 数据层小问题

`HomeViewModel.summarizeArticle` 成功后遍历所有分类的所有文章做 map 复制，文章多时会造成一次大状态拷贝，可以只更新命中分类；`seenArticleIds` 的 `toList().takeLast(300).toSet()` 每次刷新都重建集合，改用 `ArrayDeque` 或直接信任后端 `exclude`。`formatRelativeTime` 在映射时用 `SimpleDateFormat`（每篇 new 一个），换 `java.time` + 复用 formatter。这些是次级优化，排在上面之后。

---

## 2. 后端推荐与调度

### 2.1 推荐：把"每次请求现算"改成"入库时算好"

现状：`recommend()` 每个请求都拉 300+ 行候选（含 `content`、`content_html` 全文）进 Python 再算质量分×时间衰减、指纹去重；`ai_frontier` 每次用 14 个 AI 信号 × 3 列做 `LIKE '%…%'` 全扫描。单人使用能跑，但每次下拉刷新都是一次全量重排，延迟和 CPU 都浪费。

方案（保持 SQLite + 纯 Python，不引入新组件）：

1. **入库时打分**：`upsert_articles` 时算好并落库三个派生列：`quality_score REAL`（现 `_article_quality_score` 逻辑）、`is_ai_news INTEGER`（现 AI 信号判断）、`title_fingerprint TEXT`（现指纹函数），分别建索引。时间衰减是唯一需要请求时计算的因子，而 `exp(-λ·h)` 单调，等价于直接 `ORDER BY (quality_score, published_at)` 的组合；更简单的做法是 SQL 里 `ORDER BY quality_score * exp(-0.04 * (julianday('now') - julianday(published_at)) * 24) DESC`（SQLite 自带 `exp`≥3.35；若版本低，用自定义函数 `create_function` 注册）。这样 `recommend` 变成一条带索引的 SQL + 仅对当页做指纹去重；`ai_frontier` 变成 `WHERE is_ai_news = 1` 的索引查询，`LIKE` 全部消失。
2. **候选列瘦身**：即使保留 Python 精排，候选查询也不要取 `content/content_html`（打分只用长度，长度也可入库为 `content_len`），排完再按 id 取当页 20 条全量数据。候选集内存占用可降一个数量级。
3. **新闻推荐缓存**：与论文缓存同构，给 `recommend()` 加 60–120 秒 TTL 的进程内缓存（key = category/region/query/source），下拉刷新和翻页大多命中。注意：一旦按 2.4 合并调度进 API 进程，ingest 完成即可精确失效。
4. **轻量个性化闭环**（下一阶段）：目前"个性化"只有 AI 信号加分，没有用户反馈。加一张 `events(external_id, kind, ts)` 表和 `POST /v1/events`（点击、展开全文、AI 总结即强信号），入库时对来源/类别做指数衰减的亲和度统计，作为 `quality_score` 的乘性因子。单用户场景百行代码即可，推荐会明显"越用越懂"。
5. 论文侧已有关键词打分+缓存+去重，剩余改进：把 `score_paper` 也挪到 `upsert_papers` 时算基础分（关键词变化时全量重算一次即可，关键词很少变），缓存 miss 时不再全表扫。

### 2.2 调度：并发抓取 + 增量丰富 + 分层间隔

现状 `run_scheduler` 单线程顺序执行：12 个源逐个抓，timeout 12s，坏源能把一轮拖到分钟级；更严重的是 `_enrich_short_articles_from_pages` 对每个源最多串行抓 12 个详情页（36氪还强制抓），而且**不区分文章是否已入库**——每 10 分钟一轮，旧文章的详情页被反复重抓，是当前最大的无效流量与耗时来源。

方案：

1. **增量丰富**：ingest 前先 `SELECT external_id FROM articles WHERE external_id IN (...)`，已存在且 `content` 非空的跳过详情抓取。一行判断，抓取量预计降 70%+。
2. **并发抓源**：`ThreadPoolExecutor(max_workers=4~6)` 并发 `fetch_source`，单轮从"求和"变"取最大"，10 分钟间隔可以安全缩短。
3. **条件请求**：RSS 源记录 `ETag/Last-Modified`，带 `If-None-Match/If-Modified-Since`，304 直接跳过解析。
4. **分层间隔**：财联社/东财/同花顺快讯 5 分钟，普通 RSS 15–30 分钟，portal 30 分钟；在 `SourceConfig` 加 `interval_minutes` 字段即可，调度器已支持按 job 分间隔，扩展为按源分组。
5. **失败退避**：连续失败的源做指数退避（10→20→40 分钟），避免坏源每轮浪费 timeout；`IngestResult` 已有 error 字段，落一张 `source_health` 表还能顺便做观测。
6. **摘要预生成（可选）**：调度器在每轮 ingest 后对推荐前 10 条且无 `ai_summary` 的文章预调 LLM，用户点开即秒出。注意加并发上限 1、失败静默，控制 token 成本。
7. **单飞锁**：`/ai-summary` 接口对同一 `external_id` 加 in-flight 锁，防止用户连点造成并发重复调 LLM。

### 2.3 一个需要注意的一致性问题

论文推荐缓存是**进程内**的：`serve` 和 `scheduler` 是两个进程，scheduler 进程里 `upsert_papers` 调的 `invalidate_paper_recommendation_cache()` 只失效自己进程的缓存，API 进程要等 10 分钟 TTL 才看到新论文。这不是 bug 但值得知道；按 2.4 合并进程后自然解决。

---

## 3. 部署资源数

目标是把整个后端压到"一个进程、一个数据库文件、一个服务单元"。

**合并为单进程。** `serve` 启动时用 `threading.Thread(target=run_scheduler, daemon=True)` 把调度器跑在 API 进程内（CLI 加 `serve --with-scheduler`）。收益：部署单元从 2 个进程/服务减到 1 个；进程内缓存失效精确生效（解决 2.3）；SQLite 写竞争减少（同进程共享 WAL）。抓取是 IO 密集，GIL 不构成问题。

**控制 SQLite 体积（当前最大的资源风险）。** 图片 BLOB 直接存库且永不清理，文章无限累积，DB 只会单调膨胀。加一个每日保留任务（挂进调度器）：删除 30 天前的文章（时间衰减下 7 天后分数已≈0.1%，老文章不可能再出现在推荐里）；对 14 天前的文章置空 `content_html` 与 `image_data`；论文保留 90 天；每周 `PRAGMA incremental_vacuum`（配 `auto_vacuum=INCREMENTAL`）。这能把 DB 稳定在几百 MB 内，决定了你能否长期用 1 台最小规格机器跑。

**响应压缩与图片上限。** 1.1 的 gzip 同时省带宽；`/image` 缓存时限制单图 ≤500KB（超出的按需转 JPEG 质量 80 压缩，`Pillow` 可选依赖），避免把 2MB 原图存库又推给手机。

**部署形态建议。** 单容器/单 systemd 服务即可：`python -m backend.topnews_backend.cli serve --with-scheduler`，资源限额 1 vCPU / 512MB–1GB 内存 / 5–10GB 磁盘完全够（当前技术栈无任何重依赖，这是它的优点，建议保持 stdlib，不要为了框架换 FastAPI）。加两件小事：`/health` 已有，配合 systemd `Restart=on-failure` 或 docker healthcheck；数据备份用一行 cron `sqlite3 topnews.db ".backup ..."` 或 Litestream 推对象存储。Android 端是本地 APK 无服务器成本，唯一相关项是开启 R8 减包体。

---

## 4. 实施顺序建议

| 优先级 | 事项 | 主要收益 | 工作量 |
|---|---|---|---|
| P0 | 列表接口字段瘦身 + gzip（1.1） | 首屏/翻页流畅度、带宽 | 半天 |
| P0 | 增量丰富：已入库文章跳过详情抓取（2.2-1） | 抓取耗时/流量 -70% | 1 小时 |
| P0 | 数据保留任务（3） | 资源上限可控 | 半天 |
| P0 | scheduler 合并进 serve 进程（3） | 部署单元 2→1，缓存一致 | 2 小时 |
| P1 | 入库打分 + is_ai_news 索引 + 新闻推荐缓存（2.1） | 接口延迟、CPU | 1 天 |
| P1 | Coil 全局 ImageLoader + 解码尺寸 + 比例缓存（1.2） | 滚动帧率 | 半天 |
| P1 | scrim/cornerRadius 延迟读取、detailBlocks 异步（1.2） | 卡片动画帧率 | 半天 |
| P1 | R8 + Baseline Profile + 图标压缩（1.2） | 冷启动、包体 | 半天 |
| P2 | 并发抓源 + 条件请求 + 分层间隔 + 退避（2.2） | 内容新鲜度 | 1 天 |
| P2 | 事件上报 + 来源/类别亲和度个性化（2.1-4） | 推荐质量 | 1–2 天 |
| P2 | 摘要预生成 + 单飞锁、@Immutable 注解等 | 体验细节 | 各半天 |

先把 4 个 P0 做完（合计约两天），前端可感知的流畅度、后端负载和部署形态都会有台阶式改善；P1 是纯客户端帧率专项；P2 属于质量迭代。
