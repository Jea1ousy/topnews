package com.example.topnews.ui.screen.home.components

internal fun compactSourceName(source: String): String {
    val normalized = source.trim()
    if (normalized.isBlank()) return "新闻"
    if (normalized.contains("联合早报")) return "联合早报"
    return normalized
        .split("·")
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: normalized
}
