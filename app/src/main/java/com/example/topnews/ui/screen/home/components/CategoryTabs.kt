package com.example.topnews.ui.screen.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CategoryTabs(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                    targetValue = if (selected) 28.dp else 0.dp,
                    animationSpec = tween(durationMillis = 220),
                    label = "categoryIndicatorWidth"
                )
                val indicatorHeight by animateDpAsState(
                    targetValue = if (selected) 3.dp else 2.dp,
                    animationSpec = tween(durationMillis = 220),
                    label = "categoryIndicatorHeight"
                )
                val fontSize by animateFloatAsState(
                    targetValue = if (selected) 18f else 16f,
                    animationSpec = tween(durationMillis = 220),
                    label = "categoryFontSize"
                )
                Column(
                    modifier = Modifier
                        .clickable { onCategorySelected(category) }
                        .padding(horizontal = 2.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = category,
                        color = textColor,
                        fontSize = fontSize.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .padding(horizontal = 4.dp)
                            .width(28.dp)
                    ) {
                        if (indicatorWidth > 0.dp) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width(indicatorWidth),
                                thickness = indicatorHeight,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
