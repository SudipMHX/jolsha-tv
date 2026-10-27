package com.jolsha.tv

import android.app.Application
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.jolsha.tv.data.repository.PlaylistRepository
import com.jolsha.tv.data.repository.UserPreferencesRepository
import java.io.PrintWriter
import java.io.StringWriter

class JolshaApp : Application(), ImageLoaderFactory {

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(applicationContext)
    }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(applicationContext, preferencesRepository)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Global crash handler to record any fatal exception for user display
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JolshaApp", "FATAL UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            try {
                val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                prefs.edit().putString("last_crash", sw.toString()).commit()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowHardware(false)
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: JolshaApp
            private set
    }
}
