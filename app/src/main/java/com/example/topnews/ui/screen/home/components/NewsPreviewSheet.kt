package com.example.topnews.ui.screen.home.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.ui.theme.TopnewsTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun NewsPreviewOverlay(
    article: NewsArticle,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val previewTopPx = fullHeightPx * PREVIEW_TOP_FRACTION
        val expandThresholdPx = fullHeightPx * EXPAND_THRESHOLD_FRACTION
        val dismissThresholdPx = fullHeightPx * DISMISS_THRESHOLD_FRACTION
        val scope = rememberCoroutineScope()
        var cardOffsetPx by remember(article.id, fullHeightPx) {
            mutableFloatStateOf(fullHeightPx)
        }
        var isFullDetail by remember(article.id) { mutableStateOf(false) }
        var isImagePreviewOpen by remember(article.id) { mutableStateOf(false) }

        suspend fun animateCardTo(targetOffsetPx: Float) {
            animate(
                initialValue = cardOffsetPx,
                targetValue = targetOffsetPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ ->
                cardOffsetPx = value
            }
        }

        val dismissOverlay: () -> Unit = {
            scope.launch {
                animateCardTo(fullHeightPx)
                onClose()
            }
        }

        LaunchedEffect(article.id, fullHeightPx) {
            cardOffsetPx = fullHeightPx
            animateCardTo(previewTopPx)
        }

        BackHandler {
            if (isImagePreviewOpen) {
                isImagePreviewOpen = false
            } else {
                dismissOverlay()
            }
        }

        val scrimAlpha = ((fullHeightPx - cardOffsetPx) / fullHeightPx)
            .coerceIn(0f, 1f) * 0.34f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .pointerInput(article.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
        )

        val cornerRadius = (
            22f * (cardOffsetPx / previewTopPx).coerceIn(0f, 1f)
        ).dp
        val dragState = rememberDraggableState { delta ->
            if (!isFullDetail) {
                cardOffsetPx = (cardOffsetPx + delta).coerceIn(0f, fullHeightPx)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, cardOffsetPx.roundToInt()) }
                .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = !isFullDetail && !isImagePreviewOpen,
                    onDragStopped = { velocity ->
                        scope.launch {
                            val shouldExpand = cardOffsetPx <= expandThresholdPx ||
                                velocity < -MIN_UPWARD_FLING_VELOCITY
                            if (shouldExpand) {
                                isFullDetail = true
                                animateCardTo(0f)
                            } else if (
                                cardOffsetPx >= dismissThresholdPx ||
                                velocity > MIN_DOWNWARD_FLING_VELOCITY
                            ) {
                                dismissOverlay()
                            } else {
                                animateCardTo(previewTopPx)
                            }
                        }
                    }
                )
        ) {
            AnimatedContent(
                targetState = isFullDetail,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 14 })
                        .togetherWith(fadeOut() + slideOutVertically { -it / 20 })
                },
                label = "news-preview-to-detail"
            ) { fullDetail ->
                if (fullDetail) {
                    FullArticleDetail(
                        article = article,
                        onClose = dismissOverlay,
                        onImageClick = { isImagePreviewOpen = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PreviewSummary(
                        article = article,
                        onImageClick = { isImagePreviewOpen = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isImagePreviewOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f)
        ) {
            EnlargedImagePreview(
                article = article,
                onClose = { isImagePreviewOpen = false }
            )
        }
    }
}

@Composable
private fun PreviewSummary(
    article: NewsArticle,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            if (article.imageUrl != null) {
                PreviewImage(
                    article = article,
                    onClick = onImageClick,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(18.dp))
            }
            Text(
                text = article.readableBody(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (article.imageUrl != null) 2f else 1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                lineHeight = 28.sp,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FullArticleDetail(
    article: NewsArticle,
    onClose: () -> Unit,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        DetailTopBar(onClose = onClose)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 18.dp, bottom = 18.dp)
        ) {
            SourceRow(article = article)
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = article.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Normal
            )
            if (article.imageUrl != null) {
                Spacer(modifier = Modifier.height(20.dp))
                PreviewImage(
                    article = article,
                    onClick = onImageClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = article.readableBody(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Normal
            )
        }
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
            color = MaterialTheme.colorScheme.onSurface,
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "搜你想看的",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = "...",
            color = MaterialTheme.colorScheme.onSurface,
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
                .size(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = article.source.firstOrNull()?.toString() ?: "新",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.source,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = article.timeText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "关注",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PreviewImage(
    article: NewsArticle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var imageAspectRatio by remember(article.imageUrl) {
        mutableFloatStateOf(DEFAULT_IMAGE_ASPECT_RATIO)
    }
    Box(
        modifier = modifier
            .aspectRatio(imageAspectRatio)
            .animateContentSize()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = article.imageUrl,
            contentDescription = article.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    imageAspectRatio = (size.width / size.height)
                        .coerceIn(MIN_IMAGE_ASPECT_RATIO, MAX_IMAGE_ASPECT_RATIO)
                }
            }
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
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EnlargedImagePreview(
    article: NewsArticle,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable(onClick = onClose)
    ) {
        AsyncImage(
            model = article.imageUrl,
            contentDescription = article.title,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 72.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = "×",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 18.dp)
                .size(44.dp)
                .clickable(onClick = onClose),
            color = Color.White,
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Light
        )
        Text(
            text = article.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun NewsArticle.readableBody(): String {
    return listOf(content, description)
        .firstOrNull { it.isNotBlank() }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?: "暂无更多正文内容。"
}

private const val PREVIEW_TOP_FRACTION = 0.38f
private const val EXPAND_THRESHOLD_FRACTION = 0.30f
private const val DISMISS_THRESHOLD_FRACTION = 0.72f
private const val MIN_UPWARD_FLING_VELOCITY = 150f
private const val MIN_DOWNWARD_FLING_VELOCITY = 150f
private const val DEFAULT_IMAGE_ASPECT_RATIO = 16f / 9f
private const val MIN_IMAGE_ASPECT_RATIO = 0.8f
private const val MAX_IMAGE_ASPECT_RATIO = 3f

@Preview(
    name = "News Preview Card",
    showBackground = true,
    backgroundColor = 0xFFF3F3F3,
    widthDp = 393,
    heightDp = 520
)
@Composable
private fun NewsPreviewCardPreview() {
    TopnewsTheme(dynamicColor = false) {
        PreviewSummary(
            article = previewArticleSample,
            onImageClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp)
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

@Preview(
    name = "Full News Detail",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
    widthDp = 393,
    heightDp = 852
)
@Composable
private fun FullNewsDetailPreview() {
    TopnewsTheme(dynamicColor = false) {
        FullArticleDetail(
            article = previewArticleSample,
            onClose = {},
            onImageClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

private val previewArticleSample = NewsArticle(
    id = "preview-news",
    title = "视频｜警惕“名师押题”“AI押题”！教育部发布高考诈骗与谣言典型案例",
    source = "央视新闻",
    commentCount = 36,
    timeText = "2026-6-2 11:04  北京",
    description = "2026年高考即将到来，广大考生已经进入最后冲刺阶段。一些不法分子受利益驱使也在伺机而动，散布高考相关虚假信息，制造贩卖焦虑，组织实施诈骗，甚至诱导考生作弊，严重损害考生和家长切身利益，严重扰乱考试招生秩序。",
    imageUrl = "https://p3-sign.douyinpic.com/tos-cn-i-dy/preview-news-image.jpeg"
)
