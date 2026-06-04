package com.example.topnews.data.repository

import com.example.topnews.BuildConfig
import com.example.topnews.data.remote.TopNewsBackendApi
import com.example.topnews.data.remote.dto.BackendNewsDto
import com.example.topnews.data.remote.dto.BackendNewsPageResponse
import com.example.topnews.data.remote.dto.BackendPaperDto
import com.example.topnews.data.remote.dto.BackendPaperPageResponse
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.domain.model.NewsPage
import com.example.topnews.domain.repository.NewsRepository
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TopNewsBackendRepository(
    private val baseUrl: String = BuildConfig.TOPNEWS_BACKEND_BASE_URL
) : NewsRepository {
    private val api: TopNewsBackendApi by lazy { createApi(baseUrl) }

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
            content = content?.takeIf { it.isNotBlank() } ?: description.orEmpty(),
            channelName = category.orEmpty(),
            imageUrl = resolveBackendUrl(imageUrl),
            imageUrls = resolveBackendUrls(imageUrls, imageUrl)
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

    companion object {
        private const val MAX_REFRESH_EXCLUDE_IDS = 300

        private fun createApi(baseUrl: String): TopNewsBackendApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl.withTrailingSlash())
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TopNewsBackendApi::class.java)
        }

        private fun String.withTrailingSlash(): String {
            return if (endsWith("/")) this else "$this/"
        }

        private fun formatRelativeTime(rawTime: String?): String {
            val instant = rawTime?.toInstantOrNull() ?: return "刚刚"
            val now = Instant.now()
            val duration = Duration.between(instant, now)
            if (duration.isNegative) return "刚刚"

            val minutes = duration.toMinutes()
            val hours = duration.toHours()
            val days = duration.toDays()
            return when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "${minutes}分钟前"
                hours < 24 -> "${hours}小时前"
                days == 1L -> "昨天"
                days < 7 -> "${days}天前"
                else -> instant
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .formatForNews()
            }
        }

        private fun String.toInstantOrNull(): Instant? {
            return runCatching { Instant.parse(this) }.getOrNull()
        }

        private fun LocalDate.formatForNews(): String {
            val pattern = if (year == LocalDate.now().year) "MM-dd" else "yyyy-MM-dd"
            return format(DateTimeFormatter.ofPattern(pattern))
        }
    }
}
