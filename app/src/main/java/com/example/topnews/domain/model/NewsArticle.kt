package com.example.topnews.domain.model

data class NewsArticle(
    val id: String,
    val title: String,
    val source: String,
    val commentCount: Int,
    val timeText: String,
    val imageUrl: String? = null,
    val videoDuration: String? = null,
    val isTop: Boolean = false
)
