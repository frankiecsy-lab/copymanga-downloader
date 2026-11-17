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
 * 自訂 Application — 全局 Coil ImageLoaderFactory。
 *
 * 站點封面 CDN 同圖片域名一樣認瀏覽器 UA（桌面版 fetch 封面都帶同一個 UA + Referer），
 * Coil 預設 UA 係 Coil/xxx 會被拒，所以所有圖片加載統一注入瀏覽器 UA + Referer。
 *
 * 【教訓】本檔曾被 session 中斷錯位到 android/app/ 根目錄（Gradle 唔會編譯），
 * Manifest android:name=".MangaCopyApp" 又唔做編譯期檢查 → 實機啟動即
 * ClassNotFoundException 閃退。而家檔喺正確 java 源碼樹；改 Manifest 類名後
 * 要跑 `gradle lintDebug`（MissingClass check）先至提交。
 */
class MangaCopyApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 三個需要 Context 嘅單例全部喺呢度初始化（UI 層直接 Db.get()，呢度唔 init 就閃退）
        Db.init(this)
        Storage.init(this)
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
                                .header("Referer", AppConfig.BASE_URL + "/")
                                .build()
                        )
                    }
                    .build()
            }
            .crossfade(true)
            .build()
}
