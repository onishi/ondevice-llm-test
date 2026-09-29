package com.example.ondevicellm

/*
 * LLM の出力向けの軽量 Markdown パーサ。
 *
 * CommonMark の強調は「left/right-flanking」規則で開閉を判定するが、日本語は語間に空白がないため
 * `**「引用」**です` のように記号と隣接する強調が成立せず `**` が露出する。
 * ここでは flanking 規則を使わず、行内の `**` を出現順に 2 つずつ対にする。
 */

data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
)

sealed interface MdLine {
    data object Blank : MdLine
    data object Rule : MdLine
    data class Text(val spans: List<Span>) : MdLine
    data class Heading(val level: Int, val spans: List<Span>) : MdLine
    /** marker は "•" や "1." など。indent はネストの深さ */
    data class ListItem(val indent: Int, val marker: String, val spans: List<Span>) : MdLine
    data class Quote(val spans: List<Span>) : MdLine
    /** ``` で囲まれたブロック全体。lang は ```python の "python" (なければ空) */
    data class CodeBlock(val lang: String, val code: String) : MdLine
}

private val headingRe = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val ruleRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val bulletRe = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val numberedRe = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val quoteRe = Regex("""^\s*>\s?(.*)$""")
private val fenceRe = Regex("""^\s*```\s*([\w+#.-]*)""")

/**
 * @param streaming 生成途中なら true。最終行の閉じていない `**` を「ここから太字」とみなし、記号を見せない
 */
fun parseMarkdown(src: String, streaming: Boolean = false): List<MdLine> {
    val lines = src.split("\n")
    val out = mutableListOf<MdLine>()
    // 開いているコードブロック (閉じていなければ末尾まで。生成途中でもブロックとして表示される)
    var fenceLang: String? = null
    val fenceBody = mutableListOf<String>()
    fun closeFence() {
        out += MdLine.CodeBlock(fenceLang.orEmpty(), fenceBody.joinToString("\n"))
        fenceBody.clear()
        fenceLang = null
    }
    lines.forEachIndexed { idx, raw ->
        val line = raw.trimEnd('\r')
        val openToEnd = streaming && idx == lines.lastIndex
        val fence = fenceRe.find(line)
        if (fenceLang != null) {
            if (fence != null && fence.groupValues[1].isEmpty()) closeFence() else fenceBody += line
            return@forEachIndexed
        }
        if (fence != null) {
            fenceLang = fence.groupValues[1]
            return@forEachIndexed
        }
        if (line.isBlank()) {
            if (out.isNotEmpty() && out.last() != MdLine.Blank) out += MdLine.Blank
            return@forEachIndexed
        }
        out += headingRe.matchEntire(line)?.let {
            MdLine.Heading(it.groupValues[1].length, parseInline(it.groupValues[2], openToEnd))
        } ?: if (ruleRe.matches(line)) {
            MdLine.Rule
        } else bulletRe.matchEntire(line)?.let {
            MdLine.ListItem(indentLevel(it.groupValues[1]), "•", parseInline(it.groupValues[2], openToEnd))
        } ?: numberedRe.matchEntire(line)?.let {
            MdLine.ListItem(indentLevel(it.groupValues[1]), "${it.groupValues[2]}.", parseInline(it.groupValues[3], openToEnd))
        } ?: quoteRe.matchEntire(line)?.let {
            MdLine.Quote(parseInline(it.groupValues[1], openToEnd))
        } ?: MdLine.Text(parseInline(line, openToEnd))
    }
    if (fenceLang != null) closeFence()
    while (out.lastOrNull() == MdLine.Blank) out.removeAt(out.lastIndex)
    return out
}

private fun indentLevel(ws: String): Int =
    ws.sumOf { if (it == '\t') 4 else 1 } / 2

private sealed interface Token {
    data class Text(val text: String, val link: Boolean = false, val math: Boolean = false) : Token
    data class Code(val text: String) : Token
    class Bold : Token
    class Italic(val canOpen: Boolean, val canClose: Boolean) : Token
}

private val linkRe = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
/** LaTeX のインライン数式 `$i$`。閉じの `$` の直前が空白なら数式とみなさない ("$a + $b" などを誤爆しない) */
private val mathRe = Regex("""\$([^\s$](?:[^$\n]{0,40}?[^\s$])?)\$(?![\w$])""")

private fun tokenize(s: String): List<Token> {
    val tokens = mutableListOf<Token>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) tokens += Token.Text(buf.toString())
        buf.clear()
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && !s[i + 1].isLetterOrDigit() && !s[i + 1].isWhitespace() -> {
                buf.append(s[i + 1]); i += 2
            }
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush(); tokens += Token.Code(s.substring(i + 1, end)); i = end + 1
                } else {
                    buf.append(c); i++
                }
            }
            c == '[' -> {
                val m = linkRe.matchAt(s, i)
                if (m != null) {
                    flush(); tokens += Token.Text(m.groupValues[1], link = true); i = m.range.last + 1
                } else {
                    buf.append(c); i++
                }
            }
            c == '$' -> {
                val m = mathRe.matchAt(s, i)
                if (m != null) {
                    flush(); tokens += Token.Text(m.groupValues[1], math = true); i = m.range.last + 1
                } else {
                    buf.append(c); i++
                }
            }
            c == '*' -> {
                var n = 0
                while (i + n < s.length && s[i + n] == '*') n++
                val before = s.getOrNull(i - 1)
                val after = s.getOrNull(i + n)
                val canOpen = after != null && !after.isWhitespace()
                val canClose = before != null && !before.isWhitespace()
                flush()
                when (n) {
                    1 -> tokens += Token.Italic(canOpen, canClose)
                    2 -> tokens += Token.Bold()
                    3 -> {
                        // ***x*** は太字+斜体。開きは ** → *、閉じは * → ** の順に並べて入れ子を保つ
                        if (canOpen && !canClose) {
                            tokens += Token.Bold(); tokens += Token.Italic(true, false)
                        } else {
                            tokens += Token.Italic(canOpen, canClose); tokens += Token.Bold()
                        }
                    }
                    else -> buf.append("*".repeat(n))
                }
                i += n
            }
            else -> {
                buf.append(c); i++
            }
        }
    }
    flush()
    return tokens
}

fun parseInline(s: String, openToEnd: Boolean = false): List<Span> {
    val tokens = tokenize(s)

    // 太字: 出現順に 2 つずつ対にする (日本語対応のため flanking 規則は見ない)
    val boldIdx = tokens.indices.filter { tokens[it] is Token.Bold }
    val active = BooleanArray(tokens.size)
    boldIdx.chunked(2).forEach { pair ->
        if (pair.size == 2 || openToEnd) pair.forEach { active[it] = true }
    }

    // 斜体: `*` の直後/直前が空白でないものだけを開き/閉じとして対にする (箇条書きや掛け算の誤爆を避ける)
    var open = -1
    tokens.forEachIndexed { i, t ->
        if (t !is Token.Italic) return@forEachIndexed
        if (open >= 0 && t.canClose) {
            active[open] = true; active[i] = true; open = -1
        } else if (t.canOpen) {
            open = i
        }
    }

    val spans = mutableListOf<Span>()
    var bold = false
    var italic = false
    fun add(text: String, code: Boolean = false, link: Boolean = false) {
        if (text.isEmpty()) return
        val span = Span(text, bold, italic, code, link)
        val last = spans.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            spans[spans.lastIndex] = last.copy(text = last.text + text)
        } else {
            spans += span
        }
    }
    tokens.forEachIndexed { i, t ->
        when (t) {
            is Token.Text -> if (t.math) {
                val wasItalic = italic
                italic = true
                add(t.text, link = t.link)
                italic = wasItalic
            } else {
                add(t.text, link = t.link)
            }
            is Token.Code -> add(t.text, code = true)
            is Token.Bold -> if (active[i]) bold = !bold else add("**")
            is Token.Italic -> if (active[i]) italic = !italic else add("*")
        }
    }
    return spans
}
