package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.topnews.domain.model.NewsArticle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsList(
    articles: List<NewsArticle>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    lastUpdatedText: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onArticleClick: (NewsArticle) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isLoading && articles.isEmpty() -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        error != null && articles.isEmpty() -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "$error，点击重试",
                    modifier = Modifier
                        .clickable { onRetry() }
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        else -> {
            val listState = rememberLazyListState()
            val shouldLoadMore by remember {
                derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val totalItemsCount = layoutInfo.totalItemsCount

                    totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 3
                }
            }

            LaunchedEffect(shouldLoadMore, articles.size, isLoadingMore, isRefreshing, hasMore) {
                if (shouldLoadMore && !isLoadingMore && !isRefreshing && hasMore) {
                    onLoadMore()
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Top
                ) {
                    if (isLoading) {
                        item(key = "loading") {
                            Text(
                                text = "正在刷新...",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (lastUpdatedText.isNotBlank()) {
                        item(key = "last-updated") {
                            Text(
                                text = lastUpdatedText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (error != null) {
                        item(key = "error") {
                            Text(
                                text = "$error，点击重试",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRetry() }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }

                    items(
                        items = articles,
                        key = { article -> article.id }
                    ) { article ->
                        NewsListItem(
                            article = article,
                            onClick = onArticleClick
                        )
                    }

                    item(key = "load-more") {
                        val footerText = when {
                            isLoadingMore -> "正在加载更多..."
                            hasMore -> "上拉加载更多"
                            else -> "没有更多了"
                        }
                        Text(
                            text = footerText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
