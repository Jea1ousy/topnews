package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.example.topnews.domain.model.NewsArticle

@Composable
fun NewsList(
    articles: List<NewsArticle>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isLoading && articles.isEmpty() -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "加载中...", color = Color(0xFF777777))
            }
        }

        error != null && articles.isEmpty() -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "$error，点击重试",
                    modifier = Modifier
                        .clickable { onRetry() }
                        .padding(24.dp),
                    color = Color(0xFFFF3E49)
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top
            ) {
                items(
                    items = articles,
                    key = { article -> article.id }
                ) { article ->
                    NewsListItem(article = article)
                }
            }
        }
    }
}
