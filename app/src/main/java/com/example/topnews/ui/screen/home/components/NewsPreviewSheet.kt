package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
fun NewsPreviewSheet(
    article: NewsArticle,
    isExpanded: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isExpanded) {
        FullArticleDetail(
            article = article,
            onClose = onClose,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        SheetHeader(article = article, isExpanded = isExpanded)
        Spacer(modifier = Modifier.height(14.dp))

        if (article.imageUrl != null) {
            PreviewImage(article = article)
            Spacer(modifier = Modifier.height(18.dp))
        }

        val body = article.readableBody()
        Text(
            text = body,
            color = Color(0xFF333333),
            fontSize = if (isExpanded) 17.sp else 16.sp,
            lineHeight = if (isExpanded) 28.sp else 25.sp,
            maxLines = if (isExpanded) Int.MAX_VALUE else 7,
            overflow = TextOverflow.Ellipsis
        )

        if (isExpanded && article.link.isNotBlank()) {
            Spacer(modifier = Modifier.height(22.dp))
            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFEDEDED))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = article.link,
                color = Color(0xFF777777),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun FullArticleDetail(
    article: NewsArticle,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
    ) {
        DetailTopBar(onClose = onClose)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp, bottom = 18.dp)
        ) {
            SourceRow(article = article)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = article.title,
                color = Color(0xFF111111),
                fontSize = 26.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Medium
            )
            if (article.imageUrl != null) {
                Spacer(modifier = Modifier.height(24.dp))
                PreviewImage(article = article)
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = article.readableBody(),
                color = Color(0xFF111111),
                fontSize = 23.sp,
                lineHeight = 39.sp
            )
        }

        DetailActionBar(article = article)
    }
}

@Composable
private fun DetailTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "<",
            color = Color(0xFF111111),
            fontSize = 42.sp,
            lineHeight = 42.sp,
            modifier = Modifier.clickable { onClose() }
        )
        Spacer(modifier = Modifier.width(18.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF1F1F3))
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "搜你想看的",
                color = Color(0xFF777777),
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = "听",
            color = Color(0xFF111111),
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = "...",
            color = Color(0xFF111111),
            fontSize = 30.sp,
            lineHeight = 30.sp
        )
    }
}

@Composable
private fun SourceRow(article: NewsArticle) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFFFECEE)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = article.source.firstOrNull()?.toString() ?: "新",
                color = Color(0xFFFF3E49),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.source,
                color = Color(0xFF111111),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = article.timeText,
                color = Color(0xFF999999),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF7F7F7))
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "关注",
                color = Color(0xFF111111),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DetailActionBar(article: NewsArticle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(72.dp)
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionItem(icon = "↗", label = "分享")
        ActionItem(icon = "□", label = article.commentCount.takeIf { it > 0 }?.toString() ?: "评论")
        ActionItem(icon = "♡", label = "267")
        ActionItem(icon = "☆", label = "75")
    }
}

@Composable
private fun ActionItem(
    icon: String,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = icon,
            color = Color(0xFF111111),
            fontSize = 31.sp,
            lineHeight = 31.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = Color(0xFF111111),
            fontSize = 18.sp
        )
    }
}

@Composable
private fun SheetHeader(
    article: NewsArticle,
    isExpanded: Boolean
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = article.source,
                color = Color(0xFFFF3E49),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = article.timeText,
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = article.title,
            color = Color(0xFF202020),
            fontSize = if (isExpanded) 24.sp else 21.sp,
            lineHeight = if (isExpanded) 32.sp else 29.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PreviewImage(article: NewsArticle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFEDEDED))
    ) {
        AsyncImage(
            model = article.imageUrl,
            contentDescription = article.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (article.videoDuration != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ">",
                    color = Color(0xFFFF3E49),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun NewsArticle.readableBody(): String {
    return listOf(content, description)
        .firstOrNull { it.isNotBlank() }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?: "暂无更多正文内容。"
}
