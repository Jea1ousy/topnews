import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from backend.topnews_backend.academic import build_arxiv_rss_url, parse_arxiv_feed, parse_arxiv_rss, parse_keyword_rule
from backend.topnews_backend.classifier import classify
from backend.topnews_backend.config import SourceConfig, load_config
from backend.topnews_backend.fetchers import fetch_source, parse_article_detail, parse_cls_telegraph, parse_eastmoney_kuaixun, parse_portal, parse_rss, parse_ths_kuaixun
from backend.topnews_backend.paper_figures import build_arxiv_html_url, parse_arxiv_primary_figure
from backend.topnews_backend.scheduler import FetchOptions, ScheduleOptions, _build_jobs, run_fetch_once
from backend.topnews_backend.service import IngestResult
from backend.topnews_backend.storage import Article, NewsStore, Paper, article_to_dict, paper_to_dict
from backend.topnews_backend.summary import build_summary


class BackendTest(unittest.TestCase):
    def test_classify_domestic_tech(self):
        result = classify("中国人工智能芯片取得新进展", "北京发布相关政策")
        self.assertEqual(result.region, "境内")
        self.assertEqual(result.category, "科技")

    def test_parse_rss(self):
        source = SourceConfig(name="测试RSS", url="https://example.com/rss", kind="rss")
        body = """
        <rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item>
            <title>中国经济政策发布</title>
            <link>https://example.com/a</link>
            <description><![CDATA[国务院发布新政策 <img src="/cover.jpg" />]]></description>
            <content:encoded><![CDATA[
                <p>第一段介绍政策背景。</p>
                <img src="/cover.jpg" />
                <p>第二段说明政策影响。</p>
                <img data-src="/detail.jpg" />
            ]]></content:encoded>
            <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
        </item></channel></rss>
        """.encode()
        articles = parse_rss(body, source)
        self.assertEqual(len(articles), 1)
        self.assertEqual(articles[0].category, "国内")
        self.assertEqual(articles[0].region, "境内")
        self.assertEqual(articles[0].image_url, "https://example.com/cover.jpg")
        self.assertEqual(
            articles[0].image_urls,
            ("https://example.com/cover.jpg", "https://example.com/detail.jpg"),
        )
        self.assertEqual(articles[0].content, "第一段介绍政策背景。\n\n第二段说明政策影响。")
        self.assertIn('<img src="/cover.jpg" />', articles[0].content_html)
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            self.assertEqual(store.upsert_articles(articles), 1)
            stored = store.list_articles(page=1, page_size=1).items[0]
            payload = article_to_dict(stored)
            self.assertIn('<img data-src="/detail.jpg" />', payload["html"])
            self.assertEqual(payload["image_source_url"], "https://example.com/cover.jpg")
            self.assertEqual(payload["image_url"], f"/v1/articles/{stored.external_id}/image")
            self.assertEqual(
                payload["image_urls"],
                ["https://example.com/cover.jpg", "https://example.com/detail.jpg"],
            )
            self.assertIsNone(store.get_article_image(stored.external_id))
            self.assertEqual(
                store.get_article_image_sources(stored.external_id),
                ["https://example.com/cover.jpg", "https://example.com/detail.jpg"],
            )
            self.assertTrue(
                store.update_article_image_cache(
                    stored.external_id,
                    b"fake-jpg",
                    "image/jpeg",
                    image_url="https://example.com/detail.jpg",
                )
            )
            self.assertEqual(store.get_article_image(stored.external_id), (b"fake-jpg", "image/jpeg"))
            self.assertEqual(store.list_articles(page=1, page_size=1).items[0].image_url, "https://example.com/detail.jpg")

    def test_parse_portal(self):
        source = SourceConfig(name="测试门户", url="https://example.com/news/", kind="portal")
        body = """
        <html><head><meta name="description" content="门户摘要"></head>
        <body><a href="/a"><img src="/a.jpg" alt="国际新闻标题足够长">国际新闻标题足够长</a><a href="#">忽略</a></body></html>
        """.encode()
        articles = parse_portal(body, source)
        self.assertEqual(len(articles), 1)
        self.assertEqual(articles[0].url, "https://example.com/a")
        self.assertEqual(articles[0].image_url, "https://example.com/a.jpg")

    def test_parse_article_detail_extracts_body_text_and_images(self):
        body = """
        <html><head>
          <meta property="og:description" content="详情页摘要">
          <meta property="og:image" content="/cover.jpg">
        </head><body>
          <nav><p>导航菜单不应进入正文</p></nav>
          <article>
            <p>第一段正文内容足够长，可以作为文章详情。</p>
            <blockquote>引用内容也应该保留下来。</blockquote>
            <p>第二段正文继续说明更多背景。</p>
            <img data-src="/detail.jpg">
          </article>
          <footer><p>版权所有，不应进入正文</p></footer>
        </body></html>
        """.encode()

        detail = parse_article_detail(body, "https://example.com/news/a")

        self.assertEqual(detail.description, "详情页摘要")
        self.assertIn("第一段正文内容足够长", detail.content)
        self.assertIn("引用内容也应该保留下来", detail.content)
        self.assertNotIn("导航菜单", detail.content)
        self.assertIn("<blockquote>", detail.content_html)
        self.assertEqual(detail.image_urls, ("https://example.com/cover.jpg", "https://example.com/detail.jpg"))

    def test_fetch_source_enriches_short_rss_articles_from_detail_page(self):
        source = SourceConfig(name="短RSS", url="https://example.com/rss", kind="rss")
        rss_body = """
        <rss><channel><item>
            <title>科技新闻标题足够长</title>
            <link>https://example.com/a</link>
            <description>短摘要</description>
        </item></channel></rss>
        """.encode()
        detail_body = """
        <html><body><article>
          <p>详情页第一段正文内容足够长，应该替换 RSS 里的短摘要。</p>
          <p>详情页第二段正文继续补充更多信息。</p>
          <img src="/detail.jpg">
        </article></body></html>
        """.encode()

        def fake_download(url, timeout, user_agent):
            return rss_body if url == source.url else detail_body

        with patch("backend.topnews_backend.fetchers._download", side_effect=fake_download):
            articles = fetch_source(source, timeout=1, user_agent="test", limit=1)

        self.assertEqual(len(articles), 1)
        self.assertIn("详情页第一段正文内容足够长", articles[0].content)
        self.assertIn("<p>详情页第二段正文继续补充更多信息。</p>", articles[0].content_html)
        self.assertEqual(articles[0].image_url, "https://example.com/detail.jpg")

    def test_parse_zaobao_portal_filters_non_article_links(self):
        source = SourceConfig(
            name="联合早报中国即时",
            url="https://www.zaobao.com.sg/realtime/china",
            kind="portal",
            region="境外",
            category="国内",
        )
        body = """
        <html><head><meta name="description" content="联合早报页面摘要"></head><body>
          <a href="/">Zaobao</a>
          <a href="/zshop">ZShop 集品店</a>
          <a href="/about">新报业媒体网站</a>
          <a href="tel:1800-7416388">1800-7416388</a>
          <a href="/realtime/china/story20260610-1234567">
            中国新闻标题足够长可以入库
            <img src="/article.jpg" alt="中国新闻标题足够长可以入库" />
          </a>
        </body></html>
        """.encode()

        articles = parse_portal(body, source)

        self.assertEqual(len(articles), 1)
        self.assertEqual(articles[0].title, "中国新闻标题足够长可以入库")
        self.assertEqual(articles[0].url, "https://www.zaobao.com.sg/realtime/china/story20260610-1234567")

    def test_parse_finance_api_sources(self):
        cls_source = SourceConfig(name="财联社电报", url="https://www.cls.cn/nodeapi/telegraphList", kind="cls_telegraph", category="财经")
        cls_articles = parse_cls_telegraph(
            '{"data":{"roll_data":[{"id":123,"brief":"财联社快讯标题","content":"财联社快讯正文","ctime":1781002800}]}}'.encode(),
            cls_source,
        )
        self.assertEqual(cls_articles[0].title, "财联社快讯标题")
        self.assertEqual(cls_articles[0].url, "https://www.cls.cn/telegraph/123")
        self.assertEqual(cls_articles[0].category, "财经")

        eastmoney_source = SourceConfig(
            name="东方财富快讯",
            url="https://newsapi.eastmoney.com/kuaixun/v1/getlist.html",
            kind="eastmoney_kuaixun",
            category="财经",
        )
        eastmoney_articles = parse_eastmoney_kuaixun(
            'var ajaxResult={"LivesList":[{"newsid":"202606091234567890","title":"东方财富快讯标题","digest":"东方财富摘要","showtime":"2026-06-09 18:00:00"}]};'.encode(),
            eastmoney_source,
        )
        self.assertEqual(eastmoney_articles[0].url, "https://kuaixun.eastmoney.com/a/202606091234567890")
        self.assertEqual(eastmoney_articles[0].description, "东方财富摘要")

        ths_source = SourceConfig(name="同花顺快讯", url="https://news.10jqka.com.cn/tapp/news/push/stock/", kind="ths_kuaixun", category="财经")
        ths_articles = parse_ths_kuaixun(
            '{"data":{"list":[{"seq":"20260609123456","title":"同花顺快讯标题","digest":"同花顺摘要","ctime":1781002800}]}}'.encode(),
            ths_source,
        )
        self.assertEqual(ths_articles[0].url, "https://news.10jqka.com.cn/20260609123456")
        self.assertEqual(ths_articles[0].content, "同花顺摘要")

    def test_store_recommend(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            source = SourceConfig(name="测试RSS", url="https://example.com/rss", kind="rss")
            articles = parse_rss(
                """
                <rss><channel><item>
                    <title>AI模型发布</title>
                    <link>https://example.com/ai</link>
                    <description>人工智能新闻摘要很完整</description>
                </item><item>
                    <title>AI芯片发布</title>
                    <link>https://example.com/chip</link>
                    <description>人工智能芯片新闻摘要</description>
                </item></channel></rss>
                """.encode(),
                source,
            )
            self.assertEqual(store.upsert_articles(articles), 2)
            page = store.recommend(page=1, page_size=10, category="科技")
            self.assertEqual(page.total_count, 2)
            self.assertEqual(article_to_dict(page.items[0])["item_type"], "news")
            self.assertTrue(article_to_dict(page.items[0])["summary"])
            self.assertEqual(article_to_dict(page.items[0])["image_urls"], [])
            refresh_page = store.recommend(
                page=1,
                page_size=10,
                category="科技",
                excluded_ids=[page.items[0].external_id],
            )
            self.assertEqual(refresh_page.total_count, 1)
            self.assertNotEqual(refresh_page.items[0].external_id, page.items[0].external_id)

    def test_article_ai_summary_is_cached_and_reset_on_content_change(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            source = SourceConfig(name="测试RSS", url="https://example.com/rss", kind="rss")
            articles = parse_rss(
                """
                <rss><channel><item>
                    <title>AI模型发布</title>
                    <link>https://example.com/ai</link>
                    <description>第一版摘要。</description>
                </item></channel></rss>
                """.encode(),
                source,
            )
            store.upsert_articles(articles)
            external_id = articles[0].external_id
            self.assertTrue(store.update_article_ai_summary(external_id, "AI 总结内容"))

            stored = store.get_article(external_id)
            self.assertIsNotNone(stored)
            self.assertEqual(stored.ai_summary, "AI 总结内容")
            self.assertEqual(article_to_dict(stored)["ai_summary"], "AI 总结内容")

            updated_articles = parse_rss(
                """
                <rss><channel><item>
                    <title>AI模型发布</title>
                    <link>https://example.com/ai</link>
                    <description>第二版摘要。</description>
                </item></channel></rss>
                """.encode(),
                source,
            )
            store.upsert_articles(updated_articles)
            self.assertIsNone(store.get_article(external_id).ai_summary)

    def test_keyword_rule(self):
        rule = parse_keyword_rule("+retrieval => RAG")
        self.assertTrue(rule.is_required)
        self.assertEqual(rule.term, "retrieval")
        self.assertEqual(rule.display_name, "RAG")

        excluded = parse_keyword_rule("!survey")
        self.assertTrue(excluded.is_excluded)

    def test_parse_arxiv_feed(self):
        body = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <id>http://arxiv.org/abs/2605.00001v1</id>
            <updated>2026-05-28T10:00:00Z</updated>
            <published>2026-05-27T10:00:00Z</published>
            <title>Retrieval Augmented Generation for Agents</title>
            <summary>We study RAG systems for LLM agents.</summary>
            <author><name>Alice</name></author>
            <category term="cs.CL" />
            <link href="http://arxiv.org/abs/2605.00001v1" rel="alternate" />
            <link href="http://arxiv.org/pdf/2605.00001v1" title="pdf" />
          </entry>
        </feed>
        """.encode()
        papers = parse_arxiv_feed(body)
        self.assertEqual(len(papers), 1)
        self.assertEqual(papers[0].external_id, "arxiv:2605.00001v1")
        self.assertEqual(papers[0].authors, ("Alice",))
        self.assertEqual(papers[0].categories, ("cs.CL",))

    def test_parse_arxiv_rss(self):
        body = """
        <rss><channel>
          <item>
            <title>RAG for AI Systems</title>
            <link>https://arxiv.org/abs/2605.00001</link>
            <description>Authors: Alice, Bob Abstract: Retrieval augmented generation.</description>
            <category>cs.AI</category>
            <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
          </item>
        </channel></rss>
        """.encode()
        papers = parse_arxiv_rss(body)
        self.assertEqual(len(papers), 1)
        self.assertEqual(papers[0].external_id, "arxiv:2605.00001")
        self.assertEqual(papers[0].source, "arXiv RSS")
        self.assertEqual(papers[0].pdf_url, "https://arxiv.org/pdf/2605.00001")
        self.assertEqual(papers[0].categories, ("cs.AI",))
        self.assertEqual(papers[0].abstract, "Retrieval augmented generation.")
        self.assertTrue(build_arxiv_rss_url().startswith("https://rss.arxiv.org/rss/"))

    def test_store_paper_recommendations(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            store.add_academic_keyword("RAG")
            papers = parse_arxiv_feed(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2605.00002v1</id>
                    <updated>2026-05-28T10:00:00Z</updated>
                    <published>2026-05-27T10:00:00Z</published>
                    <title>RAG Systems for Reliable LLM Agents</title>
                    <summary>Retrieval augmented generation improves grounding.</summary>
                    <author><name>Bob</name></author>
                    <category term="cs.AI" />
                    <link href="http://arxiv.org/abs/2605.00002v1" rel="alternate" />
                    <link href="http://arxiv.org/pdf/2605.00002v1" title="pdf" />
                  </entry>
                </feed>
                """.encode()
            )
            self.assertEqual(store.upsert_papers(papers), 1)
            page = store.recommend_papers(page=1, page_size=10)
            self.assertEqual(page.total_count, 1)
            self.assertEqual(page.items[0].matched_keywords, ["RAG"])
            self.assertGreater(page.items[0].score, 0)
            self.assertEqual(paper_to_dict(page.items[0])["item_type"], "paper")
            self.assertTrue(paper_to_dict(page.items[0])["summary"])
            self.assertEqual(len(store.papers_pending_figures()), 1)
            self.assertTrue(
                store.update_paper_figure(
                    page.items[0].external_id,
                    "https://arxiv.org/html/2605.00002v1/figure.png",
                    "Figure 1: RAG system overview",
                    image_data=b"fake-png-data",
                    image_mime_type="image/png",
                )
            )
            enriched_page = store.recommend_papers(page=1, page_size=10)
            self.assertEqual(enriched_page.items[0].image_url, "https://arxiv.org/html/2605.00002v1/figure.png")
            self.assertEqual(enriched_page.items[0].image_caption, "Figure 1: RAG system overview")
            payload = paper_to_dict(enriched_page.items[0])
            self.assertEqual(payload["image_source_url"], "https://arxiv.org/html/2605.00002v1/figure.png")
            self.assertEqual(payload["image_url"], "/v1/papers/arxiv%3A2605.00002v1/image")
            self.assertEqual(store.get_paper_image(page.items[0].external_id), (b"fake-png-data", "image/png"))
            self.assertEqual(len(store.papers_pending_figures()), 0)
            refresh_page = store.recommend_papers(
                page=1,
                page_size=10,
                excluded_ids=[page.items[0].external_id],
            )
            self.assertEqual(refresh_page.total_count, 0)

    def test_paper_ai_summary_is_cached_and_reset_on_abstract_change(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            papers = parse_arxiv_feed(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2605.00005v1</id>
                    <updated>2026-05-28T10:00:00Z</updated>
                    <published>2026-05-27T10:00:00Z</published>
                    <title>Agent Summaries</title>
                    <summary>First abstract.</summary>
                    <author><name>Alice</name></author>
                    <category term="cs.AI" />
                  </entry>
                </feed>
                """.encode()
            )
            store.upsert_papers(papers)
            external_id = papers[0].external_id
            self.assertTrue(store.update_paper_ai_summary(external_id, "Paper AI summary"))
            self.assertEqual(store.get_paper(external_id).ai_summary, "Paper AI summary")
            self.assertEqual(paper_to_dict(store.get_paper(external_id))["ai_summary"], "Paper AI summary")

            updated_papers = parse_arxiv_feed(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2605.00005v1</id>
                    <updated>2026-05-29T10:00:00Z</updated>
                    <published>2026-05-27T10:00:00Z</published>
                    <title>Agent Summaries</title>
                    <summary>Second abstract.</summary>
                    <author><name>Alice</name></author>
                    <category term="cs.AI" />
                  </entry>
                </feed>
                """.encode()
            )
            store.upsert_papers(updated_papers)
            self.assertIsNone(store.get_paper(external_id).ai_summary)

    def test_existing_remote_paper_image_is_pending_until_cached(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            papers = parse_arxiv_feed(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2605.00004v1</id>
                    <updated>2026-05-28T10:00:00Z</updated>
                    <published>2026-05-27T10:00:00Z</published>
                    <title>Cached Figures for Papers</title>
                    <summary>We cache figures in SQLite.</summary>
                    <author><name>Alice</name></author>
                    <category term="cs.AI" />
                  </entry>
                </feed>
                """.encode()
            )
            store.upsert_papers(papers)
            external_id = papers[0].external_id
            store.update_paper_figure(
                external_id,
                "https://arxiv.org/html/2605.00004v1/figure.png",
                "Figure 1: Cache overview",
            )
            self.assertEqual(len(store.papers_pending_figures()), 1)
            self.assertIsNone(paper_to_dict(store.recommend_papers(1, 1, use_keywords=False).items[0])["image_url"])

    def test_ai_frontier_returns_news_only(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = NewsStore(Path(temp_dir) / "topnews.db")
            source = SourceConfig(name="AI资讯", url="https://example.com/rss", kind="rss", category="科技")
            articles = parse_rss(
                """
                <rss><channel><item>
                    <title>人工智能大模型发布新版本</title>
                    <link>https://example.com/ai-news</link>
                    <description>新版本提升了多模态理解能力。</description>
                </item></channel></rss>
                """.encode(),
                source,
            )
            papers = parse_arxiv_feed(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2605.00003v1</id>
                    <updated>2026-05-28T10:00:00Z</updated>
                    <published>2026-05-27T10:00:00Z</published>
                    <title>Multimodal Agents for Reliable Reasoning</title>
                    <summary>We study reliable multimodal AI agents.</summary>
                    <author><name>Alice</name></author>
                    <category term="cs.AI" />
                  </entry>
                </feed>
                """.encode()
            )
            store.upsert_articles(articles)
            store.upsert_papers(papers)
            store.add_academic_keyword("unrelated-keyword")

            page = store.ai_frontier(page=1, page_size=10)
            self.assertTrue(any(isinstance(item, Article) for item in page.items))
            self.assertFalse(any(isinstance(item, Paper) for item in page.items))
            self.assertEqual(page.total_count, 1)

    def test_build_summary_strips_html_and_truncates(self):
        summary = build_summary("<p>这是第一句完整摘要。</p><p>" + "很长的内容" * 100 + "</p>", max_length=20)
        self.assertEqual(summary, "这是第一句完整摘要。")

    def test_load_config_reads_llm_environment(self):
        with patch.dict(
            "os.environ",
            {
                "TOPNEWS_LLM_BASE_URL": "https://llm.example.com/v1",
                "TOPNEWS_LLM_API_KEY": "test-key",
                "TOPNEWS_LLM_MODEL": "test-model",
                "TOPNEWS_LLM_TIMEOUT": "5",
            },
            clear=False,
        ):
            config = load_config()
        self.assertEqual(config.llm_base_url, "https://llm.example.com/v1")
        self.assertEqual(config.llm_api_key, "test-key")
        self.assertEqual(config.llm_model, "test-model")
        self.assertEqual(config.llm_timeout, 5)

    def test_parse_arxiv_primary_figure(self):
        body = """
        <html><body>
          <figure class="ltx_figure">
            <img src="2605.00001v1/small.png" width="120" height="80" alt="Refer to caption">
            <figcaption><span>Figure 1:</span> Small example.</figcaption>
          </figure>
          <figure class="ltx_figure">
            <img src="2605.00001v1/overview.png" width="640" height="360" alt="Refer to caption">
            <figcaption><span>Figure 2:</span> Model overview.</figcaption>
          </figure>
        </body></html>
        """.encode()
        figure = parse_arxiv_primary_figure(body, "https://arxiv.org/html/2605.00001v1")
        self.assertIsNotNone(figure)
        self.assertEqual(figure.image_url, "https://arxiv.org/html/2605.00001v1/overview.png")
        self.assertEqual(figure.caption, "Figure 2: Model overview.")
        self.assertEqual(build_arxiv_html_url("https://arxiv.org/abs/2605.00001v1"), "https://arxiv.org/html/2605.00001v1")

    def test_run_fetch_once_respects_skip_flags(self):
        aggregator = FakeAggregator()
        results = run_fetch_once(
            aggregator,
            FetchOptions(news_limit=7, papers_limit=11, paper_source="rss", figures_limit=13, figure_delay_seconds=2.5),
            skip_figures=True,
        )

        self.assertEqual(aggregator.calls, [("news", 7), ("papers", 11, "rss")])
        self.assertEqual([result.source for result in results], ["news", "papers"])

    def test_scheduler_builds_enabled_jobs(self):
        jobs = _build_jobs(
            FakeAggregator(),
            FetchOptions(news_limit=7, papers_limit=11, figures_limit=13),
            ScheduleOptions(
                news_interval_minutes=10,
                papers_interval_minutes=0,
                figures_interval_minutes=60,
                run_on_start=False,
            ),
            now=100.0,
        )

        self.assertEqual([job.name for job in jobs], ["news", "figures"])
        self.assertEqual([job.interval_seconds for job in jobs], [600, 3600])
        self.assertEqual([job.next_run for job in jobs], [700.0, 3700.0])

    def test_scheduler_papers_job_fetches_figures_after_papers(self):
        aggregator = FakeAggregator()
        jobs = _build_jobs(
            aggregator,
            FetchOptions(
                papers_limit=11,
                paper_source="rss",
                figures_limit=13,
                figure_delay_seconds=2.5,
                force_figures=True,
            ),
            ScheduleOptions(
                news_interval_minutes=0,
                papers_interval_minutes=180,
                figures_interval_minutes=0,
                run_on_start=True,
            ),
            now=100.0,
        )

        self.assertEqual([job.name for job in jobs], ["papers"])
        results = jobs[0].run()

        self.assertEqual(aggregator.calls, [("papers", 11, "rss"), ("figures", 13, 2.5, True)])
        self.assertEqual([result.source for result in results], ["papers", "figures"])

    def test_scheduler_delays_independent_figures_fallback_when_papers_run_at_start(self):
        jobs = _build_jobs(
            FakeAggregator(),
            FetchOptions(papers_limit=11, figures_limit=13),
            ScheduleOptions(
                news_interval_minutes=0,
                papers_interval_minutes=60,
                figures_interval_minutes=180,
                run_on_start=True,
            ),
            now=100.0,
        )

        self.assertEqual([job.name for job in jobs], ["papers", "figures"])
        self.assertEqual([job.run_at_start for job in jobs], [True, False])
        self.assertEqual([job.next_run for job in jobs], [100.0, 10900.0])


class FakeAggregator:
    def __init__(self):
        self.calls = []

    def ingest(self, limit_per_source=30):
        self.calls.append(("news", limit_per_source))
        return [IngestResult(source="news", ok=True, fetched=1, stored=1)]

    def ingest_papers(self, limit=30, source="auto"):
        self.calls.append(("papers", limit, source))
        return IngestResult(source="papers", ok=True, fetched=1, stored=1)

    def ingest_paper_figures(self, limit=10, delay_seconds=3.0, force=False):
        self.calls.append(("figures", limit, delay_seconds, force))
        return IngestResult(source="figures", ok=True, fetched=1, stored=1)


if __name__ == "__main__":
    unittest.main()
