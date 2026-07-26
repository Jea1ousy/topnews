# 第二轮：前端改动验收 + 后续优化

> 承接 [`optimization-plan.md`](optimization-plan.md) 与提交 `b89811c`（P0+P1 第一轮）。
> 本轮先在真机模拟器上用 adb 验收上一轮的 Android 改动，再把验收中暴露的问题和剩余优化一并做掉。

## 0. 一句话结论

上一轮的 Android 改动**功能全部有效**，但 **release 变体从未成功构建过**——`isMinifyEnabled = true` 是在一个编译不过的资源目录上开的。修好后实测：冷启动 8.1s → 0.88s，滚动掉帧率 54% → 16%，APK 14.7MB → 2.66MB。此外在验收过程中发现并修复了 3 个后端缺陷（导航串污染正文、时间衰减完全失效、缩略图下发原图）。

---

## 1. 验收环境与方法

| 项 | 值 |
|---|---|
| 模拟器 | `Pixel_10_Pro` / Android 17 (SDK 37) / x86_64 / 480dpi / 1280x2856 |
| adb | `C:\Users\1\AppData\Local\Android\Sdk\platform-tools\adb.exe`（不在 PATH，需全路径） |
| 后端 | 线上 `http://193.112.193.205/`（`local.properties` 中配置） |
| 构建 | `.\gradlew.bat :app:assembleDebug` / `:app:assembleRelease` |

release APK 未配签名，验收时用 debug keystore 手工签名后安装（不改动项目的 signingConfig）：

```powershell
$bt = "C:\Users\1\AppData\Local\Android\Sdk\build-tools\36.0.0"
& "$bt\zipalign.exe" -f 4 app\build\outputs\apk\release\app-release-unsigned.apk out.apk
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" `
    --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey out.apk
```

常用验收命令：

```powershell
adb shell am start -W -n com.example.topnews/.MainActivity   # 冷启动 TotalTime
adb shell dumpsys gfxinfo com.example.topnews reset          # 清帧统计
adb shell input swipe 640 2200 640 700 300                   # 模拟滚动
adb shell dumpsys gfxinfo com.example.topnews                # 读 janky / 各分位
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png # 截图（不要用 PowerShell 重定向，会损坏二进制）
adb shell pm grant com.example.topnews android.permission.ACCESS_FINE_LOCATION  # 跳过定位弹窗
```

重复三轮的基准脚本见 `scripts/verify/bench.ps1`（先预热一轮把图片灌进 Coil 缓存，再测三轮取均值，避免把网络耗时算进渲染）。

---

## 2. 上一轮 Android 改动的逐项验收

| 改动 | 结论 | 依据 |
|---|---|---|
| 列表默认 `fields=list` | ✅ 有效 | 同一接口 `fields=list` 传输 **7.5KB**（解压 19.8KB），默认 `full` 传输 **90.3KB**（解压 592KB）→ 传输量 -92%，解析量 -97% |
| 打开文章按需拉全文并合并回信息流 | ✅ 有效 | 点开预览卡后正文由 summary 占位变为完整分段正文 + 内联配图（`ensureArticleDetail` → `/v1/articles/{id}`）；预览卡与全屏详情均正常 |
| 预览卡 scrim/圆角改 draw/graphicsLayer 阶段读取 | ✅ 有效 | 拖拽预览卡期间 `Slow UI thread` 从 debug 的 84 降到 release 的 2~3；代码层面已确认逐帧状态只在 `drawBehind` / `graphicsLayer` 内读取 |
| 正文 HTML 解析移到 Default 调度器 | ✅ 有效 | `produceState` + `withContext(Dispatchers.Default)`；长文（7.3KB 正文）首次展开无掉帧 |
| 图片宽高比缓存 + 去掉 animateContentSize | ✅ 有效（本轮加固） | 二次进入同一文章配图首帧即正确比例；但缓存是无界 `HashMap`，本轮改为有界 LRU |
| 列表缩略图按尺寸解码 + crossfade | ⚠️ 有效但不够 | `.size()` 只限制**解码**，下载的仍是原图。见 §4.3 |
| `@Immutable` / `contentType` | ✅ 已就位 | `NewsArticle` 等已加 `@Immutable`，`NewsList` 的 `items` 已带 `contentType` |
| release 开 R8 + 资源裁剪 | ❌ **构建失败** | 见 §3.1 |
| Coil 全局 ImageLoader（计划 1.2） | ❌ **未实现** | 项目里没有 Application 类，用的是 Coil 默认单例。本轮补上 |
| `summarizeArticle` 只更新命中分类（计划 1.3） | ❌ **未实现** | 仍无条件 `mapValues` 所有分类的所有文章。本轮补上 |
| `seenArticleIds` 不再重建集合（计划 1.3） | ❌ **未实现** | 仍是 `toList().takeLast(300).toSet()`。本轮补上 |
| `formatRelativeTime` 复用 formatter（计划 1.3） | ❌ **未实现** | 每篇文章仍 new 最多 4 个 `SimpleDateFormat`。本轮补上，并发现映射整体跑在主线程 |
| Baseline Profile（计划 1.2） | ❌ 未实现 | 见 §6 |

---

## 3. 本轮修复的缺陷

### 3.1 release 构建被资源问题挡住（阻断级）

`app/src/main/res/drawable/lzw.png` **实际是一个 JPEG 文件**（`ffd8 ffe0 ... JFIF`），只是扩展名写成了 `.png`：

```
lzw.png: JPEG image data, JFIF standard 1.01, ..., 180x180
```

debug 能过是因为 debug 不做 PNG crunch；release 走 `crunchPngs` 时 aapt2 直接失败：

```
ERROR: D:\topnews\app\src\main\res\drawable\lzw.png: AAPT: error: file failed to compile.
Execution failed for task ':app:mergeReleaseResources'.
```

也就是说 `b89811c` 里"release 开启 R8 + 资源裁剪"这条从未被验证过。

**修法**：把 drawable 下所有位图来源头像转成**无损 WebP**（logo 带文字，有损会出振铃），并从 `drawable/`（= mdpi）移到 `drawable-xxxhdpi/`——头像最大显示 42dp，xxxhdpi 下约 168px，放在 mdpi 桶里会在解码时被按密度放大 4 倍。资源名不变，所以 `R.drawable.xxx` 全部沿用，Kotlin 侧零改动。

| 文件 | 原始 | 转换后 |
|---|---|---|
| `ic_source_36kr.png` | 1028x1028, 82.7KB | 176x176, 5.8KB |
| `lhzb.png` | 500x500, 157.7KB | 176x176, 17.4KB |
| `xhs.jpg` | 1080x1176, 140.6KB | 162x176, 32.7KB |
| `ths.png` | 256x256, 27.5KB | 176x176, 12.3KB |
| `dfcf.png` | 698x698, 23.5KB | 176x176, 5.0KB |
| 其余 4 个 | — | — |
| **合计** | **446.3KB** | **88.4KB（-80.2%）** |

转换脚本：`scripts/verify/to_webp.py`。

### 3.2 正文被站点导航串污染（后端）

预览卡首段长期显示：

> 36氪Auto数字时氪未来消费智能涌现未来城市启动Power on36氪出海36氪研究院潮生TIDE36氪企服点评…

`ArticleDetailParser` 已经跳过 `nav/header/footer/aside`，但 36氪 的顶栏不是语义标签，而是一堆 `<a>` 塞在 `div`/`p` 里，`handle_data` 把这些锚文本拼成了一个"段落"。

**修法**（`fetchers.py`）：加链接密度启发式——一个文本块里 ≥2 个 `<a>` 且 ≥60% 的字符来自锚文本时判为导航并丢弃。正文里偶尔出现的单个内链不受影响（既不满足链接数，也不满足密度）。

用线上真实页面对比"关闭/开启过滤"的抽取结果：

| 页面 | 块数 | 字符数 | 被丢弃的块 |
|---|---|---|---|
| 36氪快讯 | 13 → 7 | 634 → 412 | 6 个，全部是导航/入口栏 |
| 联合早报 | 11 → 11 | 473 → 473 | 0 |
| 新时空财经 | 30 → 30 | 2057 → 2057 | 0 |

再走一遍完整 ingest 管道（`fetch_source` → `enrich_articles` → `upsert_articles`）确认落库的 `content` 已直接从标题/正文开始，不再有导航串。脚本：`scripts/verify/compare_parser.py`。

### 3.3 时间衰减对门户源完全失效（后端）

`推荐` 一页 40 条里，36氪快讯 13 条 + 新华社首页 17 条 = **75%**，且时间全部显示成同一个"N分钟前"。根因是两件事叠加：

1. `parse_portal()` 从不设置 `published_at`（portal 源恒为 `NULL`），排序与保留策略都靠 `COALESCE(published_at, fetched_at)` 兜底；
2. `upsert_articles` 的冲突分支里写着 `fetched_at=excluded.fetched_at`——**每 10 分钟一轮抓取都把 `fetched_at` 刷成 now**。

结果：门户首页上挂了三天的稿子，每轮都被重新标记为"刚发布"，永远处于时间衰减的最高分，既永久霸占推荐首页，也**永远不会被 30 天保留任务清掉**（DB 体积控制同时失效）。

**修法**（`storage.py`）：`fetched_at` 取最早值，即保留首次入库时间。

```sql
fetched_at=MIN(COALESCE(articles.fetched_at, excluded.fetched_at), excluded.fetched_at)
```

本地临时库三项验证（脚本 `scripts/verify/verify_backend.py`），同一脚本对 `HEAD` 版本代码执行会在第 1 项断言失败：

| 检查 | 改动前 | 改动后 |
|---|---|---|
| 同一文章连抓三轮后 `fetched_at` 是否不变 | ❌ 被刷新 | ✅ 保持首次入库时间 |
| 3 天前入库 vs 刚入库，推荐顺序 | （前一项已失败） | ✅ 新的排在前 |
| `apply_retention(article_max_age_days=1)` 能否删掉旧文章 | （同上） | ✅ 2 条 → 1 条 |

> 仍未解决：portal 源本身缺少真实发布时间。36氪 详情页虽然有 `<meta property="article:published_time">`，但实测其值是**页面渲染时刻**（抓取时是 19:11，meta 就写 19:11:11），照抄会让排序更糟，因此本轮没做。见 §6。

### 3.4 缩略图下发原图，图片占掉整页 99% 的流量（后端 + 客户端）

列表瘦身把 JSON 压到了 20KB，但缩略图仍是原图。实测一页 20 条：

| Tab | 列表 JSON | 缩略图合计 | 均值 | 最大 | 单张最慢 |
|---|---|---|---|---|---|
| 推荐 | 19KB | **929KB**（9 张） | 103KB | 204KB | 1.8s |
| 学术推荐 | 27KB | **9735KB**（17 张） | 573KB | 3.9MB | 10.6s |

也就是说 JSON 只占学术推荐整页流量的 **0.3%**，而这些图只画在 112x76dp 上。这直接解释了模拟器上「学术推荐」要 6~25s 才出内容——列表接口在和多 MB 的 arXiv 配图抢带宽（接口自身实测只要 120ms）。

**修法**：

- 后端新增 `topnews_backend/images.py`，`/v1/{articles,papers}/{id}/image?w=336` 返回降采样 JPEG。Pillow 是**可选依赖**（未安装则原样返回，老部署不会报错）；宽度归一化到 `160/336/480/720` 档位；变体放**进程内 32MB LRU 而不入库**，避免和「控制 DB 体积」的保留策略打对台；缓存版本号直接复用 URL 里已有的 `?v=` 指纹，省掉每请求重新哈希原图。
- 客户端 `NewsListItem` 对后端自己的 `/image` 端点追加 `&w=336`。旧版后端会忽略未知查询参数，因此**可以先于后端发布**。

实测（线上真实图片走本地缩放路径）：

| 组 | 原图 | w=336 |
|---|---|---|
| 推荐 9 张 | 929KB | **159KB（-82.9%）** |
| 学术推荐 10 张 | 2951KB | **127KB（-95.7%）** |

端到端（本地起服务 + 真实缓存图）：

```
原图 (无 w)            http=200 image/jpeg    31.3 KB    589 ms  public, max-age=86400
w=336 首次             http=200 image/jpeg    14.0 KB     19 ms
w=336 二次(命中LRU)     http=200 image/jpeg    14.0 KB      3 ms
w=160                 http=200 image/jpeg     4.9 KB     32 ms
w=abc 非法→原图         http=200 image/jpeg    31.3 KB     28 ms
```

---

## 4. 本轮新增的客户端优化

### 4.1 全局 Coil ImageLoader（补齐计划 1.2）

新增 `TopNewsApplication : Application(), ImageLoaderFactory` 并在 manifest 注册：内存缓存占堆 20%、磁盘缓存 64MB（后端 `/image` 已带 `max-age=86400`，冷启动后仍能命中）、`crossfade`、`respectCacheHeaders(false)`（新闻配图来源杂，很多站点不带 `Cache-Control`）。

### 4.2 三个 OkHttpClient 合并为一个共享实例

`TopNewsBackendRepository` / `WeatherRepository` / `AliNewsRepository` 原先各 new 一个 `OkHttpClient` = 三套 Dispatcher 线程池 + 三个连接池 + 三份 DNS 缓存。新增 `data/remote/SharedHttp.kt`：

- `apiClient`：统一实例，需要不同超时的调用方走 `withTimeouts()`（基于 `newBuilder()` 派生，线程池与连接池仍共享）
- `imageClient()`：**复用连接池但换独立 Dispatcher**。这一点是有意为之——OkHttp 默认 `maxRequestsPerHost=5`，而正文图片与列表接口同源，共用 Dispatcher 时滚动产生的几十个图片请求会把下一页接口挤在队列后面（正是 §3.4 现象的放大器）。

### 4.3 DTO→domain 映射移出主线程

Retrofit 的 suspend 函数在**调用方的调度器**上恢复（这里是 `viewModelScope` = Main），所以 `toNewsPage()` 里 20 条文章的日期解析、URL 拼接、正文 HTML 图片重写**默认全在主线程执行**，每次翻页/刷新都要付这笔固定开销。`fetchPage` 与 `getArticleDetail` 现在统一 `withContext(Dispatchers.Default)` 之后再映射。

同时把 `formatRelativeTime` 的 4 个 `SimpleDateFormat` + `Calendar` 改为 `ThreadLocal` 复用（原先每篇文章都要 new 一遍才输出一个"8分钟前"；`SimpleDateFormat` 非线程安全，所以按线程各持一份而不是单例）。

### 4.4 HomeViewModel 状态拷贝收敛

- `summarizeArticle` / `ensureArticleDetail` 统一走新的 `replaceArticle()`：只重建真正包含该文章的分类，其余分类原对象直接复用（Compose 也就不会重组它们）。原实现无条件 `mapValues` 所有分类的所有文章，多分类都加载过之后一次 AI 总结要复制上千个 `NewsArticle`。
- `seenArticleIds` 改为 `plusBounded()` 增量裁剪，替代每次 `toList().takeLast(300).toSet()` 重建三份集合。
- `currentTimeText()` 复用单个 formatter（`viewModelScope` 主线程单线程执行，安全）。
- `detailLoadedIds` 加 500 条上限，避免长会话无界增长。

### 4.5 图片宽高比缓存改为有界 LRU

`NewsPreviewSheet` 的 `imageAspectRatioCache` 原为普通 `HashMap`，长会话里翻过的每张图都会永久留一条。改为 256 条上限、按访问顺序淘汰的 `LinkedHashMap`，读写加锁（Coil 回调线程不作保证）。

---

## 5. 实测数据

同一台模拟器、同一后端。滚动为 8 次上滑 + 4 次下滑，`bench.ps1` 预热一轮后取三轮均值。

| 指标 | debug | release（修好构建后） | release（本轮优化后） |
|---|---|---|---|
| 冷启动 TotalTime | 8091 / 8253 ms | 626 / 1403 / 497 ms | 584 / 657 / 604 ms |
| 滚动掉帧率 | **54.4%** | 20.5% | **16.0%** |
| p50 帧耗时 | 38 ms | 17 ms | 17 ms |
| p90 | 109 ms | 19.7 ms | 19.0 ms |
| p99 | 250 ms | 32 ms | **27 ms** |
| Slow UI thread | 84 | 2.7 | 2.3 |
| Missed Vsync | 39 | 0 | 0 |
| APK 体积 | 14.66 MB | 2.59 MB | 2.66 MB |

读法：

- **量级最大的一步是把 release 构建修好**（R8 + 资源裁剪 + Compose 库自带的 baseline profile）：冷启动 8.1s → 0.6s，掉帧率 54% → 20%，APK 14.7MB → 2.6MB。之前所有"帧率优化"在 debug 上都被 JIT 开销盖掉了，这也是为什么必须用 release 验收。
- 本轮客户端优化的收益体现在**长尾**：p50 已经贴着 60Hz 的 17ms 上限（两版都是 60fps），改善在掉帧率 20.5% → 16.0%、p99 32ms → 27ms，以及冷启动不再出现 1403ms 的抖动。
- 模拟器测量噪声不小，单轮数据不可靠，故取三轮均值；APK 从 2.59 增至 2.66MB 是把有损 WebP 换成无损的代价（保 logo 文字清晰度）。
- release 构建原本失败，**没有"开 R8 之前"的 release 基线可比**，表中 debug 列即当时唯一可运行的构建。

---

## 6. 功能回归清单（release + R8）

R8 全模式下反射相关的路径逐条走过，无崩溃、无 `JsonSyntaxException`：

| 路径 | 结果 |
|---|---|
| 推荐 / AI前沿 / 时政 / 财经 列表 | ✅ |
| 学术推荐（论文 + 配图 + LaTeX） | ✅（慢，原因见 §3.4） |
| 打开文章 → 按需拉全文 → 全屏详情 + 内联配图 | ✅ |
| AI 总结（`POST /v1/articles/{id}/ai-summary`） | ✅ 正常返回并渲染 |
| 学术关键词 列表 / 新增 / 删除 | ✅ 增删往返验证后已恢复原状（仅剩 diffusion、agent） |
| 天气（`uapis.cn`，独立 DTO） | ✅ |
| 来源头像（9 个 WebP） | ✅ 全部正常渲染 |
| 客户端带 `&w=336` 打未升级的线上后端 | ✅ 参数被忽略，缩略图照常加载（向后兼容） |

---

## 7. 状态与下一步

### 已可直接使用

客户端全部改动均已在模拟器 release 构建上验证。`&w=336` 向后兼容，可先发客户端。

### 需要部署后端才能闭环

§3.2 / §3.3 / §3.4 的后端改动只在本地验证过（真实页面 + 本地 SQLite + 本地起服务），**线上 `193.112.193.205` 尚未部署**，因此设备上仍能看到导航串和"全部 N 分钟前"。部署时注意：

1. `images.py` 的降采样需要 `Pillow`；不装也能跑（回落原图），但 §3.4 的收益就拿不到。这是给一个刻意保持 stdlib 的后端引入第一个第三方依赖，**要不要装请你定**。
2. §3.3 只影响新的 upsert。历史数据里被反复刷新过的 `fetched_at` 不会自动回退，需要一次性修正或等 30 天保留任务自然汰换。

### 建议的后续项（按性价比）

1. **portal 源的真实发布时间**（§3.3 遗留）。36氪 的 `article:published_time` 是渲染时刻不可用；可行方向是从列表页条目自带的相对时间（"8分钟前"）或详情页正文里的日期串解析，需按源逐个验证——做错会让排序更糟，所以值得单独一轮。
2. **Baseline Profile**（计划 1.2 遗留）。当前 release 已包含 Compose 各库自带的 profile（`mergeReleaseStartupProfile`），但应用自身代码未被 profile 覆盖。需要新建 `:baselineprofile` 模块 + macrobenchmark 录制首页滚动路径。当前冷启动 0.88s，收益空间比修构建时小得多。
3. **release 签名配置**。目前 `assembleRelease` 产出未签名 APK，每次验收都要手工签名。
4. **`/image` 的图片上限**（计划 §3 遗留）。降采样解决了下发侧，但入库侧仍会把 3.9MB 原图整张存进 SQLite BLOB。

---

## 8. 改动文件一览

**客户端**

| 文件 | 改动 |
|---|---|
| `TopNewsApplication.kt` | 新增，全局 Coil ImageLoader |
| `data/remote/SharedHttp.kt` | 新增，共享 OkHttp + 独立图片 Dispatcher |
| `AndroidManifest.xml` | 注册 `android:name=".TopNewsApplication"` |
| `TopNewsBackendRepository.kt` | 映射移出主线程、ThreadLocal formatter、接共享 client |
| `WeatherRepository.kt` / `AliNewsRepository.kt` | 接共享 client |
| `HomeViewModel.kt` | `replaceArticle` / `plusBounded` / formatter 复用 / id 集合上限 |
| `NewsPreviewSheet.kt` | 宽高比缓存改有界 LRU |
| `NewsListItem.kt` | 缩略图请求追加 `&w=336` |
| `res/drawable/*.{png,jpg}` → `res/drawable-xxxhdpi/*.webp` | 9 个头像转无损 WebP，446KB → 88KB |

**后端**

| 文件 | 改动 |
|---|---|
| `fetchers.py` | `ArticleDetailParser` 链接密度过滤导航块 |
| `storage.py` | `upsert_articles` 保留首次入库的 `fetched_at` |
| `images.py` | 新增，缩略图按宽度降采样 + 进程内 LRU（Pillow 可选） |
| `api.py` | `/image` 支持 `?w=` |

**文档与脚本**

| 文件 | 内容 |
|---|---|
| `optimization-round2.md` | 本文档 |
| `scripts/verify/README.md` | 各脚本用途与注意事项 |
| `scripts/verify/bench.ps1` | 冷启动 + 滚动帧率基准 |
| `scripts/verify/check_parser.py` | 单页看正文抽取结果 |
| `scripts/verify/compare_parser.py` | 对比导航过滤开/关，确认无误杀 |
| `scripts/verify/verify_backend.py` | 临时库验证 §3.3 的三项行为 |
| `scripts/verify/to_webp.py` | 头像转无损 WebP（已执行过） |
