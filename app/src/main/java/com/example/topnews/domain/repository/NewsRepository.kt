package com.example.topnews.domain.repository

import com.example.topnews.domain.model.NewsPage

interface NewsRepository {
    suspend fun getTopNews(
        page: Int,
        pageSize: Int
    ): NewsPage
}
