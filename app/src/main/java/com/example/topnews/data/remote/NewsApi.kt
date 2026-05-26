package com.example.topnews.data.remote

import com.example.topnews.data.remote.dto.AliChannelResponse
import com.example.topnews.data.remote.dto.AliNewsListResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

interface NewsApi {
    @POST("news/channel")
    suspend fun getChannels(
        @Header("Authorization") authorization: String
    ): AliChannelResponse

    @FormUrlEncoded
    @POST("news/list")
    suspend fun getNewsList(
        @Header("Authorization") authorization: String,
        @Field("channelId") channelId: String = "",
        @Field("channelName") channelName: String = "头条",
        @Field("title") title: String = "",
        @Field("beginTime") beginTime: String = "",
        @Field("endTime") endTime: String = "",
        @Field("page") page: String = "1",
        @Field("pageSize") pageSize: String = "20"
    ): AliNewsListResponse
}
