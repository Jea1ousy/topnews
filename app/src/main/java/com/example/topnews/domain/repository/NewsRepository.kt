package com.example.topnews.domain.repository

import com.example.topnews.domain.model.NewsArticle

interface NewsRepository {
    suspend fun getTopNews(): List<NewsArticle>
}