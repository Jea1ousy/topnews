package com.example.topnews.data.remote

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全进程共享的 OkHttp 实例。
 *
 * 原先 TopNewsBackendRepository / WeatherRepository / AliNewsRepository 各自 new 一个
 * OkHttpClient，等于三套 Dispatcher 线程池 + 三个连接池 + 三份 DNS 缓存，冷启动时白白多建线程，
 * 且同一后端的连接无法复用。这里统一成一个：需要不同超时的调用方走 [withTimeouts]，
 * 它基于 newBuilder() 派生，Dispatcher 与连接池仍是同一份。
 */
object SharedHttp {

    /** API 调用共用的客户端。 */
    val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 派生一个只改超时的客户端，线程池与连接池仍与 [apiClient] 共享。 */
    fun withTimeouts(
        connectSeconds: Long,
        readSeconds: Long,
        writeSeconds: Long = readSeconds
    ): OkHttpClient {
        return apiClient.newBuilder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 图片加载专用：复用连接池（省掉与后端重新握手），但换一个独立的 Dispatcher。
     *
     * 关键点是不能和 API 共用 Dispatcher：OkHttp 默认 maxRequestsPerHost=5，
     * 而正文图片与列表接口同源，滚动时几十个图片请求会把下一页接口挤在队列后面。
     */
    fun imageClient(): OkHttpClient {
        return apiClient.newBuilder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 6
                }
            )
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
