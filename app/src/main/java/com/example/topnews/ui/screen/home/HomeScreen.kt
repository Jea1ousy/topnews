package com.example.topnews.ui.screen.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.topnews.data.location.DeviceLocationProvider
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.ui.screen.home.components.CategoryTabs
import com.example.topnews.ui.screen.home.components.HomeHeader
import com.example.topnews.ui.screen.home.components.NewsList
import com.example.topnews.ui.screen.home.components.NewsPreviewOverlay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = uiState.categories.indexOf(uiState.selectedCategory).coerceAtLeast(0),
        pageCount = { uiState.categories.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var previewArticle by remember { mutableStateOf<NewsArticle?>(null) }
    val closePreview: () -> Unit = { previewArticle = null }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            coroutineScope.launch {
                loadWeatherFromDevice(context = context, viewModel = viewModel)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            loadWeatherFromDevice(context = context, viewModel = viewModel)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(uiState.selectedCategory, uiState.categories) {
        val page = uiState.categories.indexOf(uiState.selectedCategory)
        if (page >= 0 && page != pagerState.currentPage) {
            pagerState.animateScrollToPage(page)
        }
    }

    LaunchedEffect(pagerState.currentPage, uiState.categories) {
        uiState.categories.getOrNull(pagerState.currentPage)?.let { category ->
            viewModel.selectCategory(category)
        }
    }

    BackHandler(enabled = previewArticle != null) {
        closePreview()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            HomeHeader(
                uiState = uiState,
                onRefresh = viewModel::refresh
            )
            CategoryTabs(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { category ->
                    viewModel.selectCategory(category)
                    val page = uiState.categories.indexOf(category)
                    if (page >= 0) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    }
                }
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NewsList(
                        articles = uiState.articles,
                        isLoading = uiState.isLoading,
                        isLoadingMore = uiState.isLoadingMore,
                        hasMore = uiState.hasMore,
                        error = uiState.error,
                        lastUpdatedText = uiState.lastUpdatedText,
                        onRetry = viewModel::refresh,
                        onLoadMore = viewModel::loadMore,
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = viewModel::refresh,
                        onArticleClick = { article ->
                            previewArticle = article
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    previewArticle?.let { article ->
        NewsPreviewOverlay(
            article = article,
            onClose = closePreview,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private suspend fun loadWeatherFromDevice(
    context: Context,
    viewModel: HomeViewModel
) {
    DeviceLocationProvider(context.applicationContext)
        .getDeviceLocation()
        ?.let(viewModel::loadWeather)
}

private fun Context.hasLocationPermission(): Boolean {
    val coarseGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val fineGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return coarseGranted || fineGranted
}
