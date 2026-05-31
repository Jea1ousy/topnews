package com.example.topnews.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.topnews.ui.components.TopNewsBottomBar
import com.example.topnews.ui.screen.home.components.CategoryTabs
import com.example.topnews.ui.screen.home.components.HomeHeader
import com.example.topnews.ui.screen.home.components.NewsList
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(
        initialPage = uiState.categories.indexOf(uiState.selectedCategory).coerceAtLeast(0),
        pageCount = { uiState.categories.size }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.selectedCategory, uiState.categories) {
        val page = uiState.categories.indexOf(uiState.selectedCategory)
        if (page >= 0 && page != pagerState.currentPage) {
            pagerState.animateScrollToPage(page)
        }
    }

    LaunchedEffect(pagerState.currentPage, uiState.categories) {
        uiState.categories.getOrNull(pagerState.currentPage)?.let { category ->
            viewModel.selectCategory(category)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White,
        bottomBar = { TopNewsBottomBar(selectedTab = "搜索") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            HomeHeader(
                uiState = uiState,
                onRefresh = viewModel::refresh
            )
            CategoryTabs(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { category ->
                    viewModel.selectCategory(category)
                    val page = uiState.categories.indexOf(category)
                    if (page >= 0) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    }
                }
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
