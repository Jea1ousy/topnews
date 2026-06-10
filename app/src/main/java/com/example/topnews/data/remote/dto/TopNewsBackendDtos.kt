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
    @SerializedName("summary")
    val summary: String?,
    @SerializedName("ai_summary")
    val aiSummary: String?,
    @SerializedName("content")
    val content: String?,
    @SerializedName("content_html")
    val contentHtml: String?,
    @SerializedName("html")
    val html: String?,
    @SerializedName("image_url")
    val imageUrl: String?,
    @SerializedName("image_source_url")
    val imageSourceUrl: String?,
    @SerializedName("image_urls")
    val imageUrls: List<String>?,
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
    @SerializedName("description")
    val description: String?,
    @SerializedName("summary")
    val summary: String?,
    @SerializedName("ai_summary")
    val aiSummary: String?,
    @SerializedName("content")
    val content: String?,
    @SerializedName("source")
    val source: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("pdf_url")
    val pdfUrl: String?,
    @SerializedName("image_url")
    val imageUrl: String?,
    @SerializedName("image_caption")
    val imageCaption: String?,
    @SerializedName("item_type")
    val itemType: String?,
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

data class BackendAcademicKeywordDto(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("raw_rule")
    val rawRule: String?,
    @SerializedName("display_name")
    val displayName: String?,
    @SerializedName("is_required")
    val isRequired: Boolean?,
    @SerializedName("is_excluded")
    val isExcluded: Boolean?,
    @SerializedName("is_regex")
    val isRegex: Boolean?
)

data class AddAcademicKeywordRequest(
    @SerializedName("keyword")
    val keyword: String
)

data class BackendAiSummaryResponse(
    @SerializedName("summary")
    val summary: String?,
    @SerializedName("cached")
    val cached: Boolean?
)
