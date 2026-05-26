package com.example.topnews.ui.screen.home.components

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
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { category ->
                val selected = category == selectedCategory
                Column(
                    modifier = Modifier
                        .clickable { onCategorySelected(category) }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = category,
                        color = if (selected) Color(0xFF111111) else Color(0xFF333333),
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
                        if (selected) {
                            HorizontalDivider(
                                modifier = Modifier.align(Alignment.Center),
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
