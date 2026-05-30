package com.example.topnews.data.repository

import com.example.topnews.BuildConfig
import com.example.topnews.data.remote.dto.AliChannelDto
import com.example.topnews.data.remote.dto.AliNewsDto
import com.example.topnews.data.remote.NewsApi
import com.example.topnews.domain.model.NewsArticle
import com.example.topnews.domain.model.NewsPage
import com.example.topnews.domain.repository.NewsRepository
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AliNewsRepository(
    private val appCode: String = BuildConfig.ALI_NEWS_APPCODE,
    private val baseUrl: String = BuildConfig.ALI_NEWS_BASE_URL
) : NewsRepository {
    override suspend fun getTopNews(
        page: Int,
        pageSize: Int,
        category: String,
        forceRefresh: Boolean,
        excludeIds: List<String>
    ): NewsPage {
        require(appCode.isNotBlank()) { "缺少 ALI_NEWS_APPCODE，请在 local.properties 中配置" }
        require(baseUrl.isNotBlank()) { "缺少 ALI_NEWS_BASE_URL，请在 local.properties 中配置" }

        val api = createApi(baseUrl)
        val authorization = "APPCODE $appCode"
        val channel = api.getChannels(authorization).let { response ->
            if (response.code != 200) {
                throw IllegalStateException("频道接口异常：code=${response.code}, msg=${response.msg.orEmpty()}")
            }

            response.data?.items.orEmpty()
                .firstOrNull { it.name == "国内最新" }
                ?: response.data?.items.orEmpty().firstOrNull()
                ?: throw IllegalStateException("频道接口返回为空：msg=${response.msg.orEmpty()}")
        }

        val response = api.getNewsList(
            authorization = authorization,
            channelId = channel.channelId.orEmpty(),
            channelName = channel.name.orEmpty(),
            page = page.toString(),
            pageSize = pageSize.toString()
        )

        if (response.code != 200 && response.code != 202) {
            throw IllegalStateException("新闻接口异常：code=${response.code}, msg=${response.msg.orEmpty()}")
        }

        val articles = response.data?.items.orEmpty().mapNotNull { it.toNewsArticle() }
        if (articles.isEmpty()) {
            throw IllegalStateException("新闻接口返回为空：频道=${channel.displayName()}, msg=${response.msg.orEmpty()}")
        }

        return NewsPage(
            articles = articles,
            page = response.data?.page ?: page,
            totalPage = response.data?.totalPage ?: page,
            totalCount = response.data?.totalCount ?: articles.size
        )
    }

    private fun AliChannelDto.displayName(): String {
        return name?.takeIf { it.isNotBlank() }
            ?: channelId?.takeIf { it.isNotBlank() }
            ?: "未知频道"
    }

    private fun AliNewsDto.toNewsArticle(): NewsArticle? {
        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isEmpty()) return null

        return NewsArticle(
            id = id?.takeIf { it.isNotBlank() } ?: link ?: safeTitle,
            title = safeTitle,
            source = source?.takeIf { it.isNotBlank() } ?: channelName.orEmpty().ifBlank { "新闻" },
            commentCount = 0,
            timeText = pubDate.orEmpty().ifBlank { "刚刚" },
            link = link.orEmpty(),
            description = desc.orEmpty(),
            content = content.orEmpty(),
            html = html.orEmpty(),
            channelId = channelId.orEmpty(),
            channelName = channelName.orEmpty(),
            imageUrl = imageUrls.orEmpty().firstOrNull { it.isNotBlank() }
        )
    }

    companion object {
        private fun createApi(baseUrl: String): NewsApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl.withTrailingSlash())
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(NewsApi::class.java)
        }

        private fun String.withTrailingSlash(): String {
            return if (endsWith("/")) this else "$this/"
        }
    }
}
