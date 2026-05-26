package com.example.topnews.domain.model

data class NewsPage(
    val articles: List<NewsArticle>,
    val page: Int,
    val totalPage: Int,
    val totalCount: Int
) {
    val hasMore: Boolean
        get() = page < totalPage
}
