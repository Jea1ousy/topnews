package com.example.topnews.domain.repository

import com.example.topnews.domain.model.NewsPage

interface NewsRepository {
    suspend fun getTopNews(
        page: Int,
        pageSize: Int,
        category: String = "推荐",
        forceRefresh: Boolean = false,
        excludeIds: List<String> = emptyList()
    ): NewsPage
}
