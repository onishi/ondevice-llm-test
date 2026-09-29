package com.example.ondevicellm

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanupTest {

    @Test
    fun removesStrayDevanagari() {
        assertEquals("はい、承知。PerlでFizzBuzz", removeStrayScripts("はい、承知मम。PerlでFizzBuzz"))
    }

    @Test
    fun removesIsolatedExtendedLatinAndItsSpace() {
        assertEquals("方法1（if/elsif の連）", removeStrayScripts("方法1（if/elsif の連 ŋ）"))
        assertEquals("日本語", removeStrayScripts("日本 ŋ 語"))
    }

    @Test
    fun removesStrayHangulAndCyrillic() {
        assertEquals("これはです。", removeStrayScripts("これは테스트です。"))
        assertEquals("これはです。", removeStrayScripts("これはтестです。"))
    }

    @Test
    fun keepsRomajiWithMacrons() {
        val s = "皇居 (Kōkyo) と Tōkyō"
        assertEquals(s, removeStrayScripts(s))
    }

    @Test
    fun keepsQuotedForeignWords() {
        val s = "韓国語で愛は「사랑」、ヒンディー語では「प्यार」です。"
        assertEquals(s, removeStrayScripts(s))
    }

    @Test
    fun keepsAnswersWrittenInOtherLanguages() {
        val ko = "안녕하세요! 저는 도움이 되는 어시스턴트입니다. 네, 좋아요."
        assertEquals(ko, removeStrayScripts(ko))
        val ru = "Привет! Да, это тест."
        assertEquals(ru, removeStrayScripts(ru))
    }

    @Test
    fun keepsGreekEmojiAndSymbols() {
        val s = "α = 0.5、π ≈ 3.14 です 😊 → ✓"
        assertEquals(s, removeStrayScripts(s))
    }

    @Test
    fun keepsAccentedLatin1AndCode() {
        val s = "café で `print(\"Hello, World!\")` を実行\n    my \$i = 1;"
        assertEquals(s, removeStrayScripts(s))
    }
}
