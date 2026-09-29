package com.example.ondevicellm

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    private fun plain(spans: List<Span>) = spans.joinToString("") { it.text }
    private fun bolds(spans: List<Span>) = spans.filter { it.bold }.map { it.text }

    @Test
    fun boldAdjacentToJapanesePunctuation() {
        // CommonMark だと太字にならないケース
        val spans = parseInline("これは**「引用」**です")
        assertEquals("これは「引用」です", plain(spans))
        assertEquals(listOf("「引用」"), bolds(spans))
    }

    @Test
    fun boldInsideJapaneseSentence() {
        val spans = parseInline("私たちは新しい計画について**検討する必要がある**。")
        assertEquals("私たちは新しい計画について検討する必要がある。", plain(spans))
        assertEquals(listOf("検討する必要がある"), bolds(spans))
    }

    @Test
    fun boldEndingWithColon() {
        val spans = parseInline("**トピックについて深く考える場合:**")
        assertEquals(listOf("トピックについて深く考える場合:"), bolds(spans))
    }

    @Test
    fun multipleBoldsInLine() {
        val spans = parseInline("**talk (話す):** 単に**情報**を伝える")
        assertEquals(listOf("talk (話す):", "情報"), bolds(spans))
        assertEquals("talk (話す): 単に情報を伝える", plain(spans))
    }

    @Test
    fun unmatchedBoldIsLiteralWhenDone() {
        assertEquals("a **b", plain(parseInline("a **b")))
    }

    @Test
    fun unmatchedBoldIsHiddenWhileStreaming() {
        val spans = parseInline("a **b", openToEnd = true)
        assertEquals("a b", plain(spans))
        assertEquals(listOf("b"), bolds(spans))
    }

    @Test
    fun italicAndMultiplication() {
        val spans = parseInline("*強調*と 2 * 3 = 6")
        assertEquals("強調と 2 * 3 = 6", plain(spans))
        assertEquals(listOf("強調"), spans.filter { it.italic }.map { it.text })
    }

    @Test
    fun boldItalic() {
        val spans = parseInline("***両方***")
        assertEquals(listOf(Span("両方", bold = true, italic = true)), spans)
    }

    @Test
    fun inlineCodeKeepsAsterisks() {
        val spans = parseInline("`a**b` と **太字**")
        assertEquals(Span("a**b", code = true), spans.first())
        assertEquals(listOf("太字"), bolds(spans))
    }

    @Test
    fun inlineMath() {
        assertEquals(
            listOf(Span("つまり "), Span("i", italic = true), Span(" が 3 で割り切れる")),
            parseInline("つまり \$i\$ が 3 で割り切れる"),
        )
        // Perl の変数などは数式扱いしない
        assertEquals(listOf(Span("\$a + \$b と \$5")), parseInline("\$a + \$b と \$5"))
    }

    @Test
    fun link() {
        assertEquals(listOf(Span("Google", link = true)), parseInline("[Google](https://google.com)"))
    }

    @Test
    fun unclosedFenceWhileStreaming() {
        assertEquals(
            listOf(MdLine.Text(listOf(Span("コード:"))), MdLine.CodeBlock("python", "print(1)")),
            parseMarkdown("コード:\n```python\nprint(1)", streaming = true),
        )
    }

    @Test
    fun blocks() {
        val md = """
            ## 見出し
            * **項目:** 説明
              - 入れ子
            2. 番号付き
            > 引用
            ---
            ```kotlin
            val x = **y**

              indent()
            ```


            本文
        """.trimIndent()
        assertEquals(
            listOf(
                MdLine.Heading(2, listOf(Span("見出し"))),
                MdLine.ListItem(0, "•", listOf(Span("項目:", bold = true), Span(" 説明"))),
                MdLine.ListItem(1, "•", listOf(Span("入れ子"))),
                MdLine.ListItem(0, "2.", listOf(Span("番号付き"))),
                MdLine.Quote(listOf(Span("引用"))),
                MdLine.Rule,
                MdLine.CodeBlock("kotlin", "val x = **y**\n\n  indent()"),
                MdLine.Blank,
                MdLine.Text(listOf(Span("本文"))),
            ),
            parseMarkdown(md),
        )
    }
}
