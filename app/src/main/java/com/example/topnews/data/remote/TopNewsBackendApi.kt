package com.example.topnews.data.remote

import com.example.topnews.data.remote.dto.BackendNewsPageResponse
import com.example.topnews.data.remote.dto.BackendPaperPageResponse
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface TopNewsBackendApi {
    @POST("v1/ingest")
    suspend fun ingestNews(
        @Query("limitPerSource") limitPerSource: Int
    )

    @POST("v1/papers/ingest")
    suspend fun ingestPapers(
        @Query("limit") limit: Int,
        @Query("source") source: String = "rss"
    )

    @GET("v1/recommendations")
    suspend fun getRecommendations(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("exclude") exclude: String? = null
    ): BackendNewsPageResponse

    @GET("v1/news")
    suspend fun getNews(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("category") category: String? = null,
        @Query("exclude") exclude: String? = null
    ): BackendNewsPageResponse

    @GET("v1/ai-frontier")
    suspend fun getAiFrontier(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("exclude") exclude: String? = null
    ): BackendPaperPageResponse

    @GET("v1/papers/recommendations")
    suspend fun getPaperRecommendations(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("exclude") exclude: String? = null
    ): BackendPaperPageResponse
}
