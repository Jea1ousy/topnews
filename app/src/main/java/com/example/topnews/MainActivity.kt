package com.example.topnews

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.topnews.ui.screen.home.HomeScreen
import com.example.topnews.ui.theme.TopnewsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TopnewsTheme(dynamicColor = false) {
                HomeScreen()
            }
        }
    }
}
