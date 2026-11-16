package com.mangacopy.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import com.mangacopy.app.source.WebViewRenderer
import okhttp3.OkHttpClient

/**
 * App 初始化：Storage/DB/WebView 渲染器 + 全局 Coil ImageLoader
 * （封面圖 CDN 可能比 UA — 統一加瀏覽器 User-Agent，同桌面版 requests 行為一致）。
 */
class MangaCopyApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
        Db.init(this)
        WebViewRenderer.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", AppConfig.USER_AGENT)
                                .build()
                        )
                    }
                    .build()
            }
            .crossfade(true)
            .build()
}
