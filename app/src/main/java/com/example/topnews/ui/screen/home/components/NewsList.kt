package com.example.topnews.ui.screen.home.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.ui.theme.BrandYellow

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

            val pullToRefreshState = rememberPullToRefreshState()
            val pullFraction = pullToRefreshState.distanceFraction.coerceIn(0f, 1.18f)
            val contentOffset by animateDpAsState(
                targetValue = when {
                    isRefreshing -> RefreshSettleOffset
                    pullFraction > 0f -> RefreshSettleOffset * pullFraction
                    else -> 0.dp
                },
                animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
                label = "news-list-refresh-offset"
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize(),
                state = pullToRefreshState,
                indicator = {
                    TopNewsRefreshIndicator(
                        isRefreshing = isRefreshing,
                        pullFraction = pullFraction,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(x = 0, y = contentOffset.roundToPx()) },
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

@Composable
private fun TopNewsRefreshIndicator(
    isRefreshing: Boolean,
    pullFraction: Float,
    modifier: Modifier = Modifier
) {
    val visibleProgress = when {
        isRefreshing -> 1f
        else -> pullFraction
    }.coerceIn(0f, 1f)

    if (visibleProgress <= 0.01f && !isRefreshing) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RefreshSettleOffset)
            .graphicsLayer {
                alpha = (0.18f + visibleProgress * 0.82f).coerceIn(0f, 1f)
                translationY = (-10.dp.toPx()) + (10.dp.toPx() * visibleProgress)
                scaleX = 0.92f + visibleProgress * 0.08f
                scaleY = 0.92f + visibleProgress * 0.08f
            },
        contentAlignment = Alignment.Center
    ) {
        FlowingTopNewsWord(
            isRefreshing = isRefreshing,
            pullFraction = visibleProgress
        )
    }
}

@Composable
private fun FlowingTopNewsWord(
    isRefreshing: Boolean,
    pullFraction: Float,
    modifier: Modifier = Modifier
) {
    var textWidthPx by remember { mutableIntStateOf(1) }
    val primary = MaterialTheme.colorScheme.primary
    val quietRed = primary.copy(alpha = 0.28f + pullFraction * 0.24f)
    val brandYellow = BrandYellow.copy(alpha = 0.72f + pullFraction * 0.28f)
    val highlight = Color.White.copy(alpha = 0.92f)
    val transition = rememberInfiniteTransition(label = "topnews-refresh-flow")
    val shimmer by transition.animateFloat(
        initialValue = -0.65f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isRefreshing) 980 else 1500,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "topnews-refresh-shimmer"
    )
    val staticShift = (pullFraction - 0.5f) * 0.6f
    val flowPosition = if (isRefreshing) shimmer else staticShift
    val width = textWidthPx.toFloat().coerceAtLeast(1f)
    val brush = Brush.linearGradient(
        colors = listOf(quietRed, brandYellow, highlight, brandYellow, quietRed),
        start = Offset(width * (flowPosition - 0.45f), 0f),
        end = Offset(width * (flowPosition + 0.45f), 0f)
    )

    Text(
        text = "topnews",
        modifier = modifier.onSizeChanged { textWidthPx = it.width.coerceAtLeast(1) },
        style = TextStyle(
            brush = brush,
            fontSize = 23.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.sp
        )
    )
}

private val RefreshSettleOffset = 64.dp
