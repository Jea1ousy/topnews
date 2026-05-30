package com.example.topnews.ui.screen.home

import com.example.topnews.domain.model.NewsArticle


data class HomeUiState(
    val temperature: String = "14°",
    val city: String = "北京",
    val weather: String = "多云",
    val searchText: String = "AI前沿 | 学术推荐 | 新闻聚合",
    val selectedCategory: String = "推荐",
    val categories: List<String> = listOf("推荐", "AI前沿", "学术推荐", "国内", "国际", "科技", "财经", "体育", "娱乐"),
    val articles: List<NewsArticle> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val lastUpdatedText: String = ""
)
