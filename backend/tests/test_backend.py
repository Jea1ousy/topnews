import tempfile
import unittest
from pathlib import Path

from backend.topnews_backend.academic import build_arxiv_rss_url, parse_arxiv_feed, parse_arxiv_rss, parse_keyword_rule
from backend.topnews_backend.classifier import classify
from backend.topnews_backend.config import SourceConfig
from backend.topnews_backend.fetchers import parse_portal, parse_rss
from backend.topnews_backend.storage import NewsStore


class BackendTest(unittest.TestCase):
    def test_classify_domestic_tech(self):
        result = classify("中国人工智能芯片取得新进展", "北京发布相关政策")
        self.assertEqual(result.region, "境内")
        self.assertEqual(result.category, "科技")

    def test_parse_rss(self):
        source = SourceConfig(name="测试RSS", url="https://example.com/rss", kind="rss")
        body = """
        <rss><channel><item>
            <title>中国经济政策发布</title>
            <link>https://example.com/a</link>
            <description><![CDATA[国务院发布新政策 <img src="/cover.jpg" />]]></description>
            <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
        </item></channel></rss>
        """.encode()
        articles = parse_rss(body, source)
        self.assertEqual(len(articles), 1)
        self.assertEqual(articles[0].category, "国内")
        self.assertEqual(articles[0].region, "境内")
        self.assertEqual(articles[0].image_url, "https://example.com/cover.jpg")

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
            refresh_page = store.recommend(
                page=1,
                page_size=10,
                category="科技",
                excluded_ids=[page.items[0].external_id],
            )
            self.assertEqual(refresh_page.total_count, 1)
            self.assertNotEqual(refresh_page.items[0].external_id, page.items[0].external_id)

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
            refresh_page = store.recommend_papers(
                page=1,
                page_size=10,
                excluded_ids=[page.items[0].external_id],
            )
            self.assertEqual(refresh_page.total_count, 0)


if __name__ == "__main__":
    unittest.main()
