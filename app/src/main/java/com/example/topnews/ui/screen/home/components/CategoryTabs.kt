package com.example.topnews.ui.screen.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun CategoryTabs(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val selectedIndex = categories.indexOf(selectedCategory).coerceAtLeast(0)
    val tabWidths = remember(categories) { mutableStateMapOf<String, Int>() }

    LaunchedEffect(selectedCategory, tabWidths.toMap(), scrollState.maxValue) {
        val selectedWidth = tabWidths[selectedCategory] ?: return@LaunchedEffect
        val viewportWidth = scrollState.viewportSize
        if (viewportWidth <= 0) return@LaunchedEffect

        val selectedStart = categories
            .take(selectedIndex)
            .sumOf { category -> tabWidths[category] ?: 0 }
        val selectedCenter = selectedStart + selectedWidth / 2f
        val target = (selectedCenter - viewportWidth / 2f)
            .roundToInt()
            .coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(target)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { category ->
                val selected = category == selectedCategory
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    animationSpec = tween(durationMillis = 180),
                    label = "categoryTextColor"
                )
                val indicatorWidth by animateDpAsState(
                    targetValue = if (selected) 22.dp else 0.dp,
                    animationSpec = tween(durationMillis = 180),
                    label = "categoryIndicatorWidth"
                )
                Column(
                    modifier = Modifier
                        .onSizeChanged { size ->
                            tabWidths[category] = size.width
                        }
                        .clickable { onCategorySelected(category) }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = category,
                        color = textColor,
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .padding(horizontal = 4.dp)
                            .width(22.dp)
                    ) {
                        if (indicatorWidth > 0.dp) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width(indicatorWidth),
                                thickness = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Text(
                text = "=",
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 24.sp
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
