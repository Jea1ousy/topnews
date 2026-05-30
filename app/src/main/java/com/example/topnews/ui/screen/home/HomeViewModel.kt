package com.example.topnews.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.topnews.data.repository.TopNewsBackendRepository
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
    private val pageSize = 20

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage(forceRefresh = false)
    }

    fun selectCategory(category: String) {
        if (_uiState.value.selectedCategory == category) return
        _uiState.update {
            it.copy(
                selectedCategory = category,
                articles = emptyList(),
                currentPage = 1,
                hasMore = true,
                error = null
            )
        }
        loadFirstPage(forceRefresh = false)
    }

    fun refresh() {
        loadFirstPage(forceRefresh = true)
    }

    private fun loadFirstPage(forceRefresh: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.articles.isEmpty(),
                    isRefreshing = it.articles.isNotEmpty(),
                    error = null
                )
            }

            val category = _uiState.value.selectedCategory
            runCatching {
                repository.getTopNews(
                    page = 1,
                    pageSize = pageSize,
                    category = category,
                    forceRefresh = forceRefresh,
                    excludeIds = if (forceRefresh) _uiState.value.articles.map { it.id } else emptyList()
                )
            }
                .onSuccess { newsPage ->
                    _uiState.update {
                        val keepCurrent = forceRefresh && it.articles.isNotEmpty() && newsPage.articles.isEmpty()
                        it.copy(
                            articles = if (keepCurrent) it.articles else newsPage.articles,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasMore = if (keepCurrent) it.hasMore else newsPage.hasMore,
                            currentPage = if (keepCurrent) it.currentPage else newsPage.page,
                            lastUpdatedText = if (keepCurrent) "暂无新内容 ${currentTimeText()}" else "刷新于 ${currentTimeText()}",
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
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
        if (state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMore) return

        viewModelScope.launch {
            val nextPage = _uiState.value.currentPage + 1
            _uiState.update { it.copy(isLoadingMore = true, error = null) }

            val category = _uiState.value.selectedCategory
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
                    _uiState.update {
                        it.copy(
                            articles = it.articles + nextPageResult.articles,
                            isLoadingMore = false,
                            hasMore = nextPageResult.hasMore,
                            currentPage = nextPageResult.page,
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
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
}
