package com.example.topnews.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.topnews.ui.components.TopNewsBottomBar
import com.example.topnews.ui.screen.home.components.CategoryTabs
import com.example.topnews.ui.screen.home.components.HomeHeader
import com.example.topnews.ui.screen.home.components.NewsList

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White,
        bottomBar = { TopNewsBottomBar(selectedTab = "搜索") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(innerPadding)
        ) {
            HomeHeader(
                uiState = uiState,
                onRefresh = viewModel::refresh
            )
            CategoryTabs(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = viewModel::selectCategory
            )
            NewsList(
                articles = uiState.articles,
                isLoading = uiState.isLoading,
                isLoadingMore = uiState.isLoadingMore,
                hasMore = uiState.hasMore,
                error = uiState.error,
                lastUpdatedText = uiState.lastUpdatedText,
                onRetry = viewModel::refresh,
                onLoadMore = viewModel::loadMore,
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
