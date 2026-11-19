package com.mangacopy.app.source

/**
 * 章節標題分組（v0.3.7 — 用戶：「如果有分『卷』或者『話』，上方一個TAB切換FILTER」）。
 * 「第17卷」「第22卷附錄+番外」呢類標題抽出卷數歸卷；「第405话」/無「卷」字
 * → null（歸「話」tab）。支援阿拉伯數字同常見中文數字（百位內）。
 * 純 JVM 邏輯（冇 Android 依賴）— CoreLogicTest 直接測。
 */
object ChapterGroups {
    private val VOL_AR = Regex("第\\s*(\\d{1,3})\\s*卷")
    private val VOL_CJK = Regex("第\\s*([零一二三四五六七八九十百]{1,5})\\s*卷")

    /** 呢章係第幾卷？冇「卷」概念 → null（單話）。 */
    fun volumeOf(title: String?): Int? {
        if (title == null) return null
        VOL_AR.find(title)?.let { return it.groupValues[1].toIntOrNull() }
        VOL_CJK.find(title)?.let { return cjkToInt(it.groupValues[1]) }
        return null
    }

    /** 中文數字 → Int：十→10、二十三→23、一百五→105；认唔到 → null。 */
    fun cjkToInt(s: String): Int? {
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0; var cur = 0; var matched = false
        for (ch in s) when (ch) {
            '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0; matched = true }
            '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0; matched = true }
            else -> { val d = digits[ch] ?: return null; cur = d; matched = true }
        }
        return if (matched) total + cur else null
    }
}
