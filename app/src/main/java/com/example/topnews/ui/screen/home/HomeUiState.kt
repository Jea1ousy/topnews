package com.example.topnews.ui.screen.home

import com.example.topnews.domain.model.AcademicKeyword
import com.example.topnews.domain.model.NewsArticle


data class HomeUiState(
    val temperature: String = "14°",
    val city: String = "北京",
    val weather: String = "多云",
    val selectedCategory: String = "推荐",
    val categories: List<String> = listOf("推荐", "AI前沿", "学术推荐", "国内", "财经"),
    val feedsByCategory: Map<String, CategoryFeedState> = emptyMap(),
    val aiSummaries: Map<String, AiSummaryState> = emptyMap(),
    val academicKeywords: List<AcademicKeyword> = emptyList(),
    val isLoadingAcademicKeywords: Boolean = false,
    val academicKeywordError: String? = null
) {
    val selectedFeed: CategoryFeedState
        get() = feedFor(selectedCategory)

    val articles: List<NewsArticle>
        get() = selectedFeed.articles
    val isLoading: Boolean
        get() = selectedFeed.isLoading
    val error: String?
        get() = selectedFeed.error
    val isRefreshing: Boolean
        get() = selectedFeed.isRefreshing
    val isLoadingMore: Boolean
        get() = selectedFeed.isLoadingMore
    val hasMore: Boolean
        get() = selectedFeed.hasMore
    val currentPage: Int
        get() = selectedFeed.currentPage
    val lastUpdatedText: String
        get() = selectedFeed.lastUpdatedText
    val seenArticleIds: Set<String>
        get() = selectedFeed.seenArticleIds

    fun feedFor(category: String): CategoryFeedState {
        return feedsByCategory[category] ?: CategoryFeedState()
    }
}

data class CategoryFeedState(
    val articles: List<NewsArticle> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val lastUpdatedText: String = "",
    val seenArticleIds: Set<String> = emptySet()
)

data class AiSummaryState(
    val summary: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
