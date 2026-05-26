package com.example.topnews.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.topnews.data.repository.AliNewsRepository
import com.example.topnews.domain.repository.NewsRepository
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val repository: NewsRepository = AliNewsRepository()

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadTopNews()
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun loadTopNews() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatching { repository.getTopNews() }
                .onSuccess { articles ->
                    _uiState.update {
                        it.copy(
                            articles = articles,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toUserMessage()
                        )
                    }
                }
        }
    }

    private fun Throwable.toUserMessage(): String {
        return when (this) {
            is UnknownHostException -> "无法解析接口域名：${message.orEmpty()}"
            is IllegalArgumentException -> message ?: "接口配置不完整"
            is IllegalStateException -> message ?: "接口返回异常"
            else -> message ?: "新闻加载失败"
        }
    }
}
