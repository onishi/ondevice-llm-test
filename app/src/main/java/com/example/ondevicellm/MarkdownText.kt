package com.example.ondevicellm

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val TerminalBg = Color(0xFF1E1E1E)
private val TerminalFg = Color(0xFFD4D4D4)
private val TerminalMuted = Color(0xFF8A8A8A)

/** Markdown を描画する。コードブロックは黒背景のターミナル風の矩形、それ以外は 1 つの Text にまとめる */
@Composable
fun MarkdownContent(text: String, streaming: Boolean, fg: Color) {
    val lines = remember(text, streaming) { parseMarkdown(text, streaming) }
    // コードブロックを区切りにしてグループ化する
    val groups = remember(lines) {
        buildList<Any> {
            var run = mutableListOf<MdLine>()
            for (line in lines) {
                if (line is MdLine.CodeBlock) {
                    if (run.isNotEmpty()) add(run)
                    run = mutableListOf()
                    add(line)
                } else {
                    run += line
                }
            }
            if (run.isNotEmpty()) add(run)
        }
    }
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val muted = fg.copy(alpha = 0.7f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEachIndexed { i, g ->
            val cursor = streaming && i == groups.lastIndex
            if (g is MdLine.CodeBlock) {
                CodeBlockView(g, cursor)
            } else {
                @Suppress("UNCHECKED_CAST")
                val md = remember(g, fg, codeBg) {
                    (g as List<MdLine>).dropWhile { it == MdLine.Blank }.dropLastWhile { it == MdLine.Blank }
                        .toAnnotatedString(codeBg, muted)
                }
                Text(if (cursor) md + AnnotatedString(" ▍") else md, color = fg)
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdLine.CodeBlock, cursor: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TerminalBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.lang.ifEmpty { "code" },
                color = TerminalMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(block.lang, block.code)))
                    }
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "コピー", tint = TerminalMuted, modifier = Modifier.size(16.dp))
            }
        }
        // 折り返さずに横スクロールさせる
        Text(
            block.code + if (cursor) " ▍" else "",
            color = TerminalFg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

private fun List<MdLine>.toAnnotatedString(codeBg: Color, muted: Color) = buildAnnotatedString {
    fun spans(list: List<Span>) = list.forEach { s ->
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBg else Color.Unspecified,
            textDecoration = if (s.link) TextDecoration.Underline else null,
        )
        withStyle(style) { append(s.text) }
    }
    this@toAnnotatedString.forEachIndexed { i, line ->
        if (i > 0) append("\n")
        when (line) {
            MdLine.Blank -> {}
            MdLine.Rule -> withStyle(SpanStyle(color = muted)) { append("────────") }
            is MdLine.Text -> spans(line.spans)
            is MdLine.Heading -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (line.level <= 2) 1.15.em else 1.05.em)
            ) { spans(line.spans) }
            is MdLine.ListItem -> {
                append("    ".repeat(line.indent))
                withStyle(SpanStyle(color = muted)) {
                    append(if (line.marker == "•" && line.indent > 0) "◦" else line.marker)
                }
                append(" ")
                spans(line.spans)
            }
            is MdLine.Quote -> withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) {
                append("▎ ")
                spans(line.spans)
            }
            is MdLine.CodeBlock -> {} // MarkdownContent で別描画
        }
    }
}
