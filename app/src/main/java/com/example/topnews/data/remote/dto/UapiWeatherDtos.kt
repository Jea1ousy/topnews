package com.example.topnews.data.remote.dto

import com.google.gson.annotations.SerializedName

data class UapiWeatherResponse(
    @SerializedName("province")
    val province: String?,
    @SerializedName("city")
    val city: String?,
    @SerializedName("weather")
    val weather: String?,
    @SerializedName("temperature")
    val temperature: Double?,
    @SerializedName("report_time")
    val reportTime: String?
)
