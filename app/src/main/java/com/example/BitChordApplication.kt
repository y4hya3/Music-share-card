package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.repository.MediaNotificationRepository
import com.example.util.DevicePerformanceTier

class BitChordApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        MediaNotificationRepository.initialize(this)
    }

    override fun newImageLoader(): ImageLoader {
        val tier = DevicePerformanceTier.get(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (tier.isLowRam) 0.12 else 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cover_cache"))
                    .maxSizeBytes(24L * 1024L * 1024L)
                    .build()
            }
            .allowRgb565(tier.isLowRam)
            .crossfade(160)
            .respectCacheHeaders(false)
            .build()
    }
}
