package com.example.topnews.ui.screen.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.topnews.data.location.DeviceLocationProvider
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.ui.screen.home.components.CategoryTabs
import com.example.topnews.ui.screen.home.components.HomeHeader
import com.example.topnews.ui.screen.home.components.NewsList
import com.example.topnews.ui.screen.home.components.NewsPreviewSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val previewSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val closePreviewSheet: () -> Unit = {
        coroutineScope.launch {
            previewSheetState.hide()
            previewArticle = null
        }
    }
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

    LaunchedEffect(previewArticle) {
        if (previewArticle != null) {
            previewSheetState.show()
        }
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
                        onArticleClick = { article -> previewArticle = article },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    previewArticle?.let { article ->
        val isSheetExpanded = previewSheetState.currentValue == SheetValue.Expanded ||
            previewSheetState.targetValue == SheetValue.Expanded
        ModalBottomSheet(
            onDismissRequest = closePreviewSheet,
            sheetState = previewSheetState,
            containerColor = Color.White,
            shape = if (isSheetExpanded) {
                RoundedCornerShape(0.dp)
            } else {
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            },
            dragHandle = if (isSheetExpanded) null else {
                { BottomSheetDefaults.DragHandle() }
            }
        ) {
            NewsPreviewSheet(
                article = article,
                isExpanded = isSheetExpanded,
                onClose = closePreviewSheet,
                modifier = Modifier.fillMaxHeight()
            )
        }
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
