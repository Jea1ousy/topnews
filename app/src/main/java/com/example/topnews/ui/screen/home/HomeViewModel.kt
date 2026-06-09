package com.example.topnews.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.topnews.data.repository.TopNewsBackendRepository
import com.example.topnews.data.repository.WeatherRepository
import com.example.topnews.domain.model.DeviceLocation
import com.example.topnews.domain.repository.NewsRepository
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val repository: NewsRepository = TopNewsBackendRepository()
    private val weatherRepository = WeatherRepository()
    private val pageSize = 20

    private val _uiState = MutableStateFlow(
        HomeUiState(
            feedsByCategory = mapOf(DEFAULT_CATEGORY to CategoryFeedState(isLoading = true))
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage(category = DEFAULT_CATEGORY, forceRefresh = false)
    }

    fun selectCategory(category: String) {
        val state = _uiState.value
        if (state.selectedCategory == category) return
        val cachedFeed = state.feedFor(category)
        val shouldLoad = cachedFeed.articles.isEmpty() && cachedFeed.error == null && !cachedFeed.isLoading

        _uiState.update {
            it.copy(selectedCategory = category)
        }
        if (shouldLoad) {
            loadFirstPage(category = category, forceRefresh = false)
        }
    }

    fun refresh() {
        loadFirstPage(category = _uiState.value.selectedCategory, forceRefresh = true)
    }

    fun loadWeather(location: DeviceLocation) {
        _uiState.update {
            it.copy(
                city = location.city,
                weather = "更新中"
            )
        }

        viewModelScope.launch {
            runCatching {
                weatherRepository.getCurrentWeather(city = location.city)
            }.onSuccess { weather ->
                _uiState.update {
                    it.copy(
                        city = weather.city,
                        temperature = weather.temperature,
                        weather = weather.weather
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        city = location.city,
                        weather = "天气更新失败"
                    )
                }
            }
        }
    }

    private fun loadFirstPage(category: String, forceRefresh: Boolean) {
        viewModelScope.launch {
            updateFeed(category) { feed ->
                feed.copy(
                    isLoading = feed.articles.isEmpty(),
                    isRefreshing = feed.articles.isNotEmpty(),
                    isLoadingMore = false,
                    error = null
                )
            }

            val feed = _uiState.value.feedFor(category)
            runCatching {
                repository.getTopNews(
                    page = 1,
                    pageSize = pageSize,
                    category = category,
                    forceRefresh = forceRefresh,
                    excludeIds = if (forceRefresh) feed.seenArticleIds.toList() else emptyList()
                )
            }
                .onSuccess { newsPage ->
                    updateFeed(category) { currentFeed ->
                        val keepCurrent = forceRefresh && currentFeed.articles.isNotEmpty() && newsPage.articles.isEmpty()
                        val nextArticles = if (keepCurrent) currentFeed.articles else newsPage.articles
                        currentFeed.copy(
                            articles = nextArticles,
                            seenArticleIds = (currentFeed.seenArticleIds + nextArticles.map { article -> article.id })
                                .toList()
                                .takeLast(300)
                                .toSet(),
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasMore = if (keepCurrent) currentFeed.hasMore else newsPage.hasMore,
                            currentPage = if (keepCurrent) currentFeed.currentPage else newsPage.page,
                            lastUpdatedText = if (keepCurrent) "暂无新内容 ${currentTimeText()}" else "刷新于 ${currentTimeText()}",
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    updateFeed(category) { currentFeed ->
                        currentFeed.copy(
                            articles = if (forceRefresh) currentFeed.articles else emptyList(),
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = throwable.toUserMessage()
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val category = state.selectedCategory
        val feed = state.feedFor(category)
        if (feed.isLoading || feed.isRefreshing || feed.isLoadingMore || !feed.hasMore) return

        viewModelScope.launch {
            val nextPage = feed.currentPage + 1
            updateFeed(category) {
                it.copy(isLoadingMore = true, error = null)
            }

            runCatching {
                repository.getTopNews(
                    page = nextPage,
                    pageSize = pageSize,
                    category = category,
                    forceRefresh = false,
                    excludeIds = emptyList()
                )
            }
                .onSuccess { nextPageResult ->
                    updateFeed(category) { currentFeed ->
                        currentFeed.copy(
                            articles = currentFeed.articles + nextPageResult.articles,
                            seenArticleIds = (currentFeed.seenArticleIds + nextPageResult.articles.map { article -> article.id })
                                .toList()
                                .takeLast(300)
                                .toSet(),
                            isLoadingMore = false,
                            hasMore = nextPageResult.hasMore,
                            currentPage = nextPageResult.page,
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    updateFeed(category) { currentFeed ->
                        currentFeed.copy(
                            isLoadingMore = false,
                            error = throwable.toUserMessage()
                        )
                    }
                }
        }
    }

    fun loadTopNews() {
        refresh()
    }

    private fun Throwable.toUserMessage(): String {
        return when (this) {
            is UnknownHostException -> "无法解析接口域名：${message.orEmpty()}"
            is IllegalArgumentException -> message ?: "接口配置不完整"
            is IllegalStateException -> message ?: "接口返回异常"
            else -> message ?: "新闻加载失败"
        }
    }

    private fun currentTimeText(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun updateFeed(
        category: String,
        transform: (CategoryFeedState) -> CategoryFeedState
    ) {
        _uiState.update { state ->
            state.copy(
                feedsByCategory = state.feedsByCategory + (category to transform(state.feedFor(category)))
            )
        }
    }

    companion object {
        private const val DEFAULT_CATEGORY = "推荐"
    }
}
