package com.example.topnews.ui.screen.home.components

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.min

@Composable
internal fun AcademicFormulaText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val textSizePx = with(density) { fontSize.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val colorArgb = color.toArgb()
        val formulas = remember(text, textSizePx, maxWidthPx, colorArgb) {
            createRenderedFormulas(
                text = text,
                textSizePx = textSizePx,
                maxWidthPx = maxWidthPx,
                colorArgb = colorArgb
            )
        }
        val annotatedText = remember(text, formulas) {
            buildFormulaAnnotatedText(text, formulas)
        }
        val inlineContent = formulas.associate { formula ->
            val width = with(density) { formula.widthPx.toSp() }
            val height = with(density) { formula.heightPx.toSp() }
            formula.id to InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = height,
                    placeholderVerticalAlign = if (formula.token.isBlock) {
                        PlaceholderVerticalAlign.Center
                    } else {
                        PlaceholderVerticalAlign.TextCenter
                    }
                )
            ) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(0, 0, 0, 0)
                        }
                    },
                    update = { imageView ->
                        imageView.setImageDrawable(formula.drawable)
                    }
                )
            }
        }

        Text(
            text = annotatedText,
            inlineContent = inlineContent,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Normal
        )
    }
}

private data class RenderedFormula(
    val id: String,
    val token: LatexFormulaToken,
    val drawable: JLatexMathDrawable,
    val widthPx: Float,
    val heightPx: Float
)

private fun createRenderedFormulas(
    text: String,
    textSizePx: Float,
    maxWidthPx: Float,
    colorArgb: Int
): List<RenderedFormula> {
    return parseLatexFormulas(text).mapIndexedNotNull { index, token ->
        val drawable = runCatching {
            JLatexMathDrawable.builder(token.latex)
                .textSize(textSizePx * FORMULA_TEXT_SIZE_SCALE)
                .color(colorArgb)
                .build()
        }.getOrNull() ?: return@mapIndexedNotNull null

        val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
        val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
        val availableWidth = if (token.isBlock) maxWidthPx else maxWidthPx * MAX_INLINE_WIDTH_FRACTION
        val scale = min(1f, availableWidth / intrinsicWidth)
        val renderedWidth = if (token.isBlock) maxWidthPx else intrinsicWidth * scale

        RenderedFormula(
            id = "latex-$index-${token.start}",
            token = token,
            drawable = drawable,
            widthPx = renderedWidth,
            heightPx = intrinsicHeight * scale
        )
    }
}

private fun buildFormulaAnnotatedText(
    text: String,
    formulas: List<RenderedFormula>
): AnnotatedString {
    if (formulas.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        formulas.forEach { formula ->
            append(text.substring(cursor, formula.token.start))
            if (
                formula.token.isBlock &&
                formula.token.start > 0 &&
                text[formula.token.start - 1] != '\n'
            ) {
                append('\n')
            }
            appendInlineContent(formula.id, formula.token.latex)
            if (
                formula.token.isBlock &&
                formula.token.endExclusive < text.length &&
                text[formula.token.endExclusive] != '\n'
            ) {
                append('\n')
            }
            cursor = formula.token.endExclusive
        }
        append(text.substring(cursor))
    }
}

private const val FORMULA_TEXT_SIZE_SCALE = 1.05f
private const val MAX_INLINE_WIDTH_FRACTION = 0.92f
