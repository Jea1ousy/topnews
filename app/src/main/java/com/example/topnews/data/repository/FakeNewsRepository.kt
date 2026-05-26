package com.example.topnews.data.repository

import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.domain.repository.NewsRepository
import kotlinx.coroutines.delay

class FakeNewsRepository : NewsRepository {
    override suspend fun getTopNews(): List<NewsArticle> {
        delay(300)

        return listOf(
            NewsArticle(
                id = "1",
                title = "习近平主席关于中非合作重要论述",
                source = "置顶 海外网",
                commentCount = 30,
                timeText = "刚刚",
                isTop = true
            ),
            NewsArticle(
                id = "2",
                title = "努力为党和人民争取更大光荣",
                source = "置顶 海外网",
                commentCount = 30,
                timeText = "3分钟前",
                isTop = true
            ),
            NewsArticle(
                id = "3",
                title = "这档社交观察类综艺火了，桃花坞是如何做到的",
                source = "信息来源",
                commentCount = 128,
                timeText = "12分钟前",
                imageUrl = "speaker"
            ),
            NewsArticle(
                id = "4",
                title = "我国已有近320公里高铁常态化按350公里高标运营",
                source = "央视新闻",
                commentCount = 82,
                timeText = "20分钟前",
                imageUrl = "train",
                videoDuration = "02:54"
            ),
            NewsArticle(
                id = "5",
                title = "全网笑出鹅叫声！腾讯被骗后悬赏1000瓶老干妈！还发了个视频……",
                source = "热点精选",
                commentCount = 312,
                timeText = "30分钟前",
                imageUrl = "topic"
            ),
            NewsArticle(
                id = "6",
                title = "新能源汽车产业链迎来新变化，多地加快智能充电设施建设",
                source = "经济观察",
                commentCount = 65,
                timeText = "42分钟前"
            )
        )
    }
}
