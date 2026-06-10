package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.topnews.domain.model.NewsArticle

@Composable
fun NewsListItem(
    article: NewsArticle,
    onClick: (NewsArticle) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(article) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (article.isTop) 5.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (article.isTop) 17.sp else 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                ArticleMeta(article = article)
            }

            val thumbnailUrl = article.imageUrl?.takeUnless(::isJunkArticleImageUrl)
            if (thumbnailUrl != null) {
                Spacer(modifier = Modifier.width(14.dp))
                NewsThumbnail(article = article, imageUrl = thumbnailUrl)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun ArticleMeta(article: NewsArticle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (article.isTop) {
            Text(
                text = "置顶",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        SourceAvatar(source = article.source, size = 18.dp)
        Spacer(modifier = Modifier.width(5.dp))
        val commentText = if (article.commentCount > 0) "${article.commentCount}评论  " else ""
        val sourceText = compactSourceName(article.source)
        Text(
            text = "$sourceText  $commentText${article.timeText}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NewsThumbnail(article: NewsArticle, imageUrl: String) {
    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 76.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = article.title,
            modifier = Modifier
                .fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (article.videoDuration != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ">",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = article.videoDuration,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp),
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

private fun isJunkArticleImageUrl(url: String): Boolean {
    val lowered = url.lowercase()
    return JUNK_IMAGE_URL_TOKENS.any { it in lowered }
}

private val JUNK_IMAGE_URL_TOKENS = listOf(
    "avatar",
    "author",
    "head",
    "portrait",
    "profile",
    "touxiang",
    "qrcode",
    "qr-code",
    "qr_",
    "_qr",
    "ewm",
    "weixin",
    "wechat",
    "wxcode",
    "appcode",
    "placeholder",
    "default",
    "blank",
    "empty",
    "noimage"
)
