package com.example.topnews.ui.screen.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.topnews.data.location.DeviceLocationProvider
import com.example.topnews.domain.model.AcademicKeyword
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.ui.screen.home.components.CategoryTabs
import com.example.topnews.ui.screen.home.components.HomeHeader
import com.example.topnews.ui.screen.home.components.NewsList
import com.example.topnews.ui.screen.home.components.NewsPreviewOverlay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
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
    var programmaticScrollTargetPage by remember { mutableStateOf<Int?>(null) }
    var showAcademicKeywords by remember { mutableStateOf(false) }
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
            programmaticScrollTargetPage = page
            try {
                pagerState.animateScrollToPage(page)
            } finally {
                if (programmaticScrollTargetPage == page) {
                    programmaticScrollTargetPage = null
                }
            }
        }
    }

    LaunchedEffect(pagerState, uiState.categories) {
        snapshotFlow { pagerState.currentPage }
            .mapNotNull { page ->
                uiState.categories.getOrNull(page)?.let { category -> page to category }
            }
            .distinctUntilChanged()
            .collect { (page, category) ->
                val targetPage = programmaticScrollTargetPage
                if (targetPage == null || targetPage == page) {
                    viewModel.selectCategory(category)
                }
            }
    }

    BackHandler(enabled = previewArticle != null) {
        closePreview()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            HomeHeader(
                uiState = uiState,
                onRefresh = {
                    if (previewArticle == null) {
                        viewModel.refresh()
                    }
                },
                onAcademicKeywordsClick = {
                    if (previewArticle == null) {
                        showAcademicKeywords = true
                        viewModel.loadAcademicKeywords()
                    }
                }
            )
            CategoryTabs(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { category ->
                    if (previewArticle != null) return@CategoryTabs
                    viewModel.selectCategory(category)
                }
            )
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = previewArticle == null,
                modifier = Modifier.weight(1f)
            ) { page ->
                val category = uiState.categories.getOrNull(page) ?: return@HorizontalPager
                val feed = uiState.feedFor(category)
                Box(modifier = Modifier.fillMaxSize()) {
                    NewsList(
                        articles = feed.articles,
                        isLoading = feed.isLoading,
                        isLoadingMore = feed.isLoadingMore,
                        hasMore = feed.hasMore,
                        error = feed.error,
                        lastUpdatedText = feed.lastUpdatedText,
                        onRetry = {
                            if (uiState.selectedCategory == category) {
                                viewModel.refresh()
                            }
                        },
                        onLoadMore = {
                            if (uiState.selectedCategory == category) {
                                viewModel.loadMore()
                            }
                        },
                        isRefreshing = feed.isRefreshing,
                        onRefresh = {
                            if (uiState.selectedCategory == category) {
                                viewModel.refresh()
                            }
                        },
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
            aiSummaryState = uiState.aiSummaries[article.id],
            onSummarizeClick = viewModel::summarizeArticle,
            onClose = closePreview,
            modifier = Modifier.fillMaxSize()
        )
    }

    if (showAcademicKeywords) {
        AcademicKeywordDialog(
            keywords = uiState.academicKeywords,
            isLoading = uiState.isLoadingAcademicKeywords,
            error = uiState.academicKeywordError,
            onAddKeyword = viewModel::addAcademicKeyword,
            onDeleteKeyword = viewModel::deleteAcademicKeyword,
            onRefresh = viewModel::loadAcademicKeywords,
            onDismiss = { showAcademicKeywords = false }
        )
    }
}

@Composable
private fun AcademicKeywordDialog(
    keywords: List<AcademicKeyword>,
    isLoading: Boolean,
    error: String?,
    onAddKeyword: (String) -> Unit,
    onDeleteKeyword: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var keywordText by remember { mutableStateOf("") }
    val trimmedKeyword = keywordText.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "学术关键词",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = keywordText,
                        onValueChange = { keywordText = it },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        singleLine = true,
                        label = { Text("关键词或规则") },
                        placeholder = { Text("RAG、+retrieval、!survey") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = trimmedKeyword.isNotBlank() && !isLoading,
                        onClick = {
                            onAddKeyword(trimmedKeyword)
                            keywordText = ""
                        }
                    ) {
                        Text("添加")
                    }
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isLoading && keywords.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (keywords.isEmpty()) {
                    Text(
                        text = "还没有关键词",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keywords.forEach { keyword ->
                            AcademicKeywordRow(
                                keyword = keyword,
                                enabled = !isLoading,
                                onDelete = { onDeleteKeyword(keyword.id) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "可用写法：RAG、+retrieval、!survey、/multi.?modal/、retrieval => RAG",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Text("刷新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun AcademicKeywordRow(
    keyword: AcademicKeyword,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = keyword.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (keyword.rawRule != keyword.displayName) {
                    Text(
                        text = keyword.rawRule,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeywordFlagChip(keyword = keyword)
                }
            }
            Text(
                text = "删除",
                modifier = Modifier
                    .clickable(enabled = enabled) { onDelete() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun KeywordFlagChip(keyword: AcademicKeyword) {
    val label = when {
        keyword.isExcluded -> "排除"
        keyword.isRequired -> "必须"
        keyword.isRegex -> "正则"
        else -> "普通"
    }
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    )
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
