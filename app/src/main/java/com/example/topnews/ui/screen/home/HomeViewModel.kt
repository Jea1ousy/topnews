package com.example.topnews.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.topnews.data.repository.TopNewsBackendRepository
import com.example.topnews.data.repository.WeatherRepository
import com.example.topnews.domain.model.DeviceLocation
import com.example.topnews.domain.model.NewsArticle
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

class HomeViewModel : ViewModel() {
    private val repository = TopNewsBackendRepository()
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
        loadAcademicKeywords()
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
        if (category == ACADEMIC_CATEGORY && state.academicKeywords.isEmpty() && !state.isLoadingAcademicKeywords) {
            loadAcademicKeywords()
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

    fun summarizeArticle(article: NewsArticle) {
        val cachedSummary = article.aiSummary
            ?: _uiState.value.aiSummaries[article.id]?.summary
        if (!cachedSummary.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    aiSummaries = it.aiSummaries + (article.id to AiSummaryState(summary = cachedSummary))
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    aiSummaries = it.aiSummaries + (article.id to AiSummaryState(isLoading = true))
                )
            }

            runCatching {
                repository.summarizeArticle(article)
            }
                .onSuccess { summary ->
                    _uiState.update { state ->
                        state.copy(
                            feedsByCategory = state.feedsByCategory.mapValues { (_, feed) ->
                                feed.copy(
                                    articles = feed.articles.map { item ->
                                        if (item.id == article.id) item.copy(aiSummary = summary) else item
                                    }
                                )
                            },
                            aiSummaries = state.aiSummaries + (article.id to AiSummaryState(summary = summary))
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            aiSummaries = it.aiSummaries + (
                                article.id to AiSummaryState(error = throwable.toUserMessage())
                            )
                        )
                    }
                }
        }
    }

    fun loadAcademicKeywords() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingAcademicKeywords = true, academicKeywordError = null)
            }

            runCatching {
                repository.getAcademicKeywords()
            }
                .onSuccess { keywords ->
                    _uiState.update {
                        it.copy(
                            academicKeywords = keywords,
                            isLoadingAcademicKeywords = false,
                            academicKeywordError = null
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoadingAcademicKeywords = false,
                            academicKeywordError = throwable.toUserMessage()
                        )
                    }
                }
        }
    }

    fun addAcademicKeyword(rawKeyword: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingAcademicKeywords = true, academicKeywordError = null)
            }

            runCatching {
                repository.addAcademicKeyword(rawKeyword)
            }
                .onSuccess { keyword ->
                    _uiState.update {
                        it.copy(
                            academicKeywords = (it.academicKeywords.filterNot { item -> item.id == keyword.id } + keyword)
                                .sortedBy { item -> item.id },
                            isLoadingAcademicKeywords = false,
                            academicKeywordError = null
                        )
                    }
                    loadFirstPage(category = ACADEMIC_CATEGORY, forceRefresh = true)
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoadingAcademicKeywords = false,
                            academicKeywordError = throwable.toUserMessage()
                        )
                    }
                }
        }
    }

    fun deleteAcademicKeyword(id: Int) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingAcademicKeywords = true, academicKeywordError = null)
            }

            runCatching {
                repository.deleteAcademicKeyword(id)
            }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            academicKeywords = it.academicKeywords.filterNot { keyword -> keyword.id == id },
                            isLoadingAcademicKeywords = false,
                            academicKeywordError = null
                        )
                    }
                    loadFirstPage(category = ACADEMIC_CATEGORY, forceRefresh = true)
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoadingAcademicKeywords = false,
                            academicKeywordError = throwable.toUserMessage()
                        )
                    }
                }
        }
    }

    private fun Throwable.toUserMessage(): String {
        return when (this) {
            is UnknownHostException -> "无法解析接口域名：${message.orEmpty()}"
            is HttpException -> when (code()) {
                503 -> "后端还没有配置大模型服务"
                502 -> "AI总结服务暂时不可用"
                404 -> "没有找到这篇内容"
                else -> "接口请求失败：HTTP ${code()}"
            }
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
        private const val ACADEMIC_CATEGORY = "学术推荐"
    }
}
