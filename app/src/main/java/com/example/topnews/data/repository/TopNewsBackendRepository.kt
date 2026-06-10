package com.example.topnews.data.repository

import android.os.Build
import com.example.topnews.BuildConfig
import com.example.topnews.data.remote.TopNewsBackendApi
import com.example.topnews.data.remote.dto.AddAcademicKeywordRequest
import com.example.topnews.data.remote.dto.BackendAcademicKeywordDto
import com.example.topnews.data.remote.dto.BackendNewsDto
import com.example.topnews.data.remote.dto.BackendNewsPageResponse
import com.example.topnews.data.remote.dto.BackendPaperDto
import com.example.topnews.data.remote.dto.BackendPaperPageResponse
import com.example.topnews.domain.model.AcademicKeyword
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.domain.model.NewsPage
import com.example.topnews.domain.repository.NewsRepository
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class TopNewsBackendRepository(
    configuredBaseUrl: String = BuildConfig.TOPNEWS_BACKEND_BASE_URL,
    deviceBaseUrl: String = BuildConfig.TOPNEWS_BACKEND_LAN_BASE_URL
) : NewsRepository {
    private val baseUrl: String = resolveBaseUrl(configuredBaseUrl, deviceBaseUrl)
    private val api: TopNewsBackendApi by lazy { createApi(baseUrl) }

    suspend fun getAcademicKeywords(): List<AcademicKeyword> {
        require(baseUrl.isNotBlank()) { "缺少 TOPNEWS_BACKEND_BASE_URL，请在 local.properties 中配置" }
        return api.getAcademicKeywords().mapNotNull { it.toAcademicKeyword() }
    }

    suspend fun addAcademicKeyword(keyword: String): AcademicKeyword {
        require(baseUrl.isNotBlank()) { "缺少 TOPNEWS_BACKEND_BASE_URL，请在 local.properties 中配置" }
        val normalizedKeyword = keyword.trim()
        require(normalizedKeyword.isNotBlank()) { "请输入关键词" }
        return api.addAcademicKeyword(AddAcademicKeywordRequest(normalizedKeyword)).toAcademicKeyword()
            ?: error("关键词保存失败")
    }

    suspend fun deleteAcademicKeyword(id: Int) {
        require(baseUrl.isNotBlank()) { "缺少 TOPNEWS_BACKEND_BASE_URL，请在 local.properties 中配置" }
        api.deleteAcademicKeyword(id)
    }

    suspend fun summarizeArticle(article: NewsArticle): String {
        require(baseUrl.isNotBlank()) { "缺少 TOPNEWS_BACKEND_BASE_URL，请在 local.properties 中配置" }
        val response = if (article.channelName == ACADEMIC_CATEGORY) {
            api.summarizePaper(article.id)
        } else {
            api.summarizeArticle(article.id)
        }
        return response.summary?.takeIf { it.isNotBlank() }
            ?: error("AI总结返回为空")
    }

    override suspend fun getTopNews(
        page: Int,
        pageSize: Int,
        category: String,
        forceRefresh: Boolean,
        excludeIds: List<String>
    ): NewsPage {
        require(baseUrl.isNotBlank()) { "缺少 TOPNEWS_BACKEND_BASE_URL，请在 local.properties 中配置" }
        if (forceRefresh && page == 1) {
            refreshRemote(category = category, pageSize = pageSize)
        }
        val exclude = excludeIds
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_REFRESH_EXCLUDE_IDS)
            .joinToString(",")
            .takeIf { it.isNotBlank() }

        val newsPage = fetchPage(
            page = page,
            pageSize = pageSize,
            category = category,
            exclude = exclude
        )
        if (!forceRefresh || page != 1 || excludeIds.isEmpty() || newsPage.articles.isEmpty()) {
            return newsPage
        }

        val returnedIds = newsPage.articles.map { it.id }.toSet()
        val excludedSet = excludeIds.toSet()
        val backendIgnoredExclude = returnedIds.isNotEmpty() && returnedIds.all { it in excludedSet }
        return if (backendIgnoredExclude) {
            fetchPage(page = 2, pageSize = pageSize, category = category, exclude = null)
        } else {
            newsPage
        }
    }

    private suspend fun fetchPage(
        page: Int,
        pageSize: Int,
        category: String,
        exclude: String?
    ): NewsPage {
        return when (category) {
            "AI前沿" -> api.getAiFrontier(page = page, pageSize = pageSize, exclude = exclude).toNewsPage(page, pageSize)
            "学术推荐" -> api.getPaperRecommendations(page = page, pageSize = pageSize, exclude = exclude).toNewsPage(page, pageSize)
            "推荐", "关注", "热榜" -> api.getRecommendations(page = page, pageSize = pageSize, exclude = exclude).toNewsPage(page, pageSize)
            else -> api.getNews(page = page, pageSize = pageSize, category = category, exclude = exclude).toNewsPage(page, pageSize)
        }
    }

    private suspend fun refreshRemote(category: String, pageSize: Int) {
        runCatching {
            when (category) {
                "AI前沿" -> api.ingestNews(limitPerSource = pageSize)
                "学术推荐" -> api.ingestPapers(limit = pageSize, source = "rss")
                else -> api.ingestNews(limitPerSource = pageSize)
            }
        }
    }

    private fun BackendNewsPageResponse.toNewsPage(defaultPage: Int, defaultPageSize: Int): NewsPage {
        val articles = items.orEmpty().mapNotNull { it.toNewsArticle() }
        return NewsPage(
            articles = articles,
            page = page ?: defaultPage,
            totalPage = totalPage ?: defaultPage,
            totalCount = totalCount ?: articles.size.coerceAtLeast(defaultPageSize)
        )
    }

    private fun BackendPaperPageResponse.toNewsPage(defaultPage: Int, defaultPageSize: Int): NewsPage {
        val articles = items.orEmpty().mapNotNull { it.toNewsArticle() }
        return NewsPage(
            articles = articles,
            page = page ?: defaultPage,
            totalPage = totalPage ?: defaultPage,
            totalCount = totalCount ?: articles.size.coerceAtLeast(defaultPageSize)
        )
    }

    private fun BackendNewsDto.toNewsArticle(): NewsArticle? {
        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isEmpty()) return null
        val resolvedImageUrl = resolveBackendUrl(imageUrl)
        val resolvedImageSourceUrl = resolveBackendUrl(imageSourceUrl)
        val htmlWithCachedPrimaryImage = html
            ?.replacePrimaryImageSource(
                sourceImageUrl = imageSourceUrl,
                cachedImageUrl = resolvedImageUrl
            )
        val normalizedImageUrls = imageUrls.orEmpty().map { value ->
            if (value == imageSourceUrl) imageUrl.orEmpty() else value
        }

        return NewsArticle(
            id = externalId?.takeIf { it.isNotBlank() } ?: id ?: safeTitle,
            title = safeTitle,
            source = listOfNotNull(source, region, category)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · ")
                .ifBlank { "TopNews" },
            commentCount = 0,
            timeText = formatRelativeTime(publishedAt ?: fetchedAt),
            link = url.orEmpty(),
            description = summary?.takeIf { it.isNotBlank() } ?: description.orEmpty(),
            aiSummary = aiSummary?.takeIf { it.isNotBlank() },
            content = content?.takeIf { it.isNotBlank() } ?: description.orEmpty(),
            html = htmlWithCachedPrimaryImage?.takeIf { it.isNotBlank() } ?: contentHtml.orEmpty(),
            channelName = category.orEmpty(),
            imageUrl = resolvedImageUrl,
            imageUrls = resolveBackendUrls(normalizedImageUrls, imageUrl)
                .filterNot { it == resolvedImageSourceUrl }
        )
    }

    private fun BackendPaperDto.toNewsArticle(): NewsArticle? {
        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isEmpty()) return null
        val keywordText = matchedKeywords.orEmpty().take(3).joinToString("/")
        val categoryText = categories.orEmpty().take(2).joinToString("/")
        val authorText = authors.orEmpty().take(2).joinToString(", ")
        val metaParts = listOfNotNull(
            source?.takeIf { it.isNotBlank() },
            keywordText.takeIf { it.isNotBlank() },
            categoryText.takeIf { it.isNotBlank() }
        )

        return NewsArticle(
            id = externalId?.takeIf { it.isNotBlank() } ?: id ?: safeTitle,
            title = safeTitle,
            source = metaParts.joinToString(" · ").ifBlank { "arXiv" },
            commentCount = 0,
            timeText = formatRelativeTime(publishedAt ?: updatedAt ?: fetchedAt),
            link = url.orEmpty(),
            description = summary?.takeIf { it.isNotBlank() }
                ?: abstractText?.takeIf { it.isNotBlank() }
                ?: description.orEmpty(),
            aiSummary = aiSummary?.takeIf { it.isNotBlank() },
            content = listOf(
                authorText,
                imageCaption?.takeIf { it.isNotBlank() }?.let { "图注：$it" },
                abstractText?.takeIf { it.isNotBlank() }
                    ?: content?.takeIf { it.isNotBlank() }
                    ?: description.orEmpty()
            )
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            channelName = if (itemType == "news") "AI前沿" else "学术推荐",
            imageUrl = resolveBackendUrl(imageUrl),
            imageUrls = resolveBackendUrls(emptyList(), imageUrl)
        )
    }

    private fun BackendAcademicKeywordDto.toAcademicKeyword(): AcademicKeyword? {
        val safeId = id ?: return null
        val safeRule = rawRule?.trim().orEmpty()
        if (safeRule.isEmpty()) return null
        return AcademicKeyword(
            id = safeId,
            rawRule = safeRule,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: safeRule,
            isRequired = isRequired == true,
            isExcluded = isExcluded == true,
            isRegex = isRegex == true
        )
    }

    private fun resolveBackendUrls(values: List<String>?, fallback: String?): List<String> {
        return listOfNotNull(fallback, *values.orEmpty().toTypedArray())
            .mapNotNull(::resolveBackendUrl)
            .distinct()
    }

    private fun resolveBackendUrl(value: String?): String? {
        val url = value?.takeIf { it.isNotBlank() } ?: return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> baseUrl.trimEnd('/') + url
            else -> baseUrl.withTrailingSlash() + url
        }
    }

    private fun String.replacePrimaryImageSource(sourceImageUrl: String?, cachedImageUrl: String?): String {
        if (sourceImageUrl.isNullOrBlank() || cachedImageUrl.isNullOrBlank()) return this
        return replace(sourceImageUrl, cachedImageUrl)
    }

    companion object {
        private const val MAX_REFRESH_EXCLUDE_IDS = 300
        private const val ACADEMIC_CATEGORY = "学术推荐"
        private const val EMULATOR_HOST_ALIAS = "10.0.2.2"
        private const val DEFAULT_LOCAL_BACKEND_BASE_URL = "http://10.0.2.2:8080/"

        private fun resolveBaseUrl(configuredBaseUrl: String, deviceBaseUrl: String): String {
            val configured = configuredBaseUrl.trim()
            val device = deviceBaseUrl.trim()
            val selected = when {
                isProbablyEmulator() -> configured.ifBlank { DEFAULT_LOCAL_BACKEND_BASE_URL }
                device.isNotBlank() -> device
                else -> configured
            }

            return if (isProbablyEmulator()) {
                selected.rewriteLocalBackendHostForEmulator().withTrailingSlash()
            } else {
                selected.withTrailingSlash()
            }
        }

        private fun String.rewriteLocalBackendHostForEmulator(): String {
            val uri = runCatching { URI(this) }.getOrNull() ?: return this
            val host = uri.host?.lowercase() ?: return this
            if (host !in setOf("localhost", "127.0.0.1", "0.0.0.0")) return this
            return URI(
                uri.scheme,
                uri.userInfo,
                EMULATOR_HOST_ALIAS,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        }

        private fun isProbablyEmulator(): Boolean {
            return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
                Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                Build.PRODUCT.startsWith("sdk", ignoreCase = true) ||
                Build.PRODUCT in setOf("google_sdk", "sdk", "sdk_gphone", "sdk_x86", "vbox86p")
        }

        private fun createApi(baseUrl: String): TopNewsBackendApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl.withTrailingSlash())
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TopNewsBackendApi::class.java)
        }

        private fun String.withTrailingSlash(): String {
            if (isBlank()) return this
            return if (endsWith("/")) this else "$this/"
        }

        private fun formatRelativeTime(rawTime: String?): String {
            val date = rawTime?.toDateOrNull() ?: return "刚刚"
            val durationMillis = System.currentTimeMillis() - date.time
            if (durationMillis < 0) return "刚刚"

            val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
            val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
            val days = TimeUnit.MILLISECONDS.toDays(durationMillis)
            return when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "${minutes}分钟前"
                hours < 24 -> "${hours}小时前"
                days == 1L -> "昨天"
                days < 7 -> "${days}天前"
                else -> date.formatForNews()
            }
        }

        private fun String.toDateOrNull(): Date? {
            val value = trim()
            if (value.isEmpty()) return null
            return ISO_DATE_PATTERNS.firstNotNullOfOrNull { pattern ->
                runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(value)
                }.getOrNull()
            }
        }

        private fun Date.formatForNews(): String {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val itemYear = Calendar.getInstance().apply { time = this@formatForNews }.get(Calendar.YEAR)
            val pattern = if (itemYear == currentYear) "MM-dd" else "yyyy-MM-dd"
            return SimpleDateFormat(pattern, Locale.getDefault()).format(this)
        }

        private val ISO_DATE_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
    }
}
