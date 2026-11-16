// MangaCopy Android — 根構建腳本
// 版本錨點（必須互相兼容，升級前要核對 AGP↔Gradle↔Kotlin 對照表）：
//   AGP 8.7.3 ← Gradle 8.9+ ← JDK 17；Kotlin 2.0.21（內置 Compose 編譯器插件）
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
