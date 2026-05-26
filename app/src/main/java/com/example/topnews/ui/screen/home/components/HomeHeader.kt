package com.example.topnews.ui.screen.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.topnews.ui.screen.home.HomeUiState

private val HeaderRed = Color(0xFFFF4F57)

@Composable
fun HomeHeader(
    uiState: HomeUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HeaderRed)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.22f),
                contentColor = Color.White
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "T", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = uiState.temperature,
                modifier = Modifier.padding(start = 8.dp),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    text = "${uiState.city} ${uiState.weather}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "空气质量良",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.18f),
                contentColor = Color.White
            ) {
                Text(
                    text = "AI回答",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "+",
                modifier = Modifier.padding(start = 12.dp),
                color = Color.White,
                fontSize = 28.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        SearchBar(text = uiState.searchText)
    }
}
