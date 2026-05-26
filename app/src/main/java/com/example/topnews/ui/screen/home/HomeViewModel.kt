package com.example.topnews.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.topnews.data.repository.AliNewsRepository
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
    private val repository: NewsRepository = AliNewsRepository()
    private val pageSize = 20

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.articles.isEmpty(),
                    isRefreshing = it.articles.isNotEmpty(),
                    error = null
                )
            }

            runCatching { repository.getTopNews(page = 1, pageSize = pageSize) }
                .onSuccess { newsPage ->
                    _uiState.update {
                        it.copy(
                            articles = newsPage.articles,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasMore = newsPage.hasMore,
                            currentPage = newsPage.page,
                            lastUpdatedText = "刷新于 ${currentTimeText()}",
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

            runCatching { repository.getTopNews(page = nextPage, pageSize = pageSize) }
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
