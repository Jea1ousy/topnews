package com.example.topnews.ui.screen.home

import com.example.topnews.domain.model.NewsArticle


data class HomeUiState(
    val temperature: String = "14°",
    val city: String = "北京",
    val weather: String = "多云",
    val searchText: String = "习近平主席重要论述 | 乌拉圭队或重",
    val selectedCategory: String = "推荐",
    val categories: List<String> = listOf("关注", "推荐", "热榜", "新时代", "小说", "视频"),
    val articles: List<NewsArticle> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
