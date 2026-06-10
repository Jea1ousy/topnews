package com.example.topnews.domain.model

data class AcademicKeyword(
    val id: Int,
    val rawRule: String,
    val displayName: String,
    val isRequired: Boolean,
    val isExcluded: Boolean,
    val isRegex: Boolean
)
