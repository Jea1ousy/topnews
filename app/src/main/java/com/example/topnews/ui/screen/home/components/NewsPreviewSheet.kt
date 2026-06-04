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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
        var previewImageUrl by remember(article.id) { mutableStateOf<String?>(null) }

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
            if (previewImageUrl != null) {
                previewImageUrl = null
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
                    enabled = !isFullDetail && previewImageUrl == null,
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
                        onImageClick = { previewImageUrl = it },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PreviewSummary(
                        article = article,
                        onImageClick = { previewImageUrl = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = previewImageUrl != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f)
        ) {
            EnlargedImagePreview(
                article = article,
                imageUrl = previewImageUrl.orEmpty(),
                onClose = { previewImageUrl = null }
            )
        }
    }
}

@Composable
private fun PreviewSummary(
    article: NewsArticle,
    onImageClick: (String) -> Unit,
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
                    imageUrl = article.imageUrl,
                    contentDescription = article.title,
                    videoDuration = article.videoDuration,
                    onClick = { onImageClick(article.imageUrl) },
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
    onImageClick: (String) -> Unit,
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
            ArticleText(
                article = article,
                text = article.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                lineHeight = 29.sp,
                modifier = Modifier.fillMaxWidth()
            )
            if (article.imageUrl != null) {
                Spacer(modifier = Modifier.height(20.dp))
                PreviewImage(
                    imageUrl = article.imageUrl,
                    contentDescription = article.title,
                    videoDuration = article.videoDuration,
                    onClick = { onImageClick(article.imageUrl) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            ArticleBody(article = article)
            ArticleImageGallery(
                article = article,
                onImageClick = onImageClick
            )
        }
    }
}

@Composable
private fun ArticleBody(article: NewsArticle) {
    val paragraphs = remember(article.content, article.description, article.html) {
        article.readableParagraphs()
    }
    paragraphs.forEachIndexed { index, paragraph ->
        ArticleText(
            article = article,
            text = paragraph,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 31.sp,
            modifier = Modifier.fillMaxWidth()
        )
        if (index < paragraphs.lastIndex) {
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ArticleText(
    article: NewsArticle,
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    if (article.channelName == ACADEMIC_CHANNEL_NAME && containsLatexFormula(text)) {
        AcademicFormulaText(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = modifier
        )
    } else {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Normal,
            modifier = modifier
        )
    }
}

@Composable
private fun ArticleImageGallery(
    article: NewsArticle,
    onImageClick: (String) -> Unit
) {
    val extraImages = remember(article.imageUrl, article.imageUrls) {
        article.imageUrls
            .filter { it.isNotBlank() && it != article.imageUrl }
            .distinct()
    }
    if (extraImages.isEmpty()) return

    Spacer(modifier = Modifier.height(20.dp))
    extraImages.forEachIndexed { index, imageUrl ->
        PreviewImage(
            imageUrl = imageUrl,
            contentDescription = "${article.title} 图片 ${index + 2}",
            onClick = { onImageClick(imageUrl) },
            modifier = Modifier.fillMaxWidth()
        )
        if (index < extraImages.lastIndex) {
            Spacer(modifier = Modifier.height(14.dp))
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
    imageUrl: String,
    contentDescription: String,
    videoDuration: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var imageAspectRatio by remember(imageUrl) {
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
            model = imageUrl,
            contentDescription = contentDescription,
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

        if (videoDuration != null) {
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
    imageUrl: String,
    onClose: () -> Unit
) {
    var scale by remember(article.id, imageUrl) { mutableFloatStateOf(1f) }
    var offset by remember(article.id, imageUrl) { mutableStateOf(Offset.Zero) }
    var imageViewportSize by remember(article.id, imageUrl) { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable(onClick = onClose)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = article.title,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 72.dp)
                .onSizeChanged { imageViewportSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(article.id, imageUrl) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(
                            MIN_IMAGE_PREVIEW_SCALE,
                            MAX_IMAGE_PREVIEW_SCALE
                        )
                        if (newScale <= MIN_IMAGE_PREVIEW_SCALE) {
                            scale = MIN_IMAGE_PREVIEW_SCALE
                            offset = Offset.Zero
                        } else {
                            val maxOffsetX = imageViewportSize.width * (newScale - 1f) / 2f
                            val maxOffsetY = imageViewportSize.height * (newScale - 1f) / 2f
                            scale = newScale
                            offset = Offset(
                                x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                            )
                        }
                    }
                },
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
    return readableParagraphs().joinToString("\n\n")
}

private fun NewsArticle.readableParagraphs(): List<String> {
    val rawBody = listOf(content, html, description)
        .firstOrNull { it.isNotBlank() }
        ?: return listOf("暂无更多正文内容。")
    val normalized = rawBody
        .replace(HTML_BREAK_REGEX, "\n")
        .replace(HTML_TAG_REGEX, " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { line ->
            line.replace(INLINE_WHITESPACE_REGEX, " ").trim()
        }
        .replace(MULTIPLE_NEWLINES_REGEX, "\n\n")
        .trim()

    if (normalized.isBlank()) {
        return listOf("暂无更多正文内容。")
    }

    return normalized
        .split(PARAGRAPH_BREAK_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .flatMap(::splitLongParagraph)
}

private fun splitLongParagraph(paragraph: String): List<String> {
    if (paragraph.length <= MAX_PARAGRAPH_LENGTH) {
        return listOf(paragraph)
    }

    val sentences = paragraph
        .split(SENTENCE_BOUNDARY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (sentences.size <= 1) {
        return listOf(paragraph)
    }

    val paragraphs = mutableListOf<String>()
    val current = StringBuilder()
    sentences.forEach { sentence ->
        if (current.isNotEmpty() && current.length + sentence.length > MAX_PARAGRAPH_LENGTH) {
            paragraphs += current.toString()
            current.clear()
        }
        current.append(sentence)
        if (current.length >= TARGET_PARAGRAPH_LENGTH) {
            paragraphs += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) {
        paragraphs += current.toString()
    }
    return paragraphs
}

private const val PREVIEW_TOP_FRACTION = 0.38f
private const val EXPAND_THRESHOLD_FRACTION = 0.30f
private const val DISMISS_THRESHOLD_FRACTION = 0.72f
private const val MIN_UPWARD_FLING_VELOCITY = 150f
private const val MIN_DOWNWARD_FLING_VELOCITY = 150f
private const val DEFAULT_IMAGE_ASPECT_RATIO = 16f / 9f
private const val MIN_IMAGE_ASPECT_RATIO = 0.8f
private const val MAX_IMAGE_ASPECT_RATIO = 3f
private const val MIN_IMAGE_PREVIEW_SCALE = 1f
private const val MAX_IMAGE_PREVIEW_SCALE = 5f
private const val TARGET_PARAGRAPH_LENGTH = 150
private const val MAX_PARAGRAPH_LENGTH = 240
private const val ACADEMIC_CHANNEL_NAME = "学术推荐"

private val HTML_BREAK_REGEX = Regex(
    "(?i)<\\s*br\\s*/?\\s*>|</\\s*(p|div|article|section|li|h[1-6])\\s*>"
)
private val HTML_TAG_REGEX = Regex("<[^>]+>")
private val INLINE_WHITESPACE_REGEX = Regex("[\\t ]+")
private val MULTIPLE_NEWLINES_REGEX = Regex("\\n{3,}")
private val PARAGRAPH_BREAK_REGEX = Regex("\\n+")
private val SENTENCE_BOUNDARY_REGEX = Regex("(?<=[。！？；!?;])|(?<=[.!?])\\s+")

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
