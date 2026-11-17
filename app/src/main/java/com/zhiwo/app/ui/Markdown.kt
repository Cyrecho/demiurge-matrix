package com.zhiwo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class ListBlock(val items: List<String>, val ordered: Boolean) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Math(val tex: String) : MdBlock()
    data object Rule : MdBlock()
}

private val headingRe = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRe = Regex("^\\s*[-*+]\\s+(.*)$")
private val orderedRe = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    var i = 0

    fun flushPara() {
        if (para.isNotBlank()) {
            out.add(MdBlock.Paragraph(para.toString().trim()))
            para.clear()
        }
    }

    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trim()

        if (t.startsWith("```")) {
            flushPara()
            val lang = t.removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            out.add(MdBlock.Code(lang, code.toString().trimEnd('\n')))
            continue
        }

        // 块级公式：$$ ... $$ 或 \[ ... \]
        if (t.startsWith("$$") || t.startsWith("\\[")) {
            val closer = if (t.startsWith("$$")) "$$" else "\\]"
            val openLen = if (t.startsWith("$$")) 2 else 2
            flushPara()
            val body = StringBuilder()
            if (t.length > openLen + closer.length && t.endsWith(closer)) {
                body.append(t.substring(openLen, t.length - closer.length))
                i++
            } else {
                body.append(t.substring(openLen))
                i++
                while (i < lines.size && !lines[i].trim().endsWith(closer)) {
                    body.append('\n').append(lines[i])
                    i++
                }
                if (i < lines.size) {
                    body.append('\n').append(lines[i].trim().removeSuffix(closer))
                    i++
                }
            }
            val tex = body.toString().trim()
            if (tex.isNotEmpty()) out.add(MdBlock.Math(tex))
            continue
        }

        if (t.isEmpty()) {
            flushPara()
            i++
            continue
        }

        val heading = headingRe.find(t)
        if (heading != null) {
            flushPara()
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            i++
            continue
        }

        if (ruleRe.matches(t)) {
            flushPara()
            out.add(MdBlock.Rule)
            i++
            continue
        }

        if (t.startsWith("|") && t.count { it == '|' } >= 2) {
            flushPara()
            val rows = ArrayList<String>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows.add(lines[i].trim())
                i++
            }
            val cells = rows
                .filterNot { r -> r.replace("|", "").replace("-", "").replace(":", "").isBlank() }
                .map { r -> r.trim().trimStart('|').trimEnd('|').split("|").map { it.trim() } }
            if (cells.isNotEmpty()) {
                out.add(MdBlock.Table(cells.first(), cells.drop(1)))
            }
            continue
        }

        if (t.startsWith(">")) {
            flushPara()
            val q = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                q.append(lines[i].trim().removePrefix(">").trim()).append('\n')
                i++
            }
            out.add(MdBlock.Quote(q.toString().trimEnd('\n')))
            continue
        }

        val bullet = bulletRe.find(raw)
        if (bullet != null) {
            flushPara()
            val items = ArrayList<String>()
            while (i < lines.size) {
                val m = bulletRe.find(lines[i]) ?: break
                items.add(m.groupValues[1])
                i++
            }
            out.add(MdBlock.ListBlock(items, ordered = false))
            continue
        }

        val ordered = orderedRe.find(raw)
        if (ordered != null) {
            flushPara()
            val items = ArrayList<String>()
            while (i < lines.size) {
                val m = orderedRe.find(lines[i]) ?: break
                items.add(m.groupValues[2])
                i++
            }
            out.add(MdBlock.ListBlock(items, ordered = true))
            continue
        }

        para.append(t).append(' ')
        i++
    }
    flushPara()
    return out
}

// ---------------- 行内 Markdown ----------------

private fun inlineMd(text: String, codeBg: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]

            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            if (c == '*' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            if (c == '~' && i + 1 < n && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            if (c == '[') {
                val close = text.indexOf("](", i)
                if (close > i) {
                    val end = text.indexOf(')', close + 2)
                    if (end > close) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(text.substring(i + 1, close))
                        }
                        i = end + 1
                        continue
                    }
                }
            }

            if (c == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            append(c)
            i++
        }
    }

// ---------------- 行内公式切分 ----------------

private sealed class Span {
    data class Text(val value: String) : Span()
    data class Formula(val value: String) : Span()
}

private fun splitMath(input: String): List<Span> {
    val out = ArrayList<Span>()
    val sb = StringBuilder()
    var i = 0

    fun flush() {
        if (sb.isNotEmpty()) {
            out.add(Span.Text(sb.toString()))
            sb.clear()
        }
    }

    while (i < input.length) {
        val c = input[i]

        if (c == '$' && i + 1 < input.length && input[i + 1] != '$') {
            val end = input.indexOf('$', i + 1)
            if (end > i + 1) {
                flush()
                out.add(Span.Formula(input.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }

        if (c == '\\' && i + 1 < input.length && input[i + 1] == '(') {
            val end = input.indexOf("\\)", i + 2)
            if (end > i) {
                flush()
                out.add(Span.Formula(input.substring(i + 2, end)))
                i = end + 2
                continue
            }
        }

        sb.append(c)
        i++
    }
    flush()
    return out
}

// ---------------- 渲染 ----------------

@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 15.5.sp,
    lineHeight: TextUnit = 24.sp,
    bodyColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val scheme = MaterialTheme.colorScheme

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height((textSize.value * 0.6f).dp))
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> textSize * 1.34f
                        2 -> textSize * 1.21f
                        3 -> textSize * 1.1f
                        else -> textSize * 1.04f
                    }
                    Text(
                        text = block.text,
                        fontSize = size,
                        lineHeight = size * 1.4f,
                        fontWeight = FontWeight.SemiBold,
                        color = bodyColor
                    )
                }

                is MdBlock.Paragraph -> RichText(
                    text = block.text,
                    fontSize = textSize,
                    lineHeight = lineHeight,
                    color = bodyColor
                )

                is MdBlock.Code -> CodeBlock(block.lang, block.code, textSize * 0.84f)

                is MdBlock.ListBlock -> Column {
                    block.items.forEachIndexed { idx, item ->
                        Row(Modifier.padding(vertical = (textSize.value * 0.08f).dp)) {
                            Text(
                                text = if (block.ordered) "${idx + 1}. " else "• ",
                                fontSize = textSize,
                                lineHeight = lineHeight,
                                color = scheme.onSurfaceVariant
                            )
                            RichText(
                                text = item,
                                fontSize = textSize,
                                lineHeight = lineHeight,
                                color = bodyColor
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height((textSize.value * 1.1f).dp)
                            .background(scheme.outline, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    RichText(
                        text = block.text,
                        fontSize = textSize * 0.97f,
                        lineHeight = lineHeight * 0.94f,
                        color = scheme.onSurfaceVariant
                    )
                }

                is MdBlock.Table -> TableBlock(block, textSize * 0.82f)

                is MdBlock.Math -> MathBlock(block.tex, textSize * 1.1f)

                MdBlock.Rule -> HorizontalDivider(color = scheme.outline)
            }
        }
    }
}

@Composable
private fun RichText(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color
) {
    val scheme = MaterialTheme.colorScheme
    val codeBg = scheme.surfaceVariant
    val linkColor = scheme.primary
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val argb = color.toArgb()
    val spans = remember(text) { splitMath(text) }

    val rendered = remember(spans, sizePx, argb, codeBg, linkColor) {
        val builder = AnnotatedString.Builder()
        val inline = LinkedHashMap<String, InlineTextContent>()
        var counter = 0
        spans.forEach { span ->
            when (span) {
                is Span.Text -> builder.append(inlineMd(span.value, codeBg, linkColor))
                is Span.Formula -> {
                    val bmp = MathRender.bitmap(span.value, sizePx, argb)
                    if (bmp == null) {
                        builder.append("\$" + span.value + "\$")
                    } else {
                        val id = "math${counter++}"
                        // 行内公式如果比正文高太多（带上下限的连乘、求和），会撑开行距显得突兀。
                        // 这里限高到 1.75 倍字号，超出的按比例缩小，保证它跟着正文走。
                        val maxHeight = sizePx * 1.75f
                        val scale = if (bmp.height > maxHeight) maxHeight / bmp.height else 1f
                        val wSp = with(density) { (bmp.width * scale).toDp().toSp() }
                        val hSp = with(density) { (bmp.height * scale).toDp().toSp() }
                        builder.appendInlineContent(id, span.value)
                        inline[id] = InlineTextContent(
                            Placeholder(wSp, hSp, PlaceholderVerticalAlign.Center)
                        ) { _ ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = span.value,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }
        builder.toAnnotatedString() to inline
    }

    BasicText(
        text = rendered.first,
        inlineContent = rendered.second,
        style = TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight)
    )
}

@Composable
private fun MathBlock(tex: String, fontSize: TextUnit) {
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    val sizePx = with(density) { fontSize.toPx() }
    val argb = color.toArgb()
    val bmp = remember(tex, sizePx, argb) { MathRender.bitmap(tex, sizePx, argb) }

    if (bmp == null) {
        Text(text = tex, fontFamily = FontFamily.Monospace, fontSize = fontSize)
        return
    }

    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = tex,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun CodeBlock(lang: String, code: String, fontSize: TextUnit) {
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.ifBlank { "code" },
                    fontSize = fontSize * 0.85f,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    text = "复制",
                    fontSize = fontSize * 0.85f,
                    color = scheme.primary,
                    modifier = Modifier
                        .clickable { clipboard.setText(AnnotatedString(code)) }
                        .padding(4.dp)
                )
            }
            Box(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.45f,
                    color = scheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table, fontSize: TextUnit) {
    val scheme = MaterialTheme.colorScheme
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    Surface(color = scheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                table.header.forEach { cell ->
                    Text(
                        text = cell,
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp),
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface
                    )
                }
                repeat(columns - table.header.size) { Spacer(Modifier.weight(1f)) }
            }
            HorizontalDivider(color = scheme.outline, modifier = Modifier.padding(vertical = 4.dp))
            table.rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { cell ->
                        // 单元格里也会有 $x^2$ 这类公式，用纯 Text 会把源码直接露出来
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(4.dp)
                        ) {
                            RichText(
                                text = cell,
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.4f,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
