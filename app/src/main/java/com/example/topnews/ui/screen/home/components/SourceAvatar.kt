package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.topnews.R

@Composable
fun SourceAvatar(
    source: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp
) {
    val iconRes = remember(source) { sourceIconRes(source) }
    if (iconRes != null) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = source,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White),
            contentScale = ContentScale.Crop
        )
        return
    }

    val palette = remember(source) { sourceAvatarPalette(source) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pageWidth = this.size.width * 0.48f
            val pageHeight = this.size.height * 0.52f
            val left = (this.size.width - pageWidth) / 2f
            val top = (this.size.height - pageHeight) / 2f
            val radius = this.size.width * 0.04f

            drawRoundRect(
                color = palette.foreground,
                topLeft = Offset(left, top),
                size = Size(pageWidth, pageHeight),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = this.size.width * 0.045f)
            )
            drawRoundRect(
                color = palette.foreground.copy(alpha = 0.88f),
                topLeft = Offset(left + pageWidth * 0.14f, top + pageHeight * 0.16f),
                size = Size(pageWidth * 0.72f, pageHeight * 0.14f),
                cornerRadius = CornerRadius(radius, radius)
            )
            repeat(3) { index ->
                val y = top + pageHeight * (0.46f + index * 0.15f)
                drawLine(
                    color = palette.foreground.copy(alpha = 0.82f),
                    start = Offset(left + pageWidth * 0.16f, y),
                    end = Offset(left + pageWidth * 0.84f, y),
                    strokeWidth = this.size.width * 0.035f
                )
            }
        }
    }
}

private fun sourceIconRes(source: String): Int? {
    return when {
        source.contains("36氪", ignoreCase = true) -> R.drawable.ic_source_36kr
        source.contains("arXiv", ignoreCase = true) -> R.drawable.arxiv
        source.contains("财联社", ignoreCase = true) -> R.drawable.cailianshe
        source.contains("东方财富", ignoreCase = true) -> R.drawable.dfcf
        source.contains("联合早报", ignoreCase = true) -> R.drawable.lhzb
        source.contains("新华社", ignoreCase = true) -> R.drawable.xhs
        source.contains("量子位", ignoreCase = true) -> R.drawable.lzw
        source.contains("少数派", ignoreCase = true) -> R.drawable.ic_source_sspai
        source.contains("TechCrunch", ignoreCase = true) -> R.drawable.techcrunch
        source.contains("同花顺", ignoreCase = true) -> R.drawable.ths
        else -> null
    }
}

private data class SourceAvatarPalette(
    val background: Color,
    val foreground: Color
)

private fun sourceAvatarPalette(source: String): SourceAvatarPalette {
    val palettes = listOf(
        SourceAvatarPalette(Color(0xFFE7F0FF), Color(0xFF1B5EA8)),
        SourceAvatarPalette(Color(0xFFFFE9DE), Color(0xFF9F3D16)),
        SourceAvatarPalette(Color(0xFFE6F4EA), Color(0xFF1E6B43)),
        SourceAvatarPalette(Color(0xFFFFF3C7), Color(0xFF8A5B00)),
        SourceAvatarPalette(Color(0xFFF0E9FF), Color(0xFF5D3AA5)),
        SourceAvatarPalette(Color(0xFFE4F7F8), Color(0xFF126A73))
    )
    val index = Math.floorMod(source.hashCode(), palettes.size)
    return palettes[index]
}
