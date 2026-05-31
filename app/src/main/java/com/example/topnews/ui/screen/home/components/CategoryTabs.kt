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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val scrollState = rememberScrollState()
    val selectedIndex = categories.indexOf(selectedCategory).coerceAtLeast(0)

    LaunchedEffect(selectedIndex) {
        val target = (selectedIndex * 72).coerceAtLeast(0)
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
                    targetValue = if (selected) Color(0xFF111111) else Color(0xFF333333),
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
                                color = Color(0xFFFF3E49)
                            )
                        }
                    }
                }
            }
            Text(
                text = "=",
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                color = Color(0xFF777777),
                fontSize = 24.sp
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE8E8E8))
    }
}
