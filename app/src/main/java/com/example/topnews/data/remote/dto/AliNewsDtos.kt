package com.example.topnews.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AliChannelResponse(
    @SerializedName("code")
    val code: Int?,
    @SerializedName("msg")
    val msg: String?,
    @SerializedName("taskNo")
    val taskNo: String?,
    @SerializedName("data")
    val data: AliChannelData?
)

data class AliChannelData(
    @SerializedName("totalCount")
    val totalCount: Int?,
    @SerializedName("items")
    val items: List<AliChannelDto>?
)

data class AliChannelDto(
    @SerializedName("name")
    val name: String?,
    @SerializedName("channelId")
    val channelId: String?
)

data class AliNewsListResponse(
    @SerializedName("code")
    val code: Int?,
    @SerializedName("msg")
    val msg: String?,
    @SerializedName("taskNo")
    val taskNo: String?,
    @SerializedName("data")
    val data: AliNewsListData?
)

data class AliNewsListData(
    @SerializedName("page")
    val page: Int?,
    @SerializedName("pageSize")
    val pageSize: Int?,
    @SerializedName("totalPage")
    val totalPage: Int?,
    @SerializedName("totalCount")
    val totalCount: Int?,
    @SerializedName("items")
    val items: List<AliNewsDto>?
)

data class AliNewsDto(
    @SerializedName("link")
    val link: String?,
    @SerializedName("channelId")
    val channelId: String?,
    @SerializedName("channelName")
    val channelName: String?,
    @SerializedName("source")
    val source: String?,
    @SerializedName("id")
    val id: String?,
    @SerializedName("title")
    val title: String?,
    @SerializedName("pubDate")
    val pubDate: String?,
    @SerializedName("desc")
    val desc: String?,
    @SerializedName("nid")
    val nid: String?,
    @SerializedName("imageUrls")
    val imageUrls: List<String>?,
    @SerializedName("content")
    val content: String?,
    @SerializedName("html")
    val html: String?
)
