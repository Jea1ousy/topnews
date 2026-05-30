package com.example.topnews.data.remote.dto

import com.google.gson.annotations.SerializedName

data class BackendNewsPageResponse(
    @SerializedName("items")
    val items: List<BackendNewsDto>?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("pageSize")
    val pageSize: Int?,
    @SerializedName("totalPage")
    val totalPage: Int?,
    @SerializedName("totalCount")
    val totalCount: Int?,
    @SerializedName("hasMore")
    val hasMore: Boolean?
)

data class BackendNewsDto(
    @SerializedName("id")
    val id: String?,
    @SerializedName("external_id")
    val externalId: String?,
    @SerializedName("title")
    val title: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("source")
    val source: String?,
    @SerializedName("region")
    val region: String?,
    @SerializedName("category")
    val category: String?,
    @SerializedName("description")
    val description: String?,
    @SerializedName("content")
    val content: String?,
    @SerializedName("image_url")
    val imageUrl: String?,
    @SerializedName("published_at")
    val publishedAt: String?,
    @SerializedName("fetched_at")
    val fetchedAt: String?
)

data class BackendPaperPageResponse(
    @SerializedName("items")
    val items: List<BackendPaperDto>?,
    @SerializedName("page")
    val page: Int?,
    @SerializedName("pageSize")
    val pageSize: Int?,
    @SerializedName("totalPage")
    val totalPage: Int?,
    @SerializedName("totalCount")
    val totalCount: Int?,
    @SerializedName("hasMore")
    val hasMore: Boolean?
)

data class BackendPaperDto(
    @SerializedName("id")
    val id: String?,
    @SerializedName("external_id")
    val externalId: String?,
    @SerializedName("title")
    val title: String?,
    @SerializedName("authors")
    val authors: List<String>?,
    @SerializedName("abstract")
    val abstractText: String?,
    @SerializedName("source")
    val source: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("pdf_url")
    val pdfUrl: String?,
    @SerializedName("categories")
    val categories: List<String>?,
    @SerializedName("published_at")
    val publishedAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?,
    @SerializedName("fetched_at")
    val fetchedAt: String?,
    @SerializedName("matched_keywords")
    val matchedKeywords: List<String>?,
    @SerializedName("score")
    val score: Int?
)
