package com.example.topnews.ui.screen.home.components

internal data class LatexFormulaToken(
    val start: Int,
    val endExclusive: Int,
    val latex: String,
    val isBlock: Boolean
)

internal fun containsLatexFormula(text: String): Boolean {
    return LATEX_FORMULA_REGEX.containsMatchIn(text)
}

internal fun parseLatexFormulas(text: String): List<LatexFormulaToken> {
    return LATEX_FORMULA_REGEX.findAll(text).mapNotNull { match ->
        val latex = match.groupValues
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            .orEmpty()
        if (latex.isEmpty()) {
            null
        } else {
            LatexFormulaToken(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                latex = latex,
                isBlock = match.value.startsWith("\\[") || isStandaloneFormula(text, match.range)
            )
        }
    }.toList()
}

private fun isStandaloneFormula(text: String, range: IntRange): Boolean {
    val lineStart = text.lastIndexOf('\n', startIndex = range.first - 1)
        .let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', startIndex = range.last + 1)
        .let { if (it < 0) text.length else it }
    return text.substring(lineStart, lineEnd).trim() == text.substring(range).trim()
}

private val LATEX_FORMULA_REGEX = Regex(
    pattern = """(?s)(?<!\\)\\\[(.+?)(?<!\\)\\\]|(?<!\\)\\\((.+?)(?<!\\)\\\)|(?<!\\)\$\$(.+?)(?<!\\)\$\$|(?<!\\)(?<!\$)\$(?!\$)(.+?)(?<!\\)(?<!\$)\$(?!\$)"""
)
