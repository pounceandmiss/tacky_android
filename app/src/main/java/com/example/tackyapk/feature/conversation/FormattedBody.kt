package com.example.tackyapk.feature.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.serialization.Serializable

/**
 * One styling span the backend parsed out of a message: a type plus a
 * [offset, offset+length) range over the displayed text (caption else body).
 * The type may be compound (e.g. "bold.italic"), matching tacky's
 * messagestyling::ResolveEntities, which joins overlapping styles with ".".
 */
@Serializable
data class FormatSpan(val type: String = "", val offset: Int = 0, val length: Int = 0)

// http/https URLs, with optional paired angle-bracket delimiters. News bots wrap
// bare URLs as "< https://host/path >" (a plaintext URL-delimiter convention);
// once the URL is a real link those brackets read as litter, so a paired set is
// stripped from the displayed text. group(2) is always the URL itself.
private val URL_REGEX = Regex("""(<\s*)?(https?://[^\s<>]+)(\s*>)?""")

private fun styleFor(type: String, quoteColor: Color): SpanStyle? = when (type) {
    "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
    "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
    "overstrike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "monospace", "preformatted" -> SpanStyle(fontFamily = FontFamily.Monospace)
    // The "> " marker is already in the text, so a quote just needs a muted tint -
    // not a shouty fill - to read as quoted.
    "quote" -> SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic)
    else -> null
}

private class LinkRange(val start: Int, val end: Int, val url: String)

/**
 * Render text with the backend's formatting spans plus client-side URL autolinking
 * as a styled AnnotatedString. [quoteColor]/[linkColor] come from the theme at the
 * call site (defaults are sane for tests).
 *
 * Mirrors the desktop GUI's renderer (one style per entity over its char range)
 * with two deliberate departures: quotes are muted+italic rather than green+indent,
 * and URLs are autolinked (desktop has none; XEP-0393 carries no link entity).
 * Compound entity types ("bold.italic") are split on "." so each style applies,
 * matching tacky's compound output.
 */
fun formattedBody(
    text: String,
    spans: List<FormatSpan>,
    quoteColor: Color = Color(0xFF6E6E6E),
    linkColor: Color = Color(0xFF1A73E8),
): AnnotatedString {
    // Build the displayed text (paired <...> stripped around URLs) and a map from
    // original char offsets to displayed offsets, so backend spans still land right.
    val sb = StringBuilder(text.length)
    val map = IntArray(text.length + 1)
    val links = mutableListOf<LinkRange>()
    var orig = 0

    fun keep(upTo: Int) {
        while (orig < upTo) {
            map[orig] = sb.length
            sb.append(text[orig])
            orig++
        }
    }
    fun drop(upTo: Int) {
        while (orig < upTo) {
            map[orig] = sb.length
            orig++
        }
    }

    for (m in URL_REGEX.findAll(text)) {
        keep(m.range.first)
        val url = m.groups[2]!!
        val paired = m.groups[1] != null && m.groups[3] != null
        if (paired) drop(url.range.first) else keep(url.range.first)
        val linkStart = sb.length
        keep(url.range.last + 1)
        val linkEnd = sb.length
        if (paired) drop(m.range.last + 1) else keep(m.range.last + 1)
        links += LinkRange(linkStart, linkEnd, url.value)
    }
    keep(text.length)
    map[text.length] = sb.length
    val display = sb.toString()

    return buildAnnotatedString {
        append(display)
        for (span in spans) {
            val start = map[span.offset.coerceIn(0, text.length)]
            val end = map[(span.offset + span.length).coerceIn(0, text.length)]
            if (start >= end) continue
            for (part in span.type.split('.')) {
                val style = styleFor(part, quoteColor) ?: continue
                addStyle(style, start, end)
            }
        }
        for (l in links) {
            addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), l.start, l.end)
            addLink(LinkAnnotation.Url(l.url), l.start, l.end)
        }
    }
}
