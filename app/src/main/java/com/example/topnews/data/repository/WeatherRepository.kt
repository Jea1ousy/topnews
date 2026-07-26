package com.example.topnews.data.repository

import com.example.topnews.data.remote.SharedHttp
import com.example.topnews.data.remote.UapiWeatherApi
import com.example.topnews.domain.model.WeatherSnapshot
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.roundToInt

class WeatherRepository {
    private val api: UapiWeatherApi by lazy { createApi() }

    suspend fun getCurrentWeather(city: String?): WeatherSnapshot {
        val response = api.getCurrentWeather(city = city?.takeIf { it.isNotBlank() && it != "当前位置" })
        return WeatherSnapshot(
            city = response.city?.takeIf { it.isNotBlank() } ?: city?.takeIf { it.isNotBlank() } ?: "当前位置",
            temperature = response.temperature?.roundToInt()?.let { "$it°" } ?: "--°",
            weather = response.weather?.takeIf { it.isNotBlank() } ?: "天气"
        )
    }

    companion object {
        private fun createApi(): UapiWeatherApi {
            return Retrofit.Builder()
                .baseUrl("https://uapis.cn/")
                .client(SharedHttp.withTimeouts(connectSeconds = 10, readSeconds = 15))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(UapiWeatherApi::class.java)
        }
    }
}
