package com.example.topnews

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.topnews.data.remote.SharedHttp

/**
 * 提供全局 Coil ImageLoader。
 *
 * 之前没有 Application 类，Coil 用的是默认单例：内存缓存按默认比例、磁盘缓存也是默认目录与上限，
 * 且每次 AsyncImage 都走它自建的 OkHttpClient。这里显式声明三件事：
 *  - 内存缓存占可用堆 20%（列表来回滚动时缩略图基本不再重新解码）
 *  - 磁盘缓存 64MB，冷启动后仍能命中，后端 /image 已带 max-age=86400
 *  - 复用 [SharedHttp] 的连接池，但用独立 Dispatcher，避免图片请求把接口请求挤在队列后
 */
class TopNewsApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { SharedHttp.imageClient() }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            // 新闻配图来源杂，很多站点不带 Cache-Control；忽略响应头一律入盘缓存
            .respectCacheHeaders(false)
            .build()
    }
}
