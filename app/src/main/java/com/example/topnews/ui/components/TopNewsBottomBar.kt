package com.example.topnews.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
fun TopNewsBottomBar(
    selectedTab: String,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("首页", "视频", "搜索", "任务", "我的")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE8E8E8))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = tab == selectedTab
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = iconFor(tab),
                        color = if (selected) Color(0xFFFF3E49) else Color(0xFF111111),
                        fontSize = 22.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = tab,
                        color = if (selected) Color(0xFFFF3E49) else Color(0xFF111111),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun iconFor(tab: String): String {
    return when (tab) {
        "首页" -> "⌂"
        "视频" -> "▷"
        "搜索" -> "○"
        "任务" -> "□"
        else -> "♙"
    }
}
