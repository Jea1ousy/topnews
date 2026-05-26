package com.example.topnews.domain.model

data class NewsArticle(
    val id: String,
    val title: String,
    val source: String,
    val commentCount: Int,
    val timeText: String,
    val link: String = "",
    val description: String = "",
    val content: String = "",
    val html: String = "",
    val channelId: String = "",
    val channelName: String = "",
    val imageUrl: String? = null,
    val videoDuration: String? = null,
    val isTop: Boolean = false
)
