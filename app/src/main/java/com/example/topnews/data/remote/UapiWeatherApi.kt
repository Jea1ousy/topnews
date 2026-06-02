package com.example.topnews.data.remote

import com.example.topnews.data.remote.dto.UapiWeatherResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface UapiWeatherApi {
    @GET("api/v1/misc/weather")
    suspend fun getCurrentWeather(
        @Query("city") city: String? = null,
        @Query("lang") lang: String = "zh"
    ): UapiWeatherResponse
}
