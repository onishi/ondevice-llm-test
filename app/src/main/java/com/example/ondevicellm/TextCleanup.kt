package com.example.ondevicellm

import java.lang.Character.UnicodeScript

/*
 * 量子化モデルを GPU で動かすと、日本語の文中に無関係な文字体系のトークンがまれに混ざる
 * (例: 「承知मम」「連 ŋ」)。文章全体の中で浮いている短い文字列を取り除く。
 *
 * - 5 文字以上続く箇所が 1 つもない文字体系の、4 文字以下の並び → 除去
 *   (韓国語やヒンディー語で答えている場合は長い並びがあるので残る)
 * - Latin-1 より後ろのラテン文字 (ŋ など) で、前後に ASCII の英字がないもの → 除去
 *   (Tōkyō の ō のように英単語の一部なら残る)
 * - 括弧や引用符で囲まれたものは意図的な引用とみなして残す
 */

private enum class Kind { JAPANESE, LATIN, EXT_LATIN, OTHER, NEUTRAL }

private data class Run(val kind: Kind, val script: UnicodeScript?, val text: String) {
    val length get() = text.codePointCount(0, text.length)
}

private fun kindOf(cp: Int): Pair<Kind, UnicodeScript?> {
    val script = UnicodeScript.of(cp)
    val kind = when (script) {
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN -> Kind.JAPANESE
        UnicodeScript.LATIN -> when {
            cp <= 0xFF -> Kind.LATIN
            cp in 0xFF00..0xFFEF -> Kind.NEUTRAL // 全角英字
            else -> Kind.EXT_LATIN
        }
        // 記号・数字・絵文字・結合文字、数式でよく使うギリシャ文字は対象外
        UnicodeScript.COMMON, UnicodeScript.INHERITED, UnicodeScript.GREEK, UnicodeScript.UNKNOWN -> Kind.NEUTRAL
        else -> Kind.OTHER
    }
    return kind to script.takeIf { kind == Kind.OTHER }
}

private fun splitRuns(text: String): List<Run> {
    val runs = mutableListOf<Run>()
    val buf = StringBuilder()
    var current: Pair<Kind, UnicodeScript?>? = null
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        var k = kindOf(cp)
        // 結合文字 (濁点や母音記号など) は直前の文字と同じ並びとして扱う
        if (UnicodeScript.of(cp) == UnicodeScript.INHERITED && current != null) k = current
        if (k != current && buf.isNotEmpty()) {
            runs += Run(current!!.first, current.second, buf.toString())
            buf.clear()
        }
        current = k
        buf.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    if (buf.isNotEmpty()) runs += Run(current!!.first, current.second, buf.toString())
    return runs
}

private const val OPENERS = "「『\"“'‘(（[【"
private const val CLOSERS = "」』\"”'’)）]】"

fun removeStrayScripts(text: String): String {
    val runs = splitRuns(text)
    val establishedScripts = runs.filter { it.kind == Kind.OTHER && it.length >= 5 }.mapNotNull { it.script }.toSet()

    fun isStray(i: Int): Boolean {
        val r = runs[i]
        val prev = runs.getOrNull(i - 1)
        val next = runs.getOrNull(i + 1)
        val quoted = prev?.text?.lastOrNull()?.let { it in OPENERS } == true &&
            next?.text?.firstOrNull()?.let { it in CLOSERS } == true
        if (quoted) return false
        return when (r.kind) {
            Kind.OTHER -> r.length <= 4 && r.script !in establishedScripts
            Kind.EXT_LATIN -> prev?.kind != Kind.LATIN && next?.kind != Kind.LATIN
            else -> false
        }
    }

    val out = StringBuilder()
    var skipSpace = false
    runs.forEachIndexed { i, r ->
        if (isStray(i)) {
            val nextChar = runs.getOrNull(i + 1)?.text?.firstOrNull()
            // 「連 ŋ）」→「連）」のように、除去で浮いた空白も落とす
            if (out.endsWith(" ") && (nextChar == null || nextChar == ' ' || !nextChar.isLetterOrDigit())) {
                out.setLength(out.length - 1)
            }
            skipSpace = out.isEmpty() || !out.last().isLetterOrDigit() || kindOf(out.codePointBefore(out.length)).first == Kind.JAPANESE
            return@forEachIndexed
        }
        var t = r.text
        if (skipSpace && t.startsWith(" ")) t = t.substring(1)
        skipSpace = false
        out.append(t)
    }
    return out.toString()
}
