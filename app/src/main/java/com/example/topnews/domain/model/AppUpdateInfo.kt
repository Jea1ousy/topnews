package com.example.topnews.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val forceUpdate: Boolean = false
)
