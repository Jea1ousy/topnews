package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.topnews.domain.model.NewsArticle

enum class NewsCardType {
    HERO_IMAGE,
    THREE_IMAGE,
    THUMBNAIL,
    PLAIN_TEXT
}

fun getNewsCardType(article: NewsArticle, index: Int = -1): NewsCardType {
    val images = validArticleImages(article)
    if ((article.isTop || index == 0) && images.isNotEmpty()) {
        return NewsCardType.HERO_IMAGE
    }
    if (images.size >= 3) {
        return NewsCardType.THREE_IMAGE
    }
    if (images.isNotEmpty()) {
        return NewsCardType.THUMBNAIL
    }
    return NewsCardType.PLAIN_TEXT
}

fun isFlashSource(source: String): Boolean {
    return source.contains("电报") || source.contains("快讯")
}

internal fun validArticleImages(article: NewsArticle): List<String> {
    val list = if (article.imageUrls.isNotEmpty()) {
        article.imageUrls
    } else {
        listOfNotNull(article.imageUrl)
    }
    return list.filterNot(::isJunkArticleImageUrl).distinct()
}

@Composable
fun NewsListItem(
    article: NewsArticle,
    onClick: (NewsArticle) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = -1
) {
    val cardType = remember(article, index) { getNewsCardType(article, index) }
    val validImages = remember(article) { validArticleImages(article) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(article) }
    ) {
        when (cardType) {
            NewsCardType.HERO_IMAGE -> {
                HeroImageCardContent(
                    article = article,
                    imageUrl = validImages.first()
                )
            }
            NewsCardType.THREE_IMAGE -> {
                ThreeImageCardContent(
                    article = article,
                    images = validImages.take(3)
                )
            }
            NewsCardType.THUMBNAIL -> {
                ThumbnailCardContent(
                    article = article,
                    imageUrl = validImages.first()
                )
            }
            NewsCardType.PLAIN_TEXT -> {
                PlainTextCardContent(
                    article = article
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** 宽幅大图卡（置顶头条或首位焦点） */
@Composable
private fun HeroImageCardContent(
    article: NewsArticle,
    imageUrl: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = article.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val reqUrl = remember(imageUrl) { thumbnailRequestUrl(imageUrl, THUMBNAIL_HERO_WIDTH_PX) }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(reqUrl)
                    .size(THUMBNAIL_HERO_WIDTH_PX, THUMBNAIL_HERO_HEIGHT_PX)
                    .crossfade(true)
                    .build(),
                contentDescription = article.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (article.videoDuration != null) {
                VideoOverlay(duration = article.videoDuration)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        ArticleMeta(article = article)
    }
}

/** 三图卡（3 张及以上配图平铺） */
@Composable
private fun ThreeImageCardContent(
    article: NewsArticle,
    images: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = article.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            images.take(3).forEach { imgUrl ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1.33f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val reqUrl = remember(imgUrl) { thumbnailRequestUrl(imgUrl, THUMBNAIL_DECODE_WIDTH_PX) }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(reqUrl)
                            .size(THUMBNAIL_DECODE_WIDTH_PX, THUMBNAIL_DECODE_HEIGHT_PX)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        ArticleMeta(article = article)
    }
}

/** 经典左文右小图卡 */
@Composable
private fun ThumbnailCardContent(
    article: NewsArticle,
    imageUrl: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            ArticleMeta(article = article)
        }
        Spacer(modifier = Modifier.width(14.dp))
        NewsThumbnail(article = article, imageUrl = imageUrl)
    }
}

/** 经典纯文卡（普通无图与快讯短讯均遵循此统一布局） */
@Composable
private fun PlainTextCardContent(
    article: NewsArticle
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = article.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        ArticleMeta(article = article)
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
        } else if (isFlashSource(article.source)) {
            Text(
                text = "快讯",
                color = MaterialTheme.colorScheme.error,
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
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val thumbnailUrl = remember(imageUrl) { thumbnailRequestUrl(imageUrl) }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(thumbnailUrl)
                .size(THUMBNAIL_DECODE_WIDTH_PX, THUMBNAIL_DECODE_HEIGHT_PX)
                .crossfade(true)
                .build(),
            contentDescription = article.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (article.videoDuration != null) {
            VideoOverlay(duration = article.videoDuration)
        }
    }
}

@Composable
private fun BoxScope.VideoOverlay(duration: String) {
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
        text = duration,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp),
        color = Color.White,
        fontSize = 12.sp
    )
}

private fun thumbnailRequestUrl(imageUrl: String, width: Int = THUMBNAIL_DECODE_WIDTH_PX): String {
    if (!imageUrl.contains("/image")) return imageUrl
    val separator = if (imageUrl.contains('?')) "&" else "?"
    return "$imageUrl${separator}w=$width"
}

private fun isJunkArticleImageUrl(url: String): Boolean {
    val lowered = url.lowercase()
    return JUNK_IMAGE_URL_TOKENS.any { it in lowered }
}

private const val THUMBNAIL_DECODE_WIDTH_PX = 336
private const val THUMBNAIL_DECODE_HEIGHT_PX = 228
private const val THUMBNAIL_HERO_WIDTH_PX = 720
private const val THUMBNAIL_HERO_HEIGHT_PX = 405

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
